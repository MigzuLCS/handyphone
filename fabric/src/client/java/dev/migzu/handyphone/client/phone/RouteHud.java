package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.Geo;
import dev.migzu.handyphone.client.os.Lang;
import dev.migzu.handyphone.client.os.PhoneData;
import dev.migzu.handyphone.client.os.PhoneOS;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Indicadores do celular na tela do jogo, fora do aparelho:
 * <ul>
 *   <li>a rota ("Ir até aqui"): uma seta que aponta para o destino e a distância, até chegar ou cancelar.
 *       Sem caminho calculado, só a direção em linha reta;</li>
 *   <li>um aviso vermelho enquanto a localização do jogador está sendo compartilhada, para ele nunca
 *       esquecer que está compartilhando (o celular guardado não mostra nada).</li>
 * </ul>
 */
public final class RouteHud implements HudElement {
	private static final int RED = 0xFFFF5A4E, WHITE = 0xFFFFFFFF, GRAY = 0xFFCFD3DC;

	private RouteHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(HandyPhone.id("location_hud"), new RouteHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		PhoneOS os = PhoneController.INSTANCE.os;
		// Sem a trava do PhoneOS: a thread de desenho do celular a segura durante cada quadro e o HUD não pode esperar.
		PhoneData.HudView hud = os.hud();
		PhoneData.Fix fix = os.self().fix;
		boolean sharing = hud.sharing();
		int left = (int) Math.max(0, (hud.shareUntil() - System.currentTimeMillis()) / 1000);
		boolean hasRoute = hud.hasRoute();
		String routeDimension = hud.routeDimension();
		int routeX = hud.routeX(), routeZ = hud.routeZ();
		boolean routeKnown = hud.routeKnown();
		String distance = hasRoute && routeKnown ? Geo.distanceFrom(fix, routeDimension, routeX, routeZ) : "";
		Font font = mc.font;
		int cx = graphics.guiWidth() / 2;
		// Começa abaixo da dica "V expand · Esc back" que a tela do celular escreve no topo.
		int y = 20;
		if (sharing) {
			String text = Lang.t("handyphone.hud.sharing", Geo.remaining(left * 1000L));
			graphics.centeredText(font, text, cx, y, RED);
			y += 12;
		}
		if (!hasRoute) {
			return;
		}
		// O nome só aparece quando diz alguma coisa (um local salvo, uma pessoa); um ponto marcado no mapa tem o nome vazio.
		String name = hud.routeLabel();
		boolean named = !name.isEmpty();
		int line = named ? 11 : 0;
		if (!routeKnown || fix == null) {
			if (named) {
				graphics.centeredText(font, name, cx, y, WHITE);
			}
			String why = !routeKnown && !routeDimension.isEmpty() && fix != null && !routeDimension.equals(fix.dimension())
				? Lang.t("handyphone.hud.other_dimension", Geo.dimensionName(routeDimension)) : Lang.t("handyphone.hud.no_signal");
			graphics.centeredText(font, why, cx, y + line, GRAY);
			return;
		}
		if (!routeDimension.equals(fix.dimension())) {
			if (named) {
				graphics.centeredText(font, name, cx, y, WHITE);
			}
			graphics.centeredText(font, Lang.t("handyphone.hud.other_dimension", Geo.dimensionName(routeDimension)), cx, y + line, GRAY);
			return;
		}
		// Ângulo do destino em relação ao olhar: positivo = à direita (o yaw do jogo cresce no sentido horário, visto de cima).
		double dx = routeX + 0.5 - fix.x(), dz = routeZ + 0.5 - fix.z();
		double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
		double rel = Math.toRadians(wrap(targetYaw - fix.yaw()));
		if (named) {
			graphics.centeredText(font, name, cx, y, WHITE);
		}
		ARROW.draw(graphics, cx, y + (named ? 19 : 7), rel, mc.getWindow().getGuiScale());
		graphics.centeredText(font, distance, cx, y + (named ? 29 : 17), GRAY);
	}

	private static double wrap(double degrees) {
		double d = degrees % 360;
		if (d > 180) {
			d -= 360;
		} else if (d < -180) {
			d += 360;
		}
		return d;
	}

	/** Só a thread de desenho do jogo chama o HUD, então um único rasterizador com buffers reaproveitados basta. */
	private static final Arrow3D ARROW = new Arrow3D();

	/**
	 * Uma seta de bússola em 3D: a silhueta de seta extrudada (um prisma com a lombada levemente elevada, como
	 * uma quilha), deitada no chão e vista de cima e um pouco de frente. Gira em torno do eixo vertical para
	 * apontar o destino, então mesmo virada para trás continua sendo lida como seta (a ponta encurta, mas o
	 * volume e a luz mostram para onde ela vai).
	 * <p>
	 * O GUI do jogo só desenha retângulos 2D, então o modelo é rasterizado aqui por software: projeção
	 * ortográfica, faces de trás para a frente (algoritmo do pintor), sombreamento plano por face com luz fixa
	 * vinda de cima à esquerda e um contorno escuro em volta da silhueta para destacar no céu e no chão.
	 * Desenha em pixels reais da tela (não em pixels do GUI, que são 2 a 4 vezes maiores): com só ~14 pixels
	 * de GUI a seta ficaria em degraus grossos demais para as faces aparecerem. Cada trecho horizontal de
	 * mesma cor vira um {@code fill}: algumas centenas de retângulos por quadro, sem alocar nada.
	 */
	private static final class Arrow3D {
		/**
		 * Silhueta no plano do chão (x para a direita, z para trás): ponta, asa direita, entalhe, asa esquerda.
		 * Mais comprida que larga e com entalhe fundo: larga demais ou com entalhe raso, virada para trás ela
		 * parecia um triângulo apontando para o lado.
		 */
		private static final double[] OX = {0, 0.68, 0, -0.68}, OZ = {-1.0, 0.72, 0.12, 0.72};
		/** Espessura do prisma e quanto a lombada (ponta e entalhe) sobe acima das asas, em unidades do modelo. */
		private static final double THICK = 0.2, RIDGE = 0.1;
		/** Pixels do GUI por unidade do modelo: ~11 px de largura e ~14 de comprimento (antes eram ~22). */
		private static final double GUI_PER_UNIT = 8;
		/**
		 * Elevação da câmera sobre o chão: 90° seria de cima, chapada (parecia uma seta 2D apontando para cima).
		 * 28° deita a seta de verdade: apontando para frente ou para trás ela encurta na vertical, como um objeto
		 * no chão visto em perspectiva, e as laterais aparecem.
		 */
		private static final double ELEV = Math.toRadians(28);
		private static final double SIN_E = Math.sin(ELEV), COS_E = Math.cos(ELEV);
		/** Luz fixa na tela (não gira com a seta): de cima, da esquerda e um pouco de frente. */
		private static final double LX, LY, LZ;
		private static final int TOP = 0xFF8CD7FF, SIDE = 0xFF5BB5FF, OUTLINE = 0xFF0E1830;

		static {
			double lx = -0.55, ly = 0.75, lz = 0.35, n = Math.sqrt(lx * lx + ly * ly + lz * lz);
			LX = lx / n;
			LY = ly / n;
			LZ = lz / n;
		}

		/**
		 * 4 laterais (aresta da silhueta em cima e embaixo) + 2 águas do topo; o fundo nunca aparece, a câmera
		 * está acima do chão. Índices dos vértices: 0..3 em cima, 4..7 embaixo.
		 */
		private static final int[][] FACE_VERTS = {{0, 1, 5, 4}, {1, 2, 6, 5}, {2, 3, 7, 6}, {3, 0, 4, 7}, {0, 1, 2}, {0, 2, 3}};
		private static final int FACES = FACE_VERTS.length;
		/** Vértices projetados (pixels reais, relativos ao centro) de cada face; no máximo 4 por face. */
		private final double[][] fx = new double[FACES][4], fy = new double[FACES][4];
		private final int[] fn = new int[FACES], fcolor = new int[FACES];
		private final double[] fdepth = new double[FACES];
		private final boolean[] fvisible = new boolean[FACES];
		private final int[] order = new int[FACES];
		/** Vértices do modelo já girados: 0..3 em cima, 4..7 embaixo. */
		private final double[] vx = new double[8], vy = new double[8], vz = new double[8];
		private int[] pixels = new int[0], outlined = new int[0];
		private int size;

		void draw(GuiGraphicsExtractor graphics, int cx, int cy, double angle, int guiScale) {
			int scale = Math.max(1, guiScale);
			double s = GUI_PER_UNIT * scale;
			int border = Math.max(1, Math.round(scale / 2f));
			int half = (int) Math.ceil(1.25 * s) + border + 1;
			int n = half * 2;
			if (n != size) {
				size = n;
				pixels = new int[n * n];
				outlined = new int[n * n];
			} else {
				java.util.Arrays.fill(pixels, 0);
			}
			build(angle, s);
			// Pintor: as laterais primeiro, das mais fundas para as mais próximas; o topo por último, porque visto
			// de cima nenhuma lateral (que fica abaixo dele) pode cobri-lo.
			int count = 0;
			for (int f = 0; f < 4; f++) {
				if (fvisible[f]) {
					order[count++] = f;
				}
			}
			for (int i = 1; i < count; i++) {
				int f = order[i], j = i - 1;
				while (j >= 0 && fdepth[order[j]] > fdepth[f]) {
					order[j + 1] = order[j];
					j--;
				}
				order[j + 1] = f;
			}
			order[count++] = fdepth[4] <= fdepth[5] ? 4 : 5;
			order[count++] = fdepth[4] <= fdepth[5] ? 5 : 4;
			for (int i = 0; i < count; i++) {
				raster(order[i], half);
			}
			outline(border);
			emit(graphics, cx, cy, scale, half);
		}

		/** Gira o modelo pelo ângulo do destino, projeta e calcula visibilidade, profundidade e cor de cada face. */
		private void build(double angle, double s) {
			double sin = Math.sin(angle), cos = Math.cos(angle);
			// Centra o volume na tela: metade da altura para baixo e o meio da silhueta (não a ponta) no centro.
			double cz = 0.05, cyOff = -(THICK + RIDGE) / 2;
			for (int i = 0; i < 4; i++) {
				double x = OX[i], z = OZ[i] - cz;
				double rx = x * cos - z * sin, rz = x * sin + z * cos;
				// Ponta e entalhe ficam na lombada, mais altos que as asas: o topo vira duas águas com luzes diferentes.
				double top = THICK + (i % 2 == 0 ? RIDGE : 0);
				vx[i] = rx;
				vy[i] = top + cyOff;
				vz[i] = rz;
				vx[i + 4] = rx;
				vy[i + 4] = cyOff;
				vz[i + 4] = rz;
			}
			// Laterais: a normal de fora sai da aresta da silhueta (que roda no sentido horário visto de cima).
			for (int e = 0; e < 4; e++) {
				int a = e, b = (e + 1) % 4;
				double ex = vx[b] - vx[a], ez = vz[b] - vz[a], len = Math.sqrt(ex * ex + ez * ez);
				double nx = ez / len, nz = -ex / len;
				// Vista: só a face que olha para a câmera (componente na direção do observador) aparece.
				fvisible[e] = nz * COS_E > 1e-6;
				project(e, s);
				fcolor[e] = shade(SIDE, nx, 0, nz);
			}
			// Duas águas do topo: direita (ponta, asa direita, entalhe) e esquerda (ponta, entalhe, asa esquerda).
			roof(4, s);
			roof(5, s);
		}

		private void roof(int f, double s) {
			int a = FACE_VERTS[f][0], b = FACE_VERTS[f][1], c = FACE_VERTS[f][2];
			double ux = vx[b] - vx[a], uy = vy[b] - vy[a], uz = vz[b] - vz[a];
			double wx = vx[c] - vx[a], wy = vy[c] - vy[a], wz = vz[c] - vz[a];
			double nx = uy * wz - uz * wy, ny = uz * wx - ux * wz, nz = ux * wy - uy * wx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (ny < 0) {
				len = -len;
			}
			fvisible[f] = true;
			project(f, s);
			fcolor[f] = shade(TOP, nx / len, ny / len, nz / len);
		}

		/** Ortográfica: x da tela = x; y da tela (para baixo) desce com a altura e sobe com a distância. */
		private void project(int f, double s) {
			int[] idx = FACE_VERTS[f];
			int count = idx.length;
			double depth = 0;
			for (int i = 0; i < count; i++) {
				int v = idx[i];
				fx[f][i] = vx[v] * s;
				fy[f][i] = (-vy[v] * COS_E + vz[v] * SIN_E) * s;
				depth += vy[v] * SIN_E + vz[v] * COS_E;
			}
			fn[f] = count;
			fdepth[f] = depth / count;
		}

		/** Sombreamento plano: luz ambiente mais difusa, sem passar do branco. */
		private static int shade(int base, double nx, double ny, double nz) {
			double k = 0.5 + 0.62 * Math.max(0, nx * LX + ny * LY + nz * LZ);
			int r = Math.min(255, (int) (((base >> 16) & 0xFF) * k));
			int g = Math.min(255, (int) (((base >> 8) & 0xFF) * k));
			int b = Math.min(255, (int) ((base & 0xFF) * k));
			return 0xFF000000 | r << 16 | g << 8 | b;
		}

		/** Varredura por linhas no centro de cada pixel; as faces são convexas, então basta o mínimo e o máximo. */
		private void raster(int f, int half) {
			double[] xs = fx[f], ys = fy[f];
			int count = fn[f], color = fcolor[f];
			double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
			for (int i = 0; i < count; i++) {
				minY = Math.min(minY, ys[i]);
				maxY = Math.max(maxY, ys[i]);
			}
			int y0 = Math.max(-half, (int) Math.floor(minY)), y1 = Math.min(half - 1, (int) Math.ceil(maxY));
			for (int py = y0; py <= y1; py++) {
				double sy = py + 0.5, lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
				for (int i = 0; i < count; i++) {
					double ax = xs[i], ay = ys[i], bx = xs[(i + 1) % count], by = ys[(i + 1) % count];
					if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
						double x = ax + (sy - ay) / (by - ay) * (bx - ax);
						lo = Math.min(lo, x);
						hi = Math.max(hi, x);
					}
				}
				if (lo > hi) {
					continue;
				}
				// Pixel aceso quando o centro dele cai dentro do trecho.
				int x0 = Math.max(-half, (int) Math.ceil(lo - 0.5)), x1 = Math.min(half - 1, (int) Math.ceil(hi - 0.5) - 1);
				int row = (py + half) * size + half;
				for (int px = x0; px <= x1; px++) {
					pixels[row + px] = color;
				}
			}
		}

		/** Contorno escuro: todo pixel vazio a até {@code border} pixels da seta vira borda. */
		private void outline(int border) {
			int n = size;
			for (int y = 0; y < n; y++) {
				for (int x = 0; x < n; x++) {
					int c = pixels[y * n + x];
					if (c == 0) {
						search:
						for (int oy = -border; oy <= border; oy++) {
							int yy = y + oy;
							if (yy < 0 || yy >= n) {
								continue;
							}
							for (int ox = -border; ox <= border; ox++) {
								int xx = x + ox;
								// Cantos de fora do quadrado arredondam a borda.
								if (xx >= 0 && xx < n && ox * ox + oy * oy <= border * border + border && pixels[yy * n + xx] != 0) {
									c = OUTLINE;
									break search;
								}
							}
						}
					}
					outlined[y * n + x] = c;
				}
			}
		}

		/** Manda cada trecho horizontal de mesma cor como um retângulo, em pixels reais da tela. */
		private void emit(GuiGraphicsExtractor graphics, int cx, int cy, int scale, int half) {
			int n = size;
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx, cy);
			graphics.pose().scale(1f / scale, 1f / scale);
			for (int y = 0; y < n; y++) {
				int x = 0;
				while (x < n) {
					int c = outlined[y * n + x];
					int end = x + 1;
					while (end < n && outlined[y * n + end] == c) {
						end++;
					}
					if (c != 0) {
						graphics.fill(x - half, y - half, end - half, y - half + 1, c);
					}
					x = end;
				}
			}
			graphics.pose().popMatrix();
		}
	}
}
