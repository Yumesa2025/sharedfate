package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.net.PerkCloseOfferPayload;
import com.sharedfate.net.PerkDrawPayload;
import com.sharedfate.net.PerkResultPayload;
import com.sharedfate.net.PerkOfferPayload;
import com.sharedfate.net.PerkVoteSyncPayload;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import com.sharedfate.ui.PerkVoteBoard;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 강제 증강 선택 세션. 시간 정지·무적·제한시간을 한 곳에서 관리한다.
 *
 * <p>레벨 구간에 도달하면 서버가 {@code /tick freeze} 와 같은 방법으로 시간을 멈추고
 * ({@link ServerTickRateManager#setFrozen(boolean)}) 팀 전원 화면에 선택창을 띄운다.
 * 창이 떠 있는 동안 팀원은 어떤 피해도 받지 않고, 제한시간이 지나면 후보 중 하나가 무작위로
 * 선택되며 시간이 다시 흐른다.
 *
 * <p>바닐라의 시간 정지는 플레이어를 얼리지 않는다. 용암·낙하·불·익사는 플레이어 자기 틱에서
 * 계산되므로 얼어 있는 동안에도 그대로 들어온다. 그래서 세션이 사는 동안에는
 * {@code LivingEntityPerkDamageMixin} 이 {@link #blocksDamage(Entity)} 로 피해를 통째로 버린다.
 *
 * <h2>영원히 얼지 않게 하는 장치</h2>
 * <ol>
 *   <li>제한시간은 <b>서버가</b> 센다. 클라이언트가 아무 말도 하지 않아도
 *       {@link #TIMEOUT_TICKS} 이 지나면 무조건 녹는다. 시간 정지 중에도
 *       {@code MinecraftServer.tickServer} 는 매 틱 돌기 때문에 이 카운트다운은 멈추지 않는다.</li>
 *   <li>팀원이 전부 접속을 끊으면 그 자리에서 녹인다. 선택권은 대기열에 그대로 남는다.</li>
 *   <li>매 틱 팀·상태·대기열이 아직 유효한지 다시 확인한다. 하나라도 어긋나면 녹인다.</li>
 *   <li>서버가 멈출 때 녹인다. 게다가 바닐라는 시간 정지 상태를 저장하지 않으므로
 *       재시작하면 원래 풀려 있다. 그래도 켜질 때 한 번 더 확인한다
 *       ({@link #onServerStarted(MinecraftServer)}).</li>
 *   <li>제한시간이 끝났는데 적용에 실패하더라도 그 선택권은 반드시 대기열에서 뺀다.</li>
 * </ol>
 *
 * <h2>얼기 전 상태 복원</h2>
 * 운영자가 {@code /tick freeze} 를 직접 걸어둔 상태였다면 우리가 녹여서는 안 된다.
 * 얼리기 전의 {@link ServerTickRateManager#isFrozen()} 을 기억해 두고 그대로 되돌린다.
 */
public final class PerkChoiceSession {
	/** 선택 제한시간. 60초. */
	public static final int TIMEOUT_TICKS = 1200;
	/**
	 * 선택자를 뽑는 연출 시간. 3.5초.
	 *
	 * <p>이 값은 <b>클라이언트 연출 길이인 동시에 시간이 멈춰 있는 길이</b>다. 서버는 이만큼
	 * 기다렸다가 선택창으로 넘어가고, 클라이언트는 이 값을 {@code PerkDrawPayload} 로 받아
	 * 그 안에서 이름을 굴린다. 그래서 연출을 줄이려면 여기 한 곳만 줄이면 된다.
	 */
	public static final int DRAW_TICKS = 70;
	/**
	 * 고른 증강을 보여 주는 시간. 5초.
	 *
	 * <p>{@link #DRAW_TICKS} 와 마찬가지로 <b>클라이언트 연출 길이인 동시에 시간이 멈춰 있는
	 * 길이</b>다. 서버는 이만큼 더 얼려 두고, 클라이언트는 이 값을 {@code PerkResultPayload} 로
	 * 받아 그대로 「N초 뒤 다시 시작합니다」를 센다. 그래서 늘리려면 여기 한 곳만 고치면 된다.
	 */
	public static final int RESULT_TICKS = 100;

	/** 세션이 지나가는 세 단계. */
	private enum Phase {
		/** 누가 고를지 뽑는 연출 중. 아직 선택창은 뜨지 않았다. */
		DRAW,
		/** 선택창이 떠 있고 제한시간이 흐르는 중. */
		CHOOSE,
		/** 고른 증강을 보여 주는 중. 이 시간이 끝나면 창을 닫고 시간을 녹인다. */
		RESULT
	}

	/** 남은 시간을 채팅으로 한 번 더 알려 주는 지점(초). */
	private static final int[] WARN_SECONDS = {30, 10, 5};

	private static @Nullable State state;

	/** 테스트가 제한시간을 줄일 수 있게 열어 둔 값. 평소에는 {@link #TIMEOUT_TICKS} 이다. */
	private static int timeoutTicks = TIMEOUT_TICKS;

	private PerkChoiceSession() {
	}

	/** 진행 중인 강제 선택 세션 하나. 서버 전체에 동시에 하나만 존재한다. */
	private static final class State {
		final UUID teamId;
		final int milestone;
		/** 얼리기 <b>전</b>의 시간 정지 상태. 녹일 때 이 값으로 되돌린다. */
		final boolean frozenBefore;
		/** 우리가 실제로 얼렸는지. 이미 얼어 있었다면 false 이고 녹일 때 아무것도 하지 않는다. */
		final boolean frozenByUs;
		/** 무적을 걸어 둔 팀원. 세션이 끝나면 통째로 비운다. */
		final Set<UUID> guarded = new LinkedHashSet<>();
		/**
		 * 이 사람이 무적 대상이 된 <b>그 순간</b>의 공기량. 세션이 사는 동안 매 틱 이 값으로
		 * 되돌린다. {@link #blocksDamage} 로 익사 피해는 막아도 공기 게이지 자체는 줄어들기
		 * 때문에, 이걸 안 하면 선택이 길어질수록 산소가 계속 깎이다가 창이 닫히는 순간 몰아서
		 * 익사 피해를 받는다.
		 */
		final Map<UUID, Integer> savedAir = new HashMap<>();
		/**
		 * 선택자가 아닌 사람들이 던져 둔 표. 열쇠가 <b>던진 사람</b>이라 한 사람은 언제나
		 * 한 표뿐이고, 값은 후보 증강 id 이거나 {@link PerkVoteBoard#REROLL_TARGET} 이다.
		 *
		 * <p>표는 <b>제안일 뿐이다.</b> 여기 몇이 모이든 세션이 스스로 무엇을 고르는 일은
		 * 없다 — 이 map 을 읽는 곳은 화면에 보낼 수를 세는 자리 하나뿐이다.
		 *
		 * <p>넣은 차례를 지키는 map 을 쓴다. 화면이 대상을 후보 차례대로 찾아 그리므로 표의
		 * 차례 자체는 보이지 않지만, 차례가 흔들리지 않아야 같은 상태를 두 번 보내지 않는지
		 * 눈으로 확인할 수 있다.
		 */
		final Map<UUID, String> votes = new LinkedHashMap<>();
		/**
		 * 지금 선택자. {@code sendOffer} 가 보낸 것과 같은 값이다.
		 *
		 * <p>{@link #refreshAudience} 가 <b>선택자가 정말 바뀌었는지</b> 가리는 데 쓴다.
		 * 그 메서드는 선택자가 바뀔 때 말고 다른 자리에서도 불릴 수 있어, 무조건 표를 지우면
		 * 애먼 표가 날아간다.
		 */
		@Nullable UUID chooser;
		/**
		 * 지금 화면에 떠 있는 후보. {@code sendOffer} 가 보낸 것을 그대로 둔 사본이다.
		 *
		 * <p><b>결과를 보여 주는 동안 늦게 들어온 사람에게 창을 다시 주려면 이것이 있어야
		 * 한다.</b> 그 시점에는 후보가 {@code TeamState.pending} 에서 이미 빠진 뒤라
		 * ({@code PerkManager.commit} 이 {@code removeFirst} 한다) 대기열에서 다시 읽을
		 * 방법이 없다.
		 */
		List<PerkOfferPayload.PerkOption> options = List.of();
		/** 무엇이 정해졌는가. {@link Phase#RESULT} 에서만 채워져 있다. */
		@Nullable String resultPerkId;
		/** 고른 사람 이름. 시간이 다 되어 무작위로 정해졌으면 빈 문자열. */
		String resultChooserName = "";
		int remainingTicks;
		int nextWarnIndex;
		Phase phase = Phase.DRAW;
		/** 지금 단계가 끝나기까지 남은 틱. DRAW 와 RESULT 에서만 쓴다. */
		int phaseTicks = DRAW_TICKS;

		State(UUID teamId, int milestone, boolean frozenBefore, int remainingTicks) {
			this.teamId = teamId;
			this.milestone = milestone;
			this.frozenBefore = frozenBefore;
			this.frozenByUs = !frozenBefore;
			resetDeadline(remainingTicks);
		}

		/**
		 * 제한시간을 처음부터 다시 센다. 세션을 열 때와 <b>다시 뽑았을 때</b> 같은 길을 지난다.
		 *
		 * <p>예고 지점도 함께 되돌린다. 되돌리지 않으면 다시 뽑은 뒤 60초가 새로 흐르는데도
		 * "30초 남았습니다"가 영영 나오지 않는다.
		 */
		void resetDeadline(int ticks) {
			this.remainingTicks = ticks;
			this.nextWarnIndex = 0;
			// 제한시간이 짧으면 "30초 남았습니다" 같은 예고가 시작하자마자 쏟아진다. 건너뛴다.
			while (nextWarnIndex < WARN_SECONDS.length
					&& ticks <= WARN_SECONDS[nextWarnIndex] * 20) {
				nextWarnIndex++;
			}
		}
	}

	// ------------------------------------------------------------------ 조회

	public static boolean isActive() {
		return state != null;
	}

	/** 진행 중인 세션이 다루는 팀. 없으면 null. */
	public static @Nullable UUID activeTeamId() {
		return state == null ? null : state.teamId;
	}

	/** 진행 중인 세션이 다루는 레벨 구간. 없으면 0. */
	public static int activeMilestone() {
		return state == null ? 0 : state.milestone;
	}

	/** 남은 제한시간(틱). 세션이 없으면 0. */
	public static int remainingTicks() {
		return state == null ? 0 : state.remainingTicks;
	}

	/**
	 * 지금 이 팀·이 구간의 후보를 다시 뽑아도 되는 상태인지.
	 *
	 * <p><b>선택창이 실제로 떠 있는 동안</b>({@link Phase#CHOOSE})만 참이다. 뽑기 연출 중에는
	 * 아직 후보를 본 적이 없어 다시 뽑을 이유가 없고, 결과를 보여 주는 중에는 이미 증강이
	 * 확정돼 대기열에서 빠진 뒤다. 그 두 단계에서 들어온 요청은 조작이거나 늦게 도착한
	 * 패킷이므로 {@code PerkManager} 가 조용히 버린다.
	 */
	public static boolean acceptsReroll(@Nullable UUID teamId, int milestone) {
		State current = state;
		return current != null && current.phase == Phase.CHOOSE
				&& current.teamId.equals(teamId) && current.milestone == milestone;
	}

	/**
	 * 이 대상이 지금 강제 선택창 때문에 무적인지.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} 진입 시점에 부른다. 세션이 없으면
	 * 곧바로 false 라 평소 피해 처리에는 사실상 비용이 없다.
	 */
	public static boolean blocksDamage(@Nullable Entity entity) {
		State current = state;
		if (current == null || current.guarded.isEmpty() || !(entity instanceof ServerPlayer player)) {
			return false;
		}
		return current.guarded.contains(player.getUUID());
	}

	// ------------------------------------------------------------------ 표(제안)

	/**
	 * 선택자가 아닌 사람이 「이걸 하자」고 던진 표를 받는다.
	 *
	 * <p><b>표가 몇 개 모이든 여기서 무엇을 고르는 일은 없다.</b> 이 메서드가 하는 일은
	 * 표를 켜고 끄고 옮긴 뒤 바뀐 수를 팀에 다시 그려 주는 것뿐이다. 자동으로 정해지거나
	 * 선택자가 떠밀리면 안 된다 — 고르는 것은 끝까지 선택자 하나다.
	 *
	 * <p>클라이언트를 믿지 않는다. 다음 중 하나라도 어긋나면 <b>조용히 무시한다</b>
	 * ({@code PerkRerollC2SPayload} 와 같은 길이다 — 지연·재전송된 패킷과 조작된 패킷을 한
	 * 길로 버린다).
	 *
	 * <ul>
	 *   <li>선택창이 떠 있는 단계({@link Phase#CHOOSE})가 아니다 — 뽑기 연출 중에는 아직
	 *       후보를 본 적이 없고, 결과를 보여 주는 중에는 이미 정해진 뒤다</li>
	 *   <li>구간이 다르다 — 늦게 도착한 패킷이 다음 회차의 표를 건드리면 안 된다</li>
	 *   <li>보낸 사람이 이 세션의 팀이 아니다</li>
	 *   <li><b>보낸 사람이 선택자 본인이다</b> — 선택자는 고르면 되지 제안할 것이 없고,
	 *       자기 표 한 개가 팀의 뜻처럼 섞여 보이면 숫자가 거짓이 된다</li>
	 *   <li><b>관전자다</b> — 이 저장소가 다른 곳에서 일관되게 관전자를 빼는 것과 같다</li>
	 *   <li>대상이 지금 후보에도 없고 「다시 뽑자」도 아니다</li>
	 *   <li>「다시 뽑자」인데 남은 횟수가 0 이다 — 쓸 수 없는 것을 제안하면 선택자가
	 *       할 수 없는 일을 하라는 표를 보게 된다</li>
	 * </ul>
	 */
	public static void castVote(ServerPlayer player, int milestone, String target) {
		State current = state;
		if (player == null || current == null || current.phase != Phase.CHOOSE
				|| current.milestone != milestone) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null || player.isSpectator()) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState teamState = manager.stateOf(player.getUUID());
		if (team == null || teamState == null || !team.teamId().equals(current.teamId)
				|| teamState.pending.isEmpty()) {
			return;
		}
		PendingOffer offer = teamState.pending.getFirst();
		if (offer.milestone() != milestone || offer.isChooser(player.getUUID())) {
			return;
		}
		if (!acceptsTarget(offer, teamState, target)) {
			return;
		}

		// 켤지 끌지 옮길지는 서버가 정한다. 클라이언트가 보낸 것은 「이걸 눌렀다」뿐이다.
		UUID voter = player.getUUID();
		String next = PerkVoteBoard.toggle(current.votes.get(voter), target);
		if (next == null) {
			// 같은 것을 다시 눌렀다 — 취소다.
			current.votes.remove(voter);
		} else {
			// 처음 던졌거나 다른 쪽으로 옮겼다. 열쇠가 사람이라 옛 표는 저절로 사라진다.
			current.votes.put(voter, next);
		}
		broadcastVotes(server, team, current);
	}

	/** 표를 던질 수 있는 대상인가. 후보 증강이거나, 쓸 수 있는 「다시 뽑자」다. */
	private static boolean acceptsTarget(PendingOffer offer, TeamState teamState, String target) {
		if (target == null || target.isEmpty()) {
			return false;
		}
		if (PerkVoteBoard.isReroll(target)) {
			return teamState.rerollsRemaining > 0;
		}
		return offer.optionIds().contains(target);
	}

	/**
	 * 표를 통째로 버린다. 버릴 것이 있었으면 팀에 빈 표를 다시 그려 준다.
	 *
	 * <p>부르는 자리는 둘이다 — <b>후보를 다시 뽑았을 때</b>(없어진 카드에 붙은 표는 뜻이
	 * 없다)와 <b>선택자가 정말 바뀌었을 때</b>(앞 선택자에게 하던 제안이고, 새 선택자가 표를
	 * 들고 있으면 안 된다). 뒤쪽은 {@link #refreshAudience} 가 바뀌었는지 먼저 견주고 부른다
	 * — 그 메서드는 선택자가 그대로인 채로도 불릴 수 있어서, 무조건 지우면 아무 일도 없었는데
	 * 체크가 사라진다. <b>사람이 들어오는 것은 여기 해당하지 않는다</b>
	 * ({@link #onMemberJoined}).
	 *
	 * <p>둘 다 바로 뒤에 {@code sendOffer} 로 선택창을 다시 보내는 자리라, 받는 쪽은 창을
	 * 새로 만들며 체크도 함께 버린다 — 그래도 여기서 보내 두는 것은 창을 못 받은 사람
	 * (사망 화면 등)이 옛 체크를 들고 남지 않게 하기 위해서다.
	 *
	 * <p>나머지 둘은 여기를 거치지 않는다. <b>무엇이 정해졌을 때</b>는 결과 화면이 고른 카드
	 * 하나만 남기므로 {@code onChoiceApplied} 가 조용히 비우기만 하고, <b>세션이 통째로 끝나는
	 * 길</b>은 {@link #finish} 가 {@link #state} 를 버리므로 표도 함께 사라진다.
	 */
	private static void clearVotes(MinecraftServer server, ShareTeam team, State current) {
		if (current.votes.isEmpty()) {
			return;
		}
		current.votes.clear();
		broadcastVotes(server, team, current);
	}

	/**
	 * 접속이 끊겼거나 관전자가 된 사람의 표를 거둔다. 거둔 것이 있으면 다시 그려 준다.
	 *
	 * <p><b>나간 사람의 표는 남기지 않는다.</b> 이 장치는 「지금 여기 있는 사람들이 무엇을
	 * 원하는가」를 보여 주는 것이고, 없는 사람의 표는 선택자가 말을 걸 수도 바꿀 수도 없는데
	 * 살아 있는 지지처럼 읽힌다. 다시 들어와도 표는 돌아오지 않는다 — 들어온 사람이 다시
	 * 누르면 그만이고, 그 한 번이 「나는 아직 이걸 원한다」는 뜻이 된다.
	 *
	 * <p>이 저장소가 접속이 끊긴 사람의 클라이언트 상태를 {@code DISCONNECT} 에서
	 * 통째로 잊는 것({@code PerkClientRules.forget} 등)과 같은 모양이다.
	 */
	private static void pruneVotes(MinecraftServer server, ShareTeam team, State current,
			List<ServerPlayer> audience) {
		if (current.votes.isEmpty()) {
			return;
		}
		Set<UUID> present = new HashSet<>();
		for (ServerPlayer member : audience) {
			if (!member.isSpectator()) {
				present.add(member.getUUID());
			}
		}
		if (current.votes.keySet().retainAll(present)) {
			broadcastVotes(server, team, current);
		}
	}

	/**
	 * 지금 표를 세션에 참여 중인 팀원 <b>전원</b>에게 보낸다.
	 *
	 * <p>선택자에게도 보내는 것이 이 장치의 요점이다 — 표는 선택자가 읽으라고 있는 것이다.
	 *
	 * <p>{@code ownVote} 는 받는 사람마다 다르므로 묶음을 사람마다 따로 만든다. 내 표가
	 * 어디 있는지 안 보이면 취소하려다 오히려 옮기게 된다.
	 */
	private static void broadcastVotes(MinecraftServer server, ShareTeam team, State current) {
		if (server == null || team == null) {
			return;
		}
		List<PerkVoteSyncPayload.Tally> tallies = tally(current.votes);
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online == null) {
				continue;
			}
			String own = current.votes.getOrDefault(member, "");
			ServerPlayNetworking.send(online,
					new PerkVoteSyncPayload(current.milestone, tallies, own));
		}
	}

	/**
	 * 표를 대상별 수로 접는다. 0표인 대상은 아예 빠진다.
	 *
	 * <p>세션도 서버도 플레이어도 읽지 않는다. 넘긴 map 하나만 보고 값을 만든다.
	 */
	static List<PerkVoteSyncPayload.Tally> tally(Map<UUID, String> votes) {
		Map<String, Integer> counted = new LinkedHashMap<>();
		for (String target : votes.values()) {
			counted.merge(target, 1, Integer::sum);
		}
		List<PerkVoteSyncPayload.Tally> tallies = new ArrayList<>(counted.size());
		for (Map.Entry<String, Integer> entry : counted.entrySet()) {
			tallies.add(new PerkVoteSyncPayload.Tally(entry.getKey(), entry.getValue()));
		}
		return List.copyOf(tallies);
	}

	// ------------------------------------------------------------------ 공기량 고정

	/**
	 * 이 사람이 지금 처음 무적 대상이 됐다면 지금 공기량을 기억해 둔다. 이미 기억해 둔 사람은
	 * 건드리지 않는다 — 세션 도중 여러 번 불려도(접속·재접속 등) 처음 값만 남아야 한다.
	 */
	private static void captureAir(State current, ServerPlayer player) {
		current.savedAir.putIfAbsent(player.getUUID(), player.getAirSupply());
	}

	/**
	 * 무적 대상 전원의 공기량을 기억해 둔 값으로 되돌린다.
	 *
	 * <p>물 밖에 있던 사람은 공기가 원래 가득 차 있어(기억해 둔 값도 가득) 이 호출이 사실상
	 * 아무 일도 하지 않는다. 물속에서 세션이 시작된 사람은 그 값 그대로 묶여 있다가, 세션이
	 * 끝나면 딱 그 자리(더도 덜도 아닌)에서 다시 줄어들기 시작한다 — 선택 시작 시점에 이미
	 * 산소가 0이었다면 세션이 끝난 뒤에도 여전히 0이라는 뜻이고, 그건 이 증강이 만든 상황이
	 * 아니라 원래 상태이므로 그대로 둔다.
	 */
	private static void restoreAir(State current, List<ServerPlayer> audience) {
		for (ServerPlayer player : audience) {
			Integer restore = airToRestore(current.savedAir.get(player.getUUID()), player.getAirSupply());
			if (restore != null) {
				player.setAirSupply(restore);
			}
		}
	}

	/**
	 * 기억해 둔 값과 지금 값을 보고 되돌려야 할 값을 정한다. 손댈 필요가 없으면 null.
	 *
	 * <p>플레이어를 읽지 않는 순수 계산이라 살아 있는 서버 없이 시험할 수 있다.
	 *
	 * @param saved      세션이 시작될 때 기억해 둔 공기량. 아직 기억한 적이 없으면 null
	 * @param currentAir 지금 공기량
	 */
	static @Nullable Integer airToRestore(@Nullable Integer saved, int currentAir) {
		if (saved == null || saved == currentAir) {
			return null;
		}
		return saved;
	}

	// ------------------------------------------------------------------ 세션 시작

	/**
	 * 이 팀의 첫 대기 선택권으로 강제 선택 세션을 연다.
	 *
	 * <p>다음 경우에는 열지 않는다. 특히 후보가 하나도 없으면 <b>절대 얼리지 않는다.</b>
	 * 고를 것이 없는 창 때문에 서버가 멈추는 것이 가장 나쁘다.
	 *
	 * <ul>
	 *   <li>이미 다른 세션이 진행 중</li>
	 *   <li>{@code perksEnabled} 가 꺼진 팀</li>
	 *   <li>대기 중인 선택권이 없음</li>
	 *   <li>후보가 0개 — 증강 풀이 비었거나 저장된 id 가 전부 풀에서 사라진 경우</li>
	 *   <li>접속 중인 팀원이 없음 — 아무도 볼 수 없는 창 때문에 얼릴 이유가 없다</li>
	 * </ul>
	 *
	 * @return 실제로 세션을 열었으면 true
	 */
	public static boolean begin(MinecraftServer server, ShareTeam team, TeamState teamState) {
		if (server == null || team == null || teamState == null || state != null) {
			return false;
		}
		// 엔드 시련 룰렛도 시간을 멈춘다. 둘이 겹치면 나중에 녹는 쪽이 앞의 상태를 「얼어
		// 있었다」로 기억해 아무도 녹이지 않는 상태가 만들어진다. 선택권은 대기열에 그대로
		// 남으므로 미루는 쪽이 싸다 — 얼어붙은 서버는 콘솔 없이 되돌릴 방법이 없다.
		if (com.sharedfate.sync.TrialFreeze.isActive()) {
			return false;
		}
		if (!teamState.perksEnabled || teamState.pending.isEmpty()) {
			return false;
		}
		PendingOffer offer = teamState.pending.getFirst();
		List<PerkOfferPayload.PerkOption> options = PerkManager.describeOptions(offer);
		if (options.isEmpty()) {
			// 후보가 0개인 선택권은 영원히 풀리지 않는다. 얼리는 대신 여기서 버린다.
			SharedFateMod.LOGGER.warn(
					"{}렙 구간 선택권의 후보가 하나도 남아 있지 않아 강제 선택을 건너뛰고 버립니다.",
					offer.milestone());
			teamState.pending.removeFirst();
			TeamManager.get(server).setDirty();
			return false;
		}
		List<ServerPlayer> audience = onlineMembers(server, team);
		if (audience.isEmpty()) {
			return false;
		}
		if (!hasPlayableMember(audience)) {
			// 접속해 있는 팀원이 <b>전원 관전자</b>다. 고를 수 있는 사람이 하나도 없는데
			// 시간을 60초 멈춰 두면, 아무도 아무것도 못 하다가 무작위로 정해질 뿐이다.
			// 그래서 <b>열지 않는다.</b> 선택권은 대기열에 그대로 남고, 누군가 생존·모험
			// 모드로 돌아오면 다음 감지 주기에 저절로 열린다 — 바로 위의 「접속 중인 팀원이
			// 없음」과 같은 모양이다.
			//
			// 로그를 남기지 않는 것도 같은 이유다. 이 길은 감지 주기마다 다시 지나므로
			// 한 줄씩 적으면 관전 중인 동안 로그가 끝없이 불어난다.
			return false;
		}

		ServerTickRateManager tickRate = server.tickRateManager();
		boolean frozenBefore = tickRate.isFrozen();
		State opened = new State(team.teamId(), offer.milestone(), frozenBefore, timeoutTicks);
		for (ServerPlayer member : audience) {
			opened.guarded.add(member.getUUID());
			captureAir(opened, member);
		}
		state = opened;
		if (!frozenBefore) {
			tickRate.setFrozen(true);
		} else {
			SharedFateMod.LOGGER.info(
					"증강 선택을 시작하지만 서버가 이미 정지 상태였습니다. 선택이 끝나도 정지 상태를 그대로 둡니다.");
		}

		sendDraw(server, offer, audience);
		broadcast(server, team, Component.literal(
				"[증강] " + offer.milestone() + "렙 달성. 시간을 멈추고 선택자를 뽑습니다."));
		return true;
	}

	private static String chooserName(MinecraftServer server, PendingOffer offer) {
		if (offer.chooser().isEmpty()) {
			return "팀원";
		}
		ServerPlayer picked = server.getPlayerList().getPlayer(offer.chooser().get());
		return picked == null ? "팀원" : picked.getGameProfile().name();
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 세션을 한 틱 진행시킨다. {@code PerkManager.tick} 이 매 틱(구간 감지 주기와 무관하게) 부른다.
	 *
	 * <p>시간 정지 중에도 {@code tickServer} 는 그대로 돌아 이 메서드가 계속 호출된다.
	 * 그래서 제한시간은 무슨 일이 있어도 흘러간다.
	 */
	public static void tick(MinecraftServer server) {
		State current = state;
		if (server == null || current == null) {
			return;
		}

		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(current.teamId);
		TeamState teamState = manager.stateByTeamId(current.teamId);
		if (team == null || teamState == null || !teamState.perksEnabled) {
			// 팀이 사라졌거나 증강이 꺼졌다. 붙잡고 있을 이유가 없다.
			finish(server, "팀 상태가 바뀌어 증강 선택을 종료합니다.");
			return;
		}
		if (teamState.pending.isEmpty()
				|| teamState.pending.getFirst().milestone() != current.milestone) {
			// 다른 경로로 이미 처리된 선택권이다.
			finish(server, null);
			return;
		}

		List<ServerPlayer> audience = onlineMembers(server, team);
		if (audience.isEmpty()) {
			// 아무도 없는 서버를 얼려 둘 수는 없다. 선택권은 대기열에 그대로 두고 녹인다.
			finish(server, null);
			SharedFateMod.LOGGER.info(
					"팀원이 모두 접속을 끊어 증강 선택을 중단하고 시간을 다시 흐르게 합니다. 선택권은 남아 있습니다.");
			return;
		}
		// 도중에 들어온 팀원도 무적 대상에 넣는다. 세션이 끝나면 어차피 통째로 비운다.
		for (ServerPlayer member : audience) {
			current.guarded.add(member.getUUID());
			captureAir(current, member);
		}
		// 익사 피해는 무적이 막아 주지만 공기량 자체는 얼어 있는 동안에도 계속 줄어든다.
		// 그대로 두면 선택이 끝나는 순간 이미 산소가 0이라 곧바로 익사 피해를 받는다.
		restoreAir(current, audience);
		// 나갔거나 관전자가 된 사람의 표를 거둔다. DISCONNECT 사건에 따로 걸지 않고 여기서
		// 하는 이유는, 관전 모드로 바꾼 사람에게는 그런 사건이 없기 때문이다. 표는 많아야
		// 팀원 수만큼이라 매 틱 훑어도 값이 없다.
		pruneVotes(server, team, current, audience);

		if (current.phase == Phase.DRAW) {
			if (current.phaseTicks > 0) {
				current.phaseTicks--;
				return;
			}
			// 뽑기 연출이 끝났다. 이제 진짜 선택창을 띄운다.
			current.phase = Phase.CHOOSE;
			PendingOffer offer = teamState.pending.getFirst();
			sendOffer(server, offer, teamState, audience);
			broadcast(server, team, Component.literal(
					"[증강] " + PerkManager.offerGradeLabel(offer) + " 선택권은 "
							+ chooserName(server, offer) + "님에게 있습니다. 제한시간 "
							+ seconds(current.remainingTicks) + "초."));
			return;
		}

		if (current.phase == Phase.RESULT) {
			if (current.phaseTicks > 0) {
				current.phaseTicks--;
				return;
			}
			finish(server, null);
			return;
		}

		if (current.remainingTicks > 0) {
			current.remainingTicks--;
			warnIfNeeded(server, team, current);
			return;
		}

		// 제한시간 종료. 대기열에서 빼고 결과 연출로 넘어간다. 시간은 그때까지 멈춰 있다.
		int milestone = current.milestone;
		UUID teamId = current.teamId;
		PerkManager.applyRandomChoice(server, teamId, milestone);
		// 후보가 하나도 없어 아무것도 고르지 못했으면 결과 화면도 없다. 그대로 녹인다.
		if (state != null && state.phase != Phase.RESULT) {
			finish(server, null);
		}
	}

	private static void warnIfNeeded(MinecraftServer server, ShareTeam team, State current) {
		if (current.nextWarnIndex >= WARN_SECONDS.length) {
			return;
		}
		int threshold = WARN_SECONDS[current.nextWarnIndex];
		if (current.remainingTicks > threshold * 20) {
			return;
		}
		current.nextWarnIndex++;
		broadcast(server, team, Component.literal(
				"[증강] 선택까지 " + threshold + "초 남았습니다."));
	}

	// ------------------------------------------------------------------ 세션 종료

	/** 선택이 성사돼 세션이 할 일을 다 했을 때. */
	public static void onChoiceApplied(MinecraftServer server, UUID teamId, int milestone,
			String perkId, String chooserName) {
		State current = state;
		if (current == null || !current.teamId.equals(teamId) || current.milestone != milestone) {
			return;
		}
		// 곧바로 닫지 않는다. 고른 카드를 잠깐 보여 주고 그때까지 시간도 멈춰 둔다.
		// 바로 닫으면 고른 사람 말고는 무엇이 정해졌는지 모른 채 게임으로 돌아간다.
		current.phase = Phase.RESULT;
		current.phaseTicks = RESULT_TICKS;
		// 정해졌으니 제안할 것이 없다. 결과 화면은 고른 카드 하나만 남기므로 체크가 남아
		// 있으면 「아직 고르는 중」으로 읽힌다.
		current.votes.clear();
		// 결과를 보여 주는 동안 들어온 사람에게도 같은 것을 보여 주려면 기억해 둬야 한다.
		// 이 시점의 후보는 대기열에서 이미 빠졌다.
		current.resultPerkId = perkId;
		current.resultChooserName = chooserName == null ? "" : chooserName;
		PerkResultPayload result = new PerkResultPayload(perkId, chooserName, RESULT_TICKS);
		for (UUID member : new HashSet<>(current.guarded)) {
			ServerPlayer online = server == null ? null : server.getPlayerList().getPlayer(member);
			if (online != null) {
				ServerPlayNetworking.send(online, result);
			}
		}
	}

	/**
	 * 선택자가 바뀌었을 때 열려 있는 창을 <b>팀 전원에게</b> 다시 보낸다.
	 *
	 * <p>선택자가 접속을 끊으면 {@code PerkManager} 가 다른 팀원에게 선택권을 넘긴다. 그때
	 * 새 선택자의 화면이 관전 모드로 남아 있으면 아무도 고를 수 없어 제한시간까지 방치된다.
	 *
	 * <p><b>늦게 들어온 한 사람에게 주는 길은 여기가 아니다</b>({@link #onMemberJoined}).
	 * 여기는 전원에게 다시 보내므로 남들의 창이 통째로 새로 만들어진다 — 한 사람이 들어올
	 * 때마다 팀 전체의 카드가 다시 올라오고, 읽던 툴팁과 펴 둔 「현재 증강」 판이 닫힌다.
	 * 선택 권한이 실제로 바뀌는 자리에서만 그 값을 치를 만하다.
	 */
	public static void refreshAudience(MinecraftServer server) {
		State current = state;
		if (server == null || current == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(current.teamId);
		TeamState teamState = manager.stateByTeamId(current.teamId);
		if (team == null || teamState == null || teamState.pending.isEmpty()) {
			return;
		}
		PendingOffer offer = teamState.pending.getFirst();
		if (offer.milestone() != current.milestone) {
			return;
		}
		List<ServerPlayer> audience = onlineMembers(server, team);
		for (ServerPlayer member : audience) {
			current.guarded.add(member.getUUID());
			captureAir(current, member);
		}
		if (current.phase == Phase.DRAW) {
			// 아직 뽑기 연출 중이다. 선택창을 미리 보내면 남은 연출이 통째로 건너뛰어지고,
			// 그러고도 연출이 끝나는 틱에 sendOffer 가 한 번 더 와서 창이 두 번 만들어진다.
			// 여기서는 <b>바뀐 이름으로 남은 만큼</b> 다시 굴려 주기만 한다.
			PerkDrawPayload draw = drawPayload(server, offer, audience, current.phaseTicks);
			for (ServerPlayer member : audience) {
				ServerPlayNetworking.send(member, draw);
			}
			current.chooser = offer.chooser().orElse(null);
			return;
		}
		// 선택자가 <b>정말 바뀌었을 때만</b> 표를 지운다. 앞 선택자에게 하던 제안이고, 새
		// 선택자가 방금까지 표를 던지던 사람일 수도 있기 때문이다.
		//
		// 무조건 지우지 않는 것이 중요하다. 이 메서드는 공개돼 있고 선택자가 그대로인 채로도
		// 불릴 수 있어서, 그때마다 표를 쓸어버리면 아무 일도 없었는데 체크가 사라진다.
		if (!Objects.equals(current.chooser, offer.chooser().orElse(null))) {
			clearVotes(server, team, current);
		}
		sendOffer(server, offer, teamState, audience);
		// 창이 새로 만들어졌으므로 체크도 통째로 다시 줘야 한다. 화면은 창을 만들 때 표를
		// 빈 채로 시작한다.
		broadcastVotes(server, team, current);
	}

	/**
	 * 세션이 도는 도중에 <b>들어온 한 사람</b>에게 지금 상태를 준다.
	 *
	 * <p>⚠ 이것이 없으면 그 사람은 <b>시간이 멈춘 세상에 아무 창도 없이</b> 서 있게 된다.
	 * 무엇을 기다리는지도 모르고, 남이 고를 때까지 아무것도 못 한다.
	 *
	 * <p>{@link #refreshAudience} 를 쓰지 않는 이유는 둘이다.
	 * <ul>
	 *   <li>그쪽은 <b>전원</b>에게 다시 보내 남들의 창까지 새로 만든다</li>
	 *   <li>그쪽은 대기열({@code TeamState.pending})에서 후보를 읽는데, 결과를 보여 주는
	 *       동안에는 후보가 이미 빠진 뒤라({@code PerkManager.commit}) <b>조용히 되돌아간다</b> —
	 *       그 몇 초 사이에 들어온 사람은 창을 영영 못 받는다</li>
	 * </ul>
	 *
	 * <p>단계마다 주는 것이 다르다.
	 * <ul>
	 *   <li><b>뽑기 연출 중</b> — 남은 만큼의 연출을 준다. 남들과 같은 순간에 멈춘다</li>
	 *   <li><b>선택 중</b> — 선택창과 지금 표. <b>{@code canChoose} 는 이 사람이 선택자인지
	 *       그대로 따른다</b> — 선택자 본인이 튕겼다 들어온 경우가 가장 급하다</li>
	 *   <li><b>결과 중</b> — 기억해 둔 후보로 창을 세우고 곧바로 결과를 얹는다. 처음부터 다시
	 *       고르는 화면이 뜨면 안 된다</li>
	 * </ul>
	 *
	 * <p><b>표는 되살리지 않는다.</b> 나가면서 거둬졌고({@link #pruneVotes}) 그대로 둔다 —
	 * 창을 다시 주는 것과 표를 되살리는 것은 다른 이야기다. 들어온 사람이 다시 누르면 그만이고,
	 * 그 한 번이 「나는 아직 이걸 원한다」는 뜻이 된다.
	 */
	public static void onMemberJoined(MinecraftServer server, ServerPlayer player) {
		State current = state;
		if (server == null || current == null || player == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(current.teamId);
		TeamState teamState = manager.stateByTeamId(current.teamId);
		if (team == null || teamState == null || !team.members().contains(player.getUUID())) {
			return;
		}
		// 들어온 사람도 창이 떠 있는 동안 무적이다. 안 넣으면 얼어 있는 사이에 용암·낙하로
		// 죽는다 — 이 세션이 막아 주려는 바로 그 피해다.
		current.guarded.add(player.getUUID());
		captureAir(current, player);

		if (current.phase == Phase.RESULT) {
			sendResultTo(server, player, current);
			return;
		}
		if (teamState.pending.isEmpty()) {
			return;
		}
		PendingOffer offer = teamState.pending.getFirst();
		if (offer.milestone() != current.milestone) {
			return;
		}
		if (current.phase == Phase.DRAW) {
			ServerPlayNetworking.send(player, drawPayload(server, offer,
					onlineMembers(server, team), current.phaseTicks));
			return;
		}
		ServerPlayNetworking.send(player, new PerkOfferPayload(
				offer.milestone(), offer.isChooser(player.getUUID()), true,
				current.remainingTicks, Math.max(0, teamState.rerollsRemaining),
				PerkManager.describeOptions(offer)));
		// 창을 새로 만든 사람이라 체크가 비어 있다. 지금 표를 한 번 들려 보낸다.
		// 본인 표는 나가면서 거둬졌으므로 언제나 빈 문자열이다.
		ServerPlayNetworking.send(player, new PerkVoteSyncPayload(current.milestone,
				tally(current.votes), current.votes.getOrDefault(player.getUUID(), "")));
	}

	/**
	 * 결과를 보여 주는 도중에 들어온 사람에게 그 화면을 세워 준다.
	 *
	 * <p>묶음을 둘 보낸다 — 먼저 <b>기억해 둔 후보</b>로 선택창을 세우고, 그 위에 결과를
	 * 얹는다. {@code PerkResultPayload} 는 선택창이 떠 있어야만 먹히기 때문이다
	 * ({@code SharedFateClient.showPerkResult}). 둘은 같은 차례로 클라이언트 본 스레드에
	 * 올라가므로 순서가 뒤집히지 않는다.
	 *
	 * <p>{@code canChoose} 는 거짓으로 보낸다. 이미 정해진 뒤라 누구도 고를 것이 없다.
	 *
	 * <p>마감 자리에는 <b>결과를 붙잡아 두는 남은 틱</b>을 싣는다. 화면은 결과를 보여 주는
	 * 동안 제한시간 대신 「N초 뒤 다시 시작합니다」를 그리는데, 그 자리가
	 * {@code hasDeadline()} 뒤에 있어 여기가 0 이면 카운트다운이 통째로 사라진다.
	 *
	 * <p>기억해 둔 것이 없으면 아무것도 안 보낸다. 그런 상태로 창을 세우면 카드가 하나도 없는
	 * 빈 창이 뜨고, 그건 아무 창도 없는 것보다 나쁘다.
	 */
	private static void sendResultTo(MinecraftServer server, ServerPlayer player, State current) {
		if (current.resultPerkId == null || current.options.isEmpty()) {
			return;
		}
		ServerPlayNetworking.send(player, new PerkOfferPayload(current.milestone, false, true,
				Math.max(1, current.phaseTicks), 0, current.options));
		ServerPlayNetworking.send(player, new PerkResultPayload(current.resultPerkId,
				current.resultChooserName, Math.max(1, current.phaseTicks)));
	}

	/**
	 * 후보를 다시 뽑았다. <b>제한시간을 60초로 되돌리고</b> 바뀐 후보를 팀 전원에게 다시 보낸다.
	 *
	 * <p>시간 정지와 무적은 손대지 않는다. 세션은 그대로 살아 있고 단계도 {@link Phase#CHOOSE}
	 * 그대로다 — 다시 뽑기는 선택창 안에서 일어나는 일이지 세션을 다시 여는 일이 아니다.
	 *
	 * <p>{@code PerkManager.applyReroll} 이 대기열을 이미 갈아 끼운 뒤에 부른다.
	 */
	public static void onRerolled(MinecraftServer server, UUID teamId, int milestone) {
		State current = state;
		if (server == null || current == null || !current.teamId.equals(teamId)
				|| current.milestone != milestone || current.phase != Phase.CHOOSE) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(teamId);
		TeamState teamState = manager.stateByTeamId(teamId);
		if (team == null || teamState == null || teamState.pending.isEmpty()) {
			return;
		}
		PendingOffer offer = teamState.pending.getFirst();
		if (offer.milestone() != milestone) {
			return;
		}
		current.resetDeadline(timeoutTicks);
		// 카드가 통째로 갈렸다. 없어진 카드에 붙어 있던 표를 그대로 두면 새 카드에 엉뚱한
		// 체크가 옮겨 붙는다.
		clearVotes(server, team, current);
		sendOffer(server, offer, teamState, onlineMembers(server, team));
	}

	/** 서버가 켜질 때. 이전 실행의 찌꺼기가 남아 있으면 여기서 확실히 털어낸다. */
	public static void onServerStarted(MinecraftServer server) {
		state = null;
		if (server == null) {
			return;
		}
		ServerTickRateManager tickRate = server.tickRateManager();
		if (tickRate.isFrozen()) {
			// 바닐라는 시간 정지를 저장하지 않으므로 여기서 얼어 있으면 비정상이다. 무조건 녹인다.
			tickRate.setFrozen(false);
			SharedFateMod.LOGGER.warn("서버 시작 시점에 시간이 멈춰 있어 강제로 풀었습니다.");
		}
	}

	/** 서버가 멈추기 직전. 얼려 둔 채로 종료하지 않는다. */
	public static void onServerStopping(MinecraftServer server) {
		finish(server, null);
	}

	/** 서버가 완전히 멈춘 뒤. 서버 객체를 만질 수 없으므로 상태만 버린다. */
	public static void reset() {
		state = null;
		timeoutTicks = TIMEOUT_TICKS;
	}

	/**
	 * 세션을 닫는다. <b>이 메서드만이 시간을 다시 흐르게 한다.</b>
	 *
	 * <p>어떤 경로로 들어와도 순서는 같다. 상태를 먼저 비워 재진입을 막고, 시간을 되돌리고,
	 * 마지막에 창을 닫으라고 알린다. 무적은 {@link #state} 가 null 이 되는 순간 함께 풀린다.
	 */
	private static void finish(@Nullable MinecraftServer server, @Nullable String reason) {
		State current = state;
		if (current == null) {
			return;
		}
		// 무적 해제와 재진입 방지를 겸한다. 아래에서 예외가 나도 세션은 이미 죽어 있다.
		state = null;
		if (server == null) {
			return;
		}
		try {
			if (current.frozenByUs) {
				server.tickRateManager().setFrozen(current.frozenBefore);
			}
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.error("시간 정지를 되돌리지 못했습니다. /tick unfreeze 로 풀어 주십시오.", error);
		}
		try {
			closeScreens(server, current);
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("증강 선택창 닫기 지시를 보내지 못했습니다.", error);
		}
		if (reason != null) {
			SharedFateMod.LOGGER.info(reason);
		}
	}

	/** 세션에 참여했던 전원에게 창을 닫으라고 알린다. 관전자 화면도 함께 닫힌다. */
	private static void closeScreens(MinecraftServer server, State current) {
		PerkCloseOfferPayload close = new PerkCloseOfferPayload(current.milestone);
		for (UUID member : new HashSet<>(current.guarded)) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				ServerPlayNetworking.send(online, close);
			}
		}
	}

	// ------------------------------------------------------------------ 보조

	/**
	 * 선택자를 뽑는 연출을 시작하라고 알린다.
	 *
	 * <p>선택자는 서버가 이미 정해 두었다. 클라이언트는 그 결과에서 멈추도록 이름만 굴린다.
	 */
	private static void sendDraw(MinecraftServer server, PendingOffer offer,
			List<ServerPlayer> audience) {
		PerkDrawPayload draw = drawPayload(server, offer, audience, DRAW_TICKS);
		for (ServerPlayer member : audience) {
			ServerPlayNetworking.send(member, draw);
		}
	}

	/**
	 * 뽑기 연출 묶음 하나를 만든다.
	 *
	 * <p>{@code durationTicks} 를 밖에서 받는 이유는 <b>연출 도중에 들어온 사람</b> 때문이다.
	 * 그 사람에게 처음 길이를 그대로 주면 남들보다 늦게 끝나고, 그러면 서버가 선택창을 보내는
	 * 순간에 이름이 아직 굴러가는 화면이 튕겨 나간다. 남은 만큼만 주면 같은 순간에 멈춘다.
	 */
	private static PerkDrawPayload drawPayload(MinecraftServer server, PendingOffer offer,
			List<ServerPlayer> audience, int durationTicks) {
		List<String> names = new ArrayList<>();
		for (ServerPlayer member : audience) {
			names.add(member.getGameProfile().name());
		}
		return new PerkDrawPayload(names, chooserName(server, offer), Math.max(1, durationTicks));
	}

	private static void sendOffer(MinecraftServer server, PendingOffer offer, TeamState teamState,
			List<ServerPlayer> audience) {
		List<PerkOfferPayload.PerkOption> options = PerkManager.describeOptions(offer);
		State current = state;
		int remaining = current == null ? timeoutTicks : current.remainingTicks;
		int rerolls = teamState == null ? 0 : Math.max(0, teamState.rerollsRemaining);
		if (current != null) {
			// 늦게 들어온 사람에게 다시 줄 수 있게 남겨 둔다. 결과 단계가 되면 후보가
			// 대기열에서 빠져 여기 말고는 읽을 곳이 없다.
			current.options = List.copyOf(options);
			current.chooser = offer.chooser().orElse(null);
		}
		for (ServerPlayer member : audience) {
			ServerPlayNetworking.send(member, new PerkOfferPayload(
					offer.milestone(), offer.isChooser(member.getUUID()), true, remaining,
					rerolls, options));
		}
	}

	/**
	 * 이 사람들 중에 <b>실제로 고를 수 있는 사람</b>이 하나라도 있는가.
	 *
	 * <p>관전자는 세지 않는다. 이 저장소가 다른 곳에서 일관되게 관전자를 빼고
	 * ({@code PerkManager.pickChooser} 도 이제 그렇다), 표도 못 던지게 해 두었다.
	 */
	private static boolean hasPlayableMember(List<ServerPlayer> audience) {
		for (ServerPlayer member : audience) {
			if (!member.isSpectator()) {
				return true;
			}
		}
		return false;
	}

	private static List<ServerPlayer> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> online = new ArrayList<>();
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null) {
				online.add(player);
			}
		}
		return online;
	}

	private static void broadcast(MinecraftServer server, ShareTeam team, Component message) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				online.sendSystemMessage(message);
			}
		}
	}

	private static int seconds(int ticks) {
		return Math.max(1, (ticks + 19) / 20);
	}

	// ------------------------------------------------------------------ 테스트 지원

	/** 테스트에서만 쓴다. 제한시간을 짧게 줄여 만료 경로를 확인할 때 필요하다. */
	static void setTimeoutTicksForTesting(int ticks) {
		timeoutTicks = Math.max(1, ticks);
	}

	/** 테스트에서만 쓴다. */
	static int timeoutTicksForTesting() {
		return timeoutTicks;
	}
}
