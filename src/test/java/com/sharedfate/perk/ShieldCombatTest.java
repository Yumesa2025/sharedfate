package com.sharedfate.perk;

import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.*;
import com.sharedfate.team.TeamState;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 방패의 사람별 상태, 실제 번들 정의, 확률 소모량을 함께 검증한다. */
class ShieldCombatTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@BeforeEach void load() { PerkRegistry.loadBundled(); PerkSetRegistry.loadBundled(); }
	@AfterEach void clear() { PerkShieldCombat.clear(); PerkRegistry.clear(); PerkSetRegistry.clear(); }
	private Perk perk(String id) { return PerkRegistry.byId("sharedfate:" + id).orElseThrow(); }

	@Test void 반격은_막은_사람의_다음_근접_한번만_세초_안에_두배다() {
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		PerkShieldCombat.arm(first, 100);
		assertEquals(1, PerkShieldCombat.consume(second, 101, true));
		assertEquals(1, PerkShieldCombat.consume(first, 102, false));
		assertEquals(2, PerkShieldCombat.consume(first, 160, true));
		assertEquals(1, PerkShieldCombat.consume(first, 160, true));
		PerkShieldCombat.arm(first, 200);
		assertEquals(1, PerkShieldCombat.consume(first, 261, true));
	}
	@Test void 돌진은_개인별_십초_쿨타임이고_방어력_절반이다() {
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		assertTrue(PerkShieldCombat.startRush(first, 10));
		assertFalse(PerkShieldCombat.startRush(first, 209));
		assertTrue(PerkShieldCombat.startRush(second, 11));
		assertTrue(PerkShieldCombat.startRush(first, 210));
		assertEquals(10, PerkShieldCombat.rushDamage(20));
		assertEquals(1.5F, PerkShieldCombat.rushDamage(3));
		assertEquals(0, PerkShieldCombat.rushDamage(0));
	}
	@Test void 내구도_배율은_실제_최대값을_바꾸지_않고_소모량만_바꾼다() {
		TeamState state = TeamState.fresh(20); state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:shield_maintenance");
		ItemStack shield = new ItemStack(Items.SHIELD);
		int maximum = shield.getMaxDamage();
		RandomSource random = RandomSource.create(1234);
		long total = 0;
		for (int i = 0; i < 100000; i++) total += PerkShieldCombat.durability(state, shield, 1, random);
		assertEquals(1 / 1.5, total / 100000.0, 0.01);
		assertEquals(maximum, shield.getMaxDamage());
		assertEquals(5, PerkShieldCombat.durability(state, new ItemStack(Items.DIAMOND_SWORD), 5, random));
		state.perksEnabled = false;
		assertEquals(5, PerkShieldCombat.durability(state, shield, 5, random));
	}
	@Test void 내구도_감소_대가와_세트_무시확률도_계산된다() {
		assertEquals(2, ShieldDurabilityEffect.loss(1, 0.9, 0, 0.9, 0));
		assertEquals(1, ShieldDurabilityEffect.loss(1, 0.9, 0, 0.9, 0.9));
		assertEquals(0, ShieldDurabilityEffect.loss(10, 1, 0.6, 0.599, 0));
		assertEquals(10, ShieldDurabilityEffect.loss(10, 1, 0.6, 0.6, 0));
		for (String json : new String[]{"{\"multiplier\":0}", "{\"ignore_chance\":1.1}", "{\"multiplier\":-1}"})
			assertNull(ShieldDurabilityEffect.fromJson("test", 0, JsonParser.parseString(json).getAsJsonObject()));
	}
	@Test void 화살_내구도_대가는_방패에만_세배로_합쳐진다() {
		PerkShieldCombat.wearContext(3);
		assertEquals(15, PerkShieldCombat.durability(null, new ItemStack(Items.SHIELD), 5, RandomSource.create(1)));
		assertEquals(5, PerkShieldCombat.durability(null, new ItemStack(Items.IRON_SWORD), 5, RandomSource.create(1)));
		PerkShieldCombat.wearContext(1);
	}
	@Test void 반사와_경직은_보스와_플레이어를_제외한다() throws Exception {
		assertTrue(PerkShieldCombat.ordinaryEnemy(empty(Zombie.class)));
		assertFalse(PerkShieldCombat.ordinaryEnemy(empty(EnderDragon.class)));
		assertFalse(PerkShieldCombat.ordinaryEnemy(empty(WitherBoss.class)));
		assertFalse(PerkShieldCombat.ordinaryEnemy(empty(ServerPlayer.class)));
		assertFalse(PerkShieldCombat.ordinaryEnemy(null));
	}
	@Test void 튕겨내기는_스켈레톤_계열만_되돌린다() throws Exception {
		assertTrue(PerkShieldCombat.skeletonShooter(empty(net.minecraft.world.entity.monster.skeleton.Skeleton.class)));
		assertTrue(PerkShieldCombat.skeletonShooter(empty(net.minecraft.world.entity.monster.skeleton.Stray.class)));
		assertTrue(PerkShieldCombat.skeletonShooter(empty(net.minecraft.world.entity.monster.skeleton.Bogged.class)));
		assertFalse(PerkShieldCombat.skeletonShooter(empty(ServerPlayer.class)));
		assertFalse(PerkShieldCombat.skeletonShooter(empty(Zombie.class)));
	}
	@Test void 여섯_증강과_기존_방패의_새_정의가_읽힌다() {
		assertEquals(119, PerkRegistry.all().size());
		assertInstanceOf(ShieldCounterEffect.class, perk("shield_counter").effects().getFirst());
		assertInstanceOf(ShieldRicochetEffect.class, perk("shield_ricochet").effects().getFirst());
		assertInstanceOf(TurtleShellEffect.class, perk("turtle_shell").effects().getFirst());
		assertInstanceOf(ShieldRushEffect.class, perk("shield_rush").effects().getFirst());
		assertEquals(2, perk("shield_maintenance").effects().size());
		assertEquals(1, perk("steel_shield_workshop").effects().size());
		assertEquals(1, perk("grounded_guard").effects().size());
		assertEquals(25, PerkDamage.fallBlockDurabilityCost(5, false));
		assertTrue(perk("shield_rush").setTypes().contains(PerkSetType.DEFENSE));
		assertTrue(perk("shield_rush").setTypes().contains(PerkSetType.SHIELD));
	}
	@SuppressWarnings("unchecked")
	private static <T extends Entity> T empty(Class<T> type) throws Exception {
		var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
	}
}
