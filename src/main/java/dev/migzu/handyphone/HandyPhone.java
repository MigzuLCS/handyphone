package dev.migzu.handyphone;

import dev.migzu.handyphone.item.PhoneItem;
import dev.migzu.handyphone.net.PhonePayloads;
import dev.migzu.handyphone.server.PhoneCommands;
import dev.migzu.handyphone.server.PhoneService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandyPhone implements ModInitializer {
	public static final String MOD_ID = "handyphone";
	/** O nome do mod nos textos (provisório): os avisos dos comandos o recebem daqui, em vez de repeti-lo nos idiomas. */
	public static final String DISPLAY_NAME = "HandyPhone";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final ResourceKey<Item> PHONE_KEY = ResourceKey.create(Registries.ITEM, id("phone"));
	public static final Item PHONE = Registry.register(
		BuiltInRegistries.ITEM, PHONE_KEY, new PhoneItem(new Item.Properties().setId(PHONE_KEY).stacksTo(1))
	);

	@Override
	public void onInitialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(PHONE));
		PhonePayloads.register();
		PhoneService.register();
		PhoneCommands.register();
		LOGGER.info("HandyPhone loaded");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
