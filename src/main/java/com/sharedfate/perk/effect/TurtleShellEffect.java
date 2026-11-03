package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;

/** 방패 전투 규칙을 켜는 표시. 실행은 PerkShieldCombat이 맡는다. */
public final class TurtleShellEffect implements PerkEffect {
 public static final TurtleShellEffect INSTANCE = new TurtleShellEffect();
 private TurtleShellEffect() {}
 public static PerkEffect fromJson(String id, int index, JsonObject json) { return INSTANCE; }
}
