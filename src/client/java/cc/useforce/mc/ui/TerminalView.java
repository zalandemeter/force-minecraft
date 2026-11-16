package cc.useforce.mc.ui;

import cc.useforce.mc.core.TerminalBuffer;
import net.minecraft.network.chat.Style;

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
        return Math.max(1, c.textWidth("M", px, false));
    }

    public static int cellH(int px) {
        return Theme.line(px);
    }

    public static Grid fit(Canvas c, int w, int h, int px) {
        return new Grid(Math.max(20, w / cellW(c, px)), Math.max(5, h / cellH(px)));
    }

    public void draw(Canvas c, int x, int y, int w, int h, TerminalBuffer.Snapshot s, int px) {
        c.fill(x, y, w, h, Theme.TERM_BG);
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
                if (bg != null) c.fill(col * cw, ry, len * cw, ch, 0xFF000000 | bg);
                Style st = Style.EMPTY.withItalic(r.italic()).withUnderlined(r.underline());
                drawRun(c, r.text(), col, ry, cw, px, fg, r.bold(), st);
                col += len;
            }
        }
        if (s.cursorVisible() && (System.currentTimeMillis() / 530) % 2 == 0)
            c.fill(s.cursorX() * cw, s.cursorY() * ch, cw, ch, 0xB0D0D0D0);
        pose.popMatrix();
    }

    /** ASCII stretches go in one call (the font's advance is the cell); anything else one cell per character. */
    private static void drawRun(Canvas c, String text, int col, int y, int cw, int px, int fg, boolean bold,
                                Style st) {
        StringBuilder ascii = new StringBuilder();
        int start = col, i = 0, cc = col;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x20 && cp < 0x7F) {
                if (ascii.isEmpty()) start = cc;
                ascii.appendCodePoint(cp);
            } else {
                flush(c, ascii, start, y, cw, px, fg, bold, st);
                if (BoxDraw.draw(c, cp, cc * cw, y, cw, cellH(px), px, fg)) {
                    // drawn as lines and blocks
                } else if (cp != ' ') {
                    String sub = substitute(cp);
                    c.text(Theme.text(sub != null ? sub : new String(Character.toChars(cp)), px, st, bold), cc * cw,
                            y, px, fg);
                }
            }
            cc++;
        }
        flush(c, ascii, start, y, cw, px, fg, bold, st);
    }

    private static void flush(Canvas c, StringBuilder ascii, int col, int y, int cw, int px, int fg, boolean bold,
                              Style st) {
        if (ascii.isEmpty()) return;
        c.text(Theme.text(ascii.toString(), px, st, bold), col * cw, y, px, fg);
        ascii.setLength(0);
    }
}
