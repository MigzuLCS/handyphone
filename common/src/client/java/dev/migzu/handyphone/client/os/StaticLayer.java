package dev.migzu.handyphone.client.os;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * Uma parte do desenho que nunca muda (a moldura do aparelho): é desenhada uma vez numa imagem do tamanho da face, na resolução
 * em que o PhoneOS está sendo desenhado, e nos quadros seguintes só é colada. Desenhar a moldura de novo a cada quadro (gradientes e
 * formas com antialiasing) custava mais de um décimo de cada redesenho. Mesmo padrão do papel de parede dos temas.
 *
 * <p>A imagem guarda só o tamanho em pixels; trocar a resolução a refaz. Quem usa decide quando a camada vale (a moldura só
 * muda enquanto o botão físico está apertado; aí desenha ao vivo).
 */
final class StaticLayer {
	/** Desliga (só nos testes) o cache, para conferir que a moldura colada é igual à desenhada ao vivo (PhonePerfTest). */
	static boolean enabled = true;

	private BufferedImage image;

	/** Cola a camada em {@code g}, que deve estar com a escala {@code pixelScale} (como o PhoneOS é sempre desenhado); a refaz se preciso. */
	void draw(Graphics2D g, float pixelScale, Consumer<Graphics2D> painter) {
		if (!enabled) {
			painter.accept(g);
			return;
		}
		int w = Math.round(PhoneOS.FACE_W * pixelScale), h = Math.round(PhoneOS.FACE_H * pixelScale);
		if (image == null || image.getWidth() != w || image.getHeight() != h) {
			image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D lg = image.createGraphics();
			lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			lg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			lg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			lg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			lg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			lg.scale(pixelScale, pixelScale);
			painter.accept(lg);
			lg.dispose();
		}
		AffineTransform t = g.getTransform();
		g.scale(1 / pixelScale, 1 / pixelScale);
		g.drawImage(image, 0, 0, null);
		g.setTransform(t);
	}

	/** Esquece a imagem (ao trocar de tema, para não guardar a moldura de um tema que não está em uso). */
	void clear() {
		image = null;
	}
}
