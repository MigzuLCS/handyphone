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
	/** O primeiro toque em "Apagar" só arma; o segundo apaga de verdade. */
	private boolean deleteArmed;
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
		photoId = id;
		deleteArmed = false;
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
		float barH = mine ? 28 : 0;
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
		g.setFont(Ui.font(Font.PLAIN, 8.2f));
		g.setColor(new Color(0xB9BCC8));
		if (os.data.photos.isGone(photoId)) {
			Ui.centered(g, Lang.t("handyphone.photo.viewer.gone"), x + w / 2f, y + (h - barH) / 2f);
		} else if (full == null) {
			String dots = ".".repeat(1 + (int) (os.time * 3) % 3);
			Ui.centered(g, Lang.t("handyphone.photo.viewer.loading") + dots, x + w / 2f, y + h - barH - 8);
		}

		if (!mine) {
			return;
		}
		float by = y + h - barH + 3, bw = (w - 18) / 2f;
		float bx = x + 6;
		button(g, "viewer-send", Lang.t("handyphone.photo.viewer.send"), bx, by, bw, 22, accent, onSend);
		boolean armed = deleteArmed;
		button(g, "viewer-delete", Lang.t(armed ? "handyphone.photo.viewer.delete_confirm" : "handyphone.photo.viewer.delete"), bx + bw + 6, by, bw, 22, armed ? new Color(0xC62828) : new Color(0x7B7F8C), () -> {
			if (!deleteArmed) {
				deleteArmed = true;
				return;
			}
			os.deletePhoto(photoId);
			onDelete.run();
		});
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
