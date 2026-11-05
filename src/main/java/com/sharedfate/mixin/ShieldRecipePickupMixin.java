package com.sharedfate.mixin;

import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;

/** 결과를 본 뒤 팀이나 증강이 바뀌었어도 서버가 꺼내기를 다시 검사한다. */
@Mixin(Slot.class)
public abstract class ShieldRecipePickupMixin {
	@Inject(method = "mayPickup", at = @At("HEAD"), cancellable = true)
	private void sharedfate$requireUnlock(Player player, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ResultSlot) || !(player.containerMenu instanceof AbstractCraftingMenu menu)
				|| player.level().isClientSide()) return;
		var grid = new ArrayList<ItemStack>();
		for (Slot slot : menu.getInputGridSlots()) grid.add(slot.getItem());
		ItemStack result = ShieldRecipes.resultFor(menu, grid, TeamLookup.stateOf(player.getUUID()));
		if (result != null && result.isEmpty()) cir.setReturnValue(false);
	}
}
