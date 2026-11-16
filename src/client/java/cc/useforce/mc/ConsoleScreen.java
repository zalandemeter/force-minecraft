package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.Intent;
import cc.useforce.mc.core.Keys;
import cc.useforce.mc.core.TerminalBackend;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.SessionList;
import cc.useforce.mc.ui.TerminalView;
import cc.useforce.mc.ui.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The console layout: the session list on the left, the selected session's terminal on the right, both glass over
 * the running game (it never pauses). Every key goes to the terminal, Esc included; the console key closes it,
 * Ctrl+Shift+Up/Down step through the list, a click on the list picks, the wheel scrolls the terminal.
 */
public final class ConsoleScreen extends Screen {
    private final ForceClient mod;
    private final SessionList list = new SessionList();
    private final TerminalView view = new TerminalView();
    private final StringBuilder typed = new StringBuilder();
    private final Keys keys = new Keys(KeyTable.create());
    private int lx, ly, lw, lh, tx, ty, tw, th;

    ConsoleScreen(ForceClient mod) {
        super(Component.translatable("force_mc.console.title"));
        this.mod = mod;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    private void layout() {
        int m = 8;
        lw = Math.max(120, Math.min(200, width / 5));
        lx = m;
        ly = m;
        lh = height - 2 * m;
        tx = lx + lw + 6;
        ty = m;
        tw = width - tx - m;
        th = height - 2 * m;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x66000000);            // the game stays visible behind the glass
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        flushTyped();
        layout();
        ForceModel model = mod.model();
        ForceModel.State s = model.state();
        Panel.draw(g, lx, ly, lw, lh);
        g.text(font, Theme.mono("⚡"), lx + 5, ly + 4, Theme.CORAL, false);
        g.text(font, Theme.mono("Force"), lx + 15, ly + 4, Theme.TEXT, false);
        String head = Hud.header(model, s);
        g.text(font, Theme.mono(head), lx + lw - font.width(Theme.mono(head)) - 5, ly + 4,
                s.needsYou() > 0 ? Theme.RED : Theme.DIM, false);
        list.draw(g, font, lx + 1, ly + 16, lw - 2, lh - 30, s, mod.terminal().address(), false,
                s.hosts().size() > 1);
        g.text(font, Theme.mono(keyHint()), lx + 5, ly + lh - 11, Theme.DIM, false);

        Panel.draw(g, tx, ty, tw, th);
        ForceModel.Session cur = mod.terminal().address() == null ? null : s.find(mod.terminal().address());
        String title = cur == null ? Hud.empty(model) : cur.address() + "  ·  " + Theme.label(cur);
        g.text(font, Theme.mono(title), tx + 6, ty + 4, cur == null ? Theme.DIM : Theme.color(cur), false);
        TerminalBackend term = mod.terminal();
        String problem = term.problem();
        if (problem != null) g.text(font, Theme.mono(problem), tx + 6, ty + 16, Theme.RED, false);
        else {
            int gs = minecraft.getWindow().getGuiScale();
            double unit = mod.config().terminalScale;
            TerminalView.Grid grid = TerminalView.fit(tw - 4, th - 17, unit, gs);
            term.resize(grid.cols(), grid.rows());          // only sent when it changed
            view.draw(g, font, tx + 2, ty + 15, tw - 4, th - 17, term.buffer().snapshot(), unit, gs);
        }
    }

    private String keyHint() {
        return mod.consoleKeyName() + " close · ^⇧↑↓ switch";
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (mod.isConsoleKey(e)) {
            flushTyped();
            mod.controller().handle(new Intent.CloseConsole());
            return true;
        }
        int mods = e.modifiers();
        boolean up = e.key() == InputConstants.KEY_UP, down = e.key() == InputConstants.KEY_DOWN;
        if (keys.ctrl(mods) && keys.shift(mods) && (up || down)) {
            flushTyped();
            mod.controller().handle(new Intent.Step(up ? -1 : 1));
            return true;
        }
        String name = keys.name(e.key(), mods);
        if (name != null) {
            flushTyped();
            mod.controller().handle(new Intent.Keys(List.of(name)));
        }
        return true;                                        // every key is the terminal's
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        typed.append(e.codepointAsString());                // sent once per frame: fast typing is one request
        return true;
    }

    private void flushTyped() {
        if (typed.isEmpty()) return;
        String t = typed.toString();
        typed.setLength(0);
        mod.controller().handle(new Intent.Keys(List.of(new TerminalBackend.Text(t))));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (e.x() >= lx && e.x() < lx + lw) {
            String a = list.at(e.y());
            if (a != null) mod.controller().handle(new Intent.Select(a));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return true;
        if (mx >= lx && mx < lx + lw) mod.controller().handle(new Intent.Step(sy > 0 ? -1 : 1));
        else mod.controller().handle(new Intent.Scroll(sy > 0 ? -3 : 3));
        return true;
    }

    @Override
    public void removed() {
        mod.consoleRemoved();
    }
}
