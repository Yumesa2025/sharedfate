package com.sharedfate.client;

import com.sharedfate.config.SharedFateClientConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * 이 컴퓨터의 설정({@code config/sharedfate-client.json})을 들고 있다가 HUD 와 팀 화면에 건넨다.
 *
 * <p>읽고 쓰는 규칙은 {@link SharedFateClientConfig} 에 있다. 여기서는 파일 자리를 정하고, 게임
 * 안에서 단추를 누르면 곧바로 다시 쓴다. 모두 클라이언트 본 스레드에서만 부른다.
 */
public final class ClientPreferences {
	private static SharedFateClientConfig config = new SharedFateClientConfig();

	private ClientPreferences() {
	}

	/** 게임을 켤 때 한 번 읽는다. */
	public static void load() {
		config = SharedFateClientConfig.loadOrCreate(file());
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve(SharedFateClientConfig.FILE_NAME);
	}

	/** 증강 발동 알림 글자를 띄우는가. */
	public static boolean procAlerts() {
		return config.procAlerts;
	}

	/** 증강 발동 알림 소리를 내는가. */
	public static boolean procSound() {
		return config.procSound;
	}

	public static void toggleProcAlerts() {
		config.procAlerts = !config.procAlerts;
		config.trySave(file());
	}

	public static void toggleProcSound() {
		config.procSound = !config.procSound;
		config.trySave(file());
	}
}
