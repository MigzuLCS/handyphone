package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * O miolo de um app, igual em todos os temas: o tema desenha a moldura e a barra
 * de título, e o app desenha dentro da área que recebe.
 *
 * <p>Toques funcionam por "áreas clicáveis": ao desenhar um botão, o app chama
 * {@link #hit} com o retângulo e a ação. O toque procura a área sob o dedo e roda
 * a ação quando o dedo solta em cima dela. Assim desenho e toque nunca divergem.
 */
abstract class AppContent {
	/** Campo de texto simples. */
	static final class Field {
		final StringBuilder text = new StringBuilder();
		final int max;
		final boolean digitsOnly;

		Field(int max, boolean digitsOnly) {
			this.max = max;
			this.digitsOnly = digitsOnly;
		}

		String value() {
			return text.toString().strip();
		}

		void set(String value) {
			text.setLength(0);
			text.append(value);
		}

		boolean type(String typed) {
			int before = text.length();
			typed.codePoints().forEach(cp -> {
				// Sem caracteres invisíveis ou de direção (categoria Cf): o servidor também os tira.
				boolean ok = digitsOnly ? cp >= '0' && cp <= '9'
					: cp >= 32 && cp != 127 && cp != 0xA7 && Character.getType(cp) != Character.FORMAT && Character.getType(cp) != Character.CONTROL;
				if (ok && text.length() < max) {
					text.appendCodePoint(cp);
				}
			});
			return text.length() != before || !typed.isEmpty();
		}

		void backspace() {
			if (!text.isEmpty()) {
				text.setLength(TextFallback.lastGraphemeStart(text));
			}
		}
	}

	private record Hit(String key, float x, float y, float w, float h, PhoneSound sound, Runnable action) {
	}

	private final List<Hit> hits = new ArrayList<>();
	private String pressed;
	/** Campo que recebe o teclado, ou null. */
	protected Field focus;
	/** Onde o dedo (mouse) está, na última vez que o toque foi avisado. */
	protected float pointerX, pointerY;

	// ------------------------------------------------------------------ para o PhoneOS

	final void render(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		hits.clear();
		draw(g, os, x, y, w, h, accent);
	}

	final void pointerDown(float x, float y) {
		pointerX = x;
		pointerY = y;
		pressed = null;
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (x >= hit.x && x <= hit.x + hit.w && y >= hit.y && y <= hit.y + hit.h) {
				pressed = hit.key;
				return;
			}
		}
	}

	final void pointerMove(PhoneOS os, float x, float y) {
		pointerX = x;
		pointerY = y;
		pointerMoved(os, x, y);
	}

	final void pointerUp(PhoneOS os, float x, float y) {
		pointerX = x;
		pointerY = y;
		String key = pressed;
		pressed = null;
		if (key == null) {
			return;
		}
		for (int i = hits.size() - 1; i >= 0; i--) {
			Hit hit = hits.get(i);
			if (hit.key.equals(key) && x >= hit.x && x <= hit.x + hit.w && y >= hit.y && y <= hit.y + hit.h) {
				if (hit.sound != null) {
					os.playSound(hit.sound);
				}
				hit.action.run();
				return;
			}
		}
	}

	/** Áreas clicáveis do último desenho, {x, y, w, h}, por chave, na ordem em que foram registradas (para o destaque da roda). */
	final java.util.LinkedHashMap<String, float[]> focusTargets() {
		java.util.LinkedHashMap<String, float[]> out = new java.util.LinkedHashMap<>();
		for (Hit hit : hits) {
			out.putIfAbsent(hit.key, new float[] {hit.x, hit.y, hit.w, hit.h});
		}
		return out;
	}

	/** Chave do item que o destaque da roda marca ao abrir esta camada (o principal da tela), ou null para o primeiro item. */
	String firstFocus() {
		return null;
	}

	/** Solta o campo de texto em foco (o celular voltou para a mão: o teclado é do jogo de novo). */
	final void blur() {
		focus = null;
	}

	final void cancelPointer() {
		pressed = null;
		pointerReleased();
	}

	final boolean wantsKeyboard() {
		return focus != null;
	}

	final void typed(PhoneOS os, String text) {
		if (focus != null && focus.type(text)) {
			os.playSound(PhoneSound.KEY_TYPE);
		}
	}

	// ------------------------------------------------------------------ para os apps

	/** Desenha o app na área {x, y, w, h} (coordenadas da face). */
	abstract void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent);

	/** O app acabou de ser aberto pela tela inicial. */
	void opened(PhoneOS os) {
	}

	/** O jogador voltou a olhar o celular com este app aberto. */
	void resumed(PhoneOS os) {
	}

	/** Voltar dentro do app. Retorna false se já está na primeira tela (o app então fecha). */
	boolean back(PhoneOS os) {
		return false;
	}

	/**
	 * Roda do mouse em "cliques" (positivo = para cima). Com {@link #smoothScroll()} o PhoneOS entrega cada clique aos poucos,
	 * em frações, ao longo de uns 200 ms: o app só soma {@code amount * SCROLL_STEP} à rolagem e limita.
	 */
	void scroll(PhoneOS os, float amount) {
	}

	/** Pontos da tela rolados por clique da roda (eram 18: uma linha de lista pedia 2 cliques). */
	static final float SCROLL_STEP = 40f;

	/** A roda rola uma lista (entregue aos poucos, deslizando). Falso quando cada clique é um degrau, como o zoom do Mapa. */
	boolean smoothScroll() {
		return true;
	}

	/** O dedo andou com o botão apertado (arrastar). */
	void pointerMoved(PhoneOS os, float x, float y) {
	}

	/** O toque terminou (soltou, dentro ou fora da área). */
	void pointerReleased() {
	}

	/** A área clicável com essa chave está sob o dedo apertado agora? */
	protected final boolean isPressed(String key) {
		return key.equals(pressed);
	}

	void key(PhoneOS os, PhoneOS.Key key) {
		if (key == PhoneOS.Key.BACKSPACE && focus != null) {
			focus.backspace();
			os.playSound(PhoneSound.KEY_DELETE);
		} else if (key == PhoneOS.Key.ENTER && focus != null) {
			os.playSound(PhoneSound.KEY_ENTER);
		}
	}

	/** Registra uma área clicável de item; retorna true se ela está pressionada agora. */
	protected final boolean hit(String key, float x, float y, float w, float h, Runnable action) {
		return hit(key, x, y, w, h, PhoneSound.LIST_ITEM, action);
	}

	/** Registra uma área clicável com som específico (ou null para não tocar som próprio). */
	protected final boolean hit(String key, float x, float y, float w, float h, PhoneSound sound, Runnable action) {
		hits.add(new Hit(key, x, y, w, h, sound, action));
		return key.equals(pressed);
	}

	// ------------------------------------------------------------------ peças de interface

	static final Color INK = new Color(0x15151A), MUTED = new Color(0x6B6B76), LINE = new Color(0xC4C6CE), PRESSED = new Color(0xDADCE4);

	/** Botão redondo verde com o fone, para ligar (linhas de contato e cabeçalho de conversa). Retorna true se está pressionado. */
	protected final boolean callButton(Graphics2D g, String key, float cx, float cy, float d, Runnable action) {
		boolean down = hit(key, cx - d / 2f - 2, cy - d / 2f - 2, d + 4, d + 4, PhoneSound.BUTTON, action);
		Color green = new Color(0x2FB75A);
		g.setColor(down ? Ui.shade(green, 0.8f) : green);
		g.fill(new java.awt.geom.Ellipse2D.Float(cx - d / 2f, cy - d / 2f, d, d));
		java.awt.geom.AffineTransform t = g.getTransform();
		float scale = d * 0.62f / 34f;
		g.translate(cx - 17 * scale, cy - 17 * scale);
		g.scale(scale, scale);
		Ui.glyph(g, "phone", Color.WHITE, green);
		g.setTransform(t);
		g.setStroke(new BasicStroke(1f));
		return down;
	}

	/** Botão cheio com texto centralizado. */
	protected final void button(Graphics2D g, String key, String label, float x, float y, float w, float h, Color color, Runnable action) {
		boolean down = hit(key, x, y, w, h, PhoneSound.BUTTON, action);
		g.setColor(down ? Ui.shade(color, 0.8f) : color);
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 8.6f));
		Ui.centered(g, label, x + w / 2f, y + h / 2f + 3.1f);
	}

	/** Campo de texto; tocar nele dá o foco do teclado. */
	protected final void field(Graphics2D g, PhoneOS os, String key, Field field, String placeholder, float x, float y, float w, float h, Color accent) {
		boolean focused = focus == field;
		hit(key, x, y, w, h, PhoneSound.FIELD_FOCUS, () -> focus = field);
		RoundRectangle2D box = new RoundRectangle2D.Float(x, y, w, h, 8, 8);
		g.setColor(Color.WHITE);
		g.fill(box);
		g.setStroke(new BasicStroke(focused ? 1.2f : 0.7f));
		g.setColor(focused ? accent : LINE);
		g.draw(box);

		g.setFont(Ui.font(Font.PLAIN, 8.8f));
		String text = field.text.toString();
		float baseline = y + h / 2f + 3.1f, left = x + 6, room = w - 12;
		java.awt.Shape oldClip = g.getClip();
		g.clip(new java.awt.geom.Rectangle2D.Float(x + 3, y, w - 6, h));
		if (text.isEmpty() && !focused) {
			g.setColor(new Color(0xA0A2AC));
			Ui.text(g, placeholder, left, baseline);
		} else {
			// Texto maior que o campo rola para a esquerda, mantendo o fim visível.
			float width = Ui.textWidth(g, text);
			float tx = left - Math.max(0, width + 2 - room);
			g.setColor(INK);
			Ui.text(g, text, tx, baseline);
			if (focused && os.time % 1.0 < 0.55) {
				g.setColor(accent);
				g.fill(new java.awt.geom.Rectangle2D.Float(tx + width + 0.8f, y + 4, 0.9f, h - 8));
			}
		}
		g.setClip(oldClip);
	}

	/** Desenha a imagem preenchendo a caixa (corta o que sobra, sem distorcer), com cantos arredondados. */
	protected static void drawCover(Graphics2D g, java.awt.image.BufferedImage img, float x, float y, float w, float h, float arc) {
		float scale = Math.max(w / img.getWidth(), h / img.getHeight());
		float sw = w / scale, sh = h / scale;
		float sx = (img.getWidth() - sw) / 2f, sy = (img.getHeight() - sh) / 2f;
		java.awt.Shape oldClip = g.getClip();
		g.clip(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
		g.drawImage(img, Math.round(x), Math.round(y), Math.round(x + w), Math.round(y + h),
			Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh), null);
		g.setClip(oldClip);
	}

	/** Desenha a imagem inteira dentro da caixa (sobram bordas), centralizada. Retorna {x, y, w, h} usados. */
	protected static float[] drawFit(Graphics2D g, java.awt.image.BufferedImage img, float x, float y, float w, float h, float arc) {
		float scale = Math.min(w / img.getWidth(), h / img.getHeight());
		float dw = img.getWidth() * scale, dh = img.getHeight() * scale;
		float dx = x + (w - dw) / 2f, dy = y + (h - dh) / 2f;
		java.awt.Shape oldClip = g.getClip();
		g.clip(new RoundRectangle2D.Float(dx, dy, dw, dh, arc, arc));
		g.drawImage(img, Math.round(dx), Math.round(dy), Math.round(dw), Math.round(dh), null);
		g.setClip(oldClip);
		return new float[] {dx, dy, dw, dh};
	}

	/** Corta o texto com "…" para caber na largura. */
	protected static String fit(Graphics2D g, String text, float width) {
		if (Ui.textWidth(g, text) <= width) {
			return text;
		}
		// Busca binária nos limites de grafema: o maior começo do texto que, com as reticências, cabe (nunca parte um emoji ao meio).
		int[] bounds = TextFallback.graphemeBounds(text);
		int low = 0, high = bounds.length - 1;
		while (low < high) {
			int mid = (low + high + 1) >>> 1;
			if (Ui.textWidth(g, text.substring(0, bounds[mid]) + "…") <= width) {
				low = mid;
			} else {
				high = mid - 1;
			}
		}
		return text.substring(0, bounds[low]) + "…";
	}

	/** Bolinha colorida com a inicial, como foto de contato. A cor depende da chave. */
	protected static void avatar(Graphics2D g, String keyForColor, String label, float cx, float cy, float r) {
		float hue = (keyForColor.hashCode() & 0xFFFF) / 65535f;
		g.setColor(Color.getHSBColor(hue, 0.48f, 0.78f));
		g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, r * 1.05f));
		String initial = label.isEmpty() ? "?" : label.substring(0, label.offsetByCodePoints(0, 1)).toUpperCase();
		Ui.centered(g, initial, cx, cy + r * 0.38f);
	}
}
