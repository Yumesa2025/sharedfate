package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 경고가 세 층으로 제때 올라오는지 본다.
 *
 * <p>층을 고르는 것이 이 클래스의 유일한 판단이다. 파티클과 소리는 월드가 있어야 해서 여기서
 * 볼 수 없지만, <b>언제 어느 층인가</b>가 틀리면 나머지가 다 맞아도 경고가 늦거나 겹친다.
 */
class TrialWarningTest {

	@Test
	void 먼_미래에는_아직_아무_층도_아니다() {
		assertNull(TrialWarning.stageFor(200), "2초 넘게 남았는데 벌써 경고하면 신호가 흔해진다");
		assertNull(TrialWarning.stageFor(101));
	}

	@Test
	void 세_층이_차례로_올라온다() {
		assertEquals(TrialWarning.Stage.APPROACH, TrialWarning.stageFor(100), "첫 층 — 뭔가 온다");
		assertEquals(TrialWarning.Stage.MARK, TrialWarning.stageFor(50), "둘째 층 — 여기로 온다");
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(14), "셋째 층 — 지금 나가라");
	}

	@Test
	void 층은_뒤로_돌아가지_않는다() {
		TrialWarning.Stage previous = null;
		for (int remaining = 120; remaining >= 0; remaining--) {
			TrialWarning.Stage now = TrialWarning.stageFor(remaining);
			if (previous != null && now != null) {
				assertTrue(now.ordinal() >= previous.ordinal(),
						"시간이 줄어드는데 경고가 약해지면 플레이어가 안심한다: " + remaining);
			}
			previous = now;
		}
	}

	@Test
	void 발동_순간에도_마지막_층이다() {
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(0),
				"0 틱에서 층이 사라지면 마지막 경고 없이 터진다");
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(-5),
				"이미 지난 값이 들어와도 약해지면 안 된다");
	}

	@Test
	void 경계값에서_층이_갈린다() {
		assertNotEquals(TrialWarning.stageFor(101), TrialWarning.stageFor(100), "첫 층의 문턱");
		assertNotEquals(TrialWarning.stageFor(51), TrialWarning.stageFor(50), "둘째 층의 문턱");
		assertNotEquals(TrialWarning.stageFor(15), TrialWarning.stageFor(14), "셋째 층의 문턱");
	}

	@Test
	void 예고_시간은_요구하는_행동이_클수록_길다() {
		assertTrue(TrialWarning.TICKS_SIDESTEP < TrialWarning.TICKS_REPOSITION,
				"옆으로 한 걸음보다 지정 위치로 가는 것이 오래 걸린다");
		assertTrue(TrialWarning.TICKS_REPOSITION < TrialWarning.TICKS_SCATTER,
				"혼자 움직이는 것보다 넷이 흩어지는 것이 오래 걸린다");
		assertTrue(TrialWarning.TICKS_SIDESTEP >= 25,
				"사람의 지각·판단·입력에만 0.25초가 든다. 그보다 짧으면 반응할 수 없다");
	}

	@Test
	void 가장_긴_예고도_세_층_안에_들어온다() {
		assertEquals(TrialWarning.Stage.APPROACH, TrialWarning.stageFor(TrialWarning.TICKS_SCATTER),
				"넷이 흩어져야 하는 패턴인데 첫 층이 안 뜨면 예고가 통째로 없는 것과 같다");
	}
}
