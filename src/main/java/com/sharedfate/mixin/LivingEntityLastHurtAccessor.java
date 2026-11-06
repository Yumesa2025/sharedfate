package com.sharedfate.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 무적시간 안의 피해 비교에 쓰는 {@code lastHurt}. 「한 번 더」가 넣고 나서 되돌린다. */
@Mixin(LivingEntity.class)
public interface LivingEntityLastHurtAccessor {
	@Accessor("lastHurt")
	float sharedfate$getLastHurt();

	@Accessor("lastHurt")
	void sharedfate$setLastHurt(float value);
}
