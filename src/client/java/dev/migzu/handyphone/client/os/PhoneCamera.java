package dev.migzu.handyphone.client.os;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

/**
 * A câmera do aparelho, do ponto de vista do {@link PhoneOS}: um visor e o disparo.
 * Quem implementa (do lado do jogo) captura o que o jogador vê no mundo, sem a
 * interface e sem o celular na frente. O PhoneOS não conhece o Minecraft.
 *
 * <p>Todos os quadros têm a proporção {@link #ASPECT_W}:{@link #ASPECT_H}, a mesma da
 * foto que sai: o que aparece no visor é exatamente o que fica na foto.
 */
public interface PhoneCamera {
	int ASPECT_W = 4, ASPECT_H = 3;

	/** Último quadro do visor, ou null se ainda não chegou nenhum (ou a câmera está desligada). */
	BufferedImage preview();

	/** Liga ou desliga o visor ao vivo; desligado, a captura não custa nada. */
	void setPreviewActive(boolean active);

	/**
	 * Pede uma foto. O resultado volta por {@code result} em qualquer thread: a imagem
	 * em tamanho real da janela, ou null se não foi possível capturar.
	 */
	void shoot(Consumer<BufferedImage> result);

	/** Usado quando não há jogo por trás (testes fora do Minecraft): nunca tem imagem. */
	PhoneCamera NONE = new PhoneCamera() {
		@Override
		public BufferedImage preview() {
			return null;
		}

		@Override
		public void setPreviewActive(boolean active) {
		}

		@Override
		public void shoot(Consumer<BufferedImage> result) {
			result.accept(null);
		}
	};
}
