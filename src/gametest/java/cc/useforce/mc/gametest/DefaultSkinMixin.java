package cc.useforce.mc.gametest;

import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** The game tests' offline player wears the maintainer's skin (slim arms) in the README's pictures. Tests only. */
@Mixin(DefaultPlayerSkin.class)
public abstract class DefaultSkinMixin {
    private static final PlayerSkin README = PlayerSkin.insecure(
            new ClientAsset.ResourceTexture(Identifier.fromNamespaceAndPath("force_mc_gametest", "entity/player/readme")),
            null, null, PlayerModelType.SLIM);

    @Inject(method = "get(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/PlayerSkin;", at = @At("HEAD"),
            cancellable = true)
    private static void force_mc$readmeSkin(UUID id, CallbackInfoReturnable<PlayerSkin> cir) {
        cir.setReturnValue(README);
    }
}
