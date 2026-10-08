package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.net.TrialTimersPayload;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 드래곤 패턴 타이머 HUD 의 서버 쪽 — 무엇을 담는가.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」.
 *
 * <p>⚠ 이 시험의 중심은 <b>「HUD 의 남은 틱 = 실행기가 실제로 터뜨리는 틱」</b>이다. 실행기의
 * 발동 판정({@code firesAt} · {@code shotIndexAt} · 위상)을 틱마다 굴려 다음 발동을 찾고, 그 틱이
 * HUD 가 말한 틱과 같은지 본다. 주기를 HUD 쪽에서 다시 셈해 흉내 내면 실행기를 고치는 날 HUD 만 옛
 * 주기를 센다 — 그날 이 시험이 멈춘다.
 */
class TrialTimersTest {

	private static final long GRANTED = 10_000L;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void forget() {
		DragonFireBarrage.clearState();
		TrialTimers.clearState();
	}

	private static TrialCatalog.Risk riskOf(String id) {
		TrialCatalog.Trial trial = TrialCatalog.byId(id);
		assertNotNull(trial, "카드가 없다: " + id);
		return trial.risks().get(0);
	}

	/**
	 * {@code now} 에서 본 남은 틱이 <b>실제 다음 발동</b>과 같은가.
	 *
	 * <p>HUD 는 그 틱의 실행기가 다 돈 뒤에 읽으므로 {@code now} 자신의 발동은 「이미 지났다」다.
	 */
	private static void assertNextEvent(String what, LongPredicate firesAt, long now, int remaining) {
		long expected = -1L;
		for (long t = now + 1; t <= now + 100_000L; t++) {
			if (firesAt.test(t)) {
				expected = t;
				break;
			}
		}
		assertTrue(expected > 0L, what + " — 다음 발동을 못 찾았다");
		assertEquals(expected - now, remaining, what + " — now=" + now);
	}

	// ------------------------------------------------------------------ 일반 전투 — 카드

	@Test
	void 자리_폭격과_낙뢰의_남은_틱이_실행기의_실제_발동과_같다() {
		for (String id : List.of("sharedfate:ground_strike", "sharedfate:lightning_storm")) {
			TrialCatalog.Risk.DelayedStrike strike = (TrialCatalog.Risk.DelayedStrike) riskOf(id);
			for (long now = GRANTED; now <= GRANTED + strike.interval() * 3L; now++) {
				TrialTimers.Clock clock = TrialRisks.strikeClock(GRANTED, now, strike);
				assertNotNull(clock);
				assertFalse(clock.active());
				assertNextEvent(id, t -> TrialRisks.firesAt(t, GRANTED, strike.interval()), now,
						clock.remaining());
				assertEquals(strike.interval(), clock.total());
			}
		}
	}

	@Test
	void 자리_폭격의_예고는_바닥_고리가_깔리는_50틱이다() {
		TrialCatalog.Risk.DelayedStrike strike =
				(TrialCatalog.Risk.DelayedStrike) riskOf("sharedfate:ground_strike");
		TrialTimers.Clock clock = TrialRisks.strikeClock(GRANTED, GRANTED + 1, strike);
		assertNotNull(clock);
		assertEquals(50, clock.warn(), "TrialWarning.stageFor 가 MARK 로 넘어가는 자리");
		assertEquals(TrialWarning.Stage.MARK, TrialWarning.stageFor(clock.warn()));
		assertEquals(TrialWarning.Stage.APPROACH, TrialWarning.stageFor(clock.warn() + 1));
	}

	@Test
	void 기둥_화염구의_남은_틱은_착탄이고_예고는_궤적_2초다() {
		TrialCatalog.Risk.TracedProjectile shot =
				(TrialCatalog.Risk.TracedProjectile) riskOf("sharedfate:pillar_fireball");
		for (long now = GRANTED; now <= GRANTED + shot.interval() * 3L; now++) {
			TrialTimers.Clock clock = TrialFireball.clock(GRANTED, now, shot);
			assertNotNull(clock);
			assertNextEvent("기둥 화염구", t -> TrialRisks.firesAt(t, GRANTED, shot.interval()), now,
					clock.remaining());
			assertEquals(40, clock.warn());
		}
	}

	@Test
	void 표적의_남은_틱은_다음_구체가_닿는_틱이다() {
		TrialCatalog.Risk.DragonFocus focus =
				(TrialCatalog.Risk.DragonFocus) riskOf("sharedfate:dragon_mark");
		int flight = TrialDragonFocus.flightTicks(focus.markTicks(), focus.shots());
		// 실행기가 쏘는 틱을 그대로 굴려 착탄 틱을 모은다.
		LongPredicate lands = t -> {
			long firedAt = t - flight;
			long elapsed = firedAt - GRANTED;
			if (elapsed < 0L) {
				return false;
			}
			int phase = TrialDragonFocus.phaseOf(elapsed, focus.markTicks(), focus.restTicks());
			return TrialDragonFocus.shotIndexAt(phase, focus.markTicks(), focus.shots()) >= 0;
		};
		int period = TrialDragonFocus.period(focus.markTicks(), focus.restTicks());
		for (long now = GRANTED; now <= GRANTED + period * 2L; now++) {
			TrialTimers.Clock clock = TrialDragonFocus.clock(GRANTED, now, focus);
			assertNotNull(clock);
			assertNextEvent("표적", lands, now, clock.remaining());
			assertEquals(flight, clock.warn(), "예고는 구체가 날아오는 시간");
		}
	}

	@Test
	void 엔더_파동은_고리가_출발하는_틱을_세고_퍼지는_동안은_진행_중이다() {
		TrialCatalog.Risk.EnderPulse pulse =
				(TrialCatalog.Risk.EnderPulse) riskOf("sharedfate:ender_pulse");
		LongPredicate launches = t -> {
			long elapsed = t - GRANTED;
			return elapsed >= pulse.interval() && elapsed % pulse.interval() == 0L;
		};
		for (long now = GRANTED; now <= GRANTED + pulse.interval() * 3L; now++) {
			TrialTimers.Clock clock = TrialEnderPulse.clock(GRANTED, now, pulse);
			assertNotNull(clock);
			long elapsed = now - GRANTED;
			int step = (int) (elapsed % pulse.interval());
			if (elapsed >= pulse.interval() && step < pulse.travelTicks()) {
				assertTrue(clock.active(), "고리가 퍼지는 동안 — now=" + now);
				assertEquals(pulse.travelTicks() - step, clock.remaining());
			} else {
				assertFalse(clock.active());
				assertNextEvent("엔더 파동", launches, now, clock.remaining());
			}
		}
	}

	@Test
	void 굳는_손은_칸이_옮겨_가는_틱을_센다() {
		TrialCatalog.Risk.HotbarLock lock =
				(TrialCatalog.Risk.HotbarLock) riskOf("sharedfate:hotbar_lock");
		LongPredicate moves = t -> (t - GRANTED) / lock.interval()
				!= (t - 1 - GRANTED) / lock.interval();
		for (long now = GRANTED; now <= GRANTED + lock.interval() * 2L; now++) {
			TrialTimers.Clock clock = TrialHotbarLock.clock(GRANTED, now, lock);
			assertNotNull(clock);
			assertNextEvent("굳는 손", moves, now, clock.remaining());
			assertEquals(0, clock.warn(), "바닥 예고가 없는 카드");
		}
	}

	@Test
	void 엔더폭풍은_소용돌이가_도는_동안_진행_중이고_쉬는_동안은_다음_출발을_센다() {
		TrialCatalog.Risk.EnderStorm storm =
				(TrialCatalog.Risk.EnderStorm) riskOf("sharedfate:ender_storm");
		int travel = TrialEnderStorm.travelTicks(TrialEnderStorm.blocksPerTick(storm.speedPerSecond()));
		int cycle = TrialEnderStorm.cycleTicks(travel, storm.restTicks());
		LongPredicate starts = t -> t - GRANTED > 0L && (t - GRANTED) % cycle == 0L;
		for (long now = GRANTED + 1; now <= GRANTED + cycle * 2L; now++) {
			TrialTimers.Clock clock = TrialEnderStorm.clock(GRANTED, now, storm);
			assertNotNull(clock);
			int step = (int) ((now - GRANTED) % cycle);
			if (step <= travel) {
				assertTrue(clock.active());
				assertEquals(travel + 1 - step, clock.remaining(), "step 이 travel 을 넘는 틱에 사라진다");
			} else {
				assertFalse(clock.active());
				assertNextEvent("엔더폭풍", starts, now, clock.remaining());
			}
		}
	}

	@Test
	void 수정_과충전은_도화선이면_과충전까지_볼리면_진행_중_쉼이면_쉼_더하기_도화선() {
		TrialCatalog.Risk.CrystalOvercharge risk =
				(TrialCatalog.Risk.CrystalOvercharge) riskOf("sharedfate:crystal_overcharge");
		TrialTimers.Clock fuse = TrialCrystalOvercharge.clockOf(
				TrialCrystalOvercharge.Step.FUSE, true, 100L, risk);
		assertNotNull(fuse);
		assertEquals(risk.fuseTicks() - 100, fuse.remaining());
		assertEquals(risk.fuseTicks(), fuse.warn(), "빛기둥이 도화선 내내 서 있다");
		assertFalse(fuse.active());

		assertNull(TrialCrystalOvercharge.clockOf(TrialCrystalOvercharge.Step.FUSE, false, 0L, risk),
				"달아오를 크리스탈이 없으면 시각이 안 정해진다");

		TrialTimers.Clock volley = TrialCrystalOvercharge.clockOf(
				TrialCrystalOvercharge.Step.VOLLEY, true, 50L, risk);
		assertNotNull(volley);
		assertTrue(volley.active());
		assertEquals(risk.beamTicks() - 50, volley.remaining());

		TrialTimers.Clock rest = TrialCrystalOvercharge.clockOf(
				TrialCrystalOvercharge.Step.REST, false, 150L, risk);
		assertNotNull(rest);
		assertEquals(risk.restTicks() - 150 + risk.fuseTicks(), rest.remaining());

		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:crystal_overcharge");
		assertEquals("과충전까지", TrialTimers.labelOf(trial, risk, fuse));
		assertEquals(trial.name(), TrialTimers.labelOf(trial, risk, volley));
	}

	@Test
	void 수정_보호막은_서_있는_동안만_진행_중이다() {
		TrialTimers.Clock up = TrialCrystalLink.clockOf(100L, 400L, 600);
		assertNotNull(up);
		assertTrue(up.active());
		assertEquals(300, up.remaining());
		assertNull(TrialCrystalLink.clockOf(400L, 400L, 600), "다 걷혔다");
	}

	@Test
	void 밤의_군세와_종말의_비는_끝날_때까지_진행_중이고_끝나면_사라진다() {
		TrialCatalog.Risk.NightHost host =
				(TrialCatalog.Risk.NightHost) riskOf("sharedfate:night_host");
		TrialTimers.Clock night = TrialNightHost.clock(GRANTED, GRANTED + 1, host);
		assertNotNull(night);
		assertTrue(night.active());
		assertEquals(host.hostileTicks(), night.remaining());
		assertNull(TrialNightHost.clock(GRANTED, GRANTED + host.hostileTicks() + 1, host),
				"calmAt 이 푸는 틱");
		assertNull(TrialNightHost.clock(GRANTED, GRANTED, host), "받은 그 틱에는 아직 창이 안 열렸다");

		TrialCatalog.Risk.EndRain rain = (TrialCatalog.Risk.EndRain) riskOf("sharedfate:end_rain");
		TrialTimers.Clock pour = TrialEndRain.clock(GRANTED, GRANTED + 100, rain);
		assertNotNull(pour);
		assertTrue(pour.active());
		assertEquals(rain.durationTicks() + 1 - 100, pour.remaining());
		assertNull(TrialEndRain.clock(GRANTED, GRANTED + rain.durationTicks() + 1, rain));
	}

	@Test
	void 시각이_안_정해지거나_한_번으로_끝나는_카드는_넣지_않는다() {
		for (String id : List.of("sharedfate:crystal_ward", "sharedfate:iron_cage",
				"sharedfate:crystal_revival", "sharedfate:dry_world", "sharedfate:landing_shock")) {
			assertNull(TrialTimers.clockOf(riskOf(id), GRANTED, GRANTED + 50), id);
		}
	}

	// ------------------------------------------------------------------ 일반 전투 — 기본 패시브

	@Test
	void 연쇄_포격의_남은_틱은_첫_원이_터지는_틱이고_예고는_4초다() {
		for (long now = GRANTED; now <= GRANTED + DragonFireBarrage.PERIOD_TICKS * 2L; now += 7) {
			TrialTimers.Clock clock = DragonFireBarrage.clock(GRANTED, now);
			assertNotNull(clock);
			assertNextEvent("연쇄 포격",
					t -> TrialRisks.firesAt(t, GRANTED, DragonFireBarrage.PERIOD_TICKS), now,
					clock.remaining());
			assertEquals(DragonFireBarrage.LEAD_TICKS, clock.warn());
		}
	}

	@Test
	void 연쇄_포격이_터지는_동안은_진행_중이다() {
		DragonFireBarrage.beginFight(GRANTED);
		long firedAt = GRANTED + DragonFireBarrage.PERIOD_TICKS;
		DragonFireBarrage.remember(DragonFireBarrage.planVolley(0L, 0.2, 0.5).startedAt(firedAt));
		TrialTimers.Clock clock = DragonFireBarrage.clock(GRANTED, firedAt + 10);
		assertNotNull(clock);
		assertTrue(clock.active());
		assertEquals(DragonFireBarrage.BARRAGE_TICKS - 10, clock.remaining());
	}

	/**
	 * ⚠ 예고 구간에서 판단했는데 포격을 못 놓은 주기(재시작 직후 남은 예고가 모자랐다)는 통째로 안
	 * 온다. HUD 가 주기만 세면 아무것도 안 올 「0.0초」를 띄운다.
	 */
	@Test
	void 건너뛴_주기는_다음_주기로_센다() {
		DragonFireBarrage.beginFight(GRANTED);
		long now = GRANTED + DragonFireBarrage.PERIOD_TICKS - 30;
		DragonFireBarrage.notePlanned(TrialRisks.strikeIndex(now, GRANTED,
				DragonFireBarrage.PERIOD_TICKS));
		TrialTimers.Clock clock = DragonFireBarrage.clock(GRANTED, now);
		assertNotNull(clock);
		assertEquals(30 + DragonFireBarrage.PERIOD_TICKS, clock.remaining());
	}

	// ------------------------------------------------------------------ 일반 전투 — 묶음

	@Test
	void 일반_전투는_패시브와_시각이_정해진_카드만_짧은_순으로_담는다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), GRANTED, true);
		session.choose("sharedfate:ground_strike", GRANTED);
		session.choose("sharedfate:pillar_fireball", GRANTED + 30);
		session.choose("sharedfate:crystal_ward", GRANTED + 40);
		session.choose("sharedfate:landing_shock", GRANTED + 50);
		long now = GRANTED + 100;

		TrialTimersPayload payload = TrialTimers.collect(session, false, 0.9F, now);
		assertTrue(payload.visible());
		assertFalse(payload.frozen());
		assertTrue(payload.cast().isEmpty(), "시전 바는 최후의 저항에서만");

		List<String> ids = new ArrayList<>();
		for (Entry entry : payload.timers()) {
			ids.add(entry.id());
		}
		assertEquals(List.of("sharedfate:pillar_fireball#0", "sharedfate:ground_strike#0",
				TrialTimers.BARRAGE_ID), ids, "남은 시간이 짧은 줄이 위 — 보호막·착지 충격은 빠진다");
		Entry strike = payload.timers().get(1);
		assertEquals("자리 폭격", strike.label());
		assertEquals(Kind.DAMAGE, strike.kind());
		assertEquals(Entry.MODE_COUNTDOWN, strike.mode());
		assertEquals(TrialRisks.strikeClock(GRANTED, now,
				(TrialCatalog.Risk.DelayedStrike) riskOf("sharedfate:ground_strike")).remaining(),
				strike.remainingTicks());
	}

	@Test
	void 낙뢰는_노랑_엔더_파동은_파랑_굳는_손은_보라다() {
		assertEquals(Kind.LIGHTNING, TrialTimers.kindOf(riskOf("sharedfate:lightning_storm")));
		assertEquals(Kind.SHOVE, TrialTimers.kindOf(riskOf("sharedfate:ender_pulse")));
		assertEquals(Kind.DISRUPT, TrialTimers.kindOf(riskOf("sharedfate:hotbar_lock")));
		assertEquals(Kind.DAMAGE, TrialTimers.kindOf(riskOf("sharedfate:ground_strike")));
	}

	@Test
	void 시련을_끈_팀은_HUD_를_지운다() {
		DragonTrialSession off = new DragonTrialSession(UUID.randomUUID(), GRANTED, false);
		assertFalse(TrialTimers.collect(off, false, 0.9F, GRANTED + 100).visible());
		assertFalse(TrialTimers.collect(null, false, 0.9F, GRANTED + 100).visible());
	}

	/**
	 * 룰렛이 판을 얼리면 게임 시각이 안 오른다. 카드 실행기는 그 게임 시각으로 세므로 남은 틱도
	 * 저절로 선다 — 멈춤을 따로 뺄 필요가 없고, 받는 쪽에는 {@code frozen} 만 알리면 된다.
	 *
	 * <p>⚠ 그 약속이 서려면 <b>남은 틱이 인자 {@code now} 만의 함수</b>여야 한다. 전에는 같은 인자로
	 * 두 번 불러 같은지만 봤는데, 그것은 {@code now} 를 받는 모든 함수가 참이라 실행기의 {@code clock}
	 * 하나가 서버 틱 수·벽시계처럼 <b>얼어도 흐르는 시계</b>를 읽게 바뀌어도 통과했다(2026-10-06
	 * 검토에서 확정된 문제). 이제 {@code now} 를 옮겨 읽어 <b>옮긴 만큼만</b> 줄고, 다른 {@code now} 를
	 * 읽은 뒤 돌아와도 처음 값 그대로인지(읽기가 상태를 굴리지 않는다) 본다.
	 */
	@Test
	void 남은_틱은_now_만의_함수라_시각이_서면_함께_서고_frozen_이_실린다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), GRANTED, true);
		session.choose("sharedfate:ground_strike", GRANTED);
		long frozenAt = GRANTED + 77;
		int shift = 3;
		TrialTimersPayload first = TrialTimers.collect(session, true, 0.9F, frozenAt);
		TrialTimersPayload moved = TrialTimers.collect(session, true, 0.9F, frozenAt + shift);
		TrialTimersPayload back = TrialTimers.collect(session, true, 0.9F, frozenAt);
		assertTrue(first.frozen());

		// 시각을 옮기면 옮긴 만큼만 준다 — now 가 아닌 시계를 읽는 줄이 있으면 여기서 어긋난다.
		assertEquals(first.timers().size(), moved.timers().size());
		int checked = 0;
		for (Entry before : first.timers()) {
			Entry after = moved.timers().stream()
					.filter(entry -> entry.id().equals(before.id())).findFirst().orElseThrow();
			if (before.remainingTicks() > shift) {
				assertEquals(before.remainingTicks() - shift, after.remainingTicks(),
						before.id() + " — now 를 " + shift + "틱 옮겼는데 남은 틱이 그만큼 안 줄었다");
				checked++;
			}
		}
		assertTrue(checked >= 2, "패시브와 카드 줄을 둘 다 재야 한다 — 잰 줄이 " + checked + "개");

		// 다른 시각을 읽은 뒤 돌아와도 같다 — 읽기가 실행기 상태를 굴리지 않는다. 그래서 시각이 서
		// 있는 동안 몇 번을 읽어도(매 서버 틱 HUD 를 모은다) 같은 묶음이다.
		assertEquals(first.timers(), back.timers(),
				"같은 now 로 돌아왔는데 남은 틱이 다르다 — 얼어 있는 동안 숫자가 흐른다");

		assertFalse(TrialTimers.needsSend(first, frozenAt, 100, back, frozenAt, 110),
				"얼어 있는 동안 상태가 안 바뀌면 박동 전에는 안 보낸다");
		assertTrue(TrialTimers.needsSend(first, frozenAt, 100,
				TrialTimers.collect(session, false, 0.9F, frozenAt), frozenAt, 101),
				"멈춤이 끝나는 틱에 곧장 보낸다");
	}

	// ------------------------------------------------------------------ 최후의 저항 — 시전 바

	private static DragonLastStand.View view(boolean shielded, long nextPatternAt, long restBegan,
			DragonLastStand.Pattern running, long runningUntil) {
		return new DragonLastStand.View(shielded, 1000L, 1160L, nextPatternAt, restBegan, running,
				runningUntil);
	}

	@Test
	void 진입_보호막_구간은_첫_패턴까지_남은_시간이다() {
		Cast cast = TrialTimers.castOf(view(true, 1220L, 1000L, null, 0L), 1050L).orElseThrow();
		assertEquals(TrialTimers.SHIELD_TITLE, cast.title());
		assertEquals(Cast.STATE_IDLE, cast.state());
		assertEquals(170, cast.remainingTicks());
		assertEquals(220, cast.totalTicks());
		assertEquals(Kind.NEUTRAL, cast.kind());
	}

	@Test
	void 쉬는_중은_다음_패턴_무작위와_남은_쉬는_시간이다() {
		Cast cast = TrialTimers.castOf(view(false, 2060L, 2000L, null, 0L), 2010L).orElseThrow();
		assertEquals(TrialTimers.REST_TITLE, cast.title());
		assertEquals(Cast.STATE_IDLE, cast.state());
		assertEquals(50, cast.remainingTicks());
		assertEquals(60, cast.totalTicks());
		assertEquals("", cast.hint());
	}

	@Test
	void 패턴을_고르면_예고_뒤에_진행이고_예고_길이는_패턴_상수다() {
		assertEquals(80, TrialTimers.warnTicksOf(DragonLastStand.Pattern.CONE_BREATH));
		assertEquals(40, TrialTimers.warnTicksOf(DragonLastStand.Pattern.VOID_SUCTION));
		assertEquals(60, TrialTimers.warnTicksOf(DragonLastStand.Pattern.CROSS_FISSURE));
		assertEquals(0, TrialTimers.warnTicksOf(DragonLastStand.Pattern.WING_BEAT));

		DragonLastStand.Pattern breath = DragonLastStand.Pattern.CONE_BREATH;
		long picked = 3000L;
		long until = picked + breath.durationTicks();
		Cast warning = TrialTimers.castOf(view(false, 0L, 0L, breath, until), picked + 30)
				.orElseThrow();
		assertEquals(Cast.STATE_WARNING, warning.state());
		assertEquals("부채꼴 브레스", warning.title());
		assertEquals(50, warning.remainingTicks(), "발동까지");
		assertEquals(80, warning.totalTicks());
		assertEquals("머리 방향 90° · 한 대에 전멸 — 옆으로", warning.hint());
		assertEquals(Kind.DAMAGE, warning.kind());

		Cast running = TrialTimers.castOf(view(false, 0L, 0L, breath, until), picked + 85)
				.orElseThrow();
		assertEquals(Cast.STATE_RUNNING, running.state());
		assertEquals("부채꼴 브레스 · 진행 중", running.title());
		assertEquals(breath.durationTicks() - 85, running.remainingTicks());
		assertEquals(breath.durationTicks() - 80, running.totalTicks());
	}

	@Test
	void 날개는_예고가_없어_고른_틱부터_진행이다() {
		DragonLastStand.Pattern wing = DragonLastStand.Pattern.WING_BEAT;
		Cast cast = TrialTimers.castOf(view(false, 0L, 0L, wing, 500L + wing.durationTicks()), 500L)
				.orElseThrow();
		assertEquals(Cast.STATE_RUNNING, cast.state());
		assertEquals(Kind.SHOVE, cast.kind());
		assertEquals("0.6초마다 넉백 8번 · 피해 없음", cast.hint());
	}

	@Test
	void 흡입과_십자의_대처법은_승인된_문구다() {
		assertEquals("반대로 달리기 · 불 결계 안은 계속 아픔",
				TrialTimers.hintOf(DragonLastStand.Pattern.VOID_SUCTION));
		assertEquals("빨간 바닥 사이 틈으로 · 두 번째 십자 대비",
				TrialTimers.hintOf(DragonLastStand.Pattern.CROSS_FISSURE));
		assertEquals(Kind.DISRUPT, TrialTimers.kindOf(DragonLastStand.Pattern.VOID_SUCTION));
	}

	// ------------------------------------------------------------------ 최후의 저항 — 오른쪽 위

	@Test
	void 안전지대는_축소_시작까지_세고_줄어드는_동안은_진행_중이고_끝나면_사라진다() {
		long base = 5000L;
		for (long elapsed = -160L; elapsed <= DragonLastStandZone.LEG_END_TICKS[2] + 5; elapsed++) {
			long now = base + elapsed;
			TrialTimers.ZoneTimer zone = DragonLastStandZone.timer(base, now);
			double radius = DragonLastStandZone.radiusAt(elapsed);
			double nextRadius = DragonLastStandZone.radiusAt(elapsed + 1);
			if (elapsed >= DragonLastStandZone.LEG_END_TICKS[2]) {
				assertNull(zone, "축소가 다 끝났다");
				continue;
			}
			assertNotNull(zone);
			if (zone.shrinking()) {
				assertTrue(nextRadius < radius, "줄어드는 중 — elapsed=" + elapsed);
				// 다 줄어드는 틱이 실제로 remaining 뒤다 — 그 틱의 반경이 목표 반경이다.
				long ends = elapsed + zone.remaining();
				assertEquals(zone.toRadius(), DragonLastStandZone.radiusAt(ends), 1.0E-9);
				assertTrue(DragonLastStandZone.radiusAt(ends - 1) > zone.toRadius());
			} else {
				// 축소가 시작되는 틱이 실제로 remaining 뒤다 — 그 틱부터 반경이 줄기 시작한다.
				long begins = elapsed + zone.remaining();
				assertEquals(radius, DragonLastStandZone.radiusAt(begins), 1.0E-9);
				assertTrue(DragonLastStandZone.radiusAt(begins + 1) < radius);
				assertTrue(DragonLastStandZone.radiusAt(begins - 1) == radius);
			}
		}
		TrialTimers.ZoneTimer first = DragonLastStandZone.timer(base, base);
		assertNotNull(first);
		assertEquals(32.0, first.toRadius(), 1.0E-9);
		List<Entry> entries = TrialTimers.lastStandTimers(first, null, null, Float.NaN);
		assertEquals("안전지대 → 32칸", entries.get(0).label());
		assertEquals(Kind.ZONE, entries.get(0).kind());
	}

	@Test
	void 상시_번개는_열린_볼리면_내리칠_시각_아니면_다음_볼리_더하기_예고다() {
		long base = 8000L;
		int warn = DragonLastStandPatterns.LIGHTNING_WARN_TICKS;
		TrialTimers.Clock before = DragonLastStandPatterns.lightningClockOf(Long.MIN_VALUE, 0L,
				Long.MIN_VALUE, base, base - 100);
		assertEquals(100 + DragonLastStand.ENTRY_GRACE_TICKS + warn, before.remaining(),
				"진입 연출 중 — 첫 볼리는 시계 원점 + 무적 3초");
		TrialTimers.Clock open = DragonLastStandPatterns.lightningClockOf(base, base + 500,
				base + 400, base, base + 380);
		assertEquals(20, open.remaining());
		assertEquals(warn, open.warn());
		TrialTimers.Clock idle = DragonLastStandPatterns.lightningClockOf(base, base + 500,
				Long.MIN_VALUE, base, base + 450);
		assertEquals(50 + warn, idle.remaining());
	}

	@Test
	void 오브젝트_파도는_체력_25_다음_10_다음_없음() {
		Entry first = TrialTimers.objectsEntry(new TrialTimers.ObjectsView(0, false, 0, 0), 0.40F);
		assertNotNull(first);
		assertEquals(Entry.MODE_HEALTH, first.mode());
		assertEquals("체력 25%", first.valueText());
		assertEquals(0.6F, first.fill(), 1.0E-4F, "50% → 25% 사이에서 40% 는 남은 거리 0.6");
		assertEquals(Kind.HEAL, first.kind());

		Entry second = TrialTimers.objectsEntry(new TrialTimers.ObjectsView(1, false, 0, 0), 0.20F);
		assertNotNull(second);
		assertEquals("체력 10%", second.valueText());
		assertEquals(10.0F / 15.0F, second.fill(), 1.0E-4F);

		assertNull(TrialTimers.objectsEntry(new TrialTimers.ObjectsView(2, false, 0, 0), 0.05F),
				"문턱을 다 썼다");

		Entry running = TrialTimers.objectsEntry(new TrialTimers.ObjectsView(1, true, 3, 6), 0.24F);
		assertNotNull(running);
		assertEquals("진행 중 · 3개", running.valueText());
		assertEquals(0.5F, running.fill(), 1.0E-6F);
	}

	@Test
	void 최후의_저항_줄은_시간형이_위_체력형이_맨_아래다() {
		List<Entry> entries = TrialTimers.lastStandTimers(
				new TrialTimers.ZoneTimer(true, 40L, 100L, 22.0),
				TrialTimers.Clock.countdown(90L, 156L, 60),
				new TrialTimers.ObjectsView(0, false, 0, 0), 0.45F);
		assertEquals(List.of(TrialTimers.ZONE_ID, TrialTimers.LIGHTNING_ID, TrialTimers.OBJECTS_ID),
				entries.stream().map(Entry::id).toList());
		assertEquals("안전지대 축소 중 → 22칸", entries.get(0).label());
		assertEquals(Entry.MODE_ACTIVE, entries.get(0).mode());
	}

	// ------------------------------------------------------------------ 언제 보내는가

	private static TrialTimersPayload payloadWith(int remaining, boolean frozen) {
		return new TrialTimersPayload(true, frozen,
				List.of(Entry.countdown("a", "자리 폭격", Kind.DAMAGE, remaining, 240, 50)),
				Optional.empty());
	}

	@Test
	void 예측대로_줄어들면_박동까지_안_보내고_튀면_곧장_보낸다() {
		TrialTimersPayload sent = payloadWith(200, false);
		assertFalse(TrialTimers.needsSend(sent, 1000L, 50, payloadWith(195, false), 1005L, 55));
		assertTrue(TrialTimers.needsSend(sent, 1000L, 50, payloadWith(195, false), 1005L,
				50 + TrialTimers.HEARTBEAT_TICKS), "1초 박동");
		assertTrue(TrialTimers.needsSend(sent, 1000L, 50, payloadWith(240, false), 1005L, 55),
				"발동해서 다음 주기로 튀었다");
		assertTrue(TrialTimers.needsSend(sent, 1000L, 50, payloadWith(195, true), 1005L, 55),
				"멈춤이 시작됐다");
		TrialTimersPayload withCard = new TrialTimersPayload(true, false, List.of(
				Entry.countdown("a", "자리 폭격", Kind.DAMAGE, 195, 240, 50),
				Entry.countdown("b", "낙뢰", Kind.LIGHTNING, 100, 120, 50)), Optional.empty());
		assertTrue(TrialTimers.needsSend(sent, 1000L, 50, withCard, 1005L, 55), "카드가 늘었다");
	}

	@Test
	void 시전_바의_상태가_바뀌면_곧장_보내고_체력형_바는_박동이_싣는다() {
		Cast rest = new Cast(TrialTimers.REST_TITLE, Kind.NEUTRAL, Cast.STATE_IDLE, 10, 60, "");
		Cast warn = new Cast("공허 흡입", Kind.DISRUPT, Cast.STATE_WARNING, 40, 40, "x");
		TrialTimersPayload a = new TrialTimersPayload(true, false, List.of(), Optional.of(rest));
		TrialTimersPayload b = new TrialTimersPayload(true, false, List.of(), Optional.of(warn));
		assertTrue(TrialTimers.needsSend(a, 0L, 0, b, 3L, 3), "패턴을 골랐다");

		TrialTimersPayload hp1 = new TrialTimersPayload(true, false,
				List.of(Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.8F)),
				Optional.empty());
		TrialTimersPayload hp2 = new TrialTimersPayload(true, false,
				List.of(Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.7F)),
				Optional.empty());
		assertFalse(TrialTimers.needsSend(hp1, 0L, 0, hp2, 3L, 3),
				"맞을 때마다 보내지 않는다");
		TrialTimersPayload hp3 = new TrialTimersPayload(true, false,
				List.of(Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 10%", 1.0F)),
				Optional.empty());
		assertTrue(TrialTimers.needsSend(hp1, 0L, 0, hp3, 3L, 3), "문턱이 바뀌었다");
	}
}
