package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;

/**
 * 팀원 위치 교환이 일어나도 실제로 자리를 바꾸지 않게 한다.
 *
 * <pre>{@code
 * { "type": "swap_block" }                    // 프리즘 「뿌리내린 발」
 * { "type": "swap_block", "silent": true }    // 프리즘 「소집의 조각」
 * }</pre>
 *
 * <h2>막는 것은 순간이동뿐이다 — {@code silent} 가 아닐 때</h2>
 * <p>주기 타이머는 그대로 돌고, 카운트다운도 그대로 나오고, 같은 시점에 걸리는
 * {@link OnSwapEffect} 도 그대로 발동한다. 막히는 것은 <b>자리를 바꾸는 한 자리</b>뿐이다.
 * 「뿌리내린 발」은 자기 대가가 없지만, 함께 가진 다른 증강의 교환 시점 효과(「본진이
 * 바뀐다」의 저항, 「정거장」의 나약함 등)는 자리만 안 바뀔 뿐 그대로 받는다.
 *
 * <h2>{@code silent} 는 교환 시점 자체를 없앤다</h2>
 * <p>「소집의 조각」은 "자동 교환이 아예 없어지고 내가 부를 때만 일어난다"가 본체다. 그런데
 * 위의 기본 동작대로면 <b>자리는 안 바뀌는데 부수효과만 주기마다 계속 도는</b> 이상한 상태가
 * 된다 — 「시차」를 함께 가진 팀은 힘·재생만 공짜로 받고, 「정거장」을 함께 가진 팀은 나약함만
 * 계속 문다. 그래서 {@code silent} 를 적은 팀은 {@code PositionSwapManager} 가 주기를 세는
 * 것부터 건너뛴다. 예고도, 부수효과도, 남은 시간이 줄어드는 일도 없다.
 *
 * <p>{@link PerkEffect#apply} 로 팀원에게 붙일 것이 없다. 교환 처리 한가운데서 "이 팀이 이
 * 효과를 갖고 있는가"만 물어본다. 상태가 없어 두 인스턴스를 나눠 써도 안전하다.
 *
 * <p>실제로 막는 지점은 {@link com.sharedfate.sync.PositionSwapManager} 이고, 지금 이 팀에 이
 * 효과가 있는지 판단하는 것은 {@link com.sharedfate.perk.PerkSwapRules} 다.
 */
public final class SwapBlockEffect implements PerkEffect {
	/** 예고와 부수효과를 그대로 두는 평범한 막힘. 「뿌리내린 발」이 쓴다. */
	public static final SwapBlockEffect INSTANCE = new SwapBlockEffect(false);
	/** 교환 시점 자체를 없애는 막힘. 「소집의 조각」이 쓴다. */
	public static final SwapBlockEffect SILENT = new SwapBlockEffect(true);

	private final boolean silent;

	private SwapBlockEffect(boolean silent) {
		this.silent = silent;
	}

	/** 교환 시점 자체를 없애는가. */
	public boolean silent() {
		return silent;
	}

	/**
	 * JSON에서 만든다. 읽을 필드가 {@code silent} 하나뿐이라 언제나 성공한다.
	 *
	 * <p>{@code silent} 가 참이 아닌 값(문자열·숫자 등)이면 거짓으로 본다. 여기서 정의를 버리면
	 * 「자리가 안 바뀐다」는 본체까지 사라져 손해가 더 크다.
	 */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return isSilent(json) ? SILENT : INSTANCE;
	}

	private static boolean isSilent(JsonObject json) {
		if (json == null || !json.has("silent")) {
			return false;
		}
		try {
			return json.get("silent").getAsBoolean();
		} catch (RuntimeException error) {
			return false;
		}
	}
}
