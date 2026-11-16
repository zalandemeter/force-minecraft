package cc.useforce.mc.core;

import java.util.List;

/**
 * A live view of one session (Force SPEC §54 `terminal.*`). The UI talks to this, never to a mode: cells mode
 * ({@link CellsBackend}) now, a VT emulator backend later, both filling a {@link TerminalBuffer}.
 */
public interface TerminalBackend {
    /** Show `address` (HOST:NAME): opens the view, or switches the open one. */
    void show(String address);

    /** The session shown, or null. */
    String address();

    /** Keys: tmux key names ("Enter", "C-c", "Up", "BTab", one character) or {@link Text}. */
    void input(List<Object> keys);

    /** Into the history (negative) or back (positive); 0 = live. */
    void scroll(int lines);

    /** The view's own size in cells, as a terminal's (the session follows it while shown). */
    void resize(int cols, int rows);

    /** Not drawn (console closed, window unfocused): Force stops sending and the session is not "viewed". */
    void setHidden(boolean hidden);

    void close();

    TerminalBuffer buffer();

    /** Why the view is empty (no session, ended, an error), or null. */
    String problem();

    record Text(String text) {}
}
