package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.perk.effect.FollowUpStrikeEffect;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 무기 세트 3단계 「한 번 더」 — 60% 확률로 절반 피해. */
class FollowUpStrikeEffectTest {
	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test
	void 정의를_읽는다() {
		PerkEffect effect = FollowUpStrikeEffect.fromJson("t", 0,
				json("{\"type\":\"follow_up_strike\",\"chance\":0.6,\"damage_fraction\":0.5}"));
		FollowUpStrikeEffect strike = assertInstanceOf(FollowUpStrikeEffect.class, effect);
		assertEquals(0.6, strike.chance(), 1.0e-9);
		assertEquals(0.5, strike.damageFraction(), 1.0e-9);
		assertEquals(PerkEffectType.FOLLOW_UP_STRIKE, PerkEffectType.fromId("follow_up_strike"));
	}

	@Test
	void 범위를_벗어난_값은_버린다() {
		assertNull(FollowUpStrikeEffect.fromJson("t", 0, json("{\"chance\":0,\"damage_fraction\":0.5}")));
		assertNull(FollowUpStrikeEffect.fromJson("t", 0, json("{\"chance\":1.5,\"damage_fraction\":0.5}")));
		assertNull(FollowUpStrikeEffect.fromJson("t", 0, json("{\"chance\":0.6,\"damage_fraction\":2}")));
		assertNull(FollowUpStrikeEffect.fromJson("t", 0, json("{\"chance\":0.6}")));
	}

	@Test
	void 확률_60퍼센트로_터진다() {
		FollowUpStrikeEffect strike = new FollowUpStrikeEffect(0.6, 0.5);
		assertTrue(strike.triggers(0.0, 1.0F));
		assertTrue(strike.triggers(0.599, 1.0F));
		assertFalse(strike.triggers(0.6, 1.0F));
		assertFalse(strike.triggers(0.99, 1.0F));
	}

	@Test
	void 게이지가_덜_찬_공격에서는_터지지_않는다() {
		FollowUpStrikeEffect strike = new FollowUpStrikeEffect(0.6, 0.5);
		assertTrue(strike.triggers(0.0, 0.9F));
		assertFalse(strike.triggers(0.0, 0.89F));
		assertFalse(strike.triggers(0.0, 0.2F));
	}

	@Test
	void 두_번째_타격은_첫_타격의_절반이다() {
		FollowUpStrikeEffect strike = new FollowUpStrikeEffect(0.6, 0.5);
		assertEquals(5.0F, strike.followUpDamage(10.0F), 1.0e-6);
		assertEquals(0.0F, strike.followUpDamage(0.0F));
		assertEquals(0.0F, strike.followUpDamage(Float.NaN));
		assertEquals(0.0F, strike.followUpDamage(Float.POSITIVE_INFINITY));
	}
}
