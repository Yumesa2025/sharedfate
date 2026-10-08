package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.RuinSurveyEffect;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 보유 증강 동기화가 그대로 왕복하는지 본다. 특히 <b>규약 35 에서 늘린 유적 좌표 칸</b>이다.
 *
 * <p>좌표는 「유적 감별사」가 찾아 둔 값이고, 팀 화면의 증강 목록 <b>오른쪽</b>과 선택 화면의
 * 보유 증강 겹판이 이 칸만 보고 그린다. 예전에는 설명 문자열 뒤에 괄호로 붙여 보냈고 그래서는
 * 오른쪽에 따로 세울 수 없었다 — {@code PerkManager.ruinCoords} 의 머리말에 그 경위가 있다.
 *
 * <p>상한이 {@link RuinSurveyEffect#MAX_STRUCTURES} 와 어긋나면, 정의상 정당한 네 줄짜리
 * 증강이 <b>패킷 인코딩에서 터진다.</b> 그러면 증강 동기화가 통째로 끊겨 목록도 대기 중인
 * 선택권 수도 화면에서 사라진다. 그 어긋남을 여기서 붙들어 둔다.
 */
class PerkSyncPayloadTest {
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static PerkSyncPayload roundTrip(PerkSyncPayload payload) {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		PerkSyncPayload.CODEC.encode(buffer, payload);
		return PerkSyncPayload.CODEC.decode(buffer);
	}

	@Test
	void 빈_상태에는_좌표도_없다() {
		assertTrue(PerkSyncPayload.EMPTY.owned().isEmpty());
		assertEquals(0, PerkSyncPayload.EMPTY.pendingCount());
		assertEquals("", PerkSyncPayload.EMPTY.chooserName());
		assertTrue(PerkSyncPayload.EMPTY.ruinCoords().isEmpty(),
				"그 증강이 없으면 빈 목록이다. null 이면 화면이 통째로 터진다");
	}

	@Test
	void 좌표_두_줄이_직렬화를_그대로_통과한다() {
		PerkSyncPayload decoded = roundTrip(new PerkSyncPayload(
				List.of(new PerkSyncPayload.Owned("유적 감별사", "가까운 유적 두 곳의 좌표를 알려 줍니다.",
						"prism", "")),
				1, "Steve",
				List.of("고대 도시  -1234, 567", "엔더 요새  890, -123")));

		assertEquals(1, decoded.owned().size());
		assertEquals("유적 감별사", decoded.owned().getFirst().name());
		// 설명에는 좌표가 섞여 있지 않아야 한다. 섞이면 목록에 같은 좌표가 두 번 뜬다.
		assertEquals("가까운 유적 두 곳의 좌표를 알려 줍니다.", decoded.owned().getFirst().description());
		assertEquals(1, decoded.pendingCount());
		assertEquals("Steve", decoded.chooserName());
		assertEquals(List.of("고대 도시  -1234, 567", "엔더 요새  890, -123"), decoded.ruinCoords());
	}

	@Test
	void 좌표가_없는_팀도_그대로_통과한다() {
		PerkSyncPayload decoded = roundTrip(new PerkSyncPayload(
				List.of(new PerkSyncPayload.Owned("짐꾼 가호", "설명", "silver", "운반")),
				0, "", List.of()));

		assertTrue(decoded.ruinCoords().isEmpty());
		assertEquals("운반", decoded.owned().getFirst().setTypes());
	}

	@Test
	void 좌표_상한이_정의의_구조물_상한과_같다() {
		assertEquals(RuinSurveyEffect.MAX_STRUCTURES, PerkSyncPayload.MAX_RUIN_COORDS,
				"여기가 더 작으면 정의상 정당한 증강이 패킷 인코딩에서 터진다");
	}

	@Test
	void 상한까지는_보내고_넘으면_거절한다() {
		assertEquals(PerkSyncPayload.MAX_RUIN_COORDS,
				roundTrip(new PerkSyncPayload(List.of(), 0, "",
						coords(PerkSyncPayload.MAX_RUIN_COORDS))).ruinCoords().size(),
				"상한까지는 그대로 나가야 한다");

		// 상한을 넘으면 조용히 잘리는 것이 아니라 터진다. 정의 쪽에서 이미 막고 있으므로
		// (RuinSurveyEffect 가 MAX_STRUCTURES 를 넘는 정의를 건너뛴다) 여기 닿을 일이 없지만,
		// 닿았을 때 조용히 줄어들면 어느 좌표가 빠졌는지 아무도 모른다.
		PerkSyncPayload tooMany =
				new PerkSyncPayload(List.of(), 0, "", coords(PerkSyncPayload.MAX_RUIN_COORDS + 1));
		assertThrows(RuntimeException.class, () -> roundTrip(tooMany));
	}

	private static List<String> coords(int count) {
		List<String> lines = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			lines.add("유적 " + index + "  " + index + ", " + index);
		}
		return lines;
	}
}
