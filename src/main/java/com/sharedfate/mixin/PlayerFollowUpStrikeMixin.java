package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.perk.PerkFollowUpStrike;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 무기 세트 3단계 「한 번 더」의 진입점.
 *
 * <p>{@code Player.attack} 안에서 주 대상에게 피해를 넣는 {@code hurtOrSimulate} 는 하나뿐이다
 * (휩쓸기는 {@code doSweepAttack} 이 따로 넣는다). 그 호출을 감싸 <b>실제로 들어갔을 때만</b>
 * 넣은 피해량과 피해원을 {@link PerkFollowUpStrike} 에 넘긴다. 공격 게이지는 {@code attack} 이
 * 맨 앞에서 읽은 뒤 곧 되돌리므로, 같은 값을 맨 앞에서 미리 읽어 둔다.
 */
@Mixin(Player.class)
public abstract class PlayerFollowUpStrikeMixin {
	@Unique
	private float sharedfate$attackCharge;

	@Inject(method = "attack", at = @At("HEAD"))
	private void sharedfate$rememberCharge(Entity target, CallbackInfo callback) {
		sharedfate$attackCharge = ((Player) (Object) this).getAttackStrengthScale(0.5F);
	}

	@WrapOperation(method = "attack", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/world/entity/Entity;hurtOrSimulate(Lnet/minecraft/world/damagesource/DamageSource;F)Z"))
	private boolean sharedfate$followUp(Entity target, DamageSource source, float amount,
			Operation<Boolean> original) {
		boolean hurt = original.call(target, source, amount);
		if (hurt && (Object) this instanceof ServerPlayer player) {
			PerkFollowUpStrike.onMeleeHit(player, target, source, amount, sharedfate$attackCharge);
		}
		return hurt;
	}
}
