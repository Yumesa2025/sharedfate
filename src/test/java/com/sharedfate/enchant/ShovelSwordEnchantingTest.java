package com.sharedfate.enchant;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.perk.effect.ToolCraftEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.IdMap;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
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

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「삽질의 대가」의 「삽에 검 마법부여가 붙습니다」를 본다.
 *
 * <h2>시험 환경에서 바닐라 후보 뽑기를 돌리는 법</h2>
 *
 * <p>시험 환경에는 태그가 묶여 있지 않다(「Tags not bound」). 바닐라는 후보를 아이템 태그
 * ({@code supported_items}·{@code primary_items})로, 배타 관계를 인챈트 태그로 가른다. 그래서
 * 마인크래프트 JAR 안의 <b>진짜 태그 파일</b>({@code data/minecraft/tags/...})을 읽어 풀고, 그 결과를
 * 쓰는 물건(삽·검)과 인챈트 홀더에만 잠깐 묶었다가 시험이 끝나면 되돌린다. 책장 판정의 블록 태그는
 * {@link EnchantmentTableRulesTest} 와 같은 방법으로 묶는다.
 *
 * <p>그러면 마법부여대의 슬롯 단서(clue)와 실제로 붙는 마법부여가 <b>바닐라 30레벨 뽑기 그대로</b>
 * 나온다 — 대역 검이 고른 후보가 바닐라 검 후보와 같은지까지 볼 수 있다.
 */
class ShovelSwordEnchantingTest {
	private static final BlockPos TABLE = BlockPos.ZERO;
	private static SharedFateConfig previousConfig;

	/** 마법부여대에서 검이 받을 수 있는 것. 보물(수선·저주)은 탁자에 나오지 않는다. */
	private static final Set<String> SWORD_TABLE = Set.of("minecraft:sharpness", "minecraft:smite",
			"minecraft:bane_of_arthropods", "minecraft:knockback", "minecraft:fire_aspect", "minecraft:looting",
			"minecraft:sweeping_edge", "minecraft:unbreaking");
	/** 삽에만 붙는(검은 받지 못하는) 것. */
	private static final Set<String> SHOVEL_ONLY = Set.of("minecraft:efficiency", "minecraft:silk_touch",
			"minecraft:fortune");

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

	/** 책장 여섯 개를 두른 월드. 생성자는 불리지 않는다. */
	private static final class TableLevel extends ServerLevel {
		Set<BlockPos> shelves;
		RegistryAccess testRegistries;
		RandomSource testRandom;

		private TableLevel() {
			super(null, null, null, null, null, null, false, 0L, null, false);
			throw new AssertionError("이 생성자는 절대 불리지 않습니다");
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return shelves.contains(pos) ? Blocks.BOOKSHELF.defaultBlockState() : Blocks.AIR.defaultBlockState();
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

	private final Map<Holder.Reference<?>, Object> savedTags = new HashMap<>();
	private HolderSet.Named<Enchantment> tableSet;
	private final List<Object> clueIds = new ArrayList<>();

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
		bind(Blocks.BOOKSHELF.builtInRegistryHolder(), Set.of(BlockTags.ENCHANTMENT_POWER_PROVIDER));
		bind(Blocks.AIR.builtInRegistryHolder(), Set.of(BlockTags.ENCHANTMENT_POWER_TRANSMITTER));
		bindVanillaTags();
	}

	@AfterEach
	void clear() throws Exception {
		Field field = Holder.Reference.class.getDeclaredField("tags");
		field.setAccessible(true);
		for (var entry : savedTags.entrySet()) {
			field.set(entry.getKey(), entry.getValue());
		}
		savedTags.clear();
		TeamLookup.setServer(null);
		ExpandedInventoryManager.clearRuntimeState();
		PerkRegistry.clear();
		PerkSetRegistry.clear();
		EnchantmentDiamondCost.resetShown();
	}

	// ------------------------------------------------------------------ 순수 판정

	@Test
	void 대역_검은_삽의_마법부여_수치를_받고_원래_삽은_그대로다() {
		ItemStack shovel = new ItemStack(Items.WOODEN_SHOVEL);
		ItemStack standIn = ShovelSwordEnchanting.poolStack(shovel, true);

		assertTrue(standIn.is(ItemTags.SWORDS), "후보는 검으로 고른다");
		assertEquals(shovel.get(DataComponents.ENCHANTABLE), standIn.get(DataComponents.ENCHANTABLE),
				"나무 삽의 마법부여 수치(15)를 그대로 쓴다 — 철 검의 14 가 아니다");
		assertTrue(shovel.is(Items.WOODEN_SHOVEL));
		assertSame(shovel, ShovelSwordEnchanting.poolStack(shovel, false), "팀이 없으면 바닐라 그대로");
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		assertSame(sword, ShovelSwordEnchanting.poolStack(sword, true), "삽이 아니면 그대로");
	}

	@Test
	void 모루는_팀의_삽에_검_책을_받고_삽_전용_책은_받지_않는다() {
		ItemStack shovel = new ItemStack(Items.DIAMOND_SHOVEL);
		Enchantment sharpness = enchantment(Enchantments.SHARPNESS);
		Enchantment efficiency = enchantment(Enchantments.EFFICIENCY);
		Enchantment unbreaking = enchantment(Enchantments.UNBREAKING);
		Enchantment protection = enchantment(Enchantments.PROTECTION);

		// 바닐라 답: 날카로움은 삽에 못 붙고 효율은 붙는다.
		assertFalse(sharpness.canEnchant(shovel));
		assertTrue(efficiency.canEnchant(shovel));

		assertTrue(ShovelSwordEnchanting.anvilAccepts(sharpness, shovel, false, true));
		assertFalse(ShovelSwordEnchanting.anvilAccepts(efficiency, shovel, true, true), "삽에만 붙는 책은 막는다");
		assertTrue(ShovelSwordEnchanting.anvilAccepts(unbreaking, shovel, true, true), "검도 받는 공용 책은 그대로");
		assertFalse(ShovelSwordEnchanting.anvilAccepts(protection, shovel, false, true));

		// 팀이 없으면 바닐라 답을 그대로 돌려준다.
		assertFalse(ShovelSwordEnchanting.anvilAccepts(sharpness, shovel, false, false));
		assertTrue(ShovelSwordEnchanting.anvilAccepts(efficiency, shovel, true, false));
		// 삽이 아니면 팀이어도 바닐라 답이다.
		ItemStack pickaxe = new ItemStack(Items.IRON_PICKAXE);
		bindItem(Items.IRON_PICKAXE);
		assertFalse(ShovelSwordEnchanting.anvilAccepts(sharpness, pickaxe, false, true));
	}

	@Test
	void 휩쓸기_날을_단_팀의_삽만_휩쓴다() {
		ItemStack plain = new ItemStack(Items.IRON_SHOVEL);
		ItemStack sweeping = new ItemStack(Items.IRON_SHOVEL);
		sweeping.enchant(holder(Enchantments.SWEEPING_EDGE), 3);
		ItemStack sharp = new ItemStack(Items.IRON_SHOVEL);
		sharp.enchant(holder(Enchantments.SHARPNESS), 5);

		assertTrue(ShovelSwordEnchanting.sweeps(sweeping, true));
		assertFalse(ShovelSwordEnchanting.sweeps(sweeping, false), "팀이 없으면 바닐라처럼 안 휩쓴다");
		assertFalse(ShovelSwordEnchanting.sweeps(plain, true), "휩쓸기 날이 없으면 안 휩쓴다");
		assertFalse(ShovelSwordEnchanting.sweeps(sharp, true));
	}

	@Test
	void 증강_정의와_팀_보유가_이어진다() {
		TeamState none = team();
		TeamState owner = team("sharedfate:shovel_master");

		assertFalse(ToolCraftEffect.SHOVEL_SWORD_ENCHANTING.heldBy(none));
		assertTrue(ToolCraftEffect.SHOVEL_SWORD_ENCHANTING.heldBy(owner));
		assertFalse(ToolCraftEffect.DIAMOND_TO_NETHERITE.heldBy(owner), "다른 표시와 섞이지 않는다");
		owner.perksEnabled = false;
		assertFalse(ToolCraftEffect.SHOVEL_SWORD_ENCHANTING.heldBy(owner), "증강을 끄면 꺼진다");
		assertFalse(ToolCraftEffect.SHOVEL_SWORD_ENCHANTING.heldBy(null));
	}

	/** 주입 자리마다 {@code require = 1} 이라 자리가 어긋나면 클래스를 읽는 순간 터진다. 붙었다면 처리기가 들어 있다. */
	@Test
	void 마법부여대_모루_휩쓸기_주입이_붙는다() {
		assertHandler(EnchantmentMenu.class, "swordPool");
		assertHandler(AnvilMenu.class, "shovelAsSword");
		assertHandler(AnvilMenu.class, "resendCost");
		assertHandler(Player.class, "shovelSweeps");
	}

	// ------------------------------------------------------------------ 바닐라 메뉴

	@Test
	void 팀의_삽은_마법부여대에서_검_후보만_나온다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			TableLevel level = level();
			Player player = playerInTeam(storage, level, team("sharedfate:shovel_master"));
			Set<String> seen = clues(player, level, Items.DIAMOND_SHOVEL);

			assertFalse(seen.isEmpty(), "단서가 하나도 안 나왔다");
			assertTrue(SWORD_TABLE.containsAll(seen), "검 후보 밖의 것이 나왔다: " + seen);
			assertTrue(seen.stream().noneMatch(SHOVEL_ONLY::contains), "삽 후보가 남았다: " + seen);
			assertTrue(seen.stream().anyMatch(id -> !id.equals("minecraft:unbreaking")),
					"공용인 내구성만 나오면 검 후보로 바뀌었는지 알 수 없다: " + seen);
			assertEquals(clues(player, level, Items.DIAMOND_SWORD), seen,
					"같은 씨앗이면 다이아몬드 검과 똑같은 단서가 나와야 한다");
		}
	}

	@Test
	void 팀이_아니면_삽은_바닐라_삽_후보다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			TableLevel level = level();
			Player player = playerInTeam(storage, level, team());
			Set<String> seen = clues(player, level, Items.DIAMOND_SHOVEL);

			assertTrue(seen.stream().anyMatch(SHOVEL_ONLY::contains), "바닐라 삽 후보가 안 나왔다: " + seen);
			assertTrue(seen.stream().noneMatch(id -> SWORD_TABLE.contains(id) && !id.equals("minecraft:unbreaking")),
					"팀이 아닌데 검 후보가 새었다: " + seen);
		}
	}

	@Test
	void 클라이언트_쪽에서는_팀을_묻지_않는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			TableLevel level = level();
			Player player = playerInTeam(storage, level, team("sharedfate:shovel_master"));
			assertTrue(ShovelSwordEnchanting.heldBy(player));
			setField(Level.class, level, "isClientSide", true);

			assertFalse(ShovelSwordEnchanting.heldBy(player), "클라이언트는 팀을 모르므로 판정하지 않는다");
			assertFalse(ShovelSwordEnchanting.heldBy(null));
		}
	}

	@Test
	void 누르면_삽에_검_마법부여가_붙는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			TableLevel level = level();
			Player player = playerInTeam(storage, level, team("sharedfate:shovel_master"));
			Set<String> applied = new HashSet<>();
			for (int seed = 0; seed < 30; seed++) {
				for (int slot = 0; slot < 3; slot++) {
					EnchantmentMenu menu = menu(player, level);
					menu.setData(3, seed);
					menu.getSlot(0).set(new ItemStack(Items.IRON_SHOVEL));
					menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
					giveDiamonds(menu, 64);
					assertArrayEquals(new int[] {30, 30, 30}, menu.costs, "등급 30 규칙은 그대로다");

					assertTrue(menu.clickMenuButton(player, slot), seed + "·" + slot);

					ItemStack result = menu.getSlot(0).getItem();
					assertTrue(result.is(Items.IRON_SHOVEL), "삽은 삽 그대로다");
					assertTrue(result.isEnchanted());
					assertTrue(menu.getSlot(1).getItem().isEmpty(), "청금석 3개를 걷는다");
					for (var holder : result.getEnchantments().keySet()) {
						applied.add(holder.unwrapKey().orElseThrow().identifier().toString());
					}
				}
			}
			assertTrue(SWORD_TABLE.containsAll(applied), "검 후보 밖의 것이 붙었다: " + applied);
			assertTrue(applied.contains("minecraft:sharpness") || applied.contains("minecraft:smite")
					|| applied.contains("minecraft:bane_of_arthropods"), "피해 마법부여가 한 번도 안 붙었다: " + applied);
		}
	}

	// ------------------------------------------------------------------ 도움

	/** 씨앗 0~119 로 이 물건을 올렸을 때 세 칸 단서에 나온 인챈트. */
	private Set<String> clues(Player player, TableLevel level, Item item) {
		Set<String> seen = new LinkedHashSet<>();
		EnchantmentMenu menu = menu(player, level);
		for (int seed = 0; seed < 120; seed++) {
			menu.setData(3, seed);
			menu.getSlot(0).set(new ItemStack(item));
			assertArrayEquals(new int[] {30, 30, 30}, menu.costs);
			for (int clue : menu.enchantClue) {
				if (clue >= 0) {
					seen.add(((ResourceKey<?>) clueIds.get(clue)).identifier().toString());
				}
			}
			menu.getSlot(0).set(ItemStack.EMPTY);
		}
		return seen;
	}

	private static void assertHandler(Class<?> target, String handler) {
		boolean found = java.util.Arrays.stream(target.getDeclaredMethods())
				.map(Method::getName).anyMatch(name -> name.endsWith("sharedfate$" + handler));
		assertTrue(found, target.getSimpleName() + " 에 " + handler + " 가 없다");
	}

	private static TeamState team(String... perks) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perks));
		return state;
	}

	private static HolderLookup.RegistryLookup<Enchantment> enchantments() {
		return TestBootstrap.registries().lookupOrThrow(Registries.ENCHANTMENT);
	}

	private static Holder.Reference<Enchantment> holder(ResourceKey<Enchantment> key) {
		return enchantments().getOrThrow(key);
	}

	private static Enchantment enchantment(ResourceKey<Enchantment> key) {
		return holder(key).value();
	}

	private static EnchantmentMenu menu(Player player, TableLevel level) {
		return new EnchantmentMenu(1, player.getInventory(), ContainerLevelAccess.create(level, TABLE));
	}

	private TableLevel level() throws Exception {
		TableLevel level = (TableLevel) unsafe().allocateInstance(TableLevel.class);
		level.shelves = new HashSet<>();
		for (BlockPos offset : EnchantingTableBlock.BOOKSHELF_OFFSETS.subList(0, EnchantmentTableRules.REQUIRED_SHELVES)) {
			level.shelves.add(TABLE.offset(offset));
		}
		level.testRegistries = registries();
		level.testRandom = RandomSource.create(20261106L);
		return level;
	}

	private static void giveDiamonds(EnchantmentMenu menu, int count) {
		menu.getSlot(((EnchantmentDiamondAccess) menu).sharedfate$diamondMenuSlot())
				.set(new ItemStack(Items.DIAMOND, count));
	}

	private static Player playerInTeam(SavedDataStorage storage, TableLevel level, TeamState state) throws Exception {
		var server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
		Player player = (Player) unsafe().allocateInstance(HollowPlayer.class);
		UUID id = UUID.randomUUID();
		setField(Entity.class, player, "uuid", id);
		setField(Entity.class, player, "level", level);
		setField(Entity.class, player, "random", RandomSource.create(20261106L));
		setField(Player.class, player, "abilities", new Abilities());
		setField(Player.class, player, "inventory", new Inventory(player, new EntityEquipment()));
		TeamManager.get(server).createTeam("삽", id, state);
		TeamLookup.setServer(server);
		return player;
	}

	// ------------------------------------------------------------------ 태그

	/**
	 * 바닐라 후보 뽑기가 묻는 태그만 진짜 태그 파일에서 풀어 묶는다.
	 *
	 * <ul>
	 *   <li>아이템 — 인챈트의 {@code supported_items}·{@code primary_items} 에 걸린 태그와 검·삽 태그.
	 *       묶는 물건은 시험이 쓰는 삽·검뿐이다.</li>
	 *   <li>인챈트 — 배타 집합({@code exclusive_set})과 탁자 후보({@code in_enchanting_table}).
	 *       모든 인챈트 홀더에 묶는다(배타 판정이 후보 전부를 묻는다).</li>
	 * </ul>
	 */
	@SuppressWarnings("unchecked")
	private void bindVanillaTags() throws Exception {
		for (Item item : List.of(Items.WOODEN_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL,
				Items.IRON_SWORD, Items.DIAMOND_SWORD)) {
			bindItem(item);
		}
		Set<TagKey<Enchantment>> enchantmentTags = new LinkedHashSet<>();
		enchantmentTags.add(EnchantmentTags.IN_ENCHANTING_TABLE);
		enchantments().listElements().forEach(holder ->
				holder.value().exclusiveSet().unwrapKey().ifPresent(enchantmentTags::add));
		List<Holder<Enchantment>> table = new ArrayList<>();
		enchantments().listElements().forEach(holder -> {
			String id = holder.key().identifier().toString();
			Set<TagKey<Enchantment>> tags = new HashSet<>();
			for (TagKey<Enchantment> tag : enchantmentTags) {
				if (tagContains("enchantment", tag.location(), id)) tags.add(tag);
			}
			if (tags.contains(EnchantmentTags.IN_ENCHANTING_TABLE)) table.add(holder);
			try {
				bind(holder, tags);
			} catch (Exception error) {
				throw new AssertionError(error);
			}
		});
		assertTrue(table.size() > 20, "탁자 후보를 태그 파일에서 읽지 못했다: " + table.size());
		Constructor<?> constructor = HolderSet.Named.class.getDeclaredConstructor(HolderOwner.class, TagKey.class);
		constructor.setAccessible(true);
		tableSet = (HolderSet.Named<Enchantment>) constructor.newInstance(null, EnchantmentTags.IN_ENCHANTING_TABLE);
		Method bind = HolderSet.Named.class.getDeclaredMethod("bind", List.class);
		bind.setAccessible(true);
		bind.invoke(tableSet, table);
	}

	/** 이 물건이 든 아이템 태그를 진짜 태그 파일에서 풀어 묶는다. */
	private void bindItem(Item item) {
		Set<TagKey<Item>> candidates = new LinkedHashSet<>(List.of(ItemTags.SWORDS, ItemTags.SHOVELS));
		enchantments().listElements().forEach(holder -> {
			holder.value().definition().supportedItems().unwrapKey().ifPresent(candidates::add);
			holder.value().definition().primaryItems().flatMap(HolderSet::unwrapKey).ifPresent(candidates::add);
		});
		var holder = item.builtInRegistryHolder();
		String id = holder.key().identifier().toString();
		Set<TagKey<Item>> tags = new HashSet<>();
		for (TagKey<Item> tag : candidates) {
			if (tagContains("item", tag.location(), id)) tags.add(tag);
		}
		try {
			bind(holder, tags);
		} catch (Exception error) {
			throw new AssertionError(error);
		}
	}

	private final Map<String, Set<String>> resolved = new HashMap<>();

	/** {@code data/<ns>/tags/<kind>/<path>.json} 을 끝까지 풀어 {@code id} 가 들었는지. */
	private boolean tagContains(String kind, Identifier tag, String id) {
		return resolve(kind, tag).contains(id);
	}

	private Set<String> resolve(String kind, Identifier tag) {
		String key = kind + "|" + tag;
		Set<String> cached = resolved.get(key);
		if (cached != null) return cached;
		Set<String> values = new HashSet<>();
		resolved.put(key, values);
		String path = "data/" + tag.getNamespace() + "/tags/" + kind + "/" + tag.getPath() + ".json";
		try (InputStream in = ShovelSwordEnchantingTest.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) return values;
			var json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement entry : json.getAsJsonArray("values")) {
				String value = entry.isJsonObject() ? entry.getAsJsonObject().get("id").getAsString() : entry.getAsString();
				if (value.startsWith("#")) values.addAll(resolve(kind, Identifier.parse(value.substring(1))));
				else values.add(Identifier.parse(value).toString());
			}
		} catch (Exception error) {
			throw new AssertionError(path, error);
		}
		return values;
	}

	private void bind(Holder.Reference<?> holder, Set<?> tags) throws Exception {
		Field field = Holder.Reference.class.getDeclaredField("tags");
		field.setAccessible(true);
		savedTags.putIfAbsent(holder, field.get(holder));
		field.set(holder, Set.copyOf(tags));
	}

	/**
	 * 인챈트 등록부만 아는 {@link RegistryAccess}. {@link EnchantmentTableRulesTest} 의 것에
	 * 탁자 후보 태그({@code #in_enchanting_table})를 더했다. 단서 번호는 {@link #clueIds} 에 쌓인다.
	 */
	private RegistryAccess registries() {
		var lookup = enchantments();
		clueIds.clear();
		IdMap<?> idMap = (IdMap<?>) Proxy.newProxyInstance(IdMap.class.getClassLoader(), new Class<?>[] {IdMap.class},
				(proxy, method, args) -> switch (method.getName()) {
					case "getId" -> {
						Object key = ((Holder<?>) args[0]).unwrapKey().orElseThrow();
						if (!clueIds.contains(key)) clueIds.add(key);
						yield clueIds.indexOf(key);
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
						if (args[0] instanceof TagKey<?> tag) {
							yield tag.equals(EnchantmentTags.IN_ENCHANTING_TABLE) ? Optional.of(tableSet) : Optional.empty();
						}
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
