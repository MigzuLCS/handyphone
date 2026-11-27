package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneLens;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Selfie (1.3): com a câmera frontal do app Câmera, a vista do jogo vai para a frente do jogador, olhando para ele, como o F5 de frente,
 * mas perto (onde estaria o celular no braço esticado), um pouco acima e para o lado da mão. O tipo de câmera do jogo continua "primeira
 * pessoa": a mão com o celular (o visor) segue desenhada, e o corpo do jogador aparece porque a câmera fica "solta" ({@code detached}).
 */
@Mixin(Camera.class)
public abstract class CameraSelfieMixin {
	/** Distância da câmera até o rosto (blocos), antes de encostar numa parede. */
	private static final float SELFIE_DISTANCE = 1.3f;
	/** Acima dos olhos e para o lado da mão que segura (blocos). */
	private static final float SELFIE_UP = 0.1f, SELFIE_SIDE = 0.12f;
	/** Inclinação para baixo, olhando o rosto de um pouco acima (graus). */
	private static final float SELFIE_TILT = 10f;

	@Shadow
	private boolean detached;
	@Shadow
	private Entity entity;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	protected abstract void move(float zoom, float dy, float dx);

	@Shadow
	protected abstract float getMaxZoom(float distance);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void handyphone$selfie(final float partialTicks, final CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!PhoneLens.selfie() || detached || entity == null || entity != mc.player) {
			return;
		}
		float scale = entity instanceof LivingEntity living ? living.getScale() : 1f;
		// O terceiro eixo do move é a direita da câmera; virada para o jogador, a direita dela é a esquerda dele.
		float side = mc.player.getMainArm() == HumanoidArm.RIGHT ? -1f : 1f;
		detached = true;
		setRotation(yRot + 180f, -xRot + SELFIE_TILT);
		move(0f, SELFIE_UP * scale, SELFIE_SIDE * side * scale);
		move(-getMaxZoom(SELFIE_DISTANCE * scale), 0f, 0f);
	}
}
