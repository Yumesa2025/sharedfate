package com.sharedfate.inventory;

import net.minecraft.core.Holder;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 조합법 책의 <b>「눌러서 자동 채우기」</b>가 추가 27칸까지 쓰게 하는 셈.
 *
 * <p>여기 있는 것은 모두 <b>월드 없이 도는 함수</b>다. 실제로 끼워 넣는 자리는
 * {@link com.sharedfate.mixin.ExpandedPlaceRecipeMixin} 이고, 판정이 맞는지는
 * {@code ExpandedRecipeFillTest} 가 붙든다 — 이 환경에서는 클라이언트를 띄워
 * 눈으로 볼 수 없으므로 셈을 밖으로 빼내 시험으로 못박는 것이 유일한 확인 수단이다.
 *
 * <h2>바닐라가 추가 칸을 못 보는 자리는 셋이다</h2>
 * <p>{@code ServerPlaceRecipe} 는 {@code Inventory} 만 안다. 그래서 세 군데가 어긋난다.
 *
 * <ol>
 *   <li><b>꺼내기</b> — {@code moveItemToGrid} 가
 *       {@code Inventory.findSlotMatchingCraftingIngredient} 로 찾으므로 추가 칸의 재료를
 *       「없다」고 본다 → {@link #moveToGrid}</li>
 *   <li><b>되돌릴 자리 세기</b> — {@code getAmountOfFreeSlotsInInventory} 가 바닐라 36칸의
 *       빈칸만 센다 → {@link #freeOpenSlots}</li>
 *   <li><b>되돌려 놓기</b> — {@code clearGrid} 가 {@code Inventory.placeItemBackInInventory}
 *       를 부르고, 그것은 바닐라 36칸이 다하면 <b>바닥에 버린다</b> → {@link #putBack}</li>
 * </ol>
 *
 * <p>둘째와 셋째는 <b>반드시 함께</b> 고쳐야 한다. 세는 쪽만 넓히면 「자리가 있다」고 하고는
 * 넣을 때 바닥에 버리고, 넣는 쪽만 넓히면 {@code testClearGrid} 가 먼저 「자리가 없다」로
 * 막아 넓힌 것이 한 번도 쓰이지 않는다.
 *
 * <h2>되돌아갈 곳은 바닐라 36칸이 먼저다</h2>
 * <p>{@link #putBack} 은 <b>바닐라 36칸 → 추가 27칸 → 바닥</b> 순서다. 근거가 셋이다.
 *
 * <ul>
 *   <li>물건이 <b>들어오는</b> 길이 이미 그 순서다. {@code Inventory.add} 는 바닐라 36칸을
 *       먼저 채우고, 거기서 실패한 뒤에야
 *       {@code InventoryMixin.sharedfate$addToExtraItems} 가 추가 칸을 쓴다. 되돌리는
 *       길만 순서가 다르면 「쉬프트 클릭으로 올린 것이 조합법으로는 아래로 내려간다」가 된다.</li>
 *   <li>핫바와 손이 닿는 곳은 위 36칸이다. 되돌린 재료를 곧바로 다시 쓰는 것이 보통이므로
 *       가까운 쪽이 먼저여야 한다.</li>
 *   <li>{@code mainInventoryRows == 3} 이거나 팀이 없으면 이 길이 <b>통째로 꺼진다</b> —
 *       바닐라 호출 하나로 되돌아가므로 바닐라와 한 글자도 다르지 않다.</li>
 * </ul>
 *
 * <h2>⚠ 엔더 상자는 세지 않는다</h2>
 * <p>엔더 상자는 팀이 공유하지만({@code shareEnderChest}) 조합법은 「지금 들고 있는 것」으로
 * 판정해야 한다. 여기서는 {@link ExpandedInventoryContainer} 만 본다 —
 * {@code TeamEnderChestView} 는 한 번도 나오지 않는다. 바닐라 쪽도 마찬가지로
 * {@code RecipeBookComponent} 는 {@code Inventory} 와 조합칸만 센다.
 */
public final class ExpandedRecipeFill {
	/**
	 * 「그런 재료는 없다」. 바닐라 {@code ServerPlaceRecipe.ITEM_NOT_FOUND} 와 같은 값이어야
	 * 한다 — {@code moveItemToGrid} 를 부르는 쪽이 이 값으로 되돌아 나갈지 정한다.
	 */
	public static final int NOT_FOUND = -1;

	private ExpandedRecipeFill() {
	}

	/**
	 * 이 사람이 <b>지금 쓸 수 있는</b> 추가 칸 묶음. 없으면 {@code null}.
	 *
	 * <p>{@code null} 이 나오는 경우가 둘이다 — {@code mainInventoryRows} 가 3이라 추가 칸
	 * 자체가 없거나, 팀에 속하지 않아 칸이 꺼져 있는 경우다. 부르는 쪽은 {@code null} 이면
	 * <b>바닐라 그대로</b> 두어야 한다.
	 */
	@Nullable
	public static ExpandedInventoryContainer activeExtraFor(@Nullable Player player) {
		if (!ExpandedInventoryManager.enabled()) {
			return null;
		}
		ExpandedInventoryContainer extra = ExpandedInventoryManager.extraFor(player);
		return extra.active() ? extra : null;
	}

	/**
	 * 이 사람에게 열려 있는 추가 칸 수.
	 *
	 * <p>{@code player} 가 {@code null} 이어도 답한다 — 실제 놀이에서는 {@code Inventory.player}
	 * 가 비는 일이 없지만, 시험은 {@code new Inventory(null, ...)} 로 세운다. 그때
	 * {@code ExpandedInventoryManager.unlockedFor} 는 클라이언트와 같은 길로 답한다.
	 */
	public static int openSlotsFor(@Nullable Player player) {
		return Math.min(ExpandedInventoryManager.EXTRA_SIZE,
				Math.max(0, ExpandedInventoryManager.unlockedFor(player)));
	}

	/**
	 * 추가 칸에서 이 재료가 든 <b>첫 칸</b>. 없으면 {@link #NOT_FOUND}.
	 *
	 * <p>{@code Inventory.findSlotMatchingCraftingIngredient} 의 세 조건을 그대로 되풀이한다.
	 * 하나라도 빠지면 바닐라 칸과 추가 칸이 서로 다른 기준으로 골라져, 같은 재료인데 위에
	 * 있으면 되고 아래 있으면 안 되는 일이 생긴다.
	 *
	 * <ol>
	 *   <li>{@code stack.is(ingredient)} — 조합법이 요구하는 아이템인가</li>
	 *   <li>{@code Inventory.isUsableForCrafting} — 닳거나 마법이 걸리거나 이름을 붙인 것은
	 *       재료로 쓰지 않는다. 이것을 빼면 닳은 곡괭이가 재료로 잡혀 조합법이 흰색으로 떠
	 *       있는데 눌러도 안 채워진다</li>
	 *   <li>조합칸에 이미 뭔가 있으면 <b>성분까지 같은 것</b>만 그 위에 쌓는다</li>
	 * </ol>
	 *
	 * @param openSlots 열린 칸 수. 잠긴 칸은 화면 밖이라 꺼낼 수 없으므로 훑지 않는다
	 * @param gridStack 지금 그 조합칸에 들어 있는 것 (비어 있어도 된다)
	 */
	public static int findIngredient(ExpandedInventoryContainer extra, int openSlots,
			Holder<Item> ingredient, ItemStack gridStack) {
		int limit = Math.min(openSlots, extra.getContainerSize());
		for (int slot = 0; slot < limit; slot++) {
			ItemStack candidate = extra.getItem(slot);
			if (candidate.isEmpty() || !candidate.is(ingredient)
					|| !Inventory.isUsableForCrafting(candidate)) {
				continue;
			}
			if (gridStack.isEmpty()
					|| ItemStack.isSameItemSameComponents(gridStack, candidate)) {
				return slot;
			}
		}
		return NOT_FOUND;
	}

	/**
	 * 추가 칸에서 재료를 꺼내 조합칸에 넣는다. 바닐라
	 * {@code ServerPlaceRecipe.moveItemToGrid} 와 <b>같은 모양</b>으로 답한다.
	 *
	 * @param count 아직 더 넣어야 하는 개수
	 * @return 넣고 나서 <b>남은</b> 개수. 재료를 못 찾으면 {@link #NOT_FOUND} — 부르는 쪽은
	 *         그 값을 보고 되돌아 나간다
	 */
	public static int moveToGrid(@Nullable Player player, Slot gridSlot,
			Holder<Item> ingredient, int count) {
		ExpandedInventoryContainer extra = activeExtraFor(player);
		if (extra == null) {
			return NOT_FOUND;
		}
		ItemStack gridStack = gridSlot.getItem();
		int slot = findIngredient(extra, openSlotsFor(player), ingredient, gridStack);
		if (slot == NOT_FOUND) {
			return NOT_FOUND;
		}

		// 바닐라와 같이 「모자라면 통째로, 남으면 필요한 만큼만」 꺼낸다.
		ItemStack taken = count < extra.getItem(slot).getCount()
				? extra.removeItem(slot, count)
				: extra.removeItemNoUpdate(slot);
		int moved = taken.getCount();
		if (gridStack.isEmpty()) {
			gridSlot.set(taken);
		} else {
			gridStack.grow(moved);
		}
		// removeItemNoUpdate 는 바닐라와 마찬가지로 스스로 알리지 않는다. 조합법 책이 다시
		// 세는 조건이 setChanged 하나뿐이므로 여기서 한 번 확실히 알린다.
		extra.setChanged();
		return count - moved;
	}

	/**
	 * 되돌려 놓을 수 있는 <b>추가 칸의 빈칸</b> 수.
	 *
	 * <p>{@code ServerPlaceRecipe.getAmountOfFreeSlotsInInventory} 에 더한다. 그쪽은
	 * {@code testClearGrid} 가 「조합칸을 다 비울 수 있는가」를 가늠할 때 쓰는 예산이고,
	 * 그 예산이 모자라면 조합법을 눌러도 <b>아무 일도 일어나지 않는다.</b>
	 *
	 * <p>이미 물건이 든 칸에 <b>합칠</b> 여유는 세지 않는다. {@link #putBack} 은 합치기도
	 * 하므로 실제로는 여기서 센 것보다 더 들어간다 — 즉 이 셈은 <b>모자란 쪽으로만</b>
	 * 틀린다. 그 방향의 오차는 「될 것을 안 된다고 하는」 것이라 물건을 잃지 않는다.
	 * 반대로 넉넉하게 세면 {@code clearGrid} 가 넣을 곳을 못 찾아 바닥에 버린다.
	 */
	public static int freeOpenSlots(@Nullable Player player) {
		ExpandedInventoryContainer extra = activeExtraFor(player);
		if (extra == null) {
			return 0;
		}
		int limit = Math.min(openSlotsFor(player), extra.getContainerSize());
		int free = 0;
		for (int slot = 0; slot < limit; slot++) {
			if (extra.getItem(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	/**
	 * 조합칸에서 걷어낸 물건을 <b>바닐라 36칸 → 추가 27칸 → 바닥</b> 순서로 되돌린다.
	 *
	 * <h2>왜 바닐라를 한 번에 부르지 못하는가</h2>
	 * <p>{@code Inventory.placeItemBackInInventory} 는 자리가 다하는 순간 <b>남은 것을 통째로
	 * 바닥에 버리고 끝낸다.</b> 그래서 통째로 넘기면 추가 칸에 자리가 있어도 물건이 떨어진다.
	 * 여기서는 <b>버릴 일이 없는 만큼만 잘라서</b> 넘긴다 — 한 바퀴에 바닐라 한 칸 몫이다.
	 *
	 * <p>잘라 넘기는 양을 바닐라와 <b>같은 식</b>으로 잰다
	 * ({@code stack.getMaxStackSize() - getItem(slot).getCount()}). 이 식이 어긋나면 넘긴
	 * 조각이 그 칸에 안 들어가고 바닐라가 그것을 버린다.
	 *
	 * <p>마지막 줄에서 바닐라를 다시 부르는 것은 <b>버리게 하려고</b>다. 추가 칸까지 꽉 찼을
	 * 때 물건을 든 채로 돌아가면 {@code clearGrid} 가 조합칸을 비워 버려 물건이 사라진다.
	 * 버리는 것은 잃는 것이 아니다 — 바닥에서 주울 수 있다.
	 */
	public static void putBack(Inventory inventory, ItemStack stack, boolean sendPacket,
			Prediction prediction) {
		ExpandedInventoryContainer extra = activeExtraFor(inventory.player);
		if (extra == null) {
			// 추가 칸이 없다. 바닐라 그대로다.
			inventory.placeItemBackInInventory(stack, sendPacket, prediction);
			return;
		}

		while (!stack.isEmpty()) {
			int slot = inventory.getSlotWithRemainingSpace(stack);
			if (slot == -1) {
				slot = inventory.getFreeSlot();
			}
			if (slot == -1) {
				// 바닐라 36칸이 다했다. 여기서 멈추지 않으면 다음 호출이 바닥에 버린다.
				break;
			}
			int room = stack.getMaxStackSize() - inventory.getItem(slot).getCount();
			if (room <= 0) {
				break;
			}
			inventory.placeItemBackInInventory(
					stack.split(Math.min(room, stack.getCount())), sendPacket, prediction);
		}

		if (!stack.isEmpty()) {
			// 열린 칸까지만 받는다 — addStack 이 스스로 그 한도를 지킨다.
			extra.addStack(stack);
		}
		if (!stack.isEmpty()) {
			inventory.placeItemBackInInventory(stack, sendPacket, prediction);
		}
	}
}
