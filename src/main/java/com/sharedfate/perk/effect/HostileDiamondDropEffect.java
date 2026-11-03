package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;

/** 적대적인 몹을 직접 처치했을 때 다이아몬드 한 개를 떨어뜨릴 확률. */
public record HostileDiamondDropEffect(double chance) implements PerkEffect {
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double chance = PerkEffectType.readDouble(json, "chance");
		return chance != null && Double.isFinite(chance) && chance >= 0 && chance <= 1
				? new HostileDiamondDropEffect(chance) : null;
	}

	public boolean drops(boolean hostile, double roll) {
		return hostile && roll >= 0 && roll < chance;
	}
}
