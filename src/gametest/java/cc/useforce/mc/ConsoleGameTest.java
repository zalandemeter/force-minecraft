package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.Intent;
import cc.useforce.mc.core.TerminalBuffer;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.input.KeyEvent;

/**
 * The real client against a real Force (scripts/demo_force.py: a Force release, three shell sessions, its daemon):
 * the game-screen list, the session view, the whole Force app on F12 (its own UI, typing, its menu, switching, emoji),
 * the settings and a key combination set in Controls.
 * Screenshots of each step land in build/run/clientGameTest/screenshots (the README shows some).
 */
public class ConsoleGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);
        try (TestSingleplayerContext world = ctx.worldBuilder().setUseConsistentSettings(false).adjustSettings(w -> {
            w.setSeed(SEED);
            w.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            w.setAllowCommands(true);
        }).create()) {
            world.getConnection().waitForChunksRender();
            scenery(ctx, world);
            ForceClient mod = ForceClient.instance();
            ctx.waitFor(mc -> mod.model().status() == ForceModel.Status.CONNECTED
                    && mod.model().state().live().size() >= 3, 20 * 60);
            ctx.waitTicks(10);
            ctx.takeScreenshot("1-game-screen-list");

            sessionView(ctx, mod);
            app(ctx, mod);
            settings(ctx, mod);
            combination(ctx, mod);
        }
    }

    /** A real world for the README's pictures: this seed's nearest cherry grove, a clear morning. */
    private static final String SEED = "useforce";

    private static void scenery(ClientGameTestContext ctx, TestSingleplayerContext world) {
        // the ground (not the treetops) next to this seed's nearest cherry grove
        net.minecraft.core.BlockPos spot = world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var found = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.CHERRY_GROVE),
                    net.minecraft.core.BlockPos.ZERO, 6400, 32, 64);
            if (found == null) return null;
            int x = found.getFirst().getX() + 1, z = found.getFirst().getZ();
            level.getChunk(x >> 4, z >> 4);                                            // generated, for its heights
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return new net.minecraft.core.BlockPos(x, y, z);
        });
        System.err.println("SCENERY " + spot);
        for (String c : new String[]{"gamerule sendCommandFeedback false", "gamerule send_command_feedback false",
                "weather clear", "time set 1500", "gamerule doDaylightCycle false",
                "gamerule advance_time false", "gamerule doWeatherCycle false", "gamerule advance_weather false"}) {
            try {
                world.getServer().runCommand(c);
            } catch (RuntimeException | AssertionError e) {
                // the rule's name differs between versions: one of each pair works
            }
        }
        if (spot != null)                                                              // facing the cherry trees
            world.getServer().runCommand("tp @a " + (spot.getX() + 0.5) + " " + spot.getY() + " " + (spot.getZ() + 0.5) + " 135 2");
        ctx.waitTicks(40);
        world.getConnection().waitForChunksRender();
        ctx.waitTicks(20);
    }

    /** The session view (native list + one session's terminal, cells mode). */
    private static void sessionView(ClientGameTestContext ctx, ForceClient mod) {
        ctx.runOnClient(mc -> mod.controller().handle(new Intent.OpenSessions()));
        ctx.waitForScreen(ConsoleScreen.class);
        try {
            ctx.waitFor(mc -> text(mod.terminal().buffer().snapshot()).contains("retry"), 20 * 15);
        } catch (AssertionError e) {
            ctx.takeScreenshot("x-session-view");
            System.err.println("SESSION VIEW " + mod.terminal().address() + " problem=" + mod.terminal().problem()
                    + ":\n" + text(mod.terminal().buffer().snapshot()));
            throw e;
        }
        ctx.waitFor(mc -> mod.terminal().buffer().snapshot().cols() != 200, 20 * 10);   // it took the view's size
        ctx.getInput().typeChars("echo hello from minecraft");
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.terminal().buffer().snapshot()), "hello from minecraft") >= 2, 20 * 15);
        ctx.waitTicks(5);
        ctx.takeScreenshot("2-session-view");
        String before = mod.terminal().address();
        // Ctrl+Shift+Down: Fabric's test input sends no modifiers, so the event goes to the handler as GLFW/SDL would
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(
                new KeyEvent(InputConstants.KEY_DOWN, 0, InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT)));
        ctx.waitFor(mc -> mod.terminal().address() != null && !mod.terminal().address().equals(before), 20 * 10);
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
    }

    /** F12: the whole Force app, the same program a terminal runs. */
    private static void app(ClientGameTestContext ctx, ForceClient mod) {
        // the app started hidden as soon as Force was connected: the first F12 shows it at once
        ctx.waitFor(mc -> mod.app().open(), 20 * 10);
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitForScreen(AppScreen.class);
        // Force's own ui: its sidebar lists the sessions, its main pane shows one of them
        try {
            ctx.waitFor(mc -> {
                String t = text(mod.app().snapshot());
                return t.contains("api") && t.contains("web") && t.contains("docs") && t.contains("$");
            }, 20 * 30);
        } catch (AssertionError e) {
            ctx.takeScreenshot("x-app-timeout");
            System.err.println("APP PROBLEM: " + mod.app().problem());
            System.err.println("APP SCREEN:\n" + text(mod.app().snapshot()));
            throw e;
        }
        ctx.waitTicks(20);
        ctx.takeScreenshot("3-app");
        if (mod.app().vt().mouseMode() == 0) throw new AssertionError("Force asks for the mouse (its sidebar is clickable)");

        ctx.getInput().typeChars("echo typed into force");
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.app().snapshot()), "typed into force") >= 2, 20 * 15);

        // Ctrl+Space: Force's menu (the test input has no modifiers: the event goes to the screen as SDL/GLFW sends it)
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(
                new KeyEvent(InputConstants.KEY_SPACE, 0, InputConstants.MOD_CONTROL)));
        ctx.waitFor(mc -> text(mod.app().snapshot()).contains("Jump to session"), 20 * 10);
        ctx.waitTicks(10);
        ctx.takeScreenshot("4-app-menu");
        // Esc is Force's own key: it closes Force's menu, the view stays
        ctx.getInput().pressKey(InputConstants.KEY_ESCAPE);
        ctx.waitFor(mc -> !text(mod.app().snapshot()).contains("Jump to session"), 20 * 10);
        if (!(ctx.computeOnClient(mc -> mc.gui.screen()) instanceof AppScreen)) throw new AssertionError("still shown");

        // Alt+3: the third session, as in any terminal
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_3, 0, InputConstants.MOD_ALT)));
        waitApp(ctx, mod, "alt-3", t -> t.contains("finished: 3 files changed") && t.contains("\u26A0\uFE0F"));
        ctx.waitTicks(10);
        ctx.takeScreenshot("5-app-switched-emoji");

        // F12 hides it and shows it again: the same app, as it was (still on docs), not a fresh one
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        if (!mod.app().open() || !mod.app().hidden()) throw new AssertionError("hidden, the app keeps running");
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitForScreen(AppScreen.class);
        waitApp(ctx, mod, "shown-again", t -> t.contains("finished: 3 files changed"));
        if (mod.app().hidden()) throw new AssertionError("shown again");

        // a laptop's high-DPI panel: larger text, the app follows the size
        ctx.getInput().resizeWindow(2880, 1800);
        ctx.waitTicks(60);
        ctx.takeScreenshot("6-app-hidpi");
        ctx.getInput().resizeWindow(1600, 900);
        ctx.waitTicks(20);

        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        ctx.waitTicks(10);
        ctx.takeScreenshot("7-game-screen-active-only");
    }

    /** The settings (Mod Menu's Configure, or a key): see-through glass, padding, the list elsewhere. */
    private static void settings(ClientGameTestContext ctx, ForceClient mod) {
        ctx.runOnClient(mc -> mc.gui.setScreen(new SettingsScreen(null, mod)));
        ctx.waitForScreen(SettingsScreen.class);
        ctx.waitTicks(10);
        ctx.takeScreenshot("8-settings");
        ctx.runOnClient(mc -> {
            Config c = mod.config();
            c.terminalOpacity = 0.8;
            c.sidebarOpacity = 0.6;
            c.sidebarPosition = "top-left";
            c.terminalPadding = 10;
            c.textScale = 0.75;
            c.blur = true;
            mc.gui.setScreen(null);
        });
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        ctx.waitTicks(10);
        ctx.takeScreenshot("9-list-top-left-clear");
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitForScreen(AppScreen.class);
        ctx.waitTicks(20);
        // Alt+1: the agent's session, for the README's picture
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_1, 0, InputConstants.MOD_ALT)));
        waitApp(ctx, mod, "alt-1", t -> t.contains("4 passed"));
        ctx.waitTicks(20);
        ctx.takeScreenshot("10-app-glass-padded");
        // no padding: the glass runs to the screen's edges
        ctx.runOnClient(mc -> {
            mod.config().terminalPadding = 0;
            mod.config().terminalOpacity = 1;
            var l = AppScreen.layout(mod.config(), mc.getWindow().getGuiScale(), mc.getWindow().getWidth(),
                    mc.getWindow().getHeight());
            if (l.gx() != 0 || l.gy() != 0 || l.gw() != mc.getWindow().getWidth() || l.gh() != mc.getWindow().getHeight())
                throw new AssertionError("padding 0: the glass covers the screen, not " + l);
        });
        ctx.waitTicks(10);
        ctx.takeScreenshot("10b-app-no-padding");
        ctx.getInput().pressKey(InputConstants.KEY_F12);
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        ctx.runOnClient(mc -> {
            Config d = new Config();
            Config c = mod.config();
            c.terminalOpacity = d.terminalOpacity;
            c.sidebarOpacity = d.sidebarOpacity;
            c.sidebarPosition = d.sidebarPosition;
            c.terminalPadding = d.terminalPadding;
            c.textScale = d.textScale;
            c.blur = d.blur;
        });
    }

    /** Controls → Key Binds: Ctrl+F9 for Force's view (Ctrl alone waits for the key), then only Ctrl+F9 works. */
    private static void combination(ClientGameTestContext ctx, ForceClient mod) {
        ctx.runOnClient(mc -> {
            var s = new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options);
            mc.gui.setScreen(s);
            s.selectedKey = mod.consoleKey();
            s.keyPressed(new KeyEvent(InputConstants.KEY_LCONTROL, 0, InputConstants.MOD_CONTROL));
            if (s.selectedKey != mod.consoleKey()) throw new AssertionError("Ctrl alone waits for the key");
            s.keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, InputConstants.MOD_CONTROL));
            if (s.selectedKey != null) throw new AssertionError("Ctrl+F9 set");
        });
        ctx.waitTicks(5);
        ctx.takeScreenshot("11-controls-combination");
        String shown = ctx.computeOnClient(mc -> mod.consoleKey().getTranslatedKeyMessage().getString());
        if (!shown.equals("Ctrl + F9")) throw new AssertionError("shown as " + shown);
        if (!"ctrl".equals(mod.config().keyModifiers.get("key.force_mc.console")))
            throw new AssertionError("saved: " + mod.config().keyModifiers);
        ctx.runOnClient(mc -> mc.gui.setScreen(null));
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        ctx.runOnClient(mc -> mod.controller().handle(new Intent.OpenConsole()));
        ctx.waitForScreen(AppScreen.class);
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(new KeyEvent(InputConstants.KEY_F9, 0, 0)));
        ctx.waitTicks(5);
        if (!(ctx.computeOnClient(mc -> mc.gui.screen()) instanceof AppScreen))
            throw new AssertionError("F9 alone is Force's");
        ctx.runOnClient(mc -> mc.gui.screen().keyPressed(
                new KeyEvent(InputConstants.KEY_F9, 0, InputConstants.MOD_CONTROL)));
        ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
        ctx.runOnClient(mc -> {                         // back to F12
            var s = new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options);
            mc.gui.setScreen(s);
            s.selectedKey = mod.consoleKey();
            s.keyPressed(new KeyEvent(InputConstants.KEY_F12, 0, 0));
            mc.gui.setScreen(null);
        });
        if (!mod.config().keyModifiers.isEmpty()) throw new AssertionError("reset: " + mod.config().keyModifiers);
    }

    private static void waitApp(ClientGameTestContext ctx, ForceClient mod, String name,
                                java.util.function.Predicate<String> ok) {
        try {
            ctx.waitFor(mc -> ok.test(text(mod.app().snapshot())), 20 * 15);
        } catch (AssertionError e) {
            ctx.takeScreenshot("x-" + name);
            System.err.println("APP SCREEN " + name + ":\n" + text(mod.app().snapshot()));
            throw e;
        }
    }

    private static String text(TerminalBuffer.Snapshot s) {
        StringBuilder b = new StringBuilder();
        for (var line : s.lines()) {
            for (var r : line) b.append(r.text());
            b.append('\n');
        }
        return b.toString();
    }

    private static int count(String s, String sub) {
        int n = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }
}
