package com.sharedfate.mixin;

import com.sharedfate.perk.PerkShieldCombat;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 막힌 화살의 바닐라 감속 반동을 쏜 몹을 향한 반사로 바꾼다. */
@Mixin(AbstractArrow.class)
public abstract class ArrowShieldReturnMixin {
	@Inject(method = "onHitEntity", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/world/entity/projectile/arrow/AbstractArrow;deflect(Lnet/minecraft/world/entity/projectile/ProjectileDeflection;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/EntityReference;ZD)Z"), cancellable = true)
	private void sharedfate$returnArrow(EntityHitResult hit, CallbackInfo callback) {
		if (PerkShieldCombat.returnArrow((AbstractArrow) (Object) this)) callback.cancel();
	}
}
