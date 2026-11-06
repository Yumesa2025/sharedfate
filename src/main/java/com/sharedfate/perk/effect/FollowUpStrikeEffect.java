package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.team.TeamState;
import org.jetbrains.annotations.Nullable;

/**
 * 근접 공격이 들어가면 확률로 <b>한 번 더</b> 때린다. 세트 「무기 3단계」가 쓴다.
 *
 * <pre>
 * { "type": "follow_up_strike", "chance": 0.6, "damage_fraction": 0.5 }
 * </pre>
 *
 * <p>사람이 정한 것(2026-11-06) — 「공격하면 60% 확률로 절반 피해를 한 번 더」. 기존 3단계(공격 속도
 * +30% · 공격력 감소 무효)를 갈아 치웠다. 평균으로는 피해 +30% 로 예전과 비슷하다. 공격력 감소
 * 무효를 맡던 효과 {@code no_attack_damage_loss} 는 쓰는 세트가 없어져 0.45.10-dev 에서 걷어냈다.
 *
 * <p>공격 게이지는 보지 않는다. 사람이 정한 것(2026-11-06) — 처음엔 게이지 90% 이상만 굴렸는데,
 * 칼(0.63초에 다 참)보다 느린 도끼 · 삽 · 곡괭이는 칼 박자로 치면 거의 안 터져 「칼만 적용된다」로
 * 보였다. 두 번째 타격은 첫 타격 피해의 절반이고 첫 타격이 이미 게이지만큼 줄어 있으므로, 연타해도
 * 약한 공격의 절반이 붙을 뿐이다.
 *
 * <p>이 클래스는 수치만 든다. 언제 · 누구에게 · 어떻게 한 번 더 넣는지는
 * {@link com.sharedfate.perk.PerkFollowUpStrike} 가 정한다. 팀원에게 붙일 것이 없어
 * {@link PerkEffect#apply} 는 기본(아무것도 안 함) 그대로다.
 */
public final class FollowUpStrikeEffect implements PerkEffect {
	/** 두 번째 타격까지의 틱. 같은 틱에 넣으면 한 대로 보인다. */
	public static final int DEFAULT_DELAY_TICKS = 4;

	private final double chance;
	private final double damageFraction;

	public FollowUpStrikeEffect(double chance, double damageFraction) {
		this.chance = chance;
		this.damageFraction = damageFraction;
	}

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double chance = PerkEffectType.readDouble(json, "chance");
		Double fraction = PerkEffectType.readDouble(json, "damage_fraction");
		if (chance == null || !(chance > 0.0) || chance > 1.0
				|| fraction == null || !(fraction > 0.0) || fraction > 1.0) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: follow_up_strike 의 chance({}) 또는 damage_fraction({}) 이 0~1 밖입니다",
					perkId, chance, fraction);
			return null;
		}
		return new FollowUpStrikeEffect(chance, fraction);
	}

	public double chance() {
		return chance;
	}

	public double damageFraction() {
		return damageFraction;
	}

	/** {@code roll} 은 0 이상 1 미만의 난수. */
	public boolean triggers(double roll) {
		return roll < chance;
	}

	/** 첫 타격에 넣은 피해로 두 번째 타격의 피해를 낸다. */
	public float followUpDamage(float firstHit) {
		if (!(firstHit > 0.0F) || !Float.isFinite(firstHit)) {
			return 0.0F;
		}
		return (float) (firstHit * damageFraction);
	}

	/**
	 * 이 팀이 가진 「한 번 더」. 여럿이면 처음 것 하나만 쓴다 — 확률을 겹치면 100% 를 넘는다.
	 *
	 * <p>보유 증강과 켜진 세트를 <b>둘 다</b> 본다. {@link NoDefenseDrawbacksEffect#heldBy} 와 같은 모양이다.
	 */
	public static @Nullable FollowUpStrikeEffect heldBy(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled) {
			return null;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof FollowUpStrikeEffect strike) {
					return strike;
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof FollowUpStrikeEffect strike) {
				return strike;
			}
		}
		return null;
	}
}
