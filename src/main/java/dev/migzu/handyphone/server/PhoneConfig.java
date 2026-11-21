package dev.migzu.handyphone.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.net.PhonePayloads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Opções do dono do servidor, em {@code config/handyphone-server.json}. O arquivo é
 * criado com os valores padrão na primeira vez, para o dono ter onde mexer.
 */
public final class PhoneConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * Texto que acompanha o arquivo (JSON não tem comentários). É regravado a cada inicialização com o texto
	 * da versão em uso; o que o dono escrever aqui não é guardado.
	 */
	public String _leia_me = LEIA_ME;

	private static final String LEIA_ME = "allowComputerUpload is a CONVENIENCE, not protection: the server can't tell a camera photo from an image "
		+ "from the computer by its bytes, so a modified client can still upload. What limits the damage is validation, size, quota and "
		+ "rate limits, which always apply. | mapRadiusBlocks: the smallest map tile is 512 blocks, so the effective minimum is about 512 "
		+ "blocks (smaller values still deliver the whole 512x512 region that touches the square). | photosEnabled=false refuses new photos "
		+ "(camera, gallery, sending in a chat); photos already in conversations can still be downloaded by the people in them; to remove "
		+ "content use /handyphone wipe. | The server owner can read everything: messages, photos and places are stored as unencrypted JSON "
		+ "and JPEG files in <world>/handyphone/. | If this file has a JSON error, live location, map, location messages, computer upload, "
		+ "photos and calls are turned OFF until you fix it (see the server log). | Calls: they need the Simple Voice Chat mod (on the server "
		+ "and on every player); without it the Phone app says calls aren't available. Call audio is relayed by the server and is NOT "
		+ "recorded. crossDimensionCalls=false stops people in another dimension from hearing the call. Muting someone (/handyphone mute) "
		+ "also removes their voice from calls. Call audio passes through the server (which, with another plugin, could record it): "
		+ "this mod records nothing. | 'Busy' and 'declined' tell the caller that the number is online (only callers who were not blocked).";

	/**
	 * Aceita fotos enviadas do computador (arrastar ou colar). Desligado, o servidor
	 * recusa tudo o que o cliente declara como "do computador" e o app esconde a opção.
	 * <b>É conveniência, não proteção contra clientes modificados</b>: o servidor não tem como
	 * distinguir, pelos bytes, uma foto da câmera de uma imagem qualquer, então um
	 * cliente alterado pode declarar que a imagem veio da câmera. O que realmente
	 * limita o estrago são o tamanho, a cota, a taxa e a validação, valendo sempre.
	 */
	public boolean allowComputerUpload = true;
	/**
	 * Liga o recurso de fotos inteiro. Desligado, o servidor recusa novos envios (câmera, galeria) e o envio de
	 * uma foto em uma conversa ({@code SendPhoto}). Fotos que já estão em conversas continuam podendo ser baixadas
	 * por quem participa delas (esconder o que já existe é com {@code /handyphone wipe}).
	 */
	public boolean photosEnabled = true;
	/** Cota de armazenamento de fotos por jogador, em megabytes. */
	public int photoQuotaMb = 30;
	/** Teto de fotos por jogador, só para o índice não crescer sem limite (no máximo {@link PhonePayloads#MAX_GALLERY}). */
	public int maxPhotosPerPlayer = PhonePayloads.MAX_GALLERY;
	/** Teto de todas as fotos do servidor somadas, em megabytes: contas demais vezes a cota por jogador não podem encher o disco. */
	public int photoTotalMb = 2048;

	/** Mandar a localização (a atual ou um ponto marcado) em mensagem. Desligado, o servidor recusa e o app esconde as opções. */
	public boolean locationMessagesEnabled = true;
	/** Compartilhar a localização ao vivo com contatos. Desligado, o servidor recusa e o app esconde a opção. */
	public boolean liveLocationEnabled = true;
	/**
	 * Entregar os tiles do squaremap ao app Mapa. O mapa do squaremap mostra tudo o que já foi explorado,
	 * inclusive bases escondidas; o dono que não quer isso desliga aqui (ou limita com {@link #mapRadiusBlocks}).
	 */
	public boolean mapTilesEnabled = true;
	/**
	 * Só entrega tiles que tocam um quadrado de tantos blocos de raio em volta da posição ATUAL do jogador. 0 = sem limite.
	 * <b>O mínimo efetivo é de cerca de 512 blocos:</b> o menor tile tem 512x512 blocos, então qualquer raio entrega
	 * ao menos a região inteira que toca o quadrado (e até 4 regiões perto de uma borda). Um valor como 64 NÃO
	 * mostra "só 64 blocos".
	 */
	public int mapRadiusBlocks = 0;
	/** Locais salvos por jogador. */
	public int maxSavedPlaces = 32;

	/**
	 * Liga as ligações de voz (precisam do Simple Voice Chat no servidor). Desligado, o servidor recusa ligar e o app
	 * Telefone avisa que não estão disponíveis; chamadas em andamento terminam.
	 */
	public boolean callsEnabled = true;
	/** A voz da ligação atravessa dimensões (quem está no Nether fala com quem está no Overworld). Desligado, só se ouvem na mesma dimensão. */
	public boolean crossDimensionCalls = true;
	/** Pessoas ao mesmo tempo em uma chamada em grupo (etapa 5b); ligações entre duas pessoas não são afetadas. */
	public int maxCallParticipants = 8;
	/** Segundos que a ligação toca antes de virar "não atendeu". */
	public int ringSeconds = 30;

	/**
	 * Verdadeiro se o arquivo existe mas não pôde ser lido (JSON inválido): as opções sensíveis estão
	 * desligadas à força e o arquivo do dono não foi tocado. Não vai para o arquivo.
	 */
	private transient boolean failedClosed;

	public boolean failedClosed() {
		return failedClosed;
	}

	public long quotaBytes() {
		return Math.max(1, photoQuotaMb) * 1024L * 1024L;
	}

	public long totalBytes() {
		return Math.max(1, photoTotalMb) * 1024L * 1024L;
	}

	/**
	 * Lê as opções. <b>Falha fechada:</b> com o arquivo ilegível (uma vírgula a mais, um valor de tipo errado), as
	 * opções que expõem dados (localização ao vivo, mapa, localização em mensagem, envio do computador e fotos) ficam
	 * DESLIGADAS, o log recebe um {@code error} e o arquivo não é sobrescrito, para o dono corrigir o que errou.
	 * Antes, um arquivo ruim fazia tudo voltar ao padrão (tudo ligado) com só um aviso.
	 */
	public static PhoneConfig load() {
		LegacyMigration.migrateConfig(FabricLoader.getInstance().getConfigDir());
		Path file = FabricLoader.getInstance().getConfigDir().resolve("handyphone-server.json");
		PhoneConfig config = new PhoneConfig();
		try {
			if (Files.exists(file)) {
				PhoneConfig read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PhoneConfig.class);
				if (read != null) {
					config = read;
				}
			}
		} catch (IOException | RuntimeException e) {
			HandyPhone.LOGGER.error(
				"HandyPhone: {} couldn't be read ({}). For safety these were turned OFF: liveLocationEnabled, mapTilesEnabled, "
					+ "locationMessagesEnabled, allowComputerUpload, photosEnabled and callsEnabled. The file was not changed; fix the error and restart the server.",
				file, e.toString()
			);
			return failClosed();
		}
		try {
			// Regrava sempre: opções novas de versões futuras aparecem no arquivo com o padrão.
			config.normalize();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			HandyPhone.LOGGER.warn("Couldn't write {}; the options that were read stay in effect", file, e);
		}
		return config;
	}

	/** As opções com os recursos sensíveis desligados (arquivo de configuração ilegível). */
	static PhoneConfig failClosed() {
		PhoneConfig config = new PhoneConfig();
		config.liveLocationEnabled = false;
		config.mapTilesEnabled = false;
		config.locationMessagesEnabled = false;
		config.allowComputerUpload = false;
		// O dono pode ter desligado as fotos por causa de menores: um erro de vírgula não pode ligá-las de volta.
		config.photosEnabled = false;
		// Ligações também: o dono pode tê-las desligado, e um erro de vírgula não pode ligá-las de volta.
		config.callsEnabled = false;
		config.failedClosed = true;
		return config;
	}

	/** Corrige valores fora da faixa (um número absurdo no arquivo não pode virar um limite absurdo no servidor). */
	void normalize() {
		_leia_me = LEIA_ME;
		photoQuotaMb = Math.max(1, Math.min(photoQuotaMb, 4096));
		maxPhotosPerPlayer = Math.max(1, Math.min(maxPhotosPerPlayer, PhonePayloads.MAX_GALLERY));
		photoTotalMb = Math.max(photoQuotaMb, Math.min(photoTotalMb, 1_000_000));
		mapRadiusBlocks = Math.max(0, Math.min(mapRadiusBlocks, 30_000_000));
		maxSavedPlaces = Math.max(1, Math.min(maxSavedPlaces, 100));
		maxCallParticipants = Math.max(2, Math.min(maxCallParticipants, 32));
		ringSeconds = Math.max(5, Math.min(ringSeconds, 120));
	}
}
