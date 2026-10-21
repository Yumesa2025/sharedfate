package com.sharedfate.client.hud;

import com.sharedfate.client.ClientTeamState;
import com.sharedfate.ui.DragonPrepStyle;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * 화면 아래 왼쪽, 핫바 왼쪽 끝에 맞춰 팀 공유 레벨과 다음 증강까지 남은 레벨을 표시한다.
 *
 * <p>좌표 계산과 다른 표시와의 겹침 정리는 {@link BottomLeftStack} 이 맡는다.
 */
public class TeamLevelHud implements HudElement {
	/** 경험치 레벨 숫자와 같은 연두색. */
	private static final int LEVEL_COLOR = 0xFF80FF20;
	/** 남은 레벨은 한 단계 눈에 덜 띄는 색으로 둔다. */
	private static final int REMAINING_COLOR = 0xFFFFD24A;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!ClientTeamState.inTeam()) {
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

		Font font = client.font;
		int remaining = ClientTeamState.levelsToNextPerk();
		int left = BottomLeftStack.left(graphics);
		int baseline = BottomLeftStack.baseline(player, graphics.guiHeight());

		// 남은 증강이 없으면(35 구간을 넘겼거나 증강을 쓰지 않는 팀) 팀 레벨 한 줄만 그린다.
		if (remaining < 0) {
			graphics.text(font, "팀 레벨 " + ClientTeamState.teamLevel(), left, baseline, LEVEL_COLOR);
			return;
		}
		graphics.text(font, "팀 레벨 " + ClientTeamState.teamLevel(),
				left, baseline - BottomLeftStack.LINE_HEIGHT, LEVEL_COLOR);
		// 41~50 구간(다음이 45·50)이면 「드래곤전 대비 증강까지 N」을 엔더 연보라로. 40 이하는 그대로다.
		boolean prep = ClientTeamState.nextPerkIsPrep();
		graphics.text(font, DragonPrepStyle.remainingText(remaining, prep), left, baseline,
				prep ? DragonPrepStyle.COLOR : REMAINING_COLOR);
	}

}
