package dev.migzu.handyphone.server;

import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.net.PhonePayloads.LiveEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * A parte de localização do servidor: tiles do mapa (squaremap), mensagens com ponto, compartilhamento
 * ao vivo e locais salvos. Mora ao lado do {@link PhoneService} (que guarda contas e conversas) e usa os
 * mesmos princípios: quem pede é identificado pela conexão, nada do cliente é aceito sem validação, e a
 * posição de um jogador vem sempre da entidade dele no servidor.
 *
 * <p><b>Privacidade.</b> A posição de alguém só sai daqui para quem essa pessoa autorizou
 * ({@link #startShare}), enquanto o prazo não acabou, e só para quem está olhando ({@link #onWatch}).
 * Em outra dimensão, vai só o nome da dimensão. O compartilhamento <b>não é gravado em disco</b>:
 * reiniciar o servidor, sair do jogo, expirar ou parar o encerra. Jogador em modo espectador fica
 * "sem sinal" (um administrador escondido não aparece no mapa de ninguém). Nada aqui guarda um
 * {@link PhonePlayer}: o jogo cria outro a cada respawn, então tudo é procurado pelo UUID.
 *
 * <p>Tudo roda na thread do servidor, exceto a leitura dos tiles em disco, que usa uma thread própria
 * com fila curta e devolve o resultado à thread do servidor.
 */
public final class LocationService {
	/** Pedidos de tile: rajada e recarga por segundo, por jogador. */
	private static final double REQUEST_BURST = 24, REQUEST_REFILL = 8;
	/** Bytes de tile: rajada e recarga por segundo, por jogador. */
	private static final double BYTE_BURST = 2 * 1024 * 1024, BYTE_REFILL = 384 * 1024;
	/** Tiles pedidos e ainda sem resposta, por jogador. */
	private static final int MAX_PENDING_TILES = 3;
	/** Partes de tile enviadas por jogador a cada tick (64 KB): o envio de um tile grande se espalha em vez de sair de uma vez. */
	private static final int CHUNKS_PER_TICK = 4;
	/** Compartilhamentos iniciados por dono dentro da janela (evita encher a tela dos outros de avisos). */
	private static final int SHARE_STARTS = 6;
	private static final long SHARE_WINDOW_MS = 10 * 60_000L;
	/** Salvar/apagar locais e mandar pontos: ações por janela. */
	private static final int ACTIONS = 12;
	private static final long ACTION_WINDOW_MS = 10_000L;
	private static final int MAX_Y = 4096;

	/** Onde um jogador está agora. */
	public record Pos(String dimension, double x, double y, double z, boolean spectator) {
	}

	/** De quanto em quanto tempo (ticks) o servidor confere se o mapa da dimensão de cada jogador mudou (5 s). */
	static final int MAP_RECHECK_TICKS = 100;

	private static final class PlayerState {
		int epoch;
		String dimension = "";
		double requestTokens = REQUEST_BURST;
		double byteTokens = BYTE_BURST;
		long lastRefill = System.nanoTime();
		final Set<String> pending = new HashSet<>();
		final ArrayDeque<PhonePayloads.TileData> out = new ArrayDeque<>();
		boolean watching;
		/** O que o último MapInfo disse (installed, rendered, maxZoom), para reenviar só quando muda. */
		boolean sentInstalled, sentRendered;
		int sentZoom = -2;
		/** O cliente já tem uma lista de "quem compartilha" vazia (não adianta mandar outra igual todo segundo). */
		boolean liveEmpty = true;
		final ArrayDeque<Long> watchToggles = new ArrayDeque<>();
		final ArrayDeque<Long> shareStarts = new ArrayDeque<>();
		final ArrayDeque<Long> actions = new ArrayDeque<>();

		void refill() {
			long now = System.nanoTime();
			double seconds = (now - lastRefill) / 1e9;
			lastRefill = now;
			requestTokens = Math.min(REQUEST_BURST, requestTokens + seconds * REQUEST_REFILL);
			byteTokens = Math.min(BYTE_BURST, byteTokens + seconds * BYTE_REFILL);
		}
	}

	private static final class Share {
		UUID owner;
		/** O grupo que foi escolhido como público (0 = contatos escolhidos). Quem sai do grupo deixa de receber. */
		long conversation;
		long expires;
		final Set<UUID> audience = new LinkedHashSet<>();
	}

	private final PhoneService owner;
	private final MapTiles tiles;
	private final Map<UUID, PlayerState> states = new HashMap<>();
	private final Map<UUID, Share> shares = new HashMap<>();
	private final ThreadPoolExecutor diskWorker = new ThreadPoolExecutor(
		1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(24), r -> {
			Thread thread = new Thread(r, "HandyPhone-MapTiles");
			thread.setDaemon(true);
			return thread;
		}
	);
	private MapTiles activeTiles;
	private LongSupplier clock = System::currentTimeMillis;
	/** Os testes podem fingir a posição de contas simuladas; null = usa a entidade de verdade. */
	private Function<UUID, Pos> override = uuid -> null;
	private int tickCounter;

	LocationService(PhoneService owner) {
		this.owner = owner;
		this.tiles = MapTiles.forGame(owner.server().isModLoaded("squaremap"));
		this.activeTiles = tiles;
	}

	private PhoneConfig config() {
		return PhoneService.config();
	}

	private PhonePlatform server() {
		return owner.server();
	}

	void shutdown() {
		diskWorker.shutdownNow();
		states.clear();
		shares.clear();
	}

	// ------------------------------------------------------------------ posição

	/** A posição da entidade do jogador (a única fonte aceita), ou null se ele não está ligado. */
	private Pos realPosition(UUID uuid) {
		PhonePlayer player = owner.online(uuid);
		if (player == null) {
			return null;
		}
		return new Pos(player.dimension(), player.getX(), player.getY(), player.getZ(), player.isSpectator());
	}

	/** A posição conhecida de uma conta (a entidade dela, ou o que os testes escolheram), ou null. */
	public Pos positionOf(UUID uuid) {
		Pos fake = override.apply(uuid);
		return fake != null ? fake : realPosition(uuid);
	}

	private PhonePayloads.Loc stampOf(PhonePlayer player) {
		return new PhonePayloads.Loc(true, player.dimension(), player.blockX(), true, player.blockY(), player.blockZ(), "");
	}

	private PlayerState state(UUID uuid) {
		return states.computeIfAbsent(uuid, k -> new PlayerState());
	}

	/** Se o jogador mudou de dimensão (portal, comando, morte), a época muda e o cliente recebe o mapa novo. */
	private void refreshDimension(PhonePlayer player, PlayerState state) {
		String dimension = player.dimension();
		if (!dimension.equals(state.dimension)) {
			state.dimension = dimension;
			state.epoch++;
			state.pending.clear();
			state.out.clear();
			sendMapInfo(player, state);
			// As entradas "em outra dimensão" mudam de estado com a viagem de qualquer um dos dois lados.
			pushLive(player.getUUID());
		}
	}

	// ------------------------------------------------------------------ ciclo de vida

	void onJoin(PhonePlayer player) {
		PlayerState state = state(player.getUUID());
		state.dimension = player.dimension();
		state.epoch++;
		sendMapInfo(player, state);
		sendPlaces(player);
		PhoneService.send(player, new PhonePayloads.ShareStatus(false, 0, List.of()));
		// A primeira lista sai sempre, mesmo vazia: é ela que diz ao celular que, daqui em diante, quem começar a compartilhar vira aviso.
		state.liveEmpty = false;
		pushLive(player.getUUID());
	}

	void onDisconnect(UUID uuid) {
		states.remove(uuid);
		if (shares.containsKey(uuid)) {
			stopShare(uuid);
		}
	}

	/** Chamado a cada tick do servidor. */
	void tick() {
		tickCounter++;
		for (Iterator<Map.Entry<UUID, PlayerState>> it = states.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<UUID, PlayerState> entry = it.next();
			PhonePlayer player = owner.online(entry.getKey());
			if (player == null) {
				it.remove();
				continue;
			}
			drain(player, entry.getValue());
			if (tickCounter % 10 == 0) {
				refreshDimension(player, entry.getValue());
			}
			if (tickCounter % MAP_RECHECK_TICKS == 0) {
				refreshMapInfo(player, entry.getValue());
			}
		}
		if (tickCounter % 20 == 0) {
			sweepShares();
			for (Map.Entry<UUID, PlayerState> entry : List.copyOf(states.entrySet())) {
				if (entry.getValue().watching) {
					pushLive(entry.getKey());
				}
			}
		}
	}

	// ------------------------------------------------------------------ mapa e tiles

	/**
	 * O mapa da dimensão mudou desde o último MapInfo (o squaremap ficou pronto depois de o jogador entrar, desenhou os primeiros
	 * tiles de um mundo novo, ganhou um zoom)? Então o celular recebe o aviso de novo, com época nova (o cache de tiles recomeça).
	 * Antes o aviso só saía ao entrar e ao trocar de dimensão: quem entrava antes de o squaremap ter tiles ficava sem mapa até
	 * sair do jogo. {@link MapTiles#info} guarda o resultado por 30 s por dimensão, então isto quase nunca toca o disco.
	 */
	private void refreshMapInfo(PhonePlayer player, PlayerState state) {
		MapTiles.Info info = activeTiles.info(state.dimension);
		boolean rendered = config().mapTilesEnabled && info.available();
		int zoom = rendered ? info.maxZoom() : -1;
		if (activeTiles.installed() != state.sentInstalled || rendered != state.sentRendered || zoom != state.sentZoom) {
			state.epoch++;
			state.pending.clear();
			state.out.clear();
			sendMapInfo(player, state);
		}
	}

	private void sendMapInfo(PhonePlayer player, PlayerState state) {
		MapTiles.Info info = activeTiles.info(state.dimension);
		boolean rendered = config().mapTilesEnabled && info.available();
		state.sentInstalled = activeTiles.installed();
		state.sentRendered = rendered;
		state.sentZoom = rendered ? info.maxZoom() : -1;
		PhoneService.send(player, new PhonePayloads.MapInfo(
			activeTiles.installed(), rendered, rendered ? info.maxZoom() : -1, state.dimension, state.epoch,
			config().liveLocationEnabled, config().locationMessagesEnabled, config().maxSavedPlaces
		));
	}

	private static void tileUnavailable(PhonePlayer player, PhonePayloads.TileGet p, int reason) {
		PhoneService.send(player, new PhonePayloads.TileUnavailable(p.epoch(), p.zoom(), p.x(), p.z(), reason));
	}

	/** Pedido de um tile do mapa da dimensão em que o jogador está agora. Só números: nunca um caminho. */
	public void onTileGet(PhonePlayer player, PhonePayloads.TileGet p) {
		UUID uuid = player.getUUID();
		PlayerState state = state(uuid);
		// Todo pedido gasta uma ficha, até os recusados; passou do limite, nem resposta sai (o cliente honesto tenta de novo sozinho).
		state.refill();
		if (state.requestTokens < 1) {
			return;
		}
		state.requestTokens -= 1;
		refreshDimension(player, state);
		if (!config().mapTilesEnabled || !activeTiles.installed()) {
			tileUnavailable(player, p, PhonePayloads.TILE_MISSING);
			return;
		}
		if (p.epoch() != state.epoch) {
			tileUnavailable(player, p, PhonePayloads.TILE_STALE);
			return;
		}
		MapTiles.Info info = activeTiles.info(state.dimension);
		if (!info.available() || !MapTiles.inRange(info.maxZoom(), p.zoom(), p.x(), p.z()) || !nearPlayer(player, info.maxZoom(), p)) {
			tileUnavailable(player, p, PhonePayloads.TILE_MISSING);
			return;
		}
		String key = p.zoom() + "/" + p.x() + "/" + p.z();
		if (state.pending.contains(key)) {
			return;
		}
		if (state.pending.size() >= MAX_PENDING_TILES) {
			tileUnavailable(player, p, PhonePayloads.TILE_BUSY);
			return;
		}
		String dimension = state.dimension;
		int epoch = state.epoch;
		MapTiles source = activeTiles;
		try {
			diskWorker.execute(() -> {
				MapTiles.Tile tile = source.read(dimension, p.zoom(), p.x(), p.z(), p.have());
				server().execute(() -> deliver(uuid, epoch, key, p, tile));
			});
			state.pending.add(key);
		} catch (RejectedExecutionException e) {
			tileUnavailable(player, p, PhonePayloads.TILE_BUSY);
		}
	}

	/**
	 * Respeita {@code mapRadiusBlocks}: o tile precisa tocar o quadrado em volta da posição atual do jogador.
	 * <b>O mínimo efetivo do raio é de cerca de 512 blocos</b>: o menor tile (zoom máximo) tem 512x512 blocos, então
	 * qualquer raio entrega a região inteira que toca o quadrado, e até 4 regiões perto de uma borda. O servidor não
	 * recorta o PNG; o dono que quer esconder mais precisa desligar {@code mapTilesEnabled} ou limitar o squaremap.
	 */
	private boolean nearPlayer(PhonePlayer player, int maxZoom, PhonePayloads.TileGet p) {
		int radius = config().mapRadiusBlocks;
		if (radius <= 0) {
			return true;
		}
		long span = (long) MapTiles.TILE_SIZE << (maxZoom - p.zoom());
		// Um tile de zoom afastado cobre milhares de blocos (4096 no zoom 0 padrão): tocar o quadrado não basta,
		// senão o raio não esconderia nada. Só passa tile que não é bem maior que o próprio quadrado.
		if (span > Math.max(MapTiles.TILE_SIZE, 2L * radius)) {
			return false;
		}
		long x0 = p.x() * span, z0 = p.z() * span;
		double px = player.getX(), pz = player.getZ();
		return x0 <= px + radius && x0 + span >= px - radius && z0 <= pz + radius && z0 + span >= pz - radius;
	}

	/** De volta à thread do servidor: confere se o pedido ainda vale e enfileira as partes do PNG. */
	private void deliver(UUID uuid, int epoch, String key, PhonePayloads.TileGet p, MapTiles.Tile tile) {
		// No desligamento o servidor pode rodar isto na thread de quem agendou; nesse caso o estado não pode ser tocado.
		if (PhoneService.location() != this || !server().isServerThread()) {
			return;
		}
		PlayerState state = states.get(uuid);
		PhonePlayer player = owner.online(uuid);
		if (state == null || player == null) {
			return;
		}
		state.pending.remove(key);
		if (state.epoch != epoch) {
			return;
		}
		if (tile == null) {
			tileUnavailable(player, p, PhonePayloads.TILE_MISSING);
			return;
		}
		if (tile.bytes() == null) {
			// O cliente já tem esta versão do arquivo: um aviso curto no lugar de reenviar o PNG.
			tileUnavailable(player, p, PhonePayloads.TILE_UNCHANGED);
			return;
		}
		byte[] bytes = tile.bytes();
		state.refill();
		if (state.byteTokens < bytes.length) {
			tileUnavailable(player, p, PhonePayloads.TILE_BUSY);
			return;
		}
		state.byteTokens -= bytes.length;
		int count = (bytes.length + PhonePayloads.MAX_CHUNK - 1) / PhonePayloads.MAX_CHUNK;
		for (int i = 0; i < count; i++) {
			int from = i * PhonePayloads.MAX_CHUNK, to = Math.min(bytes.length, from + PhonePayloads.MAX_CHUNK);
			state.out.add(new PhonePayloads.TileData(epoch, p.zoom(), p.x(), p.z(), tile.stamp(), i, count, Arrays.copyOfRange(bytes, from, to)));
		}
	}

	private void drain(PhonePlayer player, PlayerState state) {
		for (int i = 0; i < CHUNKS_PER_TICK && !state.out.isEmpty(); i++) {
			PhoneService.send(player, state.out.poll());
		}
	}

	// ------------------------------------------------------------------ mensagens com ponto

	public void onSendLocation(PhonePlayer player, PhonePayloads.SendLocation p) {
		if (!config().locationMessagesEnabled) {
			PhoneService.notice(player, "handyphone.notice.location_off");
			return;
		}
		owner.account(player);
		PhoneService.Conversation conversation = owner.memberConversation(player, p.conversation());
		if (conversation == null || !owner.canSpeak(player) || !owner.allowSend(player)) {
			return;
		}
		// A posição é a da entidade do jogador, lida agora: o pacote nem tem campo para coordenadas.
		owner.post(conversation, player.getUUID(), "", 0L, toStored(stampOf(player)));
	}

	public void onSendPin(PhonePlayer player, PhonePayloads.SendPin p) {
		if (!config().locationMessagesEnabled) {
			PhoneService.notice(player, "handyphone.notice.location_off");
			return;
		}
		owner.account(player);
		PhoneService.Conversation conversation = owner.memberConversation(player, p.conversation());
		if (conversation == null || !owner.canSpeak(player) || !owner.allowSend(player)) {
			return;
		}
		// Ponto escolhido no mapa: rotulado como tal (stamped = false) e preso à borda do mundo; a dimensão é a do jogador.
		PhoneService.StoredLoc loc = new PhoneService.StoredLoc();
		loc.stamped = false;
		loc.dim = player.dimension();
		loc.x = clamp(p.x(), -PhonePayloads.WORLD_LIMIT, PhonePayloads.WORLD_LIMIT);
		loc.z = clamp(p.z(), -PhonePayloads.WORLD_LIMIT, PhonePayloads.WORLD_LIMIT);
		loc.hasY = false;
		owner.post(conversation, player.getUUID(), "", 0L, loc);
	}

	private static PhoneService.StoredLoc toStored(PhonePayloads.Loc loc) {
		PhoneService.StoredLoc stored = new PhoneService.StoredLoc();
		stored.stamped = loc.stamped();
		stored.dim = loc.dimension();
		stored.x = loc.x();
		stored.y = loc.y();
		stored.z = loc.z();
		stored.hasY = loc.hasY();
		stored.label = loc.label().isEmpty() ? null : loc.label();
		return stored;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	// ------------------------------------------------------------------ compartilhamento ao vivo

	public void onShareStart(PhonePlayer player, PhonePayloads.ShareStart p) {
		String error = startShare(player.getUUID(), p);
		if (error != null) {
			PhoneService.notice(player, error);
		}
	}

	/**
	 * Começa (ou troca) o compartilhamento de um jogador. O público são contatos dele ou os membros de um
	 * grupo de que ele participa; o prazo vem de uma lista fixa. Retorna o motivo da recusa, ou null.
	 */
	public String startShare(UUID who, PhonePayloads.ShareStart p) {
		if (!config().liveLocationEnabled) {
			return "handyphone.notice.live_off";
		}
		PhoneService.Account account = owner.accountOf(who);
		if (account == null) {
			return "handyphone.notice.no_number";
		}
		if (owner.isMuted(who)) {
			return "handyphone.notice.muted_share";
		}
		if (p.duration() < 0 || p.duration() >= PhonePayloads.SHARE_MINUTES.length) {
			return "handyphone.notice.share_duration";
		}
		PlayerState state = state(who);
		long now = clock.getAsLong();
		while (!state.shareStarts.isEmpty() && now - state.shareStarts.peekFirst() > SHARE_WINDOW_MS) {
			state.shareStarts.pollFirst();
		}
		if (state.shareStarts.size() >= SHARE_STARTS) {
			return "handyphone.notice.share_rate";
		}
		Set<UUID> audience = new LinkedHashSet<>();
		if (p.conversation() != 0) {
			PhoneService.Conversation group = owner.memberConversation(who, p.conversation());
			if (group == null || !group.group) {
				return "handyphone.notice.group_unknown";
			}
			audience.addAll(group.members);
		} else {
			for (String raw : p.numbers()) {
				String number = PhoneService.digits(raw);
				UUID target = owner.uuidOf(number);
				// Só contatos que o próprio jogador salvou: o público é uma escolha dele, nunca de um pacote qualquer.
				if (target != null && account.contacts.containsKey(number) && audience.size() < PhonePayloads.MAX_GROUP_MEMBERS) {
					audience.add(target);
				}
			}
		}
		audience.remove(who);
		// O público fica como o dono pediu: quem bloqueou o dono (ou foi bloqueado por ele) é filtrado só na consulta
		// (entriesFor), para o bloqueado não perceber nada, nem por uma recusa nem por um número que sumiu da lista.
		if (audience.isEmpty()) {
			return "handyphone.notice.share_audience";
		}
		state.shareStarts.addLast(now);

		Share old = shares.remove(who);
		Share share = new Share();
		share.owner = who;
		share.conversation = p.conversation();
		share.expires = now + PhonePayloads.SHARE_MINUTES[p.duration()] * 60_000L;
		share.audience.addAll(audience);
		shares.put(who, share);
		sendShareStatus(who, share);
		for (UUID viewer : audience) {
			pushLive(viewer);
		}
		if (old != null) {
			for (UUID viewer : old.audience) {
				if (!audience.contains(viewer)) {
					pushLive(viewer);
				}
			}
		}
		return null;
	}

	public void onShareStop(PhonePlayer player) {
		stopShare(player.getUUID());
	}

	/** Encerra o compartilhamento de um jogador (parou, expirou, saiu): quem via a posição recebe a lista sem ele. */
	public void stopShare(UUID who) {
		Share share = shares.remove(who);
		if (share == null) {
			return;
		}
		sendShareStatus(who, null);
		for (UUID viewer : share.audience) {
			pushLive(viewer);
		}
	}

	private void sweepShares() {
		long now = clock.getAsLong();
		for (Share share : List.copyOf(shares.values())) {
			// Dono sem posição = não está mais no jogo (o DISCONNECT já cuida disso; isto é a rede de segurança).
			if (now >= share.expires || !config().liveLocationEnabled || positionOf(share.owner) == null) {
				// O celular mostra o aviso sozinho quando recebe o ShareStatus "parado".
				stopShare(share.owner);
			}
		}
	}

	public void onWatch(PhonePlayer player, PhonePayloads.Watch p) {
		PlayerState state = state(player.getUUID());
		// Limite silencioso: um cliente que fica ligando e desligando não ganha uma resposta por troca.
		long now = clock.getAsLong();
		while (!state.watchToggles.isEmpty() && now - state.watchToggles.peekFirst() > ACTION_WINDOW_MS) {
			state.watchToggles.pollFirst();
		}
		if (state.watchToggles.size() >= ACTIONS * 2) {
			return;
		}
		if (state.watching != p.on()) {
			state.watchToggles.addLast(now);
			state.watching = p.on();
			pushLive(player.getUUID());
		}
	}

	private void sendShareStatus(UUID who, Share share) {
		PhonePlayer player = owner.online(who);
		if (player == null) {
			return;
		}
		if (share == null) {
			PhoneService.send(player, new PhonePayloads.ShareStatus(false, 0, List.of()));
			return;
		}
		List<String> numbers = new ArrayList<>();
		for (UUID viewer : share.audience) {
			numbers.add(owner.numberOf(viewer));
		}
		PhoneService.send(player, new PhonePayloads.ShareStatus(true, secondsLeft(share), numbers));
	}

	private int secondsLeft(Share share) {
		return (int) Math.max(0, (share.expires - clock.getAsLong()) / 1000);
	}

	private void pushLive(UUID viewer) {
		PhonePlayer player = owner.online(viewer);
		if (player != null) {
			PlayerState state = states.get(viewer);
			List<LiveEntry> entries = entriesFor(viewer, state != null && state.watching);
			if (state != null) {
				// Lista vazia depois de lista vazia não muda nada para o cliente: poupa 1 pacote por segundo de cada jogador com o Mapa aberto.
				if (entries.isEmpty() && state.liveEmpty) {
					return;
				}
				state.liveEmpty = entries.isEmpty();
			}
			PhoneService.send(player, new PhonePayloads.Live(entries));
		}
	}

	/**
	 * O que este jogador pode saber agora sobre quem compartilha com ele. Só entra quem o autorizou e ainda
	 * está dentro do prazo. Sem {@code positions} (o jogador não está olhando), só se sabe QUE a pessoa compartilha.
	 */
	public List<LiveEntry> entriesFor(UUID viewer, boolean positions) {
		List<LiveEntry> entries = new ArrayList<>();
		if (!config().liveLocationEnabled) {
			return entries;
		}
		long now = clock.getAsLong();
		Pos viewerPos = positions ? positionOf(viewer) : null;
		for (Share share : shares.values()) {
			if (!share.audience.contains(viewer) || now >= share.expires) {
				continue;
			}
			// O bloqueio vale na hora da consulta, nos dois sentidos: bloquear (ou ser bloqueado) esconde na hora, sem esperar o fim do prazo.
			if (owner.isBlocked(viewer, share.owner) || owner.isBlocked(share.owner, viewer)) {
				continue;
			}
			String number = owner.numberOf(share.owner);
			int left = secondsLeft(share);
			Pos pos = positions ? positionOf(share.owner) : null;
			if (!positions || pos == null) {
				entries.add(new LiveEntry(number, PhonePayloads.LIVE_IDLE, "", 0, 0, 0, left));
			} else if (pos.spectator()) {
				entries.add(new LiveEntry(number, PhonePayloads.LIVE_PAUSED, "", 0, 0, 0, left));
			} else if (viewerPos == null || !viewerPos.dimension().equals(pos.dimension())) {
				// Outra dimensão: só o nome dela. As coordenadas de lá não valem aqui e não saem do servidor.
				entries.add(new LiveEntry(number, PhonePayloads.LIVE_OTHER_DIMENSION, pos.dimension(), 0, 0, 0, left));
			} else {
				entries.add(new LiveEntry(
					number, PhonePayloads.LIVE_POSITION, pos.dimension(), (int) Math.floor(pos.x()), (int) Math.floor(pos.y()), (int) Math.floor(pos.z()), left
				));
			}
		}
		return entries;
	}

	/** O jogador bloqueou (ou desbloqueou) alguém: a lista de quem compartilha muda para os dois, na hora. */
	void onBlockChanged(UUID a, UUID b) {
		pushLive(a);
		pushLive(b);
	}

	/**
	 * Quem sai de um grupo deixa de ver os compartilhamentos que tinham o grupo como público, e o que ele próprio
	 * compartilhava com o grupo termina.
	 */
	void onLeftGroup(UUID who, long conversation) {
		Share own = shares.get(who);
		if (own != null && own.conversation == conversation) {
			stopShare(who);
		}
		for (Share share : List.copyOf(shares.values())) {
			if (share.conversation == conversation && share.audience.remove(who)) {
				pushLive(who);
			}
		}
	}

	/** O grupo deixou de existir: os compartilhamentos que o tinham como público terminam. */
	void onConversationGone(long conversation) {
		for (Share share : List.copyOf(shares.values())) {
			if (share.conversation == conversation) {
				stopShare(share.owner);
			}
		}
	}

	/** Reenvia os locais salvos de um jogador (depois de uma ação de administração). */
	void sendPlacesTo(PhonePlayer player) {
		sendPlaces(player);
	}

	// ------------------------------------------------------------------ locais salvos

	private boolean allowAction(PhonePlayer player) {
		PlayerState state = state(player.getUUID());
		long now = clock.getAsLong();
		while (!state.actions.isEmpty() && now - state.actions.peekFirst() > ACTION_WINDOW_MS) {
			state.actions.pollFirst();
		}
		if (state.actions.size() >= ACTIONS) {
			PhoneService.notice(player, "handyphone.notice.slow_down");
			return false;
		}
		state.actions.addLast(now);
		return true;
	}

	public void onPlaceSave(PhonePlayer player, PhonePayloads.PlaceSave p) {
		PhoneService.Account account = owner.account(player);
		if (!allowAction(player)) {
			return;
		}
		String name = PhoneService.clean(p.name(), PhonePayloads.MAX_NAME);
		if (name.isEmpty()) {
			PhoneService.notice(player, "handyphone.notice.place_name");
			return;
		}
		if (PhoneService.isReservedName(name)) {
			PhoneService.notice(player, "handyphone.notice.name_reserved");
			return;
		}
		String dimension = server().dimension(p.dimension());
		if (dimension == null) {
			PhoneService.notice(player, "handyphone.notice.dimension_unknown");
			return;
		}
		if (account.places.size() >= config().maxSavedPlaces) {
			PhoneService.notice(player, "handyphone.notice.places_full", String.valueOf(config().maxSavedPlaces));
			return;
		}
		PhoneService.Place place = new PhoneService.Place();
		place.id = account.nextPlaceId++;
		place.name = name;
		place.dim = dimension;
		place.x = clamp(p.x(), -PhonePayloads.WORLD_LIMIT, PhonePayloads.WORLD_LIMIT);
		place.z = clamp(p.z(), -PhonePayloads.WORLD_LIMIT, PhonePayloads.WORLD_LIMIT);
		place.hasY = p.hasY();
		place.y = p.hasY() ? clamp(p.y(), -MAX_Y, MAX_Y) : 0;
		place.color = validColor(p.color());
		account.places.add(place);
		owner.markAccountsDirty();
		sendPlaces(player);
	}

	/** Cor fora da paleta vira a primeira (um cliente alterado não escolhe um índice que não existe). */
	private static int validColor(int color) {
		return color >= 0 && color < PhonePayloads.PLACE_COLORS ? color : 0;
	}

	public void onPlaceEdit(PhonePlayer player, PhonePayloads.PlaceEdit p) {
		PhoneService.Account account = owner.account(player);
		if (!allowAction(player)) {
			return;
		}
		String name = PhoneService.clean(p.name(), PhonePayloads.MAX_NAME);
		if (name.isEmpty()) {
			PhoneService.notice(player, "handyphone.notice.place_name");
			return;
		}
		if (PhoneService.isReservedName(name)) {
			PhoneService.notice(player, "handyphone.notice.name_reserved");
			return;
		}
		for (PhoneService.Place place : account.places) {
			if (place.id == p.id()) {
				place.name = name;
				place.color = validColor(p.color());
				owner.markAccountsDirty();
				sendPlaces(player);
				return;
			}
		}
	}

	/** Encaminha um local salvo (do próprio jogador) para uma conversa dele. A mensagem leva o nome e as coordenadas do local. */
	public void onSendPlace(PhonePlayer player, PhonePayloads.SendPlace p) {
		if (!config().locationMessagesEnabled) {
			PhoneService.notice(player, "handyphone.notice.location_off");
			return;
		}
		PhoneService.Account account = owner.account(player);
		PhoneService.Conversation conversation = owner.memberConversation(player, p.conversation());
		PhoneService.Place place = null;
		for (PhoneService.Place candidate : account.places) {
			if (candidate.id == p.place()) {
				place = candidate;
			}
		}
		if (conversation == null || place == null || !owner.canSpeak(player) || !owner.allowSend(player)) {
			return;
		}
		PhoneService.StoredLoc loc = new PhoneService.StoredLoc();
		loc.stamped = false;
		loc.dim = place.dim;
		loc.x = place.x;
		loc.y = place.y;
		loc.z = place.z;
		loc.hasY = place.hasY;
		loc.label = place.name;
		owner.post(conversation, player.getUUID(), "", 0L, loc);
	}

	public void onPlaceDelete(PhonePlayer player, PhonePayloads.PlaceDelete p) {
		PhoneService.Account account = owner.account(player);
		if (allowAction(player) && account.places.removeIf(place -> place.id == p.id())) {
			owner.markAccountsDirty();
			sendPlaces(player);
		}
	}

	private void sendPlaces(PhonePlayer player) {
		PhoneService.Account account = owner.account(player);
		List<PhonePayloads.PlaceEntry> list = new ArrayList<>();
		for (PhoneService.Place place : account.places) {
			list.add(new PhonePayloads.PlaceEntry(place.id, place.name, place.dim, place.x, place.hasY, place.y, place.z, validColor(place.color)));
		}
		PhoneService.send(player, new PhonePayloads.Places(list));
	}

	// ------------------------------------------------------------------ testes e administração

	/** Troca a fonte dos tiles (os testes apontam para uma pasta qualquer). */
	public void useTiles(MapTiles replacement) {
		activeTiles = replacement != null ? replacement : tiles;
	}

	/** Os testes escolhem a posição de contas simuladas (que não têm entidade); a função devolve null para as demais. */
	public void useLocator(Function<UUID, Pos> replacement) {
		override = replacement != null ? replacement : uuid -> null;
	}

	/** A época atual do mapa de um jogador (muda a cada troca de dimensão). */
	public int epochOf(UUID who) {
		PlayerState state = states.get(who);
		return state == null ? 0 : state.epoch;
	}

	/** Os testes controlam o relógio para expirar um compartilhamento sem esperar. */
	public void useClock(LongSupplier replacement) {
		clock = replacement != null ? replacement : System::currentTimeMillis;
	}

	/** Reenvia ao jogador a situação do mapa (depois de os testes trocarem a fonte dos tiles). */
	public void resendMapInfo(UUID who) {
		PhonePlayer player = owner.online(who);
		if (player != null) {
			activeTiles.forget();
			sendMapInfo(player, state(who));
		}
	}

	public boolean isSharing(UUID who) {
		return shares.containsKey(who);
	}

	/** Roda a verificação periódica agora (expiração e envio das posições). */
	public void sweepNow() {
		sweepShares();
		for (UUID viewer : List.copyOf(states.keySet())) {
			if (state(viewer).watching) {
				pushLive(viewer);
			}
		}
	}

	/** Ids dos locais salvos de uma conta, na ordem da lista (para os testes). */
	public java.util.List<Long> placeIds(UUID who) {
		PhoneService.Account account = owner.accountOf(who);
		java.util.List<Long> ids = new ArrayList<>();
		if (account != null) {
			for (PhoneService.Place place : account.places) {
				ids.add(place.id);
			}
		}
		return ids;
	}

	public int savedPlaces(UUID who) {
		PhoneService.Account account = owner.accountOf(who);
		return account == null ? 0 : account.places.size();
	}

	/** Entrega das partes pendentes de tiles (os testes não esperam os ticks). */
	public int pendingTileChunks(UUID who) {
		PlayerState state = states.get(who);
		return state == null ? 0 : state.out.size() + state.pending.size();
	}
}
