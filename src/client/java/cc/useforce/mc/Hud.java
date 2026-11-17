package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.core.Os;
import cc.useforce.mc.ui.Canvas;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.SessionList;
import cc.useforce.mc.ui.Theme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** The game-screen layout: a compact glass list (on the right edge, or where the settings put it), with Force's
 * attention count on top. */
final class Hud {
    private final ForceClient mod;
    private final SessionList list = new SessionList();

    Hud(ForceClient mod) {
        this.mod = mod;
    }

    void draw(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!mod.hudVisible() || mod.consoleOpen()) return;
        Canvas c = new Canvas(g, mc.font, mc.getWindow().getGuiScale());
        int px = mod.config().hudPx(c.scale);
        ForceModel.State all = mod.model().state();
        ForceModel.State s = active(all, mod.config().hudRows);
        int line = Theme.line(px), row = SessionList.rowHeight(px, true);
        long hosts = s.sessions().stream().map(ForceModel.Session::host).distinct().count();
        int w = px * 17, head = line + px;
        int h = head + (s.sessions().isEmpty() ? line : s.sessions().size() * row + (int) hosts * (line + px / 4))
                + px / 3;
        c.begin();
        String pos = mod.config().sidebarPosition;
        int edge = px / 2;
        int x = pos.endsWith("left") ? edge : c.width() - w - edge;
        int y = pos.startsWith("top") ? edge : pos.startsWith("bottom") ? c.height() - h - edge : c.height() / 2 - h / 2;
        Panel.draw(c, x, y, w, h, mod.config().sidebarOpacity);
        header(c, mod.model(), all, x, y, w, px);
        if (s.sessions().isEmpty())
            c.text(mod.model().status() == ForceModel.Status.CONNECTED ? "all quiet" : empty(mod.model()),
                    x + px / 2, y + head, px, Theme.DIM, false);
        else list.draw(c, x + 1, y + head, w - 2, h - head, s, mod.selection().address(), true, true, px);
        c.end();
    }

    /** The sessions worth a look on the game screen: those with a colour (needs you, working, your turn, unread), not
     * the grey ones (idle, waiting, stale, down), at most `max`, host by host as Force lists them. */
    static ForceModel.State active(ForceModel.State s, int max) {
        List<ForceModel.Session> out = new java.util.ArrayList<>();
        for (ForceModel.Session x : s.live())
            if (Theme.color(x) != Theme.DIM && out.size() < max) out.add(x);
        return new ForceModel.State(s.hosts(), out, s.needsYou(), s.unread(), s.update());
    }

    /** "⚡ Force" and who needs you, on one line at the top of a panel. */
    static void header(Canvas c, ForceModel model, ForceModel.State s, int x, int y, int w, int px) {
        int ty = y + px / 2;
        c.text("⚡", x + px / 2, ty, px, Theme.CORAL, false);
        c.text("Force", x + px / 2 + c.textWidth("⚡ ", px, false), ty, px, Theme.TEXT, true);
        String head = header(model, s);
        c.text(head, x + w - c.textWidth(head, px, false) - px / 2, ty, px, s.needsYou() > 0 ? Theme.RED : Theme.DIM,
                false);
    }

    static String header(ForceModel model, ForceModel.State s) {
        if (model.status() != ForceModel.Status.CONNECTED) return "";
        if (s.needsYou() > 0) return s.needsYou() + " need" + (s.needsYou() == 1 ? "s" : "") + " you";
        if (s.unread() > 0) return s.unread() + " to read";
        return s.live().size() + " sessions";
    }

    static String empty(ForceModel model) {
        return switch (model.status()) {
            case CONNECTED -> "no sessions";
            case CONNECTING -> "connecting…";
            case NOT_FOUND -> Os.CURRENT == Os.Kind.WINDOWS ? "Force not found in WSL" : "Force not found";
            case DISCONNECTED -> "not connected";
        };
    }

}
