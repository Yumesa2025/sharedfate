package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.sync.DragonTrialSuspend.Verdict;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤이 메모리에 없다」와 「드래곤이 죽었다」를 가르는 셈과, 멈췄다 이을 때의 셈.
 *
 * <p>2026-10-18 본서버 로그 — 「(팀원) left the game」 1초 뒤 「[END] 팀 'test' 드래곤 처치」. 팀이 전부
 * 접속을 끊어 섬 청크가 내려간 것을 처치로 읽었고, 최후의 저항이면 팀이 무적이 됐다.
 */
class DragonTrialSuspendTest {

	@BeforeAll
	static void setUp() {
		// TrialEntrance · DragonLastStand 의 정적 칸이 바닐라 글자·소리를 든다.
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 판정

	/** ⚠⚠ 그날의 판 — 살아 있는 드래곤이 없는데 죽은 근거도 없다. 처치가 아니다. */
	@Test
	void 내려간_드래곤은_처치가_아니다() {
		assertEquals(Verdict.ABSENT, DragonTrialSuspend.judge(false, false, false, false),
				"섬 청크가 내려간 것을 「드래곤 처치」로 읽는다 — 나갔다 들어오면 무적 공략이 된다");
		assertEquals(Verdict.ABSENT, DragonTrialSuspend.judge(false, false, false, true),
				"접속 직후 드래곤이 올라오기 전 몇 틱도 처치가 아니다");
	}

	/** 처치의 근거는 둘 — 죽은 개체가 월드에 있거나({@code tickDeath} 200틱) 바닐라가 처치를 적었다. */
	@Test
	void 진짜로_죽었을_때만_처치다() {
		assertEquals(Verdict.KILLED, DragonTrialSuspend.judge(false, true, false, true),
				"체력 0 이 된 틱(시체가 월드에 있다)에 처치를 못 잡는다");
		assertEquals(Verdict.KILLED, DragonTrialSuspend.judge(false, false, true, true),
				"/kill 처럼 시체 없이 바닐라 깃발만 서는 길을 못 잡는다");
		assertEquals(Verdict.KILLED, DragonTrialSuspend.judge(false, true, true, false),
				"팀이 없어도 죽은 것은 죽은 것이다");
	}

	/** 살아 있는 드래곤이 있으면 죽음 근거를 보지 않는다 — 크리스탈로 되살리는 틱 · 드래곤이 둘인 판. */
	@Test
	void 살아_있는_드래곤이_있으면_처치가_아니다() {
		assertEquals(Verdict.FIGHTING, DragonTrialSuspend.judge(true, true, true, true));
		assertEquals(Verdict.AWAY, DragonTrialSuspend.judge(true, true, true, false));
	}

	/** ⚠ 「한 명이라도 엔드에 남아 있으면 지금처럼 정상 진행」 — 사람 지시. */
	@Test
	void 한_명이라도_있으면_싸운다() {
		assertEquals(Verdict.FIGHTING, DragonTrialSuspend.judge(true, false, false, true));
		assertEquals(Verdict.AWAY, DragonTrialSuspend.judge(true, false, false, false),
				"엔드가 빈 틱 — 드래곤이 내려가기 전에 멈춰야 실제 체력을 읽는다");
	}

	@Test
	void 멈추는_판정은_둘이다() {
		assertTrue(DragonTrialSuspend.pauses(Verdict.AWAY));
		assertTrue(DragonTrialSuspend.pauses(Verdict.ABSENT));
		assertFalse(DragonTrialSuspend.pauses(Verdict.FIGHTING));
		assertFalse(DragonTrialSuspend.pauses(Verdict.KILLED), "처치는 멈춤이 아니라 닫힘이다");
	}

	// ------------------------------------------------------------------ 입장 연출

	/** 크리스탈을 거둔 채 멈추면 되살리는 단계가 영영 안 와 0개로 남는다 — 그때는 처음부터 다시. */
	@Test
	void 입장_연출_도중이면_처음부터_다시() {
		long start = 10_000L;
		assertTrue(DragonTrialSuspend.replaysEntrance(start, start), "첫 틱");
		assertTrue(DragonTrialSuspend.replaysEntrance(start, start + TrialEntrance.VANISH_TICKS / 2),
				"크리스탈을 거둔 10초 안 — 0개로 남던 그 구간");
		assertTrue(DragonTrialSuspend.replaysEntrance(start, start + TrialEntrance.CINEMATIC_TICKS),
				"부활이 끝나는 틱");
		assertTrue(DragonTrialSuspend.replaysEntrance(start, start + TrialEntrance.ROULETTE_DELAY_TICKS),
				"입장 룰렛 직전의 숨 돌리는 틈까지");
		assertFalse(DragonTrialSuspend.replaysEntrance(start,
				start + TrialEntrance.ROULETTE_DELAY_TICKS + 1), "연출이 끝난 판을 다시 돌린다");
		assertTrue(DragonTrialSuspend.replaysEntrance(start, start - 5L),
				"재시작 직후 시각이 원점보다 작아도 터지지 않는다");
	}

	/** 처음에는 연출의 원점이 전투가 열린 틱이고, 이을 때 그것만 옮긴다 — 카드 위상의 원점은 그대로. */
	@Test
	void 연출을_다시_돌리면_원점만_옮긴다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		assertEquals(1_000L, session.entranceTick());
		session.suspend(true, 1500.0F, -1);
		assertTrue(session.resume(9_000L));
		assertEquals(9_000L, session.entranceTick(), "연출이 처음부터 안 돈다");
		assertEquals(1_000L, session.startedTick(), "패시브·카드 위상의 원점까지 옮겼다");
		assertTrue(TrialEntrance.managesCrystals(session, 9_000L),
				"이은 틱에 크리스탈 셈을 멈추지 않는다 — 거두는 순간 「첫 크리스탈」이 헛되이 터진다");
	}

	@Test
	void 연출이_끝난_뒤에_멈췄으면_원점을_옮기지_않는다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.suspend(false, 900.0F, -1);
		assertFalse(session.resume(50_000L));
		assertEquals(1_000L, session.entranceTick());
		assertFalse(TrialEntrance.managesCrystals(session, 50_000L));
	}

	// ------------------------------------------------------------------ 체력

	/** 저장해 둔 체력으로 <b>정확히</b> — 회복도 깎임도 없게. */
	@Test
	void 적어_둔_체력으로_정확히_되돌린다() {
		assertEquals(1234.5F, DragonTrialSuspend.restoredHealth(1234.5F, 200.0F, 2400.0F), 0.0F,
				"200 에 잘린 채 남는다(0.41 — 다시 올라오면 Health 가 바닐라 최대에 잘렸다)");
		assertEquals(1234.5F, DragonTrialSuspend.restoredHealth(1234.5F, 2400.0F, 2400.0F), 0.0F,
				"가득으로 읽혀 최대 체력까지 회복한다(0.41 의 strengthenDragon)");
	}

	@Test
	void 모르면_손대지_않는다() {
		assertEquals(700.0F, DragonTrialSuspend.restoredHealth(Float.NaN, 700.0F, 2400.0F), 0.0F);
		assertEquals(700.0F, DragonTrialSuspend.restoredHealth(0.0F, 700.0F, 2400.0F), 0.0F,
				"0 을 쓰면 우리가 드래곤을 죽인다");
		assertEquals(700.0F, DragonTrialSuspend.restoredHealth(-3.0F, 700.0F, 2400.0F), 0.0F);
		assertEquals(2400.0F, DragonTrialSuspend.restoredHealth(9999.0F, 700.0F, 2400.0F), 0.0F,
				"최대 체력을 넘는다");
	}

	/**
	 * 최후의 저항 진입 연출 도중에 끊기면 차오를 몫을 다 채운 값을 지킨다 — 이을 때 회복을 다시 주지 않으므로,
	 * 반쯤 찬 값을 지키면 나갔다 들어오는 것만으로 회복이 깎인다.
	 */
	@Test
	void 진입_회복_도중이면_다_채운_값을_지킨다() {
		float max = 2400.0F;
		float entry = max * 0.30F;
		float half = entry + max * 0.10F;
		assertEquals(DragonLastStand.healedHealth(entry, max),
				DragonTrialSuspend.pausedHealth(half, max, true, entry), 0.01F);
		assertEquals(half, DragonTrialSuspend.pausedHealth(half, max, false, entry), 0.0F,
				"연출이 끝난 판(또는 회복 없는 다시 세운 판)에서 체력을 올린다");
	}

	// ------------------------------------------------------------------ 오브젝트 파도

	/** 끝까지 치른 파도만 센다 — 도는 중이던 파도는 개체를 걷었으니 이을 때 다시 온다. */
	@Test
	void 치른_파도만_센다() {
		assertEquals(0, DragonTrialSuspend.clearedWaves(0, false));
		assertEquals(0, DragonTrialSuspend.clearedWaves(1, true), "도는 파도를 치른 것으로 셌다");
		assertEquals(1, DragonTrialSuspend.clearedWaves(1, false));
		assertEquals(1, DragonTrialSuspend.clearedWaves(2, true));
		assertEquals(2, DragonTrialSuspend.clearedWaves(2, false));
		assertEquals(0, DragonTrialSuspend.clearedWaves(0, true), "음수가 나온다");
	}

	// ------------------------------------------------------------------ 세션

	/** 멈춰도 카드·자리·줄은 그대로다 — 룰렛 도중에 끊겨 쌓인 카드도. */
	@Test
	void 멈춰도_카드와_자리는_그대로다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.fire(TrialCatalog.Trigger.ENTRY);
		session.fire(TrialCatalog.Trigger.HEALTH_80);
		session.beginChoice();
		session.choose("a", 1_300L);
		session.suspend(false, 1500.0F, -1);

		assertTrue(session.suspended());
		assertEquals(1, session.trialCount());
		assertEquals(1300L, session.grantedTick("a"), "받은 틱이 사라지면 위상이 튄다");
		assertEquals(TrialCatalog.Trigger.HEALTH_80, session.peekTrigger(), "줄에 남은 자리가 사라졌다");
		assertTrue(session.fired().contains(TrialCatalog.Trigger.ENTRY));
	}

	/** 다시 멈출 때 모르는 체력(NaN)이 적어 둔 값을 지우지 않는다 — 재시작 뒤 첫 틱의 길. */
	@Test
	void 모르는_체력은_적어_둔_값을_지우지_않는다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.suspend(false, 1500.0F, 1);
		session.suspend(false, Float.NaN, -1);
		assertEquals(1500.0F, session.dragonHealth(), 0.0F);
		assertEquals(1, session.lastStandWaves(), "음수(모름)가 치른 파도를 지웠다");
	}

	/** 이으면 적어 둔 체력을 버린다 — 남기면 다음 강제 종료 뒤에 낡은 값으로 되돌려 드래곤이 회복한다. */
	@Test
	void 이으면_적어_둔_체력을_버린다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.suspend(false, 1500.0F, 1);
		session.resume(2_000L);
		assertFalse(session.suspended());
		assertTrue(Float.isNaN(session.dragonHealth()));
		assertEquals(1, session.lastStandWaves(), "치른 파도는 이은 뒤에도 남아야 다음 재진입이 안 준다");
	}

	@Test
	void 저장에서_중단_상태를_되살린다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.restoreSuspension(true, 4_000L, true, 1777.0F, 2);
		assertTrue(session.suspended());
		assertTrue(session.replayEntrance());
		assertEquals(4_000L, session.entranceTick());
		assertEquals(1777.0F, session.dragonHealth(), 0.0F);
		assertEquals(2, session.lastStandWaves());

		DragonTrialSession old = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		old.restoreSuspension(false, null, true, 1777.0F, 0);
		assertEquals(1_000L, old.entranceTick(), "옛 파일(칸 없음)의 연출 원점은 전투가 열린 틱이다");
		assertFalse(old.replayEntrance(), "싸우던 세션에 「다시 돌린다」가 붙었다");
		assertTrue(Float.isNaN(old.dragonHealth()),
				"싸우던 세션의 체력을 믿었다 — 강제 종료 뒤 낡은 값으로 되돌린다");
	}

	// ------------------------------------------------------------------ 2026-10-18 검토 — 이어 가기

	/**
	 * 룰렛 도중에 끊기면 멈추는 틱의 경과가 정확히 {@code ROULETTE_DELAY_TICKS} 다(룰렛 동안 게임 시각이 선다).
	 * 입장 자리가 이미 소비됐으면(카드가 쌓였다) 연출을 다시 돌리지 않는다.
	 */
	@Test
	void 입장_자리가_소비됐으면_연출을_다시_돌리지_않는다() {
		long start = 10_000L;
		long rouletteTick = start + TrialEntrance.ROULETTE_DELAY_TICKS;
		assertTrue(DragonTrialSuspend.replaysEntrance(start, rouletteTick, true), "룰렛이 뜨기 전이면 처음부터");
		assertFalse(DragonTrialSuspend.replaysEntrance(start, rouletteTick, false),
				"카드는 쌓였는데 입장 연출이 처음부터 다시 돈다");
		assertFalse(DragonTrialSuspend.replaysEntrance(start, start + 5L, false));

		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), start, true);
		session.fire(TrialCatalog.Trigger.ENTRY);
		assertTrue(session.pending(TrialCatalog.Trigger.ENTRY));
		session.beginChoice();
		session.choose("a", rouletteTick);
		assertFalse(session.pending(TrialCatalog.Trigger.ENTRY), "꺼낸 자리가 줄에 남아 있다");
	}

	/** 재개 직후 3초 보호를 받을 사람 — 지난 틱 명단에 없던 사람만. */
	@Test
	void 새로_온_팀원만_보호를_받는다() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		UUID c = UUID.randomUUID();
		assertEquals(List.of(a, b), DragonTrialSuspend.newArrivals(Set.of(), List.of(a, b)), "이은 틱에는 전원");
		assertEquals(List.of(c), DragonTrialSuspend.newArrivals(Set.of(a, b), List.of(a, b, c)),
				"늦게 온 사람이 보호를 못 받는다");
		assertTrue(DragonTrialSuspend.newArrivals(Set.of(a, b), List.of(a)).isEmpty(), "남아 있는 사람이 또 받는다");
		assertEquals(60, DragonTrialSuspend.RESUME_GRACE_TICKS, "사람 결정은 3초");
	}

	/** 카드의 받은 틱을 멈춰 있던 만큼 민다 — 창 있는 카드를 끊기로 건너뛰지 못한다. */
	@Test
	void 받은_틱을_멈춘_만큼_민다() {
		assertEquals(1_600L, DragonTrialSuspend.resumedGrant(1_000L, 2_000L, 2_600L));
		assertEquals(1_000L, DragonTrialSuspend.resumedGrant(1_000L, Long.MIN_VALUE, 9_000L), "모르면 밀지 않는다");
		assertEquals(1_000L, DragonTrialSuspend.resumedGrant(1_000L, 2_000L, 1_500L), "시각이 거꾸로면 밀지 않는다");

		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.fire(TrialCatalog.Trigger.HEALTH_80);
		session.beginChoice();
		session.choose("rain", 5_000L);
		// 받은 지 100틱 만에 끊고 10분 뒤 돌아온다.
		session.suspend(false, 1500.0F, -1, 5_100L);
		assertEquals(5_100L, session.pausedAt());
		session.suspend(false, Float.NaN, -1, 9_999L);
		assertEquals(5_100L, session.pausedAt(), "다시 멈추는 길이 멈춘 틱을 덮어썼다");
		session.resume(17_100L);
		assertEquals(17_000L, session.grantedTick("rain"), "창이 멈춘 자리에서 잇지 않는다");
		assertEquals(Long.MIN_VALUE, session.pausedAt());
		assertEquals(100L, TrialRisks.elapsedSinceGrant(17_100L, session.grantedTick("rain")));
	}

	/** 한 번짜리 카드가 한 일은 줄지 않는다 — 재시작 첫 틱처럼 빈 기억으로 다시 멈춰도. 카드를 비우면 함께 버린다. */
	@Test
	void 한_일은_줄지_않고_카드를_비우면_버린다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.rememberDone(Map.of("sharedfate:crystal_revival#0", 1, "sharedfate:night_host#0", 3));
		session.rememberDone(Map.of());
		session.rememberDone(Map.of("sharedfate:night_host#0", 2));
		assertEquals(1, session.riskDone().get("sharedfate:crystal_revival#0"), "빈 기억이 한 일을 지웠다");
		assertEquals(2, session.riskDone().get("sharedfate:night_host#0"), "살아 있는 수는 마지막 값");
		session.restore(List.of(), List.of(), List.of(), false, Map.of());
		assertTrue(session.riskDone().isEmpty(), "비운 카드가 한 일이 남아 같은 카드를 다시 받으면 죽은 카드가 된다");

		DragonTrialSession loaded = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		loaded.restoreSuspension(true, null, false, 900.0F, 0);
		loaded.restorePause(4_000L, 777L, Map.of("k#0", 1));
		assertEquals(4_000L, loaded.pausedAt());
		assertEquals(777L, loaded.lastStandClock());
		assertEquals(Map.of("k#0", 1), loaded.riskDone());
		DragonTrialSession fighting = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		fighting.restorePause(4_000L, null, null);
		assertEquals(Long.MIN_VALUE, fighting.pausedAt(), "싸우던 세션에 멈춘 틱이 붙었다 — 받은 틱이 튄다");
		assertEquals(Long.MIN_VALUE, fighting.lastStandClock());
	}

	/** 강화를 새로 붙이거나 뗄 때 체력은 비율로 — 이미 지난 문턱 위로는 자른다. */
	@Test
	void 강화를_새로_붙이면_체력은_비율로_옮긴다() {
		assertEquals(2400.0F, DragonTrialSuspend.rescaledHealth(200.0F, 200.0F, 2400.0F, 1.0F), 0.01F,
				"가득이던 드래곤이 가득이 아니다(전투를 여는 틱)");
		assertEquals(1800.0F, DragonTrialSuspend.rescaledHealth(150.0F, 200.0F, 2400.0F, 1.0F), 0.01F,
				"200 / 2400 이 되어 문턱 셋이 한꺼번에 터진다");
		assertEquals(1920.0F, DragonTrialSuspend.rescaledHealth(200.0F, 200.0F, 2400.0F, 0.8F), 0.01F,
				"80% 문턱을 지난 드래곤이 가득으로 되돌아온다");
		assertEquals(150.0F, DragonTrialSuspend.rescaledHealth(1800.0F, 2400.0F, 200.0F, 1.0F), 0.01F,
				"강화를 떼면 가득이 된다");
		assertEquals(0.0F, DragonTrialSuspend.rescaledHealth(0.0F, 200.0F, 2400.0F, 1.0F), 0.0F, "죽은 드래곤을 살린다");

		assertEquals(1.0F, DragonTrialSuspend.healthCeiling(EnumSet.noneOf(TrialCatalog.Trigger.class)), 0.0F);
		assertEquals(0.8F, DragonTrialSuspend.healthCeiling(EnumSet.of(TrialCatalog.Trigger.HEALTH_80)), 0.0F);
		assertEquals(0.5F, DragonTrialSuspend.healthCeiling(
				EnumSet.of(TrialCatalog.Trigger.HEALTH_80, TrialCatalog.Trigger.HEALTH_50)), 0.0F);
		assertEquals(0.5F, DragonTrialSuspend.healthCeiling(EnumSet.of(TrialCatalog.Trigger.HEALTH_30)), 0.001F,
				"최후의 저항은 진입 회복 +20%p 를 더한 50% 까지");
	}
}
