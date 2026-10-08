package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

public final class RunProgressManager {
	public static final String STATE_FILE_NAME = "sharedfate-run-state.json";
	/**
	 * 회차 표시 보스바의 식별자.
	 *
	 * <p>공개인 이유는 <b>클라이언트가 이 보스바만 골라내야 하기</b> 때문이다.
	 * {@code BossHealthOverlayMixin} 이 이 값으로 「내 보스바인가」를 판정해 막대 그리기만
	 * 건너뛴다. 이름이나 색으로 고르면 문구가 바뀔 때마다 조용히 어긋나므로 식별자로 고른다.
	 * 값 자체는 이름에서 만드는 고정값이라 서버와 클라이언트가 따로 계산해도 같지만, 두 곳에
	 * 적어 두면 한쪽만 고쳤을 때 알아챌 방법이 없어 여기 하나만 둔다.
	 */
	public static final UUID BOSS_EVENT_ID = UUID.nameUUIDFromBytes(
			"sharedfate:run-progress".getBytes(StandardCharsets.UTF_8));

	/** 보스바 문구를 다시 재는 주기. 사람이 단추를 누를 때만 바뀌는 값이라 1초면 충분하다. */
	private static final int BOSS_BAR_SCAN_INTERVAL_TICKS = 20;

	private static RunProgressState state;
	private static Path stateFile;
	private static ServerBossEvent bossBar;
	/** 마지막으로 보스바에 써 넣은 문구. 같으면 팀을 훑지도 패킷을 보내지도 않는다. */
	private static Component lastBossBarTitle;
	private static int bossBarCooldown;

	private RunProgressManager() {
	}

	public static void onServerStarted(MinecraftServer server) {
		DamageLedger.onServerStarted(server);
		stateFile = server.getServerDirectory().toAbsolutePath().normalize().resolve(STATE_FILE_NAME);
		try {
			state = RunProgressState.loadOrCreate(stateFile);
		} catch (IOException e) {
			state = RunProgressState.firstRun();
			SharedFateMod.LOGGER.error("회차 파일을 읽지 못해 메모리에서 1회차로 시작합니다: {}", stateFile, e);
		}
		// 운영자 초기화 표식이 있으면 여기서 회차를 1로 누른다. 보스바를 만들기 전이고
		// TeamRosterStore.onServerStarted 보다도 앞이라, 명단 복원이 이미 1회차를 본다.
		forceFirstRunIfRequested(server);
		if (SharedFateMod.config.showRunBossBar) {
			lastBossBarTitle = title(server);
			bossBar = new ServerBossEvent(BOSS_EVENT_ID, lastBossBarTitle, color(server),
					BossEvent.BossBarOverlay.PROGRESS);
			bossBar.setProgress(1.0F);
			bossBar.setVisible(true);
		}
		SharedFateMod.LOGGER.info(
				"[RUN] runNumber={} status={} winningTeam={}",
				state.runNumber(), state.status(), state.winningTeam());
	}

	public static void onPlayerJoin(ServerPlayer player) {
		if (bossBar != null) {
			bossBar.addPlayer(player);
		}
	}

	public static void onPlayerLeave(ServerPlayer player) {
		if (bossBar != null) {
			bossBar.removePlayer(player);
		}
	}

	public static boolean isVictory() {
		return state != null && state.isVictory();
	}

	public static int runNumber() {
		return state == null ? 1 : state.runNumber();
	}

	/**
	 * 운영자 초기화가 남긴 표식을 보고 회차를 1로 되돌린다.
	 *
	 * <p>루프 스크립트가 월드를 지운 뒤 {@code runNumber++} 를 하므로, 초기화 명령이 종료 전에
	 * 회차를 1로 적어 둘 수가 없다 — 적어 두면 다음 회차가 2가 된다. 스크립트는
	 * {@code runNumber < 1} 을 보면 예외를 던지고 죽으므로 0 을 심어 두는 길도 막혀 있다.
	 * 그래서 <b>다시 뜬 뒤인 지금</b> 누른다.
	 *
	 * <p>표식이 손상됐거나 남의 서버 것이면 {@link RunResetMarker#consume} 이 거짓을 돌려주고
	 * 로그만 남긴다. 그때 회차를 건드리면 남의 진행이 사라진다.
	 */
	private static void forceFirstRunIfRequested(MinecraftServer server) {
		if (!RunResetMarker.consume(server.getServerDirectory())) {
			return;
		}
		int previous = state.runNumber();
		state = RunProgressState.firstRun();
		try {
			state.save(stateFile);
		} catch (IOException e) {
			SharedFateMod.LOGGER.error("되돌린 회차를 저장하지 못했습니다: {}", stateFile, e);
		}
		SharedFateMod.LOGGER.warn(
				"[RUN] 초기화 표식을 보고 회차를 {}에서 1로 되돌렸습니다.", previous);
	}

	public static void onDeath(LivingEntity entity, DamageSource source) {
		if (!(entity instanceof EnderDragon dragon)
				|| SharedFateMod.config == null
				|| !SharedFateMod.config.dragonKillEndsRun
				|| isVictory()) {
			return;
		}

		MinecraftServer server = dragon.level().getServer();
		// 처치자를 찾는 일은 통째로 VictoryTeamResolver 가 한다. 「간접 처치면 아무도 못 찾아
		// 승리 책과 회차 기록이 통째로 빠진다」는 사고가 여기 있었다 — 왜 네 단계인지는 그
		// 클래스 문서에 적어 뒀다. 「아직 시작하지 않은 팀은 승리로 세지 않는다」도 그쪽으로
		// 옮겼다. 4단계가 이미 같은 규칙을 품고 있어, 두 곳에 두면 언젠가 어긋난다.
		VictoryTeamResolver.Resolution resolved =
				VictoryTeamResolver.resolve(server, dragon, source);
		if (!resolved.victory()) {
			SharedFateMod.LOGGER.info(
					"[RUN] 아직 시작하지 않은 팀 '{}' 의 드래곤 처치라 승리로 세지 않습니다.",
					resolved.teamName());
			return;
		}
		ShareTeam winningTeam = resolved.team();
		Player killer = VictoryTeamResolver.killerPlayer(server, resolved);
		String winningName = winningTeam != null
				? winningTeam.name()
				: killer != null ? killer.getPlainTextName() : "모험가";
		// 간접 처치는 로그만 보고는 원인을 알 수 없던 종류라 어느 단계에서 찾았는지 남긴다.
		SharedFateMod.LOGGER.info(
				"[RUN] 드래곤 처치자 판정: outcome={} team={} killer={}",
				resolved.outcome(), resolved.teamName(), resolved.killer());
		declareVictory(server, winningTeam, killer, winningName);
	}

	private static void declareVictory(
			MinecraftServer server, ShareTeam winningTeam, Player killer, String winningName) {
		state.markVictory(winningName);
		try {
			state.save(stateFile);
		} catch (IOException e) {
			SharedFateMod.LOGGER.error("승리 회차 상태를 저장하지 못했습니다: {}", stateFile, e);
		}
		WorldResetCoordinator.cancelPendingReset();
		// 회차 기록을 <b>책보다 먼저</b> 남긴다. 그래야 방금 끝낸 회차가 책과 엔딩에 함께 들어간다.
		if (winningTeam != null) {
			DamageLedger.noteRunEnd(winningTeam, state.runNumber(), null,
					RunPerkNames.of(TeamManager.get(server).stateByTeamId(winningTeam.teamId())));
		}
		DamageLedger.giveVictoryBooks(server, winningTeam, state.runNumber());
		refreshBossBar(server);
		server.getPlayerList().broadcastSystemMessage(Component.literal(
				"승리! '" + winningName + "' 팀이 " + state.runNumber() + "회차에서 엔더 드래곤을 처치했습니다!"),
				false);

		// 바닐라 엔딩 크레딧은 띄우지 않는다. 대신 폭죽과 함께 넘어가는 엔딩을 예약한다.
		Set<UUID> audience = celebrationAudience(server, winningTeam, killer);
		VictoryCelebration.start(audience, state.runNumber(), winningName,
				DamageLedger.summaryFor(winningTeam),
				SharedFateMod.config.victoryTitleDelayTicks,
				SharedFateMod.config.victoryFireworkDelayTicks);
		SharedFateMod.LOGGER.info(
				"[RUN] victory runNumber={} team={} celebrationPlayers={}",
				state.runNumber(), winningName, audience.size());
	}

	/** 승리 연출을 볼 사람들. 승리 팀이 있으면 그 팀, 없으면 처치자, 그마저 없으면 접속자 전원. */
	private static Set<UUID> celebrationAudience(
			MinecraftServer server, ShareTeam winningTeam, Player killer) {
		Set<UUID> audience = new LinkedHashSet<>();
		if (winningTeam != null) {
			audience.addAll(winningTeam.members());
		} else if (killer != null) {
			audience.add(killer.getUUID());
		} else {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				audience.add(player.getUUID());
			}
		}
		return audience;
	}

	public static void tick(MinecraftServer server) {
		VictoryCelebration.tick(server);
		if (++bossBarCooldown >= BOSS_BAR_SCAN_INTERVAL_TICKS) {
			bossBarCooldown = 0;
			refreshBossBar(server);
		}
	}

	/**
	 * 보스바 문구를 지금 있어야 할 모습으로 맞춘다.
	 *
	 * <p>「시작 대기」와 「진행 중」을 오가는 값은 사람이 단추를 누를 때 바뀌므로, 승리처럼 한
	 * 지점에서 알려 주는 대신 {@value #BOSS_BAR_SCAN_INTERVAL_TICKS} 틱마다 다시 잰다. 값이
	 * 그대로면 아무것도 보내지 않는다 — {@code ServerBossEvent.setName} 은 이름이 실제로
	 * 달라졌을 때만 패킷을 뿌리지만, 팀을 훑는 비용은 여기서 미리 아낀다.
	 */
	private static void refreshBossBar(@Nullable MinecraftServer server) {
		if (bossBar == null) {
			return;
		}
		Component next = title(server);
		if (next.equals(lastBossBarTitle)) {
			return;
		}
		lastBossBarTitle = next;
		bossBar.setName(next);
		bossBar.setColor(color(server));
	}

	private static Component title(@Nullable MinecraftServer server) {
		return Component.literal(label(runNumber(),
				state != null && state.isVictory(),
				state == null ? "" : state.winningTeam(),
				GameStartManager.anyTeamStarted(server)));
	}

	/**
	 * 보스바에 적을 한 줄.
	 *
	 * <p>시작 전에는 회차 번호와 함께 <b>무엇을 하면 회차가 시작되는지</b>까지 적는다.
	 *
	 * <p>보스바는 서버가 만들어 모두에게 같은 것을 뿌리므로 <b>보는 사람이 리더인지 알 수
	 * 없다.</b>
	 */
	static String label(int runNumber, boolean victory, @Nullable String winningTeam,
			boolean anyTeamStarted) {
		if (victory) {
			return "SharedFate · " + runNumber + "회차 · "
					+ (winningTeam == null ? "" : winningTeam) + " 승리!";
		}
		return anyTeamStarted
				? "SharedFate · " + runNumber + "회차 진행 중"
				: "SharedFate · " + runNumber + "회차 — 「게임 시작」을 눌러 주세요";
	}

	private static BossEvent.BossBarColor color(@Nullable MinecraftServer server) {
		if (isVictory()) {
			return BossEvent.BossBarColor.GREEN;
		}
		// 아직 아무도 시작하지 않았다는 사실이 색으로도 보여야 한다.
		return GameStartManager.anyTeamStarted(server)
				? BossEvent.BossBarColor.BLUE : BossEvent.BossBarColor.YELLOW;
	}

	public static void reset() {
		if (bossBar != null) {
			bossBar.removeAllPlayers();
		}
		bossBar = null;
		lastBossBarTitle = null;
		bossBarCooldown = 0;
		state = null;
		stateFile = null;
		VictoryCelebration.reset();
		FoodOverflowBuffer.resetRuntime();
		DamageLedger.resetRuntime();
	}
}
