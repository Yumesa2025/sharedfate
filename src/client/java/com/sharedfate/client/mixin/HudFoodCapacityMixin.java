package com.sharedfate.client.mixin;

import com.sharedfate.client.hud.FoodCapacityHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「먹방」의 「57/100」 을 허기 막대 바로 옆에 붙인다. 그리는 일은 {@link FoodCapacityHud} 가 한다.
 *
 * <h2>왜 HUD 요소가 아니라 믹스인인가</h2>
 * <p>{@link HudPendingDamageMixin} 과 같은 까닭이다. 막대 자리를 밖에서 다시 계산하지 않고 바닐라가
 * 막대를 그린 자리를 그대로 받는다. 게다가 바닐라가 막대를 안 그리는 때(탈것 체력이 그 자리를 쓸 때,
 * 「장님 거인」이 허기 표시를 가릴 때)에는 이 자리도 지나지 않아 숫자가 저절로 함께 빠진다.
 *
 * <h2>무는 자리 (26.3 바이트코드로 확인)</h2>
 * <p>{@code Hud.extractFood(GuiGraphicsExtractor, Player, int top, int right)} 의 끝. 부르는
 * {@code extractPlayerHealth} 가 {@code top = guiHeight − 39}, {@code right = guiWidth / 2 + 91} 을
 * 넘긴다. {@code private} 라 재정의될 수 없고 {@code Hud} 를 상속하는 클래스도 없다.
 */
@Mixin(Hud.class)
public abstract class HudFoodCapacityMixin {
	@Inject(method = "extractFood", at = @At("TAIL"))
	private void sharedfate$drawFoodCapacity(GuiGraphicsExtractor graphics, Player player, int top,
			int right, CallbackInfo ci) {
		FoodCapacityHud.draw(graphics, player, top, right);
	}
}
