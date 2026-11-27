package dev.migzu.handyphone.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.migzu.handyphone.client.phone.PhoneWorldModel;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.PlayerItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Na mão principal de quem está com o celular, o celular no lugar do item (terceira pessoa e os outros jogadores). */
@Mixin(PlayerItemInHandLayer.class)
public abstract class PlayerItemInHandLayerMixin {
	@Inject(
		method = "submitArmWithItem(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;Lnet/minecraft/client/renderer/item/ItemStackRenderState;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/HumanoidArm;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void handyphone$phone(
		final AvatarRenderState state,
		final ItemStackRenderState itemState,
		final ItemStack itemStack,
		final HumanoidArm arm,
		final PoseStack poseStack,
		final SubmitNodeCollector collector,
		final int lightCoords,
		final CallbackInfo ci
	) {
		if (arm != state.mainArm || !PhoneWorldModel.holding(state)) {
			return;
		}
		@SuppressWarnings("unchecked")
		ArmedModel<AvatarRenderState> model = (ArmedModel<AvatarRenderState>) ((RenderLayer<?, ?>) (Object) this).getParentModel();
		PhoneWorldModel.submit(model, state, arm, poseStack, collector, lightCoords);
		ci.cancel();
	}
}
