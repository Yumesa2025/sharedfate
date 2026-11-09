package com.sharedfate.mixin;

import com.sharedfate.recipe.ShieldRecipes;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 숫돌로 강철·다이아몬드 방패를 다른 방패와 합치지 못하게 한다.
 *
 * <p>바닐라 {@code mergeItems} 는 위 칸을 복사해 큰 최대 내구도만 적는다. 그래서 일반 방패 위에
 * 다이아몬드 방패를 얹으면 「닳지 않음」·달리기 허용 없이 내구도만 3000 인 방패가 나왔다. 결과 칸을
 * 비워 꺼내지 못하게 한다. 클라이언트도 같은 판정을 돌리므로 화면과 실제가 갈리지 않는다.
 */
@Mixin(GrindstoneMenu.class)
public abstract class GrindstoneMenuShieldMixin {
	@Inject(method = "mergeItems", at = @At("HEAD"), cancellable = true)
	private void sharedfate$blockModShieldMerge(ItemStack first, ItemStack second,
			CallbackInfoReturnable<ItemStack> callback) {
		if (ShieldRecipes.blocksShieldMerge(first, second)) {
			callback.setReturnValue(ItemStack.EMPTY);
		}
	}
}
