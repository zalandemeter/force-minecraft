package cc.useforce.mc;

import cc.useforce.mc.core.Links;
import cc.useforce.mc.core.TerminalBuffer;
import cc.useforce.mc.ui.Canvas;
import cc.useforce.mc.ui.GlyphAtlas;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.Theme;

/**
 * Web links in a terminal view, as a terminal shows them: the one under the mouse is underlined (every line of it,
 * when it wraps) with how to open it beside the mouse, and Ctrl+click opens it. A link the program named itself
 * (OSC 8) shows its address in the hint, since its text may say something else. Both views use it: Force's app and
 * the session view.
 */
final class LinkHover {
    /** Where the grid is drawn, in screen pixels: its top-left corner and one cell (scaled, when it was fitted). */
    record Grid(int x, int y, float cw, float ch) {
        int col(double px) { return (int) Math.floor((px - x) / cw); }
        int row(double py) { return (int) Math.floor((py - y) / ch); }
    }

    private LinkHover() {}

    /** The link at a point (screen pixels), or null. */
    static Links.Link at(TerminalBuffer.Snapshot s, Grid g, double x, double y) {
        if (g == null || s.cols() == 0) return null;
        int c = g.col(x), r = g.row(y);
        if (r < 0 || r >= s.rows() || c < 0 || c >= s.cols()) return null;
        return Links.at(s, r, c);
    }

    /** Underlines the link under the mouse (screen pixels mx, my) and shows the hint; the link, or null. */
    static Links.Link draw(Canvas c, TerminalBuffer.Snapshot s, Grid g, int px, int mx, int my) {
        Links.Link l = at(s, g, mx, my);
        if (l == null) return null;
        float k = g.cw() / GlyphAtlas.get().metrics(px).cellW();               // the grid's scale
        int base = Math.round((GlyphAtlas.get().metrics(px).baseline() + Math.max(1, px / 12)) * k);
        int thick = Math.max(1, Math.round(Math.max(1, px / 14) * k));
        for (Links.Span sp : l.spans()) {
            int x0 = g.x() + Math.round(sp.start() * g.cw()), x1 = g.x() + Math.round(sp.end() * g.cw());
            c.fill(x0, g.y() + Math.round(sp.row() * g.ch()) + base, x1 - x0, thick, Theme.BLUE);
        }
        String hint = "Ctrl+click to open";
        int hpx = Math.max(12, px * 3 / 4);
        if (l.marked()) hint = c.clip(hint + "  " + l.url(), c.width() / 2, hpx);
        int hw = c.textWidth(hint, hpx, false) + hpx, hh = Theme.line(hpx) + hpx / 3;
        int hx = Math.max(2, Math.min(mx + px, c.width() - hw - 2)), hy = Math.max(2, Math.min(my + px, c.height() - hh - 2));
        Panel.draw(c, hx, hy, hw, hh);
        c.text(hint, hx + hpx / 2, hy + hpx / 6, hpx, Theme.SOFT, false);
        return l;
    }

    /** Ctrl+click at a point (screen pixels): opens the link there; true when there was one. */
    static boolean click(TerminalBuffer.Snapshot s, Grid g, double x, double y) {
        Links.Link l = at(s, g, x, y);
        if (l == null) return false;
        OpenLink.open(l.url());
        return true;
    }
}
