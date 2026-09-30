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
 * <h2>시련은 팀 설정이고, 기본값은 끔이다</h2>
 *
 * <p>{@code TeamState.dragonTrialsEnabled} 가 거짓인 팀에게는 <b>이 파일이 드래곤에 아무
 * 손도 대지 않는다.</b> 체력 수정자를 붙였다가 떼는 것이 아니라 <b>처음부터 붙이지 않는다</b> —
 * 올렸다 내리면 보스바가 한 번 튀고, 그 사이 틱에 맞은 피해가 다른 최대치를 기준으로 계산된다.
 * 그래서 {@link #strengthenDragon} 을 <b>부르지 않는 것</b>이 유일한 갈림이다.
 *
 * <p>끈 팀에게 꺼지는 것은 다섯이다 — 시련 카드(룰렛)·고정 시련(체력 80%)·최후의
 * 저항({@link DragonLastStand})·기본 패시브({@link DragonPassives})·드래곤 체력 강화. 앞의
 * 둘은 {@link DragonTrialSession} 이 자리를 아예 세지 않아 저절로 꺼지고, 뒤의 셋은
 * {@link #tickSessions} 와 {@link #startSession} 이 각각 건너뛴다.
 *
 * <h2>체력 30% 는 카드가 아니다</h2>
 *
 * <p>{@code Trigger.HEALTH_30} 이 터지면 룰렛이 열리는 대신 {@link DragonLastStand} 가
 * 열린다 — <b>별개의 보스전</b>이라 {@code TrialCatalog.POOL_HEALTH_30} 은 비어 있다. 그때부터
 * 이 파일의 세션 루프는 그 팀의 <b>룰렛·시련·패시브를 전부 건너뛴다.</b>
 *
 * <p><b>팀을 엔드로 부르는 것은 끄지 않는다.</b> 그것은 시련이 아니라 「최종 보스는 팀이 함께
 * 선다」는 이 모드의 규칙이고, 시련을 껐다고 혼자 들어가게 두면 나머지는 다른 차원에서 공유
 * 체력만 깎이는 것을 구경하게 된다 — 아래 「왜 전원을 부르는가」가 그대로 성립한다.
 *
 * <h2>엔드는 나갈 수 없다</h2>
 *
 * <p>드래곤을 잡기 전에 엔드를 나가는 길은 죽는 것뿐이고, 이 모드에서 그건 곧 전멸이자 월드
 * 초기화다. 그래서 「전원 이탈」 같은 종료 조건은 두지 않는다 — 일어날 수 없는 상황을 위한
 * 코드는 시험할 수도 없고 다음 사람이 읽을 때 헷갈린다. 끝나는 길은 <b>드래곤 사망</b>과
 * <b>팀 전멸</b> 둘뿐이다.
 *
 * <p>그 반대쪽 — <b>들어온 사람이 밖으로 새는 것</b> — 은 실제로 일어났다. 우리 뒤에 도는
 * 순간이동들이 같은 틱에 자리를 덮어썼기 때문이고, 그것을 {@code EndFightTeleportLock} 이
 * 막는다. 자물쇠가 새는 날을 위한 그물이 {@link #recallStragglers} 다 — 전투 중에 엔드 밖에
 * 남은 팀원을 몇 초마다 다시 부른다.
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
	static final int ARRIVAL_GRACE_TICKS = 60;
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
	/**
	 * 기준 크리스탈 수. 「처음 깨졌다」·「전멸」을 이것과 비교해 판단한다.
	 *
	 * <p><b>전투가 열릴 때가 아니라 {@link TrialEntrance 입장 연출}이 끝난 뒤에 처음 적힌다.</b>
	 * 연출이 열 개를 거뒀다 되살리므로, 거두기 전의 개수를 기준으로 삼으면 그 사이에 두 자리가
	 * 헛되게 터진다. 적는 자리는 {@link #detectTriggers} 하나다.
	 *
	 * <p>저장하지 않는다. 재시작하면 <b>그때 남아 있는 개수</b>로 다시 적힌다 — 이미 터진 자리는
	 * {@link DragonTrialSession} 이 기억하고 있어 다시 세지 않고, 남은 개수가 0 이 되는 순간은
	 * 그대로 잡힌다.
	 */
	private static final Map<UUID, Integer> CRYSTALS_AT_START = new HashMap<>();
	/**
	 * 팀마다 다음으로 「밖에 남은 사람이 있나」를 볼 시각.
	 *
	 * <p>저장하지 않는다. 재시작하면 첫 틱에 한 번 보고 지나가는데, 그것이 오히려 맞다 —
	 * 재시작 직후는 밖에 남은 사람이 있을 가능성이 가장 높은 순간이다.
	 */
	private static final Map<UUID, Long> RECALL_AT = new HashMap<>();
	/**
	 * 밖에 남은 사람을 다시 부르는 간격(틱). 2초.
	 *
	 * <p>근거는 {@link #recallStragglers} 에 적어 두었다.
	 */
	static final int RECALL_INTERVAL_TICKS = 40;

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
			// 켬·끔은 저장 파일에 담지 않는다. 팀 설정이 그 사실의 유일한 출처이고, 여기
			// 한 벌을 더 두면 팀을 해체하고 다시 만든 뒤에 옛 값이 되살아난다. 팀 명단은
			// SharedFateMod 가 이보다 먼저(TeamRosterStore.onServerStarted) 세워 둔다.
			boolean trials = trialsEnabled(server, teamId);
			DragonTrialSession session = new DragonTrialSession(teamId, entry.startedTick, trials);
			session.restore(entry.chosen, entry.fired, entry.queued, entry.awaitingChoice,
					entry.grantedTicks);
			SESSIONS.put(teamId, session);
			SharedFateMod.LOGGER.info("[END] 진행 중이던 엔드 전투를 되살렸습니다 — 시련 {} · {}장",
					trials ? "켬" : "끔(바닐라 드래곤전)", session.trialCount());
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
			for (ServerPlayer member : onlineMembers(server, team)) {
				if (member.level().dimension() != Level.END) {
					TitleMessenger.showTitle(member, Component.literal("엔드로 이동합니다"),
							Component.literal("팀이 최종 보스에 들어섰습니다"), 5, 40, 10);
				}
			}
		}
	}

	/**
	 * 매 틱. 소환 카운트다운과 시련 타이머를 돌린다.
	 *
	 * <h2>⚠ 이 파일은 {@code END_SERVER_TICK} 에서 <b>가장 먼저</b> 돈다</h2>
	 *
	 * <p>{@code SharedFateMod} 의 등록 순서가 곧 실행 순서인데 {@code DragonTrialManager::tick}
	 * 이 그 줄 맨 앞에 있다. 곧 <b>여기서 사람을 엔드로 옮겨 놓아도 같은 틱의 뒤쪽에서 다른
	 * 것들이 그 자리를 덮어쓸 수 있다.</b> 사람을 옮기는 길이 다섯이고(순열 교환 · 집합 ·
	 * 정거장 · 시차 · 소집의 조각) 전부 우리 뒤에 돈다 — 같은 틱에 부딪히면 <b>엔드 소환이
	 * 구조적으로 진다.</b> 실제로 「엔드에 끌려 들어갔다가 도로 나온다」가 그것이었다.
	 *
	 * <p>지금은 {@code EndFightTeleportLock} 이 그 다섯을 막아 부딪히지 않는다. 그래도 이
	 * 사실을 여기 남겨 둔다 — <b>순서로 이기려 들지 말 것.</b> 등록 순서를 바꿔 뒤로 옮기면
	 * 이번에는 우리가 남의 순간이동을 덮어쓰게 되고, 어느 쪽이 이기는지가 등록 파일의 줄 순서에
	 * 달린 상태로 돌아간다. 막아야 하는 것은 순서가 아니라 <b>옮기는 행위</b>다.
	 *
	 * <p>자물쇠가 새는 날을 대비한 그물이 {@link #recallStragglers} 다. 순서 경쟁에서 져서
	 * 누가 밖으로 떨어져도 몇 초 뒤에 다시 끌어온다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		// 정지는 설정 검사보다 앞이다. 얼려 둔 채로 설정이 0 이 되면 녹일 사람이 없어진다.
		TrialFreeze.tick(server);
		// 드래곤을 잡은 팀의 무적. 세션이 이미 닫힌 뒤라 아래 세션 루프에 둘 수 없고, 사람이
		// 엔드를 떠난 뒤에도 유지돼야 해서 엔드 검사보다도 앞이다.
		DragonLastStand.tickVictory(server);
		applyFinishedTrial(server);
		// ⚠ 이 줄은 <b>서버 전체</b>를 끄는 스위치다. 팀 설정의 「드래곤 시련」과 다르다 —
		// 여기서 돌아가면 팀을 엔드로 부르는 것까지 함께 멈춘다. 시련을 끈 팀에게
		// dragonHealthPerMember 를 무시한다고 적었지만, 0 으로 둔 서버에서는 그 팀도 전원
		// 소환을 못 받는다. 이 경로는 시련 설정이 생기기 전부터 있던 것이라 그대로 두었다.
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
		// ⚠ 세션을 돌리기 전이다. tickSessions 는 세션을 지울 수 있고, 지워진 팀을 다시
		// 부르는 것은 「전투가 끝났는데 엔드로 끌려간다」가 된다.
		recallStragglers(server, end, now);
		tickSessions(server, end, now);
	}

	/**
	 * 전투가 열려 있는데 <b>엔드 밖에 남은 팀원</b>을 다시 부른다.
	 *
	 * <h2>이것이 없어서 실제로 일어나던 일</h2>
	 *
	 * <p>{@link #detectArrival} 은 세션이 이미 열려 있으면 <b>통째로 건너뛴다.</b> 그래서 한 번
	 * 소환한 뒤에 밖으로 나간 사람은 <b>다시는 불리지 않았다.</b> 나가는 길이 둘이다.
	 *
	 * <ul>
	 *   <li><b>낡은 좌표를 쓰는 순간이동</b>(정거장 · 시차)이 사람을 엔드 밖에 떨어뜨린다.
	 *       {@code EndFightTeleportLock} 이 이제 막지만, 자물쇠가 새면 그대로 밖에 남는다</li>
	 *   <li><b>소환될 때 오프라인이었던 팀원.</b> {@link #summonTeam} 은 접속해 있는 사람만
	 *       옮기므로, 전투 도중에 들어온 사람은 영영 밖이다</li>
	 * </ul>
	 *
	 * <p>공유 체력이라 밖에 남은 사람은 <b>싸우지 않으면서 체력만 같이 깎인다.</b> 싸우는 쪽만
	 * 위험을 지고 전멸하면 월드가 지워진다.
	 *
	 * <h2>{@link #summonTeam} 을 그대로 부른다</h2>
	 *
	 * <p>안전 착지점 {@link #ARRIVAL_POINT} 와 도착 무적 {@link #ARRIVAL_GRACE_TICKS} 가 그
	 * 안에 있다. 여기서 다시 짜면 두 벌이 되어 「언젠가 한쪽만 고쳐진다」가 된다. 이미 엔드에
	 * 있는 사람은 그쪽이 스스로 건너뛴다.
	 *
	 * <h2>무엇을 보고 부르는가</h2>
	 *
	 * <ul>
	 *   <li><b>오프라인은 세지 않는다.</b> {@link #onlineMembers} 가 접속한 사람만 준다.
	 *       접속하지 않은 사람은 부를 수도 없고, 들어오면 그 뒤 첫 검사에서 잡힌다</li>
	 *   <li><b>관전자는 세지 않는다.</b> 판에 끼어들지 않는 사람이라 밖에 있어도 팀이 손해를
	 *       보지 않는다. ⚠ 다만 <b>다른 사람 때문에 소환이 돌면 관전자도 함께 끌려온다</b> —
	 *       {@link #summonTeam} 은 관전자를 가리지 않고, 그쪽을 고치는 것보다 이 한 줄을 적어
	 *       두는 편이 싸다. 「최종 보스는 팀이 함께 선다」에 어긋나지도 않는다</li>
	 *   <li><b>차원으로 가른다.</b> 엔드가 아니면 밖이다. 좌표로 재지 않는다 — 오버월드 원점
	 *       근처가 엔드 중앙과 같은 좌표라는 함정은 {@link #membersOf} 에 적혀 있다</li>
	 *   <li>{@code dragonHealthPerMember <= 0} 이면 {@link #tick} 이 여기 오기 전에 되돌아간다.
	 *       소환이 없는 서버에서는 재소환도 없다 — 자물쇠가 보는 조건과 같은 값이다</li>
	 * </ul>
	 *
	 * <h2>{@value #RECALL_INTERVAL_TICKS} 틱마다인 이유</h2>
	 *
	 * <p>매 틱 부르면 안 된다. 재소환은 <b>사람을 화면째로 끌어오는 일</b>이고, 우리 뒤에 도는
	 * 순간이동과 같은 틱에 맞붙으면 사람이 두 자리 사이에서 떨린다.
	 *
	 * <p>2초로 잡은 근거가 둘이다. 「운명 공동체」의 {@code gather} 쿨타임이 <b>20틱</b>이라
	 * 1초로 두면 그쪽과 <b>같은 박자로 맞붙는다</b>. 그리고 {@link #ARRIVAL_GRACE_TICKS} 가
	 * 60틱이라 40틱마다면 연달아 끌려와도 <b>도착 무적이 끊기지 않는다.</b>
	 *
	 * <h2>최후의 저항 중에도 돈다</h2>
	 *
	 * <p>{@link #tickSessions} 가 아니라 여기 {@link #tick} 에 있다. 그쪽에 두면 시련을 끈 팀과
	 * {@link DragonLastStand} 가 도는 팀에서 {@code continue} 에 걸려 빠진다 — 붙박이 드래곤과
	 * 싸우는 도중에 한 명이 밖에 남는 것이 가장 나쁜 경우다.
	 */
	private static void recallStragglers(MinecraftServer server, ServerLevel end, long now) {
		if (SESSIONS.isEmpty()) {
			return;
		}
		for (UUID teamId : new ArrayList<>(SESSIONS.keySet())) {
			Long nextAt = RECALL_AT.get(teamId);
			if (nextAt != null && now < nextAt) {
				continue;
			}
			RECALL_AT.put(teamId, now + RECALL_INTERVAL_TICKS);
			ShareTeam team = TeamManager.get(server).teamById(teamId);
			if (team == null) {
				continue;
			}
			List<ServerPlayer> outside = strayMembers(server, team, end);
			if (outside.isEmpty()) {
				continue;
			}
			StringBuilder names = new StringBuilder();
			for (ServerPlayer stray : outside) {
				if (!names.isEmpty()) {
					names.append(", ");
				}
				names.append(stray.getPlainTextName());
			}
			SharedFateMod.LOGGER.info("[END] 팀 '{}' 전투 중 엔드 밖에 남은 팀원을 다시 부릅니다 — {}",
					team.name(), names);
			summonTeam(server, end, team);
		}
	}

	/**
	 * 전투 중인데 엔드 밖에 있는 팀원들. <b>관전자는 빼고 센다.</b>
	 *
	 * <p>돌려주는 목록은 로그에만 쓴다. 실제로 옮기는 것은 {@link #summonTeam} 이다 — 부를지
	 * 말지를 정하는 것과 옮기는 것을 나눠 두어야 옮기는 쪽이 한 벌로 남는다.
	 */
	private static List<ServerPlayer> strayMembers(MinecraftServer server, ShareTeam team,
			ServerLevel end) {
		List<ServerPlayer> outside = new ArrayList<>();
		for (ServerPlayer member : onlineMembers(server, team)) {
			if (member.isSpectator() || member.level() == end) {
				continue;
			}
			outside.add(member);
		}
		return outside;
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
		for (ServerPlayer member : onlineMembers(server, team)) {
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

	/**
	 * 전투를 연다. <b>여기가 시련을 켠 팀과 끈 팀이 갈리는 유일한 자리다.</b>
	 *
	 * <p>끈 팀에게는 {@link #strengthenDragon} 을 <b>부르지 않는다.</b> 「올려 놓고 다시 내린다」
	 * 가 아니라 아예 손을 대지 않는 것이라, 드래곤은 바닐라 최대 체력 200 그대로이고
	 * {@code SharedFateConfig.dragonHealthPerMember} 는 읽히지도 않는다. 붙였다 떼면 보스바가
	 * 한 번 튀고 그 사이 틱의 피해가 다른 최대치로 계산된다.
	 *
	 * <p>자리(트리거)를 세는 일도 세션이 스스로 막는다 — {@link DragonTrialSession#fire} 가
	 * 끈 팀에서는 아무 일도 하지 않으므로 아래 {@code fire(ENTRY)} 는 그대로 두어도 된다.
	 * 여기에 {@code if} 를 하나 더 두면 「어디서 막았나」가 두 곳이 된다.
	 *
	 * <p>로그는 <b>켬·끔을 반드시 적는다.</b> 기본값이 끔이라 「왜 시련이 안 뜨지」의 답이 거의
	 * 언제나 이 줄에 있다.
	 */
	private static void startSession(MinecraftServer server, ServerLevel end, ShareTeam team,
			long now) {
		boolean trials = trialsEnabled(server, team.teamId());
		DragonTrialSession session = new DragonTrialSession(team.teamId(), now, trials);
		SESSIONS.put(team.teamId(), session);
		int memberCount = Math.max(1, team.members().size());
		// 끈 팀에게는 강화 자체를 건너뛴다. 지금 값을 그대로 로그에 적어 「바닐라 200 이다」가
		// 눈으로 확인되게 한다.
		EnderDragon dragon = findDragon(end);
		float target = trials
				? strengthenDragon(end, memberCount)
				: (dragon == null ? 0.0F : dragon.getMaxHealth());
		// 엔드에 들어선 것 자체가 첫 자리다. 끈 팀에서는 fire 가 거짓을 돌려주고 끝난다.
		session.fire(TrialCatalog.Trigger.ENTRY);
		// ⚠ 기준 크리스탈 수를 여기서 적지 않는다. 시련을 켠 팀에서는 TrialEntrance 가 곧
		// 크리스탈을 전부 거두므로, 여기서 적어 둔 값은 그 순간 「열 개였는데 0 이 됐다」가 되어
		// 두 자리를 헛되게 터뜨린다. 적는 자리는 detectTriggers 하나뿐이고, 입장 연출이
		// 끝난 뒤 첫 틱이다.
		CRYSTALS_AT_START.remove(team.teamId());
		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 엔드 전투 시작 — 시련 {} · 인원 {}명 · 드래곤 체력 {} · 크리스탈 {}개",
				team.name(), trials ? "켬" : "끔(바닐라 드래곤전)", memberCount, target,
				countCrystals(end));
		persist();
	}

	/**
	 * 이 팀이 시련을 쓰기로 했는가. 팀 상태를 못 찾으면 <b>끔</b>이다.
	 *
	 * <p>모를 때 켜는 쪽으로 기울면, 상태를 못 읽은 팀이 아무도 고른 적 없는 체력 2400짜리
	 * 드래곤을 만나게 된다. 모를 때는 <b>기본값</b>과 같은 쪽으로 간다.
	 */
	private static boolean trialsEnabled(MinecraftServer server, UUID teamId) {
		com.sharedfate.team.TeamState state = TeamManager.get(server).stateByTeamId(teamId);
		return state != null && state.dragonTrialsEnabled;
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
				// 최후의 저항이 돌고 있었으면 여기가 「처치」다 — 시련이 사라지고 팀이 무적이
				// 되며 보스바 이름이 되돌아간다. 돌고 있지 않았으면 아무 일도 하지 않는다.
				DragonLastStand.onFightClosed(end, team);
				finished.add(entry.getKey());
				continue;
			}
			// 시련을 끈 팀은 여기서 통째로 지나간다. 바닐라 드래곤전이므로 자리도 카드도
			// 패시브도 없고, 남는 일은 위의 「드래곤이 죽었는가」 하나뿐이다.
			//
			// ⚠ 안쪽 다섯 중 셋(자리 감지·룰렛·위험)은 세션이 자리를 세지 않아 어차피
			// 아무 일도 안 한다. 그래도 한 줄로 묶어 두는 것은 DragonPassives 때문이다 —
			// 그쪽은 카드와 무관하게 「언제나 있는 판」이라 스스로 멈출 근거가 없고, 여기서
			// 안 막으면 시련을 끈 팀도 연쇄 포격을 맞는다.
			//
			// ⚠ 최후의 저항도 이 줄에 함께 걸린다. 그것도 시련의 일부라 끈 팀은 붙박이
			// 드래곤을 만나지 않고 바닐라 드래곤전을 끝까지 한다.
			if (!session.trialsEnabled()) {
				continue;
			}
			List<ServerPlayer> members = membersOf(server, team, end);
			boolean lastStandBegins = detectTriggers(end, dragon, session, now);
			// ⚠ 최후의 저항은 카드가 아니라 별개의 보스전이고, 열리는 순간 아래 셋을 전부
			// 멈춘다 — 룰렛·시련·패시브. 「시련이 전부 멈춥니다」가 이 continue 한 줄이다.
			DragonLastStand.Standing standing = DragonLastStand.tick(server, end, dragon, team,
					session, members, lastStandBegins, now);
			if (standing != DragonLastStand.Standing.OFF) {
				if (standing == DragonLastStand.Standing.ENTERED) {
					// 진입하며 줄에 남아 있던 자리를 비웠다. 저장해 두지 않으면 재시작 뒤에
					// 「선택 대기 중」인 채로 되살아나 룰렛이 영영 안 열리는 팀이 된다.
					persist();
				}
				continue;
			}
			// 입장 연출. 룰렛보다 <b>먼저</b> 돌아야 한다 — 룰렛이 뜨면 TrialFreeze 가 판을
			// 얼려 연출이 그 자리에서 멈춘다. 겹치지 않게 하는 것은 순서가 아니라
			// TrialCatalog.DELAY_SETTLE_TICKS 가 연출 길이와 한 상수로 묶여 있는 것이다.
			TrialEntrance.tick(end, dragon, members, session, now);
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
			// 재소환 시계도 함께 버린다. 남겨 두면 다음 전투의 첫 검사가 앞 전투의 시각을
			// 물려받아 최대 2초 늦게 돈다 — 소환 직후가 가장 어긋나기 쉬운 순간이다.
			finished.forEach(RECALL_AT::remove);
			endTrials();
			persist();
		}
	}

	/**
	 * 마지막 전투가 닫히는 틱에 시련이 판에 걸어 둔 것을 전부 되돌린다.
	 *
	 * <h2>이것이 없어서 실제로 일어나던 일</h2>
	 *
	 * <p>{@link #tickSessions} 는 드래곤이 죽은 틱에 세션을 지우기만 하고 여기를 지나지 않았다.
	 * 실행기들은 마지막 틱을 한 번도 못 받으므로 <b>스스로 되돌릴 기회가 없다.</b>
	 *
	 * <ul>
	 *   <li>「크리스탈 보호막」 — 화살 면역이 켜진 채 남아 월드가 바뀔 때까지 엔드 크리스탈이
	 *       화살에 맞지 않았다</li>
	 *   <li>「밤의 군세」 — 20초가 끝나기 전에 드래곤이 죽으면 엔더맨이 <b>영영 적대</b>로 남았다.
	 *       26.3 바닐라는 이 분노를 풀어 주지 않는다</li>
	 * </ul>
	 *
	 * <h2>세션 하나가 아니라 마지막 하나에서 부른다</h2>
	 *
	 * <p>{@link TrialRisks} 의 상태는 팀별이 아니라 <b>정적 한 벌</b>이다(위험이 값이라 상태를 들
	 * 수 없다). 그래서 팀 하나가 끝날 때마다 비우면 같은 엔드에서 아직 싸우고 있는 다른 팀의
	 * 굳은 칸·표적·잡아 둔 자리까지 함께 지워진다. 드래곤은 차원에 하나뿐이라 그 드래곤이
	 * 죽으면 어차피 모든 세션이 같은 틱에 닫히므로, 「남은 세션이 없을 때」로 미뤄도 되돌리는
	 * 시점은 달라지지 않는다.
	 *
	 * <p>⚠ 실행기들은 이 길이 없던 때에 <b>저마다 안전장치를 만들어 두었다</b>
	 * ({@code TrialDryWorld.BAN_GRACE_TICKS} 기한, {@code TrialHotbarLock.LAPSE_TICKS} +
	 * {@code seenAt}, {@code TrialNightHost.fighting}). 그것들은 걷어내지 않았다 — 서버 강제
	 * 종료처럼 여기를 지나지 못하는 길이 아직 남아 있기 때문이다.
	 */
	private static void endTrials() {
		if (!SESSIONS.isEmpty()) {
			return;
		}
		TrialRisks.clearState();
	}

	/**
	 * 전투 진행도를 보고 자리가 터졌는지 본다.
	 *
	 * <p>체력은 <b>강화된 최대치</b> 기준이다. 크리스탈로 회복해 문턱을 오르내려도
	 * {@link DragonTrialSession#fire} 가 처음 한 번만 센다.
	 *
	 * <h2>⚠ 입장 연출이 도는 동안에는 크리스탈을 세지 않는다</h2>
	 *
	 * <p>{@link TrialEntrance} 가 열 개를 한꺼번에 거두고 잠시 뒤 되살린다. 그 사이에 세면
	 * <b>팀이 아무것도 하지 않았는데</b> 「첫 크리스탈」과 「크리스탈 전멸」이 같은 틱에 터진다.
	 *
	 * <p>그래서 기준값({@link #CRYSTALS_AT_START})을 <b>전투가 열릴 때가 아니라 연출이 끝난 뒤에</b>
	 * 처음 적는다. 그때 서 있는 개수가 이 전투의 기준이고, 되살아난 크리스탈은 무적도 풀려 바닐라와
	 * 같은 상태다. 부활이 <b>시작될 때</b> 이미 크리스탈이 서는데도 연출이 완전히 끝날 때까지
	 * 미루는 이유는 {@link TrialEntrance} 클래스 설명에 있다 — 그 구간에 사람이 기둥 위로 올라오면
	 * 그 크리스탈이 거둬지므로, 거기서 기준값을 적었다면 「첫 크리스탈」이 사람 하나가 올라선
	 * 것만으로 터진다.
	 *
	 * @param now 지금 게임 시각. 입장 연출이 아직 도는지를 이 값으로 판단한다
	 * @return {@code HEALTH_30} 이 <b>이번 틱에 처음</b> 터졌는가. 곧 최후의 저항이 열리는
	 *         순간인가다. 진입 연출은 딱 한 번만 돌아야 하는데 「처음 한 번」을 아는 곳이
	 *         {@link DragonTrialSession#fire} 하나뿐이라 그 답을 여기서 내보낸다 —
	 *         {@code DragonLastStand} 가 체력 비율을 다시 재면 문턱이 두 곳이 된다
	 */
	private static boolean detectTriggers(ServerLevel end, EnderDragon dragon,
			DragonTrialSession session, long now) {
		if (TrialEntrance.managesCrystals(session, now)) {
			// 기준값도 적지 않는다. 연출이 끝난 뒤 첫 틱에 그때의 개수로 처음 적힌다.
			CRYSTALS_AT_START.remove(session.teamId());
		} else {
			int crystals = countCrystals(end);
			// ⚠ 0 을 기준값으로 굳히지 않는다. 굳히면 아래 `atStart > 0` 이 영영 거짓이라
			// 크리스탈 자리 둘이 그 전투 내내 죽는다 — 기둥이 없는 판이나 되살릴 자리를 하나도
			// 못 찾은 경우가 그 길이다. 안 적어 두면 크리스탈이 생기는 첫 틱에 적힌다.
			Integer recorded = CRYSTALS_AT_START.get(session.teamId());
			if (recorded == null && crystals > 0) {
				recorded = crystals;
				CRYSTALS_AT_START.put(session.teamId(), crystals);
			}
			int atStart = recorded == null ? 0 : recorded;
			if (atStart > 0) {
				if (crystals < atStart) {
					session.fire(TrialCatalog.Trigger.FIRST_CRYSTAL);
				}
				if (crystals == 0) {
					session.fire(TrialCatalog.Trigger.ALL_CRYSTALS);
				}
			}
		}

		float max = dragon.getMaxHealth();
		if (!(max > 0.0F)) {
			return false;
		}
		float ratio = dragon.getHealth() / max;
		if (ratio <= 0.80F) {
			session.fire(TrialCatalog.Trigger.HEALTH_80);
		}
		if (ratio <= 0.50F) {
			session.fire(TrialCatalog.Trigger.HEALTH_50);
		}
		// 문턱 값은 DragonLastStand.ENTRY_HEALTH_RATIO 와 같아야 한다. 저쪽은 이 값을 읽지
		// 않고 「터졌는가」만 받으므로 실제로 재는 곳은 여기 하나다.
		return ratio <= DragonLastStand.ENTRY_HEALTH_RATIO
				&& session.fire(TrialCatalog.Trigger.HEALTH_30);
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
	 * 끝난 화면의 결과를 실제로 쌓는다.
	 *
	 * <p>자리를 <b>여기서야</b> 줄에서 꺼낸다. 열 때 꺼내면 연출 도중 서버가 내려갔을 때 그
	 * 자리가 통째로 사라진다. 지금은 연출만 사라지고 자리는 줄에 남아 다시 뜰 때 처음부터 돈다.
	 *
	 * <p>⚠ <b>얼음을 안 쓰는 자리는 이 길로 오지 않는다.</b>
	 * {@link TrialCatalog.Reveal#SILENT} 은 판을 멈추지 않으므로 「얼음이 끝나는 틱」이 아예
	 * 없다 — 그쪽은 뽑은 그 틱에 {@link #applyChoice} 를 직접 부른다.
	 */
	private static void applyFinishedTrial(MinecraftServer server) {
		TrialFreeze.Finished done = TrialFreeze.poll();
		if (done == null) {
			return;
		}
		DragonTrialSession session = SESSIONS.get(done.teamId());
		if (session == null) {
			// 세션이 사라졌어도 지연은 지운다. 앞 자리가 쓰던 시각을 남겨 두면 다음 전투의
			// 첫 자리가 그것을 물려받는다.
			resetTrialDelay(done.teamId());
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		long now = end == null ? server.overworld().getGameTime() : end.getGameTime();
		applyChoice(session, done.trialId(), now);
	}

	/**
	 * 뽑힌 카드를 줄 맨 앞의 자리에 얹어 실제로 쌓는다.
	 *
	 * <h2>연출이 셋인데 「확정」은 한 자리여야 한다</h2>
	 *
	 * <p>{@link TrialCatalog.Reveal} 마다 카드가 정해지는 시점이 다르다 — 룰렛과 정해진 카드
	 * 화면은 <b>얼음이 끝나는 틱</b>, {@link TrialCatalog.Reveal#SILENT} 은 <b>뽑는 그 틱</b>이다.
	 * 그래도 「줄에서 꺼내고 · 쌓고 · 지연을 다시 세고 · 저장한다」는 넷은 어느 쪽이든 같아야
	 * 하므로 여기 한 곳에 둔다. 연출을 하나 더 만드는 사람은 뽑기만 하고 여기로 넘기면 된다.
	 *
	 * @param now 지금 게임 시각. 이 카드의 위험 주기를 여기서부터 센다
	 */
	private static void applyChoice(DragonTrialSession session, @Nullable String trialId,
			long now) {
		// 줄 맨 앞이 바뀌었으므로 지연을 다시 센다. 앞 자리가 쓰던 시각을 남겨 두면 다음 자리가
		// 자기 지연 대신 그것을 물려받는다.
		resetTrialDelay(session.teamId());
		TrialCatalog.Trigger trigger = session.beginChoice();
		if (!session.choose(trialId, now)) {
			session.skipChoice();
			persist();
			return;
		}
		TrialCatalog.Trial trial = TrialCatalog.byId(trialId);
		SharedFateMod.LOGGER.info("[END] {} 에서 시련 {}장째 — {} (줄에 {}개 남음)",
				trigger == null ? "?" : trigger.label(), session.trialCount(),
				trial == null ? trialId : trial.name(), session.queuedCount());
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
	 * 자리가 터지고 그 자리가 정한 지연이 지나면 카드를 정한다.
	 *
	 * <h2>「어떻게 뜨는가」를 읽는 유일한 자리다</h2>
	 *
	 * <p>{@link TrialCatalog.Trigger#reveal()} 이 갈리는 곳이 여기 하나다. 값은 자리에 붙어
	 * 있고({@link TrialCatalog.Reveal}) 그 값을 행동으로 바꾸는 것은 이 메서드다 — 자리를 새로
	 * 만드는 사람은 연출을 고르기만 하면 되고, 연출을 새로 만드는 사람은 여기 한 곳만 본다.
	 *
	 * <p>{@code default} 없는 <b>switch 식</b>으로 가른다. {@link TrialCatalog.Reveal} 에 값을
	 * 더하고 여기에 갈래를 안 붙이면 <b>컴파일이 거절한다.</b> 이 저장소가
	 * {@code TrialCatalog.Risk} 와 {@code GameOverCountdown.Reason} 에서 이미 쓰는 방식이다 —
	 * <b>switch 문으로 바꾸지 말 것.</b> 문은 열거형을 다 덮지 않아도 컴파일이 통과해서, 연출을
	 * 하나 더 만든 사람의 자리가 조용히 아무 일도 안 하게 된다.
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
		// 연출이 무엇이든 뽑는 자리는 하나다. 여기를 갈래마다 따로 두면 「어느 카드가 뽑히는가」가
		// 연출에 따라 달라지고, 그 차이는 눈으로 봐서는 알 수 없다.
		TrialRoulette draw = TrialRoulette.open(trigger, pool, now, end.getRandom());
		if (draw == null) {
			return;
		}
		// 여기서부터 자리는 null 이 아니다 — 풀이 비어 있지 않다는 것이 이미 그 뜻이다
		// (offerable 은 자리를 모르면 빈 목록을 준다). 룰렛이 들고 있는 것을 쓰면 그 사실이
		// 코드에도 남는다.
		TrialCatalog.Trigger drawnAt = draw.trigger();
		TrialCatalog.Trial result = draw.result();

		// 「지금 이 틱에 카드를 확정해야 하는가」. 얼음을 쓰는 연출은 거짓이다 — 그쪽은
		// 얼음이 끝나는 틱에 applyFinishedTrial 이 확정한다(연출 도중 서버가 내려가도 자리가
		// 통째로 사라지지 않게 하려고 미뤄 둔 것이다).
		boolean decideNow = switch (drawnAt.reveal()) {
			case ROULETTE -> {
				openRouletteScreen(server, session, members, drawnAt, pool, result);
				yield false;
			}
			case FIXED_SCREEN -> {
				openFixedScreen(server, session, members, drawnAt, result);
				yield false;
			}
			// 판을 멈추지도, 카드 화면을 띄우지도 않는다. 카드만 조용히 걸린다.
			//
			// ⚠ 얼음이 없으면 「얼음이 끝나는 틱」도 없다. 미뤄 두면 카드가 영영 안 걸리므로
			// 여기서 확정한다. 미룰 이유였던 「연출 도중 서버가 내려간다」도 성립하지 않는다 —
			// 연출이 없어 뽑는 틱과 쌓는 틱이 같고, 그 사이에 내려갈 틈이 없다.
			//
			// 화면이 없다고 아무것도 알리지 않으면 「달성했는데 왜 아무 일도 없지」가 된다.
			// 사람이 체력 80% 에서 카드 화면을 걷어내며 「화면에 강화만 시켜주고」라고 했고,
			// 그 「강화」가 TrialEmpower 다 — 무엇이 걸렸는지는 말하지 않고 「세졌다」만 말한다.
			// ⚠ 어느 카드가 걸렸는지를 여기서 읽지 않는다. 자리가 늘어도 같은 신호를 쓴다.
			case SILENT -> {
				TrialEmpower.play(end, findDragon(end), members);
				yield true;
			}
		};
		if (decideNow) {
			applyChoice(session, result.id(), now);
		}
	}

	/**
	 * 룰렛을 돌리는 자리. 판을 멈추고 후보 전부를 보낸다.
	 *
	 * <p>정지를 <b>먼저</b> 건다. 화면만 띄우고 시간이 흐르면 글을 읽는 동안 맞는다. 얼지 못하면
	 * ({@link TrialFreeze#begin} 이 거짓) 화면도 보내지 않는다 — 자리는 줄에 남아 다음 틱에 다시
	 * 시도한다.
	 */
	private static void openRouletteScreen(MinecraftServer server, DragonTrialSession session,
			List<ServerPlayer> members, TrialCatalog.Trigger trigger,
			List<TrialCatalog.Trial> candidates, TrialCatalog.Trial result) {
		int spinTicks = TrialRoulette.TOTAL_TICKS;
		if (!TrialFreeze.begin(server, session.teamId(), result.id(), members,
				spinTicks + TrialFreeze.HOLD_TICKS)) {
			return;
		}
		sendTrialScreen(members, trigger, candidates, result, spinTicks, TrialFreeze.HOLD_TICKS);
	}

	/**
	 * 판은 멈추되 <b>룰렛은 돌지 않는</b> 자리. 정해진 카드의 이름과 설명만 보여 준다.
	 *
	 * <h2>⚠ 지금 이 길로 오는 자리가 하나도 없다 — 그래도 지우지 말 것</h2>
	 *
	 * <p>{@code Trigger.HEALTH_80} 이 유일한 사용자였는데 사람이 카드 화면을 걷어내
	 * {@link TrialCatalog.Reveal#SILENT} 로 옮겼다. {@code switch} 갈래는 남아 있지만
	 * <b>실행되지 않는다.</b> 까닭과 되살릴 조건은 {@link TrialCatalog.Reveal#FIXED_SCREEN} 에
	 * 적어 두었다.
	 *
	 * <h2>후보를 한 장만 보낸다</h2>
	 *
	 * <p>{@code spinTicks} 를 0 으로 두는 것만으로도 화면은 굴리지 않는다. 그런데 후보 목록까지
	 * 한 장으로 줄이는 것은 <b>굴릴 거리 자체를 없애기 위해서다</b> — 화면은 받은 후보 전부로
	 * 글자 배율과 판 높이를 재므로, 안 보여 줄 카드를 실어 보내면 보이지도 않는 이름에 맞춰
	 * 판이 커진다. 게다가 누가 나중에 굴림 길이를 잘못 채워도 <b>돌릴 것이 없다.</b>
	 *
	 * <p>붙잡아 두는 시간이 룰렛과 다른 이유는 {@link TrialFreeze#FIXED_HOLD_TICKS} 에 적어
	 * 두었다.
	 */
	private static void openFixedScreen(MinecraftServer server, DragonTrialSession session,
			List<ServerPlayer> members, TrialCatalog.Trigger trigger, TrialCatalog.Trial result) {
		if (!TrialFreeze.begin(server, session.teamId(), result.id(), members,
				TrialFreeze.FIXED_HOLD_TICKS)) {
			return;
		}
		sendTrialScreen(members, trigger, List.of(result), result, 0,
				TrialFreeze.FIXED_HOLD_TICKS);
	}

	/**
	 * 후보와 결과를 한 번에 보낸다. <b>연출은 클라이언트가 돌린다.</b>
	 *
	 * <p>칸이 바뀔 때마다 보내면 4초에 열다섯 번이고 그중 하나만 늦어도 화면이 튄다. 결과는 이미
	 * 정해져 있으므로 늦게 닿아도 답이 달라지지 않는다.
	 *
	 * @param spinTicks 굴림 길이. <b>0 이면 한 틱도 굴리지 않는다</b>
	 * @param holdTicks 결과를 붙잡아 두는 시간. {@link TrialFreeze} 에 넘긴 값과 같아야 한다 —
	 *                  어긋나면 화면이 먼저 닫혀 얼어 있는 채로 서 있거나, 시간이 먼저 흘러
	 *                  화면 뒤에서 드래곤이 움직인다
	 */
	private static void sendTrialScreen(List<ServerPlayer> members,
			@Nullable TrialCatalog.Trigger trigger, List<TrialCatalog.Trial> candidates,
			TrialCatalog.Trial result, int spinTicks, int holdTicks) {
		List<TrialRoulettePayload.TrialOption> options = new ArrayList<>();
		for (TrialCatalog.Trial trial : candidates) {
			options.add(new TrialRoulettePayload.TrialOption(
					trial.id(), trial.name(), trial.description()));
		}
		TrialRoulettePayload payload = new TrialRoulettePayload(
				trigger == null ? "시련" : trigger.label(),
				Math.max(0, candidates.indexOf(result)), spinTicks, holdTicks, options);
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

	/**
	 * ⚠ <b>지금 엔드에 서 있는 팀원만.</b> 전투 중에 팀원에게 무엇이든 하는 코드는 이 목록을 쓴다.
	 *
	 * <h2>차원을 여기서 가르는 이유</h2>
	 *
	 * <p>이 목록은 {@link TrialRisks} 와 {@link DragonPassives} 를 거쳐 실행기 전부에 그대로
	 * 흘러간다. 실행기들은 <b>중앙 {@code (0, ?, 0)} 에서 잰 수평 거리</b>로 판정하는데, 좌표에는
	 * 차원이 없다 — <b>오버월드 원점 근처에 선 팀원이 엔드의 고리에 맞고 묶인다.</b>
	 * 「연쇄 포격」({@code DragonFireBarrage.detonate})·{@code TrialDragonFocus} ·
	 * {@code TrialEnderPulse} 가 모두 그 길이었고, 그중 연쇄 포격은 이미 시험 서버에 올라가 있다.
	 *
	 * <p>실행기마다 따로 거르게 두지 않는다. 열몇 개가 각자 같은 한 줄을 적어야 하고, 하나라도
	 * 빠뜨리면 그 카드만 오버월드를 때린다 — 컴파일도 시험도 조용한 종류의 사고다. 그래서
	 * <b>목록을 만드는 이 한 자리</b>에서 자른다.
	 *
	 * <p>{@code TrialLandingShock} 과 {@code TrialEnderStorm} 이 자기 파일 안에서 한 번 더 거른다.
	 * 이제는 중복이지만 남겨 둔다 — 걸러진 목록을 받는다는 보장이 그 파일 밖에 있기 때문이다.
	 *
	 * @param end 엔드 월드. 여기 서 있는 사람만 돌려준다
	 */
	private static List<ServerPlayer> membersOf(MinecraftServer server, ShareTeam team,
			ServerLevel end) {
		List<ServerPlayer> inEnd = new ArrayList<>();
		for (ServerPlayer member : onlineMembers(server, team)) {
			if (member.level() == end) {
				inEnd.add(member);
			}
		}
		return inEnd;
	}

	/**
	 * 접속해 있는 팀원 전부. <b>차원을 가리지 않는다.</b>
	 *
	 * <p>쓰는 곳이 둘뿐이고 둘 다 <b>엔드 밖에 있는 사람을 찾는 것이 목적</b>이다 — 도착을
	 * 알리는 자막({@link #detectArrival})과 끌어오는 텔레포트({@link #summonTeam}). 전투 중에
	 * 팀원에게 무엇이든 하는 코드는 이쪽이 아니라 {@link #membersOf} 를 쓸 것.
	 */
	private static List<ServerPlayer> onlineMembers(MinecraftServer server, ShareTeam team) {
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

	/**
	 * 이 팀이 <b>전원 소환을 기다리는 중</b>인가. 세션이 열리기 전의 {@value #SUMMON_DELAY_TICKS}
	 * 틱 구간이다.
	 *
	 * <h2>왜 밖으로 여는가</h2>
	 *
	 * <p>엔드 입장을 감지한 뒤 전원을 옮길 때까지 3초가 비어 있다({@link #SUMMON_DELAY_TICKS}).
	 * 그 사이에는 {@link #sessionOf} 가 아직 {@code null} 이라, <b>밖에서 보면 전투가 열리지
	 * 않은 것과 구별되지 않는다.</b> 그래서 {@code EndFightTeleportLock} 이 그 3초를 덮으려고
	 * 「팀원 하나가 이미 엔드에 있으면」으로 <b>근사</b>하고 있었다.
	 *
	 * <p>근사가 나쁜 것은 틀려서가 아니라 <b>「전투가 열리는 중인가」의 답이 두 곳에 있게
	 * 되기 때문</b>이다. 입장 판정을 고치면 두 군데를 봐야 하고, 한쪽만 고치면 3초짜리 구멍이
	 * 조용히 돌아온다. 사실을 들고 있는 것은 {@link #PENDING_SUMMON} 하나이므로 그것을 연다.
	 *
	 * <p>⚠ {@link #sessionOf} 와 <b>겹치지 않는다.</b> 소환이 끝나면 같은 틱에
	 * {@link #PENDING_SUMMON} 에서 빠지고 {@link #SESSIONS} 에 들어간다 — 곧 「전투가 열려
	 * 있는가」는 둘을 <b>함께</b> 물어야 한다.
	 */
	public static boolean pendingSummon(@Nullable UUID teamId) {
		return teamId != null && PENDING_SUMMON.containsKey(teamId);
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 남은 상태를 버린다. */
	public static void clearState() {
		SESSIONS.clear();
		PENDING_SUMMON.clear();
		CRYSTALS_AT_START.clear();
		READY_AT.clear();
		RECALL_AT.clear();
		TrialFreeze.reset();
		TrialRisks.clearState();
		DragonPassives.clearState();
		// ⚠ 여기는 SERVER_STOPPED 에서도 불려 월드를 만질 수 없다. 저쪽이 정적 상태만
		// 버리는 까닭과, 나머지를 어디서 되돌리는지는 DragonLastStand.clearState 에 있다.
		DragonLastStand.clearState();
	}

	/** 시험·명령용. 지금 당장 전투를 연다. */
	public static void forceStart(MinecraftServer server, ShareTeam team) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null || team == null) {
			return;
		}
		SESSIONS.remove(team.teamId());
		resetTrialDelay(team.teamId());
		RECALL_AT.remove(team.teamId());
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
