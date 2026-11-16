package cc.useforce.mc.ui;

import cc.useforce.mc.core.ForceModel;

import java.util.ArrayList;
import java.util.List;

/**
 * The sessions as a list (screen pixels): a glyph in the state's colour, the name, and the activity under it (or the
 * state label on the right when `compact`). It only draws and answers "what is at this point"; what a click means is
 * the caller's.
 */
public final class SessionList {
    public record Hit(int y0, int y1, String address) {}

    private final List<Hit> hits = new ArrayList<>();

    public static int rowHeight(int px, boolean compact) {
        return compact ? Theme.line(px) + px / 4 : 2 * Theme.line(px) + px / 3;
    }

    public void draw(Canvas c, int x, int y, int w, int h, ForceModel.State state, String selected, boolean compact,
                     boolean showHosts, int px) {
        hits.clear();
        long now = System.currentTimeMillis();
        int row = rowHeight(px, compact);
        int pad = px / 2, glyphW = c.textWidth("M", px, false) * 2;
        int cy = y;
        String host = null;
        for (ForceModel.Session s : state.live()) {
            if (showHosts && !s.host().equals(host)) {
                host = s.host();
                if (cy + Theme.line(px) > y + h) break;
                c.text(host, x + pad, cy + px / 4, px, Theme.DIM, false);
                cy += Theme.line(px) + px / 4;
            }
            if (cy + row > y + h) break;
            boolean sel = s.address().equals(selected);
            if (sel) {
                c.fill(x, cy, w, row, Theme.RAISED);
                c.fill(x, cy, Math.max(2, px / 6), row, Theme.CORAL);
            }
            int color = Theme.color(s);
            int ty = cy + (compact ? px / 8 : px / 6);
            c.text(Theme.glyph(s, now), x + pad, ty, px, color, false);
            int nx = x + pad + glyphW;
            if (compact) {
                String lbl = Theme.label(s);
                int lw = c.textWidth(lbl, px, false);
                c.text(c.clip(s.name(), w - (nx - x) - lw - pad * 2, px), nx, ty, px, sel ? Theme.TEXT : Theme.SOFT,
                        sel);
                c.text(lbl, x + w - lw - pad, ty, px, color, false);
            } else {
                c.text(c.clip(s.name(), w - (nx - x) - pad, px), nx, ty, px, sel ? Theme.TEXT : Theme.SOFT, sel);
                String act = s.activity() != null ? s.activity() : Theme.label(s);
                c.text(c.clip(act, w - (nx - x) - pad, px), nx, ty + Theme.line(px), px, Theme.DIM, false);
            }
            hits.add(new Hit(cy, cy + row, s.address()));
            cy += row;
        }
    }

    /** The session drawn at height `my` (screen pixels), or null. */
    public String at(double my) {
        for (Hit h : hits) if (my >= h.y0() && my < h.y1()) return h.address();
        return null;
    }
}
