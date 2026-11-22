package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.PhoneService;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * Liga o serviço do celular ({@code common}) aos eventos do servidor Fabric e aos pacotes. Antes da separação isto morava
 * em {@code PhoneService.register}; a ordem e os eventos são os mesmos.
 */
public final class FabricServer {
	private FabricServer() {
	}

	/** Chamado uma vez na inicialização do mod. */
	public static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> PhoneService.onServerStarted(new FabricPlatform(server)));
		ServerTickEvents.END_SERVER_TICK.register(server -> PhoneService.onServerTick());
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> PhoneService.onAfterSave(flush));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			PhoneService.onServerStopping();
			FabricPlatform.forgetAll();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> PhoneService.onJoin(FabricPlatform.cached(handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PhoneService.onDisconnect(handler.player.getUUID());
			FabricPlatform.forget(handler.player.getUUID());
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> PhoneService.onWorldChange(newPlayer.getUUID()));
		ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> PhoneService.onWorldChange(player.getUUID()));
		FabricNet.registerServerReceivers();
	}

	/**
	 * Para os testes de ataque: o mesmo caminho de um pacote vindo da rede, já na thread do servidor (antes era
	 * {@code PhoneService.handlePacket(ServerPlayer, ...)}).
	 */
	public static void handlePacket(ServerPlayer player, PhonePayloads.Payload packet) {
		PhoneService.handlePacket(FabricPlatform.wrap(player), packet);
	}

}
