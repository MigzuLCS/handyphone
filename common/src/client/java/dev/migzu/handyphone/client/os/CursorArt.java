package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Desenho dos cursores do modo expandido, em Java2D: a figurinha dos Ajustes e a textura que o jogo cola no vidro saem da mesma
 * arte. A mão é um ponteiro de mouse (indicador para cima); a ponta do dedo é o ponto do clique.
 * <p>
 * A mão é desenhada em "unidades" (21 de altura); {@link #HAND_HOT_X}/{@link #HAND_HOT_Y} é a ponta do dedo dentro da caixa de
 * {@link #HAND_BOX} x {@link #HAND_BOX} unidades que a textura cobre (com folga para o contorno).
 */
public final class CursorArt {
	/** Lado da caixa da textura da mão, em unidades; a mão fica dentro com folga para o contorno. */
	public static final float HAND_BOX = 24f;
	/** Ponta do dedo (ponto do clique) dentro da caixa, em unidades. */
	public static final float HAND_HOT_X = 5.5f + 3.5f, HAND_HOT_Y = 0.55f + 1.5f;
	/** Altura da mão desenhada, em unidades (da ponta do dedo ao pulso). */
	public static final float HAND_HEIGHT = 20f;

	private static final Color FILL = Color.WHITE;
	private static final Color OUTLINE = new Color(0x1A1A1A);
	private static final Path2D HAND = handPath();

	private CursorArt() {
	}

	/** Contorno da mão com a ponta do dedo em (5,5, 0,55). */
	private static Path2D handPath() {
		GeneralPath p = new GeneralPath();
		p.moveTo(4f, 2f);
		p.curveTo(4f, 0.15f, 7f, 0.15f, 7f, 2f); // ponta do indicador
		p.lineTo(7f, 8.2f);
		p.curveTo(7f, 6.4f, 10f, 6.4f, 10f, 8.4f); // médio
		p.curveTo(10f, 7.0f, 13f, 7.0f, 13f, 9.1f); // anelar
		p.curveTo(13f, 8.0f, 15.6f, 8.0f, 15.6f, 10.2f); // mínimo
		p.lineTo(15.6f, 15f);
		p.curveTo(15.6f, 17.6f, 14.6f, 19.2f, 13.4f, 20.5f); // lado do mínimo até o pulso
		p.lineTo(6.2f, 20.5f);
		p.curveTo(5f, 19f, 2.6f, 16.4f, 1.1f, 14.2f); // base do polegar
		p.curveTo(0.1f, 12.6f, 1.7f, 10.9f, 3.1f, 12.2f); // ponta do polegar
		p.lineTo(4f, 13.1f);
		p.closePath();
		return p;
	}

	/**
	 * Mão com a ponta do dedo em (x, y). {@code scale} = pontos por unidade (a mão tem {@link #HAND_HEIGHT} unidades de altura).
	 */
	public static void hand(Graphics2D g, float x, float y, float scale) {
		AffineTransform old = g.getTransform();
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.translate(x, y);
		g.scale(scale, scale);
		g.translate(-5.5f, -0.55f);
		BasicStroke round = new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		g.setColor(FILL);
		g.fill(HAND);
		g.setColor(OUTLINE);
		g.setStroke(round);
		g.draw(HAND);
		// Vincos entre os dedos.
		g.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new java.awt.geom.Line2D.Float(7f, 9f, 7f, 12f));
		g.draw(new java.awt.geom.Line2D.Float(10f, 9.2f, 10f, 12f));
		g.draw(new java.awt.geom.Line2D.Float(13f, 9.9f, 13f, 12f));
		g.setTransform(old);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	/** Cruz branca com contorno escuro centrada em (cx, cy), com braços de {@code arm} pontos (o mesmo desenho do jogo). */
	public static void cross(Graphics2D g, float cx, float cy, float arm) {
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		float thick = arm * 0.12f, outline = arm * 0.1f;
		g.setColor(OUTLINE);
		bars(g, cx, cy, arm + outline, thick + outline);
		g.setColor(FILL);
		bars(g, cx, cy, arm, thick);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
	}

	private static void bars(Graphics2D g, float cx, float cy, float arm, float thick) {
		g.fill(new java.awt.geom.Rectangle2D.Float(cx - arm, cy - thick, arm * 2, thick * 2));
		g.fill(new java.awt.geom.Rectangle2D.Float(cx - thick, cy - arm, thick * 2, arm * 2));
	}

	/**
	 * Imagem quadrada (ARGB) da mão para a textura do jogo: {@code size} pixels cobrem {@link #HAND_BOX} unidades, a ponta do
	 * dedo em ({@link #HAND_HOT_X}, {@link #HAND_HOT_Y}) unidades.
	 */
	public static BufferedImage handImage(int size) {
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		float s = size / HAND_BOX;
		hand(g, HAND_HOT_X * s, HAND_HOT_Y * s, s);
		g.dispose();
		return img;
	}
}
