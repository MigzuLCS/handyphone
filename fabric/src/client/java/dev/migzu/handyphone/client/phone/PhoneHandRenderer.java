package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.mixin.GameRendererBobAccessor;
import dev.migzu.handyphone.client.os.CursorArt;
import dev.migzu.handyphone.client.os.PhoneSettings;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Desenha o celular 3D na mão em primeira pessoa, com a tela "viva". */
public final class PhoneHandRenderer {
	/** Largura do aparelho quando a altura é 1. */
	public static final float ASPECT = (float) PhoneOS.FACE_W / PhoneOS.FACE_H;
	private static final PhoneMesh MESH = new PhoneMesh(ASPECT / 2f, PhoneOS.FACE_R / PhoneOS.FACE_H);
	/** Distância das texturas (tela e traseira) até o corpo, para não brigarem na profundidade. */
	private static final float SURFACE_GAP = 0.0012f;
	private static final Vector3f LIGHT_DIR = new Vector3f(-0.35f, 0.75f, 0.55f).normalize();
	private static final Vector3f HALF_DIR = new Vector3f(LIGHT_DIR).add(0f, 0f, 1f).normalize();

	/**
	 * Quanto do balanço da caminhada (e do atraso da mão ao virar) o celular na mão cancela: 0,9 = sobra 10%. Vem de
	 * Ajustes > Navegação > Balanço ao andar (padrão 10%); 1 = firme, como no expandido.
	 */
	private static float handSteady() {
		return 1f - PhoneController.INSTANCE.os.settings().handSway() / 100f;
	}

	// Cruz do cursor, em pontos da tela do celular (FACE_W x FACE_H): meio braço, meia espessura e contorno.
	private static final float CURSOR_ARM = 5.5f;
	private static final float CURSOR_THICK = 0.65f;
	private static final float CURSOR_OUTLINE = 0.55f;
	private static final float CURSOR_PRESSED_SCALE = 0.72f;
	private static final int CURSOR_COLOR = 0xFFFFFFFF;
	private static final int CURSOR_OUTLINE_COLOR = 0xFF1A1A1A;
	/** Altura do cursor de mão, da ponta do dedo ao pulso, em pontos da tela do celular. */
	private static final float CURSOR_HAND_H = 14f;

	/** Giro extra em Y (graus), usado só pelo teste automático para fotografar a lateral e a traseira. */
	public static volatile float debugYaw;
	/** Esconde o destaque da roda; só o teste automático usa, para comparar o quadro com e sem ele. */
	public static volatile boolean debugHideFocus;

	/**
	 * Ordem de desenho das camadas do aparelho ({@code collector.order}). Corpo, tela, destaque e cursor usam todos o pipeline
	 * translúcido de texto, e dentro de uma mesma ordem o jogo agrupa os desenhos por {@code RenderType} num HashMap
	 * ({@code SimpleFeatureRenderPhase}) cujo hash é o de identidade: a ordem entre os grupos muda a cada vez que o jogo abre. Quando o
	 * destaque saía antes da tela, ele gravava profundidade, a tela não era pintada embaixo dele e o brilho translúcido se misturava
	 * com o corpo escuro do aparelho: um contorno cinza em volta do item que aparecia em umas aberturas e não em outras. Com ordens
	 * diferentes a sequência é fixa (o jogo desenha a ordem 0 inteira, depois a 1...): corpo, tela, destaque, cursor.
	 */
	private static final int ORDER_BODY = 0, ORDER_SCREEN = 1, ORDER_FOCUS = 2, ORDER_CURSOR = 3;

	// Pose: {x, y, z, rotY, rotX, rotZ, altura}. x e as rotações em Y/Z são espelhados para canhotos.
	private static final float[] IDLE = {0.50f, -0.33f, -0.62f, -20f, -14f, 5f, 0.40f};
	private static final float[] RAISED = {0.25f, 0.0f, -0.52f, -11f, -4f, 1.5f, 0.52f};
	// Expandido fica um pouco acima do centro para não ficar atrás da barra de itens.
	private static final float[] EXPANDED = {0f, 0.04f, -0.50f, 0f, 0f, 0f, 0.58f};

	@FunctionalInterface
	public interface ArmRenderer {
		void render(PoseStack poseStack, float inverseArmHeight);
	}

	private PhoneHandRenderer() {
	}

	/**
	 * A mão desenhada pelo Iris (com pacote de shaders): ele monta a projeção como {@code projeção x tremor de dano x balanço da caminhada}
	 * e entrega a pilha de poses zerada. Lido por reflexão (o Iris é opcional); nenhuma classe dele é carregada sem ele.
	 */
	static final class IrisHands {
		private static boolean checked;
		private static Object instance;
		private static java.lang.reflect.Method isActive;

		private IrisHands() {
		}

		/** A mesma matriz de balanço que o Iris pôs na projeção, se é ele que está desenhando a mão agora; senão null. */
		static Matrix4f bobWhileDrawing() {
			if (!drawing()) {
				return null;
			}
			Minecraft mc = Minecraft.getInstance();
			var camera = mc.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
			if (camera == null) {
				return null;
			}
			GameRendererBobAccessor bob = (GameRendererBobAccessor) mc.gameRenderer;
			PoseStack stack = new PoseStack();
			bob.handyphone$bobHurt(camera, stack);
			if (mc.options.bobView().get()) {
				bob.handyphone$bobView(camera, stack);
			}
			return new Matrix4f(stack.last().pose());
		}

		private static boolean drawing() {
			if (!checked) {
				checked = true;
				try {
					Class<?> type = Class.forName("net.irisshaders.iris.pathways.HandRenderer");
					instance = type.getField("INSTANCE").get(null);
					isActive = type.getMethod("isActive");
				} catch (ReflectiveOperationException | LinkageError e) {
					instance = null;
				}
			}
			if (instance == null) {
				return false;
			}
			try {
				return (Boolean) isActive.invoke(instance);
			} catch (ReflectiveOperationException | RuntimeException e) {
				return false;
			}
		}
	}

	/** Quanto o celular desce no meio da troca de lado (em "altura do braço"; 1 já o tira de baixo da tela). */
	private static final float MIRROR_DIP = 1.1f;

	/** 0 a 1: o celular da tela a caminho do outro lado (selfie). */
	public static float mirrorProgress() {
		return Mth.clamp(PhoneController.INSTANCE.mirror.f(), 0f, 1f);
	}

	public static void render(
		PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, HumanoidArm arm, float inverseArmHeight, boolean showArm, ArmRenderer armRenderer
	) {
		PhoneController ctrl = PhoneController.INSTANCE;
		ctrl.prepareDisplay();
		float r = ctrl.raise.f();
		float e = ctrl.expand.f();
		float e01 = Mth.clamp(e, 0f, 1f);
		float side = arm == HumanoidArm.RIGHT ? 1f : -1f;
		// Selfie: o celular troca de lado da tela descendo e subindo (como passar de uma mão para a outra), sem atravessar o meio à vista.
		float m = mirrorProgress();
		if (m > 0f) {
			side *= (float) Math.cos(Math.PI * m);
			inverseArmHeight += (float) Math.sin(Math.PI * m) * MIRROR_DIP;
		}
		// O jogo estreita o FOV da mão debaixo d'água (e na lava): sem compensar, o celular "dá zoom" e o clique, que
		// assume o FOV normal, deixa de acertar. Esticar x e y (não z) pela razão das tangentes cancela o efeito.
		float fovFix = (float) (Math.tan(Math.toRadians(PhoneController.handFov / 2.0)) / Math.tan(Math.toRadians(PhoneController.HAND_FOV_DEGREES / 2.0)));

		// Matriz de vista que a GPU aplica depois da pilha de poses. Sem shader é a rotação da câmera (e a pilha
		// começa com a inversa dela); o Iris desenha a mão por outro caminho, com outra vista e a pilha zerada.
		// Ler a matriz em uso, em vez de supor a do jogo puro, mantém o expandido e o clique certos nos dois casos.
		Matrix4f viewRot = new Matrix4f(RenderSystem.getModelViewStack());
		// Com shader, o Iris põe o balanço da caminhada e o tremor de dano na projeção da mão, fora da pilha de poses: sem somá-los à
		// vista, a estabilização (e o clique no expandido) não os enxergava e o celular balançava inteiro na mão.
		Matrix4f irisBob = IrisHands.bobWhileDrawing();
		if (irisBob != null) {
			viewRot = irisBob.mul(viewRot);
		}

		// Na mão, o balanço da caminhada quase some (ver handSteady()); vale para o braço também, senão ele se descola do celular.
		float handSteady = handSteady() * Mth.clamp(r, 0f, 1f);

		// Braço: acompanha o celular e sai de cena quando ele expande.
		if (showArm) {
			poseStack.pushPose();
			if (handSteady > 0f) {
				poseStack.last().pose().lerp(viewRot.invert(new Matrix4f()), handSteady);
			}
			poseStack.scale(fovFix, fovFix, 1f);
			poseStack.translate(side * -0.08f * r, 0.10f * r, 0f);
			poseStack.rotateDegrees(Axis.ZP, side * 8f);
			armRenderer.render(poseStack, inverseArmHeight + e01 * 1.6f);
			poseStack.popPose();
		}

		poseStack.pushPose();
		// Expandido: cancela o balanço da caminhada para o celular ficar firme na tela. Na mão, quase todo (sobra um pouco, para
		// ele não parecer colado na câmera): balançando inteiro dava enjoo e não dava para ler. Some com a subida (r), sem pulo ao guardar.
		float steady = Math.max(e01, handSteady);
		if (steady > 0f) {
			poseStack.last().pose().lerp(viewRot.invert(new Matrix4f()), steady);
		}
		poseStack.scale(fovFix, fovFix, 1f);

		float x = mix(IDLE[0], RAISED[0], EXPANDED[0], r, e) * side;
		float y = mix(IDLE[1], RAISED[1], EXPANDED[1], r, e) - inverseArmHeight * 0.6f * (1f - e01);
		float z = mix(IDLE[2], RAISED[2], EXPANDED[2], r, e);
		float rotY = mix(IDLE[3], RAISED[3], EXPANDED[3], r, e) * side;
		float rotX = mix(IDLE[4], RAISED[4], EXPANDED[4], r, e);
		float rotZ = mix(IDLE[5], RAISED[5], EXPANDED[5], r, e) * side;
		float height = mix(IDLE[6], RAISED[6], EXPANDED[6], r, e);

		poseStack.translate(x, y, z);
		poseStack.rotateDegrees(Axis.YP, rotY);
		poseStack.rotateDegrees(Axis.XP, rotX);
		poseStack.rotateDegrees(Axis.ZP, rotZ);
		poseStack.scale(height, height, height);

		ctrl.setViewModel(new Matrix4f(viewRot).mul(poseStack.last().pose()));
		// Onde o mouse cai na tela, com a matriz deste quadro (null durante a puxada e fora do aparelho).
		float[] cursor = PhoneCursor.locate(Minecraft.getInstance(), ctrl);

		// Animação de puxada (ver PullAnimations): roda até o aparelho assentar na mão.
		float pullProgress = ctrl.pullProgress();
		if (pullProgress < 1f) {
			ctrl.pull().motion().apply(poseStack, pullProgress);
		}

		if (debugYaw != 0f) {
			poseStack.translate(0f, 0f, -PhoneMesh.THICKNESS / 2f);
			poseStack.rotateDegrees(Axis.YP, debugYaw);
			poseStack.translate(0f, 0f, PhoneMesh.THICKNESS / 2f);
		}

		// A pilha de poses da mão ainda não inclui a rotação da câmera; as normais precisam dela.
		submitModel(poseStack, collector, new Matrix3f(viewRot), lightCoords, PhoneDisplay.SCREEN_TEXTURE, ORDER_BODY, ORDER_SCREEN);
		float hw = ASPECT / 2f;
		// Destaque da roda (celular na mão), no vidro: anima a cada quadro sem redesenhar a tela.
		PhoneOS.FocusShape focus = ctrl.inHand() ? ctrl.os.focusShape() : null;
		if (focus != null) {
			PhoneSettings settings = ctrl.os.settings();
			if (!debugHideFocus) collector.order(ORDER_FOCUS).submitCustomGeometry(poseStack, RenderTypes.text(PhoneDisplay.BODY_TEXTURE),
				(pose, buffer) -> PhoneFocusRing.submit(pose, buffer, focus, settings, hw, SURFACE_GAP * 2f, LightCoordsUtil.FULL_BRIGHT));
		} else {
			PhoneFocusRing.reset();
		}
		if (cursor != null) {
			submitCursor(collector, poseStack, cursor, hw);
		}
		poseStack.popPose();
	}

	/**
	 * Corpo, traseira e tela do aparelho na pose atual (face da tela em z = 0, altura 1). Usado pela mão em primeira pessoa e pelo celular
	 * de qualquer jogador visto em terceira pessoa ({@link PhoneWorldModel}). {@code viewNormal} leva as normais ao espaço da luz fixa do
	 * sombreado; {@code screenTexture} null = tela apagada (preta).
	 */
	static void submitModel(PoseStack poseStack, SubmitNodeCollector collector, Matrix3f viewNormal, int lightCoords,
		net.minecraft.resources.Identifier screenTexture, int bodyOrder, int screenOrder) {
		// Corpo: malha sólida, sombreada pela normal de cada vértice.
		collector.order(bodyOrder).submitCustomGeometry(poseStack, RenderTypes.text(PhoneDisplay.BODY_TEXTURE), (pose, buffer) -> {
			for (PhoneMesh.Part part : MESH.parts) {
				solid(viewNormal, pose, buffer, part.quads(), part.color(), lightCoords);
			}
		});
		float hw = ASPECT / 2f;
		// Traseira (imagem espelhada, pois é vista pelo outro lado) e tela.
		float backZ = -PhoneMesh.THICKNESS - SURFACE_GAP;
		collector.order(bodyOrder).submitCustomGeometry(poseStack, RenderTypes.text(PhoneDisplay.BACK_TEXTURE), (pose, buffer) -> {
			int color = shade(viewNormal, pose, 0f, 0f, -1f, 0xFFFFFF);
			buffer.addVertex(pose, hw, -0.5f, backZ).setColor(color).setUv(0f, 1f).setLight(lightCoords);
			buffer.addVertex(pose, -hw, -0.5f, backZ).setColor(color).setUv(1f, 1f).setLight(lightCoords);
			buffer.addVertex(pose, -hw, 0.5f, backZ).setColor(color).setUv(1f, 0f).setLight(lightCoords);
			buffer.addVertex(pose, hw, 0.5f, backZ).setColor(color).setUv(0f, 0f).setLight(lightCoords);
		});
		// A tela depois do corpo: os cantos arredondados da imagem são semitransparentes e precisam do corpo já desenhado embaixo.
		boolean dark = screenTexture == null;
		int screenColor = dark ? 0xFF0A0A0C : 0xFFFFFFFF;
		collector.order(screenOrder).submitCustomGeometry(poseStack, RenderTypes.text(dark ? PhoneDisplay.BODY_TEXTURE : screenTexture), (pose, buffer) -> {
			int light = LightCoordsUtil.FULL_BRIGHT;
			float u0 = dark ? 0.5f : 0f, u1 = dark ? 0.5f : 1f, v0 = dark ? 0.5f : 0f, v1 = dark ? 0.5f : 1f;
			buffer.addVertex(pose, -hw, -0.5f, SURFACE_GAP).setColor(screenColor).setUv(u0, v1).setLight(light);
			buffer.addVertex(pose, hw, -0.5f, SURFACE_GAP).setColor(screenColor).setUv(u1, v1).setLight(light);
			buffer.addVertex(pose, hw, 0.5f, SURFACE_GAP).setColor(screenColor).setUv(u1, v0).setLight(light);
			buffer.addVertex(pose, -hw, 0.5f, SURFACE_GAP).setColor(screenColor).setUv(u0, v0).setLight(light);
		});
	}

	/**
	 * Cursor colado no vidro (Ajustes > Destaque e cursor > Cursor). Cruz: um contorno escuro e, por cima, a cruz branca (legível em qualquer
	 * papel de parede). Mão: um quad com a textura {@link PhoneDisplay#CURSOR_HAND_TEXTURE}.
	 * Medidas em pontos da tela do celular; apertar o botão encolhe a cruz, como um toque.
	 */
	private static void submitCursor(SubmitNodeCollector collector, PoseStack poseStack, float[] cursor, float hw) {
		float unit = 1f / PhoneOS.FACE_H;
		float cx = cursor[0] * unit - hw;
		float cy = 0.5f - cursor[1] * unit;
		float scale = PhoneCursor.pressed() ? CURSOR_PRESSED_SCALE : 1f;
		float arm = CURSOR_ARM * unit * scale;
		float thick = CURSOR_THICK * unit;
		float outline = CURSOR_OUTLINE * unit;
		if (PhoneController.INSTANCE.os.settings().cursorStyle() == PhoneSettings.Cursor.HAND) {
			// Mão: um quad com a textura; a ponta do dedo (CursorArt.HAND_HOT_*) cai no ponto do clique e é o centro do encolher.
			float k = CURSOR_HAND_H / CursorArt.HAND_HEIGHT * unit * scale;
			float x0 = cx - CursorArt.HAND_HOT_X * k, y0 = cy + CursorArt.HAND_HOT_Y * k;
			float x1 = x0 + CursorArt.HAND_BOX * k, y1 = y0 - CursorArt.HAND_BOX * k;
			collector.order(ORDER_CURSOR).submitCustomGeometry(poseStack, RenderTypes.text(PhoneDisplay.CURSOR_HAND_TEXTURE), (pose, buffer) -> {
				int light = LightCoordsUtil.FULL_BRIGHT;
				float z = SURFACE_GAP * 3f;
				buffer.addVertex(pose, x0, y1, z).setColor(0xFFFFFFFF).setUv(0f, 1f).setLight(light);
				buffer.addVertex(pose, x1, y1, z).setColor(0xFFFFFFFF).setUv(1f, 1f).setLight(light);
				buffer.addVertex(pose, x1, y0, z).setColor(0xFFFFFFFF).setUv(1f, 0f).setLight(light);
				buffer.addVertex(pose, x0, y0, z).setColor(0xFFFFFFFF).setUv(0f, 0f).setLight(light);
			});
			return;
		}
		collector.order(ORDER_CURSOR).submitCustomGeometry(poseStack, RenderTypes.text(PhoneDisplay.BODY_TEXTURE), (pose, buffer) -> {
			int light = LightCoordsUtil.FULL_BRIGHT;
			cross(pose, buffer, cx, cy, arm + outline, thick + outline, SURFACE_GAP * 2f, CURSOR_OUTLINE_COLOR, light);
			cross(pose, buffer, cx, cy, arm, thick, SURFACE_GAP * 3f, CURSOR_COLOR, light);
		});
	}

	/** Duas barras (horizontal e vertical) centradas em (cx, cy), no plano z da tela. */
	private static void cross(PoseStack.Pose pose, VertexConsumer buffer, float cx, float cy, float arm, float thick, float z, int color, int light) {
		rect(pose, buffer, cx - arm, cy - thick, cx + arm, cy + thick, z, color, light);
		rect(pose, buffer, cx - thick, cy - arm, cx + thick, cy - thick, z, color, light);
		rect(pose, buffer, cx - thick, cy + thick, cx + thick, cy + arm, z, color, light);
	}

	private static void rect(PoseStack.Pose pose, VertexConsumer buffer, float x0, float y0, float x1, float y1, float z, int color, int light) {
		buffer.addVertex(pose, x0, y0, z).setColor(color).setUv(0.5f, 0.5f).setLight(light);
		buffer.addVertex(pose, x1, y0, z).setColor(color).setUv(0.5f, 0.5f).setLight(light);
		buffer.addVertex(pose, x1, y1, z).setColor(color).setUv(0.5f, 0.5f).setLight(light);
		buffer.addVertex(pose, x0, y1, z).setColor(color).setUv(0.5f, 0.5f).setLight(light);
	}

	/** Desenha quads sólidos; a textura do corpo é branca no centro, então a cor vem só dos vértices. */
	private static void solid(Matrix3f viewNormal, PoseStack.Pose pose, VertexConsumer buffer, float[] quads, int baseColor, int light) {
		for (int i = 0; i < quads.length; i += PhoneMesh.STRIDE) {
			int color = shade(viewNormal, pose, quads[i + 3], quads[i + 4], quads[i + 5], baseColor);
			buffer.addVertex(pose, quads[i], quads[i + 1], quads[i + 2]).setColor(color).setUv(0.5f, 0.5f).setLight(light);
		}
	}

	/** Luz fixa em relação à câmera (vem de cima, pela esquerda) com um brilho metálico. */
	private static int shade(Matrix3f viewNormal, PoseStack.Pose pose, float nx, float ny, float nz, int baseColor) {
		Vector3f n = pose.transformNormal(nx, ny, nz, new Vector3f()).mul(viewNormal);
		float diffuse = Math.max(0f, n.dot(LIGHT_DIR));
		float specular = (float) Math.pow(Math.max(0f, n.dot(HALF_DIR)), 28) * 0.30f;
		float k = 0.42f + 0.58f * diffuse;
		int r = Math.min(255, Math.round(((baseColor >> 16) & 0xFF) * k + specular * 255f));
		int g = Math.min(255, Math.round(((baseColor >> 8) & 0xFF) * k + specular * 255f));
		int b = Math.min(255, Math.round((baseColor & 0xFF) * k + specular * 255f));
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	private static float mix(float idle, float raised, float expanded, float r, float e) {
		float a = idle + (raised - idle) * r;
		return a + (expanded - a) * e;
	}
}
