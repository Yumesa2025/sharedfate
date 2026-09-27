package com.sharedfate.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 하나의 광역 공격에 여러 팀원이 닿았을 때 공유 체력이 한 번만 깎이는지 본다.
 *
 * <p>드래곤 날갯짓이 대표적이다. 몸통 접촉은 5, 머리는 10 인데 공유 체력은 20 이다. 네 명이
 * 함께 붙은 상태로 한 번 날갯짓하면 합산 20 — 팀 체력 전부가 한 번에 날아간다. 좀비 두 마리에게
 * 따로 맞은 것과도, 공유된 독이 전원에게 도는 것과도 다른 세 번째 경우다.
 *
 * <p>실제 게임에서는 {@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} 진입점에서
 * {@link SharedAreaDamage} 에게 물어보고 중복이면 피해를 통째로 버린다. 여기서는 월드 없이 그
 * 흐름만 흉내 낸다 — 각 팀원에 대해 판정을 물어보고, 막힌 팀원은 체력이 줄지 않은 것으로 두고
 * {@link StatMirror#fold} 에 넣는다.
 */
class SharedAreaDamageTest {
	private static final UUID TEAM_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000000");
	private static final UUID OTHER_TEAM = UUID.fromString("bbbbbbbb-0000-0000-0000-0000000000ff");
	private static final UUID ARA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
	private static final UUID BORA = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
	private static final UUID CHAE = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000003");
	private static final UUID DAON = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000004");
	private static final List<UUID> MEMBERS = List.of(ARA, BORA, CHAE, DAON);

	private static final UUID DRAGON = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
	private static final UUID CREEPER = UUID.fromString("dddddddd-0000-0000-0000-000000000002");
	private static final UUID ZOMBIE = UUID.fromString("dddddddd-0000-0000-0000-000000000003");
	private static final UUID SKELETON = UUID.fromString("dddddddd-0000-0000-0000-000000000004");

	private static final String MOB_ATTACK = "minecraft:mob_attack";
	private static final String EXPLOSION = "minecraft:explosion";
	private static final String DRAGON_BREATH = "minecraft:dragon_breath";
	private static final String FALL = "minecraft:fall";

	/** 드래곤 몸통 접촉 피해. */
	private static final float WING = 5.0F;

	@AfterEach
	void reset() {
		SharedAreaDamage.clearState();
	}

	@Test
	void 네_명이_같은_날갯짓에_맞아도_공유_체력은_한_번만_깎인다() {
		StatMirror.StatDelta folded = StatMirror.fold(
				hitAll(MEMBERS, TEAM_ID, DRAGON, MOB_ATTACK, WING, 100L));

		assertEquals(-WING, folded.healthLoss(), 1.0e-6F,
				"하나의 날갯짓인데 네 명 몫이 합산되면 팀 체력 20 이 한 번에 사라진다");
	}

	@Test
	void 서로_다른_몹에게_맞으면_그대로_합산한다() {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		observed.add(hit(ARA, TEAM_ID, ZOMBIE, MOB_ATTACK, 3.0F, 100L));
		observed.add(hit(BORA, TEAM_ID, SKELETON, MOB_ATTACK, 2.0F, 100L));

		StatMirror.StatDelta folded = StatMirror.fold(observed);

		assertEquals(-5.0F, folded.healthLoss(), 1.0e-6F,
				"좀비와 스켈레톤은 서로 다른 공격이다. 팀은 진짜로 두 번 맞았다");
	}

	@Test
	void 같은_몹이라도_피해_종류가_다르면_따로_센다() {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		observed.add(hit(ARA, TEAM_ID, DRAGON, MOB_ATTACK, WING, 100L));
		observed.add(hit(BORA, TEAM_ID, DRAGON, DRAGON_BREATH, 2.0F, 100L));

		StatMirror.StatDelta folded = StatMirror.fold(observed);

		assertEquals(-(WING + 2.0F), folded.healthLoss(), 1.0e-6F,
				"날개에 닿은 것과 브레스를 밟은 것은 다른 공격이다");
	}

	@Test
	void 틱이_다르면_같은_공격원이라도_다시_센다() {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		observed.add(hit(ARA, TEAM_ID, DRAGON, MOB_ATTACK, WING, 100L));
		observed.add(hit(BORA, TEAM_ID, DRAGON, MOB_ATTACK, WING, 140L));

		StatMirror.StatDelta folded = StatMirror.fold(observed);

		assertEquals(-(WING * 2), folded.healthLoss(), 1.0e-6F,
				"두 번 날갯짓한 것이다. 한 번으로 접으면 드래곤이 영원히 아프지 않다");
	}

	@Test
	void 크리퍼_폭발도_한_번으로_접는다() {
		StatMirror.StatDelta folded = StatMirror.fold(
				hitAll(MEMBERS, TEAM_ID, CREEPER, EXPLOSION, 6.0F, 100L));

		assertEquals(-6.0F, folded.healthLoss(), 1.0e-6F,
				"폭발 하나에 네 명이 휩쓸린 것도 같은 경우다");
	}

	@Test
	void 가해자가_없는_피해는_접지_않는다() {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		observed.add(hit(ARA, TEAM_ID, null, FALL, 4.0F, 100L));
		observed.add(hit(BORA, TEAM_ID, null, FALL, 4.0F, 100L));

		StatMirror.StatDelta folded = StatMirror.fold(observed);

		assertEquals(-8.0F, folded.healthLoss(), 1.0e-6F,
				"둘이 각자 떨어진 것이지 하나의 공격이 아니다");
	}

	@Test
	void 다른_팀은_서로의_판정에_끼어들지_않는다() {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		observed.add(hit(ARA, TEAM_ID, DRAGON, MOB_ATTACK, WING, 100L));
		observed.add(hit(BORA, OTHER_TEAM, DRAGON, MOB_ATTACK, WING, 100L));

		StatMirror.StatDelta folded = StatMirror.fold(observed);

		assertEquals(-(WING * 2), folded.healthLoss(), 1.0e-6F,
				"같은 드래곤에게 맞았어도 팀이 다르면 각자의 공유 풀이다");
	}

	@Test
	void 팀이_없는_사람은_판정하지_않는다() {
		assertFalse(SharedAreaDamage.isDuplicateAreaDamage(null, DRAGON, MOB_ATTACK, 100L),
				"팀이 없으면 공유 풀도 없으므로 접을 이유가 없다");
		assertFalse(SharedAreaDamage.isDuplicateAreaDamage(null, DRAGON, MOB_ATTACK, 100L),
				"두 번 물어도 마찬가지다");
	}

	@Test
	void 첫_번째는_통과하고_두_번째부터_막힌다() {
		assertFalse(SharedAreaDamage.isDuplicateAreaDamage(TEAM_ID, DRAGON, MOB_ATTACK, 100L),
				"첫 사람은 실제로 맞아야 한다");
		assertTrue(SharedAreaDamage.isDuplicateAreaDamage(TEAM_ID, DRAGON, MOB_ATTACK, 100L),
				"두 번째부터가 중복이다");
		assertTrue(SharedAreaDamage.isDuplicateAreaDamage(TEAM_ID, DRAGON, MOB_ATTACK, 100L),
				"세 번째도 마찬가지다");
	}

	@Test
	void 오래된_기록은_틱이_지나면_쌓이지_않는다() {
		for (long tick = 0; tick < 500; tick++) {
			SharedAreaDamage.isDuplicateAreaDamage(TEAM_ID, DRAGON, MOB_ATTACK, tick);
		}

		assertEquals(1, SharedAreaDamage.trackedKeyCount(),
				"틱이 넘어가면 지난 틱 기록은 버려야 한다. 안 그러면 전투 내내 쌓인다");
	}

	/** 한 공격에 여러 사람이 닿은 상황. 막힌 사람의 체력 변화는 0 이다. */
	private static List<StatMirror.PlayerDelta> hitAll(List<UUID> members, UUID teamId,
			UUID attacker, String damageType, float amount, long tick) {
		List<StatMirror.PlayerDelta> observed = new ArrayList<>();
		for (UUID ignored : members) {
			observed.add(hit(ignored, teamId, attacker, damageType, amount, tick));
		}
		return observed;
	}

	private static StatMirror.PlayerDelta hit(UUID member, UUID teamId, UUID attacker,
			String damageType, float amount, long tick) {
		boolean blocked = SharedAreaDamage.isDuplicateAreaDamage(teamId, attacker, damageType, tick);
		float health = blocked ? 0.0F : -amount;
		return new StatMirror.PlayerDelta(health, 0.0F, 0.0F, 0, 0.0F, 0L);
	}
}
