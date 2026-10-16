package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedInventoryContainer;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedInventorySlot;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 플레이어 인벤토리 화면에 추가 27칸을 붙인다.
 *
 * <p>{@link ExpandedStandardMenuMixin} 이 이 메뉴만 빼는 이유는 오프핸드 칸 때문이다 —
 * 바닐라는 인벤토리 36칸을 붙인 <b>뒤에</b> 오프핸드를 45번으로 붙이므로, 그 사이에
 * 끼워 넣으면 오프핸드 번호가 어긋난다. 그래서 생성자 맨 끝에서 붙이고, 배치 정보만
 * 공통 통로에 알려 준다.
 */
@Mixin(InventoryMenu.class)
public abstract class ExpandedInventoryMenuMixin {
	/** 인벤토리 세 줄이 시작하는 메뉴 번호. 0~3 제작, 4 결과, 5~8 장비 다음이다. */
	private static final int PLAYER_SLOT_START = 9;
	/** 바닐라 인벤토리 첫 줄의 화면 y. */
	private static final int INVENTORY_TOP_Y = 84;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void sharedfate$appendExpandedSlots(
			Inventory inventory, boolean active, Player owner, CallbackInfo ci) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}

		ExpandedInventoryContainer extra = ExpandedInventoryManager.extraFor(owner);
		AbstractContainerMenuAccessor menu = (AbstractContainerMenuAccessor) this;
		for (int extraIndex = 0; extraIndex < ExpandedInventoryManager.EXTRA_SIZE; extraIndex++) {
			int column = extraIndex % ExpandedInventoryManager.EXTRA_COLUMNS;
			int row = extraIndex / ExpandedInventoryManager.EXTRA_COLUMNS;
			menu.sharedfate$invokeAddSlot(new ExpandedInventorySlot(extra, extraIndex,
					8 + column * ExpandedInventoryManager.SLOT_PITCH,
					INVENTORY_TOP_Y + ExpandedInventoryManager.EXTRA_TOP_OFFSET
							+ row * ExpandedInventoryManager.SLOT_PITCH));
		}
		((ExpandedMenuLayout) this).sharedfate$setExpandedLayout(
				PLAYER_SLOT_START,
				ExpandedInventoryManager.VANILLA_INVENTORY_MENU_SIZE,
				INVENTORY_TOP_Y);
		ExpandedInventoryManager.updateMenuLayout(
				(InventoryMenu) (Object) this, extra.active(),
				ExpandedInventoryManager.unlockedFor(owner));
	}

	/**
	 * 추가 칸에서 쉬프트 클릭했을 때의 목적지를 <b>바닐라 세 줄과 똑같이</b> 정한다.
	 *
	 * <p>바닐라 {@code quickMoveStack} 은 방어구·왼손을 먼저 보고, 그다음에야 「어느 줄에서
	 * 눌렀는가」로 핫바와 세 줄을 가른다. 추가 27칸은 그 판정에 아예 닿지 못하기 때문에
	 * 아래 줄에서는 갑옷이 입혀지지 않고, 핫바가 텅 비어 있어도 위로만 올라간다.
	 * 여기서 그 세 갈래를 그대로 되풀이한다.
	 *
	 * <ol>
	 *   <li>입을 수 있는 방어구이고 그 방어구 칸이 비어 있으면 <b>그 칸</b></li>
	 *   <li>왼손 물건이고 왼손 칸이 비어 있으면 <b>왼손 칸</b></li>
	 *   <li>아니면 <b>핫바</b> — 핫바가 다 받지 못하면 나머지는 제자리에 남는다</li>
	 * </ol>
	 *
	 * <p>장비 칸으로 보내다 막히면 바닐라와 같이 <b>아무 데도 보내지 않고</b> 끝낸다.
	 * 증강이 걸어 둔 착용 금지({@code equip_ban}·{@code offhand_lock})는 {@code mayPlace}
	 * 로 판정되므로 여기서 따로 볼 것이 없다.
	 */
	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveFromExtra(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		if (!ExpandedInventoryManager.enabled()
				|| index < ExpandedInventoryManager.VANILLA_INVENTORY_MENU_SIZE
				|| index >= ExpandedInventoryManager.EXPANDED_INVENTORY_MENU_SIZE) {
			return;
		}
		InventoryMenu menu = (InventoryMenu) (Object) this;
		Slot slot = menu.getSlot(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();
		if (!ExpandedInventoryMoves.move(
				menu, stack, sharedfate$destination(menu, player, stack), false)) {
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

	/** 추가 칸에서 나가는 아이템이 훑을 메뉴 번호 목록. */
	@Unique
	private int[] sharedfate$destination(InventoryMenu menu, Player player, ItemStack stack) {
		EquipmentSlot equipmentSlot = ExpandedInventoryMoves.equipmentSlotFor(player, stack);
		int armorSlot = ExpandedInventoryMoves.armorMenuSlot(equipmentSlot);
		if (armorSlot != ExpandedInventoryMoves.NO_SLOT && !menu.getSlot(armorSlot).hasItem()) {
			return new int[] {armorSlot};
		}
		if (equipmentSlot == EquipmentSlot.OFFHAND
				&& !menu.getSlot(InventoryMenu.SHIELD_SLOT).hasItem()) {
			return new int[] {InventoryMenu.SHIELD_SLOT};
		}
		return ExpandedInventoryMoves.hotbarOrder(PLAYER_SLOT_START);
	}
}
