package com.sharedfate.ui;

import com.sharedfate.ui.PendingHeartSpan.Part;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「완충」의 남은 피해를 체력바의 어느 반 칸에 겹칠지.
 *
 * <p>하트 {@code i} 의 왼쪽 반이 체력 {@code 2i+1}, 오른쪽 반이 {@code 2i+2} 다. 최대 체력 20 이면
 * 체력 하트가 0~9 번이고 흡수 하트가 10 번부터다.
 */
class PendingHeartSpanTest {
	private static final float MAX = 20.0F;

	@Test
	void 남은_피해가_없으면_아무것도_덮지_않는다() {
		assertSame(PendingHeartSpan.EMPTY, PendingHeartSpan.of(20.0F, 0.0F, MAX, 0.0F));
		assertSame(PendingHeartSpan.EMPTY, PendingHeartSpan.of(20.0F, 0.0F, MAX, Float.NaN));
		assertSame(PendingHeartSpan.EMPTY, PendingHeartSpan.of(20.0F, 0.0F, MAX, -1.0F));
		assertTrue(PendingHeartSpan.EMPTY.isEmpty());
		assertEquals(Part.NONE, PendingHeartSpan.EMPTY.partOf(0));
	}

	/** 체력 15(일곱 칸 반)에서 3 이 깎이면 13·14·15 — 6번 하트 전체와 7번 하트 왼쪽 반이다. */
	@Test
	void 지금_체력의_끝에서부터_덮는다() {
		PendingHeartSpan span = PendingHeartSpan.of(15.0F, 0.0F, MAX, 3.0F);

		assertFalse(span.isEmpty());
		assertEquals(Part.NONE, span.partOf(5), "12 까지는 남는다");
		assertEquals(Part.FULL, span.partOf(6));
		assertEquals(Part.LEFT, span.partOf(7));
		assertEquals(Part.NONE, span.partOf(8), "빈 틀은 덮지 않는다");
	}

	/** 체력 16 에서 3 이 깎이면 14·15·16 — 6번 하트는 오른쪽 반만 덮인다. */
	@Test
	void 칸_중간에서_시작하면_오른쪽_반만_덮는다() {
		PendingHeartSpan span = PendingHeartSpan.of(16.0F, 0.0F, MAX, 3.0F);

		assertEquals(Part.RIGHT, span.partOf(6));
		assertEquals(Part.FULL, span.partOf(7));
		assertEquals(Part.NONE, span.partOf(5));
	}

	/** 바닐라처럼 반올림해 센다. 체력 15.3 은 16 칸으로 그려지고, 2.0 이 깎이면 13.3 → 14 칸이 남는다. */
	@Test
	void 바닐라와_같이_올림으로_센다() {
		PendingHeartSpan span = PendingHeartSpan.of(15.3F, 0.0F, MAX, 2.0F);

		assertEquals(Part.FULL, span.partOf(7), "15·16 이 빈다");
		assertEquals(Part.NONE, span.partOf(6), "14 까지는 남는다");

		// 반 칸에 못 미치는 부스러기는 칸이 바뀌지 않으므로 덮을 것도 없다.
		assertTrue(PendingHeartSpan.of(15.3F, 0.0F, MAX, 0.2F).isEmpty());
	}

	/** 피해는 흡수부터 깎는다. 흡수 4(10·11번 하트)에 3 이 깎이면 흡수 끝쪽만 덮이고 체력은 그대로다. */
	@Test
	void 흡수부터_덮는다() {
		PendingHeartSpan span = PendingHeartSpan.of(20.0F, 4.0F, MAX, 3.0F);

		assertEquals(Part.NONE, span.partOf(9), "체력 하트는 그대로다");
		assertEquals(Part.RIGHT, span.partOf(10), "흡수 2 만 덮인다");
		assertEquals(Part.FULL, span.partOf(11));
	}

	/** 흡수보다 크게 깎이면 넘친 만큼 체력 하트로 넘어간다. */
	@Test
	void 흡수를_넘치면_체력_하트로_넘어간다() {
		PendingHeartSpan span = PendingHeartSpan.of(20.0F, 2.0F, MAX, 5.0F);

		assertEquals(Part.FULL, span.partOf(10), "흡수 2 가 전부");
		assertEquals(Part.FULL, span.partOf(9), "19·20");
		assertEquals(Part.RIGHT, span.partOf(8), "18");
		assertEquals(Part.NONE, span.partOf(7));
	}

	/** 남은 체력보다 크게 깎여도 0 아래로 칸을 만들지 않는다. */
	@Test
	void 체력보다_크면_전부_덮고_멈춘다() {
		PendingHeartSpan span = PendingHeartSpan.of(3.0F, 0.0F, MAX, 50.0F);

		assertEquals(Part.FULL, span.partOf(0));
		assertEquals(Part.LEFT, span.partOf(1));
		assertEquals(Part.NONE, span.partOf(2));
		assertEquals(Part.NONE, span.partOf(-1));
	}
}
