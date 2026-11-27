package dev.migzu.handyphone.client.os;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lista de apoiadores do app Apoiadores. Vem dentro do mod ({@code assets/handyphone/supporters.json}), nada é baixado: o Migzu manda
 * as doações e a lista é atualizada a cada versão. Uma entrada por doação: {@code name}, {@code date} (AAAA-MM-DD), {@code amount}
 * (dólares) e {@code message} (opcional, só a partir de US$ 5; abaixo disso é ignorada). Entrada estragada é pulada, nunca derruba o app.
 */
final class Supporters {
	/** Valor mínimo (em centavos de dólar) para a mensagem aparecer. */
	static final long MESSAGE_MIN_CENTS = 500;
	static final int MAX_NAME = 40, MAX_MESSAGE = 200;
	static final String RESOURCE = "/assets/handyphone/supporters.json";

	record Entry(String name, LocalDate date, long cents, String message) {
	}

	enum Order { DATE, VALUE }

	private static List<Entry> loaded;

	private Supporters() {
	}

	/** A lista do jar (lida uma vez). */
	static synchronized List<Entry> load() {
		if (loaded == null) {
			List<Entry> list = List.of();
			try (InputStream in = Supporters.class.getResourceAsStream(RESOURCE)) {
				if (in != null) {
					list = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
				}
			} catch (Exception e) {
				list = List.of();
			}
			loaded = list;
		}
		return loaded;
	}

	/** Lê o JSON ({@code {"supporters": [...]}}); o que não estiver certo fica de fora. */
	static List<Entry> parse(String json) {
		List<Entry> out = new ArrayList<>();
		JsonArray array;
		try {
			JsonElement root = JsonParser.parseString(json);
			array = root.isJsonArray() ? root.getAsJsonArray() : root.getAsJsonObject().getAsJsonArray("supporters");
		} catch (Exception e) {
			return List.of();
		}
		if (array == null) {
			return List.of();
		}
		for (JsonElement el : array) {
			try {
				JsonObject o = el.getAsJsonObject();
				String name = clean(o.get("name").getAsString(), MAX_NAME);
				LocalDate date = LocalDate.parse(o.get("date").getAsString());
				double amount = o.get("amount").getAsDouble();
				if (name.isEmpty() || !(amount > 0) || amount > 1_000_000) {
					continue;
				}
				long cents = Math.round(amount * 100);
				String message = o.has("message") && !o.get("message").isJsonNull() ? clean(o.get("message").getAsString(), MAX_MESSAGE) : "";
				if (cents < MESSAGE_MIN_CENTS) {
					message = ""; // mensagem só a partir de US$ 5
				}
				out.add(new Entry(name, date, cents, message));
			} catch (Exception ignored) {
				// entrada estragada: pula
			}
		}
		return Collections.unmodifiableList(out);
	}

	/** Tira caracteres de controle e invisíveis (Cf: largura zero, direção), troca quebras por espaço e corta no tamanho. */
	static String clean(String text, int max) {
		StringBuilder sb = new StringBuilder(text.length());
		text.codePoints().forEach(cp -> {
			int type = Character.getType(cp);
			if (cp == '\n' || cp == '\r' || cp == '\t') {
				sb.append(' ');
			} else if (cp >= 32 && cp != 127 && cp != 0xA7 && type != Character.FORMAT && type != Character.CONTROL) {
				sb.appendCodePoint(cp);
			}
		});
		String s = sb.toString().strip().replaceAll(" {2,}", " ");
		if (s.codePointCount(0, s.length()) > max) {
			s = s.substring(0, s.offsetByCodePoints(0, max)).strip() + "…";
		}
		return s;
	}

	/** A lista na ordem pedida; {@code descending} = mais recente (ou maior) primeiro. Empates: mais recente, depois o nome. */
	static List<Entry> sorted(List<Entry> list, Order order, boolean descending) {
		Comparator<Entry> byDate = Comparator.comparing(Entry::date);
		Comparator<Entry> primary = order == Order.DATE ? byDate : Comparator.comparingLong(Entry::cents);
		if (descending) {
			primary = primary.reversed();
		}
		Comparator<Entry> c = primary.thenComparing(byDate.reversed()).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
		List<Entry> copy = new ArrayList<>(list);
		copy.sort(c);
		return copy;
	}

	/** Valor no jeito do idioma ("US$ 5,00", "$5.00"). */
	static String money(long cents) {
		String number = String.format(Lang.locale(), "%,.2f", cents / 100.0);
		return Lang.t("handyphone.supporters.amount", number);
	}
}
