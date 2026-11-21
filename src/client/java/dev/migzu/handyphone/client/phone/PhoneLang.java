package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.client.HandyPhoneClient;
import dev.migzu.handyphone.client.os.Lang;
import dev.migzu.handyphone.client.os.PhoneOS;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

/**
 * Liga os textos do celular ao idioma do jogo. O PhoneOS não usa classes do Minecraft, então ele traduz por
 * {@link Lang}; aqui se instala a função que consulta o {@link Language} do jogo (o idioma escolhido, o inglês de
 * reserva e os pacotes de recursos, de graça). Ao trocar o idioma (ou recarregar pacotes) o jogo cria outro
 * {@code Language}: o tick percebe, reinstala e manda o celular se redesenhar.
 */
public final class PhoneLang {
	private static Language last;
	private static String lastCode = "";
	private static int ticks;

	private PhoneLang() {
	}

	/** Faz o próximo tick reinstalar a função do jogo (os testes que trocam o idioma na mão a devolvem assim). */
	public static void invalidate() {
		last = null;
	}

	/** Chamado a cada tick do cliente. */
	public static void tick(Minecraft mc) {
		Language current = Language.getInstance();
		String code = mc.getLanguageManager().getSelected();
		PhoneOS os = PhoneController.INSTANCE.os;
		if (current != last || !code.equals(lastCode)) {
			last = current;
			lastCode = code;
			Lang.install(PhoneLang::lookup, code);
			synchronized (os) {
				os.languageChanged();
			}
		}
		// O nome da tecla (o jogador pode remapeá-la) entra no aviso da tela de bloqueio; sem a trava do PhoneOS.
		if (ticks++ % 20 == 0) {
			os.setUseKeyName(HandyPhoneClient.EXPAND_KEY.getTranslatedKeyMessage().getString());
		}
	}

	private static String lookup(String key) {
		Language language = Language.getInstance();
		return language.has(key) ? language.getOrDefault(key) : null;
	}
}
