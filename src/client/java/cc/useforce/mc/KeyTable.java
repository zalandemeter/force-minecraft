package cc.useforce.mc;

import cc.useforce.mc.core.Keys;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.HashMap;
import java.util.Map;

/** {@link Keys.Table} from this Minecraft's own key constants (GLFW codes up to 26.2, SDL from 26.3). */
final class KeyTable {
    private KeyTable() {}

    static Keys.Table create() {
        Map<Integer, String> n = new HashMap<>();
        n.put(InputConstants.KEY_ESCAPE, "Escape");
        n.put(InputConstants.KEY_RETURN, "Enter");
        n.put(InputConstants.KEY_NUMPADENTER, "Enter");
        n.put(InputConstants.KEY_TAB, "Tab");
        n.put(InputConstants.KEY_BACKSPACE, "BSpace");
        n.put(InputConstants.KEY_INSERT, "IC");
        n.put(InputConstants.KEY_DELETE, "DC");
        n.put(InputConstants.KEY_RIGHT, "Right");
        n.put(InputConstants.KEY_LEFT, "Left");
        n.put(InputConstants.KEY_DOWN, "Down");
        n.put(InputConstants.KEY_UP, "Up");
        n.put(InputConstants.KEY_PAGEUP, "PPage");
        n.put(InputConstants.KEY_PAGEDOWN, "NPage");
        n.put(InputConstants.KEY_HOME, "Home");
        n.put(InputConstants.KEY_END, "End");
        int[] f = {InputConstants.KEY_F1, InputConstants.KEY_F2, InputConstants.KEY_F3, InputConstants.KEY_F4,
                InputConstants.KEY_F5, InputConstants.KEY_F6, InputConstants.KEY_F7, InputConstants.KEY_F8,
                InputConstants.KEY_F9, InputConstants.KEY_F10, InputConstants.KEY_F11, InputConstants.KEY_F12};
        for (int i = 0; i < f.length; i++) n.put(f[i], "F" + (i + 1));
        Map<Integer, Character> c = new HashMap<>();
        int[] letters = {InputConstants.KEY_A, InputConstants.KEY_B, InputConstants.KEY_C, InputConstants.KEY_D,
                InputConstants.KEY_E, InputConstants.KEY_F, InputConstants.KEY_G, InputConstants.KEY_H,
                InputConstants.KEY_I, InputConstants.KEY_J, InputConstants.KEY_K, InputConstants.KEY_L,
                InputConstants.KEY_M, InputConstants.KEY_N, InputConstants.KEY_O, InputConstants.KEY_P,
                InputConstants.KEY_Q, InputConstants.KEY_R, InputConstants.KEY_S, InputConstants.KEY_T,
                InputConstants.KEY_U, InputConstants.KEY_V, InputConstants.KEY_W, InputConstants.KEY_X,
                InputConstants.KEY_Y, InputConstants.KEY_Z};
        for (int i = 0; i < letters.length; i++) c.put(letters[i], (char) ('a' + i));
        int[] digits = {InputConstants.KEY_0, InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3,
                InputConstants.KEY_4, InputConstants.KEY_5, InputConstants.KEY_6, InputConstants.KEY_7,
                InputConstants.KEY_8, InputConstants.KEY_9};
        for (int i = 0; i < digits.length; i++) c.put(digits[i], (char) ('0' + i));
        c.put(InputConstants.KEY_SPACE, ' ');
        c.put(InputConstants.KEY_LBRACKET, '[');
        c.put(InputConstants.KEY_RBRACKET, ']');
        c.put(InputConstants.KEY_BACKSLASH, '\\');
        c.put(InputConstants.KEY_SLASH, '/');
        c.put(InputConstants.KEY_MINUS, '-');
        return new Keys.Table(n, c, InputConstants.MOD_SHIFT, InputConstants.MOD_CONTROL, InputConstants.MOD_ALT,
                InputConstants.MOD_SUPER);
    }
}
