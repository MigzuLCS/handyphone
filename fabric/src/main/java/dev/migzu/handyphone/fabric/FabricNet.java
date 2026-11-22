package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.PhoneService;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Registra os pacotes do celular no Fabric: o mesmo id de canal e o codec do {@code common} sobre o buffer do jogo. */
public final class FabricNet {
	private FabricNet() {
	}

	/** O tipo Fabric de um pacote (guardado no próprio {@link PacketType} no registro). */
	@SuppressWarnings("unchecked")
	public static CustomPacketPayload.Type<FabricPayload> type(PacketType<?> type) {
		return (CustomPacketPayload.Type<FabricPayload>) type.platformHandle();
	}

	/** Registra todos os pacotes; chamado nos dois lados na inicialização do mod (antes era {@code PhonePayloads.register}). */
	public static void register() {
		PayloadTypeRegistry<RegistryFriendlyByteBuf> toClient = PayloadTypeRegistry.clientboundPlay();
		for (PacketType<?> type : PhonePayloads.TO_CLIENT) {
			toClient.register(bind(type), codec(type));
		}
		PayloadTypeRegistry<RegistryFriendlyByteBuf> toServer = PayloadTypeRegistry.serverboundPlay();
		for (PacketType<?> type : PhonePayloads.TO_SERVER) {
			toServer.register(bind(type), codec(type));
		}
	}

	/** Os receptores do servidor: todo pacote do celular vai para {@link PhoneService#receive}. */
	public static void registerServerReceivers() {
		for (PacketType<?> type : PhonePayloads.TO_SERVER) {
			ServerPlayNetworking.registerGlobalReceiver(type(type), (packet, ctx) -> PhoneService.receive(FabricPlatform.wrap(ctx.player()), packet.payload()));
		}
	}

	private static CustomPacketPayload.Type<FabricPayload> bind(PacketType<?> type) {
		CustomPacketPayload.Type<FabricPayload> fabric = new CustomPacketPayload.Type<>(HandyPhone.id(type.path()));
		type.setPlatformHandle(fabric);
		return fabric;
	}

	private static StreamCodec<RegistryFriendlyByteBuf, FabricPayload> codec(PacketType<?> type) {
		return CustomPacketPayload.codec(
			(FabricPayload packet, RegistryFriendlyByteBuf buf) -> packet.payload().write(new NettyPhoneBuf(buf)),
			buf -> new FabricPayload(type.read(new NettyPhoneBuf(buf)))
		);
	}
}
