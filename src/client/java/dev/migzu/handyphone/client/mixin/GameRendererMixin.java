package dev.migzu.handyphone.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.migzu.handyphone.client.phone.PhoneCameraSource;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import dev.migzu.handyphone.client.phone.PhoneController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ponto de captura da câmera do celular: logo antes de a mão (com o celular e o
 * braço) ser desenhada, o alvo de renderização principal já tem o mundo e ainda não
 * tem a mão nem a interface, que só entram depois.
 */
// O Iris redireciona essa mesma chamada (para pulá-la com shader); o @WrapOperation do MixinExtras envolve o redirecionamento, então a nossa
// escolha de chamar direto ou de seguir adiante vale com ou sem Iris. A prioridade só fixa a ordem entre envoltórios de outros mods.
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class GameRendererMixin {
	/** Uma vez por quadro, mesmo quando a mão não é desenhada (terceira pessoa, dormindo, F1). */
	@Inject(method = "render", at = @At("HEAD"))
	private void handyphone$advancePhone(final CallbackInfo ci) {
		PhoneController.INSTANCE.advance();
		PhoneCameraSource.INSTANCE.beginFrame();
	}

	/**
	 * Depois da captura da câmera, o jogo desenha as mãos pelo caminho de sempre. Com shader o Iris pula essa chamada (ele já as
	 * desenhou dentro do pipeline); quando a câmera as adiou, esta chamada as desenha de verdade, direto, sem passar pelo
	 * envoltório do Iris. Sem shader, ou sem adiamento, tudo segue como era.
	 */
	@WrapOperation(
		method = "renderItemInHand",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;submitHandsWithItems(FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/level/FirstPersonHandsAndItemsRenderState;)V")
	)
	private void handyphone$drawDeferredHands(
		final FirstPersonHandsAndItemsRenderer hands, final float partialTicks, final PoseStack poseStack, final SubmitNodeCollector collector,
		final PlayerRenderState playerState, final net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState handsState,
		final Operation<Void> original
	) {
		PhoneCameraSource camera = PhoneCameraSource.INSTANCE;
		if (camera.takeDeferredHands()) {
			camera.deferredHandsPass(true);
			try {
				hands.submitHandsWithItems(partialTicks, poseStack, collector, playerState, handsState);
			} finally {
				camera.deferredHandsPass(false);
			}
		} else {
			original.call(hands, partialTicks, poseStack, collector, playerState, handsState);
		}
	}

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void handyphone$captureWorld(
		final CameraRenderState cameraState, final PlayerRenderState playerState, final OptionsRenderState optionsState,
		final boolean consistentDepthRequired, final CallbackInfo ci
	) {
		// O FOV da mão muda debaixo d'água, na lava e ao morrer; o desenho do celular compensa (ver PhoneHandRenderer).
		PhoneController.handFov = cameraState.hudFov;
		PhoneCameraSource.INSTANCE.onWorldRendered(((GameRenderer) (Object) this).mainRenderTarget());
	}
}
