package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.CompassTargetEffect;
import com.sharedfate.perk.effect.CompassToggleEffect;
import com.sharedfate.perk.effect.RuinSurveyEffect;
import com.sharedfate.perk.effect.SmeltingSpeedEffect;
import com.sharedfate.perk.effect.VillagerTradeEffect;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 「개척」의 네 효과 타입이 정의에서 제대로 들어오는지, 그리고 셈이 맞는지 본다.
 *
 * <p>여기서 지키는 것은 <b>정의를 읽는 길</b>과 <b>숫자를 다루는 규칙</b>이다. 살아 있는 서버가
 * 필요한 부분(구조물 탐색·주민·화로)은 {@code PioneerTargetTest} 가 바닐라 자리만 못박는다.
 */
class PioneerSetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	// ------------------------------------------------------------------ 유형

	/**
	 * 개척은 <b>2단계 하나뿐</b>이다.
	 *
	 * <p>3·4단계를 안 만든 것은 <b>의도한 것</b>이다. 나중에 「단계가 비어 있는데?」 하고 채우지
	 * 않도록 여기서 못박는다. 까닭은 {@link PerkSetType#PIONEER} 주석에 있다.
	 */
	@Test
	void 개척은_2단계_하나뿐이다() {
		PerkSetRegistry.loadBundled();

		List<PerkSets.Tier> tiers = PerkSetRegistry.tiersOf(PerkSetType.PIONEER);
		assertEquals(1, tiers.size(), "개척에 단계를 더하면 이 시험부터 고쳐야 한다");
		assertEquals(2, tiers.get(0).count());
		assertEquals(2, PerkSetType.PIONEER.threshold());
		assertEquals("개척", PerkSetType.PIONEER.displayName());
	}

	/** 개척 2 는 「굽는 속도」와 「나침반 토글」 둘로 되어 있다. 하나가 빠지면 조용히 사라진다. */
	@Test
	void 개척_2는_효과_둘을_들고_있다() {
		PerkSetRegistry.loadBundled();

		List<PerkEffect> effects = PerkSetRegistry.tiersOf(PerkSetType.PIONEER).get(0).effects();
		assertEquals(2, effects.size(), effects.toString());
		SmeltingSpeedEffect speed = null;
		CompassToggleEffect toggle = null;
		for (PerkEffect effect : effects) {
			if (effect instanceof SmeltingSpeedEffect found) {
				speed = found;
			}
			if (effect instanceof CompassToggleEffect found) {
				toggle = found;
			}
		}
		assertNotNull(speed, "smelting_speed 가 빠졌다");
		assertNotNull(toggle, "compass_toggle 이 빠졌다");
		assertEquals(2.0, speed.multiplierFor(), 1.0e-9, "화로·용광로·훈연기가 2배");
		assertEquals(Level.OVERWORLD, toggle.alternate().dimension(), "엔더 요새는 오버월드다");
		assertEquals(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:stronghold")),
				toggle.alternate().structureKey(), "26.3 에 #minecraft:stronghold 태그는 없다");
		assertNull(toggle.alternate().structureTag(), "태그가 아니라 구조물 이름으로 적어야 한다");
	}

	/** 개척 증강 넷이 모두 들어 있고 등급이 맞다. */
	@Test
	void 개척_증강은_넷이다() {
		PerkRegistry.loadBundled();

		List<String> pioneer = PerkRegistry.all().stream()
				.filter(perk -> perk.hasSetType(PerkSetType.PIONEER))
				.map(Perk::id)
				.sorted()
				.toList();
		assertEquals(List.of("sharedfate:fortress_finder", "sharedfate:haggling_master",
				"sharedfate:ruin_appraiser", "sharedfate:village_finder"), pioneer);
		assertEquals(PerkRarity.GOLD,
				PerkRegistry.byId("sharedfate:haggling_master").orElseThrow().rarity());
		assertEquals(PerkRarity.SILVER,
				PerkRegistry.byId("sharedfate:ruin_appraiser").orElseThrow().rarity());
	}

	/**
	 * 「요새 탐지기」와 세트 효과는 <b>서로 다른 것</b>을 가리킨다.
	 *
	 * <p>한국어로는 둘 다 「요새」라 겹쳐 보이지만, 요새 탐지기는 네더의
	 * {@code minecraft:fortress}(네더 요새)이고 세트 토글은 오버월드의
	 * {@code minecraft:stronghold}(엔더 요새)다. 이름을 고치려 들 때 이 시험을 먼저 보라.
	 */
	@Test
	void 요새_탐지기와_세트_토글은_다른_구조물이다() {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();

		CompassTargetEffect fortress = null;
		for (PerkEffect effect
				: PerkRegistry.byId("sharedfate:fortress_finder").orElseThrow().effects()) {
			if (effect instanceof CompassTargetEffect found) {
				fortress = found;
			}
		}
		assertNotNull(fortress);
		assertEquals(Level.NETHER, fortress.dimension(), "네더 요새는 네더에 있다");
		assertEquals(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:fortress")),
				fortress.structureKey());

		CompassToggleEffect toggle = null;
		for (PerkEffect effect : PerkSetRegistry.tiersOf(PerkSetType.PIONEER).get(0).effects()) {
			if (effect instanceof CompassToggleEffect found) {
				toggle = found;
			}
		}
		assertNotNull(toggle);
		assertTrue(!toggle.alternate().dimension().equals(fortress.dimension())
						|| !toggle.alternate().structureKey().equals(fortress.structureKey()),
				"둘이 같아지면 요새 탐지기를 가진 팀에게 세트 효과 절반이 무의미해진다");
	}

	// ------------------------------------------------------------------ 굽는 속도

	@Test
	void 굽는_시간은_나눈_뒤_올림이고_1틱은_남는다() {
		// 바닐라가 speedMultiplier 를 먹일 때와 같은 셈이다.
		assertEquals(100, PerkSmelting.reducedCookTime(200, 2.0));
		assertEquals(50, PerkSmelting.reducedCookTime(100, 2.0));
		assertEquals(5, PerkSmelting.reducedCookTime(9, 2.0), "9 / 2 = 4.5 → 올림 5");
		assertEquals(1, PerkSmelting.reducedCookTime(1, 2.0), "1틱은 더 줄일 수 없다");
		assertEquals(1, PerkSmelting.reducedCookTime(4, 100.0), "0틱이 되면 굽는 단계가 사라진다");
		assertEquals(200, PerkSmelting.reducedCookTime(200, 1.0), "1배면 손대지 않는다");
		assertEquals(200, PerkSmelting.reducedCookTime(200, 0.0), "못 믿을 값이면 그대로");
		assertEquals(200, PerkSmelting.reducedCookTime(200, Double.NaN));
		assertEquals(400, PerkSmelting.reducedCookTime(200, 0.5), "느려지는 정의도 쓸 수 있다");
	}

	@Test
	void 굽는_속도_정의는_범위를_지킨다() {
		assertInstanceOf(SmeltingSpeedEffect.class, smelting(2.0));
		assertNull(smelting(0.0), "0 이면 굽는 시간을 0 으로 나누게 된다");
		assertNull(smelting(-1.0));
		assertNull(smelting(1000.0), "상한을 넘으면 정의를 버린다");
	}

	// ------------------------------------------------------------------ 나침반 토글

	@Test
	void 토글은_두_이름을_들고_있다() {
		CompassToggleEffect toggle = assertInstanceOf(CompassToggleEffect.class, toggle("""
				{ "type": "compass_toggle", "structure": "minecraft:stronghold",
				  "dimension": "minecraft:overworld", "search_radius": 100,
				  "base_name": "길잡이 나침반", "alternate_name": "엔더 요새 나침반" }
				"""));
		assertEquals("길잡이 나침반", toggle.nameFor(false));
		assertEquals("엔더 요새 나침반", toggle.nameFor(true));
	}

	/**
	 * 안에 품은 {@code CompassTargetEffect} 는 <b>같은 객체</b>여야 한다.
	 *
	 * <p>{@link PerkCompassTargets} 의 탐색 캐시가 {@code IdentityHashMap} 이라, 부를 때마다 새로
	 * 만들면 캐시가 매번 빗나가 10초마다 엔더 요새를 다시 찾는다. 구조물 탐색은 서버 스레드를
	 * 붙잡는 작업이라 그것만으로도 눈에 띈다.
	 */
	@Test
	void 대체_정의는_매번_같은_객체다() {
		CompassToggleEffect toggle = assertInstanceOf(CompassToggleEffect.class, toggle("""
				{ "type": "compass_toggle", "structure": "minecraft:stronghold",
				  "dimension": "minecraft:overworld",
				  "base_name": "가", "alternate_name": "나" }
				"""));
		assertSame(toggle.alternate(), toggle.alternate());
	}

	@Test
	void 토글_정의가_잘못되면_버린다() {
		assertNull(toggle("""
				{ "type": "compass_toggle", "dimension": "minecraft:overworld",
				  "base_name": "가", "alternate_name": "나" }
				"""), "구조물이 없으면 무엇을 가리킬지 알 수 없다");
		assertNull(toggle("""
				{ "type": "compass_toggle", "structure": "minecraft:stronghold",
				  "dimension": "minecraft:overworld", "base_name": "가" }
				"""), "이름 한쪽만 적으면 눌렀을 때 무엇이 됐는지 알 수 없다");
		assertNull(toggle("""
				{ "type": "compass_toggle", "structure": "minecraft:stronghold",
				  "dimension": "minecraft:overworld",
				  "base_name": "같다", "alternate_name": "같다" }
				"""), "두 이름이 같으면 눌러도 안 눌린 것처럼 보인다");
		assertNull(toggle("""
				{ "type": "compass_toggle", "structure": "minecraft:stronghold",
				  "dimension": "minecraft:overworld", "search_radius": 9999,
				  "base_name": "가", "alternate_name": "나" }
				"""), "반경 상한은 compass_target 과 같은 곳에서 걸러진다");
	}

	// ------------------------------------------------------------------ 흥정의 달인

	@Test
	void 주민_거래_정의는_셋을_모두_요구한다() {
		assertInstanceOf(VillagerTradeEffect.class, trade("""
				{ "type": "villager_trade", "price_discount": 0.15,
				  "restock_speed": 2.0, "experience_multiplier": 0.5 }
				"""));
		assertNull(trade("""
				{ "type": "villager_trade", "restock_speed": 2.0, "experience_multiplier": 0.5 }
				"""), "이득만 적힌 정의를 만들 수 없게 셋 다 필수다");
		assertNull(trade("""
				{ "type": "villager_trade", "price_discount": 0.99,
				  "restock_speed": 2.0, "experience_multiplier": 0.5 }
				"""), "공짜가 되는 비율은 받지 않는다");
	}

	/** 영웅의 증표와 같은 셈이다 — 원가에 비율을 곱해 내림하고, 0 이 되면 1 로 올린다. */
	@Test
	void 값_깎기는_원가에_비례하고_최소_1이다() {
		// 원가 6 에 15% 면 0.9 라 내림하면 0 이다. 그때 1 을 깎는 것이 바닐라의 규칙이다.
		assertEquals(1, discount(6, 0.15), "0 이 되면 1 로 올린다");
		assertEquals(4, discount(32, 0.15), "32 × 0.15 = 4.8 → 내림 4");
		assertEquals(9, discount(64, 0.15), "64 × 0.15 = 9.6 → 내림 9");
		assertEquals(1, discount(1, 0.15), "가장 싼 거래도 1 은 깎인다");
	}

	@Test
	void 재입고_대기는_나눈_뒤_올림이고_1틱은_남는다() {
		assertEquals(6000L, PerkVillagerTrades.reducedRestockTicks(12000L, 2.0));
		assertEquals(1200L, PerkVillagerTrades.reducedRestockTicks(2400L, 2.0));
		assertEquals(12000L, PerkVillagerTrades.reducedRestockTicks(12000L, 1.0), "1배면 그대로");
		assertEquals(1L, PerkVillagerTrades.reducedRestockTicks(10L, 100.0),
				"0 이 되면 주민이 틱마다 재입고를 시도한다");
		assertEquals(12000L, PerkVillagerTrades.reducedRestockTicks(12000L, 0.0),
				"못 믿을 값이면 바닐라 그대로");
	}

	// ------------------------------------------------------------------ 유적 감별사

	@Test
	void 유적_정의는_구조물과_이름표를_짝지어_읽는다() {
		RuinSurveyEffect survey = assertInstanceOf(RuinSurveyEffect.class, ruin("""
				{ "type": "ruin_survey", "dimension": "minecraft:overworld",
				  "search_radius": 100,
				  "structures": [ { "id": "minecraft:ancient_city", "label": "고대 도시" },
				                  { "id": "minecraft:mansion", "label": "숲의 저택" } ] }
				"""));
		assertEquals(Level.OVERWORLD, survey.dimension());
		assertEquals(2, survey.targets().size());
		assertEquals("고대 도시", survey.targets().get(0).label(), "정의에 적힌 순서를 지킨다");
		assertEquals("숲의 저택", survey.targets().get(1).label());
		// 26.3 의 구조물 이름은 woodland_mansion 이 아니라 mansion 이다.
		assertEquals(ResourceKey.create(Registries.STRUCTURE, Identifier.parse("minecraft:mansion")),
				survey.targets().get(1).key());
	}

	@Test
	void 유적_정의는_태그를_받지_않는다() {
		assertNull(ruin("""
				{ "type": "ruin_survey", "dimension": "minecraft:overworld",
				  "structures": [ { "id": "#minecraft:village", "label": "마을" } ] }
				"""), "태그는 묶음이라 어느 것을 찾았는지에 따라 이름표가 거짓말을 한다");
		assertNull(ruin("""
				{ "type": "ruin_survey", "dimension": "minecraft:overworld",
				  "structures": [] }
				"""), "빈 배열이면 보여 줄 줄이 없다");
		assertNull(ruin("""
				{ "type": "ruin_survey", "dimension": "minecraft:overworld",
				  "structures": [ { "id": "minecraft:mansion" } ] }
				"""), "이름표가 없으면 무엇의 좌표인지 알 수 없다");
	}

	/** 못 찾은 줄도 반드시 보인다. 줄이 사라지면 「증강이 안 먹는다」로 읽힌다. */
	@Test
	void 못_찾으면_찾지_못함이라고_적는다() {
		assertEquals("고대 도시  찾지 못함",
				new PerkRuinSurvey.Found("고대 도시", null).line());
		assertEquals("숲의 저택  -1234, 567",
				new PerkRuinSurvey.Found("숲의 저택",
						new net.minecraft.core.BlockPos(-1234, 64, 567)).line());
	}

	/** 정의에 적힌 개척 증강의 실제 값. 값이 바뀌면 문서도 함께 고쳐야 한다. */
	@Test
	void 흥정의_달인의_값이_정의와_같다() {
		PerkRegistry.loadBundled();

		VillagerTradeEffect effect = null;
		for (PerkEffect candidate
				: PerkRegistry.byId("sharedfate:haggling_master").orElseThrow().effects()) {
			if (candidate instanceof VillagerTradeEffect found) {
				effect = found;
			}
		}
		assertNotNull(effect);
		assertEquals(0.15, effect.priceDiscount(), 1.0e-9, "가격 15% 감소");
		assertEquals(2.0, effect.restockSpeed(), 1.0e-9, "재입고 대기 50% 단축 = 2배");
		assertEquals(0.5, effect.experienceMultiplier(), 1.0e-9, "거래 경험치 50% 감소");
	}

	// ------------------------------------------------------------------ 도우미

	private static PerkEffect smelting(double multiplier) {
		JsonObject json = new JsonObject();
		json.addProperty("type", "smelting_speed");
		json.addProperty("multiplier", multiplier);
		return PerkEffectType.SMELTING_SPEED.create("sharedfate:test", 0, json);
	}

	private static PerkEffect toggle(String json) {
		return PerkEffectType.COMPASS_TOGGLE.create("sharedfate:test", 0, parse(json));
	}

	private static PerkEffect trade(String json) {
		return PerkEffectType.VILLAGER_TRADE.create("sharedfate:test", 0, parse(json));
	}

	private static PerkEffect ruin(String json) {
		return PerkEffectType.RUIN_SURVEY.create("sharedfate:test", 0, parse(json));
	}

	private static int discount(int baseCount, double rate) {
		return PerkVillagerTrades.discountFor(
				new net.minecraft.world.item.trading.MerchantOffer(
						new net.minecraft.world.item.trading.ItemCost(
								net.minecraft.world.item.Items.EMERALD, baseCount),
						new net.minecraft.world.item.ItemStack(
								net.minecraft.world.item.Items.DIAMOND, 1),
						16, 2, 0.05F),
				rate);
	}

	private static JsonObject parse(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}
}
