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
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Estilo atual de smartphone: tela de ponta a ponta, ícones arredondados, molas. */
final class ModernTheme extends Theme {
	private static final float BEZEL = 5f;
	private static final float SX = BEZEL, SY = BEZEL, SW = FACE_W - 2 * BEZEL, SH = FACE_H - 2 * BEZEL;
	private static final float SCREEN_R = 25f;

	private static final int COLS = 4;
	private static final float ICON = 34f, ICON_R = 9.5f;
	private static final float GRID_TOP = SY + 58f;
	private static final float ROW_H = 58f;
	private static final float DOCK_H = 50f;
	private static final float DOCK_Y = SY + SH - DOCK_H - 14f;
	/** Onde começa o conteúdo de um app, abaixo do título. */
	private static final float APP_BODY_TOP = 64f;


	private BufferedImage wallpaper;

	@Override
	String id() {
		return "modern";
	}

	@Override
	String name() {
		return Lang.t("handyphone.theme.modern.name");
	}

	@Override
	String description() {
		return Lang.t("handyphone.theme.modern.description");
	}

	// ------------------------------------------------------------------ layout

	@Override
	int iconAt(PhoneOS os, float x, float y) {
		for (int i = 0; i < os.apps.size(); i++) {
			float[] r = iconRect(os, i);
			float pad = 6;
			if (x >= r[0] - pad && x <= r[0] + ICON + pad && y >= r[1] - pad && y <= r[1] + ICON + pad + 10) {
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
	boolean backAt(PhoneOS os, float x, float y) {
		return os.isAppOpen() && y > SY + SH - 20;
	}

	@Override
	float screenRadius() {
		return SCREEN_R;
	}

	@Override
	java.awt.Color screenEdgeColor() {
		return new Color(0x08080A);
	}

	@Override
	float[] screen() {
		return new float[] {SX, SY, SW, SH};
	}

	@Override
	float[] appBody() {
		return new float[] {SX, SY + APP_BODY_TOP, SW, SH - APP_BODY_TOP - 20};
	}

	/** Canto superior esquerdo do ícone i. */
	private float[] iconRect(PhoneOS os, int i) {
		App app = os.apps.get(i);
		float gap = (SW - COLS * ICON) / (COLS + 1);
		int slot = 0;
		for (int k = 0; k < i; k++) {
			if (os.apps.get(k).dock() == app.dock()) {
				slot++;
			}
		}
		if (app.dock()) {
			float dockGap = (SW - 16 - COLS * ICON) / (COLS + 1);
			return new float[] {SX + 8 + dockGap + slot * (ICON + dockGap), DOCK_Y + (DOCK_H - ICON) / 2f};
		}
		int col = slot % COLS, row = slot / COLS;
		return new float[] {SX + gap + col * (ICON + gap), GRID_TOP + row * ROW_H};
	}

	// ------------------------------------------------------------------ desenho

	@Override
	void render(Graphics2D g, PhoneOS os, float pixelScale) {
		// Moldura
		g.setColor(new Color(0x08080A));
		g.fill(new RoundRectangle2D.Float(0, 0, FACE_W, FACE_H, FACE_R * 2, FACE_R * 2));
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(255, 255, 255, 38));
		g.draw(new RoundRectangle2D.Float(0.6f, 0.6f, FACE_W - 1.2f, FACE_H - 1.2f, FACE_R * 2 - 1, FACE_R * 2 - 1));

		Shape oldClip = g.getClip();
		RoundRectangle2D screen = new RoundRectangle2D.Float(SX, SY, SW, SH, SCREEN_R * 2, SCREEN_R * 2);
		g.clip(screen);

		float u = clamp01(os.unlock.f());
		float a = os.appOpen.f();

		drawWallpaper(g, pixelScale);
		if (!homeCovered(os, a)) {
			if (u < 0.999f) {
				drawLock(g, os, u);
			}
			if (u > 0.001f) {
				drawHome(g, os, u, a);
			}
		}
		if (os.openApp >= 0) {
			drawApp(g, os, os.apps.get(os.openApp), a);
		}
		drawStatusBar(g, os, u, a);

		// Ilha / câmera frontal
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - 23, SY + 7, 46, 13.5f, 13.5f, 13.5f));
		g.setColor(new Color(0x15151F));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 10.5f, SY + 10.4f, 6.6f, 6.6f));
		g.setColor(new Color(60, 70, 120, 150));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 12.6f, SY + 12.3f, 2.2f, 2.2f));

		// Reflexo sutil do vidro
		g.setPaint(new GradientPaint(SX, SY, new Color(255, 255, 255, 16), SX + SW * 0.75f, SY + SH * 0.4f, new Color(255, 255, 255, 0)));
		Path2D glare = new Path2D.Float();
		glare.moveTo(SX, SY);
		glare.lineTo(SX + SW * 0.72f, SY);
		glare.lineTo(SX, SY + SH * 0.46f);
		glare.closePath();
		g.fill(glare);

		g.setClip(oldClip);
	}

	private void drawWallpaper(Graphics2D g, float pixelScale) {
		int w = Math.round(FACE_W * pixelScale), h = Math.round(FACE_H * pixelScale);
		if (wallpaper == null || wallpaper.getWidth() != w) {
			wallpaper = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D wg = wallpaper.createGraphics();
			wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			wg.scale(pixelScale, pixelScale);
			wg.setPaint(new GradientPaint(0, 0, new Color(0x0B1026), 0, FACE_H, new Color(0x2A0F3F)));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			blob(wg, 150, 70, 170, new Color(0x3D5AFE));
			blob(wg, 20, 190, 190, new Color(0xD500F9));
			blob(wg, 170, 330, 200, new Color(0xFF6D3A));
			blob(wg, 40, 390, 150, new Color(0xFFC53D));
			blob(wg, 70, 30, 120, new Color(0x00E5FF));
			wg.dispose();
		}
		AffineTransform t = g.getTransform();
		g.scale(1 / pixelScale, 1 / pixelScale);
		g.drawImage(wallpaper, 0, 0, null);
		g.setTransform(t);
	}

	private static void blob(Graphics2D g, float cx, float cy, float r, Color c) {
		Color c0 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 150);
		Color c1 = new Color(c.getRed(), c.getGreen(), c.getBlue(), 0);
		g.setPaint(new RadialGradientPaint(cx, cy, r, new float[] {0f, 1f}, new Color[] {c0, c1}));
		g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
	}

	private void drawLock(Graphics2D g, PhoneOS os, float u) {
		float alpha = clamp01(1 - u * 1.6f);
		if (alpha <= 0) {
			return;
		}
		float dy = -46 * Ui.easeIn(u);
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		LocalDateTime now = LocalDateTime.now();

		g.setColor(new Color(255, 255, 255, 215));
		g.setFont(font(Font.PLAIN, 10.5f));
		String date = now.format(Lang.format("handyphone.format.date.long"));
		date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
		centered(g, date, FACE_W / 2f, SY + 62 + dy);

		g.setColor(Color.WHITE);
		g.setFont(font(Font.BOLD, 50f));
		centered(g, now.format(PhoneOS.timeFormat()), FACE_W / 2f, SY + 110 + dy);

		// Dica pulsando embaixo
		float pulse = 0.55f + 0.45f * (float) Math.sin(os.time * 2.4);
		g.setComposite(AlphaComposite.SrcOver.derive(alpha * (0.45f + 0.4f * pulse)));
		g.setFont(font(Font.PLAIN, 9f));
		centered(g, Lang.t("handyphone.lock.hint", os.useKeyName()), FACE_W / 2f, SY + SH - 30 - dy * 0.3f);
		g.setComposite(AlphaComposite.SrcOver.derive(alpha * 0.8f));
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - 26, SY + SH - 11, 52, 3.4f, 3.4f, 3.4f));
		g.setComposite(old);
	}

	private void drawHome(Graphics2D g, PhoneOS os, float u, float a) {
		Composite old = g.getComposite();
		AffineTransform base = g.getTransform();
		float aClamped = clamp01(a);

		// A tela inicial recua um pouco quando um app abre.
		float homeScale = 1f - 0.07f * aClamped;
		g.translate(FACE_W / 2f, FACE_H / 2f);
		g.scale(homeScale, homeScale);
		g.translate(-FACE_W / 2f, -FACE_H / 2f);

		// Dock
		float dockIn = stagger(u, 0.25f);
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(dockIn)));
		float dockDy = (1 - dockIn) * 26;
		g.setColor(new Color(255, 255, 255, 46));
		g.fill(new RoundRectangle2D.Float(SX + 8, DOCK_Y + dockDy, SW - 16, DOCK_H, 30, 30));
		g.setColor(new Color(255, 255, 255, 30));
		g.setStroke(new BasicStroke(0.6f));
		g.draw(new RoundRectangle2D.Float(SX + 8.3f, DOCK_Y + dockDy + 0.3f, SW - 16.6f, DOCK_H - 0.6f, 30, 30));

		for (int i = 0; i < os.apps.size(); i++) {
			App app = os.apps.get(i);
			float[] r = iconRect(os, i);
			float in = app.dock() ? dockIn : stagger(u, 0.05f + 0.045f * i);
			float pressed = os.press[i].f();
			float scale = (0.55f + 0.45f * in) * (1f - 0.13f * pressed);
			float cx = r[0] + ICON / 2f, cy = r[1] + ICON / 2f + (app.dock() ? dockDy : (1 - in) * 14);

			AffineTransform t = g.getTransform();
			g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in * 1.3f)));
			g.translate(cx, cy);
			g.scale(scale, scale);
			g.translate(-ICON / 2f, -ICON / 2f);
			// Não desenha o ícone do app aberto: ele "virou" a janela.
			if (i != os.openApp || a < 0.02f) {
				drawIcon(g, app, ICON, ICON_R, 1f - 0.18f * pressed);
			}
			g.setTransform(t);

			if (!app.dock()) {
				g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in * 1.3f) * 0.95f));
				g.setFont(font(Font.PLAIN, 7.6f));
				g.setColor(new Color(0, 0, 0, 90));
				centered(g, app.name(), cx + 0.3f, cy + ICON / 2f + 10.6f);
				g.setColor(Color.WHITE);
				centered(g, app.name(), cx, cy + ICON / 2f + 10f);
			}
		}

		g.setTransform(base);
		g.setComposite(old);

		// Escurece o fundo conforme o app abre.
		if (aClamped > 0.001f) {
			g.setColor(new Color(0, 0, 0, Math.round(120 * aClamped)));
			g.fillRect(0, 0, FACE_W, FACE_H);
		}
	}

	private void drawApp(Graphics2D g, PhoneOS os, App app, float a) {
		float[] r = iconRect(os, os.apps.indexOf(app));
		// A janela nasce do ícone e cresce até a tela inteira (pode passar um pouco: mola).
		float x = lerp(r[0], SX, a), y = lerp(r[1], SY, a);
		float w = lerp(ICON, SW, a), h = lerp(ICON, SH, a);
		float rad = lerp(ICON_R, SCREEN_R, clamp01(a));
		if (w < 1 || h < 1) {
			return;
		}
		RoundRectangle2D win = new RoundRectangle2D.Float(x, y, w, h, rad * 2, rad * 2);
		Shape oldClip = g.getClip();
		Composite old = g.getComposite();
		g.clip(win);

		// Fundo do app
		g.setColor(new Color(0xF4F4F8));
		g.fill(win);

		float content = clamp01((a - 0.35f) / 0.5f);
		if (content > 0) {
			g.setComposite(AlphaComposite.SrcOver.derive(content));
			AffineTransform t = g.getTransform();
			float s = w / SW;
			// O conteúdo é desenhado em coordenadas da face; aqui ele acompanha a janela que cresce.
			g.translate(x, y);
			g.scale(s, s);
			g.translate(-SX, -SY);
			drawAppContent(g, os, app);
			g.setTransform(t);
		}

		// O ícone ampliado se dissolve sobre o conteúdo.
		float iconAlpha = clamp01(1 - a * 1.9f);
		if (iconAlpha > 0) {
			g.setComposite(AlphaComposite.SrcOver.derive(iconAlpha));
			AffineTransform t = g.getTransform();
			g.setPaint(new GradientPaint(0, y, app.c1(), 0, y + h, app.c2()));
			g.fill(win);
			g.translate(x, y + (h - w) / 2f);
			g.scale(w / ICON, w / ICON);
			drawIcon(g, app, ICON, 0, 1f);
			g.setTransform(t);
		}
		g.setComposite(old);
		g.setClip(oldClip);
	}

	/** Conteúdo dos apps, em coordenadas da face (app totalmente aberto). */
	private void drawAppContent(Graphics2D g, PhoneOS os, App app) {
		g.setColor(new Color(0x111114));
		g.setFont(font(Font.BOLD, 21f));
		Ui.text(g, app.name(), SX + 14, SY + 54);

		if (!os.drawApp(g, app, appBody(), new Color(0x3D5AFE))) {
			AffineTransform t = g.getTransform();
			g.translate(FACE_W / 2f - 32, SY + 118);
			drawIcon(g, app, 64, 18, 1f);
			g.setTransform(t);

			g.setColor(new Color(0x111114));
			g.setFont(font(Font.BOLD, 13f));
			centered(g, Lang.t("handyphone.soon.title"), FACE_W / 2f, SY + 214);
			g.setColor(new Color(0x6B6B76));
			g.setFont(font(Font.PLAIN, 9.5f));
			centered(g, Lang.t("handyphone.soon.line1"), FACE_W / 2f, SY + 232);
			centered(g, Lang.t("handyphone.soon.line2"), FACE_W / 2f, SY + 245);
		}

		// Barra de início
		float p = os.homeBarPress.f();
		g.setColor(new Color(20, 20, 24, 200));
		float bw = 56 * (1 + 0.12f * p);
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - bw / 2, SY + SH - 10.5f, bw, 3.6f, 3.6f, 3.6f));
	}

	private void drawStatusBar(Graphics2D g, PhoneOS os, float u, float a) {
		float alpha = clamp01(u * 1.5f);
		if (alpha <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		// Branco na tela inicial, escuro dentro de um app claro.
		int v = Math.round(lerp(255, 17, clamp01((a - 0.45f) / 0.3f)));
		Color c = new Color(v, v, v);
		g.setColor(c);
		g.setFont(font(Font.BOLD, 9.2f));
		Ui.text(g, os.clockText(), SX + 20, SY + 18.5f);

		float bx = SX + SW - 44, by = SY + 11;
		for (int i = 0; i < 4; i++) {
			float bh = 2.6f + i * 1.7f;
			g.fill(new RoundRectangle2D.Float(bx + i * 3.4f, by + 8 - bh, 2.4f, bh, 1, 1));
		}
		float tx = bx + 17;
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(v, v, v, 150));
		g.draw(new RoundRectangle2D.Float(tx, by + 0.6f, 16.5f, 7.6f, 4.2f, 4.2f));
		g.fill(new RoundRectangle2D.Float(tx + 17.2f, by + 3f, 1.3f, 2.8f, 1, 1));
		g.setColor(c);
		g.fill(new RoundRectangle2D.Float(tx + 1.3f, by + 1.9f, 11.5f, 5f, 2.4f, 2.4f));
		g.setComposite(old);
	}

	private void drawIcon(Graphics2D g, App app, float size, float radius, float brightness) {
		Color c1 = shade(app.c1(), brightness), c2 = shade(app.c2(), brightness);
		Shape body = new RoundRectangle2D.Float(0, 0, size, size, radius * 2, radius * 2);
		g.setPaint(new GradientPaint(0, 0, c1, 0, size, c2));
		g.fill(body);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 60), 0, size * 0.55f, new Color(255, 255, 255, 0)));
		g.fill(body);

		AffineTransform t = g.getTransform();
		g.scale(size / 34f, size / 34f);
		if (app.id().equals("camera")) {
			Ui.glyph(g, app.id(), new Color(0x2B2B30), new Color(0xE8E8EE));
		} else {
			Ui.glyph(g, app.id(), Color.WHITE, c2);
		}
		g.setTransform(t);
	}
}
