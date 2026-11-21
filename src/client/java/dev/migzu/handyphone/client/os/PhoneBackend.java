package dev.migzu.handyphone.client.os;

import java.util.List;

/**
 * Pedidos que o celular faz ao servidor. O {@link PhoneOS} não conhece a rede do
 * jogo; quem implementa isto é a camada de rede do cliente. As respostas voltam
 * pelos métodos {@code apply...} do PhoneOS.
 */
public interface PhoneBackend {
	/**
	 * Pede o histórico de uma conversa e a marca como lida.
	 *
	 * @param have número da última mensagem que o celular já tem dela (0 = nenhuma): o servidor só manda o que vem depois
	 */
	void open(long conversation, int have);

	/** Abre (criando se preciso) a conversa individual com um número. */
	void openDirect(String number);

	void send(long conversation, String text);

	void markRead(long conversation, int seq);

	/** Salva ou renomeia um contato; nome vazio remove. */
	void setContact(String number, String name);

	void createGroup(String title, List<String> numbers);

	/** Bloqueia ({@code on}) ou desbloqueia um número: o servidor esconde tudo o que vier dele, sem avisá-lo. */
	void block(String number, boolean on);

	/** Sai de um grupo. */
	void leaveGroup(long conversation);

	/** Pede os bytes de uma foto (ou da miniatura dela); a resposta volta por {@link PhoneOS#applyPhoto}. */
	void requestPhoto(long id, boolean thumb);

	/**
	 * Envia uma foto já pronta (JPEG reduzido) para a galeria, em partes.
	 *
	 * @param fromComputer true se veio de um arquivo do computador (arrastado ou colado), false se da câmera
	 * @return false se não há servidor com o mod para receber
	 */
	boolean uploadPhoto(byte[] jpeg, boolean fromComputer);

	/** Apaga uma foto da galeria. */
	void deletePhoto(long id);

	/** Manda, em uma conversa, uma foto da galeria. */
	void sendPhoto(long conversation, long photo);

	/** Pede um tile do mapa; a resposta volta por {@link PhoneOS#applyTile} ou {@link PhoneOS#applyTileFailed}; {@code have} é o carimbo da versão que o celular já tem (0 = nenhuma). */
	void requestTile(int epoch, int zoom, int x, int z, long have);

	/** Manda a localização atual em uma conversa (o servidor carimba a posição). */
	void sendLocation(long conversation);

	/** Manda um ponto escolhido no mapa em uma conversa. */
	void sendPin(long conversation, int x, int z);

	/** Começa a compartilhar a localização ao vivo (índice do prazo; grupo ou lista de contatos). */
	void shareStart(int duration, long conversation, List<String> numbers);

	void shareStop();

	/** Avisa que o jogador está (ou deixou de estar) olhando posições ao vivo. */
	void watch(boolean on);

	void savePlace(String name, String dimension, int x, boolean hasY, int y, int z, int color);

	/** Troca o nome e a cor de um local salvo. */
	void editPlace(long id, String name, int color);

	/** Encaminha um local salvo para uma conversa (direta ou grupo). */
	void sendPlace(long conversation, long place);

	void deletePlace(long id);

	/** Liga para um número (o servidor decide tudo e responde com o estado da ligação). */
	void callStart(String number);

	/** Chama um grupo de mensagens (ou entra na chamada que já existe nele). */
	void callGroup(long conversation);

	/** Atende a ligação que está tocando. */
	void callAnswer(long id);

	/** Recusa, cancela ou desliga, conforme o estado da ligação. */
	void callDecline(long id);

	/** Liga ou desliga o mudo (o servidor é quem para de repassar a voz). */
	void callMute(long id, boolean on);

	/** Usado quando não há servidor com o mod: nenhum pedido faz nada. */
	PhoneBackend OFFLINE = new PhoneBackend() {
		@Override
		public void open(long conversation, int have) {
		}

		@Override
		public void openDirect(String number) {
		}

		@Override
		public void send(long conversation, String text) {
		}

		@Override
		public void markRead(long conversation, int seq) {
		}

		@Override
		public void setContact(String number, String name) {
		}

		@Override
		public void createGroup(String title, List<String> numbers) {
		}

		@Override
		public void block(String number, boolean on) {
		}

		@Override
		public void leaveGroup(long conversation) {
		}

		@Override
		public void requestPhoto(long id, boolean thumb) {
		}

		@Override
		public boolean uploadPhoto(byte[] jpeg, boolean fromComputer) {
			return false;
		}

		@Override
		public void deletePhoto(long id) {
		}

		@Override
		public void sendPhoto(long conversation, long photo) {
		}

		@Override
		public void requestTile(int epoch, int zoom, int x, int z, long have) {
		}

		@Override
		public void sendLocation(long conversation) {
		}

		@Override
		public void sendPin(long conversation, int x, int z) {
		}

		@Override
		public void shareStart(int duration, long conversation, List<String> numbers) {
		}

		@Override
		public void shareStop() {
		}

		@Override
		public void watch(boolean on) {
		}

		@Override
		public void savePlace(String name, String dimension, int x, boolean hasY, int y, int z, int color) {
		}

		@Override
		public void editPlace(long id, String name, int color) {
		}

		@Override
		public void sendPlace(long conversation, long place) {
		}

		@Override
		public void deletePlace(long id) {
		}

		@Override
		public void callStart(String number) {
		}

		@Override
		public void callGroup(long conversation) {
		}

		@Override
		public void callAnswer(long id) {
		}

		@Override
		public void callDecline(long id) {
		}

		@Override
		public void callMute(long id, boolean on) {
		}
	};
}
