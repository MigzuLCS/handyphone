package dev.migzu.handyphone.client.os;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * App Câmera: um visor com o que o jogador está vendo no mundo (sem o celular na frente)
 * e o botão de disparo. A foto é reduzida e comprimida aqui no cliente antes de ir para
 * a galeria, que fica no servidor.
 */
final class CameraApp extends AppContent {
	private static final float MARGIN = 6f;

	/** Na mão, ao abrir a Câmera o destaque já está no disparador (não na miniatura da galeria). */
	@Override
	String firstFocus() {
		return "shutter";
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		g.setColor(new Color(0x14141A));
		g.fill(new Rectangle2D.Float(x, y, w, h));

		// Visor na mesma proporção da foto (4:3), então o que se vê é o que fica.
		float vw = w - 2 * MARGIN, vh = vw * PhoneCamera.ASPECT_H / PhoneCamera.ASPECT_W;
		float vx = x + MARGIN, vy = y + 8;
		BufferedImage preview = os.cameraPreview();
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(vx, vy, vw, vh, 6, 6));
		if (preview != null) {
			drawCover(g, preview, vx, vy, vw, vh, 6);
		} else {
			g.setColor(new Color(0x9A9CA8));
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.centered(g, Lang.t("handyphone.camera.preparing"), vx + vw / 2f, vy + vh / 2f + 3);
		}
		drawFrameMarks(g, vx, vy, vw, vh);

		// Linha de estado e a faixa de comandos.
		float stateY = vy + vh + 12;
		g.setFont(Ui.font(Font.PLAIN, 7.2f));
		g.setColor(new Color(0xB4B6C2));
		String status = status(os);
		if (!status.isEmpty()) {
			Ui.centered(g, status, x + w / 2f, stateY);
		}

		float shutterY = Math.min(y + h - 36, stateY + 34);
		drawShutter(g, os, x + w / 2f, shutterY, accent);

		// Última foto da galeria, no canto: toque abre a Galeria.
		float tx = x + MARGIN + 6, ts = 30;
		if (!data.gallery.isEmpty()) {
			PhoneData.GalleryPhoto last = data.gallery.getFirst();
			BufferedImage thumb = data.photos.thumb(last.id(), os.backend, os.time);
			boolean down = hit("last", tx, shutterY - ts / 2f, ts, ts, PhoneSound.ICON_TAP, () -> os.launch("gallery"));
			if (thumb != null) {
				drawCover(g, thumb, tx, shutterY - ts / 2f, ts, ts, 6);
			} else {
				g.setColor(new Color(0x3A3C48));
				g.fill(new RoundRectangle2D.Float(tx, shutterY - ts / 2f, ts, ts, 6, 6));
			}
			g.setColor(down ? Color.WHITE : new Color(0xE0E2EA));
			g.setStroke(new BasicStroke(1f));
			g.draw(new RoundRectangle2D.Float(tx, shutterY - ts / 2f, ts, ts, 6, 6));
		}

		// Clarão do disparo.
		float flash = 1f - (float) ((os.time - os.flashAt) / 0.22);
		if (flash > 0) {
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.SrcOver.derive(Math.min(0.85f, flash)));
			g.setColor(Color.WHITE);
			g.fill(new Rectangle2D.Float(vx, vy, vw, vh));
			g.setComposite(old);
		}
	}

	private static String status(PhoneOS os) {
		PhoneData data = os.data;
		if (data.number.isEmpty()) {
			return Lang.t("handyphone.camera.status.no_signal");
		}
		if (data.photoQuotaBytes <= 0) {
			return Lang.t("handyphone.camera.status.off");
		}
		if (os.photosBusy()) {
			return Lang.t("handyphone.camera.status.uploading");
		}
		if (data.photoUsedBytes >= data.photoQuotaBytes) {
			return Lang.t("handyphone.camera.status.full");
		}
		// No dia a dia, nenhum texto (o contador de fotos parecia um aviso); só perto da cota de espaço.
		if (data.photoUsedBytes >= data.photoQuotaBytes * ALMOST_FULL) {
			return Lang.t("handyphone.camera.status.almost_full");
		}
		return "";
	}

	/** Fração da cota de espaço a partir da qual a câmera avisa que a galeria está quase cheia. */
	private static final double ALMOST_FULL = 0.9;

	/** Cantos de enquadramento e uma cruz discreta no centro. */
	private static void drawFrameMarks(Graphics2D g, float x, float y, float w, float h) {
		g.setColor(new Color(255, 255, 255, 170));
		g.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float m = 6, l = 9;
		g.draw(new Line2D.Float(x + m, y + m, x + m + l, y + m));
		g.draw(new Line2D.Float(x + m, y + m, x + m, y + m + l));
		g.draw(new Line2D.Float(x + w - m, y + m, x + w - m - l, y + m));
		g.draw(new Line2D.Float(x + w - m, y + m, x + w - m, y + m + l));
		g.draw(new Line2D.Float(x + m, y + h - m, x + m + l, y + h - m));
		g.draw(new Line2D.Float(x + m, y + h - m, x + m, y + h - m - l));
		g.draw(new Line2D.Float(x + w - m, y + h - m, x + w - m - l, y + h - m));
		g.draw(new Line2D.Float(x + w - m, y + h - m, x + w - m, y + h - m - l));
		g.draw(new Line2D.Float(x + w / 2f - 4, y + h / 2f, x + w / 2f + 4, y + h / 2f));
		g.draw(new Line2D.Float(x + w / 2f, y + h / 2f - 4, x + w / 2f, y + h / 2f + 4));
	}

	private void drawShutter(Graphics2D g, PhoneOS os, float cx, float cy, Color accent) {
		boolean down = hit("shutter", cx - 22, cy - 22, 44, 44, null, () -> os.takePhoto());
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.2f));
		g.draw(new Ellipse2D.Float(cx - 19, cy - 19, 38, 38));
		g.setColor(down ? new Color(0xC9CBD6) : Color.WHITE);
		float r = down ? 13 : 15;
		g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
	}
}
