package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 드래곤 표적 고르기에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>페이즈에 대상을 심는 것은 {@code EnderDragon} 이 있어야 해서 여기서 볼 수 없다. 그런데 이
 * 카드가 망가지는 길은 거의 전부 「누구를 고르는가」 쪽이다 — <b>한 사람이 영영 물린다</b>,
 * <b>깬 사람이 없어서 아무도 안 물린다</b>, <b>나간 사람을 계속 노린다</b>. 전멸하면 월드가
 * 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 */
class TrialDragonFocusTest {
	/** 「표적」 카드의 재지정 간격. 실제 카드 값과 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int RETARGET = 100;
	private static final long GRANTED = 1000L;

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
	private static final List<UUID> FOUR = List.of(A, B, C, D);
	/** 명단에 없는 사람. 접속을 끊었거나 관전으로 넘어갔다. */
	private static final UUID GONE = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

	// ------------------------------------------------------------------ 주기

	@Test
	void 표적은_한_주기_동안_바뀌지_않는다() {
		RandomSource random = RandomSource.create(7L);
		TrialDragonFocus.Selection held = null;
		TrialDragonFocus.Selection first = null;
		for (long now = GRANTED; now <= GRANTED + RETARGET; now++) {
			long cycle = TrialRisks.strikeIndex(now, GRANTED, RETARGET);
			held = TrialDragonFocus.select(held, cycle, FOUR, TrialCatalog.Risk.Focus.RANDOM,
					null, random);
			if (first == null) {
				first = held;
				continue;
			}
			assertSame(first, held,
					"주기 안에서 다시 고르면 표식이 사람들 사이를 뛰어다녀 아무도 대응할 수 없다: " + now);
		}
	}

	@Test
	void 주기가_넘어가면_다시_고른다() {
		RandomSource random = RandomSource.create(7L);
		long insideCycle = TrialRisks.strikeIndex(GRANTED + RETARGET, GRANTED, RETARGET);
		long nextCycle = TrialRisks.strikeIndex(GRANTED + RETARGET + 1L, GRANTED, RETARGET);
		assertNotEquals(insideCycle, nextCycle, "주기가 넘어가는 자리를 잘못 잡았다");

		TrialDragonFocus.Selection held = TrialDragonFocus.select(null, insideCycle, FOUR,
				TrialCatalog.Risk.Focus.RANDOM, null, random);
		TrialDragonFocus.Selection next = TrialDragonFocus.select(held, nextCycle, FOUR,
				TrialCatalog.Risk.Focus.RANDOM, null, random);
		assertNotSameSelection(held, next);
		assertEquals(nextCycle, next.cycle());
	}

	@Test
	void retargetTicks_가_0_이하면_처음_고른_사람을_영영_유지한다() {
		// 못박아 둔다. 「매 틱 다시 고른다」쪽은 표식이 뛰어다니고 자막이 매 틱 떠서 훨씬 나쁘다.
		// 값이 잘못 적힌 카드는 심심해질 뿐이어야지 못 읽을 화면을 만들면 안 된다.
		for (int broken : new int[] {0, -1, -240}) {
			RandomSource random = RandomSource.create(11L);
			TrialDragonFocus.Selection held = null;
			for (long now = GRANTED; now < GRANTED + 10_000L; now += 137L) {
				long cycle = TrialRisks.strikeIndex(now, GRANTED, broken);
				assertEquals(0L, cycle, "주기가 0 이하면 번호가 늘 0 이다");
				TrialDragonFocus.Selection next = TrialDragonFocus.select(held, cycle, FOUR,
						TrialCatalog.Risk.Focus.RANDOM, null, random);
				if (held != null) {
					assertSame(held, next, "간격이 " + broken + " 인데 표적이 바뀌었다");
				}
				held = next;
			}
		}
	}

	@Test
	void 오래_돌리면_모두_한_번씩은_물린다() {
		RandomSource random = RandomSource.create(99L);
		TrialDragonFocus.Selection held = null;
		Set<UUID> seen = new HashSet<>();
		for (long cycle = 0L; cycle < 200L; cycle++) {
			held = TrialDragonFocus.select(held, cycle, FOUR, TrialCatalog.Risk.Focus.RANDOM,
					null, random);
			seen.add(held.target());
		}
		assertEquals(4, seen.size(), "한 사람이 영영 물리면 그 사람만 게임을 하게 된다");
	}

	// ------------------------------------------------------------------ 크리스탈을 깬 사람

	@Test
	void 깬_사람이_있으면_그_사람이_표적이다() {
		RandomSource random = RandomSource.create(3L);
		for (long cycle = 0L; cycle < 50L; cycle++) {
			TrialDragonFocus.Selection held = TrialDragonFocus.select(null, cycle, FOUR,
					TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, C, random);
			assertEquals(C, held.target(), "「크리스탈을 깬 사람에게 집중된다」가 카드에 적힌 말이다");
		}
	}

	@Test
	void 깬_사람이_없으면_무작위로_물러난다() {
		// 아무도 안 노리면 카드가 죽는다 — 룰렛이 이 카드를 뽑은 판이 시련 없는 판이 된다.
		RandomSource random = RandomSource.create(21L);
		Set<UUID> seen = new HashSet<>();
		for (long cycle = 0L; cycle < 200L; cycle++) {
			TrialDragonFocus.Selection held = TrialDragonFocus.select(null, cycle, FOUR,
					TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, null, random);
			assertTrue(FOUR.contains(held.target()), "없는 사람을 골랐다");
			seen.add(held.target());
		}
		assertEquals(4, seen.size(), "물러난 뒤에도 무작위여야 한다. 한 사람에 고정되면 그 사람만 게임을 한다");
	}

	@Test
	void 깬_사람이_접속해_있지_않으면_무작위로_물러난다() {
		RandomSource random = RandomSource.create(22L);
		Set<UUID> seen = new HashSet<>();
		for (long cycle = 0L; cycle < 200L; cycle++) {
			TrialDragonFocus.Selection held = TrialDragonFocus.select(null, cycle, FOUR,
					TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, GONE, random);
			assertNotEquals(GONE, held.target(), "나간 사람을 노리면 아무도 안 노리는 것과 같다");
			seen.add(held.target());
		}
		assertEquals(4, seen.size());
	}

	@Test
	void 들고_있던_표적이_나가면_주기_중간이라도_다시_고른다() {
		RandomSource random = RandomSource.create(33L);
		TrialDragonFocus.Selection held = new TrialDragonFocus.Selection(5L, GONE);
		TrialDragonFocus.Selection next = TrialDragonFocus.select(held, 5L, FOUR,
				TrialCatalog.Risk.Focus.RANDOM, null, random);
		assertNotNull(next);
		assertTrue(FOUR.contains(next.target()),
				"명단에 없는 사람을 계속 노리면 그 주기 내내 아무 일도 일어나지 않는다");
		assertEquals(5L, next.cycle(), "주기 번호까지 넘기면 다음 재지정이 한 박자 밀린다");
	}

	// ------------------------------------------------------------------ 인원

	@Test
	void 인원이_한_명이면_언제나_그_사람이다() {
		RandomSource random = RandomSource.create(44L);
		List<UUID> alone = List.of(A);
		for (long cycle = 0L; cycle < 50L; cycle++) {
			assertEquals(A, TrialDragonFocus.select(null, cycle, alone,
					TrialCatalog.Risk.Focus.RANDOM, null, random).target());
			assertEquals(A, TrialDragonFocus.select(null, cycle, alone,
					TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, GONE, random).target(),
					"혼자인데 물러날 곳이 없다고 아무도 안 노리면 안 된다");
		}
	}

	@Test
	void 아무도_없으면_표적도_없다() {
		RandomSource random = RandomSource.create(55L);
		assertNull(TrialDragonFocus.select(null, 0L, List.of(), TrialCatalog.Risk.Focus.RANDOM,
				null, random), "팀원이 전부 접속을 끊었거나 전부 죽어 있을 수 있다");
		assertNull(TrialDragonFocus.select(null, 0L, null, TrialCatalog.Risk.Focus.RANDOM,
				null, random));
	}

	@Test
	void 고르는_법이_없으면_무작위다() {
		// 저장 파일이나 앞으로의 JSON 에서 빈 값이 올 수 있다. 그때 터지는 대신 무작위로 돈다.
		RandomSource random = RandomSource.create(66L);
		assertTrue(FOUR.contains(TrialDragonFocus.choose(FOUR, null, C, random)));
	}

	// ------------------------------------------------------------------ 카드에 적힌 값

	@Test
	void 표적_카드는_크리스탈을_깬_사람을_노린다() {
		TrialCatalog.Risk.DragonFocus focus = onlyFocus("sharedfate:dragon_mark");
		assertEquals(TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, focus.focus(),
				"카드 설명이 「크리스탈을 깬 사람에게 집중됩니다」다. 값과 글이 어긋나면 설명이 거짓말이 된다");
		assertEquals(RETARGET, focus.retargetTicks(), "시험이 보는 값과 카드 값이 어긋났다");
	}

	@Test
	void 재지정_간격은_자막을_읽을_수_있을_만큼_길다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				if (!(risk instanceof TrialCatalog.Risk.DragonFocus focus)) {
					continue;
				}
				assertTrue(focus.retargetTicks() >= 20,
						trial.name() + " — 1초에 한 번 넘게 표적이 바뀌면 누가 물렸는지 읽을 수 없다");
			}
		}
	}

	// ------------------------------------------------------------------ 우리가 정말 개입하는가

	@Test
	void 드래곤을_사람에게_밀어넣는_호출이_실제로_있다() throws IOException {
		// 이번 결함이 정확히 「아무것도 안 한다」였다. 옛 구현은 setPhase 를 한 번도 부르지 않고
		// 드래곤이 스스로 사람을 노리기만 기다렸는데, 바닐라가 그 페이즈에 들어가도 고르는 사람이
		// 우리와 같아 카드가 바닐라와 구별되지 않았다. 다시 「기다리기만 하는」 구현으로 돌아가면
		// 여기서 걸린다.
		String bytes = classBytes();
		assertTrue(bytes.contains("setPhase"), "페이즈를 한 번도 바꾸지 않는다 — 옛 결함으로 되돌아갔다");
		assertTrue(bytes.contains(
						"(Lnet/minecraft/world/entity/boss/enderdragon/phases/EnderDragonPhase;)V"),
				"setPhase 를 이름만 적고 실제로 부르지는 않는다");
		assertTrue(bytes.contains("CHARGING_PLAYER"),
				"드래곤이 사람에게 날아오는 페이즈는 이것 하나뿐이다");
	}

	@Test
	void 바닐라가_스스로_빠져나올_수_있는_페이즈만_쓴다() {
		// 착지·앉기·이륙을 우리가 세우면 되돌릴 사람이 필요해진다. 연출 도중 드래곤이 죽거나
		// 서버가 내려가면 우리 틱이 끊기고, 그때 드래곤이 그 페이즈에 갇히면 전투가 영영 끝나지
		// 않는다. 이름을 아예 쓰지 않으면 밀어넣을 수도 없다.
		String bytes = classBytes();
		for (String stuck : new String[] {"LANDING_APPROACH", "LANDING", "SITTING_FLAMING",
				"SITTING_SCANNING", "SITTING_ATTACKING", "TAKEOFF", "HOVERING", "DYING"}) {
			assertFalse(bytes.contains(stuck),
					stuck + " 을 이름으로 부르고 있다 — 바닐라 흐름을 가로채거나 갇힐 자리를 만들었다");
		}
	}

	@Test
	void 오고_있는_선은_긴_거리로_나간다() {
		// 드래곤은 y 133 까지 올라가고 아레나를 가로지르면 150 블록이 넘는다. 짧은 형태로
		// 되돌리면 선의 출발점 — 곧 「어디서 오는가」 — 가 통째로 사라지고, 그러면 자막만 뜨고
		// 아무것도 안 보이던 그 화면으로 정확히 되돌아간다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 32 블록 밖에 있는 드래곤 쪽 선이 안 보인다");
	}

	// ------------------------------------------------------------------ 개입 주기

	@Test
	void 돌진_간격은_재지정_간격에서_뽑는다() {
		assertEquals(RETARGET * TrialDragonFocus.CHARGE_CYCLES,
				TrialDragonFocus.chargeInterval(RETARGET));
		assertEquals(0, TrialDragonFocus.chargeInterval(RETARGET) % RETARGET,
				"두 박자가 어긋나면 표적이 바뀐 사람과 드래곤이 날아가는 사람이 따로 논다");
	}

	@Test
	void 돌진_사이에는_바닐라를_내버려_둔다() {
		int interval = TrialDragonFocus.chargeInterval(RETARGET);
		long charged = GRANTED + 40L;
		for (long now = charged; now < charged + interval; now++) {
			assertFalse(TrialDragonFocus.chargeDue(now, charged, interval),
					"간격 안인데 또 민다 — 드래곤이 원을 돌지 못해 착지 주사위를 굴릴 기회가 없어진다: "
							+ (now - charged));
		}
		assertTrue(TrialDragonFocus.chargeDue(charged + interval, charged, interval),
				"간격이 지났는데 오지 않으면 카드가 다시 아무 일도 안 하는 것이 된다");
	}

	@Test
	void 돌진이_바닐라_비행_시간을_다_먹지_않는다() {
		// 돌진 한 번은 비행 2~3초에 DragonChargePlayerPhase 가 정해 둔 회복 10틱이라 넉넉히
		// 잡아 80틱이다. 간격이 그 몇 배는 되어야 나머지 시간이 바닐라 몫으로 남는다.
		int dive = 80;
		assertTrue(TrialDragonFocus.chargeInterval(RETARGET) >= dive * 3,
				"드래곤이 사람만 쫓는다 — 크리스탈을 먹으러 가지도 내려앉지도 못한다. 실제 값: "
						+ TrialDragonFocus.chargeInterval(RETARGET));
	}

	@Test
	void 재지정_간격이_잘못_적혀도_바닥_아래로는_안_내려간다() {
		// 값이 잘못 적힌 카드는 심심해질 뿐이어야지 대응 불가를 만들면 안 된다.
		for (int broken : new int[] {0, -1, -240, 1, 20, 66}) {
			assertTrue(TrialDragonFocus.chargeInterval(broken) >= TrialDragonFocus.CHARGE_MIN_TICKS,
					"간격 " + broken + " 인 카드가 드래곤을 쉬지 않고 파고들게 만든다");
		}
	}

	@Test
	void 아주_큰_간격이_음수로_넘어가지_않는다() {
		assertTrue(TrialDragonFocus.chargeInterval(Integer.MAX_VALUE) > 0,
				"곱하다 넘치면 바닥값 검사를 그대로 통과해 매 틱 돌진이 된다");
	}

	@Test
	void 카드를_받으면_첫_돌진을_기다리지_않는다() {
		// 자막이 뜨고 한참 아무 일도 없는 것이 이번에 고친 그 결함이다.
		assertTrue(TrialDragonFocus.chargeDue(GRANTED, null, 300));
	}

	@Test
	void 월드_시간이_되감겨도_돌진이_멈추지_않는다() {
		// 판을 다시 시작했는데 상태가 덜 지워졌을 수 있다. 미래 시각이 남아 있으면 「간격이
		// 지났는가」가 영영 거짓이 되어 카드가 조용히 죽는다.
		assertTrue(TrialDragonFocus.chargeDue(100L, 99_999L, 300));
	}

	// ------------------------------------------------------------------ 돌진이 성립하는 거리

	@Test
	void 너무_가깝거나_멀면_돌진시키지_않는다() {
		// 두 끝값은 DragonChargePlayerPhase.doServerTick 이 「도착했다」로 보는 경계에서 왔다.
		// 거리² 100 미만(=10블록)과 22500 초과(=150블록)에서 회복을 세기 시작한다.
		assertTrue(TrialDragonFocus.CHARGE_MIN_DISTANCE > 10.0,
				"10 블록 안에서 밀면 10틱 뒤 그냥 되돌아간다 — 또 아무 일도 없는 카드가 된다");
		assertTrue(TrialDragonFocus.CHARGE_MAX_DISTANCE < 150.0,
				"150 블록 밖으로 겨누면 돌진이 시작조차 하지 않는다");
		assertFalse(TrialDragonFocus.chargeable(TrialDragonFocus.CHARGE_MIN_DISTANCE - 0.01));
		assertTrue(TrialDragonFocus.chargeable(TrialDragonFocus.CHARGE_MIN_DISTANCE));
		assertTrue(TrialDragonFocus.chargeable(TrialDragonFocus.CHARGE_MAX_DISTANCE));
		assertFalse(TrialDragonFocus.chargeable(TrialDragonFocus.CHARGE_MAX_DISTANCE + 0.01));
	}

	@Test
	void 아레나_안의_평범한_거리는_전부_돌진_거리다() {
		// 엔드 섬 반경이 40 이고 드래곤은 y 133 까지 오른다. 사람이 중앙에 있고 드래곤이
		// 궤도 꼭대기에 있는 흔한 그림이 빠지면 카드가 대부분의 시간 동안 놀게 된다.
		for (double distance : new double[] {15.0, 30.0, 60.0, 92.0, 120.0}) {
			assertTrue(TrialDragonFocus.chargeable(distance), "거리 " + distance);
		}
	}

	// ------------------------------------------------------------------ 오고 있다는 선

	@Test
	void 선이_점선으로_읽히지_않는다() {
		for (double length = 0.1; length <= TrialDragonFocus.BEAM_KEPT_LENGTH; length += 0.1) {
			assertTrue(TrialDragonFocus.beamGap(length) <= TrialDragonFocus.BEAM_MAX_GAP + 1.0E-9,
					"길이 " + length + " 에서 점이 " + TrialDragonFocus.beamGap(length)
							+ " 블록씩 벌어진다 — 선이 아니라 점선이다");
		}
	}

	@Test
	void 선이_길어져도_점_수가_무한히_늘지_않는다() {
		assertEquals(0, TrialDragonFocus.beamPoints(0.0));
		assertEquals(0, TrialDragonFocus.beamPoints(-5.0));
		assertTrue(TrialDragonFocus.beamPoints(20.0) > 0);
		assertEquals(TrialDragonFocus.beamPoints(400.0), TrialDragonFocus.beamPoints(200.0),
				"상한에 닿은 뒤로는 더 늘지 않는다");
	}

	@Test
	void 드래곤_꼭대기에서_바닥까지도_선으로_읽힌다() {
		// 26.3 의 드래곤은 y 133 까지 오르고 궤도가 섬 밖으로 나간다. 반대편 끝에 선 사람까지가
		// 가로 140·세로 70 이면 156 이다.
		double length = Math.sqrt(140.0 * 140.0 + 70.0 * 70.0);
		assertTrue(length > 32.0,
				"이 거리가 32 를 안 넘으면 이 카드에 사거리 문제가 없다는 뜻이다. 실제 값: " + length);
		assertTrue(length <= TrialDragonFocus.BEAM_KEPT_LENGTH,
				"약속한 길이 밖이면 간격을 보장하지 못한다. 실제 값: " + length);
		assertTrue(TrialDragonFocus.beamGap(length) <= TrialDragonFocus.BEAM_MAX_GAP + 1.0E-9);
	}

	// ------------------------------------------------------------------ 26.3 의 실제 이름

	@Test
	void 사람을_노리는_페이즈_API_가_26_3_에_실제로_있다() throws Exception {
		// 이 모드는 refmap 없이 이름으로 바닐라를 부른다. 판이 올라 이름이 바뀌면 빌드가 깨지지만,
		// 여기서 먼저 깨지면 어느 이름이 사라졌는지가 바로 보인다.
		Class<?> phase = load("net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase");
		for (String name : new String[] {"HOLDING_PATTERN", "STRAFE_PLAYER", "CHARGING_PLAYER"}) {
			assertNotNull(phase.getDeclaredField(name), name);
		}

		Class<?> manager = load(
				"net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager");
		assertNotNull(manager.getDeclaredMethod("setPhase", phase));
		assertNotNull(manager.getDeclaredMethod("getPhase", phase));

		// 대상을 심는 값이 두 페이즈가 서로 다르다. 이것이 뒤집히면 돌진이 유도탄이 된다.
		assertNotNull(load("net.minecraft.world.entity.boss.enderdragon.phases"
						+ ".DragonStrafePlayerPhase")
				.getDeclaredMethod("setTarget", load("net.minecraft.world.entity.LivingEntity")));
		assertNotNull(load("net.minecraft.world.entity.boss.enderdragon.phases"
						+ ".DragonChargePlayerPhase")
				.getDeclaredMethod("setTarget", load("net.minecraft.world.phys.Vec3")));

		// 선의 출발점은 드래곤 머리다. 몸통 좌표로 그으면 입이 아닌 배에서 선이 나온다.
		assertNotNull(load("net.minecraft.world.entity.boss.enderdragon.EnderDragon")
				.getDeclaredField("head"));
	}

	// ------------------------------------------------------------------ 거들기

	/** 초기화를 일으키지 않고 클래스만 집어 온다. 월드 없이 도는 시험이라 정적 초기화를 피한다. */
	private static Class<?> load(String name) throws ClassNotFoundException {
		return Class.forName(name, false, TrialDragonFocusTest.class.getClassLoader());
	}

	/** 컴파일된 우리 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String classBytes() {
		try (InputStream in = TrialDragonFocus.class
				.getResourceAsStream("/com/sharedfate/sync/TrialDragonFocus.class")) {
			if (in == null) {
				return fail("TrialDragonFocus 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}

	private static void assertNotSameSelection(TrialDragonFocus.Selection before,
			TrialDragonFocus.Selection next) {
		if (before == next) {
			fail("주기가 넘어갔는데 그대로다 — 한 사람이 영영 물린다");
		}
	}

	private static TrialCatalog.Risk.DragonFocus onlyFocus(String id) {
		TrialCatalog.Trial trial = TrialCatalog.byId(id);
		assertNotNull(trial, id + " 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.DragonFocus focus) {
				return focus;
			}
		}
		throw new AssertionError(id + " 카드에 DragonFocus 가 없다");
	}
}
