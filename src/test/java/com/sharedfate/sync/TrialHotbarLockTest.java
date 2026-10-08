package com.sharedfate.sync;

import com.sharedfate.client.ClientHotbarLock;
import com.sharedfate.net.TrialHotbarLockPayload;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Inventory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「굳는 손」에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>막는 일과 붉은 표시는 {@code ServerPlayer} 와 화면이 있어야 해서 여기서 볼 수 없다. 그런데
 * 이 카드가 망가지는 길은 <b>거의 전부 뽑기 쪽</b>이다.
 *
 * <ul>
 *   <li><b>쌓인다</b> — 지난 비트와 OR 한 줄이면 135초에 핫바가 전부 굳어 이길 방법이 사라진다.
 *       즉사보다 나쁘고, 실제로 그 판에 가 보기 전에는 아무도 못 본다</li>
 *   <li><b>제자리에 머문다</b> — 같은 칸이 다시 뽑히면 소리만 울리고 아무것도 안 바뀐다.
 *       사람은 그것을 버그가 아니라 「운」으로 읽어 신고조차 하지 않는다</li>
 *   <li><b>칸 수가 흔들린다</b> — 어느 주기에만 한 칸이 되는 것은 돌려 봐서는 절대 안 보인다</li>
 * </ul>
 */
class TrialHotbarLockTest {

	/** 「굳는 손」의 카드 값. 시험이 현실과 붙어 있으려면 실제 카드에서 뽑아 와야 한다. */
	private static TrialCatalog.Risk.HotbarLock card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:hotbar_lock");
		if (trial == null || trial.risks().size() != 1) {
			return fail("「굳는 손」 카드가 없다");
		}
		// 봉인 인터페이스가 늘어나도 이 시험이 깨지지 않게 switch 대신 instanceof 로 본다.
		if (trial.risks().getFirst() instanceof TrialCatalog.Risk.HotbarLock lock) {
			return lock;
		}
		return fail("「굳는 손」이 HotbarLock 이 아니다");
	}

	// ------------------------------------------------------------------ 카드 값

	@Test
	void 카드는_30초마다_두_칸을_굳힌다() {
		TrialCatalog.Risk.HotbarLock lock = card();
		assertEquals(600, lock.interval(), "설명에 적힌 30초와 값이 어긋나면 카드가 거짓말을 한다");
		assertEquals(2, lock.slots(), "설명에 적힌 두 칸과 값이 어긋나면 카드가 거짓말을 한다");
		assertTrue(lock.slots() < Inventory.SELECTION_SIZE,
				"한 번에 핫바 전체를 굳히면 옮겨 담을 칸이 남지 않는다");
	}

	// ------------------------------------------------------------------ ① 쌓이지 않는다

	/**
	 * <b>이 시험이 이 카드의 ① 을 지킨다.</b>
	 *
	 * <p>{@code move} 안에서 지난 비트와 OR 하는 줄 하나면 이 시험이 먼저 깨진다. 실제 판에서는
	 * 135초를 기다려야 드러나는 고장이다.
	 */
	@Test
	void 주기를_아무리_돌려도_굳은_칸은_늘_적힌_수만큼이다() {
		TrialCatalog.Risk.HotbarLock card = card();
		RandomSource random = RandomSource.create(20260930L);
		int mask = 0;
		for (int cycle = 0; cycle < 500; cycle++) {
			mask = TrialHotbarLock.move(random, card.slots(), mask);
			assertEquals(card.slots(), TrialHotbarLock.count(mask),
					cycle + " 번째 주기에서 칸 수가 달라졌다 — 쌓이거나 새는 중이다");
		}
	}

	@Test
	void 굳은_칸은_핫바_밖으로_나가지_않는다() {
		RandomSource random = RandomSource.create(7L);
		for (int round = 0; round < 500; round++) {
			int mask = TrialHotbarLock.move(random, 2, 0);
			assertEquals(0, mask & ~TrialHotbarLockPayload.ALL_SLOTS,
					"핫바에 없는 칸이 굳었다 — 화면이 칠할 자리도 없다");
		}
	}

	// ------------------------------------------------------------------ 옮겨 다닌다

	@Test
	void 새로_굳는_칸은_지난_칸을_피한다() {
		RandomSource random = RandomSource.create(31L);
		int mask = TrialHotbarLock.move(random, 2, 0);
		for (int cycle = 0; cycle < 500; cycle++) {
			int next = TrialHotbarLock.move(random, 2, mask);
			assertEquals(0, next & mask,
					"지난 칸이 그대로 다시 굳었다 — 소리만 울리고 아무것도 안 바뀐 주기가 된다");
			mask = next;
		}
	}

	/**
	 * 회피를 버리는 자리.
	 *
	 * <p>{@code slots * 2 > 9} 면 지난 칸을 전부 피할 수 없다. 그때 <b>개수를 줄이지 않는다</b> —
	 * 「언제나 slots 칸」이 이 카드의 약속이고, 조용히 한 칸 모자란 주기가 생기는 쪽이 훨씬
	 * 알아채기 어렵다.
	 */
	@Test
	void 피할_칸이_모자라면_회피를_버리고_개수를_지킨다() {
		RandomSource random = RandomSource.create(99L);
		int mask = TrialHotbarLock.move(random, 5, 0);
		assertEquals(5, TrialHotbarLock.count(mask));
		for (int cycle = 0; cycle < 200; cycle++) {
			mask = TrialHotbarLock.move(random, 5, mask);
			assertEquals(5, TrialHotbarLock.count(mask),
					"아홉 칸에서 다섯을 뽑는 판이다. 회피가 개수를 깎으면 안 된다");
		}
	}

	/**
	 * 아홉 칸이 모두 뽑힐 수 있다.
	 *
	 * <p>부분 Fisher–Yates 의 경계가 하나만 어긋나도 끝 칸이 영영 안 뽑힌다 — 개수도 맞고 회피도
	 * 되므로 위 시험들이 전부 통과한다. 여덟 칸만 도는 카드는 돌려 봐서는 알 수 없다.
	 */
	@Test
	void 아홉_칸이_모두_굳을_수_있다() {
		RandomSource random = RandomSource.create(2026L);
		int seen = 0;
		for (int round = 0; round < 2000; round++) {
			seen |= TrialHotbarLock.move(random, 2, 0);
		}
		assertEquals(TrialHotbarLockPayload.ALL_SLOTS, seen,
				"뽑히지 않는 칸이 있다 — 그 칸은 이 카드가 영영 건드리지 않는 안전지대가 된다");
	}

	@Test
	void 칸_수가_0_이하면_아무_칸도_굳지_않는다() {
		RandomSource random = RandomSource.create(1L);
		assertEquals(0, TrialHotbarLock.move(random, 0, 0));
		assertEquals(0, TrialHotbarLock.move(random, -3, 0));
	}

	@Test
	void 카드_값이_핫바보다_커도_핫바를_넘지_않는다() {
		assertEquals(Inventory.SELECTION_SIZE, TrialHotbarLock.wanted(100));
		RandomSource random = RandomSource.create(5L);
		assertEquals(TrialHotbarLockPayload.ALL_SLOTS, TrialHotbarLock.move(random, 100, 0),
				"아홉을 넘는 값을 그대로 뽑으려 들면 뽑기가 돌 자리가 없어진다");
	}

	// ------------------------------------------------------------------ 비트 읽기

	@Test
	void 핫바_밖_번호는_언제나_굳어_있지_않다() {
		int all = TrialHotbarLockPayload.ALL_SLOTS;
		assertFalse(TrialHotbarLock.frozen(all, -1), "음수를 밀면 1 << -1 이 엉뚱한 비트를 짚는다");
		assertFalse(TrialHotbarLock.frozen(all, Inventory.SELECTION_SIZE));
		assertTrue(TrialHotbarLock.frozen(all, 0));
		assertTrue(TrialHotbarLock.frozen(all, Inventory.SELECTION_SIZE - 1));
	}

	// ------------------------------------------------------------------ 주기의 위상

	/**
	 * 위상은 받은 틱에서 센다.
	 *
	 * <p>{@code now % SYNC_INTERVAL} 로 세면 이 카드를 가진 팀 전원의 패킷이 같은 틱에 몰린다.
	 * 「시련 전체가 같은 틱에 몰린다」는 이 저장소가 이미 한 번 겪은 사고다({@code TrialRisks} 의
	 * 「주기는 카드를 받은 틱부터 센다」).
	 */
	@Test
	void 다시_알리는_위상은_월드_시간이_아니라_받은_틱에서_센다() {
		long granted = 7L;
		assertTrue(TrialHotbarLock.heartbeat(granted, granted),
				"받은 그 틱에 한 번은 보내야 한다 — 안 보내면 첫 1초 동안 붉은 표시가 없다");
		assertFalse(TrialHotbarLock.heartbeat(granted + 1, granted));
		assertTrue(TrialHotbarLock.heartbeat(granted + TrialHotbarLock.SYNC_INTERVAL, granted));
		assertFalse(TrialHotbarLock.heartbeat(TrialHotbarLock.SYNC_INTERVAL, granted),
				"월드 시간의 배수에서 울리면 카드마다 위상이 겹친다");
	}

	/** 복원 직후 {@code now < granted} 인 틱에도 음수 위상으로 떨어지지 않는다. */
	@Test
	void 되감긴_틱에서도_위상이_음수로_떨어지지_않는다() {
		assertTrue(TrialHotbarLock.heartbeat(100L, 500L),
				"elapsedSinceGrant 가 0 으로 깎으므로 되감긴 틱은 「받은 그 틱」과 같다");
	}

	// ------------------------------------------------------------------ 값끼리의 약속

	/**
	 * 다시 알리는 간격과 클라이언트가 스스로 지우는 시간의 짝.
	 *
	 * <p>이 둘이 가까워지면 패킷 한 장이 늦을 때마다 붉은 칸이 깜빡인다. 두 파일에 나뉘어 있어
	 * 한쪽만 고치기 쉬운 값이라 여기서 묶어 둔다.
	 */
	@Test
	void 다시_알리는_간격이_스스로_지우는_시간보다_넉넉히_짧다() {
		assertTrue(TrialHotbarLock.SYNC_INTERVAL > 0);
		assertTrue(TrialHotbarLock.SYNC_INTERVAL * 2L <= ClientHotbarLock.STALE_TICKS,
				"패킷 한 장을 놓쳐도 붉은 표시가 살아 있어야 한다. 지금 간격: "
						+ TrialHotbarLock.SYNC_INTERVAL + " / 지우는 시간: "
						+ ClientHotbarLock.STALE_TICKS);
	}

	/**
	 * 분배기가 틱을 멈추면 자물쇠가 스스로 풀린다.
	 *
	 * <p>드래곤이 죽는 틱에 {@code DragonTrialManager} 는 세션을 닫기만 하고 시련 상태를 비우는
	 * 길을 지나지 않는다. 이 값이 카드 주기만큼 길어지면 <b>드래곤을 잡은 보상이 30초 동안 손이
	 * 굳어 있는 것</b>이 된다.
	 */
	@Test
	void 틱이_끊기면_한_주기가_지나기_한참_전에_풀린다() {
		assertTrue(TrialHotbarLock.LAPSE_TICKS > 0);
		assertTrue(TrialHotbarLock.LAPSE_TICKS * 4L < card().interval(),
				"전투가 끝난 뒤에도 오래 굳어 있으면 승리한 사람이 원인을 찾을 수 없다");
	}

	// ------------------------------------------------------------------ 묶음

	@Test
	void 묶음은_핫바_밖_비트를_실어_나르지_않는다() {
		assertEquals(TrialHotbarLockPayload.ALL_SLOTS,
				new TrialHotbarLockPayload(-1).frozenMask(),
				"음수가 VAR_INT 로 나가면 다섯 바이트를 쓰고, 화면은 없는 칸을 칠하려 든다");
		assertEquals(0b101, new TrialHotbarLockPayload(0b101).frozenMask());
		assertEquals(0, new TrialHotbarLockPayload(1 << Inventory.SELECTION_SIZE).frozenMask(),
				"핫바 밖 비트만 켜서 보내면 아무 칸도 굳지 않은 것과 같아야 한다");
	}
}
