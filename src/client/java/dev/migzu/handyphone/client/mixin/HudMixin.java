package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Com o celular expandido no centro da tela, a mira do jogo some: ela ficaria por cima do aparelho. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@Inject(method = "extractCrosshair", at = @At("HEAD"), cancellable = true)
	private void handyphone$hideCrosshair(final GuiGraphicsExtractor graphics, final DeltaTracker deltaTracker, final CallbackInfo ci) {
		if (PhoneController.INSTANCE.isExpanded()) {
			ci.cancel();
		}
	}
}
