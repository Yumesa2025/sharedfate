package com.sharedfate.client.perk;

import com.mojang.blaze3d.platform.InputConstants;
import com.sharedfate.net.DragonPrepVotePayload;
import com.sharedfate.net.PerkOfferPayload;
import com.sharedfate.net.PerkVoteC2SPayload;
import com.sharedfate.perk.DragonPrepVote;
import com.sharedfate.ui.DragonPrepStyle;
import com.sharedfate.ui.DragonPrepVoteMotion;
import com.sharedfate.ui.PerkCardDismiss;
import com.sharedfate.ui.PerkCardFocus;
import com.sharedfate.ui.PerkDrawRoll;
import com.sharedfate.ui.PerkRarityStyle;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3x2fStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「드래곤전 대비」(45·50) 증강 팀 투표 창(2026-11-04).
 *
 * <p>일반 증강의 두 화면(선택자 뽑기 {@link PerkDrawScreen} → 선택창 {@link PerkOfferScreen})을 이 한 창이
 * 대신한다. 고를 사람을 뽑지 않고, 다시 뽑기 단추도 없다. 단계는 서버가 정한다({@link DragonPrepVote}).
 *
 * <ol>
 *   <li><b>등장</b> — 화면이 어두워지고 뒷면(엔더 문양) 카드 두 장이 왼쪽부터 떠올라 뒤집힌다
 *       ({@link DragonPrepVoteMotion})</li>
 *   <li><b>투표</b> — 60초. 카드를 누르면 표가 그쪽으로 간다. 다시 누르면 옮겨진다. 카드 아래에 몇 표인지와
 *       던진 사람 이름이 보이고, 내 표가 놓인 카드는 초록 테두리다. 전원이 던지면 시계가 5초로 준다</li>
 *   <li><b>룰렛</b> — 동점(0:0 포함)이면 강조가 두 장 사이를 오가다 느려져 서버가 정한 카드에서 멈춘다</li>
 *   <li><b>결과</b> — 안 뽑힌 카드는 어두워지며 아래로 떨어지고, 뽑힌 카드는 가운데로 와서 빛난다. 5초 뒤
 *       서버가 창을 닫는다({@code PerkCloseOfferPayload})</li>
 * </ol>
 *
 * <p><b>표는 서버가 센다.</b> 누르면 「이걸 눌렀다」({@link PerkVoteC2SPayload})만 보내고, 체크는 서버가 되돌려
 * 준 상태({@link DragonPrepVotePayload})로만 그린다 — 미리 그리면 서버가 버렸을 때 화면이 거짓말을 한다.
 *
 * <p>시계는 받은 순간부터 화면이 스스로 센다. 서버는 단계가 바뀌거나 5초로 줄었을 때처럼 시계가 실제로
 * 달라질 때만 다시 보낸다.
 */
public class DragonPrepVoteScreen extends Screen {
	private static final int ACCENT = DragonPrepStyle.COLOR;
	private static final int BACKDROP = 0xD8070310;
	/** 앞면 틀 · 띠 · 문양 그림. 선택창의 「드래곤전 대비」 카드와 같은 그림이다. */
	private static final PerkRarityStyle STYLE = PerkRarityStyle.TRIAL;
	private static final int TEXT_MAIN = 0xFFF2EAFF;
	private static final int TEXT_SUB = 0xFFCFC4E2;
	private static final int TEXT_DIM = 0xFF9A90AE;
	private static final int BAND_TEXT = 0xFF1A0E2A;
	private static final int SEPARATOR = 0x66C79BFF;
	private static final int OWN_VOTE = 0xFF80FF20;
	private static final int BAR_BACK = 0x80000000;
	private static final int SHADE = 0xFF000000;

	private static final int MARGIN = 12;
	private static final int CARD_GAP = 28;
	private static final int MIN_CARD_WIDTH = 96;
	private static final int MAX_CARD_WIDTH = 150;
	/** 맨 위 띠의 높이. 띠 그림의 원래 높이(11)라 세로로 늘이지 않는다. */
	private static final int BAND_HEIGHT = PerkRarityStyle.BAND_HEIGHT;
	private static final int PADDING = 6;
	private static final int ICON_SCALE = 2;
	private static final int ICON_SIZE = 16 * ICON_SCALE;
	private static final int HOVER_LIFT = 3;
	/** 등장할 때 카드가 아래에서 올라오는 거리. */
	private static final int RISE_DISTANCE = 48;
	/** 결과에서 안 뽑힌 카드가 화면 아래끝을 넘겨 더 내려가는 거리. */
	private static final int DISMISS_OVERSHOOT = 8;
	private static final long GLOW_PULSE_MILLIS = 1400L;
	private static final long TICK_MILLIS = 50L;
	/** 서버가 닫아 주지 않을 때 ESC 로 빠져나갈 수 있게 되기까지 단계 끝에서 더 기다리는 시간. */
	private static final long ESCAPE_GRACE_MILLIS = 15_000L;
	/** 투표 막바지에 1초마다 딸깍 소리를 내는 마지막 초. */
	private static final int TICK_SOUND_SECONDS = 5;

	private final int milestone;
	private DragonPrepVotePayload state;
	private DragonPrepVote.Stage stage;
	/** 지금 단계가 서버 기준으로 시작된 순간. 늦게 열린 창도 남들과 같은 자리를 그린다. */
	private long stageStartMillis;
	private long stageEndMillis;
	private @Nullable PerkDrawRoll roll;
	private int rouletteIndex;
	private boolean[] turned = new boolean[0];
	private int lastTickSecond = -1;

	private final List<Card> cards = new ArrayList<>();
	private int cardWidth = MIN_CARD_WIDTH;
	private int cardHeight;
	private int cardTop;
	private int firstCardLeft;

	public DragonPrepVoteScreen(DragonPrepVotePayload payload) {
		super(Component.literal(DragonPrepStyle.LABEL + " 증강 — 팀 투표"));
		this.milestone = payload.milestone();
		this.state = payload;
		enterStage(payload, true);
	}

	/** 이 창이 다루는 레벨 구간. 늦게 도착한 닫기 지시를 가린다. */
	public int milestone() {
		return milestone;
	}

	/** 서버의 닫기 지시로 창을 닫는다. */
	public void closeFromServer() {
		super.onClose();
	}

	/**
	 * 서버가 보낸 새 상태를 받는다. 구간이 다른 묶음은 버린다.
	 *
	 * <p>단계가 바뀌었으면 연출 시계를 새로 세우고, 같은 단계면 끝나는 시각만 맞춘다 — 5초로 줄었을 때다.
	 */
	public void absorb(DragonPrepVotePayload payload) {
		if (payload == null || payload.milestone() != milestone) {
			return;
		}
		boolean optionsChanged = !payload.options().equals(state.options());
		DragonPrepVote.Stage previous = stage;
		state = payload;
		if (payload.stageValue() != previous) {
			enterStage(payload, false);
		} else {
			stageEndMillis = System.currentTimeMillis() + payload.stageTicks() * TICK_MILLIS;
		}
		if (optionsChanged && this.minecraft != null) {
			layout();
		}
	}

	private void enterStage(DragonPrepVotePayload payload, boolean opening) {
		long now = System.currentTimeMillis();
		stage = payload.stageValue();
		stageStartMillis = now - (long) (payload.stageTotal() - payload.stageTicks()) * TICK_MILLIS;
		stageEndMillis = now + payload.stageTicks() * TICK_MILLIS;
		roll = null;
		if (stage == DragonPrepVote.Stage.ROULETTE) {
			int ticks = Math.max(1, payload.stageTicks());
			roll = new PerkDrawRoll(ticks);
			rouletteIndex = DragonPrepVoteMotion.rouletteStart(winnerIndex(),
					DragonPrepVoteMotion.rouletteSteps(ticks), payload.options().size());
		}
		if (opening) {
			return;
		}
		switch (stage) {
			case VOTE -> play(SoundEvents.AMETHYST_BLOCK_CHIME, 1.2F, 1.0F);
			case RESULT -> play(SoundEvents.PLAYER_LEVELUP, 1.1F, 0.7F);
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------ 배치

	@Override
	protected void init() {
		layout();
	}

	private void layout() {
		List<PerkOfferPayload.PerkOption> options = state.options();
		int count = Math.max(1, options.size());
		int fitted = (this.width - MARGIN * 2 - CARD_GAP * (count - 1)) / count;
		cardWidth = Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, fitted));
		int inner = cardWidth - PADDING * 2;
		cards.clear();
		cardHeight = 0;
		for (PerkOfferPayload.PerkOption option : options) {
			Card card = Card.of(this, option, inner);
			cards.add(card);
			cardHeight = Math.max(cardHeight, card.height(this.font.lineHeight));
		}
		turned = new boolean[cards.size()];
		int total = cardWidth * count + CARD_GAP * (count - 1);
		firstCardLeft = Math.max(2, (this.width - total) / 2);
		// 위에는 제목 · 안내 · 시계, 아래에는 표 줄 세 줄이 들어간다.
		int header = 70;
		int votersReserve = this.font.lineHeight * 3 + 12;
		cardTop = Math.max(header, (this.height - cardHeight - votersReserve) / 2 + 12);
	}

	private int cardLeft(int index) {
		return firstCardLeft + index * (cardWidth + CARD_GAP);
	}

	private int centerLeft() {
		return (this.width - cardWidth) / 2;
	}

	// ------------------------------------------------------------------ 시간

	private long stageElapsedMillis() {
		return System.currentTimeMillis() - stageStartMillis;
	}

	private long remainingMillis() {
		return Math.max(0L, stageEndMillis - System.currentTimeMillis());
	}

	/** 남은 초. 올림이라 5초 남았을 때 5가 뜨고 마지막 1틱까지 1이 남는다. */
	private int remainingSeconds() {
		return (int) ((remainingMillis() + 999L) / 1000L);
	}

	private float remainingFraction() {
		long total = state.stageTotal() * TICK_MILLIS;
		return total <= 0L ? 0.0F : Math.min(1.0F, (float) remainingMillis() / total);
	}

	@Override
	public void tick() {
		if (stage == DragonPrepVote.Stage.REVEAL) {
			long elapsed = stageElapsedMillis();
			for (int index = 0; index < turned.length; index++) {
				if (!turned[index] && elapsed >= DragonPrepVoteMotion.turnMillis(index)) {
					turned[index] = true;
					play(SoundEvents.END_PORTAL_FRAME_FILL, 1.0F + index * 0.15F, 0.8F);
				}
			}
		}
		if (stage == DragonPrepVote.Stage.VOTE) {
			int seconds = remainingSeconds();
			if (seconds != lastTickSecond && seconds > 0 && seconds <= TICK_SOUND_SECONDS) {
				play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.4F, 0.6F);
			}
			lastTickSecond = seconds;
		}
		if (stage == DragonPrepVote.Stage.ROULETTE && roll != null && !cards.isEmpty()) {
			switch (roll.tick()) {
				case NEXT_NAME -> {
					rouletteIndex = (rouletteIndex + 1) % cards.size();
					play(SoundEvents.NOTE_BLOCK_HAT.value(), 1.0F + roll.progress() * 0.6F, 0.6F);
				}
				case REVEAL -> {
					rouletteIndex = winnerIndex();
					play(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F, 0.9F);
				}
				case NOTHING -> {
				}
			}
		}
	}

	private int winnerIndex() {
		List<PerkOfferPayload.PerkOption> options = state.options();
		for (int index = 0; index < options.size(); index++) {
			if (options.get(index).id().equals(state.winner())) {
				return index;
			}
		}
		return 0;
	}

	// ------------------------------------------------------------------ 그리기

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick) {
		// 어둠. 등장 연출 첫 0.4초 동안 짙어진다.
		float dark = stage == DragonPrepVote.Stage.REVEAL
				? Math.clamp(stageElapsedMillis() / 400.0F, 0.0F, 1.0F) : 1.0F;
		graphics.fill(0, 0, this.width, this.height,
				withAlpha(BACKDROP, Math.round((BACKDROP >>> 24) * dark)));

		int centerX = this.width / 2;
		graphics.centeredText(this.font, Component.literal(this.title.getString())
				.withStyle(ChatFormatting.BOLD), centerX, 10, ACCENT);
		graphics.centeredText(this.font, subtitle(), centerX, 24, TEXT_SUB);
		renderClock(graphics, centerX);

		for (int index = 0; index < cards.size(); index++) {
			renderCard(graphics, index, mouseX, mouseY);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private Component subtitle() {
		return switch (stage) {
			case REVEAL -> Component.literal("두 장 중 하나를 팀이 함께 고릅니다");
			case VOTE -> state.shortened()
					? Component.literal("모두 골랐습니다 — 표가 많은 카드로 정해집니다")
					: votable()
							? Component.literal("카드를 눌러 표를 던지십시오. 정해지기 전까지 바꿀 수 있습니다")
							: Component.literal("관전 중이라 표를 던질 수 없습니다");
			case ROULETTE -> Component.literal("표가 같습니다 — 룰렛으로 정합니다");
			case RESULT -> Component.literal(state.byRoulette()
					? "룰렛으로 정해졌습니다" : "팀 투표로 정해졌습니다");
		};
	}

	/** 투표 중에는 남은 시간, 결과 중에는 다시 시작까지 남은 시간. 숫자와 막대로 그린다. */
	private void renderClock(GuiGraphicsExtractor graphics, int centerX) {
		String text;
		int color;
		if (stage == DragonPrepVote.Stage.VOTE) {
			text = String.valueOf(remainingSeconds());
			color = state.shortened() || remainingSeconds() <= TICK_SOUND_SECONDS ? 0xFFFFD24A : ACCENT;
		} else if (stage == DragonPrepVote.Stage.RESULT) {
			text = remainingSeconds() + "초 뒤 다시 시작합니다";
			color = TEXT_SUB;
		} else {
			return;
		}
		if (stage == DragonPrepVote.Stage.VOTE) {
			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(centerX, 38);
			pose.scale(2.0F, 2.0F);
			graphics.centeredText(this.font, Component.literal(text), 0, 0, color);
			pose.popMatrix();
		} else {
			graphics.centeredText(this.font, Component.literal(text), centerX, 40, color);
		}
		int barWidth = Math.min(200, this.width - MARGIN * 2);
		int barLeft = centerX - barWidth / 2;
		int barTop = 58;
		graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + 3, BAR_BACK);
		graphics.fill(barLeft, barTop, barLeft + Math.round(barWidth * remainingFraction()), barTop + 3,
				color);
	}

	private void renderCard(GuiGraphicsExtractor graphics, int index, int mouseX, int mouseY) {
		Card card = cards.get(index);
		String id = index < state.options().size() ? state.options().get(index).id() : "";
		int left = cardLeft(index);
		int top = cardTop;
		float shade = 0.0F;
		float scaleX = 1.0F;
		boolean front = true;
		boolean highlighted = false;
		boolean winner = id.equals(state.winner());

		switch (stage) {
			case REVEAL -> {
				long elapsed = stageElapsedMillis();
				top += DragonPrepVoteMotion.riseOffset(elapsed, index, RISE_DISTANCE);
				shade = 1.0F - DragonPrepVoteMotion.rise(elapsed, index);
				scaleX = DragonPrepVoteMotion.flipScale(elapsed, index);
				front = DragonPrepVoteMotion.frontVisible(elapsed, index);
			}
			case VOTE -> {
				if (votable() && isInside(mouseX, mouseY, left, cardTop)) {
					top -= HOVER_LIFT;
					highlighted = true;
				}
			}
			case ROULETTE -> highlighted = index == rouletteIndex;
			case RESULT -> {
				long elapsed = stageElapsedMillis();
				if (winner) {
					left = PerkCardFocus.left(elapsed, left, centerLeft());
					highlighted = true;
				} else {
					if (PerkCardDismiss.gone(elapsed, 0)) {
						return;
					}
					int travel = Math.max(0, this.height - cardTop) + DISMISS_OVERSHOOT;
					top += PerkCardDismiss.offset(elapsed, 0, travel);
					shade = PerkCardDismiss.shade(elapsed, 0);
					if (top >= this.height) {
						return;
					}
				}
			}
		}

		boolean own = id.equals(state.ownVote()) && stage != DragonPrepVote.Stage.RESULT;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		if (scaleX < 1.0F) {
			// 카드 한가운데를 축으로 가로만 접는다.
			float axis = left + cardWidth / 2.0F;
			pose.translate(axis, 0.0F);
			pose.scale(scaleX, 1.0F);
			pose.translate(-axis, 0.0F);
		}
		if (highlighted && (stage == DragonPrepVote.Stage.ROULETTE || stage == DragonPrepVote.Stage.RESULT)) {
			renderGlow(graphics, left, top);
		}
		if (front) {
			renderFront(graphics, card, left, top, highlighted, own);
		} else {
			renderBack(graphics, left, top);
		}
		if (shade > 0.0F) {
			PerkCardSprites.shade(graphics, left, top, cardWidth, cardHeight,
					withAlpha(SHADE, Math.round(0xFF * Math.clamp(shade, 0.0F, 1.0F))));
		}
		pose.popMatrix();

		if (stage != DragonPrepVote.Stage.REVEAL && (stage != DragonPrepVote.Stage.RESULT || winner)) {
			renderVoters(graphics, id, left, top + cardHeight + 6, own);
		}
	}

	private void renderFront(GuiGraphicsExtractor graphics, Card card, int left, int top,
			boolean bright, boolean own) {
		int right = left + cardWidth;
		// 틀 · 띠 · 문양 그림. 호버 · 룰렛 · 결과 · 내 표 카드는 원래 밝기로, 나머지는 조금 눌러 그린다.
		PerkCardSprites.frame(graphics, STYLE, left, top, cardWidth, cardHeight, bright || own);
		if (own) {
			// 내 표는 초록 두 겹 — 틀 그림 바깥 검은 선 위에 한 겹, 그 바깥에 한 겹.
			graphics.outline(left, top, cardWidth, cardHeight, OWN_VOTE);
			PerkCardSprites.outline(graphics, left, top, cardWidth, cardHeight, OWN_VOTE);
		} else if (bright) {
			PerkCardSprites.outline(graphics, left, top, cardWidth, cardHeight,
					mix(ACCENT, 0xFFFFFFFF, 0.45F));
		}
		int centerX = left + cardWidth / 2;
		Component label = Component.literal(DragonPrepStyle.LABEL);
		graphics.centeredText(this.font, label,
				PerkCardSprites.bandTextCenter(left, cardWidth, this.font.width(label)),
				top + (BAND_HEIGHT - this.font.lineHeight + 1) / 2, BAND_TEXT);
		int y = top + BAND_HEIGHT + 6;
		renderIcon(graphics, card.icon(), centerX - ICON_SIZE / 2, y);
		y += ICON_SIZE + 4;
		for (FormattedCharSequence line : card.nameLines()) {
			graphics.centeredText(this.font, line, centerX, y, TEXT_MAIN);
			y += this.font.lineHeight;
		}
		y += 3;
		graphics.fill(left + PADDING, y, right - PADDING, y + 1, SEPARATOR);
		y += 5;
		for (FormattedCharSequence line : card.descriptionLines()) {
			graphics.text(this.font, line, left + PADDING, y, TEXT_SUB);
			y += this.font.lineHeight;
		}
	}

	/** 뒷면. 틀 그림 한가운데에 엔더 문양 하나만 있다 — 무엇이 나올지 모르는 카드다. */
	private void renderBack(GuiGraphicsExtractor graphics, int left, int top) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PerkRarityStyle.CARD_BACK, left, top,
				cardWidth, cardHeight);
		int emblem = PerkRarityStyle.BACK_EMBLEM_SIZE;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PerkRarityStyle.CARD_BACK_EMBLEM,
				left + (cardWidth - emblem) / 2, top + (cardHeight - emblem) / 2, emblem, emblem);
	}

	/** 룰렛이 가리킨 카드와 뽑힌 카드 둘레의 빛. 빛 그림을 연보라로 물들여 깔고 천천히 숨 쉰다. */
	private void renderGlow(GuiGraphicsExtractor graphics, int left, int top) {
		float phase = (System.currentTimeMillis() % GLOW_PULSE_MILLIS) / (float) GLOW_PULSE_MILLIS;
		float strength = 0.55F + 0.35F * (0.5F - 0.5F * (float) Math.cos(phase * 2.0 * Math.PI));
		PerkCardSprites.glow(graphics, left, top, cardWidth, cardHeight, ACCENT, strength);
	}

	/** 카드 아래 표 줄 — 「N표」와 던진 사람 이름. 내 표가 있으면 초록이다. */
	private void renderVoters(GuiGraphicsExtractor graphics, String id, int left, int top, boolean own) {
		List<String> voters = state.votersOf(id);
		int centerX = left + cardWidth / 2;
		String count = voters.size() + "표" + (own ? " · 내 표" : "");
		graphics.centeredText(this.font, Component.literal(count), centerX, top,
				own ? OWN_VOTE : voters.isEmpty() ? TEXT_DIM : ACCENT);
		if (voters.isEmpty()) {
			return;
		}
		int y = top + this.font.lineHeight + 2;
		for (FormattedCharSequence line : this.font.split(
				Component.literal(String.join(", ", voters)), cardWidth + CARD_GAP - 4)) {
			graphics.centeredText(this.font, line, centerX, y, TEXT_SUB);
			y += this.font.lineHeight;
		}
	}

	private void renderIcon(GuiGraphicsExtractor graphics, ItemStack stack, int x, int y) {
		if (stack.isEmpty()) {
			return;
		}
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x, y);
		pose.scale(ICON_SCALE, ICON_SCALE);
		graphics.item(stack, 0, 0);
		pose.popMatrix();
	}

	// ------------------------------------------------------------------ 입력

	/** 지금 표를 던질 수 있는가. 투표 단계이고, 서버가 허락했고, 관전 중이 아니다. */
	private boolean votable() {
		return stage == DragonPrepVote.Stage.VOTE && state.canVote()
				&& !(this.minecraft != null && this.minecraft.player != null
						&& this.minecraft.player.isSpectator());
	}

	private boolean isInside(double x, double y, int left, int top) {
		return x >= left && x < left + cardWidth && y >= top && y < top + cardHeight;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// 왼쪽 단추 번호는 InputConstants 로만 쓴다 — 26.3 에서 번호가 바뀌었다(PerkOfferScreen.isSelectClick).
		if (votable() && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			for (int index = 0; index < cards.size() && index < state.options().size(); index++) {
				if (isInside(event.x(), event.y(), cardLeft(index), cardTop)) {
					String id = state.options().get(index).id();
					if (!id.equals(state.ownVote())
							&& ClientPlayNetworking.canSend(PerkVoteC2SPayload.TYPE)) {
						ClientPlayNetworking.send(new PerkVoteC2SPayload(milestone, id));
						play(SoundEvents.UI_TOAST_IN, 1.3F, 0.6F);
					}
					return true;
				}
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	/**
	 * ESC 로 닫을 수 없다. 다만 단계가 끝나고도 한참 서버가 닫아 주지 않으면 열어 준다 — 갇히는 것보다 낫다.
	 */
	@Override
	public boolean shouldCloseOnEsc() {
		return System.currentTimeMillis() > stageEndMillis + ESCAPE_GRACE_MILLIS;
	}

	/** 시간은 서버가 멈춰 두었다. 클라이언트까지 일시정지 화면으로 만들 이유가 없다. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ------------------------------------------------------------------ 보조

	private void play(SoundEvent sound, float pitch, float volume) {
		if (this.minecraft == null) {
			return;
		}
		this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
	}

	private static int withAlpha(int color, int alpha) {
		return (alpha & 0xFF) << 24 | color & 0x00FFFFFF;
	}

	private static int mix(int base, int tint, float amount) {
		float ratio = Math.clamp(amount, 0.0F, 1.0F);
		int red = Math.round((base >> 16 & 0xFF) * (1.0F - ratio) + (tint >> 16 & 0xFF) * ratio);
		int green = Math.round((base >> 8 & 0xFF) * (1.0F - ratio) + (tint >> 8 & 0xFF) * ratio);
		int blue = Math.round((base & 0xFF) * (1.0F - ratio) + (tint & 0xFF) * ratio);
		return (base & 0xFF000000) | red << 16 | green << 8 | blue;
	}

	/** 카드에 그릴 아이콘. 못 찾으면 엔더의 눈 — 「드래곤전 대비」 기본 아이콘과 같다. */
	private static ItemStack iconStack(String iconId) {
		Item item = null;
		if (iconId != null && !iconId.isBlank()) {
			try {
				Identifier id = Identifier.tryParse(iconId.trim());
				Optional<Holder.Reference<Item>> found = id == null
						? Optional.empty() : BuiltInRegistries.ITEM.get(id);
				if (found.isPresent() && found.get().value() != Items.AIR) {
					item = found.get().value();
				}
			} catch (Exception error) {
				item = null;
			}
		}
		return new ItemStack(item == null ? Items.ENDER_EYE : item);
	}

	/** 화면 폭이 정해진 뒤 한 번 계산해 두는 카드 한 장의 내용. */
	private record Card(List<FormattedCharSequence> nameLines, ItemStack icon,
			List<FormattedCharSequence> descriptionLines) {

		static Card of(DragonPrepVoteScreen screen, PerkOfferPayload.PerkOption option, int inner) {
			return new Card(
					screen.font.split(Component.literal(option.name()).withStyle(ChatFormatting.BOLD), inner),
					iconStack(option.icon()),
					screen.font.split(Component.literal(option.description()), inner));
		}

		/** {@code renderFront} 가 그리는 것을 하나도 빠짐없이 센다 — 덜 세면 설명 끝줄이 카드 밖으로 샌다. */
		int height(int lineHeight) {
			return BAND_HEIGHT + 6 + ICON_SIZE + 4 + nameLines.size() * lineHeight + 3 + 1 + 5
					+ descriptionLines.size() * lineHeight + PADDING;
		}
	}
}
