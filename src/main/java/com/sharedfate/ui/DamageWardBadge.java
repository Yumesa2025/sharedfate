package com.sharedfate.ui;

/**
 * 「호위」 준비 표의 자리와 모양 — 하트 맨 아랫줄 바로 왼쪽, 핫바 왼쪽 끝 바깥.
 *
 * <h2>왜 그 자리인가</h2>
 * <p>호위는 <b>체력</b>을 지키는 것이라 하트 곁이 뜻이 맞다. 그리고 그 자리는 비어 있다.
 *
 * <ul>
 *   <li>하트 맨 아랫줄은 핫바 왼쪽 끝({@code 가운데 − 91})에서 오른쪽으로 그려진다. 표는 그 <b>왼쪽 바깥</b>이라
 *       하트가 몇 줄이든(맨 아랫줄은 늘 {@code 아래끝 − 39}) 겹치지 않는다.</li>
 *   <li>왼손 칸(방패를 든 곳)은 핫바 높이({@code 아래끝 − 23} 아래)라 표보다 낮다.</li>
 *   <li>「다음 증강까지」 막대·팀 레벨 글줄·발동 알림 줄({@link BottomCenterLayout})은 모두 하트 <b>위</b>
 *       이고 핫바 폭 안쪽이다. 표는 하트와 같은 높이, 핫바 바깥이라 어느 것과도 만나지 않는다.</li>
 * </ul>
 *
 * <p>셈은 모두 여기에 두어 월드 없이 모든 GUI 배율에서 겹침을 시험한다.
 */
public final class DamageWardBadge {
	/** 상자 높이. 발동 알림 줄({@link BottomCenterLayout#PROC_LINE_HEIGHT})과 같다. */
	public static final int HEIGHT = BottomCenterLayout.PROC_LINE_HEIGHT;
	/** 상자 안쪽 좌우 여백. */
	public static final int PADDING_X = 3;
	/** 방패 그림의 폭. */
	public static final int ICON_WIDTH = 7;
	/** 방패 그림의 높이. */
	public static final int ICON_HEIGHT = 8;
	/** 그림과 글자 사이. */
	public static final int ICON_GAP = 3;
	/** 상자 오른쪽 끝과 하트 사이. */
	public static final int GAP_TO_HEARTS = 3;
	/** 화면 왼쪽 끝에서 최소한 이만큼은 띄운다. */
	public static final int SCREEN_MARGIN = 2;
	/** 나타날 때 흐릿함에서 또렷해지기까지(밀리초). */
	public static final long FADE_IN_MILLIS = 250L;

	/**
	 * 방패 그림. 줄마다 {@code {왼쪽 끝, 폭}} 이고 위에서 아래로다 — 위가 넓고 아래로 갈수록 뾰족하다.
	 */
	private static final int[][] SHIELD_ROWS = {
			{0, 7}, {0, 7}, {0, 7}, {0, 7}, {1, 5}, {1, 5}, {2, 3}, {3, 1}
	};

	private DamageWardBadge() {
	}

	/** 상자 폭. */
	public static int width(int textWidth) {
		return PADDING_X + ICON_WIDTH + ICON_GAP + Math.max(0, textWidth) + PADDING_X;
	}

	/**
	 * 상자 왼쪽 끝 x. 하트 왼쪽에 붙이되 화면 밖으로는 안 나간다.
	 *
	 * @param guiWidth 화면 GUI 폭
	 * @param width    {@link #width} 가 준 상자 폭
	 */
	public static int left(int guiWidth, int width) {
		int heartsLeft = guiWidth / 2 - BottomBarMetrics.HOTBAR_HALF_WIDTH;
		return Math.max(SCREEN_MARGIN, heartsLeft - GAP_TO_HEARTS - width);
	}

	/**
	 * 상자 윗변 y. 하트 맨 아랫줄(아이콘 9픽셀)과 가운데를 맞춘다.
	 *
	 * @param guiHeight 화면 GUI 높이
	 */
	public static int top(int guiHeight) {
		return guiHeight - BottomBarMetrics.BOTTOM_ROW - 1;
	}

	/** 방패 그림의 줄 수. */
	public static int iconRows() {
		return SHIELD_ROWS.length;
	}

	/** 방패 그림 {@code row} 번째 줄의 왼쪽 끝(그림 안에서). */
	public static int iconRowLeft(int row) {
		return SHIELD_ROWS[row][0];
	}

	/** 방패 그림 {@code row} 번째 줄의 폭. */
	public static int iconRowWidth(int row) {
		return SHIELD_ROWS[row][1];
	}

	/**
	 * 나타난 뒤 지난 시간에 따른 불투명도 0~1.
	 *
	 * @param shownFor 나타난 뒤 지난 밀리초. 음수(시계가 되감김)면 다 나타난 것으로 본다
	 */
	public static float fadeIn(long shownFor) {
		if (shownFor < 0L || shownFor >= FADE_IN_MILLIS) {
			return 1.0F;
		}
		return shownFor / (float) FADE_IN_MILLIS;
	}
}
