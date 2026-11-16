package cc.useforce.mc.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * What Force said last: the hosts and sessions (`state`), the connection, and the notices not yet shown. Force decides
 * (order, states, unread, attention); this only keeps it. The UI reads {@link #state()} on the render thread; events
 * replace it on the reader thread (an immutable value, swapped whole).
 */
public final class ForceModel {
    public enum Status { CONNECTING, CONNECTED, DISCONNECTED, NOT_FOUND }

    public record Host(String name, boolean local, String status, String version) {}

    public record Session(String host, String name, String address, int order, String kind, String state,
                          String activity, String title, String note, boolean unread, boolean watching,
                          boolean viewed, boolean archived, boolean support, String model) {
        public boolean live() { return !archived && !support; }
    }

    public record State(List<Host> hosts, List<Session> sessions, int needsYou, int unread, String update) {
        public static final State EMPTY = new State(List.of(), List.of(), 0, 0, null);

        /** The sessions a list shows: live ones, in Force's order (host by host, as Force lists the hosts). */
        public List<Session> live() {
            List<Session> out = new ArrayList<>();
            for (Session s : sessions) if (s.live()) out.add(s);
            return out;
        }

        public Session find(String address) {
            for (Session s : sessions) if (s.address().equals(address)) return s;
            return null;
        }
    }

    public record Notice(String kind, String address, String session, String message, boolean viewed) {}

    private volatile State state = State.EMPTY;
    private volatile Status status = Status.CONNECTING;
    private volatile String statusDetail;
    private final ConcurrentLinkedQueue<Notice> notices = new ConcurrentLinkedQueue<>();

    public State state() { return state; }
    public Status status() { return status; }
    public String statusDetail() { return statusDetail; }

    public void setStatus(Status s, String detail) {
        status = s;
        statusDetail = detail;
        if (s != Status.CONNECTED) state = State.EMPTY;
    }

    /** The next notice to show, or null. */
    public Notice pollNotice() { return notices.poll(); }

    /** An event from Force: `state` and `notice` are kept here; the rest is the terminal's. */
    public boolean accept(JsonObject e) {
        String kind = ApiConnection.str(e, "event", "");
        if (kind.equals("state")) {
            state = parseState(e);
            return true;
        }
        if (kind.equals("notice")) {
            notices.add(new Notice(ApiConnection.str(e, "kind", ""), ApiConnection.str(e, "address", ""),
                    ApiConnection.str(e, "session", ""), ApiConnection.str(e, "message", ""), bool(e, "viewed")));
            return true;
        }
        return false;
    }

    static State parseState(JsonObject e) {
        List<Host> hosts = new ArrayList<>();
        if (e.has("hosts")) for (JsonElement h : e.getAsJsonArray("hosts")) {
            JsonObject o = h.getAsJsonObject();
            hosts.add(new Host(ApiConnection.str(o, "name", ""), bool(o, "local"), ApiConnection.str(o, "status", ""),
                    ApiConnection.str(o, "version", null)));
        }
        List<Session> sessions = new ArrayList<>();
        if (e.has("sessions")) for (JsonElement s : e.getAsJsonArray("sessions")) {
            JsonObject o = s.getAsJsonObject();
            sessions.add(new Session(ApiConnection.str(o, "host", ""), ApiConnection.str(o, "name", ""),
                    ApiConnection.str(o, "address", ""), o.has("order") && !o.get("order").isJsonNull()
                            ? o.get("order").getAsInt() : 0,
                    ApiConnection.str(o, "kind", "claude"), ApiConnection.str(o, "state", "unknown"),
                    ApiConnection.str(o, "activity", null), ApiConnection.str(o, "title", null),
                    ApiConnection.str(o, "note", null), bool(o, "unread"), bool(o, "watching"), bool(o, "viewed"),
                    bool(o, "archived"), bool(o, "support"), ApiConnection.str(o, "model", null)));
        }
        JsonObject att = e.has("attention") ? e.getAsJsonObject("attention") : new JsonObject();
        int needs = att.has("needs_you") ? att.get("needs_you").getAsInt() : 0;
        int unread = att.has("unread") ? att.get("unread").getAsInt() : 0;
        return new State(List.copyOf(hosts), List.copyOf(sessions), needs, unread, ApiConnection.str(e, "update", null));
    }

    static boolean bool(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v != null && !v.isJsonNull() && v.getAsBoolean();
    }
}
