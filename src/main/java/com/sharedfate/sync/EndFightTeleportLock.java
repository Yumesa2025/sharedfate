package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 엔드 전투 중에는 <b>엔드로 가는 순간이동이 이긴다</b>는 규칙 하나를 집행한다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@link DragonTrialManager} 는 팀원 한 명이라도 엔드에 들어서면 <b>전원</b>을 엔드로
 * 끌어온다. 그런데 이 모드에는 사람을 옮기는 장치가 그것 말고도 다섯 개 더 있고, 그중 셋은
 * <b>옛 좌표</b>를 들고 있다가 나중에 그대로 돌려보낸다.
 *
 * <ul>
 *   <li>{@link PositionSwapManager} 순열 교환 — 엔드 밖 팀원이 하나라도 있으면 엔드 안 사람이
 *       그 자리로 나간다</li>
 *   <li>{@link TeamGathering} 집합(프리즘 「운명 공동체」) — <b>차원이 다르면 거리와 무관하게
 *       「흩어졌다」</b>({@code anyPairTooFar})이고, 정의의 재사용 대기가 20틱(1초)이라 팀이
 *       갈려 있는 동안 <b>초당 한 번</b> 발동한다. 기준점은 무작위라 엔드 밖 사람이 뽑히면
 *       팀 전원이 엔드에서 끌려 나간다</li>
 *   <li>{@link RallyPointManager} 골드 「정거장」 — 모일 때 찍어 둔 좌표로 <b>15초 뒤</b>
 *       돌려보낸다. 그 15초 사이에 엔드 소환이 끼면 전원이 엔드 밖 옛 자리로 되돌아간다</li>
 *   <li>{@link StaggeredSwapManager} 실버 「시차」 — 시퀀스를 시작할 때 찍어 둔 좌표로
 *       5~10초 간격으로 한 명씩 옮긴다. 도중에 엔드 소환이 끼면 남은 걸음이 전부 엔드 밖
 *       옛 자리로 간다</li>
 *   <li>{@link RallyShardManager} 프리즘 「소집의 조각」 — 누른 사람의 <b>지금</b> 자리로
 *       모은다. 누른 사람이 엔드 밖이면 팀 전원이 나간다</li>
 * </ul>
 *
 * <p>옛 좌표로 돌려보내는 셋 중 하나가 한 명을 엔드 밖으로 빼면, 그다음부터 「운명 공동체」가
 * 초당 한 번씩 무작위 기준점을 뽑아 팀을 엔드 안팎으로 왔다 갔다 시킨다. 사람이 겪은 것이
 * 이 상태다.
 *
 * <h2>되돌리지 않고 아예 나가지 않게 한다 — 0틱</h2>
 *
 * <p>「나갔다가 도로 끌어온다」는 아무리 빨라도 <b>1틱</b>이 든다. 그런데 차원을 넘는
 * 순간이동은 클라이언트에 로딩 화면을 띄우고 청크를 새로 받게 하므로, 왕복하면 청크 로드가
 * 두 번이고 그사이 발밑이 아직 안 온 땅일 수 있다 — 공유 체력에서 한 사람의 낙사는 곧 전멸,
 * 곧 월드 삭제다. 그래서 되돌리는 대신 <b>나가는 순간이동 자체를 거절</b>한다. 걸리는 시간은
 * 0틱이고 청크 로드도 0번이다.
 *
 * <p>거절은 {@link #refuse} 가 소리·입자·채팅 한 줄로 알린다. 자막은 쓰지 않는다 —
 * 엔드 전투 중에는 시련 자막이 이미 화면을 쓴다.
 *
 * <h2>언제 잠기는가</h2>
 *
 * <p>둘 중 하나면 잠근다.
 * <ol>
 *   <li>그 팀의 엔드 전투 세션이 열려 있다({@link DragonTrialManager#sessionOf})</li>
 *   <li>아직 세션은 없지만 <b>팀원 하나가 이미 엔드에 있다</b> — 입장 감지부터 전원 소환까지
 *       60틱(3초)의 예고 시간이 있는데, 그사이 「운명 공동체」는 초당 한 번 발동할 수 있어
 *       먼저 들어간 사람이 도로 끌려 나간다. 이 갈래가 그 3초를 덮는다</li>
 * </ol>
 *
 * <p>2번은 {@code dragonHealthPerMember > 0} 일 때만 본다. 그 값이 0 이하인 서버는
 * {@link DragonTrialManager#tick} 이 통째로 돌아가 <b>전원 소환 자체가 없으므로</b>, 잠그면
 * 먼저 들어간 사람만 영영 제자리에 묶인다. 잠그는 조건을 소환하는 조건과 같은 값에 맞춘다.
 *
 * <h2>막지 않는 것 — 일부러 남긴 탈출구</h2>
 *
 * <p>이 클래스는 <b>{@link PositionSwapManager.Position} 을 거치는 모드 자신의 순간이동만</b>
 * 본다. 아래는 손대지 않는다.
 * <ul>
 *   <li>운영자 명령 {@code /tp}, {@code /spawnpoint} 등 바닐라 순간이동</li>
 *   <li>죽음과 리스폰</li>
 *   <li>엔드 포털·귀환 포털 같은 바닐라 차원 이동</li>
 *   <li>{@link SpawnReturn}(회차 시작·팀 해체·운영자 초기화)과
 *       {@code GameStartManager} — {@code player.teleportTo} 를 직접 부르므로 여기를 안 지난다</li>
 * </ul>
 *
 * <h2>스스로는 아무도 옮기지 않는다</h2>
 *
 * <p>거절과 {@link #preferEndAnchor}(이미 일어날 집합의 <b>목적지만</b> 엔드 쪽으로 돌린다)뿐이고
 * 자체 틱도, 주기적으로 사람을 끌어오는 줄도 두지 않는다. 그래서 「A 가 빼고 B 가 넣고」가
 * 매 틱 되풀이되는 순환이 생길 수 없다.
 */
public final class EndFightTeleportLock {
	/**
	 * 같은 사람에게 거절을 다시 알리기까지 기다리는 틱. 2초다.
	 *
	 * <p>「운명 공동체」의 재사용 대기가 20틱(1초)이라, 되돌릴 수 없는 상황(팀원 하나가 엔드
	 * 밖에 있는데 데려올 방법이 없을 때)에서는 거절이 초당 한 번씩 계속 난다. 그것을 그대로
	 * 알리면 채팅이 도배된다. 그렇다고 한 번만 알리면 몇 분 뒤 다시 걸렸을 때 까닭을 모른다.
	 */
	static final int CUE_INTERVAL_TICKS = 40;
	/** 거절 기억을 버리는 나이. 1분이다. 접속을 끊은 사람의 기억이 남지 않게 한다. */
	private static final int CUE_FORGET_TICKS = 20 * 60;

	/** 사람마다 마지막으로 거절을 알린 시각. 값은 그 사람이 있던 차원의 게임 시간이다. */
	private static final Map<UUID, Long> LAST_CUE = new ConcurrentHashMap<>();

	private EndFightTeleportLock() {
	}

	/** 서버가 멈추거나 월드가 바뀔 때 기억을 비운다. */
	public static void reset() {
		LAST_CUE.clear();
	}

	/**
	 * 이 팀에 엔드 규칙이 걸려 있는가.
	 *
	 * <p>판정 근거는 클래스 문서의 「언제 잠기는가」와 같다.
	 */
	public static boolean inForce(@Nullable MinecraftServer server, @Nullable UUID teamId) {
		if (server == null || teamId == null) {
			return false;
		}
		if (DragonTrialManager.sessionOf(teamId) != null) {
			return true;
		}
		// 전원 소환이 꺼진 서버에서는 잠그지 않는다. 잠그는 조건을 소환하는 조건과 같은 값에
		// 맞춰야 「소환은 없는데 나갈 수도 없는」 상태가 생기지 않는다.
		if (SharedFateMod.config == null || SharedFateMod.config.dragonHealthPerMember <= 0) {
			return false;
		}
		ServerLevel end = server.getLevel(Level.END);
		ShareTeam team = TeamManager.get(server).teamById(teamId);
		if (end == null || team == null) {
			return false;
		}
		for (UUID memberId : team.members()) {
			ServerPlayer member = server.getPlayerList().getPlayer(memberId);
			if (member != null && member.level() == end) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 이 사람을 저 차원으로 옮기는 것을 막아야 하는가.
	 *
	 * <p>막는 것은 <b>엔드에 있는 사람을 엔드 밖으로</b> 내보낼 때뿐이다. 엔드로 들어가는
	 * 순간이동과 엔드 밖 사람들끼리의 순간이동은 그대로 통과한다.
	 *
	 * @param player      옮겨질 사람
	 * @param destination 옮겨 갈 차원
	 */
	public static boolean blocks(@Nullable ServerPlayer player, @Nullable ServerLevel destination) {
		if (player == null || destination == null) {
			return false;
		}
		// 엔드로 가는 쪽은 언제나 이긴다. 여기서 먼저 빠져나가므로 아래 팀 조회 비용도 안 든다.
		if (destination.dimension() == Level.END) {
			return false;
		}
		if (player.level().dimension() != Level.END) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		return team != null && inForce(server, team.teamId());
	}

	/**
	 * 여럿을 한곳에 모을 때, 기준점을 <b>엔드 안</b>으로 돌린다.
	 *
	 * <p>{@link #blocks} 만 있으면 기준점이 엔드 밖으로 뽑혔을 때 엔드 안 사람은 전부 거절되고
	 * 팀은 갈린 채로 남는다. 「운명 공동체」는 그 상태에서 초당 한 번 다시 발동하므로 거절만
	 * 끝없이 쏟아진다. 기준점을 엔드로 돌리면 <b>이미 일어날 집합</b>이 팀을 엔드에서 합치는
	 * 쪽으로 끝나 그 자리에서 해소된다 — 사람 말대로 「엔드로 가는 쪽이 이긴다」이다.
	 *
	 * <p>옮기는 사람 수는 달라지지 않는다. 바뀌는 것은 <b>어디로 모이느냐</b>뿐이라 이 클래스가
	 * 스스로 순간이동을 하나 더 만드는 것은 아니다.
	 *
	 * <p>엔드 규칙이 걸리지 않은 팀이거나 엔드에 아무도 없으면 뽑힌 값을 그대로 돌려준다.
	 *
	 * @param players  모일 사람들. 모두 같은 팀이어야 한다
	 * @param proposed 무작위로 뽑힌 기준점의 자리
	 * @return 실제로 쓸 기준점의 자리
	 */
	public static int preferEndAnchor(@Nullable List<ServerPlayer> players, int proposed) {
		if (players == null || proposed < 0 || proposed >= players.size()) {
			return proposed;
		}
		if (players.get(proposed).level().dimension() == Level.END) {
			return proposed;
		}
		MinecraftServer server = players.get(proposed).level().getServer();
		if (server == null) {
			return proposed;
		}
		ShareTeam team = TeamManager.get(server).teamOf(players.get(proposed).getUUID());
		if (team == null || !inForce(server, team.teamId())) {
			return proposed;
		}
		boolean[] inEnd = new boolean[players.size()];
		for (int index = 0; index < players.size(); index++) {
			inEnd[index] = players.get(index).level().dimension() == Level.END;
		}
		return firstInEnd(inEnd, proposed);
	}

	/**
	 * 엔드에 있는 첫 사람의 자리. 아무도 없으면 뽑힌 값을 그대로 돌려준다.
	 *
	 * <p>「첫 사람」인 것에 뜻은 없다 — 엔드 안이기만 하면 어느 자리든 결과가 같다. 무작위로
	 * 다시 뽑지 않는 것은, 같은 입력이면 같은 답이 나와야 시험할 수 있어서다.
	 */
	static int firstInEnd(boolean[] inEnd, int proposed) {
		if (inEnd == null) {
			return proposed;
		}
		for (int index = 0; index < inEnd.length; index++) {
			if (inEnd[index]) {
				return index;
			}
		}
		return proposed;
	}

	/**
	 * 지금 거절을 알려야 하는가.
	 *
	 * @param last 이 사람에게 마지막으로 알린 시각. 알린 적이 없으면 null
	 * @param now  지금 게임 시간
	 */
	static boolean cueDue(@Nullable Long last, long now) {
		if (last == null) {
			return true;
		}
		// now < last 는 월드가 바뀌어 게임 시간이 되감긴 경우다. 그때는 막지 않고 알린다.
		if (now < last) {
			return true;
		}
		return now - last >= CUE_INTERVAL_TICKS;
	}

	/**
	 * 옮기지 않았음을 당사자에게 알린다.
	 *
	 * <p>소리 하나와 입자, 그리고 채팅 한 줄이다. <b>자막은 쓰지 않는다</b> — 엔드 전투 중에는
	 * 시련 예고 자막이 이미 그 자리를 쓰고 있어 서로 덮는다.
	 *
	 * <p>{@link #CUE_INTERVAL_TICKS} 안에 다시 걸리면 조용히 넘어간다.
	 */
	public static void refuse(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		ServerLevel level = player.level();
		long now = level.getGameTime();
		if (!cueDue(LAST_CUE.get(player.getUUID()), now)) {
			return;
		}
		LAST_CUE.put(player.getUUID(), now);
		LAST_CUE.entrySet().removeIf(entry -> now - entry.getValue() > CUE_FORGET_TICKS);

		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ENDER_EYE_DEATH, SoundSource.PLAYERS, 0.9F, 0.6F);
		// 안으로 빨려 드는 입자다. 「밖으로 나가려다 도로 붙잡혔다」가 눈에 보이게.
		level.sendParticles(ParticleTypes.REVERSE_PORTAL,
				player.getX(), player.getY() + 1.0, player.getZ(), 24, 0.4, 0.6, 0.4, 0.05);
		player.sendSystemMessage(Component
				.literal("드래곤전이 끝날 때까지 엔드 밖으로 옮겨지지 않습니다.")
				.withStyle(ChatFormatting.LIGHT_PURPLE));
	}
}
