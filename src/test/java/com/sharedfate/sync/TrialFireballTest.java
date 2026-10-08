package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

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
	/** 궤적 40틱 = 2초. 5초짜리를 2.5배 빠르게 한 값이다(실제로 맞아 보고 정했다). */
	private static final int TRACE = 40;
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
		assertEquals(TRACE, WINDOW, "「기둥 화염구」는 주기의 5분의 1 이라 깎일 일이 없다");
		assertEquals(120, TrialFireball.traceWindow(INTERVAL, 120));
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

	/**
	 * 예고가 <b>이 카드가 요구하는 행동</b>에 필요한 시간보다 길다.
	 *
	 * <h2>기준을 흩어지기에서 옆걸음으로 바꿨다 — 낮춘 것이 아니다</h2>
	 *
	 * <p>예전에는 {@link TrialWarning#TICKS_SCATTER}(80틱)로 봤다. 그런데 그 값의 정의는 <b>「네
	 * 명이 서로를 보고 각자 다른 곳으로 흩어져야 할 때」</b>다 — 서로 합의할 시간이 필요해서 긴
	 * 것이다. 이 카드는 그 상황이 아니다. 한 주기에 <b>한 발</b>이고, 조준점은 발사 순간에
	 * 얼어붙어 <b>움직이지 않는다.</b> 노려진 사람이 해야 하는 일은 「제자리에서 옆으로 비키기」
	 * 하나뿐이고, 그 기준은 {@link TrialWarning#TICKS_SIDESTEP}(30틱)이다.
	 *
	 * <p>그래서 궤적을 40틱(2초)으로 줄인 것은 <b>기준을 낮춘 것이 아니라 맞는 기준으로 바꾼
	 * 것</b>이다. 40 은 30 보다 길다. 이 시험이 지키는 것은 예전과 같다 — <b>보여 준 것이 예고인가,
	 * 사후 통보인가.</b>
	 *
	 * <p>필요한 기준을 <b>카드 값에서 뽑는다.</b> 한 번에 여러 발을 쏘게 되면 조준점이 사람마다
	 * 달라져 「비킨 자리가 남의 조준점」이 될 수 있고, 그때는 다시 흩어지기 기준이다 — 숫자를
	 * 박아 두면 그 변화를 이 시험이 놓친다.
	 */
	@Test
	void 카드에_적힌_예고가_요구하는_행동의_최소_예고보다_길다() {
		TrialCatalog.Risk.TracedProjectile card = card();
		int window = TrialFireball.traceWindow(card.interval(), card.traceTicks());
		boolean alone = card.count() <= 1;
		int needed = alone ? TrialWarning.TICKS_SIDESTEP : TrialWarning.TICKS_SCATTER;
		assertTrue(window >= needed,
				(alone ? "한 발이라 옆걸음 기준이다" : "여러 발이면 흩어지기 기준이다")
						+ " — 예고가 그보다 짧으면 보여 준 것이 예고가 아니라 사후 통보다."
						+ " 필요 " + needed + ", 실제 " + window);
		assertTrue(window > TrialWarning.TICKS_SIDESTEP,
				"지금 카드는 40틱이라 옆걸음 최소 예고 30틱보다 길다. 실제 값: " + window);
	}

	@Test
	void 카드_값이_시험이_가정한_것과_같다() {
		TrialCatalog.Risk.TracedProjectile card = card();
		assertEquals(INTERVAL, card.interval());
		assertEquals(TRACE, card.traceTicks(), "궤적 5초를 2.5배 빠르게 한 값이다");
		assertEquals(4.35, card.radius(), "반경 3 에서 45% 넓혔다");
		// 6 → 14 → 23. 앞의 둘은 맨몸 날값으로 팀 체력 20 과 견준 값이었고, 사람이
		// 「다이아셋 + 보호 인챈트까지 하고 맞는 것까지 고려해야 한다」고 정해 다시 잡혔다.
		// 이 카드는 explosion(null, null) 을 써서 하드 곱 1.5배가 먼저 걸리므로,
		// 「3대에 죽는다」(무장 기준 한 대 6.67)를 만드는 값이 23 이다.
		assertEquals(23.0F, card.damage(), "무장 기준으로 14 는 4.1 이라 「3대」에 한참 못 미쳤다");
		assertTrue(GearedDamage.wipesInThree(
						GearedDamage.afterGear(card.damage(), GearedDamage.Source.EXPLOSION)),
				"사람이 정한 것은 「큰자리는 3대맞으면 죽는거로」다 — 두 대로는 안 죽고"
						+ " 세 대에는 죽어야 한다");
		assertEquals(1, card.count());
		assertTrue(card.traceTicks() <= card.interval(),
				"적힌 값이 깎이면 카드 설명의 「2초 동안」이 거짓말이 된다");
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

		// 상한은 「약속한 길이 ÷ 허용 간격」이다. 숫자를 박아 두면 둘 중 하나만 고쳐질 때
		// 이 시험이 그것을 놓친다.
		int cap = (int) Math.ceil(TrialFireball.TRAIL_KEPT_LENGTH / TrialFireball.TRAIL_MAX_GAP);
		assertTrue(TrialFireball.trailSamples(500.0) <= cap,
				"상한이 없으면 먼 기둥의 한 발이 파티클만으로 틱을 민다");
		assertEquals(TrialFireball.trailSamples(500.0), TrialFireball.trailSamples(300.0),
				"상한에 닿은 뒤로는 더 늘지 않는다");
	}

	@Test
	void 꼬리가_길어지면_점도_따라_늘어난다() {
		int previous = 0;
		for (double travelled = 0.5; travelled <= 60.0; travelled += 0.5) {
			int points = TrialFireball.trailSamples(travelled);
			assertTrue(points >= previous,
					"날아온 거리가 느는데 점이 줄면 선이 뒤로 끊긴다: " + travelled);
			previous = points;
		}
		assertTrue(TrialFireball.trailSamples(50.0) > TrialFireball.trailSamples(10.0));
	}

	@Test
	void 궤적_점_사이가_선으로_읽힐_만큼만_벌어진다() {
		// 상한에 걸린 뒤로는 점이 안 늘어 간격만 벌어진다. 어디까지 벌어지는지를 세어 두지 않으면
		// 상한이 조용히 선을 점선으로 만든다.
		for (double travelled = 0.1; travelled <= TrialFireball.TRAIL_KEPT_LENGTH;
				travelled += 0.1) {
			assertTrue(TrialFireball.trailGap(travelled) <= TrialFireball.TRAIL_MAX_GAP + 1.0E-9,
					"길이 " + travelled + " 에서 점이 " + TrialFireball.trailGap(travelled)
							+ " 블록씩 벌어진다 — 선이 아니라 점선이다");
		}
	}

	@Test
	void 바닥에_선_사람과_기둥_꼭대기_사이에서도_선으로_읽힌다() {
		// 26.3 의 EndSpikeFeature 는 기둥 열 개를 반경 42 원 위에 세우고 높이를 76 + 3n 으로 준다.
		// 사람은 y 63 쯤이라, 섬 가운데에 선 사람에게 가장 가까운 기둥까지가 44~58 블록이다.
		Vec3 pillar = new Vec3(42.0, 93.0, 0.0);
		Vec3 aim = new Vec3(0.0, 63.0, 0.0);
		double length = pillar.distanceTo(aim);
		assertTrue(length > 32.0,
				"이 거리가 32 를 안 넘으면 이 카드에 사거리 문제가 없다는 뜻이다. 실제 값: " + length);
		assertTrue(length <= TrialFireball.TRAIL_KEPT_LENGTH,
				"약속한 길이 밖이면 간격을 보장하지 못한다. 실제 값: " + length);
		assertTrue(TrialFireball.trailGap(length) <= TrialFireball.TRAIL_MAX_GAP + 1.0E-9,
				"실제 값: " + TrialFireball.trailGap(length));
	}

	@Test
	void 궤적은_긴_거리로_나간다() throws IOException {
		// 이 카드가 예고하는 것은 「어느 기둥에서 출발했는가」다. 짧은 형태로 되돌리면 출발점이
		// 32 블록 밖이라 선의 앞부분이 통째로 안 그려지고, 그래도 빌드와 로그는 조용하다.
		String bytes;
		try (InputStream in = TrialFireball.class
				.getResourceAsStream("/com/sharedfate/sync/TrialFireball.class")) {
			if (in == null) {
				throw new IOException("TrialFireball 의 클래스 파일을 찾지 못했다");
			}
			bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 기둥에서 출발하는 장면이 아무에게도 안 보인다");
	}

	// ------------------------------------------------------------------ 착탄 판정

	@Test
	void 착탄_판정은_원이지_정육면체가_아니다() {
		// 반경을 카드에서 뽑아 쓴다. 숫자를 박아 두면 반경을 45% 넓힌 이번 같은 변경에서 이 시험이
		// 조용히 뜻을 잃는다 — 옛 반경으로는 「밖」이던 자리가 새 반경에서는 「안」이다.
		double radius = card().radius();
		Vec3 at = new Vec3(0.0, 64.0, 0.0);
		// AABB.inflate(r) 은 정육면체라 모서리가 r×1.41 까지 걸린다. 여기(r×0.75, r×0.75)는
		// 중심에서 r×1.06 이라 상자 안이고 고리 밖이다.
		assertFalse(TrialRisks.insideMark(
						new Vec3(radius * 0.75, 64.0, radius * 0.75), at, radius),
				"표식 밖에 서 있는데 맞으면 「비키면 산다」가 거짓이 된다");
		assertTrue(TrialRisks.insideMark(new Vec3(radius - 0.1, 64.0, 0.0), at, radius));
		assertTrue(TrialRisks.insideMark(new Vec3(radius, 64.0, 0.0), at, radius), "경계는 안이다");
		assertFalse(TrialRisks.insideMark(new Vec3(radius + 0.01, 64.0, 0.0), at, radius));
	}
}
