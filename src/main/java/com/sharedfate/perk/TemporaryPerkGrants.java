package com.sharedfate.perk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.InvulnerableEffect;
import com.sharedfate.perk.effect.NoAttackDamageLossEffect;
import com.sharedfate.perk.effect.OnKillEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * "방아쇠가 당겨지면 몇 초간"이라는 형태를 읽고 실제로 얹는 공용 부품.
 *
 * <p>{@code on_team_hurt} 와 {@code on_critical} 이 같은 모양을 쓴다. 두 타입의 차이는 언제
 * 발동하는가뿐이고, "무엇을 얼마 동안 얹는가"는 완전히 같다.
 *
 * <pre>{@code
 * { "type": "on_critical", "durationSeconds": 3,
 *   "effects": [ { "type": "attribute", ... } ] }
 * }</pre>
 *
 * <p>지속시간은 {@code durationSeconds} 로 적고, 코드베이스의 snake_case 를 따르고 싶으면
 * {@code duration_seconds} 로 적어도 같다. 둘 다 없으면
 * {@link #DEFAULT_DURATION_SECONDS} 초다.
 *
 * <h2>반드시 유한 지속이어야 하는 이유</h2>
 * <p>하위 {@code status_effect} 는 무한이 아니라 {@code durationSeconds} 만큼만 걸린다.
 * {@link PerkStatusEffects} 는 "무한 지속인 상태이상"을 증강이 건 것으로 보고 팀 공유 대상에서
 * 빼는데, 잠깐 거는 것까지 무한으로 걸면 그 판정에 걸려 공유가 어긋난다.
 *
 * <h2>상태이상이 아닌 하위 효과</h2>
 * <p>속성처럼 붙였다 떼야 하는 효과는 {@link TimedPerkEffects} 에 예약해 둔다. 그쪽이 정해진
 * 틱 뒤에 {@link PerkEffect#remove} 를 불러 준다. 그래서 "3초간 공격력 +10%" 처럼 상태이상으로는
 * 표현할 수 없는 비율 변화도 잠깐만 걸 수 있다.
 *
 * <h2>공유 체력을 건드리지 않는다</h2>
 * <p>여기서 얹는 것은 상태이상과 속성 수정자뿐이라 체력·허기 공유 풀을 직접 건드리지 않는다.
 * 팀원 여러 명에게 같은 효과를 얹어도 늘어나는 것은 각자의 저항·공격력이지 공유 풀이 아니므로,
 * 인원수만큼 배수로 들어가는 문제가 생길 자리가 없다.
 */
public final class TemporaryPerkGrants {
	/** {@code durationSeconds} 를 적지 않았을 때의 지속시간. */
	public static final double DEFAULT_DURATION_SECONDS = 3.0;
	/** 지속시간 상한. 이보다 길면 "잠깐"이 아니라 상시나 다름없다. */
	public static final double MAX_DURATION_SECONDS = 60.0;
	public static final int TICKS_PER_SECOND = 20;
	/** 하위 효과 개수 상한. 정의 실수로 수백 개가 들어오는 것을 막는다. */
	static final int MAX_EFFECTS = 16;

	/**
	 * 한 번 발동했을 때 얹을 것들.
	 *
	 * @param durationTicks 얹은 뒤 유지할 시간
	 * @param effects       재귀적으로 읽어 낸 하위 효과들
	 */
	public record Window(int durationTicks, List<PerkEffect> effects) {
		public Window {
			effects = List.copyOf(effects);
		}
	}

	private TemporaryPerkGrants() {
	}

	/**
	 * {@code durationSeconds} 와 {@code effects} 를 읽는다.
	 *
	 * <p>하나라도 잘못됐으면 경고를 남기고 {@code null} 이다. 그러면 이 효과를 가진 증강 전체가
	 * 버려진다.
	 *
	 * @param typeId 경고 문구에 쓸 타입 이름
	 */
	public static @Nullable Window fromJson(String perkId, int index, String typeId, JsonObject json) {
		Integer durationTicks = readDurationTicks(perkId, typeId, json);
		if (durationTicks == null) {
			return null;
		}

		JsonElement element = json.get("effects");
		if (element == null || element.isJsonNull() || !element.isJsonArray()
				|| element.getAsJsonArray().isEmpty()) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 에 effects 가 비어 있습니다", perkId, typeId);
			return null;
		}
		JsonArray array = element.getAsJsonArray();
		if (array.size() > MAX_EFFECTS) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 의 하위 효과가 너무 많습니다 ({})",
					perkId, typeId, array.size());
			return null;
		}

		List<PerkEffect> effects = new ArrayList<>(array.size());
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
			// on_kill 이 쓰는 규칙을 그대로 빌려 쓴다. 최상위 순번은 증강 안에서 유일하므로
			// 두 타입이 한 증강에 함께 있어도 자식 구간이 겹치지 않는다.
			PerkEffect effect = childType.create(perkId, OnKillEffect.nestedIndex(index, child), childJson);
			if (effect == null) {
				return null;
			}
			// 하위 효과에 붙은 {@code drawback} 표시를 여기서 읽는다. 「동병상련」처럼 발동 자체는
			// 이득이고 하위 하나만 대가인 정의가 있어서, 부모가 아니라 하위에 붙여야 뜻이 맞다.
			// 실제로 건너뛰는 자리는 {@link #grant} 다.
			PerkDrawbacks.mark(perkId, childJson, effect);
			effects.add(effect);
		}
		return new Window(durationTicks, effects);
	}

	/**
	 * 한 사람에게 이 창을 얹는다.
	 *
	 * <p>하위 효과 하나가 실패해도 나머지는 계속 얹는다.
	 *
	 * <p><b>대가로 표시된 하위 효과는 세트 「방어 3단계」를 켠 팀에서 얹지 않는다.</b> 창에 대가가
	 * 하나도 없으면 팀 상태를 찾아보지도 않으므로, 그런 정의가 없는 서버에는 아무 부담도 얹히지
	 * 않는다. 걷어내는 쪽({@link #revoke})은 가리지 않는다 — 걸려 있지도 않은 것을 걷어내는 일은
	 * 언제나 안전하고, 세트가 켜지기 <b>전에</b> 얹힌 것이 남아 있을 수 있기 때문이다.
	 *
	 * <p><b>공격력을 깎는 하위 효과는 세트 「무기 3단계」를 켠 팀에서 얹지 않는다.</b> 판정 근거가
	 * {@code drawback} 표시가 아니라 효과가 실제로 하는 일이라, 「동병상련」처럼 표시가 붙은 것도
	 * 앞으로 새로 넣을 정의도 같은 자리에서 함께 걸린다. 창에 감소가 하나도 없으면 팀 상태를
	 * 찾아보지도 않는다.
	 */
	public static void grant(@Nullable ServerPlayer player, @Nullable Window window) {
		if (player == null || window == null) {
			return;
		}
		boolean anyDrawback = PerkDrawbacks.anyDrawback(window.effects());
		boolean anyReduction = NoAttackDamageLossEffect.anyReduction(window.effects());
		// 팀 상태 조회는 한 번이면 된다. 걸러 낼 것이 아무것도 없으면 그 한 번도 하지 않는다.
		TeamState state = anyDrawback || anyReduction
				? TeamLookup.stateOf(player.getUUID())
				: null;
		PerkDrawbacks.Waiver waiver = anyDrawback ? PerkDrawbacks.waiverFor(state) : null;
		NoAttackDamageLossEffect.Gate gate =
				anyReduction ? NoAttackDamageLossEffect.gateFor(state) : null;
		for (PerkEffect effect : window.effects()) {
			if (waiver != null && waiver.waives(null, effect)) {
				continue;
			}
			if (gate != null && gate.suppresses(effect)) {
				continue;
			}
			try {
				grantOne(player, effect, window.durationTicks());
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("잠깐 거는 하위 효과를 얹지 못했습니다", error);
			}
		}
	}

	/** 걸어 둔 것을 지금 곧바로 걷어낸다. 증강을 잃는 자리에서 부른다. */
	public static void revoke(@Nullable ServerPlayer player, @Nullable Window window) {
		if (player == null || window == null) {
			return;
		}
		for (PerkEffect effect : window.effects()) {
			if (effect instanceof StatusEffectPerk status) {
				// 종류째 지우면 다른 증강의 같은 종류 상시분까지 사라진다. 잠깐 건 층만 거둔다.
				PerkStatusStack.revokeTimed(PerkStatusStack.of(player), status.resolvedEffect(),
						status.amplifier());
				continue;
			}
			if (effect instanceof InvulnerableEffect) {
				// 무적은 사람마다 「언제까지」 하나라 누구 몫인지 가를 수 없다. 길어야 십수 초이므로
				// 다른 증강·세트가 함께 걸어 둔 몫까지 지우지 않게 저절로 끝나도록 둔다.
				continue;
			}
			TimedPerkEffects.cancel(player, effect);
		}
	}

	private static void grantOne(ServerPlayer player, PerkEffect effect, int durationTicks) {
		if (effect instanceof StatusEffectPerk status) {
			Holder<MobEffect> resolved = status.resolvedEffect();
			if (resolved == null) {
				return;
			}
			// 무한이 아니라 정해진 시간만 걸어야 PerkStatusEffects 가 증강분으로 오해하지 않는다.
			player.addEffect(new MobEffectInstance(
					resolved, durationTicks, status.amplifier(), false, false, true));
			return;
		}
		// 무적은 상태이상도 속성도 아니다. 겹치면 시간이 합쳐져야 해서 따로 센다(PerkInvulnerability).
		if (effect instanceof InvulnerableEffect) {
			PerkInvulnerability.grant(player, durationTicks);
			return;
		}
		// 속성처럼 스스로 만료되지 않는 효과다. 걷어낼 시점을 예약해 둔다.
		TimedPerkEffects.grant(player, effect, durationTicks);
	}

	/** {@code durationSeconds}(초)를 틱으로 바꾼다. 범위를 벗어나면 null. */
	private static @Nullable Integer readDurationTicks(String perkId, String typeId, JsonObject json) {
		String key = json.has("durationSeconds") ? "durationSeconds" : "duration_seconds";
		Double seconds = PerkEffectType.readDouble(json, key);
		if (seconds == null) {
			JsonElement raw = json.get(key);
			if (raw != null && !raw.isJsonNull()) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 의 {} 가 숫자가 아닙니다", perkId, typeId, key);
				return null;
			}
			seconds = DEFAULT_DURATION_SECONDS;
		}
		if (seconds <= 0.0 || seconds > MAX_DURATION_SECONDS) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 의 {} 가 범위를 벗어났습니다 ({})",
					perkId, typeId, key, seconds);
			return null;
		}
		return Math.max(1, (int) Math.round(seconds * TICKS_PER_SECOND));
	}
}
