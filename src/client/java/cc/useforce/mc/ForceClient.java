package cc.useforce.mc;

import cc.useforce.mc.core.ApiConnection;
import cc.useforce.mc.core.AppTerminal;
import cc.useforce.mc.core.CellsBackend;
import cc.useforce.mc.core.Controller;
import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.Intent;
import cc.useforce.mc.core.Selection;
import cc.useforce.mc.core.Supervisor;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Wiring: config, the `force api` supervisor, the model, the terminal backend, the controller, and the Minecraft
 * pieces (key bindings, the HUD layout, the console screen, toasts). Each is replaceable on its own.
 */
public final class ForceClient implements ClientModInitializer, Controller.Ui {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(6000L);
    private static ForceClient instance;

    /** The mod (for the client game tests). */
    public static ForceClient instance() { return instance; }

    private Config config;
    private final ForceModel model = new ForceModel();
    private final Selection selection = new Selection();
    private CellsBackend terminal;
    private AppTerminal app;
    private Supervisor supervisor;
    private Controller controller;
    private ComboKey consoleKey, hudKey, attentionKey, sessionsKey, settingsKey;
    private boolean hudVisible;
    private net.minecraft.client.gui.screens.Screen console;     // AppScreen or ConsoleScreen while shown
    private boolean wasActive = true;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = Config.load();
        hudVisible = config.hud;
        terminal = new CellsBackend(() -> supervisor.connection());
        app = new AppTerminal(() -> supervisor.connection());
        supervisor = new Supervisor(config.expandedCommand(), model, e -> {
            if (!model.accept(e) && !app.accept(e)) terminal.accept(e);
        }, ApiConnection::process);
        controller = new Controller(model, selection, terminal, () -> supervisor.connection(), this);
        supervisor.start();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("force_mc", "force"));
        // in Controls → Key Binds → Force; each can be a combination (Ctrl, Alt, Shift + a key)
        consoleKey = key("key.force_mc.console", InputConstants.KEY_F12, cat);
        hudKey = key("key.force_mc.hud", InputConstants.UNKNOWN.getValue(), cat);
        attentionKey = key("key.force_mc.attention", InputConstants.UNKNOWN.getValue(), cat);
        sessionsKey = key("key.force_mc.sessions", InputConstants.UNKNOWN.getValue(), cat);
        settingsKey = key("key.force_mc.settings", InputConstants.KEY_F10, cat);

        Hud hud = new Hud(this);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("force_mc", "sessions"), hud::draw);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private ComboKey key(String name, int key, KeyMapping.Category cat) {
        int mods = ComboKey.fromConfig(config.keyModifiers.get(name));
        ComboKey k = new ComboKey(name, key, cat, mods, changed -> {
            if (changed.mods() == 0) config.keyModifiers.remove(changed.getName());
            else config.keyModifiers.put(changed.getName(), ComboKey.toConfig(changed.mods()));
            config.save();
        });
        KeyMappingHelper.registerKeyMapping(k);
        return k;
    }

    private void tick(Minecraft mc) {
        if (consoleKey.consumeCombo()) controller.handle(new Intent.ToggleConsole());
        if (hudKey.consumeCombo()) controller.handle(new Intent.ToggleHud());
        if (attentionKey.consumeCombo()) {
            controller.handle(new Intent.NextAttention());
            if (!consoleOpen()) controller.handle(new Intent.OpenSessions());
        }
        if (sessionsKey.consumeCombo()) controller.handle(new Intent.OpenSessions());
        if (settingsKey.consumeCombo() && mc.gui.screen() == null) mc.gui.setScreen(new SettingsScreen(null, this));
        // an unfocused window: nobody looks at the console, so the session is not "viewed" (Force SPEC §54)
        boolean active = mc.isWindowActive();
        if (console instanceof ConsoleScreen && active != wasActive) terminal.setHidden(!active);
        wasActive = active;
        preloadApp(mc);
        ForceModel.Notice n;
        while ((n = model.pollNotice()) != null) {
            if (!config.toasts || (consoleOpen() && n.address().equals(terminal.address()))) continue;
            String title = switch (n.kind()) {
                case "needs-you" -> "Needs you";
                case "finished" -> "Your turn";
                default -> "Stopped";
            };
            SystemToast.addOrUpdate(mc.gui.toastManager(), TOAST, Component.literal("⚡ " + title),
                    Component.literal(n.message()));
        }
    }

    /** The app starts hidden as soon as Force is connected, so the first F12 shows it at once. */
    private void preloadApp(Minecraft mc) {
        if (app.open() || model.status() != ForceModel.Status.CONNECTED || app.problem() != null) return;
        AppScreen.Layout l = AppScreen.layout(config, mc.getWindow().getGuiScale(), mc.getWindow().getWidth(),
                mc.getWindow().getHeight());
        app.setHidden(true);
        app.open(l.cols(), l.rows());
    }

    // --- Controller.Ui
    @Override
    public boolean consoleOpen() { return console != null; }

    @Override
    public void openConsole() {
        show(new AppScreen(this));
    }

    @Override
    public void openSessions() {
        show(new ConsoleScreen(this));
    }

    private void show(net.minecraft.client.gui.screens.Screen s) {
        if (console != null) return;
        console = s;
        Minecraft.getInstance().gui.setScreen(s);
    }

    @Override
    public void closeConsole() {
        Minecraft mc = Minecraft.getInstance();
        if (console != null && mc.gui.screen() == console) mc.gui.setScreen(null);
        console = null;
    }

    @Override
    public void toggleHud() { hudVisible = !hudVisible; }

    /** The console went away (closed, or replaced by another screen): the app ends, a session view hides, so
     * Force does not count the sessions as viewed while you play. */
    void consoleRemoved() {
        if (console instanceof AppScreen) app.setHidden(true);      // keeps running: back at once, as it was
        else terminal.setHidden(true);
        console = null;
    }

    // --- for the layouts
    public ForceModel model() { return model; }
    Selection selection() { return selection; }
    public CellsBackend terminal() { return terminal; }
    public AppTerminal app() { return app; }
    public Controller controller() { return controller; }
    Config config() { return config; }
    boolean hudVisible() { return hudVisible; }

    ComboKey consoleKey() { return consoleKey; }

    boolean isConsoleKey(KeyEvent e) { return consoleKey.matches(e); }

    /** Hides Force's view: the console key (F12, or the combination set in Controls). Esc is Force's own key; it
     * hides the view only while the console key is unbound, so the view can always be left. */
    boolean isCloseKey(KeyEvent e) {
        return consoleKey.matches(e) || (consoleKey.isUnbound() && e.isEscape() && ComboKey.fromEvent(e.modifiers()) == 0);
    }

    String consoleKeyName() { return consoleKey.getTranslatedKeyMessage().getString(); }
}
