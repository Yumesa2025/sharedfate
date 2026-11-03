package com.sharedfate.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 컴퓨터에만 저장하는 설정({@code sharedfate-client.json}) — 읽기 · 쓰기 · 망가진 파일 되돌리기.
 */
class SharedFateClientConfigTest {
	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve(SharedFateClientConfig.FILE_NAME);
	}

	private Path broken() {
		return dir.resolve(SharedFateClientConfig.FILE_NAME + SharedFateClientConfig.BROKEN_SUFFIX);
	}

	private void write(String text) throws Exception {
		Files.writeString(file(), text, StandardCharsets.UTF_8);
	}

	/** 처음 켜면 파일이 없다. 둘 다 켠 기본값으로 만든다. */
	@Test
	void 파일이_없으면_기본값으로_만든다() {
		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(file());

		assertTrue(config.procAlerts);
		assertTrue(config.procSound);
		assertTrue(Files.exists(file()));
	}

	@Test
	void 폴더가_없어도_만든다() {
		Path nested = dir.resolve("config").resolve(SharedFateClientConfig.FILE_NAME);

		SharedFateClientConfig.loadOrCreate(nested);

		assertTrue(Files.exists(nested));
	}

	/** 게임 안에서 끈 것이 다음에 켤 때도 꺼져 있다. */
	@Test
	void 쓴_값을_그대로_다시_읽는다() throws Exception {
		SharedFateClientConfig config = new SharedFateClientConfig();
		config.procAlerts = false;
		config.procSound = true;
		config.save(file());

		SharedFateClientConfig loaded = SharedFateClientConfig.loadOrCreate(file());
		assertFalse(loaded.procAlerts);
		assertTrue(loaded.procSound);

		loaded.procSound = false;
		assertTrue(loaded.trySave(file()));
		assertFalse(SharedFateClientConfig.loadOrCreate(file()).procSound);
	}

	/** 사람이 손으로 하나만 적었으면 나머지는 기본값이다. */
	@Test
	void 빠진_칸은_기본값이다() throws Exception {
		write("{ \"procSound\": false }");

		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(file());

		assertTrue(config.procAlerts);
		assertFalse(config.procSound);
	}

	/** JSON 이 아니면 기본값으로 되돌리고, 원래 것은 .broken 으로 옆에 남긴다. */
	@Test
	void 망가진_파일은_기본값으로_되돌리고_옆에_남긴다() throws Exception {
		write("{ procAlerts: false, ");

		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(file());

		assertTrue(config.procAlerts);
		assertTrue(config.procSound);
		assertTrue(Files.exists(broken()), "망가진 원본");
		assertEquals("{ procAlerts: false, ", Files.readString(broken(), StandardCharsets.UTF_8));
		// 되돌린 파일은 다시 읽힌다.
		SharedFateClientConfig again = SharedFateClientConfig.loadOrCreate(file());
		assertTrue(again.procAlerts);
		assertTrue(again.procSound);
	}

	@Test
	void 값의_꼴이_틀려도_기본값으로_되돌린다() throws Exception {
		write("{ \"procAlerts\": [1, 2], \"procSound\": false }");

		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(file());

		assertTrue(config.procAlerts);
		assertTrue(config.procSound, "한 칸이라도 틀리면 통째로 기본값");
		assertTrue(Files.exists(broken()));
	}

	@Test
	void 빈_파일은_기본값으로_다시_쓴다() throws Exception {
		write("");

		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(file());

		assertTrue(config.procAlerts);
		assertTrue(config.procSound);
		assertTrue(Files.readString(file(), StandardCharsets.UTF_8).contains("procAlerts"));
	}

	/** 쓸 수 없는 자리(설정 폴더 자리를 파일이 차지)여도 예외 없이 기본값을 돌려준다. */
	@Test
	void 쓸_수_없어도_게임은_켜진다() throws Exception {
		Path blocker = dir.resolve("blocker");
		Files.writeString(blocker, "", StandardCharsets.UTF_8);
		Path unwritable = blocker.resolve(SharedFateClientConfig.FILE_NAME);

		SharedFateClientConfig config = SharedFateClientConfig.loadOrCreate(unwritable);

		assertTrue(config.procAlerts);
		assertFalse(config.trySave(unwritable));
	}
}
