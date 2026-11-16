package cc.useforce.mc.ui;

import cc.useforce.mc.core.TerminalBuffer;

/**
 * Draws a {@link TerminalBuffer}, whatever backend filled it, in screen pixels: a cell is the font's own advance wide
 * and a 1.3 line tall at `px`, every glyph on whole pixels. {@link #fit} says how many cells an area holds (the size
 * the view asks Force for); a bigger screen than that is scaled down to fit, a smaller one sits top-left.
 */
public final class TerminalView {
    public record Grid(int cols, int rows) {}

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

    public void draw(Canvas c, int x, int y, int w, int h, TerminalBuffer.Snapshot s, int px) {
        draw(c, x, y, w, h, s, px, false);
    }

    /** `glass`: the area is not filled, and cells on Force's own ground (#262626/#212121) leave the glass showing. */
    public void draw(Canvas c, int x, int y, int w, int h, TerminalBuffer.Snapshot s, int px, boolean glass) {
        if (!glass) c.fill(x, y, w, h, Theme.TERM_BG);
        if (s.cols() == 0 || s.rows() == 0) return;
        int cw = cellW(c, px), ch = cellH(px);
        float scale = Math.min(1f, Math.min((float) w / (s.cols() * cw), (float) h / (s.rows() * ch)));
        var pose = c.g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        if (scale < 1f) pose.scale(scale, scale);       // the session is bigger than asked (another terminal): fit
        for (int row = 0; row < s.rows() && row < s.lines().size(); row++) {
            int col = 0, ry = row * ch;
            for (TerminalBuffer.Run r : s.lines().get(row)) {
                int len = r.text().codePointCount(0, r.text().length());
                int fg = r.fg() != null ? 0xFF000000 | r.fg() : Theme.TERM_FG;
                Integer bg = r.bg();
                if (r.reverse()) {
                    int old = fg;
                    fg = bg != null ? 0xFF000000 | bg : 0xFF212121;
                    bg = old & 0xFFFFFF;
                }
                if (r.dim()) fg = (fg & 0xFF000000) | ((fg >> 1) & 0x7F7F7F);
                if (bg != null && !(glass && (bg == 0x262626 || bg == 0x212121)))
                    c.fill(col * cw, ry, len * cw, ch, 0xFF000000 | bg);
                drawRun(c, r.text(), col, ry, cw, px, fg, r.bold());
                if (r.underline()) c.fill(col * cw, ry + GlyphAtlas.get().metrics(px).baseline() + Math.max(1, px / 12),
                        len * cw, Math.max(1, px / 14), fg);
                col += len;
            }
        }
        if (s.cursorVisible() && (System.currentTimeMillis() / 530) % 2 == 0)
            c.fill(s.cursorX() * cw, s.cursorY() * ch, cw, ch, 0xB0D0D0D0);
        pose.popMatrix();
    }

    /** Each character in its own cell; box-drawing and block characters as exact lines and blocks. */
    private static void drawRun(Canvas c, String text, int col, int y, int cw, int px, int fg, boolean bold) {
        StringBuilder plain = new StringBuilder();
        int start = col, i = 0, cc = col;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean wide = i < text.length() && text.charAt(i) == '\u0000';
            if (cp == 0) {
                // the second cell of a wide character: drawn with the first
                flush(c, plain, start, y, cw, px, fg, bold);
                start = cc + 1;
            } else if (wide) {
                flush(c, plain, start, y, cw, px, fg, bold);
                GlyphAtlas.get().draw(c, new String(Character.toChars(cp)), cc * cw, y, px, bold, fg, 2 * cw);
                start = cc + 1;
            } else if (BoxDraw.draw(c, cp, cc * cw, y, cw, cellH(px), px, fg)) {
                flush(c, plain, start, y, cw, px, fg, bold);
                start = cc + 1;
            } else {
                if (plain.isEmpty()) start = cc;
                String sub = substitute(cp);
                if (sub != null) plain.append(sub);
                else plain.appendCodePoint(cp);
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
