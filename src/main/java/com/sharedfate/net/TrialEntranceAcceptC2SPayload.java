package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S — 입장 수락창의 <b>「확인」</b>을 눌렀다.
 *
 * <h2>싣는 것은 「눌렀다」와 「어느 창에서」뿐이다</h2>
 *
 * <p>「내가 리더다」를 싣지 않는다. 그것은 <b>밖에서 오는 값</b>이고, 실어 보내면 그 한 칸을
 * 거짓으로 채운 패킷이 남의 전투를 연다. 누를 수 있는 사람인지는 서버가 팀 명단에서 다시 본다
 * ({@code TrialEntranceGate.accept}) — 증강 쪽 제안 패킷
 * ({@code PerkVoteC2SPayload})이 「세는 일은 전부 서버가 한다」로 같은 선을 그어 두었다.
 *
 * <p>⚠ 화면이 단추를 안 그려 주는 것은 <b>안내</b>일 뿐 자물쇠가 아니다. 패킷은 화면 없이도
 * 보낼 수 있다.
 *
 * @param openedTick 창이 {@code TrialEntranceOfferPayload} 로 받은 그 시각을 되돌려 보낸다.
 *                   서버는 지금 열려 있는 창의 시각과 다르면 버린다 — <b>지난 창의 늦은 확인이
 *                   다음 창을 그 자리에서 눌러 버리는 것</b>을 막는 유일한 장치다. 전투가 끝나고
 *                   다시 엔드에 들어가면 창이 또 열리므로 실제로 일어날 수 있는 일이다
 */
public record TrialEntranceAcceptC2SPayload(long openedTick) implements CustomPacketPayload {

	public static final Type<TrialEntranceAcceptC2SPayload> TYPE =
			new Type<>(SharedFateMod.id("trial_entrance_accept"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialEntranceAcceptC2SPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG, TrialEntranceAcceptC2SPayload::openedTick,
					TrialEntranceAcceptC2SPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
