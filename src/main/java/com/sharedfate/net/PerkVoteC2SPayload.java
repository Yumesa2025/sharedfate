package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.ui.PerkVoteBoard;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * C2S — 선택자가 아닌 사람이 던지는 표.
 *
 * <p><b>클라이언트는 「내가 이걸 눌렀다」만 보낸다.</b> 켠 것인지 끈 것인지도, 지금 몇 표인지도
 * 싣지 않는다 — 세는 일은 전부 서버가 한다({@code PerkChoiceSession.castVote}). 클라이언트가
 * 센 수를 믿으면 창을 조작해 없는 표를 만들 수 있고, 두 사람이 같은 틱에 누르면 양쪽이 서로
 * 다른 수를 보내 화면끼리도 어긋난다.
 *
 * <p>같은 대상을 다시 누르면 표가 사라지고 다른 대상을 누르면 표가 옮겨 가는데, 그 판단도
 * 서버가 {@link PerkVoteBoard#toggle} 로 한다. 그래서 이 묶음에는 <b>끄기</b>를 뜻하는 값이
 * 따로 없다.
 *
 * <p>서버가 버리는 경우는 {@code PerkChoiceSession.castVote} 에 적어 두었다 — 선택창이 떠
 * 있는 단계가 아니거나, 보낸 사람이 그 팀이 아니거나, <b>보낸 사람이 선택자 본인</b>이거나,
 * 관전자이거나, 대상이 지금 후보에 없으면 조용히 무시한다.
 *
 * @param milestone 표를 던지는 선택권의 레벨 구간. 늦게 도착한 패킷이 다음 회차의 창을
 *                  건드리지 않게 하는 열쇠다
 * @param target    누른 대상. 후보 증강 id 이거나 {@link PerkVoteBoard#REROLL_TARGET}
 */
public record PerkVoteC2SPayload(int milestone, String target) implements CustomPacketPayload {

	/**
	 * 대상 문자열의 길이 상한.
	 *
	 * <p>밖에서 오는 값이라 코덱 자리에서 먼저 자른다. 증강 id 는 네임스페이스를 다 붙여도
	 * 예순 자를 넘지 않는다.
	 */
	public static final int MAX_TARGET_LENGTH = 128;

	public static final Type<PerkVoteC2SPayload> TYPE =
			new Type<>(SharedFateMod.id("perk_vote_c2s"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkVoteC2SPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, PerkVoteC2SPayload::milestone,
					ByteBufCodecs.stringUtf8(MAX_TARGET_LENGTH), PerkVoteC2SPayload::target,
					PerkVoteC2SPayload::new);

	public PerkVoteC2SPayload {
		// null 하나로 패킷 인코딩이 터지면 안 된다. 빈 대상은 서버가 어차피 버린다.
		target = target == null ? "" : target;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
