package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.client.HandyPhoneClient;
import dev.migzu.handyphone.client.os.ShortcutKeys;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * A tecla do celular trocada pelo app Ajustes: grava no mapeamento do jogo (o mesmo de Controles, salvo no {@code options.txt}).
 * Enquanto espera a tecla, a {@link PhoneInputScreen} entrega a próxima tecla ou botão do mouse para {@link #capture}.
 */
public final class PhoneShortcut implements ShortcutKeys {
	public static final PhoneShortcut INSTANCE = new PhoneShortcut();

	private volatile boolean capturing;

	private PhoneShortcut() {
	}

	@Override
	public String defaultName() {
		return HandyPhoneClient.EXPAND_KEY.getDefaultKey().getDisplayName().getString();
	}

	@Override
	public boolean isDefault() {
		return HandyPhoneClient.EXPAND_KEY.isDefault();
	}

	@Override
	public void startCapture() {
		capturing = true;
		// Na mão, as teclas são do jogo: expande para a tela de entrada receber a próxima tecla.
		Minecraft.getInstance().execute(() -> {
			PhoneController ctrl = PhoneController.INSTANCE;
			if (capturing && ctrl.inHand()) {
				ctrl.toggleExpanded();
			}
		});
	}

	@Override
	public boolean capturing() {
		return capturing;
	}

	@Override
	public void cancelCapture() {
		if (capturing) {
			capturing = false;
			PhoneController.INSTANCE.os.markDirty();
		}
	}

	@Override
	public void reset() {
		Minecraft.getInstance().execute(() -> apply(HandyPhoneClient.EXPAND_KEY.getDefaultKey()));
	}

	@Override
	public String conflict() {
		Minecraft mc = Minecraft.getInstance();
		List<KeyMapping> others = KeyConflictNotice.conflicts(mc);
		return others.stream().map(k -> net.minecraft.network.chat.Component.translatable(k.getName()).getString())
			.collect(Collectors.joining(", "));
	}

	/**
	 * Recebe a tecla (ou botão) enquanto espera; Esc cancela. Devolve true se a usou. Chamado na thread do jogo, pela tela de
	 * entrada. Esquerdo e direito do mouse não servem (são tocar e voltar).
	 */
	boolean capture(InputConstants.Key key, boolean escape) {
		if (!capturing) {
			return false;
		}
		if (escape) {
			cancelCapture();
			return true;
		}
		if (key.getType() == InputConstants.Type.MOUSE
			&& (key.getValue() == InputConstants.MOUSE_BUTTON_LEFT || key.getValue() == InputConstants.MOUSE_BUTTON_RIGHT)) {
			return false;
		}
		apply(key);
		return true;
	}

	private void apply(InputConstants.Key key) {
		capturing = false;
		Minecraft mc = Minecraft.getInstance();
		HandyPhoneClient.EXPAND_KEY.setKey(key);
		KeyMapping.resetMapping();
		mc.options.save();
		PhoneController.INSTANCE.os.setUseKeyName(HandyPhoneClient.EXPAND_KEY.getTranslatedKeyMessage().getString());
		PhoneController.INSTANCE.os.markDirty();
	}
}
