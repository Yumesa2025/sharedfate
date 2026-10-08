package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkDrawbacks;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.team.TeamState;
import org.jetbrains.annotations.Nullable;

/**
 * 가지고 있는 <b>방어 유형</b> 증강들의 「대가」가 사라지게 하는 표시.
 * 세트 「방어 3단계 — 대가는 안 치른다」가 쓴다.
 *
 * <p>정의는 {@code { "type": "no_defense_drawbacks" }} 하나뿐이고 필드가 없다.
 *
 * <p>{@link PerkEffect#apply} 로 팀원에게 붙일 것이 없다. 「효과를 붙인다」가 아니라 「이미 있는
 * 효과 중 일부를 건너뛰게 한다」는 보상이라, 이 클래스는 「이 팀에 그 규칙이 켜졌는가」라는
 * 물음에만 답한다.
 *
 * <h2>실제로 대가를 건너뛰는 곳</h2>
 * <p>{@link PerkDrawbacks.Waiver} 를 쓰는 자리들이다. 지금은 다섯 곳이고, 각각 다섯 대가가
 * 실제로 지나는 경로다.
 *
 * <ul>
 *   <li>{@code PerkManager.refreshPlayer} — {@code attribute} 로 붙였다 떼는 대가
 *       (「바람 빠진 방패」의 넉백 2배, 「맨살의 각오」의 채굴 속도 감소)</li>
 *   <li>{@code PerkManager.multiplier} — 받는·주는 피해 배율
 *       (「불굴」의 체력이 가득 찼을 때 ×1.1)</li>
 *   <li>{@code ConditionalPerkManager.refreshPlayer} — 위와 짝을 이루는 주기 재평가.
 *       여기서 걸러 내지 않으면 {@code refreshPlayer} 가 걷어낸 것을 반 초 뒤에 다시 붙인다</li>
 *   <li>{@code PerkDamage.takenSourceMultiplier} — {@code damage_taken_from}
 *       (「화살막이」의 폭발 피해 ×1.2)</li>
 *   <li>{@code TemporaryPerkGrants.grant} — {@code on_team_hurt} 의 하위 효과
 *       (「동병상련」의 발동 중 공격력 감소)</li>
 * </ul>
 *
 * <h2>몹 체력은 건드리지 않는다</h2>
 * <p>「버티는 방패」의 몹 체력 ×1.15 와 「철벽」의 ×1.5 에는 {@code drawback} 표시를 붙이지
 * 않았다. 그 둘은 팀이 아니라 <b>월드에 걸리는</b> 값이라 성격이 다르다.
 */
public final class NoDefenseDrawbacksEffect implements PerkEffect {
	/** 상태가 없으므로 하나만 만들어 돌려쓴다. */
	public static final NoDefenseDrawbacksEffect INSTANCE = new NoDefenseDrawbacksEffect();

	private NoDefenseDrawbacksEffect() {
	}

	/** JSON에서 만든다. 읽을 필드가 없어 언제나 성공한다. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}

	/**
	 * 이 팀이 이 표시를 가졌는가. 가졌으면 방어 유형 증강의 대가가 사라진다.
	 *
	 * <p>팀이 없거나 증강을 껐으면 곧바로 거짓이다. 보유 증강과 켜진 세트를 <b>둘 다</b> 본다 —
	 * 지금 이 형을 태우는 것은 세트뿐이지만, 훑는 자리가 한쪽만 보면 나중에 증강에 붙였을 때
	 * 빌드도 통과하고 로그도 없이 무동작이 된다. {@link PrismRerollEffect#heldBy} 와 같은 모양이다.
	 */
	public static boolean heldBy(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled) {
			return false;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof NoDefenseDrawbacksEffect) {
					return true;
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof NoDefenseDrawbacksEffect) {
				return true;
			}
		}
		return false;
	}
}
