package dev.migzu.handyphone.server;

import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import java.util.UUID;

/**
 * Um jogador ligado, visto pelo serviço do celular. Nunca guarde uma instância: a plataforma pode trocá-la (respawn);
 * guarde o UUID e peça de novo com {@link PhonePlatform#player}. Os nomes seguem os do jogo ({@code getUUID},
 * {@code getX}...) para o código do serviço continuar o mesmo de antes da separação.
 */
public interface PhonePlayer {
	UUID getUUID();

	/** O nome de jogo (só para logs do servidor; nunca vai para outro jogador). */
	String name();

	/** O celular deste jogador entende este pacote (tem o mod e registrou o canal)? */
	boolean canSend(PacketType<?> type);

	/** Envia um pacote (sem conferir {@link #canSend}). */
	void send(PhonePayloads.Payload payload);

	/** Desconecta com um texto traduzível. */
	void disconnect(Msg reason);

	/** O id da dimensão em que está ({@code "minecraft:overworld"}). */
	String dimension();

	double getX();

	double getY();

	double getZ();

	/** As coordenadas do bloco em que está (o {@code blockPosition()} do jogo). */
	int blockX();

	int blockY();

	int blockZ();

	boolean isSpectator();

	/** Tem a permissão de administrador do celular (Fabric: nível 3, como {@code /kick}; Paper: {@code handyphone.admin})? */
	default boolean isAdmin() {
		return false;
	}
}
