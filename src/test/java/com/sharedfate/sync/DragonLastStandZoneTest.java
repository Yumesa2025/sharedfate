package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>안전지대</b>. 월드 없이 답이 정해지는 것만 여기서 굴린다.
 *
 * <p>⚠ 2026-10-18 에 지대가 다시 <b>엔드 월드 보더</b>가 됐다(사람 말 「월드보더로 아예 못 넘어가게,
 * 넘어가서 딜 입히는 건 폐기」 · 「안전지대는 42-32-22-18 로 변경」). 10-04 ~ 10-18 의 「나갈 수 있는
 * 원」 시절 시험(밖 피해 · 피해 증가 · 넉백 유예 · 심장 박동 · 빨간 기둥 벽)은 붙들 대상이 사람 지시로
 * 사라져 <b>「없다」를 붙드는 시험</b>으로 바꿨다. 보더 자체의 셈(반변→한 변 · 지문 · 되돌릴 크기)은
 * {@code DragonTrialBorderTest} 에 있다.
 *
 * <p>⚠ 게임 창을 띄울 수 없으므로(사람이 PC 를 쓴다) 지대의 셈을 전부 순수 함수로 빼내 여기서
 * 붙든다 — 시간표 · 반변 · 진입 때 들어오는 보더 · 지나친 사람 판정이 그것이다.
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

	/** <b>42 · 32 · 22 · 18</b> 이다. 사람이 2026-10-18 에 「안전지대는 42-32-22-18 로 변경」이라고 정했다. */
	@Test
	void 반변이_42_32_22_18이다() {
		assertEquals(4, DragonLastStandZone.RADII.length, "축소는 세 번이다");
		double[] wanted = {42.0, 32.0, 22.0, 18.0};
		for (int leg = 0; leg < wanted.length; leg++) {
			assertEquals(wanted[leg], DragonLastStandZone.RADII[leg], 0.0001,
					leg + "번째 값이 사람이 정한 값이 아니다");
		}
	}

	/**
	 * 진입 보더가 <b>섬 가장자리에서 42 로</b> 하강 2초 동안 들어오고 그 뒤 42 에 선다.
	 *
	 * <p>사람 말 「드래곤전 시작하면 월드보더가 제일 끝에서 생기고 2페이지 넘어올 때 원래 줄어들기 시작할
	 * 때부터 줄어들게」. 진입 틱에 39 원 밖 사람은 이미 데려왔으므로 들어오는 벽이 사람을 안 끼운다.
	 */
	@Test
	void 진입_보더가_섬_가장자리에서_42로_들어온다() {
		assertEquals(DragonTrialBorder.ISLAND_HALF_SIDE, DragonLastStandZone.entryHalfAt(0L), 1.0E-9);
		assertEquals(DragonLastStandZone.START_RADIUS,
				DragonLastStandZone.entryHalfAt(DragonLastStandZone.ENTRY_SHRINK_TICKS), 1.0E-9);
		assertEquals(DragonLastStandZone.START_RADIUS,
				DragonLastStandZone.entryHalfAt(DragonLastStandEntry.LENGTH_TICKS), 1.0E-9,
				"연출이 끝날 때 42 가 아니면 시계가 도는 첫 틱에 벽이 튄다");
		double previous = DragonLastStandZone.entryHalfAt(0L);
		for (long step = 1L; step <= DragonLastStandZone.ENTRY_SHRINK_TICKS; step++) {
			double half = DragonLastStandZone.entryHalfAt(step);
			assertTrue(half < previous, step + "틱에 진입 보더가 안 줄었다");
			previous = half;
		}
		assertEquals(DragonLastStandEntry.DESCEND_TICKS, DragonLastStandZone.ENTRY_SHRINK_TICKS,
				"드래곤 하강과 벽이 한 덩어리로 읽히게 같은 2초로 잡았다");
		assertTrue(DragonLastStandEntry.innerEdge() < DragonLastStandZone.START_RADIUS,
				"진입 데려오기 줄이 첫 보더 밖이다 — 데려온 사람이 벽에 낀다");
	}

	/**
	 * 줄어드는 벽이 지나친 사람을 <b>그 걸음의 끝값</b> 안쪽에 둔다 — 지금 벽 바로 안이면 5초 동안 몇
	 * 번이고 다시 옮겨진다.
	 */
	@Test
	void 지나친_사람은_걸음의_끝값_안쪽에_둔다() {
		assertEquals(42.0, DragonLastStandZone.settledRadiusAt(-50L), 1.0E-9);
		assertEquals(42.0, DragonLastStandZone.settledRadiusAt(799L), 1.0E-9);
		assertEquals(32.0, DragonLastStandZone.settledRadiusAt(800L), 1.0E-9, "축소가 시작하면 끝값이다");
		assertEquals(32.0, DragonLastStandZone.settledRadiusAt(1699L), 1.0E-9);
		assertEquals(22.0, DragonLastStandZone.settledRadiusAt(1700L), 1.0E-9);
		assertEquals(18.0, DragonLastStandZone.settledRadiusAt(2200L), 1.0E-9);
		assertEquals(18.0, DragonLastStandZone.settledRadiusAt(9999L), 1.0E-9);
		for (long elapsed = 0L; elapsed <= 2400L; elapsed++) {
			assertTrue(DragonLastStandZone.settledRadiusAt(elapsed)
							<= DragonLastStandZone.radiusAt(elapsed) + 1.0E-9,
					elapsed + "틱 — 끝값이 지금 벽보다 바깥이다");
		}
	}

	/**
	 * ⚠ <b>벽이 움직이는 틱에만 옮긴다</b> — 2026-10-18 오후 사람 말 「보더 닿아도 순간이동하는 건 없애. 그냥 벽
	 * 역할만 하면 돼서」. 0.41.0 은 매 틱 옮겨서 멈춘 벽에 기대거나 넉백으로 밀려 닿은 사람까지 안쪽으로 옮겼다.
	 *
	 * <p>움직이는 구간은 진입 때 105 → 42 로 들어오는 2초와 걸음마다의 축소 5초이고, <b>끝나는 틱까지</b>다 —
	 * 마지막 틱에 지나쳐진 사람이 그 틱에야 「걸렸다」로 읽히기 때문이다.
	 */
	@Test
	void 벽이_움직일_때만_옮긴다() {
		long entry = -DragonLastStandEntry.LENGTH_TICKS;
		assertTrue(DragonLastStandZone.wallMoving(entry), "진입 틱 — 보더가 105 에서 들어오기 시작한다");
		assertTrue(DragonLastStandZone.wallMoving(entry + DragonLastStandZone.ENTRY_SHRINK_TICKS),
				"진입 보더가 42 에 닿는 틱을 놓쳤다");
		assertFalse(DragonLastStandZone.wallMoving(entry + DragonLastStandZone.ENTRY_SHRINK_TICKS + 1),
				"42 에 멈춘 뒤에도 옮긴다");
		assertFalse(DragonLastStandZone.wallMoving(-1L), "진입 연출 끝 무렵은 멈춘 벽이다");
		for (int leg = 0; leg < DragonLastStandZone.LEG_END_TICKS.length; leg++) {
			long begins = DragonLastStandZone.shrinkBeginsAt(leg);
			long ends = DragonLastStandZone.LEG_END_TICKS[leg];
			assertFalse(DragonLastStandZone.wallMoving(begins - 1), leg + "걸음 — 축소 전 멈춘 벽에서 옮긴다");
			assertTrue(DragonLastStandZone.wallMoving(begins), leg + "걸음 — 축소 첫 틱");
			assertTrue(DragonLastStandZone.wallMoving(ends), leg + "걸음 — 끝나는 틱을 빼면 마지막에 지나쳐진 사람이 밖에 남는다");
			assertFalse(DragonLastStandZone.wallMoving(ends + 1), leg + "걸음 — 멈춘 벽에서 옮긴다");
		}
		assertFalse(DragonLastStandZone.wallMoving(5000L), "끝값 18 에 멈춘 뒤에도 옮긴다");
		// 멈춘 벽이 115초 가운데 대부분이다 — 움직이는 틱은 진입 41 + 걸음마다 101 이다.
		long moving = 0L;
		for (long elapsed = 0L; elapsed <= 2400L; elapsed++) {
			if (DragonLastStandZone.wallMoving(elapsed)) {
				moving++;
			}
		}
		assertEquals(3L * (DragonLastStandZone.SHRINK_TICKS + 1), moving);
		assertTrue(classBytes().contains("wallMoving"), "tick 이 움직임을 묻지 않고 옮긴다");
	}

	/**
	 * ⚠ <b>멈춘 벽 <u>너머</u>에 선 사람은 옮긴다</b>(2026-10-18 밤, 공개 배포 직전 검토). 몸이 벽에 걸친 사람은
	 * 그대로 두고 중심이 완전히 밖인 사람만이다 — 혼자 끊었다가 보더가 줄어든 뒤 접속한 사람이나 진주로 벽에 걸친
	 * 뒤 걸어 나간 사람이 밖에 서서 번개 · 비 · 브레스를 피하며 활을 쏘던 길이다.
	 */
	@Test
	void 멈춘_벽_너머에_선_사람만_옮긴다() {
		double half = 32.0;
		// 멈춘 벽 — 기대거나 넉백으로 밀려 몸이 걸친 사람(중심은 안)은 그대로다.
		assertFalse(DragonLastStandZone.pulls(false, half - 0.2, 0.0, half), "멈춘 벽에 기댄 사람을 옮긴다");
		assertFalse(DragonLastStandZone.pulls(false, 0.0, -half, half), "벽 위에 딱 선 사람을 옮긴다");
		assertTrue(DragonLastStandZone.crossed(half - 0.2, 0.0, half), "그 사람은 벽에 걸친 사람이다");
		// 멈춘 벽 — 중심이 밖이면 옮긴다.
		assertTrue(DragonLastStandZone.pulls(false, half + 0.1, 0.0, half), "벽 너머에 선 사람을 안 옮긴다");
		assertTrue(DragonLastStandZone.pulls(false, 5.0, -(half + 20.0), half), "한참 밖에 선 사람을 안 옮긴다");
		// 움직이는 벽 — 지금처럼 몸이 걸린 사람까지.
		assertTrue(DragonLastStandZone.pulls(true, half - 0.2, 0.0, half), "줄어드는 벽에 걸린 사람을 안 옮긴다");
		assertFalse(DragonLastStandZone.pulls(true, half - 1.0, 0.0, half), "벽에 안 닿은 사람을 옮긴다");
		assertTrue(classBytes().contains("pulls"), "tick 이 이 판단을 안 지난다");
	}

	/**
	 * ⚠ <b>보더는 네모다.</b> 지나친 사람 판정도 네모이고 몸 반폭(0.3)이 벽에 걸리면 지나친 것이다.
	 *
	 * <p>반변 18 에서 (17, 17) 은 중앙에서 24칸이지만 <b>안</b>이다 — 원으로 재면 밖이었다.
	 */
	@Test
	void 지나친_판정이_네모다() {
		assertFalse(DragonLastStandZone.crossed(17.0, 17.0, 18.0), "모서리 쪽 안이 밖으로 읽혔다");
		assertFalse(DragonLastStandZone.crossed(0.0, -17.6, 18.0));
		assertTrue(DragonLastStandZone.crossed(17.8, 0.0, 18.0), "몸이 벽에 걸렸는데 안으로 읽혔다");
		assertTrue(DragonLastStandZone.crossed(-5.0, 19.0, 18.0), "밖이 안으로 읽혔다");
		// 네모 벽까지의 거리 — 축 방향은 반변 그대로, 대각선은 √2 배다.
		assertEquals(15.0, DragonLastStandZone.squareReach(1.0, 0.0, 15.0), 1.0E-9);
		double diagonal = Math.sqrt(0.5);
		assertEquals(15.0 * Math.sqrt(2.0), DragonLastStandZone.squareReach(diagonal, -diagonal, 15.0),
				1.0E-9);
		for (int degrees = 0; degrees < 360; degrees++) {
			double radians = Math.toRadians(degrees);
			double reach = DragonLastStandZone.squareReach(Math.cos(radians), Math.sin(radians), 15.0);
			assertFalse(DragonLastStandZone.crossed(Math.cos(radians) * reach * 0.97,
							Math.sin(radians) * reach * 0.97, 15.0 + 3.0),
					degrees + "도 — 내려놓는 자리가 다시 벽에 걸린다");
		}
	}

	// ------------------------------------------------------------------ 원 판정

	/**
	 * 진입 데려오기의 판정은 <b>원</b>이다(반경 39). 원 안이면 반변 42 네모 안이기도 하다.
	 *
	 * <p>반경 12 원에서 (11, 11) 은 중앙에서 15.6칸이라 <b>밖</b>이다 — 사각형이었다면 안이었다.
	 */
	@Test
	void 판정이_원이다() {
		assertFalse(DragonLastStandZone.outside(0.0, 0.0, 12.0), "중앙이 밖이다");
		assertFalse(DragonLastStandZone.outside(12.0, 0.0, 12.0), "경계 위는 안이다");
		assertFalse(DragonLastStandZone.outside(0.0, -12.0, 12.0), "경계 위는 안이다");
		assertTrue(DragonLastStandZone.outside(12.01, 0.0, 12.0), "경계 바로 밖이 안으로 읽혔다");
		assertTrue(DragonLastStandZone.outside(11.0, 11.0, 12.0),
				"사각형 모서리 쪽이 안으로 읽혔다 — 판정이 아직 「반변 안」이다");
		assertTrue(DragonLastStandZone.outside(-8.5, 8.5, 12.0), "대각선 12.02칸이 안으로 읽혔다");
		assertFalse(DragonLastStandZone.outside(-8.4, 8.4, 12.0), "대각선 11.88칸이 밖으로 읽혔다");
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

	/**
	 * 반변 시간표를 <b>틱 단위로</b> 못박는다. 보더 구동과 HUD 가 이 한 함수를 함께 본다.
	 *
	 * <p>40초까지 42 · 40~45초에 32 로 · 85초까지 32 · 85~90초에 22 로 · 110초까지 22 ·
	 * 110~115초에 18 로 · 그 뒤 18.
	 */
	@Test
	void 반경_시간표() {
		long[][] table = {
				{-160L, 42}, {0L, 42}, {799L, 42}, {800L, 42}, {900L, 32}, {1699L, 32},
				{1700L, 32}, {1800L, 22}, {2199L, 22}, {2200L, 22}, {2300L, 18}, {9999L, 18},
		};
		for (long[] row : table) {
			assertEquals((double) row[1], DragonLastStandZone.radiusAt(row[0]), 1.0E-9,
					row[0] + "틱의 반경");
		}
		// 축소 한가운데는 정확히 반이다(선형).
		assertEquals(37.0, DragonLastStandZone.radiusAt(850L), 1.0E-9);
		assertEquals(27.0, DragonLastStandZone.radiusAt(1750L), 1.0E-9);
		assertEquals(20.0, DragonLastStandZone.radiusAt(2250L), 1.0E-9);
	}

	/** 반경이 <b>줄기만</b> 하고 끝값이 18 이다. 되감긴 판에서도 시작값을 돌려준다. */
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
		assertEquals(18.0, DragonLastStandZone.radiusAt(2300L), 0.0001, "115초에 반변 18 이다");
		assertEquals(18.0, DragonLastStandZone.radiusAt(9999L), 0.0001, "그 뒤로는 그대로다");
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

	// ------------------------------------------------------------------ 밖 피해는 없다 (2026-10-18)

	/**
	 * ⚠⚠ <b>밖 피해 · 피해 증가 · 넉백 유예 · 심장 박동이 없다.</b> 사람 말 「넘어가서 딜 입히는 건 폐기」.
	 *
	 * <p>2026-10-04 ~ 10-18 에는 원 밖이면 초당 8(115초 뒤 5초마다 +1.6)을 한 사람에게 넣고 밖에 선
	 * 사람에게 {@code WARDEN_HEARTBEAT} 를 들려줬다. 그 셈을 붙들던 시험이 여기 있었고, 이제는 <b>되살아나지
	 * 않음</b>을 붙든다. 보더 자체의 칸당 피해 0 은 {@code DragonTrialBorderTest} 가 붙든다.
	 */
	@Test
	void 밖_피해가_없다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("hurtServer"), "지대가 사람을 때린다");
		assertFalse(bytes.contains("lightningBolt"), "지대에 피해원이 남았다");
		assertFalse(bytes.contains("WARDEN_HEARTBEAT"), "밖이 없는데 심장 박동이 남았다");
		for (var method : DragonLastStandZone.class.getDeclaredMethods()) {
			assertFalse(method.getName().equals("outsideDamage") || method.getName().equals("punish")
							|| method.getName().equals("noteShoved"),
					"밖 피해의 셈(" + method.getName() + ")이 남았다");
		}
		assertFalse(read("/com/sharedfate/sync/DragonLastStandPatterns.class").contains("noteShoved"),
				"날개가 아직 밖 피해 유예를 적는다");
	}

	/**
	 * ⚠⚠ <b>이 페이즈의 한 틱 최악이 400 을 넘지 않는다.</b>
	 *
	 * <p>전에는 {@code DragonLastStandZoneWallTest} 가 「패턴 + 파도 + 벽」을 붙들었다. 2026-10-18 에 벽이
	 * 보더(점 0)로 바뀌어 그 파일이 지워졌고 남은 두 값의 합을 여기서 붙든다 — 지금 346 + 37 = 383(2026-10-18 날갯짓에 종말의 비를 얹기 전 322 + 37 = 359).
	 * 남의 값을 숫자로 적지 않고 그쪽 함수에서 읽으므로 누가 어느 쪽을 올려도 여기서 먼저 멈춘다. 예산의
	 * 위 끝은 {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)이고, 「종말의 비」·「연쇄 포격」이 그 위
	 * 끝에 붙어 있어 이 페이즈는 <b>아래 끝 400</b>을 지킨다.
	 */
	@Test
	void 페이즈_한_틱_최악이_400을_넘지_않는다() {
		int total = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick();
		assertTrue(total <= 400, "패턴 + 파도 = " + total + " — 400 을 넘었다");
		assertTrue(total <= TrialLandingShock.MAX_POINTS_PER_TICK, "예산의 위 끝마저 넘었다");
	}

	// ------------------------------------------------------------------ 보더를 만지는 곳은 하나

	/**
	 * ⚠⚠ <b>보더를 만지는 것은 {@code DragonTrialBorder} 한 곳이다.</b>
	 *
	 * <p>지대 · 진입 연출 · 최후의 저항은 {@code WorldBorder} 를 직접 부르지 않는다. 기억 · 되돌림 · 반변을
	 * 한 변으로 바꾸는 곱이 한 파일에 있어야 「한쪽만 고쳐진다」가 안 생긴다. 오버월드 스폰 보더
	 * ({@code PreStartRestrictions})는 다른 기능이라 이 시험의 대상이 아니다.
	 */
	@Test
	void 보더를_만지는_곳은_하나다() {
		for (String path : new String[] {
				"/com/sharedfate/sync/DragonLastStandZone.class",
				"/com/sharedfate/sync/DragonLastStandEntry.class",
				"/com/sharedfate/sync/DragonLastStand.class",
				"/com/sharedfate/sync/DragonLastStand$Stand.class",
				"/com/sharedfate/sync/DragonTrialManager.class"}) {
			String bytes = read(path);
			assertFalse(bytes.contains("getWorldBorder"), path + " 가 보더를 직접 읽는다");
			assertFalse(bytes.contains("lerpSizeBetween"), path + " 가 보더를 직접 줄인다");
		}
		String border = read("/com/sharedfate/sync/DragonTrialBorder.class");
		assertTrue(border.contains("getWorldBorder") && border.contains("lerpSizeBetween"),
				"DragonTrialBorder 가 보더를 안 만진다");
	}

	/** 받은 {@code now} 를 쓴다. {@code getGameTime} 을 스스로 읽으면 얼어붙은 판에서 시계가 멈춘다. */
	@Test
	void 시간을_스스로_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"받은 now 를 쓸 것 — 그것이 이 저장소의 규약이다");
	}

	/** 자막을 쓰지 않는다. 신호는 보더(바닐라 경고)와 옮겨질 때의 소리·입자뿐이다. */
	@Test
	void 자막을_쓰지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("showTitle"));
		assertFalse(bytes.contains("showActionBar"));
	}

	// ------------------------------------------------------------------ 배선

	/**
	 * {@code DragonLastStand} 가 이 시계를 실제로 몰고, 지대가 보더를 세우고, 지나친 사람을 진입
	 * 데려오기와 <b>같은 길</b>로 옮기는가.
	 */
	@Test
	void 최후의_저항이_지대를_몰고_지대가_보더를_몬다() {
		String bytes = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandZone"),
				"DragonLastStand 가 안전지대를 부르지 않는다 — 보더가 아예 안 줄어든다");
		String zone = classBytes();
		assertTrue(zone.contains("com/sharedfate/sync/DragonTrialBorder"), "지대가 보더를 안 세운다");
		assertTrue(zone.contains("shrink") && zone.contains("hold"), "지대가 보더를 줄이거나 세우지 않는다");
		assertTrue(zone.contains("pullTarget"),
				"지나친 사람을 옮기는 길이 진입 데려오기와 갈렸다 — 높이 조건(PULL_MAX_RISE)이 두 벌이 된다");
		assertTrue(zone.contains("teleportTo"), "지나친 사람을 옮기지 않는다 — 「아예 못 넘어가게」가 깨진다");
		assertFalse(zone.contains("DragonLastStandZoneWall"), "걷은 빨간 기둥 벽을 아직 부른다");
	}

	/**
	 * {@code SharedFateMod} 가 종료 훅을 여전히 등록하는가. 엔드 보더를 저장보다 먼저 되돌리고
	 * 빨간 면·신호기 같은 <b>개체</b>를 월드가 살아 있는 자리에서 거둔다.
	 */
	@Test
	void 종료_훅이_등록되어_있다() {
		String bytes = read("/com/sharedfate/SharedFateMod.class");
		assertTrue(bytes.contains("onServerStopping"),
				"SharedFateMod 에 DragonLastStand::onServerStopping 이 없다");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStand"),
				"SharedFateMod 가 DragonLastStand 를 가리키지 않는다");
		assertTrue(read("/com/sharedfate/sync/DragonLastStand.class")
						.contains("com/sharedfate/sync/DragonTrialBorder"),
				"종료 훅이 보더를 되돌리지 않는다 — 줄어든 보더가 저장 파일에 남는다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 지금 <b>정말로</b> 줄어드는 중인가. 시계의 넓은 답(미리 잠그는 몫)을 빼고 본다. */
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
