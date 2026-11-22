package dev.migzu.handyphone.net;

import java.util.Arrays;

/**
 * {@link PhoneBuf} sobre um vetor de bytes que cresce sozinho: para plataformas que recebem e mandam os pacotes como bytes
 * crus (plugin messaging do Paper) e para os testes sem jogo.
 */
public final class ByteArrayBuf implements PhoneBuf {
	private byte[] data;
	private int readerIndex;
	private int writerIndex;

	/** Um buffer vazio para escrever. */
	public ByteArrayBuf() {
		this(new byte[64], 0);
	}

	/** Um buffer para ler {@code data} inteiro (o vetor não é copiado). */
	public ByteArrayBuf(byte[] data) {
		this(data, data.length);
	}

	private ByteArrayBuf(byte[] data, int length) {
		this.data = data;
		this.writerIndex = length;
	}

	/** Cópia dos bytes escritos e ainda não lidos. */
	public byte[] toByteArray() {
		return Arrays.copyOfRange(data, readerIndex, writerIndex);
	}

	private void ensure(int more) {
		if (writerIndex + more > data.length) {
			data = Arrays.copyOf(data, Math.max(data.length * 2, writerIndex + more));
		}
	}

	private void need(int bytes) {
		if (bytes > writerIndex - readerIndex) {
			throw new IndexOutOfBoundsException("readerIndex(" + readerIndex + ") + length(" + bytes + ") exceeds writerIndex(" + writerIndex + ")");
		}
	}

	@Override
	public byte readByte() {
		need(1);
		return data[readerIndex++];
	}

	@Override
	public void writeByte(int value) {
		ensure(1);
		data[writerIndex++] = (byte) value;
	}

	@Override
	public void readBytes(byte[] dst) {
		need(dst.length);
		System.arraycopy(data, readerIndex, dst, 0, dst.length);
		readerIndex += dst.length;
	}

	@Override
	public void writeBytes(byte[] src, int offset, int length) {
		ensure(length);
		System.arraycopy(src, offset, data, writerIndex, length);
		writerIndex += length;
	}

	@Override
	public int readInt() {
		need(4);
		int value = (data[readerIndex] & 0xff) << 24 | (data[readerIndex + 1] & 0xff) << 16 | (data[readerIndex + 2] & 0xff) << 8 | data[readerIndex + 3] & 0xff;
		readerIndex += 4;
		return value;
	}

	@Override
	public void writeInt(int value) {
		ensure(4);
		data[writerIndex++] = (byte) (value >>> 24);
		data[writerIndex++] = (byte) (value >>> 16);
		data[writerIndex++] = (byte) (value >>> 8);
		data[writerIndex++] = (byte) value;
	}

	@Override
	public long readLong() {
		long high = readInt() & 0xffffffffL;
		long low = readInt() & 0xffffffffL;
		return high << 32 | low;
	}

	@Override
	public void writeLong(long value) {
		writeInt((int) (value >>> 32));
		writeInt((int) value);
	}

	@Override
	public int readableBytes() {
		return writerIndex - readerIndex;
	}

	@Override
	public String readUtf8(int length) {
		need(length);
		String text = PhoneBuf.decodeUtf8(data, readerIndex, length);
		readerIndex += length;
		return text;
	}
}
