package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.HandyPhone;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.system.MemoryUtil;

/**
 * Lê uma imagem da área de transferência. O jogo só lê texto ({@code ClipboardManager}) e o AWT
 * está em modo "headless" (sem área de transferência), então usamos o SDL, que expõe os dados
 * por tipo MIME. Precisa ser chamado na thread do jogo.
 */
final class ClipboardImage {
	/** Maior imagem aceita da área de transferência, igual ao limite de arquivo importado. */
	private static final int MAX_BYTES = 32 * 1024 * 1024;
	private static final String[] PREFERRED = {"image/png", "image/jpeg", "image/bmp", "image/gif"};

	private ClipboardImage() {
	}

	/** Os bytes da imagem copiada (arquivo PNG, JPEG, BMP ou GIF), ou null se a área de transferência não tem imagem. */
	static byte[] read() {
		List<String> mimes = mimeTypes();
		String chosen = null;
		for (String preferred : PREFERRED) {
			if (mimes.contains(preferred)) {
				chosen = preferred;
				break;
			}
		}
		if (chosen == null) {
			for (String mime : mimes) {
				if (mime.startsWith("image/")) {
					chosen = mime;
					break;
				}
			}
		}
		if (chosen == null) {
			HandyPhone.LOGGER.info("Clipboard has no image; types: {}", mimes);
			return null;
		}
		// Outro programa pode estar com a área de transferência aberta no instante: tenta algumas vezes.
		ByteBuffer data = null;
		for (int attempt = 0; attempt < 5 && data == null; attempt++) {
			data = SDLClipboard.SDL_GetClipboardData(chosen);
			if (data == null) {
				try {
					Thread.sleep(60);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		if (data == null) {
			HandyPhone.LOGGER.warn("Couldn't read {} from the clipboard; types: {}", chosen, mimes);
			return null;
		}
		try {
			ByteBuffer view = data.duplicate();
			if (view.remaining() < 8 || view.remaining() > MAX_BYTES) {
				return null;
			}
			byte[] bytes = new byte[view.remaining()];
			view.get(bytes);
			return "image/bmp".equals(chosen) ? withBitmapFileHeader(bytes) : bytes;
		} finally {
			// O endereço que o LWJGL entrega ao SDL_free inclui a posição do buffer: por isso a leitura acima é
			// feita numa cópia (duplicate) e o original continua na posição 0. Liberar com a posição no fim
			// corrompia o heap do processo (parada nativa 0xC0000374).
			SDLStdinc.SDL_free(data);
		}
	}

	/** Os tipos MIME que a área de transferência oferece agora (para diagnóstico e para escolher o formato). */
	static List<String> mimeTypes() {
		List<String> mimes = new ArrayList<>();
		PointerBuffer types = SDLClipboard.SDL_GetClipboardMimeTypes();
		if (types == null) {
			return mimes;
		}
		try {
			for (int i = 0; i < types.remaining(); i++) {
				long address = types.get(i);
				if (address != 0L) {
					mimes.add(MemoryUtil.memUTF8(address));
				}
			}
		} finally {
			SDLStdinc.SDL_free(types);
		}
		return mimes;
	}

	/**
	 * A área de transferência do Windows entrega bitmaps como DIB (sem o cabeçalho de arquivo de 14
	 * bytes que o leitor de BMP do Java espera). Se faltar, monta o cabeçalho.
	 */
	static byte[] withBitmapFileHeader(byte[] dib) {
		if (dib.length >= 2 && dib[0] == 'B' && dib[1] == 'M') {
			return dib;
		}
		if (dib.length < 40) {
			return dib;
		}
		int headerSize = (dib[0] & 0xFF) | ((dib[1] & 0xFF) << 8) | ((dib[2] & 0xFF) << 16) | ((dib[3] & 0xFF) << 24);
		int bitCount = (dib[14] & 0xFF) | ((dib[15] & 0xFF) << 8);
		int compression = (dib[16] & 0xFF) | ((dib[17] & 0xFF) << 8) | ((dib[18] & 0xFF) << 16) | ((dib[19] & 0xFF) << 24);
		int colorsUsed = (dib[32] & 0xFF) | ((dib[33] & 0xFF) << 8) | ((dib[34] & 0xFF) << 16) | ((dib[35] & 0xFF) << 24);
		if (headerSize < 40 || headerSize > 200) {
			return dib;
		}
		int palette = bitCount <= 8 ? (colorsUsed != 0 ? colorsUsed : 1 << bitCount) * 4 : colorsUsed * 4;
		// Com compressão BITFIELDS e cabeçalho simples, as três máscaras vêm logo depois dele.
		int masks = compression == 3 && headerSize == 40 ? 12 : 0;
		int offset = 14 + headerSize + masks + Math.max(0, palette);
		byte[] file = new byte[14 + dib.length];
		file[0] = 'B';
		file[1] = 'M';
		putInt(file, 2, file.length);
		putInt(file, 10, offset);
		System.arraycopy(dib, 0, file, 14, dib.length);
		return file;
	}

	private static void putInt(byte[] target, int at, int value) {
		target[at] = (byte) value;
		target[at + 1] = (byte) (value >> 8);
		target[at + 2] = (byte) (value >> 16);
		target[at + 3] = (byte) (value >> 24);
	}
}
