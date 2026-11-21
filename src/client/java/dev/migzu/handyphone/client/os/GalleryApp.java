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

	@Override
	void opened(PhoneOS os) {
		view = View.GRID;
		pickFor = -1;
		scroll = 0;
	}

	/** Abre a galeria só para escolher uma foto e mandá-la para essa conversa. */
	void startPick(long conversation) {
		view = View.GRID;
		pickFor = conversation;
		scroll = 0;
	}

	/** Abre direto a escolha da conversa de destino para encaminhar essa foto. */
	void showSend(PhoneOS os, long photoId) {
		viewer.open(photoId, os);
		pickFor = -1;
		sendScroll = 0;
		view = View.SEND;
	}

	@Override
	boolean back(PhoneOS os) {
		if (view == View.SEND) {
			view = View.PHOTO;
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
	void scroll(PhoneOS os, float amount) {
		if (view == View.GRID) {
			float next = Math.max(0, Math.min(scroll - amount * 18, maxScroll));
			if (Math.abs(next - scroll) > 0.5f) {
				scroll = next;
				os.playScrollTick();
			}
		} else if (view == View.SEND) {
			float next = Math.max(0, Math.min(sendScroll - amount * 18, sendMaxScroll));
			if (Math.abs(next - sendScroll) > 0.5f) {
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
				view = View.SEND;
			}, () -> view = View.GRID);
			case SEND -> drawSend(g, os, x, y, w, h, accent);
		}
	}

	// ------------------------------------------------------------------ grade

	private void drawGrid(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		List<GalleryPhoto> photos = data.gallery;
		float headH = 26;

		// Cabeçalho: título e uso da cota.
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t(pickFor >= 0 ? "handyphone.gallery.pick" : "handyphone.gallery.title"), x + MARGIN + 2, y + 12.5f);
		if (data.photoQuotaBytes > 0) {
			float used = Math.min(1f, data.photoUsedBytes / (float) data.photoQuotaBytes);
			String label = Lang.t("handyphone.gallery.quota", mb(data.photoUsedBytes), mb(data.photoQuotaBytes));
			g.setFont(Ui.font(Font.PLAIN, 6.6f));
			g.setColor(MUTED);
			Ui.text(g, label, x + w - MARGIN - Ui.textWidth(g, label), y + 11.5f);
			float barW = w - 2 * MARGIN - 4;
			g.setColor(LINE);
			g.fill(new RoundRectangle2D.Float(x + MARGIN + 2, y + 17, barW, 3, 3, 3));
			g.setColor(used > 0.9f ? new Color(0xC62828) : accent);
			g.fill(new RoundRectangle2D.Float(x + MARGIN + 2, y + 17, Math.max(2, barW * used), 3, 3, 3));
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
			if (data.number.isEmpty()) {
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
			boolean down = hit("photo:" + photo.id(), cx, Math.max(cy, top), cell, Math.min(cell, cy + cell - top), () -> openPhoto(os, photo.id()));
			if (thumb != null) {
				drawCover(g, thumb, cx, cy, cell, cell, 6);
			} else {
				g.setColor(data.photos.isGone(photo.id()) ? new Color(0xE9D5D5) : new Color(0xD3D5DD));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 6, 6));
			}
			if (down) {
				g.setColor(new Color(0, 0, 0, 70));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 6, 6));
			}
		}
		g.setClip(oldClip);
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
			boolean down = hit("to:" + c.id, rx, Math.max(ry, top), rw, ROW_H - 2, () -> os.sendPhotoTo(c.id, viewer.photoId()));
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
