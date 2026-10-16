package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 제작대의 추가 칸에서 쉬프트 클릭했을 때만 손본다.
 *
 * <p>칸을 붙이고 자리를 잡는 일은 {@link ExpandedStandardMenuMixin} 이 다른 메뉴와 똑같이
 * 한다. 여기서 따로 다루는 것은 <b>추가 칸이 출발지일 때</b>뿐이다 — 바닐라는 그
 * 번호를 모르므로 「플레이어 인벤토리로 보내라」로 처리하는데, 그 범위에 추가 칸이 다시
 * 들어가 제자리걸음을 한다.
 */
@Mixin(CraftingMenu.class)
public abstract class ExpandedCraftingMenuMixin {
	private static final int CRAFT_INPUT_START = 1;
	private static final int CRAFT_INPUT_SIZE = 9;
	private static final int PLAYER_SLOT_START = 10;

	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveFromExtra(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}
		CraftingMenu menu = (CraftingMenu) (Object) this;
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
				ExpandedInventoryMoves.order(CRAFT_INPUT_START, CRAFT_INPUT_SIZE), false);
		if (!moved) {
			// 바닐라도 제작대 화면에서 「인벤토리 세 줄 → 핫바」로 보낸다. 추가 세 줄은
			// 화면에서 세 줄 바로 아래에 있는 인벤토리 줄이므로 같은 쪽으로 가야 한다.
			moved = ExpandedInventoryMoves.move(menu, stack,
					ExpandedInventoryMoves.hotbarOrder(PLAYER_SLOT_START), false);
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
		if (stack.getCount() == original.getCount()) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		slot.onTake(player, stack);
		cir.setReturnValue(original);
	}
}
