package dev.migzu.handyphone.paper;

import dev.migzu.handyphone.net.ByteArrayBuf;
import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.Msg;
import dev.migzu.handyphone.server.PhonePlayer;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Um {@link Player} do Paper visto pelo serviço do celular. Guarda os canais que o cliente anunciou (o
 * {@code getListeningPluginChannels()} do Bukkit copia um conjunto a cada chamada, e o tick chama {@link #canSend} muito).
 */
final class PaperPlayer implements PhonePlayer {
	final Player handle;
	private final Plugin plugin;
	/** Os canais que o cliente registrou (só a thread do servidor mexe). */
	private final Set<String> channels;

	PaperPlayer(Plugin plugin, Player handle) {
		this.plugin = plugin;
		this.handle = handle;
		this.channels = new HashSet<>(handle.getListeningPluginChannels());
	}

	void channelRegistered(String channel) {
		channels.add(channel);
	}

	void channelUnregistered(String channel) {
		channels.remove(channel);
	}

	@Override
	public UUID getUUID() {
		return handle.getUniqueId();
	}

	@Override
	public String name() {
		return handle.getName();
	}

	@Override
	public boolean canSend(PacketType<?> type) {
		return channels.contains(type.id());
	}

	@Override
	public void send(PhonePayloads.Payload payload) {
		ByteArrayBuf buf = new ByteArrayBuf();
		payload.write(buf);
		handle.sendPluginMessage(plugin, payload.type().id(), buf.toByteArray());
	}

	@Override
	public void disconnect(Msg reason) {
		handle.kick(PaperText.component(reason));
	}

	@Override
	public String dimension() {
		return handle.getWorld().getKey().toString();
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
		return (int) Math.floor(handle.getX());
	}

	@Override
	public int blockY() {
		return (int) Math.floor(handle.getY());
	}

	@Override
	public int blockZ() {
		return (int) Math.floor(handle.getZ());
	}

	@Override
	public boolean isSpectator() {
		return handle.getGameMode() == GameMode.SPECTATOR;
	}
}
