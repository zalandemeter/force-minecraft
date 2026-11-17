package cc.useforce.mc.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** A real subprocess as `force api` (a fake one): UTF-8 over real pipes; exit code 127 is "not found", others not. */
class ProcessTest {
    /** The java command that runs {@link FakeForce}: the JDK's java, its class path just the test classes. */
    private static List<String> javaCommand() throws Exception {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of(FakeForce.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                "cc.useforce.mc.core.FakeForce");
    }

    @Test
    void aRealProcessTalksUtf8() throws Exception {
        ApiConnection conn = new ApiConnection(ApiConnection.process(javaCommand()), e -> {
        }, () -> {
        });
        try {
            var hello = conn.start("test").get(20, TimeUnit.SECONDS);
            assertEquals("9.9.9-é✅", hello.get("force").getAsString());
        } finally {
            conn.close();
        }
    }

    @Test
    void aMissingCommandIsNotFound() throws Exception {
        List<String> command = new ArrayList<>(javaCommand());
        command.add("missing");
        ForceModel model = new ForceModel();
        Supervisor sup = new Supervisor(command, model, e -> {
        }, ApiConnection::process);
        try {
            sup.start();
            long end = System.currentTimeMillis() + 20_000;
            while (model.status() != ForceModel.Status.NOT_FOUND && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(ForceModel.Status.NOT_FOUND, model.status());
        } finally {
            sup.stop();
        }
    }

    @Test
    void anExitWithout127IsDisconnectedNotNotFound() throws Exception {
        ForceModel model = new ForceModel();
        Supervisor sup = new Supervisor(List.of("fake"), model, e -> {
        }, cmd -> new ApiConnection.Transport() {
            public InputStream in() { return InputStream.nullInputStream(); }
            public OutputStream out() { return OutputStream.nullOutputStream(); }
            public void close() {}
            public boolean alive() { return false; }
            public int exitCode() { return 1; }
        });
        try {
            sup.start();
            long end = System.currentTimeMillis() + 10_000;
            while (model.status() != ForceModel.Status.DISCONNECTED && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(ForceModel.Status.DISCONNECTED, model.status());
            Thread.sleep(300);
            assertNotEquals(ForceModel.Status.NOT_FOUND, model.status(), "no 127: not NOT_FOUND");
        } finally {
            sup.stop();
        }
    }
}
