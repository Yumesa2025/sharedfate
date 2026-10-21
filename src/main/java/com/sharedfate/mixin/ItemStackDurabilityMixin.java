package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.sharedfate.perk.PerkGearRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * {@code durability_multiplier} 의 집행 지점. 정해진 장비가 천천히 닳게 한다.
 *
 * <h2>왜 여기인가</h2>
 * <p>내구도가 닳는 길은 겉보기에 여럿이다 — 블록을 캐고, 몹을 때리고, 맞아서 방어구가 닳고,
 * 낚싯대·활을 쓰고. 그런데 26.2 바이트코드로 따라가 보면 그 길이 전부
 * {@code ItemStack.processDurabilityChange(int, ServerLevel, ServerPlayer)} 한 곳으로 모인다.
 * 아래가 확인한 내용이다({@code javap -p -c} 로 26.2 공통 jar 을 읽었다).
 *
 * <pre>
 * hurtAndBreak(int, LivingEntity, InteractionHand)
 *     → hurtAndBreak(int, LivingEntity, EquipmentSlot)
 * hurtAndConvertOnBreak(int, ItemLike, LivingEntity, EquipmentSlot)
 *     → hurtAndBreak(int, LivingEntity, EquipmentSlot)
 * hurtAndBreak(int, LivingEntity, EquipmentSlot)
 *     → hurtAndBreak(int, ServerLevel, ServerPlayer, Consumer&lt;Item&gt;)
 *         → processDurabilityChange(int, ServerLevel, ServerPlayer)   ← 여기
 * hurtWithoutBreaking(int, Player)
 *     → processDurabilityChange(int, ServerLevel, ServerPlayer)       ← 여기
 * </pre>
 *
 * <p>그 메서드는 <b>실제로 깎을 내구도를 돌려주는 순수한 계산</b>이다. 크리에이티브인지 보고,
 * 내구도가 있는 아이템인지 보고, 내구성 마법을 태워 값을 줄인 뒤 그 수를 돌려준다. 부르는 쪽은
 * 그 수가 0 이면 아무 일도 하지 않는다. 그러니 <b>돌아가는 수만 깎으면</b> 손상 기록도, 부서짐
 * 판정도, 발전 과제 알림도 전부 바닐라 계산이 알아서 따라온다. 내구성 마법 계산 뒤에 걸리므로
 * 마법과 증강은 자연스럽게 곱해진다.
 *
 * <h2>아이템에는 아무 흔적도 남지 않는다</h2>
 * <p>매번 지금 팀 상태를 새로 물어보므로, 증강이 사라지면 <b>다음 한 번부터</b> 바닐라와
 * 똑같이 닳는다.
 *
 * <h2>사람이 아니면 손대지 않는다</h2>
 * <p>{@code player} 가 {@code null} 인 호출은 몹이 든 장비가 닳는 경우다. 팀을 알 수 없고
 * 알 필요도 없으므로 바닐라 값을 그대로 돌려준다.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackDurabilityMixin {

	@ModifyReturnValue(
			method = "processDurabilityChange(ILnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/server/level/ServerPlayer;)I",
			at = @At("RETURN")
	)
	private int sharedfate$slowDurabilityLoss(int amount, int requested, ServerLevel level,
			ServerPlayer player) {
		if (amount <= 0 || player == null || level == null) {
			return amount;
		}
		ItemStack self = (ItemStack) (Object) this;
		// 「무뎌지지 않는 갑주」(0.44.0-dev) — 방어구·방패는 아예 닳지 않는다. 난수도 쓰지 않는다.
		if (com.sharedfate.perk.DragonPrep.keepsDurability(PerkGearRules.activeState(player), self)) {
			return 0;
		}
		return PerkGearRules.reduceDurabilityLoss(
				PerkGearRules.activeState(player), self, amount, level.getRandom());
	}
}
