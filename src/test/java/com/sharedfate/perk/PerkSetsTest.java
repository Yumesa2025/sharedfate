package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 판정({@link PerkSets})을 살아 있는 게임 없이 시험한다.
 *
 * <p>여기서 쓰는 증강과 단계는 전부 이 파일 안에서 만든 것이다. {@link PerkRegistry} 나
 * {@link PerkSetRegistry} 에 손을 대지 않으므로 정의 파일이 바뀌어도 이 시험은 흔들리지 않는다.
 */
class PerkSetsTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 유형별 개수

	/**
	 * 유형을 <b>둘</b> 가진 증강은 양쪽에 다 세어진다.
	 *
	 * <p>기본 풀에 이런 증강이 둘 있다 — 「원정 준비물」(보급·도박)과 「피의 대가」(화력·회복).
	 * 한쪽으로만 세면 그 증강을 집은 사람이 세트 하나를 손해 본다.
	 */
	@Test
	void 유형이_둘인_증강은_양쪽에_다_세어진다() {
		Map<String, Perk> pool = new HashMap<>();
		put(pool, perk("kit", PerkSetType.SUPPLY, PerkSetType.GAMBLE));
		put(pool, perk("blood", PerkSetType.POWER, PerkSetType.RECOVERY));

		Map<PerkSetType, Integer> counts =
				PerkSets.countByType(List.of("kit", "blood"), pool::get);

		assertEquals(1, counts.get(PerkSetType.SUPPLY));
		assertEquals(1, counts.get(PerkSetType.GAMBLE));
		assertEquals(1, counts.get(PerkSetType.POWER));
		assertEquals(1, counts.get(PerkSetType.RECOVERY));
		assertEquals(0, counts.get(PerkSetType.MINING), "안 가진 유형은 0 이다");
	}

	/** 표에는 유형 열네 개가 모두 들어 있다. 화면이 「채굴 0/3」을 그리려면 없는 유형도 알아야 한다. */
	@Test
	void 개수표에는_유형이_전부_들어_있다() {
		Map<PerkSetType, Integer> counts = PerkSets.countByType(List.of(), id -> null);

		assertEquals(PerkSetType.values().length, counts.size());
		for (PerkSetType type : PerkSetType.values()) {
			assertEquals(0, counts.get(type), type.displayName());
		}
	}

	/** 풀에서 사라진 id 는 조용히 건너뛴다. 저장에만 남은 id 는 언제든 생긴다. */
	@Test
	void 정의가_없는_id는_세지_않는다() {
		Map<String, Perk> pool = new HashMap<>();
		put(pool, perk("mine1", PerkSetType.MINING));

		Map<PerkSetType, Integer> counts = PerkSets.countByType(
				Arrays.asList("mine1", "sharedfate:사라진것", null), pool::get);

		assertEquals(1, counts.get(PerkSetType.MINING));
	}

	/** 무유형 증강은 어느 세트도 채우지 않는다. 기본 풀 82개 중 16개가 그렇다. */
	@Test
	void 무유형_증강은_어느_세트도_채우지_않는다() {
		Map<String, Perk> pool = new HashMap<>();
		put(pool, perk("plain"));

		Map<PerkSetType, Integer> counts = PerkSets.countByType(List.of("plain"), pool::get);

		for (PerkSetType type : PerkSetType.values()) {
			assertEquals(0, counts.get(type), type.displayName());
		}
	}

	// ------------------------------------------------------------------ 누적

	/**
	 * 단계는 <b>누적</b>이다.
	 *
	 * <p>채굴 4개면 2·3·4 단계가 전부 켜진다. 가장 높은 것 하나만 켜지는 것이 아니다.
	 * 이것이 어긋나면 4단계를 만든 사람이 2·3단계 보상을 잃는다.
	 */
	@Test
	void 넷을_모으면_2_3_4단계가_전부_켜진다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(2, 3, 4);

		List<PerkSets.Tier> active = PerkSets.activeTiers(counts(PerkSetType.MINING, 4), defined);

		assertEquals(List.of(2, 3, 4), active.stream().map(PerkSets.Tier::count).toList());
	}

	/** 셋이면 2·3 만 켜지고 4단계는 아직이다. */
	@Test
	void 셋을_모으면_4단계는_안_켜진다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(2, 3, 4);

		List<PerkSets.Tier> active = PerkSets.activeTiers(counts(PerkSetType.MINING, 3), defined);

		assertEquals(List.of(2, 3), active.stream().map(PerkSets.Tier::count).toList());
	}

	/** 하나가 모자라면 아무것도 안 켜진다. */
	@Test
	void 하나가_모자라면_아무것도_안_켜진다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(2, 3, 4);

		assertTrue(PerkSets.activeTiers(counts(PerkSetType.MINING, 1), defined).isEmpty());
		assertTrue(PerkSets.activeEffects(counts(PerkSetType.MINING, 1), defined).isEmpty());
	}

	/** 정의를 거꾸로 적어도 판정은 개수 오름차순으로 나온다. */
	@Test
	void 단계를_거꾸로_적어도_순서가_맞는다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(4, 2, 3);

		List<PerkSets.Tier> active = PerkSets.activeTiers(counts(PerkSetType.MINING, 4), defined);

		assertEquals(List.of(2, 3, 4), active.stream().map(PerkSets.Tier::count).toList());
	}

	// ------------------------------------------------------------------ 효과 펼치기

	/** 켜진 모든 단계의 효과가 하나의 목록으로 나온다. */
	@Test
	void 켜진_단계의_효과가_한_목록으로_나온다() {
		PerkEffect first = new PerkEffect() {
		};
		PerkEffect second = new PerkEffect() {
		};
		Map<PerkSetType, List<PerkSets.Tier>> defined = Map.of(PerkSetType.MINING, List.of(
				new PerkSets.Tier(PerkSetType.MINING, 2, "둘", "", List.of(first)),
				new PerkSets.Tier(PerkSetType.MINING, 3, "셋", "", List.of(second))));

		List<PerkEffect> effects = PerkSets.activeEffects(counts(PerkSetType.MINING, 3), defined);

		assertEquals(2, effects.size());
		assertSame(first, effects.get(0));
		assertSame(second, effects.get(1));
	}

	/** 효과가 비어 있는 단계는 「아직 안 만들어진 자리」다. 화면이 구분할 수 있어야 한다. */
	@Test
	void 효과가_빈_단계는_예정으로_표가_난다() {
		PerkSets.Tier empty = new PerkSets.Tier(PerkSetType.MINING, 2, "둘", "", List.of());
		PerkSets.Tier filled =
				new PerkSets.Tier(PerkSetType.MINING, 3, "셋", "", List.of(new PerkEffect() {
				}));

		assertTrue(empty.isPlaceholder());
		assertFalse(filled.isPlaceholder());
	}

	// ------------------------------------------------------------------ 화면용

	/** 다음 임계값은 아직 안 켜진 단계 중 가장 낮은 개수다. */
	@Test
	void 다음_임계값은_아직_안_켜진_가장_낮은_단계다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(2, 3, 4);

		PerkSets.Status status = statusOf(
				PerkSets.statuses(counts(PerkSetType.MINING, 3), defined), PerkSetType.MINING);

		assertEquals(3, status.owned());
		assertEquals(4, status.nextCount());
		assertEquals(2, status.activeTiers().size());
		assertTrue(status.isActive());
	}

	/** 전부 켰으면 다음 임계값은 0 이다. */
	@Test
	void 전부_켰으면_다음_임계값이_없다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = mining(2, 3, 4);

		PerkSets.Status status = statusOf(
				PerkSets.statuses(counts(PerkSetType.MINING, 9), defined), PerkSetType.MINING);

		assertEquals(0, status.nextCount());
	}

	/**
	 * 단계가 하나도 정의되지 않은 유형도 진행도는 보인다.
	 *
	 * <p>「무기」가 그렇다. 보상이 아직 없어 {@code tiers} 가 비어 있지만, 화면은
	 * 「무기 1/2」로 몇 개를 모았는지 보여 줄 수 있어야 한다. 그 2 는
	 * {@link PerkSetType#threshold()} 에서 온다 — 여기 숫자를 직접 적으면 임계값을 조정할 때
	 * 화면만 옛 값을 붙들고 있게 된다.
	 */
	@Test
	void 단계가_없는_유형도_임계값까지의_진행도를_보여_준다() {
		PerkSets.Status status = statusOf(
				PerkSets.statuses(counts(PerkSetType.WEAPON, 1), Map.of()), PerkSetType.WEAPON);

		assertEquals(1, status.owned());
		assertEquals(PerkSetType.WEAPON.threshold(), status.nextCount());
		assertFalse(status.isActive());
	}

	/**
	 * 단계가 하나라도 있으면 임계값은 분모로 끼어들지 않는다.
	 *
	 * <p>기동은 단계가 3 하나뿐인데 임계값은 2 다. 둘을 섞으면 하나 가진 사람에게 「기동 1/2」가
	 * 떠 <b>2 에서 무언가 켜진다</b>고 읽히는데, 실제로 둘째를 모아도 아무 일이 없다. 화면의 분모는
	 * 언제나 실제로 있는 단계여야 한다.
	 */
	@Test
	void 단계가_있으면_임계값은_분모가_되지_않는다() {
		Map<PerkSetType, List<PerkSets.Tier>> defined = tiersOf(PerkSetType.MOBILITY, 3);
		assertTrue(PerkSetType.MOBILITY.threshold() < 3,
				"임계값이 첫 단계보다 낮아야 이 시험이 무언가를 잡는다");

		for (int owned = 1; owned <= 2; owned++) {
			PerkSets.Status status = statusOf(
					PerkSets.statuses(counts(PerkSetType.MOBILITY, owned), defined),
					PerkSetType.MOBILITY);

			assertEquals(3, status.nextCount(), owned + "개를 가졌을 때");
			assertFalse(status.isActive());
		}
	}

	/** 상태 목록에는 유형 열네 개가 모두 들어 있다. */
	@Test
	void 상태_목록에는_유형이_전부_들어_있다() {
		List<PerkSets.Status> statuses = PerkSets.statuses(List.of(), id -> null, Map.of());

		assertEquals(PerkSetType.values().length, statuses.size());
	}

	// ------------------------------------------------------------------ 도우미

	private static void put(Map<String, Perk> pool, Perk perk) {
		pool.put(perk.id(), perk);
	}

	private static Perk perk(String id, PerkSetType... types) {
		return new Perk(id, id, "", PerkRarity.SILVER, null, 0, List.of(types),
				List.of(new PerkEffect() {
				}));
	}

	private static Map<PerkSetType, Integer> counts(PerkSetType type, int owned) {
		Map<PerkSetType, Integer> counts = new EnumMap<>(PerkSetType.class);
		for (PerkSetType each : PerkSetType.values()) {
			counts.put(each, 0);
		}
		counts.put(type, owned);
		return counts;
	}

	/** 유형 하나에 단계를 붙인 정의. 효과는 아무거나 하나씩 넣어 「예정」이 아니게 한다. */
	private static Map<PerkSetType, List<PerkSets.Tier>> tiersOf(PerkSetType type,
			int... tierCounts) {
		List<PerkSets.Tier> tiers = new ArrayList<>();
		for (int count : tierCounts) {
			tiers.add(new PerkSets.Tier(type, count, type.displayName() + " " + count, "",
					List.of(new PerkEffect() {
					})));
		}
		return Map.of(type, List.copyOf(tiers));
	}

	private static Map<PerkSetType, List<PerkSets.Tier>> mining(int... tierCounts) {
		List<PerkSets.Tier> tiers = new ArrayList<>();
		for (int count : tierCounts) {
			tiers.add(new PerkSets.Tier(PerkSetType.MINING, count, "채굴 " + count, "",
					List.of(new PerkEffect() {
					})));
		}
		return Map.of(PerkSetType.MINING, List.copyOf(tiers));
	}

	private static PerkSets.Status statusOf(List<PerkSets.Status> statuses, PerkSetType type) {
		return statuses.stream()
				.filter(status -> status.type() == type)
				.findFirst()
				.orElseThrow(() -> new AssertionError(type + " 상태가 없다"));
	}
}
