package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.perk.PerkSwapRules;
import com.sharedfate.perk.effect.GatherEffect;
import com.sharedfate.perk.effect.ProximityEffect;
import com.sharedfate.perk.effect.ProximityRangeEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * {@code gather} 증강의 실행부. 흩어진 팀을 한곳으로 끌어모은다.
 *
 * <p>{@link GatherEffect} 는 "얼마나 멀면, 얼마나 쉬고, 무엇을 얹는가"만 알고, "지금 실제로
 * 얼마나 떨어져 있는가"와 "어디로 모을 것인가"는 여기서 정한다.
 *
 * <h2>1초에 한 번만 잰다</h2>
 * <p>거리 재기는 팀원 수의 제곱만큼 돈다. 매 틱 돌 이유가 없다. 사람이 1초 만에 기준 거리를
 * 넘어 도망칠 수도 없으므로 {@link #CHECK_INTERVAL_TICKS} 마다 한 번이면 충분하다.
 *
 * <h2>시각의 기준</h2>
 * <p>오버월드 게임 시간이 아니라 {@link #tick} 이 불릴 때마다 1씩 올리는 자체 카운터를 쓴다. 재우는 시간이 얼마 남았는지는
 * 저장할 값이 아니므로 서버를 껐다 켜면 0 에서 다시 시작한다. {@link #reset} 이 남은 기억을
 * 함께 비우므로 어긋날 여지가 없다.
 *
 * <h2>증강 선택 중에는 쉰다</h2>
 * <p>강제 증강 선택 세션이 살아 있으면 시간이 멈춰 있고 팀원은 창에 갇혀 있다. 그 사이에
 * 끌려가면 창을 닫자마자 낯선 곳에 서 있게 되고, 실명과 구속까지 뒤집어쓴다. 세션이 사는
 * 동안에는 카운터조차 세우고 지나간다. {@link PositionSwapManager#tick} 과 같은 정책이다.
 *
 * <h2>모이는 지점</h2>
 * <p>팀원 중 <b>무작위 한 명의 현재 위치</b>다. 그 사람은 움직이지 않는다.
 *
 * <h2>거리를 넓히는 증강</h2>
 * <p>{@link ProximityRangeEffect} 를 가진 팀은 정의에 적힌 거리 대신 배율을 먹인 거리로 잰다.
 * <b>{@code proximity} 와 {@code gather} 에 같은 배율을 함께 먹인다.</b> 한쪽만 넓히면 늘어난
 * 보상 거리에 닿기 전에 집합이 먼저 걸려, 넓힌 거리를 영영 쓸 수 없다.
 */
public final class TeamGathering {
	/** 거리를 재는 주기. 1초다. */
	static final int CHECK_INTERVAL_TICKS = 20;
	/** 모으는 데 필요한 최소 인원. 혼자라면 흩어질 수가 없다. */
	private static final int MIN_MEMBERS = 2;
	/** 모인 직후 띄우는 타이틀이 화면에 남는 시간과 사라지는 시간(틱). */
	private static final int TITLE_STAY_TICKS = 30;
	private static final int TITLE_FADE_OUT_TICKS = 10;

	/** 팀별로 언제까지 판정을 쉬는가. 값은 {@link #now} 기준의 절대 시각이다. */
	private static final Map<UUID, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();

	/** 자체 틱 카운터. {@link #tick} 이 부를 때마다 1씩 오른다. */
	private static volatile long now;

	private static boolean warned;

	private TeamGathering() {
	}

	/** 지금까지 센 틱 수. 테스트와 진단용. */
	static long currentTick() {
		return now;
	}

	/**
	 * 팀마다 흩어진 정도를 재고 필요하면 모은다.
	 *
	 * <p>서버 틱 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다. 판정 주기가 아닌
	 * 틱에는 카운터만 올리고 끝난다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null || PerkChoiceSession.isActive()) {
			return;
		}
		long time = ++now;
		if (time % CHECK_INTERVAL_TICKS != 0) {
			return;
		}
		try {
			// 이미 지난 기억은 여기서 함께 버린다. 해체된 팀의 기억도 이 길로 사라진다.
			COOLDOWN_UNTIL.values().removeIf(until -> until <= time);

			TeamManager manager = TeamManager.get(server);
			for (ShareTeam team : manager.allTeams()) {
				TeamState state = manager.stateByTeamId(team.teamId());
				if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
					continue;
				}
				List<ProximityEffect> proximities = PerkSwapRules.proximities(state);
				List<GatherEffect> gathers = PerkSwapRules.gathers(state);
				if (proximities.isEmpty() && gathers.isEmpty()) {
					continue;
				}
				// 두 판정이 같은 배율을 써야 하므로 한 번만 구해 둘 다에 넘긴다.
				double range = rangeMultiplier(state);
				if (!proximities.isEmpty()) {
					tickProximity(server, team, proximities, range);
				}
				if (gathers.isEmpty()) {
					continue;
				}
				tickTeam(server, team, gathers, time, range);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 서버가 멈출 때 기억을 비운다. 다음 월드로 넘어가지 않게 한다. */
	public static void reset() {
		COOLDOWN_UNTIL.clear();
		now = 0;
		warned = false;
	}

	// ------------------------------------------------------------------ 팀 하나

	private static void tickTeam(MinecraftServer server, ShareTeam team,
			List<GatherEffect> gathers, long time, double range) {
		Long until = COOLDOWN_UNTIL.get(team.teamId());
		if (until != null && time < until) {
			return;
		}
		List<ServerPlayer> online = onlineMembers(server, team);
		if (online.size() < MIN_MEMBERS) {
			return;
		}
		for (GatherEffect gather : gathers) {
			if (!scattered(online, ProximityRangeEffect.scale(gather.distance(), range))) {
				continue;
			}
			// 옮기다 실패하더라도 재우는 시간은 똑같이 건다. 실패한 판정을 1초 뒤에 다시
			// 시도하면 같은 실패가 초당 한 번씩 쏟아진다.
			COOLDOWN_UNTIL.put(team.teamId(), time + gather.cooldownTicks());
			gatherTeam(online, gather, ThreadLocalRandom.current());
			// 한 번 모으면 다른 gather 정의로 또 옮길 이유가 없다.
			return;
		}
	}

	/**
	 * 팀원 전원이 붙어 있으면 {@code proximity} 효과를 다시 얹는다.
	 *
	 * <p>혼자면 붙어 있는 것으로 본다. 조건을 "전원이 가깝다"로 읽으면 비교할 상대가 없는
	 * 한 명짜리 팀은 언제나 참이다.
	 *
	 * <p>조건이 깨졌을 때 걷어내지 않는다. 얹는 효과의 지속시간이 판정 주기보다 조금 길
	 * 뿐이라 저절로 사라진다.
	 */
	private static void tickProximity(MinecraftServer server, ShareTeam team,
			List<ProximityEffect> proximities, double range) {
		List<ServerPlayer> online = onlineMembers(server, team);
		if (online.isEmpty()) {
			return;
		}
		for (ProximityEffect proximity : proximities) {
			if (online.size() >= MIN_MEMBERS
					&& scattered(online, ProximityRangeEffect.scale(proximity.distance(), range))) {
				continue;
			}
			for (ServerPlayer member : online) {
				proximity.grantTo(member);
			}
		}
	}

	/** 지금 이 사람들이 기준 거리를 넘어 흩어져 있는가. */
	private static boolean scattered(List<ServerPlayer> players, double distance) {
		List<Object> levels = new ArrayList<>(players.size());
		List<Vec3> positions = new ArrayList<>(players.size());
		for (ServerPlayer player : players) {
			levels.add(player.level());
			positions.add(player.position());
		}
		return anyPairTooFar(levels, positions, distance);
	}

	/**
	 * 아무 두 사람 사이가 기준을 넘는가.
	 *
	 * <p><b>차원이 다르면 거리와 무관하게 참이다.</b> 좌표만 보면 네더의 (0,0) 과 오버월드의
	 * (0,0) 이 붙어 있는 것으로 보이는데, 실제로는 서로 닿을 수 없는 자리다.
	 *
	 * @param levels    각자가 있는 차원. 같은 차원인지는 객체 동일성으로 본다
	 * @param positions 각자의 좌표. {@code levels} 와 순서가 같아야 한다
	 * @param distance  기준 거리(블록)
	 */
	static boolean anyPairTooFar(List<?> levels, List<Vec3> positions, double distance) {
		if (levels.size() != positions.size() || positions.size() < MIN_MEMBERS || !(distance > 0.0)) {
			return false;
		}
		// 제곱끼리 비교하면 제곱근을 뽑지 않아도 된다.
		double limitSquared = distance * distance;
		for (int first = 0; first < positions.size(); first++) {
			for (int second = first + 1; second < positions.size(); second++) {
				if (levels.get(first) != levels.get(second)) {
					return true;
				}
				if (positions.get(first).distanceToSqr(positions.get(second)) > limitSquared) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 실제로 모으기

	/**
	 * 무작위 한 명의 자리로 나머지를 끌어온다.
	 *
	 * <p>옮기다 한 명이라도 실패하면 이미 옮긴 사람들을 제자리로 되돌리고 아무 효과도 얹지
	 * 않는다. {@code swapTeamPositions} 의 롤백 정책 그대로다.
	 */
	private static void gatherTeam(List<ServerPlayer> players, GatherEffect gather,
			RandomGenerator random) {
		// 엔드 전투 중에는 기준점을 엔드 안으로 돌린다. 차원이 다르면 거리와 무관하게
		// 「흩어졌다」이고(anyPairTooFar) 「운명 공동체」의 재사용 대기가 20틱(1초)이라, 팀이
		// 엔드 안팎으로 갈린 동안 이 집합이 초당 한 번 발동한다. 기준점이 밖으로 뽑히면
		// 엔드 안 사람은 EndFightTeleportLock 에 걸려 못 움직이고 팀은 갈린 채 남아,
		// 같은 판정이 1초 뒤 또 돌아온다. 안으로 돌리면 그 자리에서 팀이 엔드에 합쳐져 끝난다.
		int anchor = EndFightTeleportLock.preferEndAnchor(players, random.nextInt(players.size()));
		List<PositionSwapManager.Position> origins =
				players.stream().map(PositionSwapManager.Position::capture).toList();
		PositionSwapManager.Position destination = origins.get(anchor);

		for (int index = 0; index < players.size(); index++) {
			if (index == anchor || destination.gather(players.get(index))) {
				continue;
			}
			PositionSwapManager.rollback(players, origins, index);
			Component failure = Component.literal("팀을 모으지 못해 원래 위치로 되돌렸습니다.");
			players.forEach(player -> player.sendSystemMessage(failure));
			SharedFateMod.LOGGER.warn("팀 집합 중 {} 이동이 실패했습니다.",
					players.get(index).getPlainTextName());
			return;
		}

		announce(players, players.get(anchor).getPlainTextName());
		// 이미 그 자리에 있던 기준점에게도 얹는다.
		for (ServerPlayer player : players) {
			gather.grantTo(player);
		}
	}

	private static void announce(List<ServerPlayer> players, String anchorName) {
		Component message = Component.literal("운명이 팀을 한곳으로 끌어당겼습니다. (" + anchorName + "님 위치)");
		for (ServerPlayer player : players) {
			player.sendSystemMessage(message);
		}
		TitleMessenger.showTitle(players,
				Component.literal("집합!").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD),
				Component.literal(anchorName + "님의 위치").withStyle(ChatFormatting.WHITE),
				0, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS);
	}

	// ------------------------------------------------------------------ 거리 배율

	/**
	 * 이 팀이 거리에 곱할 배율. {@code proximity_range} 가 없으면 1.0.
	 *
	 * <p>보유 증강의 효과에 켜진 세트 효과를 이어 붙여 훑는다. {@link PerkSetEffects} 가 적어 둔
	 * 그 모양 그대로다 — 세트 쪽을 빠뜨리면 세트로 얻은 배율이 조용히 무시된다.
	 *
	 * <p>정의에 적힌 거리에 이 값을 먹이는 일은 {@link ProximityRangeEffect#scale} 이 한다.
	 */
	public static double rangeMultiplier(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return 1.0;
		}
		List<PerkEffect> effects = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk != null) {
				effects.addAll(perk.effects());
			}
		}
		effects.addAll(PerkSetEffects.activeEffectsOf(state));
		return ProximityRangeEffect.multiplierOf(effects);
	}

	// ------------------------------------------------------------------ 도우미

	private static List<ServerPlayer> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> result = new ArrayList<>();
		for (UUID memberId : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(memberId);
			if (player != null && !player.isRemoved() && !player.isDeadOrDying()) {
				result.add(player);
			}
		}
		return result;
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"팀 집합 증강을 처리하지 못해 이번 점검은 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		reset();
	}
}
