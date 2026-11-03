package com.sharedfate.client.hud;

import com.sharedfate.client.ClientPreferences;
import com.sharedfate.client.ClientTeamState;
import com.sharedfate.net.PerkProcPayload;
import com.sharedfate.ui.BottomCenterLayout;
import com.sharedfate.ui.PerkProcFeed;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * 조건부 증강이 발동한 순간, 경험치 바 위 가운데에 「⟡ 급소만 노려」를 잠깐 띄운다.
 *
 * <p>무엇을 언제까지 띄울지(1.5초, 같은 증강 3초에 한 번, 최대 3줄)는 {@link PerkProcFeed} 가,
 * 어디에 띄울지(바닐라 액션바와 좌하단 팀 레벨 글줄 위)는 {@link BottomCenterLayout} 이 정한다.
 * 여기서는 그 둘을 받아 그리고 소리를 낼 뿐이다.
 *
 * <h2>작게</h2>
 * <p>하루 종일 보는 화면이다. 글자는 바닐라 기본 크기 그대로(더 줄이면 한글이 뭉개진다), 반투명
 * 어두운 바탕에 등급색 1픽셀 테두리만 두른다. 소리는 음표 블록 차임을 볼륨 {@value #VOLUME} 로 한 번.
 *
 * <p>글자와 소리는 팀 화면 「설정」 탭에서 따로 끌 수 있다({@code config/sharedfate-client.json}).
 */
public class PerkProcHud implements HudElement {
	/** 소리 크기. 경험치 구슬보다도 작게 둔다. */
	private static final float VOLUME = 0.25F;
	/** 바탕의 가장 진한 불투명도(글자 불투명도에 곱한다). */
	private static final float BACKDROP_ALPHA = 0.55F;
	/** 글자색. 테두리가 등급색이므로 글자는 흰빛에 가깝게 둔다. */
	private static final int TEXT_RGB = 0xF2F2F2;

	private static final PerkProcFeed FEED = new PerkProcFeed();

	/**
	 * 서버가 보낸 알림을 받는다. 클라이언트 본 스레드에서 부른다.
	 *
	 * <p>글자·소리를 띄울지는 이 컴퓨터의 설정({@link ClientPreferences})이 정한다. 서버는 끈 사람에게도
	 * 그대로 보낸다.
	 */
	public static void show(Minecraft client, PerkProcPayload payload) {
		PerkProcFeed.Offer offer = FEED.offer(payload.key(), payload.name(), payload.rarity(),
				Util.getMillis(), ClientPreferences.procAlerts(), ClientPreferences.procSound());
		if (offer.playSound()) {
			client.getSoundManager().play(SimpleSoundInstance.forUI(
					SoundEvents.NOTE_BLOCK_CHIME.value(), pitchFor(payload.rarity()), VOLUME));
		}
	}

	/** 등급이 높을수록 조금 높은 소리. 차이는 귀로만 알 만큼 작다. */
	private static float pitchFor(String rarity) {
		return switch (rarity) {
			case "gold" -> 1.6F;
			case "prism", "trial" -> 1.8F;
			default -> 1.4F;
		};
	}

	/** 접속을 끊으면 비운다. */
	public static void clear() {
		FEED.clear();
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		// 글자를 막 끈 순간 떠 있던 줄도 곧바로 감춘다. 남은 줄은 1.5초 안에 스스로 사라진다.
		if (FEED.isEmpty() || !ClientPreferences.procAlerts()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		Player player = client.player;
		if (player == null) {
			return;
		}
		// F1 로 HUD 를 껐거나 F3 디버그 화면이 켜져 있으면 그리지 않는다.
		if (client.gui.hud.isHidden() || DebugOverlay.coversScreen(client)) {
			return;
		}
		List<PerkProcFeed.Frame> frames = FEED.frames(Util.getMillis());
		if (frames.isEmpty()) {
			return;
		}

		Font font = client.font;
		int center = graphics.guiWidth() / 2;
		int bottom = BottomCenterLayout.procBottom(graphics.guiHeight(),
				BottomLeftStack.vanillaBarsHeight(player), PerkProgressHud.visible(),
				ClientTeamState.inTeam() ? BottomLeftStack.teamLevelLines() : 0);
		for (int index = 0; index < frames.size(); index++) {
			PerkProcFeed.Frame frame = frames.get(index);
			if (frame.alpha() <= 0.02F) {
				continue;
			}
			String text = frame.line().text();
			int width = BottomCenterLayout.procLineWidth(font.width(text));
			int left = center - width / 2;
			int top = BottomCenterLayout.procLineTop(bottom, index, frame.rise());
			int boxBottom = top + BottomCenterLayout.PROC_LINE_HEIGHT;
			float a = frame.alpha();

			graphics.fill(left, top, left + width, boxBottom, alpha(0x000000, BACKDROP_ALPHA * a));
			graphics.outline(left, top, width, BottomCenterLayout.PROC_LINE_HEIGHT,
					alpha(frame.line().accent(), a));
			int textLeft = left + BottomCenterLayout.PROC_PADDING_X;
			int markWidth = font.width(PerkProcFeed.MARK);
			graphics.text(font, PerkProcFeed.MARK, textLeft, top + 2, alpha(frame.line().accent(), a), false);
			graphics.text(font, text.substring(PerkProcFeed.MARK.length()), textLeft + markWidth, top + 2,
					alpha(TEXT_RGB, a), false);
		}
	}

	/** {@code rgb} 에 0~1 불투명도를 얹는다. 글자는 불투명도가 너무 낮으면 바닐라가 안 그리므로 바닥을 둔다. */
	private static int alpha(int rgb, float alpha) {
		int a = Math.max(8, Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F));
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
