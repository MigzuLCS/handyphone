package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import javax.imageio.ImageIO;

/** App Sobre: o ícone do mod, o nome, a versão, os créditos e os links das páginas do mod (Modrinth e CurseForge). */
final class AboutApp extends AppContent {
	private static final float MARGIN = 10f, ICON = 58f, ROW_H = 22f;
	private static final String MODRINTH = "https://modrinth.com/mod/handyphone";
	private static final String CURSEFORGE = "https://www.curseforge.com/minecraft/mc-mods/handyphone";
	private static BufferedImage icon;
	private static boolean iconTried;

	private static BufferedImage icon() {
		if (!iconTried) {
			iconTried = true;
			try (InputStream in = AboutApp.class.getResourceAsStream("/assets/handyphone/icon.png")) {
				icon = in == null ? null : ImageIO.read(in);
			} catch (Exception e) {
				icon = null;
			}
		}
		return icon;
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float cx = x + w / 2f, ry = y + 22f;
		BufferedImage img = icon();
		if (img != null) {
			Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			java.awt.Shape oldClip = g.getClip();
			g.clip(new RoundRectangle2D.Float(Math.round(cx - ICON / 2f), Math.round(ry), Math.round(ICON), Math.round(ICON), 16, 16));
			g.drawImage(img, Math.round(cx - ICON / 2f), Math.round(ry), Math.round(ICON), Math.round(ICON), null);
			g.setClip(oldClip);
			if (old != null) {
				g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, old);
			}
		}
		ry += ICON + 18f;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 12.5f));
		Ui.centered(g, "Migzu's HandyPhone", cx, ry);
		ry += 12f;
		if (!os.modVersion().isEmpty()) {
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 8f));
			Ui.centered(g, Lang.t("handyphone.settings.about.version", os.modVersion()), cx, ry);
			ry += 6f;
		}

		// Créditos num cartão, uma linha cada; as duas últimas são os links das páginas do mod (abrem no navegador).
		float rx = x + MARGIN, rw = w - 2 * MARGIN, top = ry + 12f;
		String[][] rows = {
			{null, Lang.t("handyphone.settings.about.author")},
			{null, Lang.t("handyphone.settings.about.font")},
			{MODRINTH, "Modrinth"},
			{CURSEFORGE, "CurseForge"},
		};
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(rx, top, rw, rows.length * ROW_H, 10, 10));
		for (int i = 0; i < rows.length; i++) {
			float rowY = top + i * ROW_H;
			String url = rows[i][0];
			boolean down = false;
			if (url != null) {
				down = hit("link:" + rows[i][1].toLowerCase(java.util.Locale.ROOT), rx, rowY, rw, ROW_H, PhoneSound.BUTTON, () -> {
					os.openLink(url);
					os.notify("HandyPhone", Lang.t("handyphone.about.opening"), -1);
				});
				if (down) {
					g.setColor(PRESSED);
					g.fill(new RoundRectangle2D.Float(rx, rowY, rw, ROW_H, 10, 10));
				}
			}
			if (i > 0) {
				g.setColor(LINE);
				g.setStroke(new BasicStroke(0.6f));
				g.draw(new Line2D.Float(rx + 8, rowY, rx + rw - 8, rowY));
			}
			Color ink = url != null ? Ui.shade(accent, 0.85f) : INK;
			g.setColor(ink);
			g.setFont(Ui.font(url != null ? Font.BOLD : Font.PLAIN, 8.2f));
			Ui.text(g, fit(g, rows[i][1], rw - 30), rx + 8, rowY + ROW_H / 2f + 3f);
			if (url != null) {
				// Seta de "abre fora": diagonal com a ponta no canto de cima.
				float ax = rx + rw - 13, ay = rowY + ROW_H / 2f;
				g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(new Line2D.Float(ax - 3f, ay + 3f, ax + 3f, ay - 3f));
				GeneralPath tip = new GeneralPath();
				tip.moveTo(ax - 0.5f, ay - 3f);
				tip.lineTo(ax + 3f, ay - 3f);
				tip.lineTo(ax + 3f, ay + 0.5f);
				g.draw(tip);
			}
		}
	}
}
