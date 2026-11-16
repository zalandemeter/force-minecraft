package cc.useforce.mc.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft key events to tmux key names (Force SPEC §54 `terminal.input`). Printable characters arrive separately as
 * typed text; this names the rest: Enter, Escape, arrows, Ctrl/Alt+key, function keys, Shift+Tab.
 * <p>
 * Key codes and modifier bits are Minecraft's {@code InputConstants}, whose values change with its input layer
 * (GLFW up to 26.2, SDL from 26.3): the client builds the {@link Table} from them, so this stays plain Java.
 */
public final class Keys {
    /** `named`: keys with a tmux name (Escape, Enter, Tab, Up, F5, …); `chars`: keys that are one character with
     * Ctrl/Alt (a-z, 0-9, [ ] \ / - and space); the modifier masks of the key event. */
    public record Table(Map<Integer, String> named, Map<Integer, Character> chars, int shift, int ctrl, int alt) {}

    private final Table t;

    public Keys(Table t) {
        this.t = t;
    }

    public boolean ctrl(int mods) { return (mods & t.ctrl()) != 0; }
    public boolean shift(int mods) { return (mods & t.shift()) != 0; }
    public boolean alt(int mods) { return (mods & t.alt()) != 0; }

    /** The tmux key name for a key press, or null when it is a printable key (its character comes as text). */
    public String name(int key, int mods) {
        boolean ctrl = ctrl(mods), alt = alt(mods), shift = shift(mods);
        String base = t.named().get(key);
        if (base != null) {
            if (base.equals("Tab") && shift) return "BTab";
            String sh = shift && !base.equals("Tab") && !base.startsWith("F") && base.length() > 1 ? "S-" : "";
            return (ctrl ? "C-" : "") + (alt ? "M-" : "") + sh + base;
        }
        Character ch = t.chars().get(key);
        if (ch == null || !(ctrl || alt)) return null;      // a plain character comes as typed text
        return (ctrl ? "C-" : "") + (alt ? "M-" : "") + (ch == ' ' ? "Space" : String.valueOf(ch));
    }

    /** GLFW's codes (Minecraft up to 26.2): a reference for the tests. */
    public static Table glfw() {
        Map<Integer, String> named = new HashMap<>(Map.ofEntries(Map.entry(256, "Escape"), Map.entry(257, "Enter"),
                Map.entry(335, "Enter"), Map.entry(258, "Tab"), Map.entry(259, "BSpace"), Map.entry(260, "IC"),
                Map.entry(261, "DC"), Map.entry(262, "Right"), Map.entry(263, "Left"), Map.entry(264, "Down"),
                Map.entry(265, "Up"), Map.entry(266, "PPage"), Map.entry(267, "NPage"), Map.entry(268, "Home"),
                Map.entry(269, "End")));
        for (int i = 1; i <= 12; i++) named.put(289 + i, "F" + i);
        Map<Integer, Character> chars = new HashMap<>();
        for (int i = 0; i < 26; i++) chars.put(65 + i, (char) ('a' + i));
        for (int i = 0; i < 10; i++) chars.put(48 + i, (char) ('0' + i));
        chars.put(32, ' ');
        chars.put(91, '[');
        chars.put(93, ']');
        chars.put(92, '\\');
        chars.put(47, '/');
        chars.put(45, '-');
        return new Table(named, chars, 0x1, 0x2, 0x4);
    }
}
