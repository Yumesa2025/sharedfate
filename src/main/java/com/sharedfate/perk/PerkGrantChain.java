package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.ExtraRerollsEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 증강을 고른 순간의 즉시 지급들이 서로를 부르는 연쇄를 처리한다.
 *
 * <p>「숨은 재능」({@code rarity_grant}, 실버→골드 1개)·「하늘의 은총」({@code rarity_grant},
 * 골드→프리즘 1개)·「도박꾼」({@code gambler}, 등급 무관 2개)·「환골탈태」({@code rarity_reroll},
 * 보유분 재편)는 전부 "이 증강을 고르면 다른 증강을 더(또는 다시) 준다"는 사건을 낸다. 그
 * 사건으로 받은 증강이 <b>또</b> 같은 종류의 사건을 내면(예: 실버 「숨은 재능」이 골드를
 * 하나 주는데 하필 그게 「하늘의 은총」이라 프리즘을 하나 더 주고, 그 프리즘이 하필
 * 「도박꾼」이라 무작위 2개를 더 주는 경우) 그 사건도 마저 처리해야 손에 들어온 증강이 전부
 * 실제로 발동한다. {@link PerkManager#applyChoice}가 부르는 {@code commit}이 원래 고른 증강
 * 하나로 이 처리를 시작한다.
 *
 * <h2>무한 재귀를 막는 두 겹</h2>
 * <ul>
 *   <li><b>방문 표시</b> — 이번 연쇄에서 이미 지급 처리를 한 증강 id 는 {@code visited}에
 *       남고, 같은 id 는 두 번째로 뽑혀도 다시 줄을 세우지 않는다. {@link PerkGambler}·
 *       {@link PerkRarityGrant}는 이미 가진 증강을 후보에서 빼므로 사실 이것만으로도 같은
 *       id 가 두 번 들어올 일은 없다. 유일한 예외가 {@link PerkRarityReroll}이다 — 지우고
 *       다시 뽑으므로 방금까지 가졌던 증강도 다시 나올 수 있는데, 그 경우에도 이 표시가
 *       다시 큐에 넣는 것을 막는다(이미 한 번 지급 처리를 마쳤으므로 다시 할 필요가 없다).</li>
 *   <li><b>단계 상한</b> — 방문 표시만으로도 유한한 풀에서는 반드시 끝나지만, 정의 파일이
 *       잘못돼 그 전제가 깨지는 경우에 대비해 {@value #MAX_STEPS} 단계에서 강제로 멈춘다.
 *       지금 증강 풀(수십 개)보다 넉넉히 크게 잡아, 정상적인 연쇄는 이 상한에 절대 닿지
 *       않는다.</li>
 * </ul>
 *
 * <h2>「환골탈태」가 연쇄 도중에 나오면</h2>
 * <p>{@code rarity_reroll}은 지금 가진 증강 <b>전부</b>를 지우고 다시 채운다. 연쇄 도중에
 * 나와도 다르지 않다 — 그 순간까지 이 연쇄로 받은 것도 이미 {@code ownedPerks}에 들어가 있는
 * "지금 가진 것"이므로 함께 지워지는 것이 맞다. 새로 채운 결과는 이 연쇄에 다시 들어가
 * 마저 처리된다(그중에 또 즉시 지급 효과가 있을 수 있으므로).
 *
 * <h2>화면 동기화는 여기서 하지 않는다</h2>
 * <p>연쇄 도중에는 채팅 알림만 나가고, {@code PerkSyncPayload} 방송은 {@code commit}이 이
 * 클래스를 부르고 돌아온 뒤 <b>한 번만</b> 한다.
 */
final class PerkGrantChain {
	/** 안전판. 방문 표시가 있으면 실제로는 이 값에 한참 못 미쳐 끝난다. */
	static final int MAX_STEPS = 64;

	private PerkGrantChain() {
	}

	/**
	 * 증강 하나를 고른 사건에서 시작해, 그로 인한 즉시 지급이 또 다른 지급을 부르는 연쇄를
	 * 끝까지 처리한다.
	 *
	 * <p>{@code chosen}은 부르는 쪽이 이미 {@code state.ownedPerks}에 넣어 둔 뒤 넘겨야 한다.
	 * {@code item_grant}·{@code legacy_gear}·{@code diamond_sundial}·{@code gambler}·
	 * {@code rarity_grant}·{@code rarity_reroll} 여섯 가지 즉시 지급 효과를 연쇄로 처리한다.
	 */
	static void run(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable Perk chosen, @Nullable RandomSource random) {
		if (state == null || chosen == null || chosen.id() == null) {
			return;
		}

		Set<String> visited = new HashSet<>();
		Deque<Perk> queue = new ArrayDeque<>();
		visited.add(chosen.id());
		queue.add(chosen);

		int steps = 0;
		while (!queue.isEmpty()) {
			if (++steps > MAX_STEPS) {
				SharedFateMod.LOGGER.warn(
						"증강 지급 연쇄가 {}단계를 넘어 강제로 멈췄습니다. 시작: {}", MAX_STEPS, chosen.id());
				break;
			}
			Perk current = queue.poll();

			// 즉시 지급은 정확히 이 여덟 곳에서만 일어난다. item_grant · legacy_gear ·
			// diamond_sundial · rally_shard · flight_charm · ruin_survey 는 서로를 부르지
			// 않는(더 받게 하지 않는) 단순 지급·몰수·조사라 큐에 넣을 것이 없다.
			PerkItemGrants.grantOnChoice(server, team, state, current);
			PerkLegacyGear.sacrificeOnChoice(server, team, state, current);
			// 「엑스레이」와 소집의 조각은 커스텀 컴포넌트를 붙여야 해서 item_grant 로 줄 수 없다.
			// 대신 지급 시점은 여기, item_grant 와 정확히 같은 자리다.
			PerkDiamondSundial.grantOnChoice(server, team, state, current);
			PerkRallyShard.grantOnChoice(server, team, state, current);
			PerkFlightCharm.grantOnChoice(server, team, state, current);
			// 「유적 감별사」는 물건을 주지 않는다. 대신 이 자리에서 구조물을 한 번 찾아 두고
			// 채팅 한 줄을 띄운다. 탐색이 비싸서 고르는 순간 딱 한 번만 돈다 —
			// PerkRuinSurvey 의 「언제 찾는가」를 보라. 줄 것이 없으므로 큐에 넣지 않는다.
			PerkRuinSurvey.surveyOnChoice(server, team, state, current);

			for (Perk granted : PerkGambler.grantOnChoiceDetailed(server, team, state, current, random)) {
				enqueue(queue, visited, granted);
			}
			for (Perk granted
					: PerkRarityGrant.grantOnChoiceDetailed(server, team, state, current, random)) {
				enqueue(queue, visited, granted);
			}
			for (Perk granted
					: PerkRarityReroll.rerollOnChoiceDetailed(server, team, state, current, random)) {
				enqueue(queue, visited, granted);
			}
		}

		// 연쇄가 끝난 뒤에 딱 한 번 본다. 도중에 보면 「환골탈태」가 보유 목록을 갈아엎기
		// 전의 세트 상태를 보게 된다.
		syncSetRerolls(server, team, state);
	}

	/**
	 * 세트 「도박」의 다시 뽑기 몫을 지금 값에 맞춘다.
	 *
	 * <p><b>2단계만이 아니다.</b> 「한 판 더」(2단계)가 5, 「어차피 프리즘」(3단계)이 3 을 얹고
	 * 단계는 누적이라 도박 증강 셋이면 몫이 8 이다. 3단계가 리롤을 함께 주기 시작한 것은
	 * 0.24.0-dev 부터다.
	 *
	 * <p><b>{@code PerkManager.refreshPlayer} 에 두면 안 된다.</b> 그쪽은 접속·부활·상태이상
	 * 재적용마다 다시 도는 길이라 접속할 때마다 몫이 통째로 불어난다.
	 *
	 * <h2>두 번 불려도 안전하다</h2>
	 * <p>실제로 더하는 일은 {@link TeamState#syncRerollSetBonus} 가 하는데, 그것은 「더한다」가
	 * 아니라 「세트로 얻은 몫을 지금 값에 맞춘다」다. 이미 받아 둔 팀은 다시 불러도 아무 일이
	 * 없고, 연쇄 도중 「환골탈태」로 도박 증강을 잃은 팀은 그 자리에서 몫이 0 으로 내려간다.
	 */
	private static void syncSetRerolls(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			TeamState state) {
		int before = state.rerollsRemaining;
		if (!state.syncRerollSetBonus(ExtraRerollsEffect.bonusOf(state))) {
			return;
		}
		int gained = state.rerollsRemaining - before;
		if (gained <= 0) {
			// 세트가 풀려 몫이 사라진 경우다. 알릴 만한 일이 아니다.
			//
			// 0.29.1-dev 전에는 「허용치가 이미 상한이라 얹을 자리가 없다」는 길이 또 있었고,
			// 그쪽이 조용한 것이 사고였다 — 굴림을 최대로 잡은 팀은 도박 세트가 통째로 죽는데
			// 아무 말도 없었다. 이제 세트 몫은 허용치 위에 얹히므로 그 길 자체가 없다.
			return;
		}
		SharedFateMod.LOGGER.info("[PERK] 세트 보상으로 다시 뽑기 {}회를 더 얻었습니다 (이번 회차 {}회 남음)",
				gained, state.rerollsRemaining);
		if (server == null || team == null) {
			return;
		}
		Component message = Component.literal("[증강] 세트 보상으로 다시 뽑기 " + gained
				+ "회를 더 얻었습니다. 이번 회차에 " + state.rerollsRemaining + "회 남았습니다.");
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				online.sendSystemMessage(message);
			}
		}
	}

	private static void enqueue(Deque<Perk> queue, Set<String> visited, @Nullable Perk perk) {
		if (perk == null || perk.id() == null || !visited.add(perk.id())) {
			return;
		}
		queue.add(perk);
	}
}
