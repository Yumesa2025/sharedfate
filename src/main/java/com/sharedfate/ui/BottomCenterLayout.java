package com.sharedfate.ui;

/**
 * 화면 아래 가운데에 우리가 올리는 두 가지 — 「다음 증강까지」 막대와 증강 발동 알림 줄 — 의 자리.
 *
 * <p>아래에서 위로 <b>바닐라 표시</b>(하트·방어구·배고픔·공기 방울, {@link BottomBarMetrics}) →
 * <b>증강 막대</b> → <b>팀 레벨 글줄</b>(왼쪽, {@code BottomLeftStack}) · <b>바닐라 액션바</b> →
 * <b>발동 알림 줄</b> 이다. 셈은 모두 여기에 두어 월드 없이 모든 GUI 배율에서 겹침을 시험한다.
 *
 * <h2>바닐라 액션바와 손에 든 물건 이름</h2>
 * <p>26.3 의 {@code Hud.extractOverlayMessage} 는 {@code guiHeight - 68} 에서 4 위({@code -72})에
 * 글자를 적고 위아래 2픽셀 뒷판을 깐다 — 아래끝에서 {@value #ACTION_BAR_TOP} ~
 * {@value #ACTION_BAR_BOTTOM}. 손에 든 물건 이름({@code extractSelectedItemName})은 {@code -59}
 * 라 그 아래다. 알림 줄은 둘 모두의 <b>위</b>에 선다.
 *
 * <h2>알림 줄이 팀 레벨 글줄보다도 위인 까닭</h2>
 * <p>「드래곤전 대비 증강까지 12」는 핫바 왼쪽 끝에서 시작해 화면 가운데를 넘는다. 가운데 정렬한
 * 알림 줄이 같은 높이에 서면 글자끼리 겹친다. 그래서 바닥선을 액션바와 그 글줄 중 <b>높은 쪽</b>
 * 위로 잡는다.
 */
public final class BottomCenterLayout {
	/** 막대 폭. 바닐라 경험치 바({@code ContextualBar.WIDTH})와 같다. */
	public static final int GAUGE_WIDTH = 182;
	/** 막대 안쪽 높이. 바닐라 경험치 바({@code ContextualBar.HEIGHT})와 같다. */
	public static final int GAUGE_HEIGHT = 5;
	/** 막대 아랫변과 바닐라 표시 윗변 사이. 테두리·빛이 1픽셀씩 나가므로 보이는 틈은 1이다. */
	public static final int GAUGE_GAP_TO_BARS = 3;
	/** 막대 위로 남겨 둘 여백. 좌하단 글줄이 이 위에서 시작한다. */
	public static final int GAUGE_MARGIN_TOP = 3;
	/** 테두리 밖으로 번지는 빛의 두께. 테두리(1) 바깥 1픽셀이다. */
	public static final int GAUGE_GLOW = 1;

	/** 바닐라 액션바 뒷판의 윗변 — 아래끝에서 잰 거리. */
	public static final int ACTION_BAR_TOP = 74;
	/** 바닐라 액션바 뒷판의 아랫변 — 아래끝에서 잰 거리. */
	public static final int ACTION_BAR_BOTTOM = 61;
	/** 좌하단 글줄 높이({@code BottomLeftStack.LINE_HEIGHT}). */
	public static final int LEFT_LINE_HEIGHT = 10;

	/** 알림 한 줄(테두리 포함)의 높이. 글자 8 + 위 2 + 아래 1. */
	public static final int PROC_LINE_HEIGHT = 11;
	/** 쌓인 알림 줄 사이. */
	public static final int PROC_LINE_GAP = 1;
	/** 알림 줄 바닥과 그 아래 것(액션바·글줄) 사이. */
	public static final int PROC_GAP = 2;
	/** 알림 줄 글자 좌우 여백. */
	public static final int PROC_PADDING_X = 4;
	/** 막 나타난 줄이 제자리보다 이만큼 아래에서 떠오른다. {@link #PROC_GAP} 을 넘지 않아 떠오르는 동안에도 아래 것을 덮지 않는다. */
	public static final int PROC_RISE = 2;

	private BottomCenterLayout() {
	}

	/**
	 * 막대 안쪽의 윗변 y.
	 *
	 * @param barsHeight 핫바 위 바닐라 표시 높이({@code BottomLeftStack.vanillaBarsHeight})
	 */
	public static int gaugeTop(int guiHeight, int barsHeight) {
		return guiHeight - barsHeight - GAUGE_GAP_TO_BARS - GAUGE_HEIGHT;
	}

	/** 막대 왼쪽 끝 x. */
	public static int gaugeLeft(int guiWidth) {
		return (guiWidth - GAUGE_WIDTH) / 2;
	}

	/**
	 * 막대가 차지하는 자리의 윗변. 좌하단 글줄은 이 위에서 시작해야 겹치지 않는다.
	 *
	 * <p>막대를 안 그리면 바닐라 표시 윗변을 그대로 돌려주어 자리를 차지하지 않는다.
	 */
	public static int clearanceTop(int guiHeight, int barsHeight, boolean gaugeVisible) {
		return gaugeVisible ? gaugeTop(guiHeight, barsHeight) - GAUGE_MARGIN_TOP : guiHeight - barsHeight;
	}

	/** 좌하단 팀 레벨 글줄의 맨 윗변. 글줄이 없으면 {@link #clearanceTop} 이다. */
	public static int leftStackTop(int guiHeight, int barsHeight, boolean gaugeVisible, int teamLines) {
		return clearanceTop(guiHeight, barsHeight, gaugeVisible) - Math.max(0, teamLines) * LEFT_LINE_HEIGHT;
	}

	/**
	 * 알림 줄 바닥(맨 아래 줄의 아랫변 바로 아래) y.
	 *
	 * @param teamLines 좌하단 팀 레벨 글줄 수({@code BottomLeftStack.teamLevelLines})
	 */
	public static int procBottom(int guiHeight, int barsHeight, boolean gaugeVisible, int teamLines) {
		int actionBar = guiHeight - ACTION_BAR_TOP;
		return Math.min(actionBar, leftStackTop(guiHeight, barsHeight, gaugeVisible, teamLines)) - PROC_GAP;
	}

	/**
	 * 알림 줄 하나의 윗변 y. 0 번이 바닥 줄이다.
	 *
	 * @param rise 0~1. 1 이면 제자리, 0 이면 {@link #PROC_RISE} 만큼 아래
	 */
	public static int procLineTop(int procBottom, int index, float rise) {
		int settled = procBottom - (index + 1) * PROC_LINE_HEIGHT - index * PROC_LINE_GAP;
		return settled + Math.round((1.0F - Math.clamp(rise, 0.0F, 1.0F)) * PROC_RISE);
	}

	/** 알림 줄 상자의 폭. */
	public static int procLineWidth(int textWidth) {
		return Math.max(0, textWidth) + PROC_PADDING_X * 2;
	}
}
