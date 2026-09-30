package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.ui.PerkVoteBoard;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 지금 표가 이렇다.
 *
 * <p><b>표는 서버가 센다.</b> 화면은 여기 실려 온 수를 그대로 그릴 뿐 스스로 더하거나 빼지
 * 않는다. 클라이언트가 세면 두 사람이 같은 순간에 누를 때 화면마다 다른 수가 뜬다.
 *
 * <p>표가 하나라도 달라질 때마다 세션에 참여 중인 팀원 <b>전원</b>에게 나간다. 선택자에게도
 * 나가는 것이 중요하다 — 이 장치는 <b>선택자에게 팀의 뜻을 보여 주려고</b> 있는 것이다.
 *
 * <p>대신 {@code ownVote} 는 받는 사람마다 다르다. 그래서 이 묶음은 사람마다 따로 만든다.
 * 「내가 어디에 표를 뒀는지」가 안 보이면 취소하려고 눌렀다가 오히려 옮기게 된다.
 *
 * <p>표가 하나도 없으면 {@code tallies} 가 빈 목록이다. 그 경우도 보내야 한다 — 마지막
 * 한 사람이 표를 거뒀을 때 아무것도 안 보내면 화면에 체크가 그대로 남는다.
 *
 * @param milestone 이 표가 붙어 있는 선택권의 레벨 구간. 화면은 자기 구간이 아닌 묶음을
 *                  버린다 — 늦게 도착한 것이 다음 회차의 창에 체크를 그리면 안 된다
 * @param tallies   표가 한 개 이상 붙은 대상만. 0표인 대상은 아예 빠진다
 * @param ownVote   <b>이 묶음을 받는 사람</b>이 표를 던져 둔 대상. 안 던졌으면 빈 문자열
 */
public record PerkVoteSyncPayload(int milestone, List<Tally> tallies, String ownVote)
		implements CustomPacketPayload {

	/**
	 * 한 묶음에 실을 수 있는 대상 수 상한.
	 *
	 * <p>후보 {@link PerkOfferPayload#MAX_OPTIONS} 개에 「다시 뽑자」 한 자리를 더한 값이다.
	 * 표를 던질 수 있는 자리가 그것뿐이므로 이보다 많아질 길이 없다.
	 */
	public static final int MAX_TALLIES = PerkOfferPayload.MAX_OPTIONS + 1;

	/**
	 * 대상 하나에 몇 표가 모였는가.
	 *
	 * @param target 후보 증강 id 이거나 {@link PerkVoteBoard#REROLL_TARGET}
	 * @param count  그 대상에 모인 표 수. 언제나 1 이상이다
	 */
	public record Tally(String target, int count) {

		public Tally {
			target = target == null ? "" : target;
			count = Math.max(0, count);
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, Tally> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH),
						Tally::target,
						ByteBufCodecs.VAR_INT, Tally::count,
						Tally::new);
	}

	public static final Type<PerkVoteSyncPayload> TYPE =
			new Type<>(SharedFateMod.id("perk_vote_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkVoteSyncPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, PerkVoteSyncPayload::milestone,
					Tally.CODEC.apply(ByteBufCodecs.list(MAX_TALLIES)), PerkVoteSyncPayload::tallies,
					ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH),
					PerkVoteSyncPayload::ownVote,
					PerkVoteSyncPayload::new);

	public PerkVoteSyncPayload {
		tallies = List.copyOf(tallies);
		ownVote = ownVote == null ? "" : ownVote;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
