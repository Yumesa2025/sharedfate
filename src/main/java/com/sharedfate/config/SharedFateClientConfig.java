package com.sharedfate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sharedfate.SharedFateMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 이 컴퓨터에만 저장하는 설정 — {@code config/sharedfate-client.json}.
 *
 * <p>서버 설정({@link SharedFateConfig})과 갈라 둔 까닭은 <b>사람마다 다른 취향</b>이기 때문이다.
 * 같은 팀이라도 한 사람은 발동 알림을 보고 싶고 다른 사람은 소리만 끄고 싶을 수 있다. 서버는
 * 이 값을 모른다 — 알림 패킷({@code PerkProcPayload})은 늘 그대로 보내고, 띄울지 울릴지는
 * 받는 쪽이 정한다. 그래서 통신 규약과도 무관하다.
 *
 * <p>읽고 쓰는 일만 여기에 두고 「어디에 있는 파일인가」는 클라이언트가 정한다. 그래야 월드
 * 없이 시험할 수 있다.
 *
 * <h2>망가진 파일</h2>
 * <p>JSON 이 아니거나 값의 꼴이 틀리면(예: {@code "procAlerts": "응"}) 경고를 남기고 기본값으로
 * 되돌린다. 원래 파일은 {@code .broken} 을 붙여 옆에 남겨 둔다 — 사람이 손으로 고치다 틀린 것이면
 * 무엇을 적었는지 다시 볼 수 있어야 한다.
 */
public final class SharedFateClientConfig {
	public static final String FILE_NAME = "sharedfate-client.json";
	/** 망가진 파일을 옮겨 둘 때 붙이는 꼬리. */
	public static final String BROKEN_SUFFIX = ".broken";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 증강 발동 알림 — 경험치 바 위의 「⟡ 급소만 노려」 글자. */
	public boolean procAlerts = true;
	/** 증강 발동 알림 — 그때 나는 작은 소리. */
	public boolean procSound = true;

	/**
	 * 읽는다. 파일이 없으면 기본값으로 만들고, 망가졌으면 옆으로 옮긴 뒤 기본값으로 다시 쓴다.
	 *
	 * <p>어떤 경우에도 예외를 던지지 않는다. 설정 하나 때문에 게임이 안 켜지면 안 된다.
	 */
	public static SharedFateClientConfig loadOrCreate(Path file) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				SharedFateClientConfig loaded = GSON.fromJson(reader, SharedFateClientConfig.class);
				if (loaded != null) {
					return loaded;
				}
				SharedFateMod.LOGGER.warn("클라이언트 설정 파일이 비어 있어 기본값으로 되돌립니다: {}", file);
			} catch (Exception e) {
				SharedFateMod.LOGGER.warn("클라이언트 설정 파일을 읽지 못해 기본값으로 되돌립니다: {}", file, e);
				setAside(file);
			}
		}

		SharedFateClientConfig config = new SharedFateClientConfig();
		config.trySave(file);
		return config;
	}

	/** 쓴다. 폴더가 없으면 만든다. */
	public void save(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		}
	}

	/**
	 * 쓰되 실패하면 경고만 남긴다. 게임 안 토글이 이것을 부른다 — 디스크가 꽉 찼다고 단추가
	 * 안 눌리면 안 된다. 그 판 동안은 바뀐 값이 그대로 살아 있다.
	 *
	 * @return 썼으면 true
	 */
	public boolean trySave(Path file) {
		try {
			save(file);
			return true;
		} catch (IOException e) {
			SharedFateMod.LOGGER.warn("클라이언트 설정 파일을 쓰지 못했습니다: {}", file, e);
			return false;
		}
	}

	/** 망가진 파일을 {@code .broken} 으로 옮긴다. 옮기지 못하면 그냥 덮어쓰게 둔다. */
	private static void setAside(Path file) {
		Path broken = file.resolveSibling(file.getFileName() + BROKEN_SUFFIX);
		try {
			Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			SharedFateMod.LOGGER.warn("망가진 클라이언트 설정 파일을 옮기지 못했습니다: {}", file, e);
		}
	}
}
