package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.ConditionalEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 증강 발동 알림의 서버 쪽 규칙 — 무엇을 알리고(조건부의 뒤집힘), 얼마나 자주 보내는가.
 *
 * <p>그리는 쪽의 쿨다운(글자 3초 · 소리 5초)은 {@code PerkProcFeedTest} 가 본다.
 */
class PerkProcFeedbackTest {
	private static final UUID A = new UUID(0L, 1L);
	private static final UUID B = new UUID(0L, 2L);

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkProcFeedback.resetForTesting();
	}

	private static ConditionalEffect conditional(String perkId, int index) {
		PerkRegistry.loadBundled();
		Perk perk = PerkRegistry.byId(perkId).orElseThrow();
		return assertInstanceOf(ConditionalEffect.class, perk.effects().get(index));
	}

	// ------------------------------------------------------------------ 보내는 빈도

	/** 「반격」은 난전에서 한 틱에 여러 번 부를 수 있다. 1초 안의 것은 보내 봐야 버려진다. */
	@Test
	void 같은_사람_같은_증강은_1초에_한_번_보낸다() {
		assertTrue(PerkProcFeedback.shouldSend(A, "riposte", 100));
		assertFalse(PerkProcFeedback.shouldSend(A, "riposte", 100));
		assertFalse(PerkProcFeedback.shouldSend(A, "riposte", 100 + PerkProcFeedback.SEND_INTERVAL_TICKS - 1));
		assertTrue(PerkProcFeedback.shouldSend(A, "riposte", 100 + PerkProcFeedback.SEND_INTERVAL_TICKS));
	}

	/** 서버가 거르는 간격은 화면이 다시 띄우는 간격(3초)보다 짧아야 화면 규칙을 가리지 않는다. */
	@Test
	void 서버_간격은_화면_쿨다운보다_짧다() {
		assertTrue(PerkProcFeedback.SEND_INTERVAL_TICKS * 50L
				< com.sharedfate.ui.PerkProcFeed.TEXT_COOLDOWN_MS);
	}

	@Test
	void 사람과_증강은_서로_따로_센다() {
		assertTrue(PerkProcFeedback.shouldSend(A, "riposte", 100));
		assertTrue(PerkProcFeedback.shouldSend(B, "riposte", 100), "팀원은 따로");
		assertTrue(PerkProcFeedback.shouldSend(A, "shared_suffering", 100), "다른 증강은 따로");
	}

	/** 서버를 다시 켜 틱이 0 부터 다시 세면 막지 않는다. 막으면 그 회차 내내 안 뜬다. */
	@Test
	void 틱이_거꾸로_가면_보낸다() {
		assertTrue(PerkProcFeedback.shouldSend(A, "riposte", 10_000));
		assertTrue(PerkProcFeedback.shouldSend(A, "riposte", 5));
	}

	@Test
	void 오래된_기록은_치운다() {
		for (int i = 0; i <= PerkProcFeedback.PRUNE_THRESHOLD; i++) {
			PerkProcFeedback.shouldSend(new UUID(1L, i), "riposte", 0);
		}
		PerkProcFeedback.shouldSend(A, "riposte", 1000);
		PerkProcFeedback.shouldSend(B, "riposte", 1000);
		assertTrue(PerkProcFeedback.trackedForTesting() <= PerkProcFeedback.PRUNE_THRESHOLD,
				"남은 기록 " + PerkProcFeedback.trackedForTesting());
	}

	// ------------------------------------------------------------------ 조건부의 뒤집힘

	/** 「포식자」 — 배가 가득 찬 순간 한 번. */
	@Test
	void 조건이_막_맞은_순간만_알린다() {
		ConditionalEffect predator = conditional("sharedfate:predator", 0);

		assertTrue(PerkProcFeedback.announcesConditional(predator, false, true));
		assertFalse(PerkProcFeedback.announcesConditional(predator, true, false), "풀린 순간");
		assertFalse(PerkProcFeedback.announcesConditional(predator, true, true), "그대로");
		assertFalse(PerkProcFeedback.announcesConditional(predator, false, false), "그대로");
	}

	/** 접속·서버 시작 직후의 첫 판정은 알리지 않는다. 가진 조건부가 한꺼번에 뜬다. */
	@Test
	void 처음_판정은_알리지_않는다() {
		assertFalse(PerkProcFeedback.announcesConditional(
				conditional("sharedfate:flawless", 0), null, true));
	}

	/** 사용자가 든 이름들 — 무결점 · 불굴 · 아가미 · 포식자 — 이 모두 알린다. */
	@Test
	void 사용자가_든_조건부는_모두_알린다() {
		for (String id : List.of("sharedfate:predator", "sharedfate:flawless", "sharedfate:unbroken",
				"sharedfate:gills", "sharedfate:brink")) {
			assertTrue(PerkProcFeedback.announcesConditional(conditional(id, 0), false, true), id);
		}
	}

	/** 「불굴」의 둘째 조건(체력이 가득하면 받는 피해 +10%)은 대가다. 손해가 켜진 것은 알리지 않는다. */
	@Test
	void 대가가_켜진_것은_알리지_않는다() {
		ConditionalEffect drawback = conditional("sharedfate:unbroken", 1);

		assertTrue(PerkDrawbacks.isDrawback(drawback));
		assertFalse(PerkProcFeedback.announcesConditional(drawback, false, true));
	}

	/** 뭉침(「발맞춰 걷기」 · 「운명 공동체」)은 팀원이 움직일 때마다 깜빡인다. */
	@Test
	void 뭉침_조건은_알리지_않는다() {
		ConditionalEffect inStep = conditional("sharedfate:in_step", 0);

		assertEquals(ConditionalEffect.Condition.TEAM_NEARBY, inStep.condition());
		assertFalse(PerkProcFeedback.announcesConditional(inStep, false, true));
	}

	@Test
	void 빈_효과는_알리지_않는다() {
		assertFalse(PerkProcFeedback.announcesConditional(null, false, true));
	}
}
