package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

	// ------------------------------------------------------------------ 거들기

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
