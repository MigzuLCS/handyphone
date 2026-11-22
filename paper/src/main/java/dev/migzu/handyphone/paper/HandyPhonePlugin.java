package dev.migzu.handyphone.paper;

import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.server.PhoneService;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * O HandyPhone como plugin Paper: só o lado do servidor. Os jogadores usam o mod Fabric no cliente (mesmo protocolo, mesmos bytes).
 * Toda a lógica está no {@code common}; aqui só a cola com o Paper (ver {@code RELATORIO-paper.md}).
 *
 * <p>Esta classe não cita o {@code VoiceLink} nem o {@code SquaremapLink}: só {@link VoiceHook} toca no Simple Voice Chat, e só depois
 * de conferir que ele está ligado.
 */
public final class HandyPhonePlugin extends JavaPlugin {
	private PaperPlatform platform;
	private PaperNet net;
	private PaperEvents events;
	private boolean serviceStarted;

	@Override
	public void onEnable() {
		platform = new PaperPlatform(this);
		net = new PaperNet(this);
		events = new PaperEvents(this);
		net.register();
		getServer().getPluginManager().registerEvents(events, this);
		PaperCommands.register(this);
		if (getServer().getPluginManager().isPluginEnabled("voicechat")) {
			try {
				VoiceHook.register(this);
			} catch (LinkageError | RuntimeException e) {
				getLogger().warning("HandyPhone: Simple Voice Chat is installed but its API couldn't be used; voice calls are off: " + e);
			}
		}
		// Normalmente o ServerLoadEvent já sobe o serviço; se o plugin foi ligado com o servidor no ar (PlugMan), o evento não vem de novo: sobe no próximo tick.
		getServer().getScheduler().runTask(this, this::startService);
		HandyPhoneCore.LOGGER.info("HandyPhone (Paper) enabled");
	}

	/** Chamado pelo {@code ServerLoadEvent}: todos os mundos já existem (o serviço lê {@code <mundo>/handyphone/}). */
	void startService() {
		if (serviceStarted) {
			return;
		}
		serviceStarted = true;
		getDataFolder().mkdirs();
		PhoneService.onServerStarted(platform);
		// Quem já estava ligado (reinício do plugin em funcionamento) e já anunciou os canais.
		events.joinOnlinePlayers();
	}

	@Override
	public void onDisable() {
		// O Paper desliga os plugins ANTES de tirar os jogadores: o PlayerQuitEvent dos últimos não chega. Grava tudo de forma síncrona aqui.
		if (serviceStarted) {
			PhoneService.onServerStopping();
		}
		serviceStarted = false;
		if (net != null) {
			net.unregister();
		}
		if (events != null) {
			events.clear();
		}
		if (platform != null) {
			platform.forgetAll();
		}
	}

	PaperPlatform platform() {
		return platform;
	}
}
