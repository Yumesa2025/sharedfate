package com.sharedfate.ui;

/**
 * 팀 만들기 탭의 설정 단추 여덟 가지에 붙는 툴팁 문구.
 *
 * <p><b>글은 실제 동작을 보고 그대로 옮긴 것이다.</b> 지어낸 문구가 아니라 각 설정을 소비하는
 * 자리를 읽고 확인했다.
 * <ul>
 *   <li>{@link #PERKS} — {@code PerkManager} 를 비롯한 증강 코드 전체가 {@code perksEnabled}
 *       를 지켜본다. 꺼지면 선택창도 안 뜨고 이미 가진 효과도 그 자리에서 멈춘다.</li>
 *   <li>{@link #DAMAGE_ALERT} — {@code StatMirror.collectDeltas} 의 주석 그대로,
 *       꺼져 있으면 <b>서버가 알림 패킷을 아예 보내지 않는다.</b></li>
 *   <li>{@link #DEATH_ALERT} — {@code DeathHandler} 가 이 값이 참일 때만
 *       {@code TeamBroadcaster.broadcastTeamWipe} 를 부른다. 꺼지면 전멸 사실이 채팅에도 서버
 *       로그에도 남지 않는다.</li>
 *   <li>{@link #DIFFICULTY} — {@code DifficultyEscalation} 이 이 값을 볼 때만 회차 경과 시간에
 *       따라 몹을 강화한다.</li>
 *   <li>{@link #DRAGON_TRIAL} — {@code DragonTrialManager.startSession} 이 이 값을 볼 때만
 *       드래곤 체력을 올리고 시련 세션을 「켜짐」으로 연다. 꺼진 팀은 체력 수정자가 아예 안
 *       붙어 바닐라 200 이고, {@code DragonTrialSession} 이 자리를 세지 않아 시련 카드도 고정
 *       시련도 뜨지 않으며 {@code DragonPassives} 도 돌지 않는다.</li>
 *   <li>{@link #MAX_HEALTH} — {@code TeamCreationSettings#applyTo} 가 팀 하나의 공유 체력
 *       상한으로 새긴다. 팀원 각자의 체력이 아니라 <b>팀 전체가 나눠 쓰는 값</b>이다.</li>
 *   <li>{@link #POSITION_SWAP} — {@code PositionSwapManager} 가 이 주기마다 팀원들의 자리를
 *       서로 맞바꾼다.</li>
 *   <li>{@link #REROLL} — {@code TeamState.rerollAllowance} 로 새겨져, 증강 선택창에서 후보를
 *       다시 뽑을 수 있는 <b>회차당</b> 횟수가 된다.</li>
 * </ul>
 *
 * <h2>왜 「팀을 만든 뒤 못 바꾼다」는 말을 여기 넣지 않았나</h2>
 * <p>그 사실은 팀 만들기 탭에 이미 늘 보이는 한 줄로 적혀 있다({@code TeamScreen.renderTeam}).
 * 일곱 툴팁에 매번 되풀이하면 정작 그 설정이 <b>무엇을 하는지</b>가 뒤에 묻힌다. 다만
 * {@link #DEATH_ALERT} 만은 예외다 — 「전멸 원인을 나중에 알 수 없다」는 대가는 다른 여섯과
 * 달리 이 설정에만 있고, 어디에도 적혀 있지 않아 여기서 반드시 알려야 한다.
 *
 * <h2>길이를 왜 짧게 잡았나</h2>
 * <p>{@code net.minecraft.client.gui.components.Tooltip} 은 170px 폭에서 스스로 줄을 접고 화면
 * 안에 들어오는 자리로 스스로 옮겨 앉는다 — 증강 카드 설명처럼 「길면 옆의 것이 화면 밖으로
 * 밀려난다」는 사고는 나지 않는다. 그래도 줄이 서너 개를 넘어가면 「무엇을 끄는지」가 한눈에
 * 안 들어오므로 {@code TeamCreationTooltipsTest} 가 길이를 짧게 못박아 둔다.
 */
public final class TeamCreationTooltips {

	public static final String PERKS =
			"켜면 팀 레벨이 오를 때 증강을 고르고 효과가 쌓입니다. 끄면 선택창이 안 뜨고 "
					+ "이미 가진 증강 효과도 전부 멈춥니다.";

	public static final String DIFFICULTY =
			"켜면 게임을 시작한 뒤 시간이 흐를수록 몹이 점점 강해집니다. "
					+ "끄면 난이도가 처음 그대로 유지됩니다.";

	/**
	 * 드래곤 시련.
	 *
	 * <p><b>다른 일곱과 달리 기본값을 문구에 적는다.</b> 「팀을 만든 뒤 못 바꾼다」를 뺀 것과
	 * 같은 결로 생략하려 했지만, 이 설정만은 <b>기본값이 곧 사고의 원인</b>이다 — 2026-09-30
	 * 전까지 시련은 늘 켜져 있었고, 이제는 팀을 만들 때마다 손으로 켜야 한다. 안 켠 채로
	 * 엔드까지 간 뒤에야 「왜 시련이 안 뜨지」를 묻게 되는데, 그때는 팀을 해체하는 수밖에 없다.
	 */
	public static final String DRAGON_TRIAL =
			"켜면 드래곤이 인원 비례로 세지고 시련 카드가 뜹니다. "
					+ "끄면(기본값) 바닐라 드래곤전 그대로입니다.";

	public static final String DAMAGE_ALERT =
			"켜면 팀원이 맞을 때마다 화면에 알림이 뜹니다. 끄면 알림이 오지 않습니다.";

	/**
	 * 사망 알림. 다른 여섯과 달리 <b>대가</b>를 반드시 적어야 하는 설정이다 — 자세한 까닭은
	 * 클래스 문서를 보라.
	 */
	public static final String DEATH_ALERT =
			"켜면 누군가 죽어 전멸했을 때 채팅과 서버 로그에 남습니다. "
					+ "끄면 기록이 전혀 남지 않아 나중에 원인을 알 수 없습니다.";

	/**
	 * 최대 체력.
	 *
	 * <p><b>하트로 환산해 적지 않는다.</b> 이 저장소는 피해·체력 숫자 옆에 하트를 나란히 적던
	 * 것을 일부러 걷어냈다 — 숫자 둘이 붙어 있으면 어느 쪽이 무엇인지 헷갈리기만 한다
	 * ({@code DamageLedger} 의 주석과 0.25.0-dev 의 승리 책 변경이 같은 결정이다).
	 */
	public static final String MAX_HEALTH =
			"팀원 전체가 나눠 쓰는 공유 체력의 최댓값입니다. 한 명이 맞아도 이 체력이 깎입니다.";

	public static final String POSITION_SWAP =
			"정해 둔 시간마다 팀원들의 위치를 서로 맞바꿉니다. 끄면 위치가 그대로 유지됩니다.";

	public static final String REROLL =
			"증강을 고를 때 후보를 다시 뽑을 수 있는 횟수입니다. "
					+ "회차당 이만큼만 쓸 수 있고, 다 쓰면 더 못 뽑습니다.";

	private TeamCreationTooltips() {
	}
}
