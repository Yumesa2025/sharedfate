package com.sharedfate.ui;

import com.sharedfate.team.TeamCreationSettings;
import com.sharedfate.team.TeamState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팀 만들기 화면이 굴리는 숫자와, 그 결과로 만들어지는 명령 한 줄.
 *
 * <p>화면({@code TeamScreen})은 {@code src/client} 에 있어 이 소스셋이 볼 수 없다. 그래서
 * <b>명령 문자열의 형식</b>을 화면이 아니라 {@link TeamCreationCycle} 에 두었고, 여기서
 * 서버의 {@code /shareteam create} 가 읽는 순서와 같은지 확인한다. 이 둘이 어긋나면
 * 팀 만들기 단추가 조용히 「알 수 없는 명령」이 된다.
 */
class TeamCreationCycleTest {

	// ------------------------------------------------------------------ 최대 체력

	@Test
	void 최대_체력은_두씩_오르다_위_끝에서_아래_끝으로_돌아온다() {
		assertEquals(22, TeamCreationCycle.nextMaxHealth(20, 20, 40, 2));
		assertEquals(40, TeamCreationCycle.nextMaxHealth(38, 20, 40, 2));
		assertEquals(20, TeamCreationCycle.nextMaxHealth(40, 20, 40, 2));
	}

	@Test
	void 범위_밖에서_굴리기_시작해도_범위_안으로_들어온다() {
		assertEquals(22, TeamCreationCycle.nextMaxHealth(3, 20, 40, 2));
		assertEquals(20, TeamCreationCycle.nextMaxHealth(999, 20, 40, 2));
	}

	@Test
	void 한_칸이_0이하면_최소한_하나는_움직인다() {
		// 굴려도 값이 그대로면 단추가 고장 난 것처럼 보인다.
		assertEquals(21, TeamCreationCycle.nextMaxHealth(20, 20, 40, 0));
	}

	// ------------------------------------------------------------------ 위치 교환

	@Test
	void 위치_교환은_끔에서_시작해_한_바퀴_돌아_끔으로_돌아온다() {
		int minutes = TeamCreationCycle.SWAP_OFF;
		int steps = 0;
		do {
			minutes = TeamCreationCycle.nextSwapMinutes(minutes);
			steps++;
			assertTrue(steps < 100, "굴림이 끔으로 돌아오지 못하고 맴돌면 안 된다");
		} while (minutes != TeamCreationCycle.SWAP_OFF);

		// 2026-09-09 에 상한이 30분으로 줄면서 45·60·90·120 자리가 사라졌다.
		assertEquals(7, steps, "끔 + 자리 여섯 개(1·5·10·15·20·30)");
	}

	@Test
	void 위치_교환_자리는_명령이_받는_범위_안이다() {
		int minutes = TeamCreationCycle.nextSwapMinutes(TeamCreationCycle.SWAP_OFF);
		while (minutes != TeamCreationCycle.SWAP_OFF) {
			assertTrue(minutes >= TeamState.PositionSwapLimits.MIN_MINUTES
							&& minutes <= TeamState.PositionSwapLimits.MAX_MINUTES,
					minutes + "분은 명령이 받지 못한다");
			minutes = TeamCreationCycle.nextSwapMinutes(minutes);
		}
	}

	@Test
	void 자리에_없는_주기에서_굴리면_바로_위의_자리로_올라간다() {
		// 명령으로 7분을 적어 둔 팀은 없지만, 굴림이 그런 값에서 멈춰 버리면 안 된다.
		assertEquals(10, TeamCreationCycle.nextSwapMinutes(7));
		assertEquals(TeamCreationCycle.SWAP_OFF, TeamCreationCycle.nextSwapMinutes(121));
	}

	@Test
	void 위치_교환_글자는_끔과_분을_구분한다() {
		assertEquals("끔", TeamCreationCycle.swapLabel(TeamCreationCycle.SWAP_OFF));
		assertEquals("30분", TeamCreationCycle.swapLabel(30));
		assertEquals("off", TeamCreationCycle.swapArgument(TeamCreationCycle.SWAP_OFF));
		assertEquals("30", TeamCreationCycle.swapArgument(30));
	}

	// ------------------------------------------------------------------ 다시 뽑기 횟수

	@Test
	void 다시_뽑기_횟수는_하나씩_오르다_위_끝에서_0으로_돌아온다() {
		int min = TeamCreationSettings.MIN_REROLL_COUNT;
		int max = TeamCreationSettings.MAX_REROLL_COUNT;

		assertEquals(4, TeamCreationCycle.nextRerollCount(3, min, max));
		assertEquals(max, TeamCreationCycle.nextRerollCount(max - 1, min, max));
		assertEquals(min, TeamCreationCycle.nextRerollCount(max, min, max));
	}

	@Test
	void 다시_뽑기_횟수는_0도_고를_수_있다() {
		// 0 은 「다시 뽑기를 아예 안 쓰는 팀」이라는 뜻이라 굴림에서 빠지면 안 된다.
		assertEquals(TeamCreationSettings.MIN_REROLL_COUNT, 0);
		assertEquals(1, TeamCreationCycle.nextRerollCount(0,
				TeamCreationSettings.MIN_REROLL_COUNT, TeamCreationSettings.MAX_REROLL_COUNT));
	}

	// ------------------------------------------------------------------ 명령 한 줄

	@Test
	void 만들기_명령은_사양이_정한_형태_그대로다() {
		assertEquals("create perks on damagealert off deathalert off difficulty off"
						+ " dragontrials off health 20 swap off reroll 3 우리팀",
				TeamCreationCycle.createCommand(true, false, false, false, false, 20,
						TeamCreationCycle.SWAP_OFF, 3, "우리팀"));
	}

	@Test
	void 켠_값과_위치_교환_주기도_같은_자리에_들어간다() {
		assertEquals("create perks off damagealert on deathalert on difficulty on"
						+ " dragontrials on health 34 swap 15 reroll 0 원정대",
				TeamCreationCycle.createCommand(false, true, true, true, true, 34, 15, 0, "원정대"));
	}

	@Test
	void 여덟_가지를_하나도_빼지_않고_적는다() {
		String command =
				TeamCreationCycle.createCommand(true, true, true, true, true, 40, 120, 10, "팀");

		for (String keyword : new String[] {
				"perks", "damagealert", "deathalert", "difficulty", "dragontrials",
				"health", "swap", "reroll"}) {
			assertTrue(command.contains(" " + keyword + " "),
					keyword + " 가 빠지면 서버가 그 항목을 기본값으로 두어 화면과 어긋난다");
		}
		assertTrue(command.endsWith(" 팀"), "이름은 greedyString 이라 반드시 맨 뒤여야 한다");
	}

	/**
	 * 드래곤 시련만은 <b>기본값이 끔</b>이라, 낱말을 빠뜨리면 「화면에서는 켰는데 실제로는
	 * 꺼진」 팀이 만들어지고 그 사실이 엔드에 도착할 때까지 드러나지 않는다.
	 */
	@Test
	void 드래곤_시련은_켜든_끄든_반드시_적힌다() {
		assertTrue(TeamCreationCycle.createCommand(true, false, false, false, true, 20,
				TeamCreationCycle.SWAP_OFF, 3, "팀").contains(" dragontrials on "));
		assertTrue(TeamCreationCycle.createCommand(true, false, false, false, false, 20,
				TeamCreationCycle.SWAP_OFF, 3, "팀").contains(" dragontrials off "));
	}

	@Test
	void 켜고_끄기는_한국어가_아니라_명령이_읽는_낱말로_적는다() {
		assertEquals("on", TeamCreationCycle.onOff(true));
		assertEquals("off", TeamCreationCycle.onOff(false));
	}
}
