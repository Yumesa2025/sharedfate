package com.sharedfate.ui;

/**
 * 보정치 한 줄 — 「추가 공격력  +35%」.
 *
 * <p>{@link StatRow} 가 「20 → 26」처럼 <b>기준과 지금</b>을 나란히 적는다면, 이 줄은 증강·세트가
 * <b>얼마나 얹었는가</b>만 적는다. 인벤토리 옆 패널이 이것을 쓴다 — 무기를 바꿀 때마다 출렁이는
 * 최종값보다, 팀이 지금까지 모은 몫이 한눈에 들어와야 한다는 것이 사람이 정한 것이다.
 *
 * <p>색 규칙은 {@link StatRow} 와 같다. {@link StatRow.Sense} 가 부호를 뒤집으므로 받는 피해
 * −10% 는 초록, 몹 체력 +15% 는 빨강이 된다.
 *
 * @param label      넉넉한 자리에서 적는 이름
 * @param shortLabel 좁은 자리에서 적는 이름
 * @param amount     비율이면 0.35 가 +35%, 고정값이면 그대로
 * @param kind       어떻게 적는가
 * @param sense      오르는 것이 좋은 값인가
 * @param suffix     {@link #fullLine()} 에만 붙는 덧붙임말. 「(하트 3개)」 같은 것
 */
public record BonusRow(String label, String shortLabel, double amount, Kind kind,
		StatRow.Sense sense, String suffix) implements PanelRow {

	/** 값을 어떻게 적는가. */
	public enum Kind {
		/** 비율 — 「+35%」. 정수 % 로 반올림한다. */
		PERCENT,
		/** 고정값 — 「+2」. 소수 한 자리까지. */
		FLAT,
		/** 증강이 가린 값 — {@value StatRow#MASK}. */
		MASKED,
		/** 값 없는 안내 한 줄 — 「증강 보정 없음」. 흐리게 적는다. */
		NOTE
	}

	public static BonusRow percent(String label, String shortLabel, double amount,
			StatRow.Sense sense) {
		return new BonusRow(label, shortLabel, amount, Kind.PERCENT, sense, "");
	}

	public static BonusRow flat(String label, String shortLabel, double amount,
			StatRow.Sense sense) {
		return new BonusRow(label, shortLabel, amount, Kind.FLAT, sense, "");
	}

	public static BonusRow masked(String label, String shortLabel) {
		return new BonusRow(label, shortLabel, 0.0, Kind.MASKED, StatRow.Sense.HIGHER_IS_BETTER, "");
	}

	public static BonusRow note(String text) {
		return new BonusRow(text, text, 0.0, Kind.NOTE, StatRow.Sense.HIGHER_IS_BETTER, "");
	}

	/** 덧붙임말을 단 사본. */
	public BonusRow withSuffix(String value) {
		return new BonusRow(label, shortLabel, amount, kind, sense, value == null ? "" : value);
	}

	/**
	 * 비율을 정수 % 로 — 0.35 → 「+35%」, −0.1 → 「-10%」, 0 → 「0%」.
	 *
	 * <p>부호를 반드시 붙인다. 이 패널의 숫자는 모두 「얼마나 달라졌는가」라 「35%」만 적으면
	 * 35% 가 된 것인지 35% 오른 것인지 갈리지 않는다.
	 */
	public static String percentText(double fraction) {
		long percent = Double.isFinite(fraction) ? Math.round(fraction * 100.0) : 0L;
		return (percent > 0 ? "+" : "") + percent + "%";
	}

	/** 고정값을 소수 한 자리까지 — 2 → 「+2」, −1.5 → 「-1.5」, 0 → 「0」. */
	public static String flatText(double value) {
		String text = StatSummary.number(Double.isFinite(value) ? value : 0.0);
		if ("-0".equals(text)) {
			text = "0";
		}
		return text.startsWith("-") || "0".equals(text) ? text : "+" + text;
	}

	/**
	 * 적으면 「0%」·「0」이 되는가. 패널은 이런 줄을 숨긴다 — 아무것도 안 걸린 항목이 줄줄이
	 * 서 있으면 정작 걸린 것이 묻힌다. 가린 줄과 안내 줄은 0 이 아니다.
	 */
	public boolean isZero() {
		return switch (kind) {
			case PERCENT -> Math.round(amount * 100.0) == 0L || !Double.isFinite(amount);
			case FLAT -> "0".equals(flatText(amount));
			default -> false;
		};
	}

	@Override
	public String values() {
		return switch (kind) {
			case PERCENT -> percentText(amount);
			case FLAT -> flatText(amount);
			case MASKED -> StatRow.MASK;
			case NOTE -> "";
		};
	}

	@Override
	public String fullLine() {
		return kind == Kind.NOTE ? label : label + "  " + values() + suffix;
	}

	@Override
	public String shortLine() {
		return kind == Kind.NOTE ? shortLabel : shortLabel + "  " + values();
	}

	@Override
	public String tightLine() {
		return kind == Kind.NOTE ? shortLabel : shortLabel + " " + values();
	}

	@Override
	public boolean wraps() {
		return kind != Kind.NOTE;
	}

	/** 좋아졌으면 1, 나빠졌으면 −1, 그대로면 0. 적힌 글자 기준이다 — 「0%」 인데 색이 붙지 않게. */
	public int tone() {
		if (kind == Kind.MASKED || kind == Kind.NOTE || isZero()) {
			return 0;
		}
		int direction = amount > 0 ? 1 : -1;
		return sense == StatRow.Sense.HIGHER_IS_BETTER ? direction : -direction;
	}

	@Override
	public int color() {
		if (kind == Kind.MASKED || kind == Kind.NOTE) {
			return StatRow.COLOR_MASKED;
		}
		return StatRow.colorFor(tone());
	}
}
