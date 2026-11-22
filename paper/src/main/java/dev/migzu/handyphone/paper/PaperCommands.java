package dev.migzu.handyphone.paper;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.migzu.handyphone.HandyPhoneCore;
import dev.migzu.handyphone.server.Msg;
import dev.migzu.handyphone.server.PhoneService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /handyphone mute|unmute|wipe|info <player> [minutes]}, como no Fabric, para quem tem a permissão {@code handyphone.admin}
 * (padrão: operadores). O jogador é um nome; se não estiver ligado, vale o nome que o servidor já conhece (cache de perfis).
 */
final class PaperCommands {
	static final String PERMISSION = "handyphone.admin";

	private PaperCommands() {
	}

	/**
	 * Quem pode usar: quem tem a permissão, mas só jogador ou console. No Fabric é preciso nível 3; aqui a permissão padrão ("op") vale para
	 * todo operador, então blocos de comando e outros remetentes automáticos ficam de fora (um bloco de comando não pode apagar contas).
	 */
	private static boolean allowed(CommandSender sender) {
		return (sender instanceof Player || sender instanceof org.bukkit.command.ConsoleCommandSender) && sender.hasPermission(PERMISSION);
	}

	static void register(HandyPhonePlugin plugin) {
		plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
			Commands commands = event.registrar();
			LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("handyphone")
				.requires(source -> allowed(source.getSender()))
				.then(Commands.literal("mute")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests((ctx, builder) -> suggestPlayers(builder))
						.executes(ctx -> mute(ctx, 0))
						.then(Commands.argument("minutes", IntegerArgumentType.integer(1, 5 * 365 * 24 * 60))
							.executes(ctx -> mute(ctx, IntegerArgumentType.getInteger(ctx, "minutes"))))))
				.then(Commands.literal("unmute")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests((ctx, builder) -> suggestPlayers(builder))
						.executes(ctx -> run(ctx, PhoneService::adminUnmute))))
				.then(Commands.literal("wipe")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests((ctx, builder) -> suggestPlayers(builder))
						.executes(ctx -> run(ctx, PhoneService::adminWipe))))
				.then(Commands.literal("info")
					.then(Commands.argument("player", StringArgumentType.word())
						.suggests((ctx, builder) -> suggestPlayers(builder))
						.executes(ctx -> run(ctx, PhoneService::adminInfo))));
			commands.register(root.build(), "HandyPhone moderation: mute, unmute, wipe and info on a player's phone");
		});
	}

	private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
		String typed = builder.getRemainingLowerCase();
		for (Player player : Bukkit.getOnlinePlayers()) {
			if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
				builder.suggest(player.getName());
			}
		}
		return builder.buildFuture();
	}

	private static int mute(CommandContext<CommandSourceStack> ctx, int minutes) {
		return run(ctx, target -> PhoneService.adminMute(target, minutes));
	}

	private interface Action {
		PhoneService.AdminResult apply(UUID target);
	}

	/** O jogador pelo nome: quem está ligado, senão o perfil que o servidor já conhece (sem consulta de rede). */
	private static OfflinePlayer find(String name) {
		Player online = Bukkit.getPlayerExact(name);
		return online != null ? online : Bukkit.getOfflinePlayerIfCached(name);
	}

	private static int run(CommandContext<CommandSourceStack> ctx, Action action) {
		CommandSender sender = ctx.getSource().getSender();
		String name = StringArgumentType.getString(ctx, "player");
		OfflinePlayer who = find(name);
		if (who == null) {
			sender.sendMessage(PaperText.component(new Msg("handyphone.command.one_player")).color(NamedTextColor.RED));
			return 0;
		}
		String shown = who.getName() != null ? who.getName() : name;
		PhoneService.AdminResult result = action.apply(who.getUniqueId());
		Component text = PaperText.component(new Msg("handyphone.command.result", HandyPhoneCore.DISPLAY_NAME, shown, result.msg()));
		if (result.ok()) {
			sender.sendMessage(text);
			// Avisa os outros administradores também (como o Fabric): moderação precisa deixar rastro.
			Component echo = Component.text("[").append(sender.name()).append(Component.text(": ")).append(text).append(Component.text("]"))
				.color(NamedTextColor.GRAY).decorate(TextDecoration.ITALIC);
			for (Player admin : Bukkit.getOnlinePlayers()) {
				if (admin != sender && admin.hasPermission(PERMISSION)) {
					admin.sendMessage(echo);
				}
			}
			if (sender != Bukkit.getConsoleSender()) {
				Bukkit.getConsoleSender().sendMessage(echo);
			}
			return 1;
		}
		sender.sendMessage(text.color(NamedTextColor.RED));
		return 0;
	}
}
