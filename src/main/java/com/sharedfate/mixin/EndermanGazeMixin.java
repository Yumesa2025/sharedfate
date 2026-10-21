package com.sharedfate.mixin;

import com.sharedfate.perk.DragonPrep;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「엔더 동화」 — 엔더맨이 팀원의 시선을 「쳐다봤다」로 세지 않는다(0.44.0-dev).
 *
 * <h2>시선 적대만 끈다</h2>
 * <p>26.3 {@code Enderman$EndermanLookForPlayerGoal} 의 대상 고르기는
 * {@code isBeingStaredBy(player) || isAngryAt(player)} 이고, 걸려 있는 동안의 순간이동·고개 돌리기도
 * {@code isBeingStaredBy} 를 묻는다. 앞쪽만 거짓으로 돌리므로 <b>때려서 화가 난 엔더맨</b>과
 * <b>「밤의 군세」가 덤비게 한 엔더맨</b>(목표·분노를 직접 건다)은 {@code isAngryAt} 쪽이라 그대로 덤빈다.
 *
 * <h2>대상</h2>
 * <p>{@code private boolean isBeingStaredBy(Player)}(26.3 {@code javap -p}). 시험이 그 모양을 못박는다.
 * 서버 플레이어만 본다 — 판정은 {@link DragonPrep#ignoresGaze} 가 팀 상태로 한다.
 */
@Mixin(Enderman.class)
public abstract class EndermanGazeMixin {

	@Inject(method = "isBeingStaredBy(Lnet/minecraft/world/entity/player/Player;)Z",
			at = @At("HEAD"), cancellable = true)
	private void sharedfate$enderKinship(Player player, CallbackInfoReturnable<Boolean> callback) {
		if (DragonPrep.ignoresGaze(player)) {
			callback.setReturnValue(false);
		}
	}
}
