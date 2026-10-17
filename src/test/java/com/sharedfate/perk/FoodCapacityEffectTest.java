package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.FoodCapacityEffect;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실버 「먹방」({@code food_capacity})의 규칙을 못박는다.
 *
 * <p>사람이 정한 것(2026-10-18) — 배가 불러도 먹을 수 있고, 허기는 100 까지 쌓이며, 100 이 차도
 * 계속 먹을 수 있다. 포만감은 20 이 끝이다. 증강을 잃으면 허기를 20 으로 깎고 넘친 몫은 버린다.
 */
class FoodCapacityEffectTest {
	private static final String MUKBANG = "sharedfate:mukbang";

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	/** 기본 풀을 읽고 「먹방」을 가진 팀 상태를 만든다. */
	private static TeamState mukbangTeam() {
		PerkRegistry.loadBundled();
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add(MUKBANG);
		return state;
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 효과_타입으로_등록돼_있다() {
		assertNotNull(PerkEffectType.fromId("food_capacity"),
				"PerkEffectType 에 FOOD_CAPACITY 를 등록해야 한다 — 빠뜨리면 「먹방」이 조용히 버려진다");
	}

	@Test
	void 상한을_읽고_적지_않으면_100_이다() {
		FoodCapacityEffect written = assertInstanceOf(FoodCapacityEffect.class,
				FoodCapacityEffect.fromJson("t", 0, json("{ \"type\": \"food_capacity\", \"max\": 60 }")));
		assertEquals(60, written.capacity());

		FoodCapacityEffect omitted = assertInstanceOf(FoodCapacityEffect.class,
				FoodCapacityEffect.fromJson("t", 0, json("{ \"type\": \"food_capacity\" }")));
		assertEquals(100, omitted.capacity());
	}

	@Test
	void 뜻_없는_상한은_정의를_버린다() {
		assertNull(FoodCapacityEffect.fromJson("t", 0, json("{ \"max\": 20 }")),
				"20 은 바닐라와 같다");
		assertNull(FoodCapacityEffect.fromJson("t", 0, json("{ \"max\": 101 }")),
				"100 위는 저장값을 읽는 자리가 살려 두지 못한다");
	}

	@Test
	void 기본_풀의_먹방은_실버_회복_하나이고_대가가_없다() {
		PerkRegistry.loadBundled();
		Perk perk = PerkRegistry.byId(MUKBANG).orElseThrow();

		assertEquals(PerkRarity.SILVER, perk.rarity());
		assertEquals(List.of(PerkSetType.RECOVERY), perk.setTypes());
		assertEquals(1, perk.effects().size(), "대가 없음 — 효과는 상한 하나뿐이다");
		assertEquals(100, assertInstanceOf(FoodCapacityEffect.class, perk.effects().getFirst()).capacity());
		long sameIcon = PerkRegistry.all().stream().filter(p -> p.icon().equals(perk.icon())).count();
		assertEquals(1, sameIcon, "아이콘이 다른 증강과 겹친다: " + perk.icon());
	}

	// ------------------------------------------------------------------ 팀의 상한

	@Test
	void 증강이_없으면_상한은_20_이다() {
		assertEquals(20, PerkFoodCapacity.capacityOf((TeamState) null));
		assertEquals(20, PerkFoodCapacity.capacityOf(TeamState.fresh(20.0F)));
	}

	@Test
	void 먹방을_가진_팀은_상한이_100_이다() {
		assertEquals(100, PerkFoodCapacity.capacityOf(mukbangTeam()));
	}

	@Test
	void 증강을_끈_팀은_먹방이_있어도_20_이다() {
		TeamState state = mukbangTeam();
		state.perksEnabled = false;

		assertEquals(20, PerkFoodCapacity.capacityOf(state));
	}

	@Test
	void 상한이_20_위일_때만_배불러도_먹는다() {
		assertFalse(PerkFoodCapacity.eatsWhenFull(20));
		assertTrue(PerkFoodCapacity.eatsWhenFull(100));
	}

	// ------------------------------------------------------------------ 먹기

	@Test
	void 먹은_만큼_100_까지_찬다() {
		assertEquals(26, PerkFoodCapacity.feed(18, 0.0F, 8, 0.0F, 100).foodLevel());
		assertEquals(65, PerkFoodCapacity.feed(57, 0.0F, 8, 0.0F, 100).foodLevel());
		assertEquals(100, PerkFoodCapacity.feed(98, 0.0F, 8, 0.0F, 100).foodLevel());
	}

	@Test
	void 백이_차도_먹을_수_있고_허기만_더_안_오른다() {
		PerkFoodCapacity.Fed fed = PerkFoodCapacity.feed(100, 3.0F, 8, 12.8F, 100);

		assertEquals(100, fed.foodLevel());
		assertEquals(15.8F, fed.saturation(), 1.0e-5F, "포만감은 20 까지 계속 찬다");
	}

	@Test
	void 포만감은_허기가_20_을_넘어도_20_이_끝이다() {
		assertEquals(20.0F, PerkFoodCapacity.feed(57, 15.0F, 8, 12.8F, 100).saturation(), 1.0e-6F);
		assertEquals(20.0F, PerkFoodCapacity.saturationCeiling(57));
		assertEquals(6.0F, PerkFoodCapacity.saturationCeiling(6), "허기 아래에서는 바닐라처럼 허기가 끝이다");
	}

	@Test
	void 상한이_20_이면_바닐라와_같다() {
		PerkFoodCapacity.Fed fed = PerkFoodCapacity.feed(18, 2.0F, 8, 12.8F, 20);

		assertEquals(20, fed.foodLevel());
		assertEquals(14.8F, fed.saturation(), 1.0e-5F);
	}

	@Test
	void 바닐라_add_는_쌓인_허기를_깎지_않는다() {
		// 포화 상태이상이 바닐라 eat 을 부르는 자리. 57 이 20 으로 잘리면 팀이 37 을 잃는다.
		assertEquals(57, PerkFoodCapacity.vanillaAddCeiling(57));
		assertEquals(20, PerkFoodCapacity.vanillaAddCeiling(12), "증강이 없으면 바닐라의 20 그대로");
	}

	// ------------------------------------------------------------------ 공유 풀

	@Test
	void 풀에_더할_때_상한까지만_오른다() {
		assertEquals(59, PerkFoodCapacity.raise(57, 2, 100));
		assertEquals(100, PerkFoodCapacity.raise(99, 5, 100));
		assertEquals(20, PerkFoodCapacity.raise(18, 5, 20));
	}

	@Test
	void 풀에_더할_때_20_으로_되깎지_않는다() {
		// 상한이 막 내려간 바퀴에도 더하는 쪽이 깎지는 않는다. 깎는 일은 StatMirror 가 맡는다.
		assertEquals(57, PerkFoodCapacity.raise(57, 2, 20));
		assertEquals(57, PerkFoodCapacity.raise(57, 0, 100));
	}

	@Test
	void 처치_보상이_먹방_팀의_허기를_20_으로_자르지_않는다() {
		TeamState state = mukbangTeam();
		state.foodLevel = 57;
		state.saturation = 19.0F;

		PerkKillRewards.applyToPool(state, new PerkKillRewards.Reward(10, 2.0F, 0.0F));

		assertEquals(67, state.foodLevel);
		assertEquals(20.0F, state.saturation, 1.0e-6F, "포만감은 20 이 끝이다");
	}

	@Test
	void 처치_보상은_증강이_없으면_예전처럼_20_까지다() {
		TeamState state = TeamState.fresh(20.0F);
		state.foodLevel = 15;

		PerkKillRewards.applyToPool(state, new PerkKillRewards.Reward(10, 0.0F, 0.0F));

		assertEquals(20, state.foodLevel);
	}

	@Test
	void 맞아서_차는_허기도_먹방_팀이면_100_까지다() {
		TeamState state = mukbangTeam();
		state.foodLevel = 98;
		PerkTriggers.applyHungerToPool(state, 5);
		assertEquals(100, state.foodLevel);

		TeamState plain = TeamState.fresh(20.0F);
		plain.foodLevel = 18;
		PerkTriggers.applyHungerToPool(plain, 5);
		assertEquals(20, plain.foodLevel);
	}

	// ------------------------------------------------------------------ 저장

	@Test
	void 저장된_팀_허기는_20_을_넘어도_살아남는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.foodLevel = 57;
		state.saturation = 30.0F;

		state.sanitize(20.0F);

		assertEquals(57, state.foodLevel, "읽는 자리가 20 으로 깎으면 재접속마다 먹은 것이 사라진다");
		assertEquals(20.0F, state.saturation, "포만감은 20 이 끝이다");
	}

	@Test
	void 저장된_팀_허기도_100_을_넘지는_않는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.foodLevel = 150;

		state.sanitize(20.0F);

		assertEquals(100, state.foodLevel);
	}
}
