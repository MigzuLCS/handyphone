package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneLens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Zoom do app Câmera (1.3): o mesmo caminho da luneta. O jogo suaviza a troca sozinho ({@code Camera.tickFov}, metade por tick) e o
 * campo de visão da mão (o celular) não muda.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class FieldOfViewMixin {
	@Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
	private void handyphone$cameraZoom(final boolean firstPerson, final float effectScale, final CallbackInfoReturnable<Float> cir) {
		float zoom = PhoneLens.zoom();
		Minecraft mc = Minecraft.getInstance();
		if (zoom > 1f && firstPerson && (Object) this == mc.player) {
			cir.setReturnValue(cir.getReturnValueF() * PhoneLens.fovModifier(zoom, mc.options.fov().get()));
		}
	}
}
