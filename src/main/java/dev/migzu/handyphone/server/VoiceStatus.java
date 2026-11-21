package dev.migzu.handyphone.server;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * O que o servidor sabe do Simple Voice Chat, <b>sem tocar na API dele</b>: este é o ponto de encontro entre a thread do
 * servidor (que cuida das ligações) e a thread de rede do SVC (que ouve a voz). Só {@link VoiceLink} escreve aqui, a partir
 * dos eventos do SVC; o {@link CallService} só lê. Sem o SVC instalado nada escreve, e as ligações ficam indisponíveis.
 *
 * <p>Tudo é seguro entre threads: conjuntos concorrentes, campos {@code volatile} e um contador de versão que o
 * serviço usa para saber que alguém ganhou ou perdeu a voz.
 */
public final class VoiceStatus {
	private static volatile boolean serverReady;
	private static final Set<UUID> WITH_VOICE = ConcurrentHashMap.newKeySet();
	private static final AtomicInteger VERSION = new AtomicInteger();
	/** O retrato de roteamento que a thread do SVC lê; trocado inteiro pela thread do servidor. */
	private static volatile VoiceRouting.Snapshot snapshot = VoiceRouting.Snapshot.EMPTY;

	private VoiceStatus() {
	}

	/** O servidor de voz do SVC está no ar? */
	public static boolean serverReady() {
		return serverReady;
	}

	/** O jogador está conectado à voz do SVC e não a desligou? */
	public static boolean hasVoice(UUID player) {
		return WITH_VOICE.contains(player);
	}

	/** Muda a cada vez que alguém ganha ou perde a voz, ou o servidor de voz liga ou desliga. */
	static int version() {
		return VERSION.get();
	}

	static VoiceRouting.Snapshot snapshot() {
		return snapshot;
	}

	static void publish(VoiceRouting.Snapshot next) {
		snapshot = next;
	}

	// ---- quem escreve: o VoiceLink (e os testes, que não têm o SVC)

	public static void serverStarted() {
		serverReady = true;
		VERSION.incrementAndGet();
	}

	public static void serverStopped() {
		serverReady = false;
		WITH_VOICE.clear();
		snapshot = VoiceRouting.Snapshot.EMPTY;
		VERSION.incrementAndGet();
	}

	public static void setVoice(UUID player, boolean on) {
		if (on ? WITH_VOICE.add(player) : WITH_VOICE.remove(player)) {
			VERSION.incrementAndGet();
		}
	}

	/** Só para os testes: volta tudo ao estado de "SVC ausente". */
	public static void resetForTests() {
		serverStopped();
	}
}
