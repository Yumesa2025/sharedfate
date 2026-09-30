package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 엔드에 들어선 직후의 <b>입장 연출.</b> 룰렛보다 앞에 온다.
 *
 * <h2>사람이 정한 순서</h2>
 *
 * <p>「첫 엔더에 입성 → 크리스탈 전부없다가 10초뒤 엔더드래곤 중앙위에서 생긴다음 크리스탈 부활.
 * 이건 이미만든 크리스탈 부활로 연출하면될거같은데 이 연출도 조금더 화려하게 가져가고싶어」·
 * 「입장연출 10초후 그뒤로 연출끝나면 룰렛시작. 입장연출은 시작하기전에 엔더드래곤 시련 전투
 * 띄워주고」.
 *
 * <pre>
 * 0                            타이틀 「엔더 드래곤 시련 전투」 · 크리스탈 전부 거둔다
 * 0 .. {@value #VANISH_TICKS}      빈 하늘. 중앙 상공으로 고리가 조여든다
 * {@value #VANISH_TICKS}            드래곤을 중앙 상공으로 끌어올리기 시작
 * .. +{@value #PERCH_TICKS}         끌려오는 동안 궤적. 마지막 틱에 착석 연출
 * .. +{@value #REVIVE_SHOW_TICKS}   크리스탈 부활 연출({@link TrialCrystalRevive} 그대로)
 * {@value #CINEMATIC_TICKS}          부활 완료 — 연출이 끝난다
 * .. +{@value #SETTLE_TICKS}        숨 돌리는 틈
 * {@value #ROULETTE_DELAY_TICKS}     입장 룰렛
 * </pre>
 *
 * <h2>상태를 하나도 들지 않는다 — 전부 {@code startedTick} 에서 나온다</h2>
 *
 * <p>이 클래스에는 정적 맵도 깃발도 없다. 지금이 몇 단계인지는
 * {@link DragonTrialSession#startedTick()} 에서 흐른 틱으로만 정해지고, 그 값은 <b>저장된다</b>
 * ({@code DragonTrialStore.Entry.startedTick}). 그래서
 *
 * <ul>
 *   <li>{@code clearState()} 가 <b>필요 없다.</b> 되돌릴 것이 없으므로 빠뜨릴 자리도 없다 —
 *       이 저장소가 실행기마다 정적 맵을 두고 「빠뜨리면 다음 판으로 샌다」를 반복해 적어 둔
 *       바로 그 위험이 여기에는 없다</li>
 *   <li><b>연출 도중 서버가 내려갔다 올라와도 제자리를 찾는다.</b> 3단계 중간이었으면
 *       {@link TrialCrystalRevive} 가 스스로 「이미 끝난 연출」로 보고 곧바로 크리스탈을
 *       세운다(그쪽 클래스 설명의 같은 문단)</li>
 * </ul>
 *
 * <p>딱 하나 남는 상태는 {@link TrialCrystalRevive} 의 것이고, 그쪽은
 * {@code TrialRisks.clearState()} 가 이미 비운다.
 *
 * <h2>드래곤을 「나타나게」 하는 방법 — 페이즈도, 감추기도 쓰지 않는다</h2>
 *
 * <p>드래곤은 원래 처음부터 아레나에 있다. 중앙 상공에 <b>생기게</b> 할 길을 셋 놓고 골랐다.
 *
 * <ul>
 *   <li><b>{@code setPhase}</b> — 쓰지 않는다. {@link TrialCatalog.Risk.DragonFocus} 의 설명에
 *       이 저장소가 그것으로 크게 틀린 기록이 있다(드래곤이 착지를 아예 안 하게 됐다)</li>
 *   <li><b>감췄다 보이기({@code setInvisible})</b> — 쓰지 않는다.
 *       {@code EnderDragonRenderer} 는 {@code LivingEntityRenderer} 가 아니라
 *       {@code EntityRenderer} 를 바로 물려받는다(26.3 클래스 파일로 확인했다). 바닐라에서
 *       투명 깃발을 읽어 렌더를 끊는 자리는 {@code LivingEntityRenderer} 쪽이므로, 이 길은
 *       <b>깃발만 세우고 아무것도 안 사라질 수 있다</b> — 빌드도 로그도 조용한 종류다</li>
 *   <li><b>자리만 옮긴다</b> — 골랐다. {@link TrialCrystalRevive#hold} 를 그대로 쓴다. 매 틱
 *       남은 거리의 20%를 당기는 방식이라 되돌릴 상태가 없고(우리가 멈추면 바닐라 비행이 그대로
 *       이어받는다), 「부활」 카드가 이미 같은 함수로 같은 자리에 드래곤을 세운다 —
 *       <b>두 연출에서 「중앙 상공」이 같은 점이라는 것이 저절로 보장된다.</b></li>
 * </ul>
 *
 * <p>{@link #PERCH_TICKS} 동안 당기므로 순간이동이 아니다({@code WATCH_PULL} 설명의 「1 이면
 * 순간이동이라 연출이 아니라 사고처럼 보인다」). 0.8^40 이면 남은 거리가 만 분의 한 자리라
 * 2초 안에 반드시 자리를 잡는다.
 *
 * <h2>최후의 저항의 페이즈 잠금과 부딪히지 않는다</h2>
 *
 * <p>{@code DragonLastStand} 는 드래곤을 {@code HOVERING} 으로 잠그고 매 틱 좌표를 못박는다.
 * 그런데 그 자리는 <b>체력 30%</b> 이고 이 연출은 <b>입장</b>이다 —
 * {@code DragonTrialManager.tickSessions} 가 최후의 저항이 열린 팀에서 이 파일에 닿기 전에
 * {@code continue} 로 빠지므로 <b>같은 틱에 둘이 도는 길이 없다.</b> 시간으로도 겹치지
 * 않는다(이 연출은 전투가 열린 뒤 {@value #ROULETTE_DELAY_TICKS} 틱 안에 끝나고, 그 안에
 * 체력이 30%까지 내려갈 수는 없다). 그리고 여기서는 <b>페이즈를 한 번도 만지지 않으므로</b>
 * 설령 겹쳐도 잠금을 풀어 놓지 않는다.
 *
 * <h2>크리스탈은 깨뜨리지 않고 거둔다</h2>
 *
 * <p>{@code Entity.RemovalReason.DISCARDED} 는 {@code EndCrystal.hurtServer} 를 지나지 않는다.
 * 깨뜨리면 {@code EnderDragon.onCrystalDestroyed} 가 돌아 드래곤 머리에 폭발 10 이 들어가고
 * <b>페이즈가 움직인다.</b> {@code DragonLastStand.enter} 가 같은 문제를 같은 방법으로 푼다.
 *
 * <h2>거둔 사이에 크리스탈 자리가 잘못 터지지 않는다</h2>
 *
 * <p>「첫 크리스탈」·「크리스탈 전멸」은 <b>크리스탈 수가 줄었는가</b>로 터진다. 우리가 열 개를
 * 한꺼번에 거두면 그 둘이 같은 틱에 터진다 — 팀은 아무것도 하지 않았는데.
 *
 * <p>그래서 {@link #managesCrystals} 가 참인 동안 {@code DragonTrialManager.detectTriggers} 는
 * <b>크리스탈을 세지 않고 기준값도 적지 않는다.</b> 기준값은 연출이 <b>완전히</b> 끝난 뒤에
 * 그때 서 있는 개수로 처음 적힌다. 부활 연출이 <b>시작될 때</b>(2단계 끝) 크리스탈이 이미 서는데도
 * 3단계 내내 참으로 두는 이유가 있다 — 그 구간에 사람이 기둥 위로 올라오면
 * {@code TrialCrystalRevive.prune} 이 그 크리스탈을 거두므로, 거기서 기준값을 적었다면
 * <b>「첫 크리스탈」이 사람 하나가 올라선 것만으로 터진다.</b>
 *
 * <p>연출이 끝나면 크리스탈은 열 개가 살아 있고 무적도 풀려 <b>바닐라와 같은 상태</b>다. 그
 * 뒤로는 두 자리가 평소대로 돈다.
 *
 * <h2>⚠ 크리스탈이 없는 10초 동안 바닐라가 착지를 굴릴 수 있다</h2>
 *
 * <p>26.3 {@code DragonHoldingPatternPhase} 는 원을 <b>한 바퀴 다 돌아 경로가 끝난 틱</b>에
 * 「크리스탈이 하나도 안 남았으면」 착지를 굴린다. 1단계에는 정말로 하나도 없으므로 그 굴림이
 * 나올 수 있다.
 *
 * <p><b>그래도 여기서 막지 않는다.</b> 막는 길은 페이즈를 만지는 것뿐이고 그것은 이 저장소가
 * 금지한 일이다. 대신 이렇게 정리된다.
 *
 * <ul>
 *   <li>한 바퀴가 200틱 안에 끝나고 거기서 굴림까지 맞아야 하므로 <b>자주 일어나지 않는다</b></li>
 *   <li>일어나도 2단계가 드래곤을 중앙 상공으로 <b>당겨 올린다.</b> 당기기가 바닐라 비행을
 *       이기는 것은 {@link TrialCrystalRevive#hold} 에 바이트코드 근거가 적혀 있다</li>
 *   <li>3단계가 끝나면 크리스탈이 열 개 서므로 바닐라 판단이 <b>스스로 제자리로 돌아온다</b></li>
 * </ul>
 *
 * <p>「부활」 카드도 같은 노출을 안고 있다 — 그쪽은 크리스탈이 전부 깨진 뒤에 도는 카드다.
 *
 * <h2>점 예산</h2>
 *
 * <p>이 파일이 한 틱에 쓰는 점은 가장 많을 때 <b>{@value #WORST_TICK_POINTS}</b> 다(2단계 마지막
 * 틱의 착석 고리 {@value #SEAT_RING_POINTS} + 궤적 {@value #TRAIL_POINTS}). 예산 400~440 안이다.
 *
 * <p>3단계는 {@link TrialCrystalRevive} 와 <b>같은 틱을 나눠 쓴다.</b> 그쪽이 자리 열 곳을
 * {@code SEAT_STRIDE} 틱에 나눠 그려 한 틱 128점이고 여기의 고리가 홀수 틱에
 * {@value #HALO_POINTS} 점이라, 두 파일을 합친 가장 바쁜 틱이 156점이다({@link #halo} 참고).
 */
public final class TrialEntrance {

	// ------------------------------------------------------------------ 단계 길이

	/**
	 * 크리스탈이 사라진 채로 비워 두는 시간(틱). <b>10초 — 사람이 정한 값이다.</b>
	 *
	 * <p>「크리스탈 전부없다가 10초뒤」가 그대로 이 숫자다. 줄이거나 늘리지 말 것.
	 */
	static final int VANISH_TICKS = 200;

	/**
	 * 드래곤이 중앙 상공으로 끌려오는 시간(틱). 2초.
	 *
	 * <p>{@link TrialCrystalRevive#hold} 가 매 틱 남은 거리의 20%를 당기므로 40틱이면 남은
	 * 거리가 {@code 0.8^40 ≈ 0.00013} 배다 — 아레나를 가로지르는 80칸에서 출발해도 1cm 안이다.
	 * <b>더 짧게 잡으면 부활 연출이 시작될 때 드래곤이 아직 오는 중</b>이고, 그러면 사람이 정한
	 * 「중앙위에서 생긴<b>다음</b> 크리스탈 부활」 순서가 깨진다.
	 */
	static final int PERCH_TICKS = 40;

	/**
	 * 크리스탈 부활 연출의 길이(틱). 5.5초.
	 *
	 * <p>두 가지를 한꺼번에 정하는 값이다.
	 *
	 * <ul>
	 *   <li><b>「부활」 카드(100틱)보다 길다.</b> 사람이 「이 연출도 조금더 화려하게」라고 했고,
	 *       이 구간은 드래곤이 하늘에 붙들려 있어 <b>길어도 팀이 잃는 것이 없다</b> —
	 *       카드 쪽에서 길이가 곧 「못 때리는 시간」이라는 대가였던 것과 다르다</li>
	 *   <li><b>카드와 값이 달라야 한다.</b> {@link TrialCrystalRevive} 가 이미 발동한 연출을
	 *       기억하는 열쇠는 「받은 틱 + 위험 값」이다. 여기가 카드와 똑같은
	 *       {@code CrystalRevive(10, 100, 0)} 이면 받은 틱까지 겹치는 날 두 연출이 한 번으로
	 *       합쳐진다 — 값을 다르게 두면 그 길이 <b>구조적으로</b> 막힌다</li>
	 * </ul>
	 */
	static final int REVIVE_SHOW_TICKS = 110;

	/**
	 * 연출이 끝나고 룰렛이 뜨기까지 숨 돌리는 틈(틱). 1초.
	 *
	 * <p>부활이 끝나는 틱에는 {@link TrialCrystalRevive} 가 빛과 소리를 한 번 더 낸다. 그 위에
	 * 곧바로 룰렛이 판을 얼리면 「복구 완료」가 화면에서 잘린다.
	 */
	static final int SETTLE_TICKS = 20;

	/** 연출 자체의 길이. 이 틱에 크리스탈 부활이 완료된다. */
	static final int CINEMATIC_TICKS = VANISH_TICKS + PERCH_TICKS + REVIVE_SHOW_TICKS;

	/**
	 * 전투가 열린 뒤 <b>입장 룰렛이 열리기까지</b>의 틱.
	 *
	 * <p>⚠ <b>{@code TrialCatalog.DELAY_SETTLE_TICKS} 가 이 값을 그대로 든다.</b> 곧 이 상수는
	 * 「연출이 끝나는 시각」과 「룰렛이 뜨는 시각」을 <b>한 자리에 묶어 둔</b> 것이다. 사람이
	 * 「연출끝나면 룰렛시작」으로 정했고, 두 값이 따로 적혀 있으면 위의 단계 길이를 하나만
	 * 고쳐도 <b>룰렛이 연출 위에 겹쳐 뜬다</b> — 화면이 뜨면 판이 얼어 연출이 그 자리에서
	 * 멈춘다.
	 *
	 * <p>단계 길이를 고치는 사람은 여기를 따로 고칠 필요가 없다. 대신 <b>전체를 다시 컴파일할
	 * 것</b> — 컴파일 시각 상수라 {@code TrialCatalog} 쪽에 값이 박혀 나간다.
	 */
	public static final int ROULETTE_DELAY_TICKS = CINEMATIC_TICKS + SETTLE_TICKS;

	// ------------------------------------------------------------------ 연출 값

	/** 화면 가운데 큰 글자. 입장 연출이 시작하기 전에 뜬다. */
	private static final Component TITLE = Component.literal("엔더 드래곤 시련 전투");
	private static final Component SUBTITLE = Component.literal("크리스탈이 사라졌습니다");

	/** 1단계에서 고리를 다시 그리는 간격(틱). 매 틱 그릴 필요가 없다. */
	private static final int VANISH_PULSE_TICKS = 5;
	/** 1단계 고리의 점 수. */
	private static final int VANISH_RING_POINTS = 24;
	/** 1단계 고리가 처음 서는 반경(블록). 10초에 걸쳐 {@link #VANISH_RING_END} 까지 조여든다. */
	private static final double VANISH_RING_START = 22.0;
	/** 1단계 고리가 마지막에 닿는 반경(블록). */
	private static final double VANISH_RING_END = 2.5;

	/** 드래곤이 끌려오는 동안 궤적에 찍는 점 수. */
	private static final int TRAIL_POINTS = 12;
	/** 궤적을 그리는 간격(틱). */
	private static final int TRAIL_PULSE_TICKS = 2;

	/** 자리가 열릴 때·드래곤이 앉을 때 그리는 고리의 점 수. */
	private static final int SEAT_RING_POINTS = 40;
	/** 그 고리의 반경(블록). 드래곤 몸(가로 16칸)보다 커야 고리로 읽힌다. */
	private static final double SEAT_RING_RADIUS = 12.0;

	/**
	 * 3단계에서 드래곤을 두르는 고리의 점 수.
	 *
	 * <p>밖으로 연 것은 {@code TrialCrystalReviveTest} 가 <b>같은 틱에 얹히는 합</b>을 예산에
	 * 대고 보기 때문이다. 두 파일이 각자 제 점만 세면 합이 예산을 넘는 것을 아무도 못 본다.
	 */
	static final int HALO_POINTS = 28;
	/** 그 고리의 반경(블록). */
	private static final double HALO_RADIUS = 7.0;
	/** 고리가 한 바퀴 도는 데 걸리는 틱. */
	private static final int HALO_SPIN_TICKS = 60;

	/**
	 * 이 파일이 한 틱에 쓰는 점 수의 상한.
	 *
	 * <p>가장 많은 틱은 2단계의 <b>마지막</b> 틱이다 — 착석 고리와 궤적이 같은 틱에 나간다.
	 * 예산(400~440. {@code TrialEnderPulse.MAX_POINTS_PER_TICK} 설명)의 8분의 1쯤이다.
	 */
	static final int WORST_TICK_POINTS = SEAT_RING_POINTS + TRAIL_POINTS;

	/**
	 * 되살릴 크리스탈 수. 바닐라 기둥 수다.
	 *
	 * <p>자리가 그보다 적으면 있는 만큼만 선다({@code TrialCrystalRevive.pickSeats}).
	 */
	private static final int SEAT_COUNT = 10;

	/**
	 * 3단계에 돌리는 부활 연출.
	 *
	 * <p>회복은 <b>0</b>이다. 「부활」 카드와 같은 판단이고, 여기서는 더 분명하다 — 입장 시점의
	 * 드래곤은 이미 체력이 가득이다.
	 */
	private static final TrialCatalog.Risk.CrystalRevive REVIVE =
			new TrialCatalog.Risk.CrystalRevive(SEAT_COUNT, REVIVE_SHOW_TICKS, 0.0F);

	/**
	 * {@link TrialCrystalRevive} 진입점의 {@code key} 자리에 넘기는 값.
	 *
	 * <p>그쪽은 이 문자열을 쓰지 않는다(겹침 금지 목록을 쓰는 실행기만 쓴다). 그래도 카드
	 * 열쇠와 <b>같은 모양</b>으로 적어 두는 것은 로그를 읽는 사람이 「이건 카드가 아니라 입장
	 * 연출이다」를 바로 알게 하려는 것이다.
	 */
	private static final String REVIVE_KEY = "sharedfate:entrance#0";

	private TrialEntrance() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 입장 연출이 <b>크리스탈을 주관하는 동안</b>인가.
	 *
	 * <p>참이면 {@code DragonTrialManager} 는 크리스탈 수로 자리를 세지 않고 기준값도 적지
	 * 않는다. 까닭은 클래스 설명의 「거둔 사이에 크리스탈 자리가 잘못 터지지 않는다」에 있다.
	 *
	 * <p>연출이 <b>끝나는 틱까지</b> 참이다. 그 틱에 {@link TrialCrystalRevive} 가 무적을 걷으므로,
	 * 다음 틱부터가 「바닐라와 같은 상태」다.
	 */
	public static boolean managesCrystals(@Nullable DragonTrialSession session, long now) {
		if (session == null) {
			return false;
		}
		return TrialRisks.elapsedSinceGrant(now, session.startedTick()) <= CINEMATIC_TICKS;
	}

	/**
	 * 매 틱. {@code DragonTrialManager.tickSessions} 가 룰렛보다 <b>먼저</b> 부른다.
	 *
	 * <p>연출이 끝난 뒤에는 첫 줄에서 곧바로 돌아간다 — 전투 내내 불리는 자리라 그 검사가
	 * 가장 싸야 한다.
	 *
	 * @param now 지금 게임 시각. {@code end.getGameTime()} 을 여기서 다시 묻지 않는다 —
	 *            판이 얼면 분배기가 보는 시계와 어긋난다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, @Nullable DragonTrialSession session, long now) {
		if (end == null || session == null) {
			return;
		}
		long started = session.startedTick();
		long elapsed = TrialRisks.elapsedSinceGrant(now, started);
		if (elapsed > CINEMATIC_TICKS) {
			return;
		}

		if (elapsed < VANISH_TICKS) {
			vanish(end, members, elapsed);
			return;
		}
		if (elapsed < VANISH_TICKS + PERCH_TICKS) {
			perch(end, dragon, members, elapsed - VANISH_TICKS);
			return;
		}
		// 3단계. 크리스탈을 세우고 빔을 긋고 드래곤을 붙들어 두는 일을 「부활」 카드의 실행기가
		// 그대로 한다 — 연출을 여기서 다시 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
		long reviveGranted = started + VANISH_TICKS + PERCH_TICKS;
		TrialCrystalRevive.tick(end, dragon, members, REVIVE_KEY, reviveGranted, now, REVIVE);
		halo(end, dragon, elapsed - (VANISH_TICKS + PERCH_TICKS));
	}

	// ------------------------------------------------------------------ 1단계 — 크리스탈이 사라진다

	/**
	 * 크리스탈을 거두고, 빈 하늘의 중앙 상공으로 고리를 조인다.
	 *
	 * <p>거두는 것은 <b>첫 틱에 한 번뿐</b>이다. 매 틱 훑으면 두 가지가 나빠진다 — 개체 조회가
	 * 200틱 내내 헛돌고, <b>사람이 손에 들고 다니다 놓은 크리스탈까지 먹는다.</b> 그것은 전투
	 * 중에 폭탄으로 쓰는 물건이라 연출이 남의 물건을 지우는 꼴이 된다.
	 */
	private static void vanish(ServerLevel end, @Nullable List<ServerPlayer> members, long step) {
		if (step == 0L) {
			open(end, members);
			return;
		}
		if (step % VANISH_PULSE_TICKS != 0L) {
			return;
		}
		Vec3 perch = TrialCrystalRevive.perchOver(end);
		if (perch == null) {
			return;
		}
		// 반경이 줄어드는 고리 하나. 「곧 저기에 무언가 온다」만 말하면 되고, 그것이 다음 단계에
		// 드래곤이 서는 바로 그 점이다.
		double span = (double) step / VANISH_TICKS;
		double radius = VANISH_RING_START + (VANISH_RING_END - VANISH_RING_START) * span;
		ring(end, perch, radius, VANISH_RING_POINTS, 0.0);
	}

	/**
	 * 연출을 연다 — 타이틀, 크리스탈 거두기, 그 자리마다의 소멸 빛.
	 *
	 * <p>타이틀은 <b>이 자리에만</b> 쓴다. 시련의 다른 알림은 소리와 바닥 표식뿐이고
	 * ({@link TrialWarning} 클래스 설명), 화면 가운데 큰 글자는 「전투가 시작됐다」 하나에만
	 * 값어치가 있다.
	 */
	private static void open(ServerLevel end, @Nullable List<ServerPlayer> members) {
		if (members != null && !members.isEmpty()) {
			TitleMessenger.showTitle(members, TITLE, SUBTITLE, 10, 50, 20);
			TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.6F);
			TrialWarning.shakeEach(members);
		}
		int taken = 0;
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			Vec3 at = crystal.position();
			// 깨뜨리지 않고 거둔다. 깨면 드래곤 머리에 폭발 10 이 들어가고 페이즈가 움직인다 —
			// 클래스 설명의 「크리스탈은 깨뜨리지 않고 거둔다」를 볼 것.
			crystal.setBeamTarget(null);
			crystal.remove(Entity.RemovalReason.DISCARDED);
			// 폭발 파티클은 쓰지 않는다. 「터졌다」로 읽히면 팀이 자기가 깬 줄 안다.
			end.sendParticles(ParticleTypes.PORTAL, true, false, at.x, at.y, at.z, 60,
					0.6, 0.9, 0.6, 0.12);
			end.sendParticles(ParticleTypes.SCULK_SOUL, true, false, at.x, at.y, at.z, 12,
					0.3, 0.5, 0.3, 0.02);
			taken++;
		}
		SharedFateMod.LOGGER.info("[END] 입장 연출 — 크리스탈 {}개를 거뒀습니다. {}틱 뒤 룰렛",
				taken, ROULETTE_DELAY_TICKS);
	}

	// ------------------------------------------------------------------ 2단계 — 드래곤이 자리를 잡는다

	/**
	 * 드래곤을 중앙 상공으로 끌어올린다.
	 *
	 * <p>{@link TrialCrystalRevive#hold} 를 쓴다 — 부위를 함께 밀고 위치 동기화 깃발까지 세우는
	 * 일이 그 안에 있어서, 여기서 다시 짜면 <b>보이는 곳에 없는데 맞는</b> 한 틱이 되살아난다.
	 */
	private static void perch(ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long step) {
		Vec3 perch = TrialCrystalRevive.perchOver(end);
		if (perch == null) {
			return;
		}
		if (step == 0L) {
			// 자리가 열린다. 드래곤은 아직 오는 중이고, 이 고리가 「저기로 온다」를 말한다.
			ring(end, perch, SEAT_RING_RADIUS, SEAT_RING_POINTS, 0.0);
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					perch.x, perch.y, perch.z, 120, 2.0, 1.5, 2.0, 0.25);
			TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 0.7F);
		}
		if (dragon != null && dragon.isAlive()) {
			Vec3 before = dragon.position();
			TrialCrystalRevive.hold(dragon, perch);
			if (step % TRAIL_PULSE_TICKS == 0L) {
				trail(end, before, dragon.position());
			}
		}
		if (step == PERCH_TICKS - 1L) {
			// 앉았다. 여기서 한 번 크게 터뜨려야 「생겼다」가 된다.
			ring(end, perch, SEAT_RING_RADIUS, SEAT_RING_POINTS, Math.PI / SEAT_RING_POINTS);
			// 셋 다 개수를 세는 형태라 꾸러미 세 장이다 — 점 예산과 무관하다.
			//
			// ⚠ FLASH 와 DRAGON_BREATH 는 쓰지 않는다. 26.3 에서 그 둘은 SimpleParticleType 이
			// 아니라 ParticleType<ColorParticleOption> · ParticleType<PowerParticleOption> 이라
			// 옵션 객체를 만들어 넘겨야 한다. 여기서 필요한 것은 「크게 한 번」이고 그것은
			// SONIC_BOOM 하나로 충분하다.
			end.sendParticles(ParticleTypes.SONIC_BOOM, true, false, perch.x, perch.y, perch.z, 1,
					0.0, 0.0, 0.0, 0.0);
			end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false,
					perch.x, perch.y, perch.z, 1, 0.0, 0.0, 0.0, 0.0);
			end.sendParticles(ParticleTypes.END_ROD, true, false,
					perch.x, perch.y, perch.z, 80, 2.5, 1.0, 2.5, 0.1);
			TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.2F, 0.5F);
			TrialWarning.shakeEach(members);
		}
	}

	/** 끌려온 만큼을 선으로 남긴다. 한 틱에 움직인 거리가 곧 선의 길이다. */
	private static void trail(ServerLevel end, Vec3 from, Vec3 to) {
		for (int index = 0; index < TRAIL_POINTS; index++) {
			double along = (double) (index + 1) / TRAIL_POINTS;
			Vec3 at = from.add(to.subtract(from).scale(along));
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, at.x, at.y, at.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 3단계에 덧붙이는 것

	/**
	 * 부활 연출이 도는 동안 드래곤을 두르는 고리.
	 *
	 * <p>「이 연출도 조금더 화려하게」에 대해 <b>거의 점을 더 쓰지 않고</b> 답하는 자리다.
	 * <b>홀수 틱에만</b> 그리므로 한 틱에 {@value #HALO_POINTS} 점이고, 이 고리도 먼지라 수명이
	 * 최소 8틱이어서 2틱마다 덧칠하면 끊겨 보이지 않는다.
	 *
	 * <p>⚠ <b>예전에 적혀 있던 「부활 연출은 짝수 틱에만 그리므로 겹치지 않는다」는 이제 사실이
	 * 아니다.</b> {@link TrialCrystalRevive} 가 640점짜리 4틱 주기를 버리고 자리를 매 틱 나눠
	 * 그리게 바뀌어({@code TrialCrystalRevive.SEAT_STRIDE}) <b>모든 틱에</b> 점을 낸다. 그래도
	 * 합이 예산 안인 것은 그쪽이 128점으로 내려갔기 때문이다 — 둘을 합친 가장 바쁜 틱이
	 * {@code 128 + }{@value #HALO_POINTS} 다.
	 *
	 * @param step 부활 연출이 시작된 뒤 흐른 틱
	 */
	private static void halo(ServerLevel end, @Nullable EnderDragon dragon, long step) {
		if (dragon == null || !dragon.isAlive() || step % 2L == 0L) {
			return;
		}
		double spin = (Math.PI * 2.0 * (step % HALO_SPIN_TICKS)) / HALO_SPIN_TICKS;
		ring(end, dragon.position(), HALO_RADIUS, HALO_POINTS, spin);
	}

	// ------------------------------------------------------------------ 그리기

	/**
	 * 수평 고리 하나.
	 *
	 * <p>긴 형식으로 보낸다. 첫 {@code boolean} 을 끄면 <b>발생 지점에서 32칸 안</b>에만 나가는데
	 * 이 연출은 처음부터 끝까지 그보다 높다({@link TrialWarning} 클래스 설명의 「거리 제한을 끄고
	 * 보낸다」).
	 *
	 * <p>색은 {@code MARKED}(보라)다. 규약에서 「너 하나를 노린다」인데 이 고리는 <b>피해를 예고하지
	 * 않으므로</b> 규약을 쓰는 것이 아니라 <b>엔드의 색</b>으로 쓴다 — 빨강·노랑·파랑은 각각
	 * 「죽는다」·「번개」·「밀려난다」라는 뜻을 이미 배운 색이라 여기에 쓰면 팀이 피할 곳을 찾는다.
	 */
	private static void ring(ServerLevel end, Vec3 center, double radius, int points,
			double offset) {
		if (points <= 0 || !(radius > 0.0)) {
			return;
		}
		for (int index = 0; index < points; index++) {
			double angle = offset + (Math.PI * 2.0 * index) / points;
			end.sendParticles(TrialWarning.dust(TrialWarning.Colors.MARKED), true, false,
					center.x + Math.cos(angle) * radius,
					center.y,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}
