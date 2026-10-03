package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * 「연쇄 포격」에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>2026-10-04 에 사람이 「터지는 속도 50프로 빨리 · 한 줄 더 그어 한번에 2줄씩 · 간격 0.4초 ·
 * 원표식이 생기고 50프로 더 빨리 떨어지게」로 고쳤다. 예고 6 → 4초, 간격 0.6 → 0.4초, 줄 1 → 2.
 *
 * <p>파티클·소리·피해는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 패시브가
 * 망가지는 길은 대부분 기하와 타이밍 쪽이다 — <b>원이 겹친다</b>, <b>한 틱에 여럿이 터진다</b>,
 * <b>터지는 순서가 뒤섞인다</b>, <b>터진 자리가 계속 아프다</b>, <b>예고가 비킬 시간보다 짧다</b>,
 * <b>지난 판의 좌표가 새 월드에서 터진다</b>. 전멸하면 월드가 지워지는 게임이라 돌려 보고
 * 발견할 수 없다.
 */
class DragonFireBarrageTest {

	/** 엔드 중앙. 선은 여기를 지나야 「아레나를 가로지른다」가 성립한다. */
	private static final Vec3 CENTRE = new Vec3(0.0, 0.0, 0.0);
	/** 팀 공유 체력. 한 틱에 이만큼 들어가면 가득 찬 상태에서도 즉사다. */
	private static final float TEAM_HEALTH = 20.0F;
	/** 바닐라 걷기 속도(칸/초). */
	private static final double WALK = 4.317;
	/** 바닐라 달리기 속도(칸/초). */
	private static final double SPRINT = 5.612;
	/** 달리며 뛰기. 바닐라에서 사람이 맨몸으로 낼 수 있는 가장 빠른 속도다. */
	private static final double SPRINT_JUMP = 7.13;

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonFireBarrage.clearState();
	}

	// ------------------------------------------------------------------ 원 열 개가 선 위에 놓인다

	@Test
	void 원이_열_개고_선_위에_고르게_놓인다() {
		for (double roll = 0.0; roll <= 1.0; roll += 0.05) {
			DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, roll);
			List<Vec3> shells = run.shells();
			assertEquals(DragonFireBarrage.SHELL_COUNT, shells.size(),
					"개수가 사람이 정한 열이 아니다: 굴림 " + roll);
			assertEquals(10, DragonFireBarrage.SHELL_COUNT, "사람이 정한 값은 열이다");

			double gap = DragonFireBarrage.shellGap();
			for (int index = 1; index < shells.size(); index++) {
				assertEquals(gap, shells.get(index - 1).distanceTo(shells.get(index)), 1.0E-9,
						"간격이 고르지 않으면 어떤 틈은 좁아 빠져나갈 수 없다: " + index);
			}
			// 첫 원과 끝 원은 경계에서 반 칸 간격만큼 안쪽이다 — 선을 열 토막으로 잘라 그
			// 한가운데에 놓았기 때문이고, 그래야 간격이 「길이 ÷ 개수」로 고르다.
			assertEquals(gap / 2.0, shells.getFirst().distanceTo(run.from()), 1.0E-9);
			assertEquals(gap / 2.0, shells.getLast().distanceTo(run.to()), 1.0E-9);
		}
	}

	@Test
	void 선이_중앙을_지나고_양_끝이_아레나_경계에_닿는다() {
		for (double roll = 0.0; roll <= 1.0; roll += 0.05) {
			DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, roll);
			assertEquals(TrialRisks.ARENA_RADIUS, run.from().distanceTo(CENTRE), 1.0E-9,
					"한쪽 끝이 경계에 안 닿으면 그 바깥이 안전지대가 된다: 굴림 " + roll);
			assertEquals(TrialRisks.ARENA_RADIUS, run.to().distanceTo(CENTRE), 1.0E-9);
			assertEquals(0.0, run.from().add(run.to()).length(), 1.0E-9,
					"양 끝이 중앙 대칭이 아니면 선이 중앙을 지나지 않는다: 굴림 " + roll);
			assertEquals(DragonFireBarrage.LINE_LENGTH, run.from().distanceTo(run.to()), 1.0E-9);
		}
	}

	@Test
	void 원이_아레나_안에_온전히_들어간다() {
		// 반쯤 허공에 걸친 원은 「저기는 안전한가」가 된다. 반경까지 더해서 경계 안이어야 한다.
		for (double roll = 0.0; roll <= 1.0; roll += 0.1) {
			for (Vec3 shell : DragonFireBarrage.plan(0L, roll).shells()) {
				assertTrue(shell.distanceTo(CENTRE) + DragonFireBarrage.SHELL_RADIUS
								<= TrialRisks.ARENA_RADIUS + 1.0E-9,
						"원이 아레나를 넘는다: 굴림 " + roll + ", 중심 " + shell);
			}
		}
	}

	@Test
	void 선은_평평하다() {
		// 높이는 지면을 읽을 때 얹는다. 기하 단계에서 y 가 섞이면 간격 계산이 비스듬해진다.
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.3);
		assertEquals(0.0, run.from().y, 1.0E-9);
		assertEquals(0.0, run.to().y, 1.0E-9);
		for (Vec3 shell : run.shells()) {
			assertEquals(0.0, shell.y, 1.0E-9);
		}
	}

	@Test
	void 각도가_매번_같지_않다() {
		RandomSource random = RandomSource.create(20260929L);
		Set<Long> angles = new HashSet<>();
		for (int round = 0; round < 200; round++) {
			Vec3 axis = DragonFireBarrage.axisFor(random.nextDouble());
			// 소수점 세 자리로 뭉쳐 센다. 부동소수 잡음까지 「다르다」로 세면 시험이 무의미하다.
			angles.add(Math.round(Math.atan2(axis.z, axis.x) * 1000.0));
		}
		assertTrue(angles.size() > 150,
				"각도가 몰리면 같은 자리만 계속 포격당한다. 서로 다른 각도 " + angles.size() + "개");
	}

	@Test
	void 반_바퀴만_써도_모든_방향의_선이_나온다() {
		// 선은 양방향이라 θ 와 θ+π 가 같은 선이다. 굴림 0 과 1 은 같은 선이고 끝만 뒤집힌다.
		DragonFireBarrage.Barrage head = DragonFireBarrage.plan(0L, 0.0);
		DragonFireBarrage.Barrage tail = DragonFireBarrage.plan(0L, 1.0);
		assertEquals(head.from().x, tail.to().x, 1.0E-9);
		assertEquals(head.from().z, tail.to().z, 1.0E-9);
	}

	// ------------------------------------------------------------------ 두 줄

	/** 한 번에 두 줄을 긋고, 두 줄은 같은 주기·같은 박자다. */
	@Test
	void 한_번에_두_줄을_긋는다() {
		DragonFireBarrage.Volley volley = DragonFireBarrage.planVolley(5L, 0.2, 0.7);
		assertEquals(DragonFireBarrage.LINE_COUNT, volley.lines().size(),
				"사람이 정한 것은 「한번에 2줄씩」이다");
		for (DragonFireBarrage.Barrage line : volley.lines()) {
			assertEquals(5L, line.cycle(), "줄마다 주기가 다르면 같은 박자가 아니다");
			assertFalse(line.started());
			assertEquals(0.0, line.from().add(line.to()).length(), 1.0E-9, "두 줄 모두 중앙을 지난다");
			assertEquals(DragonFireBarrage.SHELL_COUNT, line.shells().size());
		}
		DragonFireBarrage.Volley started = volley.startedAt(900L);
		for (DragonFireBarrage.Barrage line : started.lines()) {
			assertEquals(900L, line.startedAt(), "한 줄만 시작하면 같은 박자가 깨진다");
		}
	}

	/**
	 * <b>두 줄 사이 각은 언제나 45°~90° 다.</b>
	 *
	 * <p>45° 의 근거는 {@code MIN_LINE_ANGLE_DEGREES} 설명에 있다 — 같은 박자의 바깥 원들이
	 * 서로 안 닿는 경계가 33.9° 이고, 거기 바로 붙이면 「두 줄」이 아니라 「굵은 한 줄」로 읽힌다.
	 */
	@Test
	void 두_줄_사이_각이_45도에서_90도다() {
		assertEquals(45.0, DragonFireBarrage.MIN_LINE_ANGLE_DEGREES, 1.0E-9);
		double smallest = 180.0;
		double largest = 0.0;
		for (double first = 0.0; first <= 1.0; first += 0.05) {
			for (double gap = 0.0; gap <= 1.0; gap += 0.05) {
				DragonFireBarrage.Volley volley = DragonFireBarrage.planVolley(0L, first, gap);
				double angle = DragonFireBarrage.lineSeparationDegrees(volley.lines().get(0),
						volley.lines().get(1));
				assertTrue(angle >= DragonFireBarrage.MIN_LINE_ANGLE_DEGREES - 1.0E-6,
						"두 줄이 " + angle + "° 로 붙었다: 굴림 " + first + ", " + gap);
				assertTrue(angle <= 90.0 + 1.0E-6, "선은 양방향이라 90° 를 넘을 수 없다: " + angle);
				smallest = Math.min(smallest, angle);
				largest = Math.max(largest, angle);
			}
		}
		assertEquals(45.0, smallest, 0.5, "하한까지 실제로 나와야 굴림이 범위를 다 쓴 것이다");
		assertEquals(90.0, largest, 0.5, "직각까지 실제로 나와야 굴림이 범위를 다 쓴 것이다");
	}

	/**
	 * <b>같은 박자의 두 원은 중앙 둘 말고는 겹치지 않는다.</b>
	 *
	 * <p>두 줄은 언제나 중앙에서 만난다. 중앙에서 4칸 떨어진 4·5 번째 원은 줄 사이 각이 몇이든
	 * 겹치고, 그 자리는 「한 박자에 한 사람 한 번」({@code victims})이 맡는다. 그 바깥은 45° 에서도
	 * 틈이 남아야 한다 — 그래야 각 하한이 뜻이 있다. 방향이 뒤집힌 경우(드래곤 쪽 끝이 서로 반대로
	 * 잡힌 경우)도 함께 본다.
	 */
	@Test
	void 같은_박자_원은_중앙_둘_말고는_두_줄_사이에서_겹치지_않는다() {
		double required = TrialRisks.spotMinGap(DragonFireBarrage.SHELL_RADIUS);
		DragonFireBarrage.Barrage one = DragonFireBarrage.plan(0L, 0.0);
		// 굴림 0.25 가 45° 다 — 각 하한에 정확히 붙은, 가장 나쁜 판이다.
		DragonFireBarrage.Barrage other = DragonFireBarrage.plan(0L, 0.25);
		assertEquals(45.0, DragonFireBarrage.lineSeparationDegrees(one, other), 1.0E-6);
		for (DragonFireBarrage.Barrage second : new DragonFireBarrage.Barrage[] {
				other, DragonFireBarrage.startingNear(other, other.to())}) {
			for (int index = 0; index < DragonFireBarrage.SHELL_COUNT; index++) {
				double gap = one.shells().get(index).distanceTo(second.shells().get(index));
				boolean centre = index == DragonFireBarrage.SHELL_COUNT / 2 - 1
						|| index == DragonFireBarrage.SHELL_COUNT / 2;
				if (centre) {
					continue;
				}
				assertTrue(gap > required, "같은 박자의 원 " + index + " 이 두 줄 사이에서 겹친다 ("
						+ gap + "칸) — 각 하한이나 개수·반경을 고쳤다면 MIN_LINE_ANGLE_DEGREES 를 다시 셀 것");
			}
		}
		// 중앙 둘은 정말 겹친다. 겹치지 않는다면 victims 의 「한 박자에 한 번」이 필요 없어진 것이다.
		assertTrue(one.shells().get(4).distanceTo(other.shells().get(4)) < required,
				"45° 에서 중앙 원이 안 겹친다 — 설명의 셈이 틀렸다");
	}

	/** 두 줄 모두 드래곤이 있는 쪽 끝부터 터진다. */
	@Test
	void 두_줄_모두_드래곤_쪽부터_터진다() {
		Vec3 dragon = new Vec3(30.0, 120.0, 55.0);
		for (double gap = 0.0; gap <= 1.0; gap += 0.1) {
			DragonFireBarrage.Volley aimed = DragonFireBarrage.startingNear(
					DragonFireBarrage.planVolley(0L, 0.13, gap), dragon);
			for (DragonFireBarrage.Barrage line : aimed.lines()) {
				assertTrue(DragonFireBarrage.flatDistanceSqr(dragon, line.from())
								<= DragonFireBarrage.flatDistanceSqr(dragon, line.to()),
						"한 줄이 드래곤 반대편에서 시작한다: 굴림 " + gap);
				assertTrue(line.shells().getFirst().distanceTo(dragon)
								< line.shells().getLast().distanceTo(dragon),
						"첫 번째로 터지는 원이 드래곤 쪽이 아니다: 굴림 " + gap);
			}
		}
		DragonFireBarrage.Volley untouched = DragonFireBarrage.planVolley(0L, 0.13, 0.4);
		assertEquals(untouched, DragonFireBarrage.startingNear(untouched, null),
				"드래곤이 없으면 굴림이 정한 방향 그대로다");
	}

	/**
	 * <b>교차점에 선 사람은 한 박자에 한 번만 맞는다.</b>
	 *
	 * <p>두 줄이 만나는 중앙에서 같은 박자의 두 원이 겹친다. 거기 선 사람이 두 번 맞으면 한 틱에
	 * 46(무장 13.5)이 한 사람에게 간다. 사람이 정한 것은 「한 박자에 한 번」이다.
	 */
	@Test
	void 교차점에_선_사람은_한_박자에_한_번만_맞는다() {
		List<Vec3> shells = crossingShells();
		Vec3 both = shells.get(0).add(shells.get(1)).scale(0.5);
		assertTrue(TrialRisks.insideMark(both, shells.get(0), DragonFireBarrage.SHELL_RADIUS));
		assertTrue(TrialRisks.insideMark(both, shells.get(1), DragonFireBarrage.SHELL_RADIUS),
				"시험 자리가 두 원 모두의 안이 아니다");

		int[] hit = DragonFireBarrage.victims(List.of(both), shells);
		assertEquals(0, hit[0], "첫 원이 겹친 자리의 사람을 때려야 한다");
		assertEquals(-1, hit[1], "같은 박자의 둘째 원이 같은 사람을 또 때렸다 — 한 틱에 두 번이다");
	}

	/**
	 * <b>둘째 원은 그 원 안의 다른 사람을 때린다.</b>
	 *
	 * <p>두 줄이니 두 사람이 각자 다른 줄의 원에서 실패할 수 있다. 그 둘을 한 번으로 묶으면 둘째
	 * 줄이 아무 뜻이 없어진다. 팀이 한 틱에 받는 것은 그래서 많아야 {@code MAX_CONCURRENT_BLASTS} 발.
	 */
	@Test
	void 둘째_원은_그_원_안의_다른_사람을_때린다() {
		List<Vec3> shells = crossingShells();
		Vec3 both = shells.get(0).add(shells.get(1)).scale(0.5);
		// 둘째 원 안이면서 첫 원 밖인 자리.
		Vec3 away = shells.get(1).subtract(shells.get(0)).normalize();
		Vec3 onlySecond = shells.get(1).add(away.scale(2.5));
		assertFalse(TrialRisks.insideMark(onlySecond, shells.get(0), DragonFireBarrage.SHELL_RADIUS));
		assertTrue(TrialRisks.insideMark(onlySecond, shells.get(1), DragonFireBarrage.SHELL_RADIUS));

		int[] hit = DragonFireBarrage.victims(List.of(both, onlySecond), shells);
		assertEquals(0, hit[0]);
		assertEquals(1, hit[1], "둘째 원 안에 다른 사람이 있는데 아무도 안 맞았다");

		int[] reversed = DragonFireBarrage.victims(List.of(onlySecond, both), shells);
		assertEquals(1, reversed[0]);
		assertEquals(0, reversed[1], "팀원 순서가 바뀌어도 두 사람 모두 맞아야 한다");
	}

	/** 원 하나는 한 사람만 때린다 — 같은 원에 넷이 모여 있어도 한 번이다. */
	@Test
	void 원_하나는_한_사람만_때린다() {
		List<Vec3> shells = crossingShells();
		Vec3 first = shells.get(0);
		Vec3 away = first.subtract(shells.get(1)).normalize();
		Vec3 deep = first.add(away.scale(1.0));
		List<Vec3> huddle = List.of(deep, deep, deep, deep);
		int[] hit = DragonFireBarrage.victims(huddle, List.of(first));
		assertEquals(1, hit.length);
		assertEquals(0, hit[0], "넷이 함께 서 있으면 한 사람만 맞는다 — 함께 움직이는 것이 정답이다");
		assertEquals(-1, DragonFireBarrage.victims(List.of(new Vec3(100.0, 0.0, 100.0)), shells)[0],
				"원 밖의 사람을 때렸다");
	}

	/** 45° 로 만난 두 줄의 4 번째 원 — 같은 박자에 터지고 서로 겹친다. */
	private static List<Vec3> crossingShells() {
		DragonFireBarrage.Barrage one = DragonFireBarrage.plan(0L, 0.0);
		DragonFireBarrage.Barrage other = DragonFireBarrage.plan(0L, 0.25);
		return List.of(one.shells().get(4), other.shells().get(4));
	}

	// ------------------------------------------------------------------ 겹치면 안 된다

	/**
	 * <b>원끼리 반경의 두 배보다 멀다.</b>
	 *
	 * <p>이 저장소가 「낙뢰」에서 이미 겪은 사고다. {@code TrialRisks.SPOT_MIN_GAP_FACTOR} 에
	 * 전말이 적혀 있다 — 반경 R 짜리 원 둘의 중심이 <b>2R 보다 가까우면</b> 겹침 구역이 생기고
	 * 거기 선 사람은 한 틱에 두 번 맞는다. 반경 40 아레나에 반경 3 짜리 열 곳을 무작위로 놓으면
	 * 그 일이 <b>62.8%</b> 확률로 일어난다.
	 *
	 * <p>여기서는 선 위에 균등하게 놓으므로 간격이 계산으로 나온다. <b>개수를 늘리거나 반경을
	 * 키우는 사람은 여기서 멈춰야 한다.</b>
	 */
	@Test
	void 원끼리_반경의_두_배보다_멀다() {
		double required = TrialRisks.spotMinGap(DragonFireBarrage.SHELL_RADIUS);
		assertTrue(DragonFireBarrage.shellGap() > required,
				"간격 " + DragonFireBarrage.shellGap() + " 가 필요한 " + required
						+ " 이하다 — 겹침 구역에 선 사람은 한 틱에 두 번 맞고, 그러면 "
						+ "worstCaseTickDamage 가 거짓이 된다. 개수나 반경을 올렸다면 되돌릴 것");

		// 실제로 놓아 보고 아무 두 원도 겹치지 않는지 확인한다. 계산만 맞고 배치가 어긋나는
		// 길이 있다 — shellAt 이 끝점 배치로 되돌아가면 간격이 길이÷(개수-1) 로 벌어진다.
		for (double roll = 0.0; roll <= 1.0; roll += 0.1) {
			List<Vec3> shells = DragonFireBarrage.plan(0L, roll).shells();
			for (int one = 0; one < shells.size(); one++) {
				for (int other = one + 1; other < shells.size(); other++) {
					assertTrue(shells.get(one).distanceTo(shells.get(other)) > required,
							"원 " + one + " 과 " + other + " 이 겹친다: 굴림 " + roll);
				}
			}
		}
	}

	@Test
	void 원_사이에_빠져나갈_틈이_남는다() {
		// 겹치지 않는다는 말의 다른 면이다. 틈이 0 이면 「방금 터진 칸으로 들어간다」 말고는
		// 답이 없어지는데, 그 답도 포격 첫 원에서는 쓸 수 없다.
		double slack = DragonFireBarrage.shellGap() - DragonFireBarrage.SHELL_RADIUS * 2.0;
		assertTrue(slack > 0.5,
				"원 사이 틈이 " + slack + "칸뿐이다 — 사람 폭이 0.6칸이라 서 있을 수 없다");
	}

	// ------------------------------------------------------------------ 순차가 안전장치다

	/**
	 * <b>한 틱에 줄마다 하나만 터진다.</b>
	 *
	 * <p>순차라는 것이 이 컨셉의 안전장치다. 열 개가 겹쳐 터지면
	 * {@code 6 × 10 = 60} 으로 팀 체력 20 의 세 배다. 두 줄은 {@code blastingIndex} 하나를 함께
	 * 쓰므로 한 틱에 터지는 원은 줄 수와 같다.
	 */
	@Test
	void 한_틱에_줄마다_하나만_터진다() {
		long started = 10_000L;
		Set<Integer> seen = new HashSet<>();
		int blasts = 0;
		for (int offset = 0; offset <= DragonFireBarrage.BARRAGE_TICKS + 50; offset++) {
			int index = DragonFireBarrage.blastingIndex(started, started + offset);
			if (index < 0) {
				continue;
			}
			blasts++;
			assertTrue(seen.add(index), "원 " + index + " 이 두 번 터진다");
		}
		assertEquals(DragonFireBarrage.SHELL_COUNT, blasts,
				"열 개가 다 터지지 않으면 예고한 자리 중 어딘가는 거짓말이 된다");
		assertEquals(2, DragonFireBarrage.LINE_COUNT, "사람이 정한 것은 「한번에 2줄씩」이다");
		assertEquals(DragonFireBarrage.LINE_COUNT, DragonFireBarrage.MAX_CONCURRENT_BLASTS,
				"한 틱에 터지는 원 수가 줄 수와 다르면 worstCaseTickDamage 가 거짓이다");
	}

	@Test
	void 터지는_틱이_서로_다르다() {
		int previous = Integer.MIN_VALUE;
		for (int index = 0; index < DragonFireBarrage.SHELL_COUNT; index++) {
			int tick = DragonFireBarrage.blastTick(index);
			assertTrue(tick > previous, "터지는 틱이 같거나 거꾸로 간다: " + index);
			previous = tick;
		}
		assertEquals(0, DragonFireBarrage.blastTick(0), "첫 원이 포격의 기준 틱이다");
		assertEquals(DragonFireBarrage.BARRAGE_TICKS,
				DragonFireBarrage.blastTick(DragonFireBarrage.SHELL_COUNT - 1));
	}

	/**
	 * <b>터지는 순서가 한쪽 끝에서 반대쪽 끝까지 차례대로다.</b>
	 *
	 * <p>순서가 뒤섞이면 「방금 터진 칸으로 들어간다」를 배울 수 없고, 그러면 이 패턴에 답이 없다.
	 */
	@Test
	void 터지는_순서가_한쪽_끝에서_반대쪽_끝까지_차례대로다() {
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.37);
		List<Vec3> shells = run.shells();

		double previous = -1.0;
		for (Vec3 shell : shells) {
			double along = shell.distanceTo(run.from());
			assertTrue(along > previous, "목록 순서가 선을 따라가지 않는다: " + shell);
			previous = along;
		}
		assertEquals(shells.getFirst(), nearest(shells, run.from()),
				"첫 번째로 터지는 원이 출발 쪽 끝이 아니다");
		assertEquals(shells.getLast(), nearest(shells, run.to()),
				"마지막으로 터지는 원이 반대쪽 끝이 아니다");

		// 터지는 순서(틱)와 자리의 순서(거리)가 같은 방향이어야 한다.
		long started = 500L;
		double reached = -1.0;
		for (int offset = 0; offset <= DragonFireBarrage.BARRAGE_TICKS; offset++) {
			int index = DragonFireBarrage.blastingIndex(started, started + offset);
			if (index < 0) {
				continue;
			}
			double along = shells.get(index).distanceTo(run.from());
			assertTrue(along > reached, "포격이 되돌아간다: 틱 " + offset + ", 원 " + index);
			reached = along;
		}
	}

	/**
	 * <b>드래곤이 있는 쪽부터 터진다.</b>
	 *
	 * <p>드래곤을 선에 맞추는 것이 아니라 <b>선을 드래곤에 맞춘다.</b> 드래곤을 옮기면
	 * {@code aiStep} 의 히트박스가 사람을 때리고 {@code checkWalls} 가 블록을 부수지만, 선은 우리
	 * 것이라 공짜로 돌릴 수 있다. 「바깥부터」가 드래곤 쪽이 되면 「드래곤이 저기서부터 던진다」가
	 * 저절로 읽힌다.
	 */
	@Test
	void 드래곤이_있는_쪽부터_터진다() {
		// 굴림 0 이면 축이 +x 라 from 은 (-40,0,0), to 는 (40,0,0) 이다.
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.0);
		Vec3 dragon = new Vec3(70.0, 120.0, 0.0);

		DragonFireBarrage.Barrage aimed = DragonFireBarrage.startingNear(run, dragon);
		assertEquals(run.to(), aimed.from(), "드래곤 반대편에서 시작하면 누가 던졌는지 못 읽는다");
		assertEquals(run.from(), aimed.to());
		assertTrue(aimed.shells().getFirst().distanceTo(dragon)
						< aimed.shells().getLast().distanceTo(dragon),
				"첫 번째로 터지는 원이 드래곤 쪽이 아니다 — 목록을 뒤집는 줄이 빠졌다");
		assertEquals(run.shells().getLast(), aimed.shells().getFirst(),
				"뒤집으면 순서가 정확히 거꾸로여야 한다");

		DragonFireBarrage.Barrage already =
				DragonFireBarrage.startingNear(run, new Vec3(-70.0, 120.0, 0.0));
		assertEquals(run.from(), already.from(), "이미 드래곤 쪽에서 시작하면 그대로 둔다");
		assertEquals(run.shells(), already.shells());
	}

	@Test
	void 방향을_뒤집어도_위험한_자리는_그대로다() {
		// 방향은 연출이고 자리는 예고로 이미 보여 준 것이다. 뒤집는 것이 자리를 한 칸이라도
		// 옮기면 6초 동안 보여 준 원과 실제로 터지는 원이 어긋난다.
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.31);
		DragonFireBarrage.Barrage flipped = DragonFireBarrage.startingNear(run, run.to());
		assertNotEquals(run.from(), flipped.from(), "이 굴림에서는 실제로 뒤집혀야 시험이 뜻이 있다");
		assertEquals(new HashSet<>(run.shells()), new HashSet<>(flipped.shells()),
				"뒤집었더니 원이 움직였다 — 예고와 실제가 어긋난다");
	}

	@Test
	void 드래곤_높이는_방향을_고르는_데_쓰지_않는다() {
		// 드래곤은 아레나보다 수십 칸 위를 난다. 세로를 섞으면 양 끝이 똑같이 멀어져 사실상
		// 굴림이 방향을 정하게 된다.
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.0);
		assertEquals(run.to(),
				DragonFireBarrage.startingNear(run, new Vec3(70.0, 500.0, 0.0)).from(),
				"아무리 높이 있어도 가로로 가까운 쪽부터 터진다");
	}

	@Test
	void 드래곤이_없으면_굴림이_정한_방향_그대로다() {
		// 드래곤이 죽었거나 아직 안 나온 틱에도 원은 놓여야 한다.
		DragonFireBarrage.Barrage run = DragonFireBarrage.plan(0L, 0.63);
		assertEquals(run, DragonFireBarrage.startingNear(run, null));
	}

	// ------------------------------------------------------------------ 잔류가 없다

	/**
	 * <b>이미 터진 원은 아무도 태우지 않는다.</b>
	 *
	 * <p>이 패턴의 회피법이 <b>「방금 터진 칸으로 들어간다」</b>가 되어야 하기 때문이다. 원 열 개가
	 * 한쪽 끝에서 차례로 터지니 플레이어가 배울 수 있는 답이 그것이고, 잔류가 남으면 그 답이 막혀
	 * 원 사이 1칸 틈으로만 빠져야 해서 <b>배치 운</b>에 맡기게 된다.
	 *
	 * <p>{@code blastingIndex} 가 원 하나를 <b>단 한 틱</b>에만 돌려준다는 것이 그 약속의 전부다 —
	 * 아프게 하는 코드가 그 값 하나만 보고 있다.
	 */
	@Test
	void 이미_터진_원은_아무도_태우지_않는다() {
		long started = 7_000L;
		for (int index = 0; index < DragonFireBarrage.SHELL_COUNT; index++) {
			int blast = DragonFireBarrage.blastTick(index);
			assertEquals(index, DragonFireBarrage.blastingIndex(started, started + blast),
					"터져야 하는 틱에 안 터진다: 원 " + index);
			for (int after = 1; after <= DragonFireBarrage.BARRAGE_TICKS + 200; after++) {
				assertNotEquals(index,
						DragonFireBarrage.blastingIndex(started, started + blast + after),
						"원 " + index + " 이 터진 뒤 " + after + "틱에 또 아프게 한다 — 잔류다");
			}
		}
	}

	@Test
	void 마지막_원이_터지는_틱에_포격이_끝난다() {
		long started = 7_000L;
		assertTrue(DragonFireBarrage.running(started, started), "첫 원이 터지는 틱부터 포격이다");
		assertTrue(DragonFireBarrage.running(started, started + DragonFireBarrage.BARRAGE_TICKS),
				"마지막 원이 터지는 틱에 버리면 그 폭발이 나가지 않는다");
		assertFalse(
				DragonFireBarrage.running(started, started + DragonFireBarrage.BARRAGE_TICKS + 1),
				"마지막 원이 터진 다음 틱부터는 아무것도 남지 않아야 한다");
		assertFalse(DragonFireBarrage.running(started, started + 10_000L));
		assertEquals(DragonFireBarrage.SHELL_COUNT - 1, DragonFireBarrage.blastingIndex(started,
				started + DragonFireBarrage.BARRAGE_TICKS));
	}

	@Test
	void 시작하지_않은_포격은_아무도_태우지_않는다() {
		assertEquals(-1, DragonFireBarrage.blastingIndex(DragonFireBarrage.NOT_STARTED, 10_000L),
				"예고 중에 아프면 예고가 아니라 공격이다");
		assertEquals(0, DragonFireBarrage.blownCount(DragonFireBarrage.NOT_STARTED, 10_000L));
		assertFalse(DragonFireBarrage.running(DragonFireBarrage.NOT_STARTED, 10_000L));
		assertEquals(-1, DragonFireBarrage.flyingIndex(DragonFireBarrage.NOT_STARTED, 10_000L));
	}

	@Test
	void 시간이_거꾸로_가도_포격이_되살아나지_않는다() {
		// 서버를 재시작하면 게임 시각이 메모리에 남은 값보다 작을 수 있다.
		long started = 7_000L;
		assertFalse(DragonFireBarrage.running(started, started - 1));
		assertEquals(-1, DragonFireBarrage.blastingIndex(started, started - 1));
		assertEquals(0, DragonFireBarrage.blownCount(started, started - 1));
	}

	/**
	 * 터진 원의 고리가 그 틱에 사라진다.
	 *
	 * <p>고리가 한 틱이라도 더 남으면 이미 안전한 자리가 위험해 보이고, 그러면 「방금 터진 칸으로
	 * 들어간다」를 배울 수 없다.
	 */
	@Test
	void 터진_원의_표식이_그_틱에_사라진다() {
		long started = 7_000L;
		assertEquals(1, DragonFireBarrage.blownCount(started, started),
				"첫 원이 터지는 틱에 그 고리는 이미 없어야 한다");
		for (int index = 0; index < DragonFireBarrage.SHELL_COUNT; index++) {
			assertEquals(index + 1,
					DragonFireBarrage.blownCount(started, started
							+ DragonFireBarrage.blastTick(index)),
					"원 " + index + " 이 터진 틱에 그려지는 고리 수가 틀렸다");
		}
		assertEquals(DragonFireBarrage.SHELL_COUNT,
				DragonFireBarrage.blownCount(started, started + 100_000L),
				"다 터진 뒤로는 고리가 하나도 남지 않는다");
	}

	/**
	 * <b>잔류 구름을 되살릴 씨앗이 코드에 남아 있지 않다.</b>
	 *
	 * <p>이 자리의 앞선 구현은 지나간 자리에 15초짜리 장판을 남겼고, 사람이 그것을 <b>통째로</b>
	 * 뺐다. 「여운으로
	 * 2초쯤」 같은 타협도 없다 — 이 패턴의 회피법이 「방금 터진 칸으로 들어간다」라서다.
	 *
	 * <p>다음 사람이 「좀 남기면 멋질 텐데」로 되돌리지 못하게 <b>생김새 쪽도 함께</b> 막는다.
	 * 되살리려면 이 시험을 지워야 하고, 그러면 왜 없앴는지를 먼저 읽게 된다.
	 */
	@Test
	void 잔류_구름_파티클이_코드에_남아_있지_않다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains("DRAGON_BREATH"),
				"드래곤 잔류 구름 파티클이 돌아왔다 — 잔류는 한 틱도 남기지 않기로 했다");
		assertFalse(bytes.contains("PowerParticleOption"),
				"잔류 구름의 세기 값을 쓰던 형태다. 남길 구름이 없으므로 쓸 자리도 없다");
		assertFalse(bytes.contains("ENTITY_EFFECT"), "일반 잔류 포션 구름 쪽도 마찬가지다");
		assertFalse(bytes.contains("AreaEffectCloud"),
				"엔티티로 장판을 깔면 clearState 로 못 지우고 다음 판으로 샌다");
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 예고가 「흩어지기」 하한 이상이다.
	 *
	 * <p>⚠ 2026-10-04 에 사람이 「원표식이 생기고 50프로 더 빨리 떨어지게」로 6초 → 4초를 정했고,
	 * 그것이 하한 80 과 <b>정확히 같다.</b> 그래서 「하한보다 길다」가 「하한 이상이다」로 바뀌었다 —
	 * 이 아래로 내리는 것은 더 이상 사람이 정한 값도 아니고 하한도 깬다.
	 */
	@Test
	void 예고가_요구하는_행동의_최소_예고_이상이다() {
		// 이 패턴이 요구하는 것은 「선이 지나갈 자리에서 비키기」다. 옆으로 반경만큼 나가면
		// 되지만 어느 쪽으로 나갈지는 골라야 하므로, 기준은 흩어지기(80)다.
		assertEquals(80, DragonFireBarrage.LEAD_TICKS, "사람이 정한 것은 4초다(6초 ÷ 1.5)");
		assertTrue(DragonFireBarrage.LEAD_TICKS >= TrialWarning.TICKS_SCATTER,
				"필요 " + TrialWarning.TICKS_SCATTER + ", 실제 " + DragonFireBarrage.LEAD_TICKS
						+ " — 짧으면 보여 준 것이 예고가 아니라 사후 통보다");
		assertTrue(DragonFireBarrage.MIN_LEAD_TICKS >= TrialWarning.TICKS_SCATTER,
				"늦게 놓는 길에도 같은 하한이 걸려 있어야 한다");
		assertTrue(DragonFireBarrage.LEAD_TICKS >= DragonFireBarrage.MIN_LEAD_TICKS,
				"예고가 하한보다 짧으면 모든 주기가 건너뛰어져 포격이 영영 안 나온다");

		// 첫 화염구가 예고의 마지막 구간에서 난다. 그 동안에도 원은 그대로 보이므로 예고 길이는
		// 줄지 않지만, 화염구 없는 조용한 구간이 「지정한 자리로 이동」보다는 길어야 한다.
		int quiet = DragonFireBarrage.LEAD_TICKS - DragonFireBarrage.FLIGHT_TICKS;
		assertTrue(quiet >= TrialWarning.TICKS_REPOSITION,
				"화염구가 뜨기 전 조용한 예고가 " + quiet + "틱뿐이다");
	}

	/**
	 * 경고 세 층이 모두 나간다.
	 *
	 * <p>예고가 80 이라 「뭔가 온다」 층(≤100) <b>안에서</b> 시작한다. 그래서 직전 틱과 견주는
	 * 한 인자짜리 {@code stageJustChanged} 로는 그 층이 통째로 빠지고, 예고의 첫 틱을 층이 바뀐
	 * 틱으로 세는 두 인자짜리를 쓴다 — {@code DragonFireBarrage.warn} 이 그렇게 부른다.
	 */
	@Test
	void 경고_세_층이_모두_나간다() {
		Set<TrialWarning.Stage> seen = EnumSet.noneOf(TrialWarning.Stage.class);
		for (int remaining = DragonFireBarrage.LEAD_TICKS; remaining >= 0; remaining--) {
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage != null
					&& TrialRisks.stageJustChanged(remaining, DragonFireBarrage.LEAD_TICKS)) {
				seen.add(stage);
			}
		}
		assertEquals(EnumSet.allOf(TrialWarning.Stage.class), seen,
				"층이 빠지면 한 층을 놓친 사람을 다음 층이 못 잡는다");
		assertTrue(TrialWarning.stageFor(DragonFireBarrage.LEAD_TICKS) != null,
				"예고 첫 틱이 아무 층에도 안 닿으면 원이 소리 없이 뜬다");
	}

	@Test
	void 주기가_예고와_포격을_합친_것보다_길어_포격이_겹치지_않는다() {
		int occupied = DragonFireBarrage.LEAD_TICKS + DragonFireBarrage.BARRAGE_TICKS;
		assertTrue(DragonFireBarrage.PERIOD_TICKS > occupied,
				"포격 둘이 동시에 살아 있으면 두 선이 만나는 자리가 한 틱에 두 번 터지는 자리가"
						+ " 되고, worstCaseTickDamage 가 거짓이 된다. 주기 "
						+ DragonFireBarrage.PERIOD_TICKS + ", 차지 " + occupied);
		assertEquals(800, DragonFireBarrage.PERIOD_TICKS, "카드에 적힌 것은 40초다");
	}

	/**
	 * 「브레스가 아레나를 가릅니다」 같은 액션바 자막을 <b>띄우지 않는다.</b>
	 *
	 * <p>이 저장소의 자막을 전부 걷어냈고 {@code TrialWarningTest.아무도_자막을_띄우지_않는다} 가
	 * 지키고 있다. 여기서도 한 번 더 묻는 이유는, 글자가 빠진 만큼 <b>소리와 바닥 표식</b>이
	 * 신호의 전부가 되었기 때문이다 — 그쪽은
	 * {@link #바닥_표식은_규약의_빨강_하나뿐이다} 와 {@link #연출은_긴_거리로_나간다} 가 본다.
	 */
	@Test
	void 자막을_띄우지_않는다() throws IOException {
		assertFalse(classBytes().contains("shout"),
				"액션바 자막이 돌아왔다. 걷어내기로 한 것은 글자뿐이고 소리와 표식은 그대로 둔다 —"
						+ " 되살리려면 TrialWarning 의 설명부터 함께 고칠 것");
	}

	// ------------------------------------------------------------------ 화염구

	@Test
	void 첫_원이_터지는_틱에_화염구가_닿는다() {
		long granted = 1_000L;
		long impact = granted + DragonFireBarrage.PERIOD_TICKS;
		assertTrue(TrialRisks.firesAt(impact, granted, DragonFireBarrage.PERIOD_TICKS));
		assertEquals(0, TrialRisks.remainingTicks(impact, granted, DragonFireBarrage.PERIOD_TICKS));
		assertEquals(1.0, DragonFireBarrage.approachProgress(0), 1.0E-9,
				"닿기 전에 터지면 「던진 것이 떨어져 터졌다」가 눈에 안 맞는다");
	}

	@Test
	void 날아오는_비율이_영에서_일까지_한_번만_오른다() {
		assertEquals(0.0, DragonFireBarrage.approachProgress(DragonFireBarrage.FLIGHT_TICKS),
				1.0E-9, "떠나자마자 중간에 있으면 어디서 왔는지 못 읽는다");
		assertEquals(0.0, DragonFireBarrage.approachProgress(DragonFireBarrage.FLIGHT_TICKS + 50),
				1.0E-9, "비행 구간 밖에서는 아직 던지기 전이다");
		double previous = -1.0;
		for (int remaining = DragonFireBarrage.FLIGHT_TICKS; remaining >= 0; remaining--) {
			double progress = DragonFireBarrage.approachProgress(remaining);
			assertTrue(progress >= 0.0 && progress <= 1.0, "범위 밖: " + remaining);
			assertTrue(progress > previous, "멈추거나 되돌아가면 화염구가 공중에 선다: " + remaining);
			previous = progress;
		}
		assertEquals(1.0, previous, 1.0E-9);
	}

	/**
	 * 하늘에 화염구가 줄마다 언제나 한 발뿐이다.
	 *
	 * <p>{@link DragonFireBarrage#FLIGHT_TICKS} 가
	 * {@link DragonFireBarrage#BLAST_INTERVAL_TICKS} 와 같아서 앞 발이 터지는 그 틱에 다음 발이
	 * 떠난다. 길게 고치면 한 줄에서 여러 발이 동시에 날아 「어느 것이 다음인가」가 안 읽힌다.
	 */
	@Test
	void 하늘에_화염구가_줄마다_한_발뿐이고_터질_때마다_다음_발이_떠난다() {
		assertEquals(DragonFireBarrage.BLAST_INTERVAL_TICKS, DragonFireBarrage.FLIGHT_TICKS,
				"비행 시간과 터지는 간격이 다르면 하늘에 여러 발이 있거나 비어 있다");
		long started = 500L;
		for (int offset = 0; offset < DragonFireBarrage.BARRAGE_TICKS; offset++) {
			int flying = DragonFireBarrage.flyingIndex(started, started + offset);
			assertTrue(flying > 0 && flying < DragonFireBarrage.SHELL_COUNT,
					"포격 중에 하늘이 비었다: 틱 " + offset);
			assertEquals(offset / DragonFireBarrage.BLAST_INTERVAL_TICKS + 1, flying);
			double progress = DragonFireBarrage.flightProgress(started, started + offset);
			assertTrue(progress >= 0.0 && progress < 1.0, "비율이 범위 밖이다: 틱 " + offset);
		}
		assertEquals(0.0, DragonFireBarrage.flightProgress(started, started), 1.0E-9,
				"첫 원이 터지는 틱에 다음 발이 막 떠난다");
		assertEquals(-1, DragonFireBarrage.flyingIndex(started,
						started + DragonFireBarrage.BARRAGE_TICKS),
				"마지막 원이 터진 뒤에 더 던지면 예고하지 않은 발이다");
	}

	@Test
	void 화염구는_입과_원을_지나치지_않는다() {
		Vec3 mouth = new Vec3(60.0, 120.0, -30.0);
		Vec3 target = new Vec3(0.0, 63.5, 0.0);
		assertEquals(mouth, DragonFireBarrage.headAt(mouth, target, -1.0));
		assertEquals(target, DragonFireBarrage.headAt(mouth, target, 2.0));
		assertEquals(mouth.add(target.subtract(mouth).scale(0.5)),
				DragonFireBarrage.headAt(mouth, target, 0.5));
	}

	// ------------------------------------------------------------------ 점 수

	@Test
	void 점_수에_상한이_있다() {
		// 바닥 표식. 원 스무 개를 예고 내내 MARK_STRIDE 틱에 나눠 그린다.
		assertTrue(DragonFireBarrage.markPoints() <= DragonFireBarrage.MARK_MAX_POINTS,
				"한 틱에 " + DragonFireBarrage.markPoints() + "점이 나간다 — 파티클만으로 틱이 밀린다");
		assertTrue(DragonFireBarrage.markPoints() > 0, "고리를 하나도 안 그리면 예고가 없다");
		// 이 패시브 하나가 한 틱에 내보내는 전부. 이 저장소의 한 틱 예산은 400~440 이다
		// (docs/드래곤-트라이얼.md 5장 「점 예산」).
		assertTrue(DragonFireBarrage.worstTickPoints() <= 440,
				"고리 + 화염구 두 발 + 착탄 두 곳이 한 틱에 " + DragonFireBarrage.worstTickPoints()
						+ "점이다 — 예산 440 을 넘는다. 줄이 둘이라 화염구와 착탄도 두 벌이다");

		// 꼬리. 상한은 「약속한 길이 ÷ 허용 간격」이고 숫자를 박아 두면 한쪽만 고쳐진다.
		int cap = (int) Math.ceil(DragonFireBarrage.TRAIL_KEPT_LENGTH
				/ DragonFireBarrage.TRAIL_MAX_GAP);
		assertEquals(cap, DragonFireBarrage.TRAIL_MAX_POINTS);
		assertEquals(0, DragonFireBarrage.trailSamples(0.0), "길이가 없으면 점도 없다");
		assertEquals(0, DragonFireBarrage.trailSamples(-5.0));
		assertTrue(DragonFireBarrage.trailSamples(10_000.0) <= cap,
				"드래곤이 멀수록 패킷이 늘면, 하필 가장 안 보이는 때가 가장 비싸다");
		assertEquals(DragonFireBarrage.trailSamples(10_000.0),
				DragonFireBarrage.trailSamples(500.0), "상한에 닿은 뒤로는 더 늘지 않는다");
	}

	/**
	 * <b>고리를 나눠 그려도 고리가 끊기지 않는다.</b>
	 *
	 * <p>고리 하나를 {@code MARK_STRIDE} 틱에 한 번 그린다. 먼지 수명이 최소 8틱이라
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」) 그보다 짧은 간격으로
	 * 돌아와야 고리가 늘 보인다. 그리고 스무 개가 한 틱에 몰리지 않고 고르게 갈려야 점 예산이 선다.
	 */
	@Test
	void 고리를_나눠_그려도_모든_고리가_먼지_수명_안에_다시_그려진다() {
		assertTrue(DragonFireBarrage.MARK_STRIDE >= 1 && DragonFireBarrage.MARK_STRIDE < 8,
				"MARK_STRIDE 가 " + DragonFireBarrage.MARK_STRIDE + " — 8 이상이면 먼지가 먼저 죽어 고리가 끊긴다");
		int rings = DragonFireBarrage.LINE_COUNT * DragonFireBarrage.SHELL_COUNT;
		int cap = (rings + DragonFireBarrage.MARK_STRIDE - 1) / DragonFireBarrage.MARK_STRIDE;
		for (long now = -5L; now < 200L; now++) {
			int drawn = 0;
			for (int ring = 0; ring < rings; ring++) {
				if (DragonFireBarrage.drawsRingAt(ring, now)) {
					drawn++;
				}
			}
			assertTrue(drawn <= cap, "틱 " + now + " 에 고리 " + drawn + "개 — 한 틱에 몰렸다");
		}
		for (int ring = 0; ring < rings; ring++) {
			for (long start = 0L; start < 50L; start++) {
				boolean seen = false;
				for (long now = start; now < start + DragonFireBarrage.MARK_STRIDE; now++) {
					seen |= DragonFireBarrage.drawsRingAt(ring, now);
				}
				assertTrue(seen, "고리 " + ring + " 이 " + DragonFireBarrage.MARK_STRIDE
						+ "틱 동안 한 번도 안 그려진다: " + start);
			}
		}
	}

	@Test
	void 꼬리_점_사이가_선으로_읽힐_만큼만_벌어진다() {
		for (double length = 2.0; length <= DragonFireBarrage.TRAIL_KEPT_LENGTH; length += 0.5) {
			assertTrue(DragonFireBarrage.trailGap(length) <= DragonFireBarrage.TRAIL_MAX_GAP + 1.0E-9,
					"길이 " + length + " 에서 점이 " + DragonFireBarrage.trailGap(length)
							+ " 칸씩 벌어진다 — 「드래곤이 던졌다」가 점선으로 끊긴다");
		}
	}

	// ------------------------------------------------------------------ 피해

	/**
	 * 한 틱에 받을 수 있는 가장 큰 피해가 팀 체력에 못 미친다 — <b>완전무장 기준이다.</b>
	 *
	 * <h2>⚠ 날값 비교로 되돌리지 말 것</h2>
	 *
	 * <p>전에는 {@link DragonFireBarrage#worstCaseTickDamage()} 를 그대로 20 과 견줬다. 그런데
	 * 사람이 「다이아셋 + 보호 인챈트까지 하고 맞는 것까지 고려해야 한다」고 정해 피해가 6 에서
	 * 23 으로 올라갔고, <b>날값은 이제 팀 체력보다 크다.</b> 감쇠를 거는 것은 {@link GearedDamage}
	 * 이고 하드 난이도 곱도 거기서 함께 태운다 — 이 패시브의 피해원이
	 * {@code explosion(null, null)} 이라 {@code scaling: always} 로 1.5배가 먼저 걸린다.
	 *
	 * <p>⚠ <b>맨몸이면 34.5 가 그대로 들어가 한 발에 전멸이다.</b> 사람이 그 사실을 듣고도
	 * 「3대」로 가자고 했으므로 값을 되돌리지 말 것.
	 */
	@Test
	void 한_틱에_받을_수_있는_가장_큰_피해가_무장_기준_팀_체력에_못_미친다() {
		assertEquals(DragonFireBarrage.DAMAGE_PER_BLAST * DragonFireBarrage.MAX_CONCURRENT_BLASTS,
				DragonFireBarrage.worstCaseTickDamage(), 1.0E-6);
		float perShell = GearedDamage.afterGear(DragonFireBarrage.DAMAGE_PER_BLAST,
				GearedDamage.Source.EXPLOSION);
		// 감쇠는 한 방마다 따로 건다. 한 틱의 두 발은 서로 다른 두 사람의 몫이다(victims).
		float geared = perShell * DragonFireBarrage.MAX_CONCURRENT_BLASTS;
		assertTrue(geared < TEAM_HEALTH,
				"「즉사 메커닉 0개」가 깨졌다. 완전무장하고도 한 틱에 " + geared
						+ " — 두 줄이 한 틱에 두 사람을 때리는 판이다");
		assertTrue(perShell < TEAM_HEALTH / 2.0F,
				"원 하나가 팀 체력의 절반을 깎으면 두 발째가 곧 전멸이다. 무장 기준 " + perShell);
		assertTrue(GearedDamage.wipesInThree(perShell),
				"사람이 정한 것은 「큰자리는 3대맞으면 죽는거로」다. 무장 기준 한 발 " + perShell
						+ " · 두 발 " + perShell * 2 + " · 세 발 " + perShell * 3);
		assertTrue(DragonFireBarrage.worstCaseTickDamage() > TEAM_HEALTH,
				"적힌 값이 팀 체력보다 작아졌다면 무장 기준이 아니라 날값으로 되돌아간 것이다 —"
						+ " GearedDamage 의 설명을 먼저 읽을 것");
	}

	/**
	 * <b>제자리에 서 있으면 한 발로 끝난다.</b>
	 *
	 * <p>원끼리 겹치지 않으므로({@link #원끼리_반경의_두_배보다_멀다}) 움직이지 않는 사람은 한 원에만
	 * 들어 있다. 「들어가면 죽는다」가 아니라 <b>「한 발 맞고 배운다」</b>인 것이 이 패턴의 뜻이다.
	 */
	@Test
	void 제자리에_서_있으면_한_발로_끝난다() {
		assertEquals(1, DragonFireBarrage.chainHits(0.0),
				"안 움직이는 사람이 두 발을 맞으면 원이 겹친 것이다");
		assertEquals(DragonFireBarrage.DAMAGE_PER_BLAST, DragonFireBarrage.chainDamage(0.0), 1.0E-6);
		assertTrue(gearedChain(0.0) < TEAM_HEALTH / 2.0F,
				"한 발에 팀 체력 절반이 날아가면 배우기 전에 죽는다. 무장 기준 " + gearedChain(0.0));
	}

	/**
	 * <b>선을 따라 도망치면 원 두 개에 걸리고, 포격은 쿨타임을 무시하므로 두 발 다 들어간다.</b>
	 *
	 * <p>연달아 맞는 것은 막지 않는다 — 그것이 이 패턴의 긴장이다. 다만 <b>몇 발까지인지는 값에서
	 * 세어 두어야</b> 팀 체력 20 과 견줄 수 있다. 포격은 초당 20칸으로 전진하는데 사람이 맨몸으로
	 * 낼 수 있는 가장 빠른 속도가 7.13칸/초라 따라잡히고, 그때 걸리는 것이 원 두 개다.
	 *
	 * <p>⚠ <b>2026-10-04 에 「6할」이 한 번 사라졌다가 돌아왔다.</b> 사람이 정한 0.4초(8틱)는 바닐라
	 * 피격 쿨타임 10틱보다 짧아 둘째 원이 통째로 먹히고 한 발(6.77)만 들어갔다. 그 사실을 듣고
	 * 사람이 같은 날 <b>「포격 무시」</b>를 골라 포격만 쿨타임을 무시하게 했고, 그래서 다시 두 발 —
	 * 무장 기준 <b>13.5(팀 체력 6할 7푼)</b> 이다.
	 *
	 * <p>감쇠는 <b>발마다 따로</b> 건다({@link GearedDamage} 의 「감쇠는 한 방마다 걸린다」).
	 */
	@Test
	void 선을_따라_도망치면_원_둘에_걸리고_포격_무시라_두_발_다_들어간다() {
		float perShell = GearedDamage.afterGear(DragonFireBarrage.DAMAGE_PER_BLAST,
				GearedDamage.Source.EXPLOSION);
		for (double speed : new double[] {WALK, SPRINT, SPRINT_JUMP}) {
			assertEquals(2, DragonFireBarrage.chainHits(speed),
					"속도 " + speed + "칸/초에서 원 " + DragonFireBarrage.chainHits(speed)
							+ "개에 걸린다 — 간격이나 반경을 고쳤다면 DAMAGE_PER_BLAST 를 함께 볼 것");
			assertEquals(2, DragonFireBarrage.landedChainHits(speed),
					"속도 " + speed + " 에서 들어가는 발이 둘이 아니다 — 포격이 피격 쿨타임을 무시하지"
							+ " 않게 된 것이다(사람이 2026-10-04 에 「포격 무시」를 골랐다)");
			assertTrue(gearedChain(speed) < TEAM_HEALTH,
					"도망치다 전멸하면 도망칠 이유가 없다. 속도 " + speed + " 에서 무장 기준 "
							+ gearedChain(speed));
		}
		// 실제 셈 — 감쇠한 한 발에 발 수를 곱한다. 46 을 통째로 감쇠하면 안 된다.
		assertEquals(perShell * 2.0F, gearedChain(SPRINT_JUMP), 1.0E-4F);
		assertEquals(13.5F, gearedChain(SPRINT_JUMP), 0.1F,
				"0.4초 간격 + 포격 무시에서 도망친 사람이 실제로 받는 몫이다 — 팀 체력의 6할 7푼");
		assertTrue(GearedDamage.wipesInThree(perShell) && gearedChain(SPRINT_JUMP) < TEAM_HEALTH,
				"두 발로 전멸하면 「3대」가 아니라 「2대」다. 무장 기준 두 발 " + gearedChain(SPRINT_JUMP));
		assertEquals(DragonFireBarrage.DAMAGE_PER_BLAST * 2.0F,
				DragonFireBarrage.chainDamage(SPRINT_JUMP), 1.0E-6,
				"chainDamage 는 들어가는 발의 날값 합이다 — 두 발 46");
	}

	/**
	 * 그 속도로 도망친 사람이 <b>완전무장하고</b> 실제로 받는 합계.
	 *
	 * <p>발 수를 받아 <b>한 발씩</b> 감쇠한 뒤 더한다. 발 수는 실제로 들어가는 것만 센다
	 * ({@code landedChainHits} — 포격 무시라 지금은 걸린 원 수와 같다).
	 */
	private static float gearedChain(double blocksPerSecond) {
		return GearedDamage.afterGear(DragonFireBarrage.DAMAGE_PER_BLAST,
				GearedDamage.Source.EXPLOSION) * DragonFireBarrage.landedChainHits(blocksPerSecond);
	}

	@Test
	void 포격만큼_빠르면_전부_맞는다() {
		// 경계를 적어 둔다. 포격은 초당 간격÷0.4 칸으로 전진하므로, 신속 물약으로 그 속도가
		// 나오면 원을 끼고 함께 달리는 셈이 된다. 스스로 고른 것이라 「대응 불가」가 아니지만,
		// 계산이 조용히 1 을 돌려주고 끝나지는 않아야 한다.
		double wave = DragonFireBarrage.shellGap() * 20.0 / DragonFireBarrage.BLAST_INTERVAL_TICKS;
		assertEquals(DragonFireBarrage.SHELL_COUNT, DragonFireBarrage.chainHits(wave));
		assertTrue(wave > SPRINT_JUMP * 1.5,
				"포격이 사람 걸음만큼 느리면 도망칠 수 없다. 포격 속도 " + wave + "칸/초");
	}

	@Test
	void 열_개를_다_맞으면_무장하고도_팀_체력의_세_배다() {
		// 원을 하나씩 밟아 가며 일부러 다 맞는 경우다. 막을 수 없고 막을 것도 아니지만, 숫자를
		// 적어 두면 다음 사람이 보고 판단할 수 있다. 적힌 값으로는 230 이고 완전무장해도 67.7 —
		// 어느 쪽으로 재든 팀 체력 20 의 세 배를 넘는다.
		assertEquals(230.0F, DragonFireBarrage.SHELL_COUNT * DragonFireBarrage.DAMAGE_PER_BLAST,
				1.0E-6);
		float geared = GearedDamage.afterGear(DragonFireBarrage.DAMAGE_PER_BLAST,
				GearedDamage.Source.EXPLOSION) * DragonFireBarrage.SHELL_COUNT;
		assertTrue(geared > TEAM_HEALTH * 3.0F,
				"무장 기준 합계 " + geared + " 다. 세 배 아래로 내려왔다면 값이 날값 기준으로"
						+ " 되돌아간 것이 아닌지 볼 것");
	}

	/**
	 * 터지는 간격이 사람이 정한 0.4초다 — <b>바닐라 피격 쿨타임보다 짧다는 것을 알고 둔다.</b>
	 *
	 * <p>전에는 이 시험이 「10틱보다 넓다」를 못박았다. 사람이 2026-10-04 에 「간격도 0.4초」라고
	 * 정해 그 규칙을 내려놓았다. 그러자 둘째 원이 쿨타임에 먹혔고, 사람이 같은 날 「포격 무시」를
	 * 골라 <b>포격만 쿨타임을 무시하게</b> 했다({@code DragonFireBarrage.strike}). 그래서 걸린
	 * 원은 전부 들어간다({@code landedHits}).
	 */
	@Test
	void 터지는_간격이_사람이_정한_0_4초다() {
		assertEquals(8, DragonFireBarrage.BLAST_INTERVAL_TICKS, "사람이 정한 것은 0.4초다");
		assertEquals(72, DragonFireBarrage.BARRAGE_TICKS,
				"열 개가 0.4초 간격이면 3.6초다 — 값이 아니라 개수와 간격에서 나오는 결과다");
		assertEquals(10, DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS,
				"26.3 hurtServer 의 damageCooldownTime > 10 이다");
		assertEquals(20, DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS,
				"26.3 hurtServer 의 bipush 20; putfield damageCooldownTime 이다");
		assertTrue(DragonFireBarrage.BLAST_INTERVAL_TICKS < DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS,
				"간격이 쿨타임보다 넓어졌다면 「포격 무시」가 없어도 된다 — strike 와 이 시험을 함께 볼 것");
		int left = DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS - DragonFireBarrage.BLAST_INTERVAL_TICKS;
		assertTrue(left > DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS,
				"8틱 뒤의 쿨타임 " + left + " 은 문턱 위다 — 바닐라대로면 둘째 원이 먹힌다");

		// 연달아 걸린 원 n 개 중 들어가는 것 — 포격 무시라 전부.
		assertEquals(0, DragonFireBarrage.landedHits(0));
		assertEquals(1, DragonFireBarrage.landedHits(1));
		assertEquals(2, DragonFireBarrage.landedHits(2),
				"8틱 뒤의 둘째 원이 쿨타임 12 에 먹혔다 — 포격이 쿨타임을 무시하지 않는다");
		assertEquals(3, DragonFireBarrage.landedHits(3));
		assertEquals(DragonFireBarrage.SHELL_COUNT,
				DragonFireBarrage.landedHits(DragonFireBarrage.SHELL_COUNT));
	}

	// ------------------------------------------------------------------ 포격 무시 (2026-10-04)

	/**
	 * <b>포격만 쿨타임을 무시하고, 다른 피해원은 같은 쿨타임에 막힌다.</b>
	 *
	 * <p>사람이 2026-10-04 에 「포격 무시」를 골랐다. 다른 피해원의 쿨타임은 풀리면 안 된다.
	 *
	 * <p>바닐라의 판정은 {@link com.sharedfate.perk.PerkDamage#effectiveAmount} 가 한 줄씩 옮겨 둔
	 * 순수 계산으로 본다. 앞 원을 맞은 지 8틱 — 쿨타임 12, {@code lastHurt} 는 하드 곱이 걸린
	 * 34.5 다. 같은 쿨타임 앞에서 포격은 {@code cooldownForStrike} 를 거쳐 온전히 들어가고, 거치지
	 * 않는 피해(다른 모든 피해원)는 버려진다.
	 */
	@Test
	void 포격만_쿨타임을_무시하고_다른_피해원은_막힌다() {
		float hard = DragonFireBarrage.DAMAGE_PER_BLAST * 1.5F;
		int cooldown = DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS
				- DragonFireBarrage.BLAST_INTERVAL_TICKS;

		assertEquals(0.0F, com.sharedfate.perk.PerkDamage.effectiveAmount(hard, hard, cooldown, false),
				1.0E-6, "다른 피해원은 쿨타임 " + cooldown + " 에서 같은 크기가 버려져야 한다 — 바닐라 그대로");
		assertEquals(0.0F, com.sharedfate.perk.PerkDamage.effectiveAmount(4.5F, hard, cooldown, false),
				1.0E-6, "포격 직후의 좀비 한 대도 막혀야 한다");
		assertEquals(hard, com.sharedfate.perk.PerkDamage.effectiveAmount(hard, hard,
						DragonFireBarrage.cooldownForStrike(cooldown), false),
				1.0E-6, "포격은 같은 쿨타임 앞에서 온전히 들어가야 한다 — 「포격 무시」");

		// 지우는 것은 문턱 위일 때뿐이다. 문턱 아래는 어차피 「새로 맞음」이라 손대지 않는다.
		assertEquals(DragonFireBarrage.STRIKE_COOLDOWN_TICKS, DragonFireBarrage.cooldownForStrike(20));
		assertEquals(DragonFireBarrage.STRIKE_COOLDOWN_TICKS, DragonFireBarrage.cooldownForStrike(11));
		assertEquals(10, DragonFireBarrage.cooldownForStrike(10));
		assertEquals(0, DragonFireBarrage.cooldownForStrike(0));
		assertTrue(DragonFireBarrage.STRIKE_COOLDOWN_TICKS <= DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS,
				"지운 값이 문턱 위면 바닐라가 여전히 「쿨타임 안」 갈래로 간다");

		assertFalse(DragonFireBarrage.ignoresCooldown(),
				"포격을 넣는 중이 아닌데 「쿨타임 무시」 표시가 켜져 있다 — 다른 피해원까지 풀린다");
	}

	/**
	 * <b>포격으로 맞은 뒤에는 바닐라대로 새 쿨타임이 걸린다.</b> 우회는 「들어갈 때 이전 쿨타임을
	 * 무시」뿐이다.
	 *
	 * <p>바닐라 판정을 쿨타임 상태까지 돌려주는 순수 계산({@link SpreadDamageManager#gate})으로
	 * 한 줄씩 굴린다. 포격이 들어간 뒤의 쿨타임은 20, {@code lastHurt} 는 포격 피해량이고, 그
	 * 상태에서 10틱 안에 온 다른 피해는 바닐라대로 막힌다.
	 */
	@Test
	void 포격_뒤에는_바닐라대로_새_쿨타임이_걸린다() {
		float hard = DragonFireBarrage.DAMAGE_PER_BLAST * 1.5F;
		// 좀비에게 맞은 지 3틱. 바닐라대로면 포격은 34.5 - 4.5 = 30 만 들어간다.
		int before = DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS - 3;
		SpreadDamageManager.Gate struck = SpreadDamageManager.gate(hard, 4.5F,
				DragonFireBarrage.cooldownForStrike(before), false);
		assertEquals(hard, struck.accepted(), 1.0E-6, "포격이 앞 피해의 쿨타임에 깎였다");
		assertEquals(DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS, struck.invulnerableTicks(),
				"포격이 맞았는데 새 쿨타임이 안 걸렸다");
		assertEquals(hard, struck.lastAmount(), 1.0E-6, "lastHurt 는 포격 피해량이어야 한다");

		// 맞은 뒤 남길 값 — 바닐라가 쓴 20 을 그대로 둔다(되돌리지 않는다).
		assertEquals(DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS,
				DragonFireBarrage.cooldownAfterStrike(before, DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS),
				"포격이 맞은 뒤 쿨타임을 원래 값으로 되돌렸다 — 포격 직후 다른 피해가 쿨타임 없이 얹힌다");

		// 포격 직후 9틱 안의 다른 피해원은 막힌다.
		for (int after = 0; after < DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS
				- DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS; after++) {
			int cooldown = struck.invulnerableTicks() - after;
			assertEquals(0.0F, SpreadDamageManager.gate(4.5F, struck.lastAmount(), cooldown, false)
					.accepted(), 1.0E-6, "포격 " + after + "틱 뒤의 좀비 한 대가 쿨타임을 뚫었다");
		}
	}

	/**
	 * <b>바닐라가 받아들이지 않은 포격은 지운 쿨타임을 되돌린다.</b>
	 *
	 * <p>시련 정지·게임 시작 전의 HEAD 취소, {@code Player.hurtServer} 의 조기 반환 같은 길에서는
	 * 바닐라가 쿨타임을 쓰지 않아 지운 값(0)이 그대로 남는다. 그대로 두면 다음 다른 피해원이 쿨타임
	 * 없이 들어간다 — 「포격 무시」가 다른 피해원으로 새는 길이다.
	 */
	@Test
	void 받아들여지지_않은_포격은_지운_쿨타임을_되돌린다() {
		int strike = DragonFireBarrage.STRIKE_COOLDOWN_TICKS;
		assertEquals(12, DragonFireBarrage.cooldownAfterStrike(12, strike),
				"취소된 포격이 지운 쿨타임을 남겼다 — 다음 피해원이 쿨타임 없이 들어간다");
		assertEquals(20, DragonFireBarrage.cooldownAfterStrike(20, strike));
		// 지우지 않았으면(문턱 아래) 바닐라가 남긴 값 그대로다.
		assertEquals(5, DragonFireBarrage.cooldownAfterStrike(5, 5));
		assertEquals(0, DragonFireBarrage.cooldownAfterStrike(0, 0));
		assertEquals(20, DragonFireBarrage.cooldownAfterStrike(5, 20));
		assertNotEquals(DragonFireBarrage.STRIKE_COOLDOWN_TICKS, DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS,
				"지운 값과 바닐라가 채우는 값이 같으면 「맞았는가」를 가려낼 수 없다");
	}

	/**
	 * 「맞았는가」를 쿨타임 값으로 가려내도 되는 근거를 <b>바닐라 클래스 파일째로</b> 붙든다.
	 *
	 * <p>{@code DragonFireBarrage.cooldownAfterStrike} 는 호출 뒤에도 쿨타임이 0 이면 「안 맞았다」로
	 * 본다. 그것이 참이려면 피해 사슬({@code ServerPlayer} → {@code Player} → {@code LivingEntity}
	 * 의 {@code hurtServer}) 안에서 {@code damageCooldownTime} 에 쓰는 값이 <b>20 하나뿐</b>이어야
	 * 한다. 문턱 10 도 같은 자리에서 확인한다.
	 */
	@Test
	void 바닐라_피해_사슬이_쿨타임에_쓰는_값은_20_하나뿐이다() {
		List<String> living = cooldownWritesIn(net.minecraft.world.entity.LivingEntity.class);
		assertEquals(List.of("write:" + DragonFireBarrage.VANILLA_FRESH_COOLDOWN_TICKS,
						"gate:" + (float) DragonFireBarrage.VANILLA_DAMAGE_COOLDOWN_TICKS)
						.stream().sorted().toList(),
				living.stream().sorted().toList(),
				"26.3 LivingEntity.hurtServer 의 쿨타임 판정이 바뀌었다 — strike 의 되돌림 판정을 다시 볼 것");
		assertEquals(List.of(), cooldownWritesIn(net.minecraft.world.entity.player.Player.class),
				"Player.hurtServer 가 쿨타임을 만진다");
		assertEquals(List.of(), cooldownWritesIn(net.minecraft.server.level.ServerPlayer.class),
				"ServerPlayer.hurtServer 가 쿨타임을 만진다");
	}

	/**
	 * 우리 쪽에서 피격 쿨타임을 <b>쓰는</b> 곳이 정해진 둘뿐이다 — 포격 무시가 다른 피해원으로 새지
	 * 않았다.
	 *
	 * <p>{@code DragonFireBarrage.strike}(포격 무시)와 {@code SpreadDamageManager.deliver}(「완충」의
	 * 몫 넣기, 넣은 뒤 원래 값으로 되돌린다). 그 밖에서 쿨타임을 쓰기 시작하면 다른 피해원의
	 * 쿨타임이 풀리는 길이 생긴다. 그리고 이 패시브가 {@code hurtServer} 를 부르는 곳도
	 * {@code strike} 하나여야 한다 — 다른 데서 부르면 그 발은 쿨타임을 무시하지 못한다.
	 */
	@Test
	void 피격_쿨타임을_쓰는_곳은_포격과_완충_둘뿐이다() throws Exception {
		Set<String> writers = new HashSet<>();
		Set<String> barrageHurts = new HashSet<>();
		for (java.nio.file.Path file : mainClassFiles()) {
			byte[] bytes = java.nio.file.Files.readAllBytes(file);
			ClassReader reader = new ClassReader(bytes);
			String owner = reader.getClassName();
			reader.accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitFieldInsn(int opcode, String fieldOwner, String field,
								String fieldDescriptor) {
							if (opcode == Opcodes.PUTFIELD && field.equals("damageCooldownTime")) {
								writers.add(simple(owner) + "." + name);
							}
						}

						@Override
						public void visitMethodInsn(int opcode, String callOwner, String callName,
								String callDescriptor, boolean isInterface) {
							if (owner.equals("com/sharedfate/sync/DragonFireBarrage")
									&& callName.equals("hurtServer")) {
								barrageHurts.add(name);
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}
		assertEquals(Set.of("DragonFireBarrage.strike", "SpreadDamageManager.deliver"), writers,
				"피격 쿨타임을 쓰는 곳이 바뀌었다 — 다른 피해원의 쿨타임이 풀리는 길인지 볼 것");
		assertEquals(Set.of("strike"), barrageHurts,
				"연쇄 포격이 strike 밖에서 hurtServer 를 부른다 — 그 발은 「포격 무시」를 타지 않는다");
	}

	/** 바닐라 클래스의 {@code hurtServer} 가 쿨타임을 쓰는 값({@code write:})과 견주는 문턱({@code gate:}). */
	private static List<String> cooldownWritesIn(Class<?> type) {
		List<String> found = new ArrayList<>();
		String path = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(path)) {
			assertNotNull(in, path + " 을 찾지 못했다");
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					if (!name.equals("hurtServer")) {
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9) {
						private Integer lastInt;
						private boolean readCooldown;

						@Override
						public void visitIntInsn(int opcode, int operand) {
							lastInt = opcode == Opcodes.NEWARRAY ? null : operand;
						}

						@Override
						public void visitInsn(int opcode) {
							if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
								lastInt = opcode - Opcodes.ICONST_0;
							} else if (opcode != Opcodes.I2F) {
								lastInt = null;
							}
						}

						@Override
						public void visitLdcInsn(Object value) {
							if (readCooldown && value instanceof Float threshold) {
								found.add("gate:" + threshold);
							}
							readCooldown = false;
							lastInt = value instanceof Integer constant ? constant : null;
						}

						@Override
						public void visitFieldInsn(int opcode, String owner, String field,
								String fieldDescriptor) {
							if (field.equals("damageCooldownTime")) {
								if (opcode == Opcodes.PUTFIELD) {
									found.add("write:" + lastInt);
								} else if (opcode == Opcodes.GETFIELD) {
									readCooldown = true;
								}
							}
							lastInt = null;
						}
					};
				}
			}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		} catch (IOException failed) {
			throw new AssertionError(failed);
		}
		return found;
	}

	/** {@code com/sharedfate/sync/Foo$Bar} → {@code Foo$Bar}. */
	private static String simple(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}

	/** 본 소스 산출물의 클래스 파일 전부. {@code TrialWarningTest} 와 같은 방식이다. */
	private static List<java.nio.file.Path> mainClassFiles() throws Exception {
		java.net.URL url = DragonFireBarrage.class.getResource("DragonFireBarrage.class");
		assertNotNull(url, "DragonFireBarrage.class 를 찾지 못했다");
		// .../com/sharedfate/sync/DragonFireBarrage.class 에서 넷 올라가면 산출물 뿌리다.
		java.nio.file.Path root = java.nio.file.Path.of(url.toURI())
				.getParent().getParent().getParent().getParent();
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".class"))
					.sorted()
					.toList();
		}
	}

	// ------------------------------------------------------------------ 상태가 새지 않는가

	@Test
	void 포격이_clearState_로_깨끗이_지워진다() {
		assertNull(DragonFireBarrage.active(), "처음에는 아무 포격도 없어야 한다");

		DragonFireBarrage.remember(DragonFireBarrage.planVolley(7L, 0.42, 0.5));
		assertNotNull(DragonFireBarrage.active());

		DragonFireBarrage.clearState();
		assertNull(DragonFireBarrage.active(),
				"남으면 새 월드에서 아무도 예고한 적 없는 자리가 터진다");
	}

	@Test
	void 끝난_주기_기록도_함께_지워진다() {
		// 주기 번호가 남아 있으면 새 판의 같은 번호 주기가 「이미 판단했다」로 건너뛰어져
		// 그 판의 첫 포격이 통째로 빠진다.
		DragonFireBarrage.notePlanned(3L);
		assertEquals(3L, DragonFireBarrage.plannedCycle());

		DragonFireBarrage.clearState();
		assertEquals(Long.MIN_VALUE, DragonFireBarrage.plannedCycle(),
				"주기 기록이 남으면 새 판에서 첫 포격이 건너뛰어진다");
	}

	/**
	 * 전투가 바뀌면 스스로 비운다.
	 *
	 * <p>드래곤을 잡고 다시 들어가면 주기 번호가 0 부터 다시 매겨진다. 지난 전투의 기록이 남아
	 * 있으면 새 판의 그 주기가 「이미 판단했다」로 건너뛰어져 <b>첫 포격이 안 나온다.</b>
	 * 「가끔 안 나온다」는 아무도 못 잡는 증상이라, 배선이 비워 주기를 기다리지 않는다.
	 */
	@Test
	void 전투가_바뀌면_지난_판의_포격을_스스로_버린다() {
		DragonFireBarrage.beginFight(1_000L);
		DragonFireBarrage.remember(DragonFireBarrage.planVolley(2L, 0.6, 0.3));
		DragonFireBarrage.notePlanned(2L);

		DragonFireBarrage.beginFight(1_000L);
		assertNotNull(DragonFireBarrage.active(), "같은 전투 안에서는 아무것도 버리지 않는다");
		assertEquals(2L, DragonFireBarrage.plannedCycle());

		DragonFireBarrage.beginFight(9_999L);
		assertNull(DragonFireBarrage.active(), "새 전투인데 지난 판의 포격이 남아 있다");
		assertEquals(Long.MIN_VALUE, DragonFireBarrage.plannedCycle(),
				"주기 기록이 남으면 새 판의 그 주기가 통째로 건너뛰어진다");
	}

	// ------------------------------------------------------------------ 바이트코드가 지키는 약속

	/**
	 * 연출이 아레나 반대편까지 보인다.
	 *
	 * <p>이 패시브가 보여 주는 것은 「아레나를 가로질러 원 열 개가 늘어섰다」이고, 그것은 반대편
	 * 끝까지 보여야 성립한다. 짧은 형태는 32칸에서 잘리는데 아레나는 80칸이라, 되돌리면 절반이
	 * 아무에게도 안 보이고 그래도 빌드와 로그는 조용하다.
	 *
	 * <p>바닥 고리는 {@code TrialWarning.markGround} 가 긴 형태로 내보낸다. 이 파일이 직접 보내는
	 * 것은 화염구 궤적과 착탄 연출이고, 드래곤은 100칸 밖 하늘에 있을 수 있어 더 멀다.
	 */
	@Test
	void 연출은_긴_거리로_나간다() throws IOException {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 아레나 반대편에서는 화염구가 안 보인다");
	}

	/**
	 * 바닥 표식이 색 규약의 빨강 하나뿐이다.
	 *
	 * <p>「서 있으면 죽는다」가 정확히 이 원의 뜻이고, 규약에 없는 색을 새로 만들면 그 순간 규약이
	 * 장식이 된다. 색을 아예 고르지 않는 것이 가장 확실한 방법이라, 색 없는
	 * {@code TrialWarning.markGround} 만 부른다.
	 */
	@Test
	void 바닥_표식은_규약의_빨강_하나뿐이다() throws IOException {
		String bytes = classBytes();
		assertTrue(bytes.contains("markGround"),
				"바닥 표식을 그리지 않는다. 자막이 없는 지금 「어디가 위험한가」를 말하는 것이 이 고리뿐이다");
		assertFalse(bytes.contains("dust"),
				"색을 직접 고르기 시작했다 — 색 없는 markGround 만 쓰면 빨강 말고 나올 수가 없다");
		for (String other : new String[] {"LIGHTNING", "SHOVE", "MARKED", "DustParticleOptions"}) {
			assertFalse(bytes.contains(other),
					"규약의 다른 색이 들어왔다: " + other + " — 이 원의 뜻은 빨강(DEADLY)이다");
		}
	}

	/**
	 * 블록을 건드리지 않는다.
	 *
	 * <p>바닥을 지우면 공허 낙사가 되고, 그것은 이 전투가 유일하게 금지한 「대응 불가 즉사」다.
	 * 게다가 구멍은 판이 끝나도 남아 다음 전투의 발판이 달라진다. 블록을 <b>읽는</b> 하이트맵은
	 * 있어야 원이 바닥에 붙으므로 <b>쓰는</b> 쪽만 막는다.
	 */
	@Test
	void 블록을_한_칸도_바꾸지_않는다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlock"), "블록을 놓으면 다음 전투의 발판이 달라진다");
		assertFalse(bytes.contains("destroyBlock"), "바닥을 지우면 공허 낙사다");
		assertFalse(bytes.contains("removeBlock"));
		assertTrue(bytes.contains("getHeightmapPos"), "지면 높이는 읽어야 원이 바닥에 붙는다");
	}

	/**
	 * 바닐라 화염구 엔티티를 띄우지 않는다.
	 *
	 * <p>{@code LargeFireball} 의 블록 파괴는 {@code mobGriefing} 게임룰에 묶여 있어 우리만 끌 수
	 * 없고, 폭발에는 불도 딸려 붙는다. {@code TrialFireball} 이 기둥 화염구에서 이미 푼 문제라
	 * 같은 답을 쓴다 — 날아오는 모습은 파티클, 착탄은 직접 계산.
	 */
	@Test
	void 바닐라_화염구_엔티티를_띄우지_않는다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains("LargeFireball"), "블록 파괴를 우리가 못 끈다");
		assertFalse(bytes.contains("SmallFireball"));
		assertFalse(bytes.contains("DragonFireball"));
		assertFalse(bytes.contains("addFreshEntity"), "엔티티를 띄우면 clearState 로 못 지운다");
		assertTrue(bytes.contains("EXPLOSION_EMITTER"), "착탄이 안 보이면 무엇에 맞았는지 못 읽는다");
	}

	/**
	 * 페이즈 API 를 한 번도 부르지 않는다.
	 *
	 * <p>앞 작업에서 데었다 — 「표적」이 드래곤을 돌진 페이즈로 밀었더니 <b>착지를 아예 하지
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
	 * 넣는다 — 팀 공유 체력이 20 인데 10 은 절반이고 밀치기는 섬 밖으로 미는 길이다. 같은 구간의
	 * {@code checkWalls} 는 {@code DRAGON_IMMUNE} 이 아닌 블록을 {@code removeBlock} 하므로,
	 * 사람이 놓은 블록이 드래곤의 손으로 부서진다.
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

	// ------------------------------------------------------------------ 거들

	/** 그 점에 가장 가까운 원. 「첫 번째로 터지는 것이 끝 쪽인가」를 물을 때 쓴다. */
	private static Vec3 nearest(List<Vec3> shells, Vec3 to) {
		List<Vec3> sorted = new ArrayList<>(shells);
		sorted.sort((one, other) -> Double.compare(one.distanceTo(to), other.distanceTo(to)));
		return sorted.getFirst();
	}

	/** 클래스 파일을 통째로 읽어 온다. 상수 풀에 무엇이 있고 없는지를 묻는 시험들이 쓴다. */
	private static String classBytes() throws IOException {
		try (InputStream in = DragonFireBarrage.class
				.getResourceAsStream("/com/sharedfate/sync/DragonFireBarrage.class")) {
			if (in == null) {
				throw new IOException("DragonFireBarrage 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
