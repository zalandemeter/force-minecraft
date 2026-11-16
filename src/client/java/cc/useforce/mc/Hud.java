package cc.useforce.mc;

import cc.useforce.mc.core.ForceModel;
import cc.useforce.mc.ui.Panel;
import cc.useforce.mc.ui.SessionList;
import cc.useforce.mc.ui.Theme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
        Font font = mc.font;
        ForceModel.State s = mod.model().state();
        int w = 150;
        int rows = Math.min(s.live().size(), mod.config().hudRows);
        int h = 14 + Math.max(1, rows) * list.rowHeight(true) + 4;
        int x = g.guiWidth() - w - 4;
        int y = g.guiHeight() / 2 - h / 2;
        Panel.draw(g, x, y, w, h);
        g.text(font, Theme.mono("⚡"), x + 5, y + 3, Theme.CORAL, false);
        g.text(font, Theme.mono("Force"), x + 15, y + 3, Theme.TEXT, false);
        String head = header(mod.model(), s);
        int hw = font.width(Theme.mono(head));
        g.text(font, Theme.mono(head), x + w - hw - 5, y + 3, s.needsYou() > 0 ? Theme.RED : Theme.DIM, false);
        if (rows == 0) {
            g.text(font, Theme.mono(empty(mod.model())), x + 6, y + 16, Theme.DIM, false);
            return;
        }
        list.draw(g, font, x + 1, y + 14, w - 2, h - 16, trimmed(s, rows), mod.selection().address(), true, false);
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
        return new ForceModel.State(s.hosts(), s.live().subList(0, rows), s.needsYou(), s.unread(), s.update());
    }
}
