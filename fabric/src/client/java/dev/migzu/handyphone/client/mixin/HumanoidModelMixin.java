package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneWorldModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pose do braço que segura o celular (usando ou tirando foto), por cima da pose que o jogo calculou. */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V", at = @At("TAIL"))
	private void handyphone$phoneArm(final HumanoidRenderState state, final CallbackInfo ci) {
		PhoneWorldModel.poseArm((HumanoidModel<?>) (Object) this, state);
	}
}
