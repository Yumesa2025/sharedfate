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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 진행 중인 엔드 전투를 파일에 남긴다.
 *
 * <h2>왜 저장하는가</h2>
 *
 * <p>드래곤 체력 수정자는 개체에 붙어 <b>월드와 함께 저장된다.</b> 그런데 타이머와 누적 시련은
 * 메모리에만 있다. 전투 중에 서버가 내려갔다 올라오면 <b>체력 2000 짜리 드래곤에 시련 0 장</b>인
 * 어긋난 상태가 된다. 운영자가 {@code stop} 을 치는 일은 드물지 않다.
 *
 * <h2>⚠ 파일은 <b>월드 폴더</b>에 있다 (2026-10-18 검토)</h2>
 *
 * <p>전에는 서버 폴더(월드 밖)에 있었다. 루프 스크립트는 전멸·초기화 뒤 <b>월드 폴더만</b> 지우므로, 옛 회차의
 * 「일시 중단」 세션이 새 월드에서 되살아나 새 회차에 접속한 팀을 엔드로 끌고 가고 새 드래곤에 옛 카드·옛
 * 체력·최후의 저항까지 걸었다(팀 명부도 월드 밖이라 팀 id 가 그대로다). 싱글·LAN 에서는 월드 A 의 전투가
 * 월드 B 로 번질 수 있었다. 이제 {@link #locate} 가 월드 폴더 안을 가리키고, 서버 폴더에 남은 옛 파일은
 * 어느 월드의 것인지 알 수 없어 읽지 않고 지운다({@link #legacy}).
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
		/**
		 * 카드마다 받은 틱. 위험의 주기를 여기서부터 센다.
		 *
		 * <p>재시작마다 위상을 0 으로 되돌리면 「받자마자 예고 없이 맞는다」가 재시작할 때마다
		 * 되살아난다. 그래서 누적 목록과 같이 저장한다.
		 */
		public Map<String, Long> grantedTicks = new LinkedHashMap<>();
		/**
		 * 전투가 일시 중단된 채 저장되었는가(2026-10-18, {@code DragonTrialSuspend}). 엔드에 팀원이 없어
		 * 드래곤이 내려간 사이에 서버가 꺼져도, 돌아왔을 때 그 자리에서 잇는다.
		 */
		public boolean suspended;
		/**
		 * 입장 연출({@code TrialEntrance})의 원점. 없으면(옛 파일) {@code startedTick} 이다.
		 *
		 * <p>⚠ 칸 이름에 {@code entrance} 를 쓰지 않는다 — {@code TrialEntranceGateTest} 가 「입장 수락창은
		 * 저장되지 않는다」를 칸 이름으로 지킨다. 이 칸은 수락창이 아니라 연출의 시계다.
		 */
		public @Nullable Long introTick;
		/** 이을 때 입장 연출을 처음부터 다시 돌리는가. */
		public boolean replayIntro;
		/**
		 * 중단될 때의 드래곤 실제 체력. 없으면 모른다. ⚠ 싸우는 도중에는 적지 않는다 — 강제 종료 뒤에
		 * 낡은 값으로 되돌리면 드래곤이 회복한다.
		 */
		public @Nullable Float dragonHealth;
		/** 최후의 저항에서 끝까지 치른 오브젝트 파도 수. */
		public int lastStandWaves;
		/** 멈춘 틱. 없으면 모른다 — 이을 때 카드의 받은 틱을 밀지 않는다(2026-10-18 검토). */
		public @Nullable Long pausedTick;
		/** 최후의 저항 지대 시계가 멈출 때 어디까지 왔는가. 없으면 모른다. */
		public @Nullable Long lastStandClock;
		/** 한 번짜리 카드가 이미 한 일 — 위험 열쇠 → 값({@code DragonTrialSession.riskDone}). */
		public Map<String, Integer> riskDone = new LinkedHashMap<>();
	}

	private static final class Payload {
		List<Entry> sessions = new ArrayList<>();
	}

	private DragonTrialStore() {
	}

	/** 저장 파일 — 월드 폴더 안이다. 까닭은 클래스 설명에 있다. */
	public static Path locate(Path worldRoot) {
		return worldRoot.toAbsolutePath().normalize().resolve(FILE_NAME);
	}

	/**
	 * 0.42 이전 판이 쓰던 자리(서버 폴더). 저장 파일과 같은 자리면(월드 폴더가 곧 서버 폴더인 이상한 판)
	 * {@code null} 이다 — 그것까지 지우면 지금 파일을 지운다.
	 */
	public static @Nullable Path legacy(Path serverRoot, Path worldRoot) {
		Path old = serverRoot.toAbsolutePath().normalize().resolve(FILE_NAME);
		return old.equals(locate(worldRoot)) ? null : old;
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
					// 이 칸이 없던 옛 파일을 읽어도 빈 맵이어야 한다. null 이 그대로 나가면
					// 되살릴 때 터지고, 그러면 깨진 파일 하나에 서버 기동이 걸린다.
					Map<String, Long> marks = new LinkedHashMap<>();
					if (entry.grantedTicks != null) {
						for (Map.Entry<String, Long> mark : entry.grantedTicks.entrySet()) {
							String id = mark.getKey();
							Long tick = mark.getValue();
							if (id != null && !id.isBlank() && tick != null) {
								marks.put(id, tick);
							}
						}
					}
					entry.grantedTicks = marks;
					// 이 칸도 옛 파일에는 없다. null 이면 빈 맵이다.
					if (entry.riskDone == null) {
						entry.riskDone = new LinkedHashMap<>();
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
