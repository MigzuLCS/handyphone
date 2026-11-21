package dev.migzu.handyphone.client.phone;

/**
 * Contadores de desempenho do celular, lidos pelo teste de desempenho ({@code ./gradlew runClientGameTest -Pperf}).
 * Cada campo é escrito por uma única thread (a que o nome indica) e lido pelas outras; o custo é uma soma e duas
 * leituras do relógio por evento, desprezível perto do que se mede.
 */
public final class PhoneStats {
	private PhoneStats() {
	}

	/** Thread HandyPhone-Display: despertares do laço, desenhos completos do PhoneOS e o tempo gasto neles. */
	public static volatile long displayWakeups, displayDraws, displayDrawNanos;
	/** Thread de render: subidas da textura da tela para a GPU e o tempo gasto nelas. */
	public static volatile long displayUploads, displayUploadNanos;
	/** Thread de render: quadros do visor da câmera capturados (redução na GPU) e o tempo na chamada e na leitura. */
	public static volatile long previewCaptures, previewCaptureNanos, previewReadbacks, previewReadbackNanos;
	/** Thread de render: tempo em {@code PhoneCameraSource.onWorldRendered} (mesmo quando nada é capturado). */
	public static volatile long hookCalls, hookNanos;
	/** Com shader: quadros em que as mãos saíram do pipeline do Iris para a câmera, e quantos deles foram desenhados depois da captura (têm de ser iguais). */
	public static volatile long handsDeferred, handsDrawnLate;
}
