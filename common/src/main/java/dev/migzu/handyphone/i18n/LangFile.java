package dev.migzu.handyphone.i18n;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IllegalFormatException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lê os textos de {@code assets/handyphone/lang/<idioma>.json} direto do jar do mod, sem passar pelo jogo.
 * Serve para o que roda fora do jogo (testes de captura do PhoneOS, que não usa classes do Minecraft) e como
 * texto de reserva em inglês onde o jogo não tem o idioma carregado (console do servidor, chave ausente).
 * Os textos de quem joga vêm do próprio jogo ({@code Language}), que já junta o idioma escolhido, o inglês e
 * os pacotes de recursos.
 */
public final class LangFile {
	/** O idioma que sempre existe e serve de reserva para qualquer chave que falte em outro. */
	public static final String DEFAULT = "en_us";

	private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

	private LangFile() {
	}

	/** Os textos de um idioma do jar (vazio se o arquivo não existe); a ordem é a do arquivo. O resultado não muda. */
	public static Map<String, String> load(String code) {
		return CACHE.computeIfAbsent(code, LangFile::read);
	}

	/**
	 * Quem lista os idiomas que vêm dentro do mod: a plataforma instala o dela (o Fabric pergunta ao carregador de mods,
	 * que conhece a pasta {@code assets/handyphone/lang} do jar). Sem plataforma (testes sem jogo), lê do classpath.
	 */
	private static volatile java.util.function.Supplier<java.util.List<String>> bundledLister = LangFile::bundledFromClasspath;

	/** Troca quem lista os idiomas do jar (ver {@link #bundled}); chamado uma vez pela plataforma. */
	public static void setBundledLister(java.util.function.Supplier<java.util.List<String>> lister) {
		bundledLister = lister;
	}

	/**
	 * Os idiomas que vêm dentro do mod (códigos como "pt_br"), lidos da pasta {@code assets/handyphone/lang} do jar; os pacotes de
	 * recursos de terceiros não aparecem aqui (o servidor não os conhece).
	 */
	public static java.util.List<String> bundled() {
		try {
			return bundledLister.get();
		} catch (RuntimeException e) {
			dev.migzu.handyphone.HandyPhoneCore.LOGGER.warn("HandyPhone: couldn't list the bundled languages; using the built-in list", e);
			return builtInList();
		}
	}

	/** A lista de reserva, quando a pasta não pode ser lida. */
	public static java.util.List<String> builtInList() {
		return java.util.List.of(DEFAULT, "pt_br", "es_es", "fr_fr", "de_de", "ru_ru");
	}

	/** Os idiomas da pasta {@code assets/handyphone/lang} ao lado desta classe no classpath (pasta ou jar). */
	private static java.util.List<String> bundledFromClasspath() {
		try {
			java.net.URL url = LangFile.class.getResource("/assets/handyphone/lang/" + DEFAULT + ".json");
			if (url == null) {
				throw new IllegalStateException("no bundled languages on the classpath");
			}
			java.net.URI uri = url.toURI();
			if ("jar".equals(uri.getScheme())) {
				try (java.nio.file.FileSystem fs = java.nio.file.FileSystems.newFileSystem(uri, Map.of())) {
					return list(fs.provider().getPath(uri).getParent());
				}
			}
			return list(java.nio.file.Path.of(uri).getParent());
		} catch (java.io.IOException | java.net.URISyntaxException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Os códigos dos {@code .json} de uma pasta de idiomas, em ordem. */
	public static java.util.List<String> list(java.nio.file.Path dir) throws java.io.IOException {
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json")).map(n -> n.substring(0, n.length() - 5)).sorted().toList();
		}
	}

	/** O texto em inglês de uma chave, ou a própria chave se ela não existe. */
	public static String english(String key) {
		return load(DEFAULT).getOrDefault(key, key);
	}

	private static Map<String, String> read(String code) {
		String path = "/assets/handyphone/lang/" + code + ".json";
		try (InputStream in = LangFile.class.getResourceAsStream(path)) {
			if (in == null) {
				return Map.of();
			}
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					map.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
			return Collections.unmodifiableMap(map);
		} catch (IOException | RuntimeException e) {
			return Map.of();
		}
	}

	/**
	 * Preenche um texto com os argumentos ({@code %s}, {@code %1$s}). Um texto com formato quebrado (de um pacote de
	 * recursos da comunidade, por exemplo) não pode derrubar quem desenha: devolve o texto como está.
	 */
	public static String format(String pattern, Object... args) {
		if (args == null || args.length == 0) {
			return pattern.indexOf('%') < 0 ? pattern : safe(pattern, args);
		}
		return safe(pattern, args);
	}

	private static String safe(String pattern, Object[] args) {
		try {
			return String.format(Locale.ROOT, pattern, args == null ? new Object[0] : args);
		} catch (IllegalFormatException e) {
			return pattern;
		}
	}
}
