package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Trigger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 자리가 터지고 룰렛이 열리기까지의 지연.
 *
 * <p>월드도 드래곤도 없이 {@link DragonTrialManager#trialDue} 만 부른다. 이 계산이 틀나는 길은
 * 셋이고 <b>셋 다 눈으로 보고 잡을 수 없다.</b>
 *
 * <ol>
 * <li>지연 0 인데 <b>한 틱 밀린다</b> — 「즉시」가 목적인데 화면에서는 즉시처럼 보인다
 * <li>다음 자리가 <b>앞 자리의 지연을 물려받는다</b> — 줄이 밀렸을 때만 나오고, 줄이 밀리는 것은
 *     화력이 센 팀이 체력 문턱 여럿을 관통했을 때뿐이다
 * <li>자리를 새로 만들며 지연을 <b>안 정한다</b> — 컴파일도 로그도 조용하다
 * </ol>
 *
 * <p>전멸이 곧 월드 삭제인 판이라 「돌려 보고 고친다」가 통하지 않는다.
 */
class TrialDelayTest {
	/** 전투가 열린 시각. 0 을 쓰면 「안 더한 것」과 구별되지 않는다. */
	private static final long STARTED = 1_000L;
	/** 자리가 터진 시각. 전투 시작과 다른 값이어야 둘을 헷갈린 코드가 걸린다. */
	private static final long FIRED_AT = 1_234L;

	/** 지연 시각은 팀별 정적 지도에 남는다. 시험이 끝나면 걷어낸다. */
	private final List<DragonTrialSession> opened = new ArrayList<>();

	@AfterEach
	void 남은_지연을_걷어낸다() {
		for (DragonTrialSession session : opened) {
			DragonTrialManager.resetTrialDelay(session.teamId());
		}
		opened.clear();
	}

	/** 시험마다 새 팀이다. 팀 id 가 겹치면 앞 시험이 적어 둔 시각을 물려받는다. */
	private DragonTrialSession session(Trigger... fired) {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), STARTED);
		for (Trigger trigger : fired) {
			session.fire(trigger);
		}
		opened.add(session);
		return session;
	}

	/** 룰렛을 마치고 자리를 줄에서 꺼내는 것. {@code applyFinishedTrial} 이 하는 일과 같다. */
	private static void 자리를_소화한다(DragonTrialSession session, String trialId, long now) {
		session.beginChoice();
		session.choose(trialId, now);
		DragonTrialManager.resetTrialDelay(session.teamId());
	}

	@Test
	void 자리마다_지연이_다르다() {
		assertEquals(300, Trigger.ENTRY.delayTicks(),
				"입장은 다른 차원에서 떨어진 직후라 어디에 왔는지부터 읽어야 한다");
		for (Trigger trigger : Trigger.values()) {
			if (trigger == Trigger.ENTRY) {
				continue;
			}
			assertEquals(0, trigger.delayTicks(),
					trigger.label() + " 는 팀이 스스로 만든 결과라 원인이 이미 분명하다 — 곧바로 연다");
		}
	}

	@Test
	void 자리_여섯이_전부_지연_값을_들고_있다() {
		Map<Trigger, Integer> expected = new EnumMap<>(Trigger.class);
		expected.put(Trigger.ENTRY, TrialCatalog.DELAY_SETTLE_TICKS);
		expected.put(Trigger.FIRST_CRYSTAL, TrialCatalog.DELAY_NONE);
		expected.put(Trigger.ALL_CRYSTALS, TrialCatalog.DELAY_NONE);
		expected.put(Trigger.HEALTH_80, TrialCatalog.DELAY_NONE);
		expected.put(Trigger.HEALTH_50, TrialCatalog.DELAY_NONE);
		expected.put(Trigger.HEALTH_30, TrialCatalog.DELAY_NONE);

		assertEquals(expected.size(), Trigger.values().length,
				"자리를 새로 만들었으면 지연을 정하고 여기에도 적어라");
		for (Trigger trigger : Trigger.values()) {
			assertEquals(expected.get(trigger), trigger.delayTicks(),
					trigger.label() + " 의 지연이 바뀌었다");
		}
	}

	@Test
	void 지연과_연출을_안_정하고_자리를_만드는_길이_없다() {
		// 열거형 생성자의 앞 두 인자는 컴파일러가 붙이는 이름과 순번이다. 그 뒤가 우리가 적는
		// 값 — 이름 · 지연 · 어떻게 뜨는가다. 둘 중 하나라도 안 받는 생성자가 생기면 그 길로
		// 정하지 않은 자리가 들어오고, 그 자리는 조용히 「자리를 모를 때」의 값을 쓰게 된다.
		for (Constructor<?> constructor : Trigger.class.getDeclaredConstructors()) {
			Class<?>[] parameters = constructor.getParameterTypes();
			assertEquals(5, parameters.length,
					"지연이나 연출을 안 받는 Trigger 생성자가 생겼다 —"
							+ " 자리를 정하지 않고 만들 수 있게 된다");
			assertEquals(int.class, parameters[3], "넷째 인자가 지연이어야 한다");
			assertEquals(TrialCatalog.Reveal.class, parameters[4],
					"다섯째 인자가 「어떻게 뜨는가」여야 한다");
		}
	}

	@Test
	void 자리_여섯이_전부_어떻게_뜨는지를_들고_있다() {
		Map<Trigger, TrialCatalog.Reveal> expected = new EnumMap<>(Trigger.class);
		expected.put(Trigger.ENTRY, TrialCatalog.Reveal.ROULETTE);
		expected.put(Trigger.FIRST_CRYSTAL, TrialCatalog.Reveal.ROULETTE);
		expected.put(Trigger.ALL_CRYSTALS, TrialCatalog.Reveal.ROULETTE);
		// 카드가 한 장뿐이다. 결과가 정해진 굴림을 보여 주면 연출이 거짓말이 된다.
		expected.put(Trigger.HEALTH_80, TrialCatalog.Reveal.FIXED_SCREEN);
		expected.put(Trigger.HEALTH_50, TrialCatalog.Reveal.ROULETTE);
		// 최후의 저항은 굉음·화면 흔들림·엔더맨 소멸·보스바 이름 변경이 이미 「판이 바뀌었다」를
		// 말한다. 거기에 정지 화면을 얹으면 멈춤이 두 번 겹친다.
		expected.put(Trigger.HEALTH_30, TrialCatalog.Reveal.SILENT);

		assertEquals(expected.size(), Trigger.values().length,
				"자리를 새로 만들었으면 어떻게 뜰지를 정하고 여기에도 적어라");
		for (Trigger trigger : Trigger.values()) {
			assertEquals(expected.get(trigger), trigger.reveal(),
					trigger.label() + " 가 뜨는 방식이 바뀌었다");
		}
	}

	@Test
	void 지연이_0이면_자리가_터진_그_틱에_룰렛이_열린다() {
		for (Trigger trigger : Trigger.values()) {
			if (trigger.delayTicks() != 0) {
				continue;
			}
			DragonTrialSession session = session(trigger);
			// 자리가 터지고 처음 보는 틱이다. 여기서 거짓이면 「적어 두고 다음 틱에 본다」라
			// 한 틱이 밀린다.
			assertTrue(DragonTrialManager.trialDue(session, FIRED_AT),
					trigger.label() + " 는 터진 그 틱에 열려야 한다");
		}
	}

	@Test
	void 지연이_있는_자리는_그_틱이_지나기_전에는_안_열린다() {
		DragonTrialSession session = session(Trigger.ENTRY);

		for (long now = FIRED_AT; now < FIRED_AT + TrialCatalog.DELAY_SETTLE_TICKS; now++) {
			assertFalse(DragonTrialManager.trialDue(session, now),
					"입장 뒤 " + (now - FIRED_AT) + "틱 — 아직 아니다");
		}
		assertTrue(DragonTrialManager.trialDue(session,
						FIRED_AT + TrialCatalog.DELAY_SETTLE_TICKS),
				"15초가 지났으면 열린다. 여기서 거짓이면 룰렛이 영영 안 열린다");
	}

	@Test
	void 줄에_자리가_둘_이상_밀려도_각자의_지연을_쓴다() {
		// 입장 직후 크리스탈을 곧바로 깬 팀이다. 입장이 앞이라 15초를 기다린다.
		DragonTrialSession session = session(Trigger.ENTRY, Trigger.FIRST_CRYSTAL);
		assertFalse(DragonTrialManager.trialDue(session, FIRED_AT));
		long entryOpened = FIRED_AT + TrialCatalog.DELAY_SETTLE_TICKS;
		assertTrue(DragonTrialManager.trialDue(session, entryOpened));

		자리를_소화한다(session, "sharedfate:ground_strike", entryOpened);

		// 이제 줄 맨 앞은 첫 크리스탈이다. 앞 자리의 15초를 물려받으면 안 된다.
		assertEquals(Trigger.FIRST_CRYSTAL, session.peekTrigger());
		assertTrue(DragonTrialManager.trialDue(session, entryOpened),
				"둘째 자리는 자기 지연 0 을 쓴다");
	}

	@Test
	void 즉시_자리_뒤에_기다리는_자리가_와도_기다린다() {
		// 반대 차례. 앞이 0 이었다고 뒤까지 0 이 되면 입장 화면이 겹친다.
		DragonTrialSession session = session(Trigger.ALL_CRYSTALS, Trigger.ENTRY);
		assertTrue(DragonTrialManager.trialDue(session, FIRED_AT));

		자리를_소화한다(session, "sharedfate:lightning_storm", FIRED_AT);

		assertEquals(Trigger.ENTRY, session.peekTrigger());
		assertFalse(DragonTrialManager.trialDue(session, FIRED_AT),
				"둘째 자리는 입장이라 15초를 기다린다");
		assertTrue(DragonTrialManager.trialDue(session,
				FIRED_AT + TrialCatalog.DELAY_SETTLE_TICKS));
	}

	@Test
	void 줄이_비어_자리를_모르면_기다리는_쪽이다() {
		// 터진 자리가 없으면 shouldOfferTrial 이 먼저 막으므로 여기까지 오지 않는다. 그래도
		// 모르는 채 「즉시」를 고르는 것보다 기다리는 쪽이 안전하다.
		DragonTrialSession session = session();

		assertFalse(DragonTrialManager.trialDue(session, FIRED_AT));
		assertTrue(DragonTrialManager.trialDue(session,
				FIRED_AT + TrialCatalog.DELAY_SETTLE_TICKS));
	}

	@Test
	void 지연_상수가_초와_맞는다() {
		assertEquals(15 * 20, TrialCatalog.DELAY_SETTLE_TICKS, "15초다");
		assertEquals(0, TrialCatalog.DELAY_NONE);
	}
}
