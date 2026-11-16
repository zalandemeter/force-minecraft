package cc.useforce.mc.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static final String STATE = """
            {"event":"state","hosts":[{"name":"laptop","local":true,"status":"connected","version":"1.5.2"}],
             "sessions":[
              {"host":"laptop","name":"api","address":"laptop:api","order":1,"kind":"claude","state":"working"},
              {"host":"laptop","name":"web","address":"laptop:web","order":2,"kind":"claude","state":"needs-you"},
              {"host":"laptop","name":"old","address":"laptop:old","order":3,"state":"idle","archived":true},
              {"host":"devbox","name":"ci","address":"devbox:ci","order":1,"state":"idle","unread":true},
              {"host":"laptop","name":"chuck","address":"laptop:chuck","state":"idle","support":true}],
             "attention":{"needs_you":1,"unread":1},"update":null}""";

    @Test
    void stateIsKeptAsForceSentIt() {
        ForceModel m = new ForceModel();
        assertTrue(m.accept(json(STATE)));
        ForceModel.State s = m.state();
        assertEquals(List.of("laptop:api", "laptop:web", "devbox:ci"),
                s.live().stream().map(ForceModel.Session::address).toList(), "live: no archived, no Chuck, Force's order");
        assertEquals(1, s.needsYou());
        assertTrue(s.find("devbox:ci").unread());
        assertNull(s.update());
    }

    @Test
    void noticesQueueUntilShown() {
        ForceModel m = new ForceModel();
        m.accept(json("{\"event\":\"notice\",\"kind\":\"needs-you\",\"address\":\"laptop:web\",\"session\":\"web\","
                + "\"message\":\"web: Bash: rm -rf build\",\"viewed\":false}"));
        ForceModel.Notice n = m.pollNotice();
        assertEquals("web: Bash: rm -rf build", n.message());
        assertNull(m.pollNotice());
    }

    @Test
    void selectionPrefersAttentionAndSteps() {
        ForceModel m = new ForceModel();
        m.accept(json(STATE));
        Selection sel = new Selection();
        assertEquals("laptop:web", sel.resolve(m.state()), "needs you first");
        assertEquals("devbox:ci", sel.step(m.state(), 1));
        assertEquals("laptop:api", sel.step(m.state(), 1), "wraps");
        assertEquals("devbox:ci", sel.step(m.state(), -1));
        assertEquals("laptop:web", Selection.attention(m.state().live(), "devbox:ci"));
        sel.set("laptop:old");
        assertEquals("laptop:web", sel.resolve(m.state()), "an archived pick is replaced");
    }

    @Test
    void bufferAppliesChangedRowsOnly() {
        TerminalBuffer b = new TerminalBuffer();
        b.applyScreen(json("""
                {"event":"terminal.screen","term":1,"seq":1,"cols":10,"rows":3,"cursor":{"x":2,"y":1,"visible":true},
                 "lines":[{"y":0,"runs":[{"text":"top","fg":"#ff0000","bg":null,"bold":true}]},
                          {"y":1,"runs":[{"text":"$ ","fg":null,"bg":null}]},{"y":2,"runs":[]}]}"""));
        b.applyScreen(json("""
                {"event":"terminal.screen","term":1,"seq":2,"cols":10,"rows":3,"cursor":{"x":4,"y":1,"visible":true},
                 "lines":[{"y":1,"runs":[{"text":"$ ls","fg":null,"bg":"#010203"}]}]}"""));
        TerminalBuffer.Snapshot s = b.snapshot();
        assertEquals("top", s.lines().get(0).get(0).text(), "row 0 kept");
        assertEquals(0xff0000, s.lines().get(0).get(0).fg());
        assertTrue(s.lines().get(0).get(0).bold());
        assertEquals("$ ls", s.lines().get(1).get(0).text());
        assertEquals(0x010203, s.lines().get(1).get(0).bg());
        assertEquals(4, s.cursorX());
        assertEquals(2, s.version());
    }

    @Test
    void keysToTmuxNames() {
        assertEquals("Enter", Keys.name(257, 0));
        assertEquals("Escape", Keys.name(256, 0));
        assertEquals("BTab", Keys.name(258, Keys.SHIFT));
        assertEquals("C-c", Keys.name(67, Keys.CTRL));
        assertEquals("M-b", Keys.name(66, Keys.ALT));
        assertEquals("C-Up", Keys.name(265, Keys.CTRL));
        assertEquals("S-Up", Keys.name(265, Keys.SHIFT));
        assertEquals("F5", Keys.name(294, 0));
        assertEquals("C-Space", Keys.name(32, Keys.CTRL));
        assertNull(Keys.name(65, 0), "a plain letter comes as typed text");
        assertNull(Keys.name(65, Keys.SHIFT));
    }

    /** A fake `force api`: answers hello/subscribe/terminal.open, then sends a state and a screen. */
    @Test
    void connectionTalksTheProtocol() throws Exception {
        PipedOutputStream toClient = new PipedOutputStream();
        PipedInputStream clientIn = new PipedInputStream(toClient, 1 << 16);
        PipedOutputStream clientOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientOut, 1 << 16);
        List<String> ops = new CopyOnWriteArrayList<>();
        Thread server = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(serverIn, StandardCharsets.UTF_8));
                 Writer w = new OutputStreamWriter(toClient, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonObject m = json(line);
                    String op = m.get("op").getAsString();
                    ops.add(op);
                    int id = m.get("id").getAsInt();
                    String ok = switch (op) {
                        case "hello" -> "{\"protocol\":1,\"force\":\"1.6.0\",\"host\":\"laptop\",\"features\":[\"state\",\"terminal.cells\"]}";
                        case "terminal.open" -> "{\"term\":7,\"session\":\"laptop:web\",\"mode\":\"cells\"}";
                        default -> "{}";
                    };
                    if (op.equals("run")) {
                        w.write("{\"id\":" + id + ",\"error\":{\"code\":\"not_allowed\",\"message\":\"no\"}}\n");
                    } else w.write("{\"id\":" + id + ",\"ok\":" + ok + "}\n");
                    if (op.equals("subscribe")) w.write(STATE.replace("\n", "") + "\n");
                    if (op.equals("terminal.open")) w.write("{\"event\":\"terminal.screen\",\"term\":7,\"seq\":1,"
                            + "\"cols\":5,\"rows\":1,\"cursor\":{\"x\":0,\"y\":0,\"visible\":true},"
                            + "\"lines\":[{\"y\":0,\"runs\":[{\"text\":\"hi\",\"fg\":null,\"bg\":null}]}]}\n");
                    w.flush();
                }
            } catch (IOException ignored) {
            }
        });
        server.setDaemon(true);
        server.start();
        ForceModel model = new ForceModel();
        ApiConnection[] holder = new ApiConnection[1];
        CellsBackend term = new CellsBackend(() -> holder[0]);
        holder[0] = new ApiConnection(new ApiConnection.Transport() {
            public InputStream in() { return clientIn; }
            public OutputStream out() { return clientOut; }
            public void close() {}
            public boolean alive() { return true; }
        }, e -> {
            if (!model.accept(e)) term.accept(e);
        }, () -> {});
        JsonObject hello = holder[0].start("test").get(5, TimeUnit.SECONDS);
        assertEquals("1.6.0", hello.get("force").getAsString());
        assertTrue(holder[0].hasFeature("terminal.cells"));
        assertFalse(holder[0].hasFeature("terminal.vt"));
        long end = System.currentTimeMillis() + 5000;
        while (model.state().live().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(3, model.state().live().size());
        term.show("laptop:web");
        while ((term.buffer().snapshot().lines().isEmpty() || term.buffer().snapshot().lines().get(0).isEmpty())
                && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals("hi", term.buffer().snapshot().lines().get(0).get(0).text());
        term.input(new ArrayList<>(List.of(new TerminalBackend.Text("yes"), "Enter")));
        var err = assertThrows(Exception.class, () -> holder[0].request("run", new JsonObject()).get(5, TimeUnit.SECONDS));
        assertEquals("not_allowed", ((ApiConnection.ApiException) err.getCause()).code);
        while (!ops.contains("terminal.input") && System.currentTimeMillis() < end) Thread.sleep(20);
        assertEquals(List.of("hello", "subscribe", "terminal.open", "terminal.input", "run"), ops);
    }
}
