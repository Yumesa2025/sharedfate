package com.sharedfate.sync;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class FoodOverflowBuffer extends SavedData {
	public static final float MAX_RESERVE = 80.0F;
	private static final Map<UUID, Integer> PENDING_NUTRITION = new HashMap<>();
	private final Map<UUID, Float> reserves;

	private static final Codec<FoodOverflowBuffer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.FLOAT)
					.optionalFieldOf("reserves", Map.of()).forGetter(data -> data.reserves)
	).apply(instance, FoodOverflowBuffer::new));

	public static final SavedDataType<FoodOverflowBuffer> TYPE = new SavedDataType<>(
			SharedFateMod.id("food_overflow"), FoodOverflowBuffer::new, CODEC, null);

	public record Result(int foodLevel, float reserve) {
	}

	public FoodOverflowBuffer() {
		this(Map.of());
	}

	private FoodOverflowBuffer(Map<UUID, Float> loaded) {
		reserves = new HashMap<>();
		loaded.forEach((teamId, value) -> {
			if (teamId != null && value != null && Float.isFinite(value) && value > 0.0F) {
				reserves.put(teamId, Math.min(MAX_RESERVE, value));
			}
		});
	}

	public static FoodOverflowBuffer get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public static void recordConsumption(ServerPlayer player, FoodProperties food) {
		if (player == null || food == null || food.nutrition() <= 0) {
			return;
		}
		ShareTeam team = TeamManager.get(player.level().getServer()).teamOf(player.getUUID());
		if (team != null) {
			PENDING_NUTRITION.merge(team.teamId(), food.nutrition(),
					(left, right) -> (int) Math.min(Integer.MAX_VALUE, (long) left + right));
		}
	}

	public int apply(UUID teamId, int currentFood, int observedDelta) {
		Integer pending = PENDING_NUTRITION.remove(teamId);
		int intendedNutrition = Math.max(0, pending == null ? 0 : pending);
		float previousReserve = reserves.getOrDefault(teamId, 0.0F);
		Result result = calculate(currentFood, observedDelta, intendedNutrition, previousReserve);
		if (result.reserve() <= 0.0001F) {
			reserves.remove(teamId);
		} else {
			reserves.put(teamId, result.reserve());
		}
		if (result.reserve() != previousReserve) {
			setDirty();
		}
		return result.foodLevel() - currentFood;
	}

	/**
	 * 완충을 거치지 않고 관측한 변화량을 그대로 돌려준다. 허기 상한이 20 위로 열린 팀(「먹방」)이 쓴다.
	 *
	 * <p>그런 팀은 먹은 만큼 허기 자체가 차므로 남는 몫을 모아 둘 까닭이 없다. 이번 바퀴에 적힌 영양
	 * 기록은 버린다 — 남겨 두면 증강을 잃은 다음 바퀴에 「잘린 영양」으로 잘못 읽혀 완충에 쌓인다.
	 * 이미 쌓인 완충은 건드리지 않는다. 증강을 잃으면 {@link #apply} 가 다시 그것을 쓴다.
	 */
	public static int bypass(UUID teamId, int observedDelta) {
		PENDING_NUTRITION.remove(teamId);
		return observedDelta;
	}

	static Result calculate(int currentFood, int observedDelta, int intendedNutrition, float previousReserve) {
		int safeCurrent = Math.max(0, Math.min(20, currentFood));
		int positiveObserved = Math.max(0, observedDelta);
		float reserve = clamp(previousReserve, 0.0F, MAX_RESERVE);
		int rawFood = safeCurrent + observedDelta;
		int locallyClampedNutrition = Math.max(0, intendedNutrition - positiveObserved);
		int teamOverflow = Math.max(0, rawFood - 20);
		reserve = clamp(reserve + locallyClampedNutrition + teamOverflow, 0.0F, MAX_RESERVE);

		int food = Math.max(0, Math.min(20, rawFood));
		if (observedDelta < 0 && intendedNutrition == 0 && reserve > 0.0F) {
			int restored = Math.min(-observedDelta, (int) Math.floor(reserve));
			food = Math.min(20, food + restored);
			reserve -= restored;
		}
		return new Result(food, reserve);
	}

	public float reserve(UUID teamId) {
		return reserves.getOrDefault(teamId, 0.0F);
	}

	public static void forgetPending(UUID teamId) {
		PENDING_NUTRITION.remove(teamId);
	}

	public static void resetRuntime() {
		PENDING_NUTRITION.clear();
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
