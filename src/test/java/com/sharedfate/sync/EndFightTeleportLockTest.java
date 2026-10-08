package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EndFightTeleportLock} 의 순수 계산을 본다.
 *
 * <p>실제로 막는 일({@code blocks}·{@code inForce})은 살아 있는 서버와 {@code ServerPlayer}
 * 가 있어야 확인할 수 있어 여기서 다루지 않는다. 같은 까닭으로 {@link RallyPointManagerTest}
 * 도 모으는 일 자체는 시험하지 않는다.
 */
class EndFightTeleportLockTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		EndFightTeleportLock.reset();
	}

	@Test
	void 엔드에_있는_사람이_있으면_그쪽이_기준점이_된다() {
		// 뽑힌 자리(0)가 엔드 밖이어도 엔드에 있는 2번이 기준점이 된다.
		assertEquals(2, EndFightTeleportLock.firstInEnd(
				new boolean[] { false, false, true, false }, 0));
	}

	@Test
	void 아무도_엔드에_없으면_뽑힌_자리를_그대로_쓴다() {
		assertEquals(3, EndFightTeleportLock.firstInEnd(
				new boolean[] { false, false, false, false }, 3));
		assertEquals(1, EndFightTeleportLock.firstInEnd(new boolean[0], 1));
		assertEquals(1, EndFightTeleportLock.firstInEnd(null, 1));
	}

	@Test
	void 이미_엔드에_있는_자리가_뽑히면_그대로_둔다() {
		// 「첫 엔드 사람」을 찾는 것이라 뽑힌 자리와 다른 답이 나올 수 있지만, 둘 다 엔드
		// 안이므로 결과는 같다. 여기서는 답이 흔들리지 않는다는 것만 못 박아 둔다.
		assertEquals(0, EndFightTeleportLock.firstInEnd(new boolean[] { true, true }, 0));
		assertEquals(0, EndFightTeleportLock.firstInEnd(new boolean[] { true, true }, 1));
	}

	@Test
	void 거절_알림은_한_번_알린_뒤_잠시_쉰다() {
		assertTrue(EndFightTeleportLock.cueDue(null, 1000L), "처음이면 알린다");
		assertFalse(EndFightTeleportLock.cueDue(1000L, 1000L), "같은 틱이면 쉰다");
		assertFalse(EndFightTeleportLock.cueDue(1000L,
				1000L + EndFightTeleportLock.CUE_INTERVAL_TICKS - 1), "간격 안이면 쉰다");
		assertTrue(EndFightTeleportLock.cueDue(1000L,
				1000L + EndFightTeleportLock.CUE_INTERVAL_TICKS), "간격이 차면 다시 알린다");
	}

	@Test
	void 게임_시간이_되감기면_다시_알린다() {
		// 월드를 새로 만들면 게임 시간이 0 에서 다시 시작한다. 그때 「아직 간격이 안 찼다」로
		// 읽으면 새 회차 내내 거절이 조용해진다.
		assertTrue(EndFightTeleportLock.cueDue(100000L, 5L));
	}
}
