package com.sharedfate.recipe;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.perk.effect.ToolCraftEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.NonNullList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「금기의 광석」의 「다이아몬드 9개로 네더라이트 주괴를 만들 수 있습니다」.
 *
 * <p>방패 제작법({@link ShieldRecipesTest})과 같은 고리를 지나므로 같은 방법으로 본다. 방패 쪽 판정은
 * 재료마다 판자 태그를 묻는데 시험 환경에는 태그가 묶여 있지 않아, 다이아몬드·다이아몬드 블록에
 * 「태그 없음」을 잠깐 묶는다.
 */
class DiamondNetheriteRecipeTest {
	private final Map<Holder.Reference<Item>, Object> savedTags = new HashMap<>();

	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }

	@BeforeEach void load() throws Exception {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		for (Item item : List.of(Items.DIAMOND, Items.DIAMOND_BLOCK, Items.EMERALD)) bindNoTags(item);
	}

	@AfterEach void clear() throws Exception {
		Field field = Holder.Reference.class.getDeclaredField("tags");
		field.setAccessible(true);
		for (var entry : savedTags.entrySet()) field.set(entry.getKey(), entry.getValue());
		savedTags.clear();
		TeamLookup.setServer(null);
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	private static TeamState team(String... perks) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perks));
		return state;
	}

	private static List<ItemStack> diamonds(int count) {
		return IntStream.range(0, 9).mapToObj(i -> i < count ? new ItemStack(Items.DIAMOND, 5) : ItemStack.EMPTY).toList();
	}

	@Test void 금기의_광석_팀만_다이아몬드_아홉개로_네더라이트_주괴_하나를_얻는다() {
		TeamState owner = team("sharedfate:forbidden_ore");
		assertTrue(ToolCraftEffect.DIAMOND_TO_NETHERITE.heldBy(owner));

		ItemStack result = DiamondNetheriteRecipe.result(diamonds(9), owner);
		assertNotNull(result);
		assertTrue(result.is(Items.NETHERITE_INGOT));
		assertEquals(1, result.getCount(), "칸마다 몇 개가 쌓였든 한 번에 하나다");

		assertNull(DiamondNetheriteRecipe.result(diamonds(9), team()), "다른 팀은 바닐라 다이아몬드 블록");
		assertNull(DiamondNetheriteRecipe.result(diamonds(9), null));
		owner.perksEnabled = false;
		assertNull(DiamondNetheriteRecipe.result(diamonds(9), owner), "증강을 끄면 꺼진다");
	}

	@Test void 아홉_칸을_다이아몬드로_가득_채운_모양만이다() {
		TeamState owner = team("sharedfate:forbidden_ore");
		assertNull(DiamondNetheriteRecipe.result(diamonds(8), owner), "한 칸이라도 비면 아니다");
		var mixed = new ArrayList<>(diamonds(9));
		mixed.set(4, new ItemStack(Items.EMERALD));
		assertNull(DiamondNetheriteRecipe.result(mixed, owner), "다른 재료가 섞이면 아니다");
		assertNull(DiamondNetheriteRecipe.result(diamonds(9).subList(0, 4), owner), "2×2 에는 들어가지 않는다");
	}

	@Test void 제작대에서만_바뀌고_방패_제작법과_겹치지_않는다() throws Exception {
		TeamState owner = team("sharedfate:forbidden_ore");
		var crafting = (CraftingMenu) unsafe().allocateInstance(CraftingMenu.class);
		var inventory = (InventoryMenu) unsafe().allocateInstance(InventoryMenu.class);

		ItemStack result = PerkCraftingRecipes.resultFor(crafting, diamonds(9), owner);
		assertNotNull(result);
		assertTrue(result.is(Items.NETHERITE_INGOT));
		assertNull(PerkCraftingRecipes.resultFor(inventory, diamonds(9), owner));
		assertNull(PerkCraftingRecipes.resultFor(crafting, diamonds(9), team()), "다른 팀은 바닐라에 맡긴다");
		assertNull(ShieldRecipes.resultFor(crafting, diamonds(9), owner), "방패 제작법은 다이아몬드를 모른다");
	}

	@Test void 결과를_본_뒤_증강을_잃으면_꺼낼_수_없다() throws Exception {
		var crafting = (CraftingMenu) unsafe().allocateInstance(CraftingMenu.class);
		ItemStack shown = new ItemStack(Items.NETHERITE_INGOT);
		TeamState owner = team("sharedfate:forbidden_ore");

		assertFalse(PerkCraftingRecipes.blocksPickup(crafting, diamonds(9), shown, owner));
		assertTrue(PerkCraftingRecipes.blocksPickup(crafting, diamonds(9), shown, team()), "남은 네더라이트는 막는다");
		assertFalse(PerkCraftingRecipes.blocksPickup(crafting, diamonds(9), new ItemStack(Items.DIAMOND_BLOCK), team()),
				"바닐라 다이아몬드 블록은 그대로 꺼낸다");
		assertFalse(PerkCraftingRecipes.blocksPickup(crafting, diamonds(8), shown, team()), "모양이 아니면 바닐라 판정");
	}

	/** 바닐라 다이아몬드 블록 제작법이 남기는 것은 없지만, 특수 결과는 재료 반환을 언제나 비운다. */
	@Test void 꺼낼_때_재료_반환을_비운다(@TempDir Path directory) throws Exception {
		var server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			setField(MinecraftServer.class, server, "savedDataStorage", storage);
			var player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
			UUID id = UUID.randomUUID();
			setField(Entity.class, player, "uuid", id);
			player.containerMenu = (CraftingMenu) unsafe().allocateInstance(CraftingMenu.class);
			TeamManager.get(server).createTeam("제작", id, team("sharedfate:forbidden_ore"));
			TeamLookup.setServer(server);
			var level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
			var input = CraftingInput.of(3, 3, diamonds(9));
			var slot = new ResultSlot(player, null, new ResultContainer(), 0, 0, 0);
			Method remaining = ResultSlot.class.getDeclaredMethod("getRemainingItems", CraftingInput.class, Level.class);
			remaining.setAccessible(true);

			@SuppressWarnings("unchecked") var items = (NonNullList<ItemStack>) remaining.invoke(slot, input, level);
			assertEquals(9, items.size());
			assertTrue(items.stream().allMatch(ItemStack::isEmpty));
		}
	}

	private void bindNoTags(Item item) throws Exception {
		Holder.Reference<Item> holder = item.builtInRegistryHolder();
		Field field = Holder.Reference.class.getDeclaredField("tags");
		field.setAccessible(true);
		savedTags.putIfAbsent(holder, field.get(holder));
		field.set(holder, Set.of());
	}

	private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (sun.misc.Unsafe) field.get(null);
	}
}
