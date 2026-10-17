package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 드래곤 시련의 <b>엔드 월드 보더</b>. 월드 없이 답이 정해지는 셈과 배선을 붙든다.
 *
 * <p>2026-10-18 사람 지시: 「드래곤전 시작하면 월드보더가 제일 끝에서 생기고 2페이지 넘어올 때 원래
 * 줄어들기 시작할 때부터 줄어들게 하고 싶어. 월드보더로 아예 못 넘어가게, 넘어가서 딜 입히는 건 폐기」.
 * 사람이 고른 것: 보더는 섬 가장자리에서 시작(네모라는 것을 알고), 강제 종료로 남은 보더는 <b>지문이
 * 우리 것일 때만</b> 바닐라 기본값으로 — 운영자가 정한 보더는 건드리지 않는다.
 */
class DragonTrialBorderTest {

	/** 포디움 — 바닐라 기본 전투 원점의 착지 자리. */
	private static final Vec3 PODIUM = new Vec3(0.5, 64.0, 0.5);

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 기억을_비운다() {
		DragonTrialBorder.clearState();
	}

	/** 반변을 한 변으로 바꾸는 곱이 한 곳에 있다. 반변을 그대로 넘기면 지대가 <b>절반</b>이 된다. */
	@Test
	void 반변이_한_변의_절반이다() {
		assertEquals(84.0, DragonTrialBorder.sizeOf(42.0), 1.0E-9);
		assertEquals(36.0, DragonTrialBorder.sizeOf(18.0), 1.0E-9);
		assertEquals(0.0, DragonTrialBorder.sizeOf(-3.0), 1.0E-9, "음수 반변이 음수 크기가 됐다");
	}

	/**
	 * ⚠ <b>섬 가장자리 보더가 본섬 · 바깥 관문 고리 · 흑요석 도착 발판을 다 덮는다.</b>
	 *
	 * <p>섬 밀도식이 중앙 92칸에서 0 이고 관문 고리가 반경 96, 엔드 포털로 들어온 사람이 떨어지는 5×5
	 * 발판의 동쪽 끝이 x = 103.0 이다. 발판 끝과 벽 사이에 2칸은 있어야 도착한 사람이 벽에 안 걸린다.
	 * 너무 크면 「섬 가장자리」가 아니다 — 120 을 넘지 않게 붙든다.
	 */
	@Test
	void 섬_보더가_섬과_관문과_도착_발판을_덮는다() {
		double half = DragonTrialBorder.ISLAND_HALF_SIDE;
		assertTrue(half > 92.0, "본섬 가장자리(92)가 밖이다");
		assertTrue(half > 96.0, "바깥 관문 고리(96)가 밖이다");
		assertTrue(PODIUM.x + half >= 103.0 + 2.0, "흑요석 도착 발판(동쪽 끝 103)에 벽이 너무 가깝다");
		assertTrue(half <= 120.0, "섬 가장자리라기엔 너무 크다");
		assertTrue(half > DragonLastStandZone.START_RADIUS, "섬 보더가 최후의 저항 첫 보더보다 작다");
	}

	/**
	 * ⚠⚠ <b>지문</b> — 칸당 피해 0 이고 중심이 포디움이면 우리가 남긴 보더다. 바닐라 기본값도, 운영자가
	 * 손으로 정한 보더도 우리 것으로 읽지 않는다.
	 */
	@Test
	void 지문이_우리_보더만_가려낸다() {
		WorldBorder.Settings vanilla = WorldBorder.Settings.DEFAULT;
		assertFalse(DragonTrialBorder.looksOurs(vanilla.damagePerBlock(), vanilla.centerX(),
				vanilla.centerZ(), PODIUM), "바닐라 기본값을 우리 것으로 읽었다");
		assertTrue(DragonTrialBorder.looksOurs(0.0, 0.5, 0.5, PODIUM));
		assertFalse(DragonTrialBorder.looksOurs(0.2, 0.5, 0.5, PODIUM),
				"칸당 피해가 있는 보더를 우리 것으로 읽었다 — 우리는 언제나 0 이다");
		assertFalse(DragonTrialBorder.looksOurs(0.0, 0.0, 0.0, PODIUM),
				"운영자가 (0, 0) 에 피해 없이 세운 보더를 우리 것으로 읽었다");
		assertFalse(DragonTrialBorder.looksOurs(0.0, 100.5, 0.5, PODIUM));
	}

	/** 움직이던 보더를 기억했으면 <b>목표값</b>으로 되돌린다. 시작값으로 되돌리면 옛 크기가 튄다. */
	@Test
	void 움직이던_보더는_목표값으로_되돌린다() {
		WorldBorder.Settings still = new WorldBorder.Settings(0.0, 0.0, 0.2, 5.0, 5, 15, 500.0, 0L, 500.0);
		assertEquals(500.0, DragonTrialBorder.restoredSize(still), 1.0E-9);
		WorldBorder.Settings moving = new WorldBorder.Settings(0.0, 0.0, 0.2, 5.0, 5, 15, 500.0, 200L, 300.0);
		assertEquals(300.0, DragonTrialBorder.restoredSize(moving), 1.0E-9);
	}

	/**
	 * 줄이기 시작 크기 — 재시작으로 바닐라 크기(약 6천만)로 돌아온 보더에서 남은 몇 초 동안 줄이면 벽이
	 * 하늘 끝에서 날아온다. 있어야 할 크기에서 시작하고, 이미 더 작으면 거기서 시작해 바깥으로 튀지 않는다.
	 */
	@Test
	void 줄이기는_튀지_않는_크기에서_시작한다() {
		assertEquals(64.0, DragonTrialBorder.lerpStart(59_999_968.0, 64.0, 44.0), 1.0E-9,
				"바닐라 크기에서 줄이기 시작했다");
		assertEquals(50.0, DragonTrialBorder.lerpStart(50.0, 64.0, 44.0), 1.0E-9, "벽이 바깥으로 튄다");
		assertEquals(44.0, DragonTrialBorder.lerpStart(30.0, 64.0, 44.0), 1.0E-9, "목표보다 작게 시작했다");
	}

	/** 기억이 없으면 「들고 있지 않다」이고, {@code clearState} 는 월드를 안 만지고 기억만 버린다. */
	@Test
	void 처음에는_들고_있지_않다() {
		assertFalse(DragonTrialBorder.held());
		DragonTrialBorder.restore(null);
		assertFalse(DragonTrialBorder.held());
	}

	// ------------------------------------------------------------------ 바이트코드

	/** 「넘어가서 딜 입히는 건 폐기」 — 우리가 잡은 보더는 칸당 피해를 0 으로 둔다. */
	@Test
	void 보더_피해를_끈다() {
		String bytes = read("/com/sharedfate/sync/DragonTrialBorder.class");
		assertTrue(bytes.contains("setDamagePerBlock"), "보더 피해를 끄지 않는다");
		assertTrue(bytes.contains("lerpSizeBetween"), "보더를 줄이지 않는다");
	}

	/**
	 * ⚠⚠ <b>세우고 되돌리는 배선</b>. 시련 세션이 섬 보더를 세우고, 시련 세션이 없는 틱에 되돌리고,
	 * 서버가 뜰 때 남은 보더를 치우고, 내려갈 때 저장보다 먼저 되돌리고, 상태를 비울 때 기억을 버린다.
	 * 하나라도 빠지면 보더가 다음 판이나 오버월드로 나간 뒤까지 남는다.
	 */
	@Test
	void 세우고_되돌리는_배선이_있다() {
		String manager = read("/com/sharedfate/sync/DragonTrialManager.class");
		assertTrue(manager.contains("com/sharedfate/sync/DragonTrialBorder"), "관리자가 보더를 모른다");
		assertTrue(manager.contains("ISLAND_HALF_SIDE") || manager.contains("hold"),
				"전투가 열려도 섬 보더가 안 선다");
		assertTrue(manager.contains("restoreBorderIfIdle"), "전투가 닫혀도 보더가 안 돌아간다");
		assertTrue(manager.contains("onServerStarted"), "강제 종료로 남은 보더를 안 치운다");
		assertTrue(manager.contains("clearState"));
		String stand = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(stand.contains("com/sharedfate/sync/DragonTrialBorder"),
				"종료 훅(DragonLastStand.onServerStopping)이 보더를 되돌리지 않는다");
	}

	private static String read(String path) {
		try (InputStream in = DragonTrialBorderTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
