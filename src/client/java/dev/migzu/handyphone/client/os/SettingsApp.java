package dev.migzu.handyphone.client.os;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;

/** App Ajustes: escolha do estilo da interface e controle dos efeitos sonoros. */
final class SettingsApp extends AppContent {
	private static final float MARGIN = 8f;
	private static final float THEME_TOP = 19f, THEME_H = 31f, ROW_GAP = 3.5f;
	private static final float SOUND_H = 28f;

	private float scroll;
	private float maxScroll;

	@Override
	void scroll(PhoneOS os, float amount) {
		float next = Math.max(0, Math.min(scroll - amount * 18, maxScroll));
		if (Math.abs(next - scroll) > 0.5f) {
			scroll = next;
			os.playScrollTick();
		}
	}

	@Override
	void draw(Graphics2D g, PhoneOS os, float x, float y, float w, float h, Color accent) {
		float rx = x + MARGIN, rw = w - 2 * MARGIN;
		float themesEnd = THEME_TOP + os.themes.size() * (THEME_H + ROW_GAP);
		float soundsHeaderY = themesEnd + 11f;
		float soundsTop = soundsHeaderY + 5f;
		float totalH = soundsTop + 2 * (SOUND_H + ROW_GAP) + 6f;

		maxScroll = Math.max(0, totalH - h);
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		float oy = y - scroll;

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		Ui.text(g, Lang.t("handyphone.settings.style"), rx + 2, oy + 13f);

		for (int i = 0; i < os.themes.size(); i++) {
			Theme theme = os.themes.get(i);
			boolean selected = theme == os.theme;
			float ry = oy + THEME_TOP + i * (THEME_H + ROW_GAP);
			boolean down = hit("theme:" + theme.id(), rx, ry, rw, THEME_H, PhoneSound.SELECT, () -> os.setTheme(theme.id()));
			RoundRectangle2D row = new RoundRectangle2D.Float(rx, ry, rw, THEME_H, 8, 8);
			g.setColor(down ? PRESSED : Color.WHITE);
			g.fill(row);
			g.setStroke(new BasicStroke(selected ? 1.3f : 0.7f));
			g.setColor(selected ? accent : LINE);
			g.draw(row);

			g.setColor(INK);
			g.setFont(Ui.font(Font.BOLD, 9.6f));
			Ui.text(g, theme.name(), rx + 8, ry + 13.2f);
			g.setColor(MUTED);
			g.setFont(Ui.font(Font.PLAIN, 7.2f));
			Ui.text(g, theme.description(), rx + 8, ry + 24f);

			float cx = rx + rw - 14, cy = ry + THEME_H / 2f;
			g.setStroke(new BasicStroke(1.2f));
			g.setColor(selected ? accent : new Color(0xA9ABB5));
			g.draw(new Ellipse2D.Float(cx - 5.5f, cy - 5.5f, 11, 11));
			if (selected) {
				g.fill(new Ellipse2D.Float(cx - 3f, cy - 3f, 6, 6));
			}
		}

		g.setColor(MUTED);
		g.setFont(Ui.font(Font.BOLD, 7.4f));
		Ui.text(g, Lang.t("handyphone.settings.sounds"), rx + 2, oy + soundsHeaderY);

		drawToggleRow(g, rx, oy + soundsTop, rw, SOUND_H, accent,
			"sound:ui", Lang.t("handyphone.settings.ui_sounds.title"), Lang.t("handyphone.settings.ui_sounds.subtitle"), os.uiSounds(), null, () -> {
				boolean next = !os.uiSounds();
				os.setUiSounds(next);
				if (next) {
					os.playSound(PhoneSound.SELECT);
				}
			}
		);
		drawToggleRow(g, rx, oy + soundsTop + SOUND_H + ROW_GAP, rw, SOUND_H, accent,
			"sound:keyboard", Lang.t("handyphone.settings.keyboard_sounds.title"), Lang.t("handyphone.settings.keyboard_sounds.subtitle"), os.keyboardSounds(), PhoneSound.SELECT,
			() -> os.setKeyboardSounds(!os.keyboardSounds())
		);
	}

	private void drawToggleRow(
		Graphics2D g, float rx, float ry, float rw, float rh, Color accent,
		String key, String title, String subtitle, boolean enabled, PhoneSound sound, Runnable action
	) {
		boolean down = hit(key, rx, ry, rw, rh, sound, action);
		RoundRectangle2D row = new RoundRectangle2D.Float(rx, ry, rw, rh, 8, 8);
		g.setColor(down ? PRESSED : Color.WHITE);
		g.fill(row);
		g.setStroke(new BasicStroke(0.7f));
		g.setColor(LINE);
		g.draw(row);

		g.setColor(INK);
		g.setFont(Ui.font(Font.BOLD, 9.2f));
		Ui.text(g, title, rx + 8, ry + 12f);
		g.setColor(MUTED);
		g.setFont(Ui.font(Font.PLAIN, 7.0f));
		Ui.text(g, subtitle, rx + 8, ry + 21.8f);

		// Chave seletora (liga/desliga) à direita.
		float sw = 22f, sh = 12f, sx = rx + rw - sw - 7f, sy = ry + (rh - sh) / 2f;
		g.setColor(enabled ? accent : new Color(0xB8BAC4));
		g.fill(new RoundRectangle2D.Float(sx, sy, sw, sh, sh, sh));
		float knob = sh - 3.2f;
		float kx = enabled ? sx + sw - knob - 1.6f : sx + 1.6f;
		g.setColor(Color.WHITE);
		g.fill(new Ellipse2D.Float(kx, sy + 1.6f, knob, knob));
	}
}
