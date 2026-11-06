package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.ShovelSwordEnchanting;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「삽질의 대가」 팀의 모루에서 <b>삽을 검처럼</b> 다룬다 — 검 책은 삽에 붙고, 삽에만 붙는 책
 * (효율·섬세한 손길·행운)은 붙지 않는다. 판정은 {@link ShovelSwordEnchanting#anvilAccepts}.
 *
 * <p>26.3 {@code createResult} 안의 {@code Enchantment.canEnchant(왼쪽 물건)} 호출은 하나뿐이다
 * (바이트코드 549). 크리에이티브와 마법부여된 책은 바닐라가 그 뒤에서 따로 통과시킨다.
 *
 * <h2>클라이언트가 미리 계산한 값</h2>
 * <p>{@code createResult} 는 클라이언트에서도 돈다. 클라이언트는 팀을 모르므로 바닐라대로 계산하고,
 * 서버는 이 판정으로 계산한다. 결과 칸은 클라이언트가 클릭 패킷에 바뀐 칸을 실어 보내므로 서버가
 * 다르면 저절로 다시 보낸다. <b>비용(데이터 칸)은 실어 보내지 않으므로</b>, 이 판정이 걸린
 * 자리에서는 서버가 「마지막으로 보낸 값」을 지워 다음 동기화에 지금 값을 다시 보내게 한다.
 */
@Mixin(AnvilMenu.class)
public abstract class ShovelSwordAnvilMixin {
	@WrapOperation(
			method = "createResult()V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/enchantment/Enchantment;canEnchant(Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean sharedfate$shovelAsSword(Enchantment enchantment, ItemStack target, Operation<Boolean> original) {
		boolean vanilla = original.call(enchantment, target);
		if (!ShovelSwordEnchanting.isShovel(target)) {
			return vanilla;
		}
		return ShovelSwordEnchanting.anvilAccepts(enchantment, target, vanilla,
				ShovelSwordEnchanting.heldBy(sharedfate$owner()));
	}

	@Inject(method = "createResult()V", at = @At("RETURN"))
	private void sharedfate$resendCost(CallbackInfo ci) {
		Player owner = sharedfate$owner();
		ItemStack left = ((ItemCombinerMenu) (Object) this).getSlot(0).getItem();
		if (!ShovelSwordEnchanting.isShovel(left) || !ShovelSwordEnchanting.heldBy(owner)) {
			return;
		}
		IntList remote = ((AbstractContainerMenuAccessor) this).sharedfate$remoteDataSlots();
		for (int i = 0; i < remote.size(); i++) {
			remote.set(i, Integer.MIN_VALUE);
		}
	}

	@Unique
	private Player sharedfate$owner() {
		return ((ItemCombinerMenuAccessor) this).sharedfate$player();
	}
}
