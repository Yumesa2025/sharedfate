package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.sharedfate.perk.PerkSmelting;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 화로·용광로·훈연기가 굽는 데 걸리는 시간을 줄인다. 세트 「개척 2」의 효과 절반이다.
 *
 * <p>규칙과 계산은 {@link PerkSmelting} 에 있다. 여기는 「어디서 줄이는가」만 정한다.
 *
 * <h2>세 종류를 왜 따로 안 잡는가</h2>
 * <p>{@code FurnaceBlockEntity}·{@code BlastFurnaceBlockEntity}·{@code SmokerBlockEntity} 셋
 * 모두 {@code AbstractFurnaceBlockEntity} 를 물려받고, 굽는 시간을 재는 코드는 그 부모에만
 * 있다. 세 자식은 {@code getTotalCookTime} 을 재정의하지 않는다 — 26.3 의 세 클래스에는 그
 * 이름의 메서드가 아예 없다. 그래서 부모 하나를 잡으면 셋이 함께 바뀐다.
 *
 * <h2>대상을 어떻게 확인했는가</h2>
 * <p>{@code sharedfate.mixins.json} 에는 refmap 이 없어 <b>대상 서술자가 틀려도 빌드는
 * 통과한다.</b> 그래서 26.3 바이트코드를 직접 읽어 확인했다.
 *
 * <pre>{@code
 * javap -p -c net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.class
 *
 * private static int getTotalCookTime(RecipeHolder<? extends AbstractCookingRecipe>,
 *                                     AbstractFurnaceBlockEntity);
 *      0: aload_0
 *      1: invokevirtual  // RecipeHolder.value()
 *      7: invokevirtual  // AbstractCookingRecipe.cookingTime()I
 *     10: istore_2
 *     11: aload_1
 *     12: getfield       // Field speedMultiplier:F     ← 연료가 주는 바닐라 가속
 *     15: fconst_0 / fcmpl / ifle 35
 *     20: iload_2 / i2f / getfield speedMultiplier / fdiv
 *     27: f2d / Math.ceil / d2i                         ← 나눈 뒤 올림
 *     36: ireturn
 *
 * private static int getTotalCookTime(ServerLevel, AbstractFurnaceBlockEntity);
 *     …  quickCheck.getRecipeFor(...).map(this::lambda$getTotalCookTime$0).orElse(200)
 * }</pre>
 *
 * <p>여기서 두 가지가 확인된다.
 * <ul>
 *   <li>{@code ServerLevel} 짜리는 {@code Optional.map} 을 거쳐 <b>결국 위쪽 하나를 부른다</b>
 *       ({@code lambda$getTotalCookTime$0} 이 그 다리다). 즉 위쪽 하나만 잡으면 두 경로가 모두
 *       걸린다. 레시피가 없어 {@code orElse(200)} 으로 빠지는 경우는 굽고 있는 것이 없다는
 *       뜻이라 줄여 봐야 의미가 없다.</li>
 *   <li>바닐라도 「나눈 뒤 올림」으로 줄인다. {@link PerkSmelting#reducedCookTime} 이 같은 셈을
 *       쓰는 것은 그래서다. 우리 배율은 그 결과에 <b>이어서</b> 걸리므로 연료가 주는 가속과
 *       자연히 곱해진다.</li>
 * </ul>
 *
 * <h2>연료는 평소대로 탄다</h2>
 * <p>여기서는 {@code getBurnDuration} 을 건드리지 않는다. 그래서 같은 연료로 두 배를 굽는다.
 * 그렇게 정한 근거는 {@link com.sharedfate.perk.effect.SmeltingSpeedEffect} 에 적어 두었다.
 *
 * <h2>서버가 없으면 아무 일도 하지 않는다</h2>
 * <p>클라이언트 월드에서도 이 메서드가 불릴 수 있다({@code loadAdditional} 등). 그때
 * {@code getServer()} 가 {@code null} 이고 {@link PerkSmelting} 이 {@code 1.0} 을 돌려주므로
 * 값이 그대로 나간다. 서버가 정한 굽는 시간은 어차피 {@code DATA_COOKING_TOTAL_TIME} 으로
 * 내려가므로 화면이 어긋나지 않는다.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class AbstractFurnaceCookTimeMixin {
	@ModifyReturnValue(
			method = "getTotalCookTime(Lnet/minecraft/world/item/crafting/RecipeHolder;"
					+ "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;)I",
			at = @At("RETURN"))
	private static int sharedfate$shortenCookTime(int cookTime,
			RecipeHolder<? extends AbstractCookingRecipe> recipe,
			AbstractFurnaceBlockEntity furnace) {
		Level level = furnace == null ? null : furnace.getLevel();
		if (level == null || level.isClientSide()) {
			return cookTime;
		}
		return PerkSmelting.reducedCookTime(cookTime,
				PerkSmelting.speedMultiplier(level.getServer()));
	}
}
