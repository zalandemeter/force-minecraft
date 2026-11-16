package cc.useforce.mc.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Drawing in screen pixels: Minecraft's GUI coordinates are scaled (2×, 3×…), which blurs small text. Inside
 * {@link #begin} one unit is one screen pixel, and text is rasterised at exactly the size it is drawn
 * ({@link GlyphAtlas}), so it is crisp at any GUI scale.
 */
public final class Canvas {
    public final GuiGraphicsExtractor g;
    public final Font font;
    public final int scale;                 // the GUI scale: screen pixels per GUI unit

    public Canvas(GuiGraphicsExtractor g, Font font, int scale) {
        this.g = g;
        this.font = font;
        this.scale = Math.max(1, scale);
    }

    public void begin() {
        g.pose().pushMatrix();
        g.pose().scale(1f / scale, 1f / scale);
    }

    public void end() {
        g.pose().popMatrix();
    }

    public int width() { return g.guiWidth() * scale; }
    public int height() { return g.guiHeight() * scale; }

    public void fill(int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }

    public void outline(int x, int y, int w, int h, int argb) {
        g.outline(x, y, w, h, argb);
    }

    /** `text` in a line box whose top is `y`, `px` pixels: the ink centred in the box (GlyphAtlas). */
    public void text(String text, int x, int y, int px, int color, boolean bold) {
        GlyphAtlas.get().draw(this, text, x, y, px, bold, color, 0);
    }

    public int textWidth(String text, int px, boolean bold) {
        return GlyphAtlas.get().width(text, px, bold);
    }

    /** `text` cut with "…" to fit `w` pixels. */
    public String clip(String text, int w, int px) {
        if (text == null) return "";
        if (textWidth(text, px, false) <= w) return text;
        String t = text;
        while (!t.isEmpty() && textWidth(t + "…", px, false) > w) t = t.substring(0, t.length() - 1);
        return t + "…";
    }
}
