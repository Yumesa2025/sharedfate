package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;

/** 최대 내구도 대신 소모량을 바꿔 실효 내구도를 조절한다. */
public record ShieldDurabilityEffect(double multiplier, double ignoreChance) implements PerkEffect {
 public static PerkEffect fromJson(String id, int index, JsonObject json) {
  Double multiplier = json.has("multiplier") ? PerkEffectType.readDouble(json, "multiplier") : 1.0;
  Double chance = json.has("ignore_chance") ? PerkEffectType.readDouble(json, "ignore_chance") : 0.0;
  return multiplier != null && Double.isFinite(multiplier) && multiplier > 0 && multiplier <= 100
    && chance != null && Double.isFinite(chance) && chance >= 0 && chance <= 1
    ? new ShieldDurabilityEffect(multiplier, chance) : null;
 }
 /** 확률 반올림이라 1보다 작은 배율도 평균 소모량이 정확하다. */
 public static int loss(int amount, double multiplier, double ignoreChance, double ignoreRoll, double roundRoll) {
  if (amount <= 0 || ignoreRoll < ignoreChance) return 0;
  double scaled = Math.min(Integer.MAX_VALUE - 1.0, amount / multiplier);
  int whole = (int) scaled;
  return whole + (roundRoll < scaled - whole ? 1 : 0);
 }
}
