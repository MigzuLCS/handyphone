package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Com o celular fora, o jogador não ataca, não quebra bloco e não usa item. A tela do celular já impede isso enquanto
 * está aberta; estes cortes cobrem a descida, quando a tela já fechou mas o celular ainda ocupa a mão e o item continua escondido.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void handyphone$noAttack(final CallbackInfoReturnable<Boolean> cir) {
		if (PhoneController.INSTANCE.isShown()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void handyphone$noUse(final CallbackInfo ci) {
		if (PhoneController.INSTANCE.isShown()) {
			ci.cancel();
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void handyphone$noBreak(final boolean down, final CallbackInfo ci) {
		// Só corta quem está batendo; soltar o botão (down = false) precisa passar para zerar o tempo de espera do jogo.
		if (down && PhoneController.INSTANCE.isShown()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void handyphone$noPick(final CallbackInfo ci) {
		if (PhoneController.INSTANCE.isShown()) {
			ci.cancel();
		}
	}
}
