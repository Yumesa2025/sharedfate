package com.sharedfate.client;

import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * 조합법 책을 켜도 되는 화면인지, 그리고 이 모드가 늘린 창만큼 단추를 얼마나 올려야 하는지
 * 한곳에서 정한다.
 *
 * <p>왜 한때 책을 통째로 죽였는지는
 * {@code com.sharedfate.client.mixin.AbstractRecipeBookScreenMixin} 의 클래스 문서에 있다.
 * 여기에는 <b>다시 켜기로 하고 나서 계산한 것</b>만 적는다.
 */
public final class RecipeBookGate {
	/**
	 * 조합법 책을 펼 수 있는 가장 좁은 화면 폭.
	 *
	 * <h2>왜 바닐라와 같은 379 인가 — 이 모드는 가로를 한 픽셀도 더 쓰지 않는다</h2>
	 *
	 * <p>바닐라 {@code AbstractRecipeBookScreen.init} 은 {@code width < 379} 이면
	 * {@code widthTooNarrow} 를 세우고, 그 상태에서 책이 펴져 있으면 칸도 아이템도 그리지
	 * 않는다. 379 가 어디서 나온 값인지는 바닐라가 적어 두지 않았지만, 이 모드가 그 값을
	 * <b>더 크게</b> 잡아야 하는지는 셈으로 답이 나온다.
	 *
	 * <p>책이 펴지면 바닐라는 가로 자리를 이렇게 나눈다(창 폭 {@code iw} = 176 은 다섯 화면이
	 * 모두 같다).
	 * <ul>
	 *   <li>책 왼쪽 = {@code (w − 147) / 2 − 86} — {@code RecipeBookComponent.getXOrigin()}</li>
	 *   <li>책 오른쪽 = 그 자리 + 147</li>
	 *   <li>창 왼쪽 = {@code 177 + (w − iw − 200) / 2}
	 *       — {@code RecipeBookComponent.updateScreenPosition}</li>
	 * </ul>
	 * 둘 사이에 1~2px 이 남는다(폭이 홀수면 1, 짝수면 2 — 379 에서는 177 과 178 이다).
	 * 그리고 <b>이 셋 어디에도 창 높이가 들어가지 않는다.</b>
	 * 이 모드가 늘린 것은 {@code imageHeight} 뿐이므로 가로 셈은 바닐라와 한 글자도 다르지
	 * 않고, 따라서 더 잡을 이유가 없다. 세로도 마찬가지다 — 책은
	 * {@code getYOrigin() = (height − 166) / 2} 로 <b>화면</b> 한가운데에 서지 창에 붙지
	 * 않으므로, 창이 세로로 최대 54px({@code EXTRA_PANEL_HEIGHT}) 길어져도 책이 밀려나거나
	 * 화면 밖으로 넘치지 않는다. 창과 책은 가로로 나란히 서 있어 세로로는 애초에 겹칠 수가
	 * 없다.
	 *
	 * <p>그래서 값은 바닐라와 같다. 다만 이 숫자만 믿고 판정하지는 않는다 —
	 * {@link #tooNarrow} 가 바닐라가 스스로 세운 {@code widthTooNarrow} 와 <b>함께</b> 본다.
	 * 판이 올라 바닐라가 문턱을 더 높이면 그쪽이 먼저 걸리고, 낮추더라도 여기서 계산한 379 는
	 * 지켜진다. 어느 쪽으로 어긋나든 「그리지 않는 분기」로 들어가는 일은 없다.
	 */
	public static final int MIN_SCREEN_WIDTH = 379;

	/**
	 * 책을 못 켜는 화면에서 단추 자리에 남겨 두는 안내.
	 *
	 * <p>단추를 그냥 없애 두면 초보자는 조합법이 <b>이 모드에 아예 없는 것</b>으로 읽는다.
	 * 그래서 자리는 남기고 누를 수 없게만 해서, 올려놓으면 이 한 줄이 뜨게 한다. 자막으로
	 * 띄우지 않는 이유는 자막이 화면을 열지 않아도 지나가는 알림이라, 「지금 이 창의 이
	 * 단추가 왜 죽어 있는가」를 묶어 주지 못하기 때문이다.
	 */
	public static final String NARROW_NOTICE =
			"화면이 좁아 조합법 책을 펼 수 없습니다 · GUI 배율을 낮추거나 창을 넓히면 쓸 수 있습니다";

	private RecipeBookGate() {
	}

	/**
	 * 이 화면에서 조합법 책을 막아야 하는가.
	 *
	 * @param screenWidth       {@code Screen.width}
	 * @param vanillaTooNarrow  바닐라가 {@code init} 에서 세운 {@code widthTooNarrow}
	 */
	public static boolean tooNarrow(int screenWidth, boolean vanillaTooNarrow) {
		return vanillaTooNarrow || screenWidth < MIN_SCREEN_WIDTH;
	}

	/**
	 * 이 메뉴에서 추가 칸이 <b>지금</b> 차지하고 있는 높이. 창은 이만큼 세로로 길어져 있다.
	 *
	 * <p>판정이 {@code ContainerScreenMixin.sharedfate$expandedInventoryTop()} 과 <b>한 글자도
	 * 달라서는 안 된다.</b> 저쪽이 이 높이를 {@code imageHeight} 에 더하고 이쪽은 그 더해진
	 * 만큼을 되돌리는 것이라, 둘의 판정이 갈리면 단추가 엉뚱한 자리로 간다.
	 */
	public static int activePanelHeight(AbstractContainerMenu menu) {
		if (!(menu instanceof ExpandedMenuLayout layout)) {
			return 0;
		}
		int extraStart = layout.sharedfate$extraSlotStart();
		int inventoryTop = layout.sharedfate$inventoryTopY();
		if (extraStart < 0 || inventoryTop < 0
				|| extraStart + ExpandedInventoryManager.EXTRA_SIZE > menu.slots.size()) {
			return 0;
		}
		if (!menu.getSlot(extraStart).isActive()) {
			return 0;
		}
		return ExpandedInventoryManager.panelHeightFor(
				ExpandedInventoryManager.clientUnlockedSlots());
	}

	/**
	 * 조합법 책 단추를 창에 맞춰 <b>올려야 할</b> 픽셀 수.
	 *
	 * <h2>왜 단추가 저 혼자 흘러내리는가</h2>
	 *
	 * <p>다섯 화면의 {@code getRecipeBookButtonPosition()} 은 y 를 모두
	 * {@code height / 2 − K} 로 잡는다(인벤토리 K=22, 제작대·화로 K=49). 창 위 끝이 아니라
	 * <b>화면</b> 한가운데를 기준으로 잰다는 뜻이다. 창 높이가 바닐라 166 이던 시절에는
	 * {@code topPos = (height − 166) / 2} 라 두 기준이 늘 같은 거리를 유지했다.
	 *
	 * <p>이 모드가 창을 {@code P} 만큼 늘리면 {@code topPos = (height − 166 − P) / 2} 로
	 * <b>위로</b> {@code P/2} 올라가는데 단추 y 는 그대로다. 창 위 끝에서 재면 단추가
	 * {@code P/2} 만큼 <b>아래로</b> 밀려난 셈이다. 인벤토리 화면의 바닐라 자리는 창 안쪽
	 * y 61~79 인데, P=36(기본 두 줄)이면 79~97 로 내려와 인벤토리 첫 줄(y 84~100)을
	 * 파고든다. P=54(「짐꾼」까지 열렸을 때)면 88~106 으로 더 깊이 들어간다.
	 *
	 * <p>{@code P} 는 늘 18의 배수라 {@code P/2} 에 나머지가 생기지 않는다. 그래서 단추 y 에서
	 * 이 값을 빼면 창 위 끝과의 거리가 바닐라와 <b>정확히</b> 같아진다.
	 */
	public static int buttonLift(AbstractContainerMenu menu) {
		return activePanelHeight(menu) / 2;
	}
}
