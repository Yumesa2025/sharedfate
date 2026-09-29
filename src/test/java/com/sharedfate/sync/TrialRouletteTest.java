package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Risk;
import com.sharedfate.sync.TrialCatalog.Trial;
import com.sharedfate.sync.TrialCatalog.Trigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 룰렛 연출의 계산.
 *
 * <p>월드도 플레이어도 쓰지 않는다. {@link TrialRoulette} 을 {@code DragonTrialManager} 안에
 * 숨기지 않고 따로 뺀 이유가 이것이다.
 */
class TrialRouletteTest {

	private static Trial card(String id) {
		return new Trial("sharedfate:" + id, id, "시험용", TrialCatalog.POOL_ENTRY,
				new Risk.DelayedStrike(Risk.Aim.TRAIL, Risk.Impact.EXPLOSION,
						40, 20, 1.0F, 1.0, 0.0, 1));
	}

	private static List<Trial> cards(int count) {
		List<Trial> list = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			list.add(card("card" + index));
		}
		return list;
	}

	@Test
	@DisplayName("칸 간격의 합이 연출 길이와 같다")
	void 칸_간격의_합이_연출_길이와_같다() {
		int sum = 0;
		for (int gap : TrialRoulette.gaps()) {
			sum += gap;
		}
		// 둘이 어긋나면 마지막 칸이 뜨기 전에 연출이 끝나거나, 끝난 뒤에도 돌아간다.
		assertEquals(TrialRoulette.TOTAL_TICKS, sum);
	}

	@Test
	@DisplayName("뒤로 갈수록 느려진다")
	void 뒤로_갈수록_느려진다() {
		int[] gaps = TrialRoulette.gaps();
		for (int index = 1; index < gaps.length; index++) {
			// 일정한 속도로 돌다 뚝 멈추면 뽑는 느낌이 나지 않는다.
			assertTrue(gaps[index] >= gaps[index - 1],
					index + "번째 칸이 앞 칸보다 빨라졌습니다");
		}
		assertTrue(gaps[gaps.length - 1] > gaps[0], "마지막이 처음보다 느려야 합니다");
	}

	@Test
	@DisplayName("후보가 몇 장이든 마지막 칸은 뽑힌 카드다")
	void 후보가_몇_장이든_마지막_칸은_뽑힌_카드다() {
		for (int size = 1; size <= 6; size++) {
			List<Trial> candidates = cards(size);
			for (Trial wanted : candidates) {
				TrialRoulette roulette =
						TrialRoulette.openWith(Trigger.ENTRY, candidates, 0L, wanted);
				assertNotNull(roulette);
				// 연출이 멈춘 칸이 곧 결과여야 한다. 어긋나면 화면과 실제가 다른 것을 준다.
				assertSame(wanted, roulette.candidateAt(roulette.frameCount() - 1),
						"후보 " + size + "장에서 마지막 칸이 결과가 아닙니다");
			}
		}
	}

	@Test
	@DisplayName("후보가 하나뿐이면 모든 칸이 그 카드다")
	void 후보가_하나뿐이면_모든_칸이_그_카드다() {
		Trial only = card("only");
		TrialRoulette roulette = TrialRoulette.openWith(Trigger.ENTRY, List.of(only), 0L, only);
		assertNotNull(roulette);
		for (int frame = 0; frame < roulette.frameCount(); frame++) {
			assertSame(only, roulette.candidateAt(frame));
		}
	}

	@Test
	@DisplayName("풀이 비면 룰렛이 열리지 않는다")
	void 풀이_비면_룰렛이_열리지_않는다() {
		// 카드를 채워 가는 동안 빈 풀은 정상이다. 오류로 다루지 않는다.
		assertNull(TrialRoulette.openWith(Trigger.ENTRY, List.of(), 0L, card("x")));
		assertNull(TrialRoulette.open(Trigger.ENTRY, List.of(), 0L, null));
		assertNull(TrialRoulette.open(null, cards(3), 0L, null));
	}

	@Test
	@DisplayName("후보에 없는 카드로는 결과를 못박을 수 없다")
	void 후보에_없는_카드로는_결과를_못박을_수_없다() {
		assertNull(TrialRoulette.openWith(Trigger.ENTRY, cards(3), 0L, card("바깥")));
	}

	@Test
	@DisplayName("같은 칸에서 두 번 부르면 두 번째는 아무것도 주지 않는다")
	void 같은_칸에서_두_번_부르면_두_번째는_아무것도_주지_않는다() {
		List<Trial> candidates = cards(3);
		TrialRoulette roulette =
				TrialRoulette.openWith(Trigger.ENTRY, candidates, 100L, candidates.getFirst());
		assertNotNull(roulette);
		// 매 틱 타이틀을 다시 보내면 글자가 떨리고 소리가 뭉개진다.
		assertNotNull(roulette.advance(100L));
		assertNull(roulette.advance(100L));
	}

	@Test
	@DisplayName("칸이 하나도 빠지지 않고 차례로 나온다")
	void 칸이_하나도_빠지지_않고_차례로_나온다() {
		List<Trial> candidates = cards(4);
		TrialRoulette roulette =
				TrialRoulette.openWith(Trigger.ENTRY, candidates, 0L, candidates.get(2));
		assertNotNull(roulette);
		int seen = 0;
		Trial last = null;
		for (long tick = 0; tick <= TrialRoulette.TOTAL_TICKS; tick++) {
			Trial shown = roulette.advance(tick);
			if (shown != null) {
				seen++;
				last = shown;
			}
		}
		assertEquals(roulette.frameCount(), seen, "빠진 칸이 있습니다");
		assertSame(candidates.get(2), last, "마지막으로 보인 것이 결과가 아닙니다");
	}

	@Test
	@DisplayName("복원 직후처럼 시각이 거꾸로여도 연출이 멈추지 않는다")
	void 복원_직후처럼_시각이_거꾸로여도_연출이_멈추지_않는다() {
		List<Trial> candidates = cards(2);
		TrialRoulette roulette =
				TrialRoulette.openWith(Trigger.ENTRY, candidates, 1000L, candidates.getFirst());
		assertNotNull(roulette);
		// 세션을 되살린 직후 now 가 시작 틱보다 작을 수 있다. 음수 경과로 영영 안 끝나면 안 된다.
		assertEquals(0, roulette.frameOf(0));
		assertTrue(!roulette.finished(500L));
		assertTrue(roulette.finished(1000L + TrialRoulette.TOTAL_TICKS));
	}

	@Test
	@DisplayName("연출은 정확히 그 길이에 끝난다")
	void 연출은_정확히_그_길이에_끝난다() {
		List<Trial> candidates = cards(3);
		TrialRoulette roulette =
				TrialRoulette.openWith(Trigger.ENTRY, candidates, 0L, candidates.getFirst());
		assertNotNull(roulette);
		assertTrue(!roulette.finished(TrialRoulette.TOTAL_TICKS - 1));
		assertTrue(roulette.finished(TrialRoulette.TOTAL_TICKS));
	}
}
