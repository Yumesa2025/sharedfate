package com.sharedfate.perk;

import java.util.ArrayList;
import java.util.List;

/**
 * 증강 발동 구간 계산.
 *
 * <p>팀 공유 레벨이 5의 배수에 <b>처음</b> 도달할 때마다 증강 선택권이 하나 생긴다.
 * 구간은 5, 10, 15, 20, 25, 30, 35, 40 여덟 개뿐이고 40을 넘으면 더 발동하지 않는다.
 * 고정 프리즘 구간
 * ({@link PerkDraft#PRISM_MILESTONES})이 15 하나뿐이고, 나머지 일곱은 확률표
 * ({@link PerkDraft#ODDS_BY_EXTRA_PRISM})로 등급을 굴린다 — 30 도 그 일곱에 든다.
 *
 * <p>재발동 여부는 현재 레벨이 아니라 {@code lastMilestone}으로 판단한다. 경험치를 써서
 * 레벨이 내려갔다가 다시 올라와도 이미 처리한 구간은 다시 나오지 않는다.
 *
 * <h2>⚠ 41~50 구간은 아직 비어 있다</h2>
 * <p>드래곤 시련을 켠 팀은 레벨 상한이 50 이다({@code StatMirror.TRIAL_LEVEL_CAP}). 그런데
 * {@link #MAX} 는 40 그대로라 <b>41~50 에서는 증강 선택권이 한 번도 열리지 않는다.</b>
 * 빠뜨린 것이 아니라 그 구간에 무엇을 줄지 아직 정해지지 않았기 때문이고, 「추후 추가 예정」
 * 이다. 그 구간을 채우는 사람은 {@link #MAX} 를 50 으로 올리기 전에 45·50 에 내놓을 증강과
 * 세트를 먼저 정의 파일에 적어야 한다 — 올리기만 하면 보상이 없는 선택 화면이 뜬다.
 * 시련을 켜지 않은 팀은 상한이 40 이라 이 구간에 들어오지 않는다.
 */
public final class PerkMilestones {
	/** 구간 간격. */
	public static final int STEP = 5;
	/** 마지막 구간. 이 위로는 발동하지 않는다. */
	public static final int MAX = 40;
	/** 전체 구간 수. */
	public static final int COUNT = MAX / STEP;

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
		int handled = clampMilestone(lastMilestone);
		int reachable = Math.min(currentLevel, MAX);
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
		if (milestone <= 0) {
			return 0;
		}
		int bounded = Math.min(milestone, MAX);
		return bounded - (bounded % STEP);
	}

	/** 5, 10, …, 40 중 하나인지. */
	public static boolean isMilestone(int level) {
		return level >= STEP && level <= MAX && level % STEP == 0;
	}
}
