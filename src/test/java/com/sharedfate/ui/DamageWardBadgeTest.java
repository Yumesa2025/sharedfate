package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「호위」 준비 표의 자리. 하트 · 왼손 칸 · 증강 막대 · 발동 알림 줄 어느 것과도 겹치지 않아야 한다.
 *
 * <p>GUI 배율이 바뀌면 화면 폭과 높이만 바뀐다. 그래서 가장 좁은 화면(320 × 240)부터 넓은 화면까지
 * 몇 가지를 골라 본다.
 */
class DamageWardBadgeTest {
	private static final int[][] SCREENS = {{320, 240}, {427, 240}, {480, 270}, {640, 360}, {960, 540}};
	/** 왼손 칸(핫바 왼쪽 바깥)의 윗변 — 아래끝에서 잰 거리. 바닐라 {@code Hud} 의 {@code guiHeight - 23}. */
	private static final int OFFHAND_TOP = 23;

	private static final int TEXT_WIDTH = FakeFont.width("호위");

	@Test
	void 하트_왼쪽_바깥에_선다() {
		for (int[] screen : SCREENS) {
			int width = DamageWardBadge.width(TEXT_WIDTH);
			int left = DamageWardBadge.left(screen[0], width);
			int heartsLeft = screen[0] / 2 - BottomBarMetrics.HOTBAR_HALF_WIDTH;

			assertTrue(left + width <= heartsLeft - 1, "하트와 붙거나 겹친다: " + screen[0]);
			assertTrue(left >= 0, "화면 왼쪽 밖으로 나간다: " + screen[0]);
		}
	}

	@Test
	void 하트_맨_아랫줄과_같은_높이이고_왼손_칸보다_위다() {
		for (int[] screen : SCREENS) {
			int top = DamageWardBadge.top(screen[1]);
			int bottom = top + DamageWardBadge.HEIGHT;
			int heartTop = screen[1] - BottomBarMetrics.BOTTOM_ROW;

			// 하트 아이콘 9픽셀을 상자가 위아래 1픽셀씩 감싼다.
			assertEquals(heartTop - 1, top);
			assertTrue(bottom <= screen[1] - OFFHAND_TOP, "왼손 칸을 덮는다: " + screen[1]);
		}
	}

	/** 막대와 알림 줄은 하트 위, 핫바 폭 안쪽이다. 표는 핫바 바깥이라 가로로 이미 갈린다. */
	@Test
	void 증강_막대와_알림_줄의_가로_범위_밖이다() {
		for (int[] screen : SCREENS) {
			int width = DamageWardBadge.width(TEXT_WIDTH);
			int right = DamageWardBadge.left(screen[0], width) + width;

			assertTrue(right <= BottomCenterLayout.gaugeLeft(screen[0]),
					"막대 폭 안으로 들어온다: " + screen[0]);
		}
	}

	@Test
	void 그림은_정한_칸_안에_든다() {
		assertEquals(DamageWardBadge.ICON_HEIGHT, DamageWardBadge.iconRows());
		for (int row = 0; row < DamageWardBadge.iconRows(); row++) {
			assertTrue(DamageWardBadge.iconRowLeft(row) >= 0);
			assertTrue(DamageWardBadge.iconRowLeft(row) + DamageWardBadge.iconRowWidth(row)
					<= DamageWardBadge.ICON_WIDTH);
		}
		assertTrue(DamageWardBadge.ICON_HEIGHT <= DamageWardBadge.HEIGHT);
	}

	@Test
	void 나타날_때_흐릿함에서_또렷해진다() {
		assertEquals(0.0F, DamageWardBadge.fadeIn(0L));
		assertEquals(0.5F, DamageWardBadge.fadeIn(DamageWardBadge.FADE_IN_MILLIS / 2), 0.01F);
		assertEquals(1.0F, DamageWardBadge.fadeIn(DamageWardBadge.FADE_IN_MILLIS));
		assertEquals(1.0F, DamageWardBadge.fadeIn(-5L));
	}
}
