package com.sharedfate.mixin;

import com.sharedfate.sync.TrialDryWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「메마른 세계」({@link TrialDryWorld})가 <b>디스펜서</b>로 새는 것을 막는다.
 *
 * <h2>왜 사건으로 안 되는가</h2>
 *
 * <p>손에 든 양동이는 Fabric 사건 둘이 잡는다({@code UseItemCallback} ·
 * {@code ItemEvents.USE_ON}). 그런데 <b>디스펜서는 사람이 아니다</b> — 상호작용 사건이 하나도
 * 터지지 않는다. 26.3 의 {@code DispenseItemBehavior} 는 양동이를
 * {@code DispensibleContainerItem} 으로 캐스팅해
 * {@code emptyContents(LivingEntity, Level, BlockPos, BlockHitResult)} 를 <b>직접</b> 부르므로,
 * 그 한 자리를 잡는 것이 디스펜서를 막는 유일한 길이다. 막히면(거짓을 돌려주면) 디스펜서는
 * 유체를 놓는 대신 양동이를 그냥 뱉는다 — 물건이 사라지지 않는다.
 *
 * <h2>대상이 둘인 이유</h2>
 *
 * <ul>
 *   <li>{@link BucketItem} — 물·용암, 그리고 그것을 물려받는 {@code MobBucketItem}
 *       여섯 개(물고기·도롱뇽·올챙이)다. 그쪽 {@code emptyContents} 는 재정의돼 있지만
 *       <b>{@code super} 를 부르므로</b> 여기 넣은 것이 그대로 돈다. ⚠ 언젠가 그 재정의가
 *       {@code super} 를 안 부르게 바뀌면 이 믹스인은 <b>조용히 물고기 양동이를 놓친다</b> —
 *       판을 올릴 때 {@code MobBucketItem.emptyContents} 를 반드시 다시 볼 것</li>
 *   <li>{@link SolidBucketItem} — 서리눈이다. 이쪽은 {@link BucketItem} 을 물려받지 않아
 *       (부모가 {@code BlockItem} 이다) 따로 적어야 한다</li>
 * </ul>
 *
 * <h2>이 믹스인은 손에 든 양동이도 함께 잡는다</h2>
 *
 * <p>{@code BucketItem.use} 도 결국 {@code emptyContents} 로 내려오므로 사람이 손으로 붓는
 * 길도 여기서 한 번 더 걸린다. <b>일부러 겹쳐 둔 것이다</b> — 사건 등록 한 줄을 빠뜨려도 물은
 * 놓이지 않는다. 겹쳐서 손해 보는 것은 없다. 막는 판단이 {@link TrialDryWorld#banPlacing} 한
 * 곳에서만 나오므로 두 길이 서로 다른 답을 낼 수 없다.
 *
 * <p>사건 쪽을 그래도 두는 이유는 <b>사람에게 신호를 줄 수 있기 때문</b>이다. 여기는
 * 디스펜서가 부를 때 사람이 없어 소리를 낼 자리가 없다.
 */
@Mixin({BucketItem.class, SolidBucketItem.class})
public abstract class BucketEmptyBanMixin {

	/**
	 * 붓기 직전에 가로챈다.
	 *
	 * <p>{@code placer} 는 디스펜서가 부를 때 {@code null} 이라 보지 않는다. 판단에 필요한
	 * 것은 <b>월드의 차원</b>과 <b>아이템</b>뿐이고 둘 다 여기 있다 — 차원을 보는 덕분에
	 * 오버월드의 디스펜서는 평소대로 돈다.
	 */
	@Inject(method = "emptyContents", at = @At("HEAD"), cancellable = true)
	private void sharedfate$dryWorldStopsEmptying(LivingEntity placer, Level level, BlockPos pos,
			BlockHitResult hit, CallbackInfoReturnable<Boolean> cir) {
		if (TrialDryWorld.banPlacing(level, (Item) (Object) this)) {
			// 거짓이다. 바닐라는 이것을 「붓지 못했다」로 읽고 양동이를 그대로 남긴다.
			cir.setReturnValue(Boolean.FALSE);
		}
	}
}
