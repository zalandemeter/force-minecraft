package cc.useforce.mc.ui;

import cc.useforce.mc.core.TerminalBuffer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Style;

/**
 * Draws a {@link TerminalBuffer}: whatever backend filled it. Cells are the mono font's advance wide and a line tall;
 * the whole grid is scaled to fit the area (the pane keeps its own size, Force SPEC §54).
 */
public final class TerminalView {
    private float scale = 1f;
    private int ox, oy;

    public void draw(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, TerminalBuffer.Snapshot s) {
        g.fill(x, y, x + w, y + h, Theme.TERM_BG);
        if (s.cols() == 0 || s.rows() == 0) return;
        int cw = font.width(Theme.mono("M"));
        int ch = font.lineHeight;
        scale = Math.min((float) w / (s.cols() * cw), (float) h / (s.rows() * ch));
        ox = x + (int) ((w - s.cols() * cw * scale) / 2);
        oy = y + (int) ((h - s.rows() * ch * scale) / 2);
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
                if (r.dim()) fg = (fg & 0xFF000000) | ((fg >> 1) & 0x7F7F7F);
                if (bg != null) g.fill(col * cw, row * ch, (col + len) * cw, (row + 1) * ch, 0xFF000000 | bg);
                Style st = Style.EMPTY.withBold(r.bold()).withItalic(r.italic()).withUnderlined(r.underline());
                // one cell per character: draw each, so a glyph narrower or wider than the cell keeps the grid
                int i = 0, c = col;
                while (i < r.text().length()) {
                    int cp = r.text().codePointAt(i);
                    String one = new String(Character.toChars(cp));
                    if (cp != ' ') g.text(font, Theme.mono(one, st), c * cw, row * ch, fg, false);
                    i += Character.charCount(cp);
                    c++;
                }
                col += len;
            }
        }
        if (s.cursorVisible() && (System.currentTimeMillis() / 530) % 2 == 0)
            g.fill(s.cursorX() * cw, s.cursorY() * ch, (s.cursorX() + 1) * cw, (s.cursorY() + 1) * ch, 0xAAD0D0D0);
        pose.popMatrix();
    }

    public boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }
}
