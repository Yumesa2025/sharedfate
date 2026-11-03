package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.sharedfate.enchant.ShieldEnchantments;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import java.util.function.BiConsumer;

@Mixin(EnchantmentHelper.class)
public abstract class ShieldEnchantmentSlotsMixin {
	/** 화염 지속 시간·폭발 넉백 저항처럼 보호 계열에 딸린 속성도 같은 손 슬롯에서 붙인다. */
	@Inject(method = "forEachModifier(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Ljava/util/function/BiConsumer;)V", at = @At("RETURN"))
	private static void sharedfate$shieldAttributes(ItemStack stack, EquipmentSlot slot,
			BiConsumer<Holder<Attribute>, AttributeModifier> consumer, CallbackInfo ci) {
		for (var entry : stack.getEnchantments().entrySet()) {
			Enchantment enchantment = entry.getKey().value();
			if (enchantment.matchingSlot(slot) || !ShieldEnchantments.allowsHand(enchantment, slot, stack, null)) continue;
			for (var effect : enchantment.getEffects(EnchantmentEffectComponents.ATTRIBUTES)) {
				consumer.accept(effect.attribute(), effect.getModifier(entry.getIntValue(), slot));
			}
		}
	}
	@WrapOperation(method = "runIterationOnItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/EquipmentSlot;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/enchantment/EnchantmentHelper$EnchantmentInSlotVisitor;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/Enchantment;matchingSlot(Lnet/minecraft/world/entity/EquipmentSlot;)Z"))
	private static boolean sharedfate$shieldHands(Enchantment enchantment, EquipmentSlot slot, Operation<Boolean> original,
			@Local(argsOnly = true) ItemStack stack, @Local(argsOnly = true) LivingEntity wearer) {
		return original.call(enchantment, slot) || ShieldEnchantments.allowsHand(enchantment, slot, stack, wearer);
	}
}
