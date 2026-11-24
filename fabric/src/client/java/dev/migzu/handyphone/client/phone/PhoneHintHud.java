package dev.migzu.handyphone.client.phone;

import dev.migzu.handyphone.HandyPhone;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Dica dos controles com o celular na mão (sem tela aberta): aparece no alto da tela por alguns segundos ao puxar o celular
 * ou ao voltá-lo para a mão, e some aos poucos, para não ficar no caminho de quem joga.
 */
public final class PhoneHintHud implements HudElement {
	private static final long SHOW_MS = 5000;
	private static final long FADE_MS = 1000;

	private PhoneHintHud() {
	}

	public static void register() {
		HudElementRegistry.addLast(HandyPhone.id("phone_hint"), new PhoneHintHud());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		PhoneController ctrl = PhoneController.INSTANCE;
		if (!ctrl.inHand() || !ctrl.os.settings().controlsHint()) {
			return;
		}
		long age = System.currentTimeMillis() - ctrl.handSinceMillis();
		if (age > SHOW_MS) {
			return;
		}
		float alpha = age < SHOW_MS - FADE_MS ? 1f : (SHOW_MS - age) / (float) FADE_MS;
		int a = Math.max(4, Math.round(0xB0 * alpha));
		Minecraft mc = Minecraft.getInstance();
		graphics.centeredText(mc.font, Component.translatable("handyphone.hint.hand"), graphics.guiWidth() / 2, 6, (a << 24) | 0xFFFFFF);
	}
}
