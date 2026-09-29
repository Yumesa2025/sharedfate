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
 * <p>크리스탈을 고르는 것도 빔을 긋는 것도 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다.
 * 그래도 이 카드가 망가지는 길은 거의 전부 여기서 잡힌다 — <b>초당 한 번이 아니라 매 틱
 * 들어간다</b>, <b>다 맞으면 팀 체력을 넘는다</b>, <b>연출이 32칸에서 잘린다</b>,
 * <b>방어구를 무시하는 피해 종류로 되돌아갔다</b>, <b>드래곤을 건드린다</b>. 전멸하면 월드가
 * 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 */
class TrialCrystalOverchargeTest {

	/** 카드에 적힌 값. 실제 카드와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int FUSE = 300;
	private static final int BEAM = 200;
	private static final int REST = 400;
	private static final float DPS = 1.0F;

	// ------------------------------------------------------------------ 카드에 적힌 무게

	@Test
	void 카드_값이_시험이_보는_값과_같다() {
		TrialCatalog.Risk.CrystalOvercharge risk = card();
		assertEquals(FUSE, risk.fuseTicks(), "도화선 15초");
		assertEquals(BEAM, risk.beamTicks(), "빔 10초");
		assertEquals(REST, risk.restTicks(), "쉼 20초");
		assertEquals(DPS, risk.damagePerSecond(), "초당 1");
	}

	/**
	 * <b>다 맞아도 팀이 죽지 않는다.</b>
	 *
	 * <p>이 카드의 위협은 피해가 아니라 「크리스탈로 끌려가는 것」이다. 여기가 팀 공유 체력을
	 * 넘기는 순간 카드의 뜻이 통째로 바뀌고, 그때는 <b>도망칠 방법이 블록 뒤뿐인 즉사 카드</b>가
	 * 된다.
	 */
	@Test
	void 빔을_끝까지_다_맞아도_팀_체력을_넘지_않는다() {
		TrialCatalog.Risk.CrystalOvercharge risk = card();
		float total = TrialCrystalOvercharge.beamTotalDamage(risk);
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);

		assertEquals(10.0F, total, "초당 1 × 10초 = 10 이다");
		assertTrue(total < teamHealth,
				"팀 공유 체력이 " + teamHealth + " 다. 여기를 넘기면 이 카드가 즉사 카드가 된다");
	}

	@Test
	void 값이_비어도_피해가_0_이다() {
		assertEquals(0.0F, TrialCrystalOvercharge.beamTotalDamage(null));
		assertEquals(0, TrialCrystalOvercharge.beamHits(0, 20), "빔이 없으면 들어갈 초도 없다");
		assertEquals(0, TrialCrystalOvercharge.beamHits(200, 0), "초가 0 틱이면 나눌 수 없다");
	}

	// ------------------------------------------------------------------ 초당 한 번

	/**
	 * <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>피해를 매 틱 넣으면 10초에 200 이다. 팀 체력 20 의 <b>열 배</b>이고, 카드 설명에는
	 * 「초당 1」이라고 적혀 있다. 컴파일도 로그도 조용한 종류의 사고라 여기서 세어 둔다.
	 */
	@Test
	void 빔은_초에_한_번씩_정확히_열_번_들어간다() {
		int hits = 0;
		int last = -1;
		for (int inStep = 0; inStep < BEAM; inStep++) {
			int index = TrialCrystalOvercharge.hitIndex(inStep, TrialCrystalOvercharge.SECOND_TICKS);
			if (index < 0 || index <= last) {
				continue;
			}
			last = index;
			hits++;
		}
		assertEquals(TrialCrystalOvercharge.beamHits(BEAM, TrialCrystalOvercharge.SECOND_TICKS), hits,
				"카드 값에서 뽑은 횟수와 실제로 세어 본 횟수가 다르다");
		assertEquals(10, hits, "10초짜리 빔은 열 번이다");
	}

	/**
	 * 빔이 붙는 <b>그 틱이 0번</b>이다.
	 *
	 * <p>첫 초를 뒤로 미루면 빔이 떠 있는데 아무 일도 없는 1초가 생긴다. 그러면 「가려도 되나」를
	 * 판단할 근거가 첫 1초 동안 없다.
	 */
	@Test
	void 첫_초는_빔이_붙는_그_틱이다() {
		assertEquals(0, TrialCrystalOvercharge.hitIndex(0, 20));
		assertEquals(1, TrialCrystalOvercharge.hitIndex(20, 20));
		assertEquals(9, TrialCrystalOvercharge.hitIndex(180, 20));
		assertEquals(-1, TrialCrystalOvercharge.hitIndex(19, 20), "초 경계가 아니면 들어가지 않는다");
		assertEquals(-1, TrialCrystalOvercharge.hitIndex(-5, 20), "음수 위상은 들어갈 초가 아니다");
		assertEquals(-1, TrialCrystalOvercharge.hitIndex(20, 0), "초가 0 틱이면 나눌 수 없다");
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
	 * <p>이 카드가 요구하는 행동은 「기둥까지 가서 부순다」라 옆걸음보다 훨씬 오래 걸린다.
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
	 * <p>그것까지 고르면 빔이 아레나 바닥에서 나가고, 그 크리스탈은 다음 순간 사람 손에 터져
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
	 * <b>연출이 통째로 사라진다</b> — 「크리스탈 부활」이 정확히 이 함정에 빠진 적이 있다.
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
	 * 빔은 <b>바닐라 크리스탈 빔</b>이다.
	 *
	 * <p>먼지로 직접 그으면 32칸 문제와 「이번 틱에 안 그렸는데 화면에 남아 있다」 문제를 둘 다
	 * 새로 떠안는다. 가려졌을 때 연출을 확실히 끄는 것이 이 카드의 대응 수단이라 후자가 특히
	 * 나쁘다.
	 */
	@Test
	void 빔은_바닐라_크리스탈_빔으로_긋는다() {
		assertTrue(classBytes().contains("setBeamTarget"),
				"바닐라 빔을 쓰지 않으면 거리 제한도 끄는 길도 직접 만들어야 한다");
	}

	/**
	 * 시선 판정은 바닐라 것을 쓴다.
	 *
	 * <p>「블록으로 가리면 끊긴다」의 「블록」이 바닐라가 몹에게 적용하는 「블록」과 같아야
	 * 플레이어가 두 규칙을 따로 배우지 않는다.
	 */
	@Test
	void 시선은_바닐라_판정을_쓴다() {
		assertTrue(classBytes().contains("hasLineOfSight"),
				"시선 판정을 직접 짜면 바닐라와 다른 「가려짐」이 두 개가 된다");
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
	 * 들어 있다.</b> 그것을 쓰면 카드에 적힌 「초당 1」보다 실제로 더 아파지고, 방어구를 갖춰 온
	 * 팀이 손해를 본다.
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
					forbidden + " 를 부른다 — 달아오른 것은 빔을 쏘고 계속 서 있어야 한다");
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
