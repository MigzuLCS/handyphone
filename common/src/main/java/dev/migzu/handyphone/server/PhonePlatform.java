package dev.migzu.handyphone.server;

import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;

/**
 * O que o serviço do celular precisa do servidor em que roda (Fabric hoje; Paper ou NeoForge depois). Pequeno de
 * propósito: pastas, a thread do servidor, os jogadores ligados e se uma dimensão existe. Os eventos (início, tick,
 * salvamento, entrada e saída de jogador) a plataforma entrega chamando os métodos {@code on*} de {@link PhoneService}.
 *
 * <p>Tudo aqui é chamado na thread do servidor, menos {@link #execute}, que serve justamente para voltar a ela.
 */
public interface PhonePlatform {
	/** A pasta do mundo (onde ficam {@code <mundo>/handyphone/} e a pasta antiga {@code blockphone/}). */
	Path worldRoot();

	/** A pasta de configuração ({@code config/}, onde fica {@code handyphone-server.json}). */
	Path configDir();

	/** Um mod (ou plugin) opcional está instalado? Usado para o squaremap ({@code "squaremap"}). */
	boolean isModLoaded(String id);

	/**
	 * Põe uma tarefa na fila da thread do servidor. Chamado da própria thread do servidor, roda na hora (como o
	 * {@code MinecraftServer.execute}).
	 */
	void execute(Runnable task);

	/** Estamos na thread do servidor? */
	boolean isServerThread();

	/**
	 * O jogador ligado com este UUID, ou null. A instância de jogo muda no respawn; a plataforma devolve sempre a atual
	 * (e não deve alocar a cada chamada: o tick do celular chama isto para cada pessoa).
	 */
	PhonePlayer player(UUID uuid);

	/** Os jogadores ligados agora. */
	Collection<? extends PhonePlayer> players();

	/**
	 * O id normalizado de uma dimensão que existe ({@code "overworld"} vira {@code "minecraft:overworld"}, como o
	 * {@code Identifier} do jogo), ou null se o texto não é um id válido ou a dimensão não existe.
	 */
	String dimension(String id);
}
