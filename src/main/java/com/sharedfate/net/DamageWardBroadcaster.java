package com.sharedfate.net;

import com.sharedfate.perk.DamageWardTracker;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkBlessingSet;
import com.sharedfate.perk.PerkRarity;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.effect.DamageWardEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「호위」가 지금 막아 줄 수 있는지를 그 사람 화면에 알린다({@link DamageWardPayload}).
 *
 * <h2>무엇을 언제 보내는가</h2>
 * <p>{@link PerkSetBroadcaster} 처럼 <b>결과를 견주어 달라졌을 때만</b> 보낸다. 「호위」를 얻고
 * 잃는 자리(증강 선택·환골탈태·팀 이동·「가호 4」 켜짐)가 여럿이라 사건마다 거는 대신 몇 틱에 한
 * 번 훑는다. 다만 <b>막아 낸 순간</b>만은 훑기를 기다리지 않는다 — 표가 0.2초라도 더 떠 있으면
 * 「아직 남았다」로 읽히므로 {@link #consumed} 가 곧바로 감추라고 보낸다.
 *
 * <p>쿨타임이 차는 순간은 {@link #SCAN_INTERVAL_TICKS} 틱 안에 잡힌다. 늦어도 0.2초라 눈으로는
 * 차이가 없고, 그 사이에 맞으면 서버가 이미 막으므로 「표가 없는데 막혔다」가 될 뿐 거꾸로는 없다.
 *
 * <h2>재접속</h2>
 * <p>나갈 때 기록을 버린다({@link #forget}). 클라이언트도 나갈 때 표를 버리므로, 들어오면 처음 훑기에서
 * 지금 상태를 다시 받는다. 남은 쿨타임은 서버의 {@link DamageWardTracker} 가 들고 있어 재접속해도
 * 어긋나지 않는다.
 */
public final class DamageWardBroadcaster {
	/** 몇 틱에 한 번 훑는가. 쿨타임이 차는 순간을 이만큼 늦게 알 수 있다. */
	static final int SCAN_INTERVAL_TICKS = 4;

	/** 사람별로 마지막에 보낸 값. */
	private static final Map<UUID, DamageWardPayload> LAST_SENT = new HashMap<>();

	private static int scanCooldown;

	private DamageWardBroadcaster() {
	}

	/** 매 틱 도는 지점. {@link #SCAN_INTERVAL_TICKS} 틱마다만 실제로 훑는다. */
	public static void flush(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (++scanCooldown < SCAN_INTERVAL_TICKS) {
			return;
		}
		scanCooldown = 0;

		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			online.add(player.getUUID());
			send(player, statusOf(player));
		}
		LAST_SENT.keySet().retainAll(online);
	}

	/**
	 * 방금 「호위」가 한 번 막았다. 다음 훑기를 기다리지 않고 표를 감춘다.
	 *
	 * <p>{@code PerkDamage.blocksMobDamage} 가 쿨타임을 쓴 직후에 부른다.
	 */
	public static void consumed(@Nullable ServerPlayer player) {
		if (player != null) {
			send(player, statusOf(player));
		}
	}

	/** 지난번과 다를 때만 보낸다. 이 묶음을 모르는 클라이언트에게는 보내지 않는다. */
	private static void send(ServerPlayer player, DamageWardPayload now) {
		UUID playerId = player.getUUID();
		if (now.equals(LAST_SENT.get(playerId))) {
			return;
		}
		if (!ServerPlayNetworking.canSend(player, DamageWardPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, now);
		LAST_SENT.put(playerId, now);
	}

	/** 이 사람이 지금 받아야 할 값. */
	static DamageWardPayload statusOf(ServerPlayer player) {
		UUID playerId = player.getUUID();
		return statusOf(TeamLookup.stateOf(playerId), playerId, player.level().getGameTime(),
				player.isAlive() && !player.isSpectator());
	}

	/**
	 * 팀 상태와 시각만 보고 값을 정한다.
	 *
	 * <p>「호위」를 받는지와 쿨타임은 {@link DamageWardEffect#wardFor} 가 정한다 — 피해를 실제로 막는
	 * {@code PerkDamage.blocksMobDamage} 가 쓰는 것과 <b>같은 판정</b>이어야 표와 실제가 어긋나지 않는다.
	 * 「가호 3」의 짧은 쿨타임도, 「가호 4」의 「고르지 않은 팀원도 받는다」도 거기에 들어 있다.
	 *
	 * @param alive 살아 있고 관전자가 아닌가. 죽어 있는 동안 막을 것은 없다
	 */
	static DamageWardPayload statusOf(@Nullable TeamState state, @Nullable UUID playerId,
			long nowTick, boolean alive) {
		if (!alive) {
			return DamageWardPayload.HIDDEN;
		}
		DamageWardEffect ward = DamageWardEffect.wardFor(state, playerId);
		if (ward == null) {
			return DamageWardPayload.HIDDEN;
		}
		if (DamageWardTracker.remainingTicks(playerId, nowTick, ward.cooldownTicks()) > 0L) {
			return DamageWardPayload.HIDDEN;
		}
		Perk perk = sourceOf(state, playerId);
		if (perk == null) {
			return DamageWardPayload.ready("호위", PerkRarity.GOLD.id());
		}
		return DamageWardPayload.ready(perk.name(), perk.rarity().id());
	}

	/**
	 * 이 사람에게 「호위」를 주는 증강. 이름과 등급을 표에 적으려고 찾는다.
	 *
	 * <p>{@link DamageWardEffect#wardFor} 와 같은 조건({@link PerkBlessingSet#appliesTo})으로 훑는다.
	 * 그쪽은 효과만 돌려주어 어느 증강인지 모른다.
	 */
	private static @Nullable Perk sourceOf(@Nullable TeamState state, @Nullable UUID playerId) {
		if (state == null || playerId == null) {
			return null;
		}
		for (String perkId : state.ownedPerks) {
			if (!PerkBlessingSet.appliesTo(state, perkId, playerId)) {
				continue;
			}
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk != null && DamageWardEffect.shortestOf(perk.effects()) != null) {
				return perk;
			}
		}
		return null;
	}

	/** 한 사람의 기록을 버린다. 접속이 끊길 때 부른다. */
	public static void forget(@Nullable UUID playerId) {
		if (playerId != null) {
			LAST_SENT.remove(playerId);
		}
	}

	/** 서버가 멈출 때 들고 있던 기록을 버린다. */
	public static void reset() {
		LAST_SENT.clear();
		scanCooldown = 0;
	}
}
