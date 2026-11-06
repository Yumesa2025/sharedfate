package com.sharedfate.client.hud;

import com.sharedfate.net.DamageWardPayload;
import com.sharedfate.ui.DamageWardBadge;
import com.sharedfate.ui.PerkProcFeed;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

/**
 * 「호위」가 지금 한 번 막아 줄 수 있으면 하트 왼쪽에 작은 방패와 「호위」를 띄운다.
 *
 * <p>막는 순간 사라지고 쿨타임이 차면 다시 뜬다. 그 판단은 전부 서버가 해서 보낸다
 * ({@link DamageWardPayload}) — 여기서는 받은 값을 그리기만 한다. 서버가 아무것도 안 보내면(이 묶음을
 * 모르는 서버, 「호위」를 받지 않는 사람) 아무것도 안 뜬다.
 *
 * <p>자리는 {@link DamageWardBadge} 가 정한다. 모양은 발동 알림 줄({@link PerkProcHud})과 같은 말씨다 —
 * 반투명 어두운 바탕에 등급색 1픽셀 테두리, 글자는 흰빛.
 *
 * <p>값을 쓰는 자리(패킷 수신)를 {@code client.execute} 로 본 스레드에 올려 두었으므로 그리는 자리와
 * 같은 스레드다.
 */
public class DamageWardHud implements HudElement {
	/** 바탕의 가장 진한 불투명도. 발동 알림 줄과 같다. */
	private static final float BACKDROP_ALPHA = 0.55F;
	/** 테두리 불투명도. 하루 종일 떠 있는 표라 알림 줄보다 한 단계 옅다. */
	private static final float OUTLINE_ALPHA = 0.8F;
	/** 글자색. */
	private static final int TEXT_RGB = 0xF2F2F2;
	/** 방패 가운데 세로줄을 어둡게 덮는 색. 그림이 한 덩어리 색으로 뭉개지지 않게 한다. */
	private static final int ICON_SHADE = 0x55000000;

	private static DamageWardPayload state = DamageWardPayload.HIDDEN;
	/** 표가 마지막으로 나타난 시각(밀리초). 흐릿함에서 또렷해지는 데 쓴다. */
	private static long shownAt;

	/** 서버가 보낸 값을 받는다. 클라이언트 본 스레드에서 부른다. */
	public static void update(DamageWardPayload payload) {
		DamageWardPayload next = payload == null ? DamageWardPayload.HIDDEN : payload;
		if (next.ready() && !state.ready()) {
			shownAt = Util.getMillis();
		}
		state = next;
	}

	/** 접속을 끊으면 비운다. 남겨 두면 다음 서버의 첫 화면에 남의 표가 뜬다. */
	public static void clear() {
		state = DamageWardPayload.HIDDEN;
		shownAt = 0L;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		DamageWardPayload now = state;
		if (!now.ready() || now.name().isEmpty()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null || client.gameMode == null) {
			return;
		}
		// 하트가 안 그려지는 때(창작·관전, F1, F3)에는 곁에 붙은 이 표도 안 그린다.
		if (!client.gameMode.canHurtPlayer() || client.gui.hud.isHidden()
				|| DebugOverlay.coversScreen(client)) {
			return;
		}

		Font font = client.font;
		float a = DamageWardBadge.fadeIn(Util.getMillis() - shownAt);
		int accent = PerkProcFeed.accentFor(now.rarity());
		int width = DamageWardBadge.width(font.width(now.name()));
		int left = DamageWardBadge.left(graphics.guiWidth(), width);
		int top = DamageWardBadge.top(graphics.guiHeight());

		graphics.fill(left, top, left + width, top + DamageWardBadge.HEIGHT,
				alpha(0x000000, BACKDROP_ALPHA * a));
		graphics.outline(left, top, width, DamageWardBadge.HEIGHT, alpha(accent, OUTLINE_ALPHA * a));

		int iconLeft = left + DamageWardBadge.PADDING_X;
		int iconTop = top + (DamageWardBadge.HEIGHT - DamageWardBadge.ICON_HEIGHT) / 2;
		for (int row = 0; row < DamageWardBadge.iconRows(); row++) {
			int x = iconLeft + DamageWardBadge.iconRowLeft(row);
			int y = iconTop + row;
			graphics.fill(x, y, x + DamageWardBadge.iconRowWidth(row), y + 1, alpha(accent, a));
		}
		// 가운데 세로줄 하나를 어둡게 — 방패의 등줄기.
		int spine = iconLeft + DamageWardBadge.ICON_WIDTH / 2;
		graphics.fill(spine, iconTop + 1, spine + 1, iconTop + DamageWardBadge.ICON_HEIGHT - 2,
				scaleAlpha(ICON_SHADE, a));

		int textLeft = iconLeft + DamageWardBadge.ICON_WIDTH + DamageWardBadge.ICON_GAP;
		graphics.text(font, now.name(), textLeft, top + 2, alpha(TEXT_RGB, a), false);
	}

	/** {@code rgb} 에 0~1 불투명도를 얹는다. 글자는 불투명도가 너무 낮으면 바닐라가 안 그리므로 바닥을 둔다. */
	private static int alpha(int rgb, float alpha) {
		int value = Math.max(8, Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F));
		return (value << 24) | (rgb & 0xFFFFFF);
	}

	/** 이미 알파가 든 색의 알파만 {@code alpha} 배로 줄인다. */
	private static int scaleAlpha(int argb, float alpha) {
		int base = (argb >>> 24) & 0xFF;
		int value = Math.round(base * Math.clamp(alpha, 0.0F, 1.0F));
		return (value << 24) | (argb & 0xFFFFFF);
	}
}
