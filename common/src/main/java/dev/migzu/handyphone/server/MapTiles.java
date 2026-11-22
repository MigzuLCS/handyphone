package dev.migzu.handyphone.server;

import dev.migzu.handyphone.HandyPhoneCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Lê os tiles que o squaremap já desenhou em disco; o HandyPhone <b>não renderiza terreno</b>.
 *
 * <p>Formato do squaremap (conferido no código dele, 1.4.4): PNG de 512x512 em
 * {@code <web>/tiles/<mundo>/<zoom>/<x>_<z>.png}, onde {@code <mundo>} é o id da dimensão com
 * {@code ':'} trocado por {@code '_'}. O tile do zoom máximo cobre exatamente uma região
 * (512x512 blocos, 1 pixel por bloco) e {@code x = floorDiv(blocoX, 512)}; cada nível a menos
 * junta 2x2 tiles do nível seguinte ({@code x/2}). Pixel ainda não renderizado é transparente.
 * O squaremap grava cada tile de forma atômica, então nunca lemos um arquivo pela metade.
 *
 * <p><b>Segurança:</b> o cliente só pede números (zoom, x, z); o caminho é montado aqui, com a
 * dimensão vinda do servidor (a do jogador), e conferido para nunca sair da pasta de tiles.
 *
 * <p>Sem o squaremap instalado, {@link #info} diz "indisponível" e nada é lido. Só a classe
 * {@link SquaremapLink} toca a API dele, e ela só é carregada depois de conferir que o mod existe.
 */
public final class MapTiles {
	public static final int TILE_SIZE = 512;
	/** Tile maior que isto é recusado (um PNG de 512x512 do squaremap raramente passa de 200 KB). */
	public static final int MAX_TILE_BYTES = dev.migzu.handyphone.net.PhonePayloads.MAX_TILE_BYTES;
	/** Teto sanitário para o zoom máximo lido do disco (o padrão do squaremap é 3). */
	private static final int MAX_ZOOM_CAP = 8;
	/** Borda do mundo em regiões de 512 blocos (30.000.000 / 512). */
	private static final int WORLD_REGIONS = 58_594;
	private static final long INFO_TTL_MS = 30_000;
	private static final long CACHE_BYTES = 16L * 1024 * 1024;

	/** Situação do mapa de uma dimensão: {@code maxZoom} -1 quando não há tiles. */
	public record Info(boolean available, int maxZoom) {
		static final Info NONE = new Info(false, -1);
	}

	private record CachedInfo(Info info, long expires) {
	}

	private record CachedTile(long modified, long size, byte[] bytes) {
	}

	/** Um tile lido: {@code stamp} identifica a versão do arquivo; {@code bytes} é null quando o cliente já tem essa versão. */
	public record Tile(long stamp, byte[] bytes) {
	}

	private static long stampOf(long modified, long size) {
		return modified * 31 + size;
	}

	private final Supplier<Path> root;
	private final Predicate<String> worldEnabled;
	private final Map<String, CachedInfo> infos = new LinkedHashMap<>();
	private final Map<String, CachedTile> cache = new LinkedHashMap<>(16, 0.75f, true);
	private long cacheBytes;

	/** Para o jogo: usa o squaremap se ele estiver instalado ({@code squaremapLoaded}, que a plataforma responde). */
	public static MapTiles forGame(boolean squaremapLoaded) {
		if (squaremapLoaded) {
			try {
				return new MapTiles(SquaremapLink::tilesRoot, dimension -> {
					int colon = dimension.indexOf(':');
					return colon > 0 && SquaremapLink.worldEnabled(dimension.substring(0, colon), dimension.substring(colon + 1));
				});
			} catch (Throwable t) {
				HandyPhoneCore.LOGGER.warn("HandyPhone: squaremap is installed but its API couldn't be used; the Map app will have no terrain.", t);
			}
		}
		return new MapTiles(() -> null, dimension -> false);
	}

	/** Para os testes: uma pasta de tiles qualquer, sem o squaremap. */
	public MapTiles(Supplier<Path> root, Predicate<String> worldEnabled) {
		this.root = root;
		this.worldEnabled = worldEnabled;
	}

	/** O servidor tem como entregar algum mapa (squaremap presente e pronto)? */
	public boolean installed() {
		return root.get() != null;
	}

	/** Existe mapa renderizado para essa dimensão ({@code "minecraft:overworld"})? */
	public Info info(String dimension) {
		long now = System.currentTimeMillis();
		synchronized (this) {
			CachedInfo cached = infos.get(dimension);
			if (cached != null && cached.expires > now) {
				return cached.info;
			}
		}
		// A listagem da pasta e a API do squaremap rodam fora da trava: a thread do servidor e a de disco não esperam uma pela outra.
		Info info = scan(dimension);
		synchronized (this) {
			infos.put(dimension, new CachedInfo(info, now + INFO_TTL_MS));
			while (infos.size() > 32) {
				infos.remove(infos.keySet().iterator().next());
			}
		}
		return info;
	}

	/** Esquece o que sabia sobre as dimensões (os testes mexem na pasta de tiles). */
	public synchronized void forget() {
		infos.clear();
		cache.clear();
		cacheBytes = 0;
	}

	private Info scan(String dimension) {
		Path dir = dimensionDir(dimension);
		if (dir == null || !worldEnabled.test(dimension)) {
			return Info.NONE;
		}
		int max = -1;
		try (Stream<Path> children = Files.list(dir)) {
			for (Path child : (Iterable<Path>) children::iterator) {
				String name = child.getFileName().toString();
				if (name.length() <= 2 && name.chars().allMatch(c -> c >= '0' && c <= '9') && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
					max = Math.max(max, Integer.parseInt(name));
				}
			}
		} catch (IOException | RuntimeException e) {
			return Info.NONE;
		}
		return max < 0 ? Info.NONE : new Info(true, Math.min(max, MAX_ZOOM_CAP));
	}

	/** Pasta de tiles da dimensão, ou null se não dá para montar um caminho seguro. */
	private Path dimensionDir(String dimension) {
		Path base = root.get();
		if (base == null || dimension.isEmpty() || dimension.length() > 100) {
			return null;
		}
		try {
			Path normalBase = base.toAbsolutePath().normalize();
			Path dir = normalBase.resolve(dimension.replace(':', '_')).normalize();
			// O nome da dimensão vem do servidor, mas a conferência é barata e protege de um nome estranho de outro mod.
			return dir.startsWith(normalBase) && !dir.equals(normalBase) ? dir : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Os índices de tile aceitos neste zoom (sem passar da borda do mundo). */
	public static boolean inRange(int maxZoom, int zoom, int x, int z) {
		if (zoom < 0 || zoom > maxZoom || maxZoom > MAX_ZOOM_CAP) {
			return false;
		}
		int limit = (WORLD_REGIONS >> (maxZoom - zoom)) + 1;
		return x >= -limit && x <= limit && z >= -limit && z <= limit;
	}

	/**
	 * Bytes do PNG de um tile, ou null se o tile não existe (ainda não foi desenhado), é grande demais ou
	 * não parece um PNG do tamanho certo. Pode ser chamado em qualquer thread (faz E/S).
	 */
	public byte[] read(String dimension, int zoom, int x, int z) {
		Tile tile = read(dimension, zoom, x, z, 0);
		return tile != null ? tile.bytes() : null;
	}

	/** Igual a {@link #read(String, int, int, int)}, com o carimbo da versão; se for igual a {@code have}, os bytes vêm null (sem ler o arquivo). */
	public Tile read(String dimension, int zoom, int x, int z, long have) {
		Info info = info(dimension);
		if (!info.available() || !inRange(info.maxZoom(), zoom, x, z)) {
			return null;
		}
		Path dir = dimensionDir(dimension);
		if (dir == null) {
			return null;
		}
		Path file = dir.resolve(Integer.toString(zoom)).resolve(x + "_" + z + ".png").normalize();
		if (!file.startsWith(dir)) {
			return null;
		}
		String key = dimension + "/" + zoom + "/" + x + "_" + z;
		try {
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attrs.isRegularFile() || attrs.size() < 33 || attrs.size() > MAX_TILE_BYTES) {
				return null;
			}
			long modified = attrs.lastModifiedTime().toMillis(), size = attrs.size();
			long stamp = stampOf(modified, size);
			if (have != 0 && have == stamp) {
				return new Tile(stamp, null);
			}
			synchronized (this) {
				CachedTile hit = cache.get(key);
				if (hit != null && hit.modified == modified && hit.size == size) {
					return new Tile(stamp, hit.bytes);
				}
			}
			byte[] bytes = Files.readAllBytes(file);
			if (!looksLikeTile(bytes)) {
				return null;
			}
			synchronized (this) {
				CachedTile old = cache.put(key, new CachedTile(modified, size, bytes));
				cacheBytes += bytes.length - (old != null ? old.bytes.length : 0);
				var it = cache.entrySet().iterator();
				while (cacheBytes > CACHE_BYTES && it.hasNext()) {
					cacheBytes -= it.next().getValue().bytes.length;
					it.remove();
				}
			}
			return new Tile(stamp, bytes);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Assinatura de PNG e cabeçalho IHDR com largura e altura de no máximo 512 (nada de "bomba" de imagem). */
	static boolean looksLikeTile(byte[] b) {
		if (b.length < 33 || (b[0] & 0xFF) != 0x89 || b[1] != 'P' || b[2] != 'N' || b[3] != 'G' || b[4] != 0x0D || b[5] != 0x0A || b[6] != 0x1A || b[7] != 0x0A) {
			return false;
		}
		if (b[12] != 'I' || b[13] != 'H' || b[14] != 'D' || b[15] != 'R') {
			return false;
		}
		long width = u32(b, 16), height = u32(b, 20);
		return width >= 1 && height >= 1 && width <= TILE_SIZE && height <= TILE_SIZE;
	}

	private static long u32(byte[] b, int at) {
		return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
	}
}
