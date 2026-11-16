package cc.useforce.mc;

import cc.useforce.mc.core.ApiConnection;
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
import org.lwjgl.glfw.GLFW;

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
    private Supervisor supervisor;
    private Controller controller;
    private KeyMapping consoleKey, hudKey, attentionKey;
    private boolean hudVisible;
    private ConsoleScreen console;
    private boolean wasActive = true;

    @Override
    public void onInitializeClient() {
        instance = this;
        config = Config.load();
        hudVisible = config.hud;
        terminal = new CellsBackend(() -> supervisor.connection());
        supervisor = new Supervisor(config.expandedCommand(), model, e -> {
            if (!model.accept(e)) terminal.accept(e);
        }, ApiConnection::process);
        controller = new Controller(model, selection, terminal, () -> supervisor.connection(), this);
        supervisor.start();

        KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("force_mc", "force"));
        consoleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.force_mc.console", GLFW.GLFW_KEY_F12, cat));
        hudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.force_mc.hud", GLFW.GLFW_KEY_F10, cat));
        attentionKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.force_mc.attention", InputConstants.UNKNOWN.getValue(), cat));

        Hud hud = new Hud(this);
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("force_mc", "sessions"), hud::draw);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (consoleKey.consumeClick()) controller.handle(new Intent.ToggleConsole());
        while (hudKey.consumeClick()) controller.handle(new Intent.ToggleHud());
        while (attentionKey.consumeClick()) {
            controller.handle(new Intent.NextAttention());
            if (!consoleOpen()) controller.handle(new Intent.OpenConsole());
        }
        // an unfocused window: nobody looks at the console, so the session is not "viewed" (Force SPEC §54)
        boolean active = mc.isWindowActive();
        if (consoleOpen() && active != wasActive) terminal.setHidden(!active);
        wasActive = active;
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

    // --- Controller.Ui
    @Override
    public boolean consoleOpen() { return console != null; }

    @Override
    public void openConsole() {
        if (console != null) return;
        console = new ConsoleScreen(this);
        Minecraft.getInstance().gui.setScreen(console);
    }

    @Override
    public void closeConsole() {
        Minecraft mc = Minecraft.getInstance();
        if (console != null && mc.gui.screen() == console) mc.gui.setScreen(null);
        console = null;
    }

    @Override
    public void toggleHud() { hudVisible = !hudVisible; }

    /** The console screen went away (closed, or replaced by another screen). */
    void consoleRemoved() {
        console = null;
        terminal.setHidden(true);
    }

    // --- for the layouts
    public ForceModel model() { return model; }
    Selection selection() { return selection; }
    public CellsBackend terminal() { return terminal; }
    public Controller controller() { return controller; }
    Config config() { return config; }
    boolean hudVisible() { return hudVisible; }

    boolean isConsoleKey(KeyEvent e) { return consoleKey.matches(e); }

    String consoleKeyName() { return consoleKey.getTranslatedKeyMessage().getString(); }
}
