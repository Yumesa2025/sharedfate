package com.sharedfate.sync;

import net.minecraft.world.entity.LightningBolt;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 우리 번개 소리가 바닐라의 70% 다 — 2026-10-18 사람 말 「번개 소리 자체를 30프로 줄여 봐」.
 *
 * <p>소리 크기는 월드 없이 셈으로 정해지므로 거리마다 직접 잰다. 바닐라가 소리를 내는 자리와 값은
 * 26.3 클래스 파일에서 찾는다 — 판이 올라 그 자리가 바뀌면 여기서 먼저 멈춘다.
 */
class TrialThunderTest {

	/** 귀에 닿는 크기 — 놓은 자리의 감쇠를 거친 뒤. 볼륨이 1 이하라 감쇠 거리는 16 이다. */
	private static float heard(float sent, double place) {
		return (float) (Math.min(sent, 1.0F) * (1.0 - place / TrialThunder.ATTENUATION_DISTANCE));
	}

	@Test
	void 어느_거리에서나_바닐라의_70퍼센트로_들린다() {
		assertEquals(0.7F, TrialThunder.LOUDNESS, 0.0F, "사람이 「30프로 줄여」라고 했다");
		for (float volume : new float[] {TrialThunder.THUNDER_VOLUME, TrialThunder.IMPACT_VOLUME}) {
			for (double distance = 0.0; distance <= 260.0; distance += 0.25) {
				float target = TrialThunder.LOUDNESS * TrialThunder.vanillaLoudness(volume, distance);
				double place = Math.min(distance, TrialThunder.PLACE_DISTANCE);
				float sent = TrialThunder.sentVolume(target, place);
				assertTrue(sent <= 1.0F,
						"보내는 볼륨이 1 을 넘으면 감쇠 거리가 16 에서 늘어나 셈이 깨진다: " + sent);
				assertEquals(target, heard(sent, place), 1.0E-5F,
						"볼륨 " + volume + " · 거리 " + distance + " 에서 70% 가 아니다");
			}
		}
	}

	/**
	 * ⚠ 볼륨에 0.7 을 그대로 곱하면 안 되는 까닭 — 1 을 넘는 볼륨은 크기가 아니라 들리는 거리다.
	 */
	@Test
	void 볼륨만_곱하면_천둥이_안_작아지고_착탄음은_거리만_줄어든다() {
		assertEquals(1.0F, TrialThunder.vanillaLoudness(10000.0F * 0.7F, 40.0), 0.001F,
				"천둥 7000 은 바닐라 10000 과 똑같이 들린다 — 하나도 안 작아진다");
		assertEquals(0.0F, TrialThunder.vanillaLoudness(2.0F * 0.7F, 24.0), 0.0F,
				"착탄음 1.4 는 22.4칸 밖에서 아예 안 들린다 — 바닐라는 32칸까지 들린다");
		assertTrue(TrialThunder.vanillaLoudness(2.0F, 24.0) > 0.2F, "바닐라 착탄음은 24칸에서 들린다");
		assertEquals(0.5F, TrialThunder.vanillaLoudness(2.0F, 16.0), 1.0E-6F, "착탄음은 32칸 선형 감쇠다");
	}

	/** 바닐라가 클라이언트에서 내는 두 소리와 그것을 끄는 깃발이 26.3 에 그대로 있다. */
	@Test
	void 바닐라_번개는_조용하지_않을_때만_두_소리를_낸다() {
		byte[] bolt = classBytes(LightningBolt.class);
		assertTrue(references(bolt, "isSilent"), "조용함을 안 보면 우리가 꺼도 바닐라 소리가 겹친다");
		assertTrue(references(bolt, "LIGHTNING_BOLT_THUNDER"), "천둥을 내는 자리가 사라졌다");
		assertTrue(references(bolt, "LIGHTNING_BOLT_IMPACT"), "착탄음을 내는 자리가 사라졌다");
		assertTrue(holdsFloat(bolt, TrialThunder.THUNDER_VOLUME), "바닐라 천둥 볼륨이 10000 이 아니다");
	}

	/** 번개를 띄우는 두 곳이 모두 이 길을 지난다. */
	@Test
	void 번개를_띄우는_두_곳이_조용히_띄운다() {
		assertTrue(references(classBytes(TrialRisks.class), "TrialThunder"),
				"「낙뢰」 번개가 바닐라 소리 그대로다");
		assertTrue(references(classBytes(DragonLastStandPatterns.class), "TrialThunder"),
				"최후의 저항 상시 번개가 바닐라 소리 그대로다");
		assertTrue(references(classBytes(TrialThunder.class), "setSilent"),
				"개체를 조용히 하지 않으면 바닐라 소리와 우리 소리가 겹친다");
	}

	// ------------------------------------------------------------------ 도구

	private static byte[] classBytes(Class<?> type) {
		String resource = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream stream = type.getResourceAsStream(resource)) {
			assertNotNull(stream, resource + " 를 클래스패스에서 찾지 못했다");
			return stream.readAllBytes();
		} catch (IOException broken) {
			throw new AssertionError(resource + " 를 읽지 못했다", broken);
		}
	}

	private static boolean references(byte[] compiled, String name) {
		return indexOf(compiled, name.getBytes(StandardCharsets.US_ASCII)) >= 0;
	}

	/** 상수 풀의 {@code CONSTANT_Float}(꼬리표 4)로 그 값을 들고 있는가. */
	private static boolean holdsFloat(byte[] compiled, float value) {
		int bits = Float.floatToIntBits(value);
		return indexOf(compiled, new byte[] {4, (byte) (bits >>> 24), (byte) (bits >>> 16),
				(byte) (bits >>> 8), (byte) bits}) >= 0;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int start = 0; start + needle.length <= haystack.length; start++) {
			for (int index = 0; index < needle.length; index++) {
				if (haystack[start + index] != needle[index]) {
					continue outer;
				}
			}
			return start;
		}
		return -1;
	}
}
