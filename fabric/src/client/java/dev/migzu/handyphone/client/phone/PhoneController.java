package dev.migzu.handyphone.client.phone;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneClock;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.os.PhoneSettings;
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

	/** Campo do handyphone.json que não é opção do celular: último conflito de tecla já avisado no chat (KeyConflictNotice). */
	private static final String KEY_CONFLICT_FIELD = "keyConflictWarned";

	/** Último conflito de tecla já avisado; ver {@link KeyConflictNotice}. */
	private String keyConflictWarned = "";

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
	/** A tela de entrada está fechando para o celular voltar à mão (não é para guardá-lo). */
	private boolean shrinking;
	/** Fração da roda ainda não usada (touchpad manda frações de clique). */
	private double scrollRemainder;
	/** Quando o celular foi para a mão pela última vez (puxado ou encolhido); a dica dos controles aparece por alguns segundos. */
	private long handSinceMillis;
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
		applySettingsToGame();
		os.setSettingsListener(() -> {
			saveConfig();
			applySettingsToGame();
		});
		os.setShortcutKeys(PhoneShortcut.INSTANCE);
		os.setModVersion(FabricLoader.getInstance().getModContainer(HandyPhone.MOD_ID)
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""));
		os.setCamera(PhoneCameraSource.INSTANCE);
		os.setSoundListener(sound -> PhoneSounds.play(sound, os.soundThemeId()));
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
				// Os campos de PhoneSettings ficam no topo do arquivo, com os mesmos nomes de antes (theme, uiSounds, keyboardSounds):
				// o arquivo de versões anteriores é lido sem migração, e o que faltar fica no padrão.
				JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
				if (root != null && root.isJsonObject()) {
					JsonObject obj = root.getAsJsonObject();
					PhoneSettings cfg = GSON.fromJson(obj, PhoneSettings.class);
					if (cfg != null) {
						os.applySettings(cfg);
					}
					if (obj.has(KEY_CONFLICT_FIELD) && obj.get(KEY_CONFLICT_FIELD).isJsonPrimitive()) {
						keyConflictWarned = obj.get(KEY_CONFLICT_FIELD).getAsString();
					}
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

	/** O que das opções vale fora do PhoneOS (o resto o jogo lê na hora: balanço, dica). */
	private void applySettingsToGame() {
		PhoneSounds.setVolume(os.settings().volume() / 100f);
	}

	String keyConflictWarned() {
		return keyConflictWarned;
	}

	void setKeyConflictWarned(String signature) {
		if (!signature.equals(keyConflictWarned)) {
			keyConflictWarned = signature;
			saveConfig();
		}
	}

	private void saveConfig() {
		try {
			JsonObject obj = GSON.toJsonTree(os.settings()).getAsJsonObject();
			obj.addProperty(KEY_CONFLICT_FIELD, keyConflictWarned);
			Files.writeString(configFile(), GSON.toJson(obj) + "\n", StandardCharsets.UTF_8);
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
			themeId = os.soundThemeId();
			os.setAwake(true);
			os.setFocusMode(!expanded);
		}
		scrollRemainder = 0;
		handSinceMillis = System.currentTimeMillis();
		PhoneSounds.play(PhoneSound.PULL, themeId);
		PhoneSounds.play(pull.sound(), themeId);
		if (expanded) {
			mc.gui.setScreen(new PhoneInputScreen());
		}
	}

	/** Guarda o celular, esteja ele na mão (sem tela) ou expandido (a tela fecha e chama {@link #onClosed}). */
	public void putAway() {
		if (!inUse) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.gui.screen() instanceof PhoneInputScreen) {
			mc.gui.setScreen(null);
		} else {
			onClosed();
		}
	}

	/** A tela de entrada fechou. Se foi para voltar à mão (encolher), o celular continua em uso; senão, foi guardado. */
	void onScreenRemoved() {
		if (!shrinking) {
			onClosed();
		}
	}

	/** Guarda o celular (a tela já fechou, ou o celular estava na mão). */
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
			themeId = os.soundThemeId();
			os.setAwake(false);
			os.setFocusMode(false);
		}
		PhoneSounds.play(PhoneSound.PUT_AWAY, themeId);
	}

	/**
	 * Expandido (no centro, com cursor e teclado: abre a tela de entrada) ou na mão (câmera livre, a roda move o destaque:
	 * a tela fecha sem guardar o celular).
	 */
	public void toggleExpanded() {
		if (!inUse) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		expanded = !expanded;
		expand.target = expanded ? 1 : 0;
		String themeId;
		synchronized (os) {
			themeId = os.soundThemeId();
			os.setFocusMode(!expanded);
			if (!expanded) {
				os.blurKeyboard();
			}
		}
		scrollRemainder = 0;
		if (!expanded) {
			handSinceMillis = System.currentTimeMillis();
		}
		PhoneSounds.play(expanded ? PhoneSound.EXPAND : PhoneSound.COLLAPSE, themeId);
		if (expanded) {
			if (!(mc.gui.screen() instanceof PhoneInputScreen)) {
				mc.gui.setScreen(new PhoneInputScreen());
			}
		} else if (mc.gui.screen() instanceof PhoneInputScreen) {
			shrinking = true;
			try {
				mc.gui.setScreen(null);
			} finally {
				shrinking = false;
			}
		}
	}

	long handSinceMillis() {
		return handSinceMillis;
	}

	/** Celular na mão, sem tela: a roda, os botões do mouse e a tecla são do celular (ver MouseHandlerMixin e handTick). */
	public boolean inHand() {
		return inUse && !expanded && Minecraft.getInstance().gui.screen() == null;
	}

	/**
	 * Roda com o celular na mão (no lugar de trocar o item da barra). Cada clique move o destaque um item; frações (touchpad)
	 * se acumulam. Retorna true se o evento foi do celular.
	 */
	public boolean handScroll(double amount) {
		if (!inHand()) {
			return false;
		}
		scrollRemainder += amount;
		synchronized (os) {
			while (scrollRemainder >= 1) {
				os.focusMove(-1);
				scrollRemainder -= 1;
			}
			while (scrollRemainder <= -1) {
				os.focusMove(1);
				scrollRemainder += 1;
			}
		}
		return true;
	}

	/**
	 * Botões do mouse com o celular na mão: esquerdo toca o item destacado, direito volta (na tela inicial, guarda), meio
	 * expande. Só o apertar é do celular; soltar segue para o jogo (zera o que estava apertado antes de puxar). Os botões
	 * laterais não são tocados. Retorna true se o evento foi do celular.
	 */
	public boolean handButton(int button, boolean press) {
		if (!press || !inHand() || (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT
			&& button != InputConstants.MOUSE_BUTTON_MIDDLE)) {
			return false;
		}
		if (button == InputConstants.MOUSE_BUTTON_MIDDLE) {
			toggleExpanded();
			return true;
		}
		boolean typing;
		boolean handled = true;
		synchronized (os) {
			// Só expande se este clique acabou de tocar num campo de texto (nunca por um campo que já estava ativo).
			boolean before = os.wantsKeyboard();
			if (button == InputConstants.MOUSE_BUTTON_LEFT) {
				os.focusActivate();
			} else {
				handled = os.back();
			}
			typing = !before && os.wantsKeyboard();
		}
		if (!handled) {
			putAway();
		} else if (typing) {
			// Tocou num campo de texto: o teclado precisa da tela de entrada, então o celular expande sozinho.
			toggleExpanded();
		}
		return true;
	}

	/**
	 * A cada tick: na mão, o celular guarda sozinho se outra tela abriu (inventário, chat, pausa) ou se não dá mais para usá-lo
	 * (espectador, dormindo, interface oculta). Expandido, a tela de entrada cuida disso.
	 */
	public void handTick(Minecraft mc) {
		if (!inUse || expanded) {
			return;
		}
		if (mc.gui.screen() != null || !canPull(mc)) {
			onClosed();
		}
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
		PhoneCursor.endFrame();
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
