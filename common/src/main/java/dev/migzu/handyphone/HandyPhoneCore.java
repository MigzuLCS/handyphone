package dev.migzu.handyphone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** O que todo o HandyPhone (servidor, celular e cada plataforma) compartilha: o id do mod, o nome nos textos e o log. */
public final class HandyPhoneCore {
	public static final String MOD_ID = "handyphone";
	/** O nome do mod nos textos (provisório): os avisos dos comandos o recebem daqui, em vez de repeti-lo nos idiomas. */
	public static final String DISPLAY_NAME = "HandyPhone";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private HandyPhoneCore() {
	}
}
