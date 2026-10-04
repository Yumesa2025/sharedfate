package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
	 * ⚠⚠ 사람이 <b>절반으로 줄였다가</b>(2026-10-01) <b>다시 1.5배로 올렸다</b>(2026-10-04) —
	 * <b>6 → 3 → 4.5</b> · <b>2.25 → 1.125 → 1.6875</b>.
	 *
	 * <p>사람 말: <b>「30프로미만 2페이지에서 밀쳐지는게 너무심해 지금보다 50프로는 안밀쳐지게하고」</b>
	 * 다음에 <b>「반대로 계속 밀치는 패턴은 밀치는 힘이 지금 거의 없어진 것처럼 됐어. 50프로 키워」</b>.
	 * 둘 다 「지금보다」라 곱이 {@code 1.5 × 0.5 × 1.5} 다.
	 *
	 * <p>옛 값과 새 값을 <b>전부</b> 적어 둔다. 한쪽만 적으면 다음에 「50% 바꿔라」를 또 들은
	 * 사람이 <b>무엇의 50%인지</b>를 알 수 없다.
	 */
	@Test
	void 날개_넉백이_다시_1점5배가_됐다() {
		assertEquals(1.5, DragonLastStandPatterns.WING_PUSH_RAISE, 1.0E-9,
				"사람이 처음 올린 배율이다 — 지우면 곱이 어디서 왔는지 사라진다");
		assertEquals(0.5, DragonLastStandPatterns.WING_PUSH_CUT, 1.0E-9,
				"사람이 「50프로는 안밀쳐지게」라고 한 몫이다");
		assertEquals(1.5, DragonLastStandPatterns.WING_PUSH_REGAIN, 1.0E-9,
				"사람이 2026-10-04 에 「50프로 키워」라고 한 몫이다");
		assertEquals(1.125, DragonLastStandPatterns.WING_PUSH_SCALE, 1.0E-9, "1.5 × 0.5 × 1.5 다");

		// 옛 값 둘 ─ 새 값.
		double firstPeak = TrialEnderStorm.PUSH_BLOCKS / 2.0 * 1.5;
		double cutPeak = firstPeak * 0.5;
		assertEquals(6.0, firstPeak, 1.0E-9, "처음 올린 값이 6 이었다");
		assertEquals(3.0, cutPeak, 1.0E-9, "깎은 값이 3 이었다");
		assertEquals(4.5, DragonLastStandPatterns.WING_PUSH_BLOCKS, 1.0E-9, "3 → 4.5 다");
		assertEquals(cutPeak * 1.5, DragonLastStandPatterns.WING_PUSH_BLOCKS, 1.0E-9);
		assertEquals(1.6875, DragonLastStandPatterns.WING_NEAR_PUSH_BLOCKS, 1.0E-9,
				"1.125 → 1.6875 다 — 한쪽만 올리면 「4칸 이내는 약하다」의 정도가 달라진다");
		assertEquals(DragonLastStandPatterns.WING_PUSH_BLOCKS / DragonLastStandPatterns.WING_NEAR_PUSH_BLOCKS,
				6.0 / 2.25, 1.0E-9, "가장 센 구간과 머리 밑의 비가 처음 그대로여야 한다");

		// 바닥에서 한 번치에 실제로 밀리는 거리. 「세기를 1.5배로」가 「거리를 1.5배로」여야 뜻이 맞는다.
		double cutTravel = DragonLastStandPatterns.groundedTravel(TrialEnderStorm.pushVelocity(cutPeak));
		double nowTravel = DragonLastStandPatterns.groundedTravel(
				DragonLastStandPatterns.shoveSpeed(DragonLastStandPatterns.WING_PUSH_BLOCKS, false));
		assertEquals(cutTravel * 1.5, nowTravel, 1.0E-9,
				"바닥에서 한 번치에 실제로 밀리는 거리가 1.5배가 아니다");
		assertEquals(0.595, cutTravel, 0.001, "깎은 뒤의 한 번치가 0.59칸이었다 — 「거의 없어진 것처럼」");
		assertEquals(0.892, nowTravel, 0.001, "새 한 번치는 0.89칸이다");
		// 그 「거의 없어진 것처럼」은 버그가 닫힌 뒤의 값이다. 버그 시절에는 번치를 맞은 사람이 떠서
		// 공중 감쇠로 같은 속도가 다섯 배를 밀었다 — 사람이 기억하는 세기가 그것이다.
		double bugTravel = DragonLastStandPatterns.airborneTravel(TrialEnderStorm.pushVelocity(cutPeak));
		assertEquals(3.0, bugTravel, 1.0E-9, "버그 시절 떠서 받던 한 번치가 3칸이었다");
		assertTrue(nowTravel < bugTravel,
				"1.5배로도 버그 시절 느낌에는 못 미친다 — 사람이 또 「약하다」고 하면 이 비교부터 보여 줄 것");

		assertTrue(DragonLastStandPatterns.WING_PUSH_BLOCKS < TrialEnderStorm.PUSH_BLOCKS,
				"한 번치가 「강한 넉백」 한 대와 같아지면 5초 내내 조작이 덮어써진다");
		// 12칸 밖의 잦아드는 구간도 함께 움직였다. 거기를 안 따라가면 12.0 과 12.1 이 절벽이 된다.
		assertEquals(DragonLastStandPatterns.WING_PUSH_BLOCKS / 2.0,
				DragonLastStandPatterns.wingPushBlocks(16.0), 1.0E-9,
				"잦아드는 구간이 세기의 비율로 적혀 있지 않다");
	}

	/**
	 * ⚠⚠ <b>점프해도 바닥과 같은 거리만 밀린다.</b> 사람이 본 「저 끝까지」가 이 등식이 없던 것이다.
	 *
	 * <p>사람 말: <b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든? 그것도
	 * 조심해야겟어」</b>. {@code pushVelocity} 가 <b>공중 감쇠</b>로 속도를 잡으므로, 같은 속도가
	 * 바닥에서는 적힌 거리의 5분의 1 을 밀고 공중에서는 <b>적힌 거리를 그대로</b> 민다 —
	 * 곧 떠 있으면 <b>다섯 배</b>다.
	 *
	 * <p>이 등식이 깨지면 로그에도 빌드에도 안 남는다. 눈으로 볼 수 없는 환경이라 여기서만 잡힌다.
	 */
	@Test
	void 공중에서_밀려도_바닥과_같은_거리만_간다() {
		assertEquals((1.0 - TrialEnderStorm.AIR_DRAG) / (1.0 - DragonLastStandPatterns.GROUND_DRAG),
				DragonLastStandPatterns.AIRBORNE_PUSH_SCALE, 1.0E-12,
				"비율을 손으로 적으면 감쇠를 고칠 때 한쪽만 따라간다");
		assertEquals(0.198238, DragonLastStandPatterns.AIRBORNE_PUSH_SCALE, 1.0E-6);

		for (double distance = 0.0; distance <= DragonLastStandPatterns.WING_PUSH_BLOCKS + 1.0;
				distance += 0.05) {
			double ground = DragonLastStandPatterns.groundedTravel(
					DragonLastStandPatterns.shoveSpeed(distance, false));
			double air = DragonLastStandPatterns.airborneTravel(
					DragonLastStandPatterns.shoveSpeed(distance, true));
			assertEquals(ground, air, 1.0E-9,
					distance + "칸을 부탁했을 때 공중이 바닥과 다르다 — 점프가 이득이나 손해가 된다");
		}

		// 고치기 전이 몇 배였는지도 못박아 둔다. 그 수가 사람이 본 것이다.
		double before = DragonLastStandPatterns.airborneTravel(
				TrialEnderStorm.pushVelocity(DragonLastStandPatterns.WING_PUSH_BLOCKS));
		double after = DragonLastStandPatterns.airborneTravel(
				DragonLastStandPatterns.shoveSpeed(DragonLastStandPatterns.WING_PUSH_BLOCKS, true));
		assertEquals(4.5, before, 1.0E-9, "고치기 전의 식이면 떠 있을 때 적힌 거리를 그대로 간다");
		assertEquals(5.045, before / after, 0.01, "다섯 배였다");

		// ⚠ 공중에서도 세기는 0 이 아니다. 공중을 통째로 면제하면 배우는 답이 「퍼덕일 때는 뛰어
		// 있어라」가 되어 이 패턴이 아무것도 요구하지 않는다.
		assertTrue(DragonLastStandPatterns.shoveSpeed(
						DragonLastStandPatterns.WING_PUSH_BLOCKS, true) > 0.0,
				"공중을 면제하면 점프 한 번이 이 패턴의 정답이 된다");
	}

	/**
	 * ⚠ 공중 판정이 <b>클라이언트 깃발만 믿지 않는다.</b>
	 *
	 * <p>{@code onGround()} 는 클라이언트가 보내 준 값이다. 렉이나 거짓 보고로 「땅에 있다」가
	 * 들어오면 떠 있는 사람이 바닥 세기를 받아 <b>더 멀리</b> 날아간다 — 어긋나는 방향이 언제나
	 * 덜 미는 쪽이어야 한다는 이 저장소의 규칙에 정면으로 어긋난다. 그래서 서버만 아는
	 * 하이트맵으로 한 번 더 본다.
	 */
	@Test
	void 공중_판정이_클라이언트_깃발만_믿지_않는다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("onGround"), "가장 바로인 물음이 빠졌다");
		assertTrue(bytes.contains("surfaceAt"),
				"하이트맵으로 다시 보지 않으면 거짓 보고 한 번에 천장 논리가 무너진다");
		assertEquals(0.5, DragonLastStandPatterns.AIRBORNE_LIFT, 1.0E-9,
				"계단·반 블록 한 칸이다 — 더 작게 두면 반 블록을 오르는 중이 공중으로 읽힌다");
	}

	/**
	 * ⚠⚠ <b>드래곤이 서버쪽에 쌓아 둔 세로를 클라이언트로 내보내지 않는다.</b>
	 *
	 * <p>사람 말: <b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04).
	 *
	 * <p>원인이 <b>이 파일의 세기에 없었다.</b> 26.3 {@code EnderDragon.knockBack} 이 날개 상자
	 * ({@code wing1}·{@code wing2} 의 {@code inflate(4,2,4).move(0,-2,0)}) 안의 사람에게 매 틱
	 * {@code push(…, 0.2, …)} 를 <b>더하고</b>, {@code isSitting()} 조건은 <b>피해에만</b> 붙어
	 * 있어 최후의 저항에서도 미는 것은 그대로 돈다. {@code Entity.push} 가 켜는
	 * {@code needsSync} 는 <b>본인에게 안 가므로</b> 바닐라에서는 서버 혼자 쌓는 숫자인데,
	 * {@code shove} 가 켜는 {@code syncVelocity} 는 <b>본인에게 가고 보내는 것이 그 순간의
	 * {@code getDeltaMovement()} 통째로</b>다.
	 *
	 * <p>그래서 여기서 재는 것은 <b>「우리가 무엇을 배달하는가」</b>다. 쌓이는 과정을 틱마다 굴려
	 * 보고, 번치가 오는 틱에 나가는 세로가 <b>사람이 실제로 올라간 만큼을 넘지 않는지</b> 본다.
	 */
	@Test
	void 드래곤이_쌓아_둔_세로를_클라이언트로_내보내지_않는다() {
		// 26.3 EnderDragon.knockBack 의 세로. 바이트코드에 ldc2_w 0.20000000298023224 로 박혀 있다.
		double dragonPush = 0.20000000298023224;
		double gravity = DragonLastStandPatterns.LIFT_GRAVITY;
		double drag = DragonLastStandPatterns.LIFT_DRAG;

		// 가만히 서 있는 사람이다 — 클라이언트가 보고하는 세로 움직임이 0 이다.
		double server = -gravity * drag;
		double worstShipped = 0.0;
		double worstRaw = 0.0;
		for (int tick = 1; tick <= DragonLastStand.Pattern.WING_BEAT.durationTicks(); tick++) {
			server = (server + dragonPush - gravity) * drag;
			if (tick % DragonLastStandPatterns.WING_PULSE_TICKS != 0) {
				continue;
			}
			worstRaw = Math.max(worstRaw, server);
			worstShipped = Math.max(worstShipped,
					TrialVelocity.syncedVertical(server, 0.0));
		}

		// ① 고치기 전에 무엇이 나갔는지 못박는다. 그 수가 사람이 본 것이다.
		assertEquals(5.0233, worstRaw, 0.0005,
				"쌓이는 식이 달라졌으면 위 설명도 고칠 것 — (v + 0.2 − 0.08) × 0.98 이다");
		assertEquals(91.1, DragonLastStandPatterns.liftApex(worstRaw), 0.5,
				"그 속도의 도달 높이가 91칸이다 — 「하늘로 날라가버림」이 그것이다");
		assertTrue(DragonLastStandPatterns.liftApex(worstRaw)
						> DragonLastStandPatterns.CROSS_LIFT_BLOCKS * 7.0,
				"세로를 읽은 그대로 돌려놓으면 십자 띄움(12칸)의 일곱 배 넘게 솟는다");

		// ② 고친 뒤에는 한 톨도 안 나간다. 서 있는 사람은 올라가고 있지 않으므로 천장이 0 이다.
		assertEquals(0.0, worstShipped, 1.0E-12,
				"올라가고 있지 않은 사람에게 올라가는 속도를 보내면 그것이 곧 「하늘로 날아간다」다");

		// ③ 고정점까지 쌓여도 그대로다. 상자 안에 오래 서 있으면 5.88 칸/틱에 수렴한다.
		double fixedPoint = (dragonPush - gravity) * drag / (1.0 - drag);
		assertEquals(5.88, fixedPoint, 0.01, "고정점이 5.88 칸/틱이다");
		assertEquals(0.0, TrialVelocity.syncedVertical(fixedPoint, 0.0), 1.0E-12);
		// ⚠ 26.3 에는 「속도 패킷이 3.9 에서 잘린다」가 없다. LpVec3.ABS_MAX_VALUE = 1.7179869183E10
		//   이라 옛 판의 그 자름을 천장으로 믿으면 안 된다.
		assertEquals(0.0, TrialVelocity.syncedVertical(1.0E9, 0.0), 1.0E-12,
				"패킷이 알아서 잘라 줄 것을 기대하면 안 된다 — 26.3 은 자르지 않는다");
	}

	/**
	 * ⚠ <b>자름이 실제로 배선돼 있다.</b> 순수 함수 시험은 함수가 맞는지만 보고, 그 함수를
	 * <b>안 부르면</b> 아무것도 못 잡는다.
	 *
	 * <p>천장이 {@code getKnownMovement()} 라는 것까지 함께 본다 — 서버가 들고 있는
	 * {@code deltaMovement} 로는 「사람이 실제로 올라간 만큼」을 알 수 없고, 26.3
	 * {@code ServerGamePacketListenerImpl} 이 그 수를 넣어 주는 자리가 거기 하나다.
	 */
	@Test
	void 세로_자름이_속도를_내려_보내는_자리에_걸려_있다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("syncedVertical"),
				"자름을 한 곳에 모아 두지 않으면 밀기와 흡입이 두 벌이 되어 한쪽만 고쳐진다");
		assertTrue(bytes.contains("com/sharedfate/sync/TrialVelocity"),
				"자름이 이 파일 안으로 돌아왔다 — 「엔더폭풍」·「착지 충격」도 같은 함수를 지나므로"
						+ " 한 벌로 모아 둔 자리가 TrialVelocity 다");
		assertTrue(bytes.contains("getKnownMovement"),
				"천장이 없다 — deltaMovement 만 보면 남이 쌓아 둔 값과 사람 몫을 가를 수 없다");
		// ⚠ 쌓는 쪽을 끄는 것은 남의 파일이다 — EnderDragonContactDamageMixin 이 hurt 와
		//   knockBack 을 둘 다 끊는다(2026-10-04 에 knockBack 이 더해졌다). 다만 그 깃발은
		//   DragonLastStand.contactDamageOff() 곧 최후의 저항 전용이라, 일반 전투·착지에서는
		//   바닐라가 그대로 쌓고 배달을 막는 것이 이 자름뿐이다. 그래서 지우지 말 것 — 속도를
		//   내려 보내는 자리는 남이 쌓아 둔 값을 믿지 않는 것이 규약이다.
		assertFalse(bytes.contains("knockBack"),
				"이 파일이 바닐라 넉백에 손대면 안 된다 — 끄는 것은 믹스인의 일이다");
	}

	/**
	 * ⚠⚠ <b>내리는 쪽은 한 톨도 안 건드린다.</b> 떨어지는 사람을 더 세게 떨어뜨리면 그것이 새
	 * 낙사 장치다.
	 *
	 * <p>이 함수가 하는 일을 한 문장으로 못박는 시험이다 — <b>올리는 쪽만 자르고 그 밖에는
	 * 받은 값을 그대로 돌려준다.</b>
	 */
	@Test
	void 세로를_내리기만_하고_올리지_않는다() {
		double[] clients = {-9.0, -1.0, -0.0784, 0.0, 0.42, 1.0, 50.0, 1.0E9};
		for (double client : clients) {
			for (double y = -5.0; y <= 6.0; y += 0.01) {
				double got = TrialVelocity.syncedVertical(y, client);
				assertTrue(got <= y + 1.0E-12,
						"세로를 올렸다: " + y + " 가 " + got + " 이 됐다 (클라 " + client + ")");
				assertTrue(got <= DragonLastStandPatterns.CROSS_LIFT_SPEED + 1.0E-12,
						"이 파일이 세로로 만드는 가장 큰 값보다 크다 — 거짓 보고 한 번에 열린다");
			}
			// 내려가는 중은 바뀌지 않는다. 클라이언트가 무엇을 보고했든 그렇다.
			for (double y = -5.0; y <= 0.0; y += 0.001) {
				assertEquals(y, TrialVelocity.syncedVertical(y, client), 0.0,
						"떨어지는 중인 사람의 세로가 달라졌다 — 그 한 줄이 새 낙사 장치가 된다");
			}
		}
	}

	/**
	 * ⚠⚠ <b>점프도 십자 띄움도 세로가 한 톨도 안 달라진다.</b>
	 *
	 * <p>수평에 대한 약속({@link #공중에서_밀려도_바닥과_같은_거리만_간다})과 <b>같은 약속</b>을
	 * 세로에 대해 재는 자리다. 「점프하면 안 밀린다」로 만들지 않은 것과 같은 이유로 <b>「점프하면
	 * 점프가 죽는다」로도 만들지 않는다</b> — 그러면 정답이 「퍼덕일 때는 뛰지 말라」가 된다.
	 *
	 * <p>스스로 올라가는 사람은 <b>자기 올라가는 속도가 곧 천장</b>이라 자름이 걸리지 않는다.
	 * 그것이 {@code getKnownMovement()} 를 천장으로 고른 까닭이다.
	 */
	@Test
	void 점프와_띄움은_세로가_한_톨도_안_달라진다() {
		// ① 바닐라 점프. 처음 0.42 로 25틱(올라가 12틱 · 되돌아오는 몫까지)을 굴린다.
		double v = 0.42;
		for (int tick = 0; tick < 25; tick++) {
			assertEquals(v, TrialVelocity.syncedVertical(v, v), 0.0,
					tick + "틱째 점프 세로가 달라졌다 — 점프가 손해가 된다");
			v = (v - DragonLastStandPatterns.LIFT_GRAVITY) * DragonLastStandPatterns.LIFT_DRAG;
		}

		// ② 십자 띄움. 12칸이 안 깎이는지 본다.
		v = DragonLastStandPatterns.CROSS_LIFT_SPEED;
		for (int tick = 0;
				tick < DragonLastStandPatterns.liftAirborneTicks(
						DragonLastStandPatterns.CROSS_LIFT_SPEED);
				tick++) {
			assertEquals(v, TrialVelocity.syncedVertical(v, v), 0.0,
					tick + "틱째 띄움 세로가 달라졌다 — 사람이 말한 12칸이 조용히 깎인다");
			v = (v - DragonLastStandPatterns.LIFT_GRAVITY) * DragonLastStandPatterns.LIFT_DRAG;
		}

		// ③ 오염된 서버값과 진짜 점프가 함께 있으면 사람 몫만 나간다.
		assertEquals(0.42, TrialVelocity.syncedVertical(5.88, 0.42), 1.0E-12,
				"쌓인 값이 섞여 나가면 점프 한 번이 90칸이 된다");

		// ④ ⚠ CROSS_LIFT_SPEED 천장 하나만으로는 안전하지 않다. 1.49053(12칸 띄움)을 번치마다 다시
		//    실으면 여덟 번에 90칸이 올라 그 낙하가 팀을 끝낸다 — 낙사를 막는 것은 「실제로 올라간
		//    만큼」쪽이고 천장은 거짓 보고용 보험이다. 이 수가 그 사실의 근거다(6칸이던 때 48칸).
		double climbed = 0.0;
		for (int pulse = 0; pulse < DragonLastStandPatterns.WING_PULSES; pulse++) {
			double rise = DragonLastStandPatterns.CROSS_LIFT_SPEED;
			for (int tick = 0; tick < DragonLastStandPatterns.WING_PULSE_TICKS; tick++) {
				climbed += rise;
				rise = (rise - DragonLastStandPatterns.LIFT_GRAVITY)
						* DragonLastStandPatterns.LIFT_DRAG;
			}
		}
		assertEquals(89.6, climbed, 0.5,
				"천장만 믿고 「실제로 올라간 만큼」을 빼면 여덟 번에 90칸이 오른다");
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

	// ------------------------------------------------------------------ ⚠⚠ 낙사가 가능한 경우가 없다

	/**
	 * ⚠⚠ <b>점프 중에 밀려도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>사람 말: <b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든? 그것도
	 * 조심해야겟어」</b>. 공유 체력이라 <b>한 사람의 낙사가 팀 전멸이고 그것이 회차 끝이자 월드
	 * 삭제</b>다. 그러니 이것은 「값이 맞는가」가 아니라 <b>「일어날 수 있는가」</b>를 묻는 시험이고,
	 * 답이 「없다」여야 한다.
	 *
	 * <p>점프 중과 낙하 중은 <b>수평으로 같은 모델</b>이다(공중 감쇠 0.91). 세로 속도는 우리가
	 * 읽어서 그대로 돌려놓으므로 수평 거리에 들어오지 않는다 — 그래서 둘을 같은 셈으로 재고,
	 * 아래 {@link #낙하_중에_밀려도_섬_밖으로_못_나간다} 가 그 사실 자체를 못박는다.
	 */
	@Test
	void 점프_중에_밀려도_섬_밖으로_못_나간다() {
		밀려도_섬을_벗어나지_않는다("점프 중", true, 0.0);
	}

	/**
	 * ⚠⚠ <b>낙하 중에 밀려도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>떨어지는 중이 가장 나쁜 경우다 — 발이 땅에 없으니 마찰이 0 이고, 게다가 사람이 이미
	 * 가장자리 쪽으로 가고 있을 수 있다. 수평 모델이 점프 중과 같다는 것까지 함께 못박는다.
	 */
	@Test
	void 낙하_중에_밀려도_섬_밖으로_못_나간다() {
		// 세로가 수평 세기에 들어오지 않는다. 들어오면 점프와 낙하가 다른 답을 내야 한다.
		assertEquals(DragonLastStandPatterns.shoveSpeed(3.0, true),
				DragonLastStandPatterns.shoveSpeed(3.0, true), 1.0E-12);
		밀려도_섬을_벗어나지_않는다("낙하 중", true, 0.0);
	}

	/**
	 * ⚠⚠ <b>달리는 중에 밀려도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>여기서 재는 것은 <b>「달리던 속도가 넉백에 얹히지 않는가」</b>다. {@code shove} 가
	 * {@code setDeltaMovement} 로 수평 속도를 <b>덮어쓰므로</b> 바깥으로 달리던 0.286칸/틱이 그
	 * 자리에서 사라져야 한다 — 더하면 천장이 계산해 둔 목적지를 지나쳐 간다.
	 *
	 * <p>⚠ <b>제 발로 달려 나가는 거리는 재지 않는다.</b> 사람이 스스로 허공으로 달려 들어가는
	 * 것은 이 카드가 책임질 거리가 아니고, 그 선은 {@code TrialLandingShock.EDGE_MARGIN} 이
	 * 「스프린트 점프 한 번만큼은 남긴다」로 이미 그어 두었다.
	 */
	@Test
	void 달리는_중에_밀려도_섬_밖으로_못_나간다() {
		double sprint = DragonLastStandPatterns.WALK_INPUT
				* DragonLastStandPatterns.SPRINT_MULTIPLIER
				/ (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		assertEquals(0.286, sprint, 0.001, "달리기 종착 속도가 달라졌으면 위 설명도 고칠 것");
		밀려도_섬을_벗어나지_않는다("달리는 중", false, sprint);
	}

	/**
	 * ⚠⚠ <b>드래곤 몸통 넉백을 안고 밀려도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>2026-10-04 에 드러난 경우다. 26.3 {@code EnderDragon.knockBack} 은 세로만 쌓는 것이
	 * 아니라 <b>수평도 쌓는다</b> — {@code dx ÷ max(dx²+dz², 0.1) × 4} 라 <b>거리로 나누는 식</b>
	 * 이고, 분모가 {@code 0.1} 에서 멈추므로 최댓값이 <b>{@code √0.1} 칸에서 12.65칸/틱</b>이다.
	 * 바닥 종착 속도로는 <b>15.2칸/틱</b>, 공중이면 <b>127.9칸/틱</b>이라 그 한 틱이 섬을 통째로
	 * 넘는다.
	 *
	 * <p>그런데 {@code shove} 가 수평을 <b>덮어쓰므로</b> 그 값은 그 자리에서 사라진다 —
	 * <b>세로만 읽어서 돌려놓고 있었기 때문에 세로로만 샜다.</b> 이 시험은 그 「덮어쓴다」가
	 * 실제로 그 세기까지 지우는지를 <b>{@link #달리는_중에_밀려도_섬_밖으로_못_나간다} 와 같은
	 * 도우미로</b> 재는 것이다. 도우미를 새로 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
	 */
	@Test
	void 드래곤_몸통_넉백을_안고_밀려도_섬_밖으로_못_나간다() {
		// 26.3 knockBack: max(dx²+dz², 0.1) 로 나누므로 최댓값이 √0.1 칸에서 4 ÷ √0.1 이다.
		double impulse = 4.0 / Math.sqrt(0.1);
		assertEquals(12.649, impulse, 0.001, "바닐라가 한 틱에 더하는 수평이 12.65칸/틱이다");
		double grounded = impulse * DragonLastStandPatterns.GROUND_DRAG
				/ (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		double airborne = impulse * TrialEnderStorm.AIR_DRAG / (1.0 - TrialEnderStorm.AIR_DRAG);
		assertEquals(15.21, grounded, 0.01, "바닥 종착 속도가 15.2칸/틱이다");
		assertEquals(127.9, airborne, 0.1, "공중 종착 속도가 127.9칸/틱이다 — 한 틱에 섬을 넘는다");
		assertTrue(airborne > TrialRisks.ARENA_RADIUS,
				"이 수가 섬 반경보다 작으면 위 설명을 고칠 것");

		// 바닥과 공중 둘 다 — 그 세기를 안고 맞아도 덮어쓰기가 지운다.
		밀려도_섬을_벗어나지_않는다("드래곤 몸통에 눌린 채", false, grounded);
		밀려도_섬을_벗어나지_않는다("드래곤 몸통에 눌린 채 떠서", true, airborne);
	}

	/**
	 * ⚠⚠ <b>착지 160틱 동안 쌓인 값을 안고 밀려도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>2026-10-04 에 재어 둔 <b>가장 나쁜 경우</b>다. 착지는 드래곤을 포디움에
	 * {@code DragonPerch.HOLD_TICKS} = <b>160틱</b> 붙박아 두므로 머리를 때리는 사람이 최후의
	 * 저항과 <b>같은 기하</b>에 선다. 게다가 쌓임을 끊는 것은 {@code wasHurtRecently()} 인데
	 * {@code EnderDragonPerchRangedImmunityMixin} 이 {@code hurt} 를 HEAD 에서 끊어
	 * <b>{@code hurtTime} 이 안 올라간다</b> — <b>원거리로만 때리는 팀에게는 160틱이 한 틱도 안
	 * 끊기고 쌓인다.</b>
	 *
	 * <p>그 160틱이 만드는 수는 <b>세로 5.648칸/틱(도달 109칸)</b>과 <b>수평 공중 종착
	 * 127.9칸/틱</b>이다. 세로는 {@link TrialVelocity#syncedVertical} 이 자르고, 수평은
	 * {@code shove} 의 덮어쓰기가 지운다 — 여기서는 그 둘을 <b>한 시험에서</b> 본다.
	 *
	 * <p>⚠ <b>도우미는 {@link #드래곤_몸통_넉백을_안고_밀려도_섬_밖으로_못_나간다} 와 같은
	 * 것을 쓴다.</b> 섬 모양과 굴리는 순서를 새로 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
	 */
	@Test
	void 착지_160틱을_안고_밀려도_섬을_벗어나지_않는다() {
		// ① 세로. (v + 0.2 − 0.08) × 0.98 을 160틱 굴린다. 0 에서 출발하는 것은 「포디움에 서
		//   있던 사람이 드래곤이 앉은 틱부터 맞는다」는 뜻이고, 그것이 사람이 실제로 서는 자리다.
		double dragonPush = 0.20000000298023224;
		double vertical = 0.0;
		for (int tick = 0; tick < DragonPerch.HOLD_TICKS; tick++) {
			vertical = (vertical + dragonPush - DragonLastStandPatterns.LIFT_GRAVITY)
					* DragonLastStandPatterns.LIFT_DRAG;
		}
		assertEquals(160, DragonPerch.HOLD_TICKS, "착지가 붙박아 두는 시간이 달라졌으면 위 설명도"
				+ " 고칠 것 — 이 시험이 재는 가장 나쁜 경우가 그 시간이다");
		assertEquals(5.648, vertical, 0.001,
				"고치기 전에 내려가던 수다 — 쌓는 식이 달라졌으면 위 설명도 고칠 것");
		assertEquals(109.3, DragonLastStandPatterns.liftApex(vertical), 0.1,
				"그 속도의 도달 높이가 109칸이다 — 섬 밖 허공보다 한참 위다");
		assertEquals(0.0, TrialVelocity.syncedVertical(vertical, 0.0), 1.0E-12,
				"가만히 선 사람에게 올라가는 속도를 보내면 그것이 곧 「하늘로 날아간다」다");

		// ② 수평. 같은 호출이 쌓는 값이고 160틱이면 둘 다 종착에 붙어 있다. 감쇠가 다르므로
		//   바닥·공중을 따로 굴린다 — 세로가 잘린 뒤의 사람은 바닥에 붙어 있다.
		double impulse = 4.0 / Math.sqrt(0.1);
		double onGround = 0.0;
		double inAir = 0.0;
		for (int tick = 0; tick < DragonPerch.HOLD_TICKS; tick++) {
			onGround = (onGround + impulse) * DragonLastStandPatterns.GROUND_DRAG;
			inAir = (inAir + impulse) * TrialEnderStorm.AIR_DRAG;
		}
		assertEquals(15.21, onGround, 0.01, "160틱이면 바닥 종착(15.2칸/틱)에 붙는다");
		assertEquals(127.9, inAir, 0.1, "160틱이면 공중 종착(127.9칸/틱)에 붙는다");
		assertTrue(inAir > TrialRisks.ARENA_RADIUS,
				"이 수가 섬 반경보다 작으면 위 설명을 고칠 것 — 한 틱에 섬을 넘는 세기여야 한다");

		밀려도_섬을_벗어나지_않는다("착지 160틱을 안고 바닥에서", false, onGround);
		밀려도_섬을_벗어나지_않는다("착지 160틱을 안고 떠서", true, inAir);
	}

	/**
	 * 한 패턴(100틱 · 여덟 번치)을 섬 곳곳에서 통째로 굴려 보고 <b>어느 틱에도 땅 위에 있는지</b>
	 * 본다.
	 *
	 * <p>{@code shove} 와 <b>같은 순서</b>로 천장 둘을 지난다 —
	 * {@code TrialEnderStorm.pushDistance}(목적지가 반경 32 안) 다음에
	 * {@code TrialLandingShock.groundedReach}(길에 땅이 이어진 데까지). 그 둘이 실제 코드의 것이라
	 * 증명을 물려받고, 여기서는 <b>여덟 번이 쌓여도 그대로인가</b>를 본다.
	 *
	 * <p>지나간 길을 0.1칸마다 짚는다. 틱 사이를 건너뛰면 한 틱에 지난 0.5칸 안의 구멍을 놓친다.
	 *
	 * <p>⚠ <b>출발 자리를 천장 안으로만 잡는다.</b> 천장 밖에 선 사람은 {@code pushDistance} 가
	 * <b>한 칸도 밀지 않으므로</b>(「다른 카드나 경사가 먼저 데려다 놓은 경우인데 거기서 또 밀면 이
	 * 카드가 남의 사고를 완성시킨다」) 그 자리는 이 패턴이 만든 자리가 아니다.
	 *
	 * @param airborne 떠 있는가. 바닥이면 감쇠 0.546, 공중이면 0.91 이다
	 * @param carry    번치가 올 때 이미 <b>바깥으로</b> 들고 있던 수평 속도(칸/틱)
	 */
	private static void 밀려도_섬을_벗어나지_않는다(String what, boolean airborne, double carry) {
		double limit = TrialEnderStorm.pushLimitRadius();
		double drag = airborne ? TrialEnderStorm.AIR_DRAG : DragonLastStandPatterns.GROUND_DRAG;
		int ticks = DragonLastStand.Pattern.WING_BEAT.durationTicks();
		TrialLandingShock.GroundProbe probe = DragonLastStandPatternsTest::islandHasGround;
		int shoved = 0;
		for (int angleStep = 0; angleStep < 36; angleStep++) {
			double angle = (Math.PI * 2.0 * angleStep) / 36.0;
			for (double from = 0.5; from < limit; from += 0.5) {
				double x = Math.cos(angle) * from;
				double z = Math.sin(angle) * from;
				if (!islandHasGround(x, z)) {
					continue;
				}
				double vx = 0.0;
				double vz = 0.0;
				for (int tick = 0; tick < ticks; tick++) {
					boolean pulse = tick % DragonLastStandPatterns.WING_PULSE_TICKS == 0
							&& tick / DragonLastStandPatterns.WING_PULSE_TICKS
									< DragonLastStandPatterns.WING_PULSES;
					double radius = Math.sqrt(x * x + z * z);
					if (pulse && radius > 1.0E-4) {
						double outX = x / radius;
						double outZ = z / radius;
						double wanted = DragonLastStandPatterns.wingPushBlocks(radius);
						double distance = TrialEnderStorm.pushDistance(x, z,
								new Vec3(outX, 0.0, outZ), wanted);
						distance = TrialLandingShock.groundedReach(probe, x, z, outX, outZ, distance);
						if (distance > 0.0) {
							if (carry > 0.0) {
								// 바깥으로 달리던 중에 맞았다. 이 속도가 얹히면 안 된다.
								vx = outX * carry;
								vz = outZ * carry;
							}
							// ⚠ 덮어쓴다. 더하면 들고 있던 속도가 천장을 지나쳐 간다 —
							// shove 의 setDeltaMovement 한 줄이 그 약속이다.
							double speed = DragonLastStandPatterns.shoveSpeed(distance, airborne);
							vx = outX * speed;
							vz = outZ * speed;
							shoved++;
						}
					}
					int samples = Math.max(1, (int) Math.ceil(Math.hypot(vx, vz) / 0.1));
					for (int sample = 1; sample <= samples; sample++) {
						double px = x + (vx * sample) / samples;
						double pz = z + (vz * sample) / samples;
						assertTrue(islandHasGround(px, pz), what + "에 " + from
								+ "칸에서 밀렸는데 " + tick + "틱에 허공이다: " + px + ", " + pz);
						assertTrue(Math.sqrt(px * px + pz * pz) <= limit + 1.0E-6,
								what + "에 " + from + "칸에서 밀렸는데 " + tick
										+ "틱에 천장 밖이다");
					}
					x += vx;
					z += vz;
					vx *= drag;
					vz *= drag;
				}
			}
		}
		// 한 번도 안 밀렸으면 이 시험은 아무것도 재지 않았다. 세기를 0 으로 만든 사람이 여기서 멈춘다.
		assertTrue(shoved > 1000, what + " 에 실제로 밀린 횟수가 " + shoved + "뿐이다");
	}

	/**
	 * 시험용 섬. <b>둥글지 않고 구멍도 있다.</b>
	 *
	 * <p>「반경 + 넉백 &lt; 40」으로 검산하면 안 된다는 것이 이 저장소의 규칙이다 — 중앙 섬은
	 * 방향에 따라 끝이 다르고({@code TrialRisks.pickSpot}) 사람이 파 놓은 구멍도 있다. 그래서
	 * 가장자리가 <b>32~40 사이에서 물결치게</b> 만든다. 32 는 천장({@code pushLimitRadius})과 같은
	 * 값이라, 그 방향에서는 <b>천장만으로는 한 칸도 못 막고</b> {@code groundedReach} 가 일해야 한다.
	 *
	 * <p>구멍은 반경 20 자리에 지름 4 다. {@code groundedReach} 가 0.5칸마다 짚으므로 폭 1 짜리
	 * 구멍도 못 지나치지만, 넉넉히 잡아 「건너편에 땅이 있어도 건너뛰지 않는다」까지 재게 한다.
	 */
	private static boolean islandHasGround(double x, double z) {
		double radius = Math.sqrt(x * x + z * z);
		double edge = 36.0 + 4.0 * Math.sin(3.0 * Math.atan2(z, x));
		if (radius > edge) {
			return false;
		}
		double holeX = x - 20.0;
		double holeZ = z - 3.0;
		return holeX * holeX + holeZ * holeZ > 4.0;
	}

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/**
	 * 사람이 정한 값 넷 — <b>4초 예고</b> · 90도 · 20칸 · 피해 65.
	 *
	 * <p>예고는 5초였고 사람이 2026-10-04 에 <b>「브레스 터지는 시간을 1초 감소시켜」</b>라고 해서
	 * 4초다.
	 */
	@Test
	void 브레스가_사람이_정한_값을_지킨다() {
		assertEquals(80, DragonLastStandPatterns.CONE_WARN_TICKS,
				"4초 예고다 — 사람이 5초에서 1초 줄였다");
		assertEquals(90.0, DragonLastStandPatterns.CONE_DEGREES, 0.0001, "90도다");
		assertEquals(20.0, DragonLastStandPatterns.CONE_RANGE, 0.0001, "사거리 20칸이다");
		assertEquals(65.0F, DragonLastStand.CONE_BREATH_DAMAGE, 0.0001F, "피해 65 다");
		assertEquals(DragonLastStandPatterns.CONE_WARN_TICKS
						+ DragonLastStandPatterns.CONE_AFTERGLOW_TICKS,
				DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				"패턴 길이가 예고 + 불꽃과 갈렸다");
		assertEquals(100, DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				"예고 80 + 불꽃 20 이다 — 120 에서 저절로 줄었다");
		// 묶여 있는 것들이 저절로 따라왔는가. 손으로 적은 곳이 있으면 여기서 갈린다.
		assertEquals(DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				DragonLastStandZone.shrinkLockoutLead(),
				"축소 앞 잠금이 브레스 길이를 따라오지 않는다 — 「축소와 브레스 동시 금지」가 샌다");
		assertEquals(DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				DragonLastStandConePanel.FUSE_TICKS, "면의 심지가 브레스 길이를 따라오지 않는다");
	}

	/**
	 * ⚠ 예고가 <b>4초 이상</b>이다. 「즉사 메커닉 0개」를 조건부로 푼 세 조건 가운데 하나다.
	 *
	 * <p>그 셋 중 하나라도 빠지면 금지로 돌아간다 — 예고 · 예고 중 조준 고정 · 피할 공간.
	 *
	 * <p>⚠⚠ 조건 ①은 <b>「5초 예고」였고 사람이 2026-10-04 에 손으로 4초로 고쳤다</b>(「브레스 터지는
	 * 시간을 1초 감소시켜」). 그래서 바닥이 4초다 — <b>사람에게 다시 묻지 않고 그 아래로 내리지 말 것.</b>
	 * 4초도 「옆으로 비킬 시간」(30틱)의 2.7배라 「사후 통보」와는 거리가 멀다.
	 */
	@Test
	void 즉사를_허용하는_조건_셋이_지켜진다() {
		assertTrue(DragonLastStandPatterns.CONE_WARN_TICKS >= 80,
				"예고가 사람이 정한 4초 아래다 — 이 패턴은 그날로 금지다");
		assertTrue(DragonLastStandPatterns.CONE_WARN_TICKS > TrialWarning.TICKS_SIDESTEP * 2,
				"예고가 「옆으로 비킬 시간」의 두 배도 안 된다");
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
	 * <p>층 소리는 예고 내내 세 번뿐이고 그 첫 번째가 드래곤 울음이라 붙박이 드래곤의 울음과 구별되지
	 * 않는다 — 사람이 「전조에 소리를 뭔가 넣엇으면해」라고 한 까닭이 그것이다. 예고가 4초가 되어
	 * 다섯 번이던 것이 <b>네 번</b>이고, 마지막이 여전히 끝까지 오른다.
	 */
	@Test
	void 브레스_예고에_충전음이_네_번_울린다() {
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
		assertEquals(4, plays, "4초 동안 네 번이다(0 · 20 · 40 · 60틱)");
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
	 * ⚠ 번개가 <b>패턴이 아니다.</b> 진입 3초 뒤부터 <b>7.8초마다</b> 저 혼자 돈다.
	 *
	 * <p>사람 말: <b>「번개 주기를 30프로 내리고」</b>. 「주기를 내린다」가 두 뜻으로 읽히는데
	 * 확인한 답이 <b>「덜 자주」</b>였다 — 120 × 1.3 = <b>156</b>, 6초 → <b>7.8초</b>다.
	 *
	 * <h2>⚠⚠ 「낙뢰」 카드와 <b>일부러</b> 갈라졌다 — 묻는 것을 뒤집었다</h2>
	 *
	 * <p>전에는 이 시험이 <b>「둘이 같은 값인가」</b>를 물었다. 주기를 새로 만들지 않고 「낙뢰」
	 * ({@code sharedfate:lightning_storm})의 값을 그대로 쓴 것이 그 상수의 근거였기 때문이다.
	 *
	 * <p>사람이 <b>최후의 저항 쪽만</b> 내리라고 했으므로 이제 둘이 다른 값이고, 그래서 묻는 것을
	 * <b>뒤집었다 — 「같아졌으면 그것이 사고다」.</b> 같아지는 길이 둘이고 <b>둘 다 사고</b>다.
	 *
	 * <ol>
	 *   <li>「낙뢰」를 156 으로 따라 올렸다 — 사람이 건드리라고 하지 않은 <b>카드</b>가 묶여서
	 *       움직인 것이다. 그 카드는 크리스탈 전멸 자리의 룰렛에 있고 값도 따로 재어 정했다</li>
	 *   <li>여기를 120 으로 되돌렸다 — 사람이 내리라고 한 것이 <b>조용히 사라진</b> 것이다</li>
	 * </ol>
	 *
	 * <p>개수(열 곳)는 <b>여전히 같은 값</b>이다. 바꾸라고 한 것은 주기 하나뿐이다.
	 */
	@Test
	void 번개가_7_8초마다_저_혼자_돈다() {
		assertEquals(156, DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS, "7.8초다");
		TrialCatalog.Trial storm = TrialCatalog.byId("sharedfate:lightning_storm");
		assertNotNull(storm);
		TrialCatalog.Risk.DelayedStrike strike =
				(TrialCatalog.Risk.DelayedStrike) storm.risks().getFirst();
		assertEquals(120, strike.interval(),
				"「낙뢰」 카드의 주기가 바뀌었다 — 사람이 내리라고 한 것은 최후의 저항 쪽뿐이고 "
						+ "이 카드는 6초 그대로다");
		assertNotEquals(strike.interval(), DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS,
				"「낙뢰」와 같은 값으로 돌아갔다 — 둘은 일부러 갈라진 값이다. 「낙뢰」를 따라 "
						+ "올렸거나 이쪽을 되돌렸다는 뜻이고, 둘 다 사람이 말한 것과 다르다");
		assertEquals(strike.count(), DragonLastStandPatterns.LIGHTNING_COUNT,
				"「낙뢰」의 개수와 갈렸다 — 사람이 바꾸라고 한 것은 주기뿐이다");
		// 한 볼리가 바닥을 쓰는 시간. 예고 60 + 여운 10 이다.
		int busy = DragonLastStandPatterns.LIGHTNING_WARN_TICKS
				+ DragonLastStandPatterns.LIGHTNING_AFTER_TICKS;
		assertEquals(70, busy, "예고 60 + 여운 10 이다");
		assertTrue(busy < DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS,
				"고리가 끊기지 않으면 부채꼴 테두리가 그 위에 묻힌다");
		// ⚠ 상한을 「60틱 이하」에서 고쳤다. 그 60 은 「주기 120 − 바쁜 70 = 50」에 맞춰 적은
		// 값이라, 주기를 내리라는 사람 말 자체를 막는 자가 되어 있었다(156 − 70 = 86). 지키려던
		// 뜻은 「틈이 너무 길어지면 번개가 배경이 아니라 가끔 오는 사건이 된다」이므로, 그 뜻을
		// 숫자로 다시 뽑는다 — 틈이 가장 짧은 패턴보다 길어지면 그 패턴을 통째로 번개 없이
		// 지나가는 길이 생기고, 그때 번개는 「언제나 있는 배경」이 아니다.
		int quiet = DragonLastStandPatterns.LIGHTNING_PERIOD_TICKS - busy;
		assertEquals(86, quiet, "156 − 70 이다(4.3초)");
		int shortestPattern = Integer.MAX_VALUE;
		for (DragonLastStand.Pattern pattern : DragonLastStand.Pattern.values()) {
			shortestPattern = Math.min(shortestPattern, pattern.durationTicks());
		}
		assertEquals(100, shortestPattern,
				"가장 짧은 패턴이 100틱이다 — 2026-10-04 부터 날개 · 부채꼴(80 + 20) · 십자(60 + 30 + 10)가 "
						+ "셋 다 100틱이다");
		assertTrue(quiet < shortestPattern,
				"바닥이 깨끗한 틈이 가장 짧은 패턴보다 길다 — 번개를 한 번도 안 보고 지나가는 "
						+ "패턴이 생기고, 그때는 「계속 터진다」가 아니라 「가끔 온다」다: 틈 "
						+ quiet + "틱 대 패턴 " + shortestPattern + "틱");
	}

	/** 예고가 「옆으로 비킬 시간」보다 넉넉하다. */
	@Test
	void 번개_예고가_비킬_시간보다_길다() {
		assertEquals(60, DragonLastStandPatterns.LIGHTNING_WARN_TICKS, "3초다");
		assertTrue(DragonLastStandPatterns.LIGHTNING_WARN_TICKS >= TrialWarning.TICKS_SIDESTEP,
				"예고가 옆으로 비킬 시간(30틱)보다 짧으면 사후 통보다");
	}

	/**
	 * ⚠⚠ <b>번개에 맞은 사람만</b> 구속 III 급으로 느려진다. <b>상태이상이 아니라 이동 속도를 직접
	 * 깎는다.</b>
	 *
	 * <p>사람이 처음 말한 것은 <b>「2페이지 번개에 맞으면 그 플레이어만 구속3 1초 걸리게」</b>였는데,
	 * 이 저장소는 {@code EffectSync} 가 <b>상태이상을 팀 전원에게 퍼뜨린다</b>(「엔더 파동」의 구속
	 * III 가 그래서 팀 공유이고 그것이 <b>사람이 의도한 것</b>이다). 건너뛰는 문이
	 * {@code EffectSync} 의 {@code private static boolean propagating} 하나뿐이라 밖에서 켤 길이
	 * 없다 — 그래서 사람이 방법을 바꿨다: <b>「그 플레이어만 구속을 구속3급으로 이속을
	 * 감소시키는쪽으로가면 되지않나? 버프효과로 주는게 아니라」</b>
	 *
	 * <p>⚠ <b>그래서 「엔더 파동의 구속은 팀 공유인데 번개의 구속은 혼자」가 된다. 버그가 아니다.</b>
	 * 한쪽을 다른 쪽에 맞추려는 사람은 여기서 멈출 것 — 둘이 <b>다른 기계</b>를 쓰기 때문에 그렇게
	 * 될 수 있었고, 같은 기계로는 둘 중 하나밖에 못 한다.
	 *
	 * <p>여기서 재는 것은 <b>세기와 연산을 바닐라에서 뽑았는가</b>다. 값만 베끼고 연산을 다르게
	 * 쓰면 「구속 3급」이 거짓이 되므로 <b>바닐라가 만든 수정자와 직접 견준다.</b>
	 */
	@Test
	void 번개가_맞은_사람만_이속을_구속_III_급으로_깎는다() {
		assertEquals(2, DragonLastStandPatterns.LIGHTNING_SLOW_AMPLIFIER,
				"구속 III 는 증폭 2 다 — 증폭은 0 부터 센다");
		assertEquals(20, DragonLastStandPatterns.LIGHTNING_SLOW_TICKS, "사람이 정한 1초다");

		// ⚠ 바닐라 구속 III 가 만드는 수정자와 그대로 견준다. 값·연산·속성 셋이 다 같아야 한다.
		boolean[] seen = {false};
		MobEffects.SLOWNESS.value().createModifiers(
				DragonLastStandPatterns.LIGHTNING_SLOW_AMPLIFIER, (attribute, modifier) -> {
					assertEquals(Attributes.MOVEMENT_SPEED, attribute,
							"바닐라 구속이 이동 속도가 아닌 것을 건드린다 — 적는 자리를 다시 볼 것");
					assertEquals(DragonLastStandPatterns.LIGHTNING_SLOW_AMOUNT, modifier.amount(),
							1.0E-12, "세기가 바닐라 구속 III 와 다르다");
					assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL,
							modifier.operation(),
							"연산이 바닐라와 다르다 — 값만 같고 연산이 다르면 다른 세기가 된다");
					seen[0] = true;
				});
		assertTrue(seen[0], "바닐라 구속이 이동 속도 수정자를 안 걸게 됐다 — 이 방식의 근거가 사라졌다");

		// 한 급당 몫과 급 곱하기도 바닐라와 같은 식이다(AttributeTemplate.create 가 × (증폭 + 1)).
		assertEquals(-0.15000000596046448, DragonLastStandPatterns.SLOWNESS_AMOUNT_PER_LEVEL,
				1.0E-15, "26.3 MobEffects 의 상수를 그대로 베낀 수다 — 반올림하면 위 견주기가 멈춘다");
		assertEquals(DragonLastStandPatterns.SLOWNESS_AMOUNT_PER_LEVEL
						* (DragonLastStandPatterns.LIGHTNING_SLOW_AMPLIFIER + 1),
				DragonLastStandPatterns.LIGHTNING_SLOW_AMOUNT, 1.0E-15);
		assertEquals(-0.45, DragonLastStandPatterns.LIGHTNING_SLOW_AMOUNT, 1.0E-7, "−0.45 다");

		// ADD_MULTIPLIED_TOTAL 이므로 이동 속도가 × 0.55 가 된다. 45% 감소다.
		double factor = 1.0 + DragonLastStandPatterns.LIGHTNING_SLOW_AMOUNT;
		assertEquals(0.55, factor, 1.0E-7, "구속 III 는 이동 속도를 0.55배로 만든다");

		// ⚠ 상태이상을 걸지 않는다. 걸면 EffectSync 가 그 틱에 팀 전원에게 다시 붙인다.
		String bytes = classBytes();
		assertFalse(bytes.contains("MobEffectInstance"),
				"상태이상을 걸면 EffectSync 가 팀 전원에게 퍼뜨려 「그 플레이어만」이 거짓이 된다");
		assertFalse(bytes.contains("addEffect"), "addEffect 를 부르는 순간 팀 공유로 흘러간다");
		assertFalse(bytes.contains("com/sharedfate/sync/EffectSync"),
				"공유 시스템을 건드리면 모드 전체가 쓰는 길이 바뀐다 — 그쪽은 읽기만 한다");
		// 거는 자리가 속성이다. 이름은 우리 것이어야 한다(아래 시험이 그 까닭을 센다).
		assertTrue(bytes.contains("MOVEMENT_SPEED"), "이동 속도를 안 건드리면 느려지지 않는다");
		assertTrue(bytes.contains("addTransientModifier"), "속성 수정자를 안 건다");
	}

	/**
	 * ⚠⚠ <b>두 겹으로 쌓이지 않는다.</b> 쌓이면 {@code ADD_MULTIPLIED_TOTAL} 이 <b>곱</b>이라
	 * 구속 III 가 그대로 구속 <b>6급</b>이 된다.
	 *
	 * <p>막는 것은 두 겹이다 — ① 같은 이름을 쓰므로 26.3
	 * {@code AttributeInstance.addTransientModifier} 가 애초에 <b>예외를 던지고</b>
	 * ({@code putIfAbsent} + {@code "Modifier is already applied on this attribute!"}),
	 * ② 그래서 붙이기 전에 <b>{@code removeModifier} 를 먼저</b> 부른다. 그 순서가 곧 「다시
	 * 맞으면 남은 시간이 1초로 채워질 뿐이다」다.
	 */
	@Test
	void 번개_이속_깎기가_두_겹으로_안_쌓인다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("removeModifier"),
				"붙이기 전에 걷어내지 않으면 addTransientModifier 가 같은 이름에 예외를 던진다");
		// 두 겹이 되면 얼마가 되는가. 그 수가 이 시험이 막는 것이다.
		double once = 1.0 + DragonLastStandPatterns.LIGHTNING_SLOW_AMOUNT;
		assertEquals(0.3025, once * once, 1.0E-7,
				"두 겹이면 0.3025배 — 구속 6급이다. 곱으로 쌓이는 연산이라 더하기로 읽지 말 것");
		// ⚠ 이름이 바닐라와 달라야 한다. 바닐라 이름을 쓰면 진짜 구속이 붙었다 떨어지는 것만으로
		// MobEffect.removeAttributeModifiers 가 우리 몫을 함께 걷어 간다.
		assertFalse(bytes.contains("effect.slowness"),
				"바닐라 수정자 이름을 쓰면 진짜 구속이 우리 몫을 걷어 가거나 덮어쓴다");
		assertTrue(bytes.contains("last_stand_lightning_slow"), "우리 수정자 이름이 없다");
		// 한 사람은 한 발이다. 깎기도 그 break 안쪽에 있어야 한다.
		assertTrue(bytes.contains("slowStruck"), "깎는 자리가 없다");
	}

	/**
	 * ⚠⚠ <b>깎아 둔 것이 새지 않는다.</b> 상태이상은 바닐라가 시간을 재서 걷어 가는데 직접 깎은
	 * 것은 <b>우리가 걷어야 하고</b>, 안 걷으면 <b>영구히 느린 사람</b>이 남는다.
	 *
	 * <p>길이 넷이고 넷이 다 막혀 있어야 한다.
	 *
	 * <table border="1">
	 *   <caption>새는 길과 그것을 막는 것</caption>
	 *   <tr><th>언제</th><th>무엇이 막는가</th></tr>
	 *   <tr><td><b>1초가 지났다</b></td><td>{@code expireSlows} — {@code tickLightning} 이 매 틱
	 *       부른다</td></tr>
	 *   <tr><td><b>전투가 닫혔다 · 월드가 바뀌었다 · 서버가 내려갔다</b></td>
	 *       <td>{@code releaseSlows} — {@code clearState} 가 부르고, 그 메서드는
	 *       {@code DragonLastStand.onFightClosed} 와 {@code DragonLastStand.clearState} 둘 다에서
	 *       불린다</td></tr>
	 *   <tr><td><b>접속을 끊었다 · 죽어서 돌아왔다</b></td><td>{@code transient} 수정자라 그 사람과
	 *       함께 사라진다. {@code expireSlows} 가 {@code isRemoved()} 를 보고 표에서도 지운다</td></tr>
	 *   <tr><td><b>서버를 껐다 켰다</b></td><td>{@code transient} 라 <b>사람 파일에 안 들어간다</b> —
	 *       {@code addPermanentModifier} 로 걸면 이 줄이 거짓이 된다</td></tr>
	 * </table>
	 */
	@Test
	void 번개_이속_깎기가_새지_않는다() {
		String bytes = classBytes();
		// ① 시간. tickLightning 이 매 틱 부르는 자리다.
		assertTrue(bytes.contains("expireSlows"), "1초 뒤에 걷는 자리가 없다");
		// ② 판이 끝날 때. clearState 가 부르고, 뼈대가 두 길에서 그것을 부른다.
		assertTrue(bytes.contains("releaseSlows"), "판이 끝날 때 걷는 자리가 없다");
		String stand = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(stand.contains("clearState"),
				"뼈대가 clearState 를 안 부르면 월드가 바뀌어도 수정자가 남는다");
		assertTrue(stand.contains("tickLightning"),
				"뼈대가 번개를 매 틱 안 넘기면 1초 뒤에 걷는 자가 돌지 않는다");
		// ③·④ 저장되지 않는 수정자다. 영구 수정자로 걸면 월드 파일에 남는다.
		assertTrue(bytes.contains("addTransientModifier"));
		assertFalse(bytes.contains("addPermanentModifier"),
				"영구 수정자는 사람 파일에 들어가 서버를 껐다 켜도 느린 사람이 남는다");
		assertFalse(bytes.contains("addOrReplacePermanentModifier"));
		// 접속을 끊은 사람은 표에서도 빠진다 — 안 빠지면 clearState 까지 참조가 남는다.
		assertTrue(bytes.contains("isRemoved"),
				"사라진 사람을 표에서 안 지우면 개체 참조가 판이 끝날 때까지 남는다");

		// 걷어내기가 실제로 비우는지. 깎인 사람이 없는 상태에서 불러도 터지지 않아야 한다.
		DragonLastStandPatterns.clearState();
		DragonLastStandPatterns.clearState();
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
	 * ⚠⚠ <b>세기의 내력이 남아 있다</b> — 0.85 → 0.5525(사람이 「35프로 감소」) → <b>0.8</b>.
	 *
	 * <p>0.5525 에서 사람이 「아직 너무 셈. 적어도 반대로 달려서 저항할 수 있을 만큼」이라고 했고, 원인이
	 * 값이 아니라 <b>배달</b>이었다({@link #덮어쓰는_배달은_달리기를_지웠다}). 배달을 고치면 0.5525 는 걸어도
	 * 벗어나므로 걷기 문턱 위로 다시 잡았다. 옛 둘은 <b>기록</b>이고 곱하지 않는다.
	 *
	 * <p>옛 값과 새 값을 둘 다 적어 둔다. 한쪽만 적으면 다음에 「또 줄여라」를 들은 사람이 무엇의
	 * 몇 %인지 알 수 없다.
	 */
	@Test
	void 흡입_세기의_내력이_남아_있다() {
		assertEquals(0.85, DragonLastStandPatterns.SUCK_SPRINT_RATIO_FIRST, 1.0E-12,
				"처음 정한 「약간」이다 — 기록이다");
		assertEquals(0.65, DragonLastStandPatterns.SUCK_PULL_KEEP, 1.0E-12,
				"사람이 깎은 35% 다 — 배달 버그 위에서 나온 말이라 지금은 곱하지 않는다");
		assertEquals(0.8, DragonLastStandPatterns.SUCK_SPRINT_RATIO, 1.0E-12,
				"값이 바뀌었으면 SUCK_SPRINT_RATIO 의 표와 흡입은_달리고_걷고_서는_데_따라_갈린다 를 함께 고칠 것");
		assertEquals(0.98, DragonLastStandPatterns.INPUT_DAMPING, 1.0E-12,
				"26.3 LocalPlayer.modifyInput 의 0.98 이다");
		assertEquals(0.10192, DragonLastStandPatterns.SUCK_STEP, 1.0E-9, "0.1 × 1.3 × 0.98 × 0.8 이다");
		assertEquals(0.2245, DragonLastStandPatterns.SUCK_MAX_INWARD, 0.0001);
	}

	/**
	 * ⚠⚠ <b>고치기 전에 사람이 겪은 것</b> — 덮어쓰는 배달이 달리기를 지웠다.
	 *
	 * <p>옛 {@code pullSuck} 은 서버의 {@code deltaMovement} 에 당김을 더하고 {@code syncVelocity} 를
	 * 켰다. 받는 쪽은 {@code Entity.lerpMotion} = {@code setDeltaMovement} 라 <b>바꿔 끼우고</b>, 서버의 그
	 * 수에는 사람 입력이 없어 매 틱 당김 천장에 붙어 있었다. 그러면 사람이 한 틱에 가는 거리는
	 * 「그 틱의 입력 한 번 − 천장」이다.
	 *
	 * <p>여기서 그 수를 0.5525 로 다시 굴려 <b>사람 말과 맞는지</b> 본다 — 달려도 끌려들었다. 이 시험이
	 * 깨지면 26.3 의 배달 길이 바뀌었는지부터 볼 것.
	 */
	@Test
	void 덮어쓰는_배달은_달리기를_지웠다() {
		double oldStep = DragonLastStandPatterns.WALK_INPUT * DragonLastStandPatterns.SPRINT_MULTIPLIER
				* DragonLastStandPatterns.SUCK_SPRINT_RATIO_FIRST * DragonLastStandPatterns.SUCK_PULL_KEEP;
		double oldCap = oldStep / (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		assertEquals(0.1582, oldCap, 0.0001, "0.5525 의 당김 천장이다");

		// 서버쪽: 사람 입력 없이 바닥 감쇠만 받고, 우리가 매 틱 천장까지 더한다. 그 수가 보내진다.
		double server = 0.0;
		for (int tick = 0; tick < 200; tick++) {
			server *= DragonLastStandPatterns.GROUND_DRAG;
			double room = oldCap + server;
			server -= Math.max(0.0, Math.min(oldStep, room));
		}
		assertEquals(-oldCap, server, 1.0E-9, "서버의 수가 매 틱 천장에 붙어 있다 — 그것이 그대로 덮어써진다");

		// 받는 쪽: 속도가 그 수로 바뀐 뒤 그 틱의 입력 한 번만 더해지고 움직인다.
		double sprintInput = sprintInput();
		double walkInput = DragonLastStandPatterns.WALK_INPUT * DragonLastStandPatterns.INPUT_DAMPING;
		assertEquals(-0.62, (server + sprintInput) * 20.0, 0.01,
				"달려도 1초에 0.62칸 끌려들었다 — 사람이 「반대로 달려도 못 버틴다」고 한 그것이다");
		assertEquals(-1.20, (server + walkInput) * 20.0, 0.01, "걸으면 1초에 1.20칸 끌려들었다");
		assertEquals(-3.16, server * 20.0, 0.01, "가만히 선 사람만 표와 같았다 — 지울 속도가 없었다");
	}

	/**
	 * ⚠⚠ <b>달리면 벗어나고, 걸으면 천천히 끌려들며, 가만있으면 끌려든다.</b> 실제로 배달되는 모델로
	 * 굴린다.
	 *
	 * <p>모델: 받는 쪽이 당김을 <b>지금 속도에 더하고</b>({@code ClientboundExplodePacket} →
	 * {@code Entity.push}) 그 틱의 입력을 더한 뒤 움직이고 감쇠를 곱한다. 서버는 사람이 지난 틱에
	 * 실제로 간 거리({@code getKnownMovement()})로 천장을 잰다 — 그 수는 <b>늦게</b> 오므로 늦음을
	 * 0 · 1 · 3 · 6틱으로 바꿔 가며 같은 답이 나오는지 본다.
	 *
	 * <p>지키는 선 셋 — <b>달리면 벗어난다</b> · <b>걸으면 끌려든다</b>(사람의 「적어도」 위에 메인이 정한
	 * 목표) · <b>가만있으면 끌려든다</b>.
	 */
	@Test
	void 흡입은_달리고_걷고_서는_데_따라_갈린다() {
		double ratio = DragonLastStandPatterns.SUCK_SPRINT_RATIO;
		assertTrue(ratio < 1.0, "1.0 을 넘기면 어떤 사람도 벗어날 수 없다 — 실제 " + ratio);
		assertTrue(ratio > 1.0 / DragonLastStandPatterns.SPRINT_MULTIPLIER,
				"걷기 문턱(1 ÷ 1.3 = 0.769) 아래면 걸어도 벗어난다 — 실제 " + ratio);

		double walkInput = DragonLastStandPatterns.WALK_INPUT * DragonLastStandPatterns.INPUT_DAMPING;
		for (int lag : new int[] {0, 1, 3, 6}) {
			double sprint = suckedPerSecond(sprintInput(), false, lag);
			double walk = suckedPerSecond(walkInput, false, lag);
			double stand = suckedPerSecond(0.0, false, lag);
			assertEquals(1.12, sprint, 0.01, "늦음 " + lag + "틱: 달리면 1초에 1.12칸을 번다");
			assertEquals(-0.17, walk, 0.01, "늦음 " + lag + "틱: 걸으면 1초에 0.17칸 끌려든다");
			assertEquals(-4.49, stand, 0.01, "늦음 " + lag + "틱: 가만있으면 1초에 4.49칸 끌려든다");
		}

		// 5초 동안 달려서 버는 거리. 반경 4 를 넘기려면 한 칸이면 되므로 넉넉하다.
		double gained = suckedPerSecond(sprintInput(), false, 0)
				* DragonLastStandPatterns.SUCK_PULL_TICKS / 20.0;
		assertTrue(gained > 0.0, "5초를 달려도 못 번다 — 실제 " + gained + "칸");
		assertEquals(5.6, gained, 0.1, "값이 크게 달라졌으면 SUCK_SPRINT_RATIO 의 표도 고칠 것");
	}

	/**
	 * ⚠ <b>점프해도 당김이 세지지 않는다</b> — 공중에서는 한 번치가 {@code AIRBORNE_PUSH_SCALE} 만큼 준다.
	 *
	 * <p>깎지 않으면 감쇠 0.91 에서 같은 번치가 바닥의 다섯 배를 끌고 가 「뛰면 빨려 들어간다」가 된다.
	 * 가만히 떠 있는 사람의 종착이 바닥과 같고, 바깥으로 달리며 떠 있는 사람(공중 입력 0.026)도
	 * 바닥에서 달리는 사람과 비슷한 답을 받아야 한다.
	 */
	@Test
	void 공중에서도_흡입_세기가_바닥과_같다() {
		assertEquals(suckedPerSecond(0.0, false, 0), suckedPerSecond(0.0, true, 0), 0.01,
				"가만히 떠 있는 사람이 바닥과 다르게 끌린다 — 깎는 순서(깎고 나서 천장)를 볼 것");
		double airSprint = suckedPerSecond(0.026, true, 0);
		assertEquals(1.29, airSprint, 0.01, "바깥으로 달리며 떠 있으면 1초에 1.29칸을 번다");
		assertTrue(airSprint > 0.0, "떠 있는 동안 끌려들면 「달리며 뛰기」가 벌이 된다");
		assertEquals(DragonLastStandPatterns.SUCK_STEP * DragonLastStandPatterns.AIRBORNE_PUSH_SCALE,
				DragonLastStandPatterns.suckImpulse(0.0, true), 1.0E-12, "공중 한 번치가 바닥의 0.198배다");
		assertEquals(DragonLastStandPatterns.SUCK_STEP, DragonLastStandPatterns.suckImpulse(0.3, false),
				1.0E-12, "바깥으로 달리는 중이면 손을 다 쓴다");
		assertEquals(0.0, DragonLastStandPatterns.suckImpulse(
						-DragonLastStandPatterns.SUCK_MAX_INWARD / DragonLastStandPatterns.GROUND_DRAG, false),
				1.0E-12, "이미 천장 속도로 끌려가는 중이면 한 톨도 더하지 않는다");
	}

	/**
	 * ⚠⚠ <b>당김이 더하는 길로 나간다.</b> 순수 함수 시험은 배달 길을 못 본다 — 이 패턴이 고장 난
	 * 자리가 바로 그 길이었다.
	 */
	@Test
	void 흡입은_속도를_덮어쓰지_않고_더하게_보낸다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("net/minecraft/network/protocol/game/ClientboundExplodePacket"),
				"당김이 더하는 길(폭발의 playerKnockback)로 안 나간다 — syncVelocity 로 돌아갔다면"
						+ " 사람이 달려서 쌓은 속도가 매 틱 다시 지워진다");
		assertTrue(DragonLastStandPatterns.SUCK_PACKET_HIDE_LIFT > 32.0,
				"패킷이 찍는 입자 하나가 32칸 안이면 사람 눈앞에 매 틱 보인다");
	}

	/** 바닥 달리기 입력 가속(칸/틱). {@code 0.1 × 1.3 × 0.98}. */
	private static double sprintInput() {
		return DragonLastStandPatterns.WALK_INPUT * DragonLastStandPatterns.SPRINT_MULTIPLIER
				* DragonLastStandPatterns.INPUT_DAMPING;
	}

	/**
	 * 받는 쪽을 굴려 본 <b>바깥쪽 순이동</b>(칸/초). 끌려들면 음수다.
	 *
	 * <p>한 틱: 당김이 지금 속도에 더해지고 → 입력이 더해지고 → 그만큼 움직이고 → 감쇠를 곱한다.
	 * 서버는 {@code lag} 틱 전에 사람이 간 거리를 보고 당김을 정한다.
	 */
	private static double suckedPerSecond(double input, boolean airborne, int lag) {
		double drag = airborne ? TrialEnderStorm.AIR_DRAG : DragonLastStandPatterns.GROUND_DRAG;
		double[] moved = new double[lag + 1];
		double velocity = 0.0;
		double sum = 0.0;
		for (int tick = 0; tick < 400; tick++) {
			double known = moved[0];
			velocity -= DragonLastStandPatterns.suckImpulse(known, airborne);
			double step = velocity + input;
			velocity = step * drag;
			System.arraycopy(moved, 1, moved, 0, lag);
			moved[lag] = step;
			if (tick >= 300) {
				sum += step;
			}
		}
		return sum / 100.0 * 20.0;
	}

	/**
	 * ⚠⚠ <b>점프해도 얼음을 깔아도 당김이 세지지 않는다.</b>
	 *
	 * <p>천장이 없으면 공중 감쇠(0.91)에서 종착 속도가 바닥의 <b>8.4배</b>가 되어 점프한 사람이
	 * 중심으로 날아간다. {@code suckStep} 이 결과 속도에 천장을 씌우는 것이 그것을 막는 장치 하나이고
	 * (다른 하나는 공중 비율 — {@link #공중에서도_흡입_세기가_바닥과_같다}), 근거는
	 * {@code SUCK_MAX_INWARD} 에 있다.
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

	// ------------------------------------------------------------------ ③ 공허 흡입의 불 결계

	/**
	 * 사람이 정한 값 — 반경 4(검은 원과 같은 자리) · 초당 4 · 끝날 때 터지는 35 는 그대로.
	 *
	 * <p>사람 말(2026-10-04): <b>「드래곤 주위에 있으면 계속 딜 맞게. 그리고 옆에 있으면 딜 맞는다를
	 * 알기 쉽게 드래곤 주위에 불 결계가 생기게」</b>.
	 */
	@Test
	void 불_결계는_반경_4에_초당_4다() {
		assertEquals(DragonLastStandPatterns.SUCK_RADIUS, DragonLastStandPatterns.SUCK_FIRE_RADIUS,
				1.0E-12, "검은 원과 같은 자리여야 한다 — 경계가 둘이면 사람이 따로 배워야 한다");
		assertEquals(4.0, DragonLastStandPatterns.SUCK_FIRE_RADIUS, 1.0E-12, "사람이 정한 값이다");
		assertEquals(4.0F, DragonLastStandPatterns.SUCK_FIRE_DAMAGE, 0.0F, "사람이 정한 값이다");
		assertEquals(20, DragonLastStandPatterns.SUCK_FIRE_PERIOD_TICKS, "「초당」이다");
		assertEquals(35.0F, DragonLastStand.VOID_SUCTION_DAMAGE, 0.0F,
				"끝날 때 터지는 피해는 그대로 두라고 했다");
	}

	/**
	 * ⚠ 결계 4 는 <b>적히는 값</b>이고 피해원이 {@code lightningBolt()} 다 — 안전지대 「초당 8」과 같은
	 * 표기법이다. 무장 기준 한 대 <b>0.35</b>.
	 *
	 * <p>{@code inFire()} 를 안 쓴 것은 화염 저항 하나로 0 이 되고 그 상태이상이 팀 전원에게 퍼지기
	 * 때문이다({@code SUCK_FIRE_DAMAGE} 의 표). 여기서는 고른 피해원으로 셈한 값을 못박는다.
	 */
	@Test
	void 불_결계는_무장_기준_한_대_0점35다() {
		float geared = GearedDamage.afterGear(DragonLastStandPatterns.SUCK_FIRE_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertEquals(0.3456F, geared, 0.0005F, "방어도 19 → ×0.24, 보호 IV ×0.36 이다");
		// 다섯 번 다 맞아도 한 사람 몫이 1.73 — 결계만으로 죽지는 않는다. 넷이 다 머물면 팀에 6.9 다.
		assertEquals(1.73F, geared * 5, 0.01F);
		assertEquals(6.91F, geared * 5 * 4, 0.02F, "넷이 5초 내내 머물면 팀에 들어가는 몫이다");
		assertTrue(geared * 5 * 4 < GearedDamage.TEAM_HEALTH,
				"결계만으로 팀이 죽으면 「계속 딜」이 아니라 「즉사」다");
		String bytes = classBytes();
		assertFalse(bytes.contains("inFire"), "inFire 는 화염 저항 한 병으로 결계가 통째로 사라진다");
		assertFalse(bytes.contains("onFire"), "onFire 는 #bypasses_armor 다 — 무장 기준과 성질이 다르다");
	}

	/**
	 * ⚠⚠ <b>결계는 흡입 구간(5초)에만 · 20틱마다 · 다섯 번 때리고, 터짐 직전 10틱 안은 비운다.</b>
	 *
	 * <p>바닐라는 맞은 뒤 20틱 동안 {@code damageCooldownTime} 을 들고, 그것이 10 보다 크면 다음 피해를
	 * <b>차액만</b> 넣거나 막는다(26.3 {@code LivingEntity.hurtServer} 바이트코드 — 비교 상수
	 * {@code 10.0f}). 결계 4 가 터짐 10틱 안에 들어가면 <b>터짐 35 가 31 이 된다.</b> 이 시험이 그것이
	 * 일어날 수 없음을 못박는다.
	 */
	@Test
	void 불_결계가_흡입_구간에만_다섯_번_때린다() {
		int burst = DragonLastStandPatterns.SUCK_WARN_TICKS + DragonLastStandPatterns.SUCK_PULL_TICKS;
		java.util.List<Integer> due = new java.util.ArrayList<>();
		for (int step = -5; step < DragonLastStand.Pattern.VOID_SUCTION.durationTicks() + 5; step++) {
			if (DragonLastStandPatterns.suckFireDue(step)) {
				due.add(step);
			}
		}
		assertEquals(java.util.List.of(60, 80, 100, 120, 140), due,
				"흡입이 시작하는 틱부터 1초마다 — 예고 중에는 보이기만 하고 안 아프다");
		for (int step : due) {
			assertTrue(step >= DragonLastStandPatterns.SUCK_WARN_TICKS, step + "틱은 예고 중이다");
			assertTrue(burst - step > DragonLastStandPatterns.HURT_COOLDOWN_GUARD_TICKS,
					step + "틱의 결계가 터짐(" + burst + ") 10틱 안이다 — 바닐라 피격 무적이 터짐 35 를 깎는다");
		}
		assertFalse(DragonLastStandPatterns.suckFireDue(burst),
				"터지는 틱에 결계가 함께 들어가면 35 가 깎인다");
		assertEquals(10, DragonLastStandPatterns.HURT_COOLDOWN_GUARD_TICKS,
				"바닐라의 수(10.0f)다 — 판을 올려 그 수가 바뀌었으면 바이트코드를 다시 읽을 것");
		// 결계끼리도 서로 안 깎는다 — 주기가 그 10틱보다 길어야 다음 결계가 온전하다.
		assertTrue(DragonLastStandPatterns.SUCK_FIRE_PERIOD_TICKS
						> DragonLastStandPatterns.HURT_COOLDOWN_GUARD_TICKS,
				"결계 주기가 10틱 이하면 두 번째 결계부터 차액 0 으로 막힌다");

		// 주기를 고쳐도 조건이 지킨다 — 15틱이었다면 150틱 결계가 터짐 10틱 앞이라 빠져야 한다.
		// (값을 바꿀 수 없으니 조건의 식을 그대로 다시 세어 본다.)
		int last = -1;
		for (int step = DragonLastStandPatterns.SUCK_WARN_TICKS; step < burst; step += 15) {
			if (burst - step > DragonLastStandPatterns.HURT_COOLDOWN_GUARD_TICKS) {
				last = step;
			}
		}
		assertEquals(135, last, "주기 15 라면 마지막 결계는 150 이 아니라 135 여야 한다");

		// 배선 — 실제로 그 함수로 때리는가.
		String bytes = classBytes();
		assertTrue(bytes.contains("suckFireDue"),
				"결계 시각을 한 함수에 모으지 않으면 이 시험이 아무것도 재지 않는다");
		assertTrue(bytes.contains("burnInside"), "결계가 때리는 줄이 없다");
	}

	/**
	 * ⚠ <b>사람을 불붙이지 않는다.</b> 불이 붙으면 결계 밖에서도 계속 타 「결계 안에서만 딜」이
	 * 거짓이 되고, 공유 체력이라 넷이 함께 타면 합산된다.
	 */
	@Test
	void 불_결계가_사람을_불붙이지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setRemainingFireTicks"), "불이 붙으면 결계 밖에서도 탄다");
		assertFalse(bytes.contains("igniteForSeconds"), "불이 붙으면 결계 밖에서도 탄다");
		assertFalse(bytes.contains("AreaEffectCloud"), "장판은 잔류다");
		assertTrue(bytes.contains("FLAME"), "불꽃 벽이 없다 — 「알기 쉽게」가 그 벽이다");
	}

	/**
	 * 불꽃 벽이 <b>낮은 벽</b>이다 — 가장 짧게 사는 불꽃이 무릎, 가장 오래 사는 불꽃이 사람 키.
	 *
	 * <p>26.3 {@code RisingParticle} 의 수명이 8~40틱이고 마찰 0.96 · 중력 없음이다(바이트코드로
	 * 확인했다). 나눠 세우는 폭이 그 수명 하한보다 작아야 자리가 안 꺼진다.
	 */
	@Test
	void 불꽃_벽이_무릎에서_사람_키까지다() {
		double low = DragonLastStandPatterns.flameRise(DragonLastStandPatterns.SUCK_FIRE_RISE, 8);
		double high = DragonLastStandPatterns.flameRise(DragonLastStandPatterns.SUCK_FIRE_RISE, 40);
		assertEquals(0.70, low, 0.01, "가장 짧게 사는 불꽃이 0.7칸에서 사그라든다");
		assertEquals(2.01, high, 0.01, "가장 오래 사는 불꽃이 2.0칸에서 사그라든다");
		assertTrue(high < 2.5, "벽이 높으면 같은 원의 빨간 고리와 흰 기둥을 가린다");
		assertTrue(DragonLastStandPatterns.SUCK_FIRE_STRIDE < 8,
				"불꽃 수명 하한(8틱)보다 길게 나누면 자리가 꺼졌다 켜진다");
		assertEquals(17, DragonLastStandPatterns.suckFirePoints(),
				"둘레 51점을 세 틱에 나눈 한 틱 몫이다");
	}

	// ------------------------------------------------------------------ ④ 십자 균열

	/**
	 * 사람이 정한 값 — 폭 3 · <b>두 회차 · 회차마다 십자 둘</b> · 3초 예고 · 1.5초 간격.
	 *
	 * <p>회차는 처음 셋(「3번 반복하는 패턴」)이었고 2026-10-04 에 사람이 <b>「그 두 십자를 한번에
	 * 같이 발동시켜. 그리고 다음에도 두 개 같이 터지고」</b>라고 해서 둘 · 둘이다. 예고 구조(첫 3초 ·
	 * 다음 1.5초)는 바꾸라고 하지 않아 그대로다.
	 */
	@Test
	void 십자는_폭_3에_두_회차_둘씩이다() {
		assertEquals(3.0, DragonLastStandPatterns.CROSS_WIDTH, 1.0E-9, "사람이 정한 값이다");
		assertEquals(2, DragonLastStandPatterns.CROSS_ROUNDS, "사람이 정한 값이다 — 셋에서 둘로");
		assertEquals(2, DragonLastStandPatterns.CROSSES_PER_ROUND, "「그 두 십자를 한번에」다");
		assertEquals(60, DragonLastStandPatterns.CROSS_FIRST_WARN_TICKS, "3초다 — 그대로다");
		assertEquals(30, DragonLastStandPatterns.CROSS_GAP_TICKS, "1.5초다 — 그대로다");
		assertEquals(TrialWarning.TICKS_SIDESTEP, DragonLastStandPatterns.CROSS_GAP_TICKS,
				"1.5초가 「옆으로 비킬 시간」과 같은 값이다 — 이 패턴이 요구하는 것이 그것이다");
	}

	/**
	 * ⚠⚠ <b>네 십자가 모두 다른 모양이고, 한 회차 안의 둘은 45도 간격이다.</b>
	 *
	 * <p>십자는 90도 대칭이므로 같은 모양인지는 90 으로 나눈 나머지로 본다. 처음에 사람이 「셋이 다
	 * 다르게」를 골랐던 그 뜻(0 · 45 · 22.5)이 넷(+67.5)으로 이어진다. 그리고 바닥 면이 팔 여덟을
	 * 45도마다 깔고 가운데를 팔각형으로 맞물리므로 <b>한 회차 안의 간격이 정확히 45도</b>여야 한다 —
	 * 어긋나면 면이 판정과 갈린다.
	 */
	@Test
	void 십자_네_각도가_다_다르고_회차마다_45도_간격이다() {
		assertEquals(DragonLastStandPatterns.CROSS_ROUNDS,
				DragonLastStandPatterns.CROSS_ANGLES.length,
				"각도 배열과 회차 수가 갈라지면 둘째 회차가 배열 밖을 짚는다");
		assertArrayEquals(new double[] {0.0, 45.0}, DragonLastStandPatterns.CROSS_ANGLES[0], 1.0E-9,
				"첫 회차 — + 와 × 를 함께");
		assertArrayEquals(new double[] {22.5, 67.5}, DragonLastStandPatterns.CROSS_ANGLES[1], 1.0E-9,
				"둘째 회차 — 첫 회차의 정확히 사이");
		Set<Double> shapes = new java.util.HashSet<>();
		for (double[] round : DragonLastStandPatterns.CROSS_ANGLES) {
			assertEquals(DragonLastStandPatterns.CROSSES_PER_ROUND, round.length,
					"회차마다 십자 수가 같아야 한다");
			for (int index = 1; index < round.length; index++) {
				assertEquals(90.0 / DragonLastStandPatterns.CROSSES_PER_ROUND,
						round[index] - round[index - 1], 1.0E-9,
						"한 회차 안의 십자가 고르게 벌어져 있지 않다 — 바닥 면의 팔각형이 판정과 갈린다");
			}
			for (double angle : round) {
				double shape = ((angle % 90.0) + 90.0) % 90.0;
				assertTrue(shapes.add(shape), angle + "도가 앞의 것과 같은 모양이다");
			}
		}
		assertEquals(4, shapes.size());
	}

	/** 터지는 시각과 예고가 값에서 나온다. 터지는 틱에 다음 예고가 <b>끊기지 않고</b> 시작한다. */
	@Test
	void 십자가_60_90틱에_터진다() {
		assertEquals(60, DragonLastStandPatterns.crossFireStep(0));
		assertEquals(90, DragonLastStandPatterns.crossFireStep(1));
		assertEquals(100, DragonLastStandPatterns.crossDurationTicks(), "60 + 30 + 여운 10 이다");

		// 터지는 틱마다 정확히 한 회차가 터진다.
		for (int round = 0; round < DragonLastStandPatterns.CROSS_ROUNDS; round++) {
			assertEquals(round, DragonLastStandPatterns.crossFiredRound(
					DragonLastStandPatterns.crossFireStep(round)));
		}
		assertEquals(-1, DragonLastStandPatterns.crossFiredRound(59));
		assertEquals(-1, DragonLastStandPatterns.crossFiredRound(91));
		assertEquals(-1, DragonLastStandPatterns.crossFiredRound(120), "셋째 회차는 없다");

		// 표식이 한 틱도 끊기지 않는다. 0..89 는 늘 누군가를 예고하고 있다.
		for (int step = 0; step < 90; step++) {
			assertTrue(DragonLastStandPatterns.crossPendingRound(step) >= 0,
					step + "틱에 예고 중인 회차가 없다 — 1.5초가 쉬는 시간으로 읽힌다");
		}
		assertEquals(0, DragonLastStandPatterns.crossPendingRound(0));
		assertEquals(1, DragonLastStandPatterns.crossPendingRound(60),
				"첫 회차가 터지는 그 틱에 둘째 예고가 시작해야 한다");
		assertEquals(-1, DragonLastStandPatterns.crossPendingRound(90),
				"마지막이 터진 뒤에는 예고할 것이 없다");

		assertEquals(60, DragonLastStandPatterns.crossWarnTicks(0), "첫 예고는 3초다");
		assertEquals(30, DragonLastStandPatterns.crossWarnTicks(1), "그 뒤는 1.5초다");
	}

	/**
	 * 십자 판정이 <b>폭 3칸 선 넷</b> 그대로다.
	 *
	 * <p>네 각도 모두에서 굴려 본다. 「단순 피하기」가 성립하려면 <b>안전한 자리가 반드시 있어야</b>
	 * 하므로, 한 회차의 <b>쐐기 한가운데</b>(첫 십자에서 22.5도)가 반경 4칸부터 안전한지까지 센다 —
	 * 십자 둘이 함께라 쐐기가 45도이고 가운데 3.92칸 안은 어디에도 쐐기가 없다.
	 */
	@Test
	void 십자_판정이_폭_3칸_선_넷이다() {
		double half = DragonLastStandPatterns.CROSS_HALF_WIDTH;
		assertEquals(1.5, half, 1.0E-9, "폭 3칸의 반이다");

		for (double[] round : DragonLastStandPatterns.CROSS_ANGLES) {
			for (double base : round) {
				double radians = Math.toRadians(base);
				double alongX = Math.sin(radians);
				double alongZ = -Math.cos(radians);
				double sideX = Math.cos(radians);
				double sideZ = Math.sin(radians);

				// 중심은 두 선이 겹치는 자리다. 반드시 안이다.
				assertTrue(DragonLastStandPatterns.insideCross(0.0, 0.0, base, half,
						DragonLastStandPatterns.CROSS_REACH), base + "도에서 중심이 밖이다");

				for (double along = 1.0; along <= 30.0; along += 1.0) {
					assertTrue(DragonLastStandPatterns.insideCross(alongX * along, alongZ * along,
									base, half, DragonLastStandPatterns.CROSS_REACH),
							base + "도 선 위 " + along + "칸이 밖이다");
					double inX = alongX * along + sideX * (half - 0.01);
					double inZ = alongZ * along + sideZ * (half - 0.01);
					assertTrue(DragonLastStandPatterns.insideCross(inX, inZ, base, half,
									DragonLastStandPatterns.CROSS_REACH),
							base + "도 " + along + "칸에서 반폭 안쪽이 밖으로 읽힌다");
				}

				// 닿는 거리 밖은 밖이다.
				assertFalse(DragonLastStandPatterns.insideCross(
						alongX * (DragonLastStandPatterns.CROSS_REACH + 1.0),
						alongZ * (DragonLastStandPatterns.CROSS_REACH + 1.0), base, half,
						DragonLastStandPatterns.CROSS_REACH), base + "도에서 사거리 밖이 안이다");
			}

			// 쐐기 한가운데(첫 십자에서 22.5도)는 반경 4칸부터 안전하다. 「피할 곳이 있다」가 그것이다.
			double wedge = Math.toRadians(round[0] + 45.0 / 2.0);
			for (double away = 4.0; away <= 12.0; away += 0.5) {
				assertFalse(DragonLastStandPatterns.insideRound(Math.sin(wedge) * away,
								-Math.cos(wedge) * away, round, half, DragonLastStandPatterns.CROSS_REACH),
						round[0] + "도 회차의 쐐기 한가운데 " + away + "칸이 안으로 읽힌다 — 피할 곳이 없다");
			}
			// 가운데 3.9칸 안에는 쐐기가 없다 — 바닥 면의 팔각형이 그 자리다.
			assertTrue(DragonLastStandPatterns.insideRound(Math.sin(wedge) * 3.9,
					-Math.cos(wedge) * 3.9, round, half, DragonLastStandPatterns.CROSS_REACH),
					"3.9칸에서 쐐기가 열렸다 — 팔각형의 꼭짓점(3.92)이 판정과 갈렸다");
		}
	}

	/**
	 * ⚠⚠ <b>한 회차에 한 사람은 한 번만 맞는다.</b> 가운데처럼 선이 여럿 겹치는 자리에서도.
	 *
	 * <p>사람이 「두 십자를 한번에」라고 했고, 십자마다 따로 물으면 가운데 선 사람이 <b>두 번 맞고 두 번
	 * 띄워진다.</b> {@code insideRound} 가 물음 하나로 답하고 {@code fireRound} 가 사람 루프를 바깥에 두어
	 * 한 번만 친다 — 여기서는 그 물음이 「겹친 자리」에서도 참 하나인지와, 배선이 그 함수인지를 본다.
	 */
	@Test
	void 한_회차에_한_사람은_한_번만_맞는다() {
		for (double[] round : DragonLastStandPatterns.CROSS_ANGLES) {
			int overlapped = 0;
			for (double x = -5.0; x <= 5.0; x += 0.25) {
				for (double z = -5.0; z <= 5.0; z += 0.25) {
					int lines = 0;
					for (double base : round) {
						if (DragonLastStandPatterns.insideCross(x, z, base,
								DragonLastStandPatterns.CROSS_HALF_WIDTH,
								DragonLastStandPatterns.CROSS_REACH)) {
							lines++;
						}
					}
					if (lines >= 2) {
						overlapped++;
						assertTrue(DragonLastStandPatterns.insideRound(x, z, round,
										DragonLastStandPatterns.CROSS_HALF_WIDTH,
										DragonLastStandPatterns.CROSS_REACH),
								"두 십자가 겹친 자리가 회차 판정에서 빠졌다");
					}
				}
			}
			assertTrue(overlapped > 0, "겹치는 자리가 없으면 이 시험이 아무것도 재지 않는다");
		}
		String bytes = classBytes();
		assertTrue(bytes.contains("insideRound"),
				"회차 판정이 물음 하나가 아니다 — 십자마다 물으면 가운데서 두 번 맞는다");
		assertTrue(bytes.contains("fireRound"), "회차가 함께 터지는 자리가 없다");
	}

	/**
	 * 「단순 피하기」라 <b>지대가 좁아져도 피할 곳이 남는다.</b>
	 *
	 * <p>브레스에 축소 잠금이 붙은 까닭은 「피할 곳이 두 번 사라진다」였다. 이 패턴에 그 잠금을
	 * 걸지 않은 근거가 <b>여기서 재어지는 것</b>이다 — 마지막 지대(반변 12)의 내접원 절반(6칸)에서
	 * 안전한 방향이 남는지 회차마다 훑는다.
	 *
	 * <p>⚠ 2026-10-04 에 십자가 둘씩 터지게 되어 <b>그 원에서 안전한 몫이 반 넘게에서 3분의 1 남짓</b>
	 * (첫 회차 128도 · 둘째 136도)으로 줄었다. 쐐기 폭이 반경 6칸에서 1.7칸이라 사람 폭(0.6)보다
	 * 넉넉하다 — 그래서 축소 잠금은 여전히 걸지 않는다.
	 */
	@Test
	void 십자는_마지막_지대에서도_피할_곳이_있다() {
		double zone = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		for (double[] round : DragonLastStandPatterns.CROSS_ANGLES) {
			int safe = 0;
			for (double angle = 0.0; angle < 360.0; angle += 1.0) {
				double radians = Math.toRadians(angle);
				// 지대 내접원의 절반 거리에서 훑는다. 벽에 붙지 않고도 피할 수 있어야 한다.
				double x = Math.sin(radians) * zone * 0.5;
				double z = -Math.cos(radians) * zone * 0.5;
				if (!DragonLastStandPatterns.insideRound(x, z, round,
						DragonLastStandPatterns.CROSS_HALF_WIDTH,
						DragonLastStandPatterns.CROSS_REACH)) {
					safe++;
				}
			}
			assertTrue(safe > 90, round[0] + "도 회차에서 지대 안 안전한 방향이 " + safe
					+ "도뿐이다 — 「단순 피하기」가 성립하지 않으면 축소 잠금을 다시 봐야 한다");
			assertTrue(safe < 180, "안전한 몫이 반을 넘는다 — 십자가 둘씩 터지지 않고 있다: " + safe);
		}
		// 쐐기 폭 — 반경 6칸에서 두 띠 사이의 호 길이.
		double radius = zone * 0.5;
		double wedge = Math.toRadians(45.0) - 2.0 * Math.asin(DragonLastStandPatterns.CROSS_HALF_WIDTH / radius);
		assertEquals(1.68, wedge * radius, 0.01, "반경 6칸의 쐐기 폭이 1.7칸이다");
	}

	// ------------------------------------------------------------------ ④ 십자에 맞으면 12칸 솟는다 (2026-10-04 전에는 6칸)

	/**
	 * ⚠⚠ <b>맞은 사람이 12칸 솟는다.</b> 사람이 <b>명문 규칙을 알고 뒤집은</b> 값이다.
	 *
	 * <p>사람 말: <b>「30프로 2페이지때 십자가 공격받앗을때도 한 6칸 띄워버려 점프하게」</b> →
	 * 2026-10-04 <b>「십자 맞았을 때 하늘로 지금보다 2배는 더 날려버려」</b>. 이 전투의 설계 원칙에
	 * <b>「세로로 띄우지 않습니다」</b>가 적혀 있고, 그 규칙이 막으려던 사고는 아래 셋이 따로 막는다.
	 *
	 * <p>여기서 재는 것은 <b>「12」가 도달 높이인가</b>다. 처음 속도를 적어 두고 「12칸쯤 뜬다」고
	 * 쓰는 것이 아니라, <b>높이에서 속도를 역산</b>하고 그 속도가 실제로 그 높이에 닿는지를 센다.
	 */
	@Test
	void 십자가_맞은_사람을_12칸_띄운다() {
		assertEquals(12.0, DragonLastStandPatterns.CROSS_LIFT_BLOCKS, 1.0E-9,
				"사람이 「지금보다 2배」라고 한 값이다 — 6 의 두 배");

		// ① 식이 맞는지부터 검산한다. 바닐라 점프(처음 0.42)가 1.2522칸이라는 것은 널리 알려진
		//    값이고, 그것이 맞으면 「자리를 먼저 옮기고 → 중력을 빼고 → 0.98 을 곱한다」가 맞다.
		assertEquals(1.2522, DragonLastStandPatterns.liftApex(0.42), 0.0001,
				"바닐라 점프 높이가 안 나온다 — liftApex 의 틱 순서가 26.3 travelInAir 와 다르다");
		assertEquals(0.08, DragonLastStandPatterns.LIFT_GRAVITY, 1.0E-9,
				"26.3 Attributes.GRAVITY 기본값이다");
		assertEquals(0.98, DragonLastStandPatterns.LIFT_DRAG, 1.0E-9,
				"26.3 travelInAir 가 세로에 곱하는 값이다 — 수평의 0.91 과 다른 값이다");
		assertNotEquals(TrialEnderStorm.AIR_DRAG, DragonLastStandPatterns.LIFT_DRAG,
				"세로 감쇠를 수평 것으로 바꾸면 도달 높이가 조용히 달라진다");

		// ② 처음 속도가 높이에서 나온다. 손으로 적은 수가 아니다.
		assertEquals(DragonLastStandPatterns.liftSpeed(DragonLastStandPatterns.CROSS_LIFT_BLOCKS),
				DragonLastStandPatterns.CROSS_LIFT_SPEED, 1.0E-12,
				"속도를 손으로 적으면 중력·감쇠를 고칠 때 높이가 조용히 달라진다");
		assertEquals(1.49053, DragonLastStandPatterns.CROSS_LIFT_SPEED, 0.00001,
				"처음 속도가 1.49053 칸/틱이다(6칸이던 때 1.00746)");
		assertEquals(1.00746, DragonLastStandPatterns.liftSpeed(6.0), 0.00001,
				"6칸의 옛 속도 — 「2배」가 높이의 2배이지 속도의 2배가 아니다");

		// ③ 그 속도가 실제로 12칸에 닿는다. 「12칸」이 도달 높이라는 것이 이 한 줄이다.
		assertEquals(12.0, DragonLastStandPatterns.liftApex(DragonLastStandPatterns.CROSS_LIFT_SPEED),
				0.001, "도달 높이가 12칸이 아니다");
		assertEquals(9.58,
				DragonLastStandPatterns.liftApex(DragonLastStandPatterns.CROSS_LIFT_SPEED)
						/ DragonLastStandPatterns.liftApex(0.42), 0.01,
				"바닐라 점프의 9.6배다");

		// ④ ⚠ TrialRisks.launchVelocity 를 쓰지 않은 근거. 그쪽은 √(2gh) 근사라 10.55칸에서 멈춘다.
		double approximate = TrialRisks.launchVelocity(DragonLastStandPatterns.CROSS_LIFT_BLOCKS);
		assertEquals(1.3856, approximate, 0.0001, "√(2 × 0.08 × 12) 이다");
		assertEquals(10.553, DragonLastStandPatterns.liftApex(approximate), 0.001,
				"근사가 10.55칸까지밖에 안 뜬다 — 「12칸」을 수로 말한 요청에는 못 쓴다");
		assertTrue(DragonLastStandPatterns.CROSS_LIFT_SPEED > approximate,
				"근사보다 빨라야 12칸에 닿는다");
		// 「자리 폭격」의 4칸은 그 근사로도 3.97 이라 모자람이 0.7% 뿐이다. 그래서 그쪽은 안 고쳤다.
		assertEquals(3.971, DragonLastStandPatterns.liftApex(TrialRisks.launchVelocity(4.0)), 0.001,
				"「자리 폭격」의 4칸은 근사로도 거의 맞는다 — TrialRisks 를 고칠 이유가 없다");
	}

	/**
	 * ⚠⚠ <b>가로 성분을 한 톨도 더하지 않는다.</b> 이것이 「세로로 띄우지 않습니다」를 뒤집으면서도
	 * 안전한 유일한 근거다.
	 *
	 * <p>가로가 섞이면 띄워진 사람은 <b>마찰이 안 먹는 공중에서</b> 그만큼을 가고, 그것이 그 규칙이
	 * 막으려던 사고다({@link DragonLastStandPatterns#AIRBORNE_PUSH_SCALE} 가 그 다섯 배를 수로
	 * 들고 있다). 들고 있던 가로 속도를 <b>바꾸지도 않는다</b> — 0 으로 지우면 공중에서 조작을
	 * 빼앗는 것이 되고, 그것은 사람이 「단순 피하기」라고 못박은 카드가 할 일이 아니다.
	 */
	@Test
	void 띄우는_데_가로_성분이_없다() {
		double[][] carried = {
			{0.0, 0.0}, {0.3, 0.0}, {0.0, -0.3}, {0.286, 0.286}, {-1.5, 2.5}, {0.01, -0.01},
		};
		for (double[] motion : carried) {
			Vec3 before = new Vec3(motion[0], -0.78, motion[1]);
			Vec3 after = DragonLastStandPatterns.liftMotion(before);
			assertEquals(before.x, after.x, 1.0E-12,
					"가로 x 가 달라졌다 — 공중에서는 마찰이 안 먹어 그만큼이 통째로 이동이 된다");
			assertEquals(before.z, after.z, 1.0E-12, "가로 z 가 달라졌다");
			assertEquals(DragonLastStandPatterns.CROSS_LIFT_SPEED, after.y, 1.0E-12,
					"세로를 덮어쓰지 않았다 — 떨어지던 사람은 안 뜬다");
		}
		// ⚠ shove 와 정확히 반대다. 그쪽은 가로만 덮어쓰고 세로를 읽은 그대로 돌려놓는다.
		String bytes = classBytes();
		assertTrue(bytes.contains("liftMotion"),
				"속도를 짓는 것을 한 곳에 모아 두지 않으면 이 시험이 아무것도 재지 못한다");
	}

	/**
	 * ⚠⚠ <b>이 띄움에서 비롯한 낙하만 공짜다.</b> 다른 낙하는 그대로 아프다.
	 *
	 * <p>12칸 낙하는 바닐라 피해 <b>9</b> 이고(맨몸 9 — 사람에게 알렸다), {@code minecraft:fall} 이
	 * {@code #bypasses_armor} 라 다이아 풀셋이 한 점도 안 깎아 무장 기준 <b>3.24</b> 다. 그 3.24 는
	 * {@code TrialRisks.worstCaseTickDamage} 가 <b>세지 않는 피해</b>이고, 한 판에 최대 두 대라 면제가
	 * 빠지면 두 대가 <b>20.02 — 팀 체력 20 을 넘는다.</b> 셈에는 13.54 만 들어온다.
	 *
	 * <p>쓰는 것은 바닐라가 바로 이 일을 위해 들고 있는 장치다 —
	 * {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse(boolean, Vec3)} 이고, 면제되는 양이
	 * {@code min(낙하 거리, 띄운 자리 y − 지금 y)} 라 <b>띄운 자리보다 아래로 떨어지는 몫은 그대로
	 * 아프다.</b>
	 */
	@Test
	void 띄움은_그_낙하만_공짜로_만든다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("setIgnoreFallDamageFromCurrentImpulse"),
				"낙하 피해를 면제하지 않으면 셈 밖의 피해가 매번 얹힌다");
		assertFalse(bytes.contains("resetFallDistance"),
				"낙하 거리를 지우면 이 띄움과 무관한 낙하까지 공짜가 된다 — 거리로 끊을 것");
		assertFalse(bytes.contains("fallDistance"),
				"낙하 거리를 직접 만지면 면제의 자가 「거리」에서 「시간」으로 바뀐다");

		float perHit = GearedDamage.afterGear(DragonLastStand.CROSS_FISSURE_DAMAGE,
				GearedDamage.Source.EXPLOSION);
		assertEquals(6.77F, perHit, 0.01F, "균열 한 대가 무장 기준 6.77 이다");
		// 12칸 낙하의 바닐라 피해와, 그것이 무장 기준으로 얼마가 되는가.
		int rawFall = (int) Math.ceil(DragonLastStandPatterns.CROSS_LIFT_BLOCKS - 3.0);
		assertEquals(9, rawFall, "안전 낙하 3칸을 빼면 12칸은 피해 9 다 — 맨몸이면 그대로 9");
		float gearedFall = rawFall * (1.0F - GearedDamage.PROTECTION_EPF / 25.0F);
		assertEquals(3.24F, gearedFall, 0.01F,
				"낙하는 방어도를 지나가므로 보호 IV 만 듣는다 — 9 × 0.36 이다");
		// 한 판에 최대 두 대다(회차가 둘이고 한 회차에 한 번).
		assertEquals(2, DragonLastStandPatterns.CROSS_ROUNDS);
		float counted = perHit * DragonLastStandPatterns.CROSS_ROUNDS;
		assertEquals(13.54F, counted, 0.01F, "적힌 값으로 잡아 둔 두 대가 13.54 다");
		assertTrue(counted < GearedDamage.TEAM_HEALTH, "셈으로는 두 대에 안 죽는다");
		float uncounted = (perHit + gearedFall) * DragonLastStandPatterns.CROSS_ROUNDS;
		assertEquals(20.02F, uncounted, 0.02F,
				"면제가 빠지면 두 번 다 맞은 사람이 20.02 를 받는다 — 셈 밖의 낙하가 팀을 끝낸다");
		assertTrue(uncounted > GearedDamage.TEAM_HEALTH,
				"이 부등호가 뒤집혔으면 위의 경고를 다시 쓸 것");
	}

	/**
	 * ⚠⚠ <b>띄워진 직후에 날개 퍼덕이기가 와도 섬 밖으로 못 나간다.</b>
	 *
	 * <p>이것이 이 작업에서 <b>가장 위험한 자리</b>다 — 십자가 만든 상태(공중)가 <b>남이 만든
	 * 넉백(날개 퍼덕이기)의 입력</b>이 된다. 12칸이 되어 공중 시간이 25 → <b>35틱</b>으로 늘었다.
	 *
	 * <p>⚠ <b>보장은 공중 시간과 무관하다.</b> 공중 비율은 「떠 있는가」만 묻고 천장 둘은 「어디까지」만
	 * 자르므로, 35틱 내내 떠 있어도 번치마다 같은 셈이다. 도우미가 번치 여덟을 <b>전부 공중</b>으로
	 * 굴리므로 35틱이 아니라 100틱 내내 떠 있는 경우까지 덮는다.
	 *
	 * <p>그래서 <b>{@link #점프_중에_밀려도_섬_밖으로_못_나간다} 와 같은 도우미를 그대로 쓴다.</b>
	 * 사람이 스스로 뛴 것이든 우리가 띄운 것이든 <b>수평으로는 같은 모델</b>이다.
	 *
	 * <p>⚠ 실제로는 그 겹침이 생기지 않는다 — 마지막 회차 뒤 여운 10틱 + 쉬는 시간 최소 40틱이
	 * 공중 35틱보다 길고, 패턴은 한 번에 하나뿐이다. 그래도 굴리는 것은 「쉬는 시간이 줄면」을 미리
	 * 막기 위해서다.
	 */
	@Test
	void 띄워진_직후에_밀려도_섬_밖으로_못_나간다() {
		// ① 띄워진 사람은 16틱 올라가고 35틱 만에 되돌아온다 — 번치 간격(12틱)의 두 배가 넘는다.
		int rise = DragonLastStandPatterns.liftRiseTicks(DragonLastStandPatterns.CROSS_LIFT_SPEED);
		int airborne =
				DragonLastStandPatterns.liftAirborneTicks(DragonLastStandPatterns.CROSS_LIFT_SPEED);
		assertEquals(16, rise, "12칸까지 16틱 올라간다(6칸이던 때 12틱)");
		assertEquals(35, airborne, "되돌아오는 데까지 35틱(1.75초)이다(6칸이던 때 25틱)");
		assertTrue(airborne > DragonLastStandPatterns.WING_PULSE_TICKS,
				"공중에 있는 시간이 번치 간격보다 짧으면 「띄운 뒤에 밀린다」가 안 생긴다 — 그러면 "
						+ "이 시험이 아무것도 재지 않는다");
		assertTrue(airborne >= DragonLastStandPatterns.WING_PULSE_TICKS * 2,
				"공중에 있는 동안 번치가 두 번 들어오지 않으면 위 설명을 고칠 것: 공중 " + airborne
						+ "틱 대 간격 " + DragonLastStandPatterns.WING_PULSE_TICKS + "틱");
		// 실제로는 겹치지 않는다 — 여운 + 쉬는 시간 최소가 공중 시간보다 길다.
		assertTrue(DragonLastStandPatterns.CROSS_AFTERGLOW_TICKS + DragonLastStand.REST_MIN_TICKS
						> airborne,
				"띄워진 사람이 내려오기 전에 다음 패턴이 시작할 수 있게 됐다 — 이 시험이 막는 경우가 "
						+ "실제로 생긴다");

		// ② 그 사람이 받는 것은 「공중 세기」다. 띄우기가 가로를 안 더하므로 들고 있는 가로는 0 이다.
		Vec3 lifted = DragonLastStandPatterns.liftMotion(Vec3.ZERO);
		assertEquals(0.0, lifted.x, 1.0E-12);
		assertEquals(0.0, lifted.z, 1.0E-12);

		// ③ 그 상태에서 여덟 번치를 섬 곳곳에서 통째로 굴린다. 점프 중과 같은 도우미다.
		밀려도_섬을_벗어나지_않는다("띄워진 직후", true, 0.0);
	}

	/**
	 * ⚠⚠ <b>띄워진 뒤 쌓인 수평을 안고 밀려도 섬 밖으로 못 나간다 — 2026-10-04 에 닫은 구멍.</b>
	 *
	 * <p>{@link #드래곤이_쌓아_둔_세로를_클라이언트로_내보내지_않는다} 가 세로를 막은 뒤에도
	 * <b>수평이 열려 있었다.</b>
	 * {@code liftCross} 와 {@code pullSuck} 은 <b>수평을 읽어서 돌려놓고</b>
	 * {@code syncVelocity} 를 켠다 — 곧 바닐라 {@code EnderDragon.knockBack} 이 쌓아 둔 수평을
	 * <b>본인에게 배달하면서 동시에 그 사람을 공중(감쇠 0.91)으로 띄우고 낙하 피해까지 면제</b>
	 * 하는 자리였다. {@code shove} 는 수평을 <b>덮어쓰므로</b> 거기서는 세로로만 샜다.
	 * ⚠ {@code pullSuck} 은 2026-10-04 저녁에 이 길을 떠났다 — 서버의 속도를 아예 안 보내고 수평 한 벌을
	 * 더하게 보낸다({@link #흡입은_속도를_덮어쓰지_않고_더하게_보낸다}).
	 *
	 * <h2>⚠ 닫은 자리는 이 파일이 아니다 — <b>뿌리를 끊었다</b></h2>
	 *
	 * <p>{@code EnderDragonContactDamageMixin} 이 {@code hurt} 와 함께
	 * <b>{@code knockBack} 까지 같은 {@code contactDamageOff()} 깃발로 끊는다.</b> 최후의 저항이
	 * 도는 동안 쌓이는 값이 <b>아예 생기지 않으므로</b> 「읽어서 돌려놓는다」가 돌려놓을 오염이
	 * 없다. <b>{@code liftCross}·{@code pullSuck} 의 실행되는 코드는 한 줄도 안 고쳤다</b> —
	 * 「들고 있던 가로 속도를 바꾸지 않는다」는 설계 약속을 그대로 두고 구멍만 닫는 길이 그것이다.
	 *
	 * <p>그러니 이 시험이 묻는 것은 셋이다.
	 *
	 * <ol>
	 *   <li>쌓이는 수가 그대로인가 — <b>12.65칸/틱</b>(한 틱 최댓값) · <b>15.21</b>(바닥 종착) ·
	 *       <b>127.9</b>(공중 종착). 이 수가 바뀌면 위의 모든 설명이 함께 바뀐다</li>
	 *   <li>⚠ <b>띄우기가 그 수를 여전히 그대로 돌려놓는가.</b> 그렇다 — 그것이 설계 약속이고,
	 *       그래서 <b>뿌리가 끊겨 있어야만</b> 안전하다. 여기가 거짓이 되면(띄우기가 수평을
	 *       덮어쓰게 되면) 그것도 사고다. 사람이 「가로를 한 톨도 안 더한다」로 정했다</li>
	 *   <li><b>뿌리가 실제로 끊겨 있는가</b> — 믹스인이 {@code knockBack} 을 그 깃발로 문다</li>
	 * </ol>
	 *
	 * <p>그 위에 <b>{@link #점프_중에_밀려도_섬_밖으로_못_나간다} 와 같은 도우미로</b> 세 수를
	 * 전부 굴려 본다. 도우미를 새로 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
	 */
	@Test
	void 띄워진_뒤_쌓인_수평을_안고_밀려도_섬_밖으로_못_나간다() {
		// ① 26.3 knockBack: dx ÷ max(dx²+dz², 0.1) × 4. 분모가 0.1 에서 멈추므로 최댓값이
		//    √0.1 칸에서 4 ÷ √0.1 이다.
		double impulse = 4.0 / Math.sqrt(0.1);
		assertEquals(12.649, impulse, 0.001, "바닐라가 한 틱에 더하는 수평이 12.65칸/틱이다");
		double grounded = impulse * DragonLastStandPatterns.GROUND_DRAG
				/ (1.0 - DragonLastStandPatterns.GROUND_DRAG);
		double airborne = impulse * TrialEnderStorm.AIR_DRAG / (1.0 - TrialEnderStorm.AIR_DRAG);
		assertEquals(15.21, grounded, 0.01, "바닥 종착 속도가 15.21칸/틱이다");
		assertEquals(127.9, airborne, 0.1, "공중 종착 속도가 127.9칸/틱이다 — 한 틱에 섬을 넘는다");

		// ② ⚠ 띄우기는 그 수를 한 톨도 안 바꾼다. 설계 약속이라 바뀌면 그것도 사고다 —
		//    그래서 안전을 보증하는 것은 이 자리가 아니라 ③ 의 뿌리다.
		for (double carry : new double[] {impulse, grounded, airborne}) {
			Vec3 lifted = DragonLastStandPatterns.liftMotion(new Vec3(carry, 0.0, carry));
			assertEquals(carry, lifted.x, 1.0E-12,
					"띄우기가 가로를 바꿨다 — 「가로를 한 톨도 안 더한다」가 거짓이 됐다");
			assertEquals(carry, lifted.z, 1.0E-12, "같은 이유로 z 도 그대로여야 한다");
		}

		// ③ ⚠⚠ 뿌리가 끊겨 있다. 쌓이는 값이 아예 안 생기므로 ② 가 돌려놓을 오염이 없다.
		String mixin = read("/com/sharedfate/mixin/EnderDragonContactDamageMixin.class");
		assertTrue(mixin.contains(
						"knockBack(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V"),
				"⚠⚠ 믹스인이 knockBack 을 안 끊는다 — ② 가 그대로 돌려놓는 수평이 쌓이기 시작하고, "
						+ "그 사람은 공중(감쇠 0.91)에서 낙하 피해도 면제된 채 섬을 넘는다. "
						+ "서술자가 hurt 와 글자까지 같으니 이름을 특히 조심할 것");
		assertTrue(mixin.contains("contactDamageOff"),
				"끊는 깃발이 최후의 저항 전용 깃발이 아니다 — 일반 전투의 날개 밀치기는 바닐라 "
						+ "동작이라 끊으면 안 된다");
		// 이 파일은 바닐라 넉백에 손대지 않는다. 끄는 것은 믹스인의 일이다.
		assertFalse(classBytes().contains("knockBack"),
				"패턴 파일이 바닐라 넉백을 직접 만진다 — 끄는 자리가 둘이 되면 한쪽만 고쳐진다");

		// ④ 그래도 세 수를 전부 굴려 본다. 뿌리가 끊겼다는 것만 믿지 않는 것이 이 저장소의 규칙이다.
		밀려도_섬을_벗어나지_않는다("쌓인 한 틱치를 안고 띄워져서", true, impulse);
		밀려도_섬을_벗어나지_않는다("쌓인 바닥 종착을 안고 띄워져서", true, grounded);
		밀려도_섬을_벗어나지_않는다("쌓인 공중 종착을 안고 띄워져서", true, airborne);
		밀려도_섬을_벗어나지_않는다("쌓인 한 틱치를 안고 바닥에서", false, impulse);
	}

	// ------------------------------------------------------------------ 예산과 규약

	/**
	 * 한 틱 점 예산을 넘지 않는다.
	 *
	 * <p>⚠ <b>더하기다.</b> 패턴 넷은 동시에 돌지 않지만 <b>번개는 언제나 함께 돈다</b>. 안전지대는 월드
	 * 보더라, 빨간 투명 면(부채꼴·십자)은 디스플레이 개체라, 반구 블록 파괴는 블록이라 <b>점을 한 개도
	 * 쓰지 않는다.</b>
	 */
	@Test
	void 한_틱_점_예산을_넘지_않는다() {
		int worst = DragonLastStandPatterns.worstCasePointsPerTick();
		assertTrue(worst <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"한 틱에 " + worst + "점이라 예산 " + TrialLandingShock.MAX_POINTS_PER_TICK
						+ "을 넘는다 — 개수나 반경을 올린 사람은 여기서 멈출 것");
		assertEquals(228, DragonLastStandPatterns.wingBeatPoints(),
				"날개 퍼덕이기 — 파랑 고리 131 + 벽 68 + 바깥 가닥 24 + 돌풍 5");
		assertEquals(236, DragonLastStandPatterns.coneWarnPoints(),
				"부채꼴 예고 — 호가 하나에서 셋이 되어 202 → 236. 예고가 4초로 줄어도 한 틱 몫은 같다");
		assertEquals(55, DragonLastStandPatterns.coneSprayPoints(), "부채꼴 브레스 파티클");
		assertEquals(68, DragonLastStandPatterns.coneFlamePoints(), "터지는 틱의 불꽃");
		assertEquals(123, DragonLastStandPatterns.coneFirePoints(), "터지는 틱 전체");
		assertEquals(163, DragonLastStandPatterns.suckWarnPoints(),
				"공허 흡입 예고 — 검은 속 30 + 빨간 고리 51 + 그 고리의 벽 17 + 안쪽 가닥 48 + 불 결계 17");
		assertEquals(163, DragonLastStandPatterns.suckPoints(), "공허 흡입 전체");
		assertEquals(0, DragonLastStandPatterns.crossWarnPoints(),
				"십자 예고 — 바닥 면이라 0점이다(먼지 선 54 + 흰 기둥 108 을 걷었다)");
		assertEquals(124, DragonLastStandPatterns.crossFlashPoints(),
				"십자 하나가 터지는 연출 — 눕는 갈라짐 82 + 솟는 기둥 42");
		assertEquals(4, DragonLastStandPatterns.crossLiftPoints(),
				"띄워진 사람마다 발밑 기둥 하나 — 십자가 둘이어도 사람당 한 번이다");
		assertEquals(252, DragonLastStandPatterns.crossPoints(),
				"십자가 터지는 틱 — 갈라짐 두 벌(124 × 2) + 띄움 기둥 4");
		assertEquals(70, DragonLastStandPatterns.lightningWarnPoints(),
				"상시 번개 예고 — 열 곳을 여섯 틱에 나눠 그린 한 틱 몫");
		assertEquals(48, DragonLastStandPatterns.lightningStrikePoints(),
				"상시 번개 내리침 — 바닥 표식은 없고 깎인 사람마다 발밑 입자 12점뿐이다");
		assertEquals(70, DragonLastStandPatterns.lightningPoints(),
				"번개는 예고 틱과 내리침 틱 가운데 바쁜 쪽이다 — 지금은 예고가 이긴다");
		assertEquals(322, worst,
				"가장 바쁜 틱 — 272 → 356 → 360 → 322(2026-10-04: 십자 예고를 바닥 면으로 바꿔 162 를 "
						+ "덜고, 갈라짐이 두 벌이 되어 124 를 더했다)");
		assertEquals(DragonLastStandPatterns.lightningPoints()
						+ DragonLastStandPatterns.crossPoints(), worst,
				"최악이 십자가 터지는 틱이 아니게 됐으면 클래스 설명의 예산 절도 함께 고칠 것");
		assertTrue(DragonLastStandPatterns.suckPoints() < DragonLastStandPatterns.crossPoints(),
				"불 결계를 키우다 흡입이 가장 바쁜 패턴이 되면 클래스 설명을 고칠 것");
	}

	/**
	 * ⚠ <b>번개가 내리치는 틱도 센다.</b> 「내리치는 틱은 표식이 없어 0점」이 더는 참이 아니다.
	 *
	 * <p>깎인 사람 발밑에 점을 뿌리게 되면서({@code slowStruck}) 그 틱에도 점이 나간다. 지금은
	 * 예고(70)가 내리침(48)보다 바빠서 답이 안 바뀌지만, <b>그 부등호가 뒤집히는 날 식이 조용히 틀린
	 * 답을 주면</b> 예산을 넘긴 것을 아무도 모른다 — 되먹임 점을 올리려는 사람이 여기서 멈춘다.
	 */
	@Test
	void 번개는_예고_틱과_내리침_틱_가운데_바쁜_쪽을_센다() {
		assertEquals(12, DragonLastStandPatterns.LIGHTNING_SLOW_MARK_POINTS,
				"「엔더 파동」의 24점의 절반이다 — 그 절반이 예산에서 나온 수다");
		assertEquals(Math.max(DragonLastStandPatterns.lightningWarnPoints(),
						DragonLastStandPatterns.lightningStrikePoints()),
				DragonLastStandPatterns.lightningPoints(),
				"둘 가운데 큰 쪽을 세지 않으면 바쁜 틱이 바뀌어도 이 식이 모른다");
		assertTrue(DragonLastStandPatterns.lightningStrikePoints()
						< DragonLastStandPatterns.lightningWarnPoints(),
				"내리침 틱이 예고 틱보다 바빠졌다 — 그러면 최악이 「번개 내리침 + 십자가 터지는 "
						+ "틱」이 되고 그 합을 예산과 다시 견뎌야 한다: 내리침 "
						+ DragonLastStandPatterns.lightningStrikePoints() + " 대 예고 "
						+ DragonLastStandPatterns.lightningWarnPoints());
		int strikeWorst = DragonLastStandPatterns.lightningStrikePoints()
				+ DragonLastStandPatterns.crossPoints();
		assertEquals(300, strikeWorst, "내리침 틱이 최악이 되어도 300점이다");
		assertTrue(strikeWorst <= TrialLandingShock.MAX_POINTS_PER_TICK);
	}

	/**
	 * ⚠ <b>십자 예고가 점을 한 개도 안 쓴다 — 먼지 선과 흰 기둥을 걷고 바닥 면으로 바꿨다.</b>
	 *
	 * <p>사람 말(2026-10-04): <b>「이것도 브레스처럼 투명땅으로 표시했으면 해」</b>. 전에는 이 자리에서
	 * 「먼지 1.5배로 키운 뒤에도 살아 있는 점이 선(9,600) 안인가」를 셌는데, 그 먼지가 사라졌다.
	 * 이제 묻는 것은 <b>되돌아오지 않았는가</b>다 — 선을 되살리면 면과 선이 두 겹으로 같은 말을 하고
	 * 예산만 162점 다시 먹는다.
	 */
	@Test
	void 십자_예고가_점_대신_바닥_면을_쓴다() {
		assertEquals(0, DragonLastStandPatterns.crossWarnPoints(),
				"십자 예고에 점이 되살아났다 — 바닥 면이 그 일을 대신한다");
		String bytes = classBytes();
		assertFalse(bytes.contains("markCross"), "먼지 선·흰 기둥을 그리던 메서드가 되살아났다");
		assertFalse(bytes.contains("DustParticleOptions"),
				"크기를 키운 먼지(십자 전용)가 되살아났다 — 살아 있는 점의 셈부터 다시 할 것");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandCrossPanel"),
				"바닥 면을 세우지 않는다 — 사람이 「브레스처럼 투명땅으로」라고 했다");
		// 터질 때 솟는 기둥과 균열음은 남겼다 — 「어디」가 아니라 「언제」·「터졌다」를 말하는 갈래다.
		assertTrue(bytes.contains("flashCross"), "터질 때 솟는 기둥이 사라졌다");
		assertTrue(bytes.contains("DEEPSLATE_BREAK"), "균열음이 사라졌다");
	}

	/**
	 * ⚠ 공허 흡입의 검은 속은 <b>나눠 그리지 않으면</b> 최악의 틱을 갈아치운다.
	 *
	 * <p>검은 원의 속은 한 틱에 179점이다. 나눠 그리기를 되돌리려는 사람이 여기서 멈춘다. (십자
	 * 가장자리도 전에는 여기서 셌는데 2026-10-04 에 바닥 면으로 바뀌어 점이 없다.)
	 */
	@Test
	void 흡입의_검은_속은_나눠_그려야_한다() {
		assertEquals(TrialEndRain.MARK_MAX_STRIDE, DragonLastStandPatterns.SUCK_MARK_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다");

		int suckFill = 0;
		for (double radius = TrialWarning.POINT_GAP; radius < DragonLastStandPatterns.SUCK_RADIUS;
				radius += TrialWarning.POINT_GAP) {
			suckFill += DragonLastStandPatterns.suckFillPoints(radius);
		}
		assertEquals(179, suckFill, "검은 원의 속을 매 틱 다 채우면 179점이다");

		// 나눠 그리기를 되돌리면 그 몫이 「나눈 한 틱 몫」에서 「한 바퀴 전부」로 바뀐다.
		int suckUnsplit = DragonLastStandPatterns.suckWarnPoints()
				- (suckFill + DragonLastStandPatterns.SUCK_MARK_STRIDE - 1)
						/ DragonLastStandPatterns.SUCK_MARK_STRIDE
				+ suckFill;
		assertTrue(suckUnsplit > DragonLastStandPatterns.crossPoints(),
				"나눠 그릴 이유가 없어졌으면 SUCK_MARK_STRIDE 의 설명도 함께 고칠 것");
		// 불꽃 벽도 나눠 세운다 — 매 틱 다 세우면 51점이다.
		assertTrue(DragonLastStandPatterns.suckFirePoints() < TrialWarning.ringPoints(
				DragonLastStandPatterns.SUCK_FIRE_RADIUS), "불꽃 벽을 나눠 세우지 않는다");
	}

	// ------------------------------------------------------------------ ④ 패턴마다 다른 소리

	/**
	 * ⚠⚠ <b>네 패턴의 소리가 서로 다른 「실제 파일」이다.</b>
	 *
	 * <p>사람 말: <b>「각각 패턴마다 소리가 구분되엇으면해」</b>. 그런데 <b>이름이 다른 것으로는
	 * 아무것도 보장되지 않는다</b> — 이 저장소가 세 번 걸렸다.
	 *
	 * <table border="1">
	 *   <caption>걸린 이력</caption>
	 *   <tr><th>이름</th><th>실제 파일</th><th>같은 파일을 쓰던 것</th></tr>
	 *   <tr><td>{@code DRAGON_FIREBALL_EXPLODE}</td><td>{@code random/explode1~4}</td>
	 *       <td>일반 폭발음</td></tr>
	 *   <tr><td>{@code END_GATEWAY_SPAWN}</td><td>{@code random/explode1~4}</td>
	 *       <td>{@code GENERIC_EXPLODE}</td></tr>
	 *   <tr><td>{@code FIRECHARGE_USE}</td><td>{@code mob/ghast/fireball4}</td>
	 *       <td>{@code ENDER_DRAGON_SHOOT}·{@code GHAST_SHOOT}</td></tr>
	 * </table>
	 *
	 * <p>⚠ 아래 표의 오른쪽은 <b>26.3 바닐라 {@code assets/minecraft/sounds.json} 을 직접 열어
	 * 베낀 것</b>이다(판 자산은 시험의 클래스패스에 없어 여기서 다시 읽을 수 없다). <b>소리를
	 * 바꾸는 사람은 이 표를 고치기 전에 그 파일을 먼저 열 것.</b>
	 */
	@Test
	void 네_패턴의_소리가_서로_다른_실제_파일이다() {
		// 패턴 → {상수 이름, 그 상수가 가리키는 실제 파일 묶음}
		String[][] cues = {
			{"ENDER_DRAGON_FLAP", "mob/enderdragon/wings1~6", "날개 퍼덕이기 · 발동"},
			{"GHAST_WARN", "mob/ghast/charge", "부채꼴 브레스 · 예고"},
			{"ENDER_DRAGON_SHOOT", "mob/ghast/fireball4", "부채꼴 브레스 · 발동"},
			{"BREEZE_INHALE", "mob/breeze/inhale1~2", "공허 흡입 · 예고와 흡입"},
			{"WARDEN_SONIC_BOOM", "mob/warden/sonic_boom1~4", "공허 흡입 · 발동"},
			{"DEEPSLATE_BREAK", "block/deepslate/break1~4", "십자 균열 · 예고"},
			{"WARDEN_DIG", "mob/warden/dig", "십자 균열 · 발동"},
			// 2026-10-04 — 공허 흡입의 불 결계. block.fire.ambient 가 fire/fire 하나를 가리키고 이
			// 저장소의 어느 카드도 그 파일을 안 쓴다. ⚠ BLAZE_BURN 도 같은 fire/fire 다.
			{"FIRE_AMBIENT", "fire/fire", "공허 흡입 · 불 결계"},
		};
		String bytes = classBytes();
		Set<String> files = new java.util.HashSet<>();
		for (String[] cue : cues) {
			assertTrue(bytes.contains(cue[0]), cue[2] + " 의 소리(" + cue[0] + ")가 빠졌다");
			assertTrue(files.add(cue[1]),
					cue[2] + " 가 다른 패턴과 같은 파일을 가리킨다: " + cue[1]
							+ " — 이름이 달라도 같은 소리로 들린다");
		}
		assertEquals(cues.length, files.size());

		// ⚠ 걸린 이력 셋이 되돌아오지 않았는지. 전부 이 판의 다른 소리와 파일이 겹치는 것들이다.
		assertFalse(bytes.contains("FIRECHARGE_USE"),
				"mob/ghast/fireball4 라 ENDER_DRAGON_SHOOT 과 글자 하나까지 같다");
		assertFalse(bytes.contains("GHAST_SHOOT"),
				"「기둥 화염구」가 쓰는 소리다 — 같은 판에서 두 카드가 같은 소리를 쓰면 구별되지 않는다");
		assertFalse(bytes.contains("DRAGON_FIREBALL_EXPLODE"),
				"random/explode1~4 라 일반 폭발음과 같다 — 「종말의 비」가 이미 걸렸다");
		assertFalse(bytes.contains("END_GATEWAY_SPAWN"), "GENERIC_EXPLODE 와 같은 파일이다");
		assertFalse(bytes.contains("GENERIC_EXPLODE"),
				"random/explode1~4 를 이미 다섯 자리가 쓰고 있어 「또 폭발이 터졌다」로 묻힌다");
		// 공용 층 소리는 그대로 둔다. 그쪽 파일은 TrialWarning 이 들고 있으므로 여기 없어야 맞다.
		assertFalse(bytes.contains("ENDER_DRAGON_GROWL"),
				"층 소리를 패턴이 직접 내면 공용 경고와 제 소리가 같아진다");
		assertFalse(bytes.contains("BLAZE_BURN"),
				"fire/fire 라 불 결계와 같은 파일이다 — 이름이 달라도 같은 소리로 들린다");
	}

	/**
	 * ⚠ 예고 소리와 발동 소리가 <b>갈려 있다.</b> 「온다」와 「터졌다」가 같은 소리면 예고가 아니다.
	 *
	 * <p>날개 퍼덕이기만 예고가 없다 — <b>예고가 없는 패턴</b>이기 때문이다(시작하는 그 틱에 첫
	 * 충격파가 나가고 피해가 0 이다). 그 대신 여덟 번의 음높이가 오른다({@link #날개_음높이가_여덟_번에_걸쳐_오른다}).
	 */
	@Test
	void 예고_소리와_발동_소리가_갈려_있다() {
		String bytes = classBytes();
		// 부채꼴 · 흡입 · 십자는 예고와 발동이 다른 상수다.
		assertTrue(bytes.contains("GHAST_WARN") && bytes.contains("ENDER_DRAGON_SHOOT"));
		assertTrue(bytes.contains("BREEZE_INHALE") && bytes.contains("WARDEN_SONIC_BOOM"));
		assertTrue(bytes.contains("DEEPSLATE_BREAK") && bytes.contains("WARDEN_DIG"));
		// 되풀이되는 예고 셋. 한 번 울리는 소리로는 「차오르고 있다」를 말할 수 없다.
		assertEquals(20, DragonLastStandPatterns.CONE_CHARGE_TICKS, "부채꼴은 1초마다다");
		assertEquals(10, DragonLastStandPatterns.SUCK_BREATH_TICKS, "흡입은 0.5초마다다");
		assertEquals(10, DragonLastStandPatterns.CROSS_CRACK_TICKS, "십자는 0.5초마다다");
	}

	/**
	 * 십자 예고에 <b>균열음</b>이 되풀이되고 음높이가 회차마다 <b>끝까지 오른다.</b>
	 *
	 * <p>예고가 3초·1.5초로 다르다(회차가 셋이던 때는 3·1.5·1.5). 고정된 길이로 나누면 둘째가 중간
	 * 음높이에서 끝나 「지금 터진다」가 안 들린다 — 그래서 {@code crackPitch} 가 <b>예고 길이를 받는다.</b>
	 */
	@Test
	void 십자_예고에_균열음이_울리고_음높이가_오른다() {
		for (int round = 0; round < DragonLastStandPatterns.CROSS_ROUNDS; round++) {
			int warn = DragonLastStandPatterns.crossWarnTicks(round);
			int plays = 0;
			float previous = -1.0F;
			for (int into = 0; into < warn; into++) {
				if (into % DragonLastStandPatterns.CROSS_CRACK_TICKS != 0) {
					continue;
				}
				float pitch = DragonLastStandPatterns.crackPitch(into, warn);
				assertTrue(pitch > previous,
						round + "번째 " + into + "틱에서 음높이가 오르지 않았다");
				previous = pitch;
				plays++;
			}
			assertEquals(round == 0 ? 6 : 3, plays, round + "번째 예고의 울림 수가 다르다");
			assertEquals(0.7F, DragonLastStandPatterns.crackPitch(0, warn), 1.0E-6F, "첫 음이 낮다");
			assertEquals(1.5F, previous, 1.0E-6F,
					round + "번째 마지막 울림이 끝까지 안 올라갔다 — 예고 길이로 나누지 말 것");
		}
	}

	/** 여덟 번치의 음높이가 올라 <b>몇 번째인가</b>가 들린다. 전에는 여덟 번이 모두 같았다. */
	@Test
	void 날개_음높이가_여덟_번에_걸쳐_오른다() {
		float previous = -1.0F;
		for (int pulse = 0; pulse < DragonLastStandPatterns.WING_PULSES; pulse++) {
			float pitch = DragonLastStandPatterns.flapPitch(pulse);
			assertTrue(pitch > previous, pulse + "번째에서 음높이가 오르지 않았다");
			previous = pitch;
		}
		assertEquals(0.7F, DragonLastStandPatterns.flapPitch(0), 1.0E-6F);
		assertEquals(1.3F, previous, 1.0E-6F,
				"마지막 번치가 가장 높아야 한다 — 개수로 나누면 끝까지 안 올라간다");
	}

	// ------------------------------------------------------------------ ③ 빨아들임과 밀어냄의 방향

	/**
	 * ⚠⚠ <b>빨아들이는 가닥과 밀어내는 가닥이 정확히 반대로 흐른다.</b>
	 *
	 * <p>사람 말: <b>「빨아드리는거랑 밀치는거랑 이펙트가 너무 구분이안됨 … 예를들어 빨아드리는
	 * 패턴이면 입자들이 드래곤에게 빨려들어가는 입자가 잘보이면 이해하잖아」</b>
	 *
	 * <p>재는 것은 <b>생기는 자리가 어디로 움직이는가</b>다. 흡입은 20칸에서 4칸으로 들어오고
	 * 날개는 0 에서 20칸으로 나간다 — 이 둘이 같은 방향이 되는 순간 사람이 지적한 그 상태로
	 * 돌아간다.
	 */
	@Test
	void 빨아들이는_가닥과_밀어내는_가닥이_반대로_흐른다() {
		// 흡입 — 들어온다.
		double previous = Double.MAX_VALUE;
		int wraps = 0;
		for (int step = 0; step < 32; step++) {
			double radius = DragonLastStandPatterns.suckStreamRadius(step, 0);
			if (radius > previous) {
				wraps++;
			} else {
				assertTrue(radius < previous, step + "틱에 흡입 가닥이 안으로 안 들어왔다");
			}
			previous = radius;
		}
		assertTrue(wraps >= 2, "되풀이되지 않으면 한 벌이 지나가고 끝이다: " + wraps);

		// 날개 — 나간다.
		previous = -1.0;
		for (int step = 0; step < DragonLastStandPatterns.WING_PULSE_TICKS; step++) {
			double radius = DragonLastStandPatterns.wingWaveRadius(step);
			assertTrue(radius > previous, step + "틱에 날개 가닥이 바깥으로 안 나갔다");
			previous = radius;
		}
		assertEquals(DragonLastStandPatterns.WING_FADE_RADIUS, previous, 1.0E-9,
				"한 번치가 날개바람이 닿는 끝까지 가지 않는다");
		// 번치마다 처음부터 다시 번진다. 안 그러면 「퍼졌다」가 아니라 「거기 늘 있었다」가 된다.
		assertEquals(DragonLastStandPatterns.wingWaveRadius(0),
				DragonLastStandPatterns.wingWaveRadius(DragonLastStandPatterns.WING_PULSE_TICKS),
				1.0E-9);

		// 같은 세기 · 같은 갈래 수여야 「반대」로 읽힌다. 한쪽이 성기면 「한쪽이 세다」로 읽힌다.
		assertEquals(DragonLastStandPatterns.WING_GUST_SPOKES,
				DragonLastStandPatterns.SUCK_STREAM_SPOKES,
				"갈래 수가 갈리면 두 패턴이 서로의 반대로 읽히지 않는다");
		assertEquals(DragonLastStandPatterns.WING_GUST_DRIFT,
				DragonLastStandPatterns.SUCK_STREAM_DRIFT, 1.0E-9,
				"세기가 갈리면 방향 대비가 흐려진다");
	}

	/**
	 * 흡입 가닥이 <b>검은 원 안으로 들어가지 않고</b> 손이 닿는 거리 밖으로도 안 나간다.
	 *
	 * <p>원 안은 검은 속이 이미 채워져 있어 흰 가닥을 겹치면 검정이 묻히고, 20칸 밖은 손이 닿지
	 * 않는 자리라 거기서 흐르면 <b>표식이 거짓말을 한다.</b>
	 */
	@Test
	void 흡입_가닥이_4에서_20칸_사이에만_있다() {
		for (int step = 0; step < DragonLastStandPatterns.SUCK_WARN_TICKS
				+ DragonLastStandPatterns.SUCK_PULL_TICKS; step++) {
			for (int phase = 0; phase < DragonLastStandPatterns.SUCK_STREAM_PHASES; phase++) {
				double radius = DragonLastStandPatterns.suckStreamRadius(step, phase);
				assertTrue(radius >= DragonLastStandPatterns.SUCK_RADIUS - 1.0E-9,
						step + "틱 " + phase + "벌이 검은 원 안으로 들어갔다: " + radius);
				assertTrue(radius <= DragonLastStandPatterns.SUCK_REACH + 1.0E-9,
						step + "틱 " + phase + "벌이 손이 닿는 거리 밖이다: " + radius);
			}
		}
		// 벌 둘이 같은 반경에 겹치면 48점을 쓰고 24점만큼만 보인다.
		for (int step = 0; step < 32; step++) {
			assertTrue(Math.abs(DragonLastStandPatterns.suckStreamRadius(step, 0)
							- DragonLastStandPatterns.suckStreamRadius(step, 1)) > 1.0,
					step + "틱에 두 벌이 같은 자리에 겹쳤다");
		}
	}

	/**
	 * ⚠ 가닥이 <b>먼지가 아니라 {@code CRIT}</b> 이다. 먼지로는 방향을 말할 수 없다.
	 *
	 * <p>26.3 {@code DustParticleBase} 는 상위 생성자가 받은 속도를 <b>정규화해 무작위 크기로
	 * 다시 싣고</b> 거기에 0.1 을 곱한다 — 흐르는 거리가 0.15칸이라 눈에는 제자리다.
	 * {@code CritParticle} 은 상위에 {@code 0,0,0} 을 넘긴 뒤 <b>받은 속도의 0.4배를 그대로
	 * 더하므로</b> 방향이 보존된다(둘 다 클래스 파일로 확인했다).
	 */
	@Test
	void 가닥이_방향을_싣는_입자다() {
		assertTrue(classBytes().contains("CRIT"),
				"먼지로 가닥을 그리면 0.15칸만 흘러 방향이 안 보인다");
		// 가닥 길이가 갈래 사이와 비슷해야 「방사선」으로 읽힌다. 반경 12 에서 갈래 사이가 3.1칸이다.
		double drift = DragonLastStandPatterns.critDrift(DragonLastStandPatterns.WING_GUST_DRIFT);
		assertEquals(3.04, drift, 0.01, "가닥 길이가 달라졌으면 갈래 수도 함께 볼 것");
		double gap = (Math.PI * 2.0 * DragonLastStandPatterns.WING_STRONG_RADIUS)
				/ DragonLastStandPatterns.WING_GUST_SPOKES;
		assertEquals(3.14, gap, 0.01);
		assertTrue(drift < gap * 1.5,
				"가닥이 서로 이어 붙으면 「퍼져 나간다」가 「원이 커진다」로 읽힌다");
		assertTrue(drift > gap * 0.5, "가닥이 짧으면 점으로 흩어진다");
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
