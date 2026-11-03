package com.sharedfate.perk;

import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.*;
import com.sharedfate.team.TeamState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 번들 정의와 실제 세트 판정 경로가 함께 바뀌었는지 본다. */
class PerkBalanceUpdateTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@BeforeEach void load() { PerkRegistry.loadBundled(); PerkSetRegistry.loadBundled(); }
	@AfterEach void clear() { PerkRegistry.clear(); PerkSetRegistry.clear(); PerkTriggers.resetForTesting(); }

	private Perk perk(String name) { return PerkRegistry.byId("sharedfate:" + name).orElseThrow(); }
	private PerkSets.Tier tier(PerkSetType type, int count) {
		return PerkSetRegistry.tiersOf(type).stream().filter(t -> t.count() == count).findFirst().orElseThrow();
	}
	private <T> T effect(PerkSets.Tier tier, Class<T> type) {
		return tier.effects().stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
	}
	private TeamState team(PerkSetType type) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		PerkRegistry.all().stream().filter(p -> p.setTypes().contains(type)).limit(3)
				.forEach(p -> state.ownedPerks.add(p.id()));
		return state;
	}

	@Test void 광전사는_머리와_가슴만_막는다() {
		HideHudEffect hud = perk("blind_giant").effects().stream().filter(HideHudEffect.class::isInstance)
				.map(HideHudEffect.class::cast).findFirst().orElseThrow();
		assertTrue(hud.hides(HideHudEffect.Element.HEALTH));
		assertFalse(hud.hides(HideHudEffect.Element.FOOD));
		EquipBanEffect ban = perk("berserker").effects().stream().filter(EquipBanEffect.class::isInstance)
				.map(EquipBanEffect.class::cast).findFirst().orElseThrow();
		assertTrue(ban.bans(EquipmentSlot.HEAD)); assertTrue(ban.bans(EquipmentSlot.CHEST));
		assertFalse(ban.bans(EquipmentSlot.LEGS)); assertFalse(ban.bans(EquipmentSlot.FEET));
		GatherEffect gather = perk("shared_fate_bond").effects().stream().filter(GatherEffect.class::isInstance)
				.map(GatherEffect.class::cast).findFirst().orElseThrow();
		assertTrue(gather.grants().isEmpty());
		assertEquals(119, PerkRegistry.all().size());
	}

	@Test void 세트_보상_값이_맞다() {
		AttributeEffect speed = effect(tier(PerkSetType.WEAPON, 3), AttributeEffect.class);
		assertEquals("minecraft:attack_speed", speed.attributeId().toString());
		assertEquals(0.30, speed.amount(), 1e-9);
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, speed.operation());
		assertEquals(4, effect(tier(PerkSetType.HUNT, 3), AlwaysLootingEffect.class).levels());
		assertEquals(2, effect(tier(PerkSetType.SURVIVAL, 3), MaxHealthBonusEffect.class).amount());
		assertEquals(3.0, effect(tier(PerkSetType.BOND, 3), ProximityRangeEffect.class).multiplier());
		for (int n : new int[]{3, 4}) {
			SupplyDropEffect supply = effect(tier(PerkSetType.SUPPLY, n), SupplyDropEffect.class);
			assertEquals(0, supply.nothingChance());
			assertEquals(n == 3 ? 10 : 5, supply.intervalMinutes());
			assertEquals(n == 3 ? 2 : 5, supply.rolls());
		}
	}

	@Test void 다이아몬드는_적대_몹만_십퍼센트다() {
		HostileDiamondDropEffect drop = effect(tier(PerkSetType.HUNT, 3), HostileDiamondDropEffect.class);
		assertEquals(0.1, drop.chance());
		assertTrue(drop.drops(true, 0)); assertTrue(drop.drops(true, 0.099999));
		assertFalse(drop.drops(true, 0.1)); assertFalse(drop.drops(true, 0.99));
		assertFalse(drop.drops(false, 0));
		assertNull(HostileDiamondDropEffect.fromJson("test", 0,
				JsonParser.parseString("{\"chance\":1.1}").getAsJsonObject()));
	}

	@Test void 기동_면역은_세트가_꺼지면_사라진다() {
		TeamState state = team(PerkSetType.MOBILITY);
		assertTrue(FallImmunityEffect.heldBy(state));
		state.perksEnabled = false;
		assertFalse(FallImmunityEffect.heldBy(state));
		state.perksEnabled = true;
		state.ownedPerks.remove(state.ownedPerks.iterator().next());
		assertFalse(FallImmunityEffect.heldBy(state));
	}

	@Test void 먹방과_생존_3단계는_피격으로_백까지_찬다() {
		TeamState state = team(PerkSetType.SURVIVAL);
		state.ownedPerks.add("sharedfate:mukbang");
		state.foodLevel = 19;
		UUID team = UUID.randomUUID();
		for (int i = 0; i < 50; i++) {
			int food = PerkTriggers.takeWholeHunger(team, PerkTriggers.hungerGainFor(state, 5));
			assertEquals(2, food);
			PerkTriggers.applyHungerToPool(state, food);
			if (i == 0) assertEquals(21, state.foodLevel);
		}
		assertEquals(100, state.foodLevel);
	}
}
