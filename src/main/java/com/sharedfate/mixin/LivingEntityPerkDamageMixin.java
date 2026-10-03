package com.sharedfate.mixin;

import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkDamage;
import com.sharedfate.sync.DifficultyEscalation;
import com.sharedfate.sync.GameStartManager;
import com.sharedfate.sync.SharedAreaDamage;
import com.sharedfate.sync.SharedEffectDamage;
import com.sharedfate.sync.SpreadDamageManager;
import com.sharedfate.sync.SpreadSliceAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 서버측 피해 진입점에 걸린 SharedFate 의 처리들.
 *
 * <p>하나는 증강의 피해 배율을 실제 피해량에 꽂아 넣는 것이고, 다른 하나는 공유된
 * 상태이상이 팀 전원에게 똑같이 주는 중복 피해를 버리는 것이다. 둘 다 같은
 * {@code hurtServer} 진입점을 보므로 한 mixin 에 모아 둔다.
 *
 * <p>여기에 「완충」({@code spread_damage})의 두 지점이 함께 붙어 있다. 피해를 미뤄 두는 곳은
 * 배율을 먹이는 자리 바로 뒤이고, 나뉘어 들어오는 동안 회복을 막는 곳은 {@code heal} 진입점이다.
 * 회복 쪽은 피해와 상관없어 보이지만, <b>미뤄 둔 몫과 회복 금지는 한 몸</b>이라 갈라 두면 한쪽만
 * 고쳐지는 사고가 난다. 자세한 까닭은 {@link SpreadDamageManager} 머리말에 있다.
 *
 * <p>이 믹스인이 무는 것은 {@code LivingEntity.hurtServer(ServerLevel, DamageSource, float)}
 * <b>본문</b>이다. 「서버측 피해 진입점이 그 하나」라는 뜻이 <b>아니다</b> — 바닐라 공통 jar 에만
 * {@code hurtServer} 를 선언하는 클래스가 쉰 곳이 넘는다. 다만 {@code LivingEntity} 를 물려받은
 * 것들의 재정의는 <b>둘만 빼고 전부</b> 끝에서 {@code super.hurtServer} 를 부르므로 결국 이
 * 본문으로 돌아오고, 그래서 여기 한 곳만 잡으면 피해당 한 번만 배율이 걸린다. 플레이어가 그
 * 전형이다 — {@code ServerPlayer.hurtServer} → {@code Player.hurtServer} →
 * {@code LivingEntity.hurtServer} 로 곧장 이어진다({@code Avatar} 는 {@code hurtServer} 를
 * 재정의하지 않는다).
 *
 * <p>super 를 부르지 않는 그 둘이 {@code ArmorStand} 와 {@code EnderDragon} 이다. 갑옷 거치대는
 * 증강이 걸릴 일이 없어 상관없지만, <b>드래곤은 회차를 끝내는 상대라 그냥 넘길 수 없다.</b>
 *
 * <h2>엔더 드래곤은 오는 길이 다르다</h2>
 * <p><b>걸리기는 걸린다. 다만 길이 다르고 단서가 셋 붙는다.</b> 「드래곤에게도 평범하게
 * 걸리겠거니」 하고 그 위에 설계하면 어긋난다.
 *
 * <p>{@code EnderDragon.hurtServer} 는 {@code super} 를 부르지 않는다 — 바이트코드에
 * {@code invokespecial} 이 하나도 없고, 받은 것을 그대로
 * {@code hurt(ServerLevel, EnderDragonPart, DamageSource, float)} 로 넘길 뿐이다. 그런데도 이
 * 본문에 닿는 것은 그 뒤가 이렇게 이어지기 때문이다.
 *
 * <pre>{@code
 * EnderDragonPart.hurtServer        // EnderDragonPart 는 Entity 라 이 믹스인이 닿지 않는다
 *   → EnderDragon.hurt(level, part, source, amount)
 *   → EnderDragon.reallyHurt
 *   → invokespecial Mob.hurtServer  // Mob 은 hurtServer 를 선언하지 않는다
 *   → LivingEntity.hurtServer       // ← 여기서 이 믹스인이 발화한다
 * }</pre>
 *
 * <ol>
 *   <li><b>플레이어가 때린 것만 온다.</b> {@code reallyHurt} 는 {@code source.getEntity()} 가
 *       {@code Player} 이거나 피해원이 {@code ALWAYS_HURTS_ENDER_DRAGONS} 일 때만 불린다. 몹이나
 *       환경이 드래곤에게 준 피해는 이 본문에 <b>아예 닿지 않는다.</b> 「드래곤이 <i>받는</i>
 *       피해」를 이 자리에서 만지는 것은 무엇이든 플레이어가 낸 피해에만 듣는다는 뜻이다.
 *       (드래곤이 <i>주는</i> 피해는 얘기가 다르다. 그때 맞는 쪽은 플레이어이므로 평범하게
 *       {@code ServerPlayer.hurtServer} 사슬을 타고 내려오고, {@code mob_damage} 가 걸릴 길도
 *       막혀 있지 않다. 증강 쪽에서 드래곤을 {@code excludes} 로 빼 두었는지는 별개 문제다.)</li>
 *   <li><b>이미 깎인 값이 온다.</b> {@code EnderDragon.hurt} 가 먼저
 *       {@code phase.onHurt(source, amount)} 로 깎고, 머리·목이 아닌 부위였으면
 *       {@code amount / 4 + min(amount, 1)} 로 한 번 더 깎는다. 팀원의 {@code damage_dealt} 는
 *       <b>그렇게 깎이고 남은 값</b>에 곱해진다.</li>
 *   <li><b>아예 안 오는 경우가 있다.</b> 단계가 {@code DYING} 이거나 깎은 값이 {@code 0.01}
 *       미만이면 {@code hurt} 가 먼저 빠져나간다.</li>
 * </ol>
 *
 * <p>취소도 반만 듣는다. HEAD 에서 {@code false} 를 돌려주면 피해는 막히지만
 * {@code EnderDragon.reallyHurt} 가 그 반환값을 {@code pop} 으로 버리기 때문에, 드래곤 쪽
 * 뒷정리(앉은 자세 피해 누적, {@code TAKEOFF} 전환)는 그대로 돈다.
 *
 * <p>근거는 공통 jar 을 {@code javap -p -c} 로 읽은 것이다. <b>26.2 와 26.3 이 완전히 같다</b> —
 * 판올림 회귀가 아니라 처음부터 이 모양이었으므로, 판 번호로 외울 것이 아니라 모양으로 외워야
 * 한다.
 *
 * <p>몹이 주는 피해를 깎는 {@code mob_damage} 증강도 여기를 지난다. 가해자를 보는 자리가
 * 이미 있으므로 별도의 mixin 을 두지 않고 {@link PerkDamage} 안에서 팀원의
 * {@code damage_dealt} 와 나란히 처리한다. 자세한 내용은
 * {@link com.sharedfate.perk.MobPerkModifiers} 참고.
 *
 * <p>피해가 아닌 것이 하나 섞여 있다. 「몽둥이찜질」({@code weapon_knockback})의 넉백을 갈아
 * 끼우는 {@link #sharedfate$applyPerkWeaponKnockback} 다. 근접 공격의 추가 넉백이 정해지는
 * {@code LivingEntity.getKnockback} 이 마침 이 mixin 이 이미 잡고 있는 클래스에 있어,
 * 새 mixin 을 만들고 등록하는 대신 여기에 붙였다.
 *
 * <p>HEAD 에서 인자를 갈아 끼우므로 방패({@code applyItemBlocking})·방어구·흡수·피격 쿨타임
 * 비교({@code lastHurt})가 모두 배율이 반영된 값을 본다. 공유 체력을 맞추는
 * {@code StatMirror} 는 다음 틱에 체력 변화량을 관측하는 방식이라, 이미 배율이 반영되고 난
 * 결과만 본다. 즉 배율이 두 번 곱해질 여지가 없다.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPerkDamageMixin implements SpreadSliceAccess {

	/**
	 * 직전에 받은 피해량. 피격 쿨타임 안에 들어온 공격이 <b>실제로 얼마나 아픈지</b>를 재는 데
	 * 쓴다.
	 *
	 * <p>{@code hurtServer} 는 {@code LivingEntity.damageCooldownTime > 10} 이고
	 * {@code BYPASSES_COOLDOWN} 이 아니면 <b>{@code amount - lastHurt} 만</b> 실제 피해로 치고,
	 * 그 값이 0 이하이면 통째로 버린다(바이트코드 206~216행). 「호위」가 그 버려질 한 대를
	 * 막았다고 치고 쿨타임을 쓰면 정작 아픈 대를 못 막는다.
	 */
	@Shadow
	protected float lastHurt;

	/**
	 * 남은 피격 쿨타임(틱). <b>26.3 에서 새로 생긴 칸이고, {@code hurtServer} 가 보는 것은
	 * 이쪽이다.</b>
	 *
	 * <p>26.2 까지는 {@code Entity.invulnerableTime} 하나가 이 판정을 맡았다. 26.3 이 그것을
	 * 둘로 쪼갰다 — {@code LivingEntity.damageCooldownTime} 이 {@code public int} 로 새로 생겨
	 * {@code hurtServer} 가 읽고({@code >10}) 쓰고({@code =20}), {@code Entity.invulnerableTime}
	 * 은 {@code private} 이 되면서 <b>피해와 상관이 없어졌다</b>. 그쪽은 이제
	 * {@code Entity.commonTick} 에서 줄어들고 NBT 로 오가며 {@code isTemporarilyInvulnerable()}
	 * 이 읽을 뿐이다. 26.3 {@code LivingEntity} 클래스 파일에는 {@code invulnerableTime} 이라는
	 * 이름이 <b>한 번도 나오지 않는다.</b>
	 *
	 * <p>두 판의 {@code hurtServer} 가 <b>같은 오프셋에서 서로 다른 칸</b>을 만지기 때문에 이
	 * 갈림은 눈에 잘 띄지 않는다.
	 *
	 * <pre>
	 * 26.2  185: getfield invulnerableTime      248: putfield invulnerableTime
	 * 26.3  185: getfield damageCooldownTime    248: putfield damageCooldownTime
	 * </pre>
	 *
	 * <p>실제로 0.27.0-dev 로 26.3 에 올릴 때 {@code Entity.invulnerableTime} 이
	 * {@code private} 이 되어 컴파일이 깨졌고, 접근자 {@code getInvulnerableTime()} 으로 바꾸는
	 * 것으로 끝냈다. <b>컴파일은 통과했고 판정만 조용히 죽었다.</b> 같은 실수가 다시 들어오지
	 * 못하게 {@code SpreadDamageTargetTest} 가 두 칸을 나란히 붙들고 있다.
	 *
	 * <p>{@code @Shadow} 는 접근 제어자까지 대상과 맞아야 하므로 {@code public} 이다.
	 */
	@Shadow
	public int damageCooldownTime;

	/**
	 * 「완충」이 미뤄 둔 몫을 넣을 때 {@link #lastHurt} 를 읽는 통로. {@link SpreadSliceAccess} 참고.
	 *
	 * <p>{@code lastHurt} 를 이미 {@code @Shadow} 로 끌어오고 있는 믹스인이 여기라, 접근자 믹스인을
	 * 새로 만들어 등록하는 대신 이 클래스에 얹었다.
	 */
	@Override
	public float sharedfate$lastHurt() {
		return this.lastHurt;
	}

	/** 「완충」이 미뤄 둔 몫을 넣는 동안만 {@link #lastHurt} 를 0 으로 두었다가 되돌린다. */
	@Override
	public void sharedfate$setLastHurt(float value) {
		this.lastHurt = value;
	}

	/**
	 * 버려야 할 피해를 여기서 전부 걸러낸다.
	 *
	 * <p>두 가지를 본다.
	 *
	 * <ol>
	 *   <li><b>강제 증강 선택 중의 무적</b> — 시간이 멈춰 있어도 용암·낙하·불·익사는 플레이어 자기
	 *       틱에서 계산돼 그대로 들어온다. 선택창이 떠 있는 동안에는 팀원의 피해를 통째로 버린다.
	 *       무적은 {@link com.sharedfate.perk.PerkChoiceSession} 이 시간을 녹이는 순간 함께 풀린다.
	 *       세션이 없으면 첫 줄에서 곧바로 빠져나가므로 평소 피해 처리에는 비용이 없다.</li>
	 *   <li><b>회차 시작 전의 무적</b> — 리더가 「게임 시작」을 누르기 전에는 팀원의 피해를 통째로
	 *       버린다. 체력이 공유라 한 명만 죽어도 팀이 전멸하는데, 아직 시작도 안 한 회차 때문에
	 *       월드가 지워지는 일도 하드코어에서 관전자로 갇히는 일도 없어야 한다. 자세한 까닭은
	 *       {@link com.sharedfate.sync.GameStartManager#blocksDamage} 에 있다.</li>
	 *   <li><b>방패를 든 채 착지</b> — {@code shield_fall_immunity} 를 가진 팀원이 방패로 막는
	 *       중에 받는 낙하 피해는 통째로 버린다. 배율을 0 으로 깎지 않고 여기서 버리는 이유는
	 *       {@link com.sharedfate.perk.effect.ShieldFallImmunityEffect} 에 있다. 낙하가 아닌
	 *       피해는 {@link PerkDamage#blocksFallDamage} 첫 줄에서 곧바로 빠져나간다.
	 *       <b>버리기 전에</b> 방패를 막은 양의 열 배로 깎는다 — 그것이 이 증강의 대가다.</li>
	 *   <li><b>공유 상태이상의 중복 피해</b> — 아래 설명 참고.</li>
	 *   <li><b>몹에게 받은 한 대</b> — {@code damage_ward} 를 고른 사람은 쿨타임마다 한 번, 몹이
	 *       준 피해를 통째로 버린다. 낙하 면역과 같은 이유로 배율 0 이 아니라 여기서 버린다.
	 *       {@link com.sharedfate.perk.effect.DamageWardEffect} 참고. 이 검사만은 <b>맨 마지막</b>
	 *       이어야 한다 — 참이 되는 순간 쿨타임이 시작되므로, 앞의 규칙들이 이미 버릴 피해에
	 *       한 번을 낭비하면 안 된다.</li>
	 * </ol>
	 *
	 * <p>{@code false} 를 돌려주면 바닐라 입장에서는 "피해가 들어가지 않았다"와 같다. 체력·흡수·
	 * 무적시간·피격 애니메이션 어느 것도 건드리지 않으므로 {@code StatMirror} 가 다음 틱에 관측할
	 * 델타도 0 이고, {@code DamageLedger} 에도 이 몫이 기록되지 않는다.
	 *
	 * <p>배율을 먹이는 {@link #sharedfate$applyPerkDamageMultipliers} 와 같은 HEAD 에 붙지만
	 * 순서는 상관없다. 버릴 피해면 배율을 곱한 값도 함께 버려지고, 실제로 피해를 받는 대표
	 * 한 명에게는 배율이 정확히 한 번 걸린다. 즉 증강의 {@code damage_taken} 배율은
	 * 팀원 수만큼이 아니라 1인분에만 곱해진다.
	 */
	@Inject(method = "hurtServer", at = @At("HEAD"), cancellable = true)
	private void sharedfate$skipDuplicateSharedEffectDamage(ServerLevel level, DamageSource source,
			float amount, CallbackInfoReturnable<Boolean> callback) {
		LivingEntity self = (LivingEntity) (Object) this;
		// 시련 룰렛도 시간을 멈춘다. 바닐라 정지는 플레이어를 얼리지 않으므로 화면을 읽는
		// 4초 사이에 용암·낙하·불이 그대로 들어온다 — 증강 선택과 같은 이유로 여기서 버린다.
		if (PerkChoiceSession.blocksDamage(self) || GameStartManager.blocksDamage(self)
				|| com.sharedfate.sync.TrialFreeze.blocksDamage(self)) {
			callback.setReturnValue(false);
			return;
		}
		if (PerkDamage.blocksFallDamage(self, source)) {
			// 막아 준 대가로 방패가 크게 닳는다. 피해를 버리기 <b>전에</b> 깎아야 막은 양을 안다.
			PerkDamage.wearShieldForBlockedFall(self, amount);
			callback.setReturnValue(false);
			return;
		}
		if (SharedEffectDamage.isDuplicateEffectDamage(self)) {
			callback.setReturnValue(false);
			return;
		}
		if (SharedAreaDamage.isDuplicateAreaDamage(self, source)) {
			callback.setReturnValue(false);
			return;
		}
		// 「호위」는 맨 마지막이다. 참을 돌려주는 순간 그 사람의 쿨타임이 시작되므로, 앞에서 이미
		// 버려질 피해에 한 번을 낭비하지 않으려면 다른 모든 검사 뒤여야 한다.
		//
		// 그리고 바닐라가 피격 쿨타임 안에서 버릴 몫도 미리 덜어 낸다. 좀비 셋에게 동시에 맞으면
		// 실제로 아픈 것은 첫 대뿐인데, 그 판정 없이는 「호위」가 어차피 0 이 될 두 번째 대에
		// 소모된다.
		if (effectiveAmount(source, amount) > 0.0F
				&& PerkDamage.blocksMobDamage(level, self, source, amount)) {
			callback.setReturnValue(false);
		}
	}

	/**
	 * 인자 셋 중 {@code float amount}(로컬 3번)만 바꾼다. {@code argsOnly} 로 지역변수는 건드리지
	 * 않고, 앞쪽 인자 둘은 캡처해 피해원을 넘겨받는다.
	 *
	 * <p>증강 배율을 먹인 값에 이어서 「난이도 상승」배율을 곱한다. 같은 자리에 두 mixin 이
	 * 붙으면 어느 쪽이 먼저 도는지가 우선순위에 달려 눈에 안 보인다. 둘 다 곱셈이라 순서는
	 * 어차피 결과를 바꾸지 않는다.
	 *
	 * <p>마지막으로 「완충」이 이 값을 통째로 미뤄 갈 수 있다. 미뤄 가면 이번 피해량은 0 이 되고,
	 * 같은 값이 몇 초에 걸쳐 나뉘어 다시 이 진입점으로 들어온다. <b>배율을 다 먹인 뒤에</b>
	 * 미루는 것이 중요하다. 그래야 다시 넣을 때 배율을 한 번 더 곱하지 않는다.
	 */
	@ModifyVariable(method = "hurtServer", at = @At("HEAD"), argsOnly = true, index = 3)
	private float sharedfate$applyPerkDamageMultipliers(float amount, ServerLevel level, DamageSource source) {
		LivingEntity self = (LivingEntity) (Object) this;
		// 미뤄 두었던 몫이 다시 들어오는 중이면 손대지 않는다. 배율은 미룰 때 이미 걸었고, 여기서
		// 또 미루면 같은 피해가 영원히 나뉘기만 하고 끝나지 않는다.
		if (SpreadDamageManager.isDeliveringSlice()) {
			return amount;
		}
		// 「무엇에 맞았나」를 여기서 적어 둔다. 실제로 기록하는 자리(StatMirror)는 체력이 얼마나
		// 줄었는지만 보고 출처를 모른다. 사망 알림을 끈 팀에서는 이것이 죽은 까닭을 아는
		// 유일한 단서가 된다.
		if (self instanceof net.minecraft.server.level.ServerPlayer victim) {
			com.sharedfate.sync.DamageLedger.noteSource(victim, source);
		}
		float scaled = PerkDamage.scale(self, source, amount);
		float escalated = DifficultyEscalation.scaleDamage(source, scaled);
		return SpreadDamageManager.intercept(self, source, escalated);
	}

	/**
	 * 피해를 나누어 받는 동안에는 회복되지 않는다. 「완충」이 치르는 대가다.
	 *
	 * <p>회복 진입점은 {@code LivingEntity.heal(float)} 하나라 — 공통 jar 을 통틀어 이 서술자를
	 * 선언하는 클래스가 {@code LivingEntity} 뿐이다 — 자연 회복도 재생 상태이상도 금사과도 모두
	 * 여기를 지난다. 그래서 한 지점만 막으면 대가가 성립한다.
	 *
	 * <p>취소해도 {@code heal} 은 {@code void} 라 호출자에게 아무 신호도 가지 않는다.
	 * 재생 상태이상은 아이콘도 입자도 그대로 남고 회복량만 사라진다.
	 *
	 * <p>{@link SpreadDamageManager#blocksHealing} 은 미뤄 둔 몫이 하나도 없으면 첫 줄에서 곧바로
	 * 거짓이므로, 평소 회복 경로에는 사실상 아무 부담도 얹히지 않는다.
	 */
	@Inject(method = "heal", at = @At("HEAD"), cancellable = true)
	private void sharedfate$blockHealingWhileSpreading(float amount, CallbackInfo callback) {
		if (SpreadDamageManager.blocksHealing((LivingEntity) (Object) this)) {
			callback.cancel();
		}
	}

	/**
	 * 「몽둥이찜질」({@code weapon_knockback})의 넉백을 이 자리에서 갈아 끼운다.
	 *
	 * <p>근접 공격의 추가 넉백이 정해지는 자리는
	 * {@code LivingEntity.getKnockback(Entity, DamageSource)} 하나다. 바이트코드로 보면
	 * {@code Player.attack} 이 {@code causeExtraKnockback(대상, getKnockback(대상, 피해원) + 질주보정, …)}
	 * 로 넘기고, {@code getKnockback} 자신은
	 * {@code EnchantmentHelper.modifyKnockback(…, 속성값) / 2} 를 돌려준다. <b>여기서 {@code this}
	 * 는 때리는 쪽이고 첫 인자가 맞는 쪽이다</b> — 그래서 이 한 자리에서 「누가 때리는가」와
	 * 「누구를 때리는가」를 모두 알 수 있고, 플레이어를 뺀다는 약속을 지킬 수 있다.
	 *
	 * <p>바닐라 속성 {@code minecraft:attack_knockback} 으로는 안 된다. 등록값이
	 * {@code RangedAttribute(0, 0, 5)} 라 5 에서 잘리고, 속성은 때리는 사람에게 붙는 값이라 맞는
	 * 쪽이 플레이어인지 알 수 없기 때문이다. 자세한 근거는
	 * {@link com.sharedfate.perk.effect.WeaponKnockbackEffect} 머리말에 있다.
	 *
	 * <p>{@code LivingEntity} 는 이 mixin 이 이미 잡고 있는 클래스라 <b>새 mixin 도 등록도
	 * 필요 없다.</b>
	 *
	 * <p>{@code RETURN} 에 붙어 원래 값을 읽고, <b>더 큰 경우에만</b> 갈아 끼운다. 넉백 인챈트가
	 * 이미 더 세게 붙어 있으면 그쪽이 그대로 이기므로 이 증강이 무기를 약하게 만드는 일은 없다.
	 * 걸릴 것이 없으면 {@link com.sharedfate.perk.PerkDamage#weaponKnockback} 이 음수를 돌려주고,
	 * 넉백은 언제나 0 이상이라 그 값이 원래 값을 이기는 일도 없다.
	 */
	@Inject(method = "getKnockback", at = @At("RETURN"), cancellable = true)
	private void sharedfate$applyPerkWeaponKnockback(Entity target, DamageSource source,
			CallbackInfoReturnable<Float> callback) {
		float perk = PerkDamage.weaponKnockback((LivingEntity) (Object) this, target);
		if (perk > callback.getReturnValueF()) {
			callback.setReturnValue(perk);
		}
	}

	/**
	 * 이번 공격이 <b>실제로</b> 줄 피해. 피격 쿨타임에 걸려 버려질 몫을 덜어 낸 값이다.
	 *
	 * <p>판정 자체는 {@link PerkDamage#effectiveAmount} 에 순수 계산으로 떼어 두었다. 믹스인
	 * 안의 {@code private} 메서드는 밖에서 부를 길이 없어 시험이 닿지 않고, 닿지 않는 판정은
	 * 판올림 때 조용히 썩는다 — 실제로 그렇게 한 번 썩었다. 이 저장소가
	 * {@code GameOverCountdown}·{@code VictoryTeamResolver.resolve} 에서 쓰는 방식과 같다.
	 *
	 * <p>여기 남는 것은 <b>{@code @Shadow} 로 끌어온 두 칸을 읽어 넘기는 일</b>뿐이다.
	 * {@link #damageCooldownTime} 이 어떤 칸이고 왜 {@code Entity.invulnerableTime} 이 아닌지는
	 * 그쪽 머리말에 있다.
	 */
	private float effectiveAmount(DamageSource source, float amount) {
		return PerkDamage.effectiveAmount(amount, this.lastHurt, this.damageCooldownTime,
				source.is(DamageTypeTags.BYPASSES_COOLDOWN));
	}

}
