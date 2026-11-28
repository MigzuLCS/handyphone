package dev.migzu.handyphone.client.os;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import dev.migzu.handyphone.photo.PhotoCodec;

/**
 * O "sistema operacional" do celular: estado, animações e toques. O desenho fica
 * por conta do {@link Theme} escolhido e dos apps ({@link AppContent}). Tudo é
 * Java2D puro, sem nenhuma classe do Minecraft, para ficar estável entre versões
 * do jogo e poder ser testado fora dele.
 *
 * <p>Coordenadas lógicas: a face inteira do aparelho mede {@link #FACE_W} x {@link #FACE_H}.
 */
public final class PhoneOS {
	public static final int FACE_W = 192;
	public static final int FACE_H = 402;
	/** Raio dos cantos da face; pequeno, pois o corpo é um bloco com os cantos só suavizados. */
	public static final float FACE_R = 7f;

	/** Formato da hora (barra de status e tela de bloqueio): vem do idioma, para quem quiser 12 h. */
	static DateTimeFormatter timeFormat() {
		return Lang.format("handyphone.format.time");
	}
	/** Quanto tempo um aviso fica na tela, em segundos. */
	private static final double BANNER_SECONDS = 4.0;
	/** Intervalo mínimo entre tiques de rolagem, para não virar metralhadora. */
	private static final double SCROLL_SOUND_INTERVAL = 0.055;
	/** Velocidade com que a rolagem pendente é entregue (1/s); 18 = 95% em ~170 ms. */
	private static final double SCROLL_RATE = 18;
	/** Abaixo disto (em cliques, ~0,8 ponto) o resto sai de uma vez. */
	private static final float SCROLL_SNAP = 0.02f;

	/** Teclas especiais repassadas pelo jogo quando um campo de texto está em uso. */
	public enum Key { BACKSPACE, ENTER, TAB }

	/** {@code nameKey} é a chave de texto do nome; {@link #name()} o traduz no idioma em uso. */
	public record App(String id, String nameKey, Color c1, Color c2, boolean dock) {
		public String name() {
			return Lang.t(nameKey);
		}
	}

	/** Todos os apps, na ordem da tela inicial; o scroll.in só aparece quando o servidor diz que ele existe ({@link #applyFeedInfo}). */
	private static final List<App> ALL_APPS = List.of(
		new App("map", "handyphone.app.map", new Color(0x4FC3F7), new Color(0x1976D2), false),
		new App("contacts", "handyphone.app.contacts", new Color(0xFFB74D), new Color(0xF4511E), false),
		new App("settings", "handyphone.app.settings", new Color(0xB0BEC5), new Color(0x546E7A), false),
		new App("about", "handyphone.app.about", new Color(0xB388FF), new Color(0x5E35B1), false),
		new App("feed", "handyphone.app.feed", new Color(0xFF7B72), new Color(0xD32F2F), false),
		new App("phone", "handyphone.app.phone", new Color(0x69F0AE), new Color(0x00A152), true),
		new App("messages", "handyphone.app.messages", new Color(0x7CF07A), new Color(0x1FAF38), true),
		new App("camera", "handyphone.app.camera", new Color(0xE0E0E0), new Color(0x8E8E93), true),
		new App("gallery", "handyphone.app.gallery", new Color(0xFF8A80), new Color(0xD81B60), true)
	);

	/** Os apps à vista na tela inicial (o índice de {@link #openApp} é nesta lista). Só muda por {@link #updateApps}. */
	List<App> apps = visibleApps(false);

	private static List<App> visibleApps(boolean feed) {
		return ALL_APPS.stream().filter(a -> feed || !a.id().equals("feed")).toList();
	}

	private final MessagesApp messages = new MessagesApp();
	private final ContactsApp contacts = new ContactsApp();
	private final GalleryApp gallery = new GalleryApp();
	private final FeedApp feed = new FeedApp();
	private final MapApp map = new MapApp();
	private final PhoneApp phone = new PhoneApp();
	/** A tela da ligação (sobre tudo, menos o aviso) e a barra verde de quando ela está minimizada. */
	private final CallScreen callScreen = new CallScreen();
	private boolean pressedCall;
	private long pressedCallId;
	/** Apps que já têm conteúdo de verdade; os outros mostram "Em breve". */
	private final Map<String, AppContent> contents = Map.of(
		"settings", new SettingsApp(), "messages", messages, "contacts", contacts, "camera", new CameraApp(), "gallery", gallery, "map", map,
		"phone", phone, "about", new AboutApp(), "feed", feed
	);

	/** Reduzir e comprimir imagem custa CPU: nunca na thread de desenho nem na do jogo. */
	private static final ExecutorService IMAGE_WORK = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "HandyPhone-ImageWork");
		thread.setDaemon(true);
		return thread;
	});
	/** Uma imagem do computador já reduzida e recodificada, esperando a confirmação do jogador. */
	private record PendingImport(byte[] jpeg, BufferedImage preview, String label) {
	}

	/** Fotos em processamento ou na fila de envio que o celular aceita ao mesmo tempo. */
	private static final int MAX_PENDING_PHOTOS = 12;

	/** Estilos disponíveis, na ordem em que aparecem nos Ajustes (do mais antigo ao mais novo). */
	final List<Theme> themes = List.of(new RetroTheme(), new AeroTheme(), new FlatTheme(), new ModernTheme());
	/** Tema de quem nunca escolheu um: o Minimalista (decisão do Migzu, 2026-11-19; antes era o Clássico). */
	Theme theme = themes.get(2);
	static final String DEFAULT_THEME = "flat";
	private Consumer<String> themeListener = id -> { };
	private Runnable settingsListener = () -> { };
	private Consumer<PhoneSound> soundListener = s -> { };
	/** Opções do app Ajustes (ver {@link PhoneSettings}); mudam só por {@link #changeSettings}. */
	private final PhoneSettings settings = new PhoneSettings();

	/** Espelho do que o servidor sabe sobre este jogador. */
	final PhoneData data = new PhoneData();
	PhoneBackend backend = PhoneBackend.OFFLINE;

	// Câmera e fotos.
	private PhoneCamera camera = PhoneCamera.NONE;
	private boolean previewWanted;
	private BufferedImage lastPreview;
	/** Fotos sendo reduzidas/codificadas agora. */
	private int photoJobs;
	/** Imagens do computador prontas para enviar, esperando o jogador confirmar (nada sai do computador sem isso). */
	private final java.util.ArrayDeque<PendingImport> pendingImports = new java.util.ArrayDeque<>();
	/** Janela de confirmação aberta sobre a tela, ou null. */
	private ConfirmDialog modal;
	private boolean pressedModal;
	/** Fotos já entregues ao envio e ainda sem resposta do servidor. */
	private int uploadsInFlight;
	/** Momento do último disparo, para a animação do visor. */
	double flashAt = -10;
	/** O quadro do visor no disparo (voa até a miniatura da galeria e fica nela até a foto chegar do servidor), ou null. */
	BufferedImage shotFrame;
	/** A primeira foto da galeria no momento do disparo: quando ela muda, a foto nova chegou e a miniatura de verdade assume. */
	long shotGalleryTop = -1;
	/** Duração da animação do disparo no visor (cortina + voo até a miniatura), em segundos. */
	static final double SHOT_ANIMATION = 0.55;

	/** Câmera (1.3): zoom pedido (1 a {@link #CAMERA_MAX_ZOOM}) e câmera frontal, só nesta sessão do app (voltam ao abrir a Câmera). */
	float cameraZoom = 1f;
	boolean cameraSelfie;
	public static final float CAMERA_MAX_ZOOM = 4f;

	/**
	 * Zoom da Câmera que o jogo deve aplicar ao campo de visão (1 = nenhum). Só vale com o app Câmera aberto e à vista; fora dele, 1.
	 * Lido pela thread do jogo (sincronizar no PhoneOS, como o resto).
	 */
	public float cameraZoom() {
		return "camera".equals(openAppId()) && !cameraSelfie ? cameraZoom : 1f;
	}

	/** A câmera frontal (selfie) está em uso: o jogo põe a vista na frente do jogador. Só com o app Câmera aberto. */
	public boolean cameraSelfie() {
		return cameraSelfie && "camera".equals(openAppId());
	}

	/** 0 = tela de bloqueio, 1 = desbloqueado. */
	final Spring unlock = Spring.smooth(0);
	/** 0 = tela inicial, 1 = app aberto. */
	final Spring appOpen = new Spring(0, 300, 27);
	final Spring[] press = new Spring[ALL_APPS.size()];
	/** Instantâneo para o HUD do jogo (lido sem trava). */
	private volatile PhoneData.HudView hud = PhoneData.HudView.NONE;
	/** Instantâneo da ligação para o áudio e o HUD (lido sem trava). */
	private volatile PhoneData.CallView callView = PhoneData.CallView.NONE;
	private long lastCallSecondShown = -1;
	/** Já avisamos o servidor que estamos olhando posições ao vivo? */
	private boolean watchSent;
	/** Já chegou a primeira lista de quem compartilha (a primeira não vira aviso: pode ser só a reconexão). */
	private boolean liveReceived;
	private long lastLocationRefresh;
	/** Pressão no controle de voltar/início. */
	final Spring homeBarPress = Spring.bouncy(0);
	int openApp = -1;
	String lastClock = "";
	/** O minuto (em relação ao relógio do sistema) em que a hora acima foi lida. */
	private long lastClockMinute = -1;
	double time;

	// Aviso no topo da tela (mensagem nova ou recado do servidor).
	private final Spring banner = Spring.smooth(0);
	private String bannerTitle = "", bannerText = "";
	private long bannerConversation = -1;
	/** O que tocar no aviso do topo faz, quando não é abrir uma conversa (por exemplo, abrir a pasta das fotos salvas). */
	private Runnable bannerAction;
	private String bannerGlyph = "messages";
	private double bannerUntil;
	private boolean pressedBanner;

	private int pressedIcon = -1;
	private boolean pressedHomeBar;
	private boolean pressedPhysicalHome;
	private boolean pressedContent;
	private boolean awake;
	private boolean dirty = true;
	private boolean caretOn;
	private double lastScrollSoundTime = -1;
	/** Rolagem da roda ainda por entregar ao app, em cliques; sai aos poucos em {@link #step} para a lista deslizar. */
	private float pendingScroll;
	/** App que recebe a rolagem pendente; se outro app (ou nenhum) estiver à vista, o resto é descartado. */
	private AppContent pendingScrollTarget;
	/** Um clique da roda chegou e ainda não tocou o "tic" (um por clique, não um por fração entregue). */
	private boolean scrollTickArmed;

	public PhoneOS() {
		for (int i = 0; i < press.length; i++) {
			press[i] = Spring.bouncy(0);
		}
	}

	// ------------------------------------------------------------------ estado

	/** Acordado = jogador levantou o celular para usar. Dormindo = tela de bloqueio. */
	public void setAwake(boolean awake) {
		if (this.awake == awake) {
			return;
		}
		this.awake = awake;
		unlock.target = awake ? 1 : 0;
		playSound(awake ? PhoneSound.SCREEN_ON : PhoneSound.SCREEN_OFF);
		if (!awake) {
			releasePointer();
		} else if (content() != null) {
			content().resumed(this);
		}
		dirty = true;
	}

	public boolean isAppOpen() {
		return openApp >= 0 && appOpen.target > 0.5;
	}

	/** Id do app aberto, ou null na tela inicial. */
	public String openAppId() {
		return isAppOpen() ? apps.get(openApp).id() : null;
	}

	/** Conteúdo do app aberto, ou null (tela inicial ou app ainda sem conteúdo). */
	AppContent content() {
		return isAppOpen() ? contents.get(apps.get(openApp).id()) : null;
	}

	/** Botão "voltar"/início. Retorna false se já estava na tela inicial. */
	public boolean back() {
		focusBack = true;
		if (modal != null) {
			cancelModal();
			playSound(PhoneSound.APP_CLOSE);
			return true;
		}
		if (callScreenVisible()) {
			// A ligação não se desfaz ao voltar: a tela é minimizada (ou, encerrada, dispensada). Quem está tocando não minimiza.
			int state = data.call.state;
			if (state == PhoneData.CALL_INCOMING) {
				return false;
			}
			if (state == PhoneData.CALL_ENDED) {
				dismissCall();
			} else {
				data.call.minimized = true;
				dirty = true;
			}
			playSound(PhoneSound.APP_CLOSE);
			return true;
		}
		if (!isAppOpen()) {
			return false;
		}
		dirty = true;
		AppContent content = content();
		if (content == null || !content.back(this)) {
			appOpen.target = 0;
		}
		playSound(PhoneSound.APP_CLOSE);
		return true;
	}

	/** Abre um app direto, sem a animação de abertura (usado para ir de um app a outro). */
	void launch(String appId) {
		for (int i = 0; i < apps.size(); i++) {
			if (apps.get(i).id().equals(appId)) {
				boolean changing = !isAppOpen() || openApp != i;
				boolean switching = isAppOpen();
				openApp = i;
				appOpen.target = 1;
				if (switching) {
					appOpen.snap(1);
				}
				AppContent content = contents.get(appId);
				// Destaque da roda: o app recomeça do jeito de sempre e a tela inicial volta para o ícone dele.
				homeFocus = "icon:" + i;
				focusTrail.removeIf(e -> e.getKey() == content);
				if (content != null) {
					content.opened(this);
				}
				if (changing) {
					playSound(PhoneSound.APP_OPEN);
				}
				dirty = true;
			}
		}
	}

	/** Vai para a conversa individual com um número (o servidor cria se ainda não existir). */
	void openChatWith(String number) {
		launch("messages");
		backend.openDirect(number);
	}

	/** Abre o formulário de contato; {@code existing} null = contato novo com o número preenchido. */
	void editContact(String existing, String prefillNumber) {
		launch("contacts");
		contacts.edit(this, existing, prefillNumber);
	}

	public String themeId() {
		return theme.id();
	}

	/** De qual tema vêm os sons: o escolhido em Ajustes > Sons, ou o tema em uso (automático). */
	public String soundThemeId() {
		String id = settings.soundTheme();
		return id.isEmpty() ? theme.id() : id;
	}

	/** Troca o estilo da interface. Ids desconhecidos são ignorados. */
	public void setTheme(String id) {
		if (switchTheme(id)) {
			settings.setTheme(id);
			playSound(PhoneSound.THEME_CHANGE);
			themeListener.accept(id);
			settingsListener.run();
		}
	}

	private boolean switchTheme(String id) {
		for (Theme candidate : themes) {
			if (candidate.id().equals(id) && candidate != theme) {
				theme.releaseCaches();
				theme = candidate;
				dirty = true;
				return true;
			}
		}
		return false;
	}

	/** Cor do destaque da roda: a escolhida nos Ajustes, ou um tom diferente da cor de destaque dos botões (automática). */
	int highlightColor() {
		int chosen = settings.highlightColor();
		if (chosen >= 0) {
			return chosen;
		}
		int base = accentOr(theme.accent()).getRGB() & 0xFFFFFF;
		float[] hsb = Color.RGBtoHSB((base >> 16) & 0xFF, (base >> 8) & 0xFF, base & 0xFF, null);
		// Branco, preto e cinzas não têm cor para dar um tom: virariam um cinza que parece contorno. Usa a cor do tema.
		if (hsb[1] < 0.18f) {
			base = theme.accent().getRGB() & 0xFFFFFF;
		}
		return highlightTone(base);
	}

	/**
	 * Um tom diferente da mesma cor, para o destaque não sumir em cima de um botão da cor de destaque: cor escura ou média fica
	 * mais clara e menos saturada (um tom pastel); cor já clara fica mais escura.
	 */
	static int highlightTone(int rgb) {
		float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
		float luma = (0.2126f * ((rgb >> 16) & 0xFF) + 0.7152f * ((rgb >> 8) & 0xFF) + 0.0722f * (rgb & 0xFF)) / 255f;
		float s, b;
		if (luma < 0.62f) {
			s = hsb[1] * 0.55f;
			b = Math.min(1f, hsb[2] + 0.38f);
		} else {
			s = Math.min(1f, hsb[1] * 1.1f);
			b = hsb[2] * 0.58f;
		}
		return Color.HSBtoRGB(hsb[0], s, b) & 0xFFFFFF;
	}

	/** A cor de destaque escolhida nos Ajustes, ou {@code themeColor} se o jogador deixou na automática. */
	Color accentOr(Color themeColor) {
		int rgb = settings.accent();
		if (rgb < 0) {
			return themeColor;
		}
		if (accentColor == null || (accentColor.getRGB() & 0xFFFFFF) != rgb) {
			accentColor = new Color(rgb);
		}
		return accentColor;
	}

	private Color accentColor;
	private ShortcutKeys shortcutKeys = ShortcutKeys.NONE;
	private volatile String modVersion = "";

	/** Área de transferência do computador (no jogo, a do Minecraft); sem jogo, nada. */
	private volatile java.util.function.Consumer<String> clipboard = text -> {
	};

	public void setClipboard(java.util.function.Consumer<String> clipboard) {
		this.clipboard = clipboard == null ? text -> {
		} : clipboard;
	}

	/** Abre um endereço no navegador do computador (no jogo, pelo Minecraft); sem jogo, nada. */
	private volatile java.util.function.Consumer<String> linkOpener = url -> {
	};

	public void setLinkOpener(java.util.function.Consumer<String> opener) {
		this.linkOpener = opener == null ? url -> {
		} : opener;
	}

	/** Só para endereços fixos do próprio celular (o app Sobre), nunca texto vindo de mensagens ou do servidor. */
	void openLink(String url) {
		linkOpener.accept(url);
	}

	/** Copia um texto para a área de transferência do computador (para colar no chat do jogo ou no celular de outro jogador). */
	void copyText(String text) {
		clipboard.accept(text);
	}

	public void setShortcutKeys(ShortcutKeys keys) {
		shortcutKeys = keys == null ? ShortcutKeys.NONE : keys;
	}

	ShortcutKeys shortcutKeys() {
		return shortcutKeys;
	}

	/** Versão do mod, mostrada em Ajustes > Sobre. */
	public void setModVersion(String version) {
		modVersion = version == null ? "" : version;
	}

	String modVersion() {
		return modVersion;
	}

	/** Pede um redesenho (a ponte da tecla avisa quando a captura termina). */
	public void markDirty() {
		dirty = true;
	}

	/** As opções atuais (só leitura fora deste pacote: os setters do {@link PhoneSettings} não são públicos). */
	public PhoneSettings settings() {
		return settings;
	}

	/**
	 * Aplica opções lidas do disco, sem som e sem avisar quem salva. Um tema desconhecido fica no padrão; o resto é corrigido
	 * para a faixa válida.
	 */
	public void applySettings(PhoneSettings loaded) {
		PhoneSettings s = loaded.copy().normalize();
		if (!switchTheme(s.theme()) && !theme.id().equals(s.theme())) {
			s.setTheme(theme.id());
		}
		settings.setTheme(s.theme());
		settings.setAccent(s.accent());
		settings.setHighlight(s.highlight());
		settings.setHighlightColor(s.highlightColor());
		settings.setCursorStyle(s.cursorStyle());
		settings.setScrollSpeed(s.scrollSpeed());
		settings.setHandSway(s.handSway());
		settings.setControlsHint(s.controlsHint());
		settings.setUiSounds(s.uiSounds());
		settings.setKeyboardSounds(s.keyboardSounds());
		settings.setVolume(s.volume());
		settings.setSoundTheme(themes.stream().anyMatch(t -> t.id().equals(s.soundTheme())) ? s.soundTheme() : "");
		settings.setNotifyBanner(s.notifyBanner());
		settings.setNotifySound(s.notifySound());
		dirty = true;
	}

	/** Muda uma opção; se mudou de fato, redesenha e avisa quem salva. Devolve se mudou. */
	boolean changeSettings(java.util.function.Predicate<PhoneSettings> change) {
		if (!change.test(settings)) {
			return false;
		}
		dirty = true;
		settingsListener.run();
		return true;
	}

	/** Avisado quando o jogador troca o estilo, para a escolha ser salva. */
	public void setThemeListener(Consumer<String> listener) {
		themeListener = listener;
	}

	/** Avisado quando qualquer ajuste do celular (estilo ou sons) muda. */
	public void setSettingsListener(Runnable listener) {
		settingsListener = listener;
	}

	/** Ouvinte chamado quando o celular precisa tocar um efeito sonoro. */
	public void setSoundListener(Consumer<PhoneSound> listener) {
		soundListener = listener;
	}

	public boolean uiSounds() {
		return settings.uiSounds();
	}

	/** Ajustes > Privacidade > Mostrar o celular aos outros. */
	public void setShowToOthers(boolean show) {
		changeSettings(s -> s.setShowToOthers(show));
	}

	public void setUiSounds(boolean enabled) {
		changeSettings(s -> s.setUiSounds(enabled));
	}

	public boolean keyboardSounds() {
		return settings.keyboardSounds();
	}

	public void setKeyboardSounds(boolean enabled) {
		changeSettings(s -> s.setKeyboardSounds(enabled));
	}

	void playSound(PhoneSound sound) {
		if (sound == null) {
			return;
		}
		if (sound.group() == PhoneSound.Group.KEYBOARD && !settings.keyboardSounds()) {
			return;
		}
		if ((sound == PhoneSound.NOTIFICATION || sound == PhoneSound.NOTIFICATION_GROUP) && !settings.notifySound()) {
			return;
		}
		if ((sound.group() == PhoneSound.Group.UI || sound.group() == PhoneSound.Group.NOTIFICATION) && !settings.uiSounds()) {
			return;
		}
		soundListener.accept(sound);
	}

	void playScrollTick() {
		if (!scrollTickArmed) {
			return;
		}
		if (time - lastScrollSoundTime >= SCROLL_SOUND_INTERVAL) {
			scrollTickArmed = false;
			lastScrollSoundTime = time;
			playSound(PhoneSound.SCROLL_TICK);
		}
	}

	/** Centro do ícone de um app na tela inicial, em coordenadas da face. */
	public float[] iconCenter(String appId) {
		for (int i = 0; i < apps.size(); i++) {
			if (apps.get(i).id().equals(appId)) {
				return theme.iconCenter(this, i);
			}
		}
		throw new IllegalArgumentException("Unknown app: " + appId);
	}

	/** Centro de uma área clicável do app aberto (pela chave, do último desenho), em coordenadas da face; null se não existe. Para os testes. */
	public float[] areaCenter(String key) {
		AppContent c = content();
		float[] r = c == null ? null : c.focusTargets().get(key);
		return r == null ? null : new float[] {r[0] + r[2] / 2f, r[1] + r[3] / 2f};
	}

	public void step(double dt) {
		time += dt;
		stepScroll(dt);
		unlock.step(dt);
		appOpen.step(dt);
		homeBarPress.step(dt);
		banner.step(dt);
		for (Spring s : press) {
			s.step(dt);
		}
		if (openApp >= 0 && appOpen.target == 0 && appOpen.value < 0.002) {
			openApp = -1;
			appOpen.snap(0);
			// Um último desenho sem o app: a mola já parou, e a tela ficaria presa no quadro do fim da animação (o app quase
			// transparente por cima da tela inicial, o "fantasma" visto ao voltar do Mapa).
			dirty = true;
		}
		if (banner.target > 0 && time > bannerUntil) {
			banner.target = 0;
		}
		// O visor só captura enquanto o app Câmera está à vista; um quadro novo pede redesenho.
		boolean wantPreview = awake && "camera".equals(openAppId());
		if (wantPreview != previewWanted) {
			previewWanted = wantPreview;
			camera.setPreviewActive(wantPreview);
			lastPreview = null;
			dirty = true;
		}
		if (previewWanted) {
			BufferedImage frame = camera.preview();
			if (frame != lastPreview) {
				lastPreview = frame;
				dirty = true;
			}
		}
		if (time - flashAt < SHOT_ANIMATION + 0.05) {
			dirty = true;
		}
		stepCountdown();
		stepExport();
		expireCall();
		PhoneData.Call call = data.call;
		if (awake && call.state != PhoneData.CALL_NONE) {
			// O cronômetro (a cada segundo) e as ondas do toque (a cada quadro enquanto toca e a tela está à vista).
			long second = PhoneClock.millis() / 1000;
			boolean animated = !call.minimized && (call.state == PhoneData.CALL_INCOMING || call.state == PhoneData.CALL_OUTGOING);
			if (second != lastCallSecondShown || animated) {
				lastCallSecondShown = second;
				dirty = true;
			}
		}
		// Tiles a caminho (ou pedidos que ficaram de fora) e a contagem do compartilhamento pedem redesenho do Mapa.
		if ("map".equals(openAppId()) && awake) {
			long nowMs = System.currentTimeMillis();
			// Com o Mapa à vista, redesenha ao menos a cada 2 s: é o desenho que reconfere os tiles (o squaremap segue desenhando).
			if (data.tiles.working() && nowMs - lastLocationRefresh > 400 || data.share.active && nowMs / 1000 != lastLocationRefresh / 1000
				|| nowMs - lastLocationRefresh > 2000) {
				lastLocationRefresh = nowMs;
				dirty = true;
			}
		}
		// A hora na barra de status só muda a cada minuto: o texto só é refeito quando o minuto muda (antes, a cada passo desta thread).
		long minute = System.currentTimeMillis() / 60_000L;
		if (minute != lastClockMinute) {
			lastClockMinute = minute;
			String clock = LocalDateTime.now().format(timeFormat());
			if (!clock.equals(lastClock)) {
				lastClock = clock;
				dirty = true;
			}
		}
		// O cursor de texto pisca: redesenha quando ele muda.
		boolean caret = wantsKeyboard() && time % 1.0 < 0.55;
		if (caret != caretOn) {
			caretOn = caret;
			dirty = true;
		}
	}

	public boolean isAnimating() {
		if (pendingScroll != 0f) {
			return true;
		}
		if (!unlock.isResting() || !appOpen.isResting() || !homeBarPress.isResting() || !banner.isResting()) {
			return true;
		}
		for (Spring s : press) {
			if (!s.isResting()) {
				return true;
			}
		}
		return false;
	}

	/** Verdadeiro se a imagem precisa ser redesenhada; zera a marca de "sujo". */
	public boolean consumeDirty() {
		boolean d = dirty || isAnimating();
		dirty = false;
		return d;
	}

	// ------------------------------------------------------------------ dados vindos do servidor

	public void setBackend(PhoneBackend backend) {
		this.backend = backend;
	}

	/** Esquece tudo (ao sair do servidor). */
	public void clearData() {
		data.number = "";
		data.contacts.clear();
		data.conversations.clear();
		data.blocked.clear();
		data.gallery.clear();
		data.photoUsedBytes = 0;
		data.photoQuotaBytes = 0;
		data.importAllowed = true;
		data.photos.clear();
		data.map = new PhoneData.MapInfo();
		data.places.clear();
		data.live.clear();
		data.share = new PhoneData.Share();
		data.self.known = false;
		data.tiles.reset(0);
		data.route = null;
		publishHud();
		data.callInfoReceived = false;
		data.callsAvailable = false;
		data.call.clear();
		data.callLog.clear();
		publishCall();
		watchSent = false;
		liveReceived = false;
		uploadsInFlight = 0;
		pendingImports.clear();
		modal = null;
		pressedModal = false;
		backend = PhoneBackend.OFFLINE;
		localOnly = false;
		serverBackend = null;
		localFullWarned = false;
		localIds.clear();
		localNames.clear();
		// Os ids de foto são de cada servidor: as informações de um não podem aparecer em outro.
		data.photoInfo.clear();
		infoAsked.clear();
		clearFeed();
		dirty = true;
	}

	/**
	 * Aplica o estado completo mandado pelo servidor. As mensagens já baixadas de
	 * cada conversa são mantidas.
	 */
	public void applySync(String number, Map<String, String> contactNames, List<PhoneData.Conversation> list) {
		data.number = number;
		data.contacts.clear();
		data.contacts.putAll(contactNames);
		for (PhoneData.Conversation fresh : list) {
			PhoneData.Conversation old = data.conversation(fresh.id);
			if (old != null) {
				fresh.historyLoaded = old.historyLoaded;
				fresh.messages.addAll(old.messages);
			}
			if (awake && isShowingChat(fresh.id)) {
				fresh.unread = 0;
			}
		}
		data.conversations.clear();
		data.conversations.addAll(list);
		dirty = true;
	}

	/**
	 * Troca (ou acrescenta) uma só conversa da lista, como o servidor manda a cada mensagem nova. As mensagens que o
	 * celular já tinha dela são mantidas.
	 */
	public void applyConversation(PhoneData.Conversation fresh) {
		PhoneData.Conversation old = data.conversation(fresh.id);
		if (old != null) {
			fresh.historyLoaded = old.historyLoaded;
			fresh.messages.addAll(old.messages);
			data.conversations.remove(old);
		}
		if (awake && isShowingChat(fresh.id)) {
			fresh.unread = 0;
		}
		data.conversations.add(fresh);
		// Um servidor hostil não pode fazer a lista crescer sem teto com conversas "novas" (o Sync honesto traz no máximo 400).
		while (data.conversations.size() > PhoneData.MAX_CONVERSATIONS) {
			data.conversations.sort(java.util.Comparator.comparingLong((PhoneData.Conversation c) -> c.lastTime).reversed());
			data.conversations.removeLast();
		}
		data.conversations.sort(java.util.Comparator.comparingLong((PhoneData.Conversation c) -> c.lastTime).reversed()
			.thenComparing(java.util.Comparator.comparingLong((PhoneData.Conversation c) -> c.id).reversed()));
		dirty = true;
	}

	/** Quantas conversas o celular mostra (para os testes). */
	public int conversationCount() {
		return data.conversations.size();
	}

	/** O {@code have} que o celular mandaria ao abrir a conversa (para os testes). */
	public int haveFor(long conversation) {
		PhoneData.Conversation c = data.conversation(conversation);
		return c == null ? -1 : data.haveSeq(c);
	}

	/** O id da primeira conversa da lista, ou -1 (para os testes). */
	public long firstConversationId() {
		return data.conversations.isEmpty() ? -1 : data.conversations.getFirst().id;
	}

	/** Quantas mensagens o celular guarda de uma conversa (para os testes). */
	public int messageCount(long conversation) {
		PhoneData.Conversation c = data.conversation(conversation);
		return c == null ? -1 : c.messages.size();
	}

	/** Quantos números o celular sabe que estão bloqueados (para os testes). */
	public int blockedCount() {
		return data.blocked.size();
	}

	/** Os números bloqueados (a lista inteira). */
	public void applyBlocked(List<String> numbers) {
		data.blocked.clear();
		data.blocked.addAll(numbers);
		dirty = true;
	}

	/**
	 * Aplica mensagens de uma conversa.
	 *
	 * @param history true = histórico completo, substitui o que havia; false = mensagens novas
	 * @param focus   true = mostrar essa conversa na tela
	 * @return true se chegou mensagem nova de outra pessoa sem a conversa estar à vista (vale um toque de aviso)
	 */
	public boolean applyMessages(long conversation, boolean history, boolean focus, List<PhoneData.Message> incoming) {
		PhoneData.Conversation c = data.conversation(conversation);
		if (c == null) {
			return false;
		}
		dirty = true;
		if (history) {
			c.messages.clear();
			c.historyLoaded = true;
		}
		PhoneData.Message newest = null;
		for (PhoneData.Message m : incoming) {
			if (c.messages.isEmpty() || m.seq() > c.messages.getLast().seq()) {
				c.messages.add(m);
				newest = m;
			}
		}
		// Um servidor hostil (o cliente entra em qualquer servidor) não pode fazer a lista crescer sem teto: guarda só as mais recentes.
		if (c.messages.size() > PhoneData.MAX_MESSAGES_PER_CONVERSATION) {
			c.messages.subList(0, c.messages.size() - PhoneData.MAX_MESSAGES_PER_CONVERSATION).clear();
		}
		if (focus) {
			if (!"messages".equals(openAppId())) {
				launch("messages");
			}
			messages.showChat(conversation);
		}
		if (history || newest == null || newest.sender().equals(data.number)) {
			return false;
		}
		if (newest.system()) {
			// "Fulano saiu do grupo": aparece na conversa, sem aviso nem som.
			if (awake && isShowingChat(conversation)) {
				c.unread = 0;
				backend.markRead(conversation, newest.seq());
			}
			return false;
		}
		if (awake && isShowingChat(conversation)) {
			c.unread = 0;
			backend.markRead(conversation, newest.seq());
			playSound(PhoneSound.MESSAGE_RECEIVED);
			return false;
		}
		notify(data.titleOf(c), (c.group ? data.displayName(newest.sender()) + ": " : "") + newest.preview(), conversation);
		return true;
	}

	private boolean isShowingChat(long conversation) {
		return "messages".equals(openAppId()) && messages.isShowing(conversation);
	}

	/** Mostra um aviso no topo da tela. {@code conversation} >= 0 faz o toque no aviso abrir a conversa. */
	public void notify(String title, String text, long conversation) {
		if (conversation < 0 || settings.notifyBanner()) {
			showBanner(title, text, conversation, "messages");
		}
		if (conversation < 0) {
			playSound(PhoneSound.ERROR);
		} else {
			PhoneData.Conversation c = data.conversation(conversation);
			playSound(c != null && c.group ? PhoneSound.NOTIFICATION_GROUP : PhoneSound.NOTIFICATION);
		}
	}

	/** Aviso de notificação (chamada perdida, localização ao vivo): some se o jogador desligou o aviso no topo. */
	private void notificationBanner(String title, String text, String glyph) {
		if (settings.notifyBanner()) {
			showBanner(title, text, -1, glyph);
		}
	}

	private void showBanner(String title, String text, long conversation, String glyph) {
		bannerAction = null;
		bannerTitle = title;
		bannerText = text;
		bannerConversation = conversation;
		bannerGlyph = glyph;
		bannerUntil = time + BANNER_SECONDS;
		banner.target = 1;
		dirty = true;
	}

	/** Aviso de foto: recusa (com o som de erro) ou confirmação. */
	private void photoNotice(String text, boolean error) {
		showBanner(Lang.t("handyphone.banner.photos"), text, -1, "gallery");
		playSound(error ? PhoneSound.ERROR : PhoneSound.CONTACT_SAVED);
	}

	/** Quantas mensagens não lidas o jogador tem, somando todas as conversas. */
	public int unreadTotal() {
		return data.unreadTotal();
	}

	/** Quantas fotos o servidor diz que há na galeria. */
	public int galleryCount() {
		return data.gallery.size();
	}

	/** Miniaturas e fotos inteiras que estão no cache de memória agora. */
	public int cachedThumbs() {
		return data.photos.thumbCount();
	}

	public int cachedFullPhotos() {
		return data.photos.fullCount();
	}

	/** O visor da câmera já recebeu algum quadro (para os testes). */
	public boolean cameraPreviewReady() {
		return camera.preview() != null;
	}

	/** Texto do último aviso mostrado no topo da tela (para os testes). */
	public String lastBannerText() {
		return bannerText;
	}

	/** Há foto sendo reduzida, enviada ou esperando resposta do servidor. */
	public boolean photosBusy() {
		return photoJobs > 0 || uploadsInFlight > 0;
	}

	/** Número de telefone do jogador (só dígitos), ou vazio se o servidor ainda não respondeu. */
	public String number() {
		return data.number;
	}

	// ------------------------------------------------------------------ localização

	/** Raio, em blocos, dentro do qual a rota termina sozinha. */
	private static final double ARRIVE_BLOCKS = 6;

	/** Aviso do Mapa no topo da tela, com o mesmo estilo dos outros. */
	void mapNotice(String text, boolean error) {
		showBanner(Lang.t("handyphone.app.map"), text, -1, "map");
		playSound(error ? PhoneSound.ERROR : PhoneSound.NOTIFICATION);
	}

	/** O servidor disse o que oferece de mapa. Trocar de dimensão joga fora os tiles da anterior. */
	public void applyMapInfo(boolean installed, boolean rendered, int maxZoom, String dimension, int epoch, boolean liveEnabled, boolean messagesEnabled, int maxPlaces) {
		PhoneData.MapInfo info = data.map;
		info.received = true;
		info.installed = installed;
		info.rendered = rendered;
		info.maxZoom = maxZoom;
		info.dimension = dimension;
		info.liveEnabled = liveEnabled;
		info.messagesEnabled = messagesEnabled;
		info.maxPlaces = maxPlaces;
		if (epoch != data.tiles.epoch()) {
			data.tiles.reset(epoch);
		}
		dirty = true;
	}

	/** O servidor disse que há mapa desenhado para a dimensão atual (último MapInfo); para os testes. */
	public boolean mapRendered() {
		return data.map.rendered;
	}

	public void applyTile(int epoch, int zoom, int x, int z, BufferedImage image, long stamp) {
		data.tiles.put(epoch, new TileCache.Key(zoom, x, z), image, stamp, time);
		dirty = true;
	}

	/** O servidor confirmou que a versão do tile que o celular tem é a atual. */
	public void applyTileUnchanged(int epoch, int zoom, int x, int z) {
		data.tiles.unchanged(epoch, new TileCache.Key(zoom, x, z), time);
	}

	/** Unidades da tela por bloco no zoom atual do Mapa (para os testes). */
	public float mapScale() {
		return map.scale();
	}

	/** Carimbo da versão em memória de um tile, ou -1 (para os testes). */
	public long tileStamp(int zoom, int x, int z) {
		return data.tiles.stampOf(new TileCache.Key(zoom, x, z));
	}

	public void applyTileFailed(int epoch, int zoom, int x, int z, boolean busy) {
		data.tiles.failed(epoch, new TileCache.Key(zoom, x, z), busy, time);
		dirty = true;
	}

	public void applyPlaces(List<PhoneData.Place> places) {
		data.places.clear();
		data.places.addAll(places);
		dirty = true;
	}

	public void applyShareStatus(boolean active, int secondsLeft, List<String> audience) {
		boolean was = data.share.active;
		data.share.active = active;
		data.share.until = System.currentTimeMillis() + secondsLeft * 1000L;
		data.share.audience = List.copyOf(audience);
		if (was && !active) {
			mapNotice(Lang.t("handyphone.map.share_stopped"), false);
		}
		publishHud();
		dirty = true;
	}

	/** Substitui a lista de quem compartilha com o jogador. Avisa de quem começou agora e acompanha a rota até um contato. */
	public void applyLive(List<PhoneData.Live> entries) {
		if (liveReceived) {
			for (PhoneData.Live fresh : entries) {
				if (data.liveOf(fresh.number()) == null) {
					notificationBanner(data.displayName(fresh.number()), Lang.t("handyphone.banner.live_started"), "map");
					playSound(PhoneSound.NOTIFICATION);
				}
			}
		}
		liveReceived = true;
		data.live.clear();
		data.live.addAll(entries);
		PhoneData.Route route = data.route;
		if (route != null && route.contact != null) {
			PhoneData.Live l = data.liveOf(route.contact);
			if (l == null) {
				mapNotice(Lang.t("handyphone.map.contact_stopped", data.displayName(route.contact)), false);
				data.route = null;
			} else if (l.state() == PhoneData.LIVE_POSITION) {
				route.dimension = l.dimension();
				route.x = l.x();
				route.z = l.z();
				route.known = true;
			} else if (l.state() != PhoneData.LIVE_IDLE) {
				route.dimension = l.dimension();
				route.known = false;
			}
		}
		publishHud();
		dirty = true;
	}

	/**
	 * Onde o jogador está, direto do jogo (serve só para desenhar a própria seta, o aviso de chegada e as
	 * distâncias). Chamado a cada tick do cliente, com o celular à vista ou não.
	 */
	public void setSelf(String dimension, double x, double y, double z, float yaw, String biome) {
		PhoneData.SelfPos s = data.self;
		PhoneData.Fix old = s.fix;
		// Parado e sem olhar em volta, nada muda: o Mapa não precisa ser redesenhado (antes redesenhava ~4 vezes por segundo à toa).
		boolean changed = old == null || !s.known || old.x() != x || old.y() != y || old.z() != z || old.yaw() != yaw || !old.dimension().equals(dimension) || !biome.equals(s.biome);
		if (!changed) {
			return;
		}
		s.dimension = dimension;
		s.x = x;
		s.y = y;
		s.z = z;
		s.yaw = yaw;
		s.biome = biome;
		s.known = true;
		s.fix = new PhoneData.Fix(dimension, x, y, z, yaw);
		moved = true;
	}

	/** Mudou algo na posição desde a última checagem? (escrito sem trava pelo tick; só serve para redesenhar) */
	private volatile boolean moved;

	/**
	 * A parte da posição que mexe no estado do celular (redesenho, fim da rota ao chegar, aviso de "olhando ao
	 * vivo" ao servidor). Chamado com a trava presa, umas poucas vezes por segundo.
	 */
	public void locationTick() {
		expireCall();
		data.tiles.releaseIfIdle(System.currentTimeMillis());
		PhoneData.SelfPos s = data.self;
		if (moved && awake && "map".equals(openAppId())) {
			dirty = true;
		}
		moved = false;
		PhoneData.Route route = data.route;
		PhoneData.Fix fix = s.fix;
		if (route != null && route.known && fix != null && route.dimension.equals(fix.dimension()) && Math.hypot(route.x + 0.5 - fix.x(), route.z + 0.5 - fix.z()) < ARRIVE_BLOCKS) {
			data.route = null;
			publishHud();
			mapNotice(Lang.t("handyphone.map.arrived"), false);
		}
		boolean watch = (awake && "map".equals(openAppId()) && data.map.liveEnabled) || (data.route != null && data.route.contact != null);
		if (watch != watchSent) {
			watchSent = watch;
			backend.watch(watch);
		}
	}

	/** Abre o Mapa já mostrando um ponto (de uma mensagem, de um local salvo ou de quem compartilha). */
	void openMapAt(String label, String dimension, int x, boolean hasY, int y, int z, String contact) {
		launch("map");
		map.showPoint(this, label, dimension, x, hasY, y, z, contact);
	}

	/** Abre o Mapa para escolher um ponto e mandá-lo a essa conversa. */
	void pickPointFor(long conversation) {
		launch("map");
		map.startPick(conversation);
	}

	/** Começa a rota ("Ir até aqui"). Para um contato, a posição acompanha o que ele compartilha. */
	public void startRoute(String label, String dimension, int x, int z, String contact) {
		PhoneData.Route route = new PhoneData.Route();
		route.label = label;
		route.dimension = dimension;
		route.x = x;
		route.z = z;
		route.contact = contact;
		data.route = route;
		publishHud();
		dirty = true;
		playSound(PhoneSound.CONTACT_SAVED);
	}

	public void cancelRoute() {
		data.route = null;
		publishHud();
		dirty = true;
		playSound(PhoneSound.CONTACT_REMOVED);
	}

	/** Reconstrói o instantâneo do HUD (rota e compartilhamento); chamar com a trava presa depois de mudar um deles. */
	private void publishHud() {
		PhoneData.Route r = data.route;
		hud = new PhoneData.HudView(
			data.share.active, data.share.until, r != null, r != null ? r.label : "", r != null ? r.dimension : "",
			r != null ? r.x : 0, r != null ? r.z : 0, r != null && r.known
		);
	}

	/** O que o HUD do jogo mostra; pode ser lido a qualquer momento, sem a trava (ela fica presa durante o desenho do celular). */
	public PhoneData.HudView hud() {
		return hud;
	}

	/** A posição do jogador (campos {@code volatile}; pode ser lida sem a trava). */
	public PhoneData.SelfPos self() {
		return data.self;
	}

	/** Há uma rota ativa (para os testes; chamar com a trava presa). */
	public boolean routeActive() {
		return data.route != null;
	}

	/** O compartilhamento do próprio jogador está ligado (para os testes). */
	public boolean sharing() {
		return data.share.active;
	}

	/** Ids das conversas que o servidor mostrou (para os testes). */
	public List<Long> conversationIds() {
		return data.conversations.stream().map(c -> c.id).toList();
	}

	/** Quantas pessoas compartilham a localização com o jogador agora (para os testes). */
	public int liveCount() {
		return data.live.size();
	}

	/** Última posição {x, y, z} recebida de quem compartilha (null se só se sabe que compartilha, sem posição; para os testes). */
	public int[] livePosition(String number) {
		PhoneData.Live l = data.liveOf(number);
		return l != null && l.state() == PhoneData.LIVE_POSITION ? new int[] {l.x(), l.y(), l.z()} : null;
	}

	/** O estado da entrada de quem compartilha (um dos LIVE_*), ou -1 (para os testes). */
	public int liveState(String number) {
		PhoneData.Live l = data.liveOf(number);
		return l != null ? l.state() : -1;
	}

	/** Última localização anexada a uma mensagem de uma conversa, como {x, z, carimbada(1/0)}, ou null (para os testes). */
	public int[] lastMessageLocation(long conversation) {
		PhoneData.Conversation c = data.conversation(conversation);
		if (c == null || c.messages.isEmpty() || c.messages.getLast().loc() == null) {
			return null;
		}
		PhoneData.Loc loc = c.messages.getLast().loc();
		return new int[] {loc.x(), loc.z(), loc.stamped() ? 1 : 0};
	}

	/** Situação do mapa: "sem-servidor", "sem-squaremap", "sem-mapa" (ainda não desenhado) ou "ok" (para os testes). */
	public String mapStatus() {
		PhoneData.MapInfo info = data.map;
		if (!info.received) {
			return "sem-servidor";
		}
		return !info.installed ? "sem-squaremap" : !info.rendered ? "sem-mapa" : "ok";
	}

	/** Época do mapa que o celular conhece (para os testes). */
	public int mapEpoch() {
		return data.tiles.epoch();
	}

	/** Cores (índices da paleta) dos locais salvos, na ordem da lista (para os testes). */
	public List<Integer> placeColors() {
		return data.places.stream().map(PhoneData.Place::color).toList();
	}

	/** Nomes dos locais salvos, na ordem da lista (para os testes). */
	public List<String> placeNames() {
		return data.places.stream().map(PhoneData.Place::name).toList();
	}

	/** Rótulo da última localização anexada a uma mensagem da conversa, ou null (para os testes). */
	public String lastMessageLocationLabel(long conversation) {
		PhoneData.Conversation c = data.conversation(conversation);
		if (c == null || c.messages.isEmpty() || c.messages.getLast().loc() == null) {
			return null;
		}
		return c.messages.getLast().loc().label();
	}

	/** Quantos locais salvos o celular mostra (para os testes). */
	public int placeCount() {
		return data.places.size();
	}

	/** Tiles em memória agora (para os testes). */
	public int cachedTiles() {
		return data.tiles.count();
	}

	// ------------------------------------------------------------------ câmera, galeria e fotos

	public void setCamera(PhoneCamera camera) {
		this.camera = camera;
	}

	/** Último quadro do visor, ou null. */
	BufferedImage cameraPreview() {
		return camera.preview();
	}

	/**
	 * Confere o que não depende da imagem antes de aceitar uma foto nova; avisa o jogador se não dá.
	 *
	 * @param pendingLimit quantas fotos podem estar em andamento ao mesmo tempo
	 */
	private boolean canAddPhoto(boolean fromComputer, int pendingLimit) {
		if (localOnly) {
			// Sem o mod no servidor: só fotos da câmera, gravadas na pasta deste computador.
			if (fromComputer) {
				photoNotice(Lang.t("handyphone.photo.no_import"), true);
				return false;
			}
			if (localPhotos.root() == null) {
				photoNotice(Lang.t("handyphone.no_signal"), true);
				return false;
			}
			if (localPhotos.full()) {
				photoNotice(Lang.t("handyphone.photo.local_full", String.valueOf(settings.localPhotosMb())), true);
				return false;
			}
			if (photoJobs >= pendingLimit) {
				photoNotice(Lang.t("handyphone.photo.wait"), true);
				return false;
			}
			return true;
		}
		if (backend == PhoneBackend.OFFLINE || data.number.isEmpty()) {
			photoNotice(Lang.t("handyphone.no_signal"), true);
			return false;
		}
		if (data.photoQuotaBytes <= 0) {
			photoNotice(Lang.t("handyphone.photo.off"), true);
			return false;
		}
		if (fromComputer && !data.importAllowed) {
			photoNotice(Lang.t("handyphone.photo.no_import"), true);
			return false;
		}
		if (photoJobs + uploadsInFlight + pendingImports.size() >= pendingLimit) {
			photoNotice(Lang.t("handyphone.photo.wait"), true);
			return false;
		}
		if (data.photoUsedBytes >= data.photoQuotaBytes) {
			photoNotice(Lang.t("handyphone.photo.gallery_full"), true);
			return false;
		}
		return true;
	}

	/** Fim da contagem do temporizador (em {@link #time}), ou negativo sem contagem; e o último segundo que já tocou. */
	private double countdownEnd = -1;
	private int countdownBeeped;

	/**
	 * O disparador foi tocado: com o temporizador ligado começa a contagem (tocar de novo cancela); sem ele, dispara na hora.
	 * Confere antes se dá para tirar a foto, para não contar à toa.
	 */
	void shutterPressed() {
		if (countdownEnd >= 0) {
			countdownEnd = -1;
			dirty = true;
			playSound(PhoneSound.BUTTON);
			return;
		}
		int seconds = settings.cameraTimer();
		if (seconds <= 0) {
			takePhoto();
			return;
		}
		if (!canAddPhoto(false, 2)) {
			return;
		}
		countdownEnd = time + seconds;
		countdownBeeped = 0;
		dirty = true;
		stepCountdown();
	}

	/** Segundos que faltam na contagem do temporizador (arredondado para cima), ou 0 sem contagem. */
	int cameraCountdown() {
		return countdownEnd < 0 ? 0 : (int) Math.ceil(countdownEnd - time - 1e-9);
	}

	/** Fração do segundo atual da contagem que já passou (0 no começo do número, 1 no fim), para a animação. */
	float cameraCountdownPhase() {
		if (countdownEnd < 0) {
			return 0f;
		}
		double left = countdownEnd - time;
		return (float) (1.0 - (left - Math.floor(left)));
	}

	/** A cada passo: um toque por segundo (o último, mais agudo) e a foto no zero. Sair da Câmera cancela. */
	private void stepCountdown() {
		if (countdownEnd < 0) {
			return;
		}
		// Sair da Câmera, guardar o celular, uma janela de confirmação ou uma ligação por cima cancelam a contagem.
		if (!awake || !"camera".equals(openAppId()) || modal != null || (data.call.state != PhoneData.CALL_NONE && !data.call.minimized)) {
			countdownEnd = -1;
			dirty = true;
			return;
		}
		dirty = true;
		if (time >= countdownEnd) {
			countdownEnd = -1;
			takePhoto();
			return;
		}
		int left = cameraCountdown();
		if (left > 0 && left != countdownBeeped) {
			countdownBeeped = left;
			playSound(left == 1 ? PhoneSound.CAMERA_TIMER_LAST : PhoneSound.CAMERA_TIMER);
		}
	}

	/** Dispara a câmera: captura o mundo (sem a interface e sem o celular), reduz, comprime e envia. */
	void takePhoto() {
		if (!canAddPhoto(false, 2)) {
			return;
		}
		flashAt = time;
		shotFrame = camera.preview();
		shotGalleryTop = data.gallery.isEmpty() ? -1 : data.gallery.getFirst().id();
		dirty = true;
		photoJobs++;
		playSound(PhoneSound.CAMERA_SHUTTER);
		boolean localAtShot = localOnly;
		camera.shoot(image -> {
			if (image == null) {
				synchronized (this) {
					photoJobs--;
					photoNotice(Lang.t("handyphone.photo.capture_failed"), true);
				}
				return;
			}
			IMAGE_WORK.execute(() -> encodeAndSubmit(image, false, Lang.t("handyphone.preview.photo"), localAtShot));
		});
	}

	/**
	 * Imagens do computador (arrastadas para a janela do jogo): cada arquivo é lido, convertido
	 * e recodificado no mesmo JPEG reduzido da câmera. Entra qualquer coisa que o Java lê
	 * (PNG, JPEG, BMP, primeiro quadro de GIF); sai um formato só.
	 */
	public void importFiles(List<Path> files) {
		if (files.isEmpty() || !canAddPhoto(true, MAX_PENDING_PHOTOS)) {
			return;
		}
		int room = MAX_PENDING_PHOTOS - photoJobs - uploadsInFlight - pendingImports.size();
		if (files.size() > room) {
			photoNotice(Lang.plural("handyphone.photo.room", room), true);
		}
		for (Path file : files.subList(0, Math.min(files.size(), room))) {
			photoJobs++;
			IMAGE_WORK.execute(() -> {
				String name = file.getFileName() != null ? file.getFileName().toString() : Lang.t("handyphone.photo.file");
				try {
					if (Files.size(file) > PhotoCodec.MAX_IMPORT_FILE_BYTES) {
						photoFailed(Lang.t("handyphone.photo.failed", name, Lang.t("handyphone.photo.reason.file_too_big")));
						return;
					}
					decodeThenSubmit(Files.readAllBytes(file), name);
				} catch (IOException | RuntimeException e) {
					photoFailed(Lang.t("handyphone.photo.failed", name, Lang.t("handyphone.photo.reason.unreadable_file")));
				}
			});
		}
		dirty = true;
	}

	/** Imagem vinda da área de transferência (bytes de PNG, BMP, etc.). */
	public void importBytes(byte[] fileBytes, String label) {
		if (!canAddPhoto(true, MAX_PENDING_PHOTOS)) {
			return;
		}
		photoJobs++;
		IMAGE_WORK.execute(() -> decodeThenSubmit(fileBytes, label));
		dirty = true;
	}

	/** Roda na thread de imagens: decodifica (com as defesas do {@link PhotoCodec}) e segue para a codificação. */
	private void decodeThenSubmit(byte[] bytes, String label) {
		BufferedImage image;
		try {
			image = PhotoCodec.decodeImport(bytes);
		} catch (PhotoCodec.PhotoException e) {
			photoFailed(Lang.t("handyphone.photo.failed", label, Lang.t(e.key(), (Object[]) e.args())));
			return;
		} catch (RuntimeException | OutOfMemoryError e) {
			photoFailed(Lang.t("handyphone.photo.failed", label, Lang.t("handyphone.photo.reason.too_big_or_invalid")));
			return;
		}
		encodeAndSubmit(image, true, label);
	}

	/**
	 * Reduz e comprime a imagem. Foto da câmera segue direto para o envio; <b>imagem do computador espera a
	 * confirmação do jogador</b> (uma prévia e "Enviar para a galeria?"): a área de transferência pode ter um print
	 * com algo pessoal, e o dono do servidor passaria a ter o arquivo.
	 */
	private void encodeAndSubmit(BufferedImage image, boolean fromComputer, String label) {
		encodeAndSubmit(image, fromComputer, label, false);
	}

	/** @param localAtShot o disparo foi no modo "só neste computador" (decidido na hora: o modo pode mudar enquanto a foto é codificada) */
	private void encodeAndSubmit(BufferedImage image, boolean fromComputer, String label, boolean localAtShot) {
		byte[] jpeg = null;
		String error = null;
		try {
			jpeg = PhotoCodec.encodeForUpload(image);
		} catch (PhotoCodec.PhotoException e) {
			error = Lang.t(e.key(), (Object[]) e.args());
		} catch (RuntimeException | OutOfMemoryError e) {
			error = Lang.t("handyphone.photo.reason.process");
		}
		// Fotos da câmera: a cópia neste computador (se ligada) ou, sem o mod no servidor, o único lugar. Gravada aqui, fora do lock.
		boolean local = localAtShot && !fromComputer;
		String savedLocally = null;
		if (jpeg != null && !fromComputer && (local || settings.localCopies())) {
			localPhotos.setCapMb(settings.localPhotosMb());
			savedLocally = localPhotos.save(jpeg, System.currentTimeMillis());
		}
		synchronized (this) {
			if (local) {
				photoJobs--;
				if (savedLocally != null) {
					photoNotice(Lang.t("handyphone.photo.local_saved"), false);
					refreshLocalGallery();
				} else {
					photoNotice(jpeg == null ? error : localPhotos.full() ? Lang.t("handyphone.photo.local_full", String.valueOf(settings.localPhotosMb()))
						: Lang.t("handyphone.photo.local_failed"), true);
				}
				dirty = true;
				return;
			}
			if (jpeg != null && !fromComputer && settings.localCopies() && savedLocally == null && !localFullWarned && localPhotos.full()) {
				localFullWarned = true;
				photoNotice(Lang.t("handyphone.photo.local_full", String.valueOf(settings.localPhotosMb())), true);
			}
			photoJobs--;
			if (jpeg != null && fromComputer) {
				pendingImports.add(new PendingImport(jpeg, previewOf(image), label));
				showNextImport();
			} else if (jpeg != null && backend.uploadPhoto(jpeg, fromComputer)) {
				uploadsInFlight++;
			} else {
				photoNotice(jpeg != null ? Lang.t("handyphone.photo.not_sent_signal") : error, true);
			}
			dirty = true;
		}
	}

	/** Miniatura (até 160 px) para a janela de confirmação; a imagem inteira não fica na memória esperando. */
	private static BufferedImage previewOf(BufferedImage image) {
		float scale = Math.min(1f, 160f / Math.max(image.getWidth(), image.getHeight()));
		int w = Math.max(1, Math.round(image.getWidth() * scale)), h = Math.max(1, Math.round(image.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, w, h);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, w, h, null);
		g.dispose();
		return out;
	}

	/** Abre a confirmação da próxima imagem do computador, se não há outra janela aberta. */
	private void showNextImport() {
		PendingImport next = pendingImports.peek();
		if (next == null || modal != null) {
			return;
		}
		String text = Lang.t("handyphone.photo.confirm.text", next.label());
		modal = new ConfirmDialog(Lang.t("handyphone.photo.confirm.title"), text, Lang.t("handyphone.photo.confirm.send"), false, next.preview(), () -> {
			pendingImports.poll();
			if (backend.uploadPhoto(next.jpeg(), true)) {
				uploadsInFlight++;
			} else {
				photoNotice(Lang.t("handyphone.photo.not_sent_signal"), true);
			}
			showNextImport();
		}, () -> {
			pendingImports.poll();
			photoNotice(Lang.t("handyphone.photo.not_sent"), false);
			showNextImport();
		});
		dirty = true;
	}

	/** Mostra uma janela de confirmação sobre a tela (bloquear, sair do grupo...). */
	void confirm(String title, String message, String yesLabel, boolean danger, Runnable onYes) {
		if (modal != null) {
			return;
		}
		modal = new ConfirmDialog(title, message, yesLabel, danger, null, onYes, () -> { });
		dirty = true;
	}

	/** Fecha a janela de confirmação (sem decidir nada; quem fecha chama a ação). */
	void closeModal() {
		modal = null;
		dirty = true;
	}

	/** Cancela a janela aberta, como o botão "Cancelar" (e o "voltar") fazem. */
	public void cancelModal() {
		ConfirmDialog m = modal;
		modal = null;
		dirty = true;
		if (m != null) {
			m.cancel();
		}
	}

	/** Confirma a janela aberta, como o botão de confirmar faz (para os testes). */
	public void confirmModal() {
		ConfirmDialog m = modal;
		modal = null;
		dirty = true;
		if (m != null) {
			m.confirm();
		}
	}

	/** O título da janela de confirmação aberta, ou null (para os testes). */
	public String modalTitle() {
		return modal == null ? null : modal.title();
	}

	/** Imagens do computador esperando a confirmação (para os testes). */
	public int pendingImportCount() {
		return pendingImports.size();
	}

	private void photoFailed(String reason) {
		synchronized (this) {
			photoJobs--;
			photoNotice(reason, true);
		}
	}

	/** O servidor respondeu a um envio. Em caso de recusa, o motivo chega à parte, como aviso. */
	public void applyUploadResult(boolean ok) {
		uploadsInFlight = Math.max(0, uploadsInFlight - 1);
		if (ok) {
			photoNotice(Lang.t("handyphone.photo.saved"), false);
		}
		dirty = true;
	}

	/** Aplica a galeria mandada pelo servidor. Fotos que sumiram saem também do cache. */
	public void applyGallery(List<PhoneData.GalleryPhoto> photos, long usedBytes, long quotaBytes, boolean importAllowed) {
		for (PhoneData.GalleryPhoto old : data.gallery) {
			if (photos.stream().noneMatch(p -> p.id() == old.id())) {
				data.photos.forget(old.id());
			}
		}
		data.gallery.clear();
		data.gallery.addAll(photos);
		data.photoUsedBytes = usedBytes;
		data.photoQuotaBytes = quotaBytes;
		data.importAllowed = importAllowed;
		dirty = true;
	}

	/** Uma foto (ou miniatura) já baixada e decodificada. */
	public void applyPhoto(long id, boolean thumb, BufferedImage image) {
		data.photos.put(id, thumb, image);
		dirty = true;
	}

	/** O servidor não mandou uma foto pedida. */
	public void applyPhotoFailed(long id, boolean thumb, boolean retry) {
		data.photos.failed(id, thumb, retry, time);
		dirty = true;
	}

	/** Apaga uma foto da galeria (e das conversas, para todos). */
	void deletePhoto(long id) {
		backend.deletePhoto(id);
		data.gallery.removeIf(p -> p.id() == id);
		data.photos.forget(id);
		photoNotice(Lang.t("handyphone.photo.deleted_notice"), false);
	}

	/** Manda uma foto da galeria para uma conversa e mostra a conversa. */
	void sendPhotoTo(long conversation, long photo) {
		backend.sendPhoto(conversation, photo);
		playSound(PhoneSound.MESSAGE_SENT);
		returnToChat(conversation);
	}

	/** Várias fotos da seleção da Galeria, uma mensagem cada, da mais antiga para a mais nova (a ordem em que foram tiradas). */
	void sendPhotosTo(long conversation, List<Long> photos) {
		List<Long> ordered = new ArrayList<>(photos);
		java.util.Collections.sort(ordered);
		for (long photo : ordered) {
			backend.sendPhoto(conversation, photo);
		}
		playSound(PhoneSound.MESSAGE_SENT);
		returnToChat(conversation);
	}

	/** Apaga várias fotos da galeria (a lista volta do servidor sem elas). */
	void deletePhotos(List<Long> ids) {
		if (!ids.isEmpty()) {
			backend.deletePhotos(ids);
		}
	}

	// ------------------------------------------------------------------ fotos neste computador (1.3)

	/** A pasta de fotos deste computador (cópias das fotos tiradas e, sem o mod no servidor, a galeria inteira). */
	public final LocalPhotos localPhotos = new LocalPhotos();
	private volatile Runnable openLocalFolder = () -> { };
	/** O servidor não tem o HandyPhone: a câmera grava só aqui e a Galeria mostra a pasta deste servidor. */
	private volatile boolean localOnly;
	/** O backend do servidor guardado enquanto o local está em uso (para voltar se o servidor anunciar o mod depois). */
	private PhoneBackend serverBackend;
	/** Ids das fotos locais na galeria (negativos, para não confundir com os do servidor), pelo nome do arquivo, e o contrário. */
	private final Map<String, Long> localIds = new java.util.HashMap<>();
	private final Map<Long, String> localNames = new java.util.HashMap<>();
	private long nextLocalId = -1;
	/** Já avisou nesta sessão que a pasta local encheu (não repetir a cada foto). */
	private boolean localFullWarned;

	public void setLocalPhotoRoot(java.nio.file.Path root, Runnable open) {
		localPhotos.setRoot(root);
		openLocalFolder = open == null ? () -> { } : open;
	}

	/** O servidor ou mundo atual: nome da subpasta das fotos locais (limpo pelo LocalPhotos). */
	public void setLocalPhotoPlace(String place) {
		localPhotos.setPlace(place);
		localIds.clear();
		localNames.clear();
		// Conta o espaço usado agora, fora da thread de desenho (a câmera consulta só o resultado).
		int capMb = settings.localPhotosMb();
		IMAGE_WORK.execute(() -> {
			localPhotos.setCapMb(capMb);
			localPhotos.usedBytes();
		});
	}

	public boolean localOnly() {
		return localOnly;
	}

	/** Liga o modo "só neste computador" (servidor sem o mod) ou volta ao servidor (que anunciou o mod). */
	public void setLocalOnly(boolean on) {
		if (on == localOnly) {
			return;
		}
		localOnly = on;
		data.photoInfo.clear();
		infoAsked.clear();
		if (on) {
			serverBackend = backend;
			backend = localBackend();
			data.photos.clear();
			refreshLocalGallery();
		} else {
			backend = serverBackend != null ? serverBackend : PhoneBackend.OFFLINE;
			serverBackend = null;
			data.gallery.clear();
			data.photos.clear();
			data.photoUsedBytes = 0;
			data.photoQuotaBytes = 0;
		}
		dirty = true;
	}

	void openLocalFolder() {
		openLocalFolder.run();
	}

	/** Relê a pasta do servidor atual (fora da thread de desenho) e põe na galeria. */
	private void refreshLocalGallery() {
		IMAGE_WORK.execute(() -> {
			localPhotos.setCapMb(settings.localPhotosMb());
			List<LocalPhotos.Entry> entries = localPhotos.list(PhoneData.MAX_GALLERY_LOCAL);
			long used = localPhotos.usedBytes(), cap = localPhotos.capBytes();
			synchronized (PhoneOS.this) {
				if (!localOnly) {
					return;
				}
				List<PhoneData.GalleryPhoto> list = new ArrayList<>();
				for (LocalPhotos.Entry e : entries) {
					long id = localIds.computeIfAbsent(e.name(), n -> nextLocalId--);
					localNames.put(id, e.name());
					list.add(new PhoneData.GalleryPhoto(id, e.time(), (int) e.size()));
				}
				applyGallery(list, used, cap, false);
			}
		});
	}

	/**
	 * O "servidor" do modo local: só fotos, lidas da pasta com as mesmas defesas de uma foto vinda de um servidor
	 * ({@code PhotoCodec.decodeStored}). Mensagens, contatos, mapa e ligações não existem sem o mod.
	 */
	private PhoneBackend localBackend() {
		return (PhoneBackend) java.lang.reflect.Proxy.newProxyInstance(PhoneBackend.class.getClassLoader(), new Class<?>[] {PhoneBackend.class},
			(proxy, method, args) -> {
				switch (method.getName()) {
					case "requestPhoto" -> loadLocal((Long) args[0], (Boolean) args[1], false);
					case "requestPhotoInfo" -> loadLocal((Long) args[0], false, true);
					case "deletePhoto" -> deleteLocal(List.of((Long) args[0]));
					case "deletePhotos" -> {
						@SuppressWarnings("unchecked")
						List<Long> ids = (List<Long>) args[0];
						deleteLocal(ids);
					}
					case "hashCode" -> {
						return System.identityHashCode(proxy);
					}
					case "equals" -> {
						return proxy == args[0];
					}
					case "toString" -> {
						return "LocalBackend";
					}
					default -> {
					}
				}
				return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
			});
	}

	private void loadLocal(long id, boolean thumb, boolean info) {
		String name;
		synchronized (this) {
			name = localNames.get(id);
		}
		IMAGE_WORK.execute(() -> {
			byte[] bytes = name == null ? null : localPhotos.read(name);
			BufferedImage image = null;
			if (bytes != null) {
				try {
					image = dev.migzu.handyphone.photo.PhotoCodec.decodeStored(bytes);
				} catch (dev.migzu.handyphone.photo.PhotoCodec.PhotoException | RuntimeException e) {
					image = null;
				}
			}
			BufferedImage shown = image != null && thumb ? scaleDown(image, 160) : image;
			int size = bytes == null ? 0 : bytes.length;
			synchronized (PhoneOS.this) {
				if (info) {
					if (image != null) {
						long time = 0;
						for (PhoneData.GalleryPhoto p : data.gallery) {
							if (p.id() == id) {
								time = p.time();
							}
						}
						applyPhotoInfo(new PhoneData.PhotoInfo(id, time, image.getWidth(), image.getHeight(), size, false, "", 0, 0, 0));
					}
				} else if (shown != null) {
					applyPhoto(id, thumb, shown);
				} else {
					applyPhotoFailed(id, thumb, false);
				}
			}
		});
	}

	private void deleteLocal(List<Long> ids) {
		List<String> names = new ArrayList<>();
		synchronized (this) {
			for (long id : ids) {
				String name = localNames.get(id);
				if (name != null) {
					names.add(name);
				}
			}
		}
		IMAGE_WORK.execute(() -> {
			for (String name : names) {
				localPhotos.delete(name);
			}
			refreshLocalGallery();
		});
	}

	private static BufferedImage scaleDown(BufferedImage image, int max) {
		int w = image.getWidth(), h = image.getHeight();
		float k = Math.min(1f, max / (float) Math.max(w, h));
		if (k >= 1f) {
			return image;
		}
		BufferedImage out = new BufferedImage(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), BufferedImage.TYPE_INT_RGB);
		java.awt.Graphics2D g = out.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(image, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		return out;
	}

	// ------------------------------------------------------------------ salvar no computador (1.3)

	/** Pasta onde as fotos são salvas (no jogo, {@code screenshots/handyphone}) e quem a abre no sistema; null = indisponível. */
	private volatile java.nio.file.Path exportFolder;
	private volatile Runnable openExportFolder = () -> { };
	private final java.util.ArrayDeque<Long> exportQueue = new java.util.ArrayDeque<>();
	/** A foto que está sendo baixada/gravada agora (-1 = nenhuma), desde quando, e se a gravação já foi entregue à thread de imagens. */
	private long exportCurrent = -1;
	private double exportSince;
	private boolean exportWriting;
	private int exportOk, exportFailed;
	/** Quanto esperar a foto inteira chegar do servidor antes de desistir dela. */
	private static final double EXPORT_TIMEOUT = 20;

	public void setExportFolder(java.nio.file.Path folder, Runnable open) {
		exportFolder = folder;
		openExportFolder = open == null ? () -> { } : open;
	}

	/** Pasta das fotos salvas (para os testes). */
	public java.nio.file.Path exportFolder() {
		return exportFolder;
	}

	/** Fotos esperando para serem salvas (contando a atual); 0 = nada em andamento. */
	public int exportPending() {
		return exportQueue.size() + (exportCurrent >= 0 ? 1 : 0);
	}

	/**
	 * Salva fotos no computador, em PNG (sem perder nada além do que o JPEG já perdeu), uma por vez: cada uma é baixada inteira do
	 * servidor (o cache guarda poucas) e gravada fora da thread de desenho. No fim, um aviso; tocar nele abre a pasta.
	 */
	void exportPhotos(List<Long> ids) {
		if (exportFolder == null) {
			photoNotice(Lang.t("handyphone.photo.save_unavailable"), true);
			return;
		}
		for (long id : ids) {
			if (id != exportCurrent && !exportQueue.contains(id)) {
				exportQueue.add(id);
			}
		}
		dirty = true;
	}

	private void stepExport() {
		if (exportCurrent < 0) {
			Long next = exportQueue.poll();
			if (next == null) {
				return;
			}
			exportCurrent = next;
			exportSince = time;
			exportWriting = false;
		}
		if (exportWriting) {
			return;
		}
		long id = exportCurrent;
		BufferedImage full = data.photos.full(id, backend, time);
		if (full == null) {
			if (data.photos.isGone(id) || time - exportSince > EXPORT_TIMEOUT) {
				finishExport(false);
			}
			return;
		}
		exportWriting = true;
		java.nio.file.Path folder = exportFolder;
		long taken = photoTime(id);
		IMAGE_WORK.execute(() -> {
			boolean ok = false;
			try {
				java.nio.file.Files.createDirectories(folder);
				String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
					.format(java.time.Instant.ofEpochMilli(taken).atZone(java.time.ZoneId.systemDefault()));
				java.nio.file.Path file = folder.resolve("handyphone_" + stamp + "_" + id + ".png");
				ok = java.nio.file.Files.exists(file) || javax.imageio.ImageIO.write(full, "png", file.toFile());
			} catch (IOException | RuntimeException e) {
				ok = false;
			}
			synchronized (PhoneOS.this) {
				finishExport(ok);
			}
		});
	}

	/** Quando a foto foi tirada (da galeria, ou da mensagem que a trouxe); sem saber, agora. */
	private long photoTime(long id) {
		for (PhoneData.GalleryPhoto p : data.gallery) {
			if (p.id() == id) {
				return p.time();
			}
		}
		for (PhoneData.Conversation c : data.conversations) {
			for (PhoneData.Message m : c.messages) {
				if (m.photo() == id) {
					return m.time();
				}
			}
		}
		return System.currentTimeMillis();
	}

	private void finishExport(boolean ok) {
		if (ok) {
			exportOk++;
		} else {
			exportFailed++;
		}
		exportCurrent = -1;
		exportWriting = false;
		dirty = true;
		if (!exportQueue.isEmpty()) {
			return;
		}
		int saved = exportOk, failed = exportFailed;
		exportOk = 0;
		exportFailed = 0;
		if (saved > 0) {
			showBanner(Lang.t("handyphone.banner.photos"), Lang.plural("handyphone.photo.exported", saved), -1, "gallery");
			bannerAction = openExportFolder;
			playSound(PhoneSound.CONTACT_SAVED);
		}
		if (failed > 0 && saved == 0) {
			photoNotice(Lang.plural("handyphone.photo.save_failed", failed), true);
		}
	}

	/** Último pedido de informações por foto (não repetir a cada quadro enquanto a resposta não chega). */
	private final java.util.Map<Long, Double> infoAsked = new java.util.HashMap<>();

	/** Informações da foto, pedindo ao servidor se ainda não vieram (no máximo um pedido a cada 10 s por foto); null enquanto isso. */
	PhoneData.PhotoInfo photoInfo(long id) {
		PhoneData.PhotoInfo info = data.photoInfo.get(id);
		if (info == null) {
			Double asked = infoAsked.get(id);
			if (asked == null || time - asked > 10) {
				infoAsked.put(id, time);
				backend.requestPhotoInfo(id);
			}
		}
		return info;
	}

	/** O servidor mandou as informações de uma foto. */
	public void applyPhotoInfo(PhoneData.PhotoInfo info) {
		if (data.photoInfo.size() > 400) {
			data.photoInfo.clear();
		}
		data.photoInfo.put(info.id(), info);
		dirty = true;
	}

	/** Aviso de erro no topo, como os do servidor (para regras do próprio celular, por exemplo o limite de fotos por envio). */
	void showError(String text) {
		photoNotice(text, true);
	}

	/** Abre a galeria só para escolher uma foto e enviá-la a essa conversa (botão de anexar). */
	void openGalleryToSend(long conversation) {
		launch("gallery");
		gallery.startPick(conversation);
	}

	/** Encaminha uma foto da galeria: abre a escolha da conversa de destino. */
	void forwardPhoto(long photo) {
		launch("gallery");
		gallery.showSend(this, photo);
	}

	/** Volta a mostrar uma conversa nas Mensagens. */
	void returnToChat(long conversation) {
		launch("messages");
		messages.showChat(conversation);
		openConversation(conversation);
	}

	/** Pede ao servidor a conversa, dizendo até que mensagem o celular já tem (ele só manda o que veio depois). */
	void openConversation(long conversation) {
		PhoneData.Conversation c = data.conversation(conversation);
		backend.open(conversation, c == null ? 0 : data.haveSeq(c));
	}

	// ------------------------------------------------------------------ destaque da roda (celular na mão, sem cursor)

	/** Celular na mão: a roda move um destaque entre o que dá para tocar e o botão esquerdo "toca" o destacado. */
	private boolean focusMode;
	/** Chave do item destacado ("icon:<n>" na tela inicial, a chave da área clicável num app), ou null. */
	private String focusKey;
	/** Camada em que o destaque está (app, janela, ligação; a tela inicial é a lista de apps); trocar de camada recomeça. */
	private Object focusLayer;
	/** Último retângulo do destaque, para achar o vizinho quando a chave some (a lista rolou ou mudou). */
	private float[] focusRect;

	/** Liga o destaque (celular na mão) ou desliga (expandido, com cursor; ou guardado). */
	public void setFocusMode(boolean on) {
		if (focusMode != on) {
			focusMode = on;
			dirty = true;
		}
	}

	public boolean focusMode() {
		return focusMode;
	}

	/** Camada que recebe os toques agora: a janela, a ligação, o app aberto, ou null (tela inicial). */
	private AppContent focusContentLayer() {
		if (modal != null) {
			return modal;
		}
		if (callScreenVisible()) {
			return callScreen;
		}
		return isAppOpen() ? content() : null;
	}

	/** Itens destacáveis da camada atual, em ordem de leitura (linhas de cima para baixo, cada linha da esquerda para a direita). */
	private List<Map.Entry<String, float[]>> focusTargets() {
		List<Map.Entry<String, float[]>> out = new ArrayList<>();
		if (!awake || unlock.value < 0.6) {
			return out;
		}
		AppContent layer = focusContentLayer();
		float[] screen = theme.screen();
		if (layer == null && (openApp >= 0 || isAppOpen())) {
			// App sem conteúdo próprio (Sobre: o tema desenha a página) ou app abrindo/fechando: nada a destacar (os ícones estão cobertos).
			return out;
		}
		if (layer != null) {
			float[] area = layer == content() ? theme.appBody() : screen;
			float screenArea = screen[2] * screen[3];
			for (Map.Entry<String, float[]> e : layer.focusTargets().entrySet()) {
				float[] r = e.getValue();
				// Fora da área visível (a lista rolou) ou um "fundo" que pega a tela quase toda (fechar tocando fora): não é um item.
				float visibleH = Math.min(r[1] + r[3], area[1] + area[3]) - Math.max(r[1], area[1]);
				if (visibleH < Math.min(r[3], 8f) * 0.5f || r[2] * r[3] > screenArea * 0.6f) {
					continue;
				}
				out.add(e);
			}
			if (callBarVisible() && layer == content()) {
				out.add(Map.entry("callbar", CallScreen.barRect(screen)));
			}
		} else {
			for (int i = 0; i < apps.size(); i++) {
				float[] r = iconRect(i);
				if (r != null) {
					out.add(Map.entry("icon:" + i, r));
				}
			}
		}
		out.sort((a, b) -> {
			float[] ra = a.getValue(), rb = b.getValue();
			float ca = ra[1] + ra[3] / 2f, cb = rb[1] + rb[3] / 2f;
			if (Math.abs(ca - cb) > Math.min(ra[3], rb[3]) / 2f) {
				return Float.compare(ca, cb);
			}
			return Float.compare(ra[0], rb[0]);
		});
		return out;
	}

	/** Caixa de um ícone da tela inicial, medida pelo próprio iconAt do tema (vale para os 4 temas); null se não está à vista. */
	private float[] iconRect(int i) {
		float[] c = theme.iconCenter(this, i);
		if (c == null || theme.iconAt(this, c[0], c[1]) != i) {
			return null;
		}
		float left = 0, right = 0, up = 0, down = 0;
		while (left < 40 && theme.iconAt(this, c[0] - left - 1, c[1]) == i) {
			left++;
		}
		while (right < 40 && theme.iconAt(this, c[0] + right + 1, c[1]) == i) {
			right++;
		}
		while (up < 40 && theme.iconAt(this, c[0], c[1] - up - 1) == i) {
			up++;
		}
		while (down < 40 && theme.iconAt(this, c[0], c[1] + down + 1) == i) {
			down++;
		}
		return new float[] {c[0] - left, c[1] - up, left + right + 1, up + down + 1};
	}

	/** Identidade da camada atual (o app aberto, a janela, a ligação; a tela inicial é a própria lista de apps). */
	private Object focusLayerNow() {
		AppContent layer = focusContentLayer();
		return layer != null ? layer : apps;
	}

	/** Índice do item destacado na lista; se a chave sumiu, o mais perto do último lugar; -1 se não há destaque nesta camada. */
	private int focusIndex(List<Map.Entry<String, float[]>> targets) {
		if (focusKey == null || focusLayerNow() != focusLayer || targets.isEmpty()) {
			return -1;
		}
		for (int i = 0; i < targets.size(); i++) {
			if (targets.get(i).getKey().equals(focusKey)) {
				return i;
			}
		}
		if (focusRect == null) {
			return -1;
		}
		int best = -1;
		float bestD = Float.MAX_VALUE;
		for (int i = 0; i < targets.size(); i++) {
			float[] r = targets.get(i).getValue();
			float d = Math.abs(r[1] + r[3] / 2f - (focusRect[1] + focusRect[3] / 2f)) * 4
				+ Math.abs(r[0] + r[2] / 2f - (focusRect[0] + focusRect[2] / 2f));
			if (d < bestD) {
				bestD = d;
				best = i;
			}
		}
		return best;
	}

	/** Itens tocados com o esquerdo, por camada (o mais recente no fim): ao voltar, o destaque volta para o que levou adiante. */
	private final java.util.ArrayDeque<Map.Entry<Object, String>> focusTrail = new java.util.ArrayDeque<>();
	/** Ícone do último app aberto: ao chegar na tela inicial, o destaque está sempre nele. */
	private String homeFocus;
	/** O esquerdo acabou de tocar: se o item sumir no próximo desenho, abriu-se uma tela nova (destaque no começo dela). */
	private boolean focusForward;
	/** O voltar acabou de agir: se o item sumir, o destaque volta para o que tinha levado à tela de agora. */
	private boolean focusBack;

	private static int indexOfKey(List<Map.Entry<String, float[]>> targets, String key) {
		for (int i = 0; key != null && i < targets.size(); i++) {
			if (targets.get(i).getKey().equals(key)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Decide o item destacado neste desenho, com a mesma regra em todo lugar:
	 * <ul>
	 * <li>o item de antes continua na tela: fica nele;</li>
	 * <li>tela inicial: o ícone do último app aberto;</li>
	 * <li>voltou (direito, ou fechou uma janela ou a ligação por cima): o último item tocado nesta tela que ainda existe (a página dos
	 * Ajustes, a conversa, o "⋮");</li>
	 * <li>tela nova (app aberto, item tocado que levou adiante, janela): o item principal dela ({@link AppContent#firstFocus}) ou o
	 * primeiro;</li>
	 * <li>o item sumiu por outro motivo (a lista mudou): o vizinho mais perto.</li>
	 * </ul>
	 */
	private int resolveFocus(List<Map.Entry<String, float[]>> targets) {
		Object layer = focusLayerNow();
		boolean sameLayer = layer == focusLayer && focusKey != null;
		int i = sameLayer ? indexOfKey(targets, focusKey) : -1;
		if (i < 0) {
			boolean fromOverlay = !sameLayer && focusLayer != null && focusLayer != apps && !contents.containsValue(focusLayer);
			if (layer == apps) {
				i = indexOfKey(targets, homeFocus);
			} else if (focusBack || fromOverlay) {
				i = trailIndex(layer, targets);
			}
			if (i < 0 && (!sameLayer || focusForward || focusBack)) {
				AppContent content = layer instanceof AppContent a ? a : null;
				i = Math.max(0, indexOfKey(targets, content != null ? content.firstFocus() : null));
			}
			if (i < 0) {
				i = Math.max(0, focusIndex(targets));
			}
			setFocus(targets.get(i));
		}
		focusForward = false;
		focusBack = false;
		return i;
	}

	/** O último item tocado nesta camada que ainda está na tela, ou -1. */
	private int trailIndex(Object layer, List<Map.Entry<String, float[]>> targets) {
		for (java.util.Iterator<Map.Entry<Object, String>> it = focusTrail.descendingIterator(); it.hasNext();) {
			Map.Entry<Object, String> e = it.next();
			if (e.getKey() == layer) {
				int i = indexOfKey(targets, e.getValue());
				if (i >= 0) {
					return i;
				}
			}
		}
		return -1;
	}

	/**
	 * Uma seta de voltar desenhada pelo próprio app (cabeçalho de página) foi tocada: o destaque segue a regra do voltar (o item de onde
	 * veio), como no botão direito, e não a de "tela nova" que o toque ligou.
	 */
	void navigatedBack() {
		focusForward = false;
		focusBack = true;
		dirty = true;
	}

	/** Lembra o item tocado (o mais recente vai para o fim; no máximo 32). */
	private void rememberFocus(Object layer, String key) {
		focusTrail.removeIf(e -> e.getKey() == layer && e.getValue().equals(key));
		focusTrail.addLast(Map.entry(layer, key));
		while (focusTrail.size() > 32) {
			focusTrail.removeFirst();
		}
	}

	private void setFocus(Map.Entry<String, float[]> target) {
		focusLayer = focusLayerNow();
		focusKey = target.getKey();
		focusRect = target.getValue();
		dirty = true;
	}

	/** Chave e retângulo do item destacado agora, ou null; para os testes. */
	public Map.Entry<String, float[]> focused() {
		List<Map.Entry<String, float[]>> targets = focusTargets();
		int i = focusIndex(targets);
		return i < 0 ? null : targets.get(i);
	}

	/**
	 * Roda com o celular na mão: dir = +1 desce para o próximo item, -1 sobe. Na ponta de uma lista que rola, rola a lista
	 * (o destaque fica no item, que sobe ou desce com ela); no fim da tela inicial ou de uma janela, para.
	 */
	public void focusMove(int dir) {
		if (!awake || !focusMode) {
			return;
		}
		List<Map.Entry<String, float[]>> targets = focusTargets();
		if (targets.isEmpty()) {
			scrollContentForFocus(dir);
			return;
		}
		int i = focusIndex(targets);
		if (i < 0) {
			// Primeiro movimento nesta camada: destaca o primeiro (descendo) ou o último (subindo).
			setFocus(targets.get(dir > 0 ? 0 : targets.size() - 1));
			playSound(PhoneSound.SCROLL_TICK);
			return;
		}
		int next = i + dir;
		if (next < 0 || next >= targets.size()) {
			scrollContentForFocus(dir);
			return;
		}
		setFocus(targets.get(next));
		playSound(PhoneSound.SCROLL_TICK);
		keepFocusVisible();
	}

	/** Na ponta da lista: um clique de rolagem no app (descer = roda para baixo = valor negativo). */
	private void scrollContentForFocus(int dir) {
		AppContent content = content();
		if (content != null && focusContentLayer() == content && content.smoothScroll()) {
			scroll(dir > 0 ? -1f : 1f);
		}
	}

	/** Se o item destacado está cortado pela borda do app, rola o bastante para mostrá-lo inteiro. */
	private void keepFocusVisible() {
		AppContent content = content();
		if (focusRect == null || content == null || focusContentLayer() != content || !content.smoothScroll()) {
			return;
		}
		float[] body = theme.appBody();
		float overBottom = focusRect[1] + focusRect[3] - (body[1] + body[3]);
		float overTop = body[1] - focusRect[1];
		if (overBottom > 0.5f) {
			scroll(-(overBottom + 6f) / AppContent.SCROLL_STEP);
		} else if (overTop > 0.5f) {
			scroll((overTop + 6f) / AppContent.SCROLL_STEP);
		}
	}

	/** Botão esquerdo com o celular na mão: toca no centro do item destacado (o mesmo caminho de um clique). */
	public void focusActivate() {
		if (!awake || !focusMode) {
			return;
		}
		List<Map.Entry<String, float[]>> targets = focusTargets();
		int i = focusIndex(targets);
		if (i < 0) {
			if (!targets.isEmpty()) {
				setFocus(targets.get(0));
				playSound(PhoneSound.SCROLL_TICK);
			}
			return;
		}
		float[] r = targets.get(i).getValue();
		float cx = r[0] + r[2] / 2f, cy = r[1] + r[3] / 2f;
		rememberFocus(focusLayer, targets.get(i).getKey());
		focusForward = true;
		pointerDown(cx, cy);
		pointerUp(cx, cy);
	}

	/** O celular voltou para a mão: o campo de texto em foco solta o teclado (as teclas voltam a ser do jogo). */
	public void blurKeyboard() {
		AppContent layer = focusContentLayer();
		if (layer != null && layer.wantsKeyboard()) {
			layer.blur();
			dirty = true;
		}
	}

	/**
	 * O destaque não é desenhado na imagem da tela: ele é uma borda viva no vidro, animada a cada quadro pela mão 3D (como a cruz
	 * do cursor), sem redesenhar a tela. Aqui só se decide qual item está destacado e se publica onde ele está, em pontos da face.
	 *
	 * @param key   chave do item (muda = o destaque passa para outro item; a mão anima a troca)
	 * @param x     caixa do item {x, y, w, h} e o raio dos cantos
	 * @param clip  área onde o destaque pode aparecer {x, y, w, h} (a tela)
	 * @param color cor da borda (a de destaque dos Ajustes, ou a do tema)
	 */
	public record FocusShape(String key, float x, float y, float w, float h, float arc, float[] clip, int color) {
	}

	private volatile FocusShape focusShape;

	/** Onde está o destaque agora (thread da mão lê), ou null sem destaque. */
	public FocusShape focusShape() {
		return focusShape;
	}

	/** Atualiza o destaque publicado; chamado a cada redesenho da tela. */
	private void drawFocus(Graphics2D g) {
		if (!focusMode || !awake || unlock.value < 0.9) {
			focusShape = null;
			focusForward = false;
			focusBack = false;
			return;
		}
		List<Map.Entry<String, float[]>> targets = focusTargets();
		if (targets.isEmpty()) {
			focusShape = null;
			return;
		}
		int i = resolveFocus(targets);
		// Acompanha o item (a lista rolou, o app redesenhou): guarda o lugar novo.
		focusKey = targets.get(i).getKey();
		float[] r = targets.get(i).getValue();
		focusRect = r;
		float arc = Math.min(10f, Math.min(r[2], r[3]) * 0.45f);
		if (focusKey.startsWith("icon:")) {
			// Na tela inicial o destaque contorna o desenho do ícone (com o raio dos cantos do tema), não a área de toque.
			float[] box = theme.iconBox(this, Integer.parseInt(focusKey.substring(5)));
			if (box != null) {
				r = box;
				arc = box[4];
			}
		}
		// A chave inclui a camada: o mesmo nome de área em outra tela (outro app) também é uma troca de item.
		String key = System.identityHashCode(focusLayer) + "/" + focusKey;
		focusShape = new FocusShape(key, r[0], r[1], r[2], r[3], arc, theme.screen().clone(), highlightColor());
	}

	// ------------------------------------------------------------------ toque e teclado

	public void pointerDown(float x, float y) {
		if (!awake || unlock.value < 0.6) {
			return;
		}
		dirty = true;
		if (modal != null) {
			pressedModal = true;
			modal.pointerDown(x, y);
			return;
		}
		if (banner.value > 0.5 && inBanner(x, y)) {
			pressedBanner = true;
			return;
		}
		if (callScreenVisible() || (callBarVisible() && inside(CallScreen.barRect(theme.screen()), x, y))) {
			// A tela da ligação cobre a tela inteira e é dona de todos os toques; a barra minimizada, só da faixa dela.
			pressedCall = true;
			pressedCallId = data.call.id;
			callScreen.pointerDown(x, y);
			return;
		}
		if (theme.backAt(this, x, y)) {
			pressedHomeBar = true;
			pressedPhysicalHome = theme.physicalHomeAt(x, y);
			homeBarPress.target = 1;
			if (pressedPhysicalHome) {
				playSound(PhoneSound.HOME_KEY_DOWN);
			}
			return;
		}
		if (isAppOpen()) {
			AppContent content = content();
			if (content != null && inside(theme.appBody(), x, y)) {
				pressedContent = true;
				content.pointerDown(x, y);
			}
			return;
		}
		int i = theme.iconAt(this, x, y);
		if (i >= 0) {
			pressedIcon = i;
			press[i].target = 1;
			playSound(PhoneSound.ICON_TAP);
		}
	}

	/** O mouse andou com o botão apertado (arrastar o mapa). */
	public void pointerMove(float x, float y) {
		if (awake && pressedContent) {
			AppContent content = content();
			if (content != null) {
				content.pointerMove(this, x, y);
				dirty = true;
			}
		}
	}

	public void pointerUp(float x, float y) {
		dirty = true;
		if (pressedCall && !pressedModal && !pressedBanner) {
			pressedCall = false;
			// Uma ligação nova que chegou entre apertar e soltar não pode herdar o toque (Recusar valeria para ela).
			if (pressedCallId == data.call.id) {
				callScreen.pointerUp(this, x, y);
			} else {
				callScreen.cancelPointer();
			}
			releasePointer();
			return;
		}
		if (pressedModal) {
			ConfirmDialog m = modal;
			pressedModal = false;
			if (m != null) {
				m.pointerUp(this, x, y);
			}
			releasePointer();
			return;
		}
		if (pressedBanner) {
			if (inBanner(x, y)) {
				banner.target = 0;
				if (bannerConversation >= 0 && data.conversation(bannerConversation) != null) {
					boolean alreadyInMessages = "messages".equals(openAppId());
					launch("messages");
					messages.showChat(bannerConversation);
					openConversation(bannerConversation);
					if (alreadyInMessages) {
						playSound(PhoneSound.LIST_ITEM);
					}
				} else {
					playSound(PhoneSound.BUTTON);
					Runnable action = bannerAction;
					bannerAction = null;
					if (action != null) {
						action.run();
					}
				}
			}
		} else if (pressedHomeBar) {
			if (theme.backAt(this, x, y)) {
				back();
			}
		} else if (pressedContent) {
			AppContent content = content();
			if (content != null) {
				content.pointerUp(this, x, y);
			}
		} else if (pressedIcon >= 0 && theme.iconAt(this, x, y) == pressedIcon && !isAppOpen()) {
			launch(apps.get(pressedIcon).id());
		}
		releasePointer();
	}

	private void releasePointer() {
		if (pressedIcon >= 0) {
			press[pressedIcon].target = 0;
		}
		pressedIcon = -1;
		if (pressedPhysicalHome) {
			pressedPhysicalHome = false;
			playSound(PhoneSound.HOME_KEY_UP);
		}
		pressedHomeBar = false;
		pressedBanner = false;
		pressedModal = false;
		if (pressedCall) {
			pressedCall = false;
			callScreen.cancelPointer();
		}
		if (pressedContent) {
			pressedContent = false;
			for (AppContent content : contents.values()) {
				content.cancelPointer();
			}
		}
		homeBarPress.target = 0;
	}

	private static boolean inside(float[] rect, float x, float y) {
		return x >= rect[0] && x <= rect[0] + rect[2] && y >= rect[1] && y <= rect[1] + rect[3];
	}

	/** Verdadeiro se há um campo de texto em uso: o jogo deve mandar as teclas para o celular. */
	public boolean wantsKeyboard() {
		AppContent content = awake && modal == null && !callScreenVisible() ? content() : null;
		return content != null && content.wantsKeyboard();
	}

	public void typed(String text) {
		AppContent content = content();
		if (awake && content != null && modal == null && !callScreenVisible()) {
			content.typed(this, text);
			dirty = true;
		}
	}

	public void key(Key key) {
		AppContent content = content();
		if (awake && content != null && modal == null && !callScreenVisible()) {
			content.key(this, key);
			dirty = true;
		}
	}

	/** Roda do mouse; positivo = para cima. Listas recebem o clique aos poucos (ver {@link #step}); degraus, de uma vez. */
	public void scroll(float amount) {
		AppContent content = content();
		if (awake && content != null && modal == null && !callScreenVisible()) {
			scrollTickArmed = true;
			if (content.smoothScroll()) {
				if (pendingScrollTarget != content || Math.signum(pendingScroll) != Math.signum(amount)) {
					// Outro app, ou a roda virou de sentido: começa do zero (inverter não deve "frear" o que sobrou).
					pendingScroll = 0;
				}
				pendingScrollTarget = content;
				pendingScroll += amount * settings.scrollSpeed() / 100f;
			} else {
				pendingScroll = 0;
				content.scroll(this, amount);
			}
			dirty = true;
		}
	}

	/** Entrega uma fração da rolagem pendente: ~26% por quadro a 60 Hz, quase tudo em ~200 ms (desacelera no fim). */
	private void stepScroll(double dt) {
		if (pendingScroll == 0f) {
			return;
		}
		AppContent content = content();
		if (!awake || content == null || content != pendingScrollTarget || modal != null || callScreenVisible() || !content.smoothScroll()) {
			pendingScroll = 0f;
			pendingScrollTarget = null;
			return;
		}
		float part = Math.abs(pendingScroll) < SCROLL_SNAP ? pendingScroll : pendingScroll * (float) (1 - Math.exp(-SCROLL_RATE * dt));
		pendingScroll -= part;
		content.scroll(this, part);
		dirty = true;
	}

	// ------------------------------------------------------------------ scroll.in (1.4)

	/** Troca os apps à vista (o scroll.in some e aparece); o app aberto continua aberto, ou fecha se sumiu. */
	private void updateApps(boolean feedVisible) {
		List<App> next = visibleApps(feedVisible);
		if (next.equals(apps)) {
			return;
		}
		String open = openApp >= 0 ? apps.get(openApp).id() : null;
		apps = next;
		openApp = -1;
		if (open != null) {
			for (int i = 0; i < apps.size(); i++) {
				if (apps.get(i).id().equals(open)) {
					openApp = i;
				}
			}
			if (openApp < 0) {
				appOpen.snap(0);
				modal = null;
			}
		}
		homeFocus = null;
		pressedIcon = -1;
		dirty = true;
	}

	/** Aviso do scroll.in sem erro (denúncia enviada). */
	void feedNotice(String text) {
		showBanner(Lang.t("handyphone.app.feed"), text, -1, "feed");
		playSound(PhoneSound.CONTACT_SAVED);
	}

	/** O ícone desse app está na tela inicial? (testes: o scroll.in só aparece com servidor que o tem) */
	public boolean appVisible(String id) {
		return apps.stream().anyMatch(a -> a.id().equals(id));
	}

	/** Ids das publicações do feed já baixadas, do mais novo ao mais antigo (testes). */
	public List<Long> feedPostIds() {
		return data.feed.posts.stream().map(PhoneData.FeedPost::id).toList();
	}

	private void clearFeed() {
		data.feedInfoReceived = false;
		data.feedAvailable = false;
		data.feedMe = "";
		data.feedMeName = "";
		data.feedAdmin = false;
		data.feedEligible = null;
		data.feed.clear();
		data.profile.clear();
		data.profile.author = "";
		data.feedComments.clear();
		updateApps(false);
	}

	/** O servidor diz se o scroll.in existe aqui (sem isto o ícone nem aparece), os limites e quem é o próprio jogador no feed. */
	public void applyFeedInfo(boolean available, boolean cameraOnly, int maxCaption, int maxComment, String me, String meName, boolean admin) {
		data.feedAdmin = available && admin;
		data.feedInfoReceived = true;
		data.feedAvailable = available;
		data.feedCameraOnly = cameraOnly;
		data.feedMaxCaption = Math.max(1, maxCaption);
		data.feedMaxComment = Math.max(1, maxComment);
		data.feedMe = me;
		data.feedMeName = meName;
		if (!available) {
			data.feed.clear();
			data.profile.clear();
			data.feedComments.clear();
		}
		updateApps(available);
		dirty = true;
	}

	/**
	 * Uma página do feed ({@code author} vazio = de todos) ou de um perfil. {@code before} 0 = a primeira página (troca a lista);
	 * senão continua depois da publicação {@code before}.
	 */
	public void applyFeedPage(String author, long before, List<PhoneData.FeedPost> posts, boolean more) {
		PhoneData.FeedList list;
		if (author.isEmpty()) {
			list = data.feed;
		} else if (author.equals(data.profile.author)) {
			list = data.profile;
		} else {
			return;
		}
		if (before == 0) {
			list.posts.clear();
		} else if (list.posts.isEmpty() || list.posts.get(list.posts.size() - 1).id() != before) {
			list.loading = false;
			return; // Resposta de um pedido antigo (a lista já mudou): descarta.
		}
		for (PhoneData.FeedPost p : posts) {
			if (list.posts.size() >= PhoneData.MAX_FEED_POSTS) {
				break;
			}
			if (list.find(p.id()) == null) {
				list.posts.add(p);
			}
		}
		list.more = more && list.posts.size() < PhoneData.MAX_FEED_POSTS;
		list.loaded = true;
		list.loading = false;
		dirty = true;
	}

	/** Uma publicação mudou (curtida, comentário, legenda): troca onde estiver. */
	public void applyFeedPost(PhoneData.FeedPost post) {
		data.feed.replace(post);
		data.profile.replace(post);
		dirty = true;
	}

	/** Uma publicação saiu (apagada pelo dono, por um administrador, ou a foto saiu). */
	public void applyFeedGone(long post) {
		data.feed.posts.removeIf(p -> p.id() == post);
		data.profile.posts.removeIf(p -> p.id() == post);
		data.feedComments.remove(post);
		dirty = true;
	}

	/** Os comentários de uma publicação (todos os que o jogador pode ver, do mais antigo ao mais novo). */
	public void applyFeedComments(long post, List<PhoneData.FeedComment> comments) {
		if (data.feedComments.size() > 64) {
			data.feedComments.clear();
		}
		data.feedComments.put(post, new ArrayList<>(comments));
		dirty = true;
	}

	/** As fotos da galeria que podem ser publicadas. */
	public void applyFeedEligible(List<Long> ids) {
		data.feedEligible = new java.util.HashSet<>(ids);
		dirty = true;
	}

	/** Tipos de atividade do scroll.in que viram aviso. */
	public static final int FEED_LIKE = 0, FEED_COMMENT = 1, FEED_REPORT = 2;

	/** Alguém curtiu ou comentou uma publicação do jogador: aviso discreto (respeita o "aviso no topo" dos Ajustes). */
	public void applyFeedActivity(int kind, String name, long post) {
		if (kind == FEED_REPORT) {
			// Para administradores: aviso do servidor, não notificação social (aparece mesmo com o aviso no topo desligado).
			showBanner(Lang.t("handyphone.app.feed"), Lang.t("handyphone.feed.activity.report"), -1, "feed");
			playSound(PhoneSound.NOTIFICATION);
			return;
		}
		String text = Lang.t(kind == FEED_COMMENT ? "handyphone.feed.activity.comment" : "handyphone.feed.activity.like", name);
		notificationBanner(Lang.t("handyphone.app.feed"), text, "feed");
		playSound(PhoneSound.NOTIFICATION);
		dirty = true;
	}

	// ------------------------------------------------------------------ ligações

	/** O servidor diz se as ligações existem aqui. */
	public void applyCallInfo(boolean available, boolean crossDimension) {
		data.callInfoReceived = true;
		data.callsAvailable = available;
		data.callsCrossDimension = crossDimension;
		dirty = true;
	}

	/**
	 * Aplica o retrato da ligação do jogador. O celular só desenha o que o servidor manda; os sons de toque (em laço)
	 * e o ícone do HUD seguem o {@link #callView()}.
	 */
	public void applyCall(long id, int state, int reason, long conversation, boolean muted, boolean voice, int seconds, List<PhoneData.CallPart> parts) {
		PhoneData.Call call = data.call;
		int previous = call.state;
		long previousId = call.id;
		String previousPeer = call.firstNumber();
		if (state == PhoneData.CALL_NONE) {
			call.clear();
		} else {
			// A tela minimizada continua assim quando a ligação passa de "chamando" para "atendida", mas nunca numa chamada nova.
			boolean keepMinimized = call.minimized && call.id == id && state != PhoneData.CALL_INCOMING;
			call.id = id;
			call.state = state;
			call.reason = reason;
			call.conversation = conversation;
			call.muted = muted;
			call.voice = voice;
			call.parts = List.copyOf(parts);
			call.minimized = keepMinimized;
			if (state == PhoneData.CALL_ACTIVE) {
				// Só ao atender: um retrato novo da mesma ligação (mudo, voz) não pode fazer o cronômetro recuar até 1 s.
				if (previous != PhoneData.CALL_ACTIVE || call.answeredLocalMs == 0 || previousId != id) {
					call.answeredLocalMs = PhoneClock.millis() - seconds * 1000L;
				}
			} else if (state == PhoneData.CALL_ENDED) {
				call.endedSeconds = seconds;
				call.endedUntilMs = PhoneClock.millis() + 2400;
				call.minimized = false;
				if (reason == PhoneData.END_MISSED) {
					// Perdida enquanto tocava: um aviso, sem tela cheia.
					String who = previous == PhoneData.CALL_INCOMING && !previousPeer.isEmpty() ? data.displayName(previousPeer) : Lang.t("handyphone.banner.call");
					call.clear();
					notificationBanner(who, Lang.t("handyphone.banner.missed_call"), "phone");
					playSound(PhoneSound.NOTIFICATION);
				}
			}
		}
		publishCall();
		dirty = true;
	}

	/** A tela de "encerrada" some sozinha. Roda também com o celular guardado (a thread de desenho para quando ele não está à vista). */
	private void expireCall() {
		PhoneData.Call call = data.call;
		if (call.state == PhoneData.CALL_ENDED && PhoneClock.millis() > call.endedUntilMs) {
			call.clear();
			publishCall();
			dirty = true;
		}
	}

	/** Ganchos para os testes de tela. */
	public void typeInPhoneApp(String digits) {
		phone.setDialed(digits);
		dirty = true;
	}

	public void selectPhoneTab(int tab) {
		phone.selectTab(tab);
		dirty = true;
	}

	public void callForTests(String number) {
		startCall(number);
	}

	public void expandCallForTests() {
		expandCall();
	}

	/** Centros (em coordenadas da face) dos botões da tela da ligação, para os testes tocarem neles. */
	public float[] answerButtonCenter() {
		return CallScreen.buttonCenter(theme.screen(), 1);
	}

	public float[] muteButtonCenter() {
		return CallScreen.buttonCenter(theme.screen(), -1);
	}

	public float[] hangupButtonCenter() {
		return CallScreen.buttonCenter(theme.screen(), data.call.state == PhoneData.CALL_ACTIVE ? 1 : 0);
	}

	private void publishCall() {
		PhoneData.Call call = data.call;
		int joined = 0;
		for (PhoneData.CallPart part : call.parts) {
			if (part.joined()) {
				joined++;
			}
		}
		callView = call.state == PhoneData.CALL_NONE ? PhoneData.CallView.NONE : new PhoneData.CallView(call.id, call.state, call.reason, call.muted, joined);
	}

	/** O que o áudio e o HUD precisam da ligação; pode ser lido a qualquer momento, sem a trava. */
	public PhoneData.CallView callView() {
		return callView;
	}

	/** Estado atual da ligação, para os testes. */
	public int callState() {
		return data.call.state;
	}

	/** Motivo do fim da última ligação, para os testes. */
	public int callReason() {
		return data.call.reason;
	}

	/** Número do outro lado da ligação, ou vazio, para os testes. */
	public String callPeer() {
		return data.call.firstNumber();
	}

	/** Conversa de grupo da ligação (0 = ligação direta), para os testes. */
	public long callConversation() {
		return data.call.conversation;
	}

	/** Id da ligação do celular (0 = nenhuma), para os testes. */
	public long callId() {
		return data.call.id;
	}

	/** As ligações existem neste servidor, segundo o servidor (para os testes). */
	public boolean callsAvailable() {
		return data.callsAvailable;
	}

	/** O próprio jogador está no mudo (para os testes). */
	public boolean callMuted() {
		return data.call.muted;
	}

	/** A tela da ligação está minimizada (para os testes). */
	public boolean callMinimized() {
		return data.call.minimized;
	}

	/** A tela cheia da ligação está à vista (o jogador está com o celular na mão e ela não foi minimizada)? */
	boolean callScreenVisible() {
		PhoneData.Call call = data.call;
		if (!awake || call.state == PhoneData.CALL_NONE) {
			return false;
		}
		return call.state == PhoneData.CALL_INCOMING || call.state == PhoneData.CALL_ENDED || !call.minimized;
	}

	/** A barra verde (ligação minimizada) está à vista? */
	boolean callBarVisible() {
		PhoneData.Call call = data.call;
		return awake && call.minimized && (call.state == PhoneData.CALL_ACTIVE || call.state == PhoneData.CALL_OUTGOING);
	}

	/** O servidor mandou o histórico de chamadas. */
	public void applyCallLog(List<PhoneData.CallLogEntry> entries) {
		data.callLog.clear();
		data.callLog.addAll(entries);
		dirty = true;
	}

	/** Quantas linhas os recentes têm (para os testes). */
	public int callLogCount() {
		return data.callLog.size();
	}

	/** Chama um grupo (ou entra na chamada dele) a partir do cabeçalho da conversa. */
	void callGroup(long conversation) {
		if (!checkCanCall()) {
			return;
		}
		backend.callGroup(conversation);
	}

	public void callGroupForTests(long conversation) {
		callGroup(conversation);
	}

	/** Liga para um número a partir do celular. O servidor decide; aqui só se confere o que já se sabe sem perguntar. */
	/** Confere o que dá para saber sem perguntar ao servidor antes de ligar; avisa o jogador se não dá. */
	private boolean checkCanCall() {
		if (backend == PhoneBackend.OFFLINE || data.number.isEmpty()) {
			phoneNotice(Lang.t("handyphone.no_signal"));
			return false;
		}
		if (!data.callsAvailable) {
			phoneNotice(Lang.t("handyphone.call.unavailable"));
			return false;
		}
		if (hasCall()) {
			phoneNotice(Lang.t("handyphone.call.already"));
			return false;
		}
		return true;
	}

	void startCall(String number) {
		if (!checkCanCall()) {
			return;
		}
		if (number.isEmpty()) {
			phoneNotice(Lang.t("handyphone.call.enter_number"));
			return;
		}
		backend.callStart(number);
	}

	private void phoneNotice(String text) {
		showBanner(Lang.t("handyphone.app.phone"), text, -1, "phone");
		playSound(PhoneSound.ERROR);
	}

	void answerCall() {
		if (data.call.state == PhoneData.CALL_INCOMING) {
			backend.callAnswer(data.call.id);
		}
	}

	/** Recusa, cancela ou desliga, conforme o estado. Quem decide o que acontece é o servidor; ele responde com o novo estado. */
	void declineCall() {
		PhoneData.Call call = data.call;
		if (call.state == PhoneData.CALL_INCOMING || call.state == PhoneData.CALL_OUTGOING || call.state == PhoneData.CALL_ACTIVE) {
			backend.callDecline(call.id);
		}
	}

	void toggleCallMute() {
		PhoneData.Call call = data.call;
		if (call.state == PhoneData.CALL_ACTIVE) {
			backend.callMute(call.id, !call.muted);
		}
	}

	/** A tela de "encerrada" foi dispensada (toque ou voltar). */
	void dismissCall() {
		if (data.call.state == PhoneData.CALL_ENDED) {
			data.call.clear();
			publishCall();
			dirty = true;
		}
	}

	/** Reabre a tela da ligação a partir da barra verde. */
	void expandCall() {
		data.call.minimized = false;
		dirty = true;
	}

	/** Liga a quem está em uma conversa direta (botão do cabeçalho); o celular abre o Telefone só se faltar algo a mostrar. */
	void callFromChat(String number) {
		startCall(number);
	}

	/** Há ligação chamando, tocando ou atendida? (A tela de "encerrada" não conta: já acabou.) */
	boolean hasCall() {
		int state = data.call.state;
		return state == PhoneData.CALL_INCOMING || state == PhoneData.CALL_OUTGOING || state == PhoneData.CALL_ACTIVE;
	}

	private void drawCall(Graphics2D g) {
		if (callScreenVisible() || callBarVisible()) {
			float[] screen = theme.screen();
			Shape oldClip = g.getClip();
			g.clip(new Rectangle2D.Float(screen[0], screen[1], screen[2], screen[3]));
			callScreen.render(g, this, screen[0], screen[1], screen[2], screen[3], new Color(0x2FB75A));
			g.setClip(oldClip);
		}
	}

	// ------------------------------------------------------------------ desenho

	/**
	 * Desenha a face inteira do aparelho. O Graphics2D já deve estar escalado
	 * para coordenadas lógicas; {@code pixelScale} é usado só para o cache do papel de parede.
	 */
	public void render(Graphics2D g, float pixelScale) {
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

		Composite src = g.getComposite();
		g.setComposite(AlphaComposite.Clear);
		g.fillRect(0, 0, FACE_W, FACE_H);
		g.setComposite(src);

		theme.render(g, this, pixelScale);
		drawBadge(g);
		drawCall(g);
		drawModal(g);
		drawBanner(g);
		drawFocus(g);
		theme.drawScreenMask(g, pixelScale);
	}

	/** A janela de confirmação, sobre a tela inteira (menos a moldura do aparelho). */
	private void drawModal(Graphics2D g) {
		ConfirmDialog m = modal;
		if (m == null) {
			return;
		}
		float[] screen = theme.screen();
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(screen[0], screen[1], screen[2], screen[3]));
		Color accent = accentOr(openApp >= 0 ? apps.get(openApp).c2() : new Color(0x1976D2));
		m.render(g, this, screen[0], screen[1], screen[2], screen[3], accent);
		g.setClip(oldClip);
	}

	/**
	 * Desenha o conteúdo de um app na área dada. Retorna false se o app ainda não
	 * tem conteúdo (o tema então mostra o "Em breve").
	 */
	boolean drawApp(Graphics2D g, App app, float[] body, Color accent) {
		AppContent content = contents.get(app.id());
		if (content == null) {
			return false;
		}
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(body[0], body[1], body[2], body[3]));
		content.render(g, this, body[0], body[1], body[2], body[3], accentOr(accent));
		g.setClip(oldClip);
		return true;
	}

	/** Bolinha vermelha com as mensagens não lidas, sobre o ícone de Mensagens na tela inicial. */
	private void drawBadge(Graphics2D g) {
		int unread = unreadTotal();
		float visible = Ui.clamp01((unlock.f() - 0.85f) / 0.15f) * Ui.clamp01(1 - appOpen.f() * 6);
		if (unread <= 0 || visible <= 0) {
			return;
		}
		float[] c = iconCenter("messages");
		String count = unread > 99 ? "99+" : Integer.toString(unread);
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(visible));
		g.setFont(Ui.font(Font.BOLD, 7f));
		float w = Math.max(12, Ui.textWidth(g, count) + 6.5f), x = c[0] + 8, y = c[1] - 17;
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(x - 1, y - 1, w + 2, 14, 14, 14));
		g.setColor(new Color(0xE5392E));
		g.fill(new RoundRectangle2D.Float(x, y, w, 12, 12, 12));
		g.setColor(Color.WHITE);
		Ui.centered(g, count, x + w / 2f, y + 8.7f);
		g.setComposite(old);
	}

	/** Retângulo do aviso, dentro da tela do tema: {x, y, w, h}. */
	private float[] bannerRect() {
		float[] screen = theme.screen();
		return new float[] {screen[0] + 6, screen[1] + 18, screen[2] - 12, 34};
	}

	private boolean inBanner(float x, float y) {
		return inside(bannerRect(), x, y);
	}

	private void drawBanner(Graphics2D g) {
		float b = banner.f();
		if (b <= 0.01f) {
			return;
		}
		float[] screen = theme.screen(), r = bannerRect();
		Shape oldClip = g.getClip();
		Composite old = g.getComposite();
		g.clip(new Rectangle2D.Float(screen[0], screen[1], screen[2], screen[3]));
		g.setComposite(AlphaComposite.SrcOver.derive(Ui.clamp01(b)));
		float y = r[1] - (1 - b) * 46;
		g.setColor(new Color(0, 0, 0, 70));
		g.fill(new RoundRectangle2D.Float(r[0], y + 1.6f, r[2], r[3], 12, 12));
		g.setColor(new Color(0x23252C));
		g.fill(new RoundRectangle2D.Float(r[0], y, r[2], r[3], 12, 12));
		// O scroll.in usa a cor do ícone dele; o resto, o verde de sempre.
		Color dot = "feed".equals(bannerGlyph) ? new Color(0xD32F2F) : new Color(0x35C759);
		g.setColor(dot);
		g.fill(new Ellipse2D.Float(r[0] + 7, y + 8, 18, 18));
		java.awt.geom.AffineTransform t = g.getTransform();
		g.translate(r[0] + 9, y + 10);
		g.scale(14 / 34f, 14 / 34f);
		Ui.glyph(g, bannerGlyph, Color.WHITE, "feed".equals(bannerGlyph) ? dot : Color.WHITE);
		g.setTransform(t);
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 8.4f));
		Ui.text(g, AppContent.fit(g, bannerTitle, r[2] - 38), r[0] + 30, y + 14);
		g.setColor(new Color(0xC9CCD6));
		g.setFont(Ui.font(Font.PLAIN, 7.6f));
		Ui.text(g, AppContent.fit(g, bannerText, r[2] - 38), r[0] + 30, y + 25.5f);
		g.setComposite(old);
		g.setClip(oldClip);
	}

	/** O nome da tecla que puxa o celular, para o aviso da tela de bloqueio ("U"); o jogo o atualiza (o PhoneOS não conhece as teclas). */
	private volatile String useKeyName = "U";

	public void setUseKeyName(String name) {
		if (!name.equals(useKeyName)) {
			useKeyName = name;
			dirty = true;
		}
	}

	String useKeyName() {
		return useKeyName;
	}

	/**
	 * O idioma (ou um pacote de recursos) mudou: o que foi montado com texto, como a hora, é refeito no próximo quadro.
	 * Quem chama segura a trava do PhoneOS.
	 */
	public void languageChanged() {
		lastClockMinute = -1;
		lastClock = "";
		// A moldura pronta (imagem em cache) pode ter texto desenhado, como a marca no aparelho: refaz no próximo quadro.
		theme.releaseCaches();
		dirty = true;
	}

	/**
	 * Imagem parada da tela, como os outros jogadores veem o celular de fora (terceira pessoa): a tela inicial do tema, sem relógio, sem
	 * nada do jogador (um PhoneOS novo, sem dados) e sem cursor nem destaque. Desenhada uma vez por tema; o tamanho é o da face
	 * ({@link #FACE_W} x {@link #FACE_H}) vezes a escala.
	 */
	public static java.awt.image.BufferedImage renderStill(String themeId, float pixelScale) {
		PhoneOS os = new PhoneOS();
		os.still = true;
		os.switchTheme(themeId);
		os.setAwake(true);
		for (int i = 0; i < 240; i++) {
			os.step(1 / 60.0);
		}
		java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.round(FACE_W * pixelScale), Math.round(FACE_H * pixelScale),
			java.awt.image.BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.scale(pixelScale, pixelScale);
		os.render(g, pixelScale);
		g.dispose();
		return img;
	}

	/** Os temas, na ordem fixa em que viajam pela rede (o índice é o número do tema no pacote de pose). */
	public static final List<String> THEME_IDS = List.of("retro", "aero", "flat", "modern");

	/** Imagem parada (ver {@link #renderStill}): sem relógio. */
	private boolean still;

	String clockText() {
		if (still) {
			return "";
		}
		return lastClock.isEmpty() ? LocalDateTime.now().format(timeFormat()) : lastClock;
	}
}
