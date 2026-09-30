package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * S2C — 증강 후보 제시.
 *
 * <p>보내는 경로는 두 가지다.
 *
 * <ul>
 *   <li><b>강제 오픈</b> — 레벨 구간에 도달하면 서버가 시간을 멈추고 팀 전원에게 스스로 보낸다.
 *       {@code forced} 가 {@code true} 이고 {@code remainingTicks} 에 마감까지 남은 틱이 담긴다.
 *       클라이언트는 ESC 를 막고 카운트다운을 띄운다.</li>
 *   <li><b>직접 열기</b> — {@code /shareteam perk} 실행에 응답해 보낸다. 시간도 멈추지 않고
 *       무적도 걸리지 않는 단순 확인용이라 {@code forced} 는 {@code false},
 *       {@code remainingTicks} 는 {@link #NO_DEADLINE} 이다.</li>
 * </ul>
 *
 * @param milestone         이 선택권을 만든 레벨 구간 (5, 10, …, 35)
 * @param canChoose         실제로 고를 수 있는지. false면 다른 팀원이 고르는 걸 지켜보는 관전 모드
 * @param forced            서버가 강제로 띄운 창인지. true면 ESC 로 닫을 수 없고 카운트다운이 보인다
 * @param remainingTicks    마감까지 남은 틱. 마감이 없으면 {@link #NO_DEADLINE}
 * @param rerollsRemaining  이번 회차에 남은 다시 뽑기 횟수. 0 이면 단추가 잠긴다.
 *                          <b>화면에 적기 위한 값일 뿐</b>이고, 실제로 쓸 수 있는지는 서버가
 *                          요청을 받을 때 다시 센다
 * @param options           제시된 후보. 최대 {@link #MAX_OPTIONS}개이며, 풀이 모자라면 더 적을 수 있다
 */
public record PerkOfferPayload(int milestone, boolean canChoose, boolean forced,
		int remainingTicks, int rerollsRemaining, List<PerkOption> options)
		implements CustomPacketPayload {

	/** 한 번에 제시할 수 있는 후보 수 상한. */
	public static final int MAX_OPTIONS = 3;

	/** 마감이 없는 창({@code /shareteam perk} 로 직접 연 경우)의 {@code remainingTicks} 값. */
	public static final int NO_DEADLINE = -1;

	/**
	 * 화면에 그릴 후보 하나. 서버가 이미 표시용 문자열로 풀어서 보내므로
	 * 클라이언트는 증강 정의를 알 필요가 없다.
	 *
	 * @param id          증강 식별자. 선택 전송 시 그대로 되돌려 보낸다
	 * @param name        화면에 보이는 이름
	 * @param description 화면에 보이는 설명
	 * @param rarity      등급 문자열 ({@code common} / {@code rare} / {@code epic})
	 * @param icon        카드에 그릴 아이템 이름 (예: {@code minecraft:feather}).
	 *                    빈 문자열이면 클라이언트가 등급별 기본 아이콘을 쓴다
	 * @param setTypes    이 증강이 속한 세트 유형을 <b>이미 이어 붙인 한 줄</b>
	 *                    (예: {@code 무기·화력}). 어느 유형에도 안 들어가면 빈 문자열
	 * @param setTypeIds  같은 유형들의 <b>id</b>를 같은 차례로 이은 줄(예: {@code weapon·power}).
	 *                    화면이 툴팁을 찾을 때 쓴다 — 사람이 읽는 이름으로 찾으면 세트 동기화가
	 *                    늦은 순간에 툴팁이 통째로 사라진다
	 */
	public record PerkOption(String id, String name, String description, String rarity,
			String icon, String setTypes, String setTypeIds) {

		/** 여러 유형을 한 줄로 이을 때 쓰는 가운뎃점. 서버와 화면이 같은 것을 써야 한다. */
		public static final String SET_TYPE_JOINER = "·";

		public PerkOption {
			// 아이콘은 없어도 되는 값이라 서버 쪽 null 하나로 패킷 인코딩이 터지면 안 된다.
			icon = icon == null ? "" : icon;
			// 유형도 마찬가지다. 무유형 증강이 열한 개나 되므로 빈 값이 정상이다.
			setTypes = setTypes == null ? "" : setTypes;
			setTypeIds = setTypeIds == null ? "" : setTypeIds;
		}

		/**
		 * 화면용 이름만 아는 쪽을 위한 생성자. <b>툴팁이 안 뜬다.</b>
		 *
		 * <p>id 없이 이름만 보내면 클라이언트가 「채굴」을 {@code mining} 으로 되짚어야 하는데,
		 * 세트 동기화 패킷이 아직 안 온 순간에는 되짚을 표가 없어 툴팁이 통째로 사라진다.
		 * 그래서 실제 송신 경로({@code PerkManager.describeOptions})는 일곱 자리를 쓴다.
		 */
		public PerkOption(String id, String name, String description, String rarity, String icon,
				String setTypes) {
			this(id, name, description, rarity, icon, setTypes, "");
		}

		/** 화면에 적을 유형 줄이 있는가. */
		public boolean hasSetTypes() {
			return !setTypes.isEmpty();
		}

		public static final StreamCodec<RegistryFriendlyByteBuf, PerkOption> CODEC =
				StreamCodec.composite(
						ByteBufCodecs.STRING_UTF8, PerkOption::id,
						ByteBufCodecs.STRING_UTF8, PerkOption::name,
						ByteBufCodecs.STRING_UTF8, PerkOption::description,
						ByteBufCodecs.STRING_UTF8, PerkOption::rarity,
						ByteBufCodecs.STRING_UTF8, PerkOption::icon,
						ByteBufCodecs.STRING_UTF8, PerkOption::setTypes,
						ByteBufCodecs.STRING_UTF8, PerkOption::setTypeIds,
						PerkOption::new);
	}

	public static final Type<PerkOfferPayload> TYPE = new Type<>(SharedFateMod.id("perk_offer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkOfferPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, PerkOfferPayload::milestone,
					ByteBufCodecs.BOOL, PerkOfferPayload::canChoose,
					ByteBufCodecs.BOOL, PerkOfferPayload::forced,
					// 남은 틱은 마감이 없을 때 -1 이라 음수를 실을 수 있는 코덱이어야 한다.
					ByteBufCodecs.INT, PerkOfferPayload::remainingTicks,
					ByteBufCodecs.VAR_INT, PerkOfferPayload::rerollsRemaining,
					PerkOption.CODEC.apply(ByteBufCodecs.list(MAX_OPTIONS)), PerkOfferPayload::options,
					PerkOfferPayload::new);

	public PerkOfferPayload {
		options = List.copyOf(options);
		// 음수는 실을 수 없는 코덱이라 여기서 막는다. 서버 계산이 어긋나도 패킷이 터지면 안 된다.
		rerollsRemaining = Math.max(0, rerollsRemaining);
	}

	/**
	 * {@code /shareteam perk} 로 직접 연, 마감 없는 창.
	 *
	 * <p>다시 뽑기는 <b>강제 선택 세션 안에서만</b> 뜻이 있다. 시간이 흐르는 채로 후보를 갈아
	 * 끼우면 제한시간도 무적도 걸려 있지 않은 상태에서 회차의 규칙이 바뀐다. 그래서 이 경로로
	 * 연 창은 남은 횟수를 0 으로 받아 단추가 아예 뜨지 않는다.
	 */
	public static PerkOfferPayload manual(int milestone, boolean canChoose, List<PerkOption> options) {
		return new PerkOfferPayload(milestone, canChoose, false, NO_DEADLINE, 0, options);
	}

	/** 마감이 걸려 있는 강제 오픈인지. */
	public boolean hasDeadline() {
		return forced && remainingTicks >= 0;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
