package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 팀 공유 허기 풀이 「먹방」의 상한 100 을 따르는지 본다.
 *
 * <p>허기는 팀이 함께 쓰고 {@link StatMirror#applyDeltas} 가 매 바퀴 그 풀을 상한으로 자른다.
 * 여기가 20 에 묶여 있으면 개인이 아무리 57 까지 먹어도 다음 바퀴에 20 으로 되돌아간다.
 */
class FoodCapacityPoolTest {
	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	private static TeamState team(boolean mukbang) {
		PerkRegistry.loadBundled();
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		if (mukbang) {
			state.ownedPerks.add("sharedfate:mukbang");
		}
		return state;
	}

	private static StatMirror.StatDelta food(int food, float saturation) {
		return new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, food, saturation, 0);
	}

	@Test
	void 먹방_팀의_풀은_100_까지_쌓인다() {
		TeamState state = team(true);
		state.foodLevel = 50;
		StatMirror.applyDeltas(state, 20.0F, 0.0F, food(30, 0.0F), false);
		assertEquals(80, state.foodLevel);

		StatMirror.applyDeltas(state, 20.0F, 0.0F, food(30, 0.0F), false);
		assertEquals(100, state.foodLevel, "100 넘는 몫은 버린다");
	}

	@Test
	void 먹방_팀의_포만감도_20_이_끝이다() {
		TeamState state = team(true);
		state.foodLevel = 57;
		state.saturation = 15.0F;

		StatMirror.applyDeltas(state, 20.0F, 0.0F, food(0, 10.0F), false);

		assertEquals(20.0F, state.saturation, 1.0e-6F);
	}

	@Test
	void 증강을_잃은_팀의_풀은_다음_바퀴에_20_으로_깎인다() {
		TeamState state = team(false);
		state.foodLevel = 57;
		state.saturation = 20.0F;

		StatMirror.applyDeltas(state, 20.0F, 0.0F, food(0, 0.0F), false);

		assertEquals(20, state.foodLevel, "넘친 몫은 버린다(사람이 정했다)");
		assertEquals(20.0F, state.saturation, 1.0e-6F);
	}

	@Test
	void 증강이_없으면_예전처럼_20_까지다() {
		TeamState state = team(false);
		state.foodLevel = 18;

		StatMirror.applyDeltas(state, 20.0F, 0.0F, food(8, 0.0F), false);

		assertEquals(20, state.foodLevel);
	}

	@Test
	void 먹방_팀은_남는_허기_완충을_거치지_않는다() {
		UUID teamId = UUID.randomUUID();

		assertEquals(8, FoodOverflowBuffer.bypass(teamId, 8));
		assertEquals(-3, FoodOverflowBuffer.bypass(teamId, -3), "줄어든 허기를 완충이 메우지 않는다");
	}

	@Test
	void 먹방을_잃은_첫_바퀴에_완충은_넘친_몫을_쌓지_않고_20_으로_내린다() {
		// 풀이 57 인 채 완충 계산에 들어와도 20 으로 내려가고, 37 이 완충으로 옮겨 가지 않는다.
		FoodOverflowBuffer.Result result = FoodOverflowBuffer.calculate(57, 0, 0, 0.0F);

		assertEquals(20, result.foodLevel());
		assertEquals(0.0F, result.reserve(), 1.0e-6F);
	}
}
