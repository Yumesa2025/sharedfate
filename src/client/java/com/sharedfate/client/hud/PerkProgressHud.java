package com.sharedfate.client.hud;

import com.sharedfate.client.ClientTeamState;
import com.sharedfate.perk.PerkMilestones;
import com.sharedfate.ui.DragonPrepStyle;
import com.sharedfate.ui.PerkGauge;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * 화면 아래 가운데, 핫바 위 바닐라 표시들 <b>위</b>에 다음 증강 구간까지의 진행도를 막대로
 * 그린다.
 *
 * <p>증강은 정해진 레벨 구간에 <b>처음</b> 닿을 때 나온다. 폭과 두께는 바닐라의
 * {@code ContextualBar}(폭 182, 높이 5)에서 그대로 따왔다.
 *
 * <h2>높이는 상수가 아니다</h2>
 * <p>이 게이지는 폭이 핫바와 같아 <b>왼쪽 하트·방어구와 오른쪽 배고픔·공기 방울을 모두
 * 가로지른다.</b> 레벨 숫자에 딱 붙여 두면 하트 윗줄과 3픽셀 겹치는데, 이 모드는 최대 체력이
 * 증강으로 늘어 하트가 두 줄·세 줄이 되므로 겹치는 정도가 상황마다 달라진다. 그래서 자리를
 * 상수로 박지 않고, 바닐라가 실제로 쓰는 높이를 {@link com.sharedfate.ui.BottomBarMetrics}
 * 로 다시 계산해 그 위에 올린다.
 *
 * <p>여기서는 <b>얼마나 찼는지</b>만 그린다. 정확히 몇 레벨 남았는지는 {@link TeamLevelHud} 가
 * 좌하단에 「다음 증강까지 N」으로 적는다.
 *
 * <h2>구간 수가 바뀌어도 따라간다</h2>
 * <p>한 칸의 길이는 {@link PerkMilestones#STEP} 을 그대로 쓰고, "더 받을 증강이 있는가"는
 * 서버가 {@code TeamSyncPayload.nextPerkLevel} 로 이미 알려 준다(만렙을 넘겼으면 0). 그래서
 * 구간 만렙이 바뀌어도 이 파일은 고칠 것이 없고, <b>통신 규약도 그대로다.</b>
 */
public class PerkProgressHud implements HudElement {
	/** 게이지 폭. 바닐라 경험치 바({@code ContextualBar.WIDTH})와 같다. */
	private static final int WIDTH = 182;
	/** 게이지 높이. 바닐라 경험치 바({@code ContextualBar.HEIGHT})와 같다. */
	private static final int HEIGHT = 5;
	/**
	 * 게이지 아랫변과 바닐라 표시 윗변 사이의 거리.
	 *
	 * <p>테두리가 게이지 밖으로 1픽셀 나가므로, 눈에 보이는 여백은 이 값에서 1을 뺀 만큼이다.
	 */
	private static final int GAP_TO_BARS = 3;
	/** 게이지 위로 남겨 둘 여백. 좌하단 글줄이 이 위에서 시작한다. */
	private static final int MARGIN_TOP = 3;

	/** 아직 안 찬 부분. 바닐라 바의 어두운 바탕과 비슷한 무게로 둔다. */
	private static final int TRACK_COLOR = 0xA0202028;
	/** 찬 부분. {@link TeamLevelHud} 의 「다음 증강까지」와 같은 색이라 둘이 한 쌍으로 읽힌다. */
	private static final int FILL_COLOR = 0xFFFFD24A;
	/** 밝은 배경 위에서도 막대가 뭉개지지 않도록 두르는 테두리. */
	private static final int BORDER_COLOR = 0xC0000000;

	/**
	 * 지금 이 게이지를 그리는가.
	 *
	 * <p>팀이 없거나, 증강을 안 쓰는 팀이거나, 구간 만렙을 넘겨 더 받을 증강이 없으면 그리지
	 * 않는다. 세 경우 모두 {@code levelsToNextPerk()} 가 -1 이다.
	 */
	public static boolean visible() {
		return ClientTeamState.inTeam() && ClientTeamState.levelsToNextPerk() >= 0;
	}

	/**
	 * 게이지가 차지하는 자리의 윗변. 좌하단 글줄은 이 위에서 시작해야 겹치지 않는다.
	 *
	 * <p>안 그리는 상황이면 바닐라 표시 윗변을 그대로 돌려주어 자리를 차지하지 않는다.
	 *
	 * @param barsHeight 핫바 위 바닐라 표시가 차지하는 높이.
	 *                   {@link BottomLeftStack#vanillaBarsHeight} 가 재 준 값
	 */
	public static int clearanceTop(int guiHeight, int barsHeight) {
		return visible() ? barTop(guiHeight, barsHeight) - MARGIN_TOP : guiHeight - barsHeight;
	}

	private static int barTop(int guiHeight, int barsHeight) {
		return guiHeight - barsHeight - GAP_TO_BARS - HEIGHT;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!visible()) {
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

		int left = (graphics.guiWidth() - WIDTH) / 2;
		int top = barTop(graphics.guiHeight(), BottomLeftStack.vanillaBarsHeight(player));
		int right = left + WIDTH;
		int bottom = top + HEIGHT;

		graphics.fill(left - 1, top - 1, right + 1, bottom + 1, BORDER_COLOR);
		graphics.fill(left, top, right, bottom, TRACK_COLOR);
		int filled = Math.round(WIDTH
				* PerkGauge.fraction(ClientTeamState.levelsToNextPerk(), PerkMilestones.STEP));
		if (filled > 0) {
			// 41~50 구간(다음이 45·50)이면 「드래곤전 대비」의 엔더 연보라. 40 이하는 그대로 노랑이다.
			graphics.fill(left, top, left + filled, bottom,
					ClientTeamState.nextPerkIsPrep() ? DragonPrepStyle.COLOR : FILL_COLOR);
		}
	}

}
