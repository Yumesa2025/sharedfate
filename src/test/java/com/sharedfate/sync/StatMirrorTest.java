package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatMirrorTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@Test
	void 여러_팀원의_델타를_공유_풀에_합산하고_범위를_제한한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.health = 35.0F;
		state.foodLevel = 18;
		state.saturation = 4.0F;
		state.totalExperience = 100;

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(-12.0F, 0.0F, 0.0F, 0.0F, 5, 30.0F, -150), true);

		assertEquals(23.0F, state.health);
		assertEquals(20, state.foodLevel);
		assertEquals(20.0F, state.saturation);
		assertEquals(0, state.totalExperience);
	}

	@Test
	void 체력은_0과_설정_최대치_사이로_제한한다() {
		TeamState state = TeamState.fresh(40.0F);

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 20.0F, 0.0F, 0.0F, 0, 0.0F, 0), true);
		assertEquals(40.0F, state.health);

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(-100.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0), true);
		assertEquals(0.0F, state.health);
	}

	@Test
	void 경험치_공유를_끄면_경험치_델타를_무시한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.totalExperience = 20;

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 100), false);

		assertEquals(20, state.totalExperience);
	}

	/**
	 * 오버플로 방지와 레벨 상한은 같은 자리에서 한다. 상한이 생긴 뒤로는 상한이 먼저 걸리므로
	 * 결과가 {@code Integer.MAX_VALUE} 가 아니라 상한 경험치다. 그래도 <b>음수로 돌지 않는지</b>
	 * 는 여기서 계속 본다 — 자르는 차례가 뒤집히면 더한 값이 먼저 넘쳐 음수가 된다.
	 */
	@Test
	void 개인_경험치를_공유_풀에_합칠_때_오버플로하지_않고_상한에서_멈춘다() {
		TeamState state = TeamState.fresh(40.0F);
		state.totalExperience = Integer.MAX_VALUE - 2;

		StatMirror.addSharedExperience(state, 10);

		assertEquals(StatMirror.experienceCap(state), state.totalExperience);
	}

	// ------------------------------------------------------------------ 레벨 상한

	@Test
	void 레벨_상한은_기본_40_이고_시련을_켜면_50_이다() {
		TeamState plain = TeamState.fresh(40.0F);
		TeamState trials = TeamState.fresh(40.0F);
		trials.dragonTrialsEnabled = true;

		assertEquals(40, StatMirror.BASE_LEVEL_CAP);
		assertEquals(50, StatMirror.TRIAL_LEVEL_CAP);
		assertEquals(40, StatMirror.levelCap(plain));
		assertEquals(50, StatMirror.levelCap(trials));
	}

	/** 상한 경험치는 그 레벨의 <b>막대가 빈</b> 지점이다. 까닭은 {@code experienceCap} 에 있다. */
	@Test
	void 상한_경험치는_상한_레벨의_빈_막대_지점이다() {
		TeamState plain = TeamState.fresh(40.0F);
		TeamState trials = TeamState.fresh(40.0F);
		trials.dragonTrialsEnabled = true;

		assertEquals(2920, StatMirror.experienceCap(plain));
		assertEquals(5345, StatMirror.experienceCap(trials));
		assertEquals(StatMirror.experiencePointsFor(40, 0.0F), StatMirror.experienceCap(plain));
		assertEquals(StatMirror.experiencePointsFor(50, 0.0F), StatMirror.experienceCap(trials));
	}

	@Test
	void 상한에_닿으면_더_쌓이지_않고_넘친_몫은_버린다() {
		TeamState state = TeamState.fresh(40.0F);
		state.totalExperience = StatMirror.experienceCap(state) - 5;

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 9_999), true);
		assertEquals(StatMirror.experienceCap(state), state.totalExperience);

		// 한 번 더 받아도 쌓이지 않는다. 쌓아 두면 상한이 풀리는 날 한꺼번에 터진다.
		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 9_999), true);
		assertEquals(StatMirror.experienceCap(state), state.totalExperience);
	}

	@Test
	void 시련을_켠_팀은_40_을_넘어_50_까지_오른다() {
		TeamState trials = TeamState.fresh(40.0F);
		trials.dragonTrialsEnabled = true;

		StatMirror.applyDeltas(trials, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 99_999), true);

		assertEquals(StatMirror.experiencePointsFor(50, 0.0F), trials.totalExperience);
	}

	@Test
	void 인벤토리_유지_사망은_공유_경험치를_보존한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.health = 0.0F;
		state.foodLevel = 2;
		state.saturation = 0.0F;
		state.xpLevel = 7;
		state.xpProgress = 0.5F;
		state.totalExperience = 100;

		state.resetAfterDeath(40.0F, true);

		assertEquals(40.0F, state.health);
		assertEquals(20, state.foodLevel);
		assertEquals(5.0F, state.saturation);
		assertEquals(7, state.xpLevel);
		assertEquals(0.5F, state.xpProgress);
		assertEquals(100, state.totalExperience);
	}

	@Test
	void 현재_경험치는_레벨과_진행도에서_환산한다() {
		assertEquals(0, StatMirror.experiencePointsFor(0, 0.0F));
		assertEquals(315, StatMirror.experiencePointsFor(15, 0.0F));
		assertEquals(352, StatMirror.experiencePointsFor(16, 0.0F));
		assertEquals(1395, StatMirror.experiencePointsFor(30, 0.0F));
		assertEquals(1507, StatMirror.experiencePointsFor(31, 0.0F));
		assertEquals(373, StatMirror.experiencePointsFor(16, 0.5F));
	}

	@Test
	void 흡수_보호막은_중복_부여하지_않고_동시_소비는_합산한다() {
		TeamState state = TeamState.fresh(40.0F);
		StatMirror.AbsorptionDelta combined = new StatMirror.AbsorptionDelta(0.0F, 0.0F);
		combined = StatMirror.mergeAbsorptionDelta(combined, 4.0F);
		combined = StatMirror.mergeAbsorptionDelta(combined, 4.0F);
		combined = StatMirror.mergeAbsorptionDelta(combined, -2.0F);
		combined = StatMirror.mergeAbsorptionDelta(combined, -2.0F);

		assertEquals(4.0F, combined.gain());
		assertEquals(-4.0F, combined.loss());

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(
						0.0F, 0.0F, 0.0F, combined.gain(), 0, 0.0F, 0), true);
		assertEquals(4.0F, state.absorption);

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(
						0.0F, 0.0F, combined.loss(), 0.0F, 0, 0.0F, 0), true);
		assertEquals(0.0F, state.absorption);
	}

	@Test
	void 동시에_공유_흡수량보다_많이_소비하면_초과분은_체력에서_차감한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.absorption = 2.0F;

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(-4.0F, 0.0F, -4.0F, 0.0F, 0, 0.0F, 0), true);

		assertEquals(0.0F, state.absorption);
		assertEquals(34.0F, state.health);
	}

	@Test
	void 로컬_보호막이_없는_팀원의_체력_피해도_공유_흡수에서_먼저_차감한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.absorption = 4.0F;

		StatMirror.applyDeltas(state, 40.0F, 4.0F,
				new StatMirror.StatDelta(-2.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0), true);

		assertEquals(2.0F, state.absorption);
		assertEquals(40.0F, state.health);
	}

	@Test
	void 흡수_효과_만료로_최대치가_사라지면_일반_체력_피해로_보지_않는다() {
		TeamState state = TeamState.fresh(40.0F);
		state.absorption = 4.0F;

		StatMirror.applyDeltas(state, 40.0F, 0.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, 0.0F, 0.0F, 0, 0.0F, 0), true);

		assertEquals(0.0F, state.absorption);
		assertEquals(40.0F, state.health);
	}

	@Test
	void 흡수_최대치_감소와_실제_소비를_분리한다() {
		float firstConsumption = StatMirror.consumedAbsorption(8.0F, 2.0F, 4.0F);
		assertEquals(2.0F, firstConsumption);
		TeamState first = TeamState.fresh(40.0F);
		first.absorption = 8.0F;
		StatMirror.applyDeltas(first, 40.0F, 4.0F,
				new StatMirror.StatDelta(0.0F, 0.0F, -firstConsumption, 0.0F, 0, 0.0F, 0), true);
		assertEquals(2.0F, first.absorption);
		assertEquals(40.0F, first.health);

		float secondConsumption = StatMirror.consumedAbsorption(8.0F, 0.0F, 4.0F);
		assertEquals(4.0F, secondConsumption);
		TeamState second = TeamState.fresh(40.0F);
		second.absorption = 8.0F;
		StatMirror.applyDeltas(second, 40.0F, 4.0F,
				new StatMirror.StatDelta(-2.0F, 0.0F, -secondConsumption, 0.0F, 0, 0.0F, 0), true);
		assertEquals(0.0F, second.absorption);
		assertEquals(38.0F, second.health);
	}

	@Test
	void 흡수_효과의_자연_만료는_피해_소비가_아니다() {
		assertEquals(0.0F, StatMirror.consumedAbsorption(4.0F, 0.0F, 0.0F));
	}

	@Test
	void 최대_체력이_줄어_잘린_몫은_피해가_아니다() {
		// 상한 20 → 10. 체력 18 이던 사람이 10 으로 잘린다. 맞은 것이 아니므로 0.
		assertEquals(0.0F, StatMirror.healthDelta(18.0F, 10.0F, 10.0F));
		// 상한이 그대로면 평소처럼 관측한다.
		assertEquals(-5.0F, StatMirror.healthDelta(20.0F, 15.0F, 20.0F));
		// 이미 새 상한보다 낮았으면 잘릴 것이 없다.
		assertEquals(0.0F, StatMirror.healthDelta(6.0F, 6.0F, 10.0F));
		// 상한이 오르는 것만으로 체력이 차오르지는 않는다.
		assertEquals(0.0F, StatMirror.healthDelta(10.0F, 10.0F, 30.0F));
		// 회복은 그대로 회복이다.
		assertEquals(4.0F, StatMirror.healthDelta(10.0F, 14.0F, 20.0F));
	}

	@Test
	void 상한이_줄어든_틱에_받은_진짜_피해는_그대로_센다() {
		// 상한 20 → 10 인 틱에 3 을 맞아 체력이 7 이 됐다. 잘린 8 은 빼고 −3 만 남아야 한다.
		assertEquals(-3.0F, StatMirror.healthDelta(18.0F, 7.0F, 10.0F));
	}

	@Test
	void 고행자로_상한이_반토막_나도_3인_팀이_즉사하지_않는다() {
		// 「고행자」가 최대 체력을 10 으로 고정하면 팀원 셋이 각자 18 → 10 으로 잘린다.
		// 그 자름을 피해로 세면 8 이 세 번 빠져 18 − 24 = 0, 즉 전멸이었다.
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L)));
		assertEquals(0.0F, folded.healthLoss());

		TeamState state = TeamState.fresh(20.0F);
		state.health = 18.0F;
		// 상한이 이미 10 으로 내려간 뒤의 첫 점검이다.
		StatMirror.applyDeltas(state, 10.0F, 0.0F, folded, true);

		// 죽지 않고 "가득 찬 10" 이 되어야 한다.
		assertEquals(10.0F, state.health);
	}

	@Test
	void 상한이_반토막_난_뒤에도_실제_피해는_공유_풀에서_빠진다() {
		// 위와 같은 상황에서 한 명만 좀비에게 3 을 더 맞았다.
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 7.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(18.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L)));
		assertEquals(-3.0F, folded.healthLoss());

		TeamState state = TeamState.fresh(20.0F);
		state.health = 18.0F;
		StatMirror.applyDeltas(state, 10.0F, 0.0F, folded, true);

		assertEquals(10.0F, state.health);
	}

	@Test
	void 상한이_줄었을_때_2인_팀도_만피에서_죽지_않는다() {
		// 2인 팀은 만피(20)에서 20 − 2×10 = 0 이 되어 죽었다.
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(20.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(
						StatMirror.healthDelta(20.0F, 10.0F, 10.0F), 0.0F, 0.0F, 0, 0.0F, 0L)));

		TeamState state = TeamState.fresh(20.0F);
		state.health = 20.0F;
		StatMirror.applyDeltas(state, 10.0F, 0.0F, folded, true);

		assertEquals(10.0F, state.health);
	}

	@Test
	void 서로_다른_원인의_동시_피해는_그대로_합산한다() {
		// 아라는 좀비에게 3, 보라는 스켈레톤에게 4. 팀이 진짜로 두 번 맞았으니 둘 다 센다.
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(-3.0F, 0.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(-4.0F, 0.0F, 0.0F, 0, 0.0F, 0L)));

		assertEquals(-7.0F, folded.healthLoss());
		assertEquals(0.0F, folded.healthGain());
	}

	@Test
	void 접을_때_체력_손실과_회복을_따로_담는다() {
		// 한 명은 맞고 한 명은 재생으로 회복한 틱. 상쇄하지 않고 각각 모은다.
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(-5.0F, 0.0F, 0.0F, -1, -0.5F, 3L),
				new StatMirror.PlayerDelta(2.0F, 0.0F, 0.0F, 0, 0.0F, 4L)));

		assertEquals(-5.0F, folded.healthLoss());
		assertEquals(2.0F, folded.healthGain());
		assertEquals(-1, folded.foodLevel());
		assertEquals(-0.5F, folded.saturation());
		assertEquals(7L, folded.totalExperience());
	}

	@Test
	void 접을_때_흡수_획득은_최댓값_소비는_합산으로_모은다() {
		StatMirror.StatDelta folded = StatMirror.fold(java.util.List.of(
				new StatMirror.PlayerDelta(0.0F, 4.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(0.0F, 4.0F, 0.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(0.0F, -2.0F, 2.0F, 0, 0.0F, 0L),
				new StatMirror.PlayerDelta(0.0F, -2.0F, 2.0F, 0, 0.0F, 0L)));

		assertEquals(4.0F, folded.absorptionGain());
		assertEquals(-4.0F, folded.absorptionLoss());
	}
}
