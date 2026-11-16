package cc.useforce.mc.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A glass panel: Force's ground, see-through, with a hairline edge. */
public final class Panel {
    private Panel() {}

    public static void draw(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, Theme.PANEL);
        g.outline(x, y, w, h, Theme.PANEL_EDGE);
    }
}
