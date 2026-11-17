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
 * included; the console key (F12, or the combination set in Controls) hides it; Ctrl+Shift+V pastes; the mouse works
 * where Force asks for it (its sidebar, header and menus are clickable, the wheel scrolls). Hidden, the app keeps
 * running: it comes back at once, as it was (its host, its session, its menus). The settings screen sets how solid the
 * glass is, the blur and the padding.
 */
public final class AppScreen extends Screen implements Blur {
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
    public void blur(GuiGraphicsExtractor g) { extractBlurredBackground(g); }

    @Override
    public boolean shouldCloseOnEsc() { return false; }

    @Override
    protected void init() {
        // typed characters only arrive while a text input has the focus (SDL from Minecraft 26.3; IME before)
        minecraft.onTextInputFocusChange(this, true);
        mod.app().setHidden(false);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float delta) {
        background(this, g, mod.config());
    }

    /** The game behind Force's views: blurred (a setting), and darkened as much as the glass is solid. */
    static void background(Screen s, GuiGraphicsExtractor g, Config cfg) {
        if (cfg.blur) ((Blur) s).blur(g);
        g.fill(0, 0, s.width, s.height, Theme.withAlpha(0, 0.25 * cfg.terminalOpacity / Config.DEFAULT_OPACITY));
    }

    /** Where the app goes on a screen of this size (screen pixels): its grid and its top-left corner. */
    record Layout(int cols, int rows, int x, int y, int inset, int cw, int ch) {}

    static Layout layout(Config cfg, int guiScale, int screenW, int screenH) {
        int px = cfg.terminalPx(guiScale);
        GlyphAtlas.Metrics m = GlyphAtlas.get().metrics(px);
        int inset = Math.max(px / 2, 6) / 2;                   // the glass around the cells
        int pad = cfg.paddingPx(guiScale) + inset;
        int w = Math.max(1, screenW - 2 * pad), h = Math.max(1, screenH - 2 * pad);
        int cols = Math.max(20, Math.min(500, w / m.cellW())), rows = Math.max(5, Math.min(200, h / m.cellH()));
        return new Layout(cols, rows, pad + (w - cols * m.cellW()) / 2, pad + (h - rows * m.cellH()) / 2, inset,
                m.cellW(), m.cellH());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        flushTyped();
        Canvas c = new Canvas(g, font, minecraft.getWindow().getGuiScale());
        scale = c.scale;
        Config cfg = mod.config();
        px = cfg.terminalPx(scale);
        Layout l = layout(cfg, scale, c.width(), c.height());
        cw = l.cw();
        ch = l.ch();
        TerminalView.Grid grid = new TerminalView.Grid(l.cols(), l.rows());
        AppTerminal app = mod.app();
        app.open(grid.cols(), grid.rows());            // opens once; afterwards only follows the size
        ox = l.x();
        oy = l.y();
        int in = l.inset();
        view.cellOpacity = Math.min(1, cfg.terminalOpacity / Config.DEFAULT_OPACITY);
        c.begin();
        Panel.draw(c, ox - in, oy - in, grid.cols() * cw + 2 * in, grid.rows() * ch + 2 * in, cfg.terminalOpacity);
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
        int mods = e.modifiers();
        if (mod.isCloseKey(e)) {                                                    // F12 (or as set)
            flushTyped();
            mod.controller().handle(new cc.useforce.mc.core.Intent.CloseConsole());
            return true;
        }
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
