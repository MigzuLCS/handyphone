package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Map;

/** App Contatos: o próprio número, a agenda e o formulário para adicionar ou editar. */
final class ContactsApp extends AppContent {
	private static final float MARGIN = 8f, ROW_H = 30f;

	private boolean editing;
	/** A tela dos números bloqueados está aberta. */
	private boolean showingBlocked;
	private float blockedScroll;
	/** Número do contato sendo editado, ou null ao adicionar um novo. */
	private String editingNumber;
	private final Field name = new Field(24, false);
	private final Field number = new Field(9, true);
	private float scroll;
	private float maxScroll;

	@Override
	void opened(PhoneOS os) {
		showList();
	}

	private void showList() {
		editing = false;
		showingBlocked = false;
		focus = null;
	}

	/** Abre o formulário; com {@code existing} preenchido, edita esse contato. */
	void edit(PhoneOS os, String existing, String prefillNumber) {
		editing = true;
		editingNumber = existing;
		name.set(existing != null ? os.data.contacts.getOrDefault(existing, "") : "");
		number.set(existing != null ? existing : prefillNumber);
		focus = name;
	}

	@Override
	boolean back(PhoneOS os) {
		if (editing || showingBlocked) {
			showList();
			return true;
		}
		return false;
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		if (showingBlocked) {
			blockedScroll = Math.max(0, blockedScroll - amount * 18);
			os.playScrollTick();
		} else if (!editing) {
			float next = Math.max(0, Math.min(scroll - amount * 18, maxScroll));
			if (Math.abs(next - scroll) > 0.5f) {
				scroll = next;
				os.playScrollTick();
			}
		}
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		super.key(os, key);
		if (!editing) {
			return;
		}
		if (key == PhoneOS.Key.TAB) {
			Field next = focus == name && editingNumber == null ? number : name;
			if (next != focus) {
				focus = next;
				os.playSound(PhoneSound.FIELD_FOCUS);
			}
		} else if (key == PhoneOS.Key.ENTER) {
			save(os);
		}
	}

	private void save(PhoneOS os) {
		if (name.value().isEmpty() || number.value().isEmpty()) {
			os.notify(Lang.t("handyphone.app.contacts"), Lang.t("handyphone.contacts.fill"), -1);
			return;
		}
		os.backend.setContact(number.value(), name.value());
		os.playSound(PhoneSound.CONTACT_SAVED);
		showList();
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		if (editing) {
			drawForm(g, os, x, y, w, accent);
		} else if (showingBlocked) {
			drawBlocked(g, os, x, y, w, h, accent);
		} else {
			drawList(g, os, x, y, w, h, accent);
		}
	}

	private void drawList(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;

		// Cartão com o próprio número.
		RoundRectangle2D card = new RoundRectangle2D.Float(rx, y + 8, rw, 36, 8, 8);
		g.setColor(accent);
		g.fill(card);
		g.setColor(new Color(255, 255, 255, 200));
		g.setFont(Ui.font(Font.BOLD, 7.2f));
		Ui.text(g, Lang.t("handyphone.contacts.my_number"), rx + 8, y + 21);
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 15f));
		Ui.text(g, os.data.number.isEmpty() ? Lang.t("handyphone.contacts.no_signal") : PhoneData.formatNumber(os.data.number), rx + 8, y + 38);

		button(g, "add", Lang.t("handyphone.contacts.add"), rx, y + 50, rw, 20, Ui.shade(accent, 0.85f), () -> edit(os, null, ""));
		button(g, "blocked", Lang.t("handyphone.contacts.blocked_button", Integer.toString(os.data.blocked.size())), rx, y + 74, rw, 18, new Color(0x8E8E98), () -> {
			showingBlocked = true;
			blockedScroll = 0;
		});

		float top = y + 98, viewH = h - 98;
		float total = os.data.contacts.size() * ROW_H;
		maxScroll = Math.max(0, total - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (os.data.contacts.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.6f));
			Ui.centered(g, Lang.t("handyphone.messages.contacts_empty"), x + w / 2f, top + 26);
			Ui.centered(g, Lang.t("handyphone.contacts.empty.line2"), x + w / 2f, top + 38);
		}
		int i = 0;
		for (Map.Entry<String, String> contact : os.data.contacts.entrySet()) {
			float ry = top + i++ * ROW_H - scroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			String num = contact.getKey();
			boolean down = hit("chat:" + num, rx, Math.max(ry, top), rw - 34 - 24, ROW_H - 2, () -> os.openChatWith(num));
			boolean editDown = hit("edit:" + num, rx + rw - 34, Math.max(ry, top), 34, ROW_H - 2, PhoneSound.BUTTON, () -> edit(os, num, num));
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			avatar(g, num, contact.getValue(), rx + 14, ry + ROW_H / 2f - 1, 9);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.2f));
			Ui.text(g, fit(g, contact.getValue(), rw - 90), rx + 28, ry + 12.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			Ui.text(g, PhoneData.formatNumber(num), rx + 28, ry + 22.5f);
			if (os.data.callsAvailable) {
				callButton(g, "call:" + num, rx + rw - 34 - 12, ry + ROW_H / 2f - 1, 17, () -> os.startCall(num));
			}
			g.setColor(editDown ? accent : MUTED);
			g.setFont(Ui.font(Font.BOLD, 7f));
			Ui.centered(g, Lang.t("handyphone.contacts.edit_tag"), rx + rw - 17, ry + ROW_H / 2f + 1.5f);
		}
		g.setClip(oldClip);
	}

	/** Os números bloqueados, cada um com o botão de desbloquear. */
	private void drawBlocked(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, Lang.t("handyphone.contacts.blocked.title"), rx + 2, y + 18);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7f));
		Ui.text(g, Lang.t("handyphone.contacts.blocked.subtitle"), rx + 2, y + 29);

		float top = y + 36, viewH = h - 36;
		java.util.List<String> numbers = os.data.blocked;
		float maxScroll = Math.max(0, numbers.size() * ROW_H - viewH);
		blockedScroll = Math.max(0, Math.min(blockedScroll, maxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (numbers.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.6f));
			Ui.centered(g, Lang.t("handyphone.contacts.blocked.none"), x + w / 2f, top + 26);
		}
		for (int i = 0; i < numbers.size(); i++) {
			float ry = top + i * ROW_H - blockedScroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			String num = numbers.get(i);
			g.setColor(Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			avatar(g, num, os.data.displayName(num), rx + 14, ry + ROW_H / 2f - 1, 9);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.2f));
			Ui.text(g, fit(g, os.data.displayName(num), rw - 92), rx + 28, ry + 12.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			Ui.text(g, PhoneData.formatNumber(num), rx + 28, ry + 22.5f);
			boolean down = hit("unblock:" + num, rx + rw - 60, Math.max(ry + 5, top), 56, 18, PhoneSound.BUTTON, () -> {
				os.backend.block(num, false);
				os.notify(Lang.t("handyphone.banner.blocked"), Lang.t("handyphone.block.unblocked", PhoneData.formatNumber(num)), -1);
			});
			g.setColor(down ? Ui.shade(accent, 0.8f) : accent);
			g.fill(new RoundRectangle2D.Float(rx + rw - 60, ry + 5, 56, 18, 9, 9));
			g.setColor(Color.WHITE);
			g.setFont(Ui.font(Font.BOLD, 6.8f));
			Ui.centered(g, Lang.t("handyphone.contacts.unblock"), rx + rw - 32, ry + 16.2f);
		}
		g.setClip(oldClip);
	}

	private void drawForm(Graphics2D g, PhoneOS os, float x, float y, float w, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, Lang.t(editingNumber != null ? "handyphone.contacts.edit.title" : "handyphone.contacts.new.title"), rx + 2, y + 18);

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.2f));
		Ui.text(g, Lang.t("handyphone.contacts.name_label"), rx + 2, y + 34);
		field(g, os, "name", name, Lang.t("handyphone.contacts.name_hint"), rx, y + 38, rw, 20, accent);

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.2f));
		Ui.text(g, Lang.t("handyphone.contacts.number_label"), rx + 2, y + 72);
		if (editingNumber != null) {
			// O número de um contato existente não muda; para trocar, remova e adicione de novo.
			g.setColor(INK);
			g.setFont(Ui.font(Font.PLAIN, 10f));
			Ui.text(g, PhoneData.formatNumber(editingNumber), rx + 2, y + 90);
		} else {
			field(g, os, "number", number, Lang.t("handyphone.contacts.number_hint"), rx, y + 76, rw, 20, accent);
		}

		button(g, "save", Lang.t("handyphone.save"), rx, y + 108, rw, 22, accent, () -> save(os));
		if (editingNumber != null) {
			button(g, "remove", Lang.t("handyphone.contacts.remove"), rx, y + 136, rw, 22, new Color(0xC8463C), () -> {
				os.backend.setContact(editingNumber, "");
				os.playSound(PhoneSound.CONTACT_REMOVED);
				showList();
			});
			boolean blocked = os.data.isBlocked(editingNumber);
			button(g, "block", Lang.t(blocked ? "handyphone.block.menu_unblock" : "handyphone.block.menu_block"), rx, y + 164, rw, 22, blocked ? new Color(0x4A9B5C) : new Color(0x7A2E27), () -> {
				String target = editingNumber;
				if (blocked) {
					os.backend.block(target, false);
					os.notify(Lang.t("handyphone.banner.blocked"), Lang.t("handyphone.block.unblocked", PhoneData.formatNumber(target)), -1);
				} else {
					os.confirm(Lang.t("handyphone.block.confirm_title", os.data.displayName(target)),
						Lang.t("handyphone.block.confirm_text"), Lang.t("handyphone.block.confirm_button"), true,
						() -> os.backend.block(target, true));
				}
			});
		}
		g.setStroke(new BasicStroke(1f));
	}
}
