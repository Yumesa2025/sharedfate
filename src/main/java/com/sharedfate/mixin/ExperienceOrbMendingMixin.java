package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ExperienceOrb;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 수리는 원래대로 하고, 수선에 쓰인 경험치도 플레이어에게 온전히 넘긴다. */
@Mixin(ExperienceOrb.class)
public abstract class ExperienceOrbMendingMixin {
	@WrapOperation(method = "playerTouch", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/world/entity/ExperienceOrb;repairPlayerItems(Lnet/minecraft/server/level/ServerPlayer;I)I"))
	private int sharedfate$keepExperience(ExperienceOrb orb, ServerPlayer player, int amount,
			Operation<Integer> original) {
		return com.sharedfate.enchant.MendingExperience.repairAndKeep(amount,
				points -> original.call(orb, player, points));
	}
}
