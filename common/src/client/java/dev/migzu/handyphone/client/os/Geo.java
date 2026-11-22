package dev.migzu.handyphone.client.os;

import java.util.Locale;

/** Textos de coordenadas, distâncias e dimensões, iguais no celular e no indicador de rota do jogo. */
public final class Geo {
	private Geo() {
	}

	/** As cores que um local salvo pode ter (o servidor guarda só o índice). */
	private static final int[] PLACE_COLORS = {0x1F9E89, 0xE5392E, 0xF57C00, 0xF2B01E, 0x43A047, 0x1E88E5, 0x8E24AA, 0xD81B60};

	public static int placeColorCount() {
		return PLACE_COLORS.length;
	}

	/** Cor (RGB) do local; índice fora da paleta volta para a primeira. */
	public static java.awt.Color placeColor(int index) {
		return new java.awt.Color(PLACE_COLORS[index >= 0 && index < PLACE_COLORS.length ? index : 0]);
	}

	/** "minecraft:the_nether" vira "Nether"; dimensões de outros mods viram o nome do caminho com iniciais maiúsculas. */
	public static String dimensionName(String id) {
		return switch (id) {
			case "minecraft:overworld" -> Lang.t("handyphone.dimension.overworld");
			case "minecraft:the_nether" -> Lang.t("handyphone.dimension.nether");
			case "minecraft:the_end" -> Lang.t("handyphone.dimension.end");
			default -> {
				int colon = id.indexOf(':');
				String path = (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ').replace('/', ' ').strip();
				yield path.isEmpty() ? "?" : Character.toUpperCase(path.charAt(0)) + path.substring(1);
			}
		};
	}

	/** "230 m" ou "1,4 km". */
	public static String distance(double blocks) {
		if (blocks < 1000) {
			return Lang.t("handyphone.distance.m", Long.toString(Math.round(blocks)));
		}
		return Lang.t("handyphone.distance.km", Lang.decimal(blocks / 1000.0));
	}

	/**
	 * Distância (na horizontal) do jogador a um ponto, como texto; "em outra dimensão" se o ponto é de outra
	 * dimensão (sem converter coordenadas); vazio se ainda não sabemos onde o jogador está.
	 */
	public static String distanceFrom(PhoneData.SelfPos self, String dimension, int x, int z) {
		if (!self.known) {
			return "";
		}
		if (!self.dimension.equals(dimension)) {
			return Lang.t("handyphone.distance.other_dimension");
		}
		return distance(Math.hypot(x + 0.5 - self.x, z + 0.5 - self.z));
	}

	/** Igual à outra, a partir de uma posição consistente (para o HUD, que lê sem trava). */
	public static String distanceFrom(PhoneData.Fix fix, String dimension, int x, int z) {
		if (fix == null) {
			return "";
		}
		if (!fix.dimension().equals(dimension)) {
			return Lang.t("handyphone.distance.other_dimension");
		}
		return distance(Math.hypot(x + 0.5 - fix.x(), z + 0.5 - fix.z()));
	}

	/** "X 120  Y 64  Z -30" (sem o Y quando o ponto veio do mapa e não tem altura). */
	public static String coords(int x, boolean hasY, int y, int z) {
		return hasY ? "X " + x + "  Y " + y + "  Z " + z : "X " + x + "  Z " + z;
	}

	/** Tempo que falta, em texto curto: "14 min", "1 h 05", "45 s". */
	public static String remaining(long millis) {
		long seconds = Math.max(0, millis / 1000);
		if (seconds < 60) {
			return Lang.t("handyphone.time.seconds", Long.toString(seconds));
		}
		long minutes = (seconds + 59) / 60;
		if (minutes < 60) {
			return Lang.t("handyphone.time.minutes", Long.toString(minutes));
		}
		return Lang.t("handyphone.time.hours", Long.toString(minutes / 60), String.format(Locale.ROOT, "%02d", minutes % 60));
	}
}
