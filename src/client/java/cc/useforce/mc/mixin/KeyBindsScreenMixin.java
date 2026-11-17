package cc.useforce.mc.mixin;

import cc.useforce.mc.ComboKey;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Controls → Key Binds, for the mod's own bindings ({@link ComboKey}): a modifier pressed alone waits for the key that
 * goes with it (vanilla would bind Ctrl itself), and the key is set with the modifiers held.
 */
@Mixin(KeyBindsScreen.class)
public abstract class KeyBindsScreenMixin {
    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void force_mc$combo(KeyEvent e, CallbackInfoReturnable<Boolean> cir) {
        if (!(((KeyBindsScreen) (Object) this).selectedKey instanceof ComboKey k) || e.isEscape()) return;
        if (ComboKey.isModifier(e.key())) {
            cir.setReturnValue(true);
            return;
        }
        k.capture(e.modifiers());
    }
}
