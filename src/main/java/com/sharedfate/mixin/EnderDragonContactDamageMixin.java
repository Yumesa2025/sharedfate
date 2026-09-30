package com.sharedfate.mixin;

import com.sharedfate.sync.DragonLastStand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 「최후의 저항」이 도는 동안 드래곤의 <b>접촉 피해</b>를 끈다.
 *
 * <h2>왜 이것이 없으면 페이즈가 성립하지 않는가</h2>
 *
 * <p>26.3 {@code EnderDragon.aiStep} 은 매 틱 머리와 목 상자에 든 사람을
 * {@code mobAttack} <b>10</b> 으로 때린다.
 *
 * <pre>this.hurt(sl, sl.getEntities(this, this.head.getBoundingBox().inflate(1.0), …));
 * this.hurt(sl, sl.getEntities(this, this.neck.getBoundingBox().inflate(1.0), …));</pre>
 *
 * <p>이 모드는 체력이 팀 공유이고 {@code StatMirror.fold} 가 <b>팀원별 피해를 그대로
 * 합산</b>한다. 넷이 머리에 붙으면 한 틱에 <b>40</b> 이고 팀 체력은 <b>20</b> 이다. 그런데
 * 최후의 저항은 붙박이 드래곤을 <b>붙어서 때리는 싸움</b>이다 — 붙는 순간 전멸하면 그 페이즈가
 * 열리지도 않는다.
 *
 * <h2>무엇을 남기는가</h2>
 *
 * <ul>
 *   <li><b>블록 부수기는 남는다.</b> 같은 {@code aiStep} 의 {@code checkWalls} 는 건드리지
 *       않는다 — 포탈 주변에 쌓은 발판이 계속 날아가는 것이 이 페이즈의 압박 중 하나다</li>
 *   <li><b>날개 밀치기는 남는다.</b> {@code knockBack} 은 이 주입의 대상이 아니다. 그쪽의
 *       5 피해는 앉아 있는 동안 바닐라가 스스로 끈다
 *       ({@code !phaseManager.getCurrentPhase().isSitting()} 조건이 붙어 있고, 최후의 저항은
 *       {@code isSitting()} 이 참인 칸으로 드래곤을 잠근다). 곧 <b>남는 것은 미는 힘뿐</b>이고
 *       그것은 바닐라에서 기둥에 앉은 드래곤이 이미 하는 짓이다</li>
 *   <li><b>보스바 갱신도 남는다.</b> {@code dragonFight.updateDragon} 이 같은 구간에 있는데,
 *       거기에 보스바 이름과 체력 막대가 걸려 있다. {@code setNoAi(true)} 로 그 구간을 통째로
 *       끄지 않은 까닭이 이것과 블록 부수기 둘이다</li>
 * </ul>
 *
 * <h2>대상 서술자</h2>
 *
 * <p>{@code EnderDragon} 에는 {@code hurt} 가 둘이다 —
 * {@code hurt(ServerLevel, EnderDragonPart, DamageSource, float)} 와 여기서 노리는
 * {@code hurt(ServerLevel, List)}. 앞쪽은 <b>사람이 드래곤을 때리는</b> 길이라 절대 막으면 안
 * 된다. 그래서 이름만 적지 않고 <b>서술자를 통째로</b> 적는다.
 *
 * <p>둘 다 {@code private} 이고 26.3 에서 어디에서도 재정의되지 않는다(확인함). 「재정의되는
 * 메서드에 믹스인을 걸면 조용히 죽는다」는 이 저장소의 함정에 걸리지 않는다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>{@code sharedfate.mixins.json} 에 refmap 이 없어 <b>서술자가 틀려도 빌드가 그냥
 * 통과</b>하고 드래곤이 처음 틱을 도는 순간에 터진다. 대상이 실제로 있다는 것은 26.3
 * {@code minecraft-common-deobf} 의 {@code EnderDragon.class} 를 뜯어 확인했다.
 */
@Mixin(EnderDragon.class)
public abstract class EnderDragonContactDamageMixin {

	/**
	 * 최후의 저항이 도는 동안에는 머리·목 상자 피해를 통째로 버린다.
	 *
	 * <p>첫 줄이 {@code volatile boolean} 한 번 읽기다. 최후의 저항이 열리지 않은 판 —
	 * 곧 거의 모든 판 — 에서는 그 한 줄이 비용의 전부다.
	 */
	@Inject(
			method = "hurt(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$dropContactDamage(ServerLevel level, List<Entity> entities,
			CallbackInfo callback) {
		if (DragonLastStand.contactDamageOff()) {
			callback.cancel();
		}
	}
}
