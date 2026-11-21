package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.platform.InputConstants;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.HandyPhoneClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import dev.migzu.handyphone.client.os.Lang;
import dev.migzu.handyphone.client.os.PhoneOS;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.nio.file.Path;
import java.util.List;

/**
 * Tela invisível que só libera o cursor e encaminha cliques/teclas ao celular.
 * O celular em si continua sendo o objeto 3D na mão do jogador.
 */
public class PhoneInputScreen extends Screen {
	private final PhoneController ctrl = PhoneController.INSTANCE;

	public PhoneInputScreen() {
		super(Component.literal(HandyPhone.DISPLAY_NAME));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		// Sem escurecer nem desfocar: o mundo continua visível.
	}

	@Override
	public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
		Component hint = Component.translatable("handyphone.hint.use", HandyPhoneClient.EXPAND_KEY.getTranslatedKeyMessage());
		graphics.centeredText(this.font, hint, this.width / 2, 6, 0xB0FFFFFF);
	}

	private float[] pick(double x, double y) {
		return ctrl.pick(x / this.width, y / this.height, (double) this.width / this.height);
	}

	@Override
	public boolean mouseClicked(final MouseButtonEvent event, final boolean doubleClick) {
		if (event.button() == InputConstants.MOUSE_BUTTON_RIGHT) {
			this.onClose();
			return true;
		}
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			float[] p = pick(event.x(), event.y());
			if (p != null) {
				synchronized (ctrl.os) {
					ctrl.os.pointerDown(p[0], p[1]);
				}
				syncTextInput();
			}
			return true;
		}
		return false;
	}

	/** Arrastar com o botão esquerdo (move o mapa). */
	@Override
	public boolean mouseDragged(final MouseButtonEvent event, final double dx, final double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			float[] p = pick(event.x(), event.y());
			if (p != null) {
				synchronized (ctrl.os) {
					ctrl.os.pointerMove(p[0], p[1]);
				}
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseReleased(final MouseButtonEvent event) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			float[] p = pick(event.x(), event.y());
			synchronized (ctrl.os) {
				ctrl.os.pointerUp(p != null ? p[0] : -1000f, p != null ? p[1] : -1000f);
			}
			syncTextInput();
			return true;
		}
		return false;
	}

	@Override
	public boolean keyPressed(final KeyEvent event) {
		if (event.isEscape()) {
			boolean handled;
			synchronized (ctrl.os) {
				handled = ctrl.os.back();
			}
			if (!handled) {
				this.onClose();
			}
			return true;
		}
		// Ctrl+V: cola uma imagem copiada no computador (vai para a galeria). Antes da tecla de expandir, que também é o V.
		if (event.isPaste() || (event.key() == InputConstants.KEY_V && event.hasControlDown())) {
			pasteImage();
			return true;
		}
		// Com um campo de texto em uso, o teclado é do celular: nada de andar ou expandir.
		boolean typing;
		synchronized (ctrl.os) {
			typing = ctrl.os.wantsKeyboard();
			if (typing) {
				PhoneOS.Key key = switch (event.key()) {
					case InputConstants.KEY_BACKSPACE -> PhoneOS.Key.BACKSPACE;
					case InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER -> PhoneOS.Key.ENTER;
					case InputConstants.KEY_TAB -> PhoneOS.Key.TAB;
					default -> null;
				};
				if (key != null) {
					ctrl.os.key(key);
				}
			}
		}
		syncTextInput();
		if (typing) {
			return true;
		}
		if (HandyPhoneClient.EXPAND_KEY.matches(event)) {
			ctrl.toggleExpanded();
			return true;
		}
		if (isMovementKey(event)) {
			KeyMapping.set(InputConstants.getKey(event), true);
			return true;
		}
		return false;
	}

	@Override
	public boolean charTyped(final CharacterEvent event) {
		synchronized (ctrl.os) {
			if (ctrl.os.wantsKeyboard()) {
				ctrl.os.typed(event.codepointAsString());
				return true;
			}
		}
		return false;
	}

	/** Arquivos de imagem arrastados do computador para a janela do jogo entram na galeria. */
	@Override
	public void onFilesDrop(final List<Path> files) {
		synchronized (ctrl.os) {
			ctrl.os.importFiles(files);
		}
	}

	private void pasteImage() {
		byte[] image = ClipboardImage.read();
		synchronized (ctrl.os) {
			if (image != null) {
				ctrl.os.importBytes(image, Lang.t("handyphone.hud.pasted_image"));
			} else {
				ctrl.os.notify(Lang.t("handyphone.banner.photos"), Lang.t("handyphone.hud.no_clipboard_image"), -1);
			}
		}
	}

	@Override
	public boolean mouseScrolled(final double x, final double y, final double scrollX, final double scrollY) {
		synchronized (ctrl.os) {
			ctrl.os.scroll((float) scrollY);
		}
		return true;
	}

	@Override
	public boolean keyReleased(final KeyEvent event) {
		if (isMovementKey(event)) {
			KeyMapping.set(InputConstants.getKey(event), false);
			return true;
		}
		return false;
	}

	/** Deixa o jogador continuar andando enquanto mexe no celular. */
	private boolean isMovementKey(final KeyEvent event) {
		for (KeyMapping key : movementKeys()) {
			if (key.matches(event)) {
				return true;
			}
		}
		return false;
	}

	private KeyMapping[] movementKeys() {
		var o = this.minecraft.options;
		return new KeyMapping[] {o.keyUp, o.keyDown, o.keyLeft, o.keyRight, o.keyJump, o.keySprint, o.keyShift};
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	@Override
	public void tick() {
		// A mão deixa de ser desenhada no espectador e dormindo; o celular guarda sozinho. Trocar de slot não importa.
		var player = this.minecraft.player;
		if (player == null || player.isSpectator() || player.isSleeping()) {
			this.onClose();
			return;
		}
		syncTextInput();
	}

	/** Abrir a tela solta todas as teclas; as de andar que continuam apertadas voltam, para o jogador não parar ao puxar o celular. */
	@Override
	protected void init() {
		KeyMapping.setAll();
		// Agachar/correr no modo "alternar" também são soltos ao abrir a tela e o setAll não os religa.
		KeyMapping.restoreToggleStatesOnScreenClosed();
	}

	/**
	 * Liga a entrada de texto do SDL enquanto um campo do celular está em foco, e desliga quando não está.
	 * No SDL o texto digitado só chega em charTyped com a entrada de texto ligada, e o jogo só a liga
	 * quando um EditBox ganha foco (TextInputManager); como o celular não usa EditBox, ligamos nós mesmos.
	 */
	private void syncTextInput() {
		boolean wants;
		synchronized (ctrl.os) {
			wants = ctrl.os.wantsKeyboard();
		}
		this.minecraft.textInputManager().onTextInputFocusChange(this, wants);
	}

	@Override
	public void removed() {
		this.minecraft.textInputManager().stopTextInput(this);
		for (KeyMapping key : movementKeys()) {
			key.setDown(false);
		}
		ctrl.onClosed();
		super.removed();
	}

	@Override
	public void onClose() {
		Minecraft.getInstance().gui.setScreen(null);
	}
}
