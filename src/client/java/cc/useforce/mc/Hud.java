package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.ui.Canvas;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.SessionList;
import cc.useforce.mc.ui.Theme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.List;

/** The game-screen layout: a compact glass list on the right edge, with Force's attention count on top. */
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
        ForceModel.State s = mod.model().state();
        int rows = Math.min(s.live().size(), mod.config().hudRows);
        int w = px * 17, head = Theme.line(px) + px;
        int h = head + Math.max(1, rows) * SessionList.rowHeight(px, true) + px / 3;
        c.begin();
        int x = c.width() - w - px / 2, y = c.height() / 2 - h / 2;
        Panel.draw(c, x, y, w, h);
        header(c, mod.model(), s, x, y, w, px);
        if (rows == 0) c.text(empty(mod.model()), x + px / 2, y + head, px, Theme.DIM, false);
        else list.draw(c, x + 1, y + head, w - 2, h - head, trimmed(s, rows), mod.selection().address(), true, false,
                px);
        c.end();
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
            case NOT_FOUND -> "Force not found";
            case DISCONNECTED -> "not connected";
        };
    }

    private static ForceModel.State trimmed(ForceModel.State s, int rows) {
        List<ForceModel.Session> live = s.live().subList(0, rows);
        return new ForceModel.State(s.hosts(), live, s.needsYou(), s.unread(), s.update());
    }
}
