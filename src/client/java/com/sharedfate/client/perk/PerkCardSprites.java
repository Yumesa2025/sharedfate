package com.sharedfate.client.perk;

import com.sharedfate.ui.PerkRarityStyle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * 증강 카드 그림을 그리는 손 — 선택창({@link PerkOfferScreen})과 「드래곤전 대비」 투표창
 * ({@link DragonPrepVoteScreen})이 같은 순서 · 같은 밝기로 그리게 한 곳에 모은다.
 *
 * <p>그리는 차례는 <b>빛 → 카드 틀 → 등급 띠 → 문양</b>이고 글자 · 아이콘은 부르는 쪽이 그 위에 얹는다.
 * 그림 이름과 등급색은 {@link PerkRarityStyle} 이 쥔다.
 *
 * <p>모두 {@code blitSprite} 라 부르는 쪽이 쌓아 둔 pose(호버로 뜨기 · 뒤집기 · 미끄러지기)를 그대로 따른다.
 */
final class PerkCardSprites {
	/**
	 * 평상시 카드 틀과 띠에 곱하는 색. 호버 · 강조 카드만 그림 원래 밝기(흰색)로 그린다.
	 *
	 * <p>그림을 원래보다 밝게 그릴 길은 없어서(색은 곱해지기만 한다) 평상시를 조금 눌러 두고 밝은 쪽을
	 * 원래 그림으로 쓴다. 띠 가운데가 밝아 어두운 띠 글자는 눌러도 잘 읽힌다.
	 */
	static final int TINT_IDLE = 0xFFD4D4D4;
	static final int TINT_BRIGHT = 0xFFFFFFFF;
	/** 등급 문양이 띠 왼쪽 끝에서 떨어진 거리. 띠 끝의 마개(4픽셀) 위에 살짝 얹힌다. */
	static final int EMBLEM_INSET = 3;
	/** 문양과 등급 글자 사이에 반드시 남길 틈. */
	static final int EMBLEM_TEXT_GAP = 2;

	private PerkCardSprites() {
	}

	/**
	 * 카드 둘레의 빛. 흰 빛 그림을 등급색으로 물들여 카드보다 사방 {@link PerkRarityStyle#GLOW_SPREAD}
	 * 만큼 크게 그린다. 빛 그림의 안쪽 4픽셀은 카드 틀 밑에 깔려 안 보인다.
	 *
	 * @param strength 0~1. 숨 쉬는 세기를 투명도로 얹는다
	 */
	static void glow(GuiGraphicsExtractor graphics, int left, int top, int width, int height,
			int rgb, float strength) {
		int alpha = Math.round(0xFF * Math.clamp(strength, 0.0F, 1.0F));
		if (alpha <= 0) {
			return;
		}
		int spread = PerkRarityStyle.GLOW_SPREAD;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PerkRarityStyle.CARD_GLOW,
				left - spread, top - spread, width + spread * 2, height + spread * 2,
				alpha << 24 | rgb & 0x00FFFFFF);
	}

	/** 카드 틀과 맨 위 등급 띠. 띠 높이는 그림 원래 높이({@link PerkRarityStyle#BAND_HEIGHT})다. */
	static void frame(GuiGraphicsExtractor graphics, PerkRarityStyle style, int left, int top,
			int width, int height, boolean bright) {
		int tint = bright ? TINT_BRIGHT : TINT_IDLE;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, style.card(), left, top, width, height,
				tint);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, style.band(), left, top, width,
				PerkRarityStyle.BAND_HEIGHT, tint);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, style.emblem(), left + EMBLEM_INSET, top,
				PerkRarityStyle.EMBLEM_SIZE, PerkRarityStyle.EMBLEM_SIZE);
	}

	/**
	 * 띠 글자의 가운데 x.
	 *
	 * <p>카드 가운데에 두되, 글자가 왼쪽 문양에 닿을 만큼 넓으면 닿지 않는 데까지 오른쪽으로 민다.
	 */
	static int bandTextCenter(int left, int width, int textWidth) {
		int minCenter = left + EMBLEM_INSET + PerkRarityStyle.EMBLEM_SIZE + EMBLEM_TEXT_GAP
				+ (textWidth + 1) / 2;
		return Math.max(left + width / 2, minCenter);
	}

	/** 카드 바로 바깥 1픽셀 테두리. 호버 · 강조 · 내 표처럼 「이 카드」를 짚는 데 쓴다. */
	static void outline(GuiGraphicsExtractor graphics, int left, int top, int width, int height,
			int color) {
		graphics.outline(left - 1, top - 1, width + 2, height + 2, color);
	}

	/**
	 * 카드 위에 덮는 어둠. 틀 그림의 네 모서리 1픽셀은 비어 있으므로 그 자리만 비워 덮는다 — 통째로
	 * 덮으면 둥근 모서리에 네모난 점이 남는다.
	 */
	static void shade(GuiGraphicsExtractor graphics, int left, int top, int width, int height,
			int color) {
		if ((color >>> 24) == 0 || width < 2 || height < 2) {
			return;
		}
		int right = left + width;
		int bottom = top + height;
		graphics.fill(left + 1, top, right - 1, bottom, color);
		graphics.fill(left, top + 1, left + 1, bottom - 1, color);
		graphics.fill(right - 1, top + 1, right, bottom - 1, color);
	}
}
