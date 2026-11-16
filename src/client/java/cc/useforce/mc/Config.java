package cc.useforce.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/force_mc.json: written with the defaults on the first start, read on every start. */
public final class Config {
    /** The command that starts Force's frontend API. "~/" is your home. */
    public List<String> command = List.of("~/.local/bin/force", "api");
    /** The session list on the game screen. */
    public boolean hud = true;
    /** A toast when a session needs you or finishes, and a sound with it. */
    public boolean toasts = true;
    public boolean sound = true;
    /** The most sessions the game-screen list shows. */
    public int hudRows = 8;
    /** The terminal's text size: screen pixels per font unit (a cell is 5 × 9 units: 2 = 10 × 18 pixels). */
    public double terminalScale = 2.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static Config load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("force_mc.json");
        Config c = null;
        try {
            if (Files.exists(p)) c = GSON.fromJson(Files.readString(p), Config.class);
        } catch (IOException | RuntimeException ignored) {
        }
        if (c == null) {
            c = new Config();
            try {
                Files.writeString(p, GSON.toJson(c));
            } catch (IOException ignored) {
            }
        }
        if (c.command == null || c.command.isEmpty()) c.command = new Config().command;
        return c;
    }

    /** The command, `~/` expanded. FORCE_MC_COMMAND (words split on spaces) overrides it: for development. */
    public List<String> expandedCommand() {
        String home = System.getProperty("user.home");
        List<String> words = command;
        String env = System.getenv("FORCE_MC_COMMAND");
        if (env != null && !env.isBlank()) words = List.of(env.trim().split("\\s+"));
        List<String> out = new ArrayList<>();
        for (String w : words) out.add(w.startsWith("~/") ? home + w.substring(1) : w);
        return out;
    }
}
