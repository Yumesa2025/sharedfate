package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.DamageIncreaseReliefEffect;
import com.sharedfate.perk.effect.DamageTakenBlockingEffect;
import com.sharedfate.perk.effect.DamageTakenFromEffect;
import com.sharedfate.perk.effect.DamageWardEffect;
import com.sharedfate.perk.effect.ShieldFallImmunityEffect;
import com.sharedfate.perk.effect.WeaponKnockbackEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 증강의 피해 배율을 실제 피해량에 반영하는 계산기.
 *
 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code LivingEntity.hurtServer} 진입 시점에
 * 이 클래스를 부른다. 한 번의 피해 이벤트에 대해 딱 한 번만 곱해지고, 그 뒤의 방패·방어구·흡수
 * 계산과 {@code StatMirror} 의 공유 체력 반영은 이미 배율이 반영된 수치를 보게 된다.
 *
 * <p>배율이 정확히 1.0 이면 원래 값을 그대로 돌려준다. 증강 풀이 비어 있는 동안에는 항상 이
 * 경로를 타므로 바닐라 피해 계산과 100% 같다.
 */
public final class PerkDamage {
	/** 배율을 곱한 뒤의 상한. 무한대·NaN 이 바닐라 계산으로 새어나가지 않게 막는다. */
	static final float MAX_DAMAGE = 1.0e9F;

	/**
	 * 「버티는 방패」가 낙하 피해를 막았을 때 방패가 닳는 배수.
	 *
	 * <p>바닐라가 막은 피해량만큼 깎는 것에 견주어 이만큼 더 깎는다.
	 */
	static final int FALL_BLOCK_DURABILITY_FACTOR = 10;

	/**
	 * 세트 「방어 3단계」가 켜져 위 대가가 사라졌을 때의 배수. 바닐라가 막은 피해량만큼 깎는 것과
	 * 같은 값이다 — 대가가 사라졌다고 방패가 아예 안 닳으면 바닐라보다도 후해진다.
	 */
	static final int FALL_BLOCK_DURABILITY_FACTOR_WAIVED = 1;

	/**
	 * 바닐라가 「아직 피격 쿨타임 안이다」로 보는 경계. {@code damageCooldownTime > 10} 이다.
	 *
	 * <p>26.3 {@code LivingEntity.hurtServer} 바이트코드에 {@code 10.0F} 가 그대로 박혀 있다
	 * (상수 {@code DAMAGE_COOLDOWN_DURATION = 20} 의 절반이지만 그 상수를 쓰지는 않는다).
	 * {@code SpreadDamageManager.INVULNERABLE_GATE_TICKS} 와 같은 값이고 같은 규칙이다.
	 */
	static final int DAMAGE_COOLDOWN_GATE_TICKS = 10;

	/** 조회가 한 번 터지면 매 피해마다 로그가 쌓이므로 한 번만 남긴다. */
	private static volatile boolean warned;

	private PerkDamage() {
	}

	/**
	 * 피해량에 "주는 피해"·"받는 피해" 배율을 반영한다.
	 *
	 * @param victim 피해를 받는 대상. 팀원이면 받는 피해 배율이 걸리고, 방패로 막는 중이면
	 *               {@code damage_taken_blocking} 배율까지 함께 걸린다.
	 * @param source 피해원. 가해자가 팀원이면 주는 피해 배율이, 몹이면 {@code mob_damage}
	 *               배율이 걸린다.
	 * @param amount {@code hurtServer} 가 받은 원래 피해량
	 * @return 배율을 반영한 피해량. 반영할 게 없으면 {@code amount} 그대로
	 */
	public static float scale(@Nullable Entity victim, @Nullable DamageSource source, float amount) {
		if (!(amount > 0.0F) || !Float.isFinite(amount)) {
			return amount;
		}
		double factor;
		try {
			// 조회 실패가 피해 처리를 막으면 안 된다. 어떤 예외든 원래 값으로 돌아간다.
			Entity attacker = source == null ? null : source.getEntity();
			factor = dealtFactor(attacker) * takenFactor(victim) * mobDealtFactor(attacker)
					* takenSourceFactor(victim, source) * takenBlockingFactor(victim)
					// 「착지 노리기」·「하늘 사수」(0.44.0-dev). 드래곤이 맞을 때, 시련 전투 중에만 1 이 아니다.
					* DragonPrep.dragonHitFactor(victim, source);
		} catch (RuntimeException error) {
			warnOnce(error);
			return amount;
		}
		return combine(amount, factor);
	}

	/** 가해자가 증강을 가진 팀원일 때만 주는 피해 배율을 읽는다. */
	private static double dealtFactor(@Nullable Entity attacker) {
		if (!(attacker instanceof ServerPlayer player) || !perksActive(player)) {
			return 1.0;
		}
		return PerkManager.damageDealtMultiplier(player);
	}

	/**
	 * 가해자가 몹일 때 {@code mob_damage} 증강의 배율을 읽는다.
	 *
	 * <p>{@link #dealtFactor}와는 배타적이다. 가해자는 팀원이거나 몹이지 둘 다일 수 없다.
	 * 화살·불덩이처럼 던진 것에 맞은 경우에도 {@code DamageSource.getEntity()}는 쏜 몹을
	 * 가리키므로 여기서 잡힌다.
	 */
	private static double mobDealtFactor(@Nullable Entity attacker) {
		return MobPerkModifiers.damageMultiplier(attacker);
	}

	/**
	 * 화면이 「받는 피해」 줄에 적을 배율.
	 *
	 * <p>피해 계산이 실제로 쓰는 {@link #takenFactor} 를 그대로 부른다. 값을 여기서 따로
	 * 세면 화면과 실제가 갈라진다 — 특히 「증강을 껐다」·「아직 아무 증강도 없다」 같은
	 * 조건이 두 벌이 되면 그중 하나만 고쳐지는 사고가 난다.
	 *
	 * <p>피해 종류를 가리는 {@code damage_taken_from} 은 들어 있지 않다. 그쪽은 불·폭발·몹처럼
	 * <b>맞은 것이 무엇이냐에 따라 달라지는</b> 배율이라 한 줄로 적을 수 없고, 종류마다 한
	 * 줄씩 적으면 능력치 표시가 그 표에 잡아먹힌다. 모든 피해에 공통으로 걸리는 배율만 적는다.
	 *
	 * <p>같은 이유로 {@code damage_taken_blocking} 도 빠져 있다. 그쪽은 <b>지금 막는 중이냐에
	 * 따라 달라지는</b> 값이라 한 줄로 적으면 방패를 내린 순간 거짓말이 된다.
	 */
	public static double takenMultiplier(@Nullable ServerPlayer player) {
		return takenFactor(player);
	}

	/** 피해자가 증강을 가진 팀원일 때만 받는 피해 배율을 읽는다. */
	private static double takenFactor(@Nullable Entity victim) {
		if (!(victim instanceof ServerPlayer player) || !perksActive(player)) {
			return 1.0;
		}
		return PerkManager.damageTakenMultiplier(player);
	}

	/**
	 * 피해 종류를 가려서 걸리는 {@code damage_taken_from} 배율을 읽는다.
	 *
	 * <p>{@link #takenFactor} 와 따로 도는 이유는 {@link PerkEffect#damageTakenMultiplier()} 에
	 * 피해원이 넘어오지 않기 때문이다. 그 자리를 고치면 조건을 모르는 옛 경로가 "불 피해 전용"
	 * 배율을 모든 피해에 곱하게 되므로, 피해원을 아는 여기서 따로 훑는다.
	 *
	 * <p>증강의 <b>최상위 효과만</b> 본다. {@code periodic}·{@code conditional} 안에 들어간
	 * {@code damage_taken_from} 은 여기 걸리지 않는데, 그런 정의는 애초에
	 * {@link com.sharedfate.perk.effect.DamageTakenFromEffect} 가 읽는 시점에 버린다.
	 */
	private static double takenSourceFactor(@Nullable Entity victim, @Nullable DamageSource source) {
		if (source == null || !(victim instanceof ServerPlayer player) || !perksActive(player)) {
			return 1.0;
		}
		return takenSourceMultiplier(TeamLookup.stateOf(player.getUUID()), source);
	}

	// ------------------------------------------------------------------ 막는 중일 때의 배율

	/**
	 * 방패로 막는 중일 때만 걸리는 {@code damage_taken_blocking} 배율을 읽는다.
	 *
	 * <p>{@link PerkEffect#damageTakenMultiplier()} 에는 피해자의 <b>자세가 넘어오지 않기</b>
	 * 때문에, 자세를 아는 여기서 따로 훑는다.
	 *
	 * <p>자세를 <b>가장 먼저</b> 본다. 막고 있지 않은 피해는 팀 상태를 찾아보지도 않고 곧바로
	 * 빠져나가므로, 이 효과를 아무도 갖고 있지 않은 서버에서도 피해 경로에 얹히는 비용이 거의
	 * 없다. {@code isBlocking()} 은 방패를 <b>손에 들고만</b> 있을 때는 거짓이고 실제로
	 * 우클릭으로 막는 중일 때만 참이다 — {@link #blocksFallDamage} 가 보는 것과 같은 값이다.
	 */
	private static double takenBlockingFactor(@Nullable Entity victim) {
		if (!(victim instanceof ServerPlayer player) || !player.isBlocking() || !perksActive(player)) {
			return 1.0;
		}
		return blockingMultiplier(TeamLookup.stateOf(player.getUUID()), true);
	}

	/**
	 * 이 팀이 가진 {@code damage_taken_blocking} 중 지금 자세에 걸리는 것들의 배율을 모두 곱한 값.
	 *
	 * <p><b>보유 증강과 세트를 둘 다 훑는다.</b> {@code ownedPerks} 만 훑고 세트를 빠뜨리면 방어
	 * 2단계가 통째로 무동작이 되는데, 빌드도 통과하고 로그도 남지 않는다.
	 */
	static double blockingMultiplier(@Nullable TeamState state, boolean blocking) {
		if (state == null || !blocking || state.ownedPerks.isEmpty()) {
			return 1.0;
		}
		// 「굳은살」이 있으면 증강이 늘린 몫만 덜어 낸다. 세트 쪽(방어 2단계 ×0.5)은 줄이는 값이라
		// 걸리지 않는다.
		DamageIncreaseReliefEffect.Relief relief = DamageIncreaseReliefEffect.reliefFor(state);
		double total = 1.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof DamageTakenBlockingEffect blockingEffect) {
					total *= relief.ease(blockingEffect.multiplierFor(blocking));
				}
			}
		}
		total *= blockingMultiplierOf(PerkSetEffects.activeEffectsOf(state), blocking);
		// 배율 0 은 "막는 동안 완전 면역"이라는 뜻이라 그대로 살려 둔다. 음수와 무한대만 물러난다.
		return Double.isFinite(total) && total >= 0.0 ? total : 1.0;
	}

	/**
	 * 효과 목록만 보는 순수 계산.
	 *
	 * <p>막고 있지 않으면 목록을 훑지도 않는다.
	 */
	static double blockingMultiplierOf(@Nullable Iterable<PerkEffect> effects, boolean blocking) {
		if (effects == null || !blocking) {
			return 1.0;
		}
		double total = 1.0;
		for (PerkEffect effect : effects) {
			if (effect instanceof DamageTakenBlockingEffect blockingEffect) {
				total *= blockingEffect.multiplierFor(blocking);
			}
		}
		return total;
	}

	// ------------------------------------------------------------------ 낙하 피해 면역

	/**
	 * 이 피해를 통째로 버려야 하는가. {@code shield_fall_immunity} 만 본다.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} 진입점에서 부른다. 참이면
	 * 그 자리에서 {@code false} 를 돌려주므로 체력·무적시간·피격 애니메이션 어느 것도 움직이지
	 * 않는다. 배율 쪽({@link #scale})과 달리 값을 깎는 것이 아니라 사건 자체를 없앤다.
	 *
	 * <p>피해 종류를 <b>가장 먼저</b> 본다. 낙하가 아닌 피해는 팀 상태를 찾아보지도 않고 곧바로
	 * 빠져나가므로, 이 증강을 아무도 갖고 있지 않은 서버에서도 피해 경로에 얹히는 비용이 거의
	 * 없다.
	 */
	public static boolean blocksFallDamage(@Nullable Entity victim, @Nullable DamageSource source) {
		if (source == null || !(victim instanceof ServerPlayer player)) {
			return false;
		}
		try {
			if (!source.is(DamageTypes.FALL)) {
				return false;
			}
			return ShieldFallImmunityEffect.blocks(
					TeamLookup.stateOf(player.getUUID()), true, player.isBlocking());
		} catch (RuntimeException error) {
			warnOnce(error);
			return false;
		}
	}

	/**
	 * 막아 낸 낙하 피해만큼 방패를 닳게 한다. {@link #blocksFallDamage} 가 참을 돌려준 직후에만
	 * 부른다.
	 *
	 * <p>「버티는 방패」가 치르는 대가다. 바닐라에서 방패로 한 대를 막으면 피해량만큼 닳는데,
	 * 이 증강은 낙하 피해를 <b>통째로</b> 없애 주므로 그보다 훨씬 비싸야 한다. 그래서
	 * {@value #FALL_BLOCK_DURABILITY_FACTOR} 배를 깎는다 — 높은 데서 뛰어내릴수록 방패가 빨리
	 * 부서지고, 부서지면 그 회차에 다시 만들어야 한다.
	 *
	 * <p>닳는 대상은 {@code getUseItem()} — <b>지금 들어 올려 막고 있는 바로 그 물건</b>이다.
	 * 주 손인지 왼손인지 따로 가리지 않아도 되고, 방패가 아닌 무언가로 막는 물건이 생겨도
	 * 그대로 맞는다.
	 *
	 * <p>내구도가 없는 물건이면 아무 일도 하지 않는다. 실제로 깎이는 양은 내구성 마법과
	 * {@code durability_multiplier} 증강을 지나며 다시 줄어든다
	 * ({@link com.sharedfate.mixin.ItemStackDurabilityMixin}).
	 *
	 * <p>세트 「방어 3단계」를 켠 팀이면 열 배가 아니라 바닐라처럼 막은 양만큼만 닳는다
	 * ({@link PerkDrawbacks#builtInWaived}). 대가가 효과 안에 묶여 있어 {@code drawback} 표시로는
	 * 끌 수 없기 때문이다.
	 *
	 * @param blocked 막아 낸 피해량. 배율이 이미 반영된 값이다
	 */
	public static void wearShieldForBlockedFall(@Nullable Entity victim, float blocked) {
		if (!(victim instanceof ServerPlayer player) || !Float.isFinite(blocked) || blocked <= 0.0F) {
			return;
		}
		try {
			ItemStack shield = player.getUseItem();
			if (shield.isEmpty() || !shield.isDamageableItem()) {
				return;
			}
			boolean waived = PerkDrawbacks.builtInWaived(
					TeamLookup.stateOf(player.getUUID()), ShieldFallImmunityEffect.class);
			shield.hurtAndBreak(fallBlockDurabilityCost(blocked, waived), player,
					player.getUsedItemHand());
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * 막아 낸 낙하 피해에 방패가 닳을 양. 월드를 보지 않는 순수 계산이다.
	 *
	 * @param blocked 막아 낸 피해량
	 * @param waived  세트 「방어 3단계」로 대가가 사라졌는가
	 */
	static int fallBlockDurabilityCost(float blocked, boolean waived) {
		int factor = waived ? FALL_BLOCK_DURABILITY_FACTOR_WAIVED : FALL_BLOCK_DURABILITY_FACTOR;
		return Math.max(1, Math.round(blocked) * factor);
	}

	// ------------------------------------------------------------------ 바닐라 피격 쿨타임 판정

	/**
	 * 이 공격이 바닐라 피격 쿨타임을 통과하고 <b>실제로</b> 줄 피해.
	 *
	 * <p>{@code LivingEntity.hurtServer} 의 판정을 그대로 베낀 <b>순수 계산</b>이다. 월드도
	 * 엔티티도 보지 않으므로 살아 있는 서버 없이 시험할 수 있고, 믹스인 안에 갇혀 있던 판정이
	 * 조용히 썩는 것을 막는다. {@code SpreadDamageManager.gate} 와 같은 방식이다.
	 *
	 * <p>26.3 바이트코드({@code hurtServer} 181~248행)를 그대로 옮기면 이렇다.
	 *
	 * <pre>{@code
	 * if ((float) this.damageCooldownTime > 10.0F && !source.is(BYPASSES_COOLDOWN)) {
	 *     if (!(amount > this.lastHurt)) {
	 *         return false;                                  // 통째로 버린다
	 *     }
	 *     this.actuallyHurt(level, source, amount - this.lastHurt);   // 넘치는 만큼만 들어간다
	 *     this.lastHurt = amount;
	 * } else {
	 *     this.lastHurt = amount;
	 *     this.damageCooldownTime = 20;
	 * }
	 * }</pre>
	 *
	 * <p><b>세는 칸이 무엇인지가 판 사이에 바뀌었다.</b> 26.2 까지는
	 * {@code Entity.invulnerableTime} 하나가 이 판정을 맡았으나, 26.3 에서
	 * {@code LivingEntity.damageCooldownTime}({@code public int})이 새로 생겨 그쪽으로 옮겨 갔다.
	 * {@code Entity.invulnerableTime} 은 {@code private} 이 되면서 피해와 상관이 없어졌고,
	 * 이제 {@code Entity.commonTick} 에서 줄어들고 NBT 로 오가며
	 * {@code isTemporarilyInvulnerable()} 이 읽을 뿐이다. 두 칸을 같은 것으로 착각한 채 판을
	 * 올려 이 판정이 한동안 죽어 있었고, 그 사실은
	 * {@code SpreadDamageTargetTest.피해_판정은_무적시간_칸을_보지_않는다} 가 붙들고 있다.
	 *
	 * <p>경계는 {@code > 10} 이다. 바닐라가 쿨타임을 20 으로 채우므로 <b>맞은 뒤 앞의 0.5초</b>가
	 * 이 구간이고, 그 사이에 들어온 공격은 직전 피해를 넘는 몫만 실제로 들어간다.
	 *
	 * @param amount             이번에 들어온 피해량
	 * @param lastHurt           직전에 받아들인 피해량({@code LivingEntity.lastHurt})
	 * @param damageCooldownTime 남은 피격 쿨타임({@code LivingEntity.damageCooldownTime})
	 * @param bypassesCooldown   피해 종류가 {@code #minecraft:bypasses_cooldown} 인가
	 * @return 실제로 들어갈 피해량. 바닐라가 통째로 버릴 한 대면 0
	 */
	public static float effectiveAmount(float amount, float lastHurt, int damageCooldownTime,
			boolean bypassesCooldown) {
		if (damageCooldownTime > DAMAGE_COOLDOWN_GATE_TICKS && !bypassesCooldown) {
			// 바닐라는 amount <= lastHurt 이면 false 를 돌려주고 아무것도 바꾸지 않는다. 그 몫은
			// 「들어가지 않은 피해」이므로 음수가 아니라 0 이 맞다.
			return Math.max(0.0F, amount - lastHurt);
		}
		return amount;
	}

	// ------------------------------------------------------------------ 몹 피해 한 번 막기

	/**
	 * 이 피해를 「호위」({@code damage_ward})가 통째로 막아야 하는가.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} 진입점에서 부른다. 참이면 그
	 * 자리에서 {@code false} 를 돌려주므로 체력·무적시간·피격 애니메이션 어느 것도 움직이지 않는다.
	 * {@link #blocksFallDamage} 와 같은 방식이다.
	 *
	 * <p><b>참을 돌려주는 순간 그 사람의 쿨타임이 시작된다.</b> 그래서 값싼 검사를 모두 통과한
	 * 뒤에 맨 마지막으로 쿨타임을 건드린다. 순서는 이렇다.
	 *
	 * <ol>
	 *   <li>피해자가 팀원이고 실제로 깎일 피해량이 있는가 — 0 짜리 피해에 쿨타임을 쓰지 않는다.</li>
	 *   <li><b>가해자가 몹인가</b> — {@link #isMobAttack} 참고. 낙하·굶주림·용암·플레이어 피해는
	 *       여기서 걸러진다.</li>
	 *   <li>이 사람이 「호위」를 골랐는가 — {@link DamageWardEffect#wardFor}.</li>
	 *   <li>어차피 안 들어갈 피해는 아닌가 — 크리에이티브·무적 명령·이미 죽은 상태.</li>
	 *   <li>쿨타임이 찼는가 — {@link DamageWardTracker#tryConsume}.</li>
	 * </ol>
	 *
	 * <p><b>피격 쿨타임(맞은 뒤 0.5초) 안에 들어와 어차피 버려질 피해는 이 자리까지 오지
	 * 않는다.</b> 그 판정에 필요한 {@code LivingEntity.lastHurt} 와
	 * {@code LivingEntity.damageCooldownTime} 은 여기서 읽을 수 없어,
	 * {@code LivingEntityPerkDamageMixin} 이 {@code @Shadow} 로 두 칸을 읽어
	 * {@link #effectiveAmount} 에 넣고 그 값이 0 보다 클 때만 이것을 부른다. 그래서 몹이 여럿
	 * 달라붙어도 「호위」는 <b>실제로 아픈 첫 대</b>에만 쓰인다.
	 *
	 * @param level  피해가 처리되는 월드. 지금 시각(게임 시간)을 여기서 읽는다
	 * @param victim 피해를 받는 대상
	 * @param source 피해원
	 * @param amount 진입점이 받은 피해량
	 */
	public static boolean blocksMobDamage(@Nullable ServerLevel level, @Nullable Entity victim,
			@Nullable DamageSource source, float amount) {
		if (level == null || source == null || !(victim instanceof ServerPlayer player)
				|| !(amount > 0.0F)) {
			return false;
		}
		try {
			if (!isMobAttack(source)) {
				return false;
			}
			DamageWardEffect ward =
					DamageWardEffect.wardFor(TeamLookup.stateOf(player.getUUID()), player.getUUID());
			if (ward == null) {
				return false;
			}
			if (player.isInvulnerableTo(level, source) || player.isDeadOrDying()) {
				return false;
			}
			return DamageWardTracker.tryConsume(
					player.getUUID(), level.getGameTime(), ward.cooldownTicks());
		} catch (RuntimeException error) {
			warnOnce(error);
			return false;
		}
	}

	/**
	 * 이 피해를 몹이 준 것인가.
	 *
	 * <p>판정 규칙은 {@link com.sharedfate.perk.MobPerkModifiers#damageMultiplier} 와 <b>똑같다</b>.
	 * {@code DamageSource.getEntity()} 가 {@code Mob} 이면 몹 피해다. 화살·불덩이처럼 던진 것에
	 * 맞은 경우에도 그 자리는 쏜 몹을 가리키므로 함께 잡힌다. 플레이어는 {@code Mob} 이 아니라
	 * 자연히 빠지고, 낙하·굶주림·용암처럼 가해자가 없는 피해는 {@code null} 이라 빠진다.
	 *
	 * <p>두 곳이 같은 규칙을 써야 「몹에게서 받는 것」이라는 말이 증강마다 다른 뜻이 되지 않는다.
	 */
	private static boolean isMobAttack(DamageSource source) {
		return source.getEntity() instanceof Mob;
	}

	// ------------------------------------------------------------------ 무기 넉백

	/**
	 * 이 공격에 걸릴 {@code weapon_knockback} 의 넉백. 걸릴 것이 없으면 음수.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code LivingEntity.getKnockback} 에서 부른다.
	 * 그 자리의 {@code this} 는 <b>때리는 쪽</b>이고 첫 인자가 맞는 쪽이다.
	 *
	 * <p>때리는 쪽이 팀원이 아니면(몹끼리의 싸움 포함) 첫 줄에서 곧바로 빠져나가므로, 이 증강을
	 * 아무도 갖고 있지 않은 서버의 전투 경로에는 얹히는 비용이 거의 없다.
	 *
	 * @return {@code getKnockback} 이 돌려줄 값. 걸릴 것이 없으면 {@code -1}
	 */
	public static float weaponKnockback(@Nullable Entity attacker, @Nullable Entity target) {
		if (target == null || !(attacker instanceof ServerPlayer player)) {
			return -1.0F;
		}
		try {
			return WeaponKnockbackEffect.strengthFor(TeamLookup.stateOf(player.getUUID()),
					player.getUUID(), player.getMainHandItem(), target instanceof Player);
		} catch (RuntimeException error) {
			warnOnce(error);
			return -1.0F;
		}
	}

	/**
	 * 이 팀이 가진 {@code damage_taken_from} 중 이 피해원에 걸리는 것들의 배율을 모두 곱한 값.
	 *
	 * <p><b>보유 증강과 세트를 둘 다 훑는다.</b> 세트에서 이 타입을 쓰는 단계는 아직 없지만,
	 * 훑는 자리를 한 곳으로 맞춰 두어야 나중에 넣는 사람이 「빌드는 통과하는데 아무 일도 안
	 * 일어나는」 함정에 빠지지 않는다.
	 */
	static double takenSourceMultiplier(@Nullable TeamState state, @Nullable DamageSource source) {
		if (state == null || source == null || state.ownedPerks.isEmpty()) {
			return 1.0;
		}
		// 「화살막이」의 폭발 피해 ×1.2 가 이 길로 들어온다. 세트 「방어 3단계」를 켠 팀에서는
		// 그 한 줄만 빠지고 ×0.5 쪽은 그대로 남는다.
		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		// 「굳은살」은 면제되고 남은 대가에만 걸린다. 면제된 것은 위에서 곱하지 않으므로 겹치지 않는다.
		DamageIncreaseReliefEffect.Relief relief = DamageIncreaseReliefEffect.reliefFor(state);
		double total = 1.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			total *= takenSourceMultiplierOf(perk.effects(), source, waiver, perk, relief);
		}
		// 세트가 건 배율에는 대가가 없다. 걸러 낼 것이 없으므로 판정기를 넘기지 않는다.
		total *= takenSourceMultiplierOf(PerkSetEffects.activeEffectsOf(state), source, null, null,
				null);
		// 배율 0 은 「타지 않는 살갗」의 화염 면역이라 그대로 살린다. 예전에는 0 도 1.0 으로
		// 물러나 면역이 통째로 사라지고 받는 피해 ×1.2 만 남았다. 음수와 무한대만 물러난다.
		return Double.isFinite(total) && total >= 0.0 ? total : 1.0;
	}

	/**
	 * 효과 목록만 보는 순수 계산.
	 *
	 * @param waiver 대가를 건너뛸지 판정할 그릇. {@code null} 이면 전부 센다
	 * @param owner  이 목록을 가진 증강. 판정을 빠르게 하려고 함께 넘긴다
	 * @param relief 늘어난 몫을 덜어 낼 그릇. {@code null} 이면 그대로 센다
	 */
	private static double takenSourceMultiplierOf(@Nullable Iterable<PerkEffect> effects,
			@Nullable DamageSource source, PerkDrawbacks.@Nullable Waiver waiver,
			@Nullable Perk owner, DamageIncreaseReliefEffect.@Nullable Relief relief) {
		if (effects == null || source == null) {
			return 1.0;
		}
		double total = 1.0;
		for (PerkEffect effect : effects) {
			if (effect instanceof DamageTakenFromEffect from) {
				if (waiver != null && waiver.waives(owner, effect)) {
					continue;
				}
				double multiplier = from.multiplierFor(source);
				total *= relief == null ? multiplier : relief.ease(multiplier);
			}
		}
		return total;
	}

	/**
	 * 배율을 따질 값어치가 있는 플레이어인지 본다.
	 *
	 * <p>팀 미소속, 증강을 끈 팀, 아직 아무 증강도 없는 팀은 여기서 걸러진다. 피해 계산은 초당
	 * 수십 번 도는 자리라 이 빠른 경로가 곧 기본 경로다.
	 */
	private static boolean perksActive(ServerPlayer player) {
		TeamState state = TeamLookup.stateOf(player.getUUID());
		return state != null && state.perksEnabled && !state.ownedPerks.isEmpty();
	}

	/** 배율을 곱하고 안전한 범위로 자른다. 배율이 1.0 이면 원래 값을 그대로 돌려준다. */
	static float combine(float amount, double factor) {
		if (factor == 1.0 || !Double.isFinite(factor) || factor < 0.0) {
			return amount;
		}
		double scaled = (double) amount * factor;
		if (!Double.isFinite(scaled)) {
			return MAX_DAMAGE;
		}
		return (float) Math.max(0.0, Math.min(MAX_DAMAGE, scaled));
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"증강 피해 배율을 읽지 못해 이번 피해는 배율 없이 처리합니다. 이 경고는 한 번만 남습니다.",
				error);
	}

	/** 테스트가 경고 억제 상태를 되돌릴 때 쓴다. */
	static void resetWarnedForTesting() {
		warned = false;
	}
}
