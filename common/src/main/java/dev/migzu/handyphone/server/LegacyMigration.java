package dev.migzu.handyphone.server;

import dev.migzu.handyphone.HandyPhoneCore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Migração do nome provisório "BlockPhone" para "Migzu's HandyPhone": a pasta de dados do mundo ({@code <mundo>/blockphone/}) e o arquivo de
 * opções ({@code config/blockphone-server.json}) ganham o nome novo. Só renomeia quando o destino ainda não existe, e <b>nunca apaga</b>
 * o que é antigo: se a troca falha, o serviço segue com o destino novo vazio (e um {@code warn}). Logs em inglês (o dono do servidor pode
 * ser de qualquer país).
 */
public final class LegacyMigration {
	private LegacyMigration() {
	}

	/** Renomeia {@code <mundo>/blockphone} para {@code <mundo>/handyphone}. Retorna {@code true} se renomeou. */
	public static boolean migrateWorldData(Path worldRoot) {
		return migrate(worldRoot.resolve("blockphone"), worldRoot.resolve("handyphone"), "data folder");
	}

	/** Renomeia {@code config/blockphone-server.json} para {@code config/handyphone-server.json}. Retorna {@code true} se renomeou. */
	public static boolean migrateConfig(Path configDir) {
		return migrate(configDir.resolve("blockphone-server.json"), configDir.resolve("handyphone-server.json"), "config file");
	}

	private static boolean migrate(Path legacy, Path target, String what) {
		try {
			if (!Files.exists(legacy)) {
				return false;
			}
			if (Files.exists(target)) {
				HandyPhoneCore.LOGGER.warn(
					"HandyPhone: found both the old {} {} and the new {}; using the new one and leaving the old one untouched.",
					what, legacy, target
				);
				return false;
			}
			Files.move(legacy, target);
			HandyPhoneCore.LOGGER.info("HandyPhone: renamed the old {} {} to {} (the mod was renamed from BlockPhone).", what, legacy, target);
			return true;
		} catch (IOException | RuntimeException e) {
			HandyPhoneCore.LOGGER.warn(
				"HandyPhone: couldn't rename the old {} {} to {}; continuing with a new, empty one. The old one was not deleted.",
				what, legacy, target, e
			);
			return false;
		}
	}
}
