package dev.migzu.handyphone.client.os;

import static dev.migzu.handyphone.client.os.PhoneOS.FACE_H;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_R;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_W;
import static dev.migzu.handyphone.client.os.Ui.centered;
import static dev.migzu.handyphone.client.os.Ui.clamp01;
import static dev.migzu.handyphone.client.os.Ui.font;
import static dev.migzu.handyphone.client.os.Ui.lerp;
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
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Estilo de 2016: minimalista. Cores lisas, letras finas, ícones sem brilho,
 * relógio sem caixa e menus brancos com detalhes em azul.
 */
final class FlatTheme extends Theme {
	private static final float SX = 5f, SY = 14f, SW = FACE_W - 10f, SH = 362f;
	private static final float STATUS_H = 13f;
	private static final int COLS = 4;
	private static final float ICON = 33f;
	private static final float DOCK_Y = SY + SH - 56f;
	private static final float GRID_Y = DOCK_Y - 78f;
	private static final float HEADER_H = 30f;
	// Botão físico de início: só um contorno fino na moldura de baixo.
	private static final float KEY_CX = FACE_W / 2f, KEY_CY = 389f, KEY_W = 38f, KEY_H = 10f;

	private static final Color COBALT = new Color(0x2B6BE4);
	private static final Color INK = new Color(0x25272C);

	private BufferedImage wallpaper;

	@Override
	String id() {
		return "flat";
	}

	@Override
	String name() {
		return Lang.t("handyphone.theme.flat.name");
	}

	@Override
	String description() {
		return Lang.t("handyphone.theme.flat.description");
	}

	// ------------------------------------------------------------------ layout

	/** Canto superior esquerdo do ícone i. */
	private static float[] iconRect(PhoneOS os, int i) {
		float gap = (SW - COLS * ICON) / (COLS + 1);
		float x = SX + gap + slot(os, i) % COLS * (ICON + gap);
		return new float[] {x, os.apps.get(i).dock() ? DOCK_Y : GRID_Y};
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
		boolean onArrow = os.isAppOpen() && x >= SX && x <= SX + 30 && y >= SY + STATUS_H && y <= SY + STATUS_H + HEADER_H;
		return physicalHomeAt(x, y) || onArrow;
	}

	@Override
	float screenRadius() {
		return 3f;
	}

	@Override
	java.awt.Color screenEdgeColor() {
		return new Color(0x0B0C0F);
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
		if (pressed > 0.01f) {
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
		drawStatusBar(g, os, a);
		g.setClip(oldClip);
	}

	private void drawBezel(Graphics2D g, float p) {
		g.setColor(new Color(0x0B0C0F));
		g.fill(new RoundRectangle2D.Float(0, 0, FACE_W, FACE_H, FACE_R * 2, FACE_R * 2));
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(255, 255, 255, 30));
		g.draw(new RoundRectangle2D.Float(0.6f, 0.6f, FACE_W - 1.2f, FACE_H - 1.2f, FACE_R * 2 - 1, FACE_R * 2 - 1));

		// Alto-falante e câmera frontal, discretos.
		g.setColor(new Color(0x26282E));
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - 13, 5.6f, 26, 2.6f, 2.6f, 2.6f));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 22, 4.6f, 4.4f, 4.4f));

		RoundRectangle2D key = new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f, KEY_CY - KEY_H / 2f, KEY_W, KEY_H, KEY_H, KEY_H);
		if (p > 0.01f) {
			g.setColor(new Color(255, 255, 255, Math.round(60 * p)));
			g.fill(key);
		}
		g.setStroke(new BasicStroke(0.9f));
		g.setColor(new Color(0x6E727C));
		g.draw(key);
	}

	private void drawWallpaper(Graphics2D g, float pixelScale) {
		int w = Math.round(FACE_W * pixelScale), h = Math.round(FACE_H * pixelScale);
		if (wallpaper == null || wallpaper.getWidth() != w) {
			wallpaper = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D wg = wallpaper.createGraphics();
			wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			wg.scale(pixelScale, pixelScale);
			wg.setPaint(new GradientPaint(SX, SY, new Color(0x6FAFAE), SX + SW, SY + SH, new Color(0xB9C6B4)));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			wg.setPaint(new RadialGradientPaint(SX + SW * 0.15f, SY + SH * 0.3f, 190, new float[] {0f, 1f},
				new Color[] {new Color(70, 150, 160, 150), new Color(70, 150, 160, 0)}));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			wg.setPaint(new RadialGradientPaint(SX + SW * 0.9f, SY + SH * 0.5f, 170, new float[] {0f, 1f},
				new Color[] {new Color(214, 208, 180, 150), new Color(214, 208, 180, 0)}));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			// Lâminas geométricas escuras no canto, com um filete dourado.
			Path2D back = new Path2D.Float();
			back.moveTo(SX + SW * 0.42f, SY + SH);
			back.lineTo(SX + SW, SY + SH * 0.80f);
			back.lineTo(SX + SW, SY + SH);
			back.closePath();
			wg.setColor(new Color(0x2B3A52));
			wg.fill(back);
			Path2D front = new Path2D.Float();
			front.moveTo(SX, SY + SH * 0.93f);
			front.lineTo(SX + SW * 0.66f, SY + SH * 0.865f);
			front.lineTo(SX + SW, SY + SH * 0.94f);
			front.lineTo(SX + SW, SY + SH);
			front.lineTo(SX, SY + SH);
			front.closePath();
			wg.setColor(new Color(0x1A2436));
			wg.fill(front);
			wg.setStroke(new BasicStroke(0.8f));
			wg.setColor(new Color(0xCDAA63));
			wg.draw(new Line2D.Float(SX + SW * 0.42f, SY + SH, SX + SW, SY + SH * 0.80f));
			wg.dispose();
		}
		AffineTransform t = g.getTransform();
		g.scale(1 / pixelScale, 1 / pixelScale);
		g.drawImage(wallpaper, 0, 0, null);
		g.setTransform(t);
	}

	/** Barra de status transparente: branca sobre o papel de parede, cinza dentro dos apps. */
	private void drawStatusBar(Graphics2D g, PhoneOS os, float a) {
		int v = Math.round(lerp(255, 96, clamp01((a - 0.4f) / 0.3f)));
		Color c = new Color(v, v, v);
		String clock = os.clockText();
		g.setFont(font(Font.PLAIN, 8f));
		float cw = Ui.textWidth(g, clock), cx = SX + SW - cw - 5;
		g.setColor(c);
		Ui.text(g, clock, cx, SY + STATUS_H - 3.4f);

		g.setFont(font(Font.PLAIN, 7f));
		String pct = "87%";
		float pw = Ui.textWidth(g, pct), px = cx - pw - 4;
		Ui.text(g, pct, px, SY + STATUS_H - 3.6f);

		float bx = px - 9.5f, by = SY + 3f;
		g.setStroke(new BasicStroke(0.7f));
		g.draw(new RoundRectangle2D.Float(bx, by + 0.9f, 4.6f, 7f, 1.2f, 1.2f));
		g.fill(new Rectangle2D.Float(bx + 1.4f, by, 1.8f, 0.9f));
		g.fill(new Rectangle2D.Float(bx + 1f, by + 3f, 2.6f, 4f));

		float sx = bx - 13, base = SY + STATUS_H - 3.6f;
		for (int i = 0; i < 4; i++) {
			float bh = 1.8f + i * 1.7f;
			g.fill(new Rectangle2D.Float(sx + i * 2.6f, base - bh, 1.6f, bh));
		}
	}

	private void drawLock(Graphics2D g, PhoneOS os, float u) {
		float alpha = clamp01(1 - u * 1.6f);
		if (alpha <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		float dy = -36 * Ui.easeIn(u);
		LocalDateTime now = LocalDateTime.now();
		g.setColor(Color.WHITE);
		g.setFont(Ui.light(58f));
		centered(g, now.format(PhoneOS.timeFormat()), FACE_W / 2f, SY + 124 + dy);
		g.setFont(font(Font.PLAIN, 10f));
		centered(g, capitalized(now.format(Lang.format("handyphone.format.date.mid"))), FACE_W / 2f, SY + 142 + dy);

		float pulse = 0.55f + 0.45f * (float) Math.sin(os.time * 2.4);
		g.setComposite(AlphaComposite.SrcOver.derive(alpha * (0.5f + 0.45f * pulse)));
		g.setFont(font(Font.PLAIN, 8.8f));
		centered(g, Lang.t("handyphone.lock.hint", os.useKeyName()), FACE_W / 2f, SY + SH - 62);
		g.setComposite(old);
	}

	private static String capitalized(String s) {
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private void drawHome(Graphics2D g, PhoneOS os, float u) {
		Composite old = g.getComposite();

		// Relógio e data sem caixa, só tipografia fina e um sol em traço.
		float headIn = stagger(u, 0.05f);
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(headIn)));
		float hx = SX + 13, hy = SY + STATUS_H + 44 + (1 - headIn) * 10;
		LocalDateTime now = LocalDateTime.now();
		g.setColor(Color.WHITE);
		g.setFont(Ui.light(34f));
		String time = now.format(PhoneOS.timeFormat());
		Ui.text(g, time, hx, hy);
		float tw = Ui.textWidth(g, time);
		float sunX = hx + tw + 16, sunY = hy - 13;
		g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Ellipse2D.Float(sunX - 4.5f, sunY - 4.5f, 9, 9));
		for (int i = 0; i < 8; i++) {
			double ang = i * Math.PI / 4;
			g.draw(new Line2D.Double(sunX + Math.cos(ang) * 7, sunY + Math.sin(ang) * 7, sunX + Math.cos(ang) * 9.4, sunY + Math.sin(ang) * 9.4));
		}
		g.setFont(font(Font.PLAIN, 8.6f));
		Ui.text(g, capitalized(now.format(Lang.format("handyphone.format.date.mid"))), hx + 1.5f, hy + 14);

		// Indicador de páginas.
		float dockIn = stagger(u, 0.25f);
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(dockIn)));
		for (int i = 0; i < 3; i++) {
			g.setColor(i == 0 ? Color.WHITE : new Color(255, 255, 255, 110));
			g.fill(new Ellipse2D.Float(SX + SW / 2f - 8.5f + i * 7, DOCK_Y - 15, 3, 3));
		}

		for (int i = 0; i < os.apps.size(); i++) {
			App app = os.apps.get(i);
			float[] r = iconRect(os, i);
			float in = app.dock() ? dockIn : stagger(u, 0.12f + 0.04f * slot(os, i));
			float pressed = os.press[i].f();
			float scale = (0.7f + 0.3f * in) * (1f - 0.08f * pressed);
			float cx = r[0] + ICON / 2f, cy = r[1] + ICON / 2f + (1 - in) * 10;

			g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in * 1.3f)));
			AffineTransform t = g.getTransform();
			g.translate(cx, cy);
			g.scale(scale, scale);
			g.translate(-ICON / 2f, -ICON / 2f);
			drawFlatIcon(g, app, ICON, 1f - 0.15f * pressed);
			g.setTransform(t);

			g.setFont(font(Font.PLAIN, 7.2f));
			g.setColor(new Color(0, 0, 0, 70));
			centered(g, app.name(), cx, cy + ICON / 2f + 10.3f);
			g.setColor(Color.WHITE);
			centered(g, app.name(), cx, cy + ICON / 2f + 9.8f);
		}
		g.setComposite(old);
	}

	/** Cor única do ícone: a média das duas cores do app, sem degradê. */
	private static Color flatColor(App app, float brightness) {
		Color c1 = app.c1(), c2 = app.c2();
		Color mid = new Color((c1.getRed() + c2.getRed()) / 2, (c1.getGreen() + c2.getGreen()) / 2, (c1.getBlue() + c2.getBlue()) / 2);
		return shade(mid, brightness);
	}

	/** Ícone de cor lisa com o símbolo branco, sem brilho. */
	private void drawFlatIcon(Graphics2D g, App app, float size, float brightness) {
		Color color = flatColor(app, brightness);
		float r = size * 0.62f;
		g.setColor(new Color(0, 0, 0, 34));
		g.fill(new RoundRectangle2D.Float(0, 1.2f, size, size, r, r));
		g.setColor(color);
		g.fill(new RoundRectangle2D.Float(0, 0, size, size, r, r));
		AffineTransform t = g.getTransform();
		g.translate(size * 0.09f, size * 0.09f);
		g.scale(size * 0.82f / 34f, size * 0.82f / 34f);
		Ui.glyph(g, app.id(), Color.WHITE, color);
		g.setTransform(t);
	}

	private void drawApp(Graphics2D g, PhoneOS os, App app, float a) {
		// A janela sobe um pouco e aparece aos poucos.
		Composite old = g.getComposite();
		AffineTransform base = g.getTransform();
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(a * 1.5f)));
		g.translate(0, (1 - Ui.easeOut(a)) * 46);

		g.setColor(new Color(0xFAFAFB));
		g.fill(new Rectangle2D.Float(SX, SY, SW, SH + 46));

		float top = SY + STATUS_H;
		float p = clamp01(os.homeBarPress.f());
		if (p > 0.01f) {
			g.setColor(Ui.alpha(COBALT, Math.round(40 * p)));
			g.fill(new Ellipse2D.Float(SX + 2, top + 3, 24, 24));
		}
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(COBALT);
		Path2D arrow = new Path2D.Float();
		arrow.moveTo(SX + 16, top + 10);
		arrow.lineTo(SX + 11, top + 15);
		arrow.lineTo(SX + 16, top + 20);
		g.draw(arrow);
		g.setColor(INK);
		g.setFont(font(Font.PLAIN, 13f));
		Ui.text(g, app.name(), SX + 28, top + 19.6f);
		g.setColor(new Color(0xE3E4E8));
		g.fill(new Rectangle2D.Float(SX, top + HEADER_H - 0.7f, SW, 0.7f));

		float[] body = appBody();
		if (!os.drawApp(g, app, body, COBALT)) {
			float cx = body[0] + body[2] / 2f;
			AffineTransform t = g.getTransform();
			g.translate(cx - 28, body[1] + 52);
			drawFlatIcon(g, app, 56, 1f);
			g.setTransform(t);
			g.setColor(INK);
			g.setFont(Ui.light(17f));
			centered(g, Lang.t("handyphone.soon.title"), cx, body[1] + 138);
			g.setColor(new Color(0x7A7D86));
			g.setFont(font(Font.PLAIN, 9.2f));
			centered(g, Lang.t("handyphone.soon.line1"), cx, body[1] + 155);
			centered(g, Lang.t("handyphone.soon.line2"), cx, body[1] + 167);
		}
		g.setTransform(base);
		g.setComposite(old);
	}
}
