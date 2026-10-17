package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.DamageIncreaseReliefEffect;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실버 「굳은살」({@code damage_increase_relief})을 본다.
 *
 * <p>대가가 지나는 경로마다 하나씩 증강을 두고, 그 경로의 계산을 끝에서 끝까지 확인한다.
 * 살아 있는 플레이어가 없어도 되는 길만 쓴다 — 받는 피해는 {@link PerkManager#multiplierOf},
 * 피해 종류별 배율은 {@link PerkDamage#takenSourceMultiplier}, 몹 공격력은
 * {@link MobPerkModifiers#teamMultiplier} 다.
 */
class DamageIncreaseReliefTest {

	/** 「바람 빠진 방패」·「화살막이」처럼 방어 유형 셋을 모을 수 있게 방어 증강 셋을 넣었다. */
	private static final String PERKS = """
			{ "perks": [
			  { "id": "sharedfate:t_relief", "rarity": "silver", "name": "굳은살",
			    "effects": [ { "type": "damage_increase_relief", "reduction": 0.5 } ] },
			  { "id": "sharedfate:t_glass", "rarity": "prism", "name": "유리 세계",
			    "effects": [ { "type": "mob_damage", "multiplier": 2.0,
			      "excludes": [ "minecraft:ender_dragon" ] } ] },
			  { "id": "sharedfate:t_skin", "rarity": "gold", "name": "타지 않는 살갗",
			    "effects": [
			      { "type": "damage_taken_from", "multiplier": 0.0, "sources": [ "minecraft:in_fire" ] },
			      { "type": "damage_taken", "multiplier": 1.2 }
			    ] },
			  { "id": "sharedfate:t_arrow", "rarity": "silver", "name": "화살막이",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "damage_taken_from", "multiplier": 0.5, "sources": [ "minecraft:arrow" ] },
			      { "type": "damage_taken_from", "multiplier": 1.2,
			        "sources": [ "minecraft:explosion" ], "drawback": true }
			    ] },
			  { "id": "sharedfate:t_def_a", "rarity": "silver", "name": "방어 하나",
			    "set_types": [ "defense" ],
			    "effects": [ { "type": "damage_taken", "multiplier": 0.9 } ] },
			  { "id": "sharedfate:t_def_b", "rarity": "silver", "name": "방어 둘",
			    "set_types": [ "defense" ],
			    "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] },
			  { "id": "sharedfate:t_assassin", "rarity": "gold", "name": "암살자",
			    "effects": [ { "type": "mob_damage", "multiplier": 1.3,
			      "excludes": [ "minecraft:creeper" ] } ] },
			  { "id": "sharedfate:t_watch", "rarity": "silver", "name": "불면의 파수꾼",
			    "effects": [ { "type": "mob_damage", "multiplier": 0.7,
			      "targets": [ "minecraft:zombie" ] } ] },
			  { "id": "sharedfate:t_guard", "rarity": "gold", "name": "호위",
			    "effects": [ { "type": "holder", "fixed_to_owner": true, "pass_on_hurt": false,
			      "on_holder": [],
			      "on_others": [ { "type": "damage_taken", "multiplier": 1.1 } ] } ] }
			] }
			""";

	/** 방어 3단계만 있는 세트 정의. */
	private static final String SETS = """
			{ "sets": [
			  { "type": "defense", "tiers": [
			    { "count": 3, "name": "대가는 안 치른다",
			      "description": "가지고 있는 방어 증강들의 대가가 사라집니다.",
			      "effects": [ { "type": "no_defense_drawbacks" } ] }
			  ] }
			] }
			""";

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
		PerkHolderManager.reset();
		ConditionalPerkManager.beginMultiplierLookupForTesting(null);
	}

	// ------------------------------------------------------------------ 순수 계산

	@Test
	void 증가분만_절반이_된다() {
		assertEquals(1.1, DamageIncreaseReliefEffect.ease(1.2, 0.5), 1.0e-9);
		assertEquals(1.5, DamageIncreaseReliefEffect.ease(2.0, 0.5), 1.0e-9);
		assertEquals(1.15, DamageIncreaseReliefEffect.ease(1.3, 0.5), 1.0e-9);
		assertEquals(1.075, DamageIncreaseReliefEffect.ease(1.15, 0.5), 1.0e-9);
	}

	@Test
	void 줄이는_배율은_그대로다() {
		assertEquals(0.5, DamageIncreaseReliefEffect.ease(0.5, 0.5), 1.0e-9);
		assertEquals(0.0, DamageIncreaseReliefEffect.ease(0.0, 0.5), 1.0e-9);
		assertEquals(1.0, DamageIncreaseReliefEffect.ease(1.0, 0.5), 1.0e-9);
		assertEquals(1.2, DamageIncreaseReliefEffect.ease(1.2, 1.0), 1.0e-9, "남길 몫 1 이면 그대로");
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void reduction_을_빠뜨리면_절반이다() {
		DamageIncreaseReliefEffect effect = assertInstanceOf(DamageIncreaseReliefEffect.class,
				create("{ \"type\": \"damage_increase_relief\" }"));
		assertEquals(0.5, effect.reduction(), 1.0e-9);
	}

	@Test
	void reduction_이_범위를_벗어나거나_숫자가_아니면_버린다() {
		assertNull(create("{ \"type\": \"damage_increase_relief\", \"reduction\": 1.5 }"));
		assertNull(create("{ \"type\": \"damage_increase_relief\", \"reduction\": -0.1 }"));
		assertNull(create("{ \"type\": \"damage_increase_relief\", \"reduction\": \"절반\" }"));
	}

	// ------------------------------------------------------------------ 굳은살

	@Test
	void 몹에게_받는_피해_대가가_절반이_된다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_glass", "sharedfate:t_relief");

		assertEquals(1.5, damage(state, EntityTypes.ZOMBIE), 1.0e-9, "×2 → ×1.5");
		assertEquals(1.0, damage(state, EntityTypes.ENDER_DRAGON), 1.0e-9, "드래곤은 처음부터 빠져 있다");
	}

	@Test
	void 모든_피해_대가가_절반이_되고_면역은_그대로다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState without = teamWith("sharedfate:t_skin");
		TeamState with = teamWith("sharedfate:t_skin", "sharedfate:t_relief");

		assertEquals(1.2, PerkManager.multiplierOf(without, false), 1.0e-9);
		assertEquals(1.1, PerkManager.multiplierOf(with, false), 1.0e-9, "×1.2 → ×1.1");
		assertEquals(0.0, PerkDamage.takenSourceMultiplier(with, source(DamageTypes.IN_FIRE)), 1.0e-9,
				"화염 면역(×0)은 줄이는 쪽이라 손대지 않는다");
	}

	@Test
	void 폭발_대가는_절반이_되고_투사체_감소는_그대로다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_arrow", "sharedfate:t_relief");

		assertEquals(1.1, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.EXPLOSION)), 1.0e-9);
		assertEquals(0.5, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.ARROW)), 1.0e-9);
	}

	@Test
	void 방어_3단계로_면제된_대가와_겹쳐도_자연스럽다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_arrow", "sharedfate:t_def_a", "sharedfate:t_def_b",
				"sharedfate:t_relief");

		assertEquals(1.0, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.EXPLOSION)), 1.0e-9,
				"면제된 폭발 ×1.2 는 이미 없다 — 굳은살이 1 아래로 끌어내리지 않는다");
		assertEquals(0.5, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.ARROW)), 1.0e-9);
		assertEquals(0.9, PerkManager.multiplierOf(state, false), 1.0e-9, "방어 증강의 감소 ×0.9 는 그대로");
	}

	@Test
	void 몹_공격력_대가는_이_팀_몫만_절반이_된다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState without = teamWith("sharedfate:t_assassin", "sharedfate:t_watch");
		TeamState with = teamWith("sharedfate:t_assassin", "sharedfate:t_watch", "sharedfate:t_relief");

		assertEquals(1.3, damage(without, EntityTypes.SKELETON), 1.0e-9);
		assertEquals(1.15, damage(with, EntityTypes.SKELETON), 1.0e-9);
		// 좀비에는 ×1.3 과 ×0.7 이 함께 걸린다. 늘리는 쪽만 덜어 내므로 1.15 × 0.7 이다.
		assertEquals(1.15 * 0.7, damage(with, EntityTypes.ZOMBIE), 1.0e-9);
		assertEquals(1.0, damage(with, EntityTypes.CREEPER), 1.0e-9);
	}

	@Test
	void 보유자_효과_안의_대가도_절반이_된다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_guard", "sharedfate:t_relief");
		HolderEffect guard = assertInstanceOf(HolderEffect.class,
				PerkRegistry.byId("sharedfate:t_guard").orElseThrow().effects().get(0));
		UUID team = UUID.randomUUID();
		UUID holder = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		PerkHolderManager.setHolderForTesting(guard, team, holder);

		ConditionalPerkManager.beginMultiplierLookupForTesting(other);
		assertEquals(1.05, PerkManager.multiplierOf(state, false), 1.0e-9, "나머지 팀원 ×1.1 → ×1.05");
		ConditionalPerkManager.beginMultiplierLookupForTesting(holder);
		assertEquals(1.0, PerkManager.multiplierOf(state, false), 1.0e-9);
	}

	@Test
	void 굳은살이_없으면_아무것도_달라지지_않는다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_glass", "sharedfate:t_skin", "sharedfate:t_arrow");

		assertEquals(2.0, damage(state, EntityTypes.ZOMBIE), 1.0e-9);
		assertEquals(1.2, PerkManager.multiplierOf(state, false), 1.0e-9);
		assertEquals(1.2, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.EXPLOSION)), 1.0e-9);
		assertEquals(0.0, PerkDamage.takenSourceMultiplier(state, source(DamageTypes.IN_FIRE)), 1.0e-9,
				"「타지 않는 살갗」의 화염 면역 ×0 이 1.0 으로 물러나지 않는다");
	}

	@Test
	void 증강을_끈_팀에는_걸리지_않는다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:t_skin", "sharedfate:t_relief");
		state.perksEnabled = false;

		assertEquals(1.0, DamageIncreaseReliefEffect.keepFor(state), 1.0e-9);
	}

	// ------------------------------------------------------------------ 기본 풀

	@Test
	void 기본_풀의_대가들이_절반이_된다(@TempDir Path dir) throws IOException {
		loadBundled(dir);
		Perk callus = PerkRegistry.byId("sharedfate:callus").orElseThrow();
		assertEquals(PerkRarity.SILVER, callus.rarity());
		assertTrue(callus.setTypes().isEmpty(), "무유형이다");

		TeamState state = teamWith("sharedfate:callus");
		DamageIncreaseReliefEffect.Relief relief = DamageIncreaseReliefEffect.reliefFor(state);

		// 낙하 피해 +0.2 → +0.1, 「가벼운 발걸음」의 −0.4 는 그대로. 기본 풀에서 낙하 피해를
		// 늘리던 「허공답보」의 대가는 2026-10-16 에 걷어냈으므로 늘리는 쪽은 직접 만든다.
		AttributeEffect raise = assertInstanceOf(AttributeEffect.class, create("""
				{ "type": "attribute", "attribute": "minecraft:fall_damage_multiplier",
				  "operation": "add_multiplied_total", "amount": 0.2 }
				"""));
		AttributeEffect lightStep = fallAttribute("sharedfate:light_step");
		assertTrue(DamageIncreaseReliefEffect.raisesFallDamage(raise));
		assertFalse(DamageIncreaseReliefEffect.raisesFallDamage(lightStep));
		assertEquals(0.1, relief.easeAmount(raise, raise.amount()), 1.0e-9);
		assertEquals(-0.4, relief.easeAmount(lightStep, lightStep.amount()), 1.0e-9);
		assertTrue(PerkRegistry.byId("sharedfate:void_step").orElseThrow().effects().stream()
						.noneMatch(DamageIncreaseReliefEffect::raisesFallDamage),
				"허공답보에는 이제 낙하 피해 대가가 없다");

		// 「불굴」은 조건부 전체를 하나로 본다. 가득 참 ×1.1 → ×1.05, 절반 이하 ×0.7 은 그대로.
		Perk unbroken = PerkRegistry.byId("sharedfate:unbroken").orElseThrow();
		ConditionalEffect benefit = assertInstanceOf(ConditionalEffect.class, unbroken.effects().get(0));
		ConditionalEffect cost = assertInstanceOf(ConditionalEffect.class, unbroken.effects().get(1));
		assertEquals(1.05, relief.ease(cost.damageTakenMultiplier(true)), 1.0e-9);
		assertEquals(0.7, relief.ease(benefit.damageTakenMultiplier(true)), 1.0e-9);
	}

	/**
	 * 「유리 세계」·「엑스레이」·「반격」·「가벼운 위장」의 대가는 모든 피해가 아니라 <b>몹에게 받는
	 * 피해</b>다(사람 결정 2026-10-16). 엔더 드래곤은 빠진다.
	 */
	@Test
	void 기본_풀의_넷은_드래곤을_뺀_몹에게_받는_피해만_늘린다(@TempDir Path dir) throws IOException {
		loadBundled(dir);
		Object[][] expected = {
				{"sharedfate:glass_world", 2.0},
				{"sharedfate:diamond_sundial", 1.15},
				{"sharedfate:riposte", 1.1},
				{"sharedfate:light_disguise", 1.1},
		};
		for (Object[] row : expected) {
			String id = (String) row[0];
			double multiplier = (double) row[1];
			TeamState state = teamWith(id);
			assertEquals(1.0, PerkManager.multiplierOf(state, false), 1.0e-9, id + " 는 모든 피해에 걸리지 않는다");
			assertEquals(multiplier, damage(state, EntityTypes.ZOMBIE), 1.0e-9, id);
			assertEquals(1.0, damage(state, EntityTypes.ENDER_DRAGON), 1.0e-9, id + " 는 드래곤을 빼야 한다");
			assertTrue(PerkRegistry.byId(id).orElseThrow().description().contains("엔더 드래곤"), id);

			TeamState eased = teamWith(id, "sharedfate:callus");
			assertEquals(1.0 + (multiplier - 1.0) / 2.0, damage(eased, EntityTypes.ZOMBIE), 1.0e-9,
					id + " + 굳은살");
		}
	}

	// ------------------------------------------------------------------ 도우미

	private static PerkEffect create(String json) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		PerkEffectType type = PerkEffectType.fromId(parsed.get("type").getAsString());
		assertNotNull(type);
		return type.create("sharedfate:test", 0, parsed);
	}

	private static void load(Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), PERKS, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(PerkSetRegistry.FILE_NAME), SETS, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		PerkSetRegistry.load(dir);
	}

	private static void loadBundled(Path dir) throws IOException {
		try (InputStream bundled = DamageIncreaseReliefTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			assertNotNull(bundled, "번들에 sharedfate-perks-default.json 이 없다");
			Files.copy(bundled, dir.resolve(PerkRegistry.FILE_NAME));
		}
		PerkRegistry.load(dir);
	}

	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}

	private static AttributeEffect fallAttribute(String perkId) {
		return PerkRegistry.byId(perkId).orElseThrow().effects().stream()
				.filter(AttributeEffect.class::isInstance)
				.map(AttributeEffect.class::cast)
				.filter(a -> DamageIncreaseReliefEffect.FALL_DAMAGE_MULTIPLIER_ID.equals(a.attributeId()))
				.findFirst().orElseThrow();
	}

	/** 이 팀 몫의 몹 공격력 배율. 한 서버에 팀은 하나뿐이라 곧 「그 몹에게 받는 피해」다. */
	private static double damage(TeamState state, EntityType<?> type) {
		return MobPerkModifiers.teamMultiplier(state, type, true, MobPerkModifiers.Kind.DAMAGE);
	}

	private static DamageSource source(ResourceKey<DamageType> key) {
		return new DamageSource(type(key));
	}

	private static Holder<DamageType> type(ResourceKey<DamageType> key) {
		return TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
	}
}
