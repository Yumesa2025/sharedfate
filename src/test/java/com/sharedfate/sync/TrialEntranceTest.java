package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 입장 연출이 <b>월드 없이 확인할 수 있는 만큼</b>을 붙든다.
 *
 * <p>앞의 둘은 <b>눈으로 보고 잡을 수 없는</b> 종류다. 마지막 하나는 반대로 <b>눈에만 보이는</b>
 * 것인데, 그래서 되살리기도 쉬워 값으로 붙들어 둔다.
 *
 * <ol>
 *   <li><b>크리스탈을 거둔 사이에 자리가 터지지 않는다</b> — {@link TrialEntrance#managesCrystals}
 *       가 참인 동안 {@code DragonTrialManager} 는 크리스탈을 세지 않는다. 이 창이 한 틱이라도
 *       짧으면 「첫 크리스탈」과 「크리스탈 전멸」이 팀이 아무것도 하지 않았는데 터진다. 그것도
 *       <b>전투가 시작된 지 10초 안에</b> 터지므로 룰렛이 두 번 겹쳐 뜬다.</li>
 *   <li><b>한 틱 점 예산을 넘지 않는다</b> — 점 하나가 파티클 꾸러미 한 장이고 거리 제한을 끄고
 *       보내므로 차원 안 전원에게 나간다. 예산을 넘으면 파티클만으로 틱이 밀린다.</li>
 *   <li><b>걷어낸 부제가 돌아오지 않는다</b> — 사람이 「크리스탈을 전부 부숩니다」를 빼라고 했다.
 *       글 한 줄은 다음 사람이 「설명이 없네」 하고 되넣기 가장 쉬운 것이라 상수 풀을 직접 본다.</li>
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

	/**
	 * ⚠ 크리스탈이 사라지는 것을 <b>글로 알리지 않는다.</b>
	 *
	 * <p>사람이 <b>「입장할떄 크리스탈을 전부 부숩니다. 이거 문구 필요없어 뺴」</b>라고 해서 부제를
	 * 걷어냈다. 글은 <b>되살리기 쉽다</b> — 한 줄이고, 지운 까닭이 코드에 남지 않으면 다음 사람이
	 * 「아무 설명도 없네」 하고 다시 넣는다. 그래서 상수 풀에 그 글자가 없는지를 직접 본다.
	 *
	 * <p><b>타이틀은 함께 지키고 있다.</b> 부제를 빼면서 타이틀까지 지우면 연출이 열리는 신호가
	 * 소리와 흔들림뿐이 되는데, 사람이 뺀 것은 부제 하나다.
	 */
	@Test
	void 크리스탈이_사라졌다는_글이_없다() throws IOException {
		String bytes = classBytes();
		assertFalse(bytes.contains(pool("크리스탈이 사라졌습니다")),
				"걷어낸 부제가 돌아왔다 — 사람이 「이거 문구 필요없어 뺴」라고 한 줄이다");
		assertTrue(bytes.contains(pool("엔더 드래곤 시련 전투")),
				"타이틀까지 지웠다 — 연출이 열리는 신호가 글에서 완전히 사라진다");
	}

	/**
	 * 클래스 상수 풀에서 찾을 꼴로 바꾼다.
	 *
	 * <p>클래스 파일의 문자열은 UTF-8(수정 UTF-8)로 적혀 있고 {@link #classBytes} 는 바이트를
	 * 한 글자씩 그대로 든다. 그래서 한글은 <b>UTF-8 로 쪼갠 뒤 같은 방식으로 들어야</b> 맞는다 —
	 * 한글을 그냥 넣고 찾으면 늘 「없다」가 나와 시험이 아무것도 안 지킨다.
	 */
	private static String pool(String text) {
		return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String classBytes() throws IOException {
		String resource = "/com/sharedfate/sync/TrialEntrance.class";
		try (InputStream in = TrialEntrance.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException(resource + " 를 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
