package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.team.TeamState;
import org.jetbrains.annotations.Nullable;

/**
 * 방패를 들어 막고 있는 동안에는 낙하 피해를 받지 않게 하는 표시.
 *
 * <p>정의는 {@code { "type": "shield_fall_immunity" }} 하나뿐이고 필드가 없다. 실버
 * 「방패 낙하 면역」이 쓴다. 방패를 <b>손에 들고만</b> 있어서는 안 되고 실제로 우클릭으로
 * 막는 중이어야 한다.
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. 피해가 들어오는 한가운데서 "이 팀이
 * 이걸 가졌는가"만 물어본다.
 *
 * <h2>실제로 피해를 막는 곳</h2>
 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} 진입점에서 부르는
 * {@link com.sharedfate.perk.PerkDamage#blocksFallDamage} 다. 이미 이 모드가 피해를 가로채고
 * 있는 자리라 새 mixin 이 필요 없다.
 *
 * <p>배율을 0 으로 만드는 길({@code damage_taken_from} + {@code multiplier: 0})로는 이 효과를
 * 만들 수 없다. 배율은 조건 없이 언제나 걸리는데 이 효과는 <b>막는 중일 때만</b> 걸려야 하고,
 * 피해량 0 으로 들어간 피해도 바닐라 입장에서는 피해라서 피격 소리와 무적시간이 생긴다.
 * 진입점에서 통째로 버리면 그런 흔적이 남지 않는다.
 *
 * <h2>어느 피해가 낙하 피해인가</h2>
 * <p>{@code minecraft:fall} 하나만 본다. 종유석에 찔린 것({@code stalagmite})이나 위에서 떨어진
 * 모루({@code falling_anvil})까지 덮는 {@code #minecraft:is_fall} 태그는 쓰지 않았다. 이
 * 증강이 약속하는 것은 "떨어져도 안 다친다"이지 "낙하와 관련된 모든 사고에 면역"이 아니다.
 */
public final class ShieldFallImmunityEffect implements PerkEffect {
	/** 상태가 없으므로 하나만 만들어 돌려쓴다. */
	public static final ShieldFallImmunityEffect INSTANCE = new ShieldFallImmunityEffect();

	private ShieldFallImmunityEffect() {
	}

	/** JSON에서 만든다. 읽을 필드가 없어 언제나 성공한다. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}

	/**
	 * 이 팀이 이 효과를 가졌는가.
	 *
	 * <p>훑는 방식은 {@code PerkSwapRules.rallyPoint} 와 같다. 팀이 없거나, 증강을 껐거나, 아직
	 * 아무 증강도 없으면 곧바로 거짓이다. 풀에서 사라진 id 는 건너뛴다.
	 */
	public static boolean heldBy(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return false;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof ShieldFallImmunityEffect) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * 이 피해를 통째로 버려야 하는가.
	 *
	 * <p>세 가지가 모두 참일 때만 참이다. 판정만 떼어 놓았으므로 살아 있는 월드 없이 시험할 수
	 * 있다. 실제 피해원과 자세를 읽는 일은 {@link com.sharedfate.perk.PerkDamage} 가 한다.
	 *
	 * @param state       피해를 받는 팀원의 팀 상태
	 * @param fallDamage  이 피해가 {@code minecraft:fall} 인가
	 * @param blocking    지금 방패로 막는 중인가
	 */
	public static boolean blocks(@Nullable TeamState state, boolean fallDamage, boolean blocking) {
		return fallDamage && blocking && heldBy(state);
	}
}
