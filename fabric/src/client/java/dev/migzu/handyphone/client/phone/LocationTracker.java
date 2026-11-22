package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.client.os.PhoneOS;
import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Passa ao celular, a cada tick, onde o jogador está (posição, olhar, dimensão e bioma). Isso serve só para
 * desenhar a própria seta, calcular distâncias e terminar a rota ao chegar: a posição que os OUTROS veem
 * vem sempre do servidor, nunca daqui.
 */
public final class LocationTracker {
	private static int ticks;
	private static String biome = "";

	private LocationTracker() {
	}

	public static void tick(Minecraft mc) {
		LocalPlayer player = mc.player;
		if (player == null || mc.level == null) {
			return;
		}
		// O nome do bioma muda devagar: não precisa ser recalculado a cada tick.
		if (ticks++ % 10 == 0) {
			biome = mc.level.getBiome(player.blockPosition()).unwrapKey()
				.map(key -> Component.translatable(Util.makeDescriptionId("biome", key.identifier())).getString())
				.orElse("");
		}
		PhoneOS os = PhoneController.INSTANCE.os;
		// Sem a trava do PhoneOS (a thread de desenho a segura durante cada quadro): o jogo não espera por ela.
		os.setSelf(mc.level.dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(), player.getYRot(), biome);
		// A lógica que mexe no estado do celular (chegada, "olhando ao vivo") pode esperar: poucas vezes por segundo.
		if (ticks % 5 == 0) {
			synchronized (os) {
				os.locationTick();
			}
		}
	}
}
