package com.sharedfate.mixin;

import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import com.sharedfate.inventory.ExpandedInventoryContainer;
import com.sharedfate.inventory.ExpandedInventoryManager;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Predicate;

@Mixin(Inventory.class)
public abstract class InventoryMixin {
	@Shadow
	@Final
	@Mutable
	private NonNullList<ItemStack> items;

	@Shadow
	@Final
	public Player player;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void sharedfate$installSharedItems(Player player, EntityEquipment equipment, CallbackInfo ci) {
		if (!(player instanceof ServerPlayer)) {
			return;
		}

		TeamState state = TeamLookup.stateOf(player.getUUID());
		if (state != null) {
			this.items = state.mainItems;
		}
	}

	@Inject(method = "save", at = @At("HEAD"), cancellable = true)
	private void sharedfate$skipPersonalSave(
			ValueOutput.TypedOutputList<ItemStackWithSlot> output, CallbackInfo ci) {
		if (TeamLookup.serverStateOf(this.player) != null) {
			ci.cancel();
		}
	}

	@Inject(method = "load", at = @At("HEAD"), cancellable = true)
	private void sharedfate$skipPersonalLoad(
			ValueInput.TypedInputList<ItemStackWithSlot> input, CallbackInfo ci) {
		if (TeamLookup.serverStateOf(this.player) != null) {
			ci.cancel();
		}
	}

	@Redirect(
			method = "addResource(Lnet/minecraft/world/item/ItemStack;)I",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/player/Inventory;getFreeSlot()I"
			)
	)
	private int sharedfate$mergeExtraBeforeMainFreeSlot(Inventory inventory, ItemStack stack) {
		if (ExpandedInventoryManager.enabled() && this.player instanceof ServerPlayer) {
			ExpandedInventoryManager.extraFor(this.player).mergeExisting(stack);
			if (stack.isEmpty()) {
				return -1;
			}
		}
		return inventory.getFreeSlot();
	}

	@Inject(method = "add(ILnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"), cancellable = true)
	private void sharedfate$addToExtraItems(
			int slot, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (slot != -1 || !ExpandedInventoryManager.enabled()
				|| !(this.player instanceof ServerPlayer)) {
			return;
		}
		ExpandedInventoryContainer extra = ExpandedInventoryManager.extraFor(this.player);
		if (extra.addStack(stack)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "clearOrCountMatchingItems", at = @At("RETURN"), cancellable = true)
	private void sharedfate$clearOrCountExtraItems(
			Predicate<ItemStack> predicate, boolean simulate, int maximum, Container other,
			CallbackInfoReturnable<Integer> cir) {
		if (!ExpandedInventoryManager.enabled() || !(this.player instanceof ServerPlayer)) {
			return;
		}
		int counted = cir.getReturnValue();
		if (maximum > 0 && counted >= maximum) {
			return;
		}
		int remaining = maximum == 0 ? 0 : maximum - counted;
		int extra = ExpandedInventoryManager.extraFor(this.player)
				.clearOrCountMatchingItems(predicate, remaining, simulate);
		cir.setReturnValue(counted + extra);
	}

	/**
	 * 「무엇을 얼마나 가졌는가」를 세는 자리에 추가 27칸을 더한다.
	 *
	 * <h2>⚠ 서버에서만 켜면 조합법이 회색으로 남는다</h2>
	 * <p>예전에는 {@code player instanceof ServerPlayer} 로 <b>서버에서만</b> 더했다. 그래서
	 * 「눌러서 자동 채우기」의 셈은 맞았지만, <b>회색인지 아닌지를 정하는 쪽은
	 * 클라이언트</b>였다 — {@code RecipeBookComponent.initVisuals}/{@code updateStackedContents}
	 * 가 {@code minecraft.player.getInventory().fillStackedContents(...)} 로 <b>로컬</b>
	 * 인벤토리를 훑고, {@code LocalPlayer} 는 {@code ServerPlayer} 가 아니므로 이 줄이 한 번도
	 * 돌지 않았다. 재료를 추가 칸에만 두면 만들 수 있는데도 회색으로 남던 것이 이 틈이다.
	 *
	 * <p>그래서 {@code instanceof} 로 가르지 않고
	 * {@link ExpandedInventoryContainer#active()} 로 묻는다 — 서버에서는 팀 상태로,
	 * 클라이언트에서는 서버가 협상해 내려준 값으로 답하므로 양쪽 모두 정확하다.
	 * {@code PlayerExpandedInventoryMixin}(활·석궁이 추가 칸의 화살을 찾는 곳)이 같은 이유로
	 * 이미 같은 길을 쓰고 있다.
	 *
	 * <p>클라이언트가 추가 칸의 내용을 알고 있다는 것은 보장된다 — 창이 열려 있으면 바닐라
	 * 칸 동기화가 {@code ExpandedInventorySlot} 을 거쳐 그 사본에 그대로 써 넣는다. 조합법
	 * 책은 창이 열려 있을 때만 보이므로 늘 그 상태다.
	 *
	 * <h2>엔더 상자는 더하지 않는다</h2>
	 * <p>엔더 상자도 팀이 공유하지만({@code shareEnderChest}) 조합법은 「지금 들고 있는 것」으로
	 * 판정해야 한다. 여기에 더하는 것은 추가 27칸뿐이다.
	 */
	@Inject(method = "fillStackedContents", at = @At("TAIL"))
	private void sharedfate$fillExtraStackedContents(
			StackedItemContents contents, CallbackInfo ci) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}
		ExpandedInventoryContainer extra = ExpandedInventoryManager.extraFor(this.player);
		if (extra.active()) {
			extra.fillStackedContents(contents);
		}
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void sharedfate$tickExtraItems(CallbackInfo ci) {
		if (!sharedfate$hasActiveExtra()) {
			return;
		}
		for (ItemStack stack : ExpandedInventoryManager.extraFor(this.player).getItems()) {
			if (!stack.isEmpty()) {
				stack.inventoryTick(this.player.level(), this.player, null);
			}
		}
	}

	@Inject(method = "isEmpty", at = @At("RETURN"), cancellable = true)
	private void sharedfate$includeExtraInEmptyCheck(CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue() && sharedfate$hasActiveExtra()) {
			cir.setReturnValue(ExpandedInventoryManager.extraFor(this.player).isEmpty());
		}
	}

	@Inject(method = "contains(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("RETURN"), cancellable = true)
	private void sharedfate$containsExtraStack(
			ItemStack wanted, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && sharedfate$hasActiveExtra()) {
			cir.setReturnValue(ExpandedInventoryManager.extraFor(this.player).getItems().stream()
					.anyMatch(stack -> !stack.isEmpty()
							&& ItemStack.isSameItemSameComponents(stack, wanted)));
		}
	}

	@Inject(method = "contains(Lnet/minecraft/tags/TagKey;)Z", at = @At("RETURN"), cancellable = true)
	private void sharedfate$containsExtraTag(
			TagKey<Item> tag, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && sharedfate$hasActiveExtra()) {
			cir.setReturnValue(ExpandedInventoryManager.extraFor(this.player).getItems().stream()
					.anyMatch(stack -> !stack.isEmpty() && stack.is(tag)));
		}
	}

	@Inject(method = "contains(Ljava/util/function/Predicate;)Z", at = @At("RETURN"), cancellable = true)
	private void sharedfate$containsExtraPredicate(
			Predicate<ItemStack> predicate, CallbackInfoReturnable<Boolean> cir) {
		if (!cir.getReturnValue() && sharedfate$hasActiveExtra()) {
			cir.setReturnValue(ExpandedInventoryManager.extraFor(this.player).getItems().stream()
					.anyMatch(predicate));
		}
	}

	private boolean sharedfate$hasActiveExtra() {
		return ExpandedInventoryManager.enabled()
				&& this.player instanceof ServerPlayer
				&& ExpandedInventoryManager.extraFor(this.player).active();
	}
}
