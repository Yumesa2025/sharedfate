package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.DragonPrepVote;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「드래곤전 대비」 투표 창 묶음이 그대로 왕복하는지(2026-11-04). */
class DragonPrepVotePayloadTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static DragonPrepVotePayload roundTrip(DragonPrepVotePayload payload) {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		DragonPrepVotePayload.CODEC.encode(buffer, payload);
		DragonPrepVotePayload decoded = DragonPrepVotePayload.CODEC.decode(buffer);
		assertEquals(0, buffer.readableBytes(), "읽고 쓰는 칸 수가 같아야 한다");
		return decoded;
	}

	private static List<PerkOfferPayload.PerkOption> options() {
		return List.of(
				new PerkOfferPayload.PerkOption("sharedfate:oath", "불사의 맹세", "한 번 버틴다", "trial",
						"minecraft:totem_of_undying", ""),
				new PerkOfferPayload.PerkOption("sharedfate:quiver", "마르지 않는 화살통", "화살 없이 쏜다",
						"trial", "", ""));
	}

	@Test
	void 모든_칸이_왕복한다() {
		DragonPrepVotePayload payload = new DragonPrepVotePayload(45,
				DragonPrepVote.Stage.VOTE.ordinal(), 87, 100, true, options(),
				List.of(new DragonPrepVotePayload.Ballot("가람", "sharedfate:oath"),
						new DragonPrepVotePayload.Ballot("나래", "sharedfate:quiver"),
						new DragonPrepVotePayload.Ballot("다온", "sharedfate:oath")),
				"sharedfate:oath", true, "", false);
		DragonPrepVotePayload decoded = roundTrip(payload);
		assertEquals(payload, decoded);
		assertEquals(DragonPrepVote.Stage.VOTE, decoded.stageValue());
		assertEquals(List.of("가람", "다온"), decoded.votersOf("sharedfate:oath"),
				"카드 아래 이름 줄 — 던진 차례 그대로");
		assertEquals(List.of("나래"), decoded.votersOf("sharedfate:quiver"));
	}

	@Test
	void 결과_단계도_왕복한다() {
		DragonPrepVotePayload payload = new DragonPrepVotePayload(50,
				DragonPrepVote.Stage.RESULT.ordinal(), 100, 100, false, options(), List.of(), "", false,
				"sharedfate:quiver", true);
		DragonPrepVotePayload decoded = roundTrip(payload);
		assertEquals("sharedfate:quiver", decoded.winner());
		assertTrue(decoded.byRoulette());
		assertTrue(decoded.votersOf("sharedfate:oath").isEmpty());
	}

	@Test
	void 어긋난_값으로도_패킷이_터지지_않는다() {
		DragonPrepVotePayload payload = new DragonPrepVotePayload(45, -1, -5, 0, false, options(),
				List.of(), null, true, null, false);
		DragonPrepVotePayload decoded = roundTrip(payload);
		assertEquals(0, decoded.stageTicks());
		assertEquals(1, decoded.stageTotal(), "막대의 분모가 0 이면 안 된다");
		assertEquals("", decoded.ownVote());
		assertEquals("", decoded.winner());
	}
}
