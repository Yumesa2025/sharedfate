package com.sharedfate.ui;

/**
 * 「유적 감별사」 좌표 줄을 팀 화면의 <b>어디에</b> 세울지 고르는 계산.
 *
 * <h2>왜 이 계산이 따로 있는가 — 안 그리는 길이 있었다</h2>
 *
 * <p>처음에는 증강 목록 오른쪽(바탕판 밖의 빈자리)에만 세우고, <b>자리가 모자라면 아예 안
 * 그렸다</b>. 「잘린 좌표는 틀린 좌표다」는 근거는 지금도 옳지만, 그 규칙이 실제로 한 일은
 * <b>기본 설정에서 좌표를 영영 안 보이게</b>만든 것이었다.
 *
 * <p>숫자로 재 보면 이렇다. 판은 가로 300 이고 화면 가운데에 서므로, 오른쪽에 남는 자리는
 * {@code 화면가로 / 2 − 150 − 12} 다. 1920×1080 의 <b>기본 GUI 배율은 4</b> 라 화면이
 * 480×270 이고({@code PerkOfferScreen} 의 머리말에도 같은 값이 적혀 있다), 남는 자리는
 * <b>78px</b> 뿐이다. 「고대 도시  -1234, 567」한 줄이 100px 을 넘으니 한 줄도 못 들어간다.
 * 배율 3(640×360)에서 158px, 배율 2(960×540)에서 318px 이라 거기서는 들어간다 — 즉
 * <b>대다수가 쓰는 기본 설정에서만 안 보였다.</b>
 *
 * <p>사람이 이 증강에 요구한 것은 「채팅이 올라가면 다시 못 보니 화면에 남아 있어야 한다」다.
 * 안 그리면 그 요구가 통째로 사라진다. 그래서 자리가 모자랄 때 <b>안 그리는 대신 판 안으로
 * 들인다</b> — 판 안은 가로 300 이라 어떤 배율에서도 좌표 한 줄이 들어간다.
 *
 * <h2>왜 판 안에서도 잘리지 않는가</h2>
 *
 * <p>판 안에 들일 때는 그리는 쪽이 {@code font.split} 으로 접는다. 접기는 글자를 버리지 않고
 * 다음 줄로 넘기므로 「잘린 좌표」가 생기지 않는다. 오른쪽에 세울 때만 접을 수 없어
 * ({@link #NONE} 밖에 남지 않는 빈자리다) 여기서 폭을 미리 재는 것이다.
 *
 * <h2>왜 넓을 때는 그래도 오른쪽인가</h2>
 *
 * <p>판 안에 들이면 그만큼 증강 목록이 짧아진다. 오른쪽 빈자리는 아무도 안 쓰던 자리라
 * 공짜다 — 들어가는 화면에서 굳이 목록을 깎을 이유가 없다. 사람이 「옆에」라고 말한 모양도
 * 그쪽이다.
 */
public final class RuinCoordPlacement {

	private RuinCoordPlacement() {
	}

	/** 좌표 줄을 세울 자리. */
	public enum Spot {
		/** 그릴 것이 없다. 「유적 감별사」가 없는 팀이다. */
		NONE,
		/** 바탕판 오른쪽 빈자리. 가장 긴 줄이 그대로 들어갈 때만 고른다. */
		RIGHT,
		/** 바탕판 안, 머리글 아래. 자리가 모자랄 때의 길이며 <b>언제나 들어간다</b>. */
		INSIDE
	}

	/**
	 * 오른쪽에 세울 때 글자가 시작하는 x.
	 *
	 * @param panelLeft  바탕판 글자 영역의 왼쪽 변
	 * @param panelWidth 바탕판 글자 영역의 가로
	 * @param gap        바탕판 오른쪽 변과 글자 사이에 둘 틈
	 */
	public static int rightX(int panelLeft, int panelWidth, int gap) {
		return panelLeft + panelWidth + gap;
	}

	/**
	 * 어디에 세울지 고른다.
	 *
	 * <p>고르는 기준은 <b>가장 긴 줄 하나</b>다. 줄마다 따로 판정하면 한 줄은 오른쪽, 한 줄은
	 * 판 안에 흩어져 같은 덩어리가 두 곳으로 갈린다.
	 *
	 * @param lineCount   그릴 줄 수. 0 이면 {@link Spot#NONE}
	 * @param screenWidth 화면 가로(GUI 배율이 이미 반영된 값)
	 * @param widestLine  가장 긴 줄의 글자 폭
	 */
	public static Spot choose(int lineCount, int screenWidth, int panelLeft, int panelWidth,
			int gap, int widestLine) {
		if (lineCount <= 0) {
			return Spot.NONE;
		}
		// 폭을 못 잰 줄(빈 문자열)만 들어와도 판 안으로 보낸다. 오른쪽에 「들어간다」고 보고
		// 빈자리에 아무것도 안 그리면 다시 예전 증상이 된다.
		if (widestLine <= 0) {
			return Spot.INSIDE;
		}
		return rightX(panelLeft, panelWidth, gap) + widestLine <= screenWidth
				? Spot.RIGHT
				: Spot.INSIDE;
	}

	/**
	 * 오른쪽에 세울 수 있는 글자 폭의 상한. 시험과 진단에 쓴다.
	 *
	 * <p>{@link #choose} 와 같은 식이어야 한다 — 이 값보다 긴 줄이 곧
	 * {@link Spot#INSIDE} 로 가는 줄이다.
	 */
	public static int rightRoom(int screenWidth, int panelLeft, int panelWidth, int gap) {
		return Math.max(0, screenWidth - rightX(panelLeft, panelWidth, gap));
	}
}
