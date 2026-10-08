package com.sharedfate.inventory;

import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.SharedItemList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.StackedContentsCompatible;
import net.minecraft.world.item.ItemStack;
import java.util.function.Predicate;

public final class ExpandedInventoryContainer implements Container, StackedContentsCompatible {
	private final Player player;
	private SharedItemList local = SharedItemList.ofSize(ExpandedInventoryManager.EXTRA_SIZE);
	private boolean clientActive;

	/** 이 칸 묶음의 주인. 잠긴 칸을 가릴 때 그 사람의 팀을 봐야 한다. */
	public Player owner() {
		return player;
	}

	ExpandedInventoryContainer(Player player) {
		this.player = player;
	}

	private SharedItemList backing() {
		return ExpandedInventoryManager.backingFor(player, local);
	}

	@Override
	public ItemStack getItem(int slot) {
		return slot >= 0 && slot < backing().size() ? backing().get(slot) : ItemStack.EMPTY;
	}

	@Override
	public ItemStack removeItem(int slot, int amount) {
		ItemStack removed = ContainerHelper.removeItem(backing(), slot, amount);
		if (!removed.isEmpty()) {
			setChanged();
		}
		return removed;
	}

	@Override
	public ItemStack removeItemNoUpdate(int slot) {
		if (slot < 0 || slot >= backing().size()) {
			return ItemStack.EMPTY;
		}
		ItemStack stack = backing().get(slot);
		if (stack.isEmpty()) {
			return ItemStack.EMPTY;
		}
		backing().set(slot, ItemStack.EMPTY);
		return stack;
	}

	@Override
	public void setItem(int slot, ItemStack stack) {
		if (slot < 0 || slot >= backing().size()) {
			return;
		}
		backing().set(slot, stack);
		stack.limitSize(getMaxStackSize(stack));
		setChanged();
	}

	/**
	 * 바뀐 것을 <b>주인의 바닐라 인벤토리에도 알린다.</b>
	 *
	 * <p>여기 담긴 물건은 {@code TeamState.extraItems}(클라이언트에서는 그 사본)라 따로 저장할
	 * 것이 없다. 그래서 오래도록 빈 메서드였다.
	 *
	 * <p>그런데 조합법 책이 「만들 수 있는가」를 <b>다시</b> 세는 조건은
	 * {@code Inventory.getTimesChanged()} 가 달라졌는지 하나뿐이다
	 * ({@code RecipeBookComponent.tick}). 추가 칸만 바뀐 경우에는 그 수가 오르지 않아
	 * <b>회색·흰색이 갱신되지 않는다</b> — 추가 칸 안에서만 물건을 옮겼을 때가 그 경우다.
	 * 바닐라 칸이 함께 바뀌는 흔한 길에서는 그쪽이 올려 주어 드러나지 않았다.
	 *
	 * <p>{@code Inventory.setChanged()} 는 {@code timesChanged++} 한 줄뿐이고, 26.3 에서 그
	 * 값을 읽는 곳은 {@code RecipeBookComponent} 밖에 없다. 그래서 여기서 올려도 다른 데로
	 * 새지 않는다.
	 */
	@Override
	public void setChanged() {
		// 시험은 player 없이 이 칸만 세운다. 실제 놀이에서는 둘 다 비지 않는다.
		if (player == null || player.getInventory() == null) {
			return;
		}
		player.getInventory().setChanged();
	}

	@Override
	public int getContainerSize() {
		return ExpandedInventoryManager.EXTRA_SIZE;
	}

	@Override
	public boolean isEmpty() {
		return backing().stream().allMatch(ItemStack::isEmpty);
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public void clearContent() {
		backing().clear();
		setChanged();
	}

	/**
	 * 조합법 책과 자동 채우기가 「무엇을 얼마나 가졌는가」를 셀 때 지나는 자리.
	 *
	 * <h2>열린 칸까지만 센다</h2>
	 * <p>잠긴 칸은 화면 밖({@link ExpandedInventoryManager#HIDDEN_Y})이라 꺼낼 수 없고,
	 * 꺼내는 쪽({@link ExpandedRecipeFill#findIngredient})도 열린 칸까지만 본다. 세는 범위가
	 * 더 넓으면 <b>조합법이 흰색으로 떠 있는데 눌러도 안 채워진다.</b>
	 *
	 * <p>잠긴 칸의 물건을 못 쓰게 되는 것이 아니다 —
	 * {@code PerkInventorySlots.unlockedFor} 가 「물건이 든 칸까지는 반드시 연다」라서 그 칸은
	 * 이미 열린 것으로 세어진다.
	 *
	 * <h2>{@code accountSimpleStack} 이어야 한다</h2>
	 * <p>바닐라 {@code Inventory.fillStackedContents} 가 쓰는 것이 이쪽이다
	 * ({@code accountStack} 과 달리 {@code Inventory.isUsableForCrafting} 을 먼저 본다).
	 * {@code accountStack} 으로 세면 <b>닳은 곡괭이·마법이 걸린 검·이름을 붙인 물건</b>까지
	 * 재료로 잡히는데, 정작 꺼내는 쪽은 바닐라와 같이 그것들을 거른다. 그러면 조합법이
	 * 만들 수 있는 것으로 떠 있다가 눌러도 채워지지 않는다.
	 */
	@Override
	public void fillStackedContents(StackedItemContents contents) {
		int open = openSlots();
		for (int slot = 0; slot < open; slot++) {
			contents.accountSimpleStack(getItem(slot));
		}
	}

	public NonNullList<ItemStack> getItems() {
		return backing();
	}

	public boolean active() {
		if (player instanceof ServerPlayer) {
			return TeamLookup.serverStateOf(player) != null;
		}
		return clientActive;
	}

	public void setClientActive(boolean active) {
		clientActive = active;
	}

	/**
	 * 지금 물건을 <b>받을 수 있는</b> 칸의 개수.
	 *
	 * <p>칸은 언제나 {@link ExpandedInventoryManager#EXTRA_SIZE} 개가 만들어져 있고 팀이 연
	 * 만큼만 화면에 보인다. 잠긴 칸은 화면 밖({@link ExpandedInventoryManager#HIDDEN_Y})에
	 * 있으므로 <b>물건이 들어가면 사라진 것처럼 보인다.</b>
	 *
	 * <h2>{@code Slot.mayPlace} 만으로는 모자란다</h2>
	 * <p>{@link ExpandedInventorySlot#mayPlace} 가 막는 것은 창에서 손으로 옮기는 길뿐이다. 바닥의
	 * 물건을 <b>줍는</b> 길은 {@code Slot} 을 아예 지나지 않고
	 * {@code Inventory.add} → {@link #addStack} 으로 곧장 들어온다. 그래서 넣는 쪽 두 곳이
	 * 스스로 이 값을 봐야 한다.
	 *
	 * <p>새면 두 가지가 한꺼번에 일어난다 — 주운 물건이 안 보이는 칸으로 들어가고, 다음
	 * 접속에서 {@code PerkInventorySlots.occupiedFloor} 가 그 칸을 찾아 <b>짐꾼도 없는 팀에
	 * 27칸을 열어 준다.</b>
	 *
	 * <p>반대로 이미 물건이 든 잠긴 칸은 그 규칙이 <b>열어 두므로</b> 여기서도 열린 것으로
	 * 나온다. 「환골탈태」로 짐꾼을 잃어도 그 칸의 물건을 꺼내고 합치는 데는 문제가 없다.
	 */
	private int openSlots() {
		// unlockedFor 는 서버면 팀 상태에서 곧바로 세고, 아니면 서버가 보내 준 값을 쓴다.
		return Math.min(getContainerSize(), ExpandedInventoryManager.unlockedFor(player));
	}

	public boolean addStack(ItemStack stack) {
		if (!active() || stack.isEmpty()) {
			return false;
		}
		int before = stack.getCount();
		mergeExisting(stack);
		insertIntoEmptySlots(stack);
		if (stack.getCount() != before) {
			setChanged();
			return true;
		}
		return false;
	}

	public boolean mergeExisting(ItemStack stack) {
		if (!active() || stack.isEmpty()) {
			return false;
		}
		int before = stack.getCount();
		int open = openSlots();
		for (int slot = 0; slot < open && !stack.isEmpty(); slot++) {
			ItemStack existing = getItem(slot);
			if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) {
				continue;
			}
			int moved = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
			if (moved > 0) {
				existing.grow(moved);
				stack.shrink(moved);
			}
		}
		if (stack.getCount() != before) {
			setChanged();
			return true;
		}
		return false;
	}

	private void insertIntoEmptySlots(ItemStack stack) {
		int open = openSlots();
		for (int slot = 0; slot < open && !stack.isEmpty(); slot++) {
			if (getItem(slot).isEmpty()) {
				ItemStack inserted = stack.copyAndClear();
				inserted.setPopTime(5);
				setItem(slot, inserted);
			}
		}
	}

	public int clearOrCountMatchingItems(Predicate<ItemStack> predicate, int maximum, boolean countOnly) {
		return ContainerHelper.clearOrCountMatchingItems(this, predicate, maximum, countOnly);
	}

	public void resetLocal() {
		local = SharedItemList.ofSize(ExpandedInventoryManager.EXTRA_SIZE);
	}
}
