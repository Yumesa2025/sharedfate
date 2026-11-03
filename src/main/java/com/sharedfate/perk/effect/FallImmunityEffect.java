package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.team.TeamState;

/** 방패 없이도 낙하 피해 사건을 통째로 버리는 효과. */
public final class FallImmunityEffect implements PerkEffect {
	public static final FallImmunityEffect INSTANCE = new FallImmunityEffect();
	private FallImmunityEffect() {}

	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}

	public static boolean heldBy(TeamState state) {
		if (state == null || !state.perksEnabled) return false;
		for (String id : state.ownedPerks) {
			var perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null && perk.effects().stream().anyMatch(FallImmunityEffect.class::isInstance)) return true;
		}
		return PerkSetEffects.activeEffectsOf(state).stream().anyMatch(FallImmunityEffect.class::isInstance);
	}
}
