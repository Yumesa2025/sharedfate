package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 주민 거래가 <b>추가 27칸의 재료도</b> 값으로 인정하게 한다.
 *
 * <p>거래를 고르면 바닐라 {@code MerchantMenu.moveFromInventoryToPaymentSlot} 이 값 칸을
 * 채워 준다. 그 메서드는 훑을 범위를 <b>{@code 3}부터 {@code 39}까지</b>로 못박아 두었고,
 * 그 39는 「플레이어 인벤토리 36칸이 끝나는 자리」라는 뜻이다. 추가 27칸은 메뉴 번호로
 * 그 <b>뒤</b>에 붙으므로 이 범위 밖이라, 아래 줄에 에메랄드가 가득 있어도 값이 채워지지
 * 않는다.
 *
 * <p>{@code MerchantMenu} 는 다른 칸을 다 붙인 뒤 <b>맨 마지막</b>에
 * {@code addStandardInventorySlots} 를 부르고, 추가 27칸은 그 바로 뒤에 붙는다. 그래서
 * 끝값만 뒤로 밀면 {@code 3..38}(바닐라 36칸)과 {@code 39..65}(추가 27칸)가 <b>끊김 없이
 * 한 구간</b>이 된다. 범위 안에 남의 칸이 섞이지 않는다는 것을 값을 바꾸기 전에 확인한다.
 *
 * <h2>아이템이 늘거나 줄지 않는 이유</h2>
 *
 * <p>바닐라는 훑다가 찾은 칸의 {@code ItemStack} 을 <b>그 자리에서</b> {@code shrink} 하고
 * 줄어든 만큼만 값 칸에 넣는다. 추가 칸의 {@code ItemStack} 은 팀 공유 목록
 * {@code extraItems} 에 들어 있는 <b>바로 그 객체</b>이므로, 옮겨지는 개수가 한 번만
 * 계산되고 사본이 생기지 않는다. 훑는 <b>끝값</b>만 바꾸고 옮기는 방법에는
 * 손대지 않는다.
 */
@Mixin(MerchantMenu.class)
public abstract class ExpandedMerchantMenuMixin {
	/**
	 * 바닐라가 못박아 둔 끝값. {@code USE_ROW_SLOT_END} 이며 「거래 칸 3 + 인벤토리 36」이다.
	 *
	 * <p>이 숫자가 바뀌면 {@code ExpandedInventoryMenuTest} 가 먼저 알려 준다.
	 */
	private static final int VANILLA_INVENTORY_END = 39;

	/**
	 * 값 칸을 채울 때 훑을 <b>끝 번호</b>를 추가 27칸 뒤까지 늘린다.
	 *
	 * <p>추가 칸이 바닐라 36칸 바로 뒤에 붙어 있을 때만 늘린다. 아니면 바닐라 값을
	 * 그대로 돌려주어 <b>아무 일도 일어나지 않는다.</b>
	 */
	@ModifyConstant(
			method = "moveFromInventoryToPaymentSlot",
			constant = @Constant(intValue = VANILLA_INVENTORY_END)
	)
	private int sharedfate$alsoScanExtraSlots(int vanillaEnd) {
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		int extraStart = ((ExpandedMenuLayout) menu).sharedfate$extraSlotStart();
		int extraEnd = extraStart + ExpandedInventoryManager.EXTRA_SIZE;
		if (!ExpandedInventoryManager.enabled()
				|| extraStart != vanillaEnd
				|| extraEnd > menu.slots.size()) {
			return vanillaEnd;
		}
		return extraEnd;
	}

	/**
	 * 추가 칸에서 쉬프트 클릭했을 때만 손본다.
	 *
	 * <p>바닐라는 그 번호를 아예 모르므로 아무 일도 일어나지 않는다. 바닐라가 인벤토리
	 * 세 줄에서 핫바로 보내는 것과 같이 <b>핫바로</b> 보낸다. 값 칸은 바닐라도
	 * 쉬프트 클릭으로 채우지 않으므로 여기서도 건드리지 않는다.
	 */
	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveFromExtra(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		ExpandedMenuLayout layout = (ExpandedMenuLayout) menu;
		int extraStart = layout.sharedfate$extraSlotStart();
		int playerStart = layout.sharedfate$playerSlotStart();
		if (extraStart < 0 || playerStart < 0 || index < extraStart
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
		if (!ExpandedInventoryMoves.move(
				menu, stack, ExpandedInventoryMoves.hotbarOrder(playerStart), false)) {
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
