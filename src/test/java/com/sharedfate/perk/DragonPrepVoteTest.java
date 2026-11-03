package com.sharedfate.perk;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤전 대비」 팀 투표 판정(2026-11-04).
 *
 * <p>사람 말 — 「1분간 고르게 하고 … 1분이 지나버리면 제일 많이 고른 증강이 하나 선택되는 거고, 모든
 * 플레이어가 선택이 끝났을 때 시간이 5초가 되어 카운트다운 시작되고 과반수로 가게. … 동점이 나올 경우
 * 룰렛이 돌아가고 랜덤으로 그 2개 중 하나 골라.」
 */
class DragonPrepVoteTest {
	private static final String A = "sharedfate:oath";
	private static final String B = "sharedfate:quiver";
	private static final UUID P1 = new UUID(0, 1);
	private static final UUID P2 = new UUID(0, 2);
	private static final UUID P3 = new UUID(0, 3);
	private static final UUID P4 = new UUID(0, 4);

	private static DragonPrepVote votingBoard() {
		DragonPrepVote board = new DragonPrepVote(List.of(A, B));
		RandomSource random = RandomSource.create(1L);
		for (int tick = 0; tick < DragonPrepVote.REVEAL_TICKS; tick++) {
			board.tick(random);
		}
		assertEquals(DragonPrepVote.Stage.VOTE, board.stage());
		return board;
	}

	/** 투표 단계를 끝까지 흘린다. 단계가 넘어간 틱의 사건을 돌려준다. */
	private static DragonPrepVote.Event runOut(DragonPrepVote board, RandomSource random) {
		DragonPrepVote.Event event = DragonPrepVote.Event.NONE;
		while (board.stage() == DragonPrepVote.Stage.VOTE) {
			event = board.tick(random);
		}
		return event;
	}

	// ------------------------------------------------------------------ 단계

	@Test
	void 등장_연출_동안은_표를_받지_않고_끝나면_60초_투표가_열린다() {
		DragonPrepVote board = new DragonPrepVote(List.of(A, B));
		RandomSource random = RandomSource.create(1L);
		assertEquals(DragonPrepVote.Stage.REVEAL, board.stage());
		assertFalse(board.vote(P1, A, Set.of(P1)), "카드가 뒤집히는 중에는 누를 것이 없다");
		DragonPrepVote.Event last = DragonPrepVote.Event.NONE;
		for (int tick = 0; tick < DragonPrepVote.REVEAL_TICKS; tick++) {
			last = board.tick(random);
		}
		assertEquals(DragonPrepVote.Event.VOTE_OPENED, last);
		assertEquals(DragonPrepVote.VOTE_TICKS, board.stageTicks());
		assertEquals(1200, DragonPrepVote.VOTE_TICKS, "사람 말 「1분간 고르게」");
		assertEquals(100, DragonPrepVote.ALL_VOTED_TICKS, "사람 말 「시간이 5초가 되어」");
		assertEquals(100, DragonPrepVote.RESULT_TICKS, "사람 말 「5초간 보여 주고」");
	}

	@Test
	void 결과_화면은_5초_뒤에_끝난다() {
		DragonPrepVote board = votingBoard();
		RandomSource random = RandomSource.create(1L);
		Set<UUID> present = Set.of(P1);
		board.vote(P1, A, present);
		assertEquals(DragonPrepVote.Event.DECIDED, runOut(board, random));
		assertEquals(DragonPrepVote.Stage.RESULT, board.stage());
		int ticks = 0;
		DragonPrepVote.Event event = DragonPrepVote.Event.NONE;
		while (event != DragonPrepVote.Event.FINISHED) {
			event = board.tick(random);
			ticks++;
		}
		assertEquals(DragonPrepVote.RESULT_TICKS, ticks);
	}

	// ------------------------------------------------------------------ 집계

	@Test
	void 표가_많은_카드가_뽑힌다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2, P3, P4);
		board.vote(P1, A, present);
		board.vote(P2, A, present);
		board.vote(P3, B, present);
		assertEquals(DragonPrepVote.Event.DECIDED, runOut(board, RandomSource.create(7L)),
				"2:1 은 룰렛 없이 바로 정해진다");
		assertEquals(A, board.winner());
		assertFalse(board.byRoulette());
	}

	@Test
	void 한_사람은_한_표이고_옮길_수_있다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2, P3);
		assertTrue(board.vote(P1, A, present));
		assertFalse(board.vote(P1, A, present), "같은 카드를 다시 눌러도 표가 꺼지지 않고 아무 일도 없다");
		assertEquals(1, board.count(A));
		assertTrue(board.vote(P1, B, present), "다른 카드를 누르면 옮겨 간다");
		assertEquals(0, board.count(A));
		assertEquals(1, board.count(B));
		assertEquals(1, board.votes().size());
	}

	@Test
	void 후보에_없는_카드와_표를_던질_수_없는_사람은_버린다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2);
		assertFalse(board.vote(P1, "sharedfate:nope", present));
		assertFalse(board.vote(P3, A, present), "관전자·남의 팀은 present 에 없다");
		assertFalse(board.vote(P1, null, present));
		assertTrue(board.votes().isEmpty());
	}

	// ------------------------------------------------------------------ 동점

	@Test
	void 동점이면_룰렛이_돌고_두_장_중_하나에서_멈춘다() {
		Set<String> seen = new HashSet<>();
		// 난수 하나를 판마다 이어 쓴다. 이웃한 씨앗(0, 1, 2 …)으로 새로 만들면 첫 nextInt(2) 가 거의 같은 값이다.
		RandomSource random = RandomSource.create(20261104L);
		for (int round = 0; round < 40; round++) {
			DragonPrepVote board = votingBoard();
			Set<UUID> present = Set.of(P1, P2);
			board.vote(P1, A, present);
			board.vote(P2, B, present);
			assertEquals(DragonPrepVote.Event.ROULETTE_STARTED, runOut(board, random));
			assertTrue(board.byRoulette());
			assertTrue(List.of(A, B).contains(board.winner()), "멈출 카드는 들어가는 순간 이미 정해져 있다");
			int ticks = 0;
			DragonPrepVote.Event event = DragonPrepVote.Event.NONE;
			while (event == DragonPrepVote.Event.NONE) {
				event = board.tick(random);
				ticks++;
			}
			assertEquals(DragonPrepVote.Event.DECIDED, event, "룰렛이 멈춘 뒤에 준다");
			assertEquals(DragonPrepVote.ROULETTE_TICKS, ticks);
			seen.add(board.winner());
		}
		assertEquals(Set.of(A, B), seen, "무작위라 두 장이 다 나와야 한다");
	}

	@Test
	void 아무도_안_던지면_0대0_동점이라_룰렛이다() {
		DragonPrepVote board = votingBoard();
		assertEquals(DragonPrepVote.Event.ROULETTE_STARTED, runOut(board, RandomSource.create(3L)));
		assertTrue(List.of(A, B).contains(board.winner()));
	}

	@Test
	void 후보가_한_장뿐이면_룰렛_없이_그것이다() {
		DragonPrepVote board = new DragonPrepVote(List.of(A));
		RandomSource random = RandomSource.create(1L);
		while (board.stage() == DragonPrepVote.Stage.REVEAL) {
			board.tick(random);
		}
		assertEquals(DragonPrepVote.Event.DECIDED, runOut(board, random));
		assertEquals(A, board.winner());
	}

	// ------------------------------------------------------------------ 5초 단축

	@Test
	void 전원이_던지면_남은_시간이_5초로_준다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2, P3);
		board.vote(P1, A, present);
		board.vote(P2, B, present);
		assertFalse(board.shortened());
		assertTrue(board.stageTicks() > DragonPrepVote.ALL_VOTED_TICKS);
		assertTrue(board.vote(P3, A, present));
		assertTrue(board.shortened());
		assertEquals(DragonPrepVote.ALL_VOTED_TICKS, board.stageTicks());
		assertEquals(DragonPrepVote.ALL_VOTED_TICKS, board.stageTotal(), "막대도 5초짜리로 다시 찬다");
	}

	@Test
	void 이미_5초_미만이면_그대로_둔다() {
		DragonPrepVote board = votingBoard();
		RandomSource random = RandomSource.create(1L);
		while (board.stageTicks() > 40) {
			board.tick(random);
		}
		board.vote(P1, A, Set.of(P1));
		assertTrue(board.shortened());
		assertEquals(40, board.stageTicks(), "줄이는 것이지 늘리는 것이 아니다");
	}

	@Test
	void 줄어든_뒤에도_표를_옮길_수_있고_시계는_다시_늘지_않는다() {
		DragonPrepVote board = votingBoard();
		RandomSource random = RandomSource.create(1L);
		Set<UUID> present = Set.of(P1, P2);
		board.vote(P1, A, present);
		board.vote(P2, A, present);
		board.tick(random);
		assertTrue(board.vote(P2, B, present), "카운트다운 사이에도 바꿀 수 있다");
		assertEquals(DragonPrepVote.ALL_VOTED_TICKS - 1, board.stageTicks());
		// 새로 들어온 사람이 아직 안 던졌어도 늘지 않는다.
		Set<UUID> joined = Set.of(P1, P2, P3);
		board.prune(joined);
		assertEquals(DragonPrepVote.ALL_VOTED_TICKS - 1, board.stageTicks());
		assertTrue(board.vote(P3, B, joined), "들어온 사람도 투표에 낀다");
		assertEquals(DragonPrepVote.Event.DECIDED, runOut(board, random));
		assertEquals(B, board.winner(), "옮긴 표까지 세어 2:1 이다");
	}

	// ------------------------------------------------------------------ 접속 변화

	@Test
	void 나간_사람의_표는_빠진다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2, P3, P4);
		board.vote(P1, A, present);
		board.vote(P2, A, present);
		board.vote(P3, B, present);
		assertTrue(board.prune(Set.of(P1, P3, P4)), "P2 가 나갔다");
		assertEquals(1, board.count(A));
		assertEquals(1, board.count(B));
		assertEquals(DragonPrepVote.Event.ROULETTE_STARTED, runOut(board, RandomSource.create(5L)),
				"2:1 이 1:1 이 되어 동점이다");
	}

	@Test
	void 안_던진_사람이_나가_남은_전원이_던진_상태면_5초로_준다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2, P3);
		board.vote(P1, A, present);
		board.vote(P2, B, present);
		assertFalse(board.shortened());
		assertTrue(board.prune(Set.of(P1, P2)), "P3 가 나갔다");
		assertTrue(board.shortened());
		assertEquals(DragonPrepVote.ALL_VOTED_TICKS, board.stageTicks());
	}

	@Test
	void 아무도_없으면_전원이_던졌다고_보지_않는다() {
		DragonPrepVote board = votingBoard();
		board.prune(Set.of());
		assertFalse(board.shortened(), "빈 묶음은 「전원」이 아니다 — 시계가 5초로 튀면 안 된다");
		assertEquals(DragonPrepVote.VOTE_TICKS, board.stageTicks());
	}

	@Test
	void 정해진_뒤에는_표를_받지도_거두지도_않는다() {
		DragonPrepVote board = votingBoard();
		Set<UUID> present = Set.of(P1, P2);
		board.vote(P1, A, present);
		runOut(board, RandomSource.create(1L));
		assertEquals(DragonPrepVote.Stage.RESULT, board.stage());
		assertFalse(board.vote(P2, B, present));
		assertFalse(board.prune(Set.of()), "결과 화면의 이름 줄은 기록이다");
		assertEquals(1, board.count(A));
	}

	@Test
	void 실제로_준_증강으로_결과를_바꾼다() {
		DragonPrepVote board = votingBoard();
		board.vote(P1, A, Set.of(P1));
		runOut(board, RandomSource.create(1L));
		board.settle(B);
		assertEquals(B, board.winner());
		board.settle("sharedfate:nope");
		assertEquals(B, board.winner(), "후보에 없는 id 로는 바꾸지 않는다");
	}

	@Test
	void 표가_가장_많은_후보를_센다() {
		assertEquals(List.of(A), DragonPrepVote.leaders(java.util.Map.of(P1, A), List.of(A, B)));
		assertEquals(List.of(A, B), DragonPrepVote.leaders(java.util.Map.of(), List.of(A, B)));
		assertEquals(List.of(A, B),
				DragonPrepVote.leaders(java.util.Map.of(P1, A, P2, B, P3, "x"), List.of(A, B)),
				"후보에 없는 대상의 표는 세지 않는다");
		assertTrue(DragonPrepVote.leaders(java.util.Map.of(), List.of()).isEmpty());
		assertNull(new DragonPrepVote(List.of(A, B)).winner());
	}

	@Test
	void 단계_통신_값은_차례_그대로다() {
		assertEquals(DragonPrepVote.Stage.VOTE, DragonPrepVote.Stage.byId(1));
		assertEquals(DragonPrepVote.Stage.RESULT, DragonPrepVote.Stage.byId(3));
		assertEquals(DragonPrepVote.Stage.REVEAL, DragonPrepVote.Stage.byId(99),
				"모르는 값은 아무것도 못 누르는 단계로 본다");
	}
}
