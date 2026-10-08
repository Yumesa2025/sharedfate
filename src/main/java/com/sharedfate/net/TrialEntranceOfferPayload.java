package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C — <b>「시련을 시작합니다」 입장 수락창.</b>
 *
 * <h2>사람이 정한 것</h2>
 *
 * <p>「첫 엔더로 입장하면 한명이라도 엔더에 입장하면 정지하고 (시련중이면) 시련을 시작합니다. 라고
 * 수락창이 뜨고 리더가 확인 을 누루면 버튼은 확인만있지만, 엔더로 진입 엔더 중앙으로 보내 그
 * 기반암 단상있는곳으로 … 그위치로 그러고 연출보게 하면됨」.
 *
 * <p>곧 <b>단추가 「확인」 하나뿐</b>이고 <b>누를 수 있는 사람은 리더뿐</b>이다. 취소가 없는
 * 것은 엔드 입장이 원래 돌아올 수 없는 문턱이기 때문이다
 * ({@code DragonTrialManager} 클래스 설명의 「확인을 묻지는 않는다」).
 *
 * <h2>창은 팀 전원이 보고, 누르는 것은 리더뿐이다</h2>
 *
 * <p>「누를 수 있는가」를 <b>받는 사람마다 서버가 정해서 싣는다</b>({@link #canConfirm}).
 * 클라이언트가 리더 UUID 와 자기 UUID 를 견주게 두지 않는 것은 증강 선택창
 * ({@code PerkOfferPayload.canChoose})이 이미 고른 길이고, 거기에는 까닭이 둘 있다.
 *
 * <ul>
 *   <li><b>판단이 한 곳에 있어야 한다.</b> 서버는 어차피 들어온 확인 패킷을 다시 가린다
 *       ({@code TrialEntranceGate.accept}). 클라이언트가 같은 판단을 따로 하면 두 벌이 되고,
 *       그 둘이 어긋난 날의 증상은 <b>눌리는데 아무 일도 안 일어나는 단추</b>다</li>
 *   <li>⚠ <b>26.3 에서 클라이언트와 서버가 다른 길을 지난다.</b> 이 저장소는 조합법이 추가 칸을
 *       안 세는 사고를 「{@code ServerPlayer} 일 때만 도는 한 줄」로 겪었다. 화면 쪽 판단을
 *       서버 타입이나 클라이언트가 들고 있는 팀 상태에 기대지 않는다</li>
 * </ul>
 *
 * <h2>{@link #leaderName} 은 왜 싣는가</h2>
 *
 * <p>리더가 아닌 사람의 창에 <b>「누구를 기다리는지」</b>를 적어야 한다. 그것이 없으면 네 사람이
 * 서로 상대를 기다리며 60초를 쓴다. 클라이언트는 팀 명단을 들고 있지만
 * ({@code ClientTeamState}) 리더가 접속해 있는지도, 이름이 지금 무엇인지도 서버가 더 정확히
 * 안다.
 *
 * <h2>{@link #timeoutTicks} 은 왜 싣는가</h2>
 *
 * <p>화면이 <b>스스로 닫을 수 있어야 한다.</b> ESC 를 막아 둔 창이라 서버의 닫기 지시가 유실되면
 * 플레이어가 영영 갇힌다 — {@code TrialRouletteScreen} 이 같은 까닭으로 타이머를 직접 든다.
 * 서버가 실제로 기다리는 길이를 그대로 실어 보내므로 화면과 서버의 시계가 어긋날 수 없다
 * ({@code TrialRoulettePayload.holdTicks} 와 같은 판단이다).
 *
 * <p>⚠ 화면이 닫히는 것과 수락이 <b>무른다</b>는 것은 다르다. 시간이 다 되면 서버는
 * <b>수락한 것과 똑같이</b> 진행한다({@code TrialEntranceGate} 의 「교착을 만들지 않는다」) —
 * 취소가 없는 창이라 기다림이 끝나는 길도 하나뿐이다.
 *
 * @param openedTick  이 창이 열린 게임 시각. 확인 패킷이 <b>이 값을 되돌려 보내야</b> 받아들여
 *                    진다. 지난 창의 늦은 확인이 다음 창을 그 자리에서 눌러 버리는 것을 막는다
 * @param teamName    팀 이름. 창에 적어 「내 팀의 전투가 열린다」가 읽히게 한다
 * @param leaderName  리더 이름. 리더가 아닌 사람의 창에 「누구를 기다리는가」로 뜬다
 * @param canConfirm  <b>이 패킷을 받는 사람이</b> 확인을 누를 수 있는가. 서버가 정한다
 * @param timeoutTicks 서버가 기다리는 전체 길이(틱). 화면이 이것으로 남은 시간을 그리고 스스로
 *                     닫는다
 */
public record TrialEntranceOfferPayload(long openedTick, String teamName, String leaderName,
		boolean canConfirm, int timeoutTicks) implements CustomPacketPayload {

	/**
	 * 이름 한 칸의 길이 상한.
	 *
	 * <p>팀 이름은 사람이 적는 값이고 플레이어 이름은 서버가 주는 값이다. 코덱에 상한이 없으면
	 * <b>악의적인 패킷이 메모리를 먹는 길</b>이 열린다 — {@code PerkVoteC2SPayload} 가 같은
	 * 까닭으로 상한을 둔다.
	 */
	public static final int MAX_NAME_LENGTH = 64;

	public static final Type<TrialEntranceOfferPayload> TYPE =
			new Type<>(SharedFateMod.id("trial_entrance_offer"));

	public static final StreamCodec<RegistryFriendlyByteBuf, TrialEntranceOfferPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG, TrialEntranceOfferPayload::openedTick,
					ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), TrialEntranceOfferPayload::teamName,
					ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), TrialEntranceOfferPayload::leaderName,
					ByteBufCodecs.BOOL, TrialEntranceOfferPayload::canConfirm,
					ByteBufCodecs.VAR_INT, TrialEntranceOfferPayload::timeoutTicks,
					TrialEntranceOfferPayload::new);

	public TrialEntranceOfferPayload {
		// null 하나로 패킷 인코딩이 통째로 터진다. 전투가 열리는 자리라 그 사고는 곧 「엔드에
		// 들어갔는데 아무 일도 안 일어난다」다.
		teamName = clip(teamName);
		leaderName = clip(leaderName);
		// VAR_INT 는 음수를 실을 수 없다. 서버 계산이 어긋나도 패킷이 터지면 안 된다.
		timeoutTicks = Math.max(0, timeoutTicks);
	}

	/**
	 * 이름을 상한 안으로 자른다.
	 *
	 * <p>⚠ <b>길이를 글자 수로 센다.</b> {@code ByteBufCodecs.stringUtf8} 의 상한도 글자 수지만
	 * 실제로 쓰는 바이트는 한글 한 자가 셋이다 — 자르는 자리를 바이트로 세면 <b>한글 한 자가
	 * 반으로 쪼개져</b> 깨진 글자가 화면에 뜬다.
	 *
	 * <p>⚠ 자르는 자리가 <b>대리 쌍 가운데</b>일 수 있다(한글은 BMP 라 걸리지 않지만 팀 이름에
	 * 그림글자를 쓰면 걸린다). 그때는 한 칸 앞에서 자른다 — 반쪽만 남은 대리 문자는 그 자체로
	 * 올바르지 않은 문자열이다.
	 */
	private static String clip(String raw) {
		if (raw == null) {
			return "";
		}
		if (raw.length() <= MAX_NAME_LENGTH) {
			return raw;
		}
		int cut = Character.isHighSurrogate(raw.charAt(MAX_NAME_LENGTH - 1))
				? MAX_NAME_LENGTH - 1 : MAX_NAME_LENGTH;
		return raw.substring(0, cut);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
