package com.sharedfate.mixin;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import it.unimi.dsi.fastutil.ints.IntList;

@Mixin(AbstractContainerMenu.class)
public interface AbstractContainerMenuAccessor {
	@Invoker("addSlot")
	Slot sharedfate$invokeAddSlot(Slot slot);

	/**
	 * 데이터 칸을 하나 더 단다.
	 *
	 * <p>메뉴가 값 하나를 클라이언트로 계속 내려보내는 통로다. 붙이는 <b>순서와 개수가
	 * 양쪽에서 같아야</b> 하므로, 서버에만 붙이거나 조건에 따라 붙이면 안 된다.
	 */
	@Invoker("addDataSlot")
	DataSlot sharedfate$invokeAddDataSlot(DataSlot dataSlot);

	/**
	 * 서버가 「클라이언트에 마지막으로 보낸」 데이터 칸 값들.
	 *
	 * <p>값을 지워 두면 다음 {@code broadcastChanges} 가 지금 값을 다시 보낸다. 클라이언트가 팀을 몰라
	 * 같은 칸을 제멋대로 다시 계산한 자리(모루의 비용)를 서버 값으로 되돌릴 때 쓴다.
	 */
	@Accessor("remoteDataSlots")
	IntList sharedfate$remoteDataSlots();
}
