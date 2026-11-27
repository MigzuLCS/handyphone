package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * App Ajustes: uma lista de seções (como num celular de verdade), e cada seção abre a sua página. Todas as opções moram em
 * {@link PhoneSettings} e mudam por {@link PhoneOS#changeSettings}, que salva.
 */
final class SettingsApp extends AppContent {
	enum Page { MAIN, APPEARANCE, NAVIGATION, POINTER, SOUNDS, NOTIFICATIONS, PRIVACY, SHORTCUT }

	/** Cores de destaque que o jogador pode escolher (além de "automática" = a cor de cada app). */
	static final int[] ACCENTS = {0xFF7A1A, 0xEF4444, 0xEC4899, 0x8B5CF6, 0x3B82F6, 0x06B6D4, 0x22C55E, 0xEAB308};

	private static final float MARGIN = 8f, GAP = 3.5f;
	private static final float NAV_H = 30f, THEME_H = 31f, TOGGLE_H = 28f, SLIDER_H = 40f;
	private static final float HEADER_H = 22f;

	private Page page = Page.MAIN;
	private float scroll, maxScroll;

	/** Barras deslizantes do último desenho: arrastar com o botão apertado muda o valor. */
	private record Slider(String key, float x, float w, int min, int max, int step, IntConsumer set) {
		void setFrom(float px) {
			float t = Math.max(0f, Math.min(1f, (px - x) / w));
			int raw = Math.round(min + t * (max - min));
			set.accept(Math.round((raw - min) / (float) step) * step + min);
		}
	}

	private final List<Slider> sliders = new ArrayList<>();

	Page page() {
		return page;
	}

	void open(Page p) {
		page = p;
		scroll = 0;
		// Nenhum campo de texto fica ativo fora da página dele (senão o teclado seguiria "digitando" num campo invisível).
		focus = null;
	}

	@Override
	void opened(PhoneOS os) {
		open(Page.MAIN);
	}

	@Override
	boolean back(PhoneOS os) {
		if (focus != null) {
			// Voltar com o campo hex em uso só encerra a digitação (como num celular de verdade).
			focus = null;
			return true;
		}
		if (page == Page.MAIN) {
			return false;
		}
		if (page == Page.SHORTCUT) {
			os.shortcutKeys().cancelCapture();
		}
		open(Page.MAIN);
		return true;
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		float next = Math.max(0, Math.min(scroll - amount * SCROLL_STEP, maxScroll));
		if (Math.abs(next - scroll) > 0.01f) {
			scroll = next;
			os.playScrollTick();
		}
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		if (key == PhoneOS.Key.ENTER && focus != null) {
			// Enter confirma o código hex e encerra a digitação (a cor já valeu ao completar os 6 dígitos).
			focus = null;
			os.playSound(PhoneSound.KEY_ENTER);
			return;
		}
		super.key(os, key);
	}

	@Override
	void pointerMoved(PhoneOS os, float x, float y) {
		for (Slider s : sliders) {
			if (isPressed(s.key)) {
				s.setFrom(x);
			}
		}
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		sliders.clear();
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		float top = y;
		java.awt.Shape oldClip = g.getClip();
		if (page != Page.MAIN) {
			header(g, os, rx, y, rw, title(page));
			top = y + HEADER_H;
			g.clip(new java.awt.geom.Rectangle2D.Float(x, top, w, h - HEADER_H));
		}
		float viewH = y + h - top;
		float cy = top + 4f - scroll;
		float end = switch (page) {
			case MAIN -> drawMain(g, os, rx, cy, rw);
			case APPEARANCE -> drawAppearance(g, os, rx, cy, rw, accent);
			case NAVIGATION -> drawNavigation(g, os, rx, cy, rw, accent);
			case POINTER -> drawPointer(g, os, rx, cy, rw, accent);
			case SOUNDS -> drawSounds(g, os, rx, cy, rw, accent);
			case NOTIFICATIONS -> drawNotifications(g, os, rx, cy, rw, accent);
			case PRIVACY -> drawPrivacy(g, os, rx, cy, rw, accent);
			case SHORTCUT -> drawShortcut(g, os, rx, cy, rw, accent);
		};
		maxScroll = Math.max(0, end + scroll + 6f - top - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		g.setClip(oldClip);
	}

	private static String title(Page p) {
		return switch (p) {
			case APPEARANCE -> Lang.t("handyphone.settings.section.appearance");
			case NAVIGATION -> Lang.t("handyphone.settings.section.navigation");
			case POINTER -> Lang.t("handyphone.settings.section.pointer");
			case SOUNDS -> Lang.t("handyphone.settings.section.sounds");
			case NOTIFICATIONS -> Lang.t("handyphone.settings.section.notifications");
			case PRIVACY -> Lang.t("handyphone.settings.section.privacy");
			case SHORTCUT -> Lang.t("handyphone.settings.section.shortcut");
			case MAIN -> "";
		};
	}

	/** Cabeçalho das páginas: seta de voltar e o nome da seção. */
	private void header(Graphics2D g, PhoneOS os, float rx, float y, float rw, String title) {
		boolean down = hit("settings:back", rx - 4, y, 26, HEADER_H, PhoneSound.BUTTON, () -> {
			focus = null; // a seta da página sempre volta, mesmo com o campo hex em uso
			back(os);
			os.navigatedBack();
		});
		g.setColor(down ? MUTED : INK);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float cy = y + HEADER_H / 2f + 1f;
		GeneralPath chev = new GeneralPath();
		chev.moveTo(rx + 6, cy - 5);
		chev.lineTo(rx + 1.5f, cy);
		chev.lineTo(rx + 6, cy + 5);
		g.draw(chev);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, fit(g, title, rw - 20), rx + 13, cy + 3.6f);
		g.setColor(LINE);
		g.setStroke(new BasicStroke(0.6f));
		g.draw(new Line2D.Float(rx, y + HEADER_H - 0.5f, rx + rw, y + HEADER_H - 0.5f));
	}

	// ------------------------------------------------------------------ páginas

	private float drawMain(Graphics2D g, PhoneOS os, float rx, float y, float rw) {
		PhoneSettings s = os.settings();
		String themeName = os.theme.name();
		String sounds = !s.uiSounds() && !s.keyboardSounds() || s.volume() == 0
			? Lang.t("handyphone.settings.summary.off") : Lang.t("handyphone.settings.summary.volume", percent(s.volume()));
		String notif = s.notifyBanner() && s.notifySound() ? Lang.t("handyphone.settings.summary.on")
			: !s.notifyBanner() && !s.notifySound() ? Lang.t("handyphone.settings.summary.off") : Lang.t("handyphone.settings.summary.partial");
		Object[][] rows = {
			{Page.APPEARANCE, 0xF97316, themeName},
			{Page.NAVIGATION, 0x3B82F6, Lang.t("handyphone.settings.summary.navigation", percent(s.scrollSpeed()), percent(s.handSway()))},
			{Page.POINTER, 0x8B5CF6, Lang.t("handyphone.settings.summary.pointer",
				Lang.t("handyphone.settings.highlight." + s.highlight().id()), Lang.t("handyphone.settings.cursor." + s.cursorStyle().id()))},
			{Page.SOUNDS, 0xEC4899, sounds},
			{Page.NOTIFICATIONS, 0xEF4444, notif},
			{Page.PRIVACY, 0x10B981, s.showToOthers() ? Lang.t("handyphone.settings.summary.privacy_shown")
				: Lang.t("handyphone.settings.summary.privacy_hidden")},
			{Page.SHORTCUT, 0x64748B, os.useKeyName()},
		};
		float ry = y + 2f;
		for (Object[] row : rows) {
			Page p = (Page) row[0];
			navRow(g, os, "page:" + p.name().toLowerCase(java.util.Locale.ROOT), p, new Color((Integer) row[1]), title(p), (String) row[2], rx, ry, rw);
			ry += NAV_H + GAP;
		}
		return ry;
	}

	private float drawAppearance(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		float ry = label(g, Lang.t("handyphone.settings.style"), rx, y);
		for (Theme theme : os.themes) {
			boolean selected = theme == os.theme;
			boolean down = hit("theme:" + theme.id(), rx, ry, rw, THEME_H, PhoneSound.SELECT, () -> os.setTheme(theme.id()));
			card(g, rx, ry, rw, THEME_H, down, selected ? accent : null);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.6f));
			Ui.text(g, theme.name(), rx + 8, ry + 13.2f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.2f));
			Ui.text(g, fit(g, theme.description(), rw - 34), rx + 8, ry + 24f);
			radio(g, rx + rw - 14, ry + THEME_H / 2f, selected, accent);
			ry += THEME_H + GAP;
		}
		ry = label(g, Lang.t("handyphone.settings.accent"), rx, ry + 6f);
		int accentRgb = os.settings().accent();
		ry = colorRow(g, os, "accent", accentHex, accentRgb, -1, rgb -> os.changeSettings(x -> x.setAccent(rgb)), rx, ry, rw, accent);
		ry = hint(g, accentRgb == -1 ? Lang.t("handyphone.settings.accent.auto") : Lang.t("handyphone.settings.accent.fixed"), rx, ry, rw);

		return ry;
	}

	/** Campos do código hex das duas cores (só aceitam 0-9 e A-F; com 6 dígitos a cor vale na hora). */
	private final Field accentHex = new Field(6, false), highlightHex = new Field(6, false);

	private static float hint(Graphics2D g, String text, float rx, float ry, float rw) {
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.0f));
		Ui.text(g, fit(g, text, rw - 4), rx + 2, ry + 7f);
		return ry + 10f;
	}

	/**
	 * Escolha de cor: "automática" + as cores prontas numa linha e, embaixo, o código hex para qualquer outra cor.
	 *
	 * @param chosen  cor salva (-1 = automática)
	 * @param autoRgb cor que a automática dá agora (-1 = mostrar as cores dos apps em fatias)
	 */
	private float colorRow(Graphics2D g, PhoneOS os, String key, Field hex, int chosen, int autoRgb, IntConsumer set,
		float rx, float ry, float rw, Color accent) {
		float swH = 28f, hexH = 22f, rowH = swH + hexH + 2f;
		card(g, rx, ry, rw, rowH, false, null);
		int count = ACCENTS.length + 1;
		float cell = (rw - 8f) / count, d = Math.min(15f, cell - 3f);
		for (int i = 0; i < count; i++) {
			int rgb = i == 0 ? -1 : ACCENTS[i - 1];
			float cx = rx + 4f + cell * i + cell / 2f, cyy = ry + swH / 2f + 1f;
			// Área de toque justa em volta da bolinha: o destaque da roda fica redondo, colado nela.
			float box = Math.min(cell, d + 5f);
			boolean down = hit(key + ":" + (i == 0 ? "auto" : Integer.toHexString(rgb)), cx - box / 2f, cyy - box / 2f, box, box, PhoneSound.SELECT, () -> {
				set.accept(rgb);
				focus = null;
				hex.set("");
			});
			float dd = down ? d * 0.86f : d;
			if (rgb == -1 && autoRgb < 0) {
				// Automática (cor do tema): um círculo com as cores dos apps em fatias.
				int[] slices = {0x3B82F6, 0x22C55E, 0xF59E0B, 0xEC4899};
				for (int k = 0; k < slices.length; k++) {
					g.setColor(new Color(slices[k]));
					g.fill(new java.awt.geom.Arc2D.Float(cx - dd / 2f, cyy - dd / 2f, dd, dd, 90 + k * 90, 90, java.awt.geom.Arc2D.PIE));
				}
			} else if (rgb == -1) {
				// Automática (tom calculado): a própria cor, com um "A" para não confundir com uma cor fixa.
				g.setColor(new Color(autoRgb));
				g.fill(new Ellipse2D.Float(cx - dd / 2f, cyy - dd / 2f, dd, dd));
				g.setColor(Color.WHITE);
				g.setFont(Ui.font(Font.BOLD, dd * 0.55f));
				Ui.centered(g, "A", cx, cyy + dd * 0.2f);
			} else {
				g.setColor(new Color(rgb));
				g.fill(new Ellipse2D.Float(cx - dd / 2f, cyy - dd / 2f, dd, dd));
			}
			if (rgb == chosen) {
				// Cor escolhida: um aro branco por dentro da bolinha (por fora ele se confundia com o destaque da roda).
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(1.5f));
				float in = dd * 0.56f;
				g.draw(new Ellipse2D.Float(cx - in / 2f, cyy - in / 2f, in, in));
			}
		}
		// Código hex: o que não é 0-9/A-F sai; com 6 dígitos, a cor vale na hora.
		String clean = hex.text.toString().toUpperCase(java.util.Locale.ROOT).replaceAll("[^0-9A-F]", "");
		if (clean.length() > 6) {
			clean = clean.substring(0, 6);
		}
		if (!clean.equals(hex.text.toString())) {
			hex.set(clean);
		}
		boolean custom = chosen >= 0 && java.util.Arrays.stream(ACCENTS).noneMatch(c -> c == chosen);
		if (focus != hex && clean.isEmpty() && custom) {
			hex.set(String.format(java.util.Locale.ROOT, "%06X", chosen));
			clean = hex.text.toString();
		}
		if (clean.length() == 6) {
			int typed = Integer.parseInt(clean, 16);
			if (focus == hex && typed != chosen) {
				set.accept(typed);
			}
		}
		float hy = ry + swH + 1f;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9f));
		Ui.text(g, "#", rx + 9, hy + 13f);
		float fx = rx + 18, fw = rw - 18 - 30;
		field(g, os, key + ":hex", hex, "RRGGBB", fx, hy + 2f, fw, 17f, accent);
		int preview = chosen >= 0 ? chosen : autoRgb;
		if (preview >= 0) {
			g.setColor(new Color(preview));
			g.fill(new RoundRectangle2D.Float(rx + rw - 24, hy + 3f, 16f, 15f, 6f, 6f));
			g.setColor(LINE);
			g.setStroke(new BasicStroke(0.7f));
			g.draw(new RoundRectangle2D.Float(rx + rw - 24, hy + 3f, 16f, 15f, 6f, 6f));
		}
		return ry + rowH + 3f;
	}

	private float drawNavigation(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		PhoneSettings s = os.settings();
		float ry = y + 2f;
		ry = slider(g, os, "slider:scroll", Lang.t("handyphone.settings.scroll_speed"), percent(s.scrollSpeed()), s.scrollSpeed(),
			PhoneSettings.MIN_SCROLL_SPEED, PhoneSettings.MAX_SCROLL_SPEED, 25, v -> os.changeSettings(x -> x.setScrollSpeed(v)), rx, ry, rw, accent);
		ry = slider(g, os, "slider:sway", Lang.t("handyphone.settings.hand_sway"), percent(s.handSway()), s.handSway(), 0, 100, 10,
			v -> os.changeSettings(x -> x.setHandSway(v)), rx, ry, rw, accent);
		toggleRow(g, rx, ry, rw, accent, "toggle:hint", Lang.t("handyphone.settings.controls_hint.title"),
			Lang.t("handyphone.settings.controls_hint.subtitle"), s.controlsHint(), PhoneSound.SELECT,
			() -> os.changeSettings(x -> x.setControlsHint(!x.controlsHint())));
		return ry + TOGGLE_H + GAP;
	}

	/** Destaque da roda (modo "na mão") e cursor (modo expandido): figurinhas, a cor do destaque e o cursor. */
	private float drawPointer(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		PhoneSettings s = os.settings();
		float ry = y;
		// Como o item escolhido pela roda é marcado (celular na mão): uma figurinha por estilo.
		ry = label(g, Lang.t("handyphone.settings.highlight"), rx, ry + 2f);
		PhoneSettings.Highlight[] styles = PhoneSettings.Highlight.values();
		int ringRgb = os.highlightColor();
		for (int i = 0; i < styles.length; i++) {
			PhoneSettings.Highlight value = styles[i];
			tile(g, "highlight:" + value.id(), Lang.t("handyphone.settings.highlight." + value.id()), s.highlight() == value,
				rx + i * (tileW(rw) + GAP), ry, tileW(rw), accent, () -> os.changeSettings(x -> x.setHighlight(value)),
				(cx, cy) -> highlightPicture(g, value, cx, cy, ringRgb));
		}
		ry += TILE_H + GAP;
		ry = hint(g, Lang.t("handyphone.settings.highlight." + s.highlight().id() + ".subtitle"), rx, ry, rw) + 2f;
		ry = label(g, Lang.t("handyphone.settings.highlight_color"), rx, ry + 6f);
		int highlightRgb = s.highlightColor();
		ry = colorRow(g, os, "highlight_color", highlightHex, highlightRgb, os.highlightColor(),
			rgb -> os.changeSettings(x -> x.setHighlightColor(rgb)), rx, ry, rw, accent);
		ry = hint(g, highlightRgb == -1 ? Lang.t("handyphone.settings.highlight_color.auto") : Lang.t("handyphone.settings.highlight_color.fixed"),
			rx, ry, rw);

		// Cursor do modo expandido (o desenho no vidro onde o mouse cai).
		ry = label(g, Lang.t("handyphone.settings.cursor"), rx, ry + 6f);
		PhoneSettings.Cursor[] cursors = PhoneSettings.Cursor.values();
		for (int i = 0; i < cursors.length; i++) {
			PhoneSettings.Cursor value = cursors[i];
			tile(g, "cursor:" + value.id(), Lang.t("handyphone.settings.cursor." + value.id()), s.cursorStyle() == value,
				rx + i * (tileW(rw) + GAP), ry, tileW(rw), accent, () -> os.changeSettings(x -> x.setCursorStyle(value)),
				(cx, cy) -> cursorPicture(g, value, cx, cy));
		}
		ry += TILE_H + GAP;
		return hint(g, Lang.t("handyphone.settings.cursor.subtitle"), rx, ry, rw);
	}

	private static final float TILE_H = 56f, PICTURE_H = 40f;

	/** Largura de uma figurinha: três por linha. */
	private static float tileW(float rw) {
		return (rw - 2 * GAP) / 3f;
	}

	private interface Picture {
		void draw(float cx, float cy);
	}

	/**
	 * Figurinha de escolha: um quadradinho com o desenho da opção sobre um fundo escuro (o destaque e o cursor de verdade aparecem
	 * sobre a tela) e o nome embaixo. A escolhida tem a borda e o nome na cor de destaque.
	 */
	private void tile(Graphics2D g, String key, String name, boolean selected, float x, float y, float w, Color accent, Runnable action,
		Picture picture) {
		boolean down = hit(key, x, y, w, TILE_H, PhoneSound.SELECT, action);
		card(g, x, y, w, TILE_H, down, selected ? accent : null);
		float pad = 4f;
		RoundRectangle2D pic = new RoundRectangle2D.Float(x + pad, y + pad, w - 2 * pad, PICTURE_H - pad, 6f, 6f);
		g.setColor(down ? new Color(0x2A2E38) : new Color(0x3A3F4C));
		g.fill(pic);
		java.awt.Shape oldClip = g.getClip();
		g.clip(pic);
		picture.draw(x + w / 2f, y + pad + (PICTURE_H - pad) / 2f);
		g.setClip(oldClip);
		g.setColor(selected ? accent : INK);
		g.setFont(Ui.font(Font.BOLD, 8f));
		Ui.centered(g, fit(g, name, w - 6), x + w / 2f, y + TILE_H - 5.5f);
	}

	/** Prévia do destaque: um item redondo e a borda em volta, ondulada (fluido), quase lisa (calmo) ou lisa (simples). */
	private static void highlightPicture(Graphics2D g, PhoneSettings.Highlight style, float cx, float cy, int rgb) {
		Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0xC9CED8));
		g.fill(new Ellipse2D.Float(cx - 6f, cy - 6f, 12f, 12f));
		float amp = switch (style) {
			case FLUID -> 1.3f;
			case CALM -> 0.55f;
			case SIMPLE -> 0f;
		};
		int lobes = style == PhoneSettings.Highlight.FLUID ? 5 : 4;
		Color c = new Color(rgb);
		if (style != PhoneSettings.Highlight.SIMPLE) {
			// Brilho por fora, como no jogo: anéis cada vez mais largos e transparentes.
			for (int k = 3; k >= 1; k--) {
				g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 34));
				g.setStroke(new BasicStroke(1.6f + k * 1.8f));
				g.draw(ring(cx, cy, 10.5f + k * 0.9f, amp, lobes));
			}
		}
		g.setColor(c);
		g.setStroke(new BasicStroke(1.7f));
		g.draw(ring(cx, cy, 10.5f, amp, lobes));
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, aa);
	}

	private static GeneralPath ring(float cx, float cy, float r, float amp, int lobes) {
		GeneralPath p = new GeneralPath();
		int n = 72;
		for (int i = 0; i <= n; i++) {
			double a = i * 2 * Math.PI / n;
			double rr = r + amp * Math.sin(a * lobes + 0.6);
			float px = (float) (cx + Math.cos(a) * rr), py = (float) (cy + Math.sin(a) * rr);
			if (i == 0) {
				p.moveTo(px, py);
			} else {
				p.lineTo(px, py);
			}
		}
		p.closePath();
		return p;
	}

	/** Prévia do cursor, no mesmo desenho do jogo. */
	private static void cursorPicture(Graphics2D g, PhoneSettings.Cursor style, float cx, float cy) {
		switch (style) {
			case CROSS -> CursorArt.cross(g, cx, cy, 8f);
			case HAND -> {
				float scale = 1.3f;
				// A mão (de 0,1 a 15,6 de largura e 20,5 de altura) centrada na figurinha; a ponta do dedo fica em (5,5, 0,55).
				CursorArt.hand(g, cx - (7.85f - 5.5f) * scale, cy - (10.3f - 0.55f) * scale, scale);
			}
		}
	}

	private float drawSounds(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		PhoneSettings s = os.settings();
		float ry = y + 2f;
		ry = slider(g, os, "slider:volume", Lang.t("handyphone.settings.volume"), percent(s.volume()), s.volume(), 0, 100, 10,
			v -> {
				if (os.changeSettings(x -> x.setVolume(v)) && v > 0) {
					os.playSound(PhoneSound.SELECT);
				}
			}, rx, ry, rw, accent);
		toggleRow(g, rx, ry, rw, accent, "sound:ui", Lang.t("handyphone.settings.ui_sounds.title"),
			Lang.t("handyphone.settings.ui_sounds.subtitle"), s.uiSounds(), null, () -> {
				boolean next = !os.uiSounds();
				os.setUiSounds(next);
				if (next) {
					os.playSound(PhoneSound.SELECT);
				}
			});
		ry += TOGGLE_H + GAP;
		toggleRow(g, rx, ry, rw, accent, "sound:keyboard", Lang.t("handyphone.settings.keyboard_sounds.title"),
			Lang.t("handyphone.settings.keyboard_sounds.subtitle"), s.keyboardSounds(), PhoneSound.SELECT,
			() -> os.setKeyboardSounds(!os.keyboardSounds()));
		ry += TOGGLE_H + GAP;

		// De qual tema vêm os sons (o visual e os sons separados). Tocar numa opção toca uma amostra.
		ry = label(g, Lang.t("handyphone.settings.sound_theme"), rx, ry + 4f);
		String chosen = s.soundTheme();
		float rowH = 24f;
		for (int i = -1; i < os.themes.size(); i++) {
			String id = i < 0 ? "" : os.themes.get(i).id();
			String name = i < 0 ? Lang.t("handyphone.settings.sound_theme.auto") : os.themes.get(i).name();
			boolean selected = id.equals(chosen);
			boolean down = hit("sound_theme:" + (id.isEmpty() ? "auto" : id), rx, ry, rw, rowH, null, () -> {
				os.changeSettings(x -> x.setSoundTheme(id));
				os.playSound(PhoneSound.THEME_CHANGE);
			});
			card(g, rx, ry, rw, rowH, down, selected ? accent : null);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9f));
			Ui.text(g, fit(g, name, rw - 34), rx + 8, i < 0 ? ry + 10.5f : ry + 15f);
			if (i < 0) {
				g.setColor(MUTED);
				g.setFont(Ui.font(Font.PLAIN, 6.8f));
				Ui.text(g, fit(g, Lang.t("handyphone.settings.sound_theme.auto_subtitle", os.theme.name()), rw - 34), rx + 8, ry + 19.5f);
			}
			radio(g, rx + rw - 14, ry + rowH / 2f, selected, accent);
			ry += rowH + GAP;
		}
		return ry;
	}

	private float drawNotifications(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		PhoneSettings s = os.settings();
		float ry = y + 2f;
		toggleRow(g, rx, ry, rw, accent, "toggle:banner", Lang.t("handyphone.settings.notify_banner.title"),
			Lang.t("handyphone.settings.notify_banner.subtitle"), s.notifyBanner(), PhoneSound.SELECT,
			() -> os.changeSettings(x -> x.setNotifyBanner(!x.notifyBanner())));
		ry += TOGGLE_H + GAP;
		toggleRow(g, rx, ry, rw, accent, "toggle:notify_sound", Lang.t("handyphone.settings.notify_sound.title"),
			Lang.t("handyphone.settings.notify_sound.subtitle"), s.notifySound(), PhoneSound.SELECT, () -> {
				boolean next = !os.settings().notifySound();
				os.changeSettings(x -> x.setNotifySound(next));
				if (next) {
					os.playSound(PhoneSound.NOTIFICATION);
				}
			});
		return ry + TOGGLE_H + GAP;
	}

	private float drawPrivacy(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		PhoneSettings s = os.settings();
		float ry = y + 2f;
		toggleRow(g, rx, ry, rw, accent, "toggle:show_phone", Lang.t("handyphone.settings.show_phone.title"),
			Lang.t("handyphone.settings.show_phone.subtitle"), s.showToOthers(), PhoneSound.SELECT,
			() -> os.changeSettings(x -> x.setShowToOthers(!x.showToOthers())));
		ry += TOGGLE_H + GAP;
		ry = hint(g, Lang.t("handyphone.settings.show_phone.hint"), rx, ry, rw) + GAP * 2;
		// Cópia das fotos neste computador (ligada por padrão) e o botão para abrir a pasta.
		toggleRow(g, rx, ry, rw, accent, "toggle:local_copies", Lang.t("handyphone.settings.local_copies.title"),
			Lang.t("handyphone.settings.local_copies.subtitle"), s.localCopies(), PhoneSound.SELECT,
			() -> os.changeSettings(x -> x.setLocalCopies(!x.localCopies())));
		ry += TOGGLE_H + GAP;
		button(g, "local:open", Lang.t("handyphone.settings.local_copies.open"), rx, ry, rw, 22f, new Color(0x5B6170), os::openLocalFolder);
		ry += 22f + GAP;
		ry = hint(g, Lang.t("handyphone.settings.local_copies.hint", String.valueOf(s.localPhotosMb())), rx, ry, rw);
		return hint(g, Lang.t("handyphone.settings.local_copies.hint2"), rx, ry, rw);
	}

	private float drawShortcut(Graphics2D g, PhoneOS os, float rx, float y, float rw, Color accent) {
		ShortcutKeys keys = os.shortcutKeys();
		boolean listening = keys.capturing();
		float ry = y + 8f;
		// A tecla atual, desenhada como uma tecla de teclado.
		g.setFont(Ui.font(Font.BOLD, 15f));
		String name = listening ? "…" : os.useKeyName();
		float kw = Math.max(42f, Math.min(rw - 20, Ui.textWidth(g, name) + 24f)), kh = 38f, kx = rx + (rw - kw) / 2f;
		g.setColor(new Color(0xE3E5EC));
		g.fill(new RoundRectangle2D.Float(kx, ry + 3, kw, kh, 10, 10));
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(kx, ry, kw, kh - 1, 10, 10));
		g.setStroke(new BasicStroke(listening ? 1.4f : 0.8f));
		g.setColor(listening ? accent : LINE);
		g.draw(new RoundRectangle2D.Float(kx, ry, kw, kh - 1, 10, 10));
		g.setColor(INK);
		Ui.centered(g, fit(g, name, kw - 10), rx + rw / 2f, ry + kh / 2f + 4.5f);
		ry += kh + 14f;
		g.setFont(Ui.font(Font.PLAIN, 7.6f));
		g.setColor(listening ? accent : MUTED);
		Ui.centered(g, fit(g, listening ? Lang.t("handyphone.settings.shortcut.listening") : Lang.t("handyphone.settings.shortcut.hint"), rw), rx + rw / 2f, ry);
		ry += 11f;
		if (listening) {
			g.setColor(MUTED);
			Ui.centered(g, fit(g, Lang.t("handyphone.settings.shortcut.cancel_hint"), rw), rx + rw / 2f, ry);
			ry += 8f;
		} else {
			String conflict = keys.conflict();
			if (!conflict.isEmpty()) {
				g.setColor(new Color(0xC2410C));
				Ui.centered(g, fit(g, Lang.t("handyphone.settings.shortcut.conflict", conflict), rw), rx + rw / 2f, ry);
				ry += 8f;
			}
		}
		ry += 8f;
		if (listening) {
			button(g, "shortcut:cancel", Lang.t("handyphone.settings.shortcut.cancel"), rx, ry, rw, 22f, new Color(0x7A7D88), keys::cancelCapture);
		} else {
			button(g, "shortcut:change", Lang.t("handyphone.settings.shortcut.change"), rx, ry, rw, 22f, accent, keys::startCapture);
		}
		ry += 22f + GAP + 2f;
		if (!listening && !keys.isDefault()) {
			button(g, "shortcut:reset", Lang.t("handyphone.settings.shortcut.reset", keys.defaultName()), rx, ry, rw, 22f, new Color(0x7A7D88), keys::reset);
			ry += 22f + GAP;
		}
		return ry;
	}

	// ------------------------------------------------------------------ peças

	private static String percent(int v) {
		return Lang.t("handyphone.settings.percent", Integer.toString(v));
	}

	private static float label(Graphics2D g, String text, float rx, float y) {
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		Ui.text(g, text, rx + 2, y + 9f);
		return y + 14f;
	}

	/** Fundo branco de linha; {@code outline} != null = selecionada (borda na cor de destaque). */
	private static void card(Graphics2D g, float x, float y, float w, float h, boolean down, Color outline) {
		RoundRectangle2D row = new RoundRectangle2D.Float(x, y, w, h, 8, 8);
		g.setColor(down ? PRESSED : Color.WHITE);
		g.fill(row);
		g.setStroke(new BasicStroke(outline != null ? 1.3f : 0.7f));
		g.setColor(outline != null ? outline : LINE);
		g.draw(row);
	}

	private static void radio(Graphics2D g, float cx, float cy, boolean selected, Color accent) {
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(selected ? accent : new Color(0xA9ABB5));
		g.draw(new Ellipse2D.Float(cx - 5.5f, cy - 5.5f, 11, 11));
		if (selected) {
			g.fill(new Ellipse2D.Float(cx - 3f, cy - 3f, 6, 6));
		}
	}

	/** Linha da lista principal: ícone da seção, nome, resumo do que está escolhido e a seta. */
	private void navRow(Graphics2D g, PhoneOS os, String key, Page p, Color color, String title, String value, float rx, float ry, float rw) {
		boolean down = hit(key, rx, ry, rw, NAV_H, PhoneSound.LIST_ITEM, () -> open(p));
		card(g, rx, ry, rw, NAV_H, down, null);
		float s = 18f, ix = rx + 6f, iy = ry + (NAV_H - s) / 2f;
		g.setColor(color);
		g.fill(new RoundRectangle2D.Float(ix, iy, s, s, 6, 6));
		sectionIcon(g, p, ix + s / 2f, iy + s / 2f);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.2f));
		Ui.text(g, fit(g, title, rw - 44), rx + 30, ry + 12.5f);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.0f));
		Ui.text(g, fit(g, value, rw - 44), rx + 30, ry + 22.5f);
		g.setColor(new Color(0xA9ABB5));
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float cx = rx + rw - 9, cy = ry + NAV_H / 2f;
		GeneralPath chev = new GeneralPath();
		chev.moveTo(cx - 2, cy - 4);
		chev.lineTo(cx + 2, cy);
		chev.lineTo(cx - 2, cy + 4);
		g.draw(chev);
	}

	/** Ícones brancos simples das seções, centrados em (cx, cy), cabendo em ~11 pontos. */
	private static void sectionIcon(Graphics2D g, Page p, float cx, float cy) {
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (p) {
			case APPEARANCE -> {
				// Pincel: gota de tinta.
				GeneralPath drop = new GeneralPath();
				drop.moveTo(cx, cy - 5.5f);
				drop.curveTo(cx + 4.5f, cy - 0.5f, cx + 4.5f, cy + 4.8f, cx, cy + 4.8f);
				drop.curveTo(cx - 4.5f, cy + 4.8f, cx - 4.5f, cy - 0.5f, cx, cy - 5.5f);
				g.fill(drop);
			}
			case NAVIGATION -> {
				// Roda do mouse: setas para cima e para baixo.
				GeneralPath arrows = new GeneralPath();
				arrows.moveTo(cx - 3.5f, cy - 1.5f);
				arrows.lineTo(cx, cy - 5f);
				arrows.lineTo(cx + 3.5f, cy - 1.5f);
				arrows.moveTo(cx - 3.5f, cy + 1.5f);
				arrows.lineTo(cx, cy + 5f);
				arrows.lineTo(cx + 3.5f, cy + 1.5f);
				g.draw(arrows);
			}
			case SOUNDS -> {
				GeneralPath spk = new GeneralPath();
				spk.moveTo(cx - 5f, cy - 2f);
				spk.lineTo(cx - 2.5f, cy - 2f);
				spk.lineTo(cx + 0.5f, cy - 4.8f);
				spk.lineTo(cx + 0.5f, cy + 4.8f);
				spk.lineTo(cx - 2.5f, cy + 2f);
				spk.lineTo(cx - 5f, cy + 2f);
				spk.closePath();
				g.fill(spk);
				g.draw(new java.awt.geom.Arc2D.Float(cx - 1.5f, cy - 3.5f, 7f, 7f, -50, 100, java.awt.geom.Arc2D.OPEN));
			}
			case NOTIFICATIONS -> {
				GeneralPath bell = new GeneralPath();
				bell.moveTo(cx - 4.5f, cy + 2.8f);
				bell.curveTo(cx - 3.2f, cy + 1f, cx - 3.6f, cy - 4.8f, cx, cy - 4.8f);
				bell.curveTo(cx + 3.6f, cy - 4.8f, cx + 3.2f, cy + 1f, cx + 4.5f, cy + 2.8f);
				bell.closePath();
				g.fill(bell);
				g.fill(new Ellipse2D.Float(cx - 1.4f, cy + 3.3f, 2.8f, 2.8f));
			}
			case PRIVACY -> {
				// Um olho.
				GeneralPath eye = new GeneralPath();
				eye.moveTo(cx - 5.5f, cy);
				eye.quadTo(cx, cy - 6f, cx + 5.5f, cy);
				eye.quadTo(cx, cy + 6f, cx - 5.5f, cy);
				eye.closePath();
				g.draw(eye);
				g.fill(new Ellipse2D.Float(cx - 1.8f, cy - 1.8f, 3.6f, 3.6f));
			}
			case POINTER -> {
				// Um item com o destaque em volta.
				g.fill(new Ellipse2D.Float(cx - 2f, cy - 2f, 4f, 4f));
				g.draw(new Ellipse2D.Float(cx - 4.8f, cy - 4.8f, 9.6f, 9.6f));
			}
			case SHORTCUT -> {
				g.draw(new RoundRectangle2D.Float(cx - 5f, cy - 4f, 10f, 8f, 3f, 3f));
				g.fill(new java.awt.geom.Rectangle2D.Float(cx - 2.5f, cy + 0.6f, 5f, 1.3f));
			}
			case MAIN -> { }
		}
	}

	private void toggleRow(Graphics2D g, float rx, float ry, float rw, Color accent, String key, String title, String subtitle,
		boolean enabled, PhoneSound sound, Runnable action) {
		boolean down = hit(key, rx, ry, rw, TOGGLE_H, sound, action);
		card(g, rx, ry, rw, TOGGLE_H, down, null);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.2f));
		Ui.text(g, fit(g, title, rw - 42), rx + 8, ry + 12f);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.0f));
		Ui.text(g, fit(g, subtitle, rw - 42), rx + 8, ry + 21.8f);
		// Chave seletora (liga/desliga) à direita.
		float sw = 22f, sh = 12f, sx = rx + rw - sw - 7f, sy = ry + (TOGGLE_H - sh) / 2f;
		g.setColor(enabled ? accent : new Color(0xB8BAC4));
		g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
		float knob = sh - 3.2f;
		float kx = enabled ? sx + sw - knob - 1.6f : sx + 1.6f;
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(kx, sy + 1.6f, knob, knob));
	}

	/**
	 * Barra deslizante com "−" e "+" nas pontas (a roda, no modo "na mão", só toca: os botões dão o passo). Tocar ou arrastar
	 * na barra escolhe o valor. Devolve o y da próxima linha.
	 */
	private float slider(Graphics2D g, PhoneOS os, String key, String title, String valueText, int value, int min, int max, int step,
		IntConsumer set, float rx, float ry, float rw, Color accent) {
		card(g, rx, ry, rw, SLIDER_H, false, null);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.2f));
		Ui.text(g, fit(g, title, rw - 50), rx + 8, ry + 12.5f);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 8f));
		float vw = Ui.textWidth(g, valueText);
		Ui.text(g, valueText, rx + rw - 8 - vw, ry + 12.5f);

		float by = ry + 27f, btn = 14f;
		IntConsumer clamped = v -> set.accept(Math.max(min, Math.min(max, v)));
		stepButton(g, key + ":minus", false, rx + 5, by - btn / 2f, btn, () -> clamped.accept(value - step));
		stepButton(g, key + ":plus", true, rx + rw - 5 - btn, by - btn / 2f, btn, () -> clamped.accept(value + step));
		float bx = rx + 5 + btn + 6, bw = rw - 2 * (5 + btn + 6);
		Slider s = new Slider(key, bx, bw, min, max, step, clamped);
		sliders.add(s);
		hit(key, bx - 3, by - 7, bw + 6, 14, null, () -> s.setFrom(pointerX));
		float t = (value - min) / (float) (max - min);
		g.setColor(new Color(0xD9DBE2));
		g.fill(new RoundRectangle2D.Float(bx, by - 1.5f, bw, 3f, 3f, 3f));
		g.setColor(accent);
		g.fill(new RoundRectangle2D.Float(bx, by - 1.5f, bw * t, 3f, 3f, 3f));
		float kx = bx + bw * t;
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(kx - 5f, by - 5f, 10f, 10f));
		g.setColor(accent);
		g.setStroke(new BasicStroke(1.2f));
		g.draw(new Ellipse2D.Float(kx - 5f, by - 5f, 10f, 10f));
		return ry + SLIDER_H + GAP;
	}

	private void stepButton(Graphics2D g, String key, boolean plus, float x, float y, float d, Runnable action) {
		boolean down = hit(key, x - 2, y - 2, d + 4, d + 4, PhoneSound.SELECT, action);
		g.setColor(down ? PRESSED : new Color(0xEEF0F4));
		g.fill(new Ellipse2D.Float(x, y, d, d));
		g.setColor(INK);
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float cx = x + d / 2f, cy = y + d / 2f;
		g.draw(new Line2D.Float(cx - 3.2f, cy, cx + 3.2f, cy));
		if (plus) {
			g.draw(new Line2D.Float(cx, cy - 3.2f, cx, cy + 3.2f));
		}
	}
}
