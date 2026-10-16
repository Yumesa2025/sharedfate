package com.sharedfate.ui;

/**
 * 「완충」이 곧 깎을 체력을 체력바의 <b>어느 하트, 어느 반쪽</b>에 겹칠지 정한다.
 *
 * <p>월드도 화면도 보지 않는 순수 계산이다. 그리는 쪽은 클라이언트의
 * {@code PendingDamageHearts} 이고, 하트 자리는 바닐라가 그린 자리를 그대로 받아 쓴다.
 *
 * <h2>숫자 대신 하트 위에 겹친다</h2>
 * <p>「남은 피해 3.5 ♥」 같은 글자를 옆에 적는 방식은 이 저장소가 피해 왔다. 하트로 환산한 숫자는
 * 체력바와 따로 읽혀야 해서, 지금 체력에서 얼마가 빠지는지를 한눈에 보여 주지 못한다. 그래서
 * 체력바에서 <b>곧 비게 될 칸</b>을 그 자리에서 다른 색으로 덮는다.
 *
 * <h2>바닐라와 같은 셈으로 칸을 고른다</h2>
 * <ul>
 *   <li>바닐라는 체력을 {@code ceil(체력)} 반 칸으로 그린다. 깎인 뒤의 체력도 같은 반올림으로
 *       세야 「다 깎이고 나면 정확히 이 칸들이 빈다」가 맞는다.</li>
 *   <li>피해는 <b>흡수부터</b> 깎는다({@code Player.actuallyHurt}). 그래서 흡수 하트의 끝쪽이 먼저
 *       덮이고, 흡수보다 큰 몫만 체력 하트로 넘어온다.</li>
 *   <li>하트 {@code i} 의 왼쪽 반이 반 칸 {@code 2i+1}, 오른쪽 반이 {@code 2i+2} 다. 흡수 하트는
 *       체력 칸 수({@code ceil(최대 체력 / 2)}) 뒤에서부터 번호가 이어진다.</li>
 * </ul>
 */
public final class PendingHeartSpan {
	/** 하트 하나에서 덮을 부분. */
	public enum Part {
		NONE, LEFT, RIGHT, FULL
	}

	/** 덮을 것이 없다. */
	public static final PendingHeartSpan EMPTY = new PendingHeartSpan(0, 0, 0, 0, 0);

	private final int containers;
	private final int healthNow;
	private final int healthAfter;
	private final int absorbNow;
	private final int absorbAfter;

	private PendingHeartSpan(int containers, int healthNow, int healthAfter, int absorbNow,
			int absorbAfter) {
		this.containers = containers;
		this.healthNow = healthNow;
		this.healthAfter = healthAfter;
		this.absorbNow = absorbNow;
		this.absorbAfter = absorbAfter;
	}

	/**
	 * @param health     지금 체력
	 * @param absorption 지금 흡수량
	 * @param maxHealth  바닐라가 체력 칸 수를 셀 때 쓴 최대 체력({@code extractHearts} 의 인자)
	 * @param pending    앞으로 깎일 체력. 0 이하이거나 이상한 값이면 덮을 것이 없다
	 */
	public static PendingHeartSpan of(float health, float absorption, float maxHealth, float pending) {
		if (!(pending > 0.0F) || !Float.isFinite(pending) || !Float.isFinite(health)
				|| !Float.isFinite(maxHealth)) {
			return EMPTY;
		}
		float absorb = Float.isFinite(absorption) ? Math.max(0.0F, absorption) : 0.0F;
		float hp = Math.max(0.0F, health);
		float fromAbsorb = Math.min(pending, absorb);
		float fromHealth = pending - fromAbsorb;
		return new PendingHeartSpan(
				ceil(Math.max(0.0F, maxHealth) / 2.0F),
				ceil(hp), ceil(Math.max(0.0F, hp - fromHealth)),
				ceil(absorb), ceil(absorb - fromAbsorb));
	}

	/** 덮을 칸이 하나도 없는가. 깎일 양이 반올림 아래로 숨으면 여기서 참이 된다. */
	public boolean isEmpty() {
		return healthAfter >= healthNow && absorbAfter >= absorbNow;
	}

	/**
	 * 하트 {@code heart} 에서 덮을 부분. 번호는 바닐라 {@code extractHearts} 의 반복 변수와 같다.
	 */
	public Part partOf(int heart) {
		if (heart < 0) {
			return Part.NONE;
		}
		if (heart < containers) {
			return part(2 * heart, healthAfter, healthNow);
		}
		return part(2 * (heart - containers), absorbAfter, absorbNow);
	}

	/** 반 칸 {@code base+1}(왼쪽)과 {@code base+2}(오른쪽)이 {@code (after, now]} 안에 드는가. */
	private static Part part(int base, int after, int now) {
		boolean left = base + 1 > after && base + 1 <= now;
		boolean right = base + 2 > after && base + 2 <= now;
		if (left && right) {
			return Part.FULL;
		}
		if (left) {
			return Part.LEFT;
		}
		return right ? Part.RIGHT : Part.NONE;
	}

	private static int ceil(float value) {
		return (int) Math.ceil(value);
	}
}
