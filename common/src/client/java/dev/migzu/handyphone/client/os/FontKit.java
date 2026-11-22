package dev.migzu.handyphone.client.os;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * As fontes do celular. Uma família é um trio (normal, negrito e fino para o relógio grande): ou uma das fontes <b>embutidas</b> no
 * jar ({@code assets/handyphone/fonts/<nome>/{Regular,Bold,Light}.ttf}, carregadas com {@link Font#createFont}; a licença de cada uma fica na
 * mesma pasta) ou a do sistema (Segoe UI quando existe, senão SansSerif), que é o padrão enquanto a escolha não foi feita.
 *
 * <p>O nome vem da propriedade de sistema {@value #PROPERTY} (só para comparar fontes; o jogador comum não escolhe) ou de {@link #use}. Fonte
 * embutida que não carrega (arquivo ausente ou corrompido) cai na do sistema, sem erro. As fontes físicas do Java2D não trocam sozinhas
 * de fonte por caractere: o que a família não tem (chinês, japonês, coreano, emoji) é tratado pelo {@link TextFallback}.
 *
 * <p>Os pesos vêm de arquivos próprios, então o tamanho se muda com {@code deriveFont(float)}: {@code deriveFont(int, float)} pediria negrito
 * ao Java, que o sintetizaria por cima do arquivo.
 */
final class FontKit {
	static final String PROPERTY = "handyphone.font";
	/** O padrão: a Noto Sans embutida (escolha do Migzu, 2026-11-19). {@code "system"} (Segoe UI/SansSerif) fica para comparar. */
	static final String DEFAULT = "notosans";
	private static final String SYSTEM = "system";

	/** Uma família: {@code embedded} diz se os pesos vêm de arquivos próprios (tamanho só por {@code deriveFont(float)}). */
	record Family(String name, boolean embedded, Font regular, Font bold, Font light) {
		Font font(int style, float size) {
			if (embedded) {
				return ((style & Font.BOLD) != 0 ? bold : regular).deriveFont(size);
			}
			return regular.deriveFont(style, size);
		}

		Font light(float size) {
			return embedded ? light.deriveFont(size) : light.deriveFont(Font.PLAIN, size);
		}
	}

	private static volatile Family family;
	private static volatile String requested = normalize(System.getProperty(PROPERTY, DEFAULT));

	private FontKit() {
	}

	/** A família em uso (carregada na primeira chamada). */
	static Family family() {
		Family current = family;
		if (current == null) {
			synchronized (FontKit.class) {
				current = family;
				if (current == null) {
					current = load(requested);
					family = current;
				}
			}
		}
		return current;
	}

	/** Troca a família (testes e comparações): {@code "system"} ou o nome de uma pasta de {@code assets/handyphone/fonts}. */
	static synchronized void use(String name) {
		String normalized = normalize(name);
		if (!normalized.equals(requested) || family == null) {
			requested = normalized;
			family = null;
		}
	}

	private static String normalize(String name) {
		return name == null || name.isBlank() ? DEFAULT : name.trim().toLowerCase(Locale.ROOT);
	}

	/** O nome pedido (não necessariamente o carregado: ver {@link Family#name}). */
	static String requested() {
		return requested;
	}

	private static Family load(String name) {
		if (!SYSTEM.equals(name)) {
			try {
				Font regular = read(name, "Regular"), bold = read(name, "Bold"), light = read(name, "Light");
				if (regular != null && bold != null && light != null) {
					return new Family(name, true, regular, bold, light);
				}
			} catch (IOException | FontFormatException | RuntimeException e) {
				// cai na fonte do sistema
			}
		}
		return system();
	}

	private static Font read(String name, String weight) throws IOException, FontFormatException {
		try (InputStream in = FontKit.class.getResourceAsStream("/assets/handyphone/fonts/" + name + "/" + weight + ".ttf")) {
			return in == null ? null : Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(12f);
		}
	}

	private static Family system() {
		Font sans = pick("Segoe UI", Font.SANS_SERIF);
		return new Family(SYSTEM, false, sans, sans, pick("Segoe UI Light", Font.SANS_SERIF));
	}

	/** A fonte do sistema com este nome se existe; senão a lógica de reserva. */
	static Font pick(String preferred, String fallback) {
		Font f = new Font(preferred, Font.PLAIN, 12);
		return f.getFamily().toLowerCase(Locale.ROOT).contains(preferred.toLowerCase(Locale.ROOT)) ? f : new Font(fallback, Font.PLAIN, 12);
	}
}
