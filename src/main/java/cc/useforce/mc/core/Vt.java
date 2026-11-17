package cc.useforce.mc.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * A terminal emulator for what a tmux client sends to an xterm-256color terminal (Force's app in vt mode, SPEC §54):
 * UTF-8 text with wide characters, cursor movement, erase/insert/delete, scroll regions, the alternate screen, SGR
 * (16/256/true colour), DEC line drawing, modes (cursor, autowrap, mouse, bracketed paste, application cursor keys),
 * and answers to the queries tmux and Force ask (device attributes, cursor position, colours). It fills the same
 * {@link TerminalBuffer} the cells mode does, so the view does not care which mode drew it. Plain Java: tested
 * without Minecraft. Feed it from one thread; {@link #snapshot()} from any.
 */
public final class Vt {
    public static final int BOLD = 1, DIM = 2, ITALIC = 4, UNDERLINE = 8, REVERSE = 16, INVISIBLE = 32;
    /** The colours Force paints with (its theme): what the terminal answers when asked for its own. */
    public static final int DEFAULT_FG = 0xD0D0D0, DEFAULT_BG = 0x262626;
    /** The second cell of a wide character: drawn by the first one. */
    public static final String WIDE_TAIL = "\u0000";

    static final class Cell {
        String ch = " ";
        int fg = -1, bg = -1, attrs;

        void set(Cell o) {
            ch = o.ch;
            fg = o.fg;
            bg = o.bg;
            attrs = o.attrs;
        }

        void clear(int bgColour) {
            ch = " ";
            fg = -1;
            bg = bgColour;
            attrs = 0;
        }
    }

    private final Consumer<byte[]> reply;
    private int cols, rows;
    private Cell[][] main, alt, screen;
    private boolean altActive;
    private int cx, cy, top, bottom;
    private boolean wrapPending;
    private int fg = -1, bg = -1, attrs;
    private boolean lineDrawing;                         // G0 is DEC special graphics (ESC ( 0)
    private boolean[] tabs;
    private final int[] saved = new int[6];              // DECSC: x, y, fg, bg, attrs, line drawing
    private boolean savedSet;
    // modes
    private boolean autowrap = true, cursorVisible = true, appCursor, bracketedPaste, sgrMouse, focusEvents,
            insert, origin;
    private int mouseMode;                               // 0, 1000 (clicks), 1002 (+ drags), 1003 (+ moves)
    private long version;
    // the parser
    private enum State { GROUND, ESC, ESC_INTER, CSI, OSC, OSC_ESC, STRING, STRING_ESC }
    private State state = State.GROUND;
    private final StringBuilder params = new StringBuilder();
    private final StringBuilder osc = new StringBuilder();
    private char escInter;
    private int utf8Need, utf8Cp;

    public Vt(int cols, int rows, Consumer<byte[]> reply) {
        this.reply = reply;
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        main = grid(this.cols, this.rows);
        alt = grid(this.cols, this.rows);
        screen = main;
        bottom = this.rows - 1;
        resetTabs();
    }

    private static Cell[][] grid(int c, int r) {
        Cell[][] g = new Cell[r][c];
        for (Cell[] row : g) for (int i = 0; i < c; i++) row[i] = new Cell();
        return g;
    }

    private void resetTabs() {
        tabs = new boolean[cols];
        for (int i = 8; i < cols; i += 8) tabs[i] = true;
    }

    // --- modes the frontend needs ------------------------------------------------------------------------------
    public synchronized boolean appCursor() { return appCursor; }
    public synchronized boolean bracketedPaste() { return bracketedPaste; }
    public synchronized int mouseMode() { return mouseMode; }
    public synchronized boolean sgrMouse() { return sgrMouse; }
    public synchronized boolean focusEvents() { return focusEvents; }
    public synchronized int cols() { return cols; }
    public synchronized int rows() { return rows; }
    public synchronized long version() { return version; }

    /** A new size: the text stays top-left, cut or padded (tmux redraws everything after a resize anyway). */
    public synchronized void resize(int c, int r) {
        c = Math.max(1, c);
        r = Math.max(1, r);
        if (c == cols && r == rows) return;
        main = resized(main, c, r);
        alt = resized(alt, c, r);
        screen = altActive ? alt : main;
        cols = c;
        rows = r;
        top = 0;
        bottom = r - 1;
        cx = Math.min(cx, c - 1);
        cy = Math.min(cy, r - 1);
        wrapPending = false;
        resetTabs();
        version++;
    }

    private static Cell[][] resized(Cell[][] old, int c, int r) {
        Cell[][] g = grid(c, r);
        for (int y = 0; y < Math.min(r, old.length); y++)
            for (int x = 0; x < Math.min(c, old[y].length); x++) g[y][x].set(old[y][x]);
        return g;
    }

    // --- input ------------------------------------------------------------------------------------------------
    public synchronized void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    public synchronized void feed(byte[] data, int off, int len) {
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            if (state == State.GROUND && b >= 0x80) {
                utf8(b);
                continue;
            }
            if (utf8Need > 0) {                            // a sequence cut by a control byte
                utf8Need = 0;
                print(0xFFFD);
            }
            byteIn(b);
        }
        version++;
    }

    private void utf8(int b) {
        if (utf8Need > 0 && (b & 0xC0) == 0x80) {
            utf8Cp = (utf8Cp << 6) | (b & 0x3F);
            if (--utf8Need == 0) print(utf8Cp);
            return;
        }
        if (utf8Need > 0) print(0xFFFD);
        if ((b & 0xE0) == 0xC0) {
            utf8Cp = b & 0x1F;
            utf8Need = 1;
        } else if ((b & 0xF0) == 0xE0) {
            utf8Cp = b & 0x0F;
            utf8Need = 2;
        } else if ((b & 0xF8) == 0xF0) {
            utf8Cp = b & 0x07;
            utf8Need = 3;
        } else {
            utf8Need = 0;
            print(0xFFFD);
        }
    }

    private void byteIn(int b) {
        switch (state) {
            case GROUND -> ground(b);
            case ESC -> esc(b);
            case ESC_INTER -> {
                if (escInter == '(') lineDrawing = b == '0';
                state = State.GROUND;
            }
            case CSI -> {
                if (b >= 0x40 && b <= 0x7E) {
                    csi((char) b);
                    state = State.GROUND;
                } else if (b == 0x1B) {
                    state = State.ESC;
                } else if (b >= 0x20) {
                    params.append((char) b);
                } else control(b);
            }
            case OSC -> {
                if (b == 0x07) {
                    osc();
                    state = State.GROUND;
                } else if (b == 0x1B) state = State.OSC_ESC;
                else osc.append((char) b);
            }
            case OSC_ESC -> {
                if (b == '\\') osc();
                state = State.GROUND;
            }
            case STRING -> {                               // DCS, SOS, PM, APC: skipped to the ST
                if (b == 0x1B) state = State.STRING_ESC;
                else if (b == 0x07) state = State.GROUND;
            }
            case STRING_ESC -> state = b == '\\' ? State.GROUND : State.STRING;
        }
    }

    private void ground(int b) {
        if (b == 0x1B) {
            state = State.ESC;
        } else if (b < 0x20 || b == 0x7F) {
            control(b);
        } else print(b);
    }

    private void control(int b) {
        switch (b) {
            case 0x07 -> { }                               // bell
            case 0x08 -> {                                 // backspace
                if (cx > 0) cx--;
                wrapPending = false;
            }
            case 0x09 -> {                                 // tab
                int x = cx + 1;
                while (x < cols - 1 && !tabs[x]) x++;
                cx = Math.min(cols - 1, x);
                wrapPending = false;
            }
            case 0x0A, 0x0B, 0x0C -> lineFeed();
            case 0x0D -> {
                cx = 0;
                wrapPending = false;
            }
            case 0x0E, 0x0F -> { }                         // shift out/in: G1 unused
            default -> { }
        }
    }

    private void esc(int b) {
        state = State.GROUND;
        switch (b) {
            case '[' -> {
                params.setLength(0);
                state = State.CSI;
            }
            case ']' -> {
                osc.setLength(0);
                state = State.OSC;
            }
            case 'P', 'X', '^', '_' -> state = State.STRING;
            case '(', ')', '*', '+', '#', '%', ' ' -> {
                escInter = (char) b;
                state = State.ESC_INTER;
            }
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'D' -> lineFeed();
            case 'E' -> {
                cx = 0;
                lineFeed();
            }
            case 'M' -> reverseIndex();
            case 'H' -> {
                if (cx < cols) tabs[cx] = true;
            }
            case 'c' -> reset();
            default -> { }                                 // = > \ and the rest: nothing to keep
        }
    }

    // --- text ---------------------------------------------------------------------------------------------------
    private static final String DEC = "◆▒␉␌␍␊°±␤␋┘┐┌└┼⎺⎻─⎼⎽├┤┴┬│≤≥π≠£·";   // 0x60..0x7E

    private void print(int cp) {
        if (lineDrawing && cp >= 0x60 && cp <= 0x7E) cp = DEC.charAt(cp - 0x60);
        int w = width(cp);
        if (w == 0) {                                      // combining marks and joiners: dropped, but
            if (cp == EMOJI) emoji();                      // VS16 stays with its character: drawn as an emoji
            return;
        }
        if (wrapPending && autowrap) {
            cx = 0;
            lineFeed();
        }
        wrapPending = false;
        if (w == 2 && cx == cols - 1) {                    // a wide character does not fit: next line
            if (autowrap) {
                screen[cy][cx].clear(bg);
                cx = 0;
                lineFeed();
            } else return;
        }
        if (insert) shiftRight(cy, cx, w);
        Cell c = screen[cy][cx];
        if (c.ch.equals(WIDE_TAIL) && cx > 0) screen[cy][cx - 1].clear(bg);   // overwriting half of a wide one
        c.ch = new String(Character.toChars(cp));
        c.fg = fg;
        c.bg = bg;
        c.attrs = attrs;
        if (w == 2) {
            Cell t = screen[cy][cx + 1];
            t.ch = WIDE_TAIL;
            t.fg = fg;
            t.bg = bg;
            t.attrs = attrs;
        }
        cx += w;
        if (cx >= cols) {
            cx = cols - 1;
            if (autowrap) wrapPending = true;
        }
    }

    /** VARIATION SELECTOR-16: the character before it is drawn as an emoji (in colour), as a terminal does. */
    public static final int EMOJI = 0xFE0F;

    private void emoji() {
        int x = wrapPending ? cx : cx - 1;
        if (x < 0) return;
        Cell c = screen[cy][x];
        if (c.ch.equals(WIDE_TAIL) && x > 0) c = screen[cy][x - 1];
        if (!c.ch.equals(" ") && !c.ch.equals(WIDE_TAIL) && c.ch.indexOf(EMOJI) < 0) c.ch += "\uFE0F";
    }

    /** Terminal width of a code point, as tmux (glibc's wcwidth) counts it: 0 (combining), 1, or 2 (wide). */
    static int width(int cp) {
        if (cp < 0x300) return 1;
        if (Widths.in(Widths.ZERO, cp)) return 0;
        return Widths.in(Widths.WIDE, cp) ? 2 : 1;
    }

    private void shiftRight(int y, int x, int n) {
        Cell[] row = screen[y];
        for (int i = cols - 1; i >= x + n; i--) row[i].set(row[i - n]);
        for (int i = x; i < Math.min(cols, x + n); i++) row[i].clear(bg);
    }

    private void lineFeed() {
        wrapPending = false;
        if (cy == bottom) scrollUp(1);
        else if (cy < rows - 1) cy++;
    }

    private void reverseIndex() {
        wrapPending = false;
        if (cy == top) scrollDown(1);
        else if (cy > 0) cy--;
    }

    private void scrollUp(int n) {
        n = Math.min(n, bottom - top + 1);
        for (int y = top; y <= bottom; y++) {
            if (y + n <= bottom) swapRow(y, y + n);
            else clearRow(y);
        }
    }

    private void scrollDown(int n) {
        n = Math.min(n, bottom - top + 1);
        for (int y = bottom; y >= top; y--) {
            if (y - n >= top) swapRow(y, y - n);
            else clearRow(y);
        }
    }

    private void swapRow(int to, int from) {
        Cell[] t = screen[to];
        screen[to] = screen[from];
        screen[from] = t;
    }

    private void clearRow(int y) {
        for (Cell c : screen[y]) c.clear(bg);
    }

    private void clear(int y, int x0, int x1) {
        for (int x = Math.max(0, x0); x < Math.min(cols, x1); x++) screen[y][x].clear(bg);
    }

    private void saveCursor() {
        saved[0] = cx;
        saved[1] = cy;
        saved[2] = fg;
        saved[3] = bg;
        saved[4] = attrs;
        saved[5] = lineDrawing ? 1 : 0;
        savedSet = true;
    }

    private void restoreCursor() {
        if (!savedSet) {
            cx = cy = 0;
            return;
        }
        cx = Math.min(saved[0], cols - 1);
        cy = Math.min(saved[1], rows - 1);
        fg = saved[2];
        bg = saved[3];
        attrs = saved[4];
        lineDrawing = saved[5] == 1;
        wrapPending = false;
    }

    private void reset() {
        fg = bg = -1;
        attrs = 0;
        top = 0;
        bottom = rows - 1;
        cx = cy = 0;
        autowrap = cursorVisible = true;
        appCursor = bracketedPaste = sgrMouse = focusEvents = insert = origin = lineDrawing = false;
        mouseMode = 0;
        altActive = false;
        screen = main;
        for (int y = 0; y < rows; y++) clearRow(y);
        resetTabs();
    }

    // --- CSI ------------------------------------------------------------------------------------------------
    private void csi(char fin) {
        String p = params.toString();
        char lead = !p.isEmpty() && "?>=<".indexOf(p.charAt(0)) >= 0 ? p.charAt(0) : 0;
        String body = lead != 0 ? p.substring(1) : p;
        String inter = "";
        int cut = body.length();
        while (cut > 0 && body.charAt(cut - 1) >= 0x20 && body.charAt(cut - 1) <= 0x2F) cut--;
        inter = body.substring(cut);
        body = body.substring(0, cut);
        int[] a = numbers(body);
        int a0 = a.length > 0 ? a[0] : 0, n = Math.max(1, a0);
        if (lead == '?' && (fin == 'h' || fin == 'l')) {
            for (int m : a) privateMode(m, fin == 'h');
            return;
        }
        if (!inter.isEmpty()) {
            if (inter.equals("$") && fin == 'p') {                   // DECRQM: we report "not recognised"
                answer("\033[" + (lead == '?' ? "?" : "") + a0 + ";0$y");
            }
            return;                                                    // DECSCUSR (space q) and the rest
        }
        if (lead == '>') {
            if (fin == 'c') answer("\033[>1;10;0c");                   // secondary device attributes
            else if (fin == 'q') answer("\033P>|force-minecraft\033\\"); // XTVERSION
            return;
        }
        if (lead != 0) return;
        switch (fin) {
            case '@' -> shiftRight(cy, cx, Math.min(n, cols - cx));
            case 'A' -> moveTo(cx, Math.max(cy - n, cy >= top ? top : 0));
            case 'B', 'e' -> moveTo(cx, Math.min(cy + n, cy <= bottom ? bottom : rows - 1));
            case 'C', 'a' -> moveTo(Math.min(cx + n, cols - 1), cy);
            case 'D' -> moveTo(Math.max(cx - n, 0), cy);
            case 'E' -> moveTo(0, Math.min(cy + n, bottom));
            case 'F' -> moveTo(0, Math.max(cy - n, top));
            case 'G', '`' -> moveTo(Math.min(n - 1, cols - 1), cy);
            case 'H', 'f' -> {
                int y = Math.max(1, a0) - 1 + (origin ? top : 0), x = Math.max(1, a.length > 1 ? a[1] : 1) - 1;
                moveTo(Math.min(x, cols - 1), Math.min(y, rows - 1));
            }
            case 'I' -> {
                for (int i = 0; i < n; i++) control(0x09);
            }
            case 'J' -> {
                if (a0 == 0) {
                    clear(cy, cx, cols);
                    for (int y = cy + 1; y < rows; y++) clearRow(y);
                } else if (a0 == 1) {
                    clear(cy, 0, cx + 1);
                    for (int y = 0; y < cy; y++) clearRow(y);
                } else for (int y = 0; y < rows; y++) clearRow(y);
            }
            case 'K' -> {
                if (a0 == 0) clear(cy, cx, cols);
                else if (a0 == 1) clear(cy, 0, cx + 1);
                else clear(cy, 0, cols);
            }
            case 'L' -> {
                if (cy >= top && cy <= bottom) {
                    int t = top;
                    top = cy;
                    scrollDown(n);
                    top = t;
                }
            }
            case 'M' -> {
                if (cy >= top && cy <= bottom) {
                    int t = top;
                    top = cy;
                    scrollUp(n);
                    top = t;
                }
            }
            case 'P' -> {
                Cell[] row = screen[cy];
                n = Math.min(n, cols - cx);
                for (int x = cx; x < cols - n; x++) row[x].set(row[x + n]);
                for (int x = cols - n; x < cols; x++) row[x].clear(bg);
            }
            case 'S' -> scrollUp(n);
            case 'T' -> scrollDown(n);
            case 'X' -> clear(cy, cx, cx + n);
            case 'Z' -> {
                int x = cx;
                for (int i = 0; i < n; i++) {
                    x--;
                    while (x > 0 && !tabs[x]) x--;
                }
                moveTo(Math.max(0, x), cy);
            }
            case 'b' -> {
                if (cx > 0) {
                    String last = screen[cy][cx - 1].ch;
                    int cp = last.codePointAt(0);
                    for (int i = 0; i < n; i++) print(cp);
                }
            }
            case 'c' -> answer("\033[?62;22c");                        // a VT220 with colour
            case 'd' -> moveTo(cx, Math.min(n - 1, rows - 1));
            case 'g' -> {
                if (a0 == 0 && cx < cols) tabs[cx] = false;
                else if (a0 == 3) Arrays.fill(tabs, false);
            }
            case 'h', 'l' -> {
                for (int m : a) if (m == 4) insert = fin == 'h';
            }
            case 'm' -> sgr(body);
            case 'n' -> {
                if (a0 == 5) answer("\033[0n");
                else if (a0 == 6) answer("\033[" + (cy + 1) + ";" + (cx + 1) + "R");
            }
            case 'r' -> {
                int t = Math.max(1, a0) - 1, b = (a.length > 1 && a[1] > 0 ? a[1] : rows) - 1;
                if (t < b && b < rows) {
                    top = t;
                    bottom = b;
                    moveTo(0, origin ? top : 0);
                }
            }
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            case 't' -> {
                if (a0 == 18) answer("\033[8;" + rows + ";" + cols + "t");
            }
            default -> { }
        }
    }

    private void moveTo(int x, int y) {
        cx = Math.max(0, Math.min(x, cols - 1));
        cy = Math.max(0, Math.min(y, rows - 1));
        wrapPending = false;
    }

    private void privateMode(int m, boolean on) {
        switch (m) {
            case 1 -> appCursor = on;
            case 6 -> {
                origin = on;
                moveTo(0, on ? top : 0);
            }
            case 7 -> autowrap = on;
            case 25 -> cursorVisible = on;
            case 47, 1047 -> setAlt(on, false);
            case 1049 -> setAlt(on, true);
            case 1000, 1002, 1003 -> mouseMode = on ? m : (mouseMode == m ? 0 : mouseMode);
            case 1004 -> focusEvents = on;
            case 1006 -> sgrMouse = on;
            case 2004 -> bracketedPaste = on;
            default -> { }                                             // 12, 2026, … : nothing to keep
        }
    }

    private void setAlt(boolean on, boolean save) {
        if (on == altActive) return;
        if (on) {
            if (save) saveCursor();
            altActive = true;
            screen = alt;
            for (int y = 0; y < rows; y++) clearRow(y);
        } else {
            altActive = false;
            screen = main;
            if (save) restoreCursor();
        }
    }

    private void sgr(String body) {
        if (body.isEmpty()) {
            fg = bg = -1;
            attrs = 0;
            return;
        }
        // parameters split on ';'; a ':' group (38:2::r:g:b, 4:3) is one parameter with sub-parameters
        String[] groups = body.split(";", -1);
        List<int[]> ps = new ArrayList<>();
        for (String g : groups) {
            String[] sub = g.split(":", -1);
            int[] v = new int[sub.length];
            for (int i = 0; i < sub.length; i++) v[i] = parse(sub[i]);
            ps.add(v);
        }
        for (int i = 0; i < ps.size(); i++) {
            int[] v = ps.get(i);
            int p = v[0];
            switch (p) {
                case 0 -> {
                    fg = bg = -1;
                    attrs = 0;
                }
                case 1 -> attrs |= BOLD;
                case 2 -> attrs |= DIM;
                case 3 -> attrs |= ITALIC;
                case 4, 21 -> attrs = v.length > 1 && v[1] == 0 ? attrs & ~UNDERLINE : attrs | UNDERLINE;
                case 7 -> attrs |= REVERSE;
                case 8 -> attrs |= INVISIBLE;
                case 22 -> attrs &= ~(BOLD | DIM);
                case 23 -> attrs &= ~ITALIC;
                case 24 -> attrs &= ~UNDERLINE;
                case 27 -> attrs &= ~REVERSE;
                case 28 -> attrs &= ~INVISIBLE;
                case 39 -> fg = -1;
                case 49 -> bg = -1;
                case 38, 48, 58 -> {
                    int colour;
                    int[] rest;
                    if (v.length > 1) {                                  // colon form
                        rest = Arrays.copyOfRange(v, 1, v.length);
                    } else {                                             // semicolon form: take what follows
                        int kind = i + 1 < ps.size() ? ps.get(i + 1)[0] : -1;
                        int take = kind == 5 ? 2 : kind == 2 ? 4 : 1;
                        rest = new int[take];
                        for (int k = 0; k < take && i + 1 + k < ps.size(); k++) rest[k] = ps.get(i + 1 + k)[0];
                        i += take;
                    }
                    colour = extended(rest);
                    if (p == 38) fg = colour;
                    else if (p == 48) bg = colour;
                }
                default -> {
                    if (p >= 30 && p <= 37) fg = palette(p - 30);
                    else if (p >= 40 && p <= 47) bg = palette(p - 40);
                    else if (p >= 90 && p <= 97) fg = palette(p - 90 + 8);
                    else if (p >= 100 && p <= 107) bg = palette(p - 100 + 8);
                }
            }
        }
    }

    private static int extended(int[] rest) {
        if (rest.length >= 2 && rest[0] == 5) return palette(rest[1] & 0xFF);
        if (rest.length >= 4 && rest[0] == 2) {
            // 38;2;r;g;b, or 38:2:CS:r:g:b with a colour-space id
            int o = rest.length >= 5 ? 2 : 1;
            return ((rest[o] & 0xFF) << 16) | ((rest[o + 1] & 0xFF) << 8) | (rest[o + 2] & 0xFF);
        }
        return -1;
    }

    private static final int[] BASE = {0x000000, 0xcd0000, 0x00cd00, 0xcdcd00, 0x0000ee, 0xcd00cd, 0x00cdcd, 0xe5e5e5,
            0x7f7f7f, 0xff0000, 0x00ff00, 0xffff00, 0x5c5cff, 0xff00ff, 0x00ffff, 0xffffff};

    static int palette(int n) {
        if (n < 16) return BASE[n];
        if (n < 232) {
            n -= 16;
            int[] lv = {0, 95, 135, 175, 215, 255};
            return (lv[n / 36] << 16) | (lv[(n / 6) % 6] << 8) | lv[n % 6];
        }
        int g = 8 + (n - 232) * 10;
        return (g << 16) | (g << 8) | g;
    }

    private static int[] numbers(String body) {
        if (body.isEmpty()) return new int[0];
        String[] parts = body.split("[;:]", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = parse(parts[i]);
        return out;
    }

    private static int parse(String s) {
        if (s.isEmpty()) return 0;
        try {
            return Math.min(Integer.parseInt(s), 1_000_000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // --- OSC ------------------------------------------------------------------------------------------------
    private void osc() {
        String s = osc.toString();
        int semi = s.indexOf(';');
        String code = semi < 0 ? s : s.substring(0, semi), arg = semi < 0 ? "" : s.substring(semi + 1);
        switch (code) {
            case "10" -> {
                if (arg.equals("?")) answer("\033]10;" + rgb(DEFAULT_FG) + "\033\\");
            }
            case "11" -> {
                if (arg.equals("?")) answer("\033]11;" + rgb(DEFAULT_BG) + "\033\\");
            }
            case "12" -> {
                if (arg.equals("?")) answer("\033]12;" + rgb(DEFAULT_FG) + "\033\\");
            }
            default -> { }                                             // titles, clipboard, links: not kept
        }
    }

    private static String rgb(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return String.format("rgb:%02x%02x/%02x%02x/%02x%02x", r, r, g, g, b, b);
    }

    private void answer(String s) {
        reply.accept(s.getBytes(StandardCharsets.UTF_8));
    }

    // --- output -----------------------------------------------------------------------------------------------
    /** The screen as styled runs (wide characters followed by {@link #WIDE_TAIL}), and the cursor. */
    public synchronized TerminalBuffer.Snapshot snapshot() {
        List<List<TerminalBuffer.Run>> lines = new ArrayList<>(rows);
        for (int y = 0; y < rows; y++) {
            List<TerminalBuffer.Run> runs = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            Cell style = null;
            for (int x = 0; x < cols; x++) {
                Cell c = screen[y][x];
                if (style != null && (c.fg != style.fg || c.bg != style.bg || c.attrs != style.attrs)) {
                    runs.add(run(text.toString(), style));
                    text.setLength(0);
                }
                if (text.isEmpty()) style = c;
                text.append((c.attrs & INVISIBLE) != 0 && !c.ch.equals(WIDE_TAIL) ? " " : c.ch);
            }
            if (!text.isEmpty()) runs.add(run(text.toString(), style));
            lines.add(runs);
        }
        return new TerminalBuffer.Snapshot(cols, rows, lines, cx, cy, cursorVisible, 0, version);
    }

    private static TerminalBuffer.Run run(String text, Cell s) {
        return new TerminalBuffer.Run(text, s.fg < 0 ? null : s.fg, s.bg < 0 ? null : s.bg, (s.attrs & BOLD) != 0,
                (s.attrs & ITALIC) != 0, (s.attrs & UNDERLINE) != 0, (s.attrs & REVERSE) != 0,
                (s.attrs & DIM) != 0);
    }
}
