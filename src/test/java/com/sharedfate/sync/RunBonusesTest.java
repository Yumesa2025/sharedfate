package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.SharedFateMod;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.team.TeamState;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunBonusesTest {
	@BeforeAll
	static void bootstrap() { TestBootstrap.ensureInitialized(); }

	private TeamState reload(TeamState state) {
		return TeamState.CODEC.parse(NbtOps.INSTANCE,
				TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
	}

	@Test
	void 공유_반영에서만_한번_곱하고_가입_이관에는_곱하지_않는다() throws Exception {
		var field = RunProgressManager.class.getDeclaredField("state");
		field.setAccessible(true);
		Object previous = field.get(null);
		SharedFateConfig config = SharedFateMod.config;
		try {
			SharedFateMod.config = new SharedFateConfig();
			RunProgressState run = RunProgressState.firstRun();
			run.advanceToNextRun();
			field.set(null, run);
			TeamState team = TeamState.fresh(20);
			StatMirror.applyDeltas(team, 20, 0,
					new StatMirror.StatDelta(0, 0, 0, 0, 0, 0, 100), true);
			assertEquals(103, team.totalExperience);
			StatMirror.addSharedExperience(team, 100);
			assertEquals(203, team.totalExperience);
			StatMirror.applyDeltas(team, 20, 0,
					new StatMirror.StatDelta(0, 0, 0, 0, 0, 0, 100), false);
			assertEquals(203, team.totalExperience);
		} finally {
			field.set(null, previous);
			SharedFateMod.config = config;
		}
	}

	@Test
	void 회차마다_삼퍼센트이며_상한이_없다() {
		assertEquals(1.00, RunBonuses.experienceMultiplier(1, 3), 1e-9);
		assertEquals(1.03, RunBonuses.experienceMultiplier(2, 3), 1e-9);
		assertEquals(1.06, RunBonuses.experienceMultiplier(3, 3), 1e-9);
		assertEquals(3001.0, RunBonuses.experienceMultiplier(100001, 3), 1e-9);
		assertEquals(1.0, RunBonuses.experienceMultiplier(100001, 0));
	}

	@Test
	void 일점씩_백번_얻어도_삼점이_남고_재시작에도_보존한다() {
		TeamState state = TeamState.fresh(20);
		long total = 0;
		for (int i = 0; i < 100; i++) {
			total += RunBonuses.scaleExperience(state, 1, 1.03);
			state = reload(state);
		}
		assertEquals(103, total);
		assertEquals(0.0, state.runXpRemainder, 1e-8);
	}

	@Test
	void 엔드_시련_차단과_경험치_소비는_보너스보다_우선한다() {
		TeamState state = TeamState.fresh(20);
		state.runXpRemainder = 0.75;
		assertEquals(0, RunBonuses.scaleExperience(state,
				TrialExperience.admitted(100, TrialExperience.blocksGain(true, true)), 4));
		assertEquals(-10, RunBonuses.scaleExperience(state, -10, 4));
		assertEquals(0.75, state.runXpRemainder);
	}

	@Test
	void 오회차부터_팀당_한번이고_저장_복원에도_재지급하지_않는다() {
		TeamState state = TeamState.fresh(20);
		state.runStarted = true;
		assertFalse(RunBonuses.shouldGrantShard(state, 4, 5));
		assertTrue(RunBonuses.shouldGrantShard(state, 5, 5));
		int[] grants = {0};
		assertTrue(RunBonuses.grantShardOnce(state, 5, 5, () -> grants[0]++));
		state = reload(state);
		for (int i = 0; i < 100; i++) {
			assertFalse(RunBonuses.grantShardOnce(state, 5, 5, () -> grants[0]++));
		}
		assertEquals(1, grants[0]);
		assertFalse(RunBonuses.shouldGrantShard(state, 5, 5));
		assertTrue(RunBonuses.shouldGrantShard(state, 6, 5));
		assertTrue(RunBonuses.grantShardOnce(state, 6, 5, () -> grants[0]++));
		assertEquals(2, grants[0]);
		assertFalse(RunBonuses.shouldGrantShard(state, 6, 0));
		state.runStarted = false;
		assertFalse(RunBonuses.shouldGrantShard(state, 6, 5));
	}
}
