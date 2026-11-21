package dev.migzu.handyphone.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.migzu.handyphone.client.phone.PhoneCameraSource;
import dev.migzu.handyphone.client.phone.PhoneController;
import dev.migzu.handyphone.client.phone.PhoneHandRenderer;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Com o celular fora, ele ocupa a mão principal: o item que estiver nela não aparece. */
// Prioridade baixa = nossos injetores rodam antes dos do Iris. O Iris cancela submitArmWithItem fora das passadas dele quando o item
// não combina com a passada (iris$skipTranslucentHands); sem rodar antes, ele cancelaria o desenho tardio do celular e o celular sumiria.
@Mixin(value = FirstPersonHandsAndItemsRenderer.class, priority = 500)
public abstract class FirstPersonHandsMixin {
	@Shadow
	private void renderPlayerArm(
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final float inverseArmHeight,
		final float attackValue,
		final HumanoidArm arm,
		final PlayerRenderState playerState
	) {
		throw new AssertionError();
	}

	/**
	 * Com shader, enquanto a câmera precisa de um quadro limpo, o pipeline do Iris não desenha as mãos: elas voltam depois da captura
	 * (ver PhoneCameraSource.shouldDeferHands e GameRendererMixin).
	 */
	@Inject(method = "submitHandsWithItems", at = @At("HEAD"), cancellable = true)
	private void handyphone$deferForCamera(final CallbackInfo ci) {
		if (PhoneCameraSource.INSTANCE.shouldDeferHands()) {
			ci.cancel();
		}
	}

	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void handyphone$renderPhone(
		final PlayerRenderState playerState,
		final FirstPersonHandsAndItemsRenderState state,
		final float partialTicks,
		final float xRot,
		final InteractionHand hand,
		final float attack,
		final ItemStack itemStack,
		final float inverseArmHeight,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final CallbackInfo ci
	) {
		PhoneController ctrl = PhoneController.INSTANCE;
		if (hand != InteractionHand.MAIN_HAND || !ctrl.isShown() || state.isScoping) {
			return;
		}
		AvatarRenderState avatar = playerState.avatarRenderState;
		if (avatar == null) {
			return;
		}
		HumanoidArm arm = avatar.mainArm;
		poseStack.pushPose();
		PhoneHandRenderer.render(
			poseStack,
			submitNodeCollector,
			lightCoords,
			arm,
			// Sobe de baixo da tela ao puxar e desce ao guardar, como uma troca de item; o valor do jogo
			// (que cai quando o item da mão muda ou ao atacar) não serve, senão o celular mergulharia.
			1f - ctrl.raise.f(),
			!avatar.isInvisible,
			(stack, armHeight) -> this.renderPlayerArm(stack, submitNodeCollector, lightCoords, armHeight, 0.0F, arm, playerState)
		);
		poseStack.popPose();
		ci.cancel();
	}
}
