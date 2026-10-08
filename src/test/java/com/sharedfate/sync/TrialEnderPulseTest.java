package com.sharedfate.sync;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「엔더 파동」에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>파티클·소리·구속은 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 카드가
 * 망가지는 길은 거의 전부 기하와 타이밍 쪽이다 — <b>고리가 한 틱에 0.5칸씩 건너뛰어 사람이 통째로
 * 빠진다</b>, <b>같은 파동에 두 번 묶인다</b>, <b>고리가 닿은 적 없는 42칸 밖 사람이 묶인다</b>,
 * <b>봐 주기로 한 창이 보이는 고리와 어긋난다</b>.
 *
 * <p>피해가 없는 카드라 이것들은 <b>돌려 봐도 잘 안 보인다.</b> 갑자기 발이 묶이면 사람은 그것을
 * 「랙」이나 「다른 카드」로 읽지 버그로 읽지 않는다.
 *
 * <p>⚠ <b>「보이는가」도 값으로 묻는다.</b> 사람이 이 카드를 「어디 갔지」라고 물었고 답이
 * 「없어진 게 아니라 안 보였다」였다. 그래서 42칸에서의 <b>점 사이 간격</b>과 앞머리가 <b>세로로
 * 서는 높이</b>를 숫자로 붙들어 둔다({@link TrialEnderPulse#edgeGap},
 * {@link TrialEnderPulse#riseHeight}) — 연출은 눈으로만 확인되는 종류라 시험이 안 보면 다음에 또
 * 조용히 바닥에 눕는다.
 */
class TrialEnderPulseTest {

	private static final long GRANTED = 1000L;

	/** 「엔더 파동」의 카드 값. 시험이 현실과 붙어 있으려면 실제 카드에서 뽑아 와야 한다. */
	private static TrialCatalog.Risk.EnderPulse card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:ender_pulse");
		if (trial == null || trial.risks().size() != 1) {
			return fail("「엔더 파동」 카드가 없다");
		}
		// 봉인 인터페이스가 늘어나도 이 시험이 깨지지 않게 switch 대신 instanceof 로 본다.
		if (trial.risks().getFirst() instanceof TrialCatalog.Risk.EnderPulse pulse) {
			return pulse;
		}
		return fail("「엔더 파동」이 EnderPulse 가 아니다");
	}

	// ------------------------------------------------------------------ 퍼지는 모양

	@Test
	void 고리는_중앙에서_출발해_적힌_반경에서_멈춘다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		assertEquals(0.0, TrialEnderPulse.radiusAt(0, pulse.travelTicks(), pulse.maxRadius()),
				1.0E-9, "출발 틱에 이미 퍼져 있으면 어디서 왔는지 읽을 수 없다");
		assertEquals(pulse.maxRadius(),
				TrialEnderPulse.radiusAt(pulse.travelTicks(), pulse.travelTicks(),
						pulse.maxRadius()),
				1.0E-9, "적힌 시간에 적힌 반경까지 가야 흑요석 기둥에 닿는다");
	}

	@Test
	void 고리는_멈추거나_되돌아가지_않는다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		double previous = -1.0;
		for (int step = 0; step <= pulse.travelTicks(); step++) {
			double radius = TrialEnderPulse.radiusAt(step, pulse.travelTicks(), pulse.maxRadius());
			assertTrue(radius > previous, "고리가 멈추면 그 틱에 아무도 판정을 안 받는다: " + step);
			previous = radius;
		}
	}

	@Test
	void 뒷자락은_앞머리보다_창_하나만큼_뒤에_있다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		// 「보이는 고리」와 「봐 주는 구간」이 한 값에서 나온다는 것을 숫자로 확인한다. 둘이 따로
		// 적히면 사람이 본 것과 판정이 어긋나고, 그 어긋남은 화면에 아무 흔적도 남기지 않는다.
		for (int step = TrialEnderPulse.JUMP_WINDOW_TICKS; step <= pulse.travelTicks(); step++) {
			double edge = TrialEnderPulse.radiusAt(step - TrialEnderPulse.JUMP_WINDOW_TICKS,
					pulse.travelTicks(), pulse.maxRadius());
			assertEquals(edge,
					TrialEnderPulse.judgeRadius(step, pulse.travelTicks(), pulse.maxRadius()),
					1.0E-9, "step " + step);
		}
	}

	@Test
	void 뒷자락은_적힌_반경을_넘어가지_않는다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		// 앞머리가 멈춘 뒤에도 뒷자락은 다가온다. 자르지 않으면 고리가 닿은 적 없는 기둥 바깥
		// 사람까지 묶이고, 그러면 「보이는 것이 전부」라던 카드가 안 보이는 곳에서 사람을 묶는다.
		for (int step = 0; step <= pulse.travelTicks() + 200; step++) {
			assertTrue(TrialEnderPulse.judgeRadius(step, pulse.travelTicks(), pulse.maxRadius())
							<= pulse.maxRadius() + 1.0E-9,
					"step " + step);
		}
	}

	// ------------------------------------------------------------------ 판정 구간

	@Test
	void 어느_거리에_서_있어도_정확히_한_번_판정을_받는다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		int alive = TrialEnderPulse.lifetime(pulse.interval(), pulse.travelTicks());
		// 고리는 한 틱에 0.5칸씩 건너뛴다. 「반경과 거리가 같은가」로 물으면 대부분이 빠지고,
		// 구간을 닫힌 것으로 잡으면 경계에 선 사람이 두 번 걸린다.
		//
		// 거리는 더해 가지 않고 번호에서 뽑는다. 0.01 을 사천 번 더하면 마지막 값이 42 를 아주
		// 조금 넘어, 실제로는 멀쩡한 구현인데 시험만 깨진다.
		int samples = 4200;
		for (int index = 0; index <= samples; index++) {
			double distance = index * pulse.maxRadius() / samples;
			int hits = 0;
			for (int step = 0; step <= alive; step++) {
				double outer = TrialEnderPulse.judgeRadius(step, pulse.travelTicks(),
						pulse.maxRadius());
				double inner = TrialEnderPulse.judgeRadius(step - 1, pulse.travelTicks(),
						pulse.maxRadius());
				if (distance > inner && distance <= outer) {
					hits++;
				}
			}
			assertEquals(1, hits, "거리 " + distance + " 에서 판정이 " + hits + "번이다");
		}
	}

	@Test
	void 고리가_닿지_않는_바깥에는_판정이_없다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		int alive = TrialEnderPulse.lifetime(pulse.interval(), pulse.travelTicks());
		for (double distance = pulse.maxRadius() + 0.01; distance <= 120.0; distance += 0.25) {
			for (int step = 0; step <= alive; step++) {
				double outer = TrialEnderPulse.judgeRadius(step, pulse.travelTicks(),
						pulse.maxRadius());
				double inner = TrialEnderPulse.judgeRadius(step - 1, pulse.travelTicks(),
						pulse.maxRadius());
				assertFalse(distance > inner && distance <= outer,
						"기둥 밖 " + distance + " 가 step " + step + " 에 묶였다");
			}
		}
	}

	@Test
	void 고리는_앞머리가_멈춘_뒤에도_창만큼_더_산다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		assertEquals(pulse.travelTicks() + TrialEnderPulse.JUMP_WINDOW_TICKS,
				TrialEnderPulse.lifetime(pulse.interval(), pulse.travelTicks()),
				"여기서 깎으면 가장 바깥에 선 사람만 봐 주는 창을 못 받는다");
	}

	@Test
	void 고리는_제_주기를_넘겨_살지_않는다() {
		// 카드 값이 주기보다 긴 궤적을 적으면 다음 고리가 앞 고리를 밀어내고, 앞 고리는 도중에
		// 그냥 사라진다 — 아직 자기 차례를 기다리던 사람 눈앞에서 증발한다.
		assertTrue(TrialEnderPulse.lifetime(40, 200) <= 39,
				"주기보다 오래 사는 고리를 만들면 예고가 거짓말을 한다");
		assertEquals(0, TrialEnderPulse.lifetime(1, 200));
	}

	// ------------------------------------------------------------------ 닿는 시각

	@Test
	void 앞머리가_닿는_시각은_중앙에서_바깥으로_늦어진다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		assertEquals(0, TrialEnderPulse.reachTick(0.0, pulse.travelTicks(), pulse.maxRadius()),
				"중앙에 선 사람에게는 출발하는 그 틱이 도착이다");
		assertEquals(pulse.travelTicks(),
				TrialEnderPulse.reachTick(pulse.maxRadius(), pulse.travelTicks(),
						pulse.maxRadius()),
				"가장 바깥은 퍼지는 시간이 다 지나야 닿는다");

		int previous = -1;
		for (double distance = 0.0; distance <= pulse.maxRadius(); distance += 0.1) {
			int tick = TrialEnderPulse.reachTick(distance, pulse.travelTicks(), pulse.maxRadius());
			assertTrue(tick >= previous, "먼 사람에게 먼저 닿으면 경고 순서가 뒤집힌다: " + distance);
			previous = tick;
		}
	}

	@Test
	void 앞머리가_닿는_시각은_판정_구간보다_빠르다() {
		// 경고는 「앞머리가 언제 오는가」로 울리고 판정은 뒷자락이 내린다. 둘이 뒤집히면
		// 「이미 묶인 뒤에 곧 온다는 경고를 듣는」 카드가 된다.
		TrialCatalog.Risk.EnderPulse pulse = card();
		int alive = TrialEnderPulse.lifetime(pulse.interval(), pulse.travelTicks());
		// 고리가 한 틱에 지나는 칸의 <b>한가운데</b>를 본다. 경계에 딱 걸치는 거리는 올림과 구간
		// 판정이 같은 부동소수를 다르게 반올림할 수 있어, 구현이 아니라 시험이 흔들린다.
		for (int cell = 0; cell < pulse.travelTicks(); cell++) {
			double distance = (cell + 0.5) * pulse.maxRadius() / pulse.travelTicks();
			int reach = TrialEnderPulse.reachTick(distance, pulse.travelTicks(),
					pulse.maxRadius());
			for (int step = 0; step <= alive; step++) {
				double outer = TrialEnderPulse.judgeRadius(step, pulse.travelTicks(),
						pulse.maxRadius());
				double inner = TrialEnderPulse.judgeRadius(step - 1, pulse.travelTicks(),
						pulse.maxRadius());
				if (distance > inner && distance <= outer) {
					assertTrue(reach <= step,
							"거리 " + distance + " 는 step " + step + " 에 묶이는데 앞머리는 "
									+ reach + " 에 온다");
					assertTrue(step - reach <= TrialEnderPulse.JUMP_WINDOW_TICKS,
							"봐 주는 창보다 늦게 판정하면 뛰고 나서 착지한 사람까지 통과한다: "
									+ distance);
				}
			}
		}
	}

	// ------------------------------------------------------------------ 거리 재는 법

	@Test
	void 거리는_높이를_보지_않는다() {
		// 기둥 위에 올라간 사람의 거리가 세로 때문에 멀어지면, 고리가 발밑을 지나가는데 판정만
		// 비껴간다. 고리는 바닥에 그려지므로 사람이 본 것과 어긋난다.
		assertEquals(10.0, TrialEnderPulse.distanceFromCenter(new Vec3(10.0, 63.0, 0.0)), 1.0E-9);
		assertEquals(10.0, TrialEnderPulse.distanceFromCenter(new Vec3(10.0, 103.0, 0.0)), 1.0E-9);
		assertEquals(5.0, TrialEnderPulse.distanceFromCenter(new Vec3(3.0, 0.0, -4.0)), 1.0E-9);
	}

	// ------------------------------------------------------------------ 지형을 탄다

	/**
	 * 고리가 지나는 칸마다 그 자리의 지표 위에 찍힌다.
	 *
	 * <p>사람이 「y좌표가 달라지면 아예 이상해진다」고 말한 자리다. 중앙에서 한 번 재어 한
	 * 바퀴에 쓰면 높이가 달라지는 데서 고리가 파묻히거나 뜬다.
	 */
	@Test
	void 고리는_점마다_그_자리의_지표를_묻는다() throws IOException {
		String bytes = classBytes();
		assertTrue(bytes.contains("surfaceAt"),
				"한 높이로 그리면 높이가 달라지는 자리에서 고리가 파묻히거나 뜬다");
		assertFalse(bytes.contains("getHeightmapPos"),
				"월드 쪽 조회는 부를 때마다 청크를 다시 찾고 BlockPos 를 하나씩 만든다."
						+ " 한 틱에 사백 번 부르면 그 둘이 사백 배가 된다");

		String ground = bytesOf("/com/sharedfate/sync/TrialEnderPulse$Ground.class");
		assertTrue(ground.contains("getChunkNow"),
				"올라온 청크만 보지 않으면 표식을 그리자고 청크를 불러오게 된다 —"
						+ " 이 카드에서 그것이 가장 비싸다");
	}

	/**
	 * ⚠ 판정도 고리를 따라 올라간다.
	 *
	 * <p>보이는 고리가 지형을 타는데 판정이 평평하면 <b>지붕 밑에 선 사람이 머리 위로 지나간
	 * 고리에 묶인다.</b> 사람이 고쳐 달라고 한 것이 정확히 그 어긋남이다.
	 */
	@Test
	void 판정은_내_발밑을_지나간_고리만_센다() {
		assertTrue(TrialEnderPulse.atRingHeight(64.0, 64), "지표에 서 있으면 걸린다");
		assertTrue(TrialEnderPulse.atRingHeight(63.5, 64),
				"하프 블록 위다 — 하이트맵이 한 칸 위를 돌려주므로 반 칸은 반드시 봐 줘야 한다");
		assertTrue(TrialEnderPulse.atRingHeight(65.25, 64),
				"점프 꼭대기다. 어차피 창이 먼저 통과시키지만 여기서 먼저 걸러도 안 된다");
		assertFalse(TrialEnderPulse.atRingHeight(58.0, 64),
				"굴 속이다 — 고리는 여섯 칸 위를 지나갔고 이 사람 눈에는 보이지도 않았다");
		assertFalse(TrialEnderPulse.atRingHeight(74.0, 64),
				"열 칸 위에 떠 있다 — 발밑을 지나간 고리가 아니다");

		assertTrue(TrialEnderPulse.JUDGE_VERTICAL_REACH >= 1.0,
				"반 블록 발판(하이트맵이 한 칸 위를 돌려준다)을 못 덮으면 계단 위에 선 사람이"
						+ " 눈앞으로 지나가는 고리를 그냥 통과한다");
		assertTrue(TrialEnderPulse.JUDGE_VERTICAL_REACH < 3.0,
				"키우면 지붕 밑에 선 사람이 다시 묶인다 — 고친 어긋남이 그대로 돌아온다");
	}

	@Test
	void 땅이_없으면_그리지도_판정하지도_않는다() {
		// 허공에 점을 찍으면 고리가 까마득한 아래에 떠 「저기가 바닥이다」라고 거짓말을 한다.
		// 판정만 남기면 반대로 「아무것도 안 보이는데 걸린다」가 된다.
		assertFalse(TrialEnderPulse.atRingHeight(63.0, TrialEnderPulse.NO_GROUND),
				"땅이 없는 칸에는 고리를 그리지도 않았다");
		assertFalse(TrialEnderPulse.atRingHeight(TrialEnderPulse.NO_GROUND,
						TrialEnderPulse.NO_GROUND),
				"표시값끼리 맞아떨어져 참이 되면 안 된다");
	}

	// ------------------------------------------------------------------ 점 예산

	@Test
	void 한_틱에_쓰는_점이_예산_안이다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		// 「낙뢰」가 반경 3 짜리 고리 열 개로 쓰는 400점이 이 저장소의 예산이다. 반경이 커질수록
		// 점이 끝없이 느는 구현이면 이 카드 하나가 파티클 패킷만으로 틱을 민다.
		assertEquals(400, TrialEnderPulse.MAX_POINTS_PER_TICK,
				"예산을 바꾸려면 「낙뢰」·「연쇄 포격」과 함께 봐야 한다");
		for (double radius = 0.1; radius <= pulse.maxRadius() + 10.0; radius += 0.1) {
			int points = TrialEnderPulse.edgePoints(radius) + TrialEnderPulse.wakePoints(radius);
			assertTrue(points <= TrialEnderPulse.MAX_POINTS_PER_TICK,
					"반경 " + radius + " 에서 " + points + "점이 나간다");
		}
	}

	@Test
	void 고리가_커져도_끊겨_보이지_않는다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		// 상한에 걸린 뒤로는 점이 안 늘어 간격만 벌어진다. 어디까지 벌어지는지를 세어 두지 않으면
		// 상한이 조용히 고리를 점선으로 만든다. 이 카드는 피해가 없어 보이는 것이 전부다.
		double worst = TrialEnderPulse.edgeGap(pulse.maxRadius());
		assertTrue(worst <= TrialWarning.POINT_GAP * 3.0,
				"가장 큰 고리에서 점이 " + worst + "칸씩 벌어진다");
		for (double radius = 0.1; radius <= pulse.maxRadius(); radius += 0.1) {
			assertTrue(TrialEnderPulse.edgeGap(radius) <= worst + 1.0E-9,
					"반경 " + radius + " 가 가장 큰 고리보다 성기다");
		}
	}

	// ------------------------------------------------------------------ 42칸에서 보이는가

	/**
	 * ⚠ 앞머리가 <b>세로로 선다</b> — 사람이 「어디 갔는지」 물은 뒤에 고친 자리다.
	 *
	 * <p>없어진 카드가 아니라 <b>안 보이던 카드</b>였다. 바닥에만 찍으면 서서 보는 눈높이에서
	 * 고리가 시선과 거의 나란해 42칸 밖에서는 한 줄로 뭉개진다 — 「착지 충격」이 반경 75 에서 먼저
	 * 들은 말과 같은 까닭이다.
	 *
	 * <p>높이를 시험이 보는 까닭은 <b>눈으로만 확인되는 값</b>이기 때문이다. 다음 사람이 2.0 을
	 * 20 으로 바꿔도 컴파일도 로그도 조용한데, 그때 서는 것은 <b>다른 카드의 바닥 표식을 가리는
	 * 벽</b>이다.
	 */
	@Test
	void 앞머리가_사람_키만큼_선다() {
		double rise = TrialEnderPulse.riseHeight(TrialEnderPulse.EDGE_RISE_SPEED);
		assertEquals(1.878, rise, 1.0E-3,
				"26.3 CritParticle(속도×0.4 · 마찰 0.7 · 중력 0.5)을 수명 하한 4틱으로 굴린 값이다."
						+ " 달라졌다면 판이 올라 물리가 바뀐 것이니 상수 설명부터 다시 읽을 것");

		double human = 1.8;
		assertTrue(rise >= human,
				"올라가는 높이가 " + rise + "칸이다 — 사람 키(" + human + ")보다 낮으면 벽이 아니라"
						+ " 조금 두꺼운 바닥 선이고, 그러면 「안 보인다」가 그대로 돌아온다");
		assertTrue(rise < TrialEnderStorm.COLUMN_HEIGHT,
				"「엔더폭풍」이 기둥을 " + TrialEnderStorm.COLUMN_HEIGHT + "칸에서 멈춘 것은 세로로"
						+ " 선 것이 다른 카드의 바닥 표식을 가리기 때문이다");
		assertEquals(0.0, TrialEnderPulse.riseHeight(0.0),
				"0 이면 제자리에 찍는다 — 몸통이 그 갈래로 간다");
		assertEquals(0.0, TrialEnderPulse.riseHeight(-2.0), "음수는 땅으로 쏘는 것이다");
	}

	/**
	 * ⚠⚠ <b>가장 큰 고리에서 벽이 점 사이보다 길다</b> — 「42칸에서 보인다」를 값으로 묻는 자리다.
	 *
	 * <p>점 수가 상한({@link TrialEnderPulse#EDGE_MAX_POINTS})에 걸려 있으므로 반경 42 에서 점
	 * 사이가 1.1칸까지 벌어진다. 그 점들이 바닥에 누워 있으면 <b>간격만 보이고 줄은 안 보인다.</b>
	 * 세로로 선 길이가 간격보다 길어야 띄엄띄엄한 점이 아니라 <b>이어진 울타리</b>로 읽힌다.
	 *
	 * <p>이 부등식이 깨지는 길이 둘이다 — 쏘는 속도를 줄이거나, 카드의 최대 반경을 키워 간격을
	 * 벌리는 것. 둘 다 코드에서는 아무 소리도 안 난다.
	 */
	@Test
	void 가장_먼_고리에서도_벽이_점_사이보다_길다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		double gap = TrialEnderPulse.edgeGap(pulse.maxRadius());
		double rise = TrialEnderPulse.riseHeight(TrialEnderPulse.EDGE_RISE_SPEED);
		assertTrue(rise > gap,
				"반경 " + pulse.maxRadius() + " 에서 점 사이가 " + gap + "칸인데 세로로 선 길이가 "
						+ rise + "칸이다 — 간격보다 짧으면 벽이 아니라 점선이다");
	}

	/**
	 * 벽이 <b>점을 한 개도 더 쓰지 않는다.</b>
	 *
	 * <p>이 카드는 예산(400)을 통째로 쓰고 있어 <b>늘릴 자리가 없었다.</b> 그래서 개수 0 으로
	 * 보내 뒤 값을 속도로 읽게 했다 — 그 갈래도 파티클을 정확히 하나 만들므로 점 수도 패킷 수도
	 * 제자리에 찍는 것과 같다.
	 *
	 * <p>「잘 안 보인다」의 답을 <b>점에서</b> 찾으려는 사람은 여기서 멈춰야 한다. 상한을 올리면
	 * 이 카드 하나가 예산을 넘고, 시련은 전투가 끝날 때까지 <b>쌓인다.</b>
	 */
	@Test
	void 벽은_점을_한_개도_더_쓰지_않는다() {
		assertEquals(TrialEnderPulse.EDGE_MAX_POINTS + TrialEnderPulse.WAKE_MAX_POINTS,
				TrialEnderPulse.MAX_POINTS_PER_TICK,
				"한쪽만 고치면 예산이 조용히 깨진다");
		assertEquals(400, TrialEnderPulse.MAX_POINTS_PER_TICK,
				"세로로 세우는 것은 공짜다 — 여기가 400 에서 움직였다면 점으로 답을 찾은 것이다");
	}

	/**
	 * ⚠ 두 고리가 <b>같은 높이로</b> 선다.
	 *
	 * <p>「착지 충격」과 이 카드는 판정·그리기를 이 파일의 같은 함수로 나눠 쓴다. 벽 높이가 갈리면
	 * 사람이 <b>두 고리를 다른 것으로 배운다</b> — 「고리는 뛰면 피한다」를 한 번만 배우게 하려고
	 * 속도까지 맞춰 둔 판이다.
	 *
	 * <p>값이 두 곳에 적혀 있는 것은 <b>지금 그 파일을 다른 사람이 쓰고 있어</b> 한쪽으로 모으지
	 * 못했기 때문이다. 옮길 방향은 그쪽이 이 파일을 가리키는 것이고, 그때 이 시험은 지워도 된다.
	 */
	@Test
	void 착지_충격과_같은_높이로_선다() {
		assertEquals(TrialLandingShock.EDGE_RISE_SPEED, TrialEnderPulse.EDGE_RISE_SPEED, 1.0E-9,
				"두 고리의 벽 높이가 갈렸다 — 한쪽만 고친 것이다");
		assertEquals(TrialLandingShock.riseHeight(TrialLandingShock.EDGE_RISE_SPEED),
				TrialEnderPulse.riseHeight(TrialEnderPulse.EDGE_RISE_SPEED), 1.0E-9,
				"물리 계산이 두 곳에서 갈라졌다");
	}

	@Test
	void 작은_고리는_저장소가_정한_하한만큼_찍는다() {
		// 하한을 여기서 다시 적지 않고 TrialWarning 에서 빌려 온다. 같은 숫자를 두 곳에 적으면
		// 한쪽만 고쳐진다.
		assertEquals(TrialWarning.ringPoints(1.0), TrialEnderPulse.edgePoints(1.0));
		assertEquals(TrialWarning.ringPoints(1.0), TrialEnderPulse.wakePoints(1.0));
		assertEquals(0, TrialEnderPulse.edgePoints(0.0), "반경 0 인 출발 틱에는 그릴 것이 없다");
		assertEquals(0, TrialEnderPulse.edgePoints(-1.0));
	}

	// ------------------------------------------------------------------ 카드 값과 약속

	@Test
	void 카드에_적힌_값을_그대로_쓴다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		assertEquals(400, pulse.interval(), "20초");
		assertEquals(80, pulse.travelTicks(), "4초");
		assertEquals(42.0, pulse.maxRadius(), 1.0E-9, "흑요석 기둥이 서 있는 원");
		assertEquals(60, pulse.rootTicks(), "3초");
	}

	@Test
	void 이_카드는_피해를_세지_않는다() {
		// 피해가 없다는 것이 이 카드의 정의다. 언젠가 「조금만」 붙이는 사람이 있으면
		// TrialRisks 의 최악치 계산부터 거짓이 된다.
		assertEquals(0.0F, TrialRisks.worstCaseTickDamage(card()), 1.0E-9);
	}

	@Test
	void 봐_주는_창은_영이_아니다() {
		// 0 이면 판정이 다시 한 틱짜리가 되고, 사람이 눈으로 맞춘 점프가 서버에 닿기 전에 묶인다.
		assertTrue(TrialEnderPulse.JUMP_WINDOW_TICKS > 0);
		// 창이 퍼지는 시간만큼 길어지면 고리 전체가 「두께」가 되어 아무도 안 걸린다.
		assertTrue(TrialEnderPulse.JUMP_WINDOW_TICKS < card().travelTicks());
	}

	@Test
	void 구속은_카드에_적힌_세기다() {
		// 레벨 I 이 증폭 0 이므로 III 은 2 다. 여기를 올리면 카드 설명과 실제가 갈라진다.
		assertEquals(2, TrialEnderPulse.ROOT_AMPLIFIER);
	}

	// ------------------------------------------------------------------ 클래스 파일이 지키는 약속

	@Test
	void 고리는_긴_거리로_나간다() throws IOException {
		// 이 고리는 반경 42 까지 간다. 짧은 형태는 서버에서 32칸으로 잘리고 클라이언트가 한 번 더
		// 거르므로, 되돌리면 바깥쪽 절반이 아무에게도 안 그려진다 — 그런데 빌드도 로그도 조용하다.
		// 피해가 없는 카드라 증상은 「갑자기 발이 묶인다」로만 나타난다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 고리 바깥쪽이 아무에게도 안 보인다");
	}

	@Test
	void 얼어붙은_판에서도_도는_시각을_쓴다() throws IOException {
		// 룰렛이 도는 동안 ServerTickRateManager 가 판을 멈추면 getGameTime 도 멈춘다. 다른
		// 실행기가 now 를 인자로 받는 이유가 그것이다.
		assertFalse(classBytes().contains("getGameTime"),
				"월드 시각을 직접 물으면 판이 멈춘 동안 고리가 얼어붙는다");
	}

	/**
	 * ⚠⚠ 미는 자리는 <b>천장 둘을 지나고, 덮어쓰고, 띄우지 않는다.</b>
	 *
	 * <p>전에는 이 자리에 「아무도 밀지 않는다」가 있었다({@code setDeltaMovement} 가 바이트에 없음).
	 * 2026-10-04 사람이 「엔더 파동 구속과 밀치는 것도 있게」라고 해서 뒤집었고, 그 대신 미는 줄이
	 * 지나야 할 것을 이름으로 붙든다. <b>세로 자름</b>({@code syncVelocity}·{@code TrialVelocity}·
	 * {@code getKnownMovement}·바닐라 {@code knockBack} 무손)은 속도를 내려 보내는 자리 전부를 한
	 * 곳에서 보는 {@code TrialVelocityTest.속도를_내려보내는_자리_넷이_모두_자름을_지난다} 가 본다 —
	 * 여기서 다시 보면 시험이 두 벌이 된다.
	 */
	@Test
	void 미는_줄은_천장을_지나고_덮어쓴다() throws IOException {
		String bytes = classBytes();
		assertTrue(bytes.contains("setDeltaMovement"), "밀치기가 사라졌다 — 사람이 시킨 것이다");
		assertTrue(bytes.contains("outwardLimit") && bytes.contains("pushDistance"),
				"「착지 충격」의 천장(반경 34)을 안 지난다 — 낙사 한 번이 월드 삭제다");
		assertTrue(bytes.contains("groundedReach"),
				"땅이 끊기기 전에서 멈추지 않는다 — 섬은 둥글지 않다");
		assertFalse(bytes.contains("addDeltaMovement"),
				"속도를 더하면 달리던 속도가 얹혀 천장이 계산한 목적지를 지나친다 — 덮어쓸 것");
		assertFalse(bytes.contains("setIgnoreFallDamageFromCurrentImpulse"),
				"띄우지 않는 카드라 낙하를 면제할 일이 없다 — 띄우게 됐다는 뜻이면 천장부터 다시 볼 것");
		assertFalse(bytes.contains("hurtServer"), "이 카드에는 피해가 없다");
	}

	@Test
	void 구속은_밀치기와_함께_그대로_걸린다() throws IOException {
		// 밀치기를 더하면서 구속을 빼거나 바꾸지 않았다. 사람 말이 「구속과 밀치는 것도」다.
		String bytes = classBytes();
		assertTrue(bytes.contains("SLOWNESS"), "구속이 사라졌다");
		assertTrue(bytes.contains("addEffect"), "구속을 안 건다");
		assertEquals(2, TrialEnderPulse.ROOT_AMPLIFIER, "구속 III");
		assertEquals(60, card().rootTicks(), "3초");
	}

	// ------------------------------------------------------------------ 밀치기의 세기와 천장

	/** 그 처음 속도로 <b>바닥에 붙은</b> 몸이 끝까지 가는 거리. 감쇠 0.546 이다. */
	private static double groundTravel(double speed) {
		return speed / (1.0 - TrialEnderPulse.GROUND_DRAG);
	}

	/** 그 처음 속도로 <b>끝까지 떠 있는</b> 몸이 가는 거리. 감쇠 0.91 이다. */
	private static double airTravel(double speed) {
		return speed / (1.0 - TrialEnderStorm.AIR_DRAG);
	}

	/** 반경 {@code edge} 안이 전부 땅인 둥근 섬. */
	private static TrialLandingShock.GroundProbe island(double edge) {
		return (x, z) -> Math.sqrt(x * x + z * z) <= edge;
	}

	@Test
	void 천장이_없다면_바닥에서_사람이_고른_12칸을_간다() {
		// 사람이 고른 것은 「강하게 · 약 12칸」이고 그것은 바닥 실제 거리다. 부탁하는 거리는
		// 그것을 공중 보정 비율로 나눈 60.5칸이고, 그 부탁이 바닥에서 정확히 12칸이 되어야 한다.
		assertEquals(12.0, TrialEnderPulse.PUSH_GROUND_BLOCKS, 0.0, "사람이 정한 값이다");
		assertEquals(60.533, TrialEnderPulse.pushRequest(), 1.0E-3);
		assertEquals(12.0,
				groundTravel(TrialEnderPulse.pushSpeed(TrialEnderPulse.pushRequest(), false)),
				1.0E-9, "부탁하는 거리와 바닥 실제가 어긋났다 — 비율을 한쪽만 고쳤다");
	}

	/**
	 * ⚠⚠ <b>바닥 12칸은 이 섬에서 다 안 나온다</b> — 천장이 먼저 문다. 실제 거리를 표로 못박는다.
	 *
	 * <p>사람이 「약하다」고 하면 이 표가 답이다. 부탁(60.5)을 올려도 한 칸도 안 늘고, 늘리려면
	 * 천장(34)을 무르는 수밖에 없는데 그것은 낙사 여유를 깎는다.
	 */
	@Test
	void 바닥에서_맞으면_실제로는_천장까지_남은_길의_오분의_일을_간다() {
		double max = card().maxRadius();
		TrialLandingShock.GroundProbe flat = island(1000.0);
		double[][] table = {{0.001, 6.740}, {10.0, 4.758}, {20.0, 2.775}, {30.0, 0.793}};
		for (double[] row : table) {
			double reach = TrialEnderPulse.pushReach(row[0], 0.0, max, flat);
			assertEquals(row[1], groundTravel(TrialEnderPulse.pushSpeed(reach, false)), 1.0E-3,
					"중앙에서 " + row[0] + "칸에 선 사람이 바닥에서 밀리는 거리");
		}
		assertTrue(groundTravel(TrialEnderPulse.pushSpeed(
						TrialEnderPulse.pushReach(0.001, 0.0, max, flat), false))
						< TrialEnderPulse.PUSH_GROUND_BLOCKS,
				"천장이 12칸보다 넉넉해졌다 — 천장을 무른 것인지 먼저 볼 것");
	}

	/**
	 * ⚠⚠ <b>섬 끝에서 맞아도, 맞고 곧바로 뛰어도</b> 반경 34 를 못 넘는다.
	 *
	 * <p>이 카드에서 맞는 사람은 거의 다 「늦게 뛴 사람」이라 맞은 직후에 뜬다. 그래서 최악을
	 * <b>끝까지 떠 있는 경우</b>로 센다 — 속도를 공중 감쇠로 잡았으므로 그때 가는 거리가 곧
	 * {@code pushReach} 다. 고리가 닿는 반경 42 안을 각도·거리마다 훑는다.
	 */
	@Test
	void 어디서_맞고_곧바로_뛰어도_반경_34_를_못_넘는다() {
		double max = card().maxRadius();
		double ceiling = TrialRisks.ARENA_RADIUS - TrialLandingShock.EDGE_MARGIN;
		TrialLandingShock.GroundProbe flat = island(1000.0);
		for (int spoke = 0; spoke < 36; spoke++) {
			double angle = Math.PI * 2.0 * spoke / 36;
			for (double r = 0.25; r <= max; r += 0.25) {
				double x = Math.cos(angle) * r;
				double z = Math.sin(angle) * r;
				double reach = TrialEnderPulse.pushReach(x, z, max, flat);
				double worst = airTravel(TrialEnderPulse.pushSpeed(reach, false));
				// 천장 밖(34~42)에 이미 선 사람은 제자리에 남는 것이 답이다.
				assertTrue(r + worst <= Math.max(ceiling, r) + 1.0E-9,
						"중앙에서 " + r + "칸에 선 사람이 반경 " + (r + worst) + " 까지 간다");
				if (r >= ceiling) {
					assertEquals(0.0, reach, 0.0, "천장 밖에 선 사람을 또 밀었다");
				}
			}
		}
	}

	@Test
	void 땅이_끊기면_그_앞에서_멈춘다() {
		double max = card().maxRadius();
		// 섬이 반경 25 에서 끝나는 쪽 — 천장(34)보다 안쪽에 허공이 있다.
		TrialLandingShock.GroundProbe small = island(25.0);
		for (double r = 1.0; r <= 25.0; r += 0.5) {
			double worst = airTravel(TrialEnderPulse.pushSpeed(
					TrialEnderPulse.pushReach(r, 0.0, max, small), false));
			assertTrue(r + worst <= 25.0 + 1.0E-9, r + "칸에서 맞아 허공(25칸 밖)으로 나갔다");
		}
		// 길 한가운데 폭 1 짜리 구멍(반경 20~21). 건너편에 땅이 있어도 건너가지 않는다.
		TrialLandingShock.GroundProbe holed = (x, z) -> {
			double d = Math.sqrt(x * x + z * z);
			return d < 20.0 || d > 21.0;
		};
		double worst = airTravel(TrialEnderPulse.pushSpeed(
				TrialEnderPulse.pushReach(15.0, 0.0, max, holed), false));
		assertTrue(15.0 + worst < 20.0, "구멍 너머로 밀었다 — 밀려가는 몸은 구멍으로 떨어진다");
	}

	@Test
	void 중앙에_정확히_겹쳐_있으면_방향을_지어내지_않는다() {
		assertEquals(0.0,
				TrialEnderPulse.pushReach(0.0, 0.0, card().maxRadius(), island(1000.0)), 0.0);
	}

	@Test
	void 공중이면_바닥과_같은_거리만_간다() {
		// 사람 말 「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든?」 — 같은 속도가 공중에서
		// 다섯 배를 가는 것을 비율로 맞춘다. 점프가 이득도 손해도 아니어야 한다.
		for (double d = 0.5; d <= 34.0; d += 0.5) {
			assertEquals(groundTravel(TrialEnderPulse.pushSpeed(d, false)),
					airTravel(TrialEnderPulse.pushSpeed(d, true)), 1.0E-9,
					d + "칸 부탁에서 공중과 바닥이 갈렸다");
		}
		assertEquals(5.045,
				airTravel(TrialEnderPulse.pushSpeed(10.0, false))
						/ groundTravel(TrialEnderPulse.pushSpeed(10.0, false)),
				1.0E-3, "보정이 없으면 공중이 다섯 배를 간다 — 이 비율이 막는 것");
	}

	@Test
	void 공중_판정은_깃발_하나로_하지_않는다() {
		int surface = 64;
		assertFalse(TrialEnderPulse.airborne(true, 64.0, surface), "땅에 서 있다");
		assertTrue(TrialEnderPulse.airborne(false, 64.0, surface), "깃발이 떠 있다고 하면 떠 있다");
		// 깃발은 클라이언트가 보낸 것이다. 땅에 있다고 해도 하이트맵이 높으면 떠 있는 것으로 본다 —
		// 틀리는 방향이 「덜 민다」여야 한다.
		assertTrue(TrialEnderPulse.airborne(true, 64.6, surface), "하이트맵이 떠 있다고 한다");
		assertFalse(TrialEnderPulse.airborne(true, 64.5, surface), "반 블록 한 칸은 땅이다");
		assertTrue(TrialEnderPulse.airborne(true, 64.0, TrialEnderPulse.NO_GROUND),
				"발밑이 허공이면 떠 있다");
	}

	/**
	 * 날개 퍼덕이기와 <b>같은 값</b>이다.
	 *
	 * <p>값이 두 곳에 적혀 있는 것은 카드가 패턴 파일을 부르면 의존이 거꾸로 흐르고, 지금 그 파일을
	 * 다른 사람이 쓰고 있어 한쪽으로 모으지 못했기 때문이다. 옮길 방향은 이 값들이
	 * {@code TrialEnderStorm.AIR_DRAG} 옆으로 가고 패턴이 그것을 빌리는 것이고, 그때 이 시험은
	 * 지워도 된다.
	 */
	@Test
	void 공중_보정은_날개_퍼덕이기와_같은_값이다() {
		assertEquals(DragonLastStandPatterns.GROUND_DRAG, TrialEnderPulse.GROUND_DRAG, 0.0,
				"바닥 감쇠가 두 곳에서 갈렸다 — 한쪽만 고친 것이다");
		assertEquals(DragonLastStandPatterns.AIRBORNE_PUSH_SCALE,
				TrialEnderPulse.AIRBORNE_PUSH_SCALE, 0.0, "공중 보정이 두 곳에서 갈렸다");
		assertEquals(DragonLastStandPatterns.AIRBORNE_LIFT, TrialEnderPulse.AIRBORNE_LIFT, 0.0,
				"공중 판정의 높이가 두 곳에서 갈렸다");
		assertEquals(TrialLandingShock.AIR_DRAG, TrialEnderStorm.AIR_DRAG, 0.0);
	}

	/**
	 * ⚠ <b>고리마다 한 사람 한 번</b> 민다 — 밀린 사람을 고리와 함께 굴려 본다.
	 *
	 * <p>밀린 몸은 처음 몇 틱에 고리(틱당 0.525칸)보다 빨리 바깥으로 가서 <b>뒷자락이 아직 안 온
	 * 자리</b>에 선다. 명단이 없으면 같은 고리가 그 사람을 또 만나 또 민다 — 6장 「매 틱
	 * {@code syncVelocity} 는 사람 입력을 지운다」의 꼴이다. 그 사고가 실제로 일어나는 자리라는 것을
	 * 명단 없이 굴린 쪽이 함께 보인다.
	 */
	@Test
	void 밀린_사람은_같은_고리에_다시_걸리지_않는다() {
		TrialCatalog.Risk.EnderPulse pulse = card();
		int life = TrialEnderPulse.lifetime(pulse.interval(), pulse.travelTicks());
		for (double start = 0.5; start <= 30.0; start += 0.5) {
			assertEquals(1, hitsOnOneRing(pulse, life, start, true),
					start + "칸에 선 사람이 한 고리에 여러 번 밀렸다");
		}
		assertTrue(hitsOnOneRing(pulse, life, 5.0, false) > 1,
				"명단 없이도 한 번이라면 이 시험이 묻는 것이 없어졌다 — 고리 속도나 세기를 먼저 볼 것");
	}

	/** 한 고리가 지나가는 동안 그 사람을 몇 번 미는가. 맞은 뒤로는 바닥 마찰로 밀려간다. */
	private static int hitsOnOneRing(TrialCatalog.Risk.EnderPulse pulse, int life, double start,
			boolean keepRoster) {
		java.util.UUID who = new java.util.UUID(0L, 1L);
		java.util.Set<java.util.UUID> crossed = new java.util.HashSet<>();
		TrialLandingShock.GroundProbe flat = island(1000.0);
		double at = start;
		double speed = 0.0;
		int hits = 0;
		for (int step = 0; step <= life; step++) {
			double outer = TrialEnderPulse.judgeRadius(step, pulse.travelTicks(), pulse.maxRadius());
			double inner = TrialEnderPulse.judgeRadius(step - 1, pulse.travelTicks(),
					pulse.maxRadius());
			if (!keepRoster) {
				crossed.clear();
			}
			if (TrialEnderPulse.crossesNow(crossed, who, at, inner, outer)) {
				hits++;
				speed = TrialEnderPulse.pushSpeed(
						TrialEnderPulse.pushReach(at, 0.0, pulse.maxRadius(), flat), false);
			}
			at += speed;
			speed *= TrialEnderPulse.GROUND_DRAG;
		}
		return hits;
	}

	/**
	 * 다른 카드의 밀치기가 <b>잇따라</b> 와도 천장 안이다 — 덮어쓰고, 매번 지금 자리에서 다시 잰다.
	 *
	 * <p>「착지 충격」(부탁 16 · 천장 34)·「엔더폭풍」(부탁 24 · 천장 32)과 이 카드가 한 사람을 차례로
	 * 미는 여섯 순서를 모두 굴린다. 각각을 최악(끝까지 떠 있음)으로 센다. 서버가 아는 자리가 한두 틱
	 * 늦어 생기는 틈은 이 시험 밖이다 — 클래스 설명 「다른 카드의 밀치기와 겹칠 때」에 수를 적었다.
	 */
	@Test
	void 다른_카드가_이어_밀어도_천장_안이다() {
		double max = card().maxRadius();
		double ceiling = TrialRisks.ARENA_RADIUS - TrialLandingShock.EDGE_MARGIN;
		TrialLandingShock.GroundProbe flat = island(1000.0);
		java.util.function.DoubleUnaryOperator pulse = r -> r + airTravel(TrialEnderPulse.pushSpeed(
				TrialEnderPulse.pushReach(r, 0.0, max, flat), false));
		java.util.function.DoubleUnaryOperator shock = r -> r + TrialLandingShock.pushDistance(r,
				16.0, TrialLandingShock.outwardLimit(75.0, 16.0));
		java.util.function.DoubleUnaryOperator storm = r -> r + TrialEnderStorm.pushDistance(r, 0.0,
				new Vec3(1.0, 0.0, 0.0), TrialEnderStorm.PUSH_BLOCKS * 3.0);
		java.util.function.DoubleUnaryOperator[][] orders = {
				{pulse, shock, storm}, {pulse, storm, shock}, {shock, pulse, storm},
				{shock, storm, pulse}, {storm, pulse, shock}, {storm, shock, pulse}};
		for (double r = 0.25; r <= max; r += 0.25) {
			for (java.util.function.DoubleUnaryOperator[] order : orders) {
				double at = r;
				for (java.util.function.DoubleUnaryOperator push : order) {
					at = push.applyAsDouble(at);
				}
				assertTrue(at <= Math.max(ceiling, r) + 1.0E-9,
						r + "칸에서 차례로 밀려 반경 " + at + " 까지 갔다");
			}
		}
	}

	@Test
	void 자막을_띄우지_않는다() throws IOException {
		// 화면 아래 글자는 전부 걷어냈다. 남은 신호는 소리와 바닥 표식뿐이다.
		String bytes = classBytes();
		assertFalse(bytes.contains("TitleMessenger"), "자막을 되살렸다");
		// 클래스 파일은 「클래스 이름」과 「메서드 이름」을 따로 담으므로 이름만 본다.
		// TrialWarning 자체는 층과 소리 때문에 여기 나온다 — 걷어낸 것은 shout 뿐이다.
		assertFalse(bytes.contains("shout"), "자막을 되살렸다");
	}

	/**
	 * 몸통은 규약의 <b>파랑 — 「밀려난다」</b>다.
	 *
	 * <p>전에는 이 자리에 「규약 색을 새로 만들지 않는다」(먼지를 아예 안 쓴다)가 있었다. 넉백이 없어
	 * 규약의 네 색 어느 것도 이 카드의 뜻이 아니었기 때문이다. 2026-10-04 밀치기가 생기고 사람이
	 * 「파랑으로 바꾼다」를 골라, 이제는 「착지 충격」·「엔더폭풍」과 <b>같은 상수</b>를 쓰는지를 본다.
	 */
	@Test
	void 색은_규약에_있는_파랑이다() throws IOException {
		assertEquals(TrialWarning.Colors.SHOVE, TrialEnderPulse.markColor(),
				"이 카드는 「서 있으면 묶이고 밀려난다」라 파랑이 그 뜻 그대로다");
		assertEquals(TrialLandingShock.markColor(), TrialEnderPulse.markColor(),
				"같은 「밀려난다」 고리인데 색이 갈렸다 — 사람이 둘을 다른 것으로 배운다");
		assertEquals(TrialEnderStorm.markColor(), TrialEnderPulse.markColor());
		String bytes = classBytes();
		assertTrue(bytes.contains("markColor") && bytes.contains("dust"),
				"몸통을 파랑 먼지로 그리지 않는다 — markColor 를 거쳐 TrialWarning.dust 로 그릴 것");
		// 몸통이 옛 보라 엔더 입자로 돌아가지 않았는지. PORTAL 은 맞은 사람 발밑 연출(root)에도
		// 쓰이므로 이름으로는 못 가른다 — 대신 먼지 생성자를 직접 부르지 않았는지(크기·색을
		// 새로 고르지 않았는지)를 본다.
		assertFalse(bytes.contains("DustParticleOptions"),
				"먼지를 직접 만들면 크기·색을 이 파일이 새로 고르게 된다 — TrialWarning.dust 를 쓸 것");
	}

	private static String classBytes() throws IOException {
		return bytesOf("/com/sharedfate/sync/TrialEnderPulse.class");
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String bytesOf(String resource) throws IOException {
		try (InputStream in = TrialEnderPulse.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IOException(resource + " 를 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
