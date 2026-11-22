package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.server.PhonePlatform;
import dev.migzu.handyphone.server.PhonePlayer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** O servidor do Minecraft (Fabric) visto pelo serviço do celular. */
public final class FabricPlatform implements PhonePlatform {
	/**
	 * Um embrulho por jogador ligado, trocado quando a instância muda (respawn): pedir o jogador não aloca nada. Sai do
	 * mapa quando o jogador sai.
	 */
	private static final Map<UUID, FabricPlayer> WRAPPERS = new ConcurrentHashMap<>();

	private final MinecraftServer server;

	public FabricPlatform(MinecraftServer server) {
		this.server = server;
	}

	/**
	 * O embrulho de um jogador (o mesmo objeto enquanto a instância de jogo for a mesma). Na thread de rede (pacote
	 * recebido) só reaproveita o que já existe, sem guardar nada: um pacote atrasado depois da saída não pode prender a
	 * entidade de quem saiu no mapa.
	 */
	public static PhonePlayer wrap(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		FabricPlayer wrapper = WRAPPERS.get(player.getUUID());
		return wrapper != null && wrapper.handle == player ? wrapper : new FabricPlayer(player);
	}

	/** Como {@link #wrap}, guardando o embrulho; só na thread do servidor, para um jogador que está na lista. */
	static PhonePlayer cached(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		FabricPlayer wrapper = WRAPPERS.get(player.getUUID());
		if (wrapper == null || wrapper.handle != player) {
			wrapper = new FabricPlayer(player);
			WRAPPERS.put(player.getUUID(), wrapper);
		}
		return wrapper;
	}

	/** O jogador saiu: esquece o embrulho. */
	static void forget(UUID uuid) {
		WRAPPERS.remove(uuid);
	}

	/** O servidor parou: esquece todos. */
	static void forgetAll() {
		WRAPPERS.clear();
	}

	@Override
	public Path worldRoot() {
		return server.getWorldPath(LevelResource.ROOT);
	}

	@Override
	public Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	@Override
	public boolean isModLoaded(String id) {
		return FabricLoader.getInstance().isModLoaded(id);
	}

	@Override
	public void execute(Runnable task) {
		server.execute(task);
	}

	@Override
	public boolean isServerThread() {
		return server.isSameThread();
	}

	@Override
	public PhonePlayer player(UUID uuid) {
		return cached(server.getPlayerList().getPlayer(uuid));
	}

	@Override
	public Collection<? extends PhonePlayer> players() {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		List<PhonePlayer> out = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			out.add(cached(player));
		}
		return out;
	}

	@Override
	public String dimension(String id) {
		Identifier dimension = Identifier.tryParse(id);
		if (dimension == null || server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension)) == null) {
			return null;
		}
		return dimension.toString();
	}
}
