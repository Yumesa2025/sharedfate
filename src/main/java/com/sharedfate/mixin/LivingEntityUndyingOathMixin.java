package com.sharedfate.mixin;

import com.sharedfate.perk.DragonPrep;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「불사의 맹세」가 <b>한 대에 죽는 피해</b>를 막는 자리(0.44.0-dev). 판정은 {@link DragonPrep} 에 있다.
 *
 * <h2>왜 토템 판정의 끝인가 — 우선순위</h2>
 * <p>26.3 {@code LivingEntity.hurtServer} 는 체력이 0 이 되면 {@code checkTotemDeathProtection(source)} 를
 * 묻고, 거짓일 때만 {@code die} 로 간다. 이 판정의 <b>끝({@code RETURN})</b>에 붙어 바닐라가 거짓을 냈을
 * 때만 맹세를 쓴다. 그래서 순서가 이렇다.
 * <ol>
 *   <li>피해를 받기 전에 끊는 것들 — 시간 정지 · 증강 무적 · 「호위」 · 「완충」(미뤄서 나눠 넣음).
 *       피해가 아예 안 들어오므로 맹세까지 오지 않는다</li>
 *   <li><b>손에 든 불사의 토템</b> — 바닐라가 먼저 쓴다. 맹세는 아껴진다</li>
 *   <li><b>불사의 맹세</b> — 토템이 없을 때 마지막으로</li>
 * </ol>
 * <p>무적을 지나는 피해(공허 · {@code /kill})는 토템과 마찬가지로 막지 않는다.
 *
 * <p>공유 풀이 여럿의 피해를 합쳐 0 이 되는 길은 이 자리를 지나지 않는다 — 그쪽은 {@code StatMirror.tick}
 * 이 팀을 죽이기 직전에 같은 판정을 묻는다.
 *
 * <h2>대상</h2>
 * <p>26.3 {@code javap -p net.minecraft.world.entity.LivingEntity} —
 * {@code private boolean checkTotemDeathProtection(DamageSource)}. 시험이 그 모양을 못박는다.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityUndyingOathMixin {

	@Inject(method = "checkTotemDeathProtection(Lnet/minecraft/world/damagesource/DamageSource;)Z",
			at = @At("RETURN"), cancellable = true)
	private void sharedfate$undyingOath(DamageSource source, CallbackInfoReturnable<Boolean> callback) {
		if (callback.getReturnValueZ()) {
			return;
		}
		if ((Object) this instanceof ServerPlayer player && DragonPrep.tryUndyingOath(player, source)) {
			callback.setReturnValue(true);
		}
	}
}
