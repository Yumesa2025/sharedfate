package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.ui.StatSummary;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서버만 아는 능력치를 클라이언트로 보내는 규칙.
 *
 * <p>「언제 다시 보내는가」와 「이상한 값을 어떻게 눕히는가」 둘만 본다. 값을 <b>뽑는</b> 쪽
 * ({@code getBaseValue}/{@code getValue}·증강 배율)은 살아 있는 서버가 있어야 하므로 여기서
 * 보지 않고, {@code ScreenStatSourceTest} 가 그 값들이 무엇인지를 붙들어 둔다.
 */
class StatSnapshotSyncTest {

	private static StatSnapshotPayload snapshot(float base, float current) {
		return new StatSnapshotPayload(base, current, 1.0F, 1.0F, 1.0F, 0.0F);
	}

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 한 번도 보내지 않았으면 무조건 보낸다. 접속 직후와 재접속 직후가 이 경우다. */
	@Test
	void 아직_보낸_적이_없으면_보낸다() {
		assertTrue(snapshot(1.0F, 1.0F).differsFrom(null));
	}

	@Test
	void 값이_그대로면_다시_보내지_않는다() {
		assertFalse(snapshot(1.0F, 7.0F).differsFrom(snapshot(1.0F, 7.0F)));
	}

	/** 무기를 바꾸면 지금 값이 달라진다. 그것이 곧 다시 보내는 신호다. */
	@Test
	void 지금_값이_달라지면_다시_보낸다() {
		StatSnapshotPayload sent = snapshot(1.0F, 7.0F);

		assertTrue(snapshot(1.0F, 4.0F).differsFrom(sent));
		assertTrue(snapshot(1.0F, 9.0F).differsFrom(sent));
	}

	/** 기본값이 움직이는 일은 드물지만, 움직였는데 안 보내면 「→」 왼쪽이 거짓말이 된다. */
	@Test
	void 기본값이_달라져도_다시_보낸다() {
		assertTrue(snapshot(2.0F, 7.0F).differsFrom(snapshot(1.0F, 7.0F)));
	}

	/**
	 * 배율 셋도 각각 본다.
	 *
	 * <p>공격력만 보면 <b>증강을 골라 몹이 두 배가 된 순간</b>을 놓친다. 무기를 바꾸기
	 * 전까지 화면이 옛 배율을 그대로 들고 있게 되는데, 그게 이 묶음에서 가장 늦게 들통나는
	 * 종류의 버그다.
	 */
	@Test
	void 배율이_달라져도_다시_보낸다() {
		StatSnapshotPayload sent = snapshot(1.0F, 7.0F);

		assertTrue(new StatSnapshotPayload(1.0F, 7.0F, 2.5F, 1.0F, 1.0F, 0.0F).differsFrom(sent),
				"받는 피해 배율");
		assertTrue(new StatSnapshotPayload(1.0F, 7.0F, 1.0F, 1.15F, 1.0F, 0.0F).differsFrom(sent),
				"몹 최대 체력 배율");
		assertTrue(new StatSnapshotPayload(1.0F, 7.0F, 1.0F, 1.0F, 1.15F, 0.0F).differsFrom(sent),
				"몹 공격력 배율");
	}

	/**
	 * 「달라졌다」의 기준을 화면과 나눠 쓴다.
	 *
	 * <p>{@link StatSummary#changed} 가 무시할 만큼 작은 차이면 패킷도 나가지 않아야 한다.
	 * 두 기준이 갈라지면 초당 네 번씩 아무 뜻 없는 패킷이 나가거나, 반대로 화면이 옛 글자를
	 * 그대로 들고 있게 된다.
	 */
	@Test
	void 화면이_구분하지_못하는_차이로는_보내지_않는다() {
		StatSnapshotPayload sent = snapshot(1.0F, 7.0F);
		float dust = Math.nextUp(7.0F);

		assertFalse(StatSummary.changed(7.0F, dust));
		assertFalse(snapshot(1.0F, dust).differsFrom(sent));
	}

	@Test
	void 무한대와_NaN_은_0으로_눕힌다() {
		assertEquals(0.0F, snapshot(Float.NaN, Float.NaN).attackDamageCurrent());
		assertEquals(0.0F, snapshot(1.0F, Float.POSITIVE_INFINITY).attackDamageCurrent());
		assertEquals(0.0F, snapshot(Float.NEGATIVE_INFINITY, 1.0F).attackDamageBase());
	}

	/** 바닐라는 이 속성을 0 아래로 두지 않는다. 다른 모드가 범위를 바꿔도 화면은 지킨다. */
	@Test
	void 음수_공격력은_0으로_눕힌다() {
		StatSnapshotPayload payload = snapshot(-3.0F, -1.0F);

		assertEquals(0.0F, payload.attackDamageBase());
		assertEquals(0.0F, payload.attackDamageCurrent());
	}

	/**
	 * 배율은 0 이나 음수가 될 수 없다.
	 *
	 * <p>0 을 그대로 두면 화면이 「몹 체력 100% → 0%」라고 적는데, 그런 몹은 존재할 수 없다.
	 * 없는 사실을 적느니 「아무것도 안 걸렸다」로 물러난다.
	 */
	@Test
	void 이상한_배율은_100퍼센트로_눕힌다() {
		StatSnapshotPayload payload =
				new StatSnapshotPayload(1.0F, 1.0F, 0.0F, -2.0F, Float.NaN, 0.0F);

		assertEquals(1.0F, payload.damageTaken());
		assertEquals(1.0F, payload.mobHealth());
		assertEquals(1.0F, payload.mobDamage());
	}

	// ------------------------------------------------------------------ 「완충」 남은 피해

	private static StatSnapshotPayload pending(float amount) {
		return new StatSnapshotPayload(1.0F, 7.0F, 1.0F, 1.0F, 1.0F, amount);
	}

	/** 나눠 받기가 시작되거나 끝나는 순간은 아무리 작은 값이어도 보낸다. 안 보내면 칸이 남는다. */
	@Test
	void 남은_피해가_생기거나_사라지면_보낸다() {
		assertTrue(pending(0.05F).differsFrom(pending(0.0F)));
		assertTrue(pending(0.0F).differsFrom(pending(0.05F)));
	}

	/** 몫이 하나 들어갈 때마다 값이 줄고, 그때마다 화면이 따라와야 한다. */
	@Test
	void 남은_피해가_줄면_다시_보낸다() {
		assertTrue(pending(6.0F).differsFrom(pending(8.0F)));
		assertFalse(pending(8.0F + StatSnapshotPayload.PENDING_STEP / 2).differsFrom(pending(8.0F)),
				"소수점 부스러기로는 보내지 않는다");
	}

	@Test
	void 이상한_남은_피해는_0으로_눕힌다() {
		assertEquals(0.0F, pending(Float.NaN).spreadPending());
		assertEquals(0.0F, pending(-3.0F).spreadPending());
		assertEquals(0.0F, pending(Float.POSITIVE_INFINITY).spreadPending());
	}
}

