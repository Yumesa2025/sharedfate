package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedInventoryContainer;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedInventorySlot;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 상자·화로·제작대·인챈트 등 <b>바닐라 인벤토리를 끼워 넣는 모든 메뉴</b>에 추가 27칸을
 * 붙인다.
 *
 * <h2>어디에 붙이는가</h2>
 *
 * <p>메뉴 번호로는 <b>맨 뒤</b>에 붙는다 — 앞에 끼우면 바닐라가 하드코딩해 둔 슬롯
 * 번호가 전부 어긋난다. 하지만 <b>화면에서는 인벤토리 세 줄 바로 아래</b>에 놓고 핫바를
 * 그만큼 내린다. 그래서 사람 눈에는 바닐라 인벤토리가 여섯 줄로 늘어난 것으로 보인다.
 *
 * <p>추가 칸은 <b>창 안</b>에 있어야 한다. 창 밖에 두면 바닐라가
 * {@code hasClickedOutside} 로 「창 밖을 눌렀다」고 판정해 들고 있던 아이템을 바닥에
 * 버린다.
 *
 * <h2>{@code InventoryMenu} 만 빼는 이유</h2>
 *
 * <p>플레이어 인벤토리 화면은 {@code addStandardInventorySlots} 뒤에 <b>오프핸드 칸을 하나
 * 더</b> 붙인다. 여기서 끼워 넣으면 오프핸드가 45번이 아니게 되어 바닐라와 어긋난다.
 * 그래서 {@link ExpandedInventoryMenuMixin} 이 생성자 끝에서 따로 붙인다.
 */
@Mixin(AbstractContainerMenu.class)
public abstract class ExpandedStandardMenuMixin implements ExpandedMenuLayout {
	@Shadow
	@Final
	public net.minecraft.core.NonNullList<Slot> slots;

	@Shadow
	protected abstract Slot addSlot(Slot slot);

	@Unique
	private int sharedfate$playerStart = ExpandedMenuLayout.NONE;
	@Unique
	private int sharedfate$extraStart = ExpandedMenuLayout.NONE;
	@Unique
	private int sharedfate$inventoryTop = ExpandedMenuLayout.NONE;

	@Override
	public int sharedfate$playerSlotStart() {
		return sharedfate$playerStart;
	}

	@Override
	public int sharedfate$extraSlotStart() {
		return sharedfate$extraStart;
	}

	@Override
	public int sharedfate$inventoryTopY() {
		return sharedfate$inventoryTop;
	}

	@Override
	public void sharedfate$setExpandedLayout(
			int playerSlotStart, int extraSlotStart, int inventoryTopY) {
		sharedfate$playerStart = playerSlotStart;
		sharedfate$extraStart = extraSlotStart;
		sharedfate$inventoryTop = inventoryTopY;
	}

	@Inject(method = "addStandardInventorySlots", at = @At("HEAD"))
	private void sharedfate$rememberStandardInventoryStart(
			Container container, int x, int y, CallbackInfo ci) {
		if (ExpandedInventoryManager.enabled()
				&& container instanceof Inventory
				&& !((Object) this instanceof InventoryMenu)) {
			sharedfate$playerStart = slots.size();
			sharedfate$inventoryTop = y;
		}
	}

	@Inject(method = "addStandardInventorySlots", at = @At("RETURN"))
	private void sharedfate$appendExtraInventory(
			Container container, int x, int y, CallbackInfo ci) {
		if (!ExpandedInventoryManager.enabled()
				|| !(container instanceof Inventory inventory)
				|| (Object) this instanceof InventoryMenu
				|| sharedfate$extraStart >= 0) {
			return;
		}
		ExpandedInventoryContainer extra = ExpandedInventoryManager.extraFor(inventory.player);
		sharedfate$extraStart = slots.size();
		for (int extraIndex = 0; extraIndex < ExpandedInventoryManager.EXTRA_SIZE; extraIndex++) {
			int column = extraIndex % ExpandedInventoryManager.EXTRA_COLUMNS;
			int row = extraIndex / ExpandedInventoryManager.EXTRA_COLUMNS;
			addSlot(new ExpandedInventorySlot(extra, extraIndex,
					x + column * ExpandedInventoryManager.SLOT_PITCH,
					y + ExpandedInventoryManager.EXTRA_TOP_OFFSET
							+ row * ExpandedInventoryManager.SLOT_PITCH));
		}
		ExpandedInventoryManager.updateMenuLayout(
				(AbstractContainerMenu) (Object) this, extra.active(),
				ExpandedInventoryManager.unlockedFor(inventory.player));
	}

	/**
	 * 쉬프트 클릭이 <b>바닐라 27칸과 추가 27칸을 하나로</b> 보게 한다.
	 *
	 * <p>바닐라 메뉴들은 「플레이어 인벤토리 전체」를 {@code (시작, 시작+36)} 으로,
	 * 「윗줄만」을 {@code (시작, 시작+27)} 으로 부른다. 그 두 모양을 가로채 추가 27칸을
	 * 끼운 목록으로 바꿔 넣는다. 핫바만 지정한 {@code (시작+27, 시작+36)} 은 건드리지
	 * 않는다 — 핫바는 여전히 핫바다.
	 *
	 * <p>상자처럼 {@code (시작, slots.size())} 로 부르는 메뉴도 있다. 그 끝값은
	 * 추가 27칸까지 포함하므로 함께 알아본다. 이 경우 바닐라 그대로 두면 역방향 이동이
	 * <b>추가 칸부터</b> 채워, 상자에서 쉬프트 클릭한 물건이 핫바가 아니라 아래 칸으로
	 * 날아간다.
	 *
	 * <h2>출발이 추가 칸이면 핫바로만 보낸다</h2>
	 *
	 * <p>신호기와 양조대는 「어느 갈래에도 걸리지 않은 번호」를 마지막에
	 * {@code moveItemStackTo(플레이어 시작, 플레이어 시작 + 36)} 으로 흘려보낸다. 추가 칸
	 * 번호가 바로 그 경우다. 여기서 목록에 추가 27칸을 끼워 넣으면 <b>출발 칸이 목적지에
	 * 섞여</b> 자기 자신과 합쳐지고, 아이템이 두 배로 늘어난다.
	 *
	 * <p>그렇다고 바닐라 범위 그대로 두면 <b>위 세 줄부터</b> 채워져, 핫바가 비어 있어도 물건이
	 * 위로 올라간다. 그래서 그 호출만은 다른 화면들과 같이 <b>핫바 아홉 칸</b>으로 바꾼다 —
	 * 추가 칸이 목록에 없으니 복제도 생기지 않는다.
	 */
	@Inject(method = "moveItemStackTo", at = @At("HEAD"), cancellable = true)
	private void sharedfate$moveAcrossExpandedInventory(
			ItemStack stack, int start, int end, boolean reverse,
			CallbackInfoReturnable<Boolean> cir) {
		if (sharedfate$playerStart < 0 || sharedfate$extraStart < 0 || stack.isEmpty()
				|| start != sharedfate$playerStart) {
			return;
		}
		int extraSize = ExpandedInventoryManager.EXTRA_SIZE;
		int mainEnd = sharedfate$playerStart + extraSize;
		int hotbarEnd = mainEnd + ExpandedInventoryManager.EXTRA_COLUMNS;

		if (sharedfate$livesInExtraSlot(stack)) {
			if (end == hotbarEnd) {
				cir.setReturnValue(ExpandedInventoryMoves.move(
						(AbstractContainerMenu) (Object) this, stack,
						ExpandedInventoryMoves.hotbarOrder(sharedfate$playerStart), reverse));
			}
			return;
		}

		int[] order;
		if (end == hotbarEnd || end == sharedfate$extraStart
				|| end == sharedfate$extraStart + extraSize) {
			order = ExpandedInventoryMoves.order(
					sharedfate$playerStart, extraSize,
					sharedfate$extraStart, extraSize,
					mainEnd, ExpandedInventoryManager.EXTRA_COLUMNS);
		} else if (end == mainEnd) {
			order = ExpandedInventoryMoves.order(
					sharedfate$playerStart, extraSize,
					sharedfate$extraStart, extraSize);
		} else {
			return;
		}
		cir.setReturnValue(ExpandedInventoryMoves.move(
				(AbstractContainerMenu) (Object) this, stack, order, reverse));
	}

	/**
	 * 옮기려는 스택이 <b>추가 칸이 들고 있는 바로 그 객체</b>인지 본다.
	 *
	 * <p>{@code TeamState.extraItems} 의 원본이므로 개수를 견주지 않고 <b>객체가 같은지</b>로
	 * 본다. 같은 아이템이 다른 칸에도 있을 수 있기 때문이다.
	 */
	@Unique
	private boolean sharedfate$livesInExtraSlot(ItemStack stack) {
		if (sharedfate$extraStart + ExpandedInventoryManager.EXTRA_SIZE > slots.size()) {
			return false;
		}
		for (int offset = 0; offset < ExpandedInventoryManager.EXTRA_SIZE; offset++) {
			if (slots.get(sharedfate$extraStart + offset).getItem() == stack) {
				return true;
			}
		}
		return false;
	}
}
