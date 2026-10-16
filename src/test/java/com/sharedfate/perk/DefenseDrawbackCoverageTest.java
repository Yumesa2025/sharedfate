package com.sharedfate.perk;

import com.mojang.serialization.Codec;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.NoDefenseDrawbacksEffect;
import com.sharedfate.perk.effect.OnTeamHurtEffect;
import com.sharedfate.perk.effect.ShieldFallImmunityEffect;
import com.sharedfate.perk.effect.SpreadDamageEffect;
import com.sharedfate.sync.SpreadDamageManager;
import com.sharedfate.team.TeamState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 번들 기본 풀의 <b>방어 증강 전부</b>를 놓고, 세트 「방어 3단계」가 대가를 끄는지 시나리오로 확인한다.
 *
 * <p>사람이 게임에서 느낀 것은 「세트가 켜진 <b>뒤에</b> 고른 방어 증강은 대가가 안 사라지는 것
 * 같다」였다(2026-10-16). 따져 보니 고른 순서 탓이 아니라, 대가 중 셋이 아예 면제 경로가
 * 없었다 — 「철벽」·「버티는 방패」의 몹 체력, 「버티는 방패」의 방패 열 배 마모, 「완충」의 회복
 * 금지. 그래서 이 시험은 두 가지를 못박는다.
 *
 * <ul>
 *   <li><b>빠진 대가가 없다</b> — 방어 증강마다 끌 수 있는 대가가 하나 이상 있고, 정의에서
 *       대가로 읽히는 모든 것이 아래 {@link #liveDrawbacks} 에 잡힌다.</li>
 *   <li><b>언제든 같은 답이다</b> — 세트가 켜지기 전에 고른 것·뒤에 고른 것·다시 접속한 뒤·서버를
 *       다시 켠 뒤에 모두 대가가 없고, 세트가 풀리면 돌아온다.</li>
 * </ul>
 *
 * <p>판정은 모두 팀 상태에서 그때그때 다시 세어 만든다. 다시 접속하면
 * {@code PerkManager.onPlayerJoin} → {@code refreshPlayer} 가 같은 판정으로 속성을 다시 맞추므로
 * 「같은 팀 상태면 같은 답」이 곧 (c) 다. 서버 재시작 (d) 는 팀 상태가 저장을 지나 다시 읽혀도
 * 같은 답이 나오는지로 확인한다.
 */
class DefenseDrawbackCoverageTest {
	/** 세트를 켜는 데 쓰는 방어 실버 셋. 셋 다 몹에 거는 배율이 없어 몹 체력 계산을 흐리지 않는다. */
	private static final List<String> SET_STARTERS = List.of(
			"sharedfate:deflated_shield", "sharedfate:arrow_guard", "sharedfate:bare_skin_resolve");

	/** 세트가 켜진 <b>뒤에</b> 고르는 방어 증강들. 대가가 효과 안에 묶인 것이 모두 여기 있다. */
	private static final List<String> LATE_PICKS = List.of(
			"sharedfate:shared_suffering", "sharedfate:iron_wall", "sharedfate:unbroken",
			"sharedfate:grounded_guard", "sharedfate:cushion");

	/** 막아 낸 낙하 피해. 방패 마모를 잴 때 쓴다. */
	private static final float BLOCKED_FALL = 5.0F;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void 번들_풀을_올린다() {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	// ------------------------------------------------------------------ 빠진 대가가 없다

	/**
	 * 번들의 방어 증강이 지금 여덟이고, 하나도 빠짐없이 끌 수 있는 대가를 가진다.
	 *
	 * <p>방어 증강이 새로 들어오면 여기서 걸린다. 그 증강의 대가가 어느 경로로 꺼지는지 정한 뒤
	 * 목록과 {@link #liveDrawbacks} 를 함께 고친다.
	 */
	@Test
	void 방어_증강마다_세트가_끌_대가가_있다() {
		List<String> defense = new ArrayList<>();
		for (Perk perk : PerkRegistry.all()) {
			if (perk.hasSetType(PerkSetType.DEFENSE)) {
				defense.add(perk.id());
			}
		}
		List<String> expected = new ArrayList<>(SET_STARTERS);
		expected.addAll(LATE_PICKS);
		assertEquals(expected.stream().sorted().toList(), defense.stream().sorted().toList(),
				"방어 증강 목록이 바뀌었다 — 새 증강의 대가가 꺼지는 경로를 먼저 정할 것");

		TeamState off = teamWith();
		for (String id : defense) {
			off.ownedPerks.clear();
			off.ownedPerks.add(id);
			assertFalse(liveDrawbacks(off).isEmpty(), id + " 에 끌 대가가 하나도 잡히지 않는다");
		}
	}

	// ------------------------------------------------------------------ 시나리오

	/** (a) 세트가 켜지기 전에 고른 것과 (b) 켜진 뒤에 고른 것 — 모두 대가가 없다. */
	@Test
	void 세트_전후에_고른_방어_증강_모두_대가가_없다() {
		// (a) 하나씩 먼저 들고 있다가 세트를 나중에 켠다. 방어가 둘이면 아직 3단계가 아니다.
		for (String id : LATE_PICKS) {
			TeamState before = teamWith(id, SET_STARTERS.get(0));
			assertFalse(NoDefenseDrawbacksEffect.heldBy(before));
			Map<String, Boolean> beforeSet = liveDrawbacks(before);
			assertTrue(beforeSet.values().stream().allMatch(Boolean::booleanValue),
					"세트 전에는 대가가 전부 살아 있어야 한다: " + beforeSet);
			before.ownedPerks.addAll(SET_STARTERS.subList(1, SET_STARTERS.size()));
			assertNoLiveDrawbacks(before, "세트가 켜지기 전에 고른 " + id);
		}

		// 세트를 먼저 켜고(b), 하나씩 고를 때마다 확인한다.
		TeamState after = teamWith();
		after.ownedPerks.addAll(SET_STARTERS);
		assertTrue(NoDefenseDrawbacksEffect.heldBy(after));
		for (String id : LATE_PICKS) {
			after.ownedPerks.add(id);
			assertNoLiveDrawbacks(after, "세트가 켜진 뒤에 고른 " + id);
		}
	}

	/** (c) 다시 접속한 뒤와 (d) 서버를 다시 켠 뒤 — 저장을 지나 다시 읽힌 팀 상태도 같은 답이다. */
	@Test
	void 저장을_지나_다시_읽어도_대가가_없다() {
		TeamState state = teamWith();
		state.ownedPerks.addAll(SET_STARTERS);
		state.ownedPerks.addAll(LATE_PICKS);
		assertNoLiveDrawbacks(state, "저장 전");

		TeamState reloaded = roundTrip(state);
		assertEquals(state.ownedPerks, reloaded.ownedPerks);
		assertNoLiveDrawbacks(reloaded, "저장을 지나 다시 읽은 뒤");

		// 정의를 다시 읽어도(서버 재시작) 대가 표시가 새 효과 객체에 다시 붙는다.
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		assertNoLiveDrawbacks(reloaded, "정의를 다시 읽은 뒤");
	}

	/** 세트가 사라지면(방어가 셋에서 둘로) 모든 대가가 돌아온다. */
	@Test
	void 세트가_풀리면_모든_대가가_돌아온다() {
		TeamState state = teamWith();
		state.ownedPerks.addAll(SET_STARTERS);
		state.ownedPerks.addAll(LATE_PICKS);
		assertNoLiveDrawbacks(state, "세트가 켜진 동안");

		// 방어 증강을 잃어 3단계 아래로 떨어지는 경우(「환골탈태」가 보유 목록을 갈아엎는 등).
		// 방어가 둘만 남으면 세트가 풀린다.
		state.ownedPerks.clear();
		state.ownedPerks.addAll(List.of("sharedfate:grounded_guard", "sharedfate:cushion"));
		assertFalse(NoDefenseDrawbacksEffect.heldBy(state));
		Map<String, Boolean> live = liveDrawbacks(state);
		assertTrue(live.values().stream().allMatch(Boolean::booleanValue), "대가가 돌아와야 한다: " + live);

		// 증강 자체를 끈 팀도 세트가 없는 것과 같다.
		TeamState disabled = teamWith();
		disabled.ownedPerks.addAll(SET_STARTERS);
		disabled.ownedPerks.add("sharedfate:cushion");
		disabled.perksEnabled = false;
		assertFalse(PerkDrawbacks.builtInWaived(disabled, SpreadDamageEffect.class));
	}

	// ------------------------------------------------------------------ 하나씩

	/** 「완충」 — 세트가 없으면 나뉘는 동안 자연 회복이 막히고, 세트가 있으면 풀린다. */
	@Test
	void 완충의_자연회복_금지가_세트로_풀린다() {
		TeamState alone = teamWith("sharedfate:cushion");
		assertTrue(SpreadDamageManager.blocksNaturalRegen(true, alone), "나뉘는 동안은 막힌다");
		assertFalse(SpreadDamageManager.blocksNaturalRegen(false, alone), "나뉠 몫이 없으면 막지 않는다");

		alone.ownedPerks.addAll(SET_STARTERS);
		assertFalse(SpreadDamageManager.blocksNaturalRegen(true, alone), "세트면 나뉘는 동안에도 회복된다");
	}

	/** 「버티는 방패」 — 방패 마모가 열 배에서 바닐라와 같은 한 배로 내려간다. */
	@Test
	void 버티는_방패의_마모가_바닐라로_돌아간다() {
		assertEquals(50, PerkDamage.fallBlockDurabilityCost(BLOCKED_FALL, false));
		assertEquals(5, PerkDamage.fallBlockDurabilityCost(BLOCKED_FALL, true));
		assertEquals(1, PerkDamage.fallBlockDurabilityCost(0.2F, true), "막았으면 적어도 하나는 닳는다");

		TeamState state = teamWith("sharedfate:grounded_guard");
		assertFalse(PerkDrawbacks.builtInWaived(state, ShieldFallImmunityEffect.class));
		state.ownedPerks.addAll(SET_STARTERS);
		assertTrue(PerkDrawbacks.builtInWaived(state, ShieldFallImmunityEffect.class));
	}

	/** 「철벽」·「버티는 방패」 — 세트를 켠 팀 몫의 몹 체력 배율이 1 로 돌아간다. */
	@Test
	void 몹_체력_배율이_세트를_켠_팀_몫에서_빠진다() {
		TeamState state = teamWith("sharedfate:iron_wall", "sharedfate:grounded_guard");
		assertEquals(1.5 * 1.15, MobPerkModifiers.teamHealthMultiplier(state, EntityTypes.ZOMBIE, true),
				1.0e-9);
		// 엔더 드래곤은 원래 빠져 있다.
		assertEquals(1.0, MobPerkModifiers.teamHealthMultiplier(state, EntityTypes.ENDER_DRAGON, true),
				1.0e-9);

		state.ownedPerks.addAll(SET_STARTERS);
		assertEquals(1.0, MobPerkModifiers.teamHealthMultiplier(state, EntityTypes.ZOMBIE, true), 1.0e-9);
	}

	/** 그 효과 형을 가진 증강이 없으면 면제할 대가도 없다. 팀이 없어도 거짓이다. */
	@Test
	void 그_형을_가진_증강이_없으면_묶인_대가도_없다() {
		TeamState none = teamWith(SET_STARTERS.toArray(String[]::new));
		assertFalse(PerkDrawbacks.builtInWaived(none, SpreadDamageEffect.class),
				"그 형을 가진 증강이 없으면 면제할 것도 없다");
		assertFalse(PerkDrawbacks.builtInWaived(null, SpreadDamageEffect.class));
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 이 팀에서 방어 증강의 대가 하나하나가 <b>지금 살아 있는가</b>. 열쇠는 「증강 id · 대가」다.
	 *
	 * <p>게임이 실제로 갈라지는 판정을 그대로 부른다.
	 * <ul>
	 *   <li>표시가 붙은 최상위 효과 — {@code PerkManager.refreshPlayer}·{@code multiplier}·
	 *       {@code PerkDamage}·{@code MobPerkModifiers} 가 보는 {@link PerkDrawbacks.Waiver#waives}</li>
	 *   <li>{@code on_team_hurt} 하위 — {@code TemporaryPerkGrants.grant} 처럼 부모 없이 묻는다</li>
	 *   <li>「완충」의 자연 회복 금지 — {@link SpreadDamageManager#blocksNaturalRegen(boolean, TeamState)}</li>
	 *   <li>「버티는 방패」의 방패 마모 — {@link PerkDamage#fallBlockDurabilityCost}</li>
	 * </ul>
	 */
	private static Map<String, Boolean> liveDrawbacks(TeamState state) {
		Map<String, Boolean> live = new LinkedHashMap<>();
		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		for (String id : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(id).orElseThrow();
			if (!perk.hasSetType(PerkSetType.DEFENSE)) {
				continue;
			}
			List<PerkEffect> effects = perk.effects();
			for (int i = 0; i < effects.size(); i++) {
				PerkEffect effect = effects.get(i);
				if (PerkDrawbacks.isDrawback(effect)) {
					live.put(id + " · " + i + "번 효과", !waiver.waives(perk, effect));
				}
				if (effect instanceof OnTeamHurtEffect onHurt) {
					for (PerkEffect child : onHurt.effects()) {
						if (PerkDrawbacks.isDrawback(child)) {
							live.put(id + " · 피격 반응 하위", !waiver.waives(null, child));
						}
					}
				}
				if (effect instanceof ConditionalEffect conditional) {
					for (PerkEffect child : conditional.children()) {
						assertFalse(PerkDrawbacks.isDrawback(child), "조건부 하위의 표시는 읽지 않는다");
					}
				}
				if (effect instanceof SpreadDamageEffect) {
					live.put(id + " · 자연 회복 금지", SpreadDamageManager.blocksNaturalRegen(true, state));
				}
				if (effect instanceof ShieldFallImmunityEffect) {
					boolean waived = PerkDrawbacks.builtInWaived(state, ShieldFallImmunityEffect.class);
					live.put(id + " · 방패 열 배 마모",
							PerkDamage.fallBlockDurabilityCost(BLOCKED_FALL, waived) > Math.round(BLOCKED_FALL));
				}
			}
		}
		return live;
	}

	private static void assertNoLiveDrawbacks(TeamState state, String when) {
		assertTrue(NoDefenseDrawbacksEffect.heldBy(state), when + ": 세트가 켜져 있어야 한다");
		Map<String, Boolean> live = liveDrawbacks(state);
		assertFalse(live.isEmpty());
		live.forEach((name, alive) -> assertFalse(alive, when + ": " + name + " 가 남아 있다"));
		assertEquals(1.0, MobPerkModifiers.teamHealthMultiplier(state, EntityTypes.ZOMBIE, true), 1.0e-9,
				when + ": 몹 체력 배율이 남아 있다");
	}

	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}

	/** 월드 저장과 같은 길로 팀 상태를 썼다가 다시 읽는다. */
	private static TeamState roundTrip(TeamState state) {
		Codec<TeamState> codec = TeamState.CODEC;
		CompoundTag tag = (CompoundTag) codec.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		return codec.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}
}
