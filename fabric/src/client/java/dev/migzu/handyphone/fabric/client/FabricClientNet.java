package dev.migzu.handyphone.fabric.client;

import dev.migzu.handyphone.fabric.FabricNet;
import dev.migzu.handyphone.fabric.FabricPayload;
import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Os pacotes do celular no lado do cliente Fabric: receber, conferir e enviar pelo tipo do {@code common}. */
public final class FabricClientNet {
	private FabricClientNet() {
	}

	/** Registra quem trata um pacote vindo do servidor (na thread do jogo, como o {@code ClientPlayNetworking}). */
	@SuppressWarnings("unchecked")
	public static <T extends PhonePayloads.Payload> void receive(PacketType<T> type, BiConsumer<T, ClientPlayNetworking.Context> handler) {
		ClientPlayNetworking.registerGlobalReceiver(FabricNet.type(type), (packet, ctx) -> handler.accept((T) packet.payload(), ctx));
	}

	/** O servidor entende este pacote? */
	public static boolean canSend(PacketType<?> type) {
		return ClientPlayNetworking.canSend(FabricNet.type(type));
	}

	public static void send(PhonePayloads.Payload payload) {
		ClientPlayNetworking.send(new FabricPayload(payload));
	}
}
