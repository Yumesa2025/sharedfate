package com.sharedfate.inventory;

import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.mixin.AbstractContainerMenuInvoker;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 추가 27칸의 쉬프트 클릭을 <b>모든 화면에서</b> 살리는 공통 자리를 본다.
 *
 * <p>공통 자리는 {@code AbstractContainerMenu.doClick} 안에 있고, {@code doClick} 은 맨 첫 줄에서
 * {@code player.getInventory()} 를 부른다 — 살아 있는 플레이어 없이는 지나갈 수 없다.
 *
 * <h2>대상이 그대로인지 못박기</h2>
 *
 * <p>이 저장소는 refmap 을 만들지 않아 {@code @Mixin} 대상이나 {@code @At} 대상이 틀려도
 * <b>빌드가 그냥 통과</b>한다. 그래서 {@code doClick} 과 {@code quickMoveStack} 의 서술자를
 * 반사로 직접 확인하고, 병합된 메서드 이름으로 실제로 붙었는지도 본다.
 */
class ExpandedQuickMoveFallbackTest {
	private static SharedFateConfig previousConfig;

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
	}

	// --------------------------------------------------------------- 도우미

	private static Inventory openInventory() {
		ExpandedInventoryManager.extraFor(null).setClientActive(true);
		return new Inventory(null, new EntityEquipment());
	}

	/** Mixin 이 지나는 길을 그대로 지난다 — 바닐라를 먼저 부르고, 안 했으면 우리가 옮긴다. */
	private static ItemStack shiftClick(AbstractContainerMenu menu, int index) {
		return ExpandedQuickMoveFallback.quickMove(menu, null, index, menu::quickMoveStack);
	}

	private static int extraStart(AbstractContainerMenu menu) {
		return ((ExpandedMenuLayout) menu).sharedfate$extraSlotStart();
	}

	private static int playerStart(AbstractContainerMenu menu) {
		return ((ExpandedMenuLayout) menu).sharedfate$playerSlotStart();
	}

	/** 메뉴 안에 있는 그 아이템의 총 개수. 복제와 소실을 한 번에 잡는다. */
	private static int totalCount(AbstractContainerMenu menu, net.minecraft.world.item.Item item) {
		int total = 0;
		for (Slot slot : menu.slots) {
			ItemStack stack = slot.getItem();
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/**
	 * 바닐라가 추가 칸 번호를 어느 갈래에도 걸지 못해 아무 일도 하지 않던 화면들.
	 *
	 * <p>대장장이 작업대·양조대·직조기·석재 절단기·화로 계열은 생성자가 {@code inventory.player} 를 거쳐
	 * 세계나 레지스트리를 찾으므로 살아 있는 세계 없이는 만들 수 없다.
	 *
	 * <p>⚠ <b>모루는 여기서 빠졌다.</b> 모루에 다이아몬드 값을 붙이면서 전용 주입
	 * ({@code AnvilMenuQuickMoveMixin})이 그 화면의 쉬프트 클릭을 직접 처리하게 됐고, 그래서
	 * 「바닐라가 손을 놓는다」는 이 목록의 전제가 모루에는 더 이상 맞지 않는다.
	 *
	 * <p>그 대가로 <b>{@code ItemCombinerMenu} 갈래를 지키는 표본이 사라졌다.</b> 예전에는
	 * 모루가 대장장이 작업대 몫까지 대신 지켰다. 그 전용 주입은
	 * {@code instanceof AnvilMenu} 로 가르므로 대장장이 작업대는 여전히 이 폴백이 처리해야
	 * 하는데, 그 화면은 세계 없이 만들 수 없어 시험이 닿지 않는다 — <b>눈으로 확인할 자리다.</b>
	 */
	private static Map<String, Function<Inventory, AbstractContainerMenu>> deadEndScreens() {
		Map<String, Function<Inventory, AbstractContainerMenu>> screens = new LinkedHashMap<>();
		screens.put("연마석", inventory -> new GrindstoneMenu(1, inventory));
		screens.put("지도 제작대", inventory -> new CartographyTableMenu(1, inventory));
		return screens;
	}

	// ------------------------------------------------------- 무한 반복 위험

	/**
	 * 바닐라가 「아무것도 안 옮겼는데 비어 있지 않은 스택」을 돌려주면 {@code doClick} 의
	 * 반복문이 끝나지 않는다. 우리가 그 값을 만들어 내지 않는지 본다.
	 */
	@Test
	void 갈_곳이_없으면_빈_스택을_돌려주어_반복문이_끝난다() {
		Inventory inventory = openInventory();
		GrindstoneMenu menu = new GrindstoneMenu(1, inventory);
		int player = playerStart(menu);
		for (int index = player; index < player + 36; index++) {
			menu.getSlot(index).set(new ItemStack(Items.COBBLESTONE, 64));
		}
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		ItemStack result = shiftClick(menu, extra);

		assertTrue(result.isEmpty(), "옮길 것이 없으면 빈 스택이어야 반복문이 끝난다");
		assertEquals(3, menu.getSlot(extra).getItem().getCount(), "제자리에 그대로 있어야 한다");
	}

	/** 옮긴 만큼 개수가 반드시 줄어야 반복이 유한하다. */
	@Test
	void 옮겼을_때만_원본_사본을_돌려준다() {
		Inventory inventory = openInventory();
		GrindstoneMenu menu = new GrindstoneMenu(1, inventory);
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		ItemStack result = shiftClick(menu, extra);

		assertEquals(3, result.getCount(), "바닐라와 같이 원본 사본을 돌려준다");
		assertTrue(menu.getSlot(extra).getItem().isEmpty(), "출발 칸의 개수가 줄어야 한다");
	}

	// ----------------------------------------------------------- 덮이는 화면

	@Test
	void 갈래에_걸리지_않던_화면들도_추가_칸에서_핫바로_보낸다() {
		for (Map.Entry<String, Function<Inventory, AbstractContainerMenu>> screen
				: deadEndScreens().entrySet()) {
			// 화면마다 새 인벤토리로 열어 앞 화면이 남긴 물건이 섞이지 않게 한다.
			ExpandedInventoryManager.clearRuntimeState();
			AbstractContainerMenu menu = screen.getValue().apply(openInventory());
			String name = screen.getKey();
			int extra = extraStart(menu);
			int player = playerStart(menu);
			assertTrue(extra > 0, name + ": 추가 27칸이 붙어야 한다");
			menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

			ItemStack result = shiftClick(menu, extra);

			assertEquals(3, result.getCount(), name);
			assertTrue(menu.getSlot(extra).getItem().isEmpty(), name + ": 출발 칸이 비어야 한다");
			assertEquals(3, menu.getSlot(player + ExpandedInventoryManager.EXTRA_SIZE)
					.getItem().getCount(), name + ": 핫바 첫 칸으로 가야 한다");
			assertEquals(3, totalCount(menu, Items.DIAMOND), name + ": 개수가 변하면 안 된다");
		}
	}

	/** 바닐라가 확실히 손을 놓고 있었는지 — 우리가 나서지 않으면 정말 아무 일도 없다. */
	@Test
	void 바닐라만으로는_그_화면들에서_아무_일도_일어나지_않는다() {
		for (Map.Entry<String, Function<Inventory, AbstractContainerMenu>> screen
				: deadEndScreens().entrySet()) {
			ExpandedInventoryManager.clearRuntimeState();
			AbstractContainerMenu menu = screen.getValue().apply(openInventory());
			int extra = extraStart(menu);
			menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

			ItemStack vanilla = menu.quickMoveStack(null, extra);

			assertTrue(vanilla.isEmpty(), screen.getKey() + ": 바닐라는 빈 스택을 돌려준다");
			assertEquals(3, menu.getSlot(extra).getItem().getCount(),
					screen.getKey() + ": 바닐라는 이 번호를 모른다");
		}
	}

	// ------------------------------------------------------------ 이중 이동

	@Test
	void 상자처럼_바닐라가_이미_옮기는_화면에서는_나서지_않는다() {
		Inventory inventory = openInventory();
		ChestMenu menu = ChestMenu.threeRows(1, inventory);
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		shiftClick(menu, extra);

		assertEquals(3, menu.getSlot(0).getItem().getCount(), "상자로 들어가야 한다");
		assertEquals(3, totalCount(menu, Items.DIAMOND), "두 번 옮기면 개수가 늘어난다");
	}

	@Test
	void 호퍼와_셜커와_발사기도_바닐라_그대로_한_번만_옮긴다() {
		Inventory inventory = openInventory();
		Map<String, AbstractContainerMenu> screens = new LinkedHashMap<>();
		screens.put("호퍼", new HopperMenu(1, inventory));
		screens.put("셜커 상자", new ShulkerBoxMenu(1, inventory));
		screens.put("발사기", new DispenserMenu(1, inventory));

		for (Map.Entry<String, AbstractContainerMenu> screen : screens.entrySet()) {
			AbstractContainerMenu menu = screen.getValue();
			int extra = extraStart(menu);
			menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

			shiftClick(menu, extra);

			assertEquals(3, menu.getSlot(0).getItem().getCount(),
					screen.getKey() + ": 통 안으로 들어가야 한다");
			assertEquals(3, totalCount(menu, Items.DIAMOND),
					screen.getKey() + ": 두 번 옮기면 개수가 늘어난다");
		}
	}

	@Test
	void 제작대는_기존_Mixin_이_먼저_처리하고_공통_자리는_비켜선다() {
		Inventory inventory = openInventory();
		CraftingMenu menu = new CraftingMenu(1, inventory);
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		shiftClick(menu, extra);

		assertEquals(3, menu.getSlot(1).getItem().getCount(), "제작 격자로 들어가야 한다");
		assertEquals(3, totalCount(menu, Items.DIAMOND), "두 번 옮기면 개수가 늘어난다");
	}

	// --------------------------------------------------------------- 복제 금지

	/**
	 * 신호기와 양조대는 「어느 갈래에도 안 걸린 번호」를 마지막에
	 * {@code moveItemStackTo(플레이어 시작, 플레이어 시작 + 36)} 으로 흘려보낸다. 추가 칸
	 * 번호가 바로 그 경우다. 그 목록에 추가 27칸을 끼워 넣으면 <b>출발 칸이 목적지에 섞여</b>
	 * 자기 자신과 합쳐지고 개수가 두 배가 된다.
	 *
	 * <p>신호기와 양조대는 생성자가 살아 있는 세계를 요구하므로, 두 화면이 부르는 것과
	 * <b>똑같은 호출</b>을 손으로 재현해 본다.
	 */
	@Test
	void 플레이어_36칸_전체로_보내는_호출에서_아이템이_두_배로_늘지_않는다() {
		Inventory inventory = openInventory();
		ChestMenu menu = ChestMenu.threeRows(1, inventory);
		int extra = extraStart(menu);
		int player = playerStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		boolean moved = ((AbstractContainerMenuInvoker) menu).sharedfate$invokeMoveItemStackTo(
				menu.getSlot(extra).getItem(), player, player + 36, false);

		assertTrue(moved);
		assertEquals(3, totalCount(menu, Items.DIAMOND), "복제가 있으면 6이 된다");
		assertTrue(menu.getSlot(extra).getItem().isEmpty(), "플레이어 쪽으로 나가야 한다");
		assertEquals(3, menu.getSlot(player + ExpandedInventoryManager.EXTRA_SIZE)
				.getItem().getCount(), "핫바 첫 칸으로 가야 한다");
	}

	/**
	 * 신호기·양조대의 마지막 갈래를 바닐라 범위 그대로 두면 <b>위 세 줄부터</b> 채워진다.
	 * 위 세 줄에 같은 아이템이 있으면 핫바가 비어 있어도 그리로 합쳐져 올라갔다.
	 */
	@Test
	void 플레이어_36칸_전체로_보내는_호출도_추가_칸에서는_핫바로만_간다() {
		Inventory inventory = openInventory();
		ChestMenu menu = ChestMenu.threeRows(1, inventory);
		int extra = extraStart(menu);
		int player = playerStart(menu);
		menu.getSlot(player).set(new ItemStack(Items.COBBLESTONE, 10));
		menu.getSlot(extra).set(new ItemStack(Items.COBBLESTONE, 20));

		boolean moved = ((AbstractContainerMenuInvoker) menu).sharedfate$invokeMoveItemStackTo(
				menu.getSlot(extra).getItem(), player, player + 36, false);

		assertTrue(moved);
		assertEquals(10, menu.getSlot(player).getItem().getCount(), "위 세 줄로 합쳐지면 안 된다");
		assertEquals(20, menu.getSlot(player + ExpandedInventoryManager.EXTRA_SIZE)
				.getItem().getCount(), "핫바 첫 칸으로 가야 한다");
		assertEquals(30, totalCount(menu, Items.COBBLESTONE));
	}

	@Test
	void 플레이어_36칸_전체로_보내는_호출도_핫바가_차면_추가_칸에_남는다() {
		Inventory inventory = openInventory();
		ChestMenu menu = ChestMenu.threeRows(1, inventory);
		int extra = extraStart(menu);
		int player = playerStart(menu);
		int hotbar = player + ExpandedInventoryManager.EXTRA_SIZE;
		for (int index = hotbar; index < hotbar + ExpandedInventoryManager.EXTRA_COLUMNS; index++) {
			menu.getSlot(index).set(new ItemStack(Items.STONE, 64));
		}
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		boolean moved = ((AbstractContainerMenuInvoker) menu).sharedfate$invokeMoveItemStackTo(
				menu.getSlot(extra).getItem(), player, player + 36, false);

		assertFalse(moved);
		assertEquals(3, menu.getSlot(extra).getItem().getCount());
		assertTrue(menu.getSlot(player).getItem().isEmpty(), "위 세 줄로 올라가면 안 된다");
	}

	/** 공통 자리도 같다 — 위 세 줄의 같은 아이템보다 빈 핫바가 먼저다. */
	@Test
	void 공통_자리는_위_세_줄의_같은_아이템보다_빈_핫바로_보낸다() {
		for (Map.Entry<String, Function<Inventory, AbstractContainerMenu>> screen
				: deadEndScreens().entrySet()) {
			ExpandedInventoryManager.clearRuntimeState();
			AbstractContainerMenu menu = screen.getValue().apply(openInventory());
			String name = screen.getKey();
			int extra = extraStart(menu);
			int player = playerStart(menu);
			menu.getSlot(player).set(new ItemStack(Items.COBBLESTONE, 10));
			menu.getSlot(extra).set(new ItemStack(Items.COBBLESTONE, 20));

			shiftClick(menu, extra);

			assertEquals(10, menu.getSlot(player).getItem().getCount(),
					name + ": 위 세 줄로 합쳐지면 안 된다");
			assertEquals(20, menu.getSlot(player + ExpandedInventoryManager.EXTRA_SIZE)
					.getItem().getCount(), name + ": 핫바 첫 칸으로 가야 한다");
		}
	}

	@Test
	void 공통_자리도_핫바가_가득_차면_제자리에_두고_빈_스택을_돌려준다() {
		for (Map.Entry<String, Function<Inventory, AbstractContainerMenu>> screen
				: deadEndScreens().entrySet()) {
			ExpandedInventoryManager.clearRuntimeState();
			AbstractContainerMenu menu = screen.getValue().apply(openInventory());
			String name = screen.getKey();
			int extra = extraStart(menu);
			int hotbar = playerStart(menu) + ExpandedInventoryManager.EXTRA_SIZE;
			for (int index = hotbar;
					index < hotbar + ExpandedInventoryManager.EXTRA_COLUMNS; index++) {
				menu.getSlot(index).set(new ItemStack(Items.STONE, 64));
			}
			menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

			ItemStack result = shiftClick(menu, extra);

			assertTrue(result.isEmpty(), name + ": 빈 스택이어야 반복이 끝난다");
			assertEquals(3, menu.getSlot(extra).getItem().getCount(), name);
			assertTrue(menu.getSlot(playerStart(menu)).getItem().isEmpty(),
					name + ": 위 세 줄로 올라가면 안 된다");
		}
	}

	/** 목적지 목록에 출발 칸이 섞여 들어와도 자기 자신과 합쳐지면 안 된다. */
	@Test
	void 목적지_목록에_출발_칸이_섞여도_아이템이_늘지_않는다() {
		Inventory inventory = openInventory();
		ChestMenu menu = ChestMenu.threeRows(1, inventory);
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		boolean moved = ExpandedInventoryMoves.move(menu, menu.getSlot(extra).getItem(),
				ExpandedInventoryMoves.order(extra, ExpandedInventoryManager.EXTRA_SIZE), false);

		assertTrue(moved);
		assertEquals(3, totalCount(menu, Items.DIAMOND), "자기 자신과 합치면 6이 된다");
	}

	/**
	 * 결과 칸에는 합쳐지지 않아야 한다.
	 *
	 * <p>바닐라 {@code moveItemStackTo} 는 <b>합치는 첫 벌에서 {@code mayPlace} 를 보지
	 * 않는다.</b> 우리는 결과 칸이 섞인 목록도 넘기므로 여기서도 봐야 한다.
	 */
	@Test
	void 결과_칸에는_합쳐지지_않는다() {
		Inventory inventory = openInventory();
		CraftingMenu menu = new CraftingMenu(1, inventory);
		menu.getSlot(0).set(new ItemStack(Items.DIAMOND, 5));
		ItemStack incoming = new ItemStack(Items.DIAMOND, 3);

		assertFalse(menu.getSlot(0).mayPlace(incoming), "결과 칸은 놓기를 거부한다");
		boolean moved = ExpandedInventoryMoves.move(
				menu, incoming, ExpandedInventoryMoves.order(0, 10), false);

		assertTrue(moved);
		assertEquals(5, menu.getSlot(0).getItem().getCount(), "결과 칸은 그대로여야 한다");
		assertEquals(3, menu.getSlot(1).getItem().getCount(), "제작 격자로 가야 한다");
	}

	// --------------------------------------------------------------- 조기 반환

	@Test
	void 확장을_끄면_공통_자리가_전부_조기_반환한다() {
		SharedFateMod.config.mainInventoryRows = 3;
		try {
			Inventory inventory = new Inventory(null, new EntityEquipment());
			GrindstoneMenu menu = new GrindstoneMenu(1, inventory);

			assertEquals(ExpandedMenuLayout.NONE, extraStart(menu), "추가 칸이 붙지 않는다");
			for (int index = 0; index < menu.slots.size(); index++) {
				assertFalse(ExpandedQuickMoveFallback.fromExtraSlot(menu, index),
						"확장이 꺼져 있으면 어떤 번호도 우리 칸이 아니다");
			}
		} finally {
			SharedFateMod.config.mainInventoryRows = 6;
		}
	}

	@Test
	void 팀이_없으면_추가_칸에서_아무것도_나가지_않는다() {
		ExpandedInventoryManager.extraFor(null).setClientActive(false);
		Inventory inventory = new Inventory(null, new EntityEquipment());
		GrindstoneMenu menu = new GrindstoneMenu(1, inventory);
		int extra = extraStart(menu);
		menu.getSlot(extra).set(new ItemStack(Items.DIAMOND, 3));

		ItemStack result = shiftClick(menu, extra);

		assertTrue(result.isEmpty(), "꺼진 칸에서는 꺼낼 수 없다");
		assertEquals(3, menu.getSlot(extra).getItem().getCount());
	}

	@Test
	void 바닐라_칸에서_시작한_쉬프트_클릭은_손대지_않는다() {
		Inventory inventory = openInventory();
		GrindstoneMenu menu = new GrindstoneMenu(1, inventory);
		int player = playerStart(menu);

		assertFalse(ExpandedQuickMoveFallback.fromExtraSlot(menu, player));
		assertFalse(ExpandedQuickMoveFallback.fromExtraSlot(menu, 0));
		assertFalse(ExpandedQuickMoveFallback.fromExtraSlot(menu, player + 35));
		assertTrue(ExpandedQuickMoveFallback.fromExtraSlot(menu, extraStart(menu)));
		assertTrue(ExpandedQuickMoveFallback.fromExtraSlot(
				menu, extraStart(menu) + ExpandedInventoryManager.EXTRA_SIZE - 1));
		assertFalse(ExpandedQuickMoveFallback.fromExtraSlot(
				menu, extraStart(menu) + ExpandedInventoryManager.EXTRA_SIZE));
	}

	// ------------------------------------------------------------- 대상 못박기

	/**
	 * refmap 이 없으므로 {@code @Mixin} 이나 {@code @At} 대상이 틀려도 빌드는 통과하고
	 * 실행 중에 터진다. 여기서 먼저 터지게 한다.
	 */
	@Test
	void 공통_자리의_대상_서술자가_26_2에_그대로_있다() {
		assertDoesNotThrow(() -> AbstractContainerMenu.class.getDeclaredMethod(
						"doClick", int.class, int.class, ContainerInput.class, Player.class),
				"이 메서드가 사라지면 공통 자리를 붙일 곳이 없다");
		assertDoesNotThrow(() -> {
			Method quickMove = AbstractContainerMenu.class.getDeclaredMethod(
					"quickMoveStack", Player.class, int.class);
			assertEquals(ItemStack.class, quickMove.getReturnType());
		}, "@At 이 가리키는 호출의 서술자가 바뀌면 공통 자리가 통째로 죽는다");
		assertDoesNotThrow(() -> ContainerInput.valueOf("QUICK_MOVE"),
				"쉬프트 클릭 갈래의 이름이 바뀌면 그 자리도 함께 바뀐다");
	}

	@Test
	void 공통_자리가_실제로_병합되어_있다() {
		Set<String> merged = Arrays.stream(AbstractContainerMenu.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("sharedfate"))
				.collect(Collectors.toSet());

		assertTrue(merged.stream().anyMatch(name -> name.contains("quickMoveFallback")),
				"공통 쉬프트 클릭 뒤처리가 병합되어야 한다: " + merged);
	}
}
