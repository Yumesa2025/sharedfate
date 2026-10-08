package com.sharedfate.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「유적 감별사」 좌표가 <b>어떤 GUI 배율에서도 보이는지</b>를 숫자로 붙드는 시험.
 *
 * <h2>왜 이 시험이 필요했는가</h2>
 *
 * <p>사람이 「증강 화면에 좌표가 안 보인다」고 알렸고, 원인은 <b>오른쪽에 자리가 모자라면 아예
 * 안 그리는 규칙</b>이었다. 1920×1080 의 기본 GUI 배율은 4 라 화면이 480×270 이고, 그때 판
 * 오른쪽에 남는 자리는 78px 뿐인데 좌표 한 줄이 104px 이다. <b>기본 설정에서만 안 보였다.</b>
 *
 * <p>{@code runClient} 가 이 환경에서 뜨지 않아(글꼴 로딩 중 {@code 0xC0000005}) 눈으로 볼 수
 * 없다. 그래서 자리 판정을 {@link RuinCoordPlacement} 로 빼내고 폭은 {@link FakeFont} 로 재서
 * 배율별 숫자를 여기에 못 박는다.
 */
class RuinCoordPlacementTest {

	/** {@code TeamScreen.PANEL_WIDTH}. 두 곳이 갈리면 이 시험이 뜻을 잃는다. */
	private static final int PANEL_WIDTH = 300;

	/** {@code TeamScreen.RUIN_COORD_GAP}. */
	private static final int GAP = 12;

	/** 정의에 실제로 적힌 두 구조물로 서버가 만드는 줄들({@code PerkRuinSurvey.Found#line}). */
	private static final String CITY = "고대 도시  -1234, 567";

	private static final String MANSION = "숲의 저택  890, -123";

	/** 판 안에서 글줄을 접는 폭. {@code TeamScreen} 이 {@code PANEL_WIDTH - 8} 을 쓴다. */
	private static final int INSIDE_WRAP = PANEL_WIDTH - 8;

	private static int panelLeft(int screenWidth) {
		return (screenWidth - PANEL_WIDTH) / 2;
	}

	private static int widest() {
		return Math.max(FakeFont.width(CITY), FakeFont.width(MANSION));
	}

	private static RuinCoordPlacement.Spot spotAt(int screenWidth) {
		return RuinCoordPlacement.choose(2, screenWidth, panelLeft(screenWidth), PANEL_WIDTH, GAP,
				widest());
	}

	@Test
	@DisplayName("1920×1080 배율 2 — 오른쪽에 두 줄이 들어간다")
	void scaleTwoFitsRight() {
		int screenWidth = 1920 / 2;
		assertEquals(318, RuinCoordPlacement.rightRoom(screenWidth, panelLeft(screenWidth),
				PANEL_WIDTH, GAP), "배율 2(960×540)에서 판 오른쪽에 남는 자리");
		assertEquals(104, widest(), "가장 긴 좌표 줄의 폭");
		assertEquals(RuinCoordPlacement.Spot.RIGHT, spotAt(screenWidth));
	}

	@Test
	@DisplayName("1920×1080 배율 3 — 오른쪽에 두 줄이 들어간다")
	void scaleThreeFitsRight() {
		int screenWidth = 1920 / 3;
		assertEquals(158, RuinCoordPlacement.rightRoom(screenWidth, panelLeft(screenWidth),
				PANEL_WIDTH, GAP), "배율 3(640×360)에서 판 오른쪽에 남는 자리");
		assertEquals(RuinCoordPlacement.Spot.RIGHT, spotAt(screenWidth));
	}

	@Test
	@DisplayName("1920×1080 배율 4 — 오른쪽에 안 들어간다. 이것이 사람이 겪은 화면이다")
	void scaleFourFallsInside() {
		int screenWidth = 1920 / 4;
		assertEquals(78, RuinCoordPlacement.rightRoom(screenWidth, panelLeft(screenWidth),
				PANEL_WIDTH, GAP), "배율 4(480×270)에서 판 오른쪽에 남는 자리");
		assertTrue(widest() > 78, "좌표 줄이 그 자리보다 길다 — 그래서 예전에는 안 그렸다");
		assertEquals(RuinCoordPlacement.Spot.INSIDE, spotAt(screenWidth),
				"안 그리는 대신 판 안으로 들여야 한다");
	}

	@Test
	@DisplayName("바닐라가 보장하는 가장 좁은 화면(320×240)에서도 자리가 있다")
	void narrowestVanillaScreenFallsInside() {
		assertEquals(RuinCoordPlacement.Spot.INSIDE, spotAt(320));
	}

	@Test
	@DisplayName("어떤 배율에서든 좌표는 판 안에 들어간다 — 이것이 「안 보인다」가 안 남는 근거다")
	void insideAlwaysFits() {
		// 판은 화면 가운데 고정 폭(300)이고 바닐라가 보장하는 최소 가로가 320 이라, 배율이 아무리
		// 커도 판 안의 접기 폭은 292 로 같다. 좌표 줄은 그보다 짧다.
		assertTrue(FakeFont.width(CITY) <= INSIDE_WRAP, "고대 도시 줄");
		assertTrue(FakeFont.width(MANSION) <= INSIDE_WRAP, "숲의 저택 줄");

		// 최악의 정의: 이름표 상한({@code RuinSurveyEffect.MAX_LABEL_LENGTH} = 16)을 한글로
		// 꽉 채우고 좌표도 월드 경계(±29,999,984)까지 간 줄. 그래도 접히지 않는다.
		String worst = "가".repeat(16) + "  -29999984, -29999984";
		assertTrue(FakeFont.width(worst) <= INSIDE_WRAP,
				"최악의 줄도 판 안에서 접히지 않는다: " + FakeFont.width(worst));
	}

	@Test
	@DisplayName("그릴 줄이 없으면 아무 자리도 안 고른다")
	void noLinesPicksNothing() {
		assertEquals(RuinCoordPlacement.Spot.NONE,
				RuinCoordPlacement.choose(0, 960, panelLeft(960), PANEL_WIDTH, GAP, 0));
	}

	@Test
	@DisplayName("폭을 못 잰 줄은 오른쪽이 아니라 판 안으로 보낸다")
	void unmeasurableLinesFallInside() {
		assertEquals(RuinCoordPlacement.Spot.INSIDE,
				RuinCoordPlacement.choose(2, 960, panelLeft(960), PANEL_WIDTH, GAP, 0));
	}

	@Test
	@DisplayName("판 안에 들일 때 목록이 내려가는 만큼은 세트 덩어리와 같은 셈이다")
	void insideBlockHeightMatchesSetBlock() {
		// 새 계산을 만들지 않고 PerkSetLines.blockHeight 를 그대로 쓴다 — 머리글 아래에 줄
		// 몇 개와 틈 하나를 두는 모양이 세트 덩어리와 똑같다.
		assertEquals(2 * 12 + 4, PerkSetLines.blockHeight(2, 12, 4));
		assertEquals(0, PerkSetLines.blockHeight(0, 12, 4), "좌표가 없으면 목록이 내려가지 않는다");
	}
}
