package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.Msg;
import dev.migzu.handyphone.server.PhonePlayer;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Um {@link ServerPlayer} visto pelo serviço do celular. Criado uma vez por instância de jogador (ver {@link FabricPlatform#wrap}). */
final class FabricPlayer implements PhonePlayer {
	final ServerPlayer handle;

	FabricPlayer(ServerPlayer handle) {
		this.handle = handle;
	}

	@Override
	public UUID getUUID() {
		return handle.getUUID();
	}

	@Override
	public String name() {
		return handle.getGameProfile().name();
	}

	@Override
	public boolean canSend(PacketType<?> type) {
		return ServerPlayNetworking.canSend(handle, FabricNet.type(type));
	}

	@Override
	public void send(PhonePayloads.Payload payload) {
		ServerPlayNetworking.send(handle, new FabricPayload(payload));
	}

	@Override
	public void disconnect(Msg reason) {
		handle.connection.disconnect(FabricText.component(reason));
	}

	@Override
	public String dimension() {
		return handle.level().dimension().identifier().toString();
	}

	@Override
	public double getX() {
		return handle.getX();
	}

	@Override
	public double getY() {
		return handle.getY();
	}

	@Override
	public double getZ() {
		return handle.getZ();
	}

	@Override
	public int blockX() {
		return handle.blockPosition().getX();
	}

	@Override
	public int blockY() {
		return handle.blockPosition().getY();
	}

	@Override
	public int blockZ() {
		return handle.blockPosition().getZ();
	}

	@Override
	public boolean isSpectator() {
		return handle.isSpectator();
	}
}
