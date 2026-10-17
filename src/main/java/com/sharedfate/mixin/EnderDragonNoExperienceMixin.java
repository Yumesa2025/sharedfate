package com.sharedfate.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 엔더 드래곤이 죽어도 <b>경험치 구슬이 나오지 않게</b> 한다. 알·출구 포털·게이트웨이는 그대로다.
 *
 * <p>사람 말(2026-10-18): 드래곤 경험치 없음 — <b>항상</b>. 시련을 켜지 않은 전투도 같다.
 *
 * <h2>길이 어디인가 — 26.3 바이트코드에서 센 것</h2>
 *
 * <p>드래곤 경험치는 {@code EnderDragon.tickDeath()} 안의 {@code ExperienceOrb.award} <b>두 줄</b>
 * 에서만 나온다({@code javap -c} 로 확인했다).
 *
 * <pre>xp = (fight != null &amp;&amp; !fight.hasPreviouslyKilledDragon()) ? 12000 : 500;
 * if (dragonDeathTime &gt; 150 &amp;&amp; dragonDeathTime % 5 == 0 &amp;&amp; mobDrops)
 *     ExperienceOrb.award(level, position(), Mth.floor(xp * 0.08F));   // ① 죽는 동안 조금씩
 * ...
 * if (dragonDeathTime == 200 &amp;&amp; mobDrops)
 *     ExperienceOrb.award(level, position(), Mth.floor(xp * 0.2F));    // ② 마지막 한 번</pre>
 *
 * <p>둘 다 막는다. {@code @Redirect} 는 서수를 적지 않으면 <b>맞는 호출 전부</b>를 바꾸고,
 * {@code require = 2} 라 26.3 이 바뀌어 하나라도 못 찾으면 <b>조용히 넘어가지 않고 클래스를 불러오는
 * 순간 터진다</b>(refmap 이 없어 대상이 틀려도 빌드는 통과하는 저장소다 — 문서 6장).
 *
 * <p>⚠ 그 밖의 길은 없다. {@code LivingEntity.die → dropAllDeathLoot → dropExperience} 도 돌지만
 * 드래곤은 {@code xpReward} 를 한 번도 쓰지 않아(바이트코드에 그 필드가 없다) 기본 0 이고,
 * {@code Mob.getBaseExperienceReward} 가 {@code xpReward <= 0} 이면 0 을 돌려준다 — 0 짜리
 * {@code award} 는 구슬을 만들지 않는다.
 *
 * <h2>최후의 저항에서 앉은 채 죽는 길도 같은 자리를 지난다</h2>
 *
 * <p>앉아 있으면 {@code handleKillingBlow} 가 {@code DYING} 으로 넘기지 않아 죽음 빔이 안 뜨지만
 * {@code tickDeath()} 는 그대로 돈다(문서 4장 「죽음 빔 연출이 없습니다」). 곧 경험치가 나오는 곳은
 * 어느 길이든 이 메서드 하나다.
 *
 * <h2>재정의되는 메서드가 아니다</h2>
 *
 * <p>{@code tickDeath} 는 {@code LivingEntity} 의 것을 {@code EnderDragon} 이 재정의한 것이고,
 * 바닐라에 {@code EnderDragon} 의 하위 클래스는 없다. 믹스인은 실제로 도는 그 몸통에 걸린다.
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonNoExperienceMixin {

	/**
	 * 경험치 구슬을 뿌리는 두 호출을 <b>아무것도 안 하는 것</b>으로 바꾼다.
	 *
	 * <p>{@code dragonDeathTime} · 알 · 포털 · {@code setDragonKilled} 는 이 줄과 무관하게 돈다 —
	 * 바꾸는 것은 정적 호출 하나이고 그 앞뒤 흐름은 손대지 않는다.
	 */
	@Redirect(
			method = "tickDeath()V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/ExperienceOrb;award("
							+ "Lnet/minecraft/server/level/ServerLevel;"
							+ "Lnet/minecraft/world/phys/Vec3;I)V"),
			require = 2)
	private void sharedfate$noDragonExperience(ServerLevel level, Vec3 at, int amount) {
		// 일부러 비워 둔다. 드래곤 경험치는 항상 없다.
	}
}
