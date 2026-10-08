package com.sharedfate.client;

import com.sharedfate.net.TrialHotbarLockPayload;
import net.minecraft.world.entity.player.Inventory;

/**
 * 시련 「굳는 손」이 굳혀 둔 <b>내 핫바 칸</b>을 들고 있는 자리.
 *
 * <p>서버가 그 카드를 가진 사람에게만 1초에 한 번 보내 준다({@code TrialHotbarLockPayload}).
 * {@code com.sharedfate.client.hud.HotbarHighlight} 가 이 값을 읽어 굳은 칸을 붉게 칠한다.
 *
 * <h2>아무것도 스스로 정하지 않는다</h2>
 *
 * <p>어느 칸이 굳었는지도, 그 칸을 들고 무엇을 못 하는지도 전부 서버가 정한다. 여기는
 * <b>보여 주기만</b> 한다 — 이 파일을 지워도 굳은 칸은 그대로 굳어 있고 화면만 조용해진다.
 * 반대로 여기를 고쳐도 막히는 것이 달라지지 않는다.
 *
 * <h2>스스로 지운다</h2>
 *
 * <p>{@code ClientSwapTimer} 와 같은 규칙이다. 「그만 그려라」를 따로 받지 않고, 마지막으로 받은
 * 시각을 적어 두었다가 {@value #STALE_TICKS} 틱 동안 새 값이 없으면 지운다.
 *
 * <p>이 카드에서는 그것이 편의가 아니라 <b>유일한 길</b>이다. 서버 쪽
 * {@code TrialHotbarLock.clearState} 는 <b>끄는 패킷을 보내지 않는다</b> — 거기는
 * {@code SERVER_STOPPED} 에서도 불리는 자리라 보낼 연결이 남아 있다는 보장이 없다. 멎으면
 * 사라지는 쪽은 부를 사람이 없어도 사라진다.
 */
public final class ClientHotbarLock {

	/**
	 * 이만큼 새 값이 없으면 끊긴 것으로 본다. 60틱 = 3초.
	 *
	 * <p>1초에 한 번씩 오는 값이라({@code TrialHotbarLock.SYNC_INTERVAL}) 이 여유면 끊긴 것이
	 * 확실하다. 이 둘을 가깝게 붙이지 말 것 — 패킷 한 장이 늦을 때마다 붉은 칸이 깜빡인다.
	 */
	public static final long STALE_TICKS = 60L;

	private static int frozenMask;
	private static long receivedAtGameTime = Long.MIN_VALUE;

	private ClientHotbarLock() {
	}

	/**
	 * 서버에게서 받은 값을 적어 둔다.
	 *
	 * @param gameTime 받은 시각(틱). 값이 낡았는지 재는 데 쓴다
	 */
	public static void update(TrialHotbarLockPayload payload, long gameTime) {
		// 묶음 생성자가 이미 아홉 비트로 깎아 둔다. 여기서 다시 깎지 않는 것은 깎는 규칙이 두
		// 곳에 있으면 한쪽만 바뀌기 때문이다.
		frozenMask = payload.frozenMask();
		receivedAtGameTime = gameTime;
	}

	/** 서버를 나갈 때 지운다. 남겨 두면 다음 서버의 첫 화면에 남의 판 붉은 칸이 뜬다. */
	public static void clear() {
		frozenMask = 0;
		receivedAtGameTime = Long.MIN_VALUE;
	}

	/**
	 * 이 핫바 칸이 지금 굳어 있는가.
	 *
	 * @param gameTime 지금 게임 시간(틱)
	 */
	public static boolean isFrozen(int slot, long gameTime) {
		if (frozenMask == 0 || slot < 0 || slot >= Inventory.SELECTION_SIZE) {
			return false;
		}
		if (gameTime - receivedAtGameTime > STALE_TICKS) {
			return false;
		}
		return (frozenMask & (1 << slot)) != 0;
	}
}
