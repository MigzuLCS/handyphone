package dev.migzu.handyphone;

import dev.migzu.handyphone.i18n.LangFile;
import dev.migzu.handyphone.item.PhoneItem;
import dev.migzu.handyphone.fabric.FabricNet;
import dev.migzu.handyphone.fabric.FabricServer;
import dev.migzu.handyphone.server.PhoneCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

public class HandyPhone implements ModInitializer {
	public static final String MOD_ID = HandyPhoneCore.MOD_ID;
	/** O nome do mod nos textos (provisório): os avisos dos comandos o recebem daqui, em vez de repeti-lo nos idiomas. */
	public static final String DISPLAY_NAME = HandyPhoneCore.DISPLAY_NAME;
	public static final Logger LOGGER = HandyPhoneCore.LOGGER;

	public static final ResourceKey<Item> PHONE_KEY = ResourceKey.create(Registries.ITEM, id("phone"));
	public static final Item PHONE = Registry.register(
		BuiltInRegistries.ITEM, PHONE_KEY, new PhoneItem(new Item.Properties().setId(PHONE_KEY).stacksTo(1))
	);

	/**
	 * O item aparece na aba criativa? O cliente troca isto (ver {@code HandyPhoneClient}): num servidor que não tem o item (o plugin Paper), pegar
	 * o item no criativo faz o servidor falhar ao ler o pacote ({@code set_creative_mode_slot}, id de item que ele não conhece) e o jogador cai.
	 */
	public static volatile BooleanSupplier creativeTabVisible = () -> true;

	@Override
	public void onInitialize() {
		// Os idiomas do jar, como o carregador de mods os vê (o servidor junta os nomes reservados de todos).
		LangFile.setBundledLister(HandyPhone::bundledLanguages);
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> {
			if (creativeTabVisible.getAsBoolean()) {
				output.accept(PHONE);
			}
		});
		FabricNet.register();
		FabricServer.register();
		PhoneCommands.register();
		LOGGER.info("HandyPhone loaded");
	}

	private static java.util.List<String> bundledLanguages() {
		try {
			return LangFile.list(FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow().findPath("assets/handyphone/lang").orElseThrow());
		} catch (java.io.IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
