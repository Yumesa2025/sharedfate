package com.sharedfate.client.hud;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>자리</b> — 오른쪽 위 패널과 가운데 위 시전 바를 어디에, 얼마나 넓게,
 * 몇 줄까지 그리는가. <b>화면 크기를 하나도 가정하지 않는다.</b>
 *
 * <h2>⚠ 화면 크기를 가정하지 마십시오 ({@code docs/드래곤-트라이얼.md} 6장)</h2>
 *
 * <p>유적 감별사 좌표가 기본 설정에서만 안 보였다 — 1080p 의 기본 GUI 배율이 4 라 화면이 480×270
 * 인데, 남는 자리보다 넓은 줄을 그리려다 그냥 빠져나갔다. 이 HUD 는 두 덩어리(폭 158 · 230)가 그
 * 좁은 화면을 보스바 · 상태이상 아이콘 · 채팅 · 핫바 · 액션바 · 자막과 나눠 써야 한다. 그래서 자리는
 * 상수가 아니라 <b>그 순간 다른 것들이 차지한 칸</b>으로 정한다. 실제로 걸리는 화면을 셈해 보면
 * 이렇다(채팅 기본 폭 320 · 배율 1 → 오른쪽 끝 332).
 *
 * <ul>
 *   <li><b>480×270</b>(1080p 기본) — 보스바 오른쪽 끝 331 · 채팅 332. 패널을 158 로 그리면 왼쪽 끝이
 *       320 이라 <b>보스바 이름과 채팅에 둘 다 물린다.</b> 줄이 적으면 폭을 지킨 채 보스바 아래 ·
 *       채팅 위에 끼우고, 거기 다 안 들어가면 폭을 143 으로 줄여 둘의 오른쪽에 세운다</li>
 *   <li><b>426×240</b>(720p · 1440p · 4K 기본) — 시전 바까지 서면 보스바 · 시전 바 아래와 채팅 위
 *       사이가 한 줄 높이뿐이다. 그래서 대개 폭을 89 로 줄여 채팅 오른쪽에 세운다</li>
 * </ul>
 *
 * <h2>바닐라에서 옮긴 값 — 26.3 바이트코드에서 읽었다</h2>
 *
 * <ul>
 *   <li><b>상태이상 아이콘</b>({@code Hud.extractEffects}) — 24×24, 오른쪽 끝에서 25 칸씩 왼쪽으로.
 *       이로운 줄 y = 1, 해로운 줄 y = 1 + 26. 데모 판이면 둘 다 15 아래. 아이콘을 숨긴 효과
 *       ({@code showIcon} 거짓)는 자리를 안 먹고, 상태이상을 스스로 그리는 화면(인벤토리)이 떠
 *       있으면 HUD 아이콘이 통째로 빠진다 — 그 판정은 부르는 쪽({@link TrialTimersHud})이 한다</li>
 *   <li><b>보스바</b>({@code BossHealthOverlay.extractRenderState}) — 첫 막대 y = 12, 막대 높이 5,
 *       이름은 막대 9 위, 한 개마다 19 아래. 막대 하나를 그린 뒤 y 가 {@code guiHeight / 3} 에 닿으면
 *       나머지는 안 그린다. 막대 폭 182(가운데 ±91), 이름은 가운데 정렬이라 182 보다 넓을 수 있다</li>
 *   <li><b>액션바</b>({@code Hud.extractOverlayMessage}) — {@code guiHeight - 68} 에서 글자를 4 위로,
 *       배경까지 74</li>
 *   <li><b>자막</b>({@code SubtitleOverlay}) — 오른쪽 아래, 줄 가운데 {@code guiHeight - 35 - 10i}</li>
 *   <li><b>채팅</b>({@code ChatComponent}) — 바닥 {@code guiHeight - 40}, 배경 오른쪽 끝
 *       {@code 폭 + 12 × 배율} — 부르는 쪽이 설정에서 재어 넘긴다</li>
 * </ul>
 */
public final class TrialTimersLayout {

	// ------------------------------------------------------------------ 바닐라 값

	/** 상태이상 아이콘 한 칸(정사각형). */
	static final int ICON_SIZE = 24;
	/** 상태이상 아이콘이 왼쪽으로 늘어서는 간격. */
	static final int ICON_STEP = 25;
	/** 이로운 줄의 윗변. */
	static final int ICON_TOP = 1;
	/** 해로운 줄은 이로운 줄보다 이만큼 아래다. */
	static final int HARMFUL_ROW_OFFSET = 26;
	/** 데모 판이면 아이콘이 이만큼 내려간다(남은 시간 글자 자리). */
	static final int DEMO_SHIFT = 15;

	/** 첫 보스바 막대의 윗변. */
	static final int BOSS_FIRST_Y = 12;
	/** 보스바 하나가 먹는 높이(이름 + 막대 + 틈). */
	static final int BOSS_STEP = 19;
	/** 보스바 막대의 높이. */
	static final int BOSS_BAR_HEIGHT = 5;
	/** 보스바 막대 폭의 절반. 핫바와 같은 182 다. */
	static final int BOSS_BAR_HALF_WIDTH = 91;

	/** 핫바 폭의 절반. */
	static final int HOTBAR_HALF_WIDTH = 91;
	/** 액션바(배경 포함)의 윗변이 화면 아래끝에서 떨어진 거리. */
	static final int ACTION_BAR_RESERVE = 74;
	/**
	 * 자막을 켜 둔 사람에게 비워 두는 아래쪽 높이 — 자막 다섯 줄. {@code 35 + 5 + 10 × 4}.
	 *
	 * <p>자막 줄 수는 그때그때 바뀌고 상한이 없다. 그 수를 따라가면 소리가 날 때마다 패널 줄이
	 * 사라졌다 돌아온다 — 전투 중에 줄이 깜빡이는 쪽이 더 나쁘다. 그래서 다섯 줄을 늘 비운다. 여섯 줄
	 * 넘게 겹쳐 울리면 맨 위 자막과 패널 맨 아래가 닿을 수 있다(알고 둔다).
	 */
	static final int SUBTITLE_RESERVE = 80;

	// ------------------------------------------------------------------ 우리 값

	/** 화면 가장자리 여백. */
	static final int MARGIN = 2;
	/** 다른 표시와 띄우는 틈. */
	static final int GAP = 3;
	/** 조준점 위로 비워 두는 틈. 조준점은 15×15 로 화면 가운데에 선다. */
	static final int CROSSHAIR_CLEARANCE = 8;

	/** 패널 폭 — 사람이 승인한 예시(316 실픽셀, GUI 2배율). */
	public static final int PANEL_WIDTH = 158;
	/**
	 * 패널을 이보다 좁게 줄이지는 않는다. 줄 하나에 색 점 · 한글 서너 자 · 「12.3초」가 들어가는 폭이다.
	 * 426×240 에서 채팅 오른쪽에 남는 89 를 받기 위한 값이다.
	 */
	public static final int PANEL_MIN_WIDTH = 84;
	/** 패널 안쪽 여백. */
	public static final int PAD = 4;
	/** 머리줄 글자 높이. */
	public static final int HEADER_HEIGHT = 9;
	/** 머리줄과 첫 줄 사이. */
	public static final int HEADER_GAP = 3;
	/** 줄 하나의 높이 — 위 2 + 글자 9 + 1 + 바 2 + 아래 1. */
	public static final int ROW_HEIGHT = 15;
	/** 줄 사이. */
	public static final int ROW_GAP = 1;

	/** 시전 바 폭 — 사람이 승인한 예시. */
	public static final int CAST_WIDTH = 230;
	/** 시전 바를 이보다 좁게 줄여야 하면 줄이지 않고 왼쪽 위 표시 아래로 내린다. */
	public static final int CAST_MIN_WIDTH = 120;
	/** 시전 바 윗줄(제목 · 시간) 높이. */
	public static final int CAST_TITLE_HEIGHT = 9;
	/** 윗줄과 막대 사이 · 막대와 대처법 사이. */
	public static final int CAST_LINE_GAP = 2;
	/** 시전 바 막대 높이(테두리 포함). */
	public static final int CAST_BAR_HEIGHT = 7;
	/** 대처법 한 줄 높이. */
	public static final int CAST_HINT_HEIGHT = 9;
	/** 보스바가 하나도 없을 때 시전 바의 윗변. */
	static final int CAST_TOP_WITHOUT_BOSS = 4;

	private TrialTimersLayout() {
	}

	/**
	 * 화면의 한 칸. 오른쪽 · 아래는 <b>포함하지 않는다</b>({@code fill} 과 같은 셈).
	 */
	public record Rect(int left, int top, int right, int bottom) {
		boolean overlapsColumns(int otherLeft, int otherRight) {
			return left < otherRight && otherLeft < right;
		}

		boolean overlapsRows(int otherTop, int otherBottom) {
			return top < otherBottom && otherTop < bottom;
		}
	}

	/**
	 * 자리를 정하는 데 드는 값 전부.
	 *
	 * @param width             화면 폭(GUI 픽셀)
	 * @param height            화면 높이(GUI 픽셀)
	 * @param beneficialIcons   HUD 에 그려지는 이로운 상태이상 아이콘 수
	 * @param harmfulIcons      해로운 아이콘 수
	 * @param demo              데모 판인가
	 * @param bossNameWidths    보스바 이름 폭들 — 바닐라가 그리는 차례 그대로
	 * @param cast              시전 바를 그리는가
	 * @param castHint          시전 바에 대처법 줄이 있는가
	 * @param rowCount          패널 줄 수
	 * @param leftHud           왼쪽 위 좌표 · 세트 표시가 차지한 칸. 없으면 {@code null}
	 * @param chat              채팅이 쓸 수 있는 칸. 숨겼으면 {@code null}
	 * @param hotbarTop         핫바 위 바닐라 표시 · 증강 게이지의 윗변(y)
	 * @param subtitles         자막을 켰는가
	 */
	public record Input(int width, int height, int beneficialIcons, int harmfulIcons, boolean demo,
			List<Integer> bossNameWidths, boolean cast, boolean castHint, int rowCount,
			@Nullable Rect leftHud, @Nullable Rect chat, int hotbarTop, boolean subtitles) {
	}

	/**
	 * 시전 바 자리.
	 *
	 * @param hint 대처법 줄을 그리는가. 조준점까지 자리가 모자라면 뺀다
	 */
	public record CastBox(int x, int y, int width, boolean hint) {
		public int height() {
			return castHeight(hint);
		}

		public int bottom() {
			return y + height();
		}

		/** 막대 윗변. */
		public int barTop() {
			return y + CAST_TITLE_HEIGHT + CAST_LINE_GAP;
		}

		/** 대처법 줄 윗변. */
		public int hintTop() {
			return barTop() + CAST_BAR_HEIGHT + CAST_LINE_GAP;
		}
	}

	/**
	 * 패널 자리.
	 *
	 * @param rows 그릴 줄 수. 받은 줄보다 적으면 위에서부터 들어가는 만큼이다
	 */
	public record PanelBox(int x, int y, int width, int rows) {
		public int height() {
			return panelHeight(rows);
		}

		public int bottom() {
			return y + height();
		}

		/** {@code index} 번째 줄의 윗변. */
		public int rowTop(int index) {
			return y + PAD + HEADER_HEIGHT + HEADER_GAP + index * (ROW_HEIGHT + ROW_GAP);
		}
	}

	/** 자리 둘. 못 그리면 {@code null}. */
	public record Result(@Nullable CastBox cast, @Nullable PanelBox panel) {
	}

	// ------------------------------------------------------------------ 바닐라 셈

	/**
	 * 바닐라가 실제로 그리는 보스바 수. 하나는 언제나 그리고, 막대 하나를 그린 뒤 다음 자리가
	 * {@code guiHeight / 3} 에 닿으면 멈춘다.
	 */
	public static int bossBarsDrawn(int events, int guiHeight) {
		int drawn = 0;
		int y = BOSS_FIRST_Y;
		while (drawn < events) {
			drawn++;
			y += BOSS_STEP;
			if (y >= guiHeight / 3) {
				break;
			}
		}
		return drawn;
	}

	/** 마지막 보스바 막대의 아랫변. 보스바가 없으면 0. */
	public static int bossBottom(int drawn) {
		return drawn <= 0 ? 0 : BOSS_FIRST_Y + BOSS_STEP * (drawn - 1) + BOSS_BAR_HEIGHT;
	}

	/** 상태이상 아이콘의 아랫변. 아이콘이 없으면 0. */
	public static int effectsBottom(int beneficial, int harmful, boolean demo) {
		if (beneficial <= 0 && harmful <= 0) {
			return 0;
		}
		int top = ICON_TOP + (demo ? DEMO_SHIFT : 0);
		return harmful > 0 ? top + HARMFUL_ROW_OFFSET + ICON_SIZE : top + ICON_SIZE;
	}

	/** 상태이상 아이콘 가운데 가장 왼쪽 것의 왼쪽 끝. */
	static int effectsLeft(int beneficial, int harmful, int width) {
		return width - ICON_STEP * Math.max(beneficial, harmful);
	}

	/** 시전 바 높이. */
	static int castHeight(boolean hint) {
		return CAST_TITLE_HEIGHT + CAST_LINE_GAP + CAST_BAR_HEIGHT
				+ (hint ? CAST_LINE_GAP + CAST_HINT_HEIGHT : 0);
	}

	/** 줄 {@code rows} 개짜리 패널 높이. */
	static int panelHeight(int rows) {
		int body = rows <= 0 ? 0 : rows * ROW_HEIGHT + (rows - 1) * ROW_GAP;
		return PAD + HEADER_HEIGHT + HEADER_GAP + body + PAD;
	}

	/** 높이 {@code available} 에 들어가는 줄 수. {@code max} 를 넘지 않는다. */
	static int rowsFitting(int available, int max) {
		int body = available - PAD * 2 - HEADER_HEIGHT - HEADER_GAP;
		if (body < ROW_HEIGHT) {
			return 0;
		}
		return Math.min(max, (body + ROW_GAP) / (ROW_HEIGHT + ROW_GAP));
	}

	// ------------------------------------------------------------------ 자리

	/** 시전 바와 패널의 자리. 시전 바를 먼저 놓는다 — 가운데가 우선이고, 패널이 그것을 비켜 선다. */
	public static Result layout(Input in) {
		int bosses = bossBarsDrawn(in.bossNameWidths().size(), in.height());
		int bossBottom = bossBottom(bosses);
		int effectsBottom = effectsBottom(in.beneficialIcons(), in.harmfulIcons(), in.demo());
		int effectsLeft = effectsLeft(in.beneficialIcons(), in.harmfulIcons(), in.width());

		CastBox cast = in.cast() ? placeCast(in, bossBottom, effectsBottom, effectsLeft) : null;
		PanelBox panel = in.rowCount() > 0
				? placePanel(in, bosses, bossBottom, effectsBottom, cast) : null;
		return new Result(cast, panel);
	}

	/**
	 * 시전 바 — <b>보스바들 바로 아래 가운데</b>(사람 화면).
	 *
	 * <ol>
	 *   <li>윗변은 마지막 보스바 막대 아래</li>
	 *   <li>상태이상 아이콘이 가운데까지 늘어서 있으면 그 아래로 내린다</li>
	 *   <li>왼쪽 위 좌표 · 세트 표시와 같은 높이에 걸리면 그 오른쪽까지 폭을 줄인다. 너무 좁아지면
	 *       폭을 지키고 그 아래로 내린다. 아이콘보다 나중에 보는 것은 아이콘 때문에 내려간 자리에서
	 *       다시 재야 하기 때문이다 — 폭을 줄이는 것은 오른쪽 끝도 당기므로 아이콘 판단을 뒤집지 않는다</li>
	 *   <li>조준점(또는 채팅 꼭대기)에 닿으면 대처법 줄을 빼고, 그래도 닿으면 그리지 않는다 — 조준점을
	 *       가리는 HUD 는 싸우는 화면에서 HUD 가 없는 것보다 나쁘다</li>
	 * </ol>
	 */
	static @Nullable CastBox placeCast(Input in, int bossBottom, int effectsBottom, int effectsLeft) {
		int w = in.width();
		boolean hint = in.castHint();
		int top = bossBottom > 0 ? bossBottom + GAP + 1 : CAST_TOP_WITHOUT_BOSS;
		int width = Math.min(CAST_WIDTH, w - MARGIN * 2);

		if (effectsBottom > 0 && (w - width) / 2 + width + GAP > effectsLeft
				&& top < effectsBottom) {
			top = effectsBottom + GAP;
		}

		Rect left = in.leftHud();
		if (left != null && left.overlapsRows(top, top + castHeight(hint))) {
			int half = w / 2 - (left.right() + GAP);
			if (half * 2 < width) {
				if (half * 2 >= CAST_MIN_WIDTH) {
					width = half * 2;
				} else {
					top = Math.max(top, left.bottom() + GAP);
				}
			}
		}
		int x = (w - width) / 2;

		int limit = in.height() / 2 - CROSSHAIR_CLEARANCE;
		// 왼쪽 위 표시 아래로 내려간 시전 바가 작은 화면(320×240)에서는 채팅 꼭대기(110)에 닿는다.
		Rect chat = in.chat();
		if (chat != null && chat.right() + GAP > x) {
			limit = Math.min(limit, chat.top() - GAP);
		}
		if (hint && top + castHeight(true) > limit) {
			hint = false;
		}
		if (top + castHeight(hint) > limit || width <= 0) {
			return null;
		}
		return new CastBox(x, top, width, hint);
	}

	/**
	 * 패널 — <b>바닐라 상태이상 아이콘 바로 아래, 오른쪽 끝</b>(사람 화면). 아이콘이 없으면 위쪽
	 * 여백만 둔다.
	 *
	 * <p>폭 158 로 세우면 가운데 덩어리(보스바 · 시전 바)나 채팅 · 핫바와 같은 칸에 걸리는 화면이
	 * 있다. 그때 길이 둘이다.
	 *
	 * <ul>
	 *   <li><b>가 — 폭을 지킨다.</b> 가운데 덩어리 아래로 내리고, 채팅 · 핫바 위에서 멈춘다</li>
	 *   <li><b>나 — 폭을 줄인다.</b> 걸리는 것들의 오른쪽에 세운다. {@link #PANEL_MIN_WIDTH} 아래로는
	 *       안 줄인다</li>
	 * </ul>
	 *
	 * <p>가에 줄이 다 들어가면 가. 아니면 줄이 더 많이 들어가는 쪽 — 이 화면이 답하는 것이 「무엇이
	 * 언제 오는가」라서 넓은 패널에 두 줄보다 좁은 패널에 다섯 줄이 낫다. 같으면 가(넓은 쪽).
	 * 어느 쪽에도 한 줄도 안 들어가면 그리지 않는다.
	 */
	static @Nullable PanelBox placePanel(Input in, int bosses, int bossBottom, int effectsBottom,
			@Nullable CastBox cast) {
		int w = in.width();
		int h = in.height();
		int top0 = effectsBottom > 0 ? effectsBottom + GAP : MARGIN;
		int fullWidth = Math.min(PANEL_WIDTH, w - MARGIN * 2);
		if (fullWidth <= 0) {
			return null;
		}
		int bottomBase = h - Math.max(ACTION_BAR_RESERVE, in.subtitles() ? SUBTITLE_RESERVE : 0)
				- GAP;

		// 위에서 내려오는 것(가운데 덩어리 · 왼쪽 위 표시)과 아래에서 올라오는 것(채팅 · 핫바).
		List<Rect> above = new ArrayList<>();
		Rect center = centerColumn(in, bosses, bossBottom, cast);
		if (center != null) {
			above.add(center);
		}
		if (in.leftHud() != null) {
			above.add(in.leftHud());
		}
		List<Rect> below = new ArrayList<>();
		if (in.chat() != null) {
			below.add(in.chat());
		}
		below.add(new Rect(w / 2 - HOTBAR_HALF_WIDTH, in.hotbarTop(), w / 2 + HOTBAR_HALF_WIDTH, h));

		// 가 — 폭을 지킨다.
		int fullLeft = w - MARGIN - fullWidth;
		int topA = top0;
		int bottomA = bottomBase;
		for (Rect rect : above) {
			if (rect.right() + GAP > fullLeft) {
				topA = Math.max(topA, rect.bottom() + GAP);
			}
		}
		for (Rect rect : below) {
			if (rect.right() + GAP > fullLeft) {
				bottomA = Math.min(bottomA, rect.top() - GAP);
			}
		}
		int rowsA = rowsFitting(bottomA - topA, in.rowCount());
		if (rowsA >= in.rowCount()) {
			return new PanelBox(fullLeft, topA, fullWidth, rowsA);
		}

		// 나 — 폭을 줄인다. 패널이 서는 높이(top0 ~ bottomBase)에 걸리는 것들의 오른쪽에 세운다.
		int clearLeft = fullLeft;
		for (List<Rect> group : List.of(above, below)) {
			for (Rect rect : group) {
				if (rect.right() + GAP > fullLeft && rect.overlapsRows(top0, bottomBase)) {
					clearLeft = Math.max(clearLeft, rect.right() + GAP);
				}
			}
		}
		int narrowWidth = w - MARGIN - clearLeft;
		if (narrowWidth >= PANEL_MIN_WIDTH && narrowWidth < fullWidth) {
			int rowsB = rowsFitting(bottomBase - top0, in.rowCount());
			if (rowsB > rowsA) {
				return new PanelBox(clearLeft, top0, narrowWidth, rowsB);
			}
		}
		return rowsA > 0 ? new PanelBox(fullLeft, topA, fullWidth, rowsA) : null;
	}

	/** 가운데 위 덩어리 — 보스바(이름 포함)와 시전 바. 둘 다 없으면 {@code null}. */
	static @Nullable Rect centerColumn(Input in, int bosses, int bossBottom,
			@Nullable CastBox cast) {
		int w = in.width();
		int left = Integer.MAX_VALUE;
		int right = Integer.MIN_VALUE;
		int bottom = 0;
		if (bosses > 0) {
			int half = BOSS_BAR_HALF_WIDTH;
			for (int i = 0; i < bosses; i++) {
				// 이름은 가운데 정렬이다(바닐라: w/2 - 이름폭/2). 홀수 폭이면 오른쪽이 한 칸 더 나간다.
				half = Math.max(half, (in.bossNameWidths().get(i) + 1) / 2);
			}
			left = Math.min(left, w / 2 - half);
			right = Math.max(right, w / 2 + half);
			bottom = bossBottom;
		}
		if (cast != null) {
			left = Math.min(left, cast.x());
			right = Math.max(right, cast.x() + cast.width());
			bottom = Math.max(bottom, cast.bottom());
		}
		if (left == Integer.MAX_VALUE) {
			return null;
		}
		return new Rect(left, 0, right, bottom);
	}
}
