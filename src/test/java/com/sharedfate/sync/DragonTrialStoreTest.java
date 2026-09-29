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
				java.util.UUID.fromString(loaded.teamId), loaded.startedTick);
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
				java.util.UUID.fromString(loaded.teamId), loaded.startedTick);
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
}
