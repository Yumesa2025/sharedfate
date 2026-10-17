package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;

/**
 * 잠깐 <b>무적</b>이 된다. 「잠깐 거는」 자리({@code on_swap} 등)의 하위 효과로만 뜻이 있다.
 *
 * <pre>{@code
 * { "type": "on_swap",
 *   "effects": [ { "type": "invulnerable", "duration": 3 } ] }
 * }</pre>
 *
 * <p>필드가 없다. 얼마 동안인지는 감싸는 쪽이 적은 {@code duration} 이 정한다. 실버 「본진이
 * 바뀐다」(3초)와 교환 세트 3단계(10초)가 쓴다.
 *
 * <h2>저항 V 가 아닌 까닭 — 시간이 합쳐져야 한다</h2>
 * <p>예전에는 둘 다 저항으로 만들었다. 상태이상은 같은 종류가 겹치면 <b>긴 쪽 하나만</b> 남으므로
 * 세트 3단계(10초)를 켠 팀에서 「본진이 바뀐다」의 3초는 흔적도 없이 묻혔다. 무적은
 * {@link com.sharedfate.perk.PerkInvulnerability} 가 사람마다 「언제까지」 하나로 들고 있고,
 * 이미 무적인 사람에게 또 걸면 <b>끝나는 시각 뒤에 덧붙인다.</b> 그래서 둘을 함께 가지면
 * 10 + 3 = 13초다.
 *
 * <h2>무엇을 막는가</h2>
 * <p>모든 피해를 버린다. 다만 바닐라 무적과 같이 {@code #minecraft:bypasses_invulnerability}
 * (공허·{@code /kill})는 통과시킨다 — 운영자가 치울 길과 공허에 떨어진 사람이 끝날 길은 남겨야
 * 한다. 실제로 버리는 자리는 {@code LivingEntityPerkDamageMixin} 이다.
 *
 * <p>{@link PerkEffect#apply}로 상시 붙일 것이 없다. 증강의 맨 위에 그냥 적으면 아무 일도 하지
 * 않는다.
 */
public final class InvulnerableEffect implements PerkEffect {
	/** 상태가 없으므로 하나만 만들어 돌려쓴다. */
	public static final InvulnerableEffect INSTANCE = new InvulnerableEffect();

	private InvulnerableEffect() {
	}

	/** JSON에서 만든다. 읽을 필드가 없어 언제나 성공한다. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}
}
