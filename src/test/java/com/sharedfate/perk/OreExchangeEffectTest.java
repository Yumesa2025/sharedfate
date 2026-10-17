package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.OreExchangeEffect;
import com.sharedfate.perk.effect.OreExchangeEffect.Result;
import com.sharedfate.team.SharedItemList;
import com.sharedfate.team.TeamState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ore_exchange}(실버 「나무꾼의 욕심」)의 정의 읽기와 확률 계산({@link
 * OreExchangeEffect#rollResult}), 그리고 뽑힌 결과를 공유 인벤토리에 넣는 자리를 본다.
 *
 * <p>실제로 우클릭을 감지하고 공유 인벤토리에서 나무를 세고 빼는 것은 사건·태그 판정이
 * 데이터팩·살아 있는 서버를 필요로 하므로 여기서 다루지 않는다. 빈 인벤토리처럼 태그 판정
 * 자체가 필요 없는 경계값만 확인한다. 반대로 지급({@code PerkOreExchange.grant})은 태그를 보지
 * 않고 레지스트리와 목록만 만지므로 여기서 그대로 부를 수 있다.
 */
class OreExchangeEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 필드가_없고_인스턴스를_돌려쓴다() {
		PerkEffect first = create();
		PerkEffect second = create();

		assertSame(OreExchangeEffect.INSTANCE, first);
		assertSame(first, second);
	}

	@Test
	void 효과_타입_문자열로_찾을_수_있다() {
		assertSame(PerkEffectType.ORE_EXCHANGE, PerkEffectType.fromId("ore_exchange"));
	}

	@Test
	void apply_와_remove_는_아무_일도_하지_않는다() {
		OreExchangeEffect effect = assertInstanceOf(OreExchangeEffect.class, create());

		assertDoesNotThrow(() -> effect.apply(null));
		assertDoesNotThrow(() -> effect.remove(null));
	}

	@Test
	void 상수가_설명과_맞는다() {
		assertEquals(25, OreExchangeEffect.WOOD_COST, "나무 25개를 소모한다");
		assertEquals(Identifier.withDefaultNamespace("wooden_axe"), OreExchangeEffect.TOOL);
	}

	// ------------------------------------------------------------------ 보상표

	@Test
	void 확률의_합은_100이다() {
		assertEquals(100, OreExchangeEffect.TOTAL_WEIGHT);
		assertEquals(10 + 15 + 13 + 30 + 30 + 2, OreExchangeEffect.TOTAL_WEIGHT);
	}

	/**
	 * 표 전체를 통째로 못박는다. 설명(JSON)에 적히는 확률이 여기서 나오므로, 값이 하나라도
	 * 움직이면 설명도 같이 고쳐야 한다는 것을 이 시험이 알려 준다.
	 */
	@Test
	void 각_항목의_가중치가_의도한_값이다() {
		Map<Result, Integer> expected = new LinkedHashMap<>();
		expected.put(new Result(id("diamond"), 10), 10);
		expected.put(new Result(id("raw_gold"), 15), 15);
		expected.put(new Result(id("raw_iron"), 13), 13);
		expected.put(new Result(id("raw_copper"), 30), 30);
		expected.put(new Result(id("coal"), 30), 30);
		expected.put(OreExchangeEffect.JACKPOT, 2);

		assertEquals(expected.size(), OreExchangeEffect.RESULTS.size(), "항목 수가 다르다");
		int index = 0;
		for (Map.Entry<Result, Integer> entry : expected.entrySet()) {
			Result actual = OreExchangeEffect.RESULTS.get(index++);
			assertEquals(entry.getKey(), actual, "표의 " + index + "번째 항목이 다르다");
			assertEquals(entry.getValue().intValue(), actual.weight());
			assertEquals(entry.getValue() / 100.0, actual.chance(), 1.0E-9);
		}
	}

	@Test
	void 잭팟은_2퍼센트다() {
		Result jackpot = OreExchangeEffect.JACKPOT;

		assertEquals(2, jackpot.weight(), "잭팟의 가중치는 2다");
		assertEquals(2, OreExchangeEffect.JACKPOT_WEIGHT);
		assertEquals(0.02, jackpot.chance(), 1.0E-9, "가중치 합이 100이라 그대로 2%다");
		assertTrue(OreExchangeEffect.RESULTS.contains(jackpot), "잭팟이 표에 들어 있어야 한다");
		assertTrue(OreExchangeEffect.isJackpot(jackpot));
	}

	@Test
	void 잭팟은_다이아몬드_128개다() {
		assertEquals(id("diamond"), OreExchangeEffect.JACKPOT.itemId());
		assertEquals(128, OreExchangeEffect.JACKPOT.count());
		assertEquals(128, OreExchangeEffect.JACKPOT_COUNT);
		assertEquals(2 * 64, OreExchangeEffect.JACKPOT_COUNT, "64 × 2 — 정확히 두 스택이다");
	}

	@Test
	void 잭팟을_뺀_나머지는_모두_1개다() {
		for (Result result : OreExchangeEffect.RESULTS) {
			if (OreExchangeEffect.isJackpot(result)) {
				continue;
			}
			assertEquals(1, result.count(), result.itemId() + " 는 1개여야 한다");
		}
	}

	@Test
	void 개수가_같은_다이아몬드_1개짜리와_잭팟은_다른_항목이다() {
		Result single = OreExchangeEffect.RESULTS.getFirst();

		assertEquals(single.itemId(), OreExchangeEffect.JACKPOT.itemId(), "아이템은 같다");
		assertNotEquals(single, OreExchangeEffect.JACKPOT, "개수가 달라 서로 다른 항목이다");
		assertFalse(OreExchangeEffect.isJackpot(single), "1개짜리 다이아몬드는 잭팟이 아니다");
	}

	@Test
	void 가중치나_개수가_0_이하면_거부한다() {
		assertThrows(IllegalArgumentException.class, () -> new Result(id("coal"), 0));
		assertThrows(IllegalArgumentException.class, () -> new Result(id("coal"), 1, 0));
	}

	// ------------------------------------------------------------------ 확률 분포

	/**
	 * 누적선의 구간이 정확히 어디서 갈리는지 못박는다.
	 *
	 * <p>표 순서대로 다이아몬드 0~9 · 금 10~24 · 철 25~37 · 구리 38~67 · 석탄 68~97 ·
	 * 잭팟 98~99 다. 특히 마지막 두 칸만 잭팟이라는 것이 「2%」의 실체다.
	 */
	@Test
	void 누적선의_경계에서_올바른_항목이_나온다() {
		assertEquals(id("diamond"), OreExchangeEffect.resultForRoll(0).itemId());
		assertEquals(id("diamond"), OreExchangeEffect.resultForRoll(9).itemId());
		assertEquals(id("raw_gold"), OreExchangeEffect.resultForRoll(10).itemId());
		assertEquals(id("raw_gold"), OreExchangeEffect.resultForRoll(24).itemId());
		assertEquals(id("raw_iron"), OreExchangeEffect.resultForRoll(25).itemId());
		assertEquals(id("raw_iron"), OreExchangeEffect.resultForRoll(37).itemId());
		assertEquals(id("raw_copper"), OreExchangeEffect.resultForRoll(38).itemId());
		assertEquals(id("raw_copper"), OreExchangeEffect.resultForRoll(67).itemId());
		assertEquals(id("coal"), OreExchangeEffect.resultForRoll(68).itemId());
		assertEquals(id("coal"), OreExchangeEffect.resultForRoll(97).itemId());

		assertSame(OreExchangeEffect.JACKPOT, OreExchangeEffect.resultForRoll(98));
		assertSame(OreExchangeEffect.JACKPOT, OreExchangeEffect.resultForRoll(99));
		assertFalse(OreExchangeEffect.isJackpot(OreExchangeEffect.resultForRoll(97)),
				"97은 아직 석탄이다");
	}

	@Test
	void 굴림값_100칸_가운데_잭팟은_정확히_두_칸이다() {
		int jackpotRolls = 0;
		for (int roll = 0; roll < OreExchangeEffect.TOTAL_WEIGHT; roll++) {
			if (OreExchangeEffect.isJackpot(OreExchangeEffect.resultForRoll(roll))) {
				jackpotRolls++;
			}
		}

		assertEquals(2, jackpotRolls, "100칸 중 두 칸 = 2%");
	}

	@Test
	void 범위를_벗어난_굴림값은_양_끝으로_자른다() {
		assertEquals(id("diamond"), OreExchangeEffect.resultForRoll(-1).itemId());
		assertSame(OreExchangeEffect.JACKPOT, OreExchangeEffect.resultForRoll(1000));
	}

	@Test
	void 결과_분포가_가중치와_거의_맞는다() {
		RandomSource random = RandomSource.create(20260901L);
		Map<Result, Integer> counts = new HashMap<>();
		int trials = 200_000;
		for (int i = 0; i < trials; i++) {
			counts.merge(OreExchangeEffect.rollResult(random), 1, Integer::sum);
		}

		for (Result expected : OreExchangeEffect.RESULTS) {
			double actualFraction = counts.getOrDefault(expected, 0) / (double) trials;
			assertTrue(Math.abs(expected.chance() - actualFraction) < 0.01,
					expected + ": 기대 " + expected.chance() + ", 실제 " + actualFraction);
		}
	}

	/** 2%는 0.01 오차로는 「안 나와도」 통과하므로, 잭팟만 따로 좁은 오차로 다시 본다. */
	@Test
	void 잭팟의_실제_출현율도_2퍼센트_언저리다() {
		RandomSource random = RandomSource.create(20260906L);
		int trials = 400_000;
		int jackpots = 0;
		for (int i = 0; i < trials; i++) {
			if (OreExchangeEffect.isJackpot(OreExchangeEffect.rollResult(random))) {
				jackpots++;
			}
		}

		double fraction = jackpots / (double) trials;
		assertTrue(Math.abs(0.02 - fraction) < 0.002, "기대 0.02, 실제 " + fraction);
	}

	@Test
	void 항상_보상표_안의_항목이_나온다() {
		RandomSource random = RandomSource.create(1L);
		for (int i = 0; i < 1000; i++) {
			Result result = OreExchangeEffect.rollResult(random);
			assertTrue(OreExchangeEffect.RESULTS.contains(result), result + " 는 RESULTS 에 없다");
		}
	}

	// ------------------------------------------------------------------ 지급

	@Test
	void 넣은_아이템의_이름을_돌려준다_AIR_가_아니다() {
		TeamState state = TeamState.fresh(20.0F);

		Component name = PerkOreExchange.grant(state, new Result(id("raw_copper"), 30));

		assertNotNull(name);
		assertEquals(new ItemStack(Items.RAW_COPPER).getHoverName().getString(), name.getString());
		assertNotEquals(ItemStack.EMPTY.getHoverName().getString(), name.getString(),
				"인벤토리에 다 들어가 묶음이 비어도 이름은 남아 있어야 한다 — 예전에는 여기서 「Air」가 나왔다");
	}

	@Test
	void 잭팟도_넣고_나서_이름이_남는다() {
		TeamState state = TeamState.fresh(20.0F);

		Component name = PerkOreExchange.grant(state, OreExchangeEffect.JACKPOT);

		assertNotNull(name);
		assertEquals(new ItemStack(Items.DIAMOND).getHoverName().getString(), name.getString());
	}

	@Test
	void 잭팟_128개는_64짜리_두_칸으로_들어간다() {
		TeamState state = TeamState.fresh(20.0F);

		PerkOreExchange.grant(state, OreExchangeEffect.JACKPOT);

		assertEquals(128, countItem(state, Items.DIAMOND), "128개가 그대로 들어가야 한다");
		assertEquals(2, filledSlots(state, Items.DIAMOND), "스택 한도가 64라 두 칸이다");
		assertTrue(state.overflowItems.isEmpty(), "자리가 넉넉하면 넘침 목록은 비어 있다");
		assertNoSlotOverStack(state);
	}

	@Test
	void 인벤토리가_꽉_차면_잭팟은_넘침_목록에_남는다() {
		TeamState state = TeamState.fresh(20.0F);
		fillWithStone(state.mainItems);
		fillWithStone(state.extraItems);

		PerkOreExchange.grant(state, OreExchangeEffect.JACKPOT);

		assertEquals(0, countItem(state, Items.DIAMOND), "들어갈 자리가 없다");
		int pending = state.overflowItems.stream()
				.filter(stack -> stack.is(Items.DIAMOND))
				.mapToInt(ItemStack::getCount)
				.sum();
		assertEquals(128, pending, "바닥에 떨어뜨리지 않고 대기열에 남긴다");
		for (ItemStack stack : state.overflowItems) {
			assertTrue(stack.getCount() <= stack.getMaxStackSize(),
					"한도를 넘긴 묶음은 저장할 때 통째로 사라진다: " + stack.getCount());
		}
	}

	@Test
	void 칸이_비면_대기하던_잭팟이_저절로_들어온다() {
		TeamState state = TeamState.fresh(20.0F);
		fillWithStone(state.mainItems);
		fillWithStone(state.extraItems);
		PerkOreExchange.grant(state, OreExchangeEffect.JACKPOT);

		state.mainItems.set(0, ItemStack.EMPTY);
		state.mainItems.set(1, ItemStack.EMPTY);
		state.restoreOverflow(false);

		assertEquals(128, countItem(state, Items.DIAMOND));
		assertTrue(state.overflowItems.isEmpty());
	}

	@Test
	void 없는_아이템은_넣지_않고_이름도_없다() {
		TeamState state = TeamState.fresh(20.0F);

		Component name = PerkOreExchange.grant(
				state, new Result(Identifier.parse("sharedfate:missing_ore"), 1));

		assertNull(name);
		assertTrue(state.overflowItems.isEmpty());
		assertTrue(state.mainItems.stream().allMatch(ItemStack::isEmpty));
	}

	// ------------------------------------------------------------------ 채팅 문구

	@Test
	void 한_개짜리_문구는_예전_그대로다() {
		Component message = PerkOreExchange.exchangeMessage(
				25, new Result(id("raw_copper"), 30), Component.literal("원석 구리"));

		assertEquals("[증강] 나무 25개를 원석 구리(으)로 바꿨습니다.", message.getString());
	}

	@Test
	void 여러_개짜리_문구는_개수를_붙인다() {
		Component message = PerkOreExchange.exchangeMessage(
				25, OreExchangeEffect.JACKPOT, Component.literal("다이아몬드"));

		assertEquals("[증강] ★잭팟★ 나무 25개를 다이아몬드 128개로 바꿨습니다.", message.getString());
	}

	@Test
	void 이름을_찾지_못하면_광물이라고_적는다() {
		Component message = PerkOreExchange.exchangeMessage(25, new Result(id("coal"), 30), null);

		assertEquals("[증강] 나무 25개를 광물(으)로 바꿨습니다.", message.getString());
	}

	// ------------------------------------------------------------------ 나무 세기 경계값

	@Test
	void 빈_인벤토리는_나무가_0개다() {
		TeamState state = TeamState.fresh(20.0F);

		assertEquals(0, PerkOreExchange.countWood(state));
	}

	@Test
	void 나무_도끼만_도구로_인정한다() {
		assertTrue(PerkOreExchange.matchesTool(new ItemStack(Items.WOODEN_AXE)));
		assertFalse(PerkOreExchange.matchesTool(new ItemStack(Items.STONE_AXE)),
				"돌도끼는 나무 도끼가 아니다");
		assertFalse(PerkOreExchange.matchesTool(new ItemStack(Items.OAK_LOG)),
				"도끼가 아닌 아이템은 대상이 아니다");
		assertFalse(PerkOreExchange.matchesTool(ItemStack.EMPTY));
		assertFalse(PerkOreExchange.matchesTool(null));
	}

	// ------------------------------------------------------------------ 거들기

	private static Identifier id(String path) {
		return Identifier.withDefaultNamespace(path);
	}

	private static void fillWithStone(SharedItemList items) {
		for (int slot = 0; slot < items.size(); slot++) {
			items.set(slot, new ItemStack(Items.STONE, 64));
		}
	}

	/** 공유 목록(본체 + 확장) 전체에서 이 아이템이 몇 개나 있는가. */
	private static int countItem(TeamState state, Item item) {
		int total = 0;
		for (ItemStack stack : state.mainItems) {
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		for (ItemStack stack : state.extraItems) {
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int filledSlots(TeamState state, Item item) {
		int slots = 0;
		for (ItemStack stack : state.mainItems) {
			if (stack.is(item) && !stack.isEmpty()) {
				slots++;
			}
		}
		for (ItemStack stack : state.extraItems) {
			if (stack.is(item) && !stack.isEmpty()) {
				slots++;
			}
		}
		return slots;
	}

	private static void assertNoSlotOverStack(TeamState state) {
		for (ItemStack stack : state.mainItems) {
			if (stack.isEmpty()) {
				continue;
			}
			assertTrue(stack.getCount() <= stack.getMaxStackSize(), "칸 하나가 한도를 넘었다");
		}
	}

	private static PerkEffect create() {
		com.google.gson.JsonObject parsed = com.google.gson.JsonParser
				.parseString("{ \"type\": \"ore_exchange\" }").getAsJsonObject();
		return PerkEffectType.ORE_EXCHANGE.create("sharedfate:테스트", 0, parsed);
	}
}
