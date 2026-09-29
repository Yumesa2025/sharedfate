package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「착지 충격」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>고리를 실제로 퍼뜨리려면 {@code ServerLevel} 과 {@code EnderDragon} 이 있어야 해서 여기서
 * 굴려 볼 수 없다. 그래도 이 카드가 망가지는 길은 거의 전부 여기서 잡힌다 —
 * <b>앉아 있는 내내 터진다</b>(초당 스무 번), <b>섬 밖으로 민다</b>(공허 낙사 = 월드 삭제),
 * <b>뛰었는데 맞는다</b>, <b>넷이 다 맞으면 죽는다</b>. 전멸하면 월드가 지워지는 게임이라
 * 이것들은 실제로 굴려 보고 발견할 수 없다.
 *
 * <p>여기서 지키는 것이 하나 더 있다 — <b>「엔더 파동」과 같은 고리여야 한다.</b> 두 카드가 같은
 * 동작을 다른 타이밍으로 가르치면 사람이 배운 것이 운이 된다.
 *
 * <p>그리고 <b>드래곤의 페이즈를 건드리지도 읽지도 않는다.</b> 하필 이 카드의 발동 조건이
 * 착지라, 그 사고를 다시 내면 카드가 스스로를 끈다.
 */
class TrialLandingShockTest {

	/** 「착지 충격」 카드의 값. 실제 카드·문서와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int TRAVEL = 40;
	private static final double MAX_RADIUS = 15.0;
	private static final float DAMAGE = 4.0F;
	private static final double KNOCKBACK = 8.0;
	/** 팀 인원 상한. 공유 체력에서 범위 피해는 팀원별로 합산된다. */
	private static final int TEAM = 4;

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID GONE = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

	// ------------------------------------------------------------------ 엔더 파동과 같은 고리다

	/**
	 * <b>판정에 쓰는 것을 새로 만들지 않았다.</b>
	 *
	 * <p>같은 숫자를 두 파일에 적어 두면 한쪽만 고쳐지고, 그때 어긋나는 것은 숫자가 아니라
	 * <b>사람이 두 카드에서 배운 타이밍</b>이다.
	 */
	@Test
	void 점프_창은_엔더_파동의_것을_그대로_쓴다() {
		assertEquals(TrialEnderPulse.JUMP_WINDOW_TICKS, TrialLandingShock.JUMP_WINDOW_TICKS,
				"두 고리가 다른 창을 쓰면 한 카드에서 통하던 점프가 다른 카드에서 안 통한다");
		assertEquals(5, TrialLandingShock.JUMP_WINDOW_TICKS,
				"0.25초 — TrialWarning.TICKS_SIDESTEP 의 「지각·판단·입력」 시간이다");
	}

	/**
	 * 고리 수명이 <b>앞머리가 끝까지 간 뒤에도 창만큼</b> 남는다.
	 *
	 * <p>깎으면 가장 바깥에 선 사람만 창을 못 받아, 그 사람에게만 옛날의 한 틱짜리 판정이
	 * 적용된다.
	 */
	@Test
	void 고리는_앞머리가_멈춘_뒤에도_창만큼_산다() {
		assertEquals(TRAVEL + TrialLandingShock.JUMP_WINDOW_TICKS,
				TrialEnderPulse.lifetime(0, TRAVEL),
				"주기가 없는 카드라 간격 자리에 0 을 넣는다 — 퍼지는 시간 + 창이 나와야 한다");
	}

	/**
	 * 반열린 구간이 <b>어느 거리든 정확히 한 번</b> 덮는다.
	 *
	 * <p>「반경 == 거리」로 물으면 고리가 한 틱에 0.375칸씩 건너뛰어 대부분의 사람이 그냥
	 * 빠지고, 구간이 겹치면 한 사람이 같은 고리에 두 번 맞는다.
	 */
	@Test
	void 어느_거리든_정확히_한_번_지나간다() {
		int life = TrialEnderPulse.lifetime(0, TRAVEL);
		for (int tenth = 0; tenth <= (int) (MAX_RADIUS * 20); tenth++) {
			double distance = tenth / 20.0;
			int crossings = 0;
			for (int step = 0; step <= life; step++) {
				double outer = TrialEnderPulse.judgeRadius(step, TRAVEL, MAX_RADIUS);
				double inner = TrialEnderPulse.judgeRadius(step - 1, TRAVEL, MAX_RADIUS);
				if (distance > inner && distance <= outer) {
					crossings++;
				}
			}
			assertEquals(1, crossings, "거리 " + distance + " 가 " + crossings + "번 걸렸다");
		}
	}

	/**
	 * 바깥으로 도망쳐서는 못 피한다 — 그래서 대응이 「뛰기」 하나다.
	 *
	 * <p>여기가 뒤집히면 카드가 요구하는 행동이 둘이 되고, 그때는 예고 시간을 다시 봐야 한다.
	 */
	@Test
	void 고리가_달리기보다_빠르다() {
		double perTick = TrialEnderPulse.radiusAt(1, TRAVEL, MAX_RADIUS);
		assertEquals(0.375, perTick, 1.0E-9, "15칸을 40틱에 — 초당 7.5칸이다");
		double sprint = 5.612 / 20.0;
		assertTrue(perTick > sprint,
				"고리가 " + perTick + " 칸/틱인데 달리기가 " + sprint + " 다 — 도망칠 수 있으면"
						+ " 이 카드의 대응이 「뛰기」 하나가 아니게 된다");
	}

	@Test
	void 가장_먼_사람도_퍼지는_시간만큼_본다() {
		assertEquals(TRAVEL, TrialEnderPulse.reachTick(MAX_RADIUS, TRAVEL, MAX_RADIUS),
				"고리 끝에 선 사람의 예고가 곧 퍼지는 시간이다");
	}

	// ------------------------------------------------------------------ 「점프해 있다」

	@Test
	void 한_틱_차이로_억울하게_걸리지_않는다() {
		TrialLandingShock.clearState();
		long jumped = 1000L;
		TrialLandingShock.noteAirborne(A, jumped);
		assertTrue(TrialLandingShock.jumpedThrough(A, jumped), "지금 떠 있다");
		for (int late = 1; late <= TrialLandingShock.JUMP_WINDOW_TICKS; late++) {
			assertTrue(TrialLandingShock.jumpedThrough(A, jumped + late),
					late + "틱 늦게 판정이 내려졌다 — 고리 두께 안이면 통과다");
		}
		assertFalse(
				TrialLandingShock.jumpedThrough(A, jumped + TrialLandingShock.JUMP_WINDOW_TICKS + 1),
				"창이 길어지면 착지한 순간까지 공짜가 되어 점프가 위험을 지우지 못한다");
		assertFalse(TrialLandingShock.jumpedThrough(GONE, jumped),
				"이 카드가 붙은 뒤로 한 번도 안 뛴 사람은 걸린다");
		TrialLandingShock.clearState();
		assertFalse(TrialLandingShock.jumpedThrough(A, jumped),
				"판이 바뀌면 지난 판에 뛰어 있던 기록이 첫 고리를 통과시키면 안 된다");
	}

	@Test
	void 창은_점프_한_번보다_짧다() {
		// 바닐라 점프는 처음 속도 0.42, 중력 0.08 이라 체공이 열 틱을 넘는다. 창이 그만큼 길면
		// 「뛰었다 내렸다」를 반복하는 동안 늘 통과가 되어 이 카드가 아무 일도 안 하게 된다.
		int jumpAirTicks = (int) Math.round(2.0 * 0.42 / 0.08);
		assertTrue(TrialLandingShock.JUMP_WINDOW_TICKS < jumpAirTicks,
				"창(" + TrialLandingShock.JUMP_WINDOW_TICKS + ")이 점프 체공(" + jumpAirTicks
						+ ")보다 길면 뛴 적 없는 사람까지 통과한다");
	}

	// ------------------------------------------------------------------ 드래곤을 건드리지 않는다

	/**
	 * <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>앞선 작업에서 드래곤을 돌진 페이즈로 밀어넣었다가 <b>드래곤이 착지를 아예 하지 않게</b>
	 * 됐다. 이 카드는 <b>착지가 곧 발동 조건</b>이라 같은 실수를 하면 카드가 한 번도 안 터진다.
	 *
	 * <p>읽는 것까지 막는 이유는 「이미 페이즈를 보고 있으니 한 줄만 더」가 시작되는 자리를
	 * 없애려는 것이다.
	 */
	@Test
	void 드래곤의_페이즈를_부르지도_읽지도_않는다() {
		String bytes = classBytes();
		for (String banned : new String[] {"setPhase", "getPhaseManager", "getCurrentPhase",
				"EnderDragonPhase", "setTarget", "isSitting"}) {
			assertFalse(bytes.contains(banned),
					banned + " 이 상수 풀에 있다 — 드래곤의 상태에 손이 닿았다."
							+ " 착지를 멈춘 사고가 그렇게 났고, 이 카드는 그 착지로 발동한다");
		}
	}

	@Test
	void 드래곤에게서는_좌표만_읽는다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("getFightOrigin"),
				"착지 지점을 포탈에서 찾지 않으면 중앙을 (0,0) 으로 박게 된다");
		assertTrue(bytes.contains("getPodiumLocation"),
				"바닐라가 착지 목표로 삼는 그 점을 쓰지 않으면 판정이 바닐라와 어긋난다");
		// 이름만 본다. 상수 풀은 이름과 서술자를 따로 담으므로 「이름(서술자)」을 이어 붙여
		// 찾으면 언제나 통과한다 — 그러면 시험이 아무것도 안 지킨다.
		for (String moves : new String[] {"setPos", "addFreshEntity", "setHealth",
				"getSubEntities", "syncPosition"}) {
			assertFalse(bytes.contains(moves), moves + " — 드래곤을 옮기거나 바꾸고 있다");
		}
	}

	@Test
	void 블록을_건드리지_않는다() {
		String bytes = classBytes();
		for (String breaks : new String[] {"removeBlock", "destroyBlock", "setBlockAndUpdate"}) {
			assertFalse(bytes.contains(breaks),
					breaks + " — 엔드 섬에 구멍이 하나 뚫리면 다음 전투부터 발판이 달라진다");
		}
	}

	@Test
	void 월드_시각을_직접_읽지_않는다() {
		// 시련 화면이 떠 판이 얼어붙는 동안 게임 시각은 흐르지 않는다. 넘겨받은 now 와 섞어 쓰면
		// 고리가 그 자리에 멈추거나 한꺼번에 건너뛴다.
		assertFalse(classBytes().contains("getGameTime"),
				"level.getGameTime() 을 쓰고 있다 — 얼어붙은 판에서 고리가 어긋난다");
	}

	// ------------------------------------------------------------------ 착지를 한 번만 잡는다

	@Test
	void 포탈_위에_멈춰_있어야_착지다() {
		Vec3 podium = new Vec3(0.5, 64.0, 0.5);
		Vec3 seat = new Vec3(0.5, 64.3, 0.5);
		assertTrue(TrialLandingShock.landed(seat, seat, podium),
				"포탈 꼭대기 1칸 안에서 한 틱도 안 움직였다 — 바닐라가 앉기로 넘어간 그 상태다");

		// 돌진. 포탈 위를 스쳐 지나가는 중이라 좌표가 매 틱 바뀐다.
		Vec3 diving = new Vec3(0.5, 64.3, 0.5);
		Vec3 before = new Vec3(0.5, 66.0, 0.5);
		assertFalse(TrialLandingShock.landed(diving, before, podium),
				"움직이고 있는데 착지로 읽으면 포탈에 선 사람에게 달려들 때마다 터진다");

		// 「부활」이 중앙 상공에 붙들어 둔 경우. 멈춰 있지만 포탈 위가 아니다.
		Vec3 hovering = new Vec3(0.5, 100.0, 0.5);
		assertFalse(TrialLandingShock.landed(hovering, hovering, podium),
				"중앙 상공에 멈춰 있는 것은 착지가 아니다");
	}

	/**
	 * 앉아 있는 내내 참인 조건을 그대로 쓰면 초당 스무 번 터진다.
	 *
	 * <p>피해 4 × 20 = 80 이고 팀 공유 체력은 20 이다. 이 시험이 그 사고를 막는 자리다.
	 */
	@Test
	void 한_번의_착지에_고리는_한_번이다() {
		assertFalse(TrialLandingShock.touchdown(null, true),
				"첫 판단에 터지면 이미 앉아 있는 드래곤 앞에서 카드를 받은 판이 받자마자 터진다");
		assertTrue(TrialLandingShock.touchdown(false, true), "내려앉는 순간이 가장자리다");
		assertFalse(TrialLandingShock.touchdown(true, true), "앉아 있는 동안은 두 번째부터 조용하다");
		assertFalse(TrialLandingShock.touchdown(true, false), "일어서는 순간에는 아무 일도 없다");
		assertFalse(TrialLandingShock.touchdown(false, false));

		// 앉았다 → 날았다 → 다시 앉았다. 두 번째 착지는 다시 잡혀야 한다.
		Boolean state = null;
		int fired = 0;
		for (boolean down : new boolean[] {false, true, true, true, false, false, true, true}) {
			if (TrialLandingShock.touchdown(state, down)) {
				fired++;
			}
			state = down;
		}
		assertEquals(2, fired, "착지 두 번에 고리 둘이어야 한다");
	}

	// ------------------------------------------------------------------ 섬 밖으로 못 나간다

	/**
	 * ⚠ 이 카드가 「강한 넉백 금지」의 예외로 허용된 근거 그 자체다.
	 *
	 * <p>고리 반경 15 + 미는 거리 8 = 23 이 섬 반경 40 안이라 <b>허공에 닿을 수가 없다.</b>
	 * 둘 중 하나만 올려도 이 조건이 깨지므로 카드 값에서 직접 센다.
	 */
	@Test
	void 고리_끝에서_밀려도_섬_안이다() {
		TrialCatalog.Risk.LandingShock card = card();
		double furthest = card.maxRadius() + card.knockback();
		assertEquals(23.0, furthest, 1.0E-9, "15 + 8 = 23");
		assertTrue(furthest < TrialRisks.ARENA_RADIUS,
				"가장 멀리 밀려도 " + furthest + " 칸인데 섬 반경이 " + TrialRisks.ARENA_RADIUS
						+ " 다. 넘기는 순간 이 카드는 「강한 넉백 금지」의 예외 자격을 잃는다 —"
						+ " 반경이나 넉백 중 하나만 올려도 그렇게 된다");
		assertEquals(furthest, TrialLandingShock.outwardLimit(card.maxRadius(), card.knockback()),
				1.0E-9, "천장이 산수와 다르면 둘 중 하나가 거짓말이다");
	}

	@Test
	void 천장_밖으로는_한_칸도_밀지_않는다() {
		double limit = TrialLandingShock.outwardLimit(MAX_RADIUS, KNOCKBACK);
		// 고리 안쪽에서 맞은 사람은 적힌 만큼 밀린다.
		assertEquals(KNOCKBACK, TrialLandingShock.pushDistance(5.0, KNOCKBACK, limit), 1.0E-9);
		// 천장에 가까우면 남은 만큼만.
		assertEquals(3.0, TrialLandingShock.pushDistance(limit - 3.0, KNOCKBACK, limit), 1.0E-9);
		// 이미 천장 밖이다 — 다른 카드의 넉백이 먼저 데려다 놓은 경우다.
		assertEquals(0.0, TrialLandingShock.pushDistance(limit, KNOCKBACK, limit));
		assertEquals(0.0, TrialLandingShock.pushDistance(limit + 10.0, KNOCKBACK, limit),
				"바깥에 있는 사람을 더 밀면 이 카드가 남의 사고를 완성시킨다");

		for (int step = 0; step <= 240; step++) {
			double from = step / 4.0;
			double after = from + TrialLandingShock.pushDistance(from, KNOCKBACK, limit);
			assertTrue(after <= Math.max(from, limit) + 1.0E-9,
					from + " 칸에 서 있던 사람이 " + after + " 칸까지 밀렸다");
		}
	}

	@Test
	void 천장은_섬_반경도_함께_문다() {
		// 다음에 값을 만질 사람이 합을 섬 밖으로 올려도, 최소한 여기서 잘린다. 그때는 카드가
		// 이미 제 근거를 잃은 상태이므로 값을 되돌려야 한다 — 이 함수에 기대는 것이 아니다.
		assertEquals(TrialRisks.ARENA_RADIUS, TrialLandingShock.outwardLimit(60.0, 20.0));
		assertEquals(0.0, TrialLandingShock.outwardLimit(-5.0, -5.0));
	}

	/**
	 * 미는 속도를 <b>공중</b> 감쇠로 잡는다 — 그것이 8칸을 천장으로 만든다.
	 *
	 * <p>바닥 마찰(0.546)로 잡으면 같은 8칸에 3.6 칸/틱이 필요한데, 그 속도로 한 틱이라도
	 * 떠 있으면 40칸을 날아 섬 밖이다.
	 */
	@Test
	void 미는_속도는_공중_모델로_잡는다() {
		double speed = TrialLandingShock.pushVelocity(KNOCKBACK);
		assertEquals(0.72, speed, 1.0E-9, "8 × (1 - 0.91)");

		double airborneTravel = speed / (1.0 - TrialLandingShock.AIR_DRAG);
		assertEquals(KNOCKBACK, airborneTravel, 1.0E-9,
				"떠 있는 채로 끝까지 밀려도 적힌 거리에서 멈춰야 한다 — 그것이 천장의 뜻이다");

		double groundTravel = speed / (1.0 - 0.6 * TrialLandingShock.AIR_DRAG);
		assertTrue(groundTravel < KNOCKBACK,
				"바닥에서는 마찰이 먼저 먹는다. 실제로 밀리는 거리는 " + groundTravel
						+ " 칸이라 적힌 8칸보다 짧다 — 어긋나는 방향이 반드시 이쪽이어야 한다");
		assertEquals(0.0, TrialLandingShock.pushVelocity(-3.0), "음수 거리는 밀지 않는다");
	}

	@Test
	void 넉백을_주는_피해원을_쓰지_않는다() {
		// 실체가 붙은 피해원이면 LivingEntity 가 스스로 밀어내는데, 그 밀기는 우리가 정한
		// 방향도 거리도 아니다 — 위의 천장이 그 한 줄로 무너진다.
		String bytes = classBytes();
		assertTrue(bytes.contains("explosion"), "피해를 넣는 자리가 사라졌다");
		for (String pushes : new String[] {"mobAttack", "playerAttack", "mobProjectile",
				"indirectMagic", "(DDDLnet/minecraft/world/damagesource/DamageSource;FZ)V",
				"(DDDLnet/minecraft/world/damagesource/DamageSource;F)V"}) {
			assertFalse(bytes.contains(pushes),
					pushes + " 은 바닐라가 제멋대로 밀어내는 길이다 — 미는 거리는 이 카드가 정한다");
		}
		assertTrue(bytes.contains("syncVelocity"),
				"속도를 내려보내지 않으면 서버 혼자 민 것이 되어 제자리로 되돌아간다");
	}

	@Test
	void 위로_띄우지_않는다() {
		// 띄우면 공중 감쇠(0.91)가 바닥 마찰(0.546) 자리에 들어와 같은 속도로도 몇 배 멀리
		// 간다. 낙하 피해를 면제할 상태를 따로 들어야 하는 것은 덤이다.
		assertFalse(classBytes().contains("resetFallDistance"),
				"낙하 거리를 지우고 있다 — 사람을 띄우기 시작했다는 뜻이다");
	}

	// ------------------------------------------------------------------ 전원을 때린다

	/**
	 * 넷이 다 맞아도 죽지 않는다 — <b>그것이 전원 타격을 고른 값의 근거</b>다.
	 *
	 * <p>「연쇄 포격」이 팀에 한 번만 넣는 것은 거기서는 <b>모여 있는 것이 올바른 대응</b>이라
	 * 모두 때리면 올바른 대응이 전멸이 되기 때문이다. 이 카드는 대응이 「뛰기」라 넷이 붙어
	 * 있어도 넷 다 뛰면 0 이고, 팀에 한 번만 넣으면 한 사람만 안 뛰어도 같은 4 라 회피를
	 * 가르치지 못한다.
	 */
	@Test
	void 넷이_다_맞아도_팀이_죽지_않는다() {
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		float worst = TrialLandingShock.worstCaseTeamDamage(DAMAGE, TEAM);
		assertEquals(16.0F, worst, "4 × 4");
		assertTrue(worst < teamHealth,
				"넷 전원이 실패하면 " + worst + " 인데 팀 공유 체력이 " + teamHealth + " 다."
						+ " 넘기는 순간 전원 타격이 즉사 카드가 된다 — 피해를 올리려면 먼저"
						+ " 팀에 한 번만 넣는 쪽으로 바꿔야 한다");
		assertEquals(0.0F, TrialLandingShock.worstCaseTeamDamage(DAMAGE, 0));
		assertEquals(0.0F, TrialLandingShock.worstCaseTeamDamage(0.0F, TEAM));
	}

	@Test
	void 한_사람이_받는_몫은_한_번이다() {
		// 고리는 한 사람을 한 번만 지나간다. TrialRisks 가 이 위험을 hits(damage, 1) 로 세는
		// 것이 그 이야기이므로 둘이 어긋나면 안 된다.
		assertEquals(DAMAGE, TrialRisks.worstCaseTickDamage(card()),
				"한 사람 몫이 카드에 적힌 피해와 달라졌다");
	}

	// ------------------------------------------------------------------ 보이고 들린다

	@Test
	void 고리는_긴_형태로_나간다() {
		// 고리는 반경 15 라 지름으로 30칸이 넘는다. 짧은 형태는 32칸에서 잘려, 반대편에 선
		// 팀원에게는 아무 일도 안 일어나는 것으로 보인다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 빌드도 로그도 조용한 채로 고리가 사라진다");
	}

	/**
	 * 앞머리와 몸통을 갈랐다.
	 *
	 * <p>26.3 먼지 입자는 수명이 8~40틱이라, 파랑 먼지만으로 그리면 자국이 3~15칸 남아
	 * <b>고리가 아니라 퍼지는 원판</b>으로 보인다. 앞머리가 어디인지 안 읽히면 언제 뛰어야
	 * 하는지도 안 읽힌다. 「엔더 파동」이 같은 함정에서 고른 답을 그대로 쓴다.
	 */
	@Test
	void 앞머리를_짧은_수명_입자로_가른다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("CRIT"),
				"앞머리를 가르지 않았다 — 먼지 수명(8~40틱)이 고리를 원판으로 만든다");
		assertTrue(bytes.contains("edgePoints") && bytes.contains("wakePoints"),
				"점 수를 엔더 파동에서 가져오지 않았다 — 두 고리의 문법이 갈라진다");
		assertTrue(TrialLandingShock.JUMP_WINDOW_TICKS < 8,
				"먼지 수명 하한(8틱)이 판정 창보다 길어야 뒷자락까지 파랑으로 덮인다");
	}

	@Test
	void 경고_소리는_공용_경고를_쓴다() {
		assertTrue(classBytes().contains("(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/phys/Vec3;Lcom/sharedfate/sync/TrialWarning$Stage;)V"),
				"자막을 걷어낸 뒤로 소리는 「무엇이 언제 오는가」를 말하는 두 갈래 중 하나다");
	}

	@Test
	void 색은_규약에_있는_파랑이다() {
		assertEquals(TrialWarning.Colors.SHOVE, TrialLandingShock.markColor(),
				"이 카드는 「서 있으면 맞고 밀려난다」라 파랑이 그 뜻 그대로다");
		assertNotEquals(TrialWarning.Colors.DEADLY, TrialLandingShock.markColor(),
				"빨강은 자리 폭격·기둥 화염구·연쇄 포격이 쓰고 있다 — 섞이면 무엇이 오는지 안 읽힌다");
		assertNotEquals(TrialWarning.Colors.LIGHTNING, TrialLandingShock.markColor(),
				"노랑은 번개다");
		assertNotEquals(TrialWarning.Colors.MARKED, TrialLandingShock.markColor(),
				"보라는 「너 하나를 노린다」다");
	}

	@Test
	void 한_틱_점_예산_안이다() {
		int points = TrialLandingShock.markPoints(MAX_RADIUS);
		assertTrue(points <= 440,
				"한 틱에 " + points + " 점이 나간다. 이 판이 쓰는 예산은 400~440 이다");
		assertTrue(points > TrialWarning.MAX_POINTS,
				"이 카드의 고리는 반드시 읽혀야 하는 한 줄이라 공용 상한(" + TrialWarning.MAX_POINTS
						+ ")보다 촘촘하게 준다");
	}

	// ------------------------------------------------------------------ 카드와 문서

	@Test
	void 카드_값이_문서와_같다() {
		TrialCatalog.Risk.LandingShock card = card();
		assertEquals(TRAVEL, card.travelTicks());
		assertEquals(MAX_RADIUS, card.maxRadius());
		assertEquals(DAMAGE, card.damage());
		assertEquals(KNOCKBACK, card.knockback());
	}

	// ------------------------------------------------------------------ 도우미

	private static TrialCatalog.Risk.LandingShock card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:landing_shock");
		assertNotNull(trial, "「착지 충격」 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.LandingShock shock) {
				return shock;
			}
		}
		throw new AssertionError("「착지 충격」 카드에 LandingShock 이 없다");
	}

	/** 컴파일된 우리 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String classBytes() {
		try (InputStream in = TrialLandingShock.class
				.getResourceAsStream("/com/sharedfate/sync/TrialLandingShock.class")) {
			if (in == null) {
				return fail("TrialLandingShock 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
