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

	PhotoViewer(AppContent host) {
		this.host = host;
	}

	void open(long id, PhoneOS os) {
		photoId = id;
		deleteArmed = false;
		ownPhoto = false;
		for (PhoneData.GalleryPhoto photo : os.data.gallery) {
			if (photo.id() == id) {
				ownPhoto = true;
			}
		}
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
		if (shown != null) {
			AppContent.drawFit(g, shown, boxX, boxY, boxW, boxH, 4);
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

	private void button(Graphics2D g, String key, String label, float x, float y, float w, float h, Color color, Runnable action) {
		boolean down = host.hit(key, x, y, w, h, PhoneSound.BUTTON, action);
		g.setColor(down ? Ui.shade(color, 0.8f) : color);
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
		g.setColor(Color.WHITE);
		g.setFont(Ui.font(Font.BOLD, 8f));
		Ui.centered(g, AppContent.fit(g, label, w - 6), x + w / 2f, y + h / 2f + 2.9f);
	}
}
