package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
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
	/**
	 * 지금 룰렛이 돌고 있는 팀.
	 *
	 * <p><b>저장하지 않는다.</b> 연출 도중에 서버가 내려가면 이 맵만 사라지고 자리는 줄에 그대로
	 * 남으므로, 다시 뜰 때 룰렛이 처음부터 다시 돈다. 자리를 줄에서 꺼내는
	 * {@link DragonTrialSession#beginChoice} 를 <b>룰렛이 끝날 때</b> 부르는 이유가 이것이다 —
	 * 시작할 때 꺼내면 연출 도중 종료가 그 자리를 통째로 삼킨다.
	 */
	private static final Map<UUID, TrialRoulette> SPINS = new HashMap<>();
	/** 전투를 열 때의 크리스탈 수. 「처음 깨졌다」를 이것과 비교해 판단한다. */
	private static final Map<UUID, Integer> CRYSTALS_AT_START = new HashMap<>();

	/** 전투 상태를 적어 두는 곳. 서버가 뜰 때 정해진다. */
	private static @Nullable java.nio.file.Path stateFile;

	private DragonTrialManager() {
	}

	/**
	 * 서버가 뜰 때. 진행 중이던 전투를 되살린다.
	 *
	 * <p>드래곤 체력 수정자는 개체에 붙어 월드와 함께 저장되지만 타이머와 누적은 메모리에만
	 * 있다. 되살리지 않으면 체력만 강화된 채 시련 0 장인 어긋난 상태가 된다.
	 */
	public static void onServerStarted(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		stateFile = server.getServerDirectory().toAbsolutePath().normalize()
				.resolve(DragonTrialStore.FILE_NAME);
		SESSIONS.clear();
		for (DragonTrialStore.Entry entry : DragonTrialStore.load(stateFile)) {
			UUID teamId;
			try {
				teamId = UUID.fromString(entry.teamId);
			} catch (IllegalArgumentException malformed) {
				continue;
			}
			DragonTrialSession session = new DragonTrialSession(teamId, entry.startedTick);
			session.restore(entry.chosen, entry.fired, entry.queued, entry.awaitingChoice,
					entry.grantedTicks);
			SESSIONS.put(teamId, session);
			SharedFateMod.LOGGER.info("[END] 진행 중이던 엔드 전투를 되살렸습니다 — 시련 {}장",
					session.trialCount());
		}
	}

	/** 지금 상태를 파일에 남긴다. 세션이 열리고 닫힐 때와 시련을 고를 때 부른다. */
	private static void persist() {
		if (stateFile == null) {
			return;
		}
		List<DragonTrialStore.Entry> entries = new ArrayList<>();
		for (DragonTrialSession session : SESSIONS.values()) {
			DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
			entry.teamId = session.teamId().toString();
			entry.startedTick = session.startedTick();
			entry.chosen = new ArrayList<>(session.chosen());
			entry.fired = session.firedNames();
			entry.queued = session.queuedNames();
			entry.awaitingChoice = session.awaitingChoice();
			// 위험의 주기는 카드를 받은 틱부터 센다. 이것을 안 적으면 재시작 뒤 위상이 튄다.
			entry.grantedTicks = new java.util.LinkedHashMap<>(session.grantedTicks());
			entries.add(entry);
		}
		try {
			DragonTrialStore.save(stateFile, entries);
		} catch (java.io.IOException error) {
			SharedFateMod.LOGGER.error("엔드 전투 상태를 쓰지 못했습니다: {}", stateFile, error);
		}
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
		DragonTrialSession session = new DragonTrialSession(team.teamId(), now);
		SESSIONS.put(team.teamId(), session);
		int memberCount = Math.max(1, team.members().size());
		float target = strengthenDragon(end, memberCount);
		// 엔드에 들어선 것 자체가 첫 자리다.
		session.fire(TrialCatalog.Trigger.ENTRY);
		CRYSTALS_AT_START.put(team.teamId(), countCrystals(end));
		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 엔드 전투 시작 — 인원 {}명 · 드래곤 체력 {} · 크리스탈 {}개",
				team.name(), memberCount, target, countCrystals(end));
		persist();
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
			detectTriggers(end, dragon, session);
			tickRoulette(end, session, members, now);
			TrialRisks.tick(end, members, session, now);
		}
		if (!finished.isEmpty()) {
			finished.forEach(SESSIONS::remove);
			// 전투가 끝났는데 룰렛만 남으면 다음 전투 첫 틱에 옛 카드가 튀어나온다.
			finished.forEach(SPINS::remove);
			persist();
		}
	}

	/**
	 * 전투 진행도를 보고 자리가 터졌는지 본다.
	 *
	 * <p>체력은 <b>강화된 최대치</b> 기준이다. 크리스탈로 회복해 문턱을 오르내려도
	 * {@link DragonTrialSession#fire} 가 처음 한 번만 센다.
	 */
	private static void detectTriggers(ServerLevel end, EnderDragon dragon,
			DragonTrialSession session) {
		int crystals = countCrystals(end);
		Integer atStart = CRYSTALS_AT_START.get(session.teamId());
		if (atStart != null && atStart > 0) {
			if (crystals < atStart) {
				session.fire(TrialCatalog.Trigger.FIRST_CRYSTAL);
			}
			if (crystals == 0) {
				session.fire(TrialCatalog.Trigger.ALL_CRYSTALS);
			}
		}

		float max = dragon.getMaxHealth();
		if (!(max > 0.0F)) {
			return;
		}
		float ratio = dragon.getHealth() / max;
		if (ratio <= 0.80F) {
			session.fire(TrialCatalog.Trigger.HEALTH_80);
		}
		if (ratio <= 0.50F) {
			session.fire(TrialCatalog.Trigger.HEALTH_50);
		}
		if (ratio <= 0.30F) {
			session.fire(TrialCatalog.Trigger.HEALTH_30);
		}
	}

	/** 지금 엔드에 살아 있는 크리스탈 수. 철장에 갇힌 것도 센다. */
	private static int countCrystals(ServerLevel end) {
		int count = 0;
		for (net.minecraft.world.entity.boss.enderdragon.EndCrystal ignored
				: end.getEntities(EntityTypes.END_CRYSTAL, crystal -> crystal.isAlive())) {
			count++;
		}
		return count;
	}

	/**
	 * 룰렛을 돌리고, 멈추면 그 카드를 쌓는다.
	 *
	 * <p><b>고르게 하지 않는다.</b> 선택 화면은 클라이언트가 그려야 하고 그러면 통신 규약이 30
	 * 으로 올라간다 — 서버와 지인 전원이 같은 날 함께 판을 올려야 한다. 룰렛은 타이틀을 갈아
	 * 끼우는 것뿐이라 전부 바닐라 패킷이고 <b>규약이 29 그대로</b>다. 자세한 이유는
	 * {@link TrialRoulette} 에 적었다.
	 */
	private static void tickRoulette(ServerLevel end, DragonTrialSession session,
			List<ServerPlayer> members, long now) {
		TrialRoulette spinning = SPINS.get(session.teamId());
		if (spinning == null) {
			startRoulette(end, session, members, now);
			return;
		}
		TrialCatalog.Trial shown = spinning.advance(now);
		if (shown != null) {
			showSpinFrame(members, spinning, shown);
		}
		if (!spinning.finished(now)) {
			return;
		}
		SPINS.remove(session.teamId());
		// 자리를 이제야 줄에서 꺼낸다. 시작할 때 꺼내면 연출 도중 서버가 죽었을 때 자리가 사라진다.
		TrialCatalog.Trigger trigger = session.beginChoice();
		TrialCatalog.Trial result = spinning.result();
		if (!session.choose(result.id(), now)) {
			session.skipChoice();
			return;
		}
		announce(members, result);
		SharedFateMod.LOGGER.info("[END] {} 에서 시련 {}장째 — {} (줄에 {}개 남음)",
				trigger == null ? "?" : trigger.label(), session.trialCount(), result.name(),
				session.queuedCount());
		persist();
	}

	/**
	 * 줄 맨 앞의 자리로 룰렛을 연다.
	 *
	 * <p>풀이 비어 있으면 아무것도 주지 않고 지나간다 — 카드를 채워 가는 동안에는 빈 풀이
	 * 정상이고 오류가 아니다.
	 */
	private static void startRoulette(ServerLevel end, DragonTrialSession session,
			List<ServerPlayer> members, long now) {
		if (!session.shouldOfferTrial()) {
			return;
		}
		TrialCatalog.Trigger trigger = session.peekTrigger();
		List<TrialCatalog.Trial> pool = TrialCatalog.offerable(trigger, session.chosen());
		if (pool.isEmpty()) {
			session.beginChoice();
			session.skipChoice();
			SharedFateMod.LOGGER.info("[END] {} — 줄 수 있는 카드가 없어 지나갑니다",
					trigger == null ? "?" : trigger.label());
			persist();
			return;
		}
		TrialRoulette roulette = TrialRoulette.open(trigger, pool, now, end.getRandom());
		if (roulette == null) {
			return;
		}
		SPINS.put(session.teamId(), roulette);
		for (ServerPlayer member : members) {
			member.level().playSound(null, member.getX(), member.getY(), member.getZ(),
					SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 0.6F);
		}
	}

	/** 룰렛이 한 칸 돌았다. 칸이 바뀔 때만 부른다 — 매 틱 보내면 글자가 떨린다. */
	private static void showSpinFrame(List<ServerPlayer> members, TrialRoulette roulette,
			TrialCatalog.Trial shown) {
		for (ServerPlayer member : members) {
			TitleMessenger.showTitle(member,
					Component.literal("시련 — " + roulette.trigger().label()),
					Component.literal(shown.name()), 0, 20, 5);
			member.level().playSound(null, member.getX(), member.getY(), member.getZ(),
					SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 0.6F, 1.6F);
		}
	}

	/** 멈춘 카드를 알린다. 무엇이 일어나는지는 여기서 한 번만 읽힌다. */
	private static void announce(List<ServerPlayer> members, TrialCatalog.Trial trial) {
		for (ServerPlayer member : members) {
			TitleMessenger.showTitle(member,
					Component.literal("시련 — " + trial.name()),
					Component.literal(trial.description()), 5, 80, 20);
			member.level().playSound(null, member.getX(), member.getY(), member.getZ(),
					SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 1.0F, 0.6F);
		}
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
		CRYSTALS_AT_START.clear();
		SPINS.clear();
		TrialRisks.clearState();
	}

	/** 시험·명령용. 지금 당장 전투를 연다. */
	public static void forceStart(MinecraftServer server, ShareTeam team) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null || team == null) {
			return;
		}
		SESSIONS.remove(team.teamId());
		SPINS.remove(team.teamId());
		summonTeam(server, end, team);
		startSession(server, end, team, end.getGameTime());
	}

	/**
	 * 카드를 지금 부여한다.
	 *
	 * <p><b>이것이 없으면 두 번째 카드를 시험할 방법이 없다.</b> 자리가 터지면 룰렛이 그 풀에서
	 * 뽑으므로, 특정 카드를 보려면 운에 맡기거나 목록에서 나머지를 지워야 한다. 카드를 만들며
	 * 하나씩 눈으로 보는 것이 이 브랜치의 작업 전부라 시험 명령 쪽에 길을 낸다.
	 *
	 * @return 실제로 쌓였으면 참. 전투가 없거나 모르는 id 이거나 이미 가진 카드면 거짓
	 */
	public static boolean grant(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable String trialId) {
		DragonTrialSession session = team == null ? null : SESSIONS.get(team.teamId());
		if (server == null || session == null || TrialCatalog.byId(trialId) == null) {
			return false;
		}
		ServerLevel end = server.getLevel(Level.END);
		long now = end == null ? server.overworld().getGameTime() : end.getGameTime();
		if (!session.choose(trialId, now)) {
			return false;
		}
		persist();
		return true;
	}

	/**
	 * 자리를 강제로 터뜨린다. 룰렛이 곧 돌기 시작한다.
	 *
	 * @return 처음 터진 자리면 참. 이미 센 자리면 거짓
	 */
	public static boolean fire(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TrialCatalog.Trigger trigger) {
		DragonTrialSession session = team == null ? null : SESSIONS.get(team.teamId());
		if (server == null || session == null || !session.fire(trigger)) {
			return false;
		}
		persist();
		return true;
	}

	/**
	 * 쌓인 시련을 비운다. 다음 카드를 맨몸에서 본다.
	 *
	 * <p>돌고 있던 룰렛도 함께 버린다 — 남겨 두면 비운 직후에 옛 카드가 멈춰 다시 쌓인다.
	 *
	 * @return 전투가 열려 있었으면 참
	 */
	public static boolean clear(@Nullable MinecraftServer server, @Nullable ShareTeam team) {
		DragonTrialSession session = team == null ? null : SESSIONS.get(team.teamId());
		if (server == null || session == null) {
			return false;
		}
		SPINS.remove(team.teamId());
		session.restore(List.of(), session.firedNames(), session.queuedNames(), false, Map.of());
		TrialRisks.clearState();
		persist();
		return true;
	}

}
