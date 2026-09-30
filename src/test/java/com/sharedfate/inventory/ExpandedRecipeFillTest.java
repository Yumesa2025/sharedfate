package com.sharedfate.inventory;

import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 조합법 책이 추가 27칸을 세고, 눌렀을 때 그 칸에서 재료를 꺼내 오는지.
 *
 * <p>이 환경에서는 {@code runClient} 가 제목 화면에 닿기 전에 죽으므로 <b>눈으로 확인할
 * 길이 없다.</b> 그래서 판정을 월드 없이 도는 함수로 빼내 여기서 붙든다. 시험 소스셋은
 * {@code fabric-loader-junit} 을 쓰므로 {@code sharedfate.mixins.json} 의 믹스인이
 * <b>실제로 적용된 채</b> 돈다 — {@link #조합법_자동채우기_믹스인이_실제로_붙는다} 가 그것을
 * 확인한다.
 *
 * <p>{@code src/client} 의 화면 믹스인은 시험이 보지 못하지만, 여기서 붙드는 두 자리는 모두
 * {@code src/main} 에 있다.
 */
class ExpandedRecipeFillTest {
	private static SharedFateConfig previousConfig;

	/** 다이아 한 개를 요구하는 가짜 재료칸. {@code StackedContents.IngredientInfo} 는 단일 메서드다. */
	private static final StackedContents.IngredientInfo<Holder<Item>> WANTS_DIAMOND =
			item -> item.value() == Items.DIAMOND;

	private static Holder<Item> diamond() {
		return Items.DIAMOND.builtInRegistryHolder();
	}

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
		previousConfig = SharedFateMod.config;
		SharedFateMod.config = new SharedFateConfig();
		SharedFateMod.config.mainInventoryRows = 6;
	}

	@AfterAll
	static void tearDown() {
		ExpandedInventoryManager.clearRuntimeState();
		SharedFateMod.config = previousConfig;
	}

	@BeforeEach
	void resetState() {
		ExpandedInventoryManager.clearRuntimeState();
		SharedFateMod.config.mainInventoryRows = 6;
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.BASE_EXTRA_SIZE);
	}

	/** 팀에 든 사람의 인벤토리. 시험에서는 {@code player} 가 비어 있어도 된다. */
	private static Inventory activeInventory() {
		ExpandedInventoryManager.extraFor(null).setClientActive(true);
		return new Inventory(null, new EntityEquipment());
	}

	private static ExpandedInventoryContainer extra() {
		return ExpandedInventoryManager.extraFor(null);
	}

	// ------------------------------------------------ 1. 「만들 수 있는가」 (클라이언트)

	/**
	 * 회색인지 아닌지를 정하는 셈.
	 *
	 * <p>{@code RecipeBookComponent.updateStackedContents} 는
	 * {@code minecraft.player.getInventory().fillStackedContents(...)} 를 부른다. 그 자리가
	 * 추가 칸을 세지 않으면 재료를 아래 줄에만 둔 조합법이 <b>회색으로 남는다.</b>
	 *
	 * <p>{@code LocalPlayer} 는 {@code ServerPlayer} 가 아니므로, 이 시험이 통과한다는 것은
	 * 「서버에서만 세는」 옛 조건이 아니라는 뜻이다 — 여기 {@code player} 는 {@code null} 이다.
	 */
	@Test
	void 클라이언트_인벤토리_셈에도_추가_칸의_재료가_들어간다() {
		Inventory inventory = activeInventory();
		extra().setItem(0, new ItemStack(Items.DIAMOND, 2));

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertTrue(contents.canCraft(List.of(WANTS_DIAMOND, WANTS_DIAMOND), null),
				"추가 칸의 다이아 두 개를 세지 않으면 조합법이 회색으로 남는다");
	}

	@Test
	void 팀이_없으면_추가_칸을_세지_않는다() {
		extra().setClientActive(false);
		Inventory inventory = new Inventory(null, new EntityEquipment());
		extra().setItem(0, new ItemStack(Items.DIAMOND, 8));

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertFalse(contents.canCraft(List.of(WANTS_DIAMOND), null));
	}

	@Test
	void 확장이_꺼져_있으면_바닐라와_같이_센다() {
		SharedFateMod.config.mainInventoryRows = 3;
		try {
			extra().setClientActive(true);
			Inventory inventory = new Inventory(null, new EntityEquipment());
			extra().setItem(0, new ItemStack(Items.DIAMOND, 8));

			StackedItemContents contents = new StackedItemContents();
			inventory.fillStackedContents(contents);

			assertFalse(contents.canCraft(List.of(WANTS_DIAMOND), null),
					"세 줄 설정에서는 추가 칸이 없는 것과 같아야 한다");
		} finally {
			SharedFateMod.config.mainInventoryRows = 6;
		}
	}

	/**
	 * 닳거나 마법이 걸리거나 이름을 붙인 물건은 재료가 아니다.
	 *
	 * <p>{@code Inventory.fillStackedContents} 가 {@code accountSimpleStack} 을 쓰는 이유이고,
	 * 꺼내는 쪽({@code findSlotMatchingCraftingIngredient})도 같은 기준으로 거른다. 세는 쪽만
	 * 넓으면 조합법이 <b>흰색으로 떠 있는데 눌러도 안 채워진다.</b>
	 */
	@Test
	void 이름을_붙인_물건은_재료로_세지_않는다() {
		Inventory inventory = activeInventory();
		ItemStack named = new ItemStack(Items.DIAMOND, 4);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("아끼는 다이아"));
		extra().setItem(0, named);

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertFalse(contents.canCraft(List.of(WANTS_DIAMOND), null),
				"꺼낼 수 없는 것을 세면 흰색으로 떠 있다가 눌러도 안 채워진다");
	}

	@Test
	void 잠긴_칸의_재료는_세지_않는다() {
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.BASE_EXTRA_SIZE);
		Inventory inventory = activeInventory();
		extra().setItem(ExpandedInventoryManager.BASE_EXTRA_SIZE, new ItemStack(Items.DIAMOND, 9));

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertFalse(contents.canCraft(List.of(WANTS_DIAMOND), null),
				"화면 밖 칸을 세면 꺼낼 수 없는 재료로 흰색이 된다");
	}

	@Test
	void 짐꾼이_연_칸의_재료는_센다() {
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.EXTRA_SIZE);
		Inventory inventory = activeInventory();
		extra().setItem(ExpandedInventoryManager.BASE_EXTRA_SIZE, new ItemStack(Items.DIAMOND, 9));

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertTrue(contents.canCraft(List.of(WANTS_DIAMOND), null));
	}

	/**
	 * 엔더 상자는 팀이 공유하지만 조합법은 「지금 들고 있는 것」으로 판정해야 한다.
	 *
	 * <p>{@code Inventory.fillStackedContents} 가 더하는 것이 추가 27칸뿐인지 못박는다 —
	 * 엔더 상자를 여기에 끼우면 상자 안의 재료로 조합법이 흰색이 되고, 눌러도 절대 안 채워진다.
	 */
	@Test
	void 엔더_상자는_조합법_셈에_들어가지_않는다() {
		Inventory inventory = activeInventory();
		extra().setItem(0, new ItemStack(Items.DIAMOND, 1));

		StackedItemContents contents = new StackedItemContents();
		inventory.fillStackedContents(contents);

		assertTrue(contents.canCraft(List.of(WANTS_DIAMOND), null));
		assertFalse(contents.canCraft(List.of(WANTS_DIAMOND, WANTS_DIAMOND), null),
				"추가 칸의 한 개 말고 다른 데서 세어 오는 것이 있으면 안 된다");
	}

	// -------------------------------------------- 2. 「눌러서 자동 채우기」 (서버)

	@Test
	void 추가_칸에서_재료를_꺼내_조합칸에_넣는다() {
		activeInventory();
		extra().setItem(3, new ItemStack(Items.DIAMOND, 5));
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);

		int remaining = ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 2);

		assertEquals(0, remaining);
		assertEquals(2, grid.getItem().getCount());
		assertEquals(3, extra().getItem(3).getCount());
	}

	@Test
	void 모자라면_있는_만큼만_꺼내고_남은_수를_알린다() {
		activeInventory();
		extra().setItem(0, new ItemStack(Items.DIAMOND, 2));
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);

		int remaining = ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 5);

		assertEquals(3, remaining, "부르는 쪽이 이 값으로 다시 부른다");
		assertEquals(2, grid.getItem().getCount());
		assertTrue(extra().getItem(0).isEmpty());
	}

	@Test
	void 조합칸에_이미_있으면_그_위에_쌓는다() {
		activeInventory();
		extra().setItem(0, new ItemStack(Items.DIAMOND, 4));
		SimpleContainer gridContainer = new SimpleContainer(1);
		gridContainer.setItem(0, new ItemStack(Items.DIAMOND, 1));
		Slot grid = new Slot(gridContainer, 0, 0, 0);

		assertEquals(0, ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 2));
		assertEquals(3, grid.getItem().getCount());
	}

	@Test
	void 잠긴_칸에서는_재료를_꺼내지_않는다() {
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.BASE_EXTRA_SIZE);
		activeInventory();
		extra().setItem(ExpandedInventoryManager.BASE_EXTRA_SIZE, new ItemStack(Items.DIAMOND, 5));
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);

		assertEquals(ExpandedRecipeFill.NOT_FOUND,
				ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 1));
		assertTrue(grid.getItem().isEmpty());
	}

	@Test
	void 이름을_붙인_물건은_꺼내지_않는다() {
		activeInventory();
		ItemStack named = new ItemStack(Items.DIAMOND, 4);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("아끼는 다이아"));
		extra().setItem(0, named);
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);

		assertEquals(ExpandedRecipeFill.NOT_FOUND,
				ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 1));
	}

	@Test
	void 팀이_없거나_확장이_꺼지면_꺼내지_않는다() {
		extra().setClientActive(false);
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);
		extra().setItem(0, new ItemStack(Items.DIAMOND, 5));

		assertEquals(ExpandedRecipeFill.NOT_FOUND,
				ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 1));

		SharedFateMod.config.mainInventoryRows = 3;
		try {
			extra().setClientActive(true);
			assertEquals(ExpandedRecipeFill.NOT_FOUND,
					ExpandedRecipeFill.moveToGrid(null, grid, diamond(), 1));
		} finally {
			SharedFateMod.config.mainInventoryRows = 6;
		}
	}

	// ------------------------------------------------- 3. 조합칸을 되돌려 놓기

	@Test
	void 되돌릴_자리를_셀_때_추가_빈칸을_더한다() {
		activeInventory();
		extra().setItem(0, new ItemStack(Items.COBBLESTONE, 64));

		assertEquals(ExpandedInventoryManager.BASE_EXTRA_SIZE - 1,
				ExpandedRecipeFill.freeOpenSlots(null),
				"열린 18칸에서 한 칸이 차 있으니 17이어야 한다");
	}

	@Test
	void 잠긴_칸은_되돌릴_자리로_세지_않는다() {
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.BASE_EXTRA_SIZE);
		activeInventory();

		assertEquals(ExpandedInventoryManager.BASE_EXTRA_SIZE,
				ExpandedRecipeFill.freeOpenSlots(null),
				"잠긴 아홉 칸을 세면 되돌린 물건이 화면 밖으로 사라진다");
	}

	@Test
	void 확장이_꺼지면_되돌릴_추가_자리가_없다() {
		SharedFateMod.config.mainInventoryRows = 3;
		try {
			extra().setClientActive(true);
			assertEquals(0, ExpandedRecipeFill.freeOpenSlots(null));
		} finally {
			SharedFateMod.config.mainInventoryRows = 6;
		}
	}

	/** 바닐라 36칸에 자리가 있으면 거기로 간다. 추가 칸은 넘침받이다. */
	@Test
	void 되돌리기는_바닐라_36칸을_먼저_채운다() {
		Inventory inventory = activeInventory();
		ItemStack back = new ItemStack(Items.DIAMOND, 7);

		ExpandedRecipeFill.putBack(inventory, back, false, Prediction.SERVER_ONLY);

		assertTrue(back.isEmpty());
		assertEquals(7, inventory.getNonEquipmentItems().stream()
				.mapToInt(ItemStack::getCount).sum());
		assertEquals(0, extra().getItems().stream().mapToInt(ItemStack::getCount).sum(),
				"위에 자리가 있는데 아래로 내려보내면 안 된다");
	}

	/**
	 * 바닐라 36칸이 꽉 차면 추가 칸으로 간다.
	 *
	 * <p>이것이 없으면 {@code Inventory.placeItemBackInInventory} 가 남은 것을 <b>바닥에
	 * 버린다.</b> 물건이 아래 줄까지 쌓인 사람은 위 36칸이 이미 차 있으므로, 조합법을 두 번째로
	 * 누를 때마다 조합칸의 재료가 바닥에 떨어졌을 것이다.
	 */
	@Test
	void 바닐라가_꽉_차면_추가_칸으로_되돌린다() {
		Inventory inventory = activeInventory();
		for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		ItemStack back = new ItemStack(Items.DIAMOND, 7);

		ExpandedRecipeFill.putBack(inventory, back, false, Prediction.SERVER_ONLY);

		assertTrue(back.isEmpty(), "갈 곳을 찾지 못하면 조합칸이 비워지며 물건이 사라진다");
		assertEquals(7, extra().getItem(0).getCount());
	}

	/** 바닐라에 반만 들어가면 나머지만 아래로 내려간다. */
	@Test
	void 바닐라에_반만_들어가면_나머지가_추가_칸으로_간다() {
		Inventory inventory = activeInventory();
		for (int slot = 1; slot < inventory.getNonEquipmentItems().size(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		inventory.setItem(0, new ItemStack(Items.DIAMOND, 60));
		ItemStack back = new ItemStack(Items.DIAMOND, 10);

		ExpandedRecipeFill.putBack(inventory, back, false, Prediction.SERVER_ONLY);

		assertTrue(back.isEmpty());
		assertEquals(64, inventory.getItem(0).getCount(), "바닐라 칸을 먼저 가득 채워야 한다");
		assertEquals(6, extra().getItem(0).getCount());
	}

	@Test
	void 확장이_꺼지면_되돌리기가_바닐라_그대로다() {
		SharedFateMod.config.mainInventoryRows = 3;
		try {
			extra().setClientActive(true);
			Inventory inventory = new Inventory(null, new EntityEquipment());
			ItemStack back = new ItemStack(Items.DIAMOND, 7);

			ExpandedRecipeFill.putBack(inventory, back, false, Prediction.SERVER_ONLY);

			assertTrue(back.isEmpty());
			assertEquals(7, inventory.getItem(0).getCount());
			assertEquals(0, extra().getItems().stream().mapToInt(ItemStack::getCount).sum());
		} finally {
			SharedFateMod.config.mainInventoryRows = 6;
		}
	}

	// ------------------------------------------- 4. 믹스인이 실제로 그 자리에 붙었는가

	/**
	 * refmap 이 없어 대상이 틀려도 <b>빌드는 통과한다.</b> 병합된 메서드 이름으로 본다.
	 *
	 * <p>{@code fabric-loader-junit} 이 {@code sharedfate.mixins.json} 을 그대로 읽으므로,
	 * 여기서 {@code ServerPlaceRecipe} 에 우리 메서드가 보이면 실제로 붙은 것이다.
	 */
	@Test
	void 조합법_자동채우기_믹스인이_실제로_붙는다() {
		Set<String> merged = Arrays.stream(ServerPlaceRecipe.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("sharedfate"))
				.collect(Collectors.toSet());

		assertTrue(merged.stream().anyMatch(name -> name.contains("takeFromExtraSlots")),
				"추가 칸에서 재료 꺼내기가 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("countExtraFreeSlots")),
				"되돌릴 자리 세기가 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("putBackIntoExtraLast")),
				"되돌려 놓기가 병합되어야 한다: " + merged);
	}

	@Test
	void 인벤토리_셈_믹스인이_실제로_붙는다() {
		Set<String> merged = Arrays.stream(Inventory.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("sharedfate"))
				.collect(Collectors.toSet());

		assertTrue(merged.stream().anyMatch(name -> name.contains("fillExtraStackedContents")),
				"조합법 셈에 추가 칸을 더하는 손질이 병합되어야 한다: " + merged);
	}

	/**
	 * 우리가 파고든 바닐라 자리가 <b>그 서술자로</b> 그대로 있는지.
	 *
	 * <p>{@code ITEM_NOT_FOUND} 까지 견주는 이유는
	 * {@link ExpandedRecipeFill#NOT_FOUND} 와 값이 갈리면 「못 찾았다」를 서로 못 알아보고,
	 * 조합칸이 조용히 반만 채워지기 때문이다.
	 */
	@Test
	void 파고드는_바닐라_자리가_그대로다() throws Exception {
		assertDoesNotThrow(() -> ServerPlaceRecipe.class.getDeclaredMethod(
						"moveItemToGrid", Slot.class, Holder.class, int.class),
				"서술자가 바뀌면 추가 칸에서 재료를 꺼내는 손질이 아무 데도 안 붙는다");
		assertDoesNotThrow(() -> ServerPlaceRecipe.class.getDeclaredMethod(
						"getAmountOfFreeSlotsInInventory"),
				"없으면 되돌릴 자리 세기가 안 붙어 조합법을 눌러도 아무 일이 없다");
		assertDoesNotThrow(() -> ServerPlaceRecipe.class.getDeclaredMethod("clearGrid"),
				"없으면 되돌려 놓기가 안 붙어 조합칸의 재료가 바닥에 떨어진다");
		assertDoesNotThrow(() -> Inventory.class.getDeclaredMethod(
						"placeItemBackInInventory", ItemStack.class, boolean.class,
						Prediction.class),
				"@Redirect 의 대상 서술자다 — 틀리면 그 클래스가 로드될 때 터진다");
		assertDoesNotThrow(() -> Inventory.class.getDeclaredMethod(
						"findSlotMatchingCraftingIngredient", Holder.class, ItemStack.class),
				"바닐라가 재료를 고르는 기준을 되풀이하는 근거다");
		assertDoesNotThrow(() -> Inventory.class.getDeclaredMethod(
						"isUsableForCrafting", ItemStack.class),
				"public static 이 아니게 되면 같은 기준으로 거를 수 없다");

		Field itemNotFound = ServerPlaceRecipe.class.getDeclaredField("ITEM_NOT_FOUND");
		itemNotFound.setAccessible(true);
		assertEquals(ExpandedRecipeFill.NOT_FOUND, itemNotFound.getInt(null),
				"「못 찾았다」 값이 갈리면 조합칸이 조용히 반만 채워진다");
	}

	// --------------------------------- 5. 바닐라를 그대로 통과시켜 본다 (실제 호출)

	/**
	 * 병합된 {@code moveItemToGrid} 를 <b>진짜로 불러</b> 추가 칸에서 재료가 나오는지 본다.
	 *
	 * <p>{@code placeRecipe} 통째로는 조합법 정의와 서버 월드가 필요해 여기서 세울 수 없다.
	 * 그래서 그 안에서 재료를 꺼내는 메서드만 직접 부른다 — 믹스인이 붙었는지, 붙은 것이
	 * 바라는 대로 도는지 둘을 함께 확인하는 유일한 길이다.
	 */
	@Test
	void 병합된_바닐라_메서드가_추가_칸에서_재료를_꺼낸다() throws Exception {
		Inventory inventory = activeInventory();
		extra().setItem(0, new ItemStack(Items.DIAMOND, 4));
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);
		ServerPlaceRecipe<?> placer = newPlacer(inventory, List.of(grid));

		Method moveItemToGrid = ServerPlaceRecipe.class.getDeclaredMethod(
				"moveItemToGrid", Slot.class, Holder.class, int.class);
		moveItemToGrid.setAccessible(true);
		int remaining = (int) moveItemToGrid.invoke(placer, grid, diamond(), 3);

		assertEquals(0, remaining, "바닐라 36칸에 없으면 추가 칸을 뒤져야 한다");
		assertEquals(3, grid.getItem().getCount());
		assertEquals(1, extra().getItem(0).getCount());
	}

	/** 바닐라 칸이 먼저다. 위에 있는 재료를 놔두고 아래 것을 꺼내 가면 안 된다. */
	@Test
	void 병합해도_바닐라_칸의_재료를_먼저_쓴다() throws Exception {
		Inventory inventory = activeInventory();
		inventory.setItem(0, new ItemStack(Items.DIAMOND, 4));
		extra().setItem(0, new ItemStack(Items.DIAMOND, 4));
		Slot grid = new Slot(new SimpleContainer(1), 0, 0, 0);
		ServerPlaceRecipe<?> placer = newPlacer(inventory, List.of(grid));

		Method moveItemToGrid = ServerPlaceRecipe.class.getDeclaredMethod(
				"moveItemToGrid", Slot.class, Holder.class, int.class);
		moveItemToGrid.setAccessible(true);
		moveItemToGrid.invoke(placer, grid, diamond(), 2);

		assertEquals(2, inventory.getItem(0).getCount(), "바닐라 칸에서 먼저 꺼내야 한다");
		assertEquals(4, extra().getItem(0).getCount());
	}

	@Test
	void 병합된_자리_세기가_추가_빈칸을_더한다() throws Exception {
		Inventory inventory = activeInventory();
		for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		ServerPlaceRecipe<?> placer = newPlacer(inventory, List.of());

		Method count = ServerPlaceRecipe.class.getDeclaredMethod(
				"getAmountOfFreeSlotsInInventory");
		count.setAccessible(true);

		assertEquals(ExpandedInventoryManager.BASE_EXTRA_SIZE, (int) count.invoke(placer),
				"바닐라가 0이어도 추가 칸의 빈칸만큼은 있어야 조합법을 다시 누를 수 있다");
	}

	@Test
	void 병합된_조합칸_비우기가_추가_칸으로_되돌린다() throws Exception {
		Inventory inventory = activeInventory();
		for (int slot = 0; slot < inventory.getNonEquipmentItems().size(); slot++) {
			inventory.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
		SimpleContainer gridContainer = new SimpleContainer(1);
		gridContainer.setItem(0, new ItemStack(Items.DIAMOND, 5));
		Slot grid = new Slot(gridContainer, 0, 0, 0);
		ServerPlaceRecipe<?> placer = newPlacer(inventory, List.of(grid));

		Method clearGrid = ServerPlaceRecipe.class.getDeclaredMethod("clearGrid");
		clearGrid.setAccessible(true);
		clearGrid.invoke(placer);

		assertEquals(5, extra().getItem(0).getCount(),
				"바닥에 버리는 대신 추가 칸으로 가야 한다");
	}

	/**
	 * 바닐라 {@code ServerPlaceRecipe} 를 시험용으로 하나 세운다. 생성자가 private 이라
	 * 반사로 부른다 — {@code placeRecipe} 는 조합법과 월드를 요구해 쓸 수 없다.
	 */
	private static ServerPlaceRecipe<?> newPlacer(Inventory inventory, List<Slot> gridSlots)
			throws Exception {
		ServerPlaceRecipe.CraftingMenuAccess<CraftingRecipe> access =
				new ServerPlaceRecipe.CraftingMenuAccess<>() {
					@Override
					public void fillCraftSlotsStackedContents(StackedItemContents contents) {
					}

					@Override
					public void clearCraftingContent() {
					}

					@Override
					public boolean recipeMatches(RecipeHolder<CraftingRecipe> recipe) {
						return false;
					}
				};
		Constructor<?> constructor = ServerPlaceRecipe.class.getDeclaredConstructor(
				ServerPlaceRecipe.CraftingMenuAccess.class, Inventory.class, boolean.class,
				int.class, int.class, List.class, List.class);
		constructor.setAccessible(true);
		return (ServerPlaceRecipe<?>) constructor.newInstance(
				access, inventory, false, 1, Math.max(1, gridSlots.size()), gridSlots, gridSlots);
	}
}
