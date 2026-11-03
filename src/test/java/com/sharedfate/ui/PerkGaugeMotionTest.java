package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「다음 증강까지」 막대의 움직임과, 레벨 안의 경험치까지 세는 찬 정도.
 */
class PerkGaugeMotionTest {
	private static final float DELTA = 1.0E-4F;
	private static final long T0 = 50_000;

	/** 60fps 로 {@code millis} 동안 같은 목표를 준다. */
	private static void run(PerkGaugeMotion motion, float target, int nextLevel, long from, long millis) {
		for (long t = from; t <= from + millis; t += 16) {
			motion.update(target, nextLevel, t);
		}
	}

	// ------------------------------------------------------------------ 찬 정도

	/** 구슬 하나에도 막대가 조금씩 찬다. 레벨마다 한 칸씩 뛰지 않는다. */
	@Test
	void 레벨_안의_경험치도_센다() {
		assertEquals(0.1F, PerkGauge.fraction(5, 5, 0.5F), DELTA);
		assertEquals(0.5F, PerkGauge.fraction(3, 5, 0.5F), DELTA);
		assertEquals(PerkGauge.fraction(2, 5), PerkGauge.fraction(2, 5, 0.0F), DELTA);
	}

	@Test
	void 이상한_몫은_0_으로_본다() {
		assertEquals(PerkGauge.fraction(3, 5), PerkGauge.fraction(3, 5, Float.NaN), DELTA);
		assertEquals(PerkGauge.fraction(3, 5), PerkGauge.fraction(3, 5, -1.0F), DELTA);
		assertEquals(PerkGauge.fraction(3, 5, 1.0F), PerkGauge.fraction(3, 5, 7.0F), DELTA);
	}

	/** 이전 구간 아래로 내려갔으면 레벨 안의 몫으로 차오르지 않는다. */
	@Test
	void 한_칸보다_많이_남았으면_0_이다() {
		assertEquals(0.0F, PerkGauge.fraction(7, 5, 0.9F));
	}

	@Test
	void 구간에_닿았으면_가득이다() {
		assertEquals(1.0F, PerkGauge.fraction(0, 5, 0.7F));
	}

	// ------------------------------------------------------------------ 보간

	/** 처음 보일 때는 0 에서 기어오르지 않고 제자리에 선다. */
	@Test
	void 처음에는_제자리에_선다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.6F, 10, T0);

		assertEquals(0.6F, motion.shown(), DELTA);
		assertEquals(0.0F, motion.sparkle(T0));
	}

	@Test
	void 오르면_부드럽게_따라가다_닿는다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.2F, 10, T0);
		motion.update(0.6F, 10, T0 + 16);

		assertTrue(motion.shown() > 0.2F && motion.shown() < 0.6F, "한 화면 만에 닿지 않는다");
		run(motion, 0.6F, 10, T0 + 32, 1500);
		assertEquals(0.6F, motion.shown(), DELTA);
	}

	/** 30fps 든 144fps 든 같은 시간에 같은 만큼 간다. */
	@Test
	void 화면_갱신_빈도와_상관없이_같은_속도다() {
		PerkGaugeMotion slow = new PerkGaugeMotion();
		PerkGaugeMotion fast = new PerkGaugeMotion();
		slow.update(0.0F, 10, T0);
		fast.update(0.0F, 10, T0);
		for (long t = T0; t <= T0 + 198; t += 33) {
			slow.update(1.0F, 10, t);
		}
		for (long t = T0; t <= T0 + 198; t += 7) {
			fast.update(1.0F, 10, t);
		}
		assertEquals(slow.shown(), fast.shown(), 0.02F);
	}

	/** 창을 내렸다 올려 한 화면 사이가 길어도 한 번에 끝까지 튀지 않는다. */
	@Test
	void 긴_공백_뒤에도_한_번에_튀지_않는다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.0F, 10, T0);
		motion.update(1.0F, 10, T0 + 60_000);

		assertTrue(motion.shown() < 1.0F);
	}

	// ------------------------------------------------------------------ 반짝임 · 번쩍임

	@Test
	void 오르면_진행_끝이_잠깐_반짝인다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.2F, 10, T0);
		motion.update(0.3F, 10, T0 + 16);

		assertEquals(1.0F, motion.sparkle(T0 + 16), DELTA);
		assertTrue(motion.sparkle(T0 + 200) > 0.0F);
		assertEquals(0.0F, motion.sparkle(T0 + 16 + PerkGaugeMotion.SPARKLE_MS));
	}

	@Test
	void 부스러기만큼_오르면_반짝이지_않는다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.2F, 10, T0);
		motion.update(0.2F + PerkGaugeMotion.SPARKLE_THRESHOLD / 2, 10, T0 + 16);

		assertEquals(0.0F, motion.sparkle(T0 + 16));
	}

	/** 내려갈 때(인챈트로 레벨을 썼을 때)는 반짝이지 않는다. */
	@Test
	void 내려가면_반짝이지_않는다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.6F, 10, T0);
		motion.update(0.2F, 10, T0 + 16);

		assertEquals(0.0F, motion.sparkle(T0 + 16));
		assertEquals(0.0F, motion.flash(T0 + 16));
	}

	/** 다음 증강 레벨이 올랐다 — 구간에 닿았다. 막대가 번쩍이고 새 구간의 처음으로 곧장 간다. */
	@Test
	void 구간에_닿으면_번쩍이고_새_구간_처음으로_간다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.95F, 10, T0);
		motion.update(0.05F, 15, T0 + 16);

		assertEquals(1.0F, motion.flash(T0 + 16), DELTA);
		assertEquals(0.05F, motion.shown(), DELTA, "0 까지 천천히 줄지 않는다");
		assertTrue(motion.flash(T0 + 300) > 0.0F);
		assertEquals(0.0F, motion.flash(T0 + 16 + PerkGaugeMotion.FLASH_MS));
	}

	/** 증강이 꺼져 있다 켜진 것(다음 레벨 0 → 5)은 구간에 닿은 것이 아니다. */
	@Test
	void 처음_켜지거나_내려간_것은_번쩍이지_않는다() {
		PerkGaugeMotion fromZero = new PerkGaugeMotion();
		fromZero.update(0.0F, 0, T0);
		fromZero.update(0.2F, 5, T0 + 16);
		assertEquals(0.0F, fromZero.flash(T0 + 16));

		PerkGaugeMotion newRun = new PerkGaugeMotion();
		newRun.update(0.4F, 30, T0);
		newRun.update(0.0F, 5, T0 + 16);
		assertEquals(0.0F, newRun.flash(T0 + 16));
		assertEquals(0.0F, newRun.shown(), DELTA);
	}

	@Test
	void 시계가_거꾸로_가면_제자리에_선다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.2F, 10, T0);
		motion.update(0.8F, 10, T0 - 1000);

		assertEquals(0.8F, motion.shown(), DELTA);
	}

	@Test
	void 비우면_다음에는_제자리에_선다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(0.2F, 10, T0);
		motion.update(0.3F, 10, T0 + 16);
		motion.reset();
		motion.update(0.9F, 10, T0 + 32);

		assertEquals(0.9F, motion.shown(), DELTA);
		assertEquals(0.0F, motion.sparkle(T0 + 32));
	}

	@Test
	void 이상한_목표는_0_으로_본다() {
		PerkGaugeMotion motion = new PerkGaugeMotion();
		motion.update(Float.NaN, 10, T0);
		assertEquals(0.0F, motion.shown());
	}
}
