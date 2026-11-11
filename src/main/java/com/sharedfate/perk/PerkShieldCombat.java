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
			tickDashes(server);
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

	/**
	 * 이 사람 가방(손·갑옷·왼손 칸 포함)의 방패를 손본다 — 옛 다이아몬드 방패를 고치고, 세트 「방패
	 * 4단계」({@link ShieldFreeMovementEffect})에 맞춰 막는 동안 달릴 수 있는 쓰기 효과를 붙였다 뗀다.
	 * 막는 중에 바꿔도 바닐라가 다음 틱에 손에 든 새 물건으로 갈아 쥔다.
	 */
	static void fixLegacyDiamondShields(ServerPlayer player) {
		boolean free = has(player, ShieldFreeMovementEffect.class);
		var inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			boolean changed = ShieldRecipes.fixLegacyDiamond(stack);
			changed |= ShieldRecipes.applyFreeMovement(stack, free);
			if (changed) {
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

	// ------------------------------------------------------------------ 방패 돌진
	//
	// 사람이 정한 것(2026-11-11, 인터뷰) — 「돌진하는 게 아니라 순간이동이다」. 예전에는 한 틱 안에 몸을 20번
	// 옮기고 teleportTo 로 클라이언트 위치를 덮어써서 5칸 앞에 뚝 떨어졌다. 이제는 오른손 방패 우클릭으로
	// <b>한 번 세게 밀어</b> 10칸을 미끄러져 나간다. 서버가 틱마다 수평 속도를 보내 클라이언트가 실제로
	// 움직이고, 남은 거리에 맞춰 속도를 줄여 출발점에서 10칸이 되면 멈춘다. 공중에서도 쓸 수 있다 — 마찰이
	// 없어 그냥 밀면 수십 칸을 날아가므로 거리로 끊는다. 길 위의 적은 관통하며 한 번씩 방어력만큼 때리고,
	// 일반 몹은 5초 경직, 맞힌 적마다 방패 내구도 10, 재사용 6초.

	/** 재사용 대기(틱). 6초. */
	static final int RUSH_COOLDOWN_TICKS = 120;
	/** 나가는 거리(블록). 출발점에서 수평으로 잰다. */
	static final double RUSH_DISTANCE = 10.0D;
	/** 한 틱에 나가는 최대 거리(블록). 서버의 「너무 빨리 움직임」 검사(틱당 10칸)보다 한참 아래다. */
	static final double RUSH_MAX_SPEED = 3.9D;
	/** 끝에 가까울 때도 이만큼은 나간다. 없으면 남은 거리에 비례해 줄다 영영 못 닿는다. */
	static final double RUSH_MIN_SPEED = 0.6D;
	/** 벽에 막히는 등으로 못 닿아도 이 틱이 지나면 끝낸다. */
	static final int RUSH_MAX_TICKS = 20;
	/** 일반 몹 경직(틱). 5초. */
	static final int RUSH_STUN_TICKS = 100;
	/** 맞힌 적 하나마다 닳는 방패 내구도. */
	static final int RUSH_WEAR = 10;

	/** 지금 나가는 중인 돌진. 사람마다 하나. */
	private static final class Dash {
		final Vec3 start;
		final Vec3 direction;
		final Set<UUID> hit = new HashSet<>();
		Vec3 last;
		int ticks;
		Dash(Vec3 start, Vec3 direction) { this.start = start; this.direction = direction; this.last = start; }
	}
	private static final Map<UUID, Dash> DASHES = new HashMap<>();

	public static int rushRemaining(ServerPlayer player) {
		return (int) Math.max(0, RUSH_READY.getOrDefault(player.getUUID(), 0L) - player.level().getServer().getTickCount());
	}
	static boolean startRush(UUID player, long now) {
		if (RUSH_READY.getOrDefault(player, 0L) > now) return false;
		RUSH_READY.put(player, now + RUSH_COOLDOWN_TICKS);
		return true;
	}
	/** 사람이 정한 것(2026-11-11) — 방어력의 절반이 아니라 방어력 숫자 그대로(다이아 전신 20). */
	static float rushDamage(int armor) { return Math.max(0, armor); }
	/** 돌진할 수평 방향(길이 1). 바로 위·아래를 보면 수평 성분이 없어 null 이다. */
	static @Nullable Vec3 rushDirection(Vec3 look) {
		Vec3 flat = new Vec3(look.x, 0, look.z);
		return flat.lengthSqr() < 1.0e-12 ? null : flat.normalize();
	}
	/**
	 * 방향을 먼저 정하고, 나갈 수 있을 때만 쿨타임을 건다. 나가지도 않는 돌진에 쿨타임을 묶지 않는다.
	 * 쿨타임 중이거나 방향이 없으면 null.
	 */
	static @Nullable Vec3 beginRush(UUID player, long now, Vec3 look) {
		Vec3 direction = rushDirection(look);
		return direction != null && startRush(player, now) ? direction : null;
	}
	/**
	 * 이번 틱에 나갈 거리. 처음엔 최대로 밀고, 끝에 가까울수록 줄여 미끄러지듯 멈춘다. 남은 거리를 넘지 않는다.
	 * 다 왔으면 0.
	 */
	static double rushSpeed(double travelled) {
		double remaining = RUSH_DISTANCE - travelled;
		if (remaining <= 0.05D) return 0.0D;
		return Math.min(remaining, Math.max(RUSH_MIN_SPEED, Math.min(RUSH_MAX_SPEED, remaining * 0.6D)));
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
				|| player.isPassenger() || DASHES.containsKey(player.getUUID())
				|| !has(player, ShieldRushEffect.class) || rushRemaining(player) > 0) return;
		Vec3 direction = beginRush(player.getUUID(), player.level().getServer().getTickCount(), player.getLookAngle());
		if (direction == null) return;
		Dash dash = new Dash(player.position(), direction);
		DASHES.put(player.getUUID(), dash);
		push(player, direction.scale(rushSpeed(0.0D)));
	}
	/**
	 * 수평 속도를 이 값으로 정하고 클라이언트에 보낸다. 세로 속도는 0 — 돌진하는 동안은 떨어지지 않는다.
	 *
	 * <p>사람의 자리는 클라이언트가 정하므로 속도를 <b>본인에게</b> 보내야 실제로 움직인다. 26.3 에서 그 깃발은
	 * {@code syncVelocity} 다({@code ServerEntity} 가 본인과 지켜보는 사람 모두에게 보낸다). {@code needsSync} 는
	 * 지켜보는 사람에게만 가서 본인은 그대로다. 최후의 저항 넉백과 같은 방식이다.
	 */
	private static void push(ServerPlayer player, Vec3 horizontal) {
		player.setDeltaMovement(horizontal.x, 0.0D, horizontal.z);
		player.syncVelocity = true;
	}
	/** 틱마다 돌진을 이어 간다 — 지나온 길의 적을 때리고, 남은 거리만큼 다시 민다. */
	private static void tickDashes(net.minecraft.server.MinecraftServer server) {
		if (DASHES.isEmpty()) return;
		Iterator<Map.Entry<UUID, Dash>> it = DASHES.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, Dash> entry = it.next();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			Dash dash = entry.getValue();
			if (player == null || !player.isAlive() || player.isRemoved() || player.isSpectator()) {
				it.remove();
				continue;
			}
			dash.ticks++;
			Vec3 now = player.position();
			hitAlong(player, dash, now);
			double travelled = Math.hypot(now.x - dash.start.x, now.z - dash.start.z);
			double speed = rushSpeed(travelled);
			boolean stuck = dash.ticks > 2 && now.distanceToSqr(dash.last) < 0.0025D;
			ItemStack shield = player.getMainHandItem();
			if (speed <= 0.0D || stuck || dash.ticks >= RUSH_MAX_TICKS || !shield.is(Items.SHIELD)) {
				push(player, Vec3.ZERO);
				it.remove();
				continue;
			}
			dash.last = now;
			push(player, dash.direction.scale(speed));
		}
	}
	/** 지난 틱 자리에서 지금 자리까지 몸이 쓸고 간 상자 안의 적을 한 번씩 때린다. 빨라도 건너뛰지 않는다. */
	private static void hitAlong(ServerPlayer player, Dash dash, Vec3 now) {
		ServerLevel level = player.level();
		var swept = player.getBoundingBox().expandTowards(dash.last.subtract(now)).inflate(0.5D);
		for (Entity target : level.getEntities(player, swept)) {
			Entity victim = target instanceof EnderDragonPart part ? part.parentMob : target;
			if (!(victim instanceof Mob) || !(victim instanceof Enemy) || !dash.hit.add(victim.getUUID())) continue;
			float damage = rushDamage(player.getArmorValue());
			boolean hurt = victim instanceof EnderDragon dragon
					? dragon.hurt(level, target instanceof EnderDragonPart part ? part : dragon.head,
							player.damageSources().playerAttack(player), damage)
					: victim.hurtServer(level, player.damageSources().playerAttack(player), damage);
			if (!hurt) continue;
			PerkProcFeedback.fire(player, "sharedfate:shield_rush");
			ItemStack shield = player.getMainHandItem();
			if (shield.is(Items.SHIELD)) shield.hurtAndBreak(RUSH_WEAR, player, InteractionHand.MAIN_HAND);
			if (ordinaryEnemy(victim)) {
				Mob mob = (Mob) victim;
				STUNS.put(mob, new Stun(level.getServer().getTickCount() + RUSH_STUN_TICKS));
				mob.setDeltaMovement(Vec3.ZERO);
			}
		}
	}
	/** 저장되는 NoAI 값을 건드리지 않아 경직 중 청크를 떠나도 영구 정지하지 않는다. */
	public static boolean stunned(Mob mob) {
		Stun stun = STUNS.get(mob);
		return stun != null && mob.level().getServer() != null
				&& stun.until() > mob.level().getServer().getTickCount();
	}
	static void clear() {
		STUNS.clear(); COUNTERS.clear(); RUSH_READY.clear(); DASHES.clear(); RETURNS.clear(); BLOCK_WEAR.remove(); FOLLOW_UP.remove();
	}
}
