package com.sharedfate.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 상태이상 인스턴스 밑에 숨은 층을 읽는다.
 *
 * <p>바닐라는 더 센 효과가 덮이면 약하지만 더 오래가는 쪽을 {@code hiddenEffect} 에 숨겨 두고,
 * 센 쪽이 끝나면 그것을 꺼낸다. 증강 상시분이 포션 밑에 숨어 있는지 알아야
 * {@link com.sharedfate.perk.PerkStatusStack} 이 남의 층을 건드리지 않고 다시 쌓을 수 있다.
 */
@Mixin(MobEffectInstance.class)
public interface MobEffectInstanceAccessor {
	@Accessor("hiddenEffect")
	MobEffectInstance sharedfate$getHiddenEffect();
}
