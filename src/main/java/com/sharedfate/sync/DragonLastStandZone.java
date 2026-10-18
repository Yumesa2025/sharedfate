package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「최후의 저항」의 <b>안전지대</b>. <b>엔드 월드 보더</b>다 — 넘어갈 수 없고, 밖 피해는 없다.
 *
 * <h2>패턴이 아니라 혼자 도는 시계다</h2>
 *
 * <p>{@link DragonLastStand.Pattern} 처럼 뽑히지 않는다. 진입과 동시에 시작해 저 혼자
 * <b>45초 → 45초 → 25초</b> 세 걸음으로 좁아지고, 마지막이 <b>반변 18칸</b>이다. 그래서
 * {@link DragonLastStand} 는 이 파일에 매 틱 {@link #tick} 을 넘기기만 하고, 고르는 쪽은
 * {@link #clock} 이 돌려주는 답 두 개만 본다.
 *
 * <h2>⚠⚠ 2026-10-18 — 사람 지시로 다시 월드 보더다. 밖 피해 · 심장 박동 · 빨간 기둥 벽을 걷었다</h2>
 *
 * <p>사람 말: <b>「월드보더로 아예 못 넘어가게, 넘어가서 딜 입히는 건 폐기」</b> · <b>「안전지대는
 * 42-32-22-18 로 변경」</b>. 보더를 세우고 줄이고 되돌리는 것은 {@link DragonTrialBorder} 이고(전투가
 * 열리면 섬 가장자리에 서 있다가 진입 틱에 42 로), 여기는 <b>언제 몇으로</b>만 정한다({@link #driveBorder}).
 * 반경은 이제 <b>보더의 반변</b>(한 변의 절반)이다. 줄어드는 보더가 사람을 지나치면 피해 없이 안쪽으로
 * 옮긴다({@link #keepInside}) — ⚠ <b>벽이 움직이는 동안만</b>이다(바로 아래). 아래 2026-10-04 문단은 그때의
 * 기록으로 남긴다 — 그때 버린 까닭은 밖 피해가 붙은 「갇힘」이었고, 이번에는 밖 피해를 함께 걷었다.
 *
 * <h2>⚠ 2026-10-18 오후 — 옮기는 것은 <b>벽이 움직이는 동안만</b>이다. 멈춘 보더는 벽일 뿐이다</h2>
 *
 * <p>사람 말: <b>「보더 닿아도 순간이동하는 건 없애. 그냥 벽 역할만 하면 돼서」</b>. 0.41.0 의 {@link #keepInside}
 * 는 <b>매 틱</b> 「몸이 벽에 걸렸거나 밖인가」({@link #crossed})를 물어서, 보더가 멈춰 있을 때도 벽에 기대거나
 * 날갯짓에 벽으로 밀린 사람을 안쪽으로 옮겼다 — 사람이 겪은 「보더 닿아도 순간이동」이 그것이다. 지금은
 * {@link #wallMoving} 이 참인 틱(각 걸음의 축소 5초 · 진입 때 105 → 42 로 들어오는 2초, <b>끝나는 틱까지</b>)에만
 * 옮긴다. 줄어드는 벽이 사람을 지나치는 것은 그 구간에만 일어나므로 「벽에 지나쳐져 밖에 남는」 일은 그대로
 * 막고, 멈춘 벽에 닿은 사람은 바닐라처럼 막히기만 한다.
 *
 * <p>⚠ <b>2026-10-18 밤 — 멈춘 벽 <u>너머</u>에 선 사람은 옮긴다</b>({@link #beyond}). 위처럼 좁히자 이미 밖에
 * 선 사람(혼자 끊었다가 보더가 줄어든 뒤 접속 · 진주로 벽에 걸친 뒤 걸어 나감)이 끝까지 밖에 남아 번개 · 비 ·
 * 브레스를 피하며 활을 쏠 수 있었다. 몸이 벽에 걸친 사람은 그대로 두고 <b>중심이 완전히 밖</b>인 사람만 벽이
 * 멈춰 있어도 옮긴다.
 *
 * <h2>2026-10-04 — 월드 보더를 버리고 「나갈 수 있는 원」으로 바꿨었다(지금은 아니다)</h2>
 *
 * <p>사람 말: <b>「안전지대가 사실상 보더라 나갈 수가 없어. 이거 좀 이상함」</b>. 고른 선택지가
 * <b>「나갈 수 있는 원으로」</b>다 — 보더를 쓰지 않고 원(입자 벽)만 그린다. 밖으로 걸어 나갈 수 있고,
 * 나가 있는 동안 피해를 받는다(배틀로얄 자기장 방식).
 *
 * <p>전에는 엔드 월드 보더를 반경 42 → 32 → 22 → 12 로 줄였다. 바닐라 보더는 <b>안에 있는 사람이
 * 밖으로 나가는 것을 막는 벽</b>이라(26.3 {@code Entity.collide} 가 「안이고 벽에서 2칸 안」이면
 * 충돌 형상을 얹는다) 「밖」이 축소가 사람을 지나쳐 간 순간 말고는 생기지 않았다 — 밖 피해가
 * 있으나 마나였고, 사람 눈에는 그냥 「갇혔다」였다.
 *
 * <p>그때는 판정({@code outside} 원) · 빨간 입자 기둥 벽({@code DragonLastStandZoneWall}) · 밖에 있는
 * 사람의 심장 박동과 초당 8 이 있었다. 2026-10-18 에 전부 걷었고, 그때 「보더를 되돌리는 안전장치를 두지
 * 않는다」고 적은 근거(남은 보더를 가려낼 수 없다)는 {@link DragonTrialBorder#looksOurs} 의 지문으로
 * 바꿨다(사람이 2026-10-18 에 고른 것 — 운영자가 손으로 정한 보더는 안 건드린다).
 *
 * <h2>⚠ 서버를 껐다 켜면 시계가 처음부터 다시 돈다</h2>
 *
 * <p>{@code DragonLastStand.resume} 이 새 {@code Stand} 를 만들며 {@code beganAt} 을 <b>지금</b>
 * 으로 잡으므로 지대도 반경 42 에서 다시 시작한다. 쉬는 시계가 같은 이유로 그렇게 되어 있고
 * (그쪽 설명의 「몇 초 쉬고 있었는지는 저장되지 않았다」), 여기서도 <b>짐작해 복원하는 것보다
 * 한 번 더 주는 편</b>을 골랐다.
 *
 * <p>대가는 운영자가 서버를 다시 켜면 115초가 되살아난다는 것이다. 막으려면 {@code beganAt} 을
 * {@code DragonTrialStore} 에 저장해야 하는데, 그러면 저장 파일의 모양이 바뀐다 — 필요해지면
 * 그때 하고, 지금은 이 문단이 그 사실의 유일한 기록이다. 보더는 종료 직전에 되돌리고
 * ({@link DragonTrialBorder#onServerStopping}) 다시 뜬 판의 틱이 반변 42 로 다시 세운다.
 */
public final class DragonLastStandZone {

	// ------------------------------------------------------------------ 크기와 시간

	/**
	 * 시작 반경(칸). <b>기둥 줄과 같은 42 다.</b>
	 *
	 * <h2>왜 42 인가 — 새 숫자를 만들지 않았다</h2>
	 *
	 * <p>이 저장소가 이미 「섬 끝」으로 쓰는 값이다. 「엔더 파동」이 중앙에서 <b>42칸(기둥)</b>
	 * 까지 고리를 퍼뜨리고, 문서가 여러 곳에서 「섬 반경 40칸, 기둥 42칸」이라고 적는다.
	 *
	 * <ol>
	 *   <li><b>진입하는 순간 섬 위의 사람은 거의 다 안이다.</b> 밖에 있던 사람은 진입 연출이 반경 39
	 *       안으로 데려온다({@code DragonLastStandEntry.pullInside}). 보더가 그 사람을 끼우지 않는다</li>
	 *   <li><b>첫 벽이 기둥 줄에 선다.</b> 사람이 「어디까지가 안인가」를 기둥으로 읽는다.
	 *       반대로 44~45 로 잡으면 첫 벽이 통째로 허공 위에 서서 <b>첫 45초가 아무 일도 안 한다</b></li>
	 *   <li>⚠ 2026-10-04 까지는 「42 − 12 = 30 이 세 번에 10칸씩」이 셋째 근거였다. 2026-10-18 에 사람이
	 *       끝값을 18 로 고쳐 마지막 걸음이 4칸이다 — {@link #RADII} 를 볼 것</li>
	 * </ol>
	 *
	 * <p>⚠ {@code TrialRisks.ARENA_RADIUS}(40)를 쓰지 않은 것은 <b>그쪽이 「자리를 고르는
	 * 반경」</b>이기 때문이다. 위험 지점은 섬 위에 떨어져야 하므로 40 이 맞고, 안전지대는
	 * 「처음에는 아무도 밖이 아니어야」 하므로 42 가 맞다.
	 *
	 * <p>2026-10-18 부터 이 값은 엔드 월드 보더의 <b>반변</b>이다(한 변 84). 사각형이라 모서리 방향은
	 * 59칸까지 열린다.
	 */
	static final double START_RADIUS = 42.0;

	/**
	 * 걸음마다의 반경(칸). {@code [시작, 1축소 뒤, 2축소 뒤, 3축소 뒤]}.
	 *
	 * <p><b>42 · 32 · 22 · 18 은 사람이 정한 값</b>이다(2026-10-18 「안전지대는 42-32-22-18 로 변경」 —
	 * 전에는 끝값이 12 였다). 반경은 엔드 월드 보더의 <b>반변</b>이다.
	 *
	 * <p>한 걸음 10칸을 {@link #SHRINK_TICKS}(5초)에 좁히므로 <b>초당 2칸</b>이다(마지막 4칸은 초당
	 * 0.8칸). 걷는 속도가 초당 4.3칸이라 <b>벽을 등지고 걸어도 따라잡히지 않는다.</b> 따라잡혀도 피해 없이
	 * 안쪽으로 옮겨진다({@link #keepInside} — 벽이 움직이는 동안만).
	 *
	 * <p>상시 번개는 이 끝값의 <b>내접원</b>으로 재므로(사각형 안의 원) 18 이 되며 그쪽 자리도 함께
	 * 넓어졌다. 오브젝트 파도 고리와 십자 균열의 셈은 사람이 옛 12 에 묶었다({@link #PATTERN_CORE_RADIUS}).
	 */
	static final double[] RADII = {START_RADIUS, 32.0, 22.0, 18.0};

	/**
	 * 패턴이 재는 <b>중심 원</b>의 반경(칸) — 안전지대 끝값이 12 이던 때의 값을 <b>일부러 묶어 둔</b> 것이다.
	 *
	 * <p>2026-10-18 에 끝값이 12 → 18 이 되자 「마지막 지대 끝값」에서 뽑던 셋이 따라 움직였다. 사람이 고른
	 * 답(B): <b>상시 번개 자리만 18 을 따라 넓히고</b>({@code DragonLastStandPatterns.LIGHTNING_FIELD_RADIUS}),
	 * <b>회복 크리스탈 고리</b>({@code DragonLastStandObjects.RING_RADIUS} — 날개가 가장 센 4~12칸 안이 의도)와
	 * <b>십자 균열의 「마지막 지대에서도 피할 곳」 셈</b>은 이 값에 묶는다. 끝값 18 보다 작으므로 이 원 안은
	 * 언제나 지대 안이다.
	 */
	static final double PATTERN_CORE_RADIUS = 12.0;

	/**
	 * 축소가 <b>끝나는</b> 시각(진입부터의 틱). 45초 · 45초 · 25초.
	 *
	 * <p>마지막 값 2300 이 곧 <b>115초</b>이고, 문서의 「축소가 다 끝난 115초 시점」이 이 숫자다.
	 * 45 + 45 + 25 = 115 가 우연이 아니라 이 표의 뜻이다.
	 */
	static final long[] LEG_END_TICKS = {900L, 1800L, 2300L};

	/**
	 * 한 번의 축소에 걸리는 시간(틱). 5초.
	 *
	 * <h2>45/45/25 를 「내내 줄어든다」로 읽으면 브레스가 영영 안 나온다</h2>
	 *
	 * <p>세 걸음의 길이를 축소 애니메이션의 길이로 읽으면 <b>115초 내내 줄어드는 중</b>이 되고,
	 * 그러면 {@code DragonLastStand.breathOpen} 의 「축소 중 브레스 금지」가 <b>언제나</b> 참이라
	 * 부채꼴 브레스가 한 번도 안 나온다. 이 페이즈의 대표 패턴이 그렇게 사라지면 제한 넷이
	 * 서로를 지운 것이다.
	 *
	 * <p>그래서 한 걸음은 <b>기다렸다가 마지막 5초에 좁힌다</b>. 축소는 각 걸음의 <b>끝</b>에
	 * 붙는다 — 시작에 붙이면 마지막 축소가 1900틱에 끝나 「115초」가 거짓이 된다.
	 */
	static final long SHRINK_TICKS = 100L;

	/**
	 * ⚠ 축소 <b>전에</b> 미리 브레스를 잠그는 시간(틱).
	 *
	 * <h2>이 값이 없으면 「축소와 브레스 동시 실행 금지」가 지켜지지 않는다</h2>
	 *
	 * <p>{@code DragonLastStand.allowed} 는 <b>고르는 그 틱</b>만 본다. 부채꼴 브레스는
	 * {@code Pattern.CONE_BREATH.durationTicks()} 만큼 도는데, 축소 1틱 전에 골라도 그 뒤로
	 * 계속 돌아 <b>실제로는 겹친다.</b> 고르는 쪽을 고치라는 것이 아니라(그쪽은 이미 완성돼
	 * 있다) 이 시계가 <b>「브레스 한 판이 들어갈 자리가 남았는가」</b>를 답해야 한다.
	 *
	 * <p>그래서 {@link #clock} 의 {@code shrinking} 은 「지금 줄어드는 중」보다 넓다 —
	 * <b>줄어드는 중이거나, 브레스 한 판을 마칠 시간이 남지 않았다.</b> 값이 브레스 길이와
	 * 같아야 하므로 {@code Pattern.CONE_BREATH} 에서 직접 읽는다. 브레스를 길게 고치는 사람이
	 * 이 값을 따로 고칠 일이 없다.
	 */
	static long shrinkLockoutLead() {
		return DragonLastStand.Pattern.CONE_BREATH.durationTicks();
	}

	/**
	 * 축소가 다 끝나는 시각(틱). 115초. {@link #LEG_END_TICKS} 의 마지막과 <b>같은 값</b>이다.
	 *
	 * <p>⚠ 이름은 옛것이다 — 2026-10-18 까지 이 시각부터 밖 피해가 올랐다. 밖 피해는 걷었지만
	 * {@code DragonLastStandObjects.FUSE_TICKS} 가 「축소가 다 끝나는 시각」으로 이 값을 읽으므로 남긴다.
	 */
	static long escalationStartTicks() {
		return LEG_END_TICKS[LEG_END_TICKS.length - 1];
	}

	// ------------------------------------------------------------------ 상태

	/** 지금 몰고 있는 최후의 저항의 시계 원점. 바뀌면 처음부터 다시 세운다. */
	private static long drivingSince = Long.MIN_VALUE;

	private DragonLastStandZone() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 걸음 {@code leg} 의 축소가 <b>시작</b>하는 시각(진입부터의 틱).
	 *
	 * <p>축소는 걸음의 끝에 붙는다({@link #SHRINK_TICKS} 참고).
	 */
	static long shrinkBeginsAt(int leg) {
		return LEG_END_TICKS[leg] - SHRINK_TICKS;
	}

	/**
	 * 지금 안전지대 시계. {@code DragonLastStand.zoneClock} 이 그대로 돌려준다.
	 *
	 * <p>⚠ {@code shrinking} 은 「지금 줄어드는 중」보다 넓다 — <b>브레스 한 판이 들어갈 자리가
	 * 남지 않았다</b>까지다. 까닭은 {@link #shrinkLockoutLead} 에 있다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 115초를 통째로 훑는다.
	 *
	 * @param beganAt 최후의 저항이 시작한 틱
	 * @param now     받은 틱. {@code getGameTime} 을 여기서 다시 읽지 않는다
	 */
	static DragonLastStand.ZoneClock clock(long beganAt, long now) {
		long elapsed = now - beganAt;
		if (elapsed < 0L) {
			// 되감긴 판이다. 축소가 시작되지도 않았으므로 「없다」가 맞다.
			return DragonLastStand.ZoneClock.IDLE;
		}
		boolean shrinking = false;
		long lastEnded = Long.MIN_VALUE;
		long lead = shrinkLockoutLead();
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			long begins = shrinkBeginsAt(leg);
			long ends = LEG_END_TICKS[leg];
			if (elapsed >= begins - lead && elapsed < ends) {
				shrinking = true;
			}
			if (elapsed >= ends) {
				lastEnded = beganAt + ends;
			}
		}
		return new DragonLastStand.ZoneClock(shrinking, lastEnded);
	}

	/**
	 * 이 틱의 반경(칸) — <b>보더의 반변</b>이다. 축소 중이면 사이값이다.
	 *
	 * <p>보더는 {@link DragonTrialBorder#shrink} 가 바닐라 {@code lerpSizeBetween} 으로 줄이므로 실제 벽은
	 * 바닐라가 움직인다. 이 함수는 <b>언제 몇으로</b>를 정하는 시간표이고, HUD({@link #timer})·시험·
	 * {@link #driveBorder} 가 함께 본다.
	 */
	static double radiusAt(long elapsed) {
		if (elapsed < 0L) {
			return RADII[0];
		}
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			long begins = shrinkBeginsAt(leg);
			if (elapsed < begins) {
				return RADII[leg];
			}
			if (elapsed < LEG_END_TICKS[leg]) {
				double progress = (double) (elapsed - begins) / SHRINK_TICKS;
				return RADII[leg] + (RADII[leg + 1] - RADII[leg]) * progress;
			}
		}
		return RADII[RADII.length - 1];
	}

	/**
	 * 지대 중심에서 {@code (dx, dz)} 만큼 떨어진 자리가 반경 {@code radius} 의 <b>원</b> 밖인가.
	 *
	 * <p>⚠ 2026-10-18 부터 지대 자신은 네모(보더)다. 이 원 판정은 <b>진입 데려오기</b>
	 * ({@code DragonLastStandEntry.pullInside}, 반경 39 원)만 쓴다 — 원 안이면 반변 42 네모 안이기도
	 * 하므로 데려온 사람은 보더 안이다. 수평 거리가 반경보다 크면 밖이고, 경계 위는 안이다.
	 */
	static boolean outside(double dx, double dz, double radius) {
		return dx * dx + dz * dz > radius * radius;
	}

	/**
	 * 다음 축소 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다. <b>상태를 하나도 쓰지 않는다.</b>
	 *
	 * <p>{@link #radiusAt} 과 같은 구간을 훑는다. 축소가 시작되기 전이면 시작까지(「안전지대 → 32칸」),
	 * 줄어드는 5초({@link #SHRINK_TICKS}) 동안은 다 줄 때까지(「축소 중」)다. 진입 연출 동안은 시계
	 * 원점이 미래라 경과가 음수이고, 그만큼 첫 축소가 멀다 — 사람이 「그땐 시간 재지말고」라고 한
	 * 것이 그대로 숫자에 실린다.
	 *
	 * @param clockBase 최후의 저항의 시계 원점
	 * @return 마지막 축소(반변 18)까지 끝났으면 {@code null}
	 */
	static @Nullable TrialTimers.ZoneTimer timer(long clockBase, long now) {
		long elapsed = now - clockBase;
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			long begins = shrinkBeginsAt(leg);
			long ends = LEG_END_TICKS[leg];
			if (elapsed < begins) {
				long from = leg == 0 ? 0L : LEG_END_TICKS[leg - 1];
				long remaining = begins - elapsed;
				return new TrialTimers.ZoneTimer(false, remaining, Math.max(remaining, begins - from),
						RADII[leg + 1]);
			}
			if (elapsed < ends) {
				return new TrialTimers.ZoneTimer(true, ends - elapsed, SHRINK_TICKS, RADII[leg + 1]);
			}
		}
		return null;
	}

	/** 진입 때 섬 가장자리에서 42 로 들어오는 데 걸리는 틱. 드래곤 하강과 같은 2초다. */
	static final long ENTRY_SHRINK_TICKS = DragonLastStandEntry.DESCEND_TICKS;

	/**
	 * 진입 연출 동안의 반변 — <b>섬 가장자리에서 42 로</b> 들어온다.
	 *
	 * <p>사람이 정한 순서가 「내려오면서 … 보더가 생기면서 … 밖에 잇으면 안으로 데려오되」다. 진입 틱에
	 * 반경 39 원 밖 사람은 이미 안으로 옮겨졌으므로({@code DragonLastStandEntry.pullInside}) 줄어드는 벽이
	 * 사람을 끼우지 않는다. 드래곤이 내려오는 {@value #ENTRY_SHRINK_TICKS}틱 동안 함께 들어와
	 * 「하강 = 벽이 닫힌다」가 한 덩어리로 읽힌다. 그 뒤로는 42 에 서 있다.
	 *
	 * @param step 진입부터 흐른 틱({@code elapsed + LENGTH_TICKS})
	 */
	static double entryHalfAt(long step) {
		if (step <= 0L) {
			return DragonTrialBorder.ISLAND_HALF_SIDE;
		}
		if (step >= ENTRY_SHRINK_TICKS) {
			return START_RADIUS;
		}
		double progress = (double) step / ENTRY_SHRINK_TICKS;
		return DragonTrialBorder.ISLAND_HALF_SIDE
				+ (START_RADIUS - DragonTrialBorder.ISLAND_HALF_SIDE) * progress;
	}

	/**
	 * 이 틱에 <b>벽이 가 닿을</b> 반변 — 줄어드는 중이면 그 걸음의 끝값, 아니면 지금 값.
	 *
	 * <p>{@link #keepInside} 가 사람을 내려놓는 기준이다. 지금 벽 바로 안에 내려놓으면 축소가 그 사람을
	 * 다시 지나쳐 5초 동안 몇 번이고 옮겨진다 — 끝값 안쪽에 두면 <b>한 걸음에 한 번</b>이다.
	 */
	static double settledRadiusAt(long elapsed) {
		if (elapsed < 0L) {
			return RADII[0];
		}
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			if (elapsed < shrinkBeginsAt(leg)) {
				return RADII[leg];
			}
			if (elapsed < LEG_END_TICKS[leg]) {
				return RADII[leg + 1];
			}
		}
		return RADII[RADII.length - 1];
	}

	/**
	 * ⚠ <b>벽이 움직이는 틱인가</b> — {@link #keepInside} 를 돌리는 유일한 조건이다(2026-10-18 오후).
	 *
	 * <ul>
	 *   <li><b>진입 연출</b>({@code elapsed < 0}) — 진입부터 {@value #ENTRY_SHRINK_TICKS}틱(섬 가장자리 105 →
	 *       42) 동안. 진입 틱은 {@code elapsed + DragonLastStandEntry.LENGTH_TICKS = 0} 이다</li>
	 *   <li><b>각 걸음</b> — 축소가 시작하는 틱({@link #shrinkBeginsAt})부터 끝나는 틱({@link #LEG_END_TICKS})까지</li>
	 * </ul>
	 *
	 * <p>⚠ <b>끝나는 틱을 넣는다.</b> 그 틱에 {@link #driveBorder} 가 끝값으로 세우므로 바로 앞 틱에 지나쳐진
	 * 사람은 그 틱에야 「걸렸다」로 읽힌다 — 빼면 마지막에 지나쳐진 사람이 밖에 남는다. 그 다음 틱부터는 멈춘
	 * 벽이라 닿아도 안 옮긴다.
	 *
	 * <p>바닐라가 실제로 줄이고 있는지({@code getLerpTime})가 아니라 <b>시간표</b>로 묻는 것은 시험이 월드 없이
	 * 붙들 수 있게 하려는 것이다. 보더는 그 시간표대로 {@link #driveBorder} 가 몬다.
	 */
	static boolean wallMoving(long elapsed) {
		if (elapsed < 0L) {
			long step = elapsed + DragonLastStandEntry.LENGTH_TICKS;
			return step >= 0L && step <= ENTRY_SHRINK_TICKS;
		}
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			if (elapsed >= shrinkBeginsAt(leg) && elapsed <= LEG_END_TICKS[leg]) {
				return true;
			}
		}
		return false;
	}

	/** 사람 몸의 가로 반폭(칸). 바닐라 사람 상자가 0.6 이다. */
	static final double BODY_HALF_WIDTH = 0.3;

	/**
	 * 보더를 지나친 사람으로 보는가 — 몸이 벽에 걸렸거나 밖이다.
	 *
	 * <p><b>네모</b>다. 보더가 네모이므로 판정도 두 축 가운데 먼 쪽이다. 걸친 사람까지 넣는 것은 바닐라
	 * 보더 충돌이 「안에 있고 벽 가까이」일 때만 막기 때문이다 — 줄어드는 벽이 몸에 닿는 순간부터는
	 * 막지 않아 그 사람은 사실상 밖이다.
	 */
	static boolean crossed(double dx, double dz, double half) {
		return Math.max(Math.abs(dx), Math.abs(dz)) + BODY_HALF_WIDTH > half;
	}

	/**
	 * ⚠ <b>멈춘 벽 너머에 선 사람인가</b> — 몸 <b>중심</b>이 벽 밖이다. 벽이 멈춰 있을 때 옮기는 유일한 조건이다
	 * (2026-10-18 밤, 공개 배포 직전 검토).
	 *
	 * <p>오후에 「멈춘 보더는 벽일 뿐」으로 바꾸면서 옮기는 것을 벽이 움직이는 틱({@link #wallMoving})으로 좁혔는데,
	 * 그러자 <b>벽 너머에 이미 서 있는 사람</b>이 끝까지 밖에 남았다. 그 사람은 번개 · 종말의 비 · 브레스가 닿지 않는
	 * 자리에서 활을 쏠 수 있다. 그런 사람이 생기는 길(26.3 바이트코드로 확인했다):
	 *
	 * <ul>
	 *   <li><b>혼자 접속을 끊었다가 보더가 줄어든 뒤 돌아온다</b> — 진입 데려오기는 진입 첫 틱뿐이다</li>
	 *   <li><b>엔더 진주로 벽에 걸친 뒤 걸어 나간다</b> — 투사체는 보더에 맞아 멈추지만
	 *       ({@code ProjectileUtil} → {@code Level.clipIncludingBorder}) 맞은 자리가 벽 위라 몸이 벽에 걸치고,
	 *       바닐라 보더 충돌은 이미 겹친 상자를 밀어내지 않는다</li>
	 *   <li>후렴과일은 넘지 못한다 — {@code LivingEntity.randomTeleport} 가 목적지를
	 *       {@code WorldBorder.clampVec3ToBound} 로 벽 안에 자른다. 다만 벽에 딱 붙은 자리가 나올 수 있다</li>
	 * </ul>
	 *
	 * <p>사람 원칙 둘(「보더 닿아도 순간이동 없애, 벽 역할만」 · 「줄어들 때만 안으로」)을 함께 지키는 선이 이것이다 —
	 * <b>몸이 벽에 걸친 사람</b>(기대거나 넉백에 밀리거나 진주로 붙은 사람, 중심은 안)은 지금처럼 두고, <b>중심이
	 * 완전히 밖</b>인 사람만 벽이 멈춰 있어도 옮긴다. 벽 안에서 밀려 닿은 사람은 바닐라 충돌이 몸을 벽 안에 붙들므로
	 * 중심이 넘어갈 수 없다.
	 */
	static boolean beyond(double dx, double dz, double half) {
		return Math.max(Math.abs(dx), Math.abs(dz)) > half;
	}

	/**
	 * 이 틱에 이 사람을 안으로 옮기는가 — {@link #keepInside} 의 판단을 값으로만 적은 것. 벽이 움직이면 몸이
	 * 걸린 사람까지({@link #crossed}), 멈춰 있으면 중심이 밖인 사람만({@link #beyond}).
	 */
	static boolean pulls(boolean wallMoving, double dx, double dz, double half) {
		return wallMoving ? crossed(dx, dz, half) : beyond(dx, dz, half);
	}

	/**
	 * 그 방향으로 <b>네모 벽</b>까지의 거리(칸). 방향은 {@code (stepX, stepZ)} 단위 벡터다.
	 *
	 * <p>네모 반변 {@code half} 의 벽까지는 {@code half ÷ max(|stepX|, |stepZ|)} 이다. 원(내접원)으로
	 * 자르면 모서리 쪽 사람이 한참 안쪽으로 끌려온다 — 「완전 중앙으론 데려오지마」와 같은 뜻으로
	 * <b>있던 자리에서 가장 가까운 안</b>을 고른다.
	 */
	static double squareReach(double stepX, double stepZ, double half) {
		double axis = Math.max(Math.abs(stepX), Math.abs(stepZ));
		if (!(axis > 1.0E-9)) {
			return 0.0;
		}
		return Math.max(0.0, half) / axis;
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 붙박이 드래곤을 못박은 뒤에 부른다.
	 *
	 * <p>하는 일은 둘이다 — <b>보더를 시간표대로 세우고</b>({@link #driveBorder}) <b>벽이 움직이는 동안</b>
	 * ({@link #wallMoving}) <b>지나쳐진 사람을 안으로 옮긴다</b>({@link #keepInside}). 밖 피해 · 심장 박동 ·
	 * 빨간 기둥 벽은 2026-10-18 에 걷었다(사람 말 「넘어가서 딜 입히는 건 폐기」).
	 *
	 * <p>보더는 월드에 남으므로 되돌리는 줄이 있다 — 전투가 닫히면 {@code DragonTrialManager.tick},
	 * 서버가 내려가면 {@link DragonTrialBorder#onServerStopping}. 이 파일은 되돌리지 않는다.
	 *
	 * @param center  지대의 중심. 드래곤을 못박아 둔 자리(포디움)다
	 * @param beganAt 시계의 원점({@code Stand.clockBase}). 진입 연출 동안은 미래다
	 */
	static void tick(ServerLevel end, Vec3 center, List<ServerPlayer> members, long beganAt,
			long now) {
		if (drivingSince == Long.MIN_VALUE) {
			drivingSince = beganAt;
		} else if (drivingSince != beganAt) {
			// 남의 판이다. 보더는 차원에 하나라 두 판이 서로 다른 시간표로 몰면 벽이 틱마다 튄다 —
			// 먼저 든 판이 몰고 간다.
			// ⚠ 지금은 두 판이 동시에 열리지 않는다. 시련 세션이 한 번에 하나라(DragonTrialManager.
			// trialHolder — singleTeamOnly 를 끈 서버에서도 둘째 시련 팀은 시련 끔으로 열린다) 최후의
			// 저항도 하나뿐이다(2026-10-06 검토에서 확정된 문제). 팀이 사라진 판의 원점이 여기 남아
			// 다음 판이 영영 「남의 판」으로 읽히던 길은 DragonLastStand.onTeamGone 이 clearState 로
			// 닫는다(V1). 이 문은 남겨 둔다 — 「한쪽만 막으면 반드시 샌다」.
			return;
		}
		long elapsed = now - beganAt;
		driveBorder(end, center, elapsed, now);
		// 멈춘 벽에 닿은 사람은 옮기지 않는다 — 사람 말 「보더 닿아도 순간이동하는 건 없애」(2026-10-18 오후).
		// 다만 중심이 벽 <b>너머</b>인 사람은 벽이 멈춰 있어도 옮긴다(2026-10-18 밤 — beyond 의 설명).
		keepInside(end, center, members, settledRadiusAt(elapsed), wallMoving(elapsed));
	}

	/**
	 * 보더를 이 틱의 시간표대로 세운다. 이미 그렇게 서 있거나 움직이고 있으면 아무것도 보내지 않는다.
	 *
	 * <ul>
	 *   <li><b>진입 연출 동안</b>({@code elapsed < 0}) — 섬 가장자리에서 42 로 2초 동안 들어오고
	 *       ({@link #entryHalfAt}) 그 뒤로 42 에 선다. 시간을 재지 않으므로 축소는 없다</li>
	 *   <li><b>축소 구간</b> — 그 걸음의 끝값까지 <b>남은 틱</b> 동안 바닐라가 줄인다. 늦게 들어온
	 *       틱(재시작)에서도 제 시각에 끝난다</li>
	 *   <li><b>그 밖</b> — 그 걸음의 값에 멈춰 선다</li>
	 * </ul>
	 *
	 * <p>재시작으로 되살린 판은 연출이 없고 시계가 처음부터 다시 돈다 — 첫 틱에 42 로 바로 선다.
	 */
	private static void driveBorder(ServerLevel end, Vec3 center, long elapsed, long now) {
		if (elapsed < 0L) {
			long step = Math.max(0L, elapsed + DragonLastStandEntry.LENGTH_TICKS);
			if (step < ENTRY_SHRINK_TICKS) {
				DragonTrialBorder.shrink(end, center, entryHalfAt(step), START_RADIUS,
						ENTRY_SHRINK_TICKS - step, now);
			} else {
				DragonTrialBorder.hold(end, center, START_RADIUS);
			}
			return;
		}
		for (int leg = 0; leg < LEG_END_TICKS.length; leg++) {
			long begins = shrinkBeginsAt(leg);
			long ends = LEG_END_TICKS[leg];
			if (elapsed < begins) {
				DragonTrialBorder.hold(end, center, RADII[leg]);
				return;
			}
			if (elapsed < ends) {
				DragonTrialBorder.shrink(end, center, radiusAt(elapsed), RADII[leg + 1],
						ends - elapsed, now);
				return;
			}
		}
		DragonTrialBorder.hold(end, center, RADII[RADII.length - 1]);
	}

	/**
	 * ⚠ <b>줄어드는 보더가 지나친 사람을 피해 없이 안으로 옮긴다.</b> 사람 말 「월드보더로 아예 못
	 * 넘어가게」.
	 *
	 * <p>바닐라 보더는 <b>안에서 밖으로 나가는 것</b>만 막는다. 벽이 줄어들며 사람을 지나치면 그 사람은
	 * 그냥 밖에 서 있게 되고(칸당 피해를 껐으므로 아프지도 않다) 거기서 더 멀리 걸어갈 수 있다. 그래서
	 * 벽에 걸리는 순간({@link #crossed}) 그 사람을 옮긴다.
	 *
	 * <p>⚠ <b>벽이 움직이는 틱에만 불린다</b>({@link #wallMoving}, 2026-10-18 오후). 매 틱 부르던 때는 멈춘 벽에
	 * 기대거나 넉백으로 밀려 닿은 사람까지 옮겼다 — 사람 말 「보더 닿아도 순간이동하는 건 없애. 그냥 벽
	 * 역할만 하면 돼서」.
	 *
	 * <ul>
	 *   <li><b>어디로</b> — 진입 데려오기와 같은 길이다({@code DragonLastStandEntry.pullTarget}). 중앙에서
	 *       그 사람 방향으로 땅을 짚어 나가 땅이 끊기거나 포디움보다 {@code PULL_MAX_RISE} 넘게 높아지는
	 *       데서 멈춘다 — 흑요석 기둥 꼭대기나 허공에 내려놓지 않는다. 가장 멀리는 벽이 가 닿을 반변
	 *       ({@link #settledRadiusAt})에서 {@code WALL_MARGIN} 칸 안쪽의 <b>네모</b>까지다</li>
	 *   <li><b>있던 자리보다 바깥으로는 안 옮긴다</b> — 그럴 일은 없지만(밖에 있는 사람이다) 거리 셈이
	 *       어긋나도 사람을 벽 쪽으로 미는 일이 없게 자른다</li>
	 *   <li><b>지금 벽</b>({@link DragonTrialBorder#halfNow})으로 잰다. 시간표가 아니라 바닐라가 실제로
	 *       세워 둔 벽이 사람을 지나친 것이 기준이다</li>
	 * </ul>
	 *
	 * <p>관전자는 옮기지 않는다. 팀원만 본다 — 보더는 차원 전체에 서지만 다른 팀(시련 끔으로 같은 엔드에
	 * 있는 팀)의 사람을 우리 판 한가운데로 끌어오지 않는다. 그 사람은 밖에 서 있어도 아프지 않다.
	 *
	 * <p>⚠ <b>2026-10-18 밤 — 벽이 멈춰 있어도 중심이 벽 너머인 사람은 옮긴다</b>({@link #beyond}). 벽이 움직이는
	 * 틱에는 지금처럼 몸이 걸린 사람까지({@link #crossed}), 멈춘 틱에는 중심이 밖인 사람만이다.
	 *
	 * @param settled 벽이 가 닿을 반변
	 * @param moving  벽이 움직이는 틱인가({@link #wallMoving})
	 */
	private static void keepInside(ServerLevel end, Vec3 center, List<ServerPlayer> members,
			double settled, boolean moving) {
		if (members.isEmpty() || !DragonTrialBorder.held()) {
			return;
		}
		double wall = DragonTrialBorder.halfNow(end);
		double inner = Math.max(0.0, Math.min(wall, settled) - DragonLastStandEntry.WALL_MARGIN);
		TrialEnderPulse.Ground ground = null;
		int moved = 0;
		for (ServerPlayer member : members) {
			if (member.isSpectator() || member.level() != end) {
				continue;
			}
			double dx = member.getX() - center.x;
			double dz = member.getZ() - center.z;
			if (!pulls(moving, dx, dz, wall)) {
				continue;
			}
			double from = Math.sqrt(dx * dx + dz * dz);
			if (!(from > 1.0E-4)) {
				continue;
			}
			double edge = Math.min(from, squareReach(dx / from, dz / from, inner));
			if (ground == null) {
				ground = new TrialEnderPulse.Ground();
			}
			Vec3 target = DragonLastStandEntry.pullTarget(end, ground, center, dx, dz, edge);
			if (target == null) {
				continue;
			}
			// 엔드 안에서 엔드 안으로다. 진입 데려오기와 같은 길 — EndFightTeleportLock 은 목적지가
			// 엔드면 첫 줄에서 통과시킨다.
			member.teleportTo(end, target.x, target.y, target.z, java.util.Set.of(),
					member.getYRot(), member.getXRot(), false);
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					target.x, target.y + 1.0, target.z, 40, 0.4, 0.8, 0.4, 0.1);
			TrialWarning.playEach(end, List.of(member), SoundEvents.ENDERMAN_TELEPORT, 1.0F, 0.8F);
			moved++;
		}
		if (moved > 0) {
			SharedFateMod.LOGGER.info("[END] 최후의 저항 — 보더가 지나친 {}명을 안쪽으로 옮겼습니다(반변 {})",
					moved, Math.round(settled));
		}
	}

	// ------------------------------------------------------------------ 비우기

	/**
	 * 전투가 닫히거나 월드가 바뀌거나 서버가 내려갈 때. 정적 상태만 비운다.
	 *
	 * <p>월드를 만지지 않는다 — 보더를 되돌리는 것은 {@link DragonTrialBorder} 다
	 * ({@code DragonTrialManager.tick} 이 시련 세션이 없는 틱에 되돌린다). {@code DragonLastStand.clearState}
	 * 가 {@code SERVER_STOPPED} 에서도 부르고, {@code DragonLastStand.onFightClosed} 도 부른다.
	 */
	static void clearState() {
		drivingSince = Long.MIN_VALUE;
	}

	/** 시험이 들여다보는 곳. 지금 몰고 있는 판이 있는가. */
	static boolean driving() {
		return drivingSince != Long.MIN_VALUE;
	}
}
