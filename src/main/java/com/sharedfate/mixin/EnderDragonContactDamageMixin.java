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
 * 「최후의 저항」이 도는 동안 드래곤의 <b>접촉 피해</b>와 <b>날개 밀치기</b>를 끈다.
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
 * <h2>⚠⚠ 날개 밀치기도 끈다 — <b>전에는 일부러 남겨 두었고 그 판단이 틀렸다</b></h2>
 *
 * <p>사람 말: <b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04).
 *
 * <p>이 자리에 <b>「남는 것은 미는 힘뿐이고 그것은 바닐라가 이미 하는 짓이다」</b>라고 적혀
 * 있었다. 빠진 것이 하나 있었다 — <b>바닐라는 그 힘을 맞은 본인에게 보내지 않는다.</b>
 *
 * <p>26.3 {@code EnderDragon.knockBack} 을 {@code javap -c} 로 읽은 결과다.
 *
 * <pre>double dd = Math.max(dx * dx + dz * dz, 0.1);
 * e.push(dx / dd * 4.0, 0.20000000298023224, dz / dd * 4.0);   // ← 세로 +0.2
 * if (!phaseManager.getCurrentPhase().isSitting() &amp;&amp; …) { e.hurtServer(…, 5.0F); }</pre>
 *
 * <ul>
 *   <li>⚠ <b>{@code isSitting()} 조건은 <u>피해</u>에만 붙어 있다.</b> 「앉아 있으면 바닐라가
 *       스스로 끈다」가 참인 것은 그 5 피해뿐이고, <b>미는 것은 조건 없이 매 틱 돈다</b>.
 *       상자는 {@code wing1}·{@code wing2} 의 {@code inflate(4,2,4).move(0,-2,0)} 이고
 *       {@code !wasHurtRecently()}(= {@code hurtTime == 0}, 곧 10틱 안에 한 대도 안 맞은 틱)
 *       마다 돈다. 최후의 저항은 드래곤을 포디움 위에 붙박아 두는 페이즈라 <b>머리를 때리러
 *       붙은 사람이 늘 그 상자 안</b>이다</li>
 *   <li><b>바닐라에서는 그것이 무해하다.</b> {@code Entity.push} 가 켜는 깃발이
 *       {@code needsSync} 이고 26.3 {@code ServerEntity} 는 그것을
 *       <b>{@code sendToTrackingPlayers}</b> 로만 내보낸다 — <b>맞은 본인에게는 안 간다.</b>
 *       사람의 자리는 클라이언트가 정하므로 그 값은 <b>서버 혼자 쌓아 두는 숫자</b>로 남는다</li>
 *   <li>⚠⚠ <b>그런데 우리가 그것을 배달한다.</b> 최후의 저항의 넉백은
 *       {@code member.syncVelocity = true} 를 켜는데 그쪽은
 *       <b>{@code sendToTrackingPlayersAndSelf}</b> 이고 보내는 것이 <b>그 순간의
 *       {@code getDeltaMovement()} 통째로</b>다({@code ClientboundSetEntityMotionPacket}).
 *       곧 <b>남이 쌓아 둔 값이 그 한 줄을 타고 본인에게 내려간다</b></li>
 * </ul>
 *
 * <p>쌓이는 양은 매 틱 {@code (v + 0.2 − 0.08) × 0.98} 이라 12틱에 <b>1.2</b>, 96틱에
 * <b>5.0</b>, 고정점이 <b>5.88칸/틱</b>(도달 높이 91~116칸)이다. 수평도 같은 호출이 쌓는데
 * {@code dx ÷ max(dx²+dz², 0.1) × 4} 라 <b>거리로 나누는 식</b>이고 분모가 {@code 0.1} 에서
 * 멈추므로 {@code √0.1} 칸에서 <b>12.65칸/틱</b>이 최댓값이다 — 바닥 종착 <b>15.2칸/틱</b>,
 * 공중 종착 <b>127.9칸/틱</b>. 실제 로그에 남은 것은
 * {@code moved too quickly! 14.108, 6.485, 24.602} 다.
 *
 * <p>그래서 <b>뿌리에서 끊는다.</b> {@code TrialVelocity.syncedVertical} 이 내려보내는
 * 세로를 사람이 실제로 올라간 만큼으로 자르고 있지만, 그것은 <b>세로 하나만</b> 막는
 * 보험이다 — 수평을 <b>읽어서 돌려놓는</b> 쪽({@code liftCross}·{@code pullSuck})은 그 보험이
 * 닿지 않는다. 여기서 끊으면 <b>세로와 수평이 한 번에 닫히고</b> 그쪽은 「남이 쌓아 둔 값을
 * 믿지 않는다」는 규약으로 남는다.
 *
 * <h2>⚠ 최후의 저항에서만 끊는다</h2>
 *
 * <p>{@link DragonLastStand#contactDamageOff()} 는 <b>최후의 저항 전용 깃발</b>이다 — 팀의
 * {@code Stand} 가 만들어지는 틱에 참이 되고 마지막 {@code Stand} 가 사라지는 틱에 거짓이
 * 된다. <b>일반 전투의 날개 밀치기는 바닐라 동작이고 끊으면 안 된다</b> — 거기서는 우리가
 * 속도를 본인에게 내려보내지 않으므로 위의 「배달」이 일어나지 않고, 그래서 무해하다.
 *
 * <h2>무엇을 남기는가</h2>
 *
 * <ul>
 *   <li><b>블록 부수기는 남는다.</b> 같은 {@code aiStep} 의 {@code checkWalls} 는 건드리지
 *       않는다 — 포탈 주변에 쌓은 발판이 계속 날아가는 것이 이 페이즈의 압박 중 하나다.
 *       ⚠ {@code checkWalls} 는 {@code knockBack} 과 <b>다른 구간</b>에서 머리·목·몸통 상자로
 *       따로 불린다(바이트코드로 확인했다) — 여기서 {@code knockBack} 을 통째로 버려도 그쪽은
 *       한 줄도 안 지나간다</li>
 *   <li><b>보스바 갱신도 남는다.</b> {@code dragonFight.updateDragon} 이 같은 구간에 있는데,
 *       거기에 보스바 이름과 체력 막대가 걸려 있다. {@code setNoAi(true)} 로 그 구간을 통째로
 *       끄지 않은 까닭이 이것과 블록 부수기 둘이다</li>
 *   <li><b>드래곤의 자세와 피해 판정은 안 건드린다.</b> 여기서 하는 일은 「남의 속도와 체력에
 *       손대는 두 호출을 버린다」뿐이고, 드래곤에게는 한 칸도 쓰지 않는다</li>
 * </ul>
 *
 * <h2>대상 서술자</h2>
 *
 * <p>⚠⚠ <b>{@code hurt} 와 {@code knockBack} 의 서술자가 글자 하나까지 같다</b> —
 * 둘 다 {@code (Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V} 다(26.3
 * 바이트코드에서 확인했다). 가르는 것이 <b>이름뿐</b>이다.
 *
 * <p>{@code EnderDragon} 에는 {@code hurt} 가 둘이다 —
 * {@code hurt(ServerLevel, EnderDragonPart, DamageSource, float)} 와 여기서 노리는
 * {@code hurt(ServerLevel, List)}. 앞쪽은 <b>사람이 드래곤을 때리는</b> 길이라 절대 막으면 안
 * 된다. 그래서 이름만 적지 않고 <b>서술자를 통째로</b> 적는다.
 *
 * <p>셋 다 {@code private} 이고 26.3 에서 어디에서도 재정의되지 않는다. <b>{@code EnderDragon}
 * 을 상속하는 클래스가 공용·클라이언트 양쪽 판에 하나도 없는 것</b>까지 확인했다({@code javap}
 * 로 7,762개를 훑었다). 「재정의되는 메서드에 믹스인을 걸면 조용히 죽는다」는 이 저장소의 함정에
 * 걸리지 않는다 — {@code private} 은 애초에 재정의될 수 없다.
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

	/**
	 * ⚠⚠ 최후의 저항이 도는 동안에는 <b>날개 상자 넉백</b>도 통째로 버린다.
	 *
	 * <p>{@code knockBack} 이 하는 일은 둘뿐이다 — {@code Entity.push} 와, {@code isSitting()}
	 * 이 거짓일 때의 5 피해. 최후의 저항은 {@code isSitting()} 이 참인 칸으로 드래곤을 잠그므로
	 * <b>버려지는 것은 미는 힘뿐</b>이고, 그 힘은 애초에 <b>바닐라가 본인에게 보내지 않는</b>
	 * 값이다(클래스 설명의 「날개 밀치기도 끈다」 절). 곧 <b>이 취소로 바닐라와 달라지는 것이
	 * 없다</b> — 달라지는 것은 우리가 켜는 {@code syncVelocity} 가 배달할 값이 없어지는 것이다.
	 *
	 * <p>⚠ 블록 부수기({@code checkWalls})는 이 메서드 안에 없다. 여기를 버려도 반구와 발판은
	 * 그대로 날아간다.
	 *
	 * <p>⚠ 깃발이 거짓인 일반 전투에서는 <b>한 줄도 달라지지 않는다.</b> 그쪽의 날개 밀치기는
	 * 바닐라 동작이고 우리가 속도를 내려보내지 않아 무해하다.
	 */
	@Inject(
			method = "knockBack(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$dropWingKnockBack(ServerLevel level, List<Entity> entities,
			CallbackInfo callback) {
		if (DragonLastStand.contactDamageOff()) {
			callback.cancel();
		}
	}
}
