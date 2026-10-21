package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkRarity;
import org.jetbrains.annotations.Nullable;

/**
 * 고른 순간, 지금 가진 증강 전부(자기 자신 포함)를 버리고 지정한 등급의 무작위 증강으로
 * 같은 수만큼 다시 채운다.
 *
 * <pre>{@code
 * { "type": "rarity_reroll", "rarity": "gold" }
 * }</pre>
 *
 * <p>프리즘 「환골탈태」가 쓴다. {@link RarityGrantEffect}(더한다)와 정반대로, 이건
 * <b>있던 것을 지우고 다시 뽑는다.</b>
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. "이 증강을 고르는 순간 보유 목록을
 * 다시 짠다"는 사건 하나만 있으면 된다.
 *
 * <h2>자기 자신은 어떻게 되는가</h2>
 * <p>효과 면에서는 이 증강 자신도 교체 대상이다 — 가진 증강 N개(이 증강 포함)가 전부
 * 사라지고 그만큼 무작위 지정 등급 증강을 받는다. 다만 {@code ownedPerks} 목록에는 이
 * 증강의 id 가 그대로 남아 무엇을 골랐었는지 기록한다. 목록에 남아 있어도 이 클래스가
 * {@code apply}/{@code remove} 를 재정의하지 않으므로 재적용이 아무 일도 하지 않는다 —
 * 「도박꾼」이 같은 방식으로 안전하다.
 *
 * <h2>실제로 뽑고 바꾸는 곳</h2>
 * <p>{@link com.sharedfate.perk.PerkManager#applyChoice}가 부르는
 * {@link com.sharedfate.perk.PerkRarityReroll#rerollOnChoice} 한 곳에서, 증강을 고른 그
 * 순간 딱 한 번 일어난다.
 */
public final class RarityRerollEffect implements PerkEffect {
	private final PerkRarity rarity;

	public RarityRerollEffect(PerkRarity rarity) {
		this.rarity = rarity;
	}

	/** JSON에서 만든다. rarity 가 없거나 알 수 없으면 경고를 남기고 null. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		String rawRarity = PerkEffectType.readString(json, "rarity");
		PerkRarity rarity = PerkRarity.fromId(rawRarity);
		if (rarity == null || !rarity.isGrade()) {
			// 「드래곤전 대비」로 갈아 끼우면 40 이하에 그 풀이 섞인다. 등급만 받는다.
			SharedFateMod.LOGGER.warn("증강 {}: rarity_reroll 의 rarity 를 알 수 없거나 등급이 아닙니다 ({})",
					perkId, rawRarity);
			return null;
		}
		return new RarityRerollEffect(rarity);
	}

	/** 다시 채울 등급. */
	public PerkRarity rarity() {
		return rarity;
	}
}
