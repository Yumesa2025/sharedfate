package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkShieldCombat;
import com.sharedfate.team.TeamState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;
import org.jetbrains.annotations.Nullable;

/**
 * 방어구 내구도가 확률로 닳지 않는다. 세트 「방어 2단계」가 쓴다.
 *
 * <pre>
 * { "type": "armor_durability", "ignore_chance": 0.5 }
 * </pre>
 *
 * <p>사람이 정한 것(2026-11-06) — 방어 2단계의 「방패로 막는 동안 받는 피해 절반」이 방패 세트와 겹쳐서
 * 「받는 피해 15% 감소 + 방어구 내구도 50% 확률로 안 닳음」으로 바꿨다. 방패 세트 2단계
 * ({@link ShieldDurabilityEffect})와 짝이다.
 *
 * <p>방어구는 사람 방어구 칸(머리 · 몸통 · 다리 · 발)에 입는 것이다. 겉날개는 비행 도구라 빼고, 방패는
 * 방패 세트가 맡으므로 뺀다. 집행은 {@code ItemStackDurabilityMixin} 이 내구성 마법 계산 뒤에 한다.
 */
public record ArmorDurabilityEffect(double ignoreChance) implements PerkEffect {
	public static PerkEffect fromJson(String id, int index, JsonObject json) {
		Double chance = PerkEffectType.readDouble(json, "ignore_chance");
		return chance != null && Double.isFinite(chance) && chance > 0 && chance <= 1
				? new ArmorDurabilityEffect(chance) : null;
	}

	/** {@code roll} 은 0 이상 1 미만의 난수. 닳지 않으면 0. */
	public static int loss(int amount, double ignoreChance, double roll) {
		return amount <= 0 || roll < ignoreChance ? 0 : amount;
	}

	/** 사람 방어구 칸에 입는 물건인가. 겉날개 · 방패 · 동물 갑옷은 아니다. */
	public static boolean isArmor(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty() || stack.has(DataComponents.BLOCKS_ATTACKS)
				|| stack.has(DataComponents.GLIDER)) {
			return false;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
	}

	/** 이 팀이 이 방어구를 닳게 할 양. 효과가 여럿이면 각자 따로 굴린다. */
	public static int durability(@Nullable TeamState state, ItemStack stack, int amount, RandomSource random) {
		if (amount <= 0 || !isArmor(stack)) {
			return amount;
		}
		int loss = amount;
		for (PerkEffect effect : PerkShieldCombat.effects(state)) {
			if (effect instanceof ArmorDurabilityEffect armor) {
				loss = loss(loss, armor.ignoreChance(), random.nextDouble());
			}
		}
		return loss;
	}
}
