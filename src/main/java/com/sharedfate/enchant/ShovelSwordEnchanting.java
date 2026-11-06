package com.sharedfate.enchant;

import com.sharedfate.perk.effect.ToolCraftEffect;
import com.sharedfate.team.TeamLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import org.jetbrains.annotations.Nullable;

/**
 * 프리즘 「삽질의 대가」의 「삽에 검 마법부여가 붙습니다」.
 *
 * <p>이 팀에게 <b>삽은 마법부여에서 검이다.</b> 삽 자신의 후보(효율·섬세한 손길·행운)는 사라지고,
 * 검이 받는 후보(날카로움·강타·살충·밀치기·발화·약탈·휩쓸기 날·내구성 …)가 대신 열린다.
 *
 * <h2>어떻게 검이 되는가 — 대역 검</h2>
 * <p>바닐라는 후보를 「아이템이 어느 태그에 들었는가」로 고른다({@code supported_items}·
 * {@code primary_items}). 삽 자체를 검 태그에 넣으면 이 팀이 아닌 사람에게도 새므로, 후보를 고르는
 * 그 순간에만 삽 대신 <b>대역 검</b>({@link #standIn})을 넘긴다. 대역은 철 검이되 마법부여 수치
 * ({@code enchantable})는 <b>삽의 것을 그대로</b> 받는다 — 그래서 뽑기는 같은 재질의 검을 올려놓은
 * 것과 똑같다(등급 30·책장 6·청금석 3 규칙은 {@link EnchantmentTableRules} 그대로다). 고른 결과는
 * 바닐라가 원래 삽에 붙인다 — {@code ItemStack.enchant} 는 대상 태그를 보지 않는다.
 *
 * <h2>붙은 뒤에 실제로 듣는가</h2>
 * <p>26.3 바이트코드로 확인했다. 데이터 주도 마법부여의 효과는 {@code runIterationOnItem} 이
 * {@code Enchantment.matchingSlot(손)} 만 보고 돌린다 — 날카로움·강타·살충(피해), 밀치기, 발화
 * (공격 후 효과), 약탈({@code getEnchantmentLevel} → {@code getSlotItems}) 모두 아이템 태그를
 * 다시 보지 않는다. 단 하나 <b>휩쓸기</b>는 {@code Player.isSweepAttack} 이 손에 든 것이
 * {@code #minecraft:swords} 인지 따로 본다. 그래서 이 팀의 삽이 휩쓸기 날을 달았으면 그 자리만
 * 검으로 쳐 준다({@link #sweeps}).
 *
 * <h2>서버만 판정한다</h2>
 * <p>팀의 보유 여부는 서버만 안다. 마법부여대의 후보는 원래 서버에서만 뽑으므로(클라이언트의
 * 접근자는 {@code NULL}) 클라이언트가 비용을 지울 일이 없다 — 방패와 달리 삽은 바닐라도
 * 마법부여할 수 있는 물건이라 {@code isEnchantable} 갈래를 건드리지 않는다. 모루는 클라이언트도
 * 결과를 미리 계산하지만, 이 판정은 서버에서만 켜고 서버의 결과·비용을 다시 내려보낸다.
 */
public final class ShovelSwordEnchanting {
	private ShovelSwordEnchanting() {
	}

	/** 삽인가. {@code #minecraft:shovels} 로 가린다. */
	public static boolean isShovel(@Nullable ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.is(ItemTags.SHOVELS);
	}

	/** 이 사람의 팀이 「삽을 검처럼」을 가졌는가. 클라이언트에서는 언제나 거짓이다. */
	public static boolean heldBy(@Nullable Player player) {
		return player != null && !player.level().isClientSide()
				&& ToolCraftEffect.SHOVEL_SWORD_ENCHANTING.heldBy(TeamLookup.stateOf(player.getUUID()));
	}

	/**
	 * 후보를 고를 때 넘길 물건.
	 *
	 * <p>팀이 가졌고 삽이면 {@link #standIn 대역 검}, 아니면 받은 그대로다.
	 */
	public static ItemStack poolStack(ItemStack stack, boolean held) {
		return held && isShovel(stack) ? standIn(stack) : stack;
	}

	/** 삽의 마법부여 수치를 그대로 받은 철 검. 원래 삽은 건드리지 않는다. */
	public static ItemStack standIn(ItemStack shovel) {
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		Enchantable enchantable = shovel.get(DataComponents.ENCHANTABLE);
		if (enchantable == null) {
			sword.remove(DataComponents.ENCHANTABLE);
		} else {
			sword.set(DataComponents.ENCHANTABLE, enchantable);
		}
		return sword;
	}

	/**
	 * 모루가 이 마법부여를 이 물건에 붙여 줄지.
	 *
	 * <p>팀이 가졌고 삽이면 <b>검이 받을 수 있는가</b>로 바꿔 답한다 — 검 책은 붙고, 삽에만 붙는
	 * 책(효율·섬세한 손길·행운)은 붙지 않는다. 그 밖에는 바닐라 답({@code vanilla}) 그대로다.
	 */
	public static boolean anvilAccepts(Enchantment enchantment, ItemStack target, boolean vanilla, boolean held) {
		if (!held || !isShovel(target)) {
			return vanilla;
		}
		return enchantment.canEnchant(standIn(target));
	}

	/**
	 * 이 삽으로 휩쓸기를 할지. 바닐라는 검만 휩쓸므로, 이 팀의 삽이 휩쓸기 날을 달았을 때만 검으로 친다.
	 * 휩쓸기 날이 없는 삽은 바닐라처럼 휩쓸지 않는다.
	 */
	public static boolean sweeps(ItemStack stack, boolean held) {
		if (!held || !isShovel(stack)) {
			return false;
		}
		for (Holder<Enchantment> enchantment : stack.getEnchantments().keySet()) {
			if (enchantment.is(Enchantments.SWEEPING_EDGE)) {
				return true;
			}
		}
		return false;
	}
}
