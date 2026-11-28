package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/** Utilidades de desenho compartilhadas pelos temas. */
final class Ui {
	private static Font mono;

	private Ui() {
	}

	/** A fonte do celular no peso pedido ({@link Font#PLAIN} ou {@link Font#BOLD}): a família de {@link FontKit} (embutida ou do sistema). */
	static Font font(int style, float size) {
		return FontKit.family().font(style, size);
	}

	/** Fonte de largura fixa, para relógios com cara de visor antigo. */
	static Font mono(int style, float size) {
		if (mono == null) {
			mono = FontKit.pick("Consolas", Font.MONOSPACED);
		}
		return mono.deriveFont(style, size);
	}

	/** Fonte fina, para relógios grandes (o peso Light da família). */
	static Font light(float size) {
		return FontKit.family().light(size);
	}

	/** Largura do texto na fonte atual de {@code g}, com a reserva por caractere ({@link TextFallback}). */
	static float textWidth(Graphics2D g, String s) {
		return TextFallback.width(g, s);
	}

	/**
	 * Desenha texto com a linha de base em {@code y}. <b>Todo texto do PhoneOS passa por aqui</b> (nenhum {@code g.drawString} direto: o
	 * {@code PhoneFontTest} confere), para o que a fonte não tem cair na fonte do sistema em vez de virar quadrado.
	 */
	static void text(Graphics2D g, String s, float x, float y) {
		TextFallback.draw(g, s, x, y);
	}

	static void centered(Graphics2D g, String s, float cx, float baseline) {
		text(g, s, cx - textWidth(g, s) / 2f, baseline);
	}

	static Color shade(Color c, float k) {
		return new Color(clampByte(c.getRed() * k), clampByte(c.getGreen() * k), clampByte(c.getBlue() * k));
	}

	static Color alpha(Color c, int alpha) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
	}

	private static int clampByte(float v) {
		return Math.max(0, Math.min(255, Math.round(v)));
	}

	/** Entrada escalonada: cada elemento começa um pouco depois do anterior. */
	static float stagger(float u, float delay) {
		float v = (u - delay) / (1f - Math.min(delay, 0.6f) - 0.25f);
		return easeOut(clamp01(v));
	}

	static float easeOut(float t) {
		float k = 1 - t;
		return 1 - k * k * k;
	}

	static float easeIn(float t) {
		return t * t;
	}

	static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	static float clamp01(float v) {
		return Math.max(0f, Math.min(1f, v));
	}

	/**
	 * Desenha o símbolo de um app dentro de uma caixa de 34 x 34 na origem.
	 *
	 * @param fg   cor do símbolo
	 * @param hole cor dos "furos" (miolo do pino do mapa, da engrenagem, lente da câmera)
	 */
	static void glyph(Graphics2D g, String appId, Color fg, Color hole) {
		g.setColor(fg);
		g.setStroke(new BasicStroke(2.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		switch (appId) {
			case "messages" -> {
				Path2D p = new Path2D.Float();
				p.append(new RoundRectangle2D.Float(7, 8, 20, 14.5f, 12, 12), false);
				p.moveTo(11, 20);
				p.lineTo(9.5f, 26.5f);
				p.lineTo(16.5f, 21.5f);
				p.closePath();
				g.fill(p);
			}
			case "phone" -> {
				Path2D p = new Path2D.Float();
				p.moveTo(10.5f, 9);
				p.curveTo(10, 18, 16, 24, 25, 23.5f);
				g.setStroke(new BasicStroke(5.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(p);
			}
			case "camera" -> {
				g.fill(new RoundRectangle2D.Float(6, 11, 22, 15, 6, 6));
				g.fill(new RoundRectangle2D.Float(12.5f, 8, 9, 5, 3, 3));
				g.setColor(hole);
				g.fill(new Ellipse2D.Float(12, 13.5f, 10, 10));
				g.setColor(fg);
				g.fill(new Ellipse2D.Float(14.4f, 15.9f, 5.2f, 5.2f));
			}
			case "gallery" -> {
				g.fill(new Ellipse2D.Float(20, 8.5f, 6, 6));
				Path2D p = new Path2D.Float();
				p.moveTo(5.5f, 26);
				p.lineTo(13, 14.5f);
				p.lineTo(18.5f, 22);
				p.lineTo(22, 18);
				p.lineTo(28.5f, 26);
				p.closePath();
				g.fill(p);
			}
			case "map" -> {
				Path2D p = new Path2D.Float();
				p.moveTo(17, 28);
				p.curveTo(9, 19, 9, 16.5f, 9, 14.5f);
				p.curveTo(9, 4, 25, 4, 25, 14.5f);
				p.curveTo(25, 16.5f, 25, 19, 17, 28);
				p.closePath();
				g.fill(p);
				g.setColor(hole);
				g.fill(new Ellipse2D.Float(13.6f, 10.6f, 6.8f, 6.8f));
			}
			case "contacts" -> {
				g.fill(new Ellipse2D.Float(12, 7, 10, 10));
				g.fill(new RoundRectangle2D.Float(7.5f, 19, 19, 12, 12, 12));
			}
			case "settings" -> {
				g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				for (int i = 0; i < 8; i++) {
					double ang = i * Math.PI / 4;
					g.draw(new Line2D.Double(17 + Math.cos(ang) * 7.5, 17 + Math.sin(ang) * 7.5, 17 + Math.cos(ang) * 10, 17 + Math.sin(ang) * 10));
				}
				g.fill(new Ellipse2D.Float(9.5f, 9.5f, 15, 15));
				g.setColor(hole);
				g.fill(new Ellipse2D.Float(13.6f, 13.6f, 6.8f, 6.8f));
			}
			case "feed" -> feedGlyph(g, fg, hole);
			default -> {
				g.fill(new Ellipse2D.Float(15, 7.5f, 4.4f, 4.4f));
				g.fill(new RoundRectangle2D.Float(15, 14, 4.4f, 13, 3, 3));
			}
		}
	}

	/** Coração com centro em {@code (cx, cy)} e largura {@code size}. */
	static Path2D heart(float cx, float cy, float size) {
		float s = size / 2f;
		Path2D p = new Path2D.Float();
		p.moveTo(cx, cy + s * 0.95f);
		p.curveTo(cx - s * 0.35f, cy + s * 0.6f, cx - s, cy + s * 0.1f, cx - s, cy - s * 0.35f);
		p.curveTo(cx - s, cy - s * 0.95f, cx - s * 0.3f, cy - s * 1.1f, cx, cy - s * 0.55f);
		p.curveTo(cx + s * 0.3f, cy - s * 1.1f, cx + s, cy - s * 0.95f, cx + s, cy - s * 0.35f);
		p.curveTo(cx + s, cy + s * 0.1f, cx + s * 0.35f, cy + s * 0.6f, cx, cy + s * 0.95f);
		p.closePath();
		return p;
	}

	/** Ícone do scroll.in (escolha do Migzu, 2026-11-27): polaroid inclinada com um morro de blocos e o sol quadrado. */
	private static void feedGlyph(Graphics2D g, Color fg, Color hole) {
		java.awt.geom.AffineTransform t = g.getTransform();
		g.rotate(Math.toRadians(-11), 17, 17);
		g.fill(new RoundRectangle2D.Float(7.5f, 5.5f, 19, 23, 2.5f, 2.5f));
		g.setColor(hole);
		g.fill(new Rectangle2D.Float(9.8f, 7.8f, 14.4f, 13.4f));
		g.setColor(fg);
		g.fill(new Rectangle2D.Float(9.8f, 17.6f, 14.4f, 3.6f));
		g.fill(new Rectangle2D.Float(9.8f, 14f, 3.6f, 3.6f));
		g.fill(new Rectangle2D.Float(13.4f, 15.8f, 3.6f, 1.8f));
		g.fill(new Rectangle2D.Float(19.4f, 9.6f, 2.8f, 2.8f));
		g.setTransform(t);
	}
}
