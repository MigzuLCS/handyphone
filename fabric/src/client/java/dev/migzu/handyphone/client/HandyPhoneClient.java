package dev.migzu.handyphone.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.net.PhoneClientNet;
import dev.migzu.handyphone.client.phone.CallAudio;
import dev.migzu.handyphone.client.phone.CallHud;
import dev.migzu.handyphone.client.phone.PhoneHintHud;
import dev.migzu.handyphone.client.phone.KeyConflictNotice;
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;

public class HandyPhoneClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HandyPhone.id("main"));
	public static final KeyMapping EXPAND_KEY = new KeyMapping("key.handyphone.expand", InputConstants.KEY_Z, CATEGORY);

	@Override
	public void onInitializeClient() {
		// O item do celular só vai à aba criativa onde o servidor o conhece: partida local ou servidor Fabric (com o mod). No plugin Paper (marca
		// "Paper") ele cairia a conexão ao ser pego; marca desconhecida (proxy) = esconde, o que nunca faz mal (o item não serve para nada).
		dev.migzu.handyphone.HandyPhone.creativeTabVisible = () -> {
			net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
			if (mc.getSingleplayerServer() != null) {
				return true;
			}
			var connection = mc.getConnection();
			String brand = connection == null ? null : connection.serverBrand();
			// A marca "fabric" sozinha não basta (servidor Fabric sem o HandyPhone): ele também tem de ter anunciado os canais do celular.
			return brand != null && brand.toLowerCase(java.util.Locale.ROOT).contains("fabric")
				&& dev.migzu.handyphone.fabric.client.FabricClientNet.canSend(dev.migzu.handyphone.net.PhonePayloads.Sync.TYPE);
		};
		PhoneSounds.register();
		KeyMappingHelper.registerKeyMapping(EXPAND_KEY);
		PhoneClientNet.register();
		RouteHud.register();
		RouteBeam.register();
		CallHud.register();
		PhoneHintHud.register();
		// Tecla do celular presa a outro mapeamento (de outro mod, quase sempre): avisa no chat, uma vez por combinação.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> KeyConflictNotice.onJoin());

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			PhoneLang.tick(mc);
			PhoneCameraSource.INSTANCE.tick();
			LocationTracker.tick(mc);
			CallAudio.tick(mc);
			KeyConflictNotice.tick(mc);
			// Sem tela aberta, a tecla puxa o celular para a mão (câmera livre, roda no destaque) ou, já na mão, o guarda.
			// Expandido, a tecla chega à tela de entrada (PhoneInputScreen), que volta o celular para a mão.
			PhoneController.INSTANCE.handTick(mc);
			while (EXPAND_KEY.consumeClick()) {
				if (PhoneController.INSTANCE.isInUse()) {
					PhoneController.INSTANCE.putAway();
				} else {
					PhoneController.INSTANCE.open(false);
				}
			}
		});
	}
}
