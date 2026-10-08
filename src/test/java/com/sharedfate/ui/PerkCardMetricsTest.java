package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 증강 선택 카드의 세로 길이.
 *
 * <p>카드 안쪽은 {@code enableScissor} 로 잘리므로, 그리는 쪽에 세트 유형 줄을 더하면서 이
 * 계산을 함께 고치지 않으면 <b>아무 오류 없이 설명 마지막 줄이 사라진다.</b> 카드가 짧아졌다는
 * 표시도, 무언가 잘렸다는 표시도 화면에 나오지 않는다.
 *
 * <p>값은 {@code PerkOfferScreen} 의 상수를 그대로 옮겨 놓았다. 그쪽 숫자가 바뀌어도 이 시험이
 * 지키려는 것(<b>줄이 늘면 높이도 는다</b>)은 그대로다.
 */
class PerkCardMetricsTest {
	/** 등급 띠 11 · 띠~아이콘 4 · 아이콘~이름 3 · 이름~유형 2 · 구분선 8 · 아래 여백 6 · 글줄 9. */
	private static final PerkCardMetrics METRICS = new PerkCardMetrics(11, 4, 3, 2, 8, 6, 9);

	@Test
	void 세트_유형_줄이_높이에_들어간다() {
		int without = METRICS.height(16, 1, 0, 3);
		int with = METRICS.height(16, 1, 1, 3);

		// 줄 하나(9)와 그 위의 틈(2)만큼 늘어야 한다.
		assertEquals(without + 9 + 2, with);
	}

	@Test
	void 유형_줄이_늘면_그만큼_더_는다() {
		assertEquals(METRICS.height(16, 1, 1, 3) + 9, METRICS.height(16, 1, 2, 3));
	}

	/**
	 * 유형이 없는 증강에는 틈도 없다.
	 *
	 * <p>무유형 증강이 열한 개다. 그것들의 카드가 이유 없이 2픽셀 길어지면 세 장의 높이가
	 * 서로 어긋나 보인다.
	 */
	@Test
	void 유형이_없으면_틈도_없다() {
		PerkCardMetrics wideGap = new PerkCardMetrics(11, 4, 3, 40, 8, 6, 9);

		assertEquals(METRICS.height(16, 1, 0, 3), wideGap.height(16, 1, 0, 3));
	}

	@Test
	void 아이콘이_없으면_아이콘_자리도_없다() {
		// 11 + 4 + (이름1 + 유형1 + 설명3) × 9 + 2 + 8 + 6
		assertEquals(11 + 4 + 45 + 2 + 8 + 6, METRICS.height(0, 1, 1, 3));
	}

	@Test
	void 아이콘이_있으면_아이콘과_아래_틈을_더한다() {
		assertEquals(METRICS.height(0, 1, 1, 3) + 16 + 3, METRICS.height(16, 1, 1, 3));
	}

	@Test
	void 줄이_길어질수록_카드도_길어진다() {
		assertTrue(METRICS.height(16, 2, 1, 6) > METRICS.height(16, 1, 1, 3));
	}

	@Test
	void 음수_줄_수가_와도_높이를_깎지_않는다() {
		// 여기서 음수가 곱해지면 카드가 도리어 짧아져 내용이 통째로 잘린다.
		assertEquals(METRICS.height(16, 0, 0, 0), METRICS.height(16, -3, -1, -2));
	}
}
