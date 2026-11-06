package com.sharedfate.recipe;

import com.sharedfate.perk.effect.ToolCraftEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 프리즘 「금기의 광석」의 「다이아몬드 9개로 네더라이트 주괴를 만들 수 있습니다」.
 *
 * <p>제작대 3×3 을 <b>다이아몬드로 가득 채운 모양</b> — 바닐라 다이아몬드 블록 제작법과 같은 모양 —
 * 에서 이 팀에게만 결과를 다이아몬드 블록 대신 네더라이트 주괴 1개로 바꾼다. 다른 팀은 바닐라
 * 그대로 다이아몬드 블록을 얻는다. 칸마다 한 개씩, 모두 9개를 소비한다.
 *
 * <p>결과 칸·재료 소비·꺼내기 재검사는 방패 제작법과 같은 고리({@code ShieldCraftingMenuMixin}·
 * {@code ShieldCraftingResultMixin}·{@code ShieldRecipePickupMixin})를 지난다 —
 * {@link PerkCraftingRecipes} 가 둘을 묶는다. 결과 칸은 서버가 정해 보내므로 다른 팀의
 * 화면에 네더라이트가 비치는 일은 없다.
 */
public final class DiamondNetheriteRecipe {
	/** 제작대 칸 수. 인벤토리 2×2 에는 9개가 들어가지 않는다. */
	static final int GRID_SIZE = 9;

	private DiamondNetheriteRecipe() {
	}

	/** 3×3 이 모두 다이아몬드인가. */
	public static boolean isDiamondGrid(List<ItemStack> grid) {
		if (grid == null || grid.size() != GRID_SIZE) {
			return false;
		}
		for (ItemStack stack : grid) {
			if (stack == null || stack.isEmpty() || !stack.is(Items.DIAMOND)) {
				return false;
			}
		}
		return true;
	}

	/** 네더라이트 주괴 결과. 이 제작법이 아니거나 팀이 갖지 않았으면 null(바닐라 처리). */
	public static @Nullable ItemStack result(List<ItemStack> grid, @Nullable TeamState state) {
		if (!isDiamondGrid(grid) || !ToolCraftEffect.DIAMOND_TO_NETHERITE.heldBy(state)) {
			return null;
		}
		return new ItemStack(Items.NETHERITE_INGOT);
	}

	/** 제작대에서만 쓴다. 다른 메뉴면 null. */
	public static @Nullable ItemStack resultFor(AbstractContainerMenu menu, List<ItemStack> grid,
			@Nullable TeamState state) {
		return menu instanceof CraftingMenu ? result(grid, state) : null;
	}

	/**
	 * 결과 칸에 이미 놓인 네더라이트를 꺼내지 못하게 할지.
	 *
	 * <p>결과를 본 뒤 팀이 증강을 잃었으면 결과 칸은 다음에 재료가 바뀔 때까지 네더라이트를 들고
	 * 있다. 그 자리를 막는다 — 재료를 한 번 건드리면 바닐라 다이아몬드 블록으로 바뀐다.
	 */
	public static boolean blocksPickup(List<ItemStack> grid, ItemStack shown, @Nullable TeamState state) {
		return shown != null && shown.is(Items.NETHERITE_INGOT) && isDiamondGrid(grid)
				&& !ToolCraftEffect.DIAMOND_TO_NETHERITE.heldBy(state);
	}
}
