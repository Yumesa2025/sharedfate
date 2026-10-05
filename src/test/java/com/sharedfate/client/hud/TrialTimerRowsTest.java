package com.sharedfate.client.hud;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.hud.TrialTimerRows.Row;
import com.sharedfate.client.hud.TrialTimerRows.RowText;
import com.sharedfate.client.hud.TrialTimerRows.State;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>글자와 판정</b> — 차례 · 상태 · 초 글자 · 바 비율 · 자르기.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」 — 예시를 보고 「이대로 진행」.
 * 여기 못박는 값은 그 승인된 화면의 규칙이다(임박 ≤ 100틱 · 예고 중 = 바닥 표식 · 짧은 순 ·
 * 체력형 맨 아래 · 「12.3초」 · 예고 중 시전 바는 차오름).
 */
class TrialTimerRowsTest {

	/** 가짜 글꼴 — 한 글자 6. 실제 폭이 아니라 자르는 규칙만 본다. */
	private static final ToIntFunction<String> SIX = text -> text.length() * 6;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static Entry countdown(String id, int remaining, int warn) {
		return Entry.countdown(id, id, Kind.DAMAGE, remaining, 400, warn);
	}

	// ------------------------------------------------------------------ 차례

	@Test
	void 남은_시간이_짧은_줄이_위_체력형은_맨_아래() {
		List<Entry> entries = List.of(
				Entry.health("objects", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.6F),
				countdown("far", 300, 0),
				Entry.active("zone", "안전지대 축소 중", Kind.ZONE, 40, 100),
				countdown("near", 60, 0));
		List<String> order = TrialTimerRows.rows(entries, 0).stream()
				.map(row -> row.entry().id()).toList();
		assertEquals(List.of("zone", "near", "far", "objects"), order);
	}

	@Test
	void 줄여_가며_다시_세우고_영에_닿은_줄끼리는_열쇠_순이다() {
		// 받은 차례는 b(5) → a(10). 20틱이 흐르면 둘 다 0 — 서버와 같은 열쇠 순으로 선다.
		List<Entry> entries = List.of(countdown("b", 5, 0), countdown("a", 10, 0),
				countdown("c", 300, 0));
		List<Row> rows = TrialTimerRows.rows(entries, 20);
		assertEquals(List.of("a", "b", "c"), rows.stream().map(row -> row.entry().id()).toList());
		assertEquals(0, rows.get(0).remaining());
		assertEquals(280, rows.get(2).remaining());
	}

	// ------------------------------------------------------------------ 상태

	@Test
	void 상태는_대기_임박_예고_중_진행_중_체력형() {
		assertEquals(State.WAITING, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 101, 50));
		assertEquals(State.IMMINENT, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 100, 50),
				"승인된 문턱: 남은 ≤ 100틱");
		assertEquals(State.IMMINENT, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 51, 50));
		assertEquals(State.WARNING, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 50, 50),
				"남은 ≤ warnTicks 면 바닥 표식이 깔린 것");
		assertEquals(State.WARNING, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 0, 50));
		assertEquals(State.ACTIVE, TrialTimerRows.stateOf(Entry.MODE_ACTIVE, 300, 0));
		assertEquals(State.HEALTH, TrialTimerRows.stateOf(Entry.MODE_HEALTH, 0, 0));
	}

	@Test
	void 예고가_임박보다_길어도_예고_중이다() {
		// 기둥 화염구처럼 예고(궤적)가 5초보다 길면 임박을 건너뛰고 곧장 예고 중이다.
		assertEquals(State.WARNING, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 140, 160));
	}

	@Test
	void 바닥_예고가_없으면_예고_중이_되지_않는다() {
		assertEquals(State.IMMINENT, TrialTimerRows.stateOf(Entry.MODE_COUNTDOWN, 0, 0));
	}

	@Test
	void 모르는_모양은_시간형으로_본다() {
		assertEquals(State.WAITING, TrialTimerRows.stateOf((byte) 9, 300, 0));
	}

	@Test
	void 꼬리표는_임박_예고_중_진행_중만() {
		assertEquals("임박", TrialTimerRows.tagOf(State.IMMINENT));
		assertEquals("예고 중", TrialTimerRows.tagOf(State.WARNING));
		assertEquals("진행 중", TrialTimerRows.tagOf(State.ACTIVE));
		assertNull(TrialTimerRows.tagOf(State.WAITING));
		assertNull(TrialTimerRows.tagOf(State.HEALTH));
	}

	// ------------------------------------------------------------------ 글자

	@Test
	void 초_글자는_소수_한_자리_올림이다() {
		assertEquals("12.3초", TrialTimerRows.secondsText(246));
		assertEquals("12.3초", TrialTimerRows.secondsText(245), "12.25 → 올려서 12.3");
		assertEquals("0.1초", TrialTimerRows.secondsText(1), "1틱 남은 것이 「0.0초」로 뜨면 지난 것으로 읽힌다");
		assertEquals("0.0초", TrialTimerRows.secondsText(0));
		assertEquals("60.0초", TrialTimerRows.secondsText(1200));
		assertEquals("4.5초", TrialTimerRows.secondsText(89.5F), "보간된 값");
		assertEquals("0.0초", TrialTimerRows.secondsText(-3), "음수는 0");
	}

	@Test
	void 체력형은_초_대신_서버_글자() {
		Row row = TrialTimerRows.rows(List.of(
				Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.6F)), 0).get(0);
		assertEquals("체력 25%", TrialTimerRows.rightText(row, 0.0F));
		Row time = TrialTimerRows.rows(List.of(countdown("a", 246, 0)), 0).get(0);
		assertEquals("12.3초", TrialTimerRows.rightText(time, time.remaining()));
	}

	@Test
	void 부분_틱으로_매끄럽게_줄되_멈춘_시계는_빼지_않는다() {
		assertEquals(99.25F, TrialTimerRows.interpolated(100, 0.75F, true), 1.0E-6F);
		assertEquals(100.0F, TrialTimerRows.interpolated(100, 0.75F, false), 1.0E-6F,
				"얼었거나 일시정지면 숫자가 한 틱 덜 남은 채 서 있지 않게");
		assertEquals(0.0F, TrialTimerRows.interpolated(0, 0.75F, true), 1.0E-6F);
	}

	@Test
	void 머리줄은_일반_전투_줄_수_최후의_저항_지금_체력() {
		assertEquals("다가오는 패턴", TrialTimerRows.header(false));
		assertEquals("다가오는 것", TrialTimerRows.header(true));
		assertEquals("4개", TrialTimerRows.headerNote(false, 4, Float.NaN));
		assertEquals("지금 체력 27%", TrialTimerRows.headerNote(true, 3, 0.27F));
		assertEquals("지금 체력 26%", TrialTimerRows.headerNote(true, 3, 0.2504F),
				"올림 — 25% 가 뜨는 때가 곧 오브젝트 파도 문턱");
		assertEquals("지금 체력 25%", TrialTimerRows.headerNote(true, 3, 0.25F));
		assertEquals("", TrialTimerRows.headerNote(true, 3, Float.NaN), "드래곤이 안 보이면 비운다");
	}

	// ------------------------------------------------------------------ 바

	@Test
	void 줄_바는_남은_비율로_줄고_체력형은_fill() {
		Row row = TrialTimerRows.rows(List.of(countdown("a", 100, 0)), 0).get(0);
		assertEquals(0.25F, TrialTimerRows.rowRatio(row, 100.0F), 1.0E-6F);
		assertEquals(0.0F, TrialTimerRows.rowRatio(row, 0.0F), 1.0E-6F);
		Row health = TrialTimerRows.rows(List.of(
				Entry.health("o", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.6F)), 0).get(0);
		assertEquals(0.6F, TrialTimerRows.rowRatio(health, 0.0F), 1.0E-6F, "1 이면 문턱까지 멀다");
		assertEquals(0.0F, TrialTimerRows.countdownRatio(5.0F, 0), "분모 0");
	}

	@Test
	void 시전_바는_예고_중이면_차오르고_쉬는_중_진행_중이면_준다() {
		assertEquals(0.25F, TrialTimerRows.castRatio(Cast.STATE_WARNING, 60.0F, 80), 1.0E-6F,
				"경과 20 / 전체 80");
		assertEquals(1.0F, TrialTimerRows.castRatio(Cast.STATE_WARNING, 0.0F, 80), 1.0E-6F);
		assertEquals(0.75F, TrialTimerRows.castRatio(Cast.STATE_RUNNING, 60.0F, 80), 1.0E-6F);
		assertEquals(0.75F, TrialTimerRows.castRatio(Cast.STATE_IDLE, 60.0F, 80), 1.0E-6F);
		assertEquals(0.0F, TrialTimerRows.castRatio(Cast.STATE_WARNING, 0.0F, 0));
	}

	@Test
	void 시전_바_글자() {
		Cast warning = new Cast("부채꼴 브레스", Kind.DAMAGE, Cast.STATE_WARNING, 30, 80, "");
		assertEquals("부채꼴 브레스", TrialTimerRows.castTitle(warning));
		assertEquals("1.5초 뒤 발동", TrialTimerRows.castTimeText(Cast.STATE_WARNING, 30));
		assertEquals("1.5초", TrialTimerRows.castTimeText(Cast.STATE_RUNNING, 30));
		assertEquals("1.5초", TrialTimerRows.castTimeText(Cast.STATE_IDLE, 30));

		Cast idle = new Cast("다음 패턴 · 무작위", Kind.NEUTRAL, Cast.STATE_IDLE, 30, 60, "");
		assertEquals("다음 패턴 · 무작위", TrialTimerRows.castTitle(idle));
	}

	@Test
	void 진행_중_제목에_진행_중을_두_번_붙이지_않는다() {
		Cast fromServer = new Cast("공허 흡입 · 진행 중", Kind.DISRUPT, Cast.STATE_RUNNING, 30, 100, "");
		assertEquals("공허 흡입 · 진행 중", TrialTimerRows.castTitle(fromServer));
		Cast bare = new Cast("공허 흡입", Kind.DISRUPT, Cast.STATE_RUNNING, 30, 100, "");
		assertEquals("공허 흡입 · 진행 중", TrialTimerRows.castTitle(bare));
	}

	// ------------------------------------------------------------------ 자르기

	@Test
	void 넘치면_말줄임표로_자르고_안_들어가면_비운다() {
		assertEquals("안전지대", TrialTimerRows.fit("안전지대", 24, SIX));
		assertEquals("안전…", TrialTimerRows.fit("안전지대 → 32칸", 18, SIX));
		assertEquals("…", TrialTimerRows.fit("안전지대", 6, SIX));
		assertEquals("", TrialTimerRows.fit("안전지대", 5, SIX));
		assertEquals("", TrialTimerRows.fit("안전지대", 0, SIX));
	}

	@Test
	void 자를_때_끝_공백은_떼고_말줄임표를_붙인다() {
		assertEquals("상시…", TrialTimerRows.fit("상시 번개", 24, SIX));
	}

	@Test
	void 넓으면_이름과_꼬리표를_다_넣는다() {
		RowText text = TrialTimerRows.rowText("상시 번개", "임박", "3.1초", 140, SIX);
		assertEquals("상시 번개", text.label());
		assertEquals("임박", text.tag());
	}

	@Test
	void 좁으면_꼬리표를_빼고_이름을_지킨다() {
		// 남는 폭 = 72 - 시간 24 - 틈 4 = 44. 꼬리표(12 + 4 + 3 = 19)를 넣으면 이름에 25 — 27 보다 좁다.
		RowText text = TrialTimerRows.rowText("안전지대 → 32칸", "예고 중", "8.2초", 72, SIX);
		assertNull(text.tag(), "상태는 줄 배경 · 테두리가 함께 말한다");
		assertEquals("안전지대 →…", text.label(), "꼬리표를 뺀 44 에 맞춰 자른다");
		assertTrue(SIX.applyAsInt(text.label()) <= 44);
	}

	@Test
	void 이름이_짧으면_좁아도_꼬리표를_남긴다() {
		RowText text = TrialTimerRows.rowText("번개", "임박", "3.1초", 60, SIX);
		assertEquals("번개", text.label());
		assertEquals("임박", text.tag());
	}
}
