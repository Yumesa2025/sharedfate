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
import net.minecraft.core.registries.Registries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.phys.Vec3;
import com.sharedfate.recipe.ShieldRecipes;
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
	/** 사람이 정한 것(2026-11-11, 인터뷰) — 재사용 6초, 피해는 방어력 숫자 그대로. */
	@Test void 돌진은_개인별_육초_쿨타임이고_방어력만큼_때린다() {
		UUID first = UUID.randomUUID(), second = UUID.randomUUID();
		assertTrue(PerkShieldCombat.startRush(first, 10));
		assertFalse(PerkShieldCombat.startRush(first, 129));
		assertTrue(PerkShieldCombat.startRush(second, 11));
		assertTrue(PerkShieldCombat.startRush(first, 130));
		assertEquals(20, PerkShieldCombat.rushDamage(20));
		assertEquals(3, PerkShieldCombat.rushDamage(3));
		assertEquals(0, PerkShieldCombat.rushDamage(0));
		assertEquals(0, PerkShieldCombat.rushDamage(-4));
	}
	/**
	 * 예전에는 한 틱에 5칸을 옮겨 순간이동처럼 보였다. 이제는 틱마다 속도로 밀어 10칸을 미끄러져 간다. 한 틱에
	 * 4칸을 넘지 않고(서버의 「너무 빨리 움직임」 검사 아래), 남은 거리를 넘지 않으며, 10칸에서 멈춘다.
	 */
	@Test void 돌진은_속도로_밀어_10칸에서_멈춘다() {
		double travelled = 0.0;
		int ticks = 0;
		double speed;
		while ((speed = PerkShieldCombat.rushSpeed(travelled)) > 0.0 && ticks < 100) {
			assertTrue(speed <= 3.9 + 1.0e-9, "한 틱에 너무 멀리 간다: " + speed);
			assertTrue(travelled + speed <= 10.0 + 1.0e-9, "10칸을 넘어간다");
			travelled += speed;
			ticks++;
		}
		assertEquals(10.0, travelled, 0.051, "10칸까지 가야 한다");
		assertTrue(ticks >= 3 && ticks <= PerkShieldCombat.RUSH_MAX_TICKS,
				"한 틱에 끝나면 순간이동이고, 너무 길면 돌진이 아니다: " + ticks);
		assertEquals(3.9, PerkShieldCombat.rushSpeed(0.0), 1.0e-9, "처음엔 세게 민다");
		assertEquals(0.0, PerkShieldCombat.rushSpeed(10.0));
	}
	@Test void 바로_위아래를_보면_돌진하지_않고_쿨타임도_걸지_않는다() {
		UUID id = UUID.randomUUID();
		// 실제 시선 계산 그대로 — 고개는 ±90도까지 젖혀진다.
		for (float yaw : new float[]{0F, 37F, -135F, 180F}) {
			assertNull(PerkShieldCombat.rushDirection(Entity.calculateViewVector(-90F, yaw)));
			assertNull(PerkShieldCombat.rushDirection(Entity.calculateViewVector(90F, yaw)));
			assertNull(PerkShieldCombat.beginRush(id, 10, Entity.calculateViewVector(-90F, yaw)));
		}
		// 나가지 못한 돌진은 쿨타임을 남기지 않아 곧바로 다시 돌진할 수 있다.
		Vec3 step = PerkShieldCombat.beginRush(id, 11, Entity.calculateViewVector(-60F, 37F));
		assertNotNull(step);
		assertEquals(1.0, step.length(), 1.0e-9);
		assertEquals(0, step.y);
		assertNull(PerkShieldCombat.beginRush(id, 12, Entity.calculateViewVector(0F, 0F)));
	}
	@Test void 도끼에_무력화된_방패로는_돌진하지_않는다() {
		ItemCooldowns cooldowns = new ItemCooldowns();
		ItemStack shield = new ItemStack(Items.SHIELD);
		assertTrue(PerkShieldCombat.rushShieldReady(shield, cooldowns));
		assertFalse(PerkShieldCombat.rushShieldReady(new ItemStack(Items.IRON_SWORD), cooldowns));
		// 바닐라 무력화(BlocksAttacks.disable)는 방패 스택에 아이템 쿨타임을 건다. 같은 방패 묶음 전체가 묶인다.
		cooldowns.addCooldown(shield, 100);
		assertFalse(PerkShieldCombat.rushShieldReady(shield, cooldowns));
		assertFalse(PerkShieldCombat.rushShieldReady(ShieldRecipes.diamond(), cooldowns));
	}
	@Test void 화살_내구도_대가는_화살과_분광_화살에만_걸린다() throws Exception {
		assertTrue(PerkShieldCombat.wornByArrow(empty(Arrow.class)));
		assertTrue(PerkShieldCombat.wornByArrow(empty(SpectralArrow.class)));
		assertFalse(PerkShieldCombat.wornByArrow(empty(ThrownTrident.class)));
		assertFalse(PerkShieldCombat.wornByArrow(empty(Zombie.class)));
		assertFalse(PerkShieldCombat.wornByArrow(null));
	}
	@Test void 한번_더의_두번째_타격은_반격을_쓰지도_지우지도_않는다() throws Exception {
		ServerPlayer player = empty(ServerPlayer.class);
		UUID id = UUID.randomUUID();
		var uuid = Entity.class.getDeclaredField("uuid"); uuid.setAccessible(true); uuid.set(player, id);
		var attack = TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypes.PLAYER_ATTACK);
		DamageSource source = new DamageSource(attack, player);
		PerkShieldCombat.arm(id, 100);
		assertFalse(PerkShieldCombat.inFollowUp());
		assertTrue(PerkShieldCombat.followUpHit(() -> {
			assertTrue(PerkShieldCombat.inFollowUp());
			assertEquals(1, PerkShieldCombat.counterMultiplier(source));
			return true;
		}));
		assertFalse(PerkShieldCombat.inFollowUp());
		// 장전한 반격은 그대로 남아 사람이 직접 휘두른 다음 한 번에 걸린다.
		assertEquals(2, PerkShieldCombat.consume(id, 110, true));
		// 안에서 예외가 나도 표시는 내려간다.
		assertThrows(IllegalStateException.class, () -> PerkShieldCombat.followUpHit(() -> {
			throw new IllegalStateException();
		}));
		assertFalse(PerkShieldCombat.inFollowUp());
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
