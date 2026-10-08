package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「연결된 수정」에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>보호막을 실제로 거는 것과 「부서졌다」를 거두는 것은 개체가 있어야 해서 여기서 볼 수 없다.
 * 그런데 이 카드가 망가지는 길은 대부분 계산 쪽이다 — <b>엉뚱한 크리스탈이 잠긴다</b>,
 * <b>보호막이 카드에 적힌 것보다 오래 간다</b>, <b>영영 안 풀린다</b>, 그리고 <b>걸렸는데 아무것도
 * 안 보인다</b>. 전멸하면 월드가 지워지는 게임이라 이것들은 돌려 보고 발견할 수 없다.
 *
 * <p>보호막은 이제 바닐라 무적 칸이 아니라 <b>이 클래스가 들고 있는 봉인</b>이다
 * ({@code TrialCrystalLink.sealedCrystal}). 그래서 <b>아무도 깎아 주지 않는다</b> — 「영영 안
 * 깨지는 크리스탈」을 막는 것이 {@code guardTicks} 로 미루는 유효기한 하나뿐이라, 그 값이 언제나
 * 유한하고 {@code sealFresh} 가 기한이 지난 봉인을 반드시 열어 준다는 것을 여기서 붙든다.
 */
class TrialCrystalLinkTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 실제 카드 값(30초). 시험이 현실과 붙어 있어야 값을 바꿀 때 여기가 먼저 운다. */
	private static final int SHIELD_TICKS = 600;

	/** 바닐라 엔드 기둥의 배치 — 반경 42 원 위에 열 개. {@code EndSpikeFeature} 와 같은 수다. */
	private static final double SPIKE_RADIUS = 42.0;
	private static final int SPIKE_COUNT = 10;

	/** 기둥 꼭대기의 높이 폭. 바닐라가 만드는 범위 그대로다(y 76~103). */
	private static final double LOWEST_TOP = 76.0;
	private static final double HIGHEST_TOP = 103.0;

	/** 정적 칸이 시험끼리 새지 않게 매번 비운다. */
	@BeforeEach
	void reset() {
		TrialCrystalLink.clearState();
	}

	// ------------------------------------------------------------------ 어느 것이 잠기는가

	@Test
	void 부서진_것의_이웃이_잠긴다() {
		List<Vec3> seats = ring(flatHeights());
		Vec3 broken = seats.get(0);
		List<Vec3> survivors = without(seats, 0);

		int pick = TrialCrystalLink.nearestIndex(List.of(broken), survivors);
		// 0번이 빠졌으므로 남은 목록의 첫째가 1번, 마지막이 9번이다. 둘은 0번의 양 이웃이라
		// 어느 쪽이 나오든 옳다. 「이웃이 아닌 것」이 나오는 것만이 사고다.
		assertTrue(pick == 0 || pick == survivors.size() - 1,
				"한쪽 끝부터 밀고 나가기를 막는 것이 이 카드다. 이웃이 아닌 것이 잠기면 뜻이 사라진다");
	}

	@Test
	void 남은_것이_없으면_아무것도_고르지_않는다() {
		// 마지막 하나를 부순 경우다. 걸 곳이 없는데 무언가를 고르면 그 순간 없는 개체를 만진다.
		assertEquals(-1, TrialCrystalLink.nearestIndex(List.of(new Vec3(42.5, 77.0, 0.5)), List.of()));
	}

	@Test
	void 부서진_것이_없으면_아무것도_고르지_않는다() {
		assertEquals(-1, TrialCrystalLink.nearestIndex(List.of(), ring(flatHeights())));
	}

	@Test
	void 한_틱에_여럿이_부서지면_가장_가까운_한_쌍만_잠근다() {
		List<Vec3> seats = ring(flatHeights());
		// 0번과 5번(원의 반대편)이 같은 틱에 부서졌다. 남은 것 중 5번의 이웃인 4·6번이 0번의
		// 이웃인 1·9번과 거리가 같으므로, 여기서는 「무엇을 고르든 이웃」임을 본다.
		List<Vec3> broken = List.of(seats.get(0), seats.get(5));
		List<Vec3> survivors = new ArrayList<>();
		for (int index = 0; index < seats.size(); index++) {
			if (index != 0 && index != 5) {
				survivors.add(seats.get(index));
			}
		}

		int pick = TrialCrystalLink.nearestIndex(broken, survivors);
		assertTrue(pick >= 0, "부서진 것이 둘이어도 보호막은 걸려야 한다");
		double best = Double.MAX_VALUE;
		for (Vec3 origin : broken) {
			for (Vec3 candidate : survivors) {
				best = Math.min(best, TrialCrystalLink.flatDistanceSqr(origin, candidate));
			}
		}
		assertEquals(best,
				Math.min(TrialCrystalLink.flatDistanceSqr(broken.get(0), survivors.get(pick)),
						TrialCrystalLink.flatDistanceSqr(broken.get(1), survivors.get(pick))),
				1.0E-9,
				"보호막은 하나뿐이다 — 모든 쌍 중 가장 가까운 쌍의 남은 쪽이어야 한다");
	}

	@Test
	void 거리가_같으면_언제나_먼저_온_것이_잠긴다() {
		Vec3 broken = new Vec3(0.0, 80.0, 0.0);
		List<Vec3> tied = List.of(new Vec3(10.0, 80.0, 0.0), new Vec3(-10.0, 95.0, 0.0));
		// 무작위로 고르면 같은 판이 두 번 다르게 돌아 사람이 규칙을 배울 수 없다.
		assertEquals(0, TrialCrystalLink.nearestIndex(List.of(broken), tied));
		assertEquals(0, TrialCrystalLink.nearestIndex(List.of(broken), tied));
	}

	// ------------------------------------------------------------------ 거리를 어떻게 재는가

	@Test
	void 거리는_높이를_보지_않는다() {
		Vec3 low = new Vec3(0.0, LOWEST_TOP, 0.0);
		Vec3 high = new Vec3(0.0, HIGHEST_TOP, 0.0);
		assertEquals(0.0, TrialCrystalLink.flatDistanceSqr(low, high), 1.0E-9,
				"같은 기둥 위아래는 거리가 0 이다. 세로가 섞이면 「원 위의 이웃」이 아니게 된다");
	}

	@Test
	void 높이를_넣었어도_이웃_순서는_같다() {
		// 클래스 설명에 적은 근거를 숫자로 못박는다 — 이웃까지의 현이 26, 한 칸 건너가 49 라
		// 기둥 높이 폭(27)으로는 순서가 뒤집히지 않는다. 가장 불리한 배치, 곧 이웃만 제일 높고
		// 나머지가 제일 낮은 배치로 본다.
		double[] heights = new double[SPIKE_COUNT];
		for (int index = 0; index < SPIKE_COUNT; index++) {
			heights[index] = LOWEST_TOP;
		}
		heights[1] = HIGHEST_TOP;
		heights[SPIKE_COUNT - 1] = HIGHEST_TOP;

		List<Vec3> seats = ring(heights);
		Vec3 broken = seats.get(0);
		List<Vec3> survivors = without(seats, 0);

		int flat = TrialCrystalLink.nearestIndex(List.of(broken), survivors);
		int solid = nearestBySolidDistance(broken, survivors);
		assertEquals(solid, flat,
				"세로를 넣고 빼는 것이 결과를 바꾸면 클래스 설명의 근거가 거짓이다");
	}

	// ------------------------------------------------------------------ 보호막이 사는 시간

	@Test
	void 보호막은_카드에_적힌_만큼만_간다() {
		long endsAt = 500L + SHIELD_TICKS;
		assertEquals(SHIELD_TICKS, TrialCrystalLink.remainingShield(500L, endsAt, SHIELD_TICKS));
		assertEquals(1, TrialCrystalLink.remainingShield(endsAt - 1L, endsAt, SHIELD_TICKS));
		assertEquals(0, TrialCrystalLink.remainingShield(endsAt, endsAt, SHIELD_TICKS));
	}

	@Test
	void 시간이_뒤로_가도_보호막이_길어지지_않는다() {
		long endsAt = 500L + SHIELD_TICKS;
		// 세션을 복원하면 흐른 시간이 뒤로 갈 수 있다. 자르지 않으면 30초짜리가 그만큼 늘어난다.
		assertEquals(SHIELD_TICKS, TrialCrystalLink.remainingShield(0L, endsAt, SHIELD_TICKS));
	}

	@Test
	void 시간이_지나면_남은_시간은_0_에서_멈춘다() {
		long endsAt = 500L + SHIELD_TICKS;
		for (long elapsed = endsAt; elapsed < endsAt + 1000L; elapsed++) {
			assertEquals(0, TrialCrystalLink.remainingShield(elapsed, endsAt, SHIELD_TICKS),
					"남은 시간이 음수로 흐르면 그 값으로 봉인 기한을 다시 미루는 순간 뜻이 뒤집힌다");
		}
	}

	@Test
	void 미루는_기한은_언제나_유한하고_남은_시간보다_길다() {
		for (int remaining = -5; remaining <= SHIELD_TICKS; remaining++) {
			int guard = TrialCrystalLink.guardTicks(remaining);
			assertTrue(guard > 0, "0 을 걸면 그 틱에 보호막이 없다: " + remaining);
			assertTrue(guard >= remaining,
					"남은 보호막보다 짧게 걸면 끝나기 전에 깨진다: " + remaining);
			assertTrue(guard < Integer.MAX_VALUE,
					"무한에 가까운 값을 걸면 우리가 죽었을 때 영영 안 깨지는 크리스탈이 남는다");
		}
		// 우리가 부르기를 멈춘 뒤 스스로 풀리는 데 걸리는 시간이 곧 여유다. 「부활」과 같은 값을
		// 빌려 쓰므로 한쪽만 고치면 여기가 운다.
		assertEquals(TrialCrystalRevive.GUARD_MARGIN_TICKS, TrialCrystalLink.guardTicks(0));
	}

	// ------------------------------------------------------------------ 봉인이 스스로 열리는가

	/**
	 * 죽은 사람 스위치가 이 파일에서 가장 중요한 시험이다.
	 *
	 * <p>봉인은 개체가 아니라 정적 칸에 있으므로 {@code Entity.commonTick} 이 깎아 주지 않는다.
	 * 실행기가 기한을 미루기를 멈추면 <b>반드시</b> 열려야 하고, 그러지 않으면 드래곤이 죽어
	 * 세션만 사라지는 길에서 영영 안 깨지는 크리스탈이 남는다.
	 */
	@Test
	void 기한이_지난_봉인은_열린다() {
		UUID sealed = UUID.randomUUID();
		long freshUntil = 1000L;
		assertTrue(TrialCrystalLink.sealFresh(sealed, freshUntil, 999L));
		assertFalse(TrialCrystalLink.sealFresh(sealed, freshUntil, 1000L),
				"기한과 같은 눈금이면 열리는 쪽으로 기울여야 한다");
		assertFalse(TrialCrystalLink.sealFresh(sealed, freshUntil, 1001L));
		assertFalse(TrialCrystalLink.sealFresh(null, freshUntil, 0L),
				"봉인이 없는데 기한만 남아 있으면 판의 모든 크리스탈이 잠긴다");
	}

	/**
	 * 봉인을 미루는 값이 곧 「우리가 죽고 나서 열릴 때까지」다.
	 *
	 * <p>바닐라 무적 칸이 공짜로 주던 성질을 손으로 되찾은 자리라, 산수가 어긋나면 조용히
	 * 사라진다.
	 */
	@Test
	void 부르기를_멈추면_여유만큼_뒤에_열린다() {
		long now = 5000L;
		long freshUntil = now + TrialCrystalLink.guardTicks(SHIELD_TICKS);
		UUID sealed = UUID.randomUUID();
		// 마지막으로 미룬 그 틱에 실행기가 죽었다고 치면, 보호막이 다 닳는 시각까지는 잠겨 있고
		assertTrue(TrialCrystalLink.sealFresh(sealed, freshUntil, now + SHIELD_TICKS));
		// 여유까지 지나면 아무도 부르지 않아도 스스로 열린다.
		assertFalse(TrialCrystalLink.sealFresh(sealed, freshUntil,
				now + SHIELD_TICKS + TrialCrystalRevive.GUARD_MARGIN_TICKS));
	}

	/** 판이 끝나면 봉인이 남아 있어서는 안 된다. 월드도 개체도 없이 지나는 길이다. */
	@Test
	void 비우면_봉인이_남지_않는다() {
		TrialCrystalLink.clearState();
		assertNull(TrialCrystalLink.sealedCrystal(),
				"봉인이 남으면 다음 판에 이유 없이 안 깨지는 크리스탈이 생긴다");
		assertFalse(TrialCrystalLink.sealHolds(0L));
		assertFalse(TrialCrystalLink.sealHolds(Long.MAX_VALUE));
	}

	/**
	 * 운영자 탈출구 둘이 26.3 에 그대로 있다.
	 *
	 * <p>{@code EndCrystalSealMixin} 은 {@code Entity.isInvulnerableToBase} 가 무적을 뚫어 주는
	 * 경우를 <b>글자 그대로</b> 흉내 낸다. 둘 중 하나라도 이름이 바뀌면 봉인이 바닐라 무적보다
	 * 촘촘해져 <b>운영자가 치울 길이 없어진다.</b> 믹스인은 refmap 이 없어 그런 어긋남을 빌드가
	 * 잡아 주지 않는다.
	 */
	@Test
	void 운영자_탈출구_둘이_그대로_있다() {
		assertDoesNotThrow(() -> DamageSource.class.getDeclaredMethod("isCreativePlayer"),
				"크리에이티브가 통과하는 길이 없어졌다");
		assertNotNull(DamageTypeTags.BYPASSES_INVULNERABILITY,
				"무적을 뚫는 태그가 없어졌다 — /kill 로도 못 치우게 된다");
		assertDoesNotThrow(() -> Entity.class.getDeclaredMethod("getUUID"),
				"봉인이 어느 개체인지 물을 길이 없어졌다");
	}

	// ------------------------------------------------------------------ 눈에 보이는가

	@Test
	void 껍질은_갓_걸렸을_때_가장_크고_끝까지_사라지지_않는다() {
		assertEquals(TrialCrystalLink.SHELL_MAX,
				TrialCrystalLink.shellRadius(SHIELD_TICKS, SHIELD_TICKS), 1.0E-9);
		assertEquals(TrialCrystalLink.SHELL_MIN,
				TrialCrystalLink.shellRadius(0, SHIELD_TICKS), 1.0E-9);
		// 마지막 순간이 가장 알고 싶은 순간이다. 0 으로 오므라들면 「이미 풀렸다」로 읽혀 한 번
		// 더 헛되이 쏘게 된다. 크리스탈의 몸은 2×2 라 반폭이 1 이다.
		assertTrue(TrialCrystalLink.SHELL_MIN > 1.0,
				"껍질이 몸 안으로 들어가면 보이지 않는다");
	}

	@Test
	void 껍질은_단조롭게_줄어든다() {
		double previous = Double.MAX_VALUE;
		for (int remaining = SHIELD_TICKS; remaining >= 0; remaining--) {
			double radius = TrialCrystalLink.shellRadius(remaining, SHIELD_TICKS);
			assertTrue(radius <= previous,
					"중간에 다시 커지면 「얼마나 남았는가」를 거짓말하는 것이다: " + remaining);
			previous = radius;
		}
	}

	@Test
	void 시간이_0_인_카드에도_껍질_계산이_터지지_않는다() {
		// 카드 값이 0 이면 실행기가 아예 돌지 않지만, 계산이 0 으로 나누면 그 사실을 숨긴 채
		// 다른 시련까지 멈추는 예외가 된다.
		assertEquals(TrialCrystalLink.SHELL_MIN, TrialCrystalLink.shellRadius(0, 0), 1.0E-9);
		assertEquals(TrialCrystalLink.ARC_MIN, TrialCrystalLink.shellArc(0, 0), 1.0E-9);
		assertEquals(0, TrialCrystalLink.remainingShield(0L, 0L, 0));
	}

	@Test
	void 적도_호는_온전한_고리에서_조각까지_단조롭게_닫힌다() {
		assertEquals(1.0, TrialCrystalLink.shellArc(SHIELD_TICKS, SHIELD_TICKS), 1.0E-9,
				"갓 걸렸을 때는 고리가 닫혀 있어야 「아직 온전하다」로 읽힌다");
		assertEquals(TrialCrystalLink.ARC_MIN, TrialCrystalLink.shellArc(0, SHIELD_TICKS), 1.0E-9);
		assertTrue(TrialCrystalLink.ARC_MIN > 0.0,
				"다 닫히면 「이미 풀렸다」로 읽혀 한 번 더 헛되이 쏘게 된다");

		double previous = Double.MAX_VALUE;
		for (int remaining = SHIELD_TICKS; remaining >= 0; remaining--) {
			double arc = TrialCrystalLink.shellArc(remaining, SHIELD_TICKS);
			assertTrue(arc <= previous,
					"중간에 다시 커지면 「얼마나 남았는가」를 거짓말하는 것이다: " + remaining);
			previous = arc;
		}
	}

	/**
	 * 30초를 버티는 초읽기가 실제로 읽히는가.
	 *
	 * <p>8초짜리를 그대로 30초로 늘렸더니 반경이 <b>초당 0.037 블록</b>이 됐다. 크리스탈은 반경
	 * 42 기둥 꼭대기라 그 1.1 블록이 40 블록 밖에서 1.6도다 — 사람 눈에는 멈춰 있다. 그래서
	 * 초읽기를 호로 옮겼고, 이 시험은 <b>옮긴 쪽이 실제로 더 큰 폭을 쓰는지</b>를 붙든다.
	 *
	 * <p>반경도 남겨 두었지만 이제는 거드는 신호다. 둘 중 하나라도 폭이 사라지면 이 카드는 다시
	 * 「왜 안 깨지지」가 된다.
	 */
	@Test
	void 초읽기는_반경이_아니라_호가_한다() {
		double arcSpan = TrialCrystalLink.shellArc(SHIELD_TICKS, SHIELD_TICKS)
				- TrialCrystalLink.shellArc(0, SHIELD_TICKS);
		assertTrue(arcSpan >= 0.8,
				"호가 쓰는 폭이 좁으면 30초짜리 초읽기를 멀리서 읽을 수 없다: " + arcSpan);

		// 40 블록 밖에서 반경 변화가 만드는 각(도). 사람 눈이 못 잡는 크기라는 것을 숫자로 남긴다.
		double radiusSpan = TrialCrystalLink.SHELL_MAX - TrialCrystalLink.SHELL_MIN;
		double degrees = Math.toDegrees(Math.atan2(radiusSpan, 40.0));
		assertTrue(degrees < 3.0,
				"반경이 이만큼이나 움직인다면 호로 옮긴 근거가 거짓이다: " + degrees);
	}

	@Test
	void 점은_호의_길이에_비례하고_결코_사라지지_않는다() {
		double radius = TrialCrystalLink.SHELL_MAX;
		double full = Math.PI * 2.0;
		// 온전한 적도가 기준값 그대로다.
		assertEquals(TrialCrystalLink.SHELL_POINTS,
				TrialCrystalLink.bandPoints(radius, radius, full));
		// 위아래 띠는 둘레가 cos(π/4) 배라 그만큼만 찍는다. 같은 수를 찍으면 위아래만 촘촘해져
		// 공이 아니라 통으로 보인다.
		int tilted = TrialCrystalLink.bandPoints(radius * Math.cos(Math.PI / 4.0), radius, full);
		assertTrue(tilted < TrialCrystalLink.SHELL_POINTS && tilted > TrialCrystalLink.SHELL_MIN_POINTS,
				"위아래 띠의 점 수가 적도와 같으면 밀도가 어긋난다: " + tilted);

		// 호가 아무리 짧아져도 조각은 보여야 한다 — 그 조각이 「아직 못 깬다」의 마지막 신호다.
		for (int remaining = SHIELD_TICKS; remaining >= 0; remaining--) {
			double span = full * TrialCrystalLink.shellArc(remaining, SHIELD_TICKS);
			double ring = TrialCrystalLink.shellRadius(remaining, SHIELD_TICKS);
			assertTrue(TrialCrystalLink.bandPoints(ring, ring, span) >= TrialCrystalLink.SHELL_MIN_POINTS,
					"남은 조각이 점 한둘이 되면 화면에서 사라진다: " + remaining);
		}
		// 값이 망가져 들어와도 0 으로 나누거나 점 0 개를 돌려주지 않는다. 껍질이 사라지는 것보다
		// 나쁜 것은 그 자리에서 예외가 나 그 틱의 다른 시련까지 멈추는 것이다.
		assertEquals(TrialCrystalLink.SHELL_MIN_POINTS, TrialCrystalLink.bandPoints(0.0, 0.0, full));
		assertEquals(TrialCrystalLink.SHELL_MIN_POINTS, TrialCrystalLink.bandPoints(1.0, 1.0, 0.0));
	}

	// ------------------------------------------------------------------ 시험이 쓰는 배치

	/** 바닐라와 같은 모양으로 기둥 자리를 만든다. 높이는 인자로 받는다. */
	private static List<Vec3> ring(double[] heights) {
		List<Vec3> seats = new ArrayList<>(SPIKE_COUNT);
		for (int index = 0; index < SPIKE_COUNT; index++) {
			double angle = (Math.PI * 2.0 * index) / SPIKE_COUNT;
			seats.add(new Vec3(Math.cos(angle) * SPIKE_RADIUS, heights[index],
					Math.sin(angle) * SPIKE_RADIUS));
		}
		return seats;
	}

	/** 높이가 모두 같은 배치. 「이웃이 잠긴다」만 볼 때 쓴다. */
	private static double[] flatHeights() {
		double[] heights = new double[SPIKE_COUNT];
		for (int index = 0; index < SPIKE_COUNT; index++) {
			heights[index] = LOWEST_TOP;
		}
		return heights;
	}

	private static List<Vec3> without(List<Vec3> seats, int skip) {
		List<Vec3> rest = new ArrayList<>(seats.size() - 1);
		for (int index = 0; index < seats.size(); index++) {
			if (index != skip) {
				rest.add(seats.get(index));
			}
		}
		return rest;
	}

	/** 세로까지 넣어 잰 가장 가까운 후보. 수평으로 잰 것과 같은 답이 나오는지 보려고 둔다. */
	private static int nearestBySolidDistance(Vec3 from, List<Vec3> candidates) {
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (int index = 0; index < candidates.size(); index++) {
			double distance = from.distanceToSqr(candidates.get(index));
			if (distance < bestDistance) {
				bestDistance = distance;
				best = index;
			}
		}
		return best;
	}

	/**
	 * 상태를 비우는 길이 개체 없이도 조용히 도는지 본다.
	 *
	 * <p>월드가 이미 사라진 뒤에도, 한 번도 걸린 적이 없어도 불린다
	 * ({@code TrialRisks.clearState} 가 실행기 전부를 조건 없이 지난다). 여기서 터지면 그 뒤의
	 * 실행기들이 <b>전부 상태를 안고 다음 판으로 넘어간다.</b>
	 */
	@Test
	void 비우는_길은_걸린_것이_없어도_조용하다() {
		assertDoesNotThrow(TrialCrystalLink::clearState);
		assertDoesNotThrow(TrialCrystalLink::clearState);
	}
}
