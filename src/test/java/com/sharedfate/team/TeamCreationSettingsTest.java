package com.sharedfate.team;

import com.sharedfate.TestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팀을 만들 때만 정하는 설정들.
 *
 * <p>여섯 항목 모두 <b>만든 뒤에는 바꿀 수 없으므로</b>, 만드는 순간에 정확히 새겨지는지와
 * 막혔을 때 사람에게 무엇이 나가는지가 이 시험의 전부다.
 */
class TeamCreationSettingsTest {

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

	// ------------------------------------------------------------------ 기본값

	@Test
	void 아무것도_안_적고_만든_팀은_증강이_켜져_있다() {
		assertTrue(TeamCreationSettings.defaults(20.0F).perksEnabled());
		assertTrue(TeamCreationSettings.DEFAULT_PERKS_ENABLED);
	}

	@Test
	void 기본값에서_알림_둘과_난이도_상승은_꺼져_있다() {
		TeamCreationSettings settings = TeamCreationSettings.defaults(20.0F);

		assertFalse(settings.damageAlertEnabled());
		assertFalse(settings.deathAlertEnabled());
		assertFalse(settings.difficultyEscalationEnabled(),
				"회차를 통째로 어렵게 만드는 설정은 손으로 켜야 한다");
		assertEquals(20.0F, settings.maxHealth());
	}

	/**
	 * 드래곤 시련은 <b>끈 채로</b> 시작한다.
	 *
	 * <p>2026-09-30 전까지 늘 켜져 있던 것이라, 이 기본값이 참으로 뒤집히면 「팀을 만들 때
	 * 켜 줘야 한다」는 전제로 쓴 화면 문구와 로그가 통째로 거짓이 된다.
	 */
	@Test
	void 기본값에서_드래곤_시련은_꺼져_있다() {
		assertFalse(TeamCreationSettings.defaults(20.0F).dragonTrialsEnabled());
		assertFalse(TeamCreationSettings.DEFAULT_DRAGON_TRIALS);
	}

	@Test
	void 드래곤_시련은_저장을_왕복해도_그대로다() {
		TeamState on = TeamState.fresh(20.0F);
		TeamCreationSettings.defaults(20.0F).withDragonTrials(true).applyTo(on);
		assertTrue(decode(encode(on)).dragonTrialsEnabled);

		TeamState off = TeamState.fresh(20.0F);
		TeamCreationSettings.defaults(20.0F).applyTo(off);
		assertFalse(decode(encode(off)).dragonTrialsEnabled);
	}

	/** 이 항목이 없던 예전 월드는 기본값과 같은 쪽 — 끔 — 으로 읽혀야 한다. */
	@Test
	void 항목이_없는_예전_월드의_팀은_드래곤_시련이_꺼진_것으로_읽힌다() {
		TeamState state = TeamState.fresh(20.0F);
		CompoundTag tag = encode(state);
		tag.remove("dragonTrials");

		assertFalse(decode(tag).dragonTrialsEnabled);
	}

	/** 위치 교환은 <b>켠 채로</b> 시작한다. */
	@Test
	void 기본값에서_위치_교환은_5분_주기로_켜져_있다() {
		TeamCreationSettings settings = TeamCreationSettings.defaults(20.0F);

		assertTrue(settings.swapEnabled());
		assertEquals(5, settings.swapIntervalMinutes());
		assertEquals(5, TeamCreationSettings.DEFAULT_SWAP_MINUTES);
	}

	/** 화면이 들고 시작하는 값도 이 상수를 그대로 본다. 숫자를 옮겨 적으면 갈라진다. */
	@Test
	void 기본_주기는_굴림_단추의_자리_중_하나다() {
		assertEquals(TeamCreationSettings.DEFAULT_SWAP_MINUTES,
				com.sharedfate.ui.TeamCreationCycle.nextSwapMinutes(1),
				"1분 다음 자리가 곧 기본값이라야 화면에서 기본값으로 되돌아올 수 있다");
	}

	// ------------------------------------------------------------------ 새기기

	@Test
	void 정한_값이_갓_만든_팀_상태에_그대로_새겨진다() {
		TeamCreationSettings settings = TeamCreationSettings.defaults(20.0F)
				.withPerks(false).withDamageAlert(true).withDeathAlert(true)
				.withDifficultyEscalation(true).withMaxHealth(34.0F).withSwapIntervalMinutes(5);
		TeamState state = TeamState.fresh(20.0F);

		settings.applyTo(state);

		assertFalse(state.perksEnabled);
		assertTrue(state.damageAlertEnabled);
		assertTrue(state.deathAlertEnabled);
		assertTrue(state.difficultyEscalationEnabled);
		assertEquals(34.0F, state.baseMaxHealth);
		assertEquals(34.0F, state.maxHealth, "증강이 없는 갓 만든 팀은 둘이 같아야 한다");
		assertEquals(5, state.positionSwapIntervalMinutes());
		assertEquals(0, state.difficultyElapsedTicks);
	}

	/** 안 적으면 기본 주기(5분)로 켜진다. 앞서 다른 주기가 새겨져 있었어도 덮어쓴다. */
	@Test
	void 위치_교환을_안_적으면_기본_주기로_새겨진다() {
		TeamState state = TeamState.fresh(20.0F);
		state.enablePositionSwap(7);

		TeamCreationSettings.defaults(20.0F).applyTo(state);

		assertTrue(state.positionSwapEnabled());
		assertEquals(TeamCreationSettings.DEFAULT_SWAP_MINUTES,
				state.positionSwapIntervalMinutes());
	}

	/** 「끔」으로 만든 팀에는 앞서 켜져 있던 교환이 남으면 안 된다. */
	@Test
	void 위치_교환을_끔으로_적으면_꺼진_채로_새겨진다() {
		TeamState state = TeamState.fresh(20.0F);
		state.enablePositionSwap(7);

		TeamCreationSettings.defaults(20.0F)
				.withSwapIntervalMinutes(TeamCreationSettings.SWAP_DISABLED).applyTo(state);

		assertFalse(state.positionSwapEnabled());
	}

	@Test
	void 상한을_올려도_만든_사람의_체력이_공짜로_차지_않는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.health = 7.0F;

		TeamCreationSettings.defaults(20.0F).withMaxHealth(40.0F).applyTo(state);

		assertEquals(7.0F, state.health);
	}

	@Test
	void 상한을_내리면_현재_체력이_거기까지_깎인다() {
		TeamState state = TeamState.fresh(40.0F);
		state.health = 40.0F;

		TeamCreationSettings.defaults(40.0F).withMaxHealth(20.0F).applyTo(state);

		assertEquals(20.0F, state.health);
	}

	// ------------------------------------------------------------------ 값 검사

	@Test
	void 서버_설정이_이상해도_팀_만들기가_죽지_않는다() {
		assertEquals(20.0F, TeamCreationSettings.defaults(Float.NaN).maxHealth());
		assertEquals(1.0F, TeamCreationSettings.defaults(0.0F).maxHealth());
		assertEquals(1024.0F, TeamCreationSettings.defaults(99999.0F).maxHealth());
	}

	/**
	 * 범위를 벗어난 교환 주기는 <b>조용히 접는다.</b>
	 *
	 * <p>예전에는 예외로 알렸는데, 2026-09-09 에 상한을 120 에서 30 으로 줄이면서 그 길이
	 * 위험해졌다 — 예전에 120분으로 만든 팀이 명단 파일에 남아 있으면 <b>팀을 읽는 순간
	 * 죽는다.</b> 값 하나 때문에 팀이 통째로 사라지는 것보다 접는 쪽이 낫다.
	 */
	@Test
	void 범위를_벗어난_교환_주기는_접는다() {
		assertEquals(TeamState.PositionSwapLimits.MAX_MINUTES,
				TeamCreationSettings.defaults(20.0F).withSwapIntervalMinutes(121)
						.swapIntervalMinutes(),
				"예전 명단의 120분도 팀을 죽이지 않고 상한으로 접힌다");
		assertEquals(TeamState.PositionSwapLimits.MIN_MINUTES,
				TeamCreationSettings.defaults(20.0F).withSwapIntervalMinutes(-1)
						.swapIntervalMinutes());
		// 「끔」은 범위 밖의 값이지만 접지 않는다. 0 은 주기가 아니라 상태다.
		assertEquals(TeamCreationSettings.SWAP_DISABLED,
				TeamCreationSettings.defaults(20.0F)
						.withSwapIntervalMinutes(TeamCreationSettings.SWAP_DISABLED)
						.swapIntervalMinutes());
	}

	// ------------------------------------------------------------------ 안내 문구

	@Test
	void 막힌_설정마다_무엇이_막혔는지와_어떻게_바꾸는지가_함께_나간다() {
		for (TeamCreationSettings.Locked locked : TeamCreationSettings.Locked.values()) {
			String message = locked.message();
			assertTrue(message.contains("팀을 만들 때 정한 값이라 바꿀 수 없습니다"),
					locked + ": 왜 막혔는지가 있어야 한다");
			assertTrue(message.contains("/shareteam disband confirm"),
					locked + ": 그래도 바꾸려면 무엇을 해야 하는지가 있어야 한다");
			assertTrue(message.endsWith("."), locked + ": 존댓말 문장으로 끝나야 한다");
		}
	}

	@Test
	void 바꿀_수_없는_설정_넷이_모두_잠겨_있다() {
		// 개수로 못박으면 나중에 잠글 설정이 늘 때마다 이 시험이 애먼 이유로 깨진다.
		// 지금 반드시 잠겨 있어야 하는 넷이 들어 있는지만 본다.
		java.util.Set<TeamCreationSettings.Locked> locked =
				java.util.EnumSet.allOf(TeamCreationSettings.Locked.class);

		assertTrue(locked.contains(TeamCreationSettings.Locked.PERKS));
		assertTrue(locked.contains(TeamCreationSettings.Locked.MAX_HEALTH));
		assertTrue(locked.contains(TeamCreationSettings.Locked.POSITION_SWAP));
		assertTrue(locked.contains(TeamCreationSettings.Locked.DIFFICULTY));
	}

	@Test
	void 요약에_팀이_정한_항목들이_들어간다() {
		String summary = TeamCreationSettings.defaults(20.0F)
				.withDifficultyEscalation(true).withSwapIntervalMinutes(5).summary();

		assertTrue(summary.contains("증강: 켬"));
		assertTrue(summary.contains("피격 알림: 끔"));
		assertTrue(summary.contains("사망 알림: 끔"));
		assertTrue(summary.contains("최대 체력: 20"));
		assertTrue(summary.contains("위치 교환: 5분 주기"));
		assertTrue(summary.contains("난이도 상승: 켬"));
	}

	@Test
	void 소수점이_의미_없는_체력은_정수로_보여_준다() {
		assertEquals("20", TeamCreationSettings.trimZero(20.0F));
		assertEquals("24.5", TeamCreationSettings.trimZero(24.5F)
				.toLowerCase(Locale.ROOT));
	}

	// ------------------------------------------------------------------ 저장 왕복

	@Test
	void 난이도_상승_설정과_흐른_시간이_왕복_저장된다() {
		TeamState state = TeamState.fresh(20.0F);
		state.difficultyEscalationEnabled = true;
		state.difficultyElapsedTicks = 45000;

		TeamState round = decode(encode(state));

		assertTrue(round.difficultyEscalationEnabled);
		assertEquals(45000, round.difficultyElapsedTicks);
	}

	@Test
	void 난이도를_안_쓰는_팀은_difficulty_항목을_아예_저장하지_않는다() {
		CompoundTag encoded = encode(TeamState.fresh(20.0F));

		assertFalse(encoded.contains("difficulty"),
				"안 쓰면 저장 형태가 이 기능 도입 전과 같아야 한다");
	}

	@Test
	void difficulty_항목이_없는_기존_월드는_꺼진_채로_열린다() {
		TeamState state = TeamState.fresh(20.0F);
		state.difficultyEscalationEnabled = true;
		state.difficultyElapsedTicks = 1200;
		state.xpLevel = 9;
		CompoundTag encoded = encode(state);
		assertTrue(encoded.contains("difficulty"), "켜 두었으면 저장에 있어야 한다");
		encoded.remove("difficulty");

		TeamState round = decode(encoded);

		assertFalse(round.difficultyEscalationEnabled);
		assertEquals(0, round.difficultyElapsedTicks);
		assertEquals(9, round.xpLevel, "난이도와 무관한 값은 그대로여야 한다");
	}

	@Test
	void 망가진_흐른_시간은_0_으로_되돌린다() {
		TeamState state = TeamState.fresh(20.0F);
		state.difficultyElapsedTicks = -5;

		state.sanitize(20.0F);

		assertEquals(0, state.difficultyElapsedTicks);
	}
}
