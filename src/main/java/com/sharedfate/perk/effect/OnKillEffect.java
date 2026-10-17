package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.TimedPerkEffects;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 팀원이 몹을 처치했을 때 팀에게 보상을 준다.
 *
 * <p>예: {@code { "type": "on_kill", "food": 2, "saturation": 1.0, "health": 1.0 }} 는 처치할
 * 때마다 팀 허기 2, 포만감 1.0, 체력 1.0 을 채운다. 실버 11 사냥꾼의 식사와 프리즘 13 포식이
 * 이 타입을 쓴다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 "얼마를 줄 것인가"만 들고 있는 자료 그릇이다. "언제 누가 무엇을 죽였는가"를 보고
 * 실제로 공유 풀을 채우는 일은 {@link com.sharedfate.perk.PerkKillRewards} 가 맡는다.
 *
 * <p>회복은 팀원 개인이 아니라 <b>팀 공유 값</b>에 들어간다. 그 이유와 이중 적용을 피하는
 * 방법은 {@link com.sharedfate.perk.PerkKillRewards} 에 있다.
 *
 * <h2>{@code effects} — 처치 시 잠깐 부여할 효과</h2>
 * <p>배열 각 항목은 보통의 효과 정의와 형태가 똑같고 {@link PerkEffectType} 이 재귀적으로
 * 읽는다. 상시로 붙는 효과가 아니라 처치 순간에만 얹히는 것이라, {@code status_effect} 는
 * 무한 지속이 아니라 {@code duration} 초 동안만 걸린다. {@code duration} 을 적지 않으면
 * {@link #DEFAULT_DURATION_SECONDS} 초다.
 *
 * <p>{@code status_effect} 가 아닌 하위 효과({@code attribute} 등)는 스스로 만료되지 않으므로
 * {@link TimedPerkEffects} 에 맡겨 {@code duration} 초 뒤에 걷어낸다. 상태이상과 달리 팀
 * 공유를 타지 않아 <b>처치한 사람에게만</b> 붙는다. 증강을 잃으면 {@link #remove} 가, 접속을
 * 끊거나 부활하면 {@link TimedPerkEffects#revokeAll} 이 남은 것을 곧바로 걷어낸다.
 */
public final class OnKillEffect implements PerkEffect {
	/** 허기는 20 이 최대다. 그 이상 적어도 의미가 없다. */
	static final int MAX_FOOD = 20;
	/** 포만감은 허기를 넘지 못하므로 상한이 같다. */
	static final float MAX_SATURATION = 20.0F;
	/** 체력 회복량 상한. {@code TeamState} 가 받아들이는 최대 체력과 같은 값이다. */
	static final float MAX_HEALTH = 1024.0F;
	/** {@code duration} 을 적지 않았을 때 하위 효과가 유지되는 시간. */
	public static final double DEFAULT_DURATION_SECONDS = 5.0;
	/** 하위 효과 지속시간 상한. 이보다 길면 "잠깐"이 아니라 상시나 다름없다. */
	static final double MAX_DURATION_SECONDS = 600.0;
	public static final int TICKS_PER_SECOND = 20;

	/**
	 * 하위 효과의 순번을 부모와 겹치지 않게 밀어 두는 폭.
	 *
	 * <p>순번은 {@link AttributeEffect#modifierId} 가 수정자 이름을 만드는 데 쓰므로 한 증강
	 * 안에서 반드시 유일해야 한다. 같은 값이 두 번 나오면 뒤에 붙은 수정자가 앞의 것을 덮는다.
	 */
	static final int NESTED_INDEX_STRIDE = 1000;

	/**
	 * 처치 순간에 잠깐 얹을 하위 효과 하나.
	 *
	 * @param effect        재귀적으로 읽어 낸 효과
	 * @param durationTicks 얹은 뒤 유지할 시간. 상태이상은 지속시간으로, 그 밖의 효과는 걷어낼 예약으로 쓴다
	 */
	public record Grant(PerkEffect effect, int durationTicks) {
	}

	private final int food;
	private final float saturation;
	private final float health;
	private final List<Grant> grants;

	public OnKillEffect(int food, float saturation, float health, List<Grant> grants) {
		this.food = food;
		this.saturation = saturation;
		this.health = health;
		this.grants = List.copyOf(grants);
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		int food = PerkEffectType.readInt(json, "food", 0);
		if (food < 0 || food > MAX_FOOD) {
			SharedFateMod.LOGGER.warn("증강 {}: on_kill 의 food 값이 범위를 벗어났습니다 ({})", perkId, food);
			return null;
		}

		Float saturation = readAmount(perkId, json, "saturation", MAX_SATURATION);
		if (saturation == null) {
			return null;
		}
		Float health = readAmount(perkId, json, "health", MAX_HEALTH);
		if (health == null) {
			return null;
		}

		List<Grant> grants = readGrants(perkId, index, json);
		if (grants == null) {
			return null;
		}

		if (food == 0 && saturation == 0.0F && health == 0.0F && grants.isEmpty()) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: on_kill 이 아무것도 주지 않습니다. food/saturation/health/effects 중 하나는 있어야 합니다",
					perkId);
			return null;
		}
		return new OnKillEffect(food, saturation, health, grants);
	}

	/** 안전한 범위로 자른 허기 회복량. */
	public int foodFor() {
		return Math.min(MAX_FOOD, Math.max(0, food));
	}

	/** 안전한 범위로 자른 포만감 회복량. */
	public float saturationFor() {
		return Math.min(MAX_SATURATION, Math.max(0.0F, saturation));
	}

	/** 안전한 범위로 자른 체력 회복량. */
	public float healthFor() {
		return Math.min(MAX_HEALTH, Math.max(0.0F, health));
	}

	/**
	 * 처치한 팀원에게 하위 효과를 얹는다.
	 *
	 * <p>상태이상은 한 명에게만 걸어도 {@code EffectSync} 가 팀 전원에게 퍼뜨린다. 여기서
	 * 굳이 팀 전원을 돌지 않는 이유가 그것이다. 지속시간이 유한하므로
	 * {@code PerkStatusEffects} 가 증강분으로 오해해 공유에서 빼는 일도 없다.
	 *
	 * <p>하위 효과 하나가 실패해도 나머지는 계속 얹는다.
	 */
	public void grantTemporaryEffects(@Nullable ServerPlayer killer) {
		if (killer == null || grants.isEmpty()) {
			return;
		}
		for (Grant grant : grants) {
			try {
				grantOne(killer, grant);
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("on_kill 의 하위 효과를 얹지 못했습니다", error);
			}
		}
	}

	private static void grantOne(ServerPlayer killer, Grant grant) {
		if (!(grant.effect() instanceof StatusEffectPerk status)) {
			// 속성처럼 스스로 만료되지 않는 효과다. 걷어낼 시점을 예약해 둔다. 연달아 처치하면
			// 다시 붙이지 않고 만료 시각만 미룬다.
			TimedPerkEffects.grant(killer, grant.effect(), grant.durationTicks());
			return;
		}
		Holder<MobEffect> resolved = status.resolvedEffect();
		if (resolved == null) {
			return;
		}
		// 무한 지속이 아니라 정해진 시간만 걸어야 "처치 순간에 잠깐"이 된다.
		killer.addEffect(new MobEffectInstance(
				resolved, grant.durationTicks(), status.amplifier(), false, false, true));
	}

	/**
	 * 상시로 붙는 것이 없으므로 {@link #apply} 는 재정의하지 않는다. 대신 증강을 잃을 때는
	 * 마침 걸려 있던 속성 수정자를 걷어내야 한다. 상태이상 쪽은 유한 지속이라 스스로 끝난다.
	 */
	@Override
	public void remove(ServerPlayer player) {
		if (player == null) {
			return;
		}
		for (Grant grant : grants) {
			if (!(grant.effect() instanceof StatusEffectPerk)) {
				TimedPerkEffects.cancel(player, grant.effect());
			}
		}
	}

	public int food() {
		return food;
	}

	public float saturation() {
		return saturation;
	}

	public float health() {
		return health;
	}

	public List<Grant> grants() {
		return grants;
	}

	/** 이 효과가 공유 풀을 건드리는가. 하위 효과만 있는 정의도 있을 수 있다. */
	public boolean restoresStats() {
		return food > 0 || saturation > 0.0F || health > 0.0F;
	}

	/**
	 * 하위 효과의 순번.
	 *
	 * <p>{@code (부모 순번 + 1) * 1000 + 자식 순번} 이다. 최상위 효과 순번은 0, 1, 2 … 로
	 * 아주 작아서 1000 이상과 절대 겹치지 않고, 부모가 다르면 구간 자체가 달라진다. 처음 1을
	 * 더하는 것은 부모 순번 0 의 자식이 1000 부터 시작하게 만들기 위해서다.
	 *
	 * <p>on_kill 안에 또 on_kill 을 넣어도 규칙이 그대로 겹쳐진다. 자식이 1000 개를 넘으면
	 * 구간이 무너지지만, 그런 정의는 애초에 만들 수 없다.
	 */
	public static int nestedIndex(int parentIndex, int childIndex) {
		long shifted = ((long) Math.max(0, parentIndex) + 1L) * NESTED_INDEX_STRIDE
				+ Math.max(0, childIndex);
		return (int) Math.min(Integer.MAX_VALUE, shifted);
	}

	/**
	 * 0 이상 {@code maximum} 이하의 실수 필드.
	 *
	 * <p>필드를 아예 안 적었으면 0 이다. 적었는데 숫자가 아니거나 범위를 벗어나면 null 이고,
	 * 그러면 이 효과를 버린다. "안 적었다"와 "잘못 적었다"를 구분해야 오타를 조용히 0 으로
	 * 넘기지 않는다.
	 */
	private static @Nullable Float readAmount(String perkId, JsonObject json, String key,
			float maximum) {
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return 0.0F;
		}
		Double raw = PerkEffectType.readDouble(json, key);
		if (raw == null || raw < 0.0 || raw > maximum) {
			SharedFateMod.LOGGER.warn("증강 {}: on_kill 의 {} 값이 올바르지 않습니다 ({})",
					perkId, key, element);
			return null;
		}
		return raw.floatValue();
	}

	/**
	 * {@code effects} 배열을 재귀적으로 읽는다.
	 *
	 * <p>필드가 없으면 빈 목록이다. 하나라도 잘못됐으면 null 을 돌려주고, 그러면 이 증강 전체가
	 * 버려진다. {@code PerkRegistry} 가 최상위 효과에 쓰는 규칙과 같다. 설명은 그대로인데 효과
	 * 일부만 빠진 증강은 플레이어를 속이는 셈이기 때문이다.
	 */
	private static @Nullable List<Grant> readGrants(String perkId, int index, JsonObject json) {
		JsonElement element = json.get("effects");
		if (element == null || element.isJsonNull()) {
			return List.of();
		}
		if (!element.isJsonArray()) {
			SharedFateMod.LOGGER.warn("증강 {}: on_kill 의 effects 가 배열이 아닙니다", perkId);
			return null;
		}

		JsonArray array = element.getAsJsonArray();
		List<Grant> grants = new ArrayList<>(array.size());
		for (int child = 0; child < array.size(); child++) {
			JsonElement raw = array.get(child);
			if (raw == null || !raw.isJsonObject()) {
				SharedFateMod.LOGGER.warn("증강 {}: on_kill 의 {}번째 하위 효과가 객체가 아닙니다",
						perkId, child);
				return null;
			}
			JsonObject childJson = raw.getAsJsonObject();
			String typeId = PerkEffectType.readString(childJson, "type");
			PerkEffectType type = PerkEffectType.fromId(typeId);
			if (type == null) {
				SharedFateMod.LOGGER.warn("증강 {}: on_kill 하위 효과의 알 수 없는 type 입니다 ({})",
						perkId, typeId);
				return null;
			}
			PerkEffect effect = type.create(perkId, nestedIndex(index, child), childJson);
			if (effect == null) {
				return null;
			}
			Integer duration = readDurationTicks(perkId, childJson);
			if (duration == null) {
				return null;
			}
			grants.add(new Grant(effect, duration));
		}
		return grants;
	}

	/** 하위 효과의 {@code duration}(초)을 틱으로 바꾼다. 범위를 벗어나면 null. */
	private static @Nullable Integer readDurationTicks(String perkId, JsonObject json) {
		Double seconds = PerkEffectType.readDouble(json, "duration");
		if (seconds == null) {
			if (json.has("duration") && !json.get("duration").isJsonNull()) {
				SharedFateMod.LOGGER.warn("증강 {}: on_kill 하위 효과의 duration 이 숫자가 아닙니다", perkId);
				return null;
			}
			seconds = DEFAULT_DURATION_SECONDS;
		}
		if (seconds <= 0.0 || seconds > MAX_DURATION_SECONDS) {
			SharedFateMod.LOGGER.warn("증강 {}: on_kill 하위 효과의 duration 이 범위를 벗어났습니다 ({})",
					perkId, seconds);
			return null;
		}
		return Math.max(1, (int) Math.round(seconds * TICKS_PER_SECOND));
	}
}
