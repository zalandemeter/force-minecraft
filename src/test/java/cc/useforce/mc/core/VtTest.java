package cc.useforce.mc.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class VtTest {
    private final ByteArrayOutputStream replies = new ByteArrayOutputStream();

    private Vt vt(int cols, int rows) {
        return new Vt(cols, rows, replies::writeBytes);
    }

    private static void feed(Vt vt, String s) {
        vt.feed(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String row(Vt vt, int y) {
        StringBuilder b = new StringBuilder();
        for (TerminalBuffer.Run r : vt.snapshot().lines().get(y)) b.append(r.text());
        return b.toString().replace(Vt.WIDE_TAIL, "");
    }

    private String replies() {
        String s = replies.toString(StandardCharsets.UTF_8);
        replies.reset();
        return s;
    }

    @Test
    void anEmojiMarkerStaysWithItsCharacter() {
        Vt vt = vt(10, 2);
        feed(vt, "\u26A0\uFE0F ok \u2705\uFE0F");   // ⚠️ (one cell) and ✅ (wide) with VS16; joiners are dropped
        assertEquals("\u26A0\uFE0F ok \u2705\uFE0F   ", row(vt, 0));
        feed(vt, "\r\n\uFE0Fx\u200D");                // a stray marker has nothing to mark
        assertEquals("x         ", row(vt, 1));
    }

    @Test
    void wrapAtTheEdgeAndScroll() {
        Vt vt = vt(5, 2);
        feed(vt, "abcdefgh");                 // abcde | fgh
        assertEquals("abcde", row(vt, 0));
        assertEquals("fgh  ", row(vt, 1));
        feed(vt, "\r\nxy");                   // scrolls: fgh | xy
        assertEquals("fgh  ", row(vt, 0));
        assertEquals("xy   ", row(vt, 1));
    }

    @Test
    void cursorMovesAndErases() {
        Vt vt = vt(10, 3);
        feed(vt, "0123456789\033[2;3HX\033[1;5H\033[K\033[3;1Habc\033[1D\033[1P");
        assertEquals("0123      ", row(vt, 0));
        assertEquals("  X       ", row(vt, 1));
        assertEquals("ab        ", row(vt, 2));
        feed(vt, "\033[2J");
        assertEquals("          ", row(vt, 1));
    }

    @Test
    void scrollRegionAndInsertDeleteLines() {
        Vt vt = vt(4, 4);
        feed(vt, "a\r\nb\r\nc\r\nd\033[2;3r\033[2;1H\033[L");   // region rows 2-3; insert a line at row 2
        assertEquals("a   ", row(vt, 0));
        assertEquals("    ", row(vt, 1));
        assertEquals("b   ", row(vt, 2));
        assertEquals("d   ", row(vt, 3), "outside the region: untouched");
        feed(vt, "\033[M");
        assertEquals("b   ", row(vt, 1));
        feed(vt, "\033[3;1H\nX");                               // a line feed at the region's bottom scrolls it
        assertEquals("    ", row(vt, 1));
        assertEquals("X   ", row(vt, 2));
    }

    @Test
    void sgrColoursAndAttributes() {
        Vt vt = vt(10, 1);
        feed(vt, "\033[1;31mR\033[0m\033[38;5;208mO\033[48;2;1;2;3mB\033[38:2::4:5:6mC\033[7;4mU\033[m.");
        var runs = vt.snapshot().lines().get(0);
        assertEquals("R", runs.get(0).text());
        assertTrue(runs.get(0).bold());
        assertEquals(0xcd0000, runs.get(0).fg());
        assertEquals(0xff8700, runs.get(1).fg());
        assertEquals(0x010203, runs.get(2).bg());
        assertEquals(0x040506, runs.get(3).fg(), "colon form with a colour space");
        assertTrue(runs.get(4).reverse() && runs.get(4).underline());
        assertNull(runs.get(5).fg());
    }

    @Test
    void wideCharactersTakeTwoCells() {
        Vt vt = vt(6, 2);
        feed(vt, "a漢b");
        var s = vt.snapshot();
        StringBuilder b = new StringBuilder();
        for (var r : s.lines().get(0)) b.append(r.text());
        assertEquals("a漢" + Vt.WIDE_TAIL + "b  ", b.toString());
        assertEquals(4, s.cursorX());
        feed(vt, "\r\n12345漢");                                 // does not fit at the edge: next line
        assertEquals("12345 ", row(vt, 0), "scrolled up; the last cell left blank");
        assertEquals("漢    ", row(vt, 1));
    }

    @Test
    void widthsAreTmuxs() {
        assertEquals(2, Vt.width(0x26A1), "⚡ is wide (emoji): tmux puts two cells there");
        assertEquals(2, Vt.width(0x2728), "✨");
        assertEquals(2, Vt.width(0x1F600));
        assertEquals(2, Vt.width(0x4E00));
        assertEquals(1, Vt.width(0xF0E7), "a Nerd Fonts icon (private use): one cell");
        assertEquals(1, Vt.width(0x23FA));
        assertEquals(1, Vt.width(0x2500));
        assertEquals(0, Vt.width(0x0301));
        assertEquals(0, Vt.width(0x200D));
    }

    @Test
    void alternateScreenKeepsTheMainOne() {
        Vt vt = vt(4, 2);
        feed(vt, "main");
        feed(vt, "\033[?1049h\033[Halt");
        assertEquals("alt ", row(vt, 0));
        feed(vt, "\033[?1049l");
        assertEquals("main", row(vt, 0));
    }

    @Test
    void decLineDrawing() {
        Vt vt = vt(4, 1);
        feed(vt, "\033(0lqk\033(Bq");
        assertEquals("┌─┐q", row(vt, 0));
    }

    @Test
    void modesForTheFrontend() {
        Vt vt = vt(4, 1);
        feed(vt, "\033[?1h\033[?2004h\033[?1000h\033[?1006h\033[?25l");
        assertTrue(vt.appCursor() && vt.bracketedPaste() && vt.sgrMouse());
        assertEquals(1000, vt.mouseMode());
        assertFalse(vt.snapshot().cursorVisible());
        feed(vt, "\033[?1000l\033[?25h");
        assertEquals(0, vt.mouseMode());
    }

    @Test
    void answersTheQueriesTmuxAndForceAsk() {
        Vt vt = vt(10, 3);
        feed(vt, "\033[c");
        assertEquals("\033[?62;22c", replies());
        feed(vt, "\033[2;4H\033[6n");
        assertEquals("\033[2;4R", replies());
        feed(vt, "\033]11;?\033\\\033]10;?\007");
        assertEquals("\033]11;rgb:2626/2626/2626\033\\\033]10;rgb:d0d0/d0d0/d0d0\033\\", replies());
        feed(vt, "\033[>c\033[?2026$p");
        assertEquals("\033[>1;10;0c\033[?2026;0$y", replies());
        feed(vt, "\033P+q544e\033\\x");                              // a DCS query: skipped, no reply
        assertEquals("", replies());
        assertTrue(row(vt, 1).contains("x"));
    }

    @Test
    void utf8SplitAcrossFeeds() {
        Vt vt = vt(4, 1);
        byte[] b = "é✓".getBytes(StandardCharsets.UTF_8);
        vt.feed(b, 0, 1);
        vt.feed(b, 1, b.length - 1);
        assertEquals("é✓  ", row(vt, 0));
    }

    @Test
    void osc8Hyperlinks() {
        Vt vt = vt(30, 1);
        vt.feed("\033]8;;https://x.y/z\033\\label\033]8;;\033\\ rest".getBytes(StandardCharsets.UTF_8));
        var runs = vt.snapshot().lines().get(0);
        assertEquals("label", runs.get(0).text());
        assertEquals("https://x.y/z", runs.get(0).link());
        assertNull(runs.get(1).link(), "the text after the link has no link");
        assertTrue(runs.get(1).text().startsWith(" rest"));
    }

    @Test
    void osc8WithBellAndParamsTerminators() {
        Vt vt1 = vt(30, 1);
        vt1.feed("\033]8;;https://x.y/z\007label\033]8;;\007 rest".getBytes(StandardCharsets.UTF_8));
        var r1 = vt1.snapshot().lines().get(0);
        assertEquals("https://x.y/z", r1.get(0).link());
        assertNull(r1.get(1).link());
        Vt vt2 = vt(30, 1);
        vt2.feed("\033]8;id=a;https://x.y/z\033\\label\033]8;;\033\\ rest".getBytes(StandardCharsets.UTF_8));
        var r2 = vt2.snapshot().lines().get(0);
        assertEquals("https://x.y/z", r2.get(0).link());
        assertNull(r2.get(1).link());
    }

    @Test
    void aWideCharacterInsideALinkCarriesIt() {
        Vt vt = vt(20, 1);
        vt.feed("\033]8;;https://x.y/z\033\\✅abc\033]8;;\033\\ rest".getBytes(StandardCharsets.UTF_8));
        var runs = vt.snapshot().lines().get(0);
        assertEquals("✅\u0000abc", runs.get(0).text());
        assertEquals("https://x.y/z", runs.get(0).link());
        assertNull(runs.get(1).link());
    }

    @Test
    void erasingARowRemovesItsLinks() {
        Vt vt = vt(20, 1);
        vt.feed("\033]8;;https://x.y/z\033\\abc\033[2Kz".getBytes(StandardCharsets.UTF_8));
        assertNull(vt.snapshot().lines().get(0).get(0).link());
    }

    @Test
    void aHugeOsc8UriIsIgnoredAndParsingRecovers() {
        Vt vt = vt(20, 1);
        String longUri = "https://x.y/" + "a".repeat(10_000);
        vt.feed(("\033]8;;" + longUri + "\033\\x\033]10;?\033\\").getBytes(StandardCharsets.UTF_8));
        assertNull(vt.snapshot().lines().get(0).get(0).link());
        assertEquals("\033]10;rgb:d0d0/d0d0/d0d0\033\\", replies(), "the next OSC still works");
    }

    @Test
    void risClearsAnOpenLink() {
        Vt vt = vt(10, 1);
        vt.feed("\033]8;;https://x.y/z\033\\a\033cb".getBytes(StandardCharsets.UTF_8));
        assertNull(vt.snapshot().lines().get(0).get(0).link());
    }

    @Test
    void osc8Utf8UriRoundTrips() {
        Vt vt = vt(20, 1);
        vt.feed(("\033]8;;https://x.y/é\033\\x\033]8;;\033\\").getBytes(StandardCharsets.UTF_8));
        assertEquals("https://x.y/é", vt.snapshot().lines().get(0).get(0).link());
    }

    @Test
    void resizeKeepsTheTopLeft() {
        Vt vt = vt(4, 2);
        feed(vt, "abcd\r\nefgh");
        vt.resize(2, 3);
        assertEquals("ab", row(vt, 0));
        assertEquals("ef", row(vt, 1));
        assertEquals("  ", row(vt, 2));
    }

    // --- input --------------------------------------------------------------------------------------------
    private static String s(byte[] b) {
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void keysAsAnXtermSendsThem() {
        assertEquals("\r", s(VtInput.key("Enter", false)));
        assertEquals("\033", s(VtInput.key("Escape", false)));
        assertEquals("\033[A", s(VtInput.key("Up", false)));
        assertEquals("\033OA", s(VtInput.key("Up", true)), "application cursor keys");
        assertEquals("\033[1;5A", s(VtInput.key("C-Up", true)));
        assertEquals("\033[1;2B", s(VtInput.key("S-Down", false)));
        assertEquals("\033[Z", s(VtInput.key("BTab", false)));
        assertEquals("\u0003", s(VtInput.key("C-c", false)));
        assertEquals("\u0000", s(VtInput.key("C-Space", false)), "Force's menu key");
        assertEquals("\0332", s(VtInput.key("M-2", false)), "Alt+2: a session in Force");
        assertEquals("\033[15~", s(VtInput.key("F5", false)));
        assertEquals("\033OP", s(VtInput.key("F1", false)));
        assertEquals("\033[5;5~", s(VtInput.key("C-PPage", false)));
        assertEquals("\u007f", s(VtInput.key("BSpace", false)));
        assertEquals("\u001b", s(VtInput.key("C-[", false)));
        assertNull(VtInput.key("NoSuchKey", false));
    }

    @Test
    void textAndPastes() {
        assertEquals("héllo", s(VtInput.text("héllo", false, true)));
        assertEquals("\033[200~a\rb\033[201~", s(VtInput.text("a\nb", true, true)));
        assertEquals("a\rb", s(VtInput.text("a\r\nb", true, false)));
    }

    @Test
    void mouseReports() {
        assertEquals("\033[<0;3;2M", s(VtInput.mouse(VtInput.LEFT, true, false, 2, 1, false, false, false, true)));
        assertEquals("\033[<0;3;2m", s(VtInput.mouse(VtInput.LEFT, false, false, 2, 1, false, false, false, true)));
        assertEquals("\033[<64;1;1M", s(VtInput.mouse(VtInput.WHEEL_UP, true, false, 0, 0, false, false, false, true)));
        assertEquals("\033[<32;5;5M", s(VtInput.mouse(VtInput.LEFT, true, true, 4, 4, false, false, false, true)));
        byte[] old = VtInput.mouse(VtInput.LEFT, true, false, 0, 0, false, false, false, false);
        assertArrayEquals(new byte[]{27, '[', 'M', 32, 33, 33}, old);
    }
}
