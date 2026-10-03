package com.sharedfate.mixin;

import com.sharedfate.sync.DragonLastStandShield;
import com.sharedfate.sync.DragonPerch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 드래곤이 맞는 단 하나의 문에서 <b>두 가지</b>를 거절한다.
 *
 * <ol>
 *   <li><b>최후의 저항 진입 보호막</b> — 진입부터 첫 패턴 직전까지 <b>모든</b> 피해를 거절한다
 *       ({@code DragonLastStandShield}). 사람이 2026-10-04 에 <b>「최후의 저항 시작하고 첫 패턴
 *       전까지 모든 공격 막는 쉴드 생기고 피해 안 받게. 지금은 무슨 체력회복하면서 쳐맞는 거
 *       같아」</b>라고 했다</li>
 *   <li><b>착지 중 원거리 면역</b> — 포디움에 내려앉은 드래곤은 <b>근접 피해만</b> 받는다.
 *       사람 말: <b>「착지햇을떄는 원거리 공격 안받고 근접공격만 데미지 들어가게 해야해」</b></li>
 * </ol>
 *
 * <p>⚠ <b>같은 자리에 믹스인을 둘 만들지 않았다.</b> 둘 다 「이 한 방을 피해 처리 전에 거절한다」
 * 이고 막힘 되먹임도 같은 한 벌이라, 처리기 둘이 같은 {@code HEAD} 를 물면 어느 쪽이 먼저 도는지가
 * 믹스인 적용 순서에 달린다. 한 처리기 안에서 <b>보호막 → 착지</b> 순서로 적어 그 순서를 코드가
 * 들게 했다. 보호막이 앞인 까닭은 처리기 안의 주석에 있다.
 *
 * <p>아래 설명의 대부분은 ②(착지)를 위해 적은 것이고, 「왜 이 메서드인가」·「{@code HEAD} 에서
 * 통째로 거절한다」는 ①에도 글자 그대로 참이다 — 부위 피해·본체 피해·크리스탈 폭발이 전부 이
 * 문을 지나고, {@code HEAD} 에서 거절하면 {@code reallyHurt} 에 닿지 않아 피격 번쩍임·피격음·
 * 넉백이 나갈 자리가 없다.
 *
 * <h2>⚠⚠ 「최후의 저항」을 거르는 것이 이 믹스인의 목숨이다</h2>
 *
 * <p>체력 30% 의 별개 보스전 「최후의 저항」은 드래곤을 {@code EnderDragonPhase.HOVERING} 으로
 * 영구히 앉혀 두고 사람이 <b>활로 115초에 1200 을 깎는</b> 설계다. 그런데 {@code HOVERING} 은
 * {@code isSitting()} 이 <b>참</b>이다. 곧 <b>「앉았는가」를 {@code isSitting()} 으로 물으면 그
 * 보스전을 이길 방법이 사라진다.</b>
 *
 * <p>그 판별은 이 파일에 없다. {@code DragonPerch} 가 앉은 칸 <b>셋만 이름으로</b> 세고
 * ({@code SITTING_SCANNING}·{@code SITTING_ATTACKING}·{@code SITTING_FLAMING}), 그 위에
 * {@code DragonTrialManager} 가 최후의 저항이 열린 틱에 깃발을 내린다. 여기는 그 깃발을 읽을
 * 뿐이다 — <b>판별을 두 곳에 두지 않는다.</b>
 *
 * <h2>왜 {@code hurt(ServerLevel, EnderDragonPart, DamageSource, float)} 인가</h2>
 *
 * <p>드래곤이 맞는 길은 <b>전부</b> 이 메서드로 모인다. 26.3 바이트코드에서 확인했다.
 *
 * <ul>
 *   <li>{@code EnderDragonPart.hurtServer} → {@code parentMob.hurt(level, this, source, amount)} —
 *       사람이 머리·몸통·날개를 때리는 길이다</li>
 *   <li>{@code EnderDragon.hurtServer} → {@code hurt(level, body, source, amount)} — 부위를
 *       거치지 않는 피해(명령·효과)를 몸통으로 보낸다</li>
 * </ul>
 *
 * <p>그래서 한 자리만 막으면 샐 데가 없다. 부모 {@code LivingEntity.hurtServer} 쪽에 걸면
 * 부위 보정(머리 ×1, 몸통 ×1/4 + 1)이 이미 끝난 뒤라 뜻이 달라지고, {@code EnderDragonPart}
 * 쪽은 {@code final} 이라 그쪽에 걸 수도 있지만 <b>두 길 중 하나만</b> 덮는다.
 *
 * <p>{@code EnderDragon} 은 26.3 의 두 jar 어디에서도 상속되지 않고, 이 {@code hurt} 는
 * {@code EnderDragon} 이 스스로 선언한다. 「재정의되는 메서드에 걸면 조용히 죽는다」는 함정에
 * 걸리지 않는다.
 *
 * <p>⚠ {@code EnderDragon} 에는 {@code hurt} 가 <b>둘</b>이다 —
 * 여기서 노리는 것과 {@code hurt(ServerLevel, List)}(드래곤이 <b>사람을</b> 때리는 길,
 * {@code EnderDragonContactDamageMixin} 의 대상). 이름만 적으면 어느 쪽인지 알 수 없으므로
 * <b>서술자를 통째로</b> 적는다.
 *
 * <h2>{@code HEAD} 에서 통째로 거절한다</h2>
 *
 * <p>값을 깎는 훅이 아니라 거절이다. {@code false} 는 바닐라가 「무적이라 안 맞았다」를 말할 때
 * 쓰는 값 그대로이고({@code DYING} 일 때의 첫 줄이 그렇다), 앉은 칸이 화살을 0 으로 만들 때도
 * 결국 {@code amount < 0.01} 로 같은 {@code false} 가 나간다. 곧 <b>바닐라가 이미 쓰는 답</b>이다.
 *
 * <p>무엇을 통과시키고 무엇을 막는지는 {@code DragonPerch.melee} 한 곳에 있다 — 허용 목록이고,
 * {@code #minecraft:is_projectile} 을 쓰지 않은 까닭(쇠뇌 폭죽·스플래시 물약·TNT 가 그 태그에
 * 없다)도 그쪽에 적어 두었다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>서술자가 틀려도 빌드는 통과한다. {@code injectors.defaultRequire} 가 1 이라 붙이는 순간
 * 터지기는 하지만 조용한 쪽이 아니라고 믿지 말 것 — {@code DragonPerchTest} 가 서술자를 반사로
 * 못박는다.
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonPerchRangedImmunityMixin {

	/**
	 * 진입 보호막이 서 있으면 모든 한 방을, 앉아 있는 동안이면 근접이 아닌 한 방을 통째로
	 * 거절한다.
	 *
	 * <p>두 판별 모두 첫 줄이 {@code volatile boolean} 한 번 읽기다. 드래곤은 차원에 하나뿐이고
	 * 시련을 켠 전투에서만 켜지므로, 거의 모든 피격에서 그 두 줄이 비용의 전부다.
	 *
	 * <p>⚠ 처리기 이름은 옛 이름 그대로 둔다. {@code DragonPerchTest} 가 상수 풀을 뒤져 이 클래스를
	 * 보므로 이름을 바꿔도 시험은 안 깨지지만, 이름이 남아 있는 편이 「이 처리기가 원래 무엇이었나」를
	 * 잃지 않는다.
	 */
	@Inject(
			method = "hurt(Lnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;"
					+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$refuseRangedWhilePerched(ServerLevel level, EnderDragonPart part,
			DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
		// ① 최후의 저항 진입 보호막 — 근접이든 원거리든 전부 거절한다(무적을 지나치는 피해만
		// 통과). 착지 판별보다 앞이다: 보호막이 서 있는 동안 착지 깃발은 이미 내려가 있지만,
		// 순서가 뒤집히면 근접 한 방이 아래 「melee 면 통과」로 빠져나간다.
		if (DragonLastStandShield.refuses(level, source)) {
			DragonLastStandShield.deflect(level, part == null ? null : part.position());
			callback.setReturnValue(false);
			return;
		}
		// ② 착지 중 원거리 면역.
		if (!DragonPerch.rangedImmune()) {
			return;
		}
		if (DragonPerch.melee(source)) {
			return;
		}
		// 아무 반응 없이 0 이 들어가면 사람은 「막혔다」가 아니라 「버그다」로 읽는다. 맞은
		// 부위에서 튕긴다 — 몸 중심에서 내면 날개 끝을 맞춘 화살이 엉뚱한 데서 튕긴다.
		DragonPerch.deflect(level, part == null ? null : part.position());
		callback.setReturnValue(false);
	}
}
