package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.TeamState;

/** 회차 보너스의 서버 조회와 계산. 화면에서도 같은 값을 조회할 수 있다. */
public final class RunBonuses {
	private RunBonuses() { }

	public static double experienceMultiplier() {
		return experienceMultiplier(RunProgressManager.runNumber(),
				SharedFateMod.config == null ? 3.0 : SharedFateMod.config.runXpBonusPercent);
	}

	public static double experienceBonusPercent() {
		return (experienceMultiplier() - 1.0) * 100.0;
	}

	public static double experienceMultiplier(int run, double percent) {
		return 1.0 + Math.max(0L, (long) run - 1L) * percent / 100.0;
	}

	/** 감소·차단된 0점에는 배율도 잔여량도 적용하지 않는다. */
	public static long scaleExperience(TeamState state, long delta, double multiplier) {
		if (delta <= 0 || multiplier <= 1.0) return delta;
		double scaled = delta * multiplier + state.runXpRemainder;
		if (scaled >= Long.MAX_VALUE) {
			state.runXpRemainder = 0.0;
			return Long.MAX_VALUE;
		}
		long whole = (long) Math.floor(scaled + 1.0e-9);
		state.runXpRemainder = Math.max(0.0, scaled - whole);
		return whole;
	}

	public static boolean shouldGrantShard(TeamState state, int run, int fromRun) {
		return state.runStarted && fromRun > 0 && run >= fromRun
				&& state.rallyShardGrantedRun < run;
	}

	/** 지급이 끝난 뒤에만 기록한다. 같은 회차의 재호출은 아무 일도 하지 않는다. */
	public static boolean grantShardOnce(TeamState state, int run, int fromRun, Runnable delivery) {
		if (!shouldGrantShard(state, run, fromRun)) return false;
		delivery.run();
		state.rallyShardGrantedRun = run;
		return true;
	}
}
