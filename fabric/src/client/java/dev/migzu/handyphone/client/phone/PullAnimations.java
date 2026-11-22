package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.migzu.handyphone.client.os.PhoneSound;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.util.Mth;

/**
 * Animações de "puxada": o movimento que o celular faz ao ser levantado para uso.
 * Cada vez que o jogador puxa o celular, uma variação é sorteada conforme o peso.
 *
 * <p>Para criar uma variação nova, escreva um método {@code (PoseStack, float)} e
 * registre-o no bloco estático com um peso. O movimento é aplicado no espaço local
 * do aparelho (altura 1, tela no plano z = 0) e deve terminar sem deslocamento
 * quando o progresso chega a 1, para o celular assentar na pose normal.
 */
public final class PullAnimations {
	/** Aplica o movimento para um progresso de 0 (começo) a 1 (fim). */
	@FunctionalInterface
	public interface Motion {
		void apply(PoseStack poseStack, float progress);
	}

	/**
	 * @param weight  chance relativa de ser sorteada (2 sai o dobro de vezes que 1)
	 * @param seconds duração da animação
	 * @param sound   som próprio que acompanha esta animação de puxada
	 */
	public record Variant(String id, float weight, float seconds, PhoneSound sound, Motion motion) {
	}

	private static final List<Variant> VARIANTS = new ArrayList<>();
	private static float totalWeight;

	static {
		register("front_flip", 1f, 0.52f, PhoneSound.FLIP_FRONT, PullAnimations::frontFlip);
	}

	private PullAnimations() {
	}

	public static void register(String id, float weight, float seconds, PhoneSound sound, Motion motion) {
		if (weight <= 0f || seconds <= 0f) {
			throw new IllegalArgumentException("Weight and duration must be positive: " + id);
		}
		VARIANTS.add(new Variant(id, weight, seconds, sound, motion));
		totalWeight += weight;
	}

	/** Sorteia uma variação, respeitando os pesos. */
	public static Variant pick() {
		float roll = ThreadLocalRandom.current().nextFloat() * totalWeight;
		for (Variant variant : VARIANTS) {
			roll -= variant.weight();
			if (roll < 0f) {
				return variant;
			}
		}
		return VARIANTS.getLast();
	}

	/** Gira em torno do centro do aparelho (e não da face da tela). */
	private static void rotateAroundCenter(PoseStack poseStack, Axis axis, float degrees) {
		poseStack.translate(0f, 0f, -PhoneMesh.THICKNESS / 2f);
		poseStack.rotateDegrees(axis, degrees);
		poseStack.translate(0f, 0f, PhoneMesh.THICKNESS / 2f);
	}

	private static float easeOutCubic(float t) {
		float inv = 1f - t;
		return 1f - inv * inv * inv;
	}

	/** Jogado para cima, dá uma volta completa de ponta a ponta e assenta na mão. */
	private static void frontFlip(PoseStack poseStack, float t) {
		poseStack.translate(0f, 0.22f * Mth.sin(t * (float) Math.PI), 0f);
		rotateAroundCenter(poseStack, Axis.XP, 360f * easeOutCubic(t));
	}
}
