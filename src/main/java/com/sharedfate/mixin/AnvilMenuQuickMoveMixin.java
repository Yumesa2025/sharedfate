package com.sharedfate.mixin;

import com.sharedfate.enchant.AnvilDiamondAccess;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 모루의 다이아몬드 칸을 <b>쉬프트 클릭·창 닫기</b> 양쪽에서 살린다.
 *
 * <h2>왜 {@code AnvilMenu} 가 아니라 {@code ItemCombinerMenu} 인가</h2>
 * <p>{@code quickMoveStack} 과 {@code removed} 는 26.2 바이트코드를 보면
 * {@code AnvilMenu.class} 자체에는 <b>없다</b> — 둘 다 {@code ItemCombinerMenu} 가 구현하고
 * {@code AnvilMenu} 는 재정의하지 않는다({@code javap -p AnvilMenu.class} 에 두 메서드가
 * 보이지 않는다). {@code AnvilMenu} 에 이 두 메서드를 걸면 <b>대상이 없어 아무 일도
 * 일어나지 않는다</b> — 「재정의되는 메서드에 믹스인 금지」의 반대 함정이다: 여기서는
 * 재정의하지 <b>않는</b> 클래스, 즉 진짜로 그 메서드를 갖고 있는 클래스에 걸어야 한다.
 *
 * <p>{@code ItemCombinerMenu} 는 {@code GrindstoneMenu} 도 상속한다. 그래서 모든 처리기가
 * <b>{@code instanceof AnvilMenu} 로 먼저 가려낸다</b> — 연마석은 다이아몬드 칸이 없으므로
 * 절대 건드리지 않는다. {@code BlockExperienceSourceMixin} 이 {@code Block} 한 곳에 걸고
 * 광석만 걸리게 하는 것과 같은 방식이다({@code popExperience} 의 좌표 대조 대신, 여기는
 * 타입 하나로 가른다).
 *
 * <p>서술자를 못박는 시험은 {@code AnvilMenuTargetTest} 에 있다.
 */
@Mixin(ItemCombinerMenu.class)
public abstract class AnvilMenuQuickMoveMixin {
	/** 바닐라 모루 메뉴에서 플레이어 인벤토리가 시작하는 번호. 재료 두 칸·결과 한 칸 다음이다. */
	private static final int PLAYER_SLOT_START = 3;

	@Shadow
	@Final
	protected ContainerLevelAccess access;

	/**
	 * 쉬프트 클릭이 다이아몬드 칸과 추가 27칸을 알아보게 한다.
	 *
	 * <p>바닐라 {@code ItemCombinerMenu.quickMoveStack} 은 재료 두 칸·결과 한 칸·플레이어
	 * 36칸만 안다. 그 밖의 번호(다이아몬드 칸·추가 27칸)는 어느 갈래에도 걸리지 않아
	 * 바닐라가 <b>아무 일도 하지 않고 빈 스택을 돌려준다</b>(옮기지 않았으므로 안전하지만
	 * 불편하다). 여기서 그 번호만 가로챈다.
	 */
	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveAnvilDiamonds(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		if (!((Object) this instanceof AnvilMenu anvil)) {
			return;
		}
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		int diamondSlot = ((AnvilDiamondAccess) anvil).sharedfate$diamondMenuSlot();
		int extraStart = ((ExpandedMenuLayout) menu).sharedfate$extraSlotStart();
		int extraSize = ExpandedInventoryManager.EXTRA_SIZE;
		boolean fromExtra = extraStart >= 0
				&& index >= extraStart && index < extraStart + extraSize;
		boolean fromDiamondSlot = diamondSlot != AnvilDiamondAccess.NO_SLOT && index == diamondSlot;
		boolean fromPlayer = index >= PLAYER_SLOT_START && index < PLAYER_SLOT_START + 36;
		if (!fromExtra && !fromDiamondSlot && !fromPlayer) {
			return;
		}

		Slot slot = menu.getSlot(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) {
			return;
		}
		ItemStack stack = slot.getItem();

		// 플레이어(또는 추가 칸)에서 온 다이아몬드는 다이아몬드 칸으로 보낸다.
		if ((fromExtra || fromPlayer) && stack.is(Items.DIAMOND)
				&& diamondSlot != AnvilDiamondAccess.NO_SLOT) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack, new int[] {diamondSlot});
			return;
		}
		// 추가 칸에서 온 것은 바닐라 세 줄처럼 핫바로만 보낸다. 위 세 줄을 섞으면 같은
		// 아이템이 덜 찬 칸으로 합쳐져 위로 올라간다.
		if (fromExtra) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack,
					ExpandedInventoryMoves.hotbarOrder(PLAYER_SLOT_START));
			return;
		}
		// 다이아몬드 칸에서 온 것은 플레이어 인벤토리로 돌려보낸다.
		if (fromDiamondSlot) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack,
					ExpandedInventoryMoves.order(
							PLAYER_SLOT_START, extraSize,
							PLAYER_SLOT_START + extraSize,
							ExpandedInventoryManager.EXTRA_COLUMNS));
		}
	}

	private void sharedfate$finishQuickMove(
			Player player, CallbackInfoReturnable<ItemStack> cir, AbstractContainerMenu menu,
			Slot slot, ItemStack stack, int[] order) {
		ItemStack original = stack.copy();
		if (!ExpandedInventoryMoves.move(menu, stack, order, false)) {
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

	/**
	 * 창을 닫을 때 다이아몬드를 돌려준다.
	 *
	 * <p>다이아몬드 그릇은 이 모드가 새로 만든 것이라 바닐라 {@code removed} 가 모른다.
	 * 비우지 않으면 <b>창을 닫는 순간 다이아몬드가 증발한다</b> — 인챈트 탁자와 같은 함정.
	 */
	@Inject(method = "removed", at = @At("TAIL"))
	private void sharedfate$returnAnvilDiamonds(Player player, CallbackInfo ci) {
		if (!((Object) this instanceof AnvilMenu anvil)) {
			return;
		}
		Container diamonds = ((AnvilDiamondAccess) anvil).sharedfate$diamondContainer();
		if (diamonds == null) {
			return;
		}
		access.execute((level, pos) -> ((AbstractContainerMenuInvoker) this)
				.sharedfate$invokeClearContainer(player, diamonds));
	}
}
