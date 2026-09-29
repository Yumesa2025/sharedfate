package com.sharedfate.team;

/**
 * 팀을 만들 때 한 번만 정하는 설정.
 *
 * <p><b>증강 사용 여부·공유 최대 체력·위치 교환
 * 주기·난이도 상승·드래곤 시련·증강 다시 뽑기 횟수</b>도 팀을 만드는 순간에만 정한다. 회차가 이미
 * 굴러가는 중에 이것들이 바뀌면 같은 회차의 앞뒤가 다른 규칙으로 흘러간다. 특히 증강은
 * 껐다 켜는 것이 이미 받은 효과를 잠깐 벗어 두는 길이 되어 회차 자체가 뜻을 잃는다.
 * 그래서 바꾸는 길을 아예 두지 않고, 바꾸려 하면 {@link Locked} 의 안내로 돌려보낸다.
 *
 * <p>여기서 정한 값이 <b>회차를 넘어 이어지는</b> 일은
 * {@link TeamManager#restoreFreshRoster} 가 맡는다. 전멸로 월드가 새로 만들어져도 팀이 한 번
 * 내린 결정은 그대로 따라간다.
 *
 * @param perksEnabled                  증강을 쓸 것인가
 * @param damageAlertEnabled            피격 알림을 띄울 것인가
 * @param deathAlertEnabled             사망 알림을 띄울 것인가
 * @param difficultyEscalationEnabled   시간이 흐를수록 적대적 몹이 강해질 것인가
 * @param dragonTrialsEnabled           엔더 드래곤 시련을 쓸 것인가. 끄면 바닐라 드래곤전이다
 * @param maxHealth                     팀이 정한 공유 최대 체력. 증강 보너스가 붙기 전의 값이다
 * @param swapIntervalMinutes           위치 교환 주기(분). {@link #SWAP_DISABLED} 면 끔
 * @param rerollCount                   증강 선택창에서 후보를 다시 뽑을 수 있는 <b>회차당</b> 횟수
 */
public record TeamCreationSettings(boolean perksEnabled, boolean damageAlertEnabled,
		boolean deathAlertEnabled, boolean difficultyEscalationEnabled,
		boolean dragonTrialsEnabled,
		float maxHealth, int swapIntervalMinutes, int rerollCount) {

	/**
	 * 증강은 <b>켠 채로</b> 시작한다.
	 *
	 * <p>{@code /shareteam create} 에 {@code perks} 를 적지 않으면 이 값이 쓰인다.
	 */
	public static final boolean DEFAULT_PERKS_ENABLED = true;

	/** 난이도 상승은 <b>끈 채로</b> 시작한다. */
	public static final boolean DEFAULT_DIFFICULTY_ESCALATION = false;

	/**
	 * 엔더 드래곤 시련은 <b>끈 채로</b> 시작한다.
	 *
	 * <p>끄면 최종 보스가 <b>바닐라 엔더 드래곤전</b>이다 — 체력 200 그대로이고
	 * ({@code SharedFateConfig.dragonHealthPerMember} 를 무시한다) 시련 카드도 고정 시련도
	 * 기본 패시브도 돌지 않는다. 실제로 가르는 자리는
	 * {@code com.sharedfate.sync.DragonTrialManager} 와 {@code DragonTrialSession} 이다.
	 *
	 * <p>⚠ <b>이 값은 2026-09-30 에 「늘 켜짐」에서 「기본 끔」으로 바뀌었다.</b> 그 전에는 설정
	 * 자체가 없어 모든 팀이 시련을 받았다. 이제는 <b>팀을 만들 때 손으로 켜야</b> 하므로, 시험
	 * 월드를 새로 열 때마다 켜 주지 않으면 엔드에 가도 아무 카드가 뜨지 않는다 — 「왜 시련이 안
	 * 뜨지」의 첫 번째 원인이 이것이다. 켜고 끈 것은 팀을 만들 때와 전투가 열릴 때 로그에 한 줄씩
	 * 남는다.
	 */
	public static final boolean DEFAULT_DRAGON_TRIALS = false;

	/** 위치 교환 「끔」. 주기 0분은 없으므로 0 을 끔으로 쓴다. */
	public static final int SWAP_DISABLED = 0;

	/**
	 * 위치 교환은 <b>켠 채로</b> 시작하고, 주기는 5분이다.
	 *
	 * <p>{@code /shareteam create} 에 {@code swap} 을 적지 않으면 이 값이 쓰인다.
	 */
	public static final int DEFAULT_SWAP_MINUTES = 5;

	/** {@code /shareteam create ... health <값>} 이 받는 범위. */
	public static final int MIN_MAX_HEALTH = 20;
	public static final int MAX_MAX_HEALTH = 40;

	/**
	 * 다시 뽑기의 기본 횟수. <b>회차당</b> 세 번이다.
	 *
	 * <p>{@code /shareteam create} 에 {@code reroll} 을 적지 않으면 이 값이 쓰인다.
	 */
	public static final int DEFAULT_REROLL_COUNT = 3;

	/**
	 * {@code /shareteam create ... reroll <값>} 이 받는 범위. 0 이면 다시 뽑기를 안 쓰는 팀이다.
	 *
	 * <p><b>이것은 「팀을 만들 때 고를 수 있는 최대」일 뿐이다.</b> 세트로 얻는 몫은 여기에
	 * 포함되지 않고 이 위에 얹힌다 — {@link #MAX_SET_REROLL_BONUS} 를 보라.
	 */
	public static final int MIN_REROLL_COUNT = 0;
	public static final int MAX_REROLL_COUNT = 15;

	/**
	 * 세트로 얹을 수 있는 다시 뽑기 몫의 <b>총합 상한</b>. 「도박 2」(5) + 「도박 3」(3) = 8 이다.
	 *
	 * <h2>왜 {@link #MAX_REROLL_COUNT} 와 갈라 두는가</h2>
	 * <p>0.29.1-dev 전에는 하나였다. 세트 몫을 {@code 상한 − 회차당 허용치} 로 접었기 때문에,
	 * <b>굴림을 최대(15)로 잡은 팀은 도박 세트가 통째로 죽었다.</b> 남는 자리가 0 이라 2단계도
	 * 3단계도 한 회를 못 얹었고, 그 잘림은 아무 말도 없이 일어났다. 실제로 그렇게 당했다 —
	 * 굴림을 좋아해서 최대로 고른 사람이 <b>굴림을 주는 유형 다섯 장을 죽은 카드로</b> 받았다.
	 *
	 * <p>이제 둘은 별개다. 한 회차에 가질 수 있는 최대는 {@code 15 + 8 = 23} 이고, 어떤 허용치를
	 * 고른 팀이든 도박 세트를 <b>온전히</b> 받는다.
	 *
	 * <p><b>도박 단계에 다시 뽑기를 더 얹으려면 이 값도 함께 올려야 한다.</b> 안 올리면 넘치는
	 * 만큼이 조용히 잘린다. {@code GambleSetRewardTest} 가 번들 정의의 합계를 이 값과 견주므로
	 * 잊으면 시험이 먼저 깨진다.
	 */
	public static final int MAX_SET_REROLL_BONUS = 8;

	/** 손상된 저장값이나 조작된 값을 허용 범위 안으로 접는다. */
	public static int sanitizeRerollCount(int value) {
		return Math.max(MIN_REROLL_COUNT, Math.min(MAX_REROLL_COUNT, value));
	}

	/**
	 * 위치 교환 주기를 허용 범위 안으로 접는다. {@link #SWAP_DISABLED}(끔)는 그대로 둔다.
	 *
	 * <p>상한을 줄일 때 예전 명단이 죽지 않게 하는 것이 이 함수가 있는 이유다.
	 */
	public static int sanitizeSwapMinutes(int value) {
		if (value == SWAP_DISABLED) {
			return SWAP_DISABLED;
		}
		return Math.max(TeamState.PositionSwapLimits.MIN_MINUTES,
				Math.min(TeamState.PositionSwapLimits.MAX_MINUTES, value));
	}

	private static final float ABSOLUTE_MIN_HEALTH = 1.0F;
	private static final float ABSOLUTE_MAX_HEALTH = 1024.0F;

	public TeamCreationSettings {
		// 최대 체력은 설정 파일에서도 흘러들어온다. 서버 설정이 이상하다고 팀 만들기 자체가
		// 죽으면 안 되므로 TeamState.sanitize 와 같은 결로 조용히 접는다.
		maxHealth = Float.isFinite(maxHealth)
				? Math.max(ABSOLUTE_MIN_HEALTH, Math.min(ABSOLUTE_MAX_HEALTH, maxHealth))
				: 20.0F;
		// 주기도 최대 체력과 같은 결로 조용히 접는다. 예전에는 예외로 알렸는데, 2026-09-09 에
		// 상한을 120 에서 30 으로 줄이면서 그 길이 위험해졌다 — 예전에 120분으로 만든 팀이
		// 명단 파일에 남아 있으면 팀을 읽는 순간 죽는다. 값 하나 때문에 팀이 통째로 사라지는
		// 것보다 접는 쪽이 낫다.
		swapIntervalMinutes = sanitizeSwapMinutes(swapIntervalMinutes);
		// 다시 뽑기 횟수는 최대 체력과 같은 결로 조용히 접는다. 명령이 이미 0~15 로 거르지만
		// 예전 형식의 팀 명단 파일에서도 흘러들어오는 값이라, 그것 때문에 팀 만들기가 죽으면 안 된다.
		rerollCount = sanitizeRerollCount(rerollCount);
	}

	/** 아무것도 적지 않고 만든 팀의 설정. 최대 체력만 서버 설정에서 온다. */
	public static TeamCreationSettings defaults(float maxHealth) {
		return new TeamCreationSettings(DEFAULT_PERKS_ENABLED, false, false,
				DEFAULT_DIFFICULTY_ESCALATION, DEFAULT_DRAGON_TRIALS, maxHealth,
				DEFAULT_SWAP_MINUTES, DEFAULT_REROLL_COUNT);
	}

	public TeamCreationSettings withPerks(boolean enabled) {
		return new TeamCreationSettings(enabled, damageAlertEnabled, deathAlertEnabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, maxHealth, swapIntervalMinutes,
				rerollCount);
	}

	public TeamCreationSettings withDamageAlert(boolean enabled) {
		return new TeamCreationSettings(perksEnabled, enabled, deathAlertEnabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, maxHealth, swapIntervalMinutes,
				rerollCount);
	}

	public TeamCreationSettings withDeathAlert(boolean enabled) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, enabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, maxHealth, swapIntervalMinutes,
				rerollCount);
	}

	public TeamCreationSettings withDifficultyEscalation(boolean enabled) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, deathAlertEnabled,
				enabled, dragonTrialsEnabled, maxHealth, swapIntervalMinutes, rerollCount);
	}

	public TeamCreationSettings withDragonTrials(boolean enabled) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, deathAlertEnabled,
				difficultyEscalationEnabled, enabled, maxHealth, swapIntervalMinutes, rerollCount);
	}

	public TeamCreationSettings withMaxHealth(float value) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, deathAlertEnabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, value, swapIntervalMinutes,
				rerollCount);
	}

	public TeamCreationSettings withSwapIntervalMinutes(int minutes) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, deathAlertEnabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, maxHealth, minutes, rerollCount);
	}

	public TeamCreationSettings withRerollCount(int count) {
		return new TeamCreationSettings(perksEnabled, damageAlertEnabled, deathAlertEnabled,
				difficultyEscalationEnabled, dragonTrialsEnabled, maxHealth, swapIntervalMinutes,
				count);
	}

	public boolean swapEnabled() {
		return swapIntervalMinutes != SWAP_DISABLED;
	}

	/**
	 * 갓 만든 팀 상태에 이 설정을 새긴다.
	 *
	 * <p>증강을 아직 하나도 가지지 않은 상태에만 부른다. 그래서 {@code baseMaxHealth} 와
	 * {@code maxHealth} 를 같은 값으로 두어도 되고, 증강 보너스를 얹는
	 * {@code PerkHealthRules} 를 거칠 필요가 없다.
	 *
	 * <p>현재 체력은 <b>줄이기만</b> 한다. 팀을 만들 때 상한을 올렸다고 해서 만든 사람의
	 * 체력이 공짜로 차면 안 된다.
	 */
	public void applyTo(TeamState state) {
		state.perksEnabled = perksEnabled;
		state.damageAlertEnabled = damageAlertEnabled;
		state.deathAlertEnabled = deathAlertEnabled;
		state.difficultyEscalationEnabled = difficultyEscalationEnabled;
		state.dragonTrialsEnabled = dragonTrialsEnabled;
		// 난이도가 오른 시간은 「이 회차가 시작된 뒤」다. 갓 만든 팀은 언제나 0 에서 시작한다.
		state.difficultyElapsedTicks = 0;
		state.baseMaxHealth = maxHealth;
		state.maxHealth = maxHealth;
		state.health = Math.max(0.0F, Math.min(maxHealth, state.health));
		// 갓 만든 팀은 이번 회차의 다시 뽑기를 한 번도 쓰지 않았다. 회차가 넘어가 팀 상태를
		// 새로 만들 때도 restoreFreshRoster 가 같은 자리를 가득 채운 값으로 다시 세운다.
		state.rerollAllowance = rerollCount;
		state.rerollsRemaining = rerollCount;
		if (swapEnabled()) {
			state.enablePositionSwap(swapIntervalMinutes);
		} else {
			state.disablePositionSwap();
		}
	}

	/** 팀을 만든 직후 한 줄로 보여 줄 요약. */
	public String summary() {
		return "증강: " + onOff(perksEnabled)
				+ " · 피격 알림: " + onOff(damageAlertEnabled)
				+ " · 사망 알림: " + onOff(deathAlertEnabled)
				+ " · 최대 체력: " + trimZero(maxHealth)
				+ " · 위치 교환: " + (swapEnabled() ? swapIntervalMinutes + "분 주기" : "끔")
				+ " · 난이도 상승: " + onOff(difficultyEscalationEnabled)
				// 기본값이 끔이라 「안 적으면 안 켜진다」. 만든 직후 한 줄에 보여 주는 것이
				// 「왜 시련이 안 뜨지」를 막는 가장 이른 자리다.
				+ " · 드래곤 시련: " + onOff(dragonTrialsEnabled)
				+ " · 다시 뽑기: 회차당 " + rerollCount + "회";
	}

	public static String onOff(boolean value) {
		return value ? "켬" : "끔";
	}

	/** 20.0 처럼 소수점이 의미 없는 값을 "20" 으로 보여 준다. */
	public static String trimZero(float value) {
		return value == Math.rint(value)
				? String.valueOf((long) value)
				: String.format(java.util.Locale.ROOT, "%.1f", value);
	}

	/** 만든 뒤에 바꾸려 할 때 돌려주는 안내. */
	public enum Locked {
		PERKS("증강 사용 여부는"),
		MAX_HEALTH("팀 공유 최대 체력은"),
		POSITION_SWAP("위치 교환 설정은"),
		DIFFICULTY("난이도 상승 설정은");

		private final String subject;

		Locked(String subject) {
			this.subject = subject;
		}

		public String message() {
			return subject + " 팀을 만들 때 정한 값이라 바꿀 수 없습니다."
					+ "\n바꾸려면 리더가 /shareteam disband confirm 으로 팀을 해체하고 다시 만드세요."
					+ "\n지금 값은 /shareteam status 로 볼 수 있습니다.";
		}
	}
}
