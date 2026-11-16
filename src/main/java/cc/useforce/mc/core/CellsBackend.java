package cc.useforce.mc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.function.Supplier;

/**
 * Cells mode (Force SPEC §54): Force renders the pane and sends changed rows; this keeps one view open and fills the
 * buffer. Requests are fire-and-forget from the render thread; their errors become {@link #problem()}.
 */
public final class CellsBackend implements TerminalBackend {
    private final Supplier<ApiConnection> conn;
    private final TerminalBuffer buffer = new TerminalBuffer();
    private volatile Integer term;
    private volatile String address;
    private volatile String problem;
    private volatile boolean hidden;
    private ApiConnection openedOn;

    public CellsBackend(Supplier<ApiConnection> conn) {
        this.conn = conn;
    }

    /** Events for this view (`terminal.screen`, `terminal.closed`); true when it was one. */
    public boolean accept(JsonObject e) {
        String kind = ApiConnection.str(e, "event", "");
        if (!kind.startsWith("terminal.")) return false;
        Integer t = term;
        if (t == null || !e.has("term") || e.get("term").getAsInt() != t) return true;
        if (kind.equals("terminal.screen")) {
            buffer.applyScreen(e);
            problem = null;
        } else if (kind.equals("terminal.closed")) {
            term = null;
            problem = address + ": " + ApiConnection.str(e, "reason", "closed");
        }
        return true;
    }

    @Override
    public synchronized void show(String address) {
        ApiConnection c = conn.get();
        if (c == null || c.closed()) {
            problem = "Force is not connected";
            return;
        }
        if (c != openedOn) term = null;                 // a new `force api` process: the old view is gone
        if (address.equals(this.address) && term != null) return;
        this.address = address;
        buffer.clear();
        problem = null;
        JsonObject req = new JsonObject();
        req.addProperty("session", address);
        if (term == null) {
            req.addProperty("mode", "cells");
            openedOn = c;
            c.request("terminal.open", req).whenComplete((ok, err) -> {
                if (err != null) problem = message(err);
                else {
                    term = ok.get("term").getAsInt();
                    if (hidden) sendHidden(true);
                }
            });
        } else {
            req.addProperty("term", term);
            c.request("terminal.switch", req).whenComplete((ok, err) -> {
                if (err != null) problem = message(err);
            });
        }
    }

    @Override
    public String address() { return address; }

    @Override
    public void input(List<Object> keys) {
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null || keys.isEmpty()) return;
        JsonArray arr = new JsonArray();
        for (Object k : keys) {
            if (k instanceof Text(String text)) {
                JsonObject o = new JsonObject();
                o.addProperty("text", text);
                arr.add(o);
            } else arr.add(k.toString());
        }
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.add("keys", arr);
        c.request("terminal.input", req);
    }

    @Override
    public void scroll(int lines) {
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.addProperty("lines", lines);
        c.request("terminal.scroll", req);
    }

    @Override
    public void setHidden(boolean hidden) {
        if (hidden == this.hidden) return;
        this.hidden = hidden;
        sendHidden(hidden);
    }

    private void sendHidden(boolean h) {
        ApiConnection c = conn.get();
        Integer t = term;
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        req.addProperty("hidden", h);
        c.request("terminal.hidden", req);
    }

    @Override
    public synchronized void close() {
        ApiConnection c = conn.get();
        Integer t = term;
        term = null;
        address = null;
        if (c == null || t == null) return;
        JsonObject req = new JsonObject();
        req.addProperty("term", t);
        c.request("terminal.close", req);
    }

    @Override
    public TerminalBuffer buffer() { return buffer; }

    @Override
    public String problem() { return problem; }

    private static String message(Throwable err) {
        Throwable t = err.getCause() != null ? err.getCause() : err;
        return t.getMessage() == null ? t.toString() : t.getMessage();
    }
}
