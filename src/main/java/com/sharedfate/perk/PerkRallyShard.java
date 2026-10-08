package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.effect.RallyShardEffect;
import com.sharedfate.sync.EndFightTeleportLock;
import com.sharedfate.sync.RallyShardCooldown;
import com.sharedfate.sync.RallyShardManager;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@code rally_shard} 증강(프리즘 「소집의 조각」)의 집행부.
 *
 * <p>조각을 주 손에 들고 <b>허공을</b> 우클릭하면 나를 뺀 팀원 전원이 굳었다가 내 자리로
 * 끌려온다. 그다음 쿨타임이 걸린다.
 *
 * <h2>등록 지점</h2>
 * <p>{@code UseItemCallback.EVENT} 에 붙는다. {@link PerkDiamondSundial}·{@link PerkOreExchange}
 * 와 같은 자리이고, 셋 다 자기 아이템이 아니면 {@code PASS} 를 돌려주므로 서로 부딪히지 않는다.
 * 메아리 조각은 바닐라에서 허공 우클릭에 아무 동작이 없어 가로채도 잃는 것이 없다.
 *
 * <h2>통신 규약을 올리지 않는다</h2>
 * <p>파티클·타이틀·액션바·소리가 모두 바닐라 패킷이고, 쿨타임 게이지는 바닐라
 * {@code minecraft:use_cooldown} 컴포넌트가 그린다. 모드를 깔지 않은 클라이언트도 그대로 본다.
 *
 * <h2>실제로 끌어오는 일은 여기서 하지 않는다</h2>
 * <p>굳히기·파티클·순차 진행·폭발 예약은 {@link RallyShardManager} 가 여러 틱에 걸쳐 한다.
 * 이 클래스는 <b>쓸 수 있는 상황인지 가리고 한 번 시작시키는 것</b>까지만 한다.
 */
public final class PerkRallyShard {
	/** 액션바·채팅 머리말. 다른 증강 알림과 같은 모양으로 맞춘다. */
	public static final String PREFIX = "[증강] ";

	private static volatile boolean warned;

	private PerkRallyShard() {
	}

	// ------------------------------------------------------------------ 지급

	/**
	 * 증강 하나가 가진 {@code rally_shard} 효과의 조각을 지급한다.
	 *
	 * <p>{@link PerkDiamondSundial#grantOnChoice} 와 같은 자리·같은 규칙이다. 부르는 곳은
	 * {@link PerkGrantChain#run} 하나뿐이다 — {@code PerkEffect.apply} 에서 주면 접속할 때마다
	 * 조각이 늘어난다.
	 *
	 * @return 실제로 지급한 개수. 줄 것이 없었으면 0
	 */
	public static int grantOnChoice(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable Perk perk) {
		if (state == null || perk == null) {
			return 0;
		}

		List<ItemStack> granted = new ArrayList<>();
		for (PerkEffect effect : perk.effects()) {
			if (!(effect instanceof RallyShardEffect shard)) {
				continue;
			}
			try {
				ItemStack stack = shard.createItem();
				if (stack != null && !stack.isEmpty()) {
					granted.add(stack);
				}
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("증강 '{}' 의 소집의 조각 지급에 실패했습니다.", perk.id(), error);
			}
		}
		if (granted.isEmpty()) {
			return 0;
		}

		state.overflowItems.addAll(granted);
		state.restoreOverflow(ExpandedInventoryManager.enabled());
		state.overflowItems.removeIf(ItemStack::isEmpty);
		SharedFateMod.LOGGER.info("[PERK] 증강 {} 소집의 조각 지급={}", perk.id(), granted.size());

		if (server != null && team != null) {
			refreshScreens(server, team);
		}
		return granted.size();
	}

	/** 공유 목록을 직접 고쳤으니 접속 중인 팀원의 화면을 맞춰 준다. */
	private static void refreshScreens(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online == null || online.containerMenu == null) {
				continue;
			}
			online.containerMenu.broadcastChanges();
		}
	}

	// ------------------------------------------------------------------ 우클릭

	/** {@code UseItemCallback.EVENT} 에 붙는 지점. */
	public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		try {
			return handle(player, level, hand);
		} catch (RuntimeException error) {
			warnOnce(error);
			return InteractionResult.PASS;
		}
	}

	private static InteractionResult handle(Player player, Level level, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || level == null || level.isClientSide()
				|| !(player instanceof ServerPlayer user)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!RallyShardEffect.isRallyShard(held)) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = user.level().getServer();
		if (server == null) {
			return InteractionResult.PASS;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(user.getUUID());
		TeamState state = team == null ? null : manager.stateByTeamId(team.teamId());
		if (team == null || state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return InteractionResult.PASS;
		}
		RallyShardEffect effect = effectOf(state);
		if (effect == null) {
			// 증강을 잃었으면 조각이 남아 있어도 아무 일도 하지 않는다.
			return InteractionResult.PASS;
		}
		// 쿨타임은 사람이 아니라 팀이 쓴다. 조각이 공유 인벤토리에 있어 누구나 집어 쓸 수
		// 있으므로, 사람마다 따로 돌면 번갈아 눌러 쿨타임을 인원수만큼 나눠 버린다.
		if (RallyShardCooldown.onCooldown(team.teamId())
				|| user.getCooldowns().isOnCooldown(held)) {
			return InteractionResult.FAIL;
		}
		// 회차가 시작되기 전에는 위치를 건드리지 않는다. 「게임 시작」이 사람을 스폰으로 모으는
		// 시점과 겹치면 모였던 사람들이 도로 흩어진다.
		if (!state.runStarted) {
			refuse(user, "회차가 시작되기 전에는 쓸 수 없습니다");
			return InteractionResult.FAIL;
		}
		if (RallyShardManager.hasActiveSummon(team.teamId())) {
			refuse(user, "이미 소집이 진행 중입니다");
			return InteractionResult.FAIL;
		}
		// 엔드 전투 중에 엔드 밖에서 부르면 팀 전원이 엔드에서 끌려 나온다. 조각을 삼키고
		// 아무 일도 안 일어난 것처럼 보이지 않게, 쿨타임을 걸기 전에 까닭을 말하고 돌려보낸다.
		// 엔드 안에서 부르는 소집은 그대로 된다 — 막는 것은 나가는 방향뿐이다.
		if (user.level().dimension() != Level.END
				&& EndFightTeleportLock.inForce(server, team.teamId())) {
			refuse(user, "드래곤전 중에는 엔드 밖으로 부를 수 없습니다");
			return InteractionResult.FAIL;
		}

		List<ServerPlayer> targets = summonTargets(server, team, user);
		if (targets.isEmpty()) {
			refuse(user, "부를 팀원이 없습니다");
			return InteractionResult.FAIL;
		}

		boolean started = RallyShardManager.begin(team, user, targets, effect,
				PerkSwapRules.staggered(state), PerkSwapRules.swapExplosions(state),
				ThreadLocalRandom.current());
		if (!started) {
			return InteractionResult.FAIL;
		}
		// 팀 전원에게 함께 건다. 누른 사람의 바닐라 쿨타임도 그 안에서 걸린다.
		RallyShardCooldown.begin(server, team, effect);
		return InteractionResult.SUCCESS;
	}

	/** 부를 사람들. 접속해 있고 살아 있는 팀원 중 <b>누른 사람은 뺀다.</b> */
	static List<ServerPlayer> summonTargets(MinecraftServer server, ShareTeam team,
			ServerPlayer summoner) {
		List<ServerPlayer> result = new ArrayList<>();
		for (UUID memberId : team.members()) {
			if (memberId.equals(summoner.getUUID())) {
				continue;
			}
			ServerPlayer member = server.getPlayerList().getPlayer(memberId);
			if (member != null && !member.isRemoved() && !member.isDeadOrDying()) {
				result.add(member);
			}
		}
		return result;
	}

	/** 쓸 수 없는 상황을 알린다. 쿨타임은 걸지 않는다. */
	private static void refuse(ServerPlayer user, String reason) {
		user.sendSystemMessage(Component.literal(PREFIX + "소집의 조각: " + reason)
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 팀이 가진 소집의 조각 효과. 없으면 null.
	 *
	 * <p>{@link com.sharedfate.sync.RallyShardCooldown} 도 이것을 쓴다 — 쿨타임이 도는 중에
	 * 들어온 사람에게 게이지를 다시 걸어 주려면 「이 팀의 조각이 몇 초짜리인가」를 알아야 한다.
	 */
	public static @Nullable RallyShardEffect effectOf(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return null;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof RallyShardEffect shard) {
					return shard;
				}
			}
		}
		return null;
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("소집의 조각 처리에 실패했습니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		warned = false;
	}
}
