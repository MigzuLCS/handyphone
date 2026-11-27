package dev.migzu.handyphone.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * O balanço da caminhada e o tremor de dano do jogo, para o celular ficar firme também com shader: o Iris aplica os dois na projeção
 * da mão (e não na pilha de poses, como o jogo puro), então o {@code PhoneHandRenderer} precisa calcular a mesma matriz para desfazê-la.
 */
@Mixin(GameRenderer.class)
public interface GameRendererBobAccessor {
	@Invoker("bobHurt")
	void handyphone$bobHurt(CameraRenderState camera, PoseStack poseStack);

	@Invoker("bobView")
	void handyphone$bobView(CameraRenderState camera, PoseStack poseStack);
}
