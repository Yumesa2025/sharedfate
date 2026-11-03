package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.DragonPrepVote;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 「드래곤전 대비」 팀 투표 창의 지금 상태 전부(2026-11-04).
 *
 * <p>일반 증강은 뽑기 연출({@link PerkDrawPayload}) · 선택창({@link PerkOfferPayload}) · 표
 * ({@link PerkVoteSyncPayload}) · 결과({@link PerkResultPayload}) 넷을 차례로 보낸다. 투표 창은 그 넷을
 * <b>한 묶음</b>으로 보낸다 — 단계가 넷이고 늦게 들어온 사람에게도 같은 화면을 세워 줘야 하는데, 묶음이
 * 갈리면 「선택창이 떠 있어야 결과가 먹힌다」 같은 차례 규칙이 또 생긴다. 단계 · 남은 시간 · 표 · 결과
 * 중 하나라도 바뀌면 통째로 다시 보낸다(많아야 팀원 수만큼의 이름이라 값이 없다).
 *
 * <p>화면은 남은 시간을 <b>받은 순간부터 스스로 센다.</b> 서버는 매 틱 보내지 않고, 5초로 줄었을 때처럼
 * 시계가 실제로 바뀐 때만 보낸다.
 *
 * <p>⚠ 통신 규약은 41 그대로다 — 이 판(아직 공개 전)에 이미 41 로 올라가 있어 새 묶음을 더해도 올리지
 * 않는다({@code SharedFateNetworking.PROTOCOL_VERSION} 의 41 줄).
 *
 * @param milestone  이 투표의 레벨 구간(45·50). 화면은 자기 구간이 아닌 묶음을 버린다
 * @param stage      {@link DragonPrepVote.Stage} 의 차례
 * @param stageTicks 지금 단계가 끝나기까지 남은 틱
 * @param stageTotal 지금 단계의 전체 길이. 남은 시간 막대의 분모다
 * @param shortened  접속한 전원이 던져 5초로 줄었는가
 * @param options    두 후보. {@link PerkOfferPayload.PerkOption} 을 그대로 쓴다
 * @param ballots    누가 어느 카드에 던졌는가. 카드 아래 이름 줄이다
 * @param ownVote    <b>이 묶음을 받는 사람</b>이 던진 카드. 안 던졌으면 빈 문자열
 * @param canVote    받는 사람이 표를 던질 수 있는가. 관전자는 거짓이다
 * @param winner     뽑힌 카드. 룰렛이 돌기 시작하기 전에는 빈 문자열
 * @param byRoulette 동점이라 룰렛으로 정했는가
 */
public record DragonPrepVotePayload(int milestone, int stage, int stageTicks, int stageTotal,
		boolean shortened, List<PerkOfferPayload.PerkOption> options, List<Ballot> ballots,
		String ownVote, boolean canVote, String winner, boolean byRoulette)
		implements CustomPacketPayload {

	/** 한 묶음에 실을 수 있는 표 수 상한. 한 팀의 최대 인원이다({@link PerkDrawPayload#MAX_NAMES}). */
	public static final int MAX_BALLOTS = PerkDrawPayload.MAX_NAMES;

	/**
	 * 한 사람의 표.
	 *
	 * @param name   던진 사람 이름
	 * @param option 던진 카드의 증강 id
	 */
	public record Ballot(String name, String option) {

		public Ballot {
			name = name == null ? "" : name;
			option = option == null ? "" : option;
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, Ballot> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.stringUtf8(64), Ballot::name,
						ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH), Ballot::option,
						Ballot::new);
	}

	public static final Type<DragonPrepVotePayload> TYPE =
			new Type<>(SharedFateMod.id("dragon_prep_vote"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DragonPrepVotePayload> CODEC =
			StreamCodec.of(DragonPrepVotePayload::write, DragonPrepVotePayload::read);

	public DragonPrepVotePayload {
		options = List.copyOf(options.size() > PerkOfferPayload.MAX_OPTIONS
				? options.subList(0, PerkOfferPayload.MAX_OPTIONS) : options);
		ballots = List.copyOf(ballots.size() > MAX_BALLOTS ? ballots.subList(0, MAX_BALLOTS) : ballots);
		// 음수는 실을 수 없는 코덱이라 여기서 막는다. 서버 계산이 어긋나도 패킷이 터지면 안 된다.
		stage = Math.max(0, stage);
		stageTicks = Math.max(0, stageTicks);
		stageTotal = Math.max(1, stageTotal);
		ownVote = ownVote == null ? "" : ownVote;
		winner = winner == null ? "" : winner;
	}

	/** 단계. */
	public DragonPrepVote.Stage stageValue() {
		return DragonPrepVote.Stage.byId(stage);
	}

	/** 이 카드에 던진 사람 이름들. 던진 차례 그대로다. */
	public List<String> votersOf(String option) {
		return ballots.stream().filter(ballot -> ballot.option().equals(option))
				.map(Ballot::name).toList();
	}

	// 칸이 열 하나라 composite(최대 여덟 칸)에 안 들어간다. 손으로 같은 차례로 읽고 쓴다.
	private static void write(RegistryFriendlyByteBuf buf, DragonPrepVotePayload payload) {
		ByteBufCodecs.VAR_INT.encode(buf, payload.milestone);
		ByteBufCodecs.VAR_INT.encode(buf, payload.stage);
		ByteBufCodecs.VAR_INT.encode(buf, payload.stageTicks);
		ByteBufCodecs.VAR_INT.encode(buf, payload.stageTotal);
		ByteBufCodecs.BOOL.encode(buf, payload.shortened);
		PerkOfferPayload.PerkOption.CODEC.apply(ByteBufCodecs.list(PerkOfferPayload.MAX_OPTIONS))
				.encode(buf, payload.options);
		Ballot.CODEC.apply(ByteBufCodecs.list(MAX_BALLOTS)).encode(buf, payload.ballots);
		ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH).encode(buf, payload.ownVote);
		ByteBufCodecs.BOOL.encode(buf, payload.canVote);
		ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH).encode(buf, payload.winner);
		ByteBufCodecs.BOOL.encode(buf, payload.byRoulette);
	}

	private static DragonPrepVotePayload read(RegistryFriendlyByteBuf buf) {
		int milestone = ByteBufCodecs.VAR_INT.decode(buf);
		int stage = ByteBufCodecs.VAR_INT.decode(buf);
		int stageTicks = ByteBufCodecs.VAR_INT.decode(buf);
		int stageTotal = ByteBufCodecs.VAR_INT.decode(buf);
		boolean shortened = ByteBufCodecs.BOOL.decode(buf);
		List<PerkOfferPayload.PerkOption> options = PerkOfferPayload.PerkOption.CODEC
				.apply(ByteBufCodecs.list(PerkOfferPayload.MAX_OPTIONS)).decode(buf);
		List<Ballot> ballots = Ballot.CODEC.apply(ByteBufCodecs.list(MAX_BALLOTS)).decode(buf);
		String ownVote = ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH).decode(buf);
		boolean canVote = ByteBufCodecs.BOOL.decode(buf);
		String winner = ByteBufCodecs.stringUtf8(PerkVoteC2SPayload.MAX_TARGET_LENGTH).decode(buf);
		boolean byRoulette = ByteBufCodecs.BOOL.decode(buf);
		return new DragonPrepVotePayload(milestone, stage, stageTicks, stageTotal, shortened, options,
				ballots, ownVote, canVote, winner, byRoulette);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
