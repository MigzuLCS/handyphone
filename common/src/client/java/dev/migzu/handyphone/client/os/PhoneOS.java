package dev.migzu.handyphone.client.os;

import java.awt.AlphaComposite;
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

	/** Teclas especiais repassadas pelo jogo quando um campo de texto está em uso. */
	public enum Key { BACKSPACE, ENTER, TAB }

	/** {@code nameKey} é a chave de texto do nome; {@link #name()} o traduz no idioma em uso. */
	public record App(String id, String nameKey, Color c1, Color c2, boolean dock) {
		public String name() {
			return Lang.t(nameKey);
		}
	}

	final List<App> apps = List.of(
		new App("map", "handyphone.app.map", new Color(0x4FC3F7), new Color(0x1976D2), false),
		new App("contacts", "handyphone.app.contacts", new Color(0xFFB74D), new Color(0xF4511E), false),
		new App("settings", "handyphone.app.settings", new Color(0xB0BEC5), new Color(0x546E7A), false),
		new App("about", "handyphone.app.about", new Color(0xB388FF), new Color(0x5E35B1), false),
		new App("phone", "handyphone.app.phone", new Color(0x69F0AE), new Color(0x00A152), true),
		new App("messages", "handyphone.app.messages", new Color(0x7CF07A), new Color(0x1FAF38), true),
		new App("camera", "handyphone.app.camera", new Color(0xE0E0E0), new Color(0x8E8E93), true),
		new App("gallery", "handyphone.app.gallery", new Color(0xFF8A80), new Color(0xD81B60), true)
	);

	private final MessagesApp messages = new MessagesApp();
	private final ContactsApp contacts = new ContactsApp();
	private final GalleryApp gallery = new GalleryApp();
	private final MapApp map = new MapApp();
	private final PhoneApp phone = new PhoneApp();
	/** A tela da ligação (sobre tudo, menos o aviso) e a barra verde de quando ela está minimizada. */
	private final CallScreen callScreen = new CallScreen();
	private boolean pressedCall;
	private long pressedCallId;
	/** Apps que já têm conteúdo de verdade; os outros mostram "Em breve". */
	private final Map<String, AppContent> contents = Map.of(
		"settings", new SettingsApp(), "messages", messages, "contacts", contacts, "camera", new CameraApp(), "gallery", gallery, "map", map,
		"phone", phone
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
	private Consumer<String> themeListener = id -> { };
	private Runnable settingsListener = () -> { };
	private Consumer<PhoneSound> soundListener = s -> { };
	private boolean uiSounds = true;
	private boolean keyboardSounds = true;

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
	/** Momento do último disparo, para o clarão do visor. */
	double flashAt = -10;

	/** 0 = tela de bloqueio, 1 = desbloqueado. */
	final Spring unlock = Spring.smooth(0);
	/** 0 = tela inicial, 1 = app aberto. */
	final Spring appOpen = new Spring(0, 300, 27);
	final Spring[] press = new Spring[apps.size()];
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
	private AppContent content() {
		return isAppOpen() ? contents.get(apps.get(openApp).id()) : null;
	}

	/** Botão "voltar"/início. Retorna false se já estava na tela inicial. */
	public boolean back() {
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

	/** Troca o estilo da interface. Ids desconhecidos são ignorados. */
	public void setTheme(String id) {
		for (Theme candidate : themes) {
			if (candidate.id().equals(id) && candidate != theme) {
				theme.releaseCaches();
				theme = candidate;
				dirty = true;
				playSound(PhoneSound.THEME_CHANGE);
				themeListener.accept(id);
				settingsListener.run();
			}
		}
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
		return uiSounds;
	}

	public void setUiSounds(boolean enabled) {
		if (uiSounds != enabled) {
			uiSounds = enabled;
			dirty = true;
			settingsListener.run();
		}
	}

	public boolean keyboardSounds() {
		return keyboardSounds;
	}

	public void setKeyboardSounds(boolean enabled) {
		if (keyboardSounds != enabled) {
			keyboardSounds = enabled;
			dirty = true;
			settingsListener.run();
		}
	}

	void playSound(PhoneSound sound) {
		if (sound == null) {
			return;
		}
		if (sound.group() == PhoneSound.Group.KEYBOARD && !keyboardSounds) {
			return;
		}
		if ((sound.group() == PhoneSound.Group.UI || sound.group() == PhoneSound.Group.NOTIFICATION) && !uiSounds) {
			return;
		}
		soundListener.accept(sound);
	}

	void playScrollTick() {
		if (time - lastScrollSoundTime >= SCROLL_SOUND_INTERVAL) {
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

	public void step(double dt) {
		time += dt;
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
		if (time - flashAt < 0.3) {
			dirty = true;
		}
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
		showBanner(title, text, conversation, "messages");
		if (conversation < 0) {
			playSound(PhoneSound.ERROR);
		} else {
			PhoneData.Conversation c = data.conversation(conversation);
			playSound(c != null && c.group ? PhoneSound.NOTIFICATION_GROUP : PhoneSound.NOTIFICATION);
		}
	}

	private void showBanner(String title, String text, long conversation, String glyph) {
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
					showBanner(data.displayName(fresh.number()), Lang.t("handyphone.banner.live_started"), -1, "map");
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

	/** Dispara a câmera: captura o mundo (sem a interface e sem o celular), reduz, comprime e envia. */
	void takePhoto() {
		if (!canAddPhoto(false, 2)) {
			return;
		}
		flashAt = time;
		dirty = true;
		photoJobs++;
		// PONTO DO SOM DO OBTURADOR: quando os efeitos sonoros do celular tiverem o som da câmera,
		// é aqui, no instante do disparo, que ele toca (algo como playSound(PhoneSound.CAMERA_SHUTTER)).
		camera.shoot(image -> {
			if (image == null) {
				synchronized (this) {
					photoJobs--;
					photoNotice(Lang.t("handyphone.photo.capture_failed"), true);
				}
				return;
			}
			IMAGE_WORK.execute(() -> encodeAndSubmit(image, false, Lang.t("handyphone.preview.photo")));
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
		byte[] jpeg = null;
		String error = null;
		try {
			jpeg = PhotoCodec.encodeForUpload(image);
		} catch (PhotoCodec.PhotoException e) {
			error = Lang.t(e.key(), (Object[]) e.args());
		} catch (RuntimeException | OutOfMemoryError e) {
			error = Lang.t("handyphone.photo.reason.process");
		}
		synchronized (this) {
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

	/** Roda do mouse; positivo = para cima. */
	public void scroll(float amount) {
		AppContent content = content();
		if (awake && content != null && modal == null && !callScreenVisible()) {
			content.scroll(this, amount);
			dirty = true;
		}
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
					showBanner(who, Lang.t("handyphone.banner.missed_call"), -1, "phone");
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
		Color accent = openApp >= 0 ? apps.get(openApp).c2() : new Color(0x1976D2);
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
		content.render(g, this, body[0], body[1], body[2], body[3], accent);
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
		g.setColor(new Color(0x35C759));
		g.fill(new Ellipse2D.Float(r[0] + 7, y + 8, 18, 18));
		java.awt.geom.AffineTransform t = g.getTransform();
		g.translate(r[0] + 9, y + 10);
		g.scale(14 / 34f, 14 / 34f);
		Ui.glyph(g, bannerGlyph, Color.WHITE, Color.WHITE);
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

	String clockText() {
		return lastClock.isEmpty() ? LocalDateTime.now().format(timeFormat()) : lastClock;
	}
}
