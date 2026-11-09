package com.sharedfate.perk;

import com.sharedfate.perk.effect.*;
import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 방패를 실제로 막은 사람과 오른손으로 돌진한 사람의 전투 상태. */
public final class PerkShieldCombat {
	private static final Map<UUID, Long> COUNTERS = new HashMap<>();
	private static final Map<UUID, Long> RUSH_READY = new HashMap<>();
	private static final Map<AbstractArrow, ReturnShot> RETURNS = new WeakHashMap<>();
	private static final Map<Mob, Stun> STUNS = new HashMap<>();
	private static final ThreadLocal<Integer> BLOCK_WEAR = ThreadLocal.withInitial(() -> 1);
	/**
	 * 무기 3단계 「한 번 더」의 두 번째 타격을 넣는 중이면 참.
	 *
	 * <p>두 번째 타격은 첫 타격과 같은 피해원(근접 · 직접)으로 {@code hurtServer} 를 다시 부르므로, 표시가 없으면
	 * {@link #counterMultiplier} 가 그 사이 막아서 장전한 반격을 절반 피해에 써 버린다. 반격은 사람이 직접
	 * 휘두른 다음 한 번에 걸려야 한다.
	 */
	private static final ThreadLocal<Boolean> FOLLOW_UP = ThreadLocal.withInitial(() -> false);
	private record ReturnShot(ServerPlayer player, LivingEntity shooter, double speed) {}
	private record Stun(long until) {}
	private PerkShieldCombat() {}

	public static void register() {
		UseItemCallback.EVENT.register((player, level, hand) -> {
			if (player instanceof ServerPlayer serverPlayer && hand == InteractionHand.MAIN_HAND)
				rush(serverPlayer);
			// 쿨타임 동안에도 평소처럼 방패로 막을 수 있다.
			return InteractionResult.PASS;
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long now = server.getTickCount();
			COUNTERS.values().removeIf(expiry -> expiry < now);
			RUSH_READY.values().removeIf(expiry -> expiry <= now);
			STUNS.entrySet().removeIf(entry -> {
				if (entry.getKey().isRemoved() || entry.getValue().until() <= now) {
					return true;
				}
				entry.getKey().setDeltaMovement(Vec3.ZERO);
				return false;
			});
			// 0.45.0-dev 로 만든 다이아몬드 방패는 막는 동안 달리지 못하는 옛 값을 들고 있다.
			// 1초에 한 번 접속한 사람의 가방을 훑어 고친다. 고친 칸은 바닐라 슬롯 동기화가 다음
			// 틱에 클라이언트로 보내고, 들고 막는 중이어도 LocalPlayer 가 새 물건으로 갈아 쥔다.
			if (now % LEGACY_SHIELD_SCAN_TICKS == 0) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					fixLegacyDiamondShields(player);
				}
			}
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
	}

	/** 옛 다이아몬드 방패를 찾아 고치는 주기(틱). 가방 한 번 훑기는 가벼워 1초면 넉넉하다. */
	private static final int LEGACY_SHIELD_SCAN_TICKS = 20;

	/** 이 사람 가방(손·갑옷·왼손 칸 포함)의 옛 다이아몬드 방패를 고친다. */
	static void fixLegacyDiamondShields(ServerPlayer player) {
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && ShieldRecipes.fixLegacyDiamond(stack)) {
				inventory.setChanged();
			}
		}
	}

	public static List<PerkEffect> effects(TeamState state) {
		if (state == null || !state.perksEnabled) return List.of();
		List<PerkEffect> result = new ArrayList<>();
		for (String id : state.ownedPerks) PerkRegistry.byId(id).ifPresent(p -> result.addAll(p.effects()));
		result.addAll(PerkSetEffects.activeEffectsOf(state));
		return result;
	}
	public static boolean has(ServerPlayer player, Class<? extends PerkEffect> type) {
		return effects(TeamLookup.stateOf(player.getUUID())).stream().anyMatch(type::isInstance);
	}
	public static boolean turtle(Entity entity) {
		return entity instanceof ServerPlayer player && player.getUseItem().is(Items.SHIELD)
				&& has(player, TurtleShellEffect.class);
	}
	public static boolean ordinaryEnemy(Entity entity) {
		return entity instanceof Mob && entity instanceof Enemy
				&& !(entity instanceof EnderDragon) && !(entity instanceof WitherBoss);
	}
	static boolean skeletonShooter(Entity entity) { return entity instanceof AbstractSkeleton; }
	public static int blockWearMultiplier(Entity entity, DamageSource source) {
		return entity instanceof ServerPlayer player && wornByArrow(source.getDirectEntity())
				&& has(player, ShieldRicochetEffect.class) ? 3 : 1;
	}
	/**
	 * 「튕겨내기」 대가(내구도 ×3)가 걸리는 투사체인가. 설명이 「화살」이므로 화살 · 분광 화살만이다.
	 * {@code AbstractArrow} 로 보면 삼지창까지 걸린다.
	 */
	static boolean wornByArrow(@Nullable Entity direct) {
		return direct instanceof Arrow || direct instanceof SpectralArrow;
	}
	public static int wearContext() { return BLOCK_WEAR.get(); }
	public static void wearContext(int multiplier) { BLOCK_WEAR.set(multiplier); }

	/** 바닐라가 실제로 막아 낸 양만 받는다. 방향·관통·지연 판정은 바닐라 몫이다. */
	public static void onBlocked(Entity entity, DamageSource source, float blocked) {
		if (!(entity instanceof ServerPlayer player) || !(blocked > 0)) return;
		if (has(player, ShieldCounterEffect.class)) arm(player.getUUID(), player.level().getServer().getTickCount());
		if (has(player, ShieldRicochetEffect.class)
				&& source.getDirectEntity() instanceof AbstractArrow arrow
				&& source.getEntity() instanceof LivingEntity shooter && skeletonShooter(shooter)) {
			RETURNS.put(arrow, new ReturnShot(player, shooter, Math.max(1, arrow.getDeltaMovement().length())));
		}
		if (has(player, TurtleShellEffect.class) && ordinaryEnemy(source.getEntity())) {
			Entity attacker = source.getEntity();
			if (attacker.hurtServer(player.level(), player.damageSources().thorns(player), blocked * 0.5F))
				PerkProcFeedback.fire(player, "sharedfate:turtle_shell");
		}
	}

	/** 화살의 바닐라 반동 직전에 실행하여 감속·소멸로 덮어쓰이지 않게 한다. */
	public static boolean returnArrow(AbstractArrow arrow) {
		ReturnShot shot = RETURNS.remove(arrow);
		if (shot == null || !shot.shooter().isAlive()) return false;
		Vec3 direction = shot.shooter().getEyePosition().subtract(shot.player().getEyePosition()).normalize();
		arrow.setOwner(shot.player());
		arrow.setPos(shot.player().getEyePosition().add(direction.scale(0.8)));
		arrow.shoot(direction.x, direction.y, direction.z, (float) shot.speed(), 0);
		arrow.needsSync = true;
		arrow.syncVelocity = true;
		PerkProcFeedback.fire(shot.player(), "sharedfate:shield_ricochet");
		return true;
	}

	static void arm(UUID player, long now) { COUNTERS.put(player, now + 60); }
	static double consume(UUID player, long now, boolean melee) {
		if (!melee) return 1;
		Long expiry = COUNTERS.remove(player);
		return expiry != null && now <= expiry ? 2 : 1;
	}
	public static double counterMultiplier(DamageSource source) {
		// 「한 번 더」의 두 번째 타격은 반격을 쓰지도 지우지도 않는다(FOLLOW_UP 머리말).
		if (FOLLOW_UP.get()) return 1;
		if (source == null || !(source.getEntity() instanceof ServerPlayer player)) return 1;
		if (!has(player, ShieldCounterEffect.class)) { COUNTERS.remove(player.getUUID()); return 1; }
		double multiplier = consume(player.getUUID(), player.level().getServer().getTickCount(),
				source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == player);
		if (multiplier > 1) PerkProcFeedback.fire(player, "sharedfate:shield_counter");
		return multiplier;
	}

	/** 「한 번 더」의 두 번째 타격을 넣는다. 그동안은 반격을 소모하지 않는다. */
	public static boolean followUpHit(BooleanSupplier hit) {
		boolean previous = FOLLOW_UP.get();
		FOLLOW_UP.set(true);
		try { return hit.getAsBoolean(); }
		finally { FOLLOW_UP.set(previous); }
	}
	static boolean inFollowUp() { return FOLLOW_UP.get(); }

	/** 내구성 마법과 기존 장비 배율을 지난 뒤 방패에만 적용한다. */
	public static int durability(TeamState state, ItemStack stack, int amount, net.minecraft.util.RandomSource random) {
		if (amount <= 0 || !stack.is(Items.SHIELD)) return amount;
		int loss = (int) Math.min(Integer.MAX_VALUE, (long) amount * wearContext());
		for (PerkEffect effect : effects(state)) {
			if (effect instanceof ShieldDurabilityEffect shield)
				loss = ShieldDurabilityEffect.loss(loss, shield.multiplier(), shield.ignoreChance(),
						random.nextDouble(), random.nextDouble());
		}
		return loss;
	}

	public static int rushRemaining(ServerPlayer player) {
		return (int) Math.max(0, RUSH_READY.getOrDefault(player.getUUID(), 0L) - player.level().getServer().getTickCount());
	}
	static boolean startRush(UUID player, long now) {
		if (RUSH_READY.getOrDefault(player, 0L) > now) return false;
		RUSH_READY.put(player, now + 200);
		return true;
	}
	static float rushDamage(int armor) { return Math.max(0, armor) * 0.5F; }
	/** 한 칸 걸음(0.25블록)의 수평 방향. 바로 위·아래를 보면 수평 성분이 없어 null 이다. */
	static @Nullable Vec3 rushStep(Vec3 look) {
		Vec3 flat = new Vec3(look.x, 0, look.z).normalize();
		return flat.lengthSqr() == 0 ? null : flat.scale(0.25);
	}
	/**
	 * 방향을 먼저 정하고, 나갈 수 있을 때만 쿨타임을 건다. 나가지도 않는 돌진에 10초를 묶지 않는다.
	 * 쿨타임 중이거나 방향이 없으면 null.
	 */
	static @Nullable Vec3 beginRush(UUID player, long now, Vec3 look) {
		Vec3 step = rushStep(look);
		return step != null && startRush(player, now) ? step : null;
	}
	/**
	 * 돌진에 쓸 수 있는 방패인가. 도끼에 맞아 무력화된 방패는 아이템 쿨타임이 걸려 있고, 그동안은 막지 못하니
	 * 돌진도 하지 않는다. 바닐라의 쿨타임 검사는 Fabric 의 {@code UseItemCallback} 보다 뒤에 있어 여기서 본다.
	 */
	static boolean rushShieldReady(ItemStack shield, ItemCooldowns cooldowns) {
		return shield.is(Items.SHIELD) && !cooldowns.isOnCooldown(shield);
	}
	private static void rush(ServerPlayer player) {
		ItemStack shield = player.getMainHandItem();
		if (!rushShieldReady(shield, player.getCooldowns()) || player.isSpectator() || !player.isAlive()
				|| player.isPassenger() || !has(player, ShieldRushEffect.class) || rushRemaining(player) > 0) return;
		Vec3 step = beginRush(player.getUUID(), player.level().getServer().getTickCount(), player.getLookAngle());
		if (step == null) return;
		Set<UUID> hit = new HashSet<>();
		for (int i = 0; i < 20; i++) {
			Vec3 before = player.position();
			player.move(MoverType.SELF, step);
			for (Entity target : player.level().getEntities(player, player.getBoundingBox().inflate(0.35))) {
				Entity victim = target instanceof EnderDragonPart part ? part.parentMob : target;
				if (!(victim instanceof Mob) || !(victim instanceof Enemy) || !hit.add(victim.getUUID())) continue;
				float damage = rushDamage(player.getArmorValue());
				boolean hurt = victim instanceof EnderDragon dragon
						? dragon.hurt(player.level(), target instanceof EnderDragonPart part ? part : dragon.head,
								player.damageSources().playerAttack(player), damage)
						: victim.hurtServer(player.level(), player.damageSources().playerAttack(player), damage);
				if (hurt) {
					PerkProcFeedback.fire(player, "sharedfate:shield_rush");
					shield.hurtAndBreak(5, player, InteractionHand.MAIN_HAND);
					if (ordinaryEnemy(victim)) {
						Mob mob = (Mob) victim;
						STUNS.put(mob, new Stun(player.level().getServer().getTickCount() + 20L));
						mob.setDeltaMovement(Vec3.ZERO);
					}
				}
			}
			if (shield.isEmpty() || player.position().distanceToSqr(before) < 0.001) break;
		}
		player.teleportTo(player.getX(), player.getY(), player.getZ());
	}
	/** 저장되는 NoAI 값을 건드리지 않아 경직 중 청크를 떠나도 영구 정지하지 않는다. */
	public static boolean stunned(Mob mob) {
		Stun stun = STUNS.get(mob);
		return stun != null && mob.level().getServer() != null
				&& stun.until() > mob.level().getServer().getTickCount();
	}
	static void clear() {
		STUNS.clear(); COUNTERS.clear(); RUSH_READY.clear(); RETURNS.clear(); BLOCK_WEAR.remove(); FOLLOW_UP.remove();
	}
}
