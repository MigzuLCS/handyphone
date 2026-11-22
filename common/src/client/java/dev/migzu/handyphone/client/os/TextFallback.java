package dev.migzu.handyphone.client.os;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedString;
import java.text.BreakIterator;
import java.util.HashMap;
import java.util.Map;

/**
 * Texto com reserva por caractere. Uma fonte física do Java2D (as embutidas, a Segoe UI) não troca sozinha de fonte: o que ela não tem
 * (chinês, japonês, coreano, emoji, símbolos que os jogadores digitam) sairia como um quadrado. Aqui, o que a fonte primária não cobre é
 * desenhado com a fonte lógica {@code SansSerif} do sistema, por trecho, e a medida (largura, corte com reticências, cursor) enxerga os
 * mesmos trechos.
 *
 * <p><b>Caminho rápido (quase todo texto):</b> se todo caractere é {@linkplain #isCommon comum} (latim, cirílico das letras usuais, pontuação), a
 * fonte primária cobre (as fontes embutidas e as do sistema a que o mod recorre têm esses blocos), então o texto vai direto ao
 * {@code drawString}, sem alocar nada. Qualquer outro caractere é conferido com {@link Font#canDisplay(int)}; se tudo está coberto, ainda é
 * o caminho direto.
 *
 * <p><b>Caminho lento:</b> o texto é dividido em grafemas ({@link BreakIterator}: emoji com tom de pele, bandeiras e sequências com ZWJ
 * ficam inteiros); cada grafema usa a fonte primária se ela cobre todos os caracteres dele, senão a reserva; um {@link TextLayout} com
 * {@code TextAttribute.FONT} por trecho desenha e mede (ordem bidirecional correta e medida igual ao desenho).
 */
final class TextFallback {
	/** A reserva do mesmo tamanho (e negrito se a primária é negrito), guardada para não recriar a fonte a cada texto. */
	private static final Map<Long, Font> FALLBACKS = new HashMap<>();

	/** Desliga a reserva (só nos testes de custo: com {@code false}, tudo vai direto ao {@code drawString}, como antes). */
	static boolean enabled = true;

	private TextFallback() {
	}

	/**
	 * Caracteres que TODAS as fontes do celular têm (as quatro embutidas e a Segoe UI, conferido com as tabelas delas; o {@code PhoneFontTest}
	 * confere de novo): latim básico e Latin-1 (menos o hífen suave U+00AD, que a Inter não tem), latim estendido A, o cirílico das letras
	 * comuns (U+0400 a U+045F e o ґ ukrainiano) e a pontuação de uso comum (travessões, aspas, •, …). O resto é consultado com
	 * {@link Font#canDisplay(int)}.
	 */
	static boolean isCommon(int cp) {
		boolean inRange = cp <= 0x17F || (cp >= 0x400 && cp <= 0x45F) || cp == 0x490 || cp == 0x491
			|| (cp >= 0x2010 && cp <= 0x2015) || (cp >= 0x2018 && cp <= 0x201E) || (cp >= 0x2020 && cp <= 0x2022) || cp == 0x2026;
		if (!inRange) {
			return false;
		}
		// Os poucos caracteres dessas faixas que alguma das fontes não tem (conferido com as tabelas; o PhoneFontTest repete a conta).
		return switch (cp) {
			case 0xAD, 0x114, 0x115, 0x12C, 0x12D, 0x132, 0x133, 0x138, 0x13F, 0x140, 0x149, 0x14E, 0x14F, 0x17F, 0x2010, 0x2012, 0x2015, 0x201B -> false;
			default -> true;
		};
	}

	/** A fonte primária cobre {@code cp}? Os caracteres comuns valem sem consultar. */
	private static boolean covers(Font font, int cp) {
		return isCommon(cp) || font.canDisplay(cp);
	}

	/** O texto inteiro cabe na fonte primária? (Sem alocação.) */
	static boolean covered(Font font, String text) {
		for (int i = 0, n = text.length(); i < n; ) {
			char c = text.charAt(i);
			if (isCommon(c)) {
				i++;
				continue;
			}
			int cp = text.codePointAt(i);
			if (!font.canDisplay(cp)) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}

	static void draw(Graphics2D g, String text, float x, float y) {
		Font font = g.getFont();
		if (!enabled || covered(font, text)) {
			g.drawString(text, x, y);
			return;
		}
		layout(g, text, font).draw(g, x, y);
	}

	static float width(Graphics2D g, String text) {
		Font font = g.getFont();
		if (!enabled || covered(font, text)) {
			return (float) g.getFontMetrics().getStringBounds(text, g).getWidth();
		}
		return layout(g, text, font).getAdvance();
	}

	/** Os limites de grafema do texto (inclui 0 e o tamanho); em ASCII, um por caractere. */
	static int[] graphemeBounds(String text) {
		int n = text.length();
		boolean ascii = true;
		for (int i = 0; i < n; i++) {
			if (text.charAt(i) >= 0x300) {
				ascii = false;
				break;
			}
		}
		if (ascii) {
			int[] all = new int[n + 1];
			for (int i = 0; i <= n; i++) {
				all[i] = i;
			}
			return all;
		}
		BreakIterator iterator = BreakIterator.getCharacterInstance();
		iterator.setText(text);
		int[] bounds = new int[n + 1];
		int count = 0;
		bounds[count++] = 0;
		for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
			bounds[count++] = end;
		}
		return java.util.Arrays.copyOf(bounds, count);
	}

	/** Onde começa o último grafema (para apagar um caractere de verdade, inclusive emoji com várias partes). */
	static int lastGraphemeStart(CharSequence text) {
		if (text.isEmpty()) {
			return 0;
		}
		String s = text.toString();
		BreakIterator iterator = BreakIterator.getCharacterInstance();
		iterator.setText(s);
		int start = iterator.preceding(s.length());
		return start == BreakIterator.DONE ? 0 : start;
	}

	private record LayoutKey(String text, Font font, FontRenderContext frc) {
	}

	/** Os últimos textos do caminho lento (o celular redesenha os mesmos balões de CJK/emoji a cada quadro). */
	private static final Map<LayoutKey, TextLayout> LAYOUTS = new java.util.LinkedHashMap<>(64, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<LayoutKey, TextLayout> eldest) {
			return size() > 256;
		}
	};

	private static TextLayout layout(Graphics2D g, String text, Font primary) {
		LayoutKey key = new LayoutKey(text, primary, g.getFontRenderContext());
		synchronized (LAYOUTS) {
			TextLayout cached = LAYOUTS.get(key);
			if (cached != null) {
				return cached;
			}
		}
		TextLayout made = build(text, primary, key.frc());
		synchronized (LAYOUTS) {
			LAYOUTS.put(key, made);
		}
		return made;
	}

	private static TextLayout build(String text, Font primary, FontRenderContext frc) {
		return new TextLayout(attributed(text, primary).getIterator(), frc);
	}

	/**
	 * Quantos caracteres do começo de {@code text} cabem em {@code width} (pelo menos um grafema), com a reserva por trecho. Uma passada só
	 * ({@link LineBreakMeasurer}): serve para quebrar uma palavra enorme sem medir prefixo por prefixo.
	 */
	static int fitPrefix(Graphics2D g, String text, float width) {
		Font primary = g.getFont();
		if (covered(primary, text)) {
			int[] bounds = graphemeBounds(text);
			int low = 1, high = bounds.length - 1;
			while (low < high) {
				int mid = (low + high + 1) >>> 1;
				if (width(g, text.substring(0, bounds[mid])) <= width) {
					low = mid;
				} else {
					high = mid - 1;
				}
			}
			return bounds[low];
		}
		LineBreakMeasurer measurer = new LineBreakMeasurer(attributed(text, primary).getIterator(), g.getFontRenderContext());
		int end = measurer.nextOffset(width);
		return end > 0 ? end : graphemeBounds(text)[1];
	}

	private static AttributedString attributed(String text, Font primary) {
		AttributedString attributed = new AttributedString(text);
		Font fallback = fallbackFor(primary);
		int[] bounds = graphemeBounds(text);
		attributed.addAttribute(TextAttribute.FONT, primary, 0, text.length());
		for (int b = 0; b + 1 < bounds.length; b++) {
			int start = bounds[b], end = bounds[b + 1];
			if (!coveredRange(primary, text, start, end)) {
				attributed.addAttribute(TextAttribute.FONT, fallbackForRange(fallback, primary, text, start, end), start, end);
			}
		}
		return attributed;
	}

	private static boolean coveredRange(Font font, String text, int start, int end) {
		for (int i = start; i < end; ) {
			int cp = text.codePointAt(i);
			if (!covers(font, cp)) {
				return false;
			}
			i += Character.charCount(cp);
		}
		return true;
	}

	/**
	 * Fontes de emoji e símbolos, tentadas (em ordem) quando nem a SansSerif lógica cobre o grafema: no Windows a SansSerif do Java não tem os
	 * emoji (saem como quadrado), mas a "Segoe UI Emoji" tem; no Mac e no Linux, as de lá. Fonte que não existe no sistema vira "Dialog" e não cobre nada.
	 */
	private static final String[] EMOJI_FAMILIES = {"Segoe UI Emoji", "Apple Color Emoji", "Noto Color Emoji", "Noto Emoji", "Segoe UI Symbol"};

	/** A reserva comum (SansSerif); se ela não cobre o grafema inteiro, a primeira fonte de emoji que cobre; se nenhuma cobre, a comum mesmo. */
	private static Font fallbackForRange(Font common, Font primary, String text, int start, int end) {
		if (coveredRange(common, text, start, end)) {
			return common;
		}
		for (String family : EMOJI_FAMILIES) {
			Font candidate = emojiFont(family, common);
			if (coveredRange(candidate, text, start, end)) {
				return candidate;
			}
		}
		return common;
	}

	private static final Map<String, Font> EMOJI_FONTS = new HashMap<>();

	private static synchronized Font emojiFont(String family, Font like) {
		String key = family + "/" + like.getStyle() + "/" + like.getSize2D();
		return EMOJI_FONTS.computeIfAbsent(key, k -> new Font(family, like.getStyle(), 1).deriveFont(like.getSize2D()));
	}

	private static synchronized Font fallbackFor(Font primary) {
		boolean bold = (primary.getStyle() & Font.BOLD) != 0 || primary.getFontName().toLowerCase(java.util.Locale.ROOT).contains("bold");
		long key = ((long) Float.floatToIntBits(primary.getSize2D()) << 1) | (bold ? 1 : 0);
		return FALLBACKS.computeIfAbsent(key, k -> new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 1).deriveFont(primary.getSize2D()));
	}
}
