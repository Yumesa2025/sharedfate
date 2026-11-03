package com.sharedfate.ui;

/**
 * {@link InventoryStatPanel} 이 그릴 수 있는 한 줄.
 *
 * <p>패널은 줄이 「무엇을 적는지」 모른다. 자리에 따라 네 모양 중 하나를 골라 달라고 할 뿐이다.
 * 그래서 「기준 → 지금」 줄({@link StatRow})과 「+35%」 보정치 줄({@link BonusRow})이 같은 자리
 * 계산을 나눠 쓴다.
 */
public interface PanelRow {
	/** 온전한 이름으로 적은 한 줄. 자리가 가장 넉넉할 때. */
	String fullLine();

	/** 이름만 줄인 한 줄. */
	String shortLine();

	/** 가장 촘촘한 한 줄. */
	String tightLine();

	/** 두 줄로 접을 때의 윗줄. */
	String shortLabel();

	/** 두 줄로 접을 때의 아랫줄. */
	String values();

	/** 글자색. */
	int color();

	/**
	 * 두 줄로 접을 수 있는가. 값이 없는 안내 줄처럼 접어도 아랫줄이 비는 것은 거짓을 돌려준다 —
	 * 그런 줄은 {@link #tightLine()} 한 줄로 그린다.
	 */
	default boolean wraps() {
		return true;
	}
}
