package com.sharedfate.ui;

/**
 * 능력치 한 줄이 무엇을 적고 어떤 색으로 적히는지.
 *
 * <p>{@link StatSummary} 는 「값 → 값 (증감)」이라는 <b>글자 모양</b>만 안다. 같은 줄을
 * <b>인벤토리 화면과 팀 화면 두 곳</b>이 그리는데, 좁은 쪽에서는 이름을 줄이거나 두 줄로
 * 접어야 하고, 어떤 줄은 <b>오르는 것이 나쁜 값</b>이라 색이 뒤집혀야 한다.
 * 그 판단이 두 화면에 각각 적히면 언젠가 한쪽만 고쳐진다.
 *
 * @param label      팀 화면에 적는 이름. 「최대 체력」처럼 온전한 말이다
 * @param shortLabel 인벤토리 화면에 적는 이름. 창 왼쪽 자리가 좁아 「체력」까지 줄인다
 * @param base       기준값. 「→」 왼쪽에 적힌다
 * @param current    지금 값. 「→」 오른쪽에 적힌다
 * @param unit       숫자를 그대로 적을지 백분율로 적을지
 * @param sense      오르는 것이 좋은 값인가 나쁜 값인가. 색만 정하고 글자는 바꾸지 않는다
 * @param masked     증강이 이 값을 가리고 있는가. 참이면 숫자 대신 {@value #MASK} 를 적는다
 * @param suffix     넉넉한 자리에서만 뒤에 덧붙이는 말. 「(하트 13개)」처럼 있으면 좋지만
 *                   없어도 뜻이 통하는 것만 넣는다. {@link #fullLine()} 에만 붙는다
 */
public record StatRow(String label, String shortLabel, double base, double current,
		StatSummary.Unit unit, StatRow.Sense sense, boolean masked, String suffix)
		implements PanelRow {

	/**
	 * 값이 오르는 것이 이 팀에게 좋은 일인가.
	 *
	 * <p>대부분은 {@link #HIGHER_IS_BETTER} 다. 하지만 <b>받는 피해·몹 체력·몹 공격력</b>은
	 * 오르면 판이 험해진 것이므로 반대다. 「기본값과 다르다」만 보고 초록·빨강을 칠하면
	 * 이 셋에서 색이 거꾸로 붙어, 몹이 두 배가 된 것을 초록으로 알리게 된다.
	 */
	public enum Sense {
		HIGHER_IS_BETTER,
		LOWER_IS_BETTER
	}

	/** 좋아진 값. 팀 화면과 인벤토리 화면이 같은 초록을 써야 하므로 여기서 한 번만 정한다. */
	public static final int COLOR_GOOD = 0xFF80FF20;
	/** 나빠진 값. */
	public static final int COLOR_BAD = 0xFFFF6B6B;
	/** 기준값 그대로인 값. */
	public static final int COLOR_NEUTRAL = 0xFFE8E8F0;

	/**
	 * 가려진 값 대신 적는 글자.
	 *
	 * <p>「장님 거인」처럼 HUD 를 가리는 증강이 방어구 칸을 지우면, 방어력 숫자가 곧 그 칸이라
	 * 여기서도 가려야 한다. 줄을 <b>지우지 않고</b> 물음표를 적는 이유는, 줄이 통째로 사라지면
	 * 「그런 능력치가 없다」로 읽히기 때문이다.
	 */
	public static final String MASK = "???";

	/** 보통 줄. 가려져 있지 않다. */
	public static StatRow of(String label, String shortLabel, double base, double current,
			StatSummary.Unit unit, Sense sense) {
		return new StatRow(label, shortLabel, base, current, unit, sense, false, "");
	}

	/** 증강이 가린 줄. 숫자 대신 {@value #MASK} 를 적는다. */
	public static StatRow masked(String label, String shortLabel) {
		return new StatRow(label, shortLabel, 0.0, 0.0, StatSummary.Unit.RAW,
				Sense.HIGHER_IS_BETTER, true, "");
	}

	/**
	 * 배율 한 줄. 기준을 1.0 으로 놓고 백분율로 적는다 — 「몹 체력  100% → 115%」.
	 *
	 * <p>배율은 애초에 「몇 배」라는 뜻이라 1.15 를 그대로 적어도 틀리지는 않지만, 나머지 줄이
	 * 모두 「기준 → 지금」 모양이라 여기만 기준이 안 보이면 읽는 결이 끊긴다. 100% 로 적으면
	 * <b>아무것도 안 걸린 상태가 눈에 보인다.</b>
	 */
	public static StatRow multiplier(String label, String shortLabel, double multiplier,
			Sense sense) {
		return new StatRow(label, shortLabel, 1.0, multiplier, StatSummary.Unit.PERCENT, sense,
				false, "");
	}

	/** 덧붙임말을 단 사본. 자리가 넉넉한 화면에서만 보인다. */
	public StatRow withSuffix(String value) {
		return new StatRow(label, shortLabel, base, current, unit, sense, masked,
				value == null ? "" : value);
	}

	/** 온전한 이름으로 적은 한 줄. 팀 화면과 넓은 인벤토리 화면이 쓴다. */
	public String fullLine() {
		String head = masked ? label + "  " + MASK : StatSummary.line(label, base, current, unit);
		return head + suffix;
	}

	/** 이름만 줄인 한 줄. 증감 괄호는 그대로 남는다. */
	public String shortLine() {
		return masked
				? shortLabel + "  " + MASK
				: StatSummary.line(shortLabel, base, current, unit);
	}

	/**
	 * 증감 괄호까지 뗀 한 줄 — 「체력 20 → 26」.
	 *
	 * <p>괄호를 떼도 <b>무엇이 얼마나 달라졌는지는 남는다.</b> 20 과 26 이 나란히 있으므로
	 * +6 은 읽는 사람이 곧바로 알고, 오르내림은 색이 이미 말한다. 반대로 「→」 왼쪽을 떼면
	 * 기준이 사라져 26 이 높은 값인지 알 수 없게 되므로 그쪽은 끝까지 떼지 않는다.
	 */
	public String tightLine() {
		return shortLabel + " " + values();
	}

	/** 값 부분만 — 「20 → 26」. 자리가 아주 좁아 이름과 값을 두 줄로 접을 때 아랫줄이 된다. */
	public String values() {
		if (masked) {
			return MASK;
		}
		return StatSummary.value(base, base, unit) + " → " + StatSummary.value(current, base, unit);
	}

	/** 좋아졌으면 1, 나빠졌으면 −1, 그대로면 0. {@link Sense} 가 부호를 뒤집는다. */
	public int tone() {
		if (masked) {
			// 가려진 값은 좋아졌는지 나빠졌는지도 알려 주면 안 된다. 색이 곧 답이 된다.
			return 0;
		}
		int direction = StatSummary.direction(base, current);
		return sense == Sense.HIGHER_IS_BETTER ? direction : -direction;
	}

	/** 가려진 줄의 글자색. 팀 화면의 흐린 글자색과 같다. */
	public static final int COLOR_MASKED = 0xFF9AA0AA;

	/** {@link #tone()} 에 맞는 글자색. 가려진 줄은 흐리게 적어 「모르는 값」임을 보인다. */
	public int color() {
		return masked ? COLOR_MASKED : colorFor(tone());
	}

	public static int colorFor(int tone) {
		if (tone > 0) {
			return COLOR_GOOD;
		}
		return tone < 0 ? COLOR_BAD : COLOR_NEUTRAL;
	}
}
