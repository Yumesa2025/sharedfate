package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 조건부 증강이 방금 발동했다는 알림. 경험치 바 위에 이름이 잠깐 떠올랐다 흐려진다.
 *
 * <p>클라이언트는 증강 풀을 읽지 않으므로 <b>이름과 등급을 함께 싣는다.</b> 세트 효과처럼
 * 증강 목록에 없는 것도 같은 줄로 띄울 수 있게 하려는 것이다.
 *
 * @param key    쿨다운을 가르는 열쇠. 보통 증강 id 이고, 같은 열쇠는 화면에 3초에 한 번까지만 뜬다
 * @param name   화면에 적을 이름(「급소만 노려」)
 * @param rarity 등급 id({@code silver / gold / prism / trial}). 테두리 색을 정한다
 */
public record PerkProcPayload(String key, String name, String rarity) implements CustomPacketPayload {
	public static final Type<PerkProcPayload> TYPE = new Type<>(SharedFateMod.id("perk_proc"));
	public static final StreamCodec<RegistryFriendlyByteBuf, PerkProcPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.stringUtf8(128), PerkProcPayload::key,
					ByteBufCodecs.stringUtf8(64), PerkProcPayload::name,
					ByteBufCodecs.stringUtf8(16), PerkProcPayload::rarity,
					PerkProcPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
