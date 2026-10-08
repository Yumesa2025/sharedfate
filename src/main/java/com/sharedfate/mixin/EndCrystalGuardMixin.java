package com.sharedfate.mixin;

import com.sharedfate.sync.CrystalWatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 엔드 크리스탈이 <b>맞는 순간</b>에 끼어드는 두 자리.
 *
 * <ol>
 *   <li>「크리스탈 보호막」이 켜져 있으면 <b>투사체 피해를 거절</b>한다.</li>
 *   <li>누가 깼는지 {@link CrystalWatch#noteBreaker} 에 적는다 — 「표적」 카드가 읽는다.</li>
 * </ol>
 *
 * <h2>왜 {@code EndCrystal.hurtServer} 인가 — 재정의 함정이 없는 자리다</h2>
 *
 * <p>이 저장소는 전에 {@code SlotExpandedLockMixin} 을 {@code Slot.mayPlace} 에 걸었다가
 * <b>하위 클래스가 재정의해 한 번도 실행되지 않은</b> 사고를 냈다. 빌드도 통과했고 로그도
 * 조용했다. 그래서 자리를 고르기 전에 26.3 바이트코드를 먼저 읽었다.
 *
 * <pre>{@code
 * javap -p net/minecraft/world/entity/boss/enderdragon/EndCrystal.class
 *
 * public class EndCrystal extends Entity {
 *   public final boolean hurtServer(ServerLevel, DamageSource, float);   ← final 이다
 *   private void onDestroyedBy(ServerLevel, DamageSource);
 * }
 * }</pre>
 *
 * <p>둘이 동시에 성립한다 — <b>{@code EndCrystal} 이 스스로 선언</b>하고(상속만 받는 것이
 * 아니다), 게다가 <b>{@code final}</b> 이라 하위 클래스가 재정의할 길이 아예 없다. 바닐라에
 * {@code EndCrystal} 을 상속하는 클래스는 없고, 설령 누군가 만들어도 이 메서드는 가로챌 수
 * 없다. 부모 {@code Entity.hurtServer} 쪽에 걸었다면 정확히 옛 사고를 되풀이했을 것이다.
 *
 * <h2>피해량이 없는 메서드다 — 그래서 훅이 {@code HEAD} 다</h2>
 *
 * <p>{@code hurtServer} 는 피해량을 <b>쓰지 않는다.</b> 통과하면 곧바로
 * {@code remove(RemovalReason.KILLED)} 로 즉사시키고 폭발을 만든다. 즉 「깎을지 말지」가
 * 아니라 「죽일지 말지」뿐이라, 값을 줄이는 훅({@code @ModifyVariable} 따위)은 뜻이 없다.
 * 막으려면 <b>들어가기 전에 통째로 거절</b>해야 하므로 {@code HEAD} 에서
 * {@code setReturnValue(false)} 한다. {@code false} 는 바닐라가 「무적이라 안 맞았다」를
 * 말할 때 쓰는 값 그대로다.
 *
 * <h2>근접과 폭발은 반드시 통과시킨다</h2>
 *
 * <p>거절하는 것은 {@code #minecraft:is_projectile} 뿐이다. 26.3 에서 그 태그는 화살·삼지창·
 * 몹 투사체·화염구·위더 해골·던진 것·바람 charge 여덟이다. <b>근접과 폭발까지 막으면
 * 크리스탈을 깰 방법이 아예 없어져 전투가 끝나지 않는다</b> — 이 모드는 전멸하면 월드가
 * 지워지므로 「깰 수 없는 크리스탈」은 카드가 아니라 사고다. 카드의 뜻은 「활로는 안 되니
 * 올라가라」이지 「부수지 마라」가 아니다.
 *
 * <h2>깬 사람은 {@code onDestroyedBy} 를 부르기 직전에 적는다</h2>
 *
 * <p>{@code HEAD} 에서 적으면 <b>무적이라 튕겨 나간 피격</b>과 <b>이미 죽은 크리스탈을 다시
 * 때린 피격</b>까지 세어 버린다. {@code RETURN} 에서 적으면 「이미 없어진 크리스탈」도
 * {@code true} 를 돌려주므로 역시 구별이 안 된다. {@code onDestroyedBy} 호출은 <b>이번 한
 * 방이 정말로 깼을 때에만</b> 지나는 유일한 지점이다.
 *
 * <h2>쏜 사람을 찾는 법 — {@code getEntity()} 와 {@code getDirectEntity()} 는 다르다</h2>
 *
 * <p>화살이면 {@code getDirectEntity()} 는 <b>화살</b>이고 {@code getEntity()} 가 <b>쏜
 * 사람</b>이다. 직접 원인을 그대로 쓰면 「화살이 크리스탈을 깼다」가 되어 표적이 영영 정해지지
 * 않는다. 순서는 {@code VictoryTeamResolver} 가 이미 푼 것을 그대로 따른다.
 *
 * <ol>
 *   <li>{@code source.getEntity()} 가 사람 — 근접도 화살도 대개 여기서 끝난다.</li>
 *   <li>{@code Explosion.getIndirectSourceEntity(getDirectEntity())} 가 사람 — 소유자가
 *       {@code causingEntity} 로 실리지 않은 TNT·투사체를 한 단계 푼다. 바닐라가 폭발 피해
 *       원인을 만들 때 쓰는 바로 그 함수라 뜻이 어긋날 수 없다.</li>
 * </ol>
 *
 * <p>둘 다 비면 {@code null} 이고, {@link CrystalWatch#noteBreaker} 가 <b>그때는 기록을
 * 건드리지 않는다.</b> 크리스탈 하나가 터지면 옆 것이 연쇄로 딸려 가는데, 그 연쇄를
 * 「주인 없음」으로 덮어쓰면 방금 정해진 표적이 사라지기 때문이다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>{@code sharedfate.mixins.json} 에 refmap 이 없어 <b>대상 서술자가 틀려도 빌드가 그냥
 * 통과</b>하고 발화 시점에 터진다. 위 세 가지(메서드 서술자·{@code final}·
 * {@code onDestroyedBy} 호출)를 {@code TrialCrystalGuardTest} 가 반사와 클래스 파일로
 * 못박는다.
 */
@Mixin(EndCrystal.class)
public abstract class EndCrystalGuardMixin {

	/**
	 * 「크리스탈 보호막」이 켜져 있으면 투사체 피해를 거절한다.
	 *
	 * <p>첫 줄이 {@code volatile boolean} 한 번 읽기다. 이 카드가 뜨지 않은 판에서는 그 한
	 * 줄이 비용의 전부다 — 크리스탈은 흔한 엔티티가 아니지만, 사람이 손에 들고 다니며 터뜨리는
	 * 물건이라 엔드 밖에서도 지나간다.
	 */
	@Inject(
			method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$refuseProjectiles(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> callback) {
		if (!CrystalWatch.arrowImmune()) {
			return;
		}
		if (!source.is(DamageTypeTags.IS_PROJECTILE)) {
			// 근접·폭발은 반드시 통과시킨다. 막으면 크리스탈을 깰 길이 없어진다.
			return;
		}
		// 바닐라가 무적일 때 돌려주는 값과 같다. 맞은 티가 나지 않는 것이 「보호막」의 뜻이다.
		callback.setReturnValue(false);
	}

	/**
	 * 이번 한 방이 정말로 깼을 때만 깬 사람을 적는다.
	 *
	 * <p>{@code onDestroyedBy} 는 {@code private} 이지만 {@code @At("INVOKE")} 는 호출
	 * 명령어를 찾는 것이라 접근 제어와 무관하다. 26.3 의 {@code hurtServer} 안에 이 호출은
	 * <b>정확히 하나</b>뿐이다.
	 */
	@Inject(
			method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;"
							+ "onDestroyedBy(Lnet/minecraft/server/level/ServerLevel;"
							+ "Lnet/minecraft/world/damagesource/DamageSource;)V"
			))
	private void sharedfate$noteBreaker(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> callback) {
		CrystalWatch.noteBreaker(sharedfate$breakerOf(source));
	}

	/**
	 * 이 피해의 책임자가 사람이면 그 {@code UUID}.
	 *
	 * <p>{@code VictoryTeamResolver.candidatesOf} 의 1·2단계와 같은 순서다. 거기서는 드래곤이
	 * 기억하는 마지막 가해자라는 3단계가 더 있지만, 크리스탈은 {@code Entity} 라 그런 기억을
	 * 갖지 않는다.
	 */
	@Unique
	private static @Nullable UUID sharedfate$breakerOf(DamageSource source) {
		UUID causing = sharedfate$playerId(source.getEntity());
		if (causing != null) {
			return causing;
		}
		return sharedfate$playerId(Explosion.getIndirectSourceEntity(source.getDirectEntity()));
	}

	/** 이 엔티티가 사람이면 그 {@code UUID}. 아니면 {@code null}. */
	@Unique
	private static @Nullable UUID sharedfate$playerId(@Nullable Entity entity) {
		return entity instanceof Player player ? player.getUUID() : null;
	}
}
