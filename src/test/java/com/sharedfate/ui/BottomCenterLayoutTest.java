package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 화면 아래 가운데 자리 — 「다음 증강까지」 막대와 발동 알림 줄이 바닐라 표시·액션바·서로와 겹치지
 * 않는가를 <b>모든 GUI 배율</b>에서 본다.
 *
 * <p>y 는 모두 화면 위끝에서 잰 값이고, 띠는 [윗변, 아랫변) 이다.
 */
class BottomCenterLayoutTest {
	/** 창 크기 × GUI 배율. 바닐라는 창을 배율로 나눈 정수를 GUI 크기로 쓴다. */
	private static final int[][] WINDOWS = {
			{3840, 2160}, {2560, 1440}, {1920, 1080}, {1600, 900}, {1366, 768}, {1280, 720}, {854, 480},
			{640, 480}, {320, 240}};
	private static final int[] SCALES = {1, 2, 3, 4, 5, 6};

	/** 하트 줄(최대 체력 20 · 40 · 60 · 80) × 흡수 × 방어구 × 탈것 × 공기 방울. */
	private static int[] barHeights() {
		java.util.Set<Integer> heights = new java.util.TreeSet<>();
		for (float health : new float[] {20.0F, 40.0F, 60.0F, 80.0F}) {
			for (float absorption : new float[] {0.0F, 8.0F}) {
				for (boolean armor : new boolean[] {false, true}) {
					for (int vehicle : new int[] {0, 10, 30}) {
						for (boolean air : new boolean[] {false, true}) {
							heights.add(BottomBarMetrics.height(health, absorption, armor, vehicle, air));
						}
					}
				}
			}
		}
		return heights.stream().mapToInt(Integer::intValue).toArray();
	}

	private static boolean overlaps(int topA, int bottomA, int topB, int bottomB) {
		return topA < bottomB && topB < bottomA;
	}

	/** 바닐라 액션바 뒷판의 띠. */
	private static int actionBarTop(int guiHeight) {
		return guiHeight - BottomCenterLayout.ACTION_BAR_TOP;
	}

	private static int actionBarBottom(int guiHeight) {
		return guiHeight - BottomCenterLayout.ACTION_BAR_BOTTOM;
	}

	/** 막대가 빛까지 차지하는 띠. */
	private static int gaugeOuterTop(int guiHeight, int bars) {
		return BottomCenterLayout.gaugeTop(guiHeight, bars) - 1 - BottomCenterLayout.GAUGE_GLOW;
	}

	private static int gaugeOuterBottom(int guiHeight, int bars) {
		return BottomCenterLayout.gaugeTop(guiHeight, bars) + BottomCenterLayout.GAUGE_HEIGHT + 1
				+ BottomCenterLayout.GAUGE_GLOW;
	}

	// ------------------------------------------------------------------ 막대

	/** 빛까지 세어도 바닐라 하트·배고픔 윗변 위이고, 위 여백(좌하단 글줄의 바닥) 안이다. */
	@Test
	void 막대는_빛까지_바닐라_표시와_글줄_사이에_든다() {
		for (int guiHeight = 240; guiHeight <= 2160; guiHeight += 30) {
			for (int bars : barHeights()) {
				assertTrue(gaugeOuterBottom(guiHeight, bars) <= guiHeight - bars, "바닐라 " + bars);
				assertTrue(gaugeOuterTop(guiHeight, bars)
						>= BottomCenterLayout.clearanceTop(guiHeight, bars, true), "글줄 " + bars);
			}
		}
	}

	/**
	 * 하트가 한 줄(방어구 있음)이거나 두 줄(방어구 없음)까지는 바닐라 액션바에 닿지 않는다.
	 *
	 * <p>그보다 높이 쌓이면 바닐라가 하트와 액션바 사이에 남기는 틈이 막대 두께(빛 포함 9)보다
	 * 좁아진다. 그때는 하트를 피하는 쪽을 고른다 — 액션바 글은 몇 초 뒤 사라지지만 하트는 늘 있다.
	 */
	@Test
	void 바닐라가_틈을_남기는_동안은_막대가_액션바에_닿지_않는다() {
		int oneRowArmor = BottomBarMetrics.height(20.0F, 0.0F, true, 0, true);
		int twoRows = BottomBarMetrics.height(40.0F, 0.0F, false, 0, true);
		for (int guiHeight = 240; guiHeight <= 2160; guiHeight += 30) {
			for (int bars : new int[] {oneRowArmor, twoRows}) {
				assertTrue(!overlaps(gaugeOuterTop(guiHeight, bars), gaugeOuterBottom(guiHeight, bars),
						actionBarTop(guiHeight), actionBarBottom(guiHeight)), "바닐라 높이 " + bars);
			}
		}
	}

	@Test
	void 막대는_가운데에_선다() {
		for (int guiWidth : new int[] {320, 427, 480, 640, 960, 1920}) {
			int left = BottomCenterLayout.gaugeLeft(guiWidth);
			assertTrue(left >= 0);
			assertTrue(Math.abs(left + BottomCenterLayout.GAUGE_WIDTH / 2 - guiWidth / 2) <= 1);
		}
	}

	@Test
	void 막대를_안_그리면_자리를_차지하지_않는다() {
		assertEquals(300 - 41, BottomCenterLayout.clearanceTop(300, 41, false));
		assertEquals(300 - 41 - 3 - 5 - 3, BottomCenterLayout.clearanceTop(300, 41, true));
	}

	// ------------------------------------------------------------------ 알림 줄

	/**
	 * 모든 창·배율·바닐라 높이·팀 글줄 수에서, 세 줄을 다 쌓고 떠오르는 중(아래로 2픽셀)이어도
	 * 바닐라 액션바 · 손에 든 물건 이름 · 막대 · 좌하단 팀 레벨 글줄 어느 것과도 겹치지 않는다.
	 */
	@Test
	void 알림_줄은_어떤_화면에서도_아래_것들과_겹치지_않는다() {
		for (int[] window : WINDOWS) {
			for (int scale : SCALES) {
				int guiHeight = window[1] / scale;
				if (guiHeight < 240) {
					// 바닐라는 GUI 가 320×240 보다 작아지는 배율을 고르지 못하게 한다.
					continue;
				}
				for (int bars : barHeights()) {
					for (boolean gauge : new boolean[] {false, true}) {
						for (int teamLines = 0; teamLines <= 2; teamLines++) {
							check(guiHeight, bars, gauge, teamLines,
									window[0] + "×" + window[1] + " 배율 " + scale + " 바닐라 " + bars
											+ " 막대 " + gauge + " 글줄 " + teamLines);
						}
					}
				}
			}
		}
	}

	private static void check(int guiHeight, int bars, boolean gauge, int teamLines, String where) {
		int bottom = BottomCenterLayout.procBottom(guiHeight, bars, gauge, teamLines);
		int leftStackTop = BottomCenterLayout.leftStackTop(guiHeight, bars, gauge, teamLines);
		for (int index = 0; index < PerkProcFeed.MAX_LINES; index++) {
			for (float rise : new float[] {0.0F, 0.5F, 1.0F}) {
				int top = BottomCenterLayout.procLineTop(bottom, index, rise);
				int lineBottom = top + BottomCenterLayout.PROC_LINE_HEIGHT;
				assertTrue(lineBottom <= actionBarTop(guiHeight), "액션바 — " + where);
				assertTrue(lineBottom <= leftStackTop, "팀 글줄 — " + where);
				assertTrue(lineBottom <= guiHeight - bars, "바닐라 — " + where);
				if (gauge) {
					assertTrue(lineBottom <= gaugeOuterTop(guiHeight, bars), "막대 — " + where);
				}
				assertTrue(top >= 0, "화면 위 — " + where);
			}
		}
	}

	/** 쌓인 줄끼리도 겹치지 않는다. 막 떠오르는 바닥 줄이 윗줄을 파고들지 않는다. */
	@Test
	void 쌓인_줄끼리_겹치지_않는다() {
		int bottom = BottomCenterLayout.procBottom(270, 41, true, 2);
		for (int index = 0; index + 1 < PerkProcFeed.MAX_LINES; index++) {
			int lowerTop = BottomCenterLayout.procLineTop(bottom, index, 1.0F);
			int upperBottom = BottomCenterLayout.procLineTop(bottom, index + 1, 0.0F)
					+ BottomCenterLayout.PROC_LINE_HEIGHT;
			assertTrue(upperBottom <= lowerTop + BottomCenterLayout.PROC_RISE,
					"윗줄이 떠오르는 중에도 아랫줄 위");
			assertTrue(BottomCenterLayout.procLineTop(bottom, index + 1, 1.0F)
					+ BottomCenterLayout.PROC_LINE_HEIGHT <= lowerTop, "자리 잡은 뒤에는 틈이 있다");
		}
	}

	/** 「드래곤전 대비 증강까지 12」처럼 가운데를 넘는 좌하단 글줄이 있으면 그 위로 올라간다. */
	@Test
	void 팀_글줄이_액션바보다_높으면_그_위로_올라간다() {
		int guiHeight = 270;
		int armor = BottomBarMetrics.height(20.0F, 0.0F, true, 0, false);
		int withLines = BottomCenterLayout.procBottom(guiHeight, armor, true, 2);
		int withoutLines = BottomCenterLayout.procBottom(guiHeight, armor, true, 0);

		assertEquals(guiHeight - BottomCenterLayout.ACTION_BAR_TOP - BottomCenterLayout.PROC_GAP,
				withoutLines, "글줄이 없으면 액션바 바로 위");
		assertTrue(withLines < withoutLines);
	}

	/** 가장 긴 증강 이름도 가장 좁은 GUI(320) 안에 든다. */
	@Test
	void 가장_긴_이름도_가장_좁은_화면에_든다() {
		int widest = 0;
		for (String name : new String[] {"무한 회복, 무한 식비", "무뎌지지 않는 갑주", "마르지 않는 화살통",
				"뚝배기 대신 피통"}) {
			// 「⟡」 는 유니폰트 글자라 FakeFont 가 재는 6 보다 넓을 수 있다. 넉넉히 4 를 더한다.
			widest = Math.max(widest, BottomCenterLayout.procLineWidth(
					FakeFont.width(new PerkProcFeed.Line("k", name, 0, 0).text()) + 4));
		}
		assertTrue(widest < 320 - 2 * 16, "가장 넓은 줄 " + widest);
	}
}
