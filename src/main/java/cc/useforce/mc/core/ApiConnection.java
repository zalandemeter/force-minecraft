package cc.useforce.mc.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One `force api` process (Force SPEC §54): JSON lines over its stdin/stdout. Requests get a future; events go to the
 * listener on the reader thread. {@link #start()} says hello and subscribes; a dropped process is started again by
 * {@link Supervisor}. Nothing here knows Minecraft.
 */
public final class ApiConnection {
    public static final int PROTOCOL = 1;

    /** Where the bytes come from: a real process, or pipes in a test. */
    public interface Transport {
        InputStream in();
        OutputStream out();
        void close();
        boolean alive();
    }

    public static Transport process(List<String> command) throws IOException {
        Process p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        return new Transport() {
            public InputStream in() { return p.getInputStream(); }
            public OutputStream out() { return p.getOutputStream(); }
            public void close() { p.destroy(); }
            public boolean alive() { return p.isAlive(); }
        };
    }

    private static final Gson GSON = new Gson();
    private final Transport transport;
    private final Writer writer;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonObject>> waiting = new ConcurrentHashMap<>();
    private final Consumer<JsonObject> onEvent;
    private final Runnable onClosed;
    private volatile JsonObject hello;
    private volatile boolean closed;

    public ApiConnection(Transport transport, Consumer<JsonObject> onEvent, Runnable onClosed) {
        this.transport = transport;
        this.writer = new OutputStreamWriter(transport.out(), StandardCharsets.UTF_8);
        this.onEvent = onEvent;
        this.onClosed = onClosed;
        Thread t = new Thread(this::read, "force-api-reader");
        t.setDaemon(true);
        t.start();
    }

    /** hello, then subscribe to state and notices. Completes with the hello reply (features, Force version). */
    public CompletableFuture<JsonObject> start(String client) {
        JsonObject h = new JsonObject();
        h.addProperty("protocol", PROTOCOL);
        h.addProperty("client", client);
        return request("hello", h).thenCompose(ok -> {
            hello = ok;
            JsonObject sub = new JsonObject();
            sub.add("to", GSON.toJsonTree(List.of("state", "notices")));
            return request("subscribe", sub).thenApply(x -> ok);
        });
    }

    public JsonObject hello() { return hello; }

    public boolean hasFeature(String f) {
        JsonObject h = hello;
        if (h == null || !h.has("features")) return false;
        for (JsonElement e : h.getAsJsonArray("features")) if (f.equals(e.getAsString())) return true;
        return false;
    }

    /** One request: the future completes with `ok`, or fails with an {@link ApiException}. */
    public CompletableFuture<JsonObject> request(String op, JsonObject fields) {
        int id = ids.incrementAndGet();
        JsonObject msg = fields == null ? new JsonObject() : fields.deepCopy();
        msg.addProperty("id", id);
        msg.addProperty("op", op);
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        if (closed) {
            f.completeExceptionally(new ApiException("closed", "Force is not connected"));
            return f;
        }
        waiting.put(id, f);
        try {
            synchronized (writer) {
                writer.write(GSON.toJson(msg));
                writer.write('\n');
                writer.flush();
            }
        } catch (IOException e) {
            waiting.remove(id);
            f.completeExceptionally(new ApiException("closed", e.getMessage()));
        }
        return f;
    }

    private void read() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(transport.in(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                if (msg.has("event")) {
                    try {
                        onEvent.accept(msg);
                    } catch (RuntimeException e) {
                        // a listener bug must not end the connection
                    }
                    continue;
                }
                if (!msg.has("id") || msg.get("id").isJsonNull()) continue;
                CompletableFuture<JsonObject> f = waiting.remove(msg.get("id").getAsInt());
                if (f == null) continue;
                if (msg.has("ok")) f.complete(msg.getAsJsonObject("ok"));
                else {
                    JsonObject err = msg.has("error") ? msg.getAsJsonObject("error") : new JsonObject();
                    f.completeExceptionally(new ApiException(str(err, "code", "error"), str(err, "message", "")));
                }
            }
        } catch (IOException ignored) {
        } finally {
            closed = true;
            for (CompletableFuture<JsonObject> f : waiting.values())
                f.completeExceptionally(new ApiException("closed", "Force is not connected"));
            waiting.clear();
            onClosed.run();
        }
    }

    public void close() {
        closed = true;
        try {
            transport.out().close();
        } catch (IOException ignored) {
        }
        transport.close();
    }

    public boolean closed() { return closed; }

    static String str(JsonObject o, String key, String dflt) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? dflt : e.getAsString();
    }

    public static final class ApiException extends RuntimeException {
        public final String code;

        public ApiException(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
