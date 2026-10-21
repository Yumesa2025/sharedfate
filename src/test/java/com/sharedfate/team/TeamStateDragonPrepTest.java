package com.sharedfate.team;

import com.sharedfate.TestBootstrap;
import com.sharedfate.sync.TrialExperience;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤전 대비」(0.44.0-dev)가 팀 저장에 남기는 것 — 맹세를 썼는가 · 경험치 곡선 · 45·50 구간.
 */
class TeamStateDragonPrepTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static CompoundTag encode(TeamState state) {
		return (CompoundTag) TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
	}

	private static TeamState decode(CompoundTag tag) {
		return TeamState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	@Test
	void 맹세를_쓴_것이_저장되고_회차가_바뀌면_내려간다() {
		TeamState state = TeamState.fresh(20.0F);
		assertFalse(state.undyingOathSpent);
		assertFalse(encode(state).contains("undyingOath"), "안 쓴 팀은 저장 형태가 예전과 같다");

		state.undyingOathSpent = true;
		assertTrue(decode(encode(state)).undyingOathSpent, "서버를 껐다 켜도 절반이 유지된다");

		state.resetAfterDeath(20.0F, false);
		assertFalse(state.undyingOathSpent, "다음 회차에는 다시 쓸 수 있다");
	}

	@Test
	void 시련_팀의_사십오_구간은_저장을_읽어도_사십으로_내려가지_않는다() {
		TeamState trials = TeamState.fresh(20.0F);
		trials.dragonTrialsEnabled = true;
		trials.perksEnabled = true;
		trials.lastPerkMilestone = 45;
		assertEquals(45, decode(encode(trials)).lastPerkMilestone);

		TeamState plain = TeamState.fresh(20.0F);
		plain.perksEnabled = true;
		plain.lastPerkMilestone = 45;
		assertEquals(40, decode(encode(plain)).lastPerkMilestone, "시련을 끈 팀의 끝은 40");
	}

	@Test
	void 옛_곡선으로_적힌_시련_팀은_보이던_레벨을_지키며_옮겨진다() {
		TeamState old = TeamState.fresh(20.0F);
		old.dragonTrialsEnabled = true;
		old.xpLevel = 42;
		old.xpProgress = 0.5F;
		// 0.43 이하가 적은 값 — 바닐라 곡선의 42 레벨 반.
		old.totalExperience = TrialExperience.pointsFor(42, 0.5F, false);
		CompoundTag tag = encode(old);
		tag.remove("xpCurve");

		TeamState loaded = decode(tag);
		assertEquals(TrialExperience.pointsFor(42, 0.5F, true), loaded.totalExperience);
		assertEquals(42, TrialExperience.levelFor(loaded.totalExperience, true), "레벨이 50 으로 튀지 않는다");
	}

	@Test
	void 새_곡선으로_적힌_저장은_다시_옮기지_않는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.dragonTrialsEnabled = true;
		state.xpLevel = 44;
		state.xpProgress = 0.2F;
		state.totalExperience = 3100;
		CompoundTag tag = encode(state);
		assertEquals(1, tag.getIntOr("xpCurve", 0));
		assertEquals(3100, decode(tag).totalExperience);
	}

	@Test
	void 시련을_끈_팀과_사십_아래는_손대지_않는다() {
		TeamState plain = TeamState.fresh(20.0F);
		plain.xpLevel = 42;
		plain.totalExperience = 3400;
		CompoundTag tag = encode(plain);
		assertFalse(tag.contains("xpCurve"));
		assertEquals(3400, decode(tag).totalExperience);

		TeamState low = TeamState.fresh(20.0F);
		low.dragonTrialsEnabled = true;
		low.xpLevel = 30;
		low.totalExperience = 1400;
		CompoundTag lowTag = encode(low);
		lowTag.remove("xpCurve");
		assertEquals(1400, decode(lowTag).totalExperience);
	}
}
