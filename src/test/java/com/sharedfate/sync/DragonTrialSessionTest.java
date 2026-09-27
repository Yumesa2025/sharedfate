package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 드래곤 전투의 타이머와 시련 누적을 본다.
 *
 * <p>월드도 드래곤도 없이 시간만 흘려 보낸다. 여기서 틀리면 시련이 안 뜨거나, 겹쳐 뜨거나,
 * 끝나지 않는다.
 */
class DragonTrialSessionTest {
	private static final UUID TEAM = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
	private static final int INTERVAL = 150;
	private static final int MAX = 4;

	private static DragonTrialSession session() {
		return new DragonTrialSession(TEAM, 1000L, INTERVAL, MAX);
	}

	@Test
	void 시작하자마자는_시련을_내지_않는다() {
		DragonTrialSession session = session();

		assertFalse(session.shouldOfferTrial(1000L), "전투가 시작되는 순간 선택창이 뜨면 드래곤을 보기도 전이다");
		assertFalse(session.shouldOfferTrial(1000L + INTERVAL - 1));
	}

	@Test
	void 간격이_지나면_시련을_낸다() {
		DragonTrialSession session = session();

		assertTrue(session.shouldOfferTrial(1000L + INTERVAL));
	}

	@Test
	void 기다리는_동안에는_다음_시련이_뜨지_않는다() {
		DragonTrialSession session = session();
		session.beginChoice();

		assertFalse(session.shouldOfferTrial(1000L + INTERVAL * 3),
				"아무도 안 고르는 동안 타이머가 돌면 선택창이 겹쳐 쌓인다");
	}

	@Test
	void 다음_시련은_고른_순간부터_다시_센다() {
		DragonTrialSession session = session();
		session.beginChoice();
		// 전투 중이라 고르는 데 10초가 걸렸다.
		session.choose("a", 1000L + INTERVAL + 200L);

		assertFalse(session.shouldOfferTrial(1000L + INTERVAL + 200L + INTERVAL - 1),
				"시작 시각 기준으로 세면 늦게 고른 만큼 다음 시련이 곧바로 따라붙는다");
		assertTrue(session.shouldOfferTrial(1000L + INTERVAL + 200L + INTERVAL));
	}

	@Test
	void 같은_시련을_두_번_고를_수_없다() {
		DragonTrialSession session = session();
		session.beginChoice();
		assertTrue(session.choose("a", 2000L));
		session.beginChoice();

		assertFalse(session.choose("a", 3000L), "같은 카드가 두 장 쌓이면 효과가 두 번 걸린다");
		assertEquals(1, session.trialCount());
	}

	@Test
	void 상한에_닿으면_더_내지_않는다() {
		DragonTrialSession session = session();
		long now = 1000L;
		for (int index = 0; index < MAX; index++) {
			now += INTERVAL;
			session.beginChoice();
			assertTrue(session.choose("trial" + index, now));
		}

		assertEquals(MAX, session.trialCount());
		assertFalse(session.shouldOfferTrial(now + INTERVAL * 10),
				"못 이기는데 끝나지도 않는 상태를 막는 것이 상한이다");
		assertEquals(-1L, session.ticksUntilNextTrial(now + INTERVAL),
				"상한에 닿았으면 남은 시간이 아니라 「더 없음」이어야 한다");
	}

	@Test
	void 아무도_고르지_않으면_접고_타이머만_다시_돈다() {
		DragonTrialSession session = session();
		session.beginChoice();
		session.cancelChoice(2000L);

		assertFalse(session.awaitingChoice());
		assertEquals(0, session.trialCount(), "접었으면 아무것도 쌓이지 않는다");
		assertTrue(session.shouldOfferTrial(2000L + INTERVAL), "다음 기회는 와야 한다");
	}

	@Test
	void 서버가_재시작해도_타이머와_누적이_이어진다() {
		DragonTrialSession session = session();
		session.restore(List.of("a", "b"), 5000L, false);

		assertEquals(2, session.trialCount());
		assertEquals(List.of("a", "b"), session.chosen());
		assertFalse(session.shouldOfferTrial(4999L));
		assertTrue(session.shouldOfferTrial(5000L),
				"복원하지 않으면 체력만 강화된 채 타이머가 0 인 어긋난 상태가 된다");
	}

	@Test
	void 복원할_때_중복과_빈_값은_걸러진다() {
		DragonTrialSession session = session();
		session.restore(java.util.Arrays.asList("a", "a", "", null, "b"), 5000L, false);

		assertEquals(List.of("a", "b"), session.chosen(), "저장 파일이 손상돼도 같은 효과가 두 번 걸리면 안 된다");
	}

	@Test
	void 전투_길이를_잴_수_있다() {
		DragonTrialSession session = session();

		assertEquals(0L, session.elapsedTicks(1000L));
		assertEquals(600L, session.elapsedTicks(1600L));
		assertEquals(0L, session.elapsedTicks(500L), "시각이 거꾸로 와도 음수가 나오면 안 된다");
	}

	@Test
	void 고른_목록은_밖에서_고칠_수_없다() {
		DragonTrialSession session = session();
		session.beginChoice();
		session.choose("a", 2000L);

		org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
				() -> session.chosen().add("b"),
				"누적 목록을 밖에서 건드리면 타이머와 어긋난다");
	}
}
