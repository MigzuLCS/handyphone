package dev.migzu.handyphone.client.os;

/**
 * Ponte para a tecla que puxa o celular, que é do jogo (Controles, {@code options.txt}): o app Ajustes mostra e troca a tecla
 * por aqui, sem conhecer o Minecraft. O nome da tecla atual chega por {@link PhoneOS#setUseKeyName}.
 */
public interface ShortcutKeys {
	/** Sem jogo (testes): nada para trocar. */
	ShortcutKeys NONE = new ShortcutKeys() {
		@Override
		public String defaultName() {
			return "Z";
		}

		@Override
		public boolean isDefault() {
			return true;
		}

		@Override
		public void startCapture() {
		}

		@Override
		public boolean capturing() {
			return false;
		}

		@Override
		public void cancelCapture() {
		}

		@Override
		public void reset() {
		}

		@Override
		public String conflict() {
			return "";
		}
	};

	/** Nome da tecla padrão. */
	String defaultName();

	boolean isDefault();

	/** A próxima tecla ou botão do mouse (menos esquerdo e direito, que são da tela) vira a tecla do celular; Esc cancela. */
	void startCapture();

	boolean capturing();

	void cancelCapture();

	/** Volta à tecla padrão. */
	void reset();

	/** Nome de outro controle que usa a mesma tecla, ou vazio. */
	String conflict();
}
