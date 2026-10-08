package com.sharedfate.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 전투 중에 <b>엔드 밖에 남은 팀원</b>을 다시 부르는 그물.
 *
 * <p>고칠 수 없는 종류의 사고를 막는 자리다 — 한 번 밖에 남으면
 * {@code DragonTrialManager.detectArrival} 이 세션을 보고 건너뛰어 <b>다시는 안 불렀다.</b>
 * 공유 체력이라 그 사람은 싸우지 않으면서 체력만 같이 깎이고, 전멸은 곧 월드 삭제다.
 *
 * <p>실제로 부르는 부분은 살아 있는 서버가 있어야 해서 여기서 돌려 볼 수 없다. 대신
 * <b>값의 근거</b>와 <b>배선</b>을 붙든다 — 둘 다 조용히 무너지는 쪽이다.
 */
class EndFightRecallTest {

	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonTrialManager.clearState();
	}

	// ------------------------------------------------------------------ 간격의 근거

	/**
	 * 재소환 간격이 <b>도착 무적보다 짧다.</b>
	 *
	 * <p>{@code summonTeam} 은 옮기면서 저항 V 를 {@code ARRIVAL_GRACE_TICKS} 만큼 건다.
	 * 재소환이 그보다 뜸하면 <b>끌려오는 사이사이에 무방비인 구간</b>이 생긴다 — 밖에서
	 * 끌려온 사람이 크리스탈 빔이나 패턴 한가운데에 떨어지는 자리다.
	 */
	@Test
	void 재소환_간격이_도착_무적보다_짧다() {
		assertTrue(DragonTrialManager.RECALL_INTERVAL_TICKS
						< DragonTrialManager.ARRIVAL_GRACE_TICKS,
				"간격 " + DragonTrialManager.RECALL_INTERVAL_TICKS + "틱 · 도착 무적 "
						+ DragonTrialManager.ARRIVAL_GRACE_TICKS + "틱 — 무적이 끊기는 구간이 생긴다");
	}

	/**
	 * 재소환 간격이 <b>「운명 공동체」의 {@code gather} 쿨타임과 같지 않다.</b>
	 *
	 * <p>그쪽은 20틱마다 팀을 무작위 기준점으로 모으고, 차원이 다르면 거리와 무관하게
	 * 「흩어짐」으로 본다. 같은 박자로 두면 두 기능이 <b>1:1 로 맞붙어</b> 사람이 두 자리
	 * 사이에서 떨린다. 자물쇠가 지금 그쪽을 막지만, 박자를 겹쳐 두면 자물쇠가 새는 날의
	 * 증상이 「가끔 순간이동이 안 먹는다」가 아니라 「사람이 깜빡인다」가 된다.
	 */
	@Test
	void 재소환_간격이_집합_주기와_겹치지_않는다() {
		int gatherCooldownTicks = 20;
		assertNotEquals(gatherCooldownTicks, DragonTrialManager.RECALL_INTERVAL_TICKS,
				"「운명 공동체」의 gather 와 같은 박자다");
	}

	/** 매 틱 도는 것이 아니다. 재소환은 사람을 화면째로 끌어오는 일이라 값이 1 이면 안 된다. */
	@Test
	void 매_틱_부르지_않는다() {
		assertTrue(DragonTrialManager.RECALL_INTERVAL_TICKS > 1,
				"매 틱 끌어오면 뒤에 도는 순간이동과 매 틱 맞붙는다");
	}

	// ------------------------------------------------------------------ 배선

	/**
	 * 재소환이 {@code tick} 에 걸려 있다.
	 *
	 * <p>⚠ {@code tickSessions} 안에 두면 <b>시련을 끈 팀</b>과 <b>최후의 저항이 도는 팀</b>이
	 * {@code continue} 에 걸려 빠진다. 붙박이 드래곤과 싸우는 도중에 한 명이 밖에 남는 것이
	 * 가장 나쁜 경우라, 그 둘이야말로 빠지면 안 되는 쪽이다.
	 */
	@Test
	void 재소환이_배선에_들어_있다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("recallStragglers"), "재소환이 사라졌다");
		assertTrue(bytes.contains("summonTeam"),
				"재소환이 summonTeam 을 부르지 않는다 — 착지점과 도착 무적이 두 벌이 된 것이다");
	}

	/** 소환 대기 조회기가 열려 있다. {@code EndFightTeleportLock} 이 근사를 버리는 자리다. */
	@Test
	void 소환_대기_조회기가_열려_있다() {
		assertFalse(DragonTrialManager.pendingSummon(null),
				"팀을 모르면 대기 중이 아니다");
		assertFalse(DragonTrialManager.pendingSummon(UUID.randomUUID()),
				"아무 팀이나 대기 중이라고 하면 안 된다");
	}

	/**
	 * 등록 순서의 함정이 문서에 남아 있다.
	 *
	 * <p>{@code DragonTrialManager::tick} 이 {@code END_SERVER_TICK} 의 맨 앞이라 <b>같은 틱
	 * 뒤쪽의 순간이동이 엔드 소환을 덮어쓴다.</b> 이 사실이 파일에서 사라지면 다음 사람이
	 * 「순서를 바꿔 이기자」로 또 밟는다 — 그 길은 이번에는 남의 순간이동을 우리가 덮어쓰게
	 * 만들 뿐이다.
	 */
	@Test
	void 등록_순서의_함정이_적혀_있다() {
		String source = sourceText();
		assertTrue(source.contains("END_SERVER_TICK"), "등록 순서 이야기가 사라졌다");
		assertTrue(source.contains("EndFightTeleportLock"), "무엇이 그 다섯을 막는지가 사라졌다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		try (InputStream in = DragonTrialManager.class
				.getResourceAsStream("/com/sharedfate/sync/DragonTrialManager.class")) {
			if (in == null) {
				return fail("DragonTrialManager 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}

	/** 주석은 클래스 파일에 남지 않는다. 판단의 근거를 붙들려면 소스를 읽어야 한다. */
	private static String sourceText() {
		java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
		for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
			java.nio.file.Path candidate = at.resolve("src/main/java/com/sharedfate/sync"
					+ "/DragonTrialManager.java");
			if (java.nio.file.Files.isRegularFile(candidate)) {
				try {
					return java.nio.file.Files.readString(candidate, StandardCharsets.UTF_8);
				} catch (IOException broken) {
					return fail(broken);
				}
			}
		}
		return fail("DragonTrialManager.java 를 찾지 못했다. 작업 디렉터리: " + here);
	}
}
