package com.sharedfate.mixin;

import com.sharedfate.perk.PerkFoodCapacity;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 바닐라 {@code FoodData.add} 가 20 을 넘게 쌓인 허기를 <b>깎지 않게</b> 한다.
 *
 * <h2>무는 자리 (26.3 바이트코드로 확인)</h2>
 * <pre>{@code
 * private void add(int food, float saturation) {
 *     this.foodLevel = Mth.clamp(food + this.foodLevel, 0, 20);
 *     this.saturationLevel = Mth.clamp(saturation + this.saturationLevel, 0.0F, (float) this.foodLevel);
 * }
 * }</pre>
 *
 * <p>첫째 {@code clamp(III)I} 의 끝(20)을 {@link PerkFoodCapacity#vanillaAddCeiling} 으로,
 * 둘째 {@code clamp(FFF)F} 의 끝(허기)을 {@link PerkFoodCapacity#saturationCeiling} 으로 바꾼다.
 * 증강이 없는 사람은 허기가 20 을 넘지 않으므로 두 값 모두 바닐라와 똑같다.
 *
 * <h2>왜 필요한가</h2>
 * <p>음식과 케이크는 {@code FoodPropertiesMixin}·{@code CakeBlockEatMixin} 이 상한을 알고 직접
 * 채우지만, {@code FoodData.eat} 을 부르는 바닐라 자리가 하나 더 있다 — 포화 상태이상
 * ({@code SaturationMobEffect}). 그 자리는 주인을 모르는 {@code FoodData} 를 그대로 부르므로,
 * 허기 57 에서 포화가 한 번 돌면 20 으로 잘려 37 이 사라진다. 공유 풀은 그것을 팀원이 배가
 * 고파진 것으로 읽어 팀 전체의 허기를 깎는다. 여기서는 깎지만 않게 하고, 20 위로 올리지는 않는다.
 *
 * <p>{@code FoodData} 를 상속하는 클래스는 없고 {@code add} 는 {@code private} 이라 재정의될 수 없다.
 */
@Mixin(FoodData.class)
public abstract class FoodDataCapacityMixin {
	@Shadow
	private int foodLevel;

	@ModifyArg(method = "add",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(III)I"),
			index = 2)
	private int sharedfate$keepStoredFood(int vanillaMax) {
		// 이 자리에서 foodLevel 은 아직 더하기 전의 값이다(대입은 clamp 가 끝난 뒤).
		return Math.max(vanillaMax, PerkFoodCapacity.vanillaAddCeiling(foodLevel));
	}

	@ModifyArg(method = "add",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Mth;clamp(FFF)F"),
			index = 2)
	private float sharedfate$capSaturation(float foodAsMax) {
		return PerkFoodCapacity.saturationCeiling(foodAsMax);
	}
}
