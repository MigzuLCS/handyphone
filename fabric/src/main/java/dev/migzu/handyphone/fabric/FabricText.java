package dev.migzu.handyphone.fabric;

import dev.migzu.handyphone.i18n.LangFile;
import dev.migzu.handyphone.server.Msg;
import net.minecraft.network.chat.Component;

/** Converte um {@link Msg} do {@code common} em texto traduzível do jogo. */
public final class FabricText {
	private FabricText() {
	}

	/** Texto traduzível: cada jogador operador vê no idioma dele; o console do servidor dedicado, em inglês. */
	public static Component component(Msg msg) {
		Object[] args = msg.args();
		Object[] resolved = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			resolved[i] = args[i] instanceof Msg m ? component(m) : String.valueOf(args[i]);
		}
		return Component.translatableWithFallback(msg.key(), LangFile.english(msg.key()), resolved);
	}
}
