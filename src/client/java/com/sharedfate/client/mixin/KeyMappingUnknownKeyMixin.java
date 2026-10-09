package com.sharedfate.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「알 수 없는 키」(키코드 0) 입력이 <b>할당되지 않은 키 매핑 전부</b>를 누르지 못하게 막는다.
 *
 * <h2>무엇이 일어나는가</h2>
 * <p>26.3 은 할당되지 않은 매핑을 전부 {@link InputConstants#UNKNOWN}(키보드 키코드 0)
 * 아래 한 목록에 묶어 둔다. 그런데 {@code KeyboardHandler.keyPress} 는 화면이 없을 때 들어온
 * 키를 <b>UNKNOWN 인지 보지 않고</b> {@link KeyMapping#click} · {@link KeyMapping#set} 에
 * 넘긴다. SDL 은 한/영·한자 키처럼 대응하는 키코드가 없는 키를 키코드 0 으로 내므로, 게임
 * 화면에서 한/영 키를 한 번 누르면 그 목록의 매핑이 전부 한 번씩 눌린다.
 *
 * <p>기본 설정에서 그 목록에 드는 것이 「시네마틱 카메라」({@code key.smoothCamera})다. 켜지면
 * 시점 회전만 느려지고 미끄러지며(걷기는 그대로), 그 값은 설정 파일에 저장되지 않는
 * 메모리 값이라 <b>서버에 다시 들어가도 남고 게임을 껐다 켜야 풀린다.</b> 명령을 치려고
 * 한/영을 오가다 보면 아무 낌새 없이 켜진다.
 *
 * <h2>왜 이 두 메서드인가</h2>
 * <p>들어온 키 하나로 <b>그 키에 묶인 매핑 목록</b>을 누르는 길이 {@code click}(누름 횟수)과
 * {@code set}(눌린 상태) 둘이다. 둘 다 정적 메서드라 재정의될 수 없다. 화면을 닫을 때 도는
 * {@code setAll} 은 매핑마다 제 키를 직접 묻고 누름 횟수를 쌓지 않으므로 시네마틱 카메라를
 * 켜지 못한다. 진짜로 할당된 키는 UNKNOWN 이 될 수 없으므로 막아서 잃는 입력이 없다 —
 * 할당되지 않은 매핑은 원래 어떤 키로도 눌려서는 안 된다.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingUnknownKeyMixin {
	@Inject(method = "click", at = @At("HEAD"), cancellable = true)
	private static void sharedfate$ignoreUnknownClick(InputConstants.Key key, CallbackInfo ci) {
		if (InputConstants.UNKNOWN.equals(key)) {
			ci.cancel();
		}
	}

	@Inject(method = "set", at = @At("HEAD"), cancellable = true)
	private static void sharedfate$ignoreUnknownSet(InputConstants.Key key, boolean down, CallbackInfo ci) {
		if (InputConstants.UNKNOWN.equals(key)) {
			ci.cancel();
		}
	}
}
