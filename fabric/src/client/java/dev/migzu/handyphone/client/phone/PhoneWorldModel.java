package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.migzu.handyphone.client.os.PhoneOS;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;

/**
 * O celular visto de fora: na mão do próprio jogador em terceira pessoa (F5) e na dos outros jogadores. Só o aparelho, com a tela parada do
 * tema (PhoneOS.renderStill), nada do que está nela. O braço que segura muda de pose (usando ou tirando foto).
 */
public final class PhoneWorldModel {
	/** Poses: sem celular, usando (braço dobrado à frente), câmera (braço esticado na altura dos olhos), selfie (reservada para a câmera frontal). */
	public static final int NONE = 0, USE = 1, CAMERA = 2, SELFIE = 3;
	/** Distância máxima (blocos) em que o celular de alguém é desenhado. */
	public static final double MAX_DISTANCE = 30;
	/** Tema "sem aparelho": a pose do braço vale, mas o celular não é desenhado (a própria selfie). */
	static final int HIDDEN_PHONE = -1;
	/**
	 * Altura do aparelho no mundo, em blocos. Maior que um celular de verdade na mão de um jogador (de propósito: de longe precisa dar para
	 * ver); só vale aqui, na vista de fora. A primeira pessoa tem o próprio tamanho.
	 */
	static final float HEIGHT = 0.34f;

	// Ordens de desenho próprias, acima das do jogador (ver "Armadilhas": a tela depois do corpo, sempre).
	private static final int ORDER_BODY = 1, ORDER_SCREEN = 2;

	/**
	 * Ajuste fino (só os testes mexem, para comparar variações numa rodada): {x, y, z, rotX, rotY, rotZ} do aparelho na mão e, opcionais,
	 * cabeça e braço da pose "usando" (radianos).
	 */
	public static volatile float[] debugTweak = new float[6];

	/**
	 * Celular dos outros jogadores, como o servidor contou: UUID -> {pose, tema}. Só a thread do jogo mexe (os pacotes chegam nela);
	 * esvaziado ao sair do mundo.
	 */
	public static final java.util.Map<java.util.UUID, int[]> REMOTE = new java.util.HashMap<>();

	private PhoneWorldModel() {
	}

	/** Extração do estado de render do jogador (a cada quadro): pose e tema do celular dele. */
	public static void extract(Avatar entity, AvatarRenderState state) {
		PhoneHolder holder = (PhoneHolder) state;
		int pose = NONE;
		int theme = 0;
		Minecraft mc = Minecraft.getInstance();
		if (entity == mc.player) {
			// O próprio jogador (F5) sempre se vê com o celular; a opção de privacidade vale só para os outros.
			pose = localPose();
			theme = localTheme();
			if (pose == SELFIE && PhoneLens.selfie()) {
				// Na própria selfie o celular está onde fica a câmera: só o braço esticado aparece (como numa selfie de verdade).
				theme = HIDDEN_PHONE;
			}
		} else {
			int[] remote = REMOTE.get(entity.getUUID());
			if (remote != null) {
				pose = remote[0];
				theme = remote[1];
			}
		}
		if (pose != NONE && (state.isInvisible || state.isSpectator || state.distanceToCameraSq > MAX_DISTANCE * MAX_DISTANCE)) {
			pose = NONE;
		}
		holder.handyphone$setPhone(pose, theme);
	}

	/** A pose do próprio celular agora (sem olhar a opção de privacidade). */
	static int localPose() {
		PhoneController ctrl = PhoneController.INSTANCE;
		if (!ctrl.isShown() || ctrl.raise.f() <= 0.4f) {
			return NONE;
		}
		if (!"camera".equals(ctrl.os.openAppId())) {
			return USE;
		}
		return ctrl.os.cameraSelfie() ? SELFIE : CAMERA;
	}

	static int localTheme() {
		return PhoneOS.THEME_IDS.indexOf(PhoneController.INSTANCE.os.themeId());
	}

	// ------------------------------------------------------------------ rede

	/** O que o servidor já sabe da nossa pose (código pose * 16 + tema); -1 = nada ainda nesta conexão. */
	private static int sentCode = -1;
	/** Ticks desde o último envio da pose. Uma pose pode se perder (o servidor descarta pacotes quando o orçamento acaba): com o celular
	 * na mão a pose é repetida a cada {@link #RESEND_TICKS}; o "guardado" é repetido uma vez, logo depois. */
	private static int sinceSent;
	private static boolean noneRepeated;
	private static final int RESEND_TICKS = 100;

	public static void register() {
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			sentCode = -1;
			sinceSent = 0;
			noneRepeated = false;
			REMOTE.clear();
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			sentCode = -1;
			REMOTE.clear();
		});
		dev.migzu.handyphone.fabric.client.FabricClientNet.receive(dev.migzu.handyphone.net.PhonePayloads.PhonePoses.TYPE, (packet, ctx) -> {
			for (dev.migzu.handyphone.net.PhonePayloads.PoseEntry e : packet.entries()) {
				java.util.UUID id = new java.util.UUID(e.uuidHigh(), e.uuidLow());
				int pose = e.pose();
				if (pose <= NONE || pose > dev.migzu.handyphone.net.PhonePayloads.MAX_POSE) {
					REMOTE.remove(id);
				} else {
					REMOTE.put(id, new int[] {pose, e.theme()});
				}
			}
		});
	}

	/**
	 * A cada tick: avisa o servidor quando a pose do celular muda. Com "Mostrar o celular aos outros" desligado, manda sempre "guardado" (o
	 * servidor nem tem o que repassar). Servidor sem o canal (antigo, ou sem o mod): não manda nada.
	 */
	public static void tick(Minecraft mc) {
		if (mc.getConnection() == null || mc.player == null) {
			return;
		}
		int pose = PhoneController.INSTANCE.os.settings().showToOthers() ? localPose() : NONE;
		int theme = pose == NONE ? 0 : Math.max(0, localTheme());
		int code = pose * 16 + theme;
		sinceSent++;
		boolean resend = code == sentCode && sentCode > 0 && sinceSent >= RESEND_TICKS
			|| code == sentCode && sentCode == 0 && !noneRepeated && sinceSent >= RESEND_TICKS / 2;
		if ((code == sentCode && !resend) || (sentCode == -1 && pose == NONE)) {
			if (sentCode == -1 && pose == NONE) {
				sentCode = 0;
				noneRepeated = true;
			}
			return;
		}
		var type = dev.migzu.handyphone.net.PhonePayloads.PhonePose.TYPE;
		if (!dev.migzu.handyphone.fabric.client.FabricClientNet.canSend(type)) {
			return;
		}
		dev.migzu.handyphone.fabric.client.FabricClientNet.send(new dev.migzu.handyphone.net.PhonePayloads.PhonePose(pose, theme));
		noneRepeated = code == sentCode && code == 0;
		sentCode = code;
		sinceSent = 0;
	}

	public static boolean holding(HumanoidRenderState state) {
		return state instanceof PhoneHolder holder && holder.handyphone$phonePose() != NONE;
	}

	/** Pose do braço da mão principal, por cima da que o jogo calculou (chamado no fim de {@code HumanoidModel.setupAnim}). */
	public static void poseArm(HumanoidModel<?> model, HumanoidRenderState state) {
		if (!(state instanceof PhoneHolder holder)) {
			return;
		}
		int pose = holder.handyphone$phonePose();
		if (pose == NONE) {
			return;
		}
		boolean right = state.mainArm == HumanoidArm.RIGHT;
		ModelPart arm = right ? model.rightArm : model.leftArm;
		float inward = right ? -1f : 1f;
		float headX = model.head.xRot, headY = model.head.yRot;
		if (pose == USE) {
			// Olhando para o celular: a cabeça baixa para a tela (um pouco do olhar de verdade ainda aparece) e o braço dobrado à frente,
			// com o aparelho na altura do peito, perto do rosto e puxado para o meio do corpo.
			float look = Mth.clamp(headX, -0.8f, 0.8f);
			model.head.xRot = HEAD_DOWN + t(TWEAK_HEAD) + look * 0.2f;
			arm.xRot = ARM_UP + t(TWEAK_ARM) + look * 0.15f + (state.isCrouching ? 0.3f : 0f);
			arm.yRot = inward * 0.5f + headY * 0.3f;
			arm.zRot = 0f;
		} else if (pose == SELFIE) {
			// Selfie: braço esticado para a frente e para cima, aberto para o lado da mão (o celular fica na frente do rosto, um pouco
			// acima e de lado, de onde a câmera frontal o enquadra). A cabeça olha para o celular.
			arm.xRot = headX - Mth.HALF_PI - 0.35f + (state.isCrouching ? 0.3f : 0f);
			arm.yRot = headY - inward * 0.32f;
			arm.zRot = 0f;
		} else {
			// Câmera: braço esticado na direção do olhar, um pouco para o meio, como quem enquadra.
			arm.xRot = headX - Mth.HALF_PI + (state.isCrouching ? 0.3f : 0f);
			arm.yRot = headY + inward * 0.12f;
			arm.zRot = 0f;
		}
	}

	/** Cabeça baixa (radianos) e braço levantado (radianos, negativo = para a frente) da pose "usando". */
	private static final float HEAD_DOWN = 0.55f, ARM_UP = -1.15f;
	// Índices extras do debugTweak (só testes): ajuste da cabeça e do braço, em radianos.
	private static final int TWEAK_HEAD = 6, TWEAK_ARM = 7;

	private static float t(int i) {
		float[] t = debugTweak;
		return i < t.length ? t[i] : 0f;
	}

	/** Desenha o celular na mão principal (no lugar do item). */
	public static void submit(ArmedModel<AvatarRenderState> model, AvatarRenderState state, HumanoidArm arm, PoseStack poseStack,
		SubmitNodeCollector collector, int lightCoords) {
		PhoneHolder holder = (PhoneHolder) state;
		int pose = holder.handyphone$phonePose();
		if (holder.handyphone$phoneTheme() == HIDDEN_PHONE) {
			return;
		}
		PhoneController.INSTANCE.display.ensureTextures();
		Identifier screen = PhoneDisplay.stillTexture(holder.handyphone$phoneTheme());
		float side = arm == HumanoidArm.LEFT ? -1f : 1f;
		float[] t = debugTweak;

		poseStack.pushPose();
		model.translateToHand(state, arm, poseStack);
		// Mesmo ponto de partida do item na mão (ItemInHandLayer): na palma, apontando para a frente.
		poseStack.rotateDegrees(Axis.XP, -90f);
		poseStack.rotateDegrees(Axis.YP, 180f);
		poseStack.translate(side / 16f, 0.125f, -0.625f);
		poseStack.translate(side * t[0], t[1], t[2]);
		if (pose == USE) {
			// Deitado para trás na palma (uns 55° da vertical): a tela aponta para os olhos da cabeça baixa.
			poseStack.rotateDegrees(Axis.XP, -5f + t[3]);
		} else {
			// Câmera (e selfie): em pé, de frente para o rosto de quem segura, as lentes para a frente (escolhido pelo Migzu entre
			// variações de lado; com o topo deitado para a frente a tela parecia apontar para o peito).
			poseStack.rotateDegrees(Axis.XP, -5f + t[3]);
		}
		poseStack.rotateDegrees(Axis.YP, side * t[4]);
		poseStack.rotateDegrees(Axis.ZP, side * t[5]);
		poseStack.scale(HEIGHT, HEIGHT, HEIGHT);
		poseStack.translate(0f, 0.42f, PhoneMesh.THICKNESS / 2f);
		PhoneHandRenderer.submitModel(poseStack, collector, new Matrix3f(), lightCoords, screen, ORDER_BODY, ORDER_SCREEN);
		poseStack.popPose();
	}
}
