package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 증강 발동 알림 줄 — 쿨다운 · 쌓기 · 흐려지기 · 소리.
 *
 * <p>하루 종일 보는 화면이라 「덜 뜨는 쪽」을 붙들어 두는 시험이 대부분이다.
 */
class PerkProcFeedTest {
	private static final long T0 = 10_000;

	private static List<String> names(PerkProcFeed feed, long now) {
		return feed.frames(now).stream().map(frame -> frame.line().name()).toList();
	}

	// ------------------------------------------------------------------ 쿨다운

	@Test
	void 처음_온_알림은_띄우고_소리도_낸다() {
		PerkProcFeed feed = new PerkProcFeed();

		PerkProcFeed.Offer offer = feed.offer("sharedfate:vital_strike", "급소만 노려", "silver", T0);

		assertTrue(offer.shown());
		assertTrue(offer.playSound());
		assertEquals(List.of("급소만 노려"), names(feed, T0 + 10));
	}

	/** 「반격」은 팀원이 맞을 때마다 온다. 3초 안의 것은 줄을 늘리지도 다시 시작하지도 않는다. */
	@Test
	void 같은_증강은_3초에_한_번까지만_뜬다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("riposte", "반격", "gold", T0);

		assertFalse(feed.offer("riposte", "반격", "gold", T0 + 500).shown());
		assertFalse(feed.offer("riposte", "반격", "gold", T0 + PerkProcFeed.TEXT_COOLDOWN_MS - 1).shown());
		assertEquals(List.of(), names(feed, T0 + PerkProcFeed.SHOW_MS), "다시 시작하지 않아 제때 사라진다");
		assertTrue(feed.offer("riposte", "반격", "gold", T0 + PerkProcFeed.TEXT_COOLDOWN_MS).shown());
	}

	/** 소리는 글자보다 길게 묶는다 — 3초 뒤 다시 뜬 글자에는 소리가 없고, 5초 뒤에야 난다. */
	@Test
	void 같은_증강의_소리는_5초에_한_번이다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("riposte", "반격", "gold", T0);

		PerkProcFeed.Offer again = feed.offer("riposte", "반격", "gold", T0 + PerkProcFeed.TEXT_COOLDOWN_MS);
		assertTrue(again.shown());
		assertFalse(again.playSound());

		PerkProcFeed.Offer later = feed.offer("riposte", "반격", "gold", T0 + PerkProcFeed.SOUND_COOLDOWN_MS + 1000);
		assertTrue(later.playSound());
	}

	/** 처치 한 번에 증강 셋이 함께 터져도 소리는 하나다. */
	@Test
	void 한꺼번에_뜬_알림은_소리를_한_번만_낸다() {
		PerkProcFeed feed = new PerkProcFeed();

		assertTrue(feed.offer("a", "전리품 사냥꾼", "silver", T0).playSound());
		assertFalse(feed.offer("b", "전리품 방패", "gold", T0).playSound());
		assertFalse(feed.offer("c", "포식", "prism", T0 + PerkProcFeed.SOUND_GAP_MS - 1).playSound());
		assertTrue(feed.offer("d", "급소만 노려", "silver", T0 + PerkProcFeed.SOUND_GAP_MS).playSound());
	}

	@Test
	void 다른_증강은_서로의_쿨다운에_걸리지_않는다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "반격", "gold", T0);

		assertTrue(feed.offer("b", "동병상련", "silver", T0 + 100).shown());
	}

	@Test
	void 이름이_비었으면_띄우지_않는다() {
		PerkProcFeed feed = new PerkProcFeed();

		assertFalse(feed.offer("a", " ", "gold", T0).shown());
		assertFalse(feed.offer(null, "반격", "gold", T0).shown());
		assertTrue(feed.isEmpty());
	}

	// ------------------------------------------------------------------ 이 컴퓨터의 설정

	/** 글자를 끄면 줄을 쌓지 않는다. 소리는 그대로 난다. */
	@Test
	void 글자를_끄면_줄이_없고_소리만_난다() {
		PerkProcFeed feed = new PerkProcFeed();

		PerkProcFeed.Offer offer = feed.offer("a", "반격", "gold", T0, false, true);

		assertFalse(offer.shown());
		assertTrue(offer.playSound());
		assertTrue(feed.isEmpty());
	}

	@Test
	void 소리를_끄면_글자만_뜬다() {
		PerkProcFeed feed = new PerkProcFeed();

		PerkProcFeed.Offer offer = feed.offer("a", "반격", "gold", T0, true, false);

		assertTrue(offer.shown());
		assertFalse(offer.playSound());
		assertEquals(List.of("반격"), names(feed, T0 + 10));
	}

	/** 소리만 켠 사람에게도 3초 쿨다운이 걸린다. 「반격」이 난전 내내 울리면 안 된다. */
	@Test
	void 소리만_켜도_쿨다운은_그대로다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "반격", "gold", T0, false, true);

		assertFalse(feed.offer("a", "반격", "gold", T0 + 1000, false, true).playSound());
	}

	/** 둘 다 끄면 아무것도 기억하지 않는다. 다시 켠 순간의 첫 알림이 묵은 쿨다운에 걸리지 않는다. */
	@Test
	void 둘_다_끄면_아무것도_하지_않고_기억하지도_않는다() {
		PerkProcFeed feed = new PerkProcFeed();

		PerkProcFeed.Offer off = feed.offer("a", "반격", "gold", T0, false, false);
		assertFalse(off.shown());
		assertFalse(off.playSound());
		assertTrue(feed.isEmpty());

		PerkProcFeed.Offer on = feed.offer("a", "반격", "gold", T0 + 100, true, true);
		assertTrue(on.shown());
		assertTrue(on.playSound());
	}

	// ------------------------------------------------------------------ 쌓기

	/** 새것이 바닥(0 번), 오래된 것이 위로 밀린다. */
	@Test
	void 새_줄이_바닥에_서고_오래된_줄이_위로_밀린다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "하나", "silver", T0);
		feed.offer("b", "둘", "silver", T0 + 100);

		assertEquals(List.of("둘", "하나"), names(feed, T0 + 200));
	}

	@Test
	void 넷째_줄이_오면_가장_오래된_줄이_빠진다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "하나", "silver", T0);
		feed.offer("b", "둘", "silver", T0 + 100);
		feed.offer("c", "셋", "silver", T0 + 200);
		feed.offer("d", "넷", "silver", T0 + 300);

		assertEquals(PerkProcFeed.MAX_LINES, feed.frames(T0 + 300).size());
		assertEquals(List.of("넷", "셋", "둘"), names(feed, T0 + 300));
	}

	@Test
	void 한_줄은_1_5초_뒤에_사라진다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "하나", "silver", T0);

		assertEquals(1, feed.frames(T0 + PerkProcFeed.SHOW_MS - 1).size());
		assertTrue(feed.frames(T0 + PerkProcFeed.SHOW_MS).isEmpty());
		assertTrue(feed.isEmpty());
	}

	/** 월드를 옮겨 시계가 거꾸로 가면 남은 줄을 버린다. 영영 안 사라지는 줄이 생기면 안 된다. */
	@Test
	void 시계가_거꾸로_가면_줄을_버린다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "하나", "silver", T0);

		assertTrue(feed.frames(T0 - 1).isEmpty());
	}

	@Test
	void 비우면_쿨다운도_함께_잊는다() {
		PerkProcFeed feed = new PerkProcFeed();
		feed.offer("a", "하나", "silver", T0);
		feed.clear();

		PerkProcFeed.Offer offer = feed.offer("a", "하나", "silver", T0 + 10);
		assertTrue(offer.shown());
		assertTrue(offer.playSound());
	}

	// ------------------------------------------------------------------ 흐려지기

	@Test
	void 나타날_때_오르고_끝에서_흐려진다() {
		assertEquals(0.0F, PerkProcFeed.alphaAt(0));
		assertEquals(PerkProcFeed.MAX_ALPHA, PerkProcFeed.alphaAt(PerkProcFeed.FADE_IN_MS), 1.0E-6F);
		assertEquals(PerkProcFeed.MAX_ALPHA, PerkProcFeed.alphaAt(700), 1.0E-6F);
		float fading = PerkProcFeed.alphaAt(PerkProcFeed.SHOW_MS - PerkProcFeed.FADE_OUT_MS / 2);
		assertTrue(fading > 0.0F && fading < PerkProcFeed.MAX_ALPHA);
		assertEquals(0.0F, PerkProcFeed.alphaAt(PerkProcFeed.SHOW_MS));
	}

	/** 반투명이다. 가장 진할 때도 불투명이 아니다. */
	@Test
	void 가장_진할_때도_반투명이다() {
		for (long age = 0; age < PerkProcFeed.SHOW_MS; age += 25) {
			assertTrue(PerkProcFeed.alphaAt(age) <= PerkProcFeed.MAX_ALPHA);
		}
		assertTrue(PerkProcFeed.MAX_ALPHA < 1.0F);
	}

	@Test
	void 떠오르기는_0_에서_1_로_간다() {
		assertEquals(0.0F, PerkProcFeed.riseAt(0));
		assertEquals(1.0F, PerkProcFeed.riseAt(PerkProcFeed.FADE_IN_MS));
		assertEquals(1.0F, PerkProcFeed.riseAt(1000));
	}

	// ------------------------------------------------------------------ 색과 글

	@Test
	void 등급마다_테두리_색이_다르다() {
		assertEquals(PerkProcFeed.SILVER, PerkProcFeed.accentFor("silver"));
		assertEquals(PerkProcFeed.GOLD, PerkProcFeed.accentFor("gold"));
		assertEquals(PerkProcFeed.PRISM, PerkProcFeed.accentFor("prism"));
		assertEquals(DragonPrepStyle.COLOR, PerkProcFeed.accentFor("trial"));
		assertEquals(PerkProcFeed.SILVER, PerkProcFeed.accentFor("???"));
		assertEquals(PerkProcFeed.SILVER, PerkProcFeed.accentFor(null));
	}

	@Test
	void 줄_글은_표식과_이름이다() {
		assertEquals("⟡ 급소만 노려", new PerkProcFeed.Line("k", "급소만 노려", 0, 0).text());
	}
}
