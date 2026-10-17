package com.sharedfate.mixin;

import com.sharedfate.perk.PerkFoodCapacity;
import com.sharedfate.perk.PerkFoodRules;
import com.sharedfate.sync.FoodOverflowBuffer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 먹기 처리에 세 가지로 끼어든다.
 *
 * <ol>
 *   <li>먹은 영양을 {@link FoodOverflowBuffer} 에 적어 팀의 남는 허기를 모아 둔다.</li>
 *   <li>{@code no_food_hunger} 증강을 가진 팀이면 영양 섭취 자체를 건너뛴다.</li>
 *   <li>{@code food_nutrition} 증강을 가진 팀이면 채워지는 양에 배율을 걸고, 먹는 순간에
 *       잠깐 얹을 효과가 있으면 함께 건다.</li>
 * </ol>
 *
 * <h2>왜 여기인가</h2>
 * <p>26.2 에서 음식이 실제로 배를 채우는 곳은 {@code FoodProperties.onConsume} 안의
 * {@code FoodData.eat(FoodProperties)} 호출 한 줄뿐이다. {@code FoodData} 쪽에 직접 걸지 않는
 * 이유는 {@code FoodData} 가 자기 주인을 모르기 때문이다. 여기서는 {@code entity} 가 곧 먹는
 * 사람이라 어느 팀인지 그대로 물어볼 수 있다.
 *
 * <p>그 한 줄만 건너뛰므로 먹기의 나머지는 전부 그대로다. 아이템은 줄어들고, 먹는 소리와 트림
 * 소리도 나고, 수상한 스튜의 상태이상이나 우유의 해독 같은 {@code consume_effects} 도 모두
 * 평소처럼 일어난다. 그것들은 {@code Consumable.onConsume} 의 다른 자리에서 처리된다.
 *
 * <p>영양 기록도 함께 막아야 한다. 안 그러면 먹은 만큼이 팀의 남는 허기로 쌓였다가 나중에
 * 배가 줄 때 되돌아와, 결국 음식으로 허기를 회복한 것과 같아진다.
 *
 * <p>증강 풀이 비어 있으면 {@link PerkFoodRules} 의 판정이 전부 팀 상태만 보고 곧바로
 * "해당 없음"이다. 막지 않고, 배율은 1 이라 {@code scaleNutrition} 이 받은 정의를 그대로
 * 돌려주며, 얹을 효과도 없다. 먹기 동작이 바닐라와 완전히 같다.
 */
@Mixin(FoodProperties.class)
public abstract class FoodPropertiesMixin {
	/**
	 * 먹기가 끝난 뒤의 뒷정리.
	 *
	 * <p>먹는 순간에 얹히는 효과({@code food_nutrition} 의 {@code effects}, 예를 들어 상한
	 * 진수성찬의 독)는 <b>영양이 막혔든 아니든</b> 그대로 걸린다. 그건 회복량의 대가가 아니라
	 * 먹는 행위의 대가라, 회복이 막혔다고 면제될 이유가 없다.
	 *
	 * <p>영양 기록에는 실제로 몸에 들어간 양, 즉 배율을 먹인 값을 적는다. 원래 값을 적으면
	 * 팀의 남는 허기가 실제보다 적게 쌓여 배율이 반쯤 새어 나간다.
	 */
	@Inject(method = "onConsume", at = @At("TAIL"))
	private void sharedfate$recordNutrition(Level level, LivingEntity entity, ItemStack stack,
			Consumable consumable, CallbackInfo ci) {
		if (level.isClientSide() || !(entity instanceof ServerPlayer player)) {
			return;
		}
		PerkFoodRules.grantEatingEffects(player);
		if (!PerkFoodRules.blocksFoodHunger(player)) {
			FoodOverflowBuffer.recordConsumption(player,
					PerkFoodRules.scaleNutrition((FoodProperties) (Object) this, player));
		}
	}

	/**
	 * 영양 섭취 한 줄을 증강에 맞게 바꾼다.
	 *
	 * <p>{@code no_food_hunger} 를 가진 팀원이면 통째로 건너뛰고, {@code food_nutrition} 을
	 * 가진 팀원이면 배율을 먹인 정의를 대신 건넨다. <b>두 증강을 함께 가졌으면 막는 쪽이
	 * 이긴다.</b> 배수의 대상 자체가 0 이라 몇 배를 곱해도 0 이기 때문이다.
	 *
	 * <p>배율을 먹인 정의를 건네도 부르는 것은 바닐라의 {@code eat} 그대로다. 20 상한도,
	 * 포만감이 허기를 넘지 못하는 규칙도 바닐라가 계산한다. 「먹방」({@code food_capacity})을
	 * 가진 팀만 예외로, 상한이 20 위라 {@link PerkFoodCapacity#feed} 가 같은 순서로 대신 계산한다.
	 *
	 * <p>클라이언트에서는 팀 상태를 볼 수 없어 판정이 언제나 "해당 없음"이라 바닐라처럼 먹기를
	 * 미리 그린다. 막았을 때는 서버 쪽 허기가 바뀌지 않아 화면이 한 틱 동안 어긋나는데,
	 * {@link PerkFoodRules#resyncFoodDisplay} 가 다음 틱에 서버 값을 다시 보내게 만들어
	 * 곧바로 제자리를 찾는다. 배율만 걸렸을 때는 서버 허기가 실제로 달라지므로 바닐라가 알아서
	 * 새 값을 보내 준다.
	 */
	@Redirect(method = "onConsume",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/food/FoodData;eat(Lnet/minecraft/world/food/FoodProperties;)V"))
	private void sharedfate$skipFoodHunger(FoodData foodData, FoodProperties properties,
			Level level, LivingEntity entity, ItemStack stack, Consumable consumable) {
		if (PerkFoodRules.blocksFoodHunger(entity)) {
			PerkFoodRules.resyncFoodDisplay(entity);
			return;
		}
		FoodProperties scaled = PerkFoodRules.scaleNutrition(properties, entity);
		// 「먹방」을 가진 팀이면 20 위로 채운다. 바닐라 eat 은 20 에서 자르므로 직접 계산한다.
		int capacity = PerkFoodCapacity.capacityOf(entity);
		if (PerkFoodCapacity.eatsWhenFull(capacity)) {
			PerkFoodCapacity.feed(foodData, scaled.nutrition(), scaled.saturation(), capacity);
			return;
		}
		foodData.eat(scaled);
	}
}
