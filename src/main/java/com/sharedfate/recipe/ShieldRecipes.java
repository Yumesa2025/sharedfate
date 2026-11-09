package com.sharedfate.recipe;

import com.sharedfate.perk.*;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseEffects;
import java.util.List;

/** 제작대 한 칸에서는 한 개씩 소비한다. 판자는 놓인 칸 수만큼 수리한다. */
public final class ShieldRecipes {
	private ShieldRecipes() { }

	/**
	 * 다이아몬드 방패를 든 동안의 쓰기 효과 — 달릴 수 있고(canSprint), 걸음이 줄지 않는다(1.0).
	 *
	 * <p>26.3 에서 「막는 동안 느려지는가」는 클라이언트 {@code LocalPlayer} 가 이 칸 하나로 정한다.
	 * {@code itemUseSpeedMultiplier} 가 걸음에 곱하는 배율이고, {@code isSlowDueToUsingItem} 이
	 * {@code canSprint} 를 보고 <b>방패를 든 채로는 달리기를 시작하지 못하게</b> 한다. 0.45.0-dev 는
	 * 배율만 1.0 으로 두고 canSprint 를 거짓으로 두어, 방패를 먼저 든 채 달리려 하면 걷기 속도에
	 * 묶였다(실서버 제보).
	 */
	static final UseEffects DIAMOND_USE_EFFECTS = new UseEffects(true, true, 1.0F);
	/** 0.45.0-dev 가 만든 다이아몬드 방패의 쓰기 효과. 이 값을 단 방패만 {@link #fixLegacyDiamond} 가 고친다. */
	static final UseEffects LEGACY_DIAMOND_USE_EFFECTS = new UseEffects(false, true, 1.0F);
	/** 다이아몬드 방패의 최대 내구도. 강철(1008)·일반(336)과 겹치지 않는 표시이기도 하다. */
	static final int DIAMOND_MAX_DAMAGE = 3000;
	/** 바닐라 방패의 최대 내구도. 이 값이 아니면 이 모드가 바꾼 방패(강철·다이아몬드)다. */
	static final int VANILLA_MAX_DAMAGE = 336;
	public static boolean unlocked(TeamState state, ShieldCraftEffect wanted) {
		if (state == null || !state.perksEnabled) return false;
		for (String id : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null && perk.effects().contains(wanted)) return true;
		}
		return PerkSetEffects.activeEffectsOf(state).contains(wanted);
	}

	/** null은 다른 제작법, 빈 묶음은 이 제작법이지만 조건을 충족하지 못한 경우다. */
	public static ItemStack result(List<ItemStack> grid, TeamState state) {
		return result(grid, state, true);
	}

	/** 인벤토리의 2×2 칸. 판자 수리(방패 손질)만 되고 강철·다이아몬드 방패는 제작대에서만 만든다. */
	public static ItemStack inventoryResult(List<ItemStack> grid, TeamState state) {
		return result(grid, state, false);
	}

	/** 제작대 결과인지 2×2 결과인지 메뉴를 보고 고른다. 둘 다 아니면 null. */
	public static ItemStack resultFor(AbstractContainerMenu menu, List<ItemStack> grid, TeamState state) {
		if (menu instanceof CraftingMenu) return result(grid, state);
		if (menu instanceof InventoryMenu) return inventoryResult(grid, state);
		return null;
	}

	private static ItemStack result(List<ItemStack> grid, TeamState state, boolean workbench) {
		int shields = 0, iron = 0, planks = 0, other = 0;
		ItemStack shield = ItemStack.EMPTY;
		for (ItemStack stack : grid) {
			if (stack.isEmpty()) continue;
			if (stack.is(Items.SHIELD)) { shields++; shield = stack; }
			else if (stack.is(Items.IRON_BLOCK)) iron++;
			else if (stack.is(ItemTags.PLANKS)) planks++;
			else other++;
		}
		if (other > 0) return null;
		// 방패 둘은 바닐라 「두 개 합치기」 수리(RepairItemRecipe)의 모양이다. 바닐라는 결과를
		// new ItemStack(SHIELD) 에 큰 최대 내구도만 적어 만들고 저주 아닌 마법부여를 버린다 — 강철·다이아몬드
		// 방패가 이름·설명·닳지 않음·달리기 허용을 잃는다. 그래서 하나라도 이 모드의 방패면 막는다. 바닐라
		// 방패 둘은 바닐라에 맡긴다. 2×2 칸에서도 같은 수리가 되므로 아래 2×2 거르기보다 먼저 본다.
		if (shields == 2 && iron == 0 && planks == 0) {
			for (ItemStack stack : grid) {
				if (stack.is(Items.SHIELD) && modShield(stack)) return ItemStack.EMPTY;
			}
			return null;
		}
		if (!workbench && !(shields == 1 && planks > 0 && iron == 0)) return null;
		if (shields == 9 && iron == 0 && planks == 0) {
			// 재료는 바닐라 일반 방패만 받는다. 아홉 칸이 모두 사라지므로 강철·다이아몬드나 마법부여한
			// 방패가 섞이면 그만큼 손해다 — 결과 칸을 비워 꺼내지 못하게 한다.
			for (ItemStack stack : grid) {
				if (!stack.isEmpty() && !plainShield(stack)) return ItemStack.EMPTY;
			}
			return unlocked(state, ShieldCraftEffect.DIAMOND_RECIPE) ? diamond() : ItemStack.EMPTY;
		}
		if (shields == 1 && iron == 1 && planks == 0) {
			return unlocked(state, ShieldCraftEffect.STEEL_RECIPE) && !modShield(shield)
					? steel(shield) : ItemStack.EMPTY;
		}
		if (shields == 1 && planks > 0 && iron == 0) {
			return unlocked(state, ShieldCraftEffect.PLANK_REPAIR)
					&& shield.isDamaged() && !shield.has(DataComponents.UNBREAKABLE)
					? repair(shield, planks) : ItemStack.EMPTY;
		}
		return null;
	}

	/**
	 * 이 모드가 바꾼 방패인가 — 강철(최대 내구도 1008)·다이아몬드(3000, 닳지 않음).
	 *
	 * <p>이름·설명은 보지 않는다. 모루로 바꾸거나 지울 수 있고, 바닐라 방패에도 이름을 붙일 수 있다.
	 * 지금 이 모드가 방패에 남기는 표식은 최대 내구도와 「닳지 않음」 둘뿐이다.
	 */
	static boolean modShield(ItemStack stack) {
		return stack.getMaxDamage() != VANILLA_MAX_DAMAGE || stack.has(DataComponents.UNBREAKABLE);
	}

	/**
	 * 방패 둘을 하나로 합치는 바닐라 수리를 막아야 하는가 — 둘 다 방패이고 하나라도 이 모드의 방패면 참.
	 *
	 * <p>숫돌({@code GrindstoneMenuShieldMixin})이 쓴다. 숫돌은 위 칸 방패를 복사하고 둘 중 큰 최대
	 * 내구도를 적어, 일반 방패 + 다이아몬드 방패면 「닳지 않음」 없는 내구도 3000 짜리 방패가 나왔다
	 * (2026-11-10 검토). 제작대의 두 개 합치기를 막는 규칙과 같다.
	 */
	public static boolean blocksShieldMerge(ItemStack first, ItemStack second) {
		return first.is(Items.SHIELD) && second.is(Items.SHIELD) && (modShield(first) || modShield(second));
	}

	/** 다이아몬드 방패 재료로 받는 방패 — 이 모드가 바꾸지 않았고 마법부여도 없는 바닐라 방패. */
	static boolean plainShield(ItemStack stack) {
		return stack.is(Items.SHIELD) && !modShield(stack) && !stack.isEnchanted();
	}

	public static ItemStack steel(ItemStack original) {
		ItemStack result = original.copyWithCount(1);
		result.set(DataComponents.MAX_DAMAGE, 1008);
		result.setDamageValue((int) Math.ceil(original.getDamageValue() * 3.0));
		result.set(DataComponents.CUSTOM_NAME, Component.literal("강철 방패").withStyle(ChatFormatting.GRAY));
		result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("최대 내구도 1008 · 일반 방패의 3배"))));
		return result;
	}

	public static ItemStack diamond() {
		ItemStack result = new ItemStack(Items.SHIELD);
		result.set(DataComponents.MAX_DAMAGE, DIAMOND_MAX_DAMAGE);
		result.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		result.set(DataComponents.USE_EFFECTS, DIAMOND_USE_EFFECTS);
		result.set(DataComponents.CUSTOM_NAME, Component.literal("다이아몬드 방패").withStyle(ChatFormatting.AQUA));
		result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("내구도 3000 · 닳지 않습니다"),
				Component.literal("막는 동안 이동 속도가 줄지 않습니다"))));
		return result;
	}

	/**
	 * 이미 만들어 둔 다이아몬드 방패의 쓰기 효과를 고친다. 고쳤으면 참.
	 *
	 * <p>제작법을 고쳐도 0.45.0-dev 로 만든 방패는 옛 값을 그대로 들고 있다. 그래서 그 방패를 알아보는
	 * 표시 셋 — 방패 · 최대 내구도 3000 · 닳지 않음 — 과 <b>옛 쓰기 효과 그대로</b>인지를 모두 보고
	 * 그 칸 하나만 바꾼다. 이름은 보지 않는다(모루로 이름을 바꿨어도 같은 방패다). 다른 쓰기 효과를
	 * 단 방패는 누가 일부러 정한 것이므로 건드리지 않는다.
	 */
	public static boolean fixLegacyDiamond(ItemStack stack) {
		if (stack == null || stack.isEmpty() || !stack.is(Items.SHIELD)
				|| !stack.has(DataComponents.UNBREAKABLE)
				|| stack.getMaxDamage() != DIAMOND_MAX_DAMAGE
				|| !LEGACY_DIAMOND_USE_EFFECTS.equals(stack.get(DataComponents.USE_EFFECTS))) {
			return false;
		}
		stack.set(DataComponents.USE_EFFECTS, DIAMOND_USE_EFFECTS);
		return true;
	}

	public static ItemStack repair(ItemStack original, int planks) {
		ItemStack result = original.copyWithCount(1);
		int restored = (int) Math.floor(original.getMaxDamage() * 0.15 * planks);
		result.setDamageValue(Math.max(0, original.getDamageValue() - restored));
		return result;
	}
}
