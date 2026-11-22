package dev.migzu.handyphone.paper;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.PhoneService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.event.world.WorldSaveEvent;

/**
 * Os eventos do Paper que viram as chamadas {@code PhoneService.on*}.
 *
 * <p><b>O momento da entrada.</b> No Fabric o {@code JOIN} já sabe os canais do cliente. No Paper o servidor anuncia os dele no
 * {@code placeNewPlayer} e o cliente Fabric só responde com o {@code minecraft:register} dele depois, então no
 * {@code PlayerJoinEvent} o celular ainda não é conhecido. Esperamos o {@code PlayerRegisterChannelEvent} do canal {@code sync_v3} e
 * rodamos o {@code onJoin} no tick seguinte: o evento sai canal a canal no meio do pacote de registro, e chamar na hora deixaria os
 * canais seguintes (bloqueados, galeria, histórico de ligações) de fora do {@code canSend}. Uma vez por conexão.
 */
final class PaperEvents implements Listener {
	private static final String SYNC_CHANNEL = PhonePayloads.Sync.TYPE.id();

	private final HandyPhonePlugin plugin;
	/** Quem já passou pelo {@code onJoin} nesta conexão (sai no {@code PlayerQuitEvent}). */
	private final Set<UUID> joined = new HashSet<>();
	/** Quem já tem um {@code onJoin} marcado para o próximo tick, com a conexão (a instância do jogador) para a qual foi marcado. */
	private final Map<UUID, Player> pending = new HashMap<>();

	PaperEvents(HandyPhonePlugin plugin) {
		this.plugin = plugin;
	}

	@EventHandler
	public void onServerLoad(ServerLoadEvent event) {
		// STARTUP (e RELOAD, que recarrega o plugin inteiro): o serviço sobe uma vez, com todos os mundos carregados.
		plugin.startService();
	}

	@EventHandler
	public void onTick(ServerTickEndEvent event) {
		PhoneService.onServerTick();
	}

	@EventHandler
	public void onWorldSave(WorldSaveEvent event) {
		// Dispara uma vez por mundo, no autosave e no /save-all; só o mundo principal importa (os dados ficam na raiz do save).
		if (event.getWorld().equals(Bukkit.getWorlds().getFirst())) {
			PhoneService.onAfterSave(false);
		}
	}

	@EventHandler
	public void onPlayerJoin(PlayerJoinEvent event) {
		joinIfReady(event.getPlayer());
	}

	/** Quem já está ligado quando o serviço sobe (plugin recarregado em funcionamento). */
	void joinOnlinePlayers() {
		for (Player player : Bukkit.getOnlinePlayers()) {
			joinIfReady(player);
		}
	}

	/** Se o cliente já anunciou o canal do celular (reconexão que não manda novo registro), segue direto; senão espera o registro. */
	private void joinIfReady(Player player) {
		if (player.getListeningPluginChannels().contains(SYNC_CHANNEL)) {
			scheduleJoin(player);
		}
	}

	@EventHandler
	public void onChannelRegistered(PlayerRegisterChannelEvent event) {
		PaperPlayer wrapper = plugin.platform().existing(event.getPlayer().getUniqueId());
		if (wrapper != null) {
			wrapper.channelRegistered(event.getChannel());
		}
		if (SYNC_CHANNEL.equals(event.getChannel())) {
			scheduleJoin(event.getPlayer());
		}
	}

	@EventHandler
	public void onChannelUnregistered(PlayerUnregisterChannelEvent event) {
		PaperPlayer wrapper = plugin.platform().existing(event.getPlayer().getUniqueId());
		if (wrapper != null) {
			wrapper.channelUnregistered(event.getChannel());
		}
	}

	private void scheduleJoin(Player player) {
		UUID uuid = player.getUniqueId();
		if (joined.contains(uuid) || pending.get(uuid) == player) {
			return;
		}
		pending.put(uuid, player);
		Bukkit.getScheduler().runTask(plugin, () -> {
			// Saiu e voltou logo (mesmo UUID, outra conexão) antes deste tick: o marcador da conexão nova é o que vale.
			if (pending.get(uuid) != player) {
				return;
			}
			pending.remove(uuid);
			Player now = Bukkit.getPlayer(uuid);
			if (now != player || !now.isOnline() || !joined.add(uuid)) {
				return;
			}
			try {
				PhoneService.onJoin(plugin.platform().cached(now));
			} catch (RuntimeException e) {
				HandyPhoneCore.LOGGER.error("HandyPhone: error setting up the phone of {}", now.getName(), e);
			}
		});
	}

	@EventHandler
	public void onPlayerQuit(PlayerQuitEvent event) {
		UUID uuid = event.getPlayer().getUniqueId();
		joined.remove(uuid);
		pending.remove(uuid);
		PhoneService.onDisconnect(uuid);
		plugin.platform().forget(uuid);
	}

	@EventHandler
	public void onRespawn(PlayerPostRespawnEvent event) {
		PhoneService.onWorldChange(event.getPlayer().getUniqueId());
	}

	@EventHandler
	public void onWorldChange(PlayerChangedWorldEvent event) {
		PhoneService.onWorldChange(event.getPlayer().getUniqueId());
	}

	/** O plugin foi desligado: esquece o estado das conexões. */
	void clear() {
		joined.clear();
		pending.clear();
	}
}
