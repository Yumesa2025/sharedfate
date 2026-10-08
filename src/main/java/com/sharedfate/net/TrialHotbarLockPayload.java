package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.player.Inventory;

/**
 * S2C — 「굳는 손」이 지금 굳혀 둔 <b>내 핫바 칸</b>.
 *
 * <p>시련 「굳는 손」을 가진 팀의 <b>사람마다 따로</b> 나간다. 굳은 칸은 사람마다 다르게 뽑히므로
 * 이 묶음은 받는 사람 자기 것만 담는다 — 남의 굳은 칸은 화면에 그릴 자리가 없다.
 *
 * <h2>왜 통신이 필요한가</h2>
 *
 * <p>굳은 칸은 서버가 30초마다 굴려 정한다. 클라이언트가 스스로 알아낼 근거가 하나도 없다.
 * 아이템에 표시를 붙여 보내는 길도 따져 봤지만 둘 다 막힌다 — <b>빈 칸도 굳고</b>(붙일 물건이
 * 없다), 굳은 것은 칸이라 <b>물건을 옮기면 표시가 물건을 따라가</b> 엉뚱한 칸이 붉어진다.
 * 그래서 칸 번호를 그대로 보낸다.
 *
 * <h2>왜 비트 하나인가</h2>
 *
 * <p>칸 번호 목록을 보내면 개수 칸이 붙고 코덱에 상한을 따로 두어야 한다. 핫바는 언제나
 * {@link Inventory#SELECTION_SIZE} 칸이라 <b>정수 하나의 아래 아홉 비트</b>면 모든 경우가 담기고,
 * 상한이 형식 자체에 들어 있어 악의적인 패킷이 메모리를 먹을 길이 없다.
 *
 * <h2>「그만 그려라」를 따로 보내지 않는다</h2>
 *
 * <p>{@code SwapTimerPayload} 와 같은 규칙이다. 카드가 없어지거나 전투가 끝나면 이 묶음이 그냥
 * 멎고, 받는 쪽이 마지막으로 받은 시각을 적어 두었다가 잠시 뒤 스스로 지운다
 * ({@code com.sharedfate.client.ClientHotbarLock}).
 *
 * <p>이 카드에서는 그것이 편의가 아니라 <b>안전장치</b>다. 드래곤이 죽는 틱에
 * {@code DragonTrialManager.tickSessions} 는 세션을 닫기만 하고
 * {@code TrialRisks.clearState()} 를 부르지 않는다 — 끄는 패킷에 기대면 그 길에서 붉은 표시가
 * 회차 끝까지 화면에 남는다. 멎으면 사라지는 쪽은 부를 사람이 없어도 사라진다.
 *
 * @param frozenMask 굳은 핫바 칸. 비트 {@code n} 이 켜져 있으면 {@code n} 번 칸이 굳었다
 */
public record TrialHotbarLockPayload(int frozenMask) implements CustomPacketPayload {

	/** 핫바 아홉 칸을 모두 덮는 비트. 이 밖의 비트는 뜻이 없다. */
	public static final int ALL_SLOTS = (1 << Inventory.SELECTION_SIZE) - 1;

	public static final Type<TrialHotbarLockPayload> TYPE =
			new Type<>(SharedFateMod.id("trial_hotbar_lock"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialHotbarLockPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, TrialHotbarLockPayload::frozenMask,
					TrialHotbarLockPayload::new);

	public TrialHotbarLockPayload {
		// 아홉 비트로 깎는다. 뜻 없는 비트를 그대로 실으면 화면이 없는 칸을 칠하려 들고,
		// 무엇보다 VAR_INT 에 음수가 실려 다섯 바이트를 낭비한다. 받는 쪽에서도 같은 생성자가
		// 돌므로 조작된 패킷이 여기를 지나갈 수 없다.
		frozenMask &= ALL_SLOTS;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
