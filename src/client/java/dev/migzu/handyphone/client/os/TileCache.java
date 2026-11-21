package dev.migzu.handyphone.client.os;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Os tiles do mapa (PNG do squaremap) já baixados, só na memória e com limite. Cada tile de 512x512 ocupa
 * ~1 MB decodificado, então são poucos; o que sai é baixado de novo quando o mapa volta a mostrá-lo.
 *
 * <p>O desenho pede com {@link #get}; se o tile não está aqui, o pedido sai uma vez (no máximo
 * {@link #MAX_IN_FLIGHT} ao mesmo tempo) e só se repete se a resposta demorar demais. Tile que o servidor
 * diz não ter (ainda não desenhado) fica uns segundos sem ser pedido de novo.
 *
 * <p><b>Atualização:</b> o squaremap continua desenhando enquanto o jogador anda, então um tile mostrado há mais de
 * {@link #REFRESH} segundos é conferido de novo, <i>sem sair da tela</i>: a imagem antiga continua até a nova
 * chegar, e se o arquivo não mudou o servidor só responde "não mudou" (o pedido leva o carimbo da versão que
 * já temos). A "época" muda quando o jogador troca de dimensão: tudo da anterior é jogado fora.
 *
 * <p>Sempre usado com a trava do {@link PhoneOS} presa.
 */
final class TileCache {
	record Key(int zoom, int x, int z) {
	}

	private record Cached(BufferedImage image, long stamp, double checkedAt) {
	}

	/**
	 * O desenho usa no máximo 16 tiles por quadro (ver MapApp.drawTiles); sobra um pouco para os do zoom anterior. Cada tile decodificado
	 * ocupa 1 MB (2 MB reais no G1, que reserva uma região inteira para objetos desse tamanho).
	 */
	static final int MAX_TILES = 20;
	/** Com o Mapa fechado por tanto tempo, os tiles são soltos (a memória volta; reabrir o mapa os baixa de novo, e o servidor guarda cache). */
	static final long IDLE_RELEASE_MS = 30_000;
	/** O servidor aceita poucos pedidos em andamento por jogador (3); pedir mais só gera "ocupado". */
	static final int MAX_IN_FLIGHT = 3;
	/** Segundos até um pedido sem resposta ser considerado perdido. */
	private static final double REQUEST_TIMEOUT = 15;
	/** Segundos até conferir de novo um tile que já está na tela. */
	static final double REFRESH = 10;
	/** Segundos até pedir de novo um tile que o servidor disse não ter (o squaremap pode estar desenhando agora). */
	private static final double MISSING_RETRY = 8;
	private static final double BUSY_RETRY = 3;

	private final Map<Key, Cached> images = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
			return size() > MAX_TILES;
		}
	};
	private final Map<Key, Double> pending = new HashMap<>();
	private final Map<Key, Double> retryAt = new HashMap<>();
	private boolean starved;
	private int epoch;
	/** Última vez que o Mapa desenhou tiles (relógio do sistema). */
	private long lastDrawMillis;

	int epoch() {
		return epoch;
	}

	/** Nova época (dimensão nova): nada do que havia vale mais. */
	void reset(int newEpoch) {
		images.clear();
		pending.clear();
		retryAt.clear();
		starved = false;
		epoch = newEpoch;
	}

	/** O tile, ou null enquanto não chegou (o pedido é feito sozinho). Um tile antigo continua sendo devolvido enquanto é reconferido. */
	BufferedImage get(Key key, PhoneBackend backend, double now) {
		Cached entry = images.get(key);
		if (entry != null) {
			if (now - entry.checkedAt() >= REFRESH) {
				request(key, backend, now, entry.stamp());
			}
			return entry.image();
		}
		Double retry = retryAt.get(key);
		if (retry != null && now < retry) {
			return null;
		}
		request(key, backend, now, 0);
		return null;
	}

	private void request(Key key, PhoneBackend backend, double now, long have) {
		Double asked = pending.get(key);
		if (asked != null && now - asked < REQUEST_TIMEOUT) {
			return;
		}
		if (pending.size() >= MAX_IN_FLIGHT) {
			starved = true;
			return;
		}
		pending.put(key, now);
		backend.requestTile(epoch, key.zoom(), key.x(), key.z(), have);
	}

	/** Só olha o cache, sem pedir nada. */
	BufferedImage peek(Key key) {
		Cached entry = images.get(key);
		return entry != null ? entry.image() : null;
	}

	/** O carimbo da versão em memória, ou -1 (para os testes). */
	long stampOf(Key key) {
		Cached entry = images.get(key);
		return entry != null ? entry.stamp() : -1;
	}

	void put(int forEpoch, Key key, BufferedImage image, long stamp, double now) {
		if (forEpoch != epoch) {
			return;
		}
		images.put(key, new Cached(image, stamp, now));
		pending.remove(key);
		retryAt.remove(key);
	}

	/** O servidor confirmou que a versão que temos é a atual. */
	void unchanged(int forEpoch, Key key, double now) {
		if (forEpoch != epoch) {
			return;
		}
		pending.remove(key);
		Cached entry = images.get(key);
		if (entry != null) {
			images.put(key, new Cached(entry.image(), entry.stamp(), now));
		}
	}

	/** O servidor não mandou o tile: {@code busy} = ele pediu calma; senão o tile simplesmente não existe (ainda). */
	void failed(int forEpoch, Key key, boolean busy, double now) {
		if (forEpoch != epoch) {
			return;
		}
		pending.remove(key);
		double delay = busy ? BUSY_RETRY : MISSING_RETRY;
		Cached entry = images.get(key);
		if (entry != null) {
			// Era só uma reconferência: a imagem que já está na tela fica, e a próxima tentativa vem depois do intervalo.
			images.put(key, new Cached(entry.image(), entry.stamp(), now - REFRESH + delay));
		} else {
			retryAt.put(key, now + delay);
		}
	}

	/** Começa um quadro de desenho. */
	void beginFrame() {
		starved = false;
		lastDrawMillis = System.currentTimeMillis();
	}

	/** Solta os tiles se o Mapa não os desenha há {@link #IDLE_RELEASE_MS}. Chamado de vez em quando, mesmo com o celular guardado. */
	void releaseIfIdle(long nowMillis) {
		if (!images.isEmpty() && nowMillis - lastDrawMillis > IDLE_RELEASE_MS) {
			images.clear();
			pending.clear();
			retryAt.clear();
			starved = false;
		}
	}

	/** Há tile a caminho ou pedido que ficou de fora: vale redesenhar logo, para tentar de novo. */
	boolean working() {
		return starved || !pending.isEmpty();
	}

	int count() {
		return images.size();
	}
}
