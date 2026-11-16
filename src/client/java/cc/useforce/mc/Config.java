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
    /** Text sizes in screen pixels (12–48) for the console's terminal, its list and the game-screen list; 0 = follow
     * Minecraft's GUI scale (which follows the display: a high-DPI screen gets larger text), times `textScale`. */
    public int terminalFontPx = 0;
    public int uiFontPx = 0;
    public int hudFontPx = 0;
    public double textScale = 1.0;

    /** Screen pixels per GUI unit × these: at GUI scale 3, 20 / 18 / 17 pixels. */
    private static final double TERMINAL = 6.8, UI = 6.2, HUD = 5.8;

    public int terminalPx(int guiScale) { return px(terminalFontPx, TERMINAL, guiScale); }
    public int uiPx(int guiScale) { return px(uiFontPx, UI, guiScale); }
    public int hudPx(int guiScale) { return px(hudFontPx, HUD, guiScale); }

    private int px(int fixed, double perUnit, int guiScale) {
        int v = fixed > 0 ? fixed : (int) Math.round(perUnit * Math.max(1, guiScale) * (textScale > 0 ? textScale : 1));
        return cc.useforce.mc.ui.Theme.size(Math.max(12, Math.min(48, v)));   // a size there is a font for
    }

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
