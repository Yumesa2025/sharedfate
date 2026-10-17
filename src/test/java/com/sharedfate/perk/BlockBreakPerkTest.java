package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.BonusDropEffect;
import com.sharedfate.perk.effect.DropReplaceEffect;
import com.sharedfate.perk.effect.MiningSpeedEffect;
import com.sharedfate.perk.effect.OnBreakEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.team.TeamState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 블록 파괴에 걸리는 세 효과({@code bonus_drop}, {@code on_break}, {@code mining_speed})의
 * 정의 읽기와, 살아 있는 월드 없이 확인할 수 있는 계산을 본다.
 *
 * <p>실제로 블록이 부서지는 순간({@code PlayerBlockBreakEvents.AFTER} 등록, 전리품표 굴리기,
 * {@code popResource})은 서버와 월드가 있어야 하므로 여기서 다루지 않는다. 대신 그 코드가
 * 부르는 판단들({@link BlockSelector#matches}, {@link PerkBlockBreaks#multiplierFor},
 * {@link PerkBlockBreaks#allowedExtraDurability})을 모두 확인한다.
 *
 * <p>블록 <b>태그</b> 판정은 데이터팩이 올라와 있어야 실제로 걸린다. 여기서는 태그를 적은
 * 정의가 제대로 읽히고 판정이 예외 없이 지나가는 것까지만 본다.
 *
 * <p>{@code drop_replace}(「비옥한 땅」)는 여기서 <b>기본 풀에 제대로 들어갔는지</b>만 본다.
 * 그 효과 자체의 시험은 {@link FertileGroundDropReplaceTest} 에 있다.
 */
class BlockBreakPerkTest {

	@BeforeAll
	static void setUp() {
		// 블록·상태이상 레지스트리를 보므로 최소한의 초기화를 해 둔다.
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkBlockBreaks.resetForTesting();
	}

	// ------------------------------------------------------------------ bonus_drop

	@Test
	void 추가_드롭_정의를_읽는다() {
		BonusDropEffect effect = bonusDrop("""
				{ "type": "bonus_drop", "chance": 0.15, "extraDurability": 1,
				  "blocks": ["#c:ores", "minecraft:ancient_debris"] }
				""");

		assertEquals(0.15, effect.chanceFor(), 1.0e-9);
		assertEquals(1, effect.extraDurability());
		assertEquals(1, effect.blocks().tags().size(), "# 로 시작하면 태그다");
		assertEquals(1, effect.blocks().blockIds().size(), "그 밖은 블록 id 다");
		assertFalse(effect.blocks().matchesEverything());
	}

	@Test
	void 추가_내구도를_안_적으면_0_이다() {
		BonusDropEffect effect = bonusDrop("{ \"type\": \"bonus_drop\", \"chance\": 1.0 }");

		assertEquals(0, effect.extraDurability());
		assertTrue(effect.blocks().matchesEverything(), "blocks 를 안 적으면 모든 블록이다");
	}

	@Test
	void 확률이_없거나_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.BONUS_DROP, "{ \"type\": \"bonus_drop\" }"));
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 0 }"));
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 1.5 }"));
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": \"많이\" }"));
	}

	@Test
	void 추가_내구도가_음수거나_지나치면_버린다() {
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 0.5, \"extraDurability\": -1 }"));
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 0.5, \"extraDurability\": 9999 }"));
	}

	@Test
	void extra_를_안_적으면_1_이다() {
		BonusDropEffect effect = bonusDrop("{ \"type\": \"bonus_drop\", \"chance\": 0.5 }");

		assertEquals(1, effect.extra(), "예전 정의는 '하나 더'와 같아야 한다");
	}

	@Test
	void extra_로_여러_개를_확정으로_줄_수_있다() {
		// 비옥한 땅: 확률 1.0(항상) + extra 2(추가 2개) = 원래 1개 + 2개 = 3배.
		BonusDropEffect effect = bonusDrop(
				"{ \"type\": \"bonus_drop\", \"chance\": 1.0, \"extra\": 2 }");

		assertEquals(1.0, effect.chanceFor(), 1.0e-9);
		assertEquals(2, effect.extra());
	}

	@Test
	void extra_가_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 0.5, \"extra\": 0 }"));
		assertNull(create(PerkEffectType.BONUS_DROP,
				"{ \"type\": \"bonus_drop\", \"chance\": 0.5, \"extra\": 9 }"));
	}

	// ------------------------------------------------------------------ on_break

	@Test
	void 채굴_시_효과_정의를_읽는다() {
		OnBreakEffect effect = onBreak("""
				{ "type": "on_break", "durationSeconds": 3, "blocks": ["#c:ores"],
				  "effects": [ { "type": "status_effect", "effect": "minecraft:haste" } ] }
				""");

		assertEquals(1, effect.grants().size());
		OnBreakEffect.Grant grant = effect.grants().get(0);
		assertEquals(60, grant.durationTicks(), "3초는 60틱이다");
		StatusEffectPerk status = assertInstanceOf(StatusEffectPerk.class, grant.effect());
		assertEquals("minecraft:haste", status.effectId().toString());
		assertEquals(0, status.amplifier());
	}

	@Test
	void 하위_duration_이_durationSeconds_보다_우선한다() {
		OnBreakEffect effect = onBreak("""
				{ "type": "on_break", "durationSeconds": 3,
				  "effects": [
				    { "type": "status_effect", "effect": "minecraft:haste", "duration": 10 },
				    { "type": "status_effect", "effect": "minecraft:speed" }
				  ] }
				""");

		assertEquals(200, effect.grants().get(0).durationTicks(), "직접 적은 10초가 쓰인다");
		assertEquals(60, effect.grants().get(1).durationTicks(), "안 적으면 durationSeconds 다");
	}

	@Test
	void 지속시간을_아무_데도_안_적으면_기본값이다() {
		OnBreakEffect effect = onBreak("""
				{ "type": "on_break",
				  "effects": [ { "type": "status_effect", "effect": "minecraft:haste" } ] }
				""");

		assertEquals((int) (OnBreakEffect.DEFAULT_DURATION_SECONDS * 20),
				effect.grants().get(0).durationTicks());
	}

	@Test
	void 아무것도_주지_않는_정의는_버린다() {
		assertNull(create(PerkEffectType.ON_BREAK, "{ \"type\": \"on_break\" }"));
		assertNull(create(PerkEffectType.ON_BREAK,
				"{ \"type\": \"on_break\", \"effects\": [] }"));
	}

	@Test
	void 하위_효과가_하나라도_잘못되면_통째로_버린다() {
		assertNull(create(PerkEffectType.ON_BREAK, """
				{ "type": "on_break", "effects": [ { "type": "없는타입" } ] }
				"""));
		assertNull(create(PerkEffectType.ON_BREAK, """
				{ "type": "on_break", "effects": [
				    { "type": "status_effect", "effect": "minecraft:haste" },
				    { "type": "status_effect" }
				  ] }
				"""));
	}

	@Test
	void 지속시간이_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.ON_BREAK, """
				{ "type": "on_break", "durationSeconds": 0,
				  "effects": [ { "type": "status_effect", "effect": "minecraft:haste" } ] }
				"""));
		assertNull(create(PerkEffectType.ON_BREAK, """
				{ "type": "on_break", "durationSeconds": 99999,
				  "effects": [ { "type": "status_effect", "effect": "minecraft:haste" } ] }
				"""));
	}

	// ------------------------------------------------------------------ mining_speed

	@Test
	void 채굴_속도_정의를_읽는다() {
		MiningSpeedEffect effect = miningSpeed("""
				{ "type": "mining_speed", "multiplier": 0.7,
				  "blocks": ["#minecraft:base_stone_overworld", "minecraft:dirt"] }
				""");

		assertEquals(0.7, effect.multiplierFor(), 1.0e-9);
		assertTrue(effect.appliesTo(state(Blocks.DIRT)));
		assertFalse(effect.appliesTo(state(Blocks.IRON_ORE)));
	}

	@Test
	void 배율이_없거나_범위를_벗어나면_버린다() {
		assertNull(create(PerkEffectType.MINING_SPEED, "{ \"type\": \"mining_speed\" }"));
		assertNull(create(PerkEffectType.MINING_SPEED,
				"{ \"type\": \"mining_speed\", \"multiplier\": 0 }"));
		assertNull(create(PerkEffectType.MINING_SPEED,
				"{ \"type\": \"mining_speed\", \"multiplier\": -1.0 }"));
		assertNull(create(PerkEffectType.MINING_SPEED,
				"{ \"type\": \"mining_speed\", \"multiplier\": 999.0 }"));
	}

	// ------------------------------------------------------------------ 블록 목록

	@Test
	void 블록_id_로_고른다() {
		BlockSelector selector = selector("[\"minecraft:stone\", \"minecraft:dirt\"]");

		assertTrue(selector.matches(state(Blocks.STONE)));
		assertTrue(selector.matches(state(Blocks.DIRT)));
		assertFalse(selector.matches(state(Blocks.GRASS_BLOCK)));
		assertFalse(selector.matches(null));
	}

	@Test
	void 없는_블록_이름은_조용히_지나간다() {
		BlockSelector selector = selector("[\"sharedfate:no_such_block\", \"minecraft:stone\"]");

		assertTrue(selector.matches(state(Blocks.STONE)), "나머지는 그대로 걸린다");
		assertFalse(selector.matches(state(Blocks.DIRT)));
	}

	@Test
	void 없는_태그를_적어도_예외가_나지_않는다() {
		BlockSelector selector = selector("[\"#sharedfate:no_such_tag\"]");

		assertEquals(1, selector.tags().size());
		assertFalse(selector.matches(state(Blocks.STONE)), "걸리는 블록이 없을 뿐이다");
	}

	@Test
	void blocks_를_안_적으면_모든_블록이다() {
		JsonObject json = JsonParser.parseString("{ \"type\": \"mining_speed\" }").getAsJsonObject();
		BlockSelector selector = BlockSelector.fromJson("sharedfate:테스트", "mining_speed", json);

		assertSame(BlockSelector.ALL, selector);
		assertTrue(selector.matches(state(Blocks.STONE)));
		assertTrue(selector.matches(state(Blocks.IRON_ORE)));
	}

	@Test
	void 빈_blocks_는_오타로_본다() {
		assertNull(selector("[]"), "빈 배열은 정의를 버린다");
		assertNull(selector("\"돌\""), "배열이 아니어도 버린다");
		assertNull(selector("[\"기호가 없는 이름\"]"), "이름을 못 읽으면 버린다");
	}

	// ------------------------------------------------------------------ 채굴 속도 계산

	@Test
	void 증강이_없으면_원래_속도_그대로다() {
		BlockState stone = state(Blocks.STONE);

		assertEquals(1.0, PerkBlockBreaks.multiplierFor((TeamState) null, stone));
		assertEquals(1.0, PerkBlockBreaks.multiplierFor(team(), stone), "보유 증강이 비었다");
		assertEquals(5.0F, PerkBlockBreaks.scaleDestroySpeed(null, stone, 5.0F));
	}

	@Test
	void 걸리는_블록만_느려진다(@TempDir Path dir) throws IOException {
		loadRegistry(dir, """
				{ "perks": [ {
				  "id": "sharedfate:광맥", "name": "광맥", "description": "설명", "rarity": "silver",
				  "effects": [ { "type": "mining_speed", "multiplier": 0.5,
				                 "blocks": ["minecraft:stone"] } ]
				} ] }
				""");
		TeamState state = team("sharedfate:광맥");

		assertEquals(0.5, PerkBlockBreaks.multiplierFor(state, state(Blocks.STONE)), 1.0e-9);
		assertEquals(1.0, PerkBlockBreaks.multiplierFor(state, state(Blocks.DIRT)), 1.0e-9);
	}

	@Test
	void 여러_증강의_배율은_곱해진다(@TempDir Path dir) throws IOException {
		loadRegistry(dir, """
				{ "perks": [
				  { "id": "sharedfate:하나", "name": "하나", "description": "설명", "rarity": "silver",
				    "effects": [ { "type": "mining_speed", "multiplier": 0.5,
				                   "blocks": ["minecraft:stone"] } ] },
				  { "id": "sharedfate:둘", "name": "둘", "description": "설명", "rarity": "silver",
				    "effects": [ { "type": "mining_speed", "multiplier": 0.5,
				                   "blocks": ["minecraft:stone"] } ] }
				] }
				""");
		TeamState state = team("sharedfate:하나", "sharedfate:둘");

		assertEquals(0.25, PerkBlockBreaks.multiplierFor(state, state(Blocks.STONE)), 1.0e-9);
	}

	@Test
	void 증강을_꺼_두면_적용되지_않는다(@TempDir Path dir) throws IOException {
		loadRegistry(dir, """
				{ "perks": [ {
				  "id": "sharedfate:광맥", "name": "광맥", "description": "설명", "rarity": "silver",
				  "effects": [ { "type": "mining_speed", "multiplier": 0.5,
				                 "blocks": ["minecraft:stone"] } ]
				} ] }
				""");
		TeamState state = team("sharedfate:광맥");
		state.perksEnabled = false;

		assertEquals(1.0, PerkBlockBreaks.multiplierFor(state, state(Blocks.STONE)));
	}

	// ------------------------------------------------------------------ 추가 내구도

	@Test
	void 추가_내구도는_도구를_부러뜨리지_않는다() {
		assertEquals(1, PerkBlockBreaks.allowedExtraDurability(1, 100), "여유가 있으면 그대로");
		assertEquals(3, PerkBlockBreaks.allowedExtraDurability(3, 5));
		assertEquals(2, PerkBlockBreaks.allowedExtraDurability(5, 3), "1 은 남긴다");
		assertEquals(0, PerkBlockBreaks.allowedExtraDurability(5, 1), "마지막 1 은 건드리지 않는다");
		assertEquals(0, PerkBlockBreaks.allowedExtraDurability(5, 0));
		assertEquals(0, PerkBlockBreaks.allowedExtraDurability(0, 100));
		assertEquals(0, PerkBlockBreaks.allowedExtraDurability(-1, 100));
	}

	// ------------------------------------------------------------------ 증강 풀 통합

	@Test
	void 기본_풀의_두_증강이_읽힌다() {
		// 모드 안의 기본 풀을 그대로 읽는다. 설정 폴더는 보지 않는다.
		PerkRegistry.loadBundled();

		Perk greedy = PerkRegistry.byId("sharedfate:greedy_pickaxe").orElseThrow();
		assertEquals("욕심 많은 곡괭이", greedy.name());
		assertEquals(1, greedy.effects().size());
		BonusDropEffect bonus = assertInstanceOf(BonusDropEffect.class, greedy.effects().get(0));
		assertEquals(0.3, bonus.chanceFor(), 1.0e-9);
		assertEquals(1, bonus.extraDurability());
		assertTrue(bonus.appliesTo(state(Blocks.ANCIENT_DEBRIS)), "직접 적은 블록은 태그 없이도 걸린다");

		// 비옥한 땅은 덤을 얹는 것이 아니라 갈아 끼운다 — 밀이 사라지고 황금 당근이 나온다.
		Perk fertile = PerkRegistry.byId("sharedfate:fertile_ground").orElseThrow();
		DropReplaceEffect fertileReplace =
				assertInstanceOf(DropReplaceEffect.class, fertile.effects().get(0));
		assertEquals("minecraft:wheat", fertileReplace.fromId().toString());
		assertEquals("minecraft:golden_carrot", fertileReplace.itemId().toString());
		assertEquals(1, fertileReplace.min());
		assertEquals(3, fertileReplace.max());
		assertTrue(fertileReplace.appliesTo(state(Blocks.WHEAT)));
		assertFalse(fertileReplace.appliesTo(state(Blocks.CARROTS)), "다른 작물은 그대로다");

		// 실제로 캘 때 이 효과를 찾아내는 길. 팀이 그 증강을 가졌을 때만 걸린다.
		TeamState owner = team("sharedfate:fertile_ground");
		PerkBlockBreaks.DropReplaceMatch match =
				PerkBlockBreaks.dropReplaceFor(owner, state(Blocks.WHEAT));
		assertNotNull(match, "보유 증강에서 찾아내야 한다");
		assertEquals("비옥한 땅", match.sourceName());
		assertNull(PerkBlockBreaks.dropReplaceFor(owner, state(Blocks.CARROTS)));
		assertNull(PerkBlockBreaks.dropReplaceFor(team(), state(Blocks.WHEAT)),
				"증강이 없으면 아무것도 걸리지 않는다");

		Perk vein = PerkRegistry.byId("sharedfate:vein_sense").orElseThrow();
		assertEquals("광맥 감각", vein.name());
		assertEquals(2, vein.effects().size());
		assertTrue(vein.effects().stream().noneMatch(e -> e instanceof OnBreakEffect),
				"더는 캘 때마다 발동하지 않는다");
		MiningSpeedEffect mining = assertInstanceOf(MiningSpeedEffect.class, vein.effects().get(1));
		assertEquals(0.7, mining.multiplierFor(), 1.0e-9);
		assertTrue(mining.appliesTo(state(Blocks.GRASS_BLOCK)));
		assertFalse(mining.appliesTo(state(Blocks.STONE)), "돌은 이제 느려지지 않는다");
		assertFalse(mining.appliesTo(state(Blocks.IRON_ORE)));
	}

	// ------------------------------------------------------------------ 도우미

	private static BlockState state(net.minecraft.world.level.block.Block block) {
		return block.defaultBlockState();
	}

	private static TeamState team(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		for (String perkId : perkIds) {
			state.ownedPerks.add(perkId);
		}
		return state;
	}

	private static BlockSelector selector(String blocksJson) {
		JsonObject json = JsonParser.parseString("{ \"blocks\": " + blocksJson + " }")
				.getAsJsonObject();
		return BlockSelector.fromJson("sharedfate:테스트", "테스트", json);
	}

	private static BonusDropEffect bonusDrop(String json) {
		return assertInstanceOf(BonusDropEffect.class, create(PerkEffectType.BONUS_DROP, json));
	}

	private static OnBreakEffect onBreak(String json) {
		return assertInstanceOf(OnBreakEffect.class, create(PerkEffectType.ON_BREAK, json));
	}

	private static MiningSpeedEffect miningSpeed(String json) {
		return assertInstanceOf(MiningSpeedEffect.class, create(PerkEffectType.MINING_SPEED, json));
	}

	private static PerkEffect create(PerkEffectType type, String json) {
		return type.create("sharedfate:테스트", 0, JsonParser.parseString(json).getAsJsonObject());
	}

	private static void loadRegistry(Path dir, String json) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), json, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
	}
}
