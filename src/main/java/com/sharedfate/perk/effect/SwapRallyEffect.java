package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;

/**
 * 위치 교환을 서로 맞바꾸는 대신, 전원이 무작위로 뽑힌 한 명의 자리로 모이게 한다.
 *
 * <p>정의는 {@code { "type": "swap_rally" }} 하나뿐이고 필드가 없다. 골드 「정거장」이 쓴다.
 * 예전에는 {@code on_swap}(모여 있는 15초 동안의 나약함 I)을 대가로 짝지었지만 2026-10-16 사람
 * 결정으로 걷어냈다.
 *
 * <h2>부수효과는 이 클래스가 모른다</h2>
 * <p>모인 뒤 15초가 지나면 이동했던 사람들만 원래 자리로 돌아간다는 것은 이 클래스와 무관하다.
 * 이동은 {@link com.sharedfate.sync.RallyPointManager}가 처리하고, 교환 시점에 무언가를 걸고
 * 싶으면 평소의 {@code on_swap}({@link OnSwapEffect})을 함께 적으면 된다. 이 클래스는 "이 팀은
 * 순열 교환 대신 집합을 쓴다"는 사실 하나만 표시한다.
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. 상태가 없어 인스턴스를 나눠 써도
 * 안전하다.
 *
 * <p>지금 이 팀에 이 효과가 있는지 판단하는 것은 {@link com.sharedfate.perk.PerkSwapRules}다.
 */
public final class SwapRallyEffect implements PerkEffect {
	/** 상태가 없으므로 하나만 만들어 돌려쓴다. */
	public static final SwapRallyEffect INSTANCE = new SwapRallyEffect();

	private SwapRallyEffect() {
	}

	/** JSON에서 만든다. 읽을 필드가 없어 언제나 성공한다. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}
}
