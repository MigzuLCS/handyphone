package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLMouse;

/**
 * Cursor do celular: com a tela de entrada aberta e o mouse sobre a tela do aparelho, o cursor do sistema some e uma cruz
 * aparece colada no vidro (desenhada por {@link PhoneHandRenderer} junto do modelo, sem redesenhar a imagem da tela).
 * Fora do aparelho, o cursor do sistema volta. Tudo na thread de render (que é a principal, onde o SDL pode ser chamado).
 *
 * O Minecraft 26.3 não usa SDL_HideCursor/SDL_ShowCursor (esconde o cursor com o modo relativo ao capturar o mouse), então
 * esconder e mostrar aqui não briga com o jogo; ao fechar a tela o cursor é sempre devolvido ({@link #release()}).
 */
public final class PhoneCursor {
	/** Posição da cruz em pontos da tela do celular (0..FACE_W, 0..FACE_H), ou null fora do aparelho. Só a thread de render usa. */
	private static float[] pos;
	/** O desenho da mão rodou desde o último quadro (em terceira pessoa, dormindo ou com F1 ele não roda). */
	private static boolean drawnThisFrame;
	private static boolean systemCursorHidden;
	/** Botão esquerdo apertado: a cruz encolhe um pouco, como um toque. */
	private static volatile boolean pressed;

	private PhoneCursor() {
	}

	/**
	 * Chamado pelo desenho do celular, depois de atualizar a matriz da tela. Calcula onde o mouse cai na tela do aparelho
	 * e devolve a posição da cruz (null = não desenhar).
	 */
	static float[] locate(Minecraft mc, PhoneController ctrl) {
		drawnThisFrame = true;
		if (!(mc.gui.screen() instanceof PhoneInputScreen) || mc.mouseHandler.isMouseGrabbed()) {
			pos = null;
			return null;
		}
		Window window = mc.getWindow();
		int w = window.getGuiScaledWidth();
		int h = window.getGuiScaledHeight();
		if (w <= 0 || h <= 0) {
			pos = null;
			return null;
		}
		pos = ctrl.pick(mc.mouseHandler.getScaledXPos(window) / w, mc.mouseHandler.getScaledYPos(window) / h, (double) w / h);
		return pos;
	}

	/** Uma vez por quadro (PhoneController.advance): esconde ou mostra o cursor do sistema conforme o quadro anterior. */
	static void endFrame() {
		if (!drawnThisFrame) {
			pos = null;
		}
		drawnThisFrame = false;
		setSystemCursorHidden(pos != null);
	}

	/** A tela do celular fechou: o cursor do sistema volta sempre. */
	static void release() {
		pos = null;
		pressed = false;
		setSystemCursorHidden(false);
	}

	/** Posição atual da cruz (cópia), ou null; para os testes. */
	public static float[] current() {
		float[] p = pos;
		return p == null ? null : p.clone();
	}

	/** O cursor do sistema está escondido agora; para os testes. */
	public static boolean systemCursorHidden() {
		return systemCursorHidden;
	}

	static void setPressed(boolean down) {
		pressed = down;
	}

	static boolean pressed() {
		return pressed;
	}

	private static void setSystemCursorHidden(boolean hide) {
		if (hide == systemCursorHidden) {
			return;
		}
		systemCursorHidden = hide;
		if (hide) {
			SDLMouse.SDL_HideCursor();
		} else {
			SDLMouse.SDL_ShowCursor();
		}
	}
}
