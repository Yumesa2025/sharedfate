package com.sharedfate.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 인벤토리 화면(E) 왼쪽, {@link InventoryTeamButton} 단추 <b>바로 아래</b>에 세로로 늘어놓는
 * 능력치의 자리 계산.
 *
 * <p>인벤토리 창은 화면 한가운데에 있고, <b>왼쪽에 남는 자리는 GUI 배율과 화면 크기에 따라
 * 몇 배씩 달라진다.</b> 배율 1 에서는 팔백 픽셀이 남지만 배율 4 에서는 백오십, 조합법 책을
 * 펼치면 오십도 안 남는 경우가 있다. 그래서 「몇 픽셀에 무엇을 그린다」를 못 박지 않고,
 * <b>남은 폭을 받아 그 안에 들어가는 가장 자세한 모양</b>을 고른다.
 *
 * <p>단추와 줄들을 <b>하나의 덩어리</b>로 보고, 그 덩어리의 오른쪽 끝을 창(또는 펼친 조합법
 * 책)에 붙인다. 덩어리 안에서는 모두 왼쪽 정렬이라 이름이 세로로 가지런히 선다.
 */
public final class InventoryStatPanel {
	/** 한 줄의 높이. 팀 화면의 {@code ROW_HEIGHT}(12) 보다 촘촘하다 — 여기는 자리가 없다. */
	public static final int LINE_HEIGHT = 10;
	/** 단추 아래 첫 줄까지의 틈. */
	public static final int BUTTON_GAP = 4;
	/** 묶음과 묶음 사이의 틈. 「내 능력치」와 「이 판의 몹」을 눈으로 가른다. */
	public static final int GROUP_GAP = 4;

	/**
	 * 자리가 좁아질수록 차례로 물러나는 모양.
	 *
	 * <p><b>순서가 곧 규칙이다.</b> 위에서부터 들어가는 것을 고른다.
	 *
	 * <p>아래 보기는 최종값 줄({@link StatRow})의 모양이다. 인벤토리 화면이 지금 그리는 보정치 줄
	 * ({@link BonusRow})은 같은 차례로 「최대 체력  +6  (하트 3개)」 → 「체력  +6」 → 「체력 +6」 →
	 * 「체력」/「+6」 으로 물러난다.
	 *
	 * <ul>
	 *   <li>{@link #FULL} — 「최대 체력  20 → 26  (+6)」. 온전한 이름과 증감까지.</li>
	 *   <li>{@link #SHORT} — 「체력  20 → 26  (+6)」. 이름만 줄인다.</li>
	 *   <li>{@link #TIGHT} — 「체력 20 → 26」. 증감 괄호를 뗀다. 오르내림은 색이 말한다.</li>
	 *   <li>{@link #WRAPPED} — 「체력」 / 「20 → 26」 두 줄로 접는다. 세로를 두 배 쓰는 대신
	 *       가로를 절반으로 줄인다. 덩어리가 HUD 세트 줄을 피해 내려온 만큼 세로가 줄어들어
	 *       ({@link InventoryTeamButton#statHeight}) 짧은 화면에서는 이 모양이 세로에 걸려
	 *       빠지기도 한다.</li>
	 *   <li>{@link #HIDDEN} — 가로로든 세로로든 접어도 안 들어가면 <b>아무것도 그리지
	 *       않는다.</b> 화면 밖으로
	 *       삐져나가거나 창 위에 겹쳐 그리느니 없는 편이 낫다. 단추는 그대로 남으므로 팀
	 *       화면 「능력치」 탭에서 언제나 같은 값을 볼 수 있다.</li>
	 * </ul>
	 */
	public enum Style {
		FULL,
		SHORT,
		TIGHT,
		WRAPPED,
		HIDDEN
	}

	/**
	 * 실제로 그릴 글자 한 줄.
	 *
	 * @param text 그대로 그릴 글자
	 * @param color 글자색. {@link PanelRow#color()} 가 정한 값이다
	 * @param y 덩어리 맨 위에서부터의 세로 거리
	 */
	public record Line(String text, int color, int y) {
	}

	/**
	 * 고른 모양과 그 결과.
	 *
	 * @param style 고른 모양. {@link Style#HIDDEN} 이면 {@code lines} 가 비어 있다
	 * @param lines 그릴 줄들
	 * @param width 가장 긴 줄의 폭
	 * @param height 줄 전체가 차지하는 높이
	 */
	public record Layout(Style style, List<Line> lines, int width, int height) {
		public boolean visible() {
			return style != Style.HIDDEN;
		}
	}

	private static final Layout NOTHING = new Layout(Style.HIDDEN, List.of(), 0, 0);

	private InventoryStatPanel() {
	}

	/**
	 * 주어진 자리에 들어가는 가장 자세한 모양을 고른다.
	 *
	 * @param groups     줄 묶음들. 묶음 사이에는 {@link #GROUP_GAP} 만큼 틈이 생긴다
	 * @param maxWidth   쓸 수 있는 가로(px)
	 * @param maxHeight  쓸 수 있는 세로(px)
	 * @param measure    글자의 폭을 재는 것. 화면은 {@code font::width} 를 넘긴다
	 */
	public static Layout layout(List<? extends List<? extends PanelRow>> groups, int maxWidth,
			int maxHeight,
			ToIntFunction<String> measure) {
		if (groups == null || groups.isEmpty() || maxWidth <= 0 || maxHeight <= 0) {
			return NOTHING;
		}
		for (Style style : Style.values()) {
			if (style == Style.HIDDEN) {
				break;
			}
			List<Line> lines = build(groups, style);
			if (lines.isEmpty()) {
				return NOTHING;
			}
			int width = 0;
			for (Line line : lines) {
				width = Math.max(width, measure.applyAsInt(line.text()));
			}
			int height = lines.getLast().y() + LINE_HEIGHT;
			if (width <= maxWidth && height <= maxHeight) {
				return new Layout(style, lines, width, height);
			}
		}
		return NOTHING;
	}

	/** 한 모양으로 줄을 만든다. 어느 모양이든 줄의 차례와 묶음 사이의 틈은 같다. */
	private static List<Line> build(List<? extends List<? extends PanelRow>> groups, Style style) {
		List<Line> lines = new ArrayList<>();
		int y = 0;
		boolean firstGroup = true;
		for (List<? extends PanelRow> group : groups) {
			if (group == null || group.isEmpty()) {
				continue;
			}
			if (!firstGroup) {
				y += GROUP_GAP;
			}
			firstGroup = false;
			for (PanelRow row : group) {
				int color = row.color();
				if (style == Style.WRAPPED && row.wraps()) {
					lines.add(new Line(row.shortLabel(), color, y));
					y += LINE_HEIGHT;
					lines.add(new Line(row.values(), color, y));
					y += LINE_HEIGHT;
					continue;
				}
				lines.add(new Line(text(row, style), color, y));
				y += LINE_HEIGHT;
			}
		}
		return lines;
	}

	private static String text(PanelRow row, Style style) {
		return switch (style) {
			case FULL -> row.fullLine();
			case SHORT -> row.shortLine();
			default -> row.tightLine();
		};
	}
}
