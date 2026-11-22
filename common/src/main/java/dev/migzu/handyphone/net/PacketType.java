package dev.migzu.handyphone.net;

import java.util.function.Function;

/**
 * O tipo de um pacote do celular: o id no canal ({@code handyphone:<path>}) e como lê-lo. A direção vem da lista em que o
 * tipo aparece ({@link PhonePayloads#TO_CLIENT} ou {@link PhonePayloads#TO_SERVER}).
 *
 * @param <T> o pacote
 */
public final class PacketType<T extends PhonePayloads.Payload> {
	public static final String NAMESPACE = "handyphone";

	private final String path;
	private final Function<PhoneBuf, T> reader;
	/**
	 * Espaço da plataforma: o Fabric guarda aqui o seu {@code CustomPacketPayload.Type}, para enviar sem procurar em mapa
	 * nenhum. Escrito uma vez, no registro dos pacotes.
	 */
	private volatile Object platformHandle;

	private PacketType(String path, Function<PhoneBuf, T> reader) {
		this.path = path;
		this.reader = reader;
	}

	static <T extends PhonePayloads.Payload> PacketType<T> of(String path, Function<PhoneBuf, T> reader) {
		return new PacketType<>(path, reader);
	}

	/** O caminho do id ("sync_v3"). */
	public String path() {
		return path;
	}

	/** O id completo do canal ("handyphone:sync_v3"). */
	public String id() {
		return NAMESPACE + ":" + path;
	}

	/** Lê um pacote deste tipo. */
	public T read(PhoneBuf buf) {
		return reader.apply(buf);
	}

	public Object platformHandle() {
		return platformHandle;
	}

	public void setPlatformHandle(Object handle) {
		this.platformHandle = handle;
	}

	@Override
	public String toString() {
		return id();
	}
}
