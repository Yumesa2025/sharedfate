package com.sharedfate.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 바닐라 27칸과 추가 27칸을 <b>하나로 이어진 공간</b>처럼 다루는 쉬프트 클릭 이동이다.
 *
 * <p>바닐라 {@code moveItemStackTo} 는 「몇 번부터 몇 번까지」라는 <b>연속 구간 하나</b>만
 * 받는다. 그런데 추가 27칸은 메뉴 번호로 보면 핫바 <b>뒤</b>에 붙어 있고 화면으로 보면
 * 인벤토리 세 줄과 핫바 <b>사이</b>에 있다. 번호가 이어져 있지 않으므로 구간 하나로는
 * 표현할 수 없다.
 *
 * <p>구간을 나눠 바닐라를 두 번 부르면 <b>순서가 달라진다.</b> 바닐라는 「구간 전체에서
 * 합칠 곳을 먼저 다 찾고, 그다음에 빈칸을 채우는」 두 벌 훑기인데, 두 번 나눠 부르면
 * 앞 구간의 빈칸이 뒤 구간의 합칠 자리보다 먼저 쓰인다. 그러면 같은 아이템이 한 칸에
 * 모이지 않고 흩어져, 사람 눈에는 「위아래가 따로 논다」로 보인다.
 */
public final class ExpandedInventoryMoves {
	/** 「그런 칸은 없다」. */
	public static final int NO_SLOT = -1;

	private ExpandedInventoryMoves() {
	}

	/**
	 * {@code order} 에 적힌 순서대로 훑으며 아이템을 옮긴다.
	 *
	 * <h2>합치기에도 {@code mayPlace} 를 본다</h2>
	 *
	 * <p>바닐라 {@code moveItemStackTo} 는 <b>합치는 첫 벌에서 {@code mayPlace} 를 보지
	 * 않는다.</b> 바닐라는 결과 칸이 섞이지 않은 범위만 넘겨 주므로 그래도 된다. 여기서는
	 * 「메뉴가 제 몫으로 가진 칸 전부」처럼 결과 칸이 섞인 목록도 넘기므로, 화로 결과 칸에
	 * 놓인 철괴에 아래 줄의 철괴가 합쳐지는 일이 없도록 여기서도 본다. 덤으로 착용 금지
	 * 증강({@code equip_ban}·{@code offhand_lock})이 합치기로 새어 나가지 않는다.
	 *
	 * <h2>출발 칸이 목록에 있으면 건너뛴다</h2>
	 *
	 * <p>{@code stack} 은 출발 칸이 들고 있는 <b>바로 그 객체</b>다. 그 칸이 목적지 목록에
	 * 섞여 들어오면 자기 자신과 합쳐 개수가 두 배가 된다 — 아이템 복제다.
	 *
	 * @param order   화면에 보이는 순서대로 적은 메뉴 슬롯 번호
	 * @param reverse 참이면 목록을 <b>뒤에서부터</b> 훑는다 (바닐라의 역방향과 같다)
	 * @return 한 칸이라도 옮겼으면 참
	 */
	public static boolean move(
			AbstractContainerMenu menu, ItemStack stack, int[] order, boolean reverse) {
		boolean moved = false;

		// 1벌 — 이미 같은 아이템이 있는 칸에 합친다.
		if (stack.isStackable()) {
			for (int step = 0; step < order.length && !stack.isEmpty(); step++) {
				Slot slot = menu.getSlot(at(order, step, reverse));
				if (!slot.isActive()) {
					continue;
				}
				ItemStack existing = slot.getItem();
				if (existing == stack) {
					// 출발 칸이 목적지 목록에 섞여 들어온 경우다. 같은 객체를 자기 자신과
					// 합치면 개수가 두 배가 되어 아이템이 늘어난다.
					continue;
				}
				if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(stack, existing)
						|| !slot.mayPlace(stack)) {
					continue;
				}
				int total = existing.getCount() + stack.getCount();
				int maximum = slot.getMaxStackSize(existing);
				if (total <= maximum) {
					stack.setCount(0);
					existing.setCount(total);
					slot.setChanged();
					moved = true;
				} else if (existing.getCount() < maximum) {
					stack.shrink(maximum - existing.getCount());
					existing.setCount(maximum);
					slot.setChanged();
					moved = true;
				}
			}
		}

		// 2벌 — 남은 것을 빈칸 하나에 넣는다. 바닐라와 같이 첫 빈칸에서 멈춘다.
		if (!stack.isEmpty()) {
			for (int step = 0; step < order.length; step++) {
				Slot slot = menu.getSlot(at(order, step, reverse));
				if (!slot.isActive() || !slot.getItem().isEmpty() || !slot.mayPlace(stack)) {
					continue;
				}
				int maximum = slot.getMaxStackSize(stack);
				slot.setByPlayer(stack.split(Math.min(stack.getCount(), maximum)));
				slot.setChanged();
				moved = true;
				break;
			}
		}
		return moved;
	}

	/** 이어 붙인 구간들을 화면 순서대로 늘어놓은 번호 목록으로 만든다. */
	public static int[] order(int... startAndLengthPairs) {
		int total = 0;
		for (int pair = 1; pair < startAndLengthPairs.length; pair += 2) {
			total += startAndLengthPairs[pair];
		}
		int[] order = new int[total];
		int index = 0;
		for (int pair = 0; pair + 1 < startAndLengthPairs.length; pair += 2) {
			int start = startAndLengthPairs[pair];
			int length = startAndLengthPairs[pair + 1];
			for (int offset = 0; offset < length; offset++) {
				order[index++] = start + offset;
			}
		}
		return order;
	}

	/**
	 * 추가 칸에서 <b>플레이어 쪽으로 나갈 때</b>의 목적지 — <b>핫바 아홉 칸뿐</b>이다.
	 *
	 * <p>바닐라는 「인벤토리 세 줄에서 쉬프트 클릭하면 핫바로, 핫바에서 하면 세 줄로」
	 * 보낸다. 추가 세 줄은 화면에서 세 줄 <b>바로 아래</b>에 있는 또 다른 인벤토리 줄이므로
	 * 같은 규칙을 따라 핫바로 가야 한다.
	 *
	 * <h2>위 세 줄을 목록에 넣으면 안 된다</h2>
	 *
	 * <p>예전에는 「핫바 → 위 세 줄」을 한 목록으로 넘겼다. 그런데 {@link #move} 는 <b>목록
	 * 전체에서 합칠 곳을 먼저</b> 찾으므로, 위 세 줄에 같은 아이템이 덜 찬 채로 있으면 핫바가
	 * 텅 비어 있어도 그쪽으로 합쳐져 <b>위로 올라가 버렸다.</b> 바닐라의 세 줄도 핫바가 가득
	 * 차면 그 자리에 남는다 — 같은 인벤토리 안에서 옮겨 봐야 쓸모가 없기 때문이다. 추가 세 줄도
	 * 똑같이, 핫바가 받지 못한 몫은 제자리에 남긴다.
	 *
	 * @param playerSlotStart 인벤토리 세 줄이 시작하는 메뉴 번호
	 */
	public static int[] hotbarOrder(int playerSlotStart) {
		int hotbarStart = playerSlotStart + ExpandedInventoryManager.EXTRA_SIZE;
		return order(hotbarStart, ExpandedInventoryManager.EXTRA_COLUMNS);
	}

	/**
	 * 아이템이 들어갈 <b>장비 칸</b>을 바닐라와 똑같이 고른다.
	 *
	 * <p>{@code InventoryMenu.quickMoveStack} 은 {@code getEquipmentSlotForItem} 이 돌려준
	 * 칸을 보고 방어구·왼손으로 곧바로 보낸다.
	 *
	 * <p>{@code equippable} 성분이 없는 아이템은 바닐라도 {@code MAINHAND} 를 돌려주므로
	 * 플레이어를 건드리지 않고 먼저 끝낸다.
	 */
	public static EquipmentSlot equipmentSlotFor(Player player, ItemStack stack) {
		if (player == null || stack.get(DataComponents.EQUIPPABLE) == null) {
			return EquipmentSlot.MAINHAND;
		}
		return player.getEquipmentSlotForItem(stack);
	}

	/**
	 * 방어구 칸의 <b>메뉴 번호</b>. 방어구가 아니면 {@link #NO_SLOT}.
	 *
	 * <p>바닐라가 {@code 8 - getIndex()} 로 적어 둔 계산과 같다 — 발 0, 다리 1, 몸 2,
	 * 머리 3 이 각각 8·7·6·5번이다. 말 갑옷({@code BODY})은 번호가 발과 겹치므로
	 * <b>종류를 먼저</b> 본다.
	 */
	public static int armorMenuSlot(EquipmentSlot equipmentSlot) {
		if (equipmentSlot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
			return NO_SLOT;
		}
		return InventoryMenu.ARMOR_SLOT_END - 1 - equipmentSlot.getIndex();
	}

	private static int at(int[] order, int step, boolean reverse) {
		return order[reverse ? order.length - 1 - step : step];
	}
}
