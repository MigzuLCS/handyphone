package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.net.PhonePayloads;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Um pacote do celular ({@code common}) visto pelo Fabric: só embrulha o pacote; o tipo é o que {@link FabricNet}
 * guardou no {@code PacketType} ao registrar.
 */
public record FabricPayload(PhonePayloads.Payload payload) implements CustomPacketPayload {
	@Override
	public Type<FabricPayload> type() {
		return FabricNet.type(payload.type());
	}
}
