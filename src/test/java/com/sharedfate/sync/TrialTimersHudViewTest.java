package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.hud.TrialTimerRows;
import com.sharedfate.client.hud.TrialTimerRows.Row;
import com.sharedfate.client.hud.TrialTimerRows.State;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 드래곤 패턴 타이머 HUD — <b>서버가 담은 것을 받는 쪽이 사람 화면대로 읽는가</b>.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」.
 *
 * <p>서버 쪽({@link TrialTimers})과 받는 쪽({@link TrialTimerRows})을 다른 날 다른 사람이 만들었다.
 * 둘이 어긋나는 자리는 조용하다 — 「진행 중」이 두 번 붙거나, 체력형 줄이 「0.0초」로 뜨거나, 예고
 * 중 시전 바가 줄어드는 쪽으로 그려진다. 여기서 <b>서버가 실제로 만든 묶음</b>을 받는 쪽 판정에
 * 그대로 넣어 본다.
 */
class TrialTimersHudViewTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static DragonLastStand.View running(DragonLastStand.Pattern pattern, long picked) {
		return new DragonLastStand.View(false, 1000L, 1160L, 0L, 0L, pattern,
				picked + pattern.durationTicks());
	}

	@Test
	void 진행_중_시전_바_제목에_진행_중이_한_번만_붙는다() {
		DragonLastStand.Pattern breath = DragonLastStand.Pattern.CONE_BREATH;
		Cast cast = TrialTimers.castOf(running(breath, 3000L), 3085L).orElseThrow();
		assertEquals(Cast.STATE_RUNNING, cast.state());
		assertEquals("부채꼴 브레스 · 진행 중", TrialTimerRows.castTitle(cast));
	}

	@Test
	void 예고_중_시전_바는_차오르고_N초_뒤_발동이다() {
		DragonLastStand.Pattern breath = DragonLastStand.Pattern.CONE_BREATH;
		Cast cast = TrialTimers.castOf(running(breath, 3000L), 3030L).orElseThrow();
		assertEquals(Cast.STATE_WARNING, cast.state());
		assertEquals("부채꼴 브레스", TrialTimerRows.castTitle(cast));
		assertEquals("2.5초 뒤 발동", TrialTimerRows.castTimeText(cast.state(), cast.remainingTicks()));
		assertEquals(30.0F / 80.0F,
				TrialTimerRows.castRatio(cast.state(), cast.remainingTicks(), cast.totalTicks()),
				1.0E-6F, "고른 뒤 30틱 / 예고 80틱");
	}

	@Test
	void 쉬는_중_시전_바는_제목_그대로_줄어들고_대처법이_없다() {
		Cast cast = TrialTimers.castOf(
				new DragonLastStand.View(false, 1000L, 1160L, 2060L, 2000L, null, 0L), 2010L)
				.orElseThrow();
		assertEquals("다음 패턴 · 무작위", TrialTimerRows.castTitle(cast));
		assertEquals("2.5초", TrialTimerRows.castTimeText(cast.state(), cast.remainingTicks()));
		assertEquals(50.0F / 60.0F,
				TrialTimerRows.castRatio(cast.state(), cast.remainingTicks(), cast.totalTicks()),
				1.0E-6F);
		assertEquals("", cast.hint(), "대처법 줄을 안 그린다");
	}

	@Test
	void 최후의_저항_줄은_진행_중_임박_체력형으로_읽히고_체력형이_맨_아래다() {
		List<Entry> entries = TrialTimers.lastStandTimers(
				new TrialTimers.ZoneTimer(true, 40L, 100L, 22.0),
				TrialTimers.Clock.countdown(90L, 156L, 60),
				new TrialTimers.ObjectsView(0, false, 0, 0), 0.40F);
		List<Row> rows = TrialTimerRows.rows(entries, 0);
		assertEquals(List.of(State.ACTIVE, State.IMMINENT, State.HEALTH),
				rows.stream().map(Row::state).toList());
		assertEquals("진행 중", TrialTimerRows.tagOf(rows.get(0).state()));
		assertEquals("체력 25%", TrialTimerRows.rightText(rows.get(2), 0.0F), "초가 아니라 서버 글자");
		assertEquals(0.6F, TrialTimerRows.rowRatio(rows.get(2), 0.0F), 1.0E-4F);

		// 30틱 흐르면 번개(예고 60)는 남은 60 — 바닥 표식이 깔린 「예고 중」.
		List<Row> later = TrialTimerRows.rows(entries, 30);
		assertEquals(State.WARNING, later.get(1).state());
		assertEquals(TrialTimers.LIGHTNING_ID, later.get(1).entry().id());
	}

	@Test
	void 안전지대_대기는_바닥_예고가_없어_예고_중이_되지_않는다() {
		List<Entry> entries = TrialTimers.lastStandTimers(
				new TrialTimers.ZoneTimer(false, 30L, 300L, 24.0), null, null, Float.NaN);
		Row zone = TrialTimerRows.rows(entries, 0).get(0);
		assertEquals(State.IMMINENT, zone.state());
		assertFalse(TrialTimerRows.rows(entries, 30).get(0).state() == State.WARNING);
	}

	@Test
	void 오브젝트_파도가_서_있는_동안도_체력형이다() {
		Entry wave = TrialTimers.objectsEntry(new TrialTimers.ObjectsView(1, true, 3, 6), 0.24F);
		Row row = TrialTimerRows.rows(List.of(wave), 100).get(0);
		assertEquals(State.HEALTH, row.state());
		assertEquals("진행 중 · 3개", TrialTimerRows.rightText(row, row.remaining()));
		assertEquals(0.5F, TrialTimerRows.rowRatio(row, row.remaining()), 1.0E-6F);
	}
}
