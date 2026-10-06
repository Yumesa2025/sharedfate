package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.net.TrialTimersPayload;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 드래곤 패턴 타이머 HUD 의 서버 쪽 — <b>누구에게 · 언제</b> 보내는가.
 *
 * <p>2026-10-06 Orca 검토 F-verified 의 V10: 보내기·지우기·받는 사람 로직에 시험이 하나도 없었다.
 * 지금 코드는 맞게 돌지만 줄 순서 하나만 바뀌어도 조용히 깨지는 종류라, 결정부를 순수 함수
 * ({@link TrialTimers#plan} · {@link TrialTimers#audienceOf})로 떼어 여기서 굴린다. 사람과 연결은
 * UUID 와 「보낼 수 있는가」 술어로 바꿔 넣는다.
 *
 * <p>못박는 것 — 지우는 묶음은 <b>정확히 한 번</b> · 보낼 수 없으면 <b>기록하지 않는다</b> · 상태가
 * 바뀌면 <b>곧장</b> · 안 바뀌면 <b>박동 {@value TrialTimers#HEARTBEAT_TICKS}틱</b>마다 · 얼어 있는 동안에도
 * <b>{@code frozen = true} 로 나간다</b> · 시련을 끈 팀·없어진 팀·엔드에 아무도 없는 팀은 안 받는다.
 */
class TrialTimersPublishTest {

	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();
	private static final Predicate<UUID> EVERYONE = id -> true;
	private static final long GAME = 50_000L;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void forget() {
		TrialTimers.clearState();
	}

	/** 줄 하나짜리 묶음. 남은 틱만 바꿔 「예측대로 줄었다 / 튀었다」를 만든다. */
	private static TrialTimersPayload payload(int remaining, boolean frozen) {
		return new TrialTimersPayload(true, frozen,
				List.of(Entry.countdown("sharedfate:test", "자리 폭격", Kind.DAMAGE, remaining, 400, 50)),
				Optional.empty());
	}

	private static Map<UUID, TrialTimersPayload> to(UUID player, TrialTimersPayload payload) {
		Map<UUID, TrialTimersPayload> audience = new HashMap<>();
		audience.put(player, payload);
		return audience;
	}

	// ------------------------------------------------------------------ 보내기

	@Test
	void 처음_받는_사람에게는_곧장_보내고_기록한다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimersPayload first = payload(200, false);
		List<TrialTimers.Delivery> out = TrialTimers.plan(sent, to(ALICE, first), EVERYONE, GAME, 100);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, first)), out);
		assertEquals(new TrialTimers.Sent(first, GAME, 100), sent.get(ALICE));
	}

	/** 예측대로 줄기만 하면 박동 전에는 안 보내고, 박동 틱에 다시 보낸다. */
	@Test
	void 상태가_안_바뀌면_박동_20틱마다만_보낸다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);
		for (int tick = 1; tick < TrialTimers.HEARTBEAT_TICKS; tick++) {
			List<TrialTimers.Delivery> out = TrialTimers.plan(sent,
					to(ALICE, payload(200 - tick, false)), EVERYONE, GAME + tick, 100 + tick);
			assertTrue(out.isEmpty(), tick + "틱째에 상태도 안 바뀌었는데 보냈다");
		}
		int beat = TrialTimers.HEARTBEAT_TICKS;
		List<TrialTimers.Delivery> out = TrialTimers.plan(sent,
				to(ALICE, payload(200 - beat, false)), EVERYONE, GAME + beat, 100 + beat);
		assertEquals(1, out.size(), "박동 틱에 다시 보내지 않았다");
		assertEquals(100 + beat, sent.get(ALICE).serverTick(), "박동으로 보낸 것을 기록하지 않았다");
	}

	/** 발동해 남은 틱이 다음 주기로 튀면 박동을 기다리지 않는다. */
	@Test
	void 상태가_바뀌면_곧장_보낸다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(5, false)), EVERYONE, GAME, 100);
		TrialTimersPayload fired = payload(400, false);
		List<TrialTimers.Delivery> out = TrialTimers.plan(sent, to(ALICE, fired), EVERYONE,
				GAME + 1, 101);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, fired)), out);
	}

	// ------------------------------------------------------------------ 지우기

	/** 받던 사람이 빠지면 지우는 묶음을 <b>한 번</b> — 다음 틱에는 다시 안 보낸다. */
	@Test
	void 떠난_사람은_지우는_묶음을_정확히_한_번_받는다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);

		List<TrialTimers.Delivery> left = TrialTimers.plan(sent, Map.of(), EVERYONE, GAME + 1, 101);
		assertEquals(1, left.size());
		assertEquals(ALICE, left.get(0).player());
		assertSame(TrialTimersPayload.HIDDEN, left.get(0).payload());
		assertFalse(sent.containsKey(ALICE), "지운 사람을 기록에서 안 빼면 매 틱 지우는 묶음을 받는다");

		for (int tick = 2; tick < 60; tick++) {
			assertTrue(TrialTimers.plan(sent, Map.of(), EVERYONE, GAME + tick, 100 + tick).isEmpty(),
					tick + "틱째에 지우는 묶음을 또 보냈다");
		}
	}

	/** 안 보이는 묶음(시련을 끈 팀의 {@code collect} 결과)은 받지 않는 사람으로 센다. */
	@Test
	void 안_보이는_묶음은_떠난_것과_같다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);
		List<TrialTimers.Delivery> out = TrialTimers.plan(sent, to(ALICE, TrialTimersPayload.HIDDEN),
				EVERYONE, GAME + 1, 101);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, TrialTimersPayload.HIDDEN)), out);
		assertTrue(TrialTimers.plan(sent, to(ALICE, TrialTimersPayload.HIDDEN), EVERYONE, GAME + 2, 102)
				.isEmpty(), "안 보이는 묶음을 매 틱 보냈다");
	}

	/** 접속을 끊은 사람에게는 보낼 길이 없다 — 기록만 뺀다. 다시 들어오면 처음 받는 사람이다. */
	@Test
	void 끊은_사람은_기록에서만_빠지고_다시_들어오면_곧장_받는다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);

		assertTrue(TrialTimers.plan(sent, Map.of(), id -> false, GAME + 1, 101).isEmpty());
		assertFalse(sent.containsKey(ALICE), "끊은 사람이 기록에 남으면 다시 들어왔을 때 「이미 보냈다」로 읽힌다");

		TrialTimersPayload again = payload(198, false);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, again)),
				TrialTimers.plan(sent, to(ALICE, again), EVERYONE, GAME + 2, 102));
	}

	// ------------------------------------------------------------------ 보낼 수 없는 사람

	/**
	 * 받을 줄 모르는 클라이언트에는 안 보내고 <b>기록하지도 않는다.</b> 기록하면 받을 수 있게 된 틱에
	 * 「이미 보냈다」로 읽혀 박동까지 빈 화면이다.
	 */
	@Test
	void 보낼_수_없으면_기록하지_않고_되는_틱에_곧장_보낸다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		assertTrue(TrialTimers.plan(sent, to(ALICE, payload(200, false)), id -> false, GAME, 100)
				.isEmpty());
		assertTrue(sent.isEmpty(), "보내지 못한 것을 기록했다");

		TrialTimersPayload next = payload(199, false);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, next)),
				TrialTimers.plan(sent, to(ALICE, next), EVERYONE, GAME + 1, 101),
				"받을 수 있게 된 틱에 박동을 기다렸다");
	}

	/**
	 * 받는 사람 목록에 있으면 <b>보낼 수 없어도 「받는 사람」</b>이다 — 지우는 묶음 후보가 아니고 기록도
	 * 그대로 남는다. 「받았다」를 보낼 수 있는지 묻기 <b>앞</b>에서 세는 순서를 못박는다.
	 */
	@Test
	void 보낼_수_없는_받는_사람은_지울_사람이_아니다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);
		TrialTimers.Sent before = sent.get(ALICE);

		List<TrialTimers.Delivery> out = TrialTimers.plan(sent, to(ALICE, payload(400, false)),
				id -> false, GAME + 1, 101);
		assertTrue(out.isEmpty(), "보낼 수 없는 사람에게 무언가를 보냈다");
		assertEquals(before, sent.get(ALICE), "받는 사람의 기록이 지워지거나 바뀌었다");
	}

	/** 둘 가운데 하나만 떠나면 그 사람만 지운다. */
	@Test
	void 떠난_사람만_지우고_남은_사람은_건드리지_않는다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		Map<UUID, TrialTimersPayload> both = new HashMap<>();
		both.put(ALICE, payload(200, false));
		both.put(BOB, payload(200, false));
		assertEquals(2, TrialTimers.plan(sent, both, EVERYONE, GAME, 100).size());

		List<TrialTimers.Delivery> out = TrialTimers.plan(sent, to(BOB, payload(199, false)),
				EVERYONE, GAME + 1, 101);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, TrialTimersPayload.HIDDEN)), out);
		assertEquals(Set.of(BOB), sent.keySet());
	}

	// ------------------------------------------------------------------ 멈춤

	/**
	 * 얼면 {@code frozen} 이 바뀐 묶음을 <b>곧장</b> 보내고, 얼어 있는 동안 박동도 {@code frozen = true}
	 * 로 나간다 — 받는 쪽은 그 깃발을 보고 카운트다운을 세운다. 얼어 있으면 게임 시각이 서므로
	 * 남은 틱도 그대로다.
	 */
	@Test
	void 얼면_곧장_frozen_으로_보내고_얼어_있는_동안_박동도_frozen_이다() {
		Map<UUID, TrialTimers.Sent> sent = new HashMap<>();
		TrialTimers.plan(sent, to(ALICE, payload(200, false)), EVERYONE, GAME, 100);

		TrialTimersPayload frozen = payload(200, true);
		List<TrialTimers.Delivery> froze = TrialTimers.plan(sent, to(ALICE, frozen), EVERYONE,
				GAME, 101);
		assertEquals(List.of(new TrialTimers.Delivery(ALICE, frozen)), froze, "어는 틱에 곧장 안 보냈다");

		List<TrialTimers.Delivery> beats = new ArrayList<>();
		for (int tick = 102; tick <= 101 + TrialTimers.HEARTBEAT_TICKS; tick++) {
			beats.addAll(TrialTimers.plan(sent, to(ALICE, frozen), EVERYONE, GAME, tick));
		}
		assertEquals(1, beats.size(), "얼어 있는 동안 박동이 정확히 한 번이어야 한다");
		assertTrue(beats.get(0).payload().frozen(), "얼어 있는 동안의 박동이 frozen 을 안 실었다");
	}

	// ------------------------------------------------------------------ 받는 사람 (publishTimers)

	@Test
	void 시련을_켠_팀의_엔드에_선_팀원만_그_팀_묶음을_받는다() {
		DragonTrialSession on = new DragonTrialSession(UUID.randomUUID(), GAME, true);
		DragonTrialSession off = new DragonTrialSession(UUID.randomUUID(), GAME, false);
		DragonTrialSession gone = new DragonTrialSession(UUID.randomUUID(), GAME, true);
		DragonTrialSession empty = new DragonTrialSession(UUID.randomUUID(), GAME, true);
		Map<UUID, List<String>> inEnd = new HashMap<>();
		inEnd.put(on.teamId(), List.of("앨리스", "밥"));
		inEnd.put(off.teamId(), List.of("시련 끈 사람"));
		inEnd.put(empty.teamId(), List.of());
		// gone 은 지도에 없다 — 팀이 없어졌다.
		List<UUID> asked = new ArrayList<>();
		TrialTimersPayload ours = payload(200, false);

		Map<String, TrialTimersPayload> audience = TrialTimers.audienceOf(
				List.of(on, off, gone, empty), inEnd::get, session -> {
					asked.add(session.teamId());
					return ours;
				});

		assertEquals(Map.of("앨리스", ours, "밥", ours), audience);
		assertEquals(List.of(on.teamId()), asked,
				"받을 사람이 없는 팀(끈 팀·없어진 팀·엔드에 아무도 없는 팀)의 묶음까지 만들었다");
	}

	/** 얼어 있는 판에서 모은 묶음이 그대로 {@code frozen = true} 로 나간다 — 모으기부터 보내기까지. */
	@Test
	void 얼어_있는_판의_묶음이_frozen_으로_나간다() {
		DragonTrialSession session = new DragonTrialSession(UUID.randomUUID(), GAME, true);
		Map<UUID, TrialTimersPayload> audience = TrialTimers.audienceOf(List.of(session),
				team -> List.of(ALICE), each -> TrialTimers.collect(each, true, 0.9F, GAME + 10));
		List<TrialTimers.Delivery> out = TrialTimers.plan(new HashMap<>(), audience, EVERYONE,
				GAME + 10, 100);
		assertEquals(1, out.size());
		assertTrue(out.get(0).payload().visible());
		assertTrue(out.get(0).payload().frozen(), "얼어 있는 판의 HUD 가 frozen 을 안 실었다");
	}

	/**
	 * 떼어 낸 결정부를 실제로 지나는가 — 고치기 전의 클래스에는 두 이름이 없다. 시험이 굴리는 함수와
	 * 서버가 쓰는 길이 갈라지면 위 시험들이 아무것도 지키지 못한다.
	 */
	@Test
	void 서버가_떼어_낸_결정부를_지난다() {
		assertTrue(bytesOf("DragonTrialManager").contains("audienceOf"),
				"publishTimers 가 audienceOf 를 안 지난다 — 받는 사람 시험이 서버 길을 못 지킨다");
		assertTrue(bytesOf("TrialTimers").contains("plan"),
				"publish 가 plan 을 안 지난다 — 보내기 시험이 서버 길을 못 지킨다");
	}

	private static String bytesOf(String simpleName) {
		String path = "/com/sharedfate/sync/" + simpleName + ".class";
		try (InputStream in = TrialTimersPublishTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
