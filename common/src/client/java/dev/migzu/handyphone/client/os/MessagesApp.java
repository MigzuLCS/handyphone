package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.client.os.PhoneData.Conversation;
import dev.migzu.handyphone.client.os.PhoneData.Message;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** App Mensagens: lista de conversas, nova conversa, novo grupo e a conversa em si. */
final class MessagesApp extends AppContent {
	private enum View { LIST, NEW, GROUP, CHAT, PHOTO }

	private static final float MARGIN = 8f, ROW_H = 34f;

	private View view = View.LIST;
	private long chatId = -1;
	private float listScroll, chatScroll, pickScroll;
	private float listMaxScroll, chatMaxScroll, pickMaxScroll;
	private final Field newNumber = new Field(9, true);
	private final Field groupTitle = new Field(24, false);
	private final Field input = new Field(256, false);
	private final Set<String> groupPick = new LinkedHashSet<>();
	private final PhotoViewer viewer = new PhotoViewer(this);
	/** O menu do botão de localização (minha localização / ponto no mapa) está aberto. */
	private boolean locationMenu;
	private static final float LOC_H = 40f;
	/** Altura de uma mensagem de sistema ("Fulano saiu do grupo"). */
	private static final float SYS_H = 14f;
	/** O menu do cabeçalho da conversa (bloquear número, sair do grupo) está aberto. */
	private boolean chatMenu;

	// ------------------------------------------------------------------ navegação

	@Override
	void opened(PhoneOS os) {
		show(View.LIST);
	}

	private void show(View next) {
		view = next;
		focus = switch (next) {
			case NEW -> newNumber;
			case GROUP -> groupTitle;
			case CHAT -> input;
			case LIST, PHOTO -> null;
		};
	}

	/** Mostra a conversa (o histórico chega depois, pelo servidor). */
	void showChat(long id) {
		if (chatId != id) {
			input.set("");
		}
		chatId = id;
		chatScroll = 0;
		locationMenu = false;
		chatMenu = false;
		show(View.CHAT);
	}

	/** Verdadeiro se o jogador está com essa conversa aberta na tela. */
	boolean isShowing(long id) {
		return view == View.CHAT && chatId == id;
	}

	@Override
	void resumed(PhoneOS os) {
		markRead(os);
	}

	/** Avisa o servidor que a conversa aberta foi lida. */
	void markRead(PhoneOS os) {
		Conversation c = view == View.CHAT ? os.data.conversation(chatId) : null;
		if (c != null && c.unread > 0) {
			c.unread = 0;
			os.backend.markRead(c.id, c.lastSeq);
		}
	}

	@Override
	boolean back(PhoneOS os) {
		if (chatMenu) {
			chatMenu = false;
			return true;
		}
		if (locationMenu) {
			locationMenu = false;
			return true;
		}
		if (view == View.LIST) {
			return false;
		}
		show(view == View.PHOTO ? View.CHAT : View.LIST);
		return true;
	}

	@Override
	void pointerMoved(PhoneOS os, float x, float y) {
		if (view == View.PHOTO) {
			viewer.drag(x, y);
		}
	}

	@Override
	void pointerReleased() {
		viewer.release();
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		switch (view) {
			case LIST -> {
				float next = Math.max(0, Math.min(listScroll - amount * SCROLL_STEP, listMaxScroll));
				if (Math.abs(next - listScroll) > 0.01f) {
					listScroll = next;
					os.playScrollTick();
				}
			}
			case CHAT -> {
				float next = Math.max(0, Math.min(chatScroll + amount * SCROLL_STEP, chatMaxScroll));
				if (Math.abs(next - chatScroll) > 0.01f) {
					chatScroll = next;
					os.playScrollTick();
				}
			}
			case PHOTO -> {
				if (!os.focusMode()) {
					viewer.scroll(os, amount);
				}
			}
			default -> {
				float next = Math.max(0, Math.min(pickScroll - amount * SCROLL_STEP, pickMaxScroll));
				if (Math.abs(next - pickScroll) > 0.01f) {
					pickScroll = next;
					os.playScrollTick();
				}
			}
		}
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		super.key(os, key);
		if (key == PhoneOS.Key.ENTER) {
			switch (view) {
				case CHAT -> send(os);
				case NEW -> openNew(os);
				case GROUP -> createGroup(os);
				default -> { }
			}
		}
	}

	private void send(PhoneOS os) {
		String text = input.value();
		if (!text.isEmpty() && os.data.conversation(chatId) != null) {
			os.backend.send(chatId, text);
			input.set("");
			chatScroll = 0;
			os.playSound(PhoneSound.MESSAGE_SENT);
		}
	}

	private void openNew(PhoneOS os) {
		if (!newNumber.value().isEmpty()) {
			os.backend.openDirect(newNumber.value());
		}
	}

	private void createGroup(PhoneOS os) {
		if (groupTitle.value().isEmpty()) {
			os.notify(Lang.t("handyphone.app.messages"), Lang.t("handyphone.messages.group_name_missing"), -1);
		} else if (groupPick.isEmpty()) {
			os.notify(Lang.t("handyphone.app.messages"), Lang.t("handyphone.messages.group_contacts_missing"), -1);
		} else {
			os.backend.createGroup(groupTitle.value(), new ArrayList<>(groupPick));
			os.playSound(PhoneSound.GROUP_CREATED);
		}
	}

	// ------------------------------------------------------------------ desenho

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		if (view == View.CHAT && os.data.conversation(chatId) == null) {
			show(View.LIST);
		}
		switch (view) {
			case LIST -> drawList(g, os, x, y, w, h, accent);
			case NEW -> drawNew(g, os, x, y, w, h, accent);
			case GROUP -> drawGroup(g, os, x, y, w, h, accent);
			case CHAT -> drawChat(g, os, x, y, w, h, accent);
			case PHOTO -> viewer.draw(g, os, x, y, w, h, accent, () -> os.forwardPhoto(viewer.photoId()), () -> show(View.CHAT));
		}
	}

	private void drawList(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN, half = (rw - 4) / 2f;
		button(g, "new", Lang.t("handyphone.messages.new_chat"), rx, y + 8, half, 20, accent, () -> {
			newNumber.set("");
			pickScroll = 0;
			show(View.NEW);
		});
		button(g, "group", Lang.t("handyphone.messages.new_group"), rx + half + 4, y + 8, half, 20, Ui.shade(accent, 0.85f), () -> {
			groupTitle.set("");
			groupPick.clear();
			pickScroll = 0;
			show(View.GROUP);
		});

		float top = y + 34, viewH = h - 34;
		List<Conversation> list = os.data.conversations;
		listMaxScroll = Math.max(0, list.size() * ROW_H - viewH);
		listScroll = Math.max(0, Math.min(listScroll, listMaxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (list.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.6f));
			if (os.data.number.isEmpty()) {
				Ui.centered(g, Lang.t("handyphone.no_signal.line1"), x + w / 2f, top + 26);
				Ui.centered(g, Lang.t("handyphone.no_signal.line2"), x + w / 2f, top + 38);
			} else {
				Ui.centered(g, Lang.t("handyphone.messages.empty"), x + w / 2f, top + 26);
				Ui.centered(g, Lang.t("handyphone.messages.your_number", PhoneData.formatNumber(os.data.number)), x + w / 2f, top + 38);
			}
		}
		for (int i = 0; i < list.size(); i++) {
			Conversation c = list.get(i);
			float ry = top + i * ROW_H - listScroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			boolean down = hit("conv:" + c.id, rx, Math.max(ry, top), rw, ROW_H - 2, () -> {
				showChat(c.id);
				os.openConversation(c.id);
				c.unread = 0;
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			String title = os.data.titleOf(c);
			avatar(g, c.group ? "g" + c.id : String.valueOf(os.data.peerOf(c)), c.group ? "#" : title, rx + 15, ry + ROW_H / 2f - 1, 10.5f);

			g.setFont(Ui.font(Font.PLAIN, 6.6f));
			String when = c.lastTime > 0 ? shortTime(c.lastTime) : "";
			float whenW = Ui.textWidth(g, when);
			g.setColor(c.unread > 0 ? accent : MUTED);
			Ui.text(g, when, rx + rw - 6 - whenW, ry + 12);

			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.2f));
			Ui.text(g, fit(g, title, rw - 42 - whenW), rx + 31, ry + 13f);
			g.setFont(Ui.font(c.unread > 0 ? Font.BOLD : Font.PLAIN, 7.6f));
			g.setColor(c.unread > 0 ? INK : MUTED);
			Ui.text(g, fit(g, os.data.lastPreview(c), rw - 54), rx + 31, ry + 24.5f);

			if (c.unread > 0) {
				String count = c.unread > 99 ? "99+" : Integer.toString(c.unread);
				g.setFont(Ui.font(Font.BOLD, 6.6f));
				float bw = Math.max(11, Ui.textWidth(g, count) + 6);
				g.setColor(accent);
				g.fill(new RoundRectangle2D.Float(rx + rw - 6 - bw, ry + 16, bw, 11, 11, 11));
				g.setColor(Color.WHITE);
				Ui.centered(g, count, rx + rw - 6 - bw / 2f, ry + 24);
			}
		}
		g.setClip(oldClip);
	}

	private void drawNew(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, Lang.t("handyphone.messages.new.title"), rx + 2, y + 18);
		field(g, os, "number", newNumber, Lang.t("handyphone.messages.new.number_hint"), rx, y + 26, rw, 20, accent);
		button(g, "open", Lang.t("handyphone.messages.new.open"), rx, y + 52, rw, 22, accent, () -> openNew(os));

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.2f));
		Ui.text(g, Lang.t("handyphone.messages.new.pick_contact"), rx + 2, y + 92);
		drawContactPicker(g, os, x, y + 98, w, h - 98, accent, false);
	}

	private void drawGroup(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, Lang.t("handyphone.messages.group.title"), rx + 2, y + 18);
		field(g, os, "title", groupTitle, Lang.t("handyphone.messages.group.name_hint"), rx, y + 26, rw, 20, accent);

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.2f));
		Ui.text(g, Lang.t("handyphone.messages.group.members", Integer.toString(groupPick.size())), rx + 2, y + 62);
		drawContactPicker(g, os, x, y + 68, w, h - 68 - 30, accent, true);
		button(g, "create", Lang.t("handyphone.messages.group.create"), rx, y + h - 26, rw, 22, accent, () -> createGroup(os));
	}

	/** Lista de contatos para tocar: abre a conversa, ou marca/desmarca para um grupo. */
	private void drawContactPicker(Graphics2D g, PhoneOS os, float x, float top, float w, float viewH, Color accent, boolean multi) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN, rowH = 24;
		Map<String, String> contacts = os.data.contacts;
		pickMaxScroll = Math.max(0, contacts.size() * rowH - viewH);
		pickScroll = Math.max(0, Math.min(pickScroll, pickMaxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (contacts.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.2f));
			Ui.centered(g, Lang.t("handyphone.messages.contacts_empty"), x + w / 2f, top + 18);
		}
		int i = 0;
		for (Map.Entry<String, String> contact : contacts.entrySet()) {
			float ry = top + i++ * rowH - pickScroll;
			if (ry + rowH < top || ry > top + viewH) {
				continue;
			}
			String num = contact.getKey();
			boolean picked = groupPick.contains(num);
			boolean down = hit("pick:" + num, rx, Math.max(ry, top), rw, rowH - 2, multi ? PhoneSound.SELECT : PhoneSound.LIST_ITEM, () -> {
				if (!multi) {
					os.backend.openDirect(num);
				} else if (!groupPick.remove(num)) {
					groupPick.add(num);
				}
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, rowH - 2, 8, 8));
			avatar(g, num, contact.getValue(), rx + 12, ry + rowH / 2f - 1, 7.5f);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 8.6f));
			Ui.text(g, fit(g, contact.getValue(), rw - 50), rx + 24, ry + 14.5f);
			if (multi) {
				float cx = rx + rw - 13, cy = ry + rowH / 2f - 1;
				g.setStroke(new BasicStroke(1.1f));
				g.setColor(picked ? accent : new Color(0xA9ABB5));
				if (picked) {
					g.fill(new Ellipse2D.Float(cx - 5.5f, cy - 5.5f, 11, 11));
					g.setColor(Color.WHITE);
					g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
					Path2D check = new Path2D.Float();
					check.moveTo(cx - 2.6f, cy + 0.2f);
					check.lineTo(cx - 0.8f, cy + 2.2f);
					check.lineTo(cx + 2.8f, cy - 2f);
					g.draw(check);
				} else {
					g.draw(new Ellipse2D.Float(cx - 5.5f, cy - 5.5f, 11, 11));
				}
			}
		}
		g.setClip(oldClip);
	}

	private void drawChat(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		Conversation c = os.data.conversation(chatId);
		float headH = 20, inputH = 26;

		// Mensagens, ancoradas embaixo e roláveis para cima.
		float top = y + headH, viewH = h - headH - inputH, bottom = top + viewH;
		float maxBubble = w * 0.72f, pad = 5.5f, lineH = 10.2f;
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));

		// Mede tudo de baixo para cima para saber a altura total (e limitar a rolagem).
		Font textFont = Ui.font(Font.PLAIN, 8.4f);
		g.setFont(textFont);
		List<List<String>> wrapped = new ArrayList<>();
		float total = 6, photoW = Math.min(maxBubble - 5, 96f);
		for (Message m : c.messages) {
			boolean named = c.group && !m.sender().equals(os.data.number);
			if (m.system()) {
				wrapped.add(List.of());
				total += SYS_H;
				continue;
			}
			if (m.loc() != null) {
				wrapped.add(List.of());
				total += LOC_H + 9 + (named ? 8 : 0);
				continue;
			}
			if (m.photo() != 0) {
				wrapped.add(List.of());
				total += photoHeight(os, m.photo(), photoW) + 5 + 9 + (named ? 8 : 0);
				continue;
			}
			List<String> lines = wrap(g, m.text(), maxBubble - 2 * pad);
			wrapped.add(lines);
			total += lines.size() * lineH + 2 * pad - 2 + 9 + (named ? 8 : 0);
		}
		chatMaxScroll = Math.max(0, total - viewH);
		chatScroll = Math.max(0, Math.min(chatScroll, chatMaxScroll));

		float cursor = bottom - 4 + chatScroll;
		for (int i = c.messages.size() - 1; i >= 0 && cursor > top; i--) {
			Message m = c.messages.get(i);
			if (m.system()) {
				float sy = cursor - SYS_H;
				if (sy < bottom) {
					g.setColor(MUTED);
					g.setFont(Ui.font(Font.PLAIN, 7f));
					Ui.centered(g, fit(g, os.data.systemText(m.text()), w - 2 * MARGIN), x + w / 2f, sy + 9.5f);
				}
				cursor = sy;
				continue;
			}
			List<String> lines = wrapped.get(i);
			boolean mine = m.sender().equals(os.data.number), named = c.group && !mine, photo = m.photo() != 0, loc = m.loc() != null;
			float bh, bw, widest = 0, ph = photo ? photoHeight(os, m.photo(), photoW) : 0;
			g.setFont(textFont);
			if (loc) {
				bh = LOC_H;
				bw = Math.min(maxBubble, 118f);
			} else if (photo) {
				bh = ph + 5;
				bw = photoW + 5;
			} else {
				bh = lines.size() * lineH + 2 * pad - 2;
				for (String line : lines) {
					widest = Math.max(widest, Ui.textWidth(g, line));
				}
				bw = widest + 2 * pad;
			}
			float bx = mine ? x + w - MARGIN - bw : x + MARGIN;
			float by = cursor - 9 - bh;
			if (by < bottom) {
				g.setColor(mine ? accent : Color.WHITE);
				g.fill(new RoundRectangle2D.Float(bx, by, bw, bh, 10, 10));
				if (!mine) {
					g.setStroke(new BasicStroke(0.6f));
					g.setColor(LINE);
					g.draw(new RoundRectangle2D.Float(bx, by, bw, bh, 10, 10));
				}
				if (loc) {
					drawLocationBubble(g, os, m, mine, bx, by, bw, bh, Math.max(by, top), Math.min(by + bh, bottom), accent);
				}
				if (photo) {
					drawPhotoBubble(g, os, m, bx + 2.5f, by + 2.5f, photoW, ph, Math.max(by, top), Math.min(by + bh, bottom));
				}
				g.setColor(mine ? Color.WHITE : INK);
				g.setFont(textFont);
				for (int l = 0; l < lines.size(); l++) {
					Ui.text(g, lines.get(l), bx + pad, by + pad + 6.6f + l * lineH);
				}
				g.setColor(MUTED);
				g.setFont(Ui.font(Font.PLAIN, 5.8f));
				String when = shortTime(m.time());
				Ui.text(g, when, mine ? x + w - MARGIN - Ui.textWidth(g, when) - 1 : bx + 1, by + bh + 6.6f);
				if (named) {
					g.setFont(Ui.font(Font.BOLD, 6.4f));
					g.setColor(nameColor(m.sender()));
					Ui.text(g, os.data.displayName(m.sender()), bx + 2, by - 2.4f);
				}
			}
			cursor = by - (named ? 8 : 0);
		}
		if (c.messages.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			Ui.centered(g, Lang.t("handyphone.messages.say_hi"), x + w / 2f, top + viewH / 2f);
		}
		g.setClip(oldClip);

		// Cabeçalho da conversa.
		g.setColor(new Color(0xE6E8EE));
		g.fill(new Rectangle2D.Float(x, y, w, headH));
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, y + headH - 0.6f, w, 0.6f));
		String title = os.data.titleOf(c), peer = os.data.peerOf(c);
		boolean unknown = peer != null && !os.data.contacts.containsKey(peer);
		boolean blockedPeer = peer != null && os.data.isBlocked(peer);
		// O botão do menu (três pontos) fica na ponta direita; o resto do cabeçalho se ajusta à esquerda dele.
		float menuX = x + w - MARGIN - 14, right = menuX - 4;
		// Conversa direta, com ligações disponíveis e sem bloqueio: o botão de ligar fica ao lado do menu.
		boolean canCall = (peer != null || c.group) && os.data.callsAvailable && !blockedPeer;
		if (canCall) {
			callButton(g, "call", right - 8, y + headH / 2f, 15, () -> {
				if (c.group) {
					os.callGroup(c.id);
				} else {
					os.callFromChat(peer);
				}
			});
			right -= 20;
		}
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.2f));
		Ui.text(g, fit(g, title, right - x - MARGIN - (unknown || blockedPeer ? 50 : c.group ? 58 : 0)), x + MARGIN, y + 13.4f);
		if (c.group) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 6.6f));
			String count = Lang.plural("handyphone.messages.people", c.members.size());
			Ui.text(g, count, right - Ui.textWidth(g, count), y + 13f);
		} else if (blockedPeer) {
			g.setColor(new Color(0xC8463C));
			g.setFont(Ui.font(Font.BOLD, 6.8f));
			String label = Lang.t("handyphone.messages.blocked_tag");
			Ui.text(g, label, right - Ui.textWidth(g, label), y + 12.6f);
		} else if (unknown) {
			boolean down = hit("save", right - 50, y + 3, 50, 14, PhoneSound.BUTTON, () -> os.editContact(null, peer));
			g.setColor(down ? Ui.shade(accent, 0.8f) : accent);
			g.fill(new RoundRectangle2D.Float(right - 50, y + 3, 50, 14, 14, 14));
			g.setColor(Color.WHITE);
			g.setFont(Ui.font(Font.BOLD, 6.8f));
			Ui.centered(g, Lang.t("handyphone.messages.add_contact"), right - 25, y + 12.6f);
		}
		boolean menuDown = hit("menu", menuX - 3, y + 1, 20, headH - 2, PhoneSound.BUTTON, () -> chatMenu = !chatMenu);
		g.setColor(menuDown || chatMenu ? INK : MUTED);
		for (int dot = 0; dot < 3; dot++) {
			g.fill(new Ellipse2D.Float(menuX + 5.2f, y + 5.2f + dot * 3.6f, 2.2f, 2.2f));
		}

		// Campo de digitação e botão de enviar.
		float iy = y + h - inputH;
		g.setColor(new Color(0xE6E8EE));
		g.fill(new Rectangle2D.Float(x, iy, w, inputH));
		drawAttach(g, os, x + MARGIN - 4, iy + inputH / 2f, accent);
		// O botão de localização só existe se o servidor deixa mandar localização.
		float shift = os.data.map.messagesEnabled ? 20 : 0;
		if (os.data.map.messagesEnabled) {
			drawLocationButton(g, os, x + MARGIN + 16, iy + inputH / 2f, accent);
		}
		field(g, os, "input", input, Lang.t("handyphone.messages.input_hint"), x + MARGIN + 14 + shift, iy + 3, w - 2 * MARGIN - 36 - shift, inputH - 6, accent);
		float sx = x + w - MARGIN - 9, sy = iy + inputH / 2f;
		boolean down = hit("send", sx - 11, iy + 2, 22, inputH - 4, PhoneSound.BUTTON, () -> send(os));
		g.setColor(down ? Ui.shade(accent, 0.8f) : accent);
		g.fill(new Ellipse2D.Float(sx - 9, sy - 9, 18, 18));
		Path2D plane = new Path2D.Float();
		plane.moveTo(sx - 3.6f, sy - 4.4f);
		plane.lineTo(sx + 4.8f, sy);
		plane.lineTo(sx - 3.6f, sy + 4.4f);
		plane.lineTo(sx - 1.8f, sy);
		plane.closePath();
		g.setColor(Color.WHITE);
		g.fill(plane);
		if (locationMenu) {
			drawLocationMenu(g, os, x, y + headH, w, h - headH - inputH, accent);
		}
		if (chatMenu) {
			drawChatMenu(g, os, c, x, y, w, h, headH);
		}
	}

	/** Menu do cabeçalho: bloquear (ou desbloquear) o número, numa conversa individual; sair do grupo, num grupo. */
	private void drawChatMenu(Graphics2D g, PhoneOS os, Conversation c, float x, float y, float w, float h, float headH) {
		hit("menuscrim", x, y, w, h, null, () -> chatMenu = false);
		String peer = os.data.peerOf(c);
		boolean blocked = peer != null && os.data.isBlocked(peer);
		String label = Lang.t(c.group ? "handyphone.messages.menu.leave" : blocked ? "handyphone.block.menu_unblock" : "handyphone.block.menu_block");
		float mw = 112, mh = 26, mx = x + w - MARGIN - mw, my = y + headH + 2;
		g.setColor(new Color(0, 0, 0, 50));
		g.fill(new RoundRectangle2D.Float(mx, my + 1.5f, mw, mh, 10, 10));
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 10, 10));
		hit("menubg", mx, my, mw, mh, null, () -> { });
		button(g, "menuitem", label, mx + 4, my + 3, mw - 8, 20, c.group || !blocked ? new Color(0xD14336) : Ui.shade(new Color(0x1FAF38), 0.9f), () -> {
			chatMenu = false;
			if (c.group) {
				os.confirm(Lang.t("handyphone.messages.leave.title"), Lang.t("handyphone.messages.leave.text", c.title), Lang.t("handyphone.messages.leave.confirm"), true, () -> {
					os.backend.leaveGroup(c.id);
					show(View.LIST);
				});
			} else if (blocked) {
				os.backend.block(peer, false);
				os.notify(Lang.t("handyphone.banner.blocked"), Lang.t("handyphone.block.unblocked", PhoneData.formatNumber(peer)), -1);
			} else if (peer != null) {
				os.confirm(Lang.t("handyphone.block.confirm_title", os.data.displayName(peer)),
					Lang.t("handyphone.block.confirm_text"), Lang.t("handyphone.block.confirm_button"), true,
					() -> os.backend.block(peer, true));
			}
		});
	}

	/** Botão de localização (alfinete) ao lado do clipe. */
	private void drawLocationButton(Graphics2D g, PhoneOS os, float x, float cy, Color accent) {
		boolean down = hit("locbtn", x - 2, cy - 11, 24, 22, PhoneSound.BUTTON, () -> locationMenu = !locationMenu);
		g.setColor(down || locationMenu ? PRESSED : new Color(0xD3D5DD));
		g.fill(new Ellipse2D.Float(x, cy - 9, 18, 18));
		MapApp.pin(g, x + 9, cy + 6, 0.62f, down ? Ui.shade(accent, 0.8f) : accent);
	}

	/** Duas opções sobre a linha de digitação; tocar fora fecha. */
	private void drawLocationMenu(Graphics2D g, PhoneOS os, float x, float top, float w, float viewH, Color accent) {
		hit("locscrim", x, top, w, viewH, null, () -> locationMenu = false);
		float mw = 124, mh = 54, mx = x + MARGIN, my = top + viewH - mh - 4;
		g.setColor(new Color(0, 0, 0, 50));
		g.fill(new RoundRectangle2D.Float(mx, my + 1.5f, mw, mh, 10, 10));
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(mx, my, mw, mh, 10, 10));
		hit("locmenubg", mx, my, mw, mh, null, () -> { });
		button(g, "sendloc", Lang.t("handyphone.messages.loc.send"), mx + 5, my + 5, mw - 10, 20, accent, () -> {
			locationMenu = false;
			os.backend.sendLocation(chatId);
			os.playSound(PhoneSound.MESSAGE_SENT);
			chatScroll = 0;
		});
		button(g, "pickloc", Lang.t("handyphone.messages.loc.pick"), mx + 5, my + 29, mw - 10, 20, Ui.shade(accent, 0.8f), () -> {
			locationMenu = false;
			os.pickPointFor(chatId);
		});
	}

	/** Cartão da mensagem de localização: ícone, "Localização" ou "Ponto marcado", coordenadas e distância; tocar abre no Mapa. */
	private void drawLocationBubble(Graphics2D g, PhoneOS os, Message m, boolean mine, float x, float y, float w, float h, float hitTop, float hitBottom, Color accent) {
		PhoneData.Loc loc = m.loc();
		g.setColor(mine ? new Color(255, 255, 255, 46) : new Color(0xEEF0F4));
		g.fill(new RoundRectangle2D.Float(x + 2.5f, y + 2.5f, w - 5, h - 5, 8, 8));
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(x + 6, y + h / 2f - 9, 18, 18));
		MapApp.pin(g, x + 15, y + h / 2f + 6.5f, 0.7f, loc.stamped() ? new Color(0xE5392E) : new Color(0x1F9E89));
		Color main = mine ? Color.WHITE : INK, sub = mine ? new Color(255, 255, 255, 215) : MUTED;
		g.setColor(main);
		g.setFont(Ui.font(Font.BOLD, 7.8f));
		// O tipo vem sempre do celular e fica separado do nome: um local salvo vira "Local salvo: <nome>", então um nome
		// escolhido por outro jogador nunca se passa por "Localização" (a posição carimbada pelo servidor).
		String title = !loc.label().isEmpty() ? Lang.t("handyphone.preview.place", loc.label()) : Lang.t(loc.stamped() ? "handyphone.preview.location" : "handyphone.preview.pin");
		Ui.text(g, fit(g, title, w - 34), x + 28, y + 13);
		g.setColor(sub);
		g.setFont(Ui.font(Font.PLAIN, 6.6f));
		Ui.text(g, fit(g, Geo.coords(loc.x(), loc.hasY(), loc.y(), loc.z()), w - 34), x + 28, y + 23.5f);
		String distance = Geo.distanceFrom(os.data.self, loc.dimension(), loc.x(), loc.z());
		Ui.text(g, fit(g, distance.isEmpty() ? Lang.t("handyphone.messages.loc.tap_to_open") : distance, w - 34), x + 28, y + 33);
		if (hitBottom - hitTop > 2) {
			String who = os.data.displayName(m.sender());
			hit("loc:" + m.seq(), x, hitTop, w, hitBottom - hitTop, PhoneSound.LIST_ITEM, () -> os.openMapAt(
				(!loc.label().isEmpty() ? Lang.t("handyphone.messages.loc.place_from", loc.label(), who) : Lang.t(loc.stamped() ? "handyphone.messages.loc.stamped_from" : "handyphone.messages.loc.pin_from", who)), loc.dimension(), loc.x(), loc.hasY(), loc.y(), loc.z(), null
			));
		}
	}

	/** Altura da miniatura dentro do balão, pela proporção dela (4:3 enquanto não chegou). */
	private static float photoHeight(PhoneOS os, long photoId, float width) {
		BufferedImage thumb = os.data.photos.thumb(photoId, os.backend, os.time);
		float ratio = thumb != null ? (float) thumb.getHeight() / thumb.getWidth() : 0.75f;
		return width * Math.max(0.6f, Math.min(1.5f, ratio));
	}

	/** Miniatura dentro do balão; tocar nela abre a foto inteira. */
	private void drawPhotoBubble(Graphics2D g, PhoneOS os, Message m, float x, float y, float w, float h, float hitTop, float hitBottom) {
		BufferedImage thumb = os.data.photos.thumb(m.photo(), os.backend, os.time);
		if (thumb != null) {
			drawCover(g, thumb, x, y, w, h, 8);
		} else {
			g.setColor(new Color(0xD3D5DD));
			g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.2f));
			Ui.centered(g, os.data.photos.isGone(m.photo()) ? Lang.t("handyphone.photo.deleted") : Lang.t("handyphone.loading"), x + w / 2f, y + h / 2f + 2.6f);
		}
		if (hitBottom - hitTop > 2 && !os.data.photos.isGone(m.photo())) {
			hit("photo:" + m.seq(), x, hitTop, w, hitBottom - hitTop, PhoneSound.LIST_ITEM, () -> openPhoto(os, m.photo()));
		}
	}

	private void openPhoto(PhoneOS os, long photoId) {
		viewer.open(photoId, os);
		show(View.PHOTO);
	}

	/** Botão de anexar (clipe): abre a galeria para escolher a foto a enviar nesta conversa. */
	private void drawAttach(Graphics2D g, PhoneOS os, float x, float cy, Color accent) {
		boolean down = hit("attach", x - 2, cy - 11, 24, 22, PhoneSound.BUTTON, () -> os.openGalleryToSend(chatId));
		g.setColor(down ? PRESSED : new Color(0xD3D5DD));
		g.fill(new Ellipse2D.Float(x, cy - 9, 18, 18));
		// Ícone de foto: moldura, sol e montanha.
		g.setColor(down ? Ui.shade(accent, 0.8f) : accent);
		g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new RoundRectangle2D.Float(x + 4, cy - 5, 10, 9, 2.5f, 2.5f));
		g.fill(new Ellipse2D.Float(x + 10, cy - 3.6f, 2, 2));
		Path2D hill = new Path2D.Float();
		hill.moveTo(x + 4.6f, cy + 3.4f);
		hill.lineTo(x + 8, cy - 0.4f);
		hill.lineTo(x + 10.4f, cy + 1.8f);
		hill.lineTo(x + 11.6f, cy + 0.6f);
		hill.lineTo(x + 13.4f, cy + 3.4f);
		g.fill(hill);
	}

	private static Color nameColor(String number) {
		return Color.getHSBColor((number.hashCode() & 0xFFFF) / 65535f, 0.6f, 0.62f);
	}

	private record WrapKey(String text, int widthBits, java.awt.Font font) {
	}

	/** As últimas quebras de linha (o chat redesenha as mesmas mensagens a cada quadro; texto sem espaços, como CJK, custa caro de medir). */
	private static final java.util.Map<WrapKey, List<String>> WRAPS = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(java.util.Map.Entry<WrapKey, List<String>> eldest) {
			return size() > 512;
		}
	};

	/** Quebra o texto em linhas que cabem na largura; palavras enormes são partidas (por grafema: nunca no meio de um emoji). */
	static List<String> wrap(Graphics2D g, String text, float width) {
		WrapKey key = new WrapKey(text, Float.floatToIntBits(width), g.getFont());
		synchronized (WRAPS) {
			List<String> cached = WRAPS.get(key);
			if (cached != null) {
				return cached;
			}
		}
		List<String> lines = List.copyOf(wrapUncached(g, text, width));
		synchronized (WRAPS) {
			WRAPS.put(key, lines);
		}
		return lines;
	}

	private static List<String> wrapUncached(Graphics2D g, String text, float width) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			while (Ui.textWidth(g, word) > width) {
				if (!line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
				}
				// O maior começo da palavra que cabe (pelo menos um grafema; nunca no meio de um emoji).
				int end = TextFallback.fitPrefix(g, word, width);
				lines.add(word.substring(0, end));
				word = word.substring(end);
			}
			String candidate = line.isEmpty() ? word : line + " " + word;
			if (Ui.textWidth(g, candidate) > width) {
				lines.add(line.toString());
				line.setLength(0);
				line.append(word);
			} else {
				line.setLength(0);
				line.append(candidate);
			}
		}
		if (!line.isEmpty() || lines.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	/** Hora se for de hoje, senão o dia. */
	private static String shortTime(long millis) {
		LocalDateTime when = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
		return when.toLocalDate().equals(LocalDate.now()) ? when.format(Lang.format("handyphone.format.time")) : when.format(Lang.format("handyphone.format.day"));
	}
}
