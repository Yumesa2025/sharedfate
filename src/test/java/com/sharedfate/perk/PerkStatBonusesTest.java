package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인벤토리 옆 패널의 보정치가 <b>어느 수정자를 세고 어떻게 % 로 바꾸는가</b>.
 *
 * <p>살아 있는 서버 없이 수정자 묶음만으로 본다. 조건부 효과가 「꺼져 있다」는 것은 곧 수정자가
 * 붙어 있지 않다는 뜻이라, 묶음에서 빼는 것으로 그 경우를 그대로 흉내 낸다.
 */
class PerkStatBonusesTest {
	private static final double DELTA = 1.0E-9;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static AttributeModifier perk(String perkId, int index, double amount,
			Operation operation) {
		return new AttributeModifier(AttributeEffect.modifierId(perkId, index), amount, operation);
	}

	private static AttributeModifier vanilla(String path, double amount, Operation operation) {
		return new AttributeModifier(Identifier.fromNamespaceAndPath("minecraft", path), amount,
				operation);
	}

	// ------------------------------------------------------------------ 무엇을 세는가

	@Test
	void 증강과_세트의_수정자를_센다() {
		assertTrue(PerkStatBonuses.counts(AttributeEffect.modifierId("sharedfate:berserk", 0)));
		// 세트는 증강 id 자리에 set/<유형>/<단계> 를 넣는다(PerkSetRegistry.effectOwnerId).
		assertTrue(PerkStatBonuses.counts(AttributeEffect.modifierId(
				PerkSetRegistry.effectOwnerId(PerkSetType.POWER, 2), 0)));
	}

	/** 손에 든 무기·달리기·물약은 바닐라 이름이다. 핫바를 돌릴 때마다 출렁이면 안 된다. */
	@Test
	void 바닐라_수정자는_세지_않는다() {
		assertFalse(PerkStatBonuses.counts(Identifier.fromNamespaceAndPath("minecraft", "sprinting")));
		assertFalse(PerkStatBonuses.counts(
				Identifier.fromNamespaceAndPath("minecraft", "base_attack_damage")));
	}

	/** 「우대 무기 ×2」는 증강이지만 지금 든 무기에 따라 걸렸다 풀린다. 최종값 쪽 사정이다. */
	@Test
	void 무기에_따라_바뀌는_증강_수정자는_세지_않는다() {
		assertFalse(PerkStatBonuses.counts(PerkWeaponDamage.MODIFIER_ID));
	}

	/** 같은 모드라도 증강이 아닌 것 — 팀 최대 체력 맞추기·난이도 상승 — 은 빠진다. */
	@Test
	void 증강이_아닌_모드_수정자는_세지_않는다() {
		assertFalse(PerkStatBonuses.counts(Identifier.fromNamespaceAndPath("sharedfate",
				"shared_max_health")));
		assertFalse(PerkStatBonuses.counts(Identifier.fromNamespaceAndPath("sharedfate",
				"difficulty/mob_health")));
		assertFalse(PerkStatBonuses.counts(null));
	}

	@Test
	void 묶음에서_증강_것만_골라낸다() {
		AttributeModifier mine = perk("sharedfate:a", 0, 0.2, Operation.ADD_MULTIPLIED_TOTAL);
		List<AttributeModifier> picked = PerkStatBonuses.perkModifiers(List.of(
				mine,
				vanilla("sprinting", 0.3, Operation.ADD_MULTIPLIED_TOTAL),
				new AttributeModifier(PerkWeaponDamage.MODIFIER_ID, 1.0, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(List.of(mine), picked);
	}

	// ------------------------------------------------------------------ 어떻게 % 로 바꾸는가

	/** 「추가 공격력」. 전체곱은 서로 곱해진다 — 1.2 × 1.1 = 1.32 라 +32% 다. */
	@Test
	void 공격력_곱하기는_바닐라_순서대로_곱한다() {
		double factor = PerkStatBonuses.multiplicative(List.of(
				perk("sharedfate:a", 0, 0.2, Operation.ADD_MULTIPLIED_TOTAL),
				perk("sharedfate:b", 0, 0.1, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(1.32, factor, DELTA);
	}

	/** 기본곱은 서로 더한 뒤 한 번 곱한다. 그 뒤에 전체곱이 곱해진다. */
	@Test
	void 기본곱은_더한_뒤_곱한다() {
		double factor = PerkStatBonuses.multiplicative(List.of(
				perk("sharedfate:a", 0, 0.2, Operation.ADD_MULTIPLIED_BASE),
				perk("sharedfate:b", 0, 0.1, Operation.ADD_MULTIPLIED_BASE),
				perk("sharedfate:c", 0, 0.5, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(1.3 * 1.5, factor, DELTA);
	}

	/** 곱하기에 더하기는 들어가지 않는다. 맨손 +200% 와 검 +29% 로 갈라지기 때문이다. */
	@Test
	void 공격력_더하기는_곱하기와_따로_센다() {
		List<AttributeModifier> mods = List.of(
				perk("sharedfate:a", 0, 2.0, Operation.ADD_VALUE),
				perk("sharedfate:b", 0, 0.35, Operation.ADD_MULTIPLIED_TOTAL));

		assertEquals(1.35, PerkStatBonuses.multiplicative(mods), DELTA);
		assertEquals(2.0, PerkStatBonuses.additive(mods), DELTA);
	}

	/** 이동 속도는 기본 0.1 이다. +15% 전체곱이면 기본값의 1.15 배. */
	@Test
	void 이동_속도는_기본값에_대한_비율이다() {
		double ratio = PerkStatBonuses.ratio(0.1, List.of(
				perk("sharedfate:a", 0, 0.15, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(1.15, ratio, DELTA);
	}

	/** 공격 속도 기본 4 에 +1 이면 +25%. 더하기도 기본값에 대한 비율로 바뀐다. */
	@Test
	void 공격_속도의_더하기는_기본값에_대한_비율로_바꾼다() {
		double ratio = PerkStatBonuses.ratio(4.0, List.of(
				perk("sharedfate:a", 0, 1.0, Operation.ADD_VALUE),
				perk("sharedfate:b", 0, 0.1, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(5.0 * 1.1 / 4.0, ratio, DELTA);
	}

	/** 대가로 깎는 증강은 음수가 된다. 「이동 속도 -10%」 가 그대로 보여야 한다. */
	@Test
	void 깎는_증강은_음수_비율이다() {
		double ratio = PerkStatBonuses.ratio(0.1, List.of(
				perk("sharedfate:a", 0, -0.1, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(0.9, ratio, DELTA);
	}

	/**
	 * 조건부(뭉침 등)는 <b>켜져 있을 때만</b> 수정자가 붙는다. 꺼지면 묶음에 없으므로 0 이다.
	 * 증강 목록으로 다시 세면 이 경우를 틀린다.
	 */
	@Test
	void 꺼진_조건부_효과는_수정자가_없어_0_이다() {
		AttributeModifier always = perk("sharedfate:a", 0, 0.2, Operation.ADD_MULTIPLIED_TOTAL);
		AttributeModifier whileNearby = perk("sharedfate:huddle", 0, 0.3,
				Operation.ADD_MULTIPLIED_TOTAL);

		assertEquals(1.2 * 1.3, PerkStatBonuses.multiplicative(List.of(always, whileNearby)), DELTA);
		assertEquals(1.2, PerkStatBonuses.multiplicative(List.of(always)), DELTA);
	}

	@Test
	void 아무것도_없으면_배율_1_더하기_0_이다() {
		assertEquals(1.0, PerkStatBonuses.multiplicative(List.of()), DELTA);
		assertEquals(0.0, PerkStatBonuses.additive(List.of()), DELTA);
		assertEquals(1.0, PerkStatBonuses.ratio(0.1, List.of()), DELTA);
		assertEquals(1.0, PerkStatBonuses.multiplicative(null), DELTA);
		assertTrue(PerkStatBonuses.perkModifiers((java.util.Collection<AttributeModifier>) null)
				.isEmpty());
	}

	/** 기본값이 0 인 속성은 비율을 구할 수 없다. 곱하기만 보고, 0 으로 나누지 않는다. */
	@Test
	void 기본값이_0_이면_곱하기만_본다() {
		double ratio = PerkStatBonuses.ratio(0.0, List.of(
				perk("sharedfate:a", 0, 3.0, Operation.ADD_VALUE),
				perk("sharedfate:b", 0, 0.2, Operation.ADD_MULTIPLIED_TOTAL)));

		assertEquals(1.2, ratio, DELTA);
	}
}
