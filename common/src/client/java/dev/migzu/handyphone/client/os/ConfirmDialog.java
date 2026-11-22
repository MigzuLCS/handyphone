package dev.migzu.handyphone.client.os;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Janela de confirmação sobre a tela do celular: título, texto, uma prévia opcional de imagem e dois botões
 * (cancelar e confirmar). Serve para o que tem consequência e não deve acontecer por um toque sem querer:
 * bloquear um número, sair de um grupo, enviar para a galeria uma imagem vinda do computador.
 */
final class ConfirmDialog extends AppContent {
	private final String title, message, yesLabel;
	private final boolean danger;
	private final BufferedImage preview;
	private final Runnable onYes, onNo;

	ConfirmDialog(String title, String message, String yesLabel, boolean danger, BufferedImage preview, Runnable onYes, Runnable onNo) {
		this.title = title;
		this.message = message;
		this.yesLabel = yesLabel;
		this.danger = danger;
		this.preview = preview;
		this.onYes = onYes;
		this.onNo = onNo;
	}

	String title() {
		return title;
	}

	void confirm() {
		onYes.run();
	}

	void cancel() {
		onNo.run();
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		// A tela inteira escurece e engole os toques fora do cartão.
		hit("scrim", x, y, w, h, null, () -> { });
		g.setColor(new Color(0, 0, 0, 140));
		g.fill(new Rectangle2D.Float(x, y, w, h));

		float cw = w - 20, cx = x + 10, pad = 10;
		g.setFont(Ui.font(Font.PLAIN, 8.2f));
		List<String> lines = MessagesApp.wrap(g, message, cw - 2 * pad);
		float imgH = 0, imgW = 0;
		if (preview != null) {
			float scale = Math.min((cw - 2 * pad) / preview.getWidth(), 120f / preview.getHeight());
			imgW = preview.getWidth() * scale;
			imgH = preview.getHeight() * scale;
		}
		float lineH = 10.4f;
		float ch = pad + 12 + 6 + (imgH > 0 ? imgH + 8 : 0) + lines.size() * lineH + 10 + 24 + pad;
		float cy = y + Math.max(14, (h - ch) / 2f - 10);

		g.setColor(new Color(0, 0, 0, 60));
		g.fill(new RoundRectangle2D.Float(cx, cy + 2, cw, ch, 14, 14));
		g.setColor(Color.WHITE);
		g.fill(new RoundRectangle2D.Float(cx, cy, cw, ch, 14, 14));
		hit("card", cx, cy, cw, ch, null, () -> { });

		float ty = cy + pad + 9;
		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.6f));
		Ui.text(g, fit(g, title, cw - 2 * pad), cx + pad, ty);
		ty += 6;
		if (imgH > 0) {
			drawFit(g, preview, cx + (cw - imgW) / 2f, ty, imgW, imgH, 6);
			ty += imgH + 8;
		}
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 8.2f));
		for (String line : lines) {
			ty += lineH;
			Ui.text(g, line, cx + pad, ty - 2.5f);
		}
		float by = cy + ch - pad - 24, half = (cw - 2 * pad - 6) / 2f;
		button(g, "no", Lang.t("handyphone.cancel"), cx + pad, by, half, 24, new Color(0x8E8E98), () -> {
			os.closeModal();
			onNo.run();
		});
		button(g, "yes", yesLabel, cx + pad + half + 6, by, half, 24, danger ? new Color(0xD14336) : accent, () -> {
			os.closeModal();
			onYes.run();
		});
	}
}
