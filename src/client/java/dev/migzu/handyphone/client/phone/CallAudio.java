package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.client.os.PhoneData;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.os.PhoneSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;

/**
 * Os sons das ligações que não dependem de o celular estar na mão: o toque de quem recebe, o tom de quem chama (os dois em
 * laço) e os avisos de atendida, encerrada e ocupado. Roda no tick do cliente e só lê o {@link PhoneData.CallView}, que o
 * celular publica sem trava. Respeita o ajuste "Sons da interface" do celular, como os outros avisos.
 *
 * <p>Um som que ainda não tem arquivo (os {@code call_*} chegam em separado) simplesmente não toca: nada quebra nem enche o
 * log, e o laço só tenta de novo de tempos em tempos.
 */
public final class CallAudio {
	/** Intervalo mínimo entre tentativas quando o som não tem como tocar (arquivo ausente). */
	private static final long RETRY_MS = 2000;

	private static SimpleSoundInstance loop;
	private static PhoneSound loopKind;
	private static long nextTry;
	private static int lastState = PhoneData.CALL_NONE;
	private static long lastId;
	private static int lastJoined;

	private CallAudio() {
	}

	/** Chamado a cada tick do cliente (thread do jogo). */
	public static void tick(Minecraft mc) {
		PhoneOS os = PhoneController.INSTANCE.os;
		PhoneData.CallView view = os.callView();
		if (mc.player == null) {
			stopLoop();
			lastState = PhoneData.CALL_NONE;
			lastId = 0;
			lastJoined = 0;
			return;
		}
		String theme = os.themeId();
		boolean sounds = os.uiSounds();
		int state = view.state();
		// Alguém entrou ou saiu de uma chamada em andamento (a de grupo): um aviso curto. A primeira contagem depois de atender não conta.
		boolean sameActive = state == PhoneData.CALL_ACTIVE && lastState == PhoneData.CALL_ACTIVE && view.id() == lastId;
		if (sameActive && sounds) {
			if (view.joined() > lastJoined) {
				PhoneSounds.play(PhoneSound.CALL_JOIN, theme);
			} else if (view.joined() < lastJoined) {
				PhoneSounds.play(PhoneSound.CALL_LEAVE, theme);
			}
		}
		lastJoined = view.joined();
		if (state != lastState || view.id() != lastId) {
			transition(lastState, view, theme, sounds);
			lastState = state;
			lastId = view.id();
		}
		PhoneSound wanted = !sounds ? null : state == PhoneData.CALL_INCOMING ? PhoneSound.RINGTONE : state == PhoneData.CALL_OUTGOING ? PhoneSound.CALL_DIAL : null;
		if (wanted != loopKind) {
			stopLoop();
			loopKind = wanted;
			nextTry = 0;
		}
		if (wanted != null && (loop == null || !PhoneSounds.isActive(loop))) {
			long now = System.currentTimeMillis();
			if (now >= nextTry) {
				loop = PhoneSounds.playTracked(wanted, theme);
				// Sem arquivo para tocar: não insiste a cada tick.
				nextTry = loop == null ? now + RETRY_MS : now + 250;
			}
		}
	}

	private static void transition(int from, PhoneData.CallView view, String theme, boolean sounds) {
		if (!sounds) {
			return;
		}
		boolean wasRinging = from == PhoneData.CALL_OUTGOING || from == PhoneData.CALL_INCOMING;
		switch (view.state()) {
			case PhoneData.CALL_ACTIVE -> {
				if (wasRinging) {
					PhoneSounds.play(PhoneSound.CALL_CONNECT, theme);
				}
			}
			case PhoneData.CALL_ENDED -> PhoneSounds.play(view.reason() == PhoneData.END_BUSY ? PhoneSound.CALL_BUSY : PhoneSound.CALL_END, theme);
			default -> { }
		}
	}

	private static void stopLoop() {
		if (loop != null) {
			PhoneSounds.stop(loop);
			loop = null;
		}
		loopKind = null;
	}

	/** O laço que está pedido agora (testes), ou null. */
	public static PhoneSound activeLoop() {
		return loopKind;
	}
}
