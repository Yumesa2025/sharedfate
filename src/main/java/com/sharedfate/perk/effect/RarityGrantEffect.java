package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkRarity;
import org.jetbrains.annotations.Nullable;

/**
 * 고른 즉시 지정한 등급의 무작위 증강을 더 얻게 한다.
 *
 * <pre>{@code
 * { "type": "rarity_grant", "rarity": "gold", "count": 1 }
 * }</pre>
 *
 * <p>「도박꾼」의 {@code gambler}(등급 무관, 2개 고정)를 등급 지정으로 일반화한 것이다.
 * 실버 「숨은 재능」은 {@code rarity: gold}, 골드 「하늘의 은총」은 {@code rarity: prism}을 쓴다.
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. "이 증강을 고르는 순간 지정 등급에서
 * {@code count}개를 더 준다"는 사건 하나만 있으면 된다.
 *
 * <h2>실제로 뽑고 주는 곳</h2>
 * <p>{@link com.sharedfate.perk.PerkManager#applyChoice}가 부르는
 * {@link com.sharedfate.perk.PerkRarityGrant#grantOnChoice} 한 곳에서, 증강을 고른 그 순간
 * 딱 한 번 일어난다. {@code gambler}와 같은 자리, 같은 시점이다.
 *
 * <h2>대가는 이 타입이 지지 않는다</h2>
 * <p>대가는 <b>같은 증강에 같이 적은 다른 효과</b>(속성 감소 등)가 진다.
 */
public final class RarityGrantEffect implements PerkEffect {
	/** 한 번에 더 얻는 증강 수 상한. 도박꾼보다 크게 벌리지 않는다. */
	public static final int MAX_COUNT = 3;

	private final PerkRarity rarity;
	private final int count;

	public RarityGrantEffect(PerkRarity rarity, int count) {
		this.rarity = rarity;
		this.count = count;
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		String rawRarity = PerkEffectType.readString(json, "rarity");
		PerkRarity rarity = PerkRarity.fromId(rawRarity);
		if (rarity == null || !rarity.isGrade()) {
			// 「드래곤전 대비」는 등급이 아니라 덤으로 줄 수 없다 — 45·50 에서 고르는 것만이 그 길이다.
			SharedFateMod.LOGGER.warn("증강 {}: rarity_grant 의 rarity 를 알 수 없거나 등급이 아닙니다 ({})",
					perkId, rawRarity);
			return null;
		}

		int count = PerkEffectType.readInt(json, "count", 1);
		if (count < 1 || count > MAX_COUNT) {
			SharedFateMod.LOGGER.warn("증강 {}: rarity_grant 의 count 가 1~{} 범위를 벗어났습니다 ({})",
					perkId, MAX_COUNT, count);
			return null;
		}
		return new RarityGrantEffect(rarity, count);
	}

	/** 지정 등급. */
	public PerkRarity rarity() {
		return rarity;
	}

	/** 더 줄 개수. */
	public int count() {
		return count;
	}
}
