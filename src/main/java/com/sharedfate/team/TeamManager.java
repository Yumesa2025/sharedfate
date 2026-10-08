package com.sharedfate.team;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sharedfate.SharedFateMod;
import com.sharedfate.sync.TeamRosterStore;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class TeamManager extends SavedData {
	private final Map<UUID, ShareTeam> teams = new LinkedHashMap<>();
	private final Map<UUID, TeamState> states = new HashMap<>();
	private final Map<UUID, UUID> playerToTeam = new HashMap<>();
	private final Set<UUID> pendingEffectClears = new HashSet<>();
	private final Set<UUID> pendingExperienceClears = new HashSet<>();
	/**
	 * 팀이 해체될 때 <b>스폰으로 돌려보내야 하는데 접속해 있지 않던</b> 사람들.
	 *
	 * <p>{@link #pendingExperienceClears} 와 같은 장치다. 해체는 접속 중인 사람에게는 그 자리에서
	 * 일어나지만, 오프라인인 사람에게는 <b>다음에 들어올 때</b> 일어나야 한다. 그때까지 들고
	 * 있는 쪽지가 이것이고, 명단 파일에 함께 저장되므로 서버를 껐다 켜도 살아남는다.
	 *
	 * <p>이것이 없으면 해체 때 접속 안 한 사람만 <b>네더 한복판에 빈손으로</b> 남는다 — 아이템도
	 * 경험치도 이미 사라진 채로.
	 */
	private final Set<UUID> pendingSpawnReturns = new HashSet<>();

	private record Entry(ShareTeam team, TeamState state) {
		private static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ShareTeam.CODEC.fieldOf("team").forGetter(Entry::team),
				TeamState.CODEC.fieldOf("state").forGetter(Entry::state)
		).apply(instance, Entry::new));
	}

	public static final Codec<TeamManager> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Entry.CODEC.listOf().fieldOf("teams").forGetter(TeamManager::toEntries),
			UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("pendingEffectClears", List.of())
					.forGetter(manager -> List.copyOf(manager.pendingEffectClears)),
			UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("pendingExperienceClears", List.of())
					.forGetter(manager -> List.copyOf(manager.pendingExperienceClears)),
			// optionalFieldOf 라 이 항목이 없는 옛 명단 파일도 그대로 읽힌다. 저장 형식을 올리지
			// 않는 이유가 이것이다.
			UUIDUtil.STRING_CODEC.listOf().optionalFieldOf("pendingSpawnReturns", List.of())
					.forGetter(manager -> List.copyOf(manager.pendingSpawnReturns))
	).apply(instance, TeamManager::fromCodec));

	public static final SavedDataType<TeamManager> TYPE = new SavedDataType<>(
			SharedFateMod.id("teams"),
			TeamManager::new,
			CODEC,
			null
	);

	public TeamManager() {
	}

	public static TeamManager get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public @Nullable ShareTeam teamOf(UUID player) {
		UUID teamId = playerToTeam.get(player);
		return teamId == null ? null : teams.get(teamId);
	}

	public @Nullable TeamState stateOf(UUID player) {
		UUID teamId = playerToTeam.get(player);
		return teamId == null ? null : states.get(teamId);
	}

	public @Nullable ShareTeam teamById(UUID teamId) {
		return teams.get(teamId);
	}

	public @Nullable TeamState stateByTeamId(UUID teamId) {
		return states.get(teamId);
	}

	public @Nullable ShareTeam teamByName(String name) {
		for (ShareTeam team : teams.values()) {
			if (team.name().equalsIgnoreCase(name)) {
				return team;
			}
		}
		return null;
	}

	public Collection<ShareTeam> allTeams() {
		return List.copyOf(teams.values());
	}

	/** 팀이 하나라도 있는지. */
	public boolean hasAnyTeam() {
		return !teams.isEmpty();
	}

	/**
	 * singleTeamOnly 정책상 새 팀을 만들 수 있는지 판단한다.
	 * 이미 있는 팀은 그대로 두고 새로 만드는 것만 막으므로, 팀이 여럿인 서버도 깨지지 않는다.
	 * 회차 리셋 복원은 {@link #restoreFreshRoster}를 쓰므로 이 판단을 거치지 않는다.
	 */
	public boolean canCreateNewTeam(boolean singleTeamOnly) {
		return !singleTeamOnly || teams.isEmpty();
	}

	/**
	 * 새 회차의 빈 월드에 팀 명단과 그 팀이 정해 둔 설정을 되살린다.
	 *
	 * <p>공유 아이템·체력·경험치는 새로 시작하지만 <b>증강 사용 여부·최대 체력·위치 교환
	 * 주기·두 알림·난이도 상승·다시 뽑기 횟수 설정은 이어진다.</b> 이것들은 회차마다 달라지는 진행 상황이
	 * 아니라 팀이 한 번 내린 결정이고, 모두 팀을 만들 때만 정할 수 있어 이어지지 않으면
	 * 회차가 넘어가는 순간 되돌릴 길이 없다.
	 *
	 * <p>다만 난이도가 <b>실제로 오른 시간</b>과 <b>이번 회차에 남은 다시 뽑기 횟수</b>는
	 * 이어지지 않는다. 둘 다 「회차가 시작된 뒤」를 뜻하는 값이라, 새 회차는 시간은 0 에서,
	 * 다시 뽑기는 가득 찬 채로 시작한다.
	 *
	 * <p>보유 증강은 이어지지 않는다. 회차마다 새로 고르는 것이 규칙이다.
	 *
	 * <p>여기서 되살린 팀은 {@link TeamState#fresh} 때문에 일단 <b>「시작 대기」</b> 로 나오지만,
	 * 바로 뒤에서 {@code GameStartManager.syncRunStart} 가 회차 번호를 보고 회차를 켠다
	 * ({@code TeamRosterStore.onServerStarted}). <b>사람이 「게임 시작」을 누르는 것은 1회차
	 * 전 한 번뿐이고</b>, 2회차부터는 저절로 진행 중이 된다.
	 */
	public int restoreFreshRoster(Collection<TeamRosterStore.RestoredTeam> roster) {
		Objects.requireNonNull(roster, "roster");
		if (!teams.isEmpty()) {
			throw new IllegalStateException("기존 팀이 있는 저장소에는 팀 명단을 복원할 수 없습니다.");
		}

		List<TeamRosterStore.RestoredTeam> entries = List.copyOf(roster);
		List<ShareTeam> snapshot = entries.stream().map(TeamRosterStore.RestoredTeam::team).toList();
		Set<UUID> teamIds = new HashSet<>();
		Set<String> names = new HashSet<>();
		Set<UUID> members = new HashSet<>();
		for (ShareTeam team : snapshot) {
			if (team == null || team.isEmpty()
					|| team.size() > com.sharedfate.config.SharedFateConfig.NETWORK_MAX_TEAM_SIZE
					|| team.name().isBlank() || team.name().length() > 32
					|| !teamIds.add(team.teamId())
					|| !names.add(team.name().toLowerCase(Locale.ROOT))
					|| new HashSet<>(team.members()).size() != team.members().size()) {
				throw new IllegalArgumentException("유효하지 않거나 중복된 팀 명단입니다.");
			}
			for (UUID member : team.members()) {
				if (member == null || !members.add(member)) {
					throw new IllegalArgumentException("한 플레이어가 여러 팀에 중복되어 있습니다.");
				}
			}
		}

		pendingEffectClears.clear();
		pendingExperienceClears.clear();
		// 새 회차의 새 명단이다. 지난 회차에 남은 스폰 귀환 쪽지를 들고 가면 엉뚱한 사람이
		// 접속하자마자 스폰으로 끌려간다.
		pendingSpawnReturns.clear();
		for (TeamRosterStore.RestoredTeam entry : entries) {
			ShareTeam team = entry.team();
			TeamState state = TeamState.fresh(sanitizeMaxHealth(entry.maxHealth()));
			state.perksEnabled = entry.perksEnabled();
			state.damageAlertEnabled = entry.damageAlertEnabled();
			state.deathAlertEnabled = entry.deathAlertEnabled();
			state.positionSwapIntervalTicks = Math.max(0, entry.swapIntervalTicks());
			// 난이도 상승은 켜고 끄기만 이어진다. 오른 시간은 회차마다 0 에서 다시 센다.
			state.difficultyEscalationEnabled = entry.difficultyEscalationEnabled();
			// 드래곤 시련도 「이 팀은 켜기로 했다」는 결정이라 회차를 넘겨 그대로 이어진다.
			// 전투 상태(무슨 카드를 뽑았나)는 여기 없다 — 그쪽은 회차마다 0 에서 다시 쌓인다.
			state.dragonTrialsEnabled = entry.dragonTrialsEnabled();
			// 다시 뽑기도 같은 결이다. 「회차당 몇 번」이라는 결정만 이어지고, 이번 회차에
			// 남은 횟수는 여기서 가득 찬다 — 회차가 넘어가면 다시 차야 하기 때문이다.
			state.rerollAllowance = TeamCreationSettings.sanitizeRerollCount(entry.rerollCount());
			state.rerollsRemaining = state.rerollAllowance;
			// 세트로 받은 몫은 이어지지 않는다. 회차가 넘어가면 세트도 처음부터 다시 모은다.
			state.rerollSetBonus = 0;
			// 「유산」이 몰수했던 도구·무기·방어구는 여기서 인벤토리에 꽂지 않고 그대로 들고만
			// 있는다. 실제로 돌려주는 것은 회차가 시작되는 자리다 — 1회차 전이라면 리더가
			// 「게임 시작」을 누르는 순간, 2회차부터라면 GameStartManager.syncRunStart.
			// 「게임 시작」이 아이템을 전부 지우는 동작이라, 여기서 미리 넣어 두면 그 청소에
			// 함께 쓸려 나가 「유산」이 아무 뜻도 없는 증강이 된다.
			state.legacyGear.addAll(entry.legacyGear());
			teams.put(team.teamId(), team);
			states.put(team.teamId(), state);
			for (UUID member : team.members()) {
				playerToTeam.put(member, team.teamId());
			}
		}
		if (!snapshot.isEmpty()) {
			setDirty();
		}
		return snapshot.size();
	}

	/** 손상된 저장값이 와도 허용 범위 안으로 맞춘다. */
	private static float sanitizeMaxHealth(float stored) {
		float fallback = SharedFateMod.config == null
				? 20.0F : (float) SharedFateMod.config.sharedMaxHealth;
		if (!Float.isFinite(stored) || stored < 20.0F || stored > 40.0F) {
			return fallback;
		}
		return stored;
	}

	public @Nullable ShareTeam createTeam(String name, UUID leader, float maxHealth) {
		return createTeam(name, leader, TeamState.fresh(maxHealth));
	}

	public @Nullable ShareTeam createTeam(String name, UUID leader, TeamState initialState) {
		if (teamByName(name) != null || playerToTeam.containsKey(leader)) {
			return null;
		}
		ShareTeam team = ShareTeam.create(name, leader);
		teams.put(team.teamId(), team);
		states.put(team.teamId(), initialState);
		playerToTeam.put(leader, team.teamId());
		setDirty();
		return team;
	}

	public boolean addMember(UUID teamId, UUID player, int maxTeamSize) {
		ShareTeam team = teams.get(teamId);
		if (team == null || team.size() >= maxTeamSize || playerToTeam.containsKey(player)) {
			return false;
		}
		teams.put(teamId, team.withMemberAdded(player));
		playerToTeam.put(player, teamId);
		setDirty();
		return true;
	}

	public void removeMember(UUID player) {
		UUID teamId = playerToTeam.get(player);
		if (teamId == null) {
			return;
		}
		ShareTeam team = teams.get(teamId);
		if (team != null) {
			ShareTeam next = team.withMemberRemoved(player);
			if (next.isEmpty()) {
				TeamState state = states.get(teamId);
				if (state != null && state.hasSharedItems()) {
					throw new IllegalStateException("공유 아이템을 정산하지 않고 마지막 멤버를 제거할 수 없습니다.");
				}
				teams.remove(teamId);
				states.remove(teamId);
			} else {
				teams.put(teamId, next);
			}
		}
		playerToTeam.remove(player);
		setDirty();
	}

	public void disband(UUID teamId) {
		TeamState state = states.get(teamId);
		if (state != null && state.hasSharedItems()) {
			throw new IllegalStateException("공유 아이템을 정산하지 않고 팀을 해체할 수 없습니다.");
		}
		ShareTeam team = teams.remove(teamId);
		states.remove(teamId);
		if (team != null) {
			for (UUID member : team.members()) {
				playerToTeam.remove(member);
			}
			setDirty();
		}
	}

	public void markEffectClear(UUID player) {
		if (pendingEffectClears.add(player)) {
			setDirty();
		}
	}

	public boolean consumeEffectClear(UUID player) {
		if (!pendingEffectClears.remove(player)) {
			return false;
		}
		setDirty();
		return true;
	}

	public void markExperienceClear(UUID player) {
		if (pendingExperienceClears.add(player)) {
			setDirty();
		}
	}

	public boolean consumeExperienceClear(UUID player) {
		if (!pendingExperienceClears.remove(player)) {
			return false;
		}
		setDirty();
		return true;
	}

	/** 이 사람을 다음에 볼 때 스폰으로 돌려보내라고 적어 둔다. */
	public void markSpawnReturn(UUID player) {
		if (pendingSpawnReturns.add(player)) {
			setDirty();
		}
	}

	/** 적어 둔 쪽지가 있으면 지우고 참을 돌려준다. 부르는 쪽이 그때 옮긴다. */
	public boolean consumeSpawnReturn(UUID player) {
		if (!pendingSpawnReturns.remove(player)) {
			return false;
		}
		setDirty();
		return true;
	}

	/**
	 * 팀이 하나라도 있으면 저장을 더럽다고 표시한다. 매 서버 틱 불린다.
	 *
	 * <h2>⚠ 예전에는 여기서 넘침 대기열을 되돌렸다</h2>
	 *
	 * <p>매 틱 {@code state.restoreOverflow(...)} 를 불러 칸이 비는 순간 물건을 도로 밀어
	 * 넣었다. 아이템을 잃지는 않았지만 <b>언제 돌아오는지 아무도 몰랐다.</b> 「보급을 받았다는
	 * 채팅은 떴는데 인벤토리 어디에도 없다」가 그것이었다.
	 *
	 * <p>이제 넘친 물건은 {@code TeamStorage} 창고에 <b>쌓이기만 하고 사람이 꺼내 간다.</b>
	 * 여기서 되돌리면 창고를 열기도 전에 물건이 빠져나가 창고가 늘 비어 보인다.
	 *
	 * <p>「지금 바로 넣어 보고 안 되면 창고행」 하는 자리(커서 아이템·「유산」 승계)는 스스로
	 * {@code restoreOverflow} 를 부르므로 그대로 동작한다. 자리가 있는데도 굳이 창고로 가는
	 * 일은 없다.
	 */
	public void markDirtyIfActive() {
		if (!teams.isEmpty()) {
			setDirty();
		}
	}

	private List<Entry> toEntries() {
		List<Entry> entries = new ArrayList<>();
		for (ShareTeam team : teams.values()) {
			TeamState state = states.get(team.teamId());
			if (state != null) {
				entries.add(new Entry(team, state));
			}
		}
		return entries;
	}

	/** 저장 데이터를 되살린다. */
	private static TeamManager fromCodec(List<Entry> entries,
			List<UUID> pendingEffectClears, List<UUID> pendingExperienceClears,
			List<UUID> pendingSpawnReturns) {
		TeamManager manager = new TeamManager();
		for (Entry entry : entries) {
			ShareTeam team = entry.team();
			if (team.isEmpty()
					|| team.size() > com.sharedfate.config.SharedFateConfig.NETWORK_MAX_TEAM_SIZE
					|| new HashSet<>(team.members()).size() != team.members().size()
					|| team.name().isBlank() || manager.teamByName(team.name()) != null
					|| manager.teams.containsKey(team.teamId())
					|| team.members().stream().anyMatch(manager.playerToTeam::containsKey)) {
				SharedFateMod.LOGGER.warn("손상되거나 중복된 팀 저장 데이터를 건너뜁니다: {}", team.name());
				continue;
			}
			float maximum = SharedFateMod.config == null
					? 20.0F : (float) SharedFateMod.config.sharedMaxHealth;
			entry.state().sanitize(maximum);
			manager.teams.put(team.teamId(), team);
			manager.states.put(team.teamId(), entry.state());
			for (UUID member : team.members()) {
				manager.playerToTeam.put(member, team.teamId());
			}
		}
		manager.pendingEffectClears.addAll(pendingEffectClears);
		manager.pendingExperienceClears.addAll(pendingExperienceClears);
		manager.pendingSpawnReturns.addAll(pendingSpawnReturns);
		return manager;
	}
}
