package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.OnKillEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

/**
 * {@code on_kill} 증강의 실행부.
 *
 * <p>{@link OnKillEffect} 는 "얼마를 주는가"만 들고 있고, "언제 누가 무엇을 죽였는가"와 그
 * 보상을 어디에 넣는가는 전부 여기서 정한다.
 *
 * <h2>누구의 어떤 처치가 세어지는가</h2>
 * <ul>
 *   <li>죽은 쪽이 {@link Mob} 이어야 한다. {@code Player} 는 {@code Mob} 이 아니므로 팀원
 *       사망도, 팀원끼리의 처치도, 다른 팀 플레이어를 죽인 것도 여기 걸리지 않는다.
 *       갑옷 거치대처럼 {@code Mob} 이 아닌 {@code LivingEntity} 도 빠진다.</li>
 *   <li>가해자가 접속 중이고 살아 있는 팀원이어야 한다. {@code DamageSource.getEntity()} 는
 *       화살을 쏜 사람도 가리키므로 원거리 처치도 함께 세어진다. 늑대가 물어 죽인 것처럼
 *       가해자가 플레이어가 아닌 처치는 세지 않는다.</li>
 * </ul>
 *
 * <h2>회복이 두 번 들어가지 않게 하는 방법</h2>
 * <p>이 모드는 체력과 허기를 팀이 공유한다. {@code StatMirror} 는 매 틱 팀원 개인의 체력·허기가
 * <em>얼마나 움직였는지</em>를 보고 그 변화량을 공유 풀에 합산한 뒤, 합쳐진 공유 값을 팀원
 * 전원에게 다시 써 준다. 그래서 여기서 {@code killer.heal(...)} 이나
 * {@code killer.getFoodData().eat(...)} 을 부르면 안 된다. 개인 값이 움직인 만큼
 * {@code StatMirror} 가 그것을 회복으로 관측해 공유 풀에 <b>한 번 더</b> 더하기 때문이다.
 *
 * <p>그래서 이 클래스는 {@link TeamState} 의 {@code health}/{@code foodLevel}/{@code saturation}
 * 만 직접 올리고 팀원 개인에게는 손대지 않는다. 개인 값이 그대로면 {@code StatMirror} 가 보는
 * 변화량이 0 이라 공유 풀에 아무것도 더해지지 않고, 같은 틱 끝의 {@code writeBack} 이 우리가
 * 올려 둔 공유 값을 팀 전원에게 그대로 써 준다. 결과는 "팀 전체의 체력·허기가 찼다"이고
 * 움직인 양은 정확히 한 번분이다.
 */
public final class PerkKillRewards {
	/** 허기 상한. {@code FoodData} 와 {@code TeamState.sanitize} 가 쓰는 값과 같다. */
	static final int MAX_FOOD = 20;

	private static volatile boolean warned;

	private PerkKillRewards() {
	}

	/** 한 번의 처치로 팀 공유 풀에 들어갈 회복량. */
	public record Reward(int food, float saturation, float health) {
		public static final Reward NONE = new Reward(0, 0.0F, 0.0F);

		public boolean isEmpty() {
			return food == 0 && saturation == 0.0F && health == 0.0F;
		}
	}

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DEATH} 에 붙는 지점.
	 *
	 * <p>몹이 죽는 모든 자리를 지나므로 어떤 예외도 밖으로 내보내지 않는다. 증강 하나가 잘못돼
	 * 사망 처리가 멈추면 안 된다.
	 */
	public static void onDeath(LivingEntity victim, DamageSource source) {
		try {
			reward(victim, source);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	private static void reward(LivingEntity victim, DamageSource source) {
		if (!(victim instanceof Mob)) {
			return;
		}
		if (source == null || !(source.getEntity() instanceof ServerPlayer killer)) {
			return;
		}
		if (killer.isRemoved() || killer.isDeadOrDying()) {
			return;
		}
		MinecraftServer server = killer.level().getServer();
		if (server == null) {
			return;
		}

		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(killer.getUUID());
		TeamState state = manager.stateOf(killer.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return;
		}
		// 공유 체력이 이미 0 이면 전멸 처리가 도는 중이다. 그 위에 회복을 얹지 않는다.
		if (!(state.health > 0.0F)) {
			return;
		}

		grantTemporaryEffects(killer, state);

		Reward reward = rewardFor(state);
		if (reward.isEmpty()) {
			return;
		}
		applyToPool(state, reward);
		manager.setDirty();
	}

	/**
	 * 이 팀이 가진 {@code on_kill} 들이 한 번의 처치로 주는 회복량의 합.
	 *
	 * <p>같은 팀이 {@code on_kill} 증강을 여러 개 가졌으면 전부 더한다.
	 */
	public static Reward rewardFor(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return Reward.NONE;
		}
		int food = 0;
		float saturation = 0.0F;
		float health = 0.0F;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (!(effect instanceof OnKillEffect onKill)) {
					continue;
				}
				food += onKill.foodFor();
				saturation += onKill.saturationFor();
				health += onKill.healthFor();
			}
		}
		return new Reward(Math.min(MAX_FOOD, food), saturation, health);
	}

	/**
	 * 회복량을 공유 풀에 더한다.
	 *
	 * <p>{@code StatMirror.applyDeltas} 와 같은 규칙으로 자른다. 체력은 팀 최대 체력까지,
	 * 허기는 팀의 허기 상한까지(평소 20, 「먹방」이면 100), 포만감은 허기와 20 을 넘지 못한다.
	 */
	static void applyToPool(TeamState state, Reward reward) {
		if (reward.health() > 0.0F) {
			state.health = clamp(state.health + reward.health(), 0.0F, state.maxHealth);
		}
		if (reward.food() > 0) {
			// 「먹방」 팀이면 상한이 100 이다. 20 을 넘게 쌓인 허기를 20 으로 되깎지 않는다.
			state.foodLevel = PerkFoodCapacity.raise(state.foodLevel, reward.food(),
					PerkFoodCapacity.capacityOf(state));
		}
		if (reward.saturation() > 0.0F) {
			state.saturation = clamp(state.saturation + reward.saturation(), 0.0F,
					PerkFoodCapacity.saturationCeiling(state.foodLevel));
		}
	}

	/**
	 * 처치한 팀원에게 {@code on_kill} 의 하위 효과를 얹는다.
	 *
	 * <p>하위 효과가 없는 정의가 대부분이라 이 순회는 대개 아무 일도 하지 않는다.
	 */
	private static void grantTemporaryEffects(ServerPlayer killer, TeamState state) {
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof OnKillEffect onKill) {
					onKill.grantTemporaryEffects(killer);
				}
			}
		}
	}

	private static float clamp(float value, float minimum, float maximum) {
		if (!Float.isFinite(value)) {
			return minimum;
		}
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"처치 보상 증강을 처리하지 못해 이번에는 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		warned = false;
	}
}
