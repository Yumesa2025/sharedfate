package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.net.PerkOfferPayload;
import com.sharedfate.net.PerkSyncPayload;
import com.sharedfate.perk.effect.NoAttackDamageLossEffect;
import com.sharedfate.perk.effect.NoSilverOffersEffect;
import com.sharedfate.perk.effect.PrismRerollEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 증강 시스템의 허브.
 *
 * <p>레벨 구간 감지 → 후보 추첨 → 대기열 적재 → 선택 적용까지를 담당한다. 실제 효과 계산은
 * {@link PerkEffect} 구현체가, 후보 선정 규칙은 {@link PerkDraft} 가 맡는다.
 *
 * <p>모든 상태는 {@link TeamState} 에 들어 있어 월드와 함께 저장된다. 전멸로 월드가 초기화되면
 * 보유 증강과 대기열은 자동으로 사라진다.
 */
public final class PerkManager {
	/** 구간 감지는 매 틱 할 필요가 없다. 1초에 한 번이면 충분하다. */
	private static final int CHECK_INTERVAL_TICKS = 20;
	private static final int OPTION_COUNT = 3;
	/**
	 * 프리즘 확률을 {@link PerkDraft#PRISM_BOOST_PERCENT} 만큼 얹어 주는 증강.
	 *
	 * <p>정의 파일에서 이 id 가 사라지면 판정이 거짓이 되어 보너스도 함께 사라진다.
	 */
	private static final String PRISM_BOOST_PERK_ID = "sharedfate:expedition_kit";

	private static int tickCounter;

	private PerkManager() {
	}

	/** {@code /shareteam perk} 실행 결과. 명령 쪽에서 안내 문구를 고르는 데 쓴다. */
	public enum OpenResult {
		OPENED,
		SPECTATING,
		NO_TEAM,
		PERKS_DISABLED,
		NOTHING_PENDING
	}

	public static void reset() {
		tickCounter = 0;
		PerkChoiceSession.reset();
		// 세트는 저장하지 않는 파생 상태다. 붙여 준 기록도 함께 비운다.
		PerkSetEffects.reset();
	}

	/** 서버가 켜질 때. 이전 실행에서 얼려 둔 시간이 남아 있지 않은지 확인한다. */
	public static void onServerStarted(MinecraftServer server) {
		tickCounter = 0;
		PerkChoiceSession.onServerStarted(server);
	}

	/** 서버가 멈추기 직전. 얼어 있는 채로 종료하지 않는다. */
	public static void onServerStopping(MinecraftServer server) {
		PerkChoiceSession.onServerStopping(server);
	}

	// ------------------------------------------------------------------ 구간 감지

	public static void tick(MinecraftServer server) {
		if (server == null) {
			return;
		}
		// 강제 선택 세션은 제한시간을 세야 하므로 감지 주기와 무관하게 매 틱 돌린다.
		// 시간이 멈춰 있어도 tickServer 는 그대로 도니 이 카운트다운은 절대 멈추지 않는다.
		PerkChoiceSession.tick(server);

		if (++tickCounter < CHECK_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

		TeamManager manager = TeamManager.get(server);
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			TeamState state = manager.stateByTeamId(team.teamId());
			// 「게임 시작」을 누르기 전에는 구간을 세지 않는다. 시작하는 순간 레벨과 지나온
			// 구간이 함께 0 으로 돌아간다 ({@code GameStartManager}).
			if (state == null || !state.perksEnabled || !state.runStarted) {
				continue;
			}
			boolean changed = advanceMilestones(server, team, state);
			changed |= assignMissingChoosers(server, team, state);
			if (changed) {
				manager.setDirty();
				broadcastSync(server, team, state);
			}
		}
		beginSessionIfIdle(server, manager);
	}

	/**
	 * 대기 중인 선택권이 남아 있으면 강제 선택 세션을 연다.
	 *
	 * <p>세션은 서버 전체에 하나뿐이다. 팀이 여럿이면 한 팀이 끝난 뒤 다음 팀 차례가 온다.
	 * 팀원이 전부 접속을 끊어 중단된 선택권도 누군가 돌아오면 이 경로로 다시 열린다.
	 */
	private static void beginSessionIfIdle(MinecraftServer server, TeamManager manager) {
		if (PerkChoiceSession.isActive()) {
			return;
		}
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null || !state.perksEnabled || !state.runStarted || state.pending.isEmpty()) {
				continue;
			}
			if (PerkChoiceSession.begin(server, team, state)) {
				return;
			}
		}
	}

	/** 아직 처리하지 않은 구간들을 대기열로 밀어넣는다. */
	private static boolean advanceMilestones(MinecraftServer server, ShareTeam team, TeamState state) {
		List<Integer> reached = PerkMilestones.newlyReached(state.lastPerkMilestone, state.xpLevel);
		if (reached.isEmpty()) {
			return false;
		}
		RandomSource random = server.overworld().getRandom();
		boolean silverBlocked = silverOffersBlocked(state);
		boolean prismBoost = state.ownedPerks.contains(PRISM_BOOST_PERK_ID);
		// 한 번에 여러 구간을 지날 때(경험치가 한꺼번에 들어오면 흔하다) 이 루프가 그 구간들의
		// 후보를 **전부 미리** 뽑는다. 그때 state.ownedPerks 는 아직 하나도 안 고른 옛 목록이라,
		// 같은 증강이 두 구간에 함께 뽑힐 수 있다. 실제로 「원정 준비물을 골랐는데 다음 창에
		// 또 떴고, 고르려니 화면이 안 닫혔다」로 터졌다.
		//
		// 그래서 이 루프 안에서 뽑은 것을 함께 쌓아 「이미 가진 것」으로 넘긴다.
		List<String> claimed = new ArrayList<>(state.ownedPerks);
		for (int milestone : reached) {
			// 등급을 여기서 먼저 정하고 뽑기는 그 등급으로 부른다.
			PerkRarity rarity = PerkDraft.rarityFor(milestone, state.extraPrismRounds,
					silverBlocked, prismBoost, random);
			// 팀 설정을 못 채우는 증강은 후보에서 뺀다 — 위치 교환을 끈 팀에게 교환 증강이
			// 뜨면 골라도 아무 일이 없는 죽은 카드가 된다.
			List<String> options = PerkDraft.drawFor(rarity, milestone, PerkRegistry.all(),
					claimed, List.of(), PerkSwapRules.satisfiedRequirements(state),
					random, OPTION_COUNT, silverBlocked);
			claimed.addAll(options);
			state.lastPerkMilestone = milestone;
			if (options.isEmpty()) {
				SharedFateMod.LOGGER.warn(
						"{}렙 구간의 증강 후보를 하나도 뽑지 못해 건너뜁니다. 증강 풀이 비어 있는지 확인하십시오.",
						milestone);
				continue;
			}
			// 고정 구간(15)의 프리즘는 세지 않는다. 확률표가 세는 것은 「확률로 더 나온」
			// 프리즘뿐이고, 고정까지 넣으면 확률로 얻을 수 있는 프리즘가 하나 줄어든다.
			// 후보를 하나도 못 뽑아 건너뛴 라운드도 위에서 이미 빠졌다.
			if (rarity == PerkRarity.PRISM && !PerkDraft.PRISM_MILESTONES.contains(milestone)) {
				state.extraPrismRounds++;
			}
			UUID chooser = pickChooser(server, team, random);
			PendingOffer offer = new PendingOffer(milestone, Optional.ofNullable(chooser), options);
			state.pending.add(offer);
			// 알림은 여기서 하지 않는다. 곧바로 강제 선택 세션이 열리면서
			// PerkChoiceSession 이 구간·등급·선택자·제한시간을 한 번에 알려 준다.
		}
		return true;
	}

	/**
	 * 실버 후보가 통째로 막혀 있는가.
	 *
	 * <p>실버를 막는 것은 {@code no_silver_offers} 효과이고, 지금은 실버 「원정 준비물」 하나가
	 * 가지고 있다. 보유 증강을 돌며 그 효과 형을 찾는 일은
	 * {@link NoSilverOffersEffect#heldBy}가 맡는다.
	 */
	private static boolean silverOffersBlocked(TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return false;
		}
		return NoSilverOffersEffect.heldBy(state);
	}

	/**
	 * 다시 뽑기가 프리즘만 내놓아야 하는가.
	 *
	 * <p>세트 「도박 3단계 — 어차피 프리즘」이 {@code prism_reroll} 표시를 켜고, 그것을 찾는 일은
	 * {@link PrismRerollEffect#heldBy} 가 맡는다.
	 *
	 * <p><b>구간 추첨에는 영향이 없다.</b> 이 판정을 보는 곳은 {@link #applyReroll} 하나뿐이라,
	 * 세트를 모은 팀도 처음 뜨는 후보는 여전히 구간 확률표대로 나온다. 「다시 뽑으면」이라는
	 * 설명 그대로다.
	 */
	private static boolean prismRerollActive(TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return false;
		}
		return PrismRerollEffect.heldBy(state);
	}

	/**
	 * 후보에서 프리즘이 아닌 것을 걸러낸다. 「어차피 프리즘」이 폴백으로 섞인 골드를 버리는 자리다.
	 *
	 * <p>풀에서 사라진 id 도 함께 버린다. 등급을 알 수 없는 후보를 「프리즘일 것」으로 보고
	 * 남겨 두면 그 한 장이 이 세트의 약속을 깨뜨린다.
	 */
	static List<String> onlyPrism(List<String> options) {
		List<String> filtered = new ArrayList<>(options.size());
		for (String id : options) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null && perk.rarity() == PerkRarity.PRISM) {
				filtered.add(id);
			}
		}
		return List.copyOf(filtered);
	}

	/** 발동 당시 아무도 접속해 있지 않았던 선택권에 뒤늦게 선택자를 붙인다. */
	private static boolean assignMissingChoosers(MinecraftServer server, ShareTeam team, TeamState state) {
		boolean changed = false;
		RandomSource random = server.overworld().getRandom();
		for (int i = 0; i < state.pending.size(); i++) {
			PendingOffer offer = state.pending.get(i);
			if (offer.chooser().isPresent()) {
				continue;
			}
			UUID chooser = pickChooser(server, team, random);
			if (chooser == null) {
				continue;
			}
			state.pending.set(i, offer.withChooser(chooser));
			changed = true;
		}
		return changed;
	}

	/**
	 * 선택자 한 사람을 뽑는다. 뽑을 사람이 없으면 null.
	 *
	 * <p><b>관전자는 뽑지 않는다.</b> 관전자는 표도 못 던지는데
	 * ({@code PerkChoiceSession.castVote}) 선택자는 될 수 있으면 앞뒤가 안 맞고, 그 사람에게
	 * 선택권이 가면 <b>시간이 멈춘 채 고를 사람만 없는</b> 상태가 된다. 이 저장소가 다른
	 * 곳에서도 일관되게 관전자를 뺀다.
	 *
	 * <p><b>전원이 관전자면 null 이다.</b> 아무도 접속해 있지 않을 때와 같은 값이고, 그래서
	 * 같은 길을 지난다 — 선택권은 선택자 없이 대기열에 남고
	 * ({@link #assignMissingChoosers} 가 다음 접속 때 다시 본다), 강제 선택 세션은 아예
	 * 열리지 않는다({@code PerkChoiceSession.begin}). 새 예외 경로를 만들지 않는 것이
	 * 중요하다 — 여기서 던지면 팀 하나 때문에 그 틱이 통째로 죽는다.
	 */
	private static @Nullable UUID pickChooser(MinecraftServer server, ShareTeam team, RandomSource random) {
		List<UUID> candidates = playableMembers(server, team);
		if (candidates.isEmpty()) {
			return null;
		}
		return candidates.get(random.nextInt(candidates.size()));
	}

	private static List<UUID> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<UUID> online = new ArrayList<>();
		for (UUID member : team.members()) {
			if (server.getPlayerList().getPlayer(member) != null) {
				online.add(member);
			}
		}
		return online;
	}

	/**
	 * 접속해 있으면서 <b>관전자가 아닌</b> 팀원. 선택자를 뽑을 때 쓰는 후보다.
	 *
	 * <p>{@link #onlineMembers} 와 나눠 둔 것은 쓰임이 달라서다. 무적을 걸거나 알림을 보내는
	 * 자리는 관전자도 대상이지만, <b>고르는 일을 맡기는 자리</b>는 아니다.
	 */
	private static List<UUID> playableMembers(MinecraftServer server, ShareTeam team) {
		List<UUID> playable = new ArrayList<>();
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null && !player.isSpectator()) {
				playable.add(member);
			}
		}
		return playable;
	}

	// ------------------------------------------------------------------ 접속 이벤트

	public static void onPlayerJoin(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null) {
			return;
		}
		refreshPlayer(player);
		if (!state.perksEnabled) {
			return;
		}
		if (assignMissingChoosers(server, team, state)) {
			manager.setDirty();
		}
		broadcastSync(server, team, state);
		// 강제 선택이 진행 중이면 늦게 들어온 사람에게도 창을 띄우고 무적을 걸어 준다.
		//
		// refreshAudience 가 아니라 이쪽인 이유는 둘이다. 그쪽은 (1) 전원에게 다시 보내
		// 남들의 창까지 새로 만들고, (2) 대기열에서 후보를 읽어서 <b>결과를 보여 주는
		// 동안에는 조용히 되돌아간다</b> — 후보가 이미 빠진 뒤라(commit) 그 몇 초 사이에
		// 들어온 사람은 시간이 멈춘 세상에 창 없이 서 있게 된다.
		PerkChoiceSession.onMemberJoined(server, player);
		remindIfChooser(player, state);
	}

	/** 선택권을 가진 사람이 나가면 접속 중인 다른 팀원에게 넘긴다. 후보는 그대로 유지한다. */
	public static void onPlayerLeave(ServerPlayer player) {
		// 다시 들어오면 refreshPlayer 가 처음부터 다시 붙인다.
		PerkSetEffects.forget(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.pending.isEmpty()) {
			return;
		}
		UUID leaving = player.getUUID();
		RandomSource random = server.overworld().getRandom();
		boolean changed = false;
		for (int i = 0; i < state.pending.size(); i++) {
			PendingOffer offer = state.pending.get(i);
			if (!offer.isChooser(leaving)) {
				continue;
			}
			// 넘겨받을 사람도 관전자면 안 된다. 여기서 관전자에게 넘기면 시간이 멈춘 채
			// 고를 사람만 없는 상태가 되고, 제한시간 60초를 다 태워야 풀린다.
			List<UUID> candidates = new ArrayList<>(playableMembers(server, team));
			candidates.remove(leaving);
			UUID next = candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
			state.pending.set(i, offer.withChooser(next));
			changed = true;
			if (next != null) {
				announceOffer(server, team, offer, next);
			}
		}
		if (changed) {
			manager.setDirty();
			broadcastSync(server, team, state);
			// 선택자가 바뀌었으니 남은 사람들의 창을 새 권한으로 다시 띄운다. 이걸 빠뜨리면
			// 아무도 고를 수 없는 채로 제한시간까지 방치된다.
			PerkChoiceSession.refreshAudience(server);
		}
	}

	// ------------------------------------------------------------------ 선택창

	public static OpenResult openOffer(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return OpenResult.NO_TEAM;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null) {
			return OpenResult.NO_TEAM;
		}
		if (!state.perksEnabled) {
			return OpenResult.PERKS_DISABLED;
		}
		if (state.pending.isEmpty()) {
			return OpenResult.NOTHING_PENDING;
		}

		PendingOffer offer = state.pending.getFirst();
		boolean canChoose = offer.isChooser(player.getUUID());
		// 관전 화면이 "○○님이 고르는 중입니다"를 띄우려면 선택자 이름이 먼저 가 있어야 한다.
		broadcastSync(server, team, state);
		if (PerkChoiceSession.isActive()
				&& team.teamId().equals(PerkChoiceSession.activeTeamId())
				&& PerkChoiceSession.activeMilestone() == offer.milestone()) {
			// 강제 선택이 진행 중인데 창을 잃어버린 경우다. 마감이 살아 있는 창으로 되돌려 준다.
			ServerPlayNetworking.send(player, new PerkOfferPayload(offer.milestone(), canChoose,
					true, PerkChoiceSession.remainingTicks(), state.rerollsRemaining,
					describeOptions(offer)));
		} else {
			// 직접 여는 경로. 시간을 멈추지도, 무적을 걸지도 않는 단순 확인용이다.
			ServerPlayNetworking.send(player,
					PerkOfferPayload.manual(offer.milestone(), canChoose, describeOptions(offer)));
		}
		return canChoose ? OpenResult.OPENED : OpenResult.SPECTATING;
	}

	/**
	 * 대기 중인 후보를 화면에 그릴 수 있는 형태로 푼다.
	 *
	 * <p>풀에서 사라진 id 는 빠지므로 결과가 빈 목록일 수 있다. 그런 선택권으로는
	 * 절대 시간을 멈추지 않는다({@link PerkChoiceSession#begin}).
	 */
	static List<PerkOfferPayload.PerkOption> describeOptions(PendingOffer offer) {
		List<PerkOfferPayload.PerkOption> options = new ArrayList<>();
		for (String id : offer.optionIds()) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk == null) {
				SharedFateMod.LOGGER.warn("대기 중인 후보 '{}' 를 증강 풀에서 찾을 수 없어 제외합니다.", id);
				continue;
			}
			// 아이콘이 없는 증강은 빈 문자열로 보낸다. 화면이 등급별 기본 아이콘으로 메운다.
			// 세트 유형도 함께 보낸다 — 무유형이면 빈 문자열이 되어 카드에 줄이 안 그려진다.
			options.add(new PerkOfferPayload.PerkOption(
					perk.id(), perk.name(), perk.description(), perk.rarity().id(),
					perk.icon() == null ? "" : perk.icon().toString(),
					perk.setTypes().stream().map(PerkSetType::displayName)
							.collect(java.util.stream.Collectors.joining(
									PerkOfferPayload.PerkOption.SET_TYPE_JOINER)),
					// id 도 같은 차례로 함께 보낸다. 화면이 사람이 읽는 이름으로 툴팁을 찾으면
					// 세트 동기화가 늦은 순간에 툴팁이 통째로 사라진다.
					perk.setTypes().stream().map(PerkSetType::id)
							.collect(java.util.stream.Collectors.joining(
									PerkOfferPayload.PerkOption.SET_TYPE_JOINER))));
		}
		return options;
	}

	/**
	 * 클라이언트가 보낸 선택을 검증하고 반영한다.
	 *
	 * <p>신뢰할 수 없는 입력이므로 팀 소속, 활성 여부, 선택자 본인 여부, 구간 일치, 후보 포함 여부,
	 * 이미 보유했는지를 모두 서버에서 다시 확인한다. 지연·재전송된 패킷은 조용히 무시한다.
	 */
	public static void applyChoice(ServerPlayer player, int milestone, String perkId) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.pending.isEmpty()) {
			return;
		}

		PendingOffer offer = state.pending.getFirst();
		if (offer.milestone() != milestone || !offer.isChooser(player.getUUID())) {
			return;
		}
		if (!offer.optionIds().contains(perkId)) {
			return;
		}
		Perk perk = PerkRegistry.byId(perkId).orElse(null);
		if (perk == null) {
			return;
		}
		if (state.ownedPerks.contains(perkId)) {
			// 이미 가진 증강은 후보에 없어야 한다. 그런데 「없어야 한다」에 기대어 조용히
			// 돌아가면 선택 화면이 영영 안 닫힌다 — 세션이 시간을 멈춰 둔 채라 게임이 통째로
			// 멎은 것처럼 보인다. 실제로 그렇게 터졌다.
			//
			// 그래서 막고 끝내지 않고 **빠져나갈 길을 준다.** 후보를 다시 뽑아 내려보내면
			// 사람은 멀쩡한 카드에서 고를 수 있다. 다시 뽑기 횟수는 깎지 않는다 — 사람 잘못이
			// 아니다.
			SharedFateMod.LOGGER.warn(
					"이미 가진 증강이 후보에 있었습니다. 후보를 다시 뽑습니다: milestone={}, perk={}",
					milestone, perkId);
			replaceOfferWithFreshOptions(server, manager, team, state, offer, milestone);
			return;
		}

		RandomSource random = server.overworld().getRandom();
		commit(server, manager, team, state, perk,
				player.getGameProfile().name() + "님이 " + gradeAndName(perk) + " 을(를) 골랐습니다.",
				player.getUUID(), random);
		// 선택이 끝났으니 시간을 다시 흐르게 하고 팀 전원의 창을 닫는다.
		PerkChoiceSession.onChoiceApplied(server, team.teamId(), milestone,
				perk.id(), player.getGameProfile().name());
	}

	/**
	 * 클라이언트가 보낸 「다시 뽑기」 요청을 검증하고 후보를 갈아 끼운다.
	 *
	 * <p>클라이언트가 보내는 것은 「눌렀다」는 사실과 어느 창인지뿐이다. <b>남은 횟수를 세는
	 * 것도 새 후보를 뽑는 것도 전부 여기서 한다.</b> 그래서 창을 조작해도 무한히 다시 뽑을 수
	 * 없고, 등급을 올리거나 이미 가진 증강을 다시 받게 만들 수도 없다.
	 *
	 * <p><b>직전에 보여 준 3개는 되도록 다시 나오지 않는다.</b> 그 3개를 {@code avoid} 로
	 * 넘긴다 — 등급 통에 남은 후보가 3개 미만일 때만 그중에서 마저 채운다. 직전 한 번만
	 * 피하므로 두 번 넘게 다시 뽑으면 그보다 앞서 본 후보는 돌아올 수 있다.
	 *
	 * <p><b>세트 「도박 3단계 — 어차피 프리즘」을 켠 팀만 등급이 프리즘으로 올라간다.</b>
	 * 그때 아직 안 가진 프리즘이 3장 미만이면 카드도 그만큼만 뜬다.
	 *
	 * <p>다음 중 하나라도 어긋나면 <b>아무 말 없이 돌아간다.</b>
	 *
	 * <ul>
	 *   <li>팀·상태가 없거나 증강을 쓰지 않는 팀</li>
	 *   <li>지금 진행 중인 강제 선택 세션이 아님 (선택창이 떠 있는 단계가 아닌 경우 포함)</li>
	 *   <li>보낸 사람이 이 선택권의 선택자가 아님 — 관전자는 못 누른다</li>
	 *   <li>이번 회차에 남은 횟수가 0</li>
	 *   <li>같은 등급에서 새로 뽑을 후보가 하나도 없음 — 이때는 <b>횟수도 깎지 않는다</b></li>
	 * </ul>
	 */
	public static void applyReroll(ServerPlayer player, int milestone) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.pending.isEmpty()) {
			return;
		}
		if (!PerkChoiceSession.acceptsReroll(team.teamId(), milestone)) {
			return;
		}
		PendingOffer offer = state.pending.getFirst();
		if (offer.milestone() != milestone || !offer.isChooser(player.getUUID())) {
			return;
		}
		if (state.rerollsRemaining <= 0) {
			return;
		}
		// 다시 뽑아도 등급은 그대로다. 「골드 라운드에서 다시 뽑았더니 실버가 나왔다」가 되면
		// 안 되고, 반대로 프리즘이 나와도 안 된다. 후보에서 등급을 읽지 못하면 아무것도 하지
		// 않는다 — 등급을 모르는 채로 뽑으면 구간 규칙을 다시 굴리는 셈이 된다.
		//
		// 유일한 예외가 세트 「도박 3단계 — 어차피 프리즘」이다. 그 팀만 등급이 프리즘으로
		// 올라간다.
		boolean prismOnly = prismRerollActive(state);
		PerkRarity rarity = prismOnly ? PerkRarity.PRISM : offerRarity(offer);
		if (rarity == null) {
			return;
		}
		RandomSource random = server.overworld().getRandom();
		// 이미 가진 증강은 PerkDraft 가 등급을 가리지 않고 언제나 뺀다. 다시 뽑기도 같은 길을
		// 지나므로 재추첨 결과에 보유 증강이 섞일 수 없다. 구간을 함께 넘겨야 min_level 이
		// 걸린 증강(예: 30렙부터인 프리즘 「환골탈태」)이 이른 구간에 튀어나오지 않는다.
		//
		// 지금 화면에 떠 있는 3개를 avoid 로 넘긴다. 넘기지 않으면 실버 라운드 기준 세 번에 한
		// 번꼴로 방금 본 카드가 그대로 돌아와, 다시 뽑기를 쓰고도 안 쓴 것처럼 보인다.
		// 「되도록」이라 남은 후보가 3개 미만이면 뺐던 것에서 마저 채운다.
		// 직전 한 번만 피하므로 두 번 이상 다시 뽑으면 그전 것은 다시 나올 수 있다.
		List<String> options = PerkDraft.drawFor(rarity, milestone, PerkRegistry.all(),
				state.ownedPerks, offer.optionIds(),
				PerkSwapRules.satisfiedRequirements(state), random, OPTION_COUNT,
				silverOffersBlocked(state));
		if (prismOnly) {
			// PerkDraft.fallbackOrder(PRISM) 은 프리즘 → 골드 → 실버라, 아직 안 가진 프리즘이
			// 3장 미만이면 골드가 섞여 들어온다. 남은 프리즘이 두 장이면 카드도 두 장이다.
			options = onlyPrism(options);
		}
		if (options.isEmpty()) {
			// 뽑을 것이 없으면 창이 비어 버린다. 횟수를 깎지 않고 지금 후보를 그대로 둔다.
			SharedFateMod.LOGGER.warn(
					"{}렙 구간을 다시 뽑으려 했지만 {} 등급에 남은 후보가 없어 그대로 둡니다.",
					milestone, rarity.displayName());
			return;
		}

		state.pending.set(0, new PendingOffer(milestone, offer.chooser(), options));
		state.rerollsRemaining--;
		manager.setDirty();
		// 제한시간을 60초로 되돌리고 바뀐 후보를 팀 전원에게 다시 보낸다. 시간 정지와 무적은
		// 그대로다.
		PerkChoiceSession.onRerolled(server, team.teamId(), milestone);
		broadcastSync(server, team, state);
		broadcast(server, team, Component.literal(
				"[증강] " + player.getGameProfile().name() + "님이 후보를 다시 뽑았습니다. 남은 횟수 "
						+ state.rerollsRemaining + "회."));
	}

	/**
	 * 지금 떠 있는 선택권의 후보를 <b>다시 뽑아</b> 내려보낸다. 다시 뽑기 횟수는 깎지 않는다.
	 *
	 * <p>후보가 망가진 것을 알아차렸을 때의 <b>탈출구</b>다. 지금은 「이미 가진 증강이 후보에
	 * 있다」 한 곳에서 쓴다. 조용히 돌아가면 화면이 안 닫히고 시간도 멈춘 채로 남는다.
	 *
	 * <p>새 후보도 못 뽑으면 <b>제한시간에 맡긴다.</b> 거기서 무작위 선택이 대기열을 비우므로
	 * 적어도 영원히 갇히지는 않는다.
	 */
	private static void replaceOfferWithFreshOptions(MinecraftServer server, TeamManager manager,
			ShareTeam team, TeamState state, PendingOffer offer, int milestone) {
		PerkRarity rarity = offerRarity(offer);
		if (rarity == null) {
			rarity = PerkRarity.SILVER;
		}
		boolean blocked = silverOffersBlocked(state);
		if (blocked && rarity == PerkRarity.SILVER) {
			rarity = PerkRarity.GOLD;
		}
		List<String> options = PerkDraft.drawFor(rarity, milestone, PerkRegistry.all(),
				state.ownedPerks, offer.optionIds(), PerkSwapRules.satisfiedRequirements(state),
				server.overworld().getRandom(), OPTION_COUNT, blocked);
		if (options.isEmpty()) {
			SharedFateMod.LOGGER.error(
					"{}렙 구간의 후보를 다시 뽑지 못했습니다. 제한시간의 무작위 선택에 맡깁니다.",
					milestone);
			return;
		}
		state.pending.set(0, new PendingOffer(milestone, offer.chooser(), options));
		manager.setDirty();
		PerkChoiceSession.onRerolled(server, team.teamId(), milestone);
		broadcastSync(server, team, state);
		broadcast(server, team, Component.literal(
				"[증강] 후보가 잘못되어 다시 뽑았습니다. 다시 뽑기 횟수는 그대로입니다."));
	}

	/**
	 * 제한시간이 끝났을 때 후보 중 하나를 무작위로 골라 적용한다.
	 *
	 * <p>{@link PerkChoiceSession} 이 시간을 이미 녹인 뒤에 부른다. <b>고를 수 있는 후보가 하나도
	 * 없더라도 이 선택권은 반드시 대기열에서 사라진다.</b> 남겨 두면 다음 감지 주기에 같은
	 * 선택권으로 다시 얼어붙어 영원히 빠져나오지 못한다.
	 */
	static void applyRandomChoice(MinecraftServer server, UUID teamId, int milestone) {
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamById(teamId);
		TeamState state = manager.stateByTeamId(teamId);
		if (team == null || state == null || state.pending.isEmpty()) {
			return;
		}
		PendingOffer offer = state.pending.getFirst();
		if (offer.milestone() != milestone) {
			return;
		}

		Perk perk = pickRandomTakeable(server, state, offer);
		if (perk == null) {
			state.pending.removeFirst();
			manager.setDirty();
			broadcastSync(server, team, state);
			SharedFateMod.LOGGER.warn(
					"{}렙 구간 선택권에 고를 수 있는 후보가 없어 그대로 버립니다.", milestone);
			broadcast(server, team, Component.literal(
					"[증강] 시간이 다 되었지만 고를 수 있는 후보가 없어 이번 선택권은 사라집니다."));
			return;
		}
		RandomSource random = server.overworld().getRandom();
		// 자동 선택이어도 「고른 사람」은 있다. 선택자로 지정돼 있던 그 사람이다. 그가 창을 보고
		// 있었으니 fixed_to_owner 증강의 주인으로도 그 사람이 맞다. 선택자가 아직 정해지지
		// 않았던 선택권이라면 주인 없이 지나간다.
		commit(server, manager, team, state, perk,
				"시간이 다 되어 " + gradeAndName(perk) + " 이(가) 무작위로 선택되었습니다.",
				offer.chooser().orElse(null), random);
		// 자동 선택도 직접 고른 것과 똑같이 결과를 보여 준다. 고른 사람 이름은 비운다.
		PerkChoiceSession.onChoiceApplied(server, teamId, milestone, perk.id(), "");
	}

	/** 후보 중 지금 실제로 가져갈 수 있는 것 하나를 무작위로 고른다. 하나도 없으면 null. */
	private static @Nullable Perk pickRandomTakeable(MinecraftServer server, TeamState state,
			PendingOffer offer) {
		List<Perk> takeable = new ArrayList<>();
		for (String id : offer.optionIds()) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null && !state.ownedPerks.contains(id)) {
				takeable.add(perk);
			}
		}
		if (takeable.isEmpty()) {
			return null;
		}
		RandomSource random = server.overworld().getRandom();
		return takeable.get(random.nextInt(takeable.size()));
	}

	/**
	 * 선택을 실제로 반영한다. 직접 고른 경우와 자동 선택이 같은 길을 지나게 하는 자리다.
	 *
	 * <p><b>「고른 사람」이 여기까지 내려온다.</b> {@code holder} 의 {@code fixed_to_owner} 가
	 * 켜진 증강은 고른 사람이 회차 내내 보유자여야 하는데, 그 사실을 아는 곳은 선택을 받는
	 * 이 경로뿐이다. {@code TeamState.perkOwners} 에 증강 id 별로 적어 두면
	 * {@link PerkHolderManager} 가 그것을 읽어 보유자를 정한다. 다른 증강에는 아무 영향이 없다.
	 *
	 * @param chooser 이 증강을 고른 사람. 알 수 없으면 null
	 */
	private static void commit(MinecraftServer server, TeamManager manager, ShareTeam team,
			TeamState state, Perk perk, String announcement, @Nullable UUID chooser,
			RandomSource random) {
		if (!state.ownedPerks.contains(perk.id())) {
			state.ownedPerks.add(perk.id());
		}
		if (chooser != null) {
			state.perkOwners.put(perk.id(), chooser);
		}
		state.pending.removeFirst();
		manager.setDirty();

		// 즉시 지급은 여기서만 일어난다. refreshPlayer 는 접속·부활 때마다 다시 도는 길이라
		// 거기에 두면 접속할 때마다 아이템이 불어난다. 한 증강은 한 회차에 한 번만 고를 수 있으므로
		// 이 자리를 지나는 횟수도 증강마다 한 번뿐이다.
		//
		// item_grant·legacy_gear·gambler·rarity_grant·rarity_reroll 다섯 즉시 지급 효과는
		// PerkGrantChain 이 한 곳에서 처리한다. 무작위로 받은 증강이 또 즉시 지급 효과를
		// 가지고 있으면(예: 「숨은 재능」이 뽑은 골드가 하필 「하늘의 은총」인 경우) 그것도
		// 마저 처리해야 실제로 손에 들어온 증강이 전부 발동한다.
		PerkGrantChain.run(server, team, state, perk, random);
		// 대기열에 아직 안 보여 준 선택권이 남아 있을 수 있다. 방금 얻은 것 때문에 그 후보가
		// 틀려졌을 수 있으므로 여기서 다시 본다.
		revalidatePendingOffers(server, state, random);

		applyToTeam(server, team, state);
		// 몹에게 걸리는 증강은 폴링으로도 따라잡지만, 고른 즉시 반영되는 편이 자연스럽다.
		MobPerkModifiers.invalidateNow(server);
		broadcastSync(server, team, state);
		broadcast(server, team, Component.literal(
				"[증강] " + announcement + " 팀 전체에 적용됩니다."));
	}

	/**
	 * 아직 안 보여 준 선택권들의 후보를 지금 상태로 다시 검증한다.
	 *
	 * <h2>왜 필요한가 — 후보는 「미리」 뽑힌다</h2>
	 * <p>{@code advanceMilestones} 는 한 번에 도달한 구간들의 후보를 <b>그 자리에서 전부</b>
	 * 뽑는다. 그 뒤에 앞 구간을 고르면 <b>이미 뽑아 둔 뒤 구간의 후보는 옛 상태 그대로</b>다.
	 * 그래서 방금 얻은 증강이 다음 창에 또 뜨거나, 방금 켜진 실버 차단이 무시된다. 둘 다 실제로
	 * 터졌고, 앞쪽은 고르는 순간 화면이 안 닫히는 사고로 이어졌다.
	 *
	 * <p>{@code advanceMilestones} 가 같은 루프 안의 겹침은 이미 막는다. 여기서 막는 것은
	 * <b>고른 뒤에 달라지는 것</b>이다 — 즉시 지급으로 증강이 늘어나는 길(「숨은 재능」·
	 * 「도박꾼」·「환골탈태」)까지 포함해서, 그 연쇄가 끝난 뒤에 한 번 본다.
	 *
	 * <p><b>첫 번째(지금 떠 있는) 선택권은 건드리지 않는다.</b> 부르는 자리가
	 * {@code commit} 이라 그것은 이미 대기열에서 빠진 뒤다.
	 */
	private static void revalidatePendingOffers(MinecraftServer server, TeamState state,
			RandomSource random) {
		if (state.pending.isEmpty()) {
			return;
		}
		boolean blocked = silverOffersBlocked(state);
		for (int i = 0; i < state.pending.size(); i++) {
			PendingOffer offer = state.pending.get(i);
			if (!needsRedraw(offer, state, blocked)) {
				continue;
			}
			PerkRarity rarity = offerRarity(offer);
			if (rarity == null || (blocked && rarity == PerkRarity.SILVER)) {
				rarity = PerkRarity.GOLD;
			}
			List<String> options = PerkDraft.drawFor(rarity, offer.milestone(),
					PerkRegistry.all(), state.ownedPerks, List.of(),
					PerkSwapRules.satisfiedRequirements(state), random, OPTION_COUNT, blocked);
			if (options.isEmpty()) {
				// 뽑을 것이 없으면 옛 후보를 그대로 둔다. 비워 두면 그 구간이 빈 창으로 뜬다.
				SharedFateMod.LOGGER.warn(
						"{}렙 구간의 후보를 다시 뽑지 못해 그대로 둡니다.", offer.milestone());
				continue;
			}
			SharedFateMod.LOGGER.info(
					"[PERK] {}렙 구간의 후보가 낡아 다시 뽑았습니다.", offer.milestone());
			state.pending.set(i, new PendingOffer(offer.milestone(), offer.chooser(), options));
		}
	}

	/** 이 선택권의 후보가 지금 상태에서 틀렸는가 — 이미 가진 것이거나, 막힌 실버이거나. */
	private static boolean needsRedraw(PendingOffer offer, TeamState state, boolean silverBlocked) {
		for (String id : offer.optionIds()) {
			if (state.ownedPerks.contains(id)) {
				return true;
			}
			if (silverBlocked) {
				Perk perk = PerkRegistry.byId(id).orElse(null);
				if (perk != null && perk.rarity() == PerkRarity.SILVER) {
					return true;
				}
			}
		}
		return false;
	}

	private static String gradeAndName(Perk perk) {
		return perk.rarity().displayName() + " 등급 " + perk.name();
	}

	// ------------------------------------------------------------------ 효과 적용

	/**
	 * 한 플레이어에게 팀이 보유한 증강 효과를 전부 다시 맞춘다.
	 *
	 * <p><b>세트 효과도 여기서 함께 맞춘다.</b> 세트는 {@code ownedPerks} 를 다시 세어 만드는
	 * 파생 상태라 보유 목록이 바뀌는 모든 경로가 결국 이 자리를 지난다
	 * ({@code commit} → {@code applyToTeam} → 여기). 그래서 재평가 훅이 따로 필요 없다.
	 *
	 * <p>몇 번을 불러도 결과가 같아야 한다. 접속·부활·상태이상 재적용마다 불리는 자리다.
	 */
	public static void refreshPlayer(ServerPlayer player) {
		TeamState state = com.sharedfate.team.TeamLookup.stateOf(player.getUUID());
		if (state == null) {
			return;
		}
		// 세트 「방어 3단계」를 켠 팀에서는 방어 유형 증강의 대가를 붙이지 않는다. 대가가 하나도
		// 없는 팀은 세트를 보지도 않는다.
		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		// 세트 「무기 3단계」를 켠 팀에서는 공격력을 깎는 효과를 붙이지 않는다. 이쪽은 표시가
		// 아니라 효과가 실제로 하는 일을 보고 가리므로 앞으로 새로 넣는 증강도 함께 걸린다.
		NoAttackDamageLossEffect.Gate gate = NoAttackDamageLossEffect.gateFor(state);
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				try {
					if (waiver.waives(perk, effect) || gate.suppresses(effect)) {
						// 건너뛰기만 하면 안 된다. 세트가 켜지기 전에 이미 붙어 있던 수정자는
						// 아무도 걷어내지 않으므로 여기서 걷어낸다. remove 는 몇 번을 불러도
						// 결과가 같아서 이 자리는 그대로 멱등하다.
						effect.remove(player);
					} else {
						effect.apply(player);
						// conditional·holder·periodic 은 자기 하위를 스스로 붙인다. 부모를 통째로
						// 건너뛰면 같은 묶음의 공격력 증가까지 사라지므로, 붙인 직후에 감소만
						// 골라 걷어낸다. 세트가 꺼져 있으면 아무 일도 하지 않는다.
						gate.stripChildren(player, effect);
					}
				} catch (RuntimeException error) {
					SharedFateMod.LOGGER.warn("증강 '{}' 효과 적용에 실패했습니다.", perk.id(), error);
				}
			}
		}
		// 켜진 세트를 붙이고, 방금 꺼진 세트를 걷어낸다. 세트가 걷어내는 것은 세트가 붙인 것뿐이다.
		PerkSetEffects.refresh(player);
	}

	/**
	 * 팀의 증강 사용 여부를 바꾸고, 이미 붙어 있던 효과까지 정리한다.
	 *
	 * <p>피해 배율이나 교환 규칙처럼 <b>그때그때 계산에 끼어드는</b> 효과는 조회할 때마다
	 * {@code perksEnabled} 를 보므로 플래그만 내리면 곧바로 멈춘다. 반면 속성·상태이상처럼
	 * <b>플레이어에게 붙여 둔</b> 효과는 아무도 걷어내지 않으면 그대로 남는다. 그래서 끌 때는
	 * 여기서 직접 {@link PerkEffect#remove} 를 돌려 준다.
	 *
	 * <p>보유 목록({@code ownedPerks})은 건드리지 않는다.
	 *
	 * @param enabled 켤 것인가
	 */
	public static void setPerksEnabled(MinecraftServer server, ShareTeam team, TeamState state,
			boolean enabled) {
		state.perksEnabled = enabled;
		if (enabled) {
			applyToTeam(server, team, state);
			broadcastSync(server, team, state);
			return;
		}
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				stripEffects(online, state);
			}
		}
		broadcastSync(server, team, state);
		// 증강을 끄면 「짐꾼」이 열어 둔 칸도 함께 닫힌다. 그 사실도 알려야 창이 맞는다.
		com.sharedfate.net.TeamBroadcaster.broadcast(server, team);
	}

	/**
	 * 팀에서 빠지는 사람에게 붙어 있던 증강 자국을 <b>전부</b> 걷어낸다.
	 *
	 * <h2>왜 필요한가</h2>
	 *
	 * <p>팀을 해체하거나 탈퇴하면 팀 상태는 통째로 사라지지만, <b>플레이어에게 붙여 둔
	 * 것들은 아무도 걷어내지 않는다.</b> 속성 수정자와 상태이상은 플레이어 쪽에 적혀 있어
	 * 팀이 없어져도 그대로 남고, 새 팀을 만들어도 옛 증강의 효과를 그대로 달고 다니게 된다.
	 *
	 * <p>화면도 마찬가지다. 보유 증강 목록은 {@link #broadcastSync} 가 <b>팀원에게만</b>
	 * 보내므로, 팀이 사라진 순간 아무도 새 목록을 못 받고 <b>옛 목록이 화면에 영영 남는다.</b>
	 * 그래서 빈 목록을 직접 보내 준다.
	 *
	 * <p>{@code ownedPerks} 를 비우지는 않는다 — 부르는 쪽이 팀 상태를 통째로 버리는 길이라
	 * 여기서 손댈 것이 없고, 걷어낼 효과를 찾으려면 그 목록이 필요하다.
	 *
	 * @param state 빠져나가는 팀의 상태. 이미 사라졌으면 {@code null} 이어도 되고, 그때는
	 *              세트 효과와 화면만 정리한다
	 */
	public static void detach(@Nullable ServerPlayer player, @Nullable TeamState state) {
		if (player == null) {
			return;
		}
		stripEffects(player, state);
		ServerPlayNetworking.send(player, PerkSyncPayload.EMPTY);
	}

	/**
	 * 팀에 <b>막 들어온</b> 사람에게 그 팀의 증강을 걸어 주고 목록을 보낸다. {@link #detach} 의 짝.
	 *
	 * <h2>왜 필요한가</h2>
	 *
	 * <p>증강은 팀이 고르는 순간 {@code applyToTeam} 이 <b>그때 접속해 있던 팀원</b>에게 붙인다.
	 * 나중에 들어온 사람은 그 자리를 지나지 않았으므로 아무것도 안 붙어 있다. 접속을 끊었다
	 * 다시 들어오면 {@link #onPlayerJoin} 이 붙여 주지만, <b>초대받아 들어온 그 순간부터</b>
	 * 다시 접속할 때까지는 목록에는 증강이 보이는데 몸에는 아무 효과도 없는 상태가 된다.
	 *
	 * <p>{@code detach} 와 짝을 맞춰 여기 한 곳에서 붙인다.
	 */
	public static void attach(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null) {
			return;
		}
		if (state.perksEnabled) {
			refreshPlayer(player);
		}
		// 증강을 끈 팀에도 목록은 보낸다. 「무엇을 가졌는가」와 「지금 켜져 있는가」는 다른 값이다.
		broadcastSync(server, team, state);
	}

	/** 한 사람에게 붙어 있는 증강 효과와 세트 효과를 걷어낸다. */
	private static void stripEffects(ServerPlayer player, @Nullable TeamState state) {
		if (state != null) {
			for (String perkId : state.ownedPerks) {
				Perk perk = PerkRegistry.byId(perkId).orElse(null);
				if (perk == null) {
					continue;
				}
				for (PerkEffect effect : perk.effects()) {
					try {
						effect.remove(player);
					} catch (RuntimeException error) {
						SharedFateMod.LOGGER.warn("증강 '{}' 효과 해제에 실패했습니다.", perk.id(), error);
					}
				}
			}
		}
		// 증강이 꺼지면 세트도 함께 꺼진다.
		PerkSetEffects.removeAll(player);
	}

	private static void applyToTeam(MinecraftServer server, ShareTeam team, TeamState state) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				refreshPlayer(online);
			}
		}
		// 「짐꾼」이 고른 즉시 안 열리던 이유가 여기였다. 열린 칸 수는 TeamSyncPayload 를 타고
		// 클라이언트로 가는데, 보유 목록이 바뀌었을 때 그 묶음을 보내는 사람이 아무도 없었다.
		// 그래서 레벨이 우연히 달라져 정기 동기화가 도는 다음 순간까지, 심하면 재접속할
		// 때까지 창이 옛 크기 그대로였다.
		//
		// 「짐꾼」을 잃었는데 그 줄에 물건이 남아 있으면 칸이 안 닫힌다. 여기서 한 번 빼내
		// 대기열로 보낸다 — 물건은 잃지 않고, 창은 그 자리에서 줄어든다. 반드시 아래 broadcast
		// 보다 먼저여야 클라이언트가 줄어든 값을 받는다.
		PerkInventorySlots.evacuateLockedSlots(state);
		// 보유 목록이 바뀌는 길은 결국 전부 이 자리를 지난다(고르기·요행·환골탈태·켜고 끄기).
		// 그래서 부르는 곳마다 따로 걸지 않고 여기 한 곳에서 보낸다. 이 안에서 서버 쪽 메뉴의
		// 칸 자리도 함께 맞춰진다(TeamBroadcaster.refreshExpandedLayout).
		com.sharedfate.net.TeamBroadcaster.broadcast(server, team);
	}

	/** 팀이 보유한 증강의 주는 피해 배율을 모두 곱한 값. */
	public static double damageDealtMultiplier(ServerPlayer player) {
		return multiplier(player, true);
	}

	/** 팀이 보유한 증강의 받는 피해 배율을 모두 곱한 값. */
	public static double damageTakenMultiplier(ServerPlayer player) {
		return multiplier(player, false);
	}

	private static double multiplier(ServerPlayer player, boolean dealt) {
		TeamState state = com.sharedfate.team.TeamLookup.stateOf(player.getUUID());
		if (state == null || state.ownedPerks.isEmpty()) {
			return 1.0;
		}
		// 조건부 증강은 배율 조회에 플레이어 인자가 없어 대상을 따로 알려 줘야 한다.
		ConditionalPerkManager.beginMultiplierLookup(player);
		// 「불굴」의 체력이 가득 찼을 때 받는 피해 ×1.1 이 이 길로 들어온다. 같은 증강의 이득
		// (체력 절반 이하일 때 ×0.7)도 이 길이지만 대가 표시가 없어 건너뛰지 않는다.
		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		// 「급소만 노려」의 주는 피해 ×0.95 처럼 배율로 적힌 공격력 감소가 이 길로 들어온다.
		// 무기 3단계를 켠 팀에서는 그 한 줄만 곱하지 않는다. 받는 피해 쪽은 공격력과 무관하므로
		// 주는 피해를 모을 때만 본다.
		NoAttackDamageLossEffect.Gate gate = NoAttackDamageLossEffect.gateFor(state);
		double total = 1.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (waiver.waives(perk, effect)) {
					continue;
				}
				if (dealt && gate.suppresses(effect)) {
					continue;
				}
				total *= dealt
						? effect.damageDealtMultiplier()
						: effect.damageTakenMultiplier();
			}
		}
		// 세트가 건 배율도 같은 곱에 들어간다. 세트가 없으면 빈 목록이라 값이 그대로다.
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			total *= dealt
					? effect.damageDealtMultiplier()
					: effect.damageTakenMultiplier();
		}
		return Double.isFinite(total) && total > 0.0 ? total : 1.0;
	}

	// ------------------------------------------------------------------ 조회와 알림

	public static List<PerkSyncPayload.Owned> ownedLines(ServerPlayer player) {
		TeamState state = com.sharedfate.team.TeamLookup.stateOf(player.getUUID());
		if (state == null || state.ownedPerks.isEmpty()) {
			return List.of();
		}
		List<PerkSyncPayload.Owned> lines = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			// 정의가 사라진 증강도 보유 목록에는 남아 있다. 식별자라도 보여 준다.
			lines.add(perk == null
					? new PerkSyncPayload.Owned(perkId, "정의를 찾을 수 없는 증강입니다.", "silver", "")
					: new PerkSyncPayload.Owned(perk.name(), perk.description(),
							perk.rarity().name().toLowerCase(java.util.Locale.ROOT),
							// 카드에 적는 것과 같은 방식으로 잇는다. 무유형이면 빈 문자열이
							// 되어 목록에 유형 딱지가 안 붙는다.
							perk.setTypes().stream().map(PerkSetType::displayName)
									.collect(java.util.stream.Collectors.joining(
											PerkOfferPayload.PerkOption.SET_TYPE_JOINER))));
		}
		return lines;
	}

	/**
	 * 「유적 감별사」가 찾아 둔 좌표 줄들. 그 증강이 없으면 빈 목록.
	 *
	 * <p><b>증강 하나가 아니라 팀 하나에 딸린 값이다.</b> 유적 좌표 효과는 팀 전체에 하나뿐이라
	 * ({@link PerkRuinSurvey#effectOf}) 보유 증강 줄마다 실어 보내면 같은 목록이 되풀이된다.
	 * 그래서 {@code PerkSyncPayload} 의 별도 칸으로 나가고, 화면은 목록 오른쪽에 따로 세운다.
	 *
	 * <p><b>예전에는 설명 문자열 뒤에 괄호로 붙여 보냈다.</b> 통신 형식을 안 늘리려는 임시
	 * 방편이었고, 그래서는 사람이 요청한 「목록 오른쪽에 두 줄」이 될 수 없었다 — 오른쪽에 따로
	 * 세우려면 클라이언트가 좌표를 설명과 <b>구분해서</b> 받아야 한다. 규약 35 에서 칸을 늘렸으니
	 * 덧붙임은 지웠다. 두 곳에 같은 좌표가 뜨면 안 된다.
	 *
	 * <p>여기서는 <b>찾지 않는다.</b> {@link PerkRuinSurvey#lines} 는 이미 찾아 둔 것만 돌려주고,
	 * 찾는 일은 증강을 고를 때와 접속할 때만 일어난다. 동기화는 자주 도는 길이다.
	 */
	public static List<String> ruinCoords(ServerPlayer player) {
		TeamState state = com.sharedfate.team.TeamLookup.stateOf(player.getUUID());
		MinecraftServer server = player.level().getServer();
		if (state == null || server == null) {
			return List.of();
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		return PerkRuinSurvey.lines(team, state);
	}

	/** 선택권이 다른 사람에게 넘어갔을 때만 쓰는 알림. 최초 발동 알림은 세션 쪽이 맡는다. */
	private static void announceOffer(MinecraftServer server, ShareTeam team, PendingOffer offer,
			@Nullable UUID chooser) {
		if (chooser == null) {
			return;
		}
		ServerPlayer picked = server.getPlayerList().getPlayer(chooser);
		String name = picked == null ? "팀원" : picked.getGameProfile().name();
		broadcast(server, team, Component.literal(
				"[증강] " + offer.milestone() + "렙 " + offerGradeLabel(offer) + " 선택권이 "
						+ name + "님에게 넘어갔습니다."));
	}

	/** 이 선택권의 등급 표시 문자열. 등급을 알 수 없으면 그냥 "증강". */
	static String offerGradeLabel(PendingOffer offer) {
		PerkRarity rarity = offerRarity(offer);
		return rarity == null ? "증강" : rarity.displayName() + " 등급 증강";
	}

	/**
	 * 이 선택권의 등급.
	 *
	 * <p>한 라운드의 후보는 전부 같은 등급이므로 찾을 수 있는 첫 후보만 보면 된다.
	 * 풀에서 사라진 id뿐이면 null 이다.
	 */
	private static @Nullable PerkRarity offerRarity(PendingOffer offer) {
		for (String id : offer.optionIds()) {
			Perk perk = PerkRegistry.byId(id).orElse(null);
			if (perk != null) {
				return perk.rarity();
			}
		}
		return null;
	}

	private static void remindIfChooser(ServerPlayer player, TeamState state) {
		for (PendingOffer offer : state.pending) {
			if (offer.isChooser(player.getUUID())) {
				player.sendSystemMessage(Component.literal(
						"[증강] 고르지 않은 선택권이 있습니다. /shareteam perk 로 확인하십시오."));
				return;
			}
		}
	}

	private static void broadcast(MinecraftServer server, ShareTeam team, Component message) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				online.sendSystemMessage(message);
			}
		}
	}

	private static void broadcastSync(MinecraftServer server, ShareTeam team, TeamState state) {
		String chooserName = "";
		if (!state.pending.isEmpty()) {
			Optional<UUID> chooser = state.pending.getFirst().chooser();
			if (chooser.isPresent()) {
				ServerPlayer picked = server.getPlayerList().getPlayer(chooser.get());
				if (picked != null) {
					chooserName = picked.getGameProfile().name();
				}
			}
		}
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online == null) {
				continue;
			}
			ServerPlayNetworking.send(online,
					new PerkSyncPayload(ownedLines(online), state.pending.size(), chooserName,
							ruinCoords(online)));
		}
	}
}
