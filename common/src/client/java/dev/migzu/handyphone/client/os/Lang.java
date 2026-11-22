package dev.migzu.handyphone.client.os;

import dev.migzu.handyphone.i18n.LangFile;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Os textos do celular. O PhoneOS não usa classes do Minecraft, então quem traduz é uma função instalada de fora:
 * no jogo, {@code PhoneLang} instala a do {@code Language} (o idioma escolhido, o inglês de reserva e os pacotes de
 * recursos); sem jogo (testes de captura) vale o JSON do jar, com {@link #useClasspath}.
 *
 * <p>Regras: nenhum texto fixo no código, tudo por chave ({@code handyphone.*} em {@code assets/handyphone/lang}).
 * Chave que não existe aparece como a própria chave (defeito visível; o {@code checkLang} do build confere). Todos os
 * argumentos são texto ({@code %s}, {@code %1$s}); número entra já formatado. Plural: {@code base.<categoria>} pela
 * regra do idioma ({@link #pluralCategory}), com {@code base.other} de reserva.
 */
public final class Lang {
	/**
	 * Tudo o que depende do idioma, num objeto só e imutável (menos o cache de formatos, que nasce junto): a thread de desenho
	 * lê um retrato coerente (função de tradução, código, formatos) mesmo no meio de uma troca de idioma.
	 */
	private record State(Function<String, String> source, String code, int generation, Map<String, DateTimeFormatter> formats) {
	}

	private static volatile State state = new State(classpathSource("en_us"), "en_us", 0, new ConcurrentHashMap<>());

	private Lang() {
	}

	/**
	 * Instala quem traduz: {@code lookup} devolve o texto de uma chave, ou null se não há; {@code languageCode} é o
	 * código do jogo ("pt_br"), de onde sai o {@link Locale} dos nomes de mês e dia.
	 */
	public static void install(Function<String, String> lookup, String languageCode) {
		state = new State(lookup, languageCode, state.generation() + 1, new ConcurrentHashMap<>());
	}

	/** Sem jogo: lê o idioma direto do jar ("en_us", "pt_br"), com o inglês cobrindo o que faltar. */
	public static void useClasspath(String languageCode) {
		install(classpathSource(languageCode), languageCode);
	}

	private static Function<String, String> classpathSource(String languageCode) {
		Map<String, String> chosen = LangFile.load(languageCode);
		return key -> chosen.get(key);
	}

	/** Muda a cada troca de idioma; quem guarda texto já montado compara com o valor de antes e refaz. */
	public static int generation() {
		return state.generation();
	}

	/** O código do idioma em uso ("pt_br"). */
	public static String code() {
		return state.code();
	}

	/** Existe texto para esta chave (no idioma em uso ou no inglês de reserva)? */
	public static boolean has(String key) {
		return raw(key) != null;
	}

	private static String raw(String key) {
		String text = null;
		try {
			text = state.source().apply(key);
		} catch (RuntimeException ignored) {
			// uma fonte quebrada não pode derrubar a thread de desenho: cai no inglês
		}
		return text != null ? text : LangFile.load(LangFile.DEFAULT).get(key);
	}

	/** O texto da chave com os argumentos; a própria chave se não existe. Um formato quebrado cai no inglês, depois no texto cru. */
	public static String t(String key, Object... args) {
		String text = raw(key);
		if (text == null) {
			return key;
		}
		try {
			if (args == null || args.length == 0) {
				return text.indexOf('%') < 0 ? text : String.format(Locale.ROOT, text);
			}
			return String.format(Locale.ROOT, text, args);
		} catch (java.util.IllegalFormatException e) {
			String english = LangFile.load(LangFile.DEFAULT).get(key);
			return english != null && !english.equals(text) ? LangFile.format(english, args) : text;
		}
	}

	/**
	 * Plural: a categoria vem da regra do idioma em uso ({@link #pluralCategory}), e o texto de {@code base.<categoria>}; se o idioma
	 * (ou o pacote de recursos) não tem essa forma, cai em {@code base.other}. O primeiro argumento do texto é o próprio n.
	 */
	public static String plural(String base, long n, Object... args) {
		Object[] all = new Object[args.length + 1];
		all[0] = Long.toString(n);
		System.arraycopy(args, 0, all, 1, args.length);
		String key = base + "." + pluralCategory(state.code(), n);
		return t(has(key) ? key : base + ".other", all);
	}

	/**
	 * A categoria de plural de {@code n} no idioma ({@code one}, {@code few}, {@code many} ou {@code other}), pelas regras do CLDR
	 * simplificadas para números inteiros: en/pt/es/de e os desconhecidos = one se n é 1; fr = one se n é 0 ou 1; ru/uk/be = one
	 * (termina em 1, menos 11), few (termina em 2 a 4, menos 12 a 14), many (o resto); pl = one se n é 1, few como no russo, many o resto.
	 * Para acrescentar um idioma com outra regra, some um caso aqui e as formas dele em {@code pluralForms} do {@code checkLang}.
	 */
	public static String pluralCategory(String languageCode, long n) {
		long abs = Math.abs(n);
		String language = languageCode.split("_", 2)[0];
		switch (language) {
			case "fr":
				return abs == 0 || abs == 1 ? "one" : "other";
			case "ru", "uk", "be": {
				long last = abs % 10, lastTwo = abs % 100;
				return last == 1 && lastTwo != 11 ? "one" : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "few" : "many";
			}
			case "pl": {
				long last = abs % 10, lastTwo = abs % 100;
				return abs == 1 ? "one" : last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14) ? "few" : "many";
			}
			default:
				return abs == 1 ? "one" : "other";
		}
	}

	/** O {@link Locale} do idioma em uso ("pt_br" vira pt-BR); um idioma sem região vira só o idioma. */
	public static Locale locale() {
		return localeOf(state.code());
	}

	private static Locale localeOf(String code) {
		String[] parts = code.split("_", 2);
		return parts.length == 2 ? Locale.of(parts[0], parts[1].toUpperCase(Locale.ROOT)) : Locale.of(parts[0]);
	}

	/**
	 * Um formato de data ou hora cujo padrão vem da chave (por exemplo {@code handyphone.format.time}); padrão
	 * inválido de um pacote de recursos cai no padrão do inglês.
	 */
	public static DateTimeFormatter format(String key) {
		State current = state;
		DateTimeFormatter cached = current.formats().get(key);
		if (cached != null) {
			return cached;
		}
		DateTimeFormatter made;
		try {
			made = DateTimeFormatter.ofPattern(t(key), localeOf(current.code()));
		} catch (IllegalArgumentException e) {
			made = DateTimeFormatter.ofPattern(LangFile.english(key), Locale.US);
		}
		// Guarda no cache do retrato que foi lido: se o idioma mudou no meio, o formato velho cai no cache velho, descartado.
		current.formats().put(key, made);
		return made;
	}

	private static final Pattern NOTICE_KEY = Pattern.compile("handyphone\\.notice\\.[a-z0-9_.]{1,64}");

	/**
	 * O texto de um aviso do servidor ({@code Notice}: chave + argumentos). O servidor só pode escolher entre os textos
	 * {@code handyphone.notice.*} do próprio mod (nada de chaves do jogo ou de outros mods); chave desconhecida (servidor
	 * de outra versão) vira um aviso genérico. Os argumentos perdem caracteres invisíveis e de controle.
	 */
	public static String notice(String key, List<String> args) {
		if (!NOTICE_KEY.matcher(key).matches() || !has(key)) {
			return t("handyphone.notice.unknown");
		}
		Object[] clean = args.stream().limit(4).map(Lang::clean).toArray();
		return t(key, clean);
	}

	private static String clean(String text) {
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> {
			int type = Character.getType(cp);
			if (cp >= 32 && cp != 127 && type != Character.FORMAT && type != Character.CONTROL) {
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	/** Número decimal com uma casa no jeito do idioma ("1,4" em português, "1.4" em inglês). */
	public static String decimal(double value) {
		return String.format(locale(), "%.1f", value);
	}
}
