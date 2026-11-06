package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentTableAccess;
import com.sharedfate.enchant.ShieldEnchantments;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.world.level.block.EnchantingTableBlock;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.List;

/**
 * 방패 세트 3단계 팀이 마법부여대에서 방패에 마법부여하게 한다.
 *
 * <p>등급은 다른 장비와 같이 {@link EnchantmentMenuTableRulesMixin} 이 정한다 — 책장 6개 이상이면
 * 언제나 30 이라, 방패의 후보 전체가 언제나 열린다.
 *
 * <p>책장 수 데이터 칸이 여기 달려 있는 것은 순서 때문이다. 데이터 칸은 붙은 순서대로 번호를
 * 받으므로 옮기면 다른 칸과 번호가 뒤바뀐다.
 */
@Mixin(EnchantmentMenu.class)
public abstract class ShieldEnchantmentMenuMixin implements EnchantmentTableAccess {
	@Shadow @Final private DataSlot enchantmentSeed;
	@Shadow @Final private ContainerLevelAccess access;
	@Unique private Player sharedfate$shieldOwner;
	@Unique private DataSlot sharedfate$shelves;

	@Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("TAIL"))
	private void sharedfate$initialize(int id, Inventory inventory, ContainerLevelAccess levelAccess, CallbackInfo ci) {
		sharedfate$shieldOwner = inventory.player;
		// 바닐라와 같은 방법으로 센 책장 수다. 15 를 넘기지 않는 것은 바닐라가 보는 최대치이기 때문이다.
		// 화면은 6 에서 자른다(EnchantmentTableRules.shownShelves).
		sharedfate$shelves = new DataSlot() {
			private int shown;
			@Override public int get() {
				return levelAccess.evaluate((level, pos) -> (int) Math.min(15,
						EnchantingTableBlock.BOOKSHELF_OFFSETS.stream().filter(offset ->
								EnchantingTableBlock.isValidBookShelf(level, pos, offset)).count()), shown);
			}
			@Override public void set(int value) { shown = value; }
		};
		((AbstractContainerMenuAccessor) this).sharedfate$invokeAddDataSlot(sharedfate$shelves);
	}

	@Override public int sharedfate$bookshelves() { return sharedfate$shelves == null ? 0 : sharedfate$shelves.get(); }

	@Unique private boolean sharedfate$unlocked() {
		return sharedfate$shieldOwner != null && ShieldRecipes.unlocked(
				TeamLookup.stateOf(sharedfate$shieldOwner.getUUID()), ShieldCraftEffect.ENCHANTING);
	}

	/** 해금 판정은 서버에서만 한다. 클라이언트가 「잠김」으로 비용을 지우면 서버는 다시 보내 주지 않는다. */
	@WrapOperation(method = "slotsChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEnchantable()Z"))
	private boolean sharedfate$allowShield(ItemStack stack, Operation<Boolean> original) {
		if (!stack.is(Items.SHIELD)) return original.call(stack);
		boolean serverSide = sharedfate$shieldOwner instanceof ServerPlayer;
		return ShieldEnchantments.tableAccepts(stack, serverSide, serverSide && sharedfate$unlocked());
	}

	@Inject(method = "getEnchantmentList", at = @At("HEAD"), cancellable = true)
	private void sharedfate$shieldCandidates(RegistryAccess registries, ItemStack stack, int slot, int grade,
			CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
		if (stack.is(Items.SHIELD)) cir.setReturnValue(sharedfate$unlocked()
				? ShieldEnchantments.select(registries, (long) enchantmentSeed.get() + slot) : List.of());
	}
}
