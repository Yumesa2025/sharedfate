package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「가르는 브레스」에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>파티클·소리·피해는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 패시브가
 * 망가지는 길은 대부분 기하와 타이밍 쪽이다 — <b>선이 아레나를 안 가른다</b>, <b>점이 성겨 선이
 * 점선으로 보인다</b>, <b>장판이 안 꺼진다</b>, <b>예고가 고를 시간보다 짧다</b>, <b>지난 판의
 * 선이 새 월드에서 탄다</b>. 전멸하면 월드가 지워지는 게임이라 돌려 보고 발견할 수 없다.
 */
class DragonRiftBreathTest {

	/** 아레나를 지름으로 가르므로 선 길이는 반경의 두 배다. */
	private static final double LENGTH = TrialRisks.ARENA_RADIUS * 2.0;
	/** 엔드 중앙. 선은 여기를 지나야 「반으로 갈린다」가 성립한다. */
	private static final Vec3 CENTRE = new Vec3(0.0, 0.0, 0.0);
	/** 팀 공유 체력. 한 틱에 이만큼 들어가면 가득 찬 상태에서도 즉사다. */
	private static final float TEAM_HEALTH = 20.0F;

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonRiftBreath.clearState();
	}

	// ------------------------------------------------------------------ 선이 아레나를 가르는가

	@Test
	void 선이_중앙을_지나고_양_끝이_아레나_경계에_닿는다() {
		for (double roll = 0.0; roll <= 1.0; roll += 0.05) {
			DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, roll);
			assertEquals(TrialRisks.ARENA_RADIUS, rift.from().distanceTo(CENTRE), 1.0E-9,
					"출발점이 경계에 안 닿으면 그 바깥이 안전지대가 된다: 굴림 " + roll);
			assertEquals(TrialRisks.ARENA_RADIUS, rift.to().distanceTo(CENTRE), 1.0E-9,
					"도착점이 경계에 안 닿으면 끝으로 돌아갈 수 있다: 굴림 " + roll);
			assertEquals(0.0,
					DragonRiftBreath.distanceToSegment(CENTRE, rift.from(), rift.to()), 1.0E-9,
					"중앙을 안 지나면 「반으로 갈린다」가 아니라 「구석이 잘린다」다: 굴림 " + roll);
			assertEquals(LENGTH, rift.from().distanceTo(rift.to()), 1.0E-9);
		}
	}

	@Test
	void 선은_평평하다() {
		// 높이는 지면을 읽을 때 얹는다. 기하 단계에서 y 가 섞이면 폭 계산이 비스듬해진다.
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.3);
		assertEquals(0.0, rift.from().y, 1.0E-9);
		assertEquals(0.0, rift.to().y, 1.0E-9);
		for (Vec3 point : rift.leftEdge()) {
			assertEquals(0.0, point.y, 1.0E-9);
		}
	}

	@Test
	void 각도가_매번_같지_않다() {
		RandomSource random = RandomSource.create(20260929L);
		Set<Long> angles = new HashSet<>();
		for (int round = 0; round < 200; round++) {
			Vec3 axis = DragonRiftBreath.axisFor(random.nextDouble());
			// 소수점 세 자리로 뭉쳐 센다. 부동소수 잡음까지 「다르다」로 세면 시험이 무의미하다.
			angles.add(Math.round(Math.atan2(axis.z, axis.x) * 1000.0));
		}
		assertTrue(angles.size() > 150,
				"각도가 몰리면 같은 자리만 계속 갈린다. 서로 다른 각도 " + angles.size() + "개");
	}

	@Test
	void 반_바퀴만_써도_모든_방향의_선이_나온다() {
		// 선은 양방향이라 θ 와 θ+π 가 같은 선이다. 굴림 0 과 1 은 같은 선이고 출발점만 뒤집힌다.
		DragonRiftBreath.Rift head = DragonRiftBreath.plan(0L, 0.0);
		DragonRiftBreath.Rift tail = DragonRiftBreath.plan(0L, 1.0);
		assertEquals(head.from().x, tail.to().x, 1.0E-9);
		assertEquals(head.from().z, tail.to().z, 1.0E-9);
	}

	// ------------------------------------------------------------------ 점이 선으로 읽히는가

	@Test
	void 점_수에_상한이_있다() {
		assertEquals(0, DragonRiftBreath.linePoints(0.0), "길이가 없으면 점도 없다");
		assertEquals(0, DragonRiftBreath.linePoints(-5.0));
		assertTrue(DragonRiftBreath.linePoints(LENGTH) > 0);

		// 상한은 「약속한 길이 ÷ 허용 간격」이다. 숫자를 박아 두면 둘 중 하나만 고쳐질 때
		// 이 시험이 그것을 놓친다.
		int cap = (int) Math.ceil(DragonRiftBreath.LINE_KEPT_LENGTH / DragonRiftBreath.LINE_MAX_GAP)
				+ 1;
		assertEquals(cap, DragonRiftBreath.LINE_MAX_POINTS);
		assertTrue(DragonRiftBreath.linePoints(10_000.0) <= cap,
				"상한이 없으면 선 하나가 파티클만으로 틱을 민다");
		assertEquals(DragonRiftBreath.linePoints(10_000.0), DragonRiftBreath.linePoints(500.0),
				"상한에 닿은 뒤로는 더 늘지 않는다");
	}

	@Test
	void 점_사이가_선으로_읽힐_만큼만_벌어진다() {
		for (double length = 0.5; length <= DragonRiftBreath.LINE_KEPT_LENGTH; length += 0.5) {
			assertTrue(DragonRiftBreath.lineGap(length) <= DragonRiftBreath.LINE_MAX_GAP + 1.0E-9,
					"길이 " + length + " 에서 점이 " + DragonRiftBreath.lineGap(length)
							+ " 칸씩 벌어진다 — 벽이 아니라 점선으로 보인다");
		}
		assertTrue(DragonRiftBreath.lineGap(LENGTH) <= DragonRiftBreath.LINE_MAX_GAP + 1.0E-9,
				"실제로 쓰는 길이에서의 간격: " + DragonRiftBreath.lineGap(LENGTH));
	}

	@Test
	void 점은_끝에서_끝까지_고르게_놓인다() {
		Vec3 from = new Vec3(-40.0, 0.0, 0.0);
		Vec3 to = new Vec3(40.0, 0.0, 0.0);
		int points = DragonRiftBreath.linePoints(LENGTH);
		assertEquals(from, DragonRiftBreath.pointOn(from, to, 0, points), "첫 점이 출발점이다");
		assertEquals(to, DragonRiftBreath.pointOn(from, to, points - 1, points),
				"끝점이 모자라면 아레나 경계에 틈이 생기고 사람은 그리로 지나가려 한다");
		assertEquals(CENTRE.x,
				DragonRiftBreath.pointOn(from, to, (points - 1) / 2, points).x,
				DragonRiftBreath.LINE_MAX_GAP);
	}

	@Test
	void 두_경계선이_폭만큼_떨어져_있다() {
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.37);
		List<Vec3> left = rift.leftEdge();
		List<Vec3> right = rift.rightEdge();
		assertEquals(left.size(), right.size());
		assertEquals(DragonRiftBreath.linePoints(LENGTH), left.size());
		for (int index = 0; index < left.size(); index++) {
			assertEquals(DragonRiftBreath.HALF_WIDTH,
					DragonRiftBreath.distanceToSegment(left.get(index), rift.from(), rift.to()),
					1.0E-9, "왼쪽 경계가 폭 밖에 그려지면 「어디부터 아픈가」가 거짓말이 된다");
			assertEquals(DragonRiftBreath.HALF_WIDTH,
					DragonRiftBreath.distanceToSegment(right.get(index), rift.from(), rift.to()),
					1.0E-9);
			assertEquals(DragonRiftBreath.HALF_WIDTH * 2.0,
					left.get(index).distanceTo(right.get(index)), 1.0E-9);
		}
	}

	// ------------------------------------------------------------------ 어디까지가 장판인가

	@Test
	void 장판_판정은_선분이지_무한_직선이_아니다() {
		Vec3 from = new Vec3(-40.0, 0.0, 0.0);
		Vec3 to = new Vec3(40.0, 0.0, 0.0);
		assertTrue(DragonRiftBreath.insideField(new Vec3(0.0, 63.0, 0.0), from, to,
				DragonRiftBreath.HALF_WIDTH));
		assertTrue(DragonRiftBreath.insideField(new Vec3(40.0, 63.0, 0.0), from, to,
				DragonRiftBreath.HALF_WIDTH), "끝점은 장판이다");
		assertFalse(DragonRiftBreath.insideField(new Vec3(45.0, 63.0, 0.0), from, to,
						DragonRiftBreath.HALF_WIDTH),
				"연장선까지 아프면 브레스가 닿은 적 없는 자리가 타는 것이다");
	}

	@Test
	void 폭_밖에_서_있으면_맞지_않는다() {
		Vec3 from = new Vec3(-40.0, 0.0, 0.0);
		Vec3 to = new Vec3(40.0, 0.0, 0.0);
		double half = DragonRiftBreath.HALF_WIDTH;
		assertTrue(DragonRiftBreath.insideField(new Vec3(0.0, 63.0, half), from, to, half),
				"경계는 안이다 — 표식과 판정이 어긋나면 안 된다");
		assertFalse(DragonRiftBreath.insideField(new Vec3(0.0, 63.0, half + 0.01), from, to, half));
	}

	@Test
	void 높이는_보지_않는다() {
		// 표식은 바닥에 그려진다. 「선 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이다.
		Vec3 from = new Vec3(-40.0, 0.0, 0.0);
		Vec3 to = new Vec3(40.0, 0.0, 0.0);
		assertTrue(DragonRiftBreath.insideField(new Vec3(0.0, 120.0, 0.0), from, to,
				DragonRiftBreath.HALF_WIDTH));
	}

	// ------------------------------------------------------------------ 15초 뒤 사라진다

	@Test
	void 장판이_정확히_15초_뒤_사라진다() {
		assertEquals(15 * 20, DragonRiftBreath.FIELD_TICKS, "카드에 적힌 것은 15초다");
		long lit = 10_000L;
		assertTrue(DragonRiftBreath.fieldAlive(lit, lit), "붙는 그 틱부터 장판이다");
		assertTrue(DragonRiftBreath.fieldAlive(lit, lit + DragonRiftBreath.FIELD_TICKS - 1),
				"마지막 틱까지는 살아 있어야 15초다");
		assertFalse(DragonRiftBreath.fieldAlive(lit, lit + DragonRiftBreath.FIELD_TICKS),
				"한 틱이라도 더 남으면 「15초 뒤 사라진다」가 거짓말이다");
		assertFalse(DragonRiftBreath.fieldAlive(lit, lit + DragonRiftBreath.FIELD_TICKS + 500));
	}

	@Test
	void 붙지_않은_선은_아무도_태우지_않는다() {
		assertFalse(DragonRiftBreath.fieldAlive(DragonRiftBreath.NOT_IGNITED, 10_000L),
				"예고 중에 아프면 예고가 아니라 공격이다");
		assertFalse(DragonRiftBreath.pulsesAt(DragonRiftBreath.NOT_IGNITED, 10_000L));
	}

	@Test
	void 시간이_거꾸로_가도_장판이_되살아나지_않는다() {
		// 서버를 재시작하면 게임 시각이 메모리에 남은 값보다 작을 수 있다.
		long lit = 10_000L;
		assertFalse(DragonRiftBreath.fieldAlive(lit, lit - 1));
	}

	// ------------------------------------------------------------------ 피해

	@Test
	void 한_틱에_받을_수_있는_가장_큰_피해가_팀_체력에_못_미친다() {
		assertTrue(DragonRiftBreath.worstCaseTickDamage() < TEAM_HEALTH,
				"「즉사 메커닉 0개」가 깨졌다. 실제 값: " + DragonRiftBreath.worstCaseTickDamage());
		assertEquals(DragonRiftBreath.DAMAGE_PER_PULSE * DragonRiftBreath.MAX_CONCURRENT_RIFTS,
				DragonRiftBreath.worstCaseTickDamage(), 1.0E-6);
	}

	@Test
	void 주기가_예고와_장판을_합친_것보다_길어_선이_겹치지_않는다() {
		int occupied = DragonRiftBreath.LEAD_TICKS + DragonRiftBreath.FIELD_TICKS;
		assertTrue(DragonRiftBreath.PERIOD_TICKS > occupied,
				"선 둘이 동시에 살아 있으면 교차점이 한 틱에 두 번 맞는 자리가 되고, "
						+ "worstCaseTickDamage 가 거짓이 된다. 주기 "
						+ DragonRiftBreath.PERIOD_TICKS + ", 차지 " + occupied);
		assertEquals(1, DragonRiftBreath.MAX_CONCURRENT_RIFTS,
				"주기를 줄였으면 이 숫자와 worstCaseTickDamage 를 함께 고쳐야 한다");
	}

	@Test
	void 장판_피해가_초당_4다() {
		double perSecond = DragonRiftBreath.DAMAGE_PER_PULSE * 20.0
				/ DragonRiftBreath.DAMAGE_PERIOD_TICKS;
		assertEquals(4.0, perSecond, 1.0E-6, "카드에 적힌 것은 초당 4다");
		assertEquals(30, DragonRiftBreath.pulseCount());
		assertEquals(60.0F, DragonRiftBreath.pulseCount() * DragonRiftBreath.DAMAGE_PER_PULSE,
				1.0E-6, "끝까지 서 있으면 팀 체력의 세 배다");
	}

	@Test
	void 피해_간격이_바닐라_무적시간에_먹히지_않는다() {
		assertTrue(DragonRiftBreath.DAMAGE_PERIOD_TICKS >= 10,
				"바닐라 피격 무적시간이 10틱이다. 더 촘촘하면 그 몫이 조용히 사라진다");
		assertTrue(DragonRiftBreath.DAMAGE_PERIOD_TICKS <= 10,
				"20틱마다 때리면 달려서 건넌 사람이 한 점도 안 아프다 — 「지나가려면 아프다」가 깨진다");
	}

	@Test
	void 붙는_그_틱부터_규칙적으로_때린다() {
		long lit = 500L;
		assertTrue(DragonRiftBreath.pulsesAt(lit, lit), "붙는 순간이 첫 점이다");
		for (int after = 1; after < DragonRiftBreath.DAMAGE_PERIOD_TICKS; after++) {
			assertFalse(DragonRiftBreath.pulsesAt(lit, lit + after),
					"간격 안에서 또 때리면 초당 4 가 아니다: " + after);
		}
		assertTrue(DragonRiftBreath.pulsesAt(lit, lit + DragonRiftBreath.DAMAGE_PERIOD_TICKS));
		assertFalse(DragonRiftBreath.pulsesAt(lit, lit + DragonRiftBreath.FIELD_TICKS),
				"꺼진 뒤에도 때리면 장판이 안 사라진 것이다");
	}

	/**
	 * <b>갇히면 안 된다.</b> 장판 위에 서 있던 사람이 걸어 나오는 동안 받는 피해가 팀을 끝내지
	 * 않아야 한다. 이 패턴의 뜻은 「들어가면 죽는다」가 아니라 「지나가려면 아프다」다.
	 */
	@Test
	void 걸어서_건너는_동안_받는_피해가_팀_체력보다_훨씬_적다() {
		// 바닐라 걷기 속도는 초당 4.317 칸이다. 폭을 수직으로 가로지르는 것이 가장 짧은 길이다.
		double walkPerTick = 4.317 / 20.0;
		int crossTicks = (int) Math.ceil(DragonRiftBreath.HALF_WIDTH * 2.0 / walkPerTick);
		int pulses = crossTicks / DragonRiftBreath.DAMAGE_PERIOD_TICKS + 1;
		float taken = pulses * DragonRiftBreath.DAMAGE_PER_PULSE;
		assertTrue(taken < TEAM_HEALTH / 2.0F,
				"건너는 데 " + crossTicks + "틱, 받는 피해 " + taken
						+ " — 팀 체력의 절반을 넘으면 「못 지나간다」이지 「아프다」가 아니다");
		assertTrue(taken > 0.0F, "한 점도 안 아프면 벽이 아니다");
	}

	// ------------------------------------------------------------------ 예고

	@Test
	void 예고가_요구하는_행동의_최소_예고보다_길다() {
		// 이 패턴이 요구하는 것은 「어느 쪽에 남을지 고르기」다. 넷이 서로 보고 갈라서야 하므로
		// 기준은 옆걸음(30)이나 자리 이동(50)이 아니라 흩어지기(80)다.
		assertTrue(DragonRiftBreath.LEAD_TICKS > TrialWarning.TICKS_SCATTER,
				"필요 " + TrialWarning.TICKS_SCATTER + ", 실제 " + DragonRiftBreath.LEAD_TICKS
						+ " — 짧으면 보여 준 것이 예고가 아니라 사후 통보다");
		assertTrue(DragonRiftBreath.MIN_LEAD_TICKS >= TrialWarning.TICKS_SCATTER,
				"늦게 긋는 길에도 같은 하한이 걸려 있어야 한다");
		assertTrue(DragonRiftBreath.LEAD_TICKS >= DragonRiftBreath.MIN_LEAD_TICKS);
	}

	@Test
	void 경고_세_층이_모두_나갈_만큼_예고가_길다() {
		int firstStageAt = 0;
		for (int remaining = 0; remaining <= 1000; remaining++) {
			if (TrialWarning.stageFor(remaining) != null) {
				firstStageAt = Math.max(firstStageAt, remaining);
			}
		}
		assertTrue(DragonRiftBreath.LEAD_TICKS > firstStageAt,
				"예고가 " + firstStageAt + " 이하면 「뭔가 온다」 층이 통째로 빠진다. 실제 "
						+ DragonRiftBreath.LEAD_TICKS);

		Set<TrialWarning.Stage> seen = EnumSet.noneOf(TrialWarning.Stage.class);
		for (int remaining = DragonRiftBreath.LEAD_TICKS; remaining >= 0; remaining--) {
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage != null && TrialRisks.stageJustChanged(remaining)) {
				seen.add(stage);
			}
		}
		assertEquals(EnumSet.allOf(TrialWarning.Stage.class), seen,
				"층이 빠지면 한 층을 놓친 사람을 다음 층이 못 잡는다");
	}

	@Test
	void 자막이_예고_안에서_나간다() {
		assertTrue(TrialWarning.TICKS_SIDESTEP < DragonRiftBreath.LEAD_TICKS,
				"자막을 띄우는 시점이 예고 밖이면 영영 안 뜬다");
		assertTrue(DragonRiftBreath.SWEEP_TICKS < TrialWarning.TICKS_SIDESTEP,
				"자막보다 브레스가 먼저 지나가면 「비키십시오」가 지나간 뒤에 뜬다");
	}

	// ------------------------------------------------------------------ 쓸고 지나가기

	@Test
	void 브레스가_반대편에_닿는_틱과_장판이_붙는_틱이_같다() {
		long granted = 1_000L;
		long impact = granted + DragonRiftBreath.PERIOD_TICKS;
		assertTrue(TrialRisks.firesAt(impact, granted, DragonRiftBreath.PERIOD_TICKS));
		assertEquals(0, TrialRisks.remainingTicks(impact, granted, DragonRiftBreath.PERIOD_TICKS));
		assertEquals(1.0, DragonRiftBreath.sweepProgress(0), 1.0E-9,
				"닿기 전에 붙으면 「지나간 자리가 장판이 된다」가 눈에 안 맞는다");
	}

	@Test
	void 쓸고_지나가는_비율이_영에서_일까지_한_번만_오른다() {
		assertEquals(0.0, DragonRiftBreath.sweepProgress(DragonRiftBreath.SWEEP_TICKS), 1.0E-9,
				"출발하자마자 중간에 있으면 어느 쪽에서 왔는지 못 읽는다");
		assertEquals(0.0, DragonRiftBreath.sweepProgress(DragonRiftBreath.SWEEP_TICKS + 50), 1.0E-9,
				"쓸기 구간 밖에서는 아직 출발 전이다");
		double previous = -1.0;
		for (int remaining = DragonRiftBreath.SWEEP_TICKS; remaining >= 0; remaining--) {
			double progress = DragonRiftBreath.sweepProgress(remaining);
			assertTrue(progress >= 0.0 && progress <= 1.0, "범위 밖: " + remaining);
			assertTrue(progress > previous, "멈추거나 되돌아가면 브레스가 공중에 선다: " + remaining);
			previous = progress;
		}
		assertEquals(1.0, previous, 1.0E-9);
	}

	@Test
	void 브레스는_출발점과_도착점을_지나치지_않는다() {
		Vec3 from = new Vec3(-40.0, 63.0, 0.0);
		Vec3 to = new Vec3(40.0, 63.0, 0.0);
		assertEquals(from, DragonRiftBreath.headAt(from, to, -1.0));
		assertEquals(to, DragonRiftBreath.headAt(from, to, 2.0));
		assertEquals(new Vec3(0.0, 63.0, 0.0), DragonRiftBreath.headAt(from, to, 0.5));
	}

	// ------------------------------------------------------------------ 상태가 새지 않는가

	@Test
	void 선이_clearState_로_깨끗이_지워진다() {
		assertNull(DragonRiftBreath.active(), "처음에는 아무 선도 없어야 한다");

		DragonRiftBreath.remember(DragonRiftBreath.plan(7L, 0.42));
		assertNotNull(DragonRiftBreath.active());

		DragonRiftBreath.clearState();
		assertNull(DragonRiftBreath.active(),
				"남으면 새 월드에서 아무도 그은 적 없는 자리가 탄다");
	}

	@Test
	void 끝난_주기_기록도_함께_지워진다() {
		// 주기 번호가 남아 있으면 새 판의 같은 번호 주기가 「이미 판단했다」로 건너뛰어져
		// 그 판의 첫 브레스가 통째로 빠진다.
		DragonRiftBreath.notePlanned(3L);
		assertEquals(3L, DragonRiftBreath.plannedCycle());

		DragonRiftBreath.clearState();
		assertEquals(Long.MIN_VALUE, DragonRiftBreath.plannedCycle(),
				"주기 기록이 남으면 새 판에서 첫 브레스가 건너뛰어진다");
	}

	/**
	 * 전투가 바뀌면 스스로 비운다.
	 *
	 * <p>드래곤을 잡고 다시 들어가면 주기 번호가 0 부터 다시 매겨진다. 지난 전투의 기록이
	 * 남아 있으면 새 판의 그 주기가 「이미 판단했다」로 건너뛰어져 <b>첫 브레스가 안 나온다.</b>
	 * 「가끔 안 나온다」는 아무도 못 잡는 증상이라, 배선이 비워 주기를 기다리지 않는다.
	 */
	@Test
	void 전투가_바뀌면_지난_판의_선을_스스로_버린다() {
		DragonRiftBreath.beginFight(1_000L);
		DragonRiftBreath.remember(DragonRiftBreath.plan(2L, 0.6));
		DragonRiftBreath.notePlanned(2L);

		DragonRiftBreath.beginFight(1_000L);
		assertNotNull(DragonRiftBreath.active(), "같은 전투 안에서는 아무것도 버리지 않는다");
		assertEquals(2L, DragonRiftBreath.plannedCycle());

		DragonRiftBreath.beginFight(9_999L);
		assertNull(DragonRiftBreath.active(), "새 전투인데 지난 판의 선이 남아 있다");
		assertEquals(Long.MIN_VALUE, DragonRiftBreath.plannedCycle(),
				"주기 기록이 남으면 새 판의 그 주기가 통째로 건너뛰어진다");
	}

	// ------------------------------------------------------------------ 파티클 사거리

	@Test
	void 선은_긴_거리로_나간다() throws IOException {
		// 이 패시브가 보여 주는 것은 「아레나가 갈렸다」이고, 그것은 반대편 끝까지 보여야 성립한다.
		// 짧은 형태는 32칸에서 잘리는데 이 선은 80칸이라, 되돌리면 절반이 아무에게도 안 보이고
		// 그래도 빌드와 로그는 조용하다.
		String bytes;
		try (InputStream in = DragonRiftBreath.class
				.getResourceAsStream("/com/sharedfate/sync/DragonRiftBreath.class")) {
			if (in == null) {
				throw new IOException("DragonRiftBreath 의 클래스 파일을 찾지 못했다");
			}
			bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 아레나 반대편에서는 선이 안 보인다");
	}

	/**
	 * 블록을 건드리지 않는다.
	 *
	 * <p>이 설계의 전부다. 바닥을 지우면 공허 낙사가 되고, 그것은 이 전투가 유일하게 금지한
	 * 「대응 불가 즉사」다. 블록을 <b>읽는</b> 하이트맵은 있어야 하므로 <b>쓰는</b> 쪽만 막는다.
	 */
	@Test
	void 블록을_한_칸도_바꾸지_않는다() throws IOException {
		String bytes;
		try (InputStream in = DragonRiftBreath.class
				.getResourceAsStream("/com/sharedfate/sync/DragonRiftBreath.class")) {
			if (in == null) {
				throw new IOException("DragonRiftBreath 의 클래스 파일을 찾지 못했다");
			}
			bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		assertFalse(bytes.contains("setBlock"), "블록을 놓으면 다음 전투의 발판이 달라진다");
		assertFalse(bytes.contains("destroyBlock"), "바닥을 지우면 공허 낙사다");
		assertFalse(bytes.contains("removeBlock"));
		assertTrue(bytes.contains("getHeightmapPos"), "지면 높이는 읽어야 선이 바닥에 붙는다");
	}
}
