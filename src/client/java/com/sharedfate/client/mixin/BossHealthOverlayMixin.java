package com.sharedfate.client.mixin;

import com.sharedfate.sync.RunProgressManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 회차 표시 보스바에서 <b>막대만</b> 지운다. 「SharedFate · N회차 …」 글자는 그대로 둔다.
 *
 * <p>바닐라 보스바는 이름과 막대가 한 덩어리다 — 서버 쪽에는 막대만 빼는 스위치가 없고,
 * 진행도를 0으로 내려도 <b>빈 막대 테두리가 그대로 남는다.</b> 그래서 그리는 쪽에서 끊는다.
 *
 * <p>{@code BossHealthOverlay.extractRenderState} 는 보스바 하나마다
 * ① {@code extractBar} 로 막대를 그리고 ② 그 위(막대 y - 9)에 이름을 그린 뒤
 * ③ y 를 19픽셀 내린다. ①만 취소하면 <b>이름의 자리와 크기, 그리고 아래에 깔리는 다른
 * 보스바들의 위치가 전부 그대로다.</b> 막대가 있던 5픽셀은 빈칸으로 남는데, 그 자리를
 * 줄이려면 ③의 증가분까지 건드려야 하고 그러면 드래곤 보스바가 위로 밀린다. 사람이
 * 거슬려 한 것은 막대지 간격이 아니므로 위치를 지키는 쪽을 골랐다.
 *
 * <p>고르는 기준은 {@link RunProgressManager#BOSS_EVENT_ID} 다 — 이름이나 색으로 고르면
 * 문구가 바뀌는 날 조용히 어긋난다. 식별자로 고르므로 <b>드래곤 보스바를 비롯한 다른
 * 보스바는 손도 대지 않는다.</b>
 *
 * <p>서버가 {@code showRunBossBar} 를 꺼 두면 이 보스바 자체가 만들어지지 않으니 여기까지
 * 오지 않는다. 즉 이 믹스인은 꺼진 설정의 동작을 바꾸지 않는다.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
	@Inject(
			method = "extractBar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/BossEvent;)V",
			at = @At("HEAD"),
			cancellable = true
	)
	private void sharedfate$skipRunProgressBar(
			GuiGraphicsExtractor extractor, int x, int y, BossEvent event, CallbackInfo ci) {
		if (RunProgressManager.BOSS_EVENT_ID.equals(event.getId())) {
			ci.cancel();
		}
	}
}
