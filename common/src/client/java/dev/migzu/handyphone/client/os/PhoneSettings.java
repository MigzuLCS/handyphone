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

	public static final int MIN_SCROLL_SPEED = 50, MAX_SCROLL_SPEED = 200;

	// Aparência
	private String theme = PhoneOS.DEFAULT_THEME;
	/** Cor do aparelho em 0xRRGGBB; -1 = a cor do próprio tema. */
	private int accent = -1;
	// Navegação
	private String highlight = Highlight.FLUID.id;
	private boolean highlightAnimated = true;
	/** Cor do destaque da roda em 0xRRGGBB; -1 = automática (um tom diferente da cor de destaque, para não sumir nos botões). */
	private int highlightColor = -1;
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

	public PhoneSettings copy() {
		PhoneSettings c = new PhoneSettings();
		c.theme = theme;
		c.accent = accent;
		c.highlight = highlight;
		c.highlightAnimated = highlightAnimated;
		c.highlightColor = highlightColor;
		c.scrollSpeed = scrollSpeed;
		c.handSway = handSway;
		c.controlsHint = controlsHint;
		c.uiSounds = uiSounds;
		c.keyboardSounds = keyboardSounds;
		c.volume = volume;
		c.soundTheme = soundTheme;
		c.notifyBanner = notifyBanner;
		c.notifySound = notifySound;
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
		if (highlightColor != -1) {
			highlightColor &= 0xFFFFFF;
		}
		scrollSpeed = clamp(scrollSpeed, MIN_SCROLL_SPEED, MAX_SCROLL_SPEED);
		handSway = clamp(handSway, 0, 100);
		volume = clamp(volume, 0, 100);
		soundTheme = soundTheme == null ? "" : soundTheme.trim();
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

	/** -1 = automática. */
	public int highlightColor() {
		return highlightColor;
	}

	public boolean highlightAnimated() {
		return highlightAnimated;
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

	boolean setHighlightColor(int rgb) {
		int v = rgb == -1 ? -1 : rgb & 0xFFFFFF;
		if (v == highlightColor) {
			return false;
		}
		highlightColor = v;
		return true;
	}

	boolean setHighlightAnimated(boolean v) {
		if (v == highlightAnimated) {
			return false;
		}
		highlightAnimated = v;
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
}
