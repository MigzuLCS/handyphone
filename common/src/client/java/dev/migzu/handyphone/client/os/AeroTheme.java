package dev.migzu.handyphone.client.os;

import static dev.migzu.handyphone.client.os.PhoneOS.FACE_H;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_R;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_W;
import static dev.migzu.handyphone.client.os.Ui.centered;
import static dev.migzu.handyphone.client.os.Ui.clamp01;
import static dev.migzu.handyphone.client.os.Ui.font;
import static dev.migzu.handyphone.client.os.Ui.shade;
import static dev.migzu.handyphone.client.os.Ui.stagger;

import dev.migzu.handyphone.client.os.PhoneOS.App;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Random;

/**
 * Estilo de smartphone de 2012: vidro, brilho e natureza. Papel de parede azul com
 * formas translúcidas, widget grande de relógio com céu e grama, ícones com reflexo.
 */
final class AeroTheme extends Theme {
	private static final float SX = 6f, SY = 20f, SW = FACE_W - 12f, SH = 344f;
	private static final float STATUS_H = 13f;
	private static final int COLS = 4;
	private static final float ICON = 32f;
	private static final float HERO_Y = SY + STATUS_H + 8f, HERO_H = 96f;
	private static final float GRID_Y = HERO_Y + HERO_H + 16f, GRID_ROW = 58f;
	private static final float DOCK_Y = SY + SH - 51f;
	private static final float HEADER_H = 26f;
	// Botão físico de início, na moldura de baixo.
	private static final float KEY_CX = FACE_W / 2f, KEY_CY = 383f, KEY_W = 46f, KEY_H = 13f;

	private static final Color HOLO = new Color(0x35B5E5);

	private BufferedImage wallpaper;

	@Override
	String id() {
		return "aero";
	}

	@Override
	String name() {
		return Lang.t("handyphone.theme.aero.name");
	}

	@Override
	String description() {
		return Lang.t("handyphone.theme.aero.description");
	}

	@Override
	float[] iconBox(PhoneOS os, int i) {
		float[] r = iconRect(os, i);
		return new float[] {r[0], r[1], ICON, ICON, ICON * 0.18f};
	}

	@Override
	Color accent() {
		return new Color(0x1E88C8);
	}

	// ------------------------------------------------------------------ layout

	/** Canto superior esquerdo do ícone i. */
	private static float[] iconRect(PhoneOS os, int i) {
		float gap = (SW - COLS * ICON) / (COLS + 1);
		float x = SX + gap + slot(os, i) % COLS * (ICON + gap);
		if (os.apps.get(i).dock()) {
			return new float[] {x, DOCK_Y};
		}
		return new float[] {x, GRID_Y + slot(os, i) / COLS * GRID_ROW};
	}

	@Override
	int iconAt(PhoneOS os, float x, float y) {
		for (int i = 0; i < os.apps.size(); i++) {
			float[] r = iconRect(os, i);
			if (x >= r[0] - 5 && x <= r[0] + ICON + 5 && y >= r[1] - 5 && y <= r[1] + ICON + 14) {
				return i;
			}
		}
		return -1;
	}

	@Override
	float[] iconCenter(PhoneOS os, int i) {
		float[] r = iconRect(os, i);
		return new float[] {r[0] + ICON / 2f, r[1] + ICON / 2f};
	}

	@Override
	boolean physicalHomeAt(float x, float y) {
		return Math.abs(x - KEY_CX) <= KEY_W / 2f + 6 && Math.abs(y - KEY_CY) <= KEY_H / 2f + 7;
	}

	@Override
	boolean backAt(PhoneOS os, float x, float y) {
		boolean onArrow = os.isAppOpen() && x >= SX && x <= SX + 36 && y >= SY + STATUS_H && y <= SY + STATUS_H + HEADER_H;
		return physicalHomeAt(x, y) || onArrow;
	}

	@Override
	float screenRadius() {
		return 3f;
	}

	@Override
	java.awt.Color screenEdgeColor() {
		return new Color(0x000000);
	}

	@Override
	float[] screen() {
		return new float[] {SX, SY, SW, SH};
	}

	@Override
	float[] appBody() {
		float top = SY + STATUS_H + HEADER_H;
		return new float[] {SX, top, SW, SY + SH - top};
	}

	// ------------------------------------------------------------------ desenho

	/** A moldura só muda com o botão físico apertado; parada, é desenhada uma vez e colada (ver StaticLayer). */
	private final StaticLayer bezel = new StaticLayer();

	@Override
	void releaseCaches() {
		bezel.clear();
	}

	@Override
	void render(Graphics2D g, PhoneOS os, float pixelScale) {
		float pressed = clamp01(os.homeBarPress.f());
		if (pressed > 0.001f) {
			drawBezel(g, pressed);
		} else {
			bezel.draw(g, pixelScale, layer -> drawBezel(layer, 0f));
		}

		Shape oldClip = g.getClip();
		g.clip(new RoundRectangle2D.Float(SX, SY, SW, SH, 3, 3));

		float u = clamp01(os.unlock.f());
		float a = clamp01(os.appOpen.f());

		drawWallpaper(g, pixelScale);
		if (!homeCovered(os, a)) {
			if (u > 0.001f) {
				drawHome(g, os, u);
			}
			if (u < 0.999f) {
				drawLock(g, os, u);
			}
		}
		if (os.openApp >= 0 && a > 0.001f) {
			drawApp(g, os, os.apps.get(os.openApp), a);
		}
		drawStatusBar(g, os);

		// Reflexo do vidro.
		g.setPaint(new GradientPaint(SX, SY, new Color(255, 255, 255, 20), SX + SW * 0.7f, SY + SH * 0.35f, new Color(255, 255, 255, 0)));
		Path2D glare = new Path2D.Float();
		glare.moveTo(SX, SY);
		glare.lineTo(SX + SW * 0.78f, SY);
		glare.lineTo(SX, SY + SH * 0.4f);
		glare.closePath();
		g.fill(glare);
		g.setClip(oldClip);
	}

	private void drawBezel(Graphics2D g, float p) {
		RoundRectangle2D face = new RoundRectangle2D.Float(0, 0, FACE_W, FACE_H, FACE_R * 2, FACE_R * 2);
		// Azul-escuro brilhante, com um leve escovado.
		g.setPaint(new GradientPaint(0, 0, new Color(0x2C3F66), 0, FACE_H, new Color(0x121B30)));
		g.fill(face);
		Shape oldClip = g.getClip();
		g.clip(face);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 46), 0, 18, new Color(255, 255, 255, 0)));
		g.fill(new Ellipse2D.Float(-40, -36, FACE_W + 80, 52));
		g.setClip(oldClip);
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(255, 255, 255, 60));
		g.draw(new RoundRectangle2D.Float(0.6f, 0.6f, FACE_W - 1.2f, FACE_H - 1.2f, FACE_R * 2 - 1, FACE_R * 2 - 1));

		// Alto-falante cromado e sensores.
		g.setPaint(new GradientPaint(0, 7.5f, new Color(0xC9D2E0), 0, 12, new Color(0x6F7C92)));
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - 17, 7.8f, 34, 4f, 4f, 4f));
		g.setColor(new Color(0x0A0F1C));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 28, 7.4f, 5, 5));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 38, 8.4f, 3, 3));

		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(SX - 1.2f, SY - 1.2f, SW + 2.4f, SH + 2.4f, 5, 5));

		// Botão físico de início: comprido, com aro cromado.
		g.setPaint(new GradientPaint(0, KEY_CY - KEY_H / 2f, new Color(0xD4DBE7), 0, KEY_CY + KEY_H / 2f, new Color(0x5E6A80)));
		g.fill(new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f, KEY_CY - KEY_H / 2f, KEY_W, KEY_H, KEY_H, KEY_H));
		float in = 1.6f;
		g.setPaint(new GradientPaint(0, KEY_CY - KEY_H / 2f, shade(new Color(0x2F4470), 1f - 0.35f * p), 0, KEY_CY + KEY_H / 2f, shade(new Color(0x16223D), 1f - 0.35f * p)));
		g.fill(new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f + in, KEY_CY - KEY_H / 2f + in, KEY_W - 2 * in, KEY_H - 2 * in, KEY_H - 2 * in, KEY_H - 2 * in));
		g.setColor(new Color(255, 255, 255, 40));
		g.fill(new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f + 4, KEY_CY - KEY_H / 2f + 2.4f, KEY_W - 8, 3.2f, 3.2f, 3.2f));
	}

	private void drawWallpaper(Graphics2D g, float pixelScale) {
		int w = Math.round(FACE_W * pixelScale), h = Math.round(FACE_H * pixelScale);
		if (wallpaper == null || wallpaper.getWidth() != w) {
			wallpaper = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D wg = wallpaper.createGraphics();
			wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			wg.scale(pixelScale, pixelScale);
			wg.setPaint(new LinearGradientPaint(0, SY, 0, SY + SH, new float[] {0f, 0.55f, 1f},
				new Color[] {new Color(0x062E7A), new Color(0x1C7FD6), new Color(0x3FA9E6)}));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			// Clarão vindo de cima.
			wg.setPaint(new RadialGradientPaint(FACE_W * 0.3f, SY + 60, 190, new float[] {0f, 1f},
				new Color[] {new Color(170, 230, 255, 120), new Color(170, 230, 255, 0)}));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			// Formas translúcidas (bokeh): hexágonos e círculos.
			Random random = new Random(7);
			for (int i = 0; i < 22; i++) {
				float cx = SX + random.nextFloat() * SW, cy = SY + 90 + random.nextFloat() * (SH - 90), r = 9 + random.nextFloat() * 26;
				wg.setColor(new Color(255, 255, 255, 14 + random.nextInt(28)));
				if (i % 3 == 0) {
					wg.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
				} else {
					Path2D hex = new Path2D.Float();
					for (int k = 0; k < 6; k++) {
						double ang = Math.PI / 3 * k + Math.PI / 6;
						float px = cx + r * (float) Math.cos(ang), py = cy + r * (float) Math.sin(ang);
						if (k == 0) {
							hex.moveTo(px, py);
						} else {
							hex.lineTo(px, py);
						}
					}
					hex.closePath();
					wg.fill(hex);
				}
			}
			// Ondas de água embaixo.
			for (int i = 0; i < 3; i++) {
				Path2D wave = new Path2D.Float();
				float y0 = SY + SH - 62 + i * 20;
				wave.moveTo(SX, y0);
				wave.curveTo(SX + SW * 0.3f, y0 - 22, SX + SW * 0.6f, y0 + 20, SX + SW, y0 - 8);
				wave.lineTo(SX + SW, SY + SH);
				wave.lineTo(SX, SY + SH);
				wave.closePath();
				wg.setColor(new Color(255, 255, 255, 30));
				wg.fill(wave);
			}
			wg.dispose();
		}
		AffineTransform t = g.getTransform();
		g.scale(1 / pixelScale, 1 / pixelScale);
		g.drawImage(wallpaper, 0, 0, null);
		g.setTransform(t);
	}

	private void drawStatusBar(Graphics2D g, PhoneOS os) {
		g.setColor(Color.BLACK);
		g.fill(new Rectangle2D.Float(SX, SY, SW, STATUS_H));

		String clock = os.clockText();
		g.setFont(font(Font.BOLD, 8.4f));
		float cw = Ui.textWidth(g, clock), cx = SX + SW - cw - 4;
		g.setColor(new Color(0xE8EEF5));
		Ui.text(g, clock, cx, SY + STATUS_H - 3.6f);

		// Bateria verde, em pé.
		float bx = cx - 9, by = SY + 2.6f;
		g.setColor(new Color(0x8A919C));
		g.fill(new Rectangle2D.Float(bx + 1.4f, by, 2.4f, 1.2f));
		g.fill(new Rectangle2D.Float(bx, by + 1.2f, 5.2f, 7.2f));
		g.setColor(new Color(0x7FD63A));
		g.fill(new Rectangle2D.Float(bx + 0.8f, by + 3f, 3.6f, 4.6f));

		// Sinal em degraus.
		float sx = bx - 15, base = SY + STATUS_H - 3.4f;
		for (int i = 0; i < 4; i++) {
			float bh = 2.2f + i * 1.9f;
			g.setColor(i < 3 ? new Color(0xE8EEF5) : new Color(0x6A717C));
			g.fill(new Rectangle2D.Float(sx + i * 3f, base - bh, 2.2f, bh));
		}
		g.setColor(new Color(0xE8EEF5));
		g.setFont(font(Font.BOLD, 5.4f));
		Ui.text(g, "3G", sx - 8.5f, base - 2.6f);
	}

	private void drawLock(Graphics2D g, PhoneOS os, float u) {
		float alpha = clamp01(1 - u * 1.6f);
		Composite old = g.getComposite();
		// Ondulação de água ao desbloquear.
		if (u > 0.01f) {
			float cx = FACE_W / 2f, cy = SY + SH * 0.55f;
			for (int i = 0; i < 3; i++) {
				float r = u * 230 - i * 34;
				if (r > 2) {
					g.setComposite(AlphaComposite.SrcOver.derive(clamp01((1 - u) * 0.75f)));
					g.setStroke(new BasicStroke(3.4f - i));
					g.setColor(new Color(220, 245, 255));
					g.draw(new Ellipse2D.Float(cx - r, cy - r * 0.8f, r * 2, r * 1.6f));
				}
			}
		}
		if (alpha <= 0) {
			g.setComposite(old);
			return;
		}
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		float dy = -40 * Ui.easeIn(u);
		LocalDateTime now = LocalDateTime.now();
		g.setFont(Ui.light(56f));
		softText(g, now.format(PhoneOS.timeFormat()), FACE_W / 2f, SY + 118 + dy, 1.4f);
		String date = now.format(Lang.format("handyphone.format.date.long"));
		date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
		g.setFont(font(Font.PLAIN, 10f));
		softText(g, date, FACE_W / 2f, SY + 136 + dy, 0.7f);

		float pulse = 0.55f + 0.45f * (float) Math.sin(os.time * 2.4);
		g.setComposite(AlphaComposite.SrcOver.derive(alpha * (0.5f + 0.45f * pulse)));
		g.setFont(font(Font.PLAIN, 9f));
		softText(g, Lang.t("handyphone.lock.hint", os.useKeyName()), FACE_W / 2f, SY + SH - 24, 0.7f);
		g.setComposite(old);
	}

	/** Texto branco centralizado com uma sombra leve por baixo. */
	private static void softText(Graphics2D g, String s, float cx, float baseline, float offset) {
		g.setColor(new Color(0, 20, 60, 120));
		centered(g, s, cx, baseline + offset);
		g.setColor(Color.WHITE);
		centered(g, s, cx, baseline);
	}

	private void drawHome(Graphics2D g, PhoneOS os, float u) {
		Composite old = g.getComposite();
		drawHero(g, stagger(u, 0.05f));

		float dockIn = stagger(u, 0.25f);
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(dockIn)));
		// Indicador de páginas: bolinhas com a casinha no meio.
		float py = DOCK_Y - 17, pcx = SX + SW / 2f;
		for (int i = -2; i <= 2; i++) {
			if (i == 0) {
				Path2D house = new Path2D.Float();
				house.moveTo(pcx, py - 3.4f);
				house.lineTo(pcx + 3.8f, py);
				house.lineTo(pcx + 2.6f, py);
				house.lineTo(pcx + 2.6f, py + 3.2f);
				house.lineTo(pcx - 2.6f, py + 3.2f);
				house.lineTo(pcx - 2.6f, py);
				house.lineTo(pcx - 3.8f, py);
				house.closePath();
				g.setColor(Color.WHITE);
				g.fill(house);
			} else {
				g.setColor(new Color(255, 255, 255, 120));
				g.fill(new RoundRectangle2D.Float(pcx + i * 10 - 1.7f, py - 1.4f, 3.4f, 3.4f, 1.4f, 1.4f));
			}
		}
		// Linha que separa o dock.
		g.setPaint(new LinearGradientPaint(SX, 0, SX + SW, 0, new float[] {0f, 0.5f, 1f},
			new Color[] {new Color(255, 255, 255, 0), new Color(255, 255, 255, 170), new Color(255, 255, 255, 0)}));
		g.fill(new Rectangle2D.Float(SX + 6, DOCK_Y - 8, SW - 12, 0.8f));

		for (int i = 0; i < os.apps.size(); i++) {
			App app = os.apps.get(i);
			float[] r = iconRect(os, i);
			float in = app.dock() ? dockIn : stagger(u, 0.12f + 0.04f * slot(os, i));
			float pressed = os.press[i].f();
			float scale = (0.6f + 0.4f * in) * (1f - 0.1f * pressed);
			float cx = r[0] + ICON / 2f, cy = r[1] + ICON / 2f + (1 - in) * 12;

			g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in * 1.3f)));
			AffineTransform t = g.getTransform();
			g.translate(cx, cy);
			g.scale(scale, scale);
			g.translate(-ICON / 2f, -ICON / 2f);
			drawGlassIcon(g, app, ICON, 1f - 0.2f * pressed);
			g.setTransform(t);

			g.setFont(font(Font.BOLD, 7.2f));
			g.setColor(new Color(0, 20, 60, 170));
			centered(g, app.name(), cx + 0.5f, cy + ICON / 2f + 10.3f);
			g.setColor(Color.WHITE);
			centered(g, app.name(), cx, cy + ICON / 2f + 9.6f);
		}
		g.setComposite(old);
	}

	/** Widget grande do topo: relógio sobre um céu com sol, nuvens e grama. */
	private void drawHero(Graphics2D g, float in) {
		if (in <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in)));
		float x = SX + 6, y = HERO_Y - (1 - in) * 12, w = SW - 12, h = HERO_H;

		// Caixa de vidro em volta.
		g.setColor(new Color(0, 20, 60, 70));
		g.fill(new RoundRectangle2D.Float(x - 1.5f, y + 0.5f, w + 3, h + 3.5f, 7, 7));
		g.setColor(new Color(255, 255, 255, 70));
		g.fill(new RoundRectangle2D.Float(x - 2.5f, y - 2.5f, w + 5, h + 5, 8, 8));

		RoundRectangle2D box = new RoundRectangle2D.Float(x, y, w, h, 5, 5);
		Shape oldClip = g.getClip();
		g.clip(box);
		g.setPaint(new GradientPaint(0, y, new Color(0x1F7BE0), 0, y + h, new Color(0xB5E4FF)));
		g.fill(box);

		// Sol com halo.
		float sx = x + w * 0.74f, sy = y + 36;
		g.setPaint(new RadialGradientPaint(sx, sy, 46, new float[] {0f, 0.25f, 1f},
			new Color[] {new Color(255, 255, 235, 255), new Color(255, 244, 170, 170), new Color(255, 244, 170, 0)}));
		g.fill(new Ellipse2D.Float(sx - 46, sy - 46, 92, 92));

		// Nuvens.
		g.setColor(new Color(255, 255, 255, 225));
		float[][] puffs = {{0.50f, 62, 15}, {0.60f, 57, 19}, {0.71f, 63, 15}, {0.10f, 72, 11}, {0.19f, 69, 14}, {0.29f, 73, 11}, {0.90f, 68, 13}};
		for (float[] p : puffs) {
			g.fill(new Ellipse2D.Float(x + w * p[0] - p[2], y + p[1] - p[2] * 0.62f, p[2] * 2, p[2] * 1.24f));
		}

		// Grama e uma árvore.
		Path2D hill = new Path2D.Float();
		hill.moveTo(x, y + h - 17);
		hill.curveTo(x + w * 0.3f, y + h - 30, x + w * 0.65f, y + h - 12, x + w, y + h - 24);
		hill.lineTo(x + w, y + h);
		hill.lineTo(x, y + h);
		hill.closePath();
		g.setPaint(new GradientPaint(0, y + h - 30, new Color(0x9BE04C), 0, y + h, new Color(0x2F8A1C)));
		g.fill(hill);
		g.setColor(new Color(0x4A3418));
		g.fill(new Rectangle2D.Float(x + w * 0.865f, y + h - 34, 1.8f, 12));
		g.setColor(new Color(0x2E7D1E));
		g.fill(new Ellipse2D.Float(x + w * 0.865f - 6.5f, y + h - 45, 14.8f, 14));
		g.setColor(new Color(0x4FA72E));
		g.fill(new Ellipse2D.Float(x + w * 0.865f - 5.2f, y + h - 45.6f, 9.5f, 8.6f));

		// Reflexo do vidro na metade de cima.
		g.setPaint(new GradientPaint(0, y, new Color(255, 255, 255, 80), 0, y + h * 0.5f, new Color(255, 255, 255, 12)));
		g.fill(new Ellipse2D.Float(x - w * 0.2f, y - h * 0.55f, w * 1.4f, h));
		g.setClip(oldClip);
		g.setStroke(new BasicStroke(0.9f));
		g.setColor(new Color(255, 255, 255, 190));
		g.draw(box);

		LocalDateTime now = LocalDateTime.now();
		g.setFont(Ui.light(40f));
		g.setColor(new Color(0, 30, 80, 130));
		Ui.text(g, now.format(PhoneOS.timeFormat()), x + 8.8f, y + 43.6f);
		g.setColor(Color.WHITE);
		Ui.text(g, now.format(PhoneOS.timeFormat()), x + 8, y + 42.4f);
		g.setFont(font(Font.BOLD, 8.4f));
		g.setColor(new Color(0, 30, 80, 130));
		Ui.text(g, now.format(Lang.format("handyphone.format.date.short")), x + 10.6f, y + 55.6f);
		g.setColor(Color.WHITE);
		Ui.text(g, now.format(Lang.format("handyphone.format.date.short")), x + 10, y + 55f);
		g.setComposite(old);
	}

	/** Ícone quadrado arredondado com degradê rico e reflexo de vidro na metade de cima. */
	private void drawGlassIcon(Graphics2D g, App app, float size, float brightness) {
		Color c1 = shade(app.c1(), brightness), c2 = shade(app.c2(), brightness);
		float r = size * 0.36f;
		g.setColor(new Color(0, 15, 50, 80));
		g.fill(new RoundRectangle2D.Float(0.4f, 1.8f, size, size, r, r));
		RoundRectangle2D body = new RoundRectangle2D.Float(0, 0, size, size, r, r);
		g.setPaint(new GradientPaint(0, 0, shade(c1, 1.08f), 0, size, shade(c2, 0.85f)));
		g.fill(body);
		Shape oldClip = g.getClip();
		g.clip(body);
		// Luz subindo de baixo, típica do visual.
		g.setPaint(new RadialGradientPaint(size / 2f, size * 1.05f, size * 0.7f, new float[] {0f, 1f},
			new Color[] {new Color(255, 255, 255, 90), new Color(255, 255, 255, 0)}));
		g.fill(body);
		g.setClip(oldClip);

		AffineTransform t = g.getTransform();
		g.scale(size / 34f, size / 34f);
		g.translate(0, 0.9f);
		Color dark = shade(c2, 0.5f);
		Ui.glyph(g, app.id(), Ui.alpha(dark, 150), Ui.alpha(dark, 0));
		g.translate(0, -0.9f);
		Ui.glyph(g, app.id(), Color.WHITE, app.id().equals("camera") ? shade(c2, 0.55f) : c2);
		g.setTransform(t);

		g.clip(body);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 135), 0, size * 0.5f, new Color(255, 255, 255, 25)));
		g.fill(new Ellipse2D.Float(-size * 0.35f, -size * 0.52f, size * 1.7f, size));
		g.setClip(oldClip);
		g.setStroke(new BasicStroke(0.9f));
		g.setColor(shade(c2, 0.55f));
		g.draw(body);
	}

	private void drawApp(Graphics2D g, PhoneOS os, App app, float a) {
		// A janela cresce do centro e aparece aos poucos.
		Composite old = g.getComposite();
		AffineTransform base = g.getTransform();
		float scale = 0.88f + 0.12f * Ui.easeOut(a);
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(a * 1.4f)));
		g.translate(FACE_W / 2f, SY + SH / 2f);
		g.scale(scale, scale);
		g.translate(-FACE_W / 2f, -(SY + SH / 2f));

		float top = SY + STATUS_H;
		g.setColor(new Color(0xEEF1F5));
		g.fill(new Rectangle2D.Float(SX, top, SW, SH - STATUS_H));

		// Cabeçalho escuro com a linha azul.
		g.setPaint(new GradientPaint(0, top, new Color(0x3C4A5C), 0, top + HEADER_H, new Color(0x1A232F)));
		g.fill(new Rectangle2D.Float(SX, top, SW, HEADER_H));
		g.setColor(new Color(255, 255, 255, 28));
		g.fill(new Rectangle2D.Float(SX, top, SW, HEADER_H / 2f));
		g.setColor(HOLO);
		g.fill(new Rectangle2D.Float(SX, top + HEADER_H - 1.4f, SW, 1.4f));
		float p = clamp01(os.homeBarPress.f());
		if (p > 0.01f) {
			g.setColor(Ui.alpha(HOLO, Math.round(120 * p)));
			g.fill(new Rectangle2D.Float(SX, top, 36, HEADER_H - 1.4f));
		}
		g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0xD5DCE6));
		Path2D arrow = new Path2D.Float();
		arrow.moveTo(SX + 9.5f, top + 8);
		arrow.lineTo(SX + 5.5f, top + 12.5f);
		arrow.lineTo(SX + 9.5f, top + 17);
		g.draw(arrow);
		AffineTransform t = g.getTransform();
		g.translate(SX + 14, top + 4.5f);
		drawGlassIcon(g, app, 16, 1f);
		g.setTransform(t);
		g.setFont(font(Font.BOLD, 11f));
		g.setColor(Color.WHITE);
		Ui.text(g, app.name(), SX + 36, top + 16.2f);

		float[] body = appBody();
		if (!os.drawApp(g, app, body, accent())) {
			float cx = body[0] + body[2] / 2f;
			t = g.getTransform();
			g.translate(cx - 28, body[1] + 48);
			drawGlassIcon(g, app, 56, 1f);
			g.setTransform(t);
			g.setColor(new Color(0x1B2330));
			g.setFont(font(Font.BOLD, 13f));
			centered(g, Lang.t("handyphone.soon.title"), cx, body[1] + 132);
			g.setColor(new Color(0x5F6875));
			g.setFont(font(Font.PLAIN, 9.2f));
			centered(g, Lang.t("handyphone.soon.line1"), cx, body[1] + 149);
			centered(g, Lang.t("handyphone.soon.line2"), cx, body[1] + 161);
		}
		g.setTransform(base);
		g.setComposite(old);
	}
}
