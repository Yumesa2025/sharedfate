package com.sharedfate.perk;

import com.sharedfate.net.PerkProcPayload;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 조건부 증강이 <b>발동한 순간</b>을 그 사람 화면에 알린다 — 경험치 바 위에 「⟡ 급소만 노려」가
 * 잠깐 떠올랐다 흐려지고 아주 작은 소리가 난다.
 *
 * <p>효과 코드는 발동 지점에서 {@link #fire} 한 줄만 부른다. 무엇을 어떻게 그릴지, 같은 증강이
 * 몇 초에 한 번까지 뜰지는 클라이언트({@code PerkProcFeed})가 정한다. 하루 종일 보는 화면이라
 * 3초(글자)·5초(소리)로 묶는다.
 *
 * <h2>서버도 한 번 거른다</h2>
 * <p>「반격」처럼 팀원이 맞을 때마다 터지는 증강은 난전에서 한 틱에 여러 번 부를 수 있다.
 * 클라이언트가 어차피 3초로 묶으므로, 그 안쪽 패킷은 보내 봐야 버려진다. 그래서 같은 사람·같은
 * 열쇠는 {@link #SEND_INTERVAL_TICKS} 안에 한 번만 보낸다. 클라이언트 쿨다운보다 짧게 두어
 * 클라이언트 쪽 규칙을 가리지 않는다.
 *
 * <h2>켜기·끄기</h2>
 * <p>이 모드에는 클라이언트 설정 파일이 없다. 서버는 늘 보내고, 끄는 길은 아직 없다.
 */
public final class PerkProcFeedback {
	/** 같은 사람·같은 열쇠를 다시 보내기까지의 틱. 클라이언트 글자 쿨다운(3초)보다 짧다. */
	static final int SEND_INTERVAL_TICKS = 20;
	/** 기억해 둔 발송 시각이 이만큼 쌓이면 오래된 것을 치운다. */
	static final int PRUNE_THRESHOLD = 512;
	/** 패킷 이름 칸의 길이 한도({@link PerkProcPayload#CODEC}). */
	static final int MAX_NAME_LENGTH = 64;
	/** 패킷 열쇠 칸의 길이 한도. */
	static final int MAX_KEY_LENGTH = 128;

	private static final Map<String, Long> LAST_SENT = new HashMap<>();

	private PerkProcFeedback() {
	}

	/**
	 * 증강 하나가 이 사람에게 발동했다.
	 *
	 * <p>증강 목록에서 이름과 등급을 찾는다. 목록에 없는 id 면 아무것도 하지 않는다 — 세트 효과처럼
	 * 목록 밖의 것은 {@link #fire(ServerPlayer, String, String, PerkRarity)} 를 쓴다.
	 */
	public static void fire(@Nullable ServerPlayer player, String perkId) {
		if (player == null || perkId == null) {
			return;
		}
		Perk perk = PerkRegistry.byId(perkId).orElse(null);
		if (perk == null) {
			return;
		}
		fire(player, perkId, perk.name(), perk.rarity());
	}

	/**
	 * 이름과 등급을 직접 주고 띄운다. 세트 효과(「반격 자세」 등)처럼 증강 목록에 없는 것용이다.
	 *
	 * @param key 쿨다운 열쇠. 같은 열쇠는 같은 것으로 묶인다. 세트면 세트 id 를 넘긴다
	 */
	public static void fire(@Nullable ServerPlayer player, String key, String name,
			@Nullable PerkRarity rarity) {
		if (player == null || key == null || name == null || name.isBlank()
				|| player.isRemoved()) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		String safeKey = clip(key, MAX_KEY_LENGTH);
		if (!shouldSend(player.getUUID(), safeKey, server.getTickCount())) {
			return;
		}
		PerkProcPayload payload = new PerkProcPayload(safeKey, clip(name, MAX_NAME_LENGTH),
				(rarity == null ? PerkRarity.SILVER : rarity).id());
		if (ServerPlayNetworking.canSend(player, payload.type())) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	/**
	 * 이 사람 팀의 보유 증강 가운데 {@code type} 효과를 가진 첫 증강을 띄운다.
	 *
	 * <p>발동 지점이 효과 객체만 알고 증강 id 를 모를 때(「호위」의 막기처럼) 쓴다. id 를 코드에
	 * 박아 두면 증강 이름을 바꾸거나 다른 증강에 같은 효과를 얹을 때 조용히 어긋난다.
	 */
	public static void fireOwnerOf(@Nullable ServerPlayer player, Class<? extends PerkEffect> type) {
		if (player == null || type == null) {
			return;
		}
		TeamState state = TeamLookup.stateOf(player.getUUID());
		if (state == null || !state.perksEnabled) {
			return;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk != null && perk.effects().stream().anyMatch(type::isInstance)) {
				fire(player, perk.id(), perk.name(), perk.rarity());
				return;
			}
		}
	}

	/**
	 * 팀 전체에 걸리는 효과가 발동했다. 접속해 있는 팀원 모두에게 띄운다.
	 *
	 * <p>팀이 없으면 그 사람에게만 띄운다.
	 *
	 * @param member 팀을 찾을 사람. 발동시킨 사람이면 된다
	 */
	public static void fireTeam(@Nullable ServerPlayer member, String perkId) {
		if (member == null || perkId == null) {
			return;
		}
		MinecraftServer server = member.level().getServer();
		ShareTeam team = server == null ? null : TeamManager.get(server).teamOf(member.getUUID());
		if (team == null) {
			fire(member, perkId);
			return;
		}
		for (UUID id : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(id);
			if (online != null) {
				fire(online, perkId);
			}
		}
	}

	/**
	 * 조건부 효과({@code conditional})가 방금 뒤집힌 것을 알릴 것인가.
	 *
	 * <p><b>조건이 막 맞은 순간</b>만 알린다. 그 밖은 모두 조용하다.
	 * <ul>
	 *   <li>처음 판정(접속·서버 시작 직후, {@code before == null}) — 가진 조건부가 한꺼번에 뜬다</li>
	 *   <li>꺼지는 순간 — 「풀렸다」는 알릴 거리가 아니다</li>
	 *   <li>대가({@code drawback}) — 「불굴」의 「체력이 가득하면 피해 +10%」처럼 손해가 켜진 것이다</li>
	 *   <li>{@code team_nearby}(뭉침) — 팀원이 움직일 때마다 켜졌다 꺼져 하루 종일 깜빡인다</li>
	 *   <li>맞았을 때 붙는 것이 없는 정의 — 켜져도 달라지는 것이 없다</li>
	 * </ul>
	 */
	public static boolean announcesConditional(@Nullable ConditionalEffect effect,
			@Nullable Boolean before, @Nullable Boolean after) {
		if (effect == null || before == null || before || !Boolean.TRUE.equals(after)) {
			return false;
		}
		return effect.condition() != ConditionalEffect.Condition.TEAM_NEARBY
				&& !effect.whenTrue().isEmpty()
				&& !PerkDrawbacks.isDrawback(effect);
	}

	/**
	 * 지금 보내도 되는가. 보내기로 하면 그 시각을 적어 둔다.
	 *
	 * <p>틱 시계가 거꾸로 가면(서버를 다시 켜 0 부터 셀 때) 보낸다. 막아 두면 그 회차 내내 안 뜬다.
	 */
	static synchronized boolean shouldSend(UUID player, String key, long tick) {
		String slot = player + "|" + key;
		Long last = LAST_SENT.get(slot);
		if (last != null && tick >= last && tick - last < SEND_INTERVAL_TICKS) {
			return false;
		}
		LAST_SENT.put(slot, tick);
		if (LAST_SENT.size() > PRUNE_THRESHOLD) {
			prune(tick);
		}
		return true;
	}

	/** 다시 보낼 때가 이미 지난 기록을 치운다. 남는 것은 지금 막 보낸 것들뿐이다. */
	private static void prune(long tick) {
		Iterator<Map.Entry<String, Long>> iterator = LAST_SENT.entrySet().iterator();
		while (iterator.hasNext()) {
			long last = iterator.next().getValue();
			if (tick < last || tick - last >= SEND_INTERVAL_TICKS) {
				iterator.remove();
			}
		}
	}

	static synchronized int trackedForTesting() {
		return LAST_SENT.size();
	}

	static synchronized void resetForTesting() {
		LAST_SENT.clear();
	}

	private static String clip(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}
}
