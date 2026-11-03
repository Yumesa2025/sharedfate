package com.sharedfate.enchant;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.sharedfate.TestBootstrap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ShieldEnchantmentsTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@Test void 마법부여_화면의_주입_서명이_현재_클라이언트와_맞는다() throws Exception {
		assertNotNull(Class.forName("net.minecraft.client.gui.screens.inventory.EnchantmentScreen"));
	}

	@Test void 후보는_등급_십오와_이십이에서_열린다() {
		assertTrue(ShieldEnchantments.candidates(14).isEmpty());
		assertEquals(List.of("minecraft:unbreaking", "minecraft:thorns"),
				ShieldEnchantments.candidates(15).stream().map(ShieldEnchantments.Candidate::id).toList());
		assertEquals(2, ShieldEnchantments.candidates(21).size());
		assertEquals(8, ShieldEnchantments.candidates(22).size());
	}

	@Test void 보호는_배타적이고_다른_종류는_함께_붙을_수_있다() {
		int most = 0;
		for (int seed = 0; seed < 1000; seed++) {
			var selected = ShieldEnchantments.choose(30, RandomSource.create(seed));
			assertTrue(selected.stream().filter(ShieldEnchantments.Candidate::protection).count() <= 1);
			assertEquals(selected.size(), selected.stream().map(ShieldEnchantments.Candidate::id).distinct().count());
			most = Math.max(most, selected.size());
		}
		assertEquals(5, most, "보호 하나·내구성·가시·체력·방어력이 함께 붙을 수 있다");
	}

	@Test void 새_마법부여_정의를_읽고_양손_속성에_실제로_적용한다() throws Exception {
		for (String id : List.of("shield_health", "shield_armor")) {
			try (var reader = new InputStreamReader(getClass().getResourceAsStream(
					"/data/sharedfate/enchantment/" + id + ".json"), StandardCharsets.UTF_8)) {
				Enchantment enchantment = Enchantment.DIRECT_CODEC.parse(
						TestBootstrap.registries().createSerializationContext(JsonOps.INSTANCE), JsonParser.parseReader(reader)).getOrThrow();
				int max = id.equals("shield_health") ? 6 : 4;
				assertEquals(max, enchantment.getMaxLevel());
				ItemStack shield = new ItemStack(Items.SHIELD);
				shield.enchant(Holder.direct(enchantment), max);
				for (var slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
					double[] amount = {0};
					EnchantmentHelper.forEachModifier(shield, slot, (attribute, modifier) -> {
						assertEquals(id.equals("shield_health") ? Attributes.MAX_HEALTH : Attributes.ARMOR, attribute);
						amount[0] += modifier.amount();
					});
					assertEquals(max, amount[0]);
				}
			}
		}
	}

	@Test void 변환된_바닐라_반복자가_방패의_보호와_가시를_집행한다() throws Exception {
		Class<?> visitor = Class.forName("net.minecraft.world.item.enchantment.EnchantmentHelper$EnchantmentInSlotVisitor");
		var iterate = EnchantmentHelper.class.getDeclaredMethod("runIterationOnItem", ItemStack.class,
				EquipmentSlot.class, LivingEntity.class, visitor);
		iterate.setAccessible(true);
		ItemStack shield = new ItemStack(Items.SHIELD);
		var registry = TestBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
		shield.enchant(registry.getOrThrow(Enchantments.PROTECTION), 4);
		shield.enchant(registry.getOrThrow(Enchantments.THORNS), 2);
		for (var slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND)) {
			int[] called = {0};
			Object callback = Proxy.newProxyInstance(visitor.getClassLoader(), new Class<?>[]{visitor},
					(proxy, method, args) -> { called[0]++; return null; });
			iterate.invoke(null, shield, slot, null, callback);
			assertEquals(2, called[0]);
		}
	}

	@Test void 화염과_폭발_보호의_부가_속성도_손에서_작동한다() {
		var registry = TestBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
		ItemStack shield = new ItemStack(Items.SHIELD);
		shield.enchant(registry.getOrThrow(Enchantments.FIRE_PROTECTION), 4);
		double[] amount = {0};
		EnchantmentHelper.forEachModifier(shield, EquipmentSlot.OFFHAND, (attribute, modifier) -> {
			assertEquals(Attributes.BURNING_TIME, attribute);
			amount[0] += modifier.amount();
		});
		assertEquals(-0.6, amount[0], 1e-6);
	}

	@Test void 공유_체력은_양손과_여러_팀원의_최댓값만_더하고_내려놓으면_복원한다() {
		var team = com.sharedfate.team.TeamState.fresh(20);
		try {
			ShieldEnchantments.rememberTeamHealth(team, 2, 6, 4);
			assertEquals(26, com.sharedfate.perk.PerkHealthRules.effectiveMaxHealth(team));
			ShieldEnchantments.rememberTeamHealth(team, 0);
			assertEquals(20, com.sharedfate.perk.PerkHealthRules.effectiveMaxHealth(team));
		} finally { ShieldEnchantments.reset(); }
	}
}
