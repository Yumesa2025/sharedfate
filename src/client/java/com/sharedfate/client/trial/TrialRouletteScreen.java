package com.sharedfate.client.trial;

import com.sharedfate.net.TrialRoulettePayload;
import com.sharedfate.net.TrialRoulettePayload.TrialOption;
import com.sharedfate.sync.TrialRoulette;
import com.sharedfate.sync.TrialWarning;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 엔드 시련이 <b>정해지는</b> 것을 보여 주는 화면.
 *
 * <h2>돌 때도 있고 안 돌 때도 있다</h2>
 *
 * <p>같은 화면이 두 자리를 그린다. 룰렛이 도는 자리가 있고, 판은 멈추되 <b>정해진 카드 한 장의
 * 이름과 설명만</b> 보여 주는 자리가 있다({@code TrialCatalog.Reveal}). 가르는 값은
 * {@link TrialRoulettePayload#spinTicks()} 하나이고 <b>0 이면 굴림이 한 틱도 나오지 않는다</b> —
 * {@link Spin#frameAt} 이 처음부터 -1 을 돌려주므로 첫 프레임부터 결과와 설명이 떠 있다.
 * 카드가 한 장뿐인 풀에서 이름이 도는 것은 <b>결과가 정해진 굴림을 보여 주는 것</b>이라 연출이
 * 거짓말이 되기 때문이다.
 *
 * <p>붙잡아 두는 시간도 자리마다 다르므로 {@linkplain TrialRoulettePayload#holdTicks() 패킷에
 * 실려 온다.} 여기에 상수를 두지 않는 이유는 그쪽에 적어 두었다.
 *
 * <h2>고르는 화면이 아니다</h2>
 *
 * <p>증강 선택창({@code PerkOfferScreen})과 겉모습이 비슷해 보이면 안 된다. 그쪽은 좋은 것을
 * <b>고르는</b> 화면이라 카드를 늘어놓고 마우스를 받지만, 여기는 나쁜 것이 <b>정해지는</b>
 * 화면이다. 그래서 카드가 한 장뿐이고, 단추도 클릭 판정도 호버도 없다. 색도
 * {@link TrialWarning.Colors} 의 전투 규약을 그대로 쓴다 — 돌 때는 {@code REQUIRED} 노랑,
 * 멈추면 {@code DEADLY} 빨강이다. <b>새 색을 만들지 않는다.</b> 규약에 없는 것은 배경·글자에
 * 쓰는 무채색뿐이다.
 *
 * <h2>연출은 클라이언트가 스스로 돌린다</h2>
 *
 * <p>서버는 {@link TrialRoulettePayload} 하나만 보내고 그 뒤로는 아무것도 보내지 않는다.
 * 그래서 <b>타이머를 화면이 들고 스스로 닫는다.</b> 서버의 닫기 지시를 기다리면 그 패킷이
 * 유실되는 순간 플레이어가 드래곤 전투 한가운데에서 화면에 갇힌다. ESC 를 막아 둔 화면이라
 * 그때는 빠져나갈 길이 아예 없다.
 *
 * <h2>서버와 같은 셈을 써야 한다</h2>
 *
 * <p>칸이 바뀌는 간격도, 「마지막 칸이 결과가 되도록 시작점을 거꾸로 맞추는」 계산도
 * {@link TrialRoulette} 이 서버에서 쓰는 것과 <b>같아야 한다.</b> 어긋나면 화면은 A 에서
 * 멈췄는데 실제로는 B 가 걸리고, 그것이 서버 로그의 「몇 번째 칸」과도 안 맞아 원인을 찾을
 * 길이 없어진다. 그래서 간격을 직접 적지 않고 {@link TrialRoulette#gaps()} 를 읽어다 쓴다.
 *
 * <h2>계산은 {@link Spin} 이 한다</h2>
 *
 * <p>렌더링은 시험할 수 없으므로 「몇 틱에 몇 번째 카드를 띄우는가」와 「언제 닫는가」를
 * 전부 {@link Spin} 으로 떼어 놓았다. {@code PerkOfferCardRow} 와 같은 방식이다 —
 * 마인크래프트 클래스를 하나도 쓰지 않으므로 화면을 띄우지 않고 시험할 수 있다.
 */
public class TrialRouletteScreen extends Screen {

	/** 화면 가장자리에서 띄우는 여백. */
	private static final int SCREEN_MARGIN = 10;
	/** 판 안쪽 여백. */
	private static final int PLATE_PADDING = 8;
	/** 판이 아무리 넓은 화면에서도 넘지 않는 가로. 더 넓히면 글줄이 길어져 읽기 나빠진다. */
	private static final int PLATE_MAX_WIDTH = 260;
	/** 이름과 설명 사이 구분선이 차지하는 세로 공간(위 여백 3 + 선 1 + 아래 여백 4). */
	private static final int SEPARATOR_BLOCK_HEIGHT = 8;
	/** 판 아래 카운트다운 막대의 두께. */
	private static final int BAR_HEIGHT = 2;
	/** 판과 막대 사이의 틈. */
	private static final int BAR_GAP = 4;

	/**
	 * 이름을 그릴 배율 후보. <b>앞에서부터 들어가는 것을 고른다.</b>
	 *
	 * <p>{@code PerkOfferScreen.ICON_SIZES} 와 같은 방식이고, 같은 사고를 막으려는 것이다 —
	 * 그쪽은 설명이 길어진 카드에서 아이콘이 통째로 사라졌다. 여기서는 배율을 먼저 재지 않으면
	 * <b>긴 이름이 판을 옆으로 밀어 화면 밖으로 나간다.</b> 자리가 없으면 배율을 낮추고,
	 * 그래도 모자라면 줄을 접는다.
	 */
	private static final int[] NAME_SCALES = {2, 1};

	// 유채색은 전투 색 규약에서만 가져온다. 나머지는 전부 무채색이다.
	/** 아직 안 정해졌다. 「여기 서 있어야 한다」의 노랑을 「지켜보라」로 쓴다. */
	private static final int NAME_SPINNING = 0xFF000000 | TrialWarning.Colors.REQUIRED;
	/** 정해졌다. 시련은 언제나 나쁜 것이므로 빨강이다. */
	private static final int NAME_RESULT = 0xFF000000 | TrialWarning.Colors.DEADLY;
	private static final int TITLE_COLOR = NAME_RESULT;

	private static final int VEIL = 0xC8060608;
	private static final int PLATE_BACKGROUND = 0xE60C0C10;
	private static final int PLATE_BORDER_SPINNING = 0x80FFFFFF;
	private static final int PLATE_BORDER_RESULT = NAME_RESULT;
	private static final int SEPARATOR = 0x60FFFFFF;
	private static final int TEXT_BODY = 0xFFC6C6CE;
	private static final int TEXT_DIM = 0xFF8E939C;
	private static final int BAR_BACKGROUND = 0x80202028;

	private static final int TICKS_PER_SECOND = 20;

	private final List<TrialOption> options;
	private final String triggerLabel;
	private final Spin spin;

	/** 연출이 시작된 뒤 지난 틱. 이 화면이 스스로 센다 — 서버는 더 보내 주지 않는다. */
	private int elapsedTicks;
	/**
	 * 마지막으로 소리를 낸 칸. 같은 칸에서 두 번 울리지 않게 기억한다.
	 *
	 * <p>{@link TrialRoulette#advance} 가 서버에서 하는 일과 같다.
	 */
	private int soundedFrame = -1;
	/** 멈춤 소리를 이미 냈는지. 멈춘 뒤 60틱 내내 울리면 안 된다. */
	private boolean revealSounded;

	// 아래는 init() 이 화면 크기에 맞춰 다시 재는 값들이다.
	private int plateLeft;
	private int plateTop;
	private int plateWidth;
	private int plateHeight;
	private int nameScale = 1;
	/** 후보마다 접어 둔 이름. 배율까지 반영해 접었으므로 그릴 때 다시 재지 않는다. */
	private List<List<FormattedCharSequence>> nameLines = List.of();
	/** 모든 후보 가운데 가장 많은 줄 수. 판 높이를 여기에 맞춰 두면 칸이 바뀌어도 안 흔들린다. */
	private int nameRows = 1;
	/** 결과 설명. 멈춘 뒤에만 그린다. 자리가 모자라면 뒤에서부터 잘려 있다. */
	private List<FormattedCharSequence> descriptionLines = List.of();

	public TrialRouletteScreen(TrialRoulettePayload payload) {
		super(Component.literal("시련"));
		this.options = List.copyOf(payload.options());
		this.triggerLabel = payload.triggerLabel();
		this.spin = Spin.of(payload);
	}

	/**
	 * 이 패킷으로 화면을 열어도 되는가.
	 *
	 * <p>후보가 하나도 없으면 열지 않는다. 서버가 그런 패킷을 만들지 않지만 <b>패킷은 밖에서
	 * 오는 값</b>이라 믿을 수 없고, 빈 룰렛이 열리면 이름도 설명도 없는 판이 4초 동안 떠 있다가
	 * 사라진다 — 고장으로 읽힌다.
	 */
	public static boolean shouldOpen(@Nullable TrialRoulettePayload payload) {
		return payload != null && !payload.options().isEmpty();
	}

	@Override
	protected void init() {
		int lineHeight = this.font.lineHeight;
		plateWidth = Math.min(PLATE_MAX_WIDTH, Math.max(80, this.width - SCREEN_MARGIN * 2));
		plateLeft = (this.width - plateWidth) / 2;
		int innerWidth = Math.max(16, plateWidth - PLATE_PADDING * 2);

		// 이름 배율을 먼저 정한다. 가장 긴 이름이 한 줄에 들어가는 배율이라야 판이 안 밀린다.
		// 굵게 그리므로 폭도 굵은 채로 잰다 — 굵으면 글자마다 1픽셀씩 넓어진다.
		List<Component> names = new ArrayList<>();
		int widest = 0;
		for (TrialOption option : options) {
			Component name = Component.literal(option.name()).withStyle(ChatFormatting.BOLD);
			names.add(name);
			widest = Math.max(widest, this.font.width(name));
		}
		nameScale = fitScale(NAME_SCALES, widest, innerWidth);

		List<List<FormattedCharSequence>> lines = new ArrayList<>();
		nameRows = 1;
		for (Component name : names) {
			// 배율을 곱해 그리므로 접는 폭은 배율로 나눈 값이다. 나누지 않으면 큰 글자가
			// 판 밖으로 삐져나간다.
			List<FormattedCharSequence> split =
					this.font.split(name, Math.max(8, innerWidth / nameScale));
			lines.add(split);
			nameRows = Math.max(nameRows, split.size());
		}
		nameLines = List.copyOf(lines);

		int headerBottom = SCREEN_MARGIN + lineHeight * 2 + 6;
		int footerTop = this.height - SCREEN_MARGIN - lineHeight;
		int room = Math.max(lineHeight * 2, footerTop - headerBottom - BAR_GAP - BAR_HEIGHT - 6);

		int nameBlock = PLATE_PADDING * 2 + nameRows * lineHeight * nameScale;
		// 설명은 남은 자리만큼만 담는다. 넘치는 줄을 그대로 두면 판 밖으로 흘러 아래 문구를
		// 덮는다 — 증강 카드가 겪은 사고와 같은 종류다.
		List<FormattedCharSequence> description = this.font.split(
				Component.literal(resultOption().description()), innerWidth);
		int descriptionRoom = room - nameBlock - SEPARATOR_BLOCK_HEIGHT;
		int maxRows = Math.max(0, descriptionRoom / lineHeight);
		descriptionLines = description.size() <= maxRows
				? List.copyOf(description)
				: List.copyOf(description.subList(0, maxRows));

		plateHeight = nameBlock + (descriptionLines.isEmpty()
				? 0 : SEPARATOR_BLOCK_HEIGHT + descriptionLines.size() * lineHeight);
		plateHeight = Math.min(plateHeight, room);
		plateTop = headerBottom + Math.max(0, (room - plateHeight) / 2);
	}

	/**
	 * {@code scales} 에서 <b>앞에서부터</b> 들어가는 첫 배율. 하나도 안 들어가면 마지막 것.
	 *
	 * <p>순수 함수로 떼어 둔 것은 시험이 붙들기 위해서다. 「큰 것부터 대 보고 안 들어가면
	 * 한 단계 낮춘다」는 판단은 글자가 화면 밖으로 나가느냐 마느냐를 가르는데, 화면을 띄우고
	 * 눈으로 봐야만 알 수 있는 자리에 두면 아무도 못 지킨다.
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

	/**
	 * 한 틱 진행시킨다. <b>닫는 것도 여기서 한다.</b>
	 *
	 * <p>서버 패킷을 기다리지 않는다. 이 화면은 ESC 로 못 닫으므로 스스로 닫지 않으면
	 * 플레이어가 전투 한가운데에 갇힌다.
	 */
	@Override
	public void tick() {
		if (spin.finished(elapsedTicks)) {
			this.onClose();
			return;
		}
		elapsedTicks++;

		int frame = spin.frameAt(elapsedTicks);
		if (frame >= 0) {
			if (frame != soundedFrame) {
				soundedFrame = frame;
				playStepSound();
			}
			return;
		}
		if (!revealSounded) {
			revealSounded = true;
			playRevealSound();
		}
	}

	/**
	 * 칸이 바뀔 때의 소리. 낮고 둔하다.
	 *
	 * <p>증강 뽑기({@code PerkDrawScreen})의 맑은 하이햇과 <b>달라야 한다.</b> 같은 소리를 쓰면
	 * 좋은 것이 돌아가는 줄 알고 화면을 안 본다.
	 */
	private void playStepSound() {
		if (this.minecraft == null) {
			return;
		}
		// 뒤로 갈수록 음이 내려간다. 느려지는 것과 함께 「가라앉는」 느낌이 난다.
		float pitch = 0.9F - 0.35F * spin.progress(elapsedTicks);
		this.minecraft.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.value(), pitch, 0.6F));
	}

	/** 멈춘 순간의 소리. 시련 생성기의 불길한 소리를 그대로 쓴다 — 이름부터 같은 것이다. */
	private void playRevealSound() {
		if (this.minecraft == null) {
			return;
		}
		this.minecraft.getSoundManager().play(
				SimpleSoundInstance.forUI(SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE, 1.0F, 0.9F));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		int centerX = this.width / 2;
		int lineHeight = this.font.lineHeight;
		boolean revealed = spin.revealed(elapsedTicks);

		// 전투 화면이 그대로 밝으면 룰렛이 묻힌다. 어둠 한 겹을 깔아 여기만 보이게 한다.
		graphics.fill(0, 0, this.width, this.height, VEIL);

		graphics.centeredText(this.font, this.title, centerX, SCREEN_MARGIN, TITLE_COLOR);
		if (!triggerLabel.isEmpty()) {
			graphics.centeredText(this.font, Component.literal(triggerLabel), centerX,
					SCREEN_MARGIN + lineHeight + 2, TEXT_DIM);
		}

		int plateRight = plateLeft + plateWidth;
		int plateBottom = plateTop + plateHeight;
		graphics.fill(plateLeft, plateTop, plateRight, plateBottom, PLATE_BACKGROUND);
		graphics.outline(plateLeft, plateTop, plateWidth, plateHeight,
				revealed ? PLATE_BORDER_RESULT : PLATE_BORDER_SPINNING);
		if (revealed) {
			// 정해진 뒤에는 테두리를 두 겹으로 둘러 한 단계 무겁게 한다.
			graphics.outline(plateLeft + 1, plateTop + 1, plateWidth - 2, plateHeight - 2,
					PLATE_BORDER_RESULT);
		}

		// 판 밖으로는 한 글자도 나가지 않는다. 자리 계산이 어긋나도 아래 문구를 덮지 않는다.
		if (plateBottom - 1 <= plateTop + 1) {
			super.extractRenderState(graphics, mouseX, mouseY, partialTick);
			return;
		}
		graphics.enableScissor(plateLeft + 1, plateTop + 1, plateRight - 1, plateBottom - 1);
		int y = plateTop + PLATE_PADDING;
		for (FormattedCharSequence line : shownNameLines()) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(centerX, y);
			pose.scale(nameScale, nameScale);
			graphics.centeredText(this.font, line, 0, 0,
					revealed ? NAME_RESULT : NAME_SPINNING);
			pose.popMatrix();
			y += lineHeight * nameScale;
		}
		// 설명은 멈춘 뒤에만 연다. 도는 동안 설명까지 바뀌면 읽히지도 않고 눈만 어지럽다.
		if (revealed && !descriptionLines.isEmpty()) {
			// 이름 줄 수가 후보마다 달라도 구분선 자리는 판 기준으로 고정이라 흔들리지 않는다.
			int separatorY = plateTop + PLATE_PADDING + nameRows * lineHeight * nameScale + 3;
			graphics.fill(plateLeft + PLATE_PADDING, separatorY,
					plateRight - PLATE_PADDING, separatorY + 1, SEPARATOR);
			int textY = separatorY + 5;
			for (FormattedCharSequence line : descriptionLines) {
				graphics.text(this.font, line, plateLeft + PLATE_PADDING, textY, TEXT_BODY);
				textY += lineHeight;
			}
		}
		graphics.disableScissor();

		if (revealed) {
			renderHoldBar(graphics, plateBottom);
		}
		graphics.centeredText(this.font, footerHint(revealed), centerX,
				this.height - SCREEN_MARGIN - lineHeight, TEXT_DIM);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	/**
	 * 판 아래의 남은 시간 막대. 화면이 <b>언제 사라지는지</b>를 글자 없이 보여 준다.
	 *
	 * <p>이것이 없으면 「멈췄는데 안 닫힌다」로 보여, 읽기를 마치기도 전에 ESC 를 누르게 된다 —
	 * 그런데 ESC 는 막혀 있다.
	 */
	private void renderHoldBar(GuiGraphicsExtractor graphics, int plateBottom) {
		int top = plateBottom + BAR_GAP;
		int width = plateWidth;
		graphics.fill(plateLeft, top, plateLeft + width, top + BAR_HEIGHT, BAR_BACKGROUND);
		int filled = Math.round(width * spin.holdFraction(elapsedTicks));
		if (filled > 0) {
			graphics.fill(plateLeft, top, plateLeft + filled, top + BAR_HEIGHT, NAME_RESULT);
		}
	}

	/** 지금 칸에 보일 이름. 멈춘 뒤에는 언제나 결과다. */
	private List<FormattedCharSequence> shownNameLines() {
		if (nameLines.isEmpty()) {
			return List.of();
		}
		return nameLines.get(Math.min(spin.shownOption(elapsedTicks), nameLines.size() - 1));
	}

	private TrialOption resultOption() {
		if (options.isEmpty()) {
			return new TrialOption("", "", "");
		}
		return options.get(Math.min(spin.resultIndex(), options.size() - 1));
	}

	private Component footerHint(boolean revealed) {
		if (!revealed) {
			return Component.literal("시련이 정해지는 중...");
		}
		int seconds = spin.remainingSeconds(elapsedTicks);
		return Component.literal(seconds > 0
				? seconds + "초 뒤 계속합니다"
				: "계속합니다");
	}

	/**
	 * ESC 로 닫을 수 없다. 뽑기이지 선택이 아니라 <b>무를 수도 건너뛸 수도 없다.</b>
	 *
	 * <p>대신 {@link #tick()} 이 반드시 스스로 닫는다. 증강 강제 오픈처럼 서버에 닫기를 맡기지
	 * 않는 이유는 이 화면이 받는 패킷이 하나뿐이기 때문이다.
	 */
	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}

	/** 드래곤 전투는 이 화면이 떠 있는 동안에도 돌아간다. 화면 때문에 게임이 멈추면 안 된다. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/**
	 * 룰렛이 <b>몇 틱에 몇 번째 카드를 띄우고 언제 닫히는가</b>.
	 *
	 * <h2>서버의 셈을 그대로 가져온다</h2>
	 *
	 * <p>칸 경계는 {@link TrialRoulette#gaps()} 를 누적해 만들고, 「마지막 칸이 결과가 되도록
	 * 시작점을 거꾸로 맞추는」 계산은 {@link TrialRoulette#candidateAt} 과 같은 식이다. 숫자를
	 * 여기 다시 적으면 어느 한쪽만 고쳐졌을 때 <b>화면은 A 에서 멈추고 실제로는 B 가 걸린다.</b>
	 *
	 * <h2>돌기 길이가 달라도 마지막 칸은 결과다</h2>
	 *
	 * <p>{@code spinTicks} 가 {@link TrialRoulette#TOTAL_TICKS} 보다 짧으면 뒤쪽 칸은 아예 오지
	 * 않는다. 그때 거꾸로 맞추는 기준을 «열다섯 번째 칸»으로 두면 마지막에 보이는 카드가 결과가
	 * 아니게 된다. 그래서 기준을 <b>실제로 지나갈 마지막 칸</b>({@link #lastFrame()})으로 잡는다.
	 * 기본값 77틱에서는 그 값이 서버와 똑같이 열다섯 번째 칸이다.
	 *
	 * <p>마인크래프트 클래스를 하나도 쓰지 않는다. 화면을 띄우지 않고 시험하기 위해서다.
	 *
	 * @param optionCount 후보 수. 0 이면 열리지 않아야 하는 패킷이다
	 * @param resultIndex 멈출 칸. <b>밖에서 온 값</b>이라 범위 안으로 눌러 둔다
	 * @param spinTicks   도는 시간(틱). 0 이하면 <b>한 틱도 돌지 않고</b> 결과부터 보여 준다
	 * @param holdTicks   결과를 붙잡아 두는 시간(틱). <b>서버가 실어 보낸 값을 그대로 쓴다</b> —
	 *                    여기서 지어내면 얼음이 풀린 판에 화면만 남거나 그 반대가 된다
	 */
	public record Spin(int optionCount, int resultIndex, int spinTicks, int holdTicks) {

		/** 칸이 바뀌는 경과 틱. {@link TrialRoulette#gaps()} 를 누적한 것이다. */
		private static final int[] FRAME_STARTS = frameStarts();

		public Spin {
			optionCount = Math.max(0, optionCount);
			// 패킷이 이미 음수를 막지만 여기서 한 번 더 누른다. 이 record 는 패킷 없이도
			// 만들어지고(시험), 범위를 벗어난 번호로 배열을 찔러 터지면 안 된다.
			resultIndex = optionCount <= 0
					? 0 : Math.clamp(resultIndex, 0, optionCount - 1);
			spinTicks = Math.max(0, spinTicks);
			holdTicks = Math.max(0, holdTicks);
		}

		public static Spin of(TrialRoulettePayload payload) {
			return new Spin(payload.options().size(), payload.resultIndex(), payload.spinTicks(),
					payload.holdTicks());
		}

		private static int[] frameStarts() {
			int[] gaps = TrialRoulette.gaps();
			int[] starts = new int[gaps.length];
			int running = 0;
			for (int index = 0; index < gaps.length; index++) {
				starts[index] = running;
				running += gaps[index];
			}
			return starts;
		}

		/**
		 * 이 화면이 실제로 쓰는 칸 간격. {@link #FRAME_STARTS} 를 간격으로 되돌린 값이다.
		 *
		 * <p>시험이 {@link TrialRoulette#gaps()} 와 맞대 보라고 열어 둔 창구다. 누가 여기에
		 * 숫자를 박아 넣으면 그 순간 어긋나서 걸린다 — <b>이 시험이 이 클래스의 존재 이유다.</b>
		 */
		public static int[] stepGaps() {
			int[] gaps = new int[FRAME_STARTS.length];
			for (int index = 0; index < FRAME_STARTS.length - 1; index++) {
				gaps[index] = FRAME_STARTS[index + 1] - FRAME_STARTS[index];
			}
			gaps[gaps.length - 1] =
					TrialRoulette.TOTAL_TICKS - FRAME_STARTS[FRAME_STARTS.length - 1];
			return gaps;
		}

		/**
		 * 이번 연출에서 실제로 지나갈 칸 수. 돌지 않으면 0.
		 *
		 * <p>{@code spinTicks} 가 길어도 칸은 {@link TrialRoulette#gaps()} 의 개수에서 멈춘다 —
		 * 남는 시간은 마지막 칸을 붙잡고 있는다. 그 칸이 결과라 붙잡혀도 거짓말이 아니다.
		 */
		public int frameCount() {
			if (spinTicks <= 0) {
				return 0;
			}
			int count = 0;
			for (int start : FRAME_STARTS) {
				if (start < spinTicks) {
					count++;
				}
			}
			return Math.max(1, count);
		}

		/** 마지막으로 보이는 칸. 돌지 않으면 -1. */
		public int lastFrame() {
			return frameCount() - 1;
		}

		/**
		 * 경과 틱이 드는 칸. 돌기가 끝났거나 아예 돌지 않으면 -1 이다.
		 *
		 * <p>-1 은 「이제 결과를 보여 줄 때」라는 뜻이다. 호출자는 그것만 보고 갈린다.
		 */
		public int frameAt(int elapsedTicks) {
			if (spinTicks <= 0 || elapsedTicks < 0 || elapsedTicks >= spinTicks) {
				return -1;
			}
			int last = lastFrame();
			int frame = 0;
			for (int index = 0; index <= last; index++) {
				if (elapsedTicks >= FRAME_STARTS[index]) {
					frame = index;
				}
			}
			return frame;
		}

		/**
		 * 그 칸에 보이는 후보 번호.
		 *
		 * <p>후보가 한 장이면 어느 칸이든 0 이다. 돌아도 같은 이름만 뜨지만 그것도 「이것밖에
		 * 없다」를 보여 주는 정직한 연출이라 특별 취급하지 않는다.
		 */
		public int optionAt(int frame) {
			if (optionCount <= 0) {
				return 0;
			}
			int last = Math.max(0, lastFrame());
			int offset = Math.floorMod(resultIndex - last, optionCount);
			return Math.floorMod(offset + frame, optionCount);
		}

		/** 지금 화면에 띄울 후보 번호. 멈춘 뒤에는 언제나 결과다. */
		public int shownOption(int elapsedTicks) {
			int frame = frameAt(elapsedTicks);
			return frame < 0 ? resultIndex : optionAt(frame);
		}

		/** 결과와 설명을 보여 줄 때인지. */
		public boolean revealed(int elapsedTicks) {
			return frameAt(elapsedTicks) < 0;
		}

		/** 돌기와 멈춤을 합친 전체 길이(틱). 서버가 판을 얼려 두는 길이와 같아야 한다. */
		public int totalTicks() {
			return spinTicks + holdTicks;
		}

		/** 화면이 스스로 닫혀야 하는 때인지. */
		public boolean finished(int elapsedTicks) {
			return elapsedTicks >= totalTicks();
		}

		/** 닫히기까지 남은 초. 올림이라 마지막 1틱까지 1 이 남는다. */
		public int remainingSeconds(int elapsedTicks) {
			int remaining = Math.max(0, totalTicks() - elapsedTicks);
			return (remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
		}

		/**
		 * 멈춤 구간이 얼마나 남았는지 0.0~1.0 으로. 카운트다운 막대의 채움 비율이다.
		 *
		 * <p>붙잡는 시간이 0 이면 막대를 그릴 구간 자체가 없다. 0 으로 나누지 않고 0 을 준다 —
		 * 어차피 그 화면은 그 틱에 닫힌다.
		 */
		public float holdFraction(int elapsedTicks) {
			if (holdTicks <= 0) {
				return 0.0F;
			}
			int remaining = Math.max(0, totalTicks() - elapsedTicks);
			return Math.clamp((float) remaining / holdTicks, 0.0F, 1.0F);
		}

		/** 돌기가 얼마나 진행됐는지 0.0~1.0 으로. 소리의 음을 내리는 데 쓴다. */
		public float progress(int elapsedTicks) {
			if (spinTicks <= 0) {
				return 1.0F;
			}
			return Math.clamp((float) elapsedTicks / spinTicks, 0.0F, 1.0F);
		}
	}
}
