package com.sharedfate.recipe;

import com.sharedfate.team.TeamState;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 증강이 바꾸는 제작 결과를 한 자리에서 고른다 — 방패 제작법({@link ShieldRecipes})과
 * 다이아몬드 → 네더라이트({@link DiamondNetheriteRecipe}).
 *
 * <p>약속은 {@link ShieldRecipes#resultFor} 그대로다. {@code null} 은 「바닐라에 맡긴다」, 빈 묶음은
 * 「이 제작법이지만 조건을 채우지 못했다」(결과 칸을 비우고 꺼내기도 막는다)이다. 두 제작법의 모양은
 * 겹치지 않는다 — 방패 쪽은 방패·철 블록·판자 밖의 재료가 하나라도 있으면 null 이다.
 */
public final class PerkCraftingRecipes {
	private PerkCraftingRecipes() {
	}

	public static @Nullable ItemStack resultFor(AbstractContainerMenu menu, List<ItemStack> grid,
			@Nullable TeamState state) {
		ItemStack shield = ShieldRecipes.resultFor(menu, grid, state);
		return shield != null ? shield : DiamondNetheriteRecipe.resultFor(menu, grid, state);
	}

	/** 결과 칸의 물건을 지금 꺼내면 안 되는가. 서버가 꺼내기 직전에 다시 묻는다. */
	public static boolean blocksPickup(AbstractContainerMenu menu, List<ItemStack> grid, ItemStack shown,
			@Nullable TeamState state) {
		ItemStack result = resultFor(menu, grid, state);
		if (result != null) {
			return result.isEmpty();
		}
		return DiamondNetheriteRecipe.blocksPickup(grid, shown, state);
	}
}
