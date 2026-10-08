package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
 * <p>{@link #boost} 는 바닐라가 0(착지)을 내지 않은 굴림마다 팀 목록을 한 번 연다. 그것도
 * 같은 「경로가 끝난 틱」에만 오므로 약 2초에 한 번이다.
 *
 * <p>⚠ {@code getGameTime()} 을 쓰지 않는다 — 이 파일은 시각을 아예 묻지 않는다. 주사위는
 * 바닐라가 굴리는 것이고 우리는 그 상한만 깎는다.
 *
 * <h2>체력 80% 가 터진 뒤에는 ×{@value #HEALTH_80_BOOST} (2026-10-04)</h2>
 *
 * <p>사람 말: <b>「80프로 터지고 착지 확률을 좀 더 올렸으면 좋겠어. 지금보다 30프로는 더」</b>
 *
 * <p>천장을 씌운 확률 {@code 1/n} 에 1.3 을 곱한다. {@code 1.3/n} 은 정수 {@code nextInt} 로
 * 적을 수 없으므로 상한을 깎는 길({@link #cap})로는 못 한다. 그래서 <b>바닐라 주사위는 그대로
 * 굴리고</b>, 그 주사위가 「안 앉는다」를 낸 경우에만 {@link #boost} 가 한 번 더 굴린다.
 *
 * <pre>
 *   P(착지) = 1/n + (1 - 1/n) × q,   q = (1.3/n - 1/n) / (1 - 1/n) = 0.3 / (n - 1)
 * </pre>
 *
 * <table border="1">
 *   <caption>체력 80% 이후 한 굴림의 착지 확률과 평균 착지 간격(굴림 약 2초마다)</caption>
 *   <tr><th>크리스탈</th><th>지금(n)</th><th>×1.3</th><th>q</th><th>평균 간격 전 → 후</th></tr>
 *   <tr><td>0</td><td>1/3</td><td>0.4333</td><td>0.15</td><td>6초 → 4.6초</td></tr>
 *   <tr><td>1</td><td>1/4</td><td>0.325</td><td>0.1</td><td>8초 → 6.2초</td></tr>
 *   <tr><td>2</td><td>1/5</td><td>0.26</td><td>0.075</td><td>10초 → 7.7초</td></tr>
 *   <tr><td>3 이상</td><td>1/6</td><td>0.2167</td><td>0.06</td><td>12초 → 9.2초</td></tr>
 * </table>
 *
 * <p><b>「80% 가 터졌는가」를 새로 재지 않는다.</b> {@code DragonTrialManager} 가 체력 비율
 * {@code ≤ 0.80} 인 틱에 {@code session.fire(Trigger.HEALTH_80)} 을 부르고, 그 자리가 세션의
 * {@link DragonTrialSession#fired()} 에 남는다. 처음 내려간 한 번만 세므로 크리스탈로 체력이
 * 80% 위로 되올라가도 이 값은 풀리지 않는다 — 「80프로 터지고」가 정확히 그 사건이다.
 * {@link DragonTrialSession#fire} 가 시련을 끈 세션에서는 아무것도 쌓지 않으므로 <b>시련이 꺼진
 * 판은 이 곱에 닿을 길이 구조적으로 없다.</b>
 *
 * <p>⚠ <b>80% 이전과 시련이 꺼진 판에서는 두 번째 굴림 자체가 없다.</b> 확률만 같은 것이 아니라
 * {@code RandomSource} 를 한 번도 더 건드리지 않으므로 바닐라 난수 흐름까지 그대로다.
 * {@code DragonLandingDiceTest} 가 같은 씨앗의 주사위 둘로 그것을 못박는다.
 *
 * <p>최후의 저항(30%)에서는 위의 「아무 일도 하지 않는다」가 그대로 성립한다 — 두 번째
 * 굴림도 {@code findNewTarget} 안에서만 일어나고 그 메서드는 {@code HOVERING} 동안 돌지 않는다.
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

	/**
	 * 체력 80% 가 터진 뒤 착지 확률에 곱하는 값.
	 *
	 * <p>사람이 2026-10-04 에 「80프로 터지고 착지 확률을 좀 더 올렸으면 좋겠어. 지금보다 30프로는
	 * 더」라고 했다. 「30프로 더」를 확률에 곱하는 1.3 으로 읽었다 — 1/6 이 0.2167 이 된다.
	 * 확률에 0.3 을 <b>더하는</b> 뜻이었다면 1/6 이 0.467 로 세 배 가까이 뛰므로 사람이 말한
	 * 「좀 더」와 맞지 않는다.
	 */
	static final float HEALTH_80_BOOST = 1.3F;

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
	 * 바닐라 주사위가 굴린 값을 받아 <b>체력 80% 이후라면 한 번 더 기회</b>를 준다.
	 *
	 * <p>{@code DragonHoldingPatternLandingMixin} 이 부르는 자리다. 바닐라는 돌려준 값이
	 * <b>0 이면 착지</b>한다({@code findNewTarget} 의 {@code ifne} — 26.3 바이트코드에서 확인했다).
	 *
	 * <p>순서가 비용이다 — 이미 0(착지)이면 팀 목록을 열지 않고, 80% 가 안 터졌으면 난수를
	 * 건드리지 않는다. 그래서 <b>80% 이전과 시련이 꺼진 판에서는 바닐라 난수 흐름까지 그대로</b>다.
	 *
	 * @param level  드래곤이 있는 판. 서버 판이 아니면 손대지 않는다
	 * @param random 바닐라가 굴린 그 주사위({@code dragon.getRandom()})
	 * @param bound  바닐라 주사위에 실제로 넘긴 상한. {@link #cap} 을 지난 값이다
	 * @param rolled 바닐라 주사위가 낸 값
	 */
	public static int boost(@Nullable Level level, RandomSource random, int bound, int rolled) {
		// 이미 앉기로 나왔으면 더 볼 것이 없다.
		if (rolled == 0) {
			return rolled;
		}
		return anyHealth80Passed(level) ? reroll(random, bound, rolled) : rolled;
	}

	/**
	 * 두 번째 굴림. <b>산수와 난수만</b> 쓴다 — 판을 모르므로 시험이 분포를 직접 잰다.
	 *
	 * @param rolled 바닐라 주사위가 낸 값. 0 이 아니어야 뜻이 있다
	 */
	static int reroll(RandomSource random, int bound, int rolled) {
		return random.nextFloat() < extraChance(bound) ? 0 : rolled;
	}

	/** 체력 80% 이후 한 굴림의 착지 확률. 1 을 넘지 않는다. */
	static float boostedChance(int bound) {
		if (bound <= 1) {
			return 1.0F;
		}
		return Math.min(1.0F, HEALTH_80_BOOST / bound);
	}

	/**
	 * 바닐라가 「안 앉는다」를 냈을 때 두 번째 굴림이 앉힐 확률 {@code q}.
	 *
	 * <p>{@code 1/n + (1 - 1/n) × q = 1.3/n} 을 {@code q} 로 푼 값이고, 정리하면
	 * {@code 0.3 / (n - 1)} 이다. 상한이 1 이하면 바닐라가 언제나 0 을 내므로 여기까지 오지 않는다.
	 */
	static float extraChance(int bound) {
		if (bound <= 1) {
			return 0.0F;
		}
		float base = 1.0F / bound;
		return (boostedChance(bound) - base) / (1.0F - base);
	}

	/**
	 * 이 세션이 <b>시련이 돌고 체력 80% 가 이미 터진</b> 전투인가.
	 *
	 * <p>판별을 새로 짜지 않는다 — {@code DragonTrialManager} 가 체력 비율 {@code ≤ 0.80} 인 틱에
	 * {@code fire(HEALTH_80)} 을 부르고 그것이 {@link DragonTrialSession#fired()} 에 남는다.
	 * {@code fire} 는 시련을 끈 세션에서 아무것도 쌓지 않지만, 「시련이 도는가」를 한 번 더 묻는
	 * 것은 그 성질이 바뀌는 날에도 시련을 끈 판이 바닐라로 남게 하려는 것이다.
	 */
	static boolean health80Passed(@Nullable DragonTrialSession session) {
		return trialLive(session) && session.fired().contains(TrialCatalog.Trigger.HEALTH_80);
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

	/**
	 * 이 판에 <b>시련을 켜고 체력 80% 가 터진 전투가 하나라도</b> 있는가.
	 *
	 * <p>{@link #anyTrialLive} 와 같은 까닭으로 팀을 가리지 않는다 — 드래곤이 하나뿐이다.
	 * 모를 때는 거짓(바닐라 쪽)이다.
	 */
	private static boolean anyHealth80Passed(@Nullable Level level) {
		if (!(level instanceof ServerLevel server)) {
			return false;
		}
		MinecraftServer host = server.getServer();
		if (host == null) {
			return false;
		}
		for (ShareTeam team : TeamManager.get(host).allTeams()) {
			if (health80Passed(DragonTrialManager.sessionOf(team.teamId()))) {
				return true;
			}
		}
		return false;
	}
}
