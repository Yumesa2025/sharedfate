package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.sync.EndBedCollapse;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 엔드에서 침대가 <b>터지지 않게</b> 한다. 침대 자신은 그대로 사라진다.
 *
 * <p>사람 말: <b>「엔더에서 침대 터뜨렷을떄 터지지않게 하자 대신 침대가 아에 파괴되게 터지는딜
 * 없애버리게」</b>
 *
 * <p>판정과 연출은 전부 {@link EndBedCollapse} 에 있다. 26.3 바이트코드를 읽어 적은 갈래와
 * 소리 고른 근거({@code sounds.json} 에서 확인한 실제 파일)도 그쪽 주석에 있다.
 *
 * <h2>왜 {@code BedBlock} 이고 {@code AbstractBedBlock} 이 아닌가</h2>
 *
 * <p>⚠ <b>재정의되는 메서드에 걸면 조용히 죽는다</b>(이 저장소가 겪었다). 26.3 의
 * {@code AbstractBedBlock.destroyOnUse} 는 <b>{@code abstract}</b> 이고
 * {@code BedBlock} 과 {@code StrawBedBlock} 이 각각 재정의한다 — 거기 걸면 아무 일도
 * 일어나지 않는다. 폭발은 {@code BedBlock} 의 구현체 <b>안</b>에 있다.
 *
 * <p>거꾸로 {@code BedBlock.destroyOnUse} 는 <b>아무도 재정의하지 않는다.</b> {@code javap}
 * 로 26.3 의 {@code net/minecraft/world/level/block/} 전부를 뒤져 {@code BedBlock} 을
 * 상속하는 클래스가 하나도 없음을 확인했다({@code StrawBedBlock} 과 {@code FlowerBedBlock} 은
 * 형제이거나 남이다). 색이 다른 열여섯 침대는 전부 <b>{@code BedBlock} 인스턴스</b>이고
 * {@code DyeColor} 만 다르다.
 *
 * <h2>폭발하는 길이 하나뿐인 까닭</h2>
 *
 * <p>⚠ 「한쪽만 막으면 반드시 샌다」가 이 저장소의 제1 함정이라 세 겹으로 확인했다.
 *
 * <ul>
 *   <li>26.3 jar 전체에서 {@code badRespawnPointExplosion} 을 부르는 클래스는
 *       {@code BedBlock} 과 {@code RespawnAnchorBlock} <b>둘뿐</b>이다 — 앵커는 자기만의
 *       {@code private explode} 를 가진 <b>다른 길</b>이고 사람이 말한 것은 침대뿐이다</li>
 *   <li>{@code destroyOnUse} 를 부르는 자리는 {@code AbstractBedBlock.useWithoutItem}
 *       <b>한 군데</b>다. 그래서 침대를 「쓰는」 길이 몇이든 폭발은 반드시 여기를 지난다</li>
 *   <li>디스펜서에는 침대 동작이 없다({@code DispenseItemBehavior} 에 침대가 없다).
 *       {@code useWithoutItem} 을 부르는 것은 {@code ServerPlayerGameMode.useItemOn} 과
 *       {@code GameTestHelper} 뿐이다 — 명령이든 다른 모드든 결국 같은 호출을 지난다</li>
 * </ul>
 *
 * <p>곧 <b>호출을 지우는 것이 아니라 호출 자리를 감싸는</b> 것이 맞다. 그 자리는 하나이고,
 * 그 하나를 지나지 않고 침대가 터지는 길은 26.3 에 없다.
 *
 * <h2>왜 {@code @WrapOperation} 인가</h2>
 *
 * <p>고를 수 있던 것은 셋이었다.
 *
 * <ul>
 *   <li><b>{@code @Inject(cancellable)} 로 메서드를 통째로 가로채기</b> — 폭발뿐 아니라
 *       <b>블록 지우기까지</b> 삼킨다. 그러면 우리가 다시 지워야 하고, 그것은 「블록을 부수지
 *       마라」를 우리 손으로 어기는 꼴이다. 버렸다</li>
 *   <li><b>{@code @Redirect}</b> — 되긴 하지만 「삼킨다」와 「흘린다」를 한 자리에서
 *       고르기가 어색하다. 네더로 흘릴 때 인자 일곱 개를 손으로 다시 늘어놓아야 하고,
 *       그 늘어놓기가 틀려도 빌드는 조용하다</li>
 *   <li><b>{@code @WrapOperation}</b> — 뽑았다. {@code Operation} 을 부르면 바닐라가 그대로
 *       돌고 안 부르면 사라진다. <b>네더 쪽 갈래가 「원래 것을 부른다」 한 줄</b>이라 손으로
 *       옮겨 적을 인자가 없다. 이 저장소가 이미 쓰는 길이기도 하다
 *       ({@code ExpandedQuickMoveFallbackMixin} · {@code EnchantmentMenuDiamondMixin})</li>
 * </ul>
 *
 * <p>{@code destroyOnUse} 안의 {@code explode} 호출은 <b>하나뿐</b>이라 {@code ordinal} 을
 * 적지 않는다. 적으면 바닐라가 줄을 하나 더 넣는 날 엉뚱한 것을 감싼다.
 */
@Mixin(BedBlock.class)
public abstract class BedBlockEndBlastMixin {
	/**
	 * 엔드면 폭발을 삼키고, 아니면 바닐라를 그대로 흘린다.
	 *
	 * <p>인자 이름은 26.3 바이트코드에서 읽은 실제 값 그대로다 — {@code source} 는
	 * {@code null}, {@code calculator} 도 {@code null}, {@code radius} 는 {@code 5.0F},
	 * {@code fire} 는 {@code true}, {@code interaction} 은 {@code BLOCK} 이다. 셋이 한
	 * 호출에 들어 있으므로 안 부르면 피해·블록 파괴·화염이 함께 사라진다.
	 */
	@WrapOperation(
			method = "destroyOnUse",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;explode("
							+ "Lnet/minecraft/world/entity/Entity;"
							+ "Lnet/minecraft/world/damagesource/DamageSource;"
							+ "Lnet/minecraft/world/level/ExplosionDamageCalculator;"
							+ "Lnet/minecraft/world/phys/Vec3;FZ"
							+ "Lnet/minecraft/world/level/Level$ExplosionInteraction;)V"))
	private void sharedfate$swallowEndBedExplosion(Level level, @Nullable Entity source,
			DamageSource damage, @Nullable ExplosionDamageCalculator calculator, Vec3 at,
			float radius, boolean fire, Level.ExplosionInteraction interaction,
			Operation<Void> original) {
		EndBedCollapse.explode(level, at, () -> original.call(level, source, damage, calculator,
				at, radius, fire, interaction));
	}
}
