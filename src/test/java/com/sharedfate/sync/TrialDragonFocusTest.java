package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 「표적」 카드에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>구체를 실제로 날리는 것은 {@code EnderDragon} 이 있어야 해서 여기서 볼 수 없다. 그래도 이
 * 카드가 망가지는 길은 거의 전부 여기서 잡힌다 — <b>한 사람이 영영 물린다</b>, <b>깬 사람이
 * 없어서 아무도 안 물린다</b>, <b>쉬는 시간에도 쏜다</b>, <b>카드에 적힌 것보다 많이 쏜다</b>.
 * 전멸하면 월드가 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 *
 * <p>그리고 하나 더 — <b>드래곤의 페이즈를 건드리지 않는다</b>를 여기서 못박는다. 그것이 실제로
 * 플레이하다 터진 사고였고, 컴파일도 로그도 조용한 종류다.
 */
class TrialDragonFocusTest {
	/** 「표적」 카드의 값. 실제 카드·문서와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int MARK = 200;
	private static final int REST = 400;
	private static final int SHOTS = 5;
	private static final float DAMAGE = 6.0F;
	/** 한 주기. 표적 10초 + 쉼 20초. */
	private static final int PERIOD = MARK + REST;

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
	private static final List<UUID> FOUR = List.of(A, B, C, D);
	/** 명단에 없는 사람. 접속을 끊었거나 관전으로 넘어갔다. */
	private static final UUID GONE = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

	// ------------------------------------------------------------------ 페이즈를 건드리지 않는다

	/**
	 * <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>옛 구현은 주기마다 드래곤을 돌진 페이즈로 밀어넣었고, 그러자 <b>드래곤이 착지를 아예 하지
	 * 않았다.</b> 바닐라는 원을 한 바퀴 다 돌아 경로가 끝난 틱에만 「착지할까」를 굴리는데, 우리가
	 * 끼어들면 돌아올 때마다 경로가 처음부터 다시 깔려 그 틱이 영영 오지 않는다. 크리스탈을 먹으러
	 * 가는 것도 내려앉아 맞아 주는 것도 사라져 <b>전투가 끝나지 않는 판</b>이 됐다.
	 *
	 * <p>고친 방법은 값을 조절한 것이 아니라 <b>개입을 통째로 걷어낸 것</b>이다. 그래서 「조금만
	 * 밀어 보자」로 되돌아오는 길을 여기서 막는다. 이름 하나라도 상수 풀에 들어오면 걸린다.
	 */
	@Test
	void 드래곤의_페이즈를_한_번도_부르지_않는다() {
		String bytes = classBytes();
		for (String banned : new String[] {"setPhase", "getPhaseManager", "getCurrentPhase",
				"EnderDragonPhase", "setTarget", "HOLDING_PATTERN", "CHARGING_PLAYER",
				"STRAFE_PLAYER", "LANDING_APPROACH", "LANDING", "SITTING_SCANNING", "TAKEOFF"}) {
			assertFalse(bytes.contains(banned),
					banned + " 이 상수 풀에 있다 — 드래곤의 행동에 다시 끼어들었다."
							+ " 그것이 착지를 멈춘 사고이고, 이 카드는 구체를 직접 날리는 쪽으로 고쳤다");
		}
	}

	@Test
	void 드래곤에게서는_머리_좌표만_읽는다() {
		// 우리가 드래곤에게 요구하는 것은 「지금 어디 있는가」 하나다. 그 밖의 것을 읽기 시작하면
		// 곧 그 밖의 것을 쓰게 되고, 그때 다시 바닐라 흐름을 가로채게 된다.
		String bytes = classBytes();
		assertTrue(bytes.contains("head"), "구체의 출발점이 드래곤 머리가 아니면 입이 아닌 데서 나온다");
		assertTrue(bytes.contains("Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;"),
				"머리는 EnderDragonPart 다. 몸통 좌표로 쏘면 배에서 구체가 나온다");
	}

	@Test
	void 착탄은_표적_한_사람에게만_묻는다() {
		// 체력이 팀 공유이고 StatMirror.fold 가 팀원별 피해를 그대로 합산한다. 착탄 자리에 있는
		// 아무나 때리면 넷이 모인 자리에 한 발이 떨어질 때 6 × 4 = 24 가 한 틱에 들어가 팀 체력
		// 20 을 넘는다 — 한 발짜리 즉사 카드다.
		assertFalse(classBytes().contains("getEntitiesOfClass"),
				"주변 사람을 긁어모으고 있다 — 모여 있는 팀이 한 발에 죽는다");
	}

	@Test
	void 넉백을_주는_피해원을_쓰지_않는다() {
		// 엔드 중앙 섬은 사방이 허공이고 공유 체력이라 한 사람의 낙사가 팀 전체를 끝낸다.
		// LivingEntity 는 피해원에 실체가 붙어 있을 때만 밀어내므로 엔티티 없는 것을 쓴다.
		String bytes = classBytes();
		assertTrue(bytes.contains("magic"), "피해를 넣는 자리가 사라졌다");
		for (String pushes : new String[] {"mobAttack", "playerAttack", "mobProjectile",
				"indirectMagic", "knockback"}) {
			assertFalse(bytes.contains(pushes), pushes + " 은 사람을 밀어낸다 — 허공 낙사는 즉사다");
		}
	}

	@Test
	void 구체와_선은_긴_거리로_나간다() {
		// 드래곤은 y 133 까지 올라가고 아레나를 가로지르면 150 블록이 넘는다. 짧은 형태는 32
		// 블록에서 잘리므로, 되돌리면 구체가 코앞에서 갑자기 나타난다 — 그러면 예고가 아니다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 32 블록 밖에 있는 드래곤 쪽이 안 보인다");
	}

	@Test
	void 장판을_남기지_않는다() {
		// 바닐라 DragonFireball 은 착탄 자리에 브레스 장판을 남긴다. 우리 카드 어디에도 장판은
		// 적혀 있지 않고, 장판을 쓰는 다른 카드가 따로 있어 섞이면 구별할 수 없다.
		String bytes = classBytes();
		assertFalse(bytes.contains("AreaEffectCloud"), "장판을 남기면 다른 카드와 구별되지 않는다");
		assertFalse(bytes.contains("DragonFireball"), "바닐라 화염구는 장판과 블록 파괴를 함께 달고 온다");
		// 소리와 사망 메시지도 갈라 둔다. 브레스 장판 쪽이 이 셋을 쓰고 있어서, 같은 것을 쓰면
		// 화면을 안 보고 있을 때 — 또는 채팅 한 줄로 — 둘을 구별할 수 없다.
		for (String taken : new String[] {"ENDER_DRAGON_SHOOT", "DRAGON_FIREBALL_EXPLODE",
				"dragonBreath"}) {
			assertFalse(bytes.contains(taken),
					taken + " 은 브레스 장판 카드가 쓰고 있다 — 둘이 섞이면 무엇이 무엇인지 알 수 없다");
		}
	}

	// ------------------------------------------------------------------ 표적과 쉼

	@Test
	void 표적이_markTicks_동안_유지되고_restTicks_동안_쉰다() {
		for (int phase = 0; phase < PERIOD; phase++) {
			assertEquals(phase < MARK, TrialDragonFocus.marked(phase, MARK),
					"위상 " + phase + " 의 판단이 틀렸다 — 표적 " + MARK + "틱, 쉼 " + REST + "틱이다");
		}
		assertEquals(PERIOD, TrialDragonFocus.period(MARK, REST));
		// 주기가 넘어가면 위상도 처음으로 돌아온다.
		assertEquals(0, TrialDragonFocus.phaseOf(PERIOD, MARK, REST));
		assertEquals(1L, TrialDragonFocus.cycleOf(PERIOD, MARK, REST));
		assertEquals(0L, TrialDragonFocus.cycleOf(PERIOD - 1, MARK, REST));
	}

	@Test
	void 쉬는_동안에는_한_발도_안_나간다() {
		for (int phase = MARK; phase < PERIOD; phase++) {
			assertEquals(-1, TrialDragonFocus.shotIndexAt(phase, MARK, SHOTS),
					"쉬는 시간인 위상 " + phase + " 에 쏜다 — 20초 쉼이 카드에 적힌 약속이다");
		}
	}

	@Test
	void 발이_markTicks_안에_shots_발_고르게_나간다() {
		List<Integer> fired = firedPhases(MARK, REST, SHOTS);
		assertEquals(SHOTS, fired.size(), "카드에 적힌 수와 실제로 나가는 수가 다르다: " + fired);
		assertEquals(0, fired.get(0), "표적을 잡는 틱에 첫 발이 나간다");
		assertTrue(fired.get(fired.size() - 1) < MARK,
				"마지막 발이 표적 해제 뒤에 날아간다: " + fired);

		int interval = TrialDragonFocus.shotInterval(MARK, SHOTS);
		for (int index = 1; index < fired.size(); index++) {
			assertEquals(interval, fired.get(index) - fired.get(index - 1),
					"발 사이가 고르지 않다 — 한 번에 몰리면 피할 수 없다: " + fired);
		}
		// 번호도 0 부터 차례대로 붙어야 한다. 번호가 어긋나면 같은 발을 두 번 쏘거나 건너뛴다.
		for (int index = 0; index < fired.size(); index++) {
			assertEquals(index, TrialDragonFocus.shotIndexAt(fired.get(index), MARK, SHOTS));
		}
	}

	@Test
	void 발사_간격은_markTicks_와_shots_에서_나온다() {
		assertEquals(40, TrialDragonFocus.shotInterval(MARK, SHOTS), "10초에 5발이면 2초에 한 발이다");
		// 숫자를 박아 두지 않았다는 증거 — 카드 값을 바꾸면 간격이 따라 움직인다.
		assertEquals(20, TrialDragonFocus.shotInterval(MARK / 2, SHOTS));
		assertEquals(80, TrialDragonFocus.shotInterval(MARK * 2, SHOTS));
		assertEquals(20, TrialDragonFocus.shotInterval(MARK, SHOTS * 2));
	}

	@Test
	void 나누어떨어지지_않아도_적힌_수보다_많이_쏘지_않는다() {
		// 10 / 3 = 3 이라 간격만 보면 위상 0·3·6·9 에 네 발이 나간다. 카드를 적은 사람이
		// 예상할 수 없는 피해다.
		assertEquals(3, firedPhases(10, 20, 3).size(), "적힌 것보다 많이 쏜다");
		assertEquals(List.of(0, 3, 6), firedPhases(10, 20, 3));
	}

	@Test
	void 값이_잘못_적힌_카드는_조용해질_뿐이다() {
		// 값이 잘못 적힌 카드는 심심해질 뿐이어야지 서버를 멈추거나 대응 불가를 만들면 안 된다.
		for (int broken : new int[] {0, -1, -240}) {
			assertTrue(TrialDragonFocus.period(broken, broken) >= 1,
					"주기가 0 이면 나머지 연산이 그 자리에서 터진다");
			assertFalse(TrialDragonFocus.marked(0, broken), "잡는 시간이 없으면 표적도 없다");
			assertEquals(0, TrialDragonFocus.shotInterval(broken, SHOTS));
			assertEquals(-1, TrialDragonFocus.shotIndexAt(0, broken, SHOTS));
			assertEquals(0, TrialDragonFocus.flightTicks(broken, SHOTS));
			assertEquals(-1, TrialDragonFocus.shotIndexAt(0, MARK, broken), "발 수가 " + broken);
		}
		assertTrue(TrialDragonFocus.period(Integer.MAX_VALUE, Integer.MAX_VALUE) > 0,
				"더하다 넘치면 음수 주기가 되어 나머지 연산이 음수를 돌려준다");
	}

	@Test
	void 월드_시간이_되감겨도_한_발만_나간다() {
		// TrialRisks.elapsedSinceGrant 가 음수를 0 으로 깎으므로, 판을 다시 시작했는데 상태가
		// 남았으면 위상 0 이 여러 틱 이어진다. 위상만 보고 쏘면 그동안 매 틱 한 발씩 나간다.
		TrialDragonFocus.Fired first = new TrialDragonFocus.Fired(0L, 0);
		assertTrue(TrialDragonFocus.freshShot(null, first), "첫 발은 기다리지 않는다");
		assertFalse(TrialDragonFocus.freshShot(first, new TrialDragonFocus.Fired(0L, 0)),
				"같은 주기의 같은 번호를 두 번 쏜다");
		assertTrue(TrialDragonFocus.freshShot(first, new TrialDragonFocus.Fired(0L, 1)));
		assertTrue(TrialDragonFocus.freshShot(first, new TrialDragonFocus.Fired(1L, 0)));
	}

	// ------------------------------------------------------------------ 구체 한 발

	@Test
	void 두_발이_같은_틱에_닿지_않는다() {
		// 닿는 틱이 겹치면 카드 값 하나가 두 배로 들어간다. 팀 체력이 20 이라 6 이 12 가 되는
		// 것으로 끝나지 않고, 다른 카드와 겹치면 그대로 전멸이다.
		for (int shots = 1; shots <= 20; shots++) {
			for (int markTicks : new int[] {20, 60, MARK, 600}) {
				assertTrue(TrialDragonFocus.flightTicks(markTicks, shots)
								<= TrialDragonFocus.shotInterval(markTicks, shots),
						"표적 " + markTicks + "틱 · " + shots + "발에서 앞 발이 아직 날고 있다");
			}
		}
	}

	@Test
	void 비행_시간이_옆걸음_예고보다_짧지_않다() {
		// 이 카드가 요구하는 행동은 「제자리에서 옆으로 비키기」 하나다 — 조준점이 발사 순간에
		// 얼어붙고 노려지는 사람도 하나라 흩어질 필요가 없다. 그 최소 예고가 30틱이다.
		assertEquals(TrialWarning.TICKS_SIDESTEP,
				TrialDragonFocus.flightTicks(MARK, SHOTS),
				"실제 카드에서 구체가 날아오는 시간이 옆걸음 예고와 달라졌다");
		assertTrue(TrialDragonFocus.flightTicks(MARK, SHOTS) >= TrialWarning.TICKS_SIDESTEP,
				"예고가 아니라 사후 통보가 된다");
	}

	@Test
	void 착탄_반경은_비켜서_벗어날_수_있다() {
		// 걷는 속도가 초당 4.3 블록이라 30틱(1.5초)이면 6 블록 넘게 움직인다. 반경 2.5 는
		// 한 걸음 반이다. 여기를 키우면 다섯 발을 전부 맞는 카드가 되고 합계 30 은 팀 체력을
		// 넘긴다 — 「즉사 메커닉 0개」가 깨진다.
		double seconds = TrialDragonFocus.flightTicks(MARK, SHOTS) / 20.0;
		double walked = 4.3 * seconds;
		assertTrue(walked > TrialDragonFocus.IMPACT_RADIUS * 2.0,
				"비행 " + seconds + "초 동안 걸어서 " + walked + " 블록인데 반경이 "
						+ TrialDragonFocus.IMPACT_RADIUS + " 다 — 비켜도 맞는다");
	}

	@Test
	void 구체는_조준점을_지나쳐_날아가지_않는다() {
		assertEquals(0.0, TrialDragonFocus.flightProgress(100L, 100L, 130L));
		assertEquals(0.5, TrialDragonFocus.flightProgress(115L, 100L, 130L), 1.0E-9);
		assertEquals(1.0, TrialDragonFocus.flightProgress(130L, 100L, 130L));
		assertEquals(1.0, TrialDragonFocus.flightProgress(200L, 100L, 130L),
				"지나친 자리에 구체를 그리면 사람들이 착탄을 엉뚱한 곳에서 본다");
		assertEquals(0.0, TrialDragonFocus.flightProgress(50L, 100L, 130L));
		assertEquals(1.0, TrialDragonFocus.flightProgress(100L, 100L, 100L),
				"비행 시간이 0 이면 이미 닿아 있다");
	}

	// ------------------------------------------------------------------ 누구를 노리는가

	@Test
	void 표적은_한_주기_동안_바뀌지_않는다() {
		RandomSource random = RandomSource.create(7L);
		TrialDragonFocus.Selection held = null;
		TrialDragonFocus.Selection first = null;
		for (long elapsed = 0L; elapsed < MARK; elapsed++) {
			long cycle = TrialDragonFocus.cycleOf(elapsed, MARK, REST);
			held = TrialDragonFocus.select(held, cycle, FOUR, TrialCatalog.Risk.Focus.RANDOM,
					null, random);
			if (first == null) {
				first = held;
				continue;
			}
			assertSame(first, held,
					"주기 안에서 다시 고르면 표식이 사람들 사이를 뛰어다녀 아무도 대응할 수 없다: " + elapsed);
		}
	}

	@Test
	void 주기가_넘어가면_다시_고른다() {
		RandomSource random = RandomSource.create(7L);
		long insideCycle = TrialDragonFocus.cycleOf(PERIOD - 1, MARK, REST);
		long nextCycle = TrialDragonFocus.cycleOf(PERIOD, MARK, REST);
		assertNotEquals(insideCycle, nextCycle, "주기가 넘어가는 자리를 잘못 잡았다");

		TrialDragonFocus.Selection held = TrialDragonFocus.select(null, insideCycle, FOUR,
				TrialCatalog.Risk.Focus.RANDOM, null, random);
		TrialDragonFocus.Selection next = TrialDragonFocus.select(held, nextCycle, FOUR,
				TrialCatalog.Risk.Focus.RANDOM, null, random);
		assertNotSameSelection(held, next);
		assertEquals(nextCycle, next.cycle());
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
			assertEquals(C, held.target(), "「크리스탈을 깬 사람이 표적이 된다」가 카드에 적힌 말이다");
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
	void 표적_카드에_적힌_값이_설계와_같다() {
		TrialCatalog.Risk.DragonFocus focus = onlyFocus("sharedfate:dragon_mark");
		assertEquals(TrialCatalog.Risk.Focus.CRYSTAL_BREAKER, focus.focus(),
				"카드 설명이 「크리스탈을 깬 사람」이다. 값과 글이 어긋나면 설명이 거짓말이 된다");
		assertEquals(MARK, focus.markTicks(), "표적 10초");
		assertEquals(REST, focus.restTicks(), "쉼 20초");
		assertEquals(SHOTS, focus.shots(), "10초에 5발");
		assertEquals(DAMAGE, focus.damage(), "발당 6");
		assertEquals(30, (focus.markTicks() + focus.restTicks()) / 20,
				"한 주기가 30초라고 문서에 적혀 있다");
	}

	@Test
	void 다_맞아도_한_틱에_죽지_않는다() {
		// 다섯 발을 전부 맞으면 30 이라 팀 체력 20 을 넘지만, 한 틱에 들어오는 것은 언제나 한
		// 발이다 — 구체가 날아가는 시간이 발사 간격을 넘지 않으므로 두 발이 같은 틱에 닿지 않는다.
		TrialCatalog.Risk.DragonFocus focus = onlyFocus("sharedfate:dragon_mark");
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		assertTrue(focus.damage() < teamHealth,
				"한 발로 팀이 죽는다. 팀 공유 체력은 " + teamHealth + " 다");
		assertTrue(TrialDragonFocus.flightTicks(focus.markTicks(), focus.shots())
						<= TrialDragonFocus.shotInterval(focus.markTicks(), focus.shots()),
				"두 발이 같은 틱에 닿으면 " + (focus.damage() * 2) + " 가 한 번에 들어간다");
	}

	/**
	 * 표식이 붙어 있는 시간이 사람이 반응할 시간보다 길다.
	 *
	 * <p>예전 이름은 「표적 시간은 <b>자막</b>을 읽을 수 있을 만큼 길다」였다. 액션바 글자를
	 * 걷어냈으므로 읽을 자막이 없다 — 대신 <b>발밑 보라 고리와 표적 자리의 소리</b>가 그 몫을
	 * 한다. 값을 재는 잣대({@link TrialWarning#TICKS_SIDESTEP}, 사람의 지각·판단·입력에 드는
	 * 최소 시간)는 그대로라 시험은 남기고 뜻만 고쳤다.
	 */
	@Test
	void 표적_시간은_반응할_수_있을_만큼_길다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				if (!(risk instanceof TrialCatalog.Risk.DragonFocus focus)) {
					continue;
				}
				assertTrue(focus.markTicks() >= TrialWarning.TICKS_SIDESTEP,
						trial.name() + " — 표적이 옆걸음 예고보다 짧게 붙으면 알아채기도 전에 풀린다");
				assertTrue(focus.restTicks() >= 0, trial.name() + " — 쉬는 시간이 음수면 뜻이 없다");
				assertTrue(focus.shots() >= 0, trial.name() + " — 발 수가 음수면 뜻이 없다");
			}
		}
	}

	/**
	 * 카드 값이 문서와 같다.
	 *
	 * <p>{@code docs/드래곤-시련-카드.md} 는 <b>값의 근거를 남기는 곳</b>이다. 코드만 고치고
	 * 문서를 두면 다음 사람이 「왜 이 값인가」를 물을 곳이 사라지고, 문서만 고치면 게임과 다른
	 * 설명이 남는다. 문서에 적어 둔 「값 —」 줄을 카드 값에서 그대로 만들어 찾는다.
	 */
	@Test
	void 표적_카드_값이_문서에도_같이_적혀_있다() {
		TrialCatalog.Risk.DragonFocus focus = onlyFocus("sharedfate:dragon_mark");
		String line = "값 — 표적 " + focus.markTicks() + "틱 · 쉼 " + focus.restTicks()
				+ "틱 · " + focus.shots() + "발 · 발당 피해 " + plain(focus.damage())
				+ " · 착탄 반경 " + plain(TrialDragonFocus.IMPACT_RADIUS);
		String doc = cardDoc();
		assertTrue(doc.contains(line),
				"「표적」의 값이 문서와 다르다. 문서에 이 줄이 있어야 한다: " + line);
	}

	@Test
	void 페이즈를_건드리지_않는다는_판단이_문서에_남아_있다() {
		// 근거가 없으면 다음 사람이 「조금만 밀어 보자」로 되돌린다. 그때 착지가 다시 멈춘다.
		String doc = cardDoc();
		assertTrue(doc.contains("착지"), "착지가 멈췄던 사고가 문서에 없다");
		assertTrue(doc.contains("setPhase"), "무엇을 부르지 않기로 했는지가 문서에 없다");
	}

	// ------------------------------------------------------------------ 「저기서 온다」는 선

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

	@Test
	void 선은_첫_구체가_닿기_전에_그려진다() {
		// 선이 주는 정보는 「어디서 날아오는가」다. 첫 구체가 이미 도착한 뒤에 그리면 늦는다.
		assertTrue(TrialDragonFocus.BEAM_TICKS <= TrialDragonFocus.flightTicks(MARK, SHOTS),
				"첫 구체가 닿은 뒤까지 선을 긋고 있다");
	}

	// ------------------------------------------------------------------ 26.3 의 실제 이름

	@Test
	void 우리가_쓰는_바닐라_이름이_26_3_에_실제로_있다() throws Exception {
		// 이 모드는 refmap 없이 이름으로 바닐라를 부른다. 판이 올라 이름이 바뀌면 빌드가 깨지지만,
		// 여기서 먼저 깨지면 어느 이름이 사라졌는지가 바로 보인다.
		assertNotNull(load("net.minecraft.world.entity.boss.enderdragon.EnderDragon")
				.getDeclaredField("head"), "구체의 출발점이다");
		assertNotNull(load("net.minecraft.world.entity.boss.enderdragon.EnderDragonPart")
				.getMethod("position"), "머리 좌표를 읽는 길");
		// 넉백 없는 피해원. 엔티티를 받는 것으로 바뀌면 사람이 밀려 허공으로 떨어진다.
		assertEquals(0, load("net.minecraft.world.damagesource.DamageSources")
				.getDeclaredMethod("magic").getParameterCount());
		assertNotNull(load("net.minecraft.sounds.SoundEvents").getDeclaredField("SHULKER_SHOOT"));
		assertNotNull(load("net.minecraft.sounds.SoundEvents")
				.getDeclaredField("SHULKER_BULLET_HIT"));
	}

	// ------------------------------------------------------------------ 거들기

	/** 한 주기를 다 돌려 실제로 발이 나가는 위상들. */
	private static List<Integer> firedPhases(int markTicks, int restTicks, int shots) {
		List<Integer> fired = new ArrayList<>();
		for (int phase = 0; phase < TrialDragonFocus.period(markTicks, restTicks); phase++) {
			if (TrialDragonFocus.shotIndexAt(phase, markTicks, shots) >= 0) {
				fired.add(phase);
			}
		}
		return fired;
	}

	/** 문서에 적는 모양 그대로. 정수로 떨어지는 값 뒤에 {@code .0} 을 붙이지 않는다. */
	private static String plain(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	/**
	 * 카드 문서를 읽는다.
	 *
	 * <p>작업 디렉터리에서 위로 올라가며 찾는다. Gradle 의 {@code test} 는 프로젝트 폴더에서
	 * 돌지만 IDE 는 모듈 폴더에서 돌 수 있어, 한 자리만 보면 환경에 따라 시험이 사라진다.
	 */
	private static String cardDoc() {
		Path here = Path.of("").toAbsolutePath();
		for (Path at = here; at != null; at = at.getParent()) {
			Path candidate = at.resolve("docs").resolve("드래곤-시련-카드.md");
			if (Files.isRegularFile(candidate)) {
				try {
					return Files.readString(candidate, StandardCharsets.UTF_8);
				} catch (IOException broken) {
					throw new AssertionError(candidate + " 를 읽지 못했다", broken);
				}
			}
		}
		throw new AssertionError("docs/드래곤-시련-카드.md 를 찾지 못했다. 작업 디렉터리: " + here);
	}

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
