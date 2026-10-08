package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 번들 기본 증강 풀({@code sharedfate-perks-default.json})의 세트 유형 배정을 못박는다.
 *
 * <p>유형별 개수는 세트가 실제로 완성될 수 있는지를 정하는 값이라, 증강을 더하거나 유형을
 * 옮길 때 여기가 먼저 깨져야 한다.
 */
class DefaultPerkSetTypesTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 유형별_증강_개수가_배정표와_같다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		Map<PerkSetType, Integer> expected = new EnumMap<>(PerkSetType.class);
		expected.put(PerkSetType.MINING, 10);
		expected.put(PerkSetType.POWER, 8);
		expected.put(PerkSetType.MOBILITY, 7);
		expected.put(PerkSetType.DEFENSE, 8);
		expected.put(PerkSetType.SUPPLY, 7);
		expected.put(PerkSetType.WEAPON, 7);
		// 0.29.1-dev 에서 「환골탈태」가 빠져 5 → 4 가 됐다. 세트 3단계(셋)는 그대로 닿는다.
		expected.put(PerkSetType.GAMBLE, 4);
		expected.put(PerkSetType.SURVIVAL, 5);
		expected.put(PerkSetType.HUNT, 6);
		expected.put(PerkSetType.SWAP, 6);
		expected.put(PerkSetType.RECOVERY, 5);
		expected.put(PerkSetType.BLESSING, 7);
		expected.put(PerkSetType.BOND, 6);
		// 개척은 기존 무유형 둘(길잡이 나침반·요새 탐지기)을 데려오고 새 증강 둘
		// (유적 감별사·흥정의 달인)을 더해 만들어졌다. 그래서 무유형이 13 에서 11 로 줄었다.
		expected.put(PerkSetType.PIONEER, 4);

		for (PerkSetType type : PerkSetType.values()) {
			long count = PerkRegistry.all().stream().filter(p -> p.hasSetType(type)).count();
			assertEquals(expected.get(type).intValue(), (int) count, type.displayName());
		}

		long none = PerkRegistry.all().stream().filter(p -> p.setTypes().isEmpty()).count();
		assertEquals(11, none, "무유형");
	}

	/**
	 * 유형이 <b>두 개</b>인 증강은 정확히 다섯뿐이다.
	 *
	 * <p>「가호」는 셋이 겸업이다 — 전속 광부(채굴)·몽둥이찜질(무기)·열외(교환). 한 사람에게
	 * 몰아주는 축과 그 효과가 무엇이냐는 축이 서로 직교하기 때문이다.
	 *
	 * <p>유형을 하나로 줄이면 세트 판정이 조용히 달라지므로, 개수와 함께 어느 증강인지도
	 * 못박는다.
	 */
	@Test
	void 유형이_둘인_증강은_정확히_다섯이다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		List<String> multi = PerkRegistry.all().stream()
				.filter(p -> p.setTypes().size() > 1)
				.map(Perk::id)
				.toList();

		assertEquals(List.of("sharedfate:expedition_kit", "sharedfate:price_of_blood",
				"sharedfate:shared_pickaxe", "sharedfate:cudgel",
				"sharedfate:swap_exempt"), multi);
		assertEquals(List.of(PerkSetType.WEAPON, PerkSetType.BLESSING),
				PerkRegistry.byId("sharedfate:cudgel").orElseThrow().setTypes(),
				"몽둥이찜질 = 무기·가호");
		assertEquals(List.of(PerkSetType.SWAP, PerkSetType.BLESSING),
				PerkRegistry.byId("sharedfate:swap_exempt").orElseThrow().setTypes(),
				"열외 = 교환·가호");
		assertEquals(List.of(PerkSetType.MINING, PerkSetType.BLESSING),
				PerkRegistry.byId("sharedfate:shared_pickaxe").orElseThrow().setTypes(),
				"전속 광부 = 채굴·가호");
		assertEquals(List.of(PerkSetType.SUPPLY, PerkSetType.GAMBLE),
				PerkRegistry.byId("sharedfate:expedition_kit").orElseThrow().setTypes(),
				"원정 준비물 = 보급·도박");
		assertEquals(List.of(PerkSetType.POWER, PerkSetType.RECOVERY),
				PerkRegistry.byId("sharedfate:price_of_blood").orElseThrow().setTypes(),
				"피의 대가 = 화력·회복");
	}

	/**
	 * 유형별 개수가 임계값 이상이다.
	 *
	 * <p>임계값보다 증강이 적은 유형이 있으면 그 세트는 <b>애초에 완성할 수 없다.</b>
	 * 유형을 옮기다가 그런 상태를 만들면 여기서 걸린다.
	 */
	@Test
	void 어느_유형도_임계값에_모자라지_않는다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		for (PerkSetType type : PerkSetType.values()) {
			long count = PerkRegistry.all().stream().filter(p -> p.hasSetType(type)).count();
			assertTrue(count >= type.threshold(),
					type.displayName() + " 은 " + count + "개뿐이라 임계값 "
							+ type.threshold() + " 을 채울 수 없다");
		}
	}

	/** 번들 기본 풀을 임시 폴더에 풀어 레지스트리에 올린다. */
	private static void loadDefaultPool(Path dir) throws IOException {
		Path target = dir.resolve(PerkRegistry.FILE_NAME);
		if (!Files.exists(target)) {
			try (InputStream bundled = DefaultPerkSetTypesTest.class
					.getResourceAsStream("/sharedfate-perks-default.json")) {
				Files.copy(bundled, target);
			}
		}
		PerkRegistry.load(dir);
	}
}
