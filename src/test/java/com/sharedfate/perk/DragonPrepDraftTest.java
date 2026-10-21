package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤전 대비」 풀의 추첨(0.44.0-dev) — 45·50 에서만 · 2장 · 40 이하 미출현 · 가중치.
 *
 * <p>월드 없이 {@link PerkDraft} 를 직접 굴린다. 풀은 시험이 만든다 — 정의 파일 값은
 * {@code DragonPrepDefinitionTest} 가 따로 본다.
 */
class DragonPrepDraftTest {
	private static final long SEED = 20261022L;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static Perk perk(String id, PerkRarity rarity) {
		return new Perk(id, id, "", rarity, List.of());
	}

	private static Perk weighted(String id, PerkRarity rarity, double weight) {
		return new Perk(id, id, "", rarity, null, 0, List.of(), List.of(), null, weight);
	}

	/** 실버·골드·프리즘 각 n 장 + 「드래곤전 대비」 열두 장(둘은 가중치 0.5). */
	private static List<Perk> pool(int perGrade) {
		List<Perk> pool = new ArrayList<>();
		for (PerkRarity rarity : PerkRarity.GRADES) {
			for (int i = 0; i < perGrade; i++) {
				pool.add(perk(rarity.id() + i, rarity));
			}
		}
		pool.addAll(trialPool());
		return pool;
	}

	private static List<Perk> trialPool() {
		List<Perk> trial = new ArrayList<>();
		trial.add(weighted("trial:oath", PerkRarity.TRIAL, 0.5));
		trial.add(weighted("trial:seal", PerkRarity.TRIAL, 0.5));
		for (int i = 0; i < 10; i++) {
			trial.add(perk("trial:" + i, PerkRarity.TRIAL));
		}
		return trial;
	}

	private static boolean isTrial(List<Perk> pool, String id) {
		return pool.stream().anyMatch(p -> p.id().equals(id) && p.rarity() == PerkRarity.TRIAL);
	}

	// ------------------------------------------------------------------ 구간 → 분류

	@Test
	void 사십오와_오십은_굴리지_않고_드래곤전_대비다() {
		for (int milestone : new int[] {45, 50}) {
			RandomSource random = RandomSource.create(SEED);
			assertEquals(PerkRarity.TRIAL, PerkDraft.rarityFor(milestone, 0, true, true, random));
			// 난수를 하나도 쓰지 않았다 — 같은 시드의 새 난수와 다음 값이 같다.
			assertEquals(RandomSource.create(SEED).nextInt(1000), random.nextInt(1000));
		}
	}

	@Test
	void 사십_이하는_드래곤전_대비가_되지_않는다() {
		RandomSource random = RandomSource.create(SEED);
		for (int round = 0; round < 2000; round++) {
			for (int milestone = 5; milestone <= 40; milestone += 5) {
				PerkRarity rarity = PerkDraft.rarityFor(milestone, round % 3, round % 2 == 0,
						round % 5 == 0, random);
				assertTrue(rarity.isGrade(), milestone + " 에 " + rarity);
			}
		}
	}

	@Test
	void 후보_수는_드래곤전_대비만_둘이다() {
		assertEquals(2, PerkDraft.TRIAL_OPTIONS);
		assertEquals(2, PerkDraft.optionCount(PerkRarity.TRIAL));
		for (PerkRarity grade : PerkRarity.GRADES) {
			assertEquals(3, PerkDraft.optionCount(grade));
		}
	}

	@Test
	void 폴백은_등급과_분류를_서로_끌어오지_않는다() {
		assertEquals(List.of(PerkRarity.TRIAL), PerkDraft.fallbackOrder(PerkRarity.TRIAL));
		for (PerkRarity grade : PerkRarity.GRADES) {
			assertFalse(PerkDraft.fallbackOrder(grade).contains(PerkRarity.TRIAL), grade.name());
		}
	}

	// ------------------------------------------------------------------ 45·50 은 이 풀에서만, 2장

	@Test
	void 사십오_추첨은_드래곤전_대비에서만_두_장이다() {
		List<Perk> pool = pool(5);
		RandomSource random = RandomSource.create(SEED);
		for (int round = 0; round < 500; round++) {
			for (int milestone : new int[] {45, 50}) {
				PerkRarity rarity = PerkDraft.rarityFor(milestone, 0, false, false, random);
				List<String> drawn = PerkDraft.drawFor(rarity, milestone, pool, List.of(), List.of(),
						Perk.Requirement.ALL, random, PerkDraft.optionCount(rarity), true,
						Set.of(PerkSetType.MINING), 100);
				assertEquals(2, drawn.size());
				assertEquals(2, new HashSet<>(drawn).size(), "같은 카드 두 번");
				for (String id : drawn) {
					assertTrue(isTrial(pool, id), id + " 가 드래곤전 대비가 아니다");
				}
			}
		}
	}

	@Test
	void 풀이_모자라도_등급으로_채우지_않는다() {
		List<Perk> pool = pool(5);
		List<String> owned = new ArrayList<>();
		for (Perk perk : trialPool()) {
			owned.add(perk.id());
		}
		owned.remove("trial:3");
		List<String> drawn = PerkDraft.drawFor(PerkRarity.TRIAL, 50, pool, owned, List.of(),
				Perk.Requirement.ALL, RandomSource.create(SEED), 2, false, Set.of(), 0);
		assertEquals(List.of("trial:3"), drawn, "남은 한 장만 — 실버로 채우지 않는다");
	}

	@Test
	void 사십오에서_가진_것은_오십에_나오지_않는다() {
		List<Perk> pool = pool(5);
		RandomSource random = RandomSource.create(SEED);
		for (int round = 0; round < 300; round++) {
			List<String> first = PerkDraft.drawFor(PerkRarity.TRIAL, 45, pool, List.of(),
					Perk.Requirement.ALL, random, 2);
			String kept = first.getFirst();
			List<String> second = PerkDraft.drawFor(PerkRarity.TRIAL, 50, pool, List.of(kept),
					Perk.Requirement.ALL, random, 2);
			assertFalse(second.contains(kept), "45 에서 고른 것이 50 에 또 나왔다");
		}
	}

	@Test
	void 다시_뽑기는_방금_본_두_장을_피한다() {
		List<Perk> pool = pool(5);
		RandomSource random = RandomSource.create(SEED);
		for (int round = 0; round < 300; round++) {
			List<String> shown = PerkDraft.drawFor(PerkRarity.TRIAL, 45, pool, List.of(),
					Perk.Requirement.ALL, random, 2);
			List<String> rerolled = PerkDraft.drawFor(PerkRarity.TRIAL, 45, pool, List.of(), shown,
					Perk.Requirement.ALL, random, 2);
			assertEquals(2, rerolled.size());
			for (String id : rerolled) {
				assertFalse(shown.contains(id), "방금 본 카드가 돌아왔다");
			}
		}
	}

	// ------------------------------------------------------------------ 40 이하에는 절대 안 나온다

	@Test
	void 사십_이하_구간은_등급이_바닥나도_드래곤전_대비를_꺼내지_않는다() {
		// 등급마다 한 장뿐이라 폴백이 끝까지 돈다.
		List<Perk> pool = pool(1);
		RandomSource random = RandomSource.create(SEED);
		for (int milestone = 5; milestone <= 40; milestone += 5) {
			for (PerkRarity grade : PerkRarity.GRADES) {
				List<String> drawn = PerkDraft.drawFor(grade, milestone, pool, List.of(), List.of(),
						Perk.Requirement.ALL, random, 3, false, Set.of(), 0);
				for (String id : drawn) {
					assertFalse(isTrial(pool, id), milestone + "렙에 " + id);
				}
			}
		}
	}

	@Test
	void 분류를_직접_시켜도_사십_이하에서는_비어_있다() {
		List<String> drawn = PerkDraft.drawFor(PerkRarity.TRIAL, 40, pool(3), List.of(),
				Perk.Requirement.ALL, RandomSource.create(SEED), 2);
		assertTrue(drawn.isEmpty(), "40 이하에서 드래곤전 대비가 나왔다: " + drawn);
		// 구간을 모르는 옛 길(구간 = 40)도 마찬가지다.
		assertTrue(PerkDraft.draw(PerkRarity.TRIAL, pool(3), List.of(), RandomSource.create(SEED), 2)
				.isEmpty());
	}

	// ------------------------------------------------------------------ 가중치

	@Test
	void 가중치가_모두_같으면_예전_균등_추첨과_난수까지_같다() {
		// 가중치를 하나도 안 적은 통(지금의 등급 셋)은 nextInt(크기) 한 번으로 고른다.
		List<Perk> bucket = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			bucket.add(perk("s" + i, PerkRarity.SILVER));
		}
		RandomSource mine = RandomSource.create(SEED);
		RandomSource legacy = RandomSource.create(SEED);
		for (int i = 0; i < 50; i++) {
			assertEquals(legacy.nextInt(bucket.size()), PerkDraft.pickIndex(bucket, mine));
		}
		// 모두 0.7 처럼 같은 값이어도 균등이다.
		List<Perk> same = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			same.add(weighted("w" + i, PerkRarity.SILVER, 0.7));
		}
		RandomSource a = RandomSource.create(SEED);
		RandomSource b = RandomSource.create(SEED);
		for (int i = 0; i < 50; i++) {
			assertEquals(PerkDraft.pickIndex(bucket, a), PerkDraft.pickIndex(same, b));
		}
	}

	@Test
	void 기본_가중치_풀은_가중치가_생기기_전과_같은_후보를_낸다() {
		// 가중치 칸 없이 만든 증강(기본 1.0)과 1.0 을 직접 적은 증강이 같은 시드에서 같은 후보를 낸다.
		List<Perk> plain = new ArrayList<>();
		List<Perk> explicit = new ArrayList<>();
		for (PerkRarity grade : PerkRarity.GRADES) {
			for (int i = 0; i < 8; i++) {
				plain.add(perk(grade.id() + i, grade));
				explicit.add(weighted(grade.id() + i, grade, 1.0));
			}
		}
		for (int milestone = 5; milestone <= 40; milestone += 5) {
			RandomSource a = RandomSource.create(SEED + milestone);
			RandomSource b = RandomSource.create(SEED + milestone);
			assertEquals(PerkDraft.draw(milestone, plain, List.of(), a),
					PerkDraft.draw(milestone, explicit, List.of(), b));
		}
	}

	@Test
	void 가중치_반인_두_장은_절반쯤만_나온다() {
		List<Perk> pool = pool(3);
		RandomSource random = RandomSource.create(SEED);
		Map<String, Integer> seen = new HashMap<>();
		int rounds = 40_000;
		for (int round = 0; round < rounds; round++) {
			for (String id : PerkDraft.drawFor(PerkRarity.TRIAL, 45, pool, List.of(),
					Perk.Requirement.ALL, random, 2)) {
				seen.merge(id, 1, Integer::sum);
			}
		}
		double half = (seen.get("trial:oath") + seen.get("trial:seal")) / 2.0;
		double full = 0.0;
		for (int i = 0; i < 10; i++) {
			full += seen.get("trial:" + i);
		}
		full /= 10.0;
		// 비복원 가중 추첨에서 2장 중에 들 확률은 0.5 가 약 0.093, 1.0 이 약 0.181 — 비는 약 0.51 이다.
		double ratio = half / full;
		assertTrue(ratio > 0.45 && ratio < 0.58, "비 " + ratio);
		assertEquals(0.0931, seen.get("trial:oath") / (double) rounds, 0.01);
		assertEquals(0.1814, full / rounds, 0.01);
	}

	@Test
	void 가중치는_통_안에서만_견준다() {
		// 한 장만 남으면 가중치와 무관하게 그것이 나온다.
		List<Perk> one = List.of(weighted("only", PerkRarity.TRIAL, 0.5));
		assertEquals(0, PerkDraft.pickIndex(new ArrayList<>(one), RandomSource.create(SEED)));
	}
}
