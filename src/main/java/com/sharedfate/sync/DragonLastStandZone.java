package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「최후의 저항」의 <b>안전지대</b>. 월드 보더로 그린다.
 *
 * <h2>패턴이 아니라 혼자 도는 시계다</h2>
 *
 * <p>{@link DragonLastStand.Pattern} 처럼 뽑히지 않는다. 진입과 동시에 시작해 저 혼자
 * <b>45초 → 45초 → 25초</b> 세 걸음으로 좁아지고, 마지막이 <b>반경 12칸</b>이다. 그래서
 * {@link DragonLastStand} 는 이 파일에 매 틱 {@link #tick} 을 넘기기만 하고, 고르는 쪽은
 * {@link #clock} 이 돌려주는 답 두 개만 본다.
 *
 * <h2>왜 월드 보더인가</h2>
 *
 * <p>바닐라 보더의 파란 벽이 그대로 뜨고 축소 애니메이션도 공짜다. 우리가 파티클로 반경 42
 * 짜리 원을 그리면 한 틱에 수백 점이 나가는데({@code TrialLandingShock.MAX_POINTS_PER_TICK} 가
 * 440 이고 그것이 이미 꽉 찬 예산이다) 보더는 <b>점을 한 개도 쓰지 않는다.</b>
 *
 * <h3>26.3 에서 확인한 사실 — 보더는 차원마다 하나씩이다</h3>
 *
 * <p>바이트코드를 풀어 읽었다. {@code ServerLevel.getWorldBorder()} 가
 * {@code getDataStorage().computeIfAbsent(WorldBorder.TYPE)} 라, 보더는 <b>그 차원의
 * {@code SavedData}</b> 다. 그리고 {@code PlayerList.addWorldborderListener(ServerLevel)} ·
 * {@code PlayerList.sendLevelInfo(player, level)} 가 차원별로 붙고
 * {@code broadcastAll(packet, level.dimension())} 로 <b>그 차원 사람에게만</b> 나간다.
 *
 * <p>여기서 따라오는 것이 셋이다.
 *
 * <ul>
 *   <li><b>엔드 보더를 만져도 오버월드는 그대로다.</b> {@code PreStartRestrictions.applySpawnBorder}
 *       가 오버월드 보더를 스폰 50칸으로 조이는데, 그것과 부딪히지 않는다</li>
 *   <li><b>엔드에 있는 사람에게만 벽이 보인다.</b> 밖에 있는 팀원에게 파란 벽이 뜨는 일이 없다</li>
 *   <li>⚠ <b>보더는 월드 저장 파일에 남는다.</b> 「월드와 함께 사라진다」가 성립하지 않는다 —
 *       되돌리는 이야기는 아래 {@link #restore} 에 있다</li>
 * </ul>
 *
 * <h3>⚠ 보더는 원이 아니라 <b>정사각형</b>이다</h3>
 *
 * <p>그래서 문서의 「반경 12칸」은 <b>한 변의 절반</b>이다({@code size} = 24).
 * {@code PreStartRestrictions.lockDiameterBlocks} 가 「이 곱셈 하나가 가장 틀리기 쉬운
 * 자리」라고 적어 둔 그 곱셈이고, 여기서도 {@link #diameterOf} 한 곳에서만 곱한다.
 *
 * <p>모서리는 그만큼 멀다 — 반변 12 짜리 사각형의 모서리는 중앙에서 <b>17칸</b>이다. 「번개
 * 5개」가 반경 12 안에 들어가는지 셀 때는 <b>내접원(12)</b>으로 재야 안전하다.
 *
 * <h3>⚠ 벽은 <b>안에 있는 사람만</b> 막는다 — 그것이 넉백에 걸리는 천장 하나다</h3>
 *
 * <p>{@code Entity.collide} 와 {@code CollisionGetter.noCollision} 은 보더의 충돌 형상을
 * {@code WorldBorder.isInsideCloseToBorder(entity, box)} 가 참일 때만 얹는다. 그 물음은
 * <b>안에 있고 벽에서 2칸 안</b>일 때만 참이다. 곧
 *
 * <ul>
 *   <li>안에 있으면 벽이 밖으로 미는 것을 막는다 — 날개 퍼덕이기가 사람을 <b>지대 밖으로 밀어
 *       낼 수 없다.</b> 다만 <b>이것에 기대지 말 것</b>: 사각형이라 대각선 쪽 벽은 42×√2 = 59칸
 *       바깥이고, 그쪽으로는 벽이 아무것도 막지 않는다. 낙사를 막는 것은 여전히
 *       {@code TrialEnderStorm.pushDistance} 의 천장이다</li>
 *   <li>이미 밖에 있으면 벽이 없다 — 보더가 지나가 버린 사람이 벽에 갇히지 않고 되돌아올 수
 *       있다. 「밖에 있으면 초당 8」이 「피할 수 없는 죽음」이 아닌 근거가 이 한 줄이다</li>
 * </ul>
 *
 * <h2>보더 자체 피해는 끄고 우리 공식을 쓴다</h2>
 *
 * <p>26.3 {@code LivingEntity} 의 그 구간은 {@code damagePerBlock > 0} 일 때만 돈다. 그래서
 * {@link #apply} 가 {@code setDamagePerBlock(0)} 한 줄로 끈다. 켜 두면 밖으로 나간 거리에
 * 비례하는 바닐라 피해가 우리 초당 8 위에 겹쳐 얹힌다.
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
 * 그때 하고, 지금은 이 문단이 그 사실의 유일한 기록이다.
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
	 *       초당 8 을 맞는다 — 화면도 자막도 없는 자리에서 그것은 「왜 아픈지 알 수 없는 피해」다</li>
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
	 * 값이 거짓이 된다.
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
	 * <p>⚠ 벽이 「안에 있는 사람」을 막으므로(클래스 설명) 넉백이 사람을 <b>지대 밖으로 밀어
	 * 내는</b> 일은 사실 없다. 유예가 실제로 일하는 자리는 <b>이미 밖에 있는 사람이 또 밀릴
	 * 때</b>와 <b>축소가 방금 지나가 버린 사람이 밀릴 때</b> 둘이다. 「밀려서 밖으로 나갔다」가
	 * 아니라 「밀리는 동안은 안 아프다」로 읽을 것.
	 */
	static final long SHOVE_GRACE_TICKS = 40L;

	// ------------------------------------------------------------------ 상태

	/**
	 * ⚠ <b>진입 전의 보더.</b> 되돌릴 때 쓰는 유일한 근거다.
	 *
	 * <p>{@code WorldBorder.Settings.DEFAULT} 를 넣어 되돌리면 안 된다 —
	 * {@code PreStartRestrictions.vanillaDefaultTarget} 이 「운영자가 손으로 정해 둔 보더를
	 * 기억하지 못한다」고 스스로 적어 둔 그 함정이고, 그쪽은 오버월드라 그래도 넘겼지만 여기는
	 * <b>엔드 보더를 우리가 처음으로 만지는 자리</b>라 넘길 이유가 없다.
	 *
	 * <p>{@code null} 이면 「우리가 아직 보더를 만지지 않았다」다. 되돌리는 쪽이 이 한 칸만
	 * 보고 갈리므로 <b>만지기 전에 반드시 채우고, 되돌린 뒤에 반드시 비운다.</b>
	 */
	private static @Nullable WorldBorder.Settings remembered;

	/** 지금 몰고 있는 최후의 저항이 시작한 틱. 바뀌면 처음부터 다시 세운다. */
	private static long drivingSince = Long.MIN_VALUE;

	/** 지금까지 실제로 시킨 축소 횟수. 틱을 건너뛰어도 빠짐없이 따라가게 한다. */
	private static int orderedShrinks;

	/** 사람마다의 넉백 유예가 끝나는 시각. */
	private static final Map<UUID, Long> SHOVE_GRACE = new HashMap<>();

	private DragonLastStandZone() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 반경(칸)을 보더 {@code size}(<b>한 변</b>)로 바꾼다.
	 *
	 * <p>⚠ 반경을 그대로 {@code setSize} 에 넘기면 지대가 <b>절반</b>이 된다.
	 * {@code PreStartRestrictions.lockDiameterBlocks} 가 같은 곱셈을 같은 이유로 들고 있고,
	 * {@code DragonLastStandZoneTest} 가 <b>두 곳의 답이 같은지</b>를 지킨다.
	 */
	static double diameterOf(double radius) {
		return Math.max(0.0, radius) * 2.0;
	}

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
	 * 이 틱에 보이는 반경(칸). 축소 중이면 사이값이다.
	 *
	 * <p>판정에 쓰지 않는다 — 판정은 보더 자신에게 묻는다({@link #outside}). 이 함수는
	 * <b>시험이 「정말 줄어들기만 하는가 · 끝값이 12 인가」를 물을 수 있게</b> 떼어 둔 것이다.
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

	/**
	 * 되돌릴 때 얹을 한 변의 길이.
	 *
	 * <p>기억해 둔 보더가 <b>움직이던 중</b>이었으면(운영자가 {@code /worldborder set X 30} 을
	 * 돌려 둔 판) 그 애니메이션을 이어 주지 않고 <b>목표값에 세운다.</b> 이어 주려면 「몇 틱이
	 * 남았는가」를 알아야 하는데 되돌리는 자리에는 시각이 없고, 중간 크기에 멈춰 세우는 것보다
	 * 운영자가 적어 둔 목표가 그 사람의 뜻에 가깝다. 정지 상태였으면
	 * {@code lerpTarget() == size()} 라 둘이 같은 값이다({@code StaticBorderExtent} 에서
	 * 확인했다).
	 */
	static double restoredDiameter(WorldBorder.Settings settings) {
		return settings.lerpTime() > 0L ? settings.lerpTarget() : settings.size();
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 붙박이 드래곤을 못박은 뒤에 부른다.
	 *
	 * @param center  지대의 중심. 드래곤을 못박아 둔 자리다
	 * @param beganAt 최후의 저항이 시작한 틱
	 */
	static void tick(ServerLevel end, Vec3 center, List<ServerPlayer> members, long beganAt,
			long now) {
		WorldBorder border = end.getWorldBorder();
		if (drivingSince == Long.MIN_VALUE) {
			apply(end, border, center, beganAt);
		} else if (drivingSince != beganAt) {
			// 남의 판이다. 보더도 드래곤도 차원에 하나뿐이라 두 판이 같은 벽을 서로 다른
			// 시각으로 밀면 벽이 매 틱 처음 크기로 되돌아간다 — 먼저 든 판이 몰고 간다.
			// 드래곤이 하나이므로 실제로 두 판이 동시에 열리는 서버는 singleTeamOnly 를 끈
			// 서버뿐이고, 그때 늦게 든 팀은 지대 없이 싸운다.
			return;
		}
		long elapsed = now - beganAt;
		if (elapsed < 0L) {
			return;
		}
		// while 이다. 틱을 건너뛰어도(렉·시간 정지) 밀린 축소를 빠짐없이 따라간다 — if 로 두면
		// 한 번 놓친 걸음이 영영 안 와 지대가 그 크기에 멈춘다.
		while (orderedShrinks < LEG_END_TICKS.length
				&& elapsed >= shrinkBeginsAt(orderedShrinks)) {
			shrink(border, orderedShrinks++, elapsed, now);
		}
		if (elapsed % OUTSIDE_TICK_INTERVAL == 0L) {
			punish(end, border, members, elapsed);
		}
	}

	/**
	 * 보더를 우리 것으로 세운다. 진입한 틱과, 재시작 뒤 다시 세우는 틱에 한 번씩이다.
	 *
	 * <p>순서가 중요하다 — <b>기억이 먼저</b>다. 먼저 만지면 기억할 원래 값이 사라진다.
	 */
	private static void apply(ServerLevel end, WorldBorder border, Vec3 center, long beganAt) {
		if (remembered == null) {
			remembered = new WorldBorder.Settings(border);
			SharedFateMod.LOGGER.info(
					"[END] 엔드 월드 보더를 기억했습니다 — 한 변 {} · 중심 ({}, {}) · 칸당 피해 {}",
					remembered.size(), remembered.centerX(), remembered.centerZ(),
					remembered.damagePerBlock());
		}
		drivingSince = beganAt;
		orderedShrinks = 0;
		SHOVE_GRACE.clear();
		// 중심은 드래곤을 못박아 둔 자리다. 카드 쪽 기하학(TrialRisks.arenaOffset ·
		// TrialEnderStorm.pushDistance)은 중앙을 (0, 0) 으로 잡는데, 바닐라 엔드의 발판은
		// atBottomCenterOf 때문에 (0.5, 0.5) 라 반 칸 어긋난다. 넉백 천장의 여유가 8칸이라
		// 이 반 칸은 묻히고, 발판이 (0, 0) 이 아닌 판(fightOrigin 을 옮긴 월드)에서는 여기가
		// 맞고 그쪽이 틀린다 — 지대는 드래곤을 중심으로 조여야 한다.
		border.setCenter(center.x, center.z);
		// 보더 자체 피해를 끈다. 켜 두면 바닐라가 「나간 거리 × 칸당 피해」를 우리 초당 8 위에
		// 얹는다 — 26.3 LivingEntity 의 그 구간은 damagePerBlock > 0 일 때만 돈다.
		border.setDamagePerBlock(0.0);
		border.setSize(diameterOf(START_RADIUS));
	}

	/**
	 * 한 걸음 좁힌다.
	 *
	 * <p>{@code lerpSizeBetween(지금, 목표, 남은 틱, 받은 틱)} 이다. 26.3 에서 그 셋째 인자가
	 * <b>밀리초가 아니라 틱</b>인 것을 바이트코드로 확인했다 —
	 * {@code MovingBorderExtent.update()} 가 {@code lerpProgress} 를 <b>1씩</b> 줄이고
	 * {@code WorldBorderCommand.formatTicksToSeconds} 가 20 으로 나눈다.
	 *
	 * <p>남은 틱으로 넘기는 것은 밀려서 시작한 축소를 <b>제 시각에 끝내기</b> 위해서다. 0 이하면
	 * 이미 끝났어야 하는 걸음이라 곧바로 세운다.
	 */
	private static void shrink(WorldBorder border, int leg, long elapsed, long now) {
		double target = diameterOf(RADII[leg + 1]);
		long remaining = SHRINK_TICKS - (elapsed - shrinkBeginsAt(leg));
		if (remaining <= 0L) {
			border.setSize(target);
			return;
		}
		border.lerpSizeBetween(border.getSize(), target, remaining, now);
	}

	/**
	 * 밖에 있는 사람 하나에게 초당 값을 넣는다.
	 *
	 * <h2>왜 <b>하나</b>인가</h2>
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
	 * <p>피해원은 {@code lightningBolt()} 다. {@code DragonLastStandTest} 의
	 * {@code 안전지대_밖은_초당_8이다} 가 그 피해원으로 셈을 해 두었으므로 여기를 바꾸면 그
	 * 시험이 재는 값과 실제가 갈린다 — 무장 기준 초당 <b>0.81</b> 이고, 완전무장한 팀이 밖에서
	 * 버틸 수 있는 시간이 약 25초다.
	 */
	private static void punish(ServerLevel end, WorldBorder border, List<ServerPlayer> members,
			long elapsed) {
		ServerPlayer worst = null;
		double worstDistance = Double.MAX_VALUE;
		for (ServerPlayer member : members) {
			if (member.isSpectator() || !outside(border, member)) {
				continue;
			}
			Long grace = SHOVE_GRACE.get(member.getUUID());
			if (grace != null && elapsed < grace) {
				// 넉백 유예 중이다. 「밀리는 동안은 안 아프다」가 사람이 정한 것이다.
				continue;
			}
			// 밖에서는 음수다(26.3 LivingEntity 가 같은 값으로 바닐라 피해를 잰다).
			double distance = border.getDistanceToBorder(member.getX(), member.getZ());
			if (distance < worstDistance) {
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
	 * 이 사람이 지대 밖인가.
	 *
	 * <p><b>보더 자신에게 묻는다.</b> 우리가 계산한 반경으로 재면 축소 애니메이션이 도는 동안
	 * 눈에 보이는 벽과 아픈 자리가 어긋난다 — 「표식이 거짓말하지 않는다」가 이 저장소의
	 * 약속이고, 여기서는 그 표식이 바닐라의 파란 벽이다.
	 *
	 * <p>세로는 보지 않는다. 보더는 원래부터 기둥이라 높이가 없다.
	 */
	private static boolean outside(WorldBorder border, ServerPlayer member) {
		return !border.isWithinBounds(member.getX(), member.getZ());
	}

	/** 넉백으로 밀렸다. {@code DragonLastStandPatterns} 의 날개 퍼덕이기가 부른다. */
	static void noteShoved(UUID memberId, long beganAt, long now) {
		SHOVE_GRACE.put(memberId, now - beganAt + SHOVE_GRACE_TICKS);
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * ⚠ <b>보더를 원래대로 되돌린다.</b> 월드가 살아 있어야 한다.
	 *
	 * <p>부르는 곳이 둘이다 — {@code DragonLastStand.onFightClosed}(드래곤이 사라진 틱)와
	 * {@code DragonLastStand.onServerStopping}(저장 직전). {@code clearState} 에서는 부를 수
	 * 없다: 그쪽은 {@code SERVER_STOPPED} 에서도 불려 레벨이 이미 닫혀 있다.
	 *
	 * <p>기억해 둔 것이 없으면 아무 일도 하지 않는다. 최후의 저항이 한 번도 안 열린 판에서
	 * 보더를 바닐라 기본값으로 「고쳐 주는」 일이 없어야 한다.
	 */
	static void restore(@Nullable ServerLevel end) {
		WorldBorder.Settings original = remembered;
		if (original == null) {
			forget();
			return;
		}
		if (end == null) {
			// 월드를 못 만진다. 기억은 그대로 들고 있는다 — 다음에 월드가 있는 자리에서 되돌린다.
			return;
		}
		try {
			WorldBorder border = end.getWorldBorder();
			border.setCenter(original.centerX(), original.centerZ());
			border.setDamagePerBlock(original.damagePerBlock());
			border.setSafeZone(original.safeZone());
			border.setWarningBlocks(original.warningBlocks());
			border.setWarningTime(original.warningTime());
			border.setSize(restoredDiameter(original));
			if (original.lerpTime() > 0L) {
				SharedFateMod.LOGGER.info(
						"[END] 되돌린 보더가 움직이던 중이었습니다 — 애니메이션을 잇지 않고 목표 {} 에 세웠습니다",
						original.lerpTarget());
			}
			SharedFateMod.LOGGER.info("[END] 엔드 월드 보더를 원래대로 되돌렸습니다 — 한 변 {} · 중심 ({}, {})",
					restoredDiameter(original), original.centerX(), original.centerZ());
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("엔드 월드 보더를 되돌리지 못했습니다.", error);
		}
		forget();
	}

	/**
	 * 서버가 멈추기 직전. 저장보다 먼저 보더를 되돌린다.
	 *
	 * <p>{@code TrialFreeze.onServerStopping} 과 같은 자리에 같은 이유로 있다 —
	 * {@code SERVER_STOPPED} 는 이미 늦다. 늦으면 <b>줄어든 보더가 저장 파일에 남아</b> 다음
	 * 기동에 파란 벽이 그대로 뜬다.
	 */
	static void onServerStopping(@Nullable MinecraftServer server) {
		restore(server == null ? null : server.getLevel(Level.END));
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. <b>기억만 버린다.</b>
	 *
	 * <p>월드를 만지지 않는다 — {@code DragonLastStand.clearState} 가 {@code SERVER_STOPPED}
	 * 에서도 불린다. 보더를 되돌리는 것은 {@link #onServerStopping} 이 먼저 해 두었고,
	 * 그 길을 못 지난 종료(강제 종료)에서는 다음 기동에 파란 벽이 남는다 — 그때는
	 * {@code DragonLastStand.resume} 이 최후의 저항을 다시 세우므로 이 시계가 다시 몰고 간다.
	 */
	static void clearState() {
		forget();
	}

	private static void forget() {
		remembered = null;
		drivingSince = Long.MIN_VALUE;
		orderedShrinks = 0;
		SHOVE_GRACE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 보더를 우리가 들고 있는가. */
	static boolean holdsBorder() {
		return remembered != null;
	}
}
