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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * 실버 「시차」의 실행부. 한 틱 안에서 전원을 옮기는 대신, 팀원 한 명씩 5~10초 간격으로
 * 순서대로 옮긴다.
 *
 * <h2>자리 배정은 그대로, 실행만 나뉜다</h2>
 * <p>누가 누구의 자리로 가는지는 {@link PositionSwapManager#derangedDonors}가 만드는 평소와
 * 똑같은 무고정점 순열이다. 이 클래스가 따로 정하는 것은 <b>그 이동을 실행하는 순서</b>(별도로
 * 섞은 목록)와 <b>각 이동 사이의 간격</b>뿐이다.
 *
 * <h2>간격은 시퀀스 하나에 한 번만 굴린다</h2>
 * <p>매 걸음마다 다시 굴리지 않는다. 시퀀스를 시작할 때 5~10초(100~200틱) 사이에서 한 번
 * 뽑아 모든 걸음에 그대로 쓴다.
 *
 * <h2>진행 중에는 다음 교환 주기가 얼어붙는다</h2>
 * <p>{@link PositionSwapManager#tick}이 이 팀에 {@link #hasActiveSequence}가 참이면
 * {@code TeamState.advancePositionSwapTick} 자체를 건너뛴다. 그래서
 * {@code positionSwapRemainingTicks}는 시퀀스가 끝날 때까지 그대로 멈춰 있다가, 마지막 걸음이
 * 끝난 뒤 {@link PerkSwapRules#nextRemainingTicks}로 다시 채워진다. 실제 교환 간격이 설정값보다
 * 시퀀스 소요 시간만큼 길어진다는 뜻이다 — 의도한 부작용이다.
 *
 * <h2>도중에 나가거나 죽으면</h2>
 * <p>그 사람 차례만 건너뛴다. 이미 옮긴 사람을 되돌리지 않는다 — 실시간으로 몇 초~몇십 초가
 * 지난 뒤라 {@code swapTeamPositions}의 "실패하면 전원 롤백" 정책이 안 맞는다. 죽음은 이
 * 모드에서 팀 전멸로 이어지므로, 그 경우는 {@link #onDeath}가 진행 중이던 시퀀스를 통째로
 * 지운다.
 */
public final class StaggeredSwapManager {
	/** 걸음 사이 간격 하한. 5초다. */
	static final int MIN_GAP_TICKS = 100;
	/** 걸음 사이 간격 상한. 10초다. */
	static final int MAX_GAP_TICKS = 200;

	private static final Map<UUID, Sequence> ACTIVE = new ConcurrentHashMap<>();

	private StaggeredSwapManager() {
	}

	/** 이 팀이 지금 순차 이동을 진행 중인가. */
	public static boolean hasActiveSequence(UUID teamId) {
		return ACTIVE.containsKey(teamId);
	}

	/** 서버가 멈출 때 진행 중이던 시퀀스를 모두 지운다. */
	public static void reset() {
		ACTIVE.clear();
	}

	/** 팀이 전멸·해체될 때 그 팀의 시퀀스만 지운다. */
	public static void forget(UUID teamId) {
		ACTIVE.remove(teamId);
	}

	/**
	 * 시퀀스를 새로 시작한다. 최종 자리 배정(순열)과 실행 순서를 여기서 한 번에 정하고,
	 * 첫 걸음은 다음 {@link #tick}에서 곧바로(간격 없이) 실행된다.
	 *
	 * @param players 자리를 바꿀 사람들. {@code PositionSwapManager} 가 이미 골드 「열외」
	 *                ({@code swap_exempt})를 빼고 넘긴다 — 여기서 다시 거르지 않는다
	 */
	public static void beginSequence(ShareTeam team, TeamState state, List<ServerPlayer> players,
			RandomGenerator random, List<SwapExplosionEffect> explosions) {
		if (players.size() < 2) {
			return;
		}
		List<PositionSwapManager.Position> origins =
				players.stream().map(PositionSwapManager.Position::capture).toList();
		int[] donors = PositionSwapManager.derangedDonors(players.size(), random);
		List<Integer> order = shuffledIndices(players.size(), random);
		List<UUID> playerIds = players.stream().map(ServerPlayer::getUUID).toList();
		int gapTicks = MIN_GAP_TICKS + random.nextInt(MAX_GAP_TICKS - MIN_GAP_TICKS + 1);

		ACTIVE.put(team.teamId(),
				new Sequence(playerIds, origins, donors, order, List.copyOf(explosions), gapTicks));
	}

	/** 진행 중인 시퀀스가 있는 팀들을 한 걸음씩 밀어 준다. */
	public static void tick(MinecraftServer server) {
		// 게임 오버 카운트다운 5초 동안은 걸음을 진행하지 않는다. 회차가 이미 끝났으므로
		// 종료 직전에 사람을 옮길 이유가 없다.
		if (server == null || ACTIVE.isEmpty() || PerkChoiceSession.isActive()
				|| WorldResetCoordinator.countingDown()) {
			return;
		}
		for (UUID teamId : List.copyOf(ACTIVE.keySet())) {
			Sequence sequence = ACTIVE.get(teamId);
			if (sequence == null) {
				continue;
			}
			if (sequence.ticksUntilNext > 0) {
				sequence.ticksUntilNext--;
				continue;
			}
			stepOnce(server, teamId, sequence);
		}
	}

	private static void stepOnce(MinecraftServer server, UUID teamId, Sequence sequence) {
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(teamId);
		TeamState state = manager.stateByTeamId(teamId);
		if (team == null || state == null) {
			ACTIVE.remove(teamId);
			return;
		}

		int index = sequence.order.get(sequence.cursor);
		UUID moverId = sequence.playerIds.get(index);
		ServerPlayer mover = server.getPlayerList().getPlayer(moverId);
		if (mover != null && !mover.isRemoved() && !mover.isDeadOrDying()) {
			PositionSwapManager.Position origin = sequence.origins.get(index);
			PositionSwapManager.Position destination = sequence.origins.get(sequence.donors[index]);
			// 여기 든 좌표는 시퀀스를 시작할 때 찍은 것이다. 걸음 사이가 5~10초라, 그사이에
			// 팀이 엔드로 소환됐으면 남은 걸음이 전부 엔드 밖 옛 자리로 간다. 그 걸음은
			// 건너뛴다 — 옮기지 않았으니 「이동했습니다」와 옛 자리 폭발도 함께 뺀다.
			if (EndFightTeleportLock.blocks(mover, destination.level())) {
				EndFightTeleportLock.refuse(mover);
			} else if (destination.teleport(mover)) {
				mover.sendSystemMessage(Component.literal("위치 교환! 순서가 되어 이동했습니다."));
				// 이 걸음에서 자리가 바뀐 사람은 이 사람뿐이라, 면역도 이 사람 하나로 충분하다.
				for (SwapExplosionEffect explosion : sequence.explosions) {
					PositionSwapManager.scheduleSwapExplosion(origin, Set.of(moverId), explosion);
				}
			} else {
				SharedFateMod.LOGGER.warn("시차 진행 중 {} 이동이 실패해 이번 걸음을 건너뜁니다.",
						mover.getPlainTextName());
			}
		} else {
			SharedFateMod.LOGGER.info("시차 진행 중 팀원이 자리를 비워 이번 걸음을 건너뜁니다.");
		}

		sequence.cursor++;
		if (sequence.cursor < sequence.order.size()) {
			sequence.ticksUntilNext = sequence.gapTicks;
			return;
		}
		finishSequence(server, team, state);
		ACTIVE.remove(teamId);
	}

	/**
	 * 마지막 걸음이 끝났다. {@code on_swap}을 얹고 다음 주기를 다시 채운다.
	 *
	 * <p>여기서 쓰는 명단은 <b>열외까지 포함한 팀 전원</b>이다. 열외가 빠지는 것은 자리를 바꾸는
	 * 명단이지 {@code on_swap} 이 아니다({@code PerkSwapRules.swapParticipants} 참고). 열외
	 * 당사자의 이동 속도 보너스는 이미 교환 시점에 {@code PositionSwapManager}가 주고 지나갔다.
	 */
	private static void finishSequence(MinecraftServer server, ShareTeam team, TeamState state) {
		List<ServerPlayer> online = onlineMembers(server, team);
		PerkSwapRules.grantOnSwap(state, online);
		state.positionSwapRemainingTicks = PerkSwapRules.nextRemainingTicks(state);
	}

	/** {@code LivingEntityEvents.AFTER_DEATH} 에 붙는 지점. 팀이 전멸하면 진행 중이던 시퀀스를 지운다. */
	public static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof ServerPlayer player)) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		if (team != null) {
			forget(team.teamId());
		}
	}

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

	/** 0..size-1 을 무작위로 섞은 목록. {@code derangedDonors} 와 같은 피셔-예이츠 방식이지만
	 * 자리 배정과는 다른 목적(실행 순서)이라 따로 굴린다. */
	static List<Integer> shuffledIndices(int size, RandomGenerator random) {
		Integer[] order = new Integer[size];
		for (int i = 0; i < size; i++) {
			order[i] = i;
		}
		for (int i = size - 1; i > 0; i--) {
			int other = random.nextInt(i + 1);
			Integer temporary = order[i];
			order[i] = order[other];
			order[other] = temporary;
		}
		return List.of(order);
	}

	/** 진행 중인 시퀀스 하나. 저장하지 않는다 — 서버가 다시 뜨면 처음부터 다시 시작해도 된다. */
	private static final class Sequence {
		final List<UUID> playerIds;
		final List<PositionSwapManager.Position> origins;
		final int[] donors;
		final List<Integer> order;
		final List<SwapExplosionEffect> explosions;
		final int gapTicks;
		int cursor;
		int ticksUntilNext;

		Sequence(List<UUID> playerIds, List<PositionSwapManager.Position> origins, int[] donors,
				List<Integer> order, List<SwapExplosionEffect> explosions, int gapTicks) {
			this.playerIds = playerIds;
			this.origins = origins;
			this.donors = donors;
			this.order = order;
			this.explosions = explosions;
			this.gapTicks = gapTicks;
			this.cursor = 0;
			this.ticksUntilNext = 0;
		}
	}
}
