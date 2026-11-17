package cc.useforce.mc.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OsTest {
    @Test
    void windows() {
        assertEquals(Os.Kind.WINDOWS, Os.of("Windows 11"));
        assertEquals(Os.Kind.WINDOWS, Os.of("windows 10"));
    }

    @Test
    void mac() {
        assertEquals(Os.Kind.MAC, Os.of("Mac OS X"));
        assertEquals(Os.Kind.MAC, Os.of("Darwin"));
    }

    @Test
    void linux() {
        assertEquals(Os.Kind.LINUX, Os.of("Linux"));
        assertEquals(Os.Kind.LINUX, Os.of("FreeBSD"));
        assertEquals(Os.Kind.LINUX, Os.of(""));
        assertEquals(Os.Kind.LINUX, Os.of(null));
    }

    @Test
    void defaultCommand() {
        assertEquals(List.of("wsl.exe", "--exec", "sh", "-lc", "exec ~/.local/bin/force api"),
                Os.defaultCommand(Os.Kind.WINDOWS));
        assertEquals(List.of("~/.local/bin/force", "api"), Os.defaultCommand(Os.Kind.LINUX));
        assertEquals(List.of("~/.local/bin/force", "api"), Os.defaultCommand(Os.Kind.MAC));
    }
}
