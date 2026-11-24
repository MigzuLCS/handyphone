package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

/**
 * App Telefone: teclado numérico e contatos para ligar. A ligação em si (chamando, recebida, em ligação) é a tela
 * {@link CallScreen}, que cobre o aparelho todo; o app só pede ao servidor para ligar. Quando as ligações não existem
 * no servidor (sem o Simple Voice Chat, ou desligadas pelo dono), o app diz isso em vez de mostrar botões que não funcionam.
 */
final class PhoneApp extends AppContent {
	private static final float MARGIN = 8f, ROW_H = 30f;
	private static final Color CALL_GREEN = new Color(0x2FB75A);

	private enum Tab { KEYPAD, CONTACTS, RECENTS }

	private Tab tab = Tab.KEYPAD;
	private final Field number = new Field(9, true);
	private float scroll, maxScroll;

	@Override
	void opened(PhoneOS os) {
		focus = null;
	}

	@Override
	boolean back(PhoneOS os) {
		if (focus != null) {
			focus = null;
			return true;
		}
		return false;
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		if (tab != Tab.KEYPAD) {
			float next = Math.max(0, Math.min(scroll - amount * SCROLL_STEP, maxScroll));
			if (Math.abs(next - scroll) > 0.01f) {
				scroll = next;
				os.playScrollTick();
			}
		}
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		super.key(os, key);
		if (key == PhoneOS.Key.ENTER && focus != null) {
			os.startCall(number.value());
		}
	}

	void selectTab(int index) {
		tab = Tab.values()[Math.max(0, Math.min(2, index))];
		focus = null;
	}

	/** O texto digitado no teclado (para os testes). */
	String dialed() {
		return number.value();
	}

	void setDialed(String digits) {
		number.set(digits);
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		if (!os.data.callInfoReceived || !os.data.callsAvailable) {
			drawUnavailable(g, os, x, y, w, h);
			return;
		}
		drawTabs(g, x, y, w, accent);
		if (tab == Tab.KEYPAD) {
			drawKeypad(g, os, x, y + 28, w, h - 28, accent);
		} else if (tab == Tab.CONTACTS) {
			drawContacts(g, os, x, y + 28, w, h - 28, accent);
		} else {
			drawRecents(g, os, x, y + 28, w, h - 28, accent);
		}
	}

	private void drawUnavailable(Graphics2D g, PhoneOS os, float x, float y, float w, float h) {
		float cx = x + w / 2f;
		g.setColor(new Color(0xD8DAE2));
		g.fill(new java.awt.geom.Ellipse2D.Float(cx - 22, y + 40, 44, 44));
		java.awt.geom.AffineTransform t = g.getTransform();
		g.translate(cx - 11, y + 51);
		g.scale(22 / 34f, 22 / 34f);
		Ui.glyph(g, "phone", new Color(0x8E8E98), new Color(0xD8DAE2));
		g.setTransform(t);
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0xC8463C));
		g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Line2D.Float(cx - 14, y + 78, cx + 14, y + 46));
		g.setStroke(new BasicStroke(1f));
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		boolean offline = !os.data.callInfoReceived;
		Ui.centered(g, Lang.t(offline ? "handyphone.phone.no_signal" : "handyphone.phone.unavailable"), cx, y + 106);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.8f));
		String[] lines = offline
			? new String[] {Lang.t("handyphone.phone.offline.line1"), Lang.t("handyphone.phone.offline.line2")}
			: new String[] {Lang.t("handyphone.phone.disabled.line1"), Lang.t("handyphone.phone.disabled.line2"), Lang.t("handyphone.phone.disabled.line3")};
		for (int i = 0; i < lines.length; i++) {
			Ui.centered(g, lines[i], cx, y + 121 + i * 10);
		}
	}

	private void drawTabs(Graphics2D g, float x, float y, float w, Color accent) {
		float tw = (w - 2 * MARGIN) / 3f;
		String[] names = {Lang.t("handyphone.phone.tab.keypad"), Lang.t("handyphone.phone.tab.contacts"), Lang.t("handyphone.phone.tab.recents")};
		for (int i = 0; i < 3; i++) {
			Tab target = Tab.values()[i];
			boolean selected = tab == target;
			float tx = x + MARGIN + i * tw;
			boolean down = hit("tab" + i, tx, y + 6, tw, 18, PhoneSound.SELECT, () -> {
				tab = target;
				focus = null;
			});
			g.setColor(selected ? accent : down ? PRESSED : new Color(0xE6E8EE));
			g.fill(new RoundRectangle2D.Float(tx + (i == 0 ? 0 : 1), y + 6, tw - 1, 18, 9, 9));
			g.setColor(selected ? Color.WHITE : MUTED);
			g.setFont(Ui.font(Font.BOLD, 8f));
			Ui.centered(g, names[i], tx + tw / 2f, y + 18);
		}
	}

	private void drawKeypad(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;

		// O número digitado, grande; tocar nele liga o teclado do computador.
		boolean focused = focus == number;
		hit("display", rx, y + 4, rw, 28, PhoneSound.FIELD_FOCUS, () -> focus = number);
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(rx, y + 4, rw, 28, 10, 10));
		g.setStroke(new BasicStroke(focused ? 1.2f : 0.7f));
		g.setColor(focused ? accent : LINE);
		g.draw(new RoundRectangle2D.Float(rx, y + 4, rw, 28, 10, 10));
		g.setStroke(new BasicStroke(1f));
		String digits = number.value();
		if (digits.isEmpty()) {
			g.setColor(new Color(0xA0A2AC));
			g.setFont(Ui.font(Font.PLAIN, 9f));
			Ui.centered(g, Lang.t("handyphone.phone.enter_number"), rx + rw / 2f, y + 22);
		} else {
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 15f));
			String shown = PhoneData.formatNumber(digits);
			float tw = Ui.textWidth(g, shown);
			float tx = rx + rw / 2f - tw / 2f;
			Ui.text(g, shown, tx, y + 24);
			if (focused && os.time % 1.0 < 0.55) {
				g.setColor(accent);
				g.fill(new Rectangle2D.Float(tx + tw + 1.2f, y + 10, 1f, 17));
			}
		}

		// Teclas.
		float gap = 5f, keyW = (rw - 2 * gap) / 3f;
		float keyH = Math.max(18f, Math.min(30f, (h - 40 - 28 - 4 * gap) / 4f));
		String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<"};
		for (int i = 0; i < labels.length; i++) {
			String label = labels[i];
			if (label.isEmpty()) {
				continue;
			}
			float kx = rx + (i % 3) * (keyW + gap), ky = y + 40 + (i / 3) * (keyH + gap);
			boolean back = label.equals("<");
			boolean down = hit("key" + label, kx, ky, keyW, keyH, null, () -> {
				if (back) {
					number.backspace();
					os.playSound(PhoneSound.KEY_DELETE);
				} else {
					number.type(label);
					os.playSound(PhoneSound.KEY_TYPE);
				}
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(kx, ky, keyW, keyH, 9, 9));
			g.setStroke(new BasicStroke(0.6f));
			g.setColor(LINE);
			g.draw(new RoundRectangle2D.Float(kx, ky, keyW, keyH, 9, 9));
			g.setColor(INK);
			if (back) {
				float cx = kx + keyW / 2f, cy = ky + keyH / 2f;
				g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Line2D.Float(cx - 6, cy, cx + 3, cy - 5));
				g.draw(new Line2D.Float(cx - 6, cy, cx + 3, cy + 5));
				g.draw(new Line2D.Float(cx - 6, cy, cx + 7, cy));
			} else {
				g.setFont(Ui.font(Font.BOLD, 13f));
				Ui.centered(g, label, kx + keyW / 2f, ky + keyH / 2f + 4.6f);
			}
		}
		g.setStroke(new BasicStroke(1f));

		// Ligar.
		float cy = y + 40 + 4 * (keyH + gap) + 2;
		boolean ready = !digits.isEmpty();
		boolean down = hit("dial", rx, cy, rw, 26, PhoneSound.BUTTON, () -> os.startCall(number.value()));
		g.setColor(!ready ? new Color(0xB9D9C3) : down ? Ui.shade(CALL_GREEN, 0.85f) : CALL_GREEN);
		g.fill(new RoundRectangle2D.Float(rx, cy, rw, 26, 13, 13));
		java.awt.geom.AffineTransform t = g.getTransform();
		g.translate(rx + rw / 2f - 28, cy + 6);
		g.scale(14 / 34f, 14 / 34f);
		Ui.glyph(g, "phone", Color.WHITE, CALL_GREEN);
		g.setTransform(t);
		g.setStroke(new BasicStroke(1f));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 9.4f));
		Ui.text(g, Lang.t("handyphone.phone.call"), rx + rw / 2f - 11, cy + 16.4f);
	}

	/** Os recentes: chamadas feitas, recebidas e perdidas, com a hora e a duração; tocar no botão chama de volta. */
	private void drawRecents(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		float top = y + 4, viewH = h - 4;
		java.util.List<PhoneData.CallLogEntry> log = os.data.callLog;
		maxScroll = Math.max(0, log.size() * ROW_H - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (log.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.6f));
			Ui.centered(g, Lang.t("handyphone.phone.recents.empty"), x + w / 2f, top + 30);
		}
		for (int i = 0; i < log.size(); i++) {
			float ry = top + i * ROW_H - scroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			PhoneData.CallLogEntry e = log.get(i);
			PhoneData.Conversation c = e.group() ? os.data.conversation(e.conversation()) : null;
			// Grupo que não está mais no celular (saiu, foi apagado): só o rótulo, sem como chamar de volta.
			boolean group = e.group();
			String name = group ? (c != null ? c.title : Lang.t("handyphone.phone.group")) : os.data.displayName(e.number());
			boolean missed = !e.outgoing() && !e.answered();
			g.setColor(Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			// A seta: sai (chamada feita), entra (recebida), vermelha = perdida / não atendida.
			float ax = rx + 11, ay = ry + ROW_H / 2f - 1;
			g.setColor(missed || (e.outgoing() && !e.answered()) ? new Color(0xD14336) : new Color(0x2FB75A));
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			float dir = e.outgoing() ? 1 : -1;
			g.draw(new java.awt.geom.Line2D.Float(ax - 3.5f * dir, ay + 3.5f * dir, ax + 3.5f * dir, ay - 3.5f * dir));
			g.draw(new java.awt.geom.Line2D.Float(ax + 3.5f * dir, ay - 3.5f * dir, ax + 3.5f * dir - 4 * dir, ay - 3.5f * dir));
			g.draw(new java.awt.geom.Line2D.Float(ax + 3.5f * dir, ay - 3.5f * dir, ax + 3.5f * dir, ay - 3.5f * dir + 4 * dir));
			g.setStroke(new BasicStroke(1f));
			g.setColor(missed ? new Color(0xD14336) : INK);
			g.setFont(Ui.font(Font.BOLD, 9f));
			Ui.text(g, fit(g, name, rw - 74), rx + 24, ry + 12.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			String what = e.answered() ? PhoneData.duration(e.seconds()) : Lang.t(e.outgoing() ? "handyphone.phone.log.unanswered" : "handyphone.phone.log.missed");
			Ui.text(g, fit(g, Lang.t(group ? "handyphone.phone.log.line_group" : "handyphone.phone.log.line", when(e.time()), what), rw - 62), rx + 24, ry + 22.5f);
			long conv = e.conversation();
			String number = e.number();
			boolean whole = ry >= top && ry + ROW_H <= top + viewH;
			if (os.data.callsAvailable && whole && (!group || c != null)) {
				callButton(g, "back:" + i, rx + rw - 14, ry + ROW_H / 2f - 1, 17, () -> {
					if (group) {
						os.callGroup(conv);
					} else {
						os.startCall(number);
					}
				});
			}
		}
		g.setClip(oldClip);
	}

	private static String when(long time) {
		java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), java.time.ZoneId.systemDefault());
		return t.toLocalDate().equals(java.time.LocalDate.now()) ? t.format(Lang.format("handyphone.format.time")) : t.format(Lang.format("handyphone.format.daytime"));
	}

	private void drawContacts(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		float top = y + 4, viewH = h - 4;
		maxScroll = Math.max(0, os.data.contacts.size() * ROW_H - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (os.data.contacts.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.6f));
			Ui.centered(g, Lang.t("handyphone.messages.contacts_empty"), x + w / 2f, top + 30);
			Ui.centered(g, Lang.t("handyphone.phone.contacts_empty.line2"), x + w / 2f, top + 42);
		}
		int i = 0;
		for (Map.Entry<String, String> contact : os.data.contacts.entrySet()) {
			float ry = top + i++ * ROW_H - scroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			String num = contact.getKey();
			g.setColor(Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			avatar(g, num, contact.getValue(), rx + 14, ry + ROW_H / 2f - 1, 9);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.2f));
			Ui.text(g, fit(g, contact.getValue(), rw - 66), rx + 28, ry + 12.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			Ui.text(g, PhoneData.formatNumber(num), rx + 28, ry + 22.5f);
			callButton(g, "call:" + num, rx + rw - 16, ry + ROW_H / 2f - 1, 18, () -> os.startCall(num));
		}
		g.setClip(oldClip);
	}
}
