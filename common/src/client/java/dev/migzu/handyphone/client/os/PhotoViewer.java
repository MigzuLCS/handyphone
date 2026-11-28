package dev.migzu.handyphone.client.os;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * A foto aberta em tela cheia dentro de um app (Galeria ou Mensagens). Mostra a miniatura
 * ampliada enquanto a foto inteira não chega do servidor. Se a foto é da galeria do
 * jogador, oferece enviar para uma conversa e apagar.
 *
 * <p>Não é um app: usa as áreas de toque do app que o hospeda.
 */
final class PhotoViewer {
	private final AppContent host;
	private long photoId;
	private boolean ownPhoto;
	/** Quem mandou a foto (número), quando aberta numa conversa; vazio na Galeria. */
	private String sender = "";
	/** Painel de informações aberto (botão "i"). */
	private boolean infoOpen;
	/** O primeiro toque em "Apagar" só arma; o segundo apaga de verdade. */
	/** Zoom (1 = a foto inteira cabe na tela, até {@link #MAX_ZOOM}) e deslocamento da foto ampliada, em pontos da tela. */
	private float zoom = 1f, panX, panY;
	/** Arrastando a foto ampliada: último ponto do dedo. */
	private boolean dragging;
	private float dragX, dragY;
	/** Área da foto no último desenho {x, y, w, h}: só arrastar começando nela move a foto. */
	private float[] box;
	private static final float MAX_ZOOM = 4f;
	/** Zoom por clique da roda (o clique chega aos poucos, então o zoom desliza). */
	private static final double ZOOM_STEP = 1.3;

	PhotoViewer(AppContent host) {
		this.host = host;
	}

	void open(long id, PhoneOS os) {
		open(id, os, "");
	}

	/** Abre a foto; {@code from} é o número de quem a mandou (nas Mensagens), ou vazio. */
	void open(long id, PhoneOS os, String from) {
		sender = from == null ? "" : from;
		infoOpen = false;
		photoId = id;
		zoom = 1f;
		panX = panY = 0f;
		dragging = false;
		ownPhoto = false;
		for (PhoneData.GalleryPhoto photo : os.data.gallery) {
			if (photo.id() == id) {
				ownPhoto = true;
			}
		}
	}

	/** Roda: amplia (para cima) ou reduz, entre a foto inteira e {@link #MAX_ZOOM}x, em torno do centro do que está à vista. */
	void scroll(PhoneOS os, float amount) {
		float next = (float) Math.max(1.0, Math.min(MAX_ZOOM, zoom * Math.pow(ZOOM_STEP, amount)));
		if (Math.abs(next - zoom) < 1e-4f) {
			return;
		}
		// Mantém o mesmo ponto da foto no centro: o deslocamento cresce (ou encolhe) junto com o zoom.
		panX *= next / zoom;
		panY *= next / zoom;
		zoom = next;
		if (zoom <= 1.0001f) {
			zoom = 1f;
			panX = panY = 0f;
		}
		os.playScrollTick();
	}

	/** Dedo andando com o botão apertado: com a foto ampliada, move a foto. */
	void drag(float x, float y) {
		if (zoom <= 1f) {
			dragging = false;
			return;
		}
		if (!dragging) {
			if (box == null || x < box[0] || x > box[0] + box[2] || y < box[1] || y > box[1] + box[3]) {
				return;
			}
			dragging = true;
		} else {
			panX += x - dragX;
			panY += y - dragY;
		}
		dragX = x;
		dragY = y;
	}

	void release() {
		dragging = false;
	}

	float zoom() {
		return zoom;
	}

	long photoId() {
		return photoId;
	}

	/**
	 * @param onSend   chamado ao tocar em "Enviar" (só para fotos da galeria)
	 * @param onDelete chamado depois de apagar
	 */
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent, Runnable onSend, Runnable onDelete) {
		boolean mine = ownPhoto;
		// Fotos da galeria: Enviar, Salvar e Apagar. Fotos recebidas numa conversa: só Salvar (1.3).
		float barH = 28;
		g.setColor(new Color(0x101014));
		g.fill(new java.awt.geom.Rectangle2D.Float(x, y, w, h - barH));

		BufferedImage full = os.data.photos.full(photoId, os.backend, os.time);
		BufferedImage shown = full != null ? full : os.data.photos.peekThumb(photoId);
		float boxX = x + 3, boxY = y + 3, boxW = w - 6, boxH = h - barH - 6;
		box = new float[] {boxX, boxY, boxW, boxH};
		if (shown != null) {
			if (zoom <= 1f) {
				AppContent.drawFit(g, shown, boxX, boxY, boxW, boxH, 4);
			} else {
				drawZoomed(g, shown, boxX, boxY, boxW, boxH);
			}
		}
		drawInfoButton(g, os, boxX + boxW - 14, boxY + 12);
		if (infoOpen) {
			drawInfo(g, os, boxX, boxY, boxW, boxH);
		}
		g.setFont(Ui.font(Font.PLAIN, 8.2f));
		g.setColor(new Color(0xB9BCC8));
		if (os.data.photos.isGone(photoId)) {
			Ui.centered(g, Lang.t("handyphone.photo.viewer.gone"), x + w / 2f, y + (h - barH) / 2f);
		} else if (full == null) {
			String dots = ".".repeat(1 + (int) (os.time * 3) % 3);
			Ui.centered(g, Lang.t("handyphone.photo.viewer.loading") + dots, x + w / 2f, y + h - barH - 8);
		}

		long id = photoId;
		float by = y + h - barH + 3, bx = x + 6;
		if (!mine) {
			float bw = w - 12;
			button(g, "viewer-save", Lang.t("handyphone.gallery.action.save"), bx, by, bw, 22, new Color(0x5B6170), () -> os.exportPhotos(java.util.List.of(id)));
			return;
		}
		// Fotos só deste computador (servidor sem o mod) não vão para conversa nenhuma: só Salvar e Apagar.
		boolean canSend = !os.localOnly();
		float bw = canSend ? (w - 24) / 3f : (w - 18) / 2f;
		if (canSend) {
			button(g, "viewer-send", Lang.t("handyphone.photo.viewer.send"), bx, by, bw, 22, accent, onSend);
			bx += bw + 6;
		}
		button(g, "viewer-save", Lang.t("handyphone.gallery.action.save"), bx, by, bw, 22, new Color(0x5B6170), () -> os.exportPhotos(java.util.List.of(id)));
		button(g, "viewer-delete", Lang.t("handyphone.photo.viewer.delete"), bx + bw + 6, by, bw, 22, new Color(0x7B7F8C), () ->
			os.confirm(Lang.plural("handyphone.gallery.delete.title", 1),
				Lang.t(os.localOnly() ? "handyphone.gallery.delete.message_local" : os.data.feedAvailable ? "handyphone.gallery.delete.message_feed" : "handyphone.gallery.delete.message"),
				Lang.t("handyphone.gallery.action.delete"), true, () -> {
					os.deletePhoto(id);
					onDelete.run();
				}));
	}

	/** Botão "i" redondo no canto da foto: abre e fecha o painel de informações. */
	private void drawInfoButton(Graphics2D g, PhoneOS os, float cx, float cy) {
		float r = 8f;
		boolean down = host.hit("viewer-info", cx - r - 3, cy - r - 3, 2 * r + 6, 2 * r + 6, PhoneSound.BUTTON, () -> infoOpen = !infoOpen);
		g.setColor(infoOpen ? Color.WHITE : new Color(0, 0, 0, down ? 170 : 120));
		g.fill(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
		g.setColor(infoOpen ? new Color(0x15151A) : Color.WHITE);
		g.setStroke(new java.awt.BasicStroke(1.1f));
		if (!infoOpen) {
			g.draw(new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
		}
		g.fill(new java.awt.geom.Ellipse2D.Float(cx - 1.05f, cy - 4.6f, 2.1f, 2.1f));
		g.fill(new RoundRectangle2D.Float(cx - 0.95f, cy - 1.4f, 1.9f, 6f, 1.6f, 1.6f));
	}

	/**
	 * Painel no pé da foto: data, tamanho, origem, quem mandou (nas Mensagens) e, só para o dono e só de fotos da câmera, onde foi tirada.
	 * Sem resposta do servidor (antigo, ou ainda chegando), mostra o que o celular já sabe.
	 */
	private void drawInfo(Graphics2D g, PhoneOS os, float x, float y, float w, float h) {
		PhoneData.PhotoInfo info = os.photoInfo(photoId);
		long time = info != null ? info.time() : 0;
		int bytes = info != null ? info.size() : 0;
		for (PhoneData.GalleryPhoto p : os.data.gallery) {
			if (p.id() == photoId) {
				time = time == 0 ? p.time() : time;
				bytes = bytes == 0 ? p.size() : bytes;
			}
		}
		java.util.List<String[]> rows = new java.util.ArrayList<>();
		if (time > 0) {
			java.time.ZonedDateTime when = java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault());
			rows.add(new String[] {Lang.t("handyphone.photo.info.date"),
				Lang.format("handyphone.format.date.full").format(when) + " · " + Lang.format("handyphone.format.time").format(when)});
		}
		if (!sender.isEmpty()) {
			rows.add(new String[] {Lang.t("handyphone.photo.info.from"), os.data.displayName(sender)});
		}
		if (info != null && info.width() > 0) {
			rows.add(new String[] {Lang.t("handyphone.photo.info.size"),
				Lang.t("handyphone.photo.info.size_value", String.valueOf(info.width()), String.valueOf(info.height()), kb(bytes))});
		} else if (bytes > 0) {
			rows.add(new String[] {Lang.t("handyphone.photo.info.size"), kb(bytes)});
		}
		if (info != null) {
			rows.add(new String[] {Lang.t("handyphone.photo.info.source"),
				Lang.t(info.imported() ? "handyphone.photo.info.source.computer" : "handyphone.photo.info.source.camera")});
		}
		boolean place = info != null && info.hasPlace();
		if (place) {
			rows.add(new String[] {Lang.t("handyphone.photo.info.place"),
				Lang.t("handyphone.photo.info.place_value", String.valueOf(info.x()), String.valueOf(info.y()), String.valueOf(info.z()))});
			rows.add(new String[] {"", Geo.dimensionName(info.dimension())});
		}
		float lineH = 13f, pad = 8f;
		float panelH = pad * 2 + 12 + rows.size() * lineH + (place ? 11 : 0);
		float py = y + h - panelH - 4, px = x + 4, pw = w - 8;
		g.setColor(new Color(20, 20, 26, 225));
		g.fill(new RoundRectangle2D.Float(px, py, pw, panelH, 10, 10));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 8.6f));
		Ui.text(g, Lang.t("handyphone.photo.info.title"), px + pad, py + pad + 7);
		float ly = py + pad + 12 + 9;
		float labelW = 0;
		g.setFont(Ui.font(Font.PLAIN, 7.4f));
		for (String[] row : rows) {
			labelW = Math.max(labelW, Ui.textWidth(g, row[0]));
		}
		for (String[] row : rows) {
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			g.setColor(new Color(0x9EA2B0));
			Ui.text(g, row[0], px + pad, ly);
			g.setColor(new Color(0xF0F1F5));
			g.setFont(Ui.font(Font.PLAIN, 7.6f));
			float vx = px + pad + labelW + 8;
			Ui.text(g, AppContent.fit(g, row[1], px + pw - pad - vx), vx, ly);
			ly += lineH;
		}
		if (place) {
			g.setFont(Ui.font(Font.PLAIN, 6.6f));
			g.setColor(new Color(0x8A8E9C));
			Ui.text(g, Lang.t("handyphone.photo.info.private"), px + pad, ly - 1);
		}
	}

	private static String kb(int bytes) {
		return Lang.t("handyphone.size.kb", String.valueOf(Math.max(1, Math.round(bytes / 1024f))));
	}

	/** A foto ampliada, recortada na área da foto; o deslocamento é limitado para a borda da foto não entrar na área. */
	private void drawZoomed(Graphics2D g, BufferedImage img, float x, float y, float w, float h) {
		float fit = Math.min(w / img.getWidth(), h / img.getHeight());
		float dw = img.getWidth() * fit * zoom, dh = img.getHeight() * fit * zoom;
		float maxX = Math.max(0f, (dw - w) / 2f), maxY = Math.max(0f, (dh - h) / 2f);
		panX = Math.max(-maxX, Math.min(maxX, panX));
		panY = Math.max(-maxY, Math.min(maxY, panY));
		float dx = x + (w - dw) / 2f + panX, dy = y + (h - dh) / 2f + panY;
		java.awt.Shape oldClip = g.getClip();
		g.clip(new RoundRectangle2D.Float(Math.max(x, dx), Math.max(y, dy), Math.min(x + w, dx + dw) - Math.max(x, dx),
			Math.min(y + h, dy + dh) - Math.max(y, dy), 4, 4));
		g.drawImage(img, Math.round(dx), Math.round(dy), Math.round(dw), Math.round(dh), null);
		g.setClip(oldClip);
	}

	private void button(Graphics2D g, String key, String label, float x, float y, float w, float h, Color color, Runnable action) {
		boolean down = host.hit(key, x, y, w, h, PhoneSound.BUTTON, action);
		g.setColor(down ? Ui.shade(color, 0.8f) : color);
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 8f));
		Ui.centered(g, AppContent.fit(g, label, w - 6), x + w / 2f, y + h / 2f + 2.9f);
	}
}
