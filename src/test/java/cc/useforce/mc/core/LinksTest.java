package cc.useforce.mc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinksTest {
    private static TerminalBuffer.Run run(String text) {
        return new TerminalBuffer.Run(text, null, null, false, false, false, false, false);
    }

    @Test
    void aLinkUnderTheCell() {
        String row = "see https://github.com/x/y/pull/8 for it";
        Links.Link l = Links.at(row, 3, 10);
        assertNotNull(l);
        assertEquals("https://github.com/x/y/pull/8", l.url());
        assertEquals(3, l.row());
        assertEquals(4, l.start());
        assertEquals(4 + l.url().length(), l.end());
        assertNull(Links.at(row, 3, 2), "before it");
        assertNull(Links.at(row, 3, l.end()), "the space after it");
    }

    @Test
    void aSentencesPunctuationIsNotPartOfIt() {
        assertEquals("https://useforce.cc", Links.at("Go to https://useforce.cc.", 0, 8).url());
        assertEquals("https://a.b/c", Links.at("(see https://a.b/c)", 0, 8).url());
        assertEquals("https://en.wikipedia.org/wiki/Tmux_(software)",
                Links.at("https://en.wikipedia.org/wiki/Tmux_(software), done", 0, 3).url());
        assertEquals("http://x.y/?a=1", Links.at("http://x.y/?a=1!", 0, 0).url());
    }

    @Test
    void notALink() {
        assertNull(Links.at("https:// nothing", 0, 2));
        assertNull(Links.at("ftp://x.y", 0, 2));
        assertNull(Links.at("", 0, 0));
        assertNull(Links.at("abc", 0, 7));
    }

    @Test
    void cellsFromRuns() {
        // a wide emoji takes two cells, its VS16 none; a link after it keeps its columns
        String cells = Links.cells(List.of(run("✅\u0000 "), run("⚠️"), run(" https://a.b")));
        assertEquals(4 + 1 + "https://a.b".length(), cells.length());
        Links.Link l = Links.at(cells, 0, 6);
        assertNotNull(l);
        assertEquals(5, l.start());
        assertEquals("https://a.b", l.url());
        assertNull(Links.at("\u0001https://a.b\u0001x", 0, 0));
        assertEquals("https://a.b", Links.at("\u0001https://a.b\u0001x", 0, 3).url(), "stops at a cell no address has");
    }
}
