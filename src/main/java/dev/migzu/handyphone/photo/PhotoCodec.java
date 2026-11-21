package dev.migzu.handyphone.photo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Tudo o que mexe em pixels de foto, usado pelos dois lados: o cliente reduz e
 * recodifica antes de enviar, e o servidor usa as mesmas funções para provar que
 * o que recebeu é mesmo uma imagem dentro dos limites. Só usa o ImageIO que já
 * vem no Java (sem dependências) e nenhuma classe do Minecraft.
 *
 * <p>Regra de formato: entra qualquer coisa que o Java leia sozinho, sai um
 * formato só ({@link #FORMAT_JPEG}). O número do formato é guardado junto de cada
 * foto no servidor, para um codificador futuro (WebP, GIF animado) conviver com as
 * fotos antigas.
 */
public final class PhotoCodec {
	/** Formato guardado junto de cada foto. Hoje só existe JPEG. */
	public static final int FORMAT_JPEG = 1;

	/** Lado maior de uma foto guardada, em pixels. */
	public static final int MAX_DIM = 640;
	/** Lado maior da miniatura guardada pelo servidor. */
	public static final int THUMB_DIM = 128;
	/** Tamanho máximo de uma foto (os bytes do arquivo). */
	public static final int MAX_PHOTO_BYTES = 200 * 1024;
	/** Tamanho máximo de uma miniatura. */
	public static final int MAX_THUMB_BYTES = 24 * 1024;
	/** Tamanho que o codificador do cliente procura não ultrapassar. */
	public static final int TARGET_BYTES = 80 * 1024;
	/** Maior arquivo que o cliente aceita ler do computador. */
	public static final long MAX_IMPORT_FILE_BYTES = 32L * 1024 * 1024;
	/** Maior imagem (em pixels) que o cliente aceita decodificar ao importar. */
	private static final long MAX_IMPORT_PIXELS = 100_000_000L;
	/** Dimensão máxima aceita por quem exibe uma foto já guardada (folga sobre {@link #MAX_DIM}). */
	private static final int MAX_STORED_DIM = 1024;

	/** Formatos que o Java lê por conteúdo; o resto (como o WBMP, que não tem assinatura) é recusado. */
	private static final Set<String> IMPORT_FORMATS = Set.of("jpeg", "png", "bmp", "gif");

	private PhotoCodec() {
	}

	/**
	 * Motivo de uma imagem recusada: uma chave de texto ({@code handyphone.notice.photo.*}) e seus argumentos; quem mostra
	 * ao jogador traduz no idioma dele. {@link #getMessage()} é o texto em inglês (para logs e testes).
	 */
	public static final class PhotoException extends Exception {
		private final String key;
		private final String[] args;

		public PhotoException(String key, String... args) {
			super(dev.migzu.handyphone.i18n.LangFile.format(dev.migzu.handyphone.i18n.LangFile.english(key), (Object[]) args));
			this.key = key;
			this.args = args;
		}

		public String key() {
			return key;
		}

		public String[] args() {
			return args.clone();
		}
	}

	public static String extension(int format) {
		return format == FORMAT_JPEG ? "jpg" : "bin";
	}

	public static boolean knownFormat(int format) {
		return format == FORMAT_JPEG;
	}

	// ------------------------------------------------------------------ servidor e cliente: foto já guardada

	/** O que o servidor guarda de uma foto recebida: arquivos novos, feitos por ele mesmo. */
	public record Stored(byte[] full, byte[] thumb, int width, int height) {
	}

	/**
	 * Valida a foto recebida de um cliente e devolve o que deve ser guardado: o JPEG
	 * <b>recodificado pelo servidor</b> (quem baixa nunca recebe os bytes do remetente) e a
	 * miniatura. Recodificar descarta qualquer metadado ou conteúdo escondido no arquivo.
	 */
	public static Stored normalize(byte[] data, int format) throws PhotoException {
		BufferedImage image = validate(data, format);
		byte[] full = null;
		for (float quality = 0.85f; quality >= 0.4f && full == null; quality -= 0.1f) {
			byte[] candidate = jpeg(image, quality);
			if (candidate.length <= MAX_PHOTO_BYTES) {
				full = candidate;
			}
		}
		if (full == null) {
			throw new PhotoException("handyphone.notice.photo.recompressed_too_big");
		}
		return new Stored(full, thumbnail(image), image.getWidth(), image.getHeight());
	}

	/**
	 * Confere que os bytes são uma foto válida no formato declarado e a decodifica.
	 * Antes de qualquer decodificação, percorre os marcadores do JPEG aceitando só um JPEG
	 * básico (sequencial, uma varredura, sem perfil de cor nem metadados) e as dimensões do
	 * cabeçalho são recusadas antes de alocar pixels. Isso fecha os casos clássicos de
	 * CPU/memória desproporcionais ao tamanho: JPEG progressivo com milhares de varreduras,
	 * perfis ICC e dimensões gigantes declaradas em poucos bytes.
	 */
	public static BufferedImage validate(byte[] data, int format) throws PhotoException {
		if (!knownFormat(format)) {
			throw new PhotoException("handyphone.notice.photo.unknown_format");
		}
		if (data.length < 4 || data.length > MAX_PHOTO_BYTES) {
			throw new PhotoException("handyphone.notice.photo.size_limit");
		}
		scanBaselineJpeg(data);
		return decode(data, Set.of("jpeg"), MAX_DIM, 1);
	}

	/** Percorre os segmentos do JPEG; lança se houver qualquer coisa fora da lista fechada. */
	static void scanBaselineJpeg(byte[] d) throws PhotoException {
		PhotoException bad = new PhotoException("handyphone.notice.photo.not_jpeg");
		if (d.length < 4 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
			throw bad;
		}
		int pos = 2, markers = 0;
		boolean sof = false, sos = false;
		while (true) {
			if (pos + 2 > d.length || (d[pos] & 0xFF) != 0xFF || ++markers > 64) {
				throw bad;
			}
			int marker = d[pos + 1] & 0xFF;
			if (marker == 0xD9) {
				// O fim da imagem precisa ser o fim do arquivo: nada colado atrás.
				if (!sos || pos + 2 != d.length) {
					throw bad;
				}
				return;
			}
			if (pos + 4 > d.length) {
				throw bad;
			}
			int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
			if (len < 2 || pos + 2 + len > d.length) {
				throw bad;
			}
			switch (marker) {
				case 0xE0, 0xDB, 0xC4, 0xDD -> {
					// JFIF, tabelas de quantização e de Huffman, intervalo de reinício.
				}
				case 0xC0 -> {
					// Quadro baseline: 8 bits, dimensões dentro do limite, 1 ou 3 componentes com amostragem de até 2x2.
					int components = len >= 8 ? d[pos + 9] & 0xFF : -1;
					if (sof || len != 8 + 3 * components || (components != 1 && components != 3) || (d[pos + 4] & 0xFF) != 8) {
						throw bad;
					}
					int height = ((d[pos + 5] & 0xFF) << 8) | (d[pos + 6] & 0xFF);
					int width = ((d[pos + 7] & 0xFF) << 8) | (d[pos + 8] & 0xFF);
					if (width < 1 || height < 1 || width > MAX_DIM || height > MAX_DIM) {
						throw new PhotoException("handyphone.notice.photo.dimension_limit");
					}
					for (int i = 0; i < components; i++) {
						int sampling = d[pos + 11 + i * 3] & 0xFF;
						if ((sampling >> 4) < 1 || (sampling >> 4) > 2 || (sampling & 0xF) < 1 || (sampling & 0xF) > 2) {
							throw bad;
						}
					}
					sof = true;
				}
				case 0xDA -> {
					if (!sof || sos) {
						throw bad;
					}
					sos = true;
					// Dados da varredura: segue até o próximo marcador de verdade (FF 00 é byte "recheado", FF D0-D7 é reinício).
					int p = pos + 2 + len;
					while (p + 1 < d.length) {
						if ((d[p] & 0xFF) == 0xFF) {
							int next = d[p + 1] & 0xFF;
							if (next != 0x00 && (next < 0xD0 || next > 0xD7)) {
								break;
							}
							p += 2;
						} else {
							p++;
						}
					}
					pos = p;
					continue;
				}
				default -> throw bad;
			}
			pos += 2 + len;
		}
	}

	/** Decodifica uma foto ou miniatura baixada do servidor, com as mesmas defesas. */
	public static BufferedImage decodeStored(byte[] data) throws PhotoException {
		if (data.length < 4 || data.length > MAX_PHOTO_BYTES) {
			throw new PhotoException("handyphone.notice.photo.size_limit");
		}
		// Um servidor hostil pode mandar qualquer coisa: só JPEG básico (uma varredura, sem progressivo), como no servidor.
		scanBaselineJpeg(data);
		return decode(data, Set.of("jpeg"), MAX_STORED_DIM, 1);
	}

	/**
	 * Lê as dimensões do cabeçalho, recusa se passarem do limite e só então decodifica.
	 *
	 * @param subsample fator de leitura reduzida (1 = tamanho real); só a importação usa mais que 1
	 */
	private static BufferedImage decode(byte[] data, Set<String> formats, int maxDim, int subsample) throws PhotoException {
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
			ImageReader reader = pickReader(in, formats);
			try {
				reader.setInput(in, true, true);
				int w = reader.getWidth(0), h = reader.getHeight(0);
				if (w < 1 || h < 1 || w > maxDim || h > maxDim) {
					throw new PhotoException("handyphone.notice.photo.dimension_limit");
				}
				ImageReadParam param = reader.getDefaultReadParam();
				if (subsample > 1) {
					param.setSourceSubsampling(subsample, subsample, 0, 0);
				}
				BufferedImage image = reader.read(0, param);
				if (image == null) {
					throw new PhotoException("handyphone.notice.photo.unreadable");
				}
				return image;
			} finally {
				reader.dispose();
			}
		} catch (PhotoException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			throw new PhotoException("handyphone.notice.photo.unreadable_format");
		}
	}

	private static ImageReader pickReader(ImageInputStream in, Set<String> formats) throws PhotoException, IOException {
		Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
		while (readers.hasNext()) {
			ImageReader reader = readers.next();
			if (formats.contains(reader.getFormatName().toLowerCase())) {
				return reader;
			}
			reader.dispose();
		}
		throw new PhotoException("handyphone.notice.photo.unsupported_format");
	}

	// ------------------------------------------------------------------ cliente: importação do computador

	/**
	 * Lê uma imagem qualquer (PNG, JPEG, BMP ou o primeiro quadro de um GIF) de bytes
	 * vindos do computador. Imagens enormes são lidas já reduzidas para não estourar a
	 * memória, e a orientação gravada por câmeras de celular (EXIF) é aplicada.
	 */
	public static BufferedImage decodeImport(byte[] data) throws PhotoException {
		if (data.length < 8 || data.length > MAX_IMPORT_FILE_BYTES) {
			throw new PhotoException("handyphone.notice.photo.file_size");
		}
		int w, h;
		try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
			ImageReader reader = pickReader(in, IMPORT_FORMATS);
			try {
				reader.setInput(in, true, true);
				w = reader.getWidth(0);
				h = reader.getHeight(0);
			} finally {
				reader.dispose();
			}
		} catch (PhotoException e) {
			throw e;
		} catch (IOException | RuntimeException e) {
			throw new PhotoException("handyphone.notice.photo.unreadable_format");
		}
		if (w < 1 || h < 1 || (long) w * h > MAX_IMPORT_PIXELS) {
			throw new PhotoException("handyphone.notice.photo.too_large");
		}
		// Mantém pelo menos o dobro da resolução final: o resto é redução de qualidade depois.
		int subsample = Math.max(1, Math.max(w, h) / (MAX_DIM * 2));
		BufferedImage image = decode(data, IMPORT_FORMATS, Integer.MAX_VALUE, subsample);
		return applyOrientation(image, exifOrientation(data));
	}

	// ------------------------------------------------------------------ cliente: codificação para envio

	/**
	 * Reduz para no máximo {@link #MAX_DIM} pixels no lado maior e comprime em JPEG,
	 * procurando ficar até {@link #TARGET_BYTES}. Nunca passa de {@link #MAX_PHOTO_BYTES}.
	 * Como é recodificado do zero, nenhum metadado do original (localização, por
	 * exemplo) sobrevive.
	 */
	public static byte[] encodeForUpload(BufferedImage source) throws PhotoException {
		BufferedImage image = fit(source, MAX_DIM);
		for (int attempt = 0; attempt < 6; attempt++) {
			byte[] best = null;
			for (float quality = 0.82f; quality >= 0.4f; quality -= 0.08f) {
				best = jpeg(image, quality);
				if (best.length <= TARGET_BYTES) {
					return best;
				}
			}
			if (best != null && best.length <= MAX_PHOTO_BYTES) {
				return best;
			}
			// Imagem cheia de detalhes: diminui mais e tenta de novo.
			image = fit(image, Math.max(64, Math.round(Math.max(image.getWidth(), image.getHeight()) * 0.8f)));
		}
		throw new PhotoException("handyphone.notice.photo.compress_enough");
	}

	/** Miniatura em JPEG para a grade da galeria e para os balões de conversa. */
	public static byte[] thumbnail(BufferedImage image) throws PhotoException {
		return jpeg(fit(image, THUMB_DIM), 0.75f);
	}

	/** Cópia em RGB (sem alfa, sobre fundo branco) que cabe em {@code maxDim} no lado maior. */
	public static BufferedImage fit(BufferedImage src, int maxDim) {
		int w = src.getWidth(), h = src.getHeight();
		float scale = Math.min(1f, maxDim / (float) Math.max(w, h));
		int tw = Math.max(1, Math.round(w * scale)), th = Math.max(1, Math.round(h * scale));
		BufferedImage current = src;
		// Reduz em passos de no máximo metade: a interpolação bilinear sozinha, num salto grande, deixa serrilhado.
		while (current.getWidth() > tw * 2 || current.getHeight() > th * 2) {
			current = draw(current, Math.max(tw, current.getWidth() / 2), Math.max(th, current.getHeight() / 2));
		}
		return draw(current, tw, th);
	}

	private static BufferedImage draw(BufferedImage src, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, w, h);
			g.drawImage(src, 0, 0, w, h, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private static byte[] jpeg(BufferedImage rgb, float quality) throws PhotoException {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
		try {
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(quality);
			param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
			ByteArrayOutputStream bytes = new ByteArrayOutputStream(48 * 1024);
			try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
				writer.setOutput(out);
				// Metadados nulos = nenhum EXIF: só o cabeçalho JFIF padrão.
				writer.write(null, new IIOImage(rgb, null, null), param);
			}
			return bytes.toByteArray();
		} catch (IOException | RuntimeException e) {
			throw new PhotoException("handyphone.notice.photo.compress");
		} finally {
			writer.dispose();
		}
	}

	// ------------------------------------------------------------------ orientação EXIF

	/** Orientação (1 a 8) gravada no EXIF de um JPEG; 1 (nada a fazer) para qualquer outro caso. */
	public static int exifOrientation(byte[] d) {
		try {
			if (d.length < 12 || (d[0] & 0xFF) != 0xFF || (d[1] & 0xFF) != 0xD8) {
				return 1;
			}
			int pos = 2;
			while (pos + 4 <= d.length && (d[pos] & 0xFF) == 0xFF) {
				int marker = d[pos + 1] & 0xFF;
				if (marker == 0xDA || marker == 0xD9) {
					break;
				}
				int len = ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
				if (len < 2) {
					break;
				}
				int start = pos + 4;
				if (marker == 0xE1 && len >= 16 && start + 6 <= d.length
					&& d[start] == 'E' && d[start + 1] == 'x' && d[start + 2] == 'i' && d[start + 3] == 'f') {
					return tiffOrientation(d, start + 6, Math.min(d.length, pos + 2 + len));
				}
				pos += 2 + len;
			}
		} catch (RuntimeException ignored) {
			// Cabeçalho torto: segue sem girar.
		}
		return 1;
	}

	private static int tiffOrientation(byte[] d, int tiff, int end) {
		if (tiff + 8 > end) {
			return 1;
		}
		boolean little = d[tiff] == 'I';
		int ifd = tiff + (int) u32(d, tiff + 4, little);
		if (ifd < tiff || ifd + 2 > end) {
			return 1;
		}
		int entries = u16(d, ifd, little);
		for (int i = 0; i < entries; i++) {
			int e = ifd + 2 + i * 12;
			if (e + 12 > end) {
				break;
			}
			if (u16(d, e, little) == 0x0112) {
				int value = u16(d, e + 8, little);
				return value >= 1 && value <= 8 ? value : 1;
			}
		}
		return 1;
	}

	private static int u16(byte[] d, int p, boolean little) {
		return little ? (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) : ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
	}

	private static long u32(byte[] d, int p, boolean little) {
		return little
			? (u16(d, p, true) | ((long) u16(d, p + 2, true) << 16))
			: (((long) u16(d, p, false) << 16) | u16(d, p + 2, false));
	}

	/** Gira/espelha a imagem conforme o valor de orientação do EXIF. */
	static BufferedImage applyOrientation(BufferedImage src, int orientation) {
		if (orientation <= 1 || orientation > 8) {
			return src;
		}
		int w = src.getWidth(), h = src.getHeight();
		boolean swap = orientation >= 5;
		AffineTransform t = new AffineTransform();
		switch (orientation) {
			case 2 -> { t.scale(-1, 1); t.translate(-w, 0); }
			case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
			case 4 -> { t.scale(1, -1); t.translate(0, -h); }
			case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
			case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
			case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(-Math.PI / 2); }
			default -> { t.translate(0, w); t.rotate(-Math.PI / 2); }
		}
		BufferedImage out = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, out.getWidth(), out.getHeight());
			g.drawImage(src, t, null);
		} finally {
			g.dispose();
		}
		return out;
	}
}
