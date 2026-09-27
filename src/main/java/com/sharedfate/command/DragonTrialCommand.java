package com.sharedfate.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.sharedfate.SharedFateMod;
import com.sharedfate.sync.DragonTrialManager;
import com.sharedfate.sync.DragonTrialSession;
import com.sharedfate.sync.TrialCatalog;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 엔드 전투를 시험하는 운영자 명령.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>드래곤 전투를 손으로 확인하려면 회차를 새로 열고 요새를 찾아 엔드까지 가야 한다. 한 번
 * 고칠 때마다 그것을 반복할 수는 없다. 이 명령은 <b>엔드에 선 상태에서 전투를 지금 열고</b>,
 * 시련 타이머를 앞당기고, 지금 상태를 들여다본다.
 *
 * <h2>실제로 노는 서버에서는 켜지 마십시오</h2>
 *
 * <p>{@code perkTestCommands} 와 같은 잠금을 쓴다. 설정이 꺼져 있으면 명령이 아예 없다.
 */
public final class DragonTrialCommand {
	private DragonTrialCommand() {
	}

	public static boolean enabled() {
		return SharedFateMod.config != null && SharedFateMod.config.perkTestCommands;
	}

	/** {@code shareteam} 트리에 붙일 {@code trialtest} 가지. */
	public static LiteralArgumentBuilder<CommandSourceStack> node() {
		return Commands.literal("trialtest")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
				.executes(DragonTrialCommand::status)
				.then(Commands.literal("status").executes(DragonTrialCommand::status))
				.then(Commands.literal("start").executes(DragonTrialCommand::start))
				.then(Commands.literal("list").executes(DragonTrialCommand::list));
	}

	/** 지금 전투 상태. 타이머가 도는지, 시련이 몇 장 쌓였는지. */
	private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ShareTeam team = teamOf(source);
		if (team == null) {
			source.sendFailure(Component.literal("팀에 속해 있지 않습니다."));
			return 0;
		}
		DragonTrialSession session = DragonTrialManager.sessionOf(team.teamId());
		if (session == null) {
			source.sendSuccess(() -> Component.literal(
					"전투가 열려 있지 않습니다. 엔드에 들어가거나 /shareteam trialtest start 를 쓰십시오."),
					false);
			return 1;
		}
		MinecraftServer server = source.getServer();
		long now = server.overworld().getGameTime();
		long elapsed = session.elapsedTicks(now) / 20;
		long next = session.ticksUntilNextTrial(now);
		String nextText = next < 0 ? "없음(상한 도달)" : (next / 20) + "초 뒤";
		source.sendSuccess(() -> Component.literal(
				"전투 " + elapsed + "초째 · 시련 " + session.trialCount() + "장 "
						+ session.chosen() + " · 다음 " + nextText), false);
		return 1;
	}

	/** 지금 있는 자리에서 전투를 연다. 엔드까지 걸어가지 않아도 된다. */
	private static int start(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ShareTeam team = teamOf(source);
		if (team == null) {
			source.sendFailure(Component.literal("팀에 속해 있지 않습니다."));
			return 0;
		}
		if (SharedFateMod.config.dragonHealthPerMember <= 0) {
			source.sendFailure(Component.literal(
					"dragonHealthPerMember 가 0 이라 강화가 꺼져 있습니다."));
			return 0;
		}
		DragonTrialManager.forceStart(source.getServer(), team);
		source.sendSuccess(() -> Component.literal("엔드 전투를 열었습니다. 팀을 엔드로 불렀습니다."),
				true);
		return 1;
	}

	/** 만들어 둔 시련 카드 목록. */
	private static int list(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> Component.literal("시련 " + TrialCatalog.all().size() + "장"), false);
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			source.sendSuccess(() -> Component.literal(
					"  " + trial.name() + " — " + trial.description()), false);
		}
		return 1;
	}

	private static @Nullable ShareTeam teamOf(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return null;
		}
		return TeamManager.get(source.getServer()).teamOf(player.getUUID());
	}
}
