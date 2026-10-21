package com.sharedfate.mixin;

import com.sharedfate.perk.DragonPrep;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「마르지 않는 화살통」의 빈 화살통 쪽 — 엔드에서 화살이 하나도 없어도 활·석궁을 쏠 수 있다(0.44.0-dev).
 *
 * <p>26.3 {@code Player.getProjectile(weapon)} 는 손 · 인벤토리에서 탄을 못 찾으면 창작 모드일 때만
 * {@code new ItemStack(Items.ARROW)} 를 돌려준다. 그 자리에서 빈 값이 나가려 할 때 화살통이 켜져 있으면
 * 같은 일반 화살 하나를 돌려준다. 그 화살은 인벤토리에 없는 새 묶음이고, 실제로 쏠 때는
 * {@link EndlessQuiverMixin} 이 주울 수 없는 복사본으로 바꾼다.
 *
 * <p>이 판정은 <b>클라이언트에서도 같아야 한다</b> — 활을 당기기 시작하는 것은 클라이언트가 먼저 이 메서드를
 * 묻고 정한다. 그래서 서버는 팀 상태로, 클라이언트는 서버가 보낸 표시({@code PerkClientFeaturesPayload}
 * 의 화살통 칸)로 답한다({@link DragonPrep#quiverActive}).
 *
 * <h2>대상</h2>
 * <p>{@code public ItemStack getProjectile(ItemStack)}(26.3 {@code javap -p}). 시험이 그 모양을 못박는다.
 */
@Mixin(Player.class)
public abstract class EndlessQuiverPlayerMixin {

	@Inject(method = "getProjectile(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;",
			at = @At("RETURN"), cancellable = true)
	private void sharedfate$arrowFromNothing(ItemStack weapon, CallbackInfoReturnable<ItemStack> callback) {
		if (!callback.getReturnValue().isEmpty() || weapon == null
				|| !(weapon.getItem() instanceof ProjectileWeaponItem)) {
			return;
		}
		if (DragonPrep.quiverActive((Player) (Object) this)) {
			callback.setReturnValue(new ItemStack(Items.ARROW));
		}
	}
}
