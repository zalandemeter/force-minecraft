package cc.useforce.mc.ui;

import cc.useforce.mc.core.ForceModel;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;

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

    /** The pixel sizes there is a font for (scripts/fonts.py): each rasterised at exactly that size. */
    public static final int[] SIZES = {12, 13, 14, 15, 16, 17, 18, 20, 22, 24, 26, 28, 32, 36, 40, 44, 48};
    private static final FontDescription[] REGULAR = new FontDescription[SIZES.length];
    private static final FontDescription[] BOLD = new FontDescription[SIZES.length];

    static {
        for (int i = 0; i < SIZES.length; i++) {
            REGULAR[i] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("force_mc", "px_" + SIZES[i]));
            BOLD[i] = new FontDescription.Resource(Identifier.fromNamespaceAndPath("force_mc", "px_" + SIZES[i] + "_bold"));
        }
    }

    private Theme() {}

    /** The nearest size there is a font for. */
    public static int size(int px) {
        int best = SIZES[0];
        for (int s : SIZES) if (Math.abs(s - px) < Math.abs(best - px)) best = s;
        return best;
    }

    public static FontDescription font(int px, boolean bold) {
        int s = size(px);
        for (int i = 0; i < SIZES.length; i++) if (SIZES[i] == s) return bold ? BOLD[i] : REGULAR[i];
        return REGULAR[0];
    }

    public static Component text(String text, int px, boolean bold) {
        return Component.literal(text).withStyle(Style.EMPTY.withFont(font(px, bold)));
    }

    public static Component text(String text, int px, Style style, boolean bold) {
        return Component.literal(text).withStyle(style.withFont(font(px, bold)));
    }

    /** Where Minecraft's text origin goes, below a line box's top, so a `px` font's ink is centred in the box.
     * Minecraft puts a TTF glyph's baseline 7 units below the origin at any size (measured: screenshots at 16 and
     * 36 px); JetBrains Mono's ink runs from 0.78 em above the baseline to 0.22 em below, centred 0.28 em up. */
    public static int baseline(int px) {
        return Math.round(line(px) / 2f + 0.28f * size(px)) - 7;
    }

    /** A line box for `px` text: JetBrains Mono's comfortable 1.3. */
    public static int line(int px) {
        return Math.round(size(px) * 1.3f);
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
