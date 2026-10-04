package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「최후의 저항」의 <b>2페이지 진입 연출.</b> 체력 30%에 닿은 뒤 <b>시계가 돌기 전</b>의 8초다.
 *
 * <h2>사람이 정한 순서</h2>
 *
 * <p>사람 말: <b>「2페이지 넘어갈떄 엔더드래곤이 중앙으로 내려오면서 무적인된채로 내려오면서
 * 보더가 생기면서, 이럴떄 유저들이 보더 밖에잇으면 안으로 데려오되 완전 중앙으론 데려오지마.
 * 그리고 중앙에 내려오고 무적인채로 연출이갈건데 그땐 시간 재지말고, 중앙 엔더드래곤쪽에
 * 신호기빛 4개가 정사각형으로 켜졋다가 (보라색 빛으로) 드래곤한테 힘이 증폭된다는 연출을
 * 넣고(점점 체력이 차는거 어처피 20프로 채울거니까 그떄 시각적으로 체력을 올리면되겟지?)
 * 드래곤에게 터지는 소리가나면서 2페이지 시작 이라고 화면에 띄우고 시작하는걸로 하고싶어.」</b>
 *
 * <p>화면 글자만 <b>「최후의 저항」</b>이다 — 사람이 「2페이지 시작」은 개발 용어 같다는 지적을
 * 받아들였다.
 *
 * <pre>
 * 0                          지대의 원이 선다 · 크리스탈과 엔더맨이 사라진다 · 굉음 · 화면 흔들림
 * 0                          원 밖에 있던 사람을 <b>있던 방향 그대로</b> 안쪽 가장자리로
 * 0 .. {@value #DESCEND_TICKS}       드래곤이 중앙으로 <b>내려온다</b>(궤적을 남긴다)
 * {@value #DESCEND_TICKS}             보라색 신호기 빛 <b>넷</b>이 정사각형으로 켜진다
 * .. +{@value #CHARGE_TICKS}          <b>체력이 20%p 만큼 점점 찬다</b> — 보스바가 차오르는 것이 곧 연출이다
 * {@value #BOOM_AT}                    드래곤에게 <b>터지는 소리</b> · 화면에 「최후의 저항」 · 빛이 꺼진다
 * .. +{@value #SETTLE_TICKS}          한 숨 돌리는 틈
 * {@value #LENGTH_TICKS}                <b>여기서부터 시계가 돈다</b>
 * </pre>
 *
 * <h2>⚠⚠ 연출 동안 <b>시간을 재지 않는다</b> — 그것을 어떻게 지켰는가</h2>
 *
 * <p>사람이 <b>「그땐 시간 재지말고」</b>라고 했다. 「안전지대 시계도 패턴 고르기도 번개도 연출이
 * 끝난 뒤에」라는 뜻이고, 그것을 여기서 막는 것이 아니라 <b>{@code DragonLastStand.Stand} 가
 * 시계의 원점을 뒤로 미는 것</b>으로 지킨다.
 *
 * <ul>
 *   <li>{@code Stand.clockBase} 가 <b>{@code beganAt + }{@value #LENGTH_TICKS}</b> 다. 그 값을
 *       {@code DragonLastStandZone.tick} 과 {@code DragonLastStandPatterns.tickLightning} 에
 *       그대로 넘기므로, 연출이 도는 동안 두 시계의 {@code elapsed} 가 <b>음수</b>다 —
 *       지대는 첫 틱부터 <b>반경 42 의 원만 그리고 그대로 서 있고</b>(축소도 밖 피해도 {@code elapsed < 0}
 *       에서 돌아간다) 번개는 첫 볼리 시각이 미래라 한 발도 안 나간다</li>
 *   <li>패턴은 {@code Stand.nextPatternAt} 이 {@code clockBase + ENTRY_GRACE_TICKS} 라 연출이
 *       끝나고 무적 3초가 지난 뒤에 처음 고른다 — <b>고치기 전과 같은 규칙</b>이고 원점만 밀렸다</li>
 *   <li>{@code DragonLastStand.tick} 이 연출 중에는 {@code advance} 와
 *       {@link DragonLastStandObjects} 에 <b>닿지 않고 돌아간다</b></li>
 * </ul>
 *
 * <p>⚠ <b>반구 블록 파괴({@link DragonLastStandDome})만은 연출 중에도 돈다.</b> 그것은 시계가
 * 아니라 <b>상시 규칙</b>이고(원점이 {@code beganAt} 이 아니라 {@code now} 의 20틱 나머지다)
 * 사람에게 피해를 주지 않는다. 연출이 끝나는 순간 지붕이 이미 걷혀 있는 편이 「블록으로 패턴을
 * 피할 수 없다」에 맞다.
 *
 * <h2>드래곤 무적 — <b>되돌릴 것을 한 칸도 남기지 않는다</b></h2>
 *
 * <p>고른 길은 <b>매 틱 체력을 우리가 쓰는 것</b>이다. 그 한 줄이 무적이면서 동시에
 * <b>「체력이 점점 찬다」</b>다 — 사람이 말한 두 가지가 같은 한 줄이다.
 *
 * <table border="1">
 *   <caption>무적을 거는 길 셋</caption>
 *   <tr><th>길</th><th>되돌릴 것</th></tr>
 *   <tr><td>{@code Entity.setInvulnerable(true)}</td>
 *       <td><b>있다.</b> {@code Invulnerable} 태그로 <b>개체와 함께 저장된다</b> — 연출 도중
 *           서버가 죽으면 다시 떴을 때도 무적인 드래곤이 남고 전투가 영영 끝나지 않는다.
 *           {@code TrialCrystalRevive} 가 크리스탈에서 같은 이유로 이 길을 버렸다</td></tr>
 *   <tr><td>{@code setInvulnerableTime(...)} 을 매 틱</td>
 *       <td>없다. 그런데 <b>체력을 올리는 일을 따로 해야</b> 하고, 그러면 무적과 회복이 두 줄이
 *           되어 한쪽만 고쳐질 수 있다</td></tr>
 *   <tr><td><b>매 틱 {@code setHealth(지금 차야 할 값)}</b></td>
 *       <td><b>없다.</b> ← 고른 것. 우리가 쓰기를 멈추는 순간 바닐라가 그대로 이어받는다
 *           ({@code TrialCrystalRevive.hold} 가 자리를 매 틱 쓰는 것과 같은 판단이다)</td></tr>
 * </table>
 *
 * <h3>⚠⚠ 2026-10-04 — 「무적」은 이 줄이 아니라 {@link DragonLastStandShield} 다</h3>
 *
 * <p>사람이 플레이해 보고 <b>「최후의 저항 시작하고 첫 패턴 전까지 모든 공격 막는 쉴드 생기고
 * 피해 안 받게. 지금은 무슨 체력회복하면서 쳐맞는 거 같아」</b>라고 했다. 위 표의 판단에 빠진
 * 것이 그것이다 — 이 줄은 {@code END_SERVER_TICK} 에서 돌므로 그 사이의 피해는 <b>끝까지
 * 들어간다.</b> 피격 번쩍임·피격음·화살 박힘이 다 나가고 체력만 틱 끝에 다시 올라가니
 * 「밀리는 것」이 아니라 <b>「맞으면서 회복하는 것」</b>으로 읽혔다. 게다가 연출이 끝난 뒤 첫
 * 패턴까지 3초는 이 줄조차 안 돌아 <b>진짜로 깎였다.</b>
 *
 * <p>그래서 막는 일은 {@link DragonLastStandShield} 로 옮겼다 — 드래곤이 맞는 문
 * ({@code EnderDragon.hurt(level, part, ...)}) 의 첫머리에서 거절하므로 피해 처리가 아예 시작되지
 * 않는다. <b>이 줄은 그대로 둔다.</b> 사람이 주문한 「점점 체력이 차는거」가 이 줄이고, 보호막이
 * 통과시키는 단 하나(무적을 지나치는 피해 — 공허·{@code generic_kill})가 남은 체력을 다 못
 * 깎았을 때 차오르는 선을 지키는 것도 이 줄이다. 보호막도 {@code setInvulnerable} 을 안 쓰므로
 * 위 표의 「되돌릴 것이 없다」는 그대로 참이다.
 *
 * <p>⚠ 전에 여기 「때려도 아무 일이 없다가 버그로 안 읽힌다 — 보스바가 거꾸로 차오르므로
 * 밀리는 것으로 읽힌다」가 적혀 있었다. <b>사람이 실제로 본 것이 그 반대였다.</b> 근거로 쓰지 말 것.
 *
 * <h2>지대 밖 사람 — 「있던 방향 그대로 안쪽 가장자리」</h2>
 *
 * <p>사람이 <b>「완전 중앙으론 데려오지마」</b>라고 했다. 그래서 방향을 그대로 두고 거리만
 * 줄인다 — {@link #pullTarget} 이 <b>중앙에서 그 사람 방향으로</b> 땅을 짚어 나가
 * <b>땅이 이어진 가장 먼 자리</b>를 고른다. 방향 감각이 깨지지 않고 완전 중앙도 아니다.
 *
 * <h3>2026-10-04 — 지대가 「나갈 수 있는 원」이 된 뒤에도 남긴다</h3>
 *
 * <p>사람이 「안전지대가 사실상 보더라 나갈 수가 없어」라고 해서 지대가 보더를 버렸다. 그래도 이
 * 데려오기는 남긴다. 근거가 셋이다.
 *
 * <ol>
 *   <li><b>사람이 직접 주문한 순서다</b> — 「유저들이 보더 밖에잇으면 안으로 데려오되」. 보더를
 *       버린 까닭은 「안에서 못 나간다」였지 「밖에서 데려온다」가 아니었다</li>
 *   <li><b>전투의 출발선을 맞춘다.</b> 체력 30% 는 아무 때나 오므로 그 순간 바깥 섬·관문·기둥 너머에
 *       있던 사람이 있다. 원이 벽이 아니게 된 뒤로는 그 사람이 <b>연출 8초 + 무적 3초가 끝나는 틱부터
 *       초당 8</b> 을 맞으며 돌아와야 한다 — 시작하자마자 팀 체력을 깎는 것은 그 사람이 고른 일이
 *       아니다</li>
 *   <li><b>원 안으로 데려와도 나갈 자유는 그대로다.</b> 데려온 뒤에 다시 걸어 나가는 것은 막지
 *       않는다 — 그것이 사람이 고른 「나갈 수 있는 원」이다</li>
 * </ol>
 *
 * <p>⚠⚠ <b>발밑에 땅이 있어야 한다.</b> 공유 체력이라 한 사람의 낙사가 팀 전멸이고 그것이 곧
 * 월드 삭제다. 그래서 거리를 두 번 자른다.
 *
 * <ol>
 *   <li><b>{@link #innerEdge()}</b> — 시작 원(반경 42)에서 {@value #WALL_MARGIN} 칸 안쪽까지만.
 *       보더 시절에는 정사각형의 <b>내접원</b>이라 이 값을 썼고, 원이 된 지금은 그것이 곧 지대
 *       자신이다. 여유를 둔 까닭은 {@link #WALL_MARGIN}</li>
 *   <li><b>{@code TrialLandingShock.groundedReach}</b> — 그 길을 0.5칸씩 짚어 <b>땅이 끊기기
 *       전</b>에서 멈춘다. 중앙 섬은 둥글지 않고 사람이 파 놓은 구멍도 있다. 이 함수를 <b>그대로
 *       부른다</b> — 날개 퍼덕이기의 천장과 같은 것이라 여기서 다시 짜면 두 벌이 된다</li>
 * </ol>
 *
 * <p>출발점이 <b>드래곤을 못박아 둔 자리</b>(= 중앙 기반암 포디움 맨 위)인 것이 요점이다. 거기는
 * 언제나 땅이고, 짚어 나가는 길이 그 점에서 <b>이어져</b> 있으므로 도착 자리가 허공 위의 외딴
 * 발판일 수 없다. 길이 통째로 막혀 {@code 0} 이 나오면 그 자리가 곧 포디움이라 <b>그때만</b>
 * 중앙이 된다 — 일어날 수 없는 경우의 바닥이고, 사람이 금지한 「완전 중앙」이 그 하나뿐이다.
 *
 * <p>⚠ {@code EndFightTeleportLock} 에 걸리지 않는다. 그것이 막는 것은 <b>엔드에서 밖으로</b>
 * 나가는 순간이동이고({@code blocks()} 가 목적지가 엔드면 첫 줄에서 거짓을 돌려준다) 여기는
 * <b>엔드 안에서 엔드 안으로</b>다. 게다가 그 자물쇠는 {@code PositionSwapManager.Position} 을
 * 지나는 길만 보는데 여기는 {@code ServerPlayer.teleportTo} 를 직접 부른다 —
 * {@code DragonTrialManager.summonTeam} 과 같은 길이다.
 *
 * <h2>보라색 신호기 빛 넷 — <b>블록을 놓지 않는다</b></h2>
 *
 * <p>진짜 신호기 빛은 {@code BeaconBlockEntity} 가 있어야 나므로 <b>블록을 놓아야</b> 한다.
 * 이 페이즈에서 블록을 놓는 것은 금지이고(다음 전투의 발판이 달라진다) 부수는 것만
 * {@link DragonLastStandDome} 에 열려 있다. 그래서 {@link DragonLastStandLights} 의 디스플레이
 * 개체로 흉내 낸다 — <b>점 예산을 한 개도 쓰지 않고</b> 통신 규약도 올라가지 않는다.
 *
 * <p>정사각형의 반변은 {@value #BEACON_HALF_SIDE} 칸이다. 근거는 <b>드래곤 몸</b>이다 — 앉은
 * 드래곤의 상자가 가로 16칸이라 반이 8 이고, 거기에 2칸을 더해 날개 끝보다 밖에 세운다. 그래야
 * 넷이 「드래곤을 둘러싼 정사각형」으로 읽히고, 안에 있는 사람(머리는 몸 앞 6.5칸이다)이 빛
 * 안쪽에 선다. {@code TrialEntrance.SEAT_RING_RADIUS} 가 같은 근거로 12 를 쓴다.
 *
 * <h2>연출 길이 — <b>사람이 정하지 않았다</b></h2>
 *
 * <p>{@value #LENGTH_TICKS}틱 = <b>8초</b>로 잡았다. 나눠 쓴 근거가 셋이다.
 *
 * <ul>
 *   <li><b>하강 {@value #DESCEND_TICKS}틱(2초)</b> — {@code TrialCrystalRevive.hold} 가 매 틱
 *       남은 거리의 20%를 당기므로 40틱이면 남은 거리가 {@code 0.8^40 ≈ 0.00013} 배다. 아레나를
 *       가로지르는 80칸에서 출발해도 1cm 안이라 <b>반드시 도착한다.</b>
 *       {@code TrialEntrance.PERCH_TICKS} 가 같은 값을 같은 근거로 쓴다</li>
 *   <li><b>충전 {@value #CHARGE_TICKS}틱(5초)</b> — 20%p 를 5초에 채우면 <b>초당 4%p</b> 다.
 *       4인 기준 2400 의 4%는 초당 96 이라 보스바가 <b>눈에 보이는 속도로</b> 찬다. 2초로 줄이면
 *       한 번 튀는 것과 구별되지 않고, 10초로 늘리면 아무것도 할 수 없는 시간이 그만큼 길어진다</li>
 *   <li><b>여운 {@value #SETTLE_TICKS}틱(1초)</b> — 터지는 소리와 타이틀이 나간 뒤 번개 첫 볼리가
 *       바로 붙으면 글자를 읽을 틈이 없다. {@code TrialEntrance.SETTLE_TICKS} 가 같은 값을 같은
 *       근거로 쓴다</li>
 * </ul>
 *
 * <p>⚠ 8초 동안 팀은 <b>드래곤을 깎을 수 없다.</b> 그 8초는 「115초에 1200을 깎는다」 계산에
 * 들어가지 않는다 — 시계가 연출이 끝난 뒤부터 도므로 <b>115초는 그대로 115초</b>다.
 *
 * <h2>점 예산</h2>
 *
 * <p>이 파일이 한 틱에 쓰는 점은 가장 많을 때 <b>{@value #WORST_TICK_POINTS}</b>(하강 궤적)이다.
 * 그리고 <b>연출 중에는 번개도 패턴도 돌지 않으므로</b> 이 값이 남의 것과 겹치지 않는다 — 곧
 * 이 파일은 {@code DragonLastStandPatterns.worstCasePointsPerTick} 의 답을 <b>바꾸지 않는다.</b>
 * 신호기 빛 넷은 디스플레이 개체라 <b>0점</b>이고, 굉음·폭발·소멸 빛은 개수를 세는 형태라
 * <b>꾸러미 한 장씩</b>이다.
 *
 * <p>⚠ 2026-10-04 부터 연출 중에 <b>함께 도는 것이 하나 생겼다</b> — {@link DragonLastStandShield}
 * 의 반구와 막힘 불꽃이다. 그 합은 그쪽 {@code worstShieldTickPoints()}(이 파일의 값을 더해 센다)
 * 가 들고 있고 패턴이 도는 틱과는 여전히 겹치지 않는다.
 *
 * <h2>서버를 껐다 켜면 연출을 다시 돌리지 않는다</h2>
 *
 * <p>{@code DragonLastStand.resume} 이 {@code cinematicUntil} 을 {@link Long#MIN_VALUE} 로 두고
 * {@code clockBase} 를 <b>지금</b>으로 잡으므로 이 파일에 한 번도 닿지 않는다. 체력 +20%p 를
 * 다시 주지 않는 것도 그 한 줄이다 — 재시작마다 20%p 씩 회복하면 판이 끝나지 않는다.
 */
public final class DragonLastStandEntry {

	// ------------------------------------------------------------------ 단계 길이

	/**
	 * 드래곤이 중앙으로 내려오는 시간(틱). 2초.
	 *
	 * <p>{@code TrialEntrance.PERCH_TICKS} 와 <b>같은 값이고 같은 근거</b>다 —
	 * {@code TrialCrystalRevive.hold} 의 20% 당기기가 40틱이면 남은 거리를 만 분의 한 자리로
	 * 줄인다. 짧게 잡으면 신호기 빛이 켜질 때 드래곤이 아직 오는 중이고, 그러면 사람이 정한
	 * 「중앙에 내려오고 <b>그 뒤</b> 빛이 켜진다」 순서가 깨진다.
	 */
	static final int DESCEND_TICKS = 40;

	/**
	 * 체력이 차는 시간(틱). 5초. <b>사람이 정하지 않았다</b> — 근거는 클래스 설명에 있다.
	 *
	 * <p>20%p 를 이 시간에 나눠 올리므로 <b>초당 4%p</b> 다.
	 */
	static final int CHARGE_TICKS = 100;

	/** 터지는 소리와 타이틀이 나가는 시각(진입부터의 틱). */
	static final int BOOM_AT = DESCEND_TICKS + CHARGE_TICKS;

	/**
	 * 터진 뒤 시계가 돌기까지 숨 돌리는 틈(틱). 1초.
	 *
	 * <p>{@code TrialEntrance.SETTLE_TICKS} 와 같은 값을 같은 근거로 쓴다 — 글자가 뜬 위에
	 * 곧바로 번개 예고가 붙으면 읽을 틈이 없다.
	 */
	static final int SETTLE_TICKS = 20;

	/**
	 * 연출 전체 길이(틱). 8초.
	 *
	 * <p>⚠ <b>{@code DragonLastStand.Stand} 가 시계의 원점을 이만큼 뒤로 민다.</b> 여기를 고치면
	 * 안전지대·상시 번개·패턴 고르기가 함께 밀린다 — 그것이 「연출 동안 시간을 재지 않는다」를
	 * 지키는 유일한 배선이다.
	 */
	static final int LENGTH_TICKS = BOOM_AT + SETTLE_TICKS;

	// ------------------------------------------------------------------ 연출 값

	/** 화면 가운데 큰 글자. <b>사람이 정한 것이다</b> — 「2페이지 시작」은 개발 용어다. */
	private static final Component TITLE = Component.literal("최후의 저항");
	private static final Component SUBTITLE = Component.literal("드래곤의 힘이 증폭되었습니다");

	/**
	 * 보라색 신호기 빛이 서는 정사각형의 <b>반변</b>(칸).
	 *
	 * <p>근거는 <b>앉은 드래곤의 상자가 가로 16칸</b>이라는 것이다. 반이 8 이므로 8 에 세우면
	 * 빛이 날개에 묻히고, 2칸을 더해 10 으로 두면 <b>날개 바로 밖</b>에 넷이 선다. 머리는 몸 앞
	 * 6.5칸이라 붙어서 때리는 자리는 언제나 정사각형 <b>안</b>이다.
	 *
	 * <p>모서리까지의 거리가 {@code 10√2 = 14.1} 칸이고 첫 지대가 반경 42 의 원이므로 넷이 모두
	 * 지대 안이다.
	 */
	static final double BEACON_HALF_SIDE = 10.0;

	/**
	 * 신호기 빛의 높이(칸).
	 *
	 * <p>바닐라 신호기 빛은 월드 천장까지 가지만 그렇게 긴 상자는 <b>멀리서 화면을 통째로
	 * 가린다.</b> 48칸이면 흑요석 기둥(42칸)보다 높아 「하늘로 뻗는다」로 읽히고, 엔드 섬
	 * 위에서 올려다보면 끝이 시야 밖이다.
	 */
	static final double BEACON_HEIGHT = 48.0;

	/** 정사각형이므로 넷이다. */
	static final int BEACON_COUNT = 4;

	/**
	 * 지대 경계에서 이만큼 안쪽까지만 데려온다(칸).
	 *
	 * <p>처음 근거는 보더 충돌이었다 — 26.3 {@code Entity.collide} 가 <b>안에 있고 벽에서 2칸 안</b>
	 * 이면 보더의 충돌 형상을 얹으므로 3 이면 벽에 끼이지 않았다. 2026-10-04 에 보더를 버린 뒤로
	 * 그 근거는 사라졌고 값은 그대로 둔다. 지금 근거는 <b>빨간 입자 기둥 바로 위에 내려놓지 않는
	 * 것</b>이다 — 경계에 딱 붙여 두면 도착한 사람이 「내가 안인가 밖인가」를 기둥과 제 발을 견주어
	 * 읽어야 하고, 기둥 간격(1.4칸)과 먼지 크기 탓에 그 판단이 한 칸쯤 흔들린다. 3칸이면 기둥이
	 * 분명히 <b>제 뒤</b>에 있다.
	 */
	static final double WALL_MARGIN = 3.0;

	/** 하강 궤적에 찍는 점 수. {@code TrialEntrance.TRAIL_POINTS} 와 같은 값이다. */
	private static final int TRAIL_POINTS = 12;
	/** 궤적을 그리는 간격(틱). */
	private static final int TRAIL_STRIDE = 2;

	/**
	 * 이 파일이 한 틱에 쓰는 점 수의 상한. 하강 궤적 하나뿐이다.
	 *
	 * <p>신호기 빛은 디스플레이 개체라 0점이고, 굉음·폭발·소멸 빛은 개수를 세는 형태라 꾸러미
	 * 한 장씩이다({@code TrialEntrance.perch} 의 같은 문단). 연출 중에는 번개도 패턴도 돌지
	 * 않으므로 이 값은 남의 것과 겹치지 않는다.
	 */
	static final int WORST_TICK_POINTS = TRAIL_POINTS;

	/** 충전음을 되풀이하는 간격(틱). 1초. */
	private static final int CHARGE_HUM_TICKS = 20;
	/** 충전음의 첫 음높이. {@code DragonLastStandPatterns} 의 부채꼴 충전음과 같은 폭이다. */
	private static final float CHARGE_PITCH_LOW = 0.6F;
	/** 충전음의 마지막 음높이. 1.0 을 넘겨야 「올라갔다」가 들린다. */
	private static final float CHARGE_PITCH_HIGH = 1.4F;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 서 있는 보라색 신호기 빛들.
	 *
	 * <p>개체를 {@link DragonLastStandLights} 가 들고 있고 여기는 <b>일찍 지우기 위한 손잡이</b>만
	 * 든다. 정적이라 월드보다 오래 살므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final List<DragonLastStandLights.Glow> BEACONS = new ArrayList<>();

	/** 지금 연출을 몰고 있는 판이 시작한 틱. 바뀌면 남은 빛을 먼저 거둔다. */
	private static long owner = Long.MIN_VALUE;

	private DragonLastStandEntry() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 지금이 연출 중인가.
	 *
	 * <p>{@code DragonLastStand.tick} 은 이것을 묻지 않고 {@code Stand.cinematicUntil} 을 본다 —
	 * 재시작으로 되살린 판은 연출을 <b>아예 돌리지 않아야</b> 하고, 그 사실은 시각만으로는 알 수
	 * 없기 때문이다. 이 함수는 시험이 시간표를 훑을 때 쓴다.
	 */
	static boolean running(long beganAt, long now) {
		long step = now - beganAt;
		return step >= 0L && step < LENGTH_TICKS;
	}

	/**
	 * 이 틱에 드래곤 체력이 있어야 할 값.
	 *
	 * <p><b>이 함수는 회복이다.</b> 피해를 막는 것은 {@link DragonLastStandShield} 이고(2026-10-04,
	 * 클래스 설명), 매 틱 이 값을 쓰는 것은 보호막을 지나친 피해가 있어도 차오르는 선이 흔들리지
	 * 않게 하는 뒷받침으로 남는다.
	 *
	 * <ul>
	 *   <li>하강 구간({@code step < }{@value #DESCEND_TICKS})은 <b>들어온 체력 그대로</b>다.
	 *       내려오는 동안 차오르면 「내려오는 것」과 「증폭되는 것」이 한 덩어리로 읽힌다</li>
	 *   <li>충전 구간은 <b>선형</b>으로 20%p 까지 올린다. 목표값은
	 *       {@code DragonLastStand.healedHealth} 가 정하므로 <b>「30% → 50%」의 유일한 출처가
	 *       그쪽 하나</b>다</li>
	 *   <li>터진 뒤는 목표값에 그대로 머문다</li>
	 * </ul>
	 *
	 * <p>⚠ 내림은 하지 않는다. 들어온 체력보다 낮은 값을 쓰면 <b>우리가 드래곤을 깎는 것</b>이
	 * 된다 — 회복 도중에 최대치를 넘지 않게 자르는 것은 그쪽 함수가 한다.
	 *
	 * @param entryHealth 진입한 틱의 체력
	 * @param max         최대 체력
	 * @param step        연출이 시작된 뒤 흐른 틱
	 */
	static float rampedHealth(float entryHealth, float max, int step) {
		float target = DragonLastStand.healedHealth(entryHealth, max);
		if (step <= DESCEND_TICKS) {
			return entryHealth;
		}
		if (step >= BOOM_AT) {
			return target;
		}
		float span = (float) (step - DESCEND_TICKS) / CHARGE_TICKS;
		return entryHealth + (target - entryHealth) * span;
	}

	/**
	 * 지대 안쪽으로 데려올 수 있는 <b>가장 먼 거리</b>(칸).
	 *
	 * <p>시작 원(반경 42)에서 {@value #WALL_MARGIN} 칸 안쪽이다. 보더 시절에는 정사각형의
	 * <b>내접원</b>이라 이 값이었다 — 모서리 방향으로 반변을 그대로 쓰면 중앙에서 59칸으로
	 * <b>허공</b>이었다. 지대가 원이 된 지금은 같은 값이 곧 「원 안」이다.
	 */
	static double innerEdge() {
		return Math.max(0.0, DragonLastStandZone.START_RADIUS - WALL_MARGIN);
	}

	/**
	 * 정사각형 네 자리 가운데 하나의 <b>상대 좌표</b>.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 넷을 전부 재어 본다 — 「정사각형인가」와
	 * 「지대 안인가」를 숫자로 물을 수 있는 유일한 자리다.
	 */
	static Vec3 beaconOffset(int index) {
		int corner = Math.floorMod(index, BEACON_COUNT);
		double x = (corner == 0 || corner == 3) ? BEACON_HALF_SIDE : -BEACON_HALF_SIDE;
		double z = (corner == 0 || corner == 1) ? BEACON_HALF_SIDE : -BEACON_HALF_SIDE;
		return new Vec3(x, 0.0, z);
	}

	/**
	 * 충전음의 이번 음높이.
	 *
	 * <p>{@code DragonLastStandPatterns.chargePitch} 와 같은 모양이다 — 낮은 데서 시작해 1.0 을
	 * 넘겨야 「올라갔다」로 들린다.
	 */
	static float chargePitch(int step) {
		int span = Math.max(1, CHARGE_TICKS);
		double along = Math.max(0.0, Math.min(1.0, (double) (step - DESCEND_TICKS) / span));
		return (float) (CHARGE_PITCH_LOW + (CHARGE_PITCH_HIGH - CHARGE_PITCH_LOW) * along);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 연출 한 틱. {@code DragonLastStand.tick} 이 연출 구간에만 부른다.
	 *
	 * <p>부르는 쪽이 <b>이 틱에 {@code advance} 와 오브젝트 파도에 닿지 않고 돌아간다.</b> 그것이
	 * 「연출 동안 시간을 재지 않는다」의 마지막 한 줄이다.
	 *
	 * @param anchor      드래곤을 못박아 둘 자리. 지대의 중심이자 정사각형의 가운데다
	 * @param entryHealth 진입한 틱의 드래곤 체력
	 * @param beganAt     최후의 저항이 시작한 틱
	 * @param now         받은 틱. {@code getGameTime} 을 여기서 다시 읽지 않는다
	 */
	static void tick(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members, Vec3 anchor,
			float entryHealth, long beganAt, long now) {
		if (owner != beganAt) {
			// 남의 판이거나 첫 틱이다. 앞 판의 빛이 남아 있으면 여기서 먼저 거둔다.
			dropBeacons();
			owner = beganAt;
		}
		int step = (int) Math.max(0L, now - beganAt);

		// ⚠ 이 한 줄이 「체력이 점점 찬다」다. 되돌릴 것을 한 칸도 남기지 않는다 — 까닭은 클래스
		// 설명의 표에 있다. 피해를 막는 것은 이 줄이 아니라 DragonLastStandShield 다(2026-10-04 —
		// 이 줄만으로는 피해가 끝까지 들어간 뒤 틱 끝에 덮어써져 「맞으면서 회복」으로 보였다).
		dragon.setHealth(rampedHealth(entryHealth, dragon.getMaxHealth(), step));

		if (step == 0) {
			pullInside(end, members, anchor);
		}
		if (step < DESCEND_TICKS) {
			descend(end, dragon, anchor, step);
			return;
		}
		if (step == DESCEND_TICKS) {
			lightBeacons(end, members, anchor);
		}
		// 하강이 끝난 뒤에도 자리를 계속 쓴다. DragonLastStand.hold 가 연출 중에는 좌표를
		// 못박지 않으므로(하강이 순간이동이 되지 않게) 여기가 유일하게 자리를 잡는 자리다.
		TrialCrystalRevive.hold(dragon, anchor);
		if (step < BOOM_AT) {
			charge(end, members, dragon, anchor, step);
			return;
		}
		if (step == BOOM_AT) {
			boom(end, members, dragon, anchor);
		}
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 남은 빛을 거둔다. <b>월드를 만지지 않는다.</b> */
	static void clearState() {
		dropBeacons();
		owner = Long.MIN_VALUE;
	}

	/** 시험이 들여다보는 곳. 지금 신호기 빛이 몇 개 서 있는가. */
	static int beaconCount() {
		return BEACONS.size();
	}

	// ------------------------------------------------------------------ ① 하강

	/**
	 * 드래곤을 중앙으로 당겨 내린다.
	 *
	 * <p>{@code TrialCrystalRevive.hold} 를 <b>그대로 부른다</b> — 부위를 함께 밀고 위치 동기화
	 * 깃발까지 세우는 일이 그 안에 있어서, 여기서 다시 짜면 <b>보이는 곳에 없는데 맞는</b> 한
	 * 틱이 되살아난다(그쪽 설명의 두 줄).
	 *
	 * <p>{@code snapTo} 로 한 번에 옮기지 않는 까닭은 사람이 <b>「내려오면서」</b>라고 했기
	 * 때문이다. 순간이동은 연출이 아니라 사고처럼 보인다({@code TrialCrystalRevive.WATCH_PULL} 의
	 * 같은 문단).
	 */
	private static void descend(ServerLevel end, EnderDragon dragon, Vec3 anchor, int step) {
		Vec3 before = dragon.position();
		TrialCrystalRevive.hold(dragon, anchor);
		if (step % TRAIL_STRIDE == 0) {
			trail(end, before, dragon.position());
		}
	}

	/** 내려온 만큼을 선으로 남긴다. {@code TrialEntrance.trail} 과 같은 문법이다. */
	private static void trail(ServerLevel end, Vec3 from, Vec3 to) {
		for (int index = 0; index < TRAIL_POINTS; index++) {
			double along = (double) (index + 1) / TRAIL_POINTS;
			Vec3 at = from.add(to.subtract(from).scale(along));
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, at.x, at.y, at.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ ② 지대 밖 사람 데려오기

	/**
	 * 지대 밖에 있던 사람을 <b>있던 방향 그대로</b> 안쪽 가장자리로 데려온다.
	 *
	 * <p>판정은 {@link #innerEdge()} 와 <b>같은 원</b>이다 — {@code DragonLastStandZone.outside} 를
	 * 그대로 부른다. 지대 판정과 같은 함수라 「원 모양」이 두 벌로 갈라지지 않는다. 보더 시절에는
	 * 정사각형이라 여기만 「반변 안」으로 따로 쟀다.
	 *
	 * <p>⚠ 문턱이 시작 반경 42 가 아니라 {@link #innerEdge()}(39)다. 경계 바로 안쪽 3칸에 서 있던
	 * 사람도 옮겨지는데, 그 사람은 거의 같은 자리(같은 방향 · 땅이 이어진 39칸 안)로 오므로 손해가
	 * 없고, 문턱을 둘로 두면 「39~42 에 선 사람」이 어느 쪽인지 따로 셈해야 한다.
	 *
	 * <p>관전자는 옮기지 않는다 — 판에 끼어들지 않는 사람이라 밖에 있어도 팀이 손해를 보지
	 * 않는다({@code DragonTrialManager.strayMembers} 와 같은 판단이다).
	 */
	private static void pullInside(ServerLevel end, List<ServerPlayer> members, Vec3 anchor) {
		double edge = innerEdge();
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		int moved = 0;
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			double dx = member.getX() - anchor.x;
			double dz = member.getZ() - anchor.z;
			if (!DragonLastStandZone.outside(dx, dz, edge)) {
				// 이미 안이다. 지대가 원이므로 판정도 원이다(보더 시절에는 「반변 안」이었다).
				continue;
			}
			Vec3 target = pullTarget(end, ground, anchor, dx, dz, edge);
			if (target == null) {
				continue;
			}
			// 엔드 안에서 엔드 안으로다. EndFightTeleportLock 은 목적지가 엔드면 첫 줄에서
			// 통과시키고, 애초에 PositionSwapManager 를 지나는 길만 본다.
			member.teleportTo(end, target.x, target.y, target.z, java.util.Set.of(),
					member.getYRot(), member.getXRot(), false);
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					target.x, target.y + 1.0, target.z, 40, 0.4, 0.8, 0.4, 0.1);
			moved++;
		}
		if (moved > 0) {
			TrialWarning.playEach(end, members, SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.8F);
			SharedFateMod.LOGGER.info("[END] 최후의 저항 진입 — 지대 밖 {}명을 안쪽 가장자리로 데려왔습니다",
					moved);
		}
	}

	/**
	 * 그 사람을 데려올 자리. <b>방향은 그대로, 거리만 줄인다.</b>
	 *
	 * <p>{@code TrialLandingShock.groundedReach} 를 <b>그대로 부른다.</b> 그 함수는 「이 방향으로
	 * 밀 때 땅이 끊기기 전까지 몇 칸인가」를 답하는데, 출발점을 <b>중앙</b>으로 두면 그 답이 곧
	 * <b>「이 방향에서 중앙과 땅으로 이어진 가장 먼 자리」</b>다 — 우리가 묻고 싶은 것과 같은
	 * 물음이라 새 함수를 만들지 않았다.
	 *
	 * <p>출발점이 포디움이라 언제나 땅이고, 답이 0 이면 그 자리가 곧 포디움이다. 그때만
	 * 「완전 중앙」이 되는데, 그것은 <b>중앙 한 칸 밖이 사방으로 허공</b>인 판에서만 나오는 답이라
	 * 일어날 수 없는 경우의 바닥이다.
	 *
	 * @return 데려올 자리. 잴 방향이 없으면 {@code null}
	 */
	private static @Nullable Vec3 pullTarget(ServerLevel end, TrialEnderPulse.Ground ground,
			Vec3 anchor, double dx, double dz, double edge) {
		double from = Math.sqrt(dx * dx + dz * dz);
		if (!(from > 1.0E-4)) {
			// 중앙에 정확히 겹쳐 있다. 「있던 방향」이 없으므로 옮기지 않는다.
			return null;
		}
		double stepX = dx / from;
		double stepZ = dz / from;
		double reach = TrialLandingShock.groundedReach(
				(x, z) -> ground.surfaceAt(end, x, z) != TrialEnderPulse.NO_GROUND,
				anchor.x, anchor.z, stepX, stepZ, edge);
		double x = anchor.x + stepX * reach;
		double z = anchor.z + stepZ * reach;
		int surface = ground.surfaceAt(end, x, z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			// groundedReach 가 땅을 보장하지만, 0 칸이면 포디움 자신이라 그 높이를 쓴다.
			return anchor;
		}
		return new Vec3(x, surface, z);
	}

	// ------------------------------------------------------------------ ③ 신호기 빛과 충전

	/** 보라색 신호기 빛 넷을 켠다. 정사각형이고 가운데가 드래곤이다. */
	private static void lightBeacons(ServerLevel end, List<ServerPlayer> members, Vec3 anchor) {
		dropBeacons();
		// 심지는 남은 연출 길이 + 1초다. 지우는 줄을 아무도 못 지나도 그 뒤에는 반드시 사라진다.
		int fuse = CHARGE_TICKS + SETTLE_TICKS + 20;
		for (int index = 0; index < BEACON_COUNT; index++) {
			Vec3 offset = beaconOffset(index);
			DragonLastStandLights.Glow glow = DragonLastStandLights.raisePillar(end,
					anchor.add(offset), DyeColor.PURPLE, BEACON_HEIGHT, fuse);
			if (glow != null) {
				BEACONS.add(glow);
			}
		}
		// ⚠ block/beacon/activate 다(sounds.json 에서 확인했다). TrialWarning 의 MARK 층이 같은
		// 소리를 음높이 1.4 로 쓰지만, 연출 중에는 예고가 한 발도 안 나가고 여기는 0.7 이라 깊은
		// 울림으로 들린다. 사람이 말한 것이 「신호기빛」이므로 신호기 소리가 맞는 답이다.
		TrialWarning.playEach(end, members, SoundEvents.BEACON_ACTIVATE, 1.0F, 0.7F);
		end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
				anchor.x, anchor.y + 1.0, anchor.z, 80, 3.0, 1.0, 3.0, 0.1);
	}

	/**
	 * 힘이 증폭되는 동안. <b>보스바가 차오르는 것이 곧 연출이다.</b>
	 *
	 * <p>여기서 점을 쓰지 않는다 — 신호기 빛 넷이 이미 서 있고, 그 위에 파티클을 얹으면 보스바
	 * 쪽으로 가야 할 눈길이 바닥으로 내려온다. 1초마다 <b>음높이가 오르는 울림</b>만 얹는다
	 * ({@code block/beacon/ambient}, 이 저장소의 어느 카드도 쓰지 않는 소리다).
	 */
	private static void charge(ServerLevel end, List<ServerPlayer> members, EnderDragon dragon,
			Vec3 anchor, int step) {
		if ((step - DESCEND_TICKS) % CHARGE_HUM_TICKS != 0) {
			return;
		}
		TrialWarning.playEach(end, members, SoundEvents.BEACON_AMBIENT, 0.9F, chargePitch(step));
		// 개수를 세는 형태라 꾸러미 한 장이다 — 점 예산과 무관하다.
		end.sendParticles(ParticleTypes.END_ROD, true, false,
				anchor.x, anchor.y + 2.0, anchor.z, 30, 2.0, 1.5, 2.0, 0.08);
	}

	// ------------------------------------------------------------------ ④ 터짐과 글자

	/**
	 * 드래곤에게 터지는 소리. 그리고 화면에 「최후의 저항」.
	 *
	 * <p>⚠ <b>{@code GENERIC_EXPLODE} 를 고른 것이 함정을 피한 것이다.</b> 사람이 말한 것이
	 * 그냥 <b>「터지는 소리」</b>이므로 일반 폭발음이 맞는 답이고, 이 판에서 그것을 「드래곤 것」
	 * 처럼 보이게 이름 붙인 소리들
	 * ({@code DRAGON_FIREBALL_EXPLODE} · {@code END_GATEWAY_SPAWN} · {@code LIGHTNING_BOLT_IMPACT})은
	 * <b>sounds.json 에서 전부 같은 {@code random/explode1~4} 를 가리킨다.</b> 곧 그쪽을 쓰면
	 * 「드래곤 소리를 골랐다」는 착각만 코드에 남고 들리는 것은 똑같다.
	 *
	 * <p>타이틀은 바닐라 패킷이라 <b>통신 규약이 올라가지 않는다</b>({@code TitleMessenger}).
	 * 이 자리가 {@code Reveal.SILENT} 라 룰렛 화면이 안 뜨므로, 화면 가운데 글자가 이 페이즈에서
	 * 「판이 바뀌었다」를 말하는 <b>유일한 글자</b>다.
	 */
	private static void boom(ServerLevel end, List<ServerPlayer> members, EnderDragon dragon,
			Vec3 anchor) {
		dropBeacons();
		Vec3 at = dragon.isAlive() ? dragon.position() : anchor;
		end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false, at.x, at.y + 2.0, at.z, 1,
				0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.SONIC_BOOM, true, false, at.x, at.y + 2.0, at.z, 1,
				0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.END_ROD, true, false, at.x, at.y + 2.0, at.z, 120,
				3.0, 2.0, 3.0, 0.3);
		TrialWarning.playEach(end, members, SoundEvents.GENERIC_EXPLODE, 1.0F, 0.6F);
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 0.5F);
		TrialWarning.shakeEach(members);
		TitleMessenger.showTitle(members, TITLE, SUBTITLE, 5, 45, 15);
		SharedFateMod.LOGGER.info("[END] 최후의 저항 진입 연출 종료 — 여기서부터 시계가 돕니다");
	}

	private static void dropBeacons() {
		for (DragonLastStandLights.Glow glow : BEACONS) {
			DragonLastStandLights.drop(glow);
		}
		BEACONS.clear();
	}
}
