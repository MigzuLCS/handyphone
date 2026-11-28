package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.client.os.PhoneData.FeedComment;
import dev.migzu.handyphone.client.os.PhoneData.FeedList;
import dev.migzu.handyphone.client.os.PhoneData.FeedPost;
import dev.migzu.handyphone.client.os.PhoneData.GalleryPhoto;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * App scroll.in (1.4): fotos públicas do servidor. O feed de todos (páginas pequenas, só as miniaturas e fotos do que está à vista),
 * a publicação aberta com os comentários, o perfil de quem foi tocado (grade) e o publicar (escolher uma foto da galeria e a legenda).
 * Tudo vem do servidor; o celular só pede e desenha.
 */
final class FeedApp extends AppContent {
	private enum View { FEED, POST, PROFILE, PICK, CAPTION }

	private static final float MARGIN = 6f, CARD_GAP = 10f, BAR_H = 24f;
	private static final int GRID_COLUMNS = 3;
	/** Faltando isto (em pontos) para o fim da lista, a próxima página é pedida. */
	private static final float PREFETCH = 120f;
	/** Segundos sem resposta até uma página pedida poder ser pedida de novo (o servidor pode ter descartado o pedido). */
	private static final double LOADING_TIMEOUT = 6;

	private View view = View.FEED;
	/** Telas anteriores, para o voltar (feed -> perfil -> publicação -> perfil...). */
	private final ArrayDeque<View> stack = new ArrayDeque<>();
	private final ArrayDeque<Long> postStack = new ArrayDeque<>();
	private float feedScroll, feedMax, postScroll, postMax, profileScroll, profileMax, pickScroll, pickMax;
	/** Publicação aberta (id). */
	private long postId;
	/** De onde veio a publicação aberta (para achar a versão mais nova dela). */
	private FeedList postFrom;
	/** Foto escolhida para publicar. */
	private long pickPhoto;
	private final Field caption = new Field(256, false);
	private final Field comment = new Field(150, false);
	/** Publicação com o menu "⋮" aberto (apagar, denunciar e, para administradores, o id), ou null. */
	private FeedPost menuPost;

	@Override
	void opened(PhoneOS os) {
		menuPost = null;
		view = View.FEED;
		stack.clear();
		postStack.clear();
		feedScroll = 0;
		focus = null;
		refresh(os);
	}

	/** Pede a primeira página de novo (abrir o app, publicar). */
	private static void refresh(PhoneOS os) {
		os.data.feed.loading = true;
		os.data.feed.loadingAt = os.time;
		os.backend.feedGet("", 0);
	}

	private void push(View next) {
		stack.push(view);
		postStack.push(postId);
		view = next;
		focus = null;
	}

	@Override
	boolean back(PhoneOS os) {
		if (menuPost != null) {
			menuPost = null;
			return true;
		}
		if (stack.isEmpty()) {
			return false;
		}
		view = stack.pop();
		postId = postStack.pop();
		focus = null;
		return true;
	}

	@Override
	String firstFocus() {
		if (menuPost != null) {
			return menuPost.mine() ? "sheet:delete" : "sheet:report";
		}
		return switch (view) {
			case CAPTION -> "caption";
			default -> null;
		};
	}

	@Override
	void key(PhoneOS os, PhoneOS.Key key) {
		super.key(os, key);
		if (key == PhoneOS.Key.ENTER) {
			if (view == View.POST && focus == comment) {
				sendComment(os);
			} else if (view == View.CAPTION && focus == caption) {
				publish(os);
			}
		}
	}

	@Override
	void scroll(PhoneOS os, float amount) {
		float d = -amount * SCROLL_STEP;
		switch (view) {
			case FEED -> feedScroll = roll(os, feedScroll, d, feedMax);
			case POST -> postScroll = roll(os, postScroll, d, postMax);
			case PROFILE -> profileScroll = roll(os, profileScroll, d, profileMax);
			case PICK -> pickScroll = roll(os, pickScroll, d, pickMax);
			default -> { }
		}
	}

	private static float roll(PhoneOS os, float value, float delta, float max) {
		float next = Math.max(0, Math.min(value + delta, max));
		if (Math.abs(next - value) > 0.01f) {
			os.playScrollTick();
		}
		return next;
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		switch (view) {
			case FEED -> drawFeed(g, os, x, y, w, h, accent);
			case POST -> drawPost(g, os, x, y, w, h, accent);
			case PROFILE -> drawProfile(g, os, x, y, w, h, accent);
			case PICK -> drawPick(g, os, x, y, w, h, accent);
			case CAPTION -> drawCaption(g, os, x, y, w, h, accent);
		}
		if (menuPost != null) {
			drawMenu(g, os, x, y, w, h, accent);
		}
	}

	/** O menu "⋮" de uma publicação, como uma folha que sobe de baixo: apagar (a própria) ou denunciar, e o id para administradores. */
	private void drawMenu(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		FeedPost post = menuPost;
		// Só a folha recebe toques: o que está por baixo sai (também do destaque da roda). Tocar fora dela fecha o menu.
		clearHits();
		g.setColor(new Color(0, 0, 0, 90));
		g.fill(new Rectangle2D.Float(x, y, w, h));
		boolean admin = os.data.feedAdmin;
		float bh = 22, pad = 8;
		float sheetH = pad + 14 + (admin ? 24 : 0) + 2 * (bh + 6) + pad;
		float sy = y + h - sheetH;
		hit("sheet:dismiss", x, y, w, h, null, () -> {
			if (pointerY < sy) {
				menuPost = null;
			}
		});
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(x + 3, sy, w - 6, sheetH + 10, 14, 14));
		float cx = x + pad + 3, cw = w - 2 * pad - 6, cy = sy + pad;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 8.8f));
		Ui.text(g, fit(g, post.name(), cw), cx, cy + 9);
		cy += 14;
		if (admin) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.2f));
			Ui.text(g, Lang.t("handyphone.feed.admin_id", String.valueOf(post.id())), cx, cy + 8);
			g.setFont(Ui.font(Font.PLAIN, 6.6f));
			Ui.text(g, fit(g, "/handyphone post remove " + post.id(), cw), cx, cy + 18);
			cy += 24;
		}
		if (post.mine()) {
			button(g, "sheet:delete", Lang.t("handyphone.feed.delete.action"), cx, cy, cw, bh, new Color(0xC62828), () -> {
				menuPost = null;
				askDelete(os, post);
			});
		} else {
			button(g, "sheet:report", Lang.t("handyphone.feed.report.action"), cx, cy, cw, bh, new Color(0xC62828), () -> {
				menuPost = null;
				askReport(os, post);
			});
		}
		cy += bh + 6;
		button(g, "sheet:cancel", Lang.t("handyphone.cancel"), cx, cy, cw, bh, new Color(0x8A8F9C), () -> menuPost = null);
	}

	private void askReport(PhoneOS os, FeedPost post) {
		os.confirm(Lang.t("handyphone.feed.report.title"), Lang.t("handyphone.feed.report.message"), Lang.t("handyphone.feed.report.action"), true,
			() -> {
				os.backend.feedReport(post.id());
				os.data.feed.posts.removeIf(p -> p.id() == post.id());
				os.data.profile.posts.removeIf(p -> p.id() == post.id());
				if (view == View.POST && postId == post.id()) {
					back(os);
				}
				os.feedNotice(Lang.t("handyphone.feed.report.sent"));
			});
	}

	// ------------------------------------------------------------------ feed

	private void drawFeed(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		PhoneData data = os.data;
		// Barra de cima: título, o próprio perfil e Publicar.
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.feed.recent"), x + MARGIN + 2, y + 14f);
		g.setFont(Ui.font(Font.BOLD, 7.6f));
		String label = "+ " + Lang.t("handyphone.feed.publish");
		float pw = Ui.textWidth(g, label) + 14, px = x + w - MARGIN - pw;
		boolean down = hit("publish", px, y + 4, pw, 15, PhoneSound.BUTTON, () -> startPublish(os));
		g.setColor(down ? Ui.shade(accent, 0.85f) : accent);
		g.fill(new RoundRectangle2D.Float(px, y + 4, pw, 15, 15, 15));
		g.setColor(Color.WHITE);
		Ui.centered(g, label, px + pw / 2f, y + 14.2f);
		if (!data.feedMe.isEmpty()) {
			float r = 7.5f, cx = px - 6 - r, cy = y + 11.5f;
			boolean me = hit("me", cx - r - 2, cy - r - 2, 2 * r + 4, 2 * r + 4, PhoneSound.BUTTON, () -> openProfile(os, data.feedMe, data.feedMeName));
			avatar(g, data.feedMe, data.feedMeName, cx, cy, r);
			if (me) {
				g.setColor(new Color(0, 0, 0, 60));
				g.fill(new Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r));
			}
		}

		float top = y + 23, viewH = h - 23;
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, top - 0.6f, w, 0.6f));
		FeedList list = data.feed;
		float cw = w - 2 * MARGIN;
		// Altura de cada cartão (depende da legenda): medida antes, para a rolagem e para pular os que estão fora da vista.
		float[] heights = new float[list.posts.size()];
		float total = 6;
		for (int i = 0; i < heights.length; i++) {
			heights[i] = cardHeight(g, list.posts.get(i), cw);
			total += heights[i] + CARD_GAP;
		}
		float footer = list.more || list.loading ? 26 : 8;
		feedMax = Math.max(0, total + footer - viewH);
		feedScroll = Math.max(0, Math.min(feedScroll, feedMax));
		if (list.loading && os.time - list.loadingAt > LOADING_TIMEOUT) {
			list.loading = false;
		}
		if (list.more && !list.loading && !list.posts.isEmpty() && feedScroll > feedMax - PREFETCH) {
			list.loading = true;
			list.loadingAt = os.time;
			os.backend.feedGet("", list.posts.get(list.posts.size() - 1).id());
		}

		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (list.posts.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			if (!list.loaded) {
				Ui.centered(g, Lang.t("handyphone.feed.loading"), x + w / 2f, top + 34);
			} else {
				Ui.centered(g, Lang.t("handyphone.feed.empty.line1"), x + w / 2f, top + 34);
				Ui.centered(g, Lang.t("handyphone.feed.empty.line2"), x + w / 2f, top + 46);
			}
		}
		float cy = top + 6 - feedScroll;
		for (int i = 0; i < heights.length; i++) {
			if (cy + heights[i] >= top && cy <= top + viewH) {
				drawCard(g, os, list, list.posts.get(i), x + MARGIN, cy, cw, heights[i], top, viewH, accent);
			}
			cy += heights[i] + CARD_GAP;
		}
		if ((list.more || list.loading) && !list.posts.isEmpty()) {
			spinner(g, os, x + w / 2f, cy + 8, accent);
		}
		g.setClip(oldClip);
	}

	private static float photoHeight(float cw) {
		return cw * 0.75f;
	}

	private static float cardHeight(Graphics2D g, FeedPost post, float cw) {
		float h = 24 + photoHeight(cw) + 22;
		if (!post.caption().isEmpty()) {
			g.setFont(Ui.font(Font.PLAIN, 8f));
			h += 11 * wrap(g, post.name() + "  " + post.caption(), cw - 8, 2).size();
		}
		return h + 4;
	}

	/** Um cartão do feed: autor, foto, curtir/comentar e a legenda (até 2 linhas). */
	private void drawCard(Graphics2D g, PhoneOS os, FeedList list, FeedPost post, float x, float y, float w, float h, float clipTop, float clipH,
		Color accent) {
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(x, y, w, h, 10, 10));
		authorRow(g, os, post, x, y, w, clipTop, clipH, accent);
		float py = y + 24, ph = photoHeight(w);
		BufferedImage img = image(os, post.photo());
		float vy = Math.max(py, clipTop), vh = Math.min(py + ph, clipTop + clipH) - vy;
		boolean down = vh > 2 && hit("post:" + post.id(), x, vy, w, vh, PhoneSound.LIST_ITEM, () -> openPost(os, list, post.id()));
		photo(g, os, img, post.photo(), x, py, w, ph, 0);
		if (down) {
			g.setColor(new Color(0, 0, 0, 50));
			g.fill(new Rectangle2D.Float(x, py, w, ph));
		}
		float ay = py + ph;
		actions(g, os, list, post, x, ay, clipTop, clipH, accent);
		if (!post.caption().isEmpty()) {
			g.setFont(Ui.font(Font.PLAIN, 8f));
			List<String> lines = wrap(g, post.name() + "  " + post.caption(), w - 8, 2);
			float ly = ay + 22 + 8;
			for (int i = 0; i < lines.size(); i++) {
				captionLine(g, lines.get(i), i == 0 ? post.name() : null, x + 5, ly);
				ly += 11;
			}
		}
	}

	/** Linha da legenda; a primeira começa pelo nome do autor em negrito. */
	private static void captionLine(Graphics2D g, String line, String boldPrefix, float x, float baseline) {
		g.setColor(INK);
		if (boldPrefix != null && line.startsWith(boldPrefix)) {
			g.setFont(Ui.font(Font.BOLD, 8f));
			Ui.text(g, boldPrefix, x, baseline);
			float bw = Ui.textWidth(g, boldPrefix);
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.text(g, line.substring(boldPrefix.length()), x + bw, baseline);
		} else {
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.text(g, line, x, baseline);
		}
	}

	/** Avatar, nome (toca e abre o perfil), há quanto tempo e, nas próprias, o menu de apagar. */
	private void authorRow(Graphics2D g, PhoneOS os, FeedPost post, float x, float y, float w, float clipTop, float clipH, Color accent) {
		float cy = y + 12;
		float vy = Math.max(y + 2, clipTop), vh = Math.min(y + 22, clipTop + clipH) - vy;
		g.setFont(Ui.font(Font.BOLD, 8.6f));
		String name = fit(g, post.name(), w - 70);
		float nameW = Ui.textWidth(g, name);
		boolean down = vh > 2 && hit("author:" + post.id(), x + 2, vy, 22 + nameW, vh, PhoneSound.LIST_ITEM,
			() -> openProfile(os, post.author(), post.name()));
		avatar(g, post.author(), post.name(), x + 12, cy, 7.5f);
		g.setColor(down ? MUTED : INK);
		Ui.text(g, name, x + 23, cy + 3);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7f));
		Ui.text(g, ago(post.time()), x + 23 + nameW + 5, cy + 3);
		if (vh > 2) {
			float mx = x + w - 13;
			boolean menu = hit("menu:" + post.id(), mx - 7, vy, 18, vh, PhoneSound.BUTTON, () -> menuPost = post);
			g.setColor(menu ? INK : MUTED);
			for (int i = -1; i <= 1; i++) {
				g.fill(new Ellipse2D.Float(mx - 1.1f + i * 4.2f, cy - 1.1f, 2.2f, 2.2f));
			}
		}
	}

	/** Coração com o número de curtidas e o balão com o de comentários. */
	private void actions(Graphics2D g, PhoneOS os, FeedList list, FeedPost post, float x, float y, float clipTop, float clipH, Color accent) {
		float cy = y + 11;
		float vy = Math.max(y + 2, clipTop), vh = Math.min(y + 20, clipTop + clipH) - vy;
		boolean visible = vh > 4;
		boolean likeDown = visible && hit("like:" + post.id(), x + 2, vy, 36, vh, PhoneSound.SELECT, () -> toggleLike(os, post));
		Path2D heart = Ui.heart(x + 12, cy, likeDown ? 10f : 11.5f);
		if (post.liked()) {
			g.setColor(new Color(0xE53950));
			g.fill(heart);
		} else {
			g.setColor(INK);
			g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(heart);
		}
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 7.6f));
		Ui.text(g, String.valueOf(post.likes()), x + 21, cy + 2.8f);
		float bx = x + 44;
		boolean commentDown = visible && list != null
			&& hit("comments:" + post.id(), bx - 2, vy, 36, vh, PhoneSound.LIST_ITEM, () -> openPost(os, list, post.id()));
		bubble(g, bx + 8, cy, commentDown ? MUTED : INK);
		g.setColor(INK);
		Ui.text(g, String.valueOf(post.comments()), bx + 17, cy + 2.8f);
	}

	private static void bubble(Graphics2D g, float cx, float cy, Color color) {
		g.setColor(color);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D p = new Path2D.Float();
		p.append(new RoundRectangle2D.Float(cx - 5.5f, cy - 5, 11, 8.6f, 7, 7), false);
		g.draw(p);
		Path2D tail = new Path2D.Float();
		tail.moveTo(cx - 3, cy + 3.3f);
		tail.lineTo(cx - 4.2f, cy + 6.2f);
		tail.lineTo(cx - 0.6f, cy + 3.6f);
		g.draw(tail);
	}

	private void toggleLike(PhoneOS os, FeedPost post) {
		boolean on = !post.liked();
		FeedPost next = new FeedPost(post.id(), post.author(), post.name(), post.time(), post.caption(), post.photo(),
			Math.max(0, post.likes() + (on ? 1 : -1)), on, post.comments(), post.mine());
		os.data.feed.replace(next);
		os.data.profile.replace(next);
		os.backend.feedLike(post.id(), on);
	}

	private void askDelete(PhoneOS os, FeedPost post) {
		os.confirm(Lang.t("handyphone.feed.delete.title"), Lang.t("handyphone.feed.delete.message"), Lang.t("handyphone.gallery.action.delete"), true,
			() -> {
				os.backend.feedDelete(post.id());
				os.data.feed.posts.removeIf(p -> p.id() == post.id());
				os.data.profile.posts.removeIf(p -> p.id() == post.id());
				if (view == View.POST && postId == post.id()) {
					back(os);
				}
			});
	}

	// ------------------------------------------------------------------ publicação aberta

	private void openPost(PhoneOS os, FeedList from, long id) {
		push(View.POST);
		postId = id;
		postFrom = from;
		postScroll = 0;
		comment.set("");
		os.backend.feedComments(id);
	}

	private FeedPost currentPost(PhoneOS os) {
		FeedPost p = postFrom != null ? postFrom.find(postId) : null;
		if (p == null) {
			p = os.data.feed.find(postId);
		}
		return p != null ? p : os.data.profile.find(postId);
	}

	private void drawPost(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		FeedPost post = currentPost(os);
		if (post == null) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			Ui.centered(g, Lang.t("handyphone.feed.gone"), x + w / 2f, y + 40);
			return;
		}
		float inputH = 26, viewH = h - inputH;
		float cw = w - 2 * MARGIN;
		List<FeedComment> comments = os.data.feedComments.get(post.id());

		// Altura do conteúdo: cartão (autor, foto, ações, legenda inteira) e os comentários.
		g.setFont(Ui.font(Font.PLAIN, 8f));
		List<String> captionLines = post.caption().isEmpty() ? List.of() : wrap(g, post.name() + "  " + post.caption(), cw - 8, 12);
		float ph = photoHeight(cw);
		float content = 4 + 24 + ph + 22 + captionLines.size() * 11 + 10 + 16;
		List<List<String>> commentLines = new ArrayList<>();
		if (comments != null) {
			for (FeedComment c : comments) {
				g.setFont(Ui.font(Font.PLAIN, 7.8f));
				List<String> lines = wrap(g, c.name() + "  " + c.text(), cw - 34, 6);
				commentLines.add(lines);
				content += 6 + Math.max(16, lines.size() * 10.5f + 9);
			}
		}
		postMax = Math.max(0, content + 6 - viewH);
		postScroll = Math.max(0, Math.min(postScroll, postMax));

		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, y, w, viewH));
		float cx = x + MARGIN, cy = y + 4 - postScroll;
		authorRow(g, os, post, cx, cy, cw, y, viewH, accent);
		cy += 24;
		BufferedImage full = os.data.photos.full(post.photo(), os.backend, os.time);
		photo(g, os, full != null ? full : os.data.photos.thumb(post.photo(), os.backend, os.time), post.photo(), cx, cy, cw, ph, 8);
		cy += ph;
		actions(g, os, null, post, cx, cy, y, viewH, accent);
		cy += 22 + 8;
		for (int i = 0; i < captionLines.size(); i++) {
			captionLine(g, captionLines.get(i), i == 0 ? post.name() : null, cx + 5, cy);
			cy += 11;
		}
		cy += 2;
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(cx, cy, cw, 0.6f));
		cy += 13;
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		Ui.text(g, Lang.plural("handyphone.feed.comments", post.comments()), cx + 2, cy);
		cy += 6;
		if (comments == null) {
			g.setFont(Ui.font(Font.PLAIN, 7.6f));
			Ui.text(g, Lang.t("handyphone.feed.loading"), cx + 2, cy + 12);
		} else {
			for (int i = 0; i < comments.size(); i++) {
				FeedComment c = comments.get(i);
				List<String> lines = commentLines.get(i);
				float ch = Math.max(16, lines.size() * 10.5f + 9);
				cy += 6;
				drawComment(g, os, post, c, lines, cx, cy, cw, ch, y, viewH);
				cy += ch;
			}
		}
		g.setClip(oldClip);

		// Campo do comentário, fixo embaixo.
		float iy = y + h - inputH;
		g.setColor(new Color(0xF3F4F7));
		g.fill(new Rectangle2D.Float(x, iy, w, inputH));
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, iy, w, 0.6f));
		float sendW = 22;
		field(g, os, "comment", comment, Lang.t("handyphone.feed.comment_hint"), x + MARGIN, iy + 4, w - 2 * MARGIN - sendW - 4, inputH - 8, accent);
		float sx = x + w - MARGIN - sendW;
		boolean ok = !comment.value().isEmpty();
		boolean down = ok && hit("comment_send", sx, iy + 4, sendW, inputH - 8, PhoneSound.BUTTON, () -> sendComment(os));
		Color c = ok ? accent : new Color(0xC9CBD3);
		g.setColor(down ? Ui.shade(c, 0.8f) : c);
		g.fill(new Ellipse2D.Float(sx + sendW / 2f - 8.5f, iy + inputH / 2f - 8.5f, 17, 17));
		arrowUp(g, sx + sendW / 2f, iy + inputH / 2f);
	}

	private static void arrowUp(Graphics2D g, float cx, float cy) {
		g.setColor(Color.WHITE);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		Path2D p = new Path2D.Float();
		p.moveTo(cx, cy + 4);
		p.lineTo(cx, cy - 4);
		p.moveTo(cx - 3.5f, cy - 0.6f);
		p.lineTo(cx, cy - 4);
		p.lineTo(cx + 3.5f, cy - 0.6f);
		g.draw(p);
	}

	private void drawComment(Graphics2D g, PhoneOS os, FeedPost post, FeedComment c, List<String> lines, float x, float y, float w, float h,
		float clipTop, float clipH) {
		avatar(g, c.author(), c.name(), x + 9, y + 7, 6.5f);
		float ty = y + 7;
		for (int i = 0; i < lines.size(); i++) {
			g.setColor(INK);
			String line = lines.get(i);
			if (i == 0 && line.startsWith(c.name())) {
				g.setFont(Ui.font(Font.BOLD, 7.8f));
				Ui.text(g, c.name(), x + 20, ty + 2.5f);
				float bw = Ui.textWidth(g, c.name());
				g.setFont(Ui.font(Font.PLAIN, 7.8f));
				Ui.text(g, line.substring(c.name().length()), x + 20 + bw, ty + 2.5f);
			} else {
				g.setFont(Ui.font(Font.PLAIN, 7.8f));
				Ui.text(g, line, x + 20, ty + 2.5f);
			}
			ty += 10.5f;
		}
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 6.6f));
		Ui.text(g, ago(c.time()), x + 20, ty + 1.5f);
		// O autor apaga o próprio comentário; o dono da publicação apaga qualquer um nela.
		if (c.mine() || post.mine()) {
			float vy = Math.max(y, clipTop), vh = Math.min(y + 14, clipTop + clipH) - vy;
			float bx = x + w - 10;
			boolean down = vh > 2 && hit("cdel:" + c.id(), bx - 7, vy, 14, vh, PhoneSound.BUTTON,
				() -> os.confirm(Lang.t("handyphone.feed.comment_delete.title"), "", Lang.t("handyphone.gallery.action.delete"), true, () -> {
					os.backend.feedCommentDelete(post.id(), c.id());
					List<FeedComment> list = os.data.feedComments.get(post.id());
					if (list != null) {
						list.removeIf(k -> k.id() == c.id());
					}
				}));
			g.setColor(down ? INK : MUTED);
			g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new java.awt.geom.Line2D.Float(bx - 2.6f, y + 4.4f, bx + 2.6f, y + 9.6f));
			g.draw(new java.awt.geom.Line2D.Float(bx + 2.6f, y + 4.4f, bx - 2.6f, y + 9.6f));
		}
	}

	private void sendComment(PhoneOS os) {
		String text = comment.value();
		FeedPost post = currentPost(os);
		if (text.isEmpty() || post == null) {
			return;
		}
		os.backend.feedComment(post.id(), text);
		comment.set("");
		os.playSound(PhoneSound.MESSAGE_SENT);
	}

	// ------------------------------------------------------------------ perfil

	private void openProfile(PhoneOS os, String author, String name) {
		FeedList p = os.data.profile;
		if (!author.equals(p.author)) {
			p.clear();
			p.author = author;
		}
		p.name = name;
		p.loading = true;
		p.loadingAt = os.time;
		os.backend.feedGet(author, 0);
		push(View.PROFILE);
		profileScroll = 0;
	}

	private void drawProfile(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		FeedList list = os.data.profile;
		float headH = 50;
		avatar(g, list.author, list.name, x + MARGIN + 18, y + 24, 16);
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10.5f));
		Ui.text(g, fit(g, list.name, w - 60), x + MARGIN + 42, y + 21);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.6f));
		String count = list.loaded ? Lang.plural("handyphone.feed.posts", list.posts.size()) + (list.more ? "+" : "") : Lang.t("handyphone.feed.loading");
		Ui.text(g, count, x + MARGIN + 42, y + 33);
		g.setColor(LINE);
		g.fill(new Rectangle2D.Float(x, y + headH - 0.6f, w, 0.6f));

		float top = y + headH, viewH = h - headH;
		float gap = 2;
		float cell = (w - 2 * MARGIN - (GRID_COLUMNS - 1) * gap) / GRID_COLUMNS;
		int rows = (list.posts.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
		profileMax = Math.max(0, rows * (cell + gap) + 8 + (list.more ? 22 : 0) - viewH);
		profileScroll = Math.max(0, Math.min(profileScroll, profileMax));
		if (list.loading && os.time - list.loadingAt > LOADING_TIMEOUT) {
			list.loading = false;
		}
		if (list.more && !list.loading && !list.posts.isEmpty() && profileScroll > profileMax - PREFETCH) {
			list.loading = true;
			list.loadingAt = os.time;
			os.backend.feedGet(list.author, list.posts.get(list.posts.size() - 1).id());
		}
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (list.loaded && list.posts.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.4f));
			Ui.centered(g, Lang.t("handyphone.feed.profile_empty"), x + w / 2f, top + 30);
		}
		for (int i = 0; i < list.posts.size(); i++) {
			FeedPost post = list.posts.get(i);
			float cx = x + MARGIN + (i % GRID_COLUMNS) * (cell + gap);
			float cy = top + 6 + (i / GRID_COLUMNS) * (cell + gap) - profileScroll;
			if (cy + cell < top || cy > top + viewH) {
				continue;
			}
			float hy = Math.max(cy, top), hh = Math.min(cy + cell, top + viewH) - hy;
			boolean down = hit("grid:" + post.id(), cx, hy, cell, hh, PhoneSound.LIST_ITEM, () -> openPost(os, list, post.id()));
			photo(g, os, os.data.photos.thumb(post.photo(), os.backend, os.time), post.photo(), cx, cy, cell, cell, 4);
			if (down) {
				g.setColor(new Color(0, 0, 0, 60));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 4, 4));
			}
		}
		if (list.more) {
			spinner(g, os, x + w / 2f, top + 6 + rows * (cell + gap) + 8 - profileScroll, accent);
		}
		g.setClip(oldClip);
	}

	// ------------------------------------------------------------------ publicar

	private void startPublish(PhoneOS os) {
		os.data.feedEligible = null;
		os.backend.feedEligible();
		pickScroll = 0;
		push(View.PICK);
	}

	private void drawPick(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.feed.pick"), x + MARGIN + 2, y + 14f);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 6.8f));
		if (os.data.feedCameraOnly) {
			Ui.text(g, Lang.t("handyphone.feed.pick_camera_only"), x + MARGIN + 2, y + 25f);
		}
		List<GalleryPhoto> photos = new ArrayList<>();
		java.util.Set<Long> ok = os.data.feedEligible;
		if (ok != null) {
			for (GalleryPhoto p : os.data.gallery) {
				if (ok.contains(p.id())) {
					photos.add(p);
				}
			}
		}
		float top = y + 31, viewH = h - 31, gap = 3;
		float cell = (w - 2 * MARGIN - (GRID_COLUMNS - 1) * gap) / GRID_COLUMNS;
		int rows = (photos.size() + GRID_COLUMNS - 1) / GRID_COLUMNS;
		pickMax = Math.max(0, rows * (cell + gap) + 6 - viewH);
		pickScroll = Math.max(0, Math.min(pickScroll, pickMax));
		Shape oldClip = g.getClip();
		g.clip(new Rectangle2D.Float(x, top, w, viewH));
		if (photos.isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8.2f));
			if (ok == null) {
				Ui.centered(g, Lang.t("handyphone.feed.loading"), x + w / 2f, top + 30);
			} else {
				Ui.centered(g, Lang.t("handyphone.feed.pick_empty.line1"), x + w / 2f, top + 30);
				Ui.centered(g, Lang.t("handyphone.feed.pick_empty.line2"), x + w / 2f, top + 42);
			}
		}
		for (int i = 0; i < photos.size(); i++) {
			GalleryPhoto p = photos.get(i);
			float cx = x + MARGIN + (i % GRID_COLUMNS) * (cell + gap);
			float cy = top + (i / GRID_COLUMNS) * (cell + gap) - pickScroll;
			if (cy + cell < top || cy > top + viewH) {
				continue;
			}
			float hy = Math.max(cy, top), hh = Math.min(cy + cell, top + viewH) - hy;
			boolean down = hit("pick:" + p.id(), cx, hy, cell, hh, PhoneSound.LIST_ITEM, () -> {
				pickPhoto = p.id();
				caption.set("");
				push(View.CAPTION);
				focus = caption;
			});
			photo(g, os, os.data.photos.thumb(p.id(), os.backend, os.time), p.id(), cx, cy, cell, cell, 6);
			if (down) {
				g.setColor(new Color(0, 0, 0, 70));
				g.fill(new RoundRectangle2D.Float(cx, cy, cell, cell, 6, 6));
			}
		}
		g.setClip(oldClip);
	}

	private void drawCaption(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float cw = w - 2 * MARGIN;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 10f));
		Ui.text(g, Lang.t("handyphone.feed.new_post"), x + MARGIN + 2, y + 14f);
		float ph = Math.min(photoHeight(cw), h * 0.48f);
		BufferedImage full = os.data.photos.full(pickPhoto, os.backend, os.time);
		photo(g, os, full != null ? full : os.data.photos.thumb(pickPhoto, os.backend, os.time), pickPhoto, x + MARGIN, y + 22, cw, ph, 8);
		float fy = y + 22 + ph + 8;
		field(g, os, "caption", caption, Lang.t("handyphone.feed.caption_hint"), x + MARGIN, fy, cw, 22, accent);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 6.6f));
		String counter = caption.text.codePointCount(0, caption.text.length()) + "/" + Math.min(caption.max, os.data.feedMaxCaption);
		Ui.text(g, counter, x + w - MARGIN - Ui.textWidth(g, counter), fy + 31);
		Ui.text(g, Lang.t("handyphone.feed.public_note"), x + MARGIN + 2, fy + 31);
		button(g, "do_publish", Lang.t("handyphone.feed.publish"), x + MARGIN, fy + 40, cw, 22, accent, () -> publish(os));
	}

	private void publish(PhoneOS os) {
		if (pickPhoto <= 0) {
			return;
		}
		os.backend.feedPublish(pickPhoto, caption.value());
		os.playSound(PhoneSound.MESSAGE_SENT);
		pickPhoto = 0;
		caption.set("");
		stack.clear();
		postStack.clear();
		view = View.FEED;
		focus = null;
		feedScroll = 0;
		refresh(os);
	}

	// ------------------------------------------------------------------ peças

	/** A foto do feed: a inteira se já chegou, senão a miniatura (pedida sozinha), senão um quadro cinza. */
	private static BufferedImage image(PhoneOS os, long photo) {
		BufferedImage full = os.data.photos.peekFull(photo);
		return full != null ? full : os.data.photos.thumb(photo, os.backend, os.time);
	}

	private static void photo(Graphics2D g, PhoneOS os, BufferedImage img, long id, float x, float y, float w, float h, float arc) {
		if (img != null) {
			drawCover(g, img, x, y, w, h, arc);
		} else {
			g.setColor(os.data.photos.isGone(id) ? new Color(0xE9D5D5) : new Color(0xD3D5DD));
			g.fill(new RoundRectangle2D.Float(x, y, w, h, arc, arc));
		}
	}

	private static void spinner(Graphics2D g, PhoneOS os, float cx, float cy, Color accent) {
		float r = 5;
		g.setColor(accent);
		g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int start = (int) (os.time * 360) % 360;
		g.draw(new java.awt.geom.Arc2D.Float(cx - r, cy - r, r * 2, r * 2, -start, 260, java.awt.geom.Arc2D.OPEN));
	}

	/** Há quanto tempo: "agora", "5 min", "3 h", "2 d", depois a data. */
	static String ago(long time) {
		long seconds = Math.max(0, (System.currentTimeMillis() - time) / 1000);
		if (seconds < 60) {
			return Lang.t("handyphone.feed.ago.now");
		}
		if (seconds < 3600) {
			return Lang.t("handyphone.feed.ago.minutes", String.valueOf(seconds / 60));
		}
		if (seconds < 86_400) {
			return Lang.t("handyphone.feed.ago.hours", String.valueOf(seconds / 3600));
		}
		if (seconds < 7 * 86_400) {
			return Lang.t("handyphone.feed.ago.days", String.valueOf(seconds / 86_400));
		}
		java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(time), java.time.ZoneId.systemDefault());
		return t.format(Lang.format("handyphone.format.day"));
	}

	/** Quebra o texto em linhas que cabem na largura (por palavras; palavra longa demais é cortada), no máximo {@code maxLines} (a última com "…"). */
	static List<String> wrap(Graphics2D g, String text, float width, int maxLines) {
		List<String> lines = new ArrayList<>();
		String[] words = text.split(" ", -1);
		StringBuilder line = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			String tryLine = line.isEmpty() ? word : line + " " + word;
			if (Ui.textWidth(g, tryLine) <= width || line.isEmpty() && Ui.textWidth(g, word) <= width) {
				line.setLength(0);
				line.append(tryLine);
				continue;
			}
			if (!line.isEmpty()) {
				lines.add(line.toString());
				line.setLength(0);
			}
			if (lines.size() == maxLines) {
				break;
			}
			if (Ui.textWidth(g, word) > width) {
				// Palavra maior que a linha: corta em pedaços (por grafema, sem partir emoji).
				String rest = word;
				while (Ui.textWidth(g, rest) > width && lines.size() < maxLines) {
					String head = fit(g, rest, width);
					head = head.endsWith("…") ? head.substring(0, head.length() - 1) : head;
					if (head.isEmpty()) {
						break;
					}
					lines.add(head);
					rest = rest.substring(head.length());
				}
				line.append(rest);
			} else {
				line.append(word);
			}
		}
		if (!line.isEmpty() && lines.size() < maxLines) {
			lines.add(line.toString());
		} else if (!line.isEmpty() || lines.size() == maxLines && wrapCut(words, lines)) {
			String last = lines.get(lines.size() - 1);
			lines.set(lines.size() - 1, fit(g, last + "…", width).replace("……", "…"));
		}
		return lines;
	}

	/** O texto não coube inteiro nas linhas (sobrou alguma palavra)? */
	private static boolean wrapCut(String[] words, List<String> lines) {
		int shown = 0;
		for (String l : lines) {
			shown += l.length() + 1;
		}
		return shown < String.join(" ", words).length();
	}
}
