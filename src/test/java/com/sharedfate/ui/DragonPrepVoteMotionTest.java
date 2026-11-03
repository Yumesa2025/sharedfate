package com.sharedfate.ui;

import com.sharedfate.perk.DragonPrepVote;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「드래곤전 대비」 투표 창의 등장 연출과 룰렛(2026-11-04). */
class DragonPrepVoteMotionTest {

	/** 연출이 서버의 등장 시간보다 길면 투표가 열린 뒤에도 카드가 뒤집히는 중이다. */
	@Test
	void 등장_연출은_서버의_등장_시간_안에_끝난다() {
		long serverMillis = DragonPrepVote.REVEAL_TICKS * 50L;
		assertTrue(DragonPrepVoteMotion.totalMillis(2) <= serverMillis,
				DragonPrepVoteMotion.totalMillis(2) + "ms > " + serverMillis + "ms");
		long end = DragonPrepVoteMotion.totalMillis(2);
		for (int index = 0; index < 2; index++) {
			assertEquals(1.0F, DragonPrepVoteMotion.rise(end, index));
			assertEquals(0, DragonPrepVoteMotion.riseOffset(end, index, 48));
			assertTrue(DragonPrepVoteMotion.frontVisible(end, index));
			assertEquals(1.0F, DragonPrepVoteMotion.flipScale(end, index), 1.0E-4F);
		}
	}

	@Test
	void 카드는_뒷면으로_어둠_속에서_시작해_왼쪽부터_떠오른다() {
		assertEquals(48, DragonPrepVoteMotion.riseOffset(0L, 0, 48));
		assertFalse(DragonPrepVoteMotion.frontVisible(0L, 0));
		long midFirst = DragonPrepVoteMotion.RISE_MILLIS / 2;
		assertTrue(DragonPrepVoteMotion.rise(midFirst, 0) > DragonPrepVoteMotion.rise(midFirst, 1),
				"왼쪽 카드가 먼저 떠오른다");
	}

	@Test
	void 반_바퀴에서_가장_좁고_그때_앞면이_된다() {
		long turn = DragonPrepVoteMotion.turnMillis(0);
		assertFalse(DragonPrepVoteMotion.frontVisible(turn - 1, 0));
		assertTrue(DragonPrepVoteMotion.frontVisible(turn, 0));
		assertTrue(DragonPrepVoteMotion.flipScale(turn, 0) < 0.1F);
		assertTrue(DragonPrepVoteMotion.flipScale(turn, 0) >= DragonPrepVoteMotion.MIN_FLIP_SCALE,
				"폭이 0 이 되면 한 프레임 동안 카드가 사라져 깜빡인다");
	}

	/** 마지막 넘김이 서버가 정한 카드에 닿아야 멈추는 순간 강조가 반대 카드에서 튀지 않는다. */
	@Test
	void 룰렛은_서버가_정한_카드에서_멈춘다() {
		int ticks = DragonPrepVote.ROULETTE_TICKS;
		int steps = DragonPrepVoteMotion.rouletteSteps(ticks);
		assertTrue(steps >= 4, "두 장 사이를 몇 번은 오가야 룰렛으로 보인다: " + steps);
		for (int winner = 0; winner < 2; winner++) {
			int index = DragonPrepVoteMotion.rouletteStart(winner, steps, 2);
			PerkDrawRoll roll = new PerkDrawRoll(ticks);
			for (int tick = 0; tick < ticks; tick++) {
				if (roll.tick() == PerkDrawRoll.Event.NEXT_NAME) {
					index = (index + 1) % 2;
				}
			}
			assertEquals(winner, index);
		}
		assertEquals(0, DragonPrepVoteMotion.rouletteStart(0, steps, 1));
	}
}
