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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>패턴 둘</b>과 <b>상시 번개</b>. 월드 없이 답이 정해지는 것만 여기서 굴린다.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 죽어 <b>눈으로 확인할 수
 * 없다.</b> 그래서 기하학(부채꼴 안인가 · 번개가 지대 안인가)과 세기(얼마나 미는가)와
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
	 * ⚠ 사람이 <b>1.5배</b>로 올렸다 — 4 → 6 · 1.5 → 2.25.
	 *
	 * <p>그래도 「강한 넉백」 한 대(8)보다 약하다. 피해가 없는데 5초 동안 여덟 번 미는 패턴이라
	 * 한 번치가 한 대와 같으면 5초 내내 조작이 덮어써져 요구하는 행동을 할 수 없다 — 배율이
	 * 2 를 넘기는 순간 그 근거가 깨진다.
	 */
	@Test
	void 날개_넉백이_1점5배다() {
		assertEquals(1.5, DragonLastStandPatterns.WING_PUSH_SCALE, 1.0E-9, "사람이 정한 배율이다");
		assertEquals(TrialEnderStorm.PUSH_BLOCKS / 2.0 * 1.5,
				DragonLastStandPatterns.WING_PUSH_BLOCKS, 1.0E-9,
				"이 저장소의 「강한 넉백」 단위(8)의 절반에 1.5배다");
		assertEquals(6.0, DragonLastStandPatterns.WING_PUSH_BLOCKS, 1.0E-9, "4 → 6 이다");
		assertEquals(2.25, DragonLastStandPatterns.WING_NEAR_PUSH_BLOCKS, 1.0E-9,
				"1.5 → 2.25 다 — 한쪽만 올리면 「4칸 이내는 약하다」의 정도가 달라진다");
		assertTrue(DragonLastStandPatterns.WING_PUSH_BLOCKS < TrialEnderStorm.PUSH_BLOCKS,
				"한 번치가 「강한 넉백」 한 대와 같아지면 5초 내내 조작이 덮어써진다");
		// 12칸 밖의 잦아드는 구간도 함께 올랐다. 거기를 안 올리면 12.0 과 12.1 이 다시 절벽이 된다.
		assertEquals(DragonLastStandPatterns.WING_PUSH_BLOCKS / 2.0,
				DragonLastStandPatterns.wingPushBlocks(16.0), 1.0E-9,
				"잦아드는 구간이 세기의 비율로 적혀 있지 않다");
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

	/**
	 * 예고 중에 <b>1초마다</b> 충전음이 울리고 음높이가 오른다.
	 *
	 * <p>층 소리는 5초에 세 번뿐이고 그 첫 번째가 드래곤 울음이라 붙박이 드래곤의 울음과 구별되지
	 * 않는다 — 사람이 「전조에 소리를 뭔가 넣엇으면해」라고 한 까닭이 그것이다.
	 */
	@Test
	void 브레스_예고에_충전음이_다섯_번_울린다() {
		assertEquals(20, DragonLastStandPatterns.CONE_CHARGE_TICKS, "1초다");
		int plays = 0;
		float previous = -1.0F;
		for (int step = 0; step < DragonLastStandPatterns.CONE_WARN_TICKS; step++) {
			if (step % DragonLastStandPatterns.CONE_CHARGE_TICKS != 0) {
				continue;
			}
			float pitch = DragonLastStandPatterns.chargePitch(step);
			assertTrue(pitch > previous, step + "틱에서 음높이가 오르지 않았다");
			previous = pitch;
			plays++;
		}
		assertEquals(5, plays, "5초 동안 다섯 번이다");
		assertEquals(0.6F, DragonLastStandPatterns.chargePitch(0), 1.0E-6F, "첫 음이 낮다");
		assertEquals(1.4F, previous, 1.0E-6F,
				"마지막 울림이 가장 높아야 한다 — 예고 길이로 나누면 끝까지 안 올라간다");
	}

	/**
	 * ⚠ 충전음이 <b>이 판에서 쓰는 다른 소리와 파일이 겹치지 않는다.</b>
	 *
	 * <p>바닐라 {@code sounds.json} 을 열어 확인했다 — {@code GHAST_WARN} 은
	 * {@code mob/ghast/charge} 하나이고 이 저장소의 어느 카드도 그 파일을 쓰지 않는다.
	 * ⚠ {@code FIRECHARGE_USE} 는 <b>이름만 다른 같은 소리</b>다({@code mob/ghast/fireball4} 로
	 * 바로 아래 {@code ENDER_DRAGON_SHOOT} · 「기둥 화염구」의 {@code GHAST_SHOOT} 과 같다).
	 */
	@Test
	void 충전음이_이미_쓰는_소리와_겹치지_않는다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("GHAST_WARN"), "충전음이 없다");
		assertFalse(bytes.contains("FIRECHARGE_USE"),
				"mob/ghast/fireball4 라 브레스가 터지는 소리와 글자 하나까지 같다");
		assertFalse(bytes.contains("GHAST_SHOOT"),
				"「기둥 화염구」가 쓰는 소리다 — 같은 판에서 두 카드가 같은 소리를 쓰면 구별되지 않는다");
	}

	/**
	 * 터지는 틱에 불꽃이 나가고 <b>불을 실제로 붙이지 않는다.</b>
	 *
	 * <p>블록을 놓으면 다음 전투의 발판이 달라지고, 사람에게 불을 붙이면 그것이 곧 잔류다.
	 */
	@Test
	void 브레스가_불을_실제로_붙이지_않는다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("FLAME"), "불타는 이펙트가 없다");
		assertFalse(bytes.contains("setRemainingFireTicks"),
				"사람에게 불을 붙이면 피해가 계속 들어와 「잔류 없음」이 그 자리에서 깨진다");
		assertFalse(bytes.contains("igniteForSeconds"));
		assertFalse(bytes.contains("LAVA"),
				"용암 방울은 「고인 것이 남았다」로 읽힌다 — 이 패턴은 고인 것을 남기지 않는다");
		assertTrue(DragonLastStandPatterns.coneFlamePoints() > 0, "불꽃이 한 점도 안 나간다");
	}

	/** 불꽃은 <b>터지는 그 한 틱</b>에만 나간다. 매 틱 뿌리면 그것이 잔류처럼 보인다. */
	@Test
	void 불꽃이_터지는_틱에만_나간다() {
		assertEquals(DragonLastStandPatterns.coneSprayPoints()
						+ DragonLastStandPatterns.coneFlamePoints(),
				DragonLastStandPatterns.coneFirePoints(),
				"터지는 틱은 브레스 파티클과 불꽃이 함께 나간다");
		assertTrue(DragonLastStandPatterns.coneSprayPoints()
						< DragonLastStandPatterns.coneFirePoints(),
				"불꽃이 터지는 틱에만 얹히는 것이 값에 드러나야 한다");
	}

	/** 빨간 투명 면을 세우고 <b>터지는 틱에 지운다.</b> 터진 뒤의 바닥은 안전하다. */
	@Test
	void 빨간_면을_터지는_틱에_지운다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandConePanel"),
				"면을 세우지 않는다 — 사람이 「모든바닥이 위험지대라고 알수잇게」라고 했다");
		assertTrue(bytes.contains("raise"), "세우는 줄이 없다");
		assertTrue(bytes.contains("drop"),
				"지우는 줄이 없다 — 개체라 남으면 다음 판까지 빨간 판이 떠 있다");
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

	// ------------------------------------------------------------------ ③ 상시 번개

	/** 사람이 정한 값 — <b>열 개</b> · 반경 2.55(낙뢰의 85%) · 피해 35(낙뢰와 같은 값). */
	@Test
	void 번개가_사람이_정한_값을_지킨다() {
		assertEquals(10, DragonLastStandPatterns.LIGHTNING_COUNT, "사람이 5 → 10 으로 올렸다");
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
	 * ⚠⚠ <b>겹침을 허용한다 — 그래서 {@code break} 가 유일한 안전장치다.</b>
	 *
	 * <p>사람이 「겹침을 허용함」을 골랐다. 그러면 <b>3겹 자리가 거의 매 볼리에 생기고</b>
	 * (20만 볼리에서 92.43%) 그 자리에 서 있으면 무장 기준 20.79 로 전멸이다. 그것이 실제로
	 * 일어나지 않는 이유는 {@code strikeLightning} 이 <b>첫 발에서 빠져나오기</b> 때문뿐이다.
	 */
	@Test
	void 겹침을_허용하고_한_사람은_한_발만_받는다() {
		assertEquals(5.1, TrialRisks.spotMinGap(DragonLastStandPatterns.LIGHTNING_RADIUS), 0.0001,
				"반경 2.55 면 최소 간격 5.1 칸이다");
		String bytes = classBytes();
		assertFalse(bytes.contains("reserveSpots"),
				"겹침 금지 목록에 들어가면 열 곳이 조용히 몇 곳으로 줄고 「낙뢰」까지 반토막 난다");
		assertFalse(bytes.contains("releaseSpots"),
				"양쪽 다 끈 상태여야 한다 — 한쪽만 되돌리면 겹침은 남고 자리만 줄어든다");
		// break 가 살아 있는지. 세 발 맞으면 전멸인 값이라 이 한 줄이 안전장치 전부다.
		float perHit = GearedDamage.afterGear(DragonLastStand.LIGHTNING_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(perHit * 3.0F >= GearedDamage.TEAM_HEALTH,
				"세 발이 전멸이 아니면 위의 경고를 다시 쓸 것");
		assertTrue(perHit * 2.0F < GearedDamage.TEAM_HEALTH, "두 발로는 안 죽는다");
	}

	/**
	 * ⚠ 열 곳이 전부 <b>마지막 안전지대(반경 12) 안</b>에 든다.
	 *
	 * <p>보더는 정사각형이라 모서리가 17칸이지만 <b>내접원(12)</b>으로 재야 안전하다. 밖에
	 * 떨어지면 「지대에서 나가야 피할 수 있는 번개」가 되어 두 장치가 서로를 죽인다.
	 *
	 * <p>자리 굴리는 반경을 <b>지대 − 반경</b>으로 적어 두었으므로 이것은 구조적으로 참이다.
	 * 시험은 그 식이 지켜지는지와 실제 굴림이 그 안에 드는지를 함께 본다.
	 */
	@Test
	void 번개_열_곳이_반경_12_안에_든다() {
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		assertEquals(zone - DragonLastStandPatterns.LIGHTNING_RADIUS,
				DragonLastStandPatterns.LIGHTNING_FIELD_RADIUS, 1.0E-9,
				"자리 반경을 지대에서 뽑지 않으면 지대를 고칠 때 한쪽만 따라간다");
		assertEquals(9.45, DragonLastStandPatterns.LIGHTNING_FIELD_RADIUS, 1.0E-9);
		for (int one = 0; one <= 40; one++) {
			for (int other = 0; other <= 40; other++) {
				Vec3 offset = DragonLastStandPatterns.lightningOffset(one / 40.0, other / 40.0);
				double from = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
				assertTrue(from + DragonLastStandPatterns.LIGHTNING_RADIUS <= zone + 1.0E-9,
						"고리 하나가 지대 밖으로 삐져나온다: " + from);
			}
		}
	}

	/** 「드래곤 주변에 몰아서」다. 아레나 전체에 흩뿌리지 않는다. */
	@Test
	void 번개가_드래곤_주변에_몰린다() {
		assertTrue(DragonLastStandPatterns.LIGHTNING_FIELD_RADIUS < TrialRisks.ARENA_RADIUS / 2.0,
				"아레나 전체에 흩뿌리면 「드래곤 주변에 몰아서」가 아니다");
		// arenaOffset 은 쓴다 — 분포(원 안에 고르게)가 남의 카드와 같아야 한다. 넘기는 반경만
		// 우리 것이다. 반경 40 을 넘기면 지대 밖에 번개가 떨어진다.
		assertTrue(classBytes().contains("arenaOffset"),
				"분포를 여기서 새로 짜면 남의 카드와 다른 모양이 된다");
		assertFalse(classBytes().contains("ARENA_RADIUS"),
				"아레나 반경을 넘기면 지대 밖에 번개가 떨어진다");
	}

	/**
	 * ⚠ 번개가 <b>패턴이 아니다.</b> 진입 3초 뒤부터 <b>6초마다</b> 저 혼자 돈다.
	 *
	 * <p>주기 120틱은 「낙뢰」와 같은 값이다 — 새 숫자를 만들지 않았고, 개수가 10 이 된 지금
	 * 그 카드와 반경·자리만 다르다.
	 */
	@Test
	void 번개가_6초마다_저_혼자_돈다() {
		assertEquals(120, DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS, "6초다");
		TrialCatalog.Trial storm = TrialCatalog.byId("sharedfate:lightning_storm");
		assertNotNull(storm);
		TrialCatalog.Risk.DelayedStrike strike =
				(TrialCatalog.Risk.DelayedStrike) storm.risks().getFirst();
		assertEquals(strike.interval(), DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS,
				"「낙뢰」의 주기와 갈렸다 — 한쪽을 고치면 다른 쪽도 함께 볼 것");
		assertEquals(strike.count(), DragonLastStandPatterns.LIGHTNING_COUNT,
				"「낙뢰」의 개수와 갈렸다");
		// 「쉬는 틈 없이 계속」이면서 바닥이 깨끗한 틈이 남아야 한다.
		int busy = DragonLastStandPatterns.LIGHTNING_WARN_TICKS
				+ DragonLastStandPatterns.LIGHTNING_AFTER_TICKS;
		assertTrue(busy < DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS,
				"고리가 끊기지 않으면 부채꼴 테두리가 그 위에 묻힌다");
		assertTrue(DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS - busy <= 60,
				"틈이 3초를 넘으면 「계속 터진다」가 아니다: "
						+ (DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS - busy));
	}

	/** 예고가 「옆으로 비킬 시간」보다 넉넉하다. */
	@Test
	void 번개_예고가_비킬_시간보다_길다() {
		assertEquals(60, DragonLastStandPatterns.LIGHTNING_WARN_TICKS, "3초다");
		assertTrue(DragonLastStandPatterns.LIGHTNING_WARN_TICKS >= TrialWarning.TICKS_SIDESTEP,
				"예고가 옆으로 비킬 시간(30틱)보다 짧으면 사후 통보다");
	}

	// ------------------------------------------------------------------ ③ 공허 흡입

	/** 사람이 정한 값 넷. 반경 4 · 예고 3초 · 흡입 5초 · 원 하나다. */
	@Test
	void 흡입은_반경_4에_3초_예고_5초_흡입이다() {
		assertEquals(4.0, DragonLastStandPatterns.SUCK_RADIUS, 1.0E-9, "사람이 정한 값이다");
		assertEquals(60, DragonLastStandPatterns.SUCK_WARN_TICKS, "3초다");
		assertEquals(100, DragonLastStandPatterns.SUCK_PULL_TICKS, "5초다");
		assertTrue(DragonLastStandPatterns.SUCK_WARN_TICKS >= TrialWarning.TICKS_SIDESTEP,
				"예고가 옆으로 비킬 시간(30틱)보다 짧으면 사후 통보다");
	}

	/**
	 * ⚠⚠ <b>달리면 벗어나고, 걷거나 가만있으면 끌려든다.</b>
	 *
	 * <p>사람이 정한 것이 <b>「반대쪽으로 달려서 도망가야지」</b> 하나이고, 이 시험이 그 한 문장을
	 * 값으로 지킨다. 세기를 올려 이 부등호를 뒤집으면 「달리면 벗어난다」가 그 자리에서 거짓이 되고,
	 * <b>그 거짓은 로그에도 빌드에도 안 남는다</b> — 눈으로 볼 수 없는 환경이라 여기서만 잡힌다.
	 */
	@Test
	void 흡입은_달리기보다_약하다() {
		assertTrue(DragonLastStandPatterns.SUCK_SPRINT_RATIO > 0.0
						&& DragonLastStandPatterns.SUCK_SPRINT_RATIO < 1.0,
				"1.0 을 넘기면 어떤 사람도 벗어날 수 없다 — 실제 "
						+ DragonLastStandPatterns.SUCK_SPRINT_RATIO);

		// 바닥에서 한 틱에 실제로 나아가는 거리는 「입력 가속 ÷ (1 − 감쇠)」다.
		double sprint = DragonLastStandPatterns.WALK_INPUT
				* DragonLastStandPatterns.SPRINT_MULTIPLIER
				/ (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		double walk = DragonLastStandPatterns.WALK_INPUT
				/ (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		double pull = DragonLastStandPatterns.SUCK_MAX_INWARD;

		assertTrue(pull < sprint, "달려도 못 벗어난다 — 당김 " + pull + " · 달리기 " + sprint);
		assertTrue(pull > walk, "걸어도 벗어나면 「달려야 한다」가 아니다 — 당김 " + pull
				+ " · 걷기 " + walk);

		// 5초 동안 달려서 버는 거리. 반경 4 를 넘기려면 한 칸이면 되므로 넉넉하다.
		double gained = (sprint - pull) * DragonLastStandPatterns.SUCK_PULL_TICKS;
		assertTrue(gained > DragonLastStandPatterns.SUCK_RADIUS,
				"5초를 달려도 반경 4 를 못 벗어난다 — 실제 " + gained + "칸");
		assertEquals(4.3, gained, 0.3, "값이 크게 달라졌으면 SUCK_SPRINT_RATIO 의 표도 고칠 것");
	}

	/**
	 * ⚠⚠ <b>점프해도 얼음을 깔아도 당김이 세지지 않는다.</b>
	 *
	 * <p>천장이 없으면 공중 감쇠(0.91)에서 종착 속도가 바닥의 <b>8.4배</b>가 되어 점프한 사람이
	 * 중심으로 날아간다. {@code suckStep} 이 결과 속도에 천장을 씌우는 것이 그것을 막는 유일한
	 * 장치다 — 근거는 {@code SUCK_MAX_INWARD} 에 있다.
	 */
	@Test
	void 흡입_세기에_천장이_있다() {
		double cap = DragonLastStandPatterns.SUCK_MAX_INWARD;

		// 이미 천장 속도로 끌려가는 중이면 한 톨도 더하지 않는다.
		assertEquals(0.0, DragonLastStandPatterns.suckStep(-cap), 1.0E-9);
		assertEquals(0.0, DragonLastStandPatterns.suckStep(-cap * 2.0), 1.0E-9,
				"천장보다 빠르면 되레 줄여야 하는 것이 아니라 손을 떼는 것이 맞다");
		// 멈춰 있으면 한 걸음치를 그대로 더한다.
		assertEquals(DragonLastStandPatterns.SUCK_STEP, DragonLastStandPatterns.suckStep(0.0),
				1.0E-9);
		// 바깥으로 달리는 중이면 손을 다 쓴다.
		assertEquals(DragonLastStandPatterns.SUCK_STEP, DragonLastStandPatterns.suckStep(0.3),
				1.0E-9);

		// 어떤 속도에서 시작해도 결과 속도가 천장을 못 넘는다. 공중 감쇠로 100틱 굴려 본다.
		for (double drag : new double[] {DragonLastStandPatterns.GROUND_DRAG, 0.91, 0.98 * 0.91}) {
			double inward = 0.0;
			for (int tick = 0; tick < 200; tick++) {
				inward += DragonLastStandPatterns.suckStep(-inward);
				assertTrue(inward <= cap + 1.0E-9,
						"감쇠 " + drag + " 에서 " + tick + "틱에 " + inward
								+ " 까지 올랐다 — 천장이 새고 있다");
				inward *= drag;
			}
		}
	}

	/** 손이 닿는 거리는 새 숫자가 아니다 — 이 페이즈가 이미 쓰는 20이다. */
	@Test
	void 흡입이_닿는_거리가_새_숫자가_아니다() {
		assertEquals(DragonLastStandPatterns.CONE_RANGE, DragonLastStandPatterns.SUCK_REACH,
				1.0E-9, "부채꼴 사거리와 같은 값이어야 한다");
		assertEquals(DragonLastStandPatterns.WING_FADE_RADIUS, DragonLastStandPatterns.SUCK_REACH,
				1.0E-9, "날개가 잦아드는 거리와도 같은 값이다");
		assertTrue(DragonLastStandPatterns.SUCK_REACH
						> DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1],
				"마지막 지대(반변 12)보다 짧으면 지대 안에 손이 안 닿는 자리가 생긴다");
	}

	// ------------------------------------------------------------------ ④ 십자 균열

	/** 사람이 정한 값 넷. 폭 3 · 세 번 · 3초 예고 · 1.5초 간격이다. */
	@Test
	void 십자는_폭_3에_세_번이다() {
		assertEquals(3.0, DragonLastStandPatterns.CROSS_WIDTH, 1.0E-9, "사람이 정한 값이다");
		assertEquals(3, DragonLastStandPatterns.CROSS_ROUNDS, "사람이 정한 값이다");
		assertEquals(60, DragonLastStandPatterns.CROSS_FIRST_WARN_TICKS, "3초다");
		assertEquals(30, DragonLastStandPatterns.CROSS_GAP_TICKS, "1.5초다");
		assertEquals(TrialWarning.TICKS_SIDESTEP, DragonLastStandPatterns.CROSS_GAP_TICKS,
				"1.5초가 「옆으로 비킬 시간」과 같은 값이다 — 이 패턴이 요구하는 것이 그것이다");
	}

	/**
	 * ⚠⚠ <b>세 각도가 다 다르다.</b> 사람이 그것 때문에 2번을 골랐다.
	 *
	 * <p>십자는 90도 대칭이므로 「대각선으로 두 번」이면 {@code 45 + 45 = 90} 이라 셋째가 첫째와
	 * 같은 모양이 된다. 그러면 <b>세 번이 두 번</b>이다. 90 으로 나눈 나머지가 서로 달라야 한다.
	 */
	@Test
	void 십자_세_각도가_다_다르다() {
		assertEquals(DragonLastStandPatterns.CROSS_ROUNDS,
				DragonLastStandPatterns.CROSS_ANGLES.length,
				"각도 배열과 횟수가 갈라지면 세 번째가 배열 밖을 짚는다");
		assertArrayEquals(new double[] {0.0, 45.0, 22.5}, DragonLastStandPatterns.CROSS_ANGLES,
				1.0E-9, "사람이 고른 셋이다 — + → × → 22.5도");
		Set<Double> shapes = new java.util.HashSet<>();
		for (double angle : DragonLastStandPatterns.CROSS_ANGLES) {
			// 십자는 90도 대칭이라 같은 모양인지는 90 으로 나눈 나머지로 본다.
			double shape = ((angle % 90.0) + 90.0) % 90.0;
			assertTrue(shapes.add(shape),
					angle + "도가 앞의 것과 같은 모양이다 — 세 번이 두 번이 된다");
		}
		assertEquals(3, shapes.size());
	}

	/** 터지는 시각과 예고가 값에서 나온다. 터지는 틱에 다음 예고가 <b>끊기지 않고</b> 시작한다. */
	@Test
	void 십자가_60_90_120틱에_터진다() {
		assertEquals(60, DragonLastStandPatterns.crossFireStep(0));
		assertEquals(90, DragonLastStandPatterns.crossFireStep(1));
		assertEquals(120, DragonLastStandPatterns.crossFireStep(2));
		assertEquals(130, DragonLastStandPatterns.crossDurationTicks());

		// 터지는 틱마다 정확히 하나가 터진다.
		for (int round = 0; round < DragonLastStandPatterns.CROSS_ROUNDS; round++) {
			assertEquals(round, DragonLastStandPatterns.crossFiredRound(
					DragonLastStandPatterns.crossFireStep(round)));
		}
		assertEquals(-1, DragonLastStandPatterns.crossFiredRound(59));
		assertEquals(-1, DragonLastStandPatterns.crossFiredRound(121));

		// 표식이 한 틱도 끊기지 않는다. 0..119 는 늘 누군가를 예고하고 있다.
		for (int step = 0; step < 120; step++) {
			assertTrue(DragonLastStandPatterns.crossPendingRound(step) >= 0,
					step + "틱에 예고 중인 십자가 없다 — 1.5초가 쉬는 시간으로 읽힌다");
		}
		assertEquals(0, DragonLastStandPatterns.crossPendingRound(0));
		assertEquals(1, DragonLastStandPatterns.crossPendingRound(60),
				"첫 십자가 터지는 그 틱에 둘째 예고가 시작해야 한다");
		assertEquals(2, DragonLastStandPatterns.crossPendingRound(90));
		assertEquals(-1, DragonLastStandPatterns.crossPendingRound(120),
				"마지막이 터진 뒤에는 예고할 것이 없다");

		assertEquals(60, DragonLastStandPatterns.crossWarnTicks(0), "첫 예고는 3초다");
		assertEquals(30, DragonLastStandPatterns.crossWarnTicks(1), "그 뒤는 1.5초다");
		assertEquals(30, DragonLastStandPatterns.crossWarnTicks(2));
	}

	/**
	 * 십자 판정이 <b>폭 3칸 선 넷</b> 그대로다.
	 *
	 * <p>각도 셋 모두에서 굴려 본다. 「단순 피하기」가 성립하려면 <b>안전한 자리가 반드시 있어야</b>
	 * 하므로, 사분면 한가운데가 안전한지까지 함께 센다 — 그것이 거짓이면 이 패턴은 피할 수 없다.
	 */
	@Test
	void 십자_판정이_폭_3칸_선_넷이다() {
		double half = DragonLastStandPatterns.CROSS_HALF_WIDTH;
		assertEquals(1.5, half, 1.0E-9, "폭 3칸의 반이다");

		for (double base : DragonLastStandPatterns.CROSS_ANGLES) {
			double radians = Math.toRadians(base);
			double alongX = Math.sin(radians);
			double alongZ = -Math.cos(radians);
			double sideX = Math.cos(radians);
			double sideZ = Math.sin(radians);

			// 중심은 두 선이 겹치는 자리다. 반드시 안이다.
			assertTrue(DragonLastStandPatterns.insideCross(0.0, 0.0, base, half,
					DragonLastStandPatterns.CROSS_REACH), base + "도에서 중심이 밖이다");

			for (double along = 1.0; along <= 30.0; along += 1.0) {
				// 선 위는 안이다.
				assertTrue(DragonLastStandPatterns.insideCross(alongX * along, alongZ * along,
								base, half, DragonLastStandPatterns.CROSS_REACH),
						base + "도 선 위 " + along + "칸이 밖이다");
				// 반폭 안쪽은 안이고 반폭 밖은 밖이다.
				double inX = alongX * along + sideX * (half - 0.01);
				double inZ = alongZ * along + sideZ * (half - 0.01);
				assertTrue(DragonLastStandPatterns.insideCross(inX, inZ, base, half,
								DragonLastStandPatterns.CROSS_REACH),
						base + "도 " + along + "칸에서 반폭 안쪽이 밖으로 읽힌다");
			}

			// 사분면 한가운데(선에서 45도)는 충분히 멀면 안전하다. 「피할 곳이 있다」가 그것이다.
			double safe = Math.toRadians(base + 45.0);
			double safeX = Math.sin(safe);
			double safeZ = -Math.cos(safe);
			for (double away = 4.0; away <= 12.0; away += 0.5) {
				assertFalse(DragonLastStandPatterns.insideCross(safeX * away, safeZ * away,
								base, half, DragonLastStandPatterns.CROSS_REACH),
						base + "도에서 사분면 한가운데 " + away + "칸이 안으로 읽힌다 — "
								+ "그러면 피할 곳이 없다");
			}

			// 닿는 거리 밖은 밖이다.
			assertFalse(DragonLastStandPatterns.insideCross(
					alongX * (DragonLastStandPatterns.CROSS_REACH + 1.0),
					alongZ * (DragonLastStandPatterns.CROSS_REACH + 1.0), base, half,
					DragonLastStandPatterns.CROSS_REACH), base + "도에서 사거리 밖이 안이다");
		}
	}

	/**
	 * 「단순 피하기」라 <b>지대가 좁아져도 피할 곳이 남는다.</b>
	 *
	 * <p>브레스에 축소 잠금이 붙은 까닭은 「피할 곳이 두 번 사라진다」였다. 이 패턴에 그 잠금을
	 * 걸지 않은 근거가 <b>여기서 재어지는 것</b>이다 — 마지막 지대(반변 12)의 내접원 안에서도
	 * 안전한 자리가 남는지 각도 셋으로 훑는다.
	 */
	@Test
	void 십자는_마지막_지대에서도_피할_곳이_있다() {
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		for (double base : DragonLastStandPatterns.CROSS_ANGLES) {
			int safe = 0;
			for (double angle = 0.0; angle < 360.0; angle += 1.0) {
				double radians = Math.toRadians(angle);
				// 지대 내접원의 절반 거리에서 훑는다. 벽에 붙지 않고도 피할 수 있어야 한다.
				double x = Math.sin(radians) * zone * 0.5;
				double z = -Math.cos(radians) * zone * 0.5;
				if (!DragonLastStandPatterns.insideCross(x, z, base,
						DragonLastStandPatterns.CROSS_HALF_WIDTH,
						DragonLastStandPatterns.CROSS_REACH)) {
					safe++;
				}
			}
			assertTrue(safe > 180, base + "도에서 지대 안 안전한 방향이 " + safe
					+ "도뿐이다 — 「단순 피하기」가 성립하지 않으면 축소 잠금을 다시 봐야 한다");
		}
	}

	// ------------------------------------------------------------------ 예산과 규약

	/**
	 * 한 틱 점 예산을 넘지 않는다.
	 *
	 * <p>⚠ <b>더하기다.</b> 패턴 넷은 동시에 돌지 않지만 <b>번개는 언제나 함께 돈다</b> — 번개가
	 * 패턴이었을 때의 「셋 중 가장 바쁜 것」은 이제 틀린 식이다. 안전지대는 월드 보더라, 빨간
	 * 투명 면은 디스플레이 개체라, 반구 블록 파괴는 파티클이 아니라 블록이라 <b>셋 다 점을 한
	 * 개도 쓰지 않는다.</b>
	 */
	@Test
	void 한_틱_점_예산을_넘지_않는다() {
		int worst = DragonLastStandPatterns.worstCasePointsPerTick();
		assertTrue(worst <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"한 틱에 " + worst + "점이라 예산 " + TrialLandingShock.MAX_POINTS_PER_TICK
						+ "을 넘는다 — 개수나 반경을 올린 사람은 여기서 멈출 것");
		// 몫을 못박아 둔다. 「착지 충격」이 434 를 못박아 둔 것과 같은 자리다 — 개수나
		// 반경을 올리면 이 수가 먼저 바뀌어 사람이 예산을 눈으로 세지 않아도 멈춰 선다.
		assertEquals(136, DragonLastStandPatterns.wingBeatPoints(), "날개 퍼덕이기");
		assertEquals(202, DragonLastStandPatterns.coneWarnPoints(), "부채꼴 예고");
		assertEquals(55, DragonLastStandPatterns.coneSprayPoints(), "부채꼴 브레스 파티클");
		assertEquals(68, DragonLastStandPatterns.coneFlamePoints(), "터지는 틱의 불꽃");
		assertEquals(123, DragonLastStandPatterns.coneFirePoints(), "터지는 틱 전체");
		assertEquals(81, DragonLastStandPatterns.suckWarnPoints(),
				"공허 흡입 예고 — 나눠 그린 검은 속 30 + 빨간 경계 고리 51");
		assertEquals(81, DragonLastStandPatterns.suckPoints(), "공허 흡입 전체");
		assertEquals(54, DragonLastStandPatterns.crossWarnPoints(),
				"십자 예고 — 가장자리 넷 324점을 여섯 틱에 나눈 몫");
		assertEquals(82, DragonLastStandPatterns.crossFlashPoints(), "십자가 터지는 틱의 갈라짐");
		assertEquals(136, DragonLastStandPatterns.crossPoints(),
				"십자가 터지는 틱은 갈라짐 + 다음 십자의 예고가 함께 나간다");
		assertEquals(70, DragonLastStandPatterns.lightningPoints(),
				"상시 번개 — 열 곳을 여섯 틱에 나눠 그린 한 틱 몫");
		assertEquals(272, worst,
				"가장 바쁜 틱은 번개 예고 + 부채꼴 예고다 — 새 패턴 둘은 그 아래이므로 "
						+ "이 수가 새 패턴 때문에 오르지는 않았다");
	}

	/**
	 * ⚠ 새 패턴 둘도 <b>나눠 그리지 않으면</b> 최악의 틱을 갈아치운다.
	 *
	 * <p>검은 원의 속은 한 틱에 179점이고 십자 가장자리는 324점이다. 번개(70)와 겹치면 각각
	 * 300점·394점이라 <b>부채꼴 예고(272)를 넘어</b> 이 페이즈의 가장 바쁜 틱이 바뀐다. 나눠
	 * 그리기를 되돌리려는 사람이 여기서 멈춘다.
	 */
	@Test
	void 새_패턴_둘도_나눠_그려야_한다() {
		assertEquals(TrialEndRain.MARK_MAX_STRIDE, DragonLastStandPatterns.SUCK_MARK_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다");
		assertEquals(TrialEndRain.MARK_MAX_STRIDE, DragonLastStandPatterns.CROSS_MARK_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다");

		int suckFill = 0;
		for (double radius = TrialWarning.POINT_GAP; radius < DragonLastStandPatterns.SUCK_RADIUS;
				radius += TrialWarning.POINT_GAP) {
			suckFill += DragonLastStandPatterns.suckFillPoints(radius);
		}
		assertEquals(179, suckFill, "검은 원의 속을 매 틱 다 채우면 179점이다");
		int crossWhole = 4 * DragonLastStandPatterns.crossLinePoints(
				DragonLastStandPatterns.CROSS_MARK_GAP);
		assertEquals(324, crossWhole, "십자 가장자리 넷을 매 틱 다 그리면 324점이다");

		int busiest = DragonLastStandPatterns.lightningPoints()
				+ DragonLastStandPatterns.coneWarnPoints();
		assertTrue(DragonLastStandPatterns.lightningPoints() + suckFill
						+ TrialWarning.ringPoints(DragonLastStandPatterns.SUCK_RADIUS) > busiest,
				"나눠 그릴 이유가 없어졌으면 SUCK_MARK_STRIDE 의 설명도 함께 고칠 것");
		assertTrue(DragonLastStandPatterns.lightningPoints() + crossWhole > busiest,
				"나눠 그릴 이유가 없어졌으면 CROSS_MARK_STRIDE 의 설명도 함께 고칠 것");
	}

	/**
	 * ⚠ 번개 고리를 <b>나눠 그리지 않으면</b> 예산이 그 자리에서 넘친다.
	 *
	 * <p>열 곳을 매 틱 다 그리면 400점이고 부채꼴 예고가 202점이라 602점이다. 나눠 그리기를
	 * 되돌리려는 사람이 여기서 멈춘다.
	 */
	@Test
	void 번개_고리를_나눠_그리지_않으면_예산이_넘친다() {
		assertEquals(TrialEndRain.MARK_MAX_STRIDE, DragonLastStandPatterns.LIGHTNING_MARK_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다");
		int whole = DragonLastStandPatterns.LIGHTNING_COUNT
				* TrialWarning.ringPoints(DragonLastStandPatterns.LIGHTNING_RADIUS);
		assertEquals(400, whole, "열 곳을 매 틱 다 그리면 400점이다");
		assertTrue(whole + DragonLastStandPatterns.coneWarnPoints()
						> TrialLandingShock.MAX_POINTS_PER_TICK,
				"나눠 그릴 이유가 없어졌으면 이 시험과 LIGHTNING_MARK_STRIDE 의 설명을 함께 고칠 것");
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

	/**
	 * ⚠ 뽑히는 패턴이 <b>넷</b>이다. 번개와 반구는 여기 없다.
	 *
	 * <p>사람이 「번개는 패턴이 아니라 기본 이펙트로 계속」이라고 정했고, 반구 블록 파괴도 같은
	 * 자리(상시 규칙)다. 다섯째를 더하는 사람은 그리는 것도 함께 붙여야 {@code run} 의
	 * {@code switch} 에서 빌드가 깨진다.
	 */
	@Test
	void 뽑히는_패턴이_넷이다() {
		assertEquals(4, DragonLastStand.Pattern.values().length);
		for (DragonLastStand.Pattern pattern : DragonLastStand.Pattern.values()) {
			assertFalse(pattern.name().contains("LIGHTNING"),
				"번개가 패턴 풀로 되돌아왔다 — 그러면 「내내 터진다」가 「가끔 뽑힌다」가 된다");
			assertFalse(pattern.name().contains("DOME"),
				"반구 블록 파괴는 상시 규칙이다 — 뽑히면 「계속 파괴하는게」가 거짓이 된다");
		}
	}

	/** 반구 블록 파괴를 <b>매 틱</b> 넘기는 줄이 뼈대에 있다. 빠지면 한 칸도 안 부서진다. */
	@Test
	void 최후의_저항이_반구를_매_틱_넘긴다() {
		String bytes = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandDome"),
				"tick 이 반구 시계를 돌리지 않는다 — 뽑히지 않으므로 이 줄이 유일한 길이다");
	}

	/** 번개를 <b>매 틱</b> 넘기는 줄이 뼈대에 있다. 빠지면 번개가 한 번도 안 떨어진다. */
	@Test
	void 최후의_저항이_번개를_매_틱_넘긴다() {
		assertTrue(read("/com/sharedfate/sync/DragonLastStand.class").contains("tickLightning"),
				"tick 이 번개 시계를 돌리지 않는다 — 뽑히지 않으므로 이 줄이 유일한 길이다");
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
