package dev.migzu.handyphone.client.os;

/**
 * Relógio do celular. Normalmente é o relógio do sistema; só no modo de gravação das cenas da página
 * ({@code -Dhandyphone.showcase=true}, usado pelo ShowcaseTest) ele passa a ser virtual: só anda quando o teste manda
 * ({@link #advance}), um passo por captura, para a animação do vídeo sair no ritmo certo mesmo que cada captura demore
 * muito mais que 50 ms de verdade. Para o jogador (propriedade desligada) nada muda.
 */
public final class PhoneClock {
	/** Modo de gravação ligado? Decidido uma vez, ao carregar a classe. */
	public static final boolean VIRTUAL = Boolean.getBoolean("handyphone.showcase");
	private static final long BASE_MILLIS = System.currentTimeMillis();
	private static double virtualSeconds;

	private PhoneClock() {
	}

	/** Milissegundos "agora" (relógio do sistema, ou o virtual na gravação). */
	public static long millis() {
		return VIRTUAL ? BASE_MILLIS + (long) (seconds() * 1000.0) : System.currentTimeMillis();
	}

	/** Segundos virtuais desde o começo da gravação (sempre 0 fora dela). */
	public static synchronized double seconds() {
		return virtualSeconds;
	}

	/** Anda o relógio virtual (só a gravação chama). */
	public static synchronized void advance(double seconds) {
		if (VIRTUAL) {
			virtualSeconds += seconds;
		}
	}
}
