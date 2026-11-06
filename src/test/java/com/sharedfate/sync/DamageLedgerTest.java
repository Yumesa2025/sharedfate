package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DamageLedgerTest {
	private static final UUID TEAM_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID FIRST = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SECOND = UUID.fromString("33333333-3333-3333-3333-333333333333");

	@AfterEach
	void reset() {
		DamageLedger.clearState();
	}

	/**
	 * 책은 <b>회차마다 한 묶음</b>이고, 그 안에 팀원 전원의 받은 피해가 적힌다.
	 *
	 * <p>하트 환산은 적지 않는다. 숫자 둘이 나란히 있으면 어느 쪽이 무엇인지 헷갈린다.
	 */
	@Test
	void 책은_회차마다_팀원_전원의_받은_피해를_적는다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "기록팀", List.of(FIRST, SECOND));
		DamageLedger.record(TEAM_ID, team.name(), FIRST, "첫째", 1, 3.5D);
		DamageLedger.record(TEAM_ID, team.name(), FIRST, "첫째", 1, 0.5D);
		DamageLedger.record(TEAM_ID, team.name(), FIRST, "첫째", 3, 6.0D);

		String book = String.join("\n---\n", DamageLedger.buildPageTexts(team, 3));

		assertTrue(book.contains("1회차 (받은 피해량)"), "회차 제목에 숫자가 받은 피해량이라고 적혀야 한다");
		assertTrue(book.contains("첫째 4.0"), "같은 회차의 두 번은 합쳐진다");
		assertTrue(book.contains("첫째 0.0"), "안 맞은 회차도 0 으로 남는다");
		assertTrue(book.contains("첫째 6.0"));
		assertTrue(book.contains(SECOND.toString().substring(0, 8)),
				"이름을 모르는 팀원도 줄을 차지한다");
		assertFalse(book.contains("♥"), "하트 환산은 더 이상 적지 않는다");
	}

	/** 표지에는 누적 숫자가, 마지막 장에는 맺음말이 온다. */
	@Test
	void 책은_표지와_맺음말_사이에_회차들을_끼운다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "기록팀", List.of(FIRST));
		DamageLedger.record(TEAM_ID, team.name(), FIRST, "첫째", 1, 12.0D);
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 1, "첫째", List.of("짐꾼", "파문"));

		List<String> pages = DamageLedger.buildPageTexts(team, 1);

		assertTrue(pages.getFirst().startsWith("SharedFate 기록"));
		assertTrue(pages.getFirst().contains("최다 피해\n 첫째 12.0"));
		assertTrue(pages.getFirst().contains("최다 사망\n 첫째 1회"));
		assertTrue(pages.getFirst().contains("고른 증강 2개"));
		assertTrue(pages.get(1).contains("끝낸 사람: 첫째"));
		assertTrue(pages.get(1).contains("· 짐꾼"));
		assertTrue(pages.get(1).contains("· 파문"));
		assertEquals("플레이해 주셔서\n감사합니다.\n\n\n제작자 카이렌", pages.getLast());
	}

	/** 승리로 끝난 회차는 「끝낸 사람」 대신 처치를 적는다. */
	@Test
	void 승리로_끝난_회차는_끝낸_사람이_없다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "기록팀", List.of(FIRST));
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 1, null, List.of("짐꾼"));

		String book = String.join("\n---\n", DamageLedger.buildPageTexts(team, 1));

		assertTrue(book.contains("드래곤 처치!"));
		assertFalse(book.contains("끝낸 사람"));
		assertEquals(0, DamageLedger.summaryFor(team).mostDeaths(),
				"승리는 사망으로 세지 않는다");
	}

	// ------------------------------------------------------------------ 엔딩에 쓰는 누적

	/**
	 * 엔딩 숫자는 <b>전체 회차 누적</b>이다.
	 *
	 * <p>회차가 넘어가면 팀 상태의 증강이 비워지므로, 회차가 끝나는 순간 찍어 둔 사진을 더하는
	 * 것 말고는 「지금까지 몇 개」를 알 방법이 없다.
	 */
	@Test
	void 엔딩_숫자는_회차를_가로질러_더해진다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "기록팀", List.of(FIRST, SECOND));
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 1, 30.0D);
		DamageLedger.record(TEAM_ID, "기록팀", SECOND, "둘째", 1, 5.0D);
		DamageLedger.record(TEAM_ID, "기록팀", SECOND, "둘째", 2, 40.0D);
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 1, "첫째", List.of("짐꾼", "파문"));
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 2, "첫째", List.of("성역"));
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 3, "둘째", List.of("살기", "완충", "호위"));

		DamageLedger.VictorySummary summary = DamageLedger.summaryFor(team);

		assertEquals("둘째", summary.topDamageName(), "45.0 로 둘째가 가장 많이 맞았다");
		assertEquals(45.0D, summary.topDamage(), 0.001D);
		assertEquals("첫째", summary.mostDeathsName());
		assertEquals(2, summary.mostDeaths());
		assertEquals(6, summary.totalPerks(), "2 + 1 + 3");
		assertTrue(summary.hasDamage());
		assertTrue(summary.hasDeaths());
	}

	/** 같은 회차를 두 번 적으면 나중 것이 이긴다. 쌓이면 「고른 증강」이 부풀어 오른다. */
	@Test
	void 같은_회차를_다시_적으면_덮어쓴다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "기록팀", List.of(FIRST));
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 1, "첫째", List.of("짐꾼", "파문"));
		DamageLedger.noteRunEnd(TEAM_ID, "기록팀", 1, null, List.of("성역"));

		DamageLedger.VictorySummary summary = DamageLedger.summaryFor(team);

		assertEquals(1, summary.totalPerks());
		assertEquals(0, summary.mostDeaths());
	}

	@Test
	void 기록이_없으면_엔딩_숫자도_비어_있다() {
		ShareTeam team = new ShareTeam(TEAM_ID, "빈팀", List.of(FIRST));

		DamageLedger.VictorySummary summary = DamageLedger.summaryFor(team);

		assertFalse(summary.hasDamage());
		assertFalse(summary.hasDeaths());
		assertEquals(0, summary.totalPerks());
	}

	// ------------------------------------------------------------------ 옛 파일

	/**
	 * 형식 번호가 낮은 옛 파일을 <b>버리지 않는다.</b>
	 *
	 * <p>여기서 버리면 지난 회차의 피해 기록이 통째로 사라진다. 늘어난 칸이 비어 있을 뿐이다.
	 */
	@Test
	void 옛_형식으로_적힌_파일도_그대로_읽는다(@TempDir Path directory) throws IOException {
		Path path = directory.resolve(DamageLedger.FILE_NAME);
		Files.writeString(path, """
				{
				  "formatVersion": 2,
				  "teams": {
				    "%s": {
				      "name": "옛팀",
				      "players": {
				        "%s": { "name": "첫째", "runs": { "1": 7.5 } }
				      }
				    }
				  }
				}
				""".formatted(TEAM_ID, FIRST), StandardCharsets.UTF_8);

		DamageLedger.loadForTesting(path);

		ShareTeam team = new ShareTeam(TEAM_ID, "옛팀", List.of(FIRST));
		assertTrue(String.join("\n", DamageLedger.buildPageTexts(team, 1)).contains("첫째 7.5"));
	}

	/** 앞으로 생길 형식은 읽지 않는다. 새 칸이 무슨 뜻인지 지금 코드는 모른다. */
	@Test
	void 앞선_형식으로_적힌_파일은_버린다(@TempDir Path directory) throws IOException {
		Path path = directory.resolve(DamageLedger.FILE_NAME);
		Files.writeString(path, """
				{
				  "formatVersion": 99,
				  "teams": {
				    "%s": {
				      "name": "미래팀",
				      "players": {
				        "%s": { "name": "첫째", "runs": { "1": 7.5 } }
				      }
				    }
				  }
				}
				""".formatted(TEAM_ID, FIRST), StandardCharsets.UTF_8);

		DamageLedger.loadForTesting(path);

		ShareTeam team = new ShareTeam(TEAM_ID, "미래팀", List.of(FIRST));
		String book = String.join("\n", DamageLedger.buildPageTexts(team, 1));
		assertFalse(book.contains("7.5"), "읽지 못한 파일의 값이 새어 나오면 안 된다");
		assertTrue(book.contains(FIRST.toString().substring(0, 8) + " 0.0"),
				"이름도 남지 않아 UUID 앞자리로 적힌다");
	}

	/**
	 * 마지막으로 받은 피해의 출처가 회차마다 남는다.
	 *
	 * <p>사망 알림을 끈 팀은 죽은 기록이 채팅에도 로그에도 남지 않아, 여기가
	 * <b>「무엇에 죽었나」를 아는 유일한 자리</b>다.
	 */
	@Test
	void 회차마다_마지막_피해의_출처가_남는다() {
		DamageLedger.noteSource(FIRST, "mob", "좀비");
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 1, 4.0D);
		DamageLedger.noteSource(FIRST, "creeper", "크리퍼");
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 1, 19.0D);

		// 같은 회차에 여러 번 맞으면 마지막 것만 남는다 — 전멸을 부른 것이 마지막 피해다.
		assertEquals("creeper (크리퍼) 19.0", DamageLedger.lastHitLine(TEAM_ID, FIRST, 1));
	}

	/** 가해자가 없는 피해(낙하·용암)는 괄호 없이 적힌다. */
	@Test
	void 가해자가_없으면_출처만_남는다() {
		DamageLedger.noteSource(FIRST, "fall", "");
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 2, 7.5D);

		assertEquals("fall 7.5", DamageLedger.lastHitLine(TEAM_ID, FIRST, 2));
	}

	/**
	 * 출처를 적어 두지 않고 기록만 하면 아무것도 남지 않는다.
	 *
	 * <p>「직전 피해의 출처가 엉뚱한 회차에 붙는」 일이 없어야 한다. 적어 둔 출처는 한 번
	 * 쓰이고 사라진다.
	 */
	@Test
	void 출처가_없으면_남지_않고_두_번_쓰이지도_않는다() {
		DamageLedger.noteSource(FIRST, "lava", "");
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 1, 3.0D);
		// 두 번째 기록에는 새 출처를 안 적었다 — 앞의 것이 따라오면 안 된다.
		DamageLedger.record(TEAM_ID, "기록팀", FIRST, "첫째", 2, 5.0D);

		assertEquals("lava 3.0", DamageLedger.lastHitLine(TEAM_ID, FIRST, 1));
		assertNull(DamageLedger.lastHitLine(TEAM_ID, FIRST, 2), "출처 없이 기록하면 비어 있어야 한다");
	}

	/** 기록이 아예 없는 회차와 사람은 조용히 null 이다. */
	@Test
	void 없는_기록은_조용히_비어_있다() {
		assertNull(DamageLedger.lastHitLine(TEAM_ID, FIRST, 9));
		assertNull(DamageLedger.lastHitLine(null, FIRST, 1));
		assertNull(DamageLedger.lastHitLine(TEAM_ID, null, 1));
	}
}
