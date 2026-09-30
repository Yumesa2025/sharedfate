package com.sharedfate.client.mixin;

import com.sharedfate.client.RecipeBookGate;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 조합법 책을 <b>화면이 좁을 때만</b> 막는다. 제작대·화로·훈연기·용광로·플레이어 인벤토리가
 * 모두 이 클래스를 물려받으므로 한 곳만 고치면 된다.
 *
 * <h2>왜 한때 통째로 없앴는가 — 지우지 말 것</h2>
 * <p>바닐라 {@code AbstractRecipeBookScreen.extractRenderState} 는 책이 펴져 있고 화면이
 * 좁으면({@code widthTooNarrow}, 화면 폭 379 미만) <b>칸과 아이템을 아예 그리지 않는다</b> —
 * {@code extractContents} 대신 배경 하나만 그리는 {@code extractBackground} 로 갈아탄다.
 * 이 모드가 추가하는 27칸도 {@code extractContents} 안(={@code extractSlots})에서만 그려지므로
 * 그 순간 함께 사라진다. 책을 펴는 순간 창 전체가 빈 배경판만 남는 것으로 보이는 것이 이
 * 증상이다. 책을 아예 못 켜지게 하면 이 갈림길 자체가 없어진다.
 *
 * <p>단추 자리를 창에 맞춰 따라오게 손보는 미봉책도 가능했지만, 그래도 위 분기 자체는 남는다
 * — 화면이 379px 아래로 좁아지는 상황(GUI 배율을 올린 작은 창)은 이 모드와 무관하게 언제든
 * 올 수 있다. 책을 없애는 것이 이 모드가 요구받은 것이기도 하고, 근본적으로도 더 안전하다.
 *
 * <h2>조합은 그대로 된다</h2>
 * <p>여기서 건드리는 것은 {@code RecipeBookComponent} 하나뿐이다. 제작대·인벤토리의 조합칸
 * 자체는 {@code CraftingMenu}/{@code InventoryMenu} 가 다루고 이 믹스인은 그 근처를 지나지도
 * 않는다. 2×2·3×3 조합은 평소처럼 마우스로 재료를 놓아 만든다.
 *
 * <h2>단추만 지우면 안 되는 이유 — {@code tick()} 이 매 틱 되살린다</h2>
 * <p>{@code RecipeBookComponent.tick()} 은 매 틱 {@code isVisibleAccordingToBookData()}(=
 * 지난번에 열어 뒀는지가 적힌 저장값)와 지금 상태가 다르면 <b>그 저장값으로 되돌린다</b>.
 * 그래서 단추만 없애 클릭을 막아도, 예전에 책을 펴 둔 채로 저장된 적이 있다면 화면을 열자마자
 * 한 틱 만에 다시 펼쳐진다. {@link #sharedfate$closeWhenTooNarrow} 가 화면을 세우는 자리에서
 * 한 번 확실히 닫아 두는 이유가 이것이다 — {@code toggleVisibility()}(={@code setVisible(false)})는
 * 저장값도 함께 닫힌 것으로 고쳐 쓰므로, 다음 틀에서 재본 저장값도 이미 닫힌 채라 되살아나지
 * 않는다.
 *
 * <h2>그래서 지금은 이렇게 되살렸다</h2>
 * <p>「조합법이 없으면 초보자가 너무 불편하다」는 말을 듣고, <b>위 분기로 들어갈 수 없는
 * 화면에서만</b> 막기로 했다. 폭이 넉넉하면 {@code widthTooNarrow} 가 서지 않으니 바닐라는
 * 늘 {@code extractContents} 로 가고, 빈 배경판이 되는 길 자체가 없다. 없앤 이유가 사라진
 * 것이 아니라 <b>이유가 성립하는 범위</b>를 좁힌 것이다.
 *
 * <p>위에서 「미봉책」이라 적은 단추 자리 문제는 이제 실제로 고쳐야 한다. 단추를 되살리면
 * 그 자리가 이 모드가 늘린 창을 따라오지 않아 추가 칸 위에 앉기 때문이다. 그것은
 * {@link RecipeBookButtonPositionMixin} 이 맡았고, 왜 그만큼 올려야 하는지는
 * {@link RecipeBookGate#buttonLift} 에 셈과 함께 있다.
 *
 * <p>되살리면서 확인한 것 — <b>추가 27칸은 창이 밀려도 함께 밀린다.</b> 책이 펴지면 바닐라는
 * {@code leftPos} 를 오른쪽으로 77px 옮기는데, 27칸과 그 배경판은
 * {@code AbstractContainerScreen.extractContents} 가 {@code (leftPos, topPos)} 만큼 밀어 둔
 * 좌표계 안에서 그려지므로({@code ContainerScreenMixin.sharedfate$drawExtraPanel} 이
 * {@code extractSlots} 에 붙어 있다) 따로 해 줄 것이 없다. 눌리는 자리도 같은 값으로 재는
 * {@code getHoveredSlot} 이 본다.
 *
 * <h2>좁을 때는 단추 대신 <b>이유</b>를 남긴다</h2>
 * <p>폭이 모자라면 예전처럼 {@code initButton} 을 통째로 취소한다 — 진짜 단추가 서면 그것을
 * 누르는 순간 빈 배경판이 된다. 다만 자리를 비워 두지는 않는다. 같은 자리에 <b>누를 수 없는
 * 같은 그림</b>을 놓고 {@link RecipeBookGate#NARROW_NOTICE} 를 올려놓기 설명으로 단다. 그냥
 * 없애 두면 초보자는 이 모드에 조합법이 아예 없는 줄 알고, 무엇을 고쳐야 나오는지도 알 수
 * 없다. {@code active} 를 내려 두면 바닐라가 마우스 커서까지
 * {@code CursorTypes.NOT_ALLOWED} 로 바꿔 주므로 눌러 보기 전에 죽은 단추임을 알 수 있다
 * ({@code AbstractButton.extractWidgetRenderState} → {@code AbstractWidget.handleCursor}).
 *
 * <h2>되살려도 추가 27칸은 조합법 창이 세지 않았다 — 지금은 센다</h2>
 * <p>「만들 수 있는 것」 판정과 자동 채우기는 {@code RecipeBookComponent.initVisuals} /
 * {@code updateStackedContents} 가 {@code Inventory.fillStackedContents} 로 <b>바닐라
 * 인벤토리만</b> 훑어서 낸다. 이 모드의 추가 27칸은 별도 {@code Container} 라 그 셈에
 * 들어가지 않았다 — 재료를 추가 칸에만 넣어 두면 조합법이 회색으로 남고 눌러도 채워지지
 * 않았다. 없애 두었을 때는 드러나지 않던 것이고 고치려면 메뉴·서버 쪽까지 건드려야 하므로
 * 되살릴 때는 손대지 않고 적어만 두었다.
 *
 * <p>이 화면이 여섯 줄이라 초보자의 재료가 주로 아래 줄에 쌓인다는 것을 생각하면, 조합법을
 * 되살린 이유(「초보자가 불편하다」)가 회색 앞에서 그대로 무너진다. 그래서 고쳤다.
 * <b>이 클래스는 한 줄도 바뀌지 않았다</b> — 고칠 자리가 여기가 아니었기 때문이다.
 *
 * <ul>
 *   <li><b>회색인지 아닌지</b>(클라이언트) — {@code InventoryMixin.sharedfate$fillExtraStackedContents}.
 *       그 줄은 전부터 있었지만 {@code player instanceof ServerPlayer} 로 묶여 있어
 *       <b>서버에서만</b> 돌았다. 판정하는 쪽은 {@code LocalPlayer} 이므로 한 번도 닿지
 *       않았다. {@code active()} 로 바꿔 양쪽에서 돈다.</li>
 *   <li><b>눌러서 자동 채우기</b>(서버) —
 *       {@code com.sharedfate.mixin.ExpandedPlaceRecipeMixin}. 셈은 이미 맞았는데
 *       {@code ServerPlaceRecipe} 가 재료를 <b>꺼내는</b> 길과 조합칸을 <b>되돌리는</b> 길이
 *       {@code Inventory} 만 알아서, 흰색으로 떠 있는 조합법을 눌러도 조합칸이 반만 채워졌다.
 *       왜 세 자리였고 되돌린 물건이 어디로 가는지는
 *       {@code com.sharedfate.inventory.ExpandedRecipeFill} 의 클래스 문서에 있다.</li>
 * </ul>
 *
 * <p>다섯 화면이 모두 한 번에 고쳐진다 — 눌렀을 때의 길이 {@code AbstractCraftingMenu}
 * (인벤토리 2×2·제작대 3×3)와 {@code AbstractFurnaceMenu}(화로·훈연기·용광로) 둘로 모이고,
 * 그 둘이 다 {@code ServerPlaceRecipe} 로 들어간다.
 */
@Mixin(AbstractRecipeBookScreen.class)
public abstract class AbstractRecipeBookScreenMixin {
	/** 바닐라 조합법 책 단추의 크기. {@code initButton} 이 쓰는 값 그대로다. */
	@Unique
	private static final int BUTTON_WIDTH = 20;
	@Unique
	private static final int BUTTON_HEIGHT = 18;

	@Shadow
	@Final
	private RecipeBookComponent<?> recipeBookComponent;

	/**
	 * 바닐라가 {@code init} 에서 {@code width < 379} 로 세우는 값.
	 *
	 * <p>{@code initButton()} 도 {@code init} 도 이 값이 정해진 <b>뒤</b>에 돌므로 그대로 읽어도
	 * 된다. 379 를 여기에 베껴 적지 않고 바닐라가 잰 것을 같이 보는 이유는
	 * {@link RecipeBookGate#MIN_SCREEN_WIDTH} 에 있다.
	 */
	@Shadow
	private boolean widthTooNarrow;

	@Shadow
	protected abstract ScreenPosition getRecipeBookButtonPosition();

	/**
	 * 폭이 넉넉하면 바닐라가 하던 대로 둔다. 좁으면 진짜 단추 대신 <b>이유를 단 죽은 단추</b>를
	 * 놓는다.
	 *
	 * <p>취소하면 {@code recipeBookComponent} 를 위젯으로 등록하는 줄도 함께 사라진다. 이 갈래는
	 * 좁을 때만 지나가고 그때는 {@link #sharedfate$closeWhenTooNarrow} 가 책을 닫아 두므로,
	 * 위젯으로 등록해 봐야 어차피 아무 입력도 받지 않는다
	 * ({@code RecipeBookComponent} 의 클릭·타이핑 처리는 하나같이 맨 앞에서
	 * {@code isVisible()} 부터 본다).
	 *
	 * <p>죽은 단추의 자리는 바닐라 단추와 같은 {@link #getRecipeBookButtonPosition()} 에서
	 * 가져온다 — 그 값은 {@link RecipeBookButtonPositionMixin} 이 이미 창에 맞춰 올려 둔 것이라,
	 * 좁은 화면에서도 추가 칸 위에 앉지 않는다.
	 */
	@Inject(method = "initButton", at = @At("HEAD"), cancellable = true)
	private void sharedfate$noButtonWhenTooNarrow(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (!RecipeBookGate.tooNarrow(self.width, widthTooNarrow)) {
			return;
		}
		ci.cancel();

		ScreenPosition where = getRecipeBookButtonPosition();
		ImageButton notice = new ImageButton(where.x(), where.y(), BUTTON_WIDTH, BUTTON_HEIGHT,
				RecipeBookComponent.RECIPE_BUTTON_SPRITES, button -> {
				});
		// visible 은 그대로 두고 active 만 내린다. 안 보이게 감추면 알릴 것이 없어진다.
		notice.active = false;
		notice.setTooltip(Tooltip.create(Component.literal(RecipeBookGate.NARROW_NOTICE)));
		((ScreenAccessor) this).sharedfate$addRenderableWidget(notice);
	}

	/**
	 * 화면을 세울 때 <b>좁으면</b> 책을 닫는다.
	 *
	 * <p>좁은 화면에서는 단추가 없으니 사람이 새로 열 길은 없지만, 저장된 「예전에 열어 뒀음」
	 * 값은 이 손질과 무관하게 남아 있다가 {@code tick()} 이 그대로 되살릴 수 있다. 넓은 화면에서
	 * 책을 펴 둔 채로 GUI 배율을 올려 좁아진 채 다시 들어오는 길이 바로 그 경우다 — 여기서
	 * 한 번 확실히 닫아 저장값까지 고쳐 두면 그 뒤로는 무엇도 다시 열 것이 없다.
	 *
	 * <p>넓은 화면에서는 손대지 않는다. 사람이 열어 둔 것을 매번 닫아 버리면 「열어 둔 채로
	 * 다시 들어온다」는 바닐라 편의가 통째로 사라진다.
	 *
	 * <p>다만 여기서 한 번 닫으면 그 「닫힘」이 저장값에 남고 서버에도 전해진다
	 * ({@code setVisible(false)} → {@code ClientRecipeBook.setOpen} +
	 * {@code sendUpdateSettings()}). 그래서 배율을 되돌려 넓어진 뒤에는 책이 저절로 다시
	 * 펴지지 않고 단추를 한 번 눌러야 한다. 열린 채로 남겨 두면 그 화면이 빈 배경판이 되므로
	 * 이쪽이 맞다.
	 *
	 * <p>여기서 닫아도 창 좌표가 어긋나지 않는다. {@code init} 은 이 지점 앞에서
	 * {@code leftPos = updateScreenPosition(...)} 을 이미 계산했지만, 그 셈은 <b>좁으면 책이
	 * 펴져 있어도</b> 가운데 정렬 값을 돌려주므로 닫기 전후가 같은 값이다.
	 */
	@Inject(method = "init", at = @At("TAIL"))
	private void sharedfate$closeWhenTooNarrow(CallbackInfo ci) {
		Screen self = (Screen) (Object) this;
		if (RecipeBookGate.tooNarrow(self.width, widthTooNarrow)
				&& recipeBookComponent.isVisible()) {
			recipeBookComponent.toggleVisibility();
		}
	}
}
