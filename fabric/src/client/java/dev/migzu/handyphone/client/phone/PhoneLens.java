package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.client.os.PhoneOS;
import net.minecraft.client.Minecraft;

/**
 * O que o app Câmera pede ao jogo (1.3): zoom (estreita o campo de visão, como a luneta) e, no passo da selfie, a vista na frente do
 * jogador. Lido uma vez por tick do PhoneOS (com o lock dele) e servido aos mixins sem lock.
 */
public final class PhoneLens {
	private static volatile float zoom = 1f;
	private static volatile boolean selfie;

	private PhoneLens() {
	}

	/** A cada tick do cliente. Só vale com o celular em uso e o app Câmera aberto (o PhoneOS já devolve 1 fora dele). */
	public static void tick(Minecraft mc) {
		PhoneController ctrl = PhoneController.INSTANCE;
		float z = 1f;
		boolean front = false;
		if (mc.player != null && ctrl.isShown() && !mc.player.isSleeping() && mc.getCameraEntity() == mc.player) {
			PhoneOS os = ctrl.os;
			synchronized (os) {
				z = os.cameraZoom();
				front = os.cameraSelfie();
			}
		}
		zoom = Math.max(1f, Math.min(PhoneOS.CAMERA_MAX_ZOOM, z));
		selfie = front;
	}

	/** A câmera frontal está em uso: a vista do jogo vai para a frente do jogador (CameraSelfieMixin). */
	public static boolean selfie() {
		return selfie;
	}

	public static float zoom() {
		return zoom;
	}

	/**
	 * Multiplicador do campo de visão para um zoom (1 = nenhum). O jogo multiplica o FOV em graus; para a imagem crescer {@code zoom}
	 * vezes de verdade, a tangente do meio-ângulo é que divide (a 70° e 4x, dá ~0,23 em vez de 0,25).
	 */
	public static float fovModifier(float zoom, float fovDegrees) {
		if (zoom <= 1f) {
			return 1f;
		}
		double half = Math.toRadians(fovDegrees) / 2.0;
		double zoomed = Math.atan(Math.tan(half) / zoom);
		return (float) (zoomed / half);
	}
}
