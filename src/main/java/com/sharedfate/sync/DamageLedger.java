package com.sharedfate.sync;

import net.minecraft.util.Prediction;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.jetbrains.annotations.Nullable;

public final class DamageLedger {
	public static final String FILE_NAME = "sharedfate-damage-history.json";
	/**
	 * 저장 형식.
	 *
	 * <p>2 에서 회차별 <b>마지막 피해의 출처</b>({@code lastHits})가, 3 에서 팀의 <b>회차
	 * 기록</b>({@code history} — 그 회차에 가졌던 증강과 회차를 끝낸 사람)이 늘었다.
	 *
	 * <p>번호가 <b>이보다 낮은 옛 파일도 그대로 읽는다.</b> 늘어난 칸이 비어 있을 뿐이고,
	 * 사람이 몇 회차에 얼마를 맞았는지는 처음부터 같은 자리에 있다. 여기서 파일을 버리면
	 * 지난 회차의 피해 기록이 통째로 사라진다.
	 */
	private static final int FORMAT_VERSION = 3;
	private static final int MAX_TEAMS = 1024;
	private static final int MAX_PLAYERS_PER_TEAM = 16;
	private static final int MAX_RUNS_PER_PLAYER = 100_000;
	/** 한 회차 묶음이 책의 한 쪽에 담는 줄 수. 바닐라 책의 한 쪽이 대략 이만큼이다. */
	private static final int LINES_PER_PAGE = 13;
	private static final int MAX_BOOK_PAGES = 100;
	/** 한 회차 기록에 남기는 증강 이름의 최대 개수. 이보다 많으면 뒤를 자르고 개수만 적는다. */
	private static final int MAX_PERKS_PER_RUN = 64;
	private static final long AUTO_FLUSH_INTERVAL_NANOS = 1_000_000_000L;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static LedgerFile data = new LedgerFile();
	private static Path file;
	private static boolean dirty;
	private static long nextAutomaticFlush;

	private static final class LedgerFile {
		int formatVersion = FORMAT_VERSION;
		Map<String, TeamEntry> teams = new LinkedHashMap<>();
	}

	private static final class TeamEntry {
		String name = "";
		Map<String, PlayerEntry> players = new LinkedHashMap<>();
		/** 회차 → 그 회차의 기록. 형식 3 에서 늘었고, 옛 파일에는 없어 {@code null} 일 수 있다. */
		Map<String, RunEntry> history = new LinkedHashMap<>();
	}

	/**
	 * 회차 하나가 <b>끝날 때</b> 남기는 기록.
	 *
	 * <p>회차 중간이 아니라 끝에 한 번만 적는다. 그 시점의 {@code ownedPerks} 가 곧 「그 회차에
	 * 가졌던 증강 전부」라, 증강이 늘어나는 다섯 갈래(고르기·요행·하늘의 은총·환골탈태·숨은
	 * 재능)에 저마다 갈고리를 거는 대신 한 자리에서 사진을 찍는 셈이다.
	 */
	private static final class RunEntry {
		/** 그 회차에 가졌던 증강 이름들. */
		List<String> perks = new ArrayList<>();
		/** 먼저 죽어 회차를 끝낸 사람의 이름. 승리로 끝난 회차는 비어 있다. */
		String endedBy = "";
		/** 승리로 끝났는가. */
		boolean victory;
	}

	/**
	 * 엔딩 화면에 적을 한 줌의 숫자.
	 *
	 * <p>전부 <b>전체 회차 누적</b>이다. 「지금까지」를 묻는 화면이라 이번 판만 세면 답이 되지
	 * 않는다.
	 *
	 * @param topDamageName  가장 많이 맞은 사람. 아무도 안 맞았으면 빈 문자열
	 * @param topDamage      그 사람이 누적으로 받은 피해
	 * @param mostDeathsName 회차를 가장 많이 끝낸 사람. 그런 회차가 없으면 빈 문자열
	 * @param mostDeaths     그 횟수
	 * @param totalPerks     지금까지 고른 증강 수의 합계
	 */
	public record VictorySummary(String topDamageName, double topDamage,
			String mostDeathsName, int mostDeaths, int totalPerks) {
		/** 기록이 하나도 없을 때. */
		public static final VictorySummary EMPTY = new VictorySummary("", 0.0D, "", 0, 0);

		/** 「최다 피해」 줄을 그릴 만한 값이 있는가. */
		public boolean hasDamage() {
			return !topDamageName.isBlank() && topDamage > 0.0D;
		}

		/** 「최다 사망」 줄을 그릴 만한 값이 있는가. */
		public boolean hasDeaths() {
			return !mostDeathsName.isBlank() && mostDeaths > 0;
		}
	}

	/**
	 * 회차별 <b>마지막으로 받은 피해의 출처</b>.
	 *
	 * <p>이것이 없던 시절에는 「왜 죽었는지」를 물어도 답할 방법이 없었다. 사망 알림을 끈 팀은
	 * 모드가 사망 메시지를 채팅에서 지우고, 서버 로그도 채팅을 받아 적는 자리라 함께 사라진다.
	 * 남는 것이 사람별 누적 숫자뿐이라 <b>「누가 얼마나」는 알아도 「무엇에」는 몰랐다.</b>
	 *
	 * <p>회차마다 <b>마지막 것 하나만</b> 남긴다. 전멸은 공유 체력이 0이 되는 순간이므로,
	 * 그 회차의 마지막 피해가 곧 결정타다. 전부 남기면 파일이 한없이 커진다.
	 */
	private static final class LastHit {
		/** {@code DamageSource.getMsgId()}. 「creeper」·「fall」·「lava」 같은 바닐라 이름이다. */
		String source = "";
		/** 가해자 이름. 몹이나 사람이 없는 피해(낙하·용암)면 비어 있다. */
		String by = "";
		/** 그 한 방의 크기. */
		double amount;
	}

	private static final class PlayerEntry {
		String name = "";
		Map<String, Double> runs = new LinkedHashMap<>();
		/** 회차 → 그 회차에 마지막으로 받은 피해. 옛 형식 파일에는 없어 {@code null} 일 수 있다. */
		Map<String, LastHit> lastHits = new LinkedHashMap<>();
	}

	private DamageLedger() {
	}

	public static void onServerStarted(MinecraftServer server) {
		file = server.getServerDirectory().toAbsolutePath().normalize().resolve(FILE_NAME);
		data = load(file);
		dirty = false;
		nextAutomaticFlush = 0L;
	}

	/**
	 * 이 사람이 방금 무엇에 맞았는지 적어 둔다. {@code hurtServer} 진입점에서 부른다.
	 *
	 * <h2>왜 여기서 따로 받아 두는가</h2>
	 * <p>피해를 <b>기록하는</b> 자리({@link StatMirror})는 체력 스냅샷의 차이로 「얼마나 줄었나」만
	 * 계산한다. 그 자리에는 {@code DamageSource} 가 없고, 넘겨 줄 수도 없다 — 한 틱에 여러
	 * 피해가 겹칠 수 있어 「이 감소분이 저 출처의 것」이라는 짝을 그쪽에서는 지을 수 없기
	 * 때문이다. 그래서 피해가 실제로 들어오는 자리에서 <b>가장 최근 출처</b>만 옆에 적어 두고,
	 * 기록하는 쪽이 그것을 집어 간다.
	 *
	 * <p>메모리에만 두고 저장하지 않는다. 서버가 꺼지면 사라지는 것이 맞다 — 다음에 켰을 때의
	 * 첫 피해가 옛 출처를 물고 들어오면 그것이 더 나쁜 거짓말이다.
	 */
	public static void noteSource(@Nullable ServerPlayer player, @Nullable DamageSource source) {
		if (player == null || source == null) {
			return;
		}
		Entity attacker = source.getEntity();
		noteSource(player.getUUID(), source.getMsgId(),
				attacker == null ? "" : attacker.getName().getString());
	}

	/**
	 * 살아 있는 월드 없이 같은 일을 한다. 시험이 보는 자리다.
	 *
	 * <p>{@code DamageSource} 는 레지스트리가 묶여 있어야 만들 수 있어 시험에서 쓸 수 없다.
	 * 여기서 갈라 두면 「무엇을 적는가」를 월드 없이 확인할 수 있다.
	 */
	static void noteSource(@Nullable UUID playerId, @Nullable String msgId, @Nullable String by) {
		if (playerId == null) {
			return;
		}
		LastHit hit = new LastHit();
		hit.source = safeName(msgId, "unknown");
		hit.by = safeName(by, "");
		PENDING_SOURCE.put(playerId, hit);
	}

	/**
	 * 그 회차에 마지막으로 받은 피해를 사람이 읽는 한 줄로. 기록이 없으면 {@code null}.
	 *
	 * <p>예: {@code "creeper (크리퍼) 19.0"} · {@code "fall 7.5"}
	 */
	public static @Nullable String lastHitLine(@Nullable UUID teamId, @Nullable UUID playerId,
			int runNumber) {
		if (teamId == null || playerId == null) {
			return null;
		}
		TeamEntry team = data.teams.get(teamId.toString());
		PlayerEntry player = team == null ? null : team.players.get(playerId.toString());
		LastHit hit = player == null || player.lastHits == null
				? null
				: player.lastHits.get(Integer.toString(runNumber));
		if (hit == null) {
			return null;
		}
		String who = hit.by == null || hit.by.isBlank() ? "" : " (" + hit.by + ")";
		return hit.source + who + " " + String.format(Locale.ROOT, "%.1f", hit.amount);
	}

	/**
	 * 아직 기록에 들어가지 않은 마지막 출처. 사람마다 하나씩만 들고 있다.
	 *
	 * <p>저장하지 않는 메모리 상태다. {@link #clearState} 가 비운다.
	 */
	private static final Map<UUID, LastHit> PENDING_SOURCE = new java.util.concurrent.ConcurrentHashMap<>();

	public static void record(ShareTeam team, ServerPlayer player, float damage) {
		if (file == null || team == null || player == null || !Float.isFinite(damage) || damage <= 0.0F) {
			return;
		}
		record(team.teamId(), team.name(), player.getUUID(), player.getPlainTextName(),
				RunProgressManager.runNumber(), damage);
	}

	static void record(UUID teamId, String teamName, UUID playerId, String playerName,
			int runNumber, double damage) {
		if (teamId == null || playerId == null || runNumber < 1
				|| !Double.isFinite(damage) || damage <= 0.0D) {
			return;
		}
		TeamEntry team = data.teams.computeIfAbsent(teamId.toString(), ignored -> new TeamEntry());
		team.name = safeName(teamName, "이름 없는 팀");
		PlayerEntry player = team.players.computeIfAbsent(playerId.toString(), ignored -> new PlayerEntry());
		player.name = safeName(playerName, playerId.toString().substring(0, 8));
		String run = Integer.toString(runNumber);
		player.runs.merge(run, damage, Double::sum);
		// 방금 적어 둔 출처가 있으면 이 회차의 「마지막 피해」로 갈아 끼운다. 꺼내 쓰고 비우므로
		// 다음 기록이 같은 출처를 물고 들어오지 않는다.
		LastHit pending = PENDING_SOURCE.remove(playerId);
		if (pending != null) {
			if (player.lastHits == null) {
				player.lastHits = new LinkedHashMap<>();
			}
			pending.amount = damage;
			player.lastHits.put(run, pending);
			trimLastHits(player);
		}
		dirty = true;
	}

	/**
	 * 마지막 피해 기록도 {@code runs} 와 같은 상한으로 자른다.
	 *
	 * <p>둘이 다른 규칙으로 늘어나면 회차가 아주 많은 팀에서 한쪽만 부풀어 오른다.
	 */
	private static void trimLastHits(PlayerEntry player) {
		if (player.lastHits == null || player.lastHits.size() <= MAX_RUNS_PER_PLAYER) {
			return;
		}
		var iterator = player.lastHits.entrySet().iterator();
		while (player.lastHits.size() > MAX_RUNS_PER_PLAYER && iterator.hasNext()) {
			iterator.next();
			iterator.remove();
		}
	}

	/**
	 * 회차가 끝났다는 사실을 남긴다. 전멸했든 승리했든 <b>끝나는 순간에 딱 한 번</b> 부른다.
	 *
	 * <p>같은 회차를 두 번 적으면 나중 것이 이긴다. 승리와 전멸이 같은 회차에 겹칠 수는
	 * 없으므로 실제로 그럴 일은 없지만, 덮어쓰기가 쌓기보다 안전하다.
	 *
	 * @param endedByName 먼저 죽어 회차를 끝낸 사람의 이름. 승리로 끝났으면 {@code null}
	 * @param perkNames   그 회차에 가지고 있던 증강 이름들
	 */
	public static void noteRunEnd(@Nullable ShareTeam team, int runNumber,
			@Nullable String endedByName, @Nullable Collection<String> perkNames) {
		if (team == null) {
			return;
		}
		noteRunEnd(team.teamId(), team.name(), runNumber, endedByName, perkNames);
	}

	static void noteRunEnd(@Nullable UUID teamId, @Nullable String teamName, int runNumber,
			@Nullable String endedByName, @Nullable Collection<String> perkNames) {
		if (teamId == null || runNumber < 1 || runNumber > MAX_RUNS_PER_PLAYER) {
			return;
		}
		TeamEntry team = data.teams.computeIfAbsent(teamId.toString(), ignored -> new TeamEntry());
		team.name = safeName(teamName, "이름 없는 팀");
		if (team.history == null) {
			team.history = new LinkedHashMap<>();
		}
		RunEntry entry = new RunEntry();
		entry.endedBy = safeName(endedByName, "");
		entry.victory = endedByName == null || endedByName.isBlank();
		if (perkNames != null) {
			for (String name : perkNames) {
				if (entry.perks.size() >= MAX_PERKS_PER_RUN) {
					break;
				}
				entry.perks.add(safeName(name, "이름 없는 증강"));
			}
		}
		team.history.put(Integer.toString(runNumber), entry);
		dirty = true;
	}

	/**
	 * 엔딩 화면과 책의 표지에 적을 누적 숫자들.
	 *
	 * <p>동점이면 <b>먼저 나온 사람</b>이 이긴다. 팀 명단의 차례라 판이 바뀌어도 같은 답이
	 * 나온다 — 무작위로 갈리면 같은 화면을 두 번 볼 때 답이 달라 보인다.
	 */
	public static VictorySummary summaryFor(@Nullable ShareTeam team) {
		if (team == null) {
			return VictorySummary.EMPTY;
		}
		TeamEntry stored = data.teams.get(team.teamId().toString());
		if (stored == null) {
			return VictorySummary.EMPTY;
		}

		String topDamageName = "";
		double topDamage = 0.0D;
		for (UUID member : team.members()) {
			PlayerEntry player = stored.players.get(member.toString());
			if (player == null || player.runs == null) {
				continue;
			}
			double total = 0.0D;
			for (Double amount : player.runs.values()) {
				total += finiteDamage(amount);
			}
			if (total > topDamage) {
				topDamage = total;
				topDamageName = safeName(player.name, member.toString().substring(0, 8));
			}
		}

		Map<String, Integer> deaths = new LinkedHashMap<>();
		int totalPerks = 0;
		if (stored.history != null) {
			for (RunEntry run : stored.history.values()) {
				if (run == null) {
					continue;
				}
				totalPerks += run.perks == null ? 0 : run.perks.size();
				if (run.endedBy != null && !run.endedBy.isBlank()) {
					deaths.merge(run.endedBy, 1, Integer::sum);
				}
			}
		}
		String mostDeathsName = "";
		int mostDeaths = 0;
		for (Map.Entry<String, Integer> entry : deaths.entrySet()) {
			if (entry.getValue() > mostDeaths) {
				mostDeaths = entry.getValue();
				mostDeathsName = entry.getKey();
			}
		}
		return new VictorySummary(topDamageName, topDamage, mostDeathsName, mostDeaths, totalPerks);
	}

	public static void flushIfDirty() {
		if (!dirty || file == null) {
			return;
		}
		try {
			save(file, data);
			dirty = false;
		} catch (IOException e) {
			SharedFateMod.LOGGER.error("피해 기록을 저장하지 못했습니다: {}", file, e);
		}
	}

	public static void flushIfDue() {
		long now = System.nanoTime();
		if (!dirty || now < nextAutomaticFlush) {
			return;
		}
		flushIfDirty();
		nextAutomaticFlush = now + AUTO_FLUSH_INTERVAL_NANOS;
	}

	public static void giveVictoryBooks(MinecraftServer server, ShareTeam team, int currentRun) {
		if (team == null) {
			return;
		}
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				ensureMember(team, player);
			}
		}
		ItemStack template = createBook(team, currentRun);
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player == null) {
				continue;
			}
			ItemStack book = template.copy();
			if (!player.addItem(book)) {
				player.drop(book, false, Prediction.SERVER_ONLY);
			}
		}
		flushIfDirty();
	}

	/**
	 * 승리 책의 쪽들을 만든다.
	 *
	 * <p>차례는 <b>표지 → 회차마다 한 묶음 → 맺음말</b>이다. 사람별로 묶던 예전 모양을 회차별로
	 * 바꾼 이유는, 「몇 회차에 무슨 일이 있었나」가 「누가 통틀어 얼마를 맞았나」보다 읽을 거리가
	 * 되기 때문이다. 누적 숫자는 표지에 한 줄로 남는다.
	 *
	 * <p>하트 환산은 적지 않는다. 피해 숫자와 나란히 두면 어느 쪽이 무엇인지 헷갈린다.
	 */
	static List<String> buildPageTexts(ShareTeam team, int currentRun) {
		TeamEntry storedTeam = data.teams.get(team.teamId().toString());
		VictorySummary summary = summaryFor(team);
		List<String> pages = new ArrayList<>();
		pages.add(coverPage(team, currentRun, summary));

		int safeCurrentRun = Math.min(MAX_RUNS_PER_PLAYER, Math.max(1, currentRun));
		for (int run : IntStream.rangeClosed(1, safeCurrentRun).boxed().toList()) {
			if (pages.size() >= MAX_BOOK_PAGES) {
				break;
			}
			addChunked(pages, runLines(team, storedTeam, run), run + "회차 (받은 피해량)");
		}
		if (pages.size() < MAX_BOOK_PAGES) {
			pages.add("플레이해 주셔서\n감사합니다.\n\n\n제작자 카이렌");
		}
		if (pages.size() >= MAX_BOOK_PAGES) {
			pages.set(MAX_BOOK_PAGES - 1, "기록이 너무 많아 책의 100쪽 한도에서 잘렸습니다.\n"
					+ "원본: " + FILE_NAME);
		}
		return List.copyOf(pages);
	}

	private static String coverPage(ShareTeam team, int currentRun, VictorySummary summary) {
		StringBuilder cover = new StringBuilder("SharedFate 기록\n\n팀: ")
				.append(team.name())
				.append("\n완주 회차: ").append(Math.max(1, currentRun)).append('\n');
		if (summary.hasDamage()) {
			cover.append("\n최다 피해\n ").append(summary.topDamageName())
					.append(' ').append(formatDamage(summary.topDamage()));
		}
		if (summary.hasDeaths()) {
			cover.append("\n최다 사망\n ").append(summary.mostDeathsName())
					.append(' ').append(summary.mostDeaths()).append("회");
		}
		if (summary.totalPerks() > 0) {
			cover.append("\n고른 증강 ").append(summary.totalPerks()).append("개");
		}
		return cover.toString();
	}

	/**
	 * 회차 한 묶음의 줄들. 사람별 피해 → 회차를 끝낸 사람 → 그때 가졌던 증강 차례다.
	 *
	 * <p>기록이 없는 회차도 빈 묶음을 남긴다. 「2회차가 통째로 없다」보다 「2회차엔 아무 일도
	 * 없었다」가 읽는 사람에게 정확하다.
	 */
	private static List<String> runLines(ShareTeam team, @Nullable TeamEntry storedTeam, int run) {
		List<String> lines = new ArrayList<>();
		String key = Integer.toString(run);
		for (UUID member : team.members()) {
			PlayerEntry entry = storedTeam == null ? null : storedTeam.players.get(member.toString());
			double amount = entry == null ? 0.0D : finiteDamage(entry.runs.get(key));
			String name = entry == null
					? member.toString().substring(0, 8)
					: safeName(entry.name, member.toString().substring(0, 8));
			lines.add(name + " " + formatDamage(amount));
		}
		RunEntry history = storedTeam == null || storedTeam.history == null
				? null : storedTeam.history.get(key);
		if (history != null) {
			lines.add("");
			if (history.victory) {
				lines.add("드래곤 처치!");
			} else if (!history.endedBy.isBlank()) {
				lines.add("끝낸 사람: " + history.endedBy);
			}
			if (history.perks != null && !history.perks.isEmpty()) {
				lines.add("증강 " + history.perks.size() + "개");
				for (String perk : history.perks) {
					lines.add("· " + perk);
				}
			}
		}
		return lines;
	}

	/**
	 * 줄 목록을 {@value #LINES_PER_PAGE} 줄씩 끊어 제목을 붙여 쪽으로 만든다.
	 *
	 * <p>이어지는 쪽에는 「(계속)」을 붙인다. 제목이 없으면 어느 회차 이야기인지 알 수 없다.
	 */
	private static void addChunked(List<String> pages, List<String> lines, String heading) {
		for (int from = 0; from < Math.max(1, lines.size()) && pages.size() < MAX_BOOK_PAGES;
				from += LINES_PER_PAGE) {
			int to = Math.min(lines.size(), from + LINES_PER_PAGE);
			StringBuilder page = new StringBuilder(heading);
			if (from > 0) {
				page.append(" (계속)");
			}
			page.append("\n\n");
			for (int index = from; index < to; index++) {
				page.append(lines.get(index)).append('\n');
			}
			pages.add(page.toString());
		}
	}

	private static ItemStack createBook(ShareTeam team, int currentRun) {
		List<Filterable<Component>> pages = buildPageTexts(team, currentRun).stream()
				.<Filterable<Component>>map(text -> Filterable.passThrough(Component.literal(text)))
				.toList();
		WrittenBookContent content = new WrittenBookContent(
				Filterable.passThrough("SharedFate 기록"), "SharedFate", 0, pages, true);
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, content);
		return book;
	}

	private static void ensureMember(ShareTeam sourceTeam, ServerPlayer sourcePlayer) {
		TeamEntry team = data.teams.computeIfAbsent(sourceTeam.teamId().toString(), ignored -> new TeamEntry());
		team.name = safeName(sourceTeam.name(), "이름 없는 팀");
		PlayerEntry player = team.players.computeIfAbsent(
				sourcePlayer.getUUID().toString(), ignored -> new PlayerEntry());
		player.name = safeName(sourcePlayer.getPlainTextName(), sourcePlayer.getUUID().toString().substring(0, 8));
		dirty = true;
	}

	private static LedgerFile load(Path path) {
		if (path == null || !Files.isRegularFile(path)) {
			return new LedgerFile();
		}
		try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
			LedgerFile loaded = GSON.fromJson(reader, LedgerFile.class);
			return sanitize(loaded);
		} catch (IOException | RuntimeException e) {
			SharedFateMod.LOGGER.error("피해 기록 파일이 손상되어 새 기록을 메모리에서 시작합니다: {}", path, e);
			return new LedgerFile();
		}
	}

	/**
	 * 읽은 파일을 믿을 수 있는 모양으로 다듬는다.
	 *
	 * <p>형식 번호가 <b>지금보다 낮으면 그대로 받는다.</b> 늘어난 칸이 비어 있을 뿐이고,
	 * 여기서 버리면 지난 회차의 피해 기록이 통째로 사라진다. 반대로 <b>높으면 버린다</b> —
	 * 앞으로 생길 칸이 어떤 뜻인지 지금 코드는 모른다.
	 */
	private static LedgerFile sanitize(LedgerFile loaded) {
		if (loaded == null || loaded.formatVersion > FORMAT_VERSION || loaded.teams == null
				|| loaded.teams.size() > MAX_TEAMS) {
			return new LedgerFile();
		}
		loaded.formatVersion = FORMAT_VERSION;
		loaded.teams.entrySet().removeIf(entry -> {
			try {
				UUID.fromString(entry.getKey());
			} catch (RuntimeException e) {
				return true;
			}
			TeamEntry team = entry.getValue();
			return team == null || team.players == null || team.players.size() > MAX_PLAYERS_PER_TEAM;
		});
		for (TeamEntry team : loaded.teams.values()) {
			team.name = safeName(team.name, "이름 없는 팀");
			// 형식 2 이하로 적힌 파일에는 이 칸 자체가 없다. 비워 두면 뒤에서 전부 null 검사를
			// 해야 하므로 여기서 한 번만 채운다.
			if (team.history == null) {
				team.history = new LinkedHashMap<>();
			}
			team.history.entrySet().removeIf(entry -> parsePositiveInt(entry.getKey()) < 1
					|| entry.getValue() == null);
			for (RunEntry run : team.history.values()) {
				run.endedBy = safeName(run.endedBy, "");
				if (run.perks == null) {
					run.perks = new ArrayList<>();
				} else if (run.perks.size() > MAX_PERKS_PER_RUN) {
					run.perks = new ArrayList<>(run.perks.subList(0, MAX_PERKS_PER_RUN));
				}
			}
			team.players.entrySet().removeIf(entry -> {
				try {
					UUID.fromString(entry.getKey());
				} catch (RuntimeException e) {
					return true;
				}
				PlayerEntry player = entry.getValue();
				return player == null || player.runs == null || player.runs.size() > MAX_RUNS_PER_PLAYER;
			});
			for (PlayerEntry player : team.players.values()) {
				player.name = safeName(player.name, "알 수 없음");
				player.runs.entrySet().removeIf(entry -> parsePositiveInt(entry.getKey()) < 1
						|| finiteDamage(entry.getValue()) <= 0.0D);
			}
		}
		return loaded;
	}

	private static void save(Path path, LedgerFile ledger) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			GSON.toJson(ledger, writer);
		}
		try {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static int parsePositiveInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (RuntimeException ignored) {
			return -1;
		}
	}

	private static double finiteDamage(Double value) {
		return value != null && Double.isFinite(value) && value > 0.0D ? value : 0.0D;
	}

	private static String safeName(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.substring(0, Math.min(64, value.length()));
	}

	/**
	 * 피해량 한 조각.
	 *
	 * <p>하트 환산({@code / 2})은 적지 않는다. 숫자 둘이 나란히 있으면 어느 쪽이 무엇인지
	 * 헷갈리기만 하고, 하트로 세는 사람도 없다.
	 */
	private static String formatDamage(double amount) {
		return String.format(Locale.ROOT, "%.1f", amount);
	}

	/**
	 * 시험이 파일 하나를 읽어 그것을 지금 기록으로 삼는다.
	 *
	 * <p>{@link #onServerStarted} 와 같은 일을 살아 있는 서버 없이 한다. 옛 형식으로 적힌
	 * 파일을 버리지 않는지 확인하는 자리가 여기다.
	 */
	static void loadForTesting(Path path) {
		file = path;
		data = load(path);
		dirty = false;
		nextAutomaticFlush = 0L;
	}

	static void clearState() {
		data = new LedgerFile();
		file = null;
		dirty = false;
		nextAutomaticFlush = 0L;
		// 적어 두기만 하고 아직 기록에 안 들어간 출처도 함께 버린다. 남겨 두면 다음에 켰을 때
		// 첫 피해가 옛 출처를 물고 들어온다.
		PENDING_SOURCE.clear();
	}

	/**
	 * 기록을 통째로 버리고 <b>「저장할 것이 있다」로 표시한다.</b> 서버 초기화가 쓰는 자리다.
	 *
	 * <p>파일을 지우지 않는다. 지워 봐야 {@link #flushIfDirty} 가 메모리에 있는 것을 그대로
	 * 다시 적기 때문이다. 비우고 {@code dirty} 로 표시해 두면 <b>그다음 저장이 빈 파일을</b>
	 * 남긴다 — 1초마다 도는 {@link #flushIfDue} 든 종료 때의 {@link #resetRuntime} 이든,
	 * 어느 쪽이 먼저 닿아도 결과는 같다.
	 *
	 * <p>{@link #clearState} 와 다른 점은 <b>어느 파일에 적을지를 잊지 않는다</b>는 것이다.
	 * 그쪽은 시험이 쓰는 자리라 {@code file} 까지 버리는데, 여기서 그러면 그 뒤의 저장이 아무
	 * 데도 가지 않아 옛 기록이 담긴 파일이 그대로 살아남는다.
	 */
	public static void clearAllRecords() {
		data = new LedgerFile();
		// 적어 두기만 하고 아직 기록에 안 들어간 출처도 함께 버린다. 남겨 두면 초기화 직후의
		// 첫 피해가 지운 회차의 출처를 물고 들어온다.
		PENDING_SOURCE.clear();
		dirty = true;
	}

	public static void resetRuntime() {
		flushIfDirty();
		clearState();
	}
}
