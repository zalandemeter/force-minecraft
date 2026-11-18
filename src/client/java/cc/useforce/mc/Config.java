package cc.useforce.mc;

import cc.useforce.mc.core.Os;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** config/force_mc.json: read on every start and written back (new settings appear with their defaults); the
 * settings screen ({@link SettingsScreen}) changes it while the game runs. */
public final class Config {
    /** The command that starts Force's frontend API. "~/" is your home. */
    public List<String> command = Os.defaultCommand(Os.CURRENT);
    /** The session list on the game screen. */
    public boolean hud = true;
    /** A toast when a session needs you or finishes, and a sound with it. */
    public boolean toasts = true;
    public boolean sound = true;
    /** The most sessions the game-screen list shows. */
    public int hudRows = 8;
    /** Text sizes in screen pixels (12–48) for the console's terminal, its list and the game-screen list; 0 = follow
     * Minecraft's GUI scale (which follows the display: a high-DPI screen gets larger text); Force's views' sizes
     * times `textScale`. */
    public int terminalFontPx = 0;
    public int uiFontPx = 0;
    public int hudFontPx = 0;
    public double textScale = 1.0;

    /** How see-through the backgrounds are, 0 (clear) to 1 (solid): Force's view (F12) and the game-screen list. */
    public double terminalOpacity = 0.8;
    public double sidebarOpacity = 0.8;
    /** Blur the game behind Force's view. */
    public boolean blur = true;
    /** Where the game-screen list sits: right, left, top-right, top-left, bottom-right, bottom-left. */
    public String sidebarPosition = "right";
    /** Space between the screen's edge and Force's view, in GUI pixels (they grow with the GUI scale). */
    public int terminalPadding = 10;
    /** The modifiers of each key binding ("ctrl+alt"); the key itself is in options.txt, set in Controls. */
    public java.util.Map<String, String> keyModifiers = new java.util.HashMap<>();

    public static final List<String> POSITIONS =
            List.of("right", "left", "top-right", "top-left", "bottom-right", "bottom-left");
    /** The glass the view was tuned on: below it the game shows through the darkening and the cells too. */
    public static final double GLASS = 0.86;

    /** Screen pixels per GUI unit × these: at GUI scale 3, 24 / 24 / 21 pixels (the console list matches its terminal). */
    private static final double TERMINAL = 8.0, UI = 8.0, HUD = 7.0;

    public int terminalPx(int guiScale) { return px(terminalFontPx, TERMINAL, guiScale, textScale); }
    public int uiPx(int guiScale) { return px(uiFontPx, UI, guiScale, textScale); }
    /** The game-screen list: Minecraft's GUI scale only (`textScale` is Force's view's text size). */
    public int hudPx(int guiScale) { return px(hudFontPx, HUD, guiScale, 1); }

    private static int px(int fixed, double perUnit, int guiScale, double scale) {
        int v = fixed > 0 ? fixed : (int) Math.round(perUnit * Math.max(1, guiScale) * (scale > 0 ? scale : 1));
        return cc.useforce.mc.ui.Theme.size(Math.max(12, Math.min(48, v)));   // a size there is a font for
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("force_mc.json");
    }

    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException ignored) {
        }
    }

    /** The padding around Force's view, in screen pixels. */
    public int paddingPx(int guiScale) {
        return Math.max(0, Math.min(200, terminalPadding)) * Math.max(1, guiScale);
    }

    static double clamp01(double v, double fallback) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : fallback;
    }

    public static Config load() {
        Path p = path();
        Config c = null;
        boolean broken = false;
        try {
            if (Files.exists(p)) c = GSON.fromJson(Files.readString(p), Config.class);
        } catch (IOException | RuntimeException e) {
            broken = true;                          // a typo in the file: defaults now, the file is left as it is
        }
        if (c == null) c = new Config();
        if (c.command == null || c.command.isEmpty()) c.command = new Config().command;
        if (c.keyModifiers == null) c.keyModifiers = new java.util.HashMap<>();
        if (c.sidebarPosition == null || !POSITIONS.contains(c.sidebarPosition)) c.sidebarPosition = "right";
        c.terminalOpacity = clamp01(c.terminalOpacity, 0.8);
        c.sidebarOpacity = clamp01(c.sidebarOpacity, 0.8);
        if (!(c.textScale > 0) || !Double.isFinite(c.textScale)) c.textScale = 1.0;
        if (!broken) c.save();                      // a file from an older version gets the new settings, with defaults
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
