package dev.migzu.handyphone.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.net.PhonePayloads;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * O scroll.in (1.4): fotos públicas do servidor, com curtidas e comentários. Tudo na thread do servidor; o arquivo é
 * {@code <mundo>/handyphone/feed.json} (gravado pela mesma fila de disco das conversas, ver {@link PhoneService#save}).
 *
 * <ul>
 *   <li>A publicação <b>referencia</b> a foto do {@link PhotoStore} (sem cópia) pelo mesmo mapa de referências das conversas
 *       ({@link PhoneService#FEED_REF}). A foto sair (apagada da galeria, wipe) tira a publicação: um ponto só,
 *       {@link #photoRemoved}.</li>
 *   <li>O autor viaja como um id opaco sorteado por conta ({@link FeedFile#handles}), nunca o número nem o UUID.</li>
 *   <li>Bloqueio (em qualquer sentido) = os dois não se veem no feed: nem publicações, nem comentários, nem avisos. Sem recusa
 *       visível (quem foi bloqueado não percebe).</li>
 *   <li>A publicação nunca leva o local da foto.</li>
 * </ul>
 */
final class FeedService {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Comentários por jogador por minuto e por hora. */
	static final int COMMENTS_PER_MINUTE = 6, COMMENTS_PER_HOUR = 60;
	private static final long DAY_MS = 24L * 3600 * 1000;

	static final class Comment {
		long id;
		UUID author;
		String text;
		long time;
	}

	static final class Post {
		long id;
		UUID owner;
		long photo;
		String caption = "";
		long time;
		LinkedHashSet<UUID> likes = new LinkedHashSet<>();
		List<Comment> comments = new ArrayList<>();
		/** Quem denunciou (a publicação some só do feed dessas contas, mesmo depois de o administrador dispensar a denúncia). */
		LinkedHashSet<UUID> hiddenFor = new LinkedHashSet<>();
	}

	/** Uma denúncia na fila dos administradores. */
	static final class Report {
		long post;
		UUID reporter;
		long time;
	}

	/** Denúncias por jogador por hora, e no servidor todo (as mais antigas saem). */
	static final int REPORTS_PER_HOUR = 10, MAX_REPORTS = 1000;
	/**
	 * Comentários de uma mesma conta numa publicação (uma pessoa não empurra a conversa dos outros para fora dos {@link PhonePayloads#MAX_COMMENTS}),
	 * e no servidor todo (o feed.json é serializado inteiro na thread do servidor: um teto mantém isso em poucos MB).
	 */
	static final int COMMENTS_PER_AUTHOR = 20, MAX_TOTAL_COMMENTS = 50_000;

	static final class FeedFile {
		long nextPost = 1;
		long nextComment = 1;
		/** Id opaco de cada conta no feed. */
		Map<UUID, String> handles = new LinkedHashMap<>();
		/** O último nome de jogo visto de cada conta (o feed mostra o nome mesmo com a pessoa fora do jogo). */
		Map<UUID, String> names = new LinkedHashMap<>();
		/** Do mais antigo ao mais novo. */
		List<Post> posts = new ArrayList<>();
		/** Fila de denúncias, da mais antiga à mais nova (saem quando a publicação sai ou o administrador as dispensa). */
		List<Report> reports = new ArrayList<>();
		/**
		 * Publicações cuja foto não foi achada numa subida em que o índice de fotos não foi lido inteiro (arquivo ilegível): ficam guardadas,
		 * fora do feed, e voltam quando a foto aparecer (o dono restaurou o índice). Só saem de vez numa subida com o índice limpo.
		 */
		List<Post> parked = new ArrayList<>();
	}

	private final PhoneService service;
	private FeedFile data = new FeedFile();
	private final Map<Long, Post> byId = new HashMap<>();
	/** A publicação de cada foto (uma por foto). */
	private final Map<Long, Post> byPhoto = new HashMap<>();
	private int totalComments;
	private final Map<String, UUID> byHandle = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> publishes = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> commentTimes = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> reportTimes = new HashMap<>();
	/** "post:quem" já avisados de uma curtida (curtir e descurtir em laço não vira uma enxurrada de avisos). */
	private final Set<String> likeNoticed = new LinkedHashSet<>();
	private final SecureRandom random = new SecureRandom();
	boolean dirty;
	/** O feed.json existe mas não pôde ser lido: nada é gravado por cima (o dono pode recuperar). */
	private boolean locked;

	FeedService(PhoneService service) {
		this.service = service;
	}

	private PhoneConfig config() {
		return service.settings();
	}

	boolean enabled() {
		return config().publicFeed && !locked;
	}

	// ------------------------------------------------------------------ disco

	Path file() {
		return service.dir().resolve("feed.json");
	}

	/** Lê o feed.json. Ilegível: guarda uma cópia, deixa o feed desligado e nunca grava por cima. */
	void load() {
		Path file = file();
		if (!Files.exists(file)) {
			return;
		}
		try {
			FeedFile read = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), FeedFile.class);
			if (read != null) {
				data = read;
			}
		} catch (IOException | RuntimeException e) {
			locked = true;
			Path copy = service.quarantineCopy(file);
			HandyPhoneCore.LOGGER.error("HandyPhone: feed.json is unreadable; scroll.in stays OFF and the file will not be overwritten. A copy was kept at {}",
				copy, e);
			return;
		}
		if (data.handles == null) {
			data.handles = new LinkedHashMap<>();
		}
		if (data.names == null) {
			data.names = new LinkedHashMap<>();
		}
		if (data.posts == null) {
			data.posts = new ArrayList<>();
		}
		if (data.reports == null) {
			data.reports = new ArrayList<>();
		}
		if (data.parked == null) {
			data.parked = new ArrayList<>();
		}
		boolean photosClean = service.photoStore().loadedClean();
		List<Post> all = new ArrayList<>(data.posts);
		all.addAll(data.parked);
		all.sort((a, b) -> a == null || b == null ? 0 : Long.compare(a.id, b.id));
		data.posts = all;
		List<Post> parked = new ArrayList<>();
		data.handles.values().removeIf(h -> h == null || h.isEmpty() || h.length() > PhonePayloads.MAX_AUTHOR);
		data.handles.forEach((uuid, handle) -> byHandle.put(handle, uuid));
		for (Iterator<Post> it = data.posts.iterator(); it.hasNext(); ) {
			Post post = it.next();
			if (post == null || post.owner == null || byId.containsKey(post.id)) {
				it.remove();
				dirty = true;
				continue;
			}
			PhotoStore.Photo photo = service.photoStore().get(post.photo);
			if (photo == null && !photosClean) {
				// O índice de fotos não foi lido inteiro: a foto pode só estar num índice ilegível. Guarda, fora do feed.
				it.remove();
				parked.add(post);
				continue;
			}
			// A foto sumiu (apagada com o servidor desligado, arquivo perdido) ou foi apagada pelo dono: a publicação sai.
			if (photo == null || photo.hidden || !photo.owner.equals(post.owner) || byPhoto.containsKey(post.photo)) {
				it.remove();
				dirty = true;
				continue;
			}
			if (post.hiddenFor == null) {
				post.hiddenFor = new LinkedHashSet<>();
			}
			if (post.caption == null) {
				post.caption = "";
			}
			if (post.likes == null) {
				post.likes = new LinkedHashSet<>();
			}
			if (post.comments == null) {
				post.comments = new ArrayList<>();
			}
			post.comments.removeIf(c -> c == null || c.author == null || c.text == null);
			for (Comment c : post.comments) {
				data.nextComment = Math.max(data.nextComment, c.id + 1);
			}
			data.nextPost = Math.max(data.nextPost, post.id + 1);
			byId.put(post.id, post);
			byPhoto.put(post.photo, post);
			totalComments += post.comments.size();
			service.addPhotoRef(post.photo, PhoneService.FEED_REF);
		}
		if (parked.size() != data.parked.size()) {
			dirty = true;
		}
		data.parked = parked;
		for (Post p : parked) {
			data.nextPost = Math.max(data.nextPost, p.id + 1);
		}
		if (!parked.isEmpty()) {
			HandyPhoneCore.LOGGER.warn("HandyPhone: {} scroll.in posts kept aside because their photos weren't found (a photo index couldn't be read)", parked.size());
		}
		data.reports.removeIf(r -> r == null || r.reporter == null || !byId.containsKey(r.post));
	}

	/** O texto a gravar (thread do servidor), ou null se nada mudou ou o arquivo do disco é intocável. */
	String takeJson() {
		if (!dirty || locked) {
			return null;
		}
		dirty = false;
		return GSON.toJson(data);
	}

	// ------------------------------------------------------------------ quem é quem

	/** O id opaco da conta no feed (sorteado na primeira vez). */
	String handle(UUID who) {
		String handle = data.handles.get(who);
		if (handle == null) {
			do {
				StringBuilder b = new StringBuilder();
				for (int i = 0; i < 10; i++) {
					b.append("abcdefghijkmnpqrstuvwxyz23456789".charAt(random.nextInt(32)));
				}
				handle = b.toString();
			} while (byHandle.containsKey(handle));
			data.handles.put(who, handle);
			byHandle.put(handle, who);
			dirty = true;
		}
		return handle;
	}

	/** O nome com que a conta aparece no feed. Hoje: o último nome de jogo visto (decisão pendente do Migzu; um lugar só). */
	String displayName(UUID who) {
		String name = data.names.get(who);
		return name == null || name.isEmpty() ? "?" : name;
	}

	private void rememberName(PhonePlayer player) {
		String name = PhoneService.clean(player.name(), PhonePayloads.MAX_NAME);
		if (!name.equals(data.names.get(player.getUUID()))) {
			data.names.put(player.getUUID(), name);
			dirty = true;
		}
	}

	/** Um bloqueou o outro (em qualquer sentido)? Então não se veem no feed. */
	private boolean apart(UUID a, UUID b) {
		return !a.equals(b) && (service.isBlocked(a, b) || service.isBlocked(b, a));
	}

	/** A publicação pode ser vista por {@code viewer}? A MESMA pergunta vale para a lista e para baixar a foto ({@link #canViewPhoto}). */
	boolean visibleTo(UUID viewer, Post post) {
		return enabled() && !apart(viewer, post.owner) && !post.hiddenFor.contains(viewer);
	}

	/** {@code viewer} pode baixar a foto por estar numa publicação que ele vê? */
	boolean canViewPhoto(UUID viewer, long photo) {
		if (!enabled()) {
			return false;
		}
		Post post = byPhoto.get(photo);
		return post != null && visibleTo(viewer, post);
	}

	/** A foto pode ser publicada por {@code who}? (dela, na galeria, e da origem que o dono do servidor aceita) */
	boolean eligible(UUID who, PhotoStore.Photo photo) {
		if (photo == null || photo.hidden || !photo.owner.equals(who)) {
			return false;
		}
		// "Câmera" = carimbada pelo servidor com o local (só fotos da câmera, desde a 1.3) e não declarada como do computador.
		return "any".equals(config().feedPhotoSource) || (!photo.imported && photo.dim != null);
	}

	private PhonePayloads.FeedPostEntry entry(UUID viewer, Post post) {
		int comments = 0;
		for (Comment c : post.comments) {
			if (!apart(viewer, c.author)) {
				comments++;
			}
		}
		// As curtidas de quem está bloqueado (em qualquer sentido) também não contam, como os comentários.
		int likes = 0;
		for (UUID liker : post.likes) {
			if (!apart(viewer, liker)) {
				likes++;
			}
		}
		return new PhonePayloads.FeedPostEntry(post.id, handle(post.owner), displayName(post.owner), post.time, post.caption, post.photo, likes,
			post.likes.contains(viewer), comments, post.owner.equals(viewer));
	}

	// ------------------------------------------------------------------ eventos

	void onJoin(PhonePlayer player) {
		rememberName(player);
		sendInfo(player);
	}

	void sendInfo(PhonePlayer player) {
		boolean on = enabled() && service.photosOn();
		UUID who = player.getUUID();
		PhoneService.send(player, new PhonePayloads.FeedInfo(on, !"any".equals(config().feedPhotoSource), PhonePayloads.MAX_CAPTION,
			PhonePayloads.MAX_COMMENT, on ? handle(who) : "", on ? displayName(who) : "", on && player.isAdmin()));
	}

	void onGet(PhonePlayer player, PhonePayloads.FeedGet packet) {
		if (!enabled()) {
			return;
		}
		UUID viewer = player.getUUID();
		UUID author = null;
		if (!packet.author().isEmpty()) {
			author = byHandle.get(packet.author());
			if (author == null || apart(viewer, author)) {
				// Perfil que não existe e perfil de quem bloqueou (ou foi bloqueado) dão a mesma resposta: vazio.
				PhoneService.send(player, new PhonePayloads.FeedPage(packet.author(), packet.before(), List.of(), false));
				return;
			}
		}
		long before = packet.before() <= 0 ? Long.MAX_VALUE : packet.before();
		List<PhonePayloads.FeedPostEntry> page = new ArrayList<>();
		boolean more = false;
		// Do mais novo para o mais antigo; o bloqueio é filtrado ANTES de paginar (a página nunca sai curta por causa dele).
		for (int i = data.posts.size() - 1; i >= 0; i--) {
			Post post = data.posts.get(i);
			if (post.id >= before || (author != null && !post.owner.equals(author)) || !visibleTo(viewer, post)) {
				continue;
			}
			if (page.size() == PhonePayloads.FEED_PAGE) {
				more = true;
				break;
			}
			page.add(entry(viewer, post));
		}
		PhoneService.send(player, new PhonePayloads.FeedPage(packet.author(), packet.before(), page, more));
	}

	void onEligibleGet(PhonePlayer player) {
		if (!enabled()) {
			return;
		}
		List<Long> ids = new ArrayList<>();
		for (PhotoStore.Photo photo : service.photoStore().gallery(player.getUUID())) {
			if (eligible(player.getUUID(), photo) && ids.size() < PhonePayloads.MAX_GALLERY) {
				ids.add(photo.id);
			}
		}
		PhoneService.send(player, new PhonePayloads.FeedEligible(ids));
	}

	void onPublish(PhonePlayer player, PhonePayloads.FeedPublish packet) {
		UUID who = player.getUUID();
		if (!enabled() || !service.photosOn()) {
			PhoneService.notice(player, "handyphone.notice.feed.off");
			return;
		}
		service.account(player);
		PhotoStore.Photo photo = service.photoStore().get(packet.photo());
		if (!eligible(who, photo)) {
			PhoneService.notice(player, "handyphone.notice.feed.not_eligible");
			return;
		}
		if (!service.canSpeak(player)) {
			return;
		}
		if (byPhoto.containsKey(photo.id)) {
			PhoneService.notice(player, "handyphone.notice.feed.already");
			return;
		}
		long now = service.now();
		ArrayDeque<Long> times = publishes.computeIfAbsent(who, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() > DAY_MS) {
			times.pollFirst();
		}
		if (times.size() >= config().feedPostsPerDay) {
			PhoneService.notice(player, "handyphone.notice.feed.rate", String.valueOf(config().feedPostsPerDay));
			return;
		}
		times.addLast(now);
		rememberName(player);
		Post post = new Post();
		post.id = data.nextPost++;
		post.owner = who;
		post.photo = photo.id;
		post.caption = PhoneService.clean(packet.caption(), PhonePayloads.MAX_CAPTION);
		post.time = now;
		data.posts.add(post);
		byId.put(post.id, post);
		byPhoto.put(post.photo, post);
		service.addPhotoRef(photo.id, PhoneService.FEED_REF);
		dirty = true;
		HandyPhoneCore.LOGGER.info("HandyPhone: {} posted photo {} on scroll.in (post {})", player.name(), photo.id, post.id);
		// Teto do servidor: as mais antigas saem do feed (a foto continua na galeria do dono).
		while (data.posts.size() > config().feedMaxPosts) {
			removePost(data.posts.get(0), false);
		}
	}

	void onLike(PhonePlayer player, PhonePayloads.FeedLike packet) {
		UUID who = player.getUUID();
		Post post = enabled() ? byId.get(packet.post()) : null;
		// Silenciado não curte (a curtida vira aviso para o dono); sem recado, para não virar spam de avisos para ele.
		if (post == null || !visibleTo(who, post) || service.isMuted(who)) {
			return;
		}
		boolean changed = packet.on() ? post.likes.add(who) : post.likes.remove(who);
		if (!changed) {
			PhoneService.send(player, new PhonePayloads.FeedPost(entry(who, post)));
			return;
		}
		dirty = true;
		PhoneService.send(player, new PhonePayloads.FeedPost(entry(who, post)));
		if (packet.on() && !post.owner.equals(who) && likeNoticed.add(post.id + ":" + who)) {
			if (likeNoticed.size() > 5000) {
				likeNoticed.remove(likeNoticed.iterator().next());
			}
			activity(post, who, PhonePayloads.FEED_LIKE);
		}
	}

	void onCommentsGet(PhonePlayer player, PhonePayloads.FeedCommentsGet packet) {
		UUID who = player.getUUID();
		Post post = enabled() ? byId.get(packet.post()) : null;
		if (post == null || !visibleTo(who, post)) {
			PhoneService.send(player, new PhonePayloads.FeedGone(packet.post()));
			return;
		}
		sendComments(player, post);
	}

	private void sendComments(PhonePlayer player, Post post) {
		UUID who = player.getUUID();
		List<PhonePayloads.FeedCommentEntry> list = new ArrayList<>();
		for (Comment c : post.comments) {
			if (!apart(who, c.author)) {
				list.add(new PhonePayloads.FeedCommentEntry(c.id, handle(c.author), displayName(c.author), c.time, c.text, c.author.equals(who)));
			}
		}
		PhoneService.send(player, new PhonePayloads.FeedComments(post.id, list));
		PhoneService.send(player, new PhonePayloads.FeedPost(entry(who, post)));
	}

	void onComment(PhonePlayer player, PhonePayloads.FeedComment packet) {
		UUID who = player.getUUID();
		Post post = enabled() ? byId.get(packet.post()) : null;
		String text = PhoneService.clean(packet.text(), PhonePayloads.MAX_COMMENT);
		if (post == null || text.isEmpty() || !visibleTo(who, post)) {
			return;
		}
		if (!service.canSpeak(player) || !allowComment(player)) {
			return;
		}
		int mine = 0;
		for (Comment k : post.comments) {
			if (k.author.equals(who)) {
				mine++;
			}
		}
		if (mine >= COMMENTS_PER_AUTHOR || totalComments >= MAX_TOTAL_COMMENTS) {
			PhoneService.notice(player, "handyphone.notice.feed.comment_rate");
			return;
		}
		rememberName(player);
		Comment c = new Comment();
		c.id = data.nextComment++;
		c.author = who;
		c.text = text;
		c.time = service.now();
		post.comments.add(c);
		totalComments++;
		while (post.comments.size() > PhonePayloads.MAX_COMMENTS) {
			post.comments.remove(0);
			totalComments--;
		}
		dirty = true;
		sendComments(player, post);
		if (!post.owner.equals(who)) {
			activity(post, who, PhonePayloads.FEED_COMMENT);
		}
	}

	private boolean allowComment(PhonePlayer player) {
		long now = service.now();
		ArrayDeque<Long> times = commentTimes.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() > 3600_000L) {
			times.pollFirst();
		}
		int lastMinute = 0;
		for (long t : times) {
			if (now - t <= 60_000L) {
				lastMinute++;
			}
		}
		if (lastMinute >= COMMENTS_PER_MINUTE || times.size() >= COMMENTS_PER_HOUR) {
			PhoneService.notice(player, "handyphone.notice.feed.comment_rate");
			return false;
		}
		times.addLast(now);
		return true;
	}

	void onCommentDelete(PhonePlayer player, PhonePayloads.FeedCommentDelete packet) {
		UUID who = player.getUUID();
		Post post = byId.get(packet.post());
		if (post == null) {
			return;
		}
		// O autor apaga o próprio comentário; o dono da publicação apaga qualquer um nela.
		boolean owner = post.owner.equals(who);
		if (post.comments.removeIf(c -> c.id == packet.comment() && (owner || c.author.equals(who)))) {
			totalComments--;
			dirty = true;
			if (visibleTo(who, post)) {
				sendComments(player, post);
			}
		}
	}

	void onDelete(PhonePlayer player, PhonePayloads.FeedDelete packet) {
		Post post = byId.get(packet.post());
		if (post != null && post.owner.equals(player.getUUID())) {
			removePost(post, true);
		}
	}

	void onReport(PhonePlayer player, PhonePayloads.FeedReport packet) {
		UUID who = player.getUUID();
		Post post = enabled() ? byId.get(packet.post()) : null;
		if (post == null || post.owner.equals(who) || !visibleTo(who, post) || service.isMuted(who)) {
			return;
		}
		long now = service.now();
		ArrayDeque<Long> times = reportTimes.computeIfAbsent(who, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() > 3600_000L) {
			times.pollFirst();
		}
		if (times.size() >= REPORTS_PER_HOUR) {
			PhoneService.notice(player, "handyphone.notice.feed.report_rate");
			return;
		}
		times.addLast(now);
		Report report = new Report();
		report.post = post.id;
		report.reporter = who;
		report.time = now;
		data.reports.add(report);
		post.hiddenFor.add(who);
		while (data.reports.size() > MAX_REPORTS) {
			data.reports.remove(0);
		}
		dirty = true;
		HandyPhoneCore.LOGGER.info("HandyPhone: {} reported scroll.in post {} (by {})", player.name(), post.id, displayName(post.owner));
		// Some do feed de quem denunciou; os administradores no jogo (com o mod) ficam sabendo.
		PhoneService.send(player, new PhonePayloads.FeedGone(post.id));
		for (PhonePlayer admin : service.onlinePlayers()) {
			if (admin.isAdmin() && !admin.getUUID().equals(who)) {
				PhoneService.send(admin, new PhonePayloads.FeedActivity(PhonePayloads.FEED_REPORT, "", post.id));
			}
		}
	}

	/** Uma linha da fila para o comando {@code /handyphone reports}: publicação, autor, legenda, quantas denúncias e a última. */
	record ReportSummary(long post, String owner, String caption, int count, long last) {
	}

	/** A fila agrupada por publicação, da denúncia mais recente para a mais antiga. */
	List<ReportSummary> reportQueue() {
		Map<Long, int[]> counts = new LinkedHashMap<>();
		Map<Long, Long> last = new HashMap<>();
		for (Report r : data.reports) {
			counts.computeIfAbsent(r.post, k -> new int[1])[0]++;
			last.merge(r.post, r.time, Math::max);
		}
		List<ReportSummary> out = new ArrayList<>();
		counts.forEach((id, n) -> {
			Post post = byId.get(id);
			if (post != null) {
				out.add(new ReportSummary(id, displayName(post.owner), post.caption, n[0], last.get(id)));
			}
		});
		out.sort((a, b) -> Long.compare(b.last(), a.last()));
		return out;
	}

	/** O administrador olhou e deixou a publicação: as denúncias dela saem da fila (quem denunciou continua sem vê-la). */
	int dismissReports(long post) {
		int before = data.reports.size();
		data.reports.removeIf(r -> r.post == post);
		int removed = before - data.reports.size();
		if (removed > 0) {
			dirty = true;
		}
		return removed;
	}

	/** O que o comando {@code post remove} arquiva antes de tirar a publicação (null se não existe). */
	Map<String, Object> proofOfPost(long id) {
		Post post = byId.get(id);
		if (post == null) {
			return null;
		}
		Map<String, Object> proof = new LinkedHashMap<>();
		proof.put("publicacao", post.id);
		proof.put("autor", post.owner.toString());
		proof.put("nome", displayName(post.owner));
		proof.put("foto", post.photo);
		proof.put("legenda", post.caption);
		proof.put("hora", post.time);
		proof.put("curtidas", post.likes.size());
		List<Object> comments = new ArrayList<>();
		for (Comment c : post.comments) {
			comments.add(Map.of("autor", c.author.toString(), "nome", displayName(c.author), "texto", c.text, "hora", c.time));
		}
		proof.put("comentarios", comments);
		List<Object> reports = new ArrayList<>();
		for (Report r : data.reports) {
			if (r.post == id) {
				reports.add(Map.of("quem", r.reporter.toString(), "hora", r.time));
			}
		}
		proof.put("denuncias", reports);
		return proof;
	}

	/** Avisa o dono da publicação (se está no jogo e não há bloqueio entre os dois). */
	private void activity(Post post, UUID actor, int kind) {
		if (apart(post.owner, actor)) {
			return;
		}
		PhonePlayer owner = service.online(post.owner);
		if (owner != null) {
			PhoneService.send(owner, new PhonePayloads.FeedActivity(kind, displayName(actor), post.id));
			PhoneService.send(owner, new PhonePayloads.FeedPost(entry(post.owner, post)));
		}
	}

	// ------------------------------------------------------------------ remoção

	/** Tira a publicação (dono, administrador, teto, foto que saiu) e avisa quem está no jogo. */
	void removePost(Post post, boolean announce) {
		if (byId.remove(post.id) == null) {
			return;
		}
		data.posts.remove(post);
		byPhoto.remove(post.photo);
		totalComments -= post.comments.size();
		data.reports.removeIf(r -> r.post == post.id);
		dirty = true;
		service.removePhotoRef(post.photo, PhoneService.FEED_REF);
		for (PhonePlayer p : service.onlinePlayers()) {
			PhoneService.send(p, new PhonePayloads.FeedGone(post.id));
		}
	}

	/** A foto saiu da galeria do dono (apagada, wipe): as publicações dela saem antes. O ÚNICO caminho de "foto saiu, publicação sai". */
	void photoRemoved(long photo) {
		for (Post post : new ArrayList<>(data.posts)) {
			if (post.photo == photo) {
				removePost(post, true);
			}
		}
	}

	/** Quantas publicações usam a foto (o "Apagar" da Galeria avisa antes). */
	int postsOfPhoto(long photo) {
		int n = 0;
		for (Post post : data.posts) {
			if (post.photo == photo) {
				n++;
			}
		}
		return n;
	}

	/** A prova do que o wipe vai apagar do feed (antes de apagar): publicações e comentários da conta. */
	List<Object> proofOf(UUID target) {
		List<Object> proof = new ArrayList<>();
		for (Post post : data.posts) {
			if (post.owner.equals(target)) {
				proof.add(Map.of("publicacao", post.id, "foto", post.photo, "legenda", post.caption, "hora", post.time));
			}
			for (Comment c : post.comments) {
				if (c.author.equals(target)) {
					proof.add(Map.of("comentario", c.id, "publicacao", post.id, "texto", c.text, "hora", c.time));
				}
			}
		}
		return proof;
	}

	/** Wipe: tira as publicações, curtidas e comentários da conta. */
	void wipe(UUID target) {
		for (Post post : new ArrayList<>(data.posts)) {
			if (post.owner.equals(target)) {
				removePost(post, true);
				continue;
			}
			if (post.likes.remove(target)) {
				dirty = true;
			}
			int before = post.comments.size();
			if (post.comments.removeIf(c -> c.author.equals(target))) {
				totalComments -= before - post.comments.size();
				dirty = true;
			}
			if (post.hiddenFor.remove(target)) {
				dirty = true;
			}
		}
		data.parked.removeIf(p -> p.owner.equals(target));
		// O nick e o id opaco da conta também saem (retenção de dados); uma conta nova no feed ganha outro id.
		String handle = data.handles.remove(target);
		if (handle != null) {
			byHandle.remove(handle);
		}
		data.names.remove(target);
		if (data.reports.removeIf(r -> r.reporter.equals(target))) {
			dirty = true;
		}
		publishes.remove(target);
		commentTimes.remove(target);
		reportTimes.remove(target);
	}

	void onDisconnect(UUID uuid) {
		// As janelas de frequência (comentários, publicações, denúncias) ficam: sair e entrar não zera o limite. As vencidas saem a cada uso.
	}

	// ------------------------------------------------------------------ testes

	int postCount() {
		return data.posts.size();
	}

	Post post(long id) {
		return byId.get(id);
	}

	UUID ownerOfHandle(String handle) {
		return byHandle.get(handle);
	}

	/** Uma conta agindo sem estar no jogo (testes e gametests): mesmo caminho dos pacotes, sem nada para onde responder. */
	static PhonePlayer actor(UUID uuid, String name) {
		return new PhonePlayer() {
			@Override
			public UUID getUUID() {
				return uuid;
			}

			@Override
			public String name() {
				return name;
			}

			@Override
			public boolean canSend(dev.migzu.handyphone.net.PacketType<?> type) {
				return false;
			}

			@Override
			public void send(PhonePayloads.Payload payload) {
			}

			@Override
			public void disconnect(Msg reason) {
			}

			@Override
			public String dimension() {
				return "minecraft:overworld";
			}

			@Override
			public double getX() {
				return 0;
			}

			@Override
			public double getY() {
				return 0;
			}

			@Override
			public double getZ() {
				return 0;
			}

			@Override
			public int blockX() {
				return 0;
			}

			@Override
			public int blockY() {
				return 0;
			}

			@Override
			public int blockZ() {
				return 0;
			}

			@Override
			public boolean isSpectator() {
				return false;
			}
		};
	}

	/** Só para a medição do teto: entra uma publicação sem passar pelas regras. */
	void addForTests(Post post) {
		data.posts.add(post);
		byId.put(post.id, post);
		totalComments += post.comments.size();
	}

	List<Long> postIds() {
		List<Long> ids = new ArrayList<>();
		for (Post p : data.posts) {
			ids.add(p.id);
		}
		return ids;
	}
}
