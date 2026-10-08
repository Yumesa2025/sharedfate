package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 와 팀 화면이 함께 쓰는 세트 줄 계산.
 *
 * <p>그리는 부분은 살아 있는 클라이언트가 있어야 하지만, <b>몇 줄을 어떤 글자로 어느 차례로
 * 그리는가</b>는 순수 함수라 여기서 확인한다. 이 시험이 지키는 것은 셋이다 — 한 개도 없는
 * 유형이 화면을 덮지 않을 것, 켜진 세트가 맨 위에 있을 것, 줄이 없으면 자리를 아예 차지하지
 * 않을 것.
 */
class PerkSetLinesTest {

	private static PerkSetLines.Entry entry(String id, String name, int owned, int next,
			int tier) {
		return new PerkSetLines.Entry(id, name, owned, next, tier);
	}

	// ------------------------------------------------------------------ 글자

	@Test
	void 켜진_세트는_찬_마름모_진행중은_빈_마름모다() {
		assertEquals("◆ 채굴 3/4", PerkSetLines.label(entry("mining", "채굴", 3, 4, 3)));
		assertEquals("◇ 방어 1/2", PerkSetLines.label(entry("defense", "방어", 1, 2, 0)));
	}

	/**
	 * 마지막 단계에 꼭 맞게 모았으면 「3/3」이다.
	 *
	 * <p>분모가 가진 개수와 같아 보이지만 <b>가진 개수를 옮겨 적은 것이 아니라</b> 마지막 단계의
	 * 개수다. 다음 시험이 그 둘을 가른다.
	 */
	@Test
	void 마지막_단계에_꼭_맞으면_분모도_그_단계다() {
		assertEquals("◆ 채굴 3/3", PerkSetLines.label(entry("mining", "채굴", 3, 0, 3)));
	}

	/**
	 * <b>단계를 전부 켠 뒤에 더 모아도 분모는 안 따라 올라간다.</b>
	 *
	 * <p>방어는 단계가 2·3 둘뿐이라 넷째를 모아도 켜질 것이 없다. 여기서 「방어 4/4」가 뜨면 4
	 * 단계가 있는 것으로 읽히는데, 툴팁을 열면 2·3 두 줄뿐이라 화면끼리 어긋난다. 분자가 분모보다
	 * 큰 것이 그대로 <b>더 모았지만 더 켤 것은 없다</b>는 뜻이다.
	 */
	@Test
	void 전부_켠_뒤_더_모아도_없는_단계를_안_가리킨다() {
		// 방어 — 단계가 2·3 인데 넷을 모았다.
		assertEquals("◆ 방어 4/3", PerkSetLines.label(entry("defense", "방어", 4, 0, 3)));
		// 채굴 — 단계가 2·3·4 인데 열을 모았다.
		assertEquals("◆ 채굴 10/4", PerkSetLines.label(entry("mining", "채굴", 10, 0, 4)));
	}

	/** 아직 오를 곳이 있으면 예전 그대로다. 이 고침이 건드리는 것은 다 켠 뒤뿐이다. */
	@Test
	void 오를_곳이_남았으면_분모는_다음_단계다() {
		assertEquals("◇ 방어 1/2", PerkSetLines.label(entry("defense", "방어", 1, 2, 0)));
		assertEquals("◆ 방어 2/3", PerkSetLines.label(entry("defense", "방어", 2, 3, 2)));
		assertEquals("◆ 채굴 3/4", PerkSetLines.label(entry("mining", "채굴", 3, 4, 3)));
	}

	/**
	 * 단계가 하나뿐인 기동.
	 *
	 * <p>기동은 3 단계 하나뿐이다. 둘까지는 아무것도 안 켜진 채 3 을 가리키고, 셋에서 켜지고,
	 * 그 뒤로는 모은 만큼 분자만 올라간다.
	 */
	@Test
	void 단계가_하나뿐인_유형도_없는_단계를_안_가리킨다() {
		assertEquals("◇ 기동 2/3", PerkSetLines.label(entry("mobility", "기동", 2, 3, 0)));
		assertEquals("◆ 기동 3/3", PerkSetLines.label(entry("mobility", "기동", 3, 0, 3)));
		assertEquals("◆ 기동 5/3", PerkSetLines.label(entry("mobility", "기동", 5, 0, 3)));
	}

	/**
	 * 단계가 하나도 정의되지 않은 유형은 분수를 아예 안 적는다.
	 *
	 * <p>정의 파일에서 한 유형의 단계를 통째로 지운 서버에서 임계값까지 채운 자리다. 가리킬 단계가
	 * 하나도 없으므로 개수만 적는다 — 없는 숫자를 지어내는 것보다 낫다.
	 */
	@Test
	void 가리킬_단계가_없으면_분수를_안_적는다() {
		assertEquals("◇ 무기 3", PerkSetLines.label(entry("weapon", "무기", 3, 0, 0)));
	}

	// ------------------------------------------------------------------ 고르기와 차례

	@Test
	void 한_개도_없는_유형은_빠진다() {
		// 유형이 열네 개다. 전부 그리면 「기동 0/2」 같은 줄이 화면 왼쪽 위를 통째로 덮는다.
		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				entry("mining", "채굴", 2, 3, 0),
				entry("mobility", "기동", 0, 2, 0),
				entry("swap", "교환", 0, 2, 0)), 10);

		assertEquals(1, lines.size());
		assertEquals("◇ 채굴 2/3", lines.getFirst().text());
	}

	@Test
	void 켜진_세트가_맨_위로_온다() {
		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				entry("mining", "채굴", 2, 3, 0),
				entry("defense", "방어", 2, 3, 2)), 10);

		assertEquals("defense", lines.getFirst().typeId());
		assertTrue(lines.getFirst().active());
		assertEquals("mining", lines.get(1).typeId());
		assertFalse(lines.get(1).active());
	}

	@Test
	void 아직인_것끼리는_많이_모은_쪽이_먼저다() {
		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				entry("swap", "교환", 1, 2, 0),
				entry("mining", "채굴", 2, 3, 0)), 10);

		assertEquals("mining", lines.getFirst().typeId());
	}

	@Test
	void 잘릴_때_없어지는_것은_가장_덜_모은_유형이다() {
		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				entry("swap", "교환", 1, 2, 0),
				entry("mining", "채굴", 3, 4, 3),
				entry("defense", "방어", 2, 3, 0)), 2);

		assertEquals(2, lines.size());
		assertEquals("mining", lines.getFirst().typeId());
		assertEquals("defense", lines.get(1).typeId());
	}

	@Test
	void 줄_수_상한이_0이거나_목록이_비면_아무것도_안_그린다() {
		assertTrue(PerkSetLines.visible(List.of(entry("mining", "채굴", 3, 4, 3)), 0).isEmpty());
		assertTrue(PerkSetLines.visible(List.of(), 10).isEmpty());
		assertTrue(PerkSetLines.visible(null, 10).isEmpty());
	}

	// ------------------------------------------------------------------ 보급 시계

	/** 10분 주기. 「보급 2·3」이 쓰는 값이다. */
	private static final int TEN_MINUTES = 10 * 20 * 60;
	/** 5분 주기. 「보급 4」가 쓰는 값이다. */
	private static final int FIVE_MINUTES = 5 * 20 * 60;

	private static PerkSetLines.Entry supply(int owned, int next, int tier, int intervalTicks) {
		return new PerkSetLines.Entry("supply", "보급", owned, next, tier, intervalTicks);
	}

	/** 켜진 시점까지 아는 보급 줄. 경계는 그 시점부터 주기마다다. */
	private static PerkSetLines.Entry supply(int owned, int next, int tier, int intervalTicks,
			long anchorTick) {
		return new PerkSetLines.Entry("supply", "보급", owned, next, tier, intervalTicks, anchorTick);
	}

	/**
	 * 시계는 <b>줄의 맨 끝</b>, 진행도 뒤에 붙는다.
	 *
	 * <p>그래야 마름모도 이름도 진행도도 줄마다 같은 자리에서 시작해 세로로 훑을 수 있다.
	 */
	@Test
	void 보급_줄에는_진행도_뒤에_남은_시간이_붙는다() {
		// 10분 주기에서 5분 48초가 지난 자리. 남은 것은 4분 12초다.
		long time = TEN_MINUTES * 12L + (5 * 60 + 48) * 20L;

		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(supply(2, 3, 2, TEN_MINUTES)), 10, time);

		assertEquals("◆ 보급 2/3 04:12", lines.getFirst().text());
	}

	/** 최대 단계(4/4)에서도 계속 보여 준다. 주기가 5분으로 줄었을 뿐 보급은 여전히 온다. */
	@Test
	void 최대_단계에서도_시계는_남는다() {
		long time = FIVE_MINUTES * 3L + 60 * 20L;

		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(supply(4, 0, 4, FIVE_MINUTES)), 10, time);

		assertEquals("◆ 보급 4/4 04:00", lines.getFirst().text());
	}

	/**
	 * 다 켠 뒤에 더 모은 보급 줄도 시계와 나란히 읽힌다.
	 *
	 * <p>보급은 단계가 2·3·4 인데 증강은 일곱 개다. 「보급 7/4 04:00」에서 슬래시가 두 숫자
	 * 덩어리를 갈라 준다 — 분수를 지우고 「보급 7 04:00」으로 적으면 개수와 시계가 맞붙어 읽힌다.
	 */
	@Test
	void 다_켠_보급_줄도_시계와_나란히_읽힌다() {
		long time = FIVE_MINUTES * 3L + 60 * 20L;

		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(supply(7, 0, 4, FIVE_MINUTES)), 10, time);

		assertEquals("◆ 보급 7/4 04:00", lines.getFirst().text());
	}

	/**
	 * <b>켜진 시점부터 한 주기를 센다.</b>
	 *
	 * <p>서버가 실어 준 켜진 시점을 화면이 그대로 쓰는지 본다. 이 값을 흘리면 세트를 켠 자리에
	 * 따라 첫 보급이 몇 초 만에 오는 것처럼 보인다.
	 */
	@Test
	void 켜진_시점부터_시계가_돈다() {
		// 주기의 배수와는 아무 상관 없는 자리에서 켰다.
		long anchor = TEN_MINUTES * 3L + 7777;

		assertEquals("◆ 보급 2/3 10:00",
				PerkSetLines.visible(List.of(supply(2, 3, 2, TEN_MINUTES, anchor)), 10, anchor)
						.getFirst().text());
		assertEquals("◆ 보급 2/3 04:12",
				PerkSetLines.visible(List.of(supply(2, 3, 2, TEN_MINUTES, anchor)), 10,
								anchor + (5 * 60 + 48) * 20L)
						.getFirst().text());
	}

	/**
	 * 4단계로 올라 주기가 5분이 되어도 시계가 되감기지 않는다.
	 *
	 * <p>켜진 시점은 그대로 두고 주기만 바뀐다. 켠 지 2분이면 5분 주기에서 3분이 남는 것이지
	 * 5분이 남는 것이 아니다.
	 */
	@Test
	void 주기가_줄어도_시계가_되감기지_않는다() {
		long anchor = 5000L;
		long time = anchor + 2 * 20 * 60L;

		assertEquals("◆ 보급 3/4 08:00",
				PerkSetLines.visible(List.of(supply(3, 4, 3, TEN_MINUTES, anchor)), 10, time)
						.getFirst().text());
		assertEquals("◆ 보급 4/4 03:00",
				PerkSetLines.visible(List.of(supply(4, 0, 4, FIVE_MINUTES, anchor)), 10, time)
						.getFirst().text());
	}

	/**
	 * <b>다른 유형에는 절대 붙지 않는다.</b>
	 *
	 * <p>붙일지 말지는 서버가 주기를 실어 주느냐로 정해진다. 화면이 유형 이름을 보고 고르면
	 * 유형 id 가 바뀌는 날 조용히 어긋난다.
	 */
	@Test
	void 주기가_없는_유형에는_시계가_안_붙는다() {
		long time = TEN_MINUTES * 12L + 100L;

		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				supply(2, 3, 2, TEN_MINUTES),
				entry("mining", "채굴", 3, 4, 3),
				entry("power", "화력", 1, 3, 0)), 10, time);

		// 차례는 정렬이 정하므로 유형으로 찾는다.
		assertEquals("◆ 채굴 3/4", textOf(lines, "mining"));
		assertEquals("◇ 화력 1/3", textOf(lines, "power"));
		assertEquals("◆ 보급 2/3 09:55", textOf(lines, "supply"));
	}

	/** 그 유형의 줄 글자. 없으면 시험을 실패시킨다. */
	private static String textOf(List<PerkSetLines.Line> lines, String typeId) {
		for (PerkSetLines.Line line : lines) {
			if (line.typeId().equals(typeId)) {
				return line.text();
			}
		}
		throw new AssertionError(typeId + " 줄이 없습니다");
	}

	/**
	 * 시간을 모르는 화면은 시계 없는 줄을 받는다.
	 *
	 * <p>팀 화면과 선택창 곁판이 이 길로 들어온다. 그 두 곳의 줄 폭이 매초 흔들리면 마우스가
	 * 어느 줄 위인지 재는 계산까지 함께 흔들린다.
	 */
	@Test
	void 시간을_안_넘기면_시계가_없다() {
		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(supply(2, 3, 2, TEN_MINUTES)), 10);

		assertEquals("◆ 보급 2/3", lines.getFirst().text());
	}

	/**
	 * 단계가 안 켜져 있어도 주기가 있으면 시계가 뜬다.
	 *
	 * <p>세트가 아니라 증강 하나가 {@code supply_drop} 을 들고 도는 경우다. 실제로 보급이 오는데
	 * 화면에 안 뜨는 것이 그 반대보다 나쁘다.
	 */
	@Test
	void 안_켜진_줄이라도_주기가_있으면_시계가_뜬다() {
		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(supply(1, 2, 0, TEN_MINUTES)), 10, TEN_MINUTES * 4L);

		assertEquals("◇ 보급 1/2 10:00", lines.getFirst().text());
	}

	/**
	 * <b>한 주기 내내 구분선의 길이가 변하지 않는다.</b>
	 *
	 * <p>{@code SupplyCountdown} 이 분까지 두 자리로 채우는 까닭이 이것이다. 구분선은 가장 긴
	 * 줄에 맞춰 매 프레임 다시 재므로, 시계의 글자 수가 한 번이라도 줄면 그 순간 선이 눈에 띄게
	 * 짧아졌다가 다음 주기에 다시 길어진다. 시계 글자만 보는 시험은
	 * {@code SupplyCountdownTest} 에 있고, 여기서는 <b>실제로 선을 재는 길</b>로 확인한다.
	 *
	 * <p>보급 줄이 가장 긴 줄이 되도록 다른 줄은 짧은 것 하나만 둔다. 그래야 시계가 흔들릴 때
	 * 폭도 함께 흔들려 시험이 실제로 무언가를 잡는다.
	 */
	@Test
	void 한_주기_동안_구분선_길이가_변하지_않는다() {
		List<PerkSetLines.Entry> entries = List.of(
				supply(2, 3, 2, TEN_MINUTES),
				entry("mining", "채굴", 1, 2, 0));

		int first = PerkSetLines.blockWidth(
				PerkSetLines.visible(entries, 10, 0L), FakeFont::width, 0);

		for (long time = 0; time < TEN_MINUTES; time++) {
			int now = PerkSetLines.blockWidth(
					PerkSetLines.visible(entries, 10, time), FakeFont::width, 0);
			assertEquals(first, now, "구분선 길이가 달라졌습니다 (틱 " + time + ")");
		}

		// 보급 줄이 정말 가장 긴 줄이었는지. 아니었다면 위 되풀이가 아무것도 못 잡는다.
		assertTrue(first > FakeFont.width("◇ 채굴 1/2"), "보급 줄이 가장 긴 줄이 아닙니다");
	}

	/**
	 * 다 켠 보급 줄에서도 한 주기 내내 구분선 길이가 그대로다.
	 *
	 * <p>분수가 「7/4」로 굳어 있어 흔들릴 것은 시계뿐이다. 위 시험과 같은 것을 보되 <b>다 켠
	 * 상태</b>로 본다.
	 */
	@Test
	void 다_켠_보급_줄에서도_구분선_길이가_변하지_않는다() {
		List<PerkSetLines.Entry> entries = List.of(
				supply(7, 0, 4, FIVE_MINUTES),
				entry("mining", "채굴", 1, 2, 0));

		int first = PerkSetLines.blockWidth(
				PerkSetLines.visible(entries, 10, 0L), FakeFont::width, 0);

		for (long time = 0; time < FIVE_MINUTES; time++) {
			int now = PerkSetLines.blockWidth(
					PerkSetLines.visible(entries, 10, time), FakeFont::width, 0);
			assertEquals(first, now, "구분선 길이가 달라졌습니다 (틱 " + time + ")");
		}

		assertTrue(first > FakeFont.width("◇ 채굴 1/2"), "보급 줄이 가장 긴 줄이 아닙니다");
	}

	/** 주기를 안 싣는 옛 서버에 붙으면 옛 모습 그대로다. 다섯 인자 생성자가 그 자리다. */
	@Test
	void 주기를_안_보내는_서버에서는_옛_모습_그대로다() {
		PerkSetLines.Entry old = entry("supply", "보급", 2, 3, 2);

		assertEquals(0, old.intervalTicks());
		assertFalse(old.hasTimer());
		assertEquals("◆ 보급 2/3",
				PerkSetLines.visible(List.of(old), 10, 12345L).getFirst().text());
	}

	/**
	 * 켜진 시점만 안 싣는 서버에서는 시계가 뜨되 경계가 게임 시간의 배수가 된다.
	 *
	 * <p>여섯 인자 생성자가 그 자리다. 시계가 아예 안 뜨는 것보다 낫다.
	 */
	@Test
	void 켜진_시점을_안_보내는_서버에서는_게임_시간의_배수가_경계다() {
		PerkSetLines.Entry noAnchor = supply(2, 3, 2, TEN_MINUTES);

		assertEquals(0L, noAnchor.anchorTick());
		assertEquals("◆ 보급 2/3 10:00",
				PerkSetLines.visible(List.of(noAnchor), 10, TEN_MINUTES * 4L).getFirst().text());
	}

	// ------------------------------------------------------------------ 자리

	/**
	 * 세트가 없으면 높이가 0이어야 한다.
	 *
	 * <p>팀 화면의 {@code perkListTop()} 이 이 값을 더한다. 0이 아니면 세트를 하나도 모으지
	 * 않은 사람의 증강 목록이 이유 없이 아래로 내려간다.
	 */
	@Test
	void 줄이_없으면_자리를_차지하지_않는다() {
		assertEquals(0, PerkSetLines.blockHeight(0, 12, 4));
		assertEquals(0, PerkSetLines.blockHeight(-1, 12, 4));
	}

	@Test
	void 줄이_있으면_줄_높이에_아래_틈을_더한다() {
		assertEquals(12 + 4, PerkSetLines.blockHeight(1, 12, 4));
		assertEquals(36 + 4, PerkSetLines.blockHeight(3, 12, 4));
	}

	/** 구분선 길이. 실제 폰트와 같은 폭으로 재는지 {@link FakeFont} 로 확인한다. */
	@Test
	void 가장_긴_줄의_폭을_잰다() {
		List<PerkSetLines.Line> lines = PerkSetLines.visible(List.of(
				entry("mining", "채굴", 3, 4, 3),
				entry("recovery", "회복", 1, 2, 0)), 10);

		// 두 줄의 글자 길이가 같아 폭도 같다. 최소값보다 크면 잰 값이 그대로 쓰인다.
		int expected = FakeFont.width("◆ 채굴 3/4");
		assertEquals(expected, PerkSetLines.blockWidth(lines, FakeFont::width, 0));
	}

	@Test
	void 줄이_아무리_짧아도_최소_폭은_긋는다() {
		List<PerkSetLines.Line> lines =
				PerkSetLines.visible(List.of(entry("mining", "채굴", 3, 4, 3)), 10);

		assertEquals(400, PerkSetLines.blockWidth(lines, FakeFont::width, 400));
	}

	@Test
	void 줄이_없으면_구분선도_없다() {
		assertEquals(0, PerkSetLines.blockWidth(List.of(), FakeFont::width, 60));
	}

	// ------------------------------------------------------------------ 마우스

	@Test
	void 줄_안에_있으면_그_차례를_돌려준다() {
		assertEquals(0, PerkSetLines.rowAt(20, 100, 10, 80, 100, 12, 3));
		assertEquals(0, PerkSetLines.rowAt(20, 111, 10, 80, 100, 12, 3));
		assertEquals(1, PerkSetLines.rowAt(20, 112, 10, 80, 100, 12, 3));
		assertEquals(2, PerkSetLines.rowAt(20, 135, 10, 80, 100, 12, 3));
	}

	@Test
	void 덩어리_밖이면_아무_줄도_아니다() {
		// 위로 벗어남 · 아래로 벗어남 · 왼쪽으로 벗어남 · 오른쪽으로 벗어남
		assertEquals(-1, PerkSetLines.rowAt(20, 99, 10, 80, 100, 12, 3));
		assertEquals(-1, PerkSetLines.rowAt(20, 136, 10, 80, 100, 12, 3));
		assertEquals(-1, PerkSetLines.rowAt(9, 100, 10, 80, 100, 12, 3));
		assertEquals(-1, PerkSetLines.rowAt(90, 100, 10, 80, 100, 12, 3));
	}

	/**
	 * 판 오른쪽의 빈자리에서는 툴팁이 뜨면 안 된다.
	 *
	 * <p>가로 범위를 안 보면 마우스를 어디에 두어도 무언가 뜨는 화면이 된다.
	 */
	@Test
	void 그릴_줄이_없으면_마우스도_받지_않는다() {
		assertEquals(-1, PerkSetLines.rowAt(20, 100, 10, 80, 100, 12, 0));
		assertEquals(-1, PerkSetLines.rowAt(20, 100, 10, 0, 100, 12, 3));
	}
}
