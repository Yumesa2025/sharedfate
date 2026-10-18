package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「폭풍 날갯짓」에 얹은 종말의 비({@link DragonLastStandWingRain}) — 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>사람 말(2026-10-18): 「종말의 비를 날개 퍼덕이기 할 때 떨구는 거야. 주변에 번개처럼, 지속시간 동안
 * 3차례, 개수는 6개 정도로」. 시각표 · 자리 · 점 예산 · 피해 셈 · 카드와 같은 길을 지나는지를 붙든다.
 */
class DragonLastStandWingRainTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandPatterns.clearState();
	}

	/** 사람이 정한 값 둘 — 세 차례 · 여섯 곳. */
	@Test
	void 세_차례_여섯_곳이다() {
		assertEquals(3, DragonLastStandWingRain.ROUNDS, "「지속시간 동안 3차례」");
		assertEquals(6, DragonLastStandWingRain.SPOTS, "「개수는 6개 정도로」");
		assertEquals(DragonLastStandWingRain.SPOTS, DragonLastStandWingRain.RAIN.minSpots());
		assertEquals(DragonLastStandWingRain.SPOTS, DragonLastStandWingRain.RAIN.maxSpots());
	}

	/** 「카드와 같은 낙하」 — 피해 · 반경 · 예고(구체가 떨어지는 시간)를 카드에서 읽는다. */
	@Test
	void 카드의_값을_그대로_쓴다() {
		TrialCatalog.Risk.EndRain card = card();
		TrialCatalog.Risk.EndRain rain = DragonLastStandWingRain.RAIN;
		assertEquals(card.damage(), rain.damage(), 0.0F, "피해가 카드와 갈렸다");
		assertEquals(23.0F, rain.damage(), 0.0F);
		assertEquals(card.radius(), rain.radius(), 0.0, "반경이 카드와 갈렸다");
		assertEquals(2.5, rain.radius(), 0.0);
		assertEquals(card.warnTicks(), rain.warnTicks(), "예고(낙하 시간)가 카드와 갈렸다");
		assertEquals(30, rain.warnTicks());
		assertTrue(TrialEndRain.usable(rain), "실행기의 셈이 받지 않는 모양이다");
	}

	/**
	 * 시각표 — 열림 0 · 33 · 66, 착탄 30 · 63 · 96. 마지막 착탄이 마지막 충격파 몫이 끝나는 96틱이고 패턴 길이
	 * 100 안이다. 두 차례가 함께 살아 있지 않고, 착탄끼리 피격 쿨타임 10틱에 먹히지 않는다.
	 */
	@Test
	void 세_차례가_패턴_안에_고르게_떨어진다() {
		assertEquals(33, DragonLastStandWingRain.ROUND_TICKS);
		int[] opens = {0, 33, 66};
		int[] lands = {30, 63, 96};
		for (int index = 0; index < DragonLastStandWingRain.ROUNDS; index++) {
			assertEquals(opens[index], DragonLastStandWingRain.openStep(index), index + "차례 열림");
			assertEquals(lands[index], DragonLastStandWingRain.landStep(index), index + "차례 착탄");
		}
		int last = DragonLastStandWingRain.landStep(DragonLastStandWingRain.ROUNDS - 1);
		assertEquals(DragonLastStandPatterns.WING_PULSES * DragonLastStandPatterns.WING_PULSE_TICKS, last,
				"마지막 착탄이 마지막 충격파 몫이 끝나는 틱이 아니다");
		assertTrue(last < DragonLastStand.Pattern.WING_BEAT.durationTicks(),
				"마지막 차례가 패턴이 끝난 뒤에 떨어진다 — 다음 패턴과 겹친다");
		assertEquals(100, DragonLastStand.Pattern.WING_BEAT.durationTicks(),
				"패턴 길이는 그대로다 — HUD 시전 바 · 쉼 · allowed() 가 이 값을 본다");
		assertTrue(DragonLastStandWingRain.ROUND_TICKS > DragonLastStandWingRain.RAIN.warnTicks(),
				"두 차례가 함께 살아 있으면 구체와 바닥 면이 두 벌이 되고 점 셈이 거짓이 된다");
		assertTrue(DragonLastStandWingRain.ROUND_TICKS > DragonLastStandPatterns.HURT_COOLDOWN_GUARD_TICKS,
				"착탄 간격이 피격 쿨타임 안이면 다음 차례가 통째로 먹힌다");
	}

	/**
	 * <b>상태 머신을 패턴 한 판 동안 통째로 굴린다</b>(2026-10-18 밤 — 공개 배포 직전 검토가 「결함은 없으나 tick 순서
	 * 시험이 없다」고 했다). {@code tick} 과 같은 차례 판단({@code next})을 0~패턴 끝까지 돌려 열림 · 착탄 틱과
	 * 「같은 틱에 착탄과 열림이 겹치지 않는다」 · 「패턴이 끝날 때 떨어지는 것이 없다」를 본다.
	 */
	@Test
	void 상태_머신이_시각표대로_열고_떨어뜨리고_비운다() {
		boolean falling = false;
		int round = -1;
		java.util.List<Integer> opened = new java.util.ArrayList<>();
		java.util.List<Integer> landed = new java.util.ArrayList<>();
		for (int step = 0; step < DragonLastStand.Pattern.WING_BEAT.durationTicks(); step++) {
			DragonLastStandWingRain.Move move = DragonLastStandWingRain.next(falling, round, step);
			if (move == DragonLastStandWingRain.Move.LAND) {
				landed.add(step);
				falling = false;
				// 같은 틱에 다음 차례를 열지 않는다 — tick 이 여기서 돌아간다.
				continue;
			}
			if (move == DragonLastStandWingRain.Move.OPEN) {
				round = DragonLastStandWingRain.dueRound(step);
				opened.add(step);
				falling = true;
			}
		}
		assertEquals(java.util.List.of(0, 33, 66), opened, "열림 틱이 시각표와 다르다");
		assertEquals(java.util.List.of(30, 63, 96), landed, "착탄 틱이 시각표와 다르다");
		assertFalse(falling, "패턴이 끝났는데 떨어지는 중인 차례가 남았다 — 다음 패턴과 겹친다");
		assertEquals(DragonLastStandWingRain.ROUNDS - 1, round);
		// 패턴 뒤로는 아무것도 열지 않는다(넷째 차례 없음).
		for (int step = 100; step < 400; step++) {
			assertEquals(DragonLastStandWingRain.Move.HOLD, DragonLastStandWingRain.next(false, round, step),
					step + "틱에 넷째 차례를 연다");
		}
		// 자리를 하나도 못 고른 차례(허공뿐)는 떨어지는 것이 없어 다음 차례를 제때 연다.
		assertEquals(DragonLastStandWingRain.Move.OPEN, DragonLastStandWingRain.next(false, 0, 33),
				"앞 차례가 비었다고 다음 차례가 밀린다");
		assertEquals(DragonLastStandWingRain.Move.HOLD, DragonLastStandWingRain.next(false, 0, 31),
				"빈 차례 뒤 착탄 틱 사이에 같은 차례를 또 연다");
	}

	/** 지금 열려 있어야 할 차례 — 0틱에 첫 차례, 66틱부터 끝까지 셋째. */
	@Test
	void 차례_경계() {
		assertEquals(-1, DragonLastStandWingRain.dueRound(-1));
		assertEquals(0, DragonLastStandWingRain.dueRound(0));
		assertEquals(0, DragonLastStandWingRain.dueRound(32));
		assertEquals(1, DragonLastStandWingRain.dueRound(33));
		assertEquals(2, DragonLastStandWingRain.dueRound(66));
		assertEquals(2, DragonLastStandWingRain.dueRound(99));
		assertEquals(2, DragonLastStandWingRain.dueRound(500), "넷째 차례는 없다");
	}

	/** 「주변에 번개처럼」 — 상시 번개와 같은 식(마지막 지대 − 반경)으로 드래곤 둘레에 몰린다. */
	@Test
	void 자리가_드래곤_둘레의_마지막_지대_안이다() {
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		assertEquals(zone - DragonLastStandWingRain.RAIN.radius(), DragonLastStandWingRain.FIELD_RADIUS,
				1.0E-9, "자리 반경을 지대에서 뽑지 않으면 지대를 고칠 때 한쪽만 따라간다");
		assertEquals(15.5, DragonLastStandWingRain.FIELD_RADIUS, 1.0E-9);
		assertTrue(DragonLastStandWingRain.FIELD_RADIUS < TrialRisks.ARENA_RADIUS / 2.0,
				"아레나 전체에 흩뿌리면 「주변에 번개처럼」이 아니다");
		for (int one = 0; one <= 40; one++) {
			for (int other = 0; other <= 40; other++) {
				Vec3 offset = DragonLastStandWingRain.spotOffset(one / 40.0, other / 40.0);
				double from = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
				assertTrue(from + DragonLastStandWingRain.RAIN.radius() <= zone + 1.0E-9,
						"표식 하나가 마지막 지대 밖으로 삐져나온다: " + from);
			}
		}
	}

	/**
	 * 점 — 구체 틱 24 · 착탄 틱 48(카드가 실제로 쓰는 지점당 8). 착탄을 지점당 12 로 그리면 페이즈 합이 407 이라
	 * 400 을 넘는다.
	 */
	@Test
	void 점_예산() {
		assertEquals(8, DragonLastStandWingRain.IMPACT_POINTS, "카드 90곳의 실제 착탄 점(800 ÷ 90)이다");
		assertEquals(TrialEndRain.impactPoints(card()), DragonLastStandWingRain.IMPACT_POINTS);
		assertEquals(24, TrialEndRain.tickPoints(DragonLastStandWingRain.RAIN), "구체 6 × 4");
		assertEquals(48, DragonLastStandWingRain.impactTickPoints(), "착탄 6 × 8");
		assertEquals(48, DragonLastStandWingRain.points(), "구체와 착탄은 한 틱에 겹치지 않는다");
		int unclipped = TrialEndRain.impactTickPoints(DragonLastStandWingRain.RAIN);
		assertEquals(72, unclipped, "지점이 여섯이면 실행기가 지점당 12 를 준다");
		int phase = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick();
		assertTrue(phase <= 400, "페이즈 한 틱 최악 " + phase + " — 400 을 넘었다");
		assertTrue(phase - DragonLastStandWingRain.points() + unclipped > 400,
				"착탄을 지점당 12 로 그려도 400 안이면 8 로 줄인 까닭이 사라졌다 — 클래스 설명을 고칠 것");
	}

	/**
	 * 피해 — 카드와 같은 피해원(폭발 · 하드 1.5배 · 방어 듣음)이라 무장 기준 한 대 6.77, <b>세 대에 전멸</b>.
	 * 적힌 최악은 여섯 겹(138)으로 센다 — 실제로는 같은 틱의 둘째부터 피격 쿨타임이 먹는다.
	 */
	@Test
	void 피해가_큰_카드_기준이다() {
		float geared = GearedDamage.afterGear(DragonLastStandWingRain.RAIN.damage(), GearedDamage.Source.EXPLOSION);
		assertTrue(GearedDamage.wipesInThree(geared), "무장 기준 두 대로는 안 죽고 세 대에 죽어야 한다: " + geared);
		assertEquals(23.0F * 6, DragonLastStandWingRain.worstCaseTickDamage(), 1.0E-4F,
				"지점보다 많이 겹칠 수는 없다 — 여섯 겹이다");
		assertEquals(23.0F * TrialEndRain.WORST_CASE_OVERLAP, TrialRisks.worstCaseTickDamage(card()), 1.0E-4F,
				"카드 쪽 셈(9겹)이 함께 움직였다");
	}

	/** 대처법 문구와 이름은 그대로다 — 사람이 정한 문구다. */
	@Test
	void 이름과_대처법이_그대로다() {
		assertEquals("폭풍 날갯짓", TrialTimers.nameOf(DragonLastStand.Pattern.WING_BEAT));
		assertEquals("넉백 버티기", TrialTimers.hintOf(DragonLastStand.Pattern.WING_BEAT));
		assertEquals(0, TrialTimers.warnTicksOf(DragonLastStand.Pattern.WING_BEAT),
				"시전 바는 그대로 「진행 100틱」이다");
	}

	/** 그리기 · 터뜨리기 · 바닥 면은 카드 실행기의 것을 그대로 부르고, 이 파일은 피해를 직접 넣지 않는다. */
	@Test
	void 카드와_같은_길을_지난다() {
		String bytes = read("/com/sharedfate/sync/DragonLastStandWingRain.class");
		assertTrue(bytes.contains("detonate"), "착탄이 카드의 detonate 를 안 지난다");
		assertTrue(bytes.contains("dropOrb"), "구체가 카드의 dropOrb 를 안 지난다");
		assertTrue(bytes.contains("impactSound"), "착탄음이 카드와 갈렸다");
		assertTrue(bytes.contains("com/sharedfate/sync/TrialEndRainPanel"), "보라 바닥 면이 없다");
		assertFalse(bytes.contains("hurtServer"), "피해를 여기서 따로 넣으면 피해원 · 한 자리 한 발이 두 벌이 된다");
		assertFalse(bytes.contains("playSound"), "팀원 루프의 playSound 는 사람 수만큼 겹친다");
		assertFalse(bytes.contains("getGameTime"), "받은 시각을 쓸 것");
		assertTrue(bytes.contains("arenaOffset"), "분포가 번개 · 카드와 달라졌다");
		assertFalse(bytes.contains("ARENA_RADIUS"), "아레나 반경을 넘기면 지대 밖에 떨어진다");
		String patterns = read("/com/sharedfate/sync/DragonLastStandPatterns.class");
		assertTrue(patterns.contains("com/sharedfate/sync/DragonLastStandWingRain"),
				"날갯짓이 비를 안 부른다 — 또는 clearState 가 면을 안 거둔다");
	}

	// ------------------------------------------------------------------ 도우미

	private static TrialCatalog.Risk.EndRain card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:end_rain");
		assertNotNull(trial, "「종말의 비」 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.EndRain rain) {
				return rain;
			}
		}
		throw new AssertionError("「종말의 비」의 위험이 종말의 비가 아니다");
	}

	private static String read(String path) {
		try (InputStream stream = DragonLastStandWingRainTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, path + " 를 클래스패스에서 찾지 못했다");
			return new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException broken) {
			throw new AssertionError(path + " 를 읽지 못했다", broken);
		}
	}
}
