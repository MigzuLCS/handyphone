package dev.migzu.handyphone.server;

import dev.migzu.handyphone.net.PhonePayloads;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Celular visto de fora (1.3): quem está com o celular na mão, e como (usando, câmera, selfie), repassado aos jogadores perto. Só a pose e o
 * tema viajam; nada da tela. Quem desligou "Mostrar o celular para os outros" manda sempre "guardado", e o servidor não tem o que repassar.
 *
 * <p>Na thread do servidor; nada vai ao disco. Cada jogador com o mod recebe só as mudanças: o serviço lembra o que já mandou a cada um
 * ({@link #sent}) e, quando alguém muda de pose ou entra/sai do alcance (mesma dimensão, até {@link #RANGE} blocos), manda a diferença.
 * O cliente desenha só até 30 blocos; a folga cobre o movimento entre duas conferências.
 */
public final class PoseService {
	/** Alcance do repasse, em blocos (o cliente desenha até 30). */
	static final double RANGE = 48;
	/** A cada quantos ticks o alcance é conferido de novo (as mudanças de pose saem no mesmo tick). */
	static final int REFRESH_TICKS = 20;

	private final PhoneService service;
	/** Pose atual de quem está com o celular fora: UUID -> código (pose * 16 + tema). Ausente = guardado. */
	private final Map<UUID, Integer> poses = new HashMap<>();
	/** O que cada jogador já recebeu: quem olha -> (quem segura -> código). */
	private final Map<UUID, Map<UUID, Integer>> sent = new HashMap<>();
	private boolean dirty;
	private int ticks;

	PoseService(PhoneService service) {
		this.service = service;
	}

	static int code(int pose, int theme) {
		return pose * 16 + theme;
	}

	/** Pacote {@code PhonePose}: valores fora da faixa viram "guardado" (pose) ou o tema padrão (o cliente também desenha o padrão). */
	void onPose(PhonePlayer player, PhonePayloads.PhonePose packet) {
		int pose = packet.pose();
		int theme = packet.theme();
		if (pose < 0 || pose > PhonePayloads.MAX_POSE) {
			pose = PhonePayloads.POSE_NONE;
		}
		if (theme < 0 || theme >= PhonePayloads.POSE_THEMES) {
			theme = 2;
		}
		UUID id = player.getUUID();
		Integer before = poses.get(id);
		if (pose == PhonePayloads.POSE_NONE) {
			if (poses.remove(id) != null) {
				dirty = true;
			}
		} else if (before == null || before != code(pose, theme)) {
			poses.put(id, code(pose, theme));
			dirty = true;
		}
	}

	void onDisconnect(UUID uuid) {
		if (poses.remove(uuid) != null) {
			dirty = true;
		}
		sent.remove(uuid);
		// Quem viu este jogador recebe "guardado" na próxima conferência (ele não está mais na lista de ninguém).
		for (Map<UUID, Integer> seen : sent.values()) {
			if (seen.containsKey(uuid)) {
				dirty = true;
				break;
			}
		}
	}

	void tick() {
		if (++ticks % REFRESH_TICKS != 0 && !dirty) {
			return;
		}
		dirty = false;
		refresh();
	}

	/** Manda a cada jogador com o mod o que mudou perto dele desde o último envio. */
	private void refresh() {
		if (poses.isEmpty() && sent.isEmpty()) {
			return;
		}
		// Quem segura o celular, uma vez por conferência (a plataforma pode trocar a instância do jogador: pede de novo).
		List<PhonePlayer> holders = new ArrayList<>(poses.size());
		for (Iterator<Map.Entry<UUID, Integer>> it = poses.entrySet().iterator(); it.hasNext();) {
			PhonePlayer holder = service.online(it.next().getKey());
			if (holder == null) {
				it.remove();
			} else if (!holder.isSpectator()) {
				holders.add(holder);
			}
		}
		Set<UUID> viewers = new HashSet<>();
		for (PhonePlayer viewer : service.onlinePlayers()) {
			if (!viewer.canSend(PhonePayloads.PhonePoses.TYPE)) {
				continue;
			}
			UUID viewerId = viewer.getUUID();
			viewers.add(viewerId);
			Map<UUID, Integer> now = new HashMap<>();
			String dim = viewer.dimension();
			double vx = viewer.getX(), vy = viewer.getY(), vz = viewer.getZ();
			for (PhonePlayer holder : holders) {
				UUID holderId = holder.getUUID();
				if (holderId.equals(viewerId) || !dim.equals(holder.dimension())) {
					continue;
				}
				double dx = holder.getX() - vx, dy = holder.getY() - vy, dz = holder.getZ() - vz;
				if (dx * dx + dy * dy + dz * dz <= RANGE * RANGE) {
					now.put(holderId, poses.get(holderId));
				}
			}
			Map<UUID, Integer> before = sent.getOrDefault(viewerId, Map.of());
			List<PhonePayloads.PoseEntry> changes = new ArrayList<>();
			for (Map.Entry<UUID, Integer> e : now.entrySet()) {
				if (!e.getValue().equals(before.get(e.getKey()))) {
					changes.add(entry(e.getKey(), e.getValue()));
				}
			}
			for (UUID gone : before.keySet()) {
				if (!now.containsKey(gone)) {
					changes.add(entry(gone, code(PhonePayloads.POSE_NONE, 0)));
				}
			}
			if (changes.isEmpty()) {
				continue;
			}
			for (int from = 0; from < changes.size(); from += PhonePayloads.MAX_POSES) {
				viewer.send(new PhonePayloads.PhonePoses(List.copyOf(changes.subList(from, Math.min(changes.size(), from + PhonePayloads.MAX_POSES)))));
			}
			if (now.isEmpty()) {
				sent.remove(viewerId);
			} else {
				sent.put(viewerId, now);
			}
		}
		sent.keySet().retainAll(viewers);
	}

	private static PhonePayloads.PoseEntry entry(UUID id, int code) {
		return new PhonePayloads.PoseEntry(id.getMostSignificantBits(), id.getLeastSignificantBits(), code / 16, code % 16);
	}

	/** Só para os testes: a pose atual guardada de um jogador (0 = guardado). */
	int poseOf(UUID uuid) {
		Integer code = poses.get(uuid);
		return code == null ? PhonePayloads.POSE_NONE : code / 16;
	}
}
