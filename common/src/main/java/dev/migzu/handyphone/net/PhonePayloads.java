package dev.migzu.handyphone.net;

import java.util.ArrayList;
import java.util.List;

/**
 * Pacotes do celular. O servidor é a autoridade: o cliente só pede ações e
 * recebe de volta o estado já validado. Números de telefone trafegam só com
 * dígitos; nomes de jogadores nunca são enviados.
 */
public final class PhonePayloads {
	public static final int MAX_TEXT = 256;
	public static final int MAX_NAME = 24;
	public static final int MAX_NUMBER = 12;
	public static final int MAX_GROUP_MEMBERS = 16;
	/** Limite de itens em qualquer lista recebida, para um cliente malicioso não estourar a memória. */
	private static final int MAX_LIST = 512;
	/** Fotos por galeria que cabem em uma lista de pacote; o teto de fotos do servidor nunca passa disto. */
	public static final int MAX_GALLERY = 500;
	/**
	 * Bytes de foto por pacote. O limite do jogo para pacotes do cliente é de ~32 KB,
	 * então uma foto (até 200 KB) viaja em partes pequenas e numeradas.
	 */
	public static final int MAX_CHUNK = 16 * 1024;
	/** Quem está enviando: a câmera do celular ou um arquivo do computador (declarado pelo cliente, não é garantia). */
	public static final int SOURCE_CAMERA = 0, SOURCE_IMPORT = 1;
	/** Maior PNG de tile que o servidor envia (e o cliente aceita). */
	public static final int MAX_TILE_BYTES = 600 * 1024;
	/** Tamanho máximo do id de uma dimensão ("minecraft:overworld"). */
	public static final int MAX_DIMENSION = 256;
	/** Prazos do compartilhamento ao vivo, em minutos; o pacote leva só o índice. */
	public static final int[] SHARE_MINUTES = {15, 60, 480};
	/** Quantas cores de local existem (o pacote leva só o índice; as cores em si são do cliente). */
	public static final int PLACE_COLORS = 8;
	/** Maior valor de coordenada aceito (a borda do mundo). */
	public static final int WORLD_LIMIT = 29_999_999;
	/** Motivos de um tile indisponível. */
	public static final int TILE_MISSING = 0, TILE_BUSY = 1, TILE_STALE = 2, TILE_UNCHANGED = 3;
	/** Estados de uma entrada do compartilhamento ao vivo. */
	public static final int LIVE_POSITION = 0, LIVE_OTHER_DIMENSION = 1, LIVE_PAUSED = 2, LIVE_IDLE = 3;
	/**
	 * Mensagem de sistema (remetente vazio) com este prefixo no texto: "saiu:&lt;número&gt;". O celular monta a frase
	 * ("Fulano saiu do grupo") com o nome que o jogador deu ao contato; o servidor nunca manda texto livre como sistema.
	 */
	public static final String SYSTEM_LEFT = "saiu:";
	/**
	 * Mensagens de sistema sobre chamadas de voz (remetente vazio, como "saiu:"): {@code chamada:inicio:<número de quem chamou>},
	 * {@code chamada:fim:<segundos>} e {@code chamada:perdida:<número de quem chamou>}. O celular monta a frase.
	 */
	public static final String SYSTEM_CALL = "chamada:";
	/** Registros do histórico de chamadas que o servidor guarda por conta (os mais recentes). */
	public static final int MAX_CALL_LOG = 30;

	/** Números que um jogador pode bloquear ao mesmo tempo. */
	public static final int MAX_BLOCKED = 100;
	/** Estados da ligação do jogador, como o celular os vê. */
	public static final int CALL_NONE = 0, CALL_OUTGOING = 1, CALL_INCOMING = 2, CALL_ACTIVE = 3, CALL_ENDED = 4;
	/** Por que uma ligação terminou (só vale em {@link #CALL_ENDED}). */
	public static final int END_HANGUP = 0, END_NO_ANSWER = 1, END_DECLINED = 2, END_BUSY = 3, END_FAILED = 4, END_MISSED = 5;
	/** Participantes de uma ligação que cabem em um pacote (o teto real é {@code maxCallParticipants}). */
	public static final int MAX_CALL_PARTS = 32;

	/**
	 * Celular visto de fora (1.3): o que o jogador está fazendo com ele. Nada da tela viaja, só isto e o tema (índice na lista fixa de
	 * temas do celular, que o servidor não conhece pelo nome).
	 */
	public static final int POSE_NONE = 0, POSE_USE = 1, POSE_CAMERA = 2, POSE_SELFIE = 3;
	/** Maior pose aceita e quantos temas existem (valor fora vira o padrão). */
	public static final int MAX_POSE = 3, POSE_THEMES = 4;
	/** Poses por pacote que o servidor manda (a leitura aceita o teto geral de listas). */
	public static final int MAX_POSES = 256;

	private PhonePayloads() {
	}

	public record ContactEntry(String number, String name) {
	}

	/**
	 * Resumo de uma conversa para a lista.
	 *
	 * @param members números de todos os participantes, incluindo o próprio jogador
	 * @param unread  quantas mensagens o jogador ainda não leu
	 */
	public record ConversationEntry(
		long id, boolean group, String title, List<String> members, int unread, int lastSeq, String lastSender, String lastText, long lastTime,
		boolean lastPhoto, boolean lastLocation
	) {
	}

	/**
	 * Um ponto do mundo anexado a uma mensagem.
	 *
	 * @param stamped true = a localização de quem enviou, carimbada pelo servidor; false = um "ponto marcado" escolhido no mapa
	 * @param hasY    false quando o ponto vem do mapa (que não conhece a altura)
	 * @param label   nome do local, quando é um local salvo encaminhado; vazio nos outros casos
	 */
	public record Loc(boolean stamped, String dimension, int x, boolean hasY, int y, int z, String label) {
	}

	/**
	 * @param photo id da foto anexada, ou 0 se a mensagem é só texto
	 * @param loc   ponto do mundo anexado, ou null
	 */
	public record MessageEntry(int seq, String sender, long time, String text, long photo, Loc loc) {
	}

	/** Um local salvo pelo jogador (anotação particular dele). */
	public record PlaceEntry(long id, String name, String dimension, int x, boolean hasY, int y, int z, int color) {
	}

	/**
	 * Alguém compartilhando a localização com este jogador.
	 *
	 * @param state     um dos {@code LIVE_*}; só {@link #LIVE_POSITION} traz x, y e z
	 * @param dimension dimensão em que a pessoa está (só o nome, sem coordenadas, se for outra)
	 */
	public record LiveEntry(String number, int state, String dimension, int x, int y, int z, int secondsLeft) {
	}

	/** Uma foto da galeria do jogador. */
	public record PhotoEntry(long id, long time, int size) {
	}

	// ------------------------------------------------------------------ servidor -> cliente

	/** Estado completo do jogador: seu número, contatos e lista de conversas. */
	public record Sync(String number, List<ContactEntry> contacts, List<ConversationEntry> conversations) implements Payload {
		public static final PacketType<Sync> TYPE = PacketType.of("sync_v3", Sync::new);

		private Sync(PhoneBuf buf) {
			this(
				buf.readUtf(MAX_NUMBER),
				readList(buf, b -> new ContactEntry(b.readUtf(MAX_NUMBER), b.readUtf(MAX_NAME))),
				readList(buf, PhonePayloads::readConversation)
			);
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(number, MAX_NUMBER);
			writeList(buf, contacts, (b, c) -> {
				b.writeUtf(c.number(), MAX_NUMBER);
				b.writeUtf(c.name(), MAX_NAME);
			});
			writeList(buf, conversations, PhonePayloads::writeConversation);
		}

		@Override
		public PacketType<Sync> type() {
			return TYPE;
		}
	}

	/**
	 * Mensagens de uma conversa.
	 *
	 * @param history true = histórico completo (substitui o que o cliente tinha); false = mensagens novas
	 * @param focus   true = o cliente deve abrir essa conversa na tela
	 */
	public record Messages(long conversation, boolean history, boolean focus, List<MessageEntry> messages) implements Payload {
		public static final PacketType<Messages> TYPE = PacketType.of("messages_v3", Messages::new);

		private Messages(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean(), buf.readBoolean(),
				readList(buf, b -> new MessageEntry(b.readVarInt(), b.readUtf(MAX_NUMBER), b.readLong(), b.readUtf(MAX_TEXT), b.readVarLong(), readLoc(b))));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeBoolean(history);
			buf.writeBoolean(focus);
			writeList(buf, messages, (b, m) -> {
				b.writeVarInt(m.seq());
				b.writeUtf(m.sender(), MAX_NUMBER);
				b.writeLong(m.time());
				b.writeUtf(m.text(), MAX_TEXT);
				b.writeVarLong(m.photo());
				writeLoc(b, m.loc());
			});
		}

		@Override
		public PacketType<Messages> type() {
			return TYPE;
		}
	}

	/**
	 * Uma conversa nova ou que mudou (mensagem nova, aberta agora): o celular troca só essa entrada da lista. Evita
	 * mandar o {@link Sync} inteiro a cada mensagem, o que multiplicaria o custo para quem está em muitas conversas.
	 */
	public record ConvUpdate(ConversationEntry entry) implements Payload {
		public static final PacketType<ConvUpdate> TYPE = PacketType.of("conv_update", ConvUpdate::new);

		private ConvUpdate(PhoneBuf buf) {
			this(readConversation(buf));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeConversation(buf, entry);
		}

		@Override
		public PacketType<ConvUpdate> type() {
			return TYPE;
		}
	}

	/** Os números que o jogador bloqueou (a lista inteira, substituindo a anterior). */
	public record Blocked(List<String> numbers) implements Payload {
		public static final PacketType<Blocked> TYPE = PacketType.of("blocked", Blocked::new);

		private Blocked(PhoneBuf buf) {
			this(readList(buf, b -> b.readUtf(MAX_NUMBER)));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, numbers, (b, n) -> b.writeUtf(n, MAX_NUMBER));
		}

		@Override
		public PacketType<Blocked> type() {
			return TYPE;
		}
	}

	/** Tamanho máximo da chave de um {@link Notice} e quantos argumentos ele leva. */
	public static final int MAX_NOTICE_KEY = 80, MAX_NOTICE_ARGS = 4;

	/**
	 * Aviso curto do servidor (por exemplo, "esse número não existe"). Viaja como chave de texto
	 * ({@code handyphone.notice.*}) mais argumentos, e o celular traduz no idioma do jogador: o servidor não sabe
	 * em que idioma cada um joga.
	 */
	public record Notice(String key, List<String> args) implements Payload {
		public static final PacketType<Notice> TYPE = PacketType.of("notice_v2", Notice::new);

		public Notice(String key, String... args) {
			this(key, List.of(args));
		}

		private Notice(PhoneBuf buf) {
			this(buf.readUtf(MAX_NOTICE_KEY), readArgs(buf));
		}

		private static List<String> readArgs(PhoneBuf buf) {
			int size = buf.readVarInt();
			if (size < 0 || size > MAX_NOTICE_ARGS) {
				throw new IllegalArgumentException("Notice with too many arguments in a HandyPhone packet: " + size);
			}
			List<String> args = new ArrayList<>(size);
			for (int i = 0; i < size; i++) {
				args.add(buf.readUtf(MAX_TEXT));
			}
			return args;
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(key, MAX_NOTICE_KEY);
			buf.writeVarInt(Math.min(args.size(), MAX_NOTICE_ARGS));
			for (int i = 0; i < Math.min(args.size(), MAX_NOTICE_ARGS); i++) {
				buf.writeUtf(args.get(i), MAX_TEXT);
			}
		}

		/** O aviso em inglês, para logs e testes. */
		public String english() {
			return dev.migzu.handyphone.i18n.LangFile.format(dev.migzu.handyphone.i18n.LangFile.english(key), args.toArray());
		}

		@Override
		public PacketType<Notice> type() {
			return TYPE;
		}
	}

	/**
	 * A galeria do jogador (só a lista; os pixels vêm sob demanda) e a situação da cota.
	 *
	 * @param importAllowed false se o dono do servidor desligou o envio de imagens do computador
	 */
	public record Gallery(List<PhotoEntry> photos, long usedBytes, long quotaBytes, boolean importAllowed) implements Payload {
		public static final PacketType<Gallery> TYPE = PacketType.of("gallery", Gallery::new);

		private Gallery(PhoneBuf buf) {
			this(readList(buf, b -> new PhotoEntry(b.readVarLong(), b.readLong(), b.readVarInt())), buf.readVarLong(), buf.readVarLong(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, photos, (b, p) -> {
				b.writeVarLong(p.id());
				b.writeLong(p.time());
				b.writeVarInt(p.size());
			});
			buf.writeVarLong(usedBytes);
			buf.writeVarLong(quotaBytes);
			buf.writeBoolean(importAllowed);
		}

		@Override
		public PacketType<Gallery> type() {
			return TYPE;
		}
	}

	/** Uma parte (numerada) dos bytes de uma foto ou miniatura pedida pelo cliente. */
	public record PhotoData(long id, boolean thumb, int index, int count, byte[] data) implements Payload {
		public static final PacketType<PhotoData> TYPE = PacketType.of("photo_data", PhotoData::new);

		private PhotoData(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeBoolean(thumb);
			buf.writeVarInt(index);
			buf.writeVarInt(count);
			buf.writeByteArray(data);
		}

		@Override
		public PacketType<PhotoData> type() {
			return TYPE;
		}
	}

	/** O servidor não vai mandar essa foto: não existe ou não é do jogador ({@code retry} falso), ou ele pediu demais ({@code retry} verdadeiro). */
	public record PhotoUnavailable(long id, boolean thumb, boolean retry) implements Payload {
		public static final PacketType<PhotoUnavailable> TYPE = PacketType.of("photo_unavailable", PhotoUnavailable::new);

		private PhotoUnavailable(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeBoolean(thumb);
			buf.writeBoolean(retry);
		}

		@Override
		public PacketType<PhotoUnavailable> type() {
			return TYPE;
		}
	}

	/** Resultado de um envio de foto; o motivo de uma recusa vem em um {@link Notice}. */
	public record UploadResult(int token, boolean ok, long photoId) implements Payload {
		public static final PacketType<UploadResult> TYPE = PacketType.of("upload_result", UploadResult::new);

		private UploadResult(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readBoolean(), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(token);
			buf.writeBoolean(ok);
			buf.writeVarLong(photoId);
		}

		@Override
		public PacketType<UploadResult> type() {
			return TYPE;
		}
	}

	/**
	 * Uma linha do histórico de chamadas ("recentes").
	 *
	 * @param conversation conversa da chamada (grupo, ou a conversa direta)
	 * @param number       ligação direta: o outro número; chamada em grupo: quem chamou
	 * @param group        chamada em grupo
	 * @param outgoing     o próprio jogador chamou
	 * @param answered     o jogador entrou na chamada (senão: não atendida, se ele chamou, ou perdida)
	 * @param seconds      duração, se foi atendida
	 */
	public record CallLogEntry(long time, long conversation, String number, boolean group, boolean outgoing, boolean answered, int seconds) {
	}

	/** O histórico de chamadas do jogador, da mais recente para a mais antiga (a lista inteira, substituindo a anterior). */
	public record CallLog(List<CallLogEntry> entries) implements Payload {
		public static final PacketType<CallLog> TYPE = PacketType.of("call_log", CallLog::new);

		private CallLog(PhoneBuf buf) {
			this(readList(buf, b -> new CallLogEntry(b.readLong(), b.readVarLong(), b.readUtf(MAX_NUMBER), b.readBoolean(), b.readBoolean(), b.readBoolean(), b.readVarInt())));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, entries, (b, e) -> {
				b.writeLong(e.time());
				b.writeVarLong(e.conversation());
				b.writeUtf(e.number(), MAX_NUMBER);
				b.writeBoolean(e.group());
				b.writeBoolean(e.outgoing());
				b.writeBoolean(e.answered());
				b.writeVarInt(e.seconds());
			});
		}

		@Override
		public PacketType<CallLog> type() {
			return TYPE;
		}
	}

	/** Uma pessoa na ligação, como o celular a vê: já entrou? tem voz? está no mudo? */
	public record CallPart(String number, boolean joined, boolean voice, boolean muted) {
	}

	/**
	 * O servidor diz se as ligações existem neste servidor: ligadas na configuração <b>e</b> com o Simple Voice Chat
	 * funcionando. É enviado na entrada e sempre que muda.
	 */
	public record CallInfo(boolean available, boolean crossDimension) implements Payload {
		public static final PacketType<CallInfo> TYPE = PacketType.of("call_info", CallInfo::new);

		private CallInfo(PhoneBuf buf) {
			this(buf.readBoolean(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeBoolean(available);
			buf.writeBoolean(crossDimension);
		}

		@Override
		public PacketType<CallInfo> type() {
			return TYPE;
		}
	}

	/**
	 * O retrato completo da ligação do jogador (o celular só desenha o que o servidor manda; não calcula estado).
	 *
	 * @param id           id da ligação, gerado no servidor (0 com {@link #CALL_NONE})
	 * @param state        um dos {@code CALL_*}
	 * @param reason       um dos {@code END_*} (só em {@link #CALL_ENDED})
	 * @param conversation grupo de onde a chamada partiu (0 = ligação direta)
	 * @param muted        o próprio jogador está no mudo
	 * @param voice        o próprio jogador tem a voz ligada (Simple Voice Chat instalado e ativo); sem ela a ligação toca, mas ele não ouve nem fala
	 * @param seconds      tocando: segundos que faltam para "não atendeu"; em ligação: segundos desde que atendeu; encerrada: duração total
	 * @param parts        os outros participantes (a voz de cada um já vem resolvida pelo servidor)
	 */
	public record CallUpdate(long id, int state, int reason, long conversation, boolean muted, boolean voice, int seconds, List<CallPart> parts)
		implements Payload {
		public static final PacketType<CallUpdate> TYPE = PacketType.of("call_update", CallUpdate::new);
		public static final CallUpdate NONE = new CallUpdate(0, CALL_NONE, 0, 0, false, true, 0, List.of());

		private CallUpdate(PhoneBuf buf) {
			this(
				buf.readVarLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarLong(), buf.readBoolean(), buf.readBoolean(), buf.readVarInt(),
				readCallParts(buf)
			);
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeVarInt(state);
			buf.writeVarInt(reason);
			buf.writeVarLong(conversation);
			buf.writeBoolean(muted);
			buf.writeBoolean(voice);
			buf.writeVarInt(seconds);
			writeList(buf, parts, (b, part) -> {
				b.writeUtf(part.number(), MAX_NUMBER);
				b.writeBoolean(part.joined());
				b.writeBoolean(part.voice());
				b.writeBoolean(part.muted());
			});
		}

		@Override
		public PacketType<CallUpdate> type() {
			return TYPE;
		}
	}

	private static List<CallPart> readCallParts(PhoneBuf buf) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_CALL_PARTS) {
			throw new IllegalArgumentException("Too many participants in a HandyPhone packet: " + size);
		}
		List<CallPart> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(new CallPart(buf.readUtf(MAX_NUMBER), buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
		}
		return list;
	}

	// ------------------------------------------------------------------ cliente -> servidor

	public record Send(long conversation, String text) implements Payload {
		public static final PacketType<Send> TYPE = PacketType.of("send", Send::new);

		private Send(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readUtf(MAX_TEXT));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeUtf(text, MAX_TEXT);
		}

		@Override
		public PacketType<Send> type() {
			return TYPE;
		}
	}

	/** Abre (criando se preciso) a conversa individual com um número. */
	public record OpenDirect(String number) implements Payload {
		public static final PacketType<OpenDirect> TYPE = PacketType.of("open_direct", OpenDirect::new);

		private OpenDirect(PhoneBuf buf) {
			this(buf.readUtf(MAX_NUMBER));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(number, MAX_NUMBER);
		}

		@Override
		public PacketType<OpenDirect> type() {
			return TYPE;
		}
	}

	/**
	 * Pede o histórico de uma conversa e a marca como lida.
	 *
	 * @param have número da última mensagem que o celular já tem dessa conversa (0 = nenhuma): o servidor só manda o que vem depois
	 */
	public record Open(long conversation, int have) implements Payload {
		public static final PacketType<Open> TYPE = PacketType.of("open_v2", Open::new);

		private Open(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeVarInt(have);
		}

		@Override
		public PacketType<Open> type() {
			return TYPE;
		}
	}

	/** Marca a conversa como lida até a mensagem {@code seq}. */
	public record Read(long conversation, int seq) implements Payload {
		public static final PacketType<Read> TYPE = PacketType.of("read", Read::new);

		private Read(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeVarInt(seq);
		}

		@Override
		public PacketType<Read> type() {
			return TYPE;
		}
	}

	/** Salva ou renomeia um contato; nome vazio remove. */
	public record Contact(String number, String name) implements Payload {
		public static final PacketType<Contact> TYPE = PacketType.of("contact", Contact::new);

		private Contact(PhoneBuf buf) {
			this(buf.readUtf(MAX_NUMBER), buf.readUtf(MAX_NAME));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(number, MAX_NUMBER);
			buf.writeUtf(name, MAX_NAME);
		}

		@Override
		public PacketType<Contact> type() {
			return TYPE;
		}
	}

	/** Bloqueia ({@code on}) ou desbloqueia um número: o servidor passa a esconder tudo o que vier dele, sem avisá-lo. */
	public record Block(String number, boolean on) implements Payload {
		public static final PacketType<Block> TYPE = PacketType.of("block", Block::new);

		private Block(PhoneBuf buf) {
			this(buf.readUtf(MAX_NUMBER), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(number, MAX_NUMBER);
			buf.writeBoolean(on);
		}

		@Override
		public PacketType<Block> type() {
			return TYPE;
		}
	}

	/** Sai de um grupo: o grupo continua para os outros, com uma mensagem de sistema. */
	public record LeaveGroup(long conversation) implements Payload {
		public static final PacketType<LeaveGroup> TYPE = PacketType.of("leave_group", LeaveGroup::new);

		private LeaveGroup(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
		}

		@Override
		public PacketType<LeaveGroup> type() {
			return TYPE;
		}
	}

	public record CreateGroup(String title, List<String> numbers) implements Payload {
		public static final PacketType<CreateGroup> TYPE = PacketType.of("create_group", CreateGroup::new);

		private CreateGroup(PhoneBuf buf) {
			this(buf.readUtf(MAX_NAME), readList(buf, b -> b.readUtf(MAX_NUMBER)));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(title, MAX_NAME);
			writeList(buf, numbers, (b, n) -> b.writeUtf(n, MAX_NUMBER));
		}

		@Override
		public PacketType<CreateGroup> type() {
			return TYPE;
		}
	}

	/**
	 * Começa o envio de uma foto, que segue em {@link UploadChunk} e termina em {@link UploadEnd}.
	 *
	 * @param token  número escolhido pelo cliente para reconhecer a resposta
	 * @param total  tamanho em bytes do arquivo inteiro
	 * @param format formato do arquivo (ver PhotoCodec)
	 * @param source {@link #SOURCE_CAMERA} ou {@link #SOURCE_IMPORT}
	 */
	public record UploadBegin(int token, int total, int format, int source) implements Payload {
		public static final PacketType<UploadBegin> TYPE = PacketType.of("upload_begin", UploadBegin::new);

		private UploadBegin(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(token);
			buf.writeVarInt(total);
			buf.writeVarInt(format);
			buf.writeVarInt(source);
		}

		@Override
		public PacketType<UploadBegin> type() {
			return TYPE;
		}
	}

	public record UploadChunk(int index, byte[] data) implements Payload {
		public static final PacketType<UploadChunk> TYPE = PacketType.of("upload_chunk", UploadChunk::new);

		private UploadChunk(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readByteArray(MAX_CHUNK));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(index);
			buf.writeByteArray(data);
		}

		@Override
		public PacketType<UploadChunk> type() {
			return TYPE;
		}
	}

	public record UploadEnd(int token) implements Payload {
		public static final PacketType<UploadEnd> TYPE = PacketType.of("upload_end", UploadEnd::new);

		private UploadEnd(PhoneBuf buf) {
			this(buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(token);
		}

		@Override
		public PacketType<UploadEnd> type() {
			return TYPE;
		}
	}

	/** Pede os bytes de uma foto ou da miniatura dela. */
	public record PhotoGet(long id, boolean thumb) implements Payload {
		public static final PacketType<PhotoGet> TYPE = PacketType.of("photo_get", PhotoGet::new);

		private PhotoGet(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeBoolean(thumb);
		}

		@Override
		public PacketType<PhotoGet> type() {
			return TYPE;
		}
	}

	/** Apaga uma foto da galeria do jogador. */
	public record PhotoDelete(long id) implements Payload {
		public static final PacketType<PhotoDelete> TYPE = PacketType.of("photo_delete", PhotoDelete::new);

		private PhotoDelete(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<PhotoDelete> type() {
			return TYPE;
		}
	}

	/** Pede as informações de uma foto (1.3): só responde a quem pode vê-la. */
	public record PhotoInfoGet(long id) implements Payload {
		public static final PacketType<PhotoInfoGet> TYPE = PacketType.of("photo_info_get", PhotoInfoGet::new);

		private PhotoInfoGet(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<PhotoInfoGet> type() {
			return TYPE;
		}
	}

	/**
	 * Informações de uma foto (1.3): quando foi guardada, tamanho, se veio do computador e, só para o dono e só de fotos da câmera, onde
	 * foi tirada (dimensão vazia = sem local). Quem recebeu a foto numa conversa nunca vê o local.
	 */
	public record PhotoInfo(long id, long time, int width, int height, int size, boolean imported, String dimension, int x, int y, int z)
		implements Payload {
		public static final PacketType<PhotoInfo> TYPE = PacketType.of("photo_info", PhotoInfo::new);

		private PhotoInfo(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), buf.readUtf(MAX_DIMENSION),
				buf.readInt(), buf.readInt(), buf.readInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeLong(time);
			buf.writeVarInt(width);
			buf.writeVarInt(height);
			buf.writeVarInt(size);
			buf.writeBoolean(imported);
			buf.writeUtf(dimension, MAX_DIMENSION);
			buf.writeInt(x);
			buf.writeInt(y);
			buf.writeInt(z);
		}

		@Override
		public PacketType<PhotoInfo> type() {
			return TYPE;
		}
	}

	/** Quantas fotos um {@link PhotoDeleteMany} leva no máximo (a galeria tem até {@link #MAX_GALLERY}: até 5 pacotes). */
	public static final int MAX_DELETE_MANY = 100;

	/** Apaga várias fotos da própria galeria de uma vez (1.3; seleção na Galeria). Servidor antigo sem o canal: o cliente manda uma a uma. */
	public record PhotoDeleteMany(List<Long> ids) implements Payload {
		public static final PacketType<PhotoDeleteMany> TYPE = PacketType.of("photo_delete_many", PhotoDeleteMany::new);

		public PhotoDeleteMany {
			ids = List.copyOf(ids);
		}

		private PhotoDeleteMany(PhoneBuf buf) {
			this(readList(buf, PhoneBuf::readVarLong));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, ids, PhoneBuf::writeVarLong);
		}

		@Override
		public PacketType<PhotoDeleteMany> type() {
			return TYPE;
		}
	}

	/** Manda, em uma conversa, uma foto que está na galeria do jogador. */
	public record SendPhoto(long conversation, long photo) implements Payload {
		public static final PacketType<SendPhoto> TYPE = PacketType.of("send_photo", SendPhoto::new);

		private SendPhoto(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeVarLong(photo);
		}

		@Override
		public PacketType<SendPhoto> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ localização: servidor -> cliente

	/**
	 * O que o servidor oferece de localização e a situação do mapa da dimensão em que o jogador está agora.
	 * Chega ao entrar e a cada troca de dimensão (o {@code epoch} muda: tiles da época anterior não valem mais).
	 *
	 * @param installed        o servidor tem o squaremap
	 * @param rendered         e já há mapa desenhado para esta dimensão (e o dono não desligou os tiles)
	 * @param maxZoom          maior zoom de tile disponível (1 pixel por bloco), ou -1
	 * @param liveEnabled      compartilhamento ao vivo ligado
	 * @param messagesEnabled  mandar localização em mensagem ligado
	 * @param maxPlaces        quantos locais salvos o jogador pode ter
	 */
	public record MapInfo(
		boolean installed, boolean rendered, int maxZoom, String dimension, int epoch, boolean liveEnabled, boolean messagesEnabled, int maxPlaces
	) implements Payload {
		public static final PacketType<MapInfo> TYPE = PacketType.of("map_info", MapInfo::new);

		private MapInfo(PhoneBuf buf) {
			this(
				buf.readBoolean(), buf.readBoolean(), buf.readVarInt() - 1, buf.readUtf(MAX_DIMENSION), buf.readVarInt(),
				buf.readBoolean(), buf.readBoolean(), buf.readVarInt()
			);
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeBoolean(installed);
			buf.writeBoolean(rendered);
			buf.writeVarInt(maxZoom + 1);
			buf.writeUtf(dimension, MAX_DIMENSION);
			buf.writeVarInt(epoch);
			buf.writeBoolean(liveEnabled);
			buf.writeBoolean(messagesEnabled);
			buf.writeVarInt(maxPlaces);
		}

		@Override
		public PacketType<MapInfo> type() {
			return TYPE;
		}
	}

	/**
	 * Uma parte (numerada) do PNG de um tile do squaremap.
	 *
	 * @param stamp carimbo da versão do arquivo (muda quando o squaremap redesenha o tile); o cliente o devolve em
	 *              {@link TileGet} para o servidor responder "não mudou" em vez de reenviar a imagem
	 */
	public record TileData(int epoch, int zoom, int x, int z, long stamp, int index, int count, byte[] data) implements Payload {
		public static final PacketType<TileData> TYPE = PacketType.of("tile_data", TileData::new);

		private TileData(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(MAX_CHUNK));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(epoch);
			buf.writeVarInt(zoom);
			buf.writeInt(x);
			buf.writeInt(z);
			buf.writeLong(stamp);
			buf.writeVarInt(index);
			buf.writeVarInt(count);
			buf.writeByteArray(data);
		}

		@Override
		public PacketType<TileData> type() {
			return TYPE;
		}
	}

	/** O servidor não vai mandar esse tile; {@code reason} é um dos {@code TILE_*}. */
	public record TileUnavailable(int epoch, int zoom, int x, int z, int reason) implements Payload {
		public static final PacketType<TileUnavailable> TYPE = PacketType.of("tile_unavailable", TileUnavailable::new);

		private TileUnavailable(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(epoch);
			buf.writeVarInt(zoom);
			buf.writeInt(x);
			buf.writeInt(z);
			buf.writeVarInt(reason);
		}

		@Override
		public PacketType<TileUnavailable> type() {
			return TYPE;
		}
	}

	/**
	 * Quem está compartilhando a localização com o jogador, <b>substituindo</b> a lista anterior. Só as
	 * pessoas que autorizaram este jogador aparecem aqui; as posições só vêm enquanto ele está com o Mapa
	 * aberto ou com uma rota ativa ({@link Watch}).
	 */
	public record Live(List<LiveEntry> entries) implements Payload {
		public static final PacketType<Live> TYPE = PacketType.of("live", Live::new);

		private Live(PhoneBuf buf) {
			this(readList(buf, b -> new LiveEntry(
				b.readUtf(MAX_NUMBER), b.readVarInt(), b.readUtf(MAX_DIMENSION), b.readInt(), b.readInt(), b.readInt(), b.readVarInt()
			)));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, entries, (b, e) -> {
				b.writeUtf(e.number(), MAX_NUMBER);
				b.writeVarInt(e.state());
				b.writeUtf(e.dimension(), MAX_DIMENSION);
				b.writeInt(e.x());
				b.writeInt(e.y());
				b.writeInt(e.z());
				b.writeVarInt(e.secondsLeft());
			});
		}

		@Override
		public PacketType<Live> type() {
			return TYPE;
		}
	}

	/** A situação do compartilhamento do próprio jogador: ativo (e por quanto tempo, com quem) ou parado. */
	public record ShareStatus(boolean active, int secondsLeft, List<String> audience) implements Payload {
		public static final PacketType<ShareStatus> TYPE = PacketType.of("share_status", ShareStatus::new);

		private ShareStatus(PhoneBuf buf) {
			this(buf.readBoolean(), buf.readVarInt(), readList(buf, b -> b.readUtf(MAX_NUMBER)));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeBoolean(active);
			buf.writeVarInt(secondsLeft);
			writeList(buf, audience, (b, n) -> b.writeUtf(n, MAX_NUMBER));
		}

		@Override
		public PacketType<ShareStatus> type() {
			return TYPE;
		}
	}

	/** Os locais salvos do jogador. */
	public record Places(List<PlaceEntry> places) implements Payload {
		public static final PacketType<Places> TYPE = PacketType.of("places", Places::new);

		private Places(PhoneBuf buf) {
			this(readList(buf, b -> new PlaceEntry(
				b.readVarLong(), b.readUtf(MAX_NAME), b.readUtf(MAX_DIMENSION), b.readInt(), b.readBoolean(), b.readInt(), b.readInt(), b.readVarInt()
			)));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, places, (b, p) -> {
				b.writeVarLong(p.id());
				b.writeUtf(p.name(), MAX_NAME);
				b.writeUtf(p.dimension(), MAX_DIMENSION);
				b.writeInt(p.x());
				b.writeBoolean(p.hasY());
				b.writeInt(p.y());
				b.writeInt(p.z());
				b.writeVarInt(p.color());
			});
		}

		@Override
		public PacketType<Places> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ localização: cliente -> servidor

	/**
	 * Pede um tile do mapa da dimensão em que o jogador está (o servidor nunca recebe dimensão nem caminho).
	 *
	 * @param have carimbo da versão que o cliente já tem (0 = nenhuma): se o arquivo não mudou, o servidor só avisa
	 */
	public record TileGet(int epoch, int zoom, int x, int z, long have) implements Payload {
		public static final PacketType<TileGet> TYPE = PacketType.of("tile_get", TileGet::new);

		private TileGet(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarInt(), buf.readInt(), buf.readInt(), buf.readLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(epoch);
			buf.writeVarInt(zoom);
			buf.writeInt(x);
			buf.writeInt(z);
			buf.writeLong(have);
		}

		@Override
		public PacketType<TileGet> type() {
			return TYPE;
		}
	}

	/** Manda, em uma conversa, a localização atual do jogador. Não leva coordenadas: o servidor lê a posição da entidade dele. */
	public record SendLocation(long conversation) implements Payload {
		public static final PacketType<SendLocation> TYPE = PacketType.of("send_location", SendLocation::new);

		private SendLocation(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
		}

		@Override
		public PacketType<SendLocation> type() {
			return TYPE;
		}
	}

	/** Manda, em uma conversa, um "ponto marcado" escolhido no mapa (não é a localização da pessoa). */
	public record SendPin(long conversation, int x, int z) implements Payload {
		public static final PacketType<SendPin> TYPE = PacketType.of("send_pin", SendPin::new);

		private SendPin(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readInt(), buf.readInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeInt(x);
			buf.writeInt(z);
		}

		@Override
		public PacketType<SendPin> type() {
			return TYPE;
		}
	}

	/**
	 * Começa a compartilhar a localização ao vivo.
	 *
	 * @param duration     índice em {@link #SHARE_MINUTES}
	 * @param conversation um grupo de que o jogador participa (todos os membros veem), ou 0 para usar {@code numbers}
	 * @param numbers      contatos do jogador que vão ver
	 */
	public record ShareStart(int duration, long conversation, List<String> numbers) implements Payload {
		public static final PacketType<ShareStart> TYPE = PacketType.of("share_start", ShareStart::new);

		private ShareStart(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarLong(), readList(buf, b -> b.readUtf(MAX_NUMBER)));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(duration);
			buf.writeVarLong(conversation);
			writeList(buf, numbers, (b, n) -> b.writeUtf(n, MAX_NUMBER));
		}

		@Override
		public PacketType<ShareStart> type() {
			return TYPE;
		}
	}

	/** Para de compartilhar agora. */
	public record ShareStop() implements Payload {
		public static final PacketType<ShareStop> TYPE = PacketType.of("share_stop", ShareStop::new);

		private ShareStop(PhoneBuf buf) {
			this();
		}

		@Override
		public void write(PhoneBuf buf) {
		}

		@Override
		public PacketType<ShareStop> type() {
			return TYPE;
		}
	}

	/** O jogador está (ou deixou de estar) olhando posições ao vivo: Mapa aberto ou rota até um contato. */
	public record Watch(boolean on) implements Payload {
		public static final PacketType<Watch> TYPE = PacketType.of("watch", Watch::new);

		private Watch(PhoneBuf buf) {
			this(buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeBoolean(on);
		}

		@Override
		public PacketType<Watch> type() {
			return TYPE;
		}
	}

	/**
	 * Salva um local (anotação particular do jogador, então as coordenadas podem vir do cliente: a posição
	 * atual dele, um ponto do mapa ou um ponto recebido em mensagem). O servidor valida tudo.
	 */
	public record PlaceSave(String name, String dimension, int x, boolean hasY, int y, int z, int color) implements Payload {
		public static final PacketType<PlaceSave> TYPE = PacketType.of("place_save", PlaceSave::new);

		private PlaceSave(PhoneBuf buf) {
			this(buf.readUtf(MAX_NAME), buf.readUtf(MAX_DIMENSION), buf.readInt(), buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(name, MAX_NAME);
			buf.writeUtf(dimension, MAX_DIMENSION);
			buf.writeInt(x);
			buf.writeBoolean(hasY);
			buf.writeInt(y);
			buf.writeInt(z);
			buf.writeVarInt(color);
		}

		@Override
		public PacketType<PlaceSave> type() {
			return TYPE;
		}
	}

	/** Troca o nome e a cor de um local salvo (as coordenadas não mudam). */
	public record PlaceEdit(long id, String name, int color) implements Payload {
		public static final PacketType<PlaceEdit> TYPE = PacketType.of("place_edit", PlaceEdit::new);

		private PlaceEdit(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readUtf(MAX_NAME), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeUtf(name, MAX_NAME);
			buf.writeVarInt(color);
		}

		@Override
		public PacketType<PlaceEdit> type() {
			return TYPE;
		}
	}

	/**
	 * Encaminha um local salvo do jogador para uma conversa (direta ou grupo), como o encaminhar do WhatsApp. O
	 * servidor lê o local da conta de quem pede, pelo id: ninguém encaminha o local de outra pessoa.
	 */
	public record SendPlace(long conversation, long place) implements Payload {
		public static final PacketType<SendPlace> TYPE = PacketType.of("send_place", SendPlace::new);

		private SendPlace(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
			buf.writeVarLong(place);
		}

		@Override
		public PacketType<SendPlace> type() {
			return TYPE;
		}
	}

	public record PlaceDelete(long id) implements Payload {
		public static final PacketType<PlaceDelete> TYPE = PacketType.of("place_delete", PlaceDelete::new);

		private PlaceDelete(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<PlaceDelete> type() {
			return TYPE;
		}
	}

	/** Liga para um número. O servidor decide tudo (quem pode, se está ocupado, bloqueio); o cliente só pede. */
	public record CallStart(String number) implements Payload {
		public static final PacketType<CallStart> TYPE = PacketType.of("call_start", CallStart::new);

		private CallStart(PhoneBuf buf) {
			this(buf.readUtf(MAX_NUMBER));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(number, MAX_NUMBER);
		}

		@Override
		public PacketType<CallStart> type() {
			return TYPE;
		}
	}

	/** Chama um grupo de mensagens (ou entra na chamada que já existe nele). O servidor confere que o jogador é membro. */
	public record CallGroup(long conversation) implements Payload {
		public static final PacketType<CallGroup> TYPE = PacketType.of("call_group", CallGroup::new);

		private CallGroup(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(conversation);
		}

		@Override
		public PacketType<CallGroup> type() {
			return TYPE;
		}
	}

	/** Atende a ligação que está tocando. */
	public record CallAnswer(long id) implements Payload {
		public static final PacketType<CallAnswer> TYPE = PacketType.of("call_answer", CallAnswer::new);

		private CallAnswer(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<CallAnswer> type() {
			return TYPE;
		}
	}

	/** Recusa (tocando), cancela (chamando) ou desliga (em ligação): o servidor decide qual dos três pelo estado. */
	public record CallDecline(long id) implements Payload {
		public static final PacketType<CallDecline> TYPE = PacketType.of("call_decline", CallDecline::new);

		private CallDecline(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<CallDecline> type() {
			return TYPE;
		}
	}

	/** Liga ou desliga o mudo. Quem para de repassar a voz é o servidor. */
	public record CallMute(long id, boolean on) implements Payload {
		public static final PacketType<CallMute> TYPE = PacketType.of("call_mute", CallMute::new);

		private CallMute(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
			buf.writeBoolean(on);
		}

		@Override
		public PacketType<CallMute> type() {
			return TYPE;
		}
	}

	private static ConversationEntry readConversation(PhoneBuf b) {
		return new ConversationEntry(
			b.readVarLong(), b.readBoolean(), b.readUtf(MAX_NAME), readList(b, m -> m.readUtf(MAX_NUMBER)),
			b.readVarInt(), b.readVarInt(), b.readUtf(MAX_NUMBER), b.readUtf(MAX_TEXT), b.readLong(), b.readBoolean(), b.readBoolean()
		);
	}

	private static void writeConversation(PhoneBuf b, ConversationEntry c) {
		b.writeVarLong(c.id());
		b.writeBoolean(c.group());
		b.writeUtf(c.title(), MAX_NAME);
		writeList(b, c.members(), (m, n) -> m.writeUtf(n, MAX_NUMBER));
		b.writeVarInt(c.unread());
		b.writeVarInt(c.lastSeq());
		b.writeUtf(c.lastSender(), MAX_NUMBER);
		b.writeUtf(c.lastText(), MAX_TEXT);
		b.writeLong(c.lastTime());
		b.writeBoolean(c.lastPhoto());
		b.writeBoolean(c.lastLocation());
	}

	private static Loc readLoc(PhoneBuf buf) {
		if (!buf.readBoolean()) {
			return null;
		}
		return new Loc(buf.readBoolean(), buf.readUtf(MAX_DIMENSION), buf.readInt(), buf.readBoolean(), buf.readInt(), buf.readInt(), buf.readUtf(MAX_NAME));
	}

	private static void writeLoc(PhoneBuf buf, Loc loc) {
		buf.writeBoolean(loc != null);
		if (loc != null) {
			buf.writeBoolean(loc.stamped());
			buf.writeUtf(loc.dimension(), MAX_DIMENSION);
			buf.writeInt(loc.x());
			buf.writeBoolean(loc.hasY());
			buf.writeInt(loc.y());
			buf.writeInt(loc.z());
			buf.writeUtf(loc.label(), MAX_NAME);
		}
	}

	// ------------------------------------------------------------------ registro

	// ------------------------------------------------------------------ celular visto de fora (1.3)

	/** O que o celular deste jogador está fazendo agora (pose e tema), para os outros verem. Só muda quando algo muda. */
	public record PhonePose(int pose, int theme) implements Payload {
		public static final PacketType<PhonePose> TYPE = PacketType.of("phone_pose", PhonePose::new);

		private PhonePose(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readVarInt());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(pose);
			buf.writeVarInt(theme);
		}

		@Override
		public PacketType<PhonePose> type() {
			return TYPE;
		}
	}

	/** A pose do celular de um jogador (UUID em duas metades); {@link #POSE_NONE} = guardou ou saiu do alcance. */
	public record PoseEntry(long uuidHigh, long uuidLow, int pose, int theme) {
	}

	/** Mudanças nas poses dos jogadores perto de quem recebe (só as que mudaram desde o último envio). */
	public record PhonePoses(List<PoseEntry> entries) implements Payload {
		public static final PacketType<PhonePoses> TYPE = PacketType.of("phone_poses", PhonePoses::new);

		private PhonePoses(PhoneBuf buf) {
			this(readList(buf, b -> new PoseEntry(b.readLong(), b.readLong(), b.readVarInt(), b.readVarInt())));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, entries, (b, e) -> {
				b.writeLong(e.uuidHigh());
				b.writeLong(e.uuidLow());
				b.writeVarInt(e.pose());
				b.writeVarInt(e.theme());
			});
		}

		@Override
		public PacketType<PhonePoses> type() {
			return TYPE;
		}
	}

	// ------------------------------------------------------------------ scroll.in (1.4: fotos públicas)

	/** Tamanho máximo da legenda de uma publicação e de um comentário. */
	public static final int MAX_CAPTION = 256, MAX_COMMENT = 150;
	/** Tamanho máximo do id opaco de um autor no feed (nunca o número nem o UUID). */
	public static final int MAX_AUTHOR = 16;
	/** Publicações por página do feed. */
	public static final int FEED_PAGE = 10;
	/** Comentários por publicação que o servidor guarda (e manda). */
	public static final int MAX_COMMENTS = 200;
	/** Tipos de {@link FeedActivity}. */
	public static final int FEED_LIKE = 0, FEED_COMMENT = 1, FEED_REPORT = 2;

	/** Uma publicação vista por quem pede: {@code liked} e {@code mine} são desse ponto de vista. */
	public record FeedPostEntry(long id, String author, String name, long time, String caption, long photo, int likes, boolean liked, int comments,
		boolean mine) {
	}

	/** Um comentário visto por quem pede. */
	public record FeedCommentEntry(long id, String author, String name, long time, String text, boolean mine) {
	}

	private static FeedPostEntry readFeedPost(PhoneBuf b) {
		return new FeedPostEntry(b.readVarLong(), b.readUtf(MAX_AUTHOR), b.readUtf(MAX_NAME), b.readLong(), b.readUtf(MAX_CAPTION), b.readVarLong(),
			b.readVarInt(), b.readBoolean(), b.readVarInt(), b.readBoolean());
	}

	private static void writeFeedPost(PhoneBuf b, FeedPostEntry p) {
		b.writeVarLong(p.id());
		b.writeUtf(p.author(), MAX_AUTHOR);
		b.writeUtf(p.name(), MAX_NAME);
		b.writeLong(p.time());
		b.writeUtf(p.caption(), MAX_CAPTION);
		b.writeVarLong(p.photo());
		b.writeVarInt(p.likes());
		b.writeBoolean(p.liked());
		b.writeVarInt(p.comments());
		b.writeBoolean(p.mine());
	}

	/** O scroll.in existe neste servidor? (sem este pacote, servidor antigo, o celular nem mostra o ícone). Também os limites, quem é o próprio jogador no feed e se ele é administrador (vê o id das publicações). */
	public record FeedInfo(boolean available, boolean cameraOnly, int maxCaption, int maxComment, String me, String meName, boolean admin) implements Payload {
		public static final PacketType<FeedInfo> TYPE = PacketType.of("feed_info", FeedInfo::new);

		private FeedInfo(PhoneBuf buf) {
			this(buf.readBoolean(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_AUTHOR), buf.readUtf(MAX_NAME), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeBoolean(available);
			buf.writeBoolean(cameraOnly);
			buf.writeVarInt(maxCaption);
			buf.writeVarInt(maxComment);
			buf.writeUtf(me, MAX_AUTHOR);
			buf.writeUtf(meName, MAX_NAME);
			buf.writeBoolean(admin);
		}

		@Override
		public PacketType<FeedInfo> type() {
			return TYPE;
		}
	}

	/** Uma página do feed (autor vazio = de todos) que responde a um {@link FeedGet}. */
	public record FeedPage(String author, long before, List<FeedPostEntry> posts, boolean more) implements Payload {
		public static final PacketType<FeedPage> TYPE = PacketType.of("feed_page", FeedPage::new);

		public FeedPage {
			posts = List.copyOf(posts);
		}

		private FeedPage(PhoneBuf buf) {
			this(buf.readUtf(MAX_AUTHOR), buf.readVarLong(), readList(buf, PhonePayloads::readFeedPost), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(author, MAX_AUTHOR);
			buf.writeVarLong(before);
			writeList(buf, posts, PhonePayloads::writeFeedPost);
			buf.writeBoolean(more);
		}

		@Override
		public PacketType<FeedPage> type() {
			return TYPE;
		}
	}

	/** Uma publicação mudou (curtida, comentário) do ponto de vista de quem recebe. */
	public record FeedPost(FeedPostEntry entry) implements Payload {
		public static final PacketType<FeedPost> TYPE = PacketType.of("feed_post", FeedPost::new);

		private FeedPost(PhoneBuf buf) {
			this(readFeedPost(buf));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeFeedPost(buf, entry);
		}

		@Override
		public PacketType<FeedPost> type() {
			return TYPE;
		}
	}

	/** Uma publicação saiu (apagada, removida por um administrador, a foto saiu). */
	public record FeedGone(long id) implements Payload {
		public static final PacketType<FeedGone> TYPE = PacketType.of("feed_gone", FeedGone::new);

		private FeedGone(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(id);
		}

		@Override
		public PacketType<FeedGone> type() {
			return TYPE;
		}
	}

	/** Os comentários de uma publicação que quem pede pode ver, do mais antigo ao mais novo. */
	public record FeedComments(long post, List<FeedCommentEntry> comments) implements Payload {
		public static final PacketType<FeedComments> TYPE = PacketType.of("feed_comments", FeedComments::new);

		public FeedComments {
			comments = List.copyOf(comments);
		}

		private FeedComments(PhoneBuf buf) {
			this(buf.readVarLong(), readList(buf, b -> new FeedCommentEntry(b.readVarLong(), b.readUtf(MAX_AUTHOR), b.readUtf(MAX_NAME), b.readLong(), b.readUtf(MAX_COMMENT), b.readBoolean())));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
			writeList(buf, comments, (b, c) -> {
				b.writeVarLong(c.id());
				b.writeUtf(c.author(), MAX_AUTHOR);
				b.writeUtf(c.name(), MAX_NAME);
				b.writeLong(c.time());
				b.writeUtf(c.text(), MAX_COMMENT);
				b.writeBoolean(c.mine());
			});
		}

		@Override
		public PacketType<FeedComments> type() {
			return TYPE;
		}
	}

	/** As fotos da galeria do jogador que podem ser publicadas. */
	public record FeedEligible(List<Long> ids) implements Payload {
		public static final PacketType<FeedEligible> TYPE = PacketType.of("feed_eligible", FeedEligible::new);

		public FeedEligible {
			ids = List.copyOf(ids);
		}

		private FeedEligible(PhoneBuf buf) {
			this(readList(buf, PhoneBuf::readVarLong));
		}

		@Override
		public void write(PhoneBuf buf) {
			writeList(buf, ids, PhoneBuf::writeVarLong);
		}

		@Override
		public PacketType<FeedEligible> type() {
			return TYPE;
		}
	}

	/** Alguém curtiu ou comentou uma publicação do jogador (vira aviso no topo). */
	public record FeedActivity(int kind, String name, long post) implements Payload {
		public static final PacketType<FeedActivity> TYPE = PacketType.of("feed_activity", FeedActivity::new);

		private FeedActivity(PhoneBuf buf) {
			this(buf.readVarInt(), buf.readUtf(MAX_NAME), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarInt(kind);
			buf.writeUtf(name, MAX_NAME);
			buf.writeVarLong(post);
		}

		@Override
		public PacketType<FeedActivity> type() {
			return TYPE;
		}
	}

	/** Pede uma página: {@code author} vazio = todos; {@code before} = a última publicação que o celular tem (0 = do começo). */
	public record FeedGet(String author, long before) implements Payload {
		public static final PacketType<FeedGet> TYPE = PacketType.of("feed_get", FeedGet::new);

		private FeedGet(PhoneBuf buf) {
			this(buf.readUtf(MAX_AUTHOR), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeUtf(author, MAX_AUTHOR);
			buf.writeVarLong(before);
		}

		@Override
		public PacketType<FeedGet> type() {
			return TYPE;
		}
	}

	/** Pede as fotos da galeria que podem ser publicadas. */
	public record FeedEligibleGet() implements Payload {
		public static final PacketType<FeedEligibleGet> TYPE = PacketType.of("feed_eligible_get", FeedEligibleGet::new);

		private FeedEligibleGet(PhoneBuf buf) {
			this();
		}

		@Override
		public void write(PhoneBuf buf) {
		}

		@Override
		public PacketType<FeedEligibleGet> type() {
			return TYPE;
		}
	}

	/** Publica uma foto da própria galeria com uma legenda. */
	public record FeedPublish(long photo, String caption) implements Payload {
		public static final PacketType<FeedPublish> TYPE = PacketType.of("feed_publish", FeedPublish::new);

		private FeedPublish(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readUtf(MAX_CAPTION));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(photo);
			buf.writeUtf(caption, MAX_CAPTION);
		}

		@Override
		public PacketType<FeedPublish> type() {
			return TYPE;
		}
	}

	/** Curte ou desfaz: leva o estado, não um alternar (repetir não muda nada). */
	public record FeedLike(long post, boolean on) implements Payload {
		public static final PacketType<FeedLike> TYPE = PacketType.of("feed_like", FeedLike::new);

		private FeedLike(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readBoolean());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
			buf.writeBoolean(on);
		}

		@Override
		public PacketType<FeedLike> type() {
			return TYPE;
		}
	}

	/** Pede os comentários de uma publicação. */
	public record FeedCommentsGet(long post) implements Payload {
		public static final PacketType<FeedCommentsGet> TYPE = PacketType.of("feed_comments_get", FeedCommentsGet::new);

		private FeedCommentsGet(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
		}

		@Override
		public PacketType<FeedCommentsGet> type() {
			return TYPE;
		}
	}

	/** Comenta. */
	public record FeedComment(long post, String text) implements Payload {
		public static final PacketType<FeedComment> TYPE = PacketType.of("feed_comment", FeedComment::new);

		private FeedComment(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readUtf(MAX_COMMENT));
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
			buf.writeUtf(text, MAX_COMMENT);
		}

		@Override
		public PacketType<FeedComment> type() {
			return TYPE;
		}
	}

	/** Apaga um comentário próprio. */
	public record FeedCommentDelete(long post, long comment) implements Payload {
		public static final PacketType<FeedCommentDelete> TYPE = PacketType.of("feed_comment_delete", FeedCommentDelete::new);

		private FeedCommentDelete(PhoneBuf buf) {
			this(buf.readVarLong(), buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
			buf.writeVarLong(comment);
		}

		@Override
		public PacketType<FeedCommentDelete> type() {
			return TYPE;
		}
	}

	/** Denuncia uma publicação aos administradores (ela some do feed de quem denunciou). */
	public record FeedReport(long post) implements Payload {
		public static final PacketType<FeedReport> TYPE = PacketType.of("feed_report", FeedReport::new);

		private FeedReport(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
		}

		@Override
		public PacketType<FeedReport> type() {
			return TYPE;
		}
	}

	/** Apaga uma publicação própria (a foto fica na galeria). */
	public record FeedDelete(long post) implements Payload {
		public static final PacketType<FeedDelete> TYPE = PacketType.of("feed_delete", FeedDelete::new);

		private FeedDelete(PhoneBuf buf) {
			this(buf.readVarLong());
		}

		@Override
		public void write(PhoneBuf buf) {
			buf.writeVarLong(post);
		}

		@Override
		public PacketType<FeedDelete> type() {
			return TYPE;
		}
	}

	/** Pacotes do servidor para o celular, na ordem em que a plataforma os registra. */
	public static final List<PacketType<?>> TO_CLIENT = List.of(
		Sync.TYPE,
		Messages.TYPE,
		ConvUpdate.TYPE,
		Blocked.TYPE,
		Notice.TYPE,
		Gallery.TYPE,
		PhotoData.TYPE,
		PhotoUnavailable.TYPE,
		UploadResult.TYPE,
		MapInfo.TYPE,
		TileData.TYPE,
		TileUnavailable.TYPE,
		Live.TYPE,
		ShareStatus.TYPE,
		Places.TYPE,
		CallInfo.TYPE,
		CallUpdate.TYPE,
		CallLog.TYPE,
		PhonePoses.TYPE,
		PhotoInfo.TYPE,
		FeedInfo.TYPE,
		FeedPage.TYPE,
		FeedPost.TYPE,
		FeedGone.TYPE,
		FeedComments.TYPE,
		FeedEligible.TYPE,
		FeedActivity.TYPE
	);

	/** Pacotes do celular para o servidor, na ordem em que a plataforma os registra. */
	public static final List<PacketType<?>> TO_SERVER = List.of(
		Send.TYPE,
		OpenDirect.TYPE,
		Open.TYPE,
		Read.TYPE,
		Contact.TYPE,
		Block.TYPE,
		LeaveGroup.TYPE,
		CreateGroup.TYPE,
		UploadBegin.TYPE,
		UploadChunk.TYPE,
		UploadEnd.TYPE,
		PhotoGet.TYPE,
		PhotoDelete.TYPE,
		SendPhoto.TYPE,
		TileGet.TYPE,
		SendLocation.TYPE,
		SendPin.TYPE,
		ShareStart.TYPE,
		ShareStop.TYPE,
		Watch.TYPE,
		PlaceSave.TYPE,
		PlaceDelete.TYPE,
		PlaceEdit.TYPE,
		SendPlace.TYPE,
		CallStart.TYPE,
		CallGroup.TYPE,
		CallAnswer.TYPE,
		CallDecline.TYPE,
		CallMute.TYPE,
		PhonePose.TYPE,
		PhotoDeleteMany.TYPE,
		PhotoInfoGet.TYPE,
		FeedGet.TYPE,
		FeedEligibleGet.TYPE,
		FeedPublish.TYPE,
		FeedLike.TYPE,
		FeedCommentsGet.TYPE,
		FeedComment.TYPE,
		FeedCommentDelete.TYPE,
		FeedDelete.TYPE,
		FeedReport.TYPE
	);

	/** Um pacote do celular: sabe o próprio tipo e se escreve no formato do jogo. */
	public interface Payload {
		PacketType<?> type();

		void write(PhoneBuf buf);
	}

	private interface Reader<T> {
		T read(PhoneBuf buf);
	}

	private interface Writer<T> {
		void write(PhoneBuf buf, T value);
	}

	private static <T> List<T> readList(PhoneBuf buf, Reader<T> reader) {
		int size = buf.readVarInt();
		if (size < 0 || size > MAX_LIST) {
			throw new IllegalArgumentException("List too large in a HandyPhone packet: " + size);
		}
		List<T> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(reader.read(buf));
		}
		return list;
	}

	private static <T> void writeList(PhoneBuf buf, List<T> list, Writer<T> writer) {
		buf.writeVarInt(list.size());
		for (T value : list) {
			writer.write(buf, value);
		}
	}
}
