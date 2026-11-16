package cc.useforce.mc.core;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps one `force api` running: starts it, tells the model how it went, and starts it again (2 s, then up to 30 s
 * apart) when it ends. The command is the user's setting (default: ~/.local/bin/force api).
 */
public final class Supervisor {
    public interface Factory {
        ApiConnection.Transport open(List<String> command) throws IOException;
    }

    private final List<String> command;
    private final ForceModel model;
    private final Consumer<JsonObject> onEvent;
    private final Factory factory;
    private volatile ApiConnection conn;
    private volatile boolean stopped;
    private Thread thread;

    public Supervisor(List<String> command, ForceModel model, Consumer<JsonObject> onEvent, Factory factory) {
        this.command = List.copyOf(command);
        this.model = model;
        this.onEvent = onEvent;
        this.factory = factory;
    }

    public ApiConnection connection() { return conn; }

    public synchronized void start() {
        if (thread != null) return;
        thread = new Thread(this::loop, "force-api-supervisor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        stopped = true;
        ApiConnection c = conn;
        if (c != null) c.close();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        long backoff = 2000;
        while (!stopped) {
            Object done = new Object();
            try {
                model.setStatus(ForceModel.Status.CONNECTING, null);
                ApiConnection.Transport t = factory.open(command);
                ApiConnection c = new ApiConnection(t, onEvent, () -> {
                    synchronized (done) {
                        done.notifyAll();
                    }
                });
                conn = c;
                JsonObject hello = c.start("force-minecraft").get();
                model.setStatus(ForceModel.Status.CONNECTED, ApiConnection.str(hello, "force", "?"));
                backoff = 2000;
                synchronized (done) {
                    while (!c.closed() && !stopped) done.wait(1000);
                }
                model.setStatus(ForceModel.Status.DISCONNECTED, "force api ended");
            } catch (IOException e) {
                model.setStatus(ForceModel.Status.NOT_FOUND, String.join(" ", command));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                model.setStatus(ForceModel.Status.DISCONNECTED, cause.getMessage());
                ApiConnection c = conn;
                if (c != null) c.close();
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(backoff * 2, 30_000);
        }
    }
}
