package com.sharedfate.mixin;

import com.sharedfate.perk.DragonPrep;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「마르지 않는 화살통」의 화살 쪽 — 엔드에서 일반 화살({@code minecraft:arrow})이 줄지 않는다
 * (0.44.0-dev). 판정은 {@link DragonPrep#endlessArrow} 에 있다.
 *
 * <h2>무한 인챈트와 같은 길을 탄다 — 그래서 복제가 없다</h2>
 * <p>26.3 {@code ProjectileWeaponItem.useAmmo(weapon, ammo, shooter, intangible)} 는 「쓸 개수」가 0 이면
 * 탄을 한 개 <b>복사</b>하고 {@code minecraft:intangible_projectile} 을 붙여 돌려준다 — 무한 인챈트가 그
 * 길이다. 그 표시가 붙은 화살은 박혀도 <b>주울 수 없다</b>(창작 모드만). 여기서도 똑같이 복사본에 그 표시를
 * 붙여 돌려주므로, 인벤토리의 화살은 그대로이고 쏜 화살을 주워 늘리는 일이 생기지 않는다. 석궁의 장전
 * ({@code CrossbowItem} → {@code draw} → {@code useAmmo})도 같은 길이다.
 *
 * <p>촉 화살 · 분광 화살 · 폭죽은 {@code Items.ARROW} 가 아니라 그대로 줄어든다.
 *
 * <p>화살이 하나도 없을 때 쏠 수 있게 하는 쪽은 {@link EndlessQuiverPlayerMixin} 이다.
 *
 * <h2>대상</h2>
 * <p>{@code protected static ItemStack useAmmo(ItemStack, ItemStack, LivingEntity, boolean)}(26.3
 * {@code javap -p}). 시험이 그 모양을 못박는다.
 */
@Mixin(ProjectileWeaponItem.class)
public abstract class EndlessQuiverMixin {

	@Inject(method = "useAmmo(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;"
			+ "Lnet/minecraft/world/entity/LivingEntity;Z)Lnet/minecraft/world/item/ItemStack;",
			at = @At("HEAD"), cancellable = true)
	private static void sharedfate$endlessArrow(ItemStack weapon, ItemStack ammo, LivingEntity shooter,
			boolean intangible, CallbackInfoReturnable<ItemStack> callback) {
		if (intangible || !(shooter instanceof Player player) || !DragonPrep.endlessArrow(player, ammo)) {
			return;
		}
		ItemStack copy = ammo.copyWithCount(1);
		copy.set(DataComponents.INTANGIBLE_PROJECTILE, Unit.INSTANCE);
		callback.setReturnValue(copy);
	}
}
