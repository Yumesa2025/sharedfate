package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 드래곤 <b>기본 패시브</b> — 착지 주사위에 <b>천장</b>을 둔다.
 *
 * <p>사람 말: <b>「엔더드래곤이 진짜 착지 자체를 너무 안 함」</b> →
 * <b>「크리스탈 영향에 천장 — 최대 1/6 으로 못박아 평균 12초에 한 번 내려앉게. 『크리스탈이
 * 살아 있으면 덜 앉는다』는 방향은 남기고, 『부활 = 착지 봉인』도 약하게만 남긴다」</b>
 *
 * <h2>바닐라가 착지를 어떻게 고르는가 — 26.3 바이트코드에서 확인했다</h2>
 *
 * <p>{@code DragonHoldingPatternPhase.findNewTarget} 이 이렇게 돈다.
 *
 * <pre>{@code
 * if (currentPath != null && currentPath.isDone()) {          // 경로가 끝난 틱에만 (약 2초마다)
 *     int alive = fight == null ? 0 : fight.aliveCrystals();
 *     if (random.nextInt(alive + 3) == 0) { setPhase(LANDING_APPROACH); return; }
 *     ... strafe ...
 * }
 * }</pre>
 *
 * <p>곧 착지 확률이 <b>{@code 1/(살아 있는 크리스탈 + 3)}</b> 이다. 열 개면 <b>1/13</b>(평균
 * 26초), 0 개면 1/3. 바닐라는 「크리스탈을 다 깨면 앉는다」로 읽히게 짠 것이다.
 *
 * <p>우리 판은 크리스탈을 훨씬 오래 살려 둔다 — 입장 연출이 열 개를 되살리고, 크리스탈 카드
 * 넷이 깨는 것을 늦추고, 「부활」이 열 개를 다시 세운다. 그래서 바닐라의 그 기울기가
 * <b>「거의 안 앉는다」</b>로 읽혔다.
 *
 * <h2>천장 {@value #CEILING} 한 줄이 곧 {@code min(alive, 3) + 3} 이다</h2>
 *
 * <p>고치는 것은 <b>{@code nextInt} 에 넘기는 값</b> 하나다. 넘기는 값이 {@code alive + 3} 이므로
 * {@code Math.min(값, 6)} 은 그대로 {@code min(alive, 3) + 3} 이고, 결과가 이렇게 된다.
 *
 * <table border="1">
 *   <caption>살아 있는 크리스탈 수에 따른 한 굴림의 착지 확률</caption>
 *   <tr><th>크리스탈</th><th>바닐라</th><th>우리</th></tr>
 *   <tr><td>0</td><td>1/3</td><td><b>1/3</b> (같다)</td></tr>
 *   <tr><td>1</td><td>1/4</td><td><b>1/4</b> (같다)</td></tr>
 *   <tr><td>2</td><td>1/5</td><td><b>1/5</b> (같다)</td></tr>
 *   <tr><td>3</td><td>1/6</td><td><b>1/6</b> (같다)</td></tr>
 *   <tr><td>4</td><td>1/7</td><td>1/6</td></tr>
 *   <tr><td>10</td><td>1/13</td><td>1/6</td></tr>
 * </table>
 *
 * <p><b>크리스탈 0~3 개 구간은 바닐라와 한 글자도 다르지 않다.</b> 천장이 일하는 것은 넷 이상
 * 서 있을 때뿐이고, 그래서 사람이 남기라고 한 두 가지가 그대로 남는다.
 *
 * <ul>
 *   <li><b>「크리스탈이 살아 있으면 덜 앉는다」</b> — 0→3 개 사이에서 확률이 1/3 에서 1/6 으로
 *       여전히 반씩 깎인다. 깎이는 것을 없앤 것이 아니라 <b>바닥을 깐 것</b>이다</li>
 *   <li><b>「부활 = 착지 봉인」을 약하게만 남긴다</b> — 「부활」이 열 개를 다시 세우면 1/13 이
 *       아니라 1/6 이 된다. 착지가 사라지는 것이 아니라 <b>가장 느린 값에 머무는 것</b>이고,
 *       {@code docs/드래곤-트라이얼.md} 가 의도로 적어 둔 「착지 충격이 안 터진다」는
 *       <b>여전히 느려지지만 영영은 아니다</b>로 바뀐다</li>
 * </ul>
 *
 * <p>주사위는 <b>경로가 끝난 틱에만</b> 굴러간다 — 아레나 한 바퀴라 약 2초다. 1/6 이면 평균
 * 여섯 굴림이고 곧 <b>약 12초에 한 번</b>이다. 사람이 말한 「평균 12초」가 그 셈이다.
 *
 * <h2>⚠⚠ 시련이 꺼진 판에서는 <b>바닐라 그대로여야 한다</b></h2>
 *
 * <p>인계 문서가 못박고 있다 — <b>「끄면 … 곧 완전한 바닐라 엔더드래곤전입니다」</b>. 착지
 * 확률이 달라지면 그 말이 거짓이 되므로 이 천장도 같은 규칙을 따른다.
 *
 * <p><b>깃발을 새로 만들지 않았다.</b> 「시련이 도는가」를 이미 들고 있는 것이
 * {@code DragonTrialManager} 의 세션이고, 그 세션이 {@code TeamState.dragonTrialsEnabled} 를
 * 전투가 열린 틱에 한 번 복사해 둔 것이
 * {@link DragonTrialSession#trialsEnabled()} 다. 여기서는 그 둘을 <b>읽기만</b> 한다
 * ({@link DragonTrialManager#sessionOf}). 같은 뜻의 깃발을 하나 더 세우면 내리는 자리를
 * 빠뜨린 날 「시련을 껐는데 드래곤만 시련처럼 구는」 판이 생긴다.
 *
 * <h2>왜 팀을 가리지 않고 「하나라도」인가</h2>
 *
 * <p><b>드래곤은 차원에 하나뿐이고 그 주사위도 하나다.</b> 팀 A 가 시련을 켜고 팀 B 가 껐다면
 * 둘이 <b>같은 드래곤</b>을 때리고 있으므로 「이 굴림은 누구의 것인가」라는 질문에 답이 없다.
 * 그래서 팀별로 가르려 하지 않고 <b>시련을 켠 전투가 하나라도 열려 있는가</b>로 묻는다.
 *
 * <p>이것은 새 규칙이 아니다 — {@code DragonTrialManager.startSession} 이 드래곤 최대 체력을
 * 올리는 것도 이미 <b>차원에 하나뿐인 드래곤</b>에 걸리고, 「먼저 연 팀의 값」이 그대로
 * 나머지에게도 적용된다. 혼자 싸우는 판(이 모드가 상정하는 판)에서는 둘이 같은 답이다.
 *
 * <h2>「최후의 저항」에서는 아무 일도 하지 않는다</h2>
 *
 * <p>체력 30% 의 별개 보스전은 드래곤을 {@code EnderDragonPhase.HOVERING} 으로 잠근다.
 * 그 칸에서 이 천장이 해롭지 않은 까닭이 <b>둘로 겹쳐 있다.</b>
 *
 * <ol>
 *   <li><b>{@code findNewTarget} 이 아예 안 돈다.</b> 그 메서드는
 *       {@code DragonHoldingPatternPhase.doServerTick} 안에서만 불리고
 *       ({@code javap -c} 로 확인했다 — 호출 자리가 하나다), 바닐라는 <b>지금 페이즈의 인스턴스
 *       하나만</b> 틱을 돌린다. {@code HOVERING} 인 동안 홀딩 패턴은 틱을 받지 않는다</li>
 *   <li><b>굴러도 되돌려진다.</b> 혹 그 틱이 오더라도 천장이 바꾸는 것은 {@code nextInt} 의
 *       상한뿐이고, 최악이 {@code LANDING_APPROACH} 로 한 틱 새는 것이다. 그런데
 *       {@code DragonLastStand.hold} 가 <b>매 틱 페이즈를 보고 {@code HOVERING} 이 아니면 다시
 *       누른다</b>. 곧 다음 틱에 제자리다</li>
 * </ol>
 *
 * <p>⚠ 그래도 이 파일이 최후의 저항을 <b>스스로 거르지는 않는다.</b> 최후의 저항은 시련의
 * 일부이므로 위의 「시련이 도는가」가 참인 구간이고, 거기에 조건을 하나 더 두면 <b>같은 질문의
 * 답이 두 곳</b>이 된다.
 *
 * <h2>비용 — 천장 아래면 팀을 한 번도 뒤지지 않는다</h2>
 *
 * <p>{@link #cap} 의 첫 줄이 {@code vanilla <= CEILING} 이다. 크리스탈이 셋 이하인 모든 굴림은
 * 거기서 그대로 빠져나가므로 <b>바닐라와 같은 답이 나오는 구간에서는 팀 목록을 열지도
 * 않는다.</b> 남는 경우도 「경로가 끝난 틱」, 곧 <b>약 2초에 한 번</b>이다.
 *
 * <p>⚠ {@code getGameTime()} 을 쓰지 않는다 — 이 파일은 시각을 아예 묻지 않는다. 주사위는
 * 바닐라가 굴리는 것이고 우리는 그 상한만 깎는다.
 *
 * <h2>왜 패시브인데 {@link DragonPassives} 에 줄이 없는가</h2>
 *
 * <p>{@link DragonPassives} 에 붙는 패시브는 <b>매 틱 할 일이 있는 것</b>이다(포격은 자리를
 * 그리고, 착지는 깃발을 세운다). 이 천장은 <b>상태도 시계도 없는 순수 함수</b>라 틱을 받을
 * 일이 없고, 비울 상태가 없으니 {@code clearState()} 에 적을 것도 없다. 틱을 받지 않는 것이
 * 곧 「지난 판의 값이 샐 수 없다」이기도 하다.
 */
public final class DragonLandingDice {

	/**
	 * {@code nextInt} 에 넘기는 값의 천장. <b>곧 착지 확률의 바닥이 1/{@value} 다.</b>
	 *
	 * <p>사람이 고른 값이다 — 「최대 1/6 으로 못박아 평균 12초에 한 번」. 주사위가 약 2초마다
	 * 굴러가므로 1/6 이 평균 열두 초다.
	 *
	 * <p>⚠ <b>3 아래로 내리지 말 것.</b> 넘기는 값이 {@code alive + 3} 이라 천장을 3 으로 두면
	 * {@code alive} 가 몇이든 1/3 이 되어 <b>「크리스탈이 살아 있으면 덜 앉는다」가 통째로
	 * 사라진다.</b> 6 은 그 기울기(0~3 개 구간)를 한 칸도 건드리지 않는 가장 낮은 천장이다.
	 */
	static final int CEILING = 6;

	private DragonLandingDice() {
	}

	/**
	 * 바닐라가 {@code nextInt} 에 넘기려는 값을 받아 <b>넘길 값</b>을 돌려준다.
	 *
	 * <p>{@code DragonHoldingPatternLandingMixin} 이 부르는 유일한 자리다. 돌려준 값이 그대로
	 * 바닐라의 주사위 상한이 되므로, <b>바꾸지 않을 때는 받은 값을 그대로 돌려준다</b> — 0 이나
	 * 음수를 만들어 돌려주면 바닐라가 {@code IllegalArgumentException} 으로 터진다.
	 *
	 * @param level   드래곤이 있는 판. 서버 판이 아니면 손대지 않는다
	 * @param vanilla 바닐라가 셈한 값. {@code 살아 있는 크리스탈 + 3} 이다
	 */
	public static int cap(@Nullable Level level, int vanilla) {
		// 천장 아래면 답이 바닐라와 같다. 팀 목록을 열 까닭이 없다.
		if (vanilla <= CEILING) {
			return vanilla;
		}
		return anyTrialLive(level) ? ceiling(vanilla) : vanilla;
	}

	/**
	 * 천장을 씌운 값. <b>산수만</b> 한다.
	 *
	 * <p>{@code Math.min} 한 줄인 까닭은 넘기는 값이 {@code alive + 3} 이기 때문이다 — 그래서
	 * 이 한 줄이 {@code min(alive, 3) + 3} 과 같은 식이고, 크리스탈 0~3 개 구간이 바닐라와
	 * 완전히 같다(클래스 설명의 표).
	 */
	static int ceiling(int vanilla) {
		return Math.min(vanilla, CEILING);
	}

	/**
	 * 이 세션이 <b>시련이 도는 전투</b>인가.
	 *
	 * <p>판별을 새로 짜지 않는다 — {@link DragonTrialSession#trialsEnabled()} 가 전투가 열린 틱에
	 * {@code TeamState.dragonTrialsEnabled} 를 복사해 둔 값이고, {@code DragonTrialManager} 의
	 * 세션 루프도 그 한 줄로 끈 팀을 통째로 지나간다. 시험이 직접 부르는 자리이기도 하다.
	 */
	static boolean trialLive(@Nullable DragonTrialSession session) {
		return session != null && session.trialsEnabled();
	}

	/**
	 * 이 판에 <b>시련을 켠 전투가 하나라도 열려 있는가</b>.
	 *
	 * <p>팀을 가리지 않는 까닭은 클래스 설명의 「왜 팀을 가리지 않고 하나라도인가」에 있다 —
	 * 드래곤이 차원에 하나뿐이라 굴림의 주인을 물을 수 없다.
	 *
	 * <p>{@code null} 이거나 서버 판이 아니면 <b>거짓</b>이다. 모를 때는 기본값(끔)과 같은 쪽으로
	 * 간다 — {@code DragonTrialManager.trialsEnabled} 가 팀 상태를 못 읽을 때 쓰는 규칙 그대로다.
	 */
	private static boolean anyTrialLive(@Nullable Level level) {
		if (!(level instanceof ServerLevel server)) {
			return false;
		}
		MinecraftServer host = server.getServer();
		if (host == null) {
			return false;
		}
		for (ShareTeam team : TeamManager.get(host).allTeams()) {
			if (trialLive(DragonTrialManager.sessionOf(team.teamId()))) {
				return true;
			}
		}
		return false;
	}
}
