package com.sharedfate.ui;

import com.sharedfate.perk.PerkMilestones;

/**
 * 「드래곤전 대비」(0.44.0-dev)를 화면에 그릴 때 쓰는 색과 말. 증강 선택창 · 보유 목록 · 팀 화면 · HUD
 * 넷이 같은 값을 쓰도록 한 곳에 둔다 — 넷이 각자 적으면 언젠가 한 곳만 다른 보라가 된다.
 *
 * <p>클라이언트 코드가 아니라 공용 쪽에 둔 것은 월드 없이 시험할 수 있게 하려는 것이다
 * ({@link PerkGauge} 와 같은 까닭).
 */
public final class DragonPrepStyle {
	/** 엔더 연보라. 카드 테두리 · 띠 · HUD 막대와 글자가 함께 쓴다. */
	public static final int COLOR = 0xFFC79BFF;
	/** 표식 글. {@code PerkRarity.TRIAL.displayName()} 과 같다. */
	public static final String LABEL = "드래곤전 대비";

	private DragonPrepStyle() {
	}

	/**
	 * 다음 증강 구간이 「드래곤전 대비」(45·50)인가.
	 *
	 * <p>서버가 보내는 {@code TeamSyncPayload.nextPerkLevel} 만으로 정한다 — 41~50 구간에 들어선 시련 팀만
	 * 그 값이 40 을 넘는다. 통신 칸을 따로 늘리지 않은 까닭이다.
	 *
	 * @param nextPerkLevel 다음 증강이 나오는 레벨. 더 받을 것이 없으면 0
	 */
	public static boolean nextIsPrep(int nextPerkLevel) {
		return nextPerkLevel > PerkMilestones.MAX;
	}

	/** 「다음 증강까지 N」 또는 「드래곤전 대비 증강까지 N」. */
	public static String remainingText(int remaining, boolean prep) {
		return (prep ? LABEL + " 증강까지 " : "다음 증강까지 ") + remaining;
	}
}
