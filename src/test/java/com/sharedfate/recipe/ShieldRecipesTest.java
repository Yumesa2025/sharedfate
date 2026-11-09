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
import net.minecraft.world.item.component.UseEffects;
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

	/** 다이아몬드 방패 제작법은 방패 세트 4단계라 방패 증강 넷을 쥔다. */
	private TeamState diamondTeam() {
		TeamState state = TeamState.fresh(20); state.perksEnabled = true;
		PerkRegistry.all().stream().filter(perk -> perk.hasSetType(PerkSetType.SHIELD)).limit(4)
				.forEach(perk -> state.ownedPerks.add(perk.id()));
		assertTrue(ShieldRecipes.unlocked(state, ShieldCraftEffect.DIAMOND_RECIPE));
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
		// 막는 동안 달리기를 시작할 수 있어야 한다 — 거짓이면 LocalPlayer 가 방패를 든 채 달리기를 막는다.
		assertTrue(result.get(DataComponents.USE_EFFECTS).canSprint());
		assertNull(ShieldRecipes.result(grid.subList(0, 8), state));
	}

	@Test void 바닐라_방패_내구도_표식은_바닐라_기본값과_같다() {
		assertEquals(new ItemStack(Items.SHIELD).getMaxDamage(), ShieldRecipes.VANILLA_MAX_DAMAGE);
		assertFalse(ShieldRecipes.modShield(new ItemStack(Items.SHIELD)));
		assertTrue(ShieldRecipes.modShield(ShieldRecipes.steel(new ItemStack(Items.SHIELD))));
		assertTrue(ShieldRecipes.modShield(ShieldRecipes.diamond()));
	}

	@Test void 모드_방패는_바닐라_두개_합치기_수리로_넘기지_않는다() {
		TeamState state = diamondTeam();
		ItemStack plain = new ItemStack(Items.SHIELD); plain.setDamageValue(100);
		ItemStack plain2 = new ItemStack(Items.SHIELD); plain2.setDamageValue(200);
		ItemStack steel = ShieldRecipes.steel(new ItemStack(Items.SHIELD)); steel.setDamageValue(500);
		ItemStack diamond = ShieldRecipes.diamond();
		// 이름을 지운 강철 방패도 최대 내구도로 알아본다.
		ItemStack renamedSteel = steel.copy(); renamedSteel.remove(DataComponents.CUSTOM_NAME);
		ItemStack unbreakablePlain = new ItemStack(Items.SHIELD);
		unbreakablePlain.set(DataComponents.UNBREAKABLE, net.minecraft.util.Unit.INSTANCE);
		for (var pair : List.of(List.of(diamond, diamond.copy()), List.of(steel, plain), List.of(plain, diamond),
				List.of(renamedSteel, steel.copy()), List.of(unbreakablePlain, plain))) {
			// 제작대(빈 칸 섞임) · 인벤토리 2×2 모두 결과 칸을 비운다. 팀 조건과 상관없다.
			var workbench = List.of(pair.get(0), ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, pair.get(1),
					ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
			var inventory = List.of(ItemStack.EMPTY, pair.get(0), pair.get(1), ItemStack.EMPTY);
			assertTrue(ShieldRecipes.result(workbench, state).isEmpty());
			assertNotNull(ShieldRecipes.result(workbench, null));
			assertTrue(ShieldRecipes.result(workbench, null).isEmpty());
			assertNotNull(ShieldRecipes.inventoryResult(inventory, state));
			assertTrue(ShieldRecipes.inventoryResult(inventory, state).isEmpty());
		}
		// 바닐라 방패 둘은 그대로 바닐라 수리에 맡긴다.
		assertNull(ShieldRecipes.result(List.of(plain, plain2), state));
		assertNull(ShieldRecipes.inventoryResult(List.of(plain, plain2, ItemStack.EMPTY, ItemStack.EMPTY), state));
	}

	@Test void 다이아몬드_방패는_바닐라_일반_방패_아홉개로만_만든다() {
		TeamState state = diamondTeam();
		ItemStack enchanted = new ItemStack(Items.SHIELD);
		enchanted.enchant(TestBootstrap.registries().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 3);
		ItemStack damaged = new ItemStack(Items.SHIELD); damaged.setDamageValue(300);
		ItemStack named = new ItemStack(Items.SHIELD); named.set(DataComponents.CUSTOM_NAME, Component.literal("내 방패"));
		for (ItemStack odd : List.of(ShieldRecipes.steel(new ItemStack(Items.SHIELD)), ShieldRecipes.diamond(), enchanted)) {
			var grid = new java.util.ArrayList<ItemStack>();
			for (int i = 0; i < 8; i++) grid.add(new ItemStack(Items.SHIELD));
			grid.add(odd);
			ItemStack result = ShieldRecipes.result(grid, state);
			assertNotNull(result);
			assertTrue(result.isEmpty(), "아홉 칸에 일반 방패가 아닌 것이 섞였는데 다이아몬드 방패가 나온다");
		}
		// 닳은 방패 · 이름만 붙인 바닐라 방패는 일반 방패다.
		var grid = new java.util.ArrayList<ItemStack>();
		for (int i = 0; i < 7; i++) grid.add(new ItemStack(Items.SHIELD));
		grid.add(damaged); grid.add(named);
		assertEquals(3000, ShieldRecipes.result(grid, state).getMaxDamage());
		// 2×2 칸에서는 아홉 개가 들어가지 않으니 여기 시험은 제작대 몫이다.
	}

	@Test void 옛_다이아몬드_방패만_달릴_수_있게_고친다() {
		// 0.45.0-dev 가 만든 모양 그대로 — 이름을 바꿔도 같은 방패로 본다.
		ItemStack legacy = ShieldRecipes.diamond();
		legacy.set(DataComponents.USE_EFFECTS, new UseEffects(false, true, 1.0F));
		legacy.set(DataComponents.CUSTOM_NAME, Component.literal("내 방패"));
		assertTrue(ShieldRecipes.fixLegacyDiamond(legacy));
		assertTrue(legacy.get(DataComponents.USE_EFFECTS).canSprint());
		assertEquals(1.0F, legacy.get(DataComponents.USE_EFFECTS).speedMultiplier());
		// 이미 고친 것 · 새로 만든 것은 다시 건드리지 않는다.
		assertFalse(ShieldRecipes.fixLegacyDiamond(legacy));
		assertFalse(ShieldRecipes.fixLegacyDiamond(ShieldRecipes.diamond()));
		// 다이아몬드 방패가 아닌 것은 같은 쓰기 효과를 달고 있어도 그대로 둔다.
		ItemStack plain = new ItemStack(Items.SHIELD);
		plain.set(DataComponents.USE_EFFECTS, new UseEffects(false, true, 1.0F));
		assertFalse(ShieldRecipes.fixLegacyDiamond(plain));
		assertFalse(plain.get(DataComponents.USE_EFFECTS).canSprint());
		assertFalse(ShieldRecipes.fixLegacyDiamond(ItemStack.EMPTY));
		assertFalse(ShieldRecipes.fixLegacyDiamond(null));
	}

	// 판자 쪽은 시험 환경에서 판자 태그가 묶이지 않아 수리 계산(repair) 시험으로 갈음한다.
	@Test void 인벤토리_2x2_칸에서는_판자_수리만_된다() {
		// 강철 방패는 2×2 에 들어가도 제작대에서만 만든다 — 바닐라 처리로 넘긴다.
		var steelGrid = List.of(new ItemStack(Items.SHIELD), new ItemStack(Items.IRON_BLOCK), ItemStack.EMPTY, ItemStack.EMPTY);
		assertNull(ShieldRecipes.inventoryResult(steelGrid, holding(ShieldCraftEffect.STEEL_RECIPE)));
		assertFalse(ShieldRecipes.result(steelGrid, holding(ShieldCraftEffect.STEEL_RECIPE)).isEmpty());
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

	/** 2026-11-10 사람 말 「모든 방패에 적용시켜」 — 방패 4단계의 「막는 동안 이동 속도 감소 없음」. */
	@Test void 방패_사단계는_일반과_강철_방패에_달릴_수_있는_쓰기_효과를_붙인다() {
		ItemStack plain = new ItemStack(Items.SHIELD);
		assertTrue(ShieldRecipes.applyFreeMovement(plain, true));
		assertEquals(ShieldRecipes.FREE_MOVEMENT_USE_EFFECTS, plain.get(DataComponents.USE_EFFECTS));
		assertTrue(plain.get(DataComponents.USE_EFFECTS).canSprint(), "막으면서 달릴 수 있어야 한다");
		assertFalse(ShieldRecipes.applyFreeMovement(plain, true), "두 번 붙이지 않는다");

		ItemStack steel = ShieldRecipes.steel(new ItemStack(Items.SHIELD));
		assertTrue(ShieldRecipes.applyFreeMovement(steel, true));
		assertEquals(ShieldRecipes.FREE_MOVEMENT_USE_EFFECTS, steel.get(DataComponents.USE_EFFECTS));
	}

	@Test void 세트가_꺼지면_붙였던_쓰기_효과만_뗀다() {
		ItemStack plain = new ItemStack(Items.SHIELD);
		UseEffects vanilla = plain.get(DataComponents.USE_EFFECTS);
		ShieldRecipes.applyFreeMovement(plain, true);
		assertTrue(ShieldRecipes.applyFreeMovement(plain, false));
		assertEquals(vanilla, plain.get(DataComponents.USE_EFFECTS), "바닐라 값으로 돌아가야 한다");
		assertFalse(ShieldRecipes.applyFreeMovement(new ItemStack(Items.SHIELD), false), "손댈 것이 없다");
	}

	@Test void 다이아몬드_방패와_따로_정한_쓰기_효과는_건드리지_않는다() {
		ItemStack diamond = ShieldRecipes.diamond();
		assertFalse(ShieldRecipes.applyFreeMovement(diamond, false), "다이아몬드 방패는 제 값을 지킨다");
		assertEquals(ShieldRecipes.DIAMOND_USE_EFFECTS, diamond.get(DataComponents.USE_EFFECTS));

		ItemStack custom = new ItemStack(Items.SHIELD);
		UseEffects other = new UseEffects(false, false, 0.5F);
		custom.set(DataComponents.USE_EFFECTS, other);
		assertFalse(ShieldRecipes.applyFreeMovement(custom, true));
		assertEquals(other, custom.get(DataComponents.USE_EFFECTS));
	}

	@Test void 방패_사단계_정의에_달리기_효과가_들어_있다() {
		assertTrue(PerkSetRegistry.all().values().stream()
				.flatMap(java.util.List::stream)
				.anyMatch(tier -> tier.count() == 4 && tier.effects().stream()
						.anyMatch(effect -> effect instanceof com.sharedfate.perk.effect.ShieldFreeMovementEffect)),
				"세트 정의가 shield_free_movement 를 읽어야 한다");
	}

	/**
	 * 숫돌은 위 칸 방패를 복사하고 큰 최대 내구도만 적는다. 일반 방패 + 다이아몬드 방패면 「닳지 않음」
	 * 없는 내구도 3000 짜리가 나왔다(2026-11-10 검토). 믹스인이 입혀진 진짜 {@code mergeItems} 를 부른다.
	 */
	@Test void 숫돌은_모드_방패를_다른_방패와_합치지_않는다() throws Exception {
		ItemStack plain = new ItemStack(Items.SHIELD);
		plain.setDamageValue(100);
		assertTrue(grindstoneMerge(plain, ShieldRecipes.diamond()).isEmpty(), "일반 + 다이아몬드");
		assertTrue(grindstoneMerge(ShieldRecipes.steel(new ItemStack(Items.SHIELD)), plain.copy()).isEmpty(),
				"강철 + 일반");
	}

	@Test void 숫돌은_바닐라_방패_둘은_그대로_합친다() throws Exception {
		ItemStack first = new ItemStack(Items.SHIELD);
		first.setDamageValue(200);
		ItemStack second = new ItemStack(Items.SHIELD);
		second.setDamageValue(200);
		ItemStack merged = grindstoneMerge(first, second);
		assertTrue(merged.is(Items.SHIELD), "바닐라 수리는 막지 않는다");
		assertTrue(merged.getDamageValue() < 200, "합치면 덜 닳아야 한다");
	}

	private static ItemStack grindstoneMerge(ItemStack first, ItemStack second) throws Exception {
		var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		unsafeField.setAccessible(true);
		var unsafe = (sun.misc.Unsafe) unsafeField.get(null);
		Object menu = unsafe.allocateInstance(net.minecraft.world.inventory.GrindstoneMenu.class);
		var merge = net.minecraft.world.inventory.GrindstoneMenu.class
				.getDeclaredMethod("mergeItems", ItemStack.class, ItemStack.class);
		merge.setAccessible(true);
		return (ItemStack) merge.invoke(menu, first, second);
	}

	private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
		var field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
	}
}
