package dev.migzu.handyphone.paper;

import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.net.ByteArrayBuf;
import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.PhoneService;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Os pacotes do celular no plugin messaging do Paper: o mesmo id de canal e os mesmos bytes que o Fabric manda (o
 * {@code CustomPacketPayload} do Fabric escreve o id e depois só os campos do pacote; no Paper isso chega como os bytes da mensagem).
 */
final class PaperNet implements PluginMessageListener {
	private final HandyPhonePlugin plugin;
	private final Map<String, PacketType<?>> incoming = new HashMap<>();

	PaperNet(HandyPhonePlugin plugin) {
		this.plugin = plugin;
	}

	void register() {
		Messenger messenger = Bukkit.getMessenger();
		for (PacketType<?> type : PhonePayloads.TO_CLIENT) {
			messenger.registerOutgoingPluginChannel(plugin, type.id());
		}
		for (PacketType<?> type : PhonePayloads.TO_SERVER) {
			incoming.put(type.id(), type);
			messenger.registerIncomingPluginChannel(plugin, type.id(), this);
		}
	}

	void unregister() {
		Messenger messenger = Bukkit.getMessenger();
		messenger.unregisterOutgoingPluginChannel(plugin);
		messenger.unregisterIncomingPluginChannel(plugin);
	}

	/**
	 * Chega na thread do servidor. O jogo (Fabric) recusa um pacote com bytes sobrando no fim ou ilegível desconectando o jogador; o Paper
	 * não confere nada, então a conferência é daqui. Exceção no receptor só viraria um aviso no log e o servidor seguiria.
	 */
	@Override
	public void onPluginMessageReceived(String channel, Player player, byte[] message) {
		PacketType<?> type = incoming.get(channel);
		if (type == null) {
			return;
		}
		PhonePayloads.Payload payload;
		try {
			ByteArrayBuf buf = new ByteArrayBuf(message);
			payload = type.read(buf);
			if (buf.readableBytes() != 0) {
				throw new IllegalStateException("Packet was larger than I expected, found " + buf.readableBytes() + " bytes extra");
			}
		} catch (RuntimeException e) {
			HandyPhoneCore.LOGGER.warn("HandyPhone: {} sent an invalid {} packet ({}); disconnecting", player.getName(), channel, e.getMessage());
			player.kick(Component.translatable("multiplayer.disconnect.invalid_packet", "Invalid packet"));
			return;
		}
		if (!player.isOnline()) {
			return;
		}
		try {
			PhoneService.receive(plugin.platform().cached(player), payload);
		} catch (RuntimeException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: error handling {} from {}", channel, player.getName(), e);
		}
	}
}
