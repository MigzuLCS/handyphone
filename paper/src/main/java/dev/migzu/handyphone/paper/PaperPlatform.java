package dev.migzu.handyphone.paper;

import dev.migzu.handyphone.server.PhonePlatform;
import dev.migzu.handyphone.server.PhonePlayer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** O servidor Paper visto pelo serviço do celular. */
final class PaperPlatform implements PhonePlatform {
	private final JavaPlugin plugin;
	/** Um embrulho por jogador ligado (trocado se a instância muda); pedir o jogador não aloca nada. */
	private final Map<UUID, PaperPlayer> wrappers = new ConcurrentHashMap<>();

	PaperPlatform(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/**
	 * O embrulho de um jogador, sem guardar nada (serve a qualquer thread; um pacote atrasado depois da saída não prende o jogador
	 * no mapa). {@link #cached} é o da thread do servidor, para quem está na lista de jogadores.
	 */
	PaperPlayer wrap(Player player) {
		if (player == null) {
			return null;
		}
		PaperPlayer wrapper = wrappers.get(player.getUniqueId());
		return wrapper != null && wrapper.handle == player ? wrapper : new PaperPlayer(plugin, player);
	}

	PaperPlayer cached(Player player) {
		if (player == null) {
			return null;
		}
		PaperPlayer wrapper = wrappers.get(player.getUniqueId());
		if (wrapper == null || wrapper.handle != player) {
			wrapper = new PaperPlayer(plugin, player);
			wrappers.put(player.getUniqueId(), wrapper);
		}
		return wrapper;
	}

	/** O embrulho já existente (sem criar), para os eventos de canal. */
	PaperPlayer existing(UUID uuid) {
		return wrappers.get(uuid);
	}

	void forget(UUID uuid) {
		wrappers.remove(uuid);
	}

	void forgetAll() {
		wrappers.clear();
	}

	@Override
	public Path worldRoot() {
		// A raiz do save (./world), o mesmo que o LevelResource.ROOT do Fabric; getWorldContainer() é obsoleto e getWorldPath() é da dimensão.
		return Bukkit.getServer().getLevelDirectory();
	}

	@Override
	public Path configDir() {
		return plugin.getDataFolder().toPath();
	}

	@Override
	public boolean isModLoaded(String id) {
		return Bukkit.getPluginManager().isPluginEnabled(id);
	}

	@Override
	public void execute(Runnable task) {
		if (Bukkit.isPrimaryThread()) {
			task.run();
		} else if (plugin.isEnabled()) {
			Bukkit.getScheduler().runTask(plugin, task);
		}
	}

	@Override
	public boolean isServerThread() {
		return Bukkit.isPrimaryThread();
	}

	@Override
	public PhonePlayer player(UUID uuid) {
		return cached(Bukkit.getPlayer(uuid));
	}

	@Override
	public Collection<? extends PhonePlayer> players() {
		Collection<? extends Player> online = Bukkit.getOnlinePlayers();
		List<PhonePlayer> out = new ArrayList<>(online.size());
		for (Player player : online) {
			out.add(cached(player));
		}
		return out;
	}

	@Override
	public String dimension(String id) {
		NamespacedKey key = id == null ? null : NamespacedKey.fromString(id);
		World world = key == null ? null : Bukkit.getWorld(key);
		return world == null ? null : world.getKey().toString();
	}
}
