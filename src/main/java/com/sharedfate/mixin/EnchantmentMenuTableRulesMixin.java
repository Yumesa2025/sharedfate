package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentTableRules;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 인챈트 탁자를 <b>「책장 6개 이상이면 세 칸 모두 30레벨, 청금석 3개」</b>로 바꾼다.
 * 규칙 자체는 {@link EnchantmentTableRules} 에 있다.
 *
 * <h2>등급</h2>
 *
 * <p>바닐라 {@code slotsChanged} 의 람다는 책장을 센 뒤 칸마다
 * {@code EnchantmentHelper.getEnchantmentCost} 로 요구 레벨을 뽑는다. 그 호출 하나를 갈아 끼워
 * 책장이 모자라면 0, 넉넉하면 30 을 돌려준다. 책장 수는 바닐라가 센 값을 그대로 받으므로 막힌
 * 책장을 빼는 규칙도 그대로다.
 *
 * <p>원래 호출의 난수를 소비하지 않아도 결과는 바닐라 30레벨과 같다. 후보를 뽑는
 * {@code getEnchantmentList} 가 칸마다 {@code enchantmentSeed + 칸} 으로 씨앗을 새로 심기
 * 때문이다. 같은 이유로 세 칸은 서로 따로 뽑힌다.
 *
 * <p>방패도 이 자리를 지난다. 방패의 후보는 {@link ShieldEnchantmentMenuMixin} 이 정한다.
 *
 * <h2>청금석</h2>
 *
 * <p>바닐라는 「청금석 ≥ 칸 번호 + 1」을 보고 칸 번호 + 1 개를 걷는다. 앞쪽은
 * {@code HEAD} 에서 3개를 먼저 따지고(넉넉하면 바닐라 비교도 저절로 통과한다), 걷는 개수는
 * 람다 안의 {@code consume} 을 감싸 3 으로 바꾼다.
 *
 * <p>{@code clickMenuButton} 은 클라이언트에서도 돈다 — 화면이 패킷을 보내기 전에 로컬에서 부른다.
 * 그래서 {@code HEAD} 검사는 양쪽에서 같이 돌고, 걷는 일은 서버에서만 도는 람다 안에서 일어난다.
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuTableRulesMixin {
	@WrapOperation(
			method = "lambda$slotsChanged$0",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/enchantment/EnchantmentHelper;"
							+ "getEnchantmentCost(Lnet/minecraft/util/RandomSource;IILnet/minecraft/world/item/ItemStack;)I"
			)
	)
	private int sharedfate$fixedGrade(
			RandomSource random, int slot, int shelves, ItemStack stack, Operation<Integer> original) {
		return EnchantmentTableRules.grade(shelves);
	}

	/** 청금석이 3개보다 적으면 어느 칸도 누를 수 없다. 크리에이티브는 바닐라처럼 통과한다. */
	@Inject(
			method = "clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private void sharedfate$requireLapis(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
		if (id < 0 || id >= 3) {
			// 범위 밖은 바닐라가 기록을 남기고 거절한다. 그 자리를 뺏지 않는다.
			return;
		}
		ItemStack lapis = ((EnchantmentMenu) (Object) this).getSlot(1).getItem();
		if (!EnchantmentTableRules.hasLapis(player, lapis)) {
			cir.setReturnValue(false);
		}
	}

	/** 걷는 청금석을 칸 번호 + 1 개에서 언제나 3개로 바꾼다. */
	@WrapOperation(
			method = "lambda$clickMenuButton$0",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/item/ItemStack;consume(ILnet/minecraft/world/entity/LivingEntity;)V"
			)
	)
	private void sharedfate$consumeThreeLapis(
			ItemStack lapis, int amount, LivingEntity entity, Operation<Void> original) {
		original.call(lapis, EnchantmentTableRules.LAPIS_COST, entity);
	}
}
