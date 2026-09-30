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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>패턴 셋</b>. 월드 없이 답이 정해지는 것만 여기서 굴린다.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 죽어 <b>눈으로 확인할 수
 * 없다.</b> 그래서 기하학(부채꼴 안인가 · 번개 다섯이 겹치는가)과 세기(얼마나 미는가)와
 * 예산(한 틱에 몇 점인가)을 전부 순수 함수로 빼내 여기서 붙든다.
 */
class DragonLastStandPatternsTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandPatterns.clearState();
	}

	// ------------------------------------------------------------------ ① 날개 퍼덕이기

	/** 5초간 0.6초마다 여덟 번이다. 사람이 정한 값 셋이 여기 있다. */
	@Test
	void 날개는_5초간_0점6초마다_여덟_번이다() {
		assertEquals(8, DragonLastStandPatterns.WING_PULSES, "사람이 정한 값이다");
		assertEquals(12, DragonLastStandPatterns.WING_PULSE_TICKS, "0.6초다");
		int span = (DragonLastStandPatterns.WING_PULSES - 1)
				* DragonLastStandPatterns.WING_PULSE_TICKS
				+ DragonLastStandPatterns.WING_PULSE_TICKS;
		assertEquals(96, span, "여덟 번이 96틱을 쓴다");
		assertTrue(span <= DragonLastStand.Pattern.WING_BEAT.durationTicks(),
				"패턴 길이가 여덟 번을 담지 못한다 — 마지막 충격파가 잘린다");
		assertEquals(100, DragonLastStand.Pattern.WING_BEAT.durationTicks(), "5초다");
	}

	/** <b>피해가 없다.</b> 넉백만이다. */
	@Test
	void 날개는_피해를_주지_않는다() {
		String bytes = classBytes();
		// hurtServer 는 이 파일에 둘뿐이다(브레스·번개). 날개가 하나 더 부르면 이 시험이 아니라
		// 아래 「피해원」 시험으로는 잡히지 않으므로, 여기서는 날개가 쓰는 도구만 본다.
		assertTrue(bytes.contains("setDeltaMovement"), "날개가 밀지 않는다");
		assertTrue(bytes.contains("syncVelocity"),
				"syncVelocity 를 켜지 않으면 서버 혼자 민 것이 되어 제자리로 되돌아간다");
	}

	/**
	 * 세기 지도가 문서 한 줄 그대로다 — <b>4칸 이내는 약하고 4~12칸이 가장 강하다.</b>
	 */
	@Test
	void 날개_세기가_4칸_이내에서_약하고_4에서_12칸이_가장_강하다() {
		double peak = DragonLastStandPatterns.WING_PUSH_BLOCKS;
		// 4~12칸이 최댓값이다.
		for (double d = 4.0; d <= 12.0; d += 0.1) {
			assertEquals(peak, DragonLastStandPatterns.wingPushBlocks(d), 1.0E-9,
					d + "칸이 가장 강한 구간인데 값이 다르다");
		}
		// 4칸 이내는 그보다 약하고, 가까워질수록 약해진다.
		double previous = peak;
		for (double d = 3.9; d > 0.0; d -= 0.1) {
			double push = DragonLastStandPatterns.wingPushBlocks(d);
			assertTrue(push < peak, d + "칸이 가장 강한 구간과 같은 세기다");
			assertTrue(push <= previous + 1.0E-9, d + "칸에서 세기가 되레 올랐다");
			previous = push;
		}
		// 0 이 아니다. 0 으로 두면 머리 밑이 완전한 안전지대가 된다.
		assertTrue(DragonLastStandPatterns.wingPushBlocks(0.5) > 0.0,
				"드래곤 바로 아래가 「약하다」이지 「없다」가 아니다");
	}

	/** 12칸 밖은 잦아들어 20칸에서 0 이 된다. 절벽처럼 끊으면 12.0 과 12.1 이 완전히 갈린다. */
	@Test
	void 날개_세기가_12칸_밖에서_잦아든다() {
		double previous = DragonLastStandPatterns.WING_PUSH_BLOCKS;
		for (double d = 12.1; d < 20.0; d += 0.1) {
			double push = DragonLastStandPatterns.wingPushBlocks(d);
			assertTrue(push < previous + 1.0E-9, d + "칸에서 세기가 올랐다");
			assertTrue(push > 0.0, d + "칸이 절벽처럼 끊겼다");
			previous = push;
		}
		assertEquals(0.0, DragonLastStandPatterns.wingPushBlocks(20.0), 1.0E-9);
		assertEquals(0.0, DragonLastStandPatterns.wingPushBlocks(80.0), 1.0E-9);
		assertEquals(0.0, DragonLastStandPatterns.wingPushBlocks(0.0), 1.0E-9,
				"드래곤과 정확히 겹쳐 있으면 「바깥쪽」이 없다");
	}

	/**
	 * ⚠ 한 번치가 「강한 넉백」 한 대의 <b>절반</b>이다.
	 *
	 * <p>피해가 없는데 5초 동안 여덟 번 민다. 한 번치가 「착지 충격」·「엔더폭풍」의 한 대와 같으면
	 * 5초 내내 조작이 덮어써져 요구하는 행동을 할 수 없는 패턴이 된다.
	 */
	@Test
	void 날개_한_번치가_강한_넉백의_절반이다() {
		assertEquals(TrialEnderStorm.PUSH_BLOCKS / 2.0, DragonLastStandPatterns.WING_PUSH_BLOCKS,
				1.0E-9, "이 저장소의 「강한 넉백」 단위(8)에서 나온 값이어야 한다");
	}

	/**
	 * ⚠⚠ <b>천장 둘이 걸려 있다.</b> 하나라도 빠지면 이 패턴은 낙사 장치다.
	 *
	 * <p>공유 체력이라 한 사람의 낙사가 팀 전체를 끝내고 그것이 곧 월드 삭제다. 「반경 + 넉백
	 * &lt; 40」으로 검산하지 말고 <b>천장이 걸려 있는지</b>를 보라는 것이 이 저장소의 규칙이다.
	 */
	@Test
	void 날개가_미는_자리에_천장이_둘_걸려_있다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/TrialEnderStorm")
						&& bytes.contains("pushDistance"),
				"목적지를 섬 안으로 자르는 천장이 없다 — 「엔더폭풍」의 pushDistance 를 그대로 쓸 것");
		assertTrue(bytes.contains("com/sharedfate/sync/TrialLandingShock")
						&& bytes.contains("groundedReach"),
				"길에 땅이 이어져 있는지 짚는 천장이 없다 — 반경 안에도 허공과 구멍이 있다");
		assertTrue(bytes.contains("pushVelocity"),
				"속도를 공중 감쇠로 잡지 않으면 점프한 사람이 천장이 잡아 둔 목적지를 지나쳐 간다");
	}

	/**
	 * 천장이 실제로 섬 안이다. 미는 함수는 「엔더폭풍」의 것이라 그쪽 증명을 물려받는다.
	 *
	 * <p>여기서는 <b>가장 센 한 번치</b>를 섬 곳곳에서 걸어 보고 목적지가 반경 32 안인지 본다.
	 */
	@Test
	void 가장_센_한_번치도_목적지가_섬_안이다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		double wanted = DragonLastStandPatterns.WING_PUSH_BLOCKS;
		for (int angleStep = 0; angleStep < 72; angleStep++) {
			double angle = (Math.PI * 2.0 * angleStep) / 72.0;
			double outX = Math.cos(angle);
			double outZ = Math.sin(angle);
			for (double from = 0.0; from < limit; from += 0.5) {
				double x = outX * from;
				double z = outZ * from;
				double distance = TrialEnderStorm.pushDistance(x, z, new Vec3(outX, 0.0, outZ),
						wanted);
				double endX = x + outX * distance;
				double endZ = z + outZ * distance;
				assertTrue(Math.sqrt(endX * endX + endZ * endZ) <= limit + 1.0E-6,
						"목적지가 천장 밖이다: " + from + "칸에서 " + distance + "칸 밀렸다");
			}
		}
		assertTrue(limit < TrialRisks.ARENA_RADIUS,
				"천장이 섬 경계와 같으면 밀린 사람이 벼랑 끝에 선다");
	}

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/** 사람이 정한 값 넷 — 5초 예고 · 90도 · 20칸 · 피해 65. */
	@Test
	void 브레스가_사람이_정한_값을_지킨다() {
		assertEquals(100, DragonLastStandPatterns.CONE_WARN_TICKS, "5초 예고다");
		assertEquals(90.0, DragonLastStandPatterns.CONE_DEGREES, 0.0001, "90도다");
		assertEquals(20.0, DragonLastStandPatterns.CONE_RANGE, 0.0001, "사거리 20칸이다");
		assertEquals(65.0F, DragonLastStand.CONE_BREATH_DAMAGE, 0.0001F, "피해 65 다");
		assertEquals(DragonLastStandPatterns.CONE_WARN_TICKS
						+ DragonLastStandPatterns.CONE_AFTERGLOW_TICKS,
				DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				"패턴 길이가 예고 + 불꽃과 갈렸다");
	}

	/**
	 * ⚠ 예고가 <b>5초 이상</b>이다. 「즉사 메커닉 0개」를 조건부로 푼 세 조건 가운데 하나다.
	 *
	 * <p>그 셋 중 하나라도 빠지면 금지로 돌아간다 — 5초 예고 · 예고 중 조준 고정 · 피할 공간.
	 */
	@Test
	void 즉사를_허용하는_조건_셋이_지켜진다() {
		assertTrue(DragonLastStandPatterns.CONE_WARN_TICKS >= 100,
				"예고가 5초 아래면 이 패턴은 그날로 금지다");
		// ② 조준이 예고 중에 바뀌지 않는다 — 방향을 한 번만 고른다.
		assertTrue(classBytes().contains("coneAimedFor"),
				"방향을 판마다 한 번 고르고 붙잡아 두는 칸이 없다 — 매 틱 다시 재면 예고가 뜻이 없다");
		// ③ 피할 공간이 남는다 — 90도는 마지막 지대(반경 12)의 4분의 1이다.
		assertTrue(DragonLastStandPatterns.CONE_DEGREES < 180.0,
				"180도가 넘으면 지대 안에 옆으로 빠질 곳이 없다");
	}

	/**
	 * ⚠⚠ <b>피해원을 {@code explosion(null, null)} 으로 골랐고 그것이 한 대에 전멸이다.</b>
	 *
	 * <p>65 를 {@code lightningBolt()} 로 쏘면 무장 기준 19.66 이라 <b>0.34 가 남아</b> 안 죽는다.
	 * 값을 올려 맞추지 말고 피해원을 고르는 문제이고, 고른 것이 폭발이다.
	 */
	@Test
	void 브레스_피해원이_폭발이고_한_대에_전멸이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("explosion"), "브레스가 폭발 피해원을 쓰지 않는다");
		assertFalse(bytes.contains("dragonBreath"),
				"dragonBreath 는 #bypasses_armor 다 — 방어구가 아예 안 들어 무장 기준의 셈과 성질이 다르다");
		float geared = GearedDamage.afterGear(DragonLastStand.CONE_BREATH_DAMAGE,
				GearedDamage.Source.EXPLOSION);
		assertTrue(geared >= GearedDamage.TEAM_HEALTH,
				"무장 기준 한 대에 전멸이어야 한다 — 실제 " + geared);
		// 여유가 하트 한 칸보다 넉넉하다. 번개 계열은 0.34 차이로 살아남는다.
		assertTrue(geared - GearedDamage.TEAM_HEALTH > 2.0F,
				"여유가 너무 얇다 — 감쇠가 조금만 늘어도 「한 대에 전멸」이 거짓이 된다: " + geared);
	}

	/**
	 * ⚠ {@code magic()} 을 쓰지 않았다.
	 *
	 * <p>죽는 쪽이 둘인데 {@code magic} 은 {@code #bypasses_armor} 라 <b>방어구를 갖춰도
	 * 줄지 않는다.</b> 이 판의 셈은 전부 「다이아 풀셋 + 보호 IV 를 지난 뒤」를 기준으로 서
	 * 있으므로 그 전제와 성질이 다르다 — 「표적」이 그 이유로 스스로 예외를 적어 두었고,
	 * 여기서 둘째 예외를 만들지 않았다.
	 */
	@Test
	void 브레스가_방어를_지나가는_피해원을_쓰지_않는다() {
		// magic() 은 인자가 없어 서술자로 가릴 수 없으므로 이름으로 본다. 이 판에서 방어를
		// 지나가는 피해원을 쓰는 것은 「표적」(TrialDragonFocus) 하나뿐이어야 한다.
		assertFalse(classBytes().contains("magic"),
				"magic 은 방어도가 하나도 안 듣는다 — 무장 전제로 잡은 이 판의 셈과 성질이 다르다");
		assertTrue(GearedDamage.Source.MAGIC.bypassesArmor(),
				"magic 이 방어를 지나가지 않게 되면 위 판단의 근거가 사라진다");
		assertFalse(GearedDamage.Source.EXPLOSION.bypassesArmor(),
				"explosion 이 방어를 지나가게 되면 고른 이유가 사라진다");
		assertTrue(GearedDamage.Source.EXPLOSION.scalesOnHard(),
				"하드 곱이 사라지면 폭발도 한 대에 전멸이 아니다");
	}

	/** 잔류가 없다. 장판을 한 장도 만들지 않는다. */
	@Test
	void 브레스가_잔류를_남기지_않는다() {
		assertFalse(classBytes().contains("AreaEffectCloud"),
				"장판이 곧 잔류다 — 「잔류 없음」이 사람이 정한 것이다");
	}

	/** 정면은 안, 반각 밖은 밖, 사거리 밖은 밖. 꼭대기에 겹쳐 있으면 안이다. */
	@Test
	void 부채꼴_판정이_각도와_거리를_모두_본다() {
		float yaw = 0.0F;
		double range = DragonLastStandPatterns.CONE_RANGE;
		double half = DragonLastStandPatterns.CONE_DEGREES / 2.0;
		// yaw 0 의 앞은 (sin0, -cos0) = (0, -1) 이다. 곧 −z 쪽이다.
		assertTrue(DragonLastStandPatterns.insideCone(0.0, -10.0, yaw, range, half), "정면");
		assertFalse(DragonLastStandPatterns.insideCone(0.0, 10.0, yaw, range, half), "뒤쪽");
		assertFalse(DragonLastStandPatterns.insideCone(0.0, -20.5, yaw, range, half), "사거리 밖");
		assertTrue(DragonLastStandPatterns.insideCone(0.0, -20.0, yaw, range, half), "사거리 경계");
		assertTrue(DragonLastStandPatterns.insideCone(0.0, 0.0, yaw, range, half),
				"드래곤 몸 안에 서 있으면 안이다 — 빼 주면 「머리에 붙으면 안 맞는다」가 된다");
		// 반각 경계. 44.9도는 안, 45.1도는 밖이다.
		for (double degrees : new double[] {0.0, 20.0, 44.9}) {
			double radians = Math.toRadians(degrees);
			assertTrue(DragonLastStandPatterns.insideCone(Math.sin(radians) * 10.0,
							-Math.cos(radians) * 10.0, yaw, range, half),
					degrees + "도가 안이어야 한다");
		}
		for (double degrees : new double[] {45.1, 60.0, 90.0, 179.0}) {
			double radians = Math.toRadians(degrees);
			assertFalse(DragonLastStandPatterns.insideCone(Math.sin(radians) * 10.0,
							-Math.cos(radians) * 10.0, yaw, range, half),
					degrees + "도가 밖이어야 한다");
		}
	}

	/** 부채꼴이 고정한 방향을 따라 돈다. 규약은 {@code (sin(yaw), −cos(yaw))} 이 앞이다. */
	@Test
	void 부채꼴이_고정한_방향을_따라_돈다() {
		double range = DragonLastStandPatterns.CONE_RANGE;
		double half = DragonLastStandPatterns.CONE_DEGREES / 2.0;
		// yaw 90 의 앞은 (sin90, -cos90) = (1, 0) 이다. 곧 +x 쪽이다.
		assertTrue(DragonLastStandPatterns.insideCone(10.0, 0.0, 90.0F, range, half));
		assertFalse(DragonLastStandPatterns.insideCone(-10.0, 0.0, 90.0F, range, half));
		// yaw 180 의 앞은 +z 쪽이다.
		assertTrue(DragonLastStandPatterns.insideCone(0.0, 10.0, 180.0F, range, half));
		assertFalse(DragonLastStandPatterns.insideCone(0.0, -10.0, 180.0F, range, half));
	}

	/** 마지막 지대(반경 12) 안에도 <b>부채꼴 밖</b>이 남는다. 「피할 공간」이 그것이다. */
	@Test
	void 마지막_지대_안에도_피할_곳이_남는다() {
		double half = DragonLastStandPatterns.CONE_DEGREES / 2.0;
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		int safe = 0;
		int total = 0;
		for (int angleStep = 0; angleStep < 360; angleStep++) {
			double radians = Math.toRadians(angleStep);
			double x = Math.cos(radians) * (zone - 0.5);
			double z = Math.sin(radians) * (zone - 0.5);
			total++;
			if (!DragonLastStandPatterns.insideCone(x, z, 0.0F,
					DragonLastStandPatterns.CONE_RANGE, half)) {
				safe++;
			}
		}
		// 90도라 4분의 1만 위험하다. 지대 테두리의 4분의 3이 안전하다.
		assertTrue(safe * 4 >= total * 2,
				"지대 테두리에서 안전한 몫이 절반도 안 된다: " + safe + " / " + total);
	}

	// ------------------------------------------------------------------ ③ 번개 5개

	/** 사람이 정한 값 — 다섯 개 · 반경 2.55(낙뢰의 85%) · 피해 35(낙뢰와 같은 값). */
	@Test
	void 번개가_사람이_정한_값을_지킨다() {
		assertEquals(5, DragonLastStandPatterns.LIGHTNING_COUNT, "다섯이다");
		TrialCatalog.Trial storm = TrialCatalog.byId("sharedfate:lightning_storm");
		assertNotNull(storm);
		TrialCatalog.Risk.DelayedStrike strike =
				(TrialCatalog.Risk.DelayedStrike) storm.risks().getFirst();
		assertEquals(strike.radius() * 0.85, DragonLastStandPatterns.LIGHTNING_RADIUS, 0.0001,
				"「낙뢰」의 3칸보다 15% 작아야 한다 — 한쪽을 고치면 다른 쪽도 함께 볼 것");
		assertEquals(strike.damage(), DragonLastStand.LIGHTNING_DAMAGE, 0.0001F,
				"「낙뢰」와 같은 값이어야 한다");
		assertEquals(TrialCatalog.Risk.Impact.LIGHTNING, strike.impact(),
				"「낙뢰와 같은 방식」이라 연출도 같아야 한다");
	}

	/** 피해원도 「낙뢰」와 같다. 무장 기준 세 대에 전멸에 그대로 얹힌다. */
	@Test
	void 번개_피해원이_낙뢰와_같다() {
		assertTrue(classBytes().contains("lightningBolt"),
				"「낙뢰와 같은 방식」이면 피해원도 같아야 한다");
		float perHit = GearedDamage.afterGear(DragonLastStand.LIGHTNING_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(GearedDamage.wipesInThree(perHit),
				"무장 기준 세 대에 전멸이어야 한다 — 실제 한 대 " + perHit);
	}

	/**
	 * ⚠ 연출용 번개다. {@code setVisualOnly(true)} 를 안 켜면 <b>피해가 조용히 두 배</b>가 된다.
	 *
	 * <p>26.3 {@code LightningBolt} 는 이 깃발 하나로 {@code tick()} 의 피해 구간과
	 * {@code spawnFire} 를 함께 끈다. 로그도 빌드도 알려 주지 않는다.
	 */
	@Test
	void 번개가_연출_전용이다() {
		assertTrue(classBytes().contains("setVisualOnly"),
				"켜지 않으면 우리가 준 35 위에 바닐라 번개 피해가 얹히고 불도 붙는다");
	}

	/**
	 * ⚠⚠ <b>다섯 곳이 서로 겹치지 않는다.</b> 굴림을 바꿔도 그대로다.
	 *
	 * <p>겹치면 한 틱에 두 발이라 무장 기준 13.86 이고 세 발이면 전멸이다. 「겹친다」의 정의는
	 * {@code TrialRisks.spotMinGap} 에서 가져온다 — 그 정의가 바뀌면 여기가 먼저 깨진다.
	 */
	@Test
	void 번개_다섯이_서로_겹치지_않는다() {
		double gap = TrialRisks.spotMinGap(DragonLastStandPatterns.LIGHTNING_RADIUS);
		assertEquals(5.1, gap, 0.0001, "반경 2.55 면 최소 간격 5.1 칸이다");
		for (int roll = 0; roll <= 200; roll++) {
			List<Vec3> offsets = DragonLastStandPatterns.lightningOffsets(roll / 200.0);
			assertEquals(DragonLastStandPatterns.LIGHTNING_COUNT, offsets.size(),
					"다섯이 다 서지 않았다 — 자리를 버리는 갈래가 이 함수에는 없어야 한다");
			for (int one = 0; one < offsets.size(); one++) {
				for (int other = one + 1; other < offsets.size(); other++) {
					double dx = offsets.get(one).x - offsets.get(other).x;
					double dz = offsets.get(one).z - offsets.get(other).z;
					double distance = Math.sqrt(dx * dx + dz * dz);
					assertTrue(distance > gap,
							"굴림 " + roll / 200.0 + " 에서 " + one + "·" + other
									+ " 가 겹친다: " + distance + " ≤ " + gap);
				}
			}
		}
	}

	/**
	 * ⚠ 다섯이 <b>마지막 안전지대(반경 12) 안</b>에 든다.
	 *
	 * <p>보더는 정사각형이라 모서리가 17칸이지만 <b>내접원(12)</b>으로 재야 안전하다. 밖에
	 * 떨어지면 「지대에서 나가야 피할 수 있는 번개」가 되어 두 장치가 서로를 죽인다.
	 */
	@Test
	void 번개_다섯이_반경_12_안에_든다() {
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		double reach = DragonLastStandPatterns.LIGHTNING_RING_RADIUS
				+ DragonLastStandPatterns.LIGHTNING_RADIUS;
		assertTrue(reach <= zone,
				"가장 먼 고리가 지대를 넘는다: " + reach + " > " + zone);
		for (int roll = 0; roll <= 200; roll++) {
			for (Vec3 offset : DragonLastStandPatterns.lightningOffsets(roll / 200.0)) {
				double from = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
				assertTrue(from + DragonLastStandPatterns.LIGHTNING_RADIUS <= zone + 1.0E-9,
						"고리 하나가 지대 밖으로 삐져나온다: " + from);
			}
		}
	}

	/** 「드래곤 주변에 몰아서」다. 아레나 전체에 흩뿌리지 않는다. */
	@Test
	void 번개가_드래곤_주변에_몰린다() {
		assertTrue(DragonLastStandPatterns.LIGHTNING_RING_RADIUS < TrialRisks.ARENA_RADIUS / 2.0,
				"아레나 전체에 흩뿌리면 「드래곤 주변에 몰아서」가 아니다");
		assertFalse(classBytes().contains("reserveSpots"),
				"reserveSpots 는 반경 40 아레나 전체에 흩뿌린다 — 쓸 수 없는 까닭 넷은 lightningFive 에 있다");
		assertFalse(classBytes().contains("arenaOffset"),
				"아레나 전체에서 자리를 굴리면 지대 밖에 번개가 떨어진다");
	}

	/** 가운데 하나가 드래곤 자리다. 머리에 붙어 때리는 사람이 한 걸음 옮겨야 한다. */
	@Test
	void 가운데_한_곳이_드래곤_자리다() {
		List<Vec3> offsets = DragonLastStandPatterns.lightningOffsets(0.37);
		assertEquals(Vec3.ZERO, offsets.getFirst(),
				"가운데가 비면 머리 밑이 완전한 안전지대가 된다");
	}

	/** 예고가 「옆으로 비킬 시간」보다 넉넉하다. */
	@Test
	void 번개_예고가_비킬_시간보다_길다() {
		assertEquals(60, DragonLastStandPatterns.LIGHTNING_WARN_TICKS, "3초다");
		assertTrue(DragonLastStandPatterns.LIGHTNING_WARN_TICKS >= TrialWarning.TICKS_SIDESTEP,
				"예고가 옆으로 비킬 시간(30틱)보다 짧으면 사후 통보다");
		assertEquals(DragonLastStandPatterns.LIGHTNING_WARN_TICKS
						+ DragonLastStandPatterns.LIGHTNING_AFTER_TICKS,
				DragonLastStand.Pattern.LIGHTNING_FIVE.durationTicks());
	}

	// ------------------------------------------------------------------ 예산과 규약

	/**
	 * 한 틱 점 예산을 넘지 않는다.
	 *
	 * <p>세 패턴은 동시에 돌지 않으므로 가장 바쁜 틱이 한 패턴의 가장 바쁜 틱이다. 안전지대는
	 * 월드 보더라 점을 한 개도 쓰지 않는다.
	 */
	@Test
	void 한_틱_점_예산을_넘지_않는다() {
		int worst = DragonLastStandPatterns.worstCasePointsPerTick();
		assertTrue(worst <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"한 틱에 " + worst + "점이라 예산 " + TrialLandingShock.MAX_POINTS_PER_TICK
						+ "을 넘는다 — 개수나 반경을 올린 사람은 여기서 멈출 것");
		// 셋의 몫을 못박아 둔다. 「착지 충격」이 434 를 못박아 둔 것과 같은 자리다 — 개수나
		// 반경을 올리면 이 수가 먼저 바뀌어 사람이 예산을 눈으로 세지 않아도 멈춰 선다.
		assertEquals(136, DragonLastStandPatterns.wingBeatPoints(), "날개 퍼덕이기");
		assertEquals(202, DragonLastStandPatterns.coneWarnPoints(), "부채꼴 예고");
		assertEquals(55, DragonLastStandPatterns.coneSprayPoints(), "부채꼴이 터지는 틱");
		assertEquals(200, DragonLastStandPatterns.lightningPoints(), "번개 5개 예고");
		assertEquals(202, worst, "가장 바쁜 틱은 부채꼴 예고다");
	}

	/** 파티클은 긴 형식이다. 짧은 형식은 32칸에서 잘리고 아레나는 80칸이다. */
	@Test
	void 파티클이_긴_형식이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형식이 아니다 — 짧은 형식은 32칸에서 잘린다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
	}

	/** 소리는 {@code playEach} / {@code soundFor} 다. 팀원 루프의 {@code playSound} 는 겹친다. */
	@Test
	void 소리가_사람마다_한_번이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("playEach"), "굉음이 playEach 를 안 쓰면 사람 수만큼 겹친다");
		assertTrue(bytes.contains("soundFor"), "층 소리가 사람마다 따로 나가지 않는다");
		assertFalse(bytes.contains("playSound"),
				"팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 각자 네 겹으로 듣는다");
	}

	/** 자막을 쓰지 않는다. 신호는 소리와 바닥 표식뿐이다. */
	@Test
	void 자막을_쓰지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("showTitle"));
		assertFalse(bytes.contains("showActionBar"));
		assertFalse(bytes.contains("com/sharedfate/sync/TitleMessenger"));
	}

	/** 받은 {@code now} 를 쓴다. */
	@Test
	void 시간을_스스로_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"받은 now 를 쓸 것 — 얼어붙은 판에서 시계가 멈춘다");
	}

	/** 블록을 한 칸도 건드리지 않는다. 바닥을 지우면 공허 낙사다. */
	@Test
	void 블록을_부수지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("removeBlock"), "바닥을 지우면 공허 낙사다");
		assertFalse(bytes.contains("setBlock"), "블록을 놓지도 않는다");
		assertFalse(bytes.contains("destroyBlock"));
		assertTrue(bytes.contains("Heightmap"), "하이트맵을 읽지 않으면 표식이 바닥에 안 붙는다");
	}

	/** 드래곤을 옮기지 않고 페이즈도 건드리지 않는다. 손잡이는 {@code setYRot} 하나다. */
	@Test
	void 드래곤에게서_읽기만_한다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("setYRot"), "머리 고정이 없다");
		assertFalse(bytes.contains("setPhase"),
				"뼈대가 HOVERING 으로 잠가 두었다 — 진짜 앉은 칸으로 가면 화살이 0 이 된다");
		// snapTo 는 연출용 번개를 세우는 데 쓴다(Vec3 한 개짜리). 드래곤을 옮기는 것은 좌표와
		// 각도를 함께 받는 쪽이라 서술자로 갈린다.
		assertFalse(bytes.contains("snapTo:(DDDFF)V"),
				"드래곤을 옮기면 hold 가 못박아 둔 자리와 싸운다");
		assertFalse(bytes.contains("setDeltaMovement:(DDD)V")
						&& bytes.contains("boss/enderdragon/EnderDragon.setDeltaMovement"),
				"드래곤의 속도를 건드리면 붙박이가 풀린다");
	}

	/** 피해원에 실체를 달지 않는다. 바닐라가 제멋대로 밀어내면 천장이 무너진다. */
	@Test
	void 피해원에_실체를_달지_않는다() {
		// 쓰는 것은 explosion(null, null) 과 lightningBolt() 둘뿐이다. 실체가 붙은 피해원
		// (mobAttack 계열)이나 방어를 지나가는 것(dragonBreath)이 섞이면 여기가 깨진다.
		String bytes = classBytes();
		assertTrue(bytes.contains("explosion"));
		assertTrue(bytes.contains("lightningBolt"));
		assertFalse(bytes.contains("mobAttack"), "실체가 붙은 피해원이다");
		assertFalse(bytes.contains("dragonBreath"));
	}

	// ------------------------------------------------------------------ 배선

	/** {@code DragonLastStand} 가 이 파일로 넘기는가. 빠지면 드래곤이 115초 동안 가만히 있는다. */
	@Test
	void 최후의_저항이_패턴을_넘긴다() {
		String bytes = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandPatterns"),
				"runPattern 이 여기로 넘기지 않는다 — 고르기만 하고 아무것도 안 그린다");
	}

	/** 패턴 셋이 셋이다. 넷째를 더하는 사람은 그리는 것도 함께 붙여야 빌드가 통과한다. */
	@Test
	void 패턴이_셋이다() {
		assertEquals(3, DragonLastStand.Pattern.values().length);
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandPatterns.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandPatternsTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
