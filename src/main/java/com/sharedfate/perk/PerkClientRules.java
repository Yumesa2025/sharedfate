package com.sharedfate.perk;

import com.sharedfate.net.PerkClientFeaturesPayload;
import com.sharedfate.perk.effect.DoubleJumpEffect;
import com.sharedfate.perk.effect.HideHudEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import com.sharedfate.ui.AirJumpImpulse;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 클라이언트가 있어야 성립하는 증강의 서버 쪽 자리.
 *
 * <p>"무엇을 할 수 있는가"는 전부 여기서 답하고, 클라이언트는 그 답을 받아 그리거나 입력을
 * 알릴 뿐이다. 세 가지 증강이 여기를 지난다.
 *
 * <ul>
 *   <li>{@link DoubleJumpEffect} — 서버는 공중 점프 입력을 볼 수 없으므로 클라이언트가
 *       스스로 뛰고 요청을 보낸다. 여기서는 그 요청을 검증해 <b>한 번 뜬 동안 한 번</b>을
 *       세고, 증강이 없는데 뛴 것이면 되돌린다.</li>
 *   <li>{@link HideHudEffect} — 서버에는 그릴 화면이 없으므로 "무엇을 가릴지"만 내려보낸다.</li>
 *   <li>{@link com.sharedfate.perk.effect.FoodCapacityEffect} — 허기 상한만 내려보낸다. 먹을 수
 *       있는가는 클라이언트도 판정하고, 실제로 얼마나 찼는가는 서버가 정해 바닐라 패킷으로 보낸다.</li>
 * </ul>
 *
 * <h2>클라이언트를 믿지 않는다</h2>
 * <p>{@code DoubleJumpPayload} 는 누구나 아무 때나 보낼 수 있다. 그래서 요청이 왔을 때
 * 팀 보유 증강·공중 여부·이번 공중에서 이미 썼는지·최소 간격을 <b>전부 다시</b> 확인하고,
 * 하나라도 어긋나면 조용히 버린다. 거절 응답은 곧 "무엇이 막혔는지" 알려 주는 신호라
 * 오히려 탐색 통로가 된다.
 *
 * <p>마인크래프트에서 <b>위치는 원래 클라이언트가 정한다.</b> 서버는 보고받은 위치를 그대로
 * 받아들이고 틱당 10칸을 넘거나 80틱 넘게 떠 있을 때만 걸러 낸다. 그러니 이 자리의 검증은
 * "몸이 뜨는 것 자체를 막는" 장치가 아니라 <b>증강이 무엇을 허락하는지 정하는 유일한 자리</b>다.
 * 증강이 없는 팀원이 뛰면 {@code cancelPrediction} 이 속도를 되돌리고, 증강이 있어도 한 번 뜬
 * 동안 두 번째는 세어 주지 않는다. 세기와 미는 방향도 서버가 증강 정의에서 읽는다.
 *
 * <h2>땅을 밟았는지 누가 세는가</h2>
 * <p>공중 점프를 "한 번"으로 묶어 두는 일은 클라이언트도 하지만 그것만으로는 부족하다.
 * 최소 간격만 지키면서 요청을 계속 보내면 끝없이 떠오를 수 있기 때문이다. 그래서 서버도
 * 매 틱 팀원의 접지 여부를 보고, 땅·물·사다리에 닿는 순간 사용 표시를 지운다. 결과적으로
 * <b>한 번 뜬 동안 공중 점프는 최대 한 번</b>이라는 약속이 서버 쪽에서도 지켜진다.
 */
public final class PerkClientRules {
	/** 보유 기능이 바뀌었는지 확인하는 주기. 매 틱 확인할 필요가 없다. */
	private static final int SCAN_INTERVAL_TICKS = 10;

	/**
	 * 공중 점프 요청을 두 번 받아들이는 사이의 최소 간격.
	 *
	 * <p>접지 판정만으로도 한 번으로 묶이지만, 접지 판정이 한 틱 깜빡이는 자리(계단 모서리,
	 * 배 위)에서 요청이 연달아 통과하는 일을 막아 둔다.
	 */
	private static final int MIN_REQUEST_INTERVAL_TICKS = 10;

	/** 팀원별로 마지막에 보낸 내용. 달라졌을 때만 다시 보낸다. */
	private static final Map<UUID, PerkClientFeaturesPayload> LAST_SENT = new HashMap<>();
	/** 지금 뜬 상태에서 공중 점프를 이미 쓴 팀원. 땅에 닿으면 빠진다. */
	private static final Set<UUID> AIR_JUMP_USED = new HashSet<>();
	/** 팀원별로 마지막 공중 점프를 받아들인 서버 틱. */
	private static final Map<UUID, Integer> LAST_ACCEPTED_TICK = new HashMap<>();
	/** 팀원별로 마지막으로 예측을 되돌린 서버 틱. */
	private static final Map<UUID, Integer> LAST_CORRECTED_TICK = new HashMap<>();

	private static int scanCooldown;

	private PerkClientRules() {
	}

	// ------------------------------------------------------------------ 빠른 경로

	/**
	 * 클라이언트 기능을 따질 값어치가 있는 팀 상태인가.
	 *
	 * @return 따질 만하면 그 상태, 아니면 null
	 */
	public static @Nullable TeamState activeState(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return null;
		}
		return state;
	}

	// ------------------------------------------------------------------ 보유 기능 계산

	/**
	 * 이 팀이 클라이언트에게 시켜야 하는 일을 모아 패킷 하나로 만든다.
	 *
	 * <p>풀에서 사라진 id 는 건너뛴다. 보유 목록에 남아 있어도 정의가 없으면 아무 기능도
	 * 켜지지 않는다는 뜻이다. 공중 점프를 주는 증강을 둘 이상 가졌다면 가장 센 쪽이 이긴다.
	 * 가림은 겹치는 개념이 아니므로 그냥 모두 합친다.
	 */
	public static PerkClientFeaturesPayload featuresOf(@Nullable TeamState state) {
		TeamState active = activeState(state);
		if (active == null) {
			return PerkClientFeaturesPayload.NONE;
		}

		boolean doubleJump = false;
		double power = 0.0;
		Set<HideHudEffect.Element> hidden = EnumSet.noneOf(HideHudEffect.Element.class);

		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof DoubleJumpEffect jump) {
					if (!doubleJump || jump.power() > power) {
						doubleJump = true;
						power = jump.power();
					}
				} else if (effect instanceof HideHudEffect hide) {
					hidden.addAll(hide.elements());
				}
			}
		}
		return PerkClientFeaturesPayload.of(doubleJump, power, hidden,
				PerkFoodCapacity.capacityOf(active),
				DragonPrep.held(active, com.sharedfate.perk.effect.DragonPrepEffect.Kind.ENDLESS_QUIVER));
	}

	/** 이 팀원이 지금 받아야 할 내용. 서버의 팀원이 아니면 {@code NONE}. */
	public static PerkClientFeaturesPayload featuresOf(@Nullable ServerPlayer player) {
		if (player == null) {
			return PerkClientFeaturesPayload.NONE;
		}
		return featuresOf(TeamLookup.stateOf(player.getUUID()));
	}

	// ------------------------------------------------------------------ 주기 처리

	/**
	 * 매 틱 도는 지점.
	 *
	 * <p>접지 판정은 한 틱만 놓쳐도 공중 점프가 두 번 되므로 매 틱 본다. 보유 기능 비교는
	 * 증강이 바뀌는 순간이 드물어 {@link #SCAN_INTERVAL_TICKS} 마다 본다.
	 */
	public static void tick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		trackGround(players);

		if (++scanCooldown < SCAN_INTERVAL_TICKS) {
			return;
		}
		scanCooldown = 0;
		syncFeatures(players);
	}

	/** 땅·물·사다리에 닿은 팀원의 공중 점프 사용 표시를 지운다. */
	private static void trackGround(List<ServerPlayer> players) {
		if (AIR_JUMP_USED.isEmpty()) {
			return;
		}
		for (ServerPlayer player : players) {
			if (grounded(player)) {
				AIR_JUMP_USED.remove(player.getUUID());
			}
		}
	}

	/** 보유 기능이 달라진 팀원에게만 다시 보낸다. */
	private static void syncFeatures(List<ServerPlayer> players) {
		Set<UUID> online = new HashSet<>();
		for (ServerPlayer player : players) {
			UUID playerId = player.getUUID();
			online.add(playerId);
			PerkClientFeaturesPayload features = featuresOf(player);
			if (features.equals(LAST_SENT.get(playerId))) {
				continue;
			}
			if (!ServerPlayNetworking.canSend(player, PerkClientFeaturesPayload.TYPE)) {
				// 이 패킷을 모르는 클라이언트다. 다음 점검에서 다시 시도한다.
				continue;
			}
			ServerPlayNetworking.send(player, features);
			LAST_SENT.put(playerId, features);
		}
		// 나간 사람의 기록은 버린다.
		LAST_SENT.keySet().retainAll(online);
		AIR_JUMP_USED.retainAll(online);
		LAST_ACCEPTED_TICK.keySet().retainAll(online);
		LAST_CORRECTED_TICK.keySet().retainAll(online);
	}

	// ------------------------------------------------------------------ 공중 점프

	/**
	 * 클라이언트가 보낸 공중 점프 요청을 처리한다.
	 *
	 * <p>검증에 실패하면 아무 일도 하지 않는다. 로그도 남기지 않는다. 초당 스무 번까지
	 * 올 수 있는 패킷이라 로그를 남기면 그 자체가 공격 통로가 된다.
	 *
	 * <h2>여기서는 클라이언트를 되밀지 않는다</h2>
	 * <p>속도는 클라이언트가 키를 누른 그 틱에 이미 스스로 실었다({@code DoubleJumpHandler}).
	 * 여기서 {@code hurtMarked} 까지 켜면 몇 틱 뒤 도착한 속도 패킷이 그새 줄어든 속도를
	 * 처음 세기로 되돌려 <b>한 번의 점프가 두 번 튄다.</b> 그래서 받아들일 때는 제 쪽 사본에만
	 * 같은 값을 적는다 — 그래야 바닐라의 이동 검사
	 * ({@code ServerGamePacketListenerImpl} 가 {@code getDeltaMovement().lengthSqr()} 를
	 * 기준으로 삼는다)와 다른 코드가 같은 값을 본다. 바닐라도 땅 점프를 이렇게 다룬다.
	 * 클라이언트가 먼저 뛰고, 서버는 위치 보고를 보고 뒤따라 {@code jumpFromGround()} 를
	 * 부를 뿐 뛴 사람에게 속도를 되돌려 보내지 않는다.
	 *
	 * <p>기준으로 삼는 것은 서버가 굴린 {@code getDeltaMovement()} 가 아니라
	 * {@code getKnownMovement()} 다. 플레이어의 위치는 클라이언트가 보고하는 것으로 덮어쓰이는데
	 * 속도 사본은 그러지 않아 서서히 어긋나기 때문이다. {@code getKnownMovement()} 는 바로
	 * 그 보고에서 뽑은 값이라 클라이언트가 실제로 움직인 속도에 가장 가깝다.
	 *
	 * <h2>세기는 덮어쓰되 깎지는 않는다</h2>
	 * <p>{@link AirJumpImpulse} 가 정한다. 클라이언트도 같은 함수를 쓰므로 양쪽 값이 어긋나지
	 * 않는다.
	 */
	public static void onDoubleJumpRequest(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		PerkClientFeaturesPayload features = featuresOf(player);
		if (!features.doubleJump()) {
			// 이 팀에는 그 증강이 없다. 그런데도 요청이 왔다면 상대가 제멋대로 뛴 것이므로
			// 미리 뜬 몸을 되돌린다.
			cancelPrediction(player);
			return;
		}
		if (player.isSpectator() || player.getAbilities().flying) {
			return;
		}
		if (grounded(player) || player.isPassenger() || player.isFallFlying()) {
			return;
		}

		UUID playerId = player.getUUID();
		if (AIR_JUMP_USED.contains(playerId)) {
			// 이번에 뜬 동안 이미 썼다.
			return;
		}

		// 간격 검사가 먼저다. 여기서 걸린 요청 때문에 사용 표시가 켜지면, 정작 정상 요청이
		// 한 번도 통하지 않은 채로 이번 공중 점프를 잃는다.
		int now = player.tickCount;
		Integer last = LAST_ACCEPTED_TICK.get(playerId);
		// 부활하면 tickCount 가 0 부터 다시 세므로 지난 값보다 작아질 수 있다. 그때는 막지 않는다.
		if (last != null && now >= last && now - last < MIN_REQUEST_INTERVAL_TICKS) {
			return;
		}
		AIR_JUMP_USED.add(playerId);
		LAST_ACCEPTED_TICK.put(playerId, now);

		Vec3 known = player.getKnownMovement();
		AirJumpImpulse.Velocity pushed = AirJumpImpulse.of(known.x, known.y, known.z,
				features.doubleJumpPower());
		player.setDeltaMovement(pushed.x(), pushed.y(), pushed.z());
		playJumpSound(player);
	}

	/**
	 * 거절한 요청 때문에 클라이언트가 미리 띄운 몸을 되돌린다.
	 *
	 * <p>{@code hurtMarked} 를 켜면 {@code ServerEntity} 가 <b>본인에게도</b> 속도 패킷을
	 * 내려보내고, 그것이 {@code Entity.lerpMotion} 을 거쳐 클라이언트의 속도를 덮어쓴다.
	 * 실어 보내는 값은 {@code getKnownMovement()} — 바로 직전에 클라이언트 스스로 보고한
	 * 움직임이다. 서버가 굴린 속도 사본은 위치와 달리 보고로 덮어쓰이지 않아 어긋날 수 있고,
	 * 어긋난 값을 되돌려 보내면 멀쩡한 사람의 화면이 튄다.
	 *
	 * <p><b>증강이 없다</b>는 거절에서만 부른다. "이번에 이미 썼다"·"지금 땅이다" 같은 거절은
	 * 서버와 클라이언트의 판정이 한 틱 어긋나는 것만으로도 생기므로, 그때까지 되밀면 멀쩡한
	 * 사람이 한 틱짜리 어긋남 때문에 아래로 끌려 내려온다. 그 경우 잃는 것은 기껏해야 도약
	 * 한 번이고, 한 번 뜬 동안 한 번이라는 약속은 {@link #AIR_JUMP_USED} 가 여전히 지킨다.
	 *
	 * <p>{@link #MIN_REQUEST_INTERVAL_TICKS} 마다 한 번만 보낸다. 요청을 쏟아붓는 것으로
	 * 서버가 패킷을 쏟아내게 만들 수 있으면 그 자체가 공격 통로다.
	 */
	private static void cancelPrediction(ServerPlayer player) {
		UUID playerId = player.getUUID();
		int now = player.tickCount;
		Integer last = LAST_CORRECTED_TICK.get(playerId);
		if (last != null && now >= last && now - last < MIN_REQUEST_INTERVAL_TICKS) {
			return;
		}
		LAST_CORRECTED_TICK.put(playerId, now);
		player.setDeltaMovement(player.getKnownMovement());
		player.syncVelocity = true;
	}

	/**
	 * 공중 점프가 실제로 받아들여졌을 때 나는 소리.
	 *
	 * <p><b>주변에 들린다.</b> 세기를 0.7 로 낮춰 들리는 거리를 열 칸 남짓으로 줄였다.
	 *
	 * <p>첫 인자가 "이 소리를 낸 사람"이다. 서버에서는 <b>그 사람만 빼고</b> 주변에 나가고,
	 * 클라이언트에서는 반대로 그 사람에게만 울린다. 뛴 사람은 키를 누른 그 틱에 스스로 소리를
	 * 내므로({@code DoubleJumpHandler}) 몸이 떠오르는 순간과 소리가 정확히 같이 오고, 여기서
	 * 빼 두었으므로 같은 소리를 두 번 듣지도 않는다.
	 */
	private static void playJumpSound(ServerPlayer player) {
		player.level().playSound(player, player.getX(), player.getY(), player.getZ(),
				SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, 0.7F, 1.0F);
	}

	/** 다시 뛸 수 있는 상태인가. 땅·물·사다리를 모두 "발판"으로 본다. */
	private static boolean grounded(ServerPlayer player) {
		return player.onGround() || player.isInLiquid() || player.onClimbable();
	}

	// ------------------------------------------------------------------ 정리

	/**
	 * 한 팀원의 기록을 버린다. 접속이 끊길 때 부른다.
	 *
	 * <p>{@link #LAST_SENT} 를 지우는 것이 중요하다. 다시 들어온 클라이언트는 캐시를 비운
	 * 채로 시작하는데, 서버가 "이미 보냈다"고 여기면 증강이 그대로인 팀원은 다시 접속해도
	 * 공중 점프가 되지 않는다.
	 */
	public static void forget(UUID playerId) {
		if (playerId == null) {
			return;
		}
		LAST_SENT.remove(playerId);
		AIR_JUMP_USED.remove(playerId);
		LAST_ACCEPTED_TICK.remove(playerId);
		LAST_CORRECTED_TICK.remove(playerId);
	}

	/** 서버가 멈출 때 들고 있던 기록을 버린다. */
	public static void reset() {
		LAST_SENT.clear();
		AIR_JUMP_USED.clear();
		LAST_ACCEPTED_TICK.clear();
		LAST_CORRECTED_TICK.clear();
		scanCooldown = 0;
	}
}
