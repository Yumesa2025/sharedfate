package com.sharedfate.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.sharedfate.client.hud.PendingDamageHearts;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「완충」이 곧 깎을 체력을 바닐라 체력바 위에 겹친다. 그리는 일은 {@link PendingDamageHearts} 가
 * 하고, 여기서는 바닐라가 하트를 <b>실제로 그린 자리</b>만 넘겨준다.
 *
 * <h2>왜 HUD 요소가 아니라 믹스인인가</h2>
 * <p>Fabric 의 HUD 요소로 체력바 뒤에 붙이면 하트 자리를 따로 계산해야 한다. 그런데 체력이 낮으면
 * 하트마다 {@code Hud.random} 으로 한 칸씩 흔들리고, 그 난수는 {@code Hud} 안에서 같은 프레임의
 * 허기 막대까지 이어 쓰여 밖에서 똑같이 다시 굴릴 수 없다. 그대로 두면 체력이 가장 위태로울 때
 * 덮개가 하트에서 어긋난다. 그래서 하트를 그리는 자리에서 좌표를 받는다.
 *
 * <h2>무는 자리 (26.3 바이트코드로 확인)</h2>
 * <p>{@code Hud.extractHearts(GuiGraphicsExtractor, Player, int x, int y, int rowHeight,
 * int regenIndex, float maxHealth, int health, int displayHealth, int absorption, boolean blinking)}
 * 는 하트 하나마다 {@code extractHeart} 를 최대 넷 부르는데, <b>첫째(ordinal 0)가 언제나 빈 틀</b>
 * ({@code HeartType.CONTAINER})이다. 그 직전에 지역변수가 이렇게 차 있다.
 *
 * <ul>
 *   <li>17번 — 하트 번호 {@code i}(체력 칸 다음에 흡수 칸이 이어진다)</li>
 *   <li>20번 — 그 하트의 x</li>
 *   <li>21번 — 그 하트의 y. 흔들림({@code random.nextInt(2)})과 재생 튀어 오름(-2)이 이미 반영됐다</li>
 * </ul>
 *
 * <p>{@code extractHearts} 는 {@code private} 라 재정의될 수 없고, {@code Hud} 를 상속하는 클래스도
 * 없다. 지역변수 번호가 판올림으로 바뀌면 MixinExtras 가 타입이 맞지 않는다며 불러올 때 멈춘다 —
 * 조용히 어긋나지 않는다.
 *
 * <p>덮을 것이 없으면({@code spreadPending} 이 0) {@link PendingDamageHearts#begin} 에서 꺼져
 * 나머지 둘은 첫 줄에서 돌아간다.
 */
@Mixin(Hud.class)
public abstract class HudPendingDamageMixin {
	@Inject(method = "extractHearts", at = @At("HEAD"))
	private void sharedfate$beginPendingHearts(GuiGraphicsExtractor graphics, Player player, int x,
			int y, int rowHeight, int regenIndex, float maxHealth, int health, int displayHealth,
			int absorption, boolean blinking, CallbackInfo ci) {
		PendingDamageHearts.begin();
	}

	@Inject(
			method = "extractHearts",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/Hud;extractHeart(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/gui/Hud$HeartType;IIZZZ)V",
					ordinal = 0
			)
	)
	private void sharedfate$notePendingHeart(GuiGraphicsExtractor graphics, Player player, int x,
			int y, int rowHeight, int regenIndex, float maxHealth, int health, int displayHealth,
			int absorption, boolean blinking, CallbackInfo ci,
			@Local(index = 17) int heart, @Local(index = 20) int heartX,
			@Local(index = 21) int heartY) {
		PendingDamageHearts.note(heart, heartX, heartY);
	}

	@Inject(method = "extractHearts", at = @At("TAIL"))
	private void sharedfate$drawPendingHearts(GuiGraphicsExtractor graphics, Player player, int x,
			int y, int rowHeight, int regenIndex, float maxHealth, int health, int displayHealth,
			int absorption, boolean blinking, CallbackInfo ci) {
		PendingDamageHearts.draw(graphics, player, maxHealth);
	}
}
