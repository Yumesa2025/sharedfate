package com.sharedfate.client.hud;

import com.sharedfate.client.mixin.BossHealthOverlayAccessor;
import com.sharedfate.client.hud.TrialTimersLayout.CastBox;
import com.sharedfate.client.hud.TrialTimersLayout.Input;
import com.sharedfate.client.hud.TrialTimersLayout.PanelBox;
import com.sharedfate.client.hud.TrialTimersLayout.Rect;
import com.sharedfate.client.hud.TrialTimersLayout.Result;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>자리</b>.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」 — 패널은 상태이상 아이콘 바로
 * 아래, 시전 바는 보스바들 바로 아래.
 *
 * <h2>왜 이 시험이 있는가 — {@code docs/드래곤-트라이얼.md} 6장 「화면 크기를 가정하지 마십시오」</h2>
 *
 * <p>유적 감별사 좌표가 1080p 기본 배율(480×270)에서만 안 보였다. 클라이언트는 이 환경에서 안 뜨므로
 * ({@code runClient} 가 글꼴 로딩 중 죽는다) <b>화면을 셈으로 훑는다</b> — 흔한 화면 크기 · 보스바 수 ·
 * 아이콘 수 · 채팅 · 자막을 엮어 「화면 밖으로 안 나가고 아무것과도 안 겹친다」를 전부 확인한다.
 */
class TrialTimersLayoutTest {

	/** 채팅 기본값(폭 320 · 배율 1 · 안 열었을 때 높이 90)의 칸. {@code TrialTimersHud.chatArea} 와 같은 셈. */
	private static Rect defaultChat(int height) {
		return new Rect(0, height - 40 - 90, 332, height);
	}

	/** 하트 한 줄 + 공기 방울이 없는 흔한 핫바 위 높이. */
	private static int hotbarTop(int height) {
		return height - 49;
	}

	private static List<Integer> bosses(int count, int nameWidth) {
		return new ArrayList<>(Collections.nCopies(count, nameWidth));
	}

	private static Input input(int width, int height, int beneficial, int harmful, int bossCount,
			boolean cast, boolean hint, int rows, Rect leftHud, Rect chat, boolean subtitles) {
		return new Input(width, height, beneficial, harmful, false, bosses(bossCount, 60), cast, hint,
				rows, leftHud, chat, hotbarTop(height), subtitles);
	}

	// ------------------------------------------------------------------ 바닐라 셈

	@Test
	void 보스바는_화면_삼분의_일에서_멈춘다() {
		assertEquals(0, TrialTimersLayout.bossBarsDrawn(0, 240));
		assertEquals(1, TrialTimersLayout.bossBarsDrawn(1, 240));
		// 240/3 = 80. 막대 12 · 31 · 50 · 69 를 그리고 다음 자리 88 에서 멈춘다.
		assertEquals(4, TrialTimersLayout.bossBarsDrawn(9, 240));
		assertEquals(9, TrialTimersLayout.bossBarsDrawn(20, 540));
		assertEquals(1, TrialTimersLayout.bossBarsDrawn(5, 30), "하나는 언제나 그린다");
		assertEquals(17 + 19, TrialTimersLayout.bossBottom(2));
		assertEquals(0, TrialTimersLayout.bossBottom(0));
	}

	@Test
	void 상태이상_아이콘_아랫변() {
		assertEquals(0, TrialTimersLayout.effectsBottom(0, 0, false));
		assertEquals(25, TrialTimersLayout.effectsBottom(3, 0, false), "이로운 줄만 — 1 + 24");
		assertEquals(51, TrialTimersLayout.effectsBottom(0, 1, false), "해로운 줄은 1 + 26 + 24");
		assertEquals(51, TrialTimersLayout.effectsBottom(2, 2, false));
		assertEquals(66, TrialTimersLayout.effectsBottom(2, 2, true), "데모 판은 15 아래");
	}

	@Test
	void 보스바_접근자가_가리키는_칸이_보스바_자신에_선언돼_있다() throws Exception {
		// refmap 이 없어 접근자 이름이 틀려도 빌드는 지나간다(6장 「믹스인이 빌드를 통과해도 안 붙을 수
		// 있습니다」). 상위 클래스에 선언된 칸이면 접근자가 안 붙는다 — 그래서 선언한 클래스까지 본다.
		Method method = BossHealthOverlayAccessor.class.getDeclaredMethod("sharedfate$events");
		Accessor accessor = method.getAnnotation(Accessor.class);
		assertNotNull(accessor, "@Accessor 가 실행 시에도 보인다");
		Field field = BossHealthOverlay.class.getDeclaredField(accessor.value());
		assertEquals(Map.class, field.getType());
		assertFalse(Modifier.isStatic(field.getModifiers()));
		assertEquals(Object.class, BossHealthOverlay.class.getSuperclass());
	}

	// ------------------------------------------------------------------ 패널 — 상태이상 아이콘 바로 아래

	@Test
	void 패널은_아이콘이_없으면_위쪽_여백만_두고_있으면_그_아래에_붙는다() {
		// 넓은 화면(960×540) — 보스바와 안 걸린다.
		PanelBox none = layoutPanel(input(960, 540, 0, 0, 2, false, false, 3, null,
				defaultChat(540), false));
		assertEquals(TrialTimersLayout.MARGIN, none.y());
		assertEquals(960 - TrialTimersLayout.MARGIN, none.x() + none.width(), "오른쪽 끝에 붙는다");
		assertEquals(TrialTimersLayout.PANEL_WIDTH, none.width());

		assertEquals(28, layoutPanel(input(960, 540, 1, 0, 2, false, false, 3, null,
				defaultChat(540), false)).y(), "이로운 줄 25 + 틈 3");
		assertEquals(54, layoutPanel(input(960, 540, 1, 1, 2, false, false, 3, null,
				defaultChat(540), false)).y(), "해로운 줄 51 + 틈 3");
	}

	@Test
	void 기본_1080p_480x270_줄이_적으면_폭을_지키고_보스바_아래에_선다() {
		PanelBox panel = layoutPanel(input(480, 270, 0, 0, 2, false, false, 3, null,
				defaultChat(270), false));
		assertEquals(TrialTimersLayout.PANEL_WIDTH, panel.width());
		assertEquals(TrialTimersLayout.bossBottom(2) + TrialTimersLayout.GAP, panel.y(),
				"보스바 오른쪽 끝 331 > 패널 왼쪽 320 — 보스바 아래로");
		assertEquals(3, panel.rows());
		assertTrue(panel.bottom() <= 270 - 130, "채팅 위");
	}

	@Test
	void 기본_1080p_480x270_줄이_많으면_보스바와_채팅_오른쪽으로_좁힌다() {
		PanelBox panel = layoutPanel(input(480, 270, 0, 0, 2, false, false, 8, null,
				defaultChat(270), false));
		assertEquals(335, panel.x(), "채팅 오른쪽 332 + 틈 3");
		assertEquals(143, panel.width());
		assertEquals(8, panel.rows());
		assertEquals(TrialTimersLayout.MARGIN, panel.y());
	}

	@Test
	void 기본_1440p_426x240_최후의_저항도_세_줄을_다_보인다() {
		Result result = TrialTimersLayout.layout(input(426, 240, 1, 1, 2, true, true, 3, null,
				defaultChat(240), false));
		CastBox cast = result.cast();
		assertNotNull(cast);
		assertEquals(TrialTimersLayout.CAST_WIDTH, cast.width());
		assertTrue(cast.hint());
		assertEquals(TrialTimersLayout.bossBottom(2) + TrialTimersLayout.GAP + 1, cast.y(),
				"보스바들 바로 아래");
		PanelBox panel = result.panel();
		assertNotNull(panel);
		assertEquals(3, panel.rows(), "폭을 지키면 시전 바 아래 · 채팅 위에 한 줄뿐 — 좁혀서 셋");
		assertEquals(335, panel.x());
		assertEquals(89, panel.width());
		assertEquals(54, panel.y(), "아이콘 아래");
	}

	@Test
	void 줄이_화면을_넘으면_위에서부터_들어가는_만큼만() {
		PanelBox panel = layoutPanel(input(426, 240, 1, 1, 2, true, true, 16, null,
				defaultChat(240), false));
		assertTrue(panel.rows() < 16);
		assertTrue(panel.rows() >= 1);
		assertTrue(panel.bottom() <= 240 - TrialTimersLayout.ACTION_BAR_RESERVE,
				"액션바 위에서 멈춘다");
	}

	@Test
	void 자막을_켜면_아래를_다섯_줄_비운다() {
		PanelBox off = layoutPanel(input(960, 540, 0, 0, 1, false, false, 16, null, null, false));
		PanelBox on = layoutPanel(input(960, 540, 0, 0, 1, false, false, 16, null, null, true));
		assertTrue(on.bottom() <= 540 - TrialTimersLayout.SUBTITLE_RESERVE);
		assertTrue(on.rows() <= off.rows());
	}

	@Test
	void 줄이_없으면_패널을_안_그린다() {
		assertNull(TrialTimersLayout.layout(input(960, 540, 0, 0, 1, true, true, 0, null, null,
				false)).panel());
	}

	// ------------------------------------------------------------------ 시전 바 — 보스바들 바로 아래

	@Test
	void 시전_바는_보스바_수만큼_내려간다() {
		assertEquals(TrialTimersLayout.CAST_TOP_WITHOUT_BOSS, layoutCast(input(960, 540, 0, 0, 0,
				true, true, 1, null, null, false)).y());
		assertEquals(12 + 5 + 4, layoutCast(input(960, 540, 0, 0, 1, true, true, 1, null, null,
				false)).y());
		assertEquals(12 + 19 * 2 + 5 + 4, layoutCast(input(960, 540, 0, 0, 3, true, true, 1, null,
				null, false)).y());
		CastBox cast = layoutCast(input(960, 540, 0, 0, 1, true, true, 1, null, null, false));
		assertEquals((960 - 230) / 2, cast.x(), "가운데");
	}

	@Test
	void 시전_바는_왼쪽_위_표시를_비켜_좁히고_너무_좁으면_그_아래로() {
		CastBox narrowed = layoutCast(input(426, 240, 0, 0, 2, true, true, 1,
				new Rect(0, 0, 110, 100), null, false));
		assertEquals(200, narrowed.width(), "213 - (110 + 3) = 100, 양쪽 200");
		assertEquals(113, narrowed.x());

		CastBox below = layoutCast(input(426, 240, 0, 0, 2, true, true, 1,
				new Rect(0, 0, 200, 90), null, false));
		assertEquals(93, below.y());
		assertFalse(below.hint(), "조준점(112)까지 모자라 대처법을 뺀다");
		assertTrue(below.bottom() <= 240 / 2 - TrialTimersLayout.CROSSHAIR_CLEARANCE);

		assertNull(TrialTimersLayout.layout(input(426, 240, 0, 0, 2, true, true, 1,
				new Rect(0, 0, 200, 100), null, false)).cast(), "조준점을 가리느니 안 그린다");
	}

	@Test
	void 시전_바는_가운데까지_늘어선_아이콘을_비켜_내려간다() {
		// 426 폭에서 아이콘 다섯 개면 왼쪽 끝 301 — 시전 바 오른쪽 328 과 걸린다.
		CastBox cast = layoutCast(input(426, 240, 5, 0, 1, true, false, 1, null, null, false));
		assertEquals(25 + TrialTimersLayout.GAP, cast.y());
	}

	// ------------------------------------------------------------------ 훑기

	@Test
	void 어떤_화면에서도_화면_밖으로_안_나가고_아무것과도_안_겹친다() {
		int[] widths = {320, 340, 400, 426, 455, 480, 533, 640, 683, 960, 1280, 1920};
		int[] heights = {240, 256, 270, 300, 360, 540, 720, 1080};
		int[] bossCounts = {0, 1, 2, 3, 6};
		int[] nameWidths = {60, 182, 260};
		int[][] icons = {{0, 0}, {1, 0}, {0, 1}, {3, 2}, {9, 9}};
		int[] rowCounts = {1, 3, 6, 12, 16};
		Rect[] leftHuds = {null, new Rect(0, 0, 120, 90), new Rect(0, 0, 170, 130)};
		int checked = 0;
		int shownCommon = 0;
		for (int width : widths) {
			for (int height : heights) {
				for (int bossCount : bossCounts) {
					for (int[] icon : icons) {
						for (int castMode = 0; castMode < 3; castMode++) {
							for (int rows : rowCounts) {
								for (Rect leftHud : leftHuds) {
									for (int variant = 0; variant < 4; variant++) {
										boolean subtitles = (variant & 1) != 0;
										Rect chat = (variant & 2) != 0 ? null : defaultChat(height);
										boolean demo = variant == 3;
										Input in = new Input(width, height, icon[0], icon[1], demo,
												bosses(bossCount, nameWidths[(rows + bossCount) % 3]),
												castMode > 0, castMode == 2, rows, leftHud, chat,
												hotbarTop(height), subtitles);
										Result result = TrialTimersLayout.layout(in);
										checkNoOverlap(in, result);
										checked++;
									}
								}
							}
						}
					}
				}
			}
		}
		assertTrue(checked > 100_000, "훑은 경우: " + checked);
	}

	@Test
	void 흔한_화면에서는_패널이_반드시_선다() {
		int[][] screens = {{426, 240}, {455, 256}, {480, 270}, {533, 300}, {640, 360}, {960, 540}};
		for (int[] screen : screens) {
			for (int bossCount = 1; bossCount <= 2; bossCount++) {
				for (int castMode = 0; castMode < 3; castMode++) {
					Input in = input(screen[0], screen[1], 1, 1, bossCount, castMode > 0,
							castMode == 2, 3, new Rect(0, 0, 120, 90), defaultChat(screen[1]), false);
					Result result = TrialTimersLayout.layout(in);
					String where = screen[0] + "×" + screen[1] + " 보스바 " + bossCount + " 시전 " + castMode;
					assertNotNull(result.panel(), "패널이 안 선다: " + where);
					assertEquals(3, result.panel().rows(), "세 줄이 다 안 들어간다: " + where);
					if (castMode > 0) {
						assertNotNull(result.cast(), "시전 바가 안 선다: " + where);
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ 도구

	private static PanelBox layoutPanel(Input in) {
		Result result = TrialTimersLayout.layout(in);
		checkNoOverlap(in, result);
		assertNotNull(result.panel());
		return result.panel();
	}

	private static CastBox layoutCast(Input in) {
		Result result = TrialTimersLayout.layout(in);
		checkNoOverlap(in, result);
		assertNotNull(result.cast());
		return result.cast();
	}

	/** 바닐라가 실제로 그리는 칸들 — 시험 쪽에서 따로 센다. */
	private static List<Rect> occupied(Input in) {
		int w = in.width();
		int h = in.height();
		List<Rect> rects = new ArrayList<>();
		int iconTop = 1 + (in.demo() ? 15 : 0);
		if (in.beneficialIcons() > 0) {
			rects.add(new Rect(w - 25 * in.beneficialIcons(), iconTop, w, iconTop + 24));
		}
		if (in.harmfulIcons() > 0) {
			rects.add(new Rect(w - 25 * in.harmfulIcons(), iconTop + 26, w, iconTop + 50));
		}
		int drawn = TrialTimersLayout.bossBarsDrawn(in.bossNameWidths().size(), h);
		for (int i = 0; i < drawn; i++) {
			int barY = 12 + 19 * i;
			int name = in.bossNameWidths().get(i);
			rects.add(new Rect(w / 2 - 91, barY, w / 2 - 91 + 182, barY + 5));
			rects.add(new Rect(w / 2 - name / 2, barY - 9, w / 2 - name / 2 + name, barY - 1));
		}
		if (in.leftHud() != null) {
			rects.add(in.leftHud());
		}
		if (in.chat() != null) {
			rects.add(in.chat());
		}
		rects.add(new Rect(w / 2 - 91, in.hotbarTop(), w / 2 + 91, h));
		return rects;
	}

	private static void checkNoOverlap(Input in, Result result) {
		int w = in.width();
		int h = in.height();
		String where = in.toString();
		List<Rect> taken = occupied(in);
		CastBox cast = result.cast();
		if (cast != null) {
			Rect box = new Rect(cast.x(), cast.y(), cast.x() + cast.width(), cast.bottom());
			if (box.left() < 0 || box.right() > w || box.top() < 0) {
				fail("시전 바가 화면 밖: " + box + " " + where);
			}
			if (cast.bottom() > h / 2 - TrialTimersLayout.CROSSHAIR_CLEARANCE) {
				fail("시전 바가 조준점에 닿는다: " + box + " " + where);
			}
			for (Rect rect : taken) {
				if (overlaps(box, rect)) {
					fail("시전 바가 " + rect + " 와 겹친다: " + box + " " + where);
				}
			}
			assertTrue(!cast.hint() || in.castHint());
		}
		PanelBox panel = result.panel();
		if (panel != null) {
			Rect box = new Rect(panel.x(), panel.y(), panel.x() + panel.width(), panel.bottom());
			if (box.left() < 0 || box.right() > w || box.top() < 0) {
				fail("패널이 화면 밖: " + box + " " + where);
			}
			if (box.bottom() > h - TrialTimersLayout.ACTION_BAR_RESERVE) {
				fail("패널이 액션바에 닿는다: " + box + " " + where);
			}
			if (in.subtitles() && box.bottom() > h - TrialTimersLayout.SUBTITLE_RESERVE) {
				fail("패널이 자막 자리에 닿는다: " + box + " " + where);
			}
			if (panel.rows() < 1 || panel.rows() > in.rowCount()) {
				fail("줄 수가 이상하다: " + panel + " " + where);
			}
			if (panel.width() < TrialTimersLayout.PANEL_MIN_WIDTH
					&& panel.width() != Math.min(TrialTimersLayout.PANEL_WIDTH, w - 4)) {
				fail("패널이 너무 좁다: " + panel + " " + where);
			}
			for (Rect rect : taken) {
				if (overlaps(box, rect)) {
					fail("패널이 " + rect + " 와 겹친다: " + box + " " + where);
				}
			}
			if (cast != null && overlaps(box,
					new Rect(cast.x(), cast.y(), cast.x() + cast.width(), cast.bottom()))) {
				fail("패널이 시전 바와 겹친다: " + box + " " + where);
			}
		}
	}

	private static boolean overlaps(Rect a, Rect b) {
		return a.left() < b.right() && b.left() < a.right()
				&& a.top() < b.bottom() && b.top() < a.bottom();
	}
}
