package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>오브젝트 파도</b>.
 *
 * <p>여기서 붙들어 두는 것 아홉.
 *
 * <ol>
 *   <li>⚠⚠ <b>사람이 정한 값</b> — 25%·10% 두 번 · 2/4/5/6 · 체력 30 · <b>연결 2초</b> ·
 *       <b>초당 0.5% 누수</b> · <b>타이머 없음</b> · 크기 한 칸</li>
 *   <li>⚠⚠ <b>초당 0.5% 가 실제로 몇인가</b>를 수로 못박는다 — 하나가 12 · 여섯이 72 ·
 *       깎아야 하는 1200 이 전부 되돌아오는 데 16.6초. ⚠ <b>시험이 0.5 를 손으로 적지 않는다</b> —
 *       {@code LEAK_FRACTION_PER_SECOND} 와 설정의 인원당 체력에서 뽑는다. 사람이 이 값을 또
 *       움직일 것이므로 두 군데에 적히면 안 된다</li>
 *   <li>⚠⚠ <b>박힌 뒤 2초 전에는 선도 회복도 없다</b></li>
 *   <li>⚠⚠ <b>부서지면 그 크리스탈 몫이 멈춘다</b> — 몫을 굳혀 들고 있지 않다</li>
 *   <li><b>자리가 마지막 지대 안이고 서로 겹치지 않는다</b></li>
 *   <li>⚠⚠ <b>보이는 크기와 맞는 상자가 같다</b> — 값 하나에서 둘이 나온다</li>
 *   <li>⚠ <b>바가 하나도 없다</b> — 크리스탈마다 뜨던 것도, 가운데 타이머도</li>
 *   <li><b>개체가 하나도 안 샌다</b> — 자리마다 둘(상자 + 그림)이고 <b>치우는 자리가 일곱</b>이다</li>
 *   <li><b>늘어난 점이 한 틱 예산 아래다</b> — 31 → 37</li>
 * </ol>
 */
class DragonLastStandObjectsTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandObjects.clearState();
		DragonLastStandLights.drop();
		DragonLastStandShells.drop();
	}

	// ------------------------------------------------------------------ 사람이 정한 값

	/** 문턱이 <b>둘뿐</b>이고 25%·10% 다. 주기적이 아니다. */
	@Test
	void 문턱이_25와_10_둘뿐이다() {
		assertEquals(2, DragonLastStandObjects.THRESHOLDS.length, "주기적이 아니라 두 번만이다");
		assertEquals(0.25F, DragonLastStandObjects.THRESHOLDS[0], 1.0E-6F);
		assertEquals(0.10F, DragonLastStandObjects.THRESHOLDS[1], 1.0E-6F);
		assertTrue(DragonLastStandObjects.THRESHOLDS[0] < DragonLastStand.ENTRY_HEALTH_RATIO,
				"진입 문턱보다 아래라야 진입 연출과 겹치지 않는다");
	}

	/** 인원별 개수가 사람이 적은 표 그대로다. 오르는 폭이 고르지 않은 것이 그 표다. */
	@Test
	void 개수가_사람이_적은_표_그대로다() {
		assertEquals(0, DragonLastStandObjects.objectCount(0), "아무도 없으면 열지 않는다");
		assertEquals(2, DragonLastStandObjects.objectCount(1));
		assertEquals(4, DragonLastStandObjects.objectCount(2));
		assertEquals(5, DragonLastStandObjects.objectCount(3));
		assertEquals(6, DragonLastStandObjects.objectCount(4));
		assertEquals(6, DragonLastStandObjects.objectCount(7), "다섯 이상은 4인 값으로 자른다");
	}

	/** 체력과 <b>연결 지연 2초</b>가 사람이 정한 값이다. */
	@Test
	void 체력과_연결_지연이_사람이_정한_값이다() {
		assertEquals(30.0F, DragonLastStandObjects.OBJECT_HEALTH, 1.0E-6F);
		assertEquals(2 * DragonLastStandObjects.SECOND_TICKS,
				DragonLastStandObjects.LINK_DELAY_TICKS,
				"사람이 「소환되고 2초뒤부터 엔더드래곤에 연결되고」라고 했다");
		assertTrue(DragonLastStandObjects.LEAK_FRACTION_PER_SECOND > 0.0F,
				"연결된 뒤에 아무것도 안 새면 이 파도가 하는 일이 없다");
	}

	/**
	 * ⚠⚠ <b>타이머가 없다.</b> 「시간 안에 부숴라」가 아니라 <b>「살아 있는 동안 계속 샌다」</b>다.
	 *
	 * <p>타이머가 있던 자리에서 쓰던 길이 전부 사라졌는지 본다 — 상수 하나라도 되살아나면
	 * 「부술 때까지」가 그 자리에서 거짓이 된다.
	 */
	@Test
	void 타이머가_하나도_남아_있지_않다() {
		for (String gone : new String[] {"TIMER_TICKS", "DRAIN_TICKS", "MISS_HEAL_FRACTION",
				"remainingRatio", "healStep", "drainPitch", "waveTicks"}) {
			assertFalse(declares(gone),
					gone + " 이 되살아났다 — 사람이 타이머를 없애고 「부술떄까지」로 바꿨다");
		}
		String bytes = classBytes();
		assertFalse(bytes.contains("reshapeBarFill"),
				"줄어드는 바가 되살아났다 — 셀 시간이 없으므로 가리킬 수가 없다");
	}

	/**
	 * ⚠⚠ <b>초당 0.5% 가 실제로 몇인지 수로 못박는다.</b>
	 *
	 * <p>⚠ <b>0.5 를 손으로 적지 않는다.</b> 사람이 1% → 0.5% 로 벌써 한 번 움직였으므로 또 움직일
	 * 값이고, 시험이 그 수를 따로 적으면 <b>한쪽만 고쳐지는 날</b>이 온다. 비율은
	 * {@code LEAK_FRACTION_PER_SECOND} 에서, 최대 체력은 <b>설정의 인원당 체력</b>에서 뽑는다.
	 *
	 * <p>남기는 사실은 셋이다 — 하나가 초당 얼마 · 여섯이 초당 얼마 · <b>깎아야 하는 1200 이 전부
	 * 되돌아오는 데 몇 초</b>.
	 */
	@Test
	void 초당_얼마가_되돌아가는지_수로_못박는다() {
		// 4인 기준 최대 체력. 「600 × 4」를 값에서 센다 — DragonTrialManager.strengthenDragon 이
		// 인원당 체력을 곱하고, 사람 표가 넷에서 끝나므로 넷이 이 판의 최대 인원이다.
		int members = DragonLastStandObjects.COUNT_BY_MEMBERS.length;
		assertEquals(4, members, "사람의 표가 넷에서 끝난다");
		float max = (float) new SharedFateConfig().dragonHealthPerMember * members;
		assertEquals(2400.0F, max, 0.5F, "4인 기준 2400 이다 — 설정의 기본값이 바뀌면 여기가 먼저 안다");

		// 하나가 1초에. 0.5% 면 12 다.
		float one = DragonLastStandObjects.leakPerSecond(max);
		assertEquals(max * DragonLastStandObjects.LEAK_FRACTION_PER_SECOND, one, 1.0E-4F);
		assertEquals(12.0F, one, 0.01F, "0.5% 면 하나가 초당 12 다");
		assertEquals(one / DragonLastStandObjects.SECOND_TICKS,
				DragonLastStandObjects.leakPerTick(max), 1.0E-5F, "틱 값이 초 값을 20으로 나눈 것이다");
		assertEquals(0.6F, DragonLastStandObjects.leakPerTick(max), 0.001F, "한 틱에 0.6 이다");

		// 여섯이 1초에. 72 다.
		int most = DragonLastStandObjects.objectCount(members);
		assertEquals(6, most);
		float six = one * most;
		assertEquals(72.0F, six, 0.05F, "여섯이 살아 있으면 초당 72 다");

		// ⚠ 이 페이즈가 깎아야 하는 것. 30% 로 들어와 +20%p 를 받아 50% 에서 시작한다.
		float toKill = DragonLastStand.healedHealth(max * DragonLastStand.ENTRY_HEALTH_RATIO, max);
		assertEquals(1200.0F, toKill, 0.5F);
		float secondsToUndoAll = toKill / six;
		assertEquals(16.6F, secondsToUndoAll, 0.1F,
				"여섯이 살아 있으면 16.6초에 이 페이즈가 깎아야 할 전부가 되돌아온다");

		// ⚠⚠ 사람이 1% 에서 내린 까닭이 이 수다. 1% 면 8.3초였다.
		assertEquals(8.3F, toKill / (six * 2.0F), 0.1F,
				"1% 면 8.3초였다 — 체력 30짜리 여섯을 2초에 다 부수는 것이 불가능하므로 과했다");
		assertTrue(secondsToUndoAll * 20.0F > DragonLastStandObjects.allLinkedTicks(most),
				"전부 되돌아오는 시간이 전부 연결되는 시간보다 짧으면 팀이 손쓸 틈이 없다");
	}

	/**
	 * ⚠⚠ <b>체력 30짜리 여섯을 2초 안에 다 부수는 것은 불가능하다.</b>
	 *
	 * <p>그러므로 <b>연결은 반드시 일어난다</b> — 이 파도는 누수를 <b>피하는</b> 싸움이 아니라
	 * <b>줄이는</b> 싸움이고, 그것이 사람이 1% 를 0.5% 로 내린 근거다. 여기를 「2초면 부술 수 있다」로
	 * 읽고 비율을 되올리면 그 판단이 사라진다.
	 */
	@Test
	void 연결_전에_다_부수는_것은_불가능하다() {
		int most = DragonLastStandObjects.objectCount(DragonLastStandObjects.COUNT_BY_MEMBERS.length);
		float need = DragonLastStandObjects.OBJECT_HEALTH * most;
		assertEquals(180.0F, need, 0.5F, "여섯이면 180 이다");
		// 다이아 검 7 · 쿨타임 0.625초(12.5틱)라 2초에 네 번이 한계다. 넷이 함께 쳐도 네 자리뿐이다.
		int swings = DragonLastStandObjects.LINK_DELAY_TICKS / 13;
		float best = 7.0F * swings * DragonLastStandObjects.COUNT_BY_MEMBERS.length;
		assertTrue(best < need,
				"2초에 넣을 수 있는 최선이 " + best + " 인데 " + need + " 가 필요하다 — "
						+ "연결을 피할 길이 없으므로 비율이 「피하면 0」을 전제로 정해질 수 없다");
	}

	// ------------------------------------------------------------------ 시간표

	/** 마지막이 박히는 틱과 <b>전부 연결되는 틱</b>이 값에서 직접 나온다. */
	@Test
	void 심는_시간이_값에서_나온다() {
		assertEquals(DragonLastStandObjects.RISE_TICKS + DragonLastStandObjects.FALL_TICKS,
				DragonLastStandObjects.plantTicks(1), "하나면 선 1초 + 낙하 1초다");
		assertEquals(20 + 5 * 10 + 20, DragonLastStandObjects.plantTicks(6), "여섯이면 90틱이다");
		assertEquals(DragonLastStandObjects.plantTicks(6)
						+ DragonLastStandObjects.LINK_DELAY_TICKS,
				DragonLastStandObjects.allLinkedTicks(6), "마지막이 박힌 뒤 2초에 전부가 붙는다");
		assertEquals(130, DragonLastStandObjects.allLinkedTicks(6),
				"여섯이면 130틱이고 그 틱이 점 예산에서 가장 바쁜 틱이다");
		// ⚠ 세우는 구간과 새는 구간이 겹친다 — 이 겹침이 옛 규칙에는 없었고 점 예산을 올렸다.
		assertTrue(DragonLastStandObjects.LINK_DELAY_TICKS
						< DragonLastStandObjects.plantTicks(6) - DragonLastStandObjects.RISE_TICKS,
				"연결 지연이 세우는 시간보다 길면 겹침이 없다 — 지금은 겹치므로 예산이 37이다");
	}

	/** 떨어지는 높이가 위에서 아래로만 간다. 뒤집히면 오브젝트가 땅에서 솟는다. */
	@Test
	void _떨어지는_높이가_내려가기만_한다() {
		double previous = Double.MAX_VALUE;
		for (int step = 0; step <= DragonLastStandObjects.FALL_TICKS; step++) {
			double here = DragonLastStandObjects.fallHeight(step);
			assertTrue(here <= previous, step + "틱에서 되레 올라갔다");
			previous = here;
		}
		assertEquals(DragonLastStandObjects.SPAWN_HEIGHT, DragonLastStandObjects.fallHeight(0),
				1.0E-9);
		assertEquals(0.0, DragonLastStandObjects.fallHeight(DragonLastStandObjects.FALL_TICKS),
				1.0E-9, "마지막 틱에 자리에 닿아야 「박힌다」가 된다");
	}

	/**
	 * ⚠⚠ <b>박힌 뒤 2초 전에는 연결도 선도 회복도 없다.</b>
	 *
	 * <p>사람 말이 「소환되고 2초뒤부터 엔더드래곤에 연결되고 선이 회복되는거야」다. 그 2초를
	 * <b>틱 단위로 훑는다</b> — 한 틱이라도 일찍 붙으면 사람이 정한 2초가 거짓이고, 늦게 붙으면
	 * 선이 뜨는 때와 회복이 시작하는 때가 갈린다.
	 *
	 * <p>⚠ <b>크리스탈마다 따로</b>다. 원점이 「파도가 열린 틱」이 아니라 <b>그것이 박힌 틱</b>이라는
	 * 것을 여러 원점으로 확인한다 — 먼저 떨어진 것이 먼저 새는 것이 이 바뀜의 핵이다.
	 */
	@Test
	void 박힌_뒤_2초_전에는_선도_회복도_없다() {
		for (int plantedAt : new int[] {40, 50, 60, 70, 80, 90}) {
			for (int step = plantedAt; step < plantedAt + DragonLastStandObjects.LINK_DELAY_TICKS;
					step++) {
				assertFalse(DragonLastStandObjects.linkedBy(step, plantedAt),
						plantedAt + "틱에 박힌 것이 " + step + "틱에 벌써 붙었다 — "
								+ (step - plantedAt) + "틱밖에 안 지났다");
			}
			int links = plantedAt + DragonLastStandObjects.LINK_DELAY_TICKS;
			assertTrue(DragonLastStandObjects.linkedBy(links, plantedAt),
					"「2초뒤부터」이므로 " + links + "틱이 연결의 첫 틱이어야 한다");
			assertTrue(DragonLastStandObjects.linkedBy(links + 1000, plantedAt),
					"한 번 붙으면 부술 때까지 떨어지지 않는다");
		}
		// 아직 안 박힌 자리는 어느 틱에도 안 붙는다 — plantedAt 이 −1 인 것이 그 상태다.
		assertFalse(DragonLastStandObjects.linkedBy(100000, -1),
				"하늘에 있는 것이 회복시키면 사람이 때릴 수 없는 것이 샌다");

		// ⚠ 먼저 떨어진 것이 먼저 샌다. 여섯이면 선이 0.5초마다 한 줄씩 늘어난다.
		assertTrue(DragonLastStandObjects.linkedBy(80, 40), "처음 것은 80틱에 붙는다");
		assertFalse(DragonLastStandObjects.linkedBy(80, 90), "마지막 것은 아직 하늘에 있다");
	}

	// ------------------------------------------------------------------ 자리

	/**
	 * ⚠⚠ <b>자리가 마지막 지대 안이다.</b>
	 *
	 * <p>값이 「지대 끝값 − 벽 여유」에서 나오므로 <b>구조적으로 참</b>이다. 지대가 줄어드는
	 * 도중에 파도가 와도 오브젝트를 때리려고 벽 밖으로 나갈 일이 없다.
	 */
	@Test
	void 자리가_마지막_지대_안이다() {
		double last = DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1];
		assertEquals(last - DragonLastStandEntry.WALL_MARGIN, DragonLastStandObjects.RING_RADIUS,
				1.0E-9, "값에서 직접 나와야 지대 끝값을 고치는 사람이 여기를 안 고친다");
		assertEquals(9.0, DragonLastStandObjects.RING_RADIUS, 1.0E-9, "지금 9칸이다");
		for (int count = 1; count <= 6; count++) {
			for (int index = 0; index < count; index++) {
				Vec3 at = DragonLastStandObjects.seatOffset(index, count, 1.234);
				assertTrue(Math.sqrt(at.x * at.x + at.z * at.z) <= last,
						count + "개 중 " + index + "번째가 마지막 지대 밖이다");
			}
		}
	}

	/** 자리가 <b>드래곤 몸 밖</b>이다. 몸 안이면 붙어 때리는 사람이 오브젝트를 못 본다. */
	@Test
	void 자리가_드래곤_몸_밖이다() {
		assertTrue(DragonLastStandObjects.RING_RADIUS > 8.0,
				"앉은 드래곤의 상자가 가로 16칸이라 반이 8 이다");
		assertTrue(DragonLastStandObjects.RING_RADIUS
						<= DragonLastStandPatterns.WING_STRONG_RADIUS,
				"오브젝트를 때리는 자리가 날개가 가장 센 구간(4~12칸) 안이라는 것이 의도다");
	}

	/**
	 * ⚠ <b>자리끼리 겹치지 않는다.</b> 굳은 고리라 굴림에 기대지 않는다.
	 *
	 * <p>겹치면 뒤의 것을 때릴 수 없고, 15초에 다 부수는 것이 그 자리에서 불가능해진다.
	 */
	@Test
	void 자리끼리_겹치지_않는다() {
		for (int count = 2; count <= 6; count++) {
			double worst = Double.MAX_VALUE;
			for (int index = 0; index < count; index++) {
				Vec3 a = DragonLastStandObjects.seatOffset(index, count, 0.7);
				Vec3 b = DragonLastStandObjects.seatOffset(index + 1, count, 0.7);
				worst = Math.min(worst, a.subtract(b).horizontalDistance());
			}
			assertTrue(worst > DragonLastStandObjects.HIT_WIDTH,
					count + "개일 때 이웃 사이가 " + worst + "칸이다 — 맞는 상자가 겹친다");
		}
	}

	/** 고리를 돌려도 개수와 간격이 그대로다. 판마다 자리가 달라야 사람이 외우지 못한다. */
	@Test
	void 고리를_돌려도_모양이_같다() {
		Vec3 first = DragonLastStandObjects.seatOffset(0, 4, 0.0);
		Vec3 spun = DragonLastStandObjects.seatOffset(0, 4, Math.PI / 3.0);
		assertTrue(first.subtract(spun).horizontalDistance() > 1.0, "돌렸는데 자리가 그대로다");
		assertEquals(first.horizontalDistance(), spun.horizontalDistance(), 1.0E-9,
				"돌리면 반경이 달라졌다");
	}

	// ------------------------------------------------------------------ 보이는 크기와 맞는 상자

	/**
	 * ⚠⚠ <b>보이는 크기와 맞는 상자가 같다.</b>
	 *
	 * <p>사람 말이 「크기를 블럭 정도 크기로 키웟으면」이고, 그 한 값
	 * ({@code CRYSTAL_HEIGHT})에서 <b>그림의 배율과 상자의 크기가 함께</b> 나온다. 어긋나면
	 * <b>보이는 쪽을 쳤는데 안 맞는</b> 자리가 생기고 그것이 곧 버그다.
	 */
	@Test
	void 보이는_크기와_맞는_상자가_같다() {
		assertEquals(1.0, DragonLastStandObjects.CRYSTAL_HEIGHT, 1.0E-9,
				"사람이 말한 「블럭 정도 크기」를 한 칸으로 읽었다");
		// 그림의 투명한 테두리를 되돌린 배율이라, 색이 있는 부분의 높이가 정확히 한 칸이다.
		assertEquals(DragonLastStandObjects.CRYSTAL_HEIGHT,
				DragonLastStandObjects.DISPLAY_SCALE * DragonLastStandObjects.SPRITE_INK_TALL,
				1.0E-9, "보이는 높이가 사람이 말한 수와 달라졌다");
		assertEquals(DragonLastStandObjects.CRYSTAL_HEIGHT, DragonLastStandObjects.HIT_HEIGHT,
				1.0E-6, "상자의 높이가 보이는 높이와 다르다");
		assertEquals(DragonLastStandObjects.DISPLAY_SCALE * DragonLastStandObjects.SPRITE_INK_WIDE,
				DragonLastStandObjects.HIT_WIDTH, 1.0E-6, "상자의 가로가 보이는 가로와 다르다");

		// 상자의 가운데가 그림의 가운데다 — 상자는 발밑에서 위로만 자라므로 절반을 내려 놓는다.
		for (double seat : new double[] {0.0, 63.0, -11.5}) {
			assertEquals(DragonLastStandObjects.crystalCenterY(seat),
					DragonLastStandObjects.standFeetY(seat)
							+ DragonLastStandObjects.CRYSTAL_HEIGHT / 2.0,
					1.0E-9, "그림과 상자의 가운데가 어긋났다 — 보이는 아래 허공이 맞는다");
		}
		assertTrue(DragonLastStandObjects.standFeetY(0.0) > 0.0,
				"상자가 지표를 파고들면 바닥을 치는 사람이 오브젝트를 때린다");

		// 옛 모습(머리에 얹은 아이템)보다 분명히 크다. 0.625 는 26.3 CustomHeadLayer 의 배율이다.
		double before = 0.625 * DragonLastStandObjects.SPRITE_INK_TALL;
		assertTrue(DragonLastStandObjects.CRYSTAL_HEIGHT > before * 1.5,
				"사람이 「너무 작아서 잘 보이지도 않고」라고 한 " + before + "칸에서 커지지 않았다");
	}

	/**
	 * ⚠ <b>상자가 {@code scale} 속성에 흔들리지 않는다.</b>
	 *
	 * <p>26.3 에는 {@code Attributes.SCALE} 이 있고 갑옷 거치대도 그것을 가진다
	 * ({@code createLivingAttributes} 에 {@code .add(Attributes.SCALE)} 가 있다 — 바이트코드로
	 * 확인했다). 그 속성이 걸리면 {@code LivingEntity.getDimensions} 가 상자를 함께 키우는데,
	 * <b>그림은 따로 서 있으므로</b> 그대로 두면 둘이 갈린다. {@code fixed} 상자는
	 * {@code EntityDimensions.scale} 이 <b>자신을 그대로 돌려주므로</b> 그 길이 막힌다.
	 */
	@Test
	void 상자가_scale_속성에_흔들리지_않는다() {
		EntityDimensions box = EntityDimensions.fixed(DragonLastStandObjects.HIT_WIDTH,
				DragonLastStandObjects.HIT_HEIGHT);
		assertTrue(box.fixed(), "fixed 가 아니면 scale 속성이 상자만 키운다");
		assertSame(box, box.scale(4.0F), "26.3 EntityDimensions.scale 의 약속이 깨졌다");
		String shard = read("/com/sharedfate/sync/DragonLastStandObjects$Shard.class");
		assertTrue(shard.contains("fixed"), "상자를 fixed 로 만들지 않았다");
		assertFalse(shard.contains("scalable"), "scalable 은 scale 속성에 따라 커진다");
	}

	/** 모습은 <b>디스플레이 개체</b>가 든다. 머리 장비로 돌아가면 다시 작아진다. */
	@Test
	void 모습은_디스플레이_개체가_든다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("END_CRYSTAL"), "보일 아이템이 없다");
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandShells"),
				"그림을 드는 개체가 없다");
		assertFalse(bytes.contains("setItemSlot"),
				"머리 장비는 0.625배로 그려지고(26.3 CustomHeadLayer) 엔드 크리스탈 아이템은 "
						+ "납작한 그림이라 옆에서 사라진다 — 사람이 「잘 보이지도 않고」라고 한 것이 그것이다");
		assertTrue(bytes.contains("setInvisible"), "갑옷 거치대가 그대로 보이면 크리스탈이 아니다");

		String shell = read("/com/sharedfate/sync/DragonLastStandShells$Shell.class");
		assertTrue(shell.contains("ITEM_DISPLAY"), "개체 종류가 아이템 디스플레이가 아니다");
		assertTrue(shell.contains("getSlot"),
				"26.3 ItemDisplay.readAdditionalSaveData 는 item 칸이 없으면 아이템을 비운다 — "
						+ "모양을 세운 뒤에 다시 끼우는 줄이 없으면 아무것도 안 보인다");
		assertTrue(shell.contains("CENTER"),
				"빌보드가 CENTER 라야 어느 각도에서도 보이는 크기가 같다");
	}

	// ------------------------------------------------------------------ 바가 하나도 없다

	/**
	 * ⚠⚠ <b>바가 하나도 없다.</b> 크리스탈마다 뜨던 것도, 아레나 가운데 타이머도.
	 *
	 * <p>사람 말이 「그 크리스탈들 체력바를 없애」라 <b>자리마다 떠 있던 바</b>를 먼저 걷었고, 그 뒤
	 * <b>타이머가 통째로 사라지면서</b> 하나 남았던 것도 지웠다 — <b>가로로 줄어드는 바는 「남은
	 * 시간」으로만 읽힌다.</b> 줄지 않는 바를 띄워 두면 사람이 <b>오지 않는 무언가를 기다린다.</b>
	 *
	 * <p>말하는 수단은 셋 남았다 — 연결선(어느 것이 샌다) · 소리(얼마나 샌다) · 드래곤 보스바
	 * (얼마나 되돌아갔다). 바를 다시 넣으려는 사람은 그 셋이 이미 하는 말을 두 번 하게 된다.
	 */
	@Test
	void 바가_하나도_없다() {
		assertEquals(1, glowFields("Slot"),
				"자리가 들고 있는 빛은 흰 신호기 선 하나뿐이어야 한다 — 크리스탈 위에 뜬 바는 "
						+ "그 크리스탈의 체력으로 읽힌다");
		assertEquals("line", glowFieldName("Slot"), "자리에 남은 빛이 흰 선이 아니다");
		assertEquals(0, glowFields("Wave"),
				"파도가 바를 하나라도 들고 있다 — 타이머가 없어졌으므로 가리킬 수가 없다");

		String bytes = classBytes();
		for (String bar : new String[] {"raiseBarBack", "raiseBarFill", "reshapeBarFill"}) {
			assertFalse(bytes.contains(bar),
					bar + " 을 다시 부른다 — 줄어드는 바는 「남은 시간」으로만 읽히고 셀 시간이 없다");
		}
		// 흰 신호기 선은 그대로다. 그것은 바가 아니라 「여기 생긴다」를 말하는 등장 연출이다.
		assertTrue(bytes.contains("raisePillar"), "흰 신호기 선이 사라졌다 — 등장 연출의 절반이다");
	}

	// ------------------------------------------------------------------ 연결선과 회복

	/**
	 * ⚠⚠ <b>선과 회복이 같은 조건 하나에 달려 있다 — {@code Slot.linked}.</b>
	 *
	 * <p>사람 말이 「2초뒤부터 엔더드래곤에 연결되고 <b>선이</b> 회복되는거야」다. 선이 보이는 때와
	 * 체력이 흐르는 때가 갈리면 그 말이 거짓이 되므로, <b>둘을 가르는 함수가 하나여야 한다.</b>
	 *
	 * <p>⚠ 세는 방법. 선을 그리는 {@code beams} 와 회복을 주는 {@code leak} 이 둘 다
	 * {@code private} 이고(바깥에서 못 부른다), <b>{@code Slot.linked} 가 그 하나뿐인 조건</b>이며
	 * 그것이 <b>{@code standing()} 을 함께 묻는다</b>는 것을 바이트코드로 본다.
	 *
	 * <p>⚠ 시험이 못 보는 것: 「{@code beams} 가 {@code standing()} 대신 {@code linked()} 를
	 * 쓴다」를 호출 자리마다 셀 수는 없다. 셀 수 있는 것은 <b>그 함수들이 존재하고 감춰져 있다는
	 * 것</b>과 <b>조건의 모양</b>이다.
	 */
	@Test
	void 선과_회복이_같은_조건에_달려_있다() throws Exception {
		Method beams = DragonLastStandObjects.class.getDeclaredMethod("beams",
				net.minecraft.server.level.ServerLevel.class,
				net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				Class.forName("com.sharedfate.sync.DragonLastStandObjects$Wave"), int.class);
		assertTrue(Modifier.isPrivate(beams.getModifiers()),
				"선을 그리는 길이 공개되면 회복과 무관한 자리에서 그릴 수 있다");
		Method leak = DragonLastStandObjects.class.getDeclaredMethod("leak",
				net.minecraft.server.level.ServerLevel.class,
				net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				java.util.List.class,
				Class.forName("com.sharedfate.sync.DragonLastStandObjects$Wave"), int.class);
		assertTrue(Modifier.isPrivate(leak.getModifiers()),
				"회복을 주는 길이 공개되면 파도 밖에서 드래곤을 채울 수 있다");

		// 하나뿐인 조건. ⚠ standing() 을 함께 묻는 것이 「부서지면 멈춘다」의 전부다.
		Class<?> slot = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Slot");
		assertTrue(slot.getDeclaredMethod("linked", int.class) != null, "조건이 사라졌다");
		assertTrue(read("/com/sharedfate/sync/DragonLastStandObjects$Slot.class")
						.contains("standing"),
				"linked 가 standing 을 안 묻는다 — 부서진 자리가 계속 회복시킨다");
		// 선을 그리는 쪽이 그 조건을 부른다.
		assertTrue(classBytes().contains("linked"), "선을 그리는 쪽이 연결 조건을 안 묻는다");

		// 파도가 없으면 샐 것도 없다.
		assertFalse(DragonLastStandObjects.leaking());
		assertFalse(DragonLastStandObjects.running());
		assertEquals(0, DragonLastStandObjects.linkedCount(100000),
				"파도가 없는데 연결된 것이 있다");
	}

	/**
	 * ⚠⚠ <b>부서지면 그 크리스탈 몫이 멈춘다 — 몫을 굳혀 들고 있지 않다.</b>
	 *
	 * <p>옛 규칙은 15초가 지나는 틱에 「못 부순 개수」를 <b>굳혀</b> 들고 있었고({@code Wave.missed} ·
	 * {@code Wave.healTotal}) 그래서 <b>부순 뒤에도 그 몫이 흘렀다</b> — 그것을 막으려고 회복 중에는
	 * 못 부수게 했던 것이 {@code Shard.spent} 다. 지금은 <b>굳는 몫이 없으므로</b> 그 둘이 전부
	 * 사라져야 한다.
	 */
	@Test
	void 부서지면_그_크리스탈_몫이_멈춘다() throws Exception {
		Class<?> wave = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Wave");
		for (Field field : wave.getDeclaredFields()) {
			assertFalse(field.getName().equals("missed") || field.getName().equals("healTotal"),
					"파도가 " + field.getName() + " 을 굳혀 들고 있다 — 부순 뒤에도 그 몫이 흐른다");
		}
		Class<?> shard = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Shard");
		for (Field field : shard.getDeclaredFields()) {
			assertFalse(field.getName().equals("spent"),
					"「회복 중에는 못 부순다」가 되살아났다 — 부수는 것이 유일한 대응이다");
		}
		for (Method method : shard.getDeclaredMethods()) {
			assertFalse(method.getName().equals("spend"), "spend() 가 되살아났다");
		}

		// 매 틱 다시 세는 길이 있고 틱을 받는다 — 굳혀 둘 수 없는 모양이다.
		assertTrue(DragonLastStandObjects.class.getDeclaredMethod("linkedCount", int.class) != null,
				"연결된 개수를 매 틱 다시 세는 길이 없다");
	}

	/**
	 * ⚠ <b>선이 드래곤에게 닿는다.</b> 중간에서 끊기면 「무엇이 회복시키는가」가 안 읽힌다.
	 *
	 * <p>마지막 점이 드래곤 상자 안인 것을 값으로 본다 — 앉은 드래곤의 상자가 가로 16칸이라
	 * 반이 8 이고(26.3 {@code sized(16.0F, 8.0F)}), 고리 반경이 9 다.
	 */
	@Test
	void 연결선이_드래곤에게_닿는다() {
		int stride = DragonLastStandObjects.BEAM_STRIDE;
		// ⚠ 선이 상시이므로 훑는 구간에 끝이 없다. 한 바퀴(BEAM_POINTS × stride)보다 넉넉히 본다.
		for (int step = 0; step < 400; step++) {
			// 그리는 쪽과 같은 셈이다 — 나눠 그리므로 틱마다 찍는 자리가 다르다.
			double first = -1.0;
			double last = -1.0;
			for (int index = Math.floorMod(step, stride);
					index < DragonLastStandObjects.BEAM_POINTS; index += stride) {
				double along = DragonLastStandObjects.beamAlong(index, step);
				if (first < 0.0) {
					first = along;
				}
				last = along;
			}
			assertTrue(first >= 0.0 && first < 0.3, step + "틱의 첫 점이 크리스탈에서 떨어졌다");
			assertTrue(last <= 1.0, step + "틱의 마지막 점이 드래곤을 지나쳤다");
			double awayFromDragon = DragonLastStandObjects.RING_RADIUS * (1.0 - last);
			assertTrue(awayFromDragon < 8.0,
					step + "틱의 마지막 점이 드래곤 상자 밖이다(" + awayFromDragon + "칸)");
		}
		// 틱이 흐르면 점이 밀린다 — 점을 하나도 더 쓰지 않고 흐르는 방향을 보여 주는 길이다.
		assertTrue(DragonLastStandObjects.beamAlong(0, 0)
				< DragonLastStandObjects.beamAlong(0, 1), "점이 밀리지 않으면 점선이 그대로 선다");
	}

	/**
	 * ⚠⚠ <b>누수 소리는 끝이 없으므로 주기를 늘리고 음량을 내렸다.</b>
	 *
	 * <p>앞 규칙에서는 회복이 <b>30틱으로 끝나는 구간</b>이라 0.5초마다 세 번이 전부였고 음높이가
	 * 「끝나 간다」를 말했다. 지금은 <b>부술 때까지</b>라 0.5초를 그대로 두면 초당 두 번이 영원히
	 * 울리고, 음높이를 시간으로 올리면 1.5초 뒤부터 <b>늘 천장</b>이라 아무 말도 하지 않는다.
	 *
	 * <p>그래서 ① 주기를 2초로 ② 음량을 절반으로 ③ 음높이를 <b>연결된 개수</b>로 바꿨다 —
	 * <b>부수면 다음 소리가 내려간다.</b>
	 */
	@Test
	void 누수_소리가_귀를_아프게_하지_않는다() {
		assertEquals(2 * DragonLastStandObjects.SECOND_TICKS,
				DragonLastStandObjects.LEAK_SOUND_TICKS,
				"끝이 없는 소리라 2초다 — 0.5초면 부술 때까지 초당 두 번이 영원히 울린다");
		assertTrue(DragonLastStandObjects.LEAK_SOUND_TICKS
						< 4 * DragonLastStandObjects.SECOND_TICKS,
				"바닐라 신호기 주변음이 4초 주기다 — 그보다 느리면 「주변음」으로 들려 사건이 안 된다");
		assertEquals(0, DragonLastStandObjects.LINK_DELAY_TICKS
						% DragonLastStandObjects.LEAK_SOUND_TICKS,
				"첫 연결이 일어나는 틱에 첫 소리가 맞아야 한다");
		assertTrue(DragonLastStandObjects.LEAK_VOLUME < 1.0F,
				"끝이 없는 소리가 예고음과 같은 음량이면 드래곤의 울음·날개·번개를 덮는다");
		assertTrue(DragonLastStandObjects.LEAK_VOLUME > 0.0F, "안 들리면 「샌다」를 알 길이 없다");

		// 음높이는 「몇 개가 붙어 있나」다 — 시간이 아니다. 부수면 내려간다.
		int most = DragonLastStandObjects.objectCount(DragonLastStandObjects.COUNT_BY_MEMBERS.length);
		assertEquals(0.8F, DragonLastStandObjects.leakPitch(1), 1.0E-5F, "하나면 0.8 이다");
		assertEquals(1.2F, DragonLastStandObjects.leakPitch(most), 1.0E-5F, "여섯이면 1.2 다");
		float previous = -1.0F;
		for (int linked = 1; linked <= most; linked++) {
			float pitch = DragonLastStandObjects.leakPitch(linked);
			assertTrue(pitch > previous, linked + "개에서 음높이가 안 올랐다");
			previous = pitch;
		}
		assertEquals(DragonLastStandObjects.leakPitch(most),
				DragonLastStandObjects.leakPitch(most + 10), 1.0E-5F,
				"개수를 넘겨도 천장이어야 한다 — 바닐라 음높이 상한이 2.0 이다");

		String bytes = classBytes();
		assertTrue(bytes.contains("AMETHYST_BLOCK_RESONATE"),
				"누수 소리가 없다 — block/amethyst/resonate1~4 다(sounds.json 에서 확인했다)");
		assertTrue(bytes.contains("ENDER_DRAGON_GROWL"),
				"첫 연결을 알리는 포효가 없다 — 파도마다 한 번이다");
		assertFalse(bytes.contains("BEACON_DEACTIVATE"),
				"「시간이 꺼졌다」 소리가 남아 있다 — 꺼질 시간이 없다");
		assertTrue(bytes.contains("playEach"),
				"위치 기반 playSound 를 팀원 루프에서 부르면 사람 수만큼 겹친다");
	}

	// ------------------------------------------------------------------ 무엇으로 깎이나

	/**
	 * ⚠ <b>「누가 했는가」가 기준이다.</b> 근접·활·폭발은 전부 받고 환경 피해는 안 받는다.
	 *
	 * <p>환경 피해까지 받으면 팀이 하지 않은 일로 오브젝트가 사라져 5% 회복이 공짜로 면제된다.
	 */
	@Test
	void 원인이_있는_피해만_받는다() {
		assertTrue(DragonLastStandObjects.accepts(true, false), "근접·활은 쏜 사람이 가해자다");
		assertTrue(DragonLastStandObjects.accepts(true, true), "사람이 터뜨린 폭발");
		assertTrue(DragonLastStandObjects.accepts(false, true),
				"가해자가 사라진 폭발도 팀이 한 일이다");
		assertFalse(DragonLastStandObjects.accepts(false, false),
				"낙하·허공·불처럼 아무도 안 한 일로 사라지면 그 몫의 누수가 공짜로 멈춘다");
	}

	/** 체력 30 이 실제로 몇 대인가. 값이 사람 감각에 닿는지 숫자로 남긴다. */
	@Test
	void 체력_30이_네다섯_대다() {
		assertEquals(5, (int) Math.ceil(DragonLastStandObjects.OBJECT_HEALTH / 7.0),
				"다이아 검 7 이면 다섯 대다");
		assertEquals(4, (int) Math.ceil(DragonLastStandObjects.OBJECT_HEALTH / 9.0),
				"힘껏 당긴 활 9 남짓이면 네 발이다");
	}

	// ------------------------------------------------------------------ 진짜 크리스탈을 쓰지 않는다

	/**
	 * ⚠⚠ <b>진짜 엔드 크리스탈을 쓰지 않는다.</b>
	 *
	 * <p>26.3 {@code EnderDragon.checkCrystals} 가 <b>드래곤 상자를 32칸 부풀린 범위</b>의
	 * {@code EndCrystal} 을 찾아 10틱마다 체력 1 을 준다. 오브젝트는 드래곤 발밑 9칸이라 반드시
	 * 그 안이다 — 그리고 크리스탈에는 애초에 체력이 없다.
	 *
	 * <p>⚠ <b>이제 더 나쁘다.</b> 바닐라가 얹는 <b>초당 2</b> 는 우리가 재는 누수에 섞여 사람이 보는
	 * 수를 우리 값이 아니게 만들고, <b>연결되기 전 2초에도 들어간다</b> — 「2초 전에는 아무 일도
	 * 없다」가 그 자리에서 거짓이 된다.
	 */
	@Test
	void 진짜_엔드_크리스탈을_쓰지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("EndCrystal"),
				"진짜 크리스탈은 ① 체력을 담을 수 없고 ② 드래곤을 회복시키고 ③ 위력 6 으로 터진다");
		assertFalse(bytes.contains("END_CRYSTAL;"),
				"개체 종류로 크리스탈을 쓰면 안 된다 — 아이템으로 쓰는 것은 모습뿐이다");
		assertTrue(bytes.contains("ARMOR_STAND"), "맞는 상자와 체력을 들 개체가 없다");

		// 바닐라가 공짜로 얹는 양(10틱마다 1 = 초당 2)을 우리 값과 견준다.
		float max = (float) new SharedFateConfig().dragonHealthPerMember
				* DragonLastStandObjects.COUNT_BY_MEMBERS.length;
		float vanilla = DragonLastStandObjects.SECOND_TICKS / 10.0F;
		assertEquals(2.0F, vanilla, 1.0E-5F, "바닐라가 10틱마다 1 이라 초당 2 다");
		assertTrue(vanilla > DragonLastStandObjects.leakPerSecond(max) * 0.1F,
				"바닐라 몫이 우리 몫의 10% 를 넘는다 — 섞이면 사람이 보는 수가 우리 값이 아니다");
	}

	// ------------------------------------------------------------------ 지우는 길

	/**
	 * ⚠⚠ <b>자리마다 개체가 둘이고 둘 다 저장되지 않으며 스스로도 타 없어진다.</b>
	 *
	 * <p>하나라도 빠지면 다음 판까지 남는다. 특히 <b>부서지는 틱에 그림을 거두지 않으면</b>
	 * 깨뜨린 자리에 때릴 수 없는 크리스탈이 떠 있다.
	 */
	@Test
	void 저장되지_않고_스스로_사라진다() {
		String shard = read("/com/sharedfate/sync/DragonLastStandObjects$Shard.class");
		String shell = read("/com/sharedfate/sync/DragonLastStandShells$Shell.class");
		for (String bytes : new String[] {shard, shell}) {
			assertTrue(bytes.contains("shouldBeSaved"),
					"저장을 막지 않으면 서버 강제 종료에 개체가 월드 파일에 들어간다 — "
							+ "다음 판에 부술 수 없는 것이 떠 있게 된다");
			assertTrue(bytes.contains("discard"), "지우는 길이 없다");
		}
		assertTrue(shard.contains("com/sharedfate/sync/DragonLastStandShells"),
				"부서지는 틱에 그림을 거두지 않으면 때릴 수 없는 크리스탈이 남는다");

		// ⚠⚠ 심지를 「파도 길이 + 여유」로 셀 수 없다 — 파도에 길이가 없어졌다. 페이즈 시계가 기준이다.
		assertTrue(DragonLastStandObjects.FUSE_TICKS
						>= DragonLastStandZone.escalationStartTicks(),
				"심지가 페이즈 시계보다 짧으면 못 부순 크리스탈이 공짜로 사라진다 — "
						+ "사람이 정한 「부술떄까지」가 그 자리에서 거짓이 된다");
		assertEquals((int) DragonLastStandZone.escalationStartTicks()
						+ DragonLastStandObjects.plantTicks(6),
				DragonLastStandObjects.FUSE_TICKS,
				"값에서 직접 나와야 페이즈 길이를 고치는 사람이 여기를 따로 안 고친다");
	}

	/**
	 * ⚠⚠ <b>치우는 자리가 일곱이다.</b> 하나라도 지우면 다음 판에 부술 수 없는 것이 떠 있다.
	 *
	 * <p>바를 걷으면서 사라진 것은 <b>바의 빛뿐</b>이고 상자와 그림을 거두는 일곱은 그대로여야 한다.
	 * 이름으로 못박는다 — 이 저장소가 「한쪽만 막으면 반드시 샌다」를 여러 번 적어 둔 자리다.
	 */
	@Test
	void 치우는_자리_일곱이_그대로다() throws Exception {
		Class<?> shard = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Shard");
		// ①②③④⑤ 상자가 사라지는 자리와 그림을 거두는 자리.
		assertTrue(shard.getDeclaredMethod("shouldBeSaved") != null, "① 저장 막기가 사라졌다");
		assertTrue(shard.getDeclaredMethod("tick") != null, "② 심지가 사라졌다");
		assertTrue(shard.getDeclaredMethod("shatter",
				net.minecraft.server.level.ServerLevel.class) != null, "③ 부서지는 자리가 사라졌다");
		assertTrue(shard.getDeclaredMethod("hurtServer",
						net.minecraft.server.level.ServerLevel.class,
						net.minecraft.world.damagesource.DamageSource.class, float.class) != null,
				"④ 운영자 /kill 자리가 사라졌다");
		Class<?> slot = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Slot");
		assertTrue(DragonLastStandObjects.class.getDeclaredMethod("advance",
						net.minecraft.server.level.ServerLevel.class, slot, int.class) != null,
				"⑤ 상자만 사라진 자리를 쓸어내는 길이 사라졌다");
		// ⑥⑦ 파도가 끝나는 틱과 상태 비우기.
		Class<?> wave = Class.forName("com.sharedfate.sync.DragonLastStandObjects$Wave");
		assertTrue(DragonLastStandObjects.class.getDeclaredMethod("close",
						net.minecraft.server.level.ServerLevel.class, java.util.List.class, wave,
						boolean.class) != null,
				"⑥ 파도가 끝나는 틱이 사라졌다");
		assertTrue(DragonLastStandObjects.class.getDeclaredMethod("clearState") != null,
				"⑦ 상태 비우기가 사라졌다");
		// 그림 쪽도 하나만 지우는 길과 전부 쓸어내는 길이 둘 다 있어야 한다.
		assertTrue(DragonLastStandShells.class.getDeclaredMethod("drop",
				DragonLastStandShells.Shell.class) != null, "그림 하나를 거두는 길이 사라졌다");
		assertTrue(DragonLastStandShells.class.getDeclaredMethod("drop") != null,
				"놓친 그림까지 쓸어내는 길이 사라졌다");
	}

	/** 아무것도 안 세운 상태에서 비워도 탈이 없다. 월드 없는 시험이 부를 수 있는 길이다. */
	@Test
	void 세우지_않았어도_비울_수_있다() {
		DragonLastStandObjects.clearState();
		assertFalse(DragonLastStandObjects.running());
		assertEquals(0, DragonLastStandObjects.liveCount());
		DragonLastStandObjects.clearState();
		assertEquals(0, DragonLastStandObjects.liveCount(), "두 번 비워도 탈이 없어야 한다");
		assertEquals(0, DragonLastStandLights.liveCount(), "빛이 남았다");
		assertEquals(0, DragonLastStandShells.liveCount(), "그림이 남았다");
	}

	/**
	 * ⚠ <b>비우는 길도, 닫는 길도 회복을 주지 않는다.</b>
	 *
	 * <p>누수는 <b>살아 있는 동안 이미 다 주었다</b>({@code leak} 한 곳). 끝나는 자리에서 한 번 더
	 * 얹으면 <b>부순 팀에게 벌을 주는 것</b>이 되고, 전투가 끝나는 자리라면 죽는 드래곤이
	 * 되살아난다.
	 */
	@Test
	void 비우는_길은_회복을_주지_않는다() {
		String bytes = classBytes();
		int heals = bytes.split("heal", -1).length - 1;
		assertTrue(heals > 0, "회복하는 길이 아예 없다");
		assertTrue(bytes.contains("leak"), "누수는 그 한 자리에만 있어야 한다");
		assertTrue(bytes.contains("leakPerTick"), "틱마다 주는 식이 없다");
		assertFalse(bytes.contains("healTotal"), "총량을 굳혀 들고 있으면 부순 뒤에도 흐른다");
	}

	/** 다섯 자리가 전부 배선됐다. 하나라도 빠지면 다음 판에 남는다. */
	@Test
	void 지우는_길이_전부_배선됐다() {
		String stand = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(stand.contains("com/sharedfate/sync/DragonLastStandObjects"),
				"DragonLastStand 가 파도를 부르지도 지우지도 않는다");
		assertTrue(stand.contains("com/sharedfate/sync/DragonLastStandLights"),
				"빛을 거두는 줄이 없다");
		assertTrue(stand.contains("clearState") && stand.contains("onServerStopping"),
				"비우는 자리와 서버 종료 자리가 둘 다 있어야 한다");
		// 그림은 파도의 clearState 가 쓸어낸다 — 그래서 DragonLastStand 를 고치지 않아도
		// 세 자리(전투 종료 · 상태 비우기 · 서버 종료)가 모두 그림까지 거둔다.
		assertTrue(read("/com/sharedfate/sync/DragonLastStandObjects.class")
						.contains("com/sharedfate/sync/DragonLastStandShells"),
				"파도를 비우는 자리가 그림을 쓸어내지 않는다");
	}

	// ------------------------------------------------------------------ 규약과 예산

	/**
	 * ⚠⚠ <b>연결선 여섯 줄을 더한 뒤에도 한 틱 예산 아래다.</b>
	 *
	 * <p>파도는 패턴·번개와 <b>같은 틱에 함께</b> 돈다. 흰 선은 디스플레이 개체라 0점이고, 떨어지는
	 * 줄기·박히는 빛·부서지는 빛·반짝임은 개수를 세는 형태라 꾸러미 한 장씩이다.
	 * <b>연결선만 점을 쓴다</b> — 줄 하나에 찍는 점 수가 거리와 무관하게 고정이라 그 몫이 값에서
	 * 바로 나온다.
	 *
	 * <p>⚠⚠ <b>31 → 37 로 올랐다.</b> 한 틱 점수가 아니라 <b>겹침</b>이 바뀌었다 — 옛 회복은 타이머가
	 * 끝난 뒤에만 돌아 세우는 동작과 절대로 겹치지 않았고(그 구간에는 반짝임이 없었다) 지금은 선이
	 * 상시라 <b>반짝임과 같은 틱에</b> 온다. 박히는 틱이 전부 10의 배수라 그 만남이 <b>우연이
	 * 아니다.</b>
	 */
	@Test
	void 늘어난_점이_예산_아래다() {
		assertEquals(37, DragonLastStandObjects.worstCasePointsPerTick(),
				"연결선 여섯 줄을 두 틱에 나눈 몫 30 + 서 있는 것마다 반짝임 6 + 하트 한 장 1");
		assertTrue(DragonLastStandObjects.BEAM_STRIDE <= TrialEndRain.MARK_MAX_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다 — 넘기면 선이 안 닫힌다");
		int worst = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick();
		// ⚠ 패턴 쪽은 356 이 아니라 360 이다 — 그쪽이 띄움 기둥 넷을 더했고 그쪽 시험이 360 을
		// 못박고 있다(DragonLastStandPatternsTest). 둘이 같은 수를 보므로 어느 쪽이 움직여도
		// 양쪽 시험이 함께 멈춘다.
		assertEquals(397, worst,
				"패턴 360 + 파도 37 이다. 연결선을 늘린 사람도, 패턴을 늘린 사람도 여기서 멈춘다");
		assertTrue(TrialLandingShock.MAX_POINTS_PER_TICK - worst >= 20,
				"예산까지 " + (TrialLandingShock.MAX_POINTS_PER_TICK - worst)
						+ "점뿐이다 — 다음 사람이 쓸 몫이 남아 있어야 한다");
		assertTrue(worst < TrialLandingShock.MAX_POINTS_PER_TICK,
				"한 틱에 " + worst + "점이라 예산 " + TrialLandingShock.MAX_POINTS_PER_TICK
						+ "을 넘는다 — 선의 점 수나 개수를 올린 사람은 여기서 멈출 것");
		assertFalse(classBytes().contains("markGround"),
				"바닥 고리를 매 틱 그리면 번개와 부채꼴 위에 그대로 얹힌다");
	}

	/** 지킬 것 — 파티클은 긴 형식, 팀 소리는 {@code playEach}, 받은 {@code now} 만 쓴다. */
	@Test
	void 파도가_저장소의_규약을_지킨다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"파티클이 긴 형식이 아니다 — 짧은 형식은 32칸에서 잘린다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
		assertTrue(bytes.contains("playEach"), "팀 전체에 알리는 소리가 playEach 를 안 쓴다");
		assertFalse(bytes.contains("showActionBar"), "자막을 쓰지 않는다");
		assertFalse(bytes.contains("showTitle"), "화면 글자는 진입 연출 하나뿐이다");
		assertFalse(bytes.contains("getGameTime"), "받은 now 를 쓸 것");
		assertFalse(bytes.contains("removeBlock") || bytes.contains("setBlockAndUpdate"),
				"블록을 만지는 것은 반구 하나뿐이다");
		assertFalse(bytes.contains("setPhase"), "페이즈는 DragonLastStand.hold 하나만 만진다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 그 안쪽 클래스가 들고 있는 <b>빛(디스플레이 개체)</b> 칸의 수. */
	private static long glowFields(String nested) {
		long count = 0;
		for (Field field : nestedOf(nested).getDeclaredFields()) {
			if (field.getType() == DragonLastStandLights.Glow.class) {
				count++;
			}
		}
		return count;
	}

	/** 그 안쪽 클래스가 들고 있는 빛 칸의 이름. 하나일 때만 뜻이 있다. */
	private static String glowFieldName(String nested) {
		for (Field field : nestedOf(nested).getDeclaredFields()) {
			if (field.getType() == DragonLastStandLights.Glow.class) {
				return field.getName();
			}
		}
		return fail(nested + " 에 빛 칸이 없다");
	}

	/**
	 * 그 이름의 칸이나 함수를 이 클래스가 들고 있는가.
	 *
	 * <p>타이머가 있던 자리의 값과 식이 <b>되살아나지 않았는지</b> 묻는 데 쓴다 — 하나라도 돌아오면
	 * 「부술 때까지」가 그 자리에서 거짓이 된다.
	 */
	private static boolean declares(String name) {
		for (Field field : DragonLastStandObjects.class.getDeclaredFields()) {
			if (field.getName().equals(name)) {
				return true;
			}
		}
		for (Method method : DragonLastStandObjects.class.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	private static Class<?> nestedOf(String nested) {
		try {
			return Class.forName("com.sharedfate.sync.DragonLastStandObjects$" + nested);
		} catch (ClassNotFoundException missing) {
			return fail(nested + " 을 찾지 못했다");
		}
	}

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandObjects.class")
				+ read("/com/sharedfate/sync/DragonLastStandObjects$Shard.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandObjectsTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
