package com.sharedfate.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.MobPerkModifiers;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkHolderManager;
import com.sharedfate.perk.PerkManager;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 증강을 하나씩 시험해 보기 위한 운영자 전용 명령({@code /shareteam perktest ...}).
 *
 * <p>이 명령은 <b>구간과 레벨을 완전히 건너뛰고</b> 증강을 직접 넣고 뺀다.
 *
 * <h2>이중 잠금</h2>
 * <p>둘이 <b>모두</b> 있어야 동작한다.
 *
 * <ol>
 *   <li><b>운영자 권한(level 4)</b> — {@link Permissions#COMMANDS_OWNER}. 없으면 명령이
 *       목록에 뜨지도 않고 파싱되지도 않는다. 일반 팀원에게는 없는 명령과 같다.</li>
 *   <li><b>설정 플래그</b> — {@code config/sharedfate.json} 의 {@code perkTestCommands}.
 *       기본값은 꺼짐이다.</li>
 * </ol>
 *
 * <p>플래그가 꺼져 있을 때 <b>운영자에게는</b> 왜 안 되는지와 켜는 방법을 알려 준다.
 *
 * <h2>시끄럽게 알린다</h2>
 * <p>플래그가 켜져 있으면 서버가 뜰 때 {@code WARN} 로그가 남고, 접속하는 사람마다 채팅으로
 * 경고를 받는다({@link #warnOnServerStarted}, {@link #warnOnJoin}).
 *
 * <h2>어떻게 반영하는가</h2>
 * <p>보유 목록을 고친 뒤 이미 붙어 있는 효과를 다시 맞춰야 한다. 그 일은
 * {@link PerkManager#setPerksEnabled} 가 이미 정확히 하고 있으므로 <b>껐다 켜는 것</b>으로
 * 재사용한다 — 끌 때 모든 효과가 걷히고, 켤 때 지금 보유 목록으로 다시 붙으며 동기화까지
 * 나간다. 몹에게 걸린 배율만 {@link MobPerkModifiers#invalidateNow} 로 따로 깨운다.
 *
 * <p><b>한 번만 일어나는 효과는 재현되지 않는다.</b> 즉시 지급({@code item_grant})은 증강을 <b>고르는 순간</b>에만 일어나고 {@code apply}/{@code remove} 에서는
 * 아무 일도 하지 않는다. 그래서 이 명령으로 넣은 증강은 아이템을 주지 않는다. 반대로 이
 * 덕분에 넣었다 뺐다를 반복해도 아이템이 불어나지 않는다.
 */
public final class PerkTestCommand {
	private PerkTestCommand() {
	}

	/** 설정 플래그가 켜져 있는가. 설정을 아직 못 읽었으면 꺼진 것으로 본다. */
	public static boolean enabled() {
		return SharedFateMod.config != null && SharedFateMod.config.perkTestCommands;
	}

	/** {@code shareteam} 트리에 붙일 {@code perktest} 가지. */
	public static LiteralArgumentBuilder<CommandSourceStack> node() {
		return Commands.literal("perktest")
				// 첫 번째 잠금. 통과 못 하면 명령이 목록에도 안 뜨고 파싱도 안 된다.
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
				.executes(PerkTestCommand::status)
				.then(Commands.literal("status").executes(PerkTestCommand::status))
				.then(Commands.literal("give")
						.then(perkIdArgument(ALL_PERK_IDS).executes(PerkTestCommand::give)))
				.then(Commands.literal("remove")
						.then(perkIdArgument(OWNED_PERK_IDS).executes(PerkTestCommand::remove)))
				.then(Commands.literal("clear")
						.executes(PerkTestCommand::clearPrompt)
						.then(Commands.literal("confirm").executes(PerkTestCommand::clear)))
				.then(Commands.literal("list")
						.executes(PerkTestCommand::listAll)
						.then(Commands.literal("all").executes(PerkTestCommand::listAll)))
				.then(Commands.literal("owner").executes(PerkTestCommand::owners));
	}

	// ------------------------------------------------------------------ 인자와 자동 완성

	/**
	 * 증강 id 인자.
	 *
	 * <p>{@code StringArgumentType} 은 못 쓴다. 브리가디어의 따옴표 없는 문자열은
	 * {@code :} 를 허용하지 않아 {@code sharedfate:...} 가 파싱 단계에서 깨진다.
	 * {@link IdentifierArgument} 는 {@code 이름공간:경로} 를 제대로 읽는다.
	 */
	private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Identifier>
			perkIdArgument(SuggestionProvider<CommandSourceStack> suggestions) {
		return Commands.argument("perkId", IdentifierArgument.id()).suggests(suggestions);
	}

	/** 등록된 증강 id 전체. */
	private static final SuggestionProvider<CommandSourceStack> ALL_PERK_IDS =
			(context, builder) -> SharedSuggestionProvider.suggest(
					PerkRegistry.all().stream().map(Perk::id).sorted().toList(), builder);

	/**
	 * 지금 이 팀이 들고 있는 증강 id 만.
	 *
	 * <p>팀이 없으면 아무것도 제안하지 않는다.
	 */
	private static final SuggestionProvider<CommandSourceStack> OWNED_PERK_IDS =
			(context, builder) -> {
				TeamState state = stateOfSource(context.getSource());
				return SharedSuggestionProvider.suggest(
						state == null ? List.<String>of() : List.copyOf(state.ownedPerks), builder);
			};

	private static @Nullable TeamState stateOfSource(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player == null ? null : TeamManager.get(source.getServer()).stateOf(player.getUUID());
	}

	/**
	 * 인자로 받은 식별자에 해당하는 증강.
	 *
	 * <p>보유 목록은 <b>증강 정의에 적힌 id 문자열 그대로</b>를 담는다. 그런데 이름공간을
	 * 적지 않은 정의도 있을 수 있고, {@link Identifier} 는 그런 값을 {@code minecraft:} 로
	 * 채운다. 그래서 문자열이 그대로 맞는 경우를 먼저 보고, 안 맞으면 식별자로 바꿔 비교한다.
	 */
	private static @Nullable Perk findPerk(Identifier id) {
		Perk direct = PerkRegistry.byId(id.toString()).orElse(null);
		if (direct != null) {
			return direct;
		}
		for (Perk perk : PerkRegistry.all()) {
			if (id.equals(Identifier.tryParse(perk.id()))) {
				return perk;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 경고

	/** 서버가 뜰 때 한 번. */
	public static void warnOnServerStarted(MinecraftServer server) {
		if (!enabled()) {
			return;
		}
		SharedFateMod.LOGGER.warn(
				"[PERK-TEST] 증강 시험 명령이 켜져 있습니다. 운영자가 /shareteam perktest 로 증강을"
						+ " 마음대로 넣고 뺄 수 있어 이 회차는 정상 회차가 아닙니다."
						+ " 실제로 플레이하는 서버라면 config/sharedfate.json 의"
						+ " perkTestCommands 를 false 로 두십시오.");
	}

	/** 접속할 때마다. */
	public static void warnOnJoin(ServerPlayer player) {
		if (!enabled()) {
			return;
		}
		player.sendSystemMessage(Component.literal(
						"[SharedFate] 증강 시험 명령이 켜져 있는 서버입니다."
								+ " 이 회차는 정상 회차가 아닐 수 있습니다.")
				.withStyle(ChatFormatting.RED));
	}

	/** {@code /shareteam status} 에 덧붙일 한 줄. 켜져 있지도 쓰지도 않았으면 빈 문자열. */
	public static String statusLine(@Nullable TeamState state) {
		if (state != null && state.perkTestUsed) {
			return "\n※ 이번 회차에 증강 시험 명령을 썼습니다. 정상 회차가 아닙니다.";
		}
		return enabled() ? "\n※ 증강 시험 명령이 켜져 있는 서버입니다." : "";
	}

	// ------------------------------------------------------------------ 하위 명령

	private static int status(CommandContext<CommandSourceStack> context) {
		if (!enabled()) {
			return disabledNotice(context);
		}
		TeamState state = stateOfSource(context.getSource());
		String mark = state == null ? "팀이 없습니다."
				: (state.perkTestUsed
						? "이 팀은 이번 회차에 시험 명령을 썼습니다. 정상 회차가 아닙니다."
						: "이 팀은 아직 시험 명령을 쓰지 않았습니다.");
		context.getSource().sendSuccess(() -> Component.literal(
				"증강 시험 명령이 켜져 있습니다 (등록된 증강 " + PerkRegistry.all().size() + "종)."
						+ "\n" + mark
						+ "\n/shareteam perktest give|remove <증강id> · clear confirm · list all · owner"), false);
		return 1;
	}

	/**
	 * 플래그가 꺼져 있을 때의 안내.
	 *
	 * <p>여기까지 왔다는 것은 이미 운영자라는 뜻이다.
	 */
	private static int disabledNotice(CommandContext<CommandSourceStack> context) {
		context.getSource().sendFailure(Component.literal(
				"증강 시험 명령이 꺼져 있습니다."
						+ "\nconfig/sharedfate.json 의 perkTestCommands 를 true 로 두고 서버를 다시 켜십시오."
						+ "\n실제로 플레이하는 서버에서는 켜지 마십시오. 회차가 뜻을 잃습니다."));
		return 0;
	}

	private static int give(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (!enabled()) {
			return disabledNotice(context);
		}
		Target target = target(context);
		if (target == null) {
			return 0;
		}
		Identifier requested = IdentifierArgument.getId(context, "perkId");
		Perk perk = findPerk(requested);
		if (perk == null) {
			context.getSource().sendFailure(Component.literal(
					"그런 증강이 없습니다: " + requested
							+ "\n/shareteam perktest list all 로 목록을 보십시오."));
			return 0;
		}
		if (target.state().ownedPerks.contains(perk.id())) {
			context.getSource().sendFailure(Component.literal(
					"이미 가지고 있습니다: " + perk.name() + " (" + perk.id() + ")"));
			return 0;
		}

		reapply(target, () -> target.state().ownedPerks.add(perk.id()));
		context.getSource().sendSuccess(() -> Component.literal(
				"증강을 넣었습니다: " + perk.rarity().displayName() + " " + perk.name()
						+ " (" + perk.id() + ")"
						+ "\n즉시 지급처럼 고르는 순간에만 일어나는 효과는 재현되지 않습니다."), true);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (!enabled()) {
			return disabledNotice(context);
		}
		Target target = target(context);
		if (target == null) {
			return 0;
		}
		Identifier requested = IdentifierArgument.getId(context, "perkId");
		Perk perk = findPerk(requested);
		// 정의가 사라진 증강이 보유 목록에 남아 있을 수 있다. 그때도 뺄 수 있어야 한다.
		String perkId = perk == null ? requested.toString() : perk.id();
		if (!target.state().ownedPerks.contains(perkId)) {
			context.getSource().sendFailure(Component.literal("가지고 있지 않습니다: " + perkId));
			return 0;
		}

		reapply(target, () -> target.state().ownedPerks.remove(perkId));
		String name = perk == null ? perkId : perk.name();
		context.getSource().sendSuccess(() -> Component.literal(
				"증강을 뺐습니다: " + name + " (" + perkId + ")"), true);
		return 1;
	}

	/** 되돌릴 수 없으므로 반드시 한 번 더 묻는다. */
	private static int clearPrompt(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		if (!enabled()) {
			return disabledNotice(context);
		}
		Target target = target(context);
		if (target == null) {
			return 0;
		}
		int count = target.state().ownedPerks.size();
		context.getSource().sendSuccess(() -> Component.literal(
				"보유 증강 " + count + "종을 모두 지우려면"
						+ " /shareteam perktest clear confirm 을 입력하십시오."
						+ "\n되돌릴 수 없습니다."), false);
		return 1;
	}

	private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
		if (!enabled()) {
			return disabledNotice(context);
		}
		Target target = target(context);
		if (target == null) {
			return 0;
		}
		int count = target.state().ownedPerks.size();
		if (count == 0) {
			context.getSource().sendSuccess(() -> Component.literal("보유한 증강이 없습니다."), false);
			return 0;
		}

		reapply(target, target.state().ownedPerks::clear);
		context.getSource().sendSuccess(
				() -> Component.literal("보유 증강 " + count + "종을 모두 지웠습니다."), true);
		return 1;
	}

	private static int listAll(CommandContext<CommandSourceStack> context) {
		if (!enabled()) {
			return disabledNotice(context);
		}
		List<Perk> perks = new ArrayList<>(PerkRegistry.all());
		if (perks.isEmpty()) {
			context.getSource().sendFailure(Component.literal("읽어 둔 증강이 없습니다."));
			return 0;
		}
		// 등급끼리 묶여 있어야 눈으로 훑을 수 있다. 등급 안에서는 id 순.
		perks.sort((first, second) -> {
			int byRarity = first.rarity().compareTo(second.rarity());
			return byRarity != 0 ? byRarity : first.id().compareTo(second.id());
		});
		TeamState state = stateOfSource(context.getSource());
		StringBuilder body = new StringBuilder("등록된 증강 ").append(perks.size()).append("종");
		for (Perk perk : perks) {
			boolean owned = state != null && state.ownedPerks.contains(perk.id());
			body.append('\n').append(owned ? "· [보유] " : "· ")
					.append(perk.rarity().displayName()).append(' ').append(perk.name())
					.append(" — ").append(perk.id());
		}
		String text = body.toString();
		context.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/**
	 * 「고른 사람만」 걸리는 증강의 주인이 지금 누구인가.
	 *
	 * <h2>왜 이것만 따로 필요한가</h2>
	 * <p>주인은 알림 액션바 한 번으로만 알려지고 <b>채팅에도 서버 로그에도 남지 않는다.</b>
	 * 화면을 안 보고 있으면 그대로 놓치고 다시 볼 방법이 없었다. 그래서 「재시작해도 같은
	 * 사람인가」 같은 것을 보려면 증강 효과를 하나하나 실연해야 했다 — 「열외」는 위치 교환
	 * 주기까지 최대 5분을 기다려야 한다.
	 *
	 * <p>보유 목록을 하나도 건드리지 않으므로 {@code markUsed} 표식을 붙이지 않는다. 읽기만
	 * 하는 것은 회차를 오염시키지 않는다.
	 *
	 * <p>{@code PerkHolderManager} 가 반 초마다 빈 주인을 채우므로, 「아직 주인 없음」이 보이는
	 * 것은 팀원이 아무도 접속해 있지 않을 때뿐이다.
	 */
	private static int owners(CommandContext<CommandSourceStack> context) {
		if (!enabled()) {
			return disabledNotice(context);
		}
		TeamState state = stateOfSource(context.getSource());
		if (state == null) {
			context.getSource().sendFailure(Component.literal("팀이 없습니다."));
			return 0;
		}
		MinecraftServer server = context.getSource().getServer();
		StringBuilder body = new StringBuilder("주인이 정해지는 증강");
		int count = 0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (!PerkHolderManager.needsOwner(perk)) {
				continue;
			}
			count++;
			UUID owner = state.perkOwners.get(perkId);
			body.append("\n· ").append(perk.name()).append(" (").append(perkId).append(") — ")
					.append(owner == null ? "아직 주인 없음" : nameOf(server, owner));
		}
		String text = count == 0
				? "주인이 정해지는 증강을 하나도 가지고 있지 않습니다."
				: body.toString();
		context.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/** 접속 중이면 이름, 아니면 UUID 앞 여덟 자. */
	private static String nameOf(MinecraftServer server, UUID id) {
		ServerPlayer player = server.getPlayerList().getPlayer(id);
		return player != null ? player.getPlainTextName() : id.toString().substring(0, 8);
	}

	// ------------------------------------------------------------------ 공통

	/** 명령을 친 사람의 팀과 상태. */
	private record Target(MinecraftServer server, ShareTeam team, TeamState state,
			TeamManager manager) {
	}

	/** 시험할 수 없는 상황이면 이유를 알리고 {@code null} 을 준다. */
	private static @Nullable Target target(CommandContext<CommandSourceStack> context)
			throws CommandSyntaxException {
		ServerPlayer self = context.getSource().getPlayerOrException();
		MinecraftServer server = context.getSource().getServer();
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(self.getUUID());
		TeamState state = manager.stateOf(self.getUUID());
		if (team == null || state == null) {
			context.getSource().sendFailure(Component.literal(
					"팀이 없습니다. 증강은 팀 단위라 팀을 먼저 만들어야 합니다."));
			return null;
		}
		// 증강이 꺼진 팀에서 억지로 넣으면 아래의 껐다 켜기가 팀을 「증강 켜짐」으로 바꿔
		// 버린다. 증강 사용 여부는 팀을 만들 때만 정하는 값이라 여기서 몰래 뒤집으면 안 된다.
		if (!state.perksEnabled) {
			context.getSource().sendFailure(Component.literal(
					"이 팀은 증강이 꺼져 있습니다. 증강 여부는 팀을 만들 때만 정하므로,"
							+ " 시험하려면 증강을 켠 팀을 새로 만드십시오."));
			return null;
		}
		return new Target(server, team, state, manager);
	}

	/**
	 * 보유 목록을 고치고 효과를 다시 맞춘다.
	 *
	 * <p>끄는 순간 모든 효과가 걷히므로 <b>목록을 고치기 전에</b> 꺼야 한다. 고친 뒤에 끄면
	 * 방금 뺀 증강의 효과를 아무도 안 걷어내 그대로 남는다.
	 */
	private static void reapply(Target target, Runnable change) {
		PerkManager.setPerksEnabled(target.server(), target.team(), target.state(), false);
		change.run();
		target.state().sanitizePerks();
		PerkManager.setPerksEnabled(target.server(), target.team(), target.state(), true);
		MobPerkModifiers.invalidateNow(target.server());
		markUsed(target);
		target.manager().setDirty();
	}

	/** 이 회차가 정상 회차가 아니라는 표식. 한 번 붙으면 회차가 끝날 때까지 안 지워진다. */
	private static void markUsed(Target target) {
		if (target.state().perkTestUsed) {
			return;
		}
		target.state().perkTestUsed = true;
		SharedFateMod.LOGGER.warn(
				"[PERK-TEST] 팀 '{}' 이 증강 시험 명령을 썼습니다. 이 회차는 정상 회차가 아닙니다.",
				target.team().name());
	}
}
