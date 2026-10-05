package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「최후의 저항」의 <b>안전지대</b>. <b>나갈 수 있는 원</b>이다 — 월드 보더를 쓰지 않는다.
 *
 * <h2>패턴이 아니라 혼자 도는 시계다</h2>
 *
 * <p>{@link DragonLastStand.Pattern} 처럼 뽑히지 않는다. 진입과 동시에 시작해 저 혼자
 * <b>45초 → 45초 → 25초</b> 세 걸음으로 좁아지고, 마지막이 <b>반경 12칸</b>이다. 그래서
 * {@link DragonLastStand} 는 이 파일에 매 틱 {@link #tick} 을 넘기기만 하고, 고르는 쪽은
 * {@link #clock} 이 돌려주는 답 두 개만 본다.
 *
 * <h2>⚠⚠ 2026-10-04 — 월드 보더를 버리고 「나갈 수 있는 원」으로 바꿨다</h2>
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
 * <p>지금은 이렇게 나뉜다.
 *
 * <ul>
 *   <li><b>판정</b> — {@link #outside}. 지대 중심에서 수평 거리가 {@link #radiusAt} 보다 크면 밖이다.
 *       <b>원</b>이다 — 보더 시절의 「반변」·「내접원」 이야기가 전부 사라졌다</li>
 *   <li><b>보이는 것</b> — {@link DragonLastStandZoneWall}. 같은 반경 둘레의 빨간 입자 기둥.
 *       줄어드는 동안 실제 반경을 따라 움직인다</li>
 *   <li><b>밖에 있는 사람의 되먹임</b> — 1초마다 그 사람에게만 <b>심장 박동</b>({@link #punish})</li>
 * </ul>
 *
 * <h3>보더를 되돌리는 안전장치를 두지 않았다 — 근거</h3>
 *
 * <p>보더를 만지던 코드(기억 → 줄이기 → 되돌리기)를 통째로 걷었다. 「이미 줄어든 보더가 저장된
 * 월드」를 고쳐 주는 줄도 넣지 않았다. 근거가 넷이다.
 *
 * <ol>
 *   <li><b>배포된 적이 없다.</b> 보더를 만지던 코드는 {@code 89a843b} 에서 들어왔고 그 커밋을 품은
 *       태그가 없다 — {@code feature/dragon-trials} 에만 있었다</li>
 *   <li><b>그 코드를 실제로 돌린 월드 둘을 열어 봤다</b>(2026-10-04). 시험 서버
 *       {@code C:\temp\sftrial\world} 와 개발용 {@code run/world} 의
 *       {@code dimensions/minecraft/the_end/data/minecraft/world_border.dat} 가 둘 다 바닐라 기본값이다
 *       (한 변 59,999,968 · 중심 0,0 · 칸당 피해 0.2)</li>
 *   <li><b>남은 보더를 「우리 것」으로 가려낼 수 없다.</b> 고치려면 「중심이 포디움 근처이고 한 변이
 *       84 이하이고 칸당 피해가 0」 같은 지문으로 짐작해 {@code Settings.DEFAULT} 로 덮어야 하는데,
 *       그것은 옛 코드 스스로 금지한 일이다(운영자가 손으로 좁혀 둔 보더를 날린다). 이 파일이 다시
 *       보더를 만지는 순간 「보더를 더는 건드리지 않는다」도 거짓이 된다</li>
 *   <li><b>남아 있어도 손으로 한 줄이다.</b> 서버 콘솔에서
 *       {@code execute in minecraft:the_end run worldborder set 59999968} — 옛 코드가 서버가 강제로
 *       죽은 판에서 줄어든 보더를 「원래 값」으로 잘못 기억하는 구멍도 있었으므로(재기동하면 줄어든
 *       보더를 기억했다가 그것으로 되돌렸다) 혹시 남았다면 이 한 줄이 맞는 답이다</li>
 * </ol>
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
 * 그때 하고, 지금은 이 문단이 그 사실의 유일한 기록이다. 보더를 버린 뒤로는 <b>월드에 남는 것이
 * 하나도 없으므로</b> 재시작이 남기는 것은 이 시계 하나뿐이다.
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
	 *   <li><b>진입하는 순간 밖에 있는 사람이 없다.</b> 섬 위든 기둥 꼭대기든 42 안이다.
	 *       40 으로 잡으면 기둥에 올라가 있던 사람이 <b>진입 3초 무적이 풀리는 그 틱부터</b>
	 *       초당 8 을 맞는다</li>
	 *   <li><b>첫 벽이 기둥 줄에 선다.</b> 사람이 「어디까지가 안인가」를 기둥으로 읽는다.
	 *       반대로 44~45 로 잡으면 첫 벽이 통째로 허공 위에 서서 <b>첫 45초가 아무 일도 안 한다</b></li>
	 *   <li><b>42 − 12 = 30 이 세 번에 정확히 10칸씩이다.</b> 걸음이 같아야 사람이 첫 축소
	 *       한 번으로 다음 크기를 배운다 — {@link #RADII} 를 볼 것</li>
	 * </ol>
	 *
	 * <p>⚠ {@code TrialRisks.ARENA_RADIUS}(40)를 쓰지 않은 것은 <b>그쪽이 「자리를 고르는
	 * 반경」</b>이기 때문이다. 위험 지점은 섬 위에 떨어져야 하므로 40 이 맞고, 안전지대는
	 * 「처음에는 아무도 밖이 아니어야」 하므로 42 가 맞다. 둘을 하나로 묶으면 위의 ①이 깨진다.
	 */
	static final double START_RADIUS = 42.0;

	/**
	 * 걸음마다의 반경(칸). {@code [시작, 1축소 뒤, 2축소 뒤, 3축소 뒤]}.
	 *
	 * <p>끝값 <b>12 는 사람이 정한 값</b>이다. 시작값 42 는 {@link #START_RADIUS} 의 근거로
	 * 골랐고, 가운데 둘은 그 사이를 <b>똑같이 세 걸음</b>으로 나눈 것이다 — 걸음이 들쭉날쭉하면
	 * 첫 축소를 보고 다음 크기를 짐작할 수 없다.
	 *
	 * <p>한 걸음 10칸을 {@link #SHRINK_TICKS}(5초)에 좁히므로 <b>초당 2칸</b>이다. 걷는 속도가
	 * 초당 4.3칸이라 <b>벽을 등지고 걸어도 따라잡히지 않는다</b> — 뛰어야만 살 수 있는 축소는
	 * 「대응 불가」에 가깝다.
	 *
	 * <p>보더 시절에는 이 값이 정사각형의 <b>반변</b>이었다. 지금은 <b>원의 반경</b>이다 — 같은 숫자가
	 * 모서리 방향으로 최대 17칸(반변 12 사각형의 모서리)까지 열어 주던 자리를 12 로 닫는다. 마지막
	 * 지대 넓이가 576칸² → 452칸²(약 78%)로 줄었다. 상시 번개와 오브젝트 파도는 이미
	 * <b>내접원(12)</b>으로 재 두었으므로 그쪽 셈은 그대로 맞다.
	 */
	static final double[] RADII = {START_RADIUS, 32.0, 22.0, 12.0};

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
	 * 밖 피해를 넣는 간격(틱). 1초.
	 *
	 * <p>초당 값이 적혀 있으므로 초에 한 번이다. 바닐라 피격 무적시간이 10틱이라 20틱은
	 * <b>반드시 통과한다</b> — 더 촘촘하게 나누면(예: 10틱마다 절반) 무적시간에 먹혀 적힌
	 * 값이 거짓이 된다. 심장 박동도 같은 박자다.
	 */
	static final long OUTSIDE_TICK_INTERVAL = 20L;

	/**
	 * 축소가 다 끝난 뒤 피해가 오르기 시작하는 시각(틱). 115초.
	 *
	 * <p>{@link #LEG_END_TICKS} 의 마지막과 <b>같은 값</b>이다. 따로 적지 않는다 — 갈라지면
	 * 「축소가 끝나자마자」가 거짓이 된다.
	 */
	static long escalationStartTicks() {
		return LEG_END_TICKS[LEG_END_TICKS.length - 1];
	}

	/** 오르는 간격(틱). 5초. */
	static final long ESCALATION_STEP_TICKS = 100L;

	/**
	 * 한 걸음에 <b>더하는</b> 값. 곱하기가 아니다.
	 *
	 * <p><b>사람이 정한 값이다.</b> 옛 값 +0.2 는 기준이 초당 1 이던 시절의 것(5초마다 기준의
	 * 20%)이라 기준이 8 로 오른 지금 그대로 쓰면 1/40 밖에 안 된다. 같은 비율을 옮긴 것이
	 * 1.6 이고, 사람이 그 숫자를 정했다.
	 */
	static final float ESCALATION_PER_STEP = 1.6F;

	/**
	 * 넉백으로 밀려난 사람을 봐 주는 시간(틱). 2초.
	 *
	 * <p><b>사람이 정한 값이다.</b> 날개 퍼덕이기가 사람을 미는데 유예가 없으면 밀린 그 자리에서
	 * 밖 피해가 곧바로 겹친다.
	 *
	 * <p>보더 시절에는 벽이 안에 있는 사람을 막아 「밀려서 밖으로 나가는」 일이 거의 없었다. 원이 된
	 * 뒤로는 <b>정말로 밀려 나간다</b> — 날개 퍼덕이기의 천장은 「섬 반경 32 안 · 땅이 이어진 데까지」
	 * 라 마지막 지대(12)보다 한참 바깥까지 민다. 그래서 이 유예가 이제야 제 일을 한다.
	 *
	 * <p>⚠⚠ <b>2026-10-04 까지 이 유예는 한 번도 일하지 않았다.</b> {@link #noteShoved} 가 받은
	 * 시각을 「이 시계의 원점」에서 잰다고 믿었는데 부르는 쪽({@code DragonLastStandPatterns.shove})이
	 * 넘기는 것은 <b>패턴이 시작한 틱</b>이었다. 패턴은 시계가 돌고 최소 3초 뒤에 시작하므로 적힌 유예
	 * 끝이 언제나 이미 지난 시각이었다. 지금은 <b>받은 {@code now} 로 절대 시각</b>을 적어 원점과
	 * 무관하다 — {@code DragonLastStandZoneTest.넉백_유예가_패턴_시각과_무관하게_2초다} 가 그 경우를 붙든다.
	 */
	static final long SHOVE_GRACE_TICKS = 40L;

	/**
	 * 심장 박동의 크기. 1.0 이 바닐라 그대로다.
	 *
	 * <p>{@code entity.warden.heartbeat} 다 — 26.3 {@code sounds.json} 에서 {@code mob/warden/heartbeat_1~4}
	 * 를 가리키고 <b>그 파일을 쓰는 다른 소리가 없다</b>(26.3 에셋 목록에서 ogg 넷을 확인했다). 이
	 * 저장소가 쓰는 워든 소리 둘({@code WARDEN_SONIC_BOOM} · {@code WARDEN_DIG})과도 파일이 다르다.
	 */
	static final float HEARTBEAT_VOLUME = 1.0F;
	/** 심장 박동의 음높이. 바닐라 그대로라 「심장 소리」로 바로 읽힌다. */
	static final float HEARTBEAT_PITCH = 1.0F;

	// ------------------------------------------------------------------ 상태

	/** 지금 몰고 있는 최후의 저항의 시계 원점. 바뀌면 처음부터 다시 세운다. */
	private static long drivingSince = Long.MIN_VALUE;

	/**
	 * 사람마다의 넉백 유예가 끝나는 <b>절대 시각</b>(받은 {@code now} 의 틱).
	 *
	 * <p>시계 원점에서 잰 값을 적지 않는다 — 까닭은 {@link #SHOVE_GRACE_TICKS} 의 ⚠⚠.
	 */
	private static final Map<UUID, Long> SHOVE_GRACE = new HashMap<>();

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
	 * 이 틱의 반경(칸). 축소 중이면 사이값이다.
	 *
	 * <p>⚠ <b>판정과 그림이 이 한 함수를 함께 본다.</b> 보더 시절에는 판정을 보더 자신에게 물어
	 * 「보이는 벽과 아픈 자리가 어긋나지 않는다」를 지켰다. 이제 벽을 우리가 그리므로 같은 약속을
	 * 「같은 함수」로 지킨다 — {@link #outside} 가 이 값으로 재고,
	 * {@link DragonLastStandZoneWall#drawRadiusAt} 이 이 값(조금 앞선 시각)으로 그린다.
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
	 * 지대 중심에서 {@code (dx, dz)} 만큼 떨어진 자리가 <b>밖</b>인가.
	 *
	 * <p><b>원</b>이다 — 수평 거리가 반경보다 크면 밖이고, 경계 위는 안이다. 세로는 보지 않는다
	 * (공중에 떠 있어도 기둥 위에 있어도 같은 원이다 — 보더가 그랬던 것과 같다).
	 */
	static boolean outside(double dx, double dz, double radius) {
		return dx * dx + dz * dz > radius * radius;
	}

	/**
	 * 지금 밖에 있는 사람에게 <b>팀에</b> 들어가는 적히는 피해.
	 *
	 * <p><b>인원수로 곱하지 않는다.</b> 넷이 다 밖이어도 이 값 하나다 — 곱하면 초당 32 라
	 * 무장하고도 6초 만에 전멸이다. 넣는 쪽이 {@link #punish} 이고, 거기서 <b>한 사람에게만</b>
	 * 한 번 넣는다.
	 *
	 * <p>115초부터 5초마다 {@link #ESCALATION_PER_STEP} 씩 <b>더한다</b>(곱하기가 아니다).
	 * 115초 그 자리는 아직 8 이고 120초에 처음 9.6 이 된다 — 「115초부터 5초마다」를 「5초가
	 * 지날 때마다」로 읽은 것이다.
	 */
	static float outsideDamage(long elapsed) {
		float base = DragonLastStand.OUTSIDE_ZONE_DAMAGE_PER_SECOND;
		long start = escalationStartTicks();
		if (elapsed < start) {
			return base;
		}
		long steps = (elapsed - start) / ESCALATION_STEP_TICKS;
		return base + ESCALATION_PER_STEP * steps;
	}

	/** 이 사람이 지금 넉백 유예 중인가. 받은 {@code now} 로 묻는다. */
	/**
	 * 다음 축소 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다. <b>상태를 하나도 쓰지 않는다.</b>
	 *
	 * <p>{@link #radiusAt} 과 같은 구간을 훑는다. 축소가 시작되기 전이면 시작까지(「안전지대 → 32칸」),
	 * 줄어드는 5초({@link #SHRINK_TICKS}) 동안은 다 줄 때까지(「축소 중」)다. 진입 연출 동안은 시계
	 * 원점이 미래라 경과가 음수이고, 그만큼 첫 축소가 멀다 — 사람이 「그땐 시간 재지말고」라고 한
	 * 것이 그대로 숫자에 실린다.
	 *
	 * @param clockBase 최후의 저항의 시계 원점
	 * @return 마지막 축소(반경 12)까지 끝났으면 {@code null}
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

	static boolean inShoveGrace(UUID memberId, long now) {
		Long until = SHOVE_GRACE.get(memberId);
		return until != null && now < until;
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 붙박이 드래곤을 못박은 뒤에 부른다.
	 *
	 * <p>⚠ <b>월드에 쓰는 것이 하나도 없다.</b> 파티클과 소리와 피해뿐이라 저장 파일에 남는 것이 없고,
	 * 그래서 되돌리는 줄(전에 있던 {@code restore} · {@code onServerStopping})도 없다.
	 *
	 * @param center  지대의 중심. 드래곤을 못박아 둔 자리다
	 * @param beganAt 시계의 원점({@code Stand.clockBase}). 진입 연출 동안은 미래다
	 */
	static void tick(ServerLevel end, Vec3 center, List<ServerPlayer> members, long beganAt,
			long now) {
		if (drivingSince == Long.MIN_VALUE) {
			drivingSince = beganAt;
			SHOVE_GRACE.clear();
		} else if (drivingSince != beganAt) {
			// 남의 판이다. 드래곤이 차원에 하나라 중심도 하나뿐이고, 두 판이 같은 자리에 서로 다른
			// 시각의 원을 그리면 원이 두 겹으로 뜬다 — 먼저 든 판이 몰고 간다. 실제로 두 판이 동시에
			// 열리는 서버는 singleTeamOnly 를 끈 서버뿐이고, 그때 늦게 든 팀은 지대 없이 싸운다.
			return;
		}
		long elapsed = now - beganAt;
		// 진입 연출 동안(elapsed < 0)에도 원은 서 있다 — 사람이 「보더가 생기면서」라고 정한 순서다.
		// 반경은 시작값 42 이고 밖 피해와 박동은 아래에서 막힌다.
		DragonLastStandZoneWall.draw(end, center, elapsed, now);
		if (elapsed < 0L) {
			return;
		}
		if (elapsed % OUTSIDE_TICK_INTERVAL == 0L) {
			punish(end, center, members, elapsed, now);
		}
	}

	/**
	 * 밖에 있는 사람마다 심장 박동을 들려주고, <b>한 사람</b>에게 초당 값을 넣는다.
	 *
	 * <h2>왜 피해는 <b>하나</b>인가</h2>
	 *
	 * <p>공유 체력에서 범위 피해는 팀원별로 그대로 합산된다({@code StatMirror.fold}). 밖에 있는
	 * 사람 모두를 때리면 넷이 다 밖일 때 초당 32 라 무장하고도 6초에 전멸이고, 문서가
	 * <b>「인원수로 곱하지 마십시오」</b>라고 못박은 것이 정확히 그것이다. 그래서 적힌 값 한 방을
	 * <b>한 사람에게만</b> 넣는다.
	 *
	 * <p>고르는 기준은 <b>가장 멀리 나간 사람</b>이다. 무작위로 고르면 같은 상황에서 팀이 받는
	 * 값이 사람의 장비에 따라 틱마다 달라지고(감쇠는 한 방마다 걸린다), 「가장 멀리」는
	 * 들쭉날쭉하지 않으면서 가장 잘못한 사람이 맞는다.
	 *
	 * <h2>심장 박동은 <b>밖에 있는 사람 모두</b>에게, <b>그 사람에게만</b></h2>
	 *
	 * <p>원이 된 뒤로는 「모르고 밖에 서 있는」 일이 생긴다 — 넉백에 밀려서, 축소가 지나가서, 뒤로
	 * 걸으며 싸우다가. 맞는 사람은 피격 연출로 알지만 공유 체력이라 <b>깎이는 것은 팀 전체</b>이고,
	 * 피해를 안 받은 나머지(유예 중이거나 더 멀리 나간 사람이 따로 있거나)는 제가 밖이라는 것을
	 * 알 길이 없었다. 그래서 박동은 피해와 갈라 <b>「지금 밖이다」</b>만 말한다. 자막은 쓰지
	 * 않는다(이 저장소의 규약 — 드래곤이 때리는 중에 지나가는 글자는 안 읽힌다).
	 *
	 * <p>{@code TrialWarning.playEach} 에 <b>그 사람 하나</b>만 넘긴다 — 안에 있는 팀원에게 박동이
	 * 들리면 「내가 밖인가」가 흐려진다({@code TrialWarning.soundFor} 가 같은 판단).
	 *
	 * <p>피해원은 {@code lightningBolt()} 다. {@code DragonLastStandTest} 의
	 * {@code 안전지대_밖은_초당_8이다} 가 그 피해원으로 셈을 해 두었으므로 여기를 바꾸면 그
	 * 시험이 재는 값과 실제가 갈린다 — 무장 기준 초당 <b>0.81</b> 이고, 완전무장한 팀이 밖에서
	 * 버틸 수 있는 시간이 약 25초다. 이 피해원은 개체가 없어 <b>넉백이 없다</b> — 밖에서 맞아 더
	 * 밖으로 밀리는 일이 없다.
	 */
	private static void punish(ServerLevel end, Vec3 center, List<ServerPlayer> members,
			long elapsed, long now) {
		double radius = radiusAt(elapsed);
		ServerPlayer worst = null;
		double worstDistance = -1.0;
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			double dx = member.getX() - center.x;
			double dz = member.getZ() - center.z;
			if (!outside(dx, dz, radius)) {
				continue;
			}
			// 밖이다. 유예 중이어도 박동은 들린다 — 박동이 말하는 것은 「아프다」가 아니라 「밖이다」다.
			TrialWarning.playEach(end, List.of(member), SoundEvents.WARDEN_HEARTBEAT,
					HEARTBEAT_VOLUME, HEARTBEAT_PITCH);
			if (inShoveGrace(member.getUUID(), now)) {
				// 넉백 유예 중이다. 「밀리는 동안은 안 아프다」가 사람이 정한 것이다.
				continue;
			}
			double distance = dx * dx + dz * dz;
			if (distance > worstDistance) {
				worstDistance = distance;
				worst = member;
			}
		}
		if (worst == null) {
			return;
		}
		worst.hurtServer(end, end.damageSources().lightningBolt(), outsideDamage(elapsed));
	}

	/**
	 * 넉백으로 밀렸다. {@code DragonLastStandPatterns} 의 날개 퍼덕이기가 부른다.
	 *
	 * <p>⚠ {@code ignoredOrigin} 은 <b>쓰지 않는다.</b> 부르는 쪽이 패턴 시작 시각을 넘기는데, 그것을
	 * 원점으로 쓰던 것이 2026-10-04 까지 유예를 통째로 죽여 두었다({@link #SHOVE_GRACE_TICKS} 의 ⚠⚠).
	 * 인자를 지우면 남의 파일({@code DragonLastStandPatterns})을 고쳐야 해서 모양만 남겼다.
	 */
	static void noteShoved(UUID memberId, long ignoredOrigin, long now) {
		SHOVE_GRACE.put(memberId, now + SHOVE_GRACE_TICKS);
	}

	// ------------------------------------------------------------------ 비우기

	/**
	 * 전투가 닫히거나 월드가 바뀌거나 서버가 내려갈 때. 정적 상태만 비운다.
	 *
	 * <p>월드를 만지지 않는다 — 만질 것이 없다. {@code DragonLastStand.clearState} 가
	 * {@code SERVER_STOPPED} 에서도 부르고, {@code DragonLastStand.onFightClosed} 도 부른다.
	 */
	static void clearState() {
		drivingSince = Long.MIN_VALUE;
		SHOVE_GRACE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 몰고 있는 판이 있는가. */
	static boolean driving() {
		return drivingSince != Long.MIN_VALUE;
	}
}
