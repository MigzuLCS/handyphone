package dev.migzu.handyphone.client.os;

/**
 * Todas as opções do celular que o jogador escolhe no app Ajustes, num lugar só. O PhoneOS guarda uma instância e avisa quem
 * salva quando algo muda; o mod grava os campos como estão no {@code handyphone.json} (Gson, campo a campo: um arquivo antigo
 * sem um campo fica com o padrão daqui). Valores fora da faixa (arquivo editado à mão) são corrigidos por {@link #normalize()}.
 * <p>
 * Os números são inteiros em porcentagem para o arquivo ficar legível. A tecla que puxa o celular não mora aqui: é do jogo
 * (Controles, {@code options.txt}).
 */
public final class PhoneSettings {
	/** Como o item escolhido pela roda é marcado no modo "na mão". */
	public enum Highlight {
		FLUID("fluid"), CALM("calm"), SIMPLE("simple");

		final String id;

		Highlight(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		static Highlight of(String id) {
			for (Highlight h : values()) {
				if (h.id.equals(id)) {
					return h;
				}
			}
			return FLUID;
		}
	}

	/** Desenho do cursor no modo expandido (no vidro, onde o mouse cai na tela). */
	public enum Cursor {
		CROSS("cross"), HAND("hand");

		final String id;

		Cursor(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}

		static Cursor of(String id) {
			for (Cursor c : values()) {
				if (c.id.equals(id)) {
					return c;
				}
			}
			return CROSS;
		}
	}

	public static final int MIN_SCROLL_SPEED = 50, MAX_SCROLL_SPEED = 200;

	// Aparência
	private String theme = PhoneOS.DEFAULT_THEME;
	/** Cor do aparelho em 0xRRGGBB; -1 = a cor do próprio tema. */
	private int accent = -1;
	// Navegação
	private String highlight = Highlight.FLUID.id;
	/**
	 * Só para ler arquivos da 1.1 (a chave "Animação do destaque" saiu na 1.2: o estilo Simples é o parado). {@code false} vira o
	 * Simples em {@link #normalize()} e o campo some do arquivo (o Gson não grava null).
	 */
	private Boolean highlightAnimated;
	/** Cor do destaque da roda em 0xRRGGBB; -1 = automática (um tom diferente da cor de destaque, para não sumir nos botões). */
	private int highlightColor = -1;
	/** Cursor do modo expandido; arquivo antigo sem o campo = cruz. */
	private String cursorStyle = Cursor.CROSS.id;
	/** Velocidade da rolagem, em % do padrão. */
	private int scrollSpeed = 100;
	/** Quanto do balanço da caminhada sobra com o celular na mão, em %. */
	private int handSway = 10;
	private boolean controlsHint = true;
	// Sons
	private boolean uiSounds = true;
	private boolean keyboardSounds = true;
	/** Volume dos sons do celular, em %. */
	private int volume = 100;
	/** De qual tema vêm os sons; vazio = do tema em uso (o visual e os sons separados: Aero com os sons do Minimalista, etc.). */
	private String soundTheme = "";
	// Notificações
	private boolean notifyBanner = true;
	private boolean notifySound = true;
	// Privacidade (1.3): os outros jogadores veem o celular na sua mão (só o aparelho, nunca a tela). Arquivo sem o campo = ligado.
	private boolean showToOthers = true;
	/** Cópia das fotos que o jogador tira neste computador (1.3; ver LocalPhotos). Arquivo sem o campo = ligada. */
	private boolean localCopies = true;
	/** Teto da pasta de fotos deste computador, em MB (todas as pastas de servidor juntas). Só pelo arquivo. */
	private int localPhotosMb = 500;
	// Câmera (1.3): grade de terços no visor e temporizador do disparo (0, 3 ou 10 s). Lembrados como num celular de verdade.
	private boolean cameraGrid = false;
	private int cameraTimer = 0;

	/** Durações do temporizador da Câmera, em segundos, na ordem do botão (0 = desligado). */
	public static final int[] CAMERA_TIMERS = {0, 3, 10};

	public PhoneSettings copy() {
		PhoneSettings c = new PhoneSettings();
		c.theme = theme;
		c.accent = accent;
		c.highlight = highlight;
		c.highlightAnimated = highlightAnimated;
		c.highlightColor = highlightColor;
		c.cursorStyle = cursorStyle;
		c.scrollSpeed = scrollSpeed;
		c.handSway = handSway;
		c.controlsHint = controlsHint;
		c.uiSounds = uiSounds;
		c.keyboardSounds = keyboardSounds;
		c.volume = volume;
		c.soundTheme = soundTheme;
		c.notifyBanner = notifyBanner;
		c.notifySound = notifySound;
		c.showToOthers = showToOthers;
		c.localCopies = localCopies;
		c.localPhotosMb = localPhotosMb;
		c.cameraGrid = cameraGrid;
		c.cameraTimer = cameraTimer;
		return c;
	}

	/** Corrige o que veio fora da faixa ou vazio (arquivo editado à mão ou de uma versão futura). */
	public PhoneSettings normalize() {
		if (theme == null || theme.isBlank()) {
			theme = PhoneOS.DEFAULT_THEME;
		}
		theme = theme.trim();
		if (accent != -1) {
			accent &= 0xFFFFFF;
		}
		highlight = Highlight.of(highlight).id;
		if (Boolean.FALSE.equals(highlightAnimated)) {
			highlight = Highlight.SIMPLE.id;
		}
		highlightAnimated = null;
		cursorStyle = Cursor.of(cursorStyle).id;
		if (highlightColor != -1) {
			highlightColor &= 0xFFFFFF;
		}
		scrollSpeed = clamp(scrollSpeed, MIN_SCROLL_SPEED, MAX_SCROLL_SPEED);
		handSway = clamp(handSway, 0, 100);
		volume = clamp(volume, 0, 100);
		soundTheme = soundTheme == null ? "" : soundTheme.trim();
		localPhotosMb = clamp(localPhotosMb, 50, 20_000);
		if (java.util.Arrays.stream(CAMERA_TIMERS).noneMatch(t -> t == cameraTimer)) {
			cameraTimer = 0;
		}
		return this;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	public String theme() {
		return theme;
	}

	/** -1 = a cor do tema. */
	public int accent() {
		return accent;
	}

	public Highlight highlight() {
		return Highlight.of(highlight);
	}

	public Cursor cursorStyle() {
		return Cursor.of(cursorStyle);
	}

	/** -1 = automática. */
	public int highlightColor() {
		return highlightColor;
	}

	/** O destaque anda (ondula e cresce na troca de item): todos menos o Simples, que é parado. */
	public boolean highlightAnimated() {
		return highlight() != Highlight.SIMPLE;
	}

	public int scrollSpeed() {
		return scrollSpeed;
	}

	public int handSway() {
		return handSway;
	}

	public boolean controlsHint() {
		return controlsHint;
	}

	public boolean uiSounds() {
		return uiSounds;
	}

	public boolean keyboardSounds() {
		return keyboardSounds;
	}

	public int volume() {
		return volume;
	}

	/** Vazio = os sons do tema em uso. */
	public String soundTheme() {
		return soundTheme;
	}

	public boolean notifyBanner() {
		return notifyBanner;
	}

	public boolean notifySound() {
		return notifySound;
	}

	// Os setters devolvem se algo mudou (o PhoneOS só salva e redesenha nesse caso).

	boolean setTheme(String id) {
		if (id.equals(theme)) {
			return false;
		}
		theme = id;
		return true;
	}

	boolean setAccent(int rgb) {
		int v = rgb == -1 ? -1 : rgb & 0xFFFFFF;
		if (v == accent) {
			return false;
		}
		accent = v;
		return true;
	}

	boolean setHighlight(Highlight h) {
		if (h.id.equals(highlight)) {
			return false;
		}
		highlight = h.id;
		return true;
	}

	boolean setCursorStyle(Cursor c) {
		if (c.id.equals(cursorStyle)) {
			return false;
		}
		cursorStyle = c.id;
		return true;
	}

	boolean setHighlightColor(int rgb) {
		int v = rgb == -1 ? -1 : rgb & 0xFFFFFF;
		if (v == highlightColor) {
			return false;
		}
		highlightColor = v;
		return true;
	}

	boolean setScrollSpeed(int v) {
		v = clamp(v, MIN_SCROLL_SPEED, MAX_SCROLL_SPEED);
		if (v == scrollSpeed) {
			return false;
		}
		scrollSpeed = v;
		return true;
	}

	boolean setHandSway(int v) {
		v = clamp(v, 0, 100);
		if (v == handSway) {
			return false;
		}
		handSway = v;
		return true;
	}

	boolean setControlsHint(boolean v) {
		if (v == controlsHint) {
			return false;
		}
		controlsHint = v;
		return true;
	}

	boolean setUiSounds(boolean v) {
		if (v == uiSounds) {
			return false;
		}
		uiSounds = v;
		return true;
	}

	boolean setKeyboardSounds(boolean v) {
		if (v == keyboardSounds) {
			return false;
		}
		keyboardSounds = v;
		return true;
	}

	boolean setVolume(int v) {
		v = clamp(v, 0, 100);
		if (v == volume) {
			return false;
		}
		volume = v;
		return true;
	}

	boolean setSoundTheme(String id) {
		String v = id == null ? "" : id;
		if (v.equals(soundTheme)) {
			return false;
		}
		soundTheme = v;
		return true;
	}

	/** Os outros jogadores veem o celular na mão deste (Ajustes > Privacidade). */
	public boolean showToOthers() {
		return showToOthers;
	}

	boolean setShowToOthers(boolean v) {
		if (v == showToOthers) {
			return false;
		}
		showToOthers = v;
		return true;
	}

	boolean setNotifyBanner(boolean v) {
		if (v == notifyBanner) {
			return false;
		}
		notifyBanner = v;
		return true;
	}

	boolean setNotifySound(boolean v) {
		if (v == notifySound) {
			return false;
		}
		notifySound = v;
		return true;
	}

	public boolean localCopies() {
		return localCopies;
	}

	boolean setLocalCopies(boolean v) {
		if (v == localCopies) {
			return false;
		}
		localCopies = v;
		return true;
	}

	public int localPhotosMb() {
		return localPhotosMb;
	}

	public boolean cameraGrid() {
		return cameraGrid;
	}

	boolean setCameraGrid(boolean v) {
		if (v == cameraGrid) {
			return false;
		}
		cameraGrid = v;
		return true;
	}

	/** Segundos do temporizador da Câmera (0 = desligado). */
	public int cameraTimer() {
		return cameraTimer;
	}

	boolean setCameraTimer(int seconds) {
		if (seconds == cameraTimer || java.util.Arrays.stream(CAMERA_TIMERS).noneMatch(t -> t == seconds)) {
			return false;
		}
		cameraTimer = seconds;
		return true;
	}
}
