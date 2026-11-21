package dev.migzu.handyphone.server;

import dev.migzu.handyphone.i18n.LangFile;
import net.minecraft.network.chat.Component;

/**
 * Uma mensagem do servidor ao administrador (comandos): chave de texto e argumentos, que podem ser texto, número ou
 * outra {@code Msg}. {@link #component()} vira texto traduzível (cada jogador operador vê no idioma dele; o console do
 * servidor dedicado, em inglês); {@link #english()} é o texto em inglês para testes e logs.
 */
public record Msg(String key, Object... args) {
	public Component component() {
		Object[] resolved = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			resolved[i] = args[i] instanceof Msg m ? m.component() : String.valueOf(args[i]);
		}
		return Component.translatableWithFallback(key, LangFile.english(key), resolved);
	}

	public String english() {
		Object[] resolved = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			resolved[i] = args[i] instanceof Msg m ? m.english() : String.valueOf(args[i]);
		}
		return LangFile.format(LangFile.english(key), resolved);
	}
}
