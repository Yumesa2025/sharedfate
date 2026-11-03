package com.sharedfate.enchant;

import com.sharedfate.TestBootstrap;
import com.sharedfate.sync.RunBonuses;
import com.sharedfate.sync.TrialExperience;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MendingExperienceTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@Test void 수선이_전부_써도_구슬_경험치는_전부_받고_회차_배율이_붙는다() {
		int[] repaired = {0};
		int gained = MendingExperience.repairAndKeep(100, points -> { repaired[0] += points * 2; return 0; });
		assertEquals(200, repaired[0]);
		assertEquals(100, gained);
		assertEquals(103, RunBonuses.scaleExperience(TeamState.fresh(20), gained, 1.03));
	}
	@Test void 엔드_시련도_수리는_하지만_경험치만_막는다() {
		int[] repaired = {0};
		int gained = MendingExperience.repairAndKeep(7, points -> { repaired[0] = points * 2; return 2; });
		assertEquals(14, repaired[0]);
		assertEquals(0, TrialExperience.admitted(gained, TrialExperience.blocksGain(true, true)));
		assertEquals(7, TrialExperience.admitted(gained, false));
	}
}
