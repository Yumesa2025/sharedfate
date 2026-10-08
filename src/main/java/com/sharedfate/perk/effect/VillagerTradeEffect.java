package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 주민 거래를 통째로 손보는 효과. 증강 「흥정의 달인」이 이 타입 하나로 만들어진다.
 *
 * <pre>{@code
 * { "type": "villager_trade", "price_discount": 0.15,
 *   "restock_speed": 2.0, "experience_multiplier": 0.5 }
 * }</pre>
 *
 * <ul>
 *   <li>{@code price_discount} — 첫 번째 재료를 깎아 주는 비율. {@code 0.15} 면 15% 싸진다.</li>
 *   <li>{@code restock_speed} — 재입고를 몇 배 빨리 하는가. {@code 2.0} 이면 기다리는 시간이
 *       절반이다.</li>
 *   <li>{@code experience_multiplier} — 거래로 얻는 경험치에 곱할 값. {@code 0.5} 면 절반이다.</li>
 * </ul>
 *
 * <h2>셋을 한 타입에 묶은 까닭</h2>
 * <p>「값이 싸지는 대신 경험치가 줄어든다」가 이 증강의 전부라, 셋을 따로 쓸 일이 없다. 따로
 * 떼어 두면 이득만 적은 정의를 만들 수 있게 되는데, 이 저장소의 골드 증강은 전부 이득과
 * 손해가 한 덩이다.
 *
 * <h2>바닐라 「영웅의 증표」와 같은 통로를 쓴다</h2>
 * <p>값 깎기는 {@code MerchantOffer.addToSpecialPriceDiff} 로 들어간다. 26.3 의
 * {@code Villager.updateSpecialPrices} 가 평판과 영웅의 증표를 <b>그 한 메서드로</b> 넣고
 * 있으므로, 우리도 같은 통로에 얹으면 셋이 <b>더해진다</b>(곱해지지 않는다). 자세한 것은
 * {@link com.sharedfate.perk.PerkVillagerTrades} 에 적어 두었다.
 *
 * <h2>이 클래스는 아무것도 하지 않는다</h2>
 * <p>「얼마인가」만 든 자료 그릇이다. 사람에게 붙였다 뗄 수 있는 것이 아니라, 거래가 일어나는
 * 순간에 조회하는 값이라 {@link #apply}/{@link #remove} 가 비어 있다.
 */
public final class VillagerTradeEffect implements PerkEffect {
	/** 값을 깎을 수 있는 최대 비율. 1.0 이면 공짜가 되므로 그 아래로 자른다. */
	public static final double MAX_PRICE_DISCOUNT = 0.9;

	/** 재입고 배율의 하한. 1 보다 작으면 느려지는 정의인데, 그것도 쓸 수 있게 열어 둔다. */
	public static final double MIN_RESTOCK_SPEED = 0.1;

	/** 재입고 배율의 상한. 이보다 크면 기다리는 시간이 0 틱으로 눌린다. */
	public static final double MAX_RESTOCK_SPEED = 20.0;

	/** 경험치 배율의 상한. {@code experience_bonus} 와 같은 값으로 맞춰 둔다. */
	public static final double MAX_EXPERIENCE_MULTIPLIER = 10.0;

	private final double priceDiscount;
	private final double restockSpeed;
	private final double experienceMultiplier;

	public VillagerTradeEffect(double priceDiscount, double restockSpeed,
			double experienceMultiplier) {
		this.priceDiscount = priceDiscount;
		this.restockSpeed = restockSpeed;
		this.experienceMultiplier = experienceMultiplier;
	}

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 {@code null}. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		// 셋 다 적지 않으면 「아무 일도 안 하는 증강」이 된다. 기본값을 주지 않고 거른다.
		Double discount = PerkEffectType.readDouble(json, "price_discount");
		if (discount == null || discount < 0.0 || discount > MAX_PRICE_DISCOUNT) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: villager_trade 의 price_discount 가 없거나 0~{} 범위를 벗어났습니다 ({})",
					perkId, MAX_PRICE_DISCOUNT, discount);
			return null;
		}
		Double restock = PerkEffectType.readDouble(json, "restock_speed");
		if (restock == null || restock < MIN_RESTOCK_SPEED || restock > MAX_RESTOCK_SPEED) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: villager_trade 의 restock_speed 가 없거나 {}~{} 범위를 벗어났습니다 ({})",
					perkId, MIN_RESTOCK_SPEED, MAX_RESTOCK_SPEED, restock);
			return null;
		}
		Double experience = PerkEffectType.readDouble(json, "experience_multiplier");
		if (experience == null || experience < 0.0 || experience > MAX_EXPERIENCE_MULTIPLIER) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: villager_trade 의 experience_multiplier 가 없거나 0~{} 범위를 "
							+ "벗어났습니다 ({})",
					perkId, MAX_EXPERIENCE_MULTIPLIER, experience);
			return null;
		}
		return new VillagerTradeEffect(discount, restock, experience);
	}

	/** 깎아 주는 비율. 0.15 면 15%. */
	public double priceDiscount() {
		return Double.isFinite(priceDiscount)
				? Math.max(0.0, Math.min(MAX_PRICE_DISCOUNT, priceDiscount))
				: 0.0;
	}

	/** 재입고 속도 배율. 못 믿을 값이면 1.0 — 바닐라 그대로다. */
	public double restockSpeed() {
		if (!Double.isFinite(restockSpeed) || restockSpeed <= 0.0) {
			return 1.0;
		}
		return Math.max(MIN_RESTOCK_SPEED, Math.min(MAX_RESTOCK_SPEED, restockSpeed));
	}

	/** 거래 경험치에 곱할 값. 못 믿을 값이면 1.0. */
	public double experienceMultiplier() {
		if (!Double.isFinite(experienceMultiplier) || experienceMultiplier < 0.0) {
			return 1.0;
		}
		return Math.min(MAX_EXPERIENCE_MULTIPLIER, experienceMultiplier);
	}
}
