package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「밤의 군세」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>엔더맨을 실제로 적대로 만드는 일은 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다.
 * 그런데 이 카드가 <b>허용된 조건을 깨는</b> 길은 거의 전부 그 바깥이다 — <b>20초가 지나도 안
 * 풀린다</b>, <b>되풀이된다</b>, <b>분노 만료를 우리가 푸는 틱보다 먼저 잡아 둔다</b>.
 *
 * <p>이 카드는 {@code docs/드래곤-시련-카드.md} 의 「쓰지 않습니다」에서 <b>조건부로</b> 풀려난
 * 것이고 그 조건이 「지속 시간이 정해져 있고 되풀이되지 않는다」뿐이다. 조건이 깨지면 카드가
 * 나쁜 것이 아니라 <b>있어서는 안 되는 카드</b>가 되므로, 돌려 보고 발견할 일이 아니다.
 */
class TrialNightHostTest {

	// ------------------------------------------------------------------ 한 번만 터진다

	/**
	 * 20초가 지나면 그 뒤로는 영영 적대가 아니다.
	 *
	 * <p>금지 목록에서 풀려난 근거가 이 한 줄이다. 되풀이되는 순간 「끝이 보이지 않는 어그로」가
	 * 되고, 그것이 금지의 본문이다.
	 */
	@Test
	void 지속_시간이_끝나면_다시_적대가_되지_않는다() {
		int hostileTicks = card().hostileTicks();
		assertFalse(TrialNightHost.hostile(hostileTicks + 1L, hostileTicks),
				"20초를 넘긴 뒤에도 적대면 한 번만 터지는 카드가 아니다");
		for (long elapsed = hostileTicks + 1L; elapsed < hostileTicks * 100L; elapsed += 137L) {
			assertFalse(TrialNightHost.hostile(elapsed, hostileTicks),
					"전투가 길어져도 되살아나면 안 된다: " + elapsed);
		}
	}

	/**
	 * 받은 바로 그 틱에는 아직 아니고, 마지막 틱은 안에 든다.
	 *
	 * <p>{@code TrialRisks.elapsedSinceGrant} 가 복원 직후의 음수 위상을 0 으로 깎으므로, 0 을
	 * 적대로 보면 <b>되감긴 판에서 카드가 다시 터진다.</b>
	 */
	@Test
	void 카드를_받은_틱에는_아직_적대가_아니다() {
		int hostileTicks = card().hostileTicks();
		assertFalse(TrialNightHost.hostile(0L, hostileTicks));
		assertTrue(TrialNightHost.hostile(1L, hostileTicks));
		assertTrue(TrialNightHost.hostile(hostileTicks, hostileTicks), "마지막 틱도 창 안이다");
	}

	/** 적대인 틱이 정확히 카드에 적힌 수만큼이다. 한 틱이라도 넘치면 카드 설명이 거짓이 된다. */
	@Test
	void 적대인_틱이_카드에_적힌_수와_같다() {
		int hostileTicks = card().hostileTicks();
		int counted = 0;
		for (long elapsed = 0L; elapsed <= hostileTicks + 100L; elapsed++) {
			if (TrialNightHost.hostile(elapsed, hostileTicks)) {
				counted++;
			}
		}
		assertEquals(hostileTicks, counted, "적대인 틱 수가 hostileTicks 와 다르다");
	}

	/** 시간을 0 이나 음수로 적은 카드는 아무 일도 하지 않는다. 영원한 어그로로 읽지 않는다. */
	@Test
	void 적대_시간이_없으면_아무_일도_없다() {
		for (long elapsed = 0L; elapsed < 100L; elapsed++) {
			assertFalse(TrialNightHost.hostile(elapsed, 0));
			assertFalse(TrialNightHost.hostile(elapsed, -1));
		}
	}

	// ------------------------------------------------------------------ 분노 만료 시각

	/**
	 * 엔더맨에게 적어 넣는 만료 시각이 <b>우리가 푸는 바로 그 틱</b>이다.
	 *
	 * <p>{@code granted + hostileTicks + 1} 과 같아야 한다. 우리가 푸는 틱은 창을 벗어나는 첫
	 * 틱, 즉 {@code elapsed == hostileTicks + 1} 인 틱이기 때문이다.
	 */
	@Test
	void 분노_만료가_우리가_푸는_틱과_같다() {
		int hostileTicks = card().hostileTicks();
		long granted = 12_345L;
		for (long elapsed = 1L; elapsed <= hostileTicks; elapsed++) {
			long now = granted + elapsed;
			assertEquals(granted + hostileTicks + 1L,
					TrialNightHost.calmAt(now, elapsed, hostileTicks),
					"창 안 어느 틱에서 못 박아도 같은 시각이어야 한다: " + elapsed);
		}
	}

	/**
	 * 못 박은 만료가 우리 창보다 <b>먼저</b> 끝나지 않는다.
	 *
	 * <p>{@code NeutralMob.isAngry} 가 {@code 만료 - 게임 시각 > 0} 이라 같은 값이면 이미 식은
	 * 것이다. 먼저 식으면 창 마지막 틱에 바닐라 목표가 손을 떼고, 「20초 동안 적대」가 실제로는
	 * 19.95초가 된다.
	 */
	@Test
	void 못_박은_만료가_창보다_먼저_끝나지_않는다() {
		int hostileTicks = card().hostileTicks();
		long granted = 900L;
		for (long elapsed = 1L; elapsed <= hostileTicks; elapsed++) {
			long now = granted + elapsed;
			assertTrue(TrialNightHost.calmAt(now, elapsed, hostileTicks) > now,
					"이 틱에는 아직 화나 있어야 한다: " + elapsed);
		}
	}

	// ------------------------------------------------------------------ 연출 예산

	/**
	 * 엔더맨이 몇 마리든 터지는 틱의 패킷 수가 예산을 넘지 않는다.
	 *
	 * <p>엔드의 엔더맨 수는 우리가 정하지 않는다. 상한을 지우면 붐비는 판에서 카드가 터지는
	 * 바로 그 틱에 패킷이 쏟아진다.
	 */
	@Test
	void 연출_패킷_수가_예산을_넘지_않는다() {
		assertEquals(0, TrialNightHost.flarePackets(0));
		assertEquals(1, TrialNightHost.flarePackets(1));
		for (int count = 0; count < 5_000; count += 7) {
			assertTrue(TrialNightHost.flarePackets(count) <= TrialNightHost.FLARE_MAX_MOBS,
					"엔더맨 " + count + " 마리에서 예산을 넘는다");
		}
	}

	// ------------------------------------------------------------------ 클래스 파일이 말하는 것

	/**
	 * 적대를 되돌리는 길이 실제로 클래스 안에 있다.
	 *
	 * <p>{@code NeutralMob.stopBeingAngry} 하나가 맞은 기억 · 분노 대상 · 표적 · 분노 만료를
	 * 함께 끈다. 이 이름이 사라졌다는 것은 누군가 <b>표적만 비우는</b> 코드로 바꿨다는 뜻이고,
	 * 그러면 분노 대상이 남아 {@code EndermanLookForPlayerGoal} 이 다음 틱에 다시 물어 온다.
	 */
	@Test
	void 적대를_되돌리는_길이_있다() {
		byte[] compiled = classBytes(TrialNightHost.class);
		assertTrue(references(compiled, "stopBeingAngry"),
				"적대를 되돌리는 호출이 없다 — 20초가 지나도 안 풀린다");
		assertTrue(references(compiled, "setPersistentAngerEndTime"),
				"분노 만료를 못 박지 않으면 언로드된 엔더맨이 바닐라가 굴린 20~39초를 들고 간다");
	}

	/**
	 * 엔더맨을 새로 소환하지 않는다.
	 *
	 * <p>이 카드는 <b>이미 엔드에 있는</b> 엔더맨을 적대로 만드는 카드이지 수를 늘리는 카드가
	 * 아니다. 소환이 섞이면 카드 설명과 실제가 갈라지고, 늘어난 놈은 20초 뒤에도 남는다.
	 */
	@Test
	void 엔더맨을_새로_소환하지_않는다() {
		byte[] compiled = classBytes(TrialNightHost.class);
		assertFalse(references(compiled, "addFreshEntity"),
				"엔티티를 띄우는 호출이 생겼다 — 수를 늘리는 카드가 아니다");
	}

	/**
	 * 드래곤을 건드리지 않는다.
	 *
	 * <p>진입점이 드래곤을 받는 것은 다섯 실행기의 모양을 같게 두기 위해서다. 읽는 것
	 * ({@code isAlive})까지는 맞지만 상태를 바꾸면 안 된다.
	 */
	@Test
	void 드래곤의_페이즈를_건드리지_않는다() {
		byte[] compiled = classBytes(TrialNightHost.class);
		assertFalse(references(compiled, "setPhase"), "드래곤의 페이즈를 바꾸면 안 된다");
	}

	/**
	 * 시계를 직접 읽지 않는다.
	 *
	 * <p>룰렛이 도는 동안 {@code TrialFreeze} 가 판을 멈추면 게임 시각도 멈춘다. 직접 물으면
	 * 창이 얼어붙은 채로 남아 「20초 뒤 반드시 풀린다」가 깨진다 — 분배기가 넘겨준
	 * {@code now} 만 쓴다.
	 */
	@Test
	void 게임_시각을_직접_읽지_않는다() {
		byte[] compiled = classBytes(TrialNightHost.class);
		assertFalse(references(compiled, "getGameTime"),
				"level.getGameTime() 을 부르면 얼어붙은 판에서 창이 안 닫힌다");
	}

	// ------------------------------------------------------------------ 도구

	private static TrialCatalog.Risk.NightHost card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:night_host");
		assertNotNull(trial, "「밤의 군세」 카드가 없다");
		assertEquals(1, trial.risks().size(), "「밤의 군세」의 위험이 하나가 아니다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.NightHost host -> host;
			case TrialCatalog.Risk risk -> throw new AssertionError(
					"「밤의 군세」의 위험이 밤의 군세가 아니다: " + risk);
		};
	}

	/** 컴파일된 클래스 파일 그대로. 우리가 무엇을 부르는지는 소스가 아니라 여기에 남는다. */
	private static byte[] classBytes(Class<?> type) {
		String resource = type.getSimpleName() + ".class";
		try (InputStream stream = type.getResourceAsStream(resource)) {
			assertNotNull(stream, resource + " 를 클래스패스에서 찾지 못했다");
			return stream.readAllBytes();
		} catch (IOException broken) {
			throw new AssertionError(resource + " 를 읽지 못했다", broken);
		}
	}

	/**
	 * 클래스 파일이 이 이름을 상수 풀에 들고 있는가.
	 *
	 * <p>상수 풀의 이름은 ASCII 구간에서 그대로 바이트로 들어간다. 없다는 것은 이 클래스가 그것을
	 * 어디서도 부르지 않는다는 뜻이다.
	 */
	private static boolean references(byte[] compiled, String name) {
		byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
		outer:
		for (int start = 0; start + needle.length <= compiled.length; start++) {
			for (int index = 0; index < needle.length; index++) {
				if (compiled[start + index] != needle[index]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}
}
