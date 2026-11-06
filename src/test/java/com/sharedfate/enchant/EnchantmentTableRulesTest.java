package com.sharedfate.enchant;

import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.perk.PerkSetType;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 인챈트 탁자의 「책장 6개 · 세 칸 모두 30레벨 · 청금석 3개」 규칙을 본다.
 *
 * <h2>시험 환경에서 바닐라를 돌리는 법</h2>
 *
 * <p>시험 환경에는 태그가 묶여 있지 않다(「Tags not bound」). 그래서
 * <ul>
 *   <li>책장 판정({@code isValidBookShelf})이 쓰는 블록 태그는 <b>책장·공기·돌 세 블록에만</b> 잠깐
 *       묶었다가 시험이 끝나면 되돌린다.</li>
 *   <li>일반 장비의 후보 목록은 아이템 태그가 필요해 시험에서 뽑을 수 없다. 대신 바닐라가 후보를
 *       뽑을 때 넘기는 등급이 곧 {@code costs[칸]} 이므로(바이트코드로 확인), 세 칸의 {@code costs}
 *       가 30 인지를 본다 — 그것이 바닐라 30레벨 뽑기와 같다는 뜻이다.</li>
 *   <li>방패의 후보는 이 모드가 직접 뽑으므로(태그를 쓰지 않는다) 실제 단서까지 확인한다.</li>
 * </ul>
 *
 * <p>월드는 {@link ServerLevel} 을 생성자 없이 만든 것이고, 시험이 쓰는 몇 메서드만 덮어 쓴다.
 */
class EnchantmentTableRulesTest {
	private static SharedFateConfig previousConfig;
	private static final BlockPos TABLE = BlockPos.ZERO;

	/** 클라이언트의 {@code LocalPlayer} 처럼 {@code ServerPlayer} 가 아닌 사람. 생성자는 불리지 않는다. */
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

	/** 책장 자리와 막힌 자리만 아는 월드. 생성자는 불리지 않는다. */
	private static final class TableLevel extends ServerLevel {
		Set<BlockPos> shelves;
		Set<BlockPos> blocked;
		RegistryAccess testRegistries;
		RandomSource testRandom;

		private TableLevel() {
			super(null, null, null, null, null, null, false, 0L, null, false);
			throw new AssertionError("이 생성자는 절대 불리지 않습니다");
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			if (shelves.contains(pos)) return Blocks.BOOKSHELF.defaultBlockState();
			if (blocked.contains(pos)) return Blocks.STONE.defaultBlockState();
			return Blocks.AIR.defaultBlockState();
		}

		@Override
		public RegistryAccess registryAccess() {
			return testRegistries;
		}

		@Override
		public RandomSource getRandom() {
			return testRandom;
		}

		@Override
		public void playSound(Entity entity, BlockPos pos, SoundEvent sound, SoundSource source,
				float volume, float pitch) {
		}
	}

	private final Map<Holder.Reference<Block>, Object> savedTags = new HashMap<>();

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
		previousConfig = SharedFateMod.config;
		SharedFateMod.config = new SharedFateConfig();
	}

	@AfterAll
	static void tearDown() {
		SharedFateMod.config = previousConfig;
	}

	@BeforeEach
	void load() throws Exception {
		ExpandedInventoryManager.clearRuntimeState();
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		EnchantmentDiamondCost.resetShown();
		bindTags(Blocks.BOOKSHELF, Set.of(BlockTags.ENCHANTMENT_POWER_PROVIDER));
		bindTags(Blocks.AIR, Set.of(BlockTags.ENCHANTMENT_POWER_TRANSMITTER));
		bindTags(Blocks.STONE, Set.of());
	}

	@AfterEach
	void clear() throws Exception {
		for (var entry : savedTags.entrySet()) {
			setField(Holder.Reference.class, entry.getKey(), "tags", entry.getValue());
		}
		savedTags.clear();
		TeamLookup.setServer(null);
		ExpandedInventoryManager.clearRuntimeState();
		PerkRegistry.clear();
		PerkSetRegistry.clear();
		EnchantmentDiamondCost.resetShown();
	}

	// ------------------------------------------------------------------ 순수 규칙

	@Test
	void 책장_다섯은_등급이_없고_여섯부터는_언제나_30이다() {
		assertEquals(0, EnchantmentTableRules.grade(0));
		assertEquals(0, EnchantmentTableRules.grade(5));
		assertEquals(30, EnchantmentTableRules.grade(6));
		assertEquals(30, EnchantmentTableRules.grade(15), "6개를 넘는 책장은 아무것도 더 주지 않는다");
	}

	@Test
	void 책장_안내_문구는_6에서_자른다() {
		assertEquals("책장이 부족합니다 (0/6)", EnchantmentTableRules.shelfLabel(0));
		assertEquals("책장이 부족합니다 (5/6)", EnchantmentTableRules.shelfLabel(5));
		assertEquals("책장 6/6", EnchantmentTableRules.shelfLabel(6));
		assertEquals("책장 6/6", EnchantmentTableRules.shelfLabel(15));
		assertTrue(EnchantmentTableRules.lacksShelves(5));
		assertFalse(EnchantmentTableRules.lacksShelves(6));
	}

	/** 화면은 바닐라의 「청금석 ≥ 칸 번호 + 1」 비교를 그대로 쓴다. 세 칸이 함께 죽고 함께 살아야 한다. */
	@Test
	void 화면의_청금석_비교는_세_칸이_함께_3개_기준이다() {
		for (int slot = 0; slot < 3; slot++) {
			assertFalse(EnchantmentTableRules.effectiveLapis(2) >= slot + 1, "청금석 2개면 " + slot + "번 칸도 죽는다");
			assertTrue(EnchantmentTableRules.effectiveLapis(3) >= slot + 1, "청금석 3개면 " + slot + "번 칸이 산다");
		}
		assertEquals(64, EnchantmentTableRules.effectiveLapis(64));
	}

	/**
	 * 화면 Mixin 이 실제로 붙었는지 본다. 주입 자리마다 {@code require = 1} 이라 자리가 어긋나면 화면
	 * 클래스를 읽는 순간 터지고, 붙었다면 처리기가 화면 클래스 안에 들어 있다.
	 */
	@Test
	void 화면의_청금석_주입이_붙는다() throws Exception {
		var methods = java.util.Arrays.stream(Class.forName(
				"net.minecraft.client.gui.screens.inventory.EnchantmentScreen").getDeclaredMethods())
				.map(java.lang.reflect.Method::getName).toList();
		for (String handler : List.of("threeLapisOnButtons", "threeLapisInTooltip",
				"threeLapisSprites", "threeLapisDisabledSprites", "showGrades")) {
			assertTrue(methods.stream().anyMatch(name -> name.endsWith("sharedfate$" + handler)), handler);
		}
	}

	// ------------------------------------------------------------------ 바닐라 메뉴

	@Test
	void 책장이_다섯이면_세_칸이_모두_빈다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			TableLevel level = level(5, Set.of());
			EnchantmentMenu menu = menu(player, level);

			for (ItemStack stack : List.of(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.BOOK),
					new ItemStack(Items.SHIELD))) {
				menu.getSlot(0).set(stack);
				assertArrayEquals(new int[] {0, 0, 0}, menu.costs, stack + " 도 책장 다섯으로는 안 된다");
				assertArrayEquals(new int[] {-1, -1, -1}, menu.enchantClue);
			}
			assertEquals(5, ((EnchantmentTableAccess) menu).sharedfate$bookshelves());
		}
	}

	@Test
	void 가운데가_막힌_책장은_세지_않는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			BlockPos first = EnchantingTableBlock.BOOKSHELF_OFFSETS.getFirst();
			TableLevel level = level(6, Set.of(TABLE.offset(first.getX() / 2, first.getY(), first.getZ() / 2)));
			EnchantmentMenu menu = menu(player, level);

			menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));

			assertTrue(((EnchantmentTableAccess) menu).sharedfate$bookshelves() < 6, "바닐라 규칙대로 막힌 책장이 빠진다");
			assertArrayEquals(new int[] {0, 0, 0}, menu.costs);
		}
	}

	@Test
	void 책장이_여섯이면_일반_장비와_책은_세_칸_모두_30레벨로_뽑는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(0));
			EnchantmentMenu menu = menu(player, level(6, Set.of()));

			menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
			assertArrayEquals(new int[] {30, 30, 30}, menu.costs);

			menu.getSlot(0).set(new ItemStack(Items.BOOK));
			assertArrayEquals(new int[] {30, 30, 30}, menu.costs, "책도 똑같이 30레벨이다");
		}
	}

	@Test
	void 책장이_열다섯이어도_30레벨_그대로다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(0));
			EnchantmentMenu menu = menu(player, level(15, Set.of()));

			menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));

			assertArrayEquals(new int[] {30, 30, 30}, menu.costs);
		}
	}

	@Test
	void 방패는_세_칸_모두_30레벨이고_칸마다_단서가_따로_뽑힌다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			EnchantmentMenu menu = menu(player, level(6, Set.of()));
			boolean differs = false;

			for (int seed = 0; seed < 50 && !differs; seed++) {
				menu.setData(3, seed);
				menu.getSlot(0).set(new ItemStack(Items.SHIELD));
				assertArrayEquals(new int[] {30, 30, 30}, menu.costs);
				int[] clues = menu.enchantClue;
				int[] levels = menu.levelClue;
				boolean allShown = clues[0] >= 0 && clues[1] >= 0 && clues[2] >= 0;
				differs = allShown && (clues[0] != clues[1] || clues[1] != clues[2]
						|| levels[0] != levels[1] || levels[1] != levels[2]);
			}

			assertTrue(differs, "세 칸은 칸마다 다른 씨앗으로 따로 뽑혀야 한다");
		}
	}

	@Test
	void 청금석이_둘이면_어느_칸도_누를_수_없다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			EnchantmentMenu menu = menu(player, level(6, Set.of()));
			menu.getSlot(0).set(new ItemStack(Items.SHIELD));
			menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 2));
			giveDiamonds(menu, 64);

			for (int slot = 0; slot < 3; slot++) {
				assertFalse(menu.clickMenuButton(player, slot), slot + "번 칸");
			}
			assertEquals(2, menu.getGoldCount());
			assertFalse(menu.getSlot(0).getItem().isEnchanted());
			assertEquals(64, EnchantmentDiamondCost.countIn(menu));
		}
	}

	@Test
	void 청금석이_셋이면_어느_칸이든_세_개를_걷는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			for (int slot = 0; slot < 3; slot++) {
				TableLevel level = level(6, Set.of());
				EnchantmentMenu menu = menu(player, level);
				menu.setData(3, seedWithCandidates(level, slot));
				menu.getSlot(0).set(new ItemStack(Items.SHIELD));
				menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 5));
				giveDiamonds(menu, 64);

				assertTrue(menu.clickMenuButton(player, slot), slot + "번 칸");

				assertEquals(2, menu.getGoldCount(), slot + "번 칸도 청금석 3개를 걷는다");
				assertTrue(menu.getSlot(0).getItem().isEnchanted());
				assertEquals(64 - EnchantmentDiamondCost.DIAMONDS_PER_ENCHANT, EnchantmentDiamondCost.countIn(menu));
			}
		}
	}

	@Test
	void 청금석이_딱_셋이면_칸이_비워진다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			Player player = playerInTeam(storage, shieldSetPerks(3));
			TableLevel level = level(6, Set.of());
			EnchantmentMenu menu = menu(player, level);
			menu.setData(3, seedWithCandidates(level, 0));
			menu.getSlot(0).set(new ItemStack(Items.SHIELD));
			menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
			giveDiamonds(menu, 64);

			assertTrue(menu.clickMenuButton(player, 0));

			assertTrue(menu.getSlot(1).getItem().isEmpty());
		}
	}

	// ------------------------------------------------------------------ 도움

	/** 이 칸의 방패 후보가 비지 않는 씨앗. 시험 등록부에는 이 모드의 방패 전용 마법부여가 없다. */
	private static int seedWithCandidates(TableLevel level, int slot) {
		for (int seed = 0; seed < 1000; seed++) {
			if (!ShieldEnchantments.select(level.testRegistries, (long) seed + slot).isEmpty()) return seed;
		}
		throw new AssertionError("후보가 있는 씨앗을 찾지 못했습니다");
	}

	private static EnchantmentMenu menu(Player player, TableLevel level) {
		return new EnchantmentMenu(1, player.getInventory(), ContainerLevelAccess.create(level, TABLE));
	}

	/** 책장 n 개를 바닐라 자리 앞쪽부터 놓은 월드. */
	private static TableLevel level(int shelfCount, Set<BlockPos> blocked) throws Exception {
		TableLevel level = (TableLevel) unsafe().allocateInstance(TableLevel.class);
		level.shelves = new HashSet<>();
		for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS.subList(0, shelfCount)) {
			level.shelves.add(TABLE.offset(offset));
		}
		level.blocked = blocked;
		level.testRegistries = enchantmentRegistries();
		level.testRandom = RandomSource.create(20261106L);
		return level;
	}

	/**
	 * 인챈트 등록부만 아는 {@link RegistryAccess}.
	 *
	 * <p>바닐라 인챈트는 시험 조회기에서 찾는다. 인챈트 태그는 묶여 있지 않으므로 「없음」으로 답한다 —
	 * 그러면 바닐라 후보 뽑기는 빈 목록을 돌려줄 뿐 터지지 않는다.
	 */
	private static RegistryAccess enchantmentRegistries() {
		var lookup = TestBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
		List<Object> ids = new ArrayList<>();
		IdMap<?> idMap = (IdMap<?>) Proxy.newProxyInstance(IdMap.class.getClassLoader(), new Class<?>[] {IdMap.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getId" -> {
						Object key = ((Holder<?>) args[0]).unwrapKey().orElseThrow();
						if (!ids.contains(key)) ids.add(key);
						yield ids.indexOf(key);
					}
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "시험용 IdMap";
					default -> throw new UnsupportedOperationException(method.getName());
				});
		Registry<?> registry = (Registry<?>) Proxy.newProxyInstance(Registry.class.getClassLoader(),
				new Class<?>[] {Registry.class}, (proxy, method, args) -> switch (method.getName()) {
					case "get" -> {
						if (args[0] instanceof Identifier id) {
							yield lookup.get(ResourceKey.create(Registries.ENCHANTMENT, id));
						}
						if (args[0] instanceof TagKey<?>) yield Optional.empty();
						throw new UnsupportedOperationException("get " + args[0]);
					}
					case "asHolderIdMap" -> idMap;
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "시험용 인챈트 등록부";
					default -> throw new UnsupportedOperationException(method.getName());
				});
		return (RegistryAccess) Proxy.newProxyInstance(RegistryAccess.class.getClassLoader(),
				new Class<?>[] {RegistryAccess.class}, (proxy, method, args) -> switch (method.getName()) {
					case "lookupOrThrow" -> registry;
					case "lookup" -> Optional.of(registry);
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					case "toString" -> "시험용 RegistryAccess";
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	@SuppressWarnings("unchecked")
	private void bindTags(Block block, Set<TagKey<Block>> tags) throws Exception {
		Holder.Reference<Block> holder = block.builtInRegistryHolder();
		Field field = Holder.Reference.class.getDeclaredField("tags");
		field.setAccessible(true);
		savedTags.putIfAbsent(holder, field.get(holder));
		field.set(holder, tags);
	}

	/** 세트 유형이 방패인 증강을 n 개 가진 팀. */
	private static TeamState shieldSetPerks(int count) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		PerkRegistry.all().stream().filter(perk -> perk.setTypes().contains(PerkSetType.SHIELD))
				.limit(count).forEach(perk -> state.ownedPerks.add(perk.id()));
		assertEquals(count, state.ownedPerks.size());
		return state;
	}

	private static void giveDiamonds(EnchantmentMenu menu, int count) {
		menu.getSlot(((EnchantmentDiamondAccess) menu).sharedfate$diamondMenuSlot())
				.set(new ItemStack(Items.DIAMOND, count));
	}

	private static Player playerInTeam(SavedDataStorage storage, TeamState state) throws Exception {
		var server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
		Player player = (Player) unsafe().allocateInstance(HollowPlayer.class);
		UUID id = UUID.randomUUID();
		setField(Entity.class, player, "uuid", id);
		setField(Entity.class, player, "random", RandomSource.create(20261106L));
		setField(Player.class, player, "abilities", new Abilities());
		setField(Player.class, player, "inventory", new Inventory(player, new EntityEquipment()));
		TeamManager.get(server).createTeam("방패", id, state);
		TeamLookup.setServer(server);
		return player;
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
