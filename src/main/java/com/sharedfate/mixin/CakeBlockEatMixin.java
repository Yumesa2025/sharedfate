package com.sharedfate.mixin;

import com.sharedfate.perk.PerkFoodCapacity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodConstants;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 「먹방」을 가진 팀이면 케이크 한 조각도 허기를 20 위로 채운다.
 *
 * <p>케이크는 음식 아이템이 아니라 {@code FoodProperties.onConsume} 을 지나지 않는다.
 * {@code CakeBlock.eat} 이 {@code player.getFoodData().eat(2, 0.1F)} 를 직접 부른다(26.3 바이트코드로
 * 확인). 먹을 수 있는가는 같은 메서드 머리의 {@code player.canEat(false)} 가 정하고, 그쪽은
 * {@link PlayerCanEatMixin} 이 연다. 여기서는 얼마나 차는가만 바꾼다.
 *
 * <p>상한이 20 이면 바닐라 {@code eat} 을 그대로 부른다. {@code CakeBlock.eat} 은 {@code static} 이라
 * 재정의될 수 없다.
 */
@Mixin(CakeBlock.class)
public abstract class CakeBlockEatMixin {
	@Redirect(method = "eat",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodData;eat(IF)V"))
	private static void sharedfate$feedSlice(FoodData foodData, int nutrition, float saturationModifier,
			LevelAccessor level, BlockPos pos, BlockState state, Player player) {
		int capacity = PerkFoodCapacity.capacityOf(player);
		if (!PerkFoodCapacity.eatsWhenFull(capacity)) {
			foodData.eat(nutrition, saturationModifier);
			return;
		}
		PerkFoodCapacity.feed(foodData, nutrition,
				FoodConstants.saturationByModifier(nutrition, saturationModifier), capacity);
	}
}
