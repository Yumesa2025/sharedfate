package com.sharedfate.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sharedfate.SharedFateMod;
import com.sharedfate.ui.GameOverCountdown;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class SharedFateConfig {
	public static final int NETWORK_MAX_TEAM_SIZE = 16;
	public static final double MIN_SHARED_MAX_HEALTH = 1.0;
	public static final double MAX_SHARED_MAX_HEALTH = 1024.0;
	public static final int MIN_WORLD_RESET_DELAY_TICKS = 40;
	public static final int MAX_WORLD_RESET_DELAY_TICKS = 1200;
	/** 승리 연출 각 단계의 지연 범위(틱). 0이면 바로 다음 틱에 넘어간다. */
	public static final int MIN_VICTORY_STAGE_TICKS = 0;
	public static final int MAX_VICTORY_STAGE_TICKS = 1200;
	/** 위치 교환 카운트다운 최대 길이(초). */
	public static final int MAX_POSITION_SWAP_COUNTDOWN_SECONDS = 30;
	/**
	 * 경험치 획득 배율의 허용 범위.
	 *
	 * <p>0 이면 경험치가 아예 나오지 않아 마법·수선이 통째로 막히므로 아래를 열어 두지 않는다.
	 * 위쪽은 10배면 이미 「경험치가 의미 없는 서버」라, 오타로 적은 큰 값을 걸러 내는 선이다.
	 */
	public static final double MIN_EXPERIENCE_MULTIPLIER = 0.1;
	public static final double MAX_EXPERIENCE_MULTIPLIER = 10.0;
	public static final double DEFAULT_EXPERIENCE_MULTIPLIER = 1.2;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int maxTeamSize = 4;
	/**
	 * 서버에 팀을 하나만 두도록 제한한다.
	 * 켜져 있으면 이미 팀이 있을 때 /shareteam create 가 거부된다.
	 * 이미 만들어져 있는 팀은 건드리지 않고, 새로 만드는 것만 막는다.
	 * 몹 증강처럼 월드 전체에 걸리는 효과가 팀끼리 충돌하는 것을 막기 위한 기본값이다.
	 */
	public boolean singleTeamOnly = true;
	public double sharedMaxHealth = 20.0;
	public int mainInventoryRows = 6;
	public boolean shareEnderChest = true;
	public boolean shareExperience = true;
	public boolean shareStatusEffects = true;
	/**
	 * 게임 전체에서 얻는 경험치에 곱하는 배율. 기본 1.2배.
	 *
	 * <p><b>증강이 아니라 상시 규칙이다.</b> 팀이 무엇을 골랐든, 증강을 쓰지 않는 팀이든 똑같이
	 * 걸린다. 회차가 바뀌어도, 월드를 새로 만들어도 그대로 유지된다.
	 *
	 * <p>거는 자리는 {@code ExperienceOrbAwardMixin} 이고 규칙 자체는
	 * {@link com.sharedfate.sync.ExperienceBonus} 에 적어 뒀다.
	 */
	public double experienceMultiplier = DEFAULT_EXPERIENCE_MULTIPLIER;
	public int damageAlertDurationTicks = 30;
	public boolean requireClientMod = true;
	public boolean resetWorldOnTeamDeath = true;
	/**
	 * 팀이 전멸한 순간부터 서버가 종료될 때까지의 시간(틱). <b>화면에 뜨는 게임 오버
	 * 카운트다운의 길이</b>가 곧 이 값이다. 100틱 = 5초.
	 *
	 * <p>이 시간이 지나면 표식 파일을 남기고 서버를 정상 종료하며, 월드를 지우고 다시 여는 일은
	 * 재시작 루프 스크립트가 한다. 자세한 것은
	 * {@link com.sharedfate.sync.WorldResetCoordinator} 에 적어 뒀다.
	 */
	public int worldResetDelayTicks = 100;
	/**
	 * 엔더 드래곤 최대 체력을 <b>팀 인원 한 명당</b> 얼마로 할 것인가. 0 이면 강화하지 않는다.
	 *
	 * <p>바닐라는 200 이다. 인원 비례인 이유는 공유 체력이 <b>방어에서는 인원을 불리하게</b>
	 * 만들지만(맞을 기회가 늘어난다) <b>공격에서는 인원이 그대로 유리</b>하기 때문이다. 네 명이
	 * 때리면 DPS 가 네 배다. 체력이 고정이면 4인 전투가 3인보다 짧아져 시련을 덜 본다.
	 *
	 * <p>인원은 접속자가 아니라 <b>팀 명단</b>으로 센다. 접속 인원으로 세면 전투 중 누가 끊겼을
	 * 때 드래곤 체력이 줄어든다.
	 *
	 * <p><b>이 값은 난이도가 아니라 전투 길이를 정한다.</b> 어려움은 시련이 담당한다.
	 *
	 * <p>⚠ <b>팀 설정에서 「드래곤 시련」을 끈 팀에게는 이 값이 아예 읽히지 않는다.</b> 그 팀의
	 * 드래곤은 바닐라 최대 체력 200 그대로이고, 체력 수정자를 붙였다 떼는 것이 아니라 처음부터
	 * 붙지 않는다({@code com.sharedfate.sync.DragonTrialManager#startSession}). 게다가
	 * <b>시련은 기본값이 끔</b>이라, 팀을 만들면서 켜 주지 않으면 이 값을 아무리 올려도 드래곤이
	 * 200 에서 꿈쩍하지 않는다 — 「왜 안 먹지」의 답은 대개 서버 설정이 아니라 그 팀의 설정이다.
	 * 지금 팀이 켰는지는 {@code /shareteam status} 의 「드래곤 시련」 줄에서 볼 수 있다.
	 *
	 * <p>여기에 <b>0</b> 을 적는 것은 그것과 다른 이야기다. 그때는 팀 설정과 무관하게 엔드 전투
	 * 지휘가 통째로 멈춰 <b>전원 소환도 일어나지 않는다.</b>
	 */
	public int dragonHealthPerMember = 600;
	/**
	 * 시련이 나오는 자리는 여섯 곳으로 고정이다 — 엔드 입장, 첫 크리스탈, 크리스탈 전멸,
	 * 드래곤 체력 80·50·30%.
	 *
	 * <p>시간이 아니라 진행도로 세므로 간격 설정이 없다. 체력 막대만 보면 다음 시련이 언제 올지
	 * 알 수 있고, 빨리 깎을수록 시련을 빨리 받는다.
	 */
	/**
	 * 발전과제(도전 과제) 달성을 채팅에 뿌리지 않는다.
	 *
	 * <p>바닐라 게임 규칙 {@code show_advancement_messages}(예전 이름
	 * {@code announceAdvancements})를 <b>서버가 뜰 때마다</b> 끈다. 회차가 바뀌어 월드가 새로
	 * 만들어져도 유지된다. 자세한 것은 {@link com.sharedfate.sync.WorldGameRules} 에 적어 뒀다.
	 */
	public boolean silenceAdvancementMessages = true;
	/**
	 * 화면 맨 위에 「SharedFate · N회차 …」 한 줄을 띄운다.
	 *
	 * <p>속은 보스바지만 <b>막대는 보이지 않는다</b> — 글자만 남기고 막대를 건너뛰는 일은
	 * 클라이언트의 {@code BossHealthOverlayMixin} 이 한다. 이름을 그대로 두는 이유는 설정
	 * 파일 호환이고, 여기를 끄면 보스바 자체가 만들어지지 않아 글자도 함께 사라진다.
	 *
	 * <p>모드를 깔지 않은 클라이언트에게는 막대가 그대로 보인다. 다만 {@code requireClientMod}
	 * 가 그런 접속을 기본적으로 막는다.
	 */
	public boolean showRunBossBar = true;
	public boolean dragonKillEndsRun = true;
	/**
	 * 엔딩 크레딧 연출용 값. 크레딧을 띄우지 않으므로 쓰이지 않는다.
	 * 기존 설정 파일과의 호환을 위해 필드만 남겨 둔다.
	 */
	public int victoryCreditsDelayTicks = 100;
	/**
	 * 드래곤 처치 후 엔딩의 첫 장이 뜰 때까지의 지연(틱). 200틱 = 10초.
	 *
	 * <p>처치 직후에는 승리 채팅과 책 지급이 쏟아지고 드래곤이 아직 떨어지고 있다. 그 위에
	 * 정산을 얹으면 아무도 읽지 않는다.
	 */
	public int victoryTitleDelayTicks = 200;
	/**
	 * 엔딩의 부제가 뜬 뒤 다음 장으로 넘어가기까지의 간격(틱). 100틱 = 5초. 폭죽은 그동안
	 * 계속 터진다.
	 *
	 * <p>이름과 달리 폭죽 주기가 아니라 <b>장 사이 간격</b>이다. 설정 파일 호환 때문에 이름을
	 * 그대로 둔다.
	 */
	public int victoryFireworkDelayTicks = 100;
	/**
	 * 엔딩 한 장에서 제목이 뜬 뒤 부제가 따라 붙기까지의 지연(틱). 70틱 = 3.5초.
	 *
	 * <p>제목과 부제를 한꺼번에 띄우면 어느 쪽을 볼지 정하기도 전에 장이 넘어간다. 한 장의
	 * 전체 길이는 {@code victorySubtitleDelayTicks + victoryFireworkDelayTicks} 다.
	 */
	public int victorySubtitleDelayTicks = 70;
	/** 위치 교환 몇 초 전부터 화면에 카운트다운을 띄울지. 0이면 카운트다운을 띄우지 않는다. */
	public int positionSwapCountdownSeconds = 5;
	/**
	 * 증강 시험 명령({@code /shareteam perktest ...})을 쓸 수 있게 한다.
	 *
	 * <p><b>기본값은 꺼짐이고, 실제로 플레이하는 서버에서는 켜면 안 된다.</b> 켜면 운영자가
	 * 구간·레벨과 상관없이 증강을 마음대로 주고 뺄 수 있으므로 회차가 뜻을 잃는다.
	 *
	 * <p>이 값만으로는 아무것도 열리지 않는다. 명령 자체가 <b>운영자 권한(level 4)</b>을
	 * 따로 요구하므로 둘이 모두 있어야 동작한다. 켜져 있으면 서버가 뜰 때 경고 로그가 남고,
	 * 접속하는 사람마다 채팅으로 경고를 받는다. 자세한 것은
	 * {@link com.sharedfate.command.PerkTestCommand} 에 적어 뒀다.
	 */
	public boolean perkTestCommands = false;
	/**
	 * 적대 몹 스폰율을 바꾸는 증강({@code mob_spawn_rate})을 실제로 걸지.
	 *
	 * <p><b>기본값은 켜짐.</b> 끄면 그 효과를 가진 증강을 보유하고 있어도 배율이 1.0 으로
	 * 고정되어 자연 스폰이 바닐라 그대로가 된다. 증강 자체가 사라지지는 않으므로 증강 목록과
	 * 설명은 그대로 보인다.
	 *
	 * <p>이 효과만은 몹 하나에 수정자를 붙이는 것이
	 * 아니라 매 틱 도는 스폰 경로를 배율만큼 더 돌게 한다. 인원이 많거나 사양이 낮은 서버에서
	 * 틱이 밀리면 다른 것을 건드리지 않고 이 한 줄로 끌 수 있다.
	 *
	 * <p>거는 자리는 {@code NaturalSpawnerRateMixin} 이고 규칙 자체는
	 * {@link com.sharedfate.perk.MobPerkModifiers#spawnRateOf} 에 적어 뒀다.
	 *
	 * <p>참·거짓뿐이라 {@link #sanitize} 가 되돌릴 「범위를 벗어난 값」이 없다. 설정 파일에
	 * 이 줄이 없으면 Gson 이 밭을 건드리지 않으므로 여기 적힌 기본값 {@code true} 가 남는다.
	 */
	public boolean mobSpawnRatePerks = true;

	/**
	 * 서버 목록에 뜨는 설명(MOTD)을 <b>모드가 손본다.</b> 0.29.1-dev 부터 기본값이 켜짐이다.
	 *
	 * <p>하는 일이 둘이고, 둘 다 {@code ServerMotd} 에 있다.
	 *
	 * <ul>
	 *   <li><b>아무도 안 썼으면 채운다</b> — 바닐라 기본값(「A Minecraft Server」)이거나 비어
	 *       있으면 「SharedFate · 체력·허기·인벤 공유 · 증강 N개」를 넣는다. <b>받아서 자기
	 *       서버를 여는 사람이 아무것도 안 해도</b> 무슨 서버인지 목록에 뜬다</li>
	 *   <li><b>썼으면 숫자만 맞춘다</b> — 글 안에 「증강 N개」가 있으면 그 자리만 갈아 끼운다.
	 *       손으로 적은 개수는 판을 올릴 때마다 낡는다. 실제로 94개인데 「82개」가 몇 판째
	 *       남아 있었다</li>
	 * </ul>
	 *
	 * <p><b>사람이 쓴 글을 덮어쓰지는 않는다.</b> 제 문구를 적어 두었고 거기 「증강 N개」가
	 * 없으면 아무 일도 일어나지 않는다. 기본값을 켜짐으로 돌릴 수 있는 이유가 이것이다 —
	 * 0.29.0-dev 까지는 채우는 갈래가 없어서, 켜 두면 남의 글에만 손대는 셈이라 꺼 두었다.
	 *
	 * <p><b>⚠ 손보는 순간 {@code server.properties} 의 {@code motd} 줄이 저장된다.</b> 전용
	 * 서버에서 {@code MinecraftServer.setMotd} 는 {@code DedicatedServerSettings.update} 를 거쳐
	 * 파일까지 즉시 쓰기 때문이다. 나중에 이 값을 꺼도 <b>마지막에 써진 MOTD 는 파일에 그대로
	 * 남는다</b> — 되돌리려면 손으로 고쳐야 한다.
	 *
	 * <p>참·거짓뿐이라 {@link #sanitize} 가 되돌릴 「범위를 벗어난 값」이 없다.
	 */
	public boolean overrideServerMotd = true;

	public static SharedFateConfig loadOrCreate(Path file) {
		if (Files.exists(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				SharedFateConfig loaded = GSON.fromJson(reader, SharedFateConfig.class);
				if (loaded != null) {
					if (loaded.sanitize()) {
						loaded.save(file);
					}
					return loaded;
				}
				SharedFateMod.LOGGER.warn("설정 파일이 비어 있어 기본값을 사용합니다: {}", file);
			} catch (Exception e) {
				SharedFateMod.LOGGER.warn("설정 파일을 읽지 못해 기본값을 사용합니다: {}", file, e);
			}
		}

		SharedFateConfig config = new SharedFateConfig();
		try {
			config.save(file);
		} catch (IOException e) {
			SharedFateMod.LOGGER.warn("설정 파일을 쓰지 못했습니다: {}", file, e);
		}
		return config;
	}

	private boolean sanitize() {
		boolean changed = false;
		if (maxTeamSize < 1) {
			maxTeamSize = 4;
			changed = true;
		} else if (maxTeamSize > NETWORK_MAX_TEAM_SIZE) {
			maxTeamSize = NETWORK_MAX_TEAM_SIZE;
			changed = true;
		}
		if (!Double.isFinite(sharedMaxHealth)
				|| sharedMaxHealth < MIN_SHARED_MAX_HEALTH
				|| sharedMaxHealth > MAX_SHARED_MAX_HEALTH) {
			sharedMaxHealth = 20.0;
			changed = true;
		}
		if (mainInventoryRows != 3 && mainInventoryRows != 6) {
			mainInventoryRows = 6;
			changed = true;
		}
		if (damageAlertDurationTicks < 0) {
			damageAlertDurationTicks = 30;
			changed = true;
		}
		if (worldResetDelayTicks < MIN_WORLD_RESET_DELAY_TICKS
				|| worldResetDelayTicks > MAX_WORLD_RESET_DELAY_TICKS) {
			worldResetDelayTicks = GameOverCountdown.DEFAULT_SECONDS * GameOverCountdown.TICKS_PER_SECOND;
			changed = true;
		}
		if (victoryCreditsDelayTicks < 20 || victoryCreditsDelayTicks > 1200) {
			victoryCreditsDelayTicks = 100;
			changed = true;
		}
		if (victoryTitleDelayTicks < MIN_VICTORY_STAGE_TICKS
				|| victoryTitleDelayTicks > MAX_VICTORY_STAGE_TICKS) {
			victoryTitleDelayTicks = 200;
			changed = true;
		}
		if (victoryFireworkDelayTicks < MIN_VICTORY_STAGE_TICKS
				|| victoryFireworkDelayTicks > MAX_VICTORY_STAGE_TICKS) {
			victoryFireworkDelayTicks = 100;
			changed = true;
		}
		if (victorySubtitleDelayTicks < MIN_VICTORY_STAGE_TICKS
				|| victorySubtitleDelayTicks > MAX_VICTORY_STAGE_TICKS) {
			victorySubtitleDelayTicks = 70;
			changed = true;
		}
		if (positionSwapCountdownSeconds < 0
				|| positionSwapCountdownSeconds > MAX_POSITION_SWAP_COUNTDOWN_SECONDS) {
			positionSwapCountdownSeconds = 5;
			changed = true;
		}
		if (!Double.isFinite(experienceMultiplier)
				|| experienceMultiplier < MIN_EXPERIENCE_MULTIPLIER
				|| experienceMultiplier > MAX_EXPERIENCE_MULTIPLIER) {
			experienceMultiplier = DEFAULT_EXPERIENCE_MULTIPLIER;
			changed = true;
		}
		return changed;
	}

	public void save(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		}
	}
}
