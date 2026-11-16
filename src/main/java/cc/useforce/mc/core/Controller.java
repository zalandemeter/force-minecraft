package cc.useforce.mc.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.function.Supplier;

/**
 * Carries out {@link Intent}s: the selection, the terminal view, Force commands. The console's open/closed state is
 * the UI's: it is asked through {@link Ui}, so this stays free of Minecraft.
 */
public final class Controller {
    public interface Ui {
        boolean consoleOpen();
        /** The whole app (vt mode). */
        void openConsole();
        /** The session view (native list + a session's terminal). */
        void openSessions();
        void closeConsole();
        void toggleHud();
    }

    private final ForceModel model;
    private final Selection selection;
    private final TerminalBackend terminal;
    private final Supplier<ApiConnection> conn;
    private final Ui ui;

    public Controller(ForceModel model, Selection selection, TerminalBackend terminal, Supplier<ApiConnection> conn,
                      Ui ui) {
        this.model = model;
        this.selection = selection;
        this.terminal = terminal;
        this.conn = conn;
        this.ui = ui;
    }

    public Selection selection() { return selection; }

    public void handle(Intent intent) {
        ForceModel.State s = model.state();
        switch (intent) {
            case Intent.ToggleHud t -> ui.toggleHud();
            case Intent.OpenConsole o -> ui.openConsole();
            case Intent.OpenSessions o -> openSessions(s);
            case Intent.CloseConsole c -> close();
            case Intent.ToggleConsole t -> {
                if (ui.consoleOpen()) close();
                else ui.openConsole();
            }
            case Intent.Step st -> show(selection.step(s, st.delta()));
            case Intent.NextAttention n -> {
                String a = Selection.attention(s.live(), selection.address());
                if (a != null) {
                    selection.set(a);
                    show(a);
                }
            }
            case Intent.Select sel -> {
                selection.set(sel.address());
                show(sel.address());
            }
            case Intent.Keys k -> terminal.input(k.keys());
            case Intent.Scroll sc -> terminal.scroll(sc.lines());
            case Intent.Run r -> run(r);
        }
    }

    private void openSessions(ForceModel.State s) {
        String a = selection.resolve(s);
        ui.openSessions();
        terminal.setHidden(false);
        if (a != null) terminal.show(a);
    }

    private void close() {
        terminal.setHidden(true);
        ui.closeConsole();
    }

    private void show(String address) {
        if (address != null && ui.consoleOpen()) terminal.show(address);
    }

    private void run(Intent.Run r) {
        ApiConnection c = conn.get();
        if (c == null) return;
        JsonObject req = new JsonObject();
        JsonArray argv = new JsonArray();
        r.argv().forEach(argv::add);
        req.add("argv", argv);
        c.request("run", req);
    }
}
