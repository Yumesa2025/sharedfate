package com.sharedfate.client.perk;

import com.sharedfate.net.PerkSyncPayload;
import com.sharedfate.ui.OwnedPerkPanelLayout;
import com.sharedfate.ui.PanelScroll;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * 증강 선택 화면 위에 덮는 <b>「보유 증강」 겹판</b>.
 *
 * <p>고르는 동안 「내가 이미 뭘 가졌나」가 보여야 판단이 된다. <b>화면을 갈아타지 않는다</b> —
 * 선택 화면은 강제 선택 세션이 띄운 것이라, 다른 화면으로 갔다가 돌아오는 길이 하나라도
 * 어긋나면 카드를 영영 못 고른다. 그래서 같은 화면 위에 덮고 단추로 접었다 편다.
 *
 * <p>자리 계산은 {@link OwnedPerkPanelLayout} 이 하고 여기서는 그리기와 스크롤만 맡는다.
 *
 * <p>목록의 출처는 {@link PerkClientState#owned()} 로, 팀 화면의 「증강」 탭이 쓰는 것과 같다.
 * 클라이언트는 증강 풀을 읽지 않으므로 <b>서버가 보내 준 것만</b> 보여 줄 수 있다.
 */
public final class OwnedPerkPanel {

	/** 판 바탕. 뒤의 카드가 비치지 않을 만큼 진하게 덮는다. */
	private static final int BACKGROUND = 0xF0101418;

	private static final int BORDER = 0xFF3C4450;

	private static final int HEADER = 0xFFE8EDF2;

	private static final int DESCRIPTION = 0xFF9AA4B0;

	/**
	 * 이름 뒤에 붙는 세트 유형 딱지의 색.
	 *
	 * <p>이름보다 흐리다. 유형은 <b>곁들이는 정보</b>지 증강의 이름이 아니라서, 같은 밝기로
	 * 붙이면 어디까지가 이름인지 눈이 못 가른다.
	 */
	private static final int SET_TYPE = 0xFF7F8A99;

	private static final int SEPARATOR = 0xFF2A313A;

	/**
	 * 「유적 감별사」 좌표의 글자색.
	 *
	 * <p>팀 화면의 증강 탭과 고를 때 띄운 채팅 한 줄이 쓰는 것과 같은 물색이다. 같은 값을 세
	 * 곳에서 읽는 사람이 「이게 그거」를 색으로 알아채야 한다.
	 */
	private static final int RUIN_COORD = 0xFF55FFFF;

	/** 목록이 비었을 때의 안내. */
	private static final int EMPTY = 0xFF8790A0;

	private static final int SCROLLBAR_TRACK = 0xFF1A1F26;

	private static final int SCROLLBAR_THUMB = 0xFF5A6675;

	private static final int SCROLLBAR_WIDTH = 4;

	private static final int SCROLL_STEP = 12;

	private static final int MIN_THUMB_HEIGHT = 12;

	/** 화면 가장자리에 남길 여백. */
	public static final int SCREEN_MARGIN = 10;

	public static final int PADDING = 6;

	/** 증강 하나와 다음 증강 사이의 틈. */
	private static final int ENTRY_GAP = 4;

	/** 접어 둔 글줄 하나. */
	private record Line(FormattedCharSequence text, int color, int height) {
	}

	private OwnedPerkPanelLayout layout = OwnedPerkPanelLayout.hidden();

	private final List<Line> lines = new ArrayList<>();

	private int contentHeight;

	private int scroll;

	private int count;

	/**
	 * 지금 화면 크기에 맞춰 <b>화면 한가운데</b>에 모달을 세우고 목록을 접는다.
	 *
	 * <p>여는 순간과 화면 크기가 바뀔 때 부른다. 매 프레임 접으면 증강이 늘어날수록 무거워진다.
	 */
	public void layout(Font font, int screenWidth, int screenHeight) {
		layout = OwnedPerkPanelLayout.fit(screenWidth, screenHeight, SCREEN_MARGIN,
				font.lineHeight, PADDING);
		lines.clear();
		contentHeight = 0;
		List<PerkSyncPayload.Owned> owned = PerkClientState.owned();
		count = owned.size();
		if (!layout.visible()) {
			scroll = 0;
			return;
		}

		int wrap = layout.wrapWidth(SCROLLBAR_WIDTH + 2);
		// 「유적 감별사」 좌표를 목록 맨 위에 둔다. 팀 화면은 목록 오른쪽에 세우지만 이 겹판은
		// 화면 한가운데 선 모달이라({@link OwnedPerkPanelLayout}) 오른쪽에 빈자리가 없다 —
		// 그쪽에는 지금 고르는 카드가 있다. 자세한 근거는 아래 addRuinCoords 에 있다.
		addRuinCoords(font, wrap);
		for (PerkSyncPayload.Owned perk : owned) {
			// 이름 뒤에 세트 유형을 흐린 글씨로 붙인다 — 「짐꾼 가호」처럼. 한 줄에 다 넣는
			// 이유는 줄이 늘면 목록이 금세 길어져서다. 무유형 증강 열하나에는 안 붙는다.
			Component title = Component.literal("· " + perk.name())
					.withStyle(style -> style.withColor(rarityColor(perk.rarity())));
			if (perk.hasSetTypes()) {
				title = title.copy().append(Component.literal("  " + perk.setTypes())
						.withStyle(style -> style.withColor(SET_TYPE)));
			}
			// 이름이 길면 접힌다. 접힌 뒷줄도 같은 색을 이어받는다.
			for (FormattedCharSequence line : font.split(title, wrap)) {
				add(new Line(line, rarityColor(perk.rarity()), font.lineHeight));
			}
			List<FormattedCharSequence> wrapped =
					font.split(Component.literal(perk.description()), wrap);
			for (int index = 0; index < wrapped.size(); index++) {
				// 증강과 증강 사이는 마지막 설명 줄의 키를 늘려 벌린다. 빈 줄을 넣는 것보다
				// 스크롤 계산이 단순하다 — 팀 화면의 증강 탭과 같은 방식이다.
				boolean last = index == wrapped.size() - 1;
				add(new Line(wrapped.get(index), DESCRIPTION,
						font.lineHeight + (last ? ENTRY_GAP : 0)));
			}
		}
		scroll = PanelScroll.clamp(scroll, contentHeight, layout.viewHeight());
	}

	/**
	 * 「유적 감별사」 좌표를 목록 <b>맨 위</b>에 넣는다. 그 증강이 없으면 아무것도 안 한다.
	 *
	 * <h2>왜 오른쪽이 아닌가</h2>
	 *
	 * <p>팀 화면의 증강 탭은 목록 오른쪽 빈자리에 좌표를 세운다. <b>여기서는 그럴 수 없다</b> —
	 * 이 겹판은 {@link com.sharedfate.ui.OwnedPerkPanelLayout} 이 화면 한가운데 세우는 모달이고,
	 * 그 오른쪽에 있는 것은 빈자리가 아니라 <b>지금 고르는 카드</b>다. 거기에 글자를 얹으면 카드를
	 * 덮는다. 모달 자체를 넓히는 것도 안 된다 — 폭 상한 320 은 「글줄이 길어지면 눈이 다음 줄을
	 * 못 찾는다」는 근거로 잡은 값이다.
	 *
	 * <h2>왜 그래도 넣는가</h2>
	 *
	 * <p>규약 35 에서 설명 뒤 괄호 덧붙임을 지웠다. 여기에 안 넣으면 이 겹판에서는 「유적
	 * 감별사」가 좌표 없는 이름 한 줄로만 남는다 — 두 화면이 다른 말을 하게 된다. 고르는 동안
	 * 「내가 이미 뭘 가졌나」를 보는 판인데, 그 증강이 들고 있는 값이 안 보이면 반쪽이다.
	 *
	 * <h2>왜 맨 위인가</h2>
	 *
	 * <p>머리글 아래 첫 줄이면 스크롤하지 않아도 보인다. 목록과 함께 굴러가므로
	 * {@code OwnedPerkPanelLayout} 을 건드릴 일이 없다 — 머리글 자리를 늘리면 {@code listTop} 이
	 * 옮겨 가고, 그 계산에는 이미 제 시험이 붙어 있다.
	 */
	private void addRuinCoords(Font font, int wrap) {
		List<String> coords = PerkClientState.ruinCoords();
		if (coords.isEmpty()) {
			return;
		}
		// 접어 둔 줄을 먼저 모은다. 마지막 한 줄만 키를 늘려야 하는데, 한 좌표가 두 줄로 접힐
		// 수 있어 「마지막 좌표의 마지막 줄」을 미리 알 수 없다.
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (String coord : coords) {
			wrapped.addAll(font.split(Component.literal(coord), wrap));
		}
		for (int index = 0; index < wrapped.size(); index++) {
			// 마지막 줄만 키를 늘려 아래 증강 목록과 벌린다. 빈 줄을 넣는 것보다 스크롤 계산이
			// 단순하다 — 증강과 증강 사이를 벌리는 것과 같은 방식이다.
			boolean last = index == wrapped.size() - 1;
			add(new Line(wrapped.get(index), RUIN_COORD,
					font.lineHeight + (last ? ENTRY_GAP : 0)));
		}
	}

	private void add(Line line) {
		lines.add(line);
		contentHeight += line.height();
	}

	/** 그릴 자리가 나왔는가. 좁은 화면에서는 아예 열지 않는다. */
	public boolean visible() {
		return layout.visible();
	}

	/** 맨 위로 되돌린다. 다시 열 때 지난번 자리에 남아 있으면 무엇을 보는지 헷갈린다. */
	public void resetScroll() {
		scroll = 0;
	}

	/** 이 자리가 겹판 안인가. 겹판이 떠 있는 동안 카드 클릭을 막는 데 쓴다. */
	public boolean contains(double mouseX, double mouseY) {
		return layout.visible() && mouseX >= layout.left() && mouseX <= layout.right()
				&& mouseY >= layout.top() && mouseY <= layout.bottom();
	}

	/**
	 * 휠을 굴린다.
	 *
	 * @return 실제로 굴렸으면 참. 거짓이면 부르는 쪽이 원래 하던 일을 한다
	 */
	public boolean scroll(double scrollY) {
		if (!layout.visible() || !PanelScroll.overflows(contentHeight, layout.viewHeight())) {
			return false;
		}
		int moved = scroll - (int) Math.round(scrollY) * SCROLL_STEP;
		scroll = PanelScroll.clamp(moved, contentHeight, layout.viewHeight());
		return true;
	}

	/**
	 * 겹판을 그린다.
	 *
	 * <p>부르는 곳은 {@code PerkOfferScreen.extractRenderState} 의 <b>맨 끝</b>이다. 카드와 세트
	 * 판을 모두 그린 뒤라야 그 위를 덮는다.
	 */
	public void render(GuiGraphicsExtractor graphics, Font font) {
		if (!layout.visible()) {
			return;
		}
		graphics.fill(layout.left(), layout.top(), layout.right(), layout.bottom(), BACKGROUND);
		graphics.outline(layout.left(), layout.top(), layout.width(), layout.height(), BORDER);

		graphics.text(font, "보유 증강 " + count + "개", layout.contentLeft(), layout.headerY(),
				HEADER);
		int separatorY = layout.headerY() + font.lineHeight + 1;
		graphics.fill(layout.contentLeft(), separatorY, layout.contentRight(), separatorY + 1,
				SEPARATOR);

		if (lines.isEmpty()) {
			graphics.text(font, "아직 고른 증강이 없습니다.", layout.contentLeft(), layout.listTop(),
					EMPTY);
			return;
		}

		int top = layout.listTop();
		int bottom = layout.listBottom();
		if (bottom <= top) {
			return;
		}
		graphics.enableScissor(layout.contentLeft(), top, layout.contentRight(), bottom);
		int y = top - scroll;
		for (Line line : lines) {
			// 창 밖으로 완전히 벗어난 줄은 건너뛴다. 증강이 백 개가 되어도 그리는 값은 창
			// 크기에 묶인다.
			if (y + line.height() >= top && y <= bottom) {
				graphics.text(font, line.text(), layout.contentLeft(), y, line.color());
			}
			y += line.height();
		}
		graphics.disableScissor();

		renderScrollbar(graphics, top, bottom);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, int top, int bottom) {
		int viewHeight = bottom - top;
		if (!PanelScroll.overflows(contentHeight, viewHeight)) {
			return;
		}
		int left = layout.contentRight() - SCROLLBAR_WIDTH;
		graphics.fill(left, top, left + SCROLLBAR_WIDTH, bottom, SCROLLBAR_TRACK);
		int thumb = PanelScroll.thumbHeight(contentHeight, viewHeight, MIN_THUMB_HEIGHT);
		int thumbTop = PanelScroll.thumbTop(top, viewHeight, thumb, scroll, contentHeight);
		graphics.fill(left, thumbTop, left + SCROLLBAR_WIDTH, thumbTop + thumb, SCROLLBAR_THUMB);
	}

	/** 등급별 글자색. 팀 화면의 「증강」 탭과 같은 값이다. */
	private static int rarityColor(String rarity) {
		return switch (rarity) {
			case "gold" -> 0xFFFFC63A;
			case "prism" -> 0xFF5FE0D8;
			default -> 0xFFC0C6CC;
		};
	}
}
