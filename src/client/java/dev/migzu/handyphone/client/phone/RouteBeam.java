package dev.migzu.handyphone.client.phone;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.migzu.handyphone.client.os.PhoneData;
import dev.migzu.handyphone.client.os.PhoneOS;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.world.phys.Vec3;

/**
 * O feixe de luz no mundo, sobre o destino da rota ("Ir até aqui"), como o de um waypoint: uma coluna azul clara
 * de ponta a ponta da altura do mundo, desenhada com a mesma rotina do feixe do sinalizador (beacon). Só a rota
 * ativa ganha feixe, nunca os locais salvos.
 *
 * <p>Como qualquer coisa do mundo, o feixe só aparece dentro da distância de renderização do jogo; mais longe,
 * vale o indicador de seta e distância do {@link RouteHud}. Lê o estado sem a trava do PhoneOS (ela fica presa
 * durante o desenho do celular).
 */
public final class RouteBeam {
	/** Azul claro, igual ao do mapa do celular. */
	private static final int COLOR = 0xFF8CD7FF;

	private RouteBeam() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(RouteBeam::collect);
	}

	private static void collect(LevelRenderContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		PhoneOS os = PhoneController.INSTANCE.os;
		PhoneData.HudView hud = os.hud();
		PhoneData.Fix fix = os.self().fix;
		if (!hud.hasRoute() || !hud.routeKnown() || fix == null || !hud.routeDimension().equals(fix.dimension())) {
			return;
		}
		Vec3 camera = context.levelState().cameraRenderState.pos;
		float time = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		PoseStack pose = context.poseStack();
		pose.pushPose();
		// As coordenadas do desenho são relativas à câmera; o feixe começa no fundo do mundo e vai até o topo.
		pose.translate(hud.routeX() - camera.x, -camera.y, hud.routeZ() - camera.z);
		BeaconRenderer.submitBeaconBeam(
			pose, context.submitNodeCollector(), BeaconRenderer.BEAM_LOCATION, 1.0F, time, mc.level.getMinY(), mc.level.getHeight(), COLOR, 0.32F, 0.45F
		);
		pose.popPose();
	}
}
