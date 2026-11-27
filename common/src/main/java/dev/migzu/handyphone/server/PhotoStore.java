package dev.migzu.handyphone.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.photo.PhotoCodec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Armazenamento das fotos no disco: {@code <mundo>/handyphone/photos/}.
 *
 * <ul>
 *   <li>{@code <id>.<ext>} e {@code <id>.t.<ext>}: a foto e a miniatura. A extensão vem do
 *       formato guardado junto de cada foto, então um codificador novo no futuro
 *       convive com as fotos antigas.</li>
 *   <li>{@code <uuid>.json}: o índice de um jogador, com os metadados das fotos dele.</li>
 * </ul>
 *
 * Só os índices ficam na memória; os bytes das fotos são lidos do disco quando pedidos.
 * Os arquivos e o índice são gravados na hora (em arquivo temporário e troca no fim), e
 * os arquivos de fotos removidas só são apagados no próximo salvamento do mundo, para uma
 * queda no meio nunca deixar um índice apontando para um arquivo que não existe.
 *
 * <p>Gravar arquivos ({@link #writeFiles}) é seguro em qualquer thread; todo o resto
 * deve ser chamado na thread do servidor.
 */
final class PhotoStore {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Pattern FILE_NAME = Pattern.compile("(\\d+)(\\.t)?\\.[a-z0-9]+(\\.tmp)?");

	static final class Photo {
		long id;
		UUID owner;
		long time;
		/** Formato do arquivo (ver {@link PhotoCodec}); hoje sempre JPEG. */
		int format = PhotoCodec.FORMAT_JPEG;
		int size;
		int thumbSize;
		int width;
		int height;
		/** O cliente declarou que veio de um arquivo do computador (não é garantia; serve para auditoria). */
		boolean imported;
		/**
		 * Apagada pelo dono, mas ainda enviada em alguma conversa (1.3): sai da galeria dele e não conta no espaço dele; o arquivo fica
		 * para quem recebeu, até nenhuma conversa usá-la. Conta no total do servidor. Índice antigo sem o campo = visível.
		 */
		boolean hidden;
		/** Onde a foto foi tirada (1.3; só fotos da câmera): dimensão (null = sem local, inclusive fotos antigas) e o bloco do jogador. */
		String dim;
		int x;
		int y;
		int z;

		long bytes() {
			return (long) size + thumbSize;
		}
	}

	private static final class PlayerIndex {
		List<Photo> photos = new ArrayList<>();
	}

	private final Path dir;
	private final Map<Long, Photo> byId = new HashMap<>();
	private final Map<UUID, Map<Long, Photo>> byOwner = new HashMap<>();
	private final Map<UUID, Long> used = new HashMap<>();
	/** Bytes das fotos apagadas pelo dono que seguem em conversas, por dono. */
	private final Map<UUID, Long> hiddenUsed = new HashMap<>();
	private long total;
	private final List<Path> pendingDeletes = new ArrayList<>();
	/** Maior id visto em disco (índices e arquivos), para nunca reaproveitar um id depois de uma queda. */
	private long maxIdOnDisk;

	PhotoStore(Path handyphoneDir) {
		this.dir = handyphoneDir.resolve("photos");
	}

	Path dir() {
		return dir;
	}

	// ------------------------------------------------------------------ consulta

	Photo get(long id) {
		return byId.get(id);
	}

	/** Fotos da galeria de um jogador (sem as que ele apagou), da mais recente para a mais antiga. */
	List<Photo> gallery(UUID owner) {
		List<Photo> list = all(owner);
		list.removeIf(p -> p.hidden);
		return list;
	}

	/** Todas as fotos de um jogador, também as apagadas que seguem em conversas (moderação), da mais recente para a mais antiga. */
	List<Photo> all(UUID owner) {
		Map<Long, Photo> mine = byOwner.get(owner);
		List<Photo> list = mine == null ? new ArrayList<>() : new ArrayList<>(mine.values());
		list.sort((a, b) -> Long.compare(b.id, a.id));
		return list;
	}

	/** Fotos na galeria do jogador (as apagadas não contam). */
	int count(UUID owner) {
		Map<Long, Photo> mine = byOwner.get(owner);
		if (mine == null) {
			return 0;
		}
		int n = 0;
		for (Photo p : mine.values()) {
			if (!p.hidden) {
				n++;
			}
		}
		return n;
	}

	/** Fotos apagadas pelo dono que ainda estão no índice (esperando as conversas largarem). */
	List<Photo> hiddenPhotos() {
		List<Photo> list = new ArrayList<>();
		for (Photo p : byId.values()) {
			if (p.hidden) {
				list.add(p);
			}
		}
		return list;
	}

	/**
	 * Tira a foto da galeria do dono e do espaço dele, mantendo o arquivo (ainda está numa conversa). Com {@code write} falso, o índice
	 * só é gravado depois, por {@link #flushIndex} (apagar várias de uma vez grava uma vez só).
	 */
	void hide(Photo photo, boolean write) {
		if (photo.hidden) {
			return;
		}
		photo.hidden = true;
		used.merge(photo.owner, -photo.bytes(), Long::sum);
		hiddenUsed.merge(photo.owner, photo.bytes(), Long::sum);
		if (write) {
			flushIndex(photo.owner);
		}
	}

	/** Bytes das fotos que o jogador apagou mas que seguem em conversas (têm um teto próprio: ver PhoneService.spaceError). */
	long hiddenBytes(UUID owner) {
		return hiddenUsed.getOrDefault(owner, 0L);
	}

	/** Grava o índice de um dono (depois de várias mudanças com {@code write} falso). */
	void flushIndex(UUID owner) {
		try {
			writeIndex(owner);
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.error("Couldn't write the photo index of {}", owner, e);
		}
	}

	/** Bytes (fotos + miniaturas) que o jogador ocupa. */
	long usedBytes(UUID owner) {
		return used.getOrDefault(owner, 0L);
	}

	/** Bytes de todas as fotos de todos os jogadores. */
	long totalBytes() {
		return total;
	}

	int totalCount() {
		return byId.size();
	}

	/** Maior id já visto em disco; o próximo id novo deve ser maior que este. */
	long maxIdOnDisk() {
		return maxIdOnDisk;
	}

	// ------------------------------------------------------------------ alteração

	/**
	 * Grava os arquivos de uma foto nova (a foto e a miniatura). Seguro em qualquer thread:
	 * não mexe em nenhum estado, só no disco.
	 */
	void writeFiles(long id, int format, byte[] full, byte[] thumb) throws IOException {
		writeAtomic(dir.resolve(id + "." + PhotoCodec.extension(format)), full);
		writeAtomic(dir.resolve(id + ".t." + PhotoCodec.extension(PhotoCodec.FORMAT_JPEG)), thumb);
	}

	/** Apaga os arquivos de uma foto que não chegou a ser registrada. */
	void discardFiles(long id, int format) {
		try {
			Files.deleteIfExists(dir.resolve(id + "." + PhotoCodec.extension(format)));
			Files.deleteIfExists(dir.resolve(id + ".t." + PhotoCodec.extension(PhotoCodec.FORMAT_JPEG)));
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.warn("Couldn't delete the files of photo {}", id, e);
		}
	}

	/** Registra no índice uma foto cujos arquivos já foram gravados. Em caso de erro nada fica registrado. */
	Photo register(long id, UUID owner, int format, int size, int thumbSize, int width, int height, boolean imported, String dim, int x, int y, int z)
		throws IOException {
		Photo photo = new Photo();
		photo.id = id;
		photo.owner = owner;
		photo.time = System.currentTimeMillis();
		photo.format = format;
		photo.size = size;
		photo.thumbSize = thumbSize;
		photo.width = width;
		photo.height = height;
		photo.imported = imported;
		photo.dim = dim;
		photo.x = x;
		photo.y = y;
		photo.z = z;
		index(photo);
		try {
			writeIndex(owner);
		} catch (IOException | RuntimeException e) {
			unindex(photo);
			throw e;
		}
		maxIdOnDisk = Math.max(maxIdOnDisk, id);
		return photo;
	}

	/** Remove a foto do índice agora e apaga os arquivos no próximo salvamento. */
	void remove(Photo photo) {
		remove(photo, true);
	}

	void remove(Photo photo, boolean write) {
		unindex(photo);
		pendingDeletes.add(fullPath(photo));
		pendingDeletes.add(thumbPath(photo));
		if (write) {
			flushIndex(photo.owner);
		}
	}

	/** Copia os arquivos de uma foto para uma pasta (a prova que a moderação guarda antes de apagar). */
	void copyTo(Photo photo, Path folder) throws IOException {
		Files.createDirectories(folder);
		Files.copy(fullPath(photo), folder.resolve(fullPath(photo).getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
		Files.copy(thumbPath(photo), folder.resolve(thumbPath(photo).getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
	}

	byte[] read(Photo photo, boolean thumb) throws IOException {
		return Files.readAllBytes(thumb ? thumbPath(photo) : fullPath(photo));
	}

	/** Apaga de fato os arquivos das fotos removidas; chamado depois do salvamento do mundo. */
	void flushDeletes() {
		for (Path file : pendingDeletes) {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				HandyPhoneCore.LOGGER.warn("Couldn't delete {}", file, e);
			}
		}
		pendingDeletes.clear();
	}

	// ------------------------------------------------------------------ índices

	private void index(Photo photo) {
		byId.put(photo.id, photo);
		byOwner.computeIfAbsent(photo.owner, k -> new LinkedHashMap<>()).put(photo.id, photo);
		if (!photo.hidden) {
			used.merge(photo.owner, photo.bytes(), Long::sum);
		} else {
			hiddenUsed.merge(photo.owner, photo.bytes(), Long::sum);
		}
		total += photo.bytes();
	}

	private void unindex(Photo photo) {
		byId.remove(photo.id);
		Map<Long, Photo> mine = byOwner.get(photo.owner);
		if (mine != null && mine.remove(photo.id) != null) {
			if (!photo.hidden) {
				used.merge(photo.owner, -photo.bytes(), Long::sum);
			} else {
				hiddenUsed.merge(photo.owner, -photo.bytes(), Long::sum);
			}
			total -= photo.bytes();
		}
	}

	/**
	 * Lê só os índices (não as fotos) de todos os jogadores, descarta entradas cujo arquivo sumiu e,
	 * se todos os índices foram lidos sem erro, apaga arquivos soltos (restos de uma queda).
	 */
	void load() {
		if (!Files.isDirectory(dir)) {
			return;
		}
		boolean allIndexesOk = true;
		List<Path> names = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			files.forEach(names::add);
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.error("Couldn't list the photos in {}", dir, e);
			return;
		}
		for (Path file : names) {
			String name = file.getFileName().toString();
			if (!name.endsWith(".json")) {
				continue;
			}
			UUID owner;
			try {
				owner = UUID.fromString(name.substring(0, name.length() - ".json".length()));
			} catch (IllegalArgumentException e) {
				continue;
			}
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				PlayerIndex loaded = GSON.fromJson(reader, PlayerIndex.class);
				boolean changed = false;
				if (loaded != null && loaded.photos != null) {
					for (Photo photo : loaded.photos) {
						if (photo == null || photo.id <= 0 || photo.size <= 0) {
							continue;
						}
						photo.owner = owner;
						if (!Files.exists(fullPath(photo)) || !Files.exists(thumbPath(photo))) {
							HandyPhoneCore.LOGGER.warn("Photo {} of {} is in the index but its file is gone; dropped", photo.id, owner);
							changed = true;
							continue;
						}
						index(photo);
						maxIdOnDisk = Math.max(maxIdOnDisk, photo.id);
					}
				}
				if (changed) {
					writeIndex(owner);
				}
			} catch (IOException | RuntimeException e) {
				allIndexesOk = false;
				HandyPhoneCore.LOGGER.error("Corrupted photo index skipped: {}", file, e);
			}
		}
		// O maior id nos nomes de arquivo também conta: uma queda pode ter deixado a foto gravada sem índice.
		int orphans = 0;
		for (Path file : names) {
			Matcher m = FILE_NAME.matcher(file.getFileName().toString());
			if (!m.matches()) {
				continue;
			}
			if (m.group(1).length() > 18) {
				continue;
			}
			long id = Long.parseLong(m.group(1));
			maxIdOnDisk = Math.max(maxIdOnDisk, id);
			boolean temp = m.group(3) != null;
			if (temp || (allIndexesOk && !byId.containsKey(id))) {
				try {
					Files.deleteIfExists(file);
					orphans++;
				} catch (IOException e) {
					HandyPhoneCore.LOGGER.warn("Couldn't delete the stray file {}", file, e);
				}
			}
		}
		if (orphans > 0) {
			HandyPhoneCore.LOGGER.info("HandyPhone: deleted {} stray photo files", orphans);
		}
	}

	private Path indexPath(UUID owner) {
		return dir.resolve(owner + ".json");
	}

	private Path fullPath(Photo photo) {
		return dir.resolve(photo.id + "." + PhotoCodec.extension(photo.format));
	}

	private Path thumbPath(Photo photo) {
		// A miniatura é sempre JPEG, independentemente do formato da foto.
		return dir.resolve(photo.id + ".t." + PhotoCodec.extension(PhotoCodec.FORMAT_JPEG));
	}

	private void writeIndex(UUID owner) throws IOException {
		PlayerIndex index = new PlayerIndex();
		Map<Long, Photo> mine = byOwner.get(owner);
		index.photos = mine == null ? new ArrayList<>() : new ArrayList<>(mine.values());
		if (index.photos.isEmpty()) {
			Files.deleteIfExists(indexPath(owner));
			return;
		}
		Files.createDirectories(dir);
		Path temp = indexPath(owner).resolveSibling(owner + ".json.tmp");
		try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
			GSON.toJson(index, writer);
		}
		DiskIo.moveAtomic(temp, indexPath(owner));
	}

	/** Grava em arquivo temporário e troca no fim, para uma queda no meio não deixar meia foto. */
	private void writeAtomic(Path file, byte[] data) throws IOException {
		Files.createDirectories(dir);
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, data);
		DiskIo.moveAtomic(temp, file);
	}
}
