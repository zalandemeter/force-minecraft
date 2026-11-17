package cc.useforce.mc;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Opens a web link in the browser, as a terminal does: the system's opener (xdg-open, open), never a shell. Only
 * http and https: a link on a screen is text anyone could have printed, so files and other schemes are not opened.
 * Minecraft's own opener moved between 26.2 and 26.3; this works the same on both.
 */
final class OpenLink {
    private OpenLink() {}

    static boolean open(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null) return false;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd = os.contains("mac") ? new String[]{"open", uri.toString()}
                : os.contains("win") ? new String[]{"rundll32", "url.dll,FileProtocolHandler", uri.toString()}
                : new String[]{"xdg-open", uri.toString()};
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
