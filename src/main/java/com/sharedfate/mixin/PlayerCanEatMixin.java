package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.sharedfate.perk.PerkFoodCapacity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 「먹방」을 가진 팀이면 배가 불러도 먹을 수 있게 한다.
 *
 * <h2>먹기 가능 판정이 지나는 자리 (26.3 바이트코드로 확인)</h2>
 * <p>{@code Player.canEat(boolean canAlwaysEat)} 하나다. 몸은 이렇게 생겼다.
 *
 * <pre>{@code return this.abilities.invulnerable || canAlwaysEat || this.foodData.needsFood();}</pre>
 *
 * <p>부르는 곳은 두 군데뿐이다(게임 jar 전체에서 {@code canEat} 을 찾아 확인했다).
 * <ul>
 *   <li>{@code Consumable.canConsume} — 음식 아이템 전부. {@code FoodProperties} 가 있으면
 *       {@code player.canEat(food.canAlwaysEat())} 를 묻는다.</li>
 *   <li>{@code CakeBlock.eat} — 놓인 케이크를 한 조각 먹을 때.</li>
 * </ul>
 *
 * <h2>왜 {@code needsFood} 가 아니라 {@code canEat} 인가</h2>
 * <p>{@code FoodData.needsFood} 는 {@code ServerPlayer.tickRegeneration} 도 본다. 평화로움 난이도에서
 * 「배가 덜 찼으면 10틱마다 허기 +1」 하는 자리라, 그쪽을 참으로 만들면 평화로움에서 허기가
 * 끝없이 차오른다. 먹기만 바꾸려면 {@code canEat} 의 답만 바꿔야 한다.
 *
 * <h2>재정의 함정</h2>
 * <p>{@code canEat(Z)Z} 는 {@code Player} 에만 있고 {@code ServerPlayer}·{@code LocalPlayer} 가
 * 재정의하지 않는다(javap 로 확인). {@code FoodCapacityTargetTest} 가 서버 쪽을 못박아 둔다.
 *
 * <p>클라이언트에서도 같은 판정이 돈다 — 먹는 동작은 클라이언트가 먼저 시작하므로, 클라이언트가
 * 거짓이라 답하면 서버가 받아 줘도 손이 움직이지 않는다. 클라이언트 쪽 답은
 * {@link PerkFoodCapacity#capacityOf(net.minecraft.world.entity.Entity)} 가 서버가 내려 준 값으로 낸다.
 */
@Mixin(Player.class)
public abstract class PlayerCanEatMixin {
	@ModifyReturnValue(method = "canEat", at = @At("RETURN"))
	private boolean sharedfate$eatWhenFull(boolean original) {
		if (original) {
			return true;
		}
		return PerkFoodCapacity.eatsWhenFull(PerkFoodCapacity.capacityOf((Player) (Object) this));
	}
}
