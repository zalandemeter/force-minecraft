package cc.useforce.mc.ui;

import cc.useforce.mc.core.TerminalBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;

/**
 * Draws a {@link TerminalBuffer}, whatever backend filled it. A cell is {@link #CELL_W} × {@link #CELL_H} font units
 * (the mono font's advance is exactly 5), drawn at `unitPx` screen pixels per unit, so glyphs sit on whole pixels and
 * keep their size whatever the GUI scale. {@link #fit} says how many cells an area holds at that size (the size the
 * view asks Force for); a larger screen than that is scaled down to fit, a smaller one is centred.
 */
public final class TerminalView {
    public static final int CELL_W = 5, CELL_H = 9;

    public record Grid(int cols, int rows) {}

    /** The cells `w` × `h` GUI units hold at `unitPx` screen pixels per font unit. */
    public static Grid fit(int w, int h, double unitPx, int guiScale) {
        double s = unitPx / guiScale;
        return new Grid(Math.max(20, (int) (w / (CELL_W * s))), Math.max(5, (int) (h / (CELL_H * s))));
    }

    public void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, TerminalBuffer.Snapshot s,
                     double unitPx, int guiScale) {
        g.fill(x, y, x + w, y + h, Theme.TERM_BG);
        if (s.cols() == 0 || s.rows() == 0) return;
        float scale = (float) (unitPx / guiScale);
        float need = Math.min((float) w / (s.cols() * CELL_W), (float) h / (s.rows() * CELL_H));
        if (need < scale) scale = need;                       // the session is bigger than asked: fit it
        int ox = x + (int) ((w - s.cols() * CELL_W * scale) / 2);
        int oy = y + (int) ((h - s.rows() * CELL_H * scale) / 2);
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(ox, oy);
        pose.scale(scale, scale);
        for (int row = 0; row < s.rows() && row < s.lines().size(); row++) {
            int col = 0;
            for (TerminalBuffer.Run r : s.lines().get(row)) {
                int len = r.text().codePointCount(0, r.text().length());
                int fg = r.fg() != null ? 0xFF000000 | r.fg() : Theme.TERM_FG;
                Integer bg = r.bg();
                if (r.reverse()) {
                    int old = fg;
                    fg = bg != null ? 0xFF000000 | bg : 0xFF212121;
                    bg = old & 0xFFFFFF;
                }
                if (r.bold()) fg = brighter(fg);              // Minecraft's bold is a smeared double draw
                if (r.dim()) fg = (fg & 0xFF000000) | ((fg >> 1) & 0x7F7F7F);
                if (bg != null) g.fill(col * CELL_W, row * CELL_H, (col + len) * CELL_W, (row + 1) * CELL_H,
                        0xFF000000 | bg);
                Style st = Style.EMPTY.withItalic(r.italic()).withUnderlined(r.underline());
                int i = 0, c = col;
                while (i < r.text().length()) {               // one cell per character: the grid holds
                    int cp = r.text().codePointAt(i);
                    if (cp != ' ') g.text(font, Theme.mono(new String(Character.toChars(cp)), st), c * CELL_W,
                            row * CELL_H, fg, false);
                    i += Character.charCount(cp);
                    c++;
                }
                col += len;
            }
        }
        if (s.cursorVisible() && (System.currentTimeMillis() / 530) % 2 == 0)
            g.fill(s.cursorX() * CELL_W, s.cursorY() * CELL_H, (s.cursorX() + 1) * CELL_W, (s.cursorY() + 1) * CELL_H,
                    0xAAD0D0D0);
        pose.popMatrix();
    }

    static int brighter(int argb) {
        int r = (argb >> 16) & 0xFF, gr = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r = r + (255 - r) / 3;
        gr = gr + (255 - gr) / 3;
        b = b + (255 - b) / 3;
        return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
    }
}
