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
		return read("/com/sharedfate/sync/DragonTrialManager.class", charset);
	}

	private static String read(String path, java.nio.charset.Charset charset) {
		try (InputStream in = DragonTrialManagerTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}

	// ------------------------------------------------------------------ 일시 중단 (2026-10-18)

	/**
	 * ⚠ 멈춘 시련 세션은 시련 <b>자리</b>를 쥐지 않는다(사람 결정 2026-10-18 — 「멈춘 A 는 시련 자리를 쥐지 않는다.
	 * B 는 B 설정대로 열고 … 그 순간 A 세션은 닫는다」). 처음 일시 중단이 들어왔을 때는 쥐었고, 그러자 A 가 멈춘
	 * 사이 들어온 B 가 A 의 강화(2400)가 붙은 드래곤과 시련 없이 싸웠다. 판에 걸어 둔 것을 되돌릴지 묻는 자리도
	 * 멈춘 세션을 세지 않는다. 다만 「이 판에 시련 세션의 흔적이 있는가」({@code anyTrialSession} — 저장에서
	 * 되살릴 때 · 주인 없는 드래곤 되돌리기)는 멈춘 세션까지 센다.
	 */
	@Test
	void 멈춘_시련_세션은_자리를_쥐지_않는다() {
		UUID team = UUID.randomUUID();
		DragonTrialSession paused = new DragonTrialSession(team, 100L, true);
		paused.suspend(false, 1000.0F, -1);
		List<DragonTrialSession> sessions = List.of(paused);
		assertNull(DragonTrialManager.trialHolder(sessions, UUID.randomUUID()),
				"멈춘 세션이 다른 팀의 시련 자리를 막는다 — 그 팀이 남의 강화가 붙은 드래곤과 시련 없이 싸운다");
		assertEquals(team, DragonTrialManager.anyTrialSession(sessions, null),
				"멈춘 세션의 드래곤 강화를 주인 없는 것으로 읽어 뗀다 / 옛 저장의 둘째 세션이 시련 켬으로 되살아난다");
		assertNull(DragonTrialManager.anyTrialSession(sessions, team), "제 세션은 세지 않는다");
		assertNull(DragonTrialManager.activeTrialHolder(sessions),
				"멈춘 세션이 보더·시련 흔적을 붙든다 — 엔드가 빈 채로 섬 보더가 남는다");
		paused.resume(200L);
		assertEquals(team, DragonTrialManager.activeTrialHolder(sessions));
		assertNull(DragonTrialManager.activeTrialHolder(
				List.of(new DragonTrialSession(UUID.randomUUID(), 1L, false))), "끈 세션은 시련 세션이 아니다");
	}

	/**
	 * ⚠⚠ 2026-10-18 검토의 배선 — 월드 초기화로 내려갈 때 전투를 일시 중단으로 남기지 않고, 저장 파일이 월드
	 * 폴더에 있고, 멈춘 전투는 부르지 않으며, 다른 팀이 열면 멈춘 시련 세션을 닫고, 재개 직후 3초 보호를 준다.
	 */
	@Test
	void 수명_주기_검토_배선이_있다() {
		String bytes = managerBytes();
		assertTrue(bytes.contains("discardForWorldReset"), "월드 초기화 길이 없다");
		assertTrue(read("/com/sharedfate/sync/WorldResetCoordinator.class", StandardCharsets.ISO_8859_1)
				.contains("discardForWorldReset"),
				"월드 초기화(halt)가 엔드 전투를 버리지 않는다 — 새 회차에 옛 일시 중단 전투가 되살아난다");
		assertTrue(read("/com/sharedfate/sync/DragonTrialStore.class", StandardCharsets.ISO_8859_1)
				.contains("legacy"), "서버 폴더의 옛 저장 파일을 치우는 길이 없다");
		assertTrue(bytes.contains("getWorldPath"), "저장 파일이 월드 폴더에 있지 않다");
		assertTrue(bytes.contains("closePausedTrials"), "다른 팀이 열 때 멈춘 시련 세션을 닫지 않는다");
		assertTrue(managerText().contains(DragonTrialManager.PAUSED_CLOSED_NOTICE),
				"닫힌 팀에게 까닭을 알리는 문장이 없다");
		assertTrue(bytes.contains("protectArrivals"), "재개 직후 3초 보호가 없다");
		assertTrue(bytes.contains("snapshotDone") && bytes.contains("restoreDone"),
				"한 번짜리 카드가 한 일을 멈출 때 적고 이을 때 심지 않는다");
		assertTrue(bytes.contains("weakenDragon"), "시련 끔으로 열 때 남은 강화를 비율대로 떼지 않는다");
	}

	/** 강화 배율 — 0.41 과 같은 값이다(수정자를 저장되는 것으로 바꿨을 뿐). */
	@Test
	void 강화_배율은_그대로다() {
		assertEquals(11.0, DragonTrialManager.healthBoost(200.0, 2400.0), 1.0E-9, "4인 600 × 4 = 2400");
		assertTrue(Double.isNaN(DragonTrialManager.healthBoost(200.0, 200.0)), "강화할 것이 없는데 붙인다");
		assertTrue(Double.isNaN(DragonTrialManager.healthBoost(200.0, 100.0)), "약화시킨다");
		assertTrue(Double.isNaN(DragonTrialManager.healthBoost(0.0, 2400.0)));
	}

	/**
	 * ⚠⚠ 배선. 체력 강화가 <b>저장되는</b> 수정자이고, 「처치」 판정이 일시 중단 판정을 지나며, 서버 종료가
	 * 전투를 멈추고, 주인 없는 드래곤에서 강화를 뗀다.
	 */
	@Test
	void 일시_중단_배선이_있다() {
		String bytes = managerBytes();
		assertTrue(bytes.contains("addOrReplacePermanentModifier"),
				"강화가 저장되지 않는다 — 다시 올라온 드래곤의 체력이 200 에 잘린다");
		assertTrue(!bytes.contains("addOrUpdateTransientModifier"),
				"저장되지 않는 수정자가 남았다(6번 — 재시작한 전투의 드래곤이 200 으로 남는다)");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonTrialSuspend"), "판정이 일시 중단을 모른다");
		assertTrue(bytes.contains("deadDragonInWorld") && bytes.contains("vanillaDragonKilled"),
				"처치의 근거 둘 가운데 하나가 빠졌다");
		assertTrue(bytes.contains("restrengthen"), "이을 때 강화를 다시 확인하지 않는다");
		assertTrue(bytes.contains("removeModifier"), "주인 없는 드래곤에서 강화를 떼지 않는다");
		assertTrue(bytes.contains("releaseOrphan"), "앉아서 굳은 드래곤을 풀지 않는다");
		assertTrue(managerText().contains("전투 일시 중단") && managerText().contains("전투 재개"),
				"로그 문장이 사람이 정한 것과 다르다");
		String mod = read("/com/sharedfate/SharedFateMod.class", StandardCharsets.ISO_8859_1);
		assertTrue(mod.contains("onServerStopping") && mod.contains("com/sharedfate/sync/DragonTrialManager"),
				"서버 종료가 전투를 멈추지 않는다 — 실제 체력을 못 적고 앉힌 채 저장된다");
	}
}
