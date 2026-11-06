package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.FollowUpStrikeEffect;
import com.sharedfate.sync.SpreadSliceAccess;
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
 *   <li><b>공격 게이지는 보지 않는다.</b> 칼 · 도끼 · 삽 · 곡괭이 · 맨손 모두 같은 확률이다. 까닭은
 *       {@link FollowUpStrikeEffect} 머리말.</li>
 *   <li><b>0.2초 뒤에 따로 들어간다.</b> 같은 틱이면 한 대로 보인다. 소리와 치명타 입자로 보이게 한다.</li>
 *   <li><b>이어지지 않는다.</b> 두 번째 타격은 {@code Player.attack} 을 거치지 않으므로 다시 굴리지 않는다.</li>
 *   <li><b>피해만 다시 넣는다.</b> 같은 피해원으로 {@code hurtServer} 를 다시 부르므로 방어구 · 증강 배율 ·
 *       흡혈({@code AFTER_DAMAGE})은 첫 타격과 똑같이 걸린다. 넉백 · 발화 · 내구도 소모는 {@code Player.attack}
 *       쪽에 있어 다시 일어나지 않는다. {@code hurtServer} 가 스스로 주는 작은 넉백은 속도를 되돌려 지운다.</li>
 *   <li><b>엔더 드래곤에게도 들어간다.</b> 부위를 때렸으면 같은 부위에 다시 넣어 바닐라의 부위 배율을 그대로 탄다.</li>
 *   <li><b>플레이어에게는 걸지 않는다.</b></li>
 * </ul>
 *
 * <h2>피격 쿨타임 — 26.3 에서는 {@code damageCooldownTime} 이다</h2>
 * <p>맞은 몹은 20틱 동안 피격 쿨타임을 들고, 10 보다 크면 직전 피해보다 약한 공격을 통째로 버린다.
 * 절반 피해는 늘 직전보다 약하므로 그대로 넣으면 <b>한 번도 들어가지 않는다.</b> 그래서 잠깐 쿨타임을
 * 0 으로 내려 「새로 맞음」 갈래로 넣고, 끝나면 쿨타임과 {@code lastHurt} 를 <b>넣기 전 값으로
 * 되돌린다.</b> 되돌리지 않으면 두 번째 타격이 쿨타임을 새로 열어, 빠른 무기로 이어 친 다음 공격이 깎인다.
 *
 * <p>⚠ 0.45.4-dev 에서는 {@code Entity.invulnerableTime} 을 내렸다. 26.3 의 {@code hurtServer} 는
 * 그 칸을 보지 않으므로(까닭은 {@code LivingEntityPerkDamageMixin.damageCooldownTime} 머리말) 두 번째
 * 타격이 전부 버려졌고, 피해가 안 들어가니 소리 · 입자도 나지 않았다. 사람 말(2026-11-06) 「무기3 효과
 * 왜 터지는지 안터지는지 시각적으로 안보여」. {@code FollowUpStrikeEffectTest} 가 다시 그 칸으로 돌아가지
 * 못하게 붙든다.
 *
 * <h2>보이게 한다</h2>
 * <p>「새로 맞음」 갈래라 바닐라 피격 연출(붉은 번쩍임 · 피격음)이 한 번 더 난다. 거기에 더해 첫 타격의
 * 치명타 입자와 섞이지 않게 푸른 마법 공격 입자와 높은 타격음을 낸다. 바닐라가 주는 작은 넉백만은
 * 속도를 되돌려 지운다.
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
			float amount) {
		try {
			if (!(amount > 0.0F) || !eligibleTarget(target)) {
				return;
			}
			FollowUpStrikeEffect strike = FollowUpStrikeEffect.heldBy(TeamLookup.stateOf(player.getUUID()));
			if (strike == null || !strike.triggers(player.getRandom().nextDouble())) {
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
		SpreadSliceAccess access = (SpreadSliceAccess) (Object) living;
		int cooldown = living.damageCooldownTime;
		float lastHurt = access.sharedfate$lastHurt();
		Vec3 motion = living.getDeltaMovement();
		living.damageCooldownTime = 0;
		boolean hurt;
		try {
			hurt = target.hurtServer(level, pending.source(), pending.amount());
		} finally {
			if (living.isAlive()) {
				living.damageCooldownTime = cooldown;
				access.sharedfate$setLastHurt(lastHurt);
				living.setDeltaMovement(motion);
			}
		}
		if (hurt) {
			double y = living.getY(0.5D);
			level.playSound(null, living.getX(), y, living.getZ(),
					SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.0F, 1.4F);
			level.sendParticles(ParticleTypes.ENCHANTED_HIT, living.getX(), y, living.getZ(),
					20, 0.4D, 0.5D, 0.4D, 0.3D);
			level.sendParticles(ParticleTypes.CRIT, living.getX(), y, living.getZ(),
					10, 0.3D, 0.4D, 0.3D, 0.3D);
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
