package com.sharedfate.client;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.hud.TrialTimerRows;
import com.sharedfate.net.TrialTimersPayload;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>받는 쪽 시계</b> — 받은 남은 틱을 언제 줄이고 언제 멈추는가.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」.
 *
 * <p>이 시계가 틀리는 방식은 조용하다 — 시련 룰렛이 판을 얼린 17초 동안 숫자가 계속 줄면 서버의
 * 실행기는 멈춰 있는데 화면만 「곧 온다」고 말하고, 멈춘 채 다시 풀리면 숫자가 한꺼번에 튄다.
 */
class ClientTrialTimersTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void forget() {
		ClientTrialTimers.clear();
	}

	private static TrialTimersPayload payload(boolean frozen, int remaining, int castRemaining) {
		return new TrialTimersPayload(true, frozen,
				List.of(Entry.countdown("a", "자리 폭격", Kind.DAMAGE, remaining, 240, 50)),
				Optional.of(new Cast("부채꼴 브레스", Kind.DAMAGE, Cast.STATE_WARNING, castRemaining,
						80, "")));
	}

	private static int remainingNow() {
		TrialTimersPayload current = ClientTrialTimers.current();
		assertNotNull(current);
		return TrialTimerRows.remainingAfter(current.timers().get(0).remainingTicks(),
				ClientTrialTimers.elapsed());
	}

	private static int castRemainingNow() {
		TrialTimersPayload current = ClientTrialTimers.current();
		assertNotNull(current);
		return TrialTimerRows.remainingAfter(current.cast().orElseThrow().remainingTicks(),
				ClientTrialTimers.elapsed());
	}

	@Test
	void 틱마다_줄과_시전_바가_1씩_준다() {
		ClientTrialTimers.update(payload(false, 100, 40));
		for (int i = 0; i < 7; i++) {
			ClientTrialTimers.tick(false);
		}
		assertEquals(93, remainingNow());
		assertEquals(33, castRemainingNow());
	}

	@Test
	void 얼어_있으면_줄지_않는다() {
		ClientTrialTimers.update(payload(true, 100, 40));
		for (int i = 0; i < 50; i++) {
			ClientTrialTimers.tick(false);
		}
		assertEquals(100, remainingNow(), "시련 룰렛이 얼린 동안 서버 실행기의 시계도 선다");
		assertEquals(40, castRemainingNow());
	}

	@Test
	void 일시정지면_줄지_않는다() {
		ClientTrialTimers.update(payload(false, 100, 40));
		for (int i = 0; i < 50; i++) {
			ClientTrialTimers.tick(true);
		}
		assertEquals(100, remainingNow());
	}

	@Test
	void 영에_닿으면_영에서_멈춘다() {
		ClientTrialTimers.update(payload(false, 3, 2));
		for (int i = 0; i < 30; i++) {
			ClientTrialTimers.tick(false);
		}
		assertEquals(0, remainingNow(), "새 묶음이 곧 온다 — 음수로 내려가 「-1.0초」를 띄우지 않는다");
		assertEquals(0, castRemainingNow());
	}

	@Test
	void 새_묶음이_오면_통째로_바꾸고_흐른_틱을_되돌린다() {
		ClientTrialTimers.update(payload(false, 100, 40));
		for (int i = 0; i < 20; i++) {
			ClientTrialTimers.tick(false);
		}
		ClientTrialTimers.update(payload(false, 240, 80));
		assertEquals(0, ClientTrialTimers.elapsed());
		assertEquals(240, remainingNow());
		assertEquals(80, castRemainingNow());
	}

	@Test
	void 얼었다_풀리면_그_자리부터_다시_준다() {
		ClientTrialTimers.update(payload(false, 100, 40));
		for (int i = 0; i < 10; i++) {
			ClientTrialTimers.tick(false);
		}
		// 서버가 멈춤을 알리며 같은 값을 다시 보낸다(멈추는 틱의 남은 틱 = 90).
		ClientTrialTimers.update(payload(true, 90, 30));
		for (int i = 0; i < 40; i++) {
			ClientTrialTimers.tick(false);
		}
		assertEquals(90, remainingNow());
		ClientTrialTimers.update(payload(false, 90, 30));
		ClientTrialTimers.tick(false);
		assertEquals(89, remainingNow());
	}

	@Test
	void 지우라는_묶음이면_비운다() {
		ClientTrialTimers.update(payload(false, 100, 40));
		ClientTrialTimers.update(TrialTimersPayload.HIDDEN);
		assertNull(ClientTrialTimers.current());
		ClientTrialTimers.tick(false);
		assertNull(ClientTrialTimers.current());
	}

	@Test
	void 체력형_줄은_줄이지_않는다() {
		ClientTrialTimers.update(new TrialTimersPayload(true, false,
				List.of(Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.6F)),
				Optional.empty()));
		for (int i = 0; i < 30; i++) {
			ClientTrialTimers.tick(false);
		}
		List<TrialTimerRows.Row> rows = TrialTimerRows.rows(
				ClientTrialTimers.current().timers(), ClientTrialTimers.elapsed());
		assertEquals(TrialTimerRows.State.HEALTH, rows.get(0).state());
		assertEquals("체력 25%", TrialTimerRows.rightText(rows.get(0), rows.get(0).remaining()));
		assertEquals(0.6F, TrialTimerRows.rowRatio(rows.get(0), 0.0F), 1.0E-6F);
	}
}
