package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 유형이 문자열에서 enum 으로, JSON 에서 {@link Perk} 로 제대로 넘어오는지 본다.
 *
 * <p>여기서 지키는 것은 <b>데이터가 들어오는 길</b>뿐이다.
 */
class PerkSetTypeTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 유형은_열네_개다() {
		assertEquals(14, PerkSetType.values().length);
	}

	@Test
	void 문자열_id_로_왕복한다() {
		for (PerkSetType type : PerkSetType.values()) {
			assertEquals(type, PerkSetType.fromId(type.id()),
					type + " 의 id 왕복이 깨졌다");
			assertEquals(type.name().toLowerCase(java.util.Locale.ROOT), type.id());
		}

		// JSON 에 적힐 문자열이 실제로 이 열네 개다. 이름을 바꾸면 정의 파일도 함께 고쳐야 한다.
		assertEquals(
				Set.of("weapon", "power", "hunt", "mining", "supply", "defense",
						"survival", "recovery", "swap", "gamble", "mobility", "blessing", "bond",
						"pioneer"),
				EnumSet.allOf(PerkSetType.class).stream().map(PerkSetType::id)
						.collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void 대소문자와_공백은_받아_주고_모르는_값은_null_이다() {
		assertEquals(PerkSetType.MINING, PerkSetType.fromId("MINING"));
		assertEquals(PerkSetType.MINING, PerkSetType.fromId("  mining  "));
		assertNull(PerkSetType.fromId("채굴"));
		assertNull(PerkSetType.fromId("없는유형"));
		assertNull(PerkSetType.fromId(null));
	}

	@Test
	void 화면_이름이_한국어로_붙어_있다() {
		assertEquals("무기", PerkSetType.WEAPON.displayName());
		assertEquals("화력", PerkSetType.POWER.displayName());
		assertEquals("사냥", PerkSetType.HUNT.displayName());
		assertEquals("채굴", PerkSetType.MINING.displayName());
		assertEquals("보급", PerkSetType.SUPPLY.displayName());
		assertEquals("방어", PerkSetType.DEFENSE.displayName());
		assertEquals("생존", PerkSetType.SURVIVAL.displayName());
		assertEquals("회복", PerkSetType.RECOVERY.displayName());
		assertEquals("교환", PerkSetType.SWAP.displayName());
		assertEquals("도박", PerkSetType.GAMBLE.displayName());
		assertEquals("기동", PerkSetType.MOBILITY.displayName());
		assertEquals("가호", PerkSetType.BLESSING.displayName());
		assertEquals("결속", PerkSetType.BOND.displayName());
		assertEquals("개척", PerkSetType.PIONEER.displayName());
	}

	/**
	 * 임계값은 채굴·화력만 3이고 나머지는 2다.
	 *
	 * <p>시뮬레이션으로 다시 정해질 값이라 자주 바뀐다. 바뀌면 여기 숫자도 함께 고치면 되고,
	 * 이 시험이 지키는 것은 <b>임계값이 {@link PerkSetType} 한 곳에만 있다</b>는 사실이다.
	 */
	@Test
	void 임계값은_채굴과_화력만_3이다() {
		assertEquals(3, PerkSetType.MINING.threshold());
		assertEquals(3, PerkSetType.POWER.threshold());
		for (PerkSetType type : PerkSetType.values()) {
			if (type == PerkSetType.MINING || type == PerkSetType.POWER) {
				continue;
			}
			assertEquals(2, type.threshold(), type + " 의 임계값");
		}
	}

	@Test
	void set_types_를_읽어_증강에_붙인다(@TempDir Path dir) throws IOException {
		write(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:one", "rarity": "silver",
				      "set_types": ["mining"],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
				    { "id": "sharedfate:two", "rarity": "gold",
				      "set_types": ["power", "recovery"],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] }
				  ]
				}
				""");

		PerkRegistry.load(dir);

		Perk one = PerkRegistry.byId("sharedfate:one").orElseThrow();
		assertEquals(List.of(PerkSetType.MINING), one.setTypes());
		assertTrue(one.hasSetType(PerkSetType.MINING));

		Perk two = PerkRegistry.byId("sharedfate:two").orElseThrow();
		assertEquals(List.of(PerkSetType.POWER, PerkSetType.RECOVERY), two.setTypes(),
				"적힌 순서를 지킨다");
	}

	@Test
	void set_types_가_없으면_무유형이다(@TempDir Path dir) throws IOException {
		// 무유형은 정상이다. 기본 풀에도 열한 개나 있다.
		write(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:plain", "rarity": "silver",
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
				    { "id": "sharedfate:empty_list", "rarity": "silver",
				      "set_types": [],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] }
				  ]
				}
				""");

		PerkRegistry.load(dir);

		assertTrue(PerkRegistry.byId("sharedfate:plain").orElseThrow().setTypes().isEmpty(),
				"키가 없으면 무유형");
		assertTrue(PerkRegistry.byId("sharedfate:empty_list").orElseThrow().setTypes().isEmpty(),
				"빈 배열도 무유형 — 키를 안 쓴 것과 같게 다뤄야 한다");
	}

	/**
	 * 유형에 오타가 나도 <b>증강은 살아남는다.</b>
	 *
	 * <p>{@link PerkRegistry} 는 효과가 잘못되면 증강을 통째로 버린다. 유형은 그러면 안 된다.
	 */
	@Test
	void 모르는_유형은_그_항목만_건너뛰고_증강은_남는다(@TempDir Path dir) throws IOException {
		write(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:typo", "rarity": "silver",
				      "set_types": ["minning", "mining"],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
				    { "id": "sharedfate:all_wrong", "rarity": "silver",
				      "set_types": ["채굴", "없는유형"],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
				    { "id": "sharedfate:not_an_array", "rarity": "silver",
				      "set_types": "mining",
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
				    { "id": "sharedfate:mixed_junk", "rarity": "silver",
				      "set_types": ["mining", 7, null],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] }
				  ]
				}
				""");

		PerkRegistry.load(dir);

		assertEquals(4, PerkRegistry.all().size(), "유형이 잘못돼도 증강을 버리지 않는다");
		assertEquals(List.of(PerkSetType.MINING),
				PerkRegistry.byId("sharedfate:typo").orElseThrow().setTypes(),
				"오타난 하나만 빠지고 나머지는 남는다");
		assertTrue(PerkRegistry.byId("sharedfate:all_wrong").orElseThrow().setTypes().isEmpty(),
				"전부 모르는 값이면 무유형이 될 뿐이다");
		assertTrue(PerkRegistry.byId("sharedfate:not_an_array").orElseThrow().setTypes().isEmpty(),
				"배열이 아니면 무유형으로 본다");
		assertEquals(List.of(PerkSetType.MINING),
				PerkRegistry.byId("sharedfate:mixed_junk").orElseThrow().setTypes(),
				"문자열이 아닌 항목은 그냥 지나친다");
	}

	@Test
	void 같은_유형을_두_번_적어도_한_번만_센다(@TempDir Path dir) throws IOException {
		write(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:dup_type", "rarity": "silver",
				      "set_types": ["mining", "mining"],
				      "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] }
				  ]
				}
				""");

		PerkRegistry.load(dir);

		assertEquals(List.of(PerkSetType.MINING),
				PerkRegistry.byId("sharedfate:dup_type").orElseThrow().setTypes());
	}

	@Test
	void 유형을_적지_않는_예전_생성자는_무유형을_만든다() {
		Perk perk = new Perk("sharedfate:old", "옛", "설명", PerkRarity.SILVER, List.of());

		assertEquals(List.of(), perk.setTypes());
		assertTrue(perk.setTypes().isEmpty());
	}

	private static void write(Path dir, String json) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), json, StandardCharsets.UTF_8);
	}
}
