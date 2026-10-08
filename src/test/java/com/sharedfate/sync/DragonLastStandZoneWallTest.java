package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
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
 * 안전지대의 <b>빨간 입자 벽</b>. 점 예산 · 나눠 세우기 · 「조금 앞서 그리기」를 붙든다.
 *
 * <p>2026-10-04 에 사람이 「안전지대가 사실상 보더라 나갈 수가 없어」라고 해서 보더를 버리고 이
 * 벽이 생겼다. 보더는 점을 하나도 안 썼고 이 벽은 <b>언제나</b> 점을 쓰므로, 이 페이즈의 한 틱
 * 최악에 그대로 더해진다 — 그 합이 여기서 못박힌다.
 */
class DragonLastStandZoneWallTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 점 예산

	/** 반경 42 에서 기둥 189개 · 한 틱 13개 · 점 39. 값에서 나온다. */
	@Test
	void 반경_42에서_한_틱_39점이다() {
		assertEquals(189, DragonLastStandZoneWall.postsAt(42.0), "둘레 263.9 ÷ 간격 1.4");
		assertEquals(39, DragonLastStandZoneWall.pointsPerTickAt(42.0), "⌈189 ÷ 15⌉ × 3");
		assertEquals(39, DragonLastStandZoneWall.worstPointsPerTick(), "가장 넓은 반경이 시작 42 다");
		assertTrue(DragonLastStandZoneWall.pointsPerTickAt(12.0)
						< DragonLastStandZoneWall.pointsPerTickAt(42.0),
				"좁아질수록 점이 줄어야 한다");
	}

	/**
	 * ⚠⚠ <b>이 페이즈의 한 틱 최악이 400 을 넘지 않는다.</b>
	 *
	 * <p>벽은 패턴 · 상시 번개 · 오브젝트 파도와 <b>언제나 함께</b> 돈다. 그래서 남의 최악 둘에 그대로
	 * 더한다 — 지금 패턴 322 + 파도 37 + 벽 39 = 398. 남의 값을 숫자로 적지 않고 그쪽 함수에서 읽으므로
	 * 누가 어느 쪽을 올려도 여기서 먼저 멈춘다. 예산의 위 끝은
	 * {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)이고, 「종말의 비」·「연쇄 포격」이 그 위 끝에
	 * 붙어 있어 이 페이즈는 <b>아래 끝 400</b>을 지킨다.
	 */
	@Test
	void 페이즈_한_틱_최악이_400을_넘지_않는다() {
		int total = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick()
				+ DragonLastStandZoneWall.worstPointsPerTick();
		assertTrue(total <= 400, "패턴 + 파도 + 벽 = " + total + " — 400 을 넘었다");
		assertTrue(total <= TrialLandingShock.MAX_POINTS_PER_TICK, "예산의 위 끝마저 넘었다");
	}

	/** 진입 연출 동안에도 벽이 서 있다. 그때 함께 도는 보호막과 더해도 예산 안이다. */
	@Test
	void 진입_연출과_더해도_예산_안이다() {
		int total = DragonLastStandShield.worstShieldTickPoints()
				+ DragonLastStandZoneWall.worstPointsPerTick();
		assertTrue(total <= 400, "보호막 + 연출 + 벽 = " + total);
	}

	// ------------------------------------------------------------------ 나눠 세우기

	/**
	 * ⚠ <b>나눠 세우는 폭이 먼지 최소 수명보다 작다.</b> 크면 기둥이 영영 안 이어진다.
	 *
	 * <p>26.3 {@code DustParticleBase} 의 수명은 {@code max(1, (int)(8 ÷ (난수×0.8 + 0.2)) × scale)} 라
	 * 최소가 {@code 8 × scale} 이다. 크기 2.0 이면 16틱이고 나눠 세우기가 15틱이다.
	 */
	@Test
	void 나눠_세우는_폭이_먼지_수명보다_작다() {
		int shortestLife = (int) (8 * DragonLastStandZoneWall.DUST_SCALE);
		assertTrue(DragonLastStandZoneWall.STRIDE < shortestLife,
				"나눠 세우기 " + DragonLastStandZoneWall.STRIDE + "틱이 먼지 최소 수명 " + shortestLife
						+ "틱 이상이다 — 먼저 세운 기둥이 다음 차례 전에 사라진다");
		assertTrue(DragonLastStandZoneWall.DUST_SCALE <= 4.0F, "먼지 크기는 4 가 바닐라 상한이다");
	}

	/** 기둥이 <b>사람 키</b>다. 바닥 선만으로는 서서 보는 눈높이에서 안 보인다. */
	@Test
	void 기둥이_사람_키다() {
		double[] heights = DragonLastStandZoneWall.POST_HEIGHTS;
		double top = heights[heights.length - 1];
		assertTrue(top >= 1.6 && top <= 2.0, "맨 위가 사람 키(1.8) 근처가 아니다 — 실제 " + top);
		for (int index = 1; index < heights.length; index++) {
			assertTrue(heights[index] > heights[index - 1], "높이가 아래에서 위로 쌓이지 않는다");
		}
		// 가장 넓은 원에서도 기둥 사이가 간격 상수 이하다(올림이라 더 좁기만 하다).
		double actualGap = Math.PI * 2.0 * 42.0 / DragonLastStandZoneWall.postsAt(42.0);
		assertTrue(actualGap <= DragonLastStandZoneWall.POST_GAP + 1.0E-9);
	}

	// ------------------------------------------------------------------ 조금 앞서 그리기

	/**
	 * ⚠⚠ <b>그리는 반경이 판정 반경보다 크지 않다</b> — 표식이 안전한 쪽으로만 틀린다.
	 *
	 * <p>한 기둥을 15틱에 한 번만 다시 세우므로 지금 반경으로 그리면 줄어드는 동안 벽이 실제 경계보다
	 * 바깥에 남아 「벽 안인데 아프다」가 된다. 다음 차례의 반경으로 그리면 벽은 언제나 경계와 같거나
	 * 안쪽이고, 차이는 15틱 × 0.1칸 = 1.5칸을 넘지 않는다. 축소가 없는 구간에서는 정확히 같다.
	 */
	@Test
	void 그리는_반경이_판정_반경보다_크지_않다() {
		double maxLead = DragonLastStandZoneWall.STRIDE * (10.0 / DragonLastStandZone.SHRINK_TICKS);
		for (long elapsed = -200L; elapsed <= 3_000L; elapsed++) {
			double judged = DragonLastStandZone.radiusAt(elapsed);
			double drawn = DragonLastStandZoneWall.drawRadiusAt(elapsed);
			assertTrue(drawn <= judged + 1.0E-9,
					elapsed + "틱에 벽(" + drawn + ")이 경계(" + judged + ")보다 바깥이다");
			assertTrue(judged - drawn <= maxLead + 1.0E-9,
					elapsed + "틱에 벽이 경계보다 " + (judged - drawn) + "칸이나 안쪽이다");
		}
		// 축소가 없는 구간은 같다.
		assertEquals(42.0, DragonLastStandZoneWall.drawRadiusAt(0L), 1.0E-9);
		assertEquals(32.0, DragonLastStandZoneWall.drawRadiusAt(1000L), 1.0E-9);
		assertEquals(12.0, DragonLastStandZoneWall.drawRadiusAt(2300L), 1.0E-9);
		// 진입 연출 동안(시계가 음수)은 시작 원이다 — 「보더가 생기면서」.
		assertEquals(42.0, DragonLastStandZoneWall.drawRadiusAt(-160L), 1.0E-9);
	}

	// ------------------------------------------------------------------ 규약

	/** 긴 형식 · 규약의 빨강 먼지 · 블록을 안 만진다 · 받은 {@code now} 를 쓴다. */
	@Test
	void 벽이_저장소의_규약을_지킨다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"파티클이 긴 형식이 아니다 — 짧은 형식은 32칸에서 잘려 42칸 저편의 벽이 안 보인다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
		assertTrue(bytes.contains("DustParticleOptions"), "규약 색은 먼지로 칠한다");
		assertFalse(bytes.contains("getGameTime"), "받은 now 를 쓸 것");
		assertFalse(bytes.contains("setBlock") || bytes.contains("removeBlock"),
				"블록을 만지는 것은 반구 하나뿐이다");
		assertFalse(bytes.contains("border/WorldBorder"), "보더를 쓰지 않는다");
		// 색은 규약의 빨강 하나다. 상수가 인라인되므로 값으로 본다.
		assertEquals(0xFF3333, TrialWarning.Colors.DEADLY, "규약의 빨강이 바뀌면 이 벽의 뜻도 함께 본다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		try (InputStream in = DragonLastStandZoneWallTest.class
				.getResourceAsStream("/com/sharedfate/sync/DragonLastStandZoneWall.class")) {
			if (in == null) {
				return fail("DragonLastStandZoneWall.class 를 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
