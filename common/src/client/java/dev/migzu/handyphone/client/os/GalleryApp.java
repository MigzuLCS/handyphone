package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.client.os.PhoneData.Conversation;
import dev.migzu.handyphone.client.os.PhoneData.GalleryPhoto;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;

/**
 * App Galeria: grade de miniaturas das fotos do jogador (guardadas no servidor), a foto
 * aberta, e a escolha da conversa para onde enviar. Também serve de "seletor" para o
 * botão de anexar das Mensagens ({@link #startPick}).
 */
final class GalleryApp extends AppContent {
	private enum View { GRID, PHOTO, SEND }

	private static final float MARGIN = 6f, GAP = 3f, ROW_H = 30f;
	private static final int COLUMNS = 3;

	private View view = View.GRID;
	/** Conversa para onde a foto tocada será enviada (modo anexar), ou -1. */
	private long pickFor = -1;
	private float scroll, maxScroll, sendScroll, sendMaxScroll;
	private final PhotoViewer viewer = new PhotoViewer(this);
	/** Modo de seleção (1.3): tocar marca/desmarca; a barra de baixo age sobre as marcadas. */
	private boolean selecting;
	private final java.util.LinkedHashSet<Long> selected = new java.util.LinkedHashSet<>();
	/** Fotos a enviar na escolha da conversa (várias, da seleção); vazio = a foto aberta no visualizador. */
	private final List<Long> sendIds = new java.util.ArrayList<>();
	/** Quantas fotos dá para enviar de uma vez (cada uma é uma mensagem; mais que isso esbarraria no limite de pacotes). */
	static final int MAX_SEND = 10;
	private static final float BAR_H = 30f;

	@Override
	void opened(PhoneOS os) {
		view = View.GRID;
		pickFor = -1;
		scroll = 0;
		endSelection();
	}

	private void endSelection() {
		selecting = false;
		selected.clear();
	}

	/** Abre a galeria só para escolher uma foto e mandá-la para essa conversa. */
	void startPick(long conversation) {
		view = View.GRID;
		pickFor = conversation;
		scroll = 0;
		endSelection();
	}

	/** Abre direto a escolha da conversa de destino para encaminhar essa foto. */
	void showSend(PhoneOS os, long photoId) {
		viewer.open(photoId, os);
		pickFor = -1;
		sendScroll = 0;
		sendIds.clear();
		view = View.SEND;
	}

	@Override
	boolean back(PhoneOS os) {
		if (view == View.SEND) {
			view = sendIds.isEmpty() ? View.PHOTO : View.GRID;
			return true;
		}
		if (selecting) {
			endSelection();
			return true;
		}
		if (view == View.PHOTO) {
			view = View.GRID;
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

	@Override
	void pointerMoved(PhoneOS os, float x, float y) {
		if (view == View.PHOTO) {
			viewer.drag(x, y);
		}
	}

	@Override
	void pointerReleased() {
		viewer.release();
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		if (view == View.GRID) {
			float next = Math.max(0, Math.min(scroll - amount * SCROLL_STEP, maxScroll));
			if (Math.abs(next - scroll) > 0.01f) {
				scroll = next;
				os.playScrollTick();
			}
		} else if (view == View.PHOTO) {
			if (!os.focusMode()) {
				viewer.scroll(os, amount);
			}
		} else if (view == View.SEND) {
			float next = Math.max(0, Math.min(sendScroll - amount * SCROLL_STEP, sendMaxScroll));
			if (Math.abs(next - sendScroll) > 0.01f) {
				sendScroll = next;
				os.playScrollTick();
			}
		}
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		switch (view) {
			case GRID -> drawGrid(g, os, x, y, w, h, accent);
			case PHOTO -> viewer.draw(g, os, x, y, w, h, accent, () -> {
				sendScroll = 0;
				sendIds.clear();
				view = View.SEND;
			}, () -> view = View.GRID);
			case SEND -> drawSend(g, os, x, y, w, h, accent);
		}
	}

	// ------------------------------------------------------------------ grade

	private void drawGrid(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		List<GalleryPhoto> photos = data.gallery;
		float headH = 32;
		// Fotos que sumiram da galeria (apagadas aqui ou noutro lugar) saem da seleção.
		if (!selected.isEmpty()) {
			java.util.Set<Long> present = new java.util.HashSet<>();
			for (GalleryPhoto p : photos) {
				present.add(p.id());
			}
			selected.retainAll(present);
		}
		if (selecting && photos.isEmpty()) {
			endSelection();
		}

		// Cabeçalho: título (ou quantas marcadas), o botão Selecionar/Cancelar e o uso da cota.
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		String title = selecting ? Lang.plural("handyphone.gallery.selected", selected.size())
			: Lang.t(pickFor >= 0 ? "handyphone.gallery.pick" : os.localOnly() ? "handyphone.gallery.local_title" : "handyphone.gallery.title");
		Ui.text(g, title, x + MARGIN + 2, y + 13f);
		if (pickFor < 0 && !photos.isEmpty()) {
			g.setFont(Ui.font(Font.BOLD, 7.6f));
			String label = Lang.t(selecting ? "handyphone.cancel" : "handyphone.gallery.select");
			float pw = Ui.textWidth(g, label) + 14, px = x + w - MARGIN - pw;
			boolean down = hit("select", px, y + 3, pw, 15, PhoneSound.BUTTON, () -> {
				if (selecting) {
					endSelection();
				} else {
					selecting = true;
				}
			});
			Color pill = selecting ? new Color(0xE4E6EC) : accent;
			g.setColor(down ? Ui.shade(pill, 0.85f) : pill);
			g.fill(new RoundRectangle2D.Float(px, y + 3, pw, 15, 15, 15));
			g.setColor(selecting ? INK : Color.WHITE);
			Ui.centered(g, label, px + pw / 2f, y + 13.2f);
		}
		if (data.photoQuotaBytes > 0) {
			float used = Math.min(1f, data.photoUsedBytes / (float) data.photoQuotaBytes);
			String label = Lang.t("handyphone.gallery.quota", mb(data.photoUsedBytes), mb(data.photoQuotaBytes));
			g.setFont(Ui.font(Font.PLAIN, 6.4f));
			float lw = Ui.textWidth(g, label);
			float barW = w - 2 * MARGIN - 4 - lw - 6;
			g.setColor(MUTED);
			Ui.text(g, label, x + w - MARGIN - lw, y + 26.5f);
			g.setColor(LINE);
			g.fill(new RoundRectangle2D.Float(x + MARGIN + 2, y + 23, barW, 3, 3, 3));
			g.setColor(used > 0.9f ? new Color(0xC62828) : accent);
			g.fill(new RoundRectangle2D.Float(x + MARGIN + 2, y + 23, Math.max(2, barW * used), 3, 3, 3));
		}
		if (selecting) {
			drawActions(g, os, x, y + h - BAR_H, w, accent);
			h -= BAR_H;
		}

		boolean uploading = os.photosBusy();
		int cells = photos.size() + (uploading ? 1 : 0);
		float top = y + headH, viewH = h - headH;
		float cell = (w - 2 * MARGIN - (COLUMNS - 1) * GAP) / COLUMNS;
		int rows = (cells + COLUMNS - 1) / COLUMNS;
		float footer = cells == 0 ? 0 : 6;
		maxScroll = Math.max(0, rows * (cell + GAP) - GAP + footer - viewH);
		scroll = Math.max(0, Math.min(scroll, maxScroll));

		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (cells == 0) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			if (os.localOnly()) {
				Ui.centered(g, Lang.t("handyphone.gallery.empty.line1"), x + w / 2f, top + 28);
				Ui.centered(g, Lang.t("handyphone.gallery.local_empty"), x + w / 2f, top + 40);
			} else if (data.number.isEmpty()) {
				Ui.centered(g, Lang.t("handyphone.no_signal.line1"), x + w / 2f, top + 28);
				Ui.centered(g, Lang.t("handyphone.no_signal.line2"), x + w / 2f, top + 40);
			} else {
				Ui.centered(g, Lang.t("handyphone.gallery.empty.line1"), x + w / 2f, top + 28);
				Ui.centered(g, Lang.t("handyphone.gallery.empty.line2"), x + w / 2f, top + 40);
				if (data.importAllowed && data.photoQuotaBytes > 0) {
					g.setFont(Ui.font(Font.PLAIN, 7.4f));
					Ui.centered(g, Lang.t("handyphone.gallery.empty.line3"), x + w / 2f, top + 58);
					Ui.centered(g, Lang.t("handyphone.gallery.empty.line4"), x + w / 2f, top + 68);
				}
			}
		}
		for (int i = 0; i < cells; i++) {
			float cx = x + MARGIN + (i % COLUMNS) * (cell + GAP);
			float cy = top + (i / COLUMNS) * (cell + GAP) - scroll;
			if (cy + cell < top || cy > top + viewH) {
				continue;
			}
			if (uploading && i == 0) {
				drawUploading(g, os, cx, cy, cell, accent);
				continue;
			}
			GalleryPhoto photo = photos.get(i - (uploading ? 1 : 0));
			BufferedImage thumb = data.photos.thumb(photo.id(), os.backend, os.time);
			float hy = Math.max(cy, top), hh = Math.min(cy + cell, top + viewH) - hy;
			boolean down = hit("photo:" + photo.id(), cx, hy, cell, hh, selecting ? PhoneSound.SELECT : PhoneSound.LIST_ITEM, () -> {
				if (selecting) {
					if (!selected.remove(photo.id())) {
						selected.add(photo.id());
					}
				} else {
					openPhoto(os, photo.id());
				}
			});
			if (thumb != null) {
				drawCover(g, thumb, cx, cy, cell, cell, 6);
			} else {
				g.setColor(data.photos.isGone(photo.id()) ? new Color(0xE9D5D5) : new Color(0xD3D5DD));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 6, 6));
			}
			boolean marked = selecting && selected.contains(photo.id());
			if (down || marked) {
				g.setColor(new Color(0, 0, 0, marked ? 90 : 70));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 6, 6));
			}
			if (selecting) {
				drawCheck(g, cx + cell - 10, cy + 10, marked, accent);
			}
		}
		g.setClip(oldClip);
	}

	/** A bolinha de marcar no canto da miniatura: vazia com aro branco, ou cheia na cor de destaque com o visto. */
	private static void drawCheck(Graphics2D g, float cx, float cy, boolean on, Color accent) {
		float r = 6.2f;
		java.awt.geom.Ellipse2D circle = new java.awt.geom.Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
		g.setColor(on ? accent : new Color(0, 0, 0, 60));
		g.fill(circle);
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.2f));
		g.draw(circle);
		if (on) {
			g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			java.awt.geom.Path2D tick = new java.awt.geom.Path2D.Float();
			tick.moveTo(cx - 2.9f, cy + 0.2f);
			tick.lineTo(cx - 0.8f, cy + 2.3f);
			tick.lineTo(cx + 3f, cy - 2.2f);
			g.draw(tick);
		}
	}

	/** Barra de baixo da seleção: Enviar (até {@link #MAX_SEND}) e Apagar (com confirmação). */
	private void drawActions(Graphics2D g, PhoneOS os, float x, float y, float w, Color accent) {
		g.setColor(new Color(0xF3F4F7));
		g.fill(new Rectangle2D.Float(x, y, w, BAR_H));
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, y, w, 0.6f));
		int n = selected.size();
		float bw = (w - 4 * MARGIN) / 3f, by = y + 5, bh = BAR_H - 10;
		actionButton(g, "sel:send", Lang.t("handyphone.gallery.action.send"), x + MARGIN, by, bw, bh, n > 0 && !os.localOnly() ? accent : null, () -> {
			if (n > MAX_SEND) {
				os.showError(Lang.t("handyphone.gallery.send_limit", String.valueOf(MAX_SEND)));
				return;
			}
			sendIds.clear();
			sendIds.addAll(selected);
			sendScroll = 0;
			view = View.SEND;
		});
		actionButton(g, "sel:save", Lang.t("handyphone.gallery.action.save"), x + 2 * MARGIN + bw, by, bw, bh, n > 0 ? new Color(0x5B6170) : null, () -> {
			// As mais antigas primeiro (os arquivos saem na ordem em que as fotos foram tiradas).
			List<Long> ids = new java.util.ArrayList<>(selected);
			java.util.Collections.sort(ids);
			os.exportPhotos(ids);
			endSelection();
		});
		actionButton(g, "sel:delete", Lang.t("handyphone.gallery.action.delete"), x + 3 * MARGIN + 2 * bw, by, bw, bh, n > 0 ? new Color(0xC62828) : null, () -> {
			List<Long> ids = List.copyOf(selected);
			os.confirm(Lang.plural("handyphone.gallery.delete.title", ids.size()),
				Lang.t(os.localOnly() ? "handyphone.gallery.delete.message_local" : "handyphone.gallery.delete.message"),
				Lang.t("handyphone.gallery.action.delete"), true, () -> {
					os.deletePhotos(ids);
					endSelection();
				});
		});
	}

	/** Botão da barra; sem cor = desligado (cinza, sem área de toque). */
	private void actionButton(Graphics2D g, String key, String label, float x, float y, float w, float h, Color color, Runnable action) {
		if (color == null) {
			g.setColor(new Color(0xD9DBE2));
			g.fill(new RoundRectangle2D.Float(x, y, w, h, 8, 8));
			g.setColor(Color.WHITE);
			g.setFont(Ui.font(Font.BOLD, 8.6f));
			Ui.centered(g, label, x + w / 2f, y + h / 2f + 3.1f);
			return;
		}
		button(g, key, label, x, y, w, h, color, action);
	}

	/** Casinha cinza com um anel girando: uma foto está sendo reduzida/enviada. */
	private void drawUploading(Graphics2D g, PhoneOS os, float x, float y, float cell, Color accent) {
		g.setColor(new Color(0xE2E4EA));
		g.fill(new RoundRectangle2D.Float(x, y, cell, cell, 6, 6));
		float cx = x + cell / 2f, cy = y + cell / 2f - 3, r = 6;
		g.setColor(accent);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int start = (int) (os.time * 360) % 360;
		g.draw(new java.awt.geom.Arc2D.Float(cx - r, cy - r, r * 2, r * 2, -start, 260, java.awt.geom.Arc2D.OPEN));
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 6.4f));
		Ui.centered(g, Lang.t("handyphone.gallery.sending"), cx, cy + r + 9);
	}

	private void openPhoto(PhoneOS os, long id) {
		if (pickFor >= 0) {
			long conversation = pickFor;
			pickFor = -1;
			os.sendPhotoTo(conversation, id);
			return;
		}
		viewer.open(id, os);
		view = View.PHOTO;
	}

	private static String mb(long bytes) {
		return Lang.t("handyphone.size.mb", Lang.decimal(bytes / 1048576.0));
	}

	// ------------------------------------------------------------------ escolher a conversa de destino

	private void drawSend(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN + 2, rw = w - 2 * MARGIN - 4;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.gallery.send_to"), rx, y + 14);
		List<Conversation> list = os.data.conversations;
		float top = y + 22, viewH = h - 22;
		sendMaxScroll = Math.max(0, list.size() * ROW_H - viewH);
		sendScroll = Math.max(0, Math.min(sendScroll, sendMaxScroll));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (list.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.2f));
			Ui.centered(g, Lang.t("handyphone.gallery.no_chats"), x + w / 2f, top + 22);
		}
		for (int i = 0; i < list.size(); i++) {
			Conversation c = list.get(i);
			float ry = top + i * ROW_H - sendScroll;
			if (ry + ROW_H < top || ry > top + viewH) {
				continue;
			}
			boolean down = hit("to:" + c.id, rx, Math.max(ry, top), rw, ROW_H - 2, () -> {
				if (sendIds.isEmpty()) {
					os.sendPhotoTo(c.id, viewer.photoId());
				} else {
					List<Long> ids = List.copyOf(sendIds);
					sendIds.clear();
					endSelection();
					view = View.GRID;
					os.sendPhotosTo(c.id, ids);
				}
			});
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(new RoundRectangle2D.Float(rx, ry, rw, ROW_H - 2, 8, 8));
			String title = os.data.titleOf(c);
			avatar(g, c.group ? "g" + c.id : String.valueOf(os.data.peerOf(c)), c.group ? "#" : title, rx + 14, ry + ROW_H / 2f - 1, 9.5f);
			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9f));
			Ui.text(g, fit(g, title, rw - 36), rx + 29, ry + ROW_H / 2f + 2.5f);
		}
		g.setClip(oldClip);
	}
}
