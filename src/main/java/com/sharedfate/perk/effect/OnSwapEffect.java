package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.TemporaryPerkGrants;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 팀원 위치가 바뀌는 시점에 팀 전원에게 잠깐 효과를 얹는다.
 *
 * <pre>{@code
 * { "type": "on_swap",
 *   "effects": [ { "type": "status_effect", "effect": "minecraft:resistance",
 *                  "amplifier": 3, "duration": 3 } ] }
 * }</pre>
 *
 * <p>실버 「본진이 바뀐다」가 이 타입을 쓴다.
 *
 * <h2>순간이동이 막혀도 시점은 온다</h2>
 * <p>{@link SwapBlockEffect} 가 함께 걸려 있으면 자리는 바뀌지 않지만 <b>바뀔 시점 자체는
 * 그대로 찾아온다.</b> 그래서 「뿌리내린 발」을 함께 가진 팀도 이 효과는 주기마다 받는다.
 * 두 효과의 순서를 정하는 곳은 {@link com.sharedfate.sync.PositionSwapManager} 다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 "무엇을 얼마 동안 얹을 것인가"만 들고 있는 자료 그릇이다. "언제 누구에게"는
 * {@link com.sharedfate.perk.PerkSwapRules} 와 {@code PositionSwapManager} 가 정한다.
 *
 * <h2>{@code effects} — 교환 시점에 잠깐 부여할 효과</h2>
 * <p>배열 각 항목은 보통의 효과 정의와 형태가 똑같고 {@link PerkEffectType} 이 재귀적으로
 * 읽는다. {@link OnKillEffect} 와 마찬가지로 지속시간은 <b>항목마다</b> {@code duration} 초로
 * 적고, 적지 않으면 {@link OnKillEffect#DEFAULT_DURATION_SECONDS} 초다. 하나의
 * {@code durationSeconds} 를 형제 전체가 나눠 쓰는 {@link TemporaryPerkGrants} 쪽과 여기서
 * 갈린다. 저항 3초와 구속 5초처럼 서로 다른 길이를 한 증강에 담을 수 있어야 하기 때문이다.
 *
 * <p>얹고 걷어내는 일 자체는 {@link TemporaryPerkGrants} 에 그대로 맡긴다. 상태이상은 유한
 * 지속으로 걸고 속성처럼 스스로 만료되지 않는 효과는 {@link com.sharedfate.perk.TimedPerkEffects}
 * 에 예약하는 갈림이 이미 그쪽에 있어서, 여기서 다시 쓸 이유가 없다.
 *
 * <p>유한 지속이어야 하는 이유도 그쪽과 같다. 무한으로 걸면
 * {@link com.sharedfate.perk.PerkStatusEffects} 가 상시 증강분으로 오해해 팀 공유에서 빼 버린다.
 */
public final class OnSwapEffect implements PerkEffect {
	/** 하위 효과 개수 상한. 정의 실수로 수백 개가 들어오는 것을 막는다. */
	static final int MAX_EFFECTS = 16;

	/**
	 * 한 시점에 잠깐 얹을 하위 효과 하나.
	 *
	 * <p>{@link OnKillEffect.Grant} 와 같은 구조다. 다른 점은 걸고 걷어내는 길이 하나뿐이라는
	 * 것으로, 상태이상이든 속성이든 {@link TemporaryPerkGrants} 가 알아서 갈라 준다.
	 *
	 * @param effect        재귀적으로 읽어 낸 효과
	 * @param durationTicks 얹은 뒤 유지할 시간
	 */
	public record Grant(PerkEffect effect, int durationTicks) {
		/**
		 * 잠깐 걸었다 걷어내는 기존 장치에 넘길 형태.
		 *
		 * <p>{@link TemporaryPerkGrants.Window} 는 아무 상태도 들고 있지 않고, 예약의 열쇠가 되는
		 * 것은 {@link #effect} 객체 쪽이다. 그래서 부를 때마다 새로 만들어도 같은 예약을 가리킨다.
		 */
		public TemporaryPerkGrants.Window window() {
			return new TemporaryPerkGrants.Window(durationTicks, List.of(effect));
		}
	}

	private final List<Grant> grants;

	public OnSwapEffect(List<Grant> grants) {
		this.grants = List.copyOf(grants);
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		List<Grant> grants = readGrants(perkId, index, "on_swap", json, true);
		if (grants == null) {
			return null;
		}
		return new OnSwapEffect(grants);
	}

	/** 이 팀원에게 잠깐 효과를 얹는다. */
	public void grantTo(@Nullable ServerPlayer player) {
		grantAll(player, grants);
	}

	/**
	 * 상시로 붙는 것이 없으므로 {@link #apply} 는 재정의하지 않는다. 대신 증강을 잃을 때는
	 * 마침 걸려 있던 것을 걷어내야 한다. {@link OnTeamHurtEffect} 와 같은 처리다.
	 */
	@Override
	public void remove(ServerPlayer player) {
		revokeAll(player, grants);
	}

	public List<Grant> grants() {
		return grants;
	}

	// ------------------------------------------------------------------ 공용 부품

	/**
	 * {@code effects} 배열을 재귀적으로 읽는다.
	 *
	 * <p>{@link GatherEffect} 도 같은 모양을 쓰므로 여기 한 곳에 모아 뒀다. 하나라도 잘못됐으면
	 * null 을 돌려주고, 그러면 이 효과를 가진 증강 전체가 버려진다. 설명은 그대로인데 효과
	 * 일부만 빠진 증강은 플레이어를 속이는 셈이기 때문이다.
	 *
	 * @param typeId   경고 문구에 쓸 타입 이름
	 * @param required 배열이 반드시 있어야 하는가. 거짓이면 없을 때 빈 목록이다
	 */
	static @Nullable List<Grant> readGrants(String perkId, int index, String typeId,
			JsonObject json, boolean required) {
		JsonElement element = json.get("effects");
		if (element == null || element.isJsonNull()) {
			if (required) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 에 effects 가 비어 있습니다", perkId, typeId);
				return null;
			}
			return List.of();
		}
		if (!element.isJsonArray()) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 의 effects 가 배열이 아닙니다", perkId, typeId);
			return null;
		}

		JsonArray array = element.getAsJsonArray();
		if (required && array.isEmpty()) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 에 effects 가 비어 있습니다", perkId, typeId);
			return null;
		}
		if (array.size() > MAX_EFFECTS) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 의 하위 효과가 너무 많습니다 ({})",
					perkId, typeId, array.size());
			return null;
		}

		List<Grant> grants = new ArrayList<>(array.size());
		for (int child = 0; child < array.size(); child++) {
			JsonElement raw = array.get(child);
			if (raw == null || !raw.isJsonObject()) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 의 {}번째 하위 효과가 객체가 아닙니다",
						perkId, typeId, child);
				return null;
			}
			JsonObject childJson = raw.getAsJsonObject();
			String childTypeId = PerkEffectType.readString(childJson, "type");
			PerkEffectType childType = PerkEffectType.fromId(childTypeId);
			if (childType == null) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 하위 효과의 알 수 없는 type 입니다 ({})",
						perkId, typeId, childTypeId);
				return null;
			}
			// 순번은 속성 수정자 이름을 만드는 데 쓰이므로 부모·형제와 절대 겹치면 안 된다.
			// on_kill 이 쓰는 규칙을 그대로 빌려 쓴다.
			PerkEffect effect = childType.create(perkId, OnKillEffect.nestedIndex(index, child), childJson);
			if (effect == null) {
				return null;
			}
			Integer duration = readDurationTicks(perkId, typeId, childJson);
			if (duration == null) {
				return null;
			}
			grants.add(new Grant(effect, duration));
		}
		return grants;
	}

	/**
	 * 한 사람에게 하위 효과를 모두 얹는다.
	 *
	 * <p>하나가 실패해도 나머지는 계속 얹는다. 서버 틱 한가운데서 불리므로 어떤 예외도 밖으로
	 * 내보내지 않는다.
	 */
	static void grantAll(@Nullable ServerPlayer player, List<Grant> grants) {
		if (player == null || grants.isEmpty()) {
			return;
		}
		for (Grant grant : grants) {
			try {
				TemporaryPerkGrants.grant(player, grant.window());
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("교환 시점의 하위 효과를 얹지 못했습니다", error);
			}
		}
	}

	/** 걸어 둔 것을 지금 곧바로 걷어낸다. 증강을 잃는 자리에서 부른다. */
	static void revokeAll(@Nullable ServerPlayer player, List<Grant> grants) {
		if (player == null || grants.isEmpty()) {
			return;
		}
		for (Grant grant : grants) {
			try {
				TemporaryPerkGrants.revoke(player, grant.window());
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("교환 시점에 걸어 둔 효과를 걷어내지 못했습니다", error);
			}
		}
	}

	/**
	 * 하위 효과의 {@code duration}(초)을 틱으로 바꾼다. 범위를 벗어나면 null.
	 *
	 * <p>{@link OnKillEffect} 와 같은 규칙이고 같은 상한을 쓴다. "안 적었다"와 "잘못 적었다"를
	 * 구분해야 오타를 조용히 기본값으로 넘기지 않는다.
	 */
	private static @Nullable Integer readDurationTicks(String perkId, String typeId, JsonObject json) {
		Double seconds = PerkEffectType.readDouble(json, "duration");
		if (seconds == null) {
			if (json.has("duration") && !json.get("duration").isJsonNull()) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 하위 효과의 duration 이 숫자가 아닙니다",
						perkId, typeId);
				return null;
			}
			seconds = OnKillEffect.DEFAULT_DURATION_SECONDS;
		}
		if (seconds <= 0.0 || seconds > OnKillEffect.MAX_DURATION_SECONDS) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 하위 효과의 duration 이 범위를 벗어났습니다 ({})",
					perkId, typeId, seconds);
			return null;
		}
		return Math.max(1, (int) Math.round(seconds * OnKillEffect.TICKS_PER_SECOND));
	}
}
