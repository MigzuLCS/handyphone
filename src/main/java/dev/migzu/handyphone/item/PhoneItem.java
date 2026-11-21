package dev.migzu.handyphone.item;

import net.minecraft.world.item.Item;

/**
 * O item do celular continua registrado (e na aba criativa), mas não é usado para nada: todo jogador tem celular,
 * puxado pela tecla (V por padrão), com ou sem o item.
 */
public class PhoneItem extends Item {
	public PhoneItem(Properties properties) {
		super(properties);
	}
}
