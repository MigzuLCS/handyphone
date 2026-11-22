package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.net.PhoneBuf;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;

/**
 * {@link PhoneBuf} sobre o {@code ByteBuf} do Netty que o jogo entrega ao codec do pacote: só as operações cruas; o
 * formato (varint, texto, listas) é o do {@code common}, idêntico ao do {@code FriendlyByteBuf}.
 */
public final class NettyPhoneBuf implements PhoneBuf {
	private final ByteBuf buf;

	public NettyPhoneBuf(ByteBuf buf) {
		this.buf = buf;
	}

	@Override
	public byte readByte() {
		return buf.readByte();
	}

	@Override
	public void writeByte(int value) {
		buf.writeByte(value);
	}

	@Override
	public void readBytes(byte[] dst) {
		buf.readBytes(dst);
	}

	@Override
	public void writeBytes(byte[] src, int offset, int length) {
		buf.writeBytes(src, offset, length);
	}

	@Override
	public int readInt() {
		return buf.readInt();
	}

	@Override
	public void writeInt(int value) {
		buf.writeInt(value);
	}

	@Override
	public long readLong() {
		return buf.readLong();
	}

	@Override
	public void writeLong(long value) {
		buf.writeLong(value);
	}

	@Override
	public int readableBytes() {
		return buf.readableBytes();
	}

	@Override
	public String readUtf8(int length) {
		// Como o Utf8String do jogo: decodifica no lugar e avança.
		String text = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
		buf.readerIndex(buf.readerIndex() + length);
		return text;
	}
}
