package dev.migzu.handyphone.paper;

import dev.migzu.handyphone.i18n.LangFile;
import dev.migzu.handyphone.server.Msg;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;

/**
 * Converte um {@link Msg} do {@code common} em texto traduzível do Adventure: o jogador com o mod Fabric vê no idioma dele (a chave
 * está nos {@code lang/} do mod); o console e quem não tem o mod veem o inglês de reserva. Não registramos nada no
 * {@code GlobalTranslator}: senão o Paper traduziria no servidor e o idioma de cada jogador deixaria de valer.
 */
final class PaperText {
	private PaperText() {
	}

	static Component component(Msg msg) {
		List<ComponentLike> args = new ArrayList<>(msg.args().length);
		for (Object arg : msg.args()) {
			args.add(arg instanceof Msg m ? component(m) : Component.text(String.valueOf(arg)));
		}
		return Component.translatable(msg.key(), LangFile.english(msg.key()), args);
	}
}
