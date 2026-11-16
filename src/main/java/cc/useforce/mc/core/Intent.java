package cc.useforce.mc.core;

import java.util.List;

/**
 * What the player wants, from any input (a key binding, a click, a wheel). Widgets and bindings only make these;
 * {@link Controller} carries them out. A new gesture is a new mapping to an existing intent; a new behaviour is a new
 * intent in one place.
 */
public sealed interface Intent {
    record ToggleHud() implements Intent {}
    /** The console: the whole Force app (vt mode). */
    record OpenConsole() implements Intent {}
    /** The session view: the native list + one session's terminal (cells mode), for the native UI to grow from. */
    record OpenSessions() implements Intent {}
    record CloseConsole() implements Intent {}
    record ToggleConsole() implements Intent {}
    /** Select the next/previous session in the list (wraps). */
    record Step(int delta) implements Intent {}
    /** Select the next session that needs you (then unread, then your turn). */
    record NextAttention() implements Intent {}
    record Select(String address) implements Intent {}
    record Keys(List<Object> keys) implements Intent {}
    record Scroll(int lines) implements Intent {}
    /** A Force command for the selected session (`run`), e.g. ["restart", ADDRESS]. */
    record Run(List<String> argv) implements Intent {}
}
