package com.sharedfate.enchant;

import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.inventory.ExpandedInventoryManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인챈트의 대가가 경험치 레벨이 아니라 다이아몬드인지 <b>진짜 메뉴로</b> 확인한다.
 *
 * <p>여기서 만드는 것은 바닐라 {@link EnchantmentMenu} 그 자체다. 아래 단언은
 * {@code EnchantmentMenuDiamondMixin} 이 실제로 붙어야만 통과한다 — 붙지 않으면 레벨 0
 * 인 플레이어는 어느 칸도 누르지 못하고, 다이아몬드는 아예 보지도 않는다.
 *
 * <p><b>플레이어를 만드는 방법.</b> {@code Player} 의 생성자는 {@code Level} 을
 * 요구하고, {@code Level} 의 생성자는 생물군계·피해 종류 같은 데이터팩 레지스트리를
 * 요구한다. 시험 한 판을 위해 월드를 통째로 세울 수는 없으므로, 생성자를 건너뛰고
 * <b>이 시험이 실제로 쓰는 세 가지만</b>(인벤토리, 능력, 난수) 채운 껍데기를 쓴다.
 * 인챈트 검사가 플레이어에게서 읽는 것은 그 셋과 {@code experienceLevel}·
 * {@code enchantmentSeed} 뿐이다.
 */
class EnchantmentDiamondCostTest {
	private static SharedFateConfig previousConfig;

	/** {@link Player} 는 추상 클래스라 껍데기가 하나 필요하다. 생성자는 불리지 않는다. */
	private static final class HollowPlayer extends Player {
		private HollowPlayer() {
			super(null, null);
			throw new AssertionError("이 생성자는 절대 불리지 않습니다");
		}

		@Override
		public GameType gameMode() {
			return GameType.SURVIVAL;
		}
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
	}

	// ------------------------------------------------------------------ 메뉴

	@Test
	void 다이아몬드_칸이_청금석_칸_아래에_생긴다() throws Exception {
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);

		int diamondSlot = diamondSlotOf(menu);
		assertNotEquals(EnchantmentDiamondAccess.NO_SLOT, diamondSlot, "다이아몬드 칸이 있어야 한다");
		Slot slot = menu.getSlot(diamondSlot);
		assertEquals(15, slot.x, "아이템 칸(15,47) 과 같은 세로줄이어야 한다");
		assertEquals(65, slot.y, "청금석 칸(35,47) 바로 아래 줄이어야 한다");
		assertTrue(slot.mayPlace(new ItemStack(Items.DIAMOND)), "다이아몬드는 놓을 수 있어야 한다");
		assertFalse(slot.mayPlace(new ItemStack(Items.EMERALD)), "다이아몬드만 받아야 한다");
	}

	@Test
	void 다이아몬드_칸은_바닐라_번호_뒤에_붙는다() throws Exception {
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);

		// 앞에 끼우면 바닐라 quickMoveStack 의 상수 2·38 이 어긋난다.
		assertEquals(0, menu.getSlot(0).getContainerSlot(), "아이템 칸이 0 이어야 한다");
		assertEquals(1, menu.getSlot(1).getContainerSlot(), "청금석 칸이 1 이어야 한다");
		assertEquals(9, menu.getSlot(2).getContainerSlot(), "플레이어 인벤토리는 2 에서 시작한다");
		assertEquals(2 + 36 + 27 + 1, menu.slots.size(),
				"아이템·청금석 2 + 인벤 36 + 추가 27 + 다이아몬드 1");
		assertEquals(38 + 27, diamondSlotOf(menu),
				"다이아몬드 칸은 추가 27칸보다도 뒤여야 한다");
	}

	@Test
	void 인챈트_창에도_추가_27칸이_인벤토리_아래로_붙는다() throws Exception {
		// 좌표를 재는 시험이라 27칸이 다 열린 팀을 가정한다. 잠긴 칸은 화면 밖으로 치워진다.
		ExpandedInventoryManager.setClientUnlockedSlots(ExpandedInventoryManager.EXTRA_SIZE);
		Player player = hollowPlayer(false);
		ExpandedInventoryManager.extraFor(player).setClientActive(true);
		EnchantmentMenu menu = readyMenu(player, 0);

		int inventoryTop = menu.getSlot(2).y;
		assertEquals(84, inventoryTop, "바닐라 인벤토리 첫 줄은 84 다");
		for (int extraIndex = 0; extraIndex < 27; extraIndex++) {
			Slot slot = menu.getSlot(38 + extraIndex);
			assertEquals(8 + (extraIndex % 9) * 18, slot.x);
			assertEquals(inventoryTop + 54 + (extraIndex / 9) * 18, slot.y);
		}
		assertEquals(inventoryTop + 112, menu.getSlot(29).y, "핫바가 아래로 내려가야 한다");
	}

	@Test
	void 다이아몬드가_아홉_개면_레벨이_아무리_있어도_세_칸_모두_막힌다() throws Exception {
		Player player = hollowPlayer(false);
		player.experienceLevel = 100;
		EnchantmentMenu menu = readyMenu(player, 3);
		giveToSlot(menu, EnchantmentDiamondCost.DIAMONDS_PER_ENCHANT - 1);

		for (int slot = 0; slot < 3; slot++) {
			assertFalse(menu.clickMenuButton(player, slot),
					slot + "번 칸이 다이아몬드 9개로 눌리면 안 된다");
		}
	}

	@Test
	void 인벤토리에_다이아몬드가_많아도_칸이_비면_막힌다() throws Exception {
		// 다이아몬드는 칸에 넣어야만 센다.
		Player player = hollowPlayer(false);
		player.getInventory().getNonEquipmentItems().set(0, new ItemStack(Items.DIAMOND, 64));
		EnchantmentMenu menu = readyMenu(player, 3);

		assertFalse(menu.clickMenuButton(player, 0), "칸이 비었으면 눌리면 안 된다");
	}

	@Test
	void 다이아몬드가_열_개면_레벨이_0이어도_세_칸_모두_눌린다() throws Exception {
		Player player = hollowPlayer(false);
		player.experienceLevel = 0;
		EnchantmentMenu menu = readyMenu(player, 3);
		giveToSlot(menu, EnchantmentDiamondCost.DIAMONDS_PER_ENCHANT);

		for (int slot = 0; slot < 3; slot++) {
			assertTrue(menu.clickMenuButton(player, slot),
					slot + "번 칸이 레벨 0·다이아몬드 10개로 눌려야 한다");
		}
	}

	/**
	 * 값이 1개인 팀은 다이아몬드 하나로 인챈트한다.
	 *
	 * <p>증강 할인과 세트 할인을 둘 다 가진 팀이 내는 개수가 1
	 * ({@link EnchantmentDiamondCost#DIAMONDS_WITH_BOTH_DISCOUNTS})이다. 그 값이 화면까지
	 * 내려온 뒤 탁자에서 실제로 통하는지 본다 — 검사에 하한이 잘못 들어가 있으면 여기서 막힌다.
	 */
	@Test
	void 값이_1개면_다이아몬드_하나로_눌린다() throws Exception {
		Player player = hollowPlayer(false);
		player.experienceLevel = 0;
		EnchantmentMenu menu = readyMenu(player, 3);
		giveToSlot(menu, 1);
		try {
			EnchantmentDiamondCost.rememberShown(
					EnchantmentDiamondCost.DIAMONDS_WITH_BOTH_DISCOUNTS);

			assertEquals(1, EnchantmentDiamondCost.forSlot(0), "1 이 그대로 내려와야 한다");
			for (int slot = 0; slot < 3; slot++) {
				assertTrue(menu.clickMenuButton(player, slot),
						slot + "번 칸이 다이아몬드 1개로 눌려야 한다");
			}
		} finally {
			EnchantmentDiamondCost.resetShown();
		}
	}

	@Test
	void 청금석이_모자라면_그_칸만_막힌다() throws Exception {
		Player player = hollowPlayer(false);
		player.experienceLevel = 0;
		EnchantmentMenu menu = readyMenu(player, 1);
		giveToSlot(menu, 64);

		assertTrue(menu.clickMenuButton(player, 0), "청금석 1개면 첫 칸은 눌린다");
		assertFalse(menu.clickMenuButton(player, 1), "둘째 칸은 청금석 2개가 필요하다");
		assertFalse(menu.clickMenuButton(player, 2), "셋째 칸은 청금석 3개가 필요하다");
	}

	@Test
	void 접근자가_비어_있으면_눌려도_다이아몬드가_사라지지_않는다() throws Exception {
		// 클라이언트의 접근자가 바로 ContainerLevelAccess.NULL 이다. 여기서 다이아몬드가
		// 줄면 서버와 즉시 어긋난다 — 차감이 execute 람다 밖으로 새어 나온 것이다.
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 3);
		giveToSlot(menu, 12);

		assertTrue(menu.clickMenuButton(player, 0));

		assertEquals(12, EnchantmentDiamondCost.countIn(menu), "람다가 돌지 않았으면 걷지 않아야 한다");
		assertEquals(3, menu.getGoldCount(), "청금석도 그대로여야 한다");
	}

	@Test
	void 크리에이티브는_다이아몬드가_하나도_없어도_눌린다() throws Exception {
		Player player = hollowPlayer(true);
		player.experienceLevel = 0;
		EnchantmentMenu menu = readyMenu(player, 0);

		assertTrue(menu.clickMenuButton(player, 2), "크리에이티브는 청금석도 다이아몬드도 필요 없다");
	}

	@Test
	void 인챈트_메뉴에_다이아몬드_Mixin_이_실제로_붙는다() {
		Set<String> merged = Arrays.stream(EnchantmentMenu.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("sharedfate"))
				.collect(Collectors.toSet());

		assertTrue(merged.stream().anyMatch(name -> name.contains("requireDiamonds")),
				"다이아몬드 검사가 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("dropLevelRequirement")),
				"요구 레벨 무력화가 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("chargeDiamonds")),
				"다이아몬드 차감이 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("addDiamondSlot")),
				"다이아몬드 칸 추가가 병합되어야 한다: " + merged);
		assertTrue(merged.stream().anyMatch(name -> name.contains("returnDiamonds")),
				"창을 닫을 때 돌려주는 코드가 병합되어야 한다: " + merged);
	}

	@Test
	void 다이아몬드를_쉬프트_클릭하면_다이아몬드_칸으로_들어간다() throws Exception {
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);
		menu.getSlot(2).set(new ItemStack(Items.DIAMOND, 7));

		menu.quickMoveStack(player, 2);

		assertEquals(7, EnchantmentDiamondCost.countIn(menu),
				"인챈트할 물건 칸이 아니라 다이아몬드 칸으로 가야 한다");
		assertTrue(menu.getSlot(0).getItem().is(Items.DIAMOND_SWORD), "아이템 칸은 그대로여야 한다");
	}

	@Test
	void 다이아몬드_칸에서_쉬프트_클릭하면_인벤토리로_돌아온다() throws Exception {
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);
		giveToSlot(menu, 7);

		menu.quickMoveStack(player, diamondSlotOf(menu));

		assertEquals(0, EnchantmentDiamondCost.countIn(menu));
		assertEquals(7, menu.getSlot(2).getItem().getCount(), "플레이어 인벤토리 첫 칸으로 와야 한다");
	}

	@Test
	void 추가_칸에서_쉬프트_클릭하면_위_세_줄이_아니라_핫바로_간다() throws Exception {
		Player player = hollowPlayer(false);
		ExpandedInventoryManager.extraFor(player).setClientActive(true);
		EnchantmentMenu menu = readyMenu(player, 0);
		// 플레이어 2~28 세 줄, 29~37 핫바, 38~ 추가 칸.
		menu.getSlot(2).set(new ItemStack(Items.COBBLESTONE, 10));
		menu.getSlot(38).set(new ItemStack(Items.COBBLESTONE, 20));

		menu.quickMoveStack(player, 38);

		assertEquals(20, menu.getSlot(29).getItem().getCount(), "핫바 첫 칸으로 가야 한다");
		assertEquals(10, menu.getSlot(2).getItem().getCount(), "위 세 줄로 합쳐지면 안 된다");
		assertTrue(menu.getSlot(38).getItem().isEmpty());
	}

	// ------------------------------------------------------------- 레벨 소모

	@Test
	void 인챈트를_해도_경험치_레벨이_줄지_않고_씨앗만_바뀐다() throws Exception {
		Player player = hollowPlayer(false);
		player.experienceLevel = 7;
		int seedBefore = player.getEnchantmentSeed();

		player.onEnchantmentPerformed(new ItemStack(Items.BOOK), 3);

		assertEquals(7, player.experienceLevel, "레벨을 깎으면 안 된다");
		assertNotEquals(seedBefore, player.getEnchantmentSeed(),
				"씨앗을 갱신하지 않으면 같은 인챈트 후보가 계속 뜬다");
	}

	// --------------------------------------------------------------- 차감

	@Test
	void 다이아몬드는_칸에서만_걷고_남은_것은_그대로_남는다() throws Exception {
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);
		giveToSlot(menu, 14);

		assertEquals(10, EnchantmentDiamondCost.consume(
				player, EnchantmentDiamondCost.containerOf(menu), 0));

		assertEquals(4, EnchantmentDiamondCost.countIn(menu), "10개만 걷어야 한다");
	}

	@Test
	void 크리에이티브에서는_다이아몬드를_걷지_않는다() throws Exception {
		Player player = hollowPlayer(true);
		EnchantmentMenu menu = readyMenu(player, 0);
		giveToSlot(menu, 9);

		assertEquals(0, EnchantmentDiamondCost.consume(
				player, EnchantmentDiamondCost.containerOf(menu), 0));
		assertEquals(9, EnchantmentDiamondCost.countIn(menu));
	}

	// --------------------------------------------------------------- 표시

	@Test
	void 단추_숫자는_요구_레벨_대신_다이아몬드_개수가_되고_빈_칸은_0으로_남는다() {
		assertArrayEqualsInt(new int[] {10, 10, 10},
				EnchantmentDiamondCost.displayCosts(new int[] {3, 12, 27}));
		assertArrayEqualsInt(new int[] {0, 10, 0},
				EnchantmentDiamondCost.displayCosts(new int[] {0, 9, 0}));
	}

	@Test
	void 세_칸의_다이아몬드_요구량이_같다() {
		assertEquals(10, EnchantmentDiamondCost.forSlot(0));
		assertEquals(10, EnchantmentDiamondCost.forSlot(1));
		assertEquals(10, EnchantmentDiamondCost.forSlot(2));
	}

	@Test
	void 요구_개수를_화면으로_내려보내는_데이터_칸이_붙는다() throws Exception {
		// enchant_cost 증강으로 개수가 달라져도 단추와 툴팁이 같은 숫자를 그리려면,
		// 서버가 계산한 값이 클라이언트까지 가야 한다. 그 통로가 이 칸이다.
		Player player = hollowPlayer(false);
		EnchantmentMenu menu = readyMenu(player, 0);
		try {
			int index = costDataSlotOf(menu);
			assertNotEquals(-1, index, "요구 개수를 실어 보낼 데이터 칸이 있어야 한다");
			assertEquals(10, dataSlotsOf(menu).get(index).get(), "증강이 없으면 기본값을 보낸다");

			// 클라이언트가 꾸러미를 받았을 때와 같은 자리다.
			menu.setData(index, 1);

			assertEquals(1, EnchantmentDiamondCost.shownDiamonds());
			assertEquals(1, EnchantmentDiamondCost.forSlot(0), "툴팁·단추도 같은 값을 본다");
		} finally {
			EnchantmentDiamondCost.resetShown();
		}
	}

	private static int costDataSlotOf(EnchantmentMenu menu) throws Exception {
		List<DataSlot> slots = dataSlotsOf(menu);
		for (int index = 0; index < slots.size(); index++) {
			if (slots.get(index) instanceof EnchantmentCostDataSlot) {
				return index;
			}
		}
		return -1;
	}

	@SuppressWarnings("unchecked")
	private static List<DataSlot> dataSlotsOf(EnchantmentMenu menu) throws Exception {
		Field field = AbstractContainerMenu.class.getDeclaredField("dataSlots");
		field.setAccessible(true);
		return (List<DataSlot>) field.get(menu);
	}

	private static void assertArrayEqualsInt(int[] expected, int[] actual) {
		assertEquals(Arrays.toString(expected), Arrays.toString(actual));
	}

	// --------------------------------------------------------------- 도구

	private static EnchantmentMenu readyMenu(Player player, int lapis) {
		EnchantmentMenu menu = new EnchantmentMenu(1, player.getInventory());
		// 인챈트할 물건이 먼저 들어가야 한다. 비어 있으면 바닐라가 costs 를 0 으로 지운다.
		menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
		if (lapis > 0) {
			menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, lapis));
		}
		// costs 는 서버가 월드를 보며 계산한다. 여기서는 월드가 없으므로 직접 채운다.
		menu.costs[0] = 5;
		menu.costs[1] = 12;
		menu.costs[2] = 27;
		return menu;
	}

	/** 다이아몬드를 <b>칸에</b> 넣는다. 인벤토리에 넣는 것으로는 세지 않는다. */
	private static void giveToSlot(EnchantmentMenu menu, int diamonds) {
		menu.getSlot(diamondSlotOf(menu)).set(new ItemStack(Items.DIAMOND, diamonds));
	}

	private static int diamondSlotOf(EnchantmentMenu menu) {
		return ((EnchantmentDiamondAccess) menu).sharedfate$diamondMenuSlot();
	}

	private static Player hollowPlayer(boolean creative) throws Exception {
		Player player = (Player) unsafe().allocateInstance(HollowPlayer.class);
		setField(Entity.class, player, "random", RandomSource.create(20260901L));
		Abilities abilities = new Abilities();
		abilities.instabuild = creative;
		setField(Player.class, player, "abilities", abilities);
		setField(Player.class, player, "inventory", new Inventory(player, new EntityEquipment()));
		return player;
	}

	private static void setField(Class<?> owner, Object target, String name, Object value)
			throws Exception {
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
