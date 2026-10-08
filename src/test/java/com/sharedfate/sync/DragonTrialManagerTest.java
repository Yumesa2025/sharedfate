package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 세션 루프의 <b>수명</b> — 얼어 있는 틱에 실행기를 다시 돌리지 않는 것(V3)과, 시련 세션을 한 번에
 * 하나로 두는 것(V2). 둘 다 2026-10-06 검토에서 확정된 문제다.
 *
 * <p>월드 없이 두 순수 함수({@link DragonTrialManager#executorsDue},
 * {@link DragonTrialManager#trialHolder})를 굴리고, 그 함수가 실제로 배선돼 있는지는 클래스 바이트로 본다.
 */
class DragonTrialManagerTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ V3 얼어 있는 틱

	/**
	 * 룰렛 하나(정지 337 서버 틱) 동안 게임 시각이 서 있으면 실행기는 <b>한 번</b>만 돈다.
	 *
	 * <p>고치기 전에는 매 서버 틱 돌아 337번이었다 — 발동 틱에 걸리면 폭발음·번개 엔티티·튕김이 그만큼
	 * 되풀이됐다(2026-10-06 검토에서 확정된 문제).
	 */
	@Test
	void 얼어_있는_동안_같은_시각으로는_한_번만_돈다() {
		long frozenAt = 12_345L;
		long ranAt = Long.MIN_VALUE;
		int runs = 0;
		for (int serverTick = 0; serverTick < 337; serverTick++) {
			if (DragonTrialManager.executorsDue(ranAt, frozenAt)) {
				runs++;
			}
			ranAt = frozenAt;
		}
		assertEquals(1, runs, "게임 시각이 선 337 서버 틱 동안 실행기가 " + runs + "번 돌았다");
	}

	/** 시각이 흐르면 매 틱 돈다 — 문이 평소 판을 막으면 시련이 통째로 멈춘다. */
	@Test
	void 시각이_흐르면_매_틱_돈다() {
		long ranAt = Long.MIN_VALUE;
		int runs = 0;
		for (long now = 1_000L; now < 1_100L; now++) {
			if (DragonTrialManager.executorsDue(ranAt, now)) {
				runs++;
			}
			ranAt = now;
		}
		assertEquals(100, runs);
	}

	/** 첫 틱과, 월드가 바뀌어 시각이 거꾸로 간 틱은 돈다 — {@code >} 로 짜면 뒤쪽이 영영 선다. */
	@Test
	void 첫_틱과_거꾸로_간_시각은_돈다() {
		assertTrue(DragonTrialManager.executorsDue(Long.MIN_VALUE, 0L), "처음 한 번도 안 돌았는데 막혔다");
		assertTrue(DragonTrialManager.executorsDue(50_000L, 10L),
				"시각이 거꾸로 가면 그 시각을 다시 지날 때까지 실행기가 서 버린다");
	}

	/**
	 * 문이 실제로 배선돼 있는가 — 얼어 있는 틱에 건너뛰는 문과, 그 틱에도 보호막 맥박만은 잇는 줄.
	 *
	 * <p>고치기 전의 클래스에는 두 이름이 다 없다.
	 */
	@Test
	void 세션_루프가_얼어_있는_틱을_가른다() {
		String bytes = managerBytes();
		assertTrue(bytes.contains("executorsDue"), "세션 루프가 「같은 시각인가」를 묻지 않는다(V3)");
		assertTrue(bytes.contains("holdShield"),
				"얼어 있는 틱에 보호막 맥박을 안 잇는다 — 진입 연출 중 증강 선택이 뜨면 그동안 드래곤이 맞는다");
	}

	// ------------------------------------------------------------------ V2 시련 세션은 하나

	/** 시련을 켠 세션이 있으면 그 팀이 자리를 쥐고 있다. 다른 팀은 시련 끔으로 열린다. */
	@Test
	void 시련_세션이_있으면_다른_팀은_자리를_못_얻는다() {
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		List<DragonTrialSession> sessions = List.of(new DragonTrialSession(first, 100L, true));
		assertEquals(first, DragonTrialManager.trialHolder(sessions, second));
	}

	/** 제 세션은 세지 않는다 — {@code forceStart} 가 같은 팀을 다시 열 때 스스로에게 막히면 안 된다. */
	@Test
	void 제_세션은_자리를_막지_않는다() {
		UUID team = UUID.randomUUID();
		List<DragonTrialSession> sessions = List.of(new DragonTrialSession(team, 100L, true));
		assertNull(DragonTrialManager.trialHolder(sessions, team));
	}

	/** 시련을 끈 세션은 자리를 쥐지 않는다 — 바닐라 판은 시련의 정적 상태를 쓰지 않는다. */
	@Test
	void 시련을_끈_세션은_자리를_쥐지_않는다() {
		List<DragonTrialSession> sessions = List.of(
				new DragonTrialSession(UUID.randomUUID(), 100L, false),
				new DragonTrialSession(UUID.randomUUID(), 200L, false));
		assertNull(DragonTrialManager.trialHolder(sessions, UUID.randomUUID()));
		assertNull(DragonTrialManager.trialHolder(sessions, null),
				"시련 세션이 없는데 endTrials 가 「아직 싸우는 시련 팀이 있다」로 읽는다");
	}

	/**
	 * 세 문이 다 같은 판단을 지나고, 자리를 못 얻은 팀에게 채팅으로 알린다.
	 *
	 * <p>{@code trialHolder} 를 부르는 자리는 {@code startSession} · {@code detectArrival} ·
	 * {@code onServerStarted} · {@code endTrials} 넷이다. 이름만으로는 몇 번 불리는지 못 세므로
	 * 알림 문장이 상수 풀에 있는지를 함께 본다.
	 */
	@Test
	void 자리를_못_얻은_팀에게_알린다() {
		assertTrue(managerBytes().contains("trialHolder"), "시련 세션 하나 규칙이 배선에서 빠졌다(V2)");
		assertTrue(managerText().contains(DragonTrialManager.TRIAL_SLOT_TAKEN_NOTICE),
				"시련 끔으로 연 팀에게 까닭을 알리는 문장이 없다 — 「설정이 안 먹는다」로 읽힌다");
		assertTrue(managerBytes().contains("sendSystemMessage"),
				"알림 문장은 있는데 채팅으로 보내는 줄이 없다");
		assertTrue(DragonTrialManager.TRIAL_SLOT_TAKEN_NOTICE.contains("시련"));
	}

	// ------------------------------------------------------------------ 도구

	private static String managerBytes() {
		return read(StandardCharsets.ISO_8859_1);
	}

	/** 상수 풀의 <b>한글 문자열</b>을 찾을 때만 쓴다. */
	private static String managerText() {
		return read(StandardCharsets.UTF_8);
	}

	private static String read(java.nio.charset.Charset charset) {
		String path = "/com/sharedfate/sync/DragonTrialManager.class";
		try (InputStream in = DragonTrialManagerTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
