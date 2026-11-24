package dev.migzu.handyphone.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.client.phone.PhoneController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Celular na mão (sem tela aberta): a roda move o destaque em vez de trocar o item da barra, e os botões esquerdo, direito e
 * do meio são do celular (tocar, voltar, expandir) em vez de atacar, usar e escolher bloco. O mouse continua girando a câmera.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
	private void handyphone$scroll(final long window, final double xOffset, final double yOffset, final CallbackInfo ci) {
		if (window == Minecraft.getInstance().getWindow().handle() && PhoneController.INSTANCE.handScroll(yOffset)) {
			ci.cancel();
		}
	}

	@Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
	private void handyphone$button(final long window, final MouseButtonInfo info, final int action, final CallbackInfo ci) {
		if (window == Minecraft.getInstance().getWindow().handle()
			&& PhoneController.INSTANCE.handButton(info.button(), action == InputConstants.PRESS)) {
			ci.cancel();
		}
	}
}
