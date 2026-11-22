package dev.migzu.handyphone.net;

import java.nio.charset.StandardCharsets;

/**
 * Leitor e escritor de bytes dos pacotes do celular, no formato EXATO do {@code FriendlyByteBuf} do Minecraft 26.3
 * (varint, varlong, texto UTF-8 com tamanho, booleano, int e long big-endian, vetor de bytes com tamanho). Um cliente
 * 1.0.1-beta conversa com qualquer servidor que use esta classe, e vice-versa: o teste de protocolo compara byte a byte.
 *
 * <p>Cada plataforma só fornece as operações cruas (um byte, um bloco de bytes, int e long, quanto falta ler, e a
 * decodificação de um trecho UTF-8); o formato em si mora aqui, nos métodos {@code default}. O Fabric embrulha o
 * {@code ByteBuf} do Netty; o Paper (ou um teste sem jogo) usa {@link ByteArrayBuf}.
 *
 * <p>Erros de formato (texto longo demais, lista grande demais, varint com bytes demais, fim dos dados) lançam
 * {@link IllegalArgumentException} ou {@link IndexOutOfBoundsException}: o pacote é descartado e a conexão cai, como hoje.
 */
public interface PhoneBuf {
	/** Tamanho máximo de texto quando ninguém diz (o mesmo do jogo). */
	int DEFAULT_MAX_STRING = 32767;

	// ------------------------------------------------------------------ operações cruas (cada plataforma)

	byte readByte();

	void writeByte(int value);

	/** Lê {@code dst.length} bytes. */
	void readBytes(byte[] dst);

	void writeBytes(byte[] src, int offset, int length);

	/** Big-endian, como o Netty. */
	int readInt();

	void writeInt(int value);

	long readLong();

	void writeLong(long value);

	int readableBytes();

	/** Decodifica {@code length} bytes UTF-8 (já conferidos) como texto e avança a leitura; bytes inválidos viram U+FFFD, como {@code new String(bytes, UTF_8)}. */
	String readUtf8(int length);

	// ------------------------------------------------------------------ formato do jogo

	default boolean readBoolean() {
		return readByte() != 0;
	}

	default void writeBoolean(boolean value) {
		writeByte(value ? 1 : 0);
	}

	/** {@code VarInt.read}: até 5 bytes; o 6º lança erro. */
	default int readVarInt() {
		int value = 0;
		int count = 0;
		byte b;
		do {
			b = readByte();
			value |= (b & 127) << count++ * 7;
			if (count > 5) {
				throw new IllegalArgumentException("VarInt too big");
			}
		} while ((b & 128) != 0);
		return value;
	}

	/** {@code VarInt.write}. */
	default void writeVarInt(int value) {
		while ((value & -128) != 0) {
			writeByte(value & 127 | 128);
			value >>>= 7;
		}
		writeByte(value);
	}

	/** {@code VarLong.read}: até 10 bytes; o 11º lança erro. */
	default long readVarLong() {
		long value = 0L;
		int count = 0;
		byte b;
		do {
			b = readByte();
			value |= (long) (b & 127) << count++ * 7;
			if (count > 10) {
				throw new IllegalArgumentException("VarLong too big");
			}
		} while ((b & 128) != 0);
		return value;
	}

	/** {@code VarLong.write}. */
	default void writeVarLong(long value) {
		while ((value & -128L) != 0L) {
			writeByte((int) (value & 127L) | 128);
			value >>>= 7;
		}
		writeByte((int) value);
	}

	default String readUtf() {
		return readUtf(DEFAULT_MAX_STRING);
	}

	/** {@code Utf8String.read}: tamanho em bytes (varint) e o texto; mais de {@code maxLength} caracteres lança erro. */
	default String readUtf(int maxLength) {
		int maxBytes = maxLength * 3;
		int length = readVarInt();
		if (length > maxBytes) {
			throw new IllegalArgumentException("The received encoded string buffer length is longer than maximum allowed (" + length + " > " + maxBytes + ")");
		}
		if (length < 0) {
			throw new IllegalArgumentException("The received encoded string buffer length is less than zero! Weird string!");
		}
		int readable = readableBytes();
		if (length > readable) {
			throw new IllegalArgumentException("Not enough bytes in buffer, expected " + length + ", but got " + readable);
		}
		String text = readUtf8(length);
		if (text.length() > maxLength) {
			throw new IllegalArgumentException("The received string length is longer than maximum allowed (" + text.length() + " > " + maxLength + ")");
		}
		return text;
	}

	default void writeUtf(String text) {
		writeUtf(text, DEFAULT_MAX_STRING);
	}

	/**
	 * {@code Utf8String.write}: o UTF-8 do Netty ({@code ByteBufUtil.writeUtf8}), que NÃO é o do Java em surrogates soltos
	 * (ver {@link #utf8Length}); com o tamanho em bytes na frente.
	 */
	default void writeUtf(String text, int maxLength) {
		if (text.length() > maxLength) {
			throw new IllegalArgumentException("String too big (was " + text.length() + " characters, max " + maxLength + ")");
		}
		int bytes = utf8Length(text);
		int maxBytes = maxLength * 3;
		if (bytes > maxBytes) {
			throw new IllegalArgumentException("String too big (was " + bytes + " bytes encoded, max " + maxBytes + ")");
		}
		writeVarInt(bytes);
		writeUtf8(this, text);
	}

	default byte[] readByteArray() {
		return readByteArray(readableBytes());
	}

	/** {@code FriendlyByteBuf.readByteArray(max)}: tamanho (varint) e os bytes. */
	default byte[] readByteArray(int maxLength) {
		int length = readVarInt();
		if (length > maxLength) {
			throw new IllegalArgumentException("ByteArray with size " + length + " is bigger than allowed " + maxLength);
		}
		byte[] data = new byte[length];
		readBytes(data);
		return data;
	}

	default void writeByteArray(byte[] data) {
		writeVarInt(data.length);
		writeBytes(data, 0, data.length);
	}

	// ------------------------------------------------------------------ UTF-8 do Netty

	/**
	 * Quantos bytes {@link #writeUtf8} escreve. Igual ao {@code ByteBufUtil.writeUtf8} do Netty: um surrogate alto seguido
	 * de algo que não é surrogate baixo vira {@code '?'} mais o segundo caractere truncado em um byte (ou outro {@code '?'}
	 * se for surrogate alto); surrogate baixo solto e surrogate alto no fim viram {@code '?'}.
	 */
	static int utf8Length(CharSequence text) {
		int length = text.length();
		int bytes = 0;
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (c < 0x80) {
				bytes++;
			} else if (c < 0x800) {
				bytes += 2;
			} else if (Character.isSurrogate(c)) {
				if (!Character.isHighSurrogate(c)) {
					bytes++;
					continue;
				}
				if (++i == length) {
					bytes++;
					break;
				}
				bytes += Character.isLowSurrogate(text.charAt(i)) ? 4 : 2;
			} else {
				bytes += 3;
			}
		}
		return bytes;
	}

	private static void writeUtf8(PhoneBuf out, CharSequence text) {
		int length = text.length();
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (c < 0x80) {
				out.writeByte(c);
			} else if (c < 0x800) {
				out.writeByte(0xc0 | c >> 6);
				out.writeByte(0x80 | c & 0x3f);
			} else if (Character.isSurrogate(c)) {
				if (!Character.isHighSurrogate(c)) {
					out.writeByte('?');
					continue;
				}
				if (++i == length) {
					out.writeByte('?');
					break;
				}
				char c2 = text.charAt(i);
				if (!Character.isLowSurrogate(c2)) {
					out.writeByte('?');
					out.writeByte(Character.isHighSurrogate(c2) ? '?' : c2);
				} else {
					int codePoint = Character.toCodePoint(c, c2);
					out.writeByte(0xf0 | codePoint >> 18);
					out.writeByte(0x80 | codePoint >> 12 & 0x3f);
					out.writeByte(0x80 | codePoint >> 6 & 0x3f);
					out.writeByte(0x80 | codePoint & 0x3f);
				}
			} else {
				out.writeByte(0xe0 | c >> 12);
				out.writeByte(0x80 | c >> 6 & 0x3f);
				out.writeByte(0x80 | c & 0x3f);
			}
		}
	}

	/** Decodificação padrão (para {@link ByteArrayBuf}). */
	static String decodeUtf8(byte[] data, int offset, int length) {
		return length == 0 ? "" : new String(data, offset, length, StandardCharsets.UTF_8);
	}
}
