package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentTableAccess;
import com.sharedfate.enchant.ShieldEnchantments;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
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

@Mixin(EnchantmentMenu.class)
public abstract class ShieldEnchantmentMenuMixin implements EnchantmentTableAccess {
	@Shadow @Final private DataSlot enchantmentSeed;
	@Shadow @Final private ContainerLevelAccess access;
	@Unique private Player sharedfate$shieldOwner;
	@Unique private DataSlot sharedfate$shelves;

	@Inject(method = "<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/inventory/ContainerLevelAccess;)V", at = @At("TAIL"))
	private void sharedfate$initialize(int id, Inventory inventory, ContainerLevelAccess levelAccess, CallbackInfo ci) {
		sharedfate$shieldOwner = inventory.player;
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

	@WrapOperation(method = "slotsChanged", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;isEnchantable()Z"))
	private boolean sharedfate$allowShield(ItemStack stack, Operation<Boolean> original) {
		return stack.is(Items.SHIELD) ? sharedfate$unlocked() && !stack.isEnchanted() : original.call(stack);
	}

	@WrapOperation(method = "lambda$slotsChanged$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;getEnchantmentCost(Lnet/minecraft/util/RandomSource;IILnet/minecraft/world/item/ItemStack;)I"))
	private int sharedfate$shieldGrade(RandomSource random, int slot, int shelves, ItemStack stack, Operation<Integer> original) {
		if (!stack.is(Items.SHIELD)) return original.call(random, slot, shelves, stack);
		ItemStack copy = stack.copy();
		copy.set(DataComponents.ENCHANTABLE, new Enchantable(15));
		return original.call(random, slot, shelves, copy);
	}

	@Inject(method = "getEnchantmentList", at = @At("HEAD"), cancellable = true)
	private void sharedfate$shieldCandidates(RegistryAccess registries, ItemStack stack, int slot, int grade,
			CallbackInfoReturnable<List<EnchantmentInstance>> cir) {
		if (stack.is(Items.SHIELD)) cir.setReturnValue(sharedfate$unlocked()
				? ShieldEnchantments.select(registries, grade, (long) enchantmentSeed.get() + slot) : List.of());
	}
}
