package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneCamera;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * A câmera do celular dentro do jogo. Captura o alvo de renderização principal em um
 * ponto exato do quadro: depois de desenhado o mundo e <b>antes</b> da mão (com o
 * celular e o braço) e da interface. Isso é feito por um mixin em
 * {@code GameRenderer.render3dHud}, que chama {@link #onWorldRendered}.
 *
 * <p>A cópia para a memória entra na fila de comandos da placa de vídeo no ponto da
 * chamada, então ela enxerga o quadro naquele instante; o resultado chega por um
 * retorno de chamada em quadros seguintes (sem travar a renderização).
 *
 * <p><b>Visor ao vivo:</b> o quadro inteiro é reduzido na própria placa de vídeo para uma
 * textura pequena (o mesmo caminho que o jogo usa para o Tracy), e só essa miniatura
 * é lida pela CPU, a poucos quadros por segundo e só enquanto o app Câmera está aberto.
 * Fora disso a câmera não custa nada. <b>Disparo:</b> uma única cópia do quadro inteiro.
 *
 * <p>Fora da foto, de propósito: o contorno de entidades brilhando, os efeitos de pós-processamento
 * de fim de quadro e os efeitos de tela (fogo, água), que o jogo desenha depois deste ponto.
 */
public final class PhoneCameraSource implements PhoneCamera {
	public static final PhoneCameraSource INSTANCE = new PhoneCameraSource();

	/** Intervalo entre quadros do visor (cerca de 6 por segundo). */
	private static final long PREVIEW_INTERVAL_NANOS = 160_000_000L;
	/** Tamanho máximo da textura reduzida do visor. */
	private static final int PREVIEW_MAX_W = 320, PREVIEW_MAX_H = 240;
	/** Quanto esperar por uma captura antes de desistir. */
	private static final long SHOT_TIMEOUT_NANOS = 3_000_000_000L;

	private volatile boolean previewActive;
	private volatile BufferedImage preview;
	private long lastPreviewNanos;
	private boolean previewInFlight, shotInFlight;

	private Consumer<BufferedImage> shotCallback;
	private long shotRequestedNanos;

	// Com shader (Iris), a mão é desenhada DENTRO do pipeline do mundo, antes da composição final, e portanto antes do ponto
	// de captura: o visor e a foto mostrariam o próprio celular. Enquanto a câmera precisa de quadros limpos (visor aberto ou
	// disparo esperando), as mãos saem do pipeline do Iris (shouldDeferHands) e são desenhadas depois da captura, pelo
	// caminho do jogo puro (deferredHandsPass, no GameRendererMixin): o celular continua na tela em todos os quadros, sem
	// piscar, só que sem o sombreamento do shader enquanto o app Câmera está aberto. Sem shader nada disso entra em ação.
	/** Quadros seguidos sem a mão adiada depois dos quais se captura assim mesmo (o Iris nem desenha a mão: F1, terceira pessoa). */
	private static final int MAX_WAIT_FRAMES = 3;
	/** As mãos deste quadro foram tiradas do pipeline do Iris e esperam o caminho do jogo puro. */
	private boolean handsDeferredThisFrame;
	/** Estamos dentro do desenho das mãos adiadas: o mixin das mãos não pode adiá-las de novo. */
	private boolean inDeferredPass;
	private int framesWithoutDeferral;
	/** Só os testes desligam: mostra o comportamento de antes da correção (o celular aparecendo no visor com shader). */
	private volatile boolean deferHandsEnabled = true;
	private static Object irisApi;
	private static java.lang.reflect.Method irisShaderPackInUse;
	private static boolean irisChecked;

	// Recursos de GPU do visor: só mexidos na thread de renderização.
	private GpuTexture smallTexture;
	private GpuTextureView smallView;
	private GpuBuffer smallBuffer;
	private int smallW, smallH, sourceW, sourceH;

	private PhoneCameraSource() {
	}

	@Override
	public BufferedImage preview() {
		return preview;
	}

	@Override
	public void setPreviewActive(boolean active) {
		previewActive = active;
		if (!active) {
			preview = null;
		}
	}

	@Override
	public synchronized void shoot(Consumer<BufferedImage> result) {
		if (shotCallback != null) {
			result.accept(null);
			return;
		}
		shotCallback = result;
		shotRequestedNanos = System.nanoTime();
	}

	/** Chamado a cada tick do cliente: desiste de um disparo que não foi atendido (por exemplo, mundo sem renderizar). */
	public void tick() {
		Consumer<BufferedImage> expired = null;
		synchronized (this) {
			if (shotCallback != null && System.nanoTime() - shotRequestedNanos > SHOT_TIMEOUT_NANOS) {
				expired = shotCallback;
				shotCallback = null;
			}
		}
		if (expired != null) {
			expired.accept(null);
		}
	}

	/** Só para os testes: liga e desliga o adiamento das mãos com shader (desligado, o visor mostra o celular, como antes da correção). */
	public void setDeferHandsEnabled(boolean enabled) {
		deferHandsEnabled = enabled;
	}

	/** Início de cada quadro (thread de renderização): o que foi adiado no quadro anterior não vale mais. */
	public void beginFrame() {
		handsDeferredThisFrame = false;
	}

	/**
	 * Chamado pelo mixin antes de o pipeline do Iris desenhar as mãos em primeira pessoa (thread de renderização). Com shader e
	 * a câmera precisando de um quadro limpo (visor aberto ou disparo esperando), devolve true: o Iris não desenha as mãos e
	 * elas voltam depois da captura, em {@link #takeDeferredHands}.
	 */
	public boolean shouldDeferHands() {
		if (inDeferredPass || !deferHandsEnabled) {
			return false;
		}
		synchronized (this) {
			if (!previewActive && (shotCallback == null || shotInFlight)) {
				return false;
			}
		}
		if (!shaderPackInUse()) {
			return false;
		}
		if (!handsDeferredThisFrame) {
			// O Iris chama duas vezes por quadro (parte sólida e parte translúcida); conta-se o quadro.
			PhoneStats.handsDeferred++;
		}
		handsDeferredThisFrame = true;
		return true;
	}

	/** O caminho do jogo puro (depois da captura) deve desenhar agora as mãos adiadas deste quadro? Vale uma vez por quadro. */
	public boolean takeDeferredHands() {
		boolean deferred = handsDeferredThisFrame;
		handsDeferredThisFrame = false;
		if (deferred) {
			PhoneStats.handsDrawnLate++;
		}
		return deferred;
	}

	/**
	 * Selfie com shader: a câmera do jogo fica solta e o Iris não desenha a mão em primeira pessoa nesse caso; o celular (com o visor)
	 * sumiria da tela. Então o caminho do jogo puro o desenha, como as mãos adiadas (sem o sombreamento do shader, como no resto da Câmera).
	 */
	public boolean selfieHandsWithShader() {
		return PhoneLens.selfie() && shaderPackInUse();
	}

	/** O mixin marca o desenho das mãos adiadas, para {@link #shouldDeferHands} não as adiar de novo. */
	public void deferredHandsPass(boolean on) {
		inDeferredPass = on;
	}

	/** Se há um pacote de shaders do Iris ligado. Lido por reflexão, já que o Iris é opcional. */
	private static boolean shaderPackInUse() {
		if (!irisChecked) {
			irisChecked = true;
			try {
				Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
				irisApi = api.getMethod("getInstance").invoke(null);
				irisShaderPackInUse = api.getMethod("isShaderPackInUse");
			} catch (ReflectiveOperationException | LinkageError e) {
				irisApi = null;
			}
		}
		if (irisApi == null) {
			return false;
		}
		try {
			return (Boolean) irisShaderPackInUse.invoke(irisApi);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/**
	 * Chamado pelo mixin, na thread de renderização, com o mundo já desenhado no alvo
	 * principal e a mão ainda não (sem shader; com shader, ver {@link #shouldDeferHands}).
	 */
	public void onWorldRendered(RenderTarget target) {
		long hookStart = System.nanoTime();
		try {
			onWorldRenderedImpl(target);
		} finally {
			PhoneStats.hookNanos += System.nanoTime() - hookStart;
			PhoneStats.hookCalls++;
		}
	}

	private void onWorldRenderedImpl(RenderTarget target) {
		if (!previewActive && smallTexture != null) {
			releasePreviewResources();
		}
		GpuTexture texture = target.getColorTexture();
		Consumer<BufferedImage> shot = null;
		boolean wantPreview = false;
		synchronized (this) {
			long now = System.nanoTime();
			boolean wantsFrame = !shotInFlight && (shotCallback != null || previewActive && !previewInFlight && now - lastPreviewNanos >= PREVIEW_INTERVAL_NANOS);
			// Com shader, só captura um quadro em que as mãos foram mesmo tiradas do pipeline (ou, se o Iris nem desenha a mão, depois de
			// alguns quadros de espera): senão o celular apareceria no visor e na foto.
			boolean handsOut = wantsFrame && !handsDeferredThisFrame && framesWithoutDeferral < MAX_WAIT_FRAMES && shaderPackInUse();
			if (handsOut) {
				framesWithoutDeferral++;
			} else if (shotCallback != null && !shotInFlight) {
				framesWithoutDeferral = 0;
				shot = shotCallback;
				shotCallback = null;
				shotInFlight = true;
			} else if (previewActive && !previewInFlight && now - lastPreviewNanos >= PREVIEW_INTERVAL_NANOS) {
				framesWithoutDeferral = 0;
				wantPreview = true;
				previewInFlight = true;
				lastPreviewNanos = now;
			}
		}
		if (shot != null) {
			if (texture == null || target.width < 16 || target.height < 16) {
				finishShot(shot, null);
			} else {
				try {
					captureFull(texture, target.width, target.height, shot);
				} catch (RuntimeException e) {
					HandyPhone.LOGGER.warn("Couldn't capture the camera photo", e);
					finishShot(shot, null);
				}
			}
		}
		if (wantPreview) {
			if (texture == null || target.width < 16 || target.height < 16) {
				previewInFlight = false;
				return;
			}
			try {
				long t0 = System.nanoTime();
				capturePreview(target);
				PhoneStats.previewCaptureNanos += System.nanoTime() - t0;
				PhoneStats.previewCaptures++;
			} catch (RuntimeException e) {
				HandyPhone.LOGGER.warn("Couldn't capture the camera viewfinder", e);
				previewInFlight = false;
			}
		}
	}

	// ------------------------------------------------------------------ foto: cópia do quadro inteiro

	private void captureFull(GpuTexture texture, int width, int height, Consumer<BufferedImage> shot) {
		int blockSize = texture.getFormat().blockSize();
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "HandyPhone camera", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * blockSize);
		try {
			RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
			BufferedImage image = null;
			try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
				image = convert(view.data(), width, height, blockSize, width, height, true);
			} catch (RuntimeException e) {
				HandyPhone.LOGGER.warn("Couldn't read the camera photo", e);
			} finally {
				buffer.close();
			}
			finishShot(shot, image);
		}, 0);
		} catch (RuntimeException e) {
			buffer.close();
			throw e;
		}
	}

	private void finishShot(Consumer<BufferedImage> shot, BufferedImage image) {
		synchronized (this) {
			shotInFlight = false;
		}
		// Quem recebe repassa o trabalho pesado (JPEG) para outra thread; aqui ainda é a de renderização.
		shot.accept(image);
	}

	// ------------------------------------------------------------------ visor: redução na GPU

	private void capturePreview(RenderTarget target) {
		ensurePreviewResources(target.width, target.height);
		GpuDevice device = RenderSystem.getDevice();
		try (RenderPass pass = device.createCommandEncoder().createRenderPass(() -> "HandyPhone preview blit", smallView, Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			pass.setUniform("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		int w = smallW, h = smallH;
		GpuBuffer buffer = smallBuffer;
		device.createCommandEncoder().copyTextureToBuffer(smallTexture, buffer, 0L, () -> {
			long t0 = System.nanoTime();
			try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
				BufferedImage image = convert(view.data(), w, h, 4, w, h, false);
				if (previewActive) {
					preview = image;
				}
				PhoneStats.previewReadbackNanos += System.nanoTime() - t0;
				PhoneStats.previewReadbacks++;
			} catch (RuntimeException e) {
				HandyPhone.LOGGER.warn("Couldn't read the camera viewfinder", e);
			} finally {
				synchronized (this) {
					previewInFlight = false;
				}
			}
		}, 0);
	}

	private void ensurePreviewResources(int width, int height) {
		if (smallTexture != null && sourceW == width && sourceH == height) {
			return;
		}
		// Nenhuma leitura pode estar pendente aqui (o visor só captura com previewInFlight falso até este ponto).
		closePreviewResources();
		float aspect = (float) width / height;
		int w = PREVIEW_MAX_W, h = Math.round(w / aspect);
		if (h > PREVIEW_MAX_H) {
			h = PREVIEW_MAX_H;
			w = Math.round(h * aspect);
		}
		smallW = Math.max(4, w / 4 * 4);
		smallH = Math.max(4, h / 4 * 4);
		sourceW = width;
		sourceH = height;
		GpuDevice device = RenderSystem.getDevice();
		smallTexture = device.createTexture("HandyPhone preview", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, smallW, smallH, 1, 1);
		smallView = device.createTextureView(smallTexture);
		smallBuffer = device.createBuffer(() -> "HandyPhone preview buffer", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) smallW * smallH * 4);
	}

	private void releasePreviewResources() {
		if (previewInFlight) {
			// Uma leitura ainda vai usar o buffer; os recursos são soltos no próximo quadro.
			return;
		}
		closePreviewResources();
	}

	private void closePreviewResources() {
		if (smallView != null) {
			smallView.close();
			smallTexture.close();
			smallBuffer.close();
		}
		smallView = null;
		smallTexture = null;
		smallBuffer = null;
		sourceW = sourceH = 0;
	}

	// ------------------------------------------------------------------ conversão

	/**
	 * Converte o quadro bruto (a leitura da placa vem de cabeça para baixo e em ABGR,
	 * como no {@code Screenshot} do jogo) para uma imagem RGB cortada em 4:3 no centro.
	 */
	private static BufferedImage convert(ByteBuffer data, int width, int height, int blockSize, int rowWidth, int rowCount, boolean full) {
		int cropW = width, cropH = height;
		if ((long) width * PhoneCamera.ASPECT_H > (long) height * PhoneCamera.ASPECT_W) {
			cropW = height * PhoneCamera.ASPECT_W / PhoneCamera.ASPECT_H;
		} else {
			cropH = width * PhoneCamera.ASPECT_H / PhoneCamera.ASPECT_W;
		}
		int x0 = (width - cropW) / 2, y0 = (height - cropH) / 2;
		BufferedImage out = new BufferedImage(cropW, cropH, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
		for (int oy = 0; oy < cropH; oy++) {
			// A linha 0 da leitura é a de baixo da imagem.
			int srcY = rowCount - 1 - (y0 + oy);
			int rowStart = srcY * rowWidth;
			int dst = oy * cropW;
			for (int ox = 0; ox < cropW; ox++) {
				int abgr = data.getInt((rowStart + x0 + ox) * blockSize);
				pixels[dst + ox] = (abgr & 0x0000FF00) | ((abgr >> 16) & 0xFF) | ((abgr & 0xFF) << 16);
			}
		}
		return out;
	}
}
