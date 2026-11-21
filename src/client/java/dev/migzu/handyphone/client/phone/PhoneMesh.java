package dev.migzu.handyphone.client.phone;

import java.util.ArrayList;
import java.util.List;

/**
 * Malha 3D do celular, no estilo do jogo: tudo é feito de caixas (corpo, botões,
 * módulo das câmeras). É só geometria, sem classes do Minecraft, gerada uma vez.
 *
 * <p>Espaço local: a face da tela fica no plano z = 0, virada para +z, com altura 1
 * e largura {@link PhoneHandRenderer#ASPECT}; o corpo vai até z = -{@link #THICKNESS}.
 * Visto de frente, +x é a direita; visto por trás, +x é a esquerda.
 */
public final class PhoneMesh {
	/** Espessura do aparelho (a altura é 1). */
	public static final float THICKNESS = 0.075f;
	/** Quanto o corpo passa da face da tela em cada lado; também é o tamanho do chanfro das arestas. */
	public static final float RIM = 0.012f;
	/** Facetas retas em cada canto do corpo: poligonal, sem virar curva lisa. */
	private static final int CORNER_FACETS = 1;
	/** Floats por vértice: x, y, z, nx, ny, nz. */
	public static final int STRIDE = 6;

	private static final int BODY = 0x4B4F5C;
	private static final int BUTTON = 0x8E929E;
	private static final int PORT = 0x15161B;
	private static final int CAMERA_BASE = 0x5F6472;
	private static final int LENS_RING = 0x23252C;
	private static final int LENS_GLASS = 0x1B2A55;

	/** Um grupo de quads (4 vértices cada) de uma cor só. */
	public record Part(int color, float[] quads) {
	}

	public final List<Part> parts = new ArrayList<>();

	/** @param faceCornerRadius raio dos cantos da face da tela (a textura precisa usar o mesmo) */
	public PhoneMesh(float halfWidth, float faceCornerRadius) {
		float w = halfWidth + RIM, h = 0.5f + RIM, t = THICKNESS;
		body(w, h, faceCornerRadius + RIM);

		// Lateral direita: dois botões de volume. Lateral esquerda: botão de ligar.
		float bz0 = -t * 0.72f, bz1 = -t * 0.28f, out = 0.005f;
		box(BUTTON, w, 0.22f, bz0, w + out, 0.31f, bz1, 0f);
		box(BUTTON, w, 0.10f, bz0, w + out, 0.19f, bz1, 0f);
		box(BUTTON, -w - out, 0.12f, bz0, -w, 0.26f, bz1, 0f);

		// Embaixo: entrada do carregador e furos do alto-falante.
		float pz0 = -t * 0.66f, pz1 = -t * 0.34f, lip = 0.003f;
		box(PORT, -0.045f, -h - lip, pz0, 0.045f, -h, pz1, 0f);
		for (int i = 0; i < 3; i++) {
			float x = 0.085f + i * 0.034f;
			box(PORT, x, -h - lip, pz0, x + 0.018f, -h, pz1, 0f);
			box(PORT, -x - 0.018f, -h - lip, pz0, -x, -h, pz1, 0f);
		}

		// Traseira: módulo das câmeras no canto superior direito (visto por trás), com duas lentes em losango.
		float cx0 = -w + 0.04f, cx1 = cx0 + 0.17f, cy1 = h - 0.04f, cy0 = cy1 - 0.31f;
		float baseZ = -t - 0.014f;
		box(CAMERA_BASE, cx0, cy0, baseZ, cx1, cy1, -t, 0f);
		float lx = (cx0 + cx1) / 2f, lens = 0.048f, glass = 0.027f;
		for (float ly : new float[] {cy1 - 0.085f, cy0 + 0.085f}) {
			box(LENS_RING, lx - lens, ly - lens, baseZ - 0.010f, lx + lens, ly + lens, baseZ, 45f);
			box(LENS_GLASS, lx - glass, ly - glass, baseZ - 0.014f, lx + glass, ly + glass, baseZ - 0.010f, 45f);
		}
	}

	/**
	 * Corpo: contorno com os cantos facetados, extrudado, com as arestas da frente e
	 * de trás chanfradas. Cada faceta tem uma normal só, para o sombreado ficar poligonal.
	 */
	private void body(float w, float h, float radius) {
		int perCorner = CORNER_FACETS + 1, ring = 4 * perCorner;
		// Perfil na espessura: {quanto entra para dentro, z}.
		float[][] profile = {{RIM, 0f}, {0f, -RIM}, {0f, -THICKNESS + RIM}, {RIM, -THICKNESS}};
		float[][][] rings = new float[profile.length][ring][];
		for (int p = 0; p < profile.length; p++) {
			// Os vértices ficam por fora do arco, para as facetas tangenciarem o canto redondo da textura.
			float r = (radius - profile[p][0]) / (float) Math.cos(Math.PI / 4 / perCorner);
			int n = 0;
			for (int corner = 0; corner < 4; corner++) {
				float cx = (corner == 0 || corner == 3 ? 1 : -1) * (w - radius);
				float cy = (corner < 2 ? 1 : -1) * (h - radius);
				for (int s = 0; s < perCorner; s++) {
					double a = Math.PI / 2 * (corner + (s + 0.5) / perCorner);
					rings[p][n++] = new float[] {cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a), profile[p][1]};
				}
			}
		}

		float[] q = new float[(ring * (profile.length - 1) + 2 * ring) * 4 * STRIDE];
		int at = 0;
		for (int i = 0; i < ring; i++) {
			int j = (i + 1) % ring;
			for (int p = 0; p < profile.length - 1; p++) {
				at = flatQuad(q, at, rings[p][i], rings[p + 1][i], rings[p + 1][j], rings[p][j]);
			}
			// Tampas da frente e de trás: leque de quads degenerados a partir do centro.
			float[] front = {0f, 0f, 0f}, back = {0f, 0f, -THICKNESS};
			float[][] last = rings[profile.length - 1];
			at = flatQuad(q, at, front, rings[0][i], rings[0][j], rings[0][j]);
			at = flatQuad(q, at, back, last[j], last[i], last[i]);
		}
		parts.add(new Part(BODY, q));
	}

	/** Um quad com a normal calculada pelos três primeiros cantos (anti-horário visto de fora). */
	private static int flatQuad(float[] out, int at, float[] a, float[] b, float[] c, float[] d) {
		float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
		float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		return face(out, at, nx / len, ny / len, nz / len, a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2]);
	}

	/** Adiciona uma caixa; {@code spin} gira a caixa em torno do próprio centro, no eixo z (graus). */
	private void box(int color, float x0, float y0, float z0, float x1, float y1, float z1, float spin) {
		float[] q = new float[6 * 4 * STRIDE];
		int at = 0;
		at = face(q, at, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		at = face(q, at, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0);
		at = face(q, at, 1, 0, 0, x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1);
		at = face(q, at, -1, 0, 0, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		at = face(q, at, 0, 1, 0, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0);
		face(q, at, 0, -1, 0, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		if (spin != 0f) {
			float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f;
			float sin = (float) Math.sin(Math.toRadians(spin)), cos = (float) Math.cos(Math.toRadians(spin));
			for (int i = 0; i < q.length; i += STRIDE) {
				float dx = q[i] - cx, dy = q[i + 1] - cy;
				q[i] = cx + dx * cos - dy * sin;
				q[i + 1] = cy + dx * sin + dy * cos;
				float nx = q[i + 3], ny = q[i + 4];
				q[i + 3] = nx * cos - ny * sin;
				q[i + 4] = nx * sin + ny * cos;
			}
		}
		parts.add(new Part(color, q));
	}

	/** Um quad com a normal dada; os 4 cantos em sentido anti-horário vistos de fora. */
	private static int face(float[] out, int at, float nx, float ny, float nz, float... corners) {
		for (int c = 0; c < 12; c += 3) {
			out[at] = corners[c];
			out[at + 1] = corners[c + 1];
			out[at + 2] = corners[c + 2];
			out[at + 3] = nx;
			out[at + 4] = ny;
			out[at + 5] = nz;
			at += STRIDE;
		}
		return at;
	}
}
