package dev.migzu.handyphone.server;

import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.net.PhonePayloads.CallPart;
import dev.migzu.handyphone.net.PhonePayloads.CallUpdate;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * As ligações de voz: quem liga para quem, quem atende, quem fala com quem. Mora ao lado do {@link PhoneService} (que guarda
 * contas, bloqueios e silenciamentos) e do {@link LocationService}, com os mesmos princípios: quem pede é identificado pela
 * conexão, nada do cliente é aceito sem validação e todas as decisões são do servidor. <b>Nada é gravado em disco</b>:
 * reiniciar o servidor encerra todas as ligações.
 *
 * <p><b>Modelo.</b> Uma {@code Call} tem participantes ({@link Status}: convidado, na chamada, recusou, saiu, não atendeu).
 * A ligação entre duas pessoas é o caso de 2; a chamada em grupo (etapa 5b) usa o mesmo modelo. O que cada celular vê
 * ({@link CallUpdate}) é calculado a partir dos participantes, por quem olha: quem liga só vê "chamando" até alguém atender.
 *
 * <p><b>Privacidade (quem liga não descobre nada).</b> Se o destino bloqueou quem liga, está offline ou não tem o mod, o
 * toque é "falso": quem liga vê "chamando" até o prazo e depois "não atendeu", idêntico a uma chamada comum não atendida, e
 * o destino não recebe nada (nem fica "ocupado"). Só quem <i>não</i> foi bloqueado descobre que o número está em outra
 * ligação ("ocupado") ou recusou.
 *
 * <p><b>Threads.</b> Todo o estado vive na thread do servidor. A voz passa pela thread de rede do Simple Voice Chat, que só lê
 * o {@link VoiceRouting.Snapshot} publicado em {@link VoiceStatus}: uma cópia imutável que é refeita aqui, de forma
 * síncrona, a cada mudança (atender, mudo, fim, bloqueio, silenciamento) e a cada {@link #SNAPSHOT_EVERY_TICKS} ticks
 * (posições).
 */
public final class CallService {
	/**
	 * Tentativas de ligar por jogador (contadas mesmo quando falham): spam de toque.
	 * Eram 6/min e 30/h; subiram em 2026-11-19 (Migzu travou testando com um amigo sem tentar chegar no limite).
	 */
	static final int ATTEMPTS_PER_MINUTE = 10, ATTEMPTS_PER_HOUR = 60;
	/**
	 * Toques NÃO atendidos de uma pessoa para a mesma outra (ou o mesmo grupo) dentro da janela: nem vários jogadores nem um
	 * chamador paciente incomodam alguém. Ligação atendida devolve o toque ({@link #forgiveRing}): ligar várias vezes para quem
	 * atende é uso normal. Eram 3 em 10 min contando as atendidas; mudou em 2026-11-19.
	 */
	static final int PAIR_RINGS = 5;
	static final long PAIR_WINDOW_MS = 5 * 60_000L;
	private static final long MINUTE_MS = 60_000L, HOUR_MS = 3_600_000L;
	/** A posição dos participantes (para dimensão e eco) é recopiada a cada tantos ticks. */
	static final int SNAPSHOT_EVERY_TICKS = 4;

	/** Situação de um participante dentro da ligação. */
	enum Status { INVITED, IN_CALL, DECLINED, LEFT, MISSED }

	static final class Participant {
		Status status;
		long ringDeadline;
		long joinedAt;
		boolean muted;
		/** Já esteve na chamada (atendeu ou entrou): quem só estava convidado não tem "chamada atendida" no histórico. */
		boolean everJoined;
		/** Tempo já passado na chamada em entradas anteriores (ms); a entrada em andamento conta de {@link #joinedAt}. */
		long accumulatedMs;
		/** O toque nunca chegou (bloqueou quem liga, offline, sem o mod, ou saiu do jogo tocando): só o prazo decide. */
		boolean fake;

		Participant(Status status) {
			this.status = status;
		}
	}

	static final class Call {
		long id;
		/** Conversa de grupo de onde a chamada partiu; 0 = ligação direta. */
		long conversation;
		UUID initiator;
		long answeredAt;
		boolean active;
		/** Quem tem voz entre os participantes vivos, em bits: só uma mudança aqui (e não a de qualquer jogador do servidor) gera pacote. */
		int voiceSignature;
		final Map<UUID, Participant> parts = new LinkedHashMap<>();
	}

	private final PhoneService owner;
	private final SecureRandom random = new SecureRandom();
	private final Map<Long, Call> calls = new HashMap<>();
	/** Quem está em uma ligação viva (chamando, tocando de verdade ou atendida). Quem tem toque falso não aparece aqui. */
	private final Map<UUID, Call> byPlayer = new HashMap<>();
	/** A chamada em andamento de cada grupo (uma só por grupo): quem chama um grupo que já está em chamada entra nela. */
	private final Map<Long, Call> byConversation = new HashMap<>();
	private final Map<UUID, ArrayDeque<Long>> attempts = new HashMap<>();
	private final Map<Pair, ArrayDeque<Long>> pairRings = new HashMap<>();
	/** Contas simuladas dos testes: recebem os pacotes aqui, no lugar da rede. */
	private final Map<UUID, Consumer<PhonePayloads.Payload>> fakePeers = new HashMap<>();

	private record Pair(UUID from, UUID to) {
	}

	private long snapshotVersion;
	private int tickCounter;
	private boolean lastAvailable;
	private int seenVoiceVersion = -1;

	CallService(PhoneService owner) {
		this.owner = owner;
	}

	// ------------------------------------------------------------------ disponibilidade

	/** As ligações existem neste servidor agora: ligadas na configuração e com o Simple Voice Chat no ar. */
	boolean available() {
		return PhoneService.config().callsEnabled && VoiceStatus.serverReady();
	}

	private long now() {
		return owner.now();
	}

	// ------------------------------------------------------------------ entrega

	/** Entrega um pacote a uma conta: pela rede (se está ligada e tem o mod) ou ao participante simulado do teste. */
	private void deliver(UUID who, PhonePayloads.Payload payload) {
		Consumer<PhonePayloads.Payload> fake = fakePeers.get(who);
		if (fake != null) {
			fake.accept(payload);
			return;
		}
		PhonePlayer player = owner.online(who);
		if (player != null) {
			PhoneService.send(player, payload);
		}
	}

	private void notice(UUID who, String key, String... args) {
		deliver(who, new PhonePayloads.Notice(key, args));
	}

	/** A conta pode receber o toque (está ligada e tem o mod)? Quem liga nunca descobre a resposta: ela só vira "toque falso". */
	private boolean reachable(UUID who) {
		if (fakePeers.containsKey(who)) {
			return true;
		}
		PhonePlayer player = owner.online(who);
		return player != null && player.canSend(CallUpdate.TYPE);
	}

	// ------------------------------------------------------------------ pedidos dos jogadores

	/**
	 * Pedido de ligar. A ordem das checagens importa: nenhuma resposta antes do "toque falso" pode depender do que o destino
	 * fez (bloquear, estar ocupado, estar offline).
	 */
	void start(UUID caller, String rawNumber) {
		if (!available()) {
			notice(caller, "handyphone.notice.calls_unavailable");
			return;
		}
		if (owner.isMuted(caller)) {
			notice(caller, "handyphone.notice.muted_call");
			return;
		}
		if (!allowAttempt(caller)) {
			notice(caller, "handyphone.notice.calls_rate");
			return;
		}
		if (byPlayer.containsKey(caller)) {
			notice(caller, "handyphone.notice.in_call");
			return;
		}
		PhoneService.Account account = owner.accountOf(caller);
		String number = PhoneService.digits(rawNumber);
		if (account == null || number.isEmpty()) {
			notice(caller, "handyphone.notice.enter_number");
			return;
		}
		// A varredura de números da etapa 4.6 vale para ligar também: quem tenta números novos demais é barrado.
		if (!owner.allowNewNumber(caller, account, number)) {
			notice(caller, "handyphone.notice.numbers_rate");
			return;
		}
		UUID target = owner.uuidOf(number);
		if (target == null) {
			notice(caller, "handyphone.notice.number_unknown");
			return;
		}
		if (target.equals(caller)) {
			notice(caller, "handyphone.notice.number_own");
			return;
		}
		if (owner.directCapReached(caller, target)) {
			// Mesma regra de abrir uma conversa nova (o fim da ligação deixa uma linha na conversa, então ela existe): sem teto, ligar criaria conversas sem limite.
			notice(caller, "handyphone.notice.chat_limit.call", String.valueOf(PhoneService.MAX_CONVERSATIONS));
			return;
		}
		if (!allowPair(caller, target)) {
			notice(caller, "handyphone.notice.call_pair_limit");
			return;
		}
		// Quem liga bloqueou o destino: recusa clara (não revela nada sobre o outro lado).
		if (owner.isBlocked(caller, target)) {
			notice(caller, "handyphone.notice.blocked_call");
			return;
		}

		Call call = new Call();
		call.id = newId();
		call.initiator = caller;
		long deadline = now() + PhoneService.config().ringSeconds * 1000L;
		Participant me = new Participant(Status.IN_CALL);
		me.joinedAt = now();
		me.everJoined = true;
		call.parts.put(caller, me);
		Participant them = new Participant(Status.INVITED);
		them.ringDeadline = deadline;
		call.parts.put(target, them);

		// Bloqueou quem liga, offline ou sem o mod: toca "de mentira" (só o prazo decide), e o destino não recebe nada.
		them.fake = owner.isBlocked(target, caller) || !reachable(target);
		if (!them.fake && byPlayer.containsKey(target)) {
			// Ocupado: só quem NÃO foi bloqueado fica sabendo. Não nasce ligação nenhuma.
			deliver(caller, new CallUpdate(0, PhonePayloads.CALL_ENDED, PhonePayloads.END_BUSY, 0, false, VoiceStatus.hasVoice(caller), 0,
				List.of(new CallPart(number, false, true, false))));
			return;
		}
		calls.put(call.id, call);
		byPlayer.put(caller, call);
		if (!them.fake) {
			byPlayer.put(target, call);
		}
		call.voiceSignature = voiceSignature(call);
		changed(call);
	}

	/**
	 * Chama um grupo de mensagens: toca para os membros (menos quem bloqueou quem chama, quem está offline, sem o mod, ou já em
	 * outra ligação: para esses o toque é "falso", e quem chama não distingue). Se o grupo já tem uma chamada, quem pede entra nela.
	 * Quem não é membro (ou o grupo não existe) recebe a mesma resposta.
	 */
	void startGroup(UUID caller, long conversation) {
		if (!available()) {
			notice(caller, "handyphone.notice.calls_unavailable");
			return;
		}
		if (owner.isMuted(caller)) {
			notice(caller, "handyphone.notice.muted_call");
			return;
		}
		if (!allowAttempt(caller)) {
			notice(caller, "handyphone.notice.calls_rate");
			return;
		}
		if (byPlayer.containsKey(caller)) {
			notice(caller, "handyphone.notice.in_call");
			return;
		}
		List<UUID> members = owner.groupMembers(caller, conversation);
		if (members == null) {
			notice(caller, "handyphone.notice.group_unknown");
			return;
		}
		Call existing = byConversation.get(conversation);
		if (existing != null) {
			join(caller, existing);
			return;
		}
		if (!allowPair(caller, new UUID(conversation, 0))) {
			notice(caller, "handyphone.notice.group_call_limit");
			return;
		}
		Call call = new Call();
		call.id = newId();
		call.conversation = conversation;
		call.initiator = caller;
		long deadline = now() + PhoneService.config().ringSeconds * 1000L;
		Participant me = new Participant(Status.IN_CALL);
		me.joinedAt = now();
		me.everJoined = true;
		call.parts.put(caller, me);
		for (UUID member : members) {
			if (member.equals(caller)) {
				continue;
			}
			Participant p = new Participant(Status.INVITED);
			p.ringDeadline = deadline;
			// O teto de toques por pessoa vale também aqui (senão um grupo contornaria o limite de ligar para a mesma pessoa).
			boolean pairOk = allowPair(caller, member);
			// Bloqueou quem chama, foi bloqueado por quem chama, offline, sem o mod, ocupado ou já tocado demais: toque falso. Nada disso é dito a quem chama.
			p.fake = !pairOk || owner.isBlocked(member, caller) || owner.isBlocked(caller, member) || !reachable(member) || byPlayer.containsKey(member);
			call.parts.put(member, p);
		}
		calls.put(call.id, call);
		byConversation.put(conversation, call);
		byPlayer.put(caller, call);
		for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
			if (entry.getValue().status == Status.INVITED && !entry.getValue().fake) {
				byPlayer.put(entry.getKey(), call);
			}
		}
		call.voiceSignature = voiceSignature(call);
		changed(call);
	}

	/** Quantos estão na chamada agora. */
	private int inCallCount(Call call) {
		int count = 0;
		for (Participant p : call.parts.values()) {
			if (p.status == Status.IN_CALL) {
				count++;
			}
		}
		return count;
	}

	/** Entra em uma chamada de grupo que já existe (quem não atendeu a tempo, ou saiu, volta quando quiser, enquanto ela durar). */
	private void join(UUID who, Call call) {
		if (inCallCount(call) >= PhoneService.config().maxCallParticipants) {
			notice(who, "handyphone.notice.call_full");
			return;
		}
		Participant p = call.parts.computeIfAbsent(who, k -> new Participant(Status.LEFT));
		p.status = Status.IN_CALL;
		p.fake = false;
		p.muted = false;
		p.joinedAt = now();
		p.everJoined = true;
		byPlayer.put(who, call);
		activate(call);
		call.voiceSignature = voiceSignature(call);
		changed(call);
	}

	/** A chamada passou a ter alguém além de quem chamou. */
	private void activate(Call call) {
		if (!call.active) {
			call.active = true;
			call.answeredAt = now();
			if (call.conversation != 0) {
				// Em grupo, quem passa pela conversa vê que há uma chamada em andamento; entre duas pessoas basta o aviso do fim.
				owner.postCallMessage(call.conversation, PhonePayloads.SYSTEM_CALL + "inicio:" + owner.numberOf(call.initiator), hiddenFromBlockers(call));
			}
		}
	}

	/** Os que bloquearam quem chamou: nunca veem nada da chamada (nem a mensagem, nem o histórico). */
	private List<UUID> hiddenFromBlockers(Call call) {
		List<UUID> hidden = new ArrayList<>();
		for (UUID who : call.parts.keySet()) {
			// Nos dois sentidos: quem foi bloqueado por quem chamou também não toca, e se recebesse o "perdida" concluiria que foi bloqueado.
			if (!who.equals(call.initiator) && (owner.isBlocked(who, call.initiator) || owner.isBlocked(call.initiator, who))) {
				hidden.add(who);
			}
		}
		return hidden;
	}

	/** O destino atende a ligação que está tocando. */
	void answer(UUID who, long id) {
		Call call = byPlayer.get(who);
		if (call == null || call.id != id) {
			return;
		}
		Participant me = call.parts.get(who);
		// Só quem está tocando atende, uma vez só, e antes do prazo.
		if (me == null || me.status != Status.INVITED || me.fake || now() >= me.ringDeadline) {
			return;
		}
		if (call.conversation != 0 && inCallCount(call) >= PhoneService.config().maxCallParticipants) {
			notice(who, "handyphone.notice.call_full");
			return;
		}
		me.status = Status.IN_CALL;
		me.joinedAt = now();
		me.everJoined = true;
		// Atendeu: o toque não era incômodo, então não conta para o teto por pessoa. No grupo, o toque do grupo volta no primeiro
		// atendimento (uma vez só por chamada), e o de cada membro volta quando ele atende.
		forgiveRing(call.initiator, who);
		if (call.conversation != 0 && !call.active) {
			forgiveRing(call.initiator, new UUID(call.conversation, 0));
		}
		activate(call);
		changed(call);
	}

	/**
	 * Recusa (tocando), cancela (chamando) ou desliga (em ligação): o servidor decide qual dos três pelo estado de quem pede.
	 * Quem desliga uma ligação atendida vê o fim com a duração; recusar e cancelar só voltam à tela anterior.
	 */
	void decline(UUID who, long id) {
		Call call = byPlayer.get(who);
		if (call == null || call.id != id) {
			return;
		}
		Participant me = call.parts.get(who);
		if (me == null) {
			return;
		}
		CallUpdate reply;
		if (me.status == Status.IN_CALL && call.active) {
			closeStint(me);
			me.status = Status.LEFT;
			reply = endedFor(call, who, PhonePayloads.END_HANGUP);
		} else if (me.status == Status.IN_CALL) {
			closeStint(me);
			me.status = Status.LEFT;
			reply = CallUpdate.NONE;
		} else if (me.status == Status.INVITED && !me.fake) {
			me.status = Status.DECLINED;
			reply = CallUpdate.NONE;
		} else {
			return;
		}
		byPlayer.remove(who);
		// O retrato de voz antes de qualquer aviso: quem saiu deixa de ser repassado na hora.
		rebuildSnapshot();
		deliver(who, reply);
		afterChange(call);
	}

	/** Liga ou desliga o mudo de quem pede: o servidor só para de repassar a voz dele. */
	void mute(UUID who, long id, boolean on) {
		Call call = byPlayer.get(who);
		if (call == null || call.id != id) {
			return;
		}
		Participant me = call.parts.get(who);
		if (me == null || me.status != Status.IN_CALL || me.muted == on) {
			return;
		}
		me.muted = on;
		changed(call);
	}

	// ------------------------------------------------------------------ o que acontece com a ligação (sem pedido do jogador)

	/** O jogador saiu do jogo. Quem ligava ou falava sai da ligação; quem estava tocando vira "toque falso" (quem liga não descobre). */
	void onDisconnect(UUID who) {
		// As tentativas de ligar NÃO são esquecidas aqui: sair e voltar não pode zerar o limite (o prune() limpa quem já passou da janela).
		leaveSilently(who);
		fakePeers.remove(who);
	}

	/** Tira a conta da ligação sem avisá-la (ela saiu do jogo ou foi apagada). */
	private void leaveSilently(UUID who) {
		Call call = byPlayer.remove(who);
		if (call == null) {
			return;
		}
		Participant me = call.parts.get(who);
		if (me == null) {
			return;
		}
		if (me.status == Status.INVITED) {
			// Quem liga não pode perceber (nem por um pacote a mais) que o destino saiu: só o retrato de voz é refeito.
			me.fake = true;
			afterChange(call, false);
		} else if (me.status == Status.IN_CALL) {
			closeStint(me);
			me.status = Status.LEFT;
			afterChange(call, true);
		}
	}

	/** Soma ao total da pessoa o tempo da entrada que está acabando. */
	private void closeStint(Participant p) {
		p.accumulatedMs += Math.max(0, now() - p.joinedAt);
	}

	/** Um bloqueio mudou entre dois jogadores: ligações diretas entre eles não podem continuar (nenhum sentido). */
	void onBlockChanged(UUID a, UUID b) {
		Call call = byPlayer.get(a);
		if (call == null || !call.parts.containsKey(b)) {
			// Quem tem o "toque falso" não está no índice: acha a ligação pelo outro lado.
			call = byPlayer.get(b);
		}
		if (call == null || !call.parts.containsKey(a) || !call.parts.containsKey(b)) {
			return;
		}
		if (call.conversation != 0) {
			// Em grupo, o bloqueio só muda quem ouve quem (o retrato é refeito). Quem ainda tocava e bloqueou quem chamou para de tocar.
			boolean silenced = false;
			for (UUID x : List.of(a, b)) {
				Participant p = call.parts.get(x);
				if (p.status == Status.INVITED && !p.fake && (owner.isBlocked(x, call.initiator) || owner.isBlocked(call.initiator, x))) {
					p.fake = true;
					byPlayer.remove(x);
					deliver(x, CallUpdate.NONE);
					silenced = true;
				}
			}
			if (silenced) {
				rebuildSnapshot();
			} else {
				changed(call);
			}
			return;
		}
		boolean aBlocksB = owner.isBlocked(a, b), bBlocksA = owner.isBlocked(b, a);
		if (!aBlocksB && !bBlocksA) {
			return;
		}
		Participant pa = call.parts.get(a), pb = call.parts.get(b);
		if (call.active) {
			// Atendida: acaba, com o mesmo motivo de qualquer fim ("encerrada"); nunca "bloqueado".
			endCall(call, PhonePayloads.END_HANGUP);
			return;
		}
		// Ainda tocando. Quem foi convidado e bloqueou quem liga deixa de tocar, sem quem liga perceber ("chamando" até o prazo).
		boolean notifyOthers = false;
		for (UUID invitee : List.of(a, b)) {
			Participant p = call.parts.get(invitee);
			UUID other = invitee.equals(a) ? b : a;
			if (p.status == Status.INVITED && !p.fake && owner.isBlocked(invitee, other)) {
				p.fake = true;
				byPlayer.remove(invitee);
				deliver(invitee, CallUpdate.NONE);
			}
		}
		// Quem liga bloqueou o convidado: a chamada é cancelada.
		UUID initiator = call.initiator;
		UUID other = initiator.equals(a) ? b : a;
		if (owner.isBlocked(initiator, other) && call.parts.get(initiator).status == Status.IN_CALL) {
			closeStint(call.parts.get(initiator));
			call.parts.get(initiator).status = Status.LEFT;
			byPlayer.remove(initiator);
			deliver(initiator, CallUpdate.NONE);
			notifyOthers = true;
		}
		afterChange(call, notifyOthers);
	}

	/** A conta saiu do grupo de mensagens: sai da chamada dele também (e para de tocar, se estava tocando). */
	void onLeftGroup(UUID who, long conversation) {
		Call call = byConversation.get(conversation);
		if (call == null || !call.parts.containsKey(who)) {
			return;
		}
		boolean live = byPlayer.remove(who, call);
		Participant p = call.parts.get(who);
		if (p.status == Status.IN_CALL) {
			closeStint(p);
		}
		// Primeiro o status, depois a reavaliação: se era o último convidado, a chamada precisa acabar (e não ficar "chamando" para sempre).
		p.status = Status.LEFT;
		p.fake = true;
		if (live) {
			deliver(who, CallUpdate.NONE);
		}
		afterChange(call, true);
	}

	/** O grupo foi apagado: a chamada acaba. */
	void onConversationGone(long conversation) {
		Call call = byConversation.get(conversation);
		if (call != null) {
			endCall(call, PhonePayloads.END_HANGUP);
		}
	}

	/** O silenciamento de uma conta mudou: o retrato de voz precisa refletir isso na hora. */
	void onMuteChanged(UUID who) {
		Call call = byPlayer.get(who);
		if (call != null) {
			changed(call);
		}
	}

	/** A conta foi apagada pelo administrador: a ligação dela acaba antes de o resto sumir. */
	void onWipe(UUID who) {
		Call call = byPlayer.get(who);
		if (call == null) {
			return;
		}
		leaveSilently(who);
		deliver(who, CallUpdate.NONE);
	}

	/** O servidor está parando: tudo termina, sem mensagens (os jogadores estão saindo). */
	void shutdown() {
		calls.clear();
		byPlayer.clear();
		byConversation.clear();
		VoiceStatus.publish(VoiceRouting.Snapshot.EMPTY);
	}

	// ------------------------------------------------------------------ cálculo do estado

	/** Algo mudou em uma ligação: reavalia, avisa todos e refaz o retrato de voz (antes de avisar, para nunca haver voz sem permissão). */
	private void changed(Call call) {
		rebuildSnapshot();
		push(call);
	}

	/** Depois que alguém saiu, recusou ou não atendeu: a ligação continua ou acaba? */
	private void afterChange(Call call) {
		afterChange(call, true);
	}

	/** {@code notify} falso: só o retrato de voz é refeito (ninguém recebe pacote), para não dar a quem liga um sinal de que algo mudou do outro lado. */
	private void afterChange(Call call, boolean notify) {
		if (!calls.containsKey(call.id)) {
			return;
		}
		int inCall = 0;
		boolean ringingLive = false, anyDeclined = false;
		long now = now();
		for (Participant p : call.parts.values()) {
			switch (p.status) {
				case IN_CALL -> inCall++;
				case INVITED -> ringingLive |= now < p.ringDeadline;
				case DECLINED -> anyDeclined = true;
				default -> { }
			}
		}
		if (call.active && inCall < 2) {
			endCall(call, PhonePayloads.END_HANGUP);
		} else if (!call.active && inCall == 0) {
			// Quem ligou cancelou: os convidados que ainda tocavam perdem a chamada.
			endCall(call, PhonePayloads.END_HANGUP);
		} else if (!call.active && !ringingLive) {
			// Ninguém mais vai atender: recusaram ou o prazo acabou.
			endCall(call, anyDeclined ? PhonePayloads.END_DECLINED : PhonePayloads.END_NO_ANSWER);
		} else if (notify) {
			changed(call);
		} else {
			rebuildSnapshot();
		}
	}

	/** Encerra a ligação e avisa quem ainda estava nela; {@code initiatorReason} vale para quem ligou se ela ainda não tinha sido atendida. */
	private void endCall(Call call, int initiatorReason) {
		calls.remove(call.id);
		if (call.conversation != 0) {
			byConversation.remove(call.conversation, call);
		}
		// O retrato de voz primeiro: ninguém é repassado depois que a ligação deixou de existir, nem por microssegundos.
		rebuildSnapshot();
		for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
			UUID who = entry.getKey();
			Participant p = entry.getValue();
			if (byPlayer.get(who) != call) {
				continue;
			}
			byPlayer.remove(who);
			int reason;
			if (p.status == Status.IN_CALL) {
				reason = call.active && initiatorReason != PhonePayloads.END_FAILED ? PhonePayloads.END_HANGUP : initiatorReason;
			} else if (p.status == Status.INVITED) {
				reason = initiatorReason == PhonePayloads.END_FAILED ? initiatorReason : PhonePayloads.END_MISSED;
			} else {
				continue;
			}
			deliver(who, endedFor(call, who, reason));
		}
		logCall(call, initiatorReason);
	}

	/**
	 * Registra o fim da chamada: a mensagem de sistema na conversa ("Chamada de voz · duração", ou "Chamada perdida") e uma linha
	 * no histórico de cada participante. Quem bloqueou quem chamou não recebe nada (nem mensagem nem histórico), e quem chamou
	 * vê o mesmo de qualquer chamada não atendida: o bloqueio não aparece em lugar nenhum. Quem estava offline recebe a chamada
	 * perdida (assim "tem histórico" não diz nada sobre quem estava ligado).
	 */
	private void logCall(Call call, int reason) {
		if (reason == PhonePayloads.END_FAILED && !call.active) {
			return;
		}
		long when = now();
		int seconds = call.active ? (int) Math.max(0, (when - call.answeredAt) / 1000) : 0;
		List<UUID> hidden = hiddenFromBlockers(call);
		long conversation = call.conversation;
		if (conversation == 0) {
			for (UUID other : call.parts.keySet()) {
				if (!other.equals(call.initiator)) {
					conversation = owner.directConversationId(call.initiator, other);
				}
			}
		}
		String text = call.active ? PhonePayloads.SYSTEM_CALL + "fim:" + seconds : PhonePayloads.SYSTEM_CALL + "perdida:" + owner.numberOf(call.initiator);
		owner.postCallMessage(conversation, text, hidden);
		for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
			UUID who = entry.getKey();
			Participant p = entry.getValue();
			if (hidden.contains(who)) {
				continue;
			}
			boolean outgoing = who.equals(call.initiator);
			boolean answered = call.active && p.everJoined;
			if (!outgoing && !answered && (p.status == Status.DECLINED || p.status == Status.LEFT)) {
				continue;
			}
			PhoneService.CallRecord record = new PhoneService.CallRecord();
			record.time = when;
			record.conversation = conversation;
			record.group = call.conversation != 0;
			record.outgoing = outgoing;
			record.answered = answered;
			long mine = p.accumulatedMs + (p.status == Status.IN_CALL ? Math.max(0, when - p.joinedAt) : 0);
			record.seconds = answered ? (int) (mine / 1000) : 0;
			record.number = call.conversation != 0 ? owner.numberOf(call.initiator)
				: owner.numberOf(outgoing ? otherOf(call, who) : call.initiator);
			owner.recordCall(who, record);
		}
	}

	private static UUID otherOf(Call call, UUID who) {
		for (UUID other : call.parts.keySet()) {
			if (!other.equals(who)) {
				return other;
			}
		}
		return who;
	}

	/** Termina uma ligação por causa de fora (o recurso foi desligado, o SVC parou): todos veem "falhou". */
	private void failAll() {
		for (Call call : List.copyOf(calls.values())) {
			endCall(call, PhonePayloads.END_FAILED);
		}
	}

	private CallUpdate endedFor(Call call, UUID viewer, int reason) {
		int seconds = call.active ? (int) Math.max(0, (now() - call.answeredAt) / 1000) : 0;
		return new CallUpdate(call.id, PhonePayloads.CALL_ENDED, reason, call.conversation, false, VoiceStatus.hasVoice(viewer), seconds, List.of());
	}

	/** Manda a cada participante vivo o retrato da ligação, do ponto de vista dele. */
	private void push(Call call) {
		for (UUID who : call.parts.keySet()) {
			if (byPlayer.get(who) == call) {
				deliver(who, viewFor(call, who));
			}
		}
	}

	private int remaining(Participant p) {
		return (int) Math.max(0, (p.ringDeadline - now() + 999) / 1000);
	}

	/** O retrato da ligação para um participante. Quem liga só vê "chamando" até atender; nada sobre o destino vaza antes disso. */
	CallUpdate viewFor(Call call, UUID viewer) {
		Participant me = call.parts.get(viewer);
		List<CallPart> others = new ArrayList<>();
		int state, seconds;
		if (me.status == Status.INVITED) {
			state = PhonePayloads.CALL_INCOMING;
			seconds = remaining(me);
			others.add(new CallPart(owner.numberOf(call.initiator), call.parts.get(call.initiator).status == Status.IN_CALL, true, false));
			for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
				if (!entry.getKey().equals(call.initiator) && !entry.getKey().equals(viewer) && entry.getValue().status == Status.IN_CALL) {
					others.add(new CallPart(owner.numberOf(entry.getKey()), true, true, false));
				}
			}
		} else if (!call.active) {
			state = PhonePayloads.CALL_OUTGOING;
			int longest = 0;
			for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
				if (entry.getValue().status == Status.INVITED) {
					longest = Math.max(longest, remaining(entry.getValue()));
					others.add(new CallPart(owner.numberOf(entry.getKey()), false, true, false));
				}
			}
			seconds = longest;
		} else {
			state = PhonePayloads.CALL_ACTIVE;
			seconds = (int) Math.max(0, (now() - call.answeredAt) / 1000);
			for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
				if (entry.getKey().equals(viewer)) {
					continue;
				}
				Participant p = entry.getValue();
				if (p.status == Status.IN_CALL) {
					others.add(new CallPart(owner.numberOf(entry.getKey()), true, VoiceStatus.hasVoice(entry.getKey()), p.muted));
				} else if (p.status == Status.INVITED) {
					// Também os de toque falso: quem chama (ou está na chamada) não distingue quem toca de verdade de quem não.
					others.add(new CallPart(owner.numberOf(entry.getKey()), false, true, false));
				}
			}
		}
		return new CallUpdate(call.id, state, 0, call.conversation, me.muted, VoiceStatus.hasVoice(viewer), seconds, others);
	}

	// ------------------------------------------------------------------ o retrato de voz

	/**
	 * Refaz o retrato imutável que a thread do SVC lê: para cada falante autorizado (atendeu, sem mudo, sem silenciamento de
	 * administrador), quem pode ouvi-lo (atendeu, e não há bloqueio em NENHUM sentido entre os dois), mais a posição de todos.
	 * Sem ligação atendida, o retrato é vazio: ninguém é repassado enquanto toca.
	 */
	void rebuildSnapshot() {
		Map<UUID, List<UUID>> listeners = new HashMap<>();
		Map<UUID, VoiceRouting.Where> where = new HashMap<>();
		for (Call call : calls.values()) {
			if (!call.active) {
				continue;
			}
			List<UUID> inCall = new ArrayList<>();
			for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
				if (entry.getValue().status == Status.IN_CALL) {
					inCall.add(entry.getKey());
				}
			}
			for (UUID speaker : inCall) {
				Participant p = call.parts.get(speaker);
				if (p.muted || owner.isMuted(speaker)) {
					continue;
				}
				List<UUID> hears = new ArrayList<>();
				for (UUID listener : inCall) {
					if (!listener.equals(speaker) && !owner.isBlocked(speaker, listener) && !owner.isBlocked(listener, speaker)) {
						hears.add(listener);
					}
				}
				if (!hears.isEmpty()) {
					listeners.put(speaker, List.copyOf(hears));
				}
			}
			for (UUID who : inCall) {
				LocationService.Pos pos = owner.locationOf(who);
				if (pos != null) {
					where.put(who, new VoiceRouting.Where(pos.dimension(), pos.x(), pos.y(), pos.z()));
				}
			}
		}
		VoiceStatus.publish(listeners.isEmpty() ? VoiceRouting.Snapshot.EMPTY
			: new VoiceRouting.Snapshot(++snapshotVersion, Map.copyOf(listeners), Map.copyOf(where), PhoneService.config().crossDimensionCalls));
	}

	/** O retrato de voz publicado agora (testes). */
	public static VoiceRouting.Snapshot voiceSnapshot() {
		return VoiceStatus.snapshot();
	}

	// ------------------------------------------------------------------ limites

	private boolean allowAttempt(UUID who) {
		long now = now();
		ArrayDeque<Long> times = attempts.computeIfAbsent(who, k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() > HOUR_MS) {
			times.pollFirst();
		}
		int lastMinute = 0;
		for (long time : times) {
			if (now - time <= MINUTE_MS) {
				lastMinute++;
			}
		}
		if (lastMinute >= ATTEMPTS_PER_MINUTE || times.size() >= ATTEMPTS_PER_HOUR) {
			return false;
		}
		times.addLast(now);
		return true;
	}

	/**
	 * Devolve o toque mais recente de {@code from} para {@code to} (a ligação foi atendida). Só existe atendimento de toque
	 * verdadeiro, então quem foi bloqueado (toque falso) nunca recupera nada por aqui.
	 */
	private void forgiveRing(UUID from, UUID to) {
		ArrayDeque<Long> times = pairRings.get(new Pair(from, to));
		if (times != null) {
			times.pollLast();
		}
	}

	/** No máximo {@link #PAIR_RINGS} toques não atendidos de uma pessoa para a mesma outra por janela (conta mesmo se o destino bloqueou). */
	private boolean allowPair(UUID from, UUID to) {
		long now = now();
		ArrayDeque<Long> times = pairRings.computeIfAbsent(new Pair(from, to), k -> new ArrayDeque<>());
		while (!times.isEmpty() && now - times.peekFirst() > PAIR_WINDOW_MS) {
			times.pollFirst();
		}
		if (times.size() >= PAIR_RINGS) {
			return false;
		}
		times.addLast(now);
		return true;
	}

	private long newId() {
		long id;
		do {
			id = random.nextLong() & Long.MAX_VALUE;
		} while (id == 0 || calls.containsKey(id));
		return id;
	}

	// ------------------------------------------------------------------ ciclo de vida

	/** A cada tick do servidor: prazos de toque, mudanças de voz e disponibilidade, posições. */
	void tick() {
		tickCounter++;
		boolean available = available();
		if (available != lastAvailable) {
			lastAvailable = available;
			if (!available) {
				failAll();
			}
			for (PhonePlayer player : owner.server().players()) {
				sendInfo(player);
			}
		}
		if (calls.isEmpty()) {
			seenVoiceVersion = VoiceStatus.version();
			if (tickCounter % 1200 == 0) {
				prune();
			}
			return;
		}
		long now = now();
		for (Call call : List.copyOf(calls.values())) {
			if (!calls.containsKey(call.id)) {
				continue;
			}
			boolean expired = false;
			for (Map.Entry<UUID, Participant> entry : call.parts.entrySet()) {
				Participant p = entry.getValue();
				if (p.status == Status.INVITED && now >= p.ringDeadline) {
					p.status = Status.MISSED;
					if (!p.fake && byPlayer.get(entry.getKey()) == call) {
						byPlayer.remove(entry.getKey());
						deliver(entry.getKey(), endedFor(call, entry.getKey(), PhonePayloads.END_MISSED));
					}
					expired = true;
				}
			}
			if (expired) {
				afterChange(call);
			}
		}
		int voice = VoiceStatus.version();
		if (voice != seenVoiceVersion) {
			// Alguém ganhou ou perdeu a voz: o aviso "sem voz" muda. Não encerra a ligação (só a parada do SVC encerra).
			seenVoiceVersion = voice;
			for (Call call : List.copyOf(calls.values())) {
				int signature = voiceSignature(call);
				if (signature != call.voiceSignature) {
					call.voiceSignature = signature;
					push(call);
				}
			}
		}
		if (tickCounter % SNAPSHOT_EVERY_TICKS == 0) {
			rebuildSnapshot();
		}
	}

	private int voiceSignature(Call call) {
		int bits = 0, i = 0;
		for (UUID who : call.parts.keySet()) {
			if (byPlayer.get(who) == call && VoiceStatus.hasVoice(who)) {
				bits |= 1 << (i % 31);
			}
			i++;
		}
		return bits;
	}

	/** Entrada de um jogador: diz se as ligações existem aqui. */
	void onJoin(PhonePlayer player) {
		sendInfo(player);
	}

	private void sendInfo(PhonePlayer player) {
		PhoneService.send(player, new PhonePayloads.CallInfo(available(), PhoneService.config().crossDimensionCalls));
	}

	/** A dimensão de alguém mudou: com a voz presa à dimensão, o retrato não pode esperar o próximo ciclo. */
	void onWorldChange(UUID who) {
		if (byPlayer.containsKey(who)) {
			rebuildSnapshot();
		}
	}

	/** Esquece o que já expirou (jogadores que saíram e nunca mais voltaram não podem fazer os mapas crescerem). */
	void prune() {
		long now = now();
		attempts.entrySet().removeIf(e -> (e.getValue().isEmpty() || now - e.getValue().peekLast() > HOUR_MS) && owner.online(e.getKey()) == null);
		pairRings.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > PAIR_WINDOW_MS);
	}

	// ------------------------------------------------------------------ administração e testes

	/** Resumo da ligação de uma conta para o administrador: o estado e o id, nunca com quem ela fala. */
	Msg describe(UUID who) {
		Call call = byPlayer.get(who);
		if (call == null) {
			return new Msg("handyphone.command.info.call.none");
		}
		Participant me = call.parts.get(who);
		String state = me.status == Status.INVITED ? "ringing" : call.active ? "answered" : "calling";
		return new Msg("handyphone.command.info.call.some", new Msg("handyphone.command.info.call." + state), call.id);
	}

	/** Quantas ligações vivas existem (testes e diagnóstico). */
	public int liveCalls() {
		return calls.size();
	}

	/** O id da ligação de uma conta, ou 0 (testes). */
	public long callOf(UUID who) {
		Call call = byPlayer.get(who);
		return call == null ? 0 : call.id;
	}

	/** A conta está em uma ligação viva agora? */
	public boolean inCall(UUID who) {
		return byPlayer.containsKey(who);
	}

	/** Como o jogo vê quem pode ouvir quem agora, direto do cálculo do servidor (testes). */
	public CallUpdate viewOf(UUID who) {
		Call call = byPlayer.get(who);
		return call == null ? CallUpdate.NONE : viewFor(call, who);
	}

	// ---- ganchos para os testes: contas simuladas pedem em nome de si mesmas, e recebem os pacotes em uma função

	/** Faz de conta que a conta está ligada e tem o mod: o que o servidor lhe mandaria vai para {@code receiver}. */
	public void useFakePeer(UUID who, Consumer<PhonePayloads.Payload> receiver) {
		if (receiver == null) {
			fakePeers.remove(who);
		} else {
			fakePeers.put(who, receiver);
		}
	}

	/** Como se a conta tivesse saído do jogo, sem tirar o participante simulado (o teste continua usando a conta). */
	public void disconnectForTests(UUID who) {
		leaveSilently(who);
	}

	public void startAs(UUID caller, String number) {
		start(caller, number);
	}

	public void startGroupAs(UUID caller, long conversation) {
		startGroup(caller, conversation);
	}

	/** Mede o custo de refazer o retrato de voz (nanossegundos por vez), com as ligações vivas agora. */
	public long benchmarkSnapshot(int times) {
		long start = System.nanoTime();
		for (int i = 0; i < times; i++) {
			rebuildSnapshot();
		}
		return (System.nanoTime() - start) / Math.max(1, times);
	}

	public void answerAs(UUID who, long id) {
		answer(who, id);
	}

	public void declineAs(UUID who, long id) {
		decline(who, id);
	}

	public void muteAs(UUID who, long id, boolean on) {
		mute(who, id, on);
	}

	/** Esquece as tentativas de ligar (testes que repetem pedidos sem esperar o relógio). */
	public void forgetLimits() {
		attempts.clear();
		pairRings.clear();
	}
}
