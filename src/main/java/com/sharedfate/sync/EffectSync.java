package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkManager;
import com.sharedfate.perk.PerkStatusEffects;
import com.sharedfate.perk.PerkStatusStack;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 팀원끼리 상태이상을 공유한다.
 *
 * <p>증강({@link com.sharedfate.perk.PerkManager})이 건 상태이상은 이 공유에서 빠진다.
 * 팀 상태에 섞여 저장되면 증강을 잃은 뒤에도 남아 되살아나기 때문이다. 판별은
 * {@link PerkStatusEffects} 가 하고, 여기서는 수집·전파·복원 세 자리에서 그 판별을 쓴다.
 * 보유 증강이 없으면 {@link PerkStatusEffects#of} 가 빈 목록을 돌려준다.
 */
public final class EffectSync {
	private static boolean propagating;
	private static final Map<UUID, Set<Holder<MobEffect>>> PENDING_UPDATES = new HashMap<>();
	/**
	 * 증강 상태이상을 다시 붙여야 하는 플레이어.
	 *
	 * <p>{@code removeAllEffects} 나 {@code removeEffect} 는 밑에 깔린 증강분까지 통째로
	 * 걷어낸다. 그 자리에서 바로 다시 붙이면 상태이상 표를 순회하는 도중에 건드리게 되므로
	 * 여기 적어 두고 다음 틱에 처리한다.
	 */
	private static final Set<UUID> PENDING_PERK_REFRESH = new HashSet<>();

	private EffectSync() {
	}

	public static void register() {
		ServerMobEffectEvents.BEFORE_ADD.register((instance, entity, context) -> {
			if (!propagating && SharedFateMod.config.shareStatusEffects
					&& entity instanceof ServerPlayer player) {
				PENDING_UPDATES.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>())
						.add(instance.getEffect());
			}
		});
		ServerMobEffectEvents.AFTER_ADD.register((instance, entity, context) -> {
			if (entity instanceof ServerPlayer player) {
				onAdded(player, instance);
			}
		});
		ServerMobEffectEvents.AFTER_REMOVE.register((instance, entity, context) -> {
			if (entity instanceof ServerPlayer player) {
				onRemoved(player, instance);
			}
		});
	}

	private static void onAdded(ServerPlayer source, MobEffectInstance instance) {
		if (propagating || !SharedFateMod.config.shareStatusEffects) {
			return;
		}
		MinecraftServer server = source.level().getServer();
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(source.getUUID());
		TeamState state = manager.stateOf(source.getUUID());
		if (team == null || state == null) {
			return;
		}
		clearPending(source.getUUID(), instance.getEffect());

		if (PerkStatusEffects.of(state).grants(instance)) {
			// 증강이 건 상태이상이다. 팀원은 각자 자기 증강으로 이미 갖고 있으므로 공유하지 않는다.
			return;
		}

		state.effects.removeIf(effect -> effect.getEffect().equals(instance.getEffect()));
		state.effects.add(new MobEffectInstance(instance));
		propagating = true;
		try {
			forEachOtherOnline(server, team, source.getUUID(), player ->
					player.addEffect(new MobEffectInstance(instance)));
		} finally {
			propagating = false;
		}
	}

	private static void onRemoved(ServerPlayer source, MobEffectInstance instance) {
		if (propagating || !SharedFateMod.config.shareStatusEffects) {
			return;
		}
		MinecraftServer server = source.level().getServer();
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(source.getUUID());
		TeamState state = manager.stateOf(source.getUUID());
		if (team == null || state == null) {
			return;
		}

		PerkStatusEffects perkEffects = PerkStatusEffects.of(state);
		if (perkEffects.grants(instance)) {
			// 증강분이 벗겨진 것뿐이다. 팀에 퍼뜨리지 말고 본인에게만 다시 붙인다.
			queuePerkRefresh(source.getUUID());
			return;
		}

		state.effects.removeIf(effect -> effect.getEffect().equals(instance.getEffect()));
		propagating = true;
		try {
			forEachOtherOnline(server, team, source.getUUID(), player ->
					player.removeEffect(instance.getEffect()));
		} finally {
			propagating = false;
		}

		if (perkEffects.covers(instance.getEffect())) {
			// removeEffect 는 같은 종류의 증강분까지 함께 걷어낸다. 팀 전체에 다시 붙여 준다.
			for (UUID member : team.members()) {
				queuePerkRefresh(member);
			}
		}
	}

	public static void refreshPlayer(ServerPlayer player) {
		if (!SharedFateMod.config.shareStatusEffects) {
			return;
		}
		TeamState state = TeamLookup.stateOf(player.getUUID());
		if (state == null) {
			return;
		}
		propagating = true;
		try {
			player.removeAllEffects();
			for (MobEffectInstance effect : state.effects) {
				player.addEffect(new MobEffectInstance(effect));
			}
		} finally {
			propagating = false;
		}
		// 팀 상태에는 증강분이 없으므로 방금 지운 증강 상태이상은 따로 다시 붙여야 한다.
		if (!state.ownedPerks.isEmpty()) {
			queuePerkRefresh(player.getUUID());
		}
	}

	public static void clearDetachedPlayer(ServerPlayer player) {
		if (!SharedFateMod.config.shareStatusEffects) {
			return;
		}
		PENDING_PERK_REFRESH.remove(player.getUUID());
		// 증강분도 함께 지워졌다. 출처 기록을 남기면 잃은 증강의 등급이 나중에 되살아난다.
		PerkStatusStack.forget(player.getUUID());
		propagating = true;
		try {
			player.removeAllEffects();
		} finally {
			propagating = false;
		}
	}

	public static void clearPersistedDetachedPlayer(ServerPlayer player) {
		PENDING_PERK_REFRESH.remove(player.getUUID());
		// 증강분도 함께 지워졌다. 출처 기록을 남기면 잃은 증강의 등급이 나중에 되살아난다.
		PerkStatusStack.forget(player.getUUID());
		propagating = true;
		try {
			player.removeAllEffects();
		} finally {
			propagating = false;
		}
	}

	public static void tick(MinecraftServer server) {
		if (!SharedFateMod.config.shareStatusEffects) {
			return;
		}
		reapplyPerkEffects(server);
		TeamManager manager = TeamManager.get(server);
		try {
			for (ShareTeam team : manager.allTeams()) {
				for (UUID member : team.members()) {
					Set<Holder<MobEffect>> updated = PENDING_UPDATES.get(member);
					ServerPlayer source = server.getPlayerList().getPlayer(member);
					if (updated == null || source == null) {
						continue;
					}
					for (Holder<MobEffect> effect : Set.copyOf(updated)) {
						MobEffectInstance current = source.getEffect(effect);
						if (current != null) {
							onAdded(source, current);
						}
					}
				}

				ServerPlayer representative = firstAliveOnline(server, team);
				TeamState state = manager.stateByTeamId(team.teamId());
				if (representative == null || state == null) {
					continue;
				}
				// 증강이 건 상태이상은 팀 상태에 남기지 않는다. 남기면 증강을 잃은 뒤에도 되살아난다.
				List<MobEffectInstance> shareable =
						PerkStatusEffects.of(state).shareable(representative.getActiveEffects());
				state.effects.clear();
				state.effects.addAll(shareable);
			}
		} finally {
			PENDING_UPDATES.clear();
		}
	}

	/**
	 * 이 안에서 붙이고 떼는 상태이상은 팀 공유로 흘려보내지 않는다.
	 *
	 * <p>{@link com.sharedfate.perk.PerkStatusStack} 이 한 종류의 층을 다시 쌓을 때 쓴다. 겉모습이
	 * 그대로인 포션 층을 지웠다 붙이는 일이 팀원에게 퍼지면, 팀원의 같은 종류 증강분까지 함께
	 * 지워진다. 이미 막혀 있는 자리에서 불러도 끝난 뒤 원래대로 돌아간다.
	 */
	public static void withoutSharing(Runnable action) {
		boolean previous = propagating;
		propagating = true;
		try {
			action.run();
		} finally {
			propagating = previous;
		}
	}

	/** 서버가 내려갈 때 남은 예약을 비운다. 다음 서버가 이전 실행의 잔여를 물려받지 않게 한다. */
	public static void reset() {
		PENDING_PERK_REFRESH.clear();
	}

	public static void clearTeamEffects(MinecraftServer server, ShareTeam team, TeamState state) {
		state.effects.clear();
		// 회차 리셋이다. 예약된 증강 재적용이 남아 있으면 방금 지운 것이 다음 틱에 되살아난다.
		PENDING_PERK_REFRESH.removeAll(team.members());
		if (!SharedFateMod.config.shareStatusEffects) {
			return;
		}
		propagating = true;
		try {
			for (UUID member : team.members()) {
				ServerPlayer player = server.getPlayerList().getPlayer(member);
				if (player != null) {
					player.removeAllEffects();
				}
				// 증강분도 함께 지워졌다. 다시 붙일 것은 refreshPlayer 가 처음부터 다시 적는다.
				PerkStatusStack.forget(member);
			}
		} finally {
			propagating = false;
		}
	}

	private static ServerPlayer firstAliveOnline(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null && !player.isRemoved() && !player.isDeadOrDying()) {
				return player;
			}
		}
		return null;
	}

	private static void forEachOtherOnline(MinecraftServer server, ShareTeam team, UUID source,
			java.util.function.Consumer<ServerPlayer> action) {
		for (UUID member : team.members()) {
			if (member.equals(source)) {
				continue;
			}
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				action.accept(player);
			}
		}
	}

	/** 증강 상태이상이 벗겨진 플레이어를 적어 둔다. 실제 재적용은 다음 틱에 한다. */
	private static void queuePerkRefresh(UUID player) {
		PENDING_PERK_REFRESH.add(player);
	}

	/**
	 * 예약된 플레이어에게 증강 효과를 다시 붙인다.
	 *
	 * <p>{@code propagating} 을 켜 두어 여기서 붙는 상태이상이 다시 팀 공유로 흘러가지 않게 한다.
	 * {@code PerkManager.refreshPlayer} 는 몇 번 불러도 같은 결과가 되도록 만들어져 있어
	 * 중복 누적 걱정은 없다.
	 */
	private static void reapplyPerkEffects(MinecraftServer server) {
		if (PENDING_PERK_REFRESH.isEmpty()) {
			return;
		}
		List<UUID> targets = List.copyOf(PENDING_PERK_REFRESH);
		PENDING_PERK_REFRESH.clear();
		propagating = true;
		try {
			for (UUID id : targets) {
				ServerPlayer player = server.getPlayerList().getPlayer(id);
				if (player != null) {
					PerkManager.refreshPlayer(player);
				}
			}
		} finally {
			propagating = false;
		}
	}

	private static void clearPending(UUID player, Holder<MobEffect> effect) {
		Set<Holder<MobEffect>> pending = PENDING_UPDATES.get(player);
		if (pending != null) {
			pending.remove(effect);
			if (pending.isEmpty()) {
				PENDING_UPDATES.remove(player);
			}
		}
	}
}
