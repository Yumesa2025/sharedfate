package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.net.WorldResetPayload;
import com.sharedfate.ui.GameOverCountdown;
import com.sharedfate.ui.RunResetMessages;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 팀이 전멸했을 때의 게임 오버 연출과 서버 종료.
 *
 * <h2>흐름</h2>
 * <ol>
 *   <li>{@code DeathHandler} 가 팀을 전부 죽이고 {@link #request} 를 부른다.</li>
 *   <li>화면에 <b>「게임 오버 · N회차」</b>가 크게 뜨고({@code GameOverClientDisplay}) 그 아래에서
 *       <b>빨간 숫자</b>가 5부터 1까지 내려간다({@code GameOverHud}).</li>
 *   <li>숫자가 다 내려가면 <b>표식 파일만 남기고 서버를 정상 종료</b>한다.</li>
 *   <li>월드를 지우고 서버를 다시 여는 일은 <b>재시작 루프 스크립트</b>가 한다
 *       ({@code sharedfate-server-loop.ps1}). 이 모드는 월드를 직접 지우지 않는다.</li>
 * </ol>
 *
 * <p>5초가 지나면 표식을 쓰고 {@code halt} 한다. 그래야 다음 회차가 정상적으로 열린다.
 *
 * <p>카운트다운 숫자는 <b>클라이언트가 자기 자리에 직접 그린다</b> — {@code GameOverHud} 가
 * 사망 화면의 제목(y=30)과 사인 줄(y=85) 사이의 빈 자리에 그리므로 어떤 GUI 크기에서도
 * 가려지지 않는다. 서버는 남은 길이를 {@code WorldResetPayload} 로 한 번 보내고 클라이언트가
 * 그 길이를 스스로 세어 내려간다.
 *
 * <h2>카운트다운 동안 멈추는 것</h2>
 * <ul>
 *   <li><b>피해</b> — {@link GameStartManager#blocksDamage} 가 {@link #countingDown()} 을 보고
 *       모든 피해를 버린다. 이미 예약된 「폭발 교환」이 종료 직전에 터져도 아무 일도 없다.</li>
 *   <li><b>위치 교환·정거장</b> — 각 {@code tick} 이 건너뛴다. 종료 직전에 자리가 뒤바뀌면
 *       월드를 지우는 서버에서는 뜻이 없고, 지우지 않는 서버에서는 벽 속에 박힌 채 저장된다.</li>
 *   <li><b>난이도 상승 시간</b> — 세지 않는다. 회차는 이미 끝났다.</li>
 * </ul>
 * <p><b>이동은 막지 않는다.</b> 전멸한 팀원은 이미 사망 화면에 갇혀 움직일 수 없다.
 */
public final class WorldResetCoordinator {
	public static final String MARKER_FILE_NAME = ".sharedfate-world-reset.pending";
	public static final String MARKER_HEADER = "sharedfate-world-reset-v1";
	/** 초기화 경로가 로그에 쓰는 이름. 전멸 경로의 팀 이름 자리에 들어간다. */
	private static final String RUN_RESET_LABEL = "운영자 초기화";

	private static MinecraftServer pendingServer;
	private static int ticksRemaining;
	private static String pendingTeamName;
	/**
	 * 지금 예약된 종료가 <b>운영자 초기화</b>인가.
	 *
	 * <p>{@link #tick} 이 실패했을 때 회차 표식을 되돌릴지 정하는 근거다. 전멸 경로에는 회차
	 * 표식이 아예 없으므로 그때 지우려 들면 안 된다.
	 *
	 * <p><b>{@link #pendingTeamName} 으로 가르지 않는다.</b> 그 값은 화면과 로그에 적는 이름이라
	 * 팀 이름이 {@link #RUN_RESET_LABEL} 과 똑같으면 두 경로가 섞인다. 판단의 근거를 표시용
	 * 문자열에 얹으면 이름이 바뀌는 날 조용히 틀린다.
	 *
	 * <p>클라이언트에 보내는 {@link GameOverCountdown.Reason} 과 묻는 것이 다르다. 저쪽은
	 * 「화면에 무슨 글자를 적을까」이고 이쪽은 「회차 표식을 거둬야 할까」다. 값을 여기서
	 * {@link #arm} 이 한 번만 뽑아 두므로 둘이 어긋날 자리는 없다.
	 */
	private static boolean pendingRunReset;

	private WorldResetCoordinator() {
	}

	public static void onServerStarted(MinecraftServer server) {
		if (SharedFateMod.config.resetWorldOnTeamDeath && !server.isHardcore()) {
			SharedFateMod.LOGGER.warn(
					"resetWorldOnTeamDeath가 켜져 있지만 server.properties의 hardcore가 false입니다. "
							+ "월드 초기화는 동작하지만 사망 화면 제목은 '게임 오버'가 아닙니다.");
		}
		warnOnStaleMarker(server);
	}

	/**
	 * 지난 회차의 월드 표식이 남아 있으면 <b>시끄럽게</b> 남긴다.
	 *
	 * <h2>지우지 않는다</h2>
	 * <p>이 표식은 루프 스크립트에게 「이 월드를 지워라」고 말하는 물건이다. 모드가 멋대로
	 * 없애면 <b>전멸하고도 월드가 안 지워진 채 다음 회차가 열린다</b> — 표식을 쓰고 종료한 뒤
	 * 스크립트가 읽기 전에 서버가 한 번 더 뜨는 순서가 실제로 가능하다({@code -nogui} 로 손수
	 * 띄우는 경우). 그래서 알리기만 한다.
	 *
	 * <h2>왜 이 자리에 필요한가</h2>
	 * <p>모드는 여태 이 파일을 <b>쓰기만 하고 읽지도 치우지도 않았다.</b> 한 번 남으면 루프
	 * 스크립트가 기동 자체를 거부하므로 <b>서버가 영영 안 뜬다</b> — java 프로세스가 하나도 뜨지
	 * 않는다. 그 상태의 운영자가 {@code java -jar} 로 손수 띄워 로그를 보는 것이 유일한 실마리라,
	 * 그 로그에 <b>지워야 할 파일의 경로</b>가 반드시 있어야 한다.
	 */
	private static void warnOnStaleMarker(MinecraftServer server) {
		Path marker = markerFile(server.getServerDirectory());
		if (!Files.exists(marker)) {
			return;
		}
		SharedFateMod.LOGGER.error(
				"지난 월드 초기화의 표식이 남아 있습니다. 이 파일이 있는 동안에는 재시작 루프"
						+ " 스크립트가 서버를 켜지 않고, /shareteam reset 도 거절합니다."
						+ " 월드를 지울 생각이 없다면 손으로 지워 주세요: {}", marker);
	}

	/**
	 * 월드 표식 파일의 자리. 루프 스크립트가 보는 바로 그 경로다.
	 *
	 * <p>{@link RunResetMarker#markerFile} 과 같은 모양으로 맞춰 둔다. 이 경로를 만드는 곳이
	 * 여럿으로 갈라지면 <b>쓰는 자리와 보는 자리가 어긋나도</b> 아무도 모른다.
	 */
	public static Path markerFile(Path serverDirectory) {
		return serverDirectory.toAbsolutePath().normalize().resolve(MARKER_FILE_NAME);
	}

	public static void request(MinecraftServer server, ShareTeam team) {
		if (SharedFateMod.config == null || !SharedFateMod.config.resetWorldOnTeamDeath
				|| RunProgressManager.isVictory()) {
			return;
		}
		request(server, team.name());
	}

	public static void cancelPendingReset() {
		clearPending();
	}

	/** 예약을 지운다. <b>표식 되돌림 여부까지 한자리에서</b> 비워야 다음 예약에 새지 않는다. */
	private static void clearPending() {
		pendingServer = null;
		ticksRemaining = 0;
		pendingTeamName = null;
		pendingRunReset = false;
	}

	/**
	 * 게임 오버 카운트다운이 도는 중인가.
	 *
	 * <p>이 값이 참인 동안 피해와 위치 교환과 난이도 시간이 멈춘다. 부르는 곳은 클래스 문서의
	 * 「카운트다운 동안 멈추는 것」에 적어 뒀다.
	 */
	public static boolean countingDown() {
		return pendingServer != null && ticksRemaining > 0;
	}

	/**
	 * 운영자 초기화 명령이 쓰는 진입점.
	 *
	 * <p>{@link #request(MinecraftServer, ShareTeam)} 와 달리 {@code resetWorldOnTeamDeath}
	 * 설정도 승리 여부도 보지 않는다. 그 둘은 「전멸로 월드를 갈아엎을 것인가」에 대한 답이지
	 * 「운영자가 서버를 초기화할 수 있는가」에 대한 답이 아니다. 연출과 종료 절차는 전멸 경로와
	 * <b>똑같은 것을 그대로</b> 쓴다 — 카운트다운이 끝나면 {@link #tick} 이 명단을 저장하고
	 * 표식을 남기고 서버를 내린다.
	 *
	 * @return 걸린 카운트다운 길이(틱). 이미 예약된 종료가 있어 걸지 못했으면 0
	 */
	public static int requestRunReset(MinecraftServer server) {
		if (pendingServer != null) {
			return 0;
		}
		// 설정을 아직 못 읽었어도 초기화는 돌아야 한다. 그때는 모드 기본값 5초로 센다.
		int delayTicks = SharedFateMod.config == null
				? GameOverCountdown.DEFAULT_SECONDS * GameOverCountdown.TICKS_PER_SECOND
				: SharedFateMod.config.worldResetDelayTicks;
		arm(server, RUN_RESET_LABEL, delayTicks,
				RunResetMessages.announcement(GameOverCountdown.secondsRemaining(delayTicks)),
				GameOverCountdown.Reason.RUN_RESET);
		SharedFateMod.LOGGER.warn(
				"운영자 초기화로 서버 종료를 예약했습니다: delayTicks={}", delayTicks);
		return delayTicks;
	}

	private static void request(MinecraftServer server, String teamName) {
		if (pendingServer != null) {
			return;
		}
		int delayTicks = SharedFateMod.config.worldResetDelayTicks;
		int seconds = GameOverCountdown.secondsRemaining(delayTicks);
		arm(server, teamName, delayTicks, GameOverCountdown.wipeAnnouncement(teamName, seconds),
				GameOverCountdown.Reason.TEAM_WIPE);
		SharedFateMod.LOGGER.warn(
				"팀 전멸로 월드 초기화를 예약했습니다: team={}, delayTicks={}", teamName, delayTicks);
	}

	/**
	 * 카운트다운을 실제로 건다. 전멸 경로와 초기화 경로가 <b>같은 절차</b>를 쓰게 하는 자리다.
	 *
	 * <p>여기가 갈라지면 한쪽만 고쳤을 때 다른 쪽이 조용히 다르게 돈다. 클라이언트는
	 * {@code WorldResetPayload} 로 받은 길이를 스스로 세어 내려간다.
	 *
	 * <p><b>절차는 같아도 화면에 적는 글자는 달라야 한다.</b> 그래서 {@code reason} 을 묶음에
	 * 실어 보낸다 — 회차도 남은 틱도 두 경로가 똑같이 채우므로 받는 쪽에는 가를 근거가 없다.
	 * 이 칸이 없던 동안 운영자가 서버를 초기화하면 살아 있는 팀원 전원의 화면에
	 * 「게임 오버」가 떴다.
	 *
	 * @param reason 이것이 <b>운영자 초기화</b> 경로인가 전멸 경로인가. 두 곳에 쓰인다 —
	 *               클라이언트가 그릴 글자를 고르는 근거이자, {@link #tick} 이 실패했을 때
	 *               회차 표식을 되돌릴지 정하는 근거다. 전멸 경로에는 회차 표식이 없다
	 */
	private static void arm(MinecraftServer server, String label, int delayTicks,
			String announcement, GameOverCountdown.Reason reason) {
		pendingServer = server;
		ticksRemaining = delayTicks;
		pendingTeamName = label;
		pendingRunReset = reason == GameOverCountdown.Reason.RUN_RESET;
		WorldResetPayload payload = new WorldResetPayload(
				RunProgressManager.runNumber(), ticksRemaining, reason);
		for (var player : server.getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}
		server.getPlayerList().broadcastSystemMessage(Component.literal(announcement), false);
	}

	public static void tick(MinecraftServer server) {
		if (pendingServer != server) {
			return;
		}
		if (--ticksRemaining > 0) {
			return;
		}

		try {
			Path serverDirectory = server.getServerDirectory();
			Path worldDirectory = validateWorldDirectory(
					serverDirectory, server.getWorldPath(LevelResource.ROOT));
			TeamRosterStore.saveCurrent(server);
			Path marker = markerFile(serverDirectory);
			Files.writeString(marker, markerContents(worldDirectory), StandardCharsets.UTF_8,
					StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
			SharedFateMod.LOGGER.warn(
					"월드 초기화 표식을 기록했습니다. 정상 종료 후 재시작 스크립트가 삭제합니다: {}",
					worldDirectory);
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					"새 월드를 만들기 위해 서버를 재시작합니다."), false);
			clearPending();
			// 엔드 전투를 일시 중단으로 남기지 않는다 — 내려가는 길(SERVER_STOPPING)이 싸우던 전투를 멈춘 채 저장하면
			// 새 회차에서 되살아나 팀을 엔드로 끌고 갔다(2026-10-18 검토). 표식을 쓴 뒤라 실패한 초기화는 여기 오지 않는다.
			com.sharedfate.sync.DragonTrialManager.discardForWorldReset(server);
			server.halt(false);
		} catch (IOException | IllegalArgumentException e) {
			SharedFateMod.LOGGER.error(
					"월드 초기화 표식을 만들지 못해 서버를 계속 실행합니다: team={}", pendingTeamName, e);
			server.getPlayerList().broadcastSystemMessage(Component.literal(
					"월드 초기화 준비에 실패했습니다. 운영자가 서버 로그를 확인해 주세요."), false);
			rollbackRunResetMarker(server);
			clearPending();
		}
	}

	/**
	 * 초기화 경로가 여기서 실패했으면 <b>회차 표식을 거둔다.</b>
	 *
	 * <p>초기화는 회차 표식을 먼저 쓰고 5초 뒤에 월드 표식을 쓴다. 뒤쪽이 실패하면 서버는
	 * 내려가지 않는데, 회차 표식을 그대로 두면 <b>다음에 서버를 켤 때 멀쩡한 회차가 1로
	 * 눌린다.</b> 초기화는 일어나지도 않았는데 회차만 사라지는 것이다.
	 *
	 * <p>전멸 경로에는 회차 표식이 없다. 그쪽에서 이 일을 하면 운영자가 <b>방금 따로 걸어 둔</b>
	 * 초기화 표식을 남의 실패가 지워 버릴 수 있다. 그래서 {@link #pendingRunReset} 로 가른다.
	 */
	private static void rollbackRunResetMarker(MinecraftServer server) {
		if (!pendingRunReset) {
			return;
		}
		Path serverDirectory = server.getServerDirectory();
		if (RunResetMarker.rollback(serverDirectory)) {
			SharedFateMod.LOGGER.warn(
					"초기화가 중간에 멈춰 회차 되돌림 표식을 거뒀습니다. 회차는 그대로 이어집니다: {}",
					serverDirectory.toAbsolutePath().normalize());
		}
	}

	static Path validateWorldDirectory(Path serverDirectory, Path worldDirectory) {
		Path root = serverDirectory.toAbsolutePath().normalize();
		Path world = worldDirectory.toAbsolutePath().normalize();
		if (world.equals(root) || world.getParent() == null || !world.getParent().equals(root)) {
			throw new IllegalArgumentException("월드 폴더는 서버 루트 바로 아래여야 합니다: " + world);
		}
		return world;
	}

	static String markerContents(Path worldDirectory) {
		return MARKER_HEADER + System.lineSeparator()
				+ worldDirectory.toAbsolutePath().normalize() + System.lineSeparator();
	}

	public static void reset() {
		clearPending();
	}
}
