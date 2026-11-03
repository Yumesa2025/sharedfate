package com.sharedfate.recipe;

import com.sharedfate.perk.*;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseEffects;
import java.util.List;

/** 제작대 한 칸에서는 한 개씩 소비한다. 판자는 놓인 칸 수만큼 수리한다. */
public final class ShieldRecipes {
	private ShieldRecipes() { }
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
		if (shields == 9 && iron == 0 && planks == 0) {
			return unlocked(state, ShieldCraftEffect.DIAMOND_RECIPE) ? diamond() : ItemStack.EMPTY;
		}
		if (shields == 1 && iron == 1 && planks == 0) {
			return unlocked(state, ShieldCraftEffect.STEEL_RECIPE)
					&& shield.getMaxDamage() == 336 && !shield.has(DataComponents.UNBREAKABLE)
					? steel(shield) : ItemStack.EMPTY;
		}
		if (shields == 1 && planks > 0 && iron == 0) {
			return unlocked(state, ShieldCraftEffect.PLANK_REPAIR)
					&& shield.isDamaged() && !shield.has(DataComponents.UNBREAKABLE)
					? repair(shield, planks) : ItemStack.EMPTY;
		}
		return null;
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
		result.set(DataComponents.MAX_DAMAGE, 3000);
		result.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
		result.set(DataComponents.USE_EFFECTS, new UseEffects(false, true, 1.0F));
		result.set(DataComponents.CUSTOM_NAME, Component.literal("다이아몬드 방패").withStyle(ChatFormatting.AQUA));
		result.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("내구도 3000 · 닳지 않습니다"),
				Component.literal("막는 동안 이동 속도가 줄지 않습니다"))));
		return result;
	}

	public static ItemStack repair(ItemStack original, int planks) {
		ItemStack result = original.copyWithCount(1);
		int restored = (int) Math.floor(original.getMaxDamage() * 0.15 * planks);
		result.setDamageValue(Math.max(0, original.getDamageValue() - restored));
		return result;
	}
}
