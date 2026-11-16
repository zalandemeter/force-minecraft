package cc.useforce.mc.core;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;

/**
 * The whole Force app (Force SPEC §54, vt mode, target "app"): Force runs its own ui in a pty and sends its output,
 * this emulates the terminal ({@link Vt}) and sends keys, text, the mouse and the emulator's answers back as bytes.
 * Open while the console is shown; closed when it is hidden (an open app is a client: its sessions count as viewed).
 */
public final class AppTerminal {
    private final Supplier<ApiConnection> conn;
    private volatile Vt vt;
    private volatile Integer term;
    private volatile String problem;
    private volatile int cols = 120, rows = 40;
    private ApiConnection openedOn;
    private boolean opening;
    private final List<JsonObject> early = new ArrayList<>();
    private volatile boolean hidden;
    private volatile TerminalBuffer.Snapshot cached;
    private volatile long cachedVersion = -1;

    public AppTerminal(Supplier<ApiConnection> conn) {
        this.conn = conn;
    }

    /** Events for the app (`terminal.data`, `terminal.closed`); true when it was one of ours. */
    public boolean accept(JsonObject e) {
        String kind = ApiConnection.str(e, "event", "");
        if (!kind.equals("terminal.data") && !kind.equals("terminal.closed")) return false;
        synchronized (early) {
            Integer t = term;
            if (t == null && opening && e.has("term")) {
                early.add(e);                    // output can come before the open's reply: kept, not lost
                return true;
            }
            if (t == null || !e.has("term") || e.get("term").getAsInt() != t) return false;
        }
        apply(kind, e);
        return true;
    }

    private void apply(String kind, JsonObject e) {
        if (kind.equals("terminal.data")) {
            Vt v = vt;
            if (v != null) v.feed(Base64.getDecoder().decode(ApiConnection.str(e, "data", "")));
        } else {
            term = null;
            problem = "Force ended (" + ApiConnection.str(e, "reason", "closed") + ")";
        }
    }

    /** Opens the app at this size (does nothing while it is open on the same connection). */
    public synchronized void open(int cols, int rows) {
        ApiConnection c = conn.get();
        if (c == null || c.closed()) {
            problem = "Force is not connected";
            return;
        }
        if (c != openedOn) term = null;
        if (term != null || opening) {
            resize(cols, rows);
            return;
        }
        if (!c.hasFeature("terminal.vt.app")) {
            problem = "this Force has no app view (update Force: force update)";
            return;
        }
        this.cols = cols;
        this.rows = rows;
        vt = new Vt(cols, rows, this::send);
        cached = null;
        problem = null;
        openedOn = c;
        synchronized (early) {
            opening = true;
            early.clear();
        }
        JsonObject req = new JsonObject();
        req.addProperty("mode", "vt");
        req.addProperty("target", "app");
        req.addProperty("cols", cols);
        req.addProperty("rows", rows);
        c.request("terminal.open", req).whenComplete((ok, err) -> {
            List<JsonObject> replay;
            synchronized (early) {
                opening = false;
                if (err != null) {
                    early.clear();
                    Throwable t = err.getCause() != null ? err.getCause() : err;
                    problem = t.getMessage();
                    return;
                }
                term = ok.get("term").getAsInt();
                replay = new ArrayList<>(early);
                early.clear();
            }
            if (hidden) sendHidden(true);
            for (JsonObject e : replay)
                if (e.get("term").getAsInt() == term) apply(ApiConnection.str(e, "event", ""), e);
        });
    }

    public boolean open() { return term != null || opening; }

    /** Hidden: the app keeps running (shown again at once, as it was); Force does not count it as viewing. */
    public void setHidden(boolean h) {
        if (h == hidden) return;
        hidden = h;
        sendHidden(h);
    }

    public boolean hidden() { return hidden; }

    private void sendHidden(boolean h) {
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.addProperty("hidden", h);
        c.request("terminal.hidden", req);
    }

    public synchronized void resize(int cols, int rows) {
        if (cols == this.cols && rows == this.rows) return;
        this.cols = cols;
        this.rows = rows;
        Vt v = vt;
        if (v != null) v.resize(cols, rows);
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.addProperty("cols", cols);
        req.addProperty("rows", rows);
        c.request("terminal.resize", req);
    }

    /** Bytes to the app, as a terminal sends them. */
    public void send(byte[] data) {
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null || data == null || data.length == 0) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.addProperty("data", Base64.getEncoder().encodeToString(data));
        c.request("terminal.input", req);
    }

    public synchronized void close() {
        ApiConnection c = conn.get();
        Integer t = term;
        term = null;
        synchronized (early) {
            opening = false;
            early.clear();
        }
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        c.request("terminal.close", req);
    }

    public Vt vt() { return vt; }

    public String problem() { return problem; }

    /** The screen, rebuilt only when the emulator changed. */
    public TerminalBuffer.Snapshot snapshot() {
        Vt v = vt;
        if (v == null) return new TerminalBuffer.Snapshot(0, 0, List.of(), 0, 0, false, 0, 0);
        long ver = v.version();
        if (cached == null || ver != cachedVersion) {
            cached = v.snapshot();
            cachedVersion = ver;
        }
        return cached;
    }
}
