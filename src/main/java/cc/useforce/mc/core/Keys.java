package cc.useforce.mc.core;

/**
 * GLFW keys to tmux key names (Force SPEC §54 `terminal.input`). Printable characters arrive separately as typed
 * text; this names the rest: Enter, Escape, arrows, Ctrl+letter, Alt+letter, function keys, Shift+Tab.
 * Constants are GLFW's (stable), so this is plain Java and tested without Minecraft.
 */
public final class Keys {
    public static final int SHIFT = 0x1, CTRL = 0x2, ALT = 0x4, SUPER = 0x8;

    private Keys() {}

    /** The tmux key name for a key press, or null when it is a printable key (its character comes as text). */
    public static String name(int key, int mods) {
        boolean ctrl = (mods & CTRL) != 0, alt = (mods & ALT) != 0, shift = (mods & SHIFT) != 0;
        String base = switch (key) {
            case 256 -> "Escape";
            case 257, 335 -> "Enter";
            case 258 -> shift ? "BTab" : "Tab";
            case 259 -> "BSpace";
            case 260 -> "IC";
            case 261 -> "DC";
            case 262 -> "Right";
            case 263 -> "Left";
            case 264 -> "Down";
            case 265 -> "Up";
            case 266 -> "PPage";
            case 267 -> "NPage";
            case 268 -> "Home";
            case 269 -> "End";
            default -> null;
        };
        if (base == null && key >= 290 && key <= 301) base = "F" + (key - 289);
        if (base != null) {
            if (base.equals("BTab")) return base;
            return (ctrl ? "C-" : "") + (alt ? "M-" : "") + (shift && !base.equals("Tab") && base.length() > 1
                    && !base.startsWith("F") ? "S-" : "") + base;
        }
        // letters, digits and some punctuation with Ctrl or Alt: no character arrives for those
        if (ctrl || alt) {
            char ch;
            if (key >= 65 && key <= 90) ch = (char) ('a' + key - 65);
            else if (key >= 48 && key <= 57) ch = (char) key;
            else if (key == 32) return (ctrl ? "C-" : "") + (alt ? "M-" : "") + "Space";
            else if (key == 91) ch = '[';
            else if (key == 93) ch = ']';
            else if (key == 92) ch = '\\';
            else if (key == 47) ch = '/';
            else if (key == 45) ch = '-';
            else return null;
            return (ctrl ? "C-" : "") + (alt ? "M-" : "") + ch;
        }
        return null;
    }
}
