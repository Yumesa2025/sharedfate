package com.sharedfate.perk;

import com.sharedfate.perk.effect.FoodCapacityEffect;
import com.sharedfate.sync.StatMirror;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import org.jetbrains.annotations.Nullable;

import java.util.function.IntSupplier;

/**
 * 허기 상한을 20 위로 여는 증강(「먹방」, {@link FoodCapacityEffect})의 규칙을 한곳에 모은다.
 *
 * <p>바닐라는 허기 상한 20 을 세 군데에 박아 두었다. 먹을 수 있는가({@code FoodData.needsFood}
 * → {@code Player.canEat}), 먹으면 얼마나 차는가({@code FoodData.add} 의 {@code Mth.clamp(.., 0, 20)}),
 * 포만감이 허기를 넘지 못한다는 규칙(같은 {@code add} 의 둘째 {@code clamp})이다. 이 모드는 그
 * 위에 팀 공유 풀({@code TeamState.foodLevel})을 하나 더 얹었으므로 그쪽의 20 도 함께 움직여야
 * 한다. 각 자리의 믹스인과 공유 풀 코드는 계산을 직접 하지 않고 여기에 묻는다.
 *
 * <h2>무엇이 100 이 되고 무엇이 20 에 남는가</h2>
 * <ul>
 *   <li><b>허기</b> — 상한이 증강 정의의 값(「먹방」은 100)이 된다. 먹은 만큼 그 값까지 찬다.</li>
 *   <li><b>포만감</b> — 바닐라처럼 20 이 끝이다. 포만감이 100 까지 열리면 빠른 자연 회복
 *       ({@code FoodData.tick} 의 「허기 20 이상 + 포만감」 갈래)이 다섯 배 오래 간다.</li>
 *   <li><b>자연 회복·달리기 문턱</b> — 바닐라 비교가 이미 {@code >= 18}·{@code >= 20}·{@code > 6}
 *       이라(26.3 바이트코드로 확인) 20 을 넘어도 「배부름」으로 대접받는다. 손댈 것이 없다.</li>
 * </ul>
 *
 * <h2>「증강을 가졌다」는 팀 단위다</h2>
 * <p>증강은 팀이 갖고(ownedPerks) 허기도 팀이 함께 쓴다. 그래서 서버에서는 언제나
 * {@link #capacityOf(TeamState)} 하나를 본다. 클라이언트는 팀 상태를 볼 수 없어 서버가
 * {@code PerkClientFeaturesPayload} 로 내려 준 값을 {@link #setClientCapacity} 로 받아 쓴다.
 */
public final class PerkFoodCapacity {
	/** 바닐라 허기 상한. 증강이 없으면 언제나 이 값이다. */
	public static final int VANILLA_MAX_FOOD = 20;
	/** 포만감 상한. 허기 상한이 열려도 바닐라 그대로다. 까닭은 머리말에 있다. */
	public static final float MAX_SATURATION = 20.0F;
	/**
	 * 정의가 열 수 있는 가장 큰 허기 상한.
	 *
	 * <p>저장된 팀 허기를 읽을 때({@code TeamState.sanitize})도 이 값까지는 살려 둔다. 읽는 시점에는
	 * 그 팀이 증강을 가졌는지 따지지 않는다 — 증강이 없으면 다음 바퀴의 {@code StatMirror} 가
	 * 그 팀의 상한으로 다시 자른다.
	 */
	public static final int MAX_CAPACITY = 100;

	/** 클라이언트가 아는 이 사람의 허기 상한. 서버가 말해 주기 전에는 바닐라 값이다. */
	private static volatile IntSupplier clientCapacity = () -> VANILLA_MAX_FOOD;

	/** 먹은 뒤의 허기와 포만감. */
	public record Fed(int foodLevel, float saturation) {
	}

	private PerkFoodCapacity() {
	}

	// ------------------------------------------------------------------ 상한

	/** 이 팀의 공유 허기 상한. 증강이 없거나 꺼져 있으면 20. */
	public static int capacityOf(@Nullable TeamState state) {
		return FoodCapacityEffect.capacityOf(state);
	}

	/**
	 * 이 사람의 허기 상한.
	 *
	 * <p>클라이언트에서는 서버가 내려 준 값을, 서버에서는 팀 상태를 본다. 사람이 아니면 언제나 20 이다.
	 */
	public static int capacityOf(@Nullable Entity entity) {
		if (!(entity instanceof Player player)) {
			return VANILLA_MAX_FOOD;
		}
		if (player.level().isClientSide()) {
			return sanitizeCapacity(clientCapacity.getAsInt());
		}
		return capacityOf(TeamLookup.serverStateOf(player));
	}

	/** 클라이언트가 부른다. 화면 쪽이 갖고 있는 값을 읽는 길을 건넨다. */
	public static void setClientCapacity(IntSupplier supplier) {
		clientCapacity = supplier == null ? () -> VANILLA_MAX_FOOD : supplier;
	}

	/** 상한을 뜻 있는 범위(20~{@link #MAX_CAPACITY})로 맞춘다. */
	public static int sanitizeCapacity(int capacity) {
		return Math.max(VANILLA_MAX_FOOD, Math.min(MAX_CAPACITY, capacity));
	}

	/**
	 * 이 상한이면 배가 불러도 먹을 수 있는가.
	 *
	 * <p>상한이 20 위로 열려 있으면 언제나 먹을 수 있다. 상한에 닿아도 마찬가지다 — 사람이
	 * 「100 넘어도 계속 먹을 수 있게」라고 정했다. 그때는 음식이 소모되고 먹는 순간의 효과도 다
	 * 받는데 허기만 더 오르지 않는다.
	 */
	public static boolean eatsWhenFull(int capacity) {
		return capacity > VANILLA_MAX_FOOD;
	}

	// ------------------------------------------------------------------ 먹기

	/**
	 * 바닐라 {@code FoodData.add} 의 허기 상한 자리에 들어갈 값.
	 *
	 * <p>바닐라의 20 을 <b>지금 허기보다 낮게는 내리지 않는다.</b> 증강이 없는 사람은 허기가 20 을
	 * 넘을 일이 없어 언제나 20 이고, 바닐라와 똑같다. 「먹방」을 가진 팀에서는 허기가 57 일 때
	 * 포화 상태이상이 바닐라 {@code eat} 을 부르면 20 으로 잘려 37 이 사라지는데, 그것을 막는다.
	 * 20 위로 <b>올리는</b> 일은 여기서 하지 않는다 — 그건 {@link #feed} 를 지나는 음식만 한다.
	 */
	public static int vanillaAddCeiling(int currentFood) {
		return Math.max(VANILLA_MAX_FOOD, currentFood);
	}

	/** 이 허기에서 포만감이 오를 수 있는 끝. 허기를 넘지 못하고, 허기가 20 을 넘어도 20 이 끝이다. */
	public static float saturationCeiling(float foodLevel) {
		return Math.max(0.0F, Math.min(foodLevel, MAX_SATURATION));
	}

	/**
	 * 상한이 열린 사람이 먹었을 때의 결과. 바닐라 {@code FoodData.add} 와 같은 순서로 계산한다 —
	 * 허기를 먼저 채우고, 그 새 허기를 끝으로 포만감을 채운다.
	 *
	 * <p>지금 허기가 이미 상한보다 높으면(상한이 막 내려간 틱) 깎지 않고 그대로 둔다. 깎는 일은
	 * 증강을 잃는 자리({@link #dropToVanilla})와 공유 풀이 맡는다.
	 */
	public static Fed feed(int food, float saturation, int nutrition, float saturationGain, int capacity) {
		int ceiling = Math.max(sanitizeCapacity(capacity), food);
		long raised = (long) food + nutrition;
		int nextFood = (int) Math.max(0L, Math.min(ceiling, raised));
		float nextSaturation = clamp(saturation + saturationGain, 0.0F, saturationCeiling(nextFood));
		return new Fed(nextFood, nextSaturation);
	}

	/** {@link #feed(int, float, int, float, int)} 의 결과를 {@code FoodData} 에 써 넣는다. */
	public static void feed(FoodData data, int nutrition, float saturationGain, int capacity) {
		Fed fed = feed(data.getFoodLevel(), data.getSaturationLevel(), nutrition, saturationGain, capacity);
		data.setFoodLevel(fed.foodLevel());
		data.setSaturation(fed.saturation());
	}

	// ------------------------------------------------------------------ 공유 풀

	/**
	 * 공유 풀의 허기에 {@code gain} 을 더한다. 처치 보상·피격 허기처럼 풀을 직접 채우는 자리가 쓴다.
	 *
	 * <p>{@link #vanillaAddCeiling} 과 같은 까닭으로, 상한이 지금 값보다 낮아도 깎지는 않는다.
	 */
	public static int raise(int current, int gain, int capacity) {
		if (gain <= 0) {
			return current;
		}
		int ceiling = Math.max(sanitizeCapacity(capacity), current);
		return (int) Math.max(0L, Math.min(ceiling, (long) current + gain));
	}

	/** 허기를 상한 안으로 자른다. 공유 풀이 매 바퀴 끝에 부른다. */
	public static int clampFood(long food, int capacity) {
		return (int) Math.max(0L, Math.min(sanitizeCapacity(capacity), food));
	}

	// ------------------------------------------------------------------ 잃을 때

	/**
	 * 증강을 잃은 사람의 개인 허기를 20 으로 깎는다. 넘친 몫은 버린다(사람이 정했다).
	 *
	 * <p>{@link FoodCapacityEffect#remove} 가 부른다. 그 자리는 증강이 꺼질 때·팀에서 빠질 때·
	 * 정의를 다시 붙이려고 잠깐 떼어 낼 때 모두 지난다. 마지막 경우에는 팀이 아직 증강을 갖고
	 * 있는데, 그래도 여기서 깎아도 괜찮다 — {@link StatMirror#rebaseFood} 로 공유 풀이 이 깎음을
	 * 「배가 고파졌다」로 세지 않게 해 두므로 풀은 그대로이고, 다음 바퀴에 풀의 값이 다시 써진다.
	 *
	 * <p>{@code rebaseFood} 를 빼먹으면 팀이 굶는다. {@code StatMirror} 는 팀원마다 지난 바퀴와의
	 * 차이를 <b>합산</b>하므로, 3인 팀이 57 에서 증강을 잃으면 37 이 세 번 빠져 풀이 0 이 된다.
	 */
	public static void dropToVanilla(ServerPlayer player) {
		FoodData data = player.getFoodData();
		if (data.getFoodLevel() <= VANILLA_MAX_FOOD) {
			return;
		}
		data.setFoodLevel(VANILLA_MAX_FOOD);
		data.setSaturation(clamp(data.getSaturationLevel(), 0.0F, saturationCeiling(VANILLA_MAX_FOOD)));
		StatMirror.rebaseFood(player.getUUID(), data.getFoodLevel(), data.getSaturationLevel());
	}

	/**
	 * 팀이 없는 사람이 접속했을 때 저장에서 읽힌 허기를 맞춘다.
	 *
	 * <p>「먹방」 팀에 있다가 접속을 끊은 사이 팀을 잃으면 개인 저장에 57 이 남아 있다. 팀이 있으면
	 * 공유 풀이 덮어쓰므로 상관없고, 팀이 없을 때만 여기서 깎는다.
	 */
	public static void settleWithoutTeam(ServerPlayer player) {
		dropToVanilla(player);
	}

	private static float clamp(float value, float minimum, float maximum) {
		if (!Float.isFinite(value)) {
			return minimum;
		}
		return Math.max(minimum, Math.min(maximum, value));
	}
}
