package dev.migzu.handyphone.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Quem recebe esta fala?" das ligações, como função pura: sem Simple Voice Chat, sem jogo, sem estado de ligação. Tudo o
 * que ela precisa vem em um {@link Snapshot} <b>imutável</b>, que a thread do servidor refaz a cada mudança de estado e a
 * cada poucos ticks, e que a thread de rede do Simple Voice Chat só lê (uma referência {@code volatile} trocada inteira).
 * Assim a thread de voz nunca toca em contas, jogadores nem no estado das ligações.
 *
 * <p>O servidor decide quem ouve quem: o retrato só lista, para cada falante, quem PODE ouvi-lo (ligação atendida dos
 * dois lados, falante sem mudo e sem silenciamento de administrador, nenhum bloqueio em nenhum sentido). Esta classe só
 * acrescenta as regras que dependem de onde cada um está (dimensão e eco).
 */
public final class VoiceRouting {
	private VoiceRouting() {
	}

	/** Onde um participante está (copiado da entidade pela thread do servidor). */
	public record Where(String dimension, double x, double y, double z) {
	}

	/**
	 * O retrato da voz.
	 *
	 * @param version        número crescente; o {@link Echo} esquece os pares antigos quando ele muda
	 * @param listeners      para cada falante autorizado, quem pode ouvi-lo (listas imutáveis)
	 * @param where          posição dos participantes de ligações; quem não está ligado não aparece
	 * @param crossDimension a voz atravessa dimensões
	 */
	public record Snapshot(long version, Map<UUID, List<UUID>> listeners, Map<UUID, Where> where, boolean crossDimension) {
		public static final Snapshot EMPTY = new Snapshot(0, Map.of(), Map.of(), true);
	}

	/** O que o Simple Voice Chat já faz por conta própria entre dois jogadores (a voz de proximidade e os grupos dele). */
	public enum Proximity {
		/** Vale a regra de distância (eco). */
		NORMAL,
		/** A voz de proximidade NÃO chega a este ouvinte (grupo normal ou isolado do SVC): repassa sempre. */
		ALWAYS,
		/** Os dois já se ouvem por outro caminho (mesmo grupo do SVC): nunca repassa. */
		NEVER
	}

	/** Pergunta ao Simple Voice Chat como os dois se ouvem agora; só o {@link VoiceLink} sabe responder. */
	@FunctionalInterface
	public interface ProximityRule {
		ProximityRule PLAIN = (speaker, listener) -> Proximity.NORMAL;

		Proximity between(UUID speaker, UUID listener);
	}

	/**
	 * Memória do eco por par (quem fala, quem ouve): a histerese impede que a voz fique ligando e desligando quando duas
	 * pessoas andam na distância-limite. Só é mexida pela thread de voz; esquece sozinha os pares que saíram do retrato.
	 */
	public static final class Echo {
		private record Pair(UUID speaker, UUID listener) {
		}

		private final ConcurrentHashMap<Pair, Boolean> relaying = new ConcurrentHashMap<>();
		private volatile long version = -1;

		private boolean get(UUID speaker, UUID listener) {
			return relaying.getOrDefault(new Pair(speaker, listener), Boolean.FALSE);
		}

		private void set(UUID speaker, UUID listener, boolean value) {
			relaying.put(new Pair(speaker, listener), value);
		}

		private void sync(Snapshot snapshot) {
			if (snapshot.version() != version) {
				version = snapshot.version();
				relaying.keySet().removeIf(pair -> {
					List<UUID> listeners = snapshot.listeners().get(pair.speaker());
					return listeners == null || !listeners.contains(pair.listener());
				});
			}
		}

		int size() {
			return relaying.size();
		}
	}

	/**
	 * Fração do alcance a partir da qual o repasse LIGA, e abaixo da qual DESLIGA. Perto da borda a voz de proximidade já
	 * está fraca (o SVC a esmaece com a distância), então é melhor um pouco de eco que um buraco de silêncio.
	 */
	static final double RELAY_ON = 0.75, RELAY_OFF = 0.65;

	/**
	 * Quem recebe a fala de {@code speaker} pelo celular. Lista vazia se ele não está autorizado a falar em nenhuma ligação.
	 *
	 * <p><b>Eco:</b> quem está na mesma dimensão e perto o bastante para ouvir a voz de proximidade do Simple Voice Chat
	 * (alcance normal, ou o de sussurro se {@code whispering}) não recebe o pacote pelo celular, senão ouviria duas vezes.
	 * A voz de proximidade segue valendo como sempre: o evento original nunca é cancelado. O falante nunca recebe a
	 * própria voz.
	 *
	 * @param voiceDistance   alcance da voz normal, em blocos
	 * @param whisperDistance alcance do sussurro, em blocos
	 * @param echo            memória da histerese (null = sem histerese, só o limite de {@link #RELAY_ON})
	 * @param rule            como o SVC já conecta cada par (null = só a distância)
	 */
	public static List<UUID> recipients(
		Snapshot snapshot, UUID speaker, boolean whispering, double voiceDistance, double whisperDistance, Echo echo, ProximityRule rule
	) {
		List<UUID> candidates = snapshot.listeners().get(speaker);
		if (candidates == null || candidates.isEmpty()) {
			return List.of();
		}
		if (echo != null) {
			echo.sync(snapshot);
		}
		Where from = snapshot.where().get(speaker);
		double reach = whispering ? whisperDistance : voiceDistance;
		ArrayList<UUID> out = null;
		for (UUID listener : candidates) {
			if (listener.equals(speaker)) {
				continue;
			}
			Where to = snapshot.where().get(listener);
			boolean sameDimension = from != null && to != null && from.dimension().equals(to.dimension());
			if (!snapshot.crossDimension() && !sameDimension) {
				// Voz presa à dimensão: sem saber onde alguém está, o mais seguro é não repassar.
				continue;
			}
			Proximity how = rule == null ? Proximity.NORMAL : rule.between(speaker, listener);
			if (how == Proximity.NEVER) {
				continue;
			}
			if (how == Proximity.NORMAL && sameDimension && !shouldRelay(echo, speaker, listener, from, to, reach)) {
				continue;
			}
			if (out == null) {
				out = new ArrayList<>(candidates.size());
			}
			out.add(listener);
		}
		return out == null ? List.of() : out;
	}

	/** Perto demais (já se ouvem pela proximidade): não repassa. Entre os dois limites, mantém o que já fazia. */
	private static boolean shouldRelay(Echo echo, UUID speaker, UUID listener, Where from, Where to, double reach) {
		double distance = distance(from, to);
		boolean relay;
		if (distance >= reach * RELAY_ON) {
			relay = true;
		} else if (distance < reach * RELAY_OFF) {
			relay = false;
		} else {
			relay = echo != null && echo.get(speaker, listener);
		}
		if (echo != null) {
			echo.set(speaker, listener, relay);
		}
		return relay;
	}

	private static double distance(Where a, Where b) {
		double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
