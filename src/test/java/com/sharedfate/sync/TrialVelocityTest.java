package com.sharedfate.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ⚠⚠ <b>자르는 함수가 한 벌인지</b>와 <b>천장이 모드 전체에서 유효한지</b>만 본다.
 *
 * <p>{@code syncedVertical} 이 <b>무엇을 돌려주는가</b>는 {@code DragonLastStandPatternsTest} 가
 * 이미 거리마다·틱마다 굴려 본다(그쪽이 날개 번치의 쌓임을 세는 자리라 함수가 태어난 곳이다).
 * 여기서 다시 재면 <b>시험이 두 벌</b>이 되어 언젠가 한쪽만 고쳐진다 — 그래서 이 파일은
 * <b>식이 아니라 자리</b>를 지킨다.
 *
 * <p>카드별 성질은 {@code TrialEnderStormTest}·{@code TrialLandingShockTest} 가 각자 본다
 * (쌓인 세로를 배달하지 않는다 · 정상 플레이어는 비트 단위 무변화 · 수평 천장이 그대로다).
 */
class TrialVelocityTest {

	/**
	 * ⚠⚠ <b>자르는 함수가 한 벌이다.</b>
	 *
	 * <p>이 저장소는 <b>같은 값이 두 벌이 되는 것을 네 번 겪었다</b>(단상 계산 세 벌 · 먼지 크기
	 * 두 벌 · {@code CRIT} 상수 두 벌). 자름이 두 벌이 되면 <b>한쪽만 고쳐지고</b>, 이 함수에서
	 * 그 일이 일어나면 고쳐지지 않은 카드가 사람을 109칸 띄운다.
	 *
	 * <p>그래서 <b>선언이 하나뿐</b>인지를 반사로 센다. 바이트열에서 {@code "syncedVertical"} 을
	 * 찾는 것으로는 <b>부르는 것과 선언하는 것을 가를 수 없어</b> 두 벌을 못 잡는다.
	 */
	@Test
	void 자르는_함수가_한_벌이다() {
		assertEquals(1, declared(TrialVelocity.class).size(),
				"TrialVelocity 가 syncedVertical 을 하나만 선언해야 한다");

		// 부르는 세 파일. 어느 하나도 제 사본을 가지면 안 된다.
		for (Class<?> other : new Class<?>[] {DragonLastStandPatterns.class, TrialEnderStorm.class,
				TrialLandingShock.class}) {
			assertTrue(declared(other).isEmpty(),
					other.getSimpleName() + " 가 제 syncedVertical 을 선언했다 — 두 벌이 됐다는"
							+ " 뜻이고, 다음에 고치는 사람은 한쪽만 고친다");
		}

		// 세로를 덮어쓰는 둘은 이 함수를 부르지도 선언하지도 않는다. 바이트열에 이름이 아예 없는
		// 것으로 충분하다 — 반사로 열면 클라이언트 쪽 서술자까지 풀어야 해서 더 비싸다.
		for (String owner : new String[] {"/com/sharedfate/sync/TrialRisks.class",
				"/com/sharedfate/perk/PerkClientRules.class"}) {
			assertFalse(read(owner).contains("syncedVertical"),
					owner + " 에 자름이 생겼다 — 그쪽은 세로를 덮어쓰는 자리라 지날 필요가 없다."
							+ " 정말로 지나야 하게 됐으면 이 시험과 TrialVelocity 의 목록을 함께"
							+ " 고칠 것");
		}
	}

	/**
	 * ⚠ <b>두 번째 천장을 베껴 적지 않았다.</b>
	 *
	 * <p>천장은 {@code DragonLastStandPatterns.CROSS_LIFT_SPEED} 곧 <b>우리가 일부러 싣는 가장 큰
	 * 세로</b>(십자 띄움 12칸)이고, 그 값이 정의된 자리는 그 파일 하나다. 여기에 숫자로 옮겨 적으면
	 * 칸 수를 고치는 날 이 천장이 뒤에 남는다(2026-10-04 에 6칸 → 12칸으로 실제로 고쳤다).
	 *
	 * <p>칸을 하나도 들지 않는 것으로 그것을 못박는다 — 베껴 적으려면 칸이 필요하다.
	 */
	@Test
	void 천장을_베껴_적지_않았다() {
		assertEquals(0, TrialVelocity.class.getDeclaredFields().length,
				"칸이 생겼다 — 천장을 베껴 적었는지 먼저 볼 것");
		assertEquals(DragonLastStandPatterns.CROSS_LIFT_SPEED,
				TrialVelocity.syncedVertical(1.0E9, 1.0E9), 0.0,
				"거짓 보고용 보험이 그 값이 아니다 — 클라이언트가 터무니없는 수를 보내도 우리가"
						+ " 일부러 싣는 가장 큰 세로에서 멈춰야 한다");
	}

	/**
	 * ⚠ <b>우리가 일부러 싣는 세로가 모두 천장 아래다.</b>
	 *
	 * <p>이 함수가 최후의 저항 밖으로 나오면서 {@code CROSS_LIFT_SPEED} 천장의 근거가
	 * 「<b>이 파일이</b> 세로로 만드는 가장 큰 값」에서 「<b>우리 모드가</b> 일부러 싣는 가장 큰
	 * 값」으로 넓어졌다. 그 말이 참인지를 값에서 센다 — 넘는 것이 생기면 그 카드의 띄우기가
	 * 조용히 깎인다.
	 *
	 * <p>세로를 <b>덮어쓰는</b> 자리가 둘이다. {@code TrialRisks.launch} 의 「자리 폭격」 4칸과
	 * {@code DragonLastStandPatterns.liftCross} 의 십자 12칸. 덮어쓰기라 이 함수를 지나지 않지만,
	 * 그 사람이 <b>다음 틱에 다른 카드에 밀리면</b> 그때 올라가는 속도가 천장에 걸린다.
	 */
	@Test
	void 우리가_일부러_싣는_세로가_모두_천장_아래다() {
		double ceiling = DragonLastStandPatterns.CROSS_LIFT_SPEED;
		assertEquals(1.491, ceiling, 0.001,
				"십자 12칸의 처음 속도다(6칸이던 때 1.007) — 달라졌으면 아래 수도 볼 것");

		// 「자리 폭격」의 4칸. 이 값이 천장을 넘으면 띄워진 사람이 밀릴 때 띄움이 깎인다.
		double launch = TrialRisks.launchVelocity(4.0);
		assertEquals(0.8, launch, 1.0E-9, "√(2 × 0.08 × 4) = 0.8 칸/틱");
		assertTrue(launch <= ceiling,
				"띄우는 카드가 천장보다 세다 — 그 사람이 다음 틱에 밀리면 띄움이 조용히 깎인다");
		assertEquals(launch, TrialVelocity.syncedVertical(launch, launch), 0.0,
				"띄워진 사람의 세로가 달라졌다");
	}

	/**
	 * ⚠⚠ <b>속도를 내려 보내는 자리 넷(파일 셋)이 모두 자름을 지난다.</b>
	 *
	 * <p>순수 함수 시험은 함수가 맞는지만 보고, 그 함수를 <b>안 부르면</b> 아무것도 못 잡는다.
	 * 2026-10-04 에 실제로 그랬다 — 날개 번치와 공허 흡입만 자름을 지나고 「엔더폭풍」·「착지
	 * 충격」은 그대로였다.
	 *
	 * <p>⚠ <b>자리를 새로 만들면 이 목록에 더해야 한다.</b> 바이트열에 {@code syncVelocity} 가
	 * 있는 파일을 저장소 전체에서 쓸어 고른 목록이고, 거기서 빠진 둘
	 * ({@code TrialRisks}·{@code PerkClientRules})은 세로를 <b>덮어쓰거나</b> 클라이언트가 보고한
	 * 값을 <b>그대로</b> 돌려보내 남이 쌓아 둔 값이 섞일 자리가 없다.
	 */
	@Test
	void 속도를_내려보내는_자리_넷이_모두_자름을_지난다() {
		// 자리는 넷이고 파일은 셋이다 — DragonLastStandPatterns 가 둘(날개 번치·공허 흡입)을 든다.
		for (String owner : new String[] {"DragonLastStandPatterns", "TrialEnderStorm",
				"TrialLandingShock"}) {
			String bytes = read("/com/sharedfate/sync/" + owner + ".class");
			assertTrue(bytes.contains("syncVelocity"),
					owner + " 가 속도를 내려보내지 않는다 — 이 목록이 낡았는지 먼저 볼 것");
			assertTrue(bytes.contains("com/sharedfate/sync/TrialVelocity"),
					owner + " 가 자름을 안 지난다 — 남이 쌓아 둔 세로가 그대로 배달된다");
			assertTrue(bytes.contains("getKnownMovement"),
					owner + " 에 천장이 없다 — deltaMovement 만 보면 남이 쌓아 둔 값과 사람 몫을"
							+ " 가를 수 없다");
			assertFalse(bytes.contains("knockBack"),
					owner + " 가 바닐라 넉백에 손댔다 — 끄는 것은 믹스인의 일이다");
		}

		// 덮어쓰는 둘. 「자름을 지나지 않는다」가 빠뜨린 것이 아니라는 것을 여기 적어 둔다.
		assertTrue(read("/com/sharedfate/sync/TrialRisks.class").contains("launchVelocity"),
				"자리 폭격이 세로를 덮어쓰지 않게 됐으면 이 설명과 위 목록을 함께 고칠 것");
		assertTrue(read("/com/sharedfate/perk/PerkClientRules.class")
						.contains("getKnownMovement"),
				"거절 되돌리기가 클라이언트 보고를 그대로 쓰지 않게 됐으면 함께 고칠 것");
	}

	private static List<Method> declared(Class<?> owner) {
		List<Method> found = new ArrayList<>();
		for (Method method : owner.getDeclaredMethods()) {
			if (method.getName().equals("syncedVertical")) {
				found.add(method);
			}
		}
		return found;
	}

	private static String read(String path) {
		try (InputStream in = TrialVelocityTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
