package cc.useforce.mc;

import cc.useforce.mc.core.AppTerminal;
import cc.useforce.mc.core.Keys;
import cc.useforce.mc.core.TerminalBuffer;
import cc.useforce.mc.core.Vt;
import cc.useforce.mc.core.VtInput;
import cc.useforce.mc.ui.Canvas;
import cc.useforce.mc.ui.GlyphAtlas;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.TerminalView;
import cc.useforce.mc.ui.Theme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * The whole Force app, full screen, over the running game (it never pauses): the same program a terminal runs
 * (Force SPEC §54 vt mode), drawn by the mod's renderer on blurred glass. Every key goes to Force, Esc and Ctrl+Space
 * included; the console key closes it; Ctrl+Shift+V pastes; the mouse works where Force asks for it (its sidebar,
 * header and menus are clickable, the wheel scrolls).
 */
public final class AppScreen extends Screen {
    private final ForceClient mod;
    private final TerminalView view = new TerminalView();
    private final Keys keys = new Keys(KeyTable.create());
    private final StringBuilder typed = new StringBuilder();
    private int scale = 1, px = 16, ox, oy, cw = 1, ch = 1;
    private int held = -1;                             // the xterm button held down, for drags

    AppScreen(ForceClient mod) {
        super(Component.translatable("force_mc.console.title"));
        this.mod = mod;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    protected void init() {
        // typed characters only arrive while a text input has the focus (SDL from Minecraft 26.3; IME before)
        minecraft.onTextInputFocusChange(this, true);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        extractBlurredBackground(g);
        g.fill(0, 0, width, height, 0x40000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        flushTyped();
        Canvas c = new Canvas(g, font, minecraft.getWindow().getGuiScale());
        scale = c.scale;
        px = mod.config().terminalPx(scale);
        GlyphAtlas.Metrics m = GlyphAtlas.get().metrics(px);
        cw = m.cellW();
        ch = m.cellH();
        int margin = Math.max(px / 2, 6);
        int w = c.width() - 2 * margin, h = c.height() - 2 * margin;
        TerminalView.Grid grid = TerminalView.fit(c, w, h, px);
        AppTerminal app = mod.app();
        app.open(grid.cols(), grid.rows());            // opens once; afterwards only follows the size
        ox = margin + (w - grid.cols() * cw) / 2;
        oy = margin + (h - grid.rows() * ch) / 2;
        c.begin();
        Panel.draw(c, ox - margin / 2, oy - margin / 2, grid.cols() * cw + margin, grid.rows() * ch + margin);
        String problem = app.problem();
        TerminalBuffer.Snapshot s = app.snapshot();
        if (problem != null && s.cols() == 0) c.text(problem, ox, oy, px, Theme.RED, false);
        else view.draw(c, ox, oy, grid.cols() * cw, grid.rows() * ch, s, px, true);
        if (problem != null && s.cols() > 0) c.text(problem, ox, oy + grid.rows() * ch - ch, px, Theme.RED, false);
        c.end();
    }

    // --- keys -----------------------------------------------------------------------------------------------
    @Override
    public boolean keyPressed(KeyEvent e) {
        if (mod.isConsoleKey(e)) {
            mod.controller().handle(new cc.useforce.mc.core.Intent.CloseConsole());
            return true;
        }
        int mods = e.modifiers();
        Vt vt = mod.app().vt();
        if (keys.ctrl(mods) && keys.shift(mods) && e.key() == InputConstants.KEY_V) {
            String clip = minecraft.keyboardHandler.getClipboard();
            flushTyped();
            if (clip != null && !clip.isEmpty())
                mod.app().send(VtInput.text(clip, true, vt != null && vt.bracketedPaste()));
            return true;
        }
        byte[] bytes = VtInput.key(keys.name(e.key(), mods), vt != null && vt.appCursor());
        if (bytes != null) {
            flushTyped();
            mod.app().send(bytes);
        }
        return true;                                   // every key is Force's
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        typed.append(e.codepointAsString());           // sent once per frame: fast typing is one request
        return true;
    }

    private void flushTyped() {
        if (typed.isEmpty()) return;
        String t = typed.toString();
        typed.setLength(0);
        mod.app().send(VtInput.text(t, false, false));
    }

    // --- mouse: only where Force asks for it ---------------------------------------------------------------------
    private int col(double x) { return (int) Math.floor((x * scale - ox) / cw); }
    private int row(double y) { return (int) Math.floor((y * scale - oy) / ch); }

    private static int xtermButton(int b) {
        if (b == InputConstants.MOUSE_BUTTON_LEFT) return VtInput.LEFT;
        if (b == InputConstants.MOUSE_BUTTON_RIGHT) return VtInput.RIGHT;
        if (b == InputConstants.MOUSE_BUTTON_MIDDLE) return VtInput.MIDDLE;
        return -1;
    }

    private boolean report(int button, boolean press, boolean drag, double x, double y, int mods) {
        Vt vt = mod.app().vt();
        if (vt == null || vt.mouseMode() == 0 || button < 0) return true;
        int c = col(x), r = row(y);
        if (c < 0 || r < 0 || c >= vt.cols() || r >= vt.rows()) return true;
        mod.app().send(VtInput.mouse(button, press, drag, c, r, keys.shift(mods), keys.alt(mods), keys.ctrl(mods),
                vt.sgrMouse()));
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        held = xtermButton(e.button());
        return report(held, true, false, e.x(), e.y(), e.modifiers());
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        int b = xtermButton(e.button());
        held = -1;
        return report(b, false, false, e.x(), e.y(), e.modifiers());
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        Vt vt = mod.app().vt();
        if (vt != null && vt.mouseMode() >= 1002 && held >= 0) report(held, true, true, e.x(), e.y(), e.modifiers());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0) return true;
        return report(sy > 0 ? VtInput.WHEEL_UP : VtInput.WHEEL_DOWN, true, false, mx, my, 0);
    }

    @Override
    public void removed() {
        flushTyped();
        minecraft.onTextInputFocusChange(this, false);
        mod.consoleRemoved();
    }
}
