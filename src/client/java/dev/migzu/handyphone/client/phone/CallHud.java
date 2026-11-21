package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneData;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * O aviso de ligação na tela do jogo, fora do aparelho: só um ícone de telefone, sem texto, discreto no canto. Laranja e
 * balançando enquanto uma chamada toca (a tecla U puxa o celular direto na tela de atender), verde com a ligação em andamento,
 * cinza enquanto se chama. Some quando o celular está na mão (a tela da ligação já mostra tudo). Faz parte da ideia de
 * notificações de HUD do Migzu, só com o ícone de ligação por enquanto.
 */
public final class CallHud implements HudElement {
	private static final int ORANGE = 0xFFF2A100, GREEN = 0xFF2FB75A, GRAY = 0xFF6F7B8E, WHITE = 0xFFFFFFFF, RED = 0xFFE5392E;
	private static final int SIZE = 15;
	/** O fone, em pontos (X = branco), dentro de um quadrado de 11 x 10. */
	private static final String[] HANDSET = {
		" XXX       ",
		" XXX       ",
		" XXX       ",
		" XXX       ",
		"  XXX      ",
		"  XXXX     ",
		"   XXXXX   ",
		"    XXXXXX ",
		"     XXXXXX",
		"     XXXXXX"
	};
	/** Largura de cada linha do disco (15 de altura), para aproximar um círculo com retângulos. */
	private static final int[] DISC = {5, 9, 11, 13, 13, 15, 15, 15, 15, 15, 13, 13, 11, 9, 5};

	private CallHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(HandyPhone.id("call_hud"), new CallHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || PhoneController.INSTANCE.isShown()) {
			return;
		}
		// Sem a trava do PhoneOS: a thread de desenho do celular a segura durante cada quadro e o HUD não pode esperar.
		PhoneData.CallView view = PhoneController.INSTANCE.os.callView();
		int state = view.state();
		if (state == PhoneData.CALL_NONE || state == PhoneData.CALL_ENDED) {
			return;
		}
		int color = state == PhoneData.CALL_INCOMING ? ORANGE : state == PhoneData.CALL_ACTIVE ? GREEN : GRAY;
		long ms = System.currentTimeMillis();
		int shake = state == PhoneData.CALL_INCOMING ? (int) Math.round(Math.sin(ms / 70.0) * 1.5) : 0;
		int x = graphics.guiWidth() - SIZE - 6 + shake, y = 6;
		for (int row = 0; row < SIZE; row++) {
			int width = DISC[row];
			int left = x + (SIZE - width) / 2;
			graphics.fill(left, y + row, left + width, y + row + 1, color);
		}
		for (int row = 0; row < HANDSET.length; row++) {
			String line = HANDSET[row];
			for (int col = 0; col < line.length(); col++) {
				if (line.charAt(col) == 'X') {
					graphics.fill(x + 2 + col, y + 2 + row, x + 3 + col, y + 3 + row, WHITE);
				}
			}
		}
		if (view.muted() && state == PhoneData.CALL_ACTIVE) {
			// Um ponto vermelho no canto: o jogador está no mudo (continua sendo uma marca discreta, sem texto).
			graphics.fill(x + SIZE - 5, y + SIZE - 5, x + SIZE - 1, y + SIZE - 1, RED);
		}
	}
}
