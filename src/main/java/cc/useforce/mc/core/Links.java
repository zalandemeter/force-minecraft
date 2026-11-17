package cc.useforce.mc.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web links on a terminal screen: `http://` or `https://` text, or the addresses a program glued on with OSC 8, found
 * the way terminals find them. Plain Java on {@link TerminalBuffer} runs, so it is unit-tested.
 */
public final class Links {
    /** Cells [start, end) of one row. */
    public record Span(int row, int start, int end) {}
    /** A web link: its address and the cells it takes (one span per row; more than one when it wraps). */
    public record Link(String url, List<Span> spans, boolean marked) {
        public boolean covers(int row, int col) {
            for (Span sp : spans) if (sp.row() == row && col >= sp.start() && col < sp.end()) return true;
            return false;
        }
    }

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'`\\x00-\\x1f\\x7f]+");
    static final char OTHER = '\u0001';             // a cell no address has
    static final char BORDER = '\u0002';           // a pane border (box drawing / block element)

    private Links() {}

    /** A row's cells, and each cell's OSC 8 link in parallel. */
    private static final class Row {
        final String cells;
        final String[] links;
        Row(String cells, String[] links) { this.cells = cells; this.links = links; }
    }

    private static Row row(List<TerminalBuffer.Run> runs) {
        StringBuilder c = new StringBuilder();
        List<String> l = new ArrayList<>();
        for (TerminalBuffer.Run r : runs) {
            String t = r.text();
            for (int i = 0; i < t.length(); ) {
                int cp = t.codePointAt(i);
                i += Character.charCount(cp);
                if (Vt.width(cp) == 0) continue;                 // VS16, combining marks, ZWJ: no cell
                if (cp >= 0x20 && cp <= 0x7E) c.append((char) cp);
                else if (cp >= 0x2500 && cp <= 0x259F) c.append(BORDER);
                else c.append(OTHER);
                l.add(r.link());
            }
        }
        return new Row(c.toString(), l.toArray(new String[0]));
    }

    /** One character per cell of a row: printable ASCII as it is, box drawing as {@link #BORDER}, anything else as
     *  {@link #OTHER} (no address runs through either). */
    public static String cells(List<TerminalBuffer.Run> runs) {
        return row(runs).cells;
    }

    /** Every web link on a screen, top to bottom (by first span's row, then start). */
    public static List<Link> find(TerminalBuffer.Snapshot s) {
        int rows = s.lines().size(), cols = s.cols();
        Row[] screen = new Row[rows];
        for (int r = 0; r < rows; r++) screen[r] = row(s.lines().get(r));

        List<Link> out = new ArrayList<>();
        boolean[][] claimed = new boolean[rows][];

        // --- marked links (OSC 8) — first ---
        Map<String, Link> byUrl = new HashMap<>();
        for (int r = 0; r < rows; r++) {
            String[] ln = screen[r].links;
            int x = 0;
            while (x < ln.length) {
                if (ln[x] == null) { x++; continue; }
                String uri = ln[x];
                int start = x;
                while (x < ln.length && uri.equals(ln[x])) x++;
                int end = x;
                if (!isHttp(uri)) continue;
                Link l = byUrl.get(uri);
                if (l != null) {
                    List<Span> spans = l.spans();
                    int lastRow = spans.get(spans.size() - 1).row();
                    if (lastRow == r || lastRow == r - 1) {
                        spans.add(new Span(r, start, end));
                        continue;
                    }
                }
                List<Span> spans = new ArrayList<>();
                spans.add(new Span(r, start, end));
                Link nl = new Link(uri, spans, true);
                out.add(nl);
                byUrl.put(uri, nl);
            }
        }
        for (Link l : out) for (Span sp : l.spans()) claim(claimed, screen[sp.row()].cells, sp.row(), sp.start(), sp.end());

        // --- plain links (regex) — then ---
        for (int r = 0; r < rows; r++) {
            String cells = screen[r].cells;
            Matcher m = URL.matcher(cells);
            while (m.find()) {
                int start = m.start(), end = m.end();
                if (anyClaimed(claimed, cells, r, start, end)) continue;
                List<Span> spans = new ArrayList<>();
                spans.add(new Span(r, start, end));
                claim(claimed, cells, r, start, end);
                StringBuilder address = new StringBuilder(cells.substring(start, end));
                String cur = cells;                                   // the row the address has reached
                int cr = r, a = start, e = end;
                while (reachesEdge(cur, cols, e) && cr + 1 < rows) {
                    int nr = cr + 1;
                    String next = screen[nr].cells;
                    int L = edgeLeft(next, a - 1);
                    int c0 = firstNonBlank(next, L);
                    if (c0 < 0 || c0 > a) break;
                    int rRight = edgeRight(next, cols, c0);
                    if (c0 >= rRight) break;
                    int e2 = urlRunEnd(next, c0);
                    if (e2 == c0) break;
                    String cont = next.substring(c0, e2);
                    if (cont.regionMatches(true, 0, "http://", 0, 7)
                            || cont.regionMatches(true, 0, "https://", 0, 8)) break;
                    if (anyClaimed(claimed, next, nr, c0, e2)) break;
                    if (!reachesEdge(next, cols, e2) && !containsPunct(trim(cont))) break;
                    address.append(cont);
                    spans.add(new Span(nr, c0, e2));
                    claim(claimed, next, nr, c0, e2);
                    a = c0;
                    e = e2;
                    cur = next;
                    cr = nr;
                }
                String url = trim(address.toString());
                shorten(spans, address.length() - url.length());
                int sch = url.indexOf("://");
                if (sch < 0 || sch + 3 >= url.length()) continue;      // nothing after ://
                out.add(new Link(url, List.copyOf(spans), false));
            }
        }
        return out;
    }

    /** The link covering a cell, or null. */
    public static Link at(TerminalBuffer.Snapshot s, int row, int col) {
        if (row < 0 || row >= s.lines().size()) return null;
        for (Link l : find(s)) if (l.covers(row, col)) return l;
        return null;
    }

    private static boolean isHttp(String uri) {
        String u = uri.toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    /** The first BORDER at or after {@code from} on a row, else the screen's edge. */
    private static int edgeRight(String cells, int cols, int from) {
        for (int i = from; i < cells.length(); i++) if (cells.charAt(i) == BORDER) return i;
        return cols;
    }

    /** A segment ending at {@code end} touches the right edge when a border or the screen's edge is at most one cell away. */
    private static boolean reachesEdge(String cells, int cols, int end) {
        int R = edgeRight(cells, cols, end);
        if (R - end > 1) return false;
        for (int i = end; i < R && i < cells.length(); i++) if (cells.charAt(i) != ' ') return false;   // past it: blank
        return true;
    }

    /** One past the nearest BORDER at or before {@code aMinusOne}, else 0 (the left edge). */
    private static int edgeLeft(String cells, int aMinusOne) {
        for (int i = Math.min(aMinusOne, cells.length() - 1); i >= 0; i--)
            if (cells.charAt(i) == BORDER) return i + 1;
        return 0;
    }

    private static int firstNonBlank(String cells, int from) {
        for (int i = from; i < cells.length(); i++) if (cells.charAt(i) != ' ') return i;
        return -1;
    }

    /** A character a URL may hold: printable ASCII but not the quoting ones. */
    private static boolean isUrlChar(char c) {
        if (c < 0x21 || c > 0x7E) return false;
        return "<>\"'`".indexOf(c) < 0;
    }

    private static int urlRunEnd(String cells, int from) {
        int i = from;
        while (i < cells.length() && isUrlChar(cells.charAt(i))) i++;
        return i;
    }

    private static boolean containsPunct(String s) {
        for (int i = 0; i < s.length(); i++)
            if ("/?#=&%._~-+:@".indexOf(s.charAt(i)) >= 0) return true;
        return false;
    }

    private static void claim(boolean[][] claimed, String cells, int row, int from, int to) {
        if (claimed[row] == null) claimed[row] = new boolean[cells.length()];
        for (int i = from; i < to && i < claimed[row].length; i++) claimed[row][i] = true;
    }

    private static boolean anyClaimed(boolean[][] claimed, String cells, int row, int from, int to) {
        boolean[] arr = claimed[row];
        if (arr == null) return false;
        for (int i = from; i < to && i < arr.length; i++) if (arr[i]) return true;
        return false;
    }

    private static void shorten(List<Span> spans, int remove) {
        int rem = remove;
        for (int i = spans.size() - 1; i >= 0 && rem > 0; i--) {
            Span sp = spans.get(i);
            int len = sp.end() - sp.start();
            if (len <= rem) {
                spans.remove(i);
                rem -= len;
            } else {
                spans.set(i, new Span(sp.row(), sp.start(), sp.end() - rem));
                rem = 0;
            }
        }
    }

    /** Punctuation after the address is trimmed away; a bracket is kept when the address opened it. */
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
