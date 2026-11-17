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

    /** A real world for the README's pictures: this seed's nearest eroded badlands, Force's oranges. */
    private static final String SEED = "useforce";

    private static void scenery(ClientGameTestContext ctx, TestSingleplayerContext world) {
        // the ground (not the treetops) in this seed's nearest eroded badlands
        net.minecraft.core.BlockPos spot = world.getServer().computeOnServer(server -> {
            var level = server.overworld();
            var found = level.findClosestBiome3d(b -> b.is(net.minecraft.world.level.biome.Biomes.ERODED_BADLANDS),
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
        if (spot != null)                                              // looking up at the terracotta spires
            world.getServer().runCommand("tp @a " + (spot.getX() + 0.5) + " " + spot.getY() + " " + (spot.getZ() + 0.5) + " 135 -8");
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
        // a web link in the session view: Ctrl+click opens it (and picks nothing in the list)
        ctx.getInput().typeChars("echo see https://useforce.cc/docs.");
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.terminal().buffer().snapshot()), "https://useforce.cc/docs") >= 2, 20 * 15);
        ctx.waitTicks(2);
        double[] link = ctx.computeOnClient(mc -> {
            var s = mod.terminal().buffer().snapshot();
            var g = ((ConsoleScreen) mc.gui.screen()).linkGrid();
            for (int r = s.rows() - 1; r >= 0 && g != null; r--) {
                String cells = cc.useforce.mc.core.Links.cells(s.lines().get(r));
                int i = cells.indexOf("see https://useforce.cc/docs");
                if (i < 0 || cells.contains("echo ")) continue;
                var l = cc.useforce.mc.core.Links.at(s, r, i + 8);
                if (l == null || !l.url().equals("https://useforce.cc/docs"))
                    throw new AssertionError("session view link: " + l + " in " + cells);
                return new double[]{g.x() + (i + 8.5) * g.cw(), g.y() + (r + 0.5) * g.ch(), mc.getWindow().getGuiScale()};
            }
            throw new AssertionError("no link in the session view:\n" + text(s));
        });
        ctx.getInput().setCursorPos(link[0], link[1]);
        ctx.waitTicks(5);
        ctx.takeScreenshot("2a-session-link-hover");
        ctx.runOnClient(mc -> {
            String picked = mod.terminal().address();
            boolean took = mc.gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(link[0] / link[2],
                    link[1] / link[2], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,
                    InputConstants.MOD_CONTROL)), false);
            if (!took || !(mc.gui.screen() instanceof ConsoleScreen) || !java.util.Objects.equals(picked, mod.terminal().address()))
                throw new AssertionError("Ctrl+click on a link in the session view");
        });
        ctx.getInput().setCursorPos(2, 2);
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
        // the text size of the README's pictures: in this 1600x900 window, full-size text leaves Force too few
        // columns for its full sidebar (it folds into the icon rail), and the checks below read the session names
        ctx.runOnClient(mc -> mod.config().textScale = 0.75);
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

        // a web link: hovered, it is underlined with a hint; Ctrl+click opens it and is not a click for Force
        ctx.getInput().typeChars("echo see https://useforce.cc/docs.");
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.app().snapshot()), "https://useforce.cc/docs") >= 2, 20 * 15);
        double[] at = appPoint(ctx, mod, "see https://useforce.cc/docs", 8, "https://useforce.cc/docs", false);
        ctx.getInput().setCursorPos(at[0], at[1]);
        ctx.waitTicks(5);
        ctx.takeScreenshot("4a-app-link-hover");
        ctx.runOnClient(mc -> {
            boolean took = mc.gui.screen().mouseClicked(new net.minecraft.client.input.MouseButtonEvent(at[0] / at[2],
                    at[1] / at[2], new net.minecraft.client.input.MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT,
                    InputConstants.MOD_CONTROL)), false);
            if (!took || !(mc.gui.screen() instanceof AppScreen)) throw new AssertionError("Ctrl+click on a link");
        });
        // a link the shell wraps onto the next lines is one link: hovered on its second line, the whole address
        String longUrl = "https://useforce.cc/wrap/" + "0123456789".repeat(22) + "/end";
        ctx.getInput().typeChars("echo " + longUrl);
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.app().snapshot()), "/end") >= 2, 20 * 15);
        double[] wrapped = appPoint(ctx, mod, "https://useforce.cc/wrap/", -1, longUrl, false);
        ctx.getInput().setCursorPos(wrapped[0], wrapped[1]);
        ctx.waitTicks(5);
        ctx.takeScreenshot("4b-app-wrapped-link-hover");
        // a link the program names itself (OSC 8, as Claude Code prints them), through Force's tmux: its address
        ctx.getInput().typeChars("printf '\\e]8;;https://useforce.cc/named\\e\\\\the docs\\e]8;;\\e\\\\\\n'");
        ctx.getInput().pressKey(InputConstants.KEY_RETURN);
        ctx.waitFor(mc -> count(text(mod.app().snapshot()), "the docs") >= 2, 20 * 15);
        double[] named = appPoint(ctx, mod, "the docs", 2, "https://useforce.cc/named", true);
        ctx.getInput().setCursorPos(named[0], named[1]);
        ctx.waitTicks(5);
        ctx.takeScreenshot("4c-app-named-link-hover");
        ctx.getInput().setCursorPos(2, 2);

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

    /**
     * Where on the window (pixels; and the GUI scale) a link in the app is: the last line of output (not the command)
     * with `start`, `dx` cells into it, or, with dx < 0, the first cell of the line below it (a wrapped link's
     * second line). The link there must be `url`, named by the program (OSC 8) or not as `marked` says.
     */
    private static double[] appPoint(ClientGameTestContext ctx, ForceClient mod, String start, int dx, String url,
                                     boolean marked) {
        return ctx.computeOnClient(mc -> {
            var s = mod.app().snapshot();
            for (int r = s.rows() - 1; r >= 0; r--) {
                String cells = cc.useforce.mc.core.Links.cells(s.lines().get(r));
                int i = cells.indexOf(start);
                if (i < 0 || cells.contains("echo ") || cells.contains("printf ")) continue;   // the output, not the command
                int row = dx < 0 ? r + 1 : r, col = dx < 0 ? i : i + dx;
                var l = cc.useforce.mc.core.Links.at(s, row, col);
                if (l == null || !l.url().equals(url) || l.marked() != marked)
                    throw new AssertionError("link at " + row + "," + col + ": " + l + "\n" + text(s));
                if (dx < 0 && l.spans().size() < 2) throw new AssertionError("not joined: " + l);
                var w = mc.getWindow();
                var lay = AppScreen.layout(mod.config(), w.getGuiScale(), w.getWidth(), w.getHeight());
                return new double[]{lay.x() + col * lay.cw() + lay.cw() / 2.0, lay.y() + row * lay.ch() + lay.ch() / 2.0,
                        w.getGuiScale()};
            }
            throw new AssertionError("no '" + start + "' on screen:\n" + text(s));
        });
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
