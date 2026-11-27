package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneWorldModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Decide, a cada quadro, se o jogador está com o celular na mão (ver PhoneWorldModel.extract). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void handyphone$phone(final Avatar entity, final AvatarRenderState state, final float partialTicks, final CallbackInfo ci) {
		PhoneWorldModel.extract(entity, state);
	}
}
