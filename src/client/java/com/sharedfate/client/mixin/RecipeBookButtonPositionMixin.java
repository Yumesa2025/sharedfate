package com.sharedfate.client.mixin;

import com.sharedfate.client.RecipeBookGate;
import net.minecraft.client.gui.navigation.ScreenPosition;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 조합법 책 단추를 <b>이 모드가 늘린 창</b>에 맞춰 올린다.
 *
 * <p>왜 단추가 저 혼자 흘러내리는지, 얼마나 올려야 하는지는
 * {@link RecipeBookGate#buttonLift} 에 셈과 함께 적어 두었다. 여기서는 그 값을 어디에
 * 끼워 넣는지만 다룬다.
 *
 * <h2>왜 {@code AbstractRecipeBookScreen} 이 아니라 세 곳인가</h2>
 *
 * <p>{@code getRecipeBookButtonPosition()} 은 {@code AbstractRecipeBookScreen} 에서
 * <b>abstract</b> 다 — 코드가 없으니 거기에는 파고들 자리가 없다. 실제 몸을 가진 곳은 셋뿐이고
 * ({@code InventoryScreen}, {@code CraftingScreen}, {@code AbstractFurnaceScreen}), 화로·훈연기·
 * 용광로 세 화면은 {@code AbstractFurnaceScreen} 것을 그대로 물려받아 따로 덮지 않는다
 * (26.3 바이트코드로 확인). 그래서 이 셋이 다섯 화면 전부를 덮는다.
 *
 * <h2>왜 단추를 만드는 자리가 아니라 좌표를 내주는 자리인가</h2>
 *
 * <p>{@code getRecipeBookButtonPosition()} 을 부르는 곳은 <b>둘</b>이다 —
 * {@code initButton()} 이 단추를 처음 세울 때와, 단추를 누를 때 도는
 * {@code lambda$initButton$0} 이다. 뒤엣것은 책을 펴고 닫으며 창이 좌우로 밀린 만큼 단추를
 * {@code setPosition} 으로 다시 놓는다. 그래서 단추를 만드는 자리만 고치면 <b>한 번 누르는
 * 순간 원래의 흘러내린 자리로 되돌아간다.</b> 좌표를 내주는 한 곳을 고치면 둘 다 따라온다.
 * 이름이 컴파일러가 붙인 {@code lambda$initButton$0} 인 메서드를 대상으로 적지 않아도 되는
 * 것은 덤이다 — 그런 이름은 판이 바뀌면 조용히 달라진다.
 */
@Mixin({InventoryScreen.class, CraftingScreen.class, AbstractFurnaceScreen.class})
public abstract class RecipeBookButtonPositionMixin {
	/**
	 * 서술자까지 적는다. 이 저장소에는 refmap 이 없어 대상이 틀려도 빌드는 그냥 통과한다.
	 */
	@Inject(
			method = "getRecipeBookButtonPosition()"
					+ "Lnet/minecraft/client/gui/navigation/ScreenPosition;",
			at = @At("RETURN"),
			cancellable = true)
	private void sharedfate$liftButtonToWindow(CallbackInfoReturnable<ScreenPosition> cir) {
		int lift = RecipeBookGate.buttonLift(
				((AbstractContainerScreen<?>) (Object) this).getMenu());
		if (lift == 0) {
			// 추가 칸이 없는 판(설정이 세 줄이거나 팀 밖)에서는 창 높이가 바닐라 그대로다.
			return;
		}
		ScreenPosition vanilla = cir.getReturnValue();
		cir.setReturnValue(new ScreenPosition(vanilla.x(), vanilla.y() - lift));
	}
}
