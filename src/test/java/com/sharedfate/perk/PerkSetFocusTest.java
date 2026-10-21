package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 세트 모으기 보정: 판정 함수와 추첨 규칙. */
class PerkSetFocusTest {
	private static final long SEED = 20261022L;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static Perk typed(String id, PerkRarity rarity, PerkSetType... types) {
		return new Perk(id, id, "", rarity, null, 0, List.of(types), List.of());
	}

	private static Perk typedReq(String id, PerkRarity rarity, Perk.Requirement req,
			PerkSetType type) {
		return new Perk(id, id, "", rarity, null, 0, List.of(type), List.of(), req);
	}

	/** 채굴은 2·3·4 단계, 화력은 2 단계뿐. */
	private static Map<PerkSetType, List<PerkSets.Tier>> defined() {
		Map<PerkSetType, List<PerkSets.Tier>> map = new EnumMap<>(PerkSetType.class);
		map.put(PerkSetType.MINING, List.of(tier(PerkSetType.MINING, 2),
				tier(PerkSetType.MINING, 3), tier(PerkSetType.MINING, 4)));
		map.put(PerkSetType.POWER, List.of(tier(PerkSetType.POWER, 2)));
		return map;
	}

	private static PerkSets.Tier tier(PerkSetType type, int count) {
		return new PerkSets.Tier(type, count, "t", "", List.of());
	}

	private static Map<PerkSetType, Integer> counts(Object... pairs) {
		Map<PerkSetType, Integer> map = new EnumMap<>(PerkSetType.class);
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((PerkSetType) pairs[i], (Integer) pairs[i + 1]);
		}
		return map;
	}

	// ------------------------------------------------------------------ 판정

	@Test
	void 한_장만_가져도_모으는_중이다() {
		assertEquals(Set.of(PerkSetType.MINING),
				PerkSets.collectingTypes(counts(PerkSetType.MINING, 1), defined()));
	}

	@Test
	void 하나도_없으면_모으는_세트가_없다() {
		assertTrue(PerkSets.collectingTypes(counts(), defined()).isEmpty());
		assertTrue(PerkSets.collectingTypes((Map<PerkSetType, Integer>) null, defined()).isEmpty());
		assertTrue(PerkSets.collectingTypes(counts(PerkSetType.MINING, 1), null).isEmpty());
	}

	@Test
	void 마지막_단계를_연_세트는_뺀다() {
		// 채굴 3개는 마지막(4) 전이라 모으는 중, 4개부터는 제외. 화력 2개는 마지막을 열었다.
		assertEquals(Set.of(PerkSetType.MINING), PerkSets.collectingTypes(
				counts(PerkSetType.MINING, 3, PerkSetType.POWER, 2), defined()));
		assertTrue(PerkSets.collectingTypes(
				counts(PerkSetType.MINING, 4, PerkSetType.POWER, 5), defined()).isEmpty());
	}

	@Test
	void 여러_세트를_함께_모을_수_있다() {
		assertEquals(Set.of(PerkSetType.MINING, PerkSetType.POWER), PerkSets.collectingTypes(
				counts(PerkSetType.MINING, 2, PerkSetType.POWER, 1), defined()));
	}

	@Test
	void 단계가_정의되지_않은_유형은_뺀다() {
		assertTrue(PerkSets.collectingTypes(counts(PerkSetType.SUPPLY, 1), defined()).isEmpty());
	}

	@Test
	void 보유_목록에서_바로_판정한다() {
		List<Perk> all = List.of(typed("a", PerkRarity.SILVER, PerkSetType.MINING),
				typed("b", PerkRarity.SILVER, PerkSetType.MINING, PerkSetType.POWER));
		Map<String, Perk> byId = new java.util.HashMap<>();
		all.forEach(p -> byId.put(p.id(), p));
		// 채굴 2(마지막 4 전), 화력 1(마지막 2 전).
		assertEquals(Set.of(PerkSetType.MINING, PerkSetType.POWER),
				PerkSets.collectingTypes(List.of("a", "b", "없음"), byId::get, defined()));
	}

	// ------------------------------------------------------------------ 추첨

	/** 실버 채굴 5 · 실버 무유형 25 · 골드 채굴 3 · 골드 무유형 10. */
	private static List<Perk> pool() {
		List<Perk> pool = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			pool.add(typed("sm" + i, PerkRarity.SILVER, PerkSetType.MINING));
		}
		for (int i = 0; i < 25; i++) {
			pool.add(typed("sn" + i, PerkRarity.SILVER));
		}
		for (int i = 0; i < 3; i++) {
			pool.add(typed("gm" + i, PerkRarity.GOLD, PerkSetType.MINING));
		}
		for (int i = 0; i < 10; i++) {
			pool.add(typed("gn" + i, PerkRarity.GOLD));
		}
		return pool;
	}

	private static List<String> draw(PerkRarity rarity, List<String> avoid, boolean blocked,
			RandomSource random, Set<PerkSetType> focus, int pct) {
		return PerkDraft.drawFor(rarity, 40, pool(), List.of(), avoid, Perk.Requirement.ALL,
				random, 3, blocked, focus, pct);
	}

	private static boolean isMining(String id) {
		return id.startsWith("sm") || id.startsWith("gm");
	}

	private static long miningCount(List<String> ids) {
		return ids.stream().filter(PerkSetFocusTest::isMining).count();
	}

	@Test
	void 확률_100이면_보정_칸이_항상_들어간다() {
		RandomSource random = RandomSource.create(SEED);
		for (int i = 0; i < 200; i++) {
			List<String> drawn = draw(PerkRarity.SILVER, List.of(), false, random,
					Set.of(PerkSetType.MINING), 100);
			assertEquals(3, drawn.size());
			assertEquals(3, new HashSet<>(drawn).size(), "중복 없음");
			assertTrue(miningCount(drawn) >= 1, "보정 칸: " + drawn);
			drawn.forEach(id -> assertTrue(id.startsWith("s"), "등급 유지"));
		}
	}

	@Test
	void 보정_칸이_늘_같은_자리에_오지_않는다() {
		RandomSource random = RandomSource.create(SEED);
		Set<Integer> slots = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			List<String> drawn = draw(PerkRarity.SILVER, List.of(), false, random,
					Set.of(PerkSetType.MINING), 100);
			for (int k = 0; k < drawn.size(); k++) {
				if (isMining(drawn.get(k))) {
					slots.add(k);
				}
			}
		}
		assertEquals(Set.of(0, 1, 2), slots);
	}

	// ------------------------------------------------------------------ 기존과 동일

	@Test
	void 모으는_세트가_없거나_확률이_0이면_기존_결과와_같다() {
		for (long seed = 0; seed < 50; seed++) {
			RandomSource ref = RandomSource.create(seed);
			List<String> base = PerkDraft.drawFor(PerkRarity.SILVER, 40, pool(), List.of(),
					List.of(), Perk.Requirement.ALL, ref, 3, false);
			RandomSource none = RandomSource.create(seed);
			assertEquals(base, draw(PerkRarity.SILVER, List.of(), false, none, Set.of(), 50));
			RandomSource off = RandomSource.create(seed);
			assertEquals(base, draw(PerkRarity.SILVER, List.of(), false, off,
					Set.of(PerkSetType.MINING), 0));
			// 난수를 하나도 더 쓰지 않았다 - 다음 값까지 같다.
			int next = ref.nextInt();
			assertEquals(next, none.nextInt());
			assertEquals(next, off.nextInt());
		}
	}

	@Test
	void 그_등급에_해당_증강이_없으면_보정_없이_기존과_같다() {
		Set<PerkSetType> focus = Set.of(PerkSetType.POWER);
		for (long seed = 0; seed < 30; seed++) {
			RandomSource ref = RandomSource.create(seed);
			List<String> base = PerkDraft.drawFor(PerkRarity.SILVER, 40, pool(), List.of(),
					List.of(), Perk.Requirement.ALL, ref, 3, false);
			RandomSource random = RandomSource.create(seed);
			assertEquals(base, draw(PerkRarity.SILVER, List.of(), false, random, focus, 100));
			assertEquals(ref.nextInt(), random.nextInt());
		}
	}

	// ------------------------------------------------------------------ 통계

	@Test
	void 보정_칸_발생률이_설정_확률_근처다() {
		RandomSource random = RandomSource.create(SEED);
		int trials = 20000;
		int boosted = 0;
		int baseline = 0;
		for (int i = 0; i < trials; i++) {
			if (miningCount(draw(PerkRarity.SILVER, List.of(), false, random,
					Set.of(PerkSetType.MINING), 50)) >= 1) {
				boosted++;
			}
			if (miningCount(draw(PerkRarity.SILVER, List.of(), false, random, Set.of(), 50)) >= 1) {
				baseline++;
			}
		}
		// 보정이 없어도 채굴이 나올 수 있으므로 기대는 0.5 + 0.5 * (보정 없는 확률).
		double b = (double) baseline / trials;
		double rate = (double) boosted / trials;
		assertEquals(0.5 + 0.5 * b, rate, 0.03);
		assertTrue(rate > b + 0.2, "보정이 실제로 올려야 한다: " + rate + " vs " + b);
	}

	// ------------------------------------------------------------------ 다른 규칙과 함께

	@Test
	void 회피_대상은_보정_칸에서도_뒤로_미룬다() {
		// 채굴 실버 5개 중 3개를 회피로 건다. 아닌 두 개가 있으니 보정 칸은 그 둘에서만 나온다.
		List<String> avoid = List.of("sm0", "sm1", "sm2");
		RandomSource random = RandomSource.create(SEED);
		for (int i = 0; i < 300; i++) {
			List<String> drawn = draw(PerkRarity.SILVER, avoid, false, random,
					Set.of(PerkSetType.MINING), 100);
			assertTrue(drawn.contains("sm3") || drawn.contains("sm4"), "" + drawn);
			assertFalse(drawn.stream().anyMatch(avoid::contains), "" + drawn);
		}
	}

	@Test
	void 회피_아닌_채굴이_없으면_회피_대상에서_보정한다() {
		List<String> avoid = List.of("sm0", "sm1", "sm2", "sm3", "sm4");
		RandomSource random = RandomSource.create(SEED);
		for (int i = 0; i < 100; i++) {
			List<String> drawn = draw(PerkRarity.SILVER, avoid, false, random,
					Set.of(PerkSetType.MINING), 100);
			assertEquals(1, miningCount(drawn), "" + drawn);
			assertEquals(3, drawn.size());
		}
	}

	@Test
	void 실버_차단이면_실버는_보정으로도_끌어오지_않는다() {
		RandomSource random = RandomSource.create(SEED);
		for (int i = 0; i < 200; i++) {
			List<String> drawn = draw(PerkRarity.GOLD, List.of(), true, random,
					Set.of(PerkSetType.MINING), 100);
			assertEquals(3, drawn.size());
			assertTrue(drawn.stream().allMatch(id -> id.startsWith("g")), "" + drawn);
			assertTrue(miningCount(drawn) >= 1);
		}
		for (int i = 0; i < 100; i++) {
			List<String> drawn = draw(PerkRarity.SILVER, List.of(), true, random,
					Set.of(PerkSetType.MINING), 100);
			assertTrue(drawn.stream().noneMatch(id -> id.startsWith("s")), "" + drawn);
		}
	}

	@Test
	void 전제조건을_못_채운_증강은_보정으로도_나오지_않는다() {
		List<Perk> pool = new ArrayList<>(pool());
		pool.add(typedReq("sw", PerkRarity.SILVER, Perk.Requirement.POSITION_SWAP,
				PerkSetType.SWAP));
		RandomSource random = RandomSource.create(SEED);
		for (int i = 0; i < 300; i++) {
			List<String> drawn = PerkDraft.drawFor(PerkRarity.SILVER, 40, pool, List.of(),
					List.of(), Set.of(), random, 3, false, Set.of(PerkSetType.SWAP), 100);
			assertFalse(drawn.contains("sw"));
		}
		// 갖췄으면 보정 칸으로 반드시 나온다.
		for (int i = 0; i < 50; i++) {
			List<String> drawn = PerkDraft.drawFor(PerkRarity.SILVER, 40, pool, List.of(),
					List.of(), Set.of(Perk.Requirement.POSITION_SWAP), random, 3, false,
					Set.of(PerkSetType.SWAP), 100);
			assertTrue(drawn.contains("sw"));
		}
	}

	@Test
	void 이미_가진_증강은_보정으로도_나오지_않는다() {
		RandomSource random = RandomSource.create(SEED);
		List<String> owned = List.of("sm0", "sm1", "sm2", "sm3");
		for (int i = 0; i < 200; i++) {
			List<String> drawn = PerkDraft.drawFor(PerkRarity.SILVER, 40, pool(), owned,
					List.of(), Perk.Requirement.ALL, random, 3, false,
					Set.of(PerkSetType.MINING), 100);
			assertFalse(drawn.stream().anyMatch(owned::contains));
			assertTrue(drawn.contains("sm4"));
		}
	}
}
