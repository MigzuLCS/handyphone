package dev.migzu.handyphone.paper;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.migzu.handyphone.server.VoiceLink;
import org.bukkit.plugin.java.JavaPlugin;

/** A ÚNICA ponte com o Simple Voice Chat: só é carregada quando o plugin {@code voicechat} está ligado. */
final class VoiceHook {
	private VoiceHook() {
	}

	static void register(JavaPlugin plugin) {
		BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
		if (service == null) {
			plugin.getLogger().warning("HandyPhone: Simple Voice Chat has no API service; voice calls are off");
			return;
		}
		service.registerPlugin(new VoiceLink());
	}
}
