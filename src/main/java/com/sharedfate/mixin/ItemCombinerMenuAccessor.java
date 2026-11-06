package com.sharedfate.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ItemCombinerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 모루·대장장이 작업대·숫돌이 함께 쓰는 메뉴의 주인. 바닐라 필드는 {@code protected} 라 밖에서 못 읽는다. */
@Mixin(ItemCombinerMenu.class)
public interface ItemCombinerMenuAccessor {
	@Accessor("player")
	Player sharedfate$player();
}
