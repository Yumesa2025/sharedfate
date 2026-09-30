package com.sharedfate.client.perk;

import com.sharedfate.net.PerkChoiceC2SPayload;
import com.sharedfate.net.PerkOfferPayload;
import com.sharedfate.net.PerkRerollC2SPayload;
import com.sharedfate.net.PerkVoteC2SPayload;
import com.sharedfate.net.PerkVoteSyncPayload;
import com.sharedfate.perk.PerkRarity;
import com.sharedfate.ui.OwnedPerkPanelLayout;
import com.sharedfate.ui.PerkCardDismiss;
import com.sharedfate.ui.PerkCardFocus;
import com.sharedfate.ui.PerkCardMetrics;
import com.sharedfate.ui.PerkCardSetTypes;
import com.sharedfate.ui.PerkRerollButton;
import com.sharedfate.ui.PerkSetLines;
import com.sharedfate.ui.PerkSetPanelLayout;
import com.sharedfate.ui.PerkSetTooltip;
import com.sharedfate.ui.PerkSetTooltipLines;
import com.sharedfate.ui.PerkVoteBoard;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 증강 후보를 카드 형태로 가로 배치해 보여주는 화면.
 *
 * <p>{@code canChoose} 가 false 면 <b>고를 수는 없고 제안만 하는</b> 모드로 동작한다. 예전에는
 * 클릭이 통째로 막힌 보기 전용 창이었다 — 아래 「표」 절에 적어 두었다.
 *
 * <p>여는 경로가 두 가지다.
 *
 * <ul>
 *   <li><b>강제 오픈</b>({@code forced}) — 서버가 시간을 멈추고 띄운 창이다. ESC 로 닫을 수 없고
 *       상단에 제한시간 카운트다운이 크게 뜬다. 닫는 책임은 서버에 있다
 *       ({@code PerkCloseOfferPayload}).</li>
 *   <li><b>직접 열기</b> — {@code /shareteam perk} 로 연 확인용 창이다. 시간도 멈추지 않았고
 *       마감도 없으므로 ESC 로 자유롭게 닫을 수 있다.</li>
 * </ul>
 *
 * <p>카드 한 장은 위에서부터 <b>등급 띠 → 아이템 아이콘 → 이름 → 세트 유형 → 구분선 →
 * 설명</b> 순서로 쌓인다. 등급색은 띠·테두리·배경 그라데이션에 함께 쓰여서 무엇을 고르는
 * 라운드인지 글자를 읽지 않아도 알 수 있게 한다.
 *
 * <p>세트 유형 줄은 어느 유형에도 안 들어가는 증강에는 아예 없다. 그 줄이 늘어나면
 * {@link PerkCardMetrics} 가 재는 카드 높이도 함께 늘어야 한다 — 카드 안쪽은 잘려 그려지므로
 * 높이를 덜 세면 설명 마지막 줄이 소리 없이 사라진다.
 *
 * <h2>화면 왼쪽의 세트 판</h2>
 * <p>고르는 동안 <b>내가 무슨 세트를 모으고 있는지</b>가 보여야 판단이 된다. 그래서 왼쪽에
 * 「지금 켜진 세트」를 세로로 세운다. 자리는 {@link PerkSetPanelLayout} 이 정하는데,
 * <b>카드 자리를 먼저 잡고 남은 폭만</b> 넘기므로 판이 카드를 가리는 일은 없다.
 *
 * <p>대신 카드를 놓을 때 <b>판이 쓸 폭을 먼저 뗀다</b>({@link PerkOfferCardRow}). 떼지 않고
 * 카드를 화면 한가운데에 놓으면 왼쪽에 남는 폭이 판보다 좁아, 화면이 480 픽셀인 자리
 * (1080p 의 기본 GUI 배율 4배)에서는 판이 통째로 사라진다. 그때 남는 것은 HUD 의 세트 줄뿐인데
 * 그쪽은 <b>흐림 앞에 그려져 읽히지 않는다.</b>
 *
 * <p>판이 떠 있는 동안에는 HUD 가 세트 줄을 접는다({@code CoordinateHud}). 같은 내용이 흐린
 * 채로 하나 더 남으면 화면만 지저분해진다.
 *
 * <h2>표 — 선택자가 아닌 사람의 제안</h2>
 * <p>선택자가 아닌 사람이 카드를 누르면 그 카드 <b>등급 띠 오른쪽</b>에 체크가 붙고, 한 명 더
 * 누르면 수가 오른다. 같은 카드를 다시 누르면 취소되고 다른 카드를 누르면 그쪽으로 옮겨 간다.
 * 「다시 뽑기」도 같은 장치를 쓴다 — 선택자가 아닌 사람에게는 그 단추가 <b>제안 단추</b>로
 * 바뀌고, 그 자리에서 남은 횟수도 함께 보인다.
 *
 * <p>⚠ <b>표는 제안일 뿐이다.</b> 몇 개가 모이든 실제로 고르는 것은 선택자 하나고, 이 화면에도
 * 서버에도 표를 세어 자동으로 정하는 길이 없다.
 *
 * <p><b>세는 일은 서버가 한다.</b> 이 화면이 보내는 것은 「내가 이걸 눌렀다」 하나뿐이고
 * ({@link PerkVoteC2SPayload}), 켠 것인지 끈 것인지도 서버가 정해서 수를 되돌려 준다
 * ({@link PerkVoteSyncPayload}). 누른 순간 화면이 미리 체크를 그리지 않는 것은 <b>서버가
 * 거절했을 때 화면만 거짓말을 하게</b> 되기 때문이다 — 「다시 뽑기」가 남은 횟수를 미리 안
 * 깎는 것과 같은 이유다.
 *
 * <p>체크를 <b>등급 띠 안에</b> 두는 이유는 자리다. 카드 위쪽에 줄을 하나 더 놓으려면 카드가
 * 그만큼 짧아지는데, 1080p 의 GUI 배율 4배(480×270)처럼 좁은 화면에서는 그 몇 픽셀에 설명
 * 마지막 줄이 잘려 나간다({@link PerkCardMetrics}). 등급 띠는 <b>이미 카드 맨 위에 있고</b>
 * 등급 글자가 가운데 정렬이라 오른쪽 끝이 늘 비어 있다. 아이콘·이름·세트 유형·설명을 하나도
 * 안 가린다.
 *
 * <h2>흐림 위에 그린다</h2>
 * <p>선택 화면은 뒤의 게임 화면을 흐리게 깐다. 그 흐림은 {@code Screen.extractBackground} 안의
 * {@code extractBlurredBackground} 가 {@code GuiGraphicsExtractor.blurBeforeThisStratum()} 을
 * 불러서 걸리고, 그 이름대로 <b>지금 층(stratum)보다 앞서 그려진 것</b>만 흐려진다. 바닐라는
 * 배경 층을 그린 뒤 {@code nextStratum()} 으로 층을 한 칸 올리고 나서 이 화면의
 * {@link #extractRenderState} 를 부른다. 그러니 <b>여기서 그리는 것은 모두 흐림 뒤에 온다</b> —
 * 세트 판도 카드도 또렷하다. 반대로 {@code extractBackground} 를 덮어써서 그 안에 무언가
 * 그리면 그것만 흐려진다. <b>세트 판을 그 쪽으로 옮기지 말 것.</b>
 */
public class PerkOfferScreen extends Screen {
	private static final int COLOR_SILVER = 0xFFC0C6CC;
	private static final int COLOR_GOLD = 0xFFFFC63A;
	private static final int COLOR_PRISM = 0xFF5FE0D8;

	/** 카드 배경 그라데이션의 위/아래 색. 여기에 등급색을 조금 섞어서 쓴다. */
	private static final int CARD_TOP = 0xE81A1A24;
	private static final int CARD_BOTTOM = 0xE80B0B10;
	private static final int CARD_TOP_HOVER = 0xF4272734;
	private static final int CARD_BOTTOM_HOVER = 0xF4131319;
	/** 배경에 등급색을 섞는 비율. 평상시엔 은은하게, 호버 때는 조금 더 짙게. */
	private static final float CARD_TINT = 0.14F;
	private static final float CARD_TINT_HOVER = 0.26F;
	/** 호버한 카드의 테두리를 흰색 쪽으로 얼마나 끌어올릴지. */
	private static final float BORDER_BRIGHTEN_HOVER = 0.40F;

	private static final int SEPARATOR = 0x60FFFFFF;
	private static final int TEXT_MAIN = 0xFFFFFFFF;
	private static final int TEXT_SUB = 0xFFC0C0C0;
	private static final int TEXT_HINT = 0xFF909090;
	private static final int TEXT_SPECTATE = 0xFFFFD24A;
	private static final int TEXT_WAITING = 0xFF7FE07F;
	/** 등급 띠는 밝은 등급색으로 채우므로 글자는 어두워야 읽힌다. */
	private static final int BAND_TEXT = 0xFF10131A;

	/**
	 * 표 체크 뒤에 까는 어두운 바탕.
	 *
	 * <p>등급 띠는 실버·골드·프리즘이 저마다 다른 밝은 색이고 프리즘은 아예 무지개다. 어떤
	 * 색 위에 얹혀도 읽히려면 <b>체크가 제 바탕을 들고 다녀야</b> 한다.
	 */
	private static final int VOTE_MARK_BACKGROUND = 0xD00C0C13;
	/** 내가 던진 표. 왼쪽 세트 판의 「켜진 줄」과 같은 초록이다. */
	private static final int VOTE_MARK_OWN = 0xFF7FE07F;
	/** 남이 던진 표만 있는 자리. */
	private static final int VOTE_MARK_OTHER = 0xFFFFFFFF;
	/** 체크 바탕과 카드 오른쪽 변 사이의 틈. */
	private static final int VOTE_MARK_INSET = 2;
	/** 체크 바탕 안쪽 좌우 여백. */
	private static final int VOTE_MARK_PADDING = 2;

	private static final int TIMER_CALM = 0xFFFFFFFF;
	private static final int TIMER_URGENT = 0xFFFF5555;
	private static final int TIMER_BAR_BACKGROUND = 0x80202028;
	private static final int TIMER_BAR_FILL = 0xFFFFC63A;
	private static final int TIMER_BAR_FILL_URGENT = 0xFFFF5555;
	/** 재개 카운트다운의 글자색과 막대색. 제한시간과 달라야 다른 뜻으로 읽힌다. */
	private static final int RESUME_TEXT = 0xFF80FF20;
	private static final int RESUME_BAR_FILL = 0xFF80FF20;

	/** 결과를 보여 주는 동안 화면 전체에 씌우는 어둠. 고른 카드만 남기려는 것이다. */
	private static final int RESULT_VEIL = 0x9C05050A;

	/**
	 * 「보유 증강」 판이 펴져 있는 동안 카드 위에 덮는 어둠.
	 *
	 * <p>{@link #RESULT_VEIL} 보다 옅다. 카드를 <b>못 고른다</b>는 것만 알리면 되지, 무엇이
	 * 후보였는지까지 가릴 이유는 없다.
	 */
	private static final int OWNED_VEIL = 0x7005050A;
	/**
	 * 내려가는 카드 위에 덮는 어둠의 색. 투명도는 {@link PerkCardDismiss#shade} 가 정한다.
	 *
	 * <p>{@link #RESULT_VEIL} 과 같은 색이라 카드가 화면 어둠 속으로 가라앉는 것처럼 보인다.
	 */
	private static final int DISMISS_SHADE = 0x05050A;
	/** 내려간 카드가 화면 아래끝을 확실히 지나도록 더 얹는 거리. */
	private static final int DISMISS_OVERSHOOT = 4;
	/** 고른 카드 둘레에 두르는 빛의 겹 수. 바깥으로 갈수록 옅어진다. */
	private static final int HIGHLIGHT_GLOW_LAYERS = 4;
	/** 빛이 한 번 밝아졌다 어두워지는 데 걸리는 시간(ms). */
	private static final long HIGHLIGHT_PULSE_MILLIS = 900L;
	/** 빛이 가장 어두울 때와 가장 밝을 때의 세기. */
	private static final float HIGHLIGHT_GLOW_MIN = 0.35F;
	private static final float HIGHLIGHT_GLOW_MAX = 0.85F;

	/** 카운트다운 글자를 몇 배로 키울지. */
	private static final int TIMER_SCALE = 2;
	/** 이 초 이하로 남으면 빨갛게 바뀐다. */
	private static final int URGENT_SECONDS = 10;
	/**
	 * 마감이 지난 뒤 ESC 를 다시 허용하기까지의 유예(ms).
	 *
	 * <p>강제 오픈된 창을 닫는 것은 서버의 몫이지만, 닫기 지시가 오지 못하는 상황
	 * (패킷 유실·접속 이상)에서 플레이어가 화면에 영영 갇히면 안 된다. 서버는 이미
	 * 제한시간에 시간을 녹였을 시점이므로 이 뒤로는 클라이언트가 스스로 빠져나갈 수 있다.
	 */
	private static final long ESCAPE_GRACE_MILLIS = 5000L;
	private static final int TIMER_BAR_HEIGHT = 3;
	private static final int MILLIS_PER_TICK = 50;
	private static final int TICKS_PER_SECOND = 20;

	private static final int PREFERRED_CARD_WIDTH = 116;
	private static final int MIN_CARD_WIDTH = 56;
	private static final int CARD_GAP = 8;
	private static final int SCREEN_MARGIN = 8;
	/**
	 * 세트 판 자리를 떼고도 카드가 지켜야 하는 최소 폭.
	 *
	 * <p>{@link #PREFERRED_CARD_WIDTH} 의 4분의 3쯤이다. 이보다 좁아지면 설명이 여섯 줄 넘게
	 * 접히고, 접힌 만큼 카드가 세로로 길어지다가 결국 아래가 잘린다. 그렇게까지 좁은 화면에서는
	 * <b>판을 포기한다</b> — 무엇을 고르는지가 안 읽히는 것이 더 나쁘다.
	 */
	private static final int PANEL_MIN_CARD_WIDTH = 88;
	private static final int CARD_PADDING = 6;
	/** 이름과 설명 사이 구분선이 차지하는 세로 공간(위 여백 3 + 선 1 + 아래 여백 4). */
	private static final int SEPARATOR_BLOCK_HEIGHT = 8;
	/**
	 * 이름과 세트 유형 줄 사이의 틈.
	 *
	 * <p>유형이 없는 카드에는 이 틈도 없다. {@code Card.height} 가 같은 조건으로 더하므로
	 * <b>둘 중 한쪽만 고치면 카드가 짧아져 설명 마지막 줄이 잘린다.</b>
	 */
	private static final int SET_TYPE_GAP = 2;
	/**
	 * 세트 유형 줄의 글자색.
	 *
	 * <p>이름보다 흐리고 설명과는 같다. 이름 아래에 이름만큼 밝은 줄이 또 있으면 어느 쪽이
	 * 증강 이름인지 한눈에 안 갈린다.
	 */
	private static final int TEXT_SET_TYPE = 0xFF8FB8C8;

	/**
	 * 카드 높이 계산에 넘길 치수.
	 *
	 * <p>계산 자체는 {@link PerkCardMetrics} 에 있다. 여기서는 상수만 모아 넘긴다.
	 */
	private static PerkCardMetrics metrics(int lineHeight) {
		return new PerkCardMetrics(BAND_HEIGHT, ICON_GAP_TOP, ICON_GAP_BOTTOM, SET_TYPE_GAP,
				SEPARATOR_BLOCK_HEIGHT, CARD_PADDING, lineHeight);
	}

	/** 카드 맨 위 등급 띠의 높이. */
	private static final int BAND_HEIGHT = 11;

	/** 프리즘 띠에 흘릴 색. 빨강에서 보라까지 이어진다. */
	private static final int[] PRISM_COLORS = {
			0xFFFF5C5C, 0xFFFFA24A, 0xFFFFE24A, 0xFF6BE06B, 0xFF5FE0D8, 0xFF6B9CFF, 0xFFC06BFF
	};

	/** 무지개 띠를 몇 조각으로 나눠 칠할지. 폭보다 크면 폭에 맞춘다. */
	private static final int PRISM_BAND_STEPS = 48;
	/** 아이템 아이콘 한 변의 원래 크기. */
	private static final int ICON_UNIT = 16;
	/** 자리가 넉넉할 때 쓰는 아이콘 크기 후보. 앞에서부터 들어가는 것을 고른다. */
	private static final int[] ICON_SIZES = {ICON_UNIT * 2, ICON_UNIT, 0};
	/** 등급 띠와 아이콘 사이 여백. */
	private static final int ICON_GAP_TOP = 4;
	/** 아이콘과 이름 사이 여백. */
	private static final int ICON_GAP_BOTTOM = 3;
	/** 아이콘 좌우로 최소한 남겨 둘 여백. 이만큼도 안 되면 한 단계 작은 아이콘을 쓴다. */
	private static final int ICON_SIDE_ROOM = 8;

	/** 마우스를 올린 카드가 위로 떠오르는 높이. */
	private static final int HOVER_LIFT = 2;
	/** 창이 열릴 때 카드가 아래에서 올라오는 높이. */
	private static final int ENTRY_RISE = 6;
	/** 카드 한 장이 제자리를 잡기까지 걸리는 시간(ms). */
	private static final long ENTRY_DURATION_MILLIS = 220L;
	/** 카드마다 등장 시작을 조금씩 미뤄 왼쪽부터 차례로 올라오게 한다(ms). */
	private static final long ENTRY_STAGGER_MILLIS = 45L;

	/** 왼쪽 세트 판의 머리글. */
	private static final String PANEL_TITLE = "지금 켜진 세트";
	/**
	 * 세트 판에 그릴 줄 수의 상한.
	 *
	 * <p>유형이 열한 가지지만 <b>가진 것이 0인 유형은 {@link PerkSetLines#visible} 이 이미
	 * 빼고 준다.</b> 여덟이면 한 회차에 흩어질 수 있는 유형을 거의 다 담는다. 이보다 많아도
	 * 어차피 카드 높이가 세로를 막는다.
	 */
	private static final int PANEL_MAX_ROWS = 8;
	/** 세트 판 오른쪽 변과 첫 카드 사이에 반드시 남길 틈. */
	private static final int PANEL_CARD_GAP = 10;
	/** 세트 판 테두리와 글자 사이 여백. */
	private static final int PANEL_PADDING = 5;
	/** 머리글과 첫 세트 줄 사이의 틈. */
	private static final int PANEL_HEADER_GAP = 3;
	private static final int PANEL_BACKGROUND = 0xC00C0C13;
	private static final int PANEL_BORDER = 0x40FFFFFF;
	/** 켜진 세트 줄. 아직인 줄과 <b>색으로 갈린다</b>. */
	private static final int PANEL_ACTIVE = 0xFF7FE07F;
	/** 아직 안 켜진 세트 줄. */
	private static final int PANEL_IDLE = 0xFF98A0AC;

	/**
	 * 툴팁 줄에 쓸 색.
	 *
	 * <p>등급 세 색은 카드가 쓰는 것과 같은 값이다. 툴팁의 「종결곡」과 카드의 프리즘 띠가 다른
	 * 색이면 같은 등급으로 안 읽힌다.
	 */
	private static final PerkSetTooltipLines.Palette TOOLTIP_PALETTE =
			new PerkSetTooltipLines.Palette(0xFFFFFFFF, PANEL_ACTIVE, 0xFF7A828E, TEXT_SUB,
					COLOR_SILVER, COLOR_GOLD, COLOR_PRISM, TEXT_HINT);
	/**
	 * 유형이 둘인 증강의 툴팁에서 「아직 없는 것」을 몇 줄까지 적을지.
	 *
	 * <p>유형이 둘이면 툴팁이 두 덩어리가 된다. 양쪽 다 여섯 줄씩 적으면 스무 줄이 넘어
	 * 카드를 통째로 덮는다. 둘인 증강은 여든두 개 중 둘뿐이라 이 자리만 짧게 줄인다.
	 */
	private static final int MULTI_TYPE_MISSING_ROWS = 3;

	/** 「다시 뽑기」 단추의 크기와 카드 아래 여백. */
	/** 「보유 증강」 단추의 치수. 왼쪽 세트 판 폭에 맞춰 줄어든다. */
	private static final int OWNED_BUTTON_HEIGHT = 14;
	private static final int OWNED_BUTTON_WIDTH = 78;
	/** 세트 판 아랫변과 단추 사이의 틈. */
	private static final int OWNED_BUTTON_GAP = 4;

	private static final int REROLL_HEIGHT = 16;
	private static final int REROLL_WIDTH = 140;
	private static final int REROLL_GAP = 5;

	private final int milestone;
	private final boolean canChoose;
	private final boolean forced;
	/**
	 * 제한시간 전체 길이(ms). 남은 시간 막대의 분모다. 마감이 없으면 0.
	 */
	private final long totalMillis;
	/**
	 * 마감 시각. 서버가 보낸 "남은 틱"을 받는 순간의 클라이언트 시계에 더해 둔 값이다.
	 *
	 * <p>서버 시계와 클라이언트 시계를 맞출 필요가 없고, 화면이 몇 프레임 밀려도 표시가 어긋나지
	 * 않는다. 어차피 실제 마감 판정은 서버가 하므로 여기 값은 보여 주기 위한 것뿐이다.
	 */
	private final long deadlineMillis;
	/** 창이 만들어진 시각. 등장 애니메이션의 기준점이다. */
	private final long openedAtMillis = System.currentTimeMillis();
	/**
	 * 이 라운드의 등급. 한 구간에서는 등급 하나 안에서만 후보를 뽑으므로 부제에 함께 적는다.
	 * 후보가 하나도 없거나 등급 문자열이 깨졌으면 null.
	 */
	private final @Nullable PerkRarity roundRarity;
	private final List<PerkOfferPayload.PerkOption> options;
	private final List<Card> cards = new ArrayList<>();

	/** 강제 오픈에서 선택을 보낸 뒤. 서버가 창을 닫아 줄 때까지 클릭을 막는다. */
	private boolean choiceSent;

	/**
	 * 이번 회차에 남은 다시 뽑기 횟수. 서버가 이 창을 보낼 때 실어 준 값이다.
	 *
	 * <p>여기서 줄이지 않는다. 다시 뽑으면 서버가 <b>선택창을 통째로 다시 보내므로</b>
	 * 새 화면이 줄어든 값을 들고 열린다. 클라이언트가 스스로 세면 서버와 어긋날 수 있다.
	 */
	private final int rerollsRemaining;
	/** 다시 뽑기를 눌러 두고 서버의 새 후보를 기다리는 중. 두 번 눌리는 것을 막는다. */
	private boolean rerollSent;
	/**
	 * 기다리기를 포기하기까지 남은 틱.
	 *
	 * <p>서버가 요청을 받아들이면 선택창이 통째로 다시 와서 이 화면 자체가 사라지므로 이
	 * 카운트다운은 끝까지 가지 않는다. 끝까지 갔다는 것은 <b>서버가 조용히 버렸다</b>는
	 * 뜻이다(뽑을 후보가 그 등급에 안 남은 경우). 그때 단추를 영영 잠근 채로 두면 남은
	 * 횟수가 그대로인데도 못 쓰게 되므로 다시 풀어 준다.
	 */
	private int rerollWaitTicks;
	/** 서버의 답을 기다리는 시간. 2초. */
	private static final int REROLL_WAIT_TICKS = 40;
	/**
	 * 카드 아래 가운데의 단추. 직접 연 창에는 없어서 null 일 수 있다.
	 *
	 * <p>하는 일이 <b>보는 사람에 따라 다르다.</b> 선택자에게는 진짜 「다시 뽑기」고,
	 * 선택자가 아닌 사람에게는 「다시 뽑자」는 표를 던지는 제안 단추다. 자리와 크기가 같아
	 * 위젯을 둘로 나누지 않았다 — 나누면 둘이 같은 자리에 겹칠 수 있다.
	 */
	private @Nullable Button rerollButton;

	/**
	 * 대상마다 지금 몇 표인가. <b>서버가 센 값을 그대로 담는다.</b>
	 *
	 * <p>열쇠는 후보 증강 id 이거나 {@link PerkVoteBoard#REROLL_TARGET} 이다. 여기에 스스로
	 * 더하거나 빼지 않는다 — 두 사람이 같은 순간에 누르면 화면마다 다른 수가 뜬다.
	 */
	private final Map<String, Integer> voteCounts = new HashMap<>();
	/** 내가 표를 던져 둔 대상. 안 던졌으면 빈 문자열. 체크 색을 가르는 데 쓴다. */
	private String ownVote = "";

	/**
	 * 결정된 증강의 후보 번호. 아직 결정 전이면 -1.
	 *
	 * <p>서버가 {@code PerkResultPayload} 를 보내면 그 카드 하나만 남기고 나머지를 지운다.
	 * 고른 사람 말고는 무엇이 정해졌는지 모른 채 창이 사라지던 것을 막기 위한 자리다.
	 */
	private int resultIndex = -1;
	/** 결과를 보여 주는 남은 시간(틱). 0 이면 결과 화면이 아니다. */
	private int resultTicks;
	/**
	 * 결과가 정해진 시각. 안 고른 카드가 내려가고 고른 카드가 가운데로 오는 움직임의 기준점이다.
	 * 재는 일은 {@link #resultElapsedMillis()} 가 한다.
	 */
	private long resultStartedAtMillis;
	/** 결과를 보여 주기로 한 전체 시간(틱). 카운트다운 막대의 분모다. */
	private int resultTotalTicks;
	/** 결과를 고른 사람 이름. 시간이 다 되어 자동으로 정해졌으면 빈 문자열. */
	private String resultChooser = "";

	private int timerY;
	private int titleY;
	private int subtitleY;
	private int cardWidth;
	private int cardHeight;
	private int cardTop;
	private int firstCardLeft;
	/** 이번 배치에서 카드에 그릴 아이콘 크기. 자리가 없으면 0이고 아이콘을 건너뛴다. */
	private int iconSize;
	/** 등장 애니메이션을 돌릴지. 화면이 빠듯하면 아래 문구를 침범하므로 끈다. */
	private boolean entryAnimated;

	/** 왼쪽 판에 그릴 세트 줄들. 켜진 것이 위다. */
	private List<PerkSetLines.Line> panelLines = List.of();
	/** 왼쪽 판의 자리. 폭이 모자라면 {@link PerkSetPanelLayout#visible()} 이 거짓이다. */
	private PerkSetPanelLayout panel = PerkSetPanelLayout.hidden();
	/**
	 * 판을 마지막으로 다시 잰 시점의 세트 상태.
	 *
	 * <p>{@code TeamScreen.signature()} 와 같은 뜻이다 — 세트가 그대로면 줄을 다시 고르지도,
	 * 글자 폭을 다시 재지도 않는다. {@code null} 이면 아직 한 번도 안 쟀다는 뜻이라
	 * {@link #init()} 이 여기에 {@code null} 을 넣어 다음 프레임에 다시 재게 한다.
	 */
	private @Nullable String panelSignature;

	/** 왼쪽 세트 판 아래의 「보유 증강」 단추. 자리가 없으면 만들지 않는다. */
	private @Nullable Button ownedButton;
	/** 그 단추가 펴는 겹판. 떠 있는 동안 카드 클릭이 막힌다. */
	private final OwnedPerkPanel ownedPanel = new OwnedPerkPanel();
	/** 겹판이 지금 펴져 있는가. */
	private boolean ownedOpen;

	public PerkOfferScreen(PerkOfferPayload payload) {
		super(Component.literal("증강 선택"));
		this.milestone = payload.milestone();
		this.canChoose = payload.canChoose();
		this.forced = payload.forced();
		this.totalMillis = payload.hasDeadline()
				? (long) payload.remainingTicks() * MILLIS_PER_TICK : 0L;
		this.deadlineMillis = payload.hasDeadline()
				? System.currentTimeMillis() + this.totalMillis : 0L;
		this.options = payload.options() == null
				? List.of() : List.copyOf(payload.options());
		this.rerollsRemaining = Math.max(0, payload.rerollsRemaining());
		this.roundRarity = firstRarity(this.options);
	}

	/** 이 창이 다루는 레벨 구간. 서버의 닫기 지시가 맞는 창인지 가릴 때 쓴다. */
	public int milestone() {
		return milestone;
	}

	/** 서버가 강제로 띄운 창인지. */
	public boolean forced() {
		return forced;
	}

	/** 서버의 닫기 지시로 창을 닫는다. 강제 오픈이든 아니든 그대로 닫힌다. */
	public void closeFromServer() {
		super.onClose();
	}

	/**
	 * 무엇이 골라졌는지 서버가 알려 왔다. 그 카드 하나만 남겨 잠깐 보여 준다.
	 *
	 * <p>안 고른 카드는 곧바로 사라지지 않고 <b>아래로 미끄러져 내려가고</b>
	 * ({@link #renderDismissedCards}), 고른 카드는 그동안 가운데로 미끄러져 온다
	 * ({@link PerkCardFocus}). 0.75초면 자리가 다 잡히므로 남은 시간은 전부 고른 카드를 읽는
	 * 데 쓰인다.
	 *
	 * <p>후보에 없는 식별자가 오면 아무것도 하지 않는다. 늦게 도착한 지시가 다음 구간의 창을
	 * 건드리는 일을 막는다.
	 *
	 * <p>{@code holdTicks} 는 서버가 시간을 더 멈춰 둘 길이({@code RESULT_TICKS})와 같은 값이
	 * 실려 온 것이다. 그래서 이 시간을 그대로 <b>재개 카운트다운</b>으로 쓴다.
	 */
	public void showResult(String perkId, String chooserName, int holdTicks) {
		for (int index = 0; index < options.size(); index++) {
			if (options.get(index).id().equals(perkId)) {
				resultIndex = index;
				resultTicks = Math.max(1, holdTicks);
				resultTotalTicks = resultTicks;
				resultStartedAtMillis = System.currentTimeMillis();
				resultChooser = chooserName == null ? "" : chooserName;
				choiceSent = true;
				playResultSound();
				return;
			}
		}
	}

	private void playResultSound() {
		if (this.minecraft == null) {
			return;
		}
		this.minecraft.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance
				.forUI(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.1F, 0.7F));
	}

	/** 결과를 보여 주는 중인지. */
	public boolean showingResult() {
		return resultIndex >= 0;
	}

	/**
	 * 서버가 센 표를 받아 화면에 반영한다.
	 *
	 * <p>구간이 다른 묶음은 버린다. 늦게 도착한 것이 다음 회차의 창에 체크를 그리면 안 된다.
	 *
	 * <p>받은 값으로 <b>통째로 갈아 끼운다.</b> 서버가 보낸 목록에 없는 대상은 0표라는 뜻이고,
	 * 마지막 한 사람이 표를 거두면 빈 목록이 온다. 있는 것만 덮어쓰면 그때 체크가 안 지워진다.
	 */
	public void updateVotes(PerkVoteSyncPayload payload) {
		if (payload == null || payload.milestone() != milestone) {
			return;
		}
		voteCounts.clear();
		for (PerkVoteSyncPayload.Tally tally : payload.tallies()) {
			voteCounts.put(tally.target(), tally.count());
		}
		ownVote = payload.ownVote();
		// 단추 글자에도 표 수가 붙는다. 매 프레임 맞추고는 있지만 여기서 한 번 더 맞춰 두면
		// 받은 그 순간에 바뀐다.
		refreshRerollButton();
	}

	/** 이 대상에 모인 표 수. 아무도 안 던졌으면 0. */
	private int voteCount(String target) {
		return voteCounts.getOrDefault(target, 0);
	}

	/**
	 * 지금 표를 던질 수 있는가.
	 *
	 * <p>서버가 강제로 띄운 창에서 <b>선택자가 아닌 사람</b>만이다. 선택자는 고르면 되지
	 * 제안할 것이 없고, {@code /shareteam perk} 로 직접 연 창은 혼자 보는 확인용이라
	 * 제안할 상대가 없다.
	 *
	 * <p>관전자는 뺀다. 서버도 같은 판정으로 버리지만({@code PerkChoiceSession.castVote}),
	 * 여기서 막지 않으면 눌러도 아무 일이 안 일어나는 카드가 눌릴 것처럼 밝아진다.
	 */
	private boolean votable() {
		return forced && !canChoose && !showingResult() && !spectating();
	}

	/** 이 클라이언트가 관전 모드인가. 아직 월드에 들어오지 않았으면 거짓으로 본다. */
	private boolean spectating() {
		return this.minecraft != null && this.minecraft.player != null
				&& this.minecraft.player.isSpectator();
	}

	/**
	 * 「이걸 하자」고 서버에 알린다. <b>보내는 것은 「눌렀다」는 사실뿐이다.</b>
	 *
	 * <p>여기서 체크를 미리 그리지 않는다. 켤지 끌지 옮길지도, 그래서 몇 표가 되는지도 전부
	 * 서버가 정해 {@link PerkVoteSyncPayload} 로 되돌려 준다. 미리 그려 두면 서버가 요청을
	 * 버렸을 때(관전자가 됐다든지, 후보가 방금 갈렸다든지) 화면만 거짓말을 하게 된다 —
	 * {@link #requestReroll} 이 남은 횟수를 미리 안 깎는 것과 같은 이유다.
	 */
	private void castVote(String target) {
		if (!votable()) {
			return;
		}
		if (ClientPlayNetworking.canSend(PerkVoteC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new PerkVoteC2SPayload(milestone, target));
		}
	}

	/**
	 * 결과가 정해진 뒤 지난 시간(ms). 카드가 움직이는 계산은 전부 이 값을 기준으로 한다.
	 *
	 * <p>남은 틱({@code resultTicks})으로 재지 않는다. 틱은 초당 20번뿐이라 그 값으로 자리를
	 * 옮기면 카드가 20단계로 뚝뚝 끊겨 움직인다.
	 */
	private long resultElapsedMillis() {
		return System.currentTimeMillis() - resultStartedAtMillis;
	}

	@Override
	public void tick() {
		if (resultTicks > 0) {
			resultTicks--;
		}
		if (rerollWaitTicks > 0 && --rerollWaitTicks == 0) {
			// 서버가 받아들였다면 이 화면은 벌써 새 선택창으로 바뀌었다. 여기까지 왔다는 것은
			// 조용히 버려졌다는 뜻이고, 그때는 횟수도 안 깎였으므로 다시 누를 수 있어야 한다.
			rerollSent = false;
		}
		// 선택을 보냈거나 결과가 정해지면 단추도 함께 잠긴다. 상태가 바뀌는 자리가 여럿이라
		// 매 틱 한 번 맞춰 두는 편이 빠뜨릴 걱정이 없다.
		refreshRerollButton();
	}

	@Override
	protected void init() {
		// GUI 배율이 커서 화면이 좁아지면 카드 폭·높이를 줄여 화면 밖으로 나가지 않게 한다.
		int top = this.height < 170 ? 6 : 12;
		timerY = top;
		// 카운트다운은 TIMER_SCALE 배로 그리므로 그만큼 세로 자리를 먼저 비워 둔다.
		int timerBlock = hasDeadline()
				? this.font.lineHeight * TIMER_SCALE + TIMER_BAR_HEIGHT + 6 : 0;
		titleY = top + timerBlock + (this.height < 170 ? 2 : 4);
		subtitleY = titleY + 14;
		int headerBottom = subtitleY + 12;

		cards.clear();
		int count = Math.max(1, options.size());
		// 왼쪽 세트 판이 쓸 폭을 먼저 뗀다. 뗄 수 없을 만큼 좁은 화면이면 0 을 뗀 것과 같은
		// 값이 돌아오고, 그때는 판이 저절로 사라진다.
		PerkOfferCardRow row = PerkOfferCardRow.fit(
				new PerkOfferCardRow.Limits(SCREEN_MARGIN, CARD_GAP, MIN_CARD_WIDTH,
						PREFERRED_CARD_WIDTH, PANEL_MIN_CARD_WIDTH),
				this.width, count, panelReserve());
		cardWidth = row.cardWidth();
		firstCardLeft = row.firstCardLeft();

		int innerWidth = Math.max(8, cardWidth - CARD_PADDING * 2);
		for (PerkOfferPayload.PerkOption option : options) {
			cards.add(Card.of(this.font, option, innerWidth, ClientPerkSets.all()));
		}

		int footerTop = Math.max(headerBottom + 46, this.height - 22);
		// 다시 뽑기 단추는 카드 아래 가운데에 선다. 그릴 자리를 먼저 떼어 두지 않으면
		// 카드가 그 자리까지 늘어나 단추와 겹친다.
		// 선택자가 아닌 사람에게도 같은 자리에 선다 — 그쪽은 제안 단추다. 자리를 떼는 조건이
		// 달라지면 사람마다 카드 높이가 달라져 같은 회차를 서로 다른 화면으로 보게 된다.
		boolean showReroll = PerkRerollButton.visible(forced, canChoose)
				|| PerkRerollButton.proposable(forced, canChoose);
		int rerollBlock = showReroll ? REROLL_HEIGHT + REROLL_GAP : 0;
		int room = footerTop - rerollBlock - headerBottom - 6;
		// 아이콘을 큰 것부터 대 보고 세로·가로 자리가 모두 나오는 첫 크기를 고른다.
		iconSize = 0;
		int neededHeight = 40;
		for (int candidate : ICON_SIZES) {
			if (candidate > 0 && candidate + ICON_SIDE_ROOM > cardWidth) {
				continue;
			}
			int needed = requiredHeight(candidate);
			iconSize = candidate;
			neededHeight = needed;
			if (needed <= room) {
				break;
			}
		}

		cardHeight = Math.max(40, Math.min(neededHeight, room));
		cardTop = headerBottom
				+ Math.max(0, (footerTop - rerollBlock - headerBottom - cardHeight) / 2);
		// 등장 애니메이션은 카드를 잠깐 아래로 밀어 둔다. 아래 문구까지 여유가 있을 때만 쓴다.
		entryAnimated = cardTop + cardHeight + ENTRY_RISE <= this.height - 20;

		rerollButton = null;
		if (showReroll) {
			int rerollWidth = Math.min(REROLL_WIDTH, Math.max(80, this.width - SCREEN_MARGIN * 2));
			// 화면이 아주 낮으면 카드가 방금 정한 자리를 넘어설 수 있다. 그래도 단추가 아래
			// 안내 문구를 덮지 않도록 마지막에 한 번 더 위로 눌러 둔다.
			int rerollY = Math.min(cardTop + cardHeight + REROLL_GAP,
					this.height - 16 - REROLL_HEIGHT);
			rerollButton = Button.builder(
							Component.literal(rerollButtonLabel()),
							button -> onRerollButtonPressed())
					.bounds((this.width - rerollWidth) / 2, rerollY, rerollWidth, REROLL_HEIGHT)
					.build();
			addRenderableWidget(rerollButton);
			refreshRerollButton();
		}

		// 카드 자리가 다 정해진 뒤라야 왼쪽에 남은 폭을 잴 수 있다. 표시를 지우고 바로 다시
		// 재는 이유는 두 가지다 — 창이 열린 첫 프레임부터 판이 서야 하고(HUD 가 이 값을 보고
		// 자기 세트 줄을 접는다), 화면 크기가 바뀌어 다시 배치할 때는 세트가 그대로여도 자리를
		// 새로 잡아야 한다. 창이 열린 뒤에 세트 패킷이 오는 경로는 표시가 달라지므로 저절로
		// 따라온다.
		panelSignature = null;
		placeOwnedButton();
		refreshSetPanel();
	}

	/**
	 * 왼쪽 열 <b>맨 아래</b>에 「보유 증강」 단추를 세운다.
	 *
	 * <p>세트 판은 열의 위에서부터 자라고 이 단추는 아래에 붙는다. 둘이 겹치지 않도록
	 * {@link #fitSetPanel} 이 단추 자리를 미리 떼어 낸다.
	 *
	 * <p>왼쪽 열이 없는 화면(카드가 너무 좁아져 {@code PerkOfferCardRow.fit} 이 자리 떼기를
	 * 포기한 경우)에서는 <b>단추를 만들지 않는다.</b> 카드 위에 걸치느니 없는 편이 낫다.
	 */
	private void placeOwnedButton() {
		ownedButton = null;
		ownedOpen = false;
		int available = firstCardLeft - PANEL_CARD_GAP - SCREEN_MARGIN;
		if (available < OWNED_BUTTON_WIDTH) {
			return;
		}
		int y = ownedButtonTop();
		if (y < cardTop) {
			// 카드 띠가 단추 하나도 못 담을 만큼 낮다.
			return;
		}
		ownedButton = Button.builder(Component.literal("현재 증강"), button -> toggleOwnedPanel())
				.bounds(SCREEN_MARGIN, y, OWNED_BUTTON_WIDTH, OWNED_BUTTON_HEIGHT)
				.build();
		addRenderableWidget(ownedButton);
	}

	/** 「보유 증강」 단추의 윗변. 세트 판이 물러나야 하는 선이기도 하다. */
	private int ownedButtonTop() {
		int bottom = cardTop + cardHeight;
		if (rerollButton != null) {
			bottom = Math.min(bottom, rerollButton.getY() - 2);
		}
		return Math.min(bottom, this.height - SCREEN_MARGIN) - OWNED_BUTTON_HEIGHT;
	}

	/** 겹판을 폈다 접는다. 접는 쪽이 기본이라 다시 열면 언제나 맨 위부터 보인다. */
	private void toggleOwnedPanel() {
		if (!ownedOpen && !canOpenOwnedPanel()) {
			// 여기 오면 안 된다. 열 자리가 없으면 단추가 이미 꺼져 있다.
			return;
		}
		ownedOpen = !ownedOpen;
		if (ownedOpen) {
			ownedPanel.resetScroll();
			layoutOwnedPanel();
		}
		refreshOwnedButton();
	}

	/**
	 * 지금 화면에 모달이 설 자리가 있는가.
	 *
	 * <h2>왜 따로 묻는가</h2>
	 *
	 * <p>예전에는 누른 <b>뒤에</b> 자리를 재서, 자리가 없으면 열자마자 도로 닫고 조용히
	 * 빠져나갔다. 화면에서는 <b>단추가 아예 안 눌리는 것처럼</b> 보인다 — 눌렀는데 아무 일도
	 * 안 일어나고 까닭도 안 알려 주니, 무엇이 잘못됐는지 알 길이 없다.
	 *
	 * <p>그래서 미리 물어보고, 못 열면 <b>단추를 꺼 둔다.</b> 회색 단추는 「지금은 안 된다」를
	 * 스스로 말한다.
	 */
	private boolean canOpenOwnedPanel() {
		return OwnedPerkPanelLayout.fit(this.width, this.height, OwnedPerkPanel.SCREEN_MARGIN,
				this.font.lineHeight, OwnedPerkPanel.PADDING).visible();
	}

	/**
	 * 「보유 증강」 모달을 <b>화면 한가운데</b>에 세운다.
	 *
	 * <p>모달이므로 왼쪽 열에 매이지 않는다. 뒤의 화면은 그대로 두고 어둠 한 겹만 덮는다.
	 */
	private void layoutOwnedPanel() {
		ownedPanel.layout(this.font, this.width, this.height);
	}

	/** 단추 글자를 지금 상태에 맞춘다. */
	private void refreshOwnedButton() {
		if (ownedButton == null) {
			return;
		}
		ownedButton.setMessage(Component.literal(ownedOpen ? "닫기" : "현재 증강"));
		// 열 자리가 없으면 꺼 둔다. 눌렀는데 아무 일도 안 일어나는 것보다 회색 단추가 낫다.
		ownedButton.active = ownedOpen || canOpenOwnedPanel();
		// 결과를 보여 주는 동안에는 왼쪽 판과 함께 접는다. 고른 카드 하나만 남기는 화면이다.
		boolean hide = showingResult();
		ownedButton.visible = !hide;
		if (hide && ownedOpen) {
			ownedOpen = false;
			ownedButton.setMessage(Component.literal("현재 증강"));
		}
		// 다시 뽑기 단추의 보임 여부가 이 상태에 매여 있다. 한 곳에서 함께 맞춘다.
		refreshRerollButton();
	}

	/**
	 * 왼쪽 세트 판이 서려면 카드 왼쪽에 비워 두어야 하는 폭. 그릴 줄이 없으면 0.
	 *
	 * <p>{@link #fitSetPanel} 이 재는 것과 <b>같은 값</b>이어야 한다. 여기서 덜 떼면 판이 자리에
	 * 못 들어가 사라지고, 더 떼면 카드가 이유 없이 오른쪽으로 밀린다.
	 */
	private int panelReserve() {
		List<PerkSetLines.Line> lines = ClientPerkSets.lines(PANEL_MAX_ROWS);
		int setWidth = 0;
		if (!lines.isEmpty()) {
			setWidth = Math.max(PerkSetLines.blockWidth(lines, this.font::width, 0),
					this.font.width(PANEL_TITLE)) + PANEL_PADDING * 2;
		}
		// 세트 줄이 아직 하나도 없어도 「보유 증강」 단추는 서야 하므로, 둘 중 넓은 쪽으로
		// 뗀다. 뗀 자리가 카드를 최소 폭 아래로 밀면 PerkOfferCardRow.fit 이 통째로 포기하고,
		// 그때는 단추도 함께 사라진다.
		return Math.max(setWidth, OWNED_BUTTON_WIDTH) + PANEL_CARD_GAP;
	}

	/**
	 * HUD 가 자기 세트 줄을 접어야 하는가.
	 *
	 * <p>이 화면이 왼쪽에 같은 것을 또렷하게 세우고 있으면 참이다. 판이 못 서는 좁은 화면에서는
	 * 거짓이라 HUD 가 그대로 그린다 — 흐려도 없는 것보다 낫다.
	 *
	 * <p>결과를 보여 주는 동안에는 판도 접히지만 여기서는 참을 그대로 돌려준다. 그때는 고른 카드
	 * 하나만 남기는 화면이라, 흐린 세트 줄이 다시 나타나면 눈이 그리로 끌린다.
	 */
	public boolean hidesHudSetLines() {
		return panel.visible();
	}

	/**
	 * 왼쪽 세트 판을 다시 잰다. 세트가 그대로면 아무것도 하지 않는다.
	 *
	 * <p>매 프레임 불러도 되게 만들어 두었다. 세트는 이 창이 떠 있는 동안 거의 안 바뀌지만,
	 * <b>바로 앞 회차의 증강이 적용된 직후에 창이 뜨는 경로</b>가 있어 한 번은 늦게 온다.
	 * 그때 판이 빈 채로 남으면 정작 필요한 순간에 아무것도 안 보인다.
	 */
	private void refreshSetPanel() {
		String signature = ClientPerkSets.signature();
		if (signature.equals(panelSignature)) {
			return;
		}
		panelSignature = signature;
		panelLines = ClientPerkSets.lines(PANEL_MAX_ROWS);
		panel = fitSetPanel();
	}

	/**
	 * 왼쪽 판이 들어갈 자리를 잰다.
	 *
	 * <p>오른쪽 한계는 <b>첫 카드에서 틈만큼 물러난 자리</b>고, 세로는 <b>카드가 서 있는 띠</b>
	 * 안이다. 카드 띠를 벗어나지 않게 잡으면 안내 문구를 덮을 수 없다.
	 *
	 * <p>「다시 뽑기」 단추 위에서 한 번 더 끊는다. 화면이 아주 낮으면 카드가 제 자리를 넘어서
	 * 단추 아래까지 내려가는데({@code cardHeight} 의 최소값 40), 후보가 한 장뿐인 회차에서는
	 * 단추가 판 바로 위까지 넓어져 겹칠 수 있다.
	 */
	private PerkSetPanelLayout fitSetPanel() {
		if (panelLines.isEmpty()) {
			return PerkSetPanelLayout.hidden();
		}
		int bottom = cardTop + cardHeight;
		if (rerollButton != null) {
			bottom = Math.min(bottom, rerollButton.getY() - 2);
		}
		if (ownedButton != null) {
			// 「보유 증강」 단추가 열 맨 아래에 붙어 있다. 그 자리를 떼지 않으면 세트 줄이
			// 많은 팀에서 판이 단추를 덮는다.
			bottom = Math.min(bottom, ownedButton.getY() - OWNED_BUTTON_GAP);
		}
		PerkSetPanelLayout.Room room = new PerkSetPanelLayout.Room(SCREEN_MARGIN,
				firstCardLeft - PANEL_CARD_GAP, cardTop, bottom,
				this.font.lineHeight, PANEL_PADDING, PANEL_HEADER_GAP);
		return PerkSetPanelLayout.fit(room, panelLines.size(),
				PerkSetLines.blockWidth(panelLines, this.font::width, 0),
				this.font.width(PANEL_TITLE));
	}

	/**
	 * 다시 뽑기 단추를 지금 누를 수 있는 상태로 맞춘다.
	 *
	 * <p>결과가 정해지면 단추 자체를 감춘다. 남은 카드 하나만 보여 주는 화면에 단추가 남아
	 * 있으면 아직 무를 수 있는 것처럼 보인다.
	 */
	private void refreshRerollButton() {
		if (rerollButton == null) {
			return;
		}
		// 「보유 증강」 판이 펴져 있는 동안에도 감춘다. 단추는 위젯이라 이 화면이 그리는 것보다
		// 나중에 올라와, 감추지 않으면 판 위에 단추만 동동 뜬다.
		rerollButton.visible = !showingResult() && !ownedOpen;
		// 글자에 표 수가 붙으므로 매번 다시 적는다. 제안 단추가 아닐 때는 같은 글자가 다시
		// 들어갈 뿐이라 값이 없다.
		rerollButton.setMessage(Component.literal(rerollButtonLabel()));
		if (PerkRerollButton.proposable(forced, canChoose)) {
			// 관전자는 표를 못 던진다. 서버도 같은 판정으로 버리지만, 여기서 잠가 두지 않으면
			// 눌러도 아무 일이 안 일어나는 단추가 된다 — 회색 단추가 낫다.
			rerollButton.active = PerkRerollButton.proposeEnabled(forced, canChoose,
					showingResult(), rerollsRemaining) && !spectating();
			return;
		}
		rerollButton.active = PerkRerollButton.enabled(forced, canChoose, choiceSent, rerollSent,
				showingResult(), rerollsRemaining);
	}

	/**
	 * 단추에 적을 글자.
	 *
	 * <p>제안 단추에는 뒤에 표 수가 붙는다. 카드처럼 <b>단추 위에</b> 줄을 따로 놓지 않는
	 * 것은 자리 때문이다 — 카드 아랫변과 아래 안내 문구 사이에 남는 것은 틈 5픽셀과 단추
	 * 높이 16픽셀뿐이고, 줄을 하나 끼우려고 그만큼 떼면 카드가 짧아져 설명 마지막 줄이
	 * 잘린다. 단추는 카드와 달리 글자 한 줄이 전부라, 같은 체크를 글자 끝에 붙이면
	 * 가릴 것이 없다.
	 */
	private String rerollButtonLabel() {
		if (!PerkRerollButton.proposable(forced, canChoose)) {
			return PerkRerollButton.label(rerollsRemaining);
		}
		String base = PerkRerollButton.proposeLabel(rerollsRemaining);
		String mark = PerkVoteBoard.mark(voteCount(PerkVoteBoard.REROLL_TARGET));
		return mark.isEmpty() ? base : base + " " + mark;
	}

	/**
	 * 단추를 눌렀다. 선택자면 진짜로 다시 뽑고, 아니면 「다시 뽑자」는 표를 던진다.
	 *
	 * <p>어느 쪽인지는 {@link PerkRerollButton#proposable} 하나로 가른다. 두 길이 같은 위젯을
	 * 쓰므로 여기서 갈라 두지 않으면 관전하던 사람이 진짜 재추첨 패킷을 보내게 된다 — 서버가
	 * 버리기는 하지만, 버려질 것을 보내는 화면은 곧 거짓말하는 화면이 된다.
	 */
	private void onRerollButtonPressed() {
		if (PerkRerollButton.proposable(forced, canChoose)) {
			castVote(PerkVoteBoard.REROLL_TARGET);
			return;
		}
		requestReroll();
	}

	/**
	 * 다시 뽑아 달라고 서버에 알린다. <b>보내는 것은 「눌렀다」는 사실뿐이다.</b>
	 *
	 * <p>여기서 창을 닫지도, 남은 횟수를 줄이지도 않는다. 서버가 요청을 받아들이면 새 후보와
	 * 줄어든 횟수를 담은 선택창을 다시 보내 주고, 거절하면 아무 일도 일어나지 않는다.
	 * 클라이언트가 미리 줄여 두면 거절당했을 때 화면만 거짓말을 하게 된다.
	 */
	private void requestReroll() {
		if (!PerkRerollButton.enabled(forced, canChoose, choiceSent, rerollSent, showingResult(),
				rerollsRemaining)) {
			return;
		}
		if (ClientPlayNetworking.canSend(PerkRerollC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new PerkRerollC2SPayload(milestone));
		}
		rerollSent = true;
		rerollWaitTicks = REROLL_WAIT_TICKS;
		refreshRerollButton();
	}

	/** 주어진 아이콘 크기로 카드 내용을 다 담으려면 필요한 높이. */
	private int requiredHeight(int icon) {
		int tallest = 40;
		for (Card card : cards) {
			tallest = Math.max(tallest, card.height(this.font, icon));
		}
		return tallest;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		int centerX = this.width / 2;
		if (showingResult()) {
			// 정해진 카드 하나만 남기는 김에 나머지 화면도 가라앉힌다. 뒤에서 돌아가는 게임
			// 화면이 그대로 밝으면 카드가 묻힌다.
			graphics.fill(0, 0, this.width, this.height, RESULT_VEIL);
			if (hasDeadline()) {
				renderResumeCountdown(graphics, centerX);
			}
		} else if (hasDeadline()) {
			renderCountdown(graphics, centerX);
		}
		graphics.centeredText(this.font, this.title, centerX, titleY, TEXT_MAIN);
		graphics.centeredText(this.font, subtitle(), centerX, subtitleY, subtitleColor());

		// 안 고른 카드를 먼저 그린다. 내려가는 길이 가운데로 옮겨 온 카드와 겹치는데, 나중에
		// 그린 것이 위에 오므로 이 순서가 아니면 떠나는 카드가 강조된 카드를 덮는다.
		renderDismissedCards(graphics, mouseX, mouseY);

		for (int index = 0; index < cards.size(); index++) {
			// 결과를 보여 주는 중에는 정해진 카드 하나만 남긴다. 나머지는 바로 위에서 이미
			// 내려가는 중으로 그렸다.
			if (showingResult() && index != resultIndex) {
				continue;
			}
			renderCard(graphics, index, mouseX, mouseY, 0, 0.0F);
		}

		graphics.centeredText(this.font, footerHint(), centerX, this.height - 14, TEXT_HINT);

		// 왼쪽 세트 판과 툴팁은 결과를 보여 주는 동안 접는다. 그때는 고른 카드 하나만 남기는
		// 화면이라, 어둠 위에 밝은 판이 남으면 눈이 그리로 끌려간다.
		if (!showingResult()) {
			refreshSetPanel();
			renderSetPanel(graphics);
			// 「보유 증강」 모달이 떠 있으면 툴팁은 띄우지 않는다. 모달 뒤에서 튀어나온다.
			if (!ownedOpen) {
				renderSetTooltip(graphics, mouseX, mouseY);
			}
		}
		refreshOwnedButton();
		// 「보유 증강」은 모달이다 — 뒤의 화면을 <b>통째로</b> 한 겹 가라앉힌 뒤 그 위에 띄운다.
		// 어둠이 카드와 세트 판을 함께 덮어야 「지금은 읽는 중」이라는 것이 전해진다. 그동안
		// 카드는 눌러도 안 골라지므로, 눌릴 것처럼 보이면 안 된다.
		if (ownedOpen) {
			graphics.fill(0, 0, this.width, this.height, OWNED_VEIL);
			ownedPanel.render(graphics, this.font);
		}

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * 화면 왼쪽의 「지금 켜진 세트」 판.
	 *
	 * <p>여기는 {@link #extractRenderState} 안이므로 <b>배경 흐림보다 뒤에 그려진다</b> —
	 * 판은 또렷하다. 자세한 것은 이 클래스 머리글에 적어 두었다.
	 *
	 * <p>켜진 줄과 아직인 줄을 색으로 가르되, 마름모 표({@link PerkSetLines#ACTIVE_MARK})가
	 * 이미 글자 앞에 붙어 있다. 색을 못 가리는 사람에게도 뜻이 남아야 한다.
	 */
	private void renderSetPanel(GuiGraphicsExtractor graphics) {
		if (!panel.visible()) {
			return;
		}
		graphics.fill(panel.left(), panel.top(), panel.right(), panel.bottom(), PANEL_BACKGROUND);
		graphics.outline(panel.left(), panel.top(), panel.right() - panel.left(),
				panel.bottom() - panel.top(), PANEL_BORDER);
		if (panel.header()) {
			graphics.text(this.font, PANEL_TITLE, panel.contentLeft(), panel.headerY(), TEXT_HINT);
		}
		for (int index = 0; index < panel.rowCount() && index < panelLines.size(); index++) {
			PerkSetLines.Line line = panelLines.get(index);
			graphics.text(this.font, line.text(), panel.contentLeft(), panel.rowY(index),
					line.active() ? PANEL_ACTIVE : PANEL_IDLE);
		}
	}

	/**
	 * 마우스가 올라간 세트의 단계 설명을 띄운다.
	 *
	 * <p>뜨는 자리가 둘이다 — <b>왼쪽 판의 세트 줄</b>과 <b>카드의 세트 유형 줄</b>. 둘은 겹칠
	 * 수 없게 자리를 잡아 두었으므로 먼저 맞는 쪽에서 끝낸다.
	 */
	private void renderSetTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int row = panel.rowAt(mouseX, mouseY);
		if (row >= 0 && row < panelLines.size()) {
			showSetTooltip(graphics, List.of(panelLines.get(row).typeId()), mouseX, mouseY);
			return;
		}
		renderCardSetTypeTooltip(graphics, mouseX, mouseY);
	}

	/**
	 * 카드의 세트 유형 줄에 마우스를 올렸는지 본다.
	 *
	 * <p>판정은 <b>움직이지 않는 자리</b>(cardTop 기준)로 한다. 카드를 클릭할 때와 같다 —
	 * 떠오른 위치로 재면 카드가 올라가는 순간 마우스가 밖으로 빠져 툴팁이 깜빡인다. 그래서
	 * 호버로 카드가 2픽셀 뜨는 동안에는 글자와 판정 자리가 그만큼 어긋나 있는데, 줄 높이가
	 * 아홉이라 눈에 잡히지 않는다.
	 *
	 * <p>유형 줄까지의 거리는 {@link PerkCardSetTypes#rowsTop} 이 잰다. <b>카드에 그리는 차례를
	 * 바꾸면 그 계산도 함께 고쳐야 한다.</b>
	 */
	private void renderCardSetTypeTooltip(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int lineHeight = this.font.lineHeight;
		PerkCardMetrics cardMetrics = metrics(lineHeight);
		for (int index = 0; index < cards.size(); index++) {
			Card card = cards.get(index);
			if (card.setTypeLines().isEmpty() || card.setTypeIds().isEmpty()) {
				continue;
			}
			int left = cardLeft(index);
			int top = cardTop
					+ PerkCardSetTypes.rowsTop(cardMetrics, iconSize, card.nameLines().size());
			if (!PerkCardSetTypes.hovered(mouseX, mouseY, left + cardWidth / 2,
					card.setTypeWidth(), top, lineHeight, card.setTypeLines().size())) {
				continue;
			}
			showSetTooltip(graphics, card.setTypeIds(), mouseX, mouseY);
			return;
		}
	}

	/**
	 * 유형 하나(또는 둘)의 단계 설명을 툴팁으로 띄운다.
	 *
	 * <p>담기는 내용은 {@link PerkSetTooltipLines} 가 정한다. 여기서는 색을 붙여
	 * {@code Component} 로 옮기기만 한다 — 그래야 팀 화면과 이 화면이 같은 말을 한다.
	 *
	 * <p>줄이 하나도 안 나오면 아무것도 띄우지 않는다. 세트 패킷이 아직 안 왔거나 서버가 모르는
	 * 유형을 보낸 때인데, 빈 툴팁이 뜨면 고장으로 읽힌다.
	 */
	private void showSetTooltip(GuiGraphicsExtractor graphics, List<String> typeIds, int mouseX,
			int mouseY) {
		int limit = typeIds.size() > 1 ? MULTI_TYPE_MISSING_ROWS : PerkSetTooltip.MAX_ROWS;
		List<Component> tooltip = new ArrayList<>();
		for (String typeId : typeIds) {
			List<PerkSetTooltipLines.Row> rows = PerkSetTooltipLines.build(
					ClientPerkSets.tooltip(typeId), ClientPerkSets.displayName(typeId), limit,
					TOOLTIP_PALETTE);
			if (rows.isEmpty()) {
				continue;
			}
			if (!tooltip.isEmpty()) {
				tooltip.add(Component.empty());
			}
			for (PerkSetTooltipLines.Row line : rows) {
				tooltip.add(line.blank() ? Component.empty()
						: Component.literal(line.text()).withColor(rgb(line.color())));
			}
		}
		if (tooltip.isEmpty()) {
			return;
		}
		graphics.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
	}

	/**
	 * 0xAARRGGBB 색에서 알파를 뗀다.
	 *
	 * <p>{@code MutableComponent.withColor} 는 24비트 색만 받는다. 알파를 남긴 채 넘기면
	 * 상위 바이트가 색값에 섞여 <b>엉뚱한 색으로 뜬다.</b>
	 */
	private static int rgb(int argb) {
		return argb & 0xFFFFFF;
	}

	/**
	 * 남은 시간을 크게 그린다.
	 *
	 * <p>글자 확대는 {@code pose()} 에 배율을 쌓아서 한다. 텍스트는 그려질 때 현재 pose 를 그대로
	 * 복사해 가므로 push/scale/popMatrix 사이에서 그리면 확대된 상태로 남는다.
	 */
	private void renderCountdown(GuiGraphicsExtractor graphics, int centerX) {
		int remaining = remainingSeconds();
		boolean urgent = remaining <= URGENT_SECONDS;
		Component label = remaining > 0
				? Component.literal("남은 시간 " + remaining + "초")
				: Component.literal("시간 초과");

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, timerY);
		pose.scale(TIMER_SCALE, TIMER_SCALE);
		graphics.centeredText(this.font, label, 0, 0, urgent ? TIMER_URGENT : TIMER_CALM);
		pose.popMatrix();

		renderTimerBar(graphics, centerX, remainingFraction(),
				urgent ? TIMER_BAR_FILL_URGENT : TIMER_BAR_FILL);
	}

	/**
	 * 게임이 다시 시작되기까지 남은 초를 크게 그린다.
	 *
	 * <p>고른 카드를 보여 주는 시간은 <b>이미 시간이 멈춰 있는 시간</b>이고, 그 시간을 그대로
	 * 카운트다운으로 보여 준다. 시간 정지와 무적이 도는 길이는 하나도 바뀌지 않는다.
	 *
	 * <p>글자에 숫자를 박아 두지 않는다. 서버가 보낸 {@code holdTicks} 를 초로 바꿔 세므로
	 * {@code RESULT_TICKS} 를 고치면 이 문구가 저절로 따라간다.
	 */
	private void renderResumeCountdown(GuiGraphicsExtractor graphics, int centerX) {
		int seconds = resumeSeconds();
		Component label = seconds > 0
				? Component.literal(seconds + "초 뒤 다시 시작합니다")
				: Component.literal("다시 시작합니다");

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(centerX, timerY);
		pose.scale(TIMER_SCALE, TIMER_SCALE);
		graphics.centeredText(this.font, label, 0, 0, RESUME_TEXT);
		pose.popMatrix();

		renderTimerBar(graphics, centerX, resumeFraction(), RESUME_BAR_FILL);
	}

	/** 카운트다운 글자 아래의 남은 시간 막대. 제한시간과 재개 카운트다운이 함께 쓴다. */
	private void renderTimerBar(GuiGraphicsExtractor graphics, int centerX, float fraction,
			int fill) {
		int barTop = timerY + this.font.lineHeight * TIMER_SCALE + 3;
		int barWidth = Math.max(60, Math.min(240, this.width - SCREEN_MARGIN * 2));
		int barLeft = centerX - barWidth / 2;
		graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + TIMER_BAR_HEIGHT,
				TIMER_BAR_BACKGROUND);
		int filled = (int) (barWidth * Math.clamp(fraction, 0.0F, 1.0F));
		if (filled > 0) {
			graphics.fill(barLeft, barTop, barLeft + filled, barTop + TIMER_BAR_HEIGHT, fill);
		}
	}

	/**
	 * 안 고른 카드를 아래로 미끄러뜨린다.
	 *
	 * <p>제자리에서 출발해 화면 아래끝을 지날 때까지 가속하며 내려가고, 내려가는 동안 어둠에
	 * 가라앉는다. 걸리는 시간과 곡선은 {@link PerkCardDismiss} 에 있다 — 결과 시간의 앞머리에서
	 * 끝나므로 뒤에는 고른 카드만 남은 화면이 충분히 남는다.
	 *
	 * <p>결과 화면이 아니면 아무것도 하지 않는다.
	 */
	private void renderDismissedCards(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		if (!showingResult()) {
			return;
		}
		long elapsed = resultElapsedMillis();
		// 카드 윗변이 화면 아래끝에 닿을 만큼 내려보낸다. 도중에 잘려 사라지므로 따로
		// 지워 줄 필요가 없다.
		int travel = Math.max(0, this.height - cardTop) + DISMISS_OVERSHOOT;
		for (int index = 0; index < cards.size(); index++) {
			if (index == resultIndex || PerkCardDismiss.gone(elapsed, index)) {
				continue;
			}
			int slide = PerkCardDismiss.offset(elapsed, index, travel);
			// 윗변이 화면 밖으로 나가면 그릴 것이 없다. 여기서 끊지 않으면 화면 밖 좌표로
			// 잘라내기(scissor)를 걸게 된다.
			if (cardTop + slide >= this.height) {
				continue;
			}
			renderCard(graphics, index, mouseX, mouseY, slide,
					PerkCardDismiss.shade(elapsed, index));
		}
	}

	/**
	 * 카드 한 장을 그린다.
	 *
	 * <p>호버 판정과 클릭 판정은 <b>움직이지 않는 자리</b>(cardTop 기준)로 한다. 떠오른 위치로
	 * 판정하면 카드가 올라가는 순간 마우스가 밖으로 빠져 깜빡이기 때문이다. 그리는 위치만
	 * 위아래로 흔든다.
	 *
	 * @param slide 제자리에서 아래로 밀어 둘 거리. 탈락해 내려가는 카드에만 0이 아니다
	 * @param shade 카드 위에 덮을 어둠의 세기 0.0~1.0. 0이면 덮지 않는다
	 */
	private void renderCard(GuiGraphicsExtractor graphics, int index, int mouseX, int mouseY,
			int slide, float shade) {
		Card card = cards.get(index);
		// 정해진 카드는 혼자 남으므로 제자리에 두면 세 칸 중 한쪽에 치우쳐 보인다. 가운데로
		// 옮기되 순간이동시키지 않고 미끄러뜨린다(PerkCardFocus).
		boolean highlighted = showingResult() && index == resultIndex;
		int left = highlighted
				? PerkCardFocus.left(resultElapsedMillis(), cardLeft(index),
						(this.width - cardWidth) / 2)
				: cardLeft(index);
		int right = left + cardWidth;
		// 「보유 증강」 판이 펴져 있으면 눌러도 안 골라진다. 그동안은 호버도 끈다 — 밝아지는
		// 카드는 「지금 누르면 된다」는 뜻이라 거짓말이 된다.
		// 표를 던질 수 있는 사람에게도 카드가 밝아진다. 눌러도 되는 카드인 것은 같고, 이쪽은
		// 고르는 대신 제안이 될 뿐이다.
		boolean hovered = (clickable() || votable()) && !ownedOpen
				&& isInside(mouseX, mouseY, left, right, cardTop + cardHeight);
		// 강조한 카드는 호버와 같은 밝기를 쓰되, 아래의 빛과 두 겹 테두리로 한 단계 더 올린다.
		boolean bright = hovered || highlighted;

		int top = cardTop + entryOffset(index) - (hovered ? HOVER_LIFT : 0) + slide;
		int bottom = top + cardHeight;
		int rarity = card.rarityColor();

		if (highlighted) {
			renderHighlightGlow(graphics, left, top, rarity);
		}

		// 등급색을 살짝 섞은 세로 그라데이션. 위가 밝고 아래로 가라앉는다.
		graphics.fillGradient(left, top, right, bottom,
				mix(bright ? CARD_TOP_HOVER : CARD_TOP, rarity,
						bright ? CARD_TINT_HOVER : CARD_TINT),
				mix(bright ? CARD_BOTTOM_HOVER : CARD_BOTTOM, rarity,
						bright ? CARD_TINT_HOVER / 2.0F : CARD_TINT / 2.0F));

		// 등급 띠. 카드 맨 위를 등급색으로 가득 채운다.
		// 프리즘만은 이름값을 하도록 무지개로 흘린다.
		if (card.rarity() == PerkRarity.PRISM) {
			renderPrismBand(graphics, left, top, right, bright ? 0xFF : 0xDC);
		} else {
			graphics.fill(left, top, right, top + BAND_HEIGHT,
					bright ? rarity : withAlpha(rarity, 0xDC));
		}

		int border = bright ? brighten(rarity, BORDER_BRIGHTEN_HOVER) : rarity;
		graphics.outline(left, top, cardWidth, cardHeight, border);
		if (bright) {
			// 마우스를 올린 카드와 정해진 카드는 테두리를 두 겹으로 그려 강조한다.
			graphics.outline(left + 1, top + 1, cardWidth - 2, cardHeight - 2, border);
		}

		// 카드가 세로로 잘린 경우 내용이 카드 밖으로 삐져나오지 않게 자른다.
		// 내려가는 카드는 아랫변이 화면 밖까지 가므로 화면 안으로 한 번 눌러 둔다. 위아래가
		// 뒤집힌 범위를 넘기면 안 되므로, 남은 자리가 없으면 내용은 아예 건너뛴다.
		int clipBottom = Math.min(bottom - 1, this.height);
		if (clipBottom <= top + 1) {
			return;
		}
		graphics.enableScissor(left + 1, top + 1, right - 1, clipBottom);
		int textCenterX = left + cardWidth / 2;
		int bandTextY = top + (BAND_HEIGHT - this.font.lineHeight + 1) / 2;
		graphics.centeredText(this.font, card.rarityLabel(), textCenterX, bandTextY, BAND_TEXT);
		// 표는 등급 글자를 그린 뒤 그 오른쪽에 얹는다. 등급 글자가 가운데 정렬이라 카드가
		// 가장 좁을 때(56픽셀)도 오른쪽 끝은 비어 있다.
		renderVoteMark(graphics, index, right, top, bandTextY);

		int y = top + BAND_HEIGHT + ICON_GAP_TOP;
		if (iconSize > 0) {
			renderIcon(graphics, card.icon(), textCenterX - iconSize / 2, y);
			y += iconSize + ICON_GAP_BOTTOM;
		}
		for (FormattedCharSequence line : card.nameLines()) {
			graphics.centeredText(this.font, line, textCenterX, y, TEXT_MAIN);
			y += this.font.lineHeight;
		}
		// 세트 유형. 이름 바로 아래, 구분선 위다 — 「이 증강이 무엇에 속하는가」는 이름의 일부처럼
		// 읽혀야지 설명에 섞이면 안 된다. 유형이 없는 증강(열여섯 개)에는 이 자리가 아예 없다.
		if (!card.setTypeLines().isEmpty()) {
			y += SET_TYPE_GAP;
			for (FormattedCharSequence line : card.setTypeLines()) {
				graphics.centeredText(this.font, line, textCenterX, y, TEXT_SET_TYPE);
				y += this.font.lineHeight;
			}
		}
		y += 3;
		graphics.fill(left + CARD_PADDING, y, right - CARD_PADDING, y + 1, SEPARATOR);
		y += 5;
		for (FormattedCharSequence line : card.descriptionLines()) {
			graphics.text(this.font, line, left + CARD_PADDING, y, TEXT_SUB);
			y += this.font.lineHeight;
		}
		graphics.disableScissor();

		// 내려가는 카드는 마지막에 어둠 한 겹을 덮는다. 잘라내기 밖에서 테두리까지 함께
		// 덮어야 배경만 어두워지고 테두리만 남는 그림이 되지 않는다.
		if (shade > 0.0F) {
			graphics.fill(left, top, right, bottom,
					withAlpha(DISMISS_SHADE, Math.round(0xFF * Math.clamp(shade, 0.0F, 1.0F))));
		}
	}

	/**
	 * 카드에 모인 표를 등급 띠 오른쪽 끝에 그린다.
	 *
	 * <p>{@code renderCard} 가 걸어 둔 잘라내기 안이라 카드 밖으로 새지 않는다. 바탕을 한 겹
	 * 깔고 그 위에 체크를 얹는 것은 등급 띠가 저마다 밝은 색이고 프리즘은 아예 무지개여서다.
	 *
	 * <p>내가 던진 표가 섞여 있으면 초록으로 그린다. 「내 표가 어디 있는지」가 안 보이면
	 * 취소하려고 누른 것이 오히려 옮기는 일이 된다.
	 *
	 * <p>강제로 띄운 창이 아니면 아무것도 안 그린다. {@code /shareteam perk} 로 직접 연 창은
	 * 혼자 보는 확인용이라 표가 있을 수 없다 — 서버가 그 경로로는 표 묶음을 보내지 않는다.
	 */
	private void renderVoteMark(GuiGraphicsExtractor graphics, int index, int right, int top,
			int textY) {
		if (!forced || showingResult() || index >= options.size()) {
			return;
		}
		String target = options.get(index).id();
		String mark = PerkVoteBoard.mark(voteCount(target));
		if (mark.isEmpty()) {
			return;
		}
		int markRight = right - VOTE_MARK_INSET;
		int markLeft = markRight - this.font.width(mark) - VOTE_MARK_PADDING * 2;
		graphics.fill(markLeft, top + 1, markRight, top + BAND_HEIGHT - 1, VOTE_MARK_BACKGROUND);
		graphics.text(this.font, mark, markLeft + VOTE_MARK_PADDING, textY,
				target.equals(ownVote) ? VOTE_MARK_OWN : VOTE_MARK_OTHER);
	}

	/**
	 * 정해진 카드 둘레에 등급색 빛을 두른다.
	 *
	 * <p>테두리를 한 겹씩 바깥으로 넓혀 가며 투명도를 낮춰 그린다. 진짜 번짐 효과는 셰이더가
	 * 있어야 하지만, 겹을 넷만 쌓아도 카드가 화면에서 떠오르는 것처럼 보인다.
	 *
	 * <p>세기는 천천히 오르내린다.
	 *
	 * <p>카드가 가운데로 오는 동안에는 빛도 함께 짙어진다. 옮겨 오는 첫 프레임부터 다 켜 두면
	 * 빛만 먼저 튀어 카드가 어디서 왔는지 가린다.
	 */
	private void renderHighlightGlow(GuiGraphicsExtractor graphics, int left, int top, int rarity) {
		float phase = (System.currentTimeMillis() % HIGHLIGHT_PULSE_MILLIS)
				/ (float) HIGHLIGHT_PULSE_MILLIS;
		float pulse = 0.5F - 0.5F * (float) Math.cos(phase * 2.0 * Math.PI);
		float strength = (HIGHLIGHT_GLOW_MIN + (HIGHLIGHT_GLOW_MAX - HIGHLIGHT_GLOW_MIN) * pulse)
				* PerkCardFocus.progress(resultElapsedMillis());
		for (int layer = 1; layer <= HIGHLIGHT_GLOW_LAYERS; layer++) {
			float fade = (float) (HIGHLIGHT_GLOW_LAYERS + 1 - layer) / (HIGHLIGHT_GLOW_LAYERS + 1);
			int alpha = Math.round(0xFF * fade * strength);
			graphics.outline(left - layer, top - layer,
					cardWidth + layer * 2, cardHeight + layer * 2, withAlpha(rarity, alpha));
		}
	}

	/**
	 * 아이템 아이콘을 {@code iconSize} 크기로 그린다.
	 *
	 * <p>{@code item()} 은 언제나 16×16 이라 카운트다운과 같은 방법으로 pose 에 배율을 쌓아
	 * 키운다. 확대 뒤 좌표는 배율로 나눈 값이어야 하므로 translate 로 먼저 옮겨 두고 0,0 에 그린다.
	 */
	private void renderIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		if (stack.isEmpty()) {
			return;
		}
		int scale = Math.max(1, iconSize / ICON_UNIT);
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(scale, scale);
		graphics.item(stack, 0, 0);
		pose.popMatrix();
	}

	/**
	 * 창이 열린 직후 카드를 아래로 밀어 둘 거리. 시간이 지나면서 0으로 줄어든다.
	 *
	 * <p>왼쪽부터 짧게 시차를 두고 올라온다.
	 */
	private int entryOffset(int index) {
		if (!entryAnimated) {
			return 0;
		}
		long elapsed = System.currentTimeMillis() - openedAtMillis - index * ENTRY_STAGGER_MILLIS;
		if (elapsed >= ENTRY_DURATION_MILLIS) {
			return 0;
		}
		if (elapsed <= 0L) {
			return ENTRY_RISE;
		}
		float progress = (float) elapsed / (float) ENTRY_DURATION_MILLIS;
		float remaining = 1.0F - progress;
		// ease-out: 처음에 빠르게 올라오고 끝에서 부드럽게 멈춘다.
		return Math.round(ENTRY_RISE * remaining * remaining * remaining);
	}

	/**
	 * 카드를 고르는 버튼인가.
	 *
	 * <p><b>숫자를 박으면 안 된다.</b> 26.3 이 GLFW 를 SDL 로 바꾸면서 버튼 번호가 통째로
	 * 달라졌다 — 왼쪽이 {@code 0} 에서 {@code 1} 로, 오른쪽이 {@code 1} 에서 {@code 3} 으로
	 * 갔다. 예전에 {@code button == 0} 으로 적어 둔 탓에 26.3 에서 <b>카드가 한 번도 눌리지
	 * 않았다.</b> 빌드도 시험도 통과하고 화면도 멀쩡히 떠서, 눌러 봐야만 아는 실패였다.
	 *
	 * <p>키 코드도 같이 바뀌었다({@code KEY_ESCAPE} 가 256 → 41). 입력 값은 언제나
	 * {@link InputConstants} 를 거쳐 쓴다.
	 *
	 * <p>순수 함수로 떼어 둔 것은 {@code PerkOfferScreenClickTest} 가 붙들기 위해서다.
	 */
	static boolean isSelectClick(int button) {
		return button == InputConstants.MOUSE_BUTTON_LEFT;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 겹판이 펴져 있으면 그 위의 클릭은 뒤로 새지 않는다. 목록을 보려고 누른 것이
		// 카드 선택이 되어 버리면 되돌릴 방법이 없다.
		if (ownedOpen && ownedPanel.contains(event.x(), event.y())) {
			return true;
		}
		// 카드를 누르는 뜻이 사람에 따라 다르다 — 선택자는 고르고, 나머지는 제안한다.
		// 판정 자리는 같으므로 훑는 것은 한 번이다.
		if ((clickable() || votable()) && !ownedOpen && isSelectClick(event.button())) {
			for (int index = 0; index < cards.size(); index++) {
				int left = cardLeft(index);
				if (isInside(event.x(), event.y(), left, left + cardWidth,
						cardTop + cardHeight)) {
					if (clickable()) {
						choose(index);
					} else if (index < options.size()) {
						castVote(options.get(index).id());
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (ownedOpen && ownedPanel.contains(mouseX, mouseY) && ownedPanel.scroll(scrollY)) {
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/**
	 * 선택을 서버로 보낸다. 검증은 서버가 다시 한다.
	 *
	 * <p>강제 오픈이면 여기서 창을 닫지 않는다. 서버가 선택을 거절했는데 창만 사라지면 시간이
	 * 멈춘 채로 아무것도 할 수 없게 된다. 닫는 것은 서버의 몫이다.
	 */
	private void choose(int index) {
		PerkOfferPayload.PerkOption option = options.get(index);
		if (ClientPlayNetworking.canSend(PerkChoiceC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new PerkChoiceC2SPayload(milestone, option.id()));
		}
		if (forced) {
			choiceSent = true;
			return;
		}
		this.onClose();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		// 겹판이 펴져 있으면 ESC 는 겹판만 접는다. 창까지 함께 닫히면 「목록을 보다가 창을
		// 잃는」 일이 생긴다.
		if (ownedOpen) {
			return false;
		}
		// 강제로 띄운 창은 ESC 로 닫을 수 없다. /shareteam perk 로 직접 연 창은 닫힌다.
		// 마감이 한참 지나도록 서버가 닫아 주지 않으면 그때는 열어 준다. 갇히는 것보다 낫다.
		return !forced || escapable();
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (ownedOpen && event.key() == InputConstants.KEY_ESCAPE) {
			toggleOwnedPanel();
			return true;
		}
		return super.keyPressed(event);
	}

	/** 서버가 닫아 주지 못했을 때 스스로 빠져나갈 수 있는 시점인지. */
	private boolean escapable() {
		if (deadlineMillis <= 0L) {
			// 마감을 알 수 없는 강제 창이다. 언제 풀릴지 알 수 없으니 잠그지 않는다.
			return true;
		}
		return System.currentTimeMillis() > deadlineMillis + ESCAPE_GRACE_MILLIS;
	}

	@Override
	public boolean isPauseScreen() {
		// 다른 팀원이 관전하는 동안에도 게임은 계속 돌아가야 한다.
		return false;
	}

	private boolean clickable() {
		return canChoose && !choiceSent && !showingResult();
	}

	private boolean hasDeadline() {
		return forced && deadlineMillis > 0L;
	}

	/** 마감까지 남은 초. 이미 지났으면 0. */
	private int remainingSeconds() {
		long left = deadlineMillis - System.currentTimeMillis();
		if (left <= 0L) {
			return 0;
		}
		return (int) ((left + 999L) / 1000L);
	}

	/**
	 * 게임이 다시 시작되기까지 남은 초. 서버가 보낸 {@code holdTicks} 를 그대로 세어 5 → 1 로
	 * 떨어진다. <b>숫자를 박아 두지 않으므로</b> {@code RESULT_TICKS} 를 고치면 저절로 따라간다.
	 *
	 * <p>올림이라 100틱에서 5가 뜨고 마지막 1틱까지 1이 남는다. 내림으로 하면 시작하자마자
	 * 4가 뜨고 마지막 1초가 0으로 보인다.
	 */
	private int resumeSeconds() {
		return (resultTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
	}

	/** 재개 카운트다운 막대의 채움 비율 0.0~1.0. */
	private float resumeFraction() {
		if (resultTotalTicks <= 0) {
			return 0.0F;
		}
		return (float) resultTicks / (float) resultTotalTicks;
	}

	/** 남은 시간 막대의 채움 비율 0.0~1.0. */
	private float remainingFraction() {
		if (totalMillis <= 0L) {
			return 0.0F;
		}
		long left = deadlineMillis - System.currentTimeMillis();
		if (left <= 0L) {
			return 0.0F;
		}
		return Math.min(1.0F, (float) left / (float) totalMillis);
	}

	private int cardLeft(int index) {
		return firstCardLeft + index * (cardWidth + CARD_GAP);
	}

	private boolean isInside(double x, double y, int left, int right, int bottom) {
		return x >= left && x < right && y >= cardTop && y < bottom;
	}

	private int subtitleColor() {
		if (choiceSent) {
			return TEXT_WAITING;
		}
		return canChoose ? TEXT_SUB : TEXT_SPECTATE;
	}

	private Component subtitle() {
		if (showingResult()) {
			return resultChooser.isEmpty()
					? Component.literal("시간이 다 되어 무작위로 정해졌습니다")
					: Component.literal(resultChooser + "님이 골랐습니다");
		}
		if (choiceSent) {
			return Component.literal("선택을 보냈습니다. 잠시만 기다리십시오");
		}
		if (canChoose) {
			if (roundRarity != null) {
				// 어떤 구간에서 무슨 등급을 고르는 중인지 한 줄로 알려 준다.
				return Component.literal("공유 레벨 " + milestone + " 달성 · "
						+ roundRarity.displayName() + " 라운드");
			}
			return Component.literal(
					"공유 레벨 " + milestone + " 달성 · 증강 하나를 고르세요");
		}
		String chooser = PerkClientState.chooserName();
		if (chooser.isEmpty()) {
			chooser = "팀원";
		}
		if (!votable()) {
			// 관전자이거나 결과가 뜬 뒤다. 누를 것이 없으므로 예전 그대로 적는다.
			return Component.literal(chooser + "님이 고르는 중입니다 (관전 중)");
		}
		if (this.width < 320) {
			return Component.literal(chooser + "님이 고르는 중 · 눌러서 제안");
		}
		return Component.literal(chooser + "님이 고르는 중입니다 · 눌러서 제안할 수 있습니다");
	}

	private Component footerHint() {
		if (showingResult()) {
			// 위쪽 카운트다운은 강제 오픈일 때만 자리가 있다. 여기서 한 번 더 적어,
			// 어떤 경로로 열린 창이든 언제 게임이 돌아오는지 알 수 있게 한다.
			int seconds = resumeSeconds();
			return Component.literal(seconds > 0
					? "팀 전체에 적용됩니다 · " + seconds + "초 뒤 다시 시작합니다"
					: "팀 전체에 적용됩니다 · 다시 시작합니다");
		}
		if (forced) {
			if (escapable()) {
				return Component.literal("응답이 없습니다 · ESC로 닫을 수 있습니다");
			}
			// 표를 던질 수 있는 사람에게는 무엇보다 이 말이 먼저다. 체크가 쌓이는 것을 보고
			// 「표가 모이면 그걸로 정해진다」고 읽으면, 정작 선택자가 다른 것을 골랐을 때
			// 모드가 고장 난 것으로 여기게 된다. 시간 정지와 무적은 아래 문구가 아니어도
			// 화면 위 카운트다운이 이미 말하고 있다.
			if (votable()) {
				return Component.literal(this.width < 320
						? "제안일 뿐 · 고르는 것은 선택자"
						: "표는 제안일 뿐입니다 · 실제로 고르는 것은 선택자 한 사람입니다");
			}
			if (this.width < 320) {
				return Component.literal("시간 정지 중 · 피해 무효");
			}
			return Component.literal(
					"시간이 멈췄고 팀 전원이 무적입니다 · 시간이 다 되면 무작위로 선택됩니다");
		}
		if (this.width < 320) {
			return Component.literal("ESC · /shareteam perk 로 다시 열기");
		}
		return Component.literal(
				"ESC로 닫아도 선택권은 남습니다 · /shareteam perk 로 다시 열 수 있습니다");
	}

	/** 후보 목록에서 이 라운드의 등급을 집어낸다. 한 라운드는 등급 하나로만 채워진다. */
	private static @Nullable PerkRarity firstRarity(List<PerkOfferPayload.PerkOption> options) {
		for (PerkOfferPayload.PerkOption option : options) {
			PerkRarity rarity = PerkRarity.fromId(option.rarity());
			if (rarity != null) {
				return rarity;
			}
		}
		return null;
	}

	private static String rarityLabel(PerkRarity rarity) {
		return rarity == null ? PerkRarity.SILVER.displayName() : rarity.displayName();
	}

	private static int rarityColor(PerkRarity rarity) {
		if (rarity == null) {
			return COLOR_SILVER;
		}
		return switch (rarity) {
			case SILVER -> COLOR_SILVER;
			case GOLD -> COLOR_GOLD;
			case PRISM -> COLOR_PRISM;
		};
	}

	/**
	 * 증강이 지정한 아이콘 아이템을 찾는다.
	 *
	 * <p>서버가 이미 걸러서 보내지만, 서버에만 있는 모드 아이템이거나 클라이언트에서
	 * 이름이 바뀐 경우가 있을 수 있다. 못 찾으면 등급별 기본 아이콘으로 조용히 대체한다.
	 * 아이콘 하나 때문에 카드가 비어 보이면 안 된다.
	 */
	private static ItemStack iconStack(String iconId, PerkRarity rarity) {
		Item item = lookupItem(iconId);
		return new ItemStack(item == null ? defaultIcon(rarity) : item);
	}

	private static @Nullable Item lookupItem(String iconId) {
		if (iconId == null || iconId.isBlank()) {
			return null;
		}
		try {
			Identifier id = Identifier.tryParse(iconId.trim());
			if (id == null) {
				return null;
			}
			Optional<Holder.Reference<Item>> found = BuiltInRegistries.ITEM.get(id);
			if (found.isEmpty()) {
				return null;
			}
			// 아이템 레지스트리는 기본값이 공기라 없는 이름도 공기로 돌아올 수 있다.
			Item item = found.get().value();
			return item == Items.AIR ? null : item;
		} catch (Exception error) {
			return null;
		}
	}

	/** 아이콘을 정하지 않은 증강이 쓰는 등급별 기본 아이콘. 금속 등급을 그대로 따른다. */
	private static Item defaultIcon(PerkRarity rarity) {
		if (rarity == null) {
			return Items.IRON_INGOT;
		}
		return switch (rarity) {
			case SILVER -> Items.IRON_INGOT;
			case GOLD -> Items.GOLD_INGOT;
			case PRISM -> Items.DIAMOND;
		};
	}

	/** {@code base} 에 {@code tint} 를 {@code amount} 만큼 섞는다. 투명도는 base 것을 쓴다. */
	private static int mix(int base, int tint, float amount) {
		float ratio = Math.clamp(amount, 0.0F, 1.0F);
		int red = Math.round((base >> 16 & 0xFF) * (1.0F - ratio) + (tint >> 16 & 0xFF) * ratio);
		int green = Math.round((base >> 8 & 0xFF) * (1.0F - ratio) + (tint >> 8 & 0xFF) * ratio);
		int blue = Math.round((base & 0xFF) * (1.0F - ratio) + (tint & 0xFF) * ratio);
		return (base & 0xFF000000) | red << 16 | green << 8 | blue;
	}

	/** 색을 흰색 쪽으로 끌어올린다. 호버한 카드의 테두리를 밝히는 데 쓴다. */
	private static int brighten(int color, float amount) {
		return mix(color, 0xFFFFFFFF, amount);
	}

	/**
	 * 프리즘 등급의 등급 띠를 무지개로 그린다.
	 *
	 * <p>{@code fillGradient} 는 위아래 두 색만 받으므로 가로 무지개를 한 번에 그릴 수 없다.
	 * 띠를 세로로 잘게 나누고 조각마다 이웃한 두 색을 섞어 칠하면 가로로 흐르는 것처럼 보인다.
	 * 등급 띠 하나에만 쓰므로 조각이 늘어도 비용은 무시할 만하다.
	 *
	 * <p>테두리까지 무지개로 하면 카드 경계가 흐려지므로 띠에만 쓴다.
	 */
	private void renderPrismBand(GuiGraphicsExtractor graphics, int left, int top, int right,
			int alpha) {
		int width = right - left;
		if (width <= 0) {
			return;
		}
		int bottom = top + BAND_HEIGHT;
		int steps = Math.min(width, PRISM_BAND_STEPS);
		for (int step = 0; step < steps; step++) {
			int sliceLeft = left + (int) ((long) width * step / steps);
			int sliceRight = left + (int) ((long) width * (step + 1) / steps);
			if (sliceRight <= sliceLeft) {
				continue;
			}
			// 조각 가운데 위치를 무지개 위의 한 점으로 본다.
			float position = (step + 0.5F) / steps * (PRISM_COLORS.length - 1);
			int index = Math.min((int) position, PRISM_COLORS.length - 2);
			int color = withAlpha(
					mix(PRISM_COLORS[index], PRISM_COLORS[index + 1], position - index), alpha);
			graphics.fill(sliceLeft, top, sliceRight, bottom, color);
		}
	}

	private static int withAlpha(int color, int alpha) {
		return (alpha & 0xFF) << 24 | color & 0x00FFFFFF;
	}

	/**
	 * 화면 폭이 정해진 뒤 한 번 계산해 두는 카드 한 장의 표시 내용.
	 *
	 * @param setTypeIds   유형 줄에 적힌 이름을 되돌린 유형 id. <b>툴팁을 물을 때 쓰는 열쇠다.</b>
	 *                     되돌리지 못한 이름은 빠지므로 유형 줄이 있어도 비어 있을 수 있다
	 * @param setTypeWidth 가장 긴 유형 줄의 글자 폭. 마우스를 받는 가로 범위다
	 */
	private record Card(List<FormattedCharSequence> nameLines,
			List<FormattedCharSequence> setTypeLines,
			List<String> setTypeIds,
			int setTypeWidth,
			Component rarityLabel,
			PerkRarity rarity,
			int rarityColor,
			ItemStack icon,
			List<FormattedCharSequence> descriptionLines) {

		static Card of(Font font, PerkOfferPayload.PerkOption option, int innerWidth,
				List<PerkSetLines.Entry> sets) {
			PerkRarity parsed = PerkRarity.fromId(option.rarity());
			// 등급을 못 읽어도 화면은 떠야 하므로 실버로 본다.
			PerkRarity rarity = parsed == null ? PerkRarity.SILVER : parsed;
			// 이름은 굵게 해서 설명과 무게를 벌린다. 굵으면 폭도 늘어나므로 줄바꿈도 굵은 채로 잰다.
			Component name = Component.literal(text(option.name()))
					.withStyle(ChatFormatting.BOLD);
			// 유형이 여럿이면 서버가 이미 「무기·화력」처럼 이어 붙여 보낸다. 좁은 카드에서는
			// 그 한 줄도 넘칠 수 있으므로 이름·설명과 똑같이 폭에 맞춰 접는다.
			String setTypes = text(option.setTypes());
			List<FormattedCharSequence> setTypeLines = setTypes.isEmpty()
					? List.of()
					: font.split(Component.literal(setTypes), innerWidth);
			int setTypeWidth = 0;
			for (FormattedCharSequence line : setTypeLines) {
				setTypeWidth = Math.max(setTypeWidth, font.width(line));
			}
			// 유형 id 는 서버가 이름과 같은 차례로 실어 준다. 이름으로 되짚지 않는 이유는
			// 세트 동기화 패킷이 아직 안 온 순간에는 되짚을 표가 없어 툴팁이 통째로 사라지기
			// 때문이다. 옛 서버가 id 를 안 보내면 빈 문자열이 와서 툴팁만 안 뜬다.
			return new Card(
					font.split(name, innerWidth),
					setTypeLines,
					PerkCardSetTypes.split(option.setTypeIds(),
							PerkOfferPayload.PerkOption.SET_TYPE_JOINER),
					setTypeWidth,
					Component.literal(PerkOfferScreen.rarityLabel(rarity)),
					rarity,
					PerkOfferScreen.rarityColor(rarity),
					PerkOfferScreen.iconStack(option.icon(), rarity),
					font.split(Component.literal(text(option.description())), innerWidth));
		}

		/**
		 * 아이콘을 {@code iconSize} 로 그린다고 할 때 이 카드가 필요로 하는 세로 길이.
		 *
		 * <p><b>{@code renderCard} 가 그리는 것을 하나도 빠짐없이 세야 한다.</b> 여기서 덜 세면
		 * 카드가 그만큼 짧아지고, 카드 안쪽은 {@code enableScissor} 로 잘리므로 <b>설명 마지막
		 * 줄이 소리 없이 사라진다.</b> 세트 유형 줄과 그 위의 틈이 그 자리다.
		 */
		int height(Font font, int iconSize) {
			return metrics(font.lineHeight)
					.height(iconSize, nameLines.size(), setTypeLines.size(),
							descriptionLines.size());
		}

		private static String text(String value) {
			return value == null ? "" : value;
		}
	}
}
