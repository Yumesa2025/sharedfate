package com.sharedfate.perk;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 고른 증강을 보여 주는 결과 화면이 <b>설계한 5초를 다 채우는가</b>(2026-11-04).
 *
 * <h2>무엇이 깨져 있었나</h2>
 * <p>고르는 순간 {@code PerkManager.commit} 이 그 선택권을 대기열에서 뺀다. 그런데 세션의 매 틱은 맨 앞에서
 * 「대기열 맨 앞이 이 세션의 구간이 아니면 다른 경로로 이미 처리된 것」이라 보고 닫았다 — 결과 단계인지 보기
 * 전에. 그래서 일반(실버·골드·프리즘) 증강은 고른 <b>다음 틱</b>에 창이 닫혀, 결과 화면(0.7.0-dev 에 3초로
 * 생겨 그 뒤 5초로 늘었다 — CHANGELOG 「결과 화면이 3초에서 5초로 늘었습니다」)이 사실상 없었다. 0.7.0-dev
 * 부터 줄곧 그랬다. 「드래곤전 대비」 투표만 예외를 두어 5초가 남아 있었다.
 *
 * <p>세션 전체는 살아 있는 서버가 있어야 돌므로, 매 틱이 닫을지 정하는 판정
 * ({@link PerkChoiceSession#offerSettledElsewhere})을 세션이 넘기는 값 그대로 시험한다.
 */
class PerkResultHoldTest {
	private static final UUID CHOOSER = new UUID(0, 1);

	private static PendingOffer offer(int milestone) {
		return new PendingOffer(milestone, Optional.of(CHOOSER), List.of("sharedfate:a", "sharedfate:b"));
	}

	@Test
	void 결과_화면은_5초다() {
		assertEquals(100, PerkChoiceSession.RESULT_TICKS);
	}

	/** 사람이 본 바로 그 경우 — 하나뿐인 선택권을 고르면 대기열이 빈다. 그래도 결과 5초 동안은 닫지 않는다. */
	@Test
	void 고른_뒤_대기열이_비어도_결과를_보여_주는_동안은_닫지_않는다() {
		assertFalse(PerkChoiceSession.offerSettledElsewhere(List.of(), 5,
				PerkChoiceSession.Phase.RESULT, null));
	}

	/** 한꺼번에 여러 구간에 닿았으면 다음 선택권이 맨 앞에 온다. 그것은 결과 화면이 끝난 뒤 새 세션으로 연다. */
	@Test
	void 다음_선택권이_기다려도_결과를_다_보여_준_뒤에_넘어간다() {
		assertFalse(PerkChoiceSession.offerSettledElsewhere(List.of(offer(10)), 5,
				PerkChoiceSession.Phase.RESULT, null));
	}

	/** 결과 단계가 아니면 예전 그대로다 — 다른 길로 처리된 선택권을 붙잡고 시간을 멈춰 두면 안 된다. */
	@Test
	void 고르기_전에_선택권이_사라졌으면_예전처럼_닫는다() {
		for (PerkChoiceSession.Phase phase : List.of(PerkChoiceSession.Phase.DRAW,
				PerkChoiceSession.Phase.CHOOSE)) {
			assertTrue(PerkChoiceSession.offerSettledElsewhere(List.of(), 5, phase, null), phase.name());
			assertTrue(PerkChoiceSession.offerSettledElsewhere(List.of(offer(10)), 5, phase, null),
					phase.name());
			assertFalse(PerkChoiceSession.offerSettledElsewhere(List.of(offer(5), offer(10)), 5, phase, null),
					"맨 앞이 이 구간이면 아직 처리되지 않았다");
		}
	}

	/** 「드래곤전 대비」 투표는 이미 예외가 있었다. 그대로 결과 5초를 지킨다. */
	@Test
	void 드래곤전_대비_투표의_결과_화면도_그대로_지킨다() {
		DragonPrepVote prep = new DragonPrepVote(List.of("sharedfate:a", "sharedfate:b"));
		RandomSource random = RandomSource.create(1L);
		while (prep.stage() != DragonPrepVote.Stage.VOTE) {
			prep.tick(random);
		}
		assertTrue(PerkChoiceSession.offerSettledElsewhere(List.of(), 45, PerkChoiceSession.Phase.DRAW, prep),
				"투표 중에 선택권이 사라졌으면 닫는다");
		prep.vote(CHOOSER, "sharedfate:a", Set.of(CHOOSER));
		while (prep.stage() == DragonPrepVote.Stage.VOTE) {
			prep.tick(random);
		}
		assertEquals(DragonPrepVote.Stage.RESULT, prep.stage());
		assertFalse(PerkChoiceSession.offerSettledElsewhere(List.of(), 45, PerkChoiceSession.Phase.DRAW, prep));
		assertFalse(PerkChoiceSession.offerSettledElsewhere(List.of(offer(50)), 45,
				PerkChoiceSession.Phase.DRAW, prep), "50 이 기다려도 45 결과를 다 보여 준다");
	}
}
