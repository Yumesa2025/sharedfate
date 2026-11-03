package com.sharedfate.client.mixin;

import com.sharedfate.client.ClientStatRows;
import com.sharedfate.client.ClientTeamState;
import com.sharedfate.client.team.TeamScreen;
import com.sharedfate.ui.InventoryStatPanel;
import com.sharedfate.ui.InventoryTeamButton;
import com.sharedfate.ui.PanelRow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 인벤토리 화면(E) 왼쪽에 SharedFate 화면을 여는 단추와 <b>능력치</b>를 붙인다.
 *
 * <p>단추 이름과 자리, 자리가 모자랄 때 어떻게 물러나는지는 {@link InventoryTeamButton} 과
 * {@link InventoryStatPanel} 에 있다.
 *
 * <h2>팀이 없으면 단추가 「팀 생성」 하나가 된다</h2>
 * <p>팀이 없을 때 위의 둘은 빈 화면을 연다 — 「SharedFate」는 「팀에 속해 있지 않습니다」 한
 * 줄이고, 「현재 증강」은 빈 목록이다. 그래서 그 자리에 <b>지금 할 수 있는 단 하나</b>인
 * 「팀 생성」을 놓고, 그것은 팀 화면을 <b>「팀」 탭이 펴진 채로</b> 연다. 감추지 않고 바꾸는
 * 것이 요점이다 — 아예 없애면 팀을 만들러 가는 길이 끊긴다. 능력치는 어느 쪽에서도 그린다.
 *
 * <p>팀이 있는지는 {@link ClientTeamState#inTeam()} 이 안다. 서버가 팀이 사라질 때
 * {@code TeamBroadcaster.sendEmpty} 로 빈 것을 보내 주므로 <b>여기서 따로 물을 것이 없고, 물어서도
 * 안 된다</b> — 화면 하나 때문에 통신 규약을 늘릴 이유가 없다.
 *
 * <p>단추 셋은 {@code init} 에서 <b>한꺼번에 만들어 두고</b> 자리를 잡을 때마다 보일 것만
 * 보인다. 갈아 끼울 때 만들고 버리면 매 프레임 위젯을 새로 만들게 되고, 팀이 생기거나 사라지는
 * 순간을 놓치면 「팀 생성」이 남아 있는 팀 화면을 열거나 그 반대가 된다. 감출 때는 폭을 0 으로
 * 줄이는 것이 아니라 {@code visible} 을 내린다 — 26.2 의 {@code AbstractWidget.isActive()} 는
 * {@code visible && active} 라, 폭만 0 인 단추는 <b>안 보이는데 눌린다.</b>
 *
 * <h2>왜 매 프레임 자리를 다시 잡는가</h2>
 * <p>조합법 책을 펼치면 바닐라는 창을 오른쪽으로 밀면서 <b>조합법 책 단추만</b> 새 자리로
 * 옮긴다({@code AbstractRecipeBookScreen} 의 단추 콜백). 화면을 다시 만들지 않으므로 여기서
 * {@code init} 때 잡아 둔 자리는 그대로 남아 펼쳐진 책에 깔린다. 그림을 뽑기 직전에 다시
 * 재면 한 프레임도 어긋나지 않는다. 능력치 값도 같은 자리에서 다시 읽으므로, 증강을 고르거나
 * 조건부 효과가 켜지면 창을 닫지 않아도 숫자가 따라온다.
 *
 * <p>{@code TAIL} 로 넣는 이유는 {@code InventoryScreen.init} 에 <b>return 이 둘</b>이기
 * 때문이다. 앞의 것은 크리에이티브 모드라 화면을 크리에이티브 인벤토리로 갈아 끼우고 곧바로
 * 빠져나가는 길이고, 거기에 단추를 얹으면 곧 버려지는 화면에 붙는다.
 */
@Mixin(InventoryScreen.class)
public abstract class InventoryScreenTeamButtonMixin {
	/**
	 * 파고들 메서드를 <b>서술자까지</b> 적는다.
	 *
	 * <p>{@code InventoryScreen} 에는 이름이 같은 메서드가 <b>둘</b> 있다 — 화면을 그리는
	 * 이것과, 인벤토리 속 플레이어 인형을 그리려고 쓰는
	 * {@code private static EntityRenderState extractRenderState(LivingEntity)} 다.
	 * 이름만 적으면 믹스인이 둘 다 고르고, 서명이 맞지 않는 쪽에서 화면을 여는 순간
	 * 터진다. {@code ScreenStatSourceTest} 가 이 둘이 그대로 있는지 붙들어 둔다.
	 */
	@Unique
	private static final String EXTRACT_RENDER_STATE =
			"extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V";

	@Unique
	private Button sharedfate$teamButton;

	/**
	 * 「SharedFate」 옆에 나란히 서는 두 번째 단추. 누르면 <b>증강 탭이 펴진 채로</b> 열린다.
	 *
	 * <p>자리가 모자란 화면에서는 만들지 않는다 — 억지로 끼우면 둘 다 글자가 잘려 무엇을
	 * 누르는지 알 수 없다.
	 */
	@Unique
	private Button sharedfate$perkButton;

	/**
	 * 팀이 없을 때 위의 둘 대신 혼자 서는 단추. 누르면 <b>팀 탭이 펴진 채로</b> 열린다.
	 *
	 * <p>위의 둘과 <b>동시에 보이는 일이 없다.</b> 판정은 {@link #sharedfate$layOut()} 한 곳에서만
	 * 한다 — 두 곳에서 하면 한쪽만 고쳐져 셋이 겹쳐 서는 프레임이 생긴다.
	 */
	@Unique
	private Button sharedfate$createButton;

	/** 이번 프레임에 그릴 능력치 줄들. {@code extractRenderState} 머리에서 다시 잰다. */
	@Unique
	private InventoryStatPanel.Layout sharedfate$stats;
	@Unique
	private int sharedfate$statLeft;
	@Unique
	private int sharedfate$statTop;

	@Inject(method = "init", at = @At("TAIL"))
	private void sharedfate$addTeamButton(CallbackInfo ci) {
		sharedfate$teamButton = Button.builder(
						Component.literal(InventoryTeamButton.LABEL),
						button -> sharedfate$openTeamScreen())
				.bounds(0, 0, InventoryTeamButton.MIN_WIDTH, InventoryTeamButton.HEIGHT)
				.tooltip(Tooltip.create(Component.literal(InventoryTeamButton.TOOLTIP)))
				.build();
		sharedfate$perkButton = Button.builder(
						Component.literal(InventoryTeamButton.PERK_LABEL),
						button -> sharedfate$openPerkList())
				.bounds(0, 0, InventoryTeamButton.MIN_WIDTH, InventoryTeamButton.HEIGHT)
				.tooltip(Tooltip.create(Component.literal(InventoryTeamButton.PERK_TOOLTIP)))
				.build();
		sharedfate$createButton = Button.builder(
						Component.literal(InventoryTeamButton.CREATE_LABEL),
						button -> sharedfate$openTeamTab())
				.bounds(0, 0, InventoryTeamButton.MIN_WIDTH, InventoryTeamButton.HEIGHT)
				.tooltip(Tooltip.create(Component.literal(InventoryTeamButton.CREATE_TOOLTIP)))
				.build();
		sharedfate$layOut();
		((ScreenAccessor) this).sharedfate$addRenderableWidget(sharedfate$teamButton);
		((ScreenAccessor) this).sharedfate$addRenderableWidget(sharedfate$perkButton);
		((ScreenAccessor) this).sharedfate$addRenderableWidget(sharedfate$createButton);
	}

	@Inject(method = EXTRACT_RENDER_STATE, at = @At("HEAD"))
	private void sharedfate$followWindow(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		sharedfate$layOut();
	}

	/**
	 * 능력치를 그린다. 창과 칸이 모두 그려진 <b>뒤</b>다.
	 *
	 * <p>줄들은 창 바깥 왼쪽에만 있어 무엇과도 겹치지 않지만, 머리에서 그리면 이 모드가 창
	 * 아래에 덧그리는 추가 27칸 판보다 먼저 나와 겹칠 여지가 생긴다. 꼬리에서 그리면 그런
	 * 순서 문제가 아예 없다.
	 */
	@Inject(method = EXTRACT_RENDER_STATE, at = @At("TAIL"))
	private void sharedfate$drawStats(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
			float partialTick, CallbackInfo ci) {
		if (sharedfate$stats == null || !sharedfate$stats.visible()) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		for (InventoryStatPanel.Line line : sharedfate$stats.lines()) {
			graphics.text(font, line.text(),
					sharedfate$statLeft, sharedfate$statTop + line.y(), line.color());
		}
	}

	/**
	 * 단추 폭·자리와 능력치 줄을 다시 잰다.
	 *
	 * <p>단추와 줄들을 <b>한 덩어리</b>로 보고 그 오른쪽 끝을 창(또는 펼친 조합법 책)에
	 * 붙인다. 덩어리 폭은 둘 중 넓은 쪽이라, 줄이 단추보다 길어도 창을 덮지 않는다.
	 *
	 * <p>팀이 있느냐 없느냐도 <b>여기서</b> 판정한다. 매 프레임 다시 묻는 것이 맞다 —
	 * {@link ClientTeamState} 는 서버가 보낸 값을 그대로 든 정적 필드라 읽는 데 드는 것이 없고,
	 * 팀이 생기거나 사라지는 순간은 인벤토리가 열려 있는 채로도 온다(초대를 받거나 리더가
	 * 해체한다). 화면을 다시 만들 계기가 없으므로 여기서 보지 않으면 창을 닫을 때까지 옛 단추가
	 * 남는다.
	 */
	@Unique
	private void sharedfate$layOut() {
		if (sharedfate$teamButton == null) {
			return;
		}
		Screen self = (Screen) (Object) this;
		AbstractContainerScreenAccessor window = (AbstractContainerScreenAccessor) this;
		Font font = Minecraft.getInstance().font;

		int available = InventoryTeamButton.available(
				self.width, window.sharedfate$getImageWidth(), window.sharedfate$getLeftPos());
		boolean inTeam = ClientTeamState.inTeam();

		// 맨 앞에 서는 단추의 글자는 팀이 있느냐에 따라 다르고, 폭은 그 글자를 재서 잡는다.
		String leadLabel = inTeam ? InventoryTeamButton.LABEL : InventoryTeamButton.CREATE_LABEL;
		int leadWidth = InventoryTeamButton.buttonWidth(font.width(leadLabel), available);
		// 팀이 없으면 뒤에 붙을 것이 없다. 0 을 넘기면 buttonRowWidth 가 맨 앞 폭을 그대로 돌려준다.
		int perkWidth = inTeam
				? InventoryTeamButton.perkButtonWidth(
						font.width(InventoryTeamButton.PERK_LABEL), available, leadWidth)
				: 0;
		int rowWidth = InventoryTeamButton.buttonRowWidth(leadWidth, perkWidth);

		// 최종값이 아니라 증강·세트가 얹은 보정치다. 무기를 바꿔도 숫자가 출렁이지 않는다.
		List<List<PanelRow>> groups = ClientStatRows.bonusGroups();
		sharedfate$stats = InventoryStatPanel.layout(groups, available,
				InventoryTeamButton.statHeight(self.height, window.sharedfate$getTopPos()),
				font::width);

		int blockWidth = Math.max(rowWidth, sharedfate$stats.width());
		int left = InventoryTeamButton.blockLeft(available, blockWidth);
		int buttonY = InventoryTeamButton.y(window.sharedfate$getTopPos());

		// 감출 때 폭을 0 으로 줄이지 않는다. 0폭 단추는 안 보이지만 눌리는 자리가 남는다.
		sharedfate$teamButton.visible = inTeam;
		sharedfate$createButton.visible = !inTeam;
		Button lead = inTeam ? sharedfate$teamButton : sharedfate$createButton;
		lead.setWidth(leadWidth);
		lead.setPosition(left, buttonY);

		// 자리가 모자라면 폭이 0으로 돌아온다. 팀이 없을 때도 같은 길로 내려와 감춰진다.
		sharedfate$perkButton.visible = perkWidth > 0;
		if (perkWidth > 0) {
			sharedfate$perkButton.setWidth(perkWidth);
			sharedfate$perkButton.setPosition(
					left + leadWidth + InventoryTeamButton.BUTTON_GAP, buttonY);
		}

		sharedfate$statLeft = left;
		sharedfate$statTop = InventoryTeamButton.statTop(window.sharedfate$getTopPos());
	}

	/**
	 * 팀 화면을 연다. {@code /shareteam} 을 친 것과 같은 창이다.
	 *
	 * <p>이 화면은 서버에 묻는 것 없이 이미 받아 둔 값만으로 그려지므로({@code TeamScreen})
	 * 곧바로 열어도 같은 내용이다.
	 */
	@Unique
	private void sharedfate$openTeamScreen() {
		Minecraft.getInstance().setScreenAndShow(new TeamScreen());
	}

	/**
	 * 증강 목록을 <b>한 번에</b> 연다.
	 *
	 * <p>같은 화면이지만 증강 탭이 펴진 채로 뜬다. 「SharedFate → 증강」 두 번을 누르지
	 * 않으려고 둔 단추다.
	 */
	@Unique
	private void sharedfate$openPerkList() {
		Minecraft.getInstance().setScreenAndShow(TeamScreen.onPerks());
	}

	/**
	 * 팀 만들기 양식을 <b>한 번에</b> 연다.
	 *
	 * <p>{@link #sharedfate$openPerkList()} 와 같은 길이다 — 탭을 미리 정해 둔 화면을 만들어
	 * 넘긴다. 기본 탭으로 열면 「팀에 속해 있지 않습니다」를 읽고 「팀」을 한 번 더 눌러야 한다.
	 */
	@Unique
	private void sharedfate$openTeamTab() {
		Minecraft.getInstance().setScreenAndShow(TeamScreen.onTeam());
	}
}
