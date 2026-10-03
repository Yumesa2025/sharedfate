package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
 * <b>뛰었는데 맞는다</b>, <b>고리 셋이 한 사람 몫을 한 번으로 세거나 셋 다 세 배로 센다</b>.
 * 전멸하면 월드가 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 *
 * <p>여기서 지키는 것이 하나 더 있다 — <b>「엔더 파동」과 같은 고리여야 한다.</b> 두 카드가 같은
 * 동작을 다른 타이밍으로 가르치면 사람이 배운 것이 운이 된다.
 *
 * <p>그리고 <b>드래곤의 페이즈를 건드리지도 읽지도 않는다.</b> 하필 이 카드의 발동 조건이
 * 착지라, 그 사고를 다시 내면 카드가 스스로를 끈다.
 */
class TrialLandingShockTest {

	/** 「착지 충격」 카드의 값. 실제 카드·문서와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int TRAVEL = 150;
	private static final double MAX_RADIUS = 75.0;
	private static final float DAMAGE = 4.0F;
	private static final double KNOCKBACK = 16.0;
	private static final int RINGS = 3;
	private static final int RING_INTERVAL = 40;
	/** 팀 인원 상한. 공유 체력에서 범위 피해는 팀원별로 합산된다. */
	private static final int TEAM = 4;
	/**
	 * 중앙 섬이 끝나는 자리. 흑요석 기둥이 서 있는 원이다.
	 *
	 * <p>고리는 75칸까지 가지만 그 바깥은 허공이라 실제로 그려지지도 사람이 서 있지도 않는다.
	 * 점 예산을 묻는 시험이 보는 것이 「섬 위에 몇 개가 있는가」라 여기가 그 경계다.
	 */
	private static final double ISLAND_RADIUS = 42.0;

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
	 * 고리 하나의 수명이 <b>앞머리가 끝까지 간 뒤에도 창만큼</b> 남는다.
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
	 * <p>「반경 == 거리」로 물으면 고리가 한 틱에 0.5칸씩 건너뛰어 대부분의 사람이 그냥 빠지고,
	 * 구간이 겹치면 한 사람이 같은 고리에 두 번 맞는다.
	 */
	@Test
	void 어느_거리든_정확히_한_번_지나간다() {
		int life = TrialEnderPulse.lifetime(0, TRAVEL);
		int samples = 3000;
		for (int index = 0; index <= samples; index++) {
			double distance = index * MAX_RADIUS / samples;
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
		assertEquals(0.5, perTick, 1.0E-9, "75칸을 150틱에 — 초당 10칸이다");
		double sprint = 5.612 / 20.0;
		assertTrue(perTick > sprint,
				"고리가 " + perTick + " 칸/틱인데 달리기가 " + sprint + " 다 — 도망칠 수 있으면"
						+ " 이 카드의 대응이 「뛰기」 하나가 아니게 된다");
	}

	/**
	 * 고리 속도를 「엔더 파동」에 맞춰 두었다.
	 *
	 * <p>두 카드는 그리기와 판정을 같은 함수로 나눠 쓴다. 속도가 어긋나면 <b>같은 5틱 창이
	 * 서로 다른 두께</b>가 되어, 한쪽에서 통하던 점프 타이밍이 다른 쪽에서 안 통한다.
	 */
	@Test
	void 고리_두께가_엔더_파동과_비슷하다() {
		double here = TrialEnderPulse.radiusAt(TrialEnderPulse.JUMP_WINDOW_TICKS, TRAVEL,
				MAX_RADIUS);
		TrialCatalog.Risk.EnderPulse pulse = pulseCard();
		double there = TrialEnderPulse.radiusAt(TrialEnderPulse.JUMP_WINDOW_TICKS,
				pulse.travelTicks(), pulse.maxRadius());
		assertTrue(Math.abs(here - there) < 0.5,
				"착지 충격의 고리 두께가 " + here + "칸이고 엔더 파동은 " + there + "칸이다 —"
						+ " 벌어지면 사람이 「고리는 뛰면 피한다」를 두 번 배워야 한다");
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

	// ------------------------------------------------------------------ 고리가 셋이다

	/**
	 * 고리마다 나이를 따로 센다.
	 *
	 * <p>착지 한 번에 고리가 셋 나가는데 시계가 하나면 셋이 같은 반경으로 겹쳐 그려지고, 한
	 * 사람이 한 틱에 세 번 맞는다.
	 */
	@Test
	void 고리마다_제_나이가_따로_간다() {
		assertEquals(0, TrialLandingShock.stepOf(0L, 0, RING_INTERVAL), "첫 고리는 바로 출발한다");
		assertEquals(-40, TrialLandingShock.stepOf(0L, 1, RING_INTERVAL),
				"둘째 고리는 아직 안 태어났다 — 음수여야 그 틱에 아무 일도 안 한다");
		assertEquals(0, TrialLandingShock.stepOf(40L, 1, RING_INTERVAL), "2초 뒤에 둘째가 출발한다");
		assertEquals(0, TrialLandingShock.stepOf(80L, 2, RING_INTERVAL), "4초 뒤에 셋째가 출발한다");
		assertEquals(80, TrialLandingShock.stepOf(80L, 0, RING_INTERVAL));

		// 사람이 정한 간격이 2초다. 고리 사이가 좁아지면 어느 고리를 보고 뛰는지가 안 읽힌다.
		assertEquals(40, RING_INTERVAL, "2초");
		assertEquals(20.0, RING_INTERVAL * TrialEnderPulse.radiusAt(1, TRAVEL, MAX_RADIUS), 1.0E-9,
				"고리 사이가 20칸이다 — 좁아지면 앞 고리의 자국이 뒤 고리를 삼킨다");
	}

	/**
	 * 고리 셋이 정말 동시에 떠 있다 — 그래서 명단을 따로 들어야 한다.
	 *
	 * <p>하나로 합치면 첫 고리를 맞은 사람이 두 번째·세 번째 고리를 그냥 통과한다. 사람이
	 * 「고리가 3번」을 요구한 뜻이 그것이면 카드가 시킨 일을 안 하는 것이다.
	 */
	@Test
	void 고리_셋이_겹쳐_난다() {
		int life = TrialEnderPulse.lifetime(0, TRAVEL);
		int mostAtOnce = 0;
		for (long age = 0; age <= TrialLandingShock.lastTick(RINGS, RING_INTERVAL, life); age++) {
			int alive = 0;
			for (int ring = 0; ring < RINGS; ring++) {
				int step = TrialLandingShock.stepOf(age, ring, RING_INTERVAL);
				if (step >= 0 && step <= life) {
					alive++;
				}
			}
			mostAtOnce = Math.max(mostAtOnce, alive);
		}
		assertEquals(RINGS, mostAtOnce,
				"셋이 함께 떠 있는 순간이 없으면 명단을 따로 들 까닭도 없다 — 값이 그렇게"
						+ " 바뀌었으면 이 시험이 아니라 코드를 단순하게 되돌릴 자리다");
	}

	@Test
	void 마지막_고리까지_다_지나가야_벌이_끝난다() {
		int life = TrialEnderPulse.lifetime(0, TRAVEL);
		long last = TrialLandingShock.lastTick(RINGS, RING_INTERVAL, life);
		assertEquals((long) (RINGS - 1) * RING_INTERVAL + life, last, "80 + 155");
		assertEquals(life, TrialLandingShock.stepOf(last, RINGS - 1, RING_INTERVAL),
				"벌이 끝나는 틱이 곧 마지막 고리의 마지막 틱이어야 한다 — 짧으면 셋째 고리의"
						+ " 뒷자락이 잘려 가장 바깥 사람이 판정을 안 받는다");
	}

	@Test
	void 값이_망가져도_고리가_한_틱에_겹치지_않는다() {
		// 0 이하 간격이면 고리 전부가 같은 틱에 지나가 한 사람이 한 번에 세 번 맞는다 —
		// 지금 피해로 한 틱에 12, 팀 합계 48 이라 그 자리에서 전멸이다.
		assertTrue(TrialLandingShock.ringInterval(0) >= 1);
		assertTrue(TrialLandingShock.ringInterval(-40) >= 1);
		assertEquals(RING_INTERVAL, TrialLandingShock.ringInterval(RING_INTERVAL),
				"멀쩡한 값을 건드리면 안 된다");
		// 0 개짜리 카드는 「드래곤이 앉았는데 조용하다」가 되어 버그로 읽힌다.
		assertEquals(1, TrialLandingShock.ringCount(0));
		assertEquals(RINGS, TrialLandingShock.ringCount(RINGS));
	}

	// ------------------------------------------------------------------ 드래곤을 건드리지 않는다

	/**
	 * <b>이 시험이 이 파일에서 가장 중요한 것 가운데 하나다.</b>
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

	/**
	 * ⚠ <b>2026-10-04 에 「{@code getFightOrigin} 을 여기서 부른다」가 거짓이 됐다.</b> 셈을
	 * {@link TrialPodium} 한 벌로 모았기 때문이고, 그 사실은
	 * {@link #단상_계산이_한_벌이다()} 가 따로 지킨다. 여기 남는 것은 <b>「드래곤을 옮기거나
	 * 바꾸지 않는다」</b>뿐이다 — 셈이 어디 있든 이 카드가 지켜야 하는 성질은 그것이다.
	 */
	@Test
	void 드래곤에게서는_좌표만_읽는다() {
		String bytes = classBytes();
		// 이름만 본다. 상수 풀은 이름과 서술자를 따로 담으므로 「이름(서술자)」을 이어 붙여
		// 찾으면 언제나 통과한다 — 그러면 시험이 아무것도 안 지킨다.
		for (String moves : new String[] {"setPos", "addFreshEntity", "setHealth",
				"getSubEntities", "syncPosition"}) {
			assertFalse(bytes.contains(moves), moves + " — 드래곤을 옮기거나 바꾸고 있다");
		}
		assertTrue(bytes.contains("position"), "드래곤 좌표를 읽지 않으면 착지를 알 길이 없다");
	}

	/**
	 * ⚠⚠ <b>단상 계산이 한 벌이다.</b> 2026-10-04 에 모았다 — 그 전에는 <b>세 벌</b>이었다.
	 *
	 * <pre>
	 * Vec3.atBottomCenterOf(end.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES,
	 *         EnderDragonFight.getPodiumLocation(dragon.getFightOrigin())))
	 * </pre>
	 *
	 * <p>이 식이 {@code TrialPodium.locate} · {@code DragonLastStand.podium}(비공개) ·
	 * {@code TrialLandingShock.podiumOf}(비공개) 셋에 그대로 적혀 있었다. 앞의 둘이
	 * {@code private} 이라 밖에서 부를 수 없어 입장 수락창이 <b>세 번째로 같은 식을 적게 된</b>
	 * 것이 {@code TrialPodium} 이 태어난 까닭이다.
	 *
	 * <p><b>셋을 글자 단위로 대조한 결과 셈은 완전히 같았고</b>, 다른 것은 <b>「드래곤이 없을 때」
	 * 하나뿐</b>이었다. 그래서 <b>셈만 합치고 문은 쓰는 쪽에 남겼다.</b>
	 *
	 * <p>⚠ 세는 방법. 셈을 부르는 자리는 바이트코드에 남으므로 <b>상수 풀로 센다</b> —
	 * 바닐라 세 이름이 {@code TrialPodium} 에는 있고 쓰는 쪽 둘에는 <b>없어야</b> 한다. 한쪽에
	 * 다시 나타나면 누가 식을 베껴 적은 것이다.
	 */
	@Test
	void 단상_계산이_한_벌이다() {
		String[] vanilla = {"getPodiumLocation", "getFightOrigin", "getHeightmapPos"};

		String owner = read("/com/sharedfate/sync/TrialPodium.class");
		for (String name : vanilla) {
			assertTrue(owner.contains(name),
					"TrialPodium 이 " + name + " 을 안 쓴다 — 단상 자리를 숫자로 지어내는 것이다");
		}

		for (String user : new String[] {"TrialLandingShock", "DragonLastStand"}) {
			String bytes = read("/com/sharedfate/sync/" + user + ".class");
			assertTrue(bytes.contains("com/sharedfate/sync/TrialPodium"),
					user + " 가 TrialPodium 을 안 지난다 — 셈이 두 벌로 갈라졌다");
			for (String name : vanilla) {
				assertFalse(bytes.contains(name),
						user + " 가 " + name + " 을 제 손으로 부른다 — 같은 셈이 두 벌이 됐고,"
								+ " 하이트맵 종류를 바꾸는 날 한쪽만 고쳐진다");
			}
		}
	}

	/**
	 * ⚠⚠ <b>합치면서 옮기지 <u>않은</u> 문이 하나 있다 — 「드래곤이 죽어 가는 중」.</b>
	 *
	 * <p>{@code TrialPodium.locate} 는 드래곤이 없으면 {@code BlockPos.ZERO} 를 원점으로 써
	 * <b>언제나 좌표를 돌려준다</b>(수락창은 「어디로 모을까」를 묻는 자리라 기본값이 맞다).
	 * 여기는 반대로 <b>「방금 앉았는가」를 묻는 자리</b>라 <b>{@code null} 을 돌려야</b> 한다 —
	 * 그러지 않으면 <b>죽는 연출 중에 좌표가 멈춘 것이 착지로 읽혀</b> 고리가 한 벌 더 터진다.
	 *
	 * <p>{@code dragon == null} 은 부르는 쪽이 한 번 더 보지만 {@code isDeadOrDying()} 은
	 * <b>이 파일밖에 없다.</b> 그 한 줄이 「같은 것으로 보이는데 다른 것」이었고, 그것을 합치는
	 * 것이 이 저장소에서 가장 비싼 사고다.
	 */
	@Test
	void 죽어_가는_드래곤을_가르는_문은_여기_남았다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("isDeadOrDying"),
				"죽는 연출 중에 멈춘 좌표가 착지로 읽힌다 — 고리가 한 벌 더 터진다");
		assertTrue(bytes.contains("isAlive"), "죽은 드래곤의 자리에도 고리를 놓게 된다");
		assertFalse(read("/com/sharedfate/sync/TrialPodium.class").contains("isDeadOrDying"),
				"그 문을 TrialPodium 으로 끌어왔다 — 수락창은 기본값이 있어야 하고, 한 함수가"
						+ " 「기본값을 돌려라」와 「null 을 돌려라」를 동시에 할 수는 없다");
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
	 * <p>고리가 셋이 되면서 값이 더 나빠졌다 — 한 번 열릴 때마다 {@code 4 × 3} 이다.
	 */
	@Test
	void 한_번의_착지에_고리는_한_벌이다() {
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
		assertEquals(2, fired, "착지 두 번에 고리 두 벌이어야 한다");
	}

	// ------------------------------------------------------------------ ⚠⚠ 섬 밖으로 못 나간다

	/**
	 * ⚠ 이 카드가 예외로 허용됐던 <b>산수가 깨졌다</b>는 것을 값에서 직접 센다.
	 *
	 * <p>예전에는 {@code 15 + 8 = 23 < 40} 이라 저절로 섬 안이었다. 지금은 91 이라 그렇지 않고,
	 * <b>천장 함수 하나가 유일한 장치</b>다. 이 시험은 그 사실을 기록해 두는 자리다 — 언젠가
	 * 값을 되돌려 산수가 다시 맞으면 여기가 먼저 깨져서 알려 준다.
	 */
	@Test
	void 산수로는_이미_섬_밖이다() {
		TrialCatalog.Risk.LandingShock card = card();
		double furthest = card.maxRadius() + card.knockback();
		assertEquals(91.0, furthest, 1.0E-9, "75 + 16 = 91");
		assertTrue(furthest > TrialRisks.ARENA_RADIUS,
				"산수가 다시 섬 안에 들어왔다. 그러면 천장에만 기대던 이 카드가 근거를 되찾은"
						+ " 것이니 문서와 이 시험을 함께 되돌릴 것");
	}

	/**
	 * ⚠⚠ 천장이 섬 <b>경계보다 안쪽</b>이다.
	 *
	 * <p>경계에 딱 세우면 거기서 한 걸음이 곧 낙사이고, 공유 체력이라 그 한 걸음이 팀 전체를
	 * 끝낸다. 그리고 40 은 「아무 곳이나 고를 때 쓰는 원」이지 섬의 실제 가장자리가 아니다.
	 */
	@Test
	void 천장은_섬_경계보다_안쪽이다() {
		double limit = TrialLandingShock.outwardLimit(MAX_RADIUS, KNOCKBACK);
		assertEquals(TrialRisks.ARENA_RADIUS - TrialLandingShock.EDGE_MARGIN, limit, 1.0E-9,
				"지금 값에서는 섬 반경에서 여유를 뺀 것이 그대로 천장이다");
		assertTrue(limit < TrialRisks.ARENA_RADIUS,
				"경계에 딱 세우면 밀린 직후 방향도 모르는 사람의 한 걸음이 팀 전멸이다");

		double sprintJump = 4.3;
		assertTrue(TrialLandingShock.EDGE_MARGIN > sprintJump,
				"여유(" + TrialLandingShock.EDGE_MARGIN + ")가 스프린트 점프 한 번(" + sprintJump
						+ ")보다 짧으면 「여유를 뒀다」가 말뿐이다");
	}

	/**
	 * ⚠⚠ <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>아레나 안팎의 자리와 온갖 방향을 훑으면서, 카드 값의 100배짜리 넉백으로도 목적지가
	 * 천장 밖으로 나가지 않는지 본다. 「엔더폭풍」이 같은 방식으로 제 넉백을 지킨다.
	 *
	 * <p>방향을 온갖 각으로 도는 것이 핵심이다. 미는 방향은 <b>고리 중심(포탈 꼭대기)에서 바깥</b>
	 * 인데 천장이 재는 것은 <b>아레나 중앙에서의 거리</b>라, 둘이 어긋난 판에서도 안전해야 한다.
	 */
	@Test
	void 아무리_세게_밀어도_천장_안이다() {
		double limit = TrialLandingShock.outwardLimit(MAX_RADIUS, KNOCKBACK);
		for (double multiple : new double[] {1.0, 2.0, 10.0, 100.0}) {
			double knock = KNOCKBACK * multiple;
			for (int spoke = 0; spoke < 72; spoke++) {
				double angle = spoke * Math.PI / 36.0;
				double dx = Math.cos(angle);
				double dz = Math.sin(angle);
				for (int px = -48; px <= 48; px += 3) {
					for (int pz = -48; pz <= 48; pz += 3) {
						double before = TrialLandingShock.fromCenter(px, pz);
						double pushed = TrialLandingShock.pushDistance(before, knock, limit);
						assertTrue(pushed >= 0.0, "음수만큼 밀면 방향이 뒤집힌다");
						double after = TrialLandingShock.fromCenter(px + dx * pushed,
								pz + dz * pushed);
						assertTrue(after <= Math.max(before, limit) + 1.0E-9,
								"(" + px + ", " + pz + ") 에 선 사람이 " + before + " 에서 "
										+ after + " 로 밀려났다 — 천장을 넘긴 것이고,"
										+ " 엔드 섬 밖은 허공이라 그대로 월드 삭제다");
						assertTrue(after < TrialRisks.ARENA_RADIUS
										|| before >= TrialRisks.ARENA_RADIUS,
								"섬 안에 있던 사람을 섬 경계 밖으로 내보냈다");
					}
				}
			}
		}
	}

	/**
	 * 밀리는 <b>도중</b>도 안전하다.
	 *
	 * <p>목적지만 안이면 되는 것이 아니다. 경사에 걸리거나 다른 카드가 끼어들어 중간에 멈출 수
	 * 있으므로, 지나가는 어느 점도 천장 밖이면 안 된다.
	 */
	@Test
	void 밀리는_도중에도_천장을_넘지_않는다() {
		double limit = TrialLandingShock.outwardLimit(MAX_RADIUS, KNOCKBACK);
		for (int spoke = 0; spoke < 36; spoke++) {
			double angle = spoke * Math.PI / 18.0;
			double dx = Math.cos(angle);
			double dz = Math.sin(angle);
			for (int px = -40; px <= 40; px += 5) {
				for (int pz = -40; pz <= 40; pz += 5) {
					double before = TrialLandingShock.fromCenter(px, pz);
					double pushed = TrialLandingShock.pushDistance(before, KNOCKBACK, limit);
					for (int tenth = 0; tenth <= 10; tenth++) {
						double along = pushed * tenth / 10.0;
						double now = TrialLandingShock.fromCenter(px + dx * along, pz + dz * along);
						assertTrue(now <= Math.max(before, limit) + 1.0E-9,
								"밀리는 도중에 천장을 넘었다: (" + px + ", " + pz + ")");
					}
				}
			}
		}
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

		for (int step = 0; step <= 400; step++) {
			double from = step / 4.0;
			double after = from + TrialLandingShock.pushDistance(from, KNOCKBACK, limit);
			assertTrue(after <= Math.max(from, limit) + 1.0E-9,
					from + " 칸에 서 있던 사람이 " + after + " 칸까지 밀렸다");
		}
	}

	@Test
	void 천장은_어떤_값에도_섬_안이다() {
		// 다음에 값을 만질 사람이 합을 더 올려도 여기서 잘린다. 카드에 뭘 적든 이 함수는
		// 못 피한다 — 지금은 그것이 이 카드의 유일한 근거다.
		double ceiling = TrialRisks.ARENA_RADIUS - TrialLandingShock.EDGE_MARGIN;
		assertEquals(ceiling, TrialLandingShock.outwardLimit(600.0, 200.0));
		assertEquals(ceiling, TrialLandingShock.outwardLimit(MAX_RADIUS, KNOCKBACK));
		assertEquals(0.0, TrialLandingShock.outwardLimit(-5.0, -5.0), "음수 합이 음수 천장이 되면"
				+ " pushDistance 의 「남은 자리」가 뒤집힌다");
		assertTrue(TrialLandingShock.outwardLimit(1.0, 1.0) <= 2.0,
				"작은 값에서는 천장이 산수 그대로여야 한다 — 여기를 늘리면 옛 카드가 더 멀리 민다");
	}

	/**
	 * ⚠ 땅이 끊긴 자리로는 밀지 않는다.
	 *
	 * <p>천장은 중앙에서 잰 반경으로만 막는데 <b>중앙 섬은 둥글지 않다.</b> 반경 34 안에도
	 * 허공이 있을 수 있고 사람이 파 놓은 구멍도 있다. 거기로 밀면 천장을 지켰는데도 낙사다.
	 */
	@Test
	void 구멍을_만나면_그_앞에서_멈춘다() {
		// 온 길이 다 땅이면 시킨 만큼 다 민다.
		assertEquals(KNOCKBACK,
				TrialLandingShock.groundedReach((x, z) -> true, 0.0, 0.0, 1.0, 0.0, KNOCKBACK),
				1.0E-9, "땅이 이어져 있는데 안 밀면 카드가 제 일을 안 한다");

		// x 가 4 부터 허공이다. 0.5 칸씩 짚으므로 3.5 에서 멈춘다.
		assertEquals(3.5,
				TrialLandingShock.groundedReach((x, z) -> x < 4.0, 0.0, 0.0, 1.0, 0.0, KNOCKBACK),
				1.0E-9, "허공을 밟기 전에 멈춰야 한다");

		// 폭 1 짜리 구멍(4 ≤ x < 5)이고 건너편은 다시 땅이다. 건너뛰면 안 된다 —
		// 밀려가는 몸은 구멍을 건너가지 않고 그리로 떨어진다.
		assertEquals(3.5,
				TrialLandingShock.groundedReach((x, z) -> x < 4.0 || x >= 5.0, 0.0, 0.0, 1.0, 0.0,
						KNOCKBACK),
				1.0E-9, "건너편에 땅이 있다고 구멍을 건너뛰면 그 구멍이 곧 낙사다");

		// 발밑 바로 앞이 허공이면 한 칸도 안 민다.
		assertEquals(0.0,
				TrialLandingShock.groundedReach((x, z) -> false, 0.0, 0.0, 1.0, 0.0, KNOCKBACK));
		assertEquals(0.0,
				TrialLandingShock.groundedReach((x, z) -> true, 0.0, 0.0, 1.0, 0.0, 0.0),
				"밀 거리가 없으면 짚을 것도 없다");
		assertEquals(0.0,
				TrialLandingShock.groundedReach((x, z) -> true, 0.0, 0.0, 1.0, 0.0, -5.0));
	}

	@Test
	void 땅을_짚어도_시킨_거리를_넘지_않는다() {
		// 짚는 간격이 0.5 라 마지막 한 걸음이 시킨 거리를 넘어설 수 있다. 넘기면 천장이
		// 그만큼 뚫린다.
		for (int tenth = 0; tenth <= 400; tenth++) {
			double wanted = tenth / 10.0;
			double reached = TrialLandingShock.groundedReach((x, z) -> true, 0.0, 0.0, 0.6, 0.8,
					wanted);
			assertEquals(Math.max(0.0, wanted), reached, 1.0E-9,
					"땅이 이어져 있으면 정확히 시킨 만큼이어야 한다: " + wanted);
		}
	}

	/**
	 * 미는 속도를 <b>공중</b> 감쇠로 잡는다 — 그것이 적힌 넉백을 천장으로 만든다.
	 *
	 * <p>바닥 마찰(0.546)로 잡으면 같은 16칸에 7.2 칸/틱이 필요한데, 그 속도로 한 틱이라도
	 * 떠 있으면 80칸을 날아 섬 밖이다.
	 */
	@Test
	void 미는_속도는_공중_모델로_잡는다() {
		double speed = TrialLandingShock.pushVelocity(KNOCKBACK);
		assertEquals(1.44, speed, 1.0E-9, "16 × (1 - 0.91)");

		double airborneTravel = speed / (1.0 - TrialLandingShock.AIR_DRAG);
		assertEquals(KNOCKBACK, airborneTravel, 1.0E-9,
				"떠 있는 채로 끝까지 밀려도 적힌 거리에서 멈춰야 한다 — 그것이 천장의 뜻이다");

		double groundTravel = speed / (1.0 - 0.6 * TrialLandingShock.AIR_DRAG);
		assertTrue(groundTravel < KNOCKBACK,
				"바닥에서는 마찰이 먼저 먹는다. 실제로 밀리는 거리는 " + groundTravel
						+ " 칸이라 적힌 16칸보다 짧다 — 어긋나는 방향이 반드시 이쪽이어야 한다");
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

	/**
	 * ⚠⚠ <b>착지 {@code 160}틱 동안 드래곤이 쌓아 둔 세로를 배달하지 않는다.</b> <b>이것이 이
	 * 저장소가 재어 둔 가장 나쁜 경우다.</b>
	 *
	 * <p>사람 말: <b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04).
	 *
	 * <p>이 카드는 세로를 <b>읽어서 그대로 돌려놓고</b> {@code syncVelocity} 를 켰다. 그 세로는
	 * <b>우리가 쓴 값이 아니다</b> — 바닐라 {@code EnderDragon.knockBack} 이 날개 상자
	 * ({@code inflate(4,2,4).move(0,-2,0)}) 안의 사람에게 매 틱 세로 {@code +0.2} 를 더한다.
	 * ⚠ {@code isSitting()} 조건은 <u>피해</u>에만 붙어 있어 <b>미는 것은 조건 없이 돈다.</b>
	 * 바닐라는 그 값을 {@code needsSync} 로만 내보내 본인에게 안 보내지만
	 * {@code syncVelocity} 는 보내고, 보내는 것이 <b>그 순간의 {@code getDeltaMovement()}
	 * 통째로</b>다.
	 *
	 * <h2>⚠⚠ 왜 이 카드가 가장 나쁜가 — <b>160틱이 한 틱도 안 끊긴다</b></h2>
	 *
	 * <p>이 카드가 터지는 창이 곧 착지이고 {@code DragonPerch.HOLD_TICKS} 가 드래곤을 포디움에
	 * <b>160틱</b> 붙박아 둔다 — 머리를 때리는 사람이 최후의 저항과 <b>같은 기하</b>에 선다.
	 * 그리고 쌓임을 끊는 것은 {@code wasHurtRecently()}(= {@code hurtTime > 0})인데
	 * {@code EnderDragonPerchRangedImmunityMixin} 이 {@code hurt} 를 HEAD 에서
	 * {@code setReturnValue(false)} 로 끊어 <b>{@code hurtTime} 이 아예 안 올라간다.</b> 곧
	 * <b>원거리로만 때리는 팀에게는 160틱이 통째로 쌓인다</b> — 우리 기능 둘이 서로를 악화시키는
	 * 자리다.
	 */
	@Test
	void 착지_160틱_동안_쌓인_세로를_배달하지_않는다() {
		// ① 자름이 실제로 배선돼 있다. 순수 함수 시험은 함수를 안 부르면 아무것도 못 잡는다.
		String bytes = classBytes();
		assertTrue(bytes.contains("syncedVertical"),
				"세로를 읽은 그대로 내려보내고 있다 — 남이 쌓아 둔 값이 그 한 줄로 배달된다");
		assertTrue(bytes.contains("com/sharedfate/sync/TrialVelocity"),
				"자름을 이 파일에 따로 적으면 두 벌이 되어 언젠가 한쪽만 고쳐진다");
		assertTrue(bytes.contains("getKnownMovement"),
				"천장이 없다 — deltaMovement 만 보면 남이 쌓아 둔 값과 사람 몫을 가를 수 없다");
		assertFalse(bytes.contains("knockBack"),
				"이 카드가 바닐라 넉백에 손대면 안 된다 — 착지의 날개 밀치기는 바닐라 동작이다");

		// ② 160틱을 틱마다 굴린다. 가만히 선 사람이라 클라이언트가 보고하는 세로가 0 이다.
		double dragonPush = 0.20000000298023224;
		double server = 0.0;
		double worstShipped = 0.0;
		for (int tick = 0; tick < DragonPerch.HOLD_TICKS; tick++) {
			server = (server + dragonPush - DragonLastStandPatterns.LIFT_GRAVITY)
					* DragonLastStandPatterns.LIFT_DRAG;
			worstShipped = Math.max(worstShipped, TrialVelocity.syncedVertical(server, 0.0));
		}
		assertEquals(160, DragonPerch.HOLD_TICKS,
				"착지가 붙박아 두는 시간이 달라졌으면 위 설명도 고칠 것");
		assertEquals(5.648, server, 0.001,
				"고치기 전에 내려가던 수다 — 쌓이는 식은 (v + 0.2 − 0.08) × 0.98 이다");
		assertEquals(109.3, DragonLastStandPatterns.liftApex(server), 0.1,
				"그 속도의 도달 높이가 109칸이다 — 「하늘로 날라가버림」이 그것이다");

		// ③ 고친 뒤에는 한 톨도 안 나간다. 서 있는 사람은 올라가고 있지 않으므로 천장이 0 이다.
		assertEquals(0.0, worstShipped, 1.0E-12,
				"올라가고 있지 않은 사람에게 올라가는 속도를 보내면 그것이 곧 「하늘로 날아간다」다");

		// ④ ⚠ 26.3 에는 「속도 패킷이 3.9 에서 잘린다」가 없다. LpVec3.ABS_MAX_VALUE 가
		//   1.7179869183E10 이라 쌓인 값이 한 톨도 안 깎이고 내려간다.
		assertEquals(0.0, TrialVelocity.syncedVertical(1.0E9, 0.0), 1.0E-12,
				"패킷이 알아서 잘라 줄 것을 기대하면 안 된다 — 26.3 은 자르지 않는다");
	}

	/**
	 * ⚠⚠ <b>정상 플레이어는 비트 단위로 무변화다.</b>
	 *
	 * <p>이 카드의 회피가 <b>뛰어넘기</b>({@link #창은_점프_한_번보다_짧다})라 더 날카롭다 —
	 * 세로를 자르는 것이 점프를 깎으면 <b>이 카드의 정답이 사라진다.</b> 그리고 떨어지는 사람을
	 * 더 세게 떨어뜨리면 그것이 새 낙사 장치다.
	 *
	 * <p>∆ 이 아니라 {@code 0.0} 오차로 잰다 — 「거의 같다」가 아니라 <b>같은 비트</b>여야 한다.
	 */
	@Test
	void 스스로_뛴_사람과_떨어지는_사람은_비트_단위로_안_달라진다() {
		// ① 바닐라 점프. 처음 0.42 로 25틱을 굴린다. 스스로 올라가는 속도가 곧 제 천장이다.
		double rise = 0.42;
		for (int tick = 0; tick < 25; tick++) {
			assertEquals(rise, TrialVelocity.syncedVertical(rise, rise), 0.0,
					tick + "틱째 점프 세로가 달라졌다 — 뛰어넘기가 손해가 된다");
			rise = (rise - DragonLastStandPatterns.LIFT_GRAVITY)
					* DragonLastStandPatterns.LIFT_DRAG;
		}

		// ② 떨어지는 중. 클라이언트가 무엇을 보고했든 받은 값이 그대로 나간다.
		for (double client : new double[] {-9.0, -1.0, -0.0784, 0.0, 0.42, 1.0, 1.0E9}) {
			for (double fall = -5.0; fall <= 0.0; fall += 0.001) {
				assertEquals(fall, TrialVelocity.syncedVertical(fall, client), 0.0,
						"떨어지는 중인 사람의 세로가 달라졌다 — 그 한 줄이 새 낙사 장치가 된다");
			}
		}
	}

	/**
	 * ⚠ <b>세로를 자르는 것이 수평 천장을 한 톨도 안 건드렸다.</b>
	 *
	 * <p>{@link #미는_속도는_공중_모델로_잡는다} 가 카드 값 하나로 재는 성질을 <b>거리마다</b>
	 * 굴려 못박는다 — {@link TrialLandingShock#pushVelocity} 는 <b>공중 모델</b>이라 사람이 떠
	 * 있어도 적힌 거리를 <b>넘을 수 없다.</b> 곧 쌓인 세로가 배달돼 사람이 들렸더라도
	 * {@link TrialLandingShock#outwardLimit} 과 {@link TrialLandingShock#groundedReach} 가 잡아 둔
	 * 거리는 무너지지 않았고, 세로를 자르는 것은 <b>「하늘로 솟는 것」을 막는 일</b>이다. 둘을
	 * 섞어 적으면 다음 사람이 한쪽을 고치고 다른 쪽이 고쳐졌다고 믿는다.
	 */
	@Test
	void 세로를_잘라도_수평_천장의_성질이_그대로다() {
		for (double distance = 0.0; distance <= KNOCKBACK * 3.0; distance += 0.25) {
			double travel = TrialLandingShock.pushVelocity(distance)
					/ (1.0 - TrialLandingShock.AIR_DRAG);
			assertEquals(distance, travel, 1.0E-9,
					"떠 있는 채로 끝까지 밀려도 적힌 거리에서 멈춰야 한다: " + distance);
		}
	}

	// ------------------------------------------------------------------ 전원을 때린다

	/**
	 * 고리 셋을 넷이 모두 맞아도 <b>팀이 죽지 않는다</b> — 완전무장 기준이다.
	 *
	 * <h2>날값 비교로 되돌리지 말 것</h2>
	 *
	 * <p>날값으로 재면 {@code 4 × 4 × 3 = 48} 이라 팀 체력 20 을 훌쩍 넘는다. 그런데 사람이
	 * 정한 기준은 <b>다이아 풀셋 + 보호 IV</b> 이고({@link GearedDamage}), 그 기준에서 피해 4 는
	 * 한 대에 <b>0.56</b> 이다 — 폭발형이라 하드 곱 1.5 가 먼저 붙고 방어·보호가 그 뒤를 깎는다.
	 * 합계가 6.7 로 팀 체력의 3분의 1 이다.
	 *
	 * <p>감쇠를 <b>한 방마다</b> 거는 것이 중요하다. 세 고리는 {@code 감쇠(4 × 3)} 이 아니라
	 * {@code 감쇠(4) × 3} 이다 — 방어 공식이 비선형이라 합쳐서 감쇠하면 실제보다 아프게 나온다.
	 *
	 * <h2>이 카드가 「안 아픈」 것이 잘못이 아니다</h2>
	 *
	 * <p>큰 카드들은 무장 기준 <b>세 대에 전멸</b>하도록 35·23 으로 잡혀 있다. 이 카드만 4 인
	 * 것은 <b>넉백이 본체</b>이기 때문이다 — 사람을 끝내는 것은 피해가 아니라 허공이다.
	 * 그래서 이 파일이 지키는 것도 피해 산수가 아니라 천장이다.
	 */
	@Test
	void 무장_기준으로_넷이_고리_셋을_다_맞아도_산다() {
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		assertEquals(GearedDamage.TEAM_HEALTH, teamHealth,
				"팀 공유 체력이 바뀌었다면 GearedDamage 와 아래 판단을 전부 다시 볼 것");

		float perHit = GearedDamage.afterGear(DAMAGE, GearedDamage.Source.EXPLOSION);
		assertTrue(perHit < 1.0F,
				"무장 기준 한 대가 " + perHit + " 다. 이 카드는 피해로 승부하지 않는다 —"
						+ " 1 을 넘었다면 값이 올라간 것이니 클래스 설명부터 다시 읽을 것");

		float worst = TrialLandingShock.worstCaseTeamDamage(perHit, TEAM, RINGS);
		assertTrue(worst < teamHealth,
				"넷이 고리 셋을 다 맞으면 " + worst + " 인데 팀 공유 체력이 " + teamHealth + " 다."
						+ " 넘기는 순간 전원 타격이 즉사 카드가 된다");
		assertTrue(worst < teamHealth / 2.0F,
				"여유가 절반도 안 남으면 다른 카드와 겹쳤을 때 이 카드가 마지막 한 방이 된다."
						+ " 실제 합계: " + worst);

		assertEquals(perHit * TEAM * RINGS, worst, 1.0E-4F, "한 대 × 인원 × 고리");
		assertEquals(0.0F, TrialLandingShock.worstCaseTeamDamage(perHit, 0, RINGS));
		assertEquals(0.0F, TrialLandingShock.worstCaseTeamDamage(0.0F, TEAM, RINGS));
		assertEquals(0.0F, TrialLandingShock.worstCaseTeamDamage(perHit, TEAM, 0));
	}

	/**
	 * ⚠ 무장을 잃은 사람에게는 감쇠가 하나도 안 걸린다.
	 *
	 * <p>{@link GearedDamage} 의 「이 기준이 봐 주지 않는 사람」이다. 날값이면 넷이 세 번 다
	 * 실패했을 때 48 이라 팀이 지워진다. 이 카드가 그래도 괜찮은 것은 <b>대응이 점프이고 6초에
	 * 걸쳐 세 번</b>이기 때문이지 값이 작아서가 아니다 — 그 사실을 숫자로 남겨 둔다.
	 */
	@Test
	void 맨몸이면_날값이_팀_체력을_넘는다() {
		float bare = TrialLandingShock.worstCaseTeamDamage(DAMAGE, TEAM, RINGS);
		assertEquals(48.0F, bare, "4 × 4 × 3");
		assertTrue(bare > GearedDamage.TEAM_HEALTH,
				"이 값이 체력 아래로 내려왔다면 카드 값이 바뀐 것이다 — 그때는 이 시험이 아니라"
						+ " 위의 무장 기준 시험이 기준이다");
	}

	@Test
	void 한_틱에_한_사람이_받는_몫은_한_번이다() {
		// 고리는 한 사람을 한 번만 지나가고, 고리끼리는 40틱 떨어져 있어 한 틱에 겹치지 않는다.
		// TrialRisks 가 이 위험을 hits(damage, 1) 로 세는 것이 그 이야기다.
		assertEquals(DAMAGE, TrialRisks.worstCaseTickDamage(card()),
				"한 사람이 한 틱에 받는 몫이 카드에 적힌 피해와 달라졌다");
		assertTrue(RING_INTERVAL > TrialLandingShock.JUMP_WINDOW_TICKS,
				"고리 간격이 판정 창보다 좁으면 두 고리의 뒷자락이 같은 틱에 지나가 한 틱에"
						+ " 두 번 맞는다 — 그러면 위의 hits(damage, 1) 이 거짓이 된다");
	}

	// ------------------------------------------------------------------ 보이고 들린다

	@Test
	void 고리는_긴_형태로_나간다() {
		// 고리는 반경 75 까지 간다. 짧은 형태는 32칸에서 잘려, 바깥쪽이 아무에게도 안 보인다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 빌드도 로그도 조용한 채로 고리가 사라진다");
	}

	/**
	 * 앞머리와 몸통을 갈랐다.
	 *
	 * <p>26.3 먼지 입자는 수명이 8~40틱이라, 파랑 먼지만으로 그리면 지금 속도(0.5칸/틱)에서
	 * 자국이 4~20칸 남아 <b>고리가 아니라 퍼지는 원판</b>으로 보인다. 앞머리가 어디인지 안
	 * 읽히면 언제 뛰어야 하는지도 안 읽힌다.
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

	/**
	 * ⚠ 앞머리가 <b>세로로 선다</b> — 사람이 「너무 이펙트가 잘안보여」라고 해서 고친 자리다.
	 *
	 * <p>바닥에만 찍으면 서서 보는 눈높이에서 고리가 시선과 거의 나란해 한 줄로 뭉개진다. 위로
	 * 쏘면 <b>점 수도 패킷 수도 그대로인데</b> 벽이 된다.
	 *
	 * <p>높이를 시험이 보는 까닭은 <b>눈으로만 확인되는 값</b>이기 때문이다. 다음 사람이 2.0 을
	 * 20 으로 바꿔도 컴파일도 로그도 조용한데, 그때 서는 것은 <b>다른 카드의 바닥 표식을 가리는
	 * 벽</b>이다. 고리가 셋이라 이 카드만으로 벽이 셋이다.
	 */
	@Test
	void 앞머리가_사람_키만큼_선다() {
		double rise = TrialLandingShock.riseHeight(TrialLandingShock.EDGE_RISE_SPEED);
		assertEquals(1.878, rise, 1.0E-3,
				"26.3 CritParticle(속도×0.4 · 마찰 0.7 · 중력 0.5)을 수명 하한 4틱으로 굴린 값이다."
						+ " 달라졌다면 판이 올라 물리가 바뀐 것이니 상수 설명부터 다시 읽을 것");

		double human = 1.8;
		assertTrue(rise >= human,
				"올라가는 높이가 " + rise + "칸이다 — 사람 키(" + human + ")보다 낮으면 벽이 아니라"
						+ " 조금 두꺼운 바닥 선이고, 그러면 고친 뜻이 없다");
		assertTrue(rise < TrialEnderStorm.COLUMN_HEIGHT,
				"「엔더폭풍」이 기둥을 " + TrialEnderStorm.COLUMN_HEIGHT + "칸에서 멈춘 것은 세로로"
						+ " 선 것이 다른 카드의 바닥 표식을 가리기 때문이다. 이 카드는 고리가"
						+ " 셋이라 더 낮아야 한다");
		assertEquals(0.0, TrialLandingShock.riseHeight(0.0),
				"0 이면 제자리에 찍는다 — 몸통이 그 갈래로 간다");
		assertEquals(0.0, TrialLandingShock.riseHeight(-2.0), "음수는 땅으로 쏘는 것이다");
	}

	/**
	 * ⚠ 몸통을 키웠는데 <b>쌓이는 수</b>가 「엔더 파동」이 그은 선 아래다.
	 *
	 * <p>먼지는 크기와 수명이 한 값에 매여 있어서(26.3 {@code DustParticleBase}) 키우면 오래
	 * 남고, 오래 남으면 <b>화면에 살아 있는 수</b>가 는다. 한 틱 예산은 그대로인데 여기가 조용히
	 * 몇 배가 될 수 있는 자리라 값에서 직접 센다.
	 */
	@Test
	void 몸통을_키워도_쌓이는_수가_선_아래다() {
		assertTrue(TrialLandingShock.WAKE_SCALE > 1.0F,
				"1.0 이면 「크게 했다」가 말뿐이다 — 사람이 고쳐 달라고 한 것이 그대로 남는다");

		// 몸통이 한 틱에 쓸 수 있는 가장 큰 몫. 나머지는 앞머리 몫이다.
		int wakePerTick = TrialLandingShock.MAX_POINTS_PER_TICK
				- TrialLandingShock.edgeShare(TrialLandingShock.MAX_POINTS_PER_TICK);
		assertEquals(176, wakePerTick, "440 - edgeShare(440)");

		// 크기 1.0 에서 8~40틱이고, 크기가 그 수에 그대로 곱해진다.
		int dustMaxLife = (int) (40.0F * TrialLandingShock.WAKE_SCALE);
		int alive = wakePerTick * dustMaxLife;
		int line = TrialEnderPulse.EDGE_MAX_POINTS * 40;
		assertTrue(alive < line,
				"몸통이 화면에 " + alive + " 점까지 쌓인다. 「엔더 파동」이 " + line
						+ " 을 두고 「만 점을 넘긴다」며 몸통 상한을 깎았다 — 그 선을 넘으려면"
						+ " 몸통 몫부터 줄여야 한다");

		// 판정이 내려지는 뒷자락까지 반드시 파랑으로 덮여 있어야 한다. 크기를 올리면 수명도
		// 함께 오르므로 이쪽은 더 넉넉해질 뿐이다.
		int dustMinLife = (int) (8.0F * TrialLandingShock.WAKE_SCALE);
		assertTrue(dustMinLife > TrialLandingShock.JUMP_WINDOW_TICKS,
				"먼지 수명 하한(" + dustMinLife + "틱)이 판정 창보다 짧으면 맞는 자리가 안 그려진"
						+ " 채로 맞는다");
	}

	/**
	 * 연출을 고치면서 <b>소리를 늘렸다</b> — 점 예산과 무관한 길이다.
	 *
	 * <p>착지한 그 틱에 한 번, 뛰어서 넘긴 순간에 한 번. 둘 다 사람마다 제자리에서 울린다 —
	 * 한 점에서 울리면 16칸 밖은 아무것도 못 듣는다.
	 */
	@Test
	void 착지와_회피를_소리로_말한다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("GENERIC_EXPLODE"),
				"착지한 틱에 아무 소리도 안 나면, 화면을 안 보고 있던 사람은 고리가 발밑에 올"
						+ " 때까지 시작한 줄을 모른다");
		assertTrue(bytes.contains("WIND_CHARGE_BURST"),
				"밀려나는 소리가 사라졌다 — 맞았을 때와 넘겼을 때를 이 소리의 음과 크기로 가른다");
		assertTrue(bytes.contains("playSound"), "소리를 한 번도 울리지 않는다");
	}

	/**
	 * 공용 경고를 쓰되 <b>사람마다 보내는</b> 쪽이어야 한다.
	 *
	 * <p>자리에 놓는 {@code TrialWarning.sound} 가 아니라 {@code TrialWarning.soundFor} 다.
	 * 고리가 중앙에서 퍼져 <b>사람마다 다른 순간에</b> 닿는 카드라, 자리에 놓으면 반경 안의
	 * 전원에게 나가 남의 경고까지 들리고 같은 거리에 선 둘은 겹쳐 듣는다.
	 */
	@Test
	void 경고_소리는_공용_경고를_쓴다() {
		assertTrue(classBytes().contains("(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/server/level/ServerPlayer;"
						+ "Lcom/sharedfate/sync/TrialWarning$Stage;)V"),
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

	// ------------------------------------------------------------------ 지형을 탄다

	/**
	 * 고리가 점마다 그 자리의 지표 위에 찍힌다.
	 *
	 * <p>이 고리의 중심은 아레나 바닥이 아니라 <b>포탈 꼭대기</b>라, 한 높이로 그리면 포탈 단을
	 * 벗어난 뒤로 섬 위를 떠서 지나간다. 사람이 「y좌표가 달라지면 아예 이상해진다」고 말한
	 * 자리다.
	 */
	@Test
	void 고리는_점마다_그_자리의_지표를_묻는다() {
		// 청크를 어떻게 다루는지는 TrialEnderPulse.Ground 한 자리에만 있고 그쪽 시험이 본다.
		// 여기서 지키는 것은 「이 카드가 그것을 쓴다」 하나다 — 제 것을 따로 만들면 두 고리가
		// 서로 다른 지형을 타게 된다.
		assertTrue(classBytes().contains("surfaceAt"),
				"한 높이로 그리면 포탈 단을 벗어난 뒤로 고리가 섬 위를 떠서 지나간다");
	}

	/**
	 * ⚠ 판정도 고리를 따라 올라간다 — <b>「엔더 파동」의 것을 그대로 쓴다.</b>
	 *
	 * <p>두 고리가 서로 다른 높이 규칙을 쓰면 사람이 한 카드에서 배운 것이 다른 카드에서 안
	 * 통한다. 규칙 자체의 근거는 그쪽 시험에 있다.
	 */
	@Test
	void 높이_판정도_엔더_파동의_것을_쓴다() {
		assertTrue(TrialEnderPulse.atRingHeight(64.0, 64), "지표에 서 있으면 걸린다");
		assertFalse(TrialEnderPulse.atRingHeight(58.0, 64),
				"굴 속이다 — 고리는 머리 위를 지나갔고 이 사람 눈에는 보이지도 않았다");
		assertFalse(TrialEnderPulse.atRingHeight(64.0, TrialEnderPulse.NO_GROUND),
				"땅이 없는 칸에는 고리를 그리지도 않았다");
		assertTrue(classBytes().contains("atRingHeight"),
				"높이 규칙을 이 파일에 새로 적으면 한쪽만 고쳐진다");
	}

	// ------------------------------------------------------------------ 점 예산

	/**
	 * ⚠ 고리가 셋이라도 <b>한 틱 합계</b>가 예산 안이다.
	 *
	 * <p>「엔더 파동」의 점 수를 고리마다 그대로 쓰면 한 틱에 1200점이다. 벌이 사는 235틱을
	 * 통째로 훑어 합계를 센다.
	 *
	 * <p>⚠ <b>가장 바쁜 틱을 숫자로 못박아 둔다.</b> 사람이 「이펙트가 잘 안 보인다」고 했을 때
	 * 가장 쉬운 답이 점을 늘리는 것인데, 이 카드는 이미 434점이라 늘릴 자리가 없다. 연출을
	 * 고치는 사람이 여기를 먼저 만나야 <b>점이 아닌 길</b>(크기·세로·소리)을 찾는다.
	 */
	@Test
	void 고리가_셋이어도_한_틱_예산_안이다() {
		long last = TrialLandingShock.lastTick(RINGS, RING_INTERVAL,
				TrialEnderPulse.lifetime(0, TRAVEL));
		int busiest = 0;
		for (long age = 0; age <= last; age++) {
			int points = pointsAtAge(age);
			assertTrue(points <= TrialLandingShock.MAX_POINTS_PER_TICK,
					age + "틱에 " + points + " 점이 나간다. 이 판이 쓰는 예산은 400~440 이다");
			busiest = Math.max(busiest, points);
		}
		assertTrue(busiest > TrialWarning.MAX_POINTS,
				"가장 바쁜 틱이 " + busiest + " 점이다. 이 카드의 고리는 반드시 읽혀야 하는 줄이라"
						+ " 공용 상한(" + TrialWarning.MAX_POINTS + ")보다 촘촘하게 준다");
		assertEquals(434, busiest,
				"연출을 고치면서 점을 늘렸다. 예산이 400~440 이라 434 에서는 늘릴 자리가 거의"
						+ " 없다 — 눈에 띄게 하려면 점 말고 크기·세로·소리를 볼 것");
	}

	/**
	 * ⚠⚠ <b>오브젝트 파도는 점을 쓴다.</b> {@code DragonLastStand.tick} 에
	 * <b>「이 파도가 매 틱 점을 한 개도 쓰지 않는다」</b>라고 적혀 있었고 <b>거짓이었다</b>(연결선이
	 * 먼지로 긋는 것이라 여섯 줄을 두 틱에 나눠도 점이 남는다). 2026-10-04 에 그 주석을 고쳤고,
	 * 여기가 <b>고친 주석이 코드와 맞는지</b>를 묻는 자리다.
	 *
	 * <p>⚠ <b>수를 세 번째로 적지 않는다.</b> 최악 397(패턴 360 + 파도 37)은
	 * {@code DragonLastStandPatternsTest} 와 {@code DragonLastStandObjectsTest} 가 <b>둘 다</b>
	 * 못박고 있어 한쪽이 올리면 함께 멈춘다 — 일부러 만든 목 좁은 자리라 여기서 같은 수를 또
	 * 적으면 고칠 곳이 셋이 된다. 여기서 묻는 것은 <b>「0 이 아니다」와 「합이 예산 안이다」</b>
	 * 둘뿐이다.
	 */
	@Test
	void 파도_몫이_예산에_함께_들어간다() {
		int wave = DragonLastStandObjects.worstCasePointsPerTick();
		assertTrue(wave > 0,
				"파도 몫이 0 이다 — 「점을 한 개도 쓰지 않는다」가 되살아났다. 연결선이 먼지라"
						+ " 그 말은 연결선이 들어간 날부터 거짓이다");
		int worst = DragonLastStandPatterns.worstCasePointsPerTick() + wave;
		assertTrue(worst <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"패턴과 파도를 합쳐 한 틱 " + worst + "점이라 예산 "
						+ TrialLandingShock.MAX_POINTS_PER_TICK + "을 넘는다 — 둘은 같은 틱에 돈다");
	}

	/**
	 * 고리 하나만 날 때는 <b>예전과 똑같이</b> 400점을 쓴다.
	 *
	 * <p>예산을 나누는 장치가 혼자 나는 고리까지 깎으면, 고리를 셋으로 늘린 값이 첫 2초의
	 * 선명함까지 빼앗은 것이 된다.
	 */
	@Test
	void 혼자_나는_고리는_깎이지_않는다() {
		double radius = TrialEnderPulse.radiusAt(39, TRAVEL, MAX_RADIUS);
		int want = TrialLandingShock.ringWant(radius);
		assertEquals(TrialEnderPulse.MAX_POINTS_PER_TICK, want, "엔더 파동과 같은 400점이다");
		assertEquals(want, TrialLandingShock.ringAllowance(want, want),
				"혼자 날 때 깎이면 첫 고리가 옛 카드보다 흐려진다");
		assertEquals(want, TrialLandingShock.markPoints(radius,
				TrialLandingShock.ringAllowance(want, want)));
	}

	/**
	 * 작은 고리가 큰 고리의 몫을 빼앗지 않는다.
	 *
	 * <p>고리 수로 똑같이 나누면 갓 태어난 반경 0.5 짜리가 146점을 받아 한 점을 서른 번 겹쳐
	 * 찍고, 둘레 251칸짜리 바깥 고리가 같은 146점으로 점선이 된다.
	 */
	@Test
	void 예산은_바라는_만큼에_비례해_나뉜다() {
		int big = TrialLandingShock.ringWant(40.0);
		int small = TrialLandingShock.ringWant(0.5);
		assertTrue(small < big, "둘레가 작으면 바라는 점도 적어야 한다");

		int wanted = big + small;
		int bigShare = TrialLandingShock.ringAllowance(big, wanted);
		int smallShare = TrialLandingShock.ringAllowance(small, wanted);
		assertTrue(bigShare > TrialLandingShock.MAX_POINTS_PER_TICK / 2,
				"둘씩 똑같이 나눴으면 " + (TrialLandingShock.MAX_POINTS_PER_TICK / 2)
						+ " 인데 큰 고리가 " + bigShare + " 밖에 못 받았다 — 둘레 251칸짜리가"
						+ " 둘레 3칸짜리와 같은 몫을 받으면 점선이 된다");
		assertTrue(bigShare > smallShare,
				"작은 고리 몫이 " + smallShare + " 이고 큰 고리가 " + bigShare + " 다");
		assertTrue(bigShare + smallShare <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"몫의 합이 예산을 넘었다");
	}

	/**
	 * ⚠ 고리를 시간축으로 나눠도 <b>반드시 닫힌다.</b>
	 *
	 * <p>「종말한 비」가 먼저 쓴 수법이고 그쪽은 먼지 수명 하한 8틱을 보고 6 으로 묶었다. 이
	 * 카드는 앞머리에 {@code CRIT} 을 쓰는데 <b>그쪽 수명 하한이 4틱</b>이라 더 작아야 한다 —
	 * 넘기면 마지막 몫을 찍는 틱에 첫 몫이 이미 죽어 고리가 영영 안 닫힌다.
	 */
	@Test
	void 나눠_그려도_고리가_닫힌다() {
		int critMinLife = 4;
		assertTrue(TrialLandingShock.MAX_STRIDE < critMinLife,
				"앞머리(CRIT) 수명 하한이 " + critMinLife + "틱인데 " + TrialLandingShock.MAX_STRIDE
						+ "틱에 나눠 그리면 한 바퀴를 채우기 전에 첫 몫이 죽는다");
		int dustMinLife = 8;
		assertTrue(TrialLandingShock.MAX_STRIDE < dustMinLife, "몸통 먼지 쪽은 더 넉넉하다");

		// 실제로 쓰이는 stride 도 상한 안이다. 벌이 사는 내내 훑는다.
		long last = TrialLandingShock.lastTick(RINGS, RING_INTERVAL,
				TrialEnderPulse.lifetime(0, TRAVEL));
		int worst = 1;
		for (long age = 0; age <= last; age++) {
			worst = Math.max(worst, strideAtAge(age));
		}
		assertTrue(worst <= TrialLandingShock.MAX_STRIDE,
				"실제로 " + worst + "틱에 나눠 그리고 있다");
		assertTrue(worst > 1,
				"한 번도 안 나눴다면 예산이 빡빡하지 않다는 뜻이다 — 그러면 이 장치를 지우고"
						+ " 그냥 다 그리는 쪽이 단순하다");
	}

	/**
	 * 나눠 그린 덕에 <b>쌓인 고리가 「엔더 파동」만큼 촘촘하다.</b>
	 *
	 * <p>몫을 그대로 점 수로 쓰면 셋이 함께 섬 위를 돌 때 반경 42 짜리 앞머리가 2.2칸 간격이
	 * 되어 점선으로 읽힌다. 언제 뛰어야 하는지를 말하는 줄이 그 꼴이면 카드가 제 일을 못 한다.
	 */
	@Test
	void 쌓인_고리가_엔더_파동만큼_촘촘하다() {
		TrialCatalog.Risk.EnderPulse pulse = pulseCard();
		double pulseWorst = TrialEnderPulse.edgeGap(pulse.maxRadius());

		long last = TrialLandingShock.lastTick(RINGS, RING_INTERVAL,
				TrialEnderPulse.lifetime(0, TRAVEL));
		double worst = 0.0;
		for (long age = 0; age <= last; age++) {
			worst = Math.max(worst, accumulatedGapAtAge(age));
		}
		assertTrue(worst <= pulseWorst + 1.0E-9,
				"쌓인 앞머리 간격이 " + worst + "칸인데 엔더 파동의 가장 성긴 자리가 " + pulseWorst
						+ "칸이다 — 두 고리가 다르게 보이면 사람이 두 번 배워야 한다");
	}

	@Test
	void 앞머리와_몸통을_엔더_파동의_비율로_가른다() {
		// 비율을 이 카드가 새로 정하면 두 고리가 다르게 보이고, 사람이 「고리는 뛰면 피한다」를
		// 두 번 배워야 한다.
		int whole = TrialEnderPulse.MAX_POINTS_PER_TICK;
		assertEquals(TrialEnderPulse.EDGE_MAX_POINTS, TrialLandingShock.edgeShare(whole));
		assertEquals(TrialEnderPulse.WAKE_MAX_POINTS, whole - TrialLandingShock.edgeShare(whole));
		assertEquals(0, TrialLandingShock.edgeShare(0));
		assertEquals(0, TrialLandingShock.ringAllowance(0, 100));
		assertEquals(0, TrialLandingShock.ringAllowance(100, 0));
	}

	// ------------------------------------------------------------------ 카드와 문서

	@Test
	void 카드_값이_문서와_같다() {
		TrialCatalog.Risk.LandingShock card = card();
		assertEquals(TRAVEL, card.travelTicks());
		assertEquals(MAX_RADIUS, card.maxRadius());
		assertEquals(DAMAGE, card.damage());
		assertEquals(KNOCKBACK, card.knockback());
		assertEquals(RINGS, card.ringCount());
		assertEquals(RING_INTERVAL, card.ringIntervalTicks());
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 벌이 {@code age} 틱째일 때 이 카드가 쓰는 점의 <b>합계.</b>
	 *
	 * <p>실행기의 두 바퀴를 그대로 흉내 낸다 — 먼저 그릴 고리들이 바라는 수를 세고, 그 합으로
	 * 나눈 몫으로 각자의 점 수를 구한다. <b>모든 고리가 땅 위에 있다고 본다</b>(가장 바쁜
	 * 경우다). 실제로는 허공에 걸린 고리가 예산에서 빠지므로 이보다 적다.
	 */
	private static int pointsAtAge(long age) {
		double[] radii = new double[RINGS];
		int[] want = new int[RINGS];
		int wanted = 0;
		for (int ring = 0; ring < RINGS; ring++) {
			int step = TrialLandingShock.stepOf(age, ring, RING_INTERVAL);
			if (step < 1 || step > TRAVEL) {
				continue;
			}
			radii[ring] = TrialEnderPulse.radiusAt(step, TRAVEL, MAX_RADIUS);
			want[ring] = TrialLandingShock.ringWant(radii[ring]);
			wanted += want[ring];
		}
		int total = 0;
		for (int ring = 0; ring < RINGS; ring++) {
			total += TrialLandingShock.markPoints(radii[ring],
					TrialLandingShock.ringAllowance(want[ring], wanted));
		}
		return total;
	}

	/**
	 * 그 틱에 실제로 쓰이는 가장 큰 {@code stride}.
	 *
	 * <p>실행기는 <b>섬 위에 없는 고리를 예산에서 뺀다</b>({@code touchesGround}). 여기서는
	 * 반경이 섬을 넘으면 뺀 것으로 본다 — 예산이 가장 빡빡해지는 것이 섬 위에 여럿 있을 때라
	 * 그쪽이 곧 가장 나쁜 경우다.
	 */
	private static int strideAtAge(long age) {
		int worst = 1;
		for (Ring ring : onLandAt(age)) {
			int room = TrialLandingShock.edgeShare(ring.allowance());
			worst = Math.max(worst,
					TrialLandingShock.stride(TrialEnderPulse.edgePoints(ring.radius()), room));
		}
		return worst;
	}

	/** 그 틱에 가장 성긴 <b>쌓인</b> 앞머리 간격(칸). 한 바퀴 점자리로 나눈 값이다. */
	private static double accumulatedGapAtAge(long age) {
		double worst = 0.0;
		for (Ring ring : onLandAt(age)) {
			int room = TrialLandingShock.edgeShare(ring.allowance());
			int whole = TrialEnderPulse.edgePoints(ring.radius());
			int step = TrialLandingShock.stride(whole, room);
			int points = Math.min(whole, room * step);
			if (points > 0) {
				worst = Math.max(worst, (Math.PI * 2.0 * ring.radius()) / points);
			}
		}
		return worst;
	}

	/** 섬 위를 도는 고리 하나와 그 몫. */
	private record Ring(double radius, int allowance) {
	}

	/**
	 * 그 틱에 섬 위를 돌고 있는 고리들과 각자의 몫.
	 *
	 * <p>실행기의 첫 바퀴를 그대로 흉내 낸다 — 바라는 수를 모아 합을 내고, 그 합으로 몫을 나눈다.
	 */
	private static List<Ring> onLandAt(long age) {
		double[] radii = new double[RINGS];
		int[] want = new int[RINGS];
		int wanted = 0;
		for (int ring = 0; ring < RINGS; ring++) {
			int step = TrialLandingShock.stepOf(age, ring, RING_INTERVAL);
			if (step < 1 || step > TRAVEL) {
				continue;
			}
			double radius = TrialEnderPulse.radiusAt(step, TRAVEL, MAX_RADIUS);
			if (radius > ISLAND_RADIUS) {
				continue;
			}
			radii[ring] = radius;
			want[ring] = TrialLandingShock.ringWant(radius);
			wanted += want[ring];
		}
		List<Ring> live = new ArrayList<>();
		for (int ring = 0; ring < RINGS; ring++) {
			if (radii[ring] > 0.0) {
				live.add(new Ring(radii[ring],
						TrialLandingShock.ringAllowance(want[ring], wanted)));
			}
		}
		return live;
	}

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

	private static TrialCatalog.Risk.EnderPulse pulseCard() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:ender_pulse");
		assertNotNull(trial, "「엔더 파동」 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.EnderPulse pulse) {
				return pulse;
			}
		}
		throw new AssertionError("「엔더 파동」 카드에 EnderPulse 가 없다");
	}

	/** 컴파일된 우리 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String classBytes() {
		return read("/com/sharedfate/sync/TrialLandingShock.class");
	}

	/**
	 * 아무 클래스 파일이나 상수 풀을 문자열로 읽는다.
	 *
	 * <p>단상 계산이 한 벌인지를 보려면 <b>이 파일만으로는 모자라다</b> — 셈을 들고 있는 쪽과
	 * 쓰는 쪽 둘을 함께 봐야 「두 벌이 아니다」를 말할 수 있다.
	 */
	private static String read(String path) {
		try (InputStream in = TrialLandingShock.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
