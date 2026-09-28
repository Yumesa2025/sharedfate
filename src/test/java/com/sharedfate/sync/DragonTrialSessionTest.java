package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Trigger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시련이 터지는 자리와 줄 세우기를 본다.
 *
 * <p>월드도 드래곤도 없이 자리만 터뜨린다. 여기서 틀리면 시련이 겹쳐 뜨거나, 같은 자리가 계속
 * 터지거나, 관통한 팀이 시련을 놓친다.
 */
class DragonTrialSessionTest {
	private static final UUID TEAM = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

	private static DragonTrialSession session() {
		return new DragonTrialSession(TEAM, 1000L);
	}

	@Test
	void 자리가_터지기_전에는_아무것도_뜨지_않는다() {
		assertFalse(session().shouldOfferTrial());
	}

	@Test
	void 자리가_터지면_선택창이_뜬다() {
		DragonTrialSession session = session();

		assertTrue(session.fire(Trigger.ENTRY), "처음 터진 자리다");
		assertTrue(session.shouldOfferTrial());
		assertEquals(Trigger.ENTRY, session.peekTrigger());
	}

	@Test
	void 같은_자리는_한_번만_센다() {
		DragonTrialSession session = session();
		session.fire(Trigger.HEALTH_50);

		assertFalse(session.fire(Trigger.HEALTH_50),
				"드래곤은 크리스탈로 회복해 50%를 오르내린다. 매번 세면 후반에 같은 자리가 계속 터진다");
		assertEquals(1, session.queuedCount());
	}

	@Test
	void 관통하면_줄을_선다() {
		DragonTrialSession session = session();
		// 화력이 센 팀이 80%에서 30%까지 한 번에 깎았다.
		session.fire(Trigger.HEALTH_80);
		session.fire(Trigger.HEALTH_50);
		session.fire(Trigger.HEALTH_30);

		assertEquals(3, session.queuedCount(), "빨리 깎은 대가는 치르게 한다 — 셋 다 받는다");
		assertEquals(Trigger.HEALTH_80, session.peekTrigger(), "터진 순서대로 나온다");
	}

	@Test
	void 앞_선택을_끝내야_다음이_뜬다() {
		DragonTrialSession session = session();
		session.fire(Trigger.HEALTH_80);
		session.fire(Trigger.HEALTH_50);

		assertEquals(Trigger.HEALTH_80, session.beginChoice());
		assertFalse(session.shouldOfferTrial(), "고르는 중에 다음 창이 겹쳐 뜨면 전투 중에 감당이 안 된다");

		session.choose("a");

		assertTrue(session.shouldOfferTrial(), "앞을 끝냈으면 다음이 와야 한다");
		assertEquals(Trigger.HEALTH_50, session.peekTrigger());
	}

	@Test
	void 줄이_비면_더_뜨지_않는다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a");

		assertFalse(session.shouldOfferTrial());
		assertNull(session.peekTrigger());
	}

	@Test
	void 같은_시련을_두_번_고를_수_없다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		assertTrue(session.choose("a"));

		session.fire(Trigger.HEALTH_80);
		session.beginChoice();

		assertFalse(session.choose("a"), "같은 카드가 두 장 쌓이면 효과가 두 번 걸린다");
		assertEquals(1, session.trialCount());
	}

	@Test
	void 줄_카드가_없으면_그냥_지나간다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.skipChoice();

		assertEquals(0, session.trialCount());
		assertFalse(session.awaitingChoice(), "지나간 자리에서 멈춰 있으면 다음 시련이 영영 안 뜬다");
		assertTrue(session.fired().contains(Trigger.ENTRY), "지나갔어도 그 자리는 센 것이다");
	}

	@Test
	void 여섯_자리가_모두_있다() {
		DragonTrialSession session = session();
		for (Trigger trigger : Trigger.values()) {
			assertTrue(session.fire(trigger), trigger + " 가 터지지 않는다");
		}

		assertEquals(6, session.queuedCount(), "입장·첫 크리스탈·크리스탈 전멸·체력 80·50·30");
	}

	@Test
	void 서버가_재시작해도_터진_자리와_줄이_이어진다() {
		DragonTrialSession session = session();
		session.restore(List.of("a"), List.of("ENTRY", "HEALTH_80"), List.of("HEALTH_80"), false);

		assertEquals(1, session.trialCount());
		assertEquals(2, session.fired().size());
		assertTrue(session.shouldOfferTrial(), "줄에 남아 있던 자리는 다시 떠야 한다");
		assertEquals(Trigger.HEALTH_80, session.peekTrigger());
		assertFalse(session.fire(Trigger.ENTRY), "복원한 뒤에도 이미 센 자리는 다시 세지 않는다");
	}

	@Test
	void 복원할_때_모르는_이름은_버린다() {
		DragonTrialSession session = session();
		session.restore(List.of(), List.of("ENTRY", "없어진_트리거"), List.of("없어진_트리거"), false);

		assertEquals(1, session.fired().size(), "옛 저장 파일에 없어진 자리가 있어도 서버를 막지 않는다");
		assertFalse(session.shouldOfferTrial());
	}

	@Test
	void 터진_적_없는_자리는_줄에_서지_못한다() {
		DragonTrialSession session = session();
		session.restore(List.of(), List.of("ENTRY"), List.of("HEALTH_30"), false);

		assertFalse(session.shouldOfferTrial(),
				"저장이 어긋나도 유령 선택창을 띄우면 안 된다");
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
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a");

		assertThrows(UnsupportedOperationException.class, () -> session.chosen().add("b"));
	}
}
