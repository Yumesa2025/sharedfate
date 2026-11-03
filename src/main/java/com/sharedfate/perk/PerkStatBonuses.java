package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 증강·세트가 속성에 얹은 <b>보정치</b>만 골라 「몇 % 올렸는가」로 바꾸는 계산기.
 *
 * <p>인벤토리 옆 능력치 패널이 「추가 공격력 +35%」처럼 적는 숫자가 여기서 나온다. 무기를 들
 * 때마다 바뀌는 최종 공격력이 아니라, <b>무엇을 들든 함께 곱해지는 몫</b>이다.
 *
 * <h2>왜 증강 목록이 아니라 붙어 있는 수정자를 읽는가</h2>
 * <p>조건부({@code conditional}: 뭉침·체력 절반 등)·주기({@code periodic})·처치 보상처럼 <b>지금
 * 켜져 있을 때만</b> 수정자를 거는 효과가 많다. 증강 목록으로 다시 세면 꺼져 있는 것까지 더해
 * 거짓말이 된다. 붙어 있는 수정자는 그 효과들이 이미 판정을 끝낸 결과라 언제나 지금 사실이다.
 * 세트 효과도 같은 길({@code AttributeEffect})로 수정자를 걸므로 따로 셀 것이 없다.
 *
 * <h2>어느 수정자가 증강의 것인가</h2>
 * <p>{@code sharedfate:perk/…} 이름을 쓰는 것만 센다({@link #counts}). 증강은
 * {@code perk/<증강 id>/<순번>}, 세트는 {@code perk/set/<유형>/<단계>/<순번>} 으로 이름을 짓는다
 * ({@code AttributeEffect.modifierId}·{@code PerkSetRegistry.effectOwnerId}). 바닐라의 달리기·물약,
 * 손에 든 무기 자신의 수정자는 {@code minecraft:} 이름이라 저절로 빠진다.
 *
 * <p>{@link PerkWeaponDamage#MODIFIER_ID} 만은 이름이 같은 모양인데도 뺀다. 「우대 무기를 들면
 * ×2」처럼 <b>지금 든 무기에 따라</b> 걸렸다 풀렸다 하는 값이라, 넣으면 핫바를 돌릴 때마다
 * 패널이 출렁인다. 사람이 원한 것이 바로 그 출렁임이 없는 숫자다.
 *
 * <p>「삽질의 대가」의 상쇄 수정자({@code perk/damage_boost_ban/…})는 이름 규칙대로 <b>들어간다.</b>
 * 증강이 올린 공격력을 지운 결과가 실제이므로 패널도 그 결과를 적어야 한다. 다만 그 상쇄는 힘
 * 물약처럼 증강 밖의 이득까지 지우므로, 그런 것이 걸린 동안에는 패널이 실제보다 낮게(음수로)
 * 적을 수 있다. 드문 조합이라 그대로 둔다.
 *
 * <h2>바닐라 계산 순서</h2>
 * <p>{@code (기본값 + Σ더하기) × (1 + Σ기본곱) × Π(1 + 전체곱)}. 여기서도 같은 순서로 센다.
 */
public final class PerkStatBonuses {
	private static final String PERK_PREFIX = "perk/";
	/** 기본값이 이보다 작으면 0 으로 보고 「기본값에 대한 비율」을 구하지 않는다. */
	private static final double EPSILON = 1.0E-6;

	private PerkStatBonuses() {
	}

	/** 이 수정자가 증강·세트의 보정치로 셀 것인가. */
	public static boolean counts(@Nullable Identifier id) {
		return id != null
				&& SharedFateMod.MOD_ID.equals(id.getNamespace())
				&& id.getPath().startsWith(PERK_PREFIX)
				&& !PerkWeaponDamage.MODIFIER_ID.equals(id);
	}

	/** 속성에 붙은 수정자 중 {@link #counts} 인 것만. 속성이 없으면 빈 목록. */
	public static List<AttributeModifier> perkModifiers(@Nullable AttributeInstance instance) {
		if (instance == null) {
			return List.of();
		}
		return perkModifiers(instance.getModifiers());
	}

	/** 수정자 묶음에서 {@link #counts} 인 것만 고른다. 살아 있는 서버 없이 시험하는 길이다. */
	public static List<AttributeModifier> perkModifiers(
			@Nullable Collection<AttributeModifier> modifiers) {
		List<AttributeModifier> picked = new ArrayList<>();
		if (modifiers == null) {
			return picked;
		}
		for (AttributeModifier modifier : modifiers) {
			if (modifier != null && counts(modifier.id())) {
				picked.add(modifier);
			}
		}
		return picked;
	}

	/** 더하기({@code add_value}) 수정자의 합. 「방어력 +3」처럼 그대로 적는 값이다. */
	public static double additive(@Nullable Collection<AttributeModifier> modifiers) {
		double total = 0.0;
		if (modifiers == null) {
			return total;
		}
		for (AttributeModifier modifier : modifiers) {
			if (modifier != null && modifier.operation() == AttributeModifier.Operation.ADD_VALUE
					&& Double.isFinite(modifier.amount())) {
				total += modifier.amount();
			}
		}
		return total;
	}

	/**
	 * 곱하기 수정자 둘({@code add_multiplied_base}·{@code add_multiplied_total})이 만드는 배율.
	 *
	 * <p>공격력에 쓴다. 두 곱은 무기가 얹은 값까지 통째로 곱하므로 <b>무엇을 들든 같은 비율</b>이다.
	 * 더하기는 여기 들어가지 않는다 — 맨손 1 에 +2 는 +200% 지만 검 7 에 +2 는 +29% 라,
	 * 비율로 바꾸면 든 것에 따라 숫자가 달라진다.
	 */
	public static double multiplicative(@Nullable Collection<AttributeModifier> modifiers) {
		double base = 0.0;
		double total = 1.0;
		if (modifiers == null) {
			return 1.0;
		}
		for (AttributeModifier modifier : modifiers) {
			if (modifier == null || !Double.isFinite(modifier.amount())) {
				continue;
			}
			switch (modifier.operation()) {
				case ADD_MULTIPLIED_BASE -> base += modifier.amount();
				case ADD_MULTIPLIED_TOTAL -> total *= 1.0 + modifier.amount();
				default -> {
				}
			}
		}
		double factor = (1.0 + base) * total;
		return Double.isFinite(factor) ? Math.max(0.0, factor) : 1.0;
	}

	/**
	 * 수정자를 모두 얹은 값이 기본값의 몇 배인가 — 이동 속도·공격 속도에 쓴다.
	 *
	 * <p>이 둘은 무기가 기본값을 바꾸지 않으므로(검의 공격 속도는 {@code minecraft:} 수정자라 이미
	 * 빠져 있다) 더하기까지 기본값에 대한 비율로 바꿔도 숫자가 흔들리지 않는다. 기본값이 0 이면
	 * 비율을 구할 수 없으니 곱하기만 본다.
	 */
	public static double ratio(double baseValue, @Nullable Collection<AttributeModifier> modifiers) {
		double factor = multiplicative(modifiers);
		if (!Double.isFinite(baseValue) || Math.abs(baseValue) < EPSILON) {
			return factor;
		}
		double value = (baseValue + additive(modifiers)) * factor;
		double result = value / baseValue;
		return Double.isFinite(result) ? Math.max(0.0, result) : 1.0;
	}
}
