package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.perk.PerkDamage;
import com.sharedfate.perk.PerkShieldCombat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 방향만 바꾸고 지연·관통·차단 불가 피해 등 나머지 판정은 바닐라에 맡긴다. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityShieldCombatMixin {
	@ModifyArg(method = "applyItemBlocking", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/world/item/component/BlocksAttacks;resolveBlockedDamage(Lnet/minecraft/world/damagesource/DamageSource;FD)F"), index = 2)
	private double sharedfate$allDirections(DamageSource source, float amount, double angle) {
		// 출처 위치가 없는 피해(바닐라가 π 로 둬 못 막게 하는 것)는 등껍질도 그대로 둔다.
		return PerkDamage.turtleAngle((LivingEntity) (Object) this, source, angle);
	}

	@WrapOperation(method = "applyItemBlocking", at = @At(value = "INVOKE", target =
			"Lnet/minecraft/world/item/component/BlocksAttacks;hurtBlockingItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"))
	private void sharedfate$arrowWear(BlocksAttacks blocks, Level level, ItemStack stack,
			LivingEntity entity, InteractionHand hand, float amount, Operation<Void> original,
			ServerLevel serverLevel, DamageSource source, float incoming) {
		int previous = PerkShieldCombat.wearContext();
		PerkShieldCombat.wearContext(PerkShieldCombat.blockWearMultiplier(entity, source));
		try { original.call(blocks, level, stack, entity, hand, amount); }
		finally { PerkShieldCombat.wearContext(previous); }
	}

	@Inject(method = "applyItemBlocking", at = @At("RETURN"))
	private void sharedfate$blocked(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Float> callback) {
		PerkShieldCombat.onBlocked((LivingEntity) (Object) this, source, callback.getReturnValueF());
	}
}
