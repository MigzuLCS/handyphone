package dev.migzu.handyphone.server;

import dev.migzu.handyphone.i18n.LangFile;

/**
 * Uma mensagem do servidor ao administrador (comandos): chave de texto e argumentos, que podem ser texto, número ou
 * outra {@code Msg}. A plataforma a converte em texto traduzível do jogo (no Fabric, {@code FabricText.component}: cada
 * jogador operador vê no idioma dele; o console do servidor dedicado, em inglês); {@link #english()} é o texto em
 * inglês para testes e logs.
 */
public record Msg(String key, Object... args) {
	public String english() {
		Object[] resolved = new Object[args.length];
		for (int i = 0; i < args.length; i++) {
			resolved[i] = args[i] instanceof Msg m ? m.english() : String.valueOf(args[i]);
		}
		return LangFile.format(LangFile.english(key), resolved);
	}
}
