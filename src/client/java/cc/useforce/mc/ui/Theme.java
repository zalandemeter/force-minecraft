package cc.useforce.mc.ui;

import cc.useforce.mc.core.ForceModel;

/** Force's own colours (its terminal theme) and the glass panels, in one place: a restyle is an edit here. */
public final class Theme {
    public static final int PANEL = 0xDC141414;           // the glass: Force's ground, a little see-through
    public static final int PANEL_EDGE = 0x33FFFFFF;
    public static final int RAISED = 0xCC2C2C2C;          // the selected card
    public static final int TEXT = 0xFFF2F2F2;
    public static final int SOFT = 0xFFC6C6C6;
    public static final int DIM = 0xFF8A8A8A;
    public static final int CORAL = 0xFFD97757;           // the Force mark
    public static final int RED = 0xFFF0645E;             // needs you
    public static final int AMBER = 0xFFD7AF5F;           // working
    public static final int GREEN = 0xFF87C08F;           // your turn
    public static final int BLUE = 0xFFA8C7FA;            // unread: a finding to read
    public static final int TERM_BG = 0xE6212121;         // the terminal's ground
    public static final int TERM_FG = 0xFFD0D0D0;

    private Theme() {}

    /** `argb` with its alpha set to `opacity` (0–1). */
    public static int withAlpha(int argb, double opacity) {
        int a = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** A text size in screen pixels the console uses (12–48). */
    public static int size(int px) {
        return Math.max(12, Math.min(48, px));
    }

    /** A line box for `px` text. */
    public static int line(int px) {
        return GlyphAtlas.get().metrics(size(px)).cellH();
    }

    /** The state's colour (as the sidebar: unread wins over working, waiting is grey). */
    public static int color(ForceModel.Session s) {
        if (s.unread()) return BLUE;
        if (s.watching()) return DIM;
        return switch (s.state()) {
            case "needs-you" -> RED;
            case "working" -> AMBER;
            case "your-turn" -> GREEN;
            default -> DIM;
        };
    }

    /** The state's glyph (in the mono font, which has them all). */
    public static String glyph(ForceModel.Session s, long nowMs) {
        if (s.watching()) return "◷";
        return switch (s.state()) {
            case "needs-you" -> "!";
            case "working" -> String.valueOf("◐◓◑◒".charAt((int) ((nowMs / 150) % 4)));
            case "your-turn" -> "●";
            case "idle" -> "○";
            case "stale" -> "◌";
            case "down" -> "×";
            default -> "·";
        };
    }

    public static String label(ForceModel.Session s) {
        if (s.note() != null) return s.note();
        if (s.watching()) return "waiting";
        return switch (s.state()) {
            case "needs-you" -> "needs you";
            case "your-turn" -> "your turn";
            default -> s.state();
        };
    }
}
