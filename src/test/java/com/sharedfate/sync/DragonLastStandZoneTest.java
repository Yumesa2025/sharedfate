package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>안전지대</b>. 월드 없이 답이 정해지는 것만 여기서 굴린다.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 제목 화면에 닿기 전에 죽으므로
 * <b>파란 벽을 눈으로 확인할 수 없다.</b> 그래서 지대의 셈을 전부 순수 함수로 빼내 여기서
 * 붙든다 — 시간표 · 반경 · 밖 피해 · 되돌릴 값이 그것이고, 실제로 보더에 그 값이 들어가는지는
 * 서버를 띄워야 볼 수 있다.
 */
class DragonLastStandZoneTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandZone.clearState();
	}

	// ------------------------------------------------------------------ 크기

	/** 시작 반경이 기둥 줄(42)과 같다. 40 으로 내리면 기둥에 선 사람이 진입 직후부터 아프다. */
	@Test
	void 시작_반경이_기둥_줄과_같다() {
		assertEquals(42.0, DragonLastStandZone.START_RADIUS, 0.0001,
				"42 는 「엔더 파동」이 이미 「섬 끝」으로 쓰는 값이다 — 근거는 START_RADIUS 에 있다");
		assertEquals(DragonLastStandZone.START_RADIUS, DragonLastStandZone.RADII[0], 0.0001);
	}

	/** 마지막이 <b>반경 12칸</b>이다. 사람이 정한 값이다. */
	@Test
	void 마지막_반경이_12다() {
		assertEquals(12.0, DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1], 0.0001,
				"사람이 정한 값이다");
	}

	/** 세 걸음이 <b>똑같이 10칸</b>이다. 들쭉날쭉하면 첫 축소를 보고 다음 크기를 짐작할 수 없다. */
	@Test
	void 세_걸음이_10칸씩이다() {
		assertEquals(4, DragonLastStandZone.RADII.length, "축소는 세 번이다");
		for (int leg = 0; leg + 1 < DragonLastStandZone.RADII.length; leg++) {
			assertEquals(10.0,
					DragonLastStandZone.RADII[leg] - DragonLastStandZone.RADII[leg + 1], 0.0001,
					leg + "번째 걸음이 10칸이 아니다");
		}
	}

	/** 반경을 지름으로 바꾸는 곱셈이 <b>이 저장소의 다른 한 곳과 답이 같다.</b> */
	@Test
	void 반경을_지름으로_바꾸는_곱이_한_가지다() {
		for (int radius : new int[] {12, 22, 32, 42, 50}) {
			assertEquals(PreStartRestrictions.lockDiameterBlocks(radius),
					DragonLastStandZone.diameterOf(radius), 0.0001,
					"PreStartRestrictions 와 답이 갈렸다 — 한쪽 지대가 절반이 된다");
		}
		assertEquals(24.0, DragonLastStandZone.diameterOf(12.0), 0.0001,
				"반경 12 를 그대로 setSize 에 넘기면 지대가 반경 6 이 된다");
	}

	// ------------------------------------------------------------------ 시간표

	/** 45초 → 45초 → 25초이고 마지막이 <b>115초</b>다. */
	@Test
	void 축소가_45_45_25초다() {
		assertEquals(900L, DragonLastStandZone.LEG_END_TICKS[0], "45초");
		assertEquals(1800L, DragonLastStandZone.LEG_END_TICKS[1], "45초 더");
		assertEquals(2300L, DragonLastStandZone.LEG_END_TICKS[2], "25초 더 — 115초다");
		assertEquals(115L * 20L, DragonLastStandZone.LEG_END_TICKS[2],
				"문서의 「축소가 다 끝난 115초 시점」이 이 숫자다");
		assertEquals(DragonLastStandZone.LEG_END_TICKS[2],
				DragonLastStandZone.escalationStartTicks(),
				"오르기 시작하는 때가 마지막 축소가 끝나는 때와 갈렸다");
	}

	/** 축소는 걸음의 <b>끝</b>에 붙는다. 시작에 붙이면 마지막 축소가 95초에 끝나 115초가 거짓이 된다. */
	@Test
	void 축소가_걸음의_끝에_붙는다() {
		for (int leg = 0; leg < DragonLastStandZone.LEG_END_TICKS.length; leg++) {
			assertEquals(DragonLastStandZone.LEG_END_TICKS[leg] - DragonLastStandZone.SHRINK_TICKS,
					DragonLastStandZone.shrinkBeginsAt(leg));
		}
		assertEquals(800L, DragonLastStandZone.shrinkBeginsAt(0));
	}

	/** 반경이 <b>줄기만</b> 하고 끝값이 12 다. 되감긴 판에서도 시작값을 돌려준다. */
	@Test
	void 반경이_줄기만_한다() {
		double previous = DragonLastStandZone.radiusAt(-100L);
		assertEquals(DragonLastStandZone.START_RADIUS, previous, 0.0001,
				"진입 전이면 아직 안 줄었다");
		for (long elapsed = 0L; elapsed <= 3000L; elapsed++) {
			double radius = DragonLastStandZone.radiusAt(elapsed);
			assertTrue(radius <= previous + 1.0E-9,
					elapsed + "틱에 반경이 늘었다: " + previous + " → " + radius);
			previous = radius;
		}
		assertEquals(12.0, DragonLastStandZone.radiusAt(2300L), 0.0001, "115초에 반경 12 다");
		assertEquals(12.0, DragonLastStandZone.radiusAt(9999L), 0.0001, "그 뒤로는 그대로다");
	}

	/**
	 * 축소 속도가 <b>걷는 속도보다 느리다.</b>
	 *
	 * <p>뛰어야만 살 수 있는 축소는 「대응 불가」에 가깝다. 걷기가 초당 4.3칸(0.215칸/틱)이고
	 * 이 지대는 100틱에 10칸이라 0.1칸/틱이다.
	 */
	@Test
	void 벽이_걷는_사람을_따라잡지_못한다() {
		double perTick = 10.0 / DragonLastStandZone.SHRINK_TICKS;
		double walkPerTick = 4.317 / 20.0;
		assertTrue(perTick < walkPerTick,
				"벽이 초당 " + perTick * 20.0 + "칸으로 좁혀 걷는 사람을 따라잡는다");
	}

	// ------------------------------------------------------------------ 시계와 브레스

	/** 축소 중에는 시계가 「줄어든다」를 말한다. */
	@Test
	void 축소_구간에서_시계가_줄어든다고_말한다() {
		long beganAt = 5_000L;
		for (int leg = 0; leg < DragonLastStandZone.LEG_END_TICKS.length; leg++) {
			long begins = DragonLastStandZone.shrinkBeginsAt(leg);
			long ends = DragonLastStandZone.LEG_END_TICKS[leg];
			assertTrue(DragonLastStandZone.clock(beganAt, beganAt + begins).shrinking(),
					leg + "번째 축소가 시작하는 틱인데 안 줄어든다고 한다");
			assertTrue(DragonLastStandZone.clock(beganAt, beganAt + ends - 1).shrinking(),
					leg + "번째 축소의 마지막 틱인데 안 줄어든다고 한다");
			assertEquals(beganAt + ends,
					DragonLastStandZone.clock(beganAt, beganAt + ends).lastShrinkEndedAt(),
					leg + "번째 축소가 끝난 시각을 안 기억한다");
		}
	}

	/** 아직 한 번도 안 줄었으면 「축소 직후」라는 것이 없다. {@code MIN_VALUE} 를 그냥 빼면 넘친다. */
	@Test
	void 첫_축소_전에는_기억이_없다() {
		assertEquals(Long.MIN_VALUE, DragonLastStandZone.clock(0L, 0L).lastShrinkEndedAt());
		assertEquals(Long.MIN_VALUE, DragonLastStandZone.clock(0L, 899L).lastShrinkEndedAt());
		assertTrue(DragonLastStand.breathOpen(Long.MIN_VALUE,
						DragonLastStandZone.clock(0L, 100L), 100L),
				"축소가 한 번도 없었는데 「축소 직후」로 읽혔다");
	}

	/** 되감긴 판에서는 축소가 아예 없다. {@code now < beganAt} 는 복원 직후에 실제로 생긴다. */
	@Test
	void 되감긴_판에서는_축소가_없다() {
		DragonLastStand.ZoneClock clock = DragonLastStandZone.clock(1_000L, 900L);
		assertFalse(clock.shrinking());
		assertEquals(Long.MIN_VALUE, clock.lastShrinkEndedAt());
	}

	/**
	 * ⚠⚠ <b>「축소와 브레스 동시 실행 금지」가 실제로 지켜진다.</b>
	 *
	 * <p>{@code DragonLastStand.allowed} 는 <b>고르는 그 틱</b>만 보므로, 축소 1틱 전에 고른
	 * 100틱짜리 브레스(5초 예고이던 때 120틱)는 그대로 축소와 겹쳐 돈다. {@code shrinkLockoutLead} 가 그 길이만큼
	 * 미리 잠그는 것이 이 시험이 지키는 것이다 — 그 상수를 지우면 여기가 먼저 깨진다.
	 */
	@Test
	void 브레스가_축소와_겹쳐_돌_수_없다() {
		long beganAt = 0L;
		int length = DragonLastStand.Pattern.CONE_BREATH.durationTicks();
		for (long at = 0L; at <= 3_000L; at++) {
			if (!DragonLastStand.breathOpen(Long.MIN_VALUE,
					DragonLastStandZone.clock(beganAt, at), at)) {
				continue;
			}
			for (long tick = at; tick < at + length; tick++) {
				assertFalse(actuallyShrinking(tick),
						at + "틱에 고른 브레스가 " + tick + "틱의 축소와 겹친다");
			}
		}
	}

	/** 그래도 브레스가 <b>나올 자리는 넉넉히 남는다.</b> 다 막으면 이 페이즈의 대표 패턴이 사라진다. */
	@Test
	void 브레스가_나올_자리가_남는다() {
		long open = 0L;
		for (long at = 0L; at < DragonLastStandZone.LEG_END_TICKS[2]; at++) {
			if (DragonLastStand.breathOpen(Long.MIN_VALUE,
					DragonLastStandZone.clock(0L, at), at)) {
				open++;
			}
		}
		long span = DragonLastStandZone.LEG_END_TICKS[2];
		assertTrue(open * 2L > span,
				"115초 가운데 브레스를 고를 수 있는 틱이 절반도 안 된다: " + open + " / " + span);
	}

	// ------------------------------------------------------------------ 밖 피해

	/** 기준이 <b>초당 8</b> 이고 115초까지 그대로다. */
	@Test
	void 축소가_끝날_때까지는_초당_8이다() {
		assertEquals(8.0F, DragonLastStandZone.outsideDamage(0L), 0.0001F);
		assertEquals(8.0F, DragonLastStandZone.outsideDamage(2_299L), 0.0001F);
		assertEquals(8.0F, DragonLastStandZone.outsideDamage(2_300L), 0.0001F,
				"115초 그 자리는 아직 8 이다 — 「5초가 지날 때마다」 오른다");
	}

	/** 115초 뒤에는 5초마다 <b>+1.6</b> 이다. 사람이 정한 값이고 <b>더하기</b>다. */
	@Test
	void 축소가_끝난_뒤에는_5초마다_1점6씩_더해진다() {
		assertEquals(1.6F, DragonLastStandZone.ESCALATION_PER_STEP, 0.0001F, "사람이 정한 값이다");
		assertEquals(100L, DragonLastStandZone.ESCALATION_STEP_TICKS, "5초다");
		assertEquals(9.6F, DragonLastStandZone.outsideDamage(2_400L), 0.0001F);
		assertEquals(11.2F, DragonLastStandZone.outsideDamage(2_500L), 0.0001F);
		// 곱하기였다면 120초에 12.8 · 125초에 20.48 이다. 더하기라 선형으로 오른다.
		float first = DragonLastStandZone.outsideDamage(2_400L)
				- DragonLastStandZone.outsideDamage(2_300L);
		float second = DragonLastStandZone.outsideDamage(2_500L)
				- DragonLastStandZone.outsideDamage(2_400L);
		assertEquals(first, second, 0.0001F, "곱하기가 되면 걸음마다 오르는 양이 달라진다");
	}

	/**
	 * ⚠ <b>인원수가 이 계산에 들어오지 않는다.</b>
	 *
	 * <p>넷이 다 밖이어도 초당 8 이다 — 곱하면 초당 32 라 무장하고도 6초에 전멸이고, 문서가
	 * 못박은 것이 정확히 그것이다. 인자에 인원이 들어오는 순간 여기가 깨진다.
	 */
	@Test
	void 밖_피해에_인원수가_들어오지_않는다() throws NoSuchMethodException {
		assertNotNull(DragonLastStandZone.class.getDeclaredMethod("outsideDamage", long.class));
		for (var method : DragonLastStandZone.class.getDeclaredMethods()) {
			if (!method.getName().equals("outsideDamage")) {
				continue;
			}
			assertEquals(1, method.getParameterCount(),
					"outsideDamage 에 인자가 늘었다 — 인원수를 받게 되면 초당 32 가 된다");
		}
	}

	/** 초당 값이 <b>초에 한 번</b> 들어간다. 더 촘촘하면 바닐라 피격 무적시간(10틱)에 먹힌다. */
	@Test
	void 밖_피해가_초에_한_번이다() {
		assertEquals(20L, DragonLastStandZone.OUTSIDE_TICK_INTERVAL);
		assertTrue(DragonLastStandZone.OUTSIDE_TICK_INTERVAL > 10L,
				"무적시간 10틱보다 좁으면 두 번째 몫이 조용히 사라져 적힌 값이 거짓이 된다");
	}

	/** 넉백 유예가 <b>2초</b>다. 사람이 정한 값이다. */
	@Test
	void 넉백_유예가_2초다() {
		assertEquals(40L, DragonLastStandZone.SHOVE_GRACE_TICKS, "사람이 정한 값이다");
	}

	// ------------------------------------------------------------------ 되돌리기

	/** 정지해 있던 보더는 그 크기로 되돌아간다. */
	@Test
	void 정지한_보더는_그_크기로_되돌아간다() {
		WorldBorder.Settings still = new WorldBorder.Settings(
				10.0, -20.0, 0.3, 4.0, 6, 14, 1234.0, 0L, 1234.0);
		assertEquals(1234.0, DragonLastStandZone.restoredDiameter(still), 0.0001);
	}

	/** 움직이던 보더는 <b>목표</b>에 세운다. 중간 크기에 멈춰 세우는 것은 아무의 뜻도 아니다. */
	@Test
	void 움직이던_보더는_목표에_세운다() {
		WorldBorder.Settings moving = new WorldBorder.Settings(
				0.0, 0.0, 0.2, 5.0, 5, 15, 500.0, 600L, 200.0);
		assertEquals(200.0, DragonLastStandZone.restoredDiameter(moving), 0.0001);
	}

	/** 바닐라 기본값을 <b>넣지 않는다.</b> 사람이 보더를 따로 만져 뒀을 수 있다. */
	@Test
	void 되돌릴_때_바닐라_기본값을_넣지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("DEFAULT"),
				"WorldBorder.Settings.DEFAULT 로 되돌리면 운영자가 좁혀 둔 보더가 날아간다");
		assertTrue(bytes.contains("border/WorldBorder$Settings"),
				"진입 전 값을 기억하지 않는다 — 되돌릴 근거가 없다");
	}

	/** 기억이 없으면 아무 일도 하지 않는다. 최후의 저항이 안 열린 판의 보더를 「고쳐 주면」 안 된다. */
	@Test
	void 만지지_않은_보더는_되돌리지도_않는다() {
		assertFalse(DragonLastStandZone.holdsBorder());
		// end 가 null 이어도 터지지 않는다. SERVER_STOPPING 에 엔드가 없는 판이 있을 수 있다.
		DragonLastStandZone.restore(null);
		DragonLastStandZone.onServerStopping(null);
		assertFalse(DragonLastStandZone.holdsBorder());
	}

	/** 보더 자체 피해를 끈다. 켜 두면 바닐라 피해가 우리 초당 8 위에 얹힌다. */
	@Test
	void 보더_자체_피해를_끈다() {
		assertTrue(classBytes().contains("setDamagePerBlock"),
				"보더 피해를 끄지 않으면 「나간 거리 × 칸당 피해」가 우리 값 위에 겹친다");
	}

	/** 밖 피해는 {@code lightningBolt()} 다. 여기를 바꾸면 문서의 「초당 0.81」이 거짓이 된다. */
	@Test
	void 밖_피해원이_번개다() {
		assertTrue(classBytes().contains("lightningBolt"),
				"DragonLastStandTest 의 안전지대_밖은_초당_8이다 가 이 피해원으로 셈을 해 두었다");
		float geared = GearedDamage.afterGear(DragonLastStandZone.outsideDamage(0L),
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(geared * 20.0F < GearedDamage.TEAM_HEALTH,
				"밖에서 1초에 전멸하면 안 된다 — 실제 초당 " + geared);
		assertTrue(geared * 30.0F > GearedDamage.TEAM_HEALTH,
				"30초를 버티면 밖이 안전지대가 된다 — 실제 초당 " + geared);
	}

	/** 받은 {@code now} 를 쓴다. {@code getGameTime} 을 스스로 읽으면 얼어붙은 판에서 시계가 멈춘다. */
	@Test
	void 시간을_스스로_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"받은 now 를 쓸 것 — 그것이 이 저장소의 규약이다");
	}

	/** 자막을 쓰지 않는다. 신호는 소리와 바닥 표식(그리고 보더의 파란 벽)뿐이다. */
	@Test
	void 자막을_쓰지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("showTitle"));
		assertFalse(bytes.contains("showActionBar"));
	}

	// ------------------------------------------------------------------ 배선

	/** {@code DragonLastStand} 가 이 시계를 실제로 몰고 되돌리는가. 빠지면 파란 벽이 남는다. */
	@Test
	void 최후의_저항이_지대를_몰고_되돌린다() {
		String bytes = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandZone"),
				"DragonLastStand 가 안전지대를 부르지 않는다 — 보더가 아예 안 선다");
		assertTrue(bytes.contains("onServerStopping"),
				"SERVER_STOPPING 되돌림이 빠졌다 — 줄어든 보더가 저장 파일에 남는다");
	}

	/** {@code SharedFateMod} 가 그 종료 훅을 실제로 등록하는가. */
	@Test
	void 종료_훅이_등록되어_있다() {
		String bytes = read("/com/sharedfate/SharedFateMod.class");
		assertTrue(bytes.contains("onServerStopping"),
				"SharedFateMod 에 DragonLastStand::onServerStopping 이 없다");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStand"),
				"SharedFateMod 가 DragonLastStand 를 가리키지 않는다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 지금 <b>정말로</b> 보더가 줄어드는 중인가. 시계의 넓은 답(미리 잠그는 몫)을 빼고 본다. */
	private static boolean actuallyShrinking(long elapsed) {
		for (int leg = 0; leg < DragonLastStandZone.LEG_END_TICKS.length; leg++) {
			if (elapsed >= DragonLastStandZone.shrinkBeginsAt(leg)
					&& elapsed < DragonLastStandZone.LEG_END_TICKS[leg]) {
				return true;
			}
		}
		return false;
	}

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandZone.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandZoneTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
