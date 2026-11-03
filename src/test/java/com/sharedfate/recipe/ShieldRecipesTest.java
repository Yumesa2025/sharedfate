package com.sharedfate.recipe;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.*;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ShieldRecipesTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@BeforeEach void load() { PerkRegistry.loadBundled(); PerkSetRegistry.loadBundled(); }
	@AfterEach void clear() { PerkRegistry.clear(); PerkSetRegistry.clear(); }

	private TeamState holding(ShieldCraftEffect effect) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		state.ownedPerks.add(PerkRegistry.all().stream().filter(perk -> perk.effects().contains(effect)).findFirst().orElseThrow().id());
		return state;
	}

	@Test void 강철은_해금한_팀만_만들고_마법부여를_보존한다() {
		ItemStack original = new ItemStack(Items.SHIELD);
		original.setDamageValue(10);
		original.enchant(TestBootstrap.registries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 3);
		var grid = List.of(original, new ItemStack(Items.IRON_BLOCK));
		assertTrue(ShieldRecipes.result(grid, null).isEmpty());
		TeamState state = holding(ShieldCraftEffect.STEEL_RECIPE);
		ItemStack steel = ShieldRecipes.result(grid, state);
		assertEquals(1008, steel.getMaxDamage());
		assertEquals(30, steel.getDamageValue());
		assertEquals(original.getEnchantments(), steel.getEnchantments());
		assertTrue(ShieldRecipes.result(List.of(steel, new ItemStack(Items.IRON_BLOCK)), state).isEmpty());
		state.perksEnabled = false;
		assertTrue(ShieldRecipes.result(grid, state).isEmpty());
	}

	@Test void 방패_넷을_모은_팀만_아홉개로_다이아몬드_방패를_만든다() {
		TeamState state = TeamState.fresh(20); state.perksEnabled = true;
		var perks = PerkRegistry.all().stream().filter(perk -> perk.hasSetType(PerkSetType.SHIELD)).limit(4).toList();
		var grid = java.util.stream.IntStream.range(0,9).mapToObj(i -> new ItemStack(Items.SHIELD)).toList();
		for (int i = 0; i < 3; i++) state.ownedPerks.add(perks.get(i).id());
		assertTrue(ShieldRecipes.result(grid, state).isEmpty());
		state.ownedPerks.add(perks.get(3).id());
		ItemStack result = ShieldRecipes.result(grid, state);
		assertEquals(3000, result.getMaxDamage());
		assertTrue(result.has(DataComponents.UNBREAKABLE));
		assertEquals(1.0F, result.get(DataComponents.USE_EFFECTS).speedMultiplier());
		assertNull(ShieldRecipes.result(grid.subList(0, 8), state));
	}

	@Test void 판자는_최대_내구도의_십오퍼센트를_수리하며_이름은_보존한다() {
		ItemStack steel = ShieldRecipes.steel(new ItemStack(Items.SHIELD));
		steel.setDamageValue(500);
		steel.set(DataComponents.CUSTOM_NAME, Component.literal("내 방패"));
		ItemStack result = ShieldRecipes.repair(steel, 2);
		assertEquals(198, result.getDamageValue());
		assertEquals("내 방패", result.getHoverName().getString());
		assertEquals(500, steel.getDamageValue());
		assertEquals(0, ShieldRecipes.repair(steel, 8).getDamageValue());
	}

	@Test void 판자_수리는_팀_조건이_없거나_수리가_필요없으면_결과가_없다() throws Exception {
		var holder = Items.OAK_PLANKS.builtInRegistryHolder();
		var tagsField = holder.getClass().getDeclaredField("tags");
		tagsField.setAccessible(true);
		Object oldBinding = tagsField.get(holder);
		var bind = holder.getClass().getDeclaredMethod("bindTags", java.util.Collection.class);
		bind.setAccessible(true);
		try {
			var tags = oldBinding == null ? new java.util.ArrayList<net.minecraft.tags.TagKey<net.minecraft.world.item.Item>>()
					: new java.util.ArrayList<>(holder.tags().toList());
			tags.add(net.minecraft.tags.ItemTags.PLANKS);
			bind.invoke(holder, tags);
			ItemStack shield = new ItemStack(Items.SHIELD); shield.setDamageValue(200);
			var grid = List.of(shield, new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS));
			assertTrue(ShieldRecipes.result(grid, null).isEmpty());
			TeamState state = holding(ShieldCraftEffect.PLANK_REPAIR);
			assertEquals(100, ShieldRecipes.result(grid, state).getDamageValue());
			shield.setDamageValue(0);
			assertTrue(ShieldRecipes.result(grid, state).isEmpty());
			assertTrue(ShieldRecipes.result(List.of(ShieldRecipes.diamond(), new ItemStack(Items.OAK_PLANKS)), state).isEmpty());
		} finally { tagsField.set(holder, oldBinding); }
	}

	@Test void 첫_방패는_일단계에서_한번이고_저장후에도_재지급하지_않는다() {
		TeamState state = TeamState.fresh(20); state.perksEnabled = true;
		state.ownedPerks.add(PerkRegistry.all().stream().filter(perk -> perk.hasSetType(PerkSetType.SHIELD)).findFirst().orElseThrow().id());
		assertTrue(ShieldRecipes.unlocked(state, ShieldCraftEffect.FIRST_SHIELD));
		ShieldSetRewards rewards = new ShieldSetRewards();
		UUID team = UUID.randomUUID(); int[] count = {0};
		assertTrue(rewards.grantOnce(team, () -> count[0]++));
		rewards = ShieldSetRewards.CODEC.parse(NbtOps.INSTANCE,
				ShieldSetRewards.CODEC.encodeStart(NbtOps.INSTANCE, rewards).getOrThrow()).getOrThrow();
		assertFalse(rewards.grantOnce(team, () -> count[0]++));
		assertEquals(1, count[0]);
	}

	@Test void 특수_제작은_바닐라의_미등록_제작법_재료_반환을_막는다(
			@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
		var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
		var server = (net.minecraft.server.MinecraftServer) unsafe.allocateInstance(net.minecraft.server.dedicated.DedicatedServer.class);
		var storage = new net.minecraft.world.level.storage.SavedDataStorage(directory, null, TestBootstrap.registries());
		setField(net.minecraft.server.MinecraftServer.class, server, "savedDataStorage", storage);
		var player = (net.minecraft.server.level.ServerPlayer) unsafe.allocateInstance(net.minecraft.server.level.ServerPlayer.class);
		UUID id = UUID.randomUUID();
		setField(net.minecraft.world.entity.Entity.class, player, "uuid", id);
		player.containerMenu = (net.minecraft.world.inventory.CraftingMenu) unsafe.allocateInstance(net.minecraft.world.inventory.CraftingMenu.class);
		var state = holding(ShieldCraftEffect.STEEL_RECIPE);
		com.sharedfate.team.TeamManager.get(server).createTeam("제작", id, state);
		var level = (net.minecraft.server.level.ServerLevel) unsafe.allocateInstance(net.minecraft.server.level.ServerLevel.class);
		var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1,
				List.of(new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_BLOCK)));
		var slot = new net.minecraft.world.inventory.ResultSlot(player, null,
				new net.minecraft.world.inventory.ResultContainer(), 0, 0, 0);
		var remaining = net.minecraft.world.inventory.ResultSlot.class.getDeclaredMethod("getRemainingItems",
				net.minecraft.world.item.crafting.CraftingInput.class, net.minecraft.world.level.Level.class);
		remaining.setAccessible(true);
		com.sharedfate.team.TeamLookup.setServer(server);
		try {
			@SuppressWarnings("unchecked") var items = (List<ItemStack>) remaining.invoke(slot, input, level);
			assertEquals(2, items.size());
			assertTrue(items.stream().allMatch(ItemStack::isEmpty));
		} finally {
			com.sharedfate.team.TeamLookup.setServer(null);
			storage.close();
		}
	}

	private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
}
