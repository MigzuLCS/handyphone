package dev.migzu.handyphone.client.os;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * As fotos que o celular já baixou, só na memória e com limite: miniaturas em
 * quantidade, fotos inteiras em poucas. O que sai do cache é baixado de novo quando
 * preciso. O desenho pede a imagem com {@link #thumb}/{@link #full}; se ela não está
 * aqui, o pedido ao servidor sai uma vez e só se repete se a resposta demorar demais.
 *
 * <p>Sempre usado com a trava do {@link PhoneOS} presa.
 */
public final class PhotoCache {
	private static final int MAX_THUMBS = 160;
	private static final int MAX_FULL = 6;
	/** Segundos até um pedido sem resposta ser considerado perdido e repetido. */
	private static final double REQUEST_TIMEOUT = 12;
	/** Segundos de espera depois de o servidor pedir calma (limite de downloads). */
	private static final double BACKOFF = 4;

	private final Map<Long, BufferedImage> thumbs = lru(MAX_THUMBS);
	private final Map<Long, BufferedImage> full = lru(MAX_FULL);
	/** Quando cada pedido pode ser repetido (chave: id * 2, mais 1 para foto inteira). */
	private final Map<Long, Double> nextTry = new HashMap<>();
	/** Fotos que o servidor disse que não existem ou não são nossas: não adianta pedir de novo. */
	private final Set<Long> gone = new HashSet<>();

	private static Map<Long, BufferedImage> lru(int max) {
		return new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> eldest) {
				return size() > max;
			}
		};
	}

	private static long key(long id, boolean thumb) {
		return id * 2 + (thumb ? 0 : 1);
	}

	/** Miniatura, ou null enquanto não chegou (o pedido é feito sozinho). */
	BufferedImage thumb(long id, PhoneBackend backend, double now) {
		return get(thumbs, id, true, backend, now);
	}

	/** Foto inteira, ou null enquanto não chegou. */
	BufferedImage full(long id, PhoneBackend backend, double now) {
		return get(full, id, false, backend, now);
	}

	/** A foto inteira se já está no cache (sem pedir). */
	BufferedImage peekFull(long id) {
		return full.get(id);
	}

	/** Só olha o cache, sem pedir nada ao servidor. */
	BufferedImage peekThumb(long id) {
		return thumbs.get(id);
	}

	private BufferedImage get(Map<Long, BufferedImage> map, long id, boolean thumb, PhoneBackend backend, double now) {
		BufferedImage image = map.get(id);
		if (image != null || gone.contains(id)) {
			return image;
		}
		long key = key(id, thumb);
		Double next = nextTry.get(key);
		if (next == null || now >= next) {
			nextTry.put(key, now + REQUEST_TIMEOUT);
			backend.requestPhoto(id, thumb);
		}
		return null;
	}

	/** Verdadeiro se o servidor já disse que essa foto não está disponível. */
	boolean isGone(long id) {
		return gone.contains(id);
	}

	void put(long id, boolean thumb, BufferedImage image) {
		(thumb ? thumbs : full).put(id, image);
		nextTry.remove(key(id, thumb));
	}

	/** O servidor não mandou a foto: {@code retry} = ele pediu calma; senão ela simplesmente não está disponível. */
	void failed(long id, boolean thumb, boolean retry, double now) {
		if (retry) {
			nextTry.put(key(id, thumb), now + BACKOFF);
		} else {
			gone.add(id);
			nextTry.remove(key(id, thumb));
		}
	}

	/** Esquece uma foto apagada. */
	void forget(long id) {
		thumbs.remove(id);
		full.remove(id);
		nextTry.remove(key(id, true));
		nextTry.remove(key(id, false));
	}

	void clear() {
		thumbs.clear();
		full.clear();
		nextTry.clear();
		gone.clear();
	}

	int thumbCount() {
		return thumbs.size();
	}

	int fullCount() {
		return full.size();
	}
}
