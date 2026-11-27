package dev.migzu.handyphone.client.phone;

/**
 * O que o estado de render de um jogador ({@code AvatarRenderState}) sabe do celular dele: a pose (ver {@link PhoneWorldModel}) e o tema
 * (índice em {@code PhoneOS.THEME_IDS}). Preenchido na extração do estado, lido pelo modelo (braço) e pela camada do item na mão.
 */
public interface PhoneHolder {
	int handyphone$phonePose();

	int handyphone$phoneTheme();

	void handyphone$setPhone(int pose, int theme);
}
