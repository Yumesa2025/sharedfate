package com.sharedfate.mixin;

import com.sharedfate.inventory.ExpandedRecipeFill;
import net.minecraft.core.Holder;
import net.minecraft.recipebook.ServerPlaceRecipe;
import net.minecraft.util.Prediction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 조합법 책의 <b>「눌러서 자동 채우기」</b>가 추가 27칸의 재료도 쓰게 한다.
 *
 * <h2>왜 여기 한 곳인가 — 다섯 화면이 모두 이리로 온다</h2>
 * <p>조합법을 누르면 {@code ServerboundPlaceRecipePacket} → {@code RecipeBookMenu.handlePlacement}
 * 로 가는데, 그 몸을 가진 것은 <b>둘뿐</b>이고 둘 다 {@code ServerPlaceRecipe.placeRecipe} 로
 * 들어온다.
 *
 * <ul>
 *   <li>{@code AbstractCraftingMenu} — 인벤토리 2×2({@code InventoryMenu})와
 *       제작대 3×3({@code CraftingMenu})</li>
 *   <li>{@code AbstractFurnaceMenu} — 화로·훈연기·용광로</li>
 * </ul>
 *
 * <p>그래서 다섯 화면이 이 한 클래스를 고치면 함께 고쳐진다. 메뉴마다 붙이지 않는 이유가
 * 이것이다.
 *
 * <h2>세 자리를 고친다</h2>
 * <p>왜 셋인지와 되돌릴 곳을 왜 그렇게 정했는지는 {@link ExpandedRecipeFill} 의 클래스 문서에
 * 셈과 함께 있다. 여기에는 <b>어디에 어떻게 끼워 넣었는지</b>만 적는다.
 *
 * <h2>⚠ 「만들 수 있는가」는 여기서 고치지 않는다</h2>
 * <p>{@code ServerPlaceRecipe.placeRecipe} 가 쓰는 {@code StackedItemContents} 는
 * {@code Inventory.fillStackedContents} 로 채워지고, 그쪽은
 * {@code InventoryMixin.sharedfate$fillExtraStackedContents} 가 이미 추가 칸까지 세게 해 두었다.
 * 그래서 <b>셈은 맞는데 꺼내지 못하는</b> 상태였다 — {@code canCraft} 는 통과하고
 * {@code moveItemToGrid} 가 {@code ITEM_NOT_FOUND} 로 되돌아 나가 조합칸이 <b>반만 채워졌다.</b>
 * 이 믹스인이 메우는 틈이 바로 그것이다.
 */
@Mixin(ServerPlaceRecipe.class)
public abstract class ExpandedPlaceRecipeMixin {
	@Shadow
	@Final
	private Inventory inventory;

	/**
	 * 바닐라 36칸에서 재료를 못 찾았으면 <b>추가 27칸</b>을 뒤진다.
	 *
	 * <p>{@code @At("RETURN")} 을 쓰는 것이 요점이다 — {@code HEAD} 에서 가로채면 추가 칸이
	 * 바닐라 칸보다 먼저 쓰여, 위에 있는 재료를 놔두고 아래 것을 꺼내 간다. 여기서는 바닐라가
	 * 「없다」고 답한 뒤에만 끼어들므로 <b>순서가 바닐라와 같다.</b>
	 *
	 * <p>{@code -1} 로만 가른다. 이 메서드의 정상 반환은 {@code count - moved} 이고 꺼낸 양이
	 * 요구량을 넘을 수 없으므로 언제나 0 이상이다 — 즉 {@code -1} 은
	 * {@code ITEM_NOT_FOUND} 뿐이다.
	 *
	 * <p>부르는 쪽은 이 값이 0보다 크면 <b>같은 재료로 다시</b> 부른다. 그래서 바닐라 칸에서
	 * 절반, 추가 칸에서 절반을 꺼내는 것도 저절로 된다.
	 */
	@Inject(method = "moveItemToGrid", at = @At("RETURN"), cancellable = true)
	private void sharedfate$takeFromExtraSlots(Slot gridSlot, Holder<Item> ingredient, int count,
			CallbackInfoReturnable<Integer> cir) {
		if (cir.getReturnValueI() != ExpandedRecipeFill.NOT_FOUND) {
			return;
		}
		int remaining = ExpandedRecipeFill.moveToGrid(
				this.inventory.player, gridSlot, ingredient, count);
		if (remaining != ExpandedRecipeFill.NOT_FOUND) {
			cir.setReturnValue(remaining);
		}
	}

	/**
	 * 조합칸을 비울 자리를 셀 때 <b>추가 칸의 빈칸</b>도 센다.
	 *
	 * <p>이 값이 모자라면 {@code testClearGrid} 가 거짓이 되고, 조합법을 눌러도 아무 일도
	 * 일어나지 않는다. 물건이 아래 줄까지 쌓인 사람은 바닐라 36칸이 이미 꽉 차 있으므로
	 * — {@code Inventory.add} 가 위를 먼저 채우고 넘친 것만 아래로 보내니까 —
	 * <b>바로 이 사람들이 늘 이 벽에 막힌다.</b>
	 *
	 * <p>{@link #sharedfate$putBackIntoExtraLast} 와 <b>짝</b>이다. 한쪽만 넓히면 샌다.
	 */
	@Inject(method = "getAmountOfFreeSlotsInInventory", at = @At("RETURN"), cancellable = true)
	private void sharedfate$countExtraFreeSlots(CallbackInfoReturnable<Integer> cir) {
		int extra = ExpandedRecipeFill.freeOpenSlots(this.inventory.player);
		if (extra > 0) {
			cir.setReturnValue(cir.getReturnValueI() + extra);
		}
	}

	/**
	 * 조합칸을 비울 때 바닐라 36칸이 다하면 <b>추가 27칸</b>으로 보낸다.
	 *
	 * <p>{@code clearGrid} 안의 {@code placeItemBackInInventory} 호출 <b>하나만</b> 바꿔
	 * 꿴다. 메서드 자체에 손대지 않는 이유는 그것을 {@code AbstractContainerMenu} 와
	 * {@code MerchantMenu} 도 부르기 때문이다 — 그쪽까지 한꺼번에 바꾸는 것은 이 손질의
	 * 몫이 아니다.
	 */
	@Redirect(
			method = "clearGrid",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Inventory;"
							+ "placeItemBackInInventory("
							+ "Lnet/minecraft/world/item/ItemStack;ZLnet/minecraft/util/Prediction;)V"
			)
	)
	private void sharedfate$putBackIntoExtraLast(Inventory target, ItemStack stack,
			boolean sendPacket, Prediction prediction) {
		ExpandedRecipeFill.putBack(target, stack, sendPacket, prediction);
	}
}
