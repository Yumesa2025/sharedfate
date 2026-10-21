package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.GamblerEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code gambler} 증강(프리즘 「도박꾼」)의 즉시 지급을 맡는다.
 *
 * <p><b>{@link #grantOnChoice}를 부르는 곳은 {@link PerkGrantChain} 하나뿐이다.</b>
 * {@link PerkItemGrants}·{@link PerkLegacyGear}와 같은 자리, 같은 시점이다. 증강은 한 회차에
 * 한 번만 고를 수 있으므로 이 자리를 지나는 것도 한 번뿐이다.
 *
 * <h2>등급 상관없이 2개, 대가 없음</h2>
 * <p>{@link PerkDraft}의 등급별 추첨과 달리 이건 <b>전체 풀(프리즘 포함)에서 등급을 가리지
 * 않고</b> 뽑는다. 이미 가진
 * 증강(도박꾼 자기 자신 포함 — {@code commit}이 {@code ownedPerks}에 도박꾼을 넣은 <b>뒤에</b>
 * 이 메서드를 부르므로 이미 걸러진다)과 이번에 같이 뽑힌 것끼리도 중복되지 않는다.
 */
public final class PerkGambler {
	/** 한 번에 더 얻는 증강 수. */
	public static final int GRANT_COUNT = 2;

	private PerkGambler() {
	}

	/**
	 * 증강 하나가 가진 {@code gambler} 효과를 실행한다. 등급 상관없이 무작위 {@link #GRANT_COUNT}개를
	 * 더 준다.
	 *
	 * @return 실제로 더 준 증강 수. 줄 것이 없었으면(도박꾼이 아니거나 후보 풀이 비었으면) 0
	 */
	public static int grantOnChoice(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable Perk perk, @Nullable RandomSource random) {
		return grantOnChoiceDetailed(server, team, state, perk, random).size();
	}

	/**
	 * {@link #grantOnChoice}와 같은 일을 하지만, 실제로 더 준 증강 목록을 그대로 돌려준다.
	 *
	 * <p>{@link PerkGrantChain}이 이 목록을 받아 그중에도 다른 즉시 지급 효과(예: 무작위로
	 * 뽑힌 증강이 하필 「하늘의 은총」인 경우)가 있으면 마저 처리한다.
	 */
	static List<Perk> grantOnChoiceDetailed(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable Perk perk, @Nullable RandomSource random) {
		if (state == null || perk == null || random == null || !hasGambler(perk)) {
			return List.of();
		}

		List<Perk> pool = eligiblePool(state);
		List<Perk> granted = new ArrayList<>(GRANT_COUNT);
		for (int i = 0; i < GRANT_COUNT && !pool.isEmpty(); i++) {
			Perk picked = pool.remove(random.nextInt(pool.size()));
			granted.add(picked);
			state.ownedPerks.add(picked.id());
		}
		if (granted.isEmpty()) {
			return List.of();
		}

		SharedFateMod.LOGGER.info("[PERK] 증강 {} 로 무작위 증강 {}개를 더 얻었습니다: {}",
				perk.id(), granted.size(), granted.stream().map(Perk::id).toList());

		if (server != null && team != null) {
			String names = granted.stream()
					.map(g -> g.rarity().displayName() + " 등급 " + g.name())
					.reduce((a, b) -> a + ", " + b).orElse("");
			Component message = Component.literal("[증강] 「" + perk.name() + "」으로 " + names + " 을(를) 더 얻었습니다.");
			for (UUID member : team.members()) {
				ServerPlayer online = server.getPlayerList().getPlayer(member);
				if (online != null) {
					online.sendSystemMessage(message);
				}
			}
		}
		return List.copyOf(granted);
	}

	private static boolean hasGambler(Perk perk) {
		for (PerkEffect effect : perk.effects()) {
			if (effect instanceof GamblerEffect) {
				return true;
			}
		}
		return false;
	}

	/** 아직 안 가진 증강 전부(등급 상관없이, 중복 id 제거). */
	private static List<Perk> eligiblePool(TeamState state) {
		Set<String> seen = new HashSet<>();
		List<Perk> pool = new ArrayList<>();
		for (Perk candidate : PerkRegistry.all()) {
			if (candidate == null || candidate.id() == null) {
				continue;
			}
			if (!seen.add(candidate.id())) {
				continue;
			}
			if (state.ownedPerks.contains(candidate.id())) {
				continue;
			}
			// 「드래곤전 대비」는 덤으로 주지 않는다. 45·50 구간에서 고르는 것만이 이 풀에 닿는 길이다 —
			// 여기서 섞이면 시련을 끈 팀이 이 풀을 받거나 40 이하에서 미리 받는다.
			if (candidate.rarity() == PerkRarity.TRIAL) {
				continue;
			}
			// 팀 설정을 못 채우는 증강은 덤으로도 주지 않는다. 후보 추첨(PerkDraft)만 막으면
			// 위치 교환을 끈 팀이 이 길로 교환 증강을 받아 죽은 카드를 쥐게 된다.
			if (!candidate.requirementMet(PerkSwapRules.satisfiedRequirements(state))) {
				continue;
			}
			pool.add(candidate);
		}
		return pool;
	}
}
