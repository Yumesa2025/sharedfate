package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.PerkMilestones;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeamBroadcaster {
	private static final TeamSyncPayload EMPTY = TeamSyncPayload.EMPTY;
	private static final Map<UUID, Integer> SELECTED_SLOTS = new HashMap<>();
	private static final Map<UUID, Integer> PENDING_SLOTS = new HashMap<>();

	/** 레벨 변화를 확인하는 주기. 매 틱 확인할 필요가 없다. */
	private static final int LEVEL_SCAN_INTERVAL_TICKS = 10;

	/**
	 * 팀별로 마지막에 보낸 <b>(레벨, 다음 증강 레벨, 열린 추가 칸)</b>. 하나라도 바뀌면 다시 보낸다.
	 *
	 * <p>⚠ <b>여기 안 담긴 값은 영영 다시 안 나간다.</b> 예전에는 레벨 둘만 담았는데, 클라이언트는
	 * 열린 칸 수를 스스로 셀 수 없어 서버가 밀어 주는 값 하나만 믿는다. 그래서 「짐꾼」을 빼도
	 * 레벨이 그대로라 키가 안 바뀌었고, <b>셋째 줄이 화면에서 안 사라졌다.</b> 나갔다 들어와도
	 * 그대로였는데 그 줄에는 물건이 안 들어갔다 — 서버는 18칸으로 잠근 상태였기 때문이다.
	 *
	 * <p>칸 수를 넣으면 값이 달라지는 <b>모든</b> 길(증강 제거·환골탈태·가호 단계 변동·물건이 든
	 * 마지막 칸 변동)이 이 한 곳을 지나 반 초 안에 스스로 복구된다.
	 */
	private static final Map<UUID, Long> LAST_SYNC_KEYS = new HashMap<>();
	private static int levelScanCooldown;

	private TeamBroadcaster() {
	}

	public static void sendTo(ServerPlayer player) {
		ShareTeam team = TeamManager.get(player.level().getServer()).teamOf(player.getUUID());
		sendIfSupported(player, team == null ? EMPTY : build(player.level().getServer(), team));
	}

	public static void broadcast(MinecraftServer server, ShareTeam team) {
		TeamSyncPayload payload = build(server, team);
		LAST_SYNC_KEYS.put(team.teamId(), packSyncKey(
				payload.xpLevel(), payload.nextPerkLevel(), payload.unlockedExtraSlots()));
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				sendIfSupported(player, payload);
				refreshExpandedLayout(player);
			}
		}
	}

	/**
	 * 서버 쪽 인벤토리 메뉴의 칸 자리를 지금 해금 수에 맞춘다.
	 *
	 * <p><b>「짐꾼」을 골라도 칸이 바로 안 열리던 이유가 여기였다.</b> 잠긴 칸을 화면 밖으로
	 * 치우는 일({@code updateMenuLayout})을 부르는 곳이 <b>메뉴를 만드는 자리뿐</b>이었는데,
	 * {@code player.inventoryMenu} 는 접속할 때 한 번 만들어져 계속 살아 있다. 그래서 클라이언트는
	 * 새 칸 수를 받아 판을 크게 그리는데 서버 슬롯은 여전히 화면 밖에 있어, <b>재접속하기
	 * 전까지</b> 늘어난 칸을 쓸 수 없었다.
	 *
	 * <p>팀 동기화를 보내는 이 자리에서 함께 맞춘다. 칸 수를 클라이언트에 실어 보내는 곳과
	 * 서버 메뉴를 고치는 곳이 <b>같은 자리</b>여야 둘이 어긋나지 않는다 — 증강을 고를 때든
	 * 세트가 켜질 때든 환골탈태로 잃을 때든, 값이 바뀌면 반드시 이 길을 지난다.
	 */
	private static void refreshExpandedLayout(ServerPlayer player) {
		if (!ExpandedInventoryManager.enabled()) {
			return;
		}
		try {
			ExpandedInventoryManager.updateMenuLayout(player,
					ExpandedInventoryManager.extraFor(player).active());
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("확장 인벤토리 칸 자리를 다시 잡지 못했습니다.", error);
		}
	}

	public static void sendEmpty(ServerPlayer player) {
		sendIfSupported(player, EMPTY);
	}

	public static void reportSelectedSlot(MinecraftServer server, ServerPlayer sender, int slot) {
		if (slot < 0 || slot >= 9) {
			return;
		}
		PENDING_SLOTS.put(sender.getUUID(), slot);
	}

	public static void flushSelectedSlots(MinecraftServer server) {
		for (Map.Entry<UUID, Integer> entry : PENDING_SLOTS.entrySet()) {
			UUID senderId = entry.getKey();
			int slot = entry.getValue();
			ServerPlayer sender = server.getPlayerList().getPlayer(senderId);
			if (sender == null || SELECTED_SLOTS.getOrDefault(senderId, -1) == slot) {
				continue;
			}
			SELECTED_SLOTS.put(senderId, slot);
			ShareTeam team = TeamManager.get(server).teamOf(senderId);
			if (team == null) {
				continue;
			}
			SelectedSlotPayload payload = new SelectedSlotPayload(senderId, slot);
			for (UUID member : team.members()) {
				if (member.equals(senderId)) {
					continue;
				}
				ServerPlayer player = server.getPlayerList().getPlayer(member);
				if (player != null) {
					sendIfSupported(player, payload);
				}
			}
		}
		PENDING_SLOTS.clear();
	}

	/**
	 * 팀 공유 레벨이 바뀌었으면 팀 상태를 다시 보낸다.
	 *
	 * <p>{@link TeamSyncPayload} 는 원래 명단이 바뀔 때만 나가므로 레벨만 오르내리면
	 * 클라이언트 HUD 가 옛 값을 그대로 들고 있게 된다. 매 틱 전부 다시 보내지 않고
	 * 값이 실제로 달라졌을 때만 보낸다.
	 */
	public static void flushTeamLevels(MinecraftServer server) {
		if (++levelScanCooldown < LEVEL_SCAN_INTERVAL_TICKS) {
			return;
		}
		levelScanCooldown = 0;

		TeamManager manager = TeamManager.get(server);
		Set<UUID> living = new HashSet<>();
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null) {
				continue;
			}
			living.add(team.teamId());
			long key = packSyncKey(Math.max(0, state.xpLevel), nextPerkLevel(state),
					com.sharedfate.perk.PerkInventorySlots.unlockedFor(state));
			Long previous = LAST_SYNC_KEYS.get(team.teamId());
			if (previous == null || previous != key) {
				broadcast(server, team);
			}
		}
		// 해체된 팀의 기록은 버린다.
		LAST_SYNC_KEYS.keySet().retainAll(living);
	}

	/**
	 * 다음 증강이 나오는 레벨. 남은 구간이 없거나 증강을 쓰지 않는 팀이면 0.
	 *
	 * <p>이미 지나온 구간은 {@code lastPerkMilestone} 으로 판단한다. 경험치를 써서
	 * 레벨이 내려가도 이미 받은 구간이 다시 다음 목표가 되지는 않는다.
	 */
	static int nextPerkLevel(TeamState state) {
		if (!state.perksEnabled) {
			return 0;
		}
		int cleared = PerkMilestones.clampMilestone(Math.max(0, state.lastPerkMilestone));
		int next = cleared + PerkMilestones.STEP;
		return next > PerkMilestones.MAX ? 0 : next;
	}

	/**
	 * 세 값을 키 하나로 접는다.
	 *
	 * <p>자리를 넉넉히 떼어 <b>서로를 덮지 않게</b> 한다. 겹치면 「레벨이 1 오르고 칸이 9 줄었다」
	 * 같은 조합이 우연히 같은 키가 되어 그때만 복구가 안 되는데, 그런 버그는 재현이 거의
	 * 불가능하다. 다음 증강 레벨은 40 이하, 열린 칸은 27 이하라 각각 한 바이트면 남는다.
	 */
	static long packSyncKey(int xpLevel, int nextPerkLevel, int unlockedExtraSlots) {
		return ((long) xpLevel << 16)
				| ((long) (nextPerkLevel & 0xFF) << 8)
				| (unlockedExtraSlots & 0xFFL);
	}

	public static void onDisconnect(ServerPlayer player) {
		UUID playerId = player.getUUID();
		SELECTED_SLOTS.remove(playerId);
		PENDING_SLOTS.remove(playerId);
		ShareTeam team = TeamManager.get(player.level().getServer()).teamOf(playerId);
		if (team != null) {
			broadcast(player.level().getServer(), team);
		}
	}

	/**
	 * 다음 위치 교환까지 남은 초를 팀 전원에게 보낸다. 골드 「폭발 교환」의 혜택이다.
	 *
	 * <p>1초에 한 번만 부르는 것은 부르는 쪽({@link com.sharedfate.sync.PositionSwapManager})의
	 * 책임이다. 여기서 다시 세면 「보낼 조건」이 두 곳으로 갈라진다.
	 */
	public static void broadcastSwapTimer(List<ServerPlayer> online, int remainingSeconds) {
		SwapTimerPayload payload = new SwapTimerPayload(remainingSeconds);
		for (ServerPlayer player : online) {
			sendIfSupported(player, payload);
		}
	}

	public static void broadcastDamageAlert(List<ServerPlayer> online, String victimName) {
		DamageAlertPayload payload = new DamageAlertPayload(
				victimName, SharedFateMod.config.damageAlertDurationTicks);
		for (ServerPlayer player : online) {
			sendIfSupported(player, payload);
		}
	}

	/**
	 * 전멸을 부른 사람의 이름을 팀 전원에게 보낸다.
	 *
	 * <p>부르는 쪽이 사망 알림 설정을 이미 확인한다. 여기서 다시 따지지 않는 이유는,
	 * 판단이 두 곳으로 갈라지면 한쪽만 고쳐지기 때문이다.
	 */
	public static void broadcastTeamWipe(MinecraftServer server, ShareTeam team, String victimName) {
		TeamWipePayload payload = new TeamWipePayload(victimName);
		for (UUID id : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) {
				sendIfSupported(player, payload);
			}
		}
	}

	private static TeamSyncPayload build(MinecraftServer server, ShareTeam team) {
		List<TeamSyncPayload.Member> members = new ArrayList<>();
		for (UUID id : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			String name = player != null ? player.getPlainTextName() : id.toString().substring(0, 8);
			int slot = SELECTED_SLOTS.getOrDefault(id, -1);
			members.add(new TeamSyncPayload.Member(id, name, slot));
		}
		TeamState state = TeamManager.get(server).stateByTeamId(team.teamId());
		int xpLevel = state == null ? 0 : Math.max(0, state.xpLevel);
		int nextPerkLevel = state == null ? 0 : nextPerkLevel(state);
		float maxHealth = state == null ? 20.0F : state.maxHealth;
		int swapMinutes = state != null && state.positionSwapEnabled()
				? state.positionSwapIntervalMinutes() : 0;
		TeamSyncPayload.Options options = state == null
				? TeamSyncPayload.Options.NONE
				: new TeamSyncPayload.Options(state.perksEnabled,
						state.damageAlertEnabled, state.deathAlertEnabled, state.runStarted,
						com.sharedfate.perk.PerkInventorySlots.unlockedFor(state),
						state.dragonTrialsEnabled);
		return new TeamSyncPayload(members, team.name(), xpLevel, nextPerkLevel,
				maxHealth, swapMinutes, options, team.leader());
	}

	private static void sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}
}
