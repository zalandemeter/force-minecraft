package cc.useforce.mc.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinksTest {
    private static TerminalBuffer.Run run(String text) {
        return new TerminalBuffer.Run(text, null, null, false, false, false, false, false);
    }

    /** A screen of one run per row; rows beyond the text are absent (treated as empty). */
    private static TerminalBuffer.Snapshot snap(int cols, String... rows) {
        List<List<TerminalBuffer.Run>> lines = new ArrayList<>();
        for (String row : rows) lines.add(List.of(run(row)));
        return new TerminalBuffer.Snapshot(cols, rows.length, lines, 0, 0, false, 0, 0);
    }

    @Test
    void aLinkUnderTheCell() {
        String row = "see https://github.com/x/y/pull/8 for it";
        TerminalBuffer.Snapshot s = snap(80, row);
        Links.Link l = Links.at(s, 0, 10);
        assertNotNull(l);
        assertEquals("https://github.com/x/y/pull/8", l.url());
        assertEquals(1, l.spans().size());
        assertEquals(4, l.spans().get(0).start());
        assertEquals(33, l.spans().get(0).end());
        assertFalse(l.marked());
        assertNull(Links.at(s, 0, 2), "before it");
        assertNull(Links.at(s, 0, 33), "the space after it");
    }

    @Test
    void aSentencesPunctuationIsNotPartOfIt() {
        assertEquals("https://useforce.cc", Links.at(snap(80, "Go to https://useforce.cc."), 0, 8).url());
        assertEquals("https://a.b/c", Links.at(snap(80, "(see https://a.b/c)"), 0, 8).url());
        assertEquals("https://en.wikipedia.org/wiki/Tmux_(software)",
                Links.at(snap(80, "https://en.wikipedia.org/wiki/Tmux_(software), done"), 0, 3).url());
        assertEquals("http://x.y/?a=1", Links.at(snap(80, "http://x.y/?a=1!"), 0, 0).url());
    }

    @Test
    void notALink() {
        assertNull(Links.at(snap(80, "https:// nothing"), 0, 2));
        assertNull(Links.at(snap(80, "ftp://x.y"), 0, 2));
        assertNull(Links.at(snap(40, ""), 0, 0));
        assertNull(Links.at(snap(40, "abc"), 0, 7), "col past the end");
    }

    @Test
    void cellsFromRuns() {
        // a wide emoji takes two cells, its VS16 none; a combining mark and ZWJ take no cell
        assertEquals("\u0001\u0001 \u0001 https://a.b",
                Links.cells(List.of(run("✅\u0000 "), run("⚠️"), run(" https://a.b"))));
        // box drawing and block elements are a border, other non-ASCII is OTHER, U+0301 after "e" takes no cell
        assertEquals("\u0002\u0002e \u0001\u0001", Links.cells(List.of(run("│─é ✅\u0000"))));
    }

    @Test
    void aWrappedLinkJoins() {
        TerminalBuffer.Snapshot s = snap(20, "see https://example.", "com/docs/page ok");
        List<Links.Link> links = Links.find(s);
        assertEquals(1, links.size());
        Links.Link l = links.get(0);
        assertEquals("https://example.com/docs/page", l.url());
        assertEquals(List.of(new Links.Span(0, 4, 20), new Links.Span(1, 0, 13)), l.spans());
        assertEquals("https://example.com/docs/page", Links.at(s, 1, 5).url());
        assertNull(Links.at(s, 1, 14), "the words after the wrapped link");
    }

    @Test
    void claudeStyleIndentWraps() {
        TerminalBuffer.Snapshot s = snap(30,
                "● Read https://github.com/zala",
                "  ndemeter/force-minecraft/blo",
                "  b/main/README.md for details.");
        Links.Link l = Links.find(s).get(0);
        assertEquals("https://github.com/zalandemeter/force-minecraft/blob/main/README.md", l.url());
        assertEquals(List.of(new Links.Span(0, 7, 30), new Links.Span(1, 2, 30), new Links.Span(2, 2, 18)), l.spans());
        assertFalse(l.marked());
    }

    @Test
    void oneCellShortJoinsTwoCellsShortDoesNot() {
        // one cell short of the edge: joined
        TerminalBuffer.Snapshot one = snap(20, "go https://a.b/cdef ", "gh/x");
        Links.Link l1 = Links.at(one, 1, 0);
        assertNotNull(l1);
        assertEquals("https://a.b/cdefgh/x", l1.url());
        assertEquals(List.of(new Links.Span(0, 3, 19), new Links.Span(1, 0, 4)), l1.spans());
        // two cells short: not joined
        TerminalBuffer.Snapshot two = snap(20, "go https://a.b/cde  ");
        Links.Link l2 = Links.at(two, 0, 5);
        assertEquals("https://a.b/cde", l2.url());
        assertEquals(List.of(new Links.Span(0, 3, 18)), l2.spans());
    }

    @Test
    void wordWrappedProseIsNotJoined() {
        TerminalBuffer.Snapshot s = snap(20, "go https://a.b/c/de ", "and more text");
        Links.Link l = Links.at(s, 0, 5);
        assertNotNull(l);
        assertEquals("https://a.b/c/de", l.url());
        assertEquals(1, l.spans().size());
        assertNull(Links.at(s, 1, 0));
    }

    @Test
    void aContinuationThatIsItsOwnHttpsLinkIsNotJoined() {
        TerminalBuffer.Snapshot s = snap(20, "go https://a.b/c/xgh ", "https://else/y");
        List<Links.Link> links = Links.find(s);
        assertEquals(2, links.size());
        assertEquals("https://a.b/c/xgh", links.get(0).url());
        assertEquals("https://else/y", links.get(1).url());
    }

    @Test
    void aMoreDeeplyIndentedContinuationIsNotJoined() {
        TerminalBuffer.Snapshot s = snap(20, "go https://a.b/c/defg ", "    nore/xyz");
        Links.Link l = Links.at(s, 0, 5);
        assertEquals("https://a.b/c/defg", l.url());
        assertEquals(1, l.spans().size());
    }

    @Test
    void stopsAtAPanesBorderAndContinuesAfterTheLeftOne() {
        TerminalBuffer.Snapshot s = snap(18, "│https://a.b/cde│", "│fg/xyz│");
        List<Links.Link> links = Links.find(s);
        assertEquals(1, links.size());
        Links.Link l = links.get(0);
        assertEquals("https://a.b/cdefg/xyz", l.url());
        assertEquals(List.of(new Links.Span(0, 1, 16), new Links.Span(1, 1, 7)), l.spans());
    }

    @Test
    void markedLinksFromVt() {
        Vt vt = new Vt(10, 2, b -> {});
        vt.feed("\033]8;;https://x.y/z\033\\label\033]8;;\033\\".getBytes(StandardCharsets.UTF_8));
        TerminalBuffer.Snapshot s = vt.snapshot();
        Links.Link l = Links.at(s, 0, 2);
        assertNotNull(l);
        assertEquals("https://x.y/z", l.url());
        assertTrue(l.marked());
        assertEquals(List.of(new Links.Span(0, 0, 5)), l.spans());
    }

    @Test
    void markedWinsOverPlain() {
        Vt vt = new Vt(20, 1, b -> {});
        vt.feed("\033]8;;https://x.y/z\033\\https://evil.example\033]8;;\033\\".getBytes(StandardCharsets.UTF_8));
        TerminalBuffer.Snapshot s = vt.snapshot();
        Links.Link l = Links.at(s, 0, 3);
        assertNotNull(l);
        assertEquals("https://x.y/z", l.url());
        assertTrue(l.marked());
        assertEquals(1, Links.find(s).size(), "no plain link reported over the marked one");
    }

    @Test
    void aNonHttpOsc8LinkIsIgnored() {
        Vt vt = new Vt(10, 1, b -> {});
        vt.feed("\033]8;;file:///etc/passwd\033\\open\033]8;;\033\\".getBytes(StandardCharsets.UTF_8));
        assertNull(Links.at(vt.snapshot(), 0, 1));
        assertTrue(Links.find(vt.snapshot()).isEmpty());
    }

    @Test
    void adjacentWrappedMarkedRowsJoin() {
        Vt vt = new Vt(10, 3, b -> {});
        vt.feed("\033]8;;https://x.y/z\033\\aa\033]8;;\033\\\033[2;1H\033]8;;https://x.y/z\033\\bb\033]8;;\033\\".getBytes(StandardCharsets.UTF_8));
        List<Links.Link> links = Links.find(vt.snapshot());
        assertEquals(1, links.size());
        assertEquals(List.of(new Links.Span(0, 0, 2), new Links.Span(1, 0, 2)), links.get(0).spans());
    }

    @Test
    void markedRowsAScreenApartAreDistinctLinks() {
        Vt vt = new Vt(10, 3, b -> {});
        vt.feed("\033]8;;https://x.y/z\033\\aa\033]8;;\033\\\033[3;1H\033]8;;https://x.y/z\033\\bb\033]8;;\033\\".getBytes(StandardCharsets.UTF_8));
        List<Links.Link> links = Links.find(vt.snapshot());
        assertEquals(2, links.size());
    }

    @Test
    void orderIsTopToBottomAndNoCellIsCoveredTwice() {
        Vt vt = new Vt(30, 2, b -> {});
        vt.feed("\033]8;;https://x.y/z\033\\https://a.b\033]8;;\033\\ x https://c.d".getBytes(StandardCharsets.UTF_8));
        TerminalBuffer.Snapshot s = vt.snapshot();
        List<Links.Link> links = Links.find(s);
        assertTrue(links.get(0).marked(), "the marked link comes first");
        assertFalse(links.get(1).marked());
        assertEquals("https://c.d", links.get(1).url());
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 15; c++) {
                List<Links.Link> covering = new ArrayList<>();
                for (Links.Link l : links) if (l.covers(r, c)) covering.add(l);
                assertTrue(covering.size() <= 1, "cell " + r + "," + c + " covered " + covering.size() + " times");
            }
        }
    }

    @Test
    void twoPanesSideBySide() {
        // the left pane's link wraps at its border; the right pane's link on the same row is read from that row
        String r0 = "https://a.b/cdefgh\u2502 https://x.y/z  ";
        String r1 = "ij/kl             \u2502 more text      ";
        TerminalBuffer.Snapshot s = snap(r0.length(), r0, r1);
        Links.Link left = Links.at(s, 0, 2), right = Links.at(s, 0, 22);
        assertEquals("https://a.b/cdefghij/kl", left.url());
        assertEquals(List.of(new Links.Span(0, 0, 18), new Links.Span(1, 0, 5)), left.spans());
        assertEquals("https://x.y/z", right.url());
        assertEquals(List.of(new Links.Span(0, 20, 33)), right.spans());
    }

    @Test
    void aRowShorterThanTheScreen() {
        // cells mode may send a row without its trailing blanks: what is not there is blank
        TerminalBuffer.Snapshot s = snap(21, "see https://a.b/cdefg", "next");
        assertEquals("https://a.b/cdefg", Links.at(s, 0, 6).url());
        TerminalBuffer.Snapshot shortRow = new TerminalBuffer.Snapshot(18, 2,
                List.of(List.of(run("see https://a.b/cd")), List.of(run("ef/gh"))), 0, 0, false, 0, 0);
        assertEquals("https://a.b/cdef/gh", Links.at(shortRow, 0, 6).url());
        TerminalBuffer.Snapshot oneShort = new TerminalBuffer.Snapshot(19, 2,
                List.of(List.of(run("see https://a.b/cd")), List.of(run("ef/gh"))), 0, 0, false, 0, 0);
        assertEquals("https://a.b/cdef/gh", Links.at(oneShort, 0, 6).url(), "one cell short of the edge, not there");
    }
}
