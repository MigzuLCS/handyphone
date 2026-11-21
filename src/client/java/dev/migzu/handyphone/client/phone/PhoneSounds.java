package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneSound;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Catálogo e reprodução dos efeitos sonoros do celular no lado do cliente.
 *
 * <p>Resolve o tema em um lugar só ({@code handyphone:<tema>.<som>}, com queda para
 * {@code modern} se um tema futuro não tiver aquele som) e garante que a chamada ao
 * {@link net.minecraft.client.sounds.SoundManager} ocorra sempre na thread do jogo.
 *
 * <p>Todos os sons usam a categoria {@code SoundSource.UI} (via {@code SimpleSoundInstance.forUI}),
 * pois são sons locais da interface e do manuseio em primeira pessoa pelo próprio jogador.
 */
public final class PhoneSounds {
	public static final List<String> THEMES = List.of("retro", "aero", "flat", "modern");
	private static final String FALLBACK_THEME = "modern";
	/** Variação máxima de tom (±4%) nos sons repetitivos com variações. */
	private static final float PITCH_SPREAD = 0.04f;

	private static final Map<Identifier, SoundEvent> EVENTS = new HashMap<>();

	static {
		for (PhoneSound sound : PhoneSound.values()) {
			if (sound.isThemed()) {
				for (String theme : THEMES) {
					registerEvent(HandyPhone.id(sound.eventPath(theme)));
				}
			} else {
				registerEvent(HandyPhone.id(sound.eventPath("device")));
			}
		}
	}

	private PhoneSounds() {
	}

	private static void registerEvent(Identifier id) {
		// De propósito fora do registro do jogo: são sons só do cliente, e registrá-los faria o
		// cliente ter entradas que um servidor dedicado não tem. O som é achado pelo id no sounds.json.
		EVENTS.put(id, SoundEvent.createVariableRangeEvent(id));
	}

	/** Garante que a classe foi carregada e os eventos criados na inicialização do cliente. */
	public static void register() {
		// O bloco estático cria os eventos.
	}

	/**
	 * Resolve o {@link SoundEvent} correspondente a um momento sonoro e ao tema atual,
	 * caindo para o conjunto {@code modern} caso o tema não possua versão própria.
	 */
	public static SoundEvent resolve(PhoneSound sound, String themeId) {
		if (!sound.isThemed()) {
			return EVENTS.get(HandyPhone.id(sound.eventPath("device")));
		}
		SoundEvent themed = EVENTS.get(HandyPhone.id(sound.eventPath(themeId)));
		if (themed != null) {
			return themed;
		}
		return EVENTS.get(HandyPhone.id(sound.eventPath(FALLBACK_THEME)));
	}

	/** Toca um som do aparelho ou da interface na thread do jogo. */
	public static void play(PhoneSound sound, String themeId) {
		if (sound == null) {
			return;
		}
		SoundEvent event = resolve(sound, themeId);
		if (event == null) {
			return;
		}
		float pitch = sound.varyPitch()
			? (1f - PITCH_SPREAD) + ThreadLocalRandom.current().nextFloat() * (2f * PITCH_SPREAD)
			: 1f;
		Minecraft mc = Minecraft.getInstance();
		if (mc != null) {
			mc.execute(() -> {
				// Som sem entrada no sounds.json (os call_* chegam em separado): só não toca, sem encher o log.
				if (mc.getSoundManager().getSoundEvent(event.location()) != null) {
					mc.getSoundManager().play(SimpleSoundInstance.forUI(event, pitch, 1.0f));
				}
			});
		}
	}

	/**
	 * Começa um som que o chamador acompanha (toque em laço, tom de chamada) e devolve a instância, ou null se não
	 * há como tocá-lo: o arquivo de áudio ainda não existe no {@code sounds.json}, o som está desligado, etc. Deve ser
	 * chamado na thread do jogo.
	 */
	public static SimpleSoundInstance playTracked(PhoneSound sound, String themeId) {
		SoundEvent event = sound == null ? null : resolve(sound, themeId);
		Minecraft mc = Minecraft.getInstance();
		// Evento sem entrada no sounds.json: o jogo reclamaria no log a cada tentativa, e um laço tentaria para sempre.
		if (event == null || mc == null || mc.getSoundManager().getSoundEvent(event.location()) == null) {
			return null;
		}
		SimpleSoundInstance instance = SimpleSoundInstance.forUI(event, 1.0f, 1.0f);
		mc.getSoundManager().play(instance);
		return instance;
	}

	/** A instância ainda está tocando? */
	public static boolean isActive(SimpleSoundInstance instance) {
		Minecraft mc = Minecraft.getInstance();
		return instance != null && mc != null && mc.getSoundManager().isActive(instance);
	}

	/** Para um som iniciado por {@link #playTracked}. */
	public static void stop(SimpleSoundInstance instance) {
		Minecraft mc = Minecraft.getInstance();
		if (instance != null && mc != null) {
			mc.getSoundManager().stop(instance);
		}
	}
}
