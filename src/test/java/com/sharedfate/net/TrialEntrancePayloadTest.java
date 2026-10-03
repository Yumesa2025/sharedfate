package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔드 입장 수락창의 묶음 셋.
 *
 * <p>왕복만 보는 시험이 아니다. 이 창이 안 뜨거나 확인이 안 먹으면 <b>팀이 엔드에서 60초를
 * 붙들려 있다가 저절로 시작된다</b> — 아무 오류도 로그도 없는 종류의 고장이라 눈으로는 「왜
 * 느리지」로만 보인다.
 */
class TrialEntrancePayloadTest {

	/**
	 * 이 묶음 셋이 들어간 규약 번호.
	 *
	 * <p>⚠ 묶음이 새로 생기면 옛 클라이언트는 읽지 못한다. 여기서는 그 대가가 특히 비싸다 —
	 * 붙들기는 <b>서버가 하는 일이라 그대로 걸리는데</b> 창이 안 떠서 확인할 길이 없다. 막을
	 * 수단이 악수뿐이라 번호를 함께 올려야 하고, 그 「함께」를 사람의 기억에 맡기지 않으려고
	 * 여기 적어 둔다.
	 */
	private static final int PROTOCOL_VERSION_WITH_ENTRANCE_GATE = 36;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static TrialEntranceOfferPayload roundTrip(TrialEntranceOfferPayload payload) {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TrialEntranceOfferPayload.CODEC.encode(buffer, payload);
		TrialEntranceOfferPayload decoded = TrialEntranceOfferPayload.CODEC.decode(buffer);
		assertEquals(0, buffer.readableBytes(), "읽고 남은 바이트가 있으면 칸이 어긋난 것이다");
		return decoded;
	}

	// ------------------------------------------------------------------ 왕복

	@Test
	void 수락창_묶음이_그대로_왕복한다() {
		TrialEntranceOfferPayload decoded = roundTrip(
				new TrialEntranceOfferPayload(12_345L, "불운한 넷", "리더이름", true, 1200));

		assertEquals(12_345L, decoded.openedTick());
		assertEquals("불운한 넷", decoded.teamName());
		assertEquals("리더이름", decoded.leaderName());
		assertTrue(decoded.canConfirm());
		assertEquals(1200, decoded.timeoutTicks());
	}

	/**
	 * <b>「누를 수 있는가」가 거짓인 채로 도착해야 한다.</b>
	 *
	 * <p>이 칸 하나가 「리더만 누른다」의 화면 쪽 전부다. 참으로 뒤집혀 도착하면 네 사람 모두에게
	 * 단추가 그려지고, 리더가 아닌 사람이 누르면 서버가 거절하므로 <b>눌리는데 아무 일도 안
	 * 일어나는 단추</b>가 된다 — 규약 34가 증강 제안에서 적어 둔 그 증상이다.
	 */
	@Test
	void 누를_수_없는_사람에게는_거짓으로_도착한다() {
		assertFalse(roundTrip(
				new TrialEntranceOfferPayload(1L, "팀", "리더", false, 1200)).canConfirm());
	}

	@Test
	void 닫기_묶음이_그대로_왕복한다() {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TrialEntranceClosePayload.CODEC.encode(buffer, new TrialEntranceClosePayload(999L));
		TrialEntranceClosePayload decoded = TrialEntranceClosePayload.CODEC.decode(buffer);

		assertEquals(0, buffer.readableBytes());
		assertEquals(999L, decoded.openedTick());
		assertTrue(decoded.matches(999L), "같은 창이면 닫아야 한다");
		assertFalse(decoded.matches(1000L),
				"다른 창이면 닫지 않는다 — 늦게 온 지시가 다음 창을 닫아 버린다");
	}

	@Test
	void 확인_묶음이_창의_이름표를_그대로_되돌려_보낸다() {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TrialEntranceAcceptC2SPayload.CODEC.encode(buffer,
				new TrialEntranceAcceptC2SPayload(777L));
		TrialEntranceAcceptC2SPayload decoded = TrialEntranceAcceptC2SPayload.CODEC.decode(buffer);

		assertEquals(0, buffer.readableBytes());
		assertEquals(777L, decoded.openedTick());
	}

	/**
	 * 확인 묶음에 <b>「내가 리더다」가 실리지 않는다.</b>
	 *
	 * <p>칸이 하나뿐인 것이 설계다. 밖에서 오는 값으로 권한을 말하게 두면 그 한 칸을 거짓으로
	 * 채운 꾸러미가 남의 전투를 연다. 누를 수 있는 사람인지는 서버가 팀 명단에서 다시 본다
	 * ({@code TrialEntranceGate.mayConfirm}).
	 */
	@Test
	void 확인_묶음에_권한을_싣지_않는다() {
		assertEquals(1, TrialEntranceAcceptC2SPayload.class.getRecordComponents().length,
				"칸이 늘었다. 「내가 리더다」를 클라이언트가 말하게 하지 말 것 —"
						+ " 그 판단은 서버에만 있어야 한다");
	}

	// ------------------------------------------------------------------ 밖에서 오는 값

	@Test
	void 빈_이름으로_만들어도_묶음이_터지지_않는다() {
		TrialEntranceOfferPayload payload =
				new TrialEntranceOfferPayload(1L, null, null, true, 1200);

		assertEquals("", payload.teamName());
		assertEquals("", payload.leaderName());
		// null 하나로 인코딩이 터지면 아무도 창을 못 받고 팀은 60초를 그냥 붙들려 있는다.
		assertEquals("", roundTrip(payload).teamName());
	}

	/** {@code VAR_INT} 는 음수를 싣지 못한다. 서버 계산이 어긋나도 꾸러미가 터지면 안 된다. */
	@Test
	void 음수_제한시간은_0으로_눌린다() {
		assertEquals(0, new TrialEntranceOfferPayload(1L, "팀", "리더", true, -5).timeoutTicks());
	}

	/**
	 * 긴 이름을 <b>글자 단위로</b> 자른다.
	 *
	 * <p>⚠ 바이트로 세어 자르면 한글 한 자가 반으로 쪼개진다. 코덱 상한도 글자 수라 둘을 같은
	 * 자로 재야 한다.
	 */
	@Test
	void 긴_이름은_코덱_상한_안으로_잘린다() {
		String tooLong = "가".repeat(TrialEntranceOfferPayload.MAX_NAME_LENGTH + 20);
		TrialEntranceOfferPayload payload =
				new TrialEntranceOfferPayload(1L, tooLong, tooLong, true, 1200);

		assertEquals(TrialEntranceOfferPayload.MAX_NAME_LENGTH, payload.teamName().length());
		// 자른 뒤에 실제로 선 위를 지나가는지까지 본다. 상한을 넘기면 인코딩이 터진다.
		assertEquals(payload.teamName(), roundTrip(payload).teamName());
	}

	/** 자르는 자리가 대리 쌍 가운데면 한 칸 앞에서 자른다 — 반쪽만 남은 문자는 잘못된 문자열이다. */
	@Test
	void 대리_쌍을_반으로_쪼개지_않는다() {
		// 그림글자 하나가 char 둘이라, 상한이 짝수면 마지막 그림글자의 앞쪽 반이 경계에 걸린다.
		String emoji = "🐉".repeat(TrialEntranceOfferPayload.MAX_NAME_LENGTH);
		String clipped = new TrialEntranceOfferPayload(1L, emoji, "", true, 1200).teamName();

		assertTrue(clipped.length() <= TrialEntranceOfferPayload.MAX_NAME_LENGTH);
		assertFalse(Character.isHighSurrogate(clipped.charAt(clipped.length() - 1)),
				"마지막 글자가 대리 쌍의 앞쪽 반이다 — 깨진 글자가 화면에 뜬다");
	}

	// ------------------------------------------------------------------ 규약

	/**
	 * <b>묶음이 셋 늘었으면 규약 번호도 올라가 있어야 한다.</b>
	 *
	 * <p>⚠ 올리면 <b>서버와 클라이언트 jar 을 둘 다</b> 바꿔야 한다. 한쪽만 올리면 악수 단계에서
	 * 걸려 아예 못 들어온다.
	 */
	@Test
	void 규약이_36_이상이다() {
		assertTrue(SharedFateNetworking.PROTOCOL_VERSION >= PROTOCOL_VERSION_WITH_ENTRANCE_GATE,
				"입장 수락창 묶음 셋은 규약 " + PROTOCOL_VERSION_WITH_ENTRANCE_GATE + " 부터다."
						+ " 지금 번호: " + SharedFateNetworking.PROTOCOL_VERSION);
	}

	/**
	 * 묶음 셋이 <b>저마다 다른 이름</b>을 쓴다.
	 *
	 * <p>같은 식별자를 두 묶음이 쓰면 등록이 하나를 덮고, 그러면 창이 뜨거나 닫히는 쪽 하나가
	 * 조용히 죽는다 — 등록은 예외를 던지지만 그것을 보는 사람은 서버 로그를 켠 사람뿐이다.
	 */
	@Test
	void 묶음_셋의_이름이_서로_다르다() {
		assertFalse(TrialEntranceOfferPayload.TYPE.id().equals(
						TrialEntranceClosePayload.TYPE.id()),
				"띄우는 묶음과 닫는 묶음이 같은 이름이다");
		assertFalse(TrialEntranceOfferPayload.TYPE.id().equals(
						TrialEntranceAcceptC2SPayload.TYPE.id()),
				"띄우는 묶음과 확인 묶음이 같은 이름이다");
		assertFalse(TrialEntranceClosePayload.TYPE.id().equals(
						TrialEntranceAcceptC2SPayload.TYPE.id()),
				"닫는 묶음과 확인 묶음이 같은 이름이다");
	}
}
