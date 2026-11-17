package cc.useforce.mc;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A key binding that can be a combination (Ctrl, Alt and Shift with a key, "Ctrl + Alt + F"), set in Minecraft's own
 * Controls → Key Binds like any other: the key goes to options.txt as usual, the modifiers to the mod's config (the
 * Controls screen hands them over through {@code KeyBindsScreenMixin}). It fires only with exactly its modifiers held,
 * so F12 and Ctrl+F12 can be two bindings.
 */
public final class ComboKey extends KeyMapping {
    public static final int CTRL = 1, ALT = 2, SHIFT = 4;

    private int mods;                   // CTRL | ALT | SHIFT
    private int pending = -1;           // the modifiers of the key the Controls screen is about to set
    private int pressedMods;            // the modifiers held when the key went down
    private final Consumer<ComboKey> changed;

    public ComboKey(String name, int key, Category category, int mods, Consumer<ComboKey> changed) {
        super(name, key, category);
        this.mods = mods;
        this.changed = changed;
    }

    public int mods() { return mods; }

    /** Minecraft's modifier bits (KeyEvent.modifiers) as CTRL | ALT | SHIFT. */
    public static int fromEvent(int modifiers) {
        return ((modifiers & InputConstants.MOD_CONTROL) != 0 ? CTRL : 0)
                | ((modifiers & InputConstants.MOD_ALT) != 0 ? ALT : 0)
                | ((modifiers & InputConstants.MOD_SHIFT) != 0 ? SHIFT : 0);
    }

    static int held() {
        Minecraft mc = Minecraft.getInstance();
        return (mc.hasControlDown() ? CTRL : 0) | (mc.hasAltDown() ? ALT : 0) | (mc.hasShiftDown() ? SHIFT : 0);
    }

    /** A modifier key alone: the Controls screen waits for the key that goes with it. */
    public static boolean isModifier(int key) {
        return key == InputConstants.KEY_LCONTROL || key == InputConstants.KEY_RCONTROL
                || key == InputConstants.KEY_LALT || key == InputConstants.KEY_RALT
                || key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT;
    }

    /** The Controls screen is about to set this binding to a key pressed with these modifiers (KeyEvent bits). */
    public void capture(int modifiers) { pending = fromEvent(modifiers); }

    @Override
    public void setKey(InputConstants.Key key) {
        super.setKey(key);
        int m = mods;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.gui != null && mc.gui.screen() instanceof KeyBindsScreen s) {
            if (s.selectedKey == this) m = pending >= 0 ? pending : held();      // set by a key, or a mouse button
            else m = 0;                                                         // "Reset" / "Reset Keys"
            if (key.equals(InputConstants.UNKNOWN)) m = 0;                      // Esc: unbound
        }
        pending = -1;
        if (m != mods) {
            mods = m;
            if (changed != null) changed.accept(this);
        }
    }

    /** In the game: the key went down; which modifiers were held then decides whether it counts. */
    @Override
    public void setDown(boolean down) {
        if (down && !isDown()) pressedMods = held();
        super.setDown(down);
    }

    /** One press of exactly this combination since the last call. */
    public boolean consumeCombo() {
        boolean any = false;
        while (consumeClick()) any |= pressedMods == mods;
        return any;
    }

    /** A key event (inside a screen) that is exactly this combination. */
    @Override
    public boolean matches(KeyEvent e) {
        return super.matches(e) && fromEvent(e.modifiers()) == mods;
    }

    @Override
    public boolean same(KeyMapping other) {
        return super.same(other) && (other instanceof ComboKey c ? c.mods : 0) == mods;
    }

    @Override
    public boolean isDefault() {
        return super.isDefault() && mods == 0;
    }

    @Override
    public Component getTranslatedKeyMessage() {
        Component key = super.getTranslatedKeyMessage();
        if (mods == 0 || isUnbound()) return key;
        return Component.literal(prefix(mods)).append(key);
    }

    static String prefix(int mods) {
        return ((mods & CTRL) != 0 ? "Ctrl + " : "") + ((mods & ALT) != 0 ? "Alt + " : "")
                + ((mods & SHIFT) != 0 ? "Shift + " : "");
    }

    /** "ctrl+alt" in the config. */
    static String toConfig(int mods) {
        StringBuilder b = new StringBuilder();
        if ((mods & CTRL) != 0) b.append("ctrl+");
        if ((mods & ALT) != 0) b.append("alt+");
        if ((mods & SHIFT) != 0) b.append("shift+");
        return b.isEmpty() ? "" : b.substring(0, b.length() - 1);
    }

    static int fromConfig(String s) {
        if (s == null) return 0;
        int m = 0;
        for (String w : s.toLowerCase(java.util.Locale.ROOT).split("[+ ,]+"))
            m |= switch (w) {
                case "ctrl", "control" -> CTRL;
                case "alt" -> ALT;
                case "shift" -> SHIFT;
                default -> 0;
            };
        return m;
    }
}
