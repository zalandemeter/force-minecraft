package cc.useforce.mc.core;

import java.util.List;
import java.util.Locale;

/** Which system this runs on, and the command that starts Force there. */
public final class Os {
    public enum Kind { LINUX, MAC, WINDOWS }

    /** From an `os.name`: "Mac OS X"/"Darwin" (any case, contains "mac" or "darwin") → MAC; starts with "windows"
     *  (any case) → WINDOWS; anything else, null or empty → LINUX. */
    public static Kind of(String osName) {
        if (osName == null) return Kind.LINUX;
        String s = osName.toLowerCase(Locale.ROOT);
        if (s.startsWith("windows")) return Kind.WINDOWS;
        if (s.contains("mac") || s.contains("darwin")) return Kind.MAC;
        return Kind.LINUX;
    }

    public static final Kind CURRENT = of(System.getProperty("os.name", ""));

    /** Force's default command on a system. */
    public static List<String> defaultCommand(Kind k) {
        return switch (k) {
            case WINDOWS -> List.of("wsl.exe", "--exec", "sh", "-lc", "exec ~/.local/bin/force api");
            case MAC, LINUX -> List.of("~/.local/bin/force", "api");
        };
    }
}
