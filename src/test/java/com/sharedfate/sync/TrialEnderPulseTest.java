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

	@Test
	void 아무도_밀지_않는다() throws IOException {
		// 엔드 중앙 섬은 사방이 허공이고 체력이 팀 공유라 한 사람의 낙사가 팀 전체를 끝낸다.
		String bytes = classBytes();
		assertFalse(bytes.contains("setDeltaMovement"), "이 카드에는 넉백이 없다");
		assertFalse(bytes.contains("knockback"), "이 카드에는 넉백이 없다");
		assertFalse(bytes.contains("hurtServer"), "이 카드에는 피해가 없다");
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

	@Test
	void 규약_색을_새로_만들지_않는다() throws IOException {
		// 색 규약은 먼지 고리의 규약이다. 여기에 다섯째 색을 더하면 그 순간 규약이 장식이 되므로
		// 이 카드는 먼지 자체를 쓰지 않고 엔더 입자로 간다.
		String bytes = classBytes();
		assertFalse(bytes.contains("DustParticleOptions"), "먼지를 쓰면 색을 하나 고르게 된다");
		assertFalse(bytes.contains("dust"), "TrialWarning.dust 를 부르면 색을 하나 고르게 된다");
		assertFalse(bytes.contains("markColor"), "규약 색을 끌어다 쓰면 다섯째 색이 생긴다");
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
