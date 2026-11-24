package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.migzu.handyphone.client.os.PhoneClock;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.os.PhoneSettings;

/**
 * O destaque da roda (celular na mão) desenhado no vidro, como a cruz do cursor: uma borda na cor de destaque em volta do item,
 * com brilho suave. No estilo fluido ela ondula e o brilho gira devagar; no calmo, menos; no simples, fica parada. Ao trocar de
 * item, o destaque novo nasce de dentro do item para fora e o antigo se abre e some (sem escorregar de um para o outro).
 * <p>
 * Tudo em pontos da face do celular (FACE_W x FACE_H, y para baixo); a mão converte para o modelo. Nada aqui redesenha a tela:
 * a imagem da tela só muda quando o item muda de lugar.
 */
final class PhoneFocusRing {
	/** Folga entre o item e a borda, espessura da borda e largura do brilho por fora (pontos). */
	private static final float GAP = 2.2f, CORE = 1.35f, GLOW_OUT = 3.6f;
	private static final int SAMPLES = 120;
	/** Tempo do destaque novo crescer e do antigo sumir (s). */
	private static final double GROW_S = 0.26, FADE_S = 0.2;

	private static PhoneOS.FocusShape current, previous;
	private static double changedAt = -10;

	private PhoneFocusRing() {
	}

	/** Esquece o destaque (o celular saiu da mão): ao voltar, o primeiro destaque aparece sem animação de troca. */
	static void reset() {
		current = null;
		previous = null;
		changedAt = -10;
	}

	/**
	 * Monta a geometria do destaque, ou nada (sem item destacado). {@code hw} = meia largura do aparelho no modelo; {@code z} =
	 * altura sobre o vidro.
	 */
	static void submit(PoseStack.Pose pose, VertexConsumer buffer, PhoneOS.FocusShape shape, PhoneSettings settings, float hw, float z,
		int light) {
		// Relógio do celular (o de verdade; na gravação das cenas, o virtual). PhoneClock.seconds() é sempre 0 fora da gravação.
		double now = PhoneClock.millis() / 1000.0;
		boolean animated = settings.highlightAnimated();
		if (shape == null) {
			reset();
			return;
		}
		if (current == null) {
			current = shape;
			previous = null;
			changedAt = -10;
		} else if (!current.key().equals(shape.key())) {
			previous = current;
			current = shape;
			changedAt = now;
		} else {
			current = shape;
		}
		PhoneSettings.Highlight style = settings.highlight();
		double since = animated ? now - changedAt : 10;
		float grow = (float) Math.min(1, since / GROW_S);
		float fade = (float) Math.min(1, since / FADE_S);
		float ease = 1 - (1 - grow) * (1 - grow) * (1 - grow);
		float t = animated ? (float) (now % 3600.0) : 0f;
		if (previous != null && fade < 1f) {
			ring(pose, buffer, previous, 1f + 0.12f * fade, 1f - fade, style, t, hw, z, light);
		}
		ring(pose, buffer, current, 0.45f + 0.55f * ease, ease, style, t, hw, z, light);
	}

	/** Uma borda: escala em torno do centro do item e transparência (para a troca). */
	private static void ring(PoseStack.Pose pose, VertexConsumer buffer, PhoneOS.FocusShape s, float scale, float alpha,
		PhoneSettings.Highlight style, float t, float hw, float z, int light) {
		if (alpha <= 0.01f) {
			return;
		}
		float cx = s.x() + s.w() / 2f, cy = s.y() + s.h() / 2f;
		float w = (s.w() + 2 * GAP) * scale, h = (s.h() + 2 * GAP) * scale, r = Math.min((s.arc() + GAP) * scale, Math.min(w, h) / 2f);
		float x0 = cx - w / 2f, y0 = cy - h / 2f;
		float amp = switch (style) {
			case FLUID -> 1.0f;
			case CALM -> 0.45f;
			case SIMPLE -> 0f;
		};
		float speed = style == PhoneSettings.Highlight.CALM ? 0.55f : 1f;
		int rgb = s.color();
		int light2 = mix(rgb, 0xFFFFFF, 0.55f);

		float[] px = new float[SAMPLES], py = new float[SAMPLES], nx = new float[SAMPLES], ny = new float[SAMPLES];
		perimeter(x0, y0, w, h, r, px, py, nx, ny);
		for (int i = 0; i < SAMPLES; i++) {
			int j = (i + 1) % SAMPLES;
			float ui = i / (float) SAMPLES, uj = j / (float) SAMPLES;
			float wi = wave(ui, t * speed) * amp, wj = wave(uj, t * speed) * amp;
			float wiIn = wave(ui, t * speed + 2.1f) * amp * 0.45f, wjIn = wave(uj, t * speed + 2.1f) * amp * 0.45f;
			int ci = shade(rgb, light2, px[i] - cx, py[i] - cy, t, speed, style), cj = shade(rgb, light2, px[j] - cx, py[j] - cy, t, speed, style);
			// Distâncias a partir da linha média: dentro (negativo) e fora (positivo).
			float inI = -CORE / 2f - wiIn, inJ = -CORE / 2f - wjIn;
			float outI = CORE / 2f + wi, outJ = CORE / 2f + wj;
			// A borda e o brilho por fora (some para longe). Sem brilho por dentro: o vão entre o item e a borda fica limpo. O brilho é
			// um tom CLARO da cor: a cor pura semitransparente sobre um fundo de cor oposta (laranja sobre o azul do Aero) vira cinza
			// e parece um contorno sujo; o tom claro só clareia o fundo.
			band(pose, buffer, s, i, j, px, py, nx, ny, inI, inJ, outI, outJ, ci, cj, alpha, alpha, hw, z, light);
			band(pose, buffer, s, i, j, px, py, nx, ny, outI, outJ, outI + GLOW_OUT, outJ + GLOW_OUT, mix(ci, 0xFFFFFF, 0.6f),
				mix(cj, 0xFFFFFF, 0.6f), alpha * 0.3f, 0f, hw, z, light);
		}
	}

	/** Faixa entre duas distâncias da linha média, do ponto i ao j; a transparência vai de {@code a0} (lado d0) a {@code a1}. */
	private static void band(PoseStack.Pose pose, VertexConsumer buffer, PhoneOS.FocusShape s, int i, int j, float[] px, float[] py,
		float[] nx, float[] ny, float d0i, float d0j, float d1i, float d1j, int ci, int cj, float a0, float a1, float hw, float z, int light) {
		// As duas faces (a ordem dos vértices decide qual lado é a frente): assim nada some por causa do recorte de faces.
		vertex(pose, buffer, s, px[i] + nx[i] * d0i, py[i] + ny[i] * d0i, ci, a0, hw, z, light);
		vertex(pose, buffer, s, px[j] + nx[j] * d0j, py[j] + ny[j] * d0j, cj, a0, hw, z, light);
		vertex(pose, buffer, s, px[j] + nx[j] * d1j, py[j] + ny[j] * d1j, cj, a1, hw, z, light);
		vertex(pose, buffer, s, px[i] + nx[i] * d1i, py[i] + ny[i] * d1i, ci, a1, hw, z, light);
		vertex(pose, buffer, s, px[i] + nx[i] * d1i, py[i] + ny[i] * d1i, ci, a1, hw, z, light);
		vertex(pose, buffer, s, px[j] + nx[j] * d1j, py[j] + ny[j] * d1j, cj, a1, hw, z, light);
		vertex(pose, buffer, s, px[j] + nx[j] * d0j, py[j] + ny[j] * d0j, cj, a0, hw, z, light);
		vertex(pose, buffer, s, px[i] + nx[i] * d0i, py[i] + ny[i] * d0i, ci, a0, hw, z, light);
	}

	/** Um vértice; o que passa da tela é empurrado para a borda dela (não vaza para a moldura). */
	private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, PhoneOS.FocusShape s, float x, float y, int rgb, float a,
		float hw, float z, int light) {
		float[] c = s.clip();
		x = Math.max(c[0], Math.min(c[0] + c[2], x));
		y = Math.max(c[1], Math.min(c[1] + c[3], y));
		float unit = 1f / PhoneOS.FACE_H;
		int alpha = Math.max(0, Math.min(255, Math.round(a * 255f)));
		buffer.addVertex(pose, x * unit - hw, 0.5f - y * unit, z).setColor((alpha << 24) | rgb).setUv(0.5f, 0.5f).setLight(light);
	}

	/** Ondulação ao longo da borda (u de 0 a 1), só para fora: soma de senos que andam em sentidos e velocidades diferentes. */
	private static float wave(float u, float t) {
		double a = u * Math.PI * 2;
		double v = Math.sin(a * 3 + t * 2.1) * 0.55 + Math.sin(a * 5 - t * 3.3) * 0.3 + Math.sin(a * 2 + t * 1.3) * 0.35;
		return (float) Math.max(0, v);
	}

	/** Cor de um ponto: o brilho (mais claro) gira devagar em volta do item; no simples, a cor pura. */
	private static int shade(int rgb, int light, float dx, float dy, float t, float speed, PhoneSettings.Highlight style) {
		if (style == PhoneSettings.Highlight.SIMPLE) {
			return rgb;
		}
		double angle = Math.atan2(dy, dx) - t * 1.4 * speed;
		float k = (float) (0.5 + 0.5 * Math.cos(angle * 2));
		return mix(rgb, light, k * 0.8f);
	}

	private static int mix(int a, int b, float k) {
		int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
		int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
		int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
		return (r << 16) | (g << 8) | bl;
	}

	/** Pontos ao longo de um retângulo arredondado (sentido horário na tela), cada um com a normal para fora. */
	private static void perimeter(float x, float y, float w, float h, float r, float[] px, float[] py, float[] nx, float[] ny) {
		float sw = Math.max(0, w - 2 * r), sh = Math.max(0, h - 2 * r), arc = (float) (Math.PI * r / 2);
		float[] seg = {sw, arc, sh, arc, sw, arc, sh, arc};
		float total = 2 * sw + 2 * sh + 4 * arc;
		for (int i = 0; i < SAMPLES; i++) {
			float d = i / (float) SAMPLES * total;
			int k = 0;
			while (k < 7 && d > seg[k]) {
				d -= seg[k];
				k++;
			}
			float a = r > 0 ? d / r : 0;
			float cxn, cyn;
			switch (k) {
				case 0 -> { px[i] = x + r + d; py[i] = y; nx[i] = 0; ny[i] = -1; }
				case 1 -> { cxn = (float) Math.sin(a); cyn = (float) -Math.cos(a); nx[i] = cxn; ny[i] = cyn; px[i] = x + w - r + cxn * r; py[i] = y + r + cyn * r; }
				case 2 -> { px[i] = x + w; py[i] = y + r + d; nx[i] = 1; ny[i] = 0; }
				case 3 -> { cxn = (float) Math.cos(a); cyn = (float) Math.sin(a); nx[i] = cxn; ny[i] = cyn; px[i] = x + w - r + cxn * r; py[i] = y + h - r + cyn * r; }
				case 4 -> { px[i] = x + w - r - d; py[i] = y + h; nx[i] = 0; ny[i] = 1; }
				case 5 -> { cxn = (float) -Math.sin(a); cyn = (float) Math.cos(a); nx[i] = cxn; ny[i] = cyn; px[i] = x + r + cxn * r; py[i] = y + h - r + cyn * r; }
				case 6 -> { px[i] = x; py[i] = y + h - r - d; nx[i] = -1; ny[i] = 0; }
				default -> { cxn = (float) -Math.cos(a); cyn = (float) -Math.sin(a); nx[i] = cxn; ny[i] = cyn; px[i] = x + r + cxn * r; py[i] = y + r + cyn * r; }
			}
		}
	}
}
