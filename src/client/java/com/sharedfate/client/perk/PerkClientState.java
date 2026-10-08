package com.sharedfate.client.perk;

import com.sharedfate.net.PerkSyncPayload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 클라이언트가 보관하는 증강 상태.
 * 서버의 PerkSyncPayload 로 갱신되며, 월드에서 나가면 clear() 로 초기화된다.
 */
public final class PerkClientState {
	private static final List<PerkSyncPayload.Owned> OWNED = new ArrayList<>();
	/**
	 * 「유적 감별사」가 찾아 둔 좌표 줄들. 그 증강이 없으면 비어 있다.
	 *
	 * <p>보유 증강 하나에 딸린 값이 아니라 <b>팀 하나에 딸린 값</b>이라 목록과 따로 둔다.
	 * 화면은 증강 목록 오른쪽에 이 줄들을 세운다.
	 */
	private static final List<String> RUIN_COORDS = new ArrayList<>();
	private static int pendingCount;
	private static String chooserName = "";

	private PerkClientState() {
	}

	/** PerkSyncPayload 수신 시 호출한다. */
	public static void update(List<PerkSyncPayload.Owned> owned, int pending, String chooser,
			List<String> ruinCoords) {
		OWNED.clear();
		if (owned != null) {
			OWNED.addAll(owned);
		}
		RUIN_COORDS.clear();
		if (ruinCoords != null) {
			RUIN_COORDS.addAll(ruinCoords);
		}
		pendingCount = Math.max(0, pending);
		chooserName = chooser == null ? "" : chooser;
	}

	/** 보유 중인 증강. 이름과 설명, 등급을 함께 들고 있다. */
	public static List<PerkSyncPayload.Owned> owned() {
		return Collections.unmodifiableList(OWNED);
	}

	/** 「유적 감별사」의 좌표 줄들. 그 증강이 없으면 빈 목록. */
	public static List<String> ruinCoords() {
		return Collections.unmodifiableList(RUIN_COORDS);
	}

	/** 아직 처리되지 않은 선택권 개수. */
	public static int pendingCount() {
		return pendingCount;
	}

	/** 현재 선택권을 가진 팀원 이름. 없으면 빈 문자열. */
	public static String chooserName() {
		return chooserName;
	}

	/** 대기 중인 선택권이 있는지. */
	public static boolean hasPending() {
		return pendingCount > 0;
	}

	/** 월드에서 나갈 때 호출한다. */
	public static void clear() {
		OWNED.clear();
		RUIN_COORDS.clear();
		pendingCount = 0;
		chooserName = "";
	}
}
