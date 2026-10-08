package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>2페이지 진입 연출</b>.
 *
 * <p>여기서 붙들어 두는 것 넷.
 *
 * <ol>
 *   <li>⚠⚠ <b>연출 동안 시계가 돌지 않는다.</b> 그것을 지키는 배선이 시각 계산 하나뿐이라
 *       ({@code Stand.clockBase}) 값이 어긋나면 <b>연출 중에 번개가 떨어지고 지대가 줄어든다</b></li>
 *   <li><b>체력이 한 번에 튀지 않고 차오른다.</b> 피해를 막는 것은 그 줄이 아니라
 *       {@code DragonLastStandShield} 다(2026-10-04 — {@code DragonLastStandShieldTest})</li>
 *   <li><b>신호기 넷이 정사각형이고 지대 안</b>이다</li>
 *   <li><b>되돌릴 것을 남기지 않는다</b> — 무적 깃발도 페이즈도 만지지 않는다</li>
 * </ol>
 */
class DragonLastStandEntryTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 시간표

	/** 단계 길이가 값에서 그대로 더해진다. 어림이 아니다. */
	@Test
	void 연출_길이가_값에서_나온다() {
		assertEquals(40, DragonLastStandEntry.DESCEND_TICKS, "하강 2초");
		assertEquals(100, DragonLastStandEntry.CHARGE_TICKS, "충전 5초");
		assertEquals(20, DragonLastStandEntry.SETTLE_TICKS, "여운 1초");
		assertEquals(DragonLastStandEntry.DESCEND_TICKS + DragonLastStandEntry.CHARGE_TICKS,
				DragonLastStandEntry.BOOM_AT, "터지는 틱이 두 단계의 합이다");
		assertEquals(160, DragonLastStandEntry.LENGTH_TICKS, "전체 8초다");
		assertEquals(DragonLastStandEntry.BOOM_AT + DragonLastStandEntry.SETTLE_TICKS,
				DragonLastStandEntry.LENGTH_TICKS);
	}

	/**
	 * 하강이 {@code TrialCrystalRevive.hold} 의 20% 당기기로 <b>반드시 도착</b>하는 길이다.
	 *
	 * <p>{@code TrialEntrance.PERCH_TICKS} 가 같은 값을 같은 근거로 든다 — 짧으면 신호기가 켜질
	 * 때 드래곤이 아직 오는 중이고, 그러면 사람이 정한 순서가 깨진다.
	 */
	@Test
	void 하강이_반드시_도착한다() {
		assertEquals(TrialEntrance.PERCH_TICKS, DragonLastStandEntry.DESCEND_TICKS,
				"입장 연출과 같은 값이어야 근거가 한 곳에 남는다");
		double left = Math.pow(0.8, DragonLastStandEntry.DESCEND_TICKS);
		assertTrue(left * 80.0 < 0.05,
				"아레나를 가로지르는 80칸에서 출발해도 5cm 안이어야 한다 — 남은 " + left * 80.0);
	}

	/** 연출 구간 밖은 돌지 않는다. 되감긴 판(음수)도 마찬가지다. */
	@Test
	void 연출_구간_밖에서는_돌지_않는다() {
		assertFalse(DragonLastStandEntry.running(100L, 99L), "되감긴 판");
		assertTrue(DragonLastStandEntry.running(100L, 100L), "첫 틱");
		assertTrue(DragonLastStandEntry.running(100L, 100L + DragonLastStandEntry.LENGTH_TICKS - 1));
		assertFalse(DragonLastStandEntry.running(100L, 100L + DragonLastStandEntry.LENGTH_TICKS),
				"끝나는 틱부터는 시계가 돈다");
	}

	// ------------------------------------------------------------------ 체력이 차오른다

	/**
	 * ⚠ <b>체력이 한 번에 튀지 않는다.</b> 그리고 목표가
	 * {@code DragonLastStand.healedHealth} 하나에서만 나온다.
	 */
	@Test
	void 체력이_연출에_걸쳐_찬다() {
		float max = 2400.0F;
		float at30 = max * 0.30F;
		float target = DragonLastStand.healedHealth(at30, max);
		assertEquals(max * 0.50F, target, 0.01F, "30% → 50% 다");

		assertEquals(at30, DragonLastStandEntry.rampedHealth(at30, max, 0), 0.01F,
				"내려오는 동안에는 차지 않는다");
		assertEquals(at30, DragonLastStandEntry.rampedHealth(at30, max,
				DragonLastStandEntry.DESCEND_TICKS), 0.01F, "신호기가 켜지는 틱이 출발점이다");
		assertEquals((at30 + target) / 2.0F, DragonLastStandEntry.rampedHealth(at30, max,
				DragonLastStandEntry.DESCEND_TICKS + DragonLastStandEntry.CHARGE_TICKS / 2),
				1.0F, "절반에서 절반이어야 한다 — 선형이다");
		assertEquals(target, DragonLastStandEntry.rampedHealth(at30, max,
				DragonLastStandEntry.BOOM_AT), 0.01F, "터지는 틱에 다 찬다");
		assertEquals(target, DragonLastStandEntry.rampedHealth(at30, max,
				DragonLastStandEntry.LENGTH_TICKS), 0.01F, "그 뒤로는 그대로다");
	}

	/** 차오르는 선이 <b>한 번도 내려가지 않는다.</b> 내려가면 우리가 드래곤을 깎는 것이 된다. */
	@Test
	void 차오르는_선이_내려가지_않는다() {
		float max = 2400.0F;
		float at30 = max * 0.30F;
		float previous = -1.0F;
		for (int step = 0; step <= DragonLastStandEntry.LENGTH_TICKS; step++) {
			float here = DragonLastStandEntry.rampedHealth(at30, max, step);
			assertTrue(here >= previous - 1.0E-3F, step + "틱에서 체력이 내려갔다");
			assertTrue(here <= max, "최대치를 넘었다");
			previous = here;
		}
	}

	/**
	 * 보스바가 <b>눈에 보이는 속도</b>로 찬다.
	 *
	 * <p>사람이 「점점 체력이 차는거」라고 했으므로 한 번 튀는 것과 구별돼야 하고, 너무 느리면
	 * 아무것도 할 수 없는 시간만 길어진다.
	 */
	@Test
	void 차오르는_속도가_읽힌다() {
		double perSecond = DragonLastStand.ENTRY_HEAL_FRACTION
				/ (DragonLastStandEntry.CHARGE_TICKS / 20.0);
		assertEquals(0.04, perSecond, 1.0E-6, "초당 4%p 다");
		assertTrue(DragonLastStandEntry.CHARGE_TICKS >= 60, "3초보다 짧으면 한 번 튀는 것과 같다");
		assertTrue(DragonLastStandEntry.CHARGE_TICKS <= 140, "7초보다 길면 지루하다");
	}

	// ------------------------------------------------------------------ 신호기 넷

	/** ⚠ <b>정사각형이다.</b> 넷이 같은 반변, 서로 다른 네 귀퉁이여야 한다. */
	@Test
	void 신호기_넷이_정사각형이다() {
		assertEquals(4, DragonLastStandEntry.BEACON_COUNT);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (int index = 0; index < DragonLastStandEntry.BEACON_COUNT; index++) {
			Vec3 at = DragonLastStandEntry.beaconOffset(index);
			assertEquals(DragonLastStandEntry.BEACON_HALF_SIDE, Math.abs(at.x), 1.0E-9,
					index + "번째의 x 가 반변이 아니다");
			assertEquals(DragonLastStandEntry.BEACON_HALF_SIDE, Math.abs(at.z), 1.0E-9,
					index + "번째의 z 가 반변이 아니다");
			assertTrue(seen.add(Math.signum(at.x) + "/" + Math.signum(at.z)),
					index + "번째가 앞의 것과 같은 귀퉁이다");
		}
		assertEquals(4, seen.size(), "네 귀퉁이가 다 나와야 정사각형이다");
	}

	/**
	 * ⚠ 신호기가 <b>드래곤 몸 밖이고 첫 지대 안</b>이다.
	 *
	 * <p>앉은 드래곤의 상자가 가로 16칸이라 반이 8 이다. 그보다 안이면 빛이 날개에 묻히고,
	 * 첫 지대(반경 42 의 원)를 넘으면 원 밖에 선다.
	 */
	@Test
	void 신호기가_드래곤_몸_밖이고_지대_안이다() {
		assertTrue(DragonLastStandEntry.BEACON_HALF_SIDE > 8.0,
				"드래곤 몸(가로 16칸)의 반보다 밖이어야 빛이 보인다");
		double corner = DragonLastStandEntry.BEACON_HALF_SIDE * Math.sqrt(2.0);
		assertTrue(corner < DragonLastStandZone.START_RADIUS,
				"모서리가 첫 지대 밖이다 — 실제 " + corner);
		assertTrue(DragonLastStandEntry.BEACON_HEIGHT > 42.0,
				"흑요석 기둥(42칸)보다 낮으면 「하늘로 뻗는다」가 안 된다");
	}

	/** 충전음이 낮은 데서 시작해 1.0 을 넘긴다. 안 넘기면 「올라갔다」가 안 들린다. */
	@Test
	void 충전음이_올라간다() {
		float first = DragonLastStandEntry.chargePitch(DragonLastStandEntry.DESCEND_TICKS);
		float last = DragonLastStandEntry.chargePitch(DragonLastStandEntry.BOOM_AT);
		assertTrue(first < 1.0F, "첫 음이 이미 높으면 오르는 것이 안 들린다");
		assertTrue(last > 1.0F, "마지막 음이 1.0 을 안 넘으면 올라간 것으로 안 들린다");
		assertTrue(first < last);
	}

	// ------------------------------------------------------------------ 지대 밖 사람

	/**
	 * ⚠⚠ <b>안쪽 가장자리가 어느 방향이든 시작 원 안이다.</b>
	 *
	 * <p>2026-10-04 에 지대가 보더(정사각형)를 버리고 원이 됐다. 데려오는 자리가 원 안이고, 원
	 * 경계(빨간 기둥)에서 {@code WALL_MARGIN} 칸 안쪽이라 도착한 사람이 기둥을 제 뒤에 둔다.
	 */
	@Test
	void 데려오는_자리가_어느_방향이든_원_안이다() {
		double edge = DragonLastStandEntry.innerEdge();
		assertTrue(edge > 0.0);
		assertEquals(DragonLastStandZone.START_RADIUS - DragonLastStandEntry.WALL_MARGIN, edge,
				1.0E-9, "값에서 직접 나와야 지대 시작값을 고치는 사람이 여기를 안 고친다");
		assertTrue(DragonLastStandEntry.WALL_MARGIN > DragonLastStandZoneWall.POST_GAP,
				"경계에서 기둥 간격보다 안쪽이어야 「내가 안인가」가 흔들리지 않는다");
		for (int degrees = 0; degrees < 360; degrees++) {
			double radians = Math.toRadians(degrees);
			double x = Math.cos(radians) * edge;
			double z = Math.sin(radians) * edge;
			assertFalse(DragonLastStandZone.outside(x, z, DragonLastStandZone.START_RADIUS),
					degrees + "도 방향이 원 밖이다");
		}
	}

	/** 「이미 안인가」를 지대와 <b>같은 원 판정</b>으로 묻는다. 정사각형 판정이 남으면 두 벌이 된다. */
	@Test
	void 데려오기_판정이_지대와_같은_원이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandZone")
						&& bytes.contains("outside"),
				"DragonLastStandZone.outside 를 부르지 않는다 — 원 판정이 두 벌로 갈라진다");
	}

	/** 낙사를 막는 천장이 남의 함수 그대로다 — 다시 짜면 두 벌이 되어 한쪽만 고쳐진다. */
	@Test
	void 땅을_보장하는_천장이_남의_것_그대로다() {
		assertTrue(classBytes().contains("groundedReach"),
				"땅이 이어진 데까지를 재지 않으면 데려온 사람이 허공에 선다 — 그것이 곧 팀 전멸이다");
		assertTrue(classBytes().contains("surfaceAt"), "지표를 재지 않으면 발밑이 없다");
	}

	/**
	 * ⚠⚠ <b>높이가 뛰는 기둥 앞에서 멈춘다</b> — 2026-10-06 검토에서 확정된 문제.
	 *
	 * <p>반경 39 선은 흑요석 기둥(중심 반경 42, 굵기 4~5)의 발자국을 자른다. 전에는 탐침이 「그 자리에
	 * 지표가 있는가」만 물어, 그 방향에 서 있던 사람이 <b>기둥 꼭대기</b>로 옮겨졌다. 여기서는 +x
	 * 방향 중심 42 · 반지름 4 짜리 기둥(꼭대기 90)을 세우고, 길이 그 발자국을 만나기 전에 멈추는지
	 * 본다. 같은 섬에서 <b>높이를 안 보는 옛 탐침</b>은 39 까지 간다는 것도 함께 확인해, 시험이 실제로
	 * 고친 차이를 재도록 한다.
	 */
	@Test
	void 높이가_뛰는_기둥_앞에서_멈춘다() {
		double anchorY = 65.0;
		double edge = DragonLastStandEntry.innerEdge();
		DragonLastStandEntry.SurfaceProbe island = (x, z) -> {
			if (Math.hypot(x, z) > 45.0) {
				return TrialEnderPulse.NO_GROUND;
			}
			// 중심 (42, 0), 반지름 4 의 기둥. 꼭대기 90.
			return Math.hypot(x - 42.0, z) <= 4.0 ? 90 : 64;
		};

		double old = TrialLandingShock.groundedReach(
				(x, z) -> island.surfaceAt(x, z) != TrialEnderPulse.NO_GROUND,
				0.0, 0.0, 1.0, 0.0, edge);
		assertEquals(edge, old, 1.0E-9, "전제 — 높이를 안 보면 길이 기둥 위까지 간다");

		double reach = DragonLastStandEntry.pullReach(island, anchorY, 0.0, 0.0, 1.0, 0.0, edge);
		assertTrue(reach > 30.0, "기둥 앞까지는 데려와야 한다 — 아무도 안 옮기면 카드가 사라진다(" + reach + ")");
		assertTrue(reach < 38.0, "기둥 발자국(x ≥ 38)에 닿았다 — 꼭대기로 옮겨진다(" + reach + ")");
		assertEquals(64, island.surfaceAt(reach, 0.0), "도착한 자리가 섬 표면이 아니다");

		// 기둥이 없는 방향은 가장자리까지 그대로 간다 — 천장이 카드를 무력화하지 않는다.
		assertEquals(edge, DragonLastStandEntry.pullReach(island, anchorY, 0.0, 0.0, -1.0, 0.0, edge),
				1.0E-9, "기둥이 없는 방향의 길이 짧아졌다");
		assertEquals(edge, DragonLastStandEntry.pullReach(island, anchorY, 0.0, 0.0, 0.0, 1.0, edge),
				1.0E-9, "기둥이 없는 방향의 길이 짧아졌다");
	}

	/**
	 * 평평한 섬에서는 <b>모든 방향이 가장자리까지</b> 간다. 높이 조건이 평지의 데려오기를 건드리면
	 * 사람이 기대한 「있던 방향 그대로 안쪽 가장자리」가 깨진다.
	 */
	@Test
	void 평지에서는_가장자리까지_간다() {
		double edge = DragonLastStandEntry.innerEdge();
		DragonLastStandEntry.SurfaceProbe flat = (x, z) -> 64;
		for (int degrees = 0; degrees < 360; degrees += 5) {
			double radians = Math.toRadians(degrees);
			assertEquals(edge, DragonLastStandEntry.pullReach(flat, 66.0, 0.0, 0.0,
					Math.cos(radians), Math.sin(radians), edge), 1.0E-9, degrees + "도");
		}
		DragonLastStandEntry.SurfaceProbe hole = (x, z) -> x < 20.0 ? 64 : TrialEnderPulse.NO_GROUND;
		assertEquals(19.5, DragonLastStandEntry.pullReach(hole, 66.0, 0.0, 0.0, 1.0, 0.0, edge),
				1.0E-9, "땅이 끊기면 그 앞에서 멈춘다 — groundedReach 의 약속이 그대로다");
	}

	/** 높이 조건의 경계. 낮은 쪽은 묻지 않고, 높은 쪽은 {@code PULL_MAX_RISE} 까지만 봐 준다. */
	@Test
	void 오르막_여유가_경계에서_갈린다() {
		double anchorY = 65.0;
		int rise = (int) DragonLastStandEntry.PULL_MAX_RISE;
		assertTrue(DragonLastStandEntry.PULL_MAX_RISE > 0.0, "여유가 0 이면 평지도 못 데려온다");
		assertTrue(DragonLastStandEntry.PULL_MAX_RISE < 10.0,
				"기둥 꼭대기는 섬 표면에서 10칸 넘게 솟는다 — 여유가 그보다 크면 기둥을 놓친다");
		assertTrue(DragonLastStandEntry.standable((int) anchorY + rise, anchorY), "여유 안은 땅이다");
		assertFalse(DragonLastStandEntry.standable((int) anchorY + rise + 1, anchorY),
				"여유를 넘는 높이는 땅이 아니다");
		assertTrue(DragonLastStandEntry.standable((int) anchorY - 30, anchorY),
				"낮은 쪽은 묻지 않는다 — 섬 가장자리는 원래 낮다");
		assertFalse(DragonLastStandEntry.standable(TrialEnderPulse.NO_GROUND, anchorY),
				"지표가 없으면 땅이 아니다");
	}

	// ------------------------------------------------------------------ 되돌릴 것을 남기지 않는다

	/**
	 * ⚠⚠ <b>드래곤 무적에 되돌릴 것이 없다.</b>
	 *
	 * <p>{@code setInvulnerable} 은 개체와 함께 <b>저장</b>되므로 연출 도중 서버가 죽으면 무적인
	 * 드래곤이 남고 전투가 영영 끝나지 않는다. 고른 길은 <b>매 틱 체력을 쓰는 것</b>이라 우리가
	 * 멈추는 순간 바닐라가 그대로 이어받는다.
	 */
	@Test
	void 무적에_되돌릴_것이_없다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setInvulnerable"),
				"저장되는 무적 깃발을 쓰면 연출 중에 서버가 죽었을 때 되돌릴 기회가 없다");
		assertFalse(bytes.contains("setPhase"), "페이즈는 DragonLastStand.hold 하나만 만진다");
		assertTrue(bytes.contains("setHealth"),
				"체력을 매 틱 쓰는 것이 「점점 차오르는」 회복이다(막는 것은 DragonLastStandShield)");
	}

	/** 지킬 것 — 파티클은 긴 형식, 소리는 {@code playEach}, 받은 {@code now} 만 쓴다. */
	@Test
	void 연출이_저장소의_규약을_지킨다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"파티클이 긴 형식이 아니다 — 짧은 형식은 32칸에서 잘린다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
		assertTrue(bytes.contains("playEach"), "소리가 playEach 를 안 쓰면 사람 수만큼 겹친다");
		assertFalse(bytes.contains("showActionBar"), "자막을 쓰지 않는다");
		assertFalse(bytes.contains("getGameTime"), "받은 now 를 쓸 것 — 시간을 스스로 읽지 않는다");
		assertFalse(bytes.contains("removeBlock") || bytes.contains("setBlockAndUpdate"),
				"블록을 만지는 것은 반구 하나뿐이다");
	}

	/**
	 * ⚠ <b>타이틀은 여기서만 띄운다.</b>
	 *
	 * <p>{@code DragonLastStandTest} 가 {@code DragonLastStand} 쪽에 {@code showTitle} 이 없는
	 * 것을 붙들고 있으므로, 글자가 이 파일에 있어야 한다.
	 */
	@Test
	void 화면_글자가_최후의_저항이다() {
		assertTrue(classBytes().contains("showTitle"), "연출이 끝나는 틱에 글자가 없다");
		assertTrue(classText().contains("최후의 저항"),
				"사람이 「2페이지 시작」은 개발 용어 같다고 해서 고친 글자다");
		assertFalse(classText().contains("2페이지 시작"), "개발 용어를 화면에 띄우지 않는다");
	}

	/** 남은 빛이 {@code clearState} 로 비워진다. 정적이라 월드보다 오래 산다. */
	@Test
	void 남은_빛을_비운다() {
		DragonLastStandEntry.clearState();
		assertEquals(0, DragonLastStandEntry.beaconCount());
		DragonLastStandEntry.clearState();
		assertEquals(0, DragonLastStandEntry.beaconCount(), "두 번 비워도 탈이 없어야 한다");
	}

	// ------------------------------------------------------------------ 시계를 미는 배선

	/**
	 * ⚠⚠ <b>시계들이 연출 길이만큼 밀렸다.</b>
	 *
	 * <p>{@code DragonLastStand} 가 안전지대와 상시 번개에 넘기는 것이 {@code beganAt} 이 아니라
	 * <b>{@code clockBase}</b> 여야 한다. 그 한 칸이 사람이 정한 「그땐 시간 재지말고」의 전부다.
	 */
	@Test
	void 시계가_연출_뒤에_돈다() {
		// 시계의 원점은 Stand 의 칸이고 바깥에서 읽으므로 두 파일을 함께 본다.
		String stand = read("/com/sharedfate/sync/DragonLastStand.class")
				+ read("/com/sharedfate/sync/DragonLastStand$Stand.class");
		assertTrue(stand.contains("clockBase"), "시계의 원점을 따로 들고 있지 않다");
		assertTrue(stand.contains("cinematicUntil"), "연출 중인지를 가르는 칸이 없다");
		assertTrue(stand.contains("com/sharedfate/sync/DragonLastStandEntry"),
				"진입 연출이 배선되지 않았다");
		// 지대가 진입부터 115초에 다 닫히는데, 그 115초는 연출이 끝난 뒤부터여야 한다.
		assertEquals(2300L,
				DragonLastStandZone.LEG_END_TICKS[DragonLastStandZone.LEG_END_TICKS.length - 1],
				"지대가 115초에 닫히는 것은 그대로다 — 원점만 밀렸다");
	}

	/** 한 틱 점 예산. 연출 중에는 번개도 패턴도 안 돌아 남의 것과 겹치지 않는다. */
	@Test
	void 점_예산_안이다() {
		assertEquals(12, DragonLastStandEntry.WORST_TICK_POINTS, "하강 궤적뿐이다");
		assertTrue(DragonLastStandEntry.WORST_TICK_POINTS
						< TrialLandingShock.MAX_POINTS_PER_TICK,
				"예산을 넘었다");
		assertTrue(DragonLastStandEntry.WORST_TICK_POINTS
						< DragonLastStandPatterns.worstCasePointsPerTick(),
				"연출이 이 판의 가장 바쁜 틱을 갈아치우면 안 된다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandEntry.class");
	}

	/** 같은 파일을 UTF-8 로 읽은 것. 상수 풀의 <b>한글 문자열</b>을 찾을 때만 쓴다. */
	private static String classText() {
		return readWith("/com/sharedfate/sync/DragonLastStandEntry.class",
				StandardCharsets.UTF_8);
	}

	private static String read(String path) {
		return readWith(path, StandardCharsets.ISO_8859_1);
	}

	private static String readWith(String path, java.nio.charset.Charset charset) {
		try (InputStream in = DragonLastStandEntryTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
