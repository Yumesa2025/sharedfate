package com.sharedfate.net;

import com.sharedfate.perk.PerkMilestones;
import com.sharedfate.team.TeamState;
import com.sharedfate.ui.DragonPrepStyle;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 41~50 구간의 다음 증강 레벨과 HUD 표식(0.44.0-dev). 클라이언트는 서버가 보내는 다음 증강 레벨이 40 을
 * 넘는가만 보고 엔더 연보라 「드래곤전 대비 증강까지 N」을 그린다.
 */
class DragonPrepNextLevelTest {

	private static TeamState team(boolean trials, int lastMilestone) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.dragonTrialsEnabled = trials;
		state.lastPerkMilestone = lastMilestone;
		return state;
	}

	@Test
	void 시련_팀은_사십_뒤에_사십오와_오십이_남는다() {
		assertEquals(45, TeamBroadcaster.nextPerkLevel(team(true, 40)));
		assertEquals(50, TeamBroadcaster.nextPerkLevel(team(true, 45)));
		assertEquals(0, TeamBroadcaster.nextPerkLevel(team(true, 50)), "50 이 끝");
		assertEquals(40, TeamBroadcaster.nextPerkLevel(team(true, 35)));
	}

	@Test
	void 시련을_끈_팀은_사십이_끝_그대로다() {
		assertEquals(0, TeamBroadcaster.nextPerkLevel(team(false, 40)));
		assertEquals(40, TeamBroadcaster.nextPerkLevel(team(false, 35)));
	}

	@Test
	void 구간은_시련_팀만_오십까지_센다() {
		assertEquals(List.of(45, 50), PerkMilestones.newlyReached(40, 50, PerkMilestones.TRIAL_MAX));
		assertEquals(List.of(45), PerkMilestones.newlyReached(40, 49, PerkMilestones.maxFor(true)));
		assertEquals(List.of(), PerkMilestones.newlyReached(40, 50), "옛 길(끝 40)은 그대로");
		assertEquals(List.of(35, 40), PerkMilestones.newlyReached(30, 50, PerkMilestones.maxFor(false)));
		assertEquals(45, PerkMilestones.clampMilestone(45, 50));
		assertEquals(40, PerkMilestones.clampMilestone(45));
		assertTrue(PerkMilestones.isTrialMilestone(45));
		assertTrue(PerkMilestones.isTrialMilestone(50));
		assertFalse(PerkMilestones.isTrialMilestone(40));
		assertFalse(PerkMilestones.isTrialMilestone(55));
		assertFalse(PerkMilestones.isTrialMilestone(47));
		assertEquals(50, PerkMilestones.maxFor(true));
		assertEquals(40, PerkMilestones.maxFor(false));
	}

	@Test
	void 사십오나_오십이_다음이면_엔더_연보라_표식이다() {
		assertTrue(DragonPrepStyle.nextIsPrep(45));
		assertTrue(DragonPrepStyle.nextIsPrep(50));
		assertFalse(DragonPrepStyle.nextIsPrep(40), "40 이하는 노랑 그대로");
		assertFalse(DragonPrepStyle.nextIsPrep(0), "더 받을 것이 없음");
		assertEquals("드래곤전 대비 증강까지 3", DragonPrepStyle.remainingText(3, true));
		assertEquals("다음 증강까지 3", DragonPrepStyle.remainingText(3, false));
	}
}
