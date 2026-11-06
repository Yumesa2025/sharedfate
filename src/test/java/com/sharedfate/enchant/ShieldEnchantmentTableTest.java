package com.sharedfate.enchant;

import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.perk.PerkSetType;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 마법부여대에 방패를 올렸을 때 비용 단추가 살아 있는지 본다.
 *
 * <p>실서버 제보: 「방패를 올려놓고 청금석을 올려두니 인챈트가 닫힘」. 클라이언트 메뉴도
 * {@code slotsChanged} 를 돌리는데, 그때 팀 해금을 클라이언트에서 물어 언제나 「잠김」이 나와
 * 서버가 보내 준 비용을 지웠다. 여기서 만드는 메뉴는 접근자가 {@code NULL} 인, 곧
 * <b>클라이언트와 같은 모양</b>이다 — 비용은 서버가 보낸 값을 손으로 채운다.
 */
class ShieldEnchantmentTableTest {
	private static SharedFateConfig previousConfig;

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
	void load() {
		ExpandedInventoryManager.clearRuntimeState();
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
	}

	@AfterEach
	void clear() {
		TeamLookup.setServer(null);
		ExpandedInventoryManager.clearRuntimeState();
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	// ------------------------------------------------------------------ 클라이언트

	@Test
	void 클라이언트에서_방패_다음에_청금석을_올려도_서버가_보낸_비용이_남는다() throws Exception {
		Player player = hollowPlayer();
		EnchantmentMenu menu = new EnchantmentMenu(1, player.getInventory());
		menu.getSlot(0).set(new ItemStack(Items.SHIELD));
		receiveCosts(menu, 30, 30, 30);

		menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));

		assertArrayEquals(new int[] {30, 30, 30}, menu.costs, "청금석을 올렸다고 단추가 사라지면 안 된다");
		giveDiamonds(menu, 64);
		assertTrue(menu.clickMenuButton(player, 2), "클라이언트가 눌러야 서버로 패킷이 간다");
	}

	@Test
	void 클라이언트에서_일반_아이템도_그대로다() throws Exception {
		Player player = hollowPlayer();
		EnchantmentMenu menu = new EnchantmentMenu(1, player.getInventory());
		menu.getSlot(0).set(new ItemStack(Items.DIAMOND_SWORD));
		receiveCosts(menu, 30, 30, 30);

		menu.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));

		assertArrayEquals(new int[] {30, 30, 30}, menu.costs);
	}

	@Test
	void 클라이언트에서도_이미_마법부여된_방패는_받지_않는다() {
		ItemStack enchanted = new ItemStack(Items.SHIELD);
		enchanted.enchant(TestBootstrap.registries()
				.lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING), 1);
		assertFalse(ShieldEnchantments.tableAccepts(enchanted, false, false));
		assertTrue(ShieldEnchantments.tableAccepts(new ItemStack(Items.SHIELD), false, false));
	}

	// ------------------------------------------------------------------ 서버

	@Test
	void 서버에서_방패_세트_3단계가_없는_팀은_비용이_지워진다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			ServerPlayer player = serverPlayerInTeam(storage, shieldSetPerks(2));
			EnchantmentMenu menu = new EnchantmentMenu(1, player.getInventory());
			receiveCosts(menu, 30, 30, 30);

			menu.getSlot(0).set(new ItemStack(Items.SHIELD));

			assertArrayEquals(new int[] {0, 0, 0}, menu.costs, "2단계까지인 팀은 방패에 마법부여할 수 없다");
		}
	}

	@Test
	void 서버에서_방패_증강_셋을_모은_팀은_방패를_받는다(@TempDir Path directory) throws Exception {
		try (var storage = new SavedDataStorage(directory, null, TestBootstrap.registries())) {
			ServerPlayer player = serverPlayerInTeam(storage, shieldSetPerks(3));
			EnchantmentMenu menu = new EnchantmentMenu(1, player.getInventory());
			receiveCosts(menu, 30, 30, 30);

			// 접근자가 NULL 이라 새 비용은 계산되지 않는다. 받아 주면 「지우는」 갈래를 타지 않는다.
			menu.getSlot(0).set(new ItemStack(Items.SHIELD));

			assertArrayEquals(new int[] {30, 30, 30}, menu.costs, "세트 3단계는 개수로 켜진다");
		}
	}

	// ------------------------------------------------------------------ 도움

	/** 세트 유형이 방패인 증강을 n 개 가진 팀. */
	private static TeamState shieldSetPerks(int count) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		PerkRegistry.all().stream().filter(perk -> perk.setTypes().contains(PerkSetType.SHIELD))
				.limit(count).forEach(perk -> state.ownedPerks.add(perk.id()));
		assertEquals(count, state.ownedPerks.size());
		return state;
	}

	/** 서버가 데이터 칸으로 보낸 비용을 받은 것처럼 채운다. */
	private static void receiveCosts(EnchantmentMenu menu, int... costs) {
		for (int slot = 0; slot < costs.length; slot++) menu.setData(slot, costs[slot]);
	}

	private static void giveDiamonds(EnchantmentMenu menu, int count) {
		menu.getSlot(((EnchantmentDiamondAccess) menu).sharedfate$diamondMenuSlot())
				.set(new ItemStack(Items.DIAMOND, count));
	}

	private static Player hollowPlayer() throws Exception {
		Player player = (Player) unsafe().allocateInstance(HollowPlayer.class);
		setField(Entity.class, player, "random", RandomSource.create(20261106L));
		setField(Player.class, player, "abilities", new Abilities());
		setField(Player.class, player, "inventory", new Inventory(player, new EntityEquipment()));
		return player;
	}

	private static ServerPlayer serverPlayerInTeam(SavedDataStorage storage, TeamState state) throws Exception {
		var server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
		var player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
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
