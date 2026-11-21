package dev.migzu.handyphone.client.os;

/**
 * Momentos sonoros do celular. Fica no pacote {@code os} sem depender do Minecraft;
 * quem traduz cada item para o evento de áudio do tema atual é o lado do cliente.
 */
public enum PhoneSound {
	// A. Aparelho físico (iguais em todos os temas)
	PULL("pull", Group.DEVICE, true),
	FLIP_FRONT("flip_front", Group.DEVICE, false),
	PUT_AWAY("put_away", Group.DEVICE, true),
	EXPAND("expand", Group.DEVICE, false),
	COLLAPSE("collapse", Group.DEVICE, false),
	HOME_KEY_DOWN("home_key_down", Group.DEVICE, false),
	HOME_KEY_UP("home_key_up", Group.DEVICE, false),
	SCREEN_ON("screen_on", Group.DEVICE, false),
	SCREEN_OFF("screen_off", Group.DEVICE, false),

	// B. Interface (mudam conforme o tema)
	ICON_TAP("icon_tap", Group.UI, true),
	APP_OPEN("app_open", Group.UI, false),
	APP_CLOSE("app_close", Group.UI, false),
	BUTTON("button", Group.UI, true),
	LIST_ITEM("list_item", Group.UI, true),
	SELECT("select", Group.UI, false),
	THEME_CHANGE("theme_change", Group.UI, false),
	FIELD_FOCUS("field_focus", Group.UI, false),
	KEY_TYPE("key", Group.KEYBOARD, true),
	KEY_DELETE("key_delete", Group.KEYBOARD, true),
	KEY_ENTER("key_enter", Group.KEYBOARD, false),
	SCROLL_TICK("scroll_tick", Group.UI, true),

	// C. Mensagens e contatos (mudam conforme o tema)
	MESSAGE_SENT("message_sent", Group.UI, false),
	MESSAGE_RECEIVED("message_received", Group.UI, false),
	NOTIFICATION("notification", Group.NOTIFICATION, false),
	NOTIFICATION_GROUP("notification_group", Group.NOTIFICATION, false),
	RINGTONE("ringtone", Group.NOTIFICATION, false),
	ERROR("error", Group.UI, false),
	CONTACT_SAVED("contact_saved", Group.UI, false),
	GROUP_CREATED("group_created", Group.UI, false),
	CONTACT_REMOVED("contact_removed", Group.UI, false),

	// D. Ligações (mudam conforme o tema). Os arquivos de áudio de call_* chegam em separado; enquanto não existem, o som só não toca.
	/** Quem liga ouve isto enquanto o outro lado toca (em laço). */
	CALL_DIAL("call_dial", Group.NOTIFICATION, false),
	CALL_CONNECT("call_connect", Group.NOTIFICATION, false),
	CALL_END("call_end", Group.NOTIFICATION, false),
	CALL_BUSY("call_busy", Group.NOTIFICATION, false),
	/** Alguém entrou na chamada em grupo (etapa 5b). */
	CALL_JOIN("call_join", Group.NOTIFICATION, false),
	/** Alguém saiu da chamada em grupo (etapa 5b). */
	CALL_LEAVE("call_leave", Group.NOTIFICATION, false);

	public enum Group {
		/** Sons físicos do aparelho e da mão; não dependem de tema. */
		DEVICE,
		/** Sons de navegação, toques e ações de interface; respeitam o ajuste "Sons da interface". */
		UI,
		/** Cliques de digitação; respeitam o ajuste separado "Som do teclado". */
		KEYBOARD,
		/** Alertas de mensagem nova com a conversa fechada; respeitam "Sons da interface". */
		NOTIFICATION
	}

	private final String key;
	private final Group group;
	private final boolean varyPitch;

	PhoneSound(String key, Group group, boolean varyPitch) {
		this.key = key;
		this.group = group;
		this.varyPitch = varyPitch;
	}

	/** Nome do evento dentro do grupo (por exemplo, {@code icon_tap} ou {@code flip_front}). */
	public String key() {
		return key;
	}

	public Group group() {
		return group;
	}

	/** Verdadeiro se o som tem uma versão por tema; falso para os sons físicos do aparelho. */
	public boolean isThemed() {
		return group != Group.DEVICE;
	}

	/** Verdadeiro se deve receber micro-variação de tom (±4%) a cada reprodução. */
	public boolean varyPitch() {
		return varyPitch;
	}

	/** Caminho do evento no {@code sounds.json} para o tema dado. */
	public String eventPath(String themeId) {
		return (isThemed() ? themeId : "device") + "." + key;
	}
}
