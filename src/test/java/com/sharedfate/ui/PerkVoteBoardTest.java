package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 선택자가 아닌 사람이 던지는 표의 규칙.
 *
 * <p>여기 붙들어 두는 것은 <b>한 사람은 한 표</b>다. 서버가
 * {@code PerkChoiceSession.castVote} 에서 이 판단 하나로 표를 켜고 끄고 옮기므로, 이 함수가
 * 어긋나면 한 사람이 카드 둘에 동시에 표를 들거나 취소가 안 되는 화면이 된다.
 */
class PerkVoteBoardTest {

	private static final String CARD_A = "sharedfate:light_step";
	private static final String CARD_B = "sharedfate:glass_world";

	// ------------------------------------------------------------------ 켜고 끄고 옮기기

	@Test
	void 처음_누르면_표가_생긴다() {
		assertEquals(CARD_A, PerkVoteBoard.toggle(null, CARD_A));
	}

	@Test
	void 같은_것을_다시_누르면_표가_사라진다() {
		assertNull(PerkVoteBoard.toggle(CARD_A, CARD_A), "취소다");
	}

	@Test
	void 다른_카드를_누르면_표가_옮겨_간다() {
		// 둘 다 들고 있게 되면 선택자가 읽는 수가 실제 사람 수보다 커진다.
		assertEquals(CARD_B, PerkVoteBoard.toggle(CARD_A, CARD_B));
	}

	@Test
	void 카드와_다시_뽑기도_한_통이다() {
		// 「이 카드로 하자」와 「다시 뽑자」는 서로 반대되는 제안이라 동시에 들 수 없다.
		assertEquals(PerkVoteBoard.REROLL_TARGET,
				PerkVoteBoard.toggle(CARD_A, PerkVoteBoard.REROLL_TARGET));
		assertEquals(CARD_A, PerkVoteBoard.toggle(PerkVoteBoard.REROLL_TARGET, CARD_A));
		assertNull(PerkVoteBoard.toggle(PerkVoteBoard.REROLL_TARGET, PerkVoteBoard.REROLL_TARGET));
	}

	@Test
	void 빈_대상은_표를_건드리지_않는다() {
		// 밖에서 오는 값이다. 빈 문자열로 남의 표를 지울 수 있으면 안 된다.
		assertEquals(CARD_A, PerkVoteBoard.toggle(CARD_A, ""));
		assertEquals(CARD_A, PerkVoteBoard.toggle(CARD_A, null));
	}

	// ------------------------------------------------------------------ 다시 뽑기 자리

	@Test
	void 다시_뽑기_자리는_증강_id_와_겹치지_않는다() {
		// 증강 id 는 네임스페이스 식별자라 '#' 를 담을 수 없다.
		assertTrue(PerkVoteBoard.REROLL_TARGET.contains("#"));
		assertTrue(PerkVoteBoard.isReroll(PerkVoteBoard.REROLL_TARGET));
		assertFalse(PerkVoteBoard.isReroll(CARD_A));
		assertFalse(PerkVoteBoard.isReroll(null));
	}

	// ------------------------------------------------------------------ 그리는 글자

	@Test
	void 아무도_안_누른_자리에는_아무것도_안_그린다() {
		assertEquals("", PerkVoteBoard.mark(0));
		assertEquals("", PerkVoteBoard.mark(-1));
	}

	@Test
	void 한_표면_체크만_두_표부터는_수가_붙는다() {
		assertEquals(PerkVoteBoard.MARK, PerkVoteBoard.mark(1));
		assertEquals(PerkVoteBoard.MARK + "2", PerkVoteBoard.mark(2));
		assertEquals(PerkVoteBoard.MARK + "5", PerkVoteBoard.mark(5));
	}
}
