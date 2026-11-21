package dev.migzu.handyphone.server;

import java.nio.file.Path;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;

/**
 * A ÚNICA classe que toca a API do squaremap. Ela só pode ser carregada depois de
 * conferir {@code FabricLoader.isModLoaded("squaremap")} (ver {@link MapTiles}): sem o
 * mod, a JVM nunca resolve as classes {@code xyz.jpenilla...} e o HandyPhone carrega normal.
 * Qualquer falha da API (mod ainda subindo, versão diferente) vira "sem mapa", nunca um erro.
 */
final class SquaremapLink {
	private SquaremapLink() {
	}

	/** Pasta {@code <web>/tiles} do squaremap, ou null se ele ainda não está pronto. */
	static Path tilesRoot() {
		try {
			return SquaremapProvider.get().webDir().resolve("tiles");
		} catch (Throwable t) {
			return null;
		}
	}

	/** O squaremap renderiza essa dimensão (mundo habilitado na configuração dele)? */
	static boolean worldEnabled(String namespace, String path) {
		try {
			return SquaremapProvider.get().getWorldIfEnabled(WorldIdentifier.create(namespace, path)).isPresent();
		} catch (Throwable t) {
			return false;
		}
	}
}
