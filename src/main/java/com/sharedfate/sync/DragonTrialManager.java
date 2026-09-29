package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import com.sharedfate.net.TrialRoulettePayload;
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
	/**
	 * 팀이 떨어지는 자리. 엔드 섬 <b>한가운데</b>다.
	 *
	 * <h2>가장자리에 내려놓다가 기둥 안에 박았다</h2>
	 *
	 * <p>처음에는 「기둥과 크리스탈 사거리를 피한다」며 반경 40 자리에 내려놓았다. 그런데
	 * 흑요석 기둥은 <b>반경 42 원 위에 선다</b>(바닐라 {@code EndSpikeFeature} 를 풀어 확인했다).
	 * 2칸 차이라 기둥 굵기 안이고, 실제로 사람이 <b>기둥 속에 스폰됐다.</b>
	 *
	 * <p>가운데는 기둥에서 가장 멀고 모든 방향이 똑같이 열려 있다. 크리스탈 사거리를 걱정했지만
	 * 도착 직후 무적이 그 몫을 한다.
	 */
	private static final Vec3 ARRIVAL_POINT = new Vec3(0.0, 75.0, 0.0);
	private static final net.minecraft.resources.Identifier HEALTH_MODIFIER_ID =
			SharedFateMod.id("trial/dragon_health");

	private static final Map<UUID, DragonTrialSession> SESSIONS = new HashMap<>();
	private static final Map<UUID, Long> PENDING_SUMMON = new HashMap<>();
	/**
	 * 룰렛을 열 수 있는 가장 이른 시각. 줄 맨 앞 자리의 지연으로 정해진다.
	 *
	 * <p><b>저장하지 않는다.</b> 재시작하면 다시 세는데, 그때는 어차피 자리가 줄에 남아 있고
	 * 그 자리의 지연만큼 더 걸릴 뿐이다. 반대로 이것을 저장했다가 값이 어긋나면 룰렛이 영영 안
	 * 열린다 — 잃는 것보다 지키기 어려운 쪽이 더 비싸다.
	 *
	 * <p>줄 맨 앞을 꺼낼 때마다 {@link #resetTrialDelay} 로 지운다. 남겨 두면 다음 자리가
	 * <b>앞 자리의 지연</b>을 물려받는다.
	 */
	private static final Map<UUID, Long> READY_AT = new HashMap<>();
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
		if (server == null) {
			return;
		}
		// 정지는 설정 검사보다 앞이다. 얼려 둔 채로 설정이 0 이 되면 녹일 사람이 없어진다.
		TrialFreeze.tick(server);
		applyFinishedTrial(server);
		if (SharedFateMod.config.dragonHealthPerMember <= 0) {
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
		Vec3 landing = ARRIVAL_POINT;
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
			openTrialWhenDue(server, end, session, members, now);
			// 패시브는 시련과 다르다. 팀이 뽑는 것이 아니라 언제나 있는 판이므로 카드와 무관하게
			// 돈다 — 섞으면 「이번 판이 왜 어려웠나」를 나눌 수 없다.
			DragonPassives.tick(end, dragon, members, session.startedTick(), now);
			TrialRisks.tick(end, dragon, members, session, now);
		}
		if (!finished.isEmpty()) {
			finished.forEach(SESSIONS::remove);
			// 전투가 끝났는데 룰렛만 남으면 다음 전투 첫 틱에 옛 카드가 튀어나온다.
			finished.forEach(DragonTrialManager::resetTrialDelay);
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
	 * 끝난 룰렛의 결과를 실제로 쌓는다.
	 *
	 * <p>자리를 <b>여기서야</b> 줄에서 꺼낸다. 열 때 꺼내면 연출 도중 서버가 내려갔을 때 그
	 * 자리가 통째로 사라진다. 지금은 연출만 사라지고 자리는 줄에 남아 다시 뜰 때 처음부터 돈다.
	 */
	private static void applyFinishedTrial(MinecraftServer server) {
		TrialFreeze.Finished done = TrialFreeze.poll();
		if (done == null) {
			return;
		}
		// 줄 맨 앞이 바뀌었으므로 지연을 다시 센다. 앞 자리가 쓰던 시각을 남겨 두면 다음 자리가
		// 자기 지연 대신 그것을 물려받는다.
		resetTrialDelay(done.teamId());
		DragonTrialSession session = SESSIONS.get(done.teamId());
		if (session == null) {
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		long now = end == null ? server.overworld().getGameTime() : end.getGameTime();
		TrialCatalog.Trigger trigger = session.beginChoice();
		if (!session.choose(done.trialId(), now)) {
			session.skipChoice();
			persist();
			return;
		}
		TrialCatalog.Trial trial = TrialCatalog.byId(done.trialId());
		SharedFateMod.LOGGER.info("[END] {} 에서 시련 {}장째 — {} (줄에 {}개 남음)",
				trigger == null ? "?" : trigger.label(), session.trialCount(),
				trial == null ? done.trialId() : trial.name(), session.queuedCount());
		persist();
	}

	/**
	 * 줄 맨 앞의 자리가 {@linkplain TrialCatalog.Trigger#delayTicks() 정한 만큼} 기다렸는가.
	 *
	 * <h2>지연 0 은 그 틱에 열려야 한다</h2>
	 *
	 * <p>전에는 「{@code READY_AT} 이 비었으면 적고 돌아간다」였다. 그 모양이면 지연이 0 이어도
	 * 적은 틱은 그냥 지나가고 <b>다음 틱에야</b> 열린다. 크리스탈처럼 즉시가 목적인 자리에서는
	 * 그 한 틱이 곧 「안 되는 것」이므로, 적어 넣은 값을 <b>같은 틱에 바로 견준다.</b>
	 *
	 * <p>지연을 자리마다 다르게 두는 이상 값을 고르려면 <b>어느 자리인지부터 알아야 한다.</b>
	 * 그래서 팀 id 가 아니라 세션을 받아 줄 맨 앞을 여기서 들여다본다. 줄이 비어 자리를 알 수
	 * 없으면 기다리는 쪽으로 둔다 — 그 상태에서는 어차피 룰렛이 열리지 않고
	 * ({@link DragonTrialSession#shouldOfferTrial} 이 막는다), 모르는 채 즉시를 고르는 것보다
	 * 안전하다.
	 *
	 * <p>시험에서 직접 부른다. 이 계산이 틀리면 룰렛이 한 틱 밀리거나 영영 안 열리는데, 둘 다
	 * 전멸이 곧 월드 삭제인 판에서 돌려 보고 발견할 수 없다.
	 */
	static boolean trialDue(DragonTrialSession session, long now) {
		long readyAt = READY_AT.computeIfAbsent(session.teamId(),
				teamId -> now + delayTicksFor(session.peekTrigger()));
		return now >= readyAt;
	}

	/** 이 자리가 정한 지연. 자리를 모르면 기다리는 쪽이다. */
	private static int delayTicksFor(@Nullable TrialCatalog.Trigger trigger) {
		return trigger == null ? TrialCatalog.DELAY_SETTLE_TICKS : trigger.delayTicks();
	}

	/**
	 * 이 팀의 지연을 처음부터 다시 센다.
	 *
	 * <p>줄 맨 앞을 꺼낸 자리마다 부른다. 다음 자리는 <b>자기 지연</b>을 써야 하므로 앞 자리가
	 * 쓰던 시각을 남겨 두면 안 된다 — 남기면 즉시여야 할 자리가 앞 자리의 15초를 물려받거나,
	 * 반대로 기다려야 할 자리가 이미 지난 시각을 보고 곧바로 열린다.
	 */
	static void resetTrialDelay(@Nullable UUID teamId) {
		READY_AT.remove(teamId);
	}

	/**
	 * 자리가 터지고 그 자리가 정한 지연이 지나면 룰렛을 연다.
	 *
	 * <h2>왜 자리마다 다른가</h2>
	 *
	 * <p>엔드에 떨어지는 순간은 판이 가장 시끄러운 때다. 그 위에 화면을 겹쳐 띄우면 무엇 때문에
	 * 떴는지 읽히지 않으므로 떨어진 것을 <b>먼저 겪게</b> 하고 잠깐 뒤에 뽑는다. 반대로 크리스탈을
	 * 깨거나 체력 문턱을 넘긴 것은 <b>팀이 스스로 만든 결과</b>라 원인이 이미 분명하고, 거기서
	 * 기다리면 「해냈는데 왜 아무 일도 없지」가 된다. 값은
	 * {@link TrialCatalog.Trigger#delayTicks()} 에 자리마다 적혀 있다.
	 *
	 * <p>풀이 비어 있으면 아무것도 주지 않고 지나간다 — 카드를 채워 가는 동안에는 빈 풀이
	 * 정상이고 오류가 아니다.
	 */
	private static void openTrialWhenDue(MinecraftServer server, ServerLevel end,
			DragonTrialSession session, List<ServerPlayer> members, long now) {
		if (TrialFreeze.isActive() || !session.shouldOfferTrial() || members.isEmpty()) {
			return;
		}
		if (!trialDue(session, now)) {
			return;
		}
		TrialCatalog.Trigger trigger = session.peekTrigger();
		List<TrialCatalog.Trial> pool = TrialCatalog.offerable(trigger, session.chosen());
		if (pool.isEmpty()) {
			session.beginChoice();
			session.skipChoice();
			resetTrialDelay(session.teamId());
			SharedFateMod.LOGGER.info("[END] {} — 줄 수 있는 카드가 없어 지나갑니다",
					trigger == null ? "?" : trigger.label());
			persist();
			return;
		}
		if (pool.size() > TrialRoulettePayload.MAX_OPTIONS) {
			// 코덱 상한을 넘으면 패킷이 터진다. 카드가 그만큼 늘면 풀을 쪼갤 때가 된 것이다.
			pool = pool.subList(0, TrialRoulettePayload.MAX_OPTIONS);
		}
		TrialRoulette roulette = TrialRoulette.open(trigger, pool, now, end.getRandom());
		if (roulette == null) {
			return;
		}
		int spinTicks = TrialRoulette.TOTAL_TICKS;
		// 정지를 먼저 건다. 화면만 띄우고 시간이 흐르면 글을 읽는 동안 맞는다.
		if (!TrialFreeze.begin(server, session.teamId(), roulette.result().id(), members,
				spinTicks + TrialFreeze.HOLD_TICKS)) {
			return;
		}
		sendRoulette(members, trigger, pool, roulette.result(), spinTicks);
	}

	/**
	 * 후보와 결과를 한 번에 보낸다. <b>연출은 클라이언트가 돌린다.</b>
	 *
	 * <p>칸이 바뀔 때마다 보내면 4초에 열다섯 번이고 그중 하나만 늦어도 화면이 튄다. 결과는 이미
	 * 정해져 있으므로 늦게 닿아도 답이 달라지지 않는다.
	 */
	private static void sendRoulette(List<ServerPlayer> members,
			@Nullable TrialCatalog.Trigger trigger, List<TrialCatalog.Trial> pool,
			TrialCatalog.Trial result, int spinTicks) {
		List<TrialRoulettePayload.TrialOption> options = new ArrayList<>();
		for (TrialCatalog.Trial trial : pool) {
			options.add(new TrialRoulettePayload.TrialOption(
					trial.id(), trial.name(), trial.description()));
		}
		TrialRoulettePayload payload = new TrialRoulettePayload(
				trigger == null ? "시련" : trigger.label(),
				Math.max(0, pool.indexOf(result)), spinTicks, options);
		for (ServerPlayer member : members) {
			ServerPlayNetworking.send(member, payload);
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
		READY_AT.clear();
		TrialFreeze.reset();
		TrialRisks.clearState();
		DragonPassives.clearState();
	}

	/** 시험·명령용. 지금 당장 전투를 연다. */
	public static void forceStart(MinecraftServer server, ShareTeam team) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null || team == null) {
			return;
		}
		SESSIONS.remove(team.teamId());
		resetTrialDelay(team.teamId());
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
		resetTrialDelay(team.teamId());
		session.restore(List.of(), session.firedNames(), session.queuedNames(), false, Map.of());
		TrialRisks.clearState();
		persist();
		return true;
	}

}
