package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.FoodNutritionEffect;
import com.sharedfate.perk.effect.OnKillEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.team.TeamState;
import net.minecraft.world.food.FoodProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code food_nutrition} 의 정의 읽기, 배율 계산, 음식 정의 바꿔치기를 본다.
 *
 * <p>실제로 바꿔치기가 일어나는 자리는 {@code FoodPropertiesMixin} 이고 그건 살아 있는 서버가
 * 있어야 확인할 수 있다. 여기서는 그 mixin 이 물어보는 질문
 * ({@link PerkFoodRules#nutritionMultiplier}, {@link FoodNutritionEffect#scale})만 확인한다.
 */
class FoodNutritionEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 배율과_하위_효과를_읽는다() {
		JsonObject json = JsonParser.parseString("""
				{ "type": "food_nutrition", "multiplier": 3.0,
				  "effects": [ { "type": "status_effect", "effect": "minecraft:poison",
				                 "amplifier": 0, "duration": 4 } ] }
				""").getAsJsonObject();

		PerkEffect effect = PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0, json);

		assertInstanceOf(FoodNutritionEffect.class, effect);
		FoodNutritionEffect nutrition = (FoodNutritionEffect) effect;
		assertEquals(3.0, nutrition.multiplier());

		List<OnKillEffect.Grant> grants = nutrition.grants();
		assertEquals(1, grants.size());
		assertEquals(80, grants.getFirst().durationTicks(), "4초는 80틱이다");
		assertInstanceOf(StatusEffectPerk.class, grants.getFirst().effect());
	}

	@Test
	void duration_을_적지_않으면_기본값이다() {
		PerkEffect effect = PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0,
				JsonParser.parseString("""
						{ "type": "food_nutrition", "multiplier": 2.0,
						  "effects": [ { "type": "status_effect", "effect": "minecraft:poison" } ] }
						""").getAsJsonObject());

		int expected = (int) Math.round(
				OnKillEffect.DEFAULT_DURATION_SECONDS * OnKillEffect.TICKS_PER_SECOND);
		assertEquals(expected,
				((FoodNutritionEffect) effect).grants().getFirst().durationTicks());
	}

	@Test
	void 배율도_하위_효과도_없으면_버린다() {
		assertNull(PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"food_nutrition\" }").getAsJsonObject()));
	}

	@Test
	void 범위를_벗어난_배율은_버린다() {
		assertNull(PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"food_nutrition\", \"multiplier\": -1.0 }")
						.getAsJsonObject()));
		assertNull(PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"food_nutrition\", \"multiplier\": 999.0 }")
						.getAsJsonObject()));
	}

	@Test
	void 하위_효과가_하나라도_잘못되면_통째로_버린다() {
		assertNull(PerkEffectType.FOOD_NUTRITION.create("sharedfate:테스트", 0,
				JsonParser.parseString("""
						{ "type": "food_nutrition", "multiplier": 3.0,
						  "effects": [ { "type": "없는타입" } ] }
						""").getAsJsonObject()));
	}

	@Test
	void 배율이_1_이면_음식_정의를_그대로_돌려준다() {
		FoodProperties bread = new FoodProperties(5, 6.0F, false);

		assertSame(bread, FoodNutritionEffect.scale(bread, 1.0),
				"증강이 없는 팀의 먹기 경로에는 새 객체가 생기지 않아야 한다");
	}

	@Test
	void 허기와_포만감에_같은_배율이_걸린다() {
		FoodProperties bread = new FoodProperties(5, 6.0F, true);

		FoodProperties scaled = FoodNutritionEffect.scale(bread, 3.0);

		assertEquals(15, scaled.nutrition());
		assertEquals(18.0F, scaled.saturation(), 1.0e-4F);
		assertTrue(scaled.canAlwaysEat(), "먹을 수 있는 조건은 건드리지 않는다");
	}

	@Test
	void 영양은_반올림하고_상한에서_자른다() {
		assertEquals(2, FoodNutritionEffect.scale(new FoodProperties(1, 0.0F, false), 1.5).nutrition(),
				"1 × 1.5 는 2 로 반올림한다");
		assertEquals(FoodNutritionEffect.MAX_SCALED_NUTRITION,
				FoodNutritionEffect.scale(new FoodProperties(100, 0.0F, false), 16.0).nutrition());
	}

	@Test
	void 증강이_없으면_배율이_1_이다() {
		assertEquals(1.0, PerkFoodRules.nutritionMultiplier((TeamState) null));
		assertEquals(1.0, PerkFoodRules.nutritionMultiplier(TeamState.fresh(20.0F)));
	}

	@Test
	void 가진_배율을_모두_곱한다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:진수성찬");
		assertEquals(3.0, PerkFoodRules.nutritionMultiplier(state));

		state.ownedPerks.add("sharedfate:소식");
		assertEquals(1.5, PerkFoodRules.nutritionMultiplier(state), "3.0 × 0.5");
	}

	@Test
	void 막는_증강과_함께_있으면_막는_쪽이_이긴다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:진수성찬");
		state.ownedPerks.add("sharedfate:포식");

		assertTrue(PerkFoodRules.blocks(state), "no_food_hunger 판정이 먼저 서고, 서면 배율은 쓰이지 않는다");
		assertEquals(3.0, PerkFoodRules.nutritionMultiplier(state),
				"배율 자체는 그대로 남아 있다. 쓰지 않을 뿐이다");
	}

	@Test
	void 다른_증강만_있으면_배율이_1_이다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:포식");

		assertEquals(1.0, PerkFoodRules.nutritionMultiplier(state));
		assertFalse(PerkFoodRules.blocks(TeamState.fresh(20.0F)));
	}

	@Test
	void 기본_풀에_실린_음식_허기_증강_셋이_그대로_읽힌다() {
		// 정의는 모드 안에만 있다. 설정 폴더를 보지 않는다.
		PerkRegistry.loadBundled();

		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;

		state.ownedPerks.add("sharedfate:endless_recovery");
		assertEquals(1.5, PerkFoodRules.exhaustionMultiplier(state), "무한 회복, 무한 식비");

		state.ownedPerks.clear();
		state.ownedPerks.add("sharedfate:spoiled_feast");
		assertEquals(3.0, PerkFoodRules.nutritionMultiplier(state), "상한 진수성찬");

		state.ownedPerks.clear();
		state.ownedPerks.add("sharedfate:ascetic");
		assertEquals(0.0, PerkFoodRules.exhaustionMultiplier(state), "고행자");
		// 12 는 너무 빡빡해 2026-09-30 에 16 으로 올렸다.
		assertEquals(16.0, PerkHealthRules.lockedMaxHealth(state).orElseThrow());
	}

	/** 회복량 배율 둘과, 먹기를 막는 증강 하나를 담은 풀. */
	private static Path pool(Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), """
				{
				  "perks": [
				    { "id": "sharedfate:진수성찬", "rarity": "gold", "name": "상한 진수성찬",
				      "effects": [
				        { "type": "food_nutrition", "multiplier": 3.0,
				          "effects": [ { "type": "status_effect", "effect": "minecraft:poison",
				                         "amplifier": 0, "duration": 4 } ] }
				      ] },
				    { "id": "sharedfate:소식", "rarity": "silver", "name": "소식",
				      "effects": [ { "type": "food_nutrition", "multiplier": 0.5 } ] },
				    { "id": "sharedfate:포식", "rarity": "prism", "name": "포식",
				      "effects": [ { "type": "no_food_hunger" } ] }
				  ]
				}
				""", StandardCharsets.UTF_8);
		return dir;
	}
}
