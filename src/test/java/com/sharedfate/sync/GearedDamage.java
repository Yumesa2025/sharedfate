package com.sharedfate.sync;

/**
 * <b>완전무장한 사람이 실제로 받는 피해.</b> 안전 시험이 20 과 견주는 값을 여기서 만든다.
 *
 * <h2>왜 이것이 필요한가 — 기준이 한 번 바뀌었다</h2>
 *
 * <p>전에는 「팀 공유 체력 20, 한 틱 피해 20 미만」을 <b>카드에 적힌 날값</b>으로 쟀다. 그런데
 * 시련은 엔드까지 온 팀이 받는 <b>추가 난이도</b>이고, 사람이 「다이아셋 + 보호 인챈트까지 하고
 * 맞는 것까지 고려해야 한다」고 정했다. 날값으로 재면 실제로는 1/8 만 들어오는 값을 천장으로
 * 삼게 되고, 그래서 「안 아프다」가 나왔다.
 *
 * <p><b>이 클래스를 지우고 날값 비교로 되돌리지 말 것.</b> 되돌리는 순간 카드의 피해 값이
 * 전부 한 자리수로 끌려 내려가고, 그것이 사람이 고친 바로 그 문제다.
 *
 * <h2>식은 26.3 에서 읽은 것이다</h2>
 *
 * <p>지어낸 근사가 아니라 바이트코드에서 확인한 실제 경로다. 판이 올라 저쪽이 바뀌면 여기도
 * 함께 바꿀 것.
 *
 * <ol>
 *   <li><b>난이도</b> — {@code Player.hurtServer} 가 {@code DamageSource.scalesWithDifficulty()}
 *       이면 하드에서 {@code 피해 × 3 / 2} 한다. <b>방어보다 먼저</b>다</li>
 *   <li><b>방어</b> — {@code CombatRules.getDamageAfterAbsorb} 가
 *       {@code 피해 × (1 − clamp(방어 − 피해/(2 + 강도/4), 방어 × 0.2, 20) / 25)}</li>
 *   <li><b>보호</b> — {@code CombatRules.getDamageAfterMagicAbsorb} 가
 *       {@code 피해 × (1 − clamp(EPF, 0, 20) / 25)}</li>
 * </ol>
 *
 * <p>이 순서가 곧 이 클래스의 {@link #afterGear} 다.
 *
 * <h2>⚠ 감쇠는 <b>한 방마다</b> 걸린다</h2>
 *
 * <p>겹친 고리 둘에 맞아도 {@code 감쇠(피해 × 2)} 가 아니라 {@code 감쇠(피해) × 2} 다. 방어
 * 공식이 피해량에 따라 감쇠율이 달라지는 비선형이라 두 값이 다르고, 합쳐서 감쇠하면 실제보다
 * <b>아프게</b> 나온다. 그래서 {@link #afterGear} 는 언제나 <b>한 방</b>을 받는다 — 곱하는 것은
 * 부르는 쪽 몫이다.
 *
 * <h2>이 기준이 봐 주지 <b>않는</b> 사람</h2>
 *
 * <p><b>죽어서 장비를 잃고 돌아온 사람</b>에게는 이 감쇠가 하나도 안 걸린다. 적힌 값을 그대로
 * 맞고, 큰 카드는 그 날값이 팀 체력 20 을 넘는다 — <b>맨몸이면 한 대에 전멸</b>이다. 사람이 그
 * 사실을 듣고도 「3대」로 가자고 했으므로 값을 되돌리지는 않되, 「즉사 메커닉 0개」가 이제
 * <b>무장 기준의 약속</b>이라는 것을 시험 이름과 이 설명에 남겨 둔다.
 */
final class GearedDamage {

	/** 다이아 풀셋의 방어도. 투구 3 · 흉갑 8 · 각반 6 · 부츠 3. */
	static final float ARMOR = 20.0F;
	/** 다이아 풀셋의 방어 강도. 조각마다 2 다. */
	static final float TOUGHNESS = 8.0F;
	/**
	 * 보호 IV 네 곳의 EPF.
	 *
	 * <p>26.3 의 {@code protection.json} 은 {@code damage_protection} 을
	 * {@code linear(base 1, per_level_above_first 1)} 로 준다 — 레벨 IV 가 조각당 4 이고 네
	 * 곳이면 16 이다. 상한이 20 이므로 여기는 아직 천장이 아니다.
	 */
	static final float PROTECTION_EPF = 16.0F;
	/** 하드에서 {@code scaling} 이 걸리는 피해에 붙는 곱. {@code Player.hurtServer} 의 {@code × 3 / 2}. */
	static final float HARD_MULTIPLIER = 1.5F;

	/** 팀 공유 체력. {@code PerkHealthRules.effectiveMaxHealth(null)} 와 같아야 한다. */
	static final float TEAM_HEALTH = 20.0F;
	/** 사람이 정한 것 — 「큰자리는 3대맞으면 죽는거로 생각하자」. */
	static final int HITS_TO_WIPE = 3;
	/**
	 * 그 「3대」를 한 대로 나눈 값(6.67).
	 *
	 * <p>⚠ <b>카드가 맞춰야 하는 값이 아니다.</b> 적힌 값을 <b>역산할 때 쓰는 입력</b>일 뿐이다.
	 * 카드에 적히는 것이 정수라 정확히 6.67 을 만드는 값이 없고, 난이도 곱이 없는 쪽은 35(한 대
	 * 6.93), 있는 쪽은 23(한 대 6.77)으로 <b>서로 다르다.</b> 둘 다 「세 대에 죽는다」를
	 * 만족하면 그것으로 끝이다 — <b>소수점을 맞추려고 값을 다시 굴리지 말 것.</b>
	 *
	 * <p>시험은 이 값과의 거리가 아니라 <b>{@code 두 대로는 안 죽고 세 대에는 죽는가}</b>를
	 * 묻는다. 허용 오차를 정할 필요가 없고, 역산값을 내림해 34 로 두면(세 대 19.83) 곧바로
	 * 걸린다.
	 */
	static final float TARGET_PER_HIT = TEAM_HEALTH / HITS_TO_WIPE;

	/**
	 * 그 한 방으로 세 대를 맞으면 팀이 지워지는가. 「큰 카드」가 만족해야 하는 조건이다.
	 *
	 * @param perHitGeared {@link #afterGear} 를 지난 한 대의 값
	 */
	static boolean wipesInThree(float perHitGeared) {
		return perHitGeared * HITS_TO_WIPE >= TEAM_HEALTH
				&& perHitGeared * (HITS_TO_WIPE - 1) < TEAM_HEALTH;
	}

	/**
	 * 실행기가 쓰는 피해원의 종류.
	 *
	 * <p><b>카드가 아니라 실행기가 고르는 값이다.</b> 실행기에서 {@code damageSources()} 를 바꾸면
	 * 여기 적힌 것도 함께 바꿀 것 — 어긋나면 시험은 계속 통과하면서 실제 피해만 달라진다.
	 */
	enum Source {
		/**
		 * {@code damageSources().lightningBolt()} — {@code TrialRisks.detonate} 가 쓴다.
		 *
		 * <p>{@code lightning_bolt} 의 {@code scaling} 은
		 * {@code when_caused_by_living_non_player} 인데, 우리 피해원은 가해 개체를 달지 않으므로
		 * {@code causingEntity} 가 {@code null} 이라 <b>하드 곱이 걸리지 않는다.</b> 방어는
		 * 그대로 듣는다 — {@code #bypasses_armor} 에 없다.
		 */
		LIGHTNING_BOLT(false, false),
		/**
		 * {@code damageSources().explosion(null, null)} — 시련 실행기 대부분과
		 * {@code DragonFireBarrage} 가 쓴다.
		 *
		 * <p>둘 다 {@code null} 이면 {@code DamageTypes.EXPLOSION} 이고 그 {@code scaling} 이
		 * {@code always} 라 <b>하드에서 반드시 1.5배</b>가 된다.
		 */
		EXPLOSION(true, false),
		/**
		 * {@code damageSources().magic()} — {@code TrialDragonFocus} 가 쓴다.
		 *
		 * <p>{@code magic} 은 {@code #bypasses_armor} 에 들어 있어 <b>방어도가 하나도 안
		 * 듣는다.</b> 보호 인챈트는 그대로 듣고, {@code scaling} 은 가해자가 없어 안 걸린다.
		 */
		MAGIC(false, true);

		private final boolean scalesOnHard;
		private final boolean bypassesArmor;

		Source(boolean scalesOnHard, boolean bypassesArmor) {
			this.scalesOnHard = scalesOnHard;
			this.bypassesArmor = bypassesArmor;
		}

		/** 하드에서 {@link #HARD_MULTIPLIER} 가 걸리는가. */
		boolean scalesOnHard() {
			return scalesOnHard;
		}

		/** 방어도를 지나치는가. 지나쳐도 보호 인챈트는 듣는다. */
		boolean bypassesArmor() {
			return bypassesArmor;
		}
	}

	private GearedDamage() {
	}

	/**
	 * 적힌 값 <b>한 방</b>이 완전무장한 사람에게 실제로 들어가는 양.
	 *
	 * <p>여럿이 겹치는 경우는 이 값을 <b>부르는 쪽이 곱한다</b> — 까닭은 클래스 설명의
	 * 「감쇠는 한 방마다 걸린다」에 있다.
	 *
	 * @param written 카드나 상수에 적힌 값
	 * @param source  실행기가 쓰는 피해원
	 */
	static float afterGear(float written, Source source) {
		if (written <= 0.0F) {
			return 0.0F;
		}
		float damage = written;
		if (source.scalesOnHard()) {
			damage = damage * HARD_MULTIPLIER;
		}
		if (!source.bypassesArmor()) {
			damage = afterArmor(damage);
		}
		return afterProtection(damage);
	}

	/** {@code CombatRules.getDamageAfterAbsorb} 를 값으로만 다시 적은 것. */
	static float afterArmor(float damage) {
		float divider = 2.0F + TOUGHNESS / 4.0F;
		float effective = clamp(ARMOR - damage / divider, ARMOR * 0.2F, 20.0F);
		return damage * (1.0F - effective / 25.0F);
	}

	/** {@code CombatRules.getDamageAfterMagicAbsorb} 를 값으로만 다시 적은 것. */
	static float afterProtection(float damage) {
		return damage * (1.0F - clamp(PROTECTION_EPF, 0.0F, 20.0F) / 25.0F);
	}

	/** {@code Mth.clamp(값, 아래, 위)} 와 같은 순서다. */
	private static float clamp(float value, float low, float high) {
		return Math.max(low, Math.min(high, value));
	}
}
