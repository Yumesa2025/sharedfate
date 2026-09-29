package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.core.particles.ParticleTypes;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

	/**
	 * 「브레스가 아레나를 가릅니다 — 한쪽을 고르십시오」를 <b>띄우지 않는다.</b>
	 *
	 * <p>예전에는 이 자리가 「자막이 예고 안에서 나간다」였다. 사람이 액션바 글자를 「일단
	 * 없애」라고 해서 뒤집었다 — 시험을 지우면 글자가 돌아와도 아무도 모른다.
	 *
	 * <p>글자가 빠진 만큼 <b>선이 예고 내내 그려지는 것</b>이 더 중요해졌다. 이 패턴이 요구하는
	 * 행동은 「어느 쪽에 남을지 고르기」이고, 고를 정보를 주는 것이 이제 선 하나뿐이다. 그쪽은
	 * {@link #예고가_요구하는_행동의_최소_예고보다_길다} 와
	 * {@link #경고_세_층이_모두_나갈_만큼_예고가_길다} 가 지킨다.
	 */
	@Test
	void 자막을_띄우지_않는다() throws IOException {
		assertFalse(classBytes().contains("shout"),
				"액션바 자막이 돌아왔다. 걷어내기로 한 것은 글자뿐이고 소리와 표식은 그대로 둔다 —"
						+ " 되살리려면 TrialWarning 의 설명부터 함께 고칠 것");
		assertTrue(DragonRiftBreath.SWEEP_TICKS < TrialWarning.TICKS_SIDESTEP,
				"브레스가 사람이 반응할 수 있는 시간보다 먼저 지나간다. 자막이 없어진 지금은"
						+ " 이 여유가 「비킬 수 있는가」의 전부다");
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

	// ------------------------------------------------------------------ 드래곤에 선을 맞춘다

	/**
	 * 브레스가 드래곤이 있는 쪽에서 출발한다.
	 *
	 * <p>드래곤을 선에 맞추는 것이 아니라 <b>선을 드래곤에 맞춘다.</b> 드래곤을 옮기면
	 * {@code aiStep} 의 히트박스가 사람을 때리고 {@code checkWalls} 가 블록을 부수지만, 선은 우리
	 * 것이라 공짜로 돌릴 수 있다.
	 */
	@Test
	void 브레스는_드래곤이_있는_쪽_끝에서_출발한다() {
		// 굴림 0 이면 축이 +x 라 from 은 (-40,0,0), to 는 (40,0,0) 이다.
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.0);

		DragonRiftBreath.Rift far = DragonRiftBreath.startingNear(rift, new Vec3(70.0, 120.0, 0.0));
		assertEquals(rift.to(), far.from(), "드래곤 반대편에서 브레스가 나오면 누가 뿜었는지 못 읽는다");
		assertEquals(rift.from(), far.to());
		assertEquals(rift.rightEdge(), far.leftEdge(), "방향을 뒤집으면 좌우도 함께 뒤집혀야 한다");
		assertEquals(rift.leftEdge(), far.rightEdge());

		DragonRiftBreath.Rift near = DragonRiftBreath.startingNear(rift, new Vec3(-70.0, 120.0, 0.0));
		assertEquals(rift.from(), near.from(), "이미 드래곤 쪽에서 출발하면 그대로 둔다");
	}

	@Test
	void 드래곤_높이는_출발점을_고르는_데_쓰지_않는다() {
		// 드래곤은 아레나보다 수십 칸 위를 난다. 세로를 섞으면 양 끝이 똑같이 멀어져 사실상
		// 굴림이 방향을 정하게 된다.
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.0);
		assertEquals(rift.to(),
				DragonRiftBreath.startingNear(rift, new Vec3(70.0, 500.0, 0.0)).from(),
				"아무리 높이 있어도 가로로 가까운 쪽이 출발점이다");
	}

	@Test
	void 드래곤이_없으면_굴림이_정한_방향_그대로다() {
		// 드래곤이 죽었거나 아직 안 나온 틱에도 선은 그어져야 한다.
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.63);
		assertEquals(rift, DragonRiftBreath.startingNear(rift, null));
	}

	@Test
	void 방향을_뒤집어도_아픈_자리는_그대로다() {
		// 방향은 연출이고 판정은 선분이다. 뒤집는 것이 「어디가 아픈가」를 한 칸이라도 바꾸면
		// 예고로 보여 준 띠와 실제 장판이 어긋난다.
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.31);
		DragonRiftBreath.Rift flipped = DragonRiftBreath.startingNear(rift, rift.to());
		assertNotEquals(rift.from(), flipped.from(), "이 굴림에서는 실제로 뒤집혀야 시험이 뜻이 있다");
		for (double x = -50.0; x <= 50.0; x += 2.5) {
			for (double z = -50.0; z <= 50.0; z += 2.5) {
				Vec3 spot = new Vec3(x, 63.0, z);
				assertEquals(
						DragonRiftBreath.insideField(spot, rift.from(), rift.to(),
								DragonRiftBreath.HALF_WIDTH),
						DragonRiftBreath.insideField(spot, flipped.from(), flipped.to(),
								DragonRiftBreath.HALF_WIDTH),
						"뒤집었더니 판정이 달라졌다: " + spot);
			}
		}
	}

	/**
	 * 브레스 줄기가 드래곤 입에서 바닥까지 이어진다.
	 *
	 * <p>드래곤을 옮기지 않기로 했으므로 <b>몸과 장판을 잇는 것은 이 줄기뿐</b>이다. 끊기면
	 * 「드래곤이 뿜었다」가 사라지고 「어디선가 선이 그어졌다」가 된다.
	 */
	@Test
	void 브레스_줄기가_드래곤_입에서_바닥까지_이어진다() {
		Vec3 mouth = new Vec3(60.0, 120.0, -30.0);
		Vec3 ground = new Vec3(0.0, 63.5, 0.0);
		List<Vec3> stream = DragonRiftBreath.breathStream(mouth, ground);

		assertTrue(stream.size() >= 2, "점 하나는 선이 아니다");
		assertEquals(0.0, stream.getFirst().distanceTo(mouth), 1.0E-9,
				"입에서 시작하지 않으면 드래곤이 뿜은 것으로 안 보인다");
		assertEquals(0.0, stream.getLast().distanceTo(ground), 1.0E-9,
				"바닥에 안 닿으면 장판과 이어지지 않는다");

		double previous = -1.0;
		for (Vec3 point : stream) {
			double along = point.distanceTo(mouth);
			assertTrue(along > previous, "되돌아가는 점이 있으면 줄기가 아니라 뭉치다");
			previous = along;
		}
	}

	@Test
	void 줄기는_드래곤이_아무리_멀어도_점이_늘지_않는다() {
		Vec3 ground = new Vec3(0.0, 63.5, 0.0);
		assertTrue(DragonRiftBreath.breathStream(new Vec3(0.0, 400.0, 0.0), ground).size()
						<= DragonRiftBreath.STREAM_MAX_POINTS,
				"드래곤이 멀수록 패킷이 늘면, 하필 가장 안 보이는 때가 가장 비싸다");
		assertEquals(2, DragonRiftBreath.breathStream(ground, ground).size(),
				"입이 바닥에 닿아 있어도 점 둘은 있어야 선이다");
	}

	// ------------------------------------------------------------------ 장판은 잔류 구름이다

	/**
	 * 예고와 장판이 서로 다른 모습이다.
	 *
	 * <p>표식은 색 규약에서 「곧 온다」는 뜻이라, 남은 장판을 표식으로 그리면 「아직 안 터진
	 * 건가」로 읽혀 사람이 그 위를 그냥 걸어 들어간다. 장판은 바닐라 잔류 구름의 생김새를
	 * 빌려야 설명이 필요 없다.
	 */
	@Test
	void 예고와_장판이_서로_다른_모습이다() {
		TestBootstrap.ensureInitialized();
		DragonRiftBreath.Rift planned = DragonRiftBreath.plan(0L, 0.2);
		assertEquals(DragonRiftBreath.Look.WARNING, DragonRiftBreath.lookFor(planned),
				"붙기 전에는 예고다");
		assertEquals(DragonRiftBreath.Look.LINGER, DragonRiftBreath.lookFor(planned.ignitedAt(100L)),
				"붙은 뒤에는 장판이다");
		assertNotEquals(DragonRiftBreath.mark(DragonRiftBreath.Look.WARNING).getType(),
				DragonRiftBreath.mark(DragonRiftBreath.Look.LINGER).getType(),
				"둘이 같은 파티클이면 사람이 언제 피해야 하는지 배울 수 없다");
		assertEquals(ParticleTypes.DRAGON_BREATH,
				DragonRiftBreath.mark(DragonRiftBreath.Look.LINGER).getType(),
				"26.3 DragonSittingFlamingPhase 가 제 잔류 구름에 넣는 바로 그 파티클이어야 한다");
	}

	@Test
	void 장판_파티클이_일반_잔류_포션이_아니라_드래곤_것이다() throws IOException {
		String bytes = classBytes();
		assertTrue(bytes.contains("DRAGON_BREATH"), "장판이 잔류 구름으로 안 보인다");
		assertTrue(bytes.contains("PowerParticleOption"),
				"세기 없는 형태로 되돌아가면 바닐라 구름과 크기·속도가 어긋난다");
		assertFalse(bytes.contains("ENTITY_EFFECT"),
				"그쪽은 일반 잔류 포션 구름의 것이다 — 이 선을 그은 것은 드래곤이다");
		assertTrue(bytes.contains("dust"), "예고는 색 규약의 빨간 표식으로 남아 있어야 한다");
	}

	/**
	 * 장판이 선이 아니라 면이다.
	 *
	 * <p>폭이 6칸인데 경계 두 줄만 그으면 여전히 「금」으로 보이고, 금은 밟는 것이 아니라 넘는
	 * 것이다.
	 */
	@Test
	void 장판이_폭만큼_면을_채운다() {
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.37);
		List<Vec3> fill = DragonRiftBreath.fieldFill(rift.leftEdge(), rift.rightEdge());
		assertFalse(fill.isEmpty(), "안쪽이 비면 선 하나지 장판이 아니다");

		boolean inner = false;
		for (Vec3 point : fill) {
			double across = DragonRiftBreath.distanceToSegment(point, rift.from(), rift.to());
			assertTrue(across <= DragonRiftBreath.HALF_WIDTH + 1.0E-9,
					"장판 밖에 뿌리면 「어디부터 아픈가」가 거짓말이 된다: " + across);
			if (across > 1.0E-9 && across < DragonRiftBreath.HALF_WIDTH - 1.0E-9) {
				inner = true;
			}
		}
		assertTrue(inner, "경계 위에만 찍으면 채운 것이 아니다");
	}

	@Test
	void 장판_점_수에_상한이_있고_경계는_상한_밖이다() {
		DragonRiftBreath.Rift rift = DragonRiftBreath.plan(0L, 0.37);
		assertTrue(DragonRiftBreath.fieldFill(rift.leftEdge(), rift.rightEdge()).size()
						<= DragonRiftBreath.FILL_MAX_POINTS,
				"면은 점이 제곱으로 는다. 상한이 없으면 장판 하나가 파티클만으로 틱을 민다");

		assertEquals(0, DragonRiftBreath.fillLanes(0.0), "폭이 없으면 채울 안쪽도 없다");
		assertTrue(DragonRiftBreath.fillLanes(100.0) <= DragonRiftBreath.FILL_MAX_LANES,
				"폭을 아무리 넓혀도 줄 수에는 천장이 있다");
		for (int lanes = 1; lanes <= DragonRiftBreath.FILL_MAX_LANES; lanes++) {
			assertTrue(lanes * DragonRiftBreath.fillAlong(lanes) <= DragonRiftBreath.FILL_MAX_POINTS,
					"줄 " + lanes + "개에서 상한을 넘는다");
			assertTrue(DragonRiftBreath.fillAlong(lanes) >= 2, "한 줄에 점 하나는 줄이 아니다");
		}

		// 상한은 안쪽에만 걸린다. 경계가 성겨지면 장판이 어디서 끝나는지를 잃는다.
		assertEquals(DragonRiftBreath.linePoints(LENGTH), rift.leftEdge().size(),
				"경계가 상한에 깎였다");
		assertEquals(DragonRiftBreath.linePoints(LENGTH), rift.rightEdge().size());
	}

	@Test
	void 폭이_넓어져도_걸어서_건널_수_있다() {
		float walked = DragonRiftBreath.crossingDamage(4.317);
		float sprinted = DragonRiftBreath.crossingDamage(5.612);
		assertEquals(6.0F, walked, 1.0E-6,
				"폭 " + DragonRiftBreath.HALF_WIDTH * 2.0 + "칸에서 걸어 건너며 받는 피해");
		assertTrue(walked < TEAM_HEALTH / 2.0F,
				"팀 체력의 절반을 넘으면 「못 지나간다」이지 「아프다」가 아니다. 실제 " + walked);
		assertTrue(walked > 0.0F, "한 점도 안 아프면 벽이 아니다");
		assertTrue(sprinted <= walked, "달리는 쪽이 더 아프면 뛸 이유가 없다");
	}

	// ------------------------------------------------------------------ 파티클 사거리

	@Test
	void 선은_긴_거리로_나간다() throws IOException {
		// 이 패시브가 보여 주는 것은 「아레나가 갈렸다」이고, 그것은 반대편 끝까지 보여야 성립한다.
		// 짧은 형태는 32칸에서 잘리는데 이 선은 80칸이라, 되돌리면 절반이 아무에게도 안 보이고
		// 그래도 빌드와 로그는 조용하다.
		String bytes = classBytes();
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
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlock"), "블록을 놓으면 다음 전투의 발판이 달라진다");
		assertFalse(bytes.contains("destroyBlock"), "바닥을 지우면 공허 낙사다");
		assertFalse(bytes.contains("removeBlock"));
		assertTrue(bytes.contains("getHeightmapPos"), "지면 높이는 읽어야 선이 바닥에 붙는다");
	}

	/**
	 * 페이즈 API 를 한 번도 부르지 않는다.
	 *
	 * <p>바로 앞 작업에서 데었다 — 「표적」이 드래곤을 돌진 페이즈로 밀었더니 <b>착지를 아예 하지
	 * 않게 됐다.</b> {@code EnderDragonPhaseManager.setPhase} 가 부르는 {@code begin()} 이
	 * {@code DragonHoldingPatternPhase.currentPath} 를 {@code null} 로 지우는데, 「착지할까」
	 * 주사위는 <b>그 경로가 끝난 틱에만</b> 굴러가기 때문이다.
	 */
	@Test
	void 페이즈_API_를_한_번도_부르지_않는다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains("setPhase"), "페이즈를 밀면 드래곤이 착지를 아예 안 한다");
		assertFalse(bytes.contains("getPhaseManager"));
		assertFalse(bytes.contains("EnderDragonPhase"));
		assertFalse(bytes.contains("LANDING_APPROACH"));
	}

	/**
	 * 드래곤을 한 칸도 옮기지 않는다.
	 *
	 * <p>페이즈를 안 건드려도 <b>위치를 미는 것만으로 같은 종류의 사고</b>가 난다. 26.3
	 * {@code EnderDragon.aiStep} 의 서버 구간이 매 틱
	 * {@code hurt(level, getEntities(head.getBoundingBox().inflate(1)))} 로
	 * {@code mobAttack} <b>10.0F</b> 를 넣고(목도 같다),
	 * {@code knockBack(wing2.getBoundingBox().inflate(4,2,4).move(0,-2,0))} 로 5.0F 와 밀치기를
	 * 넣는다 — 팀 공유 체력이 20 인데 10 은 절반이고, 80칸을 20틱에 지나가면 히트박스가 한 틱에
	 * 4칸씩 건너뛰어 맞고 안 맞고가 복불복이 된다. 같은 구간의 {@code checkWalls} 는
	 * {@code DRAGON_IMMUNE} 이 아닌 블록을 {@code removeBlock} 하므로, 사람이 놓은 블록이
	 * 드래곤의 손으로 부서진다.
	 *
	 * <p>그래서 이 패시브는 드래곤을 <b>읽기만</b> 한다. 되돌릴 상태가 없다는 것이 이 방식의
	 * 값어치이므로, 값을 쓰는 호출이 하나라도 들어오면 여기서 막는다.
	 */
	@Test
	void 드래곤을_한_칸도_옮기지_않는다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains("setPos"), "옮기면 몸이 사람을 때리고 블록을 부순다");
		assertFalse(bytes.contains("setDeltaMovement"), "속도를 눌러도 바닐라 비행을 밀어내는 것이다");
		assertFalse(bytes.contains("teleportTo"));
		assertFalse(bytes.contains("setYRot"), "방향을 틀면 부위 여덟 개가 몸과 어긋난다");
		assertFalse(bytes.contains("yBodyRot"));
		assertFalse(bytes.contains("getSubEntities"),
				"부위를 미는 코드가 있다는 것은 몸을 옮겼다는 뜻이다");
		assertFalse(bytes.contains("syncPosition"),
				"위치를 클라이언트로 밀어 보낼 일이 없어야 한다 — 옮기지 않으니까");
		assertTrue(bytes.contains("EnderDragonPart"),
				"그래도 입에서는 나와야 한다 — dragon.head 를 읽는 줄이 사라졌다");
	}

	/** 클래스 파일을 통째로 읽어 온다. 상수 풀에 무엇이 있고 없는지를 묻는 시험들이 쓴다. */
	private static String classBytes() throws IOException {
		try (InputStream in = DragonRiftBreath.class
				.getResourceAsStream("/com/sharedfate/sync/DragonRiftBreath.class")) {
			if (in == null) {
				throw new IOException("DragonRiftBreath 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
