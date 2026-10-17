package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.HideHudEffect;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 클라이언트 기능 패킷이 그대로 왕복하는지, 그리고 "달라졌을 때만 다시 보낸다"는 판단의
 * 근거인 {@code equals} 가 믿을 만한지 본다.
 *
 * <p>이 패킷은 서버가 매 점검마다 다시 만들어 지난번 것과 비교한다. 같은 내용인데 다르게
 * 나오면 초당 두 번씩 쓸데없이 나가고, 다른 내용인데 같게 나오면 증강을 얻거나 잃은 것이
 * 클라이언트에 영영 전해지지 않는다.
 */
class PerkClientFeaturesPayloadTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static PerkClientFeaturesPayload roundTrip(PerkClientFeaturesPayload payload) {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		PerkClientFeaturesPayload.CODEC.encode(buffer, payload);
		return PerkClientFeaturesPayload.CODEC.decode(buffer);
	}

	@Test
	void 아무것도_안_켜진_상태가_기본이다() {
		assertFalse(PerkClientFeaturesPayload.NONE.doubleJump());
		assertEquals(0.0, PerkClientFeaturesPayload.NONE.doubleJumpPower());
		assertTrue(PerkClientFeaturesPayload.NONE.hiddenHudElements().isEmpty());
		assertTrue(PerkClientFeaturesPayload.NONE.hidden().isEmpty());
		assertEquals(20, PerkClientFeaturesPayload.NONE.foodCapacity(), "증강이 없으면 바닐라 허기 상한");
	}

	@Test
	void 허기_상한이_직렬화를_그대로_통과한다() {
		PerkClientFeaturesPayload decoded = roundTrip(PerkClientFeaturesPayload.of(
				false, 0.0, EnumSet.noneOf(HideHudEffect.Element.class), 100));

		assertEquals(100, decoded.foodCapacity());
	}

	@Test
	void 허기_상한이_달라지면_다르다고_나온다() {
		// 「먹방」을 얻거나 잃은 것이 전해지지 않으면 배가 부를 때 먹는 동작이 막히거나 열린 채 남는다.
		assertNotEquals(PerkClientFeaturesPayload.NONE, PerkClientFeaturesPayload.of(
				false, 0.0, EnumSet.noneOf(HideHudEffect.Element.class), 100));
	}

	@Test
	void 이상한_허기_상한은_뜻_있는_범위로_맞춘다() {
		assertEquals(20, new PerkClientFeaturesPayload(false, 0.0, List.of(), -5).foodCapacity());
		assertEquals(100, new PerkClientFeaturesPayload(false, 0.0, List.of(), 9999).foodCapacity());
	}

	@Test
	void 세기와_가림이_직렬화를_그대로_통과한다() {
		PerkClientFeaturesPayload decoded = roundTrip(PerkClientFeaturesPayload.of(
				true, 0.42, EnumSet.of(HideHudEffect.Element.HEALTH, HideHudEffect.Element.FOOD)));

		assertTrue(decoded.doubleJump());
		assertEquals(0.42, decoded.doubleJumpPower());
		assertEquals(List.of("health", "food"), decoded.hiddenHudElements());
		assertEquals(EnumSet.of(HideHudEffect.Element.HEALTH, HideHudEffect.Element.FOOD),
				decoded.hidden());
	}

	@Test
	void 빈_상태도_왕복한다() {
		assertEquals(PerkClientFeaturesPayload.NONE, roundTrip(PerkClientFeaturesPayload.NONE));
	}

	@Test
	void 칸_차례는_넣은_순서가_아니라_선언_순서다() {
		// LinkedHashSet 처럼 넣은 차례를 지키는 묶음이 들어와도 결과가 흔들리면 안 된다.
		Set<HideHudEffect.Element> 거꾸로 = new java.util.LinkedHashSet<>();
		거꾸로.add(HideHudEffect.Element.AIR);
		거꾸로.add(HideHudEffect.Element.HEALTH);

		assertEquals(List.of("health", "air"),
				PerkClientFeaturesPayload.of(false, 0.0, 거꾸로).hiddenHudElements());
	}

	@Test
	void 같은_내용이면_같다고_나온다() {
		assertEquals(
				PerkClientFeaturesPayload.of(true, 0.42, EnumSet.of(HideHudEffect.Element.HEALTH)),
				PerkClientFeaturesPayload.of(true, 0.42, EnumSet.of(HideHudEffect.Element.HEALTH)));
	}

	@Test
	void 달라지면_다르다고_나온다() {
		PerkClientFeaturesPayload 기준 =
				PerkClientFeaturesPayload.of(true, 0.42, EnumSet.of(HideHudEffect.Element.HEALTH));

		assertNotEquals(기준,
				PerkClientFeaturesPayload.of(true, 0.8, EnumSet.of(HideHudEffect.Element.HEALTH)));
		assertNotEquals(기준,
				PerkClientFeaturesPayload.of(false, 0.42, EnumSet.of(HideHudEffect.Element.HEALTH)));
		assertNotEquals(기준, PerkClientFeaturesPayload.of(true, 0.42,
				EnumSet.of(HideHudEffect.Element.HEALTH, HideHudEffect.Element.FOOD)));
	}

	@Test
	void 공중_점프가_꺼져_있으면_세기도_0_으로_눕힌다() {
		// 꺼진 상태의 세기가 제각각이면 내용이 같은데도 다르다고 나와 패킷이 계속 나간다.
		assertEquals(PerkClientFeaturesPayload.NONE,
				PerkClientFeaturesPayload.of(false, 0.42,
						EnumSet.noneOf(HideHudEffect.Element.class)));
	}

	@Test
	void 모르는_칸_이름이_섞여_와도_아는_것만_읽는다() {
		// 새 서버가 늘린 이름이 옛 클라이언트에 닿는 경우다. 패킷을 버리지 않고 아는 것만 쓴다.
		PerkClientFeaturesPayload payload =
				new PerkClientFeaturesPayload(false, 0.0, List.of("health", "경험치"), 20);

		assertEquals(EnumSet.of(HideHudEffect.Element.HEALTH), payload.hidden());
	}

	@Test
	void 공중_점프_요청은_내용이_없다() {
		// 세기를 클라이언트가 실어 보내면 그게 곧 치트 통로가 된다.
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		DoubleJumpPayload.CODEC.encode(buffer, DoubleJumpPayload.INSTANCE);

		assertEquals(0, buffer.readableBytes(), "한 바이트도 실리지 않는다");
		assertEquals(DoubleJumpPayload.INSTANCE, DoubleJumpPayload.CODEC.decode(buffer));
	}
}
