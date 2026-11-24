package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.client.HandyPhoneClient;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;

/**
 * Avisa no chat, ao entrar num mundo, quando a tecla do celular é a mesma de outro mapeamento (do jogo ou de outro mod).
 * O Minecraft só aciona um dos mapeamentos que dividem a tecla, então o celular pode simplesmente não puxar.
 * Cada combinação (tecla + mapeamentos em conflito) é avisada uma vez só; trocar de tecla e cair em outro conflito avisa de novo.
 */
public final class KeyConflictNotice {
	/** Ticks depois de entrar, para o aviso não se perder entre as mensagens de entrada. */
	private static final int DELAY_TICKS = 60;
	private static int countdown = -1;

	private KeyConflictNotice() {
	}

	/** Chamado ao conectar num mundo ou servidor. */
	public static void onJoin() {
		countdown = DELAY_TICKS;
	}

	/** Chamado no fim de cada tick do cliente. */
	public static void tick(Minecraft mc) {
		if (countdown < 0 || --countdown > 0) {
			return;
		}
		countdown = -1;
		if (mc.player == null) {
			return;
		}
		List<KeyMapping> conflicts = conflicts(mc);
		String signature = signature(conflicts);
		PhoneController ctrl = PhoneController.INSTANCE;
		if (signature.equals(ctrl.keyConflictWarned())) {
			return;
		}
		ctrl.setKeyConflictWarned(signature);
		if (conflicts.isEmpty()) {
			return;
		}
		Component names = ComponentUtils.formatList(conflicts, m -> Component.translatable(m.getName()).withStyle(ChatFormatting.WHITE));
		mc.player.sendSystemMessage(Component.translatable("handyphone.hud.key_conflict",
			HandyPhoneClient.EXPAND_KEY.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.WHITE), names).withStyle(ChatFormatting.GOLD));
	}

	/** Outros mapeamentos presos à mesma tecla do celular. */
	public static List<KeyMapping> conflicts(Minecraft mc) {
		KeyMapping ours = HandyPhoneClient.EXPAND_KEY;
		List<KeyMapping> out = new ArrayList<>();
		if (ours.isUnbound()) {
			return out;
		}
		for (KeyMapping other : mc.options.keyMappings) {
			if (other != ours && !other.isUnbound() && ours.same(other)) {
				out.add(other);
			}
		}
		return out;
	}

	/** Identifica a combinação avisada; vazio = sem conflito (assim um conflito que volta depois é avisado de novo). */
	private static String signature(List<KeyMapping> conflicts) {
		if (conflicts.isEmpty()) {
			return "";
		}
		List<String> names = new ArrayList<>();
		for (KeyMapping m : conflicts) {
			names.add(m.getName());
		}
		names.sort(null);
		return HandyPhoneClient.EXPAND_KEY.saveString() + "|" + String.join(",", names);
	}
}
