package cc.useforce.mc.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The screen a terminal view shows: rows of styled runs and a cursor. Every backend fills one (cells mode from
 * `terminal.screen`; a VT emulator later); {@code TerminalView} draws only this. Readers take {@link #snapshot()}.
 */
public final class TerminalBuffer {
    public record Run(String text, Integer fg, Integer bg, boolean bold, boolean italic, boolean underline,
                      boolean reverse, boolean dim) {}

    public record Snapshot(int cols, int rows, List<List<Run>> lines, int cursorX, int cursorY, boolean cursorVisible,
                           int offset, long version) {}

    private int cols, rows, cursorX, cursorY, offset;
    private boolean cursorVisible;
    private List<List<Run>> lines = new ArrayList<>();
    private long version;

    public synchronized Snapshot snapshot() {
        List<List<Run>> copy = new ArrayList<>(lines.size());
        for (List<Run> l : lines) copy.add(List.copyOf(l));
        return new Snapshot(cols, rows, copy, cursorX, cursorY, cursorVisible, offset, version);
    }

    public synchronized void clear() {
        cols = rows = 0;
        lines = new ArrayList<>();
        version++;
    }

    /** A `terminal.screen` event (Force SPEC §54, cells mode): the rows it lists replace those rows. */
    public synchronized void applyScreen(JsonObject e) {
        int c = e.get("cols").getAsInt(), r = e.get("rows").getAsInt();
        if (c != cols || r != rows) {
            cols = c;
            rows = r;
            List<List<Run>> fresh = new ArrayList<>(r);
            for (int i = 0; i < r; i++) fresh.add(i < lines.size() ? lines.get(i) : List.of());
            lines = fresh;
        }
        if (e.has("cursor")) {
            JsonObject cur = e.getAsJsonObject("cursor");
            cursorX = cur.get("x").getAsInt();
            cursorY = cur.get("y").getAsInt();
            cursorVisible = cur.has("visible") && cur.get("visible").getAsBoolean();
        }
        offset = e.has("offset") ? e.get("offset").getAsInt() : 0;
        for (JsonElement le : e.getAsJsonArray("lines")) {
            JsonObject line = le.getAsJsonObject();
            int y = line.get("y").getAsInt();
            if (y < 0 || y >= rows) continue;
            List<Run> runs = new ArrayList<>();
            for (JsonElement re : line.getAsJsonArray("runs")) {
                JsonObject o = re.getAsJsonObject();
                runs.add(new Run(ApiConnection.str(o, "text", ""), color(o, "fg"), color(o, "bg"), ForceModel.bool(o, "bold"),
                        ForceModel.bool(o, "italic"), ForceModel.bool(o, "underline"), ForceModel.bool(o, "reverse"),
                        ForceModel.bool(o, "dim")));
            }
            lines.set(y, List.copyOf(runs));
        }
        version++;
    }

    static Integer color(JsonObject o, String key) {
        String v = ApiConnection.str(o, key, null);
        if (v == null || v.length() != 7 || v.charAt(0) != '#') return null;
        try {
            return Integer.parseInt(v.substring(1), 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
