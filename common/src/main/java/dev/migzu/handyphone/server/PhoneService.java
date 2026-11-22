package dev.migzu.handyphone.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.net.PacketType;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.net.PhonePayloads.ContactEntry;
import dev.migzu.handyphone.net.PhonePayloads.ConversationEntry;
import dev.migzu.handyphone.net.PhonePayloads.MessageEntry;
import dev.migzu.handyphone.net.PhonePayloads.PhotoEntry;
import dev.migzu.handyphone.photo.PhotoCodec;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * O lado do servidor do celular: dono dos números, contatos, conversas e mensagens.
 * Nada vindo do cliente é aceito sem validação, e quem envia é sempre identificado
 * pela conexão, nunca por um campo do pacote.
 *
 * <p>Tudo fica em {@code <mundo>/handyphone/}: um arquivo de contas e um arquivo por
 * conversa. Só o que mudou é regravado, junto com o salvamento normal do mundo.
 * As fotos ficam em {@code photos/} (ver {@link PhotoStore}).
 *
 * <p><b>Fotos.</b> O envio é em partes numeradas: {@code UploadBegin} (tamanho, formato),
 * {@code UploadChunk}s e {@code UploadEnd}. Há um envio por jogador de cada vez; o que
 * fica parado mais que {@link #UPLOAD_TIMEOUT_MS} é descartado. No fim, a imagem é
 * validada, decodificada e <b>recodificada pelo servidor</b> em uma thread própria (fila
 * pequena), e só então registrada de volta na thread do servidor, onde a cota é conferida
 * de novo. Apagar uma foto da galeria a apaga em todo lugar: o balão da conversa passa a
 * mostrar "foto apagada" (assim o espaço sempre volta a quem apagou e quem envia não
 * consegue prender disco do servidor mandando fotos para conversas).
 */
public final class PhoneService {
	/** Mensagens guardadas por conversa; as mais antigas são descartadas. */
	private static final int MAX_STORED_MESSAGES = 300;
	/** Mensagens enviadas ao abrir uma conversa. */
	private static final int HISTORY_SIZE = 100;
	private static final int MAX_CONTACTS = 100;
	/**
	 * Conversas que um jogador vê na lista; vale para as ações DELE (abrir conversa nova, criar grupo). O que os outros
	 * fazem com ele não é recusado por causa deste teto (isso diria a quem tenta que ele está cheio).
	 */
	static final int MAX_CONVERSATIONS = 200;
	/** A lista de conversas mandada ao celular nunca passa disto (o pacote aceita 512); ficam as mais recentes. */
	static final int SYNC_MAX_CONVERSATIONS = 400;
	/** Grupos que um jogador cria por minuto e por hora. */
	static final int GROUPS_PER_MINUTE = 3, GROUPS_PER_HOUR = 20;
	/** Números NOVOS que um jogador consulta por hora (contatos e conversas existentes não contam): impede varrer os números. */
	static final int NEW_NUMBERS_PER_HOUR = 20;
	private static final long NEW_NUMBERS_WINDOW_MS = 3_600_000L;
	/** Anti-spam: no máximo tantas mensagens dentro da janela de tempo. */
	private static final int RATE_LIMIT = 8;
	private static final long RATE_WINDOW_MS = 5000;
	/** Tentativas de envio de foto (contadas no início, dando certo ou não) por janela. */
	private static final int UPLOAD_RATE = 20;
	private static final long UPLOAD_RATE_WINDOW_MS = 60_000;
	/** Um envio de foto sem nenhum pacote por este tempo é considerado abandonado. */
	static final long UPLOAD_TIMEOUT_MS = 20_000;
	/** Pacotes de parte de foto sem envio ativo tolerados antes de desconectar o jogador (só um cliente modificado faz isso). */
	private static final int MAX_STRAYS = 40;
	/** Orçamento de download de fotos por jogador: rajada e recarga por segundo, em bytes. */
	private static final double DOWNLOAD_BURST = 3 * 1024 * 1024;
	private static final double DOWNLOAD_REFILL_PER_SECOND = 512 * 1024;
	/** Espaço mínimo livre no disco para aceitar uma foto nova. */
	private static final long MIN_FREE_BYTES = 256L * 1024 * 1024;
	/** Resposta interna de um pacote de parte que não pertence a nenhum envio ativo. */
	private static final Refusal STRAY = new Refusal("stray");
	/**
	 * Depois de recusar ou cancelar um envio, o cliente honesto ainda tem partes (e o fim) a caminho, pois
	 * não espera a resposta para começar a mandar. Esta é a folga de pacotes soltos que isso justifica:
	 * as partes de uma foto cheia mais o fim.
	 */
	private static final int STRAY_ALLOWANCE = PhotoCodec.MAX_PHOTO_BYTES / PhonePayloads.MAX_CHUNK + 3;

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	// ------------------------------------------------------------------ modelo (também é o formato dos arquivos)

	static final class Account {
		String number;
		Map<String, String> contacts = new LinkedHashMap<>();
		/** Até qual mensagem o jogador já leu, por conversa. */
		Map<Long, Integer> read = new HashMap<>();
		/** Locais salvos (anotações particulares do jogador). Contas antigas, sem o campo, ficam sem locais. */
		List<Place> places = new ArrayList<>();
		long nextPlaceId = 1;
		/** Contas que este jogador bloqueou. Contas antigas, sem o campo, ficam sem bloqueios. */
		List<UUID> blocked = new ArrayList<>();
		/** Silenciado pelo administrador até este instante (ms); 0 = não; {@link Long#MAX_VALUE} = sem prazo. */
		long mutedUntil;
		/** Histórico de chamadas (as mais recentes primeiro). Contas antigas, sem o campo, ficam sem histórico. */
		List<CallRecord> calls = new ArrayList<>();
	}

	/** Uma chamada no histórico de uma conta (o que {@link PhonePayloads.CallLogEntry} mostra no celular). */
	static final class CallRecord {
		long time;
		long conversation;
		String number = "";
		/** Chamada em grupo (o celular mostra o nome do grupo; sem o grupo, não oferece chamar de volta). */
		boolean group;
		boolean outgoing;
		boolean answered;
		int seconds;
	}

	/** Um local salvo. As coordenadas só voltam ao próprio dono. */
	static final class Place {
		long id;
		String name;
		/** Id da dimensão, por exemplo "minecraft:overworld". */
		String dim;
		int x;
		int y;
		int z;
		boolean hasY;
		/** Índice da cor (0 a {@link PhonePayloads#PLACE_COLORS} - 1); contas antigas, sem o campo, ficam com a primeira. */
		int color;
	}

	/** Um ponto do mundo anexado a uma mensagem. */
	static final class StoredLoc {
		/** true = a posição de quem enviou, carimbada pelo servidor; false = ponto marcado no mapa. */
		boolean stamped;
		String dim;
		int x;
		int y;
		int z;
		boolean hasY;
		/** Nome do local, quando é um local salvo encaminhado; null nos outros casos (e em arquivos antigos). */
		String label;
	}

	private static final class AccountsFile {
		long nextConversationId = 1;
		/** Próximo id de foto; ao carregar, nunca fica abaixo do maior id visto em disco (queda no meio do salvamento). */
		long nextPhotoId = 1;
		Map<UUID, Account> accounts = new LinkedHashMap<>();
	}

	private static final class StoredMessage {
		int seq;
		UUID sender;
		long time;
		String text;
		/** Foto anexada (id), ou 0. Arquivos antigos, sem o campo, valem 0. */
		long photo;
		/** Ponto do mundo anexado, ou null. Arquivos antigos, sem o campo, valem null. */
		StoredLoc loc;
		/** Mensagem de sistema (remetente null), como "saiu do grupo". Arquivos antigos valem false. */
		boolean system;
		/** Quem bloqueou o remetente na hora do envio e por isso nunca vê esta mensagem; null = ninguém. */
		List<UUID> hiddenFrom;
	}

	/** Um envio de foto em andamento. */
	private static final class Upload {
		/** A "geração" da conta quando o envio começou: um {@code wipe} no meio faz a foto ser descartada ao terminar. */
		int generation;
		int token;
		int total;
		int format;
		int source;
		byte[] buffer;
		int received;
		int nextIndex;
		long lastActivity;
		/** Já foi para a thread de processamento; o espaço do jogador só libera quando o resultado voltar. */
		boolean processing;
	}

	/** Orçamento de bytes de download (balde de fichas). */
	private static final class Bucket {
		double tokens = DOWNLOAD_BURST;
		long last = System.nanoTime();

		boolean take(double cost) {
			long now = System.nanoTime();
			tokens = Math.min(DOWNLOAD_BURST, tokens + (now - last) / 1e9 * DOWNLOAD_REFILL_PER_SECOND);
			last = now;
			if (tokens < cost) {
				return false;
			}
			tokens -= cost;
			return true;
		}
	}

	static final class Conversation {
		long id;
		boolean group;
		String title = "";
		List<UUID> members = new ArrayList<>();
		int lastSeq;
		List<StoredMessage> messages = new ArrayList<>();
		/**
		 * Quem abriu a conversa de propósito (e por isso a vê na lista mesmo vazia). Conversas novas começam só com quem
		 * as criou; null (arquivos antigos) = todos os membros a veem, como sempre foi.
		 */
		List<UUID> openers;
		/** Quem criou o grupo (null em conversas individuais e em grupos de arquivos antigos). */
		UUID creator;
		/** Quem saiu do grupo: não volta sem querer (não existe "adicionar membro"; a lista fica para quando existir). */
		List<UUID> left = new ArrayList<>();
	}

	// ------------------------------------------------------------------ estado

	private static PhoneService instance;

	private final PhonePlatform server;
	private final Path dir;
	private AccountsFile data = new AccountsFile();
	private final Map<String, UUID> byNumber = new HashMap<>();
	private final Map<Long, Conversation> conversations = new HashMap<>();
	private final Map<UUID, Set<Long>> conversationsOf = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> recentSends = new HashMap<>();
	private final SecureRandom random = new SecureRandom();
	private boolean accountsDirty;
	private final Set<Long> dirtyConversations = new HashSet<>();

	private PhotoStore photos;
	private PhoneConfig config = new PhoneConfig();
	/** Localização: mapa, mensagens com ponto, compartilhamento ao vivo e locais salvos. */
	private final LocationService location;
	/** Ligações de voz (precisam do Simple Voice Chat). */
	private final CallService calls;
	private boolean photosAvailable = true;
	private final Map<UUID, Upload> uploads = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> recentUploads = new HashMap<>();
	private final Map<UUID, Bucket> downloadBuckets = new HashMap<>();
	private final Map<UUID, Integer> strays = new HashMap<>();
	/** Pacotes soltos ainda perdoados por jogador (ver {@link #STRAY_ALLOWANCE}). */
	private final Map<UUID, Integer> strayAllowance = new HashMap<>();
	/** Foto -> conversas em que ela foi enviada (e quantas vezes); decide quem pode baixá-la. */
	private final Map<Long, Map<Long, Integer>> photoRefs = new HashMap<>();
	/** Decodifica e grava as fotos recebidas fora da thread do servidor; a fila curta evita que um jogador a encha. */
	private final ThreadPoolExecutor photoWorker = new ThreadPoolExecutor(
		1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), r -> {
			Thread thread = new Thread(r, "HandyPhone-Photos");
			thread.setDaemon(true);
			return thread;
		}
	);
	private int tickCounter;
	/** Orçamento de pacotes por jogador (todos os pacotes do celular passam por ele). */
	private PacketBudget budget = new PacketBudget();
	/** O relógio das janelas de frequência e do silenciamento; os testes o trocam. */
	private LongSupplier clock = System::currentTimeMillis;
	/** Números novos consultados na última hora, por jogador (número -> instante). */
	private final Map<UUID, Map<String, Long>> newNumbers = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> groupCreates = new HashMap<>();
	/** Muda a cada {@code wipe} de uma conta: envios de foto em andamento da geração anterior são descartados. */
	private final Map<UUID, Integer> wipeGeneration = new HashMap<>();
	/** Conversas apagadas cujo arquivo sai do disco no próximo salvamento. */
	private final Set<Long> deletedConversations = new HashSet<>();
	/**
	 * Os dados do disco não puderam ser lidos (ver {@link #load}): nada é gravado e nenhum pacote é tratado, para
	 * nunca sobrescrever o que o dono ainda pode recuperar.
	 */
	private boolean locked;

	private PhoneService(PhonePlatform server) {
		this.server = server;
		Path worldRoot = server.worldRoot();
		LegacyMigration.migrateWorldData(worldRoot);
		this.dir = worldRoot.resolve("handyphone");
		this.photos = new PhotoStore(dir);
		this.location = new LocationService(this);
		this.calls = new CallService(this);
	}

	// ------------------------------------------------------------------ eventos da plataforma
	// A plataforma (Fabric hoje) chama estes métodos nos eventos do servidor, sempre na thread do servidor, menos
	// receive(), que vem da thread de rede.

	/** O servidor terminou de subir (o {@code SERVER_STARTED} do Fabric). */
	public static void onServerStarted(PhonePlatform platform) {
		instance = new PhoneService(platform);
		instance.config = PhoneConfig.load(platform.configDir());
		instance.photosAvailable = imageIoAvailable();
		instance.load();
	}

	/** Fim de cada tick do servidor. */
	public static void onServerTick() {
		PhoneService service = instance;
		if (service != null) {
			service.tick();
		}
	}

	/** Depois de o mundo salvar; {@code flush} = o {@code /save-all flush}, que promete tudo no disco ao terminar. */
	public static void onAfterSave(boolean flush) {
		if (instance != null) {
			// O autosave e o /save-all não esperam o disco: o texto é preparado aqui e a thread de gravação o leva ao arquivo.
			// Só o /save-all flush, que promete tudo no disco ao terminar, grava de forma síncrona.
			instance.save(!flush);
		}
	}

	/** O servidor está parando. */
	public static void onServerStopping() {
		if (instance != null) {
			instance.location.shutdown();
			instance.calls.shutdown();
			// Deixa terminar a foto que estiver sendo processada antes do salvamento final.
			instance.photoWorker.shutdown();
			try {
				instance.photoWorker.awaitTermination(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			instance.save();
			instance.diskWorker.shutdown();
			instance = null;
		}
	}

	/** Um jogador entrou (a conexão de jogo está pronta para receber pacotes). */
	public static void onJoin(PhonePlayer player) {
		PhoneService service = instance;
		if (service != null && !service.locked) {
			service.sync(player);
			service.sendBlocked(player);
			service.sendGallery(player);
			service.location.onJoin(player);
			service.calls.onJoin(player);
			service.sendCallLog(player);
		}
	}

	/** Um jogador saiu. */
	public static void onDisconnect(UUID uuid) {
		PhoneService service = instance;
		if (service != null) {
			Upload upload = service.uploads.get(uuid);
			if (upload != null && !upload.processing) {
				service.uploads.remove(uuid);
			}
			service.strays.remove(uuid);
			service.strayAllowance.remove(uuid);
			service.downloadBuckets.remove(uuid);
			service.location.onDisconnect(uuid);
			service.calls.onDisconnect(uuid);
		}
	}

	/** O jogador renasceu ou mudou de dimensão (a voz das ligações pode depender da dimensão). */
	public static void onWorldChange(UUID uuid) {
		PhoneService service = instance;
		if (service != null) {
			service.calls.onWorldChange(uuid);
		}
	}

	/**
	 * Um pacote do celular chegou pela rede (na thread de rede): vai para a fila da thread do servidor e de lá para
	 * {@link #handlePacket}. A plataforma registra um receptor para cada tipo de {@link PhonePayloads#TO_SERVER}.
	 */
	public static void receive(PhonePlayer player, PhonePayloads.Payload packet) {
		PhoneService service = instance;
		if (service != null) {
			service.server.execute(() -> handlePacket(player, packet));
		}
	}

	/** O trabalho do HandyPhone em cada tick do servidor. */
	private void tick() {
		location.tick();
		calls.tick();
		if (++tickCounter % 100 == 0) {
			sweepUploads(System.currentTimeMillis());
		}
		if (tickCounter % 1200 == 0) {
			pruneRateState();
		}
	}

	/** O servidor precisa do codificador de JPEG do Java (módulo java.desktop); em um JRE mínimo sem ele, as fotos ficam desligadas. */
	private static boolean imageIoAvailable() {
		try {
			if (javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").hasNext() && javax.imageio.ImageIO.getImageReadersByFormatName("jpeg").hasNext()) {
				return true;
			}
		} catch (Throwable t) {
			HandyPhoneCore.LOGGER.warn("ImageIO unavailable", t);
		}
		HandyPhoneCore.LOGGER.warn("HandyPhone: this Java has no java.desktop module (ImageIO); photos are turned off on this server.");
		return false;
	}

	private interface Action {
		void run(PhoneService service);
	}

	private static void run(Action action) {
		PhoneService service = instance;
		if (service != null) {
			service.server.execute(() -> action.run(service));
		}
	}

	// ------------------------------------------------------------------ contas e números

	/**
	 * Número de telefone de um jogador, criando a conta se ainda não existir.
	 * Deve ser chamado na thread do servidor. Retorna null se o servidor não está no ar.
	 */
	public static String numberFor(UUID player) {
		PhoneService service = instance;
		// Com os dados travados (arquivo ilegível) nenhuma conta nova nasce: o número poderia colidir com o de uma conta que existe no arquivo.
		if (service == null || service.locked) {
			return null;
		}
		Account account = service.data.accounts.get(player);
		if (account == null) {
			account = new Account();
			account.number = service.newNumber();
			service.data.accounts.put(player, account);
			service.byNumber.put(account.number, player);
			service.accountsDirty = true;
		}
		return account.number;
	}

	/**
	 * Envia uma mensagem individual em nome de uma conta, sem passar pela rede.
	 * Serve para testes e para futuros comandos de administração; deve ser chamado
	 * na thread do servidor. Retorna false se o número de destino não existe.
	 */
	public static boolean deliverDirect(UUID sender, String toNumber, String text) {
		PhoneService service = instance;
		String clean = clean(text, PhonePayloads.MAX_TEXT);
		UUID target = service != null && !service.locked ? service.byNumber.get(digits(toNumber)) : null;
		if (target == null || target.equals(sender) || clean.isEmpty()) {
			return false;
		}
		numberFor(sender);
		return service.post(service.direct(sender, target), sender, clean, 0L);
	}

	/**
	 * Cria um grupo e envia nele uma primeira mensagem em nome do dono, sem passar
	 * pela rede (testes e futuros comandos). Retorna false se nenhum número existe.
	 */
	public static boolean deliverGroup(UUID owner, String title, List<String> numbers, String text) {
		PhoneService service = instance;
		if (service == null || service.locked) {
			return false;
		}
		numberFor(owner);
		Set<UUID> members = new LinkedHashSet<>();
		members.add(owner);
		for (String number : numbers) {
			UUID target = service.byNumber.get(digits(number));
			if (target != null && members.size() < PhonePayloads.MAX_GROUP_MEMBERS) {
				members.add(target);
			}
		}
		if (members.size() < 2) {
			return false;
		}
		Conversation group = service.create(true, clean(title, PhonePayloads.MAX_NAME), new ArrayList<>(members), owner);
		return service.post(group, owner, clean(text, PhonePayloads.MAX_TEXT), 0L);
	}

	/** Salva um contato na agenda de uma conta, sem passar pela rede (testes e administração). Retorna false se o número não existe. */
	public static boolean addContactFor(UUID owner, String number, String name) {
		PhoneService service = instance;
		String clean = digits(number);
		if (service == null || service.locked || !service.byNumber.containsKey(clean) || clean(name, PhonePayloads.MAX_NAME).isEmpty()) {
			return false;
		}
		numberFor(owner);
		service.data.accounts.get(owner).contacts.put(clean, clean(name, PhonePayloads.MAX_NAME));
		service.accountsDirty = true;
		return true;
	}

	/**
	 * Envia, em nome de uma conta e sem rede, uma mensagem de localização carimbada com a posição que o
	 * {@link LocationService} conhece dela (testes e administração). Retorna false se não pôde.
	 */
	public static boolean deliverDirectLocation(UUID sender, String toNumber) {
		PhoneService service = instance;
		UUID target = service != null && !service.locked ? service.byNumber.get(digits(toNumber)) : null;
		LocationService.Pos pos = service != null ? service.location.positionOf(sender) : null;
		if (target == null || target.equals(sender) || pos == null) {
			return false;
		}
		numberFor(sender);
		StoredLoc loc = new StoredLoc();
		loc.stamped = true;
		loc.dim = pos.dimension();
		loc.x = (int) Math.floor(pos.x());
		loc.y = (int) Math.floor(pos.y());
		loc.z = (int) Math.floor(pos.z());
		loc.hasY = true;
		return service.post(service.direct(sender, target), sender, "", 0L, loc);
	}

	/** O rótulo (nome do local encaminhado) da última mensagem com localização de uma conversa, ou null (testes). */
	public static String lastLocationLabelIn(long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		if (c == null) {
			return null;
		}
		for (int i = c.messages.size() - 1; i >= 0; i--) {
			StoredLoc l = c.messages.get(i).loc;
			if (l != null) {
				return l.label;
			}
		}
		return null;
	}

	/** O id da conversa criada mais recentemente (testes). */
	public static long newestConversationId() {
		PhoneService service = instance;
		return service == null ? -1 : service.data.nextConversationId - 1;
	}

	/** A última mensagem com localização de uma conversa como {x, y, z, carimbada(1/0), temY(1/0)}, ou null (testes). */
	public static int[] lastLocationIn(long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		if (c == null) {
			return null;
		}
		for (int i = c.messages.size() - 1; i >= 0; i--) {
			StoredLoc l = c.messages.get(i).loc;
			if (l != null) {
				return new int[] {l.x, l.y, l.z, l.stamped ? 1 : 0, l.hasY ? 1 : 0};
			}
		}
		return null;
	}

	/** Total de mensagens guardadas agora, somando todas as conversas (para diagnóstico e testes). */
	public static int storedMessages() {
		PhoneService service = instance;
		int total = 0;
		if (service != null) {
			for (Conversation conversation : service.conversations.values()) {
				total += conversation.messages.size();
			}
		}
		return total;
	}

	Account account(PhonePlayer player) {
		numberFor(player.getUUID());
		return data.accounts.get(player.getUUID());
	}

	/** A conta de um jogador, ou null se ele nunca teve número. */
	Account accountOf(UUID uuid) {
		return data.accounts.get(uuid);
	}

	/** Quem tem esse número, ou null. */
	UUID uuidOf(String number) {
		return byNumber.get(digits(number));
	}

	void markAccountsDirty() {
		accountsDirty = true;
	}

	PhonePlatform server() {
		return server;
	}

	/** Onde a conta está agora (a entidade dela, ou o que os testes escolheram), ou null. */
	LocationService.Pos locationOf(UUID who) {
		return location.positionOf(who);
	}

	/** Os membros de um grupo de que {@code who} participa (cópia); null se a conversa não existe, não é grupo ou ele não participa. */
	List<UUID> groupMembers(UUID who, long conversation) {
		Conversation c = memberConversation(who, conversation);
		return c != null && c.group ? List.copyOf(c.members) : null;
	}

	/** Ligar para este número abriria uma conversa nova e quem liga já está no teto de conversas? */
	boolean directCapReached(UUID who, UUID target) {
		return findDirect(who, target) == null && engagedCount(who) >= MAX_CONVERSATIONS;
	}

	/** O id da conversa direta entre duas contas, criada se ainda não existir. */
	long directConversationId(UUID a, UUID b) {
		return direct(a, b).id;
	}

	/**
	 * Escreve uma mensagem de sistema sobre uma chamada na conversa, escondida de quem bloqueou quem chamou. Não faz a conversa
	 * aparecer na lista de ninguém (mensagem de sistema nunca faz isso: ver {@link #listedFor}).
	 */
	void postCallMessage(long conversation, String text, List<UUID> hiddenFrom) {
		Conversation c = conversations.get(conversation);
		if (c != null) {
			post(c, null, text, 0L, null, hiddenFrom);
		}
	}

	/** Acrescenta uma chamada ao histórico da conta (guarda as {@link PhonePayloads#MAX_CALL_LOG} mais recentes) e avisa o celular. */
	void recordCall(UUID who, CallRecord record) {
		Account account = data.accounts.get(who);
		if (account == null) {
			return;
		}
		account.calls.addFirst(record);
		while (account.calls.size() > PhonePayloads.MAX_CALL_LOG) {
			account.calls.removeLast();
		}
		accountsDirty = true;
		PhonePlayer online = server.player(who);
		if (online != null) {
			sendCallLog(online);
		}
	}

	/** O histórico de chamadas da conta, para o celular. */
	void sendCallLog(PhonePlayer player) {
		Account account = data.accounts.get(player.getUUID());
		if (account == null) {
			return;
		}
		List<PhonePayloads.CallLogEntry> entries = new ArrayList<>();
		for (CallRecord r : account.calls) {
			entries.add(new PhonePayloads.CallLogEntry(r.time, r.conversation, r.number == null ? "" : r.number, r.group, r.outgoing, r.answered, r.seconds));
		}
		send(player, new PhonePayloads.CallLog(entries));
	}

	/** O histórico de chamadas de uma conta (testes). */
	public static List<PhonePayloads.CallLogEntry> callLogOf(UUID who) {
		PhoneService service = instance;
		Account account = service == null ? null : service.data.accounts.get(who);
		List<PhonePayloads.CallLogEntry> out = new ArrayList<>();
		if (account != null) {
			for (CallRecord r : account.calls) {
				out.add(new PhonePayloads.CallLogEntry(r.time, r.conversation, r.number == null ? "" : r.number, r.group, r.outgoing, r.answered, r.seconds));
			}
		}
		return out;
	}

	/** O relógio das janelas de frequência (os testes o trocam). */
	long now() {
		return clock.getAsLong();
	}

	/** As ligações de voz (para os testes e a administração); null se o serviço não está no ar. */
	public static CallService calls() {
		PhoneService service = instance;
		return service == null ? null : service.calls;
	}


	/** Os jogadores ligados agora, pelo UUID (a instância muda no respawn; nunca guarde um {@link PhonePlayer}). */
	PhonePlayer online(UUID uuid) {
		return server.player(uuid);
	}

	/** Número de 6 dígitos ainda não usado; passa para 7 se a faixa estiver ficando cheia. */
	private String newNumber() {
		int digits = byNumber.size() < 400_000 ? 6 : 7;
		int low = digits == 6 ? 100_000 : 1_000_000;
		while (true) {
			String number = Integer.toString(low + random.nextInt(low * 9));
			if (!byNumber.containsKey(number)) {
				return number;
			}
		}
	}

	String numberOf(UUID uuid) {
		Account account = data.accounts.get(uuid);
		return account != null ? account.number : "";
	}

	static String digits(String raw) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < raw.length() && out.length() < PhonePayloads.MAX_NUMBER; i++) {
			char c = raw.charAt(i);
			if (c >= '0' && c <= '9') {
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * Tira quebras de linha, caracteres de controle e caracteres de FORMATO do Unicode (categoria Cf: largura zero,
	 * marcas de direção como U+202E que invertem o texto, etc.), apara e corta no tamanho máximo. Texto que parece
	 * vazio ou que se inverte seria um jeito de enganar quem lê.
	 */
	static String clean(String raw, int max) {
		StringBuilder out = new StringBuilder();
		raw.codePoints().forEach(cp -> {
			int type = Character.getType(cp);
			if (cp == '\n' || cp == '\t' || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
				out.append(' ');
			} else if (cp >= 32 && cp != 127 && cp != 0xA7 && type != Character.FORMAT && type != Character.CONTROL
				&& type != Character.SURROGATE && type != Character.UNASSIGNED) {
				out.appendCodePoint(cp);
			}
		});
		String text = out.toString().strip();
		return text.length() > max ? text.substring(0, max).strip() : text;
	}

	/**
	 * Textos de tela (chaves de idioma) que o celular usa para falar em nome do sistema (título de aviso, rótulo de balão). Um grupo ou local com um desses
	 * nomes poderia se passar por um aviso do servidor; a comparação ignora maiúsculas, acentos, espaços e pontuação. Valem em todos os idiomas do mod.
	 */
	private static final String[] RESERVED_KEYS = {
		"handyphone.you", "handyphone.app.map", "handyphone.app.contacts", "handyphone.app.settings", "handyphone.app.about",
		"handyphone.app.phone", "handyphone.app.messages", "handyphone.app.camera", "handyphone.app.gallery", "handyphone.banner.notice",
		"handyphone.banner.photos", "handyphone.banner.blocked", "handyphone.banner.call", "handyphone.banner.missed_call",
		"handyphone.preview.photo", "handyphone.preview.location", "handyphone.preview.pin", "handyphone.preview.place",
		"handyphone.preview.empty", "handyphone.photo.deleted", "handyphone.chat.default_title"
	};

	private static final Set<String> RESERVED_NAMES = reservedNames();

	/**
	 * Os nomes reservados: as palavras de sistema abaixo (que não são texto de tela) mais, de TODOS os idiomas que vêm no mod, os textos de
	 * {@link #RESERVED_KEYS}. O servidor não sabe o idioma de cada jogador, então vale a união. Idioma novo no mod: os textos dele entram
	 * sozinhos; só as palavras de sistema (administrador, servidor...) precisam ser somadas à lista.
	 */
	private static Set<String> reservedNames() {
		Set<String> names = new java.util.HashSet<>(Set.of(
			"avisos", "alerta", "alertas", "sistema", "servidor", "admin", "administrador", "moderacao", "moderador", "handyphone", "blockphone", "ligacao",
			"notices", "alert", "alerts", "system", "server", "administrator", "moderation", "moderator", "owner", "operator",
			"alerte", "alertes", "administrateur", "moderateur", "serveur", "systeme",
			"warnung", "betreiber", "оповещение", "оповещения", "администратор", "модератор", "сервер", "система"
		));
		names = new java.util.HashSet<>(names.stream().map(PhoneService::foldName).toList());
		for (String code : dev.migzu.handyphone.i18n.LangFile.bundled()) {
			Map<String, String> lang = dev.migzu.handyphone.i18n.LangFile.load(code);
			for (String key : RESERVED_KEYS) {
				String text = lang.get(key);
				if (text != null) {
					names.add(foldName(text.replaceAll("%(\\d+\\$)?s", "")));
				}
			}
		}
		names.remove("");
		return Set.copyOf(names);
	}

	/** Minúsculas, sem acentos, espaços nem pontuação: "Ponto marcado", "ponto-marcado" e "PONTO MARCADO" viram a mesma chave. */
	private static String foldName(String name) {
		String folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).toLowerCase(java.util.Locale.ROOT);
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < folded.length(); i++) {
			char c = folded.charAt(i);
			if (Character.isLetterOrDigit(c)) {
				key.append(c);
			}
		}
		return key.toString();
	}

	/** Verdadeiro se o nome (de grupo ou de local) é um dos reservados do sistema. */
	static boolean isReservedName(String name) {
		return RESERVED_NAMES.contains(foldName(name));
	}

	// ------------------------------------------------------------------ pacotes recebidos

	private interface Handler<T extends PhonePayloads.Payload> {
		void handle(PhoneService service, PhonePlayer player, T packet);
	}

	/** Um tipo de pacote: quanto custa no orçamento do jogador e quem o trata. */
	private record Route<T extends PhonePayloads.Payload>(double cost, Handler<T> handler) {
	}

	private static final Map<PacketType<?>, Route<?>> ROUTES = new HashMap<>();

	static {
		registerRoutes();
	}

	/** Registra um tipo de pacote do cliente: o custo no orçamento e o tratador. */
	private static <T extends PhonePayloads.Payload> void route(PacketType<T> type, double cost, Handler<T> handler) {
		ROUTES.put(type, new Route<>(cost, handler));
	}

	/**
	 * O caminho de TODO pacote do celular que chega ao servidor: confere a trava de dados e o orçamento do jogador
	 * ({@link PacketBudget}) e só então chama o tratador. Deve ser chamado na thread do servidor. É público para os
	 * testes de ataque usarem exatamente o caminho da rede.
	 */
	@SuppressWarnings("unchecked")
	public static void handlePacket(PhonePlayer player, PhonePayloads.Payload packet) {
		PhoneService service = instance;
		Route<PhonePayloads.Payload> route = (Route<PhonePayloads.Payload>) ROUTES.get(packet.type());
		if (service == null || route == null) {
			return;
		}
		switch (service.budget.charge(player.getUUID(), route.cost())) {
			case DROP -> {
				return;
			}
			case DROP_NOTICE -> {
				notice(player, "handyphone.notice.budget");
				return;
			}
			case KICK -> {
				HandyPhoneCore.LOGGER.warn("HandyPhone: {} was disconnected for flooding the server with phone packets", player.name());
				player.disconnect(new Msg("handyphone.disconnect.flood"));
				return;
			}
			case ALLOW -> {
			}
		}
		if (service.locked) {
			notice(player, "handyphone.notice.unavailable");
			return;
		}
		route.handler().handle(service, player, packet);
	}

	private static void registerRoutes() {
		// Custos em fichas (capacidade 60, recarga 12 por segundo). Os caros custam mais; os que já têm limite próprio
		// (partes de foto, miniaturas, tiles) custam quase nada para uma galeria cheia não travar o resto do celular.
		route(PhonePayloads.Send.TYPE, 1, (s, pl, p) -> s.onSend(pl, p));
		route(PhonePayloads.OpenDirect.TYPE, 4, (s, pl, p) -> s.onOpenDirect(pl, p));
		route(PhonePayloads.Open.TYPE, 2, (s, pl, p) -> s.onOpen(pl, p));
		route(PhonePayloads.Read.TYPE, 0.5, (s, pl, p) -> s.onRead(pl, p));
		route(PhonePayloads.Contact.TYPE, 3, (s, pl, p) -> s.onContact(pl, p));
		route(PhonePayloads.Block.TYPE, 2, (s, pl, p) -> s.onBlock(pl, p));
		route(PhonePayloads.LeaveGroup.TYPE, 3, (s, pl, p) -> s.onLeaveGroup(pl, p));
		route(PhonePayloads.CreateGroup.TYPE, 10, (s, pl, p) -> s.onCreateGroup(pl, p));
		route(PhonePayloads.UploadBegin.TYPE, 3, (s, pl, p) -> s.onUploadBegin(pl, p));
		route(PhonePayloads.UploadChunk.TYPE, 0.05, (s, pl, p) -> s.onUploadChunk(pl, p));
		route(PhonePayloads.UploadEnd.TYPE, 0.5, (s, pl, p) -> s.onUploadEnd(pl, p));
		route(PhonePayloads.PhotoGet.TYPE, 0.05, (s, pl, p) -> s.onPhotoGet(pl, p));
		route(PhonePayloads.PhotoDelete.TYPE, 1, (s, pl, p) -> s.onPhotoDelete(pl, p));
		route(PhonePayloads.SendPhoto.TYPE, 2, (s, pl, p) -> s.onSendPhoto(pl, p));
		route(PhonePayloads.TileGet.TYPE, 0.05, (s, pl, p) -> s.location.onTileGet(pl, p));
		route(PhonePayloads.SendLocation.TYPE, 2, (s, pl, p) -> s.location.onSendLocation(pl, p));
		route(PhonePayloads.SendPin.TYPE, 2, (s, pl, p) -> s.location.onSendPin(pl, p));
		route(PhonePayloads.ShareStart.TYPE, 5, (s, pl, p) -> s.location.onShareStart(pl, p));
		route(PhonePayloads.ShareStop.TYPE, 1, (s, pl, p) -> s.location.onShareStop(pl));
		route(PhonePayloads.Watch.TYPE, 1, (s, pl, p) -> s.location.onWatch(pl, p));
		route(PhonePayloads.PlaceSave.TYPE, 3, (s, pl, p) -> s.location.onPlaceSave(pl, p));
		route(PhonePayloads.PlaceDelete.TYPE, 2, (s, pl, p) -> s.location.onPlaceDelete(pl, p));
		route(PhonePayloads.PlaceEdit.TYPE, 2, (s, pl, p) -> s.location.onPlaceEdit(pl, p));
		route(PhonePayloads.SendPlace.TYPE, 2, (s, pl, p) -> s.location.onSendPlace(pl, p));
		// Ligar é caro: cada toque incomoda outra pessoa (e ainda há o limite próprio de tentativas por minuto e por hora).
		route(PhonePayloads.CallStart.TYPE, 5, (s, pl, p) -> s.calls.start(pl.getUUID(), p.number()));
		route(PhonePayloads.CallGroup.TYPE, 5, (s, pl, p) -> s.calls.startGroup(pl.getUUID(), p.conversation()));
		route(PhonePayloads.CallAnswer.TYPE, 1, (s, pl, p) -> s.calls.answer(pl.getUUID(), p.id()));
		route(PhonePayloads.CallDecline.TYPE, 1, (s, pl, p) -> s.calls.decline(pl.getUUID(), p.id()));
		// O mudo refaz o retrato de voz e avisa os dois lados: custa mais que um clique.
		route(PhonePayloads.CallMute.TYPE, 3, (s, pl, p) -> s.calls.mute(pl.getUUID(), p.id(), p.on()));
	}

	private void onSend(PhonePlayer player, PhonePayloads.Send packet) {
		account(player);
		Conversation conversation = memberConversation(player, packet.conversation());
		String text = clean(packet.text(), PhonePayloads.MAX_TEXT);
		if (conversation == null || text.isEmpty()) {
			return;
		}
		if (!canSpeak(player) || !allowSend(player)) {
			return;
		}
		post(conversation, player.getUUID(), text, 0L);
	}

	/** O jogador não está silenciado pelo administrador? Se está, avisa (a quem foi silenciado se avisa; a quem foi bloqueado, não). */
	boolean canSpeak(PhonePlayer player) {
		if (isMuted(player.getUUID())) {
			notice(player, "handyphone.notice.muted");
			return false;
		}
		return true;
	}

	/** A conta está silenciada pelo administrador agora? */
	boolean isMuted(UUID who) {
		Account account = data.accounts.get(who);
		return account != null && account.mutedUntil > clock.getAsLong();
	}

	/**
	 * {@code blocker} bloqueou {@code blocked}? É o ÚNICO lugar que responde a isso: mensagens, convites a grupos,
	 * fotos, localização e compartilhamento ao vivo passam por aqui, e as ligações da fase 5 também devem passar.
	 */
	boolean isBlocked(UUID blocker, UUID blocked) {
		Account account = data.accounts.get(blocker);
		return account != null && account.blocked.contains(blocked);
	}

	/** Versão estática de {@link #isBlocked(UUID, UUID)} para outros sistemas (ligações da fase 5). */
	public static boolean hasBlocked(UUID blocker, UUID blocked) {
		PhoneService service = instance;
		return service != null && service.isBlocked(blocker, blocked);
	}

	/** Limite de frequência de mensagens (texto e foto contam igual). */
	boolean allowSend(PhonePlayer player) {
		long now = clock.getAsLong();
		ArrayDeque<Long> sends = recentSends.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		while (!sends.isEmpty() && now - sends.peekFirst() > RATE_WINDOW_MS) {
			sends.pollFirst();
		}
		if (sends.size() >= RATE_LIMIT) {
			notice(player, "handyphone.notice.messages_rate");
			return false;
		}
		sends.addLast(now);
		return true;
	}

	private void onSendPhoto(PhonePlayer player, PhonePayloads.SendPhoto packet) {
		account(player);
		if (!config.photosEnabled || !photosAvailable) {
			notice(player, "handyphone.notice.photos_off");
			return;
		}
		Conversation conversation = memberConversation(player, packet.conversation());
		PhotoStore.Photo photo = photos.get(packet.photo());
		// Só dá para mandar uma foto da própria galeria: encaminhar a de outra pessoa prenderia a cota dela.
		if (conversation == null || photo == null || !photo.owner.equals(player.getUUID())) {
			notice(player, "handyphone.notice.photo_gone");
			return;
		}
		if (canSpeak(player) && allowSend(player)) {
			post(conversation, player.getUUID(), "", photo.id);
		}
	}

	/** Guarda a mensagem (já validada) e entrega a quem está online. */
	private boolean post(Conversation conversation, UUID sender, String text, long photo) {
		return post(conversation, sender, text, photo, null);
	}

	/**
	 * Igual ao de cima, com um ponto do mundo opcional (mensagem de localização). {@code sender} null = mensagem de
	 * sistema. É o único caminho de uma mensagem para dentro de uma conversa, então o silenciamento e o bloqueio
	 * valem para tudo (texto, foto, localização) sem depender de cada tratador lembrar.
	 *
	 * <p><b>Bloqueio.</b> Quem bloqueou o remetente não recebe a mensagem e nunca a vê (nem no histórico, nem na lista,
	 * nem nas não lidas): ela fica guardada com a lista {@code hiddenFrom}, decidida AGORA. Assim o remetente não
	 * percebe nada, em grupo os outros membros veem normalmente e desbloquear depois não revela o que foi escondido.
	 *
	 * @return false se a mensagem foi recusada (remetente silenciado)
	 */
	boolean post(Conversation conversation, UUID sender, String text, long photo, StoredLoc loc) {
		return post(conversation, sender, text, photo, loc, null);
	}

	/**
	 * Igual ao de cima; {@code forcedHidden} vale só para mensagens de sistema (remetente null) e diz quem nunca as vê. É assim
	 * que o aviso de uma chamada some para quem bloqueou quem chamou, sem diferença nenhuma para quem chamou.
	 */
	private boolean post(Conversation conversation, UUID sender, String text, long photo, StoredLoc loc, List<UUID> forcedHidden) {
		if (sender != null && isMuted(sender)) {
			return false;
		}
		StoredMessage message = new StoredMessage();
		message.seq = ++conversation.lastSeq;
		message.sender = sender;
		message.system = sender == null;
		message.time = clock.getAsLong();
		message.text = text;
		message.photo = photo;
		message.loc = loc;
		if (sender != null) {
			for (UUID member : conversation.members) {
				if (!member.equals(sender) && isBlocked(member, sender)) {
					if (message.hiddenFrom == null) {
						message.hiddenFrom = new ArrayList<>();
					}
					message.hiddenFrom.add(member);
				}
			}
		} else if (forcedHidden != null && !forcedHidden.isEmpty()) {
			message.hiddenFrom = new ArrayList<>(forcedHidden);
		}
		conversation.messages.add(message);
		addRef(photo, conversation.id);
		// Passou de 300: sai primeiro a mensagem mais antiga que só o remetente vê ("fantasma", de quem foi bloqueado), para
		// quem foi bloqueado não conseguir empurrar para fora o histórico verdadeiro; sem fantasmas, sai a mais antiga.
		while (conversation.messages.size() > MAX_STORED_MESSAGES) {
			int victim = 0;
			for (int i = 0; i < conversation.messages.size() - 1; i++) {
				if (isGhost(conversation, conversation.messages.get(i))) {
					victim = i;
					break;
				}
			}
			removeRef(conversation.messages.get(victim).photo, conversation.id);
			conversation.messages.remove(victim);
		}
		if (sender != null) {
			// Quem escreve numa conversa passa a "tê-la aberto": ela conta para o teto dele e aparece na lista dele.
			markOpened(conversation, sender);
		}
		if (sender != null) {
			data.accounts.get(sender).read.put(conversation.id, message.seq);
		}
		dirtyConversations.add(conversation.id);
		accountsDirty = true;

		List<MessageEntry> single = List.of(entry(message));
		for (UUID member : conversation.members) {
			PhonePlayer online = server.player(member);
			if (online != null && visibleTo(member, message)) {
				// Só a entrada desta conversa (ela pode ser nova para quem recebe), e não a lista inteira: o custo de uma
				// mensagem não pode crescer com o número de conversas de cada membro.
				sendConversation(online, conversation);
				send(online, new PhonePayloads.Messages(conversation.id, false, false, single));
			}
		}
		return true;
	}

	/** A mensagem está escondida de todos os membros menos o remetente? */
	private static boolean isGhost(Conversation conversation, StoredMessage message) {
		if (message.hiddenFrom == null || message.sender == null) {
			return false;
		}
		for (UUID member : conversation.members) {
			if (!member.equals(message.sender) && !message.hiddenFrom.contains(member)) {
				return false;
			}
		}
		return true;
	}

	/** O visualizador pode ver a mensagem? (O remetente sempre pode; mensagens de sistema valem para todos.) */
	private static boolean visibleTo(UUID viewer, StoredMessage message) {
		return message.hiddenFrom == null || !message.hiddenFrom.contains(viewer);
	}

	/**
	 * A conversa aparece na lista do jogador? Só se tem ao menos uma mensagem que ele pode ver, ou se ele mesmo a abriu.
	 * É isso que impede conversa vazia de aparecer na vítima só porque alguém consultou o número dela, e que um grupo
	 * criado por quem ela bloqueou apareça (as mensagens do criador ficam escondidas). Arquivos antigos, sem
	 * {@code opener}, continuam aparecendo para todos os membros como sempre.
	 */
	private boolean listedFor(UUID viewer, Conversation conversation) {
		if (conversation.messages.isEmpty()) {
			return conversation.openers == null || conversation.openers.contains(viewer);
		}
		if (conversation.openers != null && conversation.openers.contains(viewer)) {
			return true;
		}
		for (int i = conversation.messages.size() - 1; i >= 0; i--) {
			StoredMessage message = conversation.messages.get(i);
			// "Fulano saiu do grupo" não conta: senão "criar um grupo com a vítima e sair" faria o grupo aparecer para ela.
			if (!message.system && visibleTo(viewer, message)) {
				return true;
			}
		}
		return false;
	}

	/** O jogador abriu a conversa de propósito: ela passa a aparecer na lista dele mesmo vazia (ou só com mensagens escondidas). */
	private void markOpened(Conversation conversation, UUID who) {
		if (conversation.openers != null && !conversation.openers.contains(who)) {
			conversation.openers.add(who);
			dirtyConversations.add(conversation.id);
		}
	}

	private void onOpenDirect(PhonePlayer player, PhonePayloads.OpenDirect packet) {
		Account account = account(player);
		String number = digits(packet.number());
		if (!allowNewNumber(player.getUUID(), account, number)) {
			notice(player, "handyphone.notice.numbers_rate");
			return;
		}
		UUID target = byNumber.get(number);
		if (target == null) {
			notice(player, "handyphone.notice.number_unknown");
			return;
		}
		if (target.equals(player.getUUID())) {
			notice(player, "handyphone.notice.number_own");
			return;
		}
		Conversation existing = findDirect(player.getUUID(), target);
		if (existing == null && engagedCount(player.getUUID()) >= MAX_CONVERSATIONS) {
			notice(player, "handyphone.notice.chat_limit.open", String.valueOf(MAX_CONVERSATIONS));
			return;
		}
		Conversation conversation = existing != null ? existing : create(false, "", List.of(player.getUUID(), target), player.getUUID());
		markOpened(conversation, player.getUUID());
		openFor(player, conversation, true, 0);
	}

	/** A conversa individual entre dois jogadores, ou null se ainda não existe. */
	private Conversation findDirect(UUID a, UUID b) {
		for (long id : conversationsOf.getOrDefault(a, Set.of())) {
			Conversation candidate = conversations.get(id);
			if (candidate != null && !candidate.group && candidate.members.contains(b)) {
				return candidate;
			}
		}
		return null;
	}

	/** A conversa individual entre dois jogadores, criada se ainda não existir ({@code opener} a abre; só ele a vê vazia). */
	private Conversation direct(UUID a, UUID b) {
		Conversation found = findDirect(a, b);
		return found != null ? found : create(false, "", List.of(a, b), a);
	}

	/**
	 * Limite de números NOVOS consultados por hora: impede varrer os 900 mil números de 6 dígitos para descobrir quem
	 * existe. Não contam o próprio número, os contatos, quem já tem conversa com o jogador e um número já consultado na
	 * última hora. Retorna false se passou do limite.
	 */
	boolean allowNewNumber(UUID who, Account account, String number) {
		if (number.isEmpty() || number.equals(account.number) || account.contacts.containsKey(number)) {
			return true;
		}
		UUID target = byNumber.get(number);
		if (target != null && findDirect(who, target) != null) {
			return true;
		}
		long now = clock.getAsLong();
		Map<String, Long> seen = newNumbers.computeIfAbsent(who, k -> new LinkedHashMap<>());
		seen.values().removeIf(time -> now - time > NEW_NUMBERS_WINDOW_MS);
		if (seen.containsKey(number)) {
			return true;
		}
		if (seen.size() >= NEW_NUMBERS_PER_HOUR) {
			return false;
		}
		seen.put(number, now);
		return true;
	}

	/**
	 * Conversas do jogador que contam para o teto: as que ele abriu ou em que escreveu (e as de arquivos antigos). As que
	 * outros criaram com ele e ele nunca abriu não contam, senão alguém lotaria o teto da vítima e a impediria de conversar.
	 */
	private int engagedCount(UUID who) {
		int count = 0;
		for (long id : conversationsOf.getOrDefault(who, Set.of())) {
			Conversation c = conversations.get(id);
			if (c != null && (c.openers == null || c.openers.contains(who)) && listedFor(who, c)) {
				count++;
			}
		}
		return count;
	}

	/** Conversas que o jogador vê na lista agora (as vazias que ele não abriu e as escondidas não contam). */
	private int listedCount(UUID who) {
		int count = 0;
		for (long id : conversationsOf.getOrDefault(who, Set.of())) {
			Conversation c = conversations.get(id);
			if (c != null && listedFor(who, c)) {
				count++;
			}
		}
		return count;
	}

	private void onOpen(PhonePlayer player, PhonePayloads.Open packet) {
		Conversation conversation = memberConversation(player, packet.conversation());
		if (conversation != null) {
			openFor(player, conversation, false, packet.have());
		}
	}

	private void onRead(PhonePlayer player, PhonePayloads.Read packet) {
		Conversation conversation = memberConversation(player, packet.conversation());
		if (conversation != null) {
			int seq = Math.max(0, Math.min(packet.seq(), conversation.lastSeq));
			Account account = account(player);
			if (seq > account.read.getOrDefault(conversation.id, 0)) {
				account.read.put(conversation.id, seq);
				accountsDirty = true;
			}
		}
	}

	private void onContact(PhonePlayer player, PhonePayloads.Contact packet) {
		Account account = account(player);
		String number = digits(packet.number());
		String name = clean(packet.name(), PhonePayloads.MAX_NAME);
		if (name.isEmpty()) {
			if (account.contacts.remove(number) != null) {
				accountsDirty = true;
			}
		} else if (!allowNewNumber(player.getUUID(), account, number)) {
			notice(player, "handyphone.notice.numbers_rate");
			return;
		} else if (!byNumber.containsKey(number)) {
			notice(player, "handyphone.notice.number_unknown");
			return;
		} else if (number.equals(account.number)) {
			notice(player, "handyphone.notice.number_own");
			return;
		} else if (!account.contacts.containsKey(number) && account.contacts.size() >= MAX_CONTACTS) {
			notice(player, "handyphone.notice.contacts_full");
			return;
		} else {
			account.contacts.put(number, name);
			accountsDirty = true;
		}
		sync(player);
	}

	/** Bloqueia ou desbloqueia um número. Quem foi bloqueado não recebe nenhum aviso. */
	private void onBlock(PhonePlayer player, PhonePayloads.Block packet) {
		Account account = account(player);
		String number = digits(packet.number());
		if (!allowNewNumber(player.getUUID(), account, number)) {
			notice(player, "handyphone.notice.numbers_rate");
			return;
		}
		UUID target = byNumber.get(number);
		if (target == null) {
			notice(player, "handyphone.notice.number_unknown");
			return;
		}
		if (target.equals(player.getUUID())) {
			notice(player, "handyphone.notice.number_own");
			return;
		}
		if (packet.on()) {
			if (!account.blocked.contains(target)) {
				if (account.blocked.size() >= PhonePayloads.MAX_BLOCKED) {
					notice(player, "handyphone.notice.blocked_full");
					return;
				}
				account.blocked.add(target);
			}
		} else if (!account.blocked.remove(target)) {
			sendBlocked(player);
			return;
		}
		accountsDirty = true;
		sendBlocked(player);
		// A localização ao vivo entre os dois muda na hora (nos dois sentidos).
		location.onBlockChanged(player.getUUID(), target);
		// Uma ligação entre os dois acaba na hora (ou deixa de tocar), nos dois sentidos.
		calls.onBlockChanged(player.getUUID(), target);
	}

	/**
	 * Sai de um grupo: o grupo continua para os outros, com uma mensagem de sistema. Não existe "adicionar membro",
	 * então ninguém é readicionado ao mesmo grupo; a conversa guarda quem saiu ({@code left}) para isso continuar
	 * valendo se algum dia houver essa função.
	 */
	private void onLeaveGroup(PhonePlayer player, PhonePayloads.LeaveGroup packet) {
		Conversation conversation = memberConversation(player, packet.conversation());
		if (conversation == null || !conversation.group) {
			return;
		}
		leaveGroup(player.getUUID(), conversation);
		sync(player);
	}

	private void leaveGroup(UUID who, Conversation conversation) {
		String number = numberOf(who);
		conversation.members.remove(who);
		if (!conversation.left.contains(who)) {
			conversation.left.add(who);
		}
		Set<Long> mine = conversationsOf.get(who);
		if (mine != null) {
			mine.remove(conversation.id);
		}
		Account account = data.accounts.get(who);
		if (account != null) {
			account.read.remove(conversation.id);
		}
		location.onLeftGroup(who, conversation.id);
		calls.onLeftGroup(who, conversation.id);
		dirtyConversations.add(conversation.id);
		accountsDirty = true;
		if (conversation.members.size() < 2) {
			// Sobrou uma pessoa só: o grupo acaba. Quem cria um grupo, assedia e sai não pode apagar a prova: ela é arquivada.
			archiveConversation(conversation);
			deleteConversation(conversation);
		} else {
			post(conversation, null, PhonePayloads.SYSTEM_LEFT + number, 0L, null);
		}
	}

	/** Guarda uma cópia da conversa em {@code moderacao/grupos-apagados/} (se tem mensagens) antes de ela ser apagada. */
	private void archiveConversation(Conversation conversation) {
		if (conversation.messages.isEmpty()) {
			return;
		}
		try {
			write(dir.resolve("moderacao").resolve("grupos-apagados").resolve(conversation.id + ".json"), conversation);
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.warn("HandyPhone: couldn't archive group {} before deleting it", conversation.id, e);
		}
	}

	/** Apaga a conversa de vez: memória, índices, referências de fotos e (no próximo salvamento) o arquivo. */
	private void deleteConversation(Conversation conversation) {
		conversations.remove(conversation.id);
		for (UUID member : conversation.members) {
			Set<Long> mine = conversationsOf.get(member);
			if (mine != null) {
				mine.remove(conversation.id);
			}
			Account account = data.accounts.get(member);
			if (account != null) {
				account.read.remove(conversation.id);
			}
		}
		for (StoredMessage message : conversation.messages) {
			removeRef(message.photo, conversation.id);
		}
		dirtyConversations.remove(conversation.id);
		deletedConversations.add(conversation.id);
		accountsDirty = true;
		location.onConversationGone(conversation.id);
		calls.onConversationGone(conversation.id);
		for (UUID member : conversation.members) {
			PhonePlayer online = server.player(member);
			if (online != null) {
				sync(online);
			}
		}
	}

	private void onCreateGroup(PhonePlayer player, PhonePayloads.CreateGroup packet) {
		Account account = account(player);
		if (!canSpeak(player)) {
			return;
		}
		String title = clean(packet.title(), PhonePayloads.MAX_NAME);
		if (title.isEmpty()) {
			notice(player, "handyphone.notice.group_name");
			return;
		}
		if (isReservedName(title)) {
			notice(player, "handyphone.notice.name_reserved");
			return;
		}
		if (packet.numbers().isEmpty()) {
			notice(player, "handyphone.notice.group_members");
			return;
		}
		if (!allowGroupCreate(player.getUUID())) {
			notice(player, "handyphone.notice.groups_rate");
			return;
		}
		Set<UUID> members = new LinkedHashSet<>();
		members.add(player.getUUID());
		for (String raw : packet.numbers()) {
			String number = digits(raw);
			if (!allowNewNumber(player.getUUID(), account, number)) {
				notice(player, "handyphone.notice.numbers_rate");
				return;
			}
			UUID target = byNumber.get(number);
			if (target != null && members.size() < PhonePayloads.MAX_GROUP_MEMBERS) {
				members.add(target);
			}
		}
		// Uma só resposta para tudo o que impede o grupo (número que não existe, teto de conversas de algum membro):
		// quem tenta não descobre nada sobre os outros jogadores.
		if (members.size() < 2 || atConversationCap(members)) {
			if (engagedCount(player.getUUID()) >= MAX_CONVERSATIONS) {
				notice(player, "handyphone.notice.chat_limit.create", String.valueOf(MAX_CONVERSATIONS));
			} else {
				notice(player, "handyphone.notice.group_failed");
			}
			return;
		}
		Conversation conversation = create(true, title, new ArrayList<>(members), player.getUUID());
		// Os outros membros só veem o grupo quando alguém postar nele (ver listedFor): nada de grupo vazio aparecendo para eles.
		openFor(player, conversation, true, 0);
	}

	/** Algum membro (o criador inclusive) já está no teto de conversas? */
	private boolean atConversationCap(Set<UUID> members) {
		for (UUID member : members) {
			if (engagedCount(member) >= MAX_CONVERSATIONS) {
				return true;
			}
		}
		return false;
	}

	/** No máximo {@link #GROUPS_PER_MINUTE} grupos por minuto e {@link #GROUPS_PER_HOUR} por hora, por jogador. */
	private boolean allowGroupCreate(UUID who) {
		long now = clock.getAsLong();
		ArrayDeque<Long> creates = groupCreates.computeIfAbsent(who, k -> new ArrayDeque<>());
		while (!creates.isEmpty() && now - creates.peekFirst() > 3_600_000L) {
			creates.pollFirst();
		}
		int lastMinute = 0;
		for (long time : creates) {
			if (now - time <= 60_000L) {
				lastMinute++;
			}
		}
		if (lastMinute >= GROUPS_PER_MINUTE || creates.size() >= GROUPS_PER_HOUR) {
			return false;
		}
		creates.addLast(now);
		return true;
	}

	// ------------------------------------------------------------------ conversas

	/** A conversa pedida, somente se o jogador participa dela. */
	Conversation memberConversation(PhonePlayer player, long id) {
		return memberConversation(player.getUUID(), id);
	}

	Conversation memberConversation(UUID who, long id) {
		Conversation conversation = conversations.get(id);
		return conversation != null && conversation.members.contains(who) ? conversation : null;
	}

	private Conversation create(boolean group, String title, List<UUID> members, UUID opener) {
		Conversation conversation = new Conversation();
		conversation.id = data.nextConversationId++;
		conversation.group = group;
		conversation.title = title;
		conversation.members = new ArrayList<>(members);
		conversation.openers = new ArrayList<>(List.of(opener));
		conversation.creator = group ? opener : null;
		index(conversation);
		dirtyConversations.add(conversation.id);
		accountsDirty = true;
		return conversation;
	}

	private void index(Conversation conversation) {
		conversations.put(conversation.id, conversation);
		for (UUID member : conversation.members) {
			conversationsOf.computeIfAbsent(member, k -> new HashSet<>()).add(conversation.id);
		}
		for (StoredMessage message : conversation.messages) {
			addRef(message.photo, conversation.id);
		}
	}

	private void addRef(long photo, long conversation) {
		if (photo != 0) {
			photoRefs.computeIfAbsent(photo, k -> new HashMap<>()).merge(conversation, 1, Integer::sum);
		}
	}

	private void removeRef(long photo, long conversation) {
		Map<Long, Integer> refs = photo != 0 ? photoRefs.get(photo) : null;
		if (refs != null && refs.merge(conversation, -1, Integer::sum) <= 0) {
			refs.remove(conversation);
			if (refs.isEmpty()) {
				photoRefs.remove(photo);
			}
		}
	}

	/**
	 * Marca como lida e manda o histórico recente para o jogador. {@code have} é a última mensagem que o celular já
	 * tem: se dá para continuar dali, só o que veio depois é mandado (um {@code Open} repetido não reenvia tudo).
	 */
	private void openFor(PhonePlayer player, Conversation conversation, boolean focus, int have) {
		Account account = account(player);
		if (account.read.getOrDefault(conversation.id, 0) != conversation.lastSeq) {
			account.read.put(conversation.id, conversation.lastSeq);
			accountsDirty = true;
		}
		sendHistory(player, conversation, focus, have);
	}

	/** Manda a conversa e as mensagens que o jogador pode ver (sem marcar como lida). */
	private void sendHistory(PhonePlayer player, Conversation conversation, boolean focus, int have) {
		UUID who = player.getUUID();
		List<StoredMessage> stored = conversation.messages;
		int oldest = stored.isEmpty() ? conversation.lastSeq + 1 : stored.getFirst().seq;
		// Um valor que o servidor não reconhece (0, maior que a conversa, mais velho que o que ele guarda) vira o histórico completo.
		boolean incremental = have > 0 && have <= conversation.lastSeq && have >= oldest - 1;
		List<MessageEntry> history = new ArrayList<>();
		for (int i = stored.size() - 1; i >= 0 && (incremental || history.size() < HISTORY_SIZE); i--) {
			StoredMessage message = stored.get(i);
			if (incremental && message.seq <= have) {
				break;
			}
			if (visibleTo(who, message)) {
				history.add(entry(message));
			}
		}
		java.util.Collections.reverse(history);
		sendConversation(player, conversation);
		if (!incremental || focus || !history.isEmpty()) {
			send(player, new PhonePayloads.Messages(conversation.id, !incremental, focus, history));
		}
	}

	/** O número de quem enviou; "?" se a conta não existe mais (nunca vazio: vazio é mensagem de sistema para o celular). */
	private String senderNumber(UUID sender) {
		String number = numberOf(sender);
		return number.isEmpty() ? "?" : number;
	}

	private MessageEntry entry(StoredMessage message) {
		StoredLoc l = message.loc;
		PhonePayloads.Loc loc = l == null ? null : new PhonePayloads.Loc(l.stamped, l.dim == null ? "" : l.dim, l.x, l.hasY, l.y, l.z, l.label == null ? "" : l.label);
		return new MessageEntry(message.seq, message.system ? "" : senderNumber(message.sender), message.time, message.text, message.photo, loc);
	}

	// ------------------------------------------------------------------ envio

	/** A última mensagem que o jogador pode ver na conversa, ou null. */
	private StoredMessage lastVisible(UUID viewer, Conversation conversation) {
		for (int i = conversation.messages.size() - 1; i >= 0; i--) {
			StoredMessage message = conversation.messages.get(i);
			if (visibleTo(viewer, message)) {
				return message;
			}
		}
		return null;
	}

	/** Não lidas de um jogador: só contam as mensagens que ele pode ver. */
	private int unreadFor(UUID viewer, Conversation conversation, int readSeq) {
		int unread = 0;
		for (int i = conversation.messages.size() - 1; i >= 0; i--) {
			StoredMessage message = conversation.messages.get(i);
			if (message.seq <= readSeq) {
				break;
			}
			if (visibleTo(viewer, message)) {
				unread++;
			}
		}
		return unread;
	}

	/** O resumo de uma conversa para a lista de um jogador (a última mensagem e as não lidas são só as que ele pode ver). */
	private ConversationEntry conversationEntry(UUID viewer, Account account, Conversation c) {
		List<String> members = new ArrayList<>();
		for (UUID member : c.members) {
			members.add(numberOf(member));
		}
		StoredMessage last = lastVisible(viewer, c);
		return new ConversationEntry(
			c.id, c.group, c.title, members, unreadFor(viewer, c, account.read.getOrDefault(c.id, 0)), c.lastSeq,
			last != null && !last.system ? senderNumber(last.sender) : "", last != null ? last.text : "", last != null ? last.time : 0L,
			last != null && last.photo != 0, last != null && last.loc != null
		);
	}

	/** Troca no celular do jogador só a entrada desta conversa. */
	private void sendConversation(PhonePlayer player, Conversation conversation) {
		if (!player.canSend(PhonePayloads.ConvUpdate.TYPE) || !listedFor(player.getUUID(), conversation)) {
			return;
		}
		send(player, new PhonePayloads.ConvUpdate(conversationEntry(player.getUUID(), account(player), conversation)));
	}

	/**
	 * Monta o estado completo de um jogador (número, contatos e lista de conversas). A lista nunca passa de
	 * {@link #SYNC_MAX_CONVERSATIONS}: o celular recusa pacotes com mais de 512 itens (e um jogador cheio de conversas
	 * caía do servidor toda vez que entrava). Ficam as mais recentes.
	 */
	PhonePayloads.Sync buildSync(UUID who) {
		Account account = data.accounts.get(who);
		List<ContactEntry> contacts = new ArrayList<>();
		account.contacts.forEach((number, name) -> contacts.add(new ContactEntry(number, name)));

		List<ConversationEntry> list = new ArrayList<>();
		for (long id : conversationsOf.getOrDefault(who, Set.of())) {
			Conversation c = conversations.get(id);
			if (c != null && listedFor(who, c)) {
				list.add(conversationEntry(who, account, c));
			}
		}
		list.sort(Comparator.comparingLong(ConversationEntry::lastTime).reversed().thenComparing(Comparator.comparingLong(ConversationEntry::id).reversed()));
		if (list.size() > SYNC_MAX_CONVERSATIONS) {
			list = new ArrayList<>(list.subList(0, SYNC_MAX_CONVERSATIONS));
		}
		return new PhonePayloads.Sync(account.number, contacts, list);
	}

	/** Manda ao jogador o estado completo dele (número, contatos e lista de conversas). */
	private void sync(PhonePlayer player) {
		if (locked || !player.canSend(PhonePayloads.Sync.TYPE)) {
			return;
		}
		account(player);
		send(player, buildSync(player.getUUID()));
	}

	/** Os números que o jogador bloqueou (para a tela de bloqueados). */
	private void sendBlocked(PhonePlayer player) {
		List<String> numbers = new ArrayList<>();
		for (UUID blocked : account(player).blocked) {
			String number = numberOf(blocked);
			if (!number.isEmpty()) {
				numbers.add(number);
			}
		}
		send(player, new PhonePayloads.Blocked(numbers));
	}

	/** Manda um aviso ao celular do jogador: chave de texto ({@code handyphone.notice.*}) e argumentos; o celular traduz. */
	static void notice(PhonePlayer player, String key, String... args) {
		send(player, new PhonePayloads.Notice(key, args));
	}

	/**
	 * Motivo de uma recusa de envio de foto: a chave de texto e os argumentos (o celular traduz). {@link #english()} é
	 * o texto em inglês, para logs e testes.
	 */
	record Refusal(String key, String... args) {
		String english() {
			return dev.migzu.handyphone.i18n.LangFile.format(dev.migzu.handyphone.i18n.LangFile.english(key), (Object[]) args);
		}
	}

	private static Refusal refusal(String key, String... args) {
		return new Refusal(key, args);
	}

	/** Só para os testes: vê tudo o que o servidor manda a um jogador (null = desligado). */
	private static volatile java.util.function.BiConsumer<PhonePlayer, PhonePayloads.Payload> sendSink;

	/** Liga (ou desliga, com null) a observação dos pacotes que o servidor manda; só para os testes. */
	public static void observeSends(java.util.function.BiConsumer<PhonePlayer, PhonePayloads.Payload> sink) {
		sendSink = sink;
	}

	static void send(PhonePlayer player, PhonePayloads.Payload payload) {
		java.util.function.BiConsumer<PhonePlayer, PhonePayloads.Payload> sink = sendSink;
		if (sink != null) {
			sink.accept(player, payload);
		}
		if (player.canSend(payload.type())) {
			player.send(payload);
		}
	}

	// ------------------------------------------------------------------ fotos: envio

	private void onUploadBegin(PhonePlayer player, PhonePayloads.UploadBegin packet) {
		account(player);
		Refusal error = beginUpload(player.getUUID(), packet.token(), packet.total(), packet.format(), packet.source());
		if (error != null) {
			failUpload(player.getUUID(), packet.token(), error);
		}
	}

	private void onUploadChunk(PhonePlayer player, PhonePayloads.UploadChunk packet) {
		Refusal error = uploadChunk(player.getUUID(), packet.index(), packet.data());
		if (error == STRAY) {
			countStray(player);
		} else if (error != null) {
			Upload upload = uploads.remove(player.getUUID());
			failUpload(player.getUUID(), upload != null ? upload.token : 0, error);
		}
	}

	private void onUploadEnd(PhonePlayer player, PhonePayloads.UploadEnd packet) {
		Refusal error = endUpload(player.getUUID(), packet.token());
		if (error == STRAY) {
			countStray(player);
		} else if (error != null) {
			uploads.remove(player.getUUID());
			failUpload(player.getUUID(), packet.token(), error);
		}
	}

	/**
	 * Pacote de foto sem envio ativo. Os que sobram de um envio recém-recusado são perdoados; o excesso
	 * (só um cliente alterado faz) acaba em desconexão.
	 */
	private void countStray(PhonePlayer player) {
		UUID who = player.getUUID();
		int allowance = strayAllowance.getOrDefault(who, 0);
		if (allowance > 0) {
			strayAllowance.put(who, allowance - 1);
		} else if (strays.merge(who, 1, Integer::sum) > MAX_STRAYS) {
			player.disconnect(new Msg("handyphone.disconnect.photo_flood"));
		}
	}

	/** Recusa um envio: avisa o jogador (se está online) e libera a fila de envios do cliente dele. */
	private void failUpload(UUID who, int token, Refusal reason) {
		strayAllowance.put(who, STRAY_ALLOWANCE);
		PhonePlayer online = server.player(who);
		if (online != null) {
			notice(online, reason.key(), reason.args());
			send(online, new PhonePayloads.UploadResult(token, false, 0));
		}
	}

	/**
	 * Começa um envio de foto. Todas as checagens que não dependem dos pixels acontecem aqui,
	 * antes de alocar qualquer coisa. Retorna o motivo da recusa, ou null se foi aceito.
	 */
	private Refusal beginUpload(UUID who, int token, int total, int format, int source) {
		long now = clock.getAsLong();
		// A frequência conta as tentativas (não só as que dão certo): lixo que falha também gasta CPU.
		ArrayDeque<Long> attempts = recentUploads.computeIfAbsent(who, k -> new ArrayDeque<>());
		while (!attempts.isEmpty() && now - attempts.peekFirst() > UPLOAD_RATE_WINDOW_MS) {
			attempts.pollFirst();
		}
		if (attempts.size() >= UPLOAD_RATE) {
			return refusal("handyphone.notice.photo.rate");
		}
		attempts.addLast(now);

		if (!config.photosEnabled || !photosAvailable) {
			return refusal("handyphone.notice.photos_off");
		}
		if (isMuted(who)) {
			return refusal("handyphone.notice.muted_short");
		}
		Upload current = uploads.get(who);
		if (current != null) {
			if (current.processing || now - current.lastActivity <= UPLOAD_TIMEOUT_MS) {
				return refusal("handyphone.notice.photo.busy");
			}
			uploads.remove(who);
		}
		if (total < 1 || total > PhotoCodec.MAX_PHOTO_BYTES) {
			return refusal("handyphone.notice.photo.max_size", String.valueOf(PhotoCodec.MAX_PHOTO_BYTES / 1024));
		}
		if (!PhotoCodec.knownFormat(format) || (source != PhonePayloads.SOURCE_CAMERA && source != PhonePayloads.SOURCE_IMPORT)) {
			return refusal("handyphone.notice.photo.format");
		}
		if (source == PhonePayloads.SOURCE_IMPORT && !config.allowComputerUpload) {
			return refusal("handyphone.notice.photo.no_computer");
		}
		Refusal space = spaceError(who, total);
		if (space != null) {
			return space;
		}
		Upload upload = new Upload();
		upload.generation = wipeGeneration.getOrDefault(who, 0);
		upload.token = token;
		upload.total = total;
		upload.format = format;
		upload.source = source;
		upload.buffer = new byte[total];
		upload.lastActivity = now;
		uploads.put(who, upload);
		return null;
	}

	/** Confere cota do jogador, teto de fotos e espaço do servidor para mais {@code extra} bytes. Null = cabe. */
	private Refusal spaceError(UUID who, long extra) {
		if (photos.count(who) >= config.maxPhotosPerPlayer) {
			return refusal("handyphone.notice.photo.gallery_count", String.valueOf(config.maxPhotosPerPlayer));
		}
		long used = photos.usedBytes(who), quota = config.quotaBytes();
		if (used + extra > quota) {
			return refusal("handyphone.notice.photo.gallery_space", mb(used), mb(quota));
		}
		if (photos.totalBytes() + extra > config.totalBytes()) {
			return refusal("handyphone.notice.photo.server_full");
		}
		try {
			Files.createDirectories(photos.dir());
			if (Files.getFileStore(photos.dir()).getUsableSpace() < MIN_FREE_BYTES) {
				return refusal("handyphone.notice.photo.disk");
			}
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.warn("Couldn't check the free disk space of {}", photos.dir(), e);
		}
		return null;
	}

	private static String mb(long bytes) {
		return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
	}

	/** Recebe uma parte do envio. Retorna {@link #STRAY} se não há envio ativo, o motivo se a parte é inválida, ou null. */
	private Refusal uploadChunk(UUID who, int index, byte[] data) {
		Upload upload = uploads.get(who);
		if (upload == null || upload.processing) {
			return STRAY;
		}
		if (index != upload.nextIndex) {
			return refusal("handyphone.notice.photo.order");
		}
		if (data.length < 1 || data.length > PhonePayloads.MAX_CHUNK || (long) upload.received + data.length > upload.total) {
			return refusal("handyphone.notice.photo.chunk");
		}
		System.arraycopy(data, 0, upload.buffer, upload.received, data.length);
		upload.received += data.length;
		upload.nextIndex++;
		upload.lastActivity = System.currentTimeMillis();
		return null;
	}

	/** Fecha o envio e manda a foto para processar (validar, recodificar, gravar). */
	private Refusal endUpload(UUID who, int token) {
		Upload upload = uploads.get(who);
		if (upload == null || upload.processing || upload.token != token) {
			return STRAY;
		}
		if (upload.received != upload.total) {
			return refusal("handyphone.notice.photo.incomplete");
		}
		upload.processing = true;
		upload.lastActivity = System.currentTimeMillis();
		// O id é reservado já, na thread do servidor, para a thread de processamento gravar os arquivos com ele.
		long id = Math.max(data.nextPhotoId, photos.maxIdOnDisk() + 1);
		data.nextPhotoId = id + 1;
		accountsDirty = true;
		try {
			photoWorker.execute(() -> processUpload(who, upload, id));
		} catch (RejectedExecutionException e) {
			uploads.remove(who);
			return refusal("handyphone.notice.photo.worker_busy");
		}
		return null;
	}

	/** Roda na thread de processamento: nada de estado do servidor aqui, só os bytes e o disco. */
	private void processUpload(UUID who, Upload upload, long id) {
		PhotoCodec.Stored stored = null;
		Refusal error = null;
		try {
			stored = PhotoCodec.normalize(upload.buffer, upload.format);
			photos.writeFiles(id, PhotoCodec.FORMAT_JPEG, stored.full(), stored.thumb());
		} catch (PhotoCodec.PhotoException e) {
			error = refusal(e.key(), e.args());
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.error("Couldn't write photo {} from {}", id, who, e);
			error = refusal("handyphone.notice.photo.write");
		} catch (Throwable e) {
			// Qualquer falha (até um Error do decodificador) precisa chegar ao finishUpload, senão o envio fica preso em "processando".
			HandyPhoneCore.LOGGER.error("Failed to process photo {} from {}", id, who, e);
			error = refusal("handyphone.notice.photo.process");
		}
		PhotoCodec.Stored result = stored;
		Refusal failure = error;
		server.execute(() -> finishUpload(who, upload, id, result, failure));
	}

	/** De volta à thread do servidor: confere a cota de novo (outro envio pode ter chegado antes) e registra a foto. */
	private void finishUpload(UUID who, Upload upload, long id, PhotoCodec.Stored stored, Refusal failure) {
		// Durante o desligamento o servidor roda as tarefas na própria thread de quem agendou: nesse caso o estado
		// não pode ser tocado aqui. A foto é descartada e os arquivos que sobrarem são limpos no próximo carregamento.
		if (instance != this || !server.isServerThread()) {
			if (stored != null) {
				photos.discardFiles(id, PhotoCodec.FORMAT_JPEG);
			}
			return;
		}
		uploads.remove(who, upload);
		if (upload.generation != wipeGeneration.getOrDefault(who, 0)) {
			// Um wipe da conta aconteceu enquanto a foto era processada: ela não pode reaparecer.
			if (stored != null) {
				photos.discardFiles(id, PhotoCodec.FORMAT_JPEG);
			}
			return;
		}
		Refusal error = failure;
		if (error == null) {
			error = spaceError(who, (long) stored.full().length + stored.thumb().length);
		}
		if (error == null) {
			try {
				PhotoStore.Photo photo = photos.register(
					id, who, PhotoCodec.FORMAT_JPEG, stored.full().length, stored.thumb().length, stored.width(), stored.height(),
					upload.source == PhonePayloads.SOURCE_IMPORT
				);
				HandyPhoneCore.LOGGER.info(
					"Photo {} uploaded by {} ({} bytes, {}x{}, source {})", photo.id, who, photo.size, photo.width, photo.height,
					upload.source == PhonePayloads.SOURCE_IMPORT ? "computer" : "camera"
				);
				PhonePlayer online = server.player(who);
				if (online != null) {
					send(online, new PhonePayloads.UploadResult(upload.token, true, id));
					sendGallery(online);
				}
				return;
			} catch (IOException | RuntimeException e) {
				HandyPhoneCore.LOGGER.error("Couldn't register photo {} from {}", id, who, e);
				error = refusal("handyphone.notice.photo.write");
			}
		}
		if (stored != null) {
			photos.discardFiles(id, PhotoCodec.FORMAT_JPEG);
		}
		failUpload(who, upload.token, error);
	}

	/** Descarta os envios parados há mais que o limite. Chamado periodicamente e pelos testes. */
	private void sweepUploads(long now) {
		var it = uploads.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			Upload upload = entry.getValue();
			if (!upload.processing && now - upload.lastActivity > UPLOAD_TIMEOUT_MS) {
				it.remove();
				failUpload(entry.getKey(), upload.token, refusal("handyphone.notice.photo.timeout"));
			}
		}
	}

	// ------------------------------------------------------------------ fotos: galeria, download e exclusão

	/** Manda ao jogador a lista de fotos dele e a situação da cota. */
	private void sendGallery(PhonePlayer player) {
		List<PhotoEntry> list = new ArrayList<>();
		for (PhotoStore.Photo photo : photos.gallery(player.getUUID())) {
			if (list.size() < PhonePayloads.MAX_GALLERY) {
				list.add(new PhotoEntry(photo.id, photo.time, photo.size));
			}
		}
		boolean enabled = config.photosEnabled && photosAvailable;
		send(player, new PhonePayloads.Gallery(
			list, photos.usedBytes(player.getUUID()), enabled ? config.quotaBytes() : 0L, enabled && config.allowComputerUpload
		));
	}

	/** O jogador pode baixar a foto se é dele ou se ela foi enviada em uma conversa de que ele participa. */
	private boolean canView(UUID who, PhotoStore.Photo photo) {
		if (photo.owner.equals(who)) {
			return true;
		}
		Map<Long, Integer> refs = photoRefs.get(photo.id);
		if (refs != null) {
			for (long conversationId : refs.keySet()) {
				Conversation conversation = conversations.get(conversationId);
				if (conversation != null && conversation.members.contains(who)) {
					return true;
				}
			}
		}
		return false;
	}

	private void onPhotoGet(PhonePlayer player, PhonePayloads.PhotoGet packet) {
		PhotoStore.Photo photo = photos.get(packet.id());
		// "Não existe" e "não é seu" recebem a mesma resposta, para ninguém descobrir quais ids existem.
		if (photo == null || !canView(player.getUUID(), photo)) {
			send(player, new PhonePayloads.PhotoUnavailable(packet.id(), packet.thumb(), false));
			return;
		}
		long cost = packet.thumb() ? photo.thumbSize : photo.size;
		if (!downloadBuckets.computeIfAbsent(player.getUUID(), k -> new Bucket()).take(cost)) {
			send(player, new PhonePayloads.PhotoUnavailable(packet.id(), packet.thumb(), true));
			return;
		}
		byte[] bytes;
		try {
			bytes = photos.read(photo, packet.thumb());
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.warn("Couldn't read photo {}", photo.id, e);
			send(player, new PhonePayloads.PhotoUnavailable(packet.id(), packet.thumb(), false));
			return;
		}
		int count = (bytes.length + PhonePayloads.MAX_CHUNK - 1) / PhonePayloads.MAX_CHUNK;
		for (int i = 0; i < count; i++) {
			int from = i * PhonePayloads.MAX_CHUNK, to = Math.min(bytes.length, from + PhonePayloads.MAX_CHUNK);
			send(player, new PhonePayloads.PhotoData(photo.id, packet.thumb(), i, count, java.util.Arrays.copyOfRange(bytes, from, to)));
		}
	}

	private void onPhotoDelete(PhonePlayer player, PhonePayloads.PhotoDelete packet) {
		PhotoStore.Photo photo = photos.get(packet.id());
		if (photo != null && photo.owner.equals(player.getUUID())) {
			photos.remove(photo);
			photoRefs.remove(photo.id);
			sendGallery(player);
		}
	}

	// ------------------------------------------------------------------ fotos: pontos de entrada para testes e administração

	/**
	 * Guarda uma foto em nome de uma conta, sem passar pela rede, com a mesma validação do envio
	 * real. Deve ser chamado na thread do servidor. Retorna o id, ou -1 se foi recusada.
	 */
	public static long storePhoto(UUID owner, byte[] jpeg) {
		PhoneService service = instance;
		if (service == null || service.locked) {
			return -1;
		}
		numberFor(owner);
		try {
			PhotoCodec.Stored stored = PhotoCodec.normalize(jpeg, PhotoCodec.FORMAT_JPEG);
			if (service.spaceError(owner, (long) stored.full().length + stored.thumb().length) != null) {
				return -1;
			}
			long id = Math.max(service.data.nextPhotoId, service.photos.maxIdOnDisk() + 1);
			service.data.nextPhotoId = id + 1;
			service.accountsDirty = true;
			service.photos.writeFiles(id, PhotoCodec.FORMAT_JPEG, stored.full(), stored.thumb());
			service.photos.register(id, owner, PhotoCodec.FORMAT_JPEG, stored.full().length, stored.thumb().length, stored.width(), stored.height(), false);
			PhonePlayer online = service.server.player(owner);
			if (online != null) {
				service.sendGallery(online);
			}
			return id;
		} catch (PhotoCodec.PhotoException | IOException | RuntimeException e) {
			return -1;
		}
	}

	/** Envia em nome de uma conta, sem rede, uma foto da galeria dela para outro número. Retorna false se não pôde. */
	public static boolean deliverDirectPhoto(UUID sender, String toNumber, long photoId) {
		PhoneService service = instance;
		UUID target = service != null && !service.locked ? service.byNumber.get(digits(toNumber)) : null;
		PhotoStore.Photo photo = service != null ? service.photos.get(photoId) : null;
		if (target == null || target.equals(sender) || photo == null || !photo.owner.equals(sender)) {
			return false;
		}
		numberFor(sender);
		return service.post(service.direct(sender, target), sender, "", photoId);
	}

	/** Início de envio de foto em nome de uma conta (para testar a validação do servidor com dados ruins). Null = aceito. */
	public static String testUploadBegin(UUID who, int token, int total, int format, int source) {
		PhoneService service = instance;
		return service == null ? "no server" : english(service.beginUpload(who, token, total, format, source));
	}

	/** Parte de envio em nome de uma conta. Null = aceita; "stray" = sem envio ativo; senão, o motivo (e o envio é cancelado). */
	public static String testUploadChunk(UUID who, int index, byte[] data) {
		PhoneService service = instance;
		if (service == null) {
			return "no server";
		}
		Refusal error = service.uploadChunk(who, index, data);
		if (error != null && error != STRAY) {
			service.uploads.remove(who);
		}
		return english(error);
	}

	/** Fim de envio em nome de uma conta; o resultado aparece depois, na galeria (processamento em outra thread). */
	public static String testUploadEnd(UUID who, int token) {
		PhoneService service = instance;
		if (service == null) {
			return "no server";
		}
		Refusal error = service.endUpload(who, token);
		if (error != null && error != STRAY) {
			service.uploads.remove(who);
		}
		return english(error);
	}

	private static String english(Refusal refusal) {
		return refusal == null ? null : refusal == STRAY ? "stray" : refusal.english();
	}

	/** Descarta os envios parados, fingindo que a hora é {@code nowMillis}. */
	public static void testSweepUploads(long nowMillis) {
		PhoneService service = instance;
		if (service != null) {
			service.sweepUploads(nowMillis);
		}
	}

	public static boolean hasUpload(UUID who) {
		PhoneService service = instance;
		return service != null && service.uploads.containsKey(who);
	}

	public static int photoCount(UUID owner) {
		PhoneService service = instance;
		return service == null ? 0 : service.photos.count(owner);
	}

	public static long photoBytesUsed(UUID owner) {
		PhoneService service = instance;
		return service == null ? 0 : service.photos.usedBytes(owner);
	}

	/** Ids das fotos da galeria de uma conta, da mais recente para a mais antiga. */
	public static List<Long> galleryIds(UUID owner) {
		PhoneService service = instance;
		List<Long> ids = new ArrayList<>();
		if (service != null) {
			for (PhotoStore.Photo photo : service.photos.gallery(owner)) {
				ids.add(photo.id);
			}
		}
		return ids;
	}

	/** Os bytes guardados de uma foto (ou da miniatura), ou null se não existe. */
	public static byte[] photoBytes(long id, boolean thumb) {
		PhoneService service = instance;
		PhotoStore.Photo photo = service != null ? service.photos.get(id) : null;
		try {
			return photo != null ? service.photos.read(photo, thumb) : null;
		} catch (IOException e) {
			return null;
		}
	}

	/** Se a conta pode baixar a foto (é dona, ou participa de uma conversa em que ela foi enviada). */
	public static boolean canViewPhoto(UUID who, long id) {
		PhoneService service = instance;
		PhotoStore.Photo photo = service != null ? service.photos.get(id) : null;
		return photo != null && service.canView(who, photo);
	}

	/** Apaga uma foto em nome do dono, como o pacote de exclusão faria. */
	public static boolean deletePhoto(UUID owner, long id) {
		PhoneService service = instance;
		PhotoStore.Photo photo = service != null ? service.photos.get(id) : null;
		if (photo == null || !photo.owner.equals(owner)) {
			return false;
		}
		service.photos.remove(photo);
		service.photoRefs.remove(id);
		PhonePlayer online = service.server.player(owner);
		if (online != null) {
			service.sendGallery(online);
		}
		return true;
	}

	/** Quantas mensagens com foto existem agora, somando todas as conversas. */
	public static int photoMessages() {
		PhoneService service = instance;
		int total = 0;
		if (service != null) {
			for (Conversation conversation : service.conversations.values()) {
				for (StoredMessage message : conversation.messages) {
					if (message.photo != 0) {
						total++;
					}
				}
			}
		}
		return total;
	}

	/** Reenvia a galeria a um jogador online (depois de mudar as opções do servidor, por exemplo). */
	public static void resendGallery(UUID who) {
		PhoneService service = instance;
		PhonePlayer online = service != null ? service.server.player(who) : null;
		if (online != null) {
			service.sendGallery(online);
		}
	}

	/**
	 * Salva tudo e recarrega todo o estado do disco, como numa reinicialização do servidor. Serve
	 * para testar a compatibilidade com dados antigos e a recuperação depois de uma queda.
	 */
	public static void reloadFromDisk() {
		reloadFromDisk(true);
	}

	/** Igual, escolhendo se salva antes (os testes de arquivo corrompido não podem deixar o salvamento sobrescrever o arquivo). */
	public static void reloadFromDisk(boolean saveFirst) {
		PhoneService service = instance;
		if (service == null) {
			return;
		}
		if (saveFirst) {
			service.save();
		} else {
			service.awaitWrites();
		}
		service.byNumber.clear();
		service.conversations.clear();
		service.conversationsOf.clear();
		service.photoRefs.clear();
		service.deletedConversations.clear();
		service.dirtyConversations.clear();
		service.accountsDirty = false;
		service.data = new AccountsFile();
		service.photos = new PhotoStore(service.dir);
		service.load();
	}

	/** Só para a medição de desempenho ({@code -Pperf}): roda {@code times} ticks do HandyPhone e devolve os nanossegundos gastos. */
	public static long perfTicks(int times) {
		PhoneService service = instance;
		if (service == null) {
			return -1;
		}
		long start = System.nanoTime();
		for (int i = 0; i < times; i++) {
			service.tick();
		}
		return System.nanoTime() - start;
	}

	/**
	 * Só para a medição de desempenho: o que um salvamento do mundo gasta na thread do servidor. Com {@code everything},
	 * marca contas e todas as conversas como alteradas antes (o pior caso); senão, só as {@code dirtyCount} primeiras.
	 */
	public static long perfSave(boolean everything, int dirtyCount, boolean async) {
		PhoneService service = instance;
		if (service == null) {
			return -1;
		}
		service.accountsDirty = true;
		if (everything) {
			service.dirtyConversations.addAll(service.conversations.keySet());
		} else {
			service.conversations.keySet().stream().limit(dirtyCount).forEach(service.dirtyConversations::add);
		}
		long start = System.nanoTime();
		service.save(async);
		long spent = System.nanoTime() - start;
		if (async) {
			// O que o servidor gastou é só o de cima; depois espera a fila para a próxima medição partir do disco em dia.
			service.awaitWrites();
		}
		return spent;
	}

	/** Só para a medição de desempenho: recarrega tudo do disco sem salvar antes e devolve os nanossegundos gastos. */
	public static long perfReload() {
		long start = System.nanoTime();
		reloadFromDisk(false);
		return System.nanoTime() - start;
	}

	/** O próximo id de foto que seria usado agora. */
	public static long nextPhotoId() {
		PhoneService service = instance;
		return service == null ? -1 : Math.max(service.data.nextPhotoId, service.photos.maxIdOnDisk() + 1);
	}

	/** O serviço de localização (os testes chamam os manipuladores direto, com dados ruins). */
	public static LocationService location() {
		PhoneService service = instance;
		return service != null ? service.location : null;
	}

	/** As opções do servidor em uso (os testes mudam os valores). */
	public static PhoneConfig config() {
		PhoneService service = instance;
		return service != null ? service.config : new PhoneConfig();
	}

	/** Diretório das fotos no mundo (para os testes conferirem os arquivos). */
	public static Path photosDir() {
		PhoneService service = instance;
		return service != null ? service.photos.dir() : null;
	}

	// ------------------------------------------------------------------ moderação (comando /handyphone)

	/** Resposta de uma ação de administração: o texto para quem digitou o comando e se deu certo. */
	public record AdminResult(boolean ok, Msg msg) {
		/** O texto em inglês (testes e logs); quem digita o comando recebe {@link Msg#component()}. */
		public String message() {
			return msg.english();
		}
	}

	private static final AdminResult OFF = new AdminResult(false, new Msg("handyphone.command.off"));
	private static final AdminResult LOCKED = new AdminResult(false, new Msg("handyphone.command.locked"));
	private static final AdminResult NO_ACCOUNT = new AdminResult(false, new Msg("handyphone.command.no_account"));

	/** Silencia uma conta: ela não envia mais nada pelo celular. {@code minutes} <= 0 = sem prazo. */
	public static AdminResult adminMute(UUID target, long minutes) {
		PhoneService service = instance;
		if (service == null) {
			return OFF;
		}
		if (service.locked) {
			return LOCKED;
		}
		Account account = service.data.accounts.get(target);
		if (account == null) {
			return NO_ACCOUNT;
		}
		long now = service.clock.getAsLong();
		account.mutedUntil = minutes > 0 ? now + Math.min(minutes, 5L * 365 * 24 * 60) * 60_000L : Long.MAX_VALUE;
		service.accountsDirty = true;
		// Um compartilhamento ao vivo em andamento também para: silenciado não manda nada pelo celular.
		service.location.stopShare(target);
		// A voz dele sai das ligações na hora (a ligação continua; ele só deixa de ser ouvido).
		service.calls.onMuteChanged(target);
		return new AdminResult(true, minutes > 0 ? new Msg("handyphone.command.mute.timed", minutes) : new Msg("handyphone.command.mute.forever"));
	}

	public static AdminResult adminUnmute(UUID target) {
		PhoneService service = instance;
		if (service == null) {
			return OFF;
		}
		if (service.locked) {
			return LOCKED;
		}
		Account account = service.data.accounts.get(target);
		if (account == null) {
			return NO_ACCOUNT;
		}
		boolean was = account.mutedUntil > service.clock.getAsLong();
		account.mutedUntil = 0;
		service.accountsDirty = true;
		service.calls.onMuteChanged(target);
		return new AdminResult(true, new Msg(was ? "handyphone.command.unmute.done" : "handyphone.command.unmute.not_muted"));
	}

	/** Um resumo do que a conta tem (para o administrador decidir). */
	public static AdminResult adminInfo(UUID target) {
		PhoneService service = instance;
		if (service == null) {
			return OFF;
		}
		if (service.locked) {
			return LOCKED;
		}
		Account account = service.data.accounts.get(target);
		if (account == null) {
			return NO_ACCOUNT;
		}
		int sent = 0, groups = 0;
		for (Conversation c : service.conversations.values()) {
			if (target.equals(c.creator)) {
				groups++;
			}
			for (StoredMessage m : c.messages) {
				if (target.equals(m.sender)) {
					sent++;
				}
			}
		}
		long muted = account.mutedUntil - service.clock.getAsLong();
		Msg mute = muted <= 0 ? new Msg("handyphone.command.info.mute.no")
			: account.mutedUntil == Long.MAX_VALUE ? new Msg("handyphone.command.info.mute.forever")
			: new Msg("handyphone.command.info.mute.minutes", muted / 60_000 + 1);
		return new AdminResult(true, new Msg("handyphone.command.info", formatNumber(account.number), mute,
			service.conversationsOf.getOrDefault(target, Set.of()).size(), sent, groups, service.photos.count(target), account.places.size(),
			account.blocked.size(), service.calls.describe(target)));
	}

	/**
	 * Apaga o que a conta enviou: as mensagens dela em todas as conversas, os grupos que ela criou, as fotos e os locais
	 * salvos. Antes de apagar, <b>arquiva</b> tudo em {@code <mundo>/handyphone/moderacao/} (o administrador pode precisar
	 * da prova); se o arquivo não puder ser gravado, nada é apagado. Também para o compartilhamento ao vivo e descarta
	 * qualquer foto que a conta esteja enviando agora.
	 */
	public static AdminResult adminWipe(UUID target) {
		PhoneService service = instance;
		if (service == null) {
			return OFF;
		}
		if (service.locked) {
			return LOCKED;
		}
		return service.wipe(target);
	}

	private AdminResult wipe(UUID target) {
		Account account = data.accounts.get(target);
		if (account == null) {
			return NO_ACCOUNT;
		}
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path archive = dir.resolve("moderacao").resolve(stamp + "-" + (System.nanoTime() % 100000) + "-" + target);
		List<Conversation> groupsToDelete = new ArrayList<>();
		List<Conversation> affected = new ArrayList<>();
		Map<String, Object> proof = new LinkedHashMap<>();
		List<Object> messageLog = new ArrayList<>();
		int messages = 0;
		for (Conversation c : conversations.values()) {
			if (target.equals(c.creator)) {
				groupsToDelete.add(c);
				continue;
			}
			boolean touched = false;
			for (StoredMessage m : c.messages) {
				if (target.equals(m.sender)) {
					messageLog.add(Map.of("conversa", c.id, "seq", m.seq, "hora", m.time, "texto", m.text == null ? "" : m.text, "foto", m.photo,
						"localizacao", m.loc == null ? "" : m.loc.dim + " " + m.loc.x + " " + m.loc.y + " " + m.loc.z + (m.loc.label != null ? " " + m.loc.label : "")));
					messages++;
					touched = true;
				}
			}
			if (touched) {
				affected.add(c);
			}
		}
		proof.put("jogador", target.toString());
		proof.put("numero", account.number);
		proof.put("quando", stamp);
		proof.put("mensagens", messageLog);
		proof.put("locais", account.places);
		List<PhotoStore.Photo> gallery = photos.gallery(target);
		List<Object> photoLog = new ArrayList<>();
		for (PhotoStore.Photo photo : gallery) {
			photoLog.add(Map.of("id", photo.id, "hora", photo.time, "bytes", photo.size, "doComputador", photo.imported));
		}
		proof.put("fotos", photoLog);
		try {
			Files.createDirectories(archive);
			write(archive.resolve("wipe.json"), proof);
			for (Conversation group : groupsToDelete) {
				write(archive.resolve("grupo-" + group.id + ".json"), group);
			}
			for (PhotoStore.Photo photo : gallery) {
				photos.copyTo(photo, archive.resolve("fotos"));
			}
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: the wipe of {} was CANCELLED: couldn't archive the evidence in {}", target, archive, e);
			return new AdminResult(false, new Msg("handyphone.command.wipe.failed", archive));
		}

		// Daqui em diante apaga. Primeiro corta o que está em andamento.
		calls.onWipe(target);
		location.stopShare(target);
		uploads.remove(target);
		wipeGeneration.merge(target, 1, Integer::sum);
		for (Conversation group : groupsToDelete) {
			deleteConversation(group);
		}
		for (Conversation c : affected) {
			for (StoredMessage m : c.messages) {
				if (target.equals(m.sender)) {
					removeRef(m.photo, c.id);
				}
			}
			c.messages.removeIf(m -> target.equals(m.sender));
			dirtyConversations.add(c.id);
		}
		for (PhotoStore.Photo photo : gallery) {
			photos.remove(photo);
			photoRefs.remove(photo.id);
		}
		int places = account.places.size();
		account.places.clear();
		account.calls.clear();
		accountsDirty = true;

		// Quem está online recebe a conversa já sem as mensagens (o celular guarda o que já tinha).
		for (Conversation c : affected) {
			for (UUID member : c.members) {
				PhonePlayer online = server.player(member);
				if (online != null) {
					sendHistory(online, c, false, 0);
				}
			}
		}
		PhonePlayer wiped = server.player(target);
		if (wiped != null) {
			sendGallery(wiped);
			location.sendPlacesTo(wiped);
			sync(wiped);
		}
		HandyPhoneCore.LOGGER.info(
			"HandyPhone: wipe of {}: {} messages, {} groups, {} photos and {} places deleted; evidence in {}",
			target, messages, groupsToDelete.size(), gallery.size(), places, archive
		);
		return new AdminResult(true, new Msg("handyphone.command.wipe.done", messages, groupsToDelete.size(), gallery.size(), places, archive));
	}

	// ------------------------------------------------------------------ ganchos para testes

	/** Troca o relógio das janelas de frequência e do silenciamento (null volta ao relógio real). */
	public static void useClock(LongSupplier replacement) {
		PhoneService service = instance;
		if (service != null) {
			service.clock = replacement != null ? replacement : System::currentTimeMillis;
		}
	}

	/** Troca o orçamento de pacotes (os testes usam relógios fingidos) e devolve o que estava em uso. */
	public static PacketBudget useBudget(PacketBudget replacement) {
		PhoneService service = instance;
		if (service == null) {
			return null;
		}
		PacketBudget old = service.budget;
		service.budget = replacement != null ? replacement : new PacketBudget();
		return old;
	}

	public static boolean isLocked() {
		PhoneService service = instance;
		return service != null && service.locked;
	}

	/** Quantas conversas a conta vê na lista (as que contam para o teto). */
	public static int listedConversations(UUID who) {
		PhoneService service = instance;
		return service == null ? 0 : service.listedCount(who);
	}

	/** Em quantas conversas a conta é membro, vendo-as ou não. */
	public static int memberships(UUID who) {
		PhoneService service = instance;
		return service == null ? 0 : service.conversationsOf.getOrDefault(who, Set.of()).size();
	}

	/** O estado completo que seria mandado a uma conta agora (para conferir o tamanho). */
	public static PhonePayloads.Sync syncFor(UUID who) {
		PhoneService service = instance;
		return service == null || service.data.accounts.get(who) == null ? null : service.buildSync(who);
	}

	/** Reenvia a lista de conversas a um jogador online. */
	public static void resync(UUID who) {
		PhoneService service = instance;
		PhonePlayer online = service != null ? service.server.player(who) : null;
		if (online != null) {
			service.sync(online);
		}
	}

	/** Bloqueia sem rede (testes). */
	public static boolean blockFor(UUID blocker, UUID target) {
		PhoneService service = instance;
		Account account = service != null ? service.data.accounts.get(blocker) : null;
		if (account == null || blocker.equals(target)) {
			return false;
		}
		if (!account.blocked.contains(target)) {
			account.blocked.add(target);
			service.accountsDirty = true;
		}
		// Como a rota de bloqueio: uma ligação entre os dois acaba (ou deixa de tocar) na hora.
		service.calls.onBlockChanged(blocker, target);
		return true;
	}

	/** Desfaz {@link #blockFor} (testes). */
	public static void unblockFor(UUID blocker, UUID target) {
		PhoneService service = instance;
		Account account = service != null ? service.data.accounts.get(blocker) : null;
		if (account != null && account.blocked.remove(target)) {
			service.accountsDirty = true;
		}
	}

	/** Uma mensagem de texto de uma conta em uma conversa dela, sem rede (testes de grupo). Retorna false se foi recusada. */
	public static boolean postAs(UUID sender, long conversation, String text) {
		PhoneService service = instance;
		Conversation c = service != null ? service.memberConversation(sender, conversation) : null;
		return c != null && service.post(c, sender, clean(text, PhonePayloads.MAX_TEXT), 0L);
	}

	/** Quem tem este número (as contas simuladas dos testes não têm entidade), ou null. */
	public static UUID uuidOfNumber(String number) {
		PhoneService service = instance;
		return service == null ? null : service.byNumber.get(digits(number));
	}

	/** O id da conversa individual entre duas contas, ou -1 se ainda não existe. */
	public static long directBetween(UUID a, UUID b) {
		PhoneService service = instance;
		Conversation c = service != null ? service.findDirect(a, b) : null;
		return c == null ? -1 : c.id;
	}

	/** O número da última mensagem de uma conversa (o {@code lastSeq}), ou -1. */
	public static int lastSeqOf(long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		return c == null ? -1 : c.lastSeq;
	}

	/** A conta sai de um grupo, sem rede (testes e administração). Retorna false se não é membro de um grupo com esse id. */
	public static boolean leaveGroupFor(UUID who, long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.memberConversation(who, conversation) : null;
		if (c == null || !c.group) {
			return false;
		}
		service.leaveGroup(who, c);
		return true;
	}

	/** A conta está silenciada agora? */
	public static boolean isMutedNow(UUID who) {
		PhoneService service = instance;
		return service != null && service.isMuted(who);
	}

	/** Quantas mensagens da conversa a conta pode ver. */
	public static int visibleMessages(UUID viewer, long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		int count = 0;
		if (c != null) {
			for (StoredMessage m : c.messages) {
				if (visibleTo(viewer, m)) {
					count++;
				}
			}
		}
		return count;
	}

	/** Quantas mensagens a conversa guarda no total (inclusive as escondidas de alguém). */
	public static int storedIn(long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		return c == null ? -1 : c.messages.size();
	}

	public static boolean conversationExists(long id) {
		PhoneService service = instance;
		return service != null && service.conversations.containsKey(id);
	}

	/** Os UUIDs dos membros de uma conversa, ou null se ela não existe. */
	public static List<UUID> membersOf(long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		return c == null ? null : List.copyOf(c.members);
	}

	/** Se a conversa aparece na lista da conta. */
	public static boolean listedFor(UUID who, long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		return c != null && service.listedFor(who, c);
	}

	/** Não lidas da conta numa conversa (só as que ela pode ver). */
	public static int unreadIn(UUID who, long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		Account account = service != null ? service.data.accounts.get(who) : null;
		return c == null || account == null ? -1 : service.unreadFor(who, c, account.read.getOrDefault(conversation, 0));
	}

	/** O texto da última mensagem visível para a conta numa conversa, ou null. */
	public static String lastVisibleText(UUID who, long conversation) {
		PhoneService service = instance;
		Conversation c = service != null ? service.conversations.get(conversation) : null;
		StoredMessage last = c != null ? service.lastVisible(who, c) : null;
		return last == null ? null : last.text;
	}

	/** Número do telefone de uma conta, ou "" (para os testes montarem pacotes). */
	public static String numberOfAccount(UUID who) {
		PhoneService service = instance;
		return service == null ? "" : service.numberOf(who);
	}

	/** Tira dos mapas de frequência os jogadores que já saíram e não têm mais nada na janela. */
	private void pruneRateState() {
		long now = clock.getAsLong();
		recentSends.entrySet().removeIf(e -> online(e.getKey()) == null && (e.getValue().isEmpty() || now - e.getValue().peekLast() > RATE_WINDOW_MS));
		groupCreates.entrySet().removeIf(e -> online(e.getKey()) == null && (e.getValue().isEmpty() || now - e.getValue().peekLast() > 3_600_000L));
		newNumbers.entrySet().removeIf(e -> {
			e.getValue().values().removeIf(time -> now - time > NEW_NUMBERS_WINDOW_MS);
			return e.getValue().isEmpty() && online(e.getKey()) == null;
		});
		calls.prune();
		budget.retainOnly(server.players().stream().map(PhonePlayer::getUUID).collect(java.util.stream.Collectors.toSet()));
	}

	// ------------------------------------------------------------------ arquivos

	private static final Pattern CONVERSATION_FILE = Pattern.compile("(\\d{1,18})\\.json.*");

	private Path accountsPath() {
		return dir.resolve("accounts.json");
	}

	private Path accountsBackupPath() {
		return dir.resolve("accounts.json.bak");
	}

	private Path conversationPath(long id) {
		return dir.resolve("conversations").resolve(id + ".json");
	}

	/** Lê o arquivo de contas; null se não pôde (o motivo vai para o log). */
	private AccountsFile readAccounts(Path file) {
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			AccountsFile loaded = GSON.fromJson(reader, AccountsFile.class);
			if (loaded != null && loaded.accounts != null) {
				return loaded;
			}
			HandyPhoneCore.LOGGER.error("HandyPhone: {} is empty or has no account list", file);
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: couldn't read {}", file, e);
		}
		return null;
	}

	/** "123456" vira "123-456". */
	private static String formatNumber(String digits) {
		return digits.length() > 3 ? digits.substring(0, 3) + "-" + digits.substring(3) : digits;
	}

	/** Tira do caminho um arquivo ruim (renomeia para {@code .corrompido-<data>}): fica guardado, nunca é lido nem sobrescrito. */
	private Path moveAside(Path file) {
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path target = file.resolveSibling(file.getFileName() + ".corrompido-" + stamp);
		try {
			Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
			return target;
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: couldn't set {} aside as corrupted", file, e);
			return null;
		}
	}

	/** Guarda uma cópia de um arquivo ruim ao lado dele ({@code .corrompido-<data>}); nunca o sobrescreve. */
	private Path quarantine(Path file) {
		String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path copy = file.resolveSibling(file.getFileName() + ".corrompido-" + stamp);
		try {
			Files.copy(file, copy, StandardCopyOption.REPLACE_EXISTING);
			return copy;
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: couldn't keep a copy of {}", file, e);
			return null;
		}
	}

	/**
	 * Lê tudo do disco. As três partes (contas, fotos, conversas) carregam cada uma por si: uma falha em uma não
	 * derruba as outras.
	 *
	 * <p><b>Falha fechada.</b> Se {@code accounts.json} existe e não pode ser lido, o arquivo é copiado para
	 * {@code accounts.json.corrompido-<data>} e usa-se a cópia {@code accounts.json.bak} (gravada a cada salvamento);
	 * sem ela, o serviço fica TRAVADO: não grava nada, não trata pacotes e não cria contas. Seguir vazio faria o
	 * primeiro salvamento sobrescrever as contas e as conversas novas gravarem por cima das antigas.
	 */
	private void load() {
		locked = false;
		accountsFileTrusted = true;
		Path accountsFile = accountsPath();
		if (Files.exists(accountsFile)) {
			AccountsFile loaded = readAccounts(accountsFile);
			if (loaded == null) {
				Path copy = quarantine(accountsFile);
				Path backup = accountsBackupPath();
				AccountsFile fromBackup = Files.exists(backup) ? readAccounts(backup) : null;
				if (fromBackup != null) {
					HandyPhoneCore.LOGGER.error(
						"HandyPhone: accounts.json was unreadable. Using the accounts.json.bak copy (anything changed after it may be missing). The bad file was kept at {}",
						copy
					);
					loaded = fromBackup;
					accountsFileTrusted = false;
					// O accounts.json que está no disco é o ruim: o próximo salvamento precisa regravá-lo.
					accountsDirty = true;
				} else {
					locked = true;
					HandyPhoneCore.LOGGER.error(
						"HandyPhone: accounts.json in {} is UNREADABLE and there is no usable copy. THE PHONE STAYS OFF on this server and nothing will be written "
							+ "until you fix or restore the file and restart. A copy of the bad file was kept at {}",
						dir, copy
					);
				}
			}
			if (loaded != null) {
				data = loaded;
			}
		}
		data.accounts.values().removeIf(account -> account == null || account.number == null);
		data.accounts.forEach((uuid, account) -> {
			if (account.contacts == null) {
				account.contacts = new LinkedHashMap<>();
			}
			if (account.read == null) {
				account.read = new HashMap<>();
			}
			if (account.places == null) {
				account.places = new ArrayList<>();
			}
			if (account.blocked == null) {
				account.blocked = new ArrayList<>();
			}
			account.places.removeIf(place -> place == null || place.name == null || place.dim == null);
			for (Place place : account.places) {
				account.nextPlaceId = Math.max(account.nextPlaceId, place.id + 1);
			}
			byNumber.put(account.number, uuid);
		});

		try {
			photos.load();
		} catch (RuntimeException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: couldn't load the photos from {}; conversations and accounts carry on", dir, e);
		}
		data.nextPhotoId = Math.max(data.nextPhotoId, photos.maxIdOnDisk() + 1);

		loadConversations();
		HandyPhoneCore.LOGGER.info(
			"HandyPhone: loaded {} accounts, {} conversations and {} photos{}", data.accounts.size(), conversations.size(), photos.totalCount(),
			locked ? " (LOCKED: unreadable accounts)" : ""
		);
	}

	private void loadConversations() {
		Path folder = dir.resolve("conversations");
		if (!Files.isDirectory(folder)) {
			return;
		}
		long maxFileId = 0;
		List<Path> files;
		try (Stream<Path> listing = Files.list(folder)) {
			files = listing.toList();
		} catch (IOException e) {
			HandyPhoneCore.LOGGER.error("HandyPhone: couldn't list the conversations in {}", folder, e);
			return;
		}
		// Ler e interpretar cada arquivo é o que demora (o sistema de arquivos, sobretudo com antivírus): feito em paralelo; o resto, na ordem.
		List<Path> readable = new ArrayList<>();
		for (Path file : files) {
			String name = file.getFileName().toString();
			Matcher m = CONVERSATION_FILE.matcher(name);
			// O id de TODO arquivo conta, até o ilegível ou guardado como corrompido: um id novo nunca pode cair em cima dele.
			if (m.matches()) {
				maxFileId = Math.max(maxFileId, Long.parseLong(m.group(1)));
			}
			if (name.endsWith(".json")) {
				readable.add(file);
			}
		}
		for (LoadedConversation entry : readConversations(readable)) {
			Path file = entry.file();
			Conversation conversation = entry.conversation();
			readNanos += entry.readNanos();
			parseNanos += entry.parseNanos();
			if (entry.error() != null) {
				HandyPhoneCore.LOGGER.error("HandyPhone: corrupted conversation: {}", file, entry.error());
			}
			if (conversation == null || conversation.members == null || conversation.messages == null) {
				Path kept = locked ? null : moveAside(file);
				HandyPhoneCore.LOGGER.error("HandyPhone: unreadable conversation skipped: {}{}", file, kept != null ? " (copy at " + kept + ")" : "");
				continue;
			}
			if (conversation.left == null) {
				conversation.left = new ArrayList<>();
			}
			conversation.messages.removeIf(message -> message == null || message.text == null);
			index(conversation);
			maxFileId = Math.max(maxFileId, conversation.id);
		}
		data.nextConversationId = Math.max(data.nextConversationId, maxFileId + 1);
	}

	/** O resultado de ler um arquivo de conversa: a conversa (ou null) e o erro, se houve. */
	private record LoadedConversation(Path file, Conversation conversation, Exception error, long readNanos, long parseNanos) {
	}

	/** Poucos arquivos não compensam threads. */
	private static final int PARALLEL_LOAD_MIN = 64;

	private static LoadedConversation readConversation(Path file) {
		long t0 = System.nanoTime();
		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			long t1 = System.nanoTime();
			Conversation conversation = GSON.fromJson(json, Conversation.class);
			return new LoadedConversation(file, conversation, null, t1 - t0, System.nanoTime() - t1);
		} catch (IOException | RuntimeException e) {
			return new LoadedConversation(file, null, e, System.nanoTime() - t0, 0);
		}
	}

	/** Lê os arquivos (em paralelo quando são muitos), devolvendo os resultados na mesma ordem. */
	private static List<LoadedConversation> readConversations(List<Path> files) {
		List<LoadedConversation> result = new ArrayList<>(files.size());
		int threads = Math.min(8, Runtime.getRuntime().availableProcessors());
		if (files.size() < PARALLEL_LOAD_MIN || threads < 2) {
			for (Path file : files) {
				result.add(readConversation(file));
			}
			return result;
		}
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads, r -> {
			Thread thread = new Thread(r, "HandyPhone-Load");
			thread.setDaemon(true);
			return thread;
		});
		try {
			List<java.util.concurrent.Future<LoadedConversation>> futures = new ArrayList<>(files.size());
			for (Path file : files) {
				futures.add(pool.submit(() -> readConversation(file)));
			}
			for (java.util.concurrent.Future<LoadedConversation> future : futures) {
				result.add(future.get());
			}
			return result;
		} catch (InterruptedException | java.util.concurrent.ExecutionException | RuntimeException e) {
			// A leitura em paralelo não deve derrubar o carregamento: se algo deu errado, lê tudo de novo, em sequência.
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			HandyPhoneCore.LOGGER.warn("HandyPhone: the parallel read of the conversations failed; reading them one by one", e);
			result.clear();
			for (Path file : files) {
				result.add(readConversation(file));
			}
		} finally {
			pool.shutdownNow();
		}
		return result;
	}

	/** O accounts.json que está no disco foi lido sem erro ou gravado por nós? Só então vale como cópia de segurança. */
	private volatile boolean accountsFileTrusted = true;

	/** Um arquivo a gravar: o texto já está pronto (serializado na thread do servidor), só falta o disco. */
	private record WriteJob(Path file, String json, boolean backupFirst, long conversation) {
		static final long ACCOUNTS = -1;
	}

	/** O que uma rodada de gravações não conseguiu fazer: arquivos não gravados e arquivos de conversas apagadas que ficaram. */
	private record WriteFailures(List<WriteJob> writes, List<Long> deletes) {
		boolean any() {
			return !writes.isEmpty() || !deletes.isEmpty();
		}
	}

	/** Grava em fila, numa thread só e na ordem, o que o autosave serializou: o disco não prende o tick do servidor. */
	private final ThreadPoolExecutor diskWorker = new ThreadPoolExecutor(
		1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
			Thread thread = new Thread(r, "HandyPhone-Disk");
			thread.setDaemon(true);
			return thread;
		}
	);

	/**
	 * Espera as gravações em fila terminarem (antes de qualquer salvamento ou leitura que precise do disco em dia). Não desiste: seguir
	 * adiante com a fila ainda gravando faria dois escritores no mesmo arquivo. Um disco travado só atrasa (com um aviso a cada minuto).
	 * Funciona mesmo com a thread já interrompida (a interrupção é guardada e devolvida depois).
	 */
	private void awaitWrites() {
		boolean interrupted = Thread.interrupted();
		try {
			java.util.concurrent.Future<?> barrier;
			try {
				barrier = diskWorker.submit(() -> {
				});
			} catch (java.util.concurrent.RejectedExecutionException e) {
				return;
			}
			while (true) {
				try {
					barrier.get(60, TimeUnit.SECONDS);
					return;
				} catch (InterruptedException e) {
					interrupted = true;
				} catch (java.util.concurrent.TimeoutException e) {
					HandyPhoneCore.LOGGER.warn("HandyPhone: the queued disk writes are taking more than a minute; waiting");
				} catch (java.util.concurrent.ExecutionException e) {
					HandyPhoneCore.LOGGER.warn("HandyPhone: failed while waiting for the queued writes", e);
					return;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Salvamento completo e síncrono: usado ao parar o servidor, na moderação e nos testes. */
	private void save() {
		save(false);
	}

	/**
	 * Salva o que mudou. A serialização (CPU, rápida) é sempre feita aqui, na thread do servidor, para o texto ser uma foto
	 * imutável do estado; o disco (que no Windows, com antivírus, custa ~2 ms por arquivo) vai para a thread de gravação quando
	 * {@code async}. Quem grava de forma síncrona espera antes as gravações em fila, para a ordem no disco ser sempre a dos salvamentos.
	 * Se uma gravação falha, o que ela levava volta a ser "alterado" e o próximo salvamento tenta de novo.
	 */
	private void save(boolean async) {
		if (locked) {
			return;
		}
		if (!async) {
			awaitWrites();
		}
		List<WriteJob> jobs = new ArrayList<>();
		List<Long> deletes = new ArrayList<>(deletedConversations);
		try {
			if (accountsDirty) {
				// A cópia de segurança (feita antes de gravar) é o estado anterior, que sabemos estar bom.
				jobs.add(new WriteJob(accountsPath(), toJson(data), accountsFileTrusted, WriteJob.ACCOUNTS));
				accountsDirty = false;
			}
			for (Iterator<Long> it = dirtyConversations.iterator(); it.hasNext(); ) {
				long id = it.next();
				Conversation conversation = conversations.get(id);
				if (conversation != null) {
					jobs.add(new WriteJob(conversationPath(id), toJson(conversation), false, id));
				}
				it.remove();
			}
			// Conversas apagadas: o arquivo só sai agora, com os índices já gravados.
			deletedConversations.clear();
		} catch (RuntimeException e) {
			HandyPhoneCore.LOGGER.error("Couldn't save the HandyPhone data in {}", dir, e);
		}
		if (!jobs.isEmpty() || !deletes.isEmpty()) {
			Runnable task = () -> {
				WriteFailures failures = runJobs(jobs, deletes);
				if (failures.any()) {
					server.execute(() -> remark(failures));
				}
			};
			if (async) {
				diskWorker.execute(task);
			} else {
				task.run();
			}
		}
		// Só agora os arquivos de fotos apagadas saem do disco (o índice de fotos já foi gravado, ao apagar).
		photos.flushDeletes();
	}

	/** Grava (na ordem) o que foi serializado e apaga os arquivos das conversas apagadas; devolve o que não deu certo. */
	private WriteFailures runJobs(List<WriteJob> jobs, List<Long> deletes) {
		WriteFailures failures = new WriteFailures(new ArrayList<>(), new ArrayList<>());
		for (WriteJob job : jobs) {
			try {
				if (job.backupFirst() && Files.exists(job.file())) {
					try {
						Files.copy(job.file(), accountsBackupPath(), StandardCopyOption.REPLACE_EXISTING);
					} catch (IOException e) {
						HandyPhoneCore.LOGGER.warn("HandyPhone: couldn't update accounts.json.bak", e);
					}
				}
				writeText(job.file(), job.json());
				if (job.conversation() == WriteJob.ACCOUNTS) {
					// Só agora o accounts.json do disco é um arquivo nosso e bom: antes, se a gravação falhar, ele pode ser o ruim de que o serviço
					// partiu (aberto pela cópia .bak), e copiá-lo por cima do .bak estragaria a única cópia boa.
					accountsFileTrusted = true;
				}
			} catch (IOException | RuntimeException e) {
				HandyPhoneCore.LOGGER.error("Couldn't save {} of HandyPhone", job.file(), e);
				failures.writes().add(job);
			}
		}
		for (long id : deletes) {
			try {
				Files.deleteIfExists(conversationPath(id));
			} catch (IOException | RuntimeException e) {
				HandyPhoneCore.LOGGER.error("Couldn't delete the conversation file {}", id, e);
				failures.deletes().add(id);
			}
		}
		return failures;
	}

	/** Volta a marcar como "alterado" o que uma gravação não conseguiu levar ao disco (thread do servidor). */
	private void remark(WriteFailures failures) {
		for (WriteJob job : failures.writes()) {
			if (job.conversation() == WriteJob.ACCOUNTS) {
				accountsDirty = true;
			} else if (conversations.containsKey(job.conversation())) {
				dirtyConversations.add(job.conversation());
			}
		}
		for (long id : failures.deletes()) {
			if (!conversations.containsKey(id)) {
				deletedConversations.add(id);
			}
		}
	}

	private static String toJson(Object value) {
		long start = System.nanoTime();
		String json = GSON.toJson(value);
		serializeNanos += System.nanoTime() - start;
		return json;
	}

	/** Grava em arquivo temporário e troca no fim (troca atômica), para uma queda no meio não corromper o arquivo. */
	private static void write(Path file, Object value) throws IOException {
		writeText(file, toJson(value));
	}

	private static void writeText(Path file, String json) throws IOException {
		long start = System.nanoTime();
		Files.createDirectories(file.getParent());
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(temp, json, StandardCharsets.UTF_8);
		DiskIo.moveAtomic(temp, file);
		ioNanos += System.nanoTime() - start;
	}

	/** Quanto das gravações foi gasto serializando (CPU) e quanto no sistema de arquivos; só para a medição de desempenho ({@code -Pperf}). */
	private static long serializeNanos, ioNanos;

	/** Quanto do carregamento das conversas foi gasto lendo os arquivos e interpretando o JSON; só para a medição de desempenho. */
	private static long readNanos, parseNanos;

	/** Só para a medição de desempenho: {leitura dos arquivos, interpretação do JSON} em nanossegundos desde o último pedido (e zera). */
	public static long[] perfLoadSplit() {
		long[] split = {readNanos, parseNanos};
		readNanos = 0;
		parseNanos = 0;
		return split;
	}

	/** Só para a medição de desempenho: {serialização, arquivos} em nanossegundos desde o último pedido (e zera). */
	public static long[] perfWriteSplit() {
		long[] split = {serializeNanos, ioNanos};
		serializeNanos = 0;
		ioNanos = 0;
		return split;
	}
}
