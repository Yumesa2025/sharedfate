package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkSwapRules;
import com.sharedfate.perk.effect.SwapExplosionEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

public final class PositionSwapManager {
	private static final int TICKS_PER_SECOND = 20;
	/**
	 * 교환에 필요한 최소 인원.
	 *
	 * <p>세는 대상은 「접속한 팀원」이 아니라 <b>실제로 자리를 바꿀 사람</b>이다. 골드 「열외」로
	 * 한 명이 빠져 둘 미만이 되면 교환이 일어나지 않고 1초 뒤 다시 시도한다.
	 */
	private static final int MIN_SWAP_MEMBERS = 2;
	/** 카운트다운 기본 길이(초). 설정이 없을 때 쓴다. */
	static final int DEFAULT_COUNTDOWN_SECONDS = 5;
	/** 교환 직후 "위치 교환!" 타이틀이 화면에 남는 시간과 사라지는 시간(틱). */
	private static final int SWAP_TITLE_STAY_TICKS = 30;
	private static final int SWAP_TITLE_FADE_OUT_TICKS = 10;

	private PositionSwapManager() {
	}

	public static void tick(MinecraftServer server) {
		// 강제 증강 선택 중에는 시간이 멈춰 있고 팀원은 창에 갇혀 아무것도 할 수 없다. 그
		// 사이에 카운트다운이 흐르거나 자리가 뒤바뀌면 창을 닫자마자 낯선 곳에 서 있게 된다.
		// 세션이 사는 동안에는 주기 자체를 세우고 지나간다.
		// 게임 오버 카운트다운 5초 동안도 마찬가지다. 회차는 이미 끝났고 곧 서버가 멈추는데,
		// 그 사이에 자리가 뒤바뀌면 월드를 지우지 않는 서버에서는 벽 속에 박힌 채 저장된다.
		if (PerkChoiceSession.isActive() || WorldResetCoordinator.countingDown()) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		int countdownSeconds = configuredCountdownSeconds();
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			// 「게임 시작」을 누르기 전에는 주기를 세지 않는다. 팀원을 기다리는 동안 자리가
			// 뒤바뀌면 모여 있던 사람들이 흩어지고, 무엇보다 그 시간은 회차에 속하지 않는다.
			// 시작하는 순간 남은 시간이 주기 그대로 채워진다({@code GameStartManager}).
			if (state == null || !state.positionSwapEnabled() || !state.runStarted) {
				continue;
			}
			// 시차가 걸음을 진행하는 동안에는 다음 주기 자체를 세지 않는다. 정거장은 이
			// 얼림을 쓰지 않는다 — 복귀 대기 중에 새 주기가 오면 그 대기를 버리고 새로 모인다.
			if (StaggeredSwapManager.hasActiveSequence(team.teamId())) {
				continue;
			}
			// 「소집의 조각」은 자동 교환을 통째로 없앤다. 주기를 세지도, 예고하지도, on_swap 을
			// 발동하지도 않는다. 자리를 바꾸는 일만 막으면 부수효과가 주기마다 계속 돌아
			// 「시차」는 공짜 버프가 되고 「정거장」은 대가만 남는다(PerkSwapRules.silentSwapBlock).
			if (PerkSwapRules.silentSwapBlock(state)) {
				continue;
			}
			List<ServerPlayer> online = onlineMembers(server, team);
			// 골드 「열외」를 고른 사람은 자리를 바꾸는 명단에서 빠진다. 최소 인원도 남은 사람
			// 기준으로 센다 — 열외로 한 명이 빠져 둘 미만이 되면 교환 자체가 일어나지 않고
			// 1초 뒤 다시 시도한다(TeamState.advancePositionSwapTick).
			List<ServerPlayer> movers = PerkSwapRules.swapParticipants(state, online);
			boolean enoughMembers = movers.size() >= MIN_SWAP_MEMBERS;
			if (state.advancePositionSwapTick(enoughMembers)) {
				swapMoment(online, movers, team, state);
				continue;
			}
			// 「폭발 교환」을 가진 팀은 남은 시간을 화면 왼쪽 위에 늘 달고 다닌다. 자리를 비울
			// 때 터지는 폭발이 대가이고, 언제 터질지 아는 것이 혜택이다. 최소 인원이 모자라
			// 교환이 미뤄지는 동안에도 시계는 돌아야 하므로 인원 검사보다 앞이다.
			if (!PerkSwapRules.swapExplosions(state).isEmpty()
					&& state.positionSwapRemainingTicks % TICKS_PER_SECOND == 0) {
				com.sharedfate.net.TeamBroadcaster.broadcastSwapTimer(online,
						Math.max(0, state.positionSwapRemainingTicks / TICKS_PER_SECOND));
			}
			if (!enoughMembers) {
				continue;
			}
			int secondsLeft = countdownSecondsToShow(
					state.positionSwapRemainingTicks, countdownSeconds);
			if (secondsLeft > 0) {
				announceCountdown(online, secondsLeft);
			}
		}
	}

	/**
	 * 자리가 바뀔 시점이 왔다. 증강이 끼어드는 자리는 여기 다섯 곳이다.
	 *
	 * <p>순서가 중요하다. {@code swap_block} 이 막는 것은 <b>순간이동 한 자리</b>뿐이고,
	 * {@code on_swap} 은 막혔든 아니든 그대로 발동한다. 그래야 「뿌리내린 발」의 "원래 바뀔
	 * 시점마다 실명과 구속"이 성립한다. 주기 배율도 마찬가지로 막힘과 무관하게 먹인다.
	 *
	 * <p>{@code swap_rally}(정거장)가 {@code staggered_swap}(시차)보다 먼저다. 한 팀이 둘 다
	 * 가진 경우에도 판정 순서가 늘 같아야 한다({@link PerkSwapRules#rallyPoint} 문서 참고).
	 *
	 * <h2>명단이 둘이다</h2>
	 * <p>{@code movers} 는 <b>자리를 바꾸는 사람들</b>이고 {@code online} 은 <b>팀 전원</b>이다.
	 * 골드 「열외」({@code swap_exempt})를 고른 사람만 앞에서 빠져 있다. 순간이동에 관한 것은
	 * 전부 {@code movers} 로, 알림과 {@code on_swap} 은 {@code online} 으로 간다 — 열외는 자리를
	 * 바꾸지 않을 뿐 여전히 팀원이라, 교환 시점에 팀에 걸리는 효과는 그대로 받아야 한다.
	 *
	 * <p>열외의 이동 속도 보너스는 <b>갈림길보다 먼저</b> 준다. 시차는 아래에서 곧바로 돌아가
	 * 버리므로 뒤에 두면 시차 팀의 열외만 영영 못 받는다.
	 *
	 * <p>{@code TeamState.advancePositionSwapTick} 이 이미 남은 틱을 주기 그대로 채워 넣은
	 * 뒤라, 마지막에 덮어쓰는 것으로 배율이 걸린다. 배율이 없으면 같은 값을 다시 쓰는 셈이라
	 * 아무 일도 일어나지 않는다.
	 *
	 * <h2>시차는 여기서 끝나지 않는다</h2>
	 * <p>{@code swap_rally}·순열 교환·막힘은 이 메서드 안에서 즉시 끝나 {@code on_swap}과
	 * 다음 주기 계산까지 곧바로 이어진다. 반면 {@code staggered_swap}은 이동 자체가 여러 틱에
	 * 걸쳐 일어나므로, {@link StaggeredSwapManager#beginSequence}를 부른 뒤 곧바로 돌아간다 —
	 * {@code on_swap}과 다음 주기 계산은 마지막 걸음이 끝난 뒤 그쪽에서 한다.
	 */
	private static void swapMoment(List<ServerPlayer> online, List<ServerPlayer> movers,
			ShareTeam team, TeamState state) {
		PerkSwapRules.grantSwapExemptBonus(state, online);
		if (PerkSwapRules.blocksSwap(state)) {
			announceBlockedSwap(online);
		} else if (PerkSwapRules.rallyPoint(state) && movers.size() >= MIN_SWAP_MEMBERS) {
			RallyPointManager.beginGather(team, movers, ThreadLocalRandom.current(),
					PerkSwapRules.swapExplosions(state));
		} else if (PerkSwapRules.staggered(state) && movers.size() >= MIN_SWAP_MEMBERS) {
			StaggeredSwapManager.beginSequence(team, state, movers, ThreadLocalRandom.current(),
					PerkSwapRules.swapExplosions(state));
			return;
		} else {
			swapTeamPositions(movers, ThreadLocalRandom.current(), PerkSwapRules.swapExplosions(state));
		}
		PerkSwapRules.grantOnSwap(state, online);
		state.positionSwapRemainingTicks = PerkSwapRules.nextRemainingTicks(state);
	}

	/**
	 * 자리가 바뀌지 않았음을 알린다.
	 *
	 * <p>교환에는 땅 접촉 조건이 없다. 막는 것은 {@link PerkSwapRules#blocksSwap} 이 보는
	 * {@code swap_block} 증강뿐이다.
	 */
	private static void announceBlockedSwap(List<ServerPlayer> players) {
		TitleMessenger.showTitle(players,
				Component.literal("제자리").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD),
				Component.literal("「뿌리내린 발」이 위치 교환을 막았습니다").withStyle(ChatFormatting.WHITE),
				0, SWAP_TITLE_STAY_TICKS, SWAP_TITLE_FADE_OUT_TICKS);
	}

	private static int configuredCountdownSeconds() {
		return SharedFateMod.config == null
				? DEFAULT_COUNTDOWN_SECONDS : SharedFateMod.config.positionSwapCountdownSeconds;
	}

	/**
	 * 이번 틱에 보여 줄 카운트다운 숫자. 보여 줄 게 없으면 0.
	 *
	 * <p>{@link TeamState#advancePositionSwapTick(boolean)}이 이미 1틱 깎은 뒤의 남은 틱을 받는다.
	 * 딱 1초 경계(20의 배수)일 때만 값을 돌려주므로 초당 한 번씩만 패킷이 나간다.
	 *
	 * @param remainingTicks   교환까지 남은 틱
	 * @param countdownSeconds 카운트다운 길이(초). 0 이하면 카운트다운을 끈다.
	 */
	static int countdownSecondsToShow(int remainingTicks, int countdownSeconds) {
		if (countdownSeconds <= 0 || remainingTicks <= 0) {
			return 0;
		}
		if (remainingTicks > countdownSeconds * TICKS_PER_SECOND) {
			return 0;
		}
		if (remainingTicks % TICKS_PER_SECOND != 0) {
			return 0;
		}
		return remainingTicks / TICKS_PER_SECOND;
	}

	/**
	 * 남은 초를 화면(액션바)에 띄운다.
	 */
	private static void announceCountdown(List<ServerPlayer> players, int secondsLeft) {
		Component text = Component.literal("위치 교환까지 " + secondsLeft + "초")
				.withStyle(secondsLeft <= 2 ? ChatFormatting.RED : ChatFormatting.GOLD,
						ChatFormatting.BOLD);
		TitleMessenger.showActionBar(players, text);
		for (ServerPlayer player : players) {
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
					SoundEvents.NOTE_BLOCK_HAT.value(), SoundSource.PLAYERS, 0.6F, 1.0F);
		}
	}

	/**
	 * 접속해 살아 있는 팀원 전원.
	 *
	 * <p>여기서는 아무도 빼지 않는다. 「열외」를 빼는 일은 {@link PerkSwapRules#swapParticipants}
	 * 가 이 결과를 받아서 하고, 그 결과가 순열 교환·시차·정거장 셋 모두에 그대로 넘어간다.
	 */
	private static List<ServerPlayer> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> result = new ArrayList<>();
		for (var memberId : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(memberId);
			if (player != null && !player.isRemoved() && !player.isDeadOrDying()) {
				result.add(player);
			}
		}
		return result;
	}

	static boolean swapTeamPositions(List<ServerPlayer> players, RandomGenerator random) {
		return swapTeamPositions(players, random, List.of());
	}

	static boolean swapTeamPositions(List<ServerPlayer> players, RandomGenerator random,
			List<SwapExplosionEffect> explosions) {
		if (players.size() < 2) {
			return false;
		}
		List<Position> origins = players.stream().map(Position::capture).toList();
		int[] donors = derangedDonors(players.size(), random);
		// 엔드 전투 중 엔드 밖으로 나가게 되는 사람은 제자리에 남는다. 그 사람 몫의
		// 「이동했습니다」 알림과 발밑 폭발을 함께 빼야 하므로 누가 실제로 움직였는지 적어 둔다.
		boolean[] moved = new boolean[players.size()];

		for (int index = 0; index < players.size(); index++) {
			Position destination = origins.get(donors[index]);
			if (EndFightTeleportLock.blocks(players.get(index), destination.level())) {
				EndFightTeleportLock.refuse(players.get(index));
				continue;
			}
			if (!destination.teleport(players.get(index))) {
				rollback(players, origins, index);
				Component failure = Component.literal("위치 교환에 실패해 원래 위치로 되돌렸습니다.");
				players.forEach(player -> player.sendSystemMessage(failure));
				SharedFateMod.LOGGER.warn("팀 위치 교환 중 {} 이동이 실패했습니다.",
						players.get(index).getPlainTextName());
				return false;
			}
			moved[index] = true;
		}

		// 방금 비운 자리(자기 원래 위치, origins.get(index))에서 0.5초 뒤 터진다. 이미 전원
		// 이동이 끝난 뒤라 이 자리는 항상 다른 누군가의 새 자리이기도 하다(완전한 순열이라
		// 고정점이 없으므로). 그래서 "떠난 사람만" 면역이 아니라 이 교환에 참여한 전원(=players
		// 전체)을 면역으로 둔다 — 도착한 사람도 이제 이 폭발들은 맞지 않는다.
		if (!explosions.isEmpty()) {
			Set<UUID> immune = new HashSet<>();
			for (ServerPlayer player : players) {
				immune.add(player.getUUID());
			}
			for (int index = 0; index < players.size(); index++) {
				// 제자리에 남은 사람의 자리에는 터뜨리지 않는다. 그 사람은 자리를 비운 적이
				// 없고, 폭발이 블록을 부수는 설정이면 서 있는 발밑이 파인다.
				if (!moved[index]) {
					continue;
				}
				for (SwapExplosionEffect explosion : explosions) {
					scheduleSwapExplosion(origins.get(index), immune, explosion);
				}
			}
		}

		for (int index = 0; index < players.size(); index++) {
			if (!moved[index]) {
				continue;
			}
			String donorName = players.get(donors[index]).getPlainTextName();
			ServerPlayer arrived = players.get(index);
			arrived.sendSystemMessage(Component.literal(
					"위치 교환! " + donorName + "님의 위치로 이동했습니다."));
			// 카운트다운이 0이 된 순간을 화면에서도 확인할 수 있게 짧은 타이틀을 함께 띄운다.
			TitleMessenger.showTitle(arrived,
					Component.literal("위치 교환!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
					Component.literal(donorName + "님의 위치").withStyle(ChatFormatting.WHITE),
					0, SWAP_TITLE_STAY_TICKS, SWAP_TITLE_FADE_OUT_TICKS);
		}
		return true;
	}

	/**
	 * 「폭발 교환」 한 발을 {@value SwapExplosionScheduler#DELAY_TICKS}틱(0.5초) 뒤로 예약한다.
	 *
	 * <p>즉시 터뜨리지 않는 이유와 {@code immuneParticipants}에 누가 들어가야 하는지는
	 * {@link SwapExplosionScheduler}의 클래스 문서에 있다.
	 *
	 * <p>이 클래스의 순열 교환뿐 아니라 {@link StaggeredSwapManager}(시차의 각 걸음)와
	 * {@link RallyPointManager}(정거장이 모이는 순간, 복귀 순간은 제외)도 같은 자리에서 이
	 * 메서드를 부른다.
	 */
	static void scheduleSwapExplosion(Position origin, Set<UUID> immuneParticipants,
			SwapExplosionEffect definition) {
		SwapExplosionScheduler.schedule(origin, immuneParticipants, definition);
	}

	/**
	 * 「폭발 교환」 한 발을 실제로 터뜨린다. {@link SwapExplosionScheduler#tick}이 예약 시간이
	 * 되면 부른다 — 이 클래스 밖에서 직접 부를 일은 없다.
	 *
	 * <p>불은 붙지 않는다({@code fire=false}, 정의 파일로 바꿀 수 없는 고정 규칙). 블록을
	 * 부수는지는 {@link SwapExplosionEffect#breakBlocks()}를 따라 {@code MOB}(크리퍼처럼
	 * {@code mobGriefing} 규칙을 따름) 또는 {@code NONE}(연출·피해만)을 고른다. 누가 맞고
	 * 안 맞는지, 피해가 얼마나 세지는지는 {@link SwapExplosionDamageCalculator}가 정한다.
	 */
	static void detonateSwapExplosion(Position origin, Set<UUID> exemptPlayers,
			SwapExplosionEffect definition) {
		ServerLevel level = origin.level();
		DamageSource source = Explosion.getDefaultDamageSource(level, null);
		Level.ExplosionInteraction interaction = definition.breakBlocks()
				? Level.ExplosionInteraction.MOB
				: Level.ExplosionInteraction.NONE;
		level.explode(null, source,
				new SwapExplosionDamageCalculator(exemptPlayers, definition.damageMultiplier()),
				origin.x(), origin.y(), origin.z(), definition.radius(), false, interaction);
	}

	/**
	 * 옮기다 실패했을 때 이미 옮긴 사람들을 제자리로 되돌린다.
	 *
	 * <p>아직 움직이지 않은 사람을 자기 원래 자리로 다시 보내도 결과는 같으므로, 부르는 쪽이
	 * "누가 움직였는지"를 정확히 가려낼 필요는 없다.
	 */
	static void rollback(List<ServerPlayer> players, List<Position> origins, int lastAttempted) {
		for (int index = 0; index <= lastAttempted; index++) {
			if (!origins.get(index).teleport(players.get(index))) {
				SharedFateMod.LOGGER.error("위치 교환 롤백에 실패했습니다: {}",
						players.get(index).getPlainTextName());
			}
		}
	}

	static int[] derangedDonors(int size, RandomGenerator random) {
		if (size < 2) {
			throw new IllegalArgumentException("위치 교환에는 두 명 이상이 필요합니다.");
		}
		int[] order = new int[size];
		for (int index = 0; index < size; index++) {
			order[index] = index;
		}
		for (int index = size - 1; index > 0; index--) {
			int other = random.nextInt(index + 1);
			int temporary = order[index];
			order[index] = order[other];
			order[other] = temporary;
		}

		int shift = random.nextInt(1, size);
		int[] donors = new int[size];
		for (int position = 0; position < size; position++) {
			donors[order[position]] = order[(position + shift) % size];
		}
		return donors;
	}

	/**
	 * 한 사람이 서 있던 자리. 차원까지 들고 있어 차원 간 이동도 그대로 처리된다.
	 */
	record Position(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		static Position capture(ServerPlayer player) {
			return new Position(player.level(), player.getX(), player.getY(), player.getZ(),
					player.getYRot(), player.getXRot());
		}

		/** 이 자리로 옮긴다. 보고 있던 방향까지 원래 주인의 것으로 맞춘다. */
		boolean teleport(ServerPlayer player) {
			if (refusedByEndFight(player)) {
				return true;
			}
			return player.teleportTo(level, x, y, z, Set.<Relative>of(), yaw, pitch, true);
		}

		/**
		 * 이 지점으로 옮기되 보고 있는 방향은 그대로 둔다.
		 *
		 * <p>여럿을 한곳에 모을 때 쓴다.
		 */
		boolean gather(ServerPlayer player) {
			if (refusedByEndFight(player)) {
				return true;
			}
			return player.teleportTo(level, x, y, z, Set.<Relative>of(),
					player.getYRot(), player.getXRot(), true);
		}

		/**
		 * 엔드 전투 중에 엔드 밖으로 내보내는 이동인가. 그렇다면 옮기지 않고 당사자에게 알린다.
		 *
		 * <p><b>이 자리가 마지막 방어선이다.</b> 사람을 옮기는 길은 다섯이고(순열 교환·집합·
		 * 정거장·시차·소집) 전부 이 레코드를 지난다. 부르는 쪽을 하나라도 빠뜨려도 여기서
		 * 걸린다 — 이 저장소가 「한쪽만 막으면 반드시 샌다」를 여러 번 겪은 까닭이다.
		 *
		 * <p>막힌 것을 <b>실패로 돌려주지 않는다.</b> {@code false} 를 돌려주면
		 * {@link #swapTeamPositions} 가 「이동 실패」로 보고 전원을 되돌리며 경고를 쏟고,
		 * {@link #rollback} 이 다시 이 검사에 걸려 오류 로그까지 남는다. 옮기지 않은 것은
		 * 사고가 아니라 규칙이므로 부르는 쪽은 하던 일을 그대로 마쳐야 한다.
		 *
		 * <p>그 대신 「옮겼다」는 알림이 거짓말이 되지 않게, 알림을 내보내는 쪽
		 * ({@link #swapTeamPositions}·{@link StaggeredSwapManager}·{@link RallyPointManager}·
		 * {@link RallyShardManager})은 부르기 <b>전에</b> {@link EndFightTeleportLock#blocks}
		 * 로 한 번 더 가려낸다.
		 */
		private boolean refusedByEndFight(ServerPlayer player) {
			if (!EndFightTeleportLock.blocks(player, level)) {
				return false;
			}
			EndFightTeleportLock.refuse(player);
			return true;
		}
	}
}
