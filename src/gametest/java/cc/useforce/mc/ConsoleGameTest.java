package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.TerminalBuffer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The real client against a demo Force (scripts/demo_force.py, started by scripts/gametest.sh): the list on the
 * game screen, the console, typing into a session, switching, closing. Screenshots of each step land in
 * run/clientGameTest/screenshots (the README and the PR show them).
 */
public class ConsoleGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext ctx) {
        ctx.getInput().resizeWindow(1600, 900);          // a laptop-sized window: the text size is part of the test
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            ForceClient mod = ForceClient.instance();
            ctx.waitFor(mc -> mod.model().status() == ForceModel.Status.CONNECTED
                    && mod.model().state().live().size() >= 3, 20 * 30);
            ctx.waitTicks(10);
            ctx.takeScreenshot("1-game-screen-list");

            ctx.getInput().pressKey(InputConstants.KEY_F12);
            ctx.waitForScreen(ConsoleScreen.class);
            ctx.waitFor(mc -> text(mod).contains("demo session"), 20 * 15);
            // the console asks Force for a terminal its own size: the pane follows (the demo panes are 100x30)
            ctx.waitFor(mc -> mod.terminal().buffer().snapshot().cols() != 100, 20 * 10);
            ctx.waitTicks(5);
            ctx.takeScreenshot("2-console");

            ctx.getInput().typeChars("echo hello from minecraft");
            ctx.getInput().pressKey(InputConstants.KEY_RETURN);
            ctx.waitFor(mc -> text(mod).contains("hello from minecraft\n") || count(text(mod), "hello from minecraft") >= 2,
                    20 * 15);
            ctx.waitTicks(5);
            ctx.takeScreenshot("3-typed");

            String before = mod.terminal().address();
            // Ctrl+Shift+Down: the test input sends no modifiers (Fabric's TestInput passes 0), so the key event goes
            // to the console's own handler as GLFW would deliver it
            ctx.runOnClient(mc -> mc.gui.screen().keyPressed(
                    new KeyEvent(InputConstants.KEY_DOWN, 0, InputConstants.MOD_CONTROL | InputConstants.MOD_SHIFT)));
            ctx.waitFor(mc -> mod.terminal().address() != null && !mod.terminal().address().equals(before), 20 * 10);
            ctx.waitTicks(20);
            ctx.takeScreenshot("4-switched");

            // an agent at work (the demo's api session): what the terminal is for
            String api = mod.model().state().live().get(0).address();
            ctx.runOnClient(mc -> mod.controller().handle(new cc.useforce.mc.core.Intent.Select(api)));
            ctx.waitFor(mc -> text(mod).contains("4 passed"), 20 * 10);
            ctx.waitTicks(20);
            ctx.takeScreenshot("5-agent");

            ctx.getInput().pressKey(InputConstants.KEY_F12);
            ctx.waitFor(mc -> mc.gui.screen() == null, 20 * 5);
            if (mod.consoleOpen()) throw new AssertionError("the console key closes the console");
        }
    }

    private static String text(ForceClient mod) {
        TerminalBuffer.Snapshot s = mod.terminal().buffer().snapshot();
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
