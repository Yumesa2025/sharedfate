package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;

/**
 * 교환 시점마다 일정 확률로 <b>아무도 자리를 바꾸지 않는다.</b> 실버 「본진이 바뀔까?」가 쓴다.
 *
 * <pre>{@code
 * { "type": "swap_skip_chance", "chance": 0.5 }
 * }</pre>
 *
 * <p>{@code chance} 는 생략할 수 있고 생략하면 {@link #DEFAULT_CHANCE}(50%)다. 0 초과 1 이하만
 * 받는다. 0 은 아무 일도 안 하는 증강이 되고, 1 은 「뿌리내린 발」과 똑같아진다.
 *
 * <h2>제자리여도 교환 시점은 온다</h2>
 * <p>판정에 걸려 자리가 그대로여도 카운트다운은 끝까지 나오고, 그 시점에 받는 것은 전부 받는다 —
 * {@code on_swap}(증강·교환 세트), 「열외」의 보너스, 「폭발 교환」의 폭발, 다음 주기 계산. 막는
 * 것은 순간이동 하나뿐이라는 점에서 {@link SwapBlockEffect} 와 같은 결이다. 다른 점은 폭발이다 —
 * 막힘은 아무도 자리를 비우지 않았다고 터뜨리지 않지만, 이쪽은 「교환 때 나오는 효과는 그대로」가
 * 약속이라 각자 서 있는 자리에서 터뜨린다({@code PositionSwapManager.stayInPlace}).
 *
 * <p>예전에는 이 증강(id {@code sharedfate:staggered_swap})이 「시차」였다 — 한 명씩 5~10초
 * 간격으로 옮겼다. 저장 호환을 위해 id 만 그대로 두었다.
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. 교환 시점에 확률만 물어본다
 * ({@link com.sharedfate.perk.PerkSwapRules#swapSkipChance}).
 */
public final class SwapSkipChanceEffect implements PerkEffect {
	/** {@code chance} 를 적지 않았을 때의 확률. */
	public static final double DEFAULT_CHANCE = 0.5;

	private final double chance;

	public SwapSkipChanceEffect(double chance) {
		this.chance = chance;
	}

	/** JSON에서 만든다. 확률이 숫자가 아니거나 범위를 벗어나면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		if (!json.has("chance") || json.get("chance").isJsonNull()) {
			return new SwapSkipChanceEffect(DEFAULT_CHANCE);
		}
		Double chance = PerkEffectType.readDouble(json, "chance");
		if (chance == null || !(chance > 0.0) || chance > 1.0) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: swap_skip_chance 의 chance 가 0 초과 1 이하가 아닙니다 ({})",
					perkId, chance);
			return null;
		}
		return new SwapSkipChanceEffect(chance);
	}

	/** 한 교환 시점에 자리를 그대로 둘 확률(0 초과 1 이하). */
	public double chance() {
		return chance;
	}
}
