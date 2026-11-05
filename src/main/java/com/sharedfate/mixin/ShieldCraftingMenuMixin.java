package com.sharedfate.mixin;

import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamLookup;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.ArrayList;

/** 제작대와 인벤토리 2×2 칸이 함께 거치는 곳이다. 서버의 결과 칸부터 막으므로 해금하지 않은 팀에는 유령 결과도 보이지 않는다. */
@Mixin(CraftingMenu.class)
public abstract class ShieldCraftingMenuMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("HEAD"), cancellable = true)
	private static void sharedfate$shieldResult(AbstractContainerMenu menu, ServerLevel level,
			Player player, CraftingContainer input, ResultContainer output,
			RecipeHolder<CraftingRecipe> recipe, CallbackInfo ci) {
		var grid = new ArrayList<ItemStack>();
		for (int i = 0; i < input.getContainerSize(); i++) grid.add(input.getItem(i));
		ItemStack result = ShieldRecipes.resultFor(menu, grid, TeamLookup.stateOf(player.getUUID()));
		if (result == null) return;
		output.setRecipeUsed(null);
		output.setItem(0, result);
		menu.setRemoteSlot(0, result);
		((ServerPlayer) player).connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), 0, result));
		ci.cancel();
	}
}
