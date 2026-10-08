package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkSwapRules;
import com.sharedfate.perk.effect.RallyShardEffect;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;

/**
 * 프리즘 「소집의 조각」의 실행부. 조각을 우클릭한 사람의 자리로 나머지 팀원을 불러 모은다.
 *
 * <h2>도착 지점은 실시간으로 따라간다</h2>
 * <p>목적지는 <b>끌려가는 그 순간의 소환자 위치</b>다. 우클릭한 자리를 기억해 두지 않는다.
 * 그래서 소환자가 걸어가면 팀원은 그 뒤를 따라 도착하고, 순차 소환(시차)이면 한 명씩 다른
 * 자리에 떨어진다. 소환자가 나가거나 죽으면 남은 걸음을 버린다 — 기억해 둔 좌표가 없으므로
 * 「주인 없는 소환」이 성립하지 않는다.
 *
 * <h2>「시차」를 가지면 순차가 된다</h2>
 * <p>기본은 전원이 동시에 굳었다가 동시에 끌려온다. {@code staggered_swap} 을 가진 팀은
 * <b>한 명씩</b> 굳고 끌려오기를 되풀이한다 — 굳는 시간 자체가 걸음 사이의 간격이 되므로
 * {@link StaggeredSwapManager} 의 5~10초 간격은 쓰지 않는다.
 *
 * <h2>교환 효과는 도착하는 사람에게 그때그때 건다</h2>
 * <p>{@link PerkSwapRules#grantOnSwap} 이 증강의 {@code on_swap} 과 <b>교환 세트 2·3단계</b>를
 * 같은 길로 태운다. 순차 소환에서 마지막에 한꺼번에 걸면 먼저 도착한 사람이 그동안 무방비가
 * 되므로, 도착하는 사람에게 즉시 건다. 소환자는 마지막 걸음이 끝날 때 함께 받는다.
 *
 * <h2>굳힘은 완전한 고정이 아니다</h2>
 * <p>26.2 에 「움직임을 서버에서 못박는」 방법이 없다. 서버가 매 틱 위치를 되돌리면 클라이언트
 * 예측과 부딪혀 몸이 떨린다. 그래서 구속 255를 걸어 <b>사실상 걸을 수 없게</b>만 한다.
 * 시선은 자유롭고 제자리 점프는 된다.
 *
 * <h2>안전 검사를 하지 않는다</h2>
 * <p>소환자가 용암 위에 서 있어도 그대로 끌어온다. 체력이 공유라 <b>버튼 한 번으로 팀이 전멸할
 * 수 있고</b>, 전멸하면 월드가 지워진다. 이것은 빠뜨린 것이 아니라 정해진 규칙이다.
 */
public final class RallyShardManager {
	/** 파티클을 몇 틱마다 보낼 것인가. 매 틱 보내면 패킷만 늘고 보기에는 같다. */
	private static final int PARTICLE_PERIOD_TICKS = 2;
	/** 한 번에 띄우는 파티클 수. */
	private static final int PARTICLE_COUNT = 8;
	/** 파티클이 퍼지는 반지름(칸). 몸 크기에 맞춘다. */
	private static final double PARTICLE_SPREAD = 0.4;
	/** 굳히는 구속의 세기. 걸음이 사실상 0이 된다. */
	private static final int FREEZE_AMPLIFIER = 255;
	/** 도착 타이틀이 화면에 남는 시간과 사라지는 시간(틱). */
	private static final int TITLE_STAY_TICKS = 30;
	private static final int TITLE_FADE_OUT_TICKS = 10;
	/** 소환에 필요한 최소 인원(소환자 포함). */
	public static final int MIN_MEMBERS = 2;

	private static final Map<UUID, Summon> ACTIVE = new ConcurrentHashMap<>();

	private RallyShardManager() {
	}

	/** 이 팀이 지금 소환을 진행 중인가. */
	public static boolean hasActiveSummon(UUID teamId) {
		return ACTIVE.containsKey(teamId);
	}

	/** 서버가 멈출 때 진행 중이던 소환을 모두 지운다. */
	public static void reset() {
		ACTIVE.clear();
	}

	/** 팀이 전멸·해체될 때 그 팀의 소환만 지운다. */
	public static void forget(UUID teamId) {
		ACTIVE.remove(teamId);
	}

	/**
	 * 소환을 시작한다.
	 *
	 * <p>이미 진행 중이면 시작하지 않는다 — 조각에 쿨타임이 걸리므로 정상적으로는 겹치지
	 * 않지만, 팀에 조각이 둘 이상 있는 상황(「요행」 등으로 같은 증강을 두 번 받는 일은 없으나
	 * 조각을 잃고 다시 받는 길이 열릴 수 있다)을 대비한다.
	 *
	 * @return 실제로 시작했으면 true
	 */
	public static boolean begin(ShareTeam team, ServerPlayer summoner, List<ServerPlayer> targets,
			RallyShardEffect effect, boolean staggered, List<SwapExplosionEffect> explosions,
			RandomGenerator random) {
		if (team == null || summoner == null || targets.isEmpty() || effect == null) {
			return false;
		}
		if (ACTIVE.containsKey(team.teamId())) {
			return false;
		}

		List<UUID> order = new ArrayList<>(targets.stream().map(ServerPlayer::getUUID).toList());
		if (staggered && order.size() > 1) {
			shuffle(order, random);
		}
		Summon summon = new Summon(summoner.getUUID(), List.copyOf(order), staggered,
				effect.freezeTicks(), List.copyOf(explosions));
		ACTIVE.put(team.teamId(), summon);

		// 첫 굳힘. 동시 소환이면 전원, 순차면 첫 사람 하나다.
		freezeCurrent(summoner.level().getServer(), summon);
		announceStart(summoner, targets, summon);
		return true;
	}

	/** 진행 중인 소환이 있는 팀들을 한 틱씩 밀어 준다. */
	public static void tick(MinecraftServer server) {
		// 증강 선택 중과 게임 오버 카운트다운 동안에는 진행하지 않는다. 위치 교환이 멈추는
		// 이유와 같다 — 창에 갇힌 사람을 옮기거나, 끝난 회차에서 사람을 옮길 이유가 없다.
		if (server == null || ACTIVE.isEmpty() || PerkChoiceSession.isActive()
				|| WorldResetCoordinator.countingDown()) {
			return;
		}
		for (UUID teamId : List.copyOf(ACTIVE.keySet())) {
			Summon summon = ACTIVE.get(teamId);
			if (summon == null) {
				continue;
			}
			stepTeam(server, teamId, summon);
		}
	}

	private static void stepTeam(MinecraftServer server, UUID teamId, Summon summon) {
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(teamId);
		TeamState state = manager.stateByTeamId(teamId);
		ServerPlayer summoner = server.getPlayerList().getPlayer(summon.summonerId);
		if (team == null || state == null || !alive(summoner)) {
			// 소환자가 사라지면 목적지가 없다. 기억해 둔 좌표가 없으므로 남은 걸음을 버린다.
			ACTIVE.remove(teamId);
			return;
		}

		if (summon.ticksLeft > 0) {
			summon.ticksLeft--;
			if (summon.ticksLeft % PARTICLE_PERIOD_TICKS == 0) {
				showFreezeParticles(server, summon);
			}
			return;
		}

		if (summon.staggered) {
			pullOne(server, state, summoner, summon);
		} else {
			pullAll(server, state, summoner, summon);
		}

		if (summon.cursor >= summon.order.size()) {
			finish(state, summoner);
			ACTIVE.remove(teamId);
			return;
		}
		// 순차 소환은 다음 사람을 이어서 굳힌다. 굳는 시간이 곧 걸음 사이의 간격이다.
		summon.ticksLeft = summon.freezeTicks;
		freezeCurrent(server, summon);
	}

	/** 동시 소환. 대상 전원을 한 틱에 끌어온다. */
	private static void pullAll(MinecraftServer server, TeamState state, ServerPlayer summoner,
			Summon summon) {
		List<ServerPlayer> arrived = new ArrayList<>();
		for (UUID targetId : summon.order) {
			ServerPlayer target = server.getPlayerList().getPlayer(targetId);
			if (pull(summoner, target, summon)) {
				arrived.add(target);
			}
		}
		summon.cursor = summon.order.size();
		PerkSwapRules.grantOnSwap(state, arrived);
	}

	/** 순차 소환. 지금 차례인 한 명만 끌어온다. */
	private static void pullOne(MinecraftServer server, TeamState state, ServerPlayer summoner,
			Summon summon) {
		UUID targetId = summon.order.get(summon.cursor);
		ServerPlayer target = server.getPlayerList().getPlayer(targetId);
		if (pull(summoner, target, summon)) {
			PerkSwapRules.grantOnSwap(state, List.of(target));
		}
		summon.cursor++;
	}

	/**
	 * 한 사람을 소환자의 <b>지금 자리</b>로 옮긴다.
	 *
	 * <p>떠난 자리에는 「폭발 교환」을 예약한다. 면역은 떠난 사람과 소환자 둘이다 — 도착 지점이
	 * 떠난 자리와 가까울 수 있어 소환자까지 넣어 둔다.
	 *
	 * @return 실제로 옮겼으면 true
	 */
	private static boolean pull(ServerPlayer summoner, ServerPlayer target, Summon summon) {
		if (!alive(target)) {
			SharedFateMod.LOGGER.info("소집 진행 중 팀원이 자리를 비워 한 명을 건너뜁니다.");
			return false;
		}
		PositionSwapManager.Position origin = PositionSwapManager.Position.capture(target);
		PositionSwapManager.Position destination = PositionSwapManager.Position.capture(summoner);
		// 엔드 전투 중에 소환자가 엔드 밖에 있으면 이 한 걸음이 팀원을 엔드에서 빼낸다.
		// 보통은 PerkRallyShard 가 조각을 누르는 자리에서 미리 거절하지만, 누른 뒤 소환자가
		// 엔드를 나가는 길(순차 소환은 여러 틱에 걸친다)이 남아 있어 여기서도 가린다.
		if (EndFightTeleportLock.blocks(target, destination.level())) {
			EndFightTeleportLock.refuse(target);
			return false;
		}
		if (!destination.gather(target)) {
			SharedFateMod.LOGGER.warn("소집 중 {} 이동이 실패해 건너뜁니다.", target.getPlainTextName());
			return false;
		}

		target.removeEffect(MobEffects.SLOWNESS);
		for (SwapExplosionEffect explosion : summon.explosions) {
			PositionSwapManager.scheduleSwapExplosion(origin,
					Set.of(target.getUUID(), summoner.getUUID()), explosion);
		}

		ServerLevel level = target.level();
		level.playSound(null, target.getX(), target.getY(), target.getZ(),
				SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 0.8F, 1.0F);
		TitleMessenger.showTitle(target,
				Component.literal("소집!").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD),
				Component.literal(summoner.getPlainTextName() + "님의 자리")
						.withStyle(ChatFormatting.WHITE),
				0, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS);
		return true;
	}

	/** 마지막 걸음이 끝났다. 소환자도 교환 효과를 받는다. */
	private static void finish(TeamState state, ServerPlayer summoner) {
		PerkSwapRules.grantOnSwap(state, List.of(summoner));
	}

	/**
	 * 이번 차례에 굳어야 하는 사람들에게 구속을 건다.
	 *
	 * <p>지속시간을 굳는 시간보다 넉넉히 잡아 둔다. 정확히 맞추면 마지막 틱에 한 걸음 움직이는
	 * 일이 생기고, 어차피 도착하는 순간 {@link #pull} 이 걷어낸다.
	 */
	private static void freezeCurrent(MinecraftServer server, Summon summon) {
		if (server == null) {
			return;
		}
		for (ServerPlayer target : currentTargets(server, summon)) {
			target.addEffect(new MobEffectInstance(MobEffects.SLOWNESS,
					summon.freezeTicks + PARTICLE_PERIOD_TICKS * 5, FREEZE_AMPLIFIER,
					false, false, false));
			TitleMessenger.showActionBar(target, Component
					.literal("[증강] 소집됩니다 — 움직일 수 없습니다")
					.withStyle(ChatFormatting.AQUA));
		}
	}

	private static void showFreezeParticles(MinecraftServer server, Summon summon) {
		for (ServerPlayer target : currentTargets(server, summon)) {
			ServerLevel level = target.level();
			level.sendParticles(RallyShardEffect.particle(),
					target.getX(), target.getY() + 1.0, target.getZ(),
					PARTICLE_COUNT, PARTICLE_SPREAD, PARTICLE_SPREAD, PARTICLE_SPREAD, 0.0);
		}
	}

	/** 지금 굳어 있어야 하는 사람들. 동시 소환이면 남은 전원, 순차면 지금 차례 하나다. */
	private static List<ServerPlayer> currentTargets(MinecraftServer server, Summon summon) {
		List<ServerPlayer> result = new ArrayList<>();
		if (summon.staggered) {
			if (summon.cursor < summon.order.size()) {
				ServerPlayer one = server.getPlayerList().getPlayer(summon.order.get(summon.cursor));
				if (alive(one)) {
					result.add(one);
				}
			}
			return result;
		}
		for (UUID id : summon.order) {
			ServerPlayer target = server.getPlayerList().getPlayer(id);
			if (alive(target)) {
				result.add(target);
			}
		}
		return result;
	}

	private static void announceStart(ServerPlayer summoner, List<ServerPlayer> targets,
			Summon summon) {
		String tail = summon.staggered ? " (한 명씩 옵니다)" : "";
		summoner.sendSystemMessage(Component.literal(
				"[증강] 소집의 조각! " + targets.size() + "명을 부릅니다." + tail));
		ServerLevel level = summoner.level();
		level.playSound(null, summoner.getX(), summoner.getY(), summoner.getZ(),
				SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.7F, 1.4F);
	}

	private static boolean alive(ServerPlayer player) {
		return player != null && !player.isRemoved() && !player.isDeadOrDying();
	}

	/**
	 * {@code LivingEntityEvents.AFTER_DEATH} 에 붙는 지점.
	 *
	 * <p>죽음은 이 모드에서 팀 전멸로 이어지므로 진행 중이던 소환을 통째로 지운다.
	 */
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
			// 전멸은 회차의 끝이다. 다음 회차에 「분명 안 썼는데 못 쓴다」가 되면 안 된다.
			RallyShardCooldown.forget(team.teamId());
		}
	}

	/** 목록을 제자리에서 섞는다. 피셔-예이츠. */
	static void shuffle(List<UUID> ids, RandomGenerator random) {
		for (int index = ids.size() - 1; index > 0; index--) {
			int other = random.nextInt(index + 1);
			UUID temporary = ids.get(index);
			ids.set(index, ids.get(other));
			ids.set(other, temporary);
		}
	}

	/**
	 * 진행 중인 소환 하나. 저장하지 않는다 — 서버가 다시 뜨면 조각을 다시 쓰면 된다.
	 *
	 * <p>목적지를 들고 있지 않다는 점이 {@link StaggeredSwapManager} 의 시퀀스와 가장 크게
	 * 다르다. 그쪽은 시작할 때 자리를 전부 캡처해 두지만, 소환은 언제나 소환자의 <b>지금</b>
	 * 자리로 간다.
	 */
	private static final class Summon {
		final UUID summonerId;
		final List<UUID> order;
		final boolean staggered;
		final int freezeTicks;
		final List<SwapExplosionEffect> explosions;
		int cursor;
		int ticksLeft;

		Summon(UUID summonerId, List<UUID> order, boolean staggered, int freezeTicks,
				List<SwapExplosionEffect> explosions) {
			this.summonerId = summonerId;
			this.order = order;
			this.staggered = staggered;
			this.freezeTicks = freezeTicks;
			this.explosions = explosions;
			this.cursor = 0;
			this.ticksLeft = freezeTicks;
		}
	}
}
