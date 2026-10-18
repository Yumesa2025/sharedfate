package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 일시 중단을 지나도 한 번짜리 카드가 <b>다시 터지지 않는다</b>(2026-10-18 검토).
 *
 * <p>멈출 때 시련이 판에 건 것을 되돌리는 길({@code TrialRisks.clearState})이 실행기의 「이미 썼다」 기억까지
 * 비워, 재개 첫 틱에 부활이 크리스탈 다섯을 또 살리고 메마른 세계가 또 증발시켰다. 서버 재시작도 같았다.
 * 이제 멈출 때 {@code TrialRisks.snapshotDone} 이 세션에 적고, 이을 때 받은 틱을 민 뒤
 * {@code TrialRisks.restoreDone} 이 다시 심는다. 실제 배선({@code DragonTrialManager.suspend · resume})의 순서
 * 그대로 월드 없이 굴린다.
 */
class TrialResumeMemoryTest {

	private static final String REVIVAL = "sharedfate:crystal_revival";
	private static final String DRY = "sharedfate:dry_world";
	private static final String CAGE = "sharedfate:iron_cage";
	private static final String NIGHT = "sharedfate:night_host";

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 상태를_비운다() {
		TrialRisks.clearState();
	}

	private static DragonTrialSession sessionWith(String trialId, long granted) {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), 1_000L, true);
		session.fire(TrialCatalog.Trigger.HEALTH_50);
		session.beginChoice();
		assertTrue(session.choose(trialId, granted));
		return session;
	}

	private static <R extends TrialCatalog.Risk> R risk(String trialId, Class<R> type) {
		return type.cast(TrialCatalog.byId(trialId).risks().getFirst());
	}

	/** ⚠⚠ 부활이 끝난 카드는 멈췄다 이어도 크리스탈을 또 살리지 않는다. */
	@Test
	void 부활은_멈췄다_이어도_다시_살리지_않는다() {
		TrialCatalog.Risk.CrystalRevive revive = risk(REVIVAL, TrialCatalog.Risk.CrystalRevive.class);
		long granted = 2_000L;
		DragonTrialSession session = sessionWith(REVIVAL, granted);
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(granted, granted + revive.showTicks(), revive));

		// 멈춘다 — 판을 거두기 전에 적고, 거둔다.
		long pausedAt = granted + revive.showTicks() + 40L;
		session.rememberDone(TrialRisks.snapshotDone(session));
		session.suspend(false, 900.0F, -1, pausedAt);
		TrialRisks.clearState();

		// 10분 뒤 잇는다 — 받은 틱을 민 뒤 심는다.
		long now = pausedAt + 12_000L;
		session.resume(now);
		long shifted = session.grantedTick(REVIVAL);
		assertEquals(granted + 12_000L, shifted);
		TrialRisks.restoreDone(session);

		assertEquals(TrialCrystalRevive.Step.NOTHING, TrialCrystalRevive.step(shifted, now, revive),
				"재개 첫 틱에 부활이 크리스탈 다섯을 또 살린다");
	}

	/** 연출 도중에 멈춘 부활은 이을 때 남은 연출을 마저 돌고 한 번 살린다 — 건너뛰지도 두 번 살리지도 않는다. */
	@Test
	void 연출_도중에_멈춘_부활은_남은_연출을_잇는다() {
		TrialCatalog.Risk.CrystalRevive revive = risk(REVIVAL, TrialCatalog.Risk.CrystalRevive.class);
		long granted = 2_000L;
		DragonTrialSession session = sessionWith(REVIVAL, granted);
		assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(granted, granted + 30L, revive));
		session.rememberDone(TrialRisks.snapshotDone(session));
		assertFalse(session.riskDone().containsKey(REVIVAL + "#0"), "아직 안 살린 카드를 「했다」로 적었다");
		session.suspend(false, 900.0F, -1, granted + 30L);
		TrialRisks.clearState();

		long now = granted + 30L + 50_000L;
		session.resume(now);
		TrialRisks.restoreDone(session);
		long shifted = session.grantedTick(REVIVAL);
		assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(shifted, now, revive),
				"끊긴 사이에 창이 지나 연출 없이 바로 살아나거나 아예 안 살아난다");
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(shifted, shifted + revive.showTicks(), revive));
	}

	/** 메마른 세계 — 이미 증발시켰으면 이을 때 또 증발·양동이 비우기·채팅을 하지 않는다. */
	@Test
	void 메마른_세계는_다시_증발시키지_않는다() {
		long granted = 3_000L;
		DragonTrialSession session = sessionWith(DRY, granted);
		TrialDryWorld.markDried(granted);
		session.rememberDone(TrialRisks.snapshotDone(session));
		assertEquals(1, session.riskDone().get(DRY + "#0"));
		session.suspend(false, 900.0F, -1, granted + 100L);
		TrialRisks.clearState();
		assertFalse(TrialDryWorld.dried(granted));

		session.resume(granted + 700L);
		TrialRisks.restoreDone(session);
		assertTrue(TrialDryWorld.dried(session.grantedTick(DRY)), "재개 첫 틱에 메마른 세계가 또 증발시킨다");
	}

	/** 다시 선 쇠창살 — 받은 틱을 밀면 5초 창이 다시 열리므로, 이미 세웠으면 다시 세우지 않는다. */
	@Test
	void 쇠창살은_다시_세우지_않는다() {
		long granted = 4_000L;
		DragonTrialSession session = sessionWith(CAGE, granted);
		String key = TrialRisks.riskKey(CAGE, 0);
		TrialCrystalGuard.markCaged(key, granted);
		session.rememberDone(TrialRisks.snapshotDone(session));
		session.suspend(false, 900.0F, -1, granted + 20L);
		TrialRisks.clearState();

		long now = granted + 20L + 6_000L;
		session.resume(now);
		long shifted = session.grantedTick(CAGE);
		assertTrue(TrialCrystalGuard.cagesNow(TrialCrystalGuard.caged(key, shifted), now, shifted),
				"전제 — 밀린 받은 틱으로는 창이 다시 열린다");
		TrialRisks.restoreDone(session);
		assertFalse(TrialCrystalGuard.cagesNow(TrialCrystalGuard.caged(key, shifted), now, shifted),
				"재개하자 쇠창살 다섯을 또 세운다");
	}

	/** 밤의 군세 — 아직 한 마리도 못 불렀으면 적지 않는다(이을 때 평소처럼 다섯을 부른다). */
	@Test
	void 부르지_않은_밤은_적지_않는다() {
		TrialCatalog.Risk.NightHost host = risk(NIGHT, TrialCatalog.Risk.NightHost.class);
		DragonTrialSession session = sessionWith(NIGHT, 5_000L);
		assertEquals(-1, TrialNightHost.survivors(5_000L, host));
		assertTrue(TrialRisks.snapshotDone(session).isEmpty());
		// 적어 둔 값(멈출 때 살아 있던 셋)을 심어도 터지지 않는다 — 다시 부를 수는 월드에서 쓴다.
		session.rememberDone(Map.of(NIGHT + "#0", 3));
		TrialRisks.restoreDone(session);
	}
}
