package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C — 입장 수락창을 닫는다.
 *
 * <h2>왜 닫기 패킷이 따로 있는가</h2>
 *
 * <p>{@code TrialEntranceOfferPayload} 가 실어 보낸 제한시간만으로는 모자라다. <b>리더가 1초 만에
 * 누르면 나머지 세 사람의 창은 59초를 더 떠 있게 된다</b> — 그동안 팀은 이미 단상으로 끌려가고
 * 18.5초 입장 연출이 그 창 뒤에서 돈다.
 *
 * <p>누른 사람의 창은 제 손으로 닫히지만({@code TrialEntranceScreen.confirm}) <b>남의 창을 닫는
 * 길은 서버 말고 없다.</b> 증강 선택창도 같은 모양으로 갈라 두었다
 * ({@code PerkCloseOfferPayload} — 「강제로 띄운 창은 ESC 로 닫을 수 없으므로 닫는 책임이
 * 서버에 있다」).
 *
 * <p>그래도 제한시간을 함께 싣는 것은 <b>이 패킷이 유실될 수 있기 때문</b>이다. 둘 가운데 하나만
 * 믿으면 ① 서버만 믿으면 패킷 하나로 사람이 전투 한가운데 갇히고 ② 화면만 믿으면 남의 창이
 * 59초 떠 있는다. 둘을 함께 두면 <b>어느 쪽이 먼저 와도 창이 닫힌다.</b>
 *
 * @param openedTick 닫으려는 창이 열린 시각. 늦게 도착한 지시가 <b>그 사이에 열린 다음 창</b>을
 *                   닫아 버리지 않게 맞춰 본다. {@code PerkCloseOfferPayload} 가 구간 번호로
 *                   같은 일을 한다
 */
public record TrialEntranceClosePayload(long openedTick) implements CustomPacketPayload {

	public static final Type<TrialEntranceClosePayload> TYPE =
			new Type<>(SharedFateMod.id("trial_entrance_close"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialEntranceClosePayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG, TrialEntranceClosePayload::openedTick,
					TrialEntranceClosePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** 이 지시가 그 창을 겨냥한 것인가. */
	public boolean matches(long screenOpenedTick) {
		return openedTick == screenOpenedTick;
	}
}
