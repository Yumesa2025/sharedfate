package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「수정 과충전」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>크리스탈을 고르는 것도 구체를 그리는 것도 {@code ServerLevel} 이 있어야 해서 여기서 볼 수
 * 없다. 그래도 이 카드가 망가지는 길은 거의 전부 여기서 잡힌다 — <b>초에 한 발이 아니라 매 틱
 * 날아간다</b>, <b>한 틱에 두 발이 겹친다</b>, <b>마지막 발이 볼리 밖에서 닿는다</b>,
 * <b>총 예산이 빔이던 때와 달라졌다</b>, <b>연출이 32칸에서 잘린다</b>, <b>방어구를 무시하는 피해
 * 종류로 되돌아갔다</b>, <b>진짜 투사체 엔티티를 띄웠다</b>, <b>드래곤을 건드린다</b>. 전멸하면
 * 월드가 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 */
class TrialCrystalOverchargeTest {

	/** 카드에 적힌 값. 실제 카드와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int FUSE = 300;
	/** 볼리가 도는 시간. 카드 칸 이름은 아직 {@code beamTicks} 다. */
	private static final int BEAM = 200;
	private static final int REST = 400;
	/**
	 * 구체 <b>한 발</b>의 피해. 카드에는 아직 「초당 피해」로 적혀 있고, <b>초에 한 발이라 같은
	 * 값이다.</b>
	 *
	 * <p>1 에서 3 으로 올린 값이다 — 사람이 실제로 플레이하고 「딜이 너무 낮다」고 했다. 다 맞아도
	 * 10(무장 기준 1.3)이라 <b>크리스탈을 우선할 이유가 안 됐다.</b> 카드의 위협은 여전히 피해가
	 * 아니라 「크리스탈로 끌려가는 것」이지만, 무시해도 되는 값이면 끌려가지 않는다.
	 */
	private static final float DPS = 3.0F;

	// ------------------------------------------------------------------ 카드에 적힌 무게

	@Test
	void 카드_값이_시험이_보는_값과_같다() {
		TrialCatalog.Risk.CrystalOvercharge risk = card();
		assertEquals(FUSE, risk.fuseTicks(), "도화선 15초");
		assertEquals(BEAM, risk.beamTicks(), "볼리 10초");
		assertEquals(REST, risk.restTicks(), "쉼 20초");
		assertEquals(DPS, risk.damagePerSecond(), "발당 3");
	}

	/**
	 * <b>발수 × 발당 피해 = 빔이던 때의 곱과 같다.</b>
	 *
	 * <p>벌의 모양만 바뀌었다 — 「초당 3 × 10초 = 30」이 「발당 3 × 10발 = 30」이 됐다. 이 곱이
	 * 달라지면 연출을 바꾸면서 <b>난이도까지 바꾼 것</b>이고, 그것은 사람이 시킨 일이 아니다.
	 */
	@Test
	void 구체는_열_발이고_합계가_빔이던_때와_같다() {
		TrialCatalog.Risk.CrystalOvercharge risk = card();

		assertEquals(10, TrialCrystalOvercharge.volleyShots(BEAM, TrialCrystalOvercharge.SECOND_TICKS),
				"10초 볼리는 초에 한 발씩 열 발이다");
		assertEquals(DPS, TrialCrystalOvercharge.orbDamage(risk),
				"발당 피해는 카드에 적힌 값 그대로여야 한다 — 올리면 worstCaseTickDamage 가 거짓이 된다");
		assertEquals(30.0F, TrialCrystalOvercharge.volleyTotalDamage(risk),
				"10발 × 3 = 30. 빔이던 때의 「초당 3 × 10초」와 같은 곱이다");
	}

	/**
	 * <b>다 맞으면 팀 체력을 넘지만 한 틱에 오는 것은 한 발뿐이다.</b>
	 *
	 * <h2>⚠ 「합계가 20 미만」이 이 카드의 안전 근거가 아니다</h2>
	 *
	 * <p>합계는 <b>30</b> 으로 팀 체력 20 을 넘는다. 그래도 즉사 카드가 아닌 것은 <b>대응 수단이
	 * 회피가 아니라 「크리스탈 부수기」와 「블록 뒤에 숨기」</b>이기 때문이다. 숨으면 그 발은 아예
	 * 나가지 않으므로 30 은 <b>열 발을 한 번도 안 가린 사람</b>의 값이다.
	 *
	 * <p>한 틱에 들어오는 것은 언제나 <b>한 발(3)</b>이고, 그것을 무장 기준으로 보는 것은
	 * {@code TrialRisksTest} 의 안전 시험이다 — 그쪽은 이 카드를
	 * 「{@code damagePerSecond} × 1」로 센다.
	 */
	@Test
	void 한_발로_팀을_죽이지_않는다() {
		TrialCatalog.Risk.CrystalOvercharge risk = card();
		float total = TrialCrystalOvercharge.volleyTotalDamage(risk);
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);

		assertTrue(total > teamHealth,
				"합계가 팀 체력 아래로 내려왔다면 발당 피해가 1 로 되돌아간 것이 아닌지 볼 것");
		assertTrue(TrialCrystalOvercharge.orbDamage(risk) < teamHealth,
				"한 발이 팀 체력을 넘으면 숨어도 소용없는 즉사 카드가 된다."
						+ " 실제 " + TrialCrystalOvercharge.orbDamage(risk));
	}

	@Test
	void 값이_비어도_피해가_0_이다() {
		assertEquals(0.0F, TrialCrystalOvercharge.volleyTotalDamage(null));
		assertEquals(0.0F, TrialCrystalOvercharge.orbDamage(null));
		assertEquals(0, TrialCrystalOvercharge.volleyShots(0, 20), "볼리가 없으면 떠날 발도 없다");
		assertEquals(0, TrialCrystalOvercharge.volleyShots(200, 0), "초가 0 틱이면 나눌 수 없다");
	}

	// ------------------------------------------------------------------ 초에 한 발

	/**
	 * <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>매 틱 던지면 10초에 200발이다. 팀 체력 20 의 <b>서른 배</b>이고, 카드 설명에는 「초당 3」이
	 * 적혀 있다. 컴파일도 로그도 조용한 종류의 사고라 여기서 세어 둔다.
	 */
	@Test
	void 발은_초에_한_번씩_정확히_열_번_떠난다() {
		int shots = 0;
		int last = -1;
		for (int inStep = 0; inStep < BEAM; inStep++) {
			int index = TrialCrystalOvercharge.shotIndex(inStep, TrialCrystalOvercharge.SECOND_TICKS);
			if (index < 0 || index <= last) {
				continue;
			}
			last = index;
			shots++;
		}
		assertEquals(TrialCrystalOvercharge.volleyShots(BEAM, TrialCrystalOvercharge.SECOND_TICKS),
				shots, "카드 값에서 뽑은 발수와 실제로 세어 본 발수가 다르다");
		assertEquals(10, shots, "10초짜리 볼리는 열 발이다");
	}

	/**
	 * 첫 발은 볼리가 시작되는 <b>그 틱</b>에 떠난다.
	 *
	 * <p>뒤로 미루면 도화선이 다 탔는데 아무것도 날아오지 않는 1초가 생겨, 「못 부순 벌이 왔다」가
	 * 그 1초 동안 화면에서 사라진다.
	 */
	@Test
	void 첫_발은_볼리가_시작되는_그_틱이다() {
		assertEquals(0, TrialCrystalOvercharge.shotIndex(0, 20));
		assertEquals(1, TrialCrystalOvercharge.shotIndex(20, 20));
		assertEquals(9, TrialCrystalOvercharge.shotIndex(180, 20));
		assertEquals(-1, TrialCrystalOvercharge.shotIndex(19, 20), "초 경계가 아니면 떠나지 않는다");
		assertEquals(-1, TrialCrystalOvercharge.shotIndex(-5, 20), "음수 위상은 떠나는 틱이 아니다");
		assertEquals(-1, TrialCrystalOvercharge.shotIndex(20, 0), "초가 0 틱이면 나눌 수 없다");
	}

	// ------------------------------------------------------------------ 구체 하나가 한 사건이다

	/**
	 * <b>한 틱에 두 발이 닿지 않는다.</b>
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 가 이 카드를 「한 발 × 1」로 세고 있다. 비행이 발
	 * 간격보다 길어지면 앞 발이 닿는 틱에 다음 발이 이미 날고 있어 <b>한 틱에 두 발</b>이 닿을 수
	 * 있고, 그러면 그 셈이 거짓이 되어 안전 시험이 실제의 절반을 본다.
	 *
	 * <p>화면에서도 사이가 비어야 한 발이 「던져서 맞는」 한 사건으로 읽힌다 — 끊김 없이 이어지면
	 * 사람이 「안 보인다」고 한 빔으로 되돌아간 것과 같다.
	 */
	@Test
	void 한_틱에_두_발이_겹치지_않는다() {
		assertTrue(TrialCrystalOvercharge.ORB_GAP_TICKS > 0,
				"발 사이가 비지 않으면 구체가 빔으로 읽힌다");
		assertTrue(TrialCrystalOvercharge.ORB_FLIGHT_TICKS
						< TrialCrystalOvercharge.SECOND_TICKS,
				"비행이 발 간격보다 길면 한 틱에 두 발이 닿을 수 있다");

		int flying = 0;
		for (int inStep = 0; inStep < BEAM; inStep++) {
			int index = TrialCrystalOvercharge.shotIndex(inStep, TrialCrystalOvercharge.SECOND_TICKS);
			int landings = 0;
			for (int shot = 0; shot < TrialCrystalOvercharge
					.volleyShots(BEAM, TrialCrystalOvercharge.SECOND_TICKS); shot++) {
				int firedAt = shot * TrialCrystalOvercharge.SECOND_TICKS;
				if (firedAt + TrialCrystalOvercharge.ORB_FLIGHT_TICKS == inStep) {
					landings++;
				}
			}
			assertTrue(landings <= 1, "위상 " + inStep + " 에 두 발이 닿는다");
			if (index >= 0) {
				flying++;
			}
			flying -= landings;
			assertTrue(flying <= 1, "위상 " + inStep + " 에 두 발이 함께 날고 있다");
		}
		assertEquals(0, flying, "볼리가 끝났는데 아직 날고 있는 구체가 있다");
	}

	/**
	 * <b>마지막 발이 볼리 안에서 닿는다.</b>
	 *
	 * <p>볼리를 벗어난 구체는 그려 놓은 궤적째로 사라진다 — 「던졌는데 아무 일도 없었다」가 되고
	 * 적힌 예산도 그 한 발만큼 빈다.
	 */
	@Test
	void 마지막_발이_볼리_안에서_닿는다() {
		int last = TrialCrystalOvercharge.lastImpactTick(BEAM, TrialCrystalOvercharge.SECOND_TICKS,
				TrialCrystalOvercharge.ORB_FLIGHT_TICKS);
		assertEquals(196, last, "열 번째 발은 위상 180 에 떠나 196 에 닿는다");
		assertTrue(last < BEAM, "마지막 발이 쉼으로 넘어간 뒤에 닿는다 — 궤적이 조용히 사라진다");
		assertEquals(0, TrialCrystalOvercharge.lastImpactTick(0, 20, 16), "볼리가 없으면 닿을 것도 없다");
	}

	@Test
	void 비행_진행도는_0_과_1_을_벗어나지_않는다() {
		assertEquals(0.0, TrialCrystalOvercharge.flightProgress(0, 0, 16));
		assertEquals(0.5, TrialCrystalOvercharge.flightProgress(8, 0, 16), 1.0E-9);
		assertEquals(1.0, TrialCrystalOvercharge.flightProgress(16, 0, 16));
		assertEquals(1.0, TrialCrystalOvercharge.flightProgress(99, 0, 16),
				"1 을 넘으면 구체가 사람을 지나쳐 날아간다");
		assertEquals(0.0, TrialCrystalOvercharge.flightProgress(-5, 0, 16),
				"되감긴 판에서도 떠난 자리보다 뒤로 가지 않는다");
		assertEquals(1.0, TrialCrystalOvercharge.flightProgress(0, 0, 0),
				"비행이 0 틱이면 떠난 틱이 곧 닿는 틱이다");
	}

	/**
	 * 막힌 구체는 <b>사람보다 앞에서</b> 터진다.
	 *
	 * <p>사람 자리에서 터뜨리면 「맞았다」와 똑같이 보여, 가린 것이 일했는지 알 수 없다 —
	 * 「연결된 수정」이 막힌 것을 안 보여 줘서 「버그인가」로 읽힌 적이 있다.
	 */
	@Test
	void 막힌_것과_맞은_것이_다르게_보인다() {
		assertTrue(TrialCrystalOvercharge.BLOCKED_PULLBACK > 0.0,
				"0 이면 막힌 구체가 사람 자리에서 터져 맞은 것과 구별되지 않는다");
	}

	// ------------------------------------------------------------------ 도화선

	@Test
	void 도화선은_적힌_틱만큼_타고_그_뒤에_쏜다() {
		assertEquals(FUSE, TrialCrystalOvercharge.remainingFuse(0, FUSE), "첫 틱에는 다 남아 있다");
		assertEquals(1, TrialCrystalOvercharge.remainingFuse(FUSE - 1, FUSE));
		assertEquals(0, TrialCrystalOvercharge.remainingFuse(FUSE, FUSE), "이 틱에 쏜다");
		assertEquals(0, TrialCrystalOvercharge.remainingFuse(FUSE + 500, FUSE),
				"지나쳐도 음수로 내려가지 않는다");
		assertEquals(FUSE, TrialCrystalOvercharge.remainingFuse(-10, FUSE),
				"음수 위상은 시작 틱과 같게 본다 — 되감긴 판에서 도화선을 건너뛰면 안 된다");
		assertEquals(0, TrialCrystalOvercharge.remainingFuse(0, 0),
				"도화선이 없는 카드는 첫 틱에 곧바로 쏜다");
	}

	/**
	 * 도화선은 <b>옆걸음보다 길다.</b>
	 *
	 * <p>이 카드의 예고는 구체의 비행이 아니라 <b>도화선</b>이다 — 구체는 사람을 따라가므로 옆으로
	 * 비켜서 피하는 것이 아니고, 요구하는 행동은 「기둥까지 가서 부순다」와 「블록 뒤에 숨는다」다.
	 * {@code TrialRisksTest} 가 카드 값 쪽에서 같은 것을 붙잡지만, 값이 아니라 <b>실행기가 보는
	 * 남은 시간</b>이 경고 세 층을 다 지나는지는 여기서만 셀 수 있다.
	 */
	@Test
	void 도화선이_경고_세_층을_모두_지난다() {
		boolean approach = false;
		boolean mark = false;
		boolean imminent = false;
		for (int inStep = 0; inStep < FUSE; inStep++) {
			TrialWarning.Stage stage =
					TrialWarning.stageFor(TrialCrystalOvercharge.remainingFuse(inStep, FUSE));
			if (stage == TrialWarning.Stage.APPROACH) {
				approach = true;
			}
			if (stage == TrialWarning.Stage.MARK) {
				mark = true;
			}
			if (stage == TrialWarning.Stage.IMMINENT) {
				imminent = true;
			}
		}
		assertTrue(approach && mark && imminent,
				"도화선이 짧아 경고 층이 잘렸다 — 소리만으로는 무엇이 오는지 못 듣는다");
	}

	@Test
	void 진행도는_0_과_1_을_벗어나지_않는다() {
		assertEquals(0.0F, TrialCrystalOvercharge.fuseProgress(0, FUSE));
		assertEquals(0.5F, TrialCrystalOvercharge.fuseProgress(FUSE / 2, FUSE), 1.0E-6F);
		assertEquals(1.0F, TrialCrystalOvercharge.fuseProgress(FUSE, FUSE));
		assertEquals(1.0F, TrialCrystalOvercharge.fuseProgress(FUSE * 10L, FUSE),
				"1 을 넘으면 빛기둥이 약속한 높이보다 더 자란다");
		assertEquals(0.0F, TrialCrystalOvercharge.fuseProgress(-50, FUSE));
		assertEquals(1.0F, TrialCrystalOvercharge.fuseProgress(0, 0),
				"도화선이 없는 카드는 첫 틱이 곧 마지막 틱이다");
	}

	/**
	 * 빛기둥은 <b>처음부터 보이고, 자라기만 한다.</b>
	 *
	 * <p>0 에서 시작하면 달아오른 크리스탈이 어느 것인지 도화선 앞부분 내내 알 수 없고, 줄어들면
	 * 「다 차면 쏜다」가 거짓말이 된다.
	 */
	@Test
	void 빛기둥은_처음부터_보이고_줄어들지_않는다() {
		assertEquals(TrialCrystalOvercharge.COLUMN_MIN, TrialCrystalOvercharge.columnHeight(0.0F));
		assertEquals(TrialCrystalOvercharge.COLUMN_MAX, TrialCrystalOvercharge.columnHeight(1.0F));
		assertTrue(TrialCrystalOvercharge.COLUMN_MIN > 0,
				"0 이면 선이 시작된 것 자체를 못 본다");
		assertTrue(TrialCrystalOvercharge.COLUMN_MAX > TrialCrystalOvercharge.COLUMN_MIN,
				"자라지 않으면 남은 시간을 말하지 못한다");

		int before = TrialCrystalOvercharge.columnHeight(0.0F);
		for (int step = 1; step <= FUSE; step++) {
			int now = TrialCrystalOvercharge.columnHeight((float) step / FUSE);
			assertTrue(now >= before, "빛기둥이 도중에 줄어들었다 — 위상 " + step);
			before = now;
		}
		assertEquals(TrialCrystalOvercharge.COLUMN_MIN, TrialCrystalOvercharge.columnHeight(-1.0F),
				"범위 밖은 잘라 낸다");
		assertEquals(TrialCrystalOvercharge.COLUMN_MAX, TrialCrystalOvercharge.columnHeight(9.0F));
	}

	@Test
	void 먼지도_같은_기울기로_는다() {
		assertEquals(TrialCrystalOvercharge.EMBER_MIN, TrialCrystalOvercharge.emberCount(0.0F));
		assertEquals(TrialCrystalOvercharge.EMBER_MAX, TrialCrystalOvercharge.emberCount(1.0F));
		assertTrue(TrialCrystalOvercharge.EMBER_MIN > 0, "첫 틱에도 붉어야 「달아올랐다」로 읽힌다");
	}

	// ------------------------------------------------------------------ 기둥 위의 것만 고른다

	/**
	 * 사람이 손에 들고 놓은 크리스탈은 고르지 않는다.
	 *
	 * <p>그것까지 고르면 구체가 아레나 바닥에서 나가고, 그 크리스탈은 다음 순간 사람 손에 터져
	 * 카드가 아무 일도 안 한 것이 된다.
	 */
	@Test
	void 기둥_자리에_선_것만_고른다() {
		List<Vec3> seats = List.of(new Vec3(42.5, 77.0, 0.5), new Vec3(0.5, 104.0, 42.5));

		assertTrue(TrialCrystalOvercharge.onSeat(new Vec3(42.5, 77.0, 0.5), seats),
				"바닐라 크리스탈은 자리와 좌표가 정확히 같다");
		assertTrue(TrialCrystalOvercharge.onSeat(new Vec3(43.5, 78.0, 0.5), seats),
				"조금 어긋난 것까지는 같은 자리로 본다");
		assertFalse(TrialCrystalOvercharge.onSeat(new Vec3(0.5, 63.0, 0.5), seats),
				"아레나 바닥에 놓은 폭탄용 크리스탈은 기둥 것이 아니다");
		assertFalse(TrialCrystalOvercharge.onSeat(new Vec3(42.5, 63.0, 0.5), seats),
				"가로가 같아도 기둥 아래면 다른 것이다 — 세로를 같이 봐야 한다");
		assertFalse(TrialCrystalOvercharge.onSeat(new Vec3(42.5, 77.0, 0.5), List.of()),
				"기둥이 없는 판에서는 고를 것도 없다");
	}

	// ------------------------------------------------------------------ 컴파일된 클래스가 지키는 것

	/**
	 * 연출이 <b>긴 형태</b>로만 나간다.
	 *
	 * <p>크리스탈은 반경 42 기둥 꼭대기(y 76~103)라 바닥에 선 사람과 거의 언제나 32 블록 밖이다.
	 * 짧은 형태로 되돌리면 서버가 패킷을 아예 안 보내고 클라이언트가 한 번 더 거르므로
	 * <b>구체가 떠나는 구간이 통째로 사라진다</b> — 그러면 「날아오는 것이 보인다」가 아니다.
	 */
	@Test
	void 파티클은_긴_형태로만_나간다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 기둥 꼭대기의 연출이 바닥에 선 사람에게 안 간다");
	}

	/**
	 * <b>진짜 투사체 엔티티를 띄우지 않는다.</b>
	 *
	 * <p>바닐라 투사체는 저마다 우리가 끌 수 없는 것을 딸고 온다(26.3 바이트코드로 확인했다) —
	 * {@code DragonFireball} 은 {@code AreaEffectCloud} 장판을 남기고, {@code ShulkerBullet} 은
	 * {@code LEVITATION} 을 걸며 <b>유도</b>라 「가리면 막힌다」가 성립하지 않고, 화염구 쪽은 블록
	 * 파괴가 {@code mobGriefing} 에 묶여 우리만 끌 수가 없다. 「표적」과 「기둥 화염구」가 이미
	 * 파티클로 그리고 착탄을 직접 계산하는 길을 골랐고, 여기도 같은 답이다.
	 */
	@Test
	void 진짜_투사체_엔티티를_띄우지_않는다() {
		String bytes = classBytes();
		for (String forbidden : new String[] {"Fireball", "ShulkerBullet", "AreaEffectCloud",
				"addFreshEntity"}) {
			assertFalse(bytes.contains(forbidden),
					forbidden + " 가 들어왔다 — 바닐라가 딸고 오는 동작을 우리가 끌 수 없다");
		}
	}

	/**
	 * 달아오른 것을 가리키는 신호는 <b>바닐라 크리스탈 빔</b>이다.
	 *
	 * <p>먼지로 직접 그으면 32칸 문제와 「이번 틱에 안 그렸는데 화면에 남아 있다」 문제를 둘 다
	 * 새로 떠안는다. 어느 크리스탈인지는 아레나 어디에서든 보여야 하므로 거리 제한이 없는 개체
	 * 데이터를 쓴다.
	 *
	 * <p>⚠ <b>그 빔이 사람을 겨누게 되돌리지 말 것.</b> 그것이 사람이 「가시성이 안 좋다」고 한
	 * 공격이다. 지금 빔은 곧게 위로 선 빛기둥이고, 공격은 구체가 말한다.
	 */
	@Test
	void 빛기둥은_바닐라_크리스탈_빔으로_세운다() {
		assertTrue(classBytes().contains("setBeamTarget"),
				"바닐라 빔을 쓰지 않으면 거리 제한도 끄는 길도 직접 만들어야 한다");
	}

	/**
	 * 시선 판정은 바닐라 것을 쓴다.
	 *
	 * <p>「블록으로 가리면 막힌다」의 「블록」이 바닐라가 몹에게 적용하는 「블록」과 같아야
	 * 플레이어가 두 규칙을 따로 배우지 않는다. 구체가 파티클이라 <b>바닐라가 벽을 대신 봐 주지
	 * 않으므로</b>, 쏘는 틱과 닿는 틱 두 곳에서 직접 물어야 한다.
	 */
	@Test
	void 시선은_바닐라_판정을_쓴다() {
		assertTrue(classBytes().contains("hasLineOfSight"),
				"시선 판정을 직접 짜면 바닐라와 다른 「가려짐」이 두 개가 된다");
	}

	/**
	 * 소리를 <b>자리에 놓지 않는다.</b>
	 *
	 * <p>크리스탈은 반경 42 기둥 꼭대기라 볼륨 1 의 16 블록은 아무에게도 안 닿고, 사람마다 자리에
	 * 놓으면 <b>사람 수만큼 겹친다</b>({@link TrialWarning#playEach} 의 「사람마다 한 번」 — 이
	 * 저장소가 같은 실수를 열일곱 자리에서 고쳤다).
	 */
	@Test
	void 소리는_사람마다_한_번만_나간다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("playEach"), "사람마다 한 번 보내는 길을 쓰지 않는다");
		assertFalse(bytes.contains("playSound"),
				"자리에 소리를 놓았다 — 기둥 꼭대기 소리는 아무에게도 안 가고 팀원 루프에서는 겹친다");
	}

	/**
	 * 발사음과 착탄음이 <b>다른 소리</b>다.
	 *
	 * <p>「던졌다」와 「맞았다」가 같은 소리면 화면을 안 보고 있을 때 둘을 구별할 수 없고, 이 카드가
	 * 고치려는 것이 바로 그 「무엇에 맞고 있는지 모르겠다」다. 둘 다 26.3 {@code sounds.json} 에서
	 * 파일까지 확인한 것이다 — {@code block.conduit.attack.target} 은
	 * {@code block/conduit/attack1~3}, {@code block.amethyst_block.resonate} 는
	 * {@code block/amethyst/resonate1~4} 이고 이 저장소의 다른 카드와 겹치지 않는다.
	 */
	@Test
	void 발사음과_착탄음이_다르다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("CONDUIT_ATTACK_TARGET"), "발사음이 사라졌다");
		assertTrue(bytes.contains("AMETHYST_BLOCK_RESONATE"), "착탄음이 사라졌다");
	}

	/**
	 * <b>드래곤을 건드리지 않는다.</b>
	 *
	 * <p>드래곤 페이즈에 끼어들었다가 <b>착지를 아예 안 하게</b> 된 사고가 있었다
	 * ({@code TrialDragonFocus} 클래스 설명). 컴파일도 로그도 조용한 종류라 상수 풀에서 본다.
	 */
	@Test
	void 드래곤에게_손대지_않는다() {
		String bytes = classBytes();
		for (String forbidden : new String[] {"setPhase", "getPhaseManager", "setTarget"}) {
			assertFalse(bytes.contains(forbidden),
					forbidden + " 을 부른다 — 드래곤이 착지를 멈춘 사고가 이 자리에서 났다");
		}
	}

	/**
	 * 얼어붙은 판에서는 시간이 가지 않아야 한다.
	 *
	 * <p>{@code level.getGameTime()} 을 부르면 「판을 멈춘다」는 다른 카드가 이 카드를 못 멈춘다.
	 * 시각은 전부 {@code now} 와 {@code grantedTick} 에서 나와야 한다.
	 */
	@Test
	void 월드_시간을_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"월드 시간을 읽는다 — 얼어붙은 판에서도 도화선이 탄다");
	}

	/**
	 * 방어구를 무시하는 피해 종류로 돌아가지 않는다.
	 *
	 * <p>26.3 에서 {@code magic} 과 {@code dragonBreath} 는 <b>{@code bypasses_armor} 태그에
	 * 들어 있다.</b> 그것을 쓰면 카드에 적힌 「3」보다 실제로 더 아파지고, 방어구를 갖춰 온 팀이
	 * 손해를 본다.
	 *
	 * <p>⚠ <b>「표적」을 따라가지 말 것.</b> 그 카드만 {@code magic} 을 써서 방어구를 지나가므로,
	 * 거기 적힌 6 과 여기 적힌 3 은 <b>같은 자로 잰 값이 아니다.</b> 구체를 쓴다는 것만 같다.
	 */
	@Test
	void 방어구를_무시하는_피해로_때리지_않는다() {
		String bytes = classBytes();
		for (String bypassing : new String[] {"magic", "dragonBreath"}) {
			assertFalse(bytes.contains(bypassing),
					bypassing + " 은 bypasses_armor 다 — 적힌 것보다 아파진다");
		}
		assertTrue(bytes.contains("explosion"),
				"다른 실행기들이 쓰는 피해 종류를 따라야 사망 메시지와 보호 계산이 어긋나지 않는다");
	}

	/**
	 * 크리스탈을 <b>우리가 부수지 않는다.</b>
	 *
	 * <p>도화선이 끝나도 크리스탈은 그대로 선다. 부숴 버리면 「못 부순 벌」이 「알아서 사라짐」이
	 * 되어 카드가 스스로를 지운다.
	 */
	@Test
	void 크리스탈을_우리가_부수지_않는다() {
		String bytes = classBytes();
		for (String forbidden : new String[] {"RemovalReason", "discard", "kill"}) {
			assertFalse(bytes.contains(forbidden),
					forbidden + " 를 부른다 — 달아오른 것은 구체를 던지고 계속 서 있어야 한다");
		}
	}

	// ------------------------------------------------------------------ 거들기

	private static TrialCatalog.Risk.CrystalOvercharge card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:crystal_overcharge");
		assertNotNull(trial, "「수정 과충전」 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.CrystalOvercharge overcharge) {
				return overcharge;
			}
		}
		return fail("「수정 과충전」 카드가 과충전 위험을 걸고 있지 않다");
	}

	private static String classBytes() {
		try (InputStream in = TrialCrystalOvercharge.class
				.getResourceAsStream("/com/sharedfate/sync/TrialCrystalOvercharge.class")) {
			if (in == null) {
				return fail("TrialCrystalOvercharge 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
