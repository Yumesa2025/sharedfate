package com.sharedfate.mixin;

import com.sharedfate.perk.PerkShieldCombat;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 방패 돌진의 짧은 경직은 저장되는 NoAI 대신 그 틱의 AI만 쉰다. */
@Mixin(Mob.class)
public abstract class MobShieldStunMixin {
	@Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
	private void sharedfate$stun(CallbackInfo callback) {
		if (PerkShieldCombat.stunned((Mob) (Object) this)) callback.cancel();
	}
}
