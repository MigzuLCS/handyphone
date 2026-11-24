package dev.migzu.handyphone.client.os;

import static dev.migzu.handyphone.client.os.PhoneOS.FACE_H;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_R;
import static dev.migzu.handyphone.client.os.PhoneOS.FACE_W;
import static dev.migzu.handyphone.client.os.Ui.alpha;
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

/**
 * Estilo de celular de toque de 2009: moldura grossa com botão físico, plástico
 * brilhante, barra lateral de apps, cartão de widget e barra inferior de botões.
 */
final class RetroTheme extends Theme {
	// Tela: bem menor que a face, com moldura grossa em cima e embaixo.
	private static final float SX = 7f, SY = 24f, SW = FACE_W - 14f, SH = 328f;
	private static final float STATUS_H = 15f;
	private static final float BAR_H = 44f, BAR_Y = SY + SH - BAR_H;
	private static final float SIDE_W = 38f, SIDE_ICON = 28f, SIDE_TOP = SY + STATUS_H + 8f, SIDE_STEP = 64f;
	private static final float DOCK_ICON = 20f;
	private static final float TITLE_H = 24f, SOFTKEY_H = 22f;
	// Botão físico de início, na moldura de baixo.
	private static final float KEY_CX = FACE_W / 2f, KEY_CY = 384f, KEY_W = 32f, KEY_H = 15f;

	private static final Color INK = new Color(0x1D1D22);
	private static final Color BLUE_1 = new Color(0x6AA8F0), BLUE_2 = new Color(0x1F4FA8);

	private BufferedImage wallpaper;

	@Override
	String id() {
		return "retro";
	}

	@Override
	String name() {
		return Lang.t("handyphone.theme.retro.name");
	}

	@Override
	String description() {
		return Lang.t("handyphone.theme.retro.description");
	}

	@Override
	float[] iconBox(PhoneOS os, int i) {
		if (os.apps.get(i).dock()) {
			return null; // Na barra de baixo cada app é um botão inteiro (nome e símbolo): a área de toque já é o desenho.
		}
		float[] c = iconCenter(os, i);
		return new float[] {c[0] - SIDE_ICON / 2f, c[1] - SIDE_ICON / 2f, SIDE_ICON, SIDE_ICON, SIDE_ICON * 0.21f};
	}

	@Override
	Color accent() {
		return BLUE_2;
	}

	// ------------------------------------------------------------------ layout

	/** Área de toque do app i: {x, y, largura, altura}. */
	private static float[] hitRect(PhoneOS os, int i) {
		int slot = slot(os, i);
		if (os.apps.get(i).dock()) {
			float w = SW / 4f;
			return new float[] {SX + slot * w, BAR_Y, w, BAR_H};
		}
		return new float[] {SX, SIDE_TOP - 6 + slot * SIDE_STEP, SIDE_W, SIDE_STEP};
	}

	@Override
	int iconAt(PhoneOS os, float x, float y) {
		for (int i = 0; i < os.apps.size(); i++) {
			float[] r = hitRect(os, i);
			if (x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]) {
				return i;
			}
		}
		return -1;
	}

	@Override
	float[] iconCenter(PhoneOS os, int i) {
		int slot = slot(os, i);
		if (os.apps.get(i).dock()) {
			return new float[] {SX + (slot + 0.5f) * SW / 4f, BAR_Y + 19 + DOCK_ICON / 2f};
		}
		return new float[] {SX + SIDE_W / 2f, SIDE_TOP + slot * SIDE_STEP + SIDE_ICON / 2f};
	}

	@Override
	boolean physicalHomeAt(float x, float y) {
		return Math.abs(x - KEY_CX) <= KEY_W / 2f + 8 && Math.abs(y - KEY_CY) <= KEY_H / 2f + 7;
	}

	@Override
	boolean backAt(PhoneOS os, float x, float y) {
		boolean onSoftKey = os.isAppOpen() && x >= SX + SW / 2f && x <= SX + SW && y >= SY + SH - SOFTKEY_H && y <= SY + SH;
		return physicalHomeAt(x, y) || onSoftKey;
	}

	@Override
	float screenRadius() {
		return 4f;
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
		float top = SY + STATUS_H + TITLE_H;
		return new float[] {SX, top, SW, SY + SH - SOFTKEY_H - top};
	}

	// ------------------------------------------------------------------ desenho

	/** A moldura só muda com o botão físico apertado; parada, é desenhada uma vez e colada (ver StaticLayer). */
	private final StaticLayer bezel = new StaticLayer();

	@Override
	void releaseCaches() {
		bezel.clear();
	}

	/** A fonte, encolhida só se o texto não cabe em {@code maxWidth} (nomes longos de app em alemão, russo e francês). */
	private static Font fitted(Graphics2D g, String text, Font font, float maxWidth) {
		Font previous = g.getFont();
		g.setFont(font);
		float width = Ui.textWidth(g, text);
		g.setFont(previous);
		return width <= maxWidth ? font : font.deriveFont(font.getSize2D() * maxWidth / width);
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
		g.clip(new RoundRectangle2D.Float(SX, SY, SW, SH, 4, 4));

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

		// Reflexo do vidro e leve escurecimento nas bordas, como um LCD antigo.
		g.setPaint(new GradientPaint(SX, SY, new Color(255, 255, 255, 22), SX + SW * 0.7f, SY + SH * 0.35f, new Color(255, 255, 255, 0)));
		Path2D glare = new Path2D.Float();
		glare.moveTo(SX, SY);
		glare.lineTo(SX + SW * 0.8f, SY);
		glare.lineTo(SX, SY + SH * 0.42f);
		glare.closePath();
		g.fill(glare);
		g.setStroke(new BasicStroke(3f));
		g.setColor(new Color(0, 0, 0, 38));
		g.draw(new Rectangle2D.Float(SX, SY, SW, SH));

		g.setClip(oldClip);
	}

	private void drawBezel(Graphics2D g, float p) {
		RoundRectangle2D face = new RoundRectangle2D.Float(0, 0, FACE_W, FACE_H, FACE_R * 2, FACE_R * 2);
		g.setPaint(new GradientPaint(0, 0, new Color(0x232327), 0, FACE_H, new Color(0x0A0A0C)));
		g.fill(face);
		// Brilho de plástico na parte de cima da moldura.
		Shape oldClip = g.getClip();
		g.clip(face);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 34), 0, 20, new Color(255, 255, 255, 0)));
		g.fill(new Ellipse2D.Float(-40, -34, FACE_W + 80, 52));
		g.setClip(oldClip);
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(255, 255, 255, 40));
		g.draw(new RoundRectangle2D.Float(0.6f, 0.6f, FACE_W - 1.2f, FACE_H - 1.2f, FACE_R * 2 - 1, FACE_R * 2 - 1));

		// Alto-falante e sensor.
		g.setColor(new Color(0x050506));
		g.fill(new RoundRectangle2D.Float(FACE_W / 2f - 19, 9.5f, 38, 4.4f, 4.4f, 4.4f));
		g.setColor(new Color(0x3A3A40));
		for (int i = 0; i < 9; i++) {
			g.fill(new Ellipse2D.Float(FACE_W / 2f - 15.5f + i * 3.6f, 10.9f, 1.5f, 1.5f));
		}
		g.setColor(new Color(0x16161C));
		g.fill(new Ellipse2D.Float(FACE_W / 2f + 30, 9.2f, 5, 5));

		// Borda rebaixada da tela.
		g.setColor(new Color(0x000000));
		g.fill(new RoundRectangle2D.Float(SX - 1.5f, SY - 1.5f, SW + 3, SH + 3, 6, 6));

		// Marca (o nome do mod é provisório).
		g.setFont(font(Font.BOLD, 7.2f).deriveFont(java.util.Map.of(java.awt.font.TextAttribute.TRACKING, 0.22f)));
		g.setColor(new Color(0xB9BCC4));
		centered(g, Lang.t("handyphone.brand"), FACE_W / 2f, 366.5f);

		// Botão físico de início, com as teclas de chamada dos lados.
		RoundRectangle2D key = new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f, KEY_CY - KEY_H / 2f, KEY_W, KEY_H, 9, 9);
		g.setPaint(new GradientPaint(0, KEY_CY - KEY_H / 2f, new Color(0x8C8F97), 0, KEY_CY + KEY_H / 2f, new Color(0x3A3C42)));
		g.fill(key);
		float in = 2.2f;
		g.setPaint(new GradientPaint(0, KEY_CY - KEY_H / 2f, shade(new Color(0x2C2D33), 1f - 0.4f * p), 0, KEY_CY + KEY_H / 2f, shade(new Color(0x55585F), 1f - 0.4f * p)));
		g.fill(new RoundRectangle2D.Float(KEY_CX - KEY_W / 2f + in, KEY_CY - KEY_H / 2f + in, KEY_W - 2 * in, KEY_H - 2 * in, 6, 6));
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(0xC9CCD4));
		g.draw(new RoundRectangle2D.Float(KEY_CX - 4.5f, KEY_CY - 3f, 9, 6, 2.5f, 2.5f));

		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.setColor(new Color(0x3FBF4F));
		g.draw(new java.awt.geom.Line2D.Float(40, KEY_CY, 56, KEY_CY));
		g.setColor(new Color(0xD8402F));
		g.draw(new java.awt.geom.Line2D.Float(FACE_W - 56, KEY_CY, FACE_W - 40, KEY_CY));
	}

	private void drawWallpaper(Graphics2D g, float pixelScale) {
		int w = Math.round(FACE_W * pixelScale), h = Math.round(FACE_H * pixelScale);
		if (wallpaper == null || wallpaper.getWidth() != w) {
			wallpaper = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
			Graphics2D wg = wallpaper.createGraphics();
			wg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			wg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			wg.scale(pixelScale, pixelScale);
			float cx = SX + SW * 0.62f, cy = SY + SH * 0.40f;
			wg.setPaint(new RadialGradientPaint(cx, cy, 300, new float[] {0f, 0.45f, 1f},
				new Color[] {new Color(0xFFD23A), new Color(0xF9A30F), new Color(0xD86A00)}));
			wg.fillRect(0, 0, FACE_W, FACE_H);
			// Anéis pontilhados saindo do canto, como os papéis de parede da época.
			float ox = SX + SW + 20, oy = SY + SH - 40;
			for (int i = 1; i <= 14; i++) {
				float r = i * 24f;
				wg.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {0.1f, 5.2f}, i * 1.3f));
				wg.setColor(new Color(255, 236, 150, i % 2 == 0 ? 120 : 70));
				wg.draw(new Ellipse2D.Float(ox - r, oy - r, r * 2, r * 2));
			}
			wg.dispose();
		}
		AffineTransform t = g.getTransform();
		g.scale(1 / pixelScale, 1 / pixelScale);
		g.drawImage(wallpaper, 0, 0, null);
		g.setTransform(t);
	}

	private void drawStatusBar(Graphics2D g, PhoneOS os) {
		g.setColor(new Color(0x050505));
		g.fill(new Rectangle2D.Float(SX, SY, SW, STATUS_H));

		// Antena e barras de sinal.
		float x = SX + 4, base = SY + STATUS_H - 3.2f;
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1f));
		Path2D antenna = new Path2D.Float();
		antenna.moveTo(x, base - 9);
		antenna.lineTo(x + 6, base - 9);
		antenna.lineTo(x + 3, base - 5);
		antenna.closePath();
		g.fill(antenna);
		g.fill(new Rectangle2D.Float(x + 2.5f, base - 6, 1f, 6));
		for (int i = 0; i < 5; i++) {
			float bh = 2f + i * 1.8f;
			g.fill(new Rectangle2D.Float(x + 7.5f + i * 2.6f, base - bh, 1.7f, bh));
		}

		// Relógio em caixa amarela, como um visor.
		String clock = os.clockText();
		g.setFont(Ui.mono(Font.BOLD, 9f));
		float cw = Ui.textWidth(g, clock) + 6;
		float cx = SX + SW - cw - 3;
		g.setColor(new Color(0xF2D53C));
		g.fill(new Rectangle2D.Float(cx, SY + 2.5f, cw, STATUS_H - 5));
		g.setColor(Color.BLACK);
		Ui.text(g, clock, cx + 3, SY + STATUS_H - 4.2f);

		// Bateria em blocos.
		float bw = 19, bx = cx - bw - 5, by = SY + 3.5f, bh = STATUS_H - 7;
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(0.9f));
		g.draw(new Rectangle2D.Float(bx, by, bw, bh));
		g.fill(new Rectangle2D.Float(bx + bw, by + 2, 1.6f, bh - 4));
		g.setColor(new Color(0x4FA3FF));
		for (int i = 0; i < 4; i++) {
			g.fill(new Rectangle2D.Float(bx + 1.6f + i * 4.2f, by + 1.6f, 3.3f, bh - 3.2f));
		}
	}

	private void drawLock(Graphics2D g, PhoneOS os, float u) {
		float alpha = clamp01(1 - u * 1.6f);
		if (alpha <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(alpha));
		g.setColor(new Color(40, 15, 0, 95));
		g.fill(new Rectangle2D.Float(SX, SY, SW, SH));

		float dy = -40 * Ui.easeIn(u);
		LocalDateTime now = LocalDateTime.now();
		String date = now.format(Lang.format("handyphone.format.date.long"));
		date = Character.toUpperCase(date.charAt(0)) + date.substring(1);
		g.setFont(font(Font.BOLD, 9.5f));
		shadowText(g, date, FACE_W / 2f, SY + 96 + dy, 0.8f);
		g.setFont(font(Font.BOLD, 48f));
		shadowText(g, now.format(PhoneOS.timeFormat()), FACE_W / 2f, SY + 142 + dy, 1.8f);

		float pulse = 0.55f + 0.45f * (float) Math.sin(os.time * 2.4);
		g.setComposite(AlphaComposite.SrcOver.derive(alpha * (0.5f + 0.45f * pulse)));
		g.setFont(font(Font.BOLD, 8.6f));
		shadowText(g, Lang.t("handyphone.lock.hint", os.useKeyName()), FACE_W / 2f, SY + SH - 22, 0.8f);
		g.setComposite(old);
	}

	/** Texto branco centralizado com sombra dura, marca do visual da época. */
	private static void shadowText(Graphics2D g, String s, float cx, float baseline, float offset) {
		g.setColor(new Color(0, 0, 0, 150));
		centered(g, s, cx + offset, baseline + offset);
		g.setColor(Color.WHITE);
		centered(g, s, cx, baseline);
	}

	private void drawHome(Graphics2D g, PhoneOS os, float u) {
		Composite old = g.getComposite();
		float sideIn = stagger(u, 0.05f), barIn = stagger(u, 0.15f), cardIn = stagger(u, 0.3f);

		drawWidget(g, os, cardIn);

		// Indicador de páginas.
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(cardIn)));
		float dotsX = SX + SIDE_W + (SW - SIDE_W) / 2f;
		for (int i = 0; i < 3; i++) {
			g.setColor(i == 0 ? new Color(255, 255, 255, 235) : new Color(90, 40, 0, 150));
			g.fill(new Rectangle2D.Float(dotsX - 9 + i * 7, BAR_Y - 11, 4, 4));
		}
		g.setComposite(old);

		drawSidebar(g, os, sideIn);
		drawBottomBar(g, os, barIn);
	}

	private void drawWidget(Graphics2D g, PhoneOS os, float in) {
		if (in <= 0) {
			return;
		}
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(clamp01(in)));
		float x = SX + SIDE_W + 9, y = SY + STATUS_H + 10 + (1 - in) * 12, w = SW - SIDE_W - 17, h = 96, head = 17;

		g.setColor(new Color(60, 25, 0, 120));
		g.fill(new RoundRectangle2D.Float(x + 2, y + 2.6f, w, h, 6, 6));
		RoundRectangle2D card = new RoundRectangle2D.Float(x, y, w, h, 6, 6);
		g.setPaint(new GradientPaint(0, y, Color.WHITE, 0, y + h, new Color(0xE9EBEF)));
		g.fill(card);

		// Cabeçalho azul brilhante.
		Shape oldClip = g.getClip();
		g.clip(card);
		g.setPaint(new GradientPaint(0, y, BLUE_1, 0, y + head, BLUE_2));
		g.fill(new Rectangle2D.Float(x, y, w, head));
		g.setColor(new Color(255, 255, 255, 60));
		g.fill(new Rectangle2D.Float(x, y, w, head / 2f));
		g.setClip(oldClip);
		g.setStroke(new BasicStroke(0.9f));
		g.setColor(new Color(0x5A5E68));
		g.draw(card);

		g.setFont(font(Font.BOLD, 9.6f));
		g.setColor(new Color(0, 0, 0, 110));
		Ui.text(g, Lang.t("handyphone.time.now"), x + 6.6f, y + 12.4f);
		g.setColor(Color.WHITE);
		Ui.text(g, Lang.t("handyphone.time.now"), x + 6, y + 11.8f);

		LocalDateTime now = LocalDateTime.now();
		g.setColor(INK);
		g.setFont(font(Font.BOLD, 28f));
		Ui.text(g, now.format(PhoneOS.timeFormat()), x + 7, y + 45);
		g.setColor(new Color(0x55585F));
		g.setFont(font(Font.PLAIN, 8.2f));
		Ui.text(g, now.format(Lang.format("handyphone.format.date.short")), x + 8, y + 57);

		// Contadores (mensagens e chamadas); ganham números de verdade nas próximas fases.
		String[] ids = {"messages", "phone"};
		float bw = (w - 16) / 2f, by = y + h - 24;
		for (int i = 0; i < ids.length; i++) {
			float bx = x + 6 + i * (bw + 4);
			RoundRectangle2D button = new RoundRectangle2D.Float(bx, by, bw, 17, 5, 5);
			g.setPaint(new GradientPaint(0, by, Color.WHITE, 0, by + 17, new Color(0xCFD3DA)));
			g.fill(button);
			g.setStroke(new BasicStroke(0.8f));
			g.setColor(new Color(0x8D919B));
			g.draw(button);
			AffineTransform t = g.getTransform();
			g.translate(bx + 7, by + 2);
			g.scale(13 / 34f, 13 / 34f);
			Ui.glyph(g, ids[i], BLUE_2, Color.WHITE);
			g.setTransform(t);
			g.setColor(INK);
			g.setFont(font(Font.BOLD, 9.5f));
			// Mensagens não lidas; as chamadas ganham número de verdade na fase das ligações.
			String count = i == 0 ? Integer.toString(Math.min(99, os.unreadTotal())) : "0";
			Ui.text(g, count, bx + bw - 9 - Ui.textWidth(g, count), by + 12.4f);
		}
		g.setComposite(old);
	}

	private void drawSidebar(Graphics2D g, PhoneOS os, float in) {
		if (in <= 0) {
			return;
		}
		AffineTransform base = g.getTransform();
		g.translate(-(1 - in) * (SIDE_W + 10), 0);
		float top = SY + STATUS_H, h = BAR_Y - top;

		// Puxador da barra.
		float tabY = top + h / 2f - 15;
		RoundRectangle2D tab = new RoundRectangle2D.Float(SX + SIDE_W - 5, tabY, 14, 30, 8, 8);
		g.setPaint(new GradientPaint(SX + SIDE_W, 0, new Color(0xC2C6CC), SX + SIDE_W + 9, 0, new Color(0x8C9199)));
		g.fill(tab);
		g.setStroke(new BasicStroke(0.8f));
		g.setColor(new Color(0x5C6068));
		g.draw(tab);
		Path2D arrow = new Path2D.Float();
		arrow.moveTo(SX + SIDE_W + 2, tabY + 15);
		arrow.lineTo(SX + SIDE_W + 6, tabY + 11);
		arrow.lineTo(SX + SIDE_W + 6, tabY + 19);
		arrow.closePath();
		g.setColor(new Color(0x3C3F46));
		g.fill(arrow);

		// Corpo metálico.
		g.setPaint(new GradientPaint(SX, 0, new Color(0xE4E6EA), SX + SIDE_W, 0, new Color(0xA4A8B0)));
		g.fill(new Rectangle2D.Float(SX, top, SIDE_W, h));
		g.setColor(new Color(255, 255, 255, 150));
		g.fill(new Rectangle2D.Float(SX, top, 1.2f, h));
		g.setColor(new Color(0x5C6068));
		g.fill(new Rectangle2D.Float(SX + SIDE_W - 0.9f, top, 0.9f, h));

		for (int i = 0; i < os.apps.size(); i++) {
			App app = os.apps.get(i);
			if (app.dock()) {
				continue;
			}
			float[] c = iconCenter(os, i);
			float pressed = os.press[i].f();
			AffineTransform t = g.getTransform();
			g.translate(c[0], c[1]);
			g.scale(1f - 0.1f * pressed, 1f - 0.1f * pressed);
			g.translate(-SIDE_ICON / 2f, -SIDE_ICON / 2f);
			drawGlossyIcon(g, app, SIDE_ICON, 1f - 0.2f * pressed);
			g.setTransform(t);

			g.setFont(fitted(g, app.name(), font(Font.BOLD, 6.6f), SIDE_W - 2));
			g.setColor(new Color(255, 255, 255, 170));
			centered(g, app.name(), c[0], c[1] + SIDE_ICON / 2f + 9.6f);
			g.setColor(new Color(0x2A2C33));
			centered(g, app.name(), c[0], c[1] + SIDE_ICON / 2f + 9f);
		}
		g.setTransform(base);
	}

	private void drawBottomBar(Graphics2D g, PhoneOS os, float in) {
		if (in <= 0) {
			return;
		}
		AffineTransform base = g.getTransform();
		g.translate(0, (1 - in) * BAR_H);
		g.setPaint(new GradientPaint(0, BAR_Y, new Color(0x4A4A4E), 0, BAR_Y + BAR_H, new Color(0x08080A)));
		g.fill(new Rectangle2D.Float(SX, BAR_Y, SW, BAR_H));
		g.setColor(new Color(255, 255, 255, 26));
		g.fill(new Rectangle2D.Float(SX, BAR_Y, SW, BAR_H / 2f));
		g.setColor(new Color(255, 255, 255, 90));
		g.fill(new Rectangle2D.Float(SX, BAR_Y, SW, 0.8f));

		float w = SW / 4f;
		for (int i = 0; i < os.apps.size(); i++) {
			App app = os.apps.get(i);
			if (!app.dock()) {
				continue;
			}
			int slot = slot(os, i);
			float bx = SX + slot * w, pressed = clamp01(os.press[i].f());
			if (pressed > 0.01f) {
				g.setColor(new Color(255, 190, 60, Math.round(110 * pressed)));
				g.fill(new Rectangle2D.Float(bx, BAR_Y, w, BAR_H));
			}
			if (slot > 0) {
				g.setColor(new Color(0, 0, 0, 170));
				g.fill(new Rectangle2D.Float(bx - 0.5f, BAR_Y + 5, 0.9f, BAR_H - 10));
				g.setColor(new Color(255, 255, 255, 45));
				g.fill(new Rectangle2D.Float(bx + 0.4f, BAR_Y + 5, 0.7f, BAR_H - 10));
			}
			g.setFont(fitted(g, app.name(), font(Font.BOLD, 7.2f), w - 3));
			g.setColor(Color.WHITE);
			centered(g, app.name(), bx + w / 2f, BAR_Y + 12.5f);

			AffineTransform t = g.getTransform();
			g.translate(bx + w / 2f - DOCK_ICON / 2f, BAR_Y + 19);
			g.scale(DOCK_ICON / 34f, DOCK_ICON / 34f);
			Ui.glyph(g, app.id(), Color.WHITE, new Color(0x1C1C20));
			g.setTransform(t);
		}
		g.setTransform(base);
	}

	/** Ícone de plástico brilhante: contorno escuro, reflexo na metade de cima e sombra dura. */
	private void drawGlossyIcon(Graphics2D g, App app, float size, float brightness) {
		Color c1 = shade(app.c1(), brightness), c2 = shade(app.c2(), brightness);
		float r = size * 0.42f;
		g.setColor(new Color(0, 0, 0, 105));
		g.fill(new RoundRectangle2D.Float(1.4f, 2.2f, size, size, r, r));
		RoundRectangle2D body = new RoundRectangle2D.Float(0, 0, size, size, r, r);
		g.setPaint(new GradientPaint(0, 0, c1, 0, size, c2));
		g.fill(body);

		AffineTransform t = g.getTransform();
		g.scale(size / 34f, size / 34f);
		g.translate(0.7f, 0.9f);
		Ui.glyph(g, app.id(), alpha(shade(c2, 0.45f), 170), alpha(shade(c2, 0.45f), 0));
		g.translate(-0.7f, -0.9f);
		Ui.glyph(g, app.id(), Color.WHITE, app.id().equals("camera") ? shade(c2, 0.5f) : c2);
		g.setTransform(t);

		// Reflexo vítreo.
		Shape oldClip = g.getClip();
		g.clip(body);
		g.setPaint(new GradientPaint(0, 0, new Color(255, 255, 255, 150), 0, size * 0.5f, new Color(255, 255, 255, 30)));
		g.fill(new Ellipse2D.Float(-size * 0.3f, -size * 0.55f, size * 1.6f, size));
		g.setClip(oldClip);

		g.setStroke(new BasicStroke(1.1f));
		g.setColor(shade(c2, 0.45f));
		g.draw(body);
	}

	private void drawApp(Graphics2D g, PhoneOS os, App app, float a) {
		// A janela entra deslizando pela direita.
		AffineTransform base = g.getTransform();
		g.translate((1 - Ui.easeOut(a)) * SW, 0);
		float top = SY + STATUS_H;
		g.setColor(new Color(0xF1F1F4));
		g.fill(new Rectangle2D.Float(SX, top, SW, SH - STATUS_H));

		// Barra de título.
		g.setPaint(new GradientPaint(0, top, BLUE_1, 0, top + TITLE_H, BLUE_2));
		g.fill(new Rectangle2D.Float(SX, top, SW, TITLE_H));
		g.setColor(new Color(255, 255, 255, 55));
		g.fill(new Rectangle2D.Float(SX, top, SW, TITLE_H / 2f));
		g.setColor(new Color(0x12306B));
		g.fill(new Rectangle2D.Float(SX, top + TITLE_H - 0.9f, SW, 0.9f));
		AffineTransform t = g.getTransform();
		g.translate(SX + 5, top + 4);
		drawGlossyIcon(g, app, 16, 1f);
		g.setTransform(t);
		g.setFont(font(Font.BOLD, 11.5f));
		g.setColor(new Color(0, 0, 0, 120));
		Ui.text(g, app.name(), SX + 27.7f, top + 16.4f);
		g.setColor(Color.WHITE);
		Ui.text(g, app.name(), SX + 27, top + 15.7f);

		float[] body = appBody();
		if (!os.drawApp(g, app, body, accent())) {
			float cx = body[0] + body[2] / 2f;
			t = g.getTransform();
			g.translate(cx - 28, body[1] + 44);
			drawGlossyIcon(g, app, 56, 1f);
			g.setTransform(t);
			g.setColor(INK);
			g.setFont(font(Font.BOLD, 13f));
			centered(g, Lang.t("handyphone.soon.title"), cx, body[1] + 128);
			g.setColor(new Color(0x60636C));
			g.setFont(font(Font.PLAIN, 9.2f));
			centered(g, Lang.t("handyphone.soon.line1"), cx, body[1] + 145);
			centered(g, Lang.t("handyphone.soon.line2"), cx, body[1] + 157);
		}

		// Barra de teclas da tela: "Voltar" à direita.
		float sy = SY + SH - SOFTKEY_H;
		g.setPaint(new GradientPaint(0, sy, new Color(0x4A4A4E), 0, sy + SOFTKEY_H, new Color(0x08080A)));
		g.fill(new Rectangle2D.Float(SX, sy, SW, SOFTKEY_H));
		g.setColor(new Color(255, 255, 255, 26));
		g.fill(new Rectangle2D.Float(SX, sy, SW, SOFTKEY_H / 2f));
		float p = clamp01(os.homeBarPress.f());
		if (p > 0.01f) {
			g.setColor(new Color(255, 190, 60, Math.round(110 * p)));
			g.fill(new Rectangle2D.Float(SX + SW / 2f, sy, SW / 2f, SOFTKEY_H));
		}
		g.setColor(new Color(0, 0, 0, 170));
		g.fill(new Rectangle2D.Float(SX + SW / 2f - 0.5f, sy + 4, 0.9f, SOFTKEY_H - 8));
		g.setFont(font(Font.BOLD, 8.6f));
		g.setColor(Color.WHITE);
		centered(g, Lang.t("handyphone.back"), SX + SW * 0.75f, sy + 14.2f);
		g.setTransform(base);
	}
}
