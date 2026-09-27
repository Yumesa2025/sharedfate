package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 엔더 드래곤 전투를 지휘한다. 엔드 입장 감지 · 전원 소환 · 드래곤 강화 · 시련 타이머.
 *
 * <h2>엔드는 나갈 수 없다</h2>
 *
 * <p>드래곤을 잡기 전에 엔드를 나가는 길은 죽는 것뿐이고, 이 모드에서 그건 곧 전멸이자 월드
 * 초기화다. 그래서 「전원 이탈」 같은 종료 조건은 두지 않는다 — 일어날 수 없는 상황을 위한
 * 코드는 시험할 수도 없고 다음 사람이 읽을 때 헷갈린다. 끝나는 길은 <b>드래곤 사망</b>과
 * <b>팀 전멸</b> 둘뿐이다.
 *
 * <h2>왜 전원을 부르는가</h2>
 *
 * <p>한 명만 들어가면 나머지는 다른 차원에서 공유 체력만 깎이는 것을 구경하게 된다. 최종 보스는
 * 팀이 함께 서야 하는 자리다. 다만 네더에서 채굴하던 사람 화면이 아무 말 없이 바뀌면 버그처럼
 * 보이므로 잠깐 예고하고 옮긴다. 확인을 묻지는 않는다 — 엔더런에서 엔드 입장은 원래 돌아올 수
 * 없는 문턱이다.
 */
public final class DragonTrialManager {
	/** 소환까지 기다리는 시간. 예고이지 말리는 장치가 아니다. */
	private static final int SUMMON_DELAY_TICKS = 60;
	/** 도착 직후 무적. 떨어지자마자 브레스에 맞아 팀이 절반 깎이는 것을 막는다. */
	private static final int ARRIVAL_GRACE_TICKS = 60;
	/** 엔드 섬 가장자리. 흑요석 기둥과 크리스탈 사거리를 피한다. */
	private static final double ARRIVAL_RADIUS = 40.0;

	private static final net.minecraft.resources.Identifier HEALTH_MODIFIER_ID =
			SharedFateMod.id("trial/dragon_health");

	private static final Map<UUID, DragonTrialSession> SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> PENDING_SUMMON = new HashMap<>();

	private DragonTrialManager() {
	}

	/**
	 * 엔드에 팀원이 들어왔는지 본다.
	 *
	 * <p>차원 이동 이벤트를 쓰지 않고 매 틱 엔드의 사람을 훑는다. Fabric 에 쓸 만한 월드 변경
	 * 이벤트가 없기도 하고, 이쪽이 <b>어떻게 들어왔든</b> 잡힌다 — 포털이든 명령이든 재접속이든.
	 * 엔드에 있는 사람은 많아야 팀 인원이라 훑는 비용도 없다.
	 */
	private static void detectArrival(MinecraftServer server, ServerLevel end, long now) {
		for (ServerPlayer player : end.players()) {
			ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
			if (team == null) {
				continue;
			}
			UUID teamId = team.teamId();
			if (SESSIONS.containsKey(teamId) || PENDING_SUMMON.containsKey(teamId)) {
				continue;
			}
			PENDING_SUMMON.put(teamId, now + SUMMON_DELAY_TICKS);
			for (ServerPlayer member : membersOf(server, team)) {
				if (member.level().dimension() != Level.END) {
					TitleMessenger.showTitle(member, Component.literal("엔드로 이동합니다"),
							Component.literal("팀이 최종 보스에 들어섰습니다"), 5, 40, 10);
				}
			}
		}
	}

	/** 매 틱. 소환 카운트다운과 시련 타이머를 돌린다. */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null || SharedFateMod.config.dragonHealthPerMember <= 0) {
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			return;
		}
		long now = end.getGameTime();
		detectArrival(server, end, now);
		tickPendingSummons(server, end, now);
		tickSessions(server, end, now);
	}

	private static void tickPendingSummons(MinecraftServer server, ServerLevel end, long now) {
		if (PENDING_SUMMON.isEmpty()) {
			return;
		}
		List<UUID> due = new ArrayList<>();
		PENDING_SUMMON.forEach((teamId, at) -> {
			if (now >= at) {
				due.add(teamId);
			}
		});
		for (UUID teamId : due) {
			PENDING_SUMMON.remove(teamId);
			ShareTeam team = TeamManager.get(server).teamById(teamId);
			if (team == null) {
				continue;
			}
			summonTeam(server, end, team);
			startSession(server, end, team, now);
		}
	}

	private static void summonTeam(MinecraftServer server, ServerLevel end, ShareTeam team) {
		Vec3 landing = new Vec3(ARRIVAL_RADIUS, 70.0, 0.0);
		for (ServerPlayer member : membersOf(server, team)) {
			if (member.level().dimension() == Level.END) {
				continue;
			}
			member.teleportTo(end, landing.x, landing.y, landing.z,
					java.util.Set.of(), member.getYRot(), member.getXRot(), false);
			// 떨어진 자리가 크리스탈 사거리일 수 있다. 상황을 볼 시간을 준다.
			member.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ARRIVAL_GRACE_TICKS, 4,
					false, false, true));
		}
	}

	private static void startSession(MinecraftServer server, ServerLevel end, ShareTeam team,
			long now) {
		DragonTrialSession session = new DragonTrialSession(team.teamId(), now,
				SharedFateMod.config.trialIntervalTicks, SharedFateMod.config.trialMaxCount);
		SESSIONS.put(team.teamId(), session);
		int memberCount = Math.max(1, team.members().size());
		float target = strengthenDragon(end, memberCount);
		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 엔드 전투 시작 — 인원 {}명 · 드래곤 체력 {} · 시련 간격 {}초 · 상한 {}장",
				team.name(), memberCount, target,
				SharedFateMod.config.trialIntervalTicks / 20, SharedFateMod.config.trialMaxCount);
	}

	/**
	 * 드래곤 최대 체력을 팀 인원에 맞춰 올린다.
	 *
	 * <p>{@code DifficultyEscalation} 은 드래곤을 일부러 빼 두므로 여기서 따로 붙인다. 연산도
	 * 같은 {@code ADD_MULTIPLIED_TOTAL} 이라 둘이 함께 붙어도 배율이 곱해질 뿐이다.
	 *
	 * @return 적용된 최대 체력. 드래곤이 아직 없으면 0
	 */
	private static float strengthenDragon(ServerLevel end, int memberCount) {
		EnderDragon dragon = findDragon(end);
		if (dragon == null) {
			return 0.0F;
		}
		AttributeInstance instance = dragon.getAttribute(Attributes.MAX_HEALTH);
		if (instance == null) {
			return 0.0F;
		}
		double base = instance.getBaseValue();
		double target = (double) SharedFateMod.config.dragonHealthPerMember * memberCount;
		if (!(base > 0.0) || target <= base) {
			return dragon.getMaxHealth();
		}
		float before = dragon.getMaxHealth();
		instance.removeModifier(HEALTH_MODIFIER_ID);
		instance.addOrUpdateTransientModifier(new AttributeModifier(
				HEALTH_MODIFIER_ID, (target / base) - 1.0,
				AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		// 가득 차 있었으면 새 최대치로 다시 채운다. 안 그러면 강화하자마자 반피로 시작한다.
		if (dragon.getHealth() >= before) {
			dragon.setHealth(dragon.getMaxHealth());
		}
		return dragon.getMaxHealth();
	}

	private static void tickSessions(MinecraftServer server, ServerLevel end, long now) {
		if (SESSIONS.isEmpty()) {
			return;
		}
		EnderDragon dragon = findDragon(end);
		List<UUID> finished = new ArrayList<>();

		for (Map.Entry<UUID, DragonTrialSession> entry : SESSIONS.entrySet()) {
			DragonTrialSession session = entry.getValue();
			ShareTeam team = TeamManager.get(server).teamById(entry.getKey());
			if (team == null) {
				finished.add(entry.getKey());
				continue;
			}
			if (dragon == null || !dragon.isAlive()) {
				SharedFateMod.LOGGER.info("[END] 팀 '{}' 드래곤 처치 — {}초 · 시련 {}장",
						team.name(), session.elapsedTicks(now) / 20, session.trialCount());
				finished.add(entry.getKey());
				continue;
			}
			List<ServerPlayer> members = membersOf(server, team);
			if (session.shouldOfferTrial(now)) {
				offerTrial(session, members, now);
			}
			DelayedStrike.tick(end, members, session, now);
		}
		finished.forEach(SESSIONS::remove);
	}

	/**
	 * 시련 한 장을 준다.
	 *
	 * <p><b>아직 선택 화면이 없다.</b> 화면은 클라이언트가 그려야 하고 그러면 통신 규약이 30 으로
	 * 올라간다. 규약을 올리면 서버와 플레이어 전원이 같은 날 함께 판을 올려야 하므로, 흐름을 먼저
	 * 굴려 보는 지금 단계에서는 <b>첫 장을 그냥 준다</b>. 선택은 화면과 함께 붙인다.
	 */
	private static void offerTrial(DragonTrialSession session, List<ServerPlayer> members,
			long now) {
		List<TrialCatalog.Trial> available = TrialCatalog.offerable(session.chosen());
		if (available.isEmpty()) {
			session.cancelChoice(now);
			return;
		}
		TrialCatalog.Trial trial = available.getFirst();
		session.beginChoice();
		session.choose(trial.id(), now);

		for (ServerPlayer member : members) {
			TitleMessenger.showTitle(member, Component.literal("시련 — " + trial.name()),
					Component.literal(trial.description()), 10, 70, 20);
			member.level().playSound(null, member.getX(), member.getY(), member.getZ(),
					SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 0.6F);
		}
		SharedFateMod.LOGGER.info("[END] 시련 {}장째 — {}", session.trialCount(), trial.name());
	}

	private static @Nullable EnderDragon findDragon(ServerLevel end) {
		for (EnderDragon dragon : end.getEntities(EntityTypes.ENDER_DRAGON,
				candidate -> candidate.isAlive())) {
			return dragon;
		}
		return null;
	}

	private static List<ServerPlayer> membersOf(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> online = new ArrayList<>();
		for (UUID memberId : team.members()) {
			ServerPlayer member = server.getPlayerList().getPlayer(memberId);
			if (member != null) {
				online.add(member);
			}
		}
		return online;
	}

	/** 지금 전투 중인 팀의 세션. 시험과 명령에서 상태를 들여다볼 때 쓴다. */
	public static @Nullable DragonTrialSession sessionOf(@Nullable UUID teamId) {
		return teamId == null ? null : SESSIONS.get(teamId);
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 남은 상태를 버린다. */
	public static void clearState() {
		SESSIONS.clear();
		PENDING_SUMMON.clear();
		DelayedStrike.clearState();
	}

	/** 시험·명령용. 지금 당장 전투를 연다. */
	public static void forceStart(MinecraftServer server, ShareTeam team) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null || team == null) {
			return;
		}
		SESSIONS.remove(team.teamId());
		summonTeam(server, end, team);
		startSession(server, end, team, end.getGameTime());
	}

	/** 「지나간 자리」 — 각자가 조금 전 있던 자리에 번개가 떨어진다. */
	private static final class DelayedStrike {
		/** 번개 사이 간격. */
		private static final int INTERVAL_TICKS = 160;
		/** 발자국을 얼마나 거슬러 올라가 노릴 것인가. 이 시간 안에 움직이면 빗나간다. */
		private static final int LOOKBACK_TICKS = 40;
		private static final double MARK_RADIUS = 2.0;
		private static final float DAMAGE = 4.0F;

		private static final Map<UUID, List<Vec3>> TRAILS = new HashMap<>();

		private DelayedStrike() {
		}

		static void tick(ServerLevel end, List<ServerPlayer> members, DragonTrialSession session,
				long now) {
			if (!session.chosen().contains("sharedfate:lightning_trail")) {
				return;
			}
			for (ServerPlayer member : members) {
				List<Vec3> trail = TRAILS.computeIfAbsent(member.getUUID(),
						key -> new ArrayList<>());
				trail.add(member.position());
				while (trail.size() > LOOKBACK_TICKS) {
					trail.removeFirst();
				}
				if (trail.size() < LOOKBACK_TICKS) {
					continue;
				}
				Vec3 target = trail.getFirst();
				int phase = (int) (now % INTERVAL_TICKS);
				int remaining = INTERVAL_TICKS - phase;

				TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
				if (stage == TrialWarning.Stage.MARK || stage == TrialWarning.Stage.IMMINENT) {
					TrialWarning.markGround(end, target, MARK_RADIUS);
				}
				if (remaining == TrialWarning.TICKS_SIDESTEP) {
					TrialWarning.sound(end, target, TrialWarning.Stage.IMMINENT);
					TrialWarning.shout(List.of(member), Component.literal("발밑을 보십시오"));
				}
				if (phase == 0) {
					strike(end, target);
				}
			}
		}

		private static void strike(ServerLevel end, Vec3 at) {
			end.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.2, at.z, 1,
					0.0, 0.0, 0.0, 0.0);
			end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
					SoundSource.HOSTILE, 2.0F, 1.0F);
			// 실제 피해는 범위 안에 남아 있는 사람에게만 들어간다. 움직였으면 빗나간 것이다.
			for (ServerPlayer nearby : end.getEntitiesOfClass(ServerPlayer.class,
					new net.minecraft.world.phys.AABB(at, at).inflate(MARK_RADIUS))) {
				nearby.hurtServer(end, end.damageSources().lightningBolt(), DAMAGE);
			}
		}

		static void clearState() {
			TRAILS.clear();
		}
	}
}
