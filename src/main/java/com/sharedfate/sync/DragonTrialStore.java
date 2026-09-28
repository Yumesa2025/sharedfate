package com.sharedfate.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 진행 중인 엔드 전투를 파일에 남긴다.
 *
 * <h2>왜 저장하는가</h2>
 *
 * <p>드래곤 체력 수정자는 개체에 붙어 <b>월드와 함께 저장된다.</b> 그런데 타이머와 누적 시련은
 * 메모리에만 있다. 전투 중에 서버가 내려갔다 올라오면 <b>체력 2000 짜리 드래곤에 시련 0 장</b>인
 * 어긋난 상태가 된다. 운영자가 {@code stop} 을 치는 일은 드물지 않다.
 *
 * <h2>파일 하나에 팀 하나</h2>
 *
 * <p>{@code singleTeamOnly} 가 기본이라 대개 하나지만, 목록으로 두면 팀이 여럿인 서버에서도
 * 그대로 돈다.
 *
 * <p>쓰기는 임시 파일에 쓰고 옮기는 방식이다. 쓰는 도중에 서버가 죽어도 반쯤 쓰인 파일이
 * 남지 않는다 — {@code RunProgressState} 와 같은 방식이다.
 */
public final class DragonTrialStore {
	public static final String FILE_NAME = "sharedfate-dragon-trial.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 저장되는 한 줄. 필드 이름이 곧 JSON 키라 함부로 바꾸면 옛 파일을 못 읽는다. */
	public static final class Entry {
		public String teamId = "";
		public long startedTick;
		public List<String> chosen = new ArrayList<>();
		/** 이미 터진 자리들. 같은 자리를 다시 세지 않으려고 남긴다. */
		public List<String> fired = new ArrayList<>();
		/** 터졌지만 아직 고르지 않은 자리들. 순서가 곧 줄이다. */
		public List<String> queued = new ArrayList<>();
		public boolean awaitingChoice;
	}

	private static final class Payload {
		List<Entry> sessions = new ArrayList<>();
	}

	private DragonTrialStore() {
	}

	/**
	 * 파일을 읽는다. 없거나 깨졌으면 빈 목록이다.
	 *
	 * <p>깨진 파일에 서버 기동을 걸지 않는다. 전투 하나를 잃는 것이 서버가 안 뜨는 것보다 낫다.
	 */
	public static List<Entry> load(@Nullable Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return List.of();
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			Payload payload = GSON.fromJson(reader, Payload.class);
			if (payload == null || payload.sessions == null) {
				return List.of();
			}
			List<Entry> clean = new ArrayList<>();
			for (Entry entry : payload.sessions) {
				if (entry != null && entry.teamId != null && !entry.teamId.isBlank()) {
					if (entry.chosen == null) {
						entry.chosen = new ArrayList<>();
					}
					if (entry.fired == null) {
						entry.fired = new ArrayList<>();
					}
					if (entry.queued == null) {
						entry.queued = new ArrayList<>();
					}
					clean.add(entry);
				}
			}
			return clean;
		} catch (IOException | JsonSyntaxException error) {
			return List.of();
		}
	}

	/** 목록을 파일에 쓴다. 비어 있으면 파일을 지운다 — 남겨 두면 다음 서버가 유령 전투를 되살린다. */
	public static void save(@Nullable Path file, @Nullable List<Entry> sessions) throws IOException {
		if (file == null) {
			return;
		}
		if (sessions == null || sessions.isEmpty()) {
			Files.deleteIfExists(file);
			return;
		}
		Payload payload = new Payload();
		payload.sessions = new ArrayList<>(sessions);

		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			GSON.toJson(payload, writer);
		}
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException fallback) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}
}
