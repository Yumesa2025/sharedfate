package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위험 실행기에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>파티클·피해·띄우기는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이
 * 기능이 망가지는 길은 거의 전부 계산 쪽이다 — <b>받자마자 터진다</b>, <b>카드 둘이 같은 틱에
 * 겹쳐 터진다</b>, <b>복원 직후 위상이 음수가 된다</b>, <b>없는 다섯째 팀원을 뽑으려 한다</b>.
 * 전멸하면 월드가 지워지는 게임이라 이것들은 돌려 보고 발견할 수 없다.
 */
class TrialRisksTest {
	/** 「자리 폭격」의 주기. 실제 카드 값과 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int INTERVAL = 240;
	private static final long GRANTED = 1000L;

	// ------------------------------------------------------------------ 위상

	@Test
	void 카드를_받은_직후에는_한_주기가_온전히_지나야_터진다() {
		for (long now = GRANTED; now < GRANTED + INTERVAL; now++) {
			assertFalse(TrialRisks.firesAt(now, GRANTED, INTERVAL),
					"받은 뒤 한 주기 안에 터지면 카드 설명을 읽는 중에 맞는다: " + (now - GRANTED));
		}
		assertTrue(TrialRisks.firesAt(GRANTED + INTERVAL, GRANTED, INTERVAL),
				"한 주기가 지나면 첫 발동이다");
	}

	@Test
	void 받은_틱이_다르면_같은_주기의_카드_둘이_다른_틱에_터진다() {
		long other = GRANTED + 50L;
		int together = 0;
		for (long now = GRANTED; now < GRANTED + INTERVAL * 20L; now++) {
			if (TrialRisks.firesAt(now, GRANTED, INTERVAL)
					&& TrialRisks.firesAt(now, other, INTERVAL)) {
				together++;
			}
		}
		assertEquals(0, together,
				"월드 시간으로 세면 주기가 같은 카드 둘은 영원히 함께 터진다. 그러면 두 장이 한 장이다");
	}

	@Test
	void 복원_직후_now_가_받은_틱보다_작아도_음수_위상이_나오지_않는다() {
		for (long now = GRANTED - 500L; now <= GRANTED; now++) {
			assertEquals(0L, TrialRisks.elapsedSinceGrant(now, GRANTED));
			assertTrue(TrialRisks.remainingTicks(now, GRANTED, INTERVAL) >= 0,
					"남은 틱이 음수면 경고 층이 뒤집히고 예고 없이 터진다: " + now);
			assertTrue(TrialRisks.strikeIndex(now, GRANTED, INTERVAL) >= 0L);
			assertFalse(TrialRisks.firesAt(now, GRANTED, INTERVAL),
					"세션은 저장 파일에서 오고 게임 시각은 월드에서 온다. 어긋났다고 터지면 안 된다");
		}
	}

	@Test
	void 남은_틱은_주기_안에서_한_칸씩만_줄어든다() {
		int previous = TrialRisks.remainingTicks(GRANTED + 1L, GRANTED, INTERVAL);
		assertEquals(INTERVAL - 1, previous, "주기가 막 시작됐다");
		for (long now = GRANTED + 2L; now <= GRANTED + INTERVAL; now++) {
			int remaining = TrialRisks.remainingTicks(now, GRANTED, INTERVAL);
			assertEquals(previous - 1, remaining, "예고가 건너뛰면 그 층의 소리가 안 울린다: " + now);
			previous = remaining;
		}
		assertEquals(0, previous, "발동하는 틱에 0 이어야 한다");
	}

	@Test
	void 주기_번호는_발동하는_틱까지_유지된다() {
		// 예고 중에 뽑아 둔 대상을 발동 순간에 잃으면 표식과 실제 폭발이 어긋난다.
		long first = TrialRisks.strikeIndex(GRANTED + 1L, GRANTED, INTERVAL);
		assertEquals(first, TrialRisks.strikeIndex(GRANTED + INTERVAL, GRANTED, INTERVAL),
				"터지는 그 틱에 번호가 넘어가면 뽑아 둔 대상이 버려진다");
		assertNotEquals(first, TrialRisks.strikeIndex(GRANTED + INTERVAL + 1L, GRANTED, INTERVAL),
				"터진 다음 틱부터는 새 주기다 — 여기서 대상을 다시 뽑는다");
	}

	@Test
	void 주기가_0_이하면_아무_일도_없다() {
		assertFalse(TrialRisks.firesAt(GRANTED + 10_000L, GRANTED, 0),
				"0 으로 나누는 대신 그냥 돌지 않는다");
		assertFalse(TrialRisks.firesAt(GRANTED + 10_000L, GRANTED, -5));
		assertNull(TrialWarning.stageFor(TrialRisks.remainingTicks(GRANTED, GRANTED, 0)),
				"경고 층도 뜨지 않아야 한다 — 터지지도 않는데 예고만 나오면 신호가 거짓말이 된다");
	}

	// ------------------------------------------------------------------ 경고 층

	@Test
	void 층이_바뀌는_틱에만_소리를_낸다() {
		int changes = 0;
		for (int remaining = INTERVAL - 1; remaining >= 0; remaining--) {
			if (TrialRisks.stageJustChanged(remaining)) {
				changes++;
			}
		}
		assertEquals(3, changes, "한 주기에 세 층. 더 울리면 신호가 흔해지고 덜 울리면 층을 놓친다");
	}

	@Test
	void 세_층의_문턱에서만_바뀐다() {
		assertTrue(TrialRisks.stageJustChanged(100), "첫 층 — 뭔가 온다");
		assertTrue(TrialRisks.stageJustChanged(50), "둘째 층 — 여기로 온다");
		assertTrue(TrialRisks.stageJustChanged(14), "셋째 층 — 지금 나가라");
		assertFalse(TrialRisks.stageJustChanged(101), "아직 아무 층도 아니다");
		assertFalse(TrialRisks.stageJustChanged(99));
		assertFalse(TrialRisks.stageJustChanged(0), "발동 틱에 한 번 더 울리면 층이 넷이 된다");
	}

	// ------------------------------------------------------------------ 대상 뽑기

	@Test
	void count_가_팀_인원보다_크면_인원수만큼만_뽑는다() {
		assertEquals(4, TrialRisks.targetCount(10, 4), "없는 다섯째 팀원을 뽑으려다 터지면 안 된다");
		assertEquals(4, TrialRisks.pickIndexes(10, 4, RandomSource.create(7L)).size());
	}

	@Test
	void count_가_0_이하면_아무도_뽑지_않는다() {
		assertEquals(0, TrialRisks.targetCount(0, 4));
		assertEquals(0, TrialRisks.targetCount(-3, 4));
		assertTrue(TrialRisks.pickIndexes(0, 4, RandomSource.create(7L)).isEmpty());
		assertTrue(TrialRisks.pickIndexes(-3, 4, RandomSource.create(7L)).isEmpty());
	}

	@Test
	void 아무도_없으면_뽑을_것도_없다() {
		assertEquals(0, TrialRisks.targetCount(2, 0), "팀원이 전부 접속을 끊었을 수 있다");
		assertTrue(TrialRisks.pickIndexes(2, 0, RandomSource.create(7L)).isEmpty());
	}

	@Test
	void 같은_사람을_두_번_뽑지_않는다() {
		RandomSource random = RandomSource.create(1234L);
		for (int round = 0; round < 200; round++) {
			List<Integer> picked = TrialRisks.pickIndexes(3, 4, random);
			assertEquals(3, picked.size());
			assertEquals(3, new HashSet<>(picked).size(),
					"같은 사람이 두 번 들어오면 그 주기가 세 발에서 두 발로 줄어든다");
			for (int index : picked) {
				assertTrue(index >= 0 && index < 4, "없는 자리를 가리키면 그 자리에서 터진다");
			}
		}
	}

	@Test
	void 오래_돌리면_모두_한_번씩은_노려진다() {
		RandomSource random = RandomSource.create(99L);
		Set<Integer> seen = new HashSet<>();
		for (int round = 0; round < 200; round++) {
			seen.addAll(TrialRisks.pickIndexes(1, 4, random));
		}
		assertEquals(4, seen.size(), "한 사람만 계속 노리면 그 사람만 게임을 하게 된다");
	}

	// ------------------------------------------------------------------ 아레나 좌표

	@Test
	void 고른_자리는_아레나_밖으로_나가지_않는다() {
		double radius = 40.0;
		RandomSource random = RandomSource.create(5150L);
		for (int round = 0; round < 500; round++) {
			Vec3 spot = TrialRisks.arenaOffset(random.nextDouble(), random.nextDouble(), radius);
			assertTrue(spot.length() <= radius + 1.0E-9,
					"엔드 중앙 섬 바깥은 허공이다. 거기서 터지면 예고도 피해도 뜻이 없다");
			assertEquals(0.0, spot.y, "높이는 지면에서 따로 찾는다");
		}
	}

	@Test
	void 굴림이_범위를_벗어나도_아레나_안이다() {
		assertTrue(TrialRisks.arenaOffset(-5.0, 9.0, 40.0).length() <= 40.0 + 1.0E-9);
		assertEquals(0.0, TrialRisks.arenaOffset(0.5, -1.0, 40.0).length(), 1.0E-9,
				"거리 굴림이 0 이하면 중앙이다");
		assertEquals(40.0, TrialRisks.arenaOffset(0.0, 1.0, 40.0).length(), 1.0E-9,
				"거리 굴림이 1 이면 가장자리다");
	}

	@Test
	void 거리는_제곱근으로_펴서_중앙에_몰리지_않게_한다() {
		double radius = 40.0;
		// 굴림이 절반이면 넓이도 절반이어야 한다 — 반경의 절반이 아니라 √½ 배다.
		assertEquals(radius * Math.sqrt(0.5), TrialRisks.arenaOffset(0.0, 0.5, radius).length(),
				1.0E-9, "그냥 곱하면 지점이 중앙에 몰려 가장자리가 안전지대가 된다");
	}

	// ------------------------------------------------------------------ 띄우기

	@Test
	void 높이가_클수록_세게_띄우고_오래_봐_준다() {
		assertTrue(TrialRisks.launchVelocity(9.0) > TrialRisks.launchVelocity(4.0));
		assertTrue(TrialRisks.fallGraceTicks(9.0) > TrialRisks.fallGraceTicks(4.0));
	}

	@Test
	void 네_블록을_띄우면_이_초쯤_봐_준다() {
		// 올라갔다 내려오는 시간에 여유를 더한 값이다. 짧으면 착지 직전에 면제가 끊긴다.
		int grace = TrialRisks.fallGraceTicks(4.0);
		assertTrue(grace >= 40 && grace <= 60, "실제 값: " + grace);
	}

	@Test
	void 띄우지_않는_카드는_면제도_없다() {
		assertEquals(0.0, TrialRisks.launchVelocity(0.0));
		assertEquals(0, TrialRisks.fallGraceTicks(0.0),
				"「낙뢰」는 띄우지 않는다. 여기서 면제가 붙으면 스스로 뛰어내린 낙사까지 사라진다");
		assertEquals(0, TrialRisks.fallGraceTicks(-3.0));
	}

	// ------------------------------------------------------------------ 값으로 돈다

	@Test
	void 카드_두_장이_같은_타입을_서로_다른_값으로_쓴다() {
		TrialCatalog.Risk.DelayedStrike trail = onlyStrike("sharedfate:ground_strike");
		TrialCatalog.Risk.DelayedStrike spots = onlyStrike("sharedfate:lightning_storm");

		assertEquals(TrialCatalog.Risk.Aim.TRAIL, trail.aim());
		assertEquals(TrialCatalog.Risk.Aim.RANDOM_SPOT, spots.aim());
		assertNotEquals(trail.interval(), spots.interval(),
				"두 장째 카드를 만들 수 없었던 것이 이 실행기를 뜯은 이유다. 값이 같아지면 뜯은 뜻이 없다");
		assertTrue(trail.launch() > 0.0, "「자리 폭격」은 띄운다");
		assertEquals(0.0, spots.launch(), "「낙뢰」는 띄우지 않는다");
	}

	@Test
	void 카드에_적힌_주기는_예고_세_층보다_길다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				switch (risk) {
					case TrialCatalog.Risk.DelayedStrike strike -> assertTrue(
							strike.interval() > TrialWarning.TICKS_SCATTER,
							trial.name() + " — 주기가 예고보다 짧으면 경고가 통째로 잘린다");
					case TrialCatalog.Risk.TracedProjectile shot -> assertTrue(
							shot.traceTicks() >= TrialWarning.TICKS_SCATTER,
							trial.name() + " — 궤적이 흩어질 시간보다 짧으면 피할 수 없다");
					// 아래 셋은 터지는 순간이 없다. 상태를 걸거나 판을 바꾼다.
					case TrialCatalog.Risk.CrystalGuard ignored -> {
					}
					case TrialCatalog.Risk.DragonFocus focus -> assertTrue(
							focus.retargetTicks() >= 0,
							trial.name() + " — 재지정 간격이 음수면 뜻이 없다");
					case TrialCatalog.Risk.CrystalRevive revive -> assertTrue(
							revive.showTicks() >= 0,
							trial.name() + " — 연출 길이가 음수면 뜻이 없다");
				}
			}
		}
	}

	// ------------------------------------------------------------------ 표식과 판정

	@Test
	void 고리_밖_모서리는_맞지_않는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		// AABB.inflate(2) 는 정육면체라 여기까지 걸린다. 표식은 반경 2 짜리 원이므로 밖이다.
		assertFalse(TrialRisks.insideMark(new Vec3(1.9, 64.0, 1.9), center, 2.0),
				"표식 밖에 서 있는데 맞으면 「같은 표식은 같은 결과」가 깨진다");
		assertTrue(TrialRisks.insideMark(new Vec3(1.9, 64.0, 0.0), center, 2.0));
		assertTrue(TrialRisks.insideMark(center, center, 2.0));
	}

	@Test
	void 고리_위에_떠_있어도_맞는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		// 띄우는 카드와 겹치면 곧바로 드러난다. 바닥 표식은 세로를 묻지 않는다.
		assertTrue(TrialRisks.insideMark(new Vec3(0.5, 72.0, 0.5), center, 2.0));
	}

	@Test
	void 반경_경계에_선_사람은_맞는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		assertTrue(TrialRisks.insideMark(new Vec3(2.0, 64.0, 0.0), center, 2.0));
		assertFalse(TrialRisks.insideMark(new Vec3(2.01, 64.0, 0.0), center, 2.0));
	}

	private static TrialCatalog.Risk.DelayedStrike onlyStrike(String id) {
		TrialCatalog.Trial trial = TrialCatalog.byId(id);
		assertTrue(trial != null && trial.risks().size() == 1, id + " 카드가 없다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.DelayedStrike strike -> strike;
			// 이 시험이 보는 것은 예고 타격뿐이다. 카드의 위험 타입이 바뀌면 조용히 지나가지 않고
			// 여기서 멈춰야 한다 — 시험이 무엇을 보는지 모르게 되는 것이 더 나쁘다.
			case TrialCatalog.Risk risk -> throw new AssertionError(
					id + " 카드의 위험이 예고 타격이 아니다: " + risk);
		};
	}
}
