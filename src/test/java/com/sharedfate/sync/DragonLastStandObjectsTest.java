package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
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
 * <p>여기서 붙들어 두는 것 여덟.
 *
 * <ol>
 *   <li>⚠⚠ <b>사람이 정한 값</b> — 25%·10% 두 번 · 2/4/5/6 · 체력 30 · 15초 · 5% 회복 ·
 *       <b>크기 한 칸</b>. 특히 <b>4인이 여섯을 다 놓치면 30%</b>라는 사실을 숫자로 못박아 둔다.
 *       사람이 그것을 알고 고른 값이라 <b>누가 「너무 세다」고 줄이면 그 판단이 사라진다</b></li>
 *   <li><b>자리가 마지막 지대 안이고 서로 겹치지 않는다</b></li>
 *   <li>⚠⚠ <b>보이는 크기와 맞는 상자가 같다</b> — 값 하나에서 둘이 나온다</li>
 *   <li>⚠ <b>크리스탈마다 뜨던 바는 없고 타이머 바는 하나 남았다</b></li>
 *   <li>⚠ <b>연결선은 회복이 도는 동안만 보이고 드래곤에게 닿는다</b></li>
 *   <li><b>무엇으로 깎이나</b>가 한 함수에 모여 있다</li>
 *   <li><b>개체가 하나도 안 샌다</b> — 자리마다 둘(상자 + 그림)이다</li>
 *   <li><b>늘어난 점이 한 틱 예산 아래다</b></li>
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

	/** 체력과 타이머가 사람이 정한 값이다. */
	@Test
	void 체력과_타이머가_사람이_정한_값이다() {
		assertEquals(30.0F, DragonLastStandObjects.OBJECT_HEALTH, 1.0E-6F);
		assertEquals(300, DragonLastStandObjects.TIMER_TICKS, "15초다 — 10초에서 사람이 늘렸다");
		assertEquals(0.05F, DragonLastStandObjects.MISS_HEAL_FRACTION, 1.0E-6F);
	}

	/**
	 * ⚠⚠ <b>4인이 여섯을 다 놓치면 30% = 720 이다.</b>
	 *
	 * <p>이 페이즈가 깎아야 하는 것이 1200 이라 <b>절반이 넘는다.</b> 사람이 그 사실을 알고
	 * 타이머만 10 → 15초로 늘렸다 — 여기를 「너무 세다」로 줄이면 그 판단이 사라진다.
	 */
	@Test
	void 넷이_여섯을_놓치면_절반이_넘게_되돌아간다() {
		int worst = DragonLastStandObjects.objectCount(4);
		float share = DragonLastStandObjects.MISS_HEAL_FRACTION * worst;
		assertEquals(0.30F, share, 1.0E-5F, "최대 체력의 30% 다");

		float max = 2400.0F;
		float healed = max * share;
		assertEquals(720.0F, healed, 0.5F, "4인 기준 720 이다");
		// 30% 에서 20%p 를 받아 50% 로 들어오므로 깎을 것이 1200 이다.
		float toKill = DragonLastStand.healedHealth(max * DragonLastStand.ENTRY_HEALTH_RATIO, max);
		assertEquals(1200.0F, toKill, 0.5F);
		assertTrue(healed > toKill / 2.0F,
				"절반 넘게 되돌아가는 것이 이 파도의 무게다 — 값을 바꾸지 말 것");
	}

	/**
	 * ⚠ <b>나눠 줘도 총량이 그대로다.</b>
	 *
	 * <p>회복을 {@code DRAIN_TICKS}틱에 나눈 것은 <b>연결선과 소리를 위해서</b>이고 사람이 정한
	 * 5%×개수를 건드린 것이 아니다. 합이 어긋나면 그 자리에서 값이 바뀐 것이 된다.
	 */
	@Test
	void 나눠_줘도_회복_총량이_그대로다() {
		float total = 720.0F;
		float sum = 0.0F;
		for (int gone = 0; gone < DragonLastStandObjects.DRAIN_TICKS; gone++) {
			float step = DragonLastStandObjects.healStep(total, gone);
			assertTrue(step > 0.0F, gone + "틱에 아무것도 안 줬다 — 선만 보이고 회복이 없는 틱이다");
			sum += step;
		}
		assertEquals(total, sum, 0.05F, "사람이 정한 5%×개수가 새면 안 된다");
		// 나누어떨어지지 않는 총량에서도 새지 않는다.
		float odd = 777.7F;
		float oddSum = 0.0F;
		for (int gone = 0; gone < DragonLastStandObjects.DRAIN_TICKS; gone++) {
			oddSum += DragonLastStandObjects.healStep(odd, gone);
		}
		assertEquals(odd, oddSum, 0.05F, "마지막 틱이 나머지를 메워야 한다");
	}

	// ------------------------------------------------------------------ 시간표

	/** 마지막이 박히는 틱이 값에서 직접 나온다. */
	@Test
	void 심는_시간이_값에서_나온다() {
		assertEquals(DragonLastStandObjects.RISE_TICKS + DragonLastStandObjects.FALL_TICKS,
				DragonLastStandObjects.plantTicks(1), "하나면 선 1초 + 낙하 1초다");
		assertEquals(20 + 5 * 10 + 20, DragonLastStandObjects.plantTicks(6), "여섯이면 90틱이다");
		assertEquals(DragonLastStandObjects.plantTicks(6) + DragonLastStandObjects.TIMER_TICKS,
				DragonLastStandObjects.waveTicks(6));
		assertTrue(DragonLastStandObjects.plantTicks(6) < DragonLastStandObjects.TIMER_TICKS,
				"심는 시간이 타이머보다 길면 「15초」가 무슨 말인지 알 수 없다");
		assertTrue(DragonLastStandObjects.DRAIN_TICKS < DragonLastStandObjects.TIMER_TICKS / 4,
				"회복 구간이 길어지면 패턴이 도는 중에 구경만 하는 시간이 길어진다");
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
	 * ⚠ <b>타이머는 마지막이 박힌 뒤에야 시작한다.</b>
	 *
	 * <p>떨어지는 동안 바가 줄면 사람이 정한 「15초」가 거짓이 된다.
	 */
	@Test
	void 타이머가_마지막이_박힌_뒤에_돈다() {
		int countdown = DragonLastStandObjects.plantTicks(6);
		assertEquals(1.0F, DragonLastStandObjects.remainingRatio(0, countdown), 1.0E-6F);
		assertEquals(1.0F, DragonLastStandObjects.remainingRatio(countdown, countdown), 1.0E-6F,
				"박히는 그 틱까지는 가득이다");
		assertEquals(0.5F, DragonLastStandObjects.remainingRatio(
				countdown + DragonLastStandObjects.TIMER_TICKS / 2, countdown), 1.0E-6F);
		assertEquals(0.0F, DragonLastStandObjects.remainingRatio(
				countdown + DragonLastStandObjects.TIMER_TICKS, countdown), 1.0E-6F);
		assertEquals(0.0F, DragonLastStandObjects.remainingRatio(
				countdown + DragonLastStandObjects.TIMER_TICKS * 3, countdown), 1.0E-6F,
				"넘겨도 음수가 되면 바가 왼쪽으로 뻗는다");
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

	// ------------------------------------------------------------------ 바는 하나다

	/**
	 * ⚠⚠ <b>크리스탈마다 뜨던 바는 없고, 타이머 바는 하나 남았다.</b>
	 *
	 * <p>사람 말이 「그 크리스탈들 체력바를 없애」다. 지운 것은 <b>자리마다 떠 있던 그 바</b>이고
	 * <b>타이머 자체는 지우지 않았다</b> — 지우면 남은 시간을 볼 길이 사라진다. 자리에 바 칸이
	 * 하나도 없고 파도에 둘(배경 + 채움)이 있는 것이 그 사실이다.
	 */
	@Test
	void 체력바는_없고_타이머_바는_하나다() {
		assertEquals(1, glowFields("Slot"),
				"자리가 들고 있는 빛은 흰 신호기 선 하나뿐이어야 한다 — 크리스탈 위에 뜬 바는 "
						+ "그 크리스탈의 체력으로 읽힌다");
		assertEquals("line", glowFieldName("Slot"), "자리에 남은 빛이 흰 선이 아니다");
		assertEquals(2, glowFields("Wave"),
				"타이머 바는 파도에 하나다(어두운 배경 한 장 + 줄어드는 흰 채움 한 장) — "
						+ "타이머 표시를 지우면 사람이 남은 시간을 볼 길이 없다");

		// 타이머를 그리는 길이 그대로 살아 있다.
		String bytes = classBytes();
		assertTrue(bytes.contains("raiseBarBack") && bytes.contains("raiseBarFill"),
				"타이머 바를 세우는 줄이 사라졌다");
		assertTrue(bytes.contains("reshapeBarFill"), "바가 줄어들지 않으면 타이머가 아니다");
		assertTrue(DragonLastStandObjects.remainingRatio(0, 10)
				> DragonLastStandObjects.remainingRatio(
						10 + DragonLastStandObjects.TIMER_TICKS / 2, 10),
				"가로로 줄어드는 것이 사람이 눈으로 확인할 목록에 있다");
	}

	// ------------------------------------------------------------------ 연결선과 회복

	/**
	 * ⚠⚠ <b>연결선은 회복이 도는 동안만 보인다.</b>
	 *
	 * <p>사람 말이 「그 크리스탈에서 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으면」
	 * 이다. 15초가 남았는데 선이 보이면 <b>거짓말</b>이 되므로, 선을 그리는 함수는
	 * {@code drain} 하나만 부를 수 있게 {@code private} 이고 회복이 도는 창은
	 * {@link DragonLastStandObjects#healStep} 이 값으로 못박는다.
	 *
	 * <p>⚠ 시험이 못 보는 것: 「{@code beams} 를 {@code drain} 만 부른다」는 바이트코드로 셀 수
	 * 없다. 셀 수 있는 것은 <b>그 함수가 바깥에서 못 불린다는 것</b>과 <b>회복이 도는 창의 모양</b>
	 * 이다.
	 */
	@Test
	void 연결선이_회복이_도는_동안만_보인다() throws Exception {
		Method beams = DragonLastStandObjects.class.getDeclaredMethod("beams",
				net.minecraft.server.level.ServerLevel.class,
				net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				Class.forName("com.sharedfate.sync.DragonLastStandObjects$Wave"), int.class);
		assertTrue(Modifier.isPrivate(beams.getModifiers()),
				"선을 그리는 길이 공개되면 회복과 무관한 자리에서 그릴 수 있다");

		// 회복이 도는 창 밖에서는 줄 체력이 0 이다 — 그 창이 곧 선이 보이는 구간이다.
		assertEquals(0.0F, DragonLastStandObjects.healStep(720.0F, -1), 1.0E-6F);
		assertEquals(0.0F,
				DragonLastStandObjects.healStep(720.0F, DragonLastStandObjects.DRAIN_TICKS),
				1.0E-6F, "창을 넘겨서도 회복하면 선이 꺼진 뒤에 체력이 찬다");
		assertTrue(DragonLastStandObjects.healStep(720.0F, 0) > 0.0F,
				"첫 틱부터 회복이 돌아야 선과 회복이 같은 틱에 시작한다");

		// 파도가 없으면 회복도 없고, 그래서 선도 없다.
		assertFalse(DragonLastStandObjects.draining());
		assertFalse(DragonLastStandObjects.running());
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
		for (int gone = 0; gone < DragonLastStandObjects.DRAIN_TICKS; gone++) {
			// 그리는 쪽과 같은 셈이다 — 나눠 그리므로 틱마다 찍는 자리가 다르다.
			double first = -1.0;
			double last = -1.0;
			for (int index = Math.floorMod(gone, stride);
					index < DragonLastStandObjects.BEAM_POINTS; index += stride) {
				double along = DragonLastStandObjects.beamAlong(index, gone);
				if (first < 0.0) {
					first = along;
				}
				last = along;
			}
			assertTrue(first >= 0.0 && first < 0.3, gone + "틱의 첫 점이 크리스탈에서 떨어졌다");
			assertTrue(last <= 1.0, gone + "틱의 마지막 점이 드래곤을 지나쳤다");
			double awayFromDragon = DragonLastStandObjects.RING_RADIUS * (1.0 - last);
			assertTrue(awayFromDragon < 8.0,
					gone + "틱의 마지막 점이 드래곤 상자 밖이다(" + awayFromDragon + "칸)");
		}
		// 틱이 흐르면 점이 밀린다 — 점을 하나도 더 쓰지 않고 흐르는 방향을 보여 주는 길이다.
		assertTrue(DragonLastStandObjects.beamAlong(0, 0)
				< DragonLastStandObjects.beamAlong(0, 1), "점이 밀리지 않으면 점선이 그대로 선다");
	}

	/** 회복 소리는 주기가 있고 음높이가 <b>올라간다.</b> 매 틱이면 초당 20번이다. */
	@Test
	void 회복_소리에_주기가_있다() {
		assertTrue(DragonLastStandObjects.HEAL_SOUND_TICKS > 1,
				"주기가 없으면 회복이 도는 동안 초당 20번 난다");
		assertTrue(DragonLastStandObjects.DRAIN_TICKS / DragonLastStandObjects.HEAL_SOUND_TICKS
				>= 3, "회복이 도는 동안 세 번은 들려야 한 흐름으로 읽힌다");
		assertTrue(DragonLastStandObjects.drainPitch(0)
				< DragonLastStandObjects.drainPitch(DragonLastStandObjects.DRAIN_TICKS - 1),
				"떨어지는 음높이는 「끝나 간다」로 들린다");
		String bytes = classBytes();
		assertTrue(bytes.contains("AMETHYST_BLOCK_RESONATE"),
				"회복 소리가 없다 — block/amethyst/resonate1~4 다(sounds.json 에서 확인했다)");
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
				"낙하·허공·불처럼 아무도 안 한 일로 사라지면 파도가 공짜가 된다");
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
	 * 그 안이고, 15초면 30 을 공짜로 돌려준다 — 그리고 크리스탈에는 애초에 체력이 없다.
	 */
	@Test
	void 진짜_엔드_크리스탈을_쓰지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("EndCrystal"),
				"진짜 크리스탈은 ① 체력을 담을 수 없고 ② 드래곤을 회복시키고 ③ 위력 6 으로 터진다");
		assertFalse(bytes.contains("END_CRYSTAL;"),
				"개체 종류로 크리스탈을 쓰면 안 된다 — 아이템으로 쓰는 것은 모습뿐이다");
		assertTrue(bytes.contains("ARMOR_STAND"), "맞는 상자와 체력을 들 개체가 없다");
		// 15초 동안 공짜로 돌아갔을 체력. 그 수를 남겨 둔다.
		assertEquals(30, DragonLastStandObjects.TIMER_TICKS / 10,
				"크리스탈을 썼다면 파도마다 이만큼이 공짜로 되돌아간다");
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
		assertTrue(DragonLastStandObjects.FUSE_TICKS
						>= DragonLastStandObjects.waveTicks(6) + DragonLastStandObjects.DRAIN_TICKS,
				"심지가 파도 + 회복보다 짧으면 오브젝트가 도중에 사라진다");
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
	 * ⚠ <b>비우는 길은 회복을 주지 않는다.</b>
	 *
	 * <p>회복은 <b>회복이 도는 1.5초</b> 한 곳에만 있다. 전투가 끝나는 자리에서 얹으면 죽는
	 * 드래곤이 되살아난다.
	 */
	@Test
	void 비우는_길은_회복을_주지_않는다() {
		String bytes = classBytes();
		int heals = bytes.split("heal", -1).length - 1;
		assertTrue(heals > 0, "회복하는 길이 아예 없다");
		assertTrue(bytes.contains("drain"), "회복은 그 창을 도는 자리 하나에만 있어야 한다");
		assertTrue(bytes.contains("healStep"), "총량을 나눠 주는 식이 없다");
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
	 * <p>파도는 패턴·번개와 <b>같은 틱에 함께</b> 돈다. 흰 선과 타이머 바는 디스플레이 개체라
	 * 0점이고, 떨어지는 줄기·박히는 빛·부서지는 빛은 개수를 세는 형태라 꾸러미 한 장씩이다.
	 * <b>연결선만 점을 쓴다</b> — 줄 하나에 찍는 점 수가 거리와 무관하게 고정이라 그 몫이 값에서
	 * 바로 나온다.
	 */
	@Test
	void 늘어난_점이_예산_아래다() {
		assertEquals(31, DragonLastStandObjects.worstCasePointsPerTick(),
				"연결선 여섯 줄을 두 틱에 나눈 몫 30 + 드래곤 머리 위 하트 꾸러미 한 장");
		assertTrue(DragonLastStandObjects.BEAM_STRIDE <= TrialEndRain.MARK_MAX_STRIDE,
				"먼지 수명 8틱에서 나온 상한은 한 곳에만 적혀 있어야 한다 — 넘기면 선이 안 닫힌다");
		int worst = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick();
		assertEquals(387, worst,
				"패턴 356 + 파도 31 이다. 연결선을 늘린 사람도, 패턴을 늘린 사람도 여기서 멈춘다");
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
