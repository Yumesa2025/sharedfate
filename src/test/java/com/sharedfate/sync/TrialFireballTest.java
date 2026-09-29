package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 기둥 화염구에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>파티클·소리·피해는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 카드가
 * 망가지는 길은 거의 전부 타이밍 쪽이다 — <b>도착보다 한 틱 먼저 터진다</b>, <b>도착하고도 한 틱
 * 더 날아간다</b>, <b>다음 발이 앞 발의 궤적을 착탄 없이 지운다</b>, <b>예고가 피할 시간보다
 * 짧다</b>. 궤적이 곧 예고인 카드라 이것들이 어긋나면 <b>예고가 거짓말을 한다.</b> 전멸하면 월드가
 * 지워지는 게임이므로 돌려 보고 발견할 수 없다.
 */
class TrialFireballTest {

	/** 「기둥 화염구」의 카드 값. 시험이 현실과 붙어 있으려면 여기 적힌 것과 같아야 한다. */
	private static final int INTERVAL = 200;
	private static final int TRACE = 100;
	private static final long GRANTED = 1000L;

	/** 카드 값으로 실제로 쓰이는 궤적 길이. */
	private static final int WINDOW = TrialFireball.traceWindow(INTERVAL, TRACE);

	// ------------------------------------------------------------------ 날아가는 비율

	@Test
	void 발사_순간에는_아직_한_칸도_날아오지_않았다() {
		assertEquals(0.0, TrialFireball.flightProgress(WINDOW, WINDOW), 1.0E-9,
				"출발하자마자 중간에 떠 있으면 어디서 왔는지 읽을 수 없다");
	}

	@Test
	void 비율은_영에서_일까지_오르고_그_범위를_넘지_않는다() {
		double previous = -1.0;
		for (int remaining = WINDOW; remaining >= 0; remaining--) {
			double progress = TrialFireball.flightProgress(remaining, WINDOW);
			assertTrue(progress >= 0.0 && progress <= 1.0,
					"범위를 넘으면 불덩이가 기둥 뒤나 조준점 너머에 그려진다: " + remaining + " → " + progress);
			assertTrue(progress > previous, "비율이 멈추거나 되돌아가면 불덩이가 공중에 선다: " + remaining);
			previous = progress;
		}
		assertEquals(1.0, previous, 1.0E-9);
	}

	@Test
	void 도착하기_전에는_비율이_일에_닿지_않는다() {
		for (int remaining = WINDOW; remaining >= 1; remaining--) {
			assertTrue(TrialFireball.flightProgress(remaining, WINDOW) < 1.0,
					"도착하지 않았는데 조준점에 얹히면 터지기 전에 이미 맞은 것처럼 보인다: " + remaining);
		}
	}

	@Test
	void 남은_틱이_창보다_많으면_아직_발사_전이다() {
		// 궤적을 그리지 않는 구간이다. 여기서 0 이 아닌 값이 나오면 주기 내내 선이 남는다.
		assertEquals(0.0, TrialFireball.flightProgress(WINDOW + 1, WINDOW), 1.0E-9);
		assertEquals(0.0, TrialFireball.flightProgress(INTERVAL - 1, WINDOW), 1.0E-9);
	}

	// ------------------------------------------------------------------ 도착과 폭발이 같은 틱

	@Test
	void 발사한_뒤_궤적_시간이_지난_바로_그_틱에_터진다() {
		long launch = launchTick();
		assertEquals(0.0, TrialFireball.flightProgress(
						TrialRisks.remainingTicks(launch, GRANTED, INTERVAL), WINDOW), 1.0E-9,
				"발사 틱은 비율 0 인 틱이다");

		assertFalse(TrialRisks.firesAt(launch + TRACE - 1, GRANTED, INTERVAL),
				"하나 일찍 터지면 궤적이 조준점에 닿기 전에 폭발한다");
		assertTrue(TrialRisks.firesAt(launch + TRACE, GRANTED, INTERVAL),
				"도착 틱과 발동 틱이 같아야 한다");
		assertFalse(TrialRisks.firesAt(launch + TRACE + 1, GRANTED, INTERVAL),
				"하나 늦게 터지면 불덩이가 도착해 멈춰 선 뒤에 폭발한다");
	}

	@Test
	void 터지는_틱에_비율이_정확히_일이다() {
		long impact = launchTick() + TRACE;
		assertEquals(1.0, TrialFireball.flightProgress(
						TrialRisks.remainingTicks(impact, GRANTED, INTERVAL), WINDOW), 1.0E-9,
				"터지는 자리와 그려진 자리가 다르면 궤적이 예고 노릇을 못 한다");
	}

	/** 이번 주기에 불덩이가 출발하는 틱. 남은 틱이 궤적 길이와 같아지는 틱이다. */
	private static long launchTick() {
		for (long after = 1L; after <= INTERVAL; after++) {
			if (TrialRisks.remainingTicks(GRANTED + after, GRANTED, INTERVAL) == WINDOW) {
				return GRANTED + after;
			}
		}
		return fail("주기 안에 발사 틱이 없다");
	}

	// ------------------------------------------------------------------ 궤적이 주기보다 길 때

	@Test
	void 궤적이_주기보다_길면_주기로_깎는다() {
		// 정해 둔 답이다. 깎지 않으면 다음 발이 앞 발을 덮어써서, 그려 놓은 궤적과 바닥 고리가
		// 착탄 없이 사라진다 — 예고가 거짓말이 된다.
		assertEquals(INTERVAL, TrialFireball.traceWindow(INTERVAL, INTERVAL * 2));
		assertEquals(INTERVAL, TrialFireball.traceWindow(INTERVAL, INTERVAL + 1));
		assertEquals(INTERVAL, TrialFireball.traceWindow(INTERVAL, INTERVAL));
	}

	@Test
	void 깎이지_않는_카드는_적힌_그대로_쓴다() {
		assertEquals(TRACE, WINDOW, "「기둥 화염구」는 주기의 절반이라 깎일 일이 없다");
		assertEquals(40, TrialFireball.traceWindow(INTERVAL, 40));
	}

	@Test
	void 깎인_궤적은_앞_발이_터진_다음_틱에_출발한다() {
		int window = TrialFireball.traceWindow(INTERVAL, INTERVAL * 2);
		int justAfterImpact = TrialRisks.remainingTicks(GRANTED + INTERVAL + 1L, GRANTED, INTERVAL);
		assertEquals(INTERVAL - 1, justAfterImpact, "터진 다음 틱이 새 주기의 첫 틱이다");
		assertTrue(justAfterImpact <= window,
				"첫 틱부터 궤적 구간이어야 하늘에 언제나 한 발이 있다");

		double first = TrialFireball.flightProgress(justAfterImpact, window);
		assertTrue(first > 0.0 && first < 0.01,
				"깎인 경우 첫 그림은 발사점에서 아주 조금 나아간 자리다. 실제 값: " + first);
		// 그리고 그 한 발은 반드시 착탄으로 끝난다.
		assertEquals(1.0, TrialFireball.flightProgress(0, window), 1.0E-9);
	}

	@Test
	void 주기나_궤적이_0_이하면_궤적_구간이_없다() {
		assertEquals(0, TrialFireball.traceWindow(0, TRACE), "0 으로 나누는 대신 그냥 돌지 않는다");
		assertEquals(0, TrialFireball.traceWindow(-5, TRACE));
		assertEquals(0, TrialFireball.traceWindow(INTERVAL, 0));
		assertEquals(0, TrialFireball.traceWindow(INTERVAL, -20));
		assertEquals(1.0, TrialFireball.flightProgress(50, 0), 1.0E-9,
				"궤적 구간이 없으면 늘 도착해 있는 것으로 본다 — 공중에 멈춘 불덩이를 만들지 않는다");
	}

	// ------------------------------------------------------------------ 카드 값

	@Test
	void 카드에_적힌_예고가_흩어질_시간보다_짧지_않다() {
		TrialCatalog.Risk.TracedProjectile card = card();
		int window = TrialFireball.traceWindow(card.interval(), card.traceTicks());
		assertTrue(window >= TrialWarning.TICKS_SCATTER,
				"예고가 회피 행동보다 짧으면 보여 준 것이 예고가 아니라 사후 통보다. 실제 값: " + window);
	}

	@Test
	void 카드_값이_시험이_가정한_것과_같다() {
		TrialCatalog.Risk.TracedProjectile card = card();
		assertEquals(INTERVAL, card.interval());
		assertEquals(TRACE, card.traceTicks());
		assertEquals(3.0, card.radius());
		assertEquals(1, card.count());
		assertTrue(card.damage() > 0.0F);
		assertTrue(card.traceTicks() <= card.interval(),
				"적힌 값이 깎이면 카드 설명의 「5초 동안」이 거짓말이 된다");
	}

	private static TrialCatalog.Risk.TracedProjectile card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:pillar_fireball");
		if (trial == null || trial.risks().size() != 1) {
			return fail("「기둥 화염구」 카드가 없다");
		}
		// 봉인 인터페이스가 늘어나도 이 시험이 깨지지 않게 switch 대신 instanceof 로 본다.
		if (trial.risks().getFirst() instanceof TrialCatalog.Risk.TracedProjectile shot) {
			return shot;
		}
		return fail("「기둥 화염구」가 TracedProjectile 이 아니다");
	}

	// ------------------------------------------------------------------ 대상 뽑기

	@Test
	void count_가_인원보다_크면_인원수만큼만_노린다() {
		assertEquals(3, TrialRisks.targetCount(5, 3), "없는 넷째 팀원에게 쏘려다 터지면 안 된다");
		assertEquals(3, TrialRisks.pickIndexes(5, 3, RandomSource.create(42L)).size());
		assertEquals(1, TrialRisks.pickIndexes(card().count(), 4, RandomSource.create(42L)).size(),
				"카드는 한 번에 한 발이다");
	}

	// ------------------------------------------------------------------ 궤적 좌표

	@Test
	void 궤적은_기둥에서_출발해_조준점에서_멈춘다() {
		Vec3 pillar = new Vec3(40.0, 90.0, 0.0);
		Vec3 aim = new Vec3(0.0, 64.0, 0.0);
		assertEquals(pillar, TrialFireball.headAt(pillar, aim, 0.0));
		assertEquals(aim, TrialFireball.headAt(pillar, aim, 1.0));
		assertEquals(new Vec3(20.0, 77.0, 0.0), TrialFireball.headAt(pillar, aim, 0.5));
	}

	@Test
	void 비율이_범위를_벗어나도_조준점을_지나치지_않는다() {
		Vec3 pillar = new Vec3(40.0, 90.0, 0.0);
		Vec3 aim = new Vec3(0.0, 64.0, 0.0);
		assertEquals(aim, TrialFireball.headAt(pillar, aim, 1.5),
				"지나쳐 날아가면 사람들이 지나간 자리에서 폭발을 본다");
		assertEquals(pillar, TrialFireball.headAt(pillar, aim, -0.3));
	}

	@Test
	void 꼬리는_길어져도_점_수가_무한히_늘지_않는다() {
		assertEquals(0, TrialFireball.trailSamples(0.0), "아직 출발점이면 꼬리가 없다");
		assertEquals(0, TrialFireball.trailSamples(-5.0));
		assertTrue(TrialFireball.trailSamples(10.0) > 0);
		assertTrue(TrialFireball.trailSamples(200.0) <= 24,
				"아레나 반대편 기둥이면 선이 100 칸이 넘는다. 상한이 없으면 파티클만으로 틱이 밀린다");
		assertTrue(TrialFireball.trailSamples(300.0) == TrialFireball.trailSamples(200.0),
				"상한에 닿은 뒤로는 더 늘지 않는다");
	}

	// ------------------------------------------------------------------ 착탄 판정

	@Test
	void 착탄_판정은_원이지_정육면체가_아니다() {
		double radius = card().radius();
		Vec3 at = new Vec3(0.0, 64.0, 0.0);
		// AABB.inflate(3) 은 모서리가 4.24 칸까지 걸린다. 바닥 고리는 반경 3 짜리 원이다.
		assertFalse(TrialRisks.insideMark(new Vec3(2.9, 64.0, 2.9), at, radius),
				"표식 밖에 서 있는데 맞으면 「비키면 산다」가 거짓이 된다");
		assertTrue(TrialRisks.insideMark(new Vec3(2.9, 64.0, 0.0), at, radius));
		assertTrue(TrialRisks.insideMark(new Vec3(3.0, 64.0, 0.0), at, radius), "경계는 안이다");
		assertFalse(TrialRisks.insideMark(new Vec3(3.01, 64.0, 0.0), at, radius));
	}
}
