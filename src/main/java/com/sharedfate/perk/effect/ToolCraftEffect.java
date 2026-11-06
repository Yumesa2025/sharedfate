package com.sharedfate.perk.effect;

import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.team.TeamState;
import org.jetbrains.annotations.Nullable;

/**
 * 마법부여대·모루·제작대가 팀의 보유 여부만 물어보는 표시. 읽을 필드가 없다.
 *
 * <ul>
 *   <li>{@link #SHOVEL_SWORD_ENCHANTING} — 프리즘 「삽질의 대가」. 삽을 검처럼 마법부여한다
 *       ({@link com.sharedfate.enchant.ShovelSwordEnchanting}).</li>
 *   <li>{@link #DIAMOND_TO_NETHERITE} — 프리즘 「금기의 광석」. 제작대의 다이아몬드 9개가
 *       다이아몬드 블록 대신 네더라이트 주괴가 된다
 *       ({@link com.sharedfate.recipe.DiamondNetheriteRecipe}).</li>
 * </ul>
 *
 * <p>{@link ShieldCraftEffect} 와 같은 모양이다. 효과는 증강 정의 안에 그대로 들어 있으므로
 * 「이 팀이 가졌는가」는 보유 증강과 세트 효과를 훑어서 답한다.
 */
public enum ToolCraftEffect implements PerkEffect {
	SHOVEL_SWORD_ENCHANTING,
	DIAMOND_TO_NETHERITE;

	/** 이 팀이 이 표시를 가졌는가. 팀이 없거나 증강을 껐으면 거짓이다. */
	public boolean heldBy(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled) {
			return false;
		}
		for (String id : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null && perk.effects().contains(this)) {
				return true;
			}
		}
		return PerkSetEffects.activeEffectsOf(state).contains(this);
	}
}
