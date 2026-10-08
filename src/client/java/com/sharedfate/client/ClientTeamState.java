package com.sharedfate.client;

import com.sharedfate.net.TeamSyncPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClientTeamState {
	private static final Map<UUID, Integer> ALLY_SLOTS = new HashMap<>();
	private static final Map<UUID, String> MEMBER_NAMES = new HashMap<>();
	/** 서버가 보낸 순서를 그대로 지킨다. 화면에서 팀원 목록이 매번 뒤바뀌면 읽기 어렵다. */
	private static final List<UUID> MEMBER_ORDER = new ArrayList<>();

	/** 팀이 공유하는 경험치 레벨. 서버가 보낸 값을 그대로 들고 있는다. */
	private static int teamLevel;
	/** 다음 증강이 나오는 레벨. 남은 증강이 없으면 0. */
	private static int nextPerkLevel;

	private static String teamName = "";
	private static float maxHealth = 20.0F;
	private static int swapIntervalMinutes;
	private static boolean perksEnabled;
	private static boolean damageAlertEnabled;
	private static boolean deathAlertEnabled;
	/**
	 * 이 팀이 엔더 드래곤 시련을 쓰는가. <b>보여 주기만 한다</b> — 시련을 걸고 안 걸고는
	 * 전부 서버가 정하고, 클라이언트는 설정 탭에 한 줄을 적는 데만 쓴다.
	 */
	private static boolean dragonTrialsEnabled;
	private static boolean leader;
	/** 이 팀의 회차가 시작되었는가. 거짓이면 「시작 대기」다. */
	private static boolean runStarted;

	private ClientTeamState() {
	}

	public static void setAllySlot(UUID player, int slot) {
		if (!MEMBER_NAMES.containsKey(player)) {
			return;
		}
		if (slot < 0 || slot >= 9) {
			ALLY_SLOTS.remove(player);
		} else {
			ALLY_SLOTS.put(player, slot);
		}
	}

	public static void setTeam(TeamSyncPayload payload, UUID localPlayer) {
		MEMBER_NAMES.clear();
		MEMBER_ORDER.clear();
		ALLY_SLOTS.clear();
		teamLevel = payload.xpLevel();
		nextPerkLevel = payload.nextPerkLevel();
		teamName = payload.teamName();
		maxHealth = payload.maxHealth();
		swapIntervalMinutes = payload.swapIntervalMinutes();
		perksEnabled = payload.perksEnabled();
		damageAlertEnabled = payload.damageAlertEnabled();
		deathAlertEnabled = payload.deathAlertEnabled();
		dragonTrialsEnabled = payload.dragonTrialsEnabled();
		runStarted = payload.runStarted();
		// 확장 인벤토리는 클라이언트가 스스로 셀 수 없다. 팀의 보유 증강을 모르기 때문이다.
		com.sharedfate.inventory.ExpandedInventoryManager.setClientUnlockedSlots(
				payload.unlockedExtraSlots());
		leader = payload.isLeader(localPlayer);
		for (TeamSyncPayload.Member member : payload.members()) {
			MEMBER_NAMES.put(member.id(), member.name());
			MEMBER_ORDER.add(member.id());
			if (!member.id().equals(localPlayer)
					&& member.selectedSlot() >= 0 && member.selectedSlot() < 9) {
				ALLY_SLOTS.put(member.id(), member.selectedSlot());
			}
		}
	}

	public static void clear() {
		ALLY_SLOTS.clear();
		MEMBER_NAMES.clear();
		MEMBER_ORDER.clear();
		teamLevel = 0;
		nextPerkLevel = 0;
		teamName = "";
		maxHealth = 20.0F;
		swapIntervalMinutes = 0;
		perksEnabled = false;
		damageAlertEnabled = false;
		deathAlertEnabled = false;
		dragonTrialsEnabled = false;
		runStarted = false;
		leader = false;
	}

	public static boolean isAllyUsingHotbarSlot(int slot) {
		return ALLY_SLOTS.containsValue(slot);
	}

	public static boolean inTeam() {
		return !MEMBER_NAMES.isEmpty();
	}

	/** 팀이 공유하는 경험치 레벨. */
	public static int teamLevel() {
		return teamLevel;
	}

	/** 다음 증강까지 남은 레벨. 더 이상 받을 증강이 없으면 -1. */
	public static int levelsToNextPerk() {
		return nextPerkLevel <= 0 ? -1 : Math.max(0, nextPerkLevel - teamLevel);
	}

	/** 팀 이름. 팀이 없으면 빈 문자열. */
	public static String teamName() {
		return teamName;
	}

	/** 팀이 정한 공유 최대 체력. */
	public static float maxHealth() {
		return maxHealth;
	}

	/** 위치 교환 주기(분). 꺼져 있으면 0. */
	public static int swapIntervalMinutes() {
		return swapIntervalMinutes;
	}

	/** 위치 교환이 켜져 있는가. */
	public static boolean swapEnabled() {
		return swapIntervalMinutes > 0;
	}

	/** 이 팀이 증강을 쓰는가. */
	public static boolean perksEnabled() {
		return perksEnabled;
	}

	/** 이 팀이 피격 알림을 쓰는가. 팀을 만들 때 정한 값이라 바뀌지 않는다. */
	public static boolean damageAlertEnabled() {
		return damageAlertEnabled;
	}

	/** 이 팀이 사망 알림을 쓰는가. 팀을 만들 때 정한 값이라 바뀌지 않는다. */
	public static boolean deathAlertEnabled() {
		return deathAlertEnabled;
	}

	/** 이 팀의 회차가 시작되었는가. 거짓이면 리더에게 「게임 시작」 단추를 보여 준다. */
	/** 이 팀이 엔더 드래곤 시련을 쓰는가. 거짓이면 바닐라 드래곤전이다. */
	public static boolean dragonTrialsEnabled() {
		return dragonTrialsEnabled;
	}

	public static boolean runStarted() {
		return runStarted;
	}

	/** 내가 팀 리더인가. 설정 단추를 열지 말지 정하는 데 쓴다. */
	public static boolean isLeader() {
		return leader;
	}

	/** 서버가 보낸 순서 그대로의 팀원 UUID 목록. */
	public static List<UUID> memberIds() {
		return Collections.unmodifiableList(MEMBER_ORDER);
	}

	/** 팀원 이름. 모르는 UUID 면 빈 문자열. */
	public static String memberName(UUID id) {
		return MEMBER_NAMES.getOrDefault(id, "");
	}
}
