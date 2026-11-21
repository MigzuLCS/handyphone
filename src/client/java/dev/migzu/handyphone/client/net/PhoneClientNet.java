package dev.migzu.handyphone.client.net;

import dev.migzu.handyphone.client.os.Lang;
import dev.migzu.handyphone.client.os.PhoneBackend;
import dev.migzu.handyphone.client.os.PhoneData;
import dev.migzu.handyphone.client.os.PhoneOS;
import dev.migzu.handyphone.client.phone.PhoneController;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.photo.PhotoCodec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Ponte entre a rede do jogo e o celular: transforma os pacotes do servidor em
 * dados do {@link PhoneOS} e os pedidos do celular em pacotes.
 */
public final class PhoneClientNet implements PhoneBackend {
	private static final PhoneClientNet INSTANCE = new PhoneClientNet();
	/** Quanto esperar pela resposta do servidor a um envio de foto antes de desistir dele. */
	private static final long UPLOAD_RESULT_TIMEOUT_MS = 30_000;

	/** Um envio de foto aguardando a vez ou em andamento. */
	private static final class PendingUpload {
		final int token;
		final byte[] bytes;
		final int source;
		int nextChunk;
		boolean endSent;
		long startedAt;

		PendingUpload(int token, byte[] bytes, int source) {
			this.token = token;
			this.bytes = bytes;
			this.source = source;
		}
	}

	private record TileKey(int epoch, int zoom, int x, int z) {
	}

	/** Partes de uma foto sendo recebidas. */
	private static final class Download {
		final int count;
		int next;
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

		Download(int count) {
			this.count = count;
		}
	}

	// Tudo abaixo só é mexido na thread do jogo.
	private final ArrayDeque<PendingUpload> uploadQueue = new ArrayDeque<>();
	private PendingUpload activeUpload;
	private int nextToken = 1;
	/** Muda a cada desconexão: trabalho de uma sessão (decodificação, envio na fila) nunca vale na seguinte. */
	private volatile int generation;
	/** Fotos pedidas e ainda não recebidas (chave: id * 2, mais 1 para foto inteira); só o que foi pedido é aceito. */
	private final Set<Long> requested = new HashSet<>();
	private final Map<Long, Download> downloads = new HashMap<>();
	/** Tiles pedidos e ainda não recebidos; só o que foi pedido é aceito. */
	private final Set<TileKey> requestedTiles = new HashSet<>();
	private final Map<TileKey, Download> tileDownloads = new HashMap<>();
	/** Decodificar JPEG não pode acontecer na thread do jogo nem na de desenho. */
	private final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "HandyPhone-PhotoDecode");
		thread.setDaemon(true);
		return thread;
	});

	private PhoneClientNet() {
	}

	public static void register() {
		PhoneOS os = PhoneController.INSTANCE.os;

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			synchronized (os) {
				os.setBackend(INSTANCE);
			}
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			INSTANCE.resetPhotoState();
			synchronized (os) {
				os.clearData();
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> INSTANCE.tickUploads());

		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Sync.TYPE, (packet, ctx) -> {
			Map<String, String> contacts = new LinkedHashMap<>();
			for (PhonePayloads.ContactEntry contact : packet.contacts()) {
				contacts.put(contact.number(), contact.name());
			}
			List<PhoneData.Conversation> conversations = new ArrayList<>();
			for (PhonePayloads.ConversationEntry entry : packet.conversations()) {
				conversations.add(conversationOf(entry));
			}
			synchronized (os) {
				os.applySync(packet.number(), contacts, conversations);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.ConvUpdate.TYPE, (packet, ctx) -> {
			PhoneData.Conversation c = conversationOf(packet.entry());
			synchronized (os) {
				os.applyConversation(c);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Blocked.TYPE, (packet, ctx) -> {
			List<String> numbers = List.copyOf(packet.numbers());
			synchronized (os) {
				os.applyBlocked(numbers);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Messages.TYPE, (packet, ctx) -> {
			List<PhoneData.Message> messages = new ArrayList<>();
			for (PhonePayloads.MessageEntry m : packet.messages()) {
				PhonePayloads.Loc l = m.loc();
				PhoneData.Loc loc = l == null ? null : new PhoneData.Loc(l.stamped(), l.dimension(), l.x(), l.hasY(), l.y(), l.z(), l.label());
				messages.add(new PhoneData.Message(m.seq(), m.sender(), m.time(), m.text(), m.photo(), loc));
			}
			synchronized (os) {
				os.applyMessages(packet.conversation(), packet.history(), packet.focus(), messages);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Notice.TYPE, (packet, ctx) -> {
			synchronized (os) {
				os.notify(Lang.t("handyphone.banner.notice"), Lang.notice(packet.key(), packet.args()), -1);
			}
		});

		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Gallery.TYPE, (packet, ctx) -> {
			List<PhoneData.GalleryPhoto> photos = new ArrayList<>();
			for (PhonePayloads.PhotoEntry entry : packet.photos()) {
				photos.add(new PhoneData.GalleryPhoto(entry.id(), entry.time(), entry.size()));
			}
			synchronized (os) {
				os.applyGallery(photos, packet.usedBytes(), packet.quotaBytes(), packet.importAllowed());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.PhotoData.TYPE, (packet, ctx) -> INSTANCE.onPhotoData(os, packet));
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.PhotoUnavailable.TYPE, (packet, ctx) -> {
			long key = photoKey(packet.id(), packet.thumb());
			INSTANCE.requested.remove(key);
			INSTANCE.downloads.remove(key);
			synchronized (os) {
				os.applyPhotoFailed(packet.id(), packet.thumb(), packet.retry());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.UploadResult.TYPE, (packet, ctx) -> INSTANCE.onUploadResult(os, packet));

		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.MapInfo.TYPE, (packet, ctx) -> {
			synchronized (os) {
				os.applyMapInfo(
					packet.installed(), packet.rendered(), packet.maxZoom(), packet.dimension(), packet.epoch(),
					packet.liveEnabled(), packet.messagesEnabled(), packet.maxPlaces()
				);
			}
			// Os pedidos e as partes da época anterior não valem mais.
			INSTANCE.requestedTiles.removeIf(key -> key.epoch() != packet.epoch());
			INSTANCE.tileDownloads.keySet().removeIf(key -> key.epoch() != packet.epoch());
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.TileData.TYPE, (packet, ctx) -> INSTANCE.onTileData(os, packet));
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.TileUnavailable.TYPE, (packet, ctx) -> {
			TileKey key = new TileKey(packet.epoch(), packet.zoom(), packet.x(), packet.z());
			INSTANCE.requestedTiles.remove(key);
			INSTANCE.tileDownloads.remove(key);
			synchronized (os) {
				if (packet.reason() == PhonePayloads.TILE_UNCHANGED) {
					os.applyTileUnchanged(packet.epoch(), packet.zoom(), packet.x(), packet.z());
				} else {
					os.applyTileFailed(packet.epoch(), packet.zoom(), packet.x(), packet.z(), packet.reason() != PhonePayloads.TILE_MISSING);
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Live.TYPE, (packet, ctx) -> {
			long now = System.currentTimeMillis();
			List<PhoneData.Live> entries = new ArrayList<>();
			for (PhonePayloads.LiveEntry e : packet.entries()) {
				entries.add(new PhoneData.Live(e.number(), e.state(), e.dimension(), e.x(), e.y(), e.z(), now + e.secondsLeft() * 1000L));
			}
			synchronized (os) {
				os.applyLive(entries);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.ShareStatus.TYPE, (packet, ctx) -> {
			synchronized (os) {
				os.applyShareStatus(packet.active(), packet.secondsLeft(), packet.audience());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.Places.TYPE, (packet, ctx) -> {
			List<PhoneData.Place> places = new ArrayList<>();
			for (PhonePayloads.PlaceEntry p : packet.places()) {
				places.add(new PhoneData.Place(p.id(), p.name(), p.dimension(), p.x(), p.hasY(), p.y(), p.z(), p.color()));
			}
			synchronized (os) {
				os.applyPlaces(places);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.CallInfo.TYPE, (packet, ctx) -> {
			synchronized (os) {
				os.applyCallInfo(packet.available(), packet.crossDimension());
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.CallLog.TYPE, (packet, ctx) -> {
			List<PhoneData.CallLogEntry> entries = new ArrayList<>();
			for (PhonePayloads.CallLogEntry e : packet.entries()) {
				entries.add(new PhoneData.CallLogEntry(e.time(), e.conversation(), e.number(), e.group(), e.outgoing(), e.answered(), e.seconds()));
			}
			synchronized (os) {
				os.applyCallLog(entries);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(PhonePayloads.CallUpdate.TYPE, (packet, ctx) -> {
			List<PhoneData.CallPart> parts = new ArrayList<>();
			for (PhonePayloads.CallPart part : packet.parts()) {
				parts.add(new PhoneData.CallPart(part.number(), part.joined(), part.voice(), part.muted()));
			}
			synchronized (os) {
				os.applyCall(packet.id(), packet.state(), packet.reason(), packet.conversation(), packet.muted(), packet.voice(), packet.seconds(), parts);
			}
		});
	}

	private static PhoneData.Conversation conversationOf(PhonePayloads.ConversationEntry entry) {
		PhoneData.Conversation c = new PhoneData.Conversation();
		c.id = entry.id();
		c.group = entry.group();
		c.title = entry.title();
		c.members = List.copyOf(entry.members());
		c.unread = entry.unread();
		c.lastSeq = entry.lastSeq();
		c.lastSender = entry.lastSender();
		c.lastText = entry.lastText();
		c.lastPhoto = entry.lastPhoto();
		c.lastLocation = entry.lastLocation();
		c.lastTime = entry.lastTime();
		return c;
	}

	private static long photoKey(long id, boolean thumb) {
		return id * 2 + (thumb ? 0 : 1);
	}

	private void resetPhotoState() {
		generation++;
		Minecraft.getInstance().execute(() -> {
			uploadQueue.clear();
			activeUpload = null;
			requested.clear();
			downloads.clear();
			requestedTiles.clear();
			tileDownloads.clear();
		});
	}

	// ------------------------------------------------------------------ fotos: download

	/** Junta as partes de uma foto pedida e a decodifica fora da thread do jogo. Tudo é conferido: o servidor também pode errar. */
	private void onPhotoData(PhoneOS os, PhonePayloads.PhotoData packet) {
		long key = photoKey(packet.id(), packet.thumb());
		if (!requested.contains(key)) {
			return;
		}
		int maxParts = PhotoCodec.MAX_PHOTO_BYTES / PhonePayloads.MAX_CHUNK + 1;
		Download download = downloads.get(key);
		if (packet.index() == 0) {
			if (packet.count() < 1 || packet.count() > maxParts) {
				requested.remove(key);
				return;
			}
			download = new Download(packet.count());
			downloads.put(key, download);
		}
		if (download == null || packet.index() != download.next || packet.count() != download.count
			|| download.bytes.size() + packet.data().length > PhotoCodec.MAX_PHOTO_BYTES) {
			downloads.remove(key);
			requested.remove(key);
			return;
		}
		download.bytes.writeBytes(packet.data());
		download.next++;
		if (download.next < download.count) {
			return;
		}
		downloads.remove(key);
		requested.remove(key);
		byte[] bytes = download.bytes.toByteArray();
		long id = packet.id();
		boolean thumb = packet.thumb();
		int session = generation;
		decoder.execute(() -> {
			try {
				BufferedImage image = PhotoCodec.decodeStored(bytes);
				synchronized (os) {
					if (session == generation) {
						os.applyPhoto(id, thumb, image);
					}
				}
			} catch (PhotoCodec.PhotoException | RuntimeException e) {
				synchronized (os) {
					if (session == generation) {
						os.applyPhotoFailed(id, thumb, false);
					}
				}
			}
		});
	}

	// ------------------------------------------------------------------ tiles do mapa

	/** Junta as partes de um tile pedido e o decodifica fora da thread do jogo. O cabeçalho do PNG é conferido antes. */
	private void onTileData(PhoneOS os, PhonePayloads.TileData packet) {
		TileKey key = new TileKey(packet.epoch(), packet.zoom(), packet.x(), packet.z());
		if (!requestedTiles.contains(key)) {
			return;
		}
		int maxParts = PhonePayloads.MAX_TILE_BYTES / PhonePayloads.MAX_CHUNK + 1;
		Download download = tileDownloads.get(key);
		if (packet.index() == 0) {
			if (packet.count() < 1 || packet.count() > maxParts) {
				requestedTiles.remove(key);
				return;
			}
			download = new Download(packet.count());
			tileDownloads.put(key, download);
		}
		if (download == null || packet.index() != download.next || packet.count() != download.count
			|| download.bytes.size() + packet.data().length > PhonePayloads.MAX_TILE_BYTES) {
			tileDownloads.remove(key);
			requestedTiles.remove(key);
			return;
		}
		download.bytes.writeBytes(packet.data());
		download.next++;
		if (download.next < download.count) {
			return;
		}
		tileDownloads.remove(key);
		requestedTiles.remove(key);
		byte[] bytes = download.bytes.toByteArray();
		int session = generation;
		long stamp = packet.stamp();
		decoder.execute(() -> {
			BufferedImage image = decodeTile(bytes);
			synchronized (os) {
				if (session == generation) {
					if (image != null) {
						os.applyTile(key.epoch(), key.zoom(), key.x(), key.z(), image, stamp);
					} else {
						os.applyTileFailed(key.epoch(), key.zoom(), key.x(), key.z(), false);
					}
				}
			}
		});
	}

	/** Lê o PNG só se as dimensões declaradas couberem em 512x512 (nenhuma imagem gigante decodificada por engano). Null se inválido. */
	private static BufferedImage decodeTile(byte[] bytes) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			var readers = ImageIO.getImageReadersByFormatName("png");
			if (input == null || !readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0), height = reader.getHeight(0);
				if (width < 1 || height < 1 || width > 512 || height > 512) {
					return null;
				}
				return reader.read(0);
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	// ------------------------------------------------------------------ fotos: envio

	/**
	 * Um envio por vez (o servidor também só aceita um): começa, manda uma parte por tick,
	 * termina e espera a resposta antes de passar para o próximo da fila.
	 */
	private void tickUploads() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() == null) {
			return;
		}
		if (activeUpload == null) {
			activeUpload = uploadQueue.poll();
			if (activeUpload == null) {
				return;
			}
			activeUpload.startedAt = System.currentTimeMillis();
			ClientPlayNetworking.send(new PhonePayloads.UploadBegin(
				activeUpload.token, activeUpload.bytes.length, PhotoCodec.FORMAT_JPEG, activeUpload.source
			));
			return;
		}
		PendingUpload upload = activeUpload;
		int from = upload.nextChunk * PhonePayloads.MAX_CHUNK;
		if (from < upload.bytes.length) {
			int to = Math.min(upload.bytes.length, from + PhonePayloads.MAX_CHUNK);
			ClientPlayNetworking.send(new PhonePayloads.UploadChunk(upload.nextChunk++, Arrays.copyOfRange(upload.bytes, from, to)));
		} else if (!upload.endSent) {
			upload.endSent = true;
			ClientPlayNetworking.send(new PhonePayloads.UploadEnd(upload.token));
		} else if (System.currentTimeMillis() - upload.startedAt > UPLOAD_RESULT_TIMEOUT_MS) {
			activeUpload = null;
			PhoneOS os = PhoneController.INSTANCE.os;
			synchronized (os) {
				os.applyUploadResult(false);
				os.notify(Lang.t("handyphone.banner.photos"), Lang.t("handyphone.photo.upload_timeout"), -1);
			}
		}
	}

	private void onUploadResult(PhoneOS os, PhonePayloads.UploadResult result) {
		// Só o resultado do envio em andamento conta: uma resposta atrasada de um envio já desistido não pode descontar outro.
		if (activeUpload == null || activeUpload.token != result.token()) {
			return;
		}
		activeUpload = null;
		synchronized (os) {
			os.applyUploadResult(result.ok());
		}
	}

	/**
	 * Os pedidos saem do celular com a trava do PhoneOS presa (às vezes na thread
	 * de desenho), então o envio é repassado para a thread do jogo.
	 */
	private static void send(CustomPacketPayload payload) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.getConnection() != null && ClientPlayNetworking.canSend(payload.type())) {
				ClientPlayNetworking.send(payload);
			}
		});
	}

	@Override
	public void open(long conversation, int have) {
		send(new PhonePayloads.Open(conversation, have));
	}

	@Override
	public void openDirect(String number) {
		send(new PhonePayloads.OpenDirect(number));
	}

	@Override
	public void send(long conversation, String text) {
		send(new PhonePayloads.Send(conversation, text));
	}

	@Override
	public void markRead(long conversation, int seq) {
		send(new PhonePayloads.Read(conversation, seq));
	}

	@Override
	public void setContact(String number, String name) {
		send(new PhonePayloads.Contact(number, name));
	}

	@Override
	public void createGroup(String title, List<String> numbers) {
		send(new PhonePayloads.CreateGroup(title, numbers));
	}

	@Override
	public void block(String number, boolean on) {
		send(new PhonePayloads.Block(number, on));
	}

	@Override
	public void leaveGroup(long conversation) {
		send(new PhonePayloads.LeaveGroup(conversation));
	}

	@Override
	public void requestPhoto(long id, boolean thumb) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.getConnection() != null && ClientPlayNetworking.canSend(PhonePayloads.PhotoGet.TYPE)) {
				requested.add(photoKey(id, thumb));
				ClientPlayNetworking.send(new PhonePayloads.PhotoGet(id, thumb));
			}
		});
	}

	@Override
	public boolean uploadPhoto(byte[] jpeg, boolean fromComputer) {
		// Chamado fora da thread do jogo (a de imagens): a conferência real da conexão é feita dentro do mc.execute.
		Minecraft mc = Minecraft.getInstance();
		int source = fromComputer ? PhonePayloads.SOURCE_IMPORT : PhonePayloads.SOURCE_CAMERA;
		int session = generation;
		mc.execute(() -> {
			if (session == generation && mc.getConnection() != null && ClientPlayNetworking.canSend(PhonePayloads.UploadBegin.TYPE)) {
				uploadQueue.add(new PendingUpload(nextToken++, jpeg, source));
			} else {
				PhoneOS os = PhoneController.INSTANCE.os;
				synchronized (os) {
					os.applyUploadResult(false);
				}
			}
		});
		return true;
	}

	@Override
	public void deletePhoto(long id) {
		send(new PhonePayloads.PhotoDelete(id));
	}

	@Override
	public void sendPhoto(long conversation, long photo) {
		send(new PhonePayloads.SendPhoto(conversation, photo));
	}

	@Override
	public void requestTile(int epoch, int zoom, int x, int z, long have) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.getConnection() != null && ClientPlayNetworking.canSend(PhonePayloads.TileGet.TYPE)) {
				requestedTiles.add(new TileKey(epoch, zoom, x, z));
				ClientPlayNetworking.send(new PhonePayloads.TileGet(epoch, zoom, x, z, have));
			}
		});
	}

	@Override
	public void sendLocation(long conversation) {
		send(new PhonePayloads.SendLocation(conversation));
	}

	@Override
	public void sendPin(long conversation, int x, int z) {
		send(new PhonePayloads.SendPin(conversation, x, z));
	}

	@Override
	public void shareStart(int duration, long conversation, List<String> numbers) {
		send(new PhonePayloads.ShareStart(duration, conversation, numbers));
	}

	@Override
	public void shareStop() {
		send(new PhonePayloads.ShareStop());
	}

	@Override
	public void watch(boolean on) {
		send(new PhonePayloads.Watch(on));
	}

	@Override
	public void savePlace(String name, String dimension, int x, boolean hasY, int y, int z, int color) {
		send(new PhonePayloads.PlaceSave(name, dimension, x, hasY, y, z, color));
	}

	@Override
	public void editPlace(long id, String name, int color) {
		send(new PhonePayloads.PlaceEdit(id, name, color));
	}

	@Override
	public void sendPlace(long conversation, long place) {
		send(new PhonePayloads.SendPlace(conversation, place));
	}

	@Override
	public void deletePlace(long id) {
		send(new PhonePayloads.PlaceDelete(id));
	}

	@Override
	public void callStart(String number) {
		send(new PhonePayloads.CallStart(number));
	}

	@Override
	public void callGroup(long conversation) {
		send(new PhonePayloads.CallGroup(conversation));
	}

	@Override
	public void callAnswer(long id) {
		send(new PhonePayloads.CallAnswer(id));
	}

	@Override
	public void callDecline(long id) {
		send(new PhonePayloads.CallDecline(id));
	}

	@Override
	public void callMute(long id, boolean on) {
		send(new PhonePayloads.CallMute(id, on));
	}
}
