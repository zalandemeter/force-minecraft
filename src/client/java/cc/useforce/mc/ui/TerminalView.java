package cc.useforce.mc.ui;

import cc.useforce.mc.core.TerminalBuffer;

/**
 * Draws a {@link TerminalBuffer}, whatever backend filled it, in screen pixels: a cell is the font's own advance wide
 * and a 1.3 line tall at `px`, every glyph on whole pixels. {@link #fit} says how many cells an area holds (the size
 * the view asks Force for); a bigger screen than that is scaled down to fit, a smaller one sits top-left.
 */
public final class TerminalView {
    public record Grid(int cols, int rows) {}

    /** How solid the cells' own backgrounds are on glass (1: as the program paints them). */
    public double cellOpacity = 1;

    /** Glyphs Claude Code draws that no bundled font has: stand-ins of the same look. */
    static String substitute(int cp) {
        return switch (cp) {
            case 0x23FA -> "●";             // ⏺ record button: Claude's bullet
            case 0x23F5 -> "▸";             // ⏵ play: Claude's mode marker
            case 0x23F8 -> "‖";             // ⏸ pause
            default -> null;
        };
    }

    public static int cellW(Canvas c, int px) {
        return GlyphAtlas.get().metrics(px).cellW();
    }

    public static int cellH(int px) {
        return GlyphAtlas.get().metrics(px).cellH();
    }

    public static Grid fit(Canvas c, int w, int h, int px) {
        return new Grid(Math.max(20, w / cellW(c, px)), Math.max(5, h / cellH(px)));
    }

    /** How much a screen is scaled down to fit an area w × h (1: it fits as it is). */
    public static float scale(int w, int h, TerminalBuffer.Snapshot s, int px) {
        if (s.cols() == 0 || s.rows() == 0) return 1f;
        return Math.min(1f, Math.min((float) w / (s.cols() * cellW(null, px)), (float) h / (s.rows() * cellH(px))));
    }

    public void draw(Canvas c, int x, int y, int w, int h, TerminalBuffer.Snapshot s, int px) {
        draw(c, x, y, w, h, s, px, false);
    }

    /** `glass`: the area is not filled, and cells on Force's own ground (#262626/#212121) leave the glass showing. */
    public void draw(Canvas c, int x, int y, int w, int h, TerminalBuffer.Snapshot s, int px, boolean glass) {
        if (!glass) c.fill(x, y, w, h, Theme.TERM_BG);
        if (s.cols() == 0 || s.rows() == 0) return;
        int cw = cellW(c, px), ch = cellH(px);
        float scale = scale(w, h, s, px);
        var pose = c.g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        if (scale < 1f) pose.scale(scale, scale);       // the session is bigger than asked (another terminal): fit
        // backgrounds first, then the text, as a terminal does: a glyph a little wider than its cell (an icon, an
        // emoji) runs over its neighbours instead of being cut by their backgrounds
        for (int pass = 0; pass < 2; pass++) {
            for (int row = 0; row < s.rows() && row < s.lines().size(); row++) {
                int col = 0, ry = row * ch;
                for (TerminalBuffer.Run r : s.lines().get(row)) {
                    int len = cells(r.text());
                    int fg = r.fg() != null ? 0xFF000000 | r.fg() : Theme.TERM_FG;
                    Integer bg = r.bg();
                    if (r.reverse()) {
                        int old = fg;
                        fg = bg != null ? 0xFF000000 | bg : 0xFF212121;
                        bg = old & 0xFFFFFF;
                    }
                    if (r.dim()) fg = (fg & 0xFF000000) | ((fg >> 1) & 0x7F7F7F);
                    if (pass == 0) {
                        if (bg != null && !(glass && (bg == 0x262626 || bg == 0x212121)))
                            c.fill(col * cw, ry, len * cw, ch, glass ? Theme.withAlpha(bg, cellOpacity) : 0xFF000000 | bg);
                    } else {
                        drawRun(c, r.text(), col, ry, cw, px, fg, r.bold());
                        if (r.underline())
                            c.fill(col * cw, ry + GlyphAtlas.get().metrics(px).baseline() + Math.max(1, px / 12),
                                    len * cw, Math.max(1, px / 14), fg);
                    }
                    col += len;
                }
            }
        }
        if (s.cursorVisible() && (System.currentTimeMillis() / 530) % 2 == 0)
            c.fill(s.cursorX() * cw, s.cursorY() * ch, cw, ch, 0xB0D0D0D0);
        pose.popMatrix();
    }

    /** The cells `text` takes: VS16 (an emoji's marker) rides on the character before it. */
    static int cells(String text) {
        int n = text.codePointCount(0, text.length());
        for (int i = text.indexOf('\uFE0F'); i >= 0; i = text.indexOf('\uFE0F', i + 1)) n--;
        return n;
    }

    /** Each character in its own cell; box-drawing and block characters as exact lines and blocks. */
    private static void drawRun(Canvas c, String text, int col, int y, int cw, int px, int fg, boolean bold) {
        StringBuilder plain = new StringBuilder();
        int start = col, i = 0, cc = col;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean emoji = i < text.length() && text.charAt(i) == '\uFE0F';
            if (emoji) i++;
            boolean wide = i < text.length() && text.charAt(i) == '\u0000';
            if (cp == 0xFE0F) continue;                 // a stray one: no cell
            String glyph = new String(Character.toChars(cp)) + (emoji ? "\uFE0F" : "");
            if (cp == 0) {
                // the second cell of a wide character: drawn with the first
                flush(c, plain, start, y, cw, px, fg, bold);
                start = cc + 1;
            } else if (wide) {
                flush(c, plain, start, y, cw, px, fg, bold);
                GlyphAtlas.get().draw(c, glyph, cc * cw, y, px, bold, fg, 2 * cw);
                start = cc + 1;
            } else if (BoxDraw.draw(c, cp, cc * cw, y, cw, cellH(px), px, fg)) {
                flush(c, plain, start, y, cw, px, fg, bold);
                start = cc + 1;
            } else {
                if (plain.isEmpty()) start = cc;
                String sub = substitute(cp);
                if (sub != null && !emoji) plain.append(sub);
                else plain.append(glyph);
            }
            cc++;
        }
        flush(c, plain, start, y, cw, px, fg, bold);
    }

    private static void flush(Canvas c, StringBuilder text, int col, int y, int cw, int px, int fg, boolean bold) {
        if (text.isEmpty()) return;
        GlyphAtlas.get().draw(c, text.toString(), col * cw, y, px, bold, fg, cw);
        text.setLength(0);
    }
}
