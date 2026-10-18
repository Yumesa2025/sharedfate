package com.sharedfate.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 진행 중인 엔드 전투가 서버 재시작을 넘어 살아남는지 본다.
 *
 * <p>드래곤 체력 수정자는 개체에 붙어 월드와 함께 저장되지만 타이머와 누적은 메모리에만 있다.
 * 여기가 틀리면 체력 2000 짜리 드래곤에 시련 0 장인 어긋난 상태로 서버가 올라온다.
 */
class DragonTrialStoreTest {

	@Test
	void 쓰고_읽으면_그대로_돌아온다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = "cccccccc-0000-0000-0000-000000000001";
		entry.startedTick = 1234L;
		entry.chosen = new ArrayList<>(List.of("a", "b"));
		entry.fired = new ArrayList<>(List.of("ENTRY", "HEALTH_80"));
		entry.queued = new ArrayList<>(List.of("HEALTH_80"));
		entry.awaitingChoice = true;

		DragonTrialStore.save(file, List.of(entry));
		List<DragonTrialStore.Entry> loaded = DragonTrialStore.load(file);

		assertEquals(1, loaded.size());
		assertEquals("cccccccc-0000-0000-0000-000000000001", loaded.getFirst().teamId);
		assertEquals(1234L, loaded.getFirst().startedTick);
		assertEquals(List.of("a", "b"), loaded.getFirst().chosen);
		assertEquals(List.of("ENTRY", "HEALTH_80"), loaded.getFirst().fired);
		assertEquals(List.of("HEALTH_80"), loaded.getFirst().queued);
		assertTrue(loaded.getFirst().awaitingChoice);
	}

	@Test
	void 빈_목록을_쓰면_파일을_지운다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = "cccccccc-0000-0000-0000-000000000001";
		DragonTrialStore.save(file, List.of(entry));
		assertTrue(Files.exists(file));

		DragonTrialStore.save(file, List.of());

		assertFalse(Files.exists(file),
				"전투가 끝났는데 파일이 남으면 다음 서버가 유령 전투를 되살린다");
	}

	@Test
	void 파일이_없으면_빈_목록이다(@TempDir Path dir) {
		assertTrue(DragonTrialStore.load(dir.resolve("없는파일.json")).isEmpty());
	}

	@Test
	void 깨진_파일에_서버_기동을_걸지_않는다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file, "{ 이건 JSON 이 아니다 ", StandardCharsets.UTF_8);

		assertTrue(DragonTrialStore.load(file).isEmpty(),
				"전투 하나를 잃는 것이 서버가 안 뜨는 것보다 낫다");
	}

	@Test
	void 팀_식별자가_빈_줄은_버린다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file,
				"{\"sessions\":[{\"teamId\":\"\",\"startedTick\":1},"
						+ "{\"teamId\":\"cccccccc-0000-0000-0000-000000000001\",\"startedTick\":2}]}",
				StandardCharsets.UTF_8);

		List<DragonTrialStore.Entry> loaded = DragonTrialStore.load(file);

		assertEquals(1, loaded.size(), "식별자가 없으면 어느 팀 것인지 알 수 없다");
		assertEquals(2L, loaded.getFirst().startedTick);
	}

	@Test
	void 고른_목록이_비어_있어도_읽힌다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file,
				"{\"sessions\":[{\"teamId\":\"cccccccc-0000-0000-0000-000000000001\"}]}",
				StandardCharsets.UTF_8);

		List<DragonTrialStore.Entry> loaded = DragonTrialStore.load(file);

		assertEquals(1, loaded.size());
		assertTrue(loaded.getFirst().chosen.isEmpty(), "null 이 그대로 나오면 되살릴 때 터진다");
	}

	@Test
	void 되살린_세션이_줄을_이어받는다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = "cccccccc-0000-0000-0000-000000000001";
		entry.startedTick = 1000L;
		entry.chosen = new ArrayList<>(List.of("a"));
		entry.fired = new ArrayList<>(List.of("ENTRY", "HEALTH_80"));
		entry.queued = new ArrayList<>(List.of("HEALTH_80"));
		DragonTrialStore.save(file, List.of(entry));

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();
		DragonTrialSession session = new DragonTrialSession(
				java.util.UUID.fromString(loaded.teamId), loaded.startedTick, true);
		session.restore(loaded.chosen, loaded.fired, loaded.queued, loaded.awaitingChoice,
				loaded.grantedTicks);

		assertEquals(1, session.trialCount());
		assertTrue(session.shouldOfferTrial(), "줄에 남아 있던 자리는 재시작 뒤에도 떠야 한다");
		assertEquals(TrialCatalog.Trigger.HEALTH_80, session.peekTrigger());
	}

	@Test
	void 카드를_받은_틱이_저장되고_복원된다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = "cccccccc-0000-0000-0000-000000000001";
		entry.startedTick = 1000L;
		entry.chosen = new ArrayList<>(List.of("a", "b"));
		entry.grantedTicks = new LinkedHashMap<>(Map.of("a", 1100L, "b", 1730L));
		DragonTrialStore.save(file, List.of(entry));

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();

		assertEquals(Map.of("a", 1100L, "b", 1730L), loaded.grantedTicks);

		DragonTrialSession session = new DragonTrialSession(
				java.util.UUID.fromString(loaded.teamId), loaded.startedTick, true);
		session.restore(loaded.chosen, loaded.fired, loaded.queued, loaded.awaitingChoice,
				loaded.grantedTicks);

		assertEquals(1100L, session.grantedTick("a"));
		assertEquals(1730L, session.grantedTick("b"),
				"위상이 재시작마다 초기화되면 예고 없이 맞는 일이 재시작할 때마다 되돌아온다");
	}

	@Test
	void 받은_틱_칸이_없는_옛_파일도_읽힌다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file,
				"{\"sessions\":[{\"teamId\":\"cccccccc-0000-0000-0000-000000000001\","
						+ "\"startedTick\":1000,\"chosen\":[\"a\"]}]}",
				StandardCharsets.UTF_8);

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();

		assertNotNull(loaded.grantedTicks, "null 이 그대로 나가면 되살릴 때 터진다");
		assertTrue(loaded.grantedTicks.isEmpty(),
				"이 칸이 생기기 전에 저장된 파일 하나에 서버 기동을 걸 수는 없다");
	}

	@Test
	void 받은_틱의_빈_키와_빈_값은_버린다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file,
				"{\"sessions\":[{\"teamId\":\"cccccccc-0000-0000-0000-000000000001\","
						+ "\"startedTick\":1000,\"chosen\":[\"a\"],"
						+ "\"grantedTicks\":{\"a\":1100,\"b\":null,\"\":1200}}]}",
				StandardCharsets.UTF_8);

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();

		assertEquals(Map.of("a", 1100L), loaded.grantedTicks,
				"손으로 고친 파일이 들어와도 되살리는 쪽이 터지면 안 된다");
	}

	/**
	 * 일시 중단(2026-10-18)이 저장을 지난다 — 엔드가 빈 사이에 서버가 꺼져도 돌아오면 그 자리에서 잇는다.
	 */
	@Test
	void 일시_중단을_저장하고_되살린다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialSession before = new DragonTrialSession(
				java.util.UUID.fromString("cccccccc-0000-0000-0000-000000000002"), 1000L, true);
		before.suspend(true, 1777.5F, 1);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = before.teamId().toString();
		entry.startedTick = before.startedTick();
		entry.suspended = before.suspended();
		entry.introTick = before.entranceTick();
		entry.replayIntro = before.replayEntrance();
		entry.dragonHealth = before.dragonHealth();
		entry.lastStandWaves = before.lastStandWaves();
		DragonTrialStore.save(file, List.of(entry));

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();
		DragonTrialSession after = new DragonTrialSession(
				java.util.UUID.fromString(loaded.teamId), loaded.startedTick, true);
		after.restoreSuspension(loaded.suspended, loaded.introTick, loaded.replayIntro,
				loaded.dragonHealth, loaded.lastStandWaves);

		assertTrue(after.suspended(), "서버가 꺼졌다 켜지면 멈춘 전투가 싸우는 전투로 되살아난다");
		assertTrue(after.replayEntrance());
		assertEquals(1777.5F, after.dragonHealth(), 0.0F, "드래곤 실제 체력이 저장을 못 지난다");
		assertEquals(1, after.lastStandWaves());
		assertEquals(1000L, after.entranceTick());
	}

	/** 일시 중단 칸이 없는 옛 파일(0.41)도 읽힌다 — 싸우던 세션으로 되살아나고 첫 틱에 다시 판정한다. */
	@Test
	void 일시_중단_칸이_없는_옛_파일도_읽힌다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		Files.writeString(file,
				"{\"sessions\":[{\"teamId\":\"cccccccc-0000-0000-0000-000000000001\","
						+ "\"startedTick\":1000,\"chosen\":[\"a\"]}]}",
				StandardCharsets.UTF_8);

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();

		assertFalse(loaded.suspended);
		assertEquals(null, loaded.introTick);
		assertEquals(null, loaded.dragonHealth, "모르는 체력을 0 으로 읽으면 드래곤을 죽이는 값이 된다");
		assertEquals(0, loaded.lastStandWaves);
		assertEquals(null, loaded.pausedTick, "모르는 멈춘 틱을 0 으로 읽으면 받은 틱이 수십만 틱 튄다");
		assertEquals(null, loaded.lastStandClock);
		assertNotNull(loaded.riskDone, "옛 파일의 빈 칸이 null 로 나가 되살릴 때 터진다");
		assertTrue(loaded.riskDone.isEmpty());
	}

	/**
	 * ⚠⚠ 저장 파일은 <b>월드 폴더</b>에 있다(2026-10-18 검토). 서버 폴더에 있으면 루프 스크립트가 월드만 지운 새
	 * 회차에서 옛 회차의 일시 중단 전투가 되살아나 팀을 엔드로 끌고 갔다.
	 */
	@Test
	void 저장_파일은_월드_폴더에_있다(@TempDir Path dir) {
		Path server = dir.resolve("server");
		Path world = server.resolve("world");
		Path file = DragonTrialStore.locate(world.resolve("."));
		assertEquals(world.toAbsolutePath().normalize(), file.getParent(), "월드 밖에 저장한다 — 월드를 지워도 남는다");
		assertEquals(server.toAbsolutePath().normalize().resolve(DragonTrialStore.FILE_NAME),
				DragonTrialStore.legacy(server, world), "옛 자리(서버 폴더)를 못 찾는다 — 옛 파일이 남는다");
		assertEquals(null, DragonTrialStore.legacy(world, world), "옛 자리가 곧 지금 자리면 지금 파일을 지운다");
	}

	/** 멈춘 틱 · 지대 시계 · 한 번짜리 카드가 한 일이 저장을 지난다 — 재시작 뒤 부활이 또 살리지 않게. */
	@Test
	void 멈춘_틱과_한_일이_저장을_지난다(@TempDir Path dir) throws IOException {
		Path file = dir.resolve(DragonTrialStore.FILE_NAME);
		DragonTrialStore.Entry entry = new DragonTrialStore.Entry();
		entry.teamId = "cccccccc-0000-0000-0000-000000000003";
		entry.startedTick = 1000L;
		entry.suspended = true;
		entry.pausedTick = 5_100L;
		entry.lastStandClock = 1_234L;
		entry.riskDone = new LinkedHashMap<>(Map.of("sharedfate:night_host#0", 3));
		DragonTrialStore.save(file, List.of(entry));

		DragonTrialStore.Entry loaded = DragonTrialStore.load(file).getFirst();
		assertEquals(5_100L, loaded.pausedTick);
		assertEquals(1_234L, loaded.lastStandClock);
		assertEquals(3, loaded.riskDone.get("sharedfate:night_host#0"));
	}
}
