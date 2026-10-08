package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.CompassToggleEffect;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 나침반 토글({@code compass_toggle})의 상태와 우클릭 처리.
 *
 * <p>세트 「개척 2」가 열려 있는 동안, 팀의 나침반을 우클릭하면 <b>마을 ↔ 엔더 요새</b>가
 * 번갈아 바뀐다. 실제로 바늘을 돌리는 일은 {@link PerkCompassTargets} 가 하고, 여기는
 * 「지금 어느 쪽인가」와 「눌렀을 때 무엇을 하는가」만 맡는다.
 *
 * <h2>상태는 팀마다 하나뿐이다</h2>
 * <p>인벤토리가 팀 공유라 나침반도 팀에 하나뿐이고, 지시 자리도 팀에 하나뿐이다
 * ({@link PerkCompassTargets} 의 머리말). 그래서 토글도 사람마다가 아니라 <b>팀마다</b> 하나다.
 * 사람마다 따로 두면 A 가 엔더 요새로 돌린 바늘을 B 는 마을로 본다고 믿게 되는데, 실제
 * 나침반은 하나뿐이라 둘 중 한 명은 반드시 거짓을 본다.
 *
 * <h2>기억은 메모리에만 둔다 — 그래야 세트가 깨질 때 되돌아간다</h2>
 * <p><b>이것이 가장 중요한 결정이다.</b> 세트를 잃었는데 나침반이 엔더 요새를 가리킨 채로
 * 남으면, 세트 없이 세트 효과를 쓰는 것이 된다.
 *
 * <p>되돌리는 길은 둘이고, 둘 다 「켬을 지우는 쪽」이라 어느 하나만 살아도 안전하다.
 *
 * <ol>
 *   <li>{@link PerkCompassTargets#tick} 이 20틱마다 모든 팀을 훑으면서
 *       {@link #forgetIfUnavailable} 을 부른다. 토글 효과가 사라진 팀의 켬은 그때 지워진다.
 *       증강을 잃든, 세트가 깨지든, 「환골탈태」가 목록을 갈아엎든 전부 같은 자리로 온다.</li>
 *   <li>저장하지 않는다. 서버를 껐다 켜면 비어 있고, 그때는 마을부터 시작한다.</li>
 * </ol>
 *
 * <p>「세트가 켜져 있는 동안만 켬을 인정한다」로 읽는 편이 정확하다. {@link #isAlternate} 는
 * 기억에 켬이 적혀 있어도 <b>지금 토글 효과가 없으면 거짓</b>을 돌려준다. 그래서 위 훑기가
 * 한 주기 늦어도 그 사이에 세트 없이 엔더 요새를 가리키는 일이 없다.
 */
public final class PerkCompassToggle {
	/** 채팅 앞에 붙이는 말. 다른 증강들과 같은 것을 쓴다. */
	public static final String PREFIX = "[증강] ";

	/**
	 * 대체 목표로 돌려 둔 팀들.
	 *
	 * <p>켠 팀만 들어 있다. 끄면 지운다 — 「거짓」을 적어 두면 세트를 잃은 팀의 찌꺼기와
	 * 구분할 수 없다.
	 */
	private static final Set<UUID> ALTERNATE = new HashSet<>();

	/** 같은 경고로 로그를 채우지 않기 위한 표시. */
	private static volatile boolean warned;

	private PerkCompassToggle() {
	}

	/** 서버가 멈출 때나 시험에서 상태를 격리할 때 쓴다. */
	public static synchronized void reset() {
		ALTERNATE.clear();
		warned = false;
	}

	// ------------------------------------------------------------------ 상태

	/**
	 * 이 팀이 가진 나침반 토글 효과. 없으면 {@code null}.
	 *
	 * <p>보유 증강과 켜진 세트 단계를 <b>둘 다</b> 훑는다. 지금은 세트 쪽에만 있지만, 증강 쪽을
	 * 빼 두면 나중에 증강 하나가 같은 효과를 갖게 됐을 때 조용히 아무 일도 안 하게 된다.
	 */
	public static @Nullable CompassToggleEffect effectOf(@Nullable TeamState state) {
		TeamState active = PerkWorldRules.activeState(state);
		if (active == null) {
			return null;
		}
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof CompassToggleEffect toggle) {
					return toggle;
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(active)) {
			if (effect instanceof CompassToggleEffect toggle) {
				return toggle;
			}
		}
		return null;
	}

	/**
	 * 이 팀의 나침반이 지금 <b>대체 목표</b>(엔더 요새)를 가리키고 있는가.
	 *
	 * <p>기억에 켬이 적혀 있어도 토글 효과가 없으면 거짓이다. 세트가 깨진 그 틱부터 곧바로
	 * 마을로 읽힌다 — 훑기가 켬을 지워 주기를 기다리지 않는다.
	 */
	public static synchronized boolean isAlternate(@Nullable UUID teamId,
			@Nullable CompassToggleEffect effect) {
		return teamId != null && effect != null && ALTERNATE.contains(teamId);
	}

	/**
	 * 토글 효과가 없어졌으면 켬을 지운다.
	 *
	 * <p>{@link PerkCompassTargets#tick} 이 팀마다 부른다. 여기서 지워 두지 않으면, 세트를
	 * 잃었다가 나중에 다시 채웠을 때 <b>누른 적도 없는데</b> 엔더 요새를 가리킨 채로 돌아온다.
	 *
	 * @return 실제로 지웠으면 참
	 */
	public static synchronized boolean forgetIfUnavailable(@Nullable UUID teamId,
			@Nullable CompassToggleEffect effect) {
		return effect == null && teamId != null && ALTERNATE.remove(teamId);
	}

	/** 해체된 팀의 기억을 턴다. */
	public static synchronized void retainAll(Set<UUID> living) {
		ALTERNATE.retainAll(living);
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
		// 주손만 본다. 왼손은 팀이 함께 쓰는 칸이라 주손과 함께 두 번 발화한다.
		if (hand != InteractionHand.MAIN_HAND || level == null || level.isClientSide()
				|| !(player instanceof ServerPlayer user)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (held == null || held.isEmpty() || held.getItem() != Items.COMPASS) {
			return InteractionResult.PASS;
		}

		MinecraftServer server = user.level().getServer();
		if (server == null) {
			return InteractionResult.PASS;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(user.getUUID());
		TeamState state = team == null ? null : manager.stateByTeamId(team.teamId());
		CompassToggleEffect effect = effectOf(state);
		if (team == null || effect == null) {
			// 세트가 없으면 나침반은 평범한 나침반이다. PASS 를 돌려줘야 바닐라 나침반이
			// 자철석에 쓰이는 길(useOn)을 막지 않는다.
			return InteractionResult.PASS;
		}

		boolean nowAlternate;
		synchronized (PerkCompassToggle.class) {
			nowAlternate = !ALTERNATE.remove(team.teamId());
			if (nowAlternate) {
				ALTERNATE.add(team.teamId());
			}
		}

		// 다음 훑기를 기다리지 않고 그 자리에서 바늘과 이름을 맞춘다. 20틱(1초)이면 누른
		// 사람 눈에는 「안 눌렸다」로 보이고 한 번 더 누르게 된다 — 그러면 도로 제자리다.
		PerkCompassTargets.refreshTeam(server, team, state);
		announce(server, team, effect.nameFor(nowAlternate));
		// SUCCESS 를 돌려주면 손을 휘두르는 동작이 나가 눌렸다는 것이 몸으로도 보인다.
		return InteractionResult.SUCCESS;
	}

	/** 팀 전원에게 한 줄 알린다. 나침반은 팀이 함께 쓰므로 누른 사람만 알면 안 된다. */
	private static void announce(MinecraftServer server, ShareTeam team, String name) {
		Component message = Component.literal(PREFIX + "나침반이 이제 " + name + " 입니다.")
				.withStyle(ChatFormatting.GRAY);
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				online.sendSystemMessage(message);
			}
		}
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("나침반 토글을 다루지 못했습니다. 이 경고는 한 번만 남습니다.", error);
	}
}
