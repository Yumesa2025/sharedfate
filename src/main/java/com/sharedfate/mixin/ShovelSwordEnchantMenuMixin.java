package com.sharedfate.mixin;

import com.sharedfate.enchant.ShovelSwordEnchanting;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「삽질의 대가」 팀이 마법부여대에 삽을 올리면 <b>검의 후보</b>에서 뽑는다.
 *
 * <p>26.3 의 {@code getEnchantmentList} 는 칸마다 씨앗을 새로 심고, 탁자 후보 태그
 * ({@code #in_enchanting_table})를 꺼내 {@code EnchantmentHelper.selectEnchantment(난수, 물건, 등급, 후보)}
 * 하나로 고른다. 그 호출의 <b>물건 인자만</b> 대역 검으로 바꾼다 — 씨앗·등급·책 처리는 바닐라
 * 그대로라, 결과는 같은 자리에 검을 올렸을 때와 똑같다. 슬롯 단서(clue)와 실제로 붙는 마법부여가
 * 같은 호출을 지나므로 둘이 어긋나지도 않는다.
 *
 * <p>{@code getEnchantmentList} 는 서버에서만 불린다({@code slotsChanged}·{@code clickMenuButton}
 * 의 {@code access.execute} 안). 클라이언트에서는 팀을 모르므로 판정도 거짓이다
 * ({@link ShovelSwordEnchanting#heldBy}).
 */
@Mixin(EnchantmentMenu.class)
public abstract class ShovelSwordEnchantMenuMixin {
	@Unique
	private Player sharedfate$shovelOwner;

	@Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
			at = @At("TAIL"))
	private void sharedfate$rememberOwner(int id, Inventory inventory, ContainerLevelAccess access, CallbackInfo ci) {
		sharedfate$shovelOwner = inventory.player;
	}

	@ModifyArg(
			method = "getEnchantmentList",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;selectEnchantment("
							+ "Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/item/ItemStack;ILjava/util/stream/Stream;)"
							+ "Ljava/util/List;"),
			index = 1)
	private ItemStack sharedfate$swordPool(ItemStack stack) {
		if (!ShovelSwordEnchanting.isShovel(stack)) {
			return stack;
		}
		return ShovelSwordEnchanting.poolStack(stack, ShovelSwordEnchanting.heldBy(sharedfate$shovelOwner));
	}
}
