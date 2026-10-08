package com.sharedfate.sync;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.DynamicOps;
import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamCreationSettings;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class TeamRosterStore {
	public static final String FILE_NAME = "sharedfate-team-roster.json";
	/**
	 * 2: 팀 설정(증강 사용 여부·최대 체력·위치 교환 주기)을 함께 적기 시작했다.
	 * 3: 피격 알림·사망 알림 표시 여부를 함께 적기 시작했다.
	 * 4: 「유산」이 몰수한 도구·무기·방어구 목록({@code legacyGear})을 함께 적기 시작했다.
	 * 5: 난이도 상승 켜고 끄기({@code difficultyEscalationEnabled})를 함께 적기 시작했다.
	 * 6: 증강 다시 뽑기의 회차당 횟수({@code rerollCount})를 함께 적기 시작했다.
	 * 7: 엔더 드래곤 시련 켜고 끄기({@code dragonTrialsEnabled})를 함께 적기 시작했다.
	 *    <b>기본값이 끔</b>이라 형식 6 이하의 파일에 이 항목이 없으면 Gson 이 두는 false 가
	 *    그대로 맞는 값이다 — 두 알림·난이도 상승과 같은 결이고, 다시 뽑기처럼 따로 되돌릴
	 *    필요가 없다.
	 *
	 * <p>6·5·4·3·2·1로 적힌 예전 파일도 그대로 읽는다. 설정 항목이 없으면 기본값으로 시작하고,
	 * 두 알림과 난이도 상승의 기본값은 꺼짐이며, {@code legacyGear} 가 없으면 빈 목록이다.
	 * <b>다시 뽑기 횟수만은 기본값이 0 이 아니다</b> — 형식 5 이하의 파일에는 이 항목이 없어
	 * Gson 이 0 으로 두므로, 아래에서 {@linkplain
	 * com.sharedfate.team.TeamCreationSettings#DEFAULT_REROLL_COUNT 기본 3회}로 바꿔 읽는다.
	 *
	 * <p><b>난이도가 오른 시간도, 이번 회차에 남은 다시 뽑기 횟수도 여기 담지 않는다.</b>
	 * 둘 다 회차마다 다시 세는 값이고 월드 저장에 들어 있다. 회차를 넘겨 이어져야 하는 것은
	 * 「이 팀은 켜기로 했다」·「이 팀은 회차당 몇 번으로 정했다」는 결정뿐이다.
	 */
	private static final int FORMAT_VERSION = 7;
	private static final List<Integer> READABLE_FORMAT_VERSIONS = List.of(7, 6, 5, 4, 3, 2, 1);
	private static final int MAX_STORED_TEAMS = 1024;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/**
	 * 회차를 넘겨 이어 갈 팀 설정.
	 *
	 * <p>보유 증강은 여기 담지 않는다. 증강은 회차마다 새로 고르는 것이 규칙이라 이어지면
	 * 안 되고, 이어져야 하는 것은 <b>"이 팀은 증강을 쓰기로 했다"</b> 는 결정뿐이다.
	 * 최대 체력과 위치 교환 주기도 같은 이유로 결정에 해당한다.
	 *
	 * <p>{@code legacyGear} 는 결정이 아니라 「유산」이 몰수한 실제 아이템이라 성격이 다르지만,
	 * 회차 경계를 넘겨야 하는 값이라는 점은 같아서 같은 파일, 같은 자리에 싣는다. 아이템은
	 * NBT 텍스트(SNBT)로 직렬화해 문자열 하나로 담는다 — Gson 은 {@code ItemStack} 을 모르지만
	 * 문자열은 그대로 다루므로, {@code ItemStack.CODEC} 과 {@code NbtOps} 로 한 번 감싸면
	 * Gson 쪽은 손댈 필요가 없다.
	 *
	 * @param perksEnabled        증강을 쓰기로 한 팀인가
	 * @param maxHealth           팀이 정한 공유 최대 체력. 증강 보너스가 아닌 기본값이다
	 * @param swapIntervalTicks   위치 교환 주기(틱). 꺼져 있으면 0
	 * @param damageAlertEnabled  피격 알림을 켜기로 한 팀인가
	 * @param deathAlertEnabled   사망 알림을 켜기로 한 팀인가
	 * @param legacyGear          「유산」이 몰수해 다음 회차로 넘기는 아이템(SNBT 문자열 목록)
	 * @param difficultyEscalationEnabled 난이도 상승을 켜기로 한 팀인가
	 * @param rerollCount         증강 다시 뽑기의 회차당 횟수. 예전 형식에는 없어 {@code null} 일 수 있다
	 * @param dragonTrialsEnabled 엔더 드래곤 시련을 켜기로 한 팀인가. 예전 형식에는 없고,
	 *                            그때는 Gson 이 두는 {@code false}(=끔)가 곧 기본값이다
	 */
	private record StoredSettings(boolean perksEnabled, float maxHealth, int swapIntervalTicks,
			boolean damageAlertEnabled, boolean deathAlertEnabled, List<String> legacyGear,
			boolean difficultyEscalationEnabled, @Nullable Integer rerollCount,
			boolean dragonTrialsEnabled) {
	}

	private record StoredTeam(String teamId, String name, List<String> members,
			StoredSettings settings) {
	}

	/** 명단과 그 팀이 이어 갈 설정을 함께 들고 다니는 짝. */
	public record RestoredTeam(ShareTeam team, boolean perksEnabled, float maxHealth,
			int swapIntervalTicks, boolean damageAlertEnabled, boolean deathAlertEnabled,
			List<ItemStack> legacyGear, boolean difficultyEscalationEnabled, int rerollCount,
			boolean dragonTrialsEnabled) {

		/** 「유산」도 난이도 상승도 안 쓰는 보통의 경우를 짧게 적기 위한 편의 생성자. */
		public RestoredTeam(ShareTeam team, boolean perksEnabled, float maxHealth,
				int swapIntervalTicks, boolean damageAlertEnabled, boolean deathAlertEnabled) {
			this(team, perksEnabled, maxHealth, swapIntervalTicks,
					damageAlertEnabled, deathAlertEnabled, List.of(), false);
		}

		/** 난이도 상승을 지정하지 않는 편의 생성자. 꺼짐으로 본다. */
		public RestoredTeam(ShareTeam team, boolean perksEnabled, float maxHealth,
				int swapIntervalTicks, boolean damageAlertEnabled, boolean deathAlertEnabled,
				List<ItemStack> legacyGear) {
			this(team, perksEnabled, maxHealth, swapIntervalTicks,
					damageAlertEnabled, deathAlertEnabled, legacyGear, false);
		}

		/** 다시 뽑기 횟수를 지정하지 않는 편의 생성자. 기본 3회로 본다. */
		public RestoredTeam(ShareTeam team, boolean perksEnabled, float maxHealth,
				int swapIntervalTicks, boolean damageAlertEnabled, boolean deathAlertEnabled,
				List<ItemStack> legacyGear, boolean difficultyEscalationEnabled) {
			this(team, perksEnabled, maxHealth, swapIntervalTicks,
					damageAlertEnabled, deathAlertEnabled, legacyGear, difficultyEscalationEnabled,
					TeamCreationSettings.DEFAULT_REROLL_COUNT);
		}

		/**
		 * 드래곤 시련을 지정하지 않는 편의 생성자.
		 * {@linkplain TeamCreationSettings#DEFAULT_DRAGON_TRIALS 끔}으로 본다.
		 */
		public RestoredTeam(ShareTeam team, boolean perksEnabled, float maxHealth,
				int swapIntervalTicks, boolean damageAlertEnabled, boolean deathAlertEnabled,
				List<ItemStack> legacyGear, boolean difficultyEscalationEnabled, int rerollCount) {
			this(team, perksEnabled, maxHealth, swapIntervalTicks,
					damageAlertEnabled, deathAlertEnabled, legacyGear, difficultyEscalationEnabled,
					rerollCount, TeamCreationSettings.DEFAULT_DRAGON_TRIALS);
		}

		public RestoredTeam {
			legacyGear = List.copyOf(legacyGear);
			rerollCount = TeamCreationSettings.sanitizeRerollCount(rerollCount);
		}
	}

	private record StoredRoster(int formatVersion, List<StoredTeam> teams) {
	}

	private TeamRosterStore() {
	}

	/**
	 * 서버가 뜰 때 명단을 맞추고, <b>회차 번호에 맞춰 「시작했는가」도 함께 맞춘다.</b>
	 *
	 * <p>여기서 갈리는 두 길이 곧 {@link GameStartManager.WorldOrigin} 의 두 값이다.
	 * <ul>
	 *   <li><b>이미 굴러가던 월드</b> — 월드에 팀이 그대로 있다. 명단 파일을 지금 모습으로 다시
	 *       뜨고, 회차가 2 이상이면 상태만 「진행 중」으로 맞춘다. <b>아이템은 손대지 않는다.</b></li>
	 *   <li><b>새 월드</b> — 월드에 팀이 하나도 없는데 명단 파일만 남아 있다. 전멸로 월드가
	 *       지워지고 새로 열렸다는 뜻이므로 명단을 되살리고 회차를 이어 간다.</li>
	 * </ul>
	 *
	 * <p><b>상태를 맞춘 뒤에 명단을 저장한다.</b> 「유산」 장비는 자동 시작이 인벤토리에 넣으면서
	 * {@code legacyGear} 에서 빠지는데, 먼저 저장하면 이미 돌려준 장비가 명단 파일에 그대로 남아
	 * 다음 회차에 한 번 더 지급된다.
	 */
	public static void onServerStarted(MinecraftServer server) {
		Path file = rosterFile(server);
		TeamManager manager = TeamManager.get(server);
		int runNumber = RunProgressManager.runNumber();
		try {
			if (!manager.allTeams().isEmpty()) {
				int resumed = GameStartManager.syncRunStart(manager, runNumber,
						GameStartManager.WorldOrigin.ONGOING_WORLD);
				save(file, snapshot(manager), server.registryAccess());
				SharedFateMod.LOGGER.info(
						"[TEAM-ROSTER] 현재 월드 팀 명단 저장: teams={}, 회차 이어받기={}",
						manager.allTeams().size(), resumed);
				return;
			}
			if (!Files.exists(file)) {
				return;
			}
			int restored = manager.restoreFreshRoster(load(file, server.registryAccess()));
			int autoStarted = GameStartManager.syncRunStart(manager, runNumber,
					GameStartManager.WorldOrigin.FRESH_WORLD);
			SharedFateMod.LOGGER.info(
					"[TEAM-ROSTER] 새 월드 팀 명단 복원·공유 자원 초기화: teams={}, 자동 시작={}",
					restored, autoStarted);
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			SharedFateMod.LOGGER.error(
					"팀 명단 파일을 처리하지 못해 기존 월드 상태를 유지합니다: {}", file, e);
		}
	}

	public static void onServerStopping(MinecraftServer server) {
		try {
			saveCurrent(server);
		} catch (IOException e) {
			SharedFateMod.LOGGER.error("서버 종료 중 팀 명단을 저장하지 못했습니다.", e);
		}
	}

	public static void saveCurrent(MinecraftServer server) throws IOException {
		save(rosterFile(server), snapshot(TeamManager.get(server)), server.registryAccess());
	}

	/** 지금 팀들의 명단과 이어 갈 설정을 함께 뜬다. */
	private static List<RestoredTeam> snapshot(TeamManager manager) {
		List<RestoredTeam> result = new ArrayList<>();
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null) {
				continue;
			}
			result.add(new RestoredTeam(team, state.perksEnabled, state.baseMaxHealth,
					state.positionSwapIntervalTicks,
					state.damageAlertEnabled, state.deathAlertEnabled,
					List.copyOf(state.legacyGear), state.difficultyEscalationEnabled,
					state.rerollAllowance, state.dragonTrialsEnabled));
		}
		return result;
	}

	/**
	 * 레지스트리 없이 적는다. <b>인챈트가 붙은 유산 장비는 저장되지 않는다</b> —
	 * {@link #itemOps} 참고. 살아 있는 서버가 없는 시험 경로만 이것을 쓴다.
	 */
	static void save(Path file, Collection<RestoredTeam> teams) throws IOException {
		save(file, teams, null);
	}

	static void save(Path file, Collection<RestoredTeam> teams,
			@Nullable HolderLookup.Provider registries) throws IOException {
		List<StoredTeam> storedTeams = teams.stream()
				.map(entry -> new StoredTeam(
						entry.team().teamId().toString(), entry.team().name(),
						entry.team().members().stream().map(UUID::toString).toList(),
						new StoredSettings(entry.perksEnabled(), entry.maxHealth(),
								entry.swapIntervalTicks(),
								entry.damageAlertEnabled(), entry.deathAlertEnabled(),
								encodeItems(entry.legacyGear(), registries),
								entry.difficultyEscalationEnabled(),
								entry.rerollCount(),
								entry.dragonTrialsEnabled())))
				.toList();
		StoredRoster roster = new StoredRoster(FORMAT_VERSION, storedTeams);
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
			GSON.toJson(roster, writer);
		}
		try {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
					StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	/** 레지스트리 없이 읽는다. {@link #save(Path, Collection)} 와 같은 한계가 있다. */
	static List<RestoredTeam> load(Path file) throws IOException {
		return load(file, null);
	}

	static List<RestoredTeam> load(Path file, @Nullable HolderLookup.Provider registries)
			throws IOException {
		if (!Files.isRegularFile(file)) {
			throw new IOException("팀 명단이 일반 파일이 아닙니다: " + file);
		}
		StoredRoster stored;
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			stored = GSON.fromJson(reader, StoredRoster.class);
		} catch (RuntimeException e) {
			throw new IOException("팀 명단 JSON이 손상되었습니다: " + file, e);
		}
		boolean known = stored != null
				&& READABLE_FORMAT_VERSIONS.contains(stored.formatVersion());
		if (!known || stored.teams() == null || stored.teams().size() > MAX_STORED_TEAMS) {
			throw new IOException("지원하지 않거나 손상된 팀 명단입니다: " + file);
		}

		List<RestoredTeam> teams = new ArrayList<>();
		try {
			for (StoredTeam team : stored.teams()) {
				if (team == null || team.teamId() == null || team.name() == null
						|| team.members() == null) {
					throw new IllegalArgumentException("필수 팀 필드가 없습니다.");
				}
				List<UUID> members = team.members().stream().map(UUID::fromString).toList();
				ShareTeam share = new ShareTeam(UUID.fromString(team.teamId()), team.name(), members);
				// 형식 1 에는 설정이 없다. 그때는 기본값으로 시작한다. 형식 2 에는 알림
				// 항목이, 형식 3 이하에는 legacyGear 가, 형식 4 이하에는 난이도 상승이,
				// 형식 5 이하에는 다시 뽑기 횟수가, 형식 6 이하에는 드래곤 시련이 없는데,
				// Gson 이 없는 필드를 각각 false·null 로 두므로 알림·난이도 상승·드래곤 시련은
				// 저절로 꺼짐이 되고 legacyGear 는 아래에서 빈 목록으로, 다시 뽑기 횟수는
				// 기본 3회로 바꾼다. 드래곤 시련만 따로 되돌리지 않는 이유는 기본값이 곧
				// 꺼짐이기 때문이다 — 「안 적힌 팀」과 「끄기로 한 팀」을 가를 이유가 없다.
				StoredSettings settings = team.settings();
				teams.add(settings == null
						? new RestoredTeam(share, false, defaultMaxHealth(), 0, false, false,
								List.of(), false)
						: new RestoredTeam(share, settings.perksEnabled(),
								settings.maxHealth(), settings.swapIntervalTicks(),
								settings.damageAlertEnabled(), settings.deathAlertEnabled(),
								decodeItems(settings.legacyGear(), registries),
								settings.difficultyEscalationEnabled(),
								rerollCount(settings.rerollCount()),
								settings.dragonTrialsEnabled()));
			}
		} catch (RuntimeException e) {
			throw new IOException("팀 명단 값이 손상되었습니다: " + file, e);
		}
		return List.copyOf(teams);
	}

	/**
	 * 설정이 없는 예전 명단을 읽을 때 쓸 최대 체력.
	 *
	 * <p>{@code SharedFateMod.config} 는 서버가 뜨기 전이나 단위 시험에서는 null 이다.
	 * 그때는 모드 기본값 20 으로 둔다.
	 */
	/**
	 * 저장된 다시 뽑기 횟수. 항목 자체가 없는 형식 5 이하의 파일은 기본 3회로 본다.
	 *
	 * <p>{@code int} 가 아니라 {@code Integer} 로 받는 이유가 이것이다. 0 회로 정한 팀과
	 * 「항목이 없는 예전 파일」을 구별할 길이 그것뿐이다.
	 */
	private static int rerollCount(@Nullable Integer stored) {
		return stored == null
				? TeamCreationSettings.DEFAULT_REROLL_COUNT
				: TeamCreationSettings.sanitizeRerollCount(stored);
	}

	private static float defaultMaxHealth() {
		return SharedFateMod.config == null
				? 20.0F : (float) SharedFateMod.config.sharedMaxHealth;
	}

	/**
	 * 아이템을 적고 읽을 때 쓸 연산자.
	 *
	 * <p><b>맨 {@link NbtOps#INSTANCE} 로는 인챈트를 적지 못한다.</b> {@code ItemEnchantments}
	 * 의 코덱이 {@code RegistryFixedCodec} 을 타는데, 그 구현은 연산자가 {@code RegistryOps} 가
	 * 아니면 곧바로 오류를 낸다. 그래서 인챈트가 붙은 유산 장비를 저장하면 <b>그 항목 전체가</b>
	 * 「저장하지 못해 건너뜁니다」로 버려진다 — 회차를 넘길 때 인챈트만 사라지는 것이 아니라
	 * 장비가 통째로 사라진다.
	 *
	 * <p>레지스트리를 못 받은 경우({@code null})에는 맨 연산자로 물러선다. 살아 있는 서버가 없는
	 * 시험 경로가 그렇고, 그때는 인챈트 없는 아이템만 오간다.
	 */
	private static DynamicOps<Tag> itemOps(@Nullable HolderLookup.Provider registries) {
		return registries == null
				? NbtOps.INSTANCE : registries.createSerializationContext(NbtOps.INSTANCE);
	}

	/** {@code ItemStack} 목록을 SNBT 문자열 목록으로 바꾼다. 빈 스택은 건너뛴다. */
	private static List<String> encodeItems(List<ItemStack> items,
			@Nullable HolderLookup.Provider registries) {
		DynamicOps<Tag> ops = itemOps(registries);
		List<String> encoded = new ArrayList<>();
		for (ItemStack stack : items) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			ItemStack.CODEC.encodeStart(ops, stack)
					.resultOrPartial(error -> SharedFateMod.LOGGER.warn(
							"유산 아이템을 저장하지 못해 건너뜁니다: {}", error))
					.ifPresent(tag -> encoded.add(tag.toString()));
		}
		return encoded;
	}

	/** SNBT 문자열 목록을 {@code ItemStack} 목록으로 되돌린다. 못 읽는 항목은 건너뛴다. */
	private static List<ItemStack> decodeItems(@Nullable List<String> encoded,
			@Nullable HolderLookup.Provider registries) {
		if (encoded == null || encoded.isEmpty()) {
			return List.of();
		}
		DynamicOps<Tag> ops = itemOps(registries);
		List<ItemStack> decoded = new ArrayList<>();
		for (String snbt : encoded) {
			if (snbt == null || snbt.isBlank()) {
				continue;
			}
			try {
				CompoundTag tag = TagParser.parseCompoundFully(snbt);
				ItemStack.CODEC.parse(ops, tag)
						.resultOrPartial(error -> SharedFateMod.LOGGER.warn(
								"유산 아이템을 복원하지 못해 건너뜁니다: {}", error))
						.filter(stack -> !stack.isEmpty())
						.ifPresent(decoded::add);
			} catch (CommandSyntaxException e) {
				SharedFateMod.LOGGER.warn("유산 아이템 텍스트가 손상되었습니다: {}", snbt, e);
			}
		}
		return decoded;
	}

	private static Path rosterFile(MinecraftServer server) {
		return server.getServerDirectory().toAbsolutePath().normalize().resolve(FILE_NAME);
	}
}
