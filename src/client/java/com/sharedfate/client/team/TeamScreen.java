package com.sharedfate.client.team;

import com.sharedfate.client.ClientStatRows;
import com.sharedfate.client.ClientTeamState;
import com.sharedfate.client.perk.ClientPerkSets;
import com.sharedfate.client.perk.PerkClientState;
import com.sharedfate.net.PerkSyncPayload;
import com.sharedfate.team.TeamCreationSettings;
import com.sharedfate.ui.GameStartButton;
import com.sharedfate.ui.PanelScroll;
import com.sharedfate.ui.PerkSetLines;
import com.sharedfate.ui.PerkSetTooltip;
import com.sharedfate.ui.PerkSetTooltipLines;
import com.sharedfate.ui.RuinCoordPlacement;
import com.sharedfate.ui.StatRow;
import com.sharedfate.ui.TeamDisbandWarning;
import com.sharedfate.ui.TeamNameInput;
import com.sharedfate.ui.TeamCreationCycle;
import com.sharedfate.ui.TeamCreationFlow;
import com.sharedfate.ui.TeamCreationTooltips;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * {@code /shareteam} 로 여는 팀 화면.
 *
 * <p>탭 넷으로 나뉜다. <b>현황</b>은 누구나, <b>팀</b>은 만들기·초대·탈퇴, <b>설정</b>은 리더만,
 * <b>증강</b>은 지금 보유한 증강을 보여 준다.
 *
 * <p>「증강」 탭은 위에서부터 <b>머리글 → 세트 → 증강 목록</b> 순서다. 세트 줄에 마우스를
 * 올리면 그 유형에서 <b>아직 안 가진 증강</b>이 툴팁으로 뜬다 — 클라이언트는 증강 풀을 읽지
 * 않으므로 그 목록도 서버가 보내 준 것이다({@code PerkSetSyncPayload}).
 *
 * <p>이 화면이 하는 일은 전부 이미 있는 {@code /shareteam ...} 명령으로 표현된다. 그래서 단추를
 * 누르면 {@link net.minecraft.client.multiplayer.ClientPacketListener#sendCommand(String)} 로
 * 그 명령을 보낸다. 권한 검사와 실패 문구는 <b>서버 명령 쪽 한 곳에만</b> 있다.
 *
 * <p>보여 줄 값은 이미 계속 오고 있는 {@code TeamSyncPayload}·{@code PerkSyncPayload} 가 채운
 * {@link ClientTeamState}·{@link PerkClientState} 에서 읽는다. 창을 열 때 서버에 따로 묻지 않는다.
 *
 * <h2>화면이 스스로 갱신되는 방식</h2>
 * <p>명령을 보낸 결과는 서버가 다시 보내 주는 동기화 묶음으로 들어온다. 몇 틱 뒤의 일이라
 * 단추를 누른 순간에는 아직 옛 값이다. 그래서 {@link #tick()} 마다 팀 상태의 요약을 견주어
 * 달라졌을 때만 위젯을 다시 만든다. 매 틱 다시 만들면 글자 입력 칸의 커서가 튄다.
 *
 * <p>「팀 만들기」도 같은 길을 탄다 — 명령만 보내고 창은 열어 둔 채 기다리다가, 팀이 생겼다는
 * 동기화가 오면 그 자리에서 팀원 목록과 「게임 시작」이 있는 화면으로 바뀐다
 * ({@link com.sharedfate.ui.TeamCreationFlow}). <b>서버가 이 창을 닫지 않는 것이 전제다</b> —
 * {@code closeContainer()} 의 닫기 패킷은 컨테이너와 아무 상관 없는 이 창까지 함께 없앤다
 * ({@code InventorySwapper.prepareJoin} 참고).
 */
public class TeamScreen extends Screen {
	private static final int PANEL_TOP = 40;
	private static final int ROW_HEIGHT = 12;
	private static final int BUTTON_HEIGHT = 20;
	private static final int TAB_HEIGHT = 18;
	private static final int PANEL_WIDTH = 300;

	// 능력치 줄과 같은 색을 써야 하므로 StatRow 에서 가져온다. 두 곳에 적으면 갈라진다.
	private static final int TEXT_MAIN = StatRow.COLOR_NEUTRAL;
	private static final int TEXT_DIM = StatRow.COLOR_MASKED;
	private static final int TEXT_GOOD = StatRow.COLOR_GOOD;
	private static final int TEXT_WARN = 0xFFFFD24A;
	/**
	 * 증강 이름 뒤에 붙는 세트 유형 딱지의 색.
	 *
	 * <p>이름보다 흐리다. 유형은 <b>곁들이는 정보</b>지 증강의 이름이 아니라서, 같은 밝기로
	 * 붙이면 어디까지가 이름인지 눈이 못 가른다.
	 */
	private static final int PERK_SET_TYPE = 0xFF7F8A99;
	private static final int PANEL_BG = 0xC0101018;

	/**
	 * 「유적 감별사」 좌표를 세우는 자리의 왼쪽 여백.
	 *
	 * <p>{@link #PANEL_BG} 의 오른쪽 변이 {@code left + PANEL_WIDTH + 6} 이고 스크롤 막대가
	 * {@code +1}~{@code +4} 를 쓴다. 둘을 지난 자리라야 바탕판 밖의 빈 곳에 선다.
	 */
	private static final int RUIN_COORD_GAP = 12;

	/**
	 * 유적 좌표 글자색.
	 *
	 * <p>증강을 처음 골랐을 때 띄운 채팅 한 줄({@code PerkRuinSurvey.announce} 의
	 * {@code ChatFormatting.AQUA})과 같은 물색이다. 같은 값을 두 곳에서 읽는 사람이 「이게 그거」를
	 * 색으로 알아채야 한다.
	 */
	private static final int RUIN_COORD = 0xFF55FFFF;

	/**
	 * 세트 툴팁 줄에 쓸 색.
	 *
	 * <p>줄을 만드는 계산({@link PerkSetTooltipLines})은 이 화면의 색 상수를 몰라야 해서 색을
	 * 밖에서 받는다. 등급색은 {@link #rarityColor} 를 그대로 불러 채운다 — 숫자를 여기에 다시
	 * 적으면 목록의 이름 색과 툴팁의 이름 색이 언젠가 갈라진다.
	 *
	 * <p>켜진 단계는 능력치가 오른 줄과 같은 초록(밝게), 안 켜진 단계는 설명 줄과 같은
	 * 회색(흐리게)이다.
	 */
	private static final PerkSetTooltipLines.Palette TOOLTIP_PALETTE =
			new PerkSetTooltipLines.Palette(TEXT_MAIN, TEXT_GOOD, TEXT_DIM, TEXT_MAIN,
					rarityColor("silver"), rarityColor("gold"), rarityColor("prism"), TEXT_DIM);

	private static final int SCROLL_TRACK = 0x40FFFFFF;
	private static final int SCROLL_THUMB = 0xC0C0C6CC;

	/** 스크롤 막대의 폭. 판 오른쪽 여백(6px) 안에 들어가야 한다. */
	private static final int SCROLL_BAR_WIDTH = 3;
	/** 손잡이가 아무리 짧아져도 이만큼은 남긴다. 사라지면 어디까지 왔는지 알 수 없다. */
	private static final int SCROLL_THUMB_MIN = 12;
	/** 휠 한 칸에 밀리는 거리. 두 줄씩 움직이는 편이 손에 붙는다. */
	private static final int SCROLL_STEP = ROW_HEIGHT * 2;
	/** 증강 목록 아래에 「증강 선택 창 열기」 단추가 있을 때 비워 둘 자리. */
	private static final int PERK_LIST_BOTTOM_WITH_BUTTON = 58;
	/** 단추가 없을 때 비워 둘 자리. 판 바닥(height − 32)과 닫기 단추를 침범하지 않는 값이다. */
	private static final int PERK_LIST_BOTTOM_PLAIN = 36;

	/**
	 * 세트 줄을 몇 줄까지 보여 줄지.
	 *
	 * <p>한 줄이 늘 때마다 아래 증강 목록이 그만큼 좁아진다. 잘릴 때 없어지는 것은 가장 덜
	 * 모은 유형이다({@link PerkSetLines#visible}).
	 */
	private static final int MAX_SET_ROWS = 6;
	/** 세트 덩어리와 그 아래 증강 목록 사이의 틈. */
	private static final int SET_BLOCK_GAP = 4;

	/** 팀 이름 길이 상한. 서버의 {@code MAX_TEAM_NAME_LENGTH} 와 같아야 한다. */
	private static final int MAX_TEAM_NAME_LENGTH = 32;
	private static final int MIN_HEALTH = TeamCreationSettings.MIN_MAX_HEALTH;
	private static final int MAX_HEALTH = TeamCreationSettings.MAX_MAX_HEALTH;
	private static final int HEALTH_STEP = 2;

	/**
	 * 팀 만들기 탭의 설정 줄들.
	 *
	 * <p>정할 것이 여덟 가지로 늘어 한 줄에 하나씩 두면 창을 넘긴다. 절반씩 둘을 나란히 놓고,
	 * 숫자는 −/+ 두 단추 대신 <b>누를 때마다 값이 굴러가는</b> 단추 하나로 줄였다
	 * ({@link TeamCreationCycle}).
	 *
	 * <h2>여덟 번째를 넣으면서 창 높이도 줄 높이도 건드리지 않았다</h2>
	 *
	 * <p>절반씩 둘을 놓으면 <b>일곱은 네 줄에 한 칸이 빈다</b> — 마지막 줄(「다시 뽑기」)의
	 * 오른쪽이 그동안 비어 있었다. 여덟 번째는 그 빈칸에 그대로 들어가므로 줄이 늘지 않고,
	 * 따라서 아래의 「팀 만들기」 단추도 경고 세 줄도 예전과 <b>같은 y</b>에 선다.
	 *
	 * <p>줄을 더 줄이거나 창을 키우는 쪽은 둘 다 대가가 있어 고르지 않았다. {@code FORM_ROW}
	 * 를 줄이면 단추가 서로 붙어 잘못 누르기 쉬워지고, 판을 키우면 GUI 배율이 큰 사람에게서
	 * 아래 경고 줄이 「닫기」 단추와 겹친다 — 바닐라가 보장하는 세로는 240 뿐이라 지금도
	 * 여유가 크지 않다. <b>빈칸이 이미 있는데 자리를 새로 만들 이유가 없다.</b>
	 *
	 * <p>⚠ 아홉 번째를 넣는 사람은 그때 진짜로 골라야 한다. 네 줄이 꽉 차므로 다섯 번째 줄이
	 * 생기고, 그만큼 아래의 모든 것이 20px 씩 내려간다.
	 */
	private static final int FORM_TOP = PANEL_TOP + 34;
	private static final int FORM_ROW = 20;
	/** 설정 줄의 단추 높이. 기본 20 보다 낮춰야 여덟 가지가 네 줄로 창 안에 들어간다. */
	private static final int FORM_BUTTON_HEIGHT = 18;

	private enum Tab {
		STATUS("현황"),
		TEAM("팀"),
		SETTINGS("설정"),
		PERKS("증강"),
		/**
		 * 증강이 능력치를 얼마나 바꿨는지.
		 */
		STATS("능력치");

		private final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private Tab tab = Tab.STATUS;
	private EditBox nameBox;
	/** 마지막으로 위젯을 만들 때의 팀 상태 요약. 달라지면 다시 만든다. */
	private String lastSignature = "";

	/**
	 * 증강 탭에 그릴 줄을 미리 접어 둔 것.
	 *
	 * <p>{@code font.split} 은 폭이 정해져야 할 수 있는 계산이라 {@link #init()} 에서 한 번만
	 * 한다. 매 프레임 다시 접으면 증강이 늘어날수록 그리기가 무거워진다.
	 */
	private final List<PerkLine> perkLines = new ArrayList<>();
	/**
	 * 증강 목록 위에 그릴 세트 줄들.
	 *
	 * <p>{@link #layoutPerkList()} 에서 한 번 정하고 그 프레임 내내 쓴다. <b>그리기와 자리
	 * 계산이 같은 목록을 봐야</b> 한다 — {@link #perkListTop()} 이 이 목록의 길이만큼 아래로
	 * 내려가는데, 그리는 쪽이 다른 길이를 쓰면 세트가 증강 목록 위에 겹쳐 찍힌다.
	 */
	private List<PerkSetLines.Line> setLines = List.of();
	/**
	 * 「유적 감별사」 좌표 줄들. 그 증강이 없으면 빈 목록이다.
	 *
	 * <p>{@link #layoutPerkList()} 에서 접어 두고 그 프레임 내내 쓴다. 판 안에 들일 때는
	 * {@link #setBlockTop()} 이 이 목록의 길이만큼 아래로 내려가므로, 그리는 쪽이 다른 길이를
	 * 쓰면 세트 줄이 좌표 위에 겹쳐 찍힌다 — {@link #setLines} 와 같은 규칙이다.
	 */
	private List<FormattedCharSequence> ruinLines = List.of();
	/**
	 * 좌표를 어디에 세울지. {@link RuinCoordPlacement} 가 화면 가로를 보고 정한다.
	 *
	 * <p>화면 크기가 바뀌면 달라지므로 {@link #layoutPerkList()} 에서 매번 다시 정한다.
	 */
	private RuinCoordPlacement.Spot ruinSpot = RuinCoordPlacement.Spot.NONE;
	/** 증강 목록 전체의 세로 길이(px). 스크롤 범위의 분모다. */
	private int perkContentHeight;
	/** 증강 목록이 보이는 창의 세로 길이(px). */
	private int perkViewHeight;
	/** 증강 목록을 위로 밀어 올린 거리(px). 0이면 맨 위다. */
	private int perkScroll;

	/**
	 * 팀을 만들 때 정할 여덟 가지. 아직 팀이 없으니 서버에 있을 수 없어 화면이 들고 있다가
	 * 「팀 만들기」를 누를 때 명령 한 줄로 보낸다. 창을 닫았다 열면 기본값으로 돌아간다.
	 *
	 * <p><b>기본값은 서버의 {@link TeamCreationSettings} 와 같아야 한다.</b> 화면에 보이는
	 * 값과 아무것도 안 적었을 때 서버가 쓰는 값이 다르면, 사람이 화면을 보고 짐작한 것과
	 * 실제 팀이 어긋난다. 그래서 <b>서버 상수를 그대로 참조</b>한다 — 숫자를 여기에 옮겨
	 * 적으면 언젠가 한쪽만 고쳐진다. 증강과 위치 교환이 켬이고 나머지는 끔·서버 설정값이다.
	 *
	 * <p>두 알림과 난이도 상승, 드래곤 시련은 <b>만든 뒤에 바꿀 수 없으므로</b> 켜는 것을
	 * 일부러 손으로 고르게 한다.
	 *
	 * <p>⚠ <b>드래곤 시련은 2026-09-30 전까지 늘 켜져 있던 것</b>이라 특히 조심할 자리다.
	 * 이제 기본값이 끔이므로 <b>시험 월드를 새로 열 때마다 팀을 만들면서 켜 줘야</b> 하고,
	 * 안 켜면 엔드에 도착해도 카드가 한 장도 안 뜬다. 창을 닫았다 열면 여기 적힌 기본값으로
	 * 돌아가므로 「아까 켰는데」도 통하지 않는다.
	 */
	private boolean newTeamPerks = TeamCreationSettings.DEFAULT_PERKS_ENABLED;
	private boolean newTeamDamageAlert;
	private boolean newTeamDeathAlert;
	private boolean newTeamDifficulty = TeamCreationSettings.DEFAULT_DIFFICULTY_ESCALATION;
	private boolean newTeamDragonTrials = TeamCreationSettings.DEFAULT_DRAGON_TRIALS;
	/**
	 * 서버가 정한 기본 최대 체력을 화면이 알 길이 없다 — 팀에 속하기 전에는 동기화가 오지
	 * 않는다. 명령이 받는 아래 끝(20)에서 시작한다.
	 */
	private int newTeamMaxHealth = MIN_HEALTH;
	private int newTeamSwapMinutes = TeamCreationSettings.DEFAULT_SWAP_MINUTES;
	private int newTeamRerollCount = TeamCreationSettings.DEFAULT_REROLL_COUNT;
	/**
	 * 적다 만 팀 이름.
	 *
	 * <p>단추를 누르면 위젯을 통째로 다시 만들므로 {@link EditBox} 도 새것이 된다. 여기에
	 * 옮겨 두지 않으면 <b>켜고 끄기를 누를 때마다 적던 이름이 지워진다.</b>
	 */
	private String newTeamName = "";
	/**
	 * 「팀 만들기」 단추.
	 *
	 * <p>이름 칸에 글자가 들어오는 <b>즉시</b> 켜고 꺼야 해서 들고 있는다. 위젯을 통째로 다시
	 * 만드는 {@link #rebuild()} 를 기다리면 한 틱 늦고, 글자 입력 칸의 커서도 튄다.
	 */
	private Button createButton;

	/**
	 * 「팀 만들기」 명령을 보내 두고 아직 결과를 못 봤는가.
	 *
	 * <p>명령을 보낸 그 틱에는 아직 팀이 없다. 팀이 생겼다는 사실은 몇 틱 뒤 도착하는
	 * {@code TeamSyncPayload} 가 알려 주므로, 그때 양식을 정리하고 「팀」 탭으로 맞추려면
	 * <b>내가 보냈다</b>는 것을 기억해 둬야 한다. 판정은 {@link TeamCreationFlow} 가 한다.
	 */
	private boolean awaitingCreate;

	/**
	 * 「게임 시작」 단추가 확인 단계인가.
	 *
	 * <p>참이면 다음 누름이 실제로 명령을 보낸다. 되돌릴 수 없는 동작이라 한 번에 나가지 않게
	 * 한 것이고, 규칙은 {@link com.sharedfate.ui.GameStartButton} 에 적어 뒀다. 탭을 옮기거나
	 * 창을 닫으면 <b>반드시 풀린다</b> — 확인 단계로 둔 채 다른 일을 하다 돌아와서 무심코 누르는
	 * 것이 이 장치가 막으려던 바로 그 사고다.
	 */
	private boolean startConfirming;

	public TeamScreen() {
		super(Component.literal("SharedFate 팀"));
	}

	/**
	 * 증강 탭을 편 채로 연다.
	 *
	 * <p>인벤토리의 「증강」 단추가 쓴다. 거기서는 <b>한 번 눌러 바로 목록</b>이 나와야 하는데,
	 * 기본 탭으로 열면 「SharedFate → 증강」 두 번을 눌러야 한다.
	 */
	public static TeamScreen onPerks() {
		TeamScreen screen = new TeamScreen();
		screen.tab = Tab.PERKS;
		return screen;
	}

	/**
	 * 팀 탭을 편 채로 연다.
	 *
	 * <p>인벤토리의 「팀 생성」 단추가 쓴다. 팀이 없을 때 그 단추가 대신 서는데, 기본 탭으로
	 * 열면 「팀에 속해 있지 않습니다」만 읽고 다시 「팀」을 눌러야 만들기 양식에 닿는다.
	 *
	 * <p>팀이 <b>있을 때</b> 이 길로 들어와도 탈은 없다 — 그때 「팀」 탭은 팀원 목록이라 볼 것이
	 * 있다. 그래서 단추가 사라지는 순간에 눌린 한 프레임을 따로 막지 않는다.
	 */
	public static TeamScreen onTeam() {
		TeamScreen screen = new TeamScreen();
		screen.tab = Tab.TEAM;
		return screen;
	}

	@Override
	protected void init() {
		lastSignature = signature();
		int left = (this.width - PANEL_WIDTH) / 2;

		int tabWidth = PANEL_WIDTH / Tab.values().length;
		for (Tab value : Tab.values()) {
			int x = left + tabWidth * value.ordinal();
			Component label = value == tab
					? Component.literal(value.label).withStyle(ChatFormatting.YELLOW)
					: Component.literal(value.label);
			addRenderableWidget(Button.builder(label, button -> switchTo(value))
					.bounds(x, PANEL_TOP - TAB_HEIGHT - 2, tabWidth - 2, TAB_HEIGHT).build());
		}

		switch (tab) {
			case STATUS -> initStatus(left);
			case TEAM -> initTeam(left);
			case SETTINGS -> initSettings(left);
			case PERKS -> initPerks(left);
			// 능력치 탭에는 위젯이 없다. 값 표시는 renderStats 가 맡는다.
			case STATS -> {
			}
		}

		addRenderableWidget(Button.builder(Component.literal("닫기"), button -> onClose())
				.bounds(left + PANEL_WIDTH / 2 - 50, this.height - 28, 100, BUTTON_HEIGHT).build());
	}

	private void switchTo(Tab next) {
		// 탭을 옮기면 목록을 맨 위부터 다시 본다. 틱마다 도는 rebuild() 는 자리를 지킨다.
		if (next != tab) {
			perkScroll = 0;
			// 확인 단계는 탭을 벗어나는 순간 풀린다.
			startConfirming = false;
		}
		tab = next;
		rebuild();
	}

	private void rebuild() {
		clearWidgets();
		init();
	}

	// ------------------------------------------------------------------ 현황

	private void initStatus(int left) {
		if (ClientTeamState.inTeam() && PerkClientState.hasPending()) {
			addRenderableWidget(Button.builder(
					Component.literal("증강 선택 창 열기"), button -> run("perk"))
					.bounds(left, this.height - 54, PANEL_WIDTH, BUTTON_HEIGHT).build());
		}
	}

	// ------------------------------------------------------------------ 팀

	private void initTeam(int left) {
		if (!ClientTeamState.inTeam()) {
			nameBox = new EditBox(this.font, left, PANEL_TOP + 12, PANEL_WIDTH, FORM_BUTTON_HEIGHT,
					Component.literal("팀 이름"));
			nameBox.setMaxLength(MAX_TEAM_NAME_LENGTH);
			nameBox.setValue(newTeamName);
			nameBox.setResponder(value -> {
				newTeamName = value;
				if (createButton != null) {
					createButton.active = TeamNameInput.valid(value);
				}
			});
			addRenderableWidget(nameBox);

			int half = PANEL_WIDTH / 2 - 2;
			int right = left + PANEL_WIDTH / 2 + 2;

			addRenderableWidget(toggle(left, formRowY(0), half, "증강", newTeamPerks,
					"사용", "사용 안 함", TeamCreationTooltips.PERKS,
					() -> newTeamPerks = !newTeamPerks));
			addRenderableWidget(toggle(right, formRowY(0), half, "난이도 상승", newTeamDifficulty,
					"켬", "끔", TeamCreationTooltips.DIFFICULTY,
					() -> newTeamDifficulty = !newTeamDifficulty));

			addRenderableWidget(toggle(left, formRowY(1), half, "피격 알림", newTeamDamageAlert,
					"켬", "끔", TeamCreationTooltips.DAMAGE_ALERT,
					() -> newTeamDamageAlert = !newTeamDamageAlert));
			addRenderableWidget(toggle(right, formRowY(1), half, "사망 알림", newTeamDeathAlert,
					"켬", "끔", TeamCreationTooltips.DEATH_ALERT,
					() -> newTeamDeathAlert = !newTeamDeathAlert));

			// 숫자 셋은 누를 때마다 다음 값으로 굴러간다. 위 끝을 넘으면 아래 끝으로 돌아온다.
			addRenderableWidget(cycle(left, formRowY(2), half,
					"최대 체력 — " + newTeamMaxHealth, TeamCreationTooltips.MAX_HEALTH,
					() -> newTeamMaxHealth = TeamCreationCycle.nextMaxHealth(
							newTeamMaxHealth, MIN_HEALTH, MAX_HEALTH, HEALTH_STEP)));
			addRenderableWidget(cycle(right, formRowY(2), half,
					"위치 교환 — " + TeamCreationCycle.swapLabel(newTeamSwapMinutes),
					TeamCreationTooltips.POSITION_SWAP,
					() -> newTeamSwapMinutes =
							TeamCreationCycle.nextSwapMinutes(newTeamSwapMinutes)));
			addRenderableWidget(cycle(left, formRowY(3), half,
					"다시 뽑기 — " + newTeamRerollCount + "회", TeamCreationTooltips.REROLL,
					() -> newTeamRerollCount = TeamCreationCycle.nextRerollCount(
							newTeamRerollCount,
							TeamCreationSettings.MIN_REROLL_COUNT,
							TeamCreationSettings.MAX_REROLL_COUNT)));
			// 마지막 줄의 오른쪽은 일곱 가지일 때 비어 있던 자리다. 여덟 번째가 여기 들어가므로
			// 줄이 늘지 않고 아래 단추와 경고 줄의 y 도 그대로다 — FORM_ROW 문서를 보라.
			addRenderableWidget(toggle(right, formRowY(3), half, "드래곤 시련",
					newTeamDragonTrials, "켬", "끔", TeamCreationTooltips.DRAGON_TRIAL,
					() -> newTeamDragonTrials = !newTeamDragonTrials));

			createButton = Button.builder(Component.literal("팀 만들기"), button -> createTeam())
					.bounds(left, formRowY(4) + 4, PANEL_WIDTH, FORM_BUTTON_HEIGHT).build();
			// 이름이 비어 있으면 눌러도 아무 일이 없다. 눌리지 않는 편이 정직하다.
			createButton.active = TeamNameInput.valid(newTeamName);
			addRenderableWidget(createButton);
			return;
		}

		createButton = null;
		nameBox = null;
		int y = PANEL_TOP + 14 + ROW_HEIGHT * (ClientTeamState.memberIds().size() + 1);

		boolean showStart = GameStartButton.visible(
				true, ClientTeamState.isLeader(), ClientTeamState.runStarted());
		// 「게임 시작」 단추와 그 위의 경고 한 줄이 들어갈 자리를 초대 단추가 침범하면 안 된다.
		// 겹치면 초대하려다 시작을 누르게 되는데, 그것이 이 화면에서 가장 나쁜 사고다.
		int inviteBottom = showStart ? this.height - 108 : this.height - 80;

		// 회차가 시작된 뒤에는 아무도 못 부른다. 서버가 어차피 막지만, 단추가 그대로 남아
		// 있으면 눌러 보고 나서야 알게 된다. 눌리지 않는 편이 정직하다.
		if (ClientTeamState.isLeader() && !ClientTeamState.runStarted()) {
			for (String name : invitableNames()) {
				if (y > inviteBottom) {
					break;
				}
				addRenderableWidget(Button.builder(Component.literal(name + " 초대"),
						button -> run("invite " + name))
						.bounds(left, y, PANEL_WIDTH, BUTTON_HEIGHT).build());
				y += BUTTON_HEIGHT + 2;
			}
		}

		if (showStart) {
			// 「팀 나가기」·「팀 해체」 한 줄 위. 되돌릴 수 없는 단추 셋이 나란히 서지만, 이것만
			// 판 전체 폭이라 눌러야 할 것과 눌러서는 안 될 것이 눈으로 갈린다.
			addRenderableWidget(Button.builder(
					Component.literal(GameStartButton.label(startConfirming))
							.withStyle(startConfirming ? ChatFormatting.RED : ChatFormatting.GREEN),
					button -> pressStart())
					.bounds(left, this.height - 78, PANEL_WIDTH, BUTTON_HEIGHT).build());
		}

		addRenderableWidget(Button.builder(Component.literal("팀 나가기"), button -> run("leave"))
				.bounds(left, this.height - 54, PANEL_WIDTH / 2 - 2, BUTTON_HEIGHT).build());
		if (ClientTeamState.isLeader()) {
			addRenderableWidget(Button.builder(
					Component.literal(TeamDisbandWarning.CONFIRM_LABEL)
							.withStyle(ChatFormatting.RED),
					button -> openDisbandConfirm())
					.bounds(left + PANEL_WIDTH / 2 + 2, this.height - 54,
							PANEL_WIDTH / 2 - 2, BUTTON_HEIGHT).build());
		}
	}

	/**
	 * 「게임 시작」 단추를 눌렀다.
	 *
	 * <p>첫 누름은 <b>아무것도 보내지 않고</b> 글자만 경고로 바꾼다. 두 번째 누름에서만 명령이
	 * 나가고, 그 명령도 서버에서 리더 여부와 이미 시작했는지를 처음부터 다시 확인한다.
	 */
	private void pressStart() {
		if (!startConfirming) {
			startConfirming = true;
			rebuild();
			return;
		}
		startConfirming = false;
		run(GameStartButton.CONFIRM_COMMAND);
	}

	/**
	 * 「팀 해체」를 눌렀다. <b>아직 아무것도 보내지 않는다.</b>
	 *
	 * <p>경고창을 띄우고 거기서 한 번 더 확인을 받는다. 무엇이 사라지는지는
	 * {@link TeamDisbandWarning} 이 적고, 그 창이 확인을 받으면 여기서 넘긴 일이 돈다.
	 *
	 * <h2>「게임 시작」처럼 두 번 누르기로 하지 않은 이유</h2>
	 * <p>{@link #pressStart()} 는 단추 글자만 경고로 바꾸고 두 번째 누름에서 명령을 보낸다.
	 * 해체는 사라지는 것이 넷이라 <b>단추 한 줄에 다 적을 수 없고</b>, 남는 팀조차 없어 시작과
	 * 달리 「다시 하면 된다」가 통하지 않는다. 그리고 두 번 누르기는 <b>같은 자리를 두 번</b>
	 * 누르게 해서, 딸깍이 겹치면 그대로 나간다. 창을 갈아 끼우면 두 번째 누름이 다른 자리로
	 * 간다.
	 */
	private void openDisbandConfirm() {
		Minecraft client = this.minecraft;
		if (client == null) {
			return;
		}
		client.setScreenAndShow(new TeamDisbandConfirmScreen(
				this, () -> run(TeamDisbandWarning.CONFIRM_COMMAND)));
	}

	/** 설정 줄 {@code index} 의 y 좌표. 0부터 센다. */
	private static int formRowY(int index) {
		return FORM_TOP + FORM_ROW * index;
	}

	/**
	 * 켜고 끄기 단추 하나. 누르면 값이 뒤집히고 화면을 다시 만든다.
	 *
	 * <p>글자만으로도 지금 값이 보이지만 색까지 바꾼다. 일곱을 훑을 때 무엇이 켜져 있는지
	 * 한눈에 들어와야 한다.
	 *
	 * @param tooltip 마우스를 올리면 뜨는 설명. 이 설정이 실제로 무엇을 켜고 끄는지
	 *                {@link TeamCreationTooltips} 가 확인한 그대로 적는다
	 */
	private Button toggle(int x, int y, int width, String label, boolean value,
			String onText, String offText, String tooltip, Runnable flip) {
		Component text = Component.literal(label + " — " + (value ? onText : offText))
				.withStyle(value ? ChatFormatting.GREEN : ChatFormatting.GRAY);
		return Button.builder(text, button -> {
			flip.run();
			rebuild();
		}).bounds(x, y, width, FORM_BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(tooltip)))
				.build();
	}

	/**
	 * 숫자를 굴리는 단추 하나. 누르면 다음 값이 되고 화면을 다시 만든다.
	 *
	 * <p>켜고 끄기와 달리 색을 바꾸지 않는다. 어떤 값이 「켜짐」인지 정할 수 없는 값들이라,
	 * 초록·회색으로 물들이면 없는 뜻이 생긴다.
	 *
	 * @param tooltip 마우스를 올리면 뜨는 설명. {@link #toggle} 과 같은 출처를 쓴다
	 */
	private Button cycle(int x, int y, int width, String label, String tooltip, Runnable next) {
		return Button.builder(Component.literal(label), button -> {
			next.run();
			rebuild();
		}).bounds(x, y, width, FORM_BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(tooltip)))
				.build();
	}

	/**
	 * 화면이 들고 있던 여덟을 모두 적어 보낸다.
	 *
	 * <p>적지 않은 항목은 서버가 기본값으로 두는데, 화면에는 이미 다른 값이 보이고 있을 수
	 * 있어 눈에 보이는 것과 실제가 어긋난다. 그래서 늘 완전한 형태를 보낸다. 낱말 순서는
	 * {@link TeamCreationCycle#createCommand} 가 서버 명령과 맞춰 둔다.
	 *
	 * <p><b>창을 닫지 않는다.</b> 명령만 보내 두고 결과를 기다린다 — 팀이 생기면 {@link #tick()}
	 * 이 알아채고 팀원 목록과 「게임 시작」이 있는 화면으로 바꾼다. 실패하면 서버가 채팅으로
	 * 사유를 알려 주고, 이 양식은 적던 이름까지 그대로 남는다.
	 */
	private void createTeam() {
		if (nameBox == null) {
			return;
		}
		String name = TeamNameInput.normalize(nameBox.getValue());
		if (!TeamCreationFlow.submitted(name)) {
			return;
		}
		run(TeamCreationCycle.createCommand(newTeamPerks, newTeamDamageAlert, newTeamDeathAlert,
				newTeamDifficulty, newTeamDragonTrials, newTeamMaxHealth, newTeamSwapMinutes,
				newTeamRerollCount, name));
		awaitingCreate = true;
	}

	/** 팀에 없는 접속자 이름. 자기 자신은 뺀다. */
	private List<String> invitableNames() {
		List<String> names = new ArrayList<>();
		if (this.minecraft == null || this.minecraft.getConnection() == null) {
			return names;
		}
		UUID self = this.minecraft.player == null ? null : this.minecraft.player.getUUID();
		for (PlayerInfo info : this.minecraft.getConnection().getListedOnlinePlayers()) {
			UUID id = info.getProfile().id();
			if (id.equals(self) || ClientTeamState.memberIds().contains(id)) {
				continue;
			}
			names.add(info.getProfile().name());
		}
		names.sort(Comparator.naturalOrder());
		return names;
	}

	// ------------------------------------------------------------------ 설정

	/**
	 * 설정 탭에는 단추가 하나도 없다.
	 *
	 * <p>최대 체력·위치 교환·증강 사용 여부는 <b>팀을 만들 때만</b> 정하는 값이라 바꿀 수 없다.
	 * {@link #renderSettings} 가 글자로만 보여 준다.
	 */
	private void initSettings(int left) {
		// 그릴 위젯이 없다. 값 표시는 renderSettings 가 맡는다.
	}

	// ------------------------------------------------------------------ 증강

	private void initPerks(int left) {
		if (ClientTeamState.inTeam() && PerkClientState.hasPending()) {
			addRenderableWidget(Button.builder(
					Component.literal("증강 선택 창 열기"), button -> run("perk"))
					.bounds(left, this.height - 54, PANEL_WIDTH, BUTTON_HEIGHT).build());
		}
		layoutPerkList();
	}

	/**
	 * 증강 목록을 미리 접어 두고 스크롤 범위를 다시 잰다.
	 *
	 * <p>목록이 창을 넘치면 자르지 않고 스크롤로 내려 본다.
	 */
	private void layoutPerkList() {
		perkLines.clear();
		// 유적 좌표와 세트 줄을 먼저 정한다. setBlockTop()·perkListTop() 이 이 두 목록의 길이를
		// 보고 자리를 내리기 때문에, 순서가 바뀌면 첫 프레임이 옛 길이로 계산된다.
		layoutRuinCoords();
		setLines = ClientPerkSets.lines(MAX_SET_ROWS);
		int top = perkListTop();
		perkViewHeight = Math.max(ROW_HEIGHT, perkListBottom() - top);

		int height = 0;
		List<PerkSyncPayload.Owned> owned = PerkClientState.owned();
		for (PerkSyncPayload.Owned perk : owned) {
			// 이름 뒤에 세트 유형을 흐린 글씨로 붙인다 — 「짐꾼 가호」처럼. 어디에도 안 들어가는
			// 증강 열하나에는 안 붙는다.
			Component title = Component.literal("· " + perk.name())
					.withStyle(style -> style.withColor(rarityColor(perk.rarity())));
			if (perk.hasSetTypes()) {
				title = title.copy().append(Component.literal("  " + perk.setTypes())
						.withStyle(style -> style.withColor(PERK_SET_TYPE)));
			}
			for (FormattedCharSequence line : this.font.split(title, PANEL_WIDTH - 8)) {
				perkLines.add(new PerkLine(line, 0, rarityColor(perk.rarity()), ROW_HEIGHT));
				height += ROW_HEIGHT;
			}

			// 설명은 폭에 맞춰 접는다. 이름만으로는 무엇을 들고 있는지 알 수 없다.
			List<FormattedCharSequence> wrapped =
					this.font.split(Component.literal(perk.description()), PANEL_WIDTH - 8);
			for (int index = 0; index < wrapped.size(); index++) {
				// 증강과 증강 사이는 마지막 설명 줄의 키를 늘려 벌린다. 빈 줄을 넣는 것보다
				// 스크롤 계산이 단순하다.
				int lineHeight = index == wrapped.size() - 1 ? ROW_HEIGHT + 2 : ROW_HEIGHT;
				perkLines.add(new PerkLine(wrapped.get(index), 8, TEXT_DIM, lineHeight));
				height += lineHeight;
			}
		}
		perkContentHeight = height;
		perkScroll = PanelScroll.clamp(perkScroll, perkContentHeight, perkViewHeight);
	}

	/**
	 * 「유적 감별사」 좌표 줄들을 접어 두고 세울 자리를 정한다.
	 *
	 * <p>판 안에 들일 때만 접는다. 오른쪽에 세우는 경우는 {@link RuinCoordPlacement} 가 이미
	 * 「가장 긴 줄이 그대로 들어간다」를 확인한 뒤이므로 접을 것이 없다.
	 */
	private void layoutRuinCoords() {
		List<String> coords = PerkClientState.ruinCoords();
		int widest = 0;
		for (String coord : coords) {
			widest = Math.max(widest, this.font.width(coord));
		}
		int left = (this.width - PANEL_WIDTH) / 2;
		ruinSpot = RuinCoordPlacement.choose(coords.size(), this.width, left, PANEL_WIDTH,
				RUIN_COORD_GAP, widest);
		if (ruinSpot == RuinCoordPlacement.Spot.NONE) {
			ruinLines = List.of();
			return;
		}
		List<FormattedCharSequence> lines = new ArrayList<>(coords.size());
		for (String coord : coords) {
			if (ruinSpot == RuinCoordPlacement.Spot.RIGHT) {
				lines.add(FormattedCharSequence.forward(coord, Style.EMPTY));
			} else {
				// 판 안에서는 접는다. 접기는 글자를 버리지 않고 다음 줄로 넘기므로 「잘린 좌표」가
				// 생기지 않는다 — 증강 이름·설명이 쓰는 폭과 같은 값을 쓴다.
				lines.addAll(this.font.split(Component.literal(coord), PANEL_WIDTH - 8));
			}
		}
		ruinLines = List.copyOf(lines);
	}

	/**
	 * 판 안에 들인 좌표 덩어리가 차지하는 세로. 오른쪽에 세웠거나 없으면 0.
	 *
	 * <p>세트 덩어리와 같은 셈({@link PerkSetLines#blockHeight})을 쓴다 — 머리글 아래에 줄
	 * 몇 개와 틈 하나를 두는 모양이 똑같아서, 계산을 새로 만들면 두 덩어리의 간격이 언젠가
	 * 갈린다.
	 */
	private int ruinBlockHeight() {
		return ruinSpot == RuinCoordPlacement.Spot.INSIDE
				? PerkSetLines.blockHeight(ruinLines.size(), ROW_HEIGHT, SET_BLOCK_GAP)
				: 0;
	}

	/** 판 안에 들인 좌표 덩어리가 시작하는 y. 「보유 증강 N개」 머리글 바로 아래다. */
	private int ruinBlockTop() {
		return PANEL_TOP + ROW_HEIGHT + 2;
	}

	/**
	 * 세트 덩어리가 시작하는 y. 머리글과 — 판 안에 들였다면 — 유적 좌표 아래다.
	 *
	 * <p>좌표를 판 안에 들이면 그만큼 아래의 모든 것이 내려간다. 여기 한 곳만 내리면
	 * {@link #perkListTop()} 과 그것을 보는 스크롤·잘라내기가 전부 따라온다.
	 */
	private int setBlockTop() {
		return ruinBlockTop() + ruinBlockHeight();
	}

	/**
	 * 증강 목록이 시작하는 y. 세트 덩어리 바로 아래다.
	 *
	 * <p><b>이 한 줄이 스크롤 계산과 잘라내기 양쪽의 단일 진실원이다.</b> 세트 줄이 늘어 목록이
	 * 내려가야 할 때 여기만 고치면 {@code perkViewHeight}·{@code perkContentHeight}·
	 * {@code enableScissor} 가 전부 따라온다. 그리는 쪽에서 y 를 직접 더하면 스크롤이 여전히
	 * 옛 자리를 기준으로 삼아, 맨 아래 증강이 창 밖에 남아 영영 안 보인다.
	 *
	 * <p>세트가 하나도 없으면 {@link PerkSetLines#blockHeight} 가 0 이라 예전과 같은 자리다.
	 */
	private int perkListTop() {
		return setBlockTop() + PerkSetLines.blockHeight(setLines.size(), ROW_HEIGHT, SET_BLOCK_GAP);
	}

	/** 증강 목록이 끝나는 y. 아래 단추와 판 바닥을 침범하지 않는다. */
	private int perkListBottom() {
		boolean hasOpenButton = ClientTeamState.inTeam() && PerkClientState.hasPending();
		return this.height
				- (hasOpenButton ? PERK_LIST_BOTTOM_WITH_BUTTON : PERK_LIST_BOTTOM_PLAIN);
	}

	/** 증강 목록 한 줄. 접어 둔 글자와 들여쓰기, 색, 차지하는 세로 길이를 함께 든다. */
	private record PerkLine(FormattedCharSequence text, int indent, int color, int height) {
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		int left = (this.width - PANEL_WIDTH) / 2;
		graphics.fill(left - 6, PANEL_TOP - TAB_HEIGHT - 8, left + PANEL_WIDTH + 6,
				this.height - 32, PANEL_BG);
		graphics.centeredText(this.font, this.title, this.width / 2, 12, TEXT_MAIN);

		switch (tab) {
			case STATUS -> renderStatus(graphics, left);
			case TEAM -> renderTeam(graphics, left);
			case SETTINGS -> renderSettings(graphics, left);
			// 세트 줄에 마우스를 올리면 툴팁이 뜬다. 그 판정에 마우스 자리가 필요해서 여기만
			// 좌표를 함께 넘긴다.
			case PERKS -> renderPerks(graphics, left, mouseX, mouseY);
			case STATS -> renderStats(graphics, left);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void renderStatus(GuiGraphicsExtractor graphics, int left) {
		int y = PANEL_TOP;
		if (!ClientTeamState.inTeam()) {
			graphics.text(this.font, "팀에 속해 있지 않습니다.", left, y, TEXT_DIM);
			graphics.text(this.font, "'팀' 탭에서 새로 만들거나, 리더에게 초대를 받으세요.",
					left, y + ROW_HEIGHT, TEXT_DIM);
			return;
		}

		graphics.text(this.font, "팀 " + ClientTeamState.teamName(), left, y, TEXT_MAIN);
		y += ROW_HEIGHT + 2;
		graphics.text(this.font, "팀원 " + onlineCount() + "/"
				+ ClientTeamState.memberIds().size() + "명 접속", left, y, TEXT_DIM);
		y += ROW_HEIGHT + 2;

		// 시작 전에는 이 줄이 맨 앞에 와야 한다. 아래의 레벨·증강을 먼저 읽으면 회차가 이미
		// 굴러가는 줄 안다. 단추는 여기 두지 않는다 — 창을 열자마자 보이는 자리에 되돌릴 수
		// 없는 단추가 있으면 잘못 누른다.
		if (!ClientTeamState.runStarted()) {
			graphics.text(this.font, GameStartButton.waitingNotice(ClientTeamState.isLeader()),
					left, y, TEXT_WARN);
			y += ROW_HEIGHT + 2;
		}

		graphics.text(this.font, "팀 레벨 " + ClientTeamState.teamLevel(), left, y, TEXT_GOOD);
		y += ROW_HEIGHT;
		int remaining = ClientTeamState.levelsToNextPerk();
		graphics.text(this.font, remaining < 0 ? "남은 증강 없음" : "다음 증강까지 " + remaining,
				left, y, TEXT_WARN);
		y += ROW_HEIGHT + 2;

		// 현재 체력은 적지 않는다. 여기서는 팀의 상한만 확인하고, 지금 얼마나 남았는지는
		// HUD 의 하트로 본다. 숫자와 하트가 한 화면에서 어긋나 보이는 일도 함께 없어진다.
		//
		// 「장님 거인」처럼 체력 표시를 가리는 증강이 있어도 이 줄은 그대로 둔다. 그 증강이
		// 감추는 것은 <b>지금 체력</b>이고, 상한은 어차피 설정 탭에서도 그냥 보인다.
		// 여기만 「???」로 가리면 두 탭이 서로 다른 말을 하게 된다.
		graphics.text(this.font, "공유 최대 체력 " + trimZero(ClientTeamState.maxHealth())
				+ "  (하트 " + trimZero(ClientTeamState.maxHealth() / 2) + "개)",
				left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		graphics.text(this.font, "위치 교환 " + (ClientTeamState.swapEnabled()
				? ClientTeamState.swapIntervalMinutes() + "분 주기" : "꺼짐"), left, y, TEXT_DIM);
		y += ROW_HEIGHT;
		graphics.text(this.font, "증강 " + (ClientTeamState.perksEnabled() ? "사용" : "사용 안 함"),
				left, y, TEXT_DIM);

		if (PerkClientState.hasPending()) {
			y += ROW_HEIGHT + 4;
			String chooser = PerkClientState.chooserName();
			graphics.text(this.font, chooser.isEmpty()
					? "고르지 않은 증강 선택권이 있습니다."
					: chooser + "님이 증강을 고를 차례입니다.", left, y, TEXT_WARN);
		}
	}

	private void renderTeam(GuiGraphicsExtractor graphics, int left) {
		int y = PANEL_TOP;
		if (!ClientTeamState.inTeam()) {
			graphics.text(this.font, "새 팀 이름을 적고 여덟 가지를 정한 뒤 만드세요.",
					left, y, TEXT_DIM);
			// 「팀 만들기」 단추 바로 아래. 여덟 가지 전부 되돌릴 수 없으므로 눈에 띄는 색으로
			// 적고, 줄 수를 둘로 줄여 창이 낮을 때 닫기 단추와 겹치지 않게 한다.
			int noteY = formRowY(4) + 4 + FORM_BUTTON_HEIGHT + 6;
			graphics.text(this.font, "여덟 가지 모두 팀을 만들 때만 정합니다. 바꾸려면 팀을 해체하세요.",
					left, noteY, TEXT_WARN);
			// 단추가 꺼져 있으면 왜 꺼져 있는지를 늘 보이는 한 줄로 적는다.
			boolean nameReady = TeamNameInput.valid(newTeamName);
			graphics.text(this.font,
					nameReady ? "숫자 단추는 누를 때마다 다음 값으로 바뀝니다." : TeamNameInput.EMPTY_HINT,
					left, noteY + ROW_HEIGHT, nameReady ? TEXT_DIM : TEXT_WARN);
			// 위치 교환은 두 명이 있어야 돈다. 팀을 만드는 순간에는 언제나 혼자이므로 막지는
			// 않고, 「지금은 안 돈다」는 사실만 알린다 — 막으면 위치 교환을 쓰는 길이 없어진다.
			if (newTeamSwapMinutes != TeamCreationCycle.SWAP_OFF) {
				graphics.text(this.font, "⚠ 위치 교환은 팀원이 둘 이상이어야 돕니다. 혼자면 멈춰 있습니다.",
						left, noteY + ROW_HEIGHT * 2, TEXT_WARN);
			}
			return;
		}

		graphics.text(this.font, "팀 " + ClientTeamState.teamName()
				+ (ClientTeamState.isLeader() ? " (내가 리더)" : ""), left, y, TEXT_MAIN);
		y += ROW_HEIGHT + 2;
		for (UUID id : ClientTeamState.memberIds()) {
			boolean online = isOnline(id);
			graphics.text(this.font, (online ? "● " : "○ ") + ClientTeamState.memberName(id),
					left, y, online ? TEXT_GOOD : TEXT_DIM);
			y += ROW_HEIGHT;
		}
		if (!ClientTeamState.isLeader()) {
			graphics.text(this.font, "초대는 리더만 할 수 있습니다.", left, y + 4, TEXT_DIM);
		} else if (invitableNames().isEmpty()) {
			graphics.text(this.font, "초대할 수 있는 접속자가 없습니다.", left, y + 4, TEXT_DIM);
		} else {
			graphics.text(this.font, "누르면 곧바로 팀에 들어옵니다. 상대의 개인 아이템은 드랍됩니다.",
					left, y + 4, TEXT_WARN);
		}

		if (!ClientTeamState.runStarted()) {
			// 단추 바로 위. 무엇을 잃는지는 단추 글자에도 적히지만, 확인 단계로 넘어가기 전에
			// 한 번은 읽혀야 한다.
			int noteY = this.height - 78 - ROW_HEIGHT - 4;
			graphics.text(this.font, ClientTeamState.isLeader()
					? "시작하면 모든 아이템이 사라지고 팀원이 스폰으로 모입니다. 되돌릴 수 없습니다."
					// 리더가 아닌 사람에게는 여기서도 그 사람이 할 수 있는 것만 적는다.
					: GameStartButton.waitingNotice(false),
					left, noteY, TEXT_WARN);
		}
	}

	/**
	 * 설정 탭. <b>보여 주기만 한다.</b>
	 *
	 * <p>아무도 값을 바꿀 수 없고, <b>보는 것은 리더만</b>이다.
	 */
	private void renderSettings(GuiGraphicsExtractor graphics, int left) {
		int y = PANEL_TOP;
		if (!ClientTeamState.inTeam()) {
			graphics.text(this.font, "팀이 있어야 설정을 볼 수 있습니다.", left, y, TEXT_DIM);
			return;
		}
		if (!ClientTeamState.isLeader()) {
			graphics.text(this.font, "설정은 리더만 볼 수 있습니다.", left, y, TEXT_DIM);
			return;
		}
		graphics.text(this.font, "최대 체력 " + trimZero(ClientTeamState.maxHealth())
				+ "  (하트 " + trimZero(ClientTeamState.maxHealth() / 2) + "개)", left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		graphics.text(this.font, "위치 교환 " + (ClientTeamState.swapEnabled()
						? ClientTeamState.swapIntervalMinutes() + "분 주기" : "꺼짐"),
				left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		graphics.text(this.font, "증강 " + (ClientTeamState.perksEnabled() ? "사용 중" : "사용 안 함"),
				left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		graphics.text(this.font, "피격 알림 " + onOffText(ClientTeamState.damageAlertEnabled()),
				left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		graphics.text(this.font, "사망 알림 " + onOffText(ClientTeamState.deathAlertEnabled()),
				left, y, TEXT_MAIN);
		y += ROW_HEIGHT;
		// 기본값이 끔이라 「켜 준 적이 있는가」를 게임 안에서 확인할 자리가 필요하다. 엔드에
		// 가서 카드가 안 뜨는 것을 보고 여기를 열면 원인이 바로 읽혀야 한다.
		graphics.text(this.font, "드래곤 시련 " + (ClientTeamState.dragonTrialsEnabled()
						? "켜짐" : "꺼짐 (바닐라 엔더 드래곤전)"),
				left, y, TEXT_MAIN);

		y += ROW_HEIGHT + 6;
		graphics.text(this.font, "이 설정들은 팀을 만들 때 정한 값이라 바꿀 수 없습니다.",
				left, y, TEXT_DIM);
		y += ROW_HEIGHT;
		graphics.text(this.font, "바꾸려면 리더가 팀을 해체하고 다시 만들어야 합니다.",
				left, y, TEXT_DIM);
	}

	private static String onOffText(boolean value) {
		return value ? "켜짐" : "꺼짐";
	}

	// ------------------------------------------------------------------ 능력치

	/**
	 * 능력치 탭. <b>증강이 무엇을 얼마나 바꿨는지</b>를 바닐라 기본값과 나란히 보여 준다.
	 *
	 * <p>같은 줄이 인벤토리 화면(E) 왼쪽에도 뜨지만, 그쪽은 <b>창 왼쪽에 남는 자리만큼만</b>
	 * 그린다. GUI 배율이 크거나 조합법 책을 펼치면 이름이 줄고, 더 좁으면 아예 감춘다
	 * ({@link com.sharedfate.ui.InventoryStatPanel}). 이 탭은 어떤 배율에서도 <b>온전한 이름과
	 * 증감, 그리고 아래의 설명 줄까지</b> 보여 준다.
	 *
	 * <p>대신 <b>두 곳이 같은 코드로 줄을 만든다</b> — {@link ClientStatRows} 가 유일한
	 * 출처이고, 글자 모양은 {@link StatRow} 가 정한다. 형식이 두 곳에서 갈라질 자리가 없다.
	 *
	 * <h2>값을 어디서 읽는지</h2>
	 * <p>{@link ClientStatRows} 에 적었다. 요약하면 최대 체력·공격 속도·방어력·이동 속도는
	 * 바닐라가 클라이언트에 보내 주는 속성이고, 공격력·받는 피해 배율·몹 배율은 서버가
	 * {@code StatSnapshotPayload} 로 따로 보내 준다.
	 *
	 * <h2>「장님 거인」과의 관계</h2>
	 * <p>그 증강이 감추는 것은 <b>지금 체력</b>과 허기이고, 여기 적는 것은 <b>상한</b>이다.
	 * 상한은 「현황」·「설정」 탭에서도 그냥 보이므로 여기서만 가리면 세 탭이 서로 다른 말을 한다.
	 * 다만 방어력은 사정이 다르다 — HUD 의 방어구 칸이 곧 이 숫자라, 그것을 가리는 증강이
	 * 붙으면 여기서도 가린다.
	 */
	private void renderStats(GuiGraphicsExtractor graphics, int left) {
		int y = PANEL_TOP;
		LocalPlayer player = this.minecraft == null ? null : this.minecraft.player;
		if (player == null) {
			graphics.text(this.font, "능력치를 읽을 수 없습니다.", left, y, TEXT_DIM);
			return;
		}

		graphics.text(this.font, "바닐라 기본값 → 지금 값  (증감)", left, y, TEXT_DIM);
		y += ROW_HEIGHT + 4;

		y = statRows(graphics, left, y, ClientStatRows.playerRows(player));
		// 「내 능력치」와 「이 판의 몹」 사이의 틈. 몹 체력이 내 체력처럼 읽히면 안 된다.
		y += 6;
		y = statRows(graphics, left, y, ClientStatRows.mobRows());

		y += 6;
		if (!ClientTeamState.inTeam()) {
			graphics.text(this.font, "팀에 들어가면 증강이 이 값들을 바꿉니다.", left, y, TEXT_DIM);
			y += ROW_HEIGHT;
		}
		graphics.text(this.font, "공격 속도는 초당 공격 횟수입니다. 무기를 들면 내려갑니다.",
				left, y, TEXT_DIM);
		y += ROW_HEIGHT;
		// 색이 반대인 줄이 있다는 것은 반드시 적어야 한다. 「몹 체력 200%」가 빨간 것을 보고
		// 무언가 고장 났다고 읽는 사람이 나오면 안 된다.
		graphics.text(this.font, "받는 피해·몹 줄은 오를수록 불리해 색이 반대입니다.",
				left, y, TEXT_DIM);
		y += ROW_HEIGHT;
		graphics.text(this.font, "공격력에는 마법부여와 치명타가 빠져 있습니다.", left, y, TEXT_DIM);
	}

	/** 줄 묶음 하나를 그리고 다음 줄의 y 를 돌려준다. */
	private int statRows(GuiGraphicsExtractor graphics, int left, int y, List<StatRow> rows) {
		for (StatRow row : rows) {
			graphics.text(this.font, row.fullLine(), left, y, row.color());
			y += ROW_HEIGHT;
		}
		return y;
	}

	private void renderPerks(GuiGraphicsExtractor graphics, int left, int mouseX, int mouseY) {
		int y = PANEL_TOP;
		if (!ClientTeamState.inTeam()) {
			graphics.text(this.font, "팀이 있어야 증강을 봅니다.", left, y, TEXT_DIM);
			return;
		}
		if (!ClientTeamState.perksEnabled()) {
			graphics.text(this.font, "이 팀은 증강을 쓰지 않습니다.", left, y, TEXT_DIM);
			return;
		}
		List<PerkSyncPayload.Owned> owned = PerkClientState.owned();
		if (owned.isEmpty()) {
			graphics.text(this.font, "아직 고른 증강이 없습니다.", left, y, TEXT_DIM);
			return;
		}
		boolean overflows = PanelScroll.overflows(perkContentHeight, perkViewHeight);
		graphics.text(this.font, "보유 증강 " + owned.size() + "개"
				+ (overflows ? "  (휠로 넘겨 보세요)" : ""), left, y, TEXT_MAIN);

		renderSets(graphics, left);
		// 목록 오른쪽, 바탕판 밖의 빈자리다. 잘라내기를 켜기 전에 그려야 한다 — 잘라내기 범위는
		// 목록 폭뿐이라 그 안에서 그리면 한 글자도 안 나온다.
		renderRuinCoords(graphics, left);

		// 창 밖으로 나가는 줄이 그려지지 않게 자른다. 자르지 않으면 스크롤한 목록이 머리글과
		// 아래 단추를 덮어쓴다.
		int top = perkListTop();
		int bottom = top + perkViewHeight;
		graphics.enableScissor(left - 4, top, left + PANEL_WIDTH + 4, bottom);
		int lineY = top - perkScroll;
		for (PerkLine line : perkLines) {
			if (lineY + ROW_HEIGHT > top && lineY < bottom) {
				graphics.text(this.font, line.text(), left + line.indent(), lineY, line.color());
			}
			lineY += line.height();
		}
		graphics.disableScissor();

		if (overflows) {
			renderPerkScrollBar(graphics, left, top);
		}

		// 툴팁은 맨 마지막이다. 잘라내기가 풀린 뒤라야 목록 창 밖까지 뻗을 수 있다.
		renderPerkTooltip(graphics, left, mouseX, mouseY);
	}

	/**
	 * 「유적 감별사」 좌표를 그린다. <b>넓으면 목록 오른쪽, 좁으면 판 안 머리글 아래</b>다.
	 *
	 * <p>사람이 요청한 모양은 목록 오른쪽이다 — 목록 안이 아니라 그 오른쪽에 좌표만 나열한다.
	 * 설명 문자열 뒤에 괄호로 붙여 보내던 임시 방편은 규약 35 에서 없앴다
	 * ({@code PerkSyncPayload} 의 {@code ruinCoords}). <b>두 곳에 같은 좌표가 뜨면 안 된다</b> —
	 * 그래서 자리는 {@link RuinCoordPlacement} 가 <b>하나만</b> 고른다.
	 *
	 * <p>줄은 서버가 만든 것을 글자 그대로 그린다. 「고대 도시  -1234, 567」처럼 이름표와 x·z 가
	 * 이미 한 줄에 들어 있어 클라이언트가 이을 것이 없다. y 는 서버가 넣지 않는다 — 파고
	 * 들어갈 자리다.
	 *
	 * <h2>「자리가 모자라면 안 그린다」를 걷어냈다</h2>
	 *
	 * <p>예전에는 오른쪽에 안 들어가면 아무것도 안 그렸다. 「잘린 좌표는 틀린 좌표다」는 근거는
	 * 옳지만, 그 규칙이 실제로 한 일은 <b>1920×1080 의 기본 GUI 배율 4</b>(화면 480×270, 판
	 * 오른쪽에 남는 자리 78px, 좌표 한 줄 104px)에서 좌표를 영영 안 보이게 만든 것이었다 —
	 * 사람이 겪은 증상이 바로 이것이다. 이 증강이 하는 일은 「채팅이 올라가도 다시 볼 수 있게
	 * 좌표를 화면에 남기는 것」이라, 안 그리면 증강 자체가 사라진다.
	 *
	 * <p>그래서 안 그리는 대신 판 안으로 들인다. 판은 어느 배율에서나 가로 300 이라 좌표 한 줄이
	 * 늘 들어가고, 판 안에서는 접을 수 있어 잘릴 일도 없다. 배율별 숫자는
	 * {@code RuinCoordPlacementTest} 가 붙들고 있다.
	 *
	 * <p>줄 수는 정의가 정하고 {@code RuinSurveyEffect.MAX_STRUCTURES} 만큼까지다. 지금 쓰이는
	 * 정의는 둘이라 두 줄이지만, 여기서 둘을 못 박지는 않는다.
	 */
	private void renderRuinCoords(GuiGraphicsExtractor graphics, int left) {
		if (ruinSpot == RuinCoordPlacement.Spot.NONE || ruinLines.isEmpty()) {
			return;
		}
		boolean right = ruinSpot == RuinCoordPlacement.Spot.RIGHT;
		int x = right ? RuinCoordPlacement.rightX(left, PANEL_WIDTH, RUIN_COORD_GAP) : left;
		int y = right ? PANEL_TOP : ruinBlockTop();
		for (FormattedCharSequence line : ruinLines) {
			graphics.text(this.font, line, x, y, RUIN_COORD);
			y += ROW_HEIGHT;
		}
	}

	/**
	 * 머리글과 증강 목록 사이의 세트 덩어리.
	 *
	 * <p>켜진 세트와 진행도를 함께 보여 준다. 무엇을 몇 줄이나 어떤 차례로 그릴지는
	 * {@link PerkSetLines} 가 정하고, 여기서는 색만 고른다 — HUD 와 같은 계산을 써야 두 화면이
	 * 다른 말을 하지 않는다.
	 */
	private void renderSets(GuiGraphicsExtractor graphics, int left) {
		int y = setBlockTop();
		for (PerkSetLines.Line line : setLines) {
			graphics.text(this.font, line.text(), left, y,
					line.active() ? TEXT_GOOD : TEXT_DIM);
			y += ROW_HEIGHT;
		}
	}

	/**
	 * <b>위쪽 세트 줄</b>에 마우스를 올렸을 때 그 유형 세트의 단계 설명을 띄운다.
	 *
	 * <p>내용은 {@code ClientPerkSets.tooltip} 한 곳에서 나오고 줄로 펴는 것은
	 * {@link PerkSetTooltipLines} 가 한다.
	 *
	 * <p><b>아래쪽 보유 증강 줄에서는 아무것도 뜨지 않는다.</b> 그 줄은 이름 아래에 설명을 이미
	 * 펼쳐 적고 있어서, 읽으려고 마우스를 얹는 순간 세트 상자가 그 설명을 덮어 버린다. 세트
	 * 진행도는 바로 위 세트 줄에서 본다.
	 *
	 * <p>클라이언트는 증강 풀도 세트 정의도 읽지 않으므로 단계 설명과 이름표는 통째로 서버가
	 * 보내 준 것이다({@code PerkSetSyncPayload}). 등급은 글자색으로 가른다.
	 */
	private void renderPerkTooltip(GuiGraphicsExtractor graphics, int left, int mouseX, int mouseY) {
		List<Component> tooltip = setBlockTooltip(left, mouseX, mouseY);
		if (tooltip.isEmpty()) {
			return;
		}
		graphics.setComponentTooltipForNextFrame(this.font, tooltip, mouseX, mouseY);
	}

	/**
	 * 세트 덩어리 위의 마우스. 어느 줄에도 없으면 빈 목록이다.
	 *
	 * <p>세로 범위는 세트 줄이 실제로 차지하는 만큼뿐이라, 그 아래 증강 목록 자리에서는 늘
	 * 빈 목록이다. 두 자리는 {@link #perkListTop()} 을 경계로 갈린다.
	 */
	private List<Component> setBlockTooltip(int left, int mouseX, int mouseY) {
		if (setLines.isEmpty()) {
			return List.of();
		}
		// 마우스를 받는 가로 폭은 글자 폭이다. 판 오른쪽 빈자리까지 받으면 마우스를 어디에
		// 두어도 툴팁이 떠 다른 것을 읽을 수 없다.
		int width = PerkSetLines.blockWidth(setLines, this.font::width, 0);
		int row = PerkSetLines.rowAt(mouseX, mouseY, left, width, setBlockTop(), ROW_HEIGHT,
				setLines.size());
		if (row < 0) {
			return List.of();
		}
		return typeTooltip(setLines.get(row).typeId());
	}

	/**
	 * 유형 하나의 툴팁.
	 *
	 * <p>세트 줄은 유형 하나에 한 줄이라 여기서 받는 유형도 늘 하나다. 세트를 아직 모르는
	 * 유형이면 줄이 하나도 안 나오고, 그러면 상자 자체를 띄우지 않는다.
	 */
	private List<Component> typeTooltip(String typeId) {
		List<PerkSetTooltipLines.Row> rows = PerkSetTooltipLines.build(
				ClientPerkSets.tooltip(typeId), ClientPerkSets.displayName(typeId),
				PerkSetTooltip.MAX_ROWS, TOOLTIP_PALETTE);
		List<Component> tooltip = new ArrayList<>(rows.size());
		for (PerkSetTooltipLines.Row row : rows) {
			// 빈 줄에 색을 입히면 알파가 섞여 아무것도 아닌 자리에 얇은 띠가 뜬다.
			tooltip.add(row.blank()
					? Component.empty()
					: Component.literal(row.text()).withColor(rgb(row.color())));
		}
		return tooltip;
	}

	/**
	 * 0xAARRGGBB 색에서 알파를 뗀다.
	 *
	 * <p>{@code MutableComponent.withColor} 는 24비트 색만 받는다. 알파를 남긴 채 넘기면
	 * 상위 바이트가 색값에 섞여 <b>등급색이 엉뚱한 색으로 뜬다.</b>
	 */
	private static int rgb(int argb) {
		return argb & 0xFFFFFF;
	}

	/** 목록 오른쪽의 스크롤 막대. 지금 어디쯤을 보고 있는지만 알려 준다. */
	private void renderPerkScrollBar(GuiGraphicsExtractor graphics, int left, int top) {
		int barLeft = left + PANEL_WIDTH + 1;
		int barRight = barLeft + SCROLL_BAR_WIDTH;
		graphics.fill(barLeft, top, barRight, top + perkViewHeight, SCROLL_TRACK);
		int thumb = PanelScroll.thumbHeight(perkContentHeight, perkViewHeight, SCROLL_THUMB_MIN);
		int thumbTop = PanelScroll.thumbTop(top, perkViewHeight, thumb, perkScroll,
				perkContentHeight);
		graphics.fill(barLeft, thumbTop, barRight, thumbTop + thumb, SCROLL_THUMB);
	}

	/**
	 * 증강 탭에서 휠을 굴리면 목록을 위아래로 옮긴다.
	 *
	 * <p>넘칠 것이 없으면 아무것도 하지 않고 넘겨서, 위젯이 휠을 쓰는 다른 탭의 동작을
	 * 가로채지 않는다.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (tab == Tab.PERKS && scrollY != 0.0
				&& PanelScroll.overflows(perkContentHeight, perkViewHeight)) {
			int moved = perkScroll - (int) Math.round(scrollY) * SCROLL_STEP;
			perkScroll = PanelScroll.clamp(moved, perkContentHeight, perkViewHeight);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** 등급별 글자색. 서버가 보낸 등급 이름을 그대로 받는다. */
	private static int rarityColor(String rarity) {
		return switch (rarity) {
			case "gold" -> 0xFFFFC63A;
			case "prism" -> 0xFF5FE0D8;
			default -> 0xFFC0C6CC;
		};
	}

	// ------------------------------------------------------------------ 거들기

	private boolean isOnline(UUID id) {
		return this.minecraft != null && this.minecraft.getConnection() != null
				&& this.minecraft.getConnection().getPlayerInfo(id) != null;
	}

	private int onlineCount() {
		int count = 0;
		for (UUID id : ClientTeamState.memberIds()) {
			if (isOnline(id)) {
				count++;
			}
		}
		return count;
	}

	/** 20.0 처럼 소수점이 의미 없는 값을 "20" 으로 보여 준다. */
	private static String trimZero(float value) {
		return value == Math.rint(value)
				? String.valueOf((long) value)
				: String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** {@code /shareteam <rest>} 를 서버로 보낸다. */
	private void run(String rest) {
		Minecraft client = this.minecraft;
		if (client == null || client.getConnection() == null) {
			return;
		}
		client.getConnection().sendCommand("shareteam " + rest);
	}

	@Override
	public void tick() {
		boolean inTeam = ClientTeamState.inTeam();
		if (TeamCreationFlow.created(awaitingCreate, inTeam)) {
			// 성공했을 때만 이름 칸을 비운다. 실패는 여기까지 오지 않으므로 적던 이름이 남는다.
			newTeamName = TeamCreationFlow.nameAfterResult(awaitingCreate, inTeam, newTeamName);
			awaitingCreate = false;
			// 팀을 만든 직후가 곧 「게임 시작」을 누를 자리다. 그 단추는 「팀」 탭에 있으므로,
			// 결과를 기다리는 사이에 다른 탭으로 옮겨 갔다면 여기서 되돌린다.
			tab = Tab.TEAM;
			perkScroll = 0;
			// 새로 그려질 단추가 확인 단계로 시작하면 한 번만 눌러도 회차가 시작된다.
			startConfirming = false;
			rebuild();
			return;
		}
		String now = signature();
		if (!now.equals(lastSignature)) {
			rebuild();
		}
	}

	/** 위젯 구성을 좌우하는 값만 모은 요약. 이 값이 그대로면 다시 만들 필요가 없다. */
	private String signature() {
		return tab + "|" + ClientTeamState.inTeam() + "|" + ClientTeamState.isLeader()
				// 시작하면 단추가 사라져야 하고, 확인 단계가 바뀌면 글자와 색이 바뀐다.
				+ "|" + ClientTeamState.runStarted() + startConfirming
				+ "|" + ClientTeamState.memberIds().size() + "|" + ClientTeamState.swapEnabled()
				+ "|" + ClientTeamState.swapIntervalMinutes() + "|" + ClientTeamState.perksEnabled()
				+ "|" + ClientTeamState.maxHealth() + "|" + PerkClientState.hasPending()
				// 증강이 늘면 목록을 다시 접어야 한다. init() 이 그 일을 한다.
				+ "|" + PerkClientState.owned().size()
				// 유적 좌표는 글자까지 견준다. 「유적 감별사」를 가진 채로 서버를 다시 켜면
				// 보유 개수는 그대로인데 좌표만 새로 채워져 오고(PerkRuinSurvey.ensure),
				// 그때 다시 접지 않으면 화면이 계속 좌표 없는 상태로 남는다. 줄은 넷까지라
				// (RuinSurveyEffect.MAX_STRUCTURES) 매 틱 이어 붙여도 값이 싸다.
				+ "|" + String.join("·", PerkClientState.ruinCoords())
				// 세트가 켜지거나 진행도가 오르면 줄의 글자가 바뀌고, 줄 수가 바뀌면 아래
				// 증강 목록이 통째로 내려간다. 여기 안 넣으면 세트가 켜져도 화면이 그대로다.
				+ "|" + ClientPerkSets.signature()
				+ "|" + newTeamPerks + newTeamDamageAlert + newTeamDeathAlert + newTeamDifficulty
				+ "|" + newTeamMaxHealth + "," + newTeamSwapMinutes + "," + newTeamRerollCount
				+ "|" + invitableNames();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
