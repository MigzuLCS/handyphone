package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.client.os.PhoneData.Conversation;
import dev.migzu.handyphone.client.os.PhoneData.Live;
import dev.migzu.handyphone.client.os.PhoneData.Place;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App Mapa: o terreno (tiles do squaremap, quando o servidor os tem), a própria posição com a direção do
 * olhar, locais salvos, pontos recebidos, quem compartilha a localização ao vivo e a rota até um ponto.
 * Também cuida dos locais salvos e do compartilhamento. Sem o squaremap, o fundo é neutro com grade.
 *
 * <p>Coordenadas: o mapa é desenhado em unidades da face do celular; {@code scale} é quantas unidades
 * ocupa um bloco. O norte fica em cima (z cresce para baixo), como nos tiles.
 */
final class MapApp extends AppContent {
	private enum View { MAP, PLACES, LIVE, NAME, SHARE_PLACE }

	/** Um ponto escolhido: o que o cartão do mapa mostra e o que "Ir até aqui"/"Salvar" usam. */
	private record Sel(String label, String dimension, int x, boolean hasY, int y, int z, String contact) {
	}

	/** Um marcador desenhado neste quadro, para o toque achar o mais próximo. */
	private record Marker(float sx, float sy, Sel sel) {
	}

	private static final float MARGIN = 8f;
	/** Unidades da face por bloco, do mais perto ao mais longe. */
	private static final float[] SCALES = {2f, 1f, 0.5f, 0.25f, 0.125f};
	private static final Color WATER = new Color(0xDCE4EA), GRID = new Color(0xC6D0DA), RED = new Color(0xE5392E), TEAL = new Color(0x1F9E89);

	private View view = View.MAP;
	private View nameBack = View.MAP;
	/** Cor escolhida na tela de nomear (índice da paleta) e o local em edição (0 = salvando um novo). */
	private int nameColor;
	private long editingId;
	/** Local sendo encaminhado e de onde se veio. */
	private Place sharing;
	private View shareBack = View.PLACES;
	private float shareScroll, shareMax;
	private double centerX, centerZ;
	private boolean follow = true;
	private int zoom = 1;
	private Sel selected;
	/** Conversa para onde o ponto escolhido será enviado (modo "ponto no mapa"), ou -1. */
	private long pickFor = -1;
	private final List<Marker> markers = new ArrayList<>();

	// arrasto
	private boolean dragging, dragMoved;
	private float dragX, dragY, dragTotal;

	// listas e formulários
	private float placesScroll, placesMax, liveScroll, liveMax;
	private final Field placeName = new Field(24, false);
	private Sel naming;
	private final Set<String> shareNumbers = new LinkedHashSet<>();
	private long shareGroup;
	private int shareDuration = 1;

	// ------------------------------------------------------------------ navegação

	@Override
	void opened(PhoneOS os) {
		view = View.MAP;
		focus = null;
		selected = null;
		pickFor = -1;
		follow = true;
	}

	float scale() {
		return SCALES[zoom];
	}

	/** Mostra um ponto (de uma mensagem, local salvo ou contato) com o cartão de ações. */
	void showPoint(PhoneOS os, String label, String dimension, int x, boolean hasY, int y, int z, String contact) {
		view = View.MAP;
		focus = null;
		pickFor = -1;
		selected = new Sel(label, dimension, x, hasY, y, z, contact);
		if (dimension.equals(os.data.map.dimension) || os.data.map.dimension.isEmpty()) {
			follow = false;
			centerX = x + 0.5;
			centerZ = z + 0.5;
		}
	}

	/** Modo "escolher ponto": o jogador toca no mapa e manda o ponto marcado na conversa. */
	void startPick(long conversation) {
		view = View.MAP;
		focus = null;
		selected = null;
		pickFor = conversation;
		follow = true;
	}

	@Override
	boolean back(PhoneOS os) {
		switch (view) {
			case NAME -> {
				view = nameBack;
				focus = null;
				return true;
			}
			case PLACES, LIVE -> {
				view = View.MAP;
				return true;
			}
			case SHARE_PLACE -> {
				view = shareBack;
				return true;
			}
			default -> {
				if (selected != null) {
					selected = null;
					return true;
				}
				if (pickFor >= 0) {
					long conversation = pickFor;
					pickFor = -1;
					os.returnToChat(conversation);
					return true;
				}
				return false;
			}
		}
	}

	@Override
	boolean smoothScroll() {
		// No mapa cada clique é um degrau de zoom; as listas (locais, ao vivo, encaminhar) deslizam.
		return view != View.MAP;
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		switch (view) {
			case MAP -> {
				int next = Math.max(0, Math.min(SCALES.length - 1, zoom + (amount > 0 ? -1 : 1)));
				if (next != zoom) {
					zoom = next;
					os.playScrollTick();
				}
			}
			case PLACES -> {
				float next = Math.max(0, Math.min(placesScroll - amount * SCROLL_STEP, placesMax));
				if (Math.abs(next - placesScroll) > 0.01f) {
					placesScroll = next;
					os.playScrollTick();
				}
			}
			case LIVE -> {
				float next = Math.max(0, Math.min(liveScroll - amount * SCROLL_STEP, liveMax));
				if (Math.abs(next - liveScroll) > 0.01f) {
					liveScroll = next;
					os.playScrollTick();
				}
			}
			case SHARE_PLACE -> {
				float next = Math.max(0, Math.min(shareScroll - amount * SCROLL_STEP, shareMax));
				if (Math.abs(next - shareScroll) > 0.01f) {
					shareScroll = next;
					os.playScrollTick();
				}
			}
			default -> { }
		}
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		super.key(os, key);
		if (view == View.NAME && key == PhoneOS.Key.ENTER) {
			saveNamed(os);
		}
	}

	@Override
	void pointerMoved(PhoneOS os, float x, float y) {
		if (view != View.MAP || !isPressed("map")) {
			return;
		}
		if (!dragging) {
			dragging = true;
			dragX = x;
			dragY = y;
			dragTotal = 0;
			return;
		}
		float dx = x - dragX, dy = y - dragY;
		dragX = x;
		dragY = y;
		dragTotal += Math.abs(dx) + Math.abs(dy);
		if (dragTotal > 4) {
			dragMoved = true;
			follow = false;
			centerX -= dx / SCALES[zoom];
			centerZ -= dy / SCALES[zoom];
		}
	}

	@Override
	void pointerReleased() {
		dragging = false;
		dragMoved = false;
	}

	// ------------------------------------------------------------------ desenho

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		switch (view) {
			case MAP -> drawMap(g, os, x, y, w, h, accent);
			case PLACES -> drawPlaces(g, os, x, y, w, h, accent);
			case LIVE -> drawLive(g, os, x, y, w, h, accent);
			case NAME -> drawName(g, os, x, y, w, accent);
			case SHARE_PLACE -> drawSharePlace(g, os, x, y, w, h, accent);
		}
	}

	private void drawMap(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		PhoneData.SelfPos self = data.self;
		float headH = 26, toolH = 26;
		float my = y + headH, mh = h - headH - toolH;
		float scale = SCALES[zoom];
		if (follow && self.known) {
			centerX = self.x;
			centerZ = self.z;
		}
		float cx = x + w / 2f, cy = my + mh / 2f;

		// Cabeçalho: dimensão, coordenadas e bioma.
		g.setColor(new Color(0xE6E8EE));
		g.fill(new Rectangle2D.Float(x, y, w, headH));
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, y + headH - 0.6f, w, 0.6f));
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 8.2f));
		if (pickFor >= 0) {
			Ui.text(g, Lang.t("handyphone.map.pick.line1"), x + MARGIN, y + 11);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			Ui.text(g, Lang.t("handyphone.map.pick.line2"), x + MARGIN, y + 21);
		} else if (self.known) {
			String dim = Geo.dimensionName(self.dimension);
			Ui.text(g, fit(g, dim + "  " + Geo.coords((int) Math.floor(self.x), true, (int) Math.floor(self.y), (int) Math.floor(self.z)), w - 2 * MARGIN), x + MARGIN, y + 11);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			Ui.text(g, fit(g, self.biome.isEmpty() ? Lang.t("handyphone.map.biome_unknown") : self.biome, w - 2 * MARGIN), x + MARGIN, y + 21);
		} else {
			g.setColor(MUTED);
			Ui.text(g, Lang.t("handyphone.map.no_signal"), x + MARGIN, y + 16);
		}

		// Área do mapa.
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, my, w, mh));
		g.setColor(WATER);
		g.fill(new Rectangle2D.Float(x, my, w, mh));
		hit("map", x, my, w, mh, null, () -> {
			if (!dragMoved) {
				tapMap(os, pointerX, pointerY, cx, cy, scale);
			}
		});
		markers.clear();
		drawGrid(g, x, my, w, mh, cx, cy, scale);
		drawTiles(g, os, x, my, w, mh, cx, cy, scale);
		drawOverlays(g, os, x, my, w, mh, cx, cy, scale, accent);
		g.setClip(oldClip);

		// Avisos sobre o mapa.
		float noteY = my + 6;
		if (data.share.active) {
			String text = Lang.t("handyphone.map.sharing_chip", Geo.remaining(data.share.until - System.currentTimeMillis()));
			g.setFont(Ui.font(Font.BOLD, 7.4f));
			float pw = Ui.textWidth(g, text) + 20;
			boolean down = hit("sharechip", x + 6, noteY, pw, 14, PhoneSound.LIST_ITEM, () -> view = View.LIVE);
			g.setColor(down ? Ui.shade(RED, 0.8f) : RED);
			g.fill(new RoundRectangle2D.Float(x + 6, noteY, pw, 14, 14, 14));
			g.setColor(Color.WHITE);
			g.fill(new Ellipse2D.Float(x + 11, noteY + 4.5f, 5, 5));
			Ui.text(g, text, x + 19, noteY + 9.8f);
			noteY += 18;
		}
		String note = null;
		if (!data.map.received) {
			note = Lang.t("handyphone.map.note.no_server");
		} else if (!data.map.installed) {
			note = Lang.t("handyphone.map.note.no_map");
		} else if (!data.map.rendered) {
			note = Lang.t("handyphone.map.note.not_drawn");
		}
		if (note != null) {
			g.setFont(Ui.font(Font.PLAIN, 7f));
			float pw = Math.min(w - 12, Ui.textWidth(g, note) + 12);
			g.setColor(new Color(0x23252C));
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.SrcOver.derive(0.78f));
			g.fill(new RoundRectangle2D.Float(x + 6, noteY, pw, 13, 13, 13));
			g.setComposite(old);
			g.setColor(Color.WHITE);
			Ui.text(g, fit(g, note, pw - 10), x + 11, noteY + 9);
		}

		// Botões de zoom e centralizar.
		float bx = x + w - 24, by = my + 8;
		// SCALES vai do mais perto (índice 0) ao mais longe: "+" aproxima (índice menor), "-" afasta.
		zoomButton(g, "zin", bx, by, 0, zoom > 0, () -> zoom = Math.max(0, zoom - 1));
		zoomButton(g, "zout", bx, by + 21, 1, zoom < SCALES.length - 1, () -> zoom = Math.min(SCALES.length - 1, zoom + 1));
		zoomButton(g, "center", bx, by + 42, 2, true, () -> follow = true);

		if (selected != null) {
			drawCard(g, os, x, my, w, mh, accent);
		}

		// Barra de baixo.
		float ty = y + h - toolH;
		g.setColor(new Color(0xE6E8EE));
		g.fill(new Rectangle2D.Float(x, ty, w, toolH));
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, ty, w, 0.6f));
		float bw = (w - 2 * MARGIN - 8) / 3f;
		if (pickFor >= 0) {
			button(g, "cancelpick", Lang.t("handyphone.cancel"), x + MARGIN, ty + 4, w - 2 * MARGIN, 18, new Color(0x8E8E93), () -> {
				long conversation = pickFor;
				pickFor = -1;
				os.returnToChat(conversation);
			});
		} else {
			button(g, "places", Lang.t("handyphone.map.btn.places"), x + MARGIN, ty + 4, bw, 18, Ui.shade(accent, 0.9f), () -> {
				placesScroll = 0;
				view = View.PLACES;
			});
			button(g, "live", Lang.t("handyphone.map.btn.live"), x + MARGIN + bw + 4, ty + 4, bw, 18, data.share.active ? RED : Ui.shade(accent, 0.9f), () -> {
				liveScroll = 0;
				view = View.LIVE;
			});
			button(g, "saveHere", Lang.t("handyphone.map.btn.save_here"), x + MARGIN + 2 * (bw + 4), ty + 4, bw, 18, Ui.shade(accent, 0.9f), () -> {
				if (self.known) {
					beginNaming(new Sel("", self.dimension, (int) Math.floor(self.x), true, (int) Math.floor(self.y), (int) Math.floor(self.z), null), View.MAP);
				} else {
					os.mapNotice(Lang.t("handyphone.map.unknown_position"), true);
				}
			});
		}
	}

	private void zoomButton(Graphics2D g, String key, float x, float y, int kind, boolean enabled, Runnable action) {
		boolean down = hit(key, x - 2, y - 2, 22, 22, PhoneSound.SELECT, enabled ? action : () -> { });
		g.setColor(new Color(0, 0, 0, 40));
		g.fill(new Ellipse2D.Float(x, y + 1, 18, 18));
		g.setColor(down ? PRESSED : Color.WHITE);
		g.fill(new Ellipse2D.Float(x, y, 18, 18));
		g.setColor(enabled ? INK : new Color(0xB4B6BE));
		g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		float mx = x + 9, my = y + 9;
		if (kind == 0) {
			g.draw(new Line2D.Float(mx - 4, my, mx + 4, my));
			g.draw(new Line2D.Float(mx, my - 4, mx, my + 4));
		} else if (kind == 1) {
			g.draw(new Line2D.Float(mx - 4, my, mx + 4, my));
		} else {
			g.setStroke(new BasicStroke(1.3f));
			g.draw(new Ellipse2D.Float(mx - 4, my - 4, 8, 8));
			g.fill(new Ellipse2D.Float(mx - 1.4f, my - 1.4f, 2.8f, 2.8f));
		}
	}

	private void drawGrid(Graphics2D g, float x, float y, float w, float h, float cx, float cy, float scale) {
		double step = 16;
		while (step * scale < 18) {
			step *= 4;
		}
		g.setStroke(new BasicStroke(0.5f));
		g.setColor(GRID);
		double left = centerX - (cx - x) / scale, right = centerX + (x + w - cx) / scale;
		double top = centerZ - (cy - y) / scale, bottom = centerZ + (y + h - cy) / scale;
		for (double wx = Math.floor(left / step) * step; wx <= right; wx += step) {
			float sx = (float) (cx + (wx - centerX) * scale);
			g.draw(new Line2D.Float(sx, y, sx, y + h));
		}
		for (double wz = Math.floor(top / step) * step; wz <= bottom; wz += step) {
			float sy = (float) (cy + (wz - centerZ) * scale);
			g.draw(new Line2D.Float(x, sy, x + w, sy));
		}
	}

	/** Os tiles visíveis. O zoom do tile é o menor que ainda tem pelo menos um pixel por unidade da tela. */
	private void drawTiles(Graphics2D g, PhoneOS os, float x, float y, float w, float h, float cx, float cy, float scale) {
		PhoneData.MapInfo info = os.data.map;
		TileCache tiles = os.data.tiles;
		tiles.beginFrame();
		if (!info.rendered || info.maxZoom < 0) {
			return;
		}
		int levelsOut = scale >= 1f ? 0 : (int) Math.round(Math.log(1.0 / scale) / Math.log(2));
		int tileZoom = Math.max(0, info.maxZoom - levelsOut);
		long span = 512L << (info.maxZoom - tileZoom);
		double left = centerX - (cx - x) / scale, right = centerX + (x + w - cx) / scale;
		double top = centerZ - (cy - y) / scale, bottom = centerZ + (y + h - cy) / scale;
		int x0 = (int) Math.floorDiv((long) Math.floor(left), span), x1 = (int) Math.floorDiv((long) Math.floor(right), span);
		int z0 = (int) Math.floorDiv((long) Math.floor(top), span), z1 = (int) Math.floorDiv((long) Math.floor(bottom), span);
		if ((long) (x1 - x0 + 1) * (z1 - z0 + 1) > 16) {
			return;
		}
		float pxPerUnit = span * scale / 512f;
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, pxPerUnit >= 1f ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int tz = z0; tz <= z1; tz++) {
			for (int tx = x0; tx <= x1; tx++) {
				BufferedImage image = tiles.get(new TileCache.Key(tileZoom, tx, tz), os.backend, os.time);
				if (image == null) {
					continue;
				}
				float sx = (float) (cx + (tx * (double) span - centerX) * scale);
				float sy = (float) (cy + (tz * (double) span - centerZ) * scale);
				AffineTransform at = new AffineTransform();
				at.translate(sx, sy);
				at.scale(pxPerUnit * 512f / image.getWidth(), pxPerUnit * 512f / image.getHeight());
				g.drawImage(image, at, null);
			}
		}
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
	}

	private void drawOverlays(Graphics2D g, PhoneOS os, float x, float y, float w, float h, float cx, float cy, float scale, Color accent) {
		PhoneData data = os.data;
		PhoneData.SelfPos self = data.self;
		String here = self.known ? self.dimension : data.map.dimension;

		// Rota: linha tracejada até o destino.
		PhoneData.Route route = data.route;
		if (route != null && route.known && route.dimension.equals(here) && self.known) {
			float sx = sx(self.x, cx, scale), sy = sz(self.z, cy, scale);
			float tx = sx(route.x + 0.5, cx, scale), ty = sz(route.z + 0.5, cy, scale);
			g.setColor(new Color(0x1E88E5));
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {4f, 3f}, 0f));
			g.draw(new Line2D.Float(sx, sy, tx, ty));
		}
		// O destino da rota ganha um feixe de luz (como um waypoint); os locais salvos comuns, não.
		if (route != null && route.known && route.dimension.equals(here)) {
			float tx = sx(route.x + 0.5, cx, scale), ty = sz(route.z + 0.5, cy, scale);
			if (inView(tx, ty, x, y, w, h, 4)) {
				drawBeam(g, tx, ty);
			} else {
				drawBeamEdge(g, tx, ty, x, y, w, h);
			}
		}

		// Locais salvos.
		for (Place p : data.places) {
			if (p.dimension().equals(here)) {
				float px = sx(p.x() + 0.5, cx, scale), py = sz(p.z() + 0.5, cy, scale);
				if (inView(px, py, x, y, w, h, 8)) {
					pin(g, px, py, 0.8f, Geo.placeColor(p.color()));
					markers.add(new Marker(px, py - 5, new Sel(p.name(), p.dimension(), p.x(), p.hasY(), p.y(), p.z(), null)));
				}
			}
		}

		// Quem compartilha ao vivo.
		for (Live l : data.live) {
			if (l.state() != PhoneData.LIVE_POSITION || !l.dimension().equals(here)) {
				continue;
			}
			float px = sx(l.x() + 0.5, cx, scale), py = sz(l.z() + 0.5, cy, scale);
			if (inView(px, py, x, y, w, h, 10)) {
				String name = data.displayName(l.number());
				g.setColor(Color.WHITE);
				g.fill(new Ellipse2D.Float(px - 8.5f, py - 8.5f, 17, 17));
				avatar(g, l.number(), name, px, py, 7.2f);
				markers.add(new Marker(px, py, new Sel(name, l.dimension(), l.x(), true, l.y(), l.z(), l.number())));
			}
		}

		// O ponto escolhido.
		if (selected != null && selected.dimension().equals(here)) {
			float px = sx(selected.x() + 0.5, cx, scale), py = sz(selected.z() + 0.5, cy, scale);
			if (inView(px, py, x, y, w, h, 12)) {
				pin(g, px, py, 1.1f, RED);
			}
		}

		// Eu: cone do olhar e seta.
		if (self.known && self.dimension.equals(here)) {
			float px = sx(self.x, cx, scale), py = sz(self.z, cy, scale);
			double yaw = Math.toRadians(self.yaw);
			float dirDeg = (float) Math.toDegrees(Math.atan2(Math.cos(yaw), -Math.sin(yaw)));
			Composite old = g.getComposite();
			g.setComposite(AlphaComposite.SrcOver.derive(0.28f));
			g.setColor(accent);
			g.fill(new Arc2D.Float(px - 24, py - 24, 48, 48, -dirDeg - 32, 64, Arc2D.PIE));
			g.setComposite(old);
			AffineTransform t = g.getTransform();
			g.translate(px, py);
			g.rotate(yaw + Math.PI);
			Path2D arrow = new Path2D.Float();
			arrow.moveTo(0, -7);
			arrow.lineTo(5, 5.5f);
			arrow.lineTo(0, 3);
			arrow.lineTo(-5, 5.5f);
			arrow.closePath();
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(arrow);
			g.setColor(new Color(0x1E6FE0));
			g.fill(arrow);
			g.setTransform(t);
		}
	}

	/** Feixe de luz subindo do destino da rota: brilho no chão, coluna que some no alto e um losango na base. */
	private static void drawBeam(Graphics2D g, float bx, float by) {
		float height = 72;
		Color lit = new Color(150, 215, 255);
		AffineTransform t = g.getTransform();
		g.translate(bx, by);
		g.scale(1, 0.5);
		g.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), 12f, new float[] {0f, 1f}, new Color[] {Ui.alpha(lit, 200), Ui.alpha(lit, 0)}));
		g.fill(new Ellipse2D.Float(-12, -12, 24, 24));
		g.setTransform(t);
		g.setPaint(new LinearGradientPaint(bx, by, bx, by - height, new float[] {0f, 1f}, new Color[] {Ui.alpha(lit, 240), Ui.alpha(lit, 0)}));
		g.fill(new Rectangle2D.Float(bx - 4.5f, by - height, 9f, height));
		g.setPaint(new LinearGradientPaint(bx, by, bx, by - height, new float[] {0f, 1f}, new Color[] {new Color(255, 255, 255, 245), new Color(255, 255, 255, 0)}));
		g.fill(new Rectangle2D.Float(bx - 1.4f, by - height, 2.8f, height));
		g.setPaint(null);
		Path2D diamond = new Path2D.Float();
		diamond.moveTo(bx, by - 3.2f);
		diamond.lineTo(bx + 3.6f, by);
		diamond.lineTo(bx, by + 3.2f);
		diamond.lineTo(bx - 3.6f, by);
		diamond.closePath();
		g.setColor(Color.WHITE);
		g.fill(diamond);
		g.setColor(new Color(0x1E88E5));
		g.setStroke(new BasicStroke(0.9f));
		g.draw(diamond);
	}

	/** Destino fora da área visível: uma ponta de seta com brilho na borda do mapa, apontando para ele. */
	private void drawBeamEdge(Graphics2D g, float tx, float ty, float x, float y, float w, float h) {
		float cx = x + w / 2f, cy = y + h / 2f, dx = tx - cx, dy = ty - cy;
		float k = Math.min((w / 2f - 12) / Math.max(1e-3f, Math.abs(dx)), (h / 2f - 12) / Math.max(1e-3f, Math.abs(dy)));
		float ex = cx + dx * k, ey = cy + dy * k;
		double ang = Math.atan2(dy, dx);
		AffineTransform t = g.getTransform();
		g.translate(ex, ey);
		g.rotate(ang);
		Color lit = new Color(150, 215, 255);
		g.setPaint(new RadialGradientPaint(new Point2D.Float(0, 0), 12f, new float[] {0f, 1f}, new Color[] {Ui.alpha(lit, 190), Ui.alpha(lit, 0)}));
		g.fill(new Ellipse2D.Float(-12, -12, 24, 24));
		g.setPaint(null);
		Path2D tip = new Path2D.Float();
		tip.moveTo(6, 0);
		tip.lineTo(-4, -5);
		tip.lineTo(-1.5f, 0);
		tip.lineTo(-4, 5);
		tip.closePath();
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(tip);
		g.setColor(new Color(0x1E88E5));
		g.fill(tip);
		g.setTransform(t);
	}

	/** Coordenada x da tela de um ponto do mundo. */
	private float sx(double wx, float cx, float scale) {
		return (float) (cx + (wx - centerX) * scale);
	}

	/** Coordenada y da tela de um ponto do mundo (o z do mundo cresce para baixo). */
	private float sz(double wz, float cy, float scale) {
		return (float) (cy + (wz - centerZ) * scale);
	}

	private static boolean inView(float px, float py, float x, float y, float w, float h, float pad) {
		return px >= x - pad && px <= x + w + pad && py >= y - pad && py <= y + h + pad;
	}

	/** Alfinete com a ponta em (px, py). */
	static void pin(Graphics2D g, float px, float py, float k, Color color) {
		AffineTransform t = g.getTransform();
		g.translate(px, py);
		g.scale(k, k);
		Path2D p = new Path2D.Float();
		p.moveTo(0, 0);
		p.curveTo(-6, -7, -6, -8.5f, -6, -10);
		p.curveTo(-6, -17, 6, -17, 6, -10);
		p.curveTo(6, -8.5f, 6, -7, 0, 0);
		p.closePath();
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(p);
		g.setColor(color);
		g.fill(p);
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(-2.4f, -12.4f, 4.8f, 4.8f));
		g.setTransform(t);
	}

	// ------------------------------------------------------------------ toque no mapa e cartão

	private void tapMap(PhoneOS os, float px, float py, float cx, float cy, float scale) {
		Marker best = null;
		float bestD = 11;
		for (Marker m : markers) {
			// Escolhendo um ponto para mandar, o marcador de uma pessoa não conta: senão um toque reenviaria a posição ao vivo dela como "ponto marcado".
			if (pickFor >= 0 && m.sel.contact() != null) {
				continue;
			}
			float d = (float) Math.hypot(m.sx - px, m.sy - py);
			if (d < bestD) {
				bestD = d;
				best = m;
			}
		}
		if (best != null) {
			selected = best.sel;
			return;
		}
		int wx = (int) Math.floor(centerX + (px - cx) / scale), wz = (int) Math.floor(centerZ + (py - cy) / scale);
		String dimension = os.data.self.known ? os.data.self.dimension : os.data.map.dimension;
		selected = new Sel("", dimension, wx, false, 0, wz, null);
	}

	private void drawCard(Graphics2D g, PhoneOS os, float x, float my, float w, float mh, Color accent) {
		Sel s = selected;
		boolean picking = pickFor >= 0;
		Place savedPlace = null;
		for (Place p : os.data.places) {
			if (p.dimension().equals(s.dimension()) && p.x() == s.x() && p.z() == s.z()) {
				savedPlace = p;
			}
		}
		boolean saved = savedPlace != null;
		Place place = savedPlace;
		float ch = 58, cw = w - 12, cx0 = x + 6, cy0 = my + mh - ch - 6;
		g.setColor(new Color(0, 0, 0, 45));
		g.fill(new RoundRectangle2D.Float(cx0, cy0 + 1.5f, cw, ch, 10, 10));
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(cx0, cy0, cw, ch, 10, 10));
		hit("cardbg", cx0, cy0, cw, ch, null, () -> { });
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 8.6f));
		Ui.text(g, fit(g, s.label().isEmpty() ? Lang.t("handyphone.preview.pin") : s.label(), cw - 30), cx0 + 8, cy0 + 12);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7f));
		String distance = Geo.distanceFrom(os.data.self, s.dimension(), s.x(), s.z());
		Ui.text(g, fit(g, Geo.coords(s.x(), s.hasY(), s.y(), s.z()) + (distance.isEmpty() ? "" : "  ·  " + distance), cw - 16), cx0 + 8, cy0 + 22);
		hit("cardclose", cx0 + cw - 20, cy0 + 2, 18, 16, PhoneSound.BUTTON, () -> selected = null);
		g.setColor(MUTED);
		g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new Line2D.Float(cx0 + cw - 15, cy0 + 6, cx0 + cw - 9, cy0 + 12));
		g.draw(new Line2D.Float(cx0 + cw - 9, cy0 + 6, cx0 + cw - 15, cy0 + 12));

		float by = cy0 + 29, bh = 22;
		if (picking) {
			button(g, "sendpin", Lang.t("handyphone.map.send_pin"), cx0 + 6, by, cw - 12, bh, accent, () -> {
				long conversation = pickFor;
				pickFor = -1;
				os.backend.sendPin(conversation, s.x(), s.z());
				os.playSound(PhoneSound.MESSAGE_SENT);
				os.returnToChat(conversation);
			});
			return;
		}
		boolean routed = os.data.route != null && os.data.route.x == s.x() && os.data.route.z == s.z()
			&& os.data.route.dimension.equals(s.dimension());
		float half = (cw - 12 - 4) / 2f;
		if (routed) {
			button(g, "goStop", Lang.t("handyphone.map.cancel_route"), cx0 + 6, by, half, bh, new Color(0x8E8E93), os::cancelRoute);
		} else {
			button(g, "go", Lang.t("handyphone.map.go"), cx0 + 6, by, half, bh, accent, () -> {
				os.startRoute(s.label(), s.dimension(), s.x(), s.z(), s.contact());
				selected = null;
			});
		}
		if (s.contact() != null) {
			button(g, "gomsg", Lang.t("handyphone.map.message"), cx0 + 10 + half, by, half, bh, Ui.shade(accent, 0.8f), () -> os.openChatWith(s.contact()));
		} else if (saved) {
			// Um local salvo se encaminha como no WhatsApp: escolhe a conversa ou o grupo.
			if (os.data.map.messagesEnabled) {
				button(g, "shareplace", Lang.t("handyphone.map.forward"), cx0 + 10 + half, by, half, bh, Ui.shade(accent, 0.8f), () -> openShare(place, View.MAP));
			} else {
				button(g, "gomsg", Lang.t("handyphone.map.already_saved"), cx0 + 10 + half, by, half, bh, new Color(0x8E8E93), () -> { });
			}
		} else {
			button(g, "saveSel", Lang.t("handyphone.map.save_place"), cx0 + 10 + half, by, half, bh, Ui.shade(accent, 0.8f), () -> beginNaming(s, View.MAP));
		}
	}

	// ------------------------------------------------------------------ nomear e salvar um local

	private void beginNaming(Sel point, View back) {
		naming = point;
		nameBack = back;
		editingId = 0;
		nameColor = 0;
		placeName.set(point.label());
		view = View.NAME;
		focus = placeName;
	}

	/** Edita o nome e a cor de um local salvo (as coordenadas não mudam). */
	private void beginEditing(Place p) {
		naming = new Sel(p.name(), p.dimension(), p.x(), p.hasY(), p.y(), p.z(), null);
		nameBack = View.PLACES;
		editingId = p.id();
		nameColor = p.color();
		placeName.set(p.name());
		view = View.NAME;
		focus = placeName;
	}

	private void saveNamed(PhoneOS os) {
		if (naming == null) {
			return;
		}
		if (placeName.value().isEmpty()) {
			os.mapNotice(Lang.t("handyphone.map.name_missing"), true);
			return;
		}
		if (editingId != 0) {
			os.backend.editPlace(editingId, placeName.value(), nameColor);
		} else {
			os.backend.savePlace(placeName.value(), naming.dimension(), naming.x(), naming.hasY(), naming.y(), naming.z(), nameColor);
		}
		os.playSound(PhoneSound.CONTACT_SAVED);
		focus = null;
		view = nameBack;
	}

	private void drawName(Graphics2D g, PhoneOS os, float x, float y, float w, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, Lang.t(editingId != 0 ? "handyphone.map.place.edit_title" : "handyphone.map.save_place"), rx + 2, y + 18);
		if (naming != null) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			Ui.text(g, Geo.dimensionName(naming.dimension()) + "  " + Geo.coords(naming.x(), naming.hasY(), naming.y(), naming.z()), rx + 2, y + 30);
		}
		field(g, os, "placename", placeName, Lang.t("handyphone.map.place.name_hint"), rx, y + 38, rw, 20, accent);
		// Paleta: toque na cor para escolher.
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7f));
		Ui.text(g, Lang.t("handyphone.map.place.color"), rx + 2, y + 72);
		int colors = Geo.placeColorCount();
		float sw = 15, gap = (rw - colors * sw) / (colors - 1);
		for (int i = 0; i < colors; i++) {
			int index = i;
			float sx0 = rx + i * (sw + gap), sy0 = y + 77;
			hit("color" + i, sx0 - 1, sy0 - 1, sw + 2, sw + 2, PhoneSound.SELECT, () -> nameColor = index);
			g.setColor(Geo.placeColor(i));
			g.fill(new Ellipse2D.Float(sx0, sy0, sw, sw));
			if (nameColor == i) {
				g.setColor(Color.WHITE);
				g.setStroke(new BasicStroke(1.6f));
				g.draw(new Ellipse2D.Float(sx0 + 2.2f, sy0 + 2.2f, sw - 4.4f, sw - 4.4f));
				g.setColor(INK);
				g.setStroke(new BasicStroke(1f));
				g.draw(new Ellipse2D.Float(sx0 - 1.2f, sy0 - 1.2f, sw + 2.4f, sw + 2.4f));
			}
		}
		button(g, "savename", Lang.t(editingId != 0 ? "handyphone.map.place.save_changes" : "handyphone.save"), rx, y + 102, rw, 22, accent, () -> saveNamed(os));
		int count = os.data.places.size();
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.2f));
		Ui.text(g, Lang.t("handyphone.map.place.count", Integer.toString(count), Integer.toString(os.data.map.maxPlaces)), rx + 2, y + 138);
	}

	// ------------------------------------------------------------------ locais salvos

	private void drawPlaces(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN, rowH = 32;
		PhoneData data = os.data;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.map.places.title"), rx + 2, y + 15);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.2f));
		String count = data.places.size() + "/" + data.map.maxPlaces;
		Ui.text(g, count, rx + rw - Ui.textWidth(g, count), y + 15);
		button(g, "saveNow", Lang.t("handyphone.map.places.save_now"), rx, y + 22, rw, 20, accent, () -> {
			PhoneData.SelfPos s = data.self;
			if (s.known) {
				beginNaming(new Sel("", s.dimension, (int) Math.floor(s.x), true, (int) Math.floor(s.y), (int) Math.floor(s.z), null), View.PLACES);
			} else {
				os.mapNotice(Lang.t("handyphone.map.unknown_position"), true);
			}
		});

		float top = y + 48, viewH = h - 48;
		placesMax = Math.max(0, data.places.size() * rowH - viewH);
		placesScroll = Math.max(0, Math.min(placesScroll, placesMax));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (data.places.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			Ui.centered(g, Lang.t("handyphone.map.places.empty.line1"), x + w / 2f, top + 22);
			Ui.centered(g, Lang.t("handyphone.map.places.empty.line2"), x + w / 2f, top + 34);
		}
		for (int i = 0; i < data.places.size(); i++) {
			Place p = data.places.get(i);
			float ry = top + i * rowH - placesScroll;
			if (ry + rowH < top || ry > top + viewH) {
				continue;
			}
			boolean canShare = data.map.messagesEnabled;
			float actions = canShare ? 66 : 44;
			boolean down = hit("place:" + p.id(), rx, Math.max(ry, top), rw - actions, rowH - 2, () -> {
				openPoint(os, new Sel(p.name(), p.dimension(), p.x(), p.hasY(), p.y(), p.z(), null));
			});
			float bx = rx + rw - 22;
			boolean delDown = hit("del:" + p.id(), bx, Math.max(ry, top), 22, rowH - 2, PhoneSound.BUTTON, () -> {
				os.backend.deletePlace(p.id());
				os.playSound(PhoneSound.CONTACT_REMOVED);
			});
			bx -= 22;
			boolean shareDown = canShare && hit("share:" + p.id(), bx, Math.max(ry, top), 22, rowH - 2, PhoneSound.BUTTON, () -> openShare(p, View.PLACES));
			if (canShare) {
				bx -= 22;
			}
			boolean editDown = hit("edit:" + p.id(), bx, Math.max(ry, top), 22, rowH - 2, PhoneSound.BUTTON, () -> beginEditing(p));
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, rowH - 2, 8, 8));
			pin(g, rx + 12, ry + 20, 0.85f, Geo.placeColor(p.color()));
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 8.8f));
			Ui.text(g, fit(g, p.name(), rw - 30 - actions), rx + 24, ry + 12.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			String distance = Geo.distanceFrom(data.self, p.dimension(), p.x(), p.z());
			Ui.text(g, fit(g, Geo.coords(p.x(), p.hasY(), p.y(), p.z()) + (distance.isEmpty() ? "" : " · " + distance), rw - 30 - actions), rx + 24, ry + 23.5f);
			float cy = ry + rowH / 2f - 1;
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			// Apagar (x).
			g.setColor(delDown ? RED : MUTED);
			float dx = rx + rw - 11;
			g.draw(new Line2D.Float(dx - 3, cy - 3, dx + 3, cy + 3));
			g.draw(new Line2D.Float(dx + 3, cy - 3, dx - 3, cy + 3));
			// Encaminhar (seta curva para a direita, como o encaminhar das conversas).
			if (canShare) {
				g.setColor(shareDown ? accent : MUTED);
				float fx = rx + rw - 33;
				// Seta curva para a frente (o ícone de encaminhar das conversas): sobe e vira para a direita.
				Path2D fwd = new Path2D.Float();
				fwd.moveTo(fx - 4.5f, cy + 3.5f);
				fwd.curveTo(fx - 4.5f, cy - 2f, fx - 1.5f, cy - 2.6f, fx + 2f, cy - 2.6f);
				g.draw(fwd);
				Path2D head = new Path2D.Float();
				head.moveTo(fx, cy - 6.2f);
				head.lineTo(fx + 4.6f, cy - 2.6f);
				head.lineTo(fx, cy + 1f);
				g.draw(head);
			}
			// Editar (lápis).
			g.setColor(editDown ? accent : MUTED);
			float ex = bx + 11;
			// Lápis: corpo grosso na diagonal, ponta triangular e a borracha na outra ponta.
			g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Float(ex - 0.6f, cy + 0.6f, ex + 3.2f, cy - 3.2f));
			Path2D tip = new Path2D.Float();
			tip.moveTo(ex - 4.2f, cy + 4.2f);
			tip.lineTo(ex - 2.9f, cy - 0.3f);
			tip.lineTo(ex + 0.3f, cy + 2.9f);
			tip.closePath();
			g.fill(tip);
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new Line2D.Float(ex + 3.7f, cy - 4.4f, ex + 5.1f, cy - 3f));
		}
		g.setClip(oldClip);
	}

	// ------------------------------------------------------------------ encaminhar um local salvo

	private void openShare(Place p, View back) {
		sharing = p;
		shareBack = back;
		shareScroll = 0;
		view = View.SHARE_PLACE;
	}

	/** A lista de conversas e grupos: tocar em uma manda o local para ela (como o encaminhar do WhatsApp). */
	private void drawSharePlace(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN, rowH = 28;
		if (sharing == null) {
			view = shareBack;
			return;
		}
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.map.forward.title"), rx + 2, y + 15);
		pin(g, rx + 9, y + 36, 0.8f, Geo.placeColor(sharing.color()));
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 8.4f));
		Ui.text(g, fit(g, sharing.name(), rw - 24), rx + 21, y + 29);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7f));
		Ui.text(g, fit(g, Geo.coords(sharing.x(), sharing.hasY(), sharing.y(), sharing.z()), rw - 24), rx + 21, y + 38.5f);
		g.setFont(Ui.font(Font.BOLD, 7f));
		Ui.text(g, Lang.t("handyphone.map.forward.to"), rx + 2, y + 54);

		float top = y + 60, viewH = h - 60;
		java.util.List<Conversation> list = os.data.conversations;
		shareMax = Math.max(0, list.size() * rowH - viewH);
		shareScroll = Math.max(0, Math.min(shareScroll, shareMax));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (list.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.2f));
			Ui.centered(g, Lang.t("handyphone.messages.empty"), x + w / 2f, top + 20);
			Ui.centered(g, Lang.t("handyphone.map.forward.empty.line2"), x + w / 2f, top + 32);
		}
		Place chosen = sharing;
		for (int i = 0; i < list.size(); i++) {
			Conversation c = list.get(i);
			float ry = top + i * rowH - shareScroll;
			if (ry + rowH < top || ry > top + viewH) {
				continue;
			}
			String title = os.data.titleOf(c);
			boolean down = hit("to:" + c.id, rx, Math.max(ry, top), rw, rowH - 2, PhoneSound.BUTTON, () -> {
				os.backend.sendPlace(c.id, chosen.id());
				os.playSound(PhoneSound.MESSAGE_SENT);
				os.mapNotice(Lang.t("handyphone.map.forward.sent", chosen.name(), title), false);
				view = shareBack;
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, rowH - 2, 8, 8));
			avatar(g, c.group ? "g" + c.id : String.valueOf(os.data.peerOf(c)), c.group ? "#" : title, rx + 14, ry + rowH / 2f - 1, 9);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 8.8f));
			Ui.text(g, fit(g, title, rw - 56), rx + 29, ry + 11.5f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			Ui.text(g, c.group ? Lang.plural("handyphone.map.members", c.members.size()) : Lang.t("handyphone.chat.default_title"), rx + 29, ry + 21.5f);
		}
		g.setClip(oldClip);
	}

	/** Volta ao mapa mostrando um ponto. */
	private void openPoint(PhoneOS os, Sel s) {
		view = View.MAP;
		selected = s;
		if (s.dimension().equals(os.data.map.dimension) || os.data.map.dimension.isEmpty()) {
			follow = false;
			centerX = s.x() + 0.5;
			centerZ = s.z() + 0.5;
		}
	}

	// ------------------------------------------------------------------ ao vivo

	private void drawLive(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		float scrollOffset = -liveScroll;
		float cy = y + scrollOffset;
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, y, w, h));

		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.map.live.title"), rx + 2, cy + 15);
		cy += 24;

		// Quem compartilha com o jogador.
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7f));
		Ui.text(g, Lang.t("handyphone.map.live.with_you"), rx + 2, cy + 6);
		cy += 12;
		if (data.live.isEmpty()) {
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.text(g, Lang.t("handyphone.map.live.nobody"), rx + 2, cy + 8);
			cy += 16;
		}
		for (Live l : data.live) {
			String name = data.displayName(l.number());
			boolean down = hit("live:" + l.number(), rx, Math.max(cy, y), rw, 28, () -> {
				if (l.state() == PhoneData.LIVE_POSITION) {
					openPoint(os, new Sel(name, l.dimension(), l.x(), true, l.y(), l.z(), l.number()));
				} else {
					os.mapNotice(Lang.t("handyphone.map.live.notice", name, liveStatus(os, l)), false);
				}
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, cy, rw, 28, 8, 8));
			avatar(g, l.number(), name, rx + 14, cy + 14, 9);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 8.8f));
			Ui.text(g, fit(g, name, rw - 36), rx + 28, cy + 12);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7f));
			Ui.text(g, fit(g, Lang.t("handyphone.map.live.row", liveStatus(os, l), Geo.remaining(l.until() - System.currentTimeMillis())), rw - 36), rx + 28, cy + 22.5f);
			cy += 31;
		}
		cy += 6;

		// O compartilhamento do próprio jogador.
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7f));
		Ui.text(g, Lang.t("handyphone.map.live.yours"), rx + 2, cy + 6);
		cy += 12;
		if (!data.map.liveEnabled) {
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.text(g, Lang.t("handyphone.map.live.off"), rx + 2, cy + 8);
			cy += 16;
		} else if (data.share.active) {
			RoundRectangle2D box = new RoundRectangle2D.Float(rx, cy, rw, 44, 10, 10);
			g.setColor(new Color(0xFBE9E7));
			g.fill(box);
			g.setColor(RED);
			g.setStroke(new BasicStroke(0.8f));
			g.draw(box);
			g.fill(new Ellipse2D.Float(rx + 8, cy + 8, 7, 7));
			g.setFont(Ui.font(Font.BOLD, 8.6f));
			Ui.text(g, Lang.t("handyphone.map.live.now"), rx + 20, cy + 15);
			g.setColor(INK);
			g.setFont(Ui.font(Font.PLAIN, 7.2f));
			List<String> names = new ArrayList<>();
			for (String number : data.share.audience) {
				names.add(data.displayName(number));
			}
			Ui.text(g, fit(g, Lang.t("handyphone.map.live.with", String.join(", ", names)), rw - 16), rx + 8, cy + 27);
			g.setColor(MUTED);
			Ui.text(g, Lang.t("handyphone.map.live.left", Geo.remaining(data.share.until - System.currentTimeMillis())), rx + 8, cy + 38);
			cy += 50;
			button(g, "stopshare", Lang.t("handyphone.map.live.stop"), rx, cy, rw, 22, RED, () -> os.backend.shareStop());
			cy += 28;
		} else {
			g.setColor(INK);
			g.setFont(Ui.font(Font.PLAIN, 7.4f));
			Ui.text(g, Lang.t("handyphone.map.live.hint1"), rx + 2, cy + 8);
			Ui.text(g, Lang.t("handyphone.map.live.hint2"), rx + 2, cy + 18);
			cy += 26;
			cy = drawAudiencePicker(g, os, rx, rw, cy, accent);
			// Prazo.
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.BOLD, 7f));
			Ui.text(g, Lang.t("handyphone.map.live.duration"), rx + 2, cy + 6);
			cy += 10;
			String[] labels = {Lang.t("handyphone.map.live.duration.0"), Lang.t("handyphone.map.live.duration.1"), Lang.t("handyphone.map.live.duration.2")};
			float cw = (rw - 8) / 3f;
			for (int i = 0; i < 3; i++) {
				int index = i;
				boolean on = shareDuration == i;
				hit("dur" + i, rx + i * (cw + 4), cy, cw, 18, PhoneSound.SELECT, () -> shareDuration = index);
				g.setColor(on ? accent : Color.WHITE);
				g.fill(new RoundRectangle2D.Float(rx + i * (cw + 4), cy, cw, 18, 9, 9));
				g.setColor(on ? Color.WHITE : INK);
				g.setFont(Ui.font(Font.BOLD, 7.6f));
				Ui.centered(g, labels[i], rx + i * (cw + 4) + cw / 2f, cy + 12);
			}
			cy += 26;
			button(g, "startshare", Lang.t("handyphone.map.live.start"), rx, cy, rw, 22, RED, () -> startShare(os));
			cy += 28;
		}
		g.setClip(oldClip);
		liveMax = Math.max(0, cy + liveScroll - (y + h) + 6);
	}

	private String liveStatus(PhoneOS os, Live l) {
		return switch (l.state()) {
			case PhoneData.LIVE_POSITION -> Geo.distanceFrom(os.data.self, l.dimension(), l.x(), l.z());
			case PhoneData.LIVE_OTHER_DIMENSION -> Lang.t("handyphone.map.live.status.other_dimension", Geo.dimensionName(l.dimension()));
			case PhoneData.LIVE_PAUSED -> Lang.t("handyphone.map.live.status.paused");
			default -> Lang.t("handyphone.map.live.status.sharing");
		};
	}

	/** Grupos (escolhe um) e contatos (escolhe vários). Retorna o y seguinte. */
	private float drawAudiencePicker(Graphics2D g, PhoneOS os, float rx, float rw, float cy, Color accent) {
		PhoneData data = os.data;
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7f));
		Ui.text(g, Lang.t("handyphone.map.live.with_whom"), rx + 2, cy + 6);
		cy += 10;
		boolean any = false;
		for (Conversation c : data.conversations) {
			if (!c.group) {
				continue;
			}
			any = true;
			boolean on = shareGroup == c.id;
			cy = pickRow(g, "grp:" + c.id, Lang.t("handyphone.map.live.group", c.title), "#", c.title, on, rx, rw, cy, accent, () -> {
				shareGroup = on ? 0 : c.id;
				shareNumbers.clear();
			});
		}
		for (Map.Entry<String, String> contact : data.contacts.entrySet()) {
			any = true;
			String number = contact.getKey();
			boolean on = shareGroup == 0 && shareNumbers.contains(number);
			cy = pickRow(g, "ct:" + number, contact.getValue(), number, contact.getValue(), on, rx, rw, cy, accent, () -> {
				shareGroup = 0;
				if (!shareNumbers.remove(number)) {
					shareNumbers.add(number);
				}
			});
		}
		if (!any) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.text(g, Lang.t("handyphone.map.live.save_contact_first"), rx + 2, cy + 8);
			cy += 14;
		}
		return cy + 4;
	}

	private float pickRow(Graphics2D g, String key, String label, String colorKey, String initialOf, boolean on, float rx, float rw, float cy, Color accent, Runnable action) {
		boolean down = hit(key, rx, cy, rw, 22, PhoneSound.SELECT, action);
		g.setColor(down ? PRESSED : Color.WHITE);
		g.fill(new RoundRectangle2D.Float(rx, cy, rw, 21, 8, 8));
		avatar(g, colorKey, initialOf, rx + 12, cy + 10.5f, 7);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 8.4f));
		Ui.text(g, fit(g, label, rw - 48), rx + 24, cy + 13.5f);
		float cx = rx + rw - 12, cm = cy + 10.5f;
		g.setStroke(new BasicStroke(1.1f));
		g.setColor(on ? accent : new Color(0xA9ABB5));
		if (on) {
			g.fill(new Ellipse2D.Float(cx - 5.5f, cm - 5.5f, 11, 11));
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D check = new Path2D.Float();
			check.moveTo(cx - 2.6f, cm + 0.2f);
			check.lineTo(cx - 0.8f, cm + 2.2f);
			check.lineTo(cx + 2.8f, cm - 2f);
			g.draw(check);
		} else {
			g.draw(new Ellipse2D.Float(cx - 5.5f, cm - 5.5f, 11, 11));
		}
		return cy + 24;
	}

	private void startShare(PhoneOS os) {
		if (shareGroup == 0 && shareNumbers.isEmpty()) {
			os.mapNotice(Lang.t("handyphone.map.live.choose"), true);
			return;
		}
		os.backend.shareStart(shareDuration, shareGroup, new ArrayList<>(shareNumbers));
		os.playSound(PhoneSound.SELECT);
	}
}
