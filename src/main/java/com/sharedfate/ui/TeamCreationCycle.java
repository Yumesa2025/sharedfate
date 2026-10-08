package com.sharedfate.ui;

import java.util.Locale;

/**
 * 팀 만들기 화면의 숫자 설정 셋을 「누를 때마다 다음 값」으로 굴리는 계산.
 */
public final class TeamCreationCycle {
	/** 위치 교환 「끔」. 서버의 {@code TeamCreationSettings.SWAP_DISABLED} 와 같은 뜻이다. */
	public static final int SWAP_OFF = 0;

	/**
	 * 위치 교환 주기가 굴러가는 자리들(분).
	 *
	 * <p>명령은 1~{@code TeamState.PositionSwapLimits.MAX_MINUTES} 아무 값이나 받는다.
	 * 2026-09-09 에 상한이 120 에서 <b>30</b> 으로 줄면서 45·60·90·120 자리를 걷어냈다 —
	 * 한 회차가 그렇게 길지 않아 그 위는 「회차 내내 한 번도 안 바뀜」과 다르지 않았고,
	 * 굴림 단추를 열한 번 눌러야 한 바퀴가 도는 것도 길었다.
	 */
	private static final int[] SWAP_STEPS = {SWAP_OFF, 1, 5, 10, 15, 20, 30};

	private TeamCreationCycle() {
	}

	/**
	 * 최대 체력을 한 칸 올린다. 위 끝을 넘으면 아래 끝으로 돌아온다.
	 *
	 * @param current 지금 값
	 * @param min     아래 끝(20)
	 * @param max     위 끝(40)
	 * @param step    한 번에 올릴 양(2). 0 이하면 1로 본다
	 */
	public static int nextMaxHealth(int current, int min, int max, int step) {
		int safeStep = Math.max(1, step);
		int clamped = Math.max(min, Math.min(max, current));
		int next = clamped + safeStep;
		return next > max ? min : next;
	}

	/** 위치 교환 주기를 다음 자리로 굴린다. 마지막(120분) 다음은 「끔」이다. */
	public static int nextSwapMinutes(int current) {
		for (int index = 0; index < SWAP_STEPS.length; index++) {
			if (SWAP_STEPS[index] == current) {
				return SWAP_STEPS[(index + 1) % SWAP_STEPS.length];
			}
		}
		// 명령으로 적은 어중간한 값에서 굴리기 시작한 경우다. 바로 위의 자리로 올린다.
		for (int step : SWAP_STEPS) {
			if (step > current) {
				return step;
			}
		}
		return SWAP_OFF;
	}

	/** 다시 뽑기 횟수를 하나 올린다. 위 끝을 넘으면 아래 끝으로 돌아온다. */
	public static int nextRerollCount(int current, int min, int max) {
		int clamped = Math.max(min, Math.min(max, current));
		return clamped >= max ? min : clamped + 1;
	}

	/**
	 * 위치 교환 값을 단추에 적을 글자로. 끔이면 「끔」, 아니면 「5분」.
	 *
	 * <p>「5분 주기」로 길게 적지 않는 이유는 자리 때문이다. 이 단추는 팀 만들기 탭에서 판
	 * 절반(148px)만 쓰므로, 「위치 교환 — 120분 주기」까지 가면 글자가 단추를 넘친다.
	 */
	public static String swapLabel(int minutes) {
		return minutes == SWAP_OFF ? "끔" : minutes + "분";
	}

	/** 명령에 적을 위치 교환 값. 끔이면 {@code off}, 아니면 분 숫자 그대로. */
	public static String swapArgument(int minutes) {
		return minutes == SWAP_OFF ? "off" : String.valueOf(minutes);
	}

	/**
	 * 화면이 정한 값들을 {@code /shareteam} 뒤에 붙일 한 줄로 만든다.
	 *
	 * <p><b>여덟 가지를 하나도 빼지 않고 적는다.</b> 안 적은 항목은 서버가 기본값으로 두는데,
	 * 화면에는 이미 다른 값이 보이고 있을 수 있어 눈에 보이는 것과 실제가 어긋난다.
	 * 드래곤 시련은 기본값이 <b>끔</b>이라 특히 그렇다 — 화면에서 켜 놓고 낱말을 빠뜨리면
	 * 「켰는데 안 켜진」 팀이 만들어지고, 그 사실은 엔드에 도착해서야 드러난다.
	 *
	 * <p>낱말 순서는 {@code ShareTeamCommand.createNode} 가 쌓아 둔 순서와 같아야 한다 —
	 * 이름이 greedyString 이라 <b>모든 설정이 이름 앞</b>에 정해진 차례로 와야 하기 때문이다.
	 *
	 * @param name 팀 이름. 앞뒤 공백은 부르는 쪽에서 이미 다듬어 넘긴다
	 */
	public static String createCommand(boolean perks, boolean damageAlert, boolean deathAlert,
			boolean difficulty, boolean dragonTrials, int maxHealth, int swapMinutes,
			int rerollCount, String name) {
		return "create perks " + onOff(perks)
				+ " damagealert " + onOff(damageAlert)
				+ " deathalert " + onOff(deathAlert)
				+ " difficulty " + onOff(difficulty)
				+ " dragontrials " + onOff(dragonTrials)
				+ " health " + maxHealth
				+ " swap " + swapArgument(swapMinutes)
				+ " reroll " + rerollCount
				+ " " + name;
	}

	/** 명령이 읽는 켜고 끄기 낱말. 화면에 보이는 한국어와 달리 {@code on}/{@code off} 다. */
	public static String onOff(boolean value) {
		return value ? "on" : "off";
	}

	/** 20.0 처럼 소수점이 의미 없는 값을 "20" 으로 보여 준다. */
	public static String trimZero(float value) {
		return value == Math.rint(value)
				? String.valueOf((long) value)
				: String.format(Locale.ROOT, "%.1f", value);
	}
}
