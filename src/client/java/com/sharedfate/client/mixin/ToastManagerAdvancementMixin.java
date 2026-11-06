package com.sharedfate.client.mixin;

import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 발전과제를 달성했을 때 오른쪽 위에 뜨는 「발전 과제 달성!」 알림을 띄우지 않는다.
 *
 * <p>사람이 정한 것(2026-11-06) — 「발전과제를 꺼 줘, 안 뜨게」. 채팅 줄은 서버가 이미 끈다
 * ({@code WorldGameRules}). 달성 자체는 그대로 두어야 발전과제 경험치({@code AdvancementExperienceRule})가
 * 계속 들어오므로, 클라이언트에서 알림만 버린다. 다른 알림(튜토리얼 · 시스템)은 건드리지 않는다.
 */
@Mixin(ToastManager.class)
public abstract class ToastManagerAdvancementMixin {
	@Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
	private void sharedfate$dropAdvancementToast(Toast toast, CallbackInfo callback) {
		if (toast instanceof AdvancementToast) {
			callback.cancel();
		}
	}
}
