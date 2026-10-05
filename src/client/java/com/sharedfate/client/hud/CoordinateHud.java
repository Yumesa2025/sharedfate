package com.sharedfate.client.hud;

import com.sharedfate.client.ClientSwapTimer;
import com.sharedfate.client.perk.ClientPerkSets;
import com.sharedfate.client.perk.PerkOfferScreen;
import com.sharedfate.ui.PerkSetLines;
import com.sharedfate.ui.StatRow;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

import java.util.List;

/**
 * 화면 <b>왼쪽 위</b>에 지금 서 있는 좌표와 한글 바이옴 이름을 두 줄로 늘 띄운다.
 *
 * <p>좌표도 바이옴도 <b>클라이언트가 이미 들고 있는 값</b>이다. 좌표는 자기 엔티티에 있고,
 * 바이옴은 서버가 청크와 함께 보내 준 것을 {@code ClientLevel} 이 그대로 갖고 있다. 이 표시는
 * <b>순수하게 클라이언트 전용</b>이라 서버와 통신하지 않는다 — 새 패킷을 만들면 통신 규약
 * 번호를 올려야 하고, 그러면 규약이 다른 클라이언트가 접속을 거부당한다.
 *
 * <p>이 자리에 무언가를 더 그리게 되면 {@link #NEXT_LINE_Y} 부터 아래로 쌓는다.
 *
 * <h2>세트 효과</h2>
 * <p>{@link #NEXT_LINE_Y} 아래에 구분선을 하나 긋고 세트를 쌓는다. 무엇을 몇 줄이나 어떤
 * 차례로 그릴지는 {@link com.sharedfate.ui.PerkSetLines} 가 정하고, 같은 계산을 팀 화면도 쓴다.
 *
 * <pre>
 * X 128  Y 64  Z -302
 * 어두운 숲
 * 위치 교환까지 3:07     ← 골드 「폭발 교환」을 가진 팀만
 * ────────────────
 * ◆ 채굴 3/3
 * ◇ 방어 1/2
 * </pre>
 *
 * <p>세트 줄은 <b>증강 선택 화면이 같은 것을 또렷하게 세우고 있을 때만</b> 접는다. 자세한
 * 이유는 {@link #setsShownByScreen} 에 적어 두었다.
 *
 * <h2>안 그리는 때</h2>
 * <p>F3 디버그 화면이 켜져 있으면 그리지 않는다. F3 이 왼쪽 위부터 글자를 깔기 때문에 그대로
 * 두면 두 글자가 겹쳐 둘 다 못 읽는다. F1 로 HUD 를 껐을 때와 관전 중일 때도 빠진다 —
 * 관전자는 자기 자리가 아니라 남을 따라다니는 중이라 그 좌표에 뜻이 없다.
 */
public class CoordinateHud implements HudElement {
	/** 화면 가장자리에서 띄우는 여백. */
	private static final int MARGIN = 4;
	/** 글줄 높이. 바닐라 기본 글꼴 기준이며 {@link BottomLeftStack} 이 쓰는 값과 같다. */
	private static final int LINE_HEIGHT = 10;
	/** 좌표 줄의 윗변. */
	private static final int POSITION_Y = MARGIN;
	/** 바이옴 줄의 윗변. */
	private static final int BIOME_Y = POSITION_Y + LINE_HEIGHT;
	/** 이 자리에 글줄을 더 쌓게 되면 여기서부터 아래로 내려간다. */
	public static final int NEXT_LINE_Y = BIOME_Y + LINE_HEIGHT;

	/** 좌표는 제일 자주 읽는 값이라 가장 밝게 둔다. */
	private static final int POSITION_COLOR = 0xFFFFFFFF;
	/** 바이옴은 한 단계 눈에 덜 띄는 회색. {@link GameOverHud} 의 안내 줄과 같은 색이다. */
	private static final int BIOME_COLOR = 0xFFCCCCCC;

	/** 바이옴 줄과 구분선 사이의 틈. */
	private static final int SEPARATOR_GAP = 3;
	/** 구분선 아래 첫 세트 줄까지의 틈. */
	private static final int SEPARATOR_BOTTOM_GAP = 3;
	/** 구분선이 아무리 짧아도 이만큼은 긋는다. */
	private static final int SEPARATOR_MIN_WIDTH = 60;
	/** 구분선 색. 좌표·바이옴보다 흐려야 <b>가르는 선</b>으로만 읽힌다. */
	private static final int SEPARATOR_COLOR = 0x50FFFFFF;
	/**
	 * 켜진 세트의 글자색. 능력치 줄의 「올랐다」와 같은 색이다({@link StatRow#COLOR_GOOD}).
	 */
	private static final int SET_ACTIVE_COLOR = StatRow.COLOR_GOOD;
	/** 아직 안 켜진 세트. 바이옴보다도 한 단계 더 가라앉혀 「지금은 아니다」를 색으로도 말한다. */
	private static final int SET_PROGRESS_COLOR = StatRow.COLOR_MASKED;
	/**
	 * 위치 교환 시계. 좌하단 「다음 증강까지」와 같은 노란색이다.
	 *
	 * <p>둘 다 <b>다음 사건까지 남은 것</b>을 세는 줄이라 같은 색으로 묶어 읽히게 한다.
	 */
	private static final int SWAP_TIMER_COLOR = 0xFFFFD24A;

	/**
	 * 좌표 한 줄을 만든다.
	 *
	 * <p><b>내림</b>이지 자름이 아니다. {@code (int)} 로 자르면 x 가 -0.5 일 때 0 이 나오는데,
	 * 실제로 서 있는 블록은 -1 이다. 바닐라 F3 도 내림을 쓴다.
	 *
	 * <p>소수점은 적지 않는다.
	 */
	public static String positionLine(double x, double y, double z) {
		return "X " + floor(x) + "  Y " + floor(y) + "  Z " + floor(z);
	}

	private static int floor(double value) {
		return (int) Math.floor(value);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft client = Minecraft.getInstance();
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		Font font = client.font;
		occupied = null;
		if (player == null || level == null || font == null) {
			return;
		}
		// F1 로 HUD 를 껐거나 F3 디버그 화면이 켜져 있으면 그리지 않는다. 관전 중에도 뺀다.
		if (client.gui.hud.isHidden() || DebugOverlay.coversScreen(client) || player.isSpectator()) {
			return;
		}

		String position = positionLine(player.getX(), player.getY(), player.getZ());
		graphics.text(font, position, MARGIN, POSITION_Y, POSITION_COLOR);
		int right = MARGIN + font.width(position);
		int bottom = POSITION_Y + LINE_HEIGHT;

		String biome = BiomeNames.korean(currentBiomeId(level, player));
		if (!biome.isEmpty()) {
			graphics.text(font, biome, MARGIN, BIOME_Y, BIOME_COLOR);
			right = Math.max(right, MARGIN + font.width(biome));
			bottom = BIOME_Y + LINE_HEIGHT;
		}

		// 교환 시계는 세트 줄 바로 위에 선다. 선택 화면이 세트를 대신 그려 주더라도 이 줄은
		// 대신 그려 주는 자리가 없어 그대로 둔다 — 좌표·바이옴 두 줄과 같은 취급이다.
		int setsTop = NEXT_LINE_Y;
		String swapTimer = ClientSwapTimer.line(level.getGameTime());
		if (swapTimer != null) {
			graphics.text(font, swapTimer, MARGIN, setsTop, SWAP_TIMER_COLOR);
			right = Math.max(right, MARGIN + font.width(swapTimer));
			setsTop += LINE_HEIGHT;
			bottom = setsTop;
		}

		if (!setsShownByScreen(client)) {
			TrialTimersLayout.Rect sets =
					renderSets(graphics, font, level.getGameTime(), setsTop);
			if (sets != null) {
				right = Math.max(right, sets.right());
				bottom = Math.max(bottom, sets.bottom());
			}
		}
		occupied = new TrialTimersLayout.Rect(0, 0, right, bottom);
	}

	/**
	 * 이 표시가 <b>지난 프레임에</b> 차지한 칸. 안 그렸으면 {@code null}.
	 *
	 * <p>드래곤 패턴 타이머의 시전 바({@link TrialTimersHud})가 좁은 화면에서 이 칸을 비켜 서려고
	 * 읽는다. 426×240 에서 시전 바(230)의 왼쪽 끝이 98 인데 세트 줄이 그보다 넓을 수 있다. 줄 수와
	 * 폭이 증강 · 바이옴 · 교환 시계로 그때그때 바뀌어 같은 셈을 밖에 한 벌 더 둘 수 없으므로, 그린
	 * 자리를 그대로 알려 준다. 시전 바가 이 표시보다 먼저 그려지므로(보스바 뒤에 붙음) 한 프레임
	 * 늦은 값이다.
	 */
	public static TrialTimersLayout.Rect occupied() {
		return occupied;
	}

	/** {@link #occupied} 의 몸. 그리기 스레드에서만 쓰고 읽는다. */
	private static TrialTimersLayout.Rect occupied;

	/**
	 * 지금 떠 있는 화면이 세트를 이미 또렷하게 그리고 있는가.
	 *
	 * <p>증강 선택 화면은 배경을 흐리게 깔고 그 위에 자기 것을 그린다. 흐림은 바닐라
	 * {@code Screen.extractBackground} 안에서 걸리고 <b>그때까지 그려진 것 전부</b>를 흐리는데,
	 * HUD 는 그보다 먼저 그려진다({@code Gui.extractRenderState} 가 HUD → 화면 차례다). 그래서
	 * 이 세트 줄은 선택 화면이 떠 있는 동안 흐려져 못 읽는다.
	 *
	 * <p>선택 화면은 같은 내용을 카드 왼쪽에 또렷하게 세우므로, 그럴 때는 여기서 접는다. 흐린
	 * 사본이 하나 더 남아 봐야 읽히지도 않으면서 화면만 지저분해진다. 반대로 화면이 좁아 그
	 * 판이 못 서면 거짓이 돌아오고, 그때는 흐려도 그리는 편이 낫다.
	 *
	 * <p>좌표·바이옴 두 줄은 그대로 둔다. 선택 화면에는 그 둘을 대신 그리는 자리가 없다.
	 */
	private static boolean setsShownByScreen(Minecraft client) {
		return client.gui.screen() instanceof PerkOfferScreen offer && offer.hidesHudSetLines();
	}

	/**
	 * 좌표·바이옴 아래에 세트를 쌓는다.
	 *
	 * <p><b>켜진 것만이 아니라 진행도까지</b> 보여 준다. 대신 <b>한 개도 없는 유형은 뺀다</b> —
	 * 열네 줄이 다 뜨면 화면 왼쪽 위를 통째로 덮는다. 그 규칙은 {@link PerkSetLines#visible} 이
	 * 들고 있다.
	 *
	 * <p>세트가 하나도 없으면 구분선도 긋지 않는다.
	 *
	 * @return 그린 칸(구분선부터 마지막 줄까지). 안 그렸으면 {@code null}
	 */
	private static TrialTimersLayout.Rect renderSets(GuiGraphicsExtractor graphics, Font font,
			long gameTime, int top) {
		List<PerkSetLines.Line> lines =
				ClientPerkSets.hudLines(PerkSetLines.MAX_HUD_LINES, gameTime);
		if (lines.isEmpty()) {
			return null;
		}

		int separatorY = top + SEPARATOR_GAP;
		int width = PerkSetLines.blockWidth(lines, font::width, SEPARATOR_MIN_WIDTH);
		graphics.fill(MARGIN, separatorY, MARGIN + width, separatorY + 1, SEPARATOR_COLOR);

		int y = separatorY + 1 + SEPARATOR_BOTTOM_GAP;
		for (PerkSetLines.Line line : lines) {
			graphics.text(font, line.text(), MARGIN, y,
					line.active() ? SET_ACTIVE_COLOR : SET_PROGRESS_COLOR);
			y += LINE_HEIGHT;
		}
		return new TrialTimersLayout.Rect(MARGIN, separatorY, MARGIN + width, y);
	}

	/**
	 * 지금 서 있는 자리의 바이옴 id. 알 수 없으면 {@code null}.
	 *
	 * <p>{@code unwrapKey()} 가 비는 경우는 데이터팩이 등록표에 없는 바이옴을 직접 끼워 넣었을
	 * 때다. 흔하지 않지만 {@code get()} 으로 꺼내면 그때 예외가 나고, HUD 는 매 프레임 돌기
	 * 때문에 그 한 번이 화면 전체를 죽인다. 그래서 {@code null} 로 받아 넘긴다.
	 */
	private static Identifier currentBiomeId(ClientLevel level, LocalPlayer player) {
		return level.getBiome(player.blockPosition())
				.unwrapKey()
				.map(ResourceKey::identifier)
				.orElse(null);
	}

}
