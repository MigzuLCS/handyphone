package dev.migzu.handyphone.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.net.PhoneClientNet;
import dev.migzu.handyphone.client.phone.CallAudio;
import dev.migzu.handyphone.client.phone.CallHud;
import dev.migzu.handyphone.client.phone.PhoneCameraSource;
import dev.migzu.handyphone.client.phone.LocationTracker;
import dev.migzu.handyphone.client.phone.PhoneController;
import dev.migzu.handyphone.client.phone.PhoneLang;
import dev.migzu.handyphone.client.phone.RouteBeam;
import dev.migzu.handyphone.client.phone.RouteHud;
import dev.migzu.handyphone.client.phone.PhoneSounds;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public class HandyPhoneClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HandyPhone.id("main"));
	public static final KeyMapping EXPAND_KEY = new KeyMapping("key.handyphone.expand", InputConstants.KEY_U, CATEGORY);

	@Override
	public void onInitializeClient() {
		PhoneSounds.register();
		KeyMappingHelper.registerKeyMapping(EXPAND_KEY);
		PhoneClientNet.register();
		RouteHud.register();
		RouteBeam.register();
		CallHud.register();

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			PhoneLang.tick(mc);
			PhoneCameraSource.INSTANCE.tick();
			LocationTracker.tick(mc);
			CallAudio.tick(mc);
			// Sem tela aberta, a tecla puxa o celular ja expandido (pronto para clicar), sem precisar de item.
			while (EXPAND_KEY.consumeClick()) {
				PhoneController.INSTANCE.open(true);
			}
		});
	}
}
