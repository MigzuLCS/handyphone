package dev.migzu.handyphone.client.os;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Um estilo visual completo do celular: moldura, tela de bloqueio, tela inicial e
 * a "casca" dos apps. O {@link PhoneOS} cuida do estado e dos toques; o tema decide
 * onde cada coisa fica e como é desenhada. O jogador escolhe o tema no app Ajustes.
 *
 * <p>Todas as coordenadas são as lógicas da face ({@link PhoneOS#FACE_W} x {@link PhoneOS#FACE_H}).
 */
abstract class Theme {
	/** Posição do app i entre os do mesmo grupo (grade ou dock). */
	static int slot(PhoneOS os, int i) {
		int slot = 0;
		for (int k = 0; k < i; k++) {
			if (os.apps.get(k).dock() == os.apps.get(i).dock()) {
				slot++;
			}
		}
		return slot;
	}

	/** Identificador estável, usado para salvar a escolha do jogador. */
	abstract String id();

	/** Nome mostrado no app Ajustes. */
	abstract String name();

	/** Descrição curta mostrada no app Ajustes. */
	abstract String description();

	/**
	 * Desliga (só nos testes) o atalho de não desenhar a tela inicial por baixo de um app totalmente aberto, para conferir que o atalho
	 * não muda nenhum pixel (PhonePerfTest).
	 */
	static boolean skipCoveredHome = true;

	/**
	 * Um app totalmente aberto cobre a tela inteira com um fundo opaco (a barra de status é desenhada depois dele): os ícones e a tela de
	 * bloqueio por baixo não aparecem, e desenhá-los era mais de um quarto do custo de cada quadro do app. O papel de parede continua
	 * sendo colado (é só uma cópia de imagem): é ele que aparece pela borda suavizada da janela do app, e assim a imagem fica igual
	 * à de antes, a menos de uns poucos pixels da borda em que um ícone encostava nela.
	 */
	static boolean homeCovered(PhoneOS os, float appOpen) {
		return skipCoveredHome && os.openApp >= 0 && appOpen >= 0.999f;
	}

	/** Solta as imagens de cache do tema (ao trocar de tema): a moldura pronta de um tema que não está em uso é memória à toa. */
	void releaseCaches() {
	}


	/** Raio (lógico) dos cantos da área da tela, como o tema a recorta. */
	float screenRadius() {
		return 3f;
	}

	/** Cor da borda interna da moldura, a que cerca a tela (o "vidro preto" em volta do display). */
	Color screenEdgeColor() {
		return Color.BLACK;
	}

	/**
	 * Liga ou desliga (só nos testes) a máscara da beirada da tela, para conferir a diferença nas capturas.
	 */
	static boolean maskScreenEdge = true;

	/**
	 * A beirada da tela cai quase sempre no meio de um pixel (a tela começa em 7 pontos lógicos, a 2,5 pixels por ponto: pixel 17,5). A
	 * borda suavizada do que é desenhado ali deixa o papel de parede aparecer nesse pixel, e com um app aberto vira uma linha fina de
	 * outra cor entre a moldura e a tela. Por isso, por cima de tudo (depois do conteúdo, da barra de status e das janelas), uma máscara
	 * opaca na cor da borda cobre os pixels da beirada que não estão inteiros dentro da tela, como o vidro preto em volta de um display de
	 * verdade. A borda de dentro da máscara cai sempre na grade de pixels (sem suavizar nos lados retos), então o conteúdo continua
	 * centrado e o toque não muda (a diferença é de no máximo um pixel).
	 */
	void drawScreenMask(Graphics2D g, float pixelScale) {
		if (!maskScreenEdge) {
			return;
		}
		float[] s = screen();
		double l = Math.ceil(s[0] * pixelScale - 1e-3), t = Math.ceil(s[1] * pixelScale - 1e-3);
		double r = Math.floor((s[0] + s[2]) * pixelScale + 1e-3), b = Math.floor((s[1] + s[3]) * pixelScale + 1e-3);
		double out = 1.2; // pontos lógicos para fora (a moldura é da mesma cor ali)
		Path2D ring = new Path2D.Double(Path2D.WIND_EVEN_ODD);
		ring.append(new Rectangle2D.Double(s[0] - out, s[1] - out, s[2] + 2 * out, s[3] + 2 * out), false);
		double arc = screenRadius() * 2;
		ring.append(new RoundRectangle2D.Double(l / pixelScale, t / pixelScale, (r - l) / pixelScale, (b - t) / pixelScale, arc, arc), false);
		g.setColor(screenEdgeColor());
		g.fill(ring);
	}

	/** Desenha a face inteira. O Graphics2D já está em coordenadas lógicas e com a imagem limpa. */
	abstract void render(Graphics2D g, PhoneOS os, float pixelScale);

	/** Índice do app cujo ícone está no ponto, ou -1. */
	abstract int iconAt(PhoneOS os, float x, float y);

	/** Centro do ícone do app i na tela inicial. */
	abstract float[] iconCenter(PhoneOS os, int i);

	/** Verdadeiro se o ponto está sobre um controle de "voltar/início" (botão físico ou na tela). */
	abstract boolean backAt(PhoneOS os, float x, float y);

	/** Verdadeiro se o ponto está sobre o botão físico de início na moldura do aparelho. */
	boolean physicalHomeAt(float x, float y) {
		return false;
	}

	/** Área da tela (sem a moldura): {x, y, largura, altura}. */
	abstract float[] screen();

	/** Área do conteúdo de um app totalmente aberto: {x, y, largura, altura}. */
	abstract float[] appBody();
}
