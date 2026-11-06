package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.mixin.LivingEntityLastHurtAccessor;
import com.sharedfate.perk.effect.FollowUpStrikeEffect;
import com.sharedfate.team.TeamLookup;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 무기 세트 3단계 「한 번 더」의 실행부.
 *
 * <p>사람이 정한 것(2026-11-06) — 공격하면 60% 확률로 절반 피해를 한 번 더. 세부는 이렇게 정했다.
 *
 * <ul>
 *   <li><b>근접 공격의 주 대상에게만.</b> 휩쓸기로 맞은 주변 몹 · 화살은 빠진다
 *       ({@code PlayerFollowUpStrikeMixin} 이 {@code Player.attack} 의 주 대상 피해만 감싼다).</li>
 *   <li><b>공격 게이지가 거의 찬 공격만</b>({@link FollowUpStrikeEffect#DEFAULT_MIN_CHARGE}).</li>
 *   <li><b>0.2초 뒤에 따로 들어간다.</b> 같은 틱이면 한 대로 보인다. 소리와 치명타 입자로 보이게 한다.</li>
 *   <li><b>이어지지 않는다.</b> 두 번째 타격은 {@code Player.attack} 을 거치지 않으므로 다시 굴리지 않는다.</li>
 *   <li><b>피해만 다시 넣는다.</b> 같은 피해원으로 {@code hurtServer} 를 다시 부르므로 방어구 · 증강 배율 ·
 *       흡혈({@code AFTER_DAMAGE})은 첫 타격과 똑같이 걸린다. 넉백 · 발화 · 내구도 소모는 {@code Player.attack}
 *       쪽에 있어 다시 일어나지 않는다. {@code hurtServer} 가 스스로 주는 작은 넉백은 속도를 되돌려 지운다.</li>
 *   <li><b>엔더 드래곤에게도 들어간다.</b> 부위를 때렸으면 같은 부위에 다시 넣어 바닐라의 부위 배율을 그대로 탄다.</li>
 *   <li><b>플레이어에게는 걸지 않는다.</b></li>
 * </ul>
 *
 * <h2>무적시간</h2>
 * <p>맞은 몹은 0.5초 동안 무적이라 그대로 넣으면 바닐라가 버린다. 그래서 잠깐 무적시간을 0 으로 내려
 * 넣고, 끝나면 무적시간과 {@code lastHurt} 를 <b>넣기 전 값으로 되돌린다.</b> 되돌리지 않으면 두 번째
 * 타격이 무적시간을 새로 열어, 빠른 무기로 이어 친 다음 공격이 깎인다.
 */
public final class PerkFollowUpStrike {
	private static final List<Pending> PENDING = new ArrayList<>();
	private static volatile boolean warned;

	private PerkFollowUpStrike() {
	}

	private record Pending(UUID attacker, Entity target, DamageSource source, float amount, long dueTick) {
	}

	/** 주 대상에게 근접 피해가 실제로 들어간 직후. */
	public static void onMeleeHit(ServerPlayer player, Entity target, DamageSource source,
			float amount, float charge) {
		try {
			if (!(amount > 0.0F) || !eligibleTarget(target)) {
				return;
			}
			FollowUpStrikeEffect strike = FollowUpStrikeEffect.heldBy(TeamLookup.stateOf(player.getUUID()));
			if (strike == null || !strike.triggers(player.getRandom().nextDouble(), charge)) {
				return;
			}
			float damage = strike.followUpDamage(amount);
			if (!(damage > 0.0F) || !(player.level() instanceof ServerLevel level)) {
				return;
			}
			PENDING.add(new Pending(player.getUUID(), target, source, damage,
					level.getGameTime() + FollowUpStrikeEffect.DEFAULT_DELAY_TICKS));
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 서버 틱 끝마다. 때가 된 두 번째 타격을 넣는다. */
	public static void tick(MinecraftServer server) {
		if (PENDING.isEmpty()) {
			return;
		}
		List<Pending> due = new ArrayList<>();
		Iterator<Pending> iterator = PENDING.iterator();
		while (iterator.hasNext()) {
			Pending pending = iterator.next();
			Entity target = pending.target();
			if (target.isRemoved() || !(target.level() instanceof ServerLevel level)
					|| level.getGameTime() >= pending.dueTick()) {
				iterator.remove();
				due.add(pending);
			}
		}
		for (Pending pending : due) {
			try {
				strike(server, pending);
			} catch (RuntimeException error) {
				warnOnce(error);
			}
		}
	}

	/** 서버가 멈추면 들고 있던 몹 참조를 버린다. */
	public static void clear() {
		PENDING.clear();
	}

	private static void strike(MinecraftServer server, Pending pending) {
		Entity target = pending.target();
		ServerPlayer attacker = server.getPlayerList().getPlayer(pending.attacker());
		if (attacker == null || attacker.isDeadOrDying() || target.isRemoved() || !target.isAlive()
				|| attacker.level() != target.level() || !(target.level() instanceof ServerLevel level)) {
			return;
		}
		LivingEntity living = livingOf(target);
		if (living == null || living.isDeadOrDying()) {
			return;
		}
		int invulnerable = living.getInvulnerableTime();
		float lastHurt = ((LivingEntityLastHurtAccessor) living).sharedfate$getLastHurt();
		Vec3 motion = living.getDeltaMovement();
		living.setInvulnerableTime(0);
		boolean hurt;
		try {
			hurt = target.hurtServer(level, pending.source(), pending.amount());
		} finally {
			if (living.isAlive()) {
				living.setInvulnerableTime(invulnerable);
				((LivingEntityLastHurtAccessor) living).sharedfate$setLastHurt(lastHurt);
				living.setDeltaMovement(motion);
			}
		}
		if (hurt) {
			level.playSound(null, target.getX(), target.getY(), target.getZ(),
					SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.3F);
			level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.5D), target.getZ(),
					8, 0.3D, 0.3D, 0.3D, 0.2D);
		}
	}

	/** 한 번 더 때릴 수 있는 대상인가. 산 몹이나 드래곤 부위여야 하고, 플레이어는 빠진다. */
	static boolean eligibleTarget(@Nullable Entity target) {
		LivingEntity living = livingOf(target);
		return living != null && !(living instanceof Player);
	}

	private static @Nullable LivingEntity livingOf(@Nullable Entity target) {
		if (target instanceof EnderDragonPart part) {
			return part.parentMob;
		}
		return target instanceof LivingEntity living ? living : null;
	}

	private static void warnOnce(RuntimeException error) {
		if (!warned) {
			warned = true;
			SharedFateMod.LOGGER.warn("무기 세트 「한 번 더」를 처리하지 못했습니다. 이 경고는 한 번만 남깁니다.", error);
		}
	}
}
