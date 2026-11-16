package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.Intent;
import cc.useforce.mc.core.Keys;
import cc.useforce.mc.core.TerminalBackend;
import cc.useforce.mc.ui.Canvas;
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
    private int lx, ly, lw, lh, tx, ty, tw, th;     // screen pixels
    private int scale = 1;                          // screen pixels per GUI unit

    ConsoleScreen(ForceClient mod) {
        super(Component.translatable("force_mc.console.title"));
        this.mod = mod;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    private int px, tpx;            // the list's and the terminal's text sizes (screen pixels)

    private void layout(Canvas c) {
        px = mod.config().uiFontPx;
        tpx = mod.config().terminalFontPx;
        int m = px;
        lw = Math.max(px * 14, Math.min(px * 22, c.width() / 5));
        lx = m;
        ly = m;
        lh = c.height() - 2 * m;
        tx = lx + lw + px / 2;
        ty = m;
        tw = c.width() - tx - m;
        th = c.height() - 2 * m;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x66000000);            // the game stays visible behind the glass
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        flushTyped();
        Canvas c = new Canvas(g, font, minecraft.getWindow().getGuiScale());
        scale = c.scale;
        layout(c);
        ForceModel model = mod.model();
        ForceModel.State s = model.state();
        c.begin();
        Panel.draw(c, lx, ly, lw, lh);
        Hud.header(c, model, s, lx, ly, lw, px);
        int head = Theme.line(px) + px;
        list.draw(c, lx + 1, ly + head, lw - 2, lh - head - Theme.line(px) - px / 2, s, mod.terminal().address(), false,
                s.hosts().size() > 1, px);
        c.text(keyHint(), lx + px / 2, ly + lh - Theme.line(px) - px / 4, px, Theme.DIM, false);

        Panel.draw(c, tx, ty, tw, th);
        ForceModel.Session cur = mod.terminal().address() == null ? null : s.find(mod.terminal().address());
        String title = cur == null ? Hud.empty(model) : cur.address() + "  ·  " + Theme.label(cur);
        c.text(title, tx + px / 2, ty + px / 2, px, cur == null ? Theme.DIM : Theme.color(cur), cur != null);
        TerminalBackend term = mod.terminal();
        String problem = term.problem();
        int vy = ty + head, vh = th - head - px / 2, vx = tx + px / 2, vw = tw - px;
        if (problem != null) c.text(problem, vx, vy, px, Theme.RED, false);
        else {
            TerminalView.Grid grid = TerminalView.fit(c, vw, vh, tpx);
            term.resize(grid.cols(), grid.rows());          // only sent when it changed
            view.draw(c, vx, vy, vw, vh, term.buffer().snapshot(), tpx);
        }
        c.end();
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
        double x = e.x() * scale, y = e.y() * scale;
        if (x >= lx && x < lx + lw) {
            String a = list.at(y);
            if (a != null) mod.controller().handle(new Intent.Select(a));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return true;
        if (mx * scale >= lx && mx * scale < lx + lw) mod.controller().handle(new Intent.Step(sy > 0 ? -1 : 1));
        else mod.controller().handle(new Intent.Scroll(sy > 0 ? -3 : 3));
        return true;
    }

    @Override
    protected void init() {
        // typed characters only arrive while a text input has the focus (SDL from Minecraft 26.3; IME before)
        minecraft.onTextInputFocusChange(this, true);
    }

    @Override
    public void removed() {
        minecraft.onTextInputFocusChange(this, false);
        mod.consoleRemoved();
    }
}
