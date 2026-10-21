package com.sharedfate.sync;

import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 드래곤 시련 팀의 경험치 곡선과 엔드 경험치 차단(0.44.0-dev). 월드 없이 식만 굴린다.
 */
class TrialExperienceTest {

	@Test
	void 사십에서_오십까지는_바닐라_십에서_이십의_값이다() {
		int[] expected = {27, 29, 31, 33, 35, 37, 42, 47, 52, 57};
		int sum = 0;
		for (int level = 40; level < 50; level++) {
			int needed = TrialExperience.needed(level, true);
			assertEquals(expected[level - 40], needed, "레벨 " + level);
			assertEquals(TrialExperience.vanillaNeeded(level - 30), needed);
			sum += needed;
		}
		assertEquals(390, sum, "합 390");
		// 원래(바닐라 40→50)는 약 2425 였다.
		int vanilla = 0;
		for (int level = 40; level < 50; level++) {
			vanilla += TrialExperience.vanillaNeeded(level);
		}
		assertEquals(2425, vanilla);
	}

	@Test
	void 사십_아래는_시련_팀도_바닐라와_한_점도_다르지_않다() {
		for (int level = 0; level <= 40; level++) {
			assertEquals(TrialExperience.vanillaBase(level), TrialExperience.base(level, true), "레벨 " + level);
			if (level < 40) {
				assertEquals(TrialExperience.vanillaNeeded(level), TrialExperience.needed(level, true));
			}
		}
		// 바닐라 곡선의 알려진 값.
		assertEquals(2920, TrialExperience.base(40, false));
		assertEquals(5345, TrialExperience.base(50, false));
		assertEquals(160, TrialExperience.base(10, false));
		assertEquals(550, TrialExperience.base(20, false));
	}

	@Test
	void 시련_곡선의_누적은_이어_붙인_값이다() {
		assertEquals(2920 + 27, TrialExperience.base(41, true));
		assertEquals(2920 + 27 + 29 + 31 + 33 + 35, TrialExperience.base(45, true));
		assertEquals(3310, TrialExperience.base(50, true));
		long running = TrialExperience.base(40, true);
		for (int level = 40; level < 50; level++) {
			running += TrialExperience.needed(level, true);
			assertEquals(running, TrialExperience.base(level + 1, true), "레벨 " + (level + 1));
		}
	}

	@Test
	void 점수와_레벨_막대가_서로_돌아온다() {
		for (boolean trial : new boolean[] {false, true}) {
			for (int level = 0; level <= 50; level++) {
				for (float progress : new float[] {0.0F, 0.25F, 0.5F, 0.9F}) {
					int points = TrialExperience.pointsFor(level, progress, trial);
					int back = TrialExperience.levelFor(points, trial);
					assertEquals(level, back, "trial=" + trial + " 레벨 " + level + " 막대 " + progress);
					assertEquals(progress, TrialExperience.progressFor(points, trial), 0.08F);
				}
			}
		}
		// 3310 은 시련 팀의 레벨 50 빈 막대, 바닐라로는 레벨 41 이다.
		assertEquals(50, TrialExperience.levelFor(3310, true));
		assertEquals(0.0F, TrialExperience.progressFor(3310, true), 1.0E-6F);
		assertEquals(41, TrialExperience.levelFor(3310, false));
	}

	@Test
	void 공유_상한은_시련_팀이_3310_이다() {
		TeamState plain = TeamState.fresh(20.0F);
		TeamState trials = TeamState.fresh(20.0F);
		trials.dragonTrialsEnabled = true;
		assertEquals(2920, StatMirror.experienceCap(plain));
		assertEquals(3310, StatMirror.experienceCap(trials));
		assertEquals(50, StatMirror.levelCap(trials));
		assertEquals(40, StatMirror.levelCap(plain));
	}

	@Test
	void 엔드_차단은_시련_팀이_엔드에_있을_때만이다() {
		assertTrue(TrialExperience.blocksGain(true, true));
		assertFalse(TrialExperience.blocksGain(true, false), "시련 팀이라도 엔드 밖이면 얻는다");
		assertFalse(TrialExperience.blocksGain(false, true), "시련을 끈 팀은 엔드에서도 얻는다");
		assertFalse(TrialExperience.blocksGain(false, false));
		assertFalse(TrialExperience.blocksGain((TeamState) null, null));
	}

	@Test
	void 막힌_사람의_증가만_자르고_감소는_그대로다() {
		assertEquals(0L, TrialExperience.admitted(37L, true));
		assertEquals(-12L, TrialExperience.admitted(-12L, true), "인챈트로 쓴 것은 그대로 빠진다");
		assertEquals(37L, TrialExperience.admitted(37L, false));
		assertEquals(0L, TrialExperience.admitted(0L, true));
	}

	@Test
	void 공유_풀은_막힌_사람의_증가를_세지_않는다() {
		// 엔드의 시련 팀원이 구슬 50 점을 먹고 오버월드 팀원이 10 점을 먹은 바퀴.
		long end = TrialExperience.admitted(50L, TrialExperience.blocksGain(true, true));
		long overworld = TrialExperience.admitted(10L, TrialExperience.blocksGain(true, false));
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(0.0F, 0.0F, 0.0F, 0, 0.0F, end),
				new StatMirror.PlayerDelta(0.0F, 0.0F, 0.0F, 0, 0.0F, overworld)));
		assertEquals(10L, folded.totalExperience());
	}
}
