package cc.useforce.mc.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web links on a terminal screen, found the way terminals find them: `http://` or `https://` text, without the
 * punctuation a sentence puts after it. Plain Java on {@link TerminalBuffer} runs, so it is unit-tested.
 */
public final class Links {
    /** A link on one row: its address and the cells it takes, [start, end). */
    public record Link(String url, int row, int start, int end) {}

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`\\x00-\\x1f\\x7f]+");
    private static final char OTHER = '\u0001';             // a cell no address has (not ASCII, a wide one's tail)

    private Links() {}

    /** One character per cell for a row's runs: ASCII as it is, anything else as a cell no link continues through. */
    public static String cells(List<TerminalBuffer.Run> runs) {
        StringBuilder b = new StringBuilder();
        for (TerminalBuffer.Run r : runs) {
            String t = r.text();
            for (int i = 0; i < t.length(); ) {
                int cp = t.codePointAt(i);
                i += Character.charCount(cp);
                if (cp == 0xFE0F) continue;                  // an emoji's marker takes no cell
                b.append(cp > 0 && cp < 0x80 ? (char) cp : OTHER);
            }
        }
        return b.toString();
    }

    /** The link covering cell `col` of a row (its {@link #cells}), or null. */
    public static Link at(String cells, int row, int col) {
        if (col < 0 || col >= cells.length()) return null;
        Matcher m = URL.matcher(cells);
        while (m.find()) {
            if (m.start() > col) break;
            String url = trim(m.group());
            int end = m.start() + url.length();
            if (col < end && url.indexOf("://") + 3 < url.length())                 // something after the scheme
                return new Link(url, row, m.start(), end);
        }
        return null;
    }

    /** A sentence's punctuation after an address is not part of it; a closing bracket is, when the address opened it. */
    static String trim(String url) {
        String u = url;
        while (!u.isEmpty()) {
            char last = u.charAt(u.length() - 1);
            if (".,;:!?*".indexOf(last) >= 0) {
                u = u.substring(0, u.length() - 1);
                continue;
            }
            char open = switch (last) {
                case ')' -> '(';
                case ']' -> '[';
                case '}' -> '{';
                default -> 0;
            };
            if (open != 0 && count(u, open) < count(u, last)) {
                u = u.substring(0, u.length() - 1);
                continue;
            }
            break;
        }
        return u;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
