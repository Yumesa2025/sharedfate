package com.sharedfate.perk;

import com.sharedfate.perk.effect.GatherEffect;
import com.sharedfate.perk.effect.OnSwapEffect;
import com.sharedfate.perk.effect.ProximityEffect;
import com.sharedfate.perk.effect.SwapBlockEffect;
import com.sharedfate.perk.effect.StaggeredSwapEffect;
import com.sharedfate.perk.effect.SwapExplosionEffect;
import com.sharedfate.perk.effect.SwapExemptEffect;
import com.sharedfate.perk.effect.SwapIntervalEffect;
import com.sharedfate.perk.effect.SwapRallyEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 팀원 위치 교환과 집합에 끼어드는 증강들의 판정부.
 *
 * <p>{@link com.sharedfate.sync.PositionSwapManager} 와
 * {@link com.sharedfate.sync.TeamGathering} 이 처리 한가운데서 여기에 물어보고, 답에 따라
 * 순간이동을 건너뛰거나 남은 틱을 고쳐 쓴다.
 *
 * <p>여기서 답하는 물음은 다섯이다.
 *
 * <ul>
 *   <li>{@link #blocksSwap} — 자리를 바꾸지 않는가 ({@code swap_block})</li>
 *   <li>{@link #nextRemainingTicks} — 다음 교환까지 몇 틱을 남길 것인가 ({@code swap_interval})</li>
 *   <li>{@link #grantOnSwap} — 교환 시점에 무엇을 얹을 것인가 ({@code on_swap})</li>
 *   <li>{@link #gathers} — 멀어지면 모으는 규칙이 있는가 ({@code gather})</li>
 *   <li>{@link #proximities} — 붙어 있으면 무엇을 얹을 것인가 ({@code proximity})</li>
 * </ul>
 *
 * <p>보유 증강이 하나도 없으면 어느 물음도 팀 상태 두 번만 보고 곧바로 "해당 없음"이다.
 * 증강을 쓰지 않는 팀의 교환 경로에는 사실상 아무 부담도 얹히지 않는다.
 *
 * <h2>사람을 가리는 두 물음</h2>
 * <p>위의 물음들이 「팀이 무엇을 갖고 있는가」라면, {@link #swapParticipants} 와
 * {@link #grantSwapExemptBonus} 는 「그 팀의 <b>누구</b>인가」를 답한다. 골드 「열외」
 * ({@code swap_exempt})는 고른 사람 하나만 교환에서 빼고 그 사람에게만 보상을 준다.
 *
 * <p>{@link #satisfiedRequirements} 는 결이 또 다르다. 교환이 일어나는 순간이 아니라 <b>증강을
 * 뽑는 순간</b>에 쓰이는 값으로, 위치 교환을 끈 팀에게 교환 증강을 보여 주지 않기 위한
 * 것이다({@link PerkDraft}).
 *
 * <h2>세 물음은 서로를 막지 않는다</h2>
 * <p>{@code swap_block} 이 참이어도 {@link #nextRemainingTicks} 와 {@link #grantOnSwap} 은
 * 그대로 쓴다. 막히는 것은 자리를 바꾸는 한 자리뿐이고, 주기와 시점은 계속 흐른다.
 * 그래서 「뿌리내린 발」을 가진 팀도 다른 증강의 교환 시점 효과는 그대로 받는다.
 */
public final class PerkSwapRules {
	/**
	 * 배율을 먹인 뒤에도 남겨 두는 최소 주기. 1초다.
	 *
	 * <p>배율을 여러 개 곱하면 주기가 0 에 가까워질 수 있다. 그러면 매 틱 순간이동이 일어나
	 * 아무도 움직일 수 없고 서버도 버티지 못한다.
	 */
	public static final int MIN_REMAINING_TICKS = 20;

	private PerkSwapRules() {
	}

	// ------------------------------------------------------------------ 순간이동 차단

	/** 이 팀이 지금 자리를 바꾸지 않는가. */
	public static boolean blocksSwap(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return false;
		}
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof SwapBlockEffect) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 이 팀이 <b>교환 시점 자체를</b> 없앴는가({@code swap_block} 의 {@code silent}).
	 *
	 * <p>참이면 {@code PositionSwapManager} 가 주기를 세는 것부터 건너뛴다 — 예고도 없고,
	 * {@link #grantOnSwap} 도 돌지 않고, 남은 시간도 줄지 않는다. 「소집의 조각」이 자동 교환을
	 * 없애고 대신 아이템으로 부르는 방식이기 때문이다. 자세한 이유는 {@link SwapBlockEffect}
	 * 문서에 있다.
	 *
	 * <p>{@link #blocksSwap} 이 참인 팀 중 일부만 이것도 참이다. 둘 다 가진 팀(「뿌리내린 발」 +
	 * 「소집의 조각」)은 조용한 쪽이 이긴다 — 교환 시점이 오지 않으므로 다른 증강의 교환 시점
	 * 효과도 함께 멈춘다.
	 */
	public static boolean silentSwapBlock(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return false;
		}
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof SwapBlockEffect block && block.silent()) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 주기 배율

	/**
	 * 이 팀이 가진 {@code swap_interval} 배율을 모두 곱한 값. 해당 없으면 1.0.
	 *
	 * <p>여러 개를 가졌으면 전부 곱한다.
	 */
	public static double intervalMultiplier(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return 1.0;
		}
		double total = 1.0;
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof SwapIntervalEffect interval && !interval.hasFixedMinutes()) {
				total *= interval.multiplier();
			}
		}
		return Double.isFinite(total) && total > 0.0 ? total : 1.0;
	}

	/**
	 * 주기를 못박는 증강이 있으면 그 값(틱). 없으면 0.
	 *
	 * <p>여러 개면 <b>가장 짧은 것</b>이 이긴다.
	 */
	public static int fixedIntervalTicks(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return 0;
		}
		int shortest = 0;
		for (PerkEffect effect : effectsOf(state)) {
			if (!(effect instanceof SwapIntervalEffect interval) || !interval.hasFixedMinutes()) {
				continue;
			}
			int ticks = interval.fixedMinutes() * TeamState.PositionSwapLimits.TICKS_PER_MINUTE;
			if (shortest == 0 || ticks < shortest) {
				shortest = ticks;
			}
		}
		return shortest;
	}

	/**
	 * 교환이 끝난 직후 다음 교환까지 남길 틱.
	 *
	 * <p>{@code TeamState.advancePositionSwapTick} 이 이미 주기 그대로를 채워 넣은 뒤에 부른다.
	 * 배율이 없으면 채워 넣은 값과 같으므로 덮어써도 아무 일도 일어나지 않는다.
	 */
	public static int nextRemainingTicks(@Nullable TeamState state) {
		int interval = state == null ? 0 : state.positionSwapIntervalTicks;
		if (interval <= 0) {
			return 0;
		}
		// 못박는 증강이 있으면 팀이 정한 주기와 배율을 모두 제친다.
		//
		// 다만 팀이 정한 주기보다 길게는 못 간다. TeamState.sanitize 가 저장을 읽을 때 남은
		// 틱을 [0, 주기] 로 자르기 때문에, 더 긴 값을 남겨 두면 서버를 껐다 켜는 순간 조용히
		// 줄어들어 재시작 전후로 팀이 다르게 움직인다. 어차피 이 증강들은 주기를 "짧게"
		// 만드는 쪽이라 실제로 걸리는 일은 거의 없다.
		int fixed = fixedIntervalTicks(state);
		if (fixed > 0) {
			return Math.max(MIN_REMAINING_TICKS, Math.min(interval, fixed));
		}
		return scaleInterval(interval, intervalMultiplier(state));
	}

	/**
	 * 곱셈 규칙만 떼어 놓은 것.
	 *
	 * <p>{@code TeamState.sanitize} 가 저장을 읽을 때 남은 틱을 {@code [0, 주기]} 로 자른다.
	 * 배율이 1보다 커서 주기보다 긴 값을 남겨 두면 서버를 껐다 켜는 순간 조용히 주기로
	 * 되돌아가, 같은 팀이 재시작 전후로 다르게 움직인다. 즉 <b>1보다 큰 배율은 지금 아무
	 * 효과가 없다.</b> 지금 쓰이는 정의는 모두 1보다 작은 배율이다.
	 */
	static int scaleInterval(int intervalTicks, double multiplier) {
		if (intervalTicks <= 0) {
			return 0;
		}
		if (!Double.isFinite(multiplier) || multiplier <= 0.0 || multiplier == 1.0) {
			return intervalTicks;
		}
		double scaled = intervalTicks * multiplier;
		if (!Double.isFinite(scaled)) {
			return intervalTicks;
		}
		long rounded = Math.max(MIN_REMAINING_TICKS, Math.round(scaled));
		return (int) Math.min(intervalTicks, rounded);
	}

	// ------------------------------------------------------------------ 교환 시점 효과

	/**
	 * 교환 시점에 팀원 전원에게 {@code on_swap} 의 하위 효과를 얹는다.
	 *
	 * <p>순간이동이 막혔든 아니든 그대로 얹는다.
	 * 하위 효과가 없는 팀이 대부분이라 이 순회는 대개 아무 일도 하지 않는다.
	 */
	public static void grantOnSwap(@Nullable TeamState state, List<ServerPlayer> members) {
		if (!usesPerks(state) || members.isEmpty()) {
			return;
		}
		for (PerkEffect effect : effectsOf(state)) {
			if (!(effect instanceof OnSwapEffect onSwap)) {
				continue;
			}
			for (ServerPlayer member : members) {
				onSwap.grantTo(member);
			}
		}
	}

	// ------------------------------------------------------------------ 교환 시점 폭발

	/**
	 * 이 팀이 가진 {@code swap_explosion} 정의들. 없으면 빈 목록.
	 *
	 * <p>이 목록이 비어 있지 않다는 것은 두 가지 뜻이다. 자리를 바꿀 때 방금 비운 자리에서
	 * 폭발을 일으켜야 하고({@code PositionSwapManager.swapTeamPositions}), 5초 카운트다운과
	 * 효과음도 이 팀에게는 보내지 말아야 한다({@code PositionSwapManager.tick}). 두 곳 모두
	 * 여기 하나만 물어본다.
	 */
	public static List<SwapExplosionEffect> swapExplosions(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return List.of();
		}
		List<SwapExplosionEffect> found = new ArrayList<>();
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof SwapExplosionEffect explosion) {
				found.add(explosion);
			}
		}
		return found;
	}

	// ------------------------------------------------------------------ 집합형 교환

	/**
	 * 이 팀이 {@code swap_rally}(골드 「정거장」)를 가졌는가.
	 *
	 * <p>참이면 {@code PositionSwapManager.swapMoment}가 순열 교환 대신
	 * {@code RallyPointManager}에게 집합·복귀를 넘긴다. {@link #staggered}보다 먼저 확인한다.
	 */
	public static boolean rallyPoint(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return false;
		}
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof SwapRallyEffect) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 순차 이동

	/**
	 * 이 팀이 {@code staggered_swap}(실버 「시차」)을 가졌는가.
	 *
	 * <p>참이면 {@code PositionSwapManager.swapMoment}가 한 틱 안에서 전부 옮기는 대신
	 * {@code StaggeredSwapManager}에게 진행을 넘긴다.
	 */
	public static boolean staggered(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return false;
		}
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof StaggeredSwapEffect) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 집합

	/** 이 팀이 가진 {@code proximity} 효과들. 해당 없으면 빈 목록. */
	public static List<ProximityEffect> proximities(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return List.of();
		}
		List<ProximityEffect> found = new ArrayList<>();
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof ProximityEffect proximity) {
				found.add(proximity);
			}
		}
		return found;
	}

	/** 이 팀이 가진 {@code gather} 규칙들. 없으면 빈 목록. */
	public static List<GatherEffect> gathers(@Nullable TeamState state) {
		if (!usesPerks(state)) {
			return List.of();
		}
		List<GatherEffect> found = new ArrayList<>();
		for (PerkEffect effect : effectsOf(state)) {
			if (effect instanceof GatherEffect gather) {
				found.add(gather);
			}
		}
		return found;
	}

	// ------------------------------------------------------------------ 열외

	/**
	 * 이 명단에서 <b>위치 교환 열외</b>({@code swap_exempt})를 뺀 사람들.
	 *
	 * <p>열외가 하나도 없으면 받은 목록을 그대로 돌려준다 — 그런 팀이 대부분이라 아무 부담도
	 * 얹히지 않는다.
	 *
	 * <p>여기서 빠지는 것은 <b>자리를 바꾸는 명단</b>뿐이다. 예고 카운트다운도 {@code on_swap}도
	 * 팀 전원이 그대로 받는다. 「그 사람만 교환에서 빠진다」이지 「그 사람만 팀이 아니다」가
	 * 아니기 때문이다.
	 *
	 * <p>{@code PositionSwapManager} 가 이 결과를 순열 교환·{@code StaggeredSwapManager}(시차)·
	 * {@code RallyPointManager}(정거장) 셋 모두에 그대로 넘기므로, 세 갈래가 같은 명단을 쓴다.
	 */
	public static List<ServerPlayer> swapParticipants(@Nullable TeamState state,
			List<ServerPlayer> online) {
		// 「가호 4」가 켜지면 열외가 팀 전원에게 걸려 아무도 움직이지 않는다.
		if (SwapExemptEffect.everyoneIn(state) != null) {
			return List.of();
		}
		Set<UUID> exempt = SwapExemptEffect.ownersIn(state).keySet();
		if (exempt.isEmpty() || online.isEmpty()) {
			return online;
		}
		List<ServerPlayer> movers = new ArrayList<>(online.size());
		for (ServerPlayer player : online) {
			if (!exempt.contains(player.getUUID())) {
				movers.add(player);
			}
		}
		return movers;
	}

	/**
	 * 사람 객체 없이 UUID 만으로 같은 계산을 한다. 시험이 보는 자리이자
	 * {@link #swapParticipants} 가 무엇을 하는지의 정의다.
	 */
	public static List<UUID> swapParticipantIds(@Nullable TeamState state, List<UUID> memberIds) {
		if (SwapExemptEffect.everyoneIn(state) != null) {
			return List.of();
		}
		return withoutExempt(SwapExemptEffect.ownersIn(state).keySet(), memberIds);
	}

	/**
	 * 명단에서 주어진 열외를 뺀다.
	 *
	 * <p>팀도 보관소도 보지 않는 순수 계산이라 시험이 이 자리를 곧바로 본다. 명단의 순서는
	 * 그대로 지킨다 — 순열 교환이 이 순서로 자리를 배정하므로 여기서 섞으면 안 된다.
	 */
	public static List<UUID> withoutExempt(Set<UUID> exempt, List<UUID> memberIds) {
		if (exempt == null || exempt.isEmpty() || memberIds.isEmpty()) {
			return List.copyOf(memberIds);
		}
		List<UUID> movers = new ArrayList<>(memberIds.size());
		for (UUID memberId : memberIds) {
			if (!exempt.contains(memberId)) {
				movers.add(memberId);
			}
		}
		return List.copyOf(movers);
	}

	/**
	 * 교환 시점에 열외 당사자에게만 이동 속도 보너스를 얹는다.
	 *
	 * <p>받는 사람은 <b>그 증강을 고른 사람</b> 하나뿐이다({@code TeamState.perkOwners}).
	 * 명단에 그 사람이 없으면(접속을 끊었거나 죽었으면) 아무 일도 하지 않는다.
	 *
	 * <p>부르는 자리는 {@code PositionSwapManager.swapMoment} 의 맨 앞이다. 시차는 이동이 여러
	 * 틱에 걸쳐 일어나 그 갈래에서 곧바로 돌아가 버리므로, 갈림길 뒤에서 주면 시차 팀의 열외는
	 * 영영 못 받는다.
	 */
	public static void grantSwapExemptBonus(@Nullable TeamState state, List<ServerPlayer> members) {
		if (members.isEmpty()) {
			return;
		}
		// 「가호 4」면 고른 사람이 아니라 팀 전원이 받는다. 그 대신 강화는 없다.
		SwapExemptEffect everyone = SwapExemptEffect.everyoneIn(state);
		if (everyone != null) {
			for (ServerPlayer member : members) {
				everyone.grantTo(member, false);
			}
			return;
		}
		Map<UUID, SwapExemptEffect> exempt = SwapExemptEffect.ownersIn(state);
		if (exempt.isEmpty()) {
			return;
		}
		boolean amplified = PerkBlessingSet.teamAmplified(state);
		for (ServerPlayer member : members) {
			SwapExemptEffect effect = exempt.get(member.getUUID());
			if (effect != null) {
				effect.grantTo(member, amplified);
			}
		}
	}

	// ------------------------------------------------------------------ 추첨 전제조건

	/**
	 * 이 팀이 갖춘 증강 전제조건들({@link Perk.Requirement}).
	 *
	 * <p>{@link PerkDraft} 는 팀 상태를 직접 보지 않으므로(월드 없이 시험할 수 있어야 한다)
	 * 이 한 줄이 팀 상태를 조건 집합으로 옮겨 준다. {@code no_silver_offers} 의
	 * {@code silverBlocked} 플래그와 같은 구조다.
	 *
	 * <p>지금 있는 조건은 {@code position_swap} 하나다. 팀 상태를 모르면 아무것도 갖추지 못한
	 * 것으로 본다 — 모르는 것을 갖춘 것으로 치면 전제조건이 있으나 마나가 된다.
	 *
	 * <p>여기서 보는 것은 <b>팀 설정</b>이지 보유 증강이 아니다. 「소집의 조각」처럼 자동 교환을
	 * 없애는 증강을 가진 팀도 위치 교환 설정 자체는 켜져 있으므로 조건은 갖춘 것이다.
	 */
	public static Set<Perk.Requirement> satisfiedRequirements(@Nullable TeamState state) {
		if (state == null) {
			return Perk.Requirement.NONE;
		}
		EnumSet<Perk.Requirement> satisfied = EnumSet.noneOf(Perk.Requirement.class);
		if (state.positionSwapEnabled()) {
			satisfied.add(Perk.Requirement.POSITION_SWAP);
		}
		return satisfied.isEmpty() ? Perk.Requirement.NONE : satisfied;
	}

	// ------------------------------------------------------------------ 공통

	/** 이 팀이 증강을 쓰고 있고 가진 것이 하나라도 있는가. */
	private static boolean usesPerks(@Nullable TeamState state) {
		return state != null && state.perksEnabled && !state.ownedPerks.isEmpty();
	}

	/**
	 * 이 팀이 보유한 증강들의 효과를 한 줄로 펼친다.
	 *
	 * <p>풀에서 사라진 id 는 건너뛴다. 증강 정의를 손으로 고칠 수 있는 이상 저장에만 남은
	 * id 는 언제든 생긴다.
	 */
	private static List<PerkEffect> effectsOf(TeamState state) {
		List<PerkEffect> effects = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk != null) {
				effects.addAll(perk.effects());
			}
		}
		// 켜진 세트의 효과도 같은 목록에 들어간다. 교환 세트 2단계가 on_swap 을 이 길로 태운다.
		effects.addAll(PerkSetEffects.activeEffectsOf(state));
		return effects;
	}
}
