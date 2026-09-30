package com.sharedfate.ui;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.RecipeBookGate;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.inventory.BlastFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.FurnaceScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.SmokerScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 조합법 책을 <b>좁은 화면에서만</b> 막는 손질이 무는 자리.
 *
 * <p>처음에는 책을 통째로 없앴고 이름도 그때 붙은 「Removal」이었다. 지금은 없애지 않고 폭이
 * 모자랄 때만 막으므로 이름을 {@code RecipeBookGate} 에 맞췄다. 여기서 붙드는 것도 「막는
 * 자리」와 「되살린 단추가 창을 따라가게 하는 자리」 둘이다.
 *
 * <p>시험 소스셋은 {@code src/client} 의 믹스인을 보지 못하지만 바닐라 클라이언트 클래스는
 * 볼 수 있다. refmap 이 없어 {@code @Inject}·{@code @Shadow} 의 대상이 틀려도 빌드는 그냥
 * 통과하고 제작대나 인벤토리를 여는 순간에야 터지므로, 대상 서술자만이라도 여기서 붙들어 둔다.
 */
class RecipeBookGateTargetTest {
	/** 다섯 화면이 모두 같은 창 폭을 쓴다. {@code AbstractContainerScreen.DEFAULT_IMAGE_WIDTH}. */
	private static final int IMAGE_WIDTH = 176;
	/** 조합법 책 판의 폭. {@code RecipeBookComponent.IMAGE_WIDTH}. */
	private static final int BOOK_WIDTH = 147;
	/** 책이 화면 가운데에서 왼쪽으로 물러나는 거리. {@code RecipeBookComponent.OFFSET_X_POSITION}. */
	private static final int BOOK_OFFSET_X = 86;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/**
	 * 단추를 만드는 자리. {@code @Inject(method = "initButton", at = @At("HEAD"),
	 * cancellable = true)} 가 <b>폭이 모자랄 때만</b> 여기서 되돌아 나가, 진짜 단추 대신
	 * 이유를 단 죽은 단추를 놓는다.
	 */
	@Test
	void 단추를_만드는_initButton이_그대로_있다() {
		assertDoesNotThrow(
				() -> AbstractRecipeBookScreen.class.getDeclaredMethod("initButton"),
				"이름이 바뀌거나 사라지면 단추를 막는 손질이 아무 데도 안 붙는다");
	}

	/**
	 * 화면을 세우는 자리. {@code @Inject(method = "init", at = @At("TAIL"))} 이 여기서
	 * <b>좁을 때만</b> 책을 강제로 닫는다 — {@code RecipeBookComponent.init} 이 저장값으로 이미
	 * 열어 둔 뒤라야 뜻이 있으므로 반드시 TAIL 이어야 한다.
	 */
	@Test
	void 화면을_세우는_init이_그대로_있다() {
		assertDoesNotThrow(
				() -> AbstractRecipeBookScreen.class.getDeclaredMethod("init"),
				"서명이 바뀌면 책을 강제로 닫는 손질이 엉뚱한 자리에 붙거나 아예 안 붙는다");
	}

	/**
	 * 폭이 모자란지 바닐라가 스스로 적어 두는 밭. {@code @Shadow} 로 그대로 읽는다.
	 *
	 * <p>이 값이 사라지면 379 를 어디선가 다시 재야 하는데, 그러면 바닐라가 문턱을 옮길 때
	 * 조용히 어긋난다.
	 */
	@Test
	void widthTooNarrow_밭이_그대로다() throws NoSuchFieldException {
		var field = AbstractRecipeBookScreen.class.getDeclaredField("widthTooNarrow");
		assertEquals(boolean.class, field.getType(),
				"타입이 달라지면 @Shadow 선언이 어긋나 결합 자체가 실패한다");
	}

	/**
	 * 단추 자리를 내주는 메서드. {@code RecipeBookButtonPositionMixin} 이 여기에 붙어
	 * 이 모드가 늘린 창만큼 단추를 올린다.
	 *
	 * <p>{@code AbstractRecipeBookScreen} 에서는 abstract 라 파고들 자리가 없다. 몸을 가진
	 * 셋에만 붙이므로 <b>그 셋이 그대로인지</b>가 중요하다.
	 */
	@Test
	void 단추_자리를_내주는_메서드가_세_곳에만_있다() throws NoSuchMethodException {
		for (Class<?> screen : new Class<?>[] {
				InventoryScreen.class, CraftingScreen.class, AbstractFurnaceScreen.class}) {
			var method = screen.getDeclaredMethod("getRecipeBookButtonPosition");
			assertEquals(ScreenPosition.class, method.getReturnType(),
					screen.getSimpleName() + " 의 반환형이 달라지면 믹스인이 안 붙는다");
		}
		// 화로 셋이 제 것을 따로 두면 AbstractFurnaceScreen 에 붙인 손질이 그 셋을 놓친다.
		for (Class<?> screen : new Class<?>[] {
				FurnaceScreen.class, SmokerScreen.class, BlastFurnaceScreen.class}) {
			assertFalse(hasOwnButtonPosition(screen),
					screen.getSimpleName() + " 이 제 자리를 따로 잡기 시작하면 여기에도 손질을 붙여야 한다");
		}
	}

	private static boolean hasOwnButtonPosition(Class<?> screen) {
		try {
			screen.getDeclaredMethod("getRecipeBookButtonPosition");
			return true;
		} catch (NoSuchMethodException expected) {
			return false;
		}
	}

	/**
	 * 죽은 단추가 빌려 쓰는 바닐라 그림.
	 *
	 * <p>{@code public static} 이어야 {@code src/client} 에서 가져다 쓸 수 있다.
	 */
	@Test
	void 단추_그림이_밖에서_보인다() throws NoSuchFieldException {
		var field = RecipeBookComponent.class.getDeclaredField("RECIPE_BUTTON_SPRITES");
		assertEquals(WidgetSprites.class, field.getType());
		assertTrue(Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()),
				"밖에서 못 보면 죽은 단추가 다른 그림을 써야 해 자리만 같고 모양이 달라진다");
	}

	/**
	 * 경계 폭에서 펼친 책과 밀린 창이 겹치지 않는다.
	 *
	 * <p>{@link RecipeBookGate#MIN_SCREEN_WIDTH} 를 바닐라와 같은 값으로 둔 근거가 이 셈이다.
	 * 여기서 쓰는 식에 창 <b>높이</b>가 한 번도 나오지 않는다는 것이 요점이다 — 이 모드가
	 * 늘린 것은 높이뿐이라 경계값을 더 잡을 이유가 없다.
	 */
	@Test
	void 경계_폭에서_책과_창이_겹치지_않는다() {
		int width = RecipeBookGate.MIN_SCREEN_WIDTH;
		int bookRight = (width - BOOK_WIDTH) / 2 - BOOK_OFFSET_X + BOOK_WIDTH;
		int windowLeft = 177 + (width - IMAGE_WIDTH - 200) / 2;

		assertTrue(bookRight <= windowLeft,
				"책 오른쪽 " + bookRight + " 이 창 왼쪽 " + windowLeft + " 을 넘으면 책 위에 창이 얹힌다");
		assertTrue(windowLeft + IMAGE_WIDTH <= width, "창 오른쪽이 화면 밖으로 나가면 안 된다");
		assertTrue((width - BOOK_WIDTH) / 2 - BOOK_OFFSET_X >= 0, "책 왼쪽이 화면 밖으로 나가면 안 된다");
	}

	/** 바닐라가 좁다고 하면 우리도 좁다고 해야 한다. 그 반대는 우리 쪽이 더 엄해도 된다. */
	@Test
	void 바닐라가_좁다고_하면_무조건_막는다() {
		assertTrue(RecipeBookGate.tooNarrow(1920, true), "바닐라 판정을 뒤집으면 빈 배경판이 된다");
		assertTrue(RecipeBookGate.tooNarrow(RecipeBookGate.MIN_SCREEN_WIDTH - 1, false));
		assertFalse(RecipeBookGate.tooNarrow(RecipeBookGate.MIN_SCREEN_WIDTH, false));
	}

	/**
	 * {@code @Shadow} 로 끌어다 쓰는 밭. private final 이라 이름과 타입이 정확히 맞아야
	 * 믹스인 결합이 된다.
	 */
	@Test
	void recipeBookComponent_밭이_그대로다() throws NoSuchFieldException {
		var field = AbstractRecipeBookScreen.class.getDeclaredField("recipeBookComponent");
		assertEquals(RecipeBookComponent.class, field.getType(),
				"타입이 달라지면 @Shadow 선언이 어긋나 결합 자체가 실패한다");
		assertTrue(Modifier.isFinal(field.getModifiers()),
				"final 이 아니게 되면 @Shadow 에도 @Final 을 반드시 맞춰 지워야 한다");
	}

	/**
	 * 책을 강제로 닫는 데 쓰는 두 메서드.
	 *
	 * <p>{@code isVisible()} 로 열려 있는지 보고, 열려 있으면 {@code toggleVisibility()} 로
	 * 끈다. 이 쪽이 {@code setVisible(false)} 를 직접 부르는 것보다 안전하다 —
	 * {@code toggleVisibility} 는 지금 상태를 스스로 뒤집으므로 이미 닫혀 있을 때 잘못 열어
	 * 버릴 일이 없다(우리는 열려 있을 때만 부르지만, 그렇더라도 이쪽이 바닐라가 원래 단추에
	 * 붙이는 것과 같은 길이라 더 믿을 수 있다).
	 */
	@Test
	void 책을_닫는_메서드_둘이_그대로_있다() {
		assertDoesNotThrow(
				() -> RecipeBookComponent.class.getDeclaredMethod("isVisible"),
				"없으면 지금 열려 있는지 알 길이 없다");
		assertDoesNotThrow(
				() -> RecipeBookComponent.class.getDeclaredMethod("toggleVisibility"),
				"없으면 강제로 닫을 길이 없다");
	}
}
