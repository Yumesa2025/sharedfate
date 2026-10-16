package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 화로의 추가 칸에서 쉬프트 클릭했을 때만 손본다. 바닐라는 그 번호를 아예 모르므로
 * 아무 일도 일어나지 않는다.
 */
@Mixin(AbstractFurnaceMenu.class)
public abstract class ExpandedFurnaceMenuMixin {
	/** 화로 자체의 칸 수 — 재료·연료·결과. */
	private static final int FURNACE_SLOTS = 3;

	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveFromExtra(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		int extraStart = ((ExpandedMenuLayout) menu).sharedfate$extraSlotStart();
		if (extraStart < 0 || index < extraStart
				|| index >= extraStart + ExpandedInventoryManager.EXTRA_SIZE) {
			return;
		}
		Slot slot = menu.getSlot(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		boolean moved = ExpandedInventoryMoves.move(menu, stack,
				ExpandedInventoryMoves.order(0, FURNACE_SLOTS), false);
		if (!moved) {
			// 바닐라도 화로 화면에서 「인벤토리 세 줄 → 핫바」로 보낸다. 추가 세 줄은
			// 화면에서 세 줄 바로 아래에 있는 인벤토리 줄이므로 같은 쪽으로 가야 한다.
			moved = ExpandedInventoryMoves.move(menu, stack,
					ExpandedInventoryMoves.hotbarOrder(FURNACE_SLOTS), false);
		}
		if (!moved) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY, original);
		} else {
			slot.setChanged();
		}
		slot.onTake(player, stack);
		cir.setReturnValue(original);
	}
}
