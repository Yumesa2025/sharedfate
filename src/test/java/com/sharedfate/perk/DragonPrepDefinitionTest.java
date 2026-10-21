package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.DragonPrepEffect;
import com.sharedfate.perk.effect.DragonPrepEffect.Kind;
import com.sharedfate.sync.DragonTrialSession;
import com.sharedfate.sync.TrialCatalog;
import com.sharedfate.team.TeamState;
import com.sharedfate.ui.DragonPrepStyle;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤전 대비」 열두 장의 정의와 효과 판정(0.44.0-dev). 월드 없이 볼 수 있는 데까지 본다 —
 * 실제로 게임에서 걸리는지는 믹스인·실행기 자리라 사람이 확인해야 한다(대상 서술자는
 * {@code DragonPrepTargetTest}).
 */
class DragonPrepDefinitionTest {
	private static final Map<String, Kind> EXPECTED = Map.ofEntries(
			Map.entry("sharedfate:undying_oath", Kind.UNDYING_OATH),
			Map.entry("sharedfate:floor_sense", Kind.FLOOR_SENSE),
			Map.entry("sharedfate:unworn_armor", Kind.UNWORN_ARMOR),
			Map.entry("sharedfate:ender_kinship", Kind.ENDER_KINSHIP),
			Map.entry("sharedfate:anchor", Kind.ANCHOR),
			Map.entry("sharedfate:first_seal", Kind.FIRST_SEAL),
			Map.entry("sharedfate:landing_ambush", Kind.LANDING_AMBUSH),
			Map.entry("sharedfate:sky_archer", Kind.SKY_ARCHER),
			Map.entry("sharedfate:endless_quiver", Kind.ENDLESS_QUIVER),
			Map.entry("sharedfate:wing_break", Kind.WING_BREAK),
			Map.entry("sharedfate:end_adaptation", Kind.END_ADAPTATION),
			Map.entry("sharedfate:last_rally", Kind.LAST_RALLY));

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}

	private static DragonPrepEffect effectOf(String id) {
		Perk perk = PerkRegistry.byId(id).orElseThrow();
		assertEquals(1, perk.effects().size(), id);
		return assertInstanceOf(DragonPrepEffect.class, perk.effects().getFirst(), id);
	}

	// ------------------------------------------------------------------ 정의 파일

	@Test
	void 열두_장이_새_분류로_읽힌다() {
		PerkRegistry.loadBundled();
		List<Perk> trial = PerkRegistry.all().stream().filter(p -> p.rarity() == PerkRarity.TRIAL).toList();
		assertEquals(12, trial.size());
		for (Map.Entry<String, Kind> entry : EXPECTED.entrySet()) {
			Perk perk = PerkRegistry.byId(entry.getKey()).orElseThrow(() -> new AssertionError(entry.getKey()));
			assertEquals(PerkRarity.TRIAL, perk.rarity(), perk.name());
			assertTrue(perk.setTypes().isEmpty(), perk.name() + " 에 세트 유형");
			assertNotNull(perk.icon(), perk.name() + " 아이콘");
			assertEquals(entry.getValue(), effectOf(entry.getKey()).kind(), perk.name());
		}
	}

	@Test
	void 가중치는_불사의_맹세와_첫_봉인만_반이다() {
		PerkRegistry.loadBundled();
		for (Perk perk : PerkRegistry.all()) {
			double expected = perk.id().equals("sharedfate:undying_oath")
					|| perk.id().equals("sharedfate:first_seal") ? 0.5 : 1.0;
			assertEquals(expected, perk.weight(), 1.0E-12, perk.name());
		}
	}

	@Test
	void 값이_사양과_같다() {
		PerkRegistry.loadBundled();
		assertEquals(0.5, effectOf("sharedfate:undying_oath").value(), 1.0E-12);
		assertEquals(0.8, effectOf("sharedfate:floor_sense").value(), 1.0E-12);
		assertEquals(0.8, effectOf("sharedfate:anchor").value(), 1.0E-12);
		assertEquals(1.15, effectOf("sharedfate:landing_ambush").value(), 1.0E-12);
		assertEquals(1.3, effectOf("sharedfate:sky_archer").value(), 1.0E-12);
		assertEquals(10.0, effectOf("sharedfate:wing_break").value(), 1.0E-12);
		assertEquals(6.0, effectOf("sharedfate:end_adaptation").value(), 1.0E-12);
		assertEquals(12.0, effectOf("sharedfate:last_rally").value(), 1.0E-12);
	}

	@Test
	void 분류_이름과_색이_한_곳에서_온다() {
		assertEquals("드래곤전 대비", PerkRarity.TRIAL.displayName());
		assertEquals(DragonPrepStyle.LABEL, PerkRarity.TRIAL.displayName());
		assertEquals("trial", PerkRarity.TRIAL.id());
		assertEquals(PerkRarity.TRIAL, PerkRarity.fromId("trial"));
		assertFalse(PerkRarity.TRIAL.isGrade());
		assertEquals(List.of(PerkRarity.SILVER, PerkRarity.GOLD, PerkRarity.PRISM), PerkRarity.GRADES);
		assertEquals("드래곤전 대비", PerkRarity.TRIAL.gradeLabel());
		assertEquals("실버 등급", PerkRarity.SILVER.gradeLabel());
		assertEquals(0xFFC79BFF, DragonPrepStyle.COLOR);
	}

	// ------------------------------------------------------------------ 정의 읽기의 범위 검사

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void 가중치는_0_초과_100_이하만_받는다() {
		assertEquals(1.0, PerkRegistry.parseWeight("t", json("{}")), 0.0);
		assertEquals(0.5, PerkRegistry.parseWeight("t", json("{\"weight\": 0.5}")), 0.0);
		assertEquals(100.0, PerkRegistry.parseWeight("t", json("{\"weight\": 100}")), 0.0);
		for (String bad : new String[] {"0", "-1", "100.5", "\"반\"", "true"}) {
			assertTrue(Double.isNaN(PerkRegistry.parseWeight("t", json("{\"weight\": " + bad + "}"))), bad);
		}
	}

	@Test
	void 효과_값이_범위_밖이면_증강을_버린다() {
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"없는 종류\"}")));
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"floor_sense\"}")), "값 빠짐");
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"floor_sense\", \"multiplier\": 0}")));
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"floor_sense\", \"multiplier\": 1.5}")));
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"sky_archer\", \"multiplier\": 0.9}")));
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"last_rally\", \"amount\": 10}")),
				"흡수는 4 의 배수");
		assertNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"wing_break\", \"hold_seconds\": 0}")));
		assertNotNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"last_rally\", \"amount\": 12}")));
		assertNotNull(DragonPrepEffect.fromJson("t", 0, json("{\"kind\": \"UNWORN_ARMOR\"}")));
	}

	@Test
	void 정의_파일에서_버려지고_세트가_떼어진다(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
			throws java.io.IOException {
		java.nio.file.Path file = dir.resolve(PerkRegistry.FILE_NAME);
		java.nio.file.Files.writeString(file, """
				{ "perks": [
				  { "id": "t:ok", "rarity": "trial", "weight": 0.5, "set_types": ["mining"],
				    "effects": [ { "type": "dragon_prep", "kind": "first_seal" } ] },
				  { "id": "t:zero", "rarity": "trial", "weight": 0,
				    "effects": [ { "type": "dragon_prep", "kind": "first_seal" } ] },
				  { "id": "t:bad_value", "rarity": "trial",
				    "effects": [ { "type": "dragon_prep", "kind": "anchor", "multiplier": 2 } ] },
				  { "id": "t:silver", "rarity": "silver", "weight": 2,
				    "effects": [ { "type": "dragon_prep", "kind": "unworn_armor" } ] }
				] }
				""");
		PerkRegistry.load(dir);
		assertEquals(Set.of("t:ok", "t:silver"),
				Set.copyOf(PerkRegistry.all().stream().map(Perk::id).toList()));
		Perk ok = PerkRegistry.byId("t:ok").orElseThrow();
		assertTrue(ok.setTypes().isEmpty(), "드래곤전 대비에 붙은 세트 유형은 떼어 낸다");
		assertEquals(0.5, ok.weight(), 0.0);
		// 가중치는 일반 풀에도 쓸 수 있다.
		assertEquals(2.0, PerkRegistry.byId("t:silver").orElseThrow().weight(), 0.0);
	}

	@Test
	void 덤_지급_효과는_드래곤전_대비를_받지_않는다() {
		assertNull(com.sharedfate.perk.effect.RarityGrantEffect.fromJson("t", 0,
				json("{\"rarity\": \"trial\", \"count\": 1}")));
		assertNull(com.sharedfate.perk.effect.RarityRerollEffect.fromJson("t", 0,
				json("{\"rarity\": \"trial\"}")));
		assertNotNull(com.sharedfate.perk.effect.RarityGrantEffect.fromJson("t", 0,
				json("{\"rarity\": \"gold\", \"count\": 1}")));
	}

	// ------------------------------------------------------------------ 판정 — 1. 불사의 맹세 · 11. 엔드 적응

	@Test
	void 맹세는_쓴_뒤에만_최대_체력을_반으로_한다() {
		PerkRegistry.loadBundled();
		TeamState state = teamWith("sharedfate:undying_oath");
		state.baseMaxHealth = 20.0F;
		assertTrue(DragonPrep.oathAvailable(state));
		assertEquals(1.0, DragonPrep.oathScale(state), 0.0);
		assertEquals(20.0F, PerkHealthRules.effectiveMaxHealth(state), 1.0E-6F);

		state.undyingOathSpent = true;
		assertFalse(DragonPrep.oathAvailable(state), "회차에 한 번");
		assertEquals(0.5, DragonPrep.oathScale(state), 0.0);
		assertEquals(10.0F, PerkHealthRules.effectiveMaxHealth(state), 1.0E-6F);
	}

	@Test
	void 맹세가_없으면_막지_않는다() {
		PerkRegistry.loadBundled();
		assertFalse(DragonPrep.oathAvailable(teamWith("sharedfate:anchor")));
		TeamState off = teamWith("sharedfate:undying_oath");
		off.perksEnabled = false;
		assertFalse(DragonPrep.oathAvailable(off), "증강을 끈 팀");
		assertFalse(DragonPrep.tryUndyingOath(null, null, off, List.of()));
	}

	@Test
	void 엔드_적응은_엔드에_있을_때만_팀에_한_번_더한다() {
		PerkRegistry.loadBundled();
		TeamState state = teamWith("sharedfate:end_adaptation");
		state.baseMaxHealth = 20.0F;
		assertEquals(0.0, DragonPrep.endBonus(state), 0.0);
		assertEquals(20.0F, PerkHealthRules.effectiveMaxHealth(state), 1.0E-6F);
		state.presentInEnd = true;
		assertEquals(6.0, DragonPrep.endBonus(state), 0.0);
		assertEquals(26.0F, PerkHealthRules.effectiveMaxHealth(state), 1.0E-6F);
	}

	@Test
	void 맹세의_반은_엔드_보너스까지_더한_뒤에_건다() {
		PerkRegistry.loadBundled();
		TeamState state = teamWith("sharedfate:end_adaptation", "sharedfate:undying_oath");
		state.baseMaxHealth = 20.0F;
		state.presentInEnd = true;
		state.undyingOathSpent = true;
		assertEquals(13.0F, PerkHealthRules.effectiveMaxHealth(state), 1.0E-6F);
	}

	// ------------------------------------------------------------------ 2·5. 바닥 감각 · 닻

	@Test
	void 바닥_감각과_닻의_값() {
		PerkRegistry.loadBundled();
		assertEquals(8.0F, DragonPrep.scaled(10.0F, 0.8), 1.0E-6F);
		assertEquals(23.0F * 0.8F, DragonPrep.scaled(23.0F, 0.8), 1.0E-4F);
		assertEquals(10.0F, DragonPrep.scaled(10.0F, Double.NaN), 0.0F);
		assertEquals(0.8, DragonPrep.value(teamWith("sharedfate:floor_sense"), Kind.FLOOR_SENSE, 1.0), 0.0);
		assertEquals(0.8, DragonPrep.value(teamWith("sharedfate:anchor"), Kind.ANCHOR, 1.0), 0.0);
		assertEquals(1.0, DragonPrep.value(teamWith("sharedfate:sky_archer"), Kind.ANCHOR, 1.0), 0.0);
		// 증강이 없는 사람(팀 없음)은 그대로 맞는다.
		assertEquals(35.0F, DragonPrep.floorStrike(null, 35.0F), 0.0F);
		assertEquals(1.0, DragonPrep.knockbackScale(null), 0.0);
	}

	// ------------------------------------------------------------------ 3. 무뎌지지 않는 갑주

	@Test
	void 방패는_닳지_않고_칼은_닳는다() {
		PerkRegistry.loadBundled();
		TeamState state = teamWith("sharedfate:unworn_armor");
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.SHIELD)));
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.DIAMOND_CHESTPLATE)));
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.NETHERITE_HELMET)));
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.IRON_LEGGINGS)));
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.LEATHER_BOOTS)));
		assertTrue(DragonPrep.keepsDurability(state, new ItemStack(Items.TURTLE_HELMET)));
		assertFalse(DragonPrep.keepsDurability(state, new ItemStack(Items.BOW)));
		assertFalse(DragonPrep.keepsDurability(state, new ItemStack(Items.WOLF_ARMOR)), "동물 갑옷은 빠진다");
		assertFalse(DragonPrep.keepsDurability(state, new ItemStack(Items.DIAMOND_SWORD)));
		assertFalse(DragonPrep.keepsDurability(state, new ItemStack(Items.ELYTRA)), "겉날개는 방어구가 아니다");
		assertFalse(DragonPrep.keepsDurability(teamWith(), new ItemStack(Items.SHIELD)));
		assertFalse(DragonPrep.keepsDurability(state, ItemStack.EMPTY));
	}

	// ------------------------------------------------------------------ 6. 첫 봉인

	@Test
	void 첫_봉인은_자리를_세되_줄에_올리지_않는다() {
		PerkRegistry.loadBundled();
		assertTrue(DragonPrep.sealsFirstCrystal(teamWith("sharedfate:first_seal")));
		assertFalse(DragonPrep.sealsFirstCrystal(teamWith("sharedfate:anchor")));

		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 0L, true);
		assertTrue(session.seal(TrialCatalog.Trigger.FIRST_CRYSTAL));
		assertTrue(session.fired().contains(TrialCatalog.Trigger.FIRST_CRYSTAL));
		assertEquals(0, session.queuedCount(), "룰렛 줄에 안 올라간다");
		assertFalse(session.shouldOfferTrial());
		assertFalse(session.fire(TrialCatalog.Trigger.FIRST_CRYSTAL), "봉인한 자리는 다시 안 터진다");
		assertFalse(session.seal(TrialCatalog.Trigger.FIRST_CRYSTAL));
		// 다른 자리는 그대로 터진다.
		assertTrue(session.fire(TrialCatalog.Trigger.ALL_CRYSTALS));
		assertEquals(1, session.queuedCount());

		DragonTrialSession vanilla = new DragonTrialSession(UUID.randomUUID(), 0L, false);
		assertFalse(vanilla.seal(TrialCatalog.Trigger.FIRST_CRYSTAL), "시련을 끈 팀은 자리를 세지 않는다");
	}

	@Test
	void 봉인한_자리는_저장_목록에_남는다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 0L, true);
		session.seal(TrialCatalog.Trigger.FIRST_CRYSTAL);
		assertTrue(session.firedNames().contains(TrialCatalog.Trigger.FIRST_CRYSTAL.name()));
		assertTrue(session.queuedNames().isEmpty());
	}

	// ------------------------------------------------------------------ 7·8. 착지 노리기 · 하늘 사수

	@Test
	void 드래곤_피해_배율의_갈래() {
		// fighting, perched, melee, projectile
		assertEquals(1.15, DragonPrep.dragonHitFactor(true, true, true, false, 1.15, 1.3), 0.0);
		assertEquals(1.0, DragonPrep.dragonHitFactor(true, true, false, true, 1.15, 1.3), 0.0,
				"앉은 드래곤은 원거리 면역이라 하늘 사수도 없다");
		assertEquals(1.3, DragonPrep.dragonHitFactor(true, false, false, true, 1.15, 1.3), 0.0);
		assertEquals(1.0, DragonPrep.dragonHitFactor(true, false, true, false, 1.15, 1.3), 0.0,
				"날고 있는 드래곤에게 근접은 그대로");
		assertEquals(1.0, DragonPrep.dragonHitFactor(false, true, true, false, 1.15, 1.3), 0.0,
				"시련 전투가 아니면 없다");
		assertEquals(1.0, DragonPrep.dragonHitFactor(false, false, false, true, 1.15, 1.3), 0.0);
		assertEquals(1.0, DragonPrep.dragonHitFactor(null, null), 0.0);
	}

	@Test
	void 시련_전투_중은_켠_세션이_멈추지_않았을_때다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 0L, true);
		assertTrue(DragonPrep.fighting(session));
		session.suspend(false, 100.0F, 0, 10L);
		assertFalse(DragonPrep.fighting(session), "일시 중단 중");
		assertFalse(DragonPrep.fighting(new DragonTrialSession(UUID.randomUUID(), 0L, false)));
		assertFalse(DragonPrep.fighting(null));
	}

	// ------------------------------------------------------------------ 9. 마르지 않는 화살통

	@Test
	void 화살통은_사람이_없으면_아무것도_안_한다() {
		assertFalse(DragonPrep.quiverActive(null));
		assertFalse(DragonPrep.endlessArrow(null, new ItemStack(Items.ARROW)));
		assertFalse(DragonPrep.endlessArrow(null, null));
	}

	// ------------------------------------------------------------------ 10. 날개 꺾기

	@Test
	void 날개_꺾기는_팔초를_십초로_늘리고_줄이지는_않는다() {
		assertEquals(200, DragonPrep.perchHoldTicks(160, 10.0));
		assertEquals(160, DragonPrep.perchHoldTicks(160, 0.0));
		assertEquals(160, DragonPrep.perchHoldTicks(160, 5.0), "짧게 만들지 않는다");
		assertEquals(160, DragonPrep.perchHoldTicks(null, 160));
		assertEquals(160, DragonPrep.perchHoldTicks(List.of(), 160));
	}

	// ------------------------------------------------------------------ 12. 결전의 함성

	@Test
	void 함성의_흡수는_상태이상_한_단계가_사다() {
		assertEquals(2, DragonPrep.rallyAmplifier(12.0), "흡수 III = 12");
		assertEquals(0, DragonPrep.rallyAmplifier(4.0));
		assertEquals(-1, DragonPrep.rallyAmplifier(0.0));
		assertEquals(-1, DragonPrep.rallyAmplifier(3.0));
		// 팀이 없거나 사람이 없으면 아무 일도 없다(예외 없이).
		DragonPrep.onLastStandBegins(null, null, null);
	}

	@Test
	void 함성의_흡수는_1분_30초다() {
		// 사람 결정(2026-10-22) — 3분에서 1분 30초로.
		assertEquals(20 * 90, DragonPrep.RALLY_DURATION_TICKS);
	}

	@Test
	void 바닥_감각은_공허_브레스와_공허_흡입을_줄이지_않는다() throws java.io.IOException {
		// 사람 결정(2026-10-22) — 대상은 연쇄 포격 · 자리 폭격 · 낙뢰 · 종말의 비 · 상시 번개 · 십자 균열.
		String patterns = java.nio.file.Files.readString(java.nio.file.Path.of(
				"src/main/java/com/sharedfate/sync/DragonLastStandPatterns.java"));
		assertTrue(patterns.contains("floorStrike(member, DragonLastStand.CROSS_FISSURE_DAMAGE)"), "십자 균열");
		assertTrue(patterns.contains("floorStrike(member, DragonLastStand.LIGHTNING_DAMAGE)"), "상시 번개");
		assertFalse(patterns.contains("floorStrike(member, DragonLastStand.CONE_BREATH_DAMAGE)"), "공허 브레스");
		assertFalse(patterns.contains("floorStrike(member, SUCK_FIRE_DAMAGE)"), "공허 흡입 불 결계");
		assertFalse(patterns.contains("floorStrike(member, DragonLastStand.VOID_SUCTION_DAMAGE)"), "공허 흡입 터짐");
		for (String file : new String[] {"DragonFireBarrage", "TrialRisks", "TrialEndRain"}) {
			assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(
					"src/main/java/com/sharedfate/sync/" + file + ".java")).contains("DragonPrep.floorStrike("), file);
		}
	}

	// ------------------------------------------------------------------ 4. 엔더 동화 · 찾기

	@Test
	void 증강을_끈_팀은_아무_효과도_없다() {
		PerkRegistry.loadBundled();
		TeamState state = teamWith(EXPECTED.keySet().toArray(String[]::new));
		Set<Kind> found = EnumSet.noneOf(Kind.class);
		for (Kind kind : Kind.values()) {
			if (DragonPrep.held(state, kind)) {
				found.add(kind);
			}
		}
		assertEquals(EnumSet.allOf(Kind.class), found, "열두 종류가 모두 찾아진다");
		state.perksEnabled = false;
		for (Kind kind : Kind.values()) {
			assertFalse(DragonPrep.held(state, kind), kind.name());
		}
		assertFalse(DragonPrep.ignoresGaze(null));
	}
}
