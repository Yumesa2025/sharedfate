package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 체력바가 「곧 깎일 만큼」으로 그릴 양({@code SpreadDamageManager.expectedLoss}).
 *
 * <p>살아 있는 플레이어의 방어구를 읽는 쪽은 여기서 부를 수 없다. 남은 몫을 어떤 순서로 떼어 내
 * 깎는지만 순수 계산으로 붙든다 — 그 순서가 실제로 넣는 쪽({@code Spread.takeSlice})과 어긋나면
 * 체력바가 실제와 다른 칸을 덮는다.
 */
class SpreadDamageExpectedLossTest {
	private static final float EPSILON = 0.0001F;

	/** 깎는 것이 없으면 남은 몫 그대로다. */
	@Test
	void 감쇄가_없으면_남은_몫_그대로다() {
		assertEquals(9.0F, SpreadDamageManager.expectedLoss(9.0F, 3, slice -> slice), EPSILON);
	}

	/** 몫마다 따로 깎는다. 12 를 넷으로 나눈 3 이 저마다 반이 되면 6 이다. */
	@Test
	void 몫마다_따로_깎아_더한다() {
		assertEquals(6.0F, SpreadDamageManager.expectedLoss(12.0F, 4, slice -> slice / 2), EPSILON);
	}

	/**
	 * 방어구처럼 작은 몫에 더 후한 감쇄면 몫 크기가 결과를 바꾼다. 그래서 합계를 한 번에 깎지 않고
	 * 실제로 넣을 크기 그대로 하나씩 깎는다.
	 */
	@Test
	void 한_번에_깎지_않고_넣을_크기대로_깎는다() {
		// 몫이 2 이하면 통째로 막고, 넘치면 넘친 만큼만 들어가는 감쇄.
		float loss = SpreadDamageManager.expectedLoss(9.0F, 3, slice -> Math.max(0.0, slice - 2.0));
		assertEquals(3.0F, loss, EPSILON, "몫 3 이 셋 — 저마다 1 씩");
	}

	@Test
	void 남은_것이_없으면_0이다() {
		assertEquals(0.0F, SpreadDamageManager.expectedLoss(0.0F, 4, slice -> slice), EPSILON);
		assertEquals(0.0F, SpreadDamageManager.expectedLoss(5.0F, 0, slice -> slice), EPSILON);
		assertEquals(0.0F, SpreadDamageManager.expectedLoss(5.0F, 2, slice -> Double.NaN), EPSILON,
				"이상한 감쇄값은 더하지 않는다");
	}
}
