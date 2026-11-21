package dev.migzu.handyphone.client.os;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A tela da ligação: chamando, chamada recebida, em ligação e encerrada, desenhada sobre a tela inteira do aparelho (e a
 * barra verde, quando ela foi minimizada). Igual nos quatro temas no que importa (os botões ficam no mesmo lugar); o tema só
 * troca o visual do fundo. Não decide nada: o estado da ligação vem do servidor ({@link PhoneData.Call}).
 */
final class CallScreen extends AppContent {
	private static final Color GREEN = new Color(0x2FB75A), RED = new Color(0xE5392E);
	private static final float BAR_H = 14f;

	/** Cores do fundo por tema: {topo, base}. */
	private static Color[] palette(String themeId) {
		return switch (themeId) {
			case "aero" -> new Color[] {new Color(0x1B93BA), new Color(0x0B4D6E)};
			case "flat" -> new Color[] {new Color(0x263040), new Color(0x161B25)};
			case "modern" -> new Color[] {new Color(0x211B3A), new Color(0x0C0A14)};
			default -> new Color[] {new Color(0x1E4F86), new Color(0x0A2342)};
		};
	}

	/**
	 * Centro de um botão redondo da tela, em coordenadas da face: {@code slot} -1 = esquerda (Recusar, Mudo), 0 = centro
	 * (Cancelar), 1 = direita (Atender, Desligar). Os testes tocam por aqui.
	 */
	static float[] buttonCenter(float[] screen, int slot) {
		float cx = screen[0] + screen[2] / 2f, top = screen[1] + screen[3] - 82;
		return new float[] {cx + slot * 38, top + 17};
	}

	/** Retângulo da barra minimizada (em coordenadas da face). */
	static float[] barRect(float[] screen) {
		return new float[] {screen[0], screen[1], screen[2], BAR_H};
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		if (!os.callScreenVisible()) {
			drawBar(g, os, new float[] {x, y, w, h});
			return;
		}
		PhoneData.Call call = os.data.call;
		boolean modern = "modern".equals(os.themeId()), aero = "aero".equals(os.themeId()), retro = "retro".equals(os.themeId());
		Color[] colors = palette(os.themeId());
		g.setPaint(new GradientPaint(x, y, colors[0], x, y + h, colors[1]));
		g.fill(new Rectangle2D.Float(x, y, w, h));
		if (aero) {
			// Brilho de vidro na metade de cima.
			g.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 70), x, y + h * 0.5f, new Color(255, 255, 255, 0)));
			g.fill(new Rectangle2D.Float(x, y, w, h * 0.5f));
		}
		g.setStroke(new BasicStroke(1f));

		boolean group = call.conversation != 0;
		PhoneData.Conversation groupConversation = group ? os.data.conversation(call.conversation) : null;
		String peer = call.firstNumber();
		String name = group ? (groupConversation != null ? groupConversation.title : Lang.t("handyphone.call.group_default")) : peer.isEmpty() ? "" : os.data.displayName(peer);
		boolean known = !group && !peer.isEmpty() && os.data.contacts.containsKey(peer);
		float cx = x + w / 2f;

		// Situação.
		String status = switch (call.state) {
			case PhoneData.CALL_OUTGOING -> Lang.t("handyphone.call.calling") + ".".repeat(1 + (int) (os.time * 2.5) % 3);
			case PhoneData.CALL_INCOMING -> Lang.t("handyphone.call.incoming");
			case PhoneData.CALL_ACTIVE -> duration((PhoneClock.millis() - call.answeredLocalMs) / 1000);
			default -> endedText(call);
		};
		g.setColor(new Color(255, 255, 255, 190));
		g.setFont(Ui.font(retro ? Font.BOLD : Font.PLAIN, 8.6f));
		Ui.centered(g, status, cx, y + 54);

		// Quem é.
		float avatarY = y + 96;
		pulse(g, os, cx, avatarY, call.state == PhoneData.CALL_INCOMING || call.state == PhoneData.CALL_OUTGOING);
		if (known) {
			avatar(g, peer, name, cx, avatarY, 27);
		} else {
			// Número que não é contato: um boneco no lugar da inicial (que seria só o primeiro dígito).
			g.setColor(new Color(255, 255, 255, 60));
			g.fill(new Ellipse2D.Float(cx - 27, avatarY - 27, 54, 54));
			AffineTransform t = g.getTransform();
			g.translate(cx - 17 * 1.1f, avatarY - 17 * 1.1f);
			g.scale(1.1f, 1.1f);
			Ui.glyph(g, "contacts", new Color(255, 255, 255, 215), new Color(0, 0, 0, 0));
			g.setTransform(t);
			g.setStroke(new BasicStroke(1f));
		}
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 13f));
		Ui.centered(g, fit(g, name.isEmpty() ? Lang.t("handyphone.call.default_name") : name, w - 24), cx, y + 148);
		String sub = "";
		if (known) {
			sub = PhoneData.formatNumber(peer);
		} else if (group) {
			int joined = 1;
			for (PhoneData.CallPart part : call.parts) {
				if (part.joined()) {
					joined++;
				}
			}
			sub = switch (call.state) {
				case PhoneData.CALL_INCOMING -> Lang.t("handyphone.call.called_you", os.data.displayName(peer));
				case PhoneData.CALL_OUTGOING -> Lang.t("handyphone.call.calling_group");
				case PhoneData.CALL_ACTIVE -> Lang.plural("handyphone.call.in_call", joined);
				default -> "";
			};
		}
		if (!sub.isEmpty()) {
			g.setColor(new Color(255, 255, 255, 150));
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.centered(g, fit(g, sub, w - 20), cx, y + 161);
		}

		float by = y + h - 82;
		// Em grupo, quem está na chamada e quem ainda toca; os avisos de voz de cada um ficam na linha dele.
		if (group && (call.state == PhoneData.CALL_ACTIVE || call.state == PhoneData.CALL_OUTGOING)) {
			// Se o aviso "você está sem voz" aparece (até duas linhas), a lista começa abaixo dele.
			float listTop = y + 172 + (call.voice ? 0 : 26);
			drawParts(g, os, call, x, listTop, w, by - 8 - listTop);
		}
		// Avisos de voz.
		if (call.state == PhoneData.CALL_ACTIVE) {
			drawVoiceWarning(g, os, call, cx, group ? y + 168 : y + 176, w);
		}

		switch (call.state) {
			case PhoneData.CALL_INCOMING -> {
				roundButton(g, "decline", Lang.t("handyphone.call.decline"), cx - 38, by, RED, true, () -> os.declineCall());
				roundButton(g, "answer", Lang.t("handyphone.call.answer"), cx + 38, by, GREEN, false, () -> os.answerCall());
			}
			case PhoneData.CALL_OUTGOING -> roundButton(g, "hangup", Lang.t("handyphone.cancel"), cx, by, RED, true, () -> os.declineCall());
			case PhoneData.CALL_ACTIVE -> {
				micButton(g, os, cx - 38, by, call.muted);
				roundButton(g, "hangup", Lang.t("handyphone.call.hangup"), cx + 38, by, RED, true, () -> os.declineCall());
			}
			default -> {
				// Encerrada: some sozinha; tocar na tela também a fecha.
				hit("close", x, y, w, h, null, () -> os.dismissCall());
			}
		}
		if (modern) {
			// Linha fina embaixo, como a barra de gestos do tema.
			g.setColor(new Color(255, 255, 255, 60));
			g.fill(new RoundRectangle2D.Float(cx - 20, y + h - 8, 40, 2.4f, 2, 2));
		}
	}

	/** As pessoas da chamada em grupo, uma por linha: bolinha verde = na chamada, cinza = ainda tocando; e as marcas de mudo e sem voz. */
	private void drawParts(Graphics2D g, PhoneOS os, PhoneData.Call call, float x, float top, float w, float room) {
		int rows = Math.max(0, (int) (room / 10.5f));
		if (rows == 0) {
			return;
		}
		java.util.List<PhoneData.CallPart> parts = call.parts;
		int shown = parts.size() <= rows ? parts.size() : Math.max(0, rows - 1);
		g.setFont(Ui.font(Font.PLAIN, 7.6f));
		for (int i = 0; i < shown; i++) {
			PhoneData.CallPart part = parts.get(i);
			float ry = top + i * 10.5f;
			g.setColor(part.joined() ? GREEN : new Color(255, 255, 255, 90));
			g.fill(new Ellipse2D.Float(x + 14, ry + 2, 5, 5));
			g.setColor(part.joined() ? Color.WHITE : new Color(255, 255, 255, 140));
			Ui.text(g, fit(g, os.data.displayName(part.number()), w - 90), x + 24, ry + 7.6f);
			String mark = !part.joined() ? Lang.t("handyphone.call.mark.calling") : part.muted() ? Lang.t("handyphone.call.mark.muted") : !part.voice() ? Lang.t("handyphone.call.mark.no_voice") : "";
			if (!mark.isEmpty()) {
				g.setColor(part.joined() && !part.muted() ? new Color(0xFFD27A) : new Color(255, 255, 255, 120));
				Ui.text(g, mark, x + w - 14 - Ui.textWidth(g, mark), ry + 7.6f);
			}
		}
		if (shown < parts.size()) {
			g.setColor(new Color(255, 255, 255, 120));
			Ui.text(g, Lang.plural("handyphone.call.more", parts.size() - shown), x + 24, top + shown * 10.5f + 7.6f);
		}
	}

	private static String endedText(PhoneData.Call call) {
		return switch (call.reason) {
			case PhoneData.END_NO_ANSWER -> Lang.t("handyphone.call.ended.no_answer");
			case PhoneData.END_DECLINED -> Lang.t("handyphone.call.ended.declined");
			case PhoneData.END_BUSY -> Lang.t("handyphone.call.ended.busy");
			case PhoneData.END_FAILED -> Lang.t("handyphone.call.ended.failed");
			default -> call.endedSeconds > 0 ? Lang.t("handyphone.call.ended.duration", duration(call.endedSeconds)) : Lang.t("handyphone.call.ended.plain");
		};
	}

	static String duration(long seconds) {
		seconds = Math.max(0, seconds);
		long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
	}

	/** Ondas que se abrem em volta da foto enquanto toca. */
	private static void pulse(Graphics2D g, PhoneOS os, float cx, float cy, boolean ringing) {
		if (!ringing) {
			return;
		}
		Composite old = g.getComposite();
		for (int i = 0; i < 2; i++) {
			float phase = (float) ((os.time * 0.9 + i * 0.5) % 1.0);
			float r = 27 + phase * 16;
			g.setComposite(AlphaComposite.SrcOver.derive(0.28f * (1 - phase)));
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.4f));
			g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
		}
		g.setComposite(old);
		g.setStroke(new BasicStroke(1f));
	}

	private void drawVoiceWarning(Graphics2D g, PhoneOS os, PhoneData.Call call, float cx, float y, float w) {
		String text = null;
		if (!call.voice) {
			text = Lang.t("handyphone.call.no_voice_self");
		} else {
			for (PhoneData.CallPart part : call.parts) {
				if (call.conversation == 0 && part.joined() && !part.voice()) {
					text = Lang.t("handyphone.call.no_voice_peer", os.data.displayName(part.number()));
					break;
				}
			}
		}
		if (text == null) {
			if (call.muted) {
				g.setColor(new Color(255, 255, 255, 150));
				g.setFont(Ui.font(Font.PLAIN, 7.4f));
				Ui.centered(g, Lang.t("handyphone.call.you_muted"), cx, y + 4);
			}
			return;
		}
		g.setFont(Ui.font(Font.PLAIN, 7.2f));
		java.util.List<String> lines = wrapLines(g, text, w - 26);
		float boxH = lines.size() * 9.4f + 9;
		g.setColor(new Color(0, 0, 0, 90));
		g.fill(new RoundRectangle2D.Float(cx - (w - 18) / 2f, y - 6, w - 18, boxH, 8, 8));
		g.setColor(new Color(0xFFD27A));
		for (int i = 0; i < lines.size(); i++) {
			Ui.centered(g, lines.get(i), cx, y + 5 + i * 9.4f);
		}
	}

	private static java.util.List<String> wrapLines(Graphics2D g, String text, float width) {
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			String next = line.isEmpty() ? word : line + " " + word;
			if (Ui.textWidth(g, next) > width && !line.isEmpty()) {
				lines.add(line.toString());
				line = new StringBuilder(word);
			} else {
				line = new StringBuilder(next);
			}
		}
		if (!line.isEmpty()) {
			lines.add(line.toString());
		}
		return lines;
	}

	/** Botão redondo com o fone (desligar vira o fone deitado) e o rótulo embaixo. */
	private void roundButton(Graphics2D g, String key, String label, float cx, float top, Color color, boolean hangUp, Runnable action) {
		float r = 17;
		boolean down = hit(key, cx - r - 4, top - 4, r * 2 + 8, r * 2 + 22, PhoneSound.BUTTON, action);
		g.setColor(down ? Ui.shade(color, 0.8f) : color);
		g.fill(new Ellipse2D.Float(cx - r, top, r * 2, r * 2));
		AffineTransform t = g.getTransform();
		g.translate(cx, top + r);
		if (hangUp) {
			g.rotate(Math.toRadians(135));
		}
		g.scale(18 / 34f, 18 / 34f);
		g.translate(-17, -17);
		Ui.glyph(g, "phone", Color.WHITE, color);
		g.setTransform(t);
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(255, 255, 255, 200));
		g.setFont(Ui.font(Font.PLAIN, 7.4f));
		Ui.centered(g, label, cx, top + r * 2 + 11);
	}

	private void micButton(Graphics2D g, PhoneOS os, float cx, float top, boolean muted) {
		float r = 17;
		boolean down = hit("mute", cx - r - 4, top - 4, r * 2 + 8, r * 2 + 22, PhoneSound.BUTTON, () -> os.toggleCallMute());
		g.setColor(muted ? Color.WHITE : (down ? new Color(255, 255, 255, 70) : new Color(255, 255, 255, 40)));
		g.fill(new Ellipse2D.Float(cx - r, top, r * 2, r * 2));
		Color ink = muted ? new Color(0x23252C) : Color.WHITE;
		float my = top + r;
		g.setColor(ink);
		g.fill(new RoundRectangle2D.Float(cx - 3.2f, my - 8, 6.4f, 10.5f, 6.4f, 6.4f));
		g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Arc2D.Float(cx - 6, my - 7, 12, 12.5f, 200, 140, Arc2D.OPEN));
		g.draw(new Line2D.Float(cx, my + 5.4f, cx, my + 8.5f));
		if (muted) {
			g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(RED);
			g.draw(new Line2D.Float(cx - 8, my - 8, cx + 8, my + 8));
		}
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(255, 255, 255, 200));
		g.setFont(Ui.font(Font.PLAIN, 7.4f));
		Ui.centered(g, Lang.t(muted ? "handyphone.call.unmute" : "handyphone.call.mute"), cx, top + r * 2 + 11);
	}

	/** A barra verde no topo da tela quando a ligação segue com a tela minimizada; tocar nela reabre a tela da ligação. */
	void drawBar(Graphics2D g, PhoneOS os, float[] screen) {
		PhoneData.Call call = os.data.call;
		float[] r = barRect(screen);
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(screen[0], screen[1], screen[2], screen[3]));
		boolean down = hit("bar", r[0], r[1], r[2], r[3], PhoneSound.BUTTON, () -> os.expandCall());
		g.setColor(down ? Ui.shade(GREEN, 0.85f) : GREEN);
		g.fill(new Rectangle2D.Float(r[0], r[1], r[2], r[3]));
		AffineTransform t = g.getTransform();
		g.translate(r[0] + 5, r[1] + 2);
		g.scale(10 / 34f, 10 / 34f);
		Ui.glyph(g, "phone", Color.WHITE, GREEN);
		g.setTransform(t);
		g.setStroke(new BasicStroke(1f));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		String text = call.state == PhoneData.CALL_ACTIVE ? Lang.t("handyphone.call.bar.active", duration((PhoneClock.millis() - call.answeredLocalMs) / 1000)) : Lang.t("handyphone.call.bar.calling");
		Ui.text(g, text, r[0] + 18, r[1] + 9.8f);
		g.setClip(oldClip);
	}
}
