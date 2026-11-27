package dev.migzu.handyphone.client.mixin;

import dev.migzu.handyphone.client.phone.PhoneHolder;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Guarda no estado de render do jogador a pose e o tema do celular dele (ver PhoneWorldModel). */
@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements PhoneHolder {
	@Unique
	private int handyphone$pose;
	@Unique
	private int handyphone$theme;

	@Override
	public int handyphone$phonePose() {
		return handyphone$pose;
	}

	@Override
	public int handyphone$phoneTheme() {
		return handyphone$theme;
	}

	@Override
	public void handyphone$setPhone(int pose, int theme) {
		handyphone$pose = pose;
		handyphone$theme = theme;
	}
}
