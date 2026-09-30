package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.RuinSurveyEffect;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 팀 보유 증강 동기화. 클라이언트 HUD와 화면 표시용이다.
 *
 * <p>중첩이 없으므로 한 증강은 한 줄이다.
 *
 * @param owned        보유 증강. 없으면 빈 목록
 * @param pendingCount 아직 고르지 않은 선택권 수
 * @param chooserName  지금 고를 차례인 팀원 이름. 선택자가 없거나 미정이면 빈 문자열
 * @param ruinCoords   「유적 감별사」가 찾아 둔 좌표 줄들. 그 증강이 없으면 빈 목록.
 *                     값은 {@code PerkRuinSurvey.lines} 가 낸 것 그대로다 —
 *                     「고대 도시  -1234, 567」처럼 이름표와 x·z 가 한 줄에 들어 있다.
 *                     <b>보유 증강 하나에 딸린 값이 아니라 팀 하나에 딸린 값이다</b> —
 *                     유적 좌표 효과는 팀 전체에 하나뿐이라({@code PerkRuinSurvey.effectOf})
 *                     {@link Owned} 안에 넣으면 같은 목록이 증강마다 되풀이된다
 */
public record PerkSyncPayload(List<Owned> owned, int pendingCount, String chooserName,
		List<String> ruinCoords) implements CustomPacketPayload {

	/**
	 * 보유 증강 하나.
	 *
	 * @param name        증강 이름
	 * @param description 고를 때 보여 준 설명 그대로
	 * @param rarity      등급 이름. 화면에서 색을 고르는 데 쓴다
	 * @param setTypes    이 증강이 속한 세트 유형 이름들을
	 *                    {@link PerkOfferPayload.PerkOption#SET_TYPE_JOINER} 로 이은 것.
	 *                    어디에도 안 들어가는 증강이 열두 개라 <b>빈 문자열이 정상</b>이다
	 */
	public record Owned(String name, String description, String rarity, String setTypes) {
		public static final StreamCodec<RegistryFriendlyByteBuf, Owned> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, Owned::name,
						ByteBufCodecs.STRING_UTF8, Owned::description,
						ByteBufCodecs.STRING_UTF8, Owned::rarity,
						ByteBufCodecs.STRING_UTF8, Owned::setTypes,
						Owned::new);

		public Owned {
			// 무유형 증강이 열두 개다. 서버 쪽 null 하나로 패킷 인코딩이 터지면 안 된다.
			setTypes = setTypes == null ? "" : setTypes;
		}

		/** 이 증강이 어느 세트에도 안 들어가는가. */
		public boolean hasSetTypes() {
			return !setTypes.isEmpty();
		}
	}

	/** 한 패킷에 담을 수 있는 보유 증강 수 상한. */
	public static final int MAX_OWNED = 64;

	/**
	 * 한 패킷에 담을 수 있는 유적 좌표 줄 수 상한.
	 *
	 * <p>정의가 적을 수 있는 구조물 수와 <b>같은 값이어야 한다</b>. 여기가 더 작으면 정의상
	 * 정당한 네 줄짜리 증강이 패킷 인코딩에서 터지고, 더 크면 상한이 두 곳에 갈려 남는다.
	 */
	public static final int MAX_RUIN_COORDS = RuinSurveyEffect.MAX_STRUCTURES;

	/** 증강이 하나도 없고 대기 중인 선택권도 없는 상태. */
	public static final PerkSyncPayload EMPTY =
			new PerkSyncPayload(List.of(), 0, "", List.of());

	public static final Type<PerkSyncPayload> TYPE = new Type<>(SharedFateMod.id("perk_sync"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkSyncPayload> CODEC =
			StreamCodec.composite(
					Owned.CODEC.apply(ByteBufCodecs.list(MAX_OWNED)), PerkSyncPayload::owned,
					ByteBufCodecs.VAR_INT, PerkSyncPayload::pendingCount,
					ByteBufCodecs.STRING_UTF8, PerkSyncPayload::chooserName,
					ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_RUIN_COORDS)),
					PerkSyncPayload::ruinCoords,
					PerkSyncPayload::new);

	public PerkSyncPayload {
		owned = List.copyOf(owned);
		// 좌표는 서버가 직접 만들어 넣으므로 null 이 올 일은 없지만, 하나가 새면 증강 동기화
		// 자체가 끊겨 화면이 통째로 빈다. 빈 목록으로 받아 넘긴다.
		ruinCoords = ruinCoords == null ? List.of() : List.copyOf(ruinCoords);
	}

	/** 고를 차례인 팀원이 정해져 있는지. */
	public boolean hasChooser() {
		return !chooserName.isEmpty();
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
