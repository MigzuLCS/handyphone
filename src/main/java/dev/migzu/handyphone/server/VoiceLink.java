package dev.migzu.handyphone.server;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import de.maxhenkel.voicechat.api.packets.MicrophonePacket;
import de.maxhenkel.voicechat.api.packets.StaticSoundPacket;
import dev.migzu.handyphone.HandyPhone;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * A ÚNICA classe que toca a API do Simple Voice Chat. Ela só é instanciada pelo próprio SVC (entrypoint {@code voicechat}
 * do {@code fabric.mod.json}): sem o SVC instalado, a JVM nunca resolve as classes {@code de.maxhenkel...} e o HandyPhone
 * carrega normal, com as ligações indisponíveis. Nenhuma outra classe do mod pode importar nada de {@code de.maxhenkel}.
 *
 * <p><b>Como a voz passa.</b> O SVC chama {@link #onMicrophone} numa thread de rede dele, uma vez por pacote de voz de cada
 * jogador. Aqui só se consulta o retrato imutável que o {@link CallService} publicou em {@link VoiceStatus} (nada de
 * contas, jogadores ou estado de ligação), e cada ouvinte autorizado recebe o mesmo áudio como som <b>estático</b> (chega
 * de qualquer distância e dimensão: é assim que a ligação atravessa dimensões). O evento <b>não é cancelado</b>: a voz de
 * proximidade continua como sempre, e {@link VoiceRouting} evita o eco entre quem já se ouve pela proximidade.
 */
public final class VoiceLink implements VoicechatPlugin {
	/** Categoria de volume própria: o jogador tem uma barra "Ligações" no SVC. */
	static final String CATEGORY = "handyphone_calls";
	/** Quantos blocos de alcance o sussurro tem se a configuração do SVC não disser (o padrão dele é metade). */
	private static final String WHISPER_KEY = "whisper_distance";

	private final VoiceRouting.Echo echo = new VoiceRouting.Echo();
	private volatile boolean failureLogged;
	/** Alcance do sussurro, lido uma vez da configuração do SVC (NaN = não leu: vale metade do alcance da voz). */
	private volatile double whisperDistance = Double.NaN;

	@Override
	public String getPluginId() {
		return HandyPhone.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		HandyPhone.LOGGER.info("HandyPhone: Simple Voice Chat found; voice calls available");
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, event -> VoiceStatus.serverStopped());
		registration.registerEvent(PlayerConnectedEvent.class, event -> setVoice(event.getConnection(), true));
		registration.registerEvent(PlayerDisconnectedEvent.class, event -> VoiceStatus.setVoice(event.getPlayerUuid(), false));
		registration.registerEvent(PlayerStateChangedEvent.class, event -> VoiceStatus.setVoice(event.getPlayerUuid(), !event.isDisabled() && !event.isDisconnected()));
		registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
	}

	private void onStarted(VoicechatServerStartedEvent event) {
		VoicechatServerApi api = event.getVoicechat();
		try {
			whisperDistance = api.getServerConfig().getDouble(WHISPER_KEY, api.getVoiceChatDistance() / 2);
		} catch (RuntimeException e) {
			whisperDistance = Double.NaN;
		}
		try {
			api.registerVolumeCategory(api.volumeCategoryBuilder()
				.setId(CATEGORY)
				.setName("Phone calls")
				.setDescription("The volume of the voice of people calling you on the phone")
				.setIcon(phoneIcon())
				.build());
		} catch (RuntimeException e) {
			HandyPhone.LOGGER.warn("HandyPhone: couldn't register the calls volume category", e);
		}
		VoiceStatus.serverStarted();
	}

	private static void setVoice(VoicechatConnection connection, boolean on) {
		if (connection != null && connection.getPlayer() != null) {
			VoiceStatus.setVoice(connection.getPlayer().getUuid(), on && connection.isConnected() && !connection.isDisabled());
		}
	}

	/** Um fone de telefone 16x16 (ARGB), desenhado por pontos: o SVC pede a imagem em um vetor de linhas. */
	private static int[][] phoneIcon() {
		int[][] icon = new int[16][16];
		int white = 0xFFFFFFFF;
		// Uma "meia-lua" diagonal: o corpo do fone.
		int[][] pixels = {
			{2, 2}, {3, 2}, {2, 3}, {3, 3}, {3, 4}, {4, 4}, {4, 5}, {4, 6}, {5, 6}, {5, 7}, {6, 8}, {7, 9}, {8, 10}, {9, 10}, {10, 11},
			{11, 11}, {11, 12}, {12, 12}, {12, 13}, {13, 13}, {12, 11}, {13, 12}, {13, 11}, {13, 10}
		};
		for (int[] p : pixels) {
			icon[p[1]][p[0]] = white;
		}
		return icon;
	}

	private void onMicrophone(MicrophonePacketEvent event) {
		try {
			VoicechatConnection senderConnection = event.getSenderConnection();
			if (senderConnection == null || senderConnection.getPlayer() == null) {
				return;
			}
			UUID speaker = senderConnection.getPlayer().getUuid();
			VoiceRouting.Snapshot snapshot = VoiceStatus.snapshot();
			// A saída mais comum (e mais barata): quase todo pacote de voz do servidor é de quem não está em ligação.
			if (!snapshot.listeners().containsKey(speaker)) {
				return;
			}
			VoicechatServerApi api = event.getVoicechat();
			MicrophonePacket packet = event.getPacket();
			double voiceDistance = api.getVoiceChatDistance();
			double whisper = Double.isNaN(whisperDistance) ? voiceDistance / 2 : whisperDistance;
			List<UUID> targets = VoiceRouting.recipients(
				snapshot, speaker, packet.isWhispering(), voiceDistance, whisper, echo, (from, to) -> proximity(api, senderConnection, to)
			);
			if (targets.isEmpty()) {
				return;
			}
			// Um canal só nosso por falante: reaproveitar o canal da voz de proximidade faria o cliente misturar os dois fluxos.
			UUID channel = UUID.nameUUIDFromBytes(("handyphone-call:" + speaker).getBytes(StandardCharsets.UTF_8));
			StaticSoundPacket.Builder<?> builder = packet.staticSoundPacketBuilder();
			builder.channelId(channel);
			builder.category(CATEGORY);
			StaticSoundPacket out = builder.build();
			for (UUID target : targets) {
				VoicechatConnection connection = api.getConnectionOf(target);
				if (connection != null && connection.isConnected() && !connection.isDisabled()) {
					api.sendStaticSoundPacketTo(connection, out);
				}
			}
		} catch (Throwable t) {
			// Nada pode escapar para a thread de voz do SVC; o log sai uma vez só.
			if (!failureLogged) {
				failureLogged = true;
				HandyPhone.LOGGER.error("HandyPhone: error while relaying call voice (further identical errors won't be logged)", t);
			}
		}
	}

	/**
	 * Como o SVC já conecta o falante e o ouvinte. Mesmo grupo = já se ouvem; falante em grupo que não é aberto, ou ouvinte
	 * em grupo isolado = a voz de proximidade não chega, então repassar é sempre necessário.
	 */
	private static VoiceRouting.Proximity proximity(VoicechatServerApi api, VoicechatConnection speaker, UUID listener) {
		VoicechatConnection to = api.getConnectionOf(listener);
		Group sg = speaker.getGroup(), lg = to == null ? null : to.getGroup();
		if (sg != null && lg != null && sg.getId().equals(lg.getId())) {
			return VoiceRouting.Proximity.NEVER;
		}
		if ((sg != null && sg.getType() != Group.Type.OPEN) || (lg != null && lg.getType() == Group.Type.ISOLATED)) {
			return VoiceRouting.Proximity.ALWAYS;
		}
		return VoiceRouting.Proximity.NORMAL;
	}
}
