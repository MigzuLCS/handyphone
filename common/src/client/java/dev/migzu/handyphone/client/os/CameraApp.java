package dev.migzu.handyphone.client.os;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
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
	void opened(PhoneOS os) {
		// Como num celular: cada vez que a Câmera abre, volta à traseira e sem zoom (grade e temporizador ficam como estavam).
		os.cameraZoom = 1f;
		os.cameraSelfie = false;
	}

	/** Zoom pela roda (expandido): cada clique é um degrau, sem deslizar. */
	@Override
	boolean smoothScroll() {
		return false;
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		if (!os.cameraSelfie) {
			zoomBy(os, (float) Math.pow(ZOOM_STEP, amount));
		}
	}

	/** Cada degrau do zoom multiplica o campo por isto (de 1x a {@link PhoneOS#CAMERA_MAX_ZOOM} em 6 degraus). */
	static final float ZOOM_STEP = 1.26f;

	static void zoomBy(PhoneOS os, float factor) {
		float z = os.cameraZoom * factor;
		if (z < 1.04f) {
			z = 1f;
		}
		os.cameraZoom = Math.min(PhoneOS.CAMERA_MAX_ZOOM, z);
		os.markDirty();
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		PhoneSettings settings = os.settings();
		g.setColor(new Color(0x14141A));
		g.fill(new Rectangle2D.Float(x, y, w, h));

		// Faixa de cima: grade e temporizador.
		float barY = y + 4, barH = 20;
		boolean grid = settings.cameraGrid();
		boolean gridDown = hit("grid", x + MARGIN, barY, 30, barH, PhoneSound.SELECT,
			() -> os.changeSettings(st -> st.setCameraGrid(!st.cameraGrid())));
		drawGridIcon(g, x + MARGIN + 15, barY + barH / 2f, grid, gridDown);
		int timer = settings.cameraTimer();
		float tw = 40;
		boolean timerDown = hit("timer", x + w - MARGIN - tw, barY, tw, barH, PhoneSound.SELECT,
			() -> os.changeSettings(st -> st.setCameraTimer(nextTimer(st.cameraTimer()))));
		drawTimerButton(g, x + w - MARGIN - tw, barY, tw, barH, timer, timerDown);

		// Visor na mesma proporção da foto (4:3), então o que se vê é o que fica.
		float vw = w - 2 * MARGIN, vh = vw * PhoneCamera.ASPECT_H / PhoneCamera.ASPECT_W;
		float vx = x + MARGIN, vy = barY + barH + 4;
		BufferedImage preview = os.cameraPreview();
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(vx, vy, vw, vh, 6, 6));
		if (preview != null && os.cameraSelfie) {
			// Frontal: o visor é um espelho, como em qualquer celular; a foto salva sai sem espelhar (vem do quadro do jogo).
			java.awt.geom.AffineTransform t = g.getTransform();
			g.translate(2 * vx + vw, 0);
			g.scale(-1, 1);
			drawCover(g, preview, vx, vy, vw, vh, 6);
			g.setTransform(t);
		} else if (preview != null) {
			drawCover(g, preview, vx, vy, vw, vh, 6);
		} else {
			g.setColor(new Color(0x9A9CA8));
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.centered(g, Lang.t("handyphone.camera.preparing"), vx + vw / 2f, vy + vh / 2f + 3);
		}
		if (grid) {
			drawThirds(g, vx, vy, vw, vh);
		} else {
			drawFrameMarks(g, vx, vy, vw, vh);
		}
		if (!os.cameraSelfie) {
			drawZoom(g, os, vx + vw / 2f, vy + vh - 13);
		}
		int countdown = os.cameraCountdown();
		if (countdown > 0) {
			drawCountdown(g, countdown, os.cameraCountdownPhase(), vx + vw / 2f, vy + vh / 2f);
		}

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

		// Última foto da galeria, no canto: toque abre a Galeria. Logo depois de um disparo, a foto tirada ocupa o lugar até a de verdade
		// chegar do servidor (a galeria muda) ou por alguns segundos.
		float tx = x + MARGIN + 6, ts = 30, ty = shutterY - ts / 2f;
		double sinceShot = os.time - os.flashAt;
		BufferedImage pending = os.shotFrame != null && sinceShot < PENDING_THUMB_SECONDS
			&& (data.gallery.isEmpty() ? -1 : data.gallery.getFirst().id()) == os.shotGalleryTop ? os.shotFrame : null;
		if (!data.gallery.isEmpty() || pending != null) {
			boolean down = hit("last", tx, ty, ts, ts, PhoneSound.ICON_TAP, () -> os.launch("gallery"));
			BufferedImage thumb = pending;
			if (thumb == null) {
				PhoneData.GalleryPhoto last = data.gallery.getFirst();
				thumb = data.photos.thumb(last.id(), os.backend, os.time);
			}
			boolean flying = pending != null && sinceShot < PhoneOS.SHOT_ANIMATION;
			if (!flying) {
				if (thumb != null) {
					drawCover(g, thumb, tx, ty, ts, ts, 6);
				} else {
					g.setColor(new Color(0x3A3C48));
					g.fill(new RoundRectangle2D.Float(tx, ty, ts, ts, 6, 6));
				}
			}
			g.setColor(down ? Color.WHITE : new Color(0xE0E2EA));
			g.setStroke(new BasicStroke(1f));
			g.draw(new RoundRectangle2D.Float(tx, ty, ts, ts, 6, 6));
		}

		// Virar a câmera (traseira / frontal), do outro lado do disparador.
		float fx = x + w - MARGIN - 6 - ts / 2f;
		boolean flipDown = hit("flip", fx - ts / 2f, shutterY - ts / 2f, ts, ts, PhoneSound.BUTTON, () -> {
			os.cameraSelfie = !os.cameraSelfie;
			os.markDirty();
		});
		drawFlip(g, fx, shutterY, ts, os.cameraSelfie, flipDown);

		drawShot(g, os, vx, vy, vw, vh, tx, ty, ts);
	}

	/** Quanto tempo a foto tirada fica na miniatura esperando a de verdade chegar do servidor. */
	private static final double PENDING_THUMB_SECONDS = 6;

	/**
	 * Disparo: a cortina do obturador (o visor escurece num piscar e volta) e a foto tirada encolhendo do visor até a miniatura da
	 * galeria, com borda branca, como num celular.
	 */
	private static void drawShot(Graphics2D g, PhoneOS os, float vx, float vy, float vw, float vh, float tx, float ty, float ts) {
		double t = os.time - os.flashAt;
		if (t < 0 || t > PhoneOS.SHOT_ANIMATION) {
			return;
		}
		// Cortina: fecha em 45 ms, fica 35 ms, abre em 120 ms.
		float dark = t < 0.045 ? (float) (t / 0.045) : t < 0.08 ? 1f : (float) Math.max(0, 1 - (t - 0.08) / 0.12);
		if (dark > 0) {
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.SrcOver.derive(0.92f * dark));
			g.setColor(Color.BLACK);
			g.fill(new RoundRectangle2D.Float(vx, vy, vw, vh, 6, 6));
			g.setComposite(old);
		}
		BufferedImage frame = os.shotFrame;
		if (frame == null || t < 0.08) {
			return;
		}
		// Voo até a miniatura: sai do tamanho do visor e desacelera ao chegar (a miniatura é quadrada: o recorte vai fechando junto).
		float p = (float) Math.min(1, (t - 0.08) / (PhoneOS.SHOT_ANIMATION - 0.08));
		float e = 1 - (1 - p) * (1 - p) * (1 - p);
		float w = vw + (ts - vw) * e, h = vh + (ts - vh) * e;
		float x = vx + (tx - vx) * e, y = vy + (ty - vy) * e;
		float arc = 6;
		drawCover(g, frame, x, y, w, h, arc);
		g.setColor(new Color(255, 255, 255, 230));
		g.setStroke(new BasicStroke(1.6f - 0.6f * e));
		g.draw(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
	}

	static int nextTimer(int current) {
		int[] t = PhoneSettings.CAMERA_TIMERS;
		for (int i = 0; i < t.length; i++) {
			if (t[i] == current) {
				return t[(i + 1) % t.length];
			}
		}
		return 0;
	}

	private static final Color CONTROL = new Color(0xE8E9EF), CONTROL_OFF = new Color(0x6E707C), ACTIVE = new Color(0xFFD43B);

	/** Ícone da grade: um quadrado dividido em terços; amarelo quando ligada. */
	private static void drawGridIcon(Graphics2D g, float cx, float cy, boolean on, boolean down) {
		Color c = on ? ACTIVE : CONTROL;
		if (down) {
			c = Ui.shade(c, 0.75f);
		}
		g.setColor(c);
		g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float s = 11, l = cx - s / 2f, t = cy - s / 2f;
		g.draw(new RoundRectangle2D.Float(l, t, s, s, 2.5f, 2.5f));
		for (int i = 1; i < 3; i++) {
			g.draw(new Line2D.Float(l + s * i / 3f, t, l + s * i / 3f, t + s));
			g.draw(new Line2D.Float(l, t + s * i / 3f, l + s, t + s * i / 3f));
		}
	}

	/** Botão do temporizador: cronômetro e, ligado, os segundos em amarelo. */
	private static void drawTimerButton(Graphics2D g, float x, float y, float w, float h, int seconds, boolean down) {
		boolean on = seconds > 0;
		Color c = on ? ACTIVE : CONTROL;
		if (down) {
			c = Ui.shade(c, 0.75f);
		}
		float cx = on ? x + 11 : x + w - 15, cy = y + h / 2f + 0.5f, r = 5.2f;
		g.setColor(c);
		g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
		g.draw(new Line2D.Float(cx - 1.6f, cy - r - 1.6f, cx + 1.6f, cy - r - 1.6f));
		g.draw(new Line2D.Float(cx, cy, cx, cy - r + 1.8f));
		g.draw(new Line2D.Float(cx, cy, cx + 2.4f, cy + 1.2f));
		if (on) {
			g.setFont(Ui.font(Font.BOLD, 7.6f));
			Ui.text(g, Lang.t("handyphone.camera.timer.seconds", String.valueOf(seconds)), cx + r + 4, cy + 2.7f);
		}
	}

	/** Zoom no pé do visor: "−  1,5x  +" numa pílula escura (os botões dão o degrau também no modo "na mão"). */
	private void drawZoom(Graphics2D g, PhoneOS os, float cx, float cy) {
		float pw = 62, ph = 16, l = cx - pw / 2f, t = cy - ph / 2f;
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
		g.setColor(Color.BLACK);
		g.fill(new RoundRectangle2D.Float(l, t, pw, ph, ph, ph));
		g.setComposite(old);
		boolean minusDown = hit("zoom:minus", l, t - 3, 20, ph + 6, PhoneSound.SELECT, () -> zoomBy(os, 1f / ZOOM_STEP));
		boolean plusDown = hit("zoom:plus", l + pw - 20, t - 3, 20, ph + 6, PhoneSound.SELECT, () -> zoomBy(os, ZOOM_STEP));
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float zoom = os.cameraZoom;
		g.setColor(minusDown || zoom <= 1f ? CONTROL_OFF : CONTROL);
		g.draw(new Line2D.Float(l + 7, cy, l + 13, cy));
		g.setColor(plusDown || zoom >= PhoneOS.CAMERA_MAX_ZOOM ? CONTROL_OFF : CONTROL);
		g.draw(new Line2D.Float(l + pw - 13, cy, l + pw - 7, cy));
		g.draw(new Line2D.Float(l + pw - 10, cy - 3, l + pw - 10, cy + 3));
		g.setColor(zoom > 1f ? ACTIVE : CONTROL);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		Ui.centered(g, Lang.t("handyphone.camera.zoom", Lang.decimal(zoom)), cx, cy + 2.6f);
	}

	/** Virar a câmera: duas setas em círculo dentro de um botão redondo; amarelas com a frontal em uso. */
	private static void drawFlip(Graphics2D g, float cx, float cy, float d, boolean selfie, boolean down) {
		g.setColor(down ? new Color(0x4A4C58) : new Color(0x30323C));
		g.fill(new Ellipse2D.Float(cx - d / 2f, cy - d / 2f, d, d));
		g.setColor(selfie ? ACTIVE : CONTROL);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float r = d * 0.24f;
		g.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 20, 150, Arc2D.OPEN));
		g.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 200, 150, Arc2D.OPEN));
		// Pontas das setas, no fim de cada arco (sentido anti-horário).
		arrowHead(g, cx, cy, r, 170);
		arrowHead(g, cx, cy, r, 350);
	}

	private static void arrowHead(Graphics2D g, float cx, float cy, float r, double deg) {
		double a = Math.toRadians(deg);
		float px = cx + (float) (Math.cos(a) * r), py = cy - (float) (Math.sin(a) * r);
		// Tangente do sentido anti-horário, em coordenadas de tela (y para baixo).
		float tx = (float) -Math.sin(a), ty = (float) -Math.cos(a);
		float nx = -ty, ny = tx, s = 2.6f;
		g.draw(new Line2D.Float(px, py, px - tx * s + nx * s * 0.8f, py - ty * s + ny * s * 0.8f));
		g.draw(new Line2D.Float(px, py, px - tx * s - nx * s * 0.8f, py - ty * s - ny * s * 0.8f));
	}

	/** Número grande da contagem no meio do visor: entra um pouco maior e assenta, com sombra para ler em céu claro. */
	private static void drawCountdown(Graphics2D g, int seconds, float phase, float cx, float cy) {
		float pop = Math.max(0f, 1f - phase / 0.25f);
		float size = 44f * (1f + 0.25f * pop * pop);
		String text = String.valueOf(seconds);
		g.setFont(Ui.font(Font.BOLD, size));
		float baseline = cy + size * 0.36f;
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.SrcOver.derive(0.35f));
		g.setColor(Color.BLACK);
		Ui.centered(g, text, cx + 1.2f, baseline + 1.6f);
		g.setComposite(AlphaComposite.SrcOver.derive(Math.min(1f, 0.55f + 0.45f * (1f - phase))));
		g.setColor(Color.WHITE);
		Ui.centered(g, text, cx, baseline);
		g.setComposite(old);
	}

	/** Grade de terços: só no visor, nunca na foto (a foto sai do quadro do jogo, não deste desenho). */
	private static void drawThirds(Graphics2D g, float x, float y, float w, float h) {
		g.setColor(new Color(255, 255, 255, 120));
		g.setStroke(new BasicStroke(0.7f));
		for (int i = 1; i < 3; i++) {
			g.draw(new Line2D.Float(x + w * i / 3f, y, x + w * i / 3f, y + h));
			g.draw(new Line2D.Float(x, y + h * i / 3f, x + w, y + h * i / 3f));
		}
	}

	private static String status(PhoneOS os) {
		PhoneData data = os.data;
		if (os.localOnly()) {
			return Lang.t(os.localPhotos.full() ? "handyphone.camera.status.local_full" : "handyphone.camera.status.local");
		}
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
		boolean down = hit("shutter", cx - 22, cy - 22, 44, 44, null, os::shutterPressed);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.2f));
		g.draw(new Ellipse2D.Float(cx - 19, cy - 19, 38, 38));
		if (os.cameraCountdown() > 0) {
			// Contando: o miolo vira um quadrado vermelho (tocar cancela), como num celular.
			g.setColor(down ? new Color(0xB8322B) : new Color(0xE5483F));
			float q = down ? 9 : 10;
			g.fill(new RoundRectangle2D.Float(cx - q, cy - q, q * 2, q * 2, 5, 5));
			return;
		}
		g.setColor(down ? new Color(0xC9CBD6) : Color.WHITE);
		float r = down ? 13 : 15;
		g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
	}
}
