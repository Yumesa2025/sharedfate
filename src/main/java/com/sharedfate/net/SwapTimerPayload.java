package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C — 다음 위치 교환까지 남은 초.
 *
 * <p>골드 「폭발 교환」을 가진 팀에게만, <b>1초에 한 번</b> 나간다. 화면 왼쪽 위 세트 줄 바로
 * 위에 한 줄로 그려지는 것이 이 증강의 혜택이고, 자리를 비울 때 터지는 폭발이 대가다.
 *
 * <h2>왜 남은 시간을 그대로 싣는가</h2>
 * <p>세트 줄의 「보급」 시계는 주기와 기준 시각만 싣고 클라이언트가 스스로 센다
 * ({@code com.sharedfate.ui.SupplyCountdown}). 그렇게 하는 이유는 이름표 백 몇 줄이 초마다
 * 함께 재전송되기 때문인데, 이 묶음은 <b>정수 하나</b>뿐이라 그 걱정이 없다.
 *
 * <p>게다가 위치 교환의 남은 시간은 게임 시간과 나란히 흐르지 않는다. 증강 선택 창이 떠
 * 있는 동안, 게임 오버 카운트다운 동안 주기가 멈춘다. 기준
 * 시각만 보내면 그 멈춤이 화면에 반영되지 않아 시계가 실제와 어긋난다.
 *
 * <h2>「그만 그려라」를 따로 보내지 않는다</h2>
 * <p>증강을 잃거나 팀을 나가면 이 묶음이 그냥 멎는다. 받는 쪽이 <b>마지막으로 받은 시각</b>을
 * 함께 적어 두고 잠시 뒤 스스로 지운다({@code ClientSwapTimer}). 끄는 패킷을 따로 두면
 * 「보낼 조건」과 「끌 조건」이 갈라져 한쪽만 고쳐지는 사고가 난다.
 *
 * @param remainingSeconds 다음 교환까지 남은 초. 0 이상
 */
public record SwapTimerPayload(int remainingSeconds) implements CustomPacketPayload {
	public static final Type<SwapTimerPayload> TYPE = new Type<>(SharedFateMod.id("swap_timer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SwapTimerPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, SwapTimerPayload::remainingSeconds,
					SwapTimerPayload::new);

	public SwapTimerPayload {
		// VAR_INT 는 음수를 담기에 낭비가 크다. 보낼 일도 없는 값이라 여기서 접는다.
		remainingSeconds = Math.max(0, remainingSeconds);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
