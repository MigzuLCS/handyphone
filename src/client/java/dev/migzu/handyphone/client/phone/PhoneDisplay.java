package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.client.os.PhoneClock;
import dev.migzu.handyphone.client.os.PhoneOS;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * A "tela" do celular como textura do jogo. O sistema ({@link PhoneOS}) é desenhado
 * em uma thread separada para não derrubar o FPS; a thread do jogo só copia o
 * último quadro pronto para a placa de vídeo.
 */
public final class PhoneDisplay {
	public static final Identifier SCREEN_TEXTURE = HandyPhone.id("dynamic/phone_screen");
	public static final Identifier BODY_TEXTURE = HandyPhone.id("dynamic/phone_body");
	public static final Identifier BACK_TEXTURE = HandyPhone.id("dynamic/phone_back");
	private static final float PIXEL_SCALE = 2.5f;
	public static final int WIDTH = Math.round(PhoneOS.FACE_W * PIXEL_SCALE);
	public static final int HEIGHT = Math.round(PhoneOS.FACE_H * PIXEL_SCALE);

	private final PhoneOS os;
	private LinearTexture screen;
	private LinearTexture body;
	private LinearTexture back;

	private final Object frameLock = new Object();
	private int[] readyFrame;
	private int[] spareFrame = new int[WIDTH * HEIGHT];
	private volatile long lastRequestNanos;
	private Thread worker;

	public PhoneDisplay(PhoneOS os) {
		this.os = os;
	}

	/** Chamado na thread de render a cada quadro em que o celular aparece. */
	public void prepare() {
		lastRequestNanos = System.nanoTime();
		if (screen == null) {
			createTextures();
		}
		if (PhoneClock.VIRTUAL) {
			prepareVirtual();
			return;
		}
		if (worker == null || !worker.isAlive()) {
			worker = new Thread(this::drawLoop, "HandyPhone-Display");
			worker.setDaemon(true);
			worker.start();
		}
		int[] frame;
		synchronized (frameLock) {
			frame = readyFrame;
			readyFrame = null;
		}
		if (frame != null) {
			long t0 = System.nanoTime();
			upload(screen, frame);
			PhoneStats.displayUploadNanos += System.nanoTime() - t0;
			PhoneStats.displayUploads++;
			synchronized (frameLock) {
				spareFrame = frame;
			}
		}
	}

	// Modo de gravação das cenas (PhoneClock.VIRTUAL): sem thread própria; a tela é desenhada aqui mesmo, na thread de render, com o tempo
	// virtual, para que cada captura mostre exatamente o passo de tempo que o teste mandou (a thread solta andaria com o relógio de verdade).
	private BufferedImage virtualImage;
	private double lastVirtualSeconds = -1;
	private boolean virtualFirst = true;

	private void prepareVirtual() {
		if (virtualImage == null) {
			virtualImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		}
		double now = PhoneClock.seconds();
		double dt = lastVirtualSeconds < 0 ? 0 : now - lastVirtualSeconds;
		lastVirtualSeconds = now;
		synchronized (os) {
			os.step(dt);
			if (os.consumeDirty() || virtualFirst) {
				virtualFirst = false;
				Graphics2D g = virtualImage.createGraphics();
				g.scale(PIXEL_SCALE, PIXEL_SCALE);
				os.render(g, PIXEL_SCALE);
				g.dispose();
				int[] src = ((DataBufferInt) virtualImage.getRaster().getDataBuffer()).getData();
				int[] out = new int[WIDTH * HEIGHT];
				toAbgr(src, out);
				upload(screen, out);
			}
		}
	}

	private void createTextures() {
		screen = new LinearTexture("handyphone screen", WIDTH, HEIGHT);
		body = new LinearTexture("handyphone body", WIDTH, HEIGHT);
		Minecraft.getInstance().getTextureManager().register(SCREEN_TEXTURE, screen);
		Minecraft.getInstance().getTextureManager().register(BODY_TEXTURE, body);
		back = new LinearTexture("handyphone back", WIDTH, HEIGHT);
		Minecraft.getInstance().getTextureManager().register(BACK_TEXTURE, back);

		// Textura toda branca: a malha do corpo usa só a cor dos vértices.
		int[] white = new int[WIDTH * HEIGHT];
		Arrays.fill(white, 0xFFFFFFFF);
		upload(body, white);

		BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.scale(PIXEL_SCALE, PIXEL_SCALE);
		drawBack(g);
		g.dispose();
		int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		toAbgr(px, px);
		upload(back, px);
	}

	/** Logo da traseira em pixels: um bloco de grama mordido, em tons de cinza. G/g = grama, D/d = terra. */
	private static final String[] LOGO = {
		".....GG...",
		"....GG....",
		"GGGGGGGG..",
		"GgGGgGGG..",
		"gDgGDgG...",
		"DDDdDDD...",
		"DdDDDDDD..",
		"DDDDdDDDDD",
		"DDdDDDDdDD",
		"dDDDDdDDDD",
		"DDDdDDDDDD",
	};

	/** Traseira do aparelho: cor lisa do corpo e o logo. As câmeras são geometria (ver PhoneMesh). */
	private static void drawBack(Graphics2D g) {
		g.setColor(new Color(0x4B4F5C));
		g.fill(new RoundRectangle2D.Float(0, 0, PhoneOS.FACE_W, PhoneOS.FACE_H, PhoneOS.FACE_R * 2, PhoneOS.FACE_R * 2));

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		int cell = 5;
		int left = PhoneOS.FACE_W / 2 - LOGO[0].length() * cell / 2, top = PhoneOS.FACE_H / 2 - LOGO.length * cell / 2;
		for (int row = 0; row < LOGO.length; row++) {
			for (int col = 0; col < LOGO[row].length(); col++) {
				int color = switch (LOGO[row].charAt(col)) {
					case 'G' -> 0xB4B8C4;
					case 'g' -> 0x9A9EAB;
					case 'D' -> 0x7C808D;
					case 'd' -> 0x6A6E7B;
					default -> -1;
				};
				if (color >= 0) {
					g.setColor(new Color(color));
					g.fillRect(left + col * cell, top + row * cell, cell, cell);
				}
			}
		}
	}

	private void drawLoop() {
		BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		int[] src = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		long last = System.nanoTime();
		boolean first = true;
		try {
			while (System.nanoTime() - lastRequestNanos < 3_000_000_000L) {
				long start = System.nanoTime();
				PhoneStats.displayWakeups++;
				double dt = (start - last) / 1e9;
				last = start;
				boolean drew = false;
				synchronized (os) {
					os.step(dt);
					if (os.consumeDirty() || first) {
						Graphics2D g = img.createGraphics();
						g.scale(PIXEL_SCALE, PIXEL_SCALE);
						os.render(g, PIXEL_SCALE);
						g.dispose();
						PhoneStats.displayDrawNanos += System.nanoTime() - start;
						PhoneStats.displayDraws++;
						drew = true;
						first = false;
					}
				}
				if (drew) {
					int[] out;
					synchronized (frameLock) {
						out = spareFrame != null ? spareFrame : new int[WIDTH * HEIGHT];
						spareFrame = null;
					}
					toAbgr(src, out);
					synchronized (frameLock) {
						if (readyFrame != null) {
							spareFrame = readyFrame;
						}
						readyFrame = out;
					}
				}
				long sleepMs = 16 - (System.nanoTime() - start) / 1_000_000L;
				Thread.sleep(Math.max(1, sleepMs));
			}
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		} catch (Throwable t) {
			HandyPhone.LOGGER.error("Failed to draw the phone screen", t);
		}
	}

	private static void toAbgr(int[] argb, int[] out) {
		for (int i = 0; i < argb.length; i++) {
			int c = argb[i];
			out[i] = (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
		}
	}

	private static void upload(DynamicTexture texture, int[] abgr) {
		NativeImage pixels = texture.getPixels();
		MemoryUtil.memIntBuffer(pixels.getPointer(), abgr.length).put(abgr, 0, abgr.length);
		texture.upload();
	}

	private static final class LinearTexture extends DynamicTexture {
		LinearTexture(String label, int width, int height) {
			super(label, width, height, true);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}
}
