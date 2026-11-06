package com.sharedfate.perk;

import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.ArmorDurabilityEffect;
import com.sharedfate.perk.effect.DamageTakenEffect;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 방어 세트 2단계 — 받는 피해 15% 감소 · 방어구 내구도 50% 확률로 안 닳음. */
class ArmorDurabilityEffectTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@BeforeEach void load() { PerkRegistry.loadBundled(); PerkSetRegistry.loadBundled(); }
	@AfterEach void clear() { PerkRegistry.clear(); PerkSetRegistry.clear(); }

	@Test
	void 정의를_읽는다() {
		ArmorDurabilityEffect effect = assertInstanceOf(ArmorDurabilityEffect.class,
				ArmorDurabilityEffect.fromJson("t", 0,
						JsonParser.parseString("{\"ignore_chance\":0.5}").getAsJsonObject()));
		assertEquals(0.5, effect.ignoreChance(), 1e-9);
		assertNull(ArmorDurabilityEffect.fromJson("t", 0, JsonParser.parseString("{}").getAsJsonObject()));
		assertNull(ArmorDurabilityEffect.fromJson("t", 0,
				JsonParser.parseString("{\"ignore_chance\":1.5}").getAsJsonObject()));
		assertEquals(PerkEffectType.ARMOR_DURABILITY, PerkEffectType.fromId("armor_durability"));
	}

	@Test
	void 확률_안이면_닳지_않는다() {
		assertEquals(0, ArmorDurabilityEffect.loss(3, 0.5, 0.0));
		assertEquals(0, ArmorDurabilityEffect.loss(3, 0.5, 0.499));
		assertEquals(3, ArmorDurabilityEffect.loss(3, 0.5, 0.5));
		assertEquals(0, ArmorDurabilityEffect.loss(0, 0.5, 0.9));
	}

	@Test
	void 사람_방어구만_대상이다() {
		assertTrue(ArmorDurabilityEffect.isArmor(new ItemStack(Items.DIAMOND_HELMET)));
		assertTrue(ArmorDurabilityEffect.isArmor(new ItemStack(Items.IRON_BOOTS)));
		assertTrue(ArmorDurabilityEffect.isArmor(new ItemStack(Items.TURTLE_HELMET)));
		assertFalse(ArmorDurabilityEffect.isArmor(new ItemStack(Items.ELYTRA)), "겉날개는 비행 도구");
		assertFalse(ArmorDurabilityEffect.isArmor(new ItemStack(Items.SHIELD)), "방패는 방패 세트가 맡는다");
		assertFalse(ArmorDurabilityEffect.isArmor(new ItemStack(Items.DIAMOND_SWORD)));
		assertFalse(ArmorDurabilityEffect.isArmor(ItemStack.EMPTY));
	}

	@Test
	void 방어_2단계_정의가_맞다() {
		PerkSets.Tier tier = PerkSetRegistry.tiersOf(PerkSetType.DEFENSE).stream()
				.filter(t -> t.count() == 2).findFirst().orElseThrow();
		assertEquals(2, tier.effects().size());
		DamageTakenEffect taken = tier.effects().stream().filter(DamageTakenEffect.class::isInstance)
				.map(DamageTakenEffect.class::cast).findFirst().orElseThrow();
		assertEquals(0.85, taken.damageTakenMultiplier(), 1e-9);
		ArmorDurabilityEffect armor = tier.effects().stream().filter(ArmorDurabilityEffect.class::isInstance)
				.map(ArmorDurabilityEffect.class::cast).findFirst().orElseThrow();
		assertEquals(0.5, armor.ignoreChance(), 1e-9);
	}
}
