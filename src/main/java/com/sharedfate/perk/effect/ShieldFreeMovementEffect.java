package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;

/**
 * 모든 방패로 막는 동안 걸음이 줄지 않고 달릴 수 있게 하는 표시. 세트 「방패 4단계」가 쓴다.
 *
 * <p>사람이 정한 것(2026-11-10) — 「막는 동안 이동 속도가 감소하지 않습니다」를 다이아몬드 방패만이
 * 아니라 모든 방패에. 막는 동안의 걸음은 클라이언트가 손에 든 방패의 쓰기 효과({@code USE_EFFECTS})로
 * 정하므로, 실행은 {@code PerkShieldCombat} 이 1초마다 가방의 방패에 그 값을 붙였다 떼는 것으로 한다.
 * 그래서 통신 규약을 바꾸지 않고 옛 클라이언트에서도 그대로 듣는다.
 */
public final class ShieldFreeMovementEffect implements PerkEffect {
	public static final ShieldFreeMovementEffect INSTANCE = new ShieldFreeMovementEffect();

	private ShieldFreeMovementEffect() {
	}

	public static PerkEffect fromJson(String id, int index, JsonObject json) {
		return INSTANCE;
	}
}
