package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.team.TeamState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 증강이 늘려 놓은 <b>받는 피해 증가분</b>을 일정 비율만큼 덜어 낸다. 실버 「굳은살」이 쓴다.
 *
 * <pre>{@code
 * { "type": "damage_increase_relief", "reduction": 0.5 }
 * }</pre>
 *
 * <p>{@code reduction} 은 증가분(배율 − 1)에서 덜어 낼 몫이다. 0.5 면 ×1.2 → ×1.1,
 * ×2 → ×1.5, 낙하 +0.2 → +0.1 이 된다. 빠뜨리면 0.5 다.
 *
 * <p>{@link NoAttackDamageLossEffect} 처럼 팀원에게 붙일 것이 없다. 「이미 있는 효과의 값을
 * 덜어 낸다」는 보상이라 이 클래스는 「이 팀에서 증가분이 얼마나 남는가」라는 물음에만 답한다.
 *
 * <h2>무엇을 덜어 내는가</h2>
 * <p>판정은 <b>효과가 실제로 하는 일</b>을 보고 한다. {@code drawback} 표시도 증강 id 목록도
 * 보지 않으므로 앞으로 들어올 증강의 증가분도 등록 없이 함께 걸린다.
 *
 * <ul>
 *   <li>{@code damage_taken} — 최상위 효과의 배율이 1 보다 크면. {@code conditional}·
 *       {@code holder}·{@code periodic} 은 <b>지금 걸린 배율 전체</b>(하위의 곱)를 하나로 보고
 *       덜어 낸다. 「불굴」의 체력 가득 참 ×1.1 이 이 길이다.</li>
 *   <li>{@code damage_taken_from} — 그 피해원에 걸린 배율이 1 보다 크면. 「화살막이」의 폭발
 *       ×1.2 가 이 길이다.</li>
 *   <li>{@code damage_taken_blocking} — 막는 중의 배율이 1 보다 크면. 지금은 쓰는 증강이 없다.</li>
 *   <li>{@code mob_damage} — 몹 공격력 배율이 1 보다 크면 <b>이 팀 몫만</b> 덜어 낸다.
 *       「유리 세계」·「엑스레이」·「반격」·「가벼운 위장」의 몹에게 받는 피해 대가와 「암살자」·
 *       「거인 사냥꾼」·「사냥꾼의 식사」가 이 길이다. 한 서버에 팀은 하나뿐이라 곧 그 팀이 받는
 *       피해다.</li>
 *   <li>{@code attribute} — 최상위의 {@code minecraft:fall_damage_multiplier} 에 <b>양수</b>를
 *       거는 것. +0.2 가 +0.1 로 붙는다. 지금 기본 풀에는 이 길을 타는 증강이 없다
 *       (「허공답보」의 대가는 2026-10-16 에 걷어냈다).</li>
 * </ul>
 *
 * <p><b>줄이는 쪽은 절대 건드리지 않는다.</b> 1 이하의 배율과 음수 수정자는 판정에서 곧바로
 * 빠져 평소와 한 톨도 다르지 않다.
 *
 * <p>세트가 건 효과는 덜어 내지 않는다. 덜어 내는 것은 「증강이 만든 증가분」이고, 지금 세트에는
 * 받는 피해를 늘리는 단계가 없다.
 *
 * <h2>세트 「방어 3단계」와 겹칠 때</h2>
 * <p>면제된 대가는 부르는 쪽이 아예 곱하지 않으므로 여기까지 오지 않는다. 남은 대가에만 걸린다.
 *
 * <h2>못 거는 곳</h2>
 * <p>{@code conditional}·{@code holder}·{@code periodic} 안에 든 낙하 피해 {@code attribute} 는
 * 그 효과가 자기 일정으로 하위를 붙였다 떼므로 덜어 내지 않는다. 지금 그런 정의는 없다.
 * 감싼 효과 하나에 줄이는 배율과 늘리는 배율이 함께 들면 둘의 곱으로 판정하므로, 그런 정의를
 * 새로 만들 때는 두 효과로 나눠 적는다.
 */
public final class DamageIncreaseReliefEffect implements PerkEffect {
	/** 빠뜨렸을 때 쓰는 몫. 증가분의 절반을 덜어 낸다. */
	static final double DEFAULT_REDUCTION = 0.5;

	/** 판정 기준이 되는 낙하 피해 속성. */
	public static final Identifier FALL_DAMAGE_MULTIPLIER_ID =
			Identifier.fromNamespaceAndPath("minecraft", "fall_damage_multiplier");

	private final double reduction;

	public DamageIncreaseReliefEffect(double reduction) {
		this.reduction = reduction;
	}

	/** JSON에서 만든다. 몫이 0~1 을 벗어나면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		boolean written = json.has("reduction");
		Double raw = written ? PerkEffectType.readDouble(json, "reduction") : null;
		double reduction = raw == null ? DEFAULT_REDUCTION : raw;
		// 적었는데 숫자가 아니면 기본값으로 덮지 않는다. 오타가 조용히 0.5 로 바뀌면 안 된다.
		boolean unreadable = written && raw == null;
		if (unreadable || !(reduction >= 0.0 && reduction <= 1.0)) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: damage_increase_relief 의 reduction 은 0~1 이어야 합니다 ({})",
					perkId, json.get("reduction"));
			return null;
		}
		return new DamageIncreaseReliefEffect(reduction);
	}

	/** 증가분에서 덜어 낼 몫. */
	public double reduction() {
		return reduction;
	}

	// ------------------------------------------------------------------ 순수 계산

	/**
	 * 배율의 증가분에 {@code keep} 만 남긴다. 1 이하이거나 이상한 값이면 그대로 돌려준다.
	 *
	 * @param multiplier 원래 배율
	 * @param keep       증가분 중 남길 몫. 1 이면 그대로, 0 이면 증가분이 모두 사라진다
	 */
	public static double ease(double multiplier, double keep) {
		if (!(multiplier > 1.0) || !Double.isFinite(multiplier) || !(keep < 1.0)) {
			return multiplier;
		}
		return 1.0 + (multiplier - 1.0) * Math.max(0.0, keep);
	}

	/** 이 효과가 낙하 피해를 늘리는 속성 수정자인가. 세트 판정과 무관한 순수 판정이다. */
	public static boolean raisesFallDamage(@Nullable PerkEffect effect) {
		return effect instanceof AttributeEffect attribute
				&& FALL_DAMAGE_MULTIPLIER_ID.equals(attribute.attributeId())
				&& attribute.amount() > 0.0;
	}

	// ------------------------------------------------------------------ 팀 판정

	/**
	 * 이 팀에서 증가분 중 남는 몫. 이 효과가 없으면 1.0 이다.
	 *
	 * <p>여럿이면 남는 몫을 곱한다. 보유 증강과 켜진 세트를 <b>둘 다</b> 본다 —
	 * {@link NoAttackDamageLossEffect#heldBy} 와 같은 모양이다.
	 */
	public static double keepFor(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled) {
			return 1.0;
		}
		double keep = 1.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			keep *= keepOf(perk.effects());
		}
		keep *= keepOf(PerkSetEffects.activeEffectsOf(state));
		return keep;
	}

	private static double keepOf(@Nullable Iterable<PerkEffect> effects) {
		if (effects == null) {
			return 1.0;
		}
		double keep = 1.0;
		for (PerkEffect effect : effects) {
			if (effect instanceof DamageIncreaseReliefEffect relief) {
				keep *= 1.0 - relief.reduction;
			}
		}
		return keep;
	}

	/** 이 팀을 기준으로 한 판정 그릇을 만든다. 판정 자체는 아직 하지 않는다. */
	public static Relief reliefFor(@Nullable TeamState state) {
		return new Relief(state);
	}

	/**
	 * 한 번의 조회 동안 「이 팀에서 증가분이 얼마나 남는가」를 <b>최대 한 번만</b> 계산해 들고
	 * 다니는 그릇.
	 *
	 * <p>피해 계산처럼 초당 여러 번 도는 자리에서 쓰므로, 1 보다 큰 배율을 <b>실제로 만났을
	 * 때</b> 한 번만 판정한다. 받는 피해를 늘리는 증강이 없는 팀은 보유 목록을 다시 훑지 않는다.
	 * {@link NoAttackDamageLossEffect.Gate} 와 같은 방식이다.
	 *
	 * <p>한 번의 조회 안에서만 쓰고 버린다. 서버 스레드에서만 오간다.
	 */
	public static final class Relief {
		private final @Nullable TeamState state;
		private double keep = Double.NaN;

		private Relief(@Nullable TeamState state) {
			this.state = state;
		}

		/** 1 보다 큰 배율이면 증가분을 덜어 낸 값, 아니면 그대로. */
		public double ease(double multiplier) {
			if (!(multiplier > 1.0)) {
				return multiplier;
			}
			return DamageIncreaseReliefEffect.ease(multiplier, keep());
		}

		/** 낙하 피해를 늘리는 수정자라면 덜어 낸 값, 아니면 그대로. */
		public double easeAmount(PerkEffect effect, double amount) {
			if (!(amount > 0.0) || !raisesFallDamage(effect)) {
				return amount;
			}
			return amount * Math.max(0.0, keep());
		}

		/**
		 * 효과를 붙인다. 낙하 피해를 늘리는 수정자면 덜어 낸 값으로, 아니면 평소대로.
		 *
		 * <p>{@code PerkManager.refreshPlayer} 가 최상위 효과마다 {@link PerkEffect#apply} 대신
		 * 부른다. 식별자가 같아 「굳은살」을 잃은 뒤의 다음 적용이 원래 값으로 덮어쓴다.
		 */
		public void apply(ServerPlayer player, PerkEffect effect) {
			if (effect instanceof AttributeEffect attribute && raisesFallDamage(attribute)
					&& keep() < 1.0) {
				attribute.apply(player, easeAmount(attribute, attribute.amount()));
				return;
			}
			effect.apply(player);
		}

		/** 남는 몫. 한 번만 계산하고 기억한다. */
		public double keep() {
			if (Double.isNaN(keep)) {
				keep = keepFor(state);
			}
			return keep;
		}
	}
}
