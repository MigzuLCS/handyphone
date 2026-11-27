package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.photo.PhotoCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fotos guardadas no próprio computador (1.3): a cópia das fotos que o jogador tira (Ajustes > Privacidade) e, num servidor sem o
 * HandyPhone, a única galeria. Uma pasta por servidor ou mundo dentro de {@code <jogo>/handyphone/photos/}; os arquivos são JPEGs comuns,
 * que dá para abrir fora do jogo.
 *
 * <p><b>Nada aqui fala com o servidor.</b> E o que está no disco é tratado como suspeito (outro programa, ou alguém, pode ter posto
 * qualquer coisa na pasta): só entram nomes no formato que esta classe cria ({@link #NAME}), nada fora da pasta (nem atalhos/links),
 * arquivos acima de {@link PhotoCodec#MAX_PHOTO_BYTES} são ignorados, e quem mostra a foto decodifica com
 * {@link PhotoCodec#decodeStored} (só JPEG básico, dimensões conferidas antes de decodificar), como uma foto vinda de um servidor.
 * A pasta inteira tem um teto ({@link #setCapMb}); cheio, para de gravar e avisa, sem apagar nada sozinho.
 */
public final class LocalPhotos {
	/** Nome de arquivo aceito: data e hora da foto, um contador opcional, ".jpg". Nada de separadores, "..", espaços ou outras extensões. */
	static final Pattern NAME = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})\\.(\\d{2})\\.(\\d{2})(?:_(\\d{1,3}))?\\.jpg");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss", Locale.ROOT);
	/** Quantos arquivos a varredura da pasta inteira olha no máximo (contar o espaço usado não pode virar trabalho sem fim). */
	private static final int MAX_SCAN = 50_000;
	/** Tamanho máximo do nome da pasta de um servidor. */
	private static final int MAX_PLACE = 64;

	private volatile Path root;
	private String place = "";
	private long capBytes = 500L * 1024 * 1024;
	/** Bytes ocupados na pasta inteira (todas as subpastas); -1 = ainda não contado. */
	private long usedBytes = -1;

	/** Uma foto da pasta: o nome do arquivo (relativo à pasta do servidor), quando foi tirada e o tamanho. */
	public record Entry(String name, long time, long size) {
	}

	public synchronized void setRoot(Path root) {
		this.root = root;
		usedBytes = -1;
		fullCached = false;
	}

	/** Lido sem o lock (volátil): quem desenha ou confere antes de disparar não espera uma listagem da pasta. */
	public Path root() {
		return root;
	}

	/** O servidor ou mundo atual (vira o nome da subpasta, limpo: ver {@link #folderName}). */
	public synchronized void setPlace(String place) {
		this.place = folderName(place);
	}

	public synchronized String place() {
		return place;
	}

	public synchronized void setCapMb(int mb) {
		capBytes = Math.max(1, mb) * 1024L * 1024L;
		updateFull();
	}

	/** Nome seguro de pasta: minúsculas, só letras, números, ponto, hífen e sublinhado; nada de "." ou ".." sozinhos; até 64 caracteres. */
	static String folderName(String raw) {
		String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT).strip();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length() && out.length() < MAX_PLACE; i++) {
			char c = s.charAt(i);
			out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' ? c : '_');
		}
		String name = out.toString().replaceAll("^\\.+", "_");
		return name.isEmpty() || name.chars().allMatch(c -> c == '.' || c == '_') ? "unknown" : name;
	}

	/** A pasta do servidor atual, ou null se não há onde gravar. */
	synchronized Path folder() {
		if (root == null || place.isEmpty()) {
			return null;
		}
		Path folder = root.resolve(place).normalize();
		return folder.startsWith(root.normalize()) ? folder : null;
	}

	/** Resultado da última conta de espaço (atualizado quando o uso muda); lido sem esperar o disco, por quem desenha. */
	private volatile boolean fullCached;

	/**
	 * A pasta inteira não tem mais espaço para uma foto do tamanho máximo? Não toca no disco nem espera outra thread: devolve a última
	 * conta (a gravação de verdade confere de novo). Antes da primeira conta, falso.
	 */
	public boolean full() {
		return fullCached;
	}

	private void updateFull() {
		fullCached = usedBytes >= 0 && usedBytes + PhotoCodec.MAX_PHOTO_BYTES > capBytes;
	}

	/** Bytes de fotos na pasta inteira (todas as subpastas). */
	public synchronized long usedBytes() {
		return used();
	}

	public synchronized long capBytes() {
		return capBytes;
	}

	/**
	 * Grava uma foto (o mesmo JPEG que iria ao servidor). Devolve o nome do arquivo, ou null se não deu (sem pasta, cheio, erro de
	 * disco). Grava num temporário e troca de uma vez, para nunca deixar meia foto com nome válido.
	 */
	public synchronized String save(byte[] jpeg, long takenMillis) {
		Path folder = folder();
		if (folder == null || jpeg.length < 4 || jpeg.length > PhotoCodec.MAX_PHOTO_BYTES || used() + jpeg.length > capBytes) {
			return null;
		}
		try {
			Files.createDirectories(folder);
			String base = STAMP.format(Instant.ofEpochMilli(takenMillis).atZone(ZoneId.systemDefault()));
			String name = base + ".jpg";
			for (int n = 2; Files.exists(folder.resolve(name), LinkOption.NOFOLLOW_LINKS); n++) {
				if (n > 999) {
					return null;
				}
				name = base + "_" + n + ".jpg";
			}
			Path temp = folder.resolve(name + ".tmp");
			Files.write(temp, jpeg);
			Files.move(temp, folder.resolve(name), StandardCopyOption.ATOMIC_MOVE);
			usedBytes = used() + jpeg.length;
			updateFull();
			return name;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** As fotos da pasta do servidor atual, da mais nova para a mais antiga, até {@code max}. Só arquivos comuns com nome válido. */
	public synchronized List<Entry> list(int max) {
		Path folder = folder();
		List<Entry> out = new ArrayList<>();
		if (folder == null || !Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
			return out;
		}
		try (Stream<Path> files = Files.list(folder)) {
			files.limit(MAX_SCAN).forEach(file -> {
				String name = file.getFileName().toString();
				Matcher m = NAME.matcher(name);
				if (!m.matches()) {
					return;
				}
				try {
					BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
					if (!attrs.isRegularFile() || attrs.size() < 4 || attrs.size() > PhotoCodec.MAX_PHOTO_BYTES) {
						return;
					}
					out.add(new Entry(name, timeOf(m), attrs.size()));
				} catch (IOException | RuntimeException e) {
					// Arquivo sumiu ou não dá para ler: fica de fora.
				}
			});
		} catch (IOException | RuntimeException e) {
			return out;
		}
		out.sort((a, b) -> {
			int byTime = Long.compare(b.time(), a.time());
			return byTime != 0 ? byTime : b.name().compareTo(a.name());
		});
		return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
	}

	/** Os bytes de uma foto da pasta atual, ou null se o nome não é válido, não é um arquivo comum dentro da pasta ou passa do tamanho. */
	public synchronized byte[] read(String name) {
		Path file = resolve(name);
		if (file == null) {
			return null;
		}
		try {
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attrs.isRegularFile() || attrs.size() < 4 || attrs.size() > PhotoCodec.MAX_PHOTO_BYTES) {
				return null;
			}
			byte[] data = Files.readAllBytes(file);
			return data.length <= PhotoCodec.MAX_PHOTO_BYTES ? data : null;
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/** Apaga uma foto da pasta atual (só nomes válidos, só dentro da pasta). */
	public synchronized boolean delete(String name) {
		Path file = resolve(name);
		if (file == null) {
			return false;
		}
		try {
			long size = Files.size(file);
			boolean gone = Files.deleteIfExists(file);
			if (gone && usedBytes >= 0) {
				usedBytes = Math.max(0, usedBytes - size);
				updateFull();
			}
			return gone;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private Path resolve(String name) {
		Path folder = folder();
		if (folder == null || name == null || !NAME.matcher(name).matches()) {
			return null;
		}
		Path file = folder.resolve(name).normalize();
		return file.getParent() != null && file.getParent().equals(folder) && !Files.isSymbolicLink(file) ? file : null;
	}

	/** Quando a foto foi tirada, pelo nome (o relógio do arquivo pode ter sido mexido; o nome é o que esta classe gravou). */
	private static long timeOf(Matcher m) {
		try {
			LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
				Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
			return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	/** Bytes de .jpg na pasta inteira (todas as subpastas, um nível), contados uma vez e depois somados a cada gravação. */
	private long used() {
		if (usedBytes >= 0) {
			return usedBytes;
		}
		long total = 0;
		if (root != null && Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			try (Stream<Path> files = Files.walk(root, 2)) {
				total = files.limit(MAX_SCAN).filter(f -> f.getFileName().toString().endsWith(".jpg")).mapToLong(f -> {
					try {
						BasicFileAttributes a = Files.readAttributes(f, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
						return a.isRegularFile() ? a.size() : 0;
					} catch (IOException | RuntimeException e) {
						return 0;
					}
				}).sum();
			} catch (IOException | RuntimeException e) {
				total = 0;
			}
		}
		usedBytes = total;
		updateFull();
		return total;
	}
}
