package cc.useforce.mc.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Keys, text and the mouse as the bytes an xterm sends (what tmux, and so Force, reads): key names come from
 * {@link Keys} ("Enter", "C-M-Up", "C-a", "F5"), so the GLFW/SDL difference stays in its table.
 */
public final class VtInput {
    public static final int LEFT = 0, MIDDLE = 1, RIGHT = 2, WHEEL_UP = 64, WHEEL_DOWN = 65;

    private VtInput() {}

    /** The bytes for a named key (Keys.name), or null. `appCursor`: the app asked for ESC O arrows. */
    public static byte[] key(String name, boolean appCursor) {
        if (name == null) return null;
        boolean ctrl = false, alt = false, shift = false;
        String base = name;
        while (base.length() > 2 && base.charAt(1) == '-' && "CMS".indexOf(base.charAt(0)) >= 0) {
            switch (base.charAt(0)) {
                case 'C' -> ctrl = true;
                case 'M' -> alt = true;
                default -> shift = true;
            }
            base = base.substring(2);
        }
        int mod = 1 + (shift ? 1 : 0) + (alt ? 2 : 0) + (ctrl ? 4 : 0);   // xterm's modifier parameter
        String seq = switch (base) {
            case "Up" -> cursor('A', mod, appCursor);
            case "Down" -> cursor('B', mod, appCursor);
            case "Right" -> cursor('C', mod, appCursor);
            case "Left" -> cursor('D', mod, appCursor);
            case "Home" -> cursor('H', mod, appCursor);
            case "End" -> cursor('F', mod, appCursor);
            case "IC" -> tilde(2, mod);
            case "DC" -> tilde(3, mod);
            case "PPage" -> tilde(5, mod);
            case "NPage" -> tilde(6, mod);
            case "F1" -> fn('P', mod);
            case "F2" -> fn('Q', mod);
            case "F3" -> fn('R', mod);
            case "F4" -> fn('S', mod);
            case "F5" -> tilde(15, mod);
            case "F6" -> tilde(17, mod);
            case "F7" -> tilde(18, mod);
            case "F8" -> tilde(19, mod);
            case "F9" -> tilde(20, mod);
            case "F10" -> tilde(21, mod);
            case "F11" -> tilde(23, mod);
            case "F12" -> tilde(24, mod);
            case "BTab" -> "\033[Z";
            case "Tab" -> (alt ? "\033" : "") + "\t";
            case "Enter" -> (alt ? "\033" : "") + "\r";
            case "Escape" -> (alt ? "\033" : "") + "\033";
            case "BSpace" -> (alt ? "\033" : "") + (ctrl ? "\b" : "\u007f");
            case "Space" -> (alt ? "\033" : "") + (ctrl ? "\u0000" : " ");
            default -> {
                if (base.length() != 1) yield null;
                char c = base.charAt(0);
                String out = String.valueOf(c);
                if (ctrl) {
                    if (c >= 'a' && c <= 'z') out = String.valueOf((char) (c - 'a' + 1));
                    else if (c == '[') out = "\033";
                    else if (c == '\\') out = "\u001c";
                    else if (c == ']') out = "\u001d";
                    else if (c == '/' || c == '-') out = "\u001f";
                    else if (c >= '2' && c <= '8') out = String.valueOf("\u0000\u001b\u001c\u001d\u001e\u001f\u007f".charAt(c - '2'));
                }
                yield (alt ? "\033" : "") + out;
            }
        };
        return seq == null ? null : seq.getBytes(StandardCharsets.UTF_8);
    }

    private static String cursor(char fin, int mod, boolean appCursor) {
        if (mod > 1) return "\033[1;" + mod + fin;
        return (appCursor ? "\033O" : "\033[") + fin;
    }

    private static String tilde(int n, int mod) {
        return "\033[" + n + (mod > 1 ? ";" + mod : "") + "~";
    }

    private static String fn(char fin, int mod) {
        return mod > 1 ? "\033[1;" + mod + fin : "\033O" + fin;
    }

    /** Typed text; a paste is bracketed when the app asked for it (it then knows it was not typed). */
    public static byte[] text(String s, boolean paste, boolean bracketed) {
        String body = paste ? s.replace("\r\n", "\r").replace('\n', '\r') : s;
        if (paste && bracketed) body = "\033[200~" + body.replace("\033[201~", "") + "\033[201~";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A mouse event at cell (col, row), 0-based: `button` LEFT/MIDDLE/RIGHT/WHEEL_*, `press` false for a release,
     * `drag` for a move with the button held. SGR encoding when the app asked for it, else the old one (cells < 223).
     */
    public static byte[] mouse(int button, boolean press, boolean drag, int col, int row, boolean shift, boolean alt,
                               boolean ctrl, boolean sgr) {
        int b = button + (drag ? 32 : 0) + (shift ? 4 : 0) + (alt ? 8 : 0) + (ctrl ? 16 : 0);
        if (sgr) return ("\033[<" + b + ";" + (col + 1) + ";" + (row + 1) + (press ? "M" : "m"))
                .getBytes(StandardCharsets.US_ASCII);
        if (!press) b = 3 + (shift ? 4 : 0) + (alt ? 8 : 0) + (ctrl ? 16 : 0);
        if (col > 222 || row > 222) return null;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("\033[M".getBytes(StandardCharsets.US_ASCII));
        o.write(32 + b);
        o.write(32 + col + 1);
        o.write(32 + row + 1);
        return o.toByteArray();
    }
}
