package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Trigger;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
		// 시련을 켠 팀. 끈 팀은 자리도 카드도 없는 것이 정상이라 이 시험의 대상이 아니다.
		return new DragonTrialSession(TEAM, 1000L, true);
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

		session.choose("a", 1000L);

		assertTrue(session.shouldOfferTrial(), "앞을 끝냈으면 다음이 와야 한다");
		assertEquals(Trigger.HEALTH_50, session.peekTrigger());
	}

	@Test
	void 줄이_비면_더_뜨지_않는다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a", 1000L);

		assertFalse(session.shouldOfferTrial());
		assertNull(session.peekTrigger());
	}

	@Test
	void 같은_시련을_두_번_고를_수_없다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		assertTrue(session.choose("a", 1000L));

		session.fire(Trigger.HEALTH_80);
		session.beginChoice();

		assertFalse(session.choose("a", 2000L), "같은 카드가 두 장 쌓이면 효과가 두 번 걸린다");
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
		session.restore(List.of("a"), List.of("ENTRY", "HEALTH_80"), List.of("HEALTH_80"), false,
				Map.of("a", 1200L));

		assertEquals(1, session.trialCount());
		assertEquals(2, session.fired().size());
		assertTrue(session.shouldOfferTrial(), "줄에 남아 있던 자리는 다시 떠야 한다");
		assertEquals(Trigger.HEALTH_80, session.peekTrigger());
		assertFalse(session.fire(Trigger.ENTRY), "복원한 뒤에도 이미 센 자리는 다시 세지 않는다");
	}

	@Test
	void 복원할_때_모르는_이름은_버린다() {
		DragonTrialSession session = session();
		session.restore(List.of(), List.of("ENTRY", "없어진_트리거"), List.of("없어진_트리거"), false,
				null);

		assertEquals(1, session.fired().size(), "옛 저장 파일에 없어진 자리가 있어도 서버를 막지 않는다");
		assertFalse(session.shouldOfferTrial());
	}

	@Test
	void 터진_적_없는_자리는_줄에_서지_못한다() {
		DragonTrialSession session = session();
		session.restore(List.of(), List.of("ENTRY"), List.of("HEALTH_30"), false, null);

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
		session.choose("a", 1000L);

		assertThrows(UnsupportedOperationException.class, () -> session.chosen().add("b"));
	}

	@Test
	void 카드를_받은_틱이_기록된다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();

		assertTrue(session.choose("a", 1440L));

		assertEquals(1440L, session.grantedTick("a"));
		assertEquals(Map.of("a", 1440L), session.grantedTicks());
	}

	@Test
	void 기록이_없는_카드는_전투_시작_틱을_돌려준다() {
		DragonTrialSession session = session();

		assertEquals(1000L, session.grantedTick("없는_카드"), "전투 시작이 기준이다");
		assertNotEquals(0L, session.grantedTick("없는_카드"),
				"0 이면 위상이 월드 시간과 같아져 고치려던 문제로 되돌아간다");
	}

	@Test
	void 주기가_같은_카드도_위상이_어긋난다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a", 1100L);
		session.fire(Trigger.HEALTH_80);
		session.beginChoice();
		session.choose("b", 1730L);

		assertNotEquals(session.grantedTick("a"), session.grantedTick("b"),
				"기준이 같으면 주기가 같은 카드 둘이 영원히 같은 틱에 함께 터진다");
	}

	@Test
	void 고르지_못한_카드의_틱은_남지_않는다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a", 1100L);
		session.fire(Trigger.HEALTH_80);
		session.beginChoice();

		assertFalse(session.choose("a", 2500L), "이미 고른 카드다");

		assertEquals(1100L, session.grantedTick("a"), "거절당한 두 번째 호출이 기록을 덮어쓰면 안 된다");
		assertEquals(1, session.grantedTicks().size(), "고른 목록과 어긋나면 안 된다");
	}

	@Test
	void 복원하면_받은_틱도_이어받는다() {
		DragonTrialSession session = session();
		session.restore(List.of("a", "b"), List.of("ENTRY"), List.of(), false,
				Map.of("a", 1100L, "b", 1730L));

		assertEquals(1100L, session.grantedTick("a"));
		assertEquals(1730L, session.grantedTick("b"),
				"재시작마다 위상이 초기화되면 예고 없이 맞는 일이 재시작할 때마다 되돌아온다");
	}

	@Test
	void 고른_적_없는_카드의_틱은_복원에서_버린다() {
		DragonTrialSession session = session();
		Map<String, Long> marks = new HashMap<>();
		marks.put("a", 1100L);
		marks.put("고른_적_없다", 1200L);
		marks.put("값이_없다", null);
		session.restore(List.of("a"), List.of("ENTRY"), List.of(), false, marks);

		assertEquals(Map.of("a", 1100L), session.grantedTicks(),
				"저장이 어긋나도 고른 목록에 없는 카드의 틱을 들고 있으면 안 된다");
	}

	@Test
	void 받은_틱이_없던_옛_저장도_되살아난다() {
		DragonTrialSession session = session();
		session.restore(List.of("a"), List.of("ENTRY"), List.of(), false, null);

		assertTrue(session.grantedTicks().isEmpty());
		assertEquals(1000L, session.grantedTick("a"),
				"이 칸이 없던 파일을 읽으면 전투 시작이 기준이다");
	}

	@Test
	void 받은_틱_목록은_밖에서_고칠_수_없다() {
		DragonTrialSession session = session();
		session.fire(Trigger.ENTRY);
		session.beginChoice();
		session.choose("a", 1100L);

		assertThrows(UnsupportedOperationException.class,
				() -> session.grantedTicks().put("b", 1200L));
	}

	// ------------------------------------------------------------- 시련을 끈 팀

	/**
	 * 팀 설정에서 시련을 끈 팀.
	 *
	 * <p>여기서 막지 못하면 「끔」이 화면에만 남고 실제로는 카드가 걸린다. 그 어긋남은
	 * 엔드에 도착해서야 드러나고, 이 모드에서 그 자리의 실수는 월드 삭제로 이어진다.
	 */
	private static DragonTrialSession disabled() {
		return new DragonTrialSession(TEAM, 1000L, false);
	}

	@Test
	void 끈_팀은_자리가_터져도_세지_않는다() {
		DragonTrialSession session = disabled();

		assertFalse(session.fire(Trigger.ENTRY));
		assertFalse(session.fire(Trigger.HEALTH_80), "고정 시련 자리도 마찬가지다");
		assertTrue(session.fired().isEmpty());
		assertEquals(0, session.queuedCount());
		assertFalse(session.shouldOfferTrial(), "줄이 비었으므로 룰렛이 열릴 근거가 없다");
	}

	@Test
	void 끈_팀은_시험_명령으로도_카드가_쌓이지_않는다() {
		DragonTrialSession session = disabled();

		// /shareteam trialtest give 는 자리를 거치지 않고 곧장 choose 로 들어온다.
		assertFalse(session.choose("a", 1100L));
		assertEquals(0, session.trialCount());
	}

	@Test
	void 끈_팀은_저장에_남아_있던_카드도_되살리지_않는다() {
		DragonTrialSession session = disabled();
		session.restore(List.of("a"), List.of("ENTRY"), List.of("ENTRY"), true,
				new HashMap<>(Map.of("a", 1100L)));

		assertEquals(0, session.trialCount(), "끄기 전에 쌓아 둔 카드가 되살아나면 안 된다");
		assertTrue(session.fired().isEmpty());
		assertFalse(session.shouldOfferTrial());
		assertFalse(session.awaitingChoice());
	}

	@Test
	void 켠_팀과_끈_팀은_스스로_어느_쪽인지_안다() {
		assertTrue(session().trialsEnabled());
		assertFalse(disabled().trialsEnabled());
	}
}
