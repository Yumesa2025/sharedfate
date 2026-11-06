package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * S2C — 「호위」({@code damage_ward})가 지금 한 번 막아 줄 수 있는가.
 *
 * <p>막을 수 있는 동안에만 하트 왼쪽에 작은 「호위」 표가 뜬다({@code DamageWardHud}). 클라이언트가
 * 스스로 알아낼 수 없는 값이라 서버가 보낸다 — 클라이언트는 증강 풀을 읽지 않아 쿨타임(10초, 「가호
 * 3」이면 5초)을 모르고, 보유 목록({@link PerkSyncPayload})에는 <b>누가 골랐는지</b>가 없어 「호위」를
 * 받는 사람이 자기인지도 모른다(「가호 4」면 고른 사람이 아니어도 받는다). 남은 시간도 서버의
 * {@code DamageWardTracker} 에만 있어 재접속하면 클라이언트 쪽 셈은 처음부터 틀린다.
 *
 * <p>그래서 <b>상태가 바뀔 때만</b> 보낸다({@link DamageWardBroadcaster}). 막는 순간 곧바로
 * 「준비 아님」이 가고, 쿨타임이 차면 「준비」가 간다.
 *
 * <p>새 묶음이라 규약 번호({@code SharedFateNetworking.PROTOCOL_VERSION})는 그대로다. 서버는
 * {@code canSend} 를 먼저 물으므로 이 묶음을 모르는 옛 클라이언트에게는 보내지 않고, 이 묶음을 아는
 * 클라이언트가 옛 서버에 붙으면 아무것도 오지 않아 표가 안 뜰 뿐이다.
 *
 * @param ready  지금 한 번 막을 수 있는가. 거짓이면 표를 감춘다
 * @param name   표에 적을 이름(「호위」). {@code ready} 가 거짓이면 빈 문자열
 * @param rarity 등급 id. 테두리 색을 정한다. {@code ready} 가 거짓이면 빈 문자열
 */
public record DamageWardPayload(boolean ready, String name, String rarity)
		implements CustomPacketPayload {
	/** 이름 칸의 길이 한도. {@link PerkProcPayload} 와 같다. */
	public static final int MAX_NAME_LENGTH = 64;
	/** 등급 칸의 길이 한도. {@link PerkProcPayload} 와 같다. */
	public static final int MAX_RARITY_LENGTH = 16;

	/** 표를 감추라는 값. 「호위」가 없거나 쿨타임 중일 때 보낸다. */
	public static final DamageWardPayload HIDDEN = new DamageWardPayload(false, "", "");

	public static final Type<DamageWardPayload> TYPE = new Type<>(SharedFateMod.id("damage_ward"));
	public static final StreamCodec<RegistryFriendlyByteBuf, DamageWardPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.BOOL, DamageWardPayload::ready,
					ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH), DamageWardPayload::name,
					ByteBufCodecs.stringUtf8(MAX_RARITY_LENGTH), DamageWardPayload::rarity,
					DamageWardPayload::new);

	public DamageWardPayload {
		// 한도를 넘는 문자열은 인코딩에서 터진다. 이름 하나 때문에 접속이 끊기면 안 된다.
		name = clip(name, MAX_NAME_LENGTH);
		rarity = clip(rarity, MAX_RARITY_LENGTH);
	}

	/** 막을 수 있다는 값. */
	public static DamageWardPayload ready(String name, String rarity) {
		return new DamageWardPayload(true, name, rarity);
	}

	private static String clip(String text, int max) {
		if (text == null) {
			return "";
		}
		return text.length() <= max ? text : text.substring(0, max);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
