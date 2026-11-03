package com.sharedfate.enchant;

import java.util.function.IntUnaryOperator;

/** 수선이 사용한 양과 무관하게 구슬 경험치를 보존한다. 엔드 차단은 이후 플레이어 입구에서 한다. */
public final class MendingExperience {
	private MendingExperience() { }
	public static int repairAndKeep(int amount, IntUnaryOperator repair) {
		repair.applyAsInt(amount);
		return amount;
	}
}
