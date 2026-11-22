package dev.migzu.handyphone.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Orçamento de pacotes por jogador (balde de fichas) para TODOS os pacotes do HandyPhone que chegam ao servidor.
 * Cada tipo de pacote custa um número de fichas (os caros custam mais); as fichas voltam aos poucos.
 *
 * <p>Um pacote sem ficha suficiente é <b>descartado</b> (nunca vira trabalho). O excesso pequeno só gera um aviso
 * (no máximo um a cada {@link #NOTICE_INTERVAL_MS}); o excesso grande e contínuo, que só um cliente modificado
 * produz, vira {@link Verdict#KICK}: cada pacote descartado soma uma "violação", que vai caindo com o tempo.
 *
 * <p>O que o orçamento protege é o <b>custo da resposta</b> (cada {@code Open}, por exemplo, manda lista e histórico de
 * volta); ele não impede que pacotes crus cheguem. Contra a inundação da própria conexão, o dono do servidor pode usar
 * {@code rate-limit} no {@code server.properties}. A classe é sincronizada, então serve de qualquer thread; o relógio
 * é injetável para os testes.
 */
public final class PacketBudget {
	/** O que fazer com o pacote. */
	public enum Verdict {
		/** Dentro do orçamento: processa. */
		ALLOW,
		/** Sem fichas: descarta em silêncio (já avisamos há pouco). */
		DROP,
		/** Sem fichas: descarta e avisa o jogador no celular. */
		DROP_NOTICE,
		/** Excesso grande e contínuo: descarta e desconecta. */
		KICK
	}

	/** Fichas que um jogador pode acumular (rajada). */
	static final double CAPACITY = 60;
	/** Fichas devolvidas por segundo. */
	static final double REFILL_PER_SECOND = 12;
	/** Violações (pacotes descartados) acima das quais o jogador é desconectado. */
	static final double KICK_THRESHOLD = 300;
	/** Violações que "esfriam" por segundo. */
	static final double VIOLATION_DECAY_PER_SECOND = 20;
	/** Intervalo mínimo entre dois avisos de "devagar". */
	static final long NOTICE_INTERVAL_MS = 5000;

	private static final class Bucket {
		double tokens = CAPACITY;
		double violations;
		long lastNanos;
		long lastNoticeMs = Long.MIN_VALUE / 2;
	}

	private final Map<UUID, Bucket> buckets = new HashMap<>();
	private final LongSupplier nanoClock;
	private final LongSupplier milliClock;

	public PacketBudget() {
		this(System::nanoTime, System::currentTimeMillis);
	}

	/** Para os testes: relógios fingidos (nanossegundos e milissegundos). */
	public PacketBudget(LongSupplier nanoClock, LongSupplier milliClock) {
		this.nanoClock = nanoClock;
		this.milliClock = milliClock;
	}

	/** Cobra {@code cost} fichas do jogador e diz o que fazer com o pacote. */
	public synchronized Verdict charge(UUID player, double cost) {
		long now = nanoClock.getAsLong();
		Bucket bucket = buckets.get(player);
		if (bucket == null) {
			bucket = new Bucket();
			bucket.lastNanos = now;
			buckets.put(player, bucket);
		}
		double seconds = Math.max(0, (now - bucket.lastNanos) / 1e9);
		bucket.lastNanos = now;
		bucket.tokens = Math.min(CAPACITY, bucket.tokens + seconds * REFILL_PER_SECOND);
		bucket.violations = Math.max(0, bucket.violations - seconds * VIOLATION_DECAY_PER_SECOND);
		if (bucket.tokens >= cost) {
			bucket.tokens -= cost;
			return Verdict.ALLOW;
		}
		bucket.violations += 1;
		if (bucket.violations > KICK_THRESHOLD) {
			return Verdict.KICK;
		}
		long ms = milliClock.getAsLong();
		if (ms - bucket.lastNoticeMs >= NOTICE_INTERVAL_MS) {
			bucket.lastNoticeMs = ms;
			return Verdict.DROP_NOTICE;
		}
		return Verdict.DROP;
	}

	/** Esquece o jogador (saiu do servidor). */
	public synchronized void forget(UUID player) {
		buckets.remove(player);
	}

	/** Esquece os jogadores que não estão na lista (já saíram do servidor). */
	public synchronized void retainOnly(java.util.Set<UUID> online) {
		buckets.keySet().retainAll(online);
	}

	/** Quantos jogadores têm balde agora (testes: o mapa não pode crescer sem limite). */
	public synchronized int size() {
		return buckets.size();
	}
}
