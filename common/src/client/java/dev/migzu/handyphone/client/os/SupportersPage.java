package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Página Apoiadores (aberta pelo app Sobre): quem apoiou o HandyPhone no Ko-fi, numa lista que vem dentro do mod ({@link Supporters}). Duas organizações, cada uma
 * com o seu dado: por data (nome e data, sem valor) e por valor (nome e valor). Um botão inverte a ordem; o "Apoiar no Ko-fi" abre a
 * página no navegador (endereço fixo daqui, como os links do Sobre).
 */
final class SupportersPage {
	static final String KOFI = "https://ko-fi.com/migzu";
	private static final float MARGIN = 8f, TABS_H = 30f, BUTTON_H = 24f, GAP = 3.5f;
	/** Os corações são sempre vermelhos (a cor do ícone do app); o resto segue a cor de destaque, como nos outros apps. */
	static final Color HEART = new Color(0xE0284A);
	private static final float NAME_FONT = 9.2f, MESSAGE_FONT = 7.6f, LINE_H = 9.6f, ROW_BASE = 24f;

	private List<Supporters.Entry> source;
	private Supporters.Order order = Supporters.Order.DATE;
	private boolean descending = true;
	private float scroll, maxScroll;

	/** Linhas da mensagem de cada entrada (quebra cara; refeita só quando a largura ou a lista mudam). */
	private List<Supporters.Entry> shown;
	private final List<List<String>> wrapped = new ArrayList<>();
	private float wrappedWidth = -1;
	private String wrappedLang;

	/** Só para os testes: outra lista no lugar da do jar. */
	void useList(List<Supporters.Entry> list) {
		source = list;
		shown = null;
	}

	private List<Supporters.Entry> source() {
		if (source == null) {
			source = Supporters.load();
		}
		return source;
	}

	Supporters.Order order() {
		return order;
	}

	boolean descending() {
		return descending;
	}

	/** A lista como está na tela agora. */
	List<Supporters.Entry> shown() {
		if (shown == null) {
			shown = Supporters.sorted(source(), order, descending);
			wrappedWidth = -1;
		}
		return shown;
	}

	/** O Sobre abriu a página: começa no padrão (por data, mais recente primeiro). */
	void reset() {
		order = Supporters.Order.DATE;
		descending = true;
		scroll = 0;
		shown = null;
	}

	private void setOrder(Supporters.Order o) {
		if (o != order) {
			order = o;
			descending = true; // cada organização começa no seu padrão: mais recente / maior primeiro
			scroll = 0;
			shown = null;
		}
	}

	private void flip() {
		descending = !descending;
		scroll = 0;
		shown = null;
	}

	void scroll(PhoneOS os, float amount) {
		float next = Math.max(0, Math.min(scroll - amount * AppContent.SCROLL_STEP, maxScroll));
		if (Math.abs(next - scroll) > 0.01f) {
			scroll = next;
			os.playScrollTick();
		}
	}

	/** Quem registra as áreas clicáveis (o app Sobre). */
	private AppContent host;

	void draw(Graphics2D g, AppContent host, PhoneOS os, float x, float y, float w, float h, Color accent) {
		this.host = host;
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		List<Supporters.Entry> list = shown();
		if (list.isEmpty()) {
			drawEmpty(g, os, rx, y, rw, h, accent);
			return;
		}
		drawTabs(g, rx, y, rw, accent);
		float listTop = y + TABS_H, listBottom = y + h - BUTTON_H - 2 * MARGIN;
		drawList(g, list, rx, listTop, rw, listBottom - listTop, accent);
		kofiButton(g, os, rx, y + h - BUTTON_H - MARGIN, rw, accent);
	}

	/** "Data" | "Valor" e, à direita, o botão que inverte a ordem (seta para baixo = mais recente / maior primeiro). */
	private void drawTabs(Graphics2D g, float rx, float y, float rw, Color accent) {
		float sortW = 24f, tw = (rw - sortW - 4f) / 2f, ty = y + 6f, th = 18f;
		Supporters.Order[] orders = Supporters.Order.values();
		String[] names = {Lang.t("handyphone.supporters.by_date"), Lang.t("handyphone.supporters.by_value")};
		for (int i = 0; i < orders.length; i++) {
			Supporters.Order target = orders[i];
			boolean selected = order == target;
			float tx = rx + i * tw;
			boolean down = host.hit("order:" + target.name().toLowerCase(java.util.Locale.ROOT), tx, ty, tw, th, PhoneSound.SELECT, () -> setOrder(target));
			g.setColor(selected ? accent : down ? AppContent.PRESSED : new Color(0xE6E8EE));
			g.fill(new RoundRectangle2D.Float(tx + (i == 0 ? 0 : 1), ty, tw - 1, th, 9, 9));
			g.setColor(selected ? Color.WHITE : AppContent.MUTED);
			g.setFont(Ui.font(Font.BOLD, 8f));
			Ui.centered(g, AppContent.fit(g, names[i], tw - 8), tx + tw / 2f, ty + 12f);
		}
		float sx = rx + rw - sortW;
		boolean down = host.hit("sort", sx, ty, sortW, th, PhoneSound.SELECT, this::flip);
		g.setColor(down ? AppContent.PRESSED : new Color(0xE6E8EE));
		g.fill(new RoundRectangle2D.Float(sx, ty, sortW, th, 9, 9));
		// Seta: para baixo = do maior/mais recente para o menor; para cima = o contrário.
		float cx = sx + sortW / 2f, cy = ty + th / 2f, d = descending ? 1f : -1f;
		g.setColor(AppContent.INK);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Line2D.Float(cx, cy - 4.5f * d, cx, cy + 4.5f * d));
		GeneralPath tip = new GeneralPath();
		tip.moveTo(cx - 3.2f, cy + 1.6f * d);
		tip.lineTo(cx, cy + 4.6f * d);
		tip.lineTo(cx + 3.2f, cy + 1.6f * d);
		g.draw(tip);
	}

	private void drawList(Graphics2D g, List<Supporters.Entry> list, float rx, float top, float rw, float viewH, Color accent) {
		float textW = rw - 16f;
		ensureWrapped(g, list, textW);
		java.awt.Shape oldClip = g.getClip();
		g.clip(new java.awt.geom.Rectangle2D.Float(rx - 2, top, rw + 4, viewH));
		float cy = top + 2f - scroll, total = 0;
		for (int i = 0; i < list.size(); i++) {
			Supporters.Entry e = list.get(i);
			List<String> lines = wrapped.get(i);
			float rowH = ROW_BASE + (lines.isEmpty() ? 0 : lines.size() * LINE_H + 3f);
			// Só as linhas visíveis são desenhadas.
			if (cy + rowH >= top && cy <= top + viewH) {
				drawRow(g, e, lines, rx, cy, rw, rowH, accent);
			}
			cy += rowH + GAP;
			total += rowH + GAP;
		}
		g.setClip(oldClip);
		maxScroll = Math.max(0, total + 4f - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
	}

	private void drawRow(Graphics2D g, Supporters.Entry e, List<String> lines, float rx, float ry, float rw, float rowH, Color accent) {
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(rx, ry, rw, rowH, 9, 9));
		g.setStroke(new BasicStroke(0.7f));
		g.setColor(AppContent.LINE);
		g.draw(new RoundRectangle2D.Float(rx, ry, rw, rowH, 9, 9));
		// À direita, o dado da organização escolhida: a data ou o valor.
		String side;
		if (order == Supporters.Order.DATE) {
			side = Lang.format("handyphone.format.date.full").format(e.date());
			g.setFont(Ui.font(Font.PLAIN, 7.6f));
			g.setColor(AppContent.MUTED);
		} else {
			side = Supporters.money(e.cents());
			g.setFont(Ui.font(Font.BOLD, 8.6f));
			g.setColor(Ui.shade(accent, 0.85f));
		}
		float sideW = Ui.textWidth(g, side);
		Ui.text(g, side, rx + rw - 8 - sideW, ry + 15f);
		heart(g, rx + 12f, ry + 12f, 9f, HEART);
		g.setColor(AppContent.INK);
		g.setFont(Ui.font(Font.BOLD, NAME_FONT));
		Ui.text(g, AppContent.fit(g, e.name(), rw - 30 - sideW - 8), rx + 21f, ry + 15.2f);
		if (!lines.isEmpty()) {
			// A mensagem, com uma barrinha na cor do app (como uma citação).
			float my = ry + ROW_BASE - 2f;
			g.setColor(Ui.alpha(accent, 110));
			g.fill(new RoundRectangle2D.Float(rx + 8f, my, 1.6f, lines.size() * LINE_H + 1f, 1.6f, 1.6f));
			g.setColor(new Color(0x45454F));
			g.setFont(Ui.font(Font.PLAIN, MESSAGE_FONT));
			for (int k = 0; k < lines.size(); k++) {
				Ui.text(g, lines.get(k), rx + 13f, my + 7.6f + k * LINE_H);
			}
		}
	}

	private void ensureWrapped(Graphics2D g, List<Supporters.Entry> list, float width) {
		String lang = Lang.locale().toLanguageTag();
		if (width == wrappedWidth && lang.equals(wrappedLang) && wrapped.size() == list.size()) {
			return;
		}
		wrappedWidth = width;
		wrappedLang = lang;
		wrapped.clear();
		Font old = g.getFont();
		g.setFont(Ui.font(Font.PLAIN, MESSAGE_FONT));
		for (Supporters.Entry e : list) {
			wrapped.add(e.message().isEmpty() ? List.of() : MessagesApp.wrap(g, e.message(), width - 6f));
		}
		g.setFont(old);
	}

	private void drawEmpty(Graphics2D g, PhoneOS os, float rx, float y, float rw, float h, Color accent) {
		float cx = rx + rw / 2f, cy = y + h * 0.36f;
		heart(g, cx, cy, 44f, HEART);
		g.setColor(AppContent.INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.centered(g, AppContent.fit(g, Lang.t("handyphone.supporters.empty.title"), rw), cx, cy + 42f);
		g.setColor(AppContent.MUTED);
		g.setFont(Ui.font(Font.PLAIN, 8f));
		List<String> lines = MessagesApp.wrap(g, Lang.t("handyphone.supporters.empty.text"), rw - 16);
		for (int i = 0; i < lines.size(); i++) {
			Ui.centered(g, lines.get(i), cx, cy + 58f + i * 11f);
		}
		kofiButton(g, os, rx, y + h - BUTTON_H - MARGIN, rw, accent);
	}

	private void kofiButton(Graphics2D g, PhoneOS os, float rx, float by, float rw, Color accent) {
		String label = Lang.t("handyphone.supporters.kofi");
		host.button(g, "kofi", "", rx, by, rw, BUTTON_H, accent, () -> {
			os.openLink(KOFI);
			os.notify("HandyPhone", Lang.t("handyphone.about.opening"), -1);
		});
		g.setFont(Ui.font(Font.BOLD, 8.8f));
		float tw = Math.min(Ui.textWidth(g, label), rw - 34), total = tw + 13f, lx = rx + (rw - total) / 2f;
		heart(g, lx + 4.5f, by + BUTTON_H / 2f, 9f, Color.WHITE);
		g.setColor(Color.WHITE);
		Ui.text(g, AppContent.fit(g, label, rw - 34), lx + 13f, by + BUTTON_H / 2f + 3.2f);
	}

	String firstFocus() {
		return shown().isEmpty() ? "kofi" : null;
	}

	/** Coração cheio centrado em (cx, cy), {@code size} de largura. */
	static void heart(Graphics2D g, float cx, float cy, float size, Color color) {
		Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(color);
		g.fill(heartPath(cx, cy, size));
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, aa);
	}

	static GeneralPath heartPath(float cx, float cy, float size) {
		float s = size / 2f;
		GeneralPath p = new GeneralPath();
		p.moveTo(cx, cy + s * 0.85f);
		p.curveTo(cx - s * 0.35f, cy + s * 0.55f, cx - s, cy + s * 0.1f, cx - s, cy - s * 0.35f);
		p.curveTo(cx - s, cy - s * 0.85f, cx - s * 0.45f, cy - s * 1.05f, cx, cy - s * 0.55f);
		p.curveTo(cx + s * 0.45f, cy - s * 1.05f, cx + s, cy - s * 0.85f, cx + s, cy - s * 0.35f);
		p.curveTo(cx + s, cy + s * 0.1f, cx + s * 0.35f, cy + s * 0.55f, cx, cy + s * 0.85f);
		p.closePath();
		return p;
	}
}
