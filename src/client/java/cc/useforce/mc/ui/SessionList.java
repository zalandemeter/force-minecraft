package cc.useforce.mc.ui;

import cc.useforce.mc.core.ForceModel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * The sessions as a list: a glyph in the state's colour, the name, the activity (two rows per session, or one when
 * `compact`). It only draws and answers "what is at this point"; what a click means is the caller's.
 */
public final class SessionList {
    public record Hit(int y0, int y1, String address) {}

    private final List<Hit> hits = new ArrayList<>();

    public int rowHeight(boolean compact) {
        return compact ? 11 : 22;
    }

    public void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, ForceModel.State state,
                     String selected, boolean compact, boolean showHosts) {
        hits.clear();
        long now = System.currentTimeMillis();
        int row = rowHeight(compact);
        int cy = y;
        String host = null;
        for (ForceModel.Session s : state.live()) {
            if (showHosts && !s.host().equals(host)) {
                host = s.host();
                if (cy + 11 > y + h) break;
                g.text(font, Theme.mono(host), x + 4, cy + 1, Theme.DIM, false);
                cy += 11;
            }
            if (cy + row > y + h) break;
            boolean sel = s.address().equals(selected);
            if (sel) g.fill(x, cy, x + w, cy + row, Theme.RAISED);
            if (sel) g.fill(x, cy, x + 2, cy + row, Theme.CORAL);
            int color = Theme.color(s);
            g.text(font, Theme.mono(Theme.glyph(s, now)), x + 6, cy + 2, color, false);
            String name = clip(font, s.name(), w - 20 - (compact ? 50 : 0));
            g.text(font, Theme.mono(name), x + 16, cy + 2, sel ? Theme.TEXT : Theme.SOFT, false);
            if (compact) {
                String lbl = Theme.label(s);
                int lw = font.width(Theme.mono(lbl));
                g.text(font, Theme.mono(lbl), x + w - lw - 4, cy + 2, color, false);
            } else {
                String act = s.activity() != null ? s.activity() : Theme.label(s);
                g.text(font, Theme.mono(clip(font, act, w - 20)), x + 16, cy + 12, Theme.DIM, false);
            }
            hits.add(new Hit(cy, cy + row, s.address()));
            cy += row;
        }
    }

    /** The session drawn at height `my`, or null. */
    public String at(double my) {
        for (Hit h : hits) if (my >= h.y0() && my < h.y1()) return h.address();
        return null;
    }

    static String clip(Font font, String s, int w) {
        if (s == null) return "";
        if (font.width(Theme.mono(s)) <= w) return s;
        String t = s;
        while (!t.isEmpty() && font.width(Theme.mono(t + "…")) > w) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
