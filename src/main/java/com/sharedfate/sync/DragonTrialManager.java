package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.mixin.EnderDragonFightAccessor;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.level.dimension.end.EnderDragonFight;
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
 * <h2>엔드는 걸어서 나갈 수 없다 — 그러나 <b>접속을 끊을 수는 있다</b></h2>
 *
 * <p>드래곤을 잡기 전에 엔드를 나가는 길은 죽는 것뿐이고, 이 모드에서 그건 곧 전멸이자 월드
 * 초기화다. 끝나는 길은 <b>드래곤 사망</b>과 <b>팀 전멸</b>(그리고 팀 해체) 셋뿐이다.
 *
 * <p>⚠⚠ 전에는 여기에 「전원 이탈은 일어날 수 없다」고 적혀 있었다. <b>틀렸다</b> — 팀원이 전부
 * <b>접속을 끊으면</b> 엔드에서 아무도 안 나갔는데 엔드가 빈다. 그러면 바닐라가 섬 청크 티켓을 떼어
 * 드래곤 개체가 메모리에서 빠지고, 이 파일은 그것을 「드래곤 처치」로 읽어 세션을 닫았다(2026-10-18
 * 본서버 로그 — 「left the game」 1초 뒤 「드래곤 처치」). 최후의 저항 중이면 팀이 무적이 됐고, 체력
 * 강화가 저장되지 않아 드래곤이 다시 올라오며 회복했다.
 *
 * <p>지금은 그것을 <b>전투의 일시 중단</b>으로 읽는다 — {@link DragonTrialSuspend}. 처치는 드래곤이
 * <b>진짜로 죽었을 때</b>만이고, 엔드가 비거나 드래곤이 내려가면 세션을 그대로 두고 시련만 멈춘다.
 * 팀원이 엔드로 돌아와 드래곤이 다시 올라오면 그 자리에서 잇는다 — 수락창도 입장 연출도 다시 띄우지
 * 않는다(연출 도중에 끊겼으면 연출만 처음부터). 배선은 {@link #tickSessions} 의 판정 세 줄이고,
 * 멈추는 것과 잇는 것은 {@link #suspend} · {@link #resume} 두 곳이다. <b>서버가 내려가는 것도 같은
 * 길</b>이다({@link #onServerStopping}).
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
 * 보이므로 잠깐 예고하고 옮긴다.
 *
 * <h2>입장 수락창 — 묻지만 말리지는 않는다</h2>
 *
 * <p>전에는 이 자리에 「확인을 묻지는 않는다」가 적혀 있었다. <b>2026-10-01 에 사람이 묻는 창을
 * 넣으라고 했다</b> — 「첫 엔더로 입장하면 한명이라도 엔더에 입장하면 정지하고 (시련중이면) 시련을
 * 시작합니다. 라고 수락창이 뜨고 리더가 확인 을 누루면 …」.
 *
 * <p>⚠ <b>그래도 「돌아올 수 없는 문턱」은 그대로다.</b> 창에는 <b>「확인」 단추 하나뿐</b>이고,
 * 리더가 끝내 누르지 않거나 접속을 끊어도 <b>수락한 것과 똑같이 진행된다</b>
 * ({@link TrialEntranceGate} 의 「교착을 만들지 않는다」). 창이 하는 일은 말리는 것이 아니라
 * <b>판이 바뀌기 전에 팀을 한 번 멈춰 세우는 것</b>이고, {@link #SUMMON_DELAY_TICKS} 의 3초
 * 예고가 하던 몫을 훨씬 분명하게 대신한다.
 *
 * <p>⚠ <b>시련을 끈 팀에게는 창이 뜨지 않는다.</b> 1장이 「끄면 완전한 바닐라」라고 못박고 있다 —
 * 그 팀은 예전 길(3초 예고 뒤 전원 소환) 그대로다. <b>소환 자체는 끄지 않는다</b>(위 「왜 전원을
 * 부르는가」).
 */
public final class DragonTrialManager {
	/** 소환까지 기다리는 시간. 예고이지 말리는 장치가 아니다. */
	private static final int SUMMON_DELAY_TICKS = 60;
	/** 도착 직후 무적. 떨어지자마자 브레스에 맞아 팀이 절반 깎이는 것을 막는다. */
	static final int ARRIVAL_GRACE_TICKS = 60;
	/**
	 * 드래곤 체력 강화 수정자의 id.
	 *
	 * <p>⚠ 2026-10-18 부터 <b>저장되는</b> 수정자다({@code addOrReplacePermanentModifier}). 전에는 저장되지
	 * 않는 수정자라 드래곤이 청크와 함께 내려갔다 올라오거나 서버가 다시 뜨면 최대 체력이 바닐라 200 으로
	 * 돌아가고, 저장된 체력도 그 200 에 잘렸다 — 26.3 {@code LivingEntity.readAdditionalSaveData} 가
	 * 속성을 먼저 읽고 {@code setHealth(Health)} 를 뒤에 하므로, 수정자가 함께 저장되어야 체력이 안
	 * 잘린다(바이트코드로 확인). 걷어내는 길은 {@link #releaseOrphanDragon} 한 곳 — 시련 세션이 하나도
	 * 없는 판의 드래곤에서 뗀다.
	 */
	private static final net.minecraft.resources.Identifier HEALTH_MODIFIER_ID =
			SharedFateMod.id("trial/dragon_health");
	/**
	 * 마지막으로 본 드래곤 체력 · 최대 체력. 드래곤이 <b>먼저 내려가고</b> 그 뒤에 멈추는 길
	 * ({@link DragonTrialSuspend.Verdict#ABSENT})에서 지킬 체력이 이것이다. 저장하지 않는다 — 재시작한
	 * 판은 세션이 적어 둔 값({@code DragonTrialSession.dragonHealth})을 쓴다.
	 */
	private static float lastSeenHealth = Float.NaN;
	private static float lastSeenMax = Float.NaN;
	/** 주인 없는 드래곤을 훑는 간격(틱). 매 틱 엔드 개체를 뒤질 까닭이 없다. */
	private static final int ORPHAN_SWEEP_TICKS = 20;

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

	/**
	 * 팀마다 지난 틱 엔드에 서 있던 팀원 — 재개 직후 보호({@link #protectArrivals})가 「새로 온 사람」을 가르는 명단.
	 * 멈출 때 지운다 — 이은 틱에는 엔드에 선 전원이 새로 온 사람이다. 저장하지 않는다.
	 */
	private static final Map<UUID, java.util.Set<UUID>> ARRIVED = new HashMap<>();

	/**
	 * 세션 루프가 실행기(자리 감지 · 최후의 저항 · 입장 연출 · 룰렛 · 패시브 · 위험)를 마지막으로 돌린
	 * 게임 시각. {@link #executorsDue} 가 이것과 지금을 견준다.
	 *
	 * <h2>이것이 없어서 일어나던 일 (2026-10-06 검토에서 확정된 문제)</h2>
	 *
	 * <p>판이 얼면 — 시련 룰렛·고정 화면({@code TrialFreeze}), 증강 선택({@code PerkChoiceSession}),
	 * 운영자의 {@code /tick freeze} — 26.3 {@code ServerLevel.tick} 이 {@code runsNormally()} 가
	 * 거짓인 틱에 {@code tickTime} 을 건너뛰어 <b>게임 시각이 선다.</b> 그런데 이 파일은
	 * {@code END_SERVER_TICK} 에서 돌아 <b>매 서버 틱</b> 불린다. 실행기는 전부 「{@code now} 가 틱마다
	 * 하나씩 는다」에 기대 {@code firesAt(now, …)}·{@code stageJustChanged}·{@code blastingIndex} 로
	 * 발동을 고르므로, <b>정지가 발동 틱에 걸리면 그 틱이 수백 번(룰렛 하나에 337번) 다시 돌았다</b> —
	 * 폭발음·번개 엔티티 생성·자리 폭격 튕김이 정지 내내 되풀이되고, 피해 차단이 없는
	 * {@code /tick freeze} 에서는 포격 {@code strike} 가 쿨타임을 지우며 매 틱 들어가 전멸할 수 있었다.
	 *
	 * <h2>「얼었는가」가 아니라 「같은 시각인가」를 본다</h2>
	 *
	 * <p>실행기가 기대는 사실은 「한 {@code now} 에 한 번」이다. 그래서 정지 깃발 셋(시련 정지 · 증강
	 * 선택 · 바닐라 정지)을 하나하나 세지 않고 <b>결과</b>인 게임 시각을 본다 — 정지하는 길이 하나 늘어도
	 * 이 줄은 바뀌지 않는다. {@code /tick step} 처럼 얼어 있어도 시각이 오르는 틱은 그대로 돈다.
	 *
	 * <h2>건너뛰는 것과 건너뛰지 않는 것</h2>
	 *
	 * <ul>
	 *   <li><b>자리 감지({@link #detectTriggers})도 건너뛴다.</b> 거기서 {@code HEALTH_30} 이 처음
	 *       터지면 그 답({@code lastStandBegins})을 같은 틱의 {@code DragonLastStand.tick} 이 받아야
	 *       진입 연출이 돈다. 감지만 돌리고 최후의 저항을 건너뛰면 그 답이 버려져, 풀린 뒤에는
	 *       「이미 터졌다 → 재시작 뒤 되살리기」로 읽혀 연출 없이 열린다</li>
	 *   <li><b>보호막 맥박은 건너뛰지 않는다</b> — {@code DragonLastStand.holdShield}</li>
	 *   <li>⚠ <b>{@code TrialFreeze.tick} 은 이 문 밖이다.</b> 그것이 정지를 풀어 주는 시계라
	 *       건너뛰면 영원히 언다. {@link #tick} 첫 줄에서 그대로 돈다</li>
	 *   <li>패턴 타이머 HUD({@link #publishTimers})도 이 문 밖이다 — 얼어 있는 동안
	 *       {@code frozen = true} 를 실어 보내는 것이 받는 쪽 카운트다운을 세우는 신호다</li>
	 *   <li>팀이 사라진 세션과 드래곤이 죽은 세션을 닫는 일도 문 밖이다 — 얼어 있어도 닫혀야 한다</li>
	 * </ul>
	 *
	 * <p>저장하지 않는다. 재시작하면 첫 틱에 한 번 도는 것이 맞다.
	 */
	private static long executorsRanAt = Long.MIN_VALUE;

	/** 전투 상태를 적어 두는 곳. 서버가 뜰 때 정해진다. */
	private static @Nullable java.nio.file.Path stateFile;

	private DragonTrialManager() {
	}

	/**
	 * 서버가 뜰 때. 진행 중이던 전투를 되살린다.
	 *
	 * <p>드래곤 체력 수정자는 개체에 붙어 월드와 함께 저장되지만(2026-10-18 부터 — 그 전에는
	 * 저장되지 않는 수정자라 이 문장이 틀려 있었고, 재시작한 전투의 드래곤이 최대 체력 200 으로 남았다)
	 * 카드와 누적은 메모리에만 있다. 되살리지 않으면 체력만 강화된 채 시련 0 장인 어긋난 상태가 된다.
	 *
	 * <p>되살린 세션은 <b>일시 중단</b>으로 읽힌다 — 서버가 내려갈 때 {@link #onServerStopping} 이 멈춰
	 * 두었고, 강제 종료로 그 줄을 못 지났어도 첫 틱에 엔드에 아무도 없으니 {@link #tickSessions} 가
	 * 멈춘다. 팀원이 돌아와 드래곤이 올라오면 {@link #resume} 이 강화를 다시 확인하고 적어 둔 체력으로
	 * 되돌린다.
	 */
	public static void onServerStarted(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		// ⚠ 월드 폴더다(2026-10-18 검토). 서버 폴더에 두면 루프 스크립트가 월드만 지운 새 회차에 옛 회차의
		// 「일시 중단」 전투가 되살아나 팀을 엔드로 끌고 갔다 — DragonTrialStore 클래스 설명.
		java.nio.file.Path worldRoot = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
		stateFile = DragonTrialStore.locate(worldRoot);
		java.nio.file.Path legacy = DragonTrialStore.legacy(server.getServerDirectory(), worldRoot);
		if (legacy != null && java.nio.file.Files.isRegularFile(legacy)) {
			// 어느 월드의 전투인지 알 수 없다 — 읽지 않고 지운다. 남겨 두면 운영자가 「아직 있다」로 헷갈린다.
			try {
				java.nio.file.Files.deleteIfExists(legacy);
				SharedFateMod.LOGGER.warn("[END] 서버 폴더의 옛 엔드 전투 저장 파일을 지웠습니다 — 이제 월드 폴더에"
						+ " 저장합니다(어느 월드의 전투인지 알 수 없어 잇지 않습니다): {}", legacy);
			} catch (java.io.IOException error) {
				SharedFateMod.LOGGER.warn("[END] 서버 폴더의 옛 엔드 전투 저장 파일을 지우지 못했습니다(읽지 않고 둡니다): {}",
						legacy, error);
			}
		}
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
			boolean wanted = trialsEnabled(server, teamId);
			// 시련 세션은 하나다(trialHolder). 이 규칙이 생기기 전의 저장 파일은 시련 세션 둘을
			// 들고 있을 수 있다 — 먼저 되살린 하나만 시련을 잇고 나머지는 끔으로 되살린다.
			// ⚠ 여기는 멈춘 세션까지 센다 — 되살린 세션은 전부 멈춘 채라 trialHolder(싸우는 세션만)로는 둘 다 켬이 된다.
			boolean trials = wanted && anyTrialSession(SESSIONS.values(), teamId) == null;
			if (wanted && !trials) {
				SharedFateMod.LOGGER.warn("[END] 팀({}) 시련 세션을 끔으로 되살립니다 — 다른 팀이 이미"
						+ " 시련 세션을 들고 있습니다(시련 세션은 한 번에 하나)", teamId);
			}
			DragonTrialSession session = new DragonTrialSession(teamId, entry.startedTick, trials);
			session.restore(entry.chosen, entry.fired, entry.queued, entry.awaitingChoice,
					entry.grantedTicks);
			session.restoreSuspension(entry.suspended, entry.introTick, entry.replayIntro,
					entry.dragonHealth, entry.lastStandWaves);
			session.restorePause(entry.pausedTick, entry.lastStandClock, entry.riskDone);
			SESSIONS.put(teamId, session);
			SharedFateMod.LOGGER.info("[END] 진행 중이던 엔드 전투를 되살렸습니다 — 시련 {} · {}장 · {}",
					trials ? "켬" : "끔(바닐라 드래곤전)", session.trialCount(),
					session.suspended() ? "일시 중단 — 팀원이 엔드로 돌아오면 잇습니다" : "첫 틱에 다시 판정합니다");
		}
		// 서버가 강제로 죽어 우리 엔드 보더가 world_border.dat 에 남았으면 치운다. ⚠ 되살린 시련 전투가
		// 있어도 치운다 — 서버가 막 뜬 틱에는 엔드에 아무도 없어 그 전투는 일시 중단으로 읽히고, 보더는
		// 팀원이 돌아와 전투를 잇는 틱에 다시 선다(DragonTrialSuspend). 전에는 「다음 틱부터 그 전투가
		// 다시 붙든다」였다.
		DragonTrialBorder.onServerStarted(server, false);
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
			// 일시 중단(2026-10-18). 체력은 멈춘 세션만 적는다 — 싸우는 도중의 값은 NaN 이라 null 로 나간다.
			entry.suspended = session.suspended();
			entry.introTick = session.entranceTick();
			entry.replayIntro = session.replayEntrance();
			entry.dragonHealth = session.dragonHealth() > 0.0F ? session.dragonHealth() : null;
			entry.lastStandWaves = session.lastStandWaves();
			// 멈춘 틱 · 지대 시계 · 한 번짜리 카드가 한 일(2026-10-18 검토).
			entry.pausedTick = session.pausedAt() == Long.MIN_VALUE ? null : session.pausedAt();
			entry.lastStandClock = session.lastStandClock() == Long.MIN_VALUE ? null : session.lastStandClock();
			entry.riskDone = new java.util.LinkedHashMap<>(session.riskDone());
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
	 *
	 * <h2>⚠⚠ 엔드로 들어오는 길이 몇이든 <b>목이 여기 하나다</b></h2>
	 *
	 * <p>이 저장소의 제1 함정이 「한쪽만 막으면 반드시 샌다」이고, 사람을 엔드 밖으로 내보내는
	 * 길은 실제로 <b>다섯</b>이라 {@code EndFightTeleportLock} 이 그 다섯을 하나하나 막는다.
	 * 들어오는 쪽은 그 수법을 쓰지 않는다 — <b>길을 세는 대신 결과를 본다.</b> 엔드 포털·귀환
	 * 게이트웨이·{@code /tp}·다른 모드·리스폰·엔드에서 접속 종료 후 재접속, 어느 길로 왔든
	 * 「엔드에 서 있다」는 같으므로 {@code end.players()} 한 줄에 전부 걸린다. 길이 하나 늘어도
	 * 이 파일은 바뀌지 않는다.
	 *
	 * <p>그래서 <b>입장 수락창도 여기에 붙인다.</b> 자물쇠처럼 갈래마다 걸면 그때부터 「샐 수
	 * 있는 길」을 세야 한다.
	 *
	 * <h2>세 가지 가운데 하나라도 있으면 건너뛴다 — 「첫 입장」만이다</h2>
	 *
	 * <p>세션 · 소환 대기 · 수락창. 전투가 이미 열린 뒤에 늦게 들어온 사람에게 창이 또 뜨면
	 * <b>싸우고 있는 팀이 다시 붙들려 단상으로 모인다.</b>
	 *
	 * <h2>시련을 끈 팀에게는 창이 안 뜬다</h2>
	 *
	 * <p>1장이 「끄면 완전한 바닐라」라고 못박고 있다. 창도 붙들기도 없이 예전 길 그대로 — 3초
	 * 뒤에 전원 소환이다. ⚠ <b>소환 자체는 끄지 않는다.</b> 그것은 시련이 아니라 「최종 보스는
	 * 팀이 함께 선다」는 이 모드의 규칙이고, 클래스 설명의 「팀을 엔드로 부르는 것은 끄지
	 * 않는다」가 그 까닭이다.
	 *
	 * <h2>⚠⚠ 첫째 겹 — 이미 끝난 전투에는 창을 띄우지 않는다 (2026-10-01)</h2>
	 *
	 * <p>드래곤을 잡고도 엔드에 서 있는 사람을 이 메서드가 「첫 입장」으로 읽어 <b>무한 고리</b>가
	 * 돌았다. 판단은 {@link #endFightLive(ServerLevel)} 하나에 있고 까닭도 거기 적혀 있다.
	 *
	 * <p>⚠ <b>수락창만 건너뛰어서는 안 된다.</b> 창을 띄우지 않으면 아래 예전 길
	 * ({@link #PENDING_SUMMON} → {@link #startSession})로 그대로 흘러가 <b>같은 고리가 로그
	 * 글자만 바꿔서 돈다</b>(「시련 끔」). 그래서 월드 단위로 한 번 보고 <b>이 메서드를 통째로</b>
	 * 돌아간다 — 드래곤은 차원에 하나뿐이라 팀마다 다시 물을 것이 없다.
	 */
	private static void detectArrival(MinecraftServer server, ServerLevel end, long now) {
		if (!endFightLive(end)) {
			return;
		}
		for (ServerPlayer player : end.players()) {
			ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
			if (team == null) {
				continue;
			}
			UUID teamId = team.teamId();
			if (SESSIONS.containsKey(teamId) || PENDING_SUMMON.containsKey(teamId)
					|| TrialEntranceGate.isOpen(teamId)) {
				continue;
			}
			// 시련을 켠 팀은 먼저 멈춰 세우고 리더에게 묻는다. 창을 띄우지 못했으면(리더가
			// 접속해 있지 않다) 아래 예전 길로 그대로 흘러간다 — 거기가 바닐라와 같은 길이다.
			// ⚠ 다른 팀이 이미 시련 전투 중이면 창을 띄우지 않는다 — 이 팀은 시련 끔으로 열릴
			// 것이라(trialHolder) 「시련을 시작합니다」 창은 거짓이 된다. 창이 둘 동시에 떠 있다가
			// 둘 다 확인되는 경우는 startSession 이 마지막에 가른다.
			if (trialsEnabled(server, teamId) && trialHolder(SESSIONS.values(), teamId) == null
					&& TrialEntranceGate.open(end, team, findDragon(end),
							onlineMembers(server, team), now)) {
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
	 * 떠 있는 입장 수락창을 돌린다. 기다림이 끝나면 <b>팀 전원을 단상으로 모으고 전투를 연다.</b>
	 *
	 * <h2>소환 대기({@link #tickPendingSummons})보다 <b>앞</b>이다</h2>
	 *
	 * <p>둘은 서로 배타적이다 — {@link #detectArrival} 이 창을 띄운 팀은 {@link #PENDING_SUMMON}
	 * 에 들어가지 않는다. 그래도 앞에 두는 것은 같은 틱에 창이 끝나고 전투가 열리는 것이
	 * <b>소환 대기가 보기 전</b>이어야 순서가 하나로 읽히기 때문이다.
	 *
	 * <h2>⚠ 소환 지연 3초를 쓰지 않는다</h2>
	 *
	 * <p>{@link #SUMMON_DELAY_TICKS} 는 「화면이 아무 말 없이 바뀌면 버그처럼 보인다」를 메우려고
	 * 둔 예고였다. 수락창이 그 몫을 <b>훨씬 분명하게</b> 한다 — 리더가 직접 확인을 눌렀으므로
	 * 확인한 그 틱에 옮기는 것이 맞다. 여기서 또 3초를 기다리면 「눌렀는데 아무 일도 안
	 * 일어난다」가 된다.
	 *
	 * <h2>진행하지 않는 길이 하나뿐이다</h2>
	 *
	 * <p>{@link TrialEntranceGate.Outcome#ABANDONED}(팀 전원 접속 종료)와 팀 해체. 나머지 셋은
	 * <b>수락한 것과 똑같이</b> 진행한다 — 까닭은 {@link TrialEntranceGate} 의 「교착을 만들지
	 * 않는다」에 적어 두었다.
	 *
	 * <h2>⚠⚠ 창이 떠 있는 사이에 전투가 끝나면 <b>붙들기를 푼다</b> (2026-10-01)</h2>
	 *
	 * <p>{@link #detectArrival} 이 창을 막는 것만으로는 모자라다. <b>이미 떠 있는 창</b>은
	 * {@link TrialEntranceGate#tick} 이 「확인·시간 초과·리더 이탈·전원 종료」 넷 가운데 하나가
	 * 될 때까지 사람을 붙들어 두므로, 그 사이에 드래곤이 죽으면 <b>최대 60초를 붙들린 채로
	 * 남는다.</b> 공유 체력 판에서 조작이 안 되는 60초는 그 자체로 사고다.
	 *
	 * <p>그래서 {@link TrialEntranceGate#tick} 보다 <b>먼저</b> 보고, 끝난 전투면
	 * {@link TrialEntranceGate#cancel} 로 <b>붙들기를 버리고 남의 화면까지 닫는다.</b>
	 */
	private static void tickEntranceGates(MinecraftServer server, ServerLevel end, long now) {
		boolean live = endFightLive(end);
		for (UUID teamId : TrialEntranceGate.openTeams()) {
			ShareTeam team = TeamManager.get(server).teamById(teamId);
			if (team == null) {
				// 창이 떠 있는 사이에 팀이 해체됐다. 버리지 않으면 그 팀 id 로 전투가 영영
				// 열리지 않는다.
				TrialEntranceGate.abandon(teamId);
				continue;
			}
			if (!live) {
				SharedFateMod.LOGGER.info(
						"[END] 팀 '{}' 입장 수락창을 접습니다 — 이 월드의 드래곤전이 이미 끝났습니다."
								+ " 붙들기를 풉니다", team.name());
				TrialEntranceGate.cancel(teamId, onlineMembers(server, team));
				continue;
			}
			TrialEntranceGate.Outcome outcome =
					TrialEntranceGate.tick(server, team, onlineMembers(server, team), now);
			// default 없는 switch 식이다. Outcome 에 값을 더하고 여기에 갈래를 안 붙이면
			// 컴파일이 거절한다 — 새 결말이 조용히 「기다린다」로 떨어지는 것을 막는다.
			boolean proceed = switch (outcome) {
				case WAIT -> false;
				case ACCEPTED -> true;
				case TIMED_OUT -> {
					SharedFateMod.LOGGER.info(
							"[END] 팀 '{}' 입장 수락창이 {}틱 동안 확인되지 않아 그대로 시작합니다"
									+ " — 엔드 입장은 되돌릴 수 없는 문턱입니다",
							team.name(), TrialEntranceGate.TIMEOUT_TICKS);
					yield true;
				}
				case LEADERLESS -> {
					SharedFateMod.LOGGER.info(
							"[END] 팀 '{}' 리더가 접속을 끊어 입장 수락을 기다리지 않고 시작합니다",
							team.name());
					yield true;
				}
				case ABANDONED -> {
					SharedFateMod.LOGGER.info(
							"[END] 팀 '{}' 전원이 접속을 끊어 입장 수락창을 접었습니다 — 전투를 열지"
									+ " 않습니다", team.name());
					yield false;
				}
			};
			if (!proceed) {
				continue;
			}
			// ⚠ 전투를 먼저 열고 그 뒤에 모은다. 거꾸로 두면 둘째 겹(startSession)이 거절한
			// 틱에도 팀이 이미 단상으로 끌려가 있어, 「확인했는데 아무 일도 안 일어나고 자리만
			// 바뀌었다」가 된다.
			if (!startSession(server, end, team, now)) {
				continue;
			}
			gatherTeam(server, end, team);
		}
	}

	/**
	 * 매 틱. 소환 카운트다운과 시련 타이머를 돌린다.
	 *
	 * <h2>⚠ 이 파일은 {@code END_SERVER_TICK} 에서 <b>가장 먼저</b> 돈다</h2>
	 *
	 * <p>{@code SharedFateMod} 의 등록 순서가 곧 실행 순서인데 {@code DragonTrialManager::tick}
	 * 이 그 줄 맨 앞에 있다. 곧 <b>여기서 사람을 엔드로 옮겨 놓아도 같은 틱의 뒤쪽에서 다른
	 * 것들이 그 자리를 덮어쓸 수 있다.</b> 사람을 옮기는 길이 넷이고(순열 교환 · 집합 ·
	 * 정거장 · 소집의 조각) 전부 우리 뒤에 돈다 — 같은 틱에 부딪히면 <b>엔드 소환이
	 * 구조적으로 진다.</b> 실제로 「엔드에 끌려 들어갔다가 도로 나온다」가 그것이었다.
	 *
	 * <p>지금은 {@code EndFightTeleportLock} 이 그 넷을 막아 부딪히지 않는다. 그래도 이
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
		deliverPausedClosedNotices(server);
		// ⚠ 이 줄은 <b>서버 전체</b>를 끄는 스위치다. 팀 설정의 「드래곤 시련」과 다르다 —
		// 여기서 돌아가면 팀을 엔드로 부르는 것까지 함께 멈춘다. 시련을 끈 팀에게
		// dragonHealthPerMember 를 무시한다고 적었지만, 0 으로 둔 서버에서는 그 팀도 전원
		// 소환을 못 받는다. 이 경로는 시련 설정이 생기기 전부터 있던 것이라 그대로 두었다.
		if (SharedFateMod.config.dragonHealthPerMember <= 0) {
			// 전투가 없는 서버다. 패턴 타이머 HUD 를 받던 사람이 있었다면 한 번 지운다.
			TrialTimers.publish(server, Map.of(), 0L);
			// 전투 도중 이 설정이 0 이 되면 세션이 더는 안 돌므로 보더도 여기서 되돌린다.
			DragonTrialBorder.restore(server.getLevel(Level.END));
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			TrialTimers.publish(server, Map.of(), 0L);
			return;
		}
		long now = end.getGameTime();
		detectArrival(server, end, now);
		// 입장 수락창. 소환 대기보다 앞이다 — 까닭은 그쪽 메서드 설명에 있다.
		tickEntranceGates(server, end, now);
		tickPendingSummons(server, end, now);
		// ⚠ 세션을 돌리기 전이다. tickSessions 는 세션을 지울 수 있고, 지워진 팀을 다시
		// 부르는 것은 「전투가 끝났는데 엔드로 끌려간다」가 된다.
		recallStragglers(server, end, now);
		tickSessions(server, end, now);
		restoreBorderIfIdle(end);
		releaseOrphanDragon(end, now);
		// ⚠ 세션을 다 돌린 <b>뒤</b>다. 실행기가 이번 틱에 터뜨리고 고른 것이 반영된 상태를 읽어야
		// 「방금 터졌다」를 「0.0초」로 띄우지 않는다.
		publishTimers(server, end, now);
	}

	/**
	 * 패턴 타이머 HUD 를 모아 보낸다 — <b>엔드에 서 있고 세션이 열린 팀원</b>에게만.
	 *
	 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
	 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」. 무엇을 담고 언제 보내는지는
	 * {@link TrialTimers} 에 있다. 여기는 <b>누구에게</b>만 정한다 — 팀원 목록을 만드는 길이
	 * {@link #membersOf} 하나라서다(오버월드 원점에 선 사람이 엔드 HUD 를 받지 않는다).
	 *
	 * <p>시련을 끈 팀은 목록에 넣지 않는다 — 바닐라 드래곤전이라 띄울 것이 없다. 목록에서 빠진 사람
	 * 가운데 전에 받은 사람에게는 {@link TrialTimers#publish} 가 지우는 묶음을 한 번 보낸다.
	 */
	private static void publishTimers(MinecraftServer server, ServerLevel end, long now) {
		Map<ServerPlayer, com.sharedfate.net.TrialTimersPayload> audience = new HashMap<>();
		if (!SESSIONS.isEmpty()) {
			boolean frozen = TrialTimers.frozen(server);
			EnderDragon dragon = findDragon(end);
			float healthRatio = dragon == null || !(dragon.getMaxHealth() > 0.0F)
					? Float.NaN
					: dragon.getHealth() / dragon.getMaxHealth();
			// 누가 어느 묶음을 받는지는 TrialTimers.audienceOf 가 고른다 — 2026-10-06 검토에서
			// 확정된 문제로 시험이 굴릴 수 있게 떼어 냈다. 여기 남은 것은 월드에서 팀과
			// 「엔드에 선 팀원」을 찾아 주는 것뿐이다.
			// 일시 중단된 세션은 띄울 것이 없다 — 엔드에 사람이 있어도(드래곤만 내려간 판) 멈춘 시계를
			// 보여 주면 「곧 온다」로 읽힌다.
			List<DragonTrialSession> fighting = new ArrayList<>();
			for (DragonTrialSession session : SESSIONS.values()) {
				if (!session.suspended()) {
					fighting.add(session);
				}
			}
			audience = TrialTimers.audienceOf(fighting, teamId -> {
				ShareTeam team = TeamManager.get(server).teamById(teamId);
				return team == null ? null : membersOf(server, team, end);
			}, session -> TrialTimers.collect(session, frozen, healthRatio, now));
		}
		TrialTimers.publish(server, audience, now);
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
	 *   <li><b>낡은 좌표를 쓰는 순간이동</b>(정거장)이 사람을 엔드 밖에 떨어뜨린다.
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
			// ⚠ 멈춘 전투는 부르지 않는다(2026-10-18 검토). 멈췄다는 것이 곧 「엔드에 아무도 없다」이고, 여기서
			// 부르면 오버월드에 접속한 팀원이 멈춘 전투로 끌려간다 — 옛 회차의 멈춘 세션이 새 회차를 엔드로 끌고
			// 간 사고의 마지막 고리가 이 줄이었다. 팀원이 스스로 엔드에 돌아와 전투를 이으면 그때부터 다시 부른다.
			DragonTrialSession session = SESSIONS.get(teamId);
			if (session == null || session.suspended()) {
				continue;
			}
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

	/**
	 * 재개 직후 3초 보호 — 이번 틱에 <b>새로</b> 엔드에 선 팀원에게 저항 V {@value DragonTrialSuspend#RESUME_GRACE_TICKS}틱.
	 *
	 * <p>사람 결정(2026-10-18) 「이어 가기, 무적 없음」 — 최후의 저항이든 아니든 재개한 틱에는 도착 무적이 없어
	 * 접속해 청크를 받는 동안 포격·카드·패턴에 맞았다(검토). 멈췄다 이은 틱에는 {@link #ARRIVED} 가 비어 있어
	 * 엔드에 선 전원이 받고, 넷이 하나씩 돌아오면 늦게 온 사람도 들어선 틱에 받는다. 수단은 도착 무적
	 * ({@link #ARRIVAL_GRACE_TICKS})과 같다. 전투를 여는 틱의 팀원도 지나가지만 모으기·부르기가 이미 같은 값을
	 * 걸었으므로 바뀌는 것이 없다.
	 */
	private static void protectArrivals(UUID teamId, List<ServerPlayer> members) {
		java.util.Set<UUID> before = ARRIVED.getOrDefault(teamId, java.util.Set.of());
		List<UUID> present = new ArrayList<>();
		for (ServerPlayer member : members) {
			present.add(member.getUUID());
		}
		List<UUID> fresh = DragonTrialSuspend.newArrivals(before, present);
		if (!fresh.isEmpty()) {
			for (ServerPlayer member : members) {
				if (member.isSpectator() || !fresh.contains(member.getUUID())) {
					continue;
				}
				member.addEffect(new MobEffectInstance(MobEffects.RESISTANCE,
						DragonTrialSuspend.RESUME_GRACE_TICKS, 4, false, false, true));
			}
		}
		ARRIVED.put(teamId, new java.util.HashSet<>(present));
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
			// ⚠ 전투를 먼저 열고 그 뒤에 부른다. tickEntranceGates 와 같은 까닭이다 — 3초
			// 예고가 도는 사이에 드래곤이 죽으면 둘째 겹이 거절하는데, 거꾸로 두면 그때도
			// 팀이 끝난 엔드로 끌려간다.
			if (!startSession(server, end, team, now)) {
				continue;
			}
			summonTeam(server, end, team);
		}
	}

	/**
	 * 엔드 밖에 있는 팀원만 불러들인다. <b>이미 엔드에 있는 사람은 건드리지 않는다.</b>
	 *
	 * <p>{@link #recallStragglers} 가 2초마다 부르는 길이라 여기서 엔드 안 사람까지 옮기면
	 * <b>싸우고 있는 사람이 2초마다 단상으로 끌려간다.</b>
	 */
	private static void summonTeam(MinecraftServer server, ServerLevel end, ShareTeam team) {
		moveTeam(server, end, team, false);
	}

	/**
	 * <b>팀 전원</b>을 단상으로 모은다 — 엔드 안에 있던 사람까지.
	 *
	 * <h2>사람이 정한 것</h2>
	 *
	 * <p>입장 수락창에서 리더가 확인을 누르면 「엔더 중앙으로 보내 그 기반암 단상있는곳으로」다.
	 * 「확인한 뒤 중앙으로 보낼 대상은 누구인가」를 따로 물었을 때 <b>「팀 전원」</b>이라고
	 * 답했다(2026-10-01). 그래서 먼저 들어가 있던 사람도 함께 옮긴다 — 그러지 않으면 넷 가운데
	 * 셋만 단상에 서고 한 사람은 포탈 자리에 남아 18.5초 연출을 혼자 다른 곳에서 본다.
	 */
	private static void gatherTeam(MinecraftServer server, ServerLevel end, ShareTeam team) {
		moveTeam(server, end, team, true);
	}

	/**
	 * 팀을 엔드 단상으로 옮긴다.
	 *
	 * <p>⚠⚠ <b>좌표를 숫자로 박지 않는다.</b> 전에는 {@code (0, 75, 0)} 이 여기 적혀 있었고 사람이
	 * 그것을 보고 「0 75 0 이엇나?」라고 물었다 — <b>물음표가 붙은 기억</b>이었고, 실제로 두 가지가
	 * 틀릴 수 있는 값이었다(가로는 전투 원점이 {@code (0, ?, 0)} 이 아닌 판, 세로는 섬 표면보다
	 * 열 칸 넘게 위). 지금은 {@link TrialPodium#locate} 가 <b>바닐라가 착지 목표로 쓰는 그 점</b>을
	 * 잡는다. 그쪽 클래스 설명에 셈과 근거가 있다.
	 *
	 * <p>단상은 기반암이라 <b>발밑이 사라지지 않는다.</b> 예전 자리는 섬 위 허공이어서 떨어지는
	 * 동안을 도착 무적이 메우고 있었다.
	 *
	 * @param includeInEnd 이미 엔드에 있는 사람도 옮기는가. 입장 수락 뒤의 「팀 전원 모으기」만
	 *                     참이다
	 */
	private static void moveTeam(MinecraftServer server, ServerLevel end, ShareTeam team,
			boolean includeInEnd) {
		Vec3 landing = TrialPodium.locate(end, findDragon(end));
		for (ServerPlayer member : onlineMembers(server, team)) {
			if (!includeInEnd && member.level().dimension() == Level.END) {
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
	 *
	 * <p>⚠ 시련을 켠 팀이라도 <b>다른 팀이 이미 시련 전투 중이면 끔으로 연다</b> — 시련 세션은 한 번에
	 * 하나다({@link #trialHolder}). 그 팀에게는 채팅 한 줄로 까닭을 알린다.
	 *
	 * <h2>⚠⚠ 둘째 겹 — 죽은 드래곤·끝난 전투로는 열리지 않는다 (2026-10-01)</h2>
	 *
	 * <p>{@link #detectArrival} 이 첫째 겹이고 여기가 둘째다. <b>이 문을 지나는 길이 셋</b>이고
	 * 그중 하나가 <b>{@code /shareteam trialtest start}</b>({@link #forceStart}) 라, 첫째 겹을
	 * 지나지 않는 길이 실제로 있다 — 명령으로도 같은 고리를 만들 수 없어야 한다. 「한쪽만 막으면
	 * 반드시 샌다」가 이 저장소의 제1 함정이다.
	 *
	 * <p>⚠ 여기서 거절하면 <b>세션이 열리지 않으므로</b> {@link #tickSessions} 가 「드래곤이
	 * 없다 → 처치」로 세어 다시 {@link #detectArrival} 로 돌려보내는 일도 없다. 고리의 3·4번이
	 * 통째로 사라지는 자리다.
	 *
	 * @return 전투를 열었으면 참. <b>거짓이면 부르는 쪽은 팀을 옮기지 않는다</b>
	 */
	private static boolean startSession(MinecraftServer server, ServerLevel end, ShareTeam team,
			long now) {
		if (!endFightLive(end)) {
			SharedFateMod.LOGGER.info(
					"[END] 팀 '{}' 엔드 전투를 열지 않습니다 — 이 월드의 드래곤전이 이미 끝났습니다."
							+ " 엔드 크리스탈 넷으로 드래곤을 되살리면 다시 열립니다", team.name());
			return false;
		}
		// ⚠ 멈춘 시련 세션은 자리를 쥐지 않는다 — 다른 팀이 전투를 여는 이 틱에 닫는다(사람 결정 2026-10-18,
		// closePausedTrials 설명). 그 팀이 걸어 둔 드래곤 강화는 아래에서 이 팀에 맞게 다시 걸거나 뗀다.
		closePausedTrials(server, end, team.teamId());
		boolean wanted = trialsEnabled(server, team.teamId());
		// 시련 세션은 한 번에 하나다 — 까닭은 trialHolder 설명에 있다. 이미 다른 팀이 시련 전투를
		// 치르고 있으면 이 팀은 시련 끔(바닐라 드래곤전)으로 연다.
		UUID holder = wanted ? trialHolder(SESSIONS.values(), team.teamId()) : null;
		boolean trials = wanted && holder == null;
		if (holder != null) {
			ShareTeam holderTeam = TeamManager.get(server).teamById(holder);
			SharedFateMod.LOGGER.info(
					"[END] 팀 '{}' 시련을 켰지만 팀 '{}' 가 이미 시련 전투 중이라 시련 끔으로 엽니다"
							+ " — 시련 세션은 한 번에 하나입니다",
					team.name(), holderTeam == null ? holder : holderTeam.name());
			Component notice = Component.literal(TRIAL_SLOT_TAKEN_NOTICE)
					.withStyle(ChatFormatting.YELLOW);
			for (ServerPlayer member : onlineMembers(server, team)) {
				member.sendSystemMessage(notice);
			}
		}
		DragonTrialSession session = new DragonTrialSession(team.teamId(), now, trials);
		SESSIONS.put(team.teamId(), session);
		int memberCount = Math.max(1, team.members().size());
		// 끈 팀에게는 강화 자체를 건너뛴다. 지금 값을 그대로 로그에 적어 「바닐라 200 이다」가
		// 눈으로 확인되게 한다.
		EnderDragon dragon = findDragon(end);
		// 시련 끔으로 여는데 드래곤에 우리 강화가 남아 있으면(방금 닫은 멈춘 시련 세션의 것) 비율을 지켜 뗀다 —
		// 「끄면 바닐라 그대로」. 다른 시련 세션이 싸우고 있으면 그 팀의 강화이므로 두지 않는다.
		if (!trials && dragon != null && trialHolder(SESSIONS.values(), team.teamId()) == null) {
			weakenDragon(dragon);
		}
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
		return true;
	}

	/**
	 * 시련 자리가 차서 시련 끔으로 연 팀에게 보내는 채팅 한 줄. 시련을 켜 두었는데 바닐라 드래곤이
	 * 나오면 「설정이 안 먹는다」로 읽히므로 <b>왜</b>를 바로 알린다.
	 */
	static final String TRIAL_SLOT_TAKEN_NOTICE =
			"다른 팀이 이미 드래곤 시련 전투를 치르고 있어, 이번 전투는 시련 없이(바닐라 드래곤전) 진행됩니다.";

	/**
	 * 시련을 켠 채 열려 있는 세션의 팀. 없으면 {@code null}. <b>월드를 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <h2>시련 세션은 한 번에 하나다 (2026-10-06 검토에서 확정된 문제)</h2>
	 *
	 * <p>시련의 실행기 상태는 팀별이 아니라 <b>정적 한 벌</b>이다 — 연쇄 포격·착지·착지 충격·엔더폭풍은
	 * {@code granted != 기억값} 이면 {@code clearState()} 하고, 최후의 저항의 번개·오브젝트·면도
	 * 「먼저 든 판」 문이 없다. {@code singleTeamOnly} 를 끈 서버에서 시련 팀 둘이 다른 틱에 들어오면
	 * 한 서버 틱 안에서 A 호출이 B 상태를, B 호출이 A 상태를 지워 <b>포격이 영영 안 터지고 착지
	 * 고정이 안 서며 최후의 저항 번개가 안 떨어졌다.</b> HUD 도 마지막에 돈 팀만 「우리 것」으로 읽었다.
	 *
	 * <p>근본은 그 상태들을 팀 열쇠 맵으로 바꾸는 것인데 손이 크다. 그래서 싼 길을 골랐다 — 두 번째
	 * 시련 팀은 <b>시련 끔으로 연다.</b> 드래곤은 차원에 하나라 두 팀이 같은 드래곤을 두고 각자 시련을
	 * 굴리는 판은 애초에 설계가 그리지 않은 판이다. 기본 {@code singleTeamOnly = true} 에서는 세션이
	 * 둘 생기지 않으므로 이 규칙에 걸리지 않는다.
	 *
	 * <p>⚠ 문이 셋이다 — {@link #startSession}(열리는 모든 길의 목), {@link #detectArrival}(시련
	 * 수락창을 띄울지), {@link #onServerStarted}(옛 저장 파일이 둘을 들고 있을 때 — 거기는
	 * {@link #anyTrialSession}). 「한쪽만 막으면 반드시 샌다」.
	 *
	 * <h2>⚠ 멈춘 세션은 자리를 쥐지 않는다 (사람 결정 2026-10-18)</h2>
	 *
	 * <p>0.42 의 일시 중단이 처음 들어왔을 때는 멈춘 세션도 자리를 쥐었다(돌아와서 이으려고). 그러자
	 * {@code singleTeamOnly} 를 끈 서버에서 시련 팀 A 가 멈춘 사이 들어온 팀 B 가 시련 끔으로 열리는데 드래곤에는
	 * A 의 강화(2400)가 남고, 착지 주사위도 A 를 시련 중으로 셌으며, B 가 잡으면 A 세션이 조용히 닫혔다 — 공개
	 * 문서의 「켠 팀이 엔드에서 싸우는 동안」 · 「끄면 바닐라 그대로」와 어긋났다. 지금은 싸우는 세션만 세고, 다른
	 * 팀이 전투를 여는 틱에 멈춘 시련 세션을 닫는다({@link #closePausedTrials}).
	 *
	 * @param except 묻는 팀 자신. 제 세션은 세지 않는다({@link #forceStart} 가 다시 열 때). {@code null}
	 *               이면 모든 세션을 센다
	 */
	static @Nullable UUID trialHolder(java.util.Collection<DragonTrialSession> sessions,
			@Nullable UUID except) {
		for (DragonTrialSession session : sessions) {
			if (session.trialsEnabled() && !session.suspended() && !session.teamId().equals(except)) {
				return session.teamId();
			}
		}
		return null;
	}

	/**
	 * 시련을 켠 세션의 팀 — <b>멈춘 것까지</b> 센다. 없으면 {@code null}. 시련 자리가 아니라 「이 판에 시련 세션의
	 * 흔적이 있는가」를 묻는 두 곳이 쓴다 — 저장 파일에서 되살릴 때(되살린 세션은 전부 멈춘 채다)와 주인 없는
	 * 드래곤을 바닐라로 되돌릴 때(멈춘 세션의 드래곤은 강화를 쥔 채 기다린다).
	 */
	static @Nullable UUID anyTrialSession(java.util.Collection<DragonTrialSession> sessions,
			@Nullable UUID except) {
		for (DragonTrialSession session : sessions) {
			if (session.trialsEnabled() && !session.teamId().equals(except)) {
				return session.teamId();
			}
		}
		return null;
	}

	/**
	 * 다른 팀이 전투를 여는 틱에 <b>멈춘 시련 세션을 닫는다</b>(사람 결정 2026-10-18 — 「멈춘 A 는 시련 자리를
	 * 쥐지 않는다. B 는 B 설정대로 열고, 드래곤의 A 강화는 떼거나 B 인원으로 다시 건다. 그 순간 A 세션은 닫는다」).
	 *
	 * <p>드래곤은 차원에 하나라 A 의 카드·최후의 저항 기억을 B 의 전투 위에 남길 수 없다. A 의 팀원이 돌아오면
	 * 세션이 없으니 그때 열린 전투에 보통 팀처럼 들어온다. A 의 팀원에게는 접속해 있으면 곧바로, 아니면 다음에
	 * 접속할 때 채팅 한 줄로 까닭을 알린다({@link #PAUSED_CLOSED_NOTICE}). 시련을 끈 멈춘 세션은 두 팀이 같은
	 * 드래곤을 함께 쓰는 지금 규칙과 다를 것이 없어 그대로 둔다.
	 *
	 * @param opener 전투를 여는 팀
	 */
	private static void closePausedTrials(MinecraftServer server, ServerLevel end, UUID opener) {
		List<UUID> closed = new ArrayList<>();
		for (DragonTrialSession session : SESSIONS.values()) {
			if (session.trialsEnabled() && session.suspended() && !session.teamId().equals(opener)) {
				closed.add(session.teamId());
			}
		}
		for (UUID teamId : closed) {
			SESSIONS.remove(teamId);
			resetTrialDelay(teamId);
			RECALL_AT.remove(teamId);
			CRYSTALS_AT_START.remove(teamId);
			ARRIVED.remove(teamId);
			// 판은 멈출 때 이미 거뒀다. 남았으면(그럴 리 없지만) 무적 없이 거둔다.
			DragonLastStand.onTeamGone(end, teamId);
			ShareTeam team = TeamManager.get(server).teamById(teamId);
			ShareTeam opening = TeamManager.get(server).teamById(opener);
			SharedFateMod.LOGGER.info("[END] 팀 '{}' 의 멈춘 시련 전투를 닫습니다 — 팀 '{}' 가 엔드 전투를 열었습니다"
					+ "(멈춘 세션은 시련 자리를 쥐지 않습니다)", team == null ? teamId : team.name(),
					opening == null ? opener : opening.name());
			if (team != null) {
				PAUSED_CLOSED_PENDING.addAll(team.members());
			}
		}
		if (!closed.isEmpty()) {
			persist();
		}
	}

	/**
	 * 멈춘 시련 전투가 다른 팀 때문에 닫혔을 때 그 팀원에게 보내는 채팅 한 줄. 돌아와서 카드가 없으면 「버그」로
	 * 읽히므로 <b>왜</b>를 알린다.
	 */
	static final String PAUSED_CLOSED_NOTICE =
			"다른 팀이 엔드 전투를 시작해, 일시 중단되어 있던 우리 팀의 드래곤 시련 전투는 끝났습니다(뽑은 카드도 사라졌습니다).";

	/**
	 * {@link #PAUSED_CLOSED_NOTICE} 를 아직 못 받은 사람. 접속해 있는 틱에 보내고 지운다. 저장하지 않는다 — 재시작
	 * 사이에 잃는 것은 알림 한 줄뿐이다.
	 */
	private static final java.util.Set<UUID> PAUSED_CLOSED_PENDING = new java.util.HashSet<>();

	/** {@link #PAUSED_CLOSED_PENDING} 가운데 접속해 있는 사람에게 알린다. 대개 비어 있어 바로 돌아간다. */
	private static void deliverPausedClosedNotices(MinecraftServer server) {
		if (PAUSED_CLOSED_PENDING.isEmpty()) {
			return;
		}
		Component notice = Component.literal(PAUSED_CLOSED_NOTICE).withStyle(ChatFormatting.YELLOW);
		PAUSED_CLOSED_PENDING.removeIf(playerId -> {
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				return false;
			}
			player.sendSystemMessage(notice);
			return true;
		});
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
		double boost = healthBoost(instance.getBaseValue(),
				(double) SharedFateMod.config.dragonHealthPerMember * memberCount);
		if (Double.isNaN(boost)) {
			return dragon.getMaxHealth();
		}
		float before = dragon.getMaxHealth();
		// ⚠ 떼기 전에 읽는다 — 이미 붙어 있던 강화를 떼는 순간 체력이 바닐라 200 에 잘린다.
		float health = dragon.getHealth();
		instance.removeModifier(HEALTH_MODIFIER_ID);
		// ⚠ 저장되는 수정자다 — 까닭은 HEALTH_MODIFIER_ID 설명에 있다.
		instance.addOrReplacePermanentModifier(new AttributeModifier(
				HEALTH_MODIFIER_ID, boost, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		// 체력은 비율을 지켜 옮긴다(2026-10-18 검토) — 가득이면 새 최대치로 가득, 깎여 있었으면 같은 비율.
		// 전에는 「가득이면 채움」만 있어 깎인 드래곤에 강화를 걸면 200 / 2400 이 되어 문턱 셋이 한꺼번에 터졌다.
		// ⚠ 이 메서드는 전투를 <b>여는</b> 틱에만 부른다 — 이을 때는 restrengthen 이다(적어 둔 체력으로 되돌린다).
		dragon.setHealth(DragonTrialSuspend.rescaledHealth(health, before, dragon.getMaxHealth(), 1.0F));
		return dragon.getMaxHealth();
	}

	/**
	 * 강화 배율. {@code ADD_MULTIPLIED_TOTAL} 의 값이다. 강화할 것이 없으면 NaN. <b>월드를 모른다 —
	 * 시험이 직접 굴린다.</b>
	 *
	 * @param base   최대 체력 기본값(바닐라 200)
	 * @param target 팀 인원에 맞춘 최대 체력
	 */
	static double healthBoost(double base, double target) {
		if (!(base > 0.0) || !(target > base)) {
			return Double.NaN;
		}
		return target / base - 1.0;
	}

	/**
	 * 이어진 전투의 드래곤에 강화를 <b>다시 건다 — 채우지 않는다.</b> 이미 붙어 있으면 아무 일도 하지
	 * 않는다(저장되는 수정자라 대개 붙은 채 올라온다). 붙어 있지 않은 길은 0.41 이전 판이 저장한 월드,
	 * 바닐라가 드래곤을 못 찾아 새로 만든 판({@code EnderDragonFight.findOrCreateDragon} — 엔드가 오래 비어
	 * {@code ticksSinceDragonSeen} 이 1200 을 넘으면 새 드래곤을 띄운다) 둘이다.
	 *
	 * <p>새로 붙일 때는 체력을 <b>비율로</b> 옮기고 이미 지난 문턱 위로는 자른다
	 * ({@link DragonTrialSuspend#rescaledHealth}, 2026-10-18 검토). 전에는 200 / 2400 이 되어 문턱 셋이 한꺼번에
	 * 터졌다. 적어 둔 체력이 있으면 부르는 쪽({@link #resume})이 그 값으로 한 번 더 맞춘다 — 새로 만든 드래곤도
	 * 나갈 때의 체력으로 돌아온다.
	 *
	 * @param ceiling 넘지 않을 체력 비율({@link DragonTrialSuspend#healthCeiling})
	 * @return 지금 최대 체력
	 */
	private static float restrengthen(EnderDragon dragon, int memberCount, float ceiling) {
		AttributeInstance instance = dragon.getAttribute(Attributes.MAX_HEALTH);
		if (instance == null || instance.hasModifier(HEALTH_MODIFIER_ID)) {
			return dragon.getMaxHealth();
		}
		double boost = healthBoost(instance.getBaseValue(),
				(double) SharedFateMod.config.dragonHealthPerMember * memberCount);
		if (!Double.isNaN(boost)) {
			float before = dragon.getMaxHealth();
			float health = dragon.getHealth();
			instance.addOrReplacePermanentModifier(new AttributeModifier(
					HEALTH_MODIFIER_ID, boost, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			dragon.setHealth(DragonTrialSuspend.rescaledHealth(health, before, dragon.getMaxHealth(), ceiling));
		}
		return dragon.getMaxHealth();
	}

	/**
	 * 우리 강화를 뗀다. 체력은 26.3 {@code LivingEntity.onAttributeUpdated} 가 새 최대치로 자른다.
	 *
	 * @return 떼었으면 참
	 */
	private static boolean dropStrength(EnderDragon dragon) {
		AttributeInstance instance = dragon.getAttribute(Attributes.MAX_HEALTH);
		return instance != null && instance.removeModifier(HEALTH_MODIFIER_ID);
	}

	/**
	 * 우리 강화를 <b>비율을 지켜</b> 뗀다 — 멈춘 시련 세션을 닫고 시련 끔으로 전투를 여는 틱({@link #startSession}).
	 * 그냥 떼면 2000 / 2400 이던 드래곤이 200 / 200(가득)이 된다.
	 */
	private static void weakenDragon(EnderDragon dragon) {
		float before = dragon.getMaxHealth();
		float health = dragon.getHealth();
		if (dropStrength(dragon)) {
			dragon.setHealth(DragonTrialSuspend.rescaledHealth(health, before, dragon.getMaxHealth(), 1.0F));
		}
	}

	private static void tickSessions(MinecraftServer server, ServerLevel end, long now) {
		if (SESSIONS.isEmpty()) {
			return;
		}
		EnderDragon found = findDragon(end);
		// ⚠ 죽음 연출(DYING — 체력 1 로 붙들린 채 200틱) 중인 드래곤은 <b>죽은 것</b>으로 본다(2026-10-18 검토).
		// 살아 있는 것으로 세면 그 사이 전원이 끊을 때 「처치」가 아니라 일시 중단이 되어 세션이 남았다.
		boolean dying = dying(found);
		EnderDragon dragon = dying ? null : found;
		if (dragon != null) {
			lastSeenHealth = dragon.getHealth();
			lastSeenMax = dragon.getMaxHealth();
		}
		// ⚠ 「살아 있는 드래곤이 없다」는 죽었다는 뜻이 아니다 — 섬 청크가 내려가도 그렇다. 죽은 근거 둘을
		// 따로 본다(DragonTrialSuspend 클래스 설명). 살아 있는 드래곤이 있으면 묻지 않는다.
		boolean deadInWorld = dying || (dragon == null && deadDragonInWorld(end));
		boolean vanillaKilled = dragon == null && vanillaDragonKilled(end);
		List<UUID> finished = new ArrayList<>();
		// 실행기를 이 now 로 이미 돌렸는가. 판이 얼어 있으면 게임 시각이 서므로 같은 now 가 매 서버
		// 틱 다시 온다 — 까닭과 무엇을 건너뛰는지는 executorsRanAt 설명에 있다.
		boolean executorsDue = executorsDue(executorsRanAt, now);
		executorsRanAt = now;

		for (Map.Entry<UUID, DragonTrialSession> entry : SESSIONS.entrySet()) {
			DragonTrialSession session = entry.getValue();
			ShareTeam team = TeamManager.get(server).teamById(entry.getKey());
			if (team == null) {
				// 팀이 전투 중에 사라졌다(해체·마지막 팀원 탈퇴). 세션만 지우면 최후의 저항의 판이
				// STANDS 에 남아 다음 판 보호막이 영영 서고·지대가 꺼지고·접촉 깃발이 서버 재시작까지
				// 붙들린다 — 2026-10-06 검토에서 확정된 문제. 거두는 것은 저쪽 한 곳이다.
				SharedFateMod.LOGGER.info("[END] 팀({})이 전투 중에 사라져 세션을 닫습니다",
						entry.getKey());
				DragonLastStand.onTeamGone(end, entry.getKey());
				finished.add(entry.getKey());
				continue;
			}
			List<ServerPlayer> members = membersOf(server, team, end);
			DragonTrialSuspend.Verdict verdict = DragonTrialSuspend.judge(dragon != null, deadInWorld,
					vanillaKilled, !members.isEmpty());
			if (verdict == DragonTrialSuspend.Verdict.KILLED) {
				SharedFateMod.LOGGER.info("[END] 팀 '{}' 드래곤 처치 — {}초 · 시련 {}장",
						team.name(), session.elapsedTicks(now) / 20, session.trialCount());
				// 최후의 저항이 돌고 있었으면 여기가 「처치」다 — 시련이 사라지고 팀이 무적이
				// 되며 보스바 이름이 되돌아간다. 돌고 있지 않았으면 아무 일도 하지 않는다.
				DragonLastStand.onFightClosed(end, team);
				finished.add(entry.getKey());
				continue;
			}
			// ⚠⚠ 일시 중단(2026-10-18). 엔드가 비었거나 드래곤이 메모리에서 내려갔다 — 처치가 아니다.
			// 멈춘 세션은 아래 실행기를 하나도 돌리지 않고, 팀원이 돌아와 드래곤이 다시 올라온 틱에 이어
			// 그 틱부터 평소대로 돈다. 까닭과 판정은 DragonTrialSuspend 에 있다.
			if (session.suspended()) {
				if (verdict != DragonTrialSuspend.Verdict.FIGHTING) {
					continue;
				}
				resume(end, team, session, dragon, now);
			} else if (DragonTrialSuspend.pauses(verdict)) {
				ARRIVED.remove(team.teamId());
				suspend(end, team.teamId(), team.name(), session, dragon,
						verdict == DragonTrialSuspend.Verdict.AWAY
								? "엔드에 팀원이 없음"
								: "드래곤이 메모리에서 내려감(섬 근처에 팀원이 없음)",
						now);
				continue;
			}
			// 재개 직후 3초 보호 — 이은 틱에 엔드에 선 팀원, 그리고 그 뒤 하나씩 돌아오는 팀원(사람 결정
			// 「이어 가기, 무적 없음」). 시련을 켠 팀만이다 — 끈 팀은 바닐라 드래곤전이다.
			if (session.trialsEnabled()) {
				protectArrivals(team.teamId(), members);
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
			if (!executorsDue) {
				// ⚠ 얼어 있는 틱이다. 자리 감지·최후의 저항·입장 연출·룰렛·패시브·위험을 전부
				// 건너뛰고 보호막 맥박 하나만 잇는다 — 맥박은 서버 틱 수로 재므로 얼어 있어도 끊긴다
				// (DragonLastStand.holdShield 설명). 자리 감지까지 건너뛰는 까닭은 executorsRanAt 에.
				DragonLastStand.holdShield(server, end, dragon, now);
				continue;
			}
			boolean lastStandBegins = detectTriggers(end, dragon, session, now);
			// ⚠ 최후의 저항은 카드가 아니라 별개의 보스전이고, 열리는 순간 아래 셋을 전부
			// 멈춘다 — 룰렛·시련·패시브. 「시련이 전부 멈춥니다」가 이 continue 한 줄이다.
			int wavesBefore = session.lastStandWaves();
			DragonLastStand.Standing standing = DragonLastStand.tick(server, end, dragon, team,
					session, members, lastStandBegins, now);
			if (lastStandBegins) {
				// 「결전의 함성」. HEALTH_30 이 처음 터진 이 틱에만 온다 — 그 자리는 세션에 저장되므로 일시
				// 중단 뒤 이어 가도 다시 주지 않는다.
				com.sharedfate.perk.DragonPrep.onLastStandBegins(team,
						TeamManager.get(server).stateByTeamId(team.teamId()), members);
			}
			if (session.lastStandWaves() != wavesBefore) {
				// 최후의 저항에서 회복 크리스탈 소환을 끝까지 치렀다(2026-10-18 밤). 바로 저장하지 않으면 강제
				// 종료 뒤에 그 파도를 다시 받는다 — DragonTrialSession.recordLastStandWaves.
				persist();
			}
			if (standing != DragonLastStand.Standing.OFF) {
				// ⚠⚠ 「착지」 패시브의 깃발을 내린다. 최후의 저항은 드래곤을
				// EnderDragonPhase.HOVERING 으로 영구히 앉혀 두고 사람이 활로 1200 을 깎는
				// 설계인데, 아래 continue 때문에 DragonPassives.tick 이 한 번도 안 불린다 —
				// 진입 직전 틱에 드래곤이 앉아 있었다면 원거리 면역이 켜진 채 남아 115초 내내
				// 활이 한 대도 안 들어간다. DragonPerch.perched 가 HOVERING 을 구조로도 거르지만
				// (그쪽 설명), 「한쪽만 막으면 반드시 샌다」라 이 줄을 함께 둔다.
				DragonPerch.standDown();
				if (standing == DragonLastStand.Standing.ENTERED) {
					// 진입하며 줄에 남아 있던 자리를 비웠다. 저장해 두지 않으면 재시작 뒤에
					// 「선택 대기 중」인 채로 되살아나 룰렛이 영영 안 열리는 팀이 된다.
					persist();
				}
				continue;
			}
			// 엔드 월드 보더를 섬 가장자리에 세운다(2026-10-18 사람 지시 「드래곤전 시작하면 월드보더가
			// 제일 끝에서 생기고」). 시련 세션이 열린(startSession) 뒤 첫 틱부터 최후의 저항 전까지 여기가
			// 붙들고, 최후의 저항이 열리면 위 continue 때문에 이 줄을 안 지나 DragonLastStandZone 이 넘겨받는다
			// — 두 쪽이 한 틱에 서로 다른 크기를 쓰는 일이 없다. 되돌리는 것은 tick 의 restoreBorderIfIdle.
			DragonTrialBorder.hold(end, TrialPodium.locate(end, dragon),
					DragonTrialBorder.ISLAND_HALF_SIDE);
			// 입장 연출. 룰렛보다 <b>먼저</b> 돌아야 한다 — 룰렛이 뜨면 TrialFreeze 가 판을
			// 얼려 연출이 그 자리에서 멈춘다. 겹치지 않게 하는 것은 순서가 아니라
			// TrialCatalog.DELAY_SETTLE_TICKS 가 연출 길이와 한 상수로 묶여 있는 것이다.
			TrialEntrance.tick(end, dragon, members, session, now);
			openTrialWhenDue(server, end, session, members, now);
			// 패시브는 시련과 다르다. 팀이 뽑는 것이 아니라 언제나 있는 판이므로 카드와 무관하게
			// 돈다 — 섞으면 「이번 판이 왜 어려웠나」를 나눌 수 없다. 입장 연출 동안에는 연쇄 포격만
			// 쉰다 — 이어진 전투의 연출이 포격 위상과 따로 다시 돈다(DragonPassives.tick 설명).
			DragonPassives.tick(end, dragon, members, session.startedTick(), now,
					TrialEntrance.managesCrystals(session, now));
			TrialRisks.tick(end, dragon, members, session, now);
		}
		if (!finished.isEmpty()) {
			finished.forEach(SESSIONS::remove);
			// 전투가 끝났는데 룰렛만 남으면 다음 전투 첫 틱에 옛 카드가 튀어나온다.
			finished.forEach(DragonTrialManager::resetTrialDelay);
			// 재소환 시계도 함께 버린다. 남겨 두면 다음 전투의 첫 검사가 앞 전투의 시각을
			// 물려받아 최대 2초 늦게 돈다 — 소환 직후가 가장 어긋나기 쉬운 순간이다.
			finished.forEach(RECALL_AT::remove);
			finished.forEach(ARRIVED::remove);
			endTrials();
			persist();
		}
	}

	/**
	 * ⚠ <b>시련 세션이 하나도 없는데 엔드 월드 보더를 들고 있으면 되돌린다.</b> {@link #tick} 이 세션을
	 * 다 돌린 뒤 매 틱 부른다.
	 *
	 * <p>보더는 시련 전투가 열려 있는 동안만 선다(섬 가장자리 → 최후의 저항 42-32-22-18,
	 * {@link DragonTrialBorder}). 닫히는 길이 여럿이다 — 승리(드래곤이 죽거나 사라져 세션이 닫힘) · 팀
	 * 해체({@code onTeamGone}) · 옛 저장 파일에서 시련 끔으로 되살린 세션만 남은 판. 그 길마다
	 * 되돌리는 줄을 두면 하나는 반드시 빠진다(「한쪽만 막으면 반드시 샌다」). 그래서 <b>「시련 세션이
	 * 없다」 한 가지 사실</b>만 보고 여기 한 곳에서 되돌린다. 시련을 끈 세션(바닐라 드래곤전)만 남아도
	 * 되돌린다 — 그 세션은 보더를 쓰지 않는다.
	 *
	 * <p>되돌리는 값은 처음 잡을 때 기억해 둔 원래 보더다({@link DragonTrialBorder#restore}). 서버가
	 * 내려가는 길은 {@code DragonLastStand.onServerStopping} 이 따로 맡는다.
	 */
	private static void restoreBorderIfIdle(ServerLevel end) {
		// 「싸우는」 시련 세션이다 — 일시 중단된 세션은 보더를 쓰지 않는다(2026-10-18). 엔드가 빈 채로 섬
		// 크기 보더가 남으면 그사이 엔드에 들어온 다른 사람이 까닭 없는 벽을 만난다. 이을 때 그 단계에
		// 맞게 다시 선다 — 섬 가장자리는 tickSessions 가, 최후의 저항은 지대가 처음 값(42)부터.
		if (DragonTrialBorder.held() && activeTrialHolder(SESSIONS.values()) == null) {
			DragonTrialBorder.restore(end);
		}
	}

	/**
	 * <b>싸우고 있는</b>(일시 중단되지 않은) 시련 세션의 팀. 없으면 {@code null}. <b>월드를 모른다 —
	 * 시험이 직접 굴린다.</b>
	 *
	 * <p><b>판에 걸어 둔 것을 되돌릴지</b>를 묻는 자리(보더 · {@link #endTrials})가 쓴다. 처음에는
	 * {@link #trialHolder} 와 멈춘 세션을 세는지로 갈렸는데, 멈춘 세션이 시련 자리를 쥐지 않게 되며(사람 결정
	 * 2026-10-18) 지금은 {@code trialHolder(sessions, null)} 과 같은 답이다. 묻는 것이 달라 이름은 둘로 둔다.
	 */
	static @Nullable UUID activeTrialHolder(java.util.Collection<DragonTrialSession> sessions) {
		for (DragonTrialSession session : sessions) {
			if (session.trialsEnabled() && !session.suspended()) {
				return session.teamId();
			}
		}
		return null;
	}

	/**
	 * 바닐라 죽음 연출 중인가 — {@code DYING} 칸(체력 1 로 붙들린 채 200틱을 돈 뒤 체력 0). 이 칸은 죽음이
	 * 정해진 뒤에만 들어간다({@code EnderDragon} 의 마지막 일격이 체력을 1 로 되돌리고 넘긴다). 살아 있는 것으로
	 * 세면 그 사이 전원이 끊을 때 처치가 일시 중단으로 읽히고, 다시 들어오면 「끝나지 않은 전투」로 수락창이 뜬다.
	 */
	private static boolean dying(@Nullable EnderDragon dragon) {
		return dragon != null && dragon.getPhaseManager().getCurrentPhase().getPhase()
				== net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase.DYING;
	}

	/** 죽은(체력 0) 드래곤 개체가 아직 월드에 있는가 — 바닐라 죽음 연출 200틱 동안이다. */
	private static boolean deadDragonInWorld(ServerLevel end) {
		for (EnderDragon ignored : end.getEntities(EntityTypes.ENDER_DRAGON,
				candidate -> !candidate.isAlive())) {
			return true;
		}
		return false;
	}

	/**
	 * 바닐라 {@code EnderDragonFight.dragonKilled}. 못 읽으면 거짓 — {@link #endFightLive(ServerLevel)}
	 * 과 같은 규칙이다(모를 때 「죽었다」로 기울면 내려간 드래곤이 다시 「처치」가 된다).
	 */
	private static boolean vanillaDragonKilled(ServerLevel end) {
		return end.getDragonFight() instanceof EnderDragonFightAccessor accessor
				&& accessor.sharedfate$dragonKilled();
	}

	/**
	 * 전투를 <b>일시 중단</b>한다. 세션은 그대로 둔다 — 뽑힌 카드 · 터진 자리 · 줄 · 받은 틱.
	 *
	 * <h2>하는 일</h2>
	 *
	 * <ol>
	 *   <li>드래곤의 <b>실제 체력</b>을 세션에 적는다. 드래곤이 보이면 그 값, 이미 내려갔으면 마지막으로 본
	 *       값({@link #lastSeenHealth}), 그것도 없으면(재시작 첫 틱) 앞서 적어 둔 값을 지킨다</li>
	 *   <li>최후의 저항이 돌고 있었으면 판을 거둔다 — {@link DragonLastStand#suspend}. 처치가 아니라
	 *       무적을 주지 않고, 드래곤을 바닐라 비행으로 풀어 두며, 진입 회복 도중이면 그 몫을 다 채워
	 *       둔다</li>
	 *   <li>입장 연출이 끝나기 전이면 이을 때 처음부터 다시 돌도록 적는다</li>
	 *   <li>싸우는 시련 세션이 더 없으면 시련이 판에 걸어 둔 것을 되돌린다 — 소환 엔더맨 · 회복 크리스탈 ·
	 *       종말의 비 패널 · 화살 면역 등({@link #endTrials} 와 같은 길 + 패시브의 시계). 카드 자체는
	 *       세션에 남아 이을 때 다시 돈다</li>
	 *   <li>룰렛 지연 · 크리스탈 기준값을 버린다 — 이은 틱부터 다시 센다</li>
	 *   <li>저장한다 — 그사이 서버가 꺼져도 잇는다</li>
	 * </ol>
	 *
	 * <p>엔드 월드 보더는 여기서 되돌리지 않는다 — {@link #restoreBorderIfIdle} 이 같은 틱 끝에 「싸우는
	 * 시련 세션이 없다」를 보고 되돌린다(되돌리는 자리는 한 곳).
	 *
	 * @param dragon 살아 있는 드래곤. 이미 내려갔으면 {@code null}
	 * @param reason 로그에 적을 까닭
	 */
	private static void suspend(ServerLevel end, UUID teamId, String teamName,
			DragonTrialSession session, @Nullable EnderDragon dragon, String reason, long now) {
		boolean trials = session.trialsEnabled();
		float health = dragon != null ? dragon.getHealth() : lastSeenHealth;
		float max = dragon != null ? dragon.getMaxHealth() : lastSeenMax;
		int waves = -1;
		boolean lastStand = false;
		if (trials) {
			// ⚠ 판을 거두기 전에 읽는다 — 거두는 길(TrialRisks.clearState)이 실행기의 「이미 썼다」 기억을 비운다.
			// 이것을 세션에 적어 두지 않으면 재개 첫 틱에 부활 · 메마른 세계가 또 터진다(2026-10-18 검토).
			session.rememberDone(TrialRisks.snapshotDone(session));
			DragonLastStand.Paused paused = DragonLastStand.suspend(end, teamId, dragon, health, max, now);
			if (paused != null) {
				health = paused.health();
				waves = paused.clearedWaves();
				// 지대(보더) 시계가 어디까지 왔는가 — 이을 때 그 단계 · 그 시간표에서 잇는다(사람 결정).
				session.keepLastStandClock(paused.zoneClock());
				lastStand = true;
			}
		}
		// 입장 자리가 이미 소비됐으면(룰렛이 끝나 카드를 쌓았다) 연출을 다시 돌리지 않는다(2026-10-18 검토).
		boolean replay = trials && DragonTrialSuspend.replaysEntrance(session.entranceTick(), now,
				session.pending(TrialCatalog.Trigger.ENTRY));
		session.suspend(replay, trials ? health : Float.NaN, waves, now);
		resetTrialDelay(teamId);
		CRYSTALS_AT_START.remove(teamId);
		if (activeTrialHolder(SESSIONS.values()) == null) {
			endTrials();
			// 연쇄 포격·착지 고정의 시계도 버린다. 이을 때 위상은 startedTick 에서 그대로 다시 센다.
			DragonPassives.clearState();
		}
		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 전투 일시 중단 — {} · 드래곤 체력 {} · 시련 {}장{}{}",
				teamName, reason, session.dragonHealth() > 0.0F ? session.dragonHealth() : "?",
				session.trialCount(), lastStand ? " · 최후의 저항(이을 때 그 자리에서 — 보더 이어 감 · 무적 없음)" : "",
				session.replayEntrance() ? " · 입장 연출을 처음부터 다시" : "");
		persist();
	}

	/**
	 * 멈춘 전투를 <b>그 자리에서 잇는다.</b> 판정이 {@code FIGHTING} 이 된 틱 — 살아 있는 드래곤이 있고
	 * 엔드에 팀원이 있다 — 에 부른다. 같은 틱에 아래 실행기가 평소대로 돈다.
	 *
	 * <ul>
	 *   <li>입장 수락창·입장 연출을 다시 띄우지 않는다 — 세션이 그대로라 {@link #detectArrival} 이
	 *       건너뛴다. 입장 연출 도중에 멈췄으면 그 연출만 처음부터 다시 돈다({@code entranceTick})</li>
	 *   <li>드래곤 체력 강화를 다시 확인하고({@link #restrengthen} — <b>채우지 않는다</b>) 적어 둔 체력으로
	 *       <b>정확히</b> 되돌린다({@link DragonTrialSuspend#restoredHealth}) — 회복도 깎임도 없다</li>
	 *   <li>최후의 저항이던 판은 이 틱의 {@code DragonLastStand.tick} 이 그 자리에서 잇는다(연출·보호막·무적
	 *       없이, 보더는 멈춘 단계에서 — {@code DragonLastStand.reenter})</li>
	 *   <li>카드의 받은 틱을 멈춘 만큼 밀고({@code DragonTrialSession.resume}) 한 번짜리 카드가 이미 한 일을 다시
	 *       심는다({@code TrialRisks.restoreDone}) — 끊기로 창을 건너뛰지도, 다시 터지지도 않는다</li>
	 *   <li>3초 보호는 같은 틱의 {@link #protectArrivals} 가 준다</li>
	 *   <li>룰렛 지연은 이 틱부터 다시 센다 — 줄에 남은 자리는 그 지연만큼 뒤에 뜬다</li>
	 * </ul>
	 */
	private static void resume(ServerLevel end, ShareTeam team, DragonTrialSession session,
			EnderDragon dragon, long now) {
		float saved = session.dragonHealth();
		// 카드의 받은 틱을 멈춰 있던 만큼 민다(창 있는 카드는 멈춘 자리에서 잇는다) — 그 뒤에 「이미 한 일」을 심는다.
		boolean replay = session.resume(now);
		resetTrialDelay(team.teamId());
		CRYSTALS_AT_START.remove(team.teamId());
		if (session.trialsEnabled()) {
			TrialRisks.restoreDone(session);
			restrengthen(dragon, Math.max(1, team.members().size()),
					DragonTrialSuspend.healthCeiling(session.fired()));
			float restored = DragonTrialSuspend.restoredHealth(saved, dragon.getHealth(),
					dragon.getMaxHealth());
			if (restored != dragon.getHealth()) {
				dragon.setHealth(restored);
			}
		}
		SharedFateMod.LOGGER.info("[END] 팀 '{}' 전투 재개 — 드래곤 체력 {} / {} · 시련 {}장{}{}",
				team.name(), dragon.getHealth(), dragon.getMaxHealth(), session.trialCount(),
				replay ? " · 입장 연출을 처음부터" : "",
				session.trialsEnabled() && session.fired().contains(TrialCatalog.Trigger.HEALTH_30)
						? " · 최후의 저항(진입 연출·보호막 없이 그 자리에서 — 보더 이어 감)" : "");
		persist();
	}

	/**
	 * 서버가 멈추기 직전 — <b>싸우는 전투를 전부 일시 중단한다.</b> {@code SharedFateMod} 가
	 * {@code SERVER_STOPPING} 에 {@code DragonLastStand.onServerStopping}(보더 되돌리기) 보다 먼저 붙인다.
	 *
	 * <p>재시작도 「팀이 엔드를 비웠다」와 같은 일이다 — 다시 뜨면 엔드에 아무도 없다. 그래서 같은 길
	 * ({@link #suspend})로 보낸다. 월드가 아직 살아 있는 자리라 드래곤의 실제 체력을 읽어 적고, 최후의
	 * 저항이면 드래곤을 저장 <b>전에</b> 놓아주고 보스바 이름을 되돌린다 — 앉힌 채 저장되지 않는다.
	 */
	public static void onServerStopping(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		// 룰렛이 돌던 중에 꺼지면 그 카드를 먼저 쌓는다(2026-10-18 검토) — 접속 종료 쪽처럼. 전에는
		// TrialFreeze.onServerStopping 이 끝난 결과를 버려 카드가 사라지고 다시 뽑혔다. 그쪽이 이보다 먼저
		// 등록되어(SharedFateMod) 정지를 풀고 결과를 남겨 둔다.
		applyFinishedTrial(server);
		if (SESSIONS.isEmpty()) {
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		if (end == null) {
			return;
		}
		EnderDragon dragon = findDragon(end);
		long now = end.getGameTime();
		for (DragonTrialSession session : new ArrayList<>(SESSIONS.values())) {
			if (session.suspended()) {
				continue;
			}
			ShareTeam team = TeamManager.get(server).teamById(session.teamId());
			suspend(end, session.teamId(), team == null ? session.teamId().toString() : team.name(),
					session, dragon, "서버 종료", now);
		}
	}

	/**
	 * <b>월드 초기화로 서버가 내려간다</b>(팀 전멸 · 운영자 초기화) — 엔드 전투를 일시 중단으로 남기지 않고
	 * <b>버린다.</b> {@code WorldResetCoordinator} 가 표식을 쓴 뒤 {@code halt} 직전에 부른다.
	 *
	 * <h2>이것이 없어서 일어나던 일 (2026-10-18 검토)</h2>
	 *
	 * <p>전멸한 틱에도 팀원은 엔드(사망 화면)에 있어 전투는 싸우는 중이고, 내려가는 길의 {@link #onServerStopping}
	 * 이 그 전투를 「일시 중단」으로 저장했다. 저장 파일이 서버 폴더에 있어 루프 스크립트가 월드만 지운 새 회차에서
	 * 그 세션이 되살아나 영영 멈춘 채 남고, 새 회차에 접속한 팀이 엔드로 끌려가 새 드래곤에 옛 카드·옛 체력·최후의
	 * 저항까지 걸렸다 — 사실상 서버를 못 썼다. 저장 파일을 월드 폴더로 옮긴 것({@link DragonTrialStore})이 첫 겹이고
	 * 이것이 둘째 겹이다. 여기서 세션을 비우고 파일을 지우므로 뒤이어 도는 {@link #onServerStopping} 이 저장할 것이
	 * 없다. 판(최후의 저항)은 무적 없이 거둔다 — 보더 되돌리기는 그 뒤의 {@code DragonLastStand.onServerStopping} 이다.
	 */
	public static void discardForWorldReset(@Nullable MinecraftServer server) {
		if (server == null || SESSIONS.isEmpty()) {
			return;
		}
		ServerLevel end = server.getLevel(Level.END);
		for (UUID teamId : new ArrayList<>(SESSIONS.keySet())) {
			DragonLastStand.onTeamGone(end, teamId);
		}
		SharedFateMod.LOGGER.info("[END] 월드 초기화로 서버를 내립니다 — 엔드 전투 {}개를 일시 중단으로 남기지 않고"
				+ " 버립니다", SESSIONS.size());
		SESSIONS.clear();
		PENDING_SUMMON.clear();
		READY_AT.clear();
		RECALL_AT.clear();
		ARRIVED.clear();
		CRYSTALS_AT_START.clear();
		endTrials();
		// 빈 목록이면 파일을 지운다(DragonTrialStore.save).
		persist();
	}

	/**
	 * <b>주인 없는 드래곤</b>에서 시련이 남긴 것을 걷는다 — 시련 세션이(멈춘 것까지) 하나도 없는 판에서만.
	 *
	 * <ul>
	 *   <li>체력 강화 수정자 — 저장되는 수정자라 걷지 않으면 다음 판(시련을 끈 팀의 바닐라 드래곤전)까지
	 *       남는다. 시련 팀이 해체되거나 드래곤이 내려간 채 세션이 닫힌 판이 그 길이다</li>
	 *   <li>「최후의 저항」 이름을 단 채 앉아 있는 드래곤 — {@code DragonLastStand.releaseOrphan}. 0.41 이전
	 *       판이 접속 종료를 「처치」로 잘못 닫으며 세이브에 남긴 드래곤도 여기서 풀린다</li>
	 * </ul>
	 *
	 * <p>엔드에 사람이 있을 때만, {@value #ORPHAN_SWEEP_TICKS} 틱마다 본다 — 사람이 없으면 드래곤도 메모리에
	 * 없다.
	 */
	private static void releaseOrphanDragon(ServerLevel end, long now) {
		// 멈춘 세션까지 센다 — 멈춘 시련 세션의 드래곤은 강화를 쥔 채 그 팀을 기다린다.
		if (now % ORPHAN_SWEEP_TICKS != 0L || end.players().isEmpty()
				|| anyTrialSession(SESSIONS.values(), null) != null) {
			return;
		}
		EnderDragon dragon = findDragon(end);
		if (dragon == null) {
			return;
		}
		boolean released = DragonLastStand.releaseOrphan(dragon);
		boolean weakened = dropStrength(dragon);
		if (released || weakened) {
			SharedFateMod.LOGGER.info("[END] 시련 세션이 없는 드래곤을 바닐라로 되돌렸습니다 —{}{} · 체력 {} / {}",
					released ? " 최후의 저항 자세·이름 풀기" : "", weakened ? " 체력 강화 떼기" : "",
					dragon.getHealth(), dragon.getMaxHealth());
		}
	}

	/**
	 * 이번 틱에 실행기를 돌려야 하는가. <b>월드를 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <p>{@code !=} 이지 {@code >} 가 아니다. 월드가 바뀌어 게임 시각이 거꾸로 가는 길이 있다면
	 * {@code >} 는 실행기를 그 시각까지 <b>영영</b> 세우지만, {@code !=} 는 같은 시각 한 번만 막는다.
	 *
	 * @param ranAt {@link #executorsRanAt}
	 * @param now   지금 엔드 게임 시각
	 */
	static boolean executorsDue(long ranAt, long now) {
		return now != ranAt;
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
	 *
	 * <h2>「세션이 없을 때」가 아니라 「<b>시련 세션</b>이 없을 때」다 (2026-10-06)</h2>
	 *
	 * <p>되돌리는 상태는 시련을 켠 세션만 만든다. 그리고 시련 세션은 이제 한 번에 하나다
	 * ({@link #startSession} 의 「시련 세션은 하나」). 전에는 세션이 하나라도 남으면 돌아갔으므로,
	 * 시련 팀이 사라지고(2026-10-06 검토에서 확정된 문제) 시련을 끈 팀이 같은 엔드에서 싸우고 있으면 화살 면역·적대
	 * 엔더맨 같은 시련의 흔적이 <b>그 바닐라 판에 남았다.</b> 끈 팀의 세션은 이 상태를 읽지도 쓰지도
	 * 않으므로 그 팀이 남아 있어도 비워도 된다.
	 */
	private static void endTrials() {
		// 「싸우는」 시련 세션이다 — 멈춘 세션은 판에 아무것도 걸어 두지 않는다({@link #suspend} 가 이 길로
		// 이미 되돌렸다).
		if (activeTrialHolder(SESSIONS.values()) != null) {
			return;
		}
		TrialRisks.clearState();
		// 「착지」 패시브의 깃발도 여기서 내린다. 드래곤이 죽은 틱에는 위의 continue 때문에
		// DragonPassives.tick 이 안 불리므로, 앉아 있던 채로 죽으면 깃발이 켜진 채 남는다 —
		// 다음 전투의 첫 틱까지 이어지면 아무도 안 앉았는데 화살이 안 박힌다.
		DragonPerch.standDown();
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
					fireFirstCrystal(end, session);
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

	/**
	 * 「첫 크리스탈」 자리. 팀이 「첫 봉인」을 가졌으면 그 자리를 <b>시련 없이</b> 넘긴다(0.44.0-dev) —
	 * 룰렛이 돌지 않고 카드가 쌓이지 않는다. 넘긴 것도 {@code fired} 에 적혀 저장되므로 다시 터지지 않는다.
	 */
	private static void fireFirstCrystal(ServerLevel end, DragonTrialSession session) {
		if (session.fired().contains(TrialCatalog.Trigger.FIRST_CRYSTAL)) {
			return;
		}
		MinecraftServer server = end.getServer();
		com.sharedfate.team.TeamState state = server == null ? null
				: TeamManager.get(server).stateByTeamId(session.teamId());
		if (!com.sharedfate.perk.DragonPrep.sealsFirstCrystal(state)) {
			session.fire(TrialCatalog.Trigger.FIRST_CRYSTAL);
			return;
		}
		if (session.seal(TrialCatalog.Trigger.FIRST_CRYSTAL)) {
			ShareTeam team = TeamManager.get(server).teamById(session.teamId());
			if (team != null) {
				for (ServerPlayer member : membersOf(server, team, end)) {
					member.sendSystemMessage(net.minecraft.network.chat.Component.literal(
							"[증강] 「첫 봉인」 — 첫 크리스탈 자리의 시련이 봉인되었습니다."));
				}
			}
			SharedFateMod.LOGGER.info("[END] 첫 크리스탈 자리 — 「첫 봉인」으로 시련 없이 넘깁니다");
			persist();
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
	 * ⚠⚠ <b>이 월드의 드래곤전이 아직 살아 있는가.</b> 거짓이면 <b>이미 끝난 전투</b>라
	 * 수락창도 띄우지 않고 세션도 열지 않는다.
	 *
	 * <h2>2026-10-01 의 무한 고리</h2>
	 *
	 * <p>사람 말: <b>「엔더드래곤 잡앗는데 시련을 시작합니다 가 떳어. 무한으로 확인 눌러도」</b>.
	 * 시험 서버 로그(167~190줄)에 고리가 그대로 찍혀 있었다 — 1초에 두세 바퀴다.
	 *
	 * <ol>
	 *   <li>드래곤을 잡는다 → {@link #tickSessions} 가 세션을 닫는다</li>
	 *   <li>사람은 <b>여전히 엔드에 서 있다</b> → {@link #detectArrival} 이 「세션 없는 팀원이
	 *       엔드에 있다」로 보고 수락창을 띄운다</li>
	 *   <li>확인하면 {@link #startSession} 이 <b>드래곤 없는 전투</b>를 연다
	 *       (로그의 「드래곤 체력 0.0 · 크리스탈 0개」가 그 증거다 —
	 *       {@link #strengthenDragon} 은 드래곤을 못 찾으면 0.0 을 돌려준다)</li>
	 *   <li>그 전투가 그 자리에서 「처치」로 판정된다(0초 · 0장) → 2번으로 돌아간다</li>
	 * </ol>
	 *
	 * <h2>⚠ 세 가지를 <b>함께</b> 본다 — 하나만 보면 반드시 틀리는 틱이 있다</h2>
	 *
	 * <ol>
	 *   <li><b>살아 있는 드래곤이 있는가</b>({@code dragonAlive}). 위 고리를 실제로 만든 것이
	 *       이 한 가지이고, 바닐라 전투 객체를 못 읽는 판(아래)에서는 이것만 남는다.
	 *       ⚠ 다만 이것 <b>하나만</b> 보면 안 된다 — 드래곤이 죽은 자리에 바닐라
	 *       {@code setDragonKilled} 가 붙지 않는 길이 있다(그쪽은 UUID 가 제 드래곤과
	 *       같을 때만 적는다). 거꾸로 <b>크리스탈로 되살리는 중</b>에는 아직 드래곤이 없으니
	 *       이 칸이 「끝났다」로 읽히는데, 되살아난 그 틱에 다시 참이 되므로 그것은 맞다</li>
	 *   <li><b>바닐라의 {@code EnderDragonFight.dragonKilled}</b>({@code fightClosed}).
	 *       {@code EnderDragonFightAccessor} 가 꺼내고, <b>그 칸이 왜 옳은지와
	 *       {@code hasPreviouslyKilledDragon} 을 왜 안 쓰는지는 그 파일에 바이트코드와 함께
	 *       적어 두었다.</b> 한 줄로 적으면 — <b>크리스탈 넷으로 되살리면 바닐라가 이 깃발을
	 *       스스로 내린다.</b> 전투 객체가 {@code null} 이면(바닐라 엔드가 아닌 판) 이 칸은
	 *       「끝나지 않았다」로 둔다. 모를 때 「끝났다」로 기울면 <b>엔드에 들어가도 전투가
	 *       영영 안 열리는 월드</b>가 생긴다</li>
	 *   <li><b>우리 회차 상태</b>({@code runWon}). {@code sharedfate-run-state.json} 의
	 *       {@code status} 가 {@code victory} 다. 바닐라 깃발이 아직 안 적힌 틱과 위 1번의
	 *       UUID 어긋남을 함께 메운다. ⚠ <b>이 칸은 회차가 끝날 때까지 안 내려간다</b> — 곧
	 *       승리로 적힌 회차에서는 되살려도 창이 안 뜨고, 그것이 의도다. 그 회차는 이미
	 *       끝났고 월드가 초기화를 기다리는 중이다({@code WorldResetCoordinator}).
	 *       {@code dragonKillEndsRun} 이 꺼진 서버에서는 이 칸이 언제나 거짓이라 아무 일도
	 *       하지 않는다</li>
	 * </ol>
	 *
	 * <p>⚠ <b>바닐라 되살리기를 막지 않는다.</b> 엔드 크리스탈 넷을 출구 포털에 놓아 드래곤을
	 * 다시 띄우는 것은 바닐라 기능이고, 그 길로 드래곤이 살아나면 <b>새 전투라 수락창이 다시
	 * 떠야 한다.</b> 1번과 2번이 둘 다 그 순간 「살아 있다」로 돌아서므로 그대로 성립한다.
	 */
	private static boolean endFightLive(ServerLevel end) {
		EnderDragon dragon = findDragon(end);
		// ⚠ findDragon 이 이미 isAlive() 로 걸렀지만 체력을 한 번 더 본다. 「살아 있다」의 뜻이
		// 판마다 달라질 수 있는 자리이고, 여기서 틀리면 돌아오는 것이 바로 그 무한 고리다.
		// 죽음 연출(DYING) 중인 드래곤도 끝난 것으로 본다 — tickSessions 가 그 틱에 처치로 닫으므로, 여기서
		// 살아 있다고 하면 그 자리에서 「첫 입장」 수락창이 뜬다(dying 설명).
		boolean dragonAlive = dragon != null && dragon.getHealth() > 0.0F && !dying(dragon);
		EnderDragonFight fight = end.getDragonFight();
		// ⚠ 캐스팅이 아니라 instanceof 다. 믹스인이 sharedfate.mixins.json 에 안 등록되면
		// 캐스팅은 엔드에 사람이 있는 매 틱 ClassCastException 으로 서버를 때리는데, 이쪽은
		// 「바닐라 깃발을 못 읽었다」로 떨어져 남은 두 수단으로 버틴다 — 그래도 조용히 넘어가지는
		// 않는다. 등록을 못박는 시험이 따로 있다(TrialEntranceGateTest).
		boolean fightClosed = fight instanceof EnderDragonFightAccessor accessor
				&& accessor.sharedfate$dragonKilled();
		return endFightLive(dragonAlive, fightClosed, RunProgressManager.isVictory());
	}

	/**
	 * {@link #endFightLive(ServerLevel)} 의 판단만 떼어 둔 것. <b>월드를 하나도 모른다 — 시험이
	 * 직접 굴린다.</b>
	 *
	 * <p>셋이 <b>모두</b> 「살아 있다」라고 말해야 참이다. 곧 <b>하나라도 「끝났다」면 끝난
	 * 것</b>이고, 그것이 이 저장소의 제1 함정(「한쪽만 막으면 반드시 샌다」)을 값의 모양으로
	 * 적은 것이다.
	 *
	 * @param dragonAlive 살아 있는 드래곤이 이 월드에 있고 체력이 0 보다 큰가
	 * @param fightClosed 바닐라 {@code EnderDragonFight.dragonKilled}. 전투 객체를 못 읽었으면
	 *                    <b>거짓</b>이다 — 모를 때는 「끝나지 않았다」 쪽이다
	 * @param runWon      이 회차가 이미 승리로 적혔는가
	 */
	static boolean endFightLive(boolean dragonAlive, boolean fightClosed, boolean runWon) {
		return dragonAlive && !fightClosed && !runWon;
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
	 * 이 팀이 지금 <b>시련 전투를 치르는 중</b>인가 — 시련을 켠 세션이 열려 있고 일시 중단되지 않았다.
	 * 「드래곤전 대비」의 전투 증강(착지 노리기 · 하늘 사수)이 묻는다.
	 */
	public static boolean fighting(@Nullable UUID teamId) {
		return com.sharedfate.perk.DragonPrep.fighting(sessionOf(teamId));
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
		// 보더의 「원래 값」 기억만 버린다. 월드는 만지지 않는다 — SERVER_STOPPED 에서도 불린다.
		DragonTrialBorder.clearState();
		PENDING_SUMMON.clear();
		CRYSTALS_AT_START.clear();
		READY_AT.clear();
		RECALL_AT.clear();
		ARRIVED.clear();
		PAUSED_CLOSED_PENDING.clear();
		executorsRanAt = Long.MIN_VALUE;
		lastSeenHealth = Float.NaN;
		lastSeenMax = Float.NaN;
		// 수락창은 저장되지 않고 붙들기도 매 틱 다시 거는 것이라 버리기만 하면 된다.
		// 월드를 만질 수 없는 자리에서도 불리므로(SERVER_STOPPED) 꾸러미를 보내지 않는다.
		TrialEntranceGate.clearState();
		TrialFreeze.reset();
		TrialRisks.clearState();
		DragonPassives.clearState();
		// ⚠ 여기는 SERVER_STOPPED 에서도 불려 월드를 만질 수 없다. 저쪽이 정적 상태만
		// 버리는 까닭과, 나머지를 어디서 되돌리는지는 DragonLastStand.clearState 에 있다.
		DragonLastStand.clearState();
		// 패턴 타이머 HUD 의 「누구에게 보냈나」. 연결이 없을 수 있으므로 지우는 묶음은 안 보낸다 —
		// 받는 쪽이 접속이 끊길 때 스스로 지운다.
		TrialTimers.clearState();
	}

	/**
	 * 시험·명령용. 지금 당장 전투를 연다.
	 *
	 * <p>⚠⚠ <b>이 길도 둘째 겹을 지난다.</b> {@link #startSession} 이 끝난 전투를 거절하므로
	 * {@code /shareteam trialtest start} 로도 「죽은 드래곤으로 전투를 열고 그 자리에서 처치
	 * 판정」이라는 2026-10-01 의 무한 고리를 만들 수 없다 — 까닭은
	 * {@link #endFightLive(ServerLevel)} 에 적혀 있다.
	 *
	 * @return 전투를 열었으면 참. <b>거짓이면 팀을 옮기지도 않았다</b> — 명령이 그 사실을 사람에게
	 *         알려야 한다. 조용히 아무 일도 안 일어나면 「명령이 안 듣는다」로 시간을 버린다
	 */
	public static boolean forceStart(MinecraftServer server, ShareTeam team) {
		ServerLevel end = server.getLevel(Level.END);
		if (end == null || team == null) {
			return false;
		}
		SESSIONS.remove(team.teamId());
		resetTrialDelay(team.teamId());
		RECALL_AT.remove(team.teamId());
		ARRIVED.remove(team.teamId());
		// ⚠ 옛 세션의 최후의 저항 판도 거둔다(2026-10-18 검토). 남기면 새 세션의 첫 틱이 그 판을 그대로 이어
		// 받아 수락창·입장 연출 없이 붙박이 드래곤과 싸운다. 처치가 아니므로 무적은 없다.
		DragonLastStand.onTeamGone(end, team.teamId());
		// ⚠ 떠 있던 입장 수락창도 접는다. 남겨 두면 다음 틱에 그 창이 「확인됐다」로 끝나면서
		// 팀을 단상으로 모으고 전투를 한 번 더 열어 — 방금 이 명령으로 연 세션을 통째로 덮어쓴다.
		// 명령으로 직접 여는 길이라 묻는 창이 뜻을 잃는 자리이기도 하다.
		TrialEntranceGate.abandon(team.teamId());
		// ⚠ 전투를 먼저 열고 그 뒤에 부른다 — 거절된 틱에 팀이 끝난 엔드로 끌려가지 않게.
		if (!startSession(server, end, team, end.getGameTime())) {
			return false;
		}
		summonTeam(server, end, team);
		return true;
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
		// 터진 자리도 함께 푼다(2026-10-18 검토 — 명령 안내 「터진 자리도 함께 풀립니다」 · fire 안내 「clear 로 비운 뒤
		// 다시 터뜨리십시오」와 코드가 어긋나 있었다). 줄도 함께 비운다 — 줄은 터진 자리 안에서만 산다.
		// ⚠ 최후의 저항 자리(체력 30%)만은 남긴다 — 카드가 아니라 별개의 보스전이고, 풀면 드래곤이 30% 아래일 때
		// 다음 틱에 진입(드래곤 +20%p · 팀 회복)이 한 번 더 열린다.
		List<String> keep = session.fired().contains(TrialCatalog.Trigger.HEALTH_30)
				? List.of(TrialCatalog.Trigger.HEALTH_30.name()) : List.of();
		session.restore(List.of(), keep, List.of(), false, Map.of());
		CRYSTALS_AT_START.remove(team.teamId());
		TrialRisks.clearState();
		persist();
		return true;
	}

}
