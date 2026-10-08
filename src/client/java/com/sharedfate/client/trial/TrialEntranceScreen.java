package com.sharedfate.client.trial;

import com.sharedfate.net.TrialEntranceAcceptC2SPayload;
import com.sharedfate.net.TrialEntranceOfferPayload;
import com.sharedfate.sync.TrialWarning;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.List;

/**
 * 엔드에 처음 들어섰을 때 뜨는 <b>입장 수락창.</b>
 *
 * <h2>사람이 정한 글 — 2026-10-01 에 적어 준 그대로다</h2>
 *
 * <pre>
 * 시련이 곧 시작됩니다.
 *
 * 엔더드래곤
 * 취소할 수 없습니다.
 * </pre>
 *
 * <p>그리고 <b>「이정도만」</b>이라고 했다. 곧 <b>글은 이것만</b>이다 — 전에 있던 「엔더 드래곤
 * 시련 전투가 시작됩니다」·「팀 전원이 엔드 중앙의 기반암 단상으로 모이고 입장 연출이
 * 돕니다」·팀 이름 줄·바닥의 「N초 뒤 자동으로 시작합니다」를 모두 걷었다.
 *
 * <p>⚠ 사람이 「취소할<b>수</b> 없습니다」로 적었지만 <b>띄어쓰기를 바르게</b> 넣었다. 화면에
 * 나가는 글이고 이 저장소의 다른 글은 띄어쓰기가 바르다.
 *
 * <h2>글이 아닌 것 셋은 남겼다</h2>
 *
 * <p>「확인」 단추 · 리더가 아닌 사람의 「〈리더〉의 확인을 기다립니다」 줄 · 남은 시간 막대.
 * 셋 다 <b>기능이고 글 장식이 아니다</b> — 단추가 없으면 확인할 길이 없고, 기다림 줄이 없으면
 * 네 사람이 서로 상대를 기다리며 제한시간을 다 쓰고, 막대가 없으면 <b>ESC 도 안 듣는 창이
 * 언제 끝나는지</b> 알 길이 없다.
 *
 * <h2>층을 셋으로 갈랐다 — 크기 하나로는 모자란다</h2>
 *
 * <ol>
 *   <li><b>「시련이 곧 시작됩니다.」</b> — 가장 큰 배율({@link #HEADLINE_SCALES}) · 굵게 ·
 *       <b>흰색</b>. 흰색은 바닐라 기본 글자색이라 새 색을 만들지 않는다</li>
 *   <li><b>「엔더드래곤」</b> — 보스 이름 줄이다. <b>배율은 1 로 두고 굵게 + 색으로</b> 층을
 *       만든다. ⚠ 배율로 가르지 않은 것은 값 때문이다 — 1080p 기본 GUI 배율 4 에서 판 안쪽이
 *       244px 뿐이라 <b>3배가 들어가는 글이 없고</b>, 머리글이 2 면 그 아래 칸은 1 하나뿐이다.
 *       색은 {@link TrialWarning.Colors#MARKED} 보라를 쓴다 — 26.3 바이트코드에서
 *       {@code EnderDragonFight.init} 이 드래곤 보스바를 {@code BossBarColor.PINK} 로 만드는
 *       것을 확인했고, 저장소 팔레트에서 그쪽에 가장 가까운 색이다</li>
 *   <li><b>「취소할 수 없습니다.」</b> — 배율 1 · 굵지 않게 · <b>{@link TrialWarning.Colors#DEADLY}
 *       빨강</b>. 이 저장소에서 빨강은 「서 있으면 죽는다」이고 룰렛 결과색과 같다 — 경고색이다</li>
 * </ol>
 *
 * <p>⚠ <b>새 색을 만들지 않았다.</b> {@code TrialRouletteScreen} 과 같은 어둠 한 겹, 같은 판,
 * 같은 팔레트다.
 *
 * <h2>고르는 화면이 아니다 — 알리는 화면이다</h2>
 *
 * <p>취소가 없다. 엔드 입장은 원래 돌아올 수 없는 문턱이라 「안 할 수 있다」고 말하면 거짓이 된다
 * ({@code DragonTrialManager} 의 「확인을 묻지는 않는다」). 이 창이 하는 일은 <b>판이 바뀌기 전에
 * 팀을 한 번 멈춰 세우는 것</b>이다. 사람이 적어 준 둘째 줄이 그 사실을 한 줄로 말한다.
 *
 * <h2>리더가 아닌 사람에게는 단추를 안 그린다</h2>
 *
 * <p>{@link TrialEntranceOfferPayload#canConfirm()} 는 <b>서버가 사람마다 정해 보낸 값</b>이다.
 * 여기서 리더 UUID 와 내 UUID 를 견주지 않는 것은 그쪽 묶음 설명에 적어 두었다 — 판단이 두 벌이
 * 되면 「눌리는데 아무 일도 안 일어나는 단추」가 생기고, 26.3 에서는 클라이언트와 서버가 다른
 * 길을 지나므로 그 둘이 어긋날 자리가 실제로 있다.
 *
 * <p>⚠ 단추를 안 그리는 것은 <b>안내</b>이고 자물쇠가 아니다. 막는 것은 서버다
 * ({@code TrialEntranceGate.mayConfirm}).
 *
 * <h2>닫히는 길이 넷이다 — ESC 는 그 넷에 없다</h2>
 *
 * <ol>
 *   <li><b>확인을 눌렀다</b> — 그 자리에서 닫는다. 서버가 같은 틱에 팀을 단상으로 모으고
 *       18.5초 입장 연출을 시작하므로, 창을 붙들고 있으면 그 연출의 앞머리를 가린다</li>
 *   <li><b>서버가 닫으라고 했다</b>({@code TrialEntranceClosePayload}) — 리더가 1초 만에
 *       눌렀을 때 <b>나머지 사람의 창을 닫는 유일한 길</b>이다</li>
 *   <li>⚠ <b>드래곤이 죽어 창이 뜻을 잃었다</b> — 2026-10-01 에 늘었다. 서버가
 *       {@code TrialEntranceGate.cancel} 로 2번과 같은 꾸러미를 보낸다. 사람 쪽에서 보면
 *       2번과 구별되지 않는 길이다</li>
 *   <li><b>제한시간이 다 됐다</b> — 서버가 보낸 {@link TrialEntranceOfferPayload#timeoutTicks()}
 *       를 스스로 센다. 2·3번 꾸러미가 유실되면 이것만 남고, 없으면 사람이 전투 한가운데에 갇힌다.
 *       {@code TrialRouletteScreen} 이 같은 까닭으로 타이머를 직접 든다</li>
 * </ol>
 *
 * <h2>셈은 {@link Countdown} 과 {@link #layout} 이 한다</h2>
 *
 * <p>「언제 닫는가」와 「판이 어디에 놓이는가」를 마인크래프트 클래스 없이 돌 수 있게 떼어 놓았다.
 * 렌더링은 시험할 수 없고, 이 환경에서는 {@code runClient} 가 아예 뜨지 않는다
 * ({@code 0xC0000005}).
 */
public class TrialEntranceScreen extends Screen {

	/** 화면 가장자리에서 띄우는 여백. {@code TrialRouletteScreen} 과 같은 값이다. */
	static final int SCREEN_MARGIN = 10;
	static final int PLATE_PADDING = 8;
	/** 판이 아무리 넓은 화면에서도 넘지 않는 가로. */
	static final int PLATE_MAX_WIDTH = 260;
	static final int BAR_HEIGHT = 2;
	static final int BAR_GAP = 4;
	static final int BUTTON_HEIGHT = 20;
	/** 단추 가로의 상한. 판보다 좁아야 「판에 붙은 단추」로 읽힌다. */
	static final int BUTTON_MAX_WIDTH = 120;
	/** 판과 단추 사이의 틈. 읽기 전에 손이 먼저 가지 않게 둔다. */
	static final int BUTTON_GAP = 10;
	/**
	 * 층 사이의 틈.
	 *
	 * <p>사람이 적어 준 글에 머리글과 보스 이름 사이가 <b>빈 줄 한 칸</b>이었다. 빈 줄을 그대로
	 * 담지 않고 틈으로 바꾼 것은, 빈 줄로 두면 줄 높이(9px)가 통째로 들어가 판이 그만큼
	 * 길어지는데 <b>여백은 6px 로도 같은 말을 한다</b>. ⚠ 보스 이름과 경고 사이에도 같은 틈을
	 * 둔다 — 사람 글에서는 붙어 있지만, 셋을 모두 가운데로 맞추고 보니 붙여 두면 두 줄이
	 * 한 덩이로 읽힌다.
	 */
	static final int LINE_GAP = 6;

	/**
	 * 머리글의 배율 후보. <b>앞에서부터</b> 들어가는 첫 것을 쓴다.
	 *
	 * <p>⚠ 3 을 넣지 않았다. 1080p 기본 GUI 배율 4 에서 판 안쪽이 {@link #innerWidth} = 244px
	 * 이고, 판 가로는 {@link #PLATE_MAX_WIDTH} 로 <b>화면이 넓어져도 안 늘어난다</b> — 곧 3배가
	 * 들어가는 길이는 81px 뿐이라 한글 아홉 자가 절대 못 들어간다. 후보로 적어 두면 「층이
	 * 셋」으로 읽히는데 실제로는 한 번도 안 골리는 죽은 값이 된다.
	 */
	static final int[] HEADLINE_SCALES = {2, 1};

	/** 머리글. 흰색은 바닐라 기본 글자색이라 새 색이 아니다. */
	private static final int HEADLINE_COLOR = 0xFFFFFFFF;
	/** 보스 이름. 바닐라 드래곤 보스바가 {@code BossBarColor.PINK} 인 것에 맞춘 보라다. */
	private static final int BOSS_COLOR = 0xFF000000 | TrialWarning.Colors.MARKED;
	/** 경고. 이 저장소에서 빨강은 「서 있으면 죽는다」다. */
	private static final int WARNING_COLOR = 0xFF000000 | TrialWarning.Colors.DEADLY;
	/** 「기다린다」의 노랑. {@code TrialRouletteScreen} 이 룰렛 글자색으로 쓰는 그 색이다. */
	@SuppressWarnings("deprecation")
	private static final int WAITING_COLOR = 0xFF000000 | TrialWarning.Colors.REQUIRED;

	private static final int VEIL = 0xC8060608;
	private static final int PLATE_BACKGROUND = 0xE60C0C10;
	/** 판 테두리. 경고색이라 판 전체가 「되돌릴 수 없다」를 말한다. */
	private static final int PLATE_BORDER = WARNING_COLOR;
	private static final int BAR_BACKGROUND = 0x80202028;

	private static final int TICKS_PER_SECOND = 20;

	/** 첫 줄. 사람이 적어 준 그대로다. */
	private static final Component HEADLINE = Component.literal("시련이 곧 시작됩니다.");
	/** 보스 이름 줄. */
	private static final Component BOSS_NAME = Component.literal("엔더드래곤");
	/** 경고 줄. 사람 글의 「취소할수」를 띄어쓰기만 바르게 고쳤다. */
	private static final Component WARNING = Component.literal("취소할 수 없습니다.");
	private static final Component CONFIRM_LABEL = Component.literal("확인");

	private final long openedTick;
	private final Countdown countdown;
	private String leaderName;
	private boolean canConfirm;

	/** 창이 떠 있은 틱. 이 화면이 스스로 센다 — 남은 시간은 꾸러미로 오지 않는다. */
	private int elapsedTicks;
	/** 이미 보냈는지. 두 번 눌러도 한 번만 보낸다. */
	private boolean sent;

	// 아래는 init() 이 화면 크기에 맞춰 다시 재는 값들이다.
	private int plateLeft;
	private int plateWidth;
	private Plate plate = new Plate(0, 0, 0, 0);
	private int headlineScale = 1;
	private List<FormattedCharSequence> headlineLines = List.of();
	private List<FormattedCharSequence> bossLines = List.of();
	private List<FormattedCharSequence> warningLines = List.of();

	public TrialEntranceScreen(TrialEntranceOfferPayload payload) {
		super(HEADLINE);
		this.openedTick = payload.openedTick();
		this.leaderName = payload.leaderName();
		this.canConfirm = payload.canConfirm();
		this.countdown = new Countdown(payload.timeoutTicks());
	}

	/** 이 창의 이름표. 같은 창이 또 왔는지, 닫으라는 지시가 이 창을 겨냥한 것인지 가린다. */
	public long openedTick() {
		return openedTick;
	}

	/**
	 * 1초마다 다시 오는 같은 꾸러미를 받아들인다. <b>시계는 건드리지 않는다.</b>
	 *
	 * <p>서버가 창을 1초마다 다시 보내는 까닭은 {@code TrialEntranceGate.OFFER_RESEND_TICKS} 에
	 * 있다 — 사망 화면이나 증강 선택창 때문에 못 열린 사람에게 다시 기회를 주려는 것이다. 이미
	 * 떠 있는 창에서는 <b>리더 이름과 누를 수 있는지만</b> 갈아 끼운다. 지난 틱 수를 되돌리면
	 * 남은 시간이 매초 제자리로 튀고, 그러면 창이 영영 닫히지 않는 것처럼 보인다.
	 *
	 * <p>누를 수 있는지가 달라졌을 때만 단추를 다시 만든다 — 붙이거나 떼는 일이라 위젯 목록을
	 * 손봐야 한다. ⚠ 바닐라 {@code Screen.rebuildWidgets()} 를 쓰지 않는다. 그쪽은
	 * {@link #init()} 을 다시 부르므로 <b>판 자리를 처음부터 다시 재고</b>, 그 길에서는 위젯만
	 * 갈아 끼우려던 것이 창 전체를 다시 세우는 일이 된다.
	 */
	public void absorb(TrialEntranceOfferPayload payload) {
		leaderName = payload.leaderName();
		if (canConfirm == payload.canConfirm()) {
			return;
		}
		canConfirm = payload.canConfirm();
		buildConfirmButton();
	}

	/**
	 * 서버가 닫으라고 했다. <b>겨냥한 창이 맞을 때만 닫는다.</b>
	 *
	 * <p>전투가 끝나고 다시 엔드에 들어가면 창이 또 열린다 — 늦게 도착한 지시가 그 다음 창을 닫아
	 * 버리지 않게 이름표를 맞춰 본다.
	 */
	public void closeFromServer(long closingTick) {
		if (closingTick == openedTick) {
			onClose();
		}
	}

	@Override
	protected void init() {
		int lineHeight = this.font.lineHeight;
		plateWidth = plateWidthFor(this.width);
		plateLeft = (this.width - plateWidth) / 2;
		int innerWidth = innerWidth(this.width);

		// 큰 글자의 배율을 먼저 정한다. ⚠ 1080p 의 기본 GUI 배율이 4 라 화면이 480×270 으로
		// 잡힌다 — 「260px 짜리 판에 2배 글자」가 들어가는지는 재 봐야 아는 것이고, 안 재고 그리면
		// 글자가 판 밖으로 나간다. 룰렛 화면이 같은 수법을 쓴다.
		Component headline = HEADLINE.copy().withStyle(ChatFormatting.BOLD);
		headlineScale = fitScale(HEADLINE_SCALES, this.font.width(headline), innerWidth);
		headlineLines = List.copyOf(
				this.font.split(headline, Math.max(8, innerWidth / headlineScale)));
		bossLines = List.copyOf(this.font.split(
				BOSS_NAME.copy().withStyle(ChatFormatting.BOLD), innerWidth));
		warningLines = List.copyOf(this.font.split(WARNING, innerWidth));

		plate = layout(this.height, lineHeight, headlineScale,
				headlineLines.size(), bossLines.size(), warningLines.size());

		buildConfirmButton();
	}

	/** 판의 가로. 화면이 아무리 넓어도 {@link #PLATE_MAX_WIDTH} 를 넘지 않는다. */
	static int plateWidthFor(int screenWidth) {
		return Math.min(PLATE_MAX_WIDTH, Math.max(80, screenWidth - SCREEN_MARGIN * 2));
	}

	/**
	 * 판 <b>안쪽</b> 가로. 글이 들어갈 자리다.
	 *
	 * <p>⚠ 화면 크기를 가정하지 않으려고 떼어 둔 것이고, 시험이 480 을 직접 넣어 본다. 6장의
	 * 사고(78px 자리에 104px 짜리 줄을 그리려다 <b>아무것도 안 그리고 빠져나갔다</b>)가 바로 이
	 * 값을 안 재서 난 것이다.
	 */
	static int innerWidth(int screenWidth) {
		return Math.max(16, plateWidthFor(screenWidth) - PLATE_PADDING * 2);
	}

	/**
	 * 판과 그 아래 것들의 세로 자리. <b>월드도 글꼴도 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <p>아래쪽 몫을 먼저 떼어 둔다(단추 틈 + 단추 + 막대 틈 + 막대). 그래야 판이 아무리 길어도
	 * <b>「확인」 단추를 덮지 않는다</b> — 증강 카드가 겪은 사고가 그것이었고, 덮으면 ESC 도 안
	 * 듣는 창에서 누를 수 있는 것이 하나도 없어진다.
	 *
	 * <p>⚠ <b>아무것도 안 그리고 빠져나가는 길을 만들지 않는다.</b> 판 높이는 아무리 좁은
	 * 화면에서도 {@code 위아래 여백 + 한 줄} 보다 작아지지 않는다. 6장의 사고를 값으로 막는
	 * 자리다 — 글이 넘치면 넘치게 두고({@code enableScissor} 가 잘라 준다) 안 그리지는 않는다.
	 *
	 * @param screenHeight  화면 세로. 1080p 기본 설정이면 <b>270</b>
	 * @param lineHeight    글꼴 한 줄 높이
	 * @param headlineScale {@link #fitScale} 이 고른 머리글 배율
	 * @param headlineRows  줄바꿈 뒤 머리글의 줄 수
	 * @param bossRows      보스 이름의 줄 수
	 * @param warningRows   경고의 줄 수
	 */
	static Plate layout(int screenHeight, int lineHeight, int headlineScale,
			int headlineRows, int bossRows, int warningRows) {
		int rowHeight = Math.max(1, lineHeight);
		int content = Math.max(headlineRows, 0) * rowHeight * Math.max(1, headlineScale);
		if (bossRows > 0) {
			content += LINE_GAP + bossRows * rowHeight;
		}
		if (warningRows > 0) {
			content += LINE_GAP + warningRows * rowHeight;
		}
		int below = BUTTON_GAP + BUTTON_HEIGHT + BAR_GAP + BAR_HEIGHT;
		int room = screenHeight - SCREEN_MARGIN * 2 - below;
		int minimum = PLATE_PADDING * 2 + rowHeight;
		int height = Math.max(minimum, Math.min(PLATE_PADDING * 2 + content,
				Math.max(minimum, room)));
		int top = SCREEN_MARGIN + Math.max(0, (room - height) / 2);
		int buttonTop = top + height + BUTTON_GAP;
		return new Plate(top, height, buttonTop, buttonTop + BUTTON_HEIGHT + BAR_GAP);
	}

	/** {@link #layout} 이 잡은 세로 자리들. */
	record Plate(int top, int height, int buttonTop, int barTop) {

		int bottom() {
			return top + height;
		}

		/** 막대 아래 끝. 화면 밖으로 나가는지 시험이 이것으로 본다. */
		int barBottom() {
			return barTop + BAR_HEIGHT;
		}

		/** 판 안에 글을 그릴 자리가 있는가. <b>거짓이면 아무것도 안 그리는 사고다.</b> */
		boolean hasTextRoom() {
			return height > PLATE_PADDING * 2;
		}
	}

	/**
	 * 단추를 붙인다. <b>리더에게만</b>이다.
	 *
	 * <p>리더가 아닌 사람에게는 위젯을 하나도 안 붙인다 — 흐린 단추를 그려 두면 「누르면 되는데 왜
	 * 안 되지」가 되고, 이 창에서 그 사람이 할 일은 아예 없다.
	 */
	private void buildConfirmButton() {
		clearWidgets();
		if (!canConfirm) {
			return;
		}
		int buttonWidth = Math.min(BUTTON_MAX_WIDTH, plateWidth);
		addRenderableWidget(Button.builder(CONFIRM_LABEL, button -> confirm())
				.bounds(plateLeft + (plateWidth - buttonWidth) / 2, plate.buttonTop(),
						buttonWidth, BUTTON_HEIGHT)
				.build());
	}

	/**
	 * 「확인」을 눌렀다.
	 *
	 * <p>보내고 <b>그 자리에서 닫는다.</b> 서버의 닫기 꾸러미를 기다리지 않는 것은, 서버가 같은
	 * 틱에 팀을 단상으로 옮기고 18.5초 연출을 시작하기 때문이다 — 창을 한 틱이라도 더 들고 있으면
	 * 그 연출의 앞머리를 내 화면만 못 본다.
	 */
	private void confirm() {
		if (sent) {
			return;
		}
		sent = true;
		if (ClientPlayNetworking.canSend(TrialEntranceAcceptC2SPayload.TYPE)) {
			ClientPlayNetworking.send(new TrialEntranceAcceptC2SPayload(openedTick));
		}
		onClose();
	}

	@Override
	public void tick() {
		super.tick();
		if (countdown.finished(elapsedTicks)) {
			onClose();
			return;
		}
		elapsedTicks++;
	}

	/**
	 * {@code scales} 에서 <b>앞에서부터</b> 들어가는 첫 배율. 하나도 안 들어가면 마지막 것.
	 *
	 * <p>{@code TrialRouletteScreen.fitScale} 과 같은 셈이다. 두 화면이 같은 판을 쓰므로 같은
	 * 판단을 해야 하고, 「큰 것부터 대 보고 안 들어가면 낮춘다」는 눈으로 봐야만 알 수 있는 자리에
	 * 두면 아무도 못 지킨다.
	 */
	static int fitScale(int[] scales, int contentWidth, int available) {
		if (scales == null || scales.length == 0) {
			return 1;
		}
		for (int scale : scales) {
			if (contentWidth * scale <= available) {
				return scale;
			}
		}
		return scales[scales.length - 1];
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		int centerX = this.width / 2;
		int lineHeight = this.font.lineHeight;

		// 전투 화면이 그대로 밝으면 창이 묻힌다. 어둠 한 겹을 깔아 여기만 보이게 한다.
		graphics.fill(0, 0, this.width, this.height, VEIL);

		int plateRight = plateLeft + plateWidth;
		int plateTop = plate.top();
		int plateBottom = plate.bottom();
		graphics.fill(plateLeft, plateTop, plateRight, plateBottom, PLATE_BACKGROUND);
		graphics.outline(plateLeft, plateTop, plateWidth, plate.height(), PLATE_BORDER);

		graphics.enableScissor(plateLeft + 1, plateTop + 1, plateRight - 1, plateBottom - 1);
		int y = plateTop + PLATE_PADDING;
		for (FormattedCharSequence line : headlineLines) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(centerX, y);
			pose.scale(headlineScale, headlineScale);
			graphics.centeredText(this.font, line, 0, 0, HEADLINE_COLOR);
			pose.popMatrix();
			y += lineHeight * headlineScale;
		}
		if (!bossLines.isEmpty()) {
			y += LINE_GAP;
			for (FormattedCharSequence line : bossLines) {
				graphics.centeredText(this.font, line, centerX, y, BOSS_COLOR);
				y += lineHeight;
			}
		}
		if (!warningLines.isEmpty()) {
			y += LINE_GAP;
			for (FormattedCharSequence line : warningLines) {
				graphics.centeredText(this.font, line, centerX, y, WARNING_COLOR);
				y += lineHeight;
			}
		}
		graphics.disableScissor();

		// 리더가 아닌 사람의 자리에는 단추 대신 「누구를 기다리는가」가 온다. 그것이 없으면 네
		// 사람이 서로 상대를 기다리며 제한시간을 다 쓴다.
		if (!canConfirm) {
			graphics.centeredText(this.font, waitingLine(), centerX,
					plate.buttonTop() + (BUTTON_HEIGHT - lineHeight) / 2, WAITING_COLOR);
		}

		renderCountdownBar(graphics, plate.barTop());

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * 남은 시간 막대. <b>글자 없이</b> 「언제 저절로 시작되는가」를 보여 준다.
	 *
	 * <p>사람이 「이정도만」이라고 해서 바닥의 「N초 뒤 자동으로 시작합니다」를 걷었다. 막대는
	 * 남긴다 — ESC 도 안 듣는 창에서 <b>끝이 있다는 사실</b>을 말하는 것이 이것뿐이다.
	 */
	private void renderCountdownBar(GuiGraphicsExtractor graphics, int top) {
		graphics.fill(plateLeft, top, plateLeft + plateWidth, top + BAR_HEIGHT, BAR_BACKGROUND);
		int filled = Math.round(plateWidth * countdown.fraction(elapsedTicks));
		if (filled > 0) {
			graphics.fill(plateLeft, top, plateLeft + filled, top + BAR_HEIGHT, WARNING_COLOR);
		}
	}

	/** 리더 이름을 모를 때는 「리더」로 적는다. 빈 이름으로 「 의 확인을」이 되면 안 된다. */
	private Component waitingLine() {
		String who = leaderName.isEmpty() ? "리더" : leaderName;
		return Component.literal(who + " 의 확인을 기다립니다");
	}

	/**
	 * ESC 로 닫을 수 없다.
	 *
	 * <p>닫아도 전투는 시작되므로 「닫으면 안 들어간다」로 읽힐 자리를 만들지 않는다.
	 * {@code TrialRouletteScreen} 과 같다 — 대신 {@link #tick()} 이 반드시 스스로 닫는다.
	 */
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	/** 서버는 얼지 않는다. 바닐라 드래곤이 이 창 뒤에서 날고 있다. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 이 패킷으로 창을 열어도 되는가.
	 *
	 * <p>제한시간이 0 이면 열지 않는다. <b>패킷은 밖에서 오는 값</b>이라 믿을 수 없고, 그 창은 뜨는
	 * 틱에 닫히므로 화면이 한 번 번쩍이는 것으로만 보인다 — 고장으로 읽힌다.
	 * {@code TrialRouletteScreen.shouldOpen} 이 빈 후보를 거르는 것과 같은 자리다.
	 */
	public static boolean shouldOpen(@Nullable TrialEntranceOfferPayload payload) {
		return payload != null && payload.timeoutTicks() > 0;
	}

	/**
	 * <b>언제 닫는가</b>와 <b>몇 초 남았는가</b>.
	 *
	 * <p>마인크래프트 클래스를 하나도 쓰지 않는다. {@code runClient} 가 이 환경에서 아예 뜨지
	 * 않으므로({@code 0xC0000005}) 화면을 띄워 보고 고칠 수 없고, 그러면 시험할 수 있는 모양으로
	 * 떼어 두는 것 말고 지킬 길이 없다.
	 *
	 * @param timeoutTicks 서버가 기다리는 전체 길이. <b>서버가 실어 보낸 값을 그대로 쓴다</b> —
	 *                     여기서 지어내면 서버가 이미 진행한 뒤에 창만 남거나 그 반대가 된다
	 */
	public record Countdown(int timeoutTicks) {

		public Countdown {
			timeoutTicks = Math.max(0, timeoutTicks);
		}

		/** 스스로 닫아야 하는 때인지. */
		public boolean finished(int elapsedTicks) {
			return elapsedTicks >= timeoutTicks;
		}

		/** 남은 초. 올림이라 마지막 1틱까지 1 이 남는다. */
		public int remainingSeconds(int elapsedTicks) {
			int remaining = Math.max(0, timeoutTicks - elapsedTicks);
			return (remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
		}

		/**
		 * 남은 비율 0.0~1.0. 막대의 채움이다.
		 *
		 * <p>제한시간이 0 이면 0 으로 나누지 않고 0 을 준다 — 어차피 그 창은 뜨는 틱에 닫힌다.
		 */
		public float fraction(int elapsedTicks) {
			if (timeoutTicks <= 0) {
				return 0.0F;
			}
			int remaining = Math.max(0, timeoutTicks - elapsedTicks);
			return Math.clamp((float) remaining / timeoutTicks, 0.0F, 1.0F);
		}
	}
}
