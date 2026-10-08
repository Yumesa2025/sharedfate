package com.sharedfate.mixin;

import com.sharedfate.sync.SpreadDamageManager;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「완충」이 미뤄 둔 몫을 넣을 때 <b>피격 소리를 삼킨다.</b>
 *
 * <h2>왜 필요한가</h2>
 * <p>완충은 한 번 받은 피해를 여러 초에 걸쳐 나눠 넣는다. 넣는 방법이
 * {@code hurtServer} 를 다시 부르는 것이라, 그대로 두면 <b>몫마다</b> 피격 소리가 나고 화면이
 * 붉어진다. 여덟 몫이면 여덟 번 맞은 것처럼 들려, 「완충되고 있다」가 아니라 「계속 맞고
 * 있다」로 읽힌다.
 *
 * <p>화면이 붉어지는 것({@code hurtTime}·{@code hurtDuration})은
 * {@link SpreadDamageManager} 가 값을 되돌리는 것으로 막지만, <b>소리는 그 자리에서 이미
 * 나가 버려</b> 되돌릴 수가 없다. 그래서 나가기 전에 여기서 막는다.
 *
 * <h2>⚠ 지금은 거의 쓰이지 않는 예비다</h2>
 * <p>위 두 막기는 <b>클라이언트 연출을 못 막았다.</b> 붉은 번쩍임·기울기·피격음은 서버가
 * 보내는 {@code broadcastDamageEvent} 를 받아 클라이언트가 {@code handleDamageEvent} 에서
 * 스스로 만든다. 그래서 {@code SpreadDamageManager.deliver} 가 몫을 바닐라의 「쿨타임 안 추가
 * 피해」 갈래로 넣도록 바꿨고, 그 갈래는 {@code broadcastDamageEvent} 도
 * {@code playHurtSound} 도 부르지 않는다. 여기가 실제로 도는 것은 피해 종류가
 * {@code bypasses_cooldown} 이라 바닐라가 쿨타임을 무시할 때뿐이다(26.3 바닐라의 그 태그는
 * 비어 있다). 지우지 않고 남겨 두는 것은 데이터팩이 그 태그를 채웠을 때 서버쪽 소리만이라도
 * 막기 위해서다.
 *
 * <h2>완충 몫일 때만 막는다</h2>
 * <p>{@link SpreadDamageManager#isDeliveringSlice()} 는 <b>미뤄 둔 몫을 넣는 그 순간에만</b>
 * 참이고, 큐가 하나도 없으면 첫 줄에서 곧바로 거짓이다. 이 증강을 아무도 갖고 있지 않은
 * 서버에서는 사실상 비용이 없다.
 *
 * <p><b>처음 맞은 그 한 번은 그대로 소리가 난다.</b> 가로채는 자리는 원래 피해가 아니라
 * 나중에 넣는 몫이기 때문이다 — 맞은 사실 자체는 알아야 한다.
 *
 * <h2>대상</h2>
 * <p>26.2 바이트코드로 확인했다 — {@code LivingEntity.playHurtSound(DamageSource)} 는
 * {@code protected void} 다. refmap 이 없으므로 대상이 틀리면 <b>빌드는 통과하고 실제로
 * 맞는 순간 터진다.</b> 그것을 못박는 시험이 {@code HurtSoundTargetTest} 다.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityHurtSoundMixin {

	@Inject(method = "playHurtSound", at = @At("HEAD"), cancellable = true)
	private void sharedfate$silenceSpreadSlice(DamageSource source, CallbackInfo info) {
		if (SpreadDamageManager.isDeliveringSlice()) {
			info.cancel();
		}
	}
}
