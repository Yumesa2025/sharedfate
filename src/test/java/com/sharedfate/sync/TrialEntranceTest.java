package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 입장 연출이 <b>월드 없이 확인할 수 있는 만큼</b>을 붙든다.
 *
 * <p>여기서 지키는 것은 둘이고, 둘 다 <b>눈으로 보고 잡을 수 없는</b> 종류다.
 *
 * <ol>
 *   <li><b>크리스탈을 거둔 사이에 자리가 터지지 않는다</b> — {@link TrialEntrance#managesCrystals}
 *       가 참인 동안 {@code DragonTrialManager} 는 크리스탈을 세지 않는다. 이 창이 한 틱이라도
 *       짧으면 「첫 크리스탈」과 「크리스탈 전멸」이 팀이 아무것도 하지 않았는데 터진다. 그것도
 *       <b>전투가 시작된 지 10초 안에</b> 터지므로 룰렛이 두 번 겹쳐 뜬다.</li>
 *   <li><b>한 틱 점 예산을 넘지 않는다</b> — 점 하나가 파티클 꾸러미 한 장이고 거리 제한을 끄고
 *       보내므로 차원 안 전원에게 나간다. 예산을 넘으면 파티클만으로 틱이 밀린다.</li>
 * </ol>
 */
class TrialEntranceTest {

	/** 전투가 열린 시각. 0 을 쓰면 「안 더한 것」과 구별되지 않는다. */
	private static final long STARTED = 1_000L;

	private static DragonTrialSession session() {
		return new DragonTrialSession(UUID.randomUUID(), STARTED, true);
	}

	/**
	 * 연출이 <b>끝나는 틱까지</b> 크리스탈을 주관한다.
	 *
	 * <p>끝나는 틱({@code CINEMATIC_TICKS})이 포함이어야 한다. 그 틱에
	 * {@link TrialCrystalRevive} 가 무적을 걷고 부활을 마무리하는데, 같은 틱에 기준값을 적으면
	 * 그 순서가 {@code DragonTrialManager.tickSessions} 의 호출 순서에 달리게 된다.
	 */
	@Test
	void 연출이_끝나는_틱까지_크리스탈을_주관한다() {
		DragonTrialSession session = session();

		assertTrue(TrialEntrance.managesCrystals(session, STARTED),
				"전투가 열린 그 틱에 이미 거두기 시작한다");
		assertTrue(TrialEntrance.managesCrystals(session,
						STARTED + TrialEntrance.VANISH_TICKS - 1),
				"빈 하늘 구간 — 크리스탈이 하나도 없다");
		assertTrue(TrialEntrance.managesCrystals(session,
						STARTED + TrialEntrance.VANISH_TICKS + TrialEntrance.PERCH_TICKS),
				"부활이 시작되는 틱. 크리스탈이 서지만 아직 무적이고 거둬질 수도 있다");
		assertTrue(TrialEntrance.managesCrystals(session,
						STARTED + TrialEntrance.CINEMATIC_TICKS),
				"연출이 끝나는 그 틱까지는 주관한다");
		assertFalse(TrialEntrance.managesCrystals(session,
						STARTED + TrialEntrance.CINEMATIC_TICKS + 1),
				"그 다음 틱부터는 바닐라와 같은 상태다 — 여기서 참이면 자리가 영영 안 터진다");
	}

	/**
	 * 부활이 <b>시작된 뒤에도</b> 계속 주관한다.
	 *
	 * <p>크리스탈이 이미 서 있는 구간이라 「이제 세도 되겠다」로 보이는데, 그 구간에 사람이 기둥
	 * 위로 올라오면 {@code TrialCrystalRevive.prune} 이 그 크리스탈을 거둔다 — 거기서 기준값을
	 * 적었다면 <b>「첫 크리스탈」이 사람 하나가 올라선 것만으로 터진다.</b>
	 */
	@Test
	void 부활이_도는_동안에도_세지_않는다() {
		DragonTrialSession session = session();
		long reviveStart = STARTED + TrialEntrance.VANISH_TICKS + TrialEntrance.PERCH_TICKS;

		for (long now = reviveStart; now <= STARTED + TrialEntrance.CINEMATIC_TICKS; now++) {
			assertTrue(TrialEntrance.managesCrystals(session, now),
					"부활 " + (now - reviveStart) + "틱 — 아직 우리 것이다");
		}
	}

	/**
	 * 복원 직후 {@code now} 가 전투 시작보다 <b>작을</b> 수 있다.
	 *
	 * <p>세션은 저장 파일에서 오고 게임 시각은 월드에서 온다. 그대로 빼면 음수가 되는데
	 * {@code TrialRisks.elapsedSinceGrant} 가 0 으로 자른다 — 여기서 거짓이 나오면 연출이
	 * 크리스탈을 거둔 채로 기준값이 0 개로 적혀 「크리스탈 전멸」이 곧바로 터진다.
	 */
	@Test
	void 시각이_뒤로_가_있어도_연출_중으로_본다() {
		assertTrue(TrialEntrance.managesCrystals(session(), STARTED - 500L));
	}

	/** 세션이 없으면 주관하지 않는다. 전투가 없는데 자리를 막을 이유가 없다. */
	@Test
	void 세션이_없으면_거짓이다() {
		assertFalse(TrialEntrance.managesCrystals(null, STARTED));
	}

	/**
	 * 한 틱 점 예산을 넘지 않는다.
	 *
	 * <p>예산은 400~440 이다({@code TrialEnderPulse.MAX_POINTS_PER_TICK} ·
	 * {@code DragonFireBarrage.MARK_MAX_POINTS} 의 설명). 이 연출이 가장 많이 쓰는 틱은 드래곤이
	 * 자리에 앉는 틱이다.
	 */
	@Test
	void 한_틱_점_예산_안이다() {
		assertTrue(TrialEntrance.WORST_TICK_POINTS <= 400,
				"입장 연출이 한 틱에 " + TrialEntrance.WORST_TICK_POINTS + "점을 쓴다 — 예산 밖이다");
		assertTrue(TrialEmpower.WORST_TICK_POINTS <= 400,
				"80% 연출이 한 틱에 " + TrialEmpower.WORST_TICK_POINTS + "점을 쓴다 — 예산 밖이다");
	}
}
