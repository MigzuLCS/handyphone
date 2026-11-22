package dev.migzu.handyphone.client.phone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneClock;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.os.PhoneSound;
import dev.migzu.handyphone.client.os.Spring;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemUseAnimation;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Estado do celular do jogador local: guardado, em uso na mão, ou expandido.
 * Todo jogador tem celular, puxado pela tecla (sem depender de nenhum item); enquanto ele está fora,
 * o item da mão principal fica escondido e o jogo não ataca nem usa item (ver FirstPersonHandsMixin e MinecraftMixin).
 */
public final class PhoneController {
	// GSON vem antes de INSTANCE: o construtor lê a configuração salva, e na ordem inversa o GSON ainda era null.
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final PhoneController INSTANCE = new PhoneController();
	/** FOV vertical fixo que o Minecraft usa para desenhar a mão. */
	public static final double HAND_FOV_DEGREES = 70.0;

	private static final class ConfigData {
		String theme = "flat";
		boolean uiSounds = true;
		boolean keyboardSounds = true;
	}

	/** FOV vertical (graus) com que o jogo desenha a mão neste quadro; atualizado pelo mixin do GameRenderer. */
	public static volatile float handFov = (float) HAND_FOV_DEGREES;

	public final PhoneOS os = new PhoneOS();
	public final PhoneDisplay display = new PhoneDisplay(os);

	/** 0 = abaixo da tela (guardado), 1 = levantado para usar. */
	public final Spring raise = new Spring(0, 190, 20);
	/** 0 = na mão, 1 = expandido no centro da tela. */
	public final Spring expand = new Spring(0, 230, 23);

	/** Animação de puxada sorteada na última vez que o celular foi levantado. */
	private PullAnimations.Variant pull = PullAnimations.pick();
	/** Progresso da animação de puxada: 0 = começou, 1 = terminou. */
	private float pullProgress = 1f;

	private boolean inUse;
	private boolean expanded;
	/** O celular estava visível no último quadro; serve para devolver o item da mão quando ele some. */
	private boolean wasShown;
	/** Câmera que o jogador usava antes de puxar o celular (o celular só existe em primeira pessoa). */
	private CameraType previousCamera;
	private long lastFrameNanos;
	private double lastVirtualSeconds;
	/** Transformação do retângulo da tela para o espaço da câmera, do último quadro. */
	private final Matrix4f viewModel = new Matrix4f();
	private boolean hasViewModel;

	private PhoneController() {
		loadConfig();
		os.setSettingsListener(this::saveConfig);
		os.setCamera(PhoneCameraSource.INSTANCE);
		os.setSoundListener(sound -> PhoneSounds.play(sound, os.themeId()));
	}

	/** Arquivo único de configuração do celular (estilo e sons). */
	private static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("handyphone.json");
	}

	/** Arquivo antigo onde ficava apenas o estilo; mantido para leitura de migração. */
	private static Path legacyThemeFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("handyphone-theme.txt");
	}

	private void loadConfig() {
		try {
			Path file = configFile();
			if (Files.exists(file)) {
				ConfigData cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ConfigData.class);
				if (cfg != null) {
					if (cfg.theme != null) {
						os.setTheme(cfg.theme.trim());
					}
					os.setUiSounds(cfg.uiSounds);
					os.setKeyboardSounds(cfg.keyboardSounds);
				}
				return;
			}
			Path legacy = legacyThemeFile();
			if (Files.exists(legacy)) {
				os.setTheme(Files.readString(legacy, StandardCharsets.UTF_8).trim());
				saveConfig();
			}
		} catch (IOException | RuntimeException e) {
			HandyPhone.LOGGER.warn("Couldn't read the saved phone settings", e);
		}
	}

	private void saveConfig() {
		try {
			ConfigData cfg = new ConfigData();
			cfg.theme = os.themeId();
			cfg.uiSounds = os.uiSounds();
			cfg.keyboardSounds = os.keyboardSounds();
			Files.writeString(configFile(), GSON.toJson(cfg) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			HandyPhone.LOGGER.warn("Couldn't save the phone settings", e);
		}
	}

	public boolean isInUse() {
		return inUse;
	}

	/** O celular está (ou está indo para) o centro da tela, expandido. */
	public boolean isExpanded() {
		return inUse && expanded;
	}

	/**
	 * O celular está na tela, subindo, em uso ou descendo. Enquanto isso o item da mão principal
	 * não aparece e o jogo não ataca nem usa item (inclusive durante a descida, com a tela já fechada).
	 */
	public boolean isShown() {
		return inUse || Math.abs(raise.value) > 0.003 || Math.abs(expand.value) > 0.003;
	}

	/** Só dá para puxar o celular onde a mão em primeira pessoa aparece: nada de espectador, dormindo ou interface oculta. */
	private static boolean canPull(Minecraft mc) {
		LocalPlayer player = mc.player;
		return player != null && mc.level != null && mc.gameMode != null && !player.isSpectator() && !player.isSleeping() && !mc.gui.hud.isHidden();
	}

	public void open(boolean startExpanded) {
		Minecraft mc = Minecraft.getInstance();
		if (inUse || mc.gui.screen() != null || !canPull(mc)) {
			return;
		}
		LocalPlayer player = mc.player;
		// Comer, armar o arco ou levantar o escudo não continuam com a tela do celular aberta; o jogo só soltaria o
		// uso na leitura das teclas, que não roda com tela aberta. Uma rachadura de bloco em andamento também não fica congelada.
		if (player.isUsingItem()) {
			// Soltar dispararia o arco, a besta, a lança ou o tridente (e o tridente com Correnteza lança o jogador): nesses, não puxa.
			ItemUseAnimation animation = player.getUseItem().getUseAnimation();
			if (animation == ItemUseAnimation.BOW || animation == ItemUseAnimation.CROSSBOW
				|| animation == ItemUseAnimation.TRIDENT || animation == ItemUseAnimation.SPEAR) {
				return;
			}
			mc.gameMode.releaseUsingItem(player);
		}
		mc.gameMode.stopDestroyBlock();
		// O celular só é desenhado em primeira pessoa.
		previousCamera = mc.options.getCameraType();
		if (previousCamera != CameraType.FIRST_PERSON) {
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		}
		inUse = true;
		wasShown = true;
		expanded = startExpanded;
		raise.target = 1;
		expand.target = expanded ? 1 : 0;
		pull = PullAnimations.pick();
		pullProgress = 0f;
		String themeId;
		synchronized (os) {
			themeId = os.themeId();
			os.setAwake(true);
		}
		PhoneSounds.play(PhoneSound.PULL, themeId);
		PhoneSounds.play(pull.sound(), themeId);
		mc.gui.setScreen(new PhoneInputScreen());
	}

	/** Chamado quando a tela de entrada fecha (por qualquer motivo). */
	public void onClosed() {
		if (!inUse) {
			return;
		}
		inUse = false;
		expanded = false;
		raise.target = 0;
		expand.target = 0;
		Minecraft mc = Minecraft.getInstance();
		if (previousCamera != null && previousCamera != CameraType.FIRST_PERSON && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
			mc.options.setCameraType(previousCamera);
		}
		previousCamera = null;
		String themeId;
		synchronized (os) {
			themeId = os.themeId();
			os.setAwake(false);
		}
		PhoneSounds.play(PhoneSound.PUT_AWAY, themeId);
	}

	public void toggleExpanded() {
		if (!inUse) {
			return;
		}
		expanded = !expanded;
		expand.target = expanded ? 1 : 0;
		String themeId;
		synchronized (os) {
			themeId = os.themeId();
		}
		PhoneSounds.play(expanded ? PhoneSound.EXPAND : PhoneSound.COLLAPSE, themeId);
	}

	/**
	 * Avança as animações; chamado uma vez por quadro por GameRendererMixin, desenhe-se a mão ou não (em terceira pessoa,
	 * dormindo ou com a interface oculta ela não é desenhada, e o celular precisa terminar de descer do mesmo jeito).
	 */
	public void advance() {
		double dt;
		if (PhoneClock.VIRTUAL) {
			// Gravação das cenas: o tempo anda só quando o teste manda (um passo por captura), não com o relógio de verdade.
			double virtualNow = PhoneClock.seconds();
			dt = virtualNow - lastVirtualSeconds;
			lastVirtualSeconds = virtualNow;
		} else {
			long now = System.nanoTime();
			dt = lastFrameNanos == 0 ? 0 : Math.min((now - lastFrameNanos) / 1e9, 0.1);
			lastFrameNanos = now;
		}
		if (!wasShown) {
			return;
		}
		raise.step(dt);
		expand.step(dt);
		pullProgress = (float) Math.min(1.0, pullProgress + dt / pull.seconds());
		if (!isShown()) {
			// O celular acabou de sumir: o item da mão sobe como numa troca de slot, sem pular na tela.
			wasShown = false;
			// A mola pode ter parado perto de zero ainda com velocidade; sem isto a próxima puxada afundaria um pouco antes de subir.
			raise.snap(0);
			expand.snap(0);
			LocalPlayer player = Minecraft.getInstance().player;
			if (player != null) {
				player.itemUsed(InteractionHand.MAIN_HAND);
			}
		}
	}

	/** Prepara a textura da tela; chamado por quem desenha o celular. */
	public void prepareDisplay() {
		display.prepare();
	}

	public PullAnimations.Variant pull() {
		return pull;
	}

	/** Progresso da animação de puxada, de 0 a 1. */
	public float pullProgress() {
		return pullProgress;
	}

	public void setViewModel(Matrix4f matrix) {
		viewModel.set(matrix);
		hasViewModel = true;
	}

	/**
	 * Converte a posição do mouse (0..1 da janela, origem no canto superior esquerdo)
	 * em coordenadas da tela do celular, traçando um raio até o aparelho 3D.
	 * Retorna null se o mouse não está sobre o celular.
	 */
	public float[] pick(double fracX, double fracY, double aspect) {
		// Durante a animação de puxada a tela não está de frente; ignora toques.
		if (!hasViewModel || pullProgress < 1f) {
			return null;
		}
		// O raio usa o FOV com que a mão foi desenhada de fato (o celular já vem compensado, ver PhoneHandRenderer).
		double tanHalf = Math.tan(Math.toRadians(handFov / 2.0));
		float dx = (float) ((fracX * 2 - 1) * tanHalf * aspect);
		float dy = (float) ((1 - fracY * 2) * tanHalf);
		Matrix4f inv = viewModel.invert(new Matrix4f());
		Vector4f origin = inv.transform(new Vector4f(0, 0, 0, 1));
		Vector4f dir = inv.transform(new Vector4f(dx, dy, -1, 0));
		if (Math.abs(dir.z) < 1e-6f) {
			return null;
		}
		float t = -origin.z / dir.z;
		if (t <= 0) {
			return null;
		}
		float lx = origin.x + dir.x * t;
		float ly = origin.y + dir.y * t;
		float halfW = PhoneHandRenderer.ASPECT / 2f;
		if (lx < -halfW || lx > halfW || ly < -0.5f || ly > 0.5f) {
			return null;
		}
		return new float[] {(lx + halfW) / PhoneHandRenderer.ASPECT * PhoneOS.FACE_W, (0.5f - ly) * PhoneOS.FACE_H};
	}
}
