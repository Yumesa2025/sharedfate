package com.sharedfate.inventory;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * <b>어느 화면에서든</b> 추가 27칸에서 쉬프트 클릭이 먹히게 하는 마지막 그물이다.
 *
 * <p>바닐라 {@code quickMoveStack} 은 메뉴마다 제 슬롯 번호를 <b>통째로 손으로 적어</b>
 * 두었다. 추가 27칸은 메뉴 번호로 그 뒤에 붙으므로 어느 갈래에도 걸리지 않는다.
 * 모루·연마석·직조기·지도 제작대·석재 절단기·대장장이 작업대처럼 「갈래에 걸리지 않으면
 * 아무 일도 하지 않는」 화면이 열 개가 넘는다.
 *
 * <p>뒤처리를 하는 자리는 {@code AbstractContainerMenu.doClick} 의 {@code QUICK_MOVE}
 * 갈래다.
 *
 * <p>26.2 바이트코드로 보면 바닐라 {@code quickMoveStack} 의 반환값은 <b>옮겼는지와
 * 일치하지 않는다.</b> {@code AbstractMountInventoryMenu.quickMoveStack} 은
 * {@code moveItemStackTo} 가 <b>참</b>을 돌려준 갈래에서도 {@code ItemStack.EMPTY} 를
 * 돌려준다. 반대로 {@code ChestMenu} 는 훑기가 도중에 막혀도 <b>원본 사본</b>을
 * 돌려준다. 그래서 <b>출발 칸의 내용을 호출 앞뒤로 견주어</b> 판정한다 — 개수가
 * 그대로이고 같은 아이템이면 화면은 아무것도 하지 않은 것이다.
 *
 * <h2>무한 반복을 만들지 않기</h2>
 *
 * <p>{@code doClick} 의 {@code QUICK_MOVE} 갈래는 아래 모양이다.
 *
 * <pre>{@code
 * ItemStack moved = this.quickMoveStack(player, slotId);
 * while (!moved.isEmpty() && ItemStack.isSameItem(slot.getItem(), moved)) {
 *     moved = this.quickMoveStack(player, slotId);
 * }
 * }</pre>
 *
 * <p>「아무것도 안 옮겼는데 비어 있지 않은 스택」을 돌려주면 <b>서버가 그 자리에서
 * 멈춘다.</b> 그래서 바닐라와 똑같은 약속을 지킨다 — <b>실제로 개수가 줄었을
 * 때만</b> 원본 사본을 돌려주고, 아니면 빈 스택을 돌려준다. 개수는 매번 반드시 줄어들므로
 * 반복은 유한하다.
 */
public final class ExpandedQuickMoveFallback {
	/**
	 * 바닐라 {@code quickMoveStack} 을 부르는 통로.
	 *
	 * <p>Mixin 은 {@code Operation} 을, 시험은 {@code menu::quickMoveStack} 을 넘긴다.
	 */
	@FunctionalInterface
	public interface VanillaQuickMove {
		ItemStack call(Player player, int index);
	}

	private ExpandedQuickMoveFallback() {
	}

	/**
	 * 바닐라를 먼저 부르고, 화면이 아무것도 하지 않았을 때만 대신 옮긴다.
	 *
	 * @param index 쉬프트 클릭한 메뉴 슬롯 번호
	 * @return {@code doClick} 이 기대하는 값 — 옮긴 것이 있으면 원본 사본, 아니면 빈 스택
	 */
	public static ItemStack quickMove(
			AbstractContainerMenu menu, Player player, int index, VanillaQuickMove vanilla) {
		if (!fromExtraSlot(menu, index)) {
			return vanilla.call(player, index);
		}

		Slot source = menu.getSlot(index);
		ItemStack before = source.getItem().copy();
		ItemStack vanillaResult = vanilla.call(player, index);
		if (before.isEmpty() || screenMoved(before, source.getItem())) {
			return vanillaResult;
		}
		return moveOut(menu, player, index);
	}

	/**
	 * 출발 칸이 <b>추가 27칸</b>인지 본다.
	 *
	 * <p>확장이 꺼져 있거나 팀이 없으면 여기서 곧바로 거짓이 된다. 크리에이티브 목록처럼
	 * 추가 칸을 붙이지 않은 메뉴도 마찬가지다.
	 */
	public static boolean fromExtraSlot(AbstractContainerMenu menu, int index) {
		if (!ExpandedInventoryManager.enabled() || !(menu instanceof ExpandedMenuLayout layout)) {
			return false;
		}
		int extraStart = layout.sharedfate$extraSlotStart();
		int playerStart = layout.sharedfate$playerSlotStart();
		if (extraStart < 0 || playerStart < 0
				|| extraStart + ExpandedInventoryManager.EXTRA_SIZE > menu.slots.size()
				|| index < extraStart
				|| index >= extraStart + ExpandedInventoryManager.EXTRA_SIZE) {
			return false;
		}
		return menu.getSlot(index) instanceof ExpandedInventorySlot;
	}

	/**
	 * 화면이 출발 칸을 건드렸는지 본다.
	 *
	 * <p>개수가 줄었거나 아이템이 바뀌었으면 화면이 무언가 한 것이다. 그 경우 나서지
	 * 않는다 — 그래야 이미 고쳐 둔 화면들과 <b>이중 이동</b>이 생기지 않는다.
	 */
	public static boolean screenMoved(ItemStack before, ItemStack after) {
		return after.getCount() != before.getCount()
				|| !ItemStack.isSameItemSameComponents(before, after);
	}

	/**
	 * 추가 칸의 아이템을 <b>핫바로</b> 내보낸다.
	 *
	 * <p>바닐라 메뉴는 제 몫의 칸을 먼저 붙이고 <b>그다음에</b>
	 * {@code addStandardInventorySlots} 로 플레이어 36칸을 붙인다. 그 시작 번호는
	 * {@link ExpandedMenuLayout#sharedfate$playerSlotStart()} 가 알고 있으므로, 세 줄 27칸과
	 * 핫바 9칸이 계산으로 나온다.
	 *
	 * <p>바닐라가 인벤토리 세 줄에서 핫바로 보내듯 <b>핫바만</b> 채운다. 추가 세 줄은
	 * 화면에서 세 줄 바로 아래에 붙은 또 하나의 인벤토리 줄이므로 같은 규칙을 따른다
	 * ({@link ExpandedInventoryMoves#hotbarOrder} 에 까닭이 있다).
	 *
	 * <h2>화면 제 칸(재료 칸·모루 왼쪽 칸 등)은 여기서 보지 않는다</h2>
	 *
	 * <p>「{@code 0} 부터 {@code playerSlotStart} 앞까지」를 먼저 훑으면 화로·제작대뿐 아니라
	 * <b>플레이어 인벤토리 화면의 2×2 제작 칸</b>까지 목적지가 된다. 바닐라는 그 칸으로
	 * 쉬프트 클릭을 보내지 않고, 그 칸에 남은 물건은 화면을 닫을 때 쏟아진다. 화로와
	 * 제작대는 각자의 Mixin 이 이미 재료 칸을 먼저 보므로, 공통 자리는 <b>모두에게 안전한
	 * 몫</b>만 맡는다.
	 */
	public static ItemStack moveOut(AbstractContainerMenu menu, Player player, int index) {
		Slot slot = menu.getSlot(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) {
			return ItemStack.EMPTY;
		}
		int playerStart = ((ExpandedMenuLayout) menu).sharedfate$playerSlotStart();
		ItemStack stack = slot.getItem();
		ItemStack original = stack.copy();

		if (!ExpandedInventoryMoves.move(
				menu, stack, ExpandedInventoryMoves.hotbarOrder(playerStart), false)) {
			return ItemStack.EMPTY;
		}

		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY, original);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == original.getCount()) {
			// 개수가 그대로면 반복문이 끝나지 않는다. 반드시 빈 스택을 돌려준다.
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		return original;
	}
}
