package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.ShovelSwordEnchanting;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 「삽질의 대가」 팀의 삽이 <b>휩쓸기 날</b>을 달았으면 검처럼 휩쓴다.
 *
 * <p>26.3 {@code Player.isSweepAttack(ZZZ)Z} 는 땅·속도 조건을 본 뒤 마지막에
 * {@code getItemInHand(MAIN_HAND).is(#minecraft:swords)} 하나로 정한다. 그 비교만 감싼다.
 * 휩쓸기 날의 몫({@code sweeping_damage_ratio})은 손 슬롯 속성이라 삽에서도 이미 붙어 있다 —
 * 휩쓸기 자체가 일어나지 않을 뿐이었다. 휩쓸기 날이 없는 삽은 바닐라처럼 휩쓸지 않는다.
 */
@Mixin(Player.class)
public abstract class ShovelSwordSweepMixin {
	@WrapOperation(
			method = "isSweepAttack(ZZZ)Z",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/ItemStack;is(Lnet/minecraft/tags/TagKey;)Z"))
	private boolean sharedfate$shovelSweeps(ItemStack stack, TagKey<Item> tag, Operation<Boolean> original) {
		if (original.call(stack, tag)) {
			return true;
		}
		return ShovelSwordEnchanting.isShovel(stack)
				&& ShovelSwordEnchanting.sweeps(stack, ShovelSwordEnchanting.heldBy((Player) (Object) this));
	}
}
