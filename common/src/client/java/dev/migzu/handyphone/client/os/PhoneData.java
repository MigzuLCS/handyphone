package dev.migzu.handyphone.client.os;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O que o celular sabe, espelhando o servidor: o próprio número, os contatos e as
 * conversas. O cliente nunca inventa esses dados; só guarda o que o servidor mandou.
 */
public final class PhoneData {
	/** Número do jogador, só dígitos; vazio enquanto o servidor não respondeu. */
	public String number = "";
	/** Contatos: número -> nome escolhido pelo jogador. */
	public final Map<String, String> contacts = new LinkedHashMap<>();
	/** Conversas, da mais recente para a mais antiga. */
	public final List<Conversation> conversations = new ArrayList<>();
	/** Números que o jogador bloqueou (o servidor esconde tudo o que vem deles; quem foi bloqueado não sabe). */
	public final List<String> blocked = new ArrayList<>();
	/** Fotos da galeria, da mais recente para a mais antiga. */
	public final List<GalleryPhoto> gallery = new ArrayList<>();
	/** Quanto da cota de fotos o jogador usa, e a cota total (em bytes); a cota 0 = o servidor ainda não disse. */
	public long photoUsedBytes, photoQuotaBytes;
	/** O servidor aceita fotos enviadas do computador (arrastar/colar)? */
	public boolean importAllowed = true;
	/** O que já foi baixado de fotos (com limite de memória). */
	public final PhotoCache photos = new PhotoCache();

	// ---- localização
	/** O que o servidor oferece de mapa e localização (vazio até ele responder). */
	public MapInfo map = new MapInfo();
	/** Locais salvos pelo jogador. */
	public final List<Place> places = new ArrayList<>();
	/** Quem compartilha a localização com o jogador agora. */
	public final List<Live> live = new ArrayList<>();
	/** O compartilhamento do próprio jogador. */
	public Share share = new Share();
	/** Onde o jogador está (vem do próprio jogo, só para desenhar). */
	public final SelfPos self = new SelfPos();
	/** Tiles do mapa já baixados. */
	final TileCache tiles = new TileCache();
	/** Rota ativa ("Ir até aqui"), ou null. */
	public Route route;

	// ---- ligações
	/** O servidor já disse se existem ligações aqui? Sem isso (servidor sem o mod) o app Telefone avisa "sem sinal". */
	public boolean callInfoReceived;
	/** Ligações existem neste servidor (ligadas na configuração e com o Simple Voice Chat no ar). */
	public boolean callsAvailable;
	/** A voz atravessa dimensões neste servidor. */
	public boolean callsCrossDimension = true;
	/** A ligação do jogador agora (estado {@link #CALL_NONE} se não há). */
	public final Call call = new Call();
	/** O histórico de chamadas ("recentes"), da mais recente para a mais antiga. */
	public final List<CallLogEntry> callLog = new ArrayList<>();

	/** Prefixo do texto de uma mensagem de sistema ("saiu:123456"); igual ao {@code PhonePayloads.SYSTEM_LEFT}. */
	public static final String SYSTEM_LEFT = "saiu:";
	/** Prefixo das mensagens de sistema de chamada ("chamada:fim:75"); igual ao {@code PhonePayloads.SYSTEM_CALL}. */
	public static final String SYSTEM_CALL = "chamada:";

	/** Uma linha dos recentes. {@code number}: ligação direta = o outro número; chamada em grupo = quem chamou. */
	public record CallLogEntry(long time, long conversation, String number, boolean group, boolean outgoing, boolean answered, int seconds) {
	}

	/** Conversas que o celular guarda na lista (o servidor honesto manda no máximo 400 no Sync). */
	public static final int MAX_CONVERSATIONS = 600;

	/** Mensagens guardadas no cliente por conversa (o servidor guarda 300 e manda 100 ao abrir). */
	public static final int MAX_MESSAGES_PER_CONVERSATION = 300;

	/** Estados de {@link Call#state}; iguais aos {@code CALL_*} do protocolo. */
	public static final int CALL_NONE = 0, CALL_OUTGOING = 1, CALL_INCOMING = 2, CALL_ACTIVE = 3, CALL_ENDED = 4;
	/** Motivos de {@link Call#reason}; iguais aos {@code END_*} do protocolo. */
	public static final int END_HANGUP = 0, END_NO_ANSWER = 1, END_DECLINED = 2, END_BUSY = 3, END_FAILED = 4, END_MISSED = 5;

	/** Estados de {@link Live#state()}; iguais aos {@code LIVE_*} do protocolo (o PhoneOS não importa classes do jogo). */
	public static final int LIVE_POSITION = 0, LIVE_OTHER_DIMENSION = 1, LIVE_PAUSED = 2, LIVE_IDLE = 3;

	/** Situação do mapa e das opções de localização no servidor. */
	public static final class MapInfo {
		public boolean received;
		public boolean installed;
		public boolean rendered;
		public int maxZoom = -1;
		public String dimension = "";
		public boolean liveEnabled = true;
		public boolean messagesEnabled = true;
		public int maxPlaces = 32;
	}

	/** Uma pessoa na ligação; a voz já vem resolvida pelo servidor. */
	public record CallPart(String number, boolean joined, boolean voice, boolean muted) {
	}

	/** O retrato da ligação do jogador, como o servidor mandou. O celular só desenha; não calcula estado de ligação. */
	public static final class Call {
		public long id;
		public int state = CALL_NONE;
		public int reason;
		public long conversation;
		public boolean muted;
		/** O próprio jogador tem a voz ligada. */
		public boolean voice = true;
		public List<CallPart> parts = List.of();
		/** Relógio local do instante em que a ligação foi atendida (só com {@link #CALL_ACTIVE}), para contar a duração. */
		public long answeredLocalMs;
		/** Duração, em segundos, de uma ligação encerrada. */
		public int endedSeconds;
		/** A tela da ligação foi minimizada (o jogador voltou ao celular); uma barra mostra que a ligação segue. */
		public boolean minimized;
		/** Instante (relógio local, ms) em que a tela de "encerrada" some sozinha. */
		public long endedUntilMs;

		public void clear() {
			id = 0;
			state = CALL_NONE;
			reason = 0;
			conversation = 0;
			muted = false;
			voice = true;
			parts = List.of();
			answeredLocalMs = 0;
			endedSeconds = 0;
			minimized = false;
			endedUntilMs = 0;
		}

		/** Os números das outras pessoas da ligação. */
		public String firstNumber() {
			return parts.isEmpty() ? "" : parts.getFirst().number();
		}
	}

	/**
	 * O que o áudio e o HUD do jogo precisam saber da ligação, em um objeto imutável publicado sem trava
	 * ({@link PhoneOS#callView()}).
	 */
	public record CallView(long id, int state, int reason, boolean muted, int joined) {
		public static final CallView NONE = new CallView(0, CALL_NONE, 0, false, 0);
	}

	/** Um local salvo; {@code color} é o índice na paleta de {@link Geo#placeColor}. */
	public record Place(long id, String name, String dimension, int x, boolean hasY, int y, int z, int color) {
	}

	/**
	 * Alguém compartilhando com o jogador.
	 *
	 * @param state um dos {@code PhonePayloads.LIVE_*}; as coordenadas só valem em LIVE_POSITION
	 * @param until instante (relógio local) em que o prazo acaba
	 */
	public record Live(String number, int state, String dimension, int x, int y, int z, long until) {
	}

	public static final class Share {
		public boolean active;
		public long until;
		public List<String> audience = List.of();
	}

	/**
	 * Onde o jogador está. Os campos são {@code volatile} porque quem escreve (o tick do jogo) não pega a trava
	 * do PhoneOS: a thread de desenho a segura durante cada quadro e o jogo não pode esperar por ela.
	 */
	public static final class SelfPos {
		/** Uma leitura consistente (dimensão, x, y, z e olhar do mesmo instante), ou null; use esta quando o conjunto importa. */
		public volatile Fix fix;
		public volatile boolean known;
		public volatile String dimension = "";
		public volatile double x, y, z;
		/** Direção do olhar em graus, como no jogo (0 = sul, 90 = oeste). */
		public volatile float yaw;
		/** Nome do bioma já traduzido para o idioma do jogo. */
		public volatile String biome = "";
	}

	/** Posição do mesmo instante, publicada de uma vez (os campos soltos de {@link SelfPos} podem se misturar entre dois ticks). */
	public record Fix(String dimension, double x, double y, double z, float yaw) {
	}

	/** O que o HUD do jogo precisa saber, em um objeto imutável publicado sem trava ({@link PhoneOS#hud()}). */
	public record HudView(boolean sharing, long shareUntil, boolean hasRoute, String routeLabel, String routeDimension, int routeX, int routeZ, boolean routeKnown) {
		public static final HudView NONE = new HudView(false, 0, false, "", "", 0, 0, false);
	}

	/**
	 * Destino da rota. Para um contato ({@code contact} != null) a posição acompanha o que o servidor manda;
	 * {@code known} é falso enquanto não há coordenadas utilizáveis (outra dimensão, sem sinal).
	 */
	public static final class Route {
		public String label = "";
		public String dimension = "";
		public int x, z;
		public boolean known = true;
		public String contact;
	}

	/** Um ponto do mundo anexado a uma mensagem. {@code stamped}: localização de quem enviou; senão, um ponto marcado. */
	public record Loc(boolean stamped, String dimension, int x, boolean hasY, int y, int z, String label) {
	}

	/**
	 * @param photo id da foto anexada, ou 0 se a mensagem é só texto
	 * @param loc   ponto do mundo anexado, ou null
	 */
	public record Message(int seq, String sender, long time, String text, long photo, Loc loc) {
		/** Mensagem de sistema (como "saiu do grupo"): o servidor a manda sem remetente. */
		public boolean system() {
			return sender.isEmpty();
		}

		/** Texto curto para avisos e listas: a própria mensagem, "Foto" ou "Localização". */
		public String preview() {
			if (loc != null) {
				return !loc.label().isEmpty() ? Lang.t("handyphone.preview.place", loc.label()) : Lang.t(loc.stamped() ? "handyphone.preview.location" : "handyphone.preview.pin");
			}
			return text.isEmpty() && photo != 0 ? Lang.t("handyphone.preview.photo") : text;
		}
	}

	/** Uma foto da galeria; os pixels vêm do {@link PhotoCache}. */
	/**
	 * Informações de uma foto vindas do servidor (1.3). {@code dimension} vazia = sem local (foto do computador, antiga, ou de outra
	 * pessoa: o servidor só conta o local ao dono).
	 */
	public record PhotoInfo(long id, long time, int width, int height, int size, boolean imported, String dimension, int x, int y, int z) {
		public boolean hasPlace() {
			return !dimension.isEmpty();
		}
	}

	/** Quantas fotos da pasta local a Galeria mostra no máximo (as mais novas). */
	public static final int MAX_GALLERY_LOCAL = 500;

	/** Informações já recebidas, por foto (pedidas ao abrir o painel "i"). */
	public final java.util.Map<Long, PhotoInfo> photoInfo = new java.util.HashMap<>();

	public record GalleryPhoto(long id, long time, int size) {
	}

	public static final class Conversation {
		public long id;
		public boolean group;
		public String title = "";
		/** Números dos participantes, incluindo o próprio jogador. */
		public List<String> members = List.of();
		public int unread;
		public int lastSeq;
		public String lastSender = "";
		public String lastText = "";
		public boolean lastPhoto;
		public boolean lastLocation;
		public long lastTime;
		/** O histórico completo já chegou (e não só mensagens soltas recebidas ao vivo)? Só então vale mandar o {@code have} ao abrir. */
		public boolean historyLoaded;
		/** Mensagens já recebidas do servidor, em ordem. */
		public final List<Message> messages = new ArrayList<>();
	}

	/** O contato (ou número) está compartilhando com o jogador? Retorna a entrada, ou null. */
	public Live liveOf(String number) {
		for (Live l : live) {
			if (l.number().equals(number)) {
				return l;
			}
		}
		return null;
	}

	public Conversation conversation(long id) {
		for (Conversation c : conversations) {
			if (c.id == id) {
				return c;
			}
		}
		return null;
	}

	public int unreadTotal() {
		int total = 0;
		for (Conversation c : conversations) {
			total += c.unread;
		}
		return total;
	}

	/** A frase de uma mensagem de sistema, com o nome que o jogador deu ao contato. */
	public String systemText(String raw) {
		if (raw.startsWith(SYSTEM_LEFT)) {
			return Lang.t("handyphone.system.left", displayName(raw.substring(SYSTEM_LEFT.length())));
		}
		if (raw.startsWith(SYSTEM_CALL)) {
			String[] parts = raw.substring(SYSTEM_CALL.length()).split(":", 2);
			String arg = parts.length > 1 ? parts[1] : "";
			switch (parts[0]) {
				case "fim" -> {
					long seconds = 0;
					try {
						seconds = Long.parseLong(arg);
					} catch (NumberFormatException ignored) {
						// texto estranho de um servidor hostil: mostra a duração zerada
					}
					return Lang.t("handyphone.system.call_duration", duration(seconds));
				}
				case "perdida" -> {
					return arg.equals(number) ? Lang.t("handyphone.system.call_unanswered") : Lang.t("handyphone.system.call_missed", displayName(arg));
				}
				case "inicio" -> {
					return Lang.t("handyphone.system.call_started", displayName(arg));
				}
				default -> {
					return "";
				}
			}
		}
		return "";
	}

	/** 75 vira "01:15", 3700 vira "1:01:40". */
	public static String duration(long seconds) {
		seconds = Math.max(0, seconds);
		long h = seconds / 3600, m = seconds / 60 % 60, s = seconds % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%02d:%02d", m, s);
	}

	/** O texto que a lista mostra como última mensagem de uma conversa. */
	public String lastPreview(Conversation c) {
		if (c.lastSender.isEmpty() && (c.lastText.startsWith(SYSTEM_LEFT) || c.lastText.startsWith(SYSTEM_CALL))) {
			return systemText(c.lastText);
		}
		String text = c.lastLocation ? Lang.t("handyphone.preview.location") : c.lastText.isEmpty() && c.lastPhoto ? Lang.t("handyphone.preview.photo") : c.lastText;
		if (text.isEmpty()) {
			return Lang.t("handyphone.preview.empty");
		}
		return c.lastSender.equals(number) ? Lang.t("handyphone.preview.you", text) : c.group ? Lang.t("handyphone.preview.sender", displayName(c.lastSender), text) : text;
	}

	/** O último número da mensagem mais recente que o celular tem de uma conversa (0 = nenhuma). */
	public int haveSeq(Conversation c) {
		return !c.historyLoaded || c.messages.isEmpty() ? 0 : c.messages.getLast().seq();
	}

	/** O número está bloqueado? */
	public boolean isBlocked(String number) {
		return blocked.contains(number);
	}

	/** "123456" vira "123-456". */
	public static String formatNumber(String digits) {
		return digits.length() > 3 ? digits.substring(0, 3) + "-" + digits.substring(3) : digits;
	}

	/** Nome do contato, ou o número formatado se não estiver na agenda. */
	public String displayName(String number) {
		if (number.equals(this.number)) {
			return Lang.t("handyphone.you");
		}
		String name = contacts.get(number);
		return name != null ? name : formatNumber(number);
	}

	/** Título da conversa: nome do grupo, ou o outro participante. */
	public String titleOf(Conversation c) {
		if (c.group) {
			return c.title;
		}
		for (String member : c.members) {
			if (!member.equals(number)) {
				return displayName(member);
			}
		}
		return Lang.t("handyphone.chat.default_title");
	}

	/** Na conversa individual, o número da outra pessoa; null em grupos. */
	public String peerOf(Conversation c) {
		if (!c.group) {
			for (String member : c.members) {
				if (!member.equals(number)) {
					return member;
				}
			}
		}
		return null;
	}
}
