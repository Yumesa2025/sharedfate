package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkFoodCapacity;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 배가 불러도 음식을 먹을 수 있게 하고, 허기 상한을 20 위로 연다. 「먹방」이 쓴다.
 *
 * <pre>{@code
 * { "type": "food_capacity", "max": 100 }
 * }</pre>
 *
 * <p>{@code max} 는 새 허기 상한이다. 적지 않으면 {@link PerkFoodCapacity#MAX_CAPACITY} 이고,
 * 21~{@link PerkFoodCapacity#MAX_CAPACITY} 밖이면 정의를 버린다 — 20 이하는 바닐라와 같아 뜻이
 * 없고, 그 위는 저장값을 읽는 자리가 살려 두지 못한다.
 *
 * <h2>붙였다 떼는 것이 아니라 그때그때 묻는 규칙이다</h2>
 * <p>먹을 수 있는가·얼마나 차는가는 먹는 순간에 {@link #capacityOf} 를 물어 정한다. 그래서
 * {@link #apply} 는 아무것도 하지 않는다. 증강을 잃으면 물어볼 대상이 사라져 상한도 저절로
 * 20 으로 돌아온다.
 *
 * <p>{@link #remove} 만은 할 일이 있다. 20 을 넘게 쌓인 개인 허기는 상한이 내려가도 그대로
 * 남으므로, 증강이 떨어져 나가는 자리(끄기·팀 탈퇴·해체)에서 20 으로 깎는다. 팀 공유 풀은
 * {@code StatMirror} 가 매 바퀴 그 팀의 상한으로 자른다.
 */
public final class FoodCapacityEffect implements PerkEffect {
	private final int capacity;

	public FoodCapacityEffect(int capacity) {
		this.capacity = capacity;
	}

	/** JSON에서 만든다. {@code max} 가 범위를 벗어났으면 경고를 남기고 null. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		int max = PerkEffectType.readInt(json, "max", PerkFoodCapacity.MAX_CAPACITY);
		if (max <= PerkFoodCapacity.VANILLA_MAX_FOOD || max > PerkFoodCapacity.MAX_CAPACITY) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: food_capacity 의 max 가 범위({}~{})를 벗어났습니다 ({})",
					perkId, PerkFoodCapacity.VANILLA_MAX_FOOD + 1, PerkFoodCapacity.MAX_CAPACITY, max);
			return null;
		}
		return new FoodCapacityEffect(max);
	}

	/** 새 허기 상한. */
	public int capacity() {
		return capacity;
	}

	@Override
	public void remove(ServerPlayer player) {
		PerkFoodCapacity.dropToVanilla(player);
	}

	/**
	 * 이 팀의 허기 상한. 증강이 없거나 꺼져 있으면 20 이다.
	 *
	 * <p>둘 이상 가졌으면 큰 쪽이 이긴다. 먹을 때마다 지나는 자리라 증강이 없는 팀은 두 번만
	 * 보고 곧바로 돌아간다. 풀에서 사라진 id 는 건너뛴다.
	 */
	public static int capacityOf(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return PerkFoodCapacity.VANILLA_MAX_FOOD;
		}
		int best = PerkFoodCapacity.VANILLA_MAX_FOOD;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof FoodCapacityEffect food) {
					best = Math.max(best, food.capacity());
				}
			}
		}
		return best;
	}
}
