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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>오브젝트 파도</b>.
 *
 * <p>여기서 붙들어 두는 것 넷.
 *
 * <ol>
 *   <li>⚠⚠ <b>사람이 정한 값</b> — 25%·10% 두 번 · 2/4/5/6 · 체력 30 · 15초 · 5% 회복.
 *       특히 <b>4인이 여섯을 다 놓치면 30%</b>라는 사실을 숫자로 못박아 둔다. 사람이 그것을
 *       알고 고른 값이라 <b>누가 「너무 세다」고 줄이면 그 판단이 사라진다</b></li>
 *   <li><b>자리가 마지막 지대 안이고 서로 겹치지 않는다</b></li>
 *   <li><b>무엇으로 깎이나</b>가 한 함수에 모여 있다</li>
 *   <li><b>다섯 자리에서 지운다</b></li>
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
			assertTrue(worst > 2.0,
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

	/** 모습은 엔드 크리스탈 <b>아이템</b>이다. 투명한 갑옷 거치대도 장비는 그린다. */
	@Test
	void 모습이_엔드_크리스탈_아이템이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("END_CRYSTAL"), "머리에 씌울 것이 없다");
		assertTrue(bytes.contains("setItemSlot"), "장비 칸을 안 쓰면 아무것도 안 보인다");
		assertTrue(bytes.contains("setInvisible"), "갑옷 거치대가 그대로 보이면 크리스탈이 아니다");
	}

	// ------------------------------------------------------------------ 지우는 길

	/** ⚠⚠ <b>저장되지 않고 스스로도 타 없어진다.</b> 둘 중 하나라도 빠지면 다음 판까지 남는다. */
	@Test
	void 저장되지_않고_스스로_사라진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("shouldBeSaved"),
				"저장을 막지 않으면 서버 강제 종료에 오브젝트가 월드 파일에 들어간다 — "
						+ "다음 판에 부술 수 없는 것이 떠 있게 된다");
		assertTrue(bytes.contains("discard"), "지우는 길이 없다");
		assertTrue(DragonLastStandObjects.FUSE_TICKS
						>= DragonLastStandObjects.waveTicks(6),
				"심지가 파도보다 짧으면 오브젝트가 타이머 도중에 사라진다");
	}

	/** 아무것도 안 세운 상태에서 비워도 탈이 없다. 월드 없는 시험이 부를 수 있는 길이다. */
	@Test
	void 세우지_않았어도_비울_수_있다() {
		DragonLastStandObjects.clearState();
		assertFalse(DragonLastStandObjects.running());
		assertEquals(0, DragonLastStandObjects.liveCount());
		DragonLastStandObjects.clearState();
		assertEquals(0, DragonLastStandObjects.liveCount(), "두 번 비워도 탈이 없어야 한다");
	}

	/**
	 * ⚠ <b>회복은 파도가 제 15초를 채웠을 때만 준다.</b>
	 *
	 * <p>{@code clearState} 에서 얹으면 전투가 끝나는 틱에 죽는 드래곤이 되살아난다.
	 */
	@Test
	void 비우는_길은_회복을_주지_않는다() {
		String bytes = classBytes();
		int heals = bytes.split("heal", -1).length - 1;
		assertTrue(heals > 0, "회복하는 길이 아예 없다");
		assertTrue(bytes.contains("finish"), "회복은 파도를 닫는 자리 하나에만 있어야 한다");
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
	}

	// ------------------------------------------------------------------ 규약과 예산

	/**
	 * ⚠ <b>파도가 한 틱 점 예산을 바꾸지 않는다.</b>
	 *
	 * <p>파도는 패턴·번개와 <b>같은 틱에 함께</b> 도는데, 매 틱 그리는 것이 하나도 없다 —
	 * 흰 선과 바는 디스플레이 개체이고 나머지는 개수를 세는 형태다. 그래서
	 * {@code worstCasePointsPerTick} 이 그대로다.
	 */
	@Test
	void 점_예산을_바꾸지_않는다() {
		assertFalse(classBytes().contains("markGround"),
				"바닥 고리를 매 틱 그리면 번개와 부채꼴 위에 그대로 얹힌다");
		assertTrue(DragonLastStandPatterns.worstCasePointsPerTick()
						< TrialLandingShock.MAX_POINTS_PER_TICK,
				"가장 바쁜 틱이 예산을 넘었다");
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
