package com.sharedfate.perk;

import java.util.ArrayList;
import java.util.List;

/**
 * 증강 발동 구간 계산.
 *
 * <p>팀 공유 레벨이 5의 배수에 <b>처음</b> 도달할 때마다 증강 선택권이 하나 생긴다.
 * 구간은 5, 10, 15, 20, 25, 30, 35, 40 여덟 개이고 40을 넘으면 더 발동하지 않는다(드래곤 시련을
 * 켠 팀만 45·50 이 더 있다 — 아래 「41~50 구간」).
 * 고정 프리즘 구간
 * ({@link PerkDraft#PRISM_MILESTONES})이 15 하나뿐이고, 나머지 일곱은 확률표
 * ({@link PerkDraft#ODDS_BY_EXTRA_PRISM})로 등급을 굴린다 — 30 도 그 일곱에 든다.
 *
 * <p>재발동 여부는 현재 레벨이 아니라 {@code lastMilestone}으로 판단한다. 경험치를 써서
 * 레벨이 내려갔다가 다시 올라와도 이미 처리한 구간은 다시 나오지 않는다.
 *
 * <h2>41~50 구간 — 드래곤 시련 팀의 「드래곤전 대비」 (0.44.0-dev)</h2>
 * <p>드래곤 시련을 켠 팀은 레벨 상한이 50 이다({@code StatMirror.TRIAL_LEVEL_CAP}). 그 팀만
 * 구간이 {@link #TRIAL_MAX}(50)까지 열려 <b>45·50</b> 두 번 더 고른다. 그 두 구간은 등급을
 * 굴리지 않고 언제나 {@link PerkRarity#TRIAL} 풀에서만, 3장이 아니라
 * {@link PerkDraft#TRIAL_OPTIONS} 장을 내놓는다({@link PerkDraft#rarityFor}).
 *
 * <p>{@link #MAX} 는 <b>시련을 끈 팀의 끝</b>으로 40 그대로 두었다. 시련을 끈 팀은 상한이 40 이라
 * 이 구간에 들어오지 않고, 40 을 기준으로 적힌 옛 계산(인자 둘짜리 {@link #newlyReached} ·
 * {@link #clampMilestone})도 한 글자도 안 바뀐다. 팀을 아는 자리는 {@link #maxFor} 로 끝을 받는다.
 *
 * <p>⚠ 저장값을 접을 때도 팀의 끝으로 접어야 한다. 시련 팀의 45 를 40 끝으로 접으면 40 으로
 * 내려가 45 구간을 <b>다시 한 번</b> 준다.
 */
public final class PerkMilestones {
	/** 구간 간격. */
	public static final int STEP = 5;
	/** 마지막 구간. 이 위로는 발동하지 않는다. */
	public static final int MAX = 40;
	/** 전체 구간 수. 시련을 끈 팀 기준이다. */
	public static final int COUNT = MAX / STEP;
	/** 드래곤 시련을 켠 팀의 마지막 구간. 41~50 의 두 구간(45·50)은 「드래곤전 대비」 전용이다. */
	public static final int TRIAL_MAX = 50;

	private PerkMilestones() {
	}

	/**
	 * {@code lastMilestone} 이후로 {@code currentLevel}이 도달한 구간들을 오름차순으로 돌려준다.
	 *
	 * <p>한 번에 여러 구간을 건너뛰었다면(예: 2렙 → 15렙) 건너뛴 구간을 모두 담는다.
	 * 새로 도달한 구간이 없으면 빈 리스트다.
	 *
	 * @param lastMilestone 마지막으로 처리한 구간. 아직 없으면 0
	 * @param currentLevel  현재 팀 공유 레벨
	 */
	public static List<Integer> newlyReached(int lastMilestone, int currentLevel) {
		return newlyReached(lastMilestone, currentLevel, MAX);
	}

	/**
	 * 팀의 마지막 구간을 함께 받는다. 시련 팀은 {@link #TRIAL_MAX}, 아니면 {@link #MAX}
	 * ({@link #maxFor}).
	 *
	 * @param max 이 팀의 마지막 구간
	 */
	public static List<Integer> newlyReached(int lastMilestone, int currentLevel, int max) {
		int handled = clampMilestone(lastMilestone, max);
		int reachable = Math.min(currentLevel, max);
		if (reachable < STEP || reachable <= handled) {
			return List.of();
		}
		List<Integer> reached = new ArrayList<>();
		for (int milestone = handled + STEP; milestone <= reachable; milestone += STEP) {
			reached.add(milestone);
		}
		return List.copyOf(reached);
	}

	/**
	 * 저장값이 손상됐을 때를 대비해 0 이상 MAX 이하의 5의 배수로 맞춘다.
	 *
	 * <p>구간이 3의 배수이던 시절의 저장값(3·6·…·36)이 그대로 남아 있을 수 있다.
	 * 그런 값도 여기서 <b>내림</b>되므로 다음 구간 계산이 깨지지 않는다. 예를 들어
	 * 12는 10으로 내려가 다음이 15가 되고, 36은 35로 잘려 더는 발동하지 않는다.
	 * 내림이라 이미 받은 구간을 한 번 더 받는 일은 없다.
	 */
	public static int clampMilestone(int milestone) {
		return clampMilestone(milestone, MAX);
	}

	/** 팀의 마지막 구간까지 접는다. 시련 팀의 45·50 이 40 으로 내려가지 않게 한다. */
	public static int clampMilestone(int milestone, int max) {
		if (milestone <= 0) {
			return 0;
		}
		int bounded = Math.min(milestone, Math.max(0, max));
		return bounded - (bounded % STEP);
	}

	/** 5, 10, …, 40 중 하나인지. 시련 팀 전용 45·50 은 {@link #isTrialMilestone} 이 따로 본다. */
	public static boolean isMilestone(int level) {
		return level >= STEP && level <= MAX && level % STEP == 0;
	}

	/** 45·50 — 드래곤 시련 팀의 「드래곤전 대비」 구간인가. */
	public static boolean isTrialMilestone(int level) {
		return level > MAX && level <= TRIAL_MAX && level % STEP == 0;
	}

	/** 이 팀의 마지막 구간. 드래곤 시련을 켠 팀만 {@link #TRIAL_MAX} 다. */
	public static int maxFor(boolean dragonTrials) {
		return dragonTrials ? TRIAL_MAX : MAX;
	}
}
