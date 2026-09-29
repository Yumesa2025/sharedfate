package com.sharedfate.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.sharedfate.SharedFateMod;
import com.sharedfate.sync.DragonTrialManager;
import com.sharedfate.sync.DragonTrialSession;
import com.sharedfate.sync.TrialCatalog;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 엔드 전투를 시험하는 운영자 명령.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>드래곤 전투를 손으로 확인하려면 회차를 새로 열고 요새를 찾아 엔드까지 가야 한다. 한 번
 * 고칠 때마다 그것을 반복할 수는 없다. 이 명령은 <b>엔드에 선 상태에서 전투를 지금 열고</b>,
 * 시련 타이머를 앞당기고, 지금 상태를 들여다본다.
 *
 * <h2>왜 카드를 하나씩 집는 가지가 따로 있는가</h2>
 *
 * <p>전투가 카드를 고르는 방식은 <b>풀에서 자동으로 뽑는 것</b>이다. 그래서 목록에 카드를 열
 * 장 넣어도 실제로 전투를 열어 보면 뽑히는 한 장만 나오고, 나머지는 눈으로 볼 길이 아예 없다.
 * 값을 조정하며 만드는 동안에는 <b>보고 싶은 카드를 지금 보는 것</b>이 필요하다.
 *
 * <p>{@code give}·{@code fire}·{@code clear} 셋이 그 일을 한다. 카드를 지목해 받고, 자리를
 * 강제로 터뜨리고, 쌓인 것을 비워 다음 카드를 맨몸으로 본다.
 *
 * <h2>자동 완성이 절반이다</h2>
 *
 * <p>{@code sharedfate:lightning_storm} 을 손으로 치게 하면 아무도 안 쓴다. 카드 id 와 자리
 * 이름 모두 자동 완성을 붙이고, 기계 이름 옆에 <b>사람이 읽는 이름</b>을 풍선으로 띄운다 —
 * {@code first_crystal} 만 봐서는 그것이 「첫 크리스탈」인지 알 수 없기 때문이다.
 *
 * <h2>실제로 노는 서버에서는 켜지 마십시오</h2>
 *
 * <p>{@code perkTestCommands} 와 같은 잠금을 쓴다. 설정이 꺼져 있으면 카드를 주무르는 세 가지는
 * <b>트리에 아예 붙지 않는다</b>. 권한({@link Permissions#COMMANDS_OWNER})은 그 위에 따로 걸려
 * 있어 둘을 모두 넘어야 닿는다.
 */
public final class DragonTrialCommand {
	/** 카드 id 인자 이름. 시험이 트리를 짚을 때도 이 이름으로 찾는다. */
	static final String ARG_TRIAL_ID = "trialId";
	/** 자리 인자 이름. */
	static final String ARG_TRIGGER = "trigger";

	private DragonTrialCommand() {
	}

	public static boolean enabled() {
		return SharedFateMod.config != null && SharedFateMod.config.perkTestCommands;
	}

	/** {@code shareteam} 트리에 붙일 {@code trialtest} 가지. */
	public static LiteralArgumentBuilder<CommandSourceStack> node() {
		LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("trialtest")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_OWNER))
				.executes(DragonTrialCommand::status)
				.then(Commands.literal("status").executes(DragonTrialCommand::status))
				.then(Commands.literal("start").executes(DragonTrialCommand::start))
				.then(Commands.literal("list").executes(DragonTrialCommand::list));
		if (!enabled()) {
			// 설정이 꺼져 있으면 아예 달지 않는다. 권한만으로 막으면 운영자 계정 하나로 노는
			// 서버에서 사실상 잠금이 없는 것과 같다.
			return node;
		}
		return node
				.then(Commands.literal("give")
						.then(Commands.argument(ARG_TRIAL_ID, IdentifierArgument.id())
								.suggests(TRIAL_IDS)
								.executes(DragonTrialCommand::give)))
				.then(Commands.literal("fire")
						.then(Commands.argument(ARG_TRIGGER, StringArgumentType.word())
								.suggests(TRIGGER_NAMES)
								.executes(DragonTrialCommand::fire)))
				.then(Commands.literal("clear").executes(DragonTrialCommand::clear));
	}

	// ------------------------------------------------------------------ 자동 완성

	/**
	 * 만들어 둔 카드 id 전부.
	 *
	 * <p>{@code SharedSuggestionProvider.suggest} 를 쓰지 않는다. 그쪽은 풍선을 달 수 없어
	 * {@code sharedfate:ground_strike} 만 늘어놓게 되는데, 그러면 어느 것이 「자리 폭격」인지
	 * 목록을 따로 띄워 대조해야 한다. 여기서는 브리가디어의 풍선에 카드 이름을 넣는다.
	 *
	 * <p>이름공간을 생략하고 {@code ground} 만 쳐도 걸리게 경로 쪽도 함께 본다.
	 */
	private static final SuggestionProvider<CommandSourceStack> TRIAL_IDS = (context, builder) -> {
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			String id = trial.id().toLowerCase(Locale.ROOT);
			int colon = id.indexOf(':');
			boolean matches = id.startsWith(typed)
					|| (colon >= 0 && id.substring(colon + 1).startsWith(typed));
			if (matches) {
				builder.suggest(trial.id(), Component.literal(trial.name()));
			}
		}
		return builder.buildFuture();
	};

	/**
	 * 자리 여섯.
	 *
	 * <p>기계 이름({@code first_crystal})으로 받고 풍선에 {@link TrialCatalog.Trigger#label()}
	 * 을 띄운다. 사람이 읽는 이름을 <b>인자 값으로</b> 받는 길도 있었지만, 「첫 크리스탈」처럼
	 * 빈칸이 들어간 낱말은 따옴표로 감싸야 해서 치기가 더 나쁘다.
	 */
	private static final SuggestionProvider<CommandSourceStack> TRIGGER_NAMES = (context, builder) -> {
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (TrialCatalog.Trigger trigger : TrialCatalog.Trigger.values()) {
			String name = trigger.name().toLowerCase(Locale.ROOT);
			if (name.startsWith(typed)) {
				builder.suggest(name, Component.literal(trigger.label()));
			}
		}
		return builder.buildFuture();
	};

	// ------------------------------------------------------------------ 들여다보기

	/** 지금 전투 상태. 타이머가 도는지, 시련이 몇 장 쌓였는지. */
	private static int status(CommandContext<CommandSourceStack> context) {
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
		String next = session.peekTrigger() == null
				? "대기 중인 자리 없음"
				: session.peekTrigger().label() + " 대기";
		source.sendSuccess(() -> Component.literal(
				"전투 " + elapsed + "초째 · 시련 " + session.trialCount() + "장 " + session.chosen()
						+ " · 터진 자리 " + session.fired().size() + "/"
						+ TrialCatalog.Trigger.values().length + " · " + next
						+ (enabled()
								? "\n/shareteam trialtest give <카드id> · fire <자리> · clear"
								: "")), false);
		return 1;
	}

	/** 지금 있는 자리에서 전투를 연다. 엔드까지 걸어가지 않아도 된다. */
	private static int start(CommandContext<CommandSourceStack> context) {
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

	/**
	 * 만들어 둔 시련 카드 목록.
	 *
	 * <p>이름과 설명만 찍던 것을 <b>어느 풀에 있고 어떤 위험을 몇으로 거는지</b>까지 찍도록
	 * 넓혔다. 카드를 만드는 동안 실제로 알고 싶은 것은 「간격이 240틱이었나 200틱이었나」이고,
	 * 그것을 보려고 소스를 다시 여는 일이 반복되기 때문이다. 카드가 위험을 여럿 들 수 있게 된
	 * 뒤로는 <b>한 줄 요약으로는 무엇이 도는지조차 알 수 없다.</b>
	 */
	private static int list(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		List<TrialCatalog.Trial> trials = TrialCatalog.all();
		StringBuilder body = new StringBuilder("시련 ").append(trials.size()).append("장");
		for (TrialCatalog.Trial trial : trials) {
			body.append("\n· ").append(trial.name())
					.append(" (").append(trial.id()).append(") — ").append(poolName(trial.pools()))
					.append("\n    ").append(trial.description());
			for (TrialCatalog.Risk risk : trial.risks()) {
				body.append("\n    ▸ ").append(describe(risk));
			}
		}
		String text = body.toString();
		source.sendSuccess(() -> Component.literal(text), false);
		return 1;
	}

	/**
	 * 이 카드가 속한 풀의 이름.
	 *
	 * <p>{@code TrialCatalog} 의 풀 상수와 같은 것이면 그 이름으로 부른다. 카드를 적을 때 쓴
	 * 낱말과 목록에 뜨는 낱말이 같아야 「이 카드를 후반으로 옮기자」가 그대로 통한다. 상수에 없는
	 * 조합이면 자리 이름을 늘어놓는다 — 손으로 조합한 풀도 정상이다.
	 */
	private static String poolName(@Nullable Set<TrialCatalog.Trigger> pools) {
		if (pools == null || pools.isEmpty()) {
			return "풀 없음 (뽑히지 않습니다)";
		}
		if (pools.equals(TrialCatalog.POOL_ENTRY)) {
			return "입장 풀";
		}
		if (pools.equals(TrialCatalog.POOL_FIRST_CRYSTAL)) {
			return "첫 크리스탈 풀";
		}
		if (pools.equals(TrialCatalog.POOL_ALL_CRYSTALS)) {
			return "크리스탈 전멸 풀";
		}
		if (pools.equals(TrialCatalog.POOL_HEALTH_80)) {
			return "체력 80% 풀";
		}
		if (pools.equals(TrialCatalog.POOL_HEALTH_50)) {
			return "체력 50% 풀";
		}
		if (pools.equals(TrialCatalog.POOL_HEALTH_30)) {
			return "체력 30% 풀";
		}
		StringBuilder joined = new StringBuilder();
		for (TrialCatalog.Trigger trigger : pools) {
			if (!joined.isEmpty()) {
				joined.append(", ");
			}
			joined.append(trigger.label());
		}
		return joined.toString();
	}

	/**
	 * 위험 하나를 사람이 읽는 한 줄로.
	 *
	 * <p>{@code default} 를 두지 않는다. {@code Risk} 에 타입을 더하고 여기를 안 고치면
	 * <b>컴파일이 거절한다</b> — 새 위험이 목록에 「알 수 없음」으로 조용히 뜨는 것을 막는다.
	 */
	private static String describe(TrialCatalog.Risk risk) {
		return switch (risk) {
			case TrialCatalog.Risk.DelayedStrike strike -> {
				StringBuilder line = new StringBuilder("예고 타격 · ")
						.append(strike.aim() == TrialCatalog.Risk.Aim.TRAIL ? "발자국" : "아무 곳")
						// 연출을 함께 찍는다. 값을 조정하는 쪽이 「이 카드가 번개인지 폭발인지」를
						// list 한 줄로 알아야 한다 — 그것이 어긋난 것이 낙뢰 카드의 결함이었다.
						.append(" · ").append(impact(strike.impact()))
						.append(" · 간격 ").append(ticks(strike.interval()))
						.append(" · 피해 ").append(strike.damage())
						.append(" · 반경 ").append(strike.radius()).append("블록")
						.append(" · ").append(strike.count())
						.append(strike.aim() == TrialCatalog.Risk.Aim.TRAIL ? "명" : "곳");
				if (strike.aim() == TrialCatalog.Risk.Aim.TRAIL) {
					line.append(" · 되돌아보기 ").append(ticks(strike.lookback()));
				}
				if (strike.launch() > 0.0) {
					line.append(" · 띄움 ").append(strike.launch()).append("블록");
				}
				yield line.toString();
			}
			case TrialCatalog.Risk.TracedProjectile shot -> "궤적 투사체 · 간격 "
					+ ticks(shot.interval()) + " · 궤적 " + ticks(shot.traceTicks())
					+ " · 피해 " + shot.damage() + " · 반경 " + shot.radius() + "블록 · "
					+ shot.count() + "발";
			case TrialCatalog.Risk.CrystalGuard guard -> {
				StringBuilder line = new StringBuilder("크리스탈 수호 · ");
				if (guard.arrowImmune()) {
					line.append("투사체 면역 ");
				}
				if (guard.restoreCage()) {
					line.append("쇠창살 재생 ");
				}
				if (guard.digSlowdown()) {
					line.append("채굴 피로 I ");
				}
				yield line.toString().trim();
			}
			case TrialCatalog.Risk.DragonFocus focus -> "표적 · "
					+ (focus.focus() == TrialCatalog.Risk.Focus.CRYSTAL_BREAKER
							? "크리스탈을 깬 사람" : "무작위")
					+ " · 표적 " + ticks(focus.markTicks()) + " · 쉼 " + ticks(focus.restTicks())
					+ " · " + focus.shots() + "발 · 발당 " + focus.damage();
			case TrialCatalog.Risk.CrystalRevive revive -> "크리스탈 부활 · "
					+ revive.count() + "개 · 연출 " + ticks(revive.showTicks())
					+ (revive.heal() > 0.0F ? " · 개당 회복 " + revive.heal() : "");
			case TrialCatalog.Risk.EnderPulse pulse -> "엔더 파동 · 간격 "
					+ ticks(pulse.interval()) + " · 퍼짐 " + ticks(pulse.travelTicks())
					+ " · 최대 반경 " + pulse.maxRadius() + "블록 · 구속 "
					+ ticks(pulse.rootTicks());
			case TrialCatalog.Risk.CrystalLink link -> "연결된 수정 · 보호막 "
					+ ticks(link.shieldTicks());
			case TrialCatalog.Risk.CrystalOvercharge overcharge -> "수정 과충전 · 도화선 "
					+ ticks(overcharge.fuseTicks()) + " · 빔 " + ticks(overcharge.beamTicks())
					+ " · 쉼 " + ticks(overcharge.restTicks()) + " · 초당 피해 "
					+ overcharge.damagePerSecond();
			case TrialCatalog.Risk.EnderStorm storm -> "엔더폭풍 · " + storm.count() + "개 · 초당 "
					+ storm.speedPerSecond() + "블록 · 피해 " + storm.damage() + " · 넉백 "
					+ storm.knockback() + "(안쪽) · 쉼 " + ticks(storm.restTicks());
			case TrialCatalog.Risk.DryWorld ignored ->
					"메마른 세계 · 한 번 · 물·용암 증발 및 설치 금지";
			case TrialCatalog.Risk.NightHost host -> "밤의 군세 · 한 번 · 엔더맨 적대 "
					+ ticks(host.hostileTicks());
			case TrialCatalog.Risk.EndRain rain -> "종말의 비 · 한 번 · 지속 "
					+ ticks(rain.durationTicks()) + " · 간격 " + rain.minInterval() + "~"
					+ rain.maxInterval() + "틱 · " + rain.minSpots() + "~" + rain.maxSpots()
					+ "곳 · 예고 " + ticks(rain.warnTicks()) + " · 피해 " + rain.damage()
					+ " · 반경 " + rain.radius() + "블록";
			case TrialCatalog.Risk.LandingShock shock -> "착지 충격 · 퍼짐 "
					+ ticks(shock.travelTicks()) + " · 최대 반경 " + shock.maxRadius()
					+ "블록 · 피해 " + shock.damage() + " · 넉백 " + shock.knockback() + "블록";
			case TrialCatalog.Risk.HotbarLock lock -> "굳는 손 · 간격 " + ticks(lock.interval())
					+ " · " + lock.slots() + "칸";
		};
	}

	/**
	 * 떨어진 자리에 무엇이 보이는가.
	 *
	 * <p>{@code default} 를 두지 않는다. {@code Impact} 를 늘리고 여기를 안 고치면 <b>컴파일이
	 * 거절한다</b> — 새 연출이 목록에 옛 이름으로 조용히 뜨는 것을 막는다.
	 */
	private static String impact(TrialCatalog.Risk.Impact impact) {
		return switch (impact) {
			case EXPLOSION -> "폭발";
			case LIGHTNING -> "번개";
		};
	}

	/** 틱과 초를 함께 적는다. 값을 조정하는 쪽은 틱으로 적고 감은 초로 잡기 때문이다. */
	private static String ticks(int value) {
		return value + "틱(" + String.format(Locale.ROOT, "%.1f", value / 20.0) + "초)";
	}

	// ------------------------------------------------------------------ 카드 하나씩

	/**
	 * 카드를 지목해 지금 받는다.
	 *
	 * <p>실패한 이유를 <b>갈라서</b> 말한다. 「전투가 없다」와 「그런 카드가 없다」는 다음에 할 일이
	 * 전혀 다르다 — 앞쪽은 {@code start} 를 쳐야 하고 뒤쪽은 id 를 다시 봐야 한다.
	 */
	private static int give(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ShareTeam team = teamOf(source);
		if (team == null) {
			source.sendFailure(Component.literal("팀에 속해 있지 않습니다."));
			return 0;
		}
		Identifier requested = IdentifierArgument.getId(context, ARG_TRIAL_ID);
		TrialCatalog.Trial trial = findTrial(requested);
		if (trial == null) {
			source.sendFailure(Component.literal(
					"그런 카드가 없습니다: " + requested
							+ "\n/shareteam trialtest list 로 목록을 보십시오."));
			return 0;
		}
		DragonTrialSession session = DragonTrialManager.sessionOf(team.teamId());
		if (session == null) {
			source.sendFailure(Component.literal(
					"전투가 열려 있지 않습니다. /shareteam trialtest start 를 먼저 쓰십시오."));
			return 0;
		}
		if (session.chosen().contains(trial.id())) {
			source.sendFailure(Component.literal(
					"이미 받은 카드입니다: " + trial.name() + " (" + trial.id() + ")"
							+ "\n/shareteam trialtest clear 로 비운 뒤 다시 주십시오."));
			return 0;
		}
		if (!DragonTrialManager.grant(source.getServer(), team, trial.id())) {
			source.sendFailure(Component.literal("카드를 넣지 못했습니다: " + trial.id()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				"카드를 넣었습니다: " + trial.name() + " (" + trial.id() + ")"), true);
		return 1;
	}

	/**
	 * 자리 하나를 강제로 터뜨린다.
	 *
	 * <p>체력을 30%까지 깎지 않고도 후반 풀이 무엇을 내는지 볼 수 있다. 자리는 한 판에 한 번만
	 * 세므로 이미 터진 자리는 거절하고 그렇게 말한다.
	 */
	private static int fire(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ShareTeam team = teamOf(source);
		if (team == null) {
			source.sendFailure(Component.literal("팀에 속해 있지 않습니다."));
			return 0;
		}
		String requested = StringArgumentType.getString(context, ARG_TRIGGER);
		TrialCatalog.Trigger trigger = parseTrigger(requested);
		if (trigger == null) {
			source.sendFailure(Component.literal(
					"그런 자리가 없습니다: " + requested + "\n" + triggerNames()));
			return 0;
		}
		DragonTrialSession session = DragonTrialManager.sessionOf(team.teamId());
		if (session == null) {
			source.sendFailure(Component.literal(
					"전투가 열려 있지 않습니다. /shareteam trialtest start 를 먼저 쓰십시오."));
			return 0;
		}
		if (session.fired().contains(trigger)) {
			source.sendFailure(Component.literal(
					"이미 터진 자리입니다: " + trigger.label()
							+ "\n자리는 한 판에 한 번만 셉니다."
							+ " /shareteam trialtest clear 로 비운 뒤 다시 터뜨리십시오."));
			return 0;
		}
		if (!DragonTrialManager.fire(source.getServer(), team, trigger)) {
			source.sendFailure(Component.literal("자리를 터뜨리지 못했습니다: " + trigger.label()));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				"자리를 터뜨렸습니다: " + trigger.label() + " (" + trigger.name().toLowerCase(Locale.ROOT)
						+ ")"), true);
		return 1;
	}

	/**
	 * 쌓인 시련을 비운다.
	 *
	 * <p>카드는 전투 중에 풀리지 않으므로, 한 장 보고 나면 그 다음 카드는 <b>앞 카드가 도는
	 * 와중에</b> 보게 된다. 둘이 겹치면 어느 쪽이 무엇을 한 것인지 가릴 수 없다. 비우고 나서
	 * 다음 카드를 맨몸으로 보기 위한 가지다.
	 *
	 * <p>{@code perktest clear} 와 달리 되묻지 않는다. 지우는 것이 시험으로 쌓은 것뿐이라
	 * 잃을 것이 없고, 카드를 하나씩 넘겨 보는 동안 가장 자주 치는 가지이기 때문이다.
	 */
	private static int clear(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		ShareTeam team = teamOf(source);
		if (team == null) {
			source.sendFailure(Component.literal("팀에 속해 있지 않습니다."));
			return 0;
		}
		DragonTrialSession session = DragonTrialManager.sessionOf(team.teamId());
		if (session == null) {
			source.sendFailure(Component.literal(
					"전투가 열려 있지 않습니다. /shareteam trialtest start 를 먼저 쓰십시오."));
			return 0;
		}
		int count = session.trialCount();
		if (!DragonTrialManager.clear(source.getServer(), team)) {
			source.sendFailure(Component.literal("시련을 비우지 못했습니다."));
			return 0;
		}
		source.sendSuccess(() -> Component.literal(
				"쌓인 시련 " + count + "장을 비웠습니다. 터진 자리도 함께 풀립니다."), true);
		return 1;
	}

	// ------------------------------------------------------------------ 공통

	/**
	 * 인자로 받은 식별자에 해당하는 카드.
	 *
	 * <p>{@link Identifier} 는 이름공간을 안 적은 값을 {@code minecraft:} 로 채운다. 카드 id 에
	 * 이름공간이 없는 정의가 섞일 수 있으므로 문자열이 그대로 맞는 경우를 먼저 본다.
	 */
	private static @Nullable TrialCatalog.Trial findTrial(Identifier id) {
		TrialCatalog.Trial direct = TrialCatalog.byId(id.toString());
		if (direct != null) {
			return direct;
		}
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			if (id.equals(Identifier.tryParse(trial.id()))) {
				return trial;
			}
		}
		return null;
	}

	/** 대소문자를 가리지 않는다. 자동 완성은 소문자를 내지만 손으로 칠 수도 있다. */
	private static @Nullable TrialCatalog.Trigger parseTrigger(String value) {
		for (TrialCatalog.Trigger trigger : TrialCatalog.Trigger.values()) {
			if (trigger.name().equalsIgnoreCase(value)) {
				return trigger;
			}
		}
		return null;
	}

	/** 실패 안내에 붙일 자리 목록. 기계 이름과 사람 이름을 함께 적는다. */
	private static String triggerNames() {
		StringBuilder body = new StringBuilder("쓸 수 있는 자리:");
		for (TrialCatalog.Trigger trigger : TrialCatalog.Trigger.values()) {
			body.append("\n· ").append(trigger.name().toLowerCase(Locale.ROOT))
					.append(" — ").append(trigger.label());
		}
		return body.toString();
	}

	private static @Nullable ShareTeam teamOf(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			return null;
		}
		return TeamManager.get(source.getServer()).teamOf(player.getUUID());
	}
}
