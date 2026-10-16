package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.GatherEffect;
import com.sharedfate.perk.effect.OnKillEffect;
import com.sharedfate.perk.effect.OnSwapEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.perk.effect.StaggeredSwapEffect;
import com.sharedfate.perk.effect.SwapBlockEffect;
import com.sharedfate.perk.effect.SwapExplosionEffect;
import com.sharedfate.perk.effect.SwapIntervalEffect;
import com.sharedfate.perk.effect.SwapRallyEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.resources.Identifier;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위치 교환에 끼어드는 네 타입의 정의 읽기와 {@link PerkSwapRules} 의 판정을 본다.
 *
 * <p>실제로 자리가 바뀌는 자리({@code PositionSwapManager}) 와 끌어모으는 자리
 * ({@code TeamGathering}) 는 살아 있는 서버와 월드가 있어야 하므로 여기서는 다루지 않는다.
 * 대신 그 코드가 부르는 판정을 모두 확인한다.
 */
class SwapPerkEffectTest {

	@BeforeAll
	static void setUp() {
		// 상태이상·속성 레지스트리를 보므로 최소한의 초기화를 해 둔다.
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ swap_interval

	@Test
	void 주기_배율을_읽는다() {
		SwapIntervalEffect effect = assertInstanceOf(SwapIntervalEffect.class,
				create(PerkEffectType.SWAP_INTERVAL, "{ \"type\": \"swap_interval\", \"multiplier\": 0.5 }"));

		assertEquals(0.5, effect.multiplier(), 1.0e-9);
	}

	@Test
	void 범위를_벗어난_주기_배율은_버린다() {
		assertNull(create(PerkEffectType.SWAP_INTERVAL, "{ \"type\": \"swap_interval\" }"),
				"multiplier 는 반드시 적어야 한다");
		assertNull(create(PerkEffectType.SWAP_INTERVAL,
				"{ \"type\": \"swap_interval\", \"multiplier\": 0.0 }"),
				"0 이면 매 틱 교환이 된다");
		assertNull(create(PerkEffectType.SWAP_INTERVAL,
				"{ \"type\": \"swap_interval\", \"multiplier\": 0.05 }"));
		assertNull(create(PerkEffectType.SWAP_INTERVAL,
				"{ \"type\": \"swap_interval\", \"multiplier\": 10.5 }"));
		assertNull(create(PerkEffectType.SWAP_INTERVAL,
				"{ \"type\": \"swap_interval\", \"multiplier\": \"절반\" }"));
	}

	// ------------------------------------------------------------------ swap_explosion

	@Test
	void 세_값을_안_적으면_기본값이다() {
		SwapExplosionEffect effect = swapExplosion("{ \"type\": \"swap_explosion\" }");

		assertEquals(SwapExplosionEffect.DEFAULT_RADIUS, effect.radius(), 1.0e-6);
		assertEquals(SwapExplosionEffect.DEFAULT_DAMAGE_MULTIPLIER, effect.damageMultiplier(), 1.0e-9);
		assertEquals(SwapExplosionEffect.DEFAULT_BREAK_BLOCKS, effect.breakBlocks());
	}

	@Test
	void 세_값을_JSON에서_그대로_읽는다() {
		SwapExplosionEffect effect = swapExplosion("""
				{ "type": "swap_explosion", "radius": 2.5,
				  "damage_multiplier": 0.5, "break_blocks": false }
				""");

		assertEquals(2.5F, effect.radius(), 1.0e-6);
		assertEquals(0.5, effect.damageMultiplier(), 1.0e-9);
		assertFalse(effect.breakBlocks());
	}

	@Test
	void camelCase_로_적어도_같다() {
		SwapExplosionEffect effect = swapExplosion("""
				{ "type": "swap_explosion", "damageMultiplier": 2.0, "breakBlocks": false }
				""");

		assertEquals(2.0, effect.damageMultiplier(), 1.0e-9);
		assertFalse(effect.breakBlocks());
	}

	@Test
	void 반경이_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"radius\": 0.1 }"));
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"radius\": 100 }"));
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"radius\": \"크게\" }"));
	}

	@Test
	void 피해_배율이_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"damage_multiplier\": -1.0 }"));
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"damage_multiplier\": 11.0 }"));
	}

	@Test
	void 블록_파괴_값이_참_거짓이_아니면_버린다() {
		assertNull(create(PerkEffectType.SWAP_EXPLOSION,
				"{ \"type\": \"swap_explosion\", \"break_blocks\": \"응\" }"));
	}

	// ------------------------------------------------------------------ swap_block

	@Test
	void 교환_차단은_필드가_없고_인스턴스를_돌려쓴다() {
		PerkEffect first = create(PerkEffectType.SWAP_BLOCK, "{ \"type\": \"swap_block\" }");
		PerkEffect second = create(PerkEffectType.SWAP_BLOCK, "{ \"type\": \"swap_block\" }");

		assertSame(SwapBlockEffect.INSTANCE, first);
		assertSame(first, second);
	}

	// ------------------------------------------------------------------ staggered_swap

	@Test
	void 시차는_필드가_없고_인스턴스를_돌려쓴다() {
		PerkEffect first = create(PerkEffectType.STAGGERED_SWAP, "{ \"type\": \"staggered_swap\" }");
		PerkEffect second = create(PerkEffectType.STAGGERED_SWAP, "{ \"type\": \"staggered_swap\" }");

		assertSame(StaggeredSwapEffect.INSTANCE, first);
		assertSame(first, second);
	}

	// ------------------------------------------------------------------ swap_rally

	@Test
	void 정거장은_필드가_없고_인스턴스를_돌려쓴다() {
		PerkEffect first = create(PerkEffectType.SWAP_RALLY, "{ \"type\": \"swap_rally\" }");
		PerkEffect second = create(PerkEffectType.SWAP_RALLY, "{ \"type\": \"swap_rally\" }");

		assertSame(SwapRallyEffect.INSTANCE, first);
		assertSame(first, second);
	}

	// ------------------------------------------------------------------ on_swap

	@Test
	void 교환_시점_효과를_재귀적으로_읽는다() {
		OnSwapEffect effect = onSwap("""
				{ "type": "on_swap",
				  "effects": [
				    { "type": "status_effect", "effect": "minecraft:resistance",
				      "amplifier": 3, "duration": 3 },
				    { "type": "attribute", "attribute": "minecraft:movement_speed",
				      "operation": "add_multiplied_total", "amount": 0.2, "duration": 10 }
				  ] }
				""");

		assertEquals(2, effect.grants().size());
		StatusEffectPerk status = assertInstanceOf(
				StatusEffectPerk.class, effect.grants().getFirst().effect());
		assertEquals(Identifier.parse("minecraft:resistance"), status.effectId());
		assertEquals(3, status.amplifier(), "저항 IV 는 amplifier 3 이다");
		assertInstanceOf(AttributeEffect.class, effect.grants().get(1).effect());
	}

	@Test
	void 하위_효과마다_지속시간을_따로_준다() {
		// on_team_hurt 와 달리 형제끼리 지속시간을 나눠 쓰지 않는다. 저항 3초와 구속 5초를
		// 한 증강에 담을 수 있어야 한다.
		OnSwapEffect effect = onSwap("""
				{ "type": "on_swap",
				  "effects": [
				    { "type": "status_effect", "effect": "minecraft:blindness", "duration": 5 },
				    { "type": "status_effect", "effect": "minecraft:slowness", "duration": 2 }
				  ] }
				""");

		assertEquals(5 * OnKillEffect.TICKS_PER_SECOND, effect.grants().getFirst().durationTicks());
		assertEquals(2 * OnKillEffect.TICKS_PER_SECOND, effect.grants().get(1).durationTicks());
	}

	@Test
	void 교환_시점_효과에_duration_을_안_적으면_기본값이다() {
		OnSwapEffect effect = onSwap("""
				{ "type": "on_swap",
				  "effects": [ { "type": "status_effect", "effect": "minecraft:speed" } ] }
				""");

		assertEquals(
				(int) Math.round(OnKillEffect.DEFAULT_DURATION_SECONDS * OnKillEffect.TICKS_PER_SECOND),
				effect.grants().getFirst().durationTicks());
	}

	@Test
	void 교환_시점_효과의_effects_가_비어_있으면_버린다() {
		assertNull(create(PerkEffectType.ON_SWAP, "{ \"type\": \"on_swap\" }"));
		assertNull(create(PerkEffectType.ON_SWAP, "{ \"type\": \"on_swap\", \"effects\": [] }"));
		assertNull(create(PerkEffectType.ON_SWAP, "{ \"type\": \"on_swap\", \"effects\": 3 }"));
		assertNull(create(PerkEffectType.ON_SWAP, "{ \"type\": \"on_swap\", \"effects\": [ 3 ] }"));
	}

	@Test
	void 교환_시점의_하위_효과가_잘못되면_증강_전체를_버린다() {
		assertNull(create(PerkEffectType.ON_SWAP, """
				{ "type": "on_swap", "effects": [ { "type": "없는타입" } ] }
				"""));
		assertNull(create(PerkEffectType.ON_SWAP, """
				{ "type": "on_swap",
				  "effects": [ { "type": "status_effect", "effect": "minecraft:speed", "duration": 0 } ] }
				"""), "지속시간 0 은 '잠깐'이 될 수 없다");
		assertNull(create(PerkEffectType.ON_SWAP, """
				{ "type": "on_swap",
				  "effects": [ { "type": "status_effect", "effect": "minecraft:speed", "duration": 9999 } ] }
				"""), "너무 길면 상시나 다름없다");
		assertNull(create(PerkEffectType.ON_SWAP, """
				{ "type": "on_swap",
				  "effects": [ { "type": "status_effect", "effect": "minecraft:speed", "duration": "조금" } ] }
				"""));
	}

	@Test
	void 교환_시점_하위_효과의_순번은_최상위_순번과_겹치지_않는다() {
		OnSwapEffect effect = assertInstanceOf(OnSwapEffect.class, create(PerkEffectType.ON_SWAP, """
				{ "type": "on_swap",
				  "effects": [
				    { "type": "attribute", "attribute": "minecraft:attack_damage",
				      "operation": "add_multiplied_total", "amount": 0.1 }
				  ] }
				""", 1));

		AttributeEffect nested = assertInstanceOf(
				AttributeEffect.class, effect.grants().getFirst().effect());
		// on_kill 과 같은 규칙을 쓴다. 겹치면 뒤에 붙은 수정자가 앞의 것을 덮는다.
		assertEquals(AttributeEffect.modifierId("sharedfate:테스트", OnKillEffect.nestedIndex(1, 0)),
				nested.modifierId());
		assertNotEquals(AttributeEffect.modifierId("sharedfate:테스트", 1), nested.modifierId());
	}

	@Test
	void 교환_시점_효과는_상시로_거는_배율이_없다() {
		OnSwapEffect effect = onSwap("""
				{ "type": "on_swap",
				  "effects": [ { "type": "damage_taken", "multiplier": 0.5 } ] }
				""");

		// 시점이 오기 전에는 아무것도 걸려 있지 않아야 한다.
		assertEquals(1.0, effect.damageTakenMultiplier(), 1.0e-9);
		assertEquals(1.0, effect.damageDealtMultiplier(), 1.0e-9);
	}

	// ------------------------------------------------------------------ gather

	@Test
	void 집합_규칙_세_가지를_읽는다() {
		GatherEffect effect = gather("""
				{ "type": "gather", "distance": 64, "cooldown_ticks": 200,
				  "effects": [ { "type": "status_effect", "effect": "minecraft:blindness",
				                 "duration": 5 } ] }
				""");

		assertEquals(64.0, effect.distance(), 1.0e-9);
		assertEquals(200, effect.cooldownTicks());
		assertEquals(1, effect.grants().size());
		assertEquals(5 * OnKillEffect.TICKS_PER_SECOND, effect.grants().getFirst().durationTicks());
	}

	@Test
	void 재우는_시간을_안_적으면_기본값이고_camelCase_로_적어도_같다() {
		assertEquals(GatherEffect.DEFAULT_COOLDOWN_TICKS,
				gather("{ \"type\": \"gather\", \"distance\": 64 }").cooldownTicks());
		assertEquals(400,
				gather("{ \"type\": \"gather\", \"distance\": 64, \"cooldownTicks\": 400 }")
						.cooldownTicks());
	}

	@Test
	void 집합에_effects_는_없어도_된다() {
		// 대가 없이 모으기만 하는 정의도 뜻이 통한다.
		assertTrue(gather("{ \"type\": \"gather\", \"distance\": 100 }").grants().isEmpty());
	}

	@Test
	void 거리가_없거나_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.GATHER, "{ \"type\": \"gather\" }"));
		assertNull(create(PerkEffectType.GATHER, "{ \"type\": \"gather\", \"distance\": 1 }"),
				"너무 짧으면 붙어 있어도 계속 발동한다");
		assertNull(create(PerkEffectType.GATHER, "{ \"type\": \"gather\", \"distance\": 99999 }"));
		assertNull(create(PerkEffectType.GATHER, "{ \"type\": \"gather\", \"distance\": \"멀리\" }"));
	}

	@Test
	void 재우는_시간이_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.GATHER,
				"{ \"type\": \"gather\", \"distance\": 64, \"cooldown_ticks\": 0 }"));
		assertNull(create(PerkEffectType.GATHER,
				"{ \"type\": \"gather\", \"distance\": 64, \"cooldown_ticks\": 999999 }"));
		assertNull(create(PerkEffectType.GATHER,
				"{ \"type\": \"gather\", \"distance\": 64, \"cooldown_ticks\": \"조금\" }"));
	}

	// ------------------------------------------------------------------ 팀 판정

	@Test
	void 증강이_없으면_교환을_막지_않고_배율도_1이다() {
		assertFalse(PerkSwapRules.blocksSwap(null));
		assertFalse(PerkSwapRules.blocksSwap(TeamState.fresh(20.0F)));
		assertEquals(1.0, PerkSwapRules.intervalMultiplier(null), 1.0e-9);
		assertEquals(1.0, PerkSwapRules.intervalMultiplier(TeamState.fresh(20.0F)), 1.0e-9);
		assertTrue(PerkSwapRules.gathers(TeamState.fresh(20.0F)).isEmpty());
	}

	@Test
	void 교환을_막는_증강을_가지면_막는다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertTrue(PerkSwapRules.blocksSwap(teamWith("sharedfate:rooted")));
		assertFalse(PerkSwapRules.blocksSwap(teamWith("sharedfate:homeswap")),
				"주기만 줄이는 증강은 교환을 막지 않는다");
	}

	/**
	 * 「소집의 조각」만 교환 시점 자체를 없앤다.
	 *
	 * <p>이것이 갈라지지 않으면 「뿌리내린 발」을 가진 팀이 다른 증강의 교환 시점 효과까지 잃거나,
	 * 반대로 조각을 가진 팀이 부수효과만 주기마다 계속 받는다.
	 */
	@Test
	void 조용한_막힘은_소집의_조각만이다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertTrue(PerkSwapRules.silentSwapBlock(teamWith("sharedfate:shard")));
		assertTrue(PerkSwapRules.blocksSwap(teamWith("sharedfate:shard")),
				"조용한 막힘도 막힘이다");

		assertFalse(PerkSwapRules.silentSwapBlock(teamWith("sharedfate:rooted")),
				"뿌리내린 발은 자리만 막고 교환 시점은 계속 찾아온다");
		assertFalse(PerkSwapRules.silentSwapBlock(TeamState.fresh(20.0F)));
	}

	@Test
	void 둘_다_가지면_조용한_쪽이_이긴다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertTrue(PerkSwapRules.silentSwapBlock(
				teamWith("sharedfate:rooted", "sharedfate:shard")),
				"교환 시점이 오지 않으므로 다른 증강의 교환 시점 효과도 함께 멈춘다");
	}

	@Test
	void 조용한_막힘은_따로_만든_인스턴스다() {
		PerkEffect plain = create(PerkEffectType.SWAP_BLOCK, "{ \"type\": \"swap_block\" }");
		PerkEffect silent = create(PerkEffectType.SWAP_BLOCK,
				"{ \"type\": \"swap_block\", \"silent\": true }");

		assertSame(SwapBlockEffect.INSTANCE, plain);
		assertSame(SwapBlockEffect.SILENT, silent);
		assertFalse(((SwapBlockEffect) plain).silent());
		assertTrue(((SwapBlockEffect) silent).silent());
	}

	@Test
	void silent_가_참_거짓이_아니면_평범한_막힘이다() {
		assertSame(SwapBlockEffect.INSTANCE, create(PerkEffectType.SWAP_BLOCK,
				"{ \"type\": \"swap_block\", \"silent\": \"네\" }"),
				"정의를 버리면 자리가 안 바뀐다는 본체까지 사라져 손해가 더 크다");
		assertSame(SwapBlockEffect.INSTANCE, create(PerkEffectType.SWAP_BLOCK,
				"{ \"type\": \"swap_block\", \"silent\": false }"));
	}

	@Test
	void 주기_배율은_가진_것을_모두_곱한다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertEquals(0.5, PerkSwapRules.intervalMultiplier(teamWith("sharedfate:homeswap")), 1.0e-9);
		assertEquals(0.5 * 0.4,
				PerkSwapRules.intervalMultiplier(teamWith("sharedfate:homeswap", "sharedfate:hurry")),
				1.0e-9);
	}

	@Test
	void 풀에서_사라진_증강_id_는_건너뛴다(@TempDir Path dir) throws IOException {
		write(dir, "{ \"perks\": [] }");
		PerkRegistry.load(dir);

		TeamState state = teamWith("sharedfate:사라진것");

		assertFalse(PerkSwapRules.blocksSwap(state));
		assertEquals(1.0, PerkSwapRules.intervalMultiplier(state), 1.0e-9);
	}

	@Test
	void 집합_규칙을_모아_준다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		List<GatherEffect> gathers = PerkSwapRules.gathers(teamWith("sharedfate:bound"));

		assertEquals(1, gathers.size());
		assertEquals(64.0, gathers.getFirst().distance(), 1.0e-9);
	}

	@Test
	void 폭발_교환_정의를_모아_준다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertTrue(PerkSwapRules.swapExplosions(TeamState.fresh(20.0F)).isEmpty(),
				"증강이 없으면 폭발도 없다");
		assertTrue(PerkSwapRules.swapExplosions(teamWith("sharedfate:homeswap")).isEmpty(),
				"관련 없는 증강만 가지면 비어 있다");

		List<SwapExplosionEffect> explosions =
				PerkSwapRules.swapExplosions(teamWith("sharedfate:bomb"));
		assertEquals(1, explosions.size());
		assertEquals(3.0F, explosions.getFirst().radius(), 1.0e-6);
	}

	@Test
	void 시차_보유_여부를_판정한다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertFalse(PerkSwapRules.staggered(TeamState.fresh(20.0F)));
		assertFalse(PerkSwapRules.staggered(teamWith("sharedfate:homeswap")), "관련 없는 증강만 가지면 거짓");
		assertTrue(PerkSwapRules.staggered(teamWith("sharedfate:stagger")));
	}

	@Test
	void 정거장_보유_여부를_판정한다(@TempDir Path dir) throws IOException {
		loadPool(dir);

		assertFalse(PerkSwapRules.rallyPoint(TeamState.fresh(20.0F)));
		assertFalse(PerkSwapRules.rallyPoint(teamWith("sharedfate:homeswap")), "관련 없는 증강만 가지면 거짓");
		assertTrue(PerkSwapRules.rallyPoint(teamWith("sharedfate:rally")));
	}

	// ------------------------------------------------------------------ 남은 틱 계산

	@Test
	void 배율을_먹인_남은_틱은_주기에_곱한_값이다() {
		assertEquals(600, PerkSwapRules.scaleInterval(1200, 0.5));
		assertEquals(480, PerkSwapRules.scaleInterval(1200, 0.4));
	}

	@Test
	void 배율이_1이거나_이상하면_주기를_그대로_쓴다() {
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, 1.0));
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, 0.0));
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, -1.0));
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, Double.NaN));
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, Double.POSITIVE_INFINITY));
	}

	@Test
	void 배율을_먹여도_최소_일초는_남긴다() {
		// 0 에 가까워지면 매 틱 순간이동이 일어나 아무도 움직일 수 없다.
		assertEquals(PerkSwapRules.MIN_REMAINING_TICKS, PerkSwapRules.scaleInterval(1200, 0.001));
	}

	@Test
	void 주기보다_길게_남기지는_않는다() {
		// TeamState.sanitize 가 저장을 읽을 때 남은 틱을 주기 이하로 자른다. 여기서 넘겨 두면
		// 서버를 껐다 켜는 순간 조용히 주기로 되돌아가 재시작 전후가 달라진다.
		assertEquals(1200, PerkSwapRules.scaleInterval(1200, 2.0));
	}

	@Test
	void 교환을_쓰지_않는_팀은_남길_틱도_없다() {
		assertEquals(0, PerkSwapRules.nextRemainingTicks(null));
		assertEquals(0, PerkSwapRules.nextRemainingTicks(TeamState.fresh(20.0F)));
	}

	@Test
	void 배율_증강을_가진_팀의_다음_주기는_절반이다(@TempDir Path dir) throws IOException {
		loadPool(dir);
		TeamState state = teamWith("sharedfate:homeswap");
		state.enablePositionSwap(1);

		assertEquals(TeamState.PositionSwapLimits.TICKS_PER_MINUTE / 2,
				PerkSwapRules.nextRemainingTicks(state));
	}

	// ------------------------------------------------------------------ 도우미

	private static OnSwapEffect onSwap(String json) {
		return assertInstanceOf(OnSwapEffect.class, create(PerkEffectType.ON_SWAP, json));
	}

	private static GatherEffect gather(String json) {
		return assertInstanceOf(GatherEffect.class, create(PerkEffectType.GATHER, json));
	}

	private static SwapExplosionEffect swapExplosion(String json) {
		return assertInstanceOf(SwapExplosionEffect.class, create(PerkEffectType.SWAP_EXPLOSION, json));
	}

	private static PerkEffect create(PerkEffectType type, String json) {
		return create(type, json, 0);
	}

	private static PerkEffect create(PerkEffectType type, String json, int index) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return type.create("sharedfate:테스트", index, parsed);
	}

	/** 증강을 쓰는 팀 하나. 가진 증강 id 를 그대로 채워 넣는다. */
	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}

	private static void loadPool(Path dir) throws IOException {
		write(dir, """
				{
				  "perks": [
				    { "id": "sharedfate:homeswap", "rarity": "silver", "name": "본진이 바뀐다",
				      "effects": [
				        { "type": "swap_interval", "multiplier": 0.5 },
				        { "type": "on_swap",
				          "effects": [ { "type": "status_effect", "effect": "minecraft:resistance",
				                         "amplifier": 3, "duration": 3 } ] }
				      ] },
				    { "id": "sharedfate:hurry", "rarity": "gold", "name": "조급함",
				      "effects": [ { "type": "swap_interval", "multiplier": 0.4 } ] },
				    { "id": "sharedfate:rooted", "rarity": "prism", "name": "뿌리내린 발",
				      "effects": [
				        { "type": "swap_block" }
				      ] },
				    { "id": "sharedfate:bound", "rarity": "prism", "name": "운명 공동체",
				      "effects": [
				        { "type": "gather", "distance": 64, "cooldown_ticks": 200,
				          "effects": [ { "type": "status_effect", "effect": "minecraft:slowness",
				                         "duration": 5 } ] }
				      ] },
				    { "id": "sharedfate:bomb", "rarity": "gold", "name": "폭발 교환",
				      "effects": [
				        { "type": "swap_explosion", "radius": 3.0 }
				      ] },
				    { "id": "sharedfate:stagger", "rarity": "silver", "name": "시차",
				      "effects": [
				        { "type": "staggered_swap" }
				      ] },
				    { "id": "sharedfate:rally", "rarity": "gold", "name": "정거장",
				      "effects": [
				        { "type": "swap_rally" },
				        { "type": "on_swap",
				          "effects": [ { "type": "status_effect", "effect": "minecraft:weakness",
				                         "amplifier": 0, "duration": 15 } ] }
				      ] },
				    { "id": "sharedfate:shard", "rarity": "prism", "name": "소집의 조각",
				      "effects": [
				        { "type": "swap_block", "silent": true },
				        { "type": "rally_shard", "freeze_seconds": 3, "cooldown_seconds": 240 }
				      ] }
				  ]
				}
				""");
		PerkRegistry.load(dir);
	}

	private static void write(Path dir, String json) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), json, StandardCharsets.UTF_8);
	}
}
