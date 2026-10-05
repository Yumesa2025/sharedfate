package com.sharedfate.client.hud;

import com.sharedfate.client.ClientTrialTimers;
import com.sharedfate.client.mixin.BossHealthOverlayAccessor;
import com.sharedfate.net.TrialTimersPayload;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Kind;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.ChatVisiblity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 드래곤 패턴 타이머 HUD — <b>오른쪽 위 「다가오는 패턴」 패널</b>과 <b>가운데 위 시전 바</b>.
 *
 * <h2>사람이 승인한 화면 (2026-10-05)</h2>
 *
 * <p>사람 말: <b>「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게 오른쪽
 * 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」</b> — 메인이 만든 예시를 보고 「이대로
 * 진행」.
 *
 * <pre>
 *                    [ 보스바들 ]                         [상태이상 아이콘]
 *          부채꼴 브레스              1.5초 뒤 발동     ┌ 다가오는 것   지금 체력 27% ┐
 *          [■■■■■■■■■■■■■■■■■■■■■□□□□□□□□]           │ ● 안전지대 → 24칸    8.2초 │
 *              머리 방향 90° · 한 대에 전멸 — 옆으로     │ ● 상시 번개 [임박]  3.1초 │
 *                                                     │ ● 오브젝트 파도   체력 25% │
 *                                                     └───────────────────────┘
 * </pre>
 *
 * <p>판단(차례 · 상태 · 글자 · 바 비율)은 {@link TrialTimerRows}, 자리는 {@link TrialTimersLayout}
 * 이 들고 있고 여기는 <b>그리기만</b> 한다.
 *
 * <h2>안 그리는 때</h2>
 *
 * <ul>
 *   <li><b>F1</b>(HUD 숨김) — 바닐라가 보스바 · 상태이상을 건너뛰는 그 구간에 붙어 있지만, 붙은
 *       자리에 기대지 않고 직접 한 번 더 묻는다</li>
 *   <li><b>F3 판이 화면을 덮고 있을 때</b> — 둘 다 접는다. F3 오른쪽 글줄이 오른쪽 위에서부터 깔려
 *       패널과 정확히 같은 자리를 먹는다. 가운데 시전 바는 F3 와 직접 겹치는 자리가 아니지만, 좁은
 *       화면(480 폭)에서 F3 왼쪽 글줄이 가운데까지 뻗어 시전 바 제목과 겹친다. 「겹쳐 놓으면 둘 다 못
 *       읽는다」는 이 저장소의 규칙({@link DebugOverlay} — 좌표 · 증강 게이지 · 팀 레벨이 같은 판단)을
 *       그대로 따른다. F3 는 사람이 일부러 여는 판이고 닫으면 곧바로 돌아온다. 판정은
 *       {@link DebugOverlay#coversScreen} — FPS 를 「항상 켜기」로 박아 둔 사람에게서 HUD 가 영영
 *       사라지지 않는 쪽이다</li>
 * </ul>
 */
public class TrialTimersHud implements HudElement {

	/** 패널 판 — 반투명 검정. 사람 화면의 {@code rgba(0,0,0,0.58)}. */
	private static final int PANEL_BACKGROUND = 0x94000000;
	/** 제목 · 이름 · 시간. */
	private static final int TEXT = 0xFFFFFFFF;
	/**
	 * 머리줄 오른쪽 작은 글 · 대처법 줄.
	 *
	 * <p>사람 화면은 이 둘을 「작은 글」로 그렸다. 바닐라 글꼴을 줄여 그리면 한글(유니폰트 글리프)이
	 * GUI 2배율 이하에서 뭉개져 못 읽으므로 <b>크기는 그대로 두고 회색으로 한 단계 가라앉혔다.</b>
	 */
	private static final int TEXT_DIM = 0xFFA8A8A8;
	/** 「임박」 줄 배경 — 판보다 한 단계 밝게. */
	private static final int IMMINENT_BACKGROUND = 0x26FFFFFF;
	/** 「예고 중」 줄 배경의 알파 — 종류 색 22%. */
	private static final int WARNING_BACKGROUND_ALPHA = 0x38;
	/** 줄 바의 트랙 — 흰색 16%. */
	private static final int ROW_TRACK = 0x29FFFFFF;
	/** 「임박」 꼬리표 — 흰 바탕 검은 글. */
	private static final int TAG_LIGHT = 0xFFFFFFFF;
	private static final int TAG_DARK_TEXT = 0xFF000000;
	/** 시전 바 트랙 — 검정. */
	private static final int CAST_TRACK = 0xC0000000;
	/** 시전 바 테두리 — 흰색 35%. */
	private static final int CAST_BORDER = 0x59FFFFFF;

	/** 색 점 한 변. */
	private static final int DOT_SIZE = 4;
	/** 색 점과 이름 사이. */
	private static final int DOT_GAP = 3;
	/** 줄 배경이 판 가장자리에서 들어오는 폭. */
	private static final int ROW_INSET = 2;
	/** 줄 윗변에서 글자 윗변까지. */
	private static final int ROW_TEXT_OFFSET = 2;
	/** 줄 윗변에서 바 윗변까지. */
	private static final int ROW_BAR_OFFSET = 12;
	/** 줄 바 높이. */
	private static final int ROW_BAR_HEIGHT = 2;
	/** 머리줄 제목과 오른쪽 글 사이에 최소한 둘 틈. */
	private static final int HEADER_NOTE_GAP = 6;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		TrialTimersPayload payload = ClientTrialTimers.current();
		if (payload == null) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		Font font = client.font;
		if (player == null || client.level == null || font == null) {
			return;
		}
		if (client.gui.hud.isHidden() || DebugOverlay.coversScreen(client)) {
			return;
		}

		int width = graphics.guiWidth();
		int height = graphics.guiHeight();
		int elapsed = ClientTrialTimers.elapsed();
		boolean counting = ClientTrialTimers.counting(payload.frozen(), client.isPaused());
		float partial = deltaTracker.getGameTimeDeltaPartialTick(true);

		List<TrialTimerRows.Row> rows = TrialTimerRows.rows(payload.timers(), elapsed);
		Optional<Cast> cast = payload.cast();

		int[] icons = effectIcons(client, player);
		TrialTimersLayout.Input input = new TrialTimersLayout.Input(width, height, icons[0], icons[1],
				client.isDemo(), bossNameWidths(client, font), cast.isPresent(),
				cast.map(c -> !c.hint().isEmpty()).orElse(false), rows.size(),
				CoordinateHud.occupied(), chatArea(client, height),
				PerkProgressHud.clearanceTop(height, BottomLeftStack.vanillaBarsHeight(player)),
				Boolean.TRUE.equals(client.options.showSubtitles().get()));
		TrialTimersLayout.Result layout = TrialTimersLayout.layout(input);

		if (layout.cast() != null && cast.isPresent()) {
			drawCast(graphics, font, layout.cast(), cast.get(), elapsed, partial, counting);
		}
		if (layout.panel() != null) {
			boolean lastStand = cast.isPresent();
			String note = TrialTimerRows.headerNote(lastStand, rows.size(),
					lastStand ? dragonHealth(client, player) : Float.NaN);
			drawPanel(graphics, font, layout.panel(), TrialTimerRows.header(lastStand), note, rows,
					partial, counting);
		}
	}

	// ------------------------------------------------------------------ 시전 바

	private static void drawCast(GuiGraphicsExtractor graphics, Font font,
			TrialTimersLayout.CastBox box, Cast cast, int elapsed, float partial, boolean counting) {
		float remaining = TrialTimerRows.interpolated(
				TrialTimerRows.remainingAfter(cast.remainingTicks(), elapsed), partial, counting);
		int color = opaque(Kind.color(cast.kind()));

		String time = TrialTimerRows.castTimeText(cast.state(), remaining);
		int timeWidth = font.width(time);
		String title = TrialTimerRows.fit(TrialTimerRows.castTitle(cast),
				box.width() - timeWidth - TrialTimerRows.RIGHT_GAP, font::width);
		graphics.text(font, title, box.x(), box.y(), TEXT);
		graphics.text(font, time, box.x() + box.width() - timeWidth, box.y(), TEXT);

		// 검은 트랙 → 종류 색 채움 → 흰 35% 테두리. 테두리를 마지막에 그려 채움 끝이 테두리를 덮지 않게.
		int barTop = box.barTop();
		int left = box.x();
		int right = box.x() + box.width();
		graphics.fill(left, barTop, right, barTop + TrialTimersLayout.CAST_BAR_HEIGHT, CAST_TRACK);
		int inner = box.width() - 2;
		int filled = Math.round(TrialTimerRows.castRatio(cast.state(), remaining, cast.totalTicks())
				* inner);
		if (filled > 0) {
			graphics.fill(left + 1, barTop + 1, left + 1 + filled,
					barTop + TrialTimersLayout.CAST_BAR_HEIGHT - 1, color);
		}
		graphics.outline(left, barTop, box.width(), TrialTimersLayout.CAST_BAR_HEIGHT, CAST_BORDER);

		if (box.hint()) {
			String hint = TrialTimerRows.fit(cast.hint(), box.width(), font::width);
			int hintX = box.x() + (box.width() - font.width(hint)) / 2;
			graphics.text(font, hint, hintX, box.hintTop(), TEXT_DIM);
		}
	}

	// ------------------------------------------------------------------ 패널

	private static void drawPanel(GuiGraphicsExtractor graphics, Font font,
			TrialTimersLayout.PanelBox box, String header, String note,
			List<TrialTimerRows.Row> rows, float partial, boolean counting) {
		int left = box.x();
		int right = box.x() + box.width();
		graphics.fill(left, box.y(), right, box.bottom(), PANEL_BACKGROUND);

		int inner = box.width() - TrialTimersLayout.PAD * 2;
		int headerY = box.y() + TrialTimersLayout.PAD;
		int noteWidth = note.isEmpty() ? 0 : font.width(note);
		// 제목이 먼저다. 둘이 다 안 들어가는 좁은 패널에서는 오른쪽 글을 뺀다.
		boolean showNote = noteWidth > 0
				&& font.width(header) + HEADER_NOTE_GAP + noteWidth <= inner;
		graphics.text(font, TrialTimerRows.fit(header, inner, font::width),
				left + TrialTimersLayout.PAD, headerY, TEXT);
		if (showNote) {
			graphics.text(font, note, right - TrialTimersLayout.PAD - noteWidth, headerY, TEXT_DIM);
		}

		// 화면 높이를 넘으면 위에서부터 들어가는 만큼만 — 위가 곧 먼저 오는 것이다.
		for (int index = 0; index < box.rows() && index < rows.size(); index++) {
			drawRow(graphics, font, box, box.rowTop(index), rows.get(index), partial, counting);
		}
	}

	private static void drawRow(GuiGraphicsExtractor graphics, Font font,
			TrialTimersLayout.PanelBox box, int top, TrialTimerRows.Row row, float partial,
			boolean counting) {
		int panelLeft = box.x();
		int panelRight = box.x() + box.width();
		int color = opaque(Kind.color(row.entry().kind()));
		TrialTimerRows.State state = row.state();

		int rowLeft = panelLeft + ROW_INSET;
		int rowRight = panelRight - ROW_INSET;
		int rowBottom = top + TrialTimersLayout.ROW_HEIGHT;
		if (state == TrialTimerRows.State.IMMINENT) {
			graphics.fill(rowLeft, top, rowRight, rowBottom, IMMINENT_BACKGROUND);
		} else if (state == TrialTimerRows.State.WARNING) {
			graphics.fill(rowLeft, top, rowRight, rowBottom,
					(WARNING_BACKGROUND_ALPHA << 24) | (color & 0xFFFFFF));
			graphics.outline(rowLeft, top, rowRight - rowLeft, rowBottom - top, color);
		}

		int contentLeft = panelLeft + TrialTimersLayout.PAD;
		int contentRight = panelRight - TrialTimersLayout.PAD;
		int textY = top + ROW_TEXT_OFFSET;
		graphics.fill(contentLeft, textY + 2, contentLeft + DOT_SIZE, textY + 2 + DOT_SIZE, color);

		float remaining = TrialTimerRows.interpolated(row.remaining(), partial, counting);
		String rightText = TrialTimerRows.rightText(row, remaining);
		int labelX = contentLeft + DOT_SIZE + DOT_GAP;
		TrialTimerRows.RowText text = TrialTimerRows.rowText(row.entry().label(),
				TrialTimerRows.tagOf(state), rightText, contentRight - labelX, font::width);

		graphics.text(font, text.label(), labelX, textY, TEXT);
		graphics.text(font, rightText, contentRight - font.width(rightText), textY, TEXT);
		if (text.tag() != null) {
			int tagX = labelX + font.width(text.label()) + TrialTimerRows.TAG_GAP;
			drawTag(graphics, font, text.tag(), tagX, top, state, color);
		}

		int barTop = top + ROW_BAR_OFFSET;
		graphics.fill(contentLeft, barTop, contentRight, barTop + ROW_BAR_HEIGHT, ROW_TRACK);
		int filled = Math.round(TrialTimerRows.rowRatio(row, remaining)
				* (contentRight - contentLeft));
		if (filled > 0) {
			graphics.fill(contentLeft, barTop, contentLeft + filled, barTop + ROW_BAR_HEIGHT, color);
		}
	}

	/**
	 * 꼬리표. 「임박」은 흰 바탕 검은 글, 「예고 중」은 종류 색 바탕 검은 글(사람 화면), 「진행 중」은
	 * 종류 색 테두리와 글 — 셋이 한눈에 갈리도록 바탕 · 바탕 · 테두리로 나눴다.
	 */
	private static void drawTag(GuiGraphicsExtractor graphics, Font font, String tag, int x,
			int rowTop, TrialTimerRows.State state, int color) {
		int width = font.width(tag) + TrialTimerRows.TAG_PADDING * 2;
		int top = rowTop + 1;
		int height = font.lineHeight + 1;
		int textX = x + TrialTimerRows.TAG_PADDING;
		int textY = rowTop + ROW_TEXT_OFFSET;
		switch (state) {
			case IMMINENT -> {
				graphics.fill(x, top, x + width, top + height, TAG_LIGHT);
				graphics.text(font, tag, textX, textY, TAG_DARK_TEXT, false);
			}
			case WARNING -> {
				graphics.fill(x, top, x + width, top + height, color);
				graphics.text(font, tag, textX, textY, TAG_DARK_TEXT, false);
			}
			default -> {
				graphics.outline(x, top, width, height, color);
				graphics.text(font, tag, textX, textY, color);
			}
		}
	}

	// ------------------------------------------------------------------ 다른 표시들이 차지한 칸

	/**
	 * HUD 에 그려지는 상태이상 아이콘 수 — {@code [이로운, 해로운]}. 26.3 {@code Hud.extractEffects}
	 * 를 그대로 옮겼다. 효과가 없거나, 상태이상을 스스로 그리는 화면(인벤토리 등)이 떠 있으면 HUD 에는
	 * 아이콘이 안 그려진다. 아이콘을 숨긴 효과({@code showIcon} 거짓)는 자리를 안 먹는다.
	 */
	private static int[] effectIcons(Minecraft client, LocalPlayer player) {
		int[] counts = new int[2];
		Collection<MobEffectInstance> effects = player.getActiveEffects();
		if (effects.isEmpty()) {
			return counts;
		}
		Screen screen = client.gui.screen();
		if (screen != null && screen.showsActiveEffects()) {
			return counts;
		}
		for (MobEffectInstance effect : effects) {
			if (!effect.showIcon()) {
				continue;
			}
			if (effect.getEffect().value().isBeneficial()) {
				counts[0]++;
			} else {
				counts[1]++;
			}
		}
		return counts;
	}

	/** 지금 떠 있는 보스바 이름 폭들. 바닐라가 그리는 차례(맵을 도는 차례) 그대로다. */
	private static List<Integer> bossNameWidths(Minecraft client, Font font) {
		List<Integer> widths = new ArrayList<>();
		for (LerpingBossEvent event : ((BossHealthOverlayAccessor) client.gui.hud.getBossOverlay())
				.sharedfate$events().values()) {
			widths.add(font.width(event.getName()));
		}
		return widths;
	}

	/**
	 * 채팅이 쓸 수 있는 칸 — <b>메시지가 없어도</b> 늘 비워 둔다.
	 *
	 * <p>26.3 {@code ChatComponent} 의 셈이다. 배율 {@code s} 로 늘이고 4 만큼 오른쪽에서 시작해,
	 * 줄 배경이 {@code -4 ~ 폭 + 8} 이므로 화면에서 오른쪽 끝은 {@code s × (12 + ⌈폭 / s⌉)}. 바닥은
	 * {@code guiHeight - 40}, 높이는 열려 있지 않을 때의 설정(열려 있으면 열린 높이)에 배율을 곱한다.
	 *
	 * <p>메시지가 올 때마다 패널 줄이 사라졌다 돌아오면 전투 중에 줄이 깜빡인다. 그래서 채팅이 비어
	 * 있어도 그 칸을 비켜 선다. 채팅을 「숨김」으로 둔 사람에게는 칸이 없다.
	 */
	private static TrialTimersLayout.Rect chatArea(Minecraft client, int guiHeight) {
		if (client.options.chatVisibility().get() == ChatVisiblity.HIDDEN) {
			return null;
		}
		double scale = client.options.chatScale().get();
		if (!(scale > 0.0)) {
			return null;
		}
		int chatWidth = ChatComponent.getWidth(client.options.chatWidth().get());
		boolean focused = client.gui.hud.getChat().isChatFocused();
		int chatHeight = ChatComponent.getHeight(focused
				? client.options.chatHeightFocused().get()
				: client.options.chatHeightUnfocused().get());
		int right = (int) Math.ceil(scale * (12 + Math.ceil(chatWidth / scale)));
		int top = guiHeight - 40 - (int) Math.ceil(chatHeight * scale);
		return new TrialTimersLayout.Rect(0, top, right, guiHeight);
	}

	/**
	 * 가장 가까운 살아 있는 드래곤의 체력 비율. 없으면 {@code NaN}.
	 *
	 * <p>묶음에는 체력이 없다 — 드래곤 체력은 바닐라가 이미 클라이언트에 보내 주는 값이라 새로 실을
	 * 까닭이 없다. 보스바 진행도가 아니라 개체를 읽는 것은 보스바를 이름으로 골라야 해서다(회차
	 * 보스바도 함께 떠 있다).
	 */
	private static float dragonHealth(Minecraft client, LocalPlayer player) {
		EnderDragon nearest = null;
		double best = Double.MAX_VALUE;
		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof EnderDragon dragon && dragon.isAlive()) {
				double distance = dragon.distanceToSqr(player);
				if (distance < best) {
					best = distance;
					nearest = dragon;
				}
			}
		}
		if (nearest == null || !(nearest.getMaxHealth() > 0.0F)) {
			return Float.NaN;
		}
		return nearest.getHealth() / nearest.getMaxHealth();
	}

	/** 알파가 없는 RGB 를 불투명으로. */
	private static int opaque(int rgb) {
		return 0xFF000000 | (rgb & 0xFFFFFF);
	}
}
