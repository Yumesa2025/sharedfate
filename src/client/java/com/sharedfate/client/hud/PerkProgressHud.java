package com.sharedfate.client.hud;

import com.sharedfate.client.ClientTeamState;
import com.sharedfate.perk.PerkMilestones;
import com.sharedfate.ui.BottomCenterLayout;
import com.sharedfate.ui.DragonPrepStyle;
import com.sharedfate.ui.PerkGauge;
import com.sharedfate.ui.PerkGaugeMotion;
import com.sharedfate.ui.PerkRarityStyle;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

/**
 * 화면 아래 가운데, 핫바 위 바닐라 표시들 <b>위</b>에 다음 증강 구간까지의 진행도를 막대로
 * 그린다.
 *
 * <p>증강은 정해진 레벨 구간에 <b>처음</b> 닿을 때 나온다. 폭과 두께는 바닐라의
 * {@code ContextualBar}(폭 182, 높이 5)에서 그대로 따왔다.
 *
 * <h2>모양</h2>
 * <p>바탕과 찬 부분은 그림이다({@link PerkRarityStyle#GAUGE_GOLD_BG} 등, 182×5 — 검은 테두리 ·
 * 윗면 광택이 그림 안에 있다). 찬 부분 그림은 <b>왼쪽에서부터 찬 폭만큼만 잘라</b> 그린다. 그 위에
 * 사각형으로 <b>테두리 빛</b>(1픽셀, 진행 끝과 번쩍일 때만) · <b>레벨 눈금</b> · <b>빛나는 진행 끝</b> ·
 * <b>번쩍임</b>을 얹는다. 색은 두 벌 — 평소의 금색, 41~50 구간(다음이 45·50)의 엔더 연보라
 * ({@link DragonPrepStyle#COLOR}).
 *
 * <p>⚠ 바탕 그림에도 옅은 눈금이 20픽셀마다(열 칸) 그려져 있다. 한 칸은 {@link PerkMilestones#STEP}
 * 레벨이라 그 눈금은 레벨과 안 맞는다 — 레벨 눈금은 여전히 코드가 따로 긋는다.
 *
 * <p>움직임(부드럽게 차오르기·짧은 반짝임·구간에 닿은 번쩍임)은 {@link PerkGaugeMotion} 이 정한다.
 * 레벨 안의 경험치까지 세어({@link PerkGauge#fraction(int, int, float)}) 구슬 하나에도 조금씩 찬다.
 *
 * <h2>높이는 상수가 아니다</h2>
 * <p>이 게이지는 폭이 핫바와 같아 <b>왼쪽 하트·방어구와 오른쪽 배고픔·공기 방울을 모두
 * 가로지른다.</b> 이 모드는 최대 체력이 증강으로 늘어 하트가 두 줄·세 줄이 되므로 자리를
 * 상수로 박지 않고, 바닐라가 실제로 쓰는 높이를 {@link com.sharedfate.ui.BottomBarMetrics}
 * 로 다시 계산해 그 위에 올린다. 자리 셈은 {@link BottomCenterLayout} 에 있다.
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
	private static final int WIDTH = BottomCenterLayout.GAUGE_WIDTH;
	private static final int HEIGHT = BottomCenterLayout.GAUGE_HEIGHT;

	/** 눈금 — 안 찬 쪽은 옅은 빛, 찬 쪽은 옅은 그림자. */
	private static final int TICK_ON_TRACK = 0x38FFFFFF;
	private static final int TICK_ON_FILL = 0x48000000;

	/** 그림과 색 한 벌. */
	private record Theme(Identifier background, Identifier fill, int edge, int glow) {
	}

	/** 평소 — 금색. {@link TeamLevelHud} 의 「다음 증강까지」(0xFFD24A)와 한 쌍으로 읽힌다. */
	private static final Theme GOLD = new Theme(PerkRarityStyle.GAUGE_GOLD_BG,
			PerkRarityStyle.GAUGE_GOLD_FILL, 0xFFFFF7D6, 0xFFD24A);
	/** 드래곤전 대비 — 엔더 연보라. */
	private static final Theme PREP = new Theme(PerkRarityStyle.GAUGE_TRIAL_BG,
			PerkRarityStyle.GAUGE_TRIAL_FILL, 0xFFF7F0FF, DragonPrepStyle.COLOR & 0xFFFFFF);

	private static final PerkGaugeMotion MOTION = new PerkGaugeMotion();

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
		return BottomCenterLayout.clearanceTop(guiHeight, barsHeight, visible());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		if (!visible()) {
			MOTION.reset();
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

		long now = Util.getMillis();
		int remaining = ClientTeamState.levelsToNextPerk();
		int teamLevel = ClientTeamState.teamLevel();
		// 팀 레벨은 서버가 팀원 모두에게 똑같이 써 준다. 어긋나 있으면(동기화 직전) 레벨 안의 몫을 세지
		// 않는다 — 남의 레벨의 몫을 얹으면 막대가 한 칸 앞서 간다.
		float progress = player.experienceLevel == teamLevel ? player.experienceProgress : 0.0F;
		MOTION.update(PerkGauge.fraction(remaining, PerkMilestones.STEP, progress),
				teamLevel + remaining, now);

		Theme theme = ClientTeamState.nextPerkIsPrep() ? PREP : GOLD;
		int left = BottomCenterLayout.gaugeLeft(graphics.guiWidth());
		int top = BottomCenterLayout.gaugeTop(graphics.guiHeight(), BottomLeftStack.vanillaBarsHeight(player));
		int right = left + WIDTH;
		int bottom = top + HEIGHT;
		int filled = Math.round(WIDTH * MOTION.shown());
		float sparkle = MOTION.sparkle(now);
		float flash = MOTION.flash(now);
		int glow = BottomCenterLayout.GAUGE_GLOW;

		// 1. 막대 밖의 빛. 이 뒤에 그리는 바탕 그림이 안쪽을 덮어 바깥 1픽셀만 남는다.
		if (flash > 0.0F) {
			graphics.fill(left - glow, top - glow, right + glow, bottom + glow,
					alpha(theme.glow(), 0.55F * flash));
		}
		boolean edgeVisible = filled > 0 && filled < WIDTH;
		if (edgeVisible) {
			int edgeX = left + filled;
			graphics.fill(edgeX - 3, top - glow, edgeX + 2, bottom + glow,
					alpha(theme.glow(), 0.22F + 0.5F * sparkle));
		}

		// 2. 바탕 그림. 검은 테두리가 그림 안에 있다.
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, theme.background(), left, top, WIDTH, HEIGHT);

		// 3. 찬 부분 그림을 왼쪽에서부터 찬 폭만큼만 잘라 얹는다. 늘이면 왼쪽 끝 테두리가 뭉개진다.
		if (filled > 0) {
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, theme.fill(), WIDTH, HEIGHT, 0, 0,
					left, top, filled, HEIGHT);
		}

		// 4. 레벨 눈금. 한 칸(STEP 레벨)을 레벨마다 나눈다. 양 끝은 테두리가 맡는다.
		for (int i = 1; i < PerkMilestones.STEP; i++) {
			int x = left + Math.round(WIDTH * i / (float) PerkMilestones.STEP);
			graphics.fill(x, top + 1, x + 1, bottom - 1, x < left + filled ? TICK_ON_FILL : TICK_ON_TRACK);
		}

		// 5. 빛나는 진행 끝. 오를 때 잠깐 더 밝고 넓어진다.
		if (edgeVisible) {
			int edgeX = left + filled;
			graphics.fill(edgeX - 1, top, edgeX, bottom, theme.edge());
			if (sparkle > 0.0F) {
				graphics.fill(edgeX - 3, top, edgeX - 1, bottom, alpha(0xFFFFFF, 0.6F * sparkle));
			}
		}

		// 6. 구간에 닿은 순간 막대 전체가 번쩍인다.
		if (flash > 0.0F) {
			graphics.fill(left, top, right, bottom, alpha(0xFFFFFF, 0.7F * flash));
		}
	}

	/** {@code rgb} 에 0~1 불투명도를 얹는다. */
	private static int alpha(int rgb, float alpha) {
		int a = Math.round(Math.clamp(alpha, 0.0F, 1.0F) * 255.0F);
		return (a << 24) | (rgb & 0xFFFFFF);
	}
}
