package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 팀 만들기 탭 설정 툴팁 문구를 못박는 시험. 상한 값의 까닭은 {@link TeamCreationTooltips}
 * 클래스 문서에 적어 두었다.
 */
class TeamCreationTooltipsTest {

	/**
	 * 한 줄에 대략 열예닐곱 자가 들어가는 170px 폭에서 서너 줄 안에 들어오는 상한.
	 *
	 * <p>증강 설명의 85자 상한과 달리 <b>넘어도 화면이 망가지지는 않는다</b> — 이 값을 넘기면
	 * 그저 툴팁이 길어질 뿐이다. 그래도 「무엇을 끄는지」가 한눈에 읽혀야 하므로 여유를 두고
	 * 짧게 잡는다.
	 */
	private static final int MAX_LENGTH = 80;

	@Test
	void 설정_툴팁_여덟_가지는_모두_짧게_읽힌다() {
		List<String> tooltips = List.of(
				TeamCreationTooltips.PERKS,
				TeamCreationTooltips.DIFFICULTY,
				TeamCreationTooltips.DRAGON_TRIAL,
				TeamCreationTooltips.DAMAGE_ALERT,
				TeamCreationTooltips.DEATH_ALERT,
				TeamCreationTooltips.MAX_HEALTH,
				TeamCreationTooltips.POSITION_SWAP,
				TeamCreationTooltips.REROLL);

		for (String tooltip : tooltips) {
			assertTrue(tooltip.length() <= MAX_LENGTH,
					"\"" + tooltip + "\" (" + tooltip.length() + "자)가 상한 " + MAX_LENGTH
							+ "자를 넘는다");
		}
	}

	@Test
	void 사망_알림_설명은_전멸_원인을_못_찾게_되는_대가를_반드시_적는다() {
		// 이 대가는 다른 일곱 설정에는 없고, 화면 어디에도 따로 적혀 있지 않다. 문구를 다듬다
		// 이 경고가 지워지면 리더가 대가를 모른 채 알림을 끄게 된다.
		String text = TeamCreationTooltips.DEATH_ALERT;
		assertTrue(text.contains("로그") && text.contains("원인"),
				"사망 알림 설명에서 로그·원인 언급이 빠졌다: " + text);
	}

	/**
	 * 드래곤 시련만은 <b>기본값이 끔이라는 사실</b>을 문구에 적는다.
	 *
	 * <p>2026-09-30 전까지 시련은 늘 켜져 있었다. 이제는 팀을 만들 때마다 손으로 켜야 하는데,
	 * 그 사실을 모르면 엔드까지 가서야 「왜 시련이 안 뜨지」를 묻게 되고 그때는 팀을 해체하는
	 * 수밖에 없다. 문구를 다듬다 이 말이 빠지면 그 사고가 그대로 돌아온다.
	 */
	@Test
	void 드래곤_시련_설명은_기본값이_끔이라는_것과_끄면_바닐라라는_것을_적는다() {
		String text = TeamCreationTooltips.DRAGON_TRIAL;
		assertTrue(text.contains("기본값"), "기본값이 끔이라는 말이 빠졌다: " + text);
		assertTrue(text.contains("바닐라"), "끄면 바닐라 드래곤전이라는 말이 빠졌다: " + text);
	}
}
