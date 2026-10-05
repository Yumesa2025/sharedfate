package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import com.sharedfate.sync.TrialWarning;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 드래곤 패턴 타이머 HUD 묶음.
 *
 * <p>사람 말(2026-10-05): 「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게
 * 오른쪽 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」.
 */
class TrialTimersPayloadTest {

	/**
	 * 이 묶음이 들어간 규약 번호.
	 *
	 * <p>⚠ 묶음이 새로 생기면 옛 클라이언트는 그냥 HUD 가 안 뜬다 — 옆 사람 화면에는 「브레스
	 * 3.2초」가 떠 있는데 자기 화면에만 없다. 막을 수단이 악수뿐이라 번호를 함께 올려야 한다.
	 */
	private static final int PROTOCOL_VERSION_WITH_TIMERS = 37;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static RegistryFriendlyByteBuf encode(TrialTimersPayload payload) {
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		TrialTimersPayload.CODEC.encode(buffer, payload);
		return buffer;
	}

	private static TrialTimersPayload roundTrip(TrialTimersPayload payload) {
		RegistryFriendlyByteBuf buffer = encode(payload);
		TrialTimersPayload decoded = TrialTimersPayload.CODEC.decode(buffer);
		assertEquals(0, buffer.readableBytes(), "읽고 남은 바이트가 있으면 칸이 어긋난 것이다");
		return decoded;
	}

	private static TrialTimersPayload fightSample() {
		return new TrialTimersPayload(true, false, List.of(
				Entry.countdown("sharedfate:pillar_fireball#0", "기둥 화염구", Kind.DAMAGE, 130, 200, 40),
				Entry.countdown("sharedfate:ground_strike#0", "자리 폭격", Kind.DAMAGE, 140, 240, 50),
				Entry.active("sharedfate:night_host#0", "밤의 군세", Kind.DISRUPT, 300, 400),
				Entry.countdown("passive:barrage", "연쇄 포격", Kind.DAMAGE, 700, 800, 80)),
				Optional.empty());
	}

	private static TrialTimersPayload lastStandSample() {
		return new TrialTimersPayload(true, true, List.of(
				Entry.active("last_stand:zone", "안전지대 축소 중 → 22칸", Kind.ZONE, 40, 100),
				Entry.countdown("last_stand:lightning", "상시 번개", Kind.LIGHTNING, 90, 156, 60),
				Entry.health("last_stand:objects", "오브젝트 파도", Kind.HEAL, "체력 25%", 0.6F)),
				Optional.of(new Cast("부채꼴 브레스", Kind.DAMAGE, Cast.STATE_WARNING, 50, 80,
						"머리 방향 90° · 한 대에 전멸 — 옆으로")));
	}

	@Test
	void 일반_전투_묶음이_그대로_왕복한다() {
		TrialTimersPayload sample = fightSample();
		assertEquals(sample, roundTrip(sample));
	}

	@Test
	void 최후의_저항_묶음이_시전_바와_체력형_줄까지_그대로_왕복한다() {
		TrialTimersPayload sample = lastStandSample();
		TrialTimersPayload decoded = roundTrip(sample);
		assertEquals(sample, decoded);
		assertTrue(decoded.frozen());
		assertEquals(Cast.STATE_WARNING, decoded.cast().orElseThrow().state());
		assertEquals("체력 25%", decoded.timers().get(2).valueText());
		assertEquals(0.6F, decoded.timers().get(2).fill(), 0.0F);
	}

	@Test
	void 지우는_묶음은_내용을_싣지_않는다() {
		TrialTimersPayload hidden = new TrialTimersPayload(false, true, fightSample().timers(),
				lastStandSample().cast());
		assertEquals(TrialTimersPayload.HIDDEN, hidden);
		assertEquals(TrialTimersPayload.HIDDEN, roundTrip(hidden));
		assertFalse(roundTrip(TrialTimersPayload.HIDDEN).visible());
	}

	@Test
	void 밖에서_오는_값이_묶음을_터뜨리지_않는다() {
		String longText = "가".repeat(500);
		Entry entry = new Entry(null, longText, Kind.DAMAGE, Entry.MODE_COUNTDOWN, -5, -9, -1,
				null, 3.0F);
		assertEquals("", entry.id());
		assertEquals(TrialTimersPayload.MAX_LABEL_LENGTH, entry.label().length());
		assertEquals(0, entry.remainingTicks(), "VAR_INT 에 음수를 싣지 않는다");
		assertEquals(-1.0F, entry.fill(), "시간형의 바 칸은 -1");
		Entry health = Entry.health("o", "x", Kind.HEAL, longText, 9.0F);
		assertEquals(1.0F, health.fill());
		Cast cast = new Cast(longText, Kind.DAMAGE, Cast.STATE_RUNNING, 50, 10, longText);
		assertEquals(50, cast.totalTicks(), "분모는 언제나 남은 틱 이상");

		List<Entry> many = new ArrayList<>();
		for (int index = 0; index < TrialTimersPayload.MAX_TIMERS + 5; index++) {
			many.add(entry);
		}
		TrialTimersPayload crowded = new TrialTimersPayload(true, false, many,
				Optional.of(cast));
		assertEquals(TrialTimersPayload.MAX_TIMERS, crowded.timers().size());
		assertEquals(crowded, roundTrip(crowded));
	}

	/**
	 * 대역폭 셈의 근거. 보고에 적은 수가 이 시험에서 나온다 — 줄 하나에 몇 바이트인가.
	 */
	@Test
	void 묶음_크기가_한_줄에_수십_바이트다() {
		int fight = encode(fightSample()).readableBytes();
		int lastStand = encode(lastStandSample()).readableBytes();
		int hidden = encode(TrialTimersPayload.HIDDEN).readableBytes();
		System.out.println("[TrialTimersPayload] 일반 전투 4줄 = " + fight + "바이트, 최후의 저항 3줄 + 시전 바 = "
				+ lastStand + "바이트, 지우기 = " + hidden + "바이트");
		assertTrue(fight < 300, "일반 전투 4줄이 " + fight + "바이트");
		assertTrue(lastStand < 300, "최후의 저항이 " + lastStand + "바이트");
		assertEquals(4, hidden, "bool 둘 + 빈 목록 + 빈 Optional");
	}

	@Test
	void 밀침의_파랑은_바닥_표식의_파랑과_같다() {
		assertEquals(TrialWarning.Colors.SHOVE, Kind.color(Kind.SHOVE));
		assertEquals(0x4AA3FF, Kind.COLOR_SHOVE);
		assertEquals(0xEF5350, Kind.color(Kind.DAMAGE));
		assertEquals(0xF2C94C, Kind.color(Kind.LIGHTNING));
		assertEquals(0xB07CFF, Kind.color(Kind.DISRUPT));
		assertEquals(0xFF8A3D, Kind.color(Kind.ZONE));
		assertEquals(0x3DDC97, Kind.color(Kind.HEAL));
		assertEquals(Kind.COLOR_NEUTRAL, Kind.color((byte) 99), "모르는 종류는 회색");
	}

	@Test
	void 규약_번호가_올라가_있다() {
		assertTrue(SharedFateNetworking.PROTOCOL_VERSION >= PROTOCOL_VERSION_WITH_TIMERS,
				"패턴 타이머 묶음은 규약 " + PROTOCOL_VERSION_WITH_TIMERS + " 부터다. 지금 번호: "
						+ SharedFateNetworking.PROTOCOL_VERSION);
	}
}
