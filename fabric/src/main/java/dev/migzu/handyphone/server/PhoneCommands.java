package dev.migzu.handyphone.server;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.migzu.handyphone.HandyPhone;
import dev.migzu.handyphone.fabric.FabricText;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Collection;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;

/**
 * Comandos de administração do celular, só para quem tem permissão de administrador no servidor (nível 3, o mesmo
 * de {@code /kick} e {@code /ban}):
 *
 * <ul>
 *   <li>{@code /handyphone mute <player> [minutes]}: o jogador não envia mais nada pelo celular (sem prazo se não
 *       disser os minutos);</li>
 *   <li>{@code /handyphone unmute <player>};</li>
 *   <li>{@code /handyphone wipe <player>}: apaga as mensagens, os grupos criados, as fotos e os locais dele, depois de
 *       arquivar tudo em {@code <mundo>/handyphone/moderacao/};</li>
 *   <li>{@code /handyphone info <player>}: um resumo do que a conta tem.</li>
 * </ul>
 *
 * O jogador pode ser um nome ou um seletor; se não estiver ligado, vale o nome que o servidor já conhece.
 */
public final class PhoneCommands {
	private PhoneCommands() {
	}

	/** Quem pode usar: administradores do servidor. */
	static boolean allowed(CommandSourceStack source) {
		return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("handyphone")
				.requires(PhoneCommands::allowed)
				.then(Commands.literal("mute")
					.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> mute(ctx, 0))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 5 * 365 * 24 * 60))
							.executes(ctx -> mute(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.literal("unmute")
					.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> run(ctx, PhoneService::adminUnmute))))
				.then(Commands.literal("wipe")
					.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> run(ctx, PhoneService::adminWipe))))
				.then(Commands.literal("info")
					.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> run(ctx, PhoneService::adminInfo))))
		));
	}

	private static int mute(CommandContext<CommandSourceStack> ctx, int minutes) throws CommandSyntaxException {
		return run(ctx, target -> PhoneService.adminMute(target, minutes));
	}

	private interface Action {
		PhoneService.AdminResult apply(java.util.UUID target);
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Action action) throws CommandSyntaxException {
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
		if (profiles.size() != 1) {
			ctx.getSource().sendFailure(FabricText.component(new Msg("handyphone.command.one_player")));
			return 0;
		}
		NameAndId who = profiles.iterator().next();
		PhoneService.AdminResult result = action.apply(who.id());
		Component text = FabricText.component(new Msg("handyphone.command.result", HandyPhone.DISPLAY_NAME, who.name(), result.msg()));
		if (result.ok()) {
			// Avisa os outros administradores também: moderação precisa deixar rastro.
			ctx.getSource().sendSuccess(() -> text, true);
			return 1;
		}
		ctx.getSource().sendFailure(text);
		return 0;
	}
}
