package cc.useforce.mc.ui;

/** A glass panel: Force's ground, see-through, with a hairline edge (screen pixels). */
public final class Panel {
    private Panel() {}

    public static void draw(Canvas c, int x, int y, int w, int h) {
        c.fill(x, y, w, h, Theme.PANEL);
        c.outline(x, y, w, h, Theme.PANEL_EDGE);
    }
}
