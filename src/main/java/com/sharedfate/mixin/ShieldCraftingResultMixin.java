package com.sharedfate.mixin;

import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 바닐라는 등록된 제작법이 없으면 입력을 돌려준다. 특수 제작만 그 반환을 비운다. */
@Mixin(ResultSlot.class)
public abstract class ShieldCraftingResultMixin {
	@Shadow @Final private Player player;
	@Inject(method = "getRemainingItems", at = @At("HEAD"), cancellable = true)
	private void sharedfate$consumeInputs(CraftingInput input, Level level,
			CallbackInfoReturnable<NonNullList<ItemStack>> cir) {
		ItemStack result = ShieldRecipes.resultFor(player.containerMenu, input.items(),
				TeamLookup.stateOf(player.getUUID()));
		if (result != null && !result.isEmpty()) cir.setReturnValue(NonNullList.withSize(input.size(), ItemStack.EMPTY));
	}
}
