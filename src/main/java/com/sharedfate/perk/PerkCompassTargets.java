package com.sharedfate.perk;

import com.mojang.datafixers.util.Pair;
import com.sharedfate.SharedFateMod;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.effect.CompassTargetEffect;
import com.sharedfate.perk.effect.CompassToggleEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.SharedItemList;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 나침반 지시 증강({@code compass_target})의 집행부.
 *
 * <p>{@link CompassTargetEffect} 가 "무엇을 어디서 얼마나 넓게 찾을 것인가"만 들고 있고,
 * 실제로 찾아서 나침반에 꽂는 일은 전부 여기서 한다.
 *
 * <h2>어떻게 가리키게 하는가</h2>
 * <p>바닐라의 자철석 나침반과 같은 길을 쓴다. 26.2 의 나침반은
 * {@code assets/minecraft/items/compass.json} 에서 {@code minecraft:lodestone_tracker} 성분이
 * 붙어 있는지로 갈라지고, 붙어 있으면 그 성분이 가리키는 자리를 향한다. 그래서 우리가 할 일은
 * {@code DataComponents.LODESTONE_TRACKER} 에
 * {@code new LodestoneTracker(Optional.of(위치), false)} 를 꽂는 것뿐이다. 클라이언트에 아무것도
 * 깔지 않아도 바닐라 클라이언트가 그대로 그려 준다.
 *
 * <h2>{@code tracked} 는 반드시 {@code false} 여야 한다</h2>
 * <p>{@code LodestoneTracker.tick(ServerLevel)} 은 {@code tracked} 가 참일 때 그 자리에 자철석
 * 블록이 실제로 있는지 확인하고, 없으면 성분을 <b>빈 값으로 갈아 버린다</b>. 요새에는 자철석이
 * 없으므로 참으로 꽂으면 다음 틱에 지워진다. 거짓이면 {@code tick} 이 맨 앞에서 그대로 돌려주고
 * 끝나므로 우리 값이 살아남는다.
 *
 * <h2>어떤 나침반이 우리 것인가</h2>
 * <p>증강을 잃으면 성분을 걷어내야 하는데, 플레이어가 직접 만든 자철석 나침반까지 망가뜨리면
 * 안 된다. 구분 기준은 {@code tracked} 다. 바닐라에서 {@code tracked} 가 거짓인 성분은 절대
 * 생기지 않는다. {@code CompassItem.useOn} 은 언제나 참으로 만들고, 자철석이 사라진 나침반도
 * "참 + 빈 목표"가 되지 별도의 값이 되지 않는다. 그래서 <b>{@code tracked} 가 거짓이고 목표가
 * 있는</b> 성분은 우리가 꽂은 것뿐이고, 걷어낼 때도 그것만 본다({@link #isOurs}).
 *
 * <h2>인벤토리는 팀이 통째로 공유한다</h2>
 * <p>이 모드에서 팀원의 인벤토리는 {@link TeamState#mainItems} 하나를 함께 쓴다. 그것도 사본이
 * 아니라 같은 목록이고, 그 안의 묶음도 같은 객체다. 그래서 손보는 대상은 플레이어별 인벤토리가
 * 아니라 그 공유 목록이고, <b>나침반 하나에 꽂을 수 있는 지시 자리도 팀에 하나뿐이다.</b>
 * 팀원마다 다른 곳을 가리키게 할 방법은 없다.
 *
 * <p>다른 차원에 있는 팀원의 화면에서는 지시 자리의 차원이 맞지 않아 바늘이 헛도는데, 그건
 * 원래 나침반보다 나쁜 상태다. 그래서 <b>아무도 그 차원에 없으면 성분을 걷어내</b> 평범한
 * 나침반으로 되돌린다.
 *
 * <h2>정의를 여럿 가졌을 때 무엇을 고르는가</h2>
 * <p>차원이 다른 정의를 함께 가질 수 있다. 마을은 오버월드, 요새는 네더다. 그래서 고르는
 * 기준은 정의의 순서가 아니라 <b>지금 팀원이 서 있는 차원</b>이다. 그 차원에 아무도 없는
 * 정의는 애초에 후보가 아니고, 후보가 여럿이면 {@link #choose} 가 다음 순서로 하나를 정한다.
 *
 * <ol>
 *   <li>그 차원에 <b>나침반을 주손에 든</b> 팀원이 있는 정의. 나침반은 팀에 하나뿐이므로 지금
 *       실제로 보고 있는 사람의 차원을 가리키는 것이 가장 쓸모 있다. 주손 선택 칸은 공유
 *       목록을 쓰면서도 사람마다 따로라 이 판정이 사람마다 갈린다.</li>
 *   <li>그 차원에 서 있는 팀원이 더 많은 정의.</li>
 *   <li>그래도 같으면 <b>먼저 얻은</b> 정의. 같은 차원에 정의가 둘일 때도 이 규칙이 정한다.
 *       "가장 가까운 것"으로 정하려면 정의마다 구조물을 찾아야 해서 아래의 탐색 비용이 정의
 *       수만큼 불어난다. 한 주기에 탐색을 한 번만 돌리려면 찾기 전에 정해져 있어야 한다.</li>
 * </ol>
 *
 * <h2>탐색은 비싸다</h2>
 * <p>{@code findNearestMapStructure} 는 반경 안의 구조물 배치 후보를 훑는 동기 작업이라 서버
 * 스레드를 그대로 붙잡는다. 그래서 세 겹으로 아낀다.
 *
 * <ol>
 *   <li>{@value #SWEEP_INTERVAL_TICKS} 틱마다만 이 자리를 지난다.</li>
 *   <li>팀원이 그 차원에 <b>있을 때만</b> 찾는다.</li>
 *   <li>한 주기에 찾는 것은 <b>고른 정의 하나뿐</b>이다. 정의를 여럿 가져도 탐색 횟수는 늘지
 *       않는다.</li>
 *   <li>찾은 결과를 팀마다, 그리고 <b>정의마다</b> 캐시하고 {@value #SEARCH_INTERVAL_TICKS}
 *       틱(10초) 안에는 다시 찾지 않는다. 정의별로 나눠 두지 않으면 팀원이 차원을 오갈 때마다
 *       서로의 결과를 밀어내 매번 다시 찾게 된다.</li>
 * </ol>
 *
 * <p>증강을 쓰지 않는 팀은 {@link #targets} 가 곧바로 빈 목록을 돌려주므로 목록 두 개를 훑는
 * 것이 전부다.
 *
 * <h2>세트 「개척 2」의 토글</h2>
 * <p>{@link PerkCompassToggle} 이 켜져 있으면 <b>{@link #choose} 가 고른 것 대신</b> 토글의
 * 대체 정의(엔더 요새)를 쓴다. 후보를 하나 늘리는 것이 아니라 갈아치우는 까닭은
 * {@link com.sharedfate.perk.effect.CompassToggleEffect} 에 적어 두었다 — 마을과 엔더 요새는
 * 둘 다 오버월드라 후보로 나란히 두면 「먼저 얻은 것」 규칙에 걸려 마을이 언제나 이긴다.
 *
 * <p>토글 효과만 있고 {@code compass_target} 은 하나도 없는 팀도 있을 수 있다 — 개척 증강
 * 넷 중 나침반을 주는 것은 둘뿐이라, 나머지 둘로 2단계를 채우면 그렇게 된다. 그때도 토글은
 * 그대로 동작한다. 켜면 팀이 가진 아무 나침반이나 엔더 요새를 가리키고, 끄면 평범한
 * 나침반으로 돌아간다.
 *
 * <h2>나침반 이름</h2>
 * <p>지금 무엇을 가리키는지는 아이템 이름으로만 보인다. 이름은
 * {@code DataComponents.ITEM_NAME} 에 넣는다. {@code CUSTOM_NAME} 이 아니다 —
 * {@code ItemStack.getHoverName} 은 {@code CUSTOM_NAME} 을 먼저 보고 없을 때만
 * {@code ITEM_NAME} 을 보므로, {@code ITEM_NAME} 을 쓰면 <b>모루로 손수 지어 준 이름을 덮지
 * 않는다.</b> 게다가 생존에서 {@code ITEM_NAME} 을 붙일 방법이 없어 <b>붙어 있으면 우리 것</b>이
 * 확실하다. {@code tracked == false} 로 우리 지시 자리를 가려내는 것과 같은 요령이다.
 */
public final class PerkCompassTargets {
	/** 나침반을 훑는 주기. */
	public static final int SWEEP_INTERVAL_TICKS = 20;
	/** 구조물을 다시 찾기까지 기다리는 최소 시간. */
	public static final int SEARCH_INTERVAL_TICKS = 200;

	/** 팀마다, 그리고 정의마다 마지막으로 찾은 결과. */
	private static final Map<UUID, Map<CompassTargetEffect, Cached>> CACHE = new HashMap<>();

	private static int tickCounter;

	private PerkCompassTargets() {
	}

	/**
	 * 한 정의의 마지막 탐색 결과.
	 *
	 * @param searchedAt 찾은 시점의 게임 시각(틱)
	 * @param found      찾은 자리. 반경 안에 없었으면 null
	 */
	private record Cached(long searchedAt, @Nullable GlobalPos found) {
	}

	/**
	 * 정의를 고를 때 보는 팀원 한 명의 상태.
	 *
	 * <p>{@link #choose} 를 살아 있는 서버 없이 시험할 수 있도록, 고르는 데 필요한 것만 떼어
	 * 담는다.
	 *
	 * @param dimension       지금 서 있는 차원
	 * @param holdingCompass  주손에 나침반을 들고 있는가
	 */
	public record Presence(ResourceKey<Level> dimension, boolean holdingCompass) {
	}

	/** 접속해 있는 팀원 한 명. 고르는 데 쓴 판정을 탐색 기준점 고를 때 다시 쓰려고 함께 든다. */
	private record Member(ServerPlayer player, boolean holdingCompass) {
		Presence presence() {
			return new Presence(player.level().dimension(), holdingCompass);
		}
	}

	/** 서버가 멈출 때 캐시와 주기 상태를 비운다. */
	public static void reset() {
		CACHE.clear();
		tickCounter = 0;
	}

	// ------------------------------------------------------------------ 주기

	/**
	 * {@value #SWEEP_INTERVAL_TICKS} 틱마다 팀별로 나침반을 맞춘다.
	 *
	 * <p>{@code SharedFateMod} 의 서버 틱에 붙는다. 증강을 잃은 팀도 <b>반드시 이 자리를 지나야</b>
	 * 꽂아 둔 성분이 걷힌다. 그래서 보유 여부와 무관하게 모든 팀을 훑는다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (++tickCounter < SWEEP_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

		TeamManager manager = TeamManager.get(server);
		Set<UUID> living = new HashSet<>();
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null) {
				continue;
			}
			living.add(team.teamId());
			try {
				updateTeam(server, team, state);
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("나침반 지시 증강을 적용하지 못했습니다.", error);
			}
		}
		// 해체된 팀의 캐시는 남겨 둘 이유가 없다. 토글 기억과 유적 좌표도 같은 기준으로 턴다.
		// 셋 다 저장하지 않는 파생 상태라 여기 한 곳에서 함께 털어야 새지 않는다.
		CACHE.keySet().retainAll(living);
		PerkCompassToggle.retainAll(living);
		PerkRuinSurvey.retainAll(living);
	}

	/**
	 * 한 팀의 나침반을 지금 맞아야 할 모습으로 곧바로 맞춘다.
	 *
	 * <p>{@link #tick} 이 20틱마다 부르는 것과 같은 일을 한다. {@link PerkCompassToggle} 이
	 * 우클릭을 받은 그 자리에서도 부른다 — 1초를 기다리게 하면 누른 사람 눈에는 「안 눌렸다」로
	 * 보여 한 번 더 누르게 되고, 그러면 도로 제자리다.
	 */
	public static void refreshTeam(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state) {
		if (server == null || team == null || state == null) {
			return;
		}
		try {
			updateTeam(server, team, state);
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("나침반 지시 증강을 적용하지 못했습니다.", error);
		}
	}

	private static void updateTeam(MinecraftServer server, ShareTeam team, TeamState state) {
		List<CompassTargetEffect> candidates = targets(state);
		CompassToggleEffect toggle = PerkCompassToggle.effectOf(state);
		// 세트가 깨졌으면 여기서 켬이 지워진다. 「엔더 요새를 가리킨 채로 남는다」를 막는
		// 자리가 이 한 줄이다.
		PerkCompassToggle.forgetIfUnavailable(team.teamId(), toggle);
		boolean alternate = PerkCompassToggle.isAlternate(team.teamId(), toggle);

		if (candidates.isEmpty() && toggle == null) {
			CACHE.remove(team.teamId());
			if (clearTargets(state) > 0) {
				broadcast(server, team);
			}
			return;
		}
		// 증강 풀을 다시 읽으면 정의 객체가 새로 만들어진다. 그때 남는 옛 항목을 여기서 턴다.
		// 토글의 대체 정의도 캐시 열쇠가 되므로 함께 살려 둬야 한다 — 빼 두면 켤 때마다
		// 엔더 요새를 다시 찾는다.
		List<CompassTargetEffect> known = new ArrayList<>(candidates);
		if (toggle != null) {
			known.add(toggle.alternate());
		}
		pruneCache(team.teamId(), known);

		List<Member> members = onlineMembers(server, team);
		CompassTargetEffect effect = alternate && toggle != null
				? toggle.alternate()
				: choose(candidates, presences(members));
		ServerPlayer scout = effect == null ? null : scoutFor(members, effect);
		// 이름은 토글을 가진 팀에만 붙인다. 토글이 없으면 평범한 나침반 이름 그대로다.
		String name = toggle == null ? null : toggle.nameFor(alternate);
		int changed;
		if (scout == null) {
			// 지금 팀원이 서 있는 차원에 맞는 정의가 하나도 없다. 평범한 나침반으로 돌려 둔다.
			// 캐시는 남겨 두므로 곧바로 돌아와도 다시 찾지 않는다.
			changed = clearTargets(state);
		} else {
			GlobalPos target = locate(server, team.teamId(), effect, scout);
			changed = target == null ? clearTargets(state) : applyTarget(state, target, name);
		}
		if (changed > 0) {
			broadcast(server, team);
		}
	}

	// ------------------------------------------------------------------ 정의 고르기

	/**
	 * 이 팀이 가진 {@code compass_target} 을 <b>얻은 순서대로</b> 모은다. 없으면 빈 목록.
	 *
	 * <p>{@code ownedPerks} 는 증강을 얻은 차례대로 쌓이므로, 이 목록의 앞자리가 곧 먼저 얻은
	 * 정의다. {@link #choose} 의 마지막 판가름이 그 순서를 쓴다.
	 */
	public static List<CompassTargetEffect> targets(@Nullable TeamState state) {
		TeamState active = PerkWorldRules.activeState(state);
		if (active == null) {
			return List.of();
		}
		List<CompassTargetEffect> found = new ArrayList<>();
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof CompassTargetEffect target) {
					found.add(target);
				}
			}
		}
		return found;
	}

	/**
	 * 지금 가리킬 정의 하나를 고른다. 맞는 것이 없으면 null.
	 *
	 * <p>고르는 판정 전부가 여기 있고 월드도 서버도 보지 않는다. 클래스 설명에 적은 세 단계를
	 * 그대로 따른다.
	 *
	 * @param state   팀 상태
	 * @param members 접속해 있는 팀원들의 상태
	 */
	public static @Nullable CompassTargetEffect choose(@Nullable TeamState state,
			List<Presence> members) {
		return choose(targets(state), members);
	}

	private static @Nullable CompassTargetEffect choose(List<CompassTargetEffect> candidates,
			List<Presence> members) {
		CompassTargetEffect best = null;
		boolean bestHeld = false;
		int bestCount = 0;
		for (CompassTargetEffect effect : candidates) {
			int count = 0;
			boolean held = false;
			for (Presence member : members) {
				if (!member.dimension().equals(effect.dimension())) {
					continue;
				}
				count++;
				held |= member.holdingCompass();
			}
			if (count == 0) {
				// 그 차원에 아무도 없다. 가리켜 봐야 바늘이 헛돈다.
				continue;
			}
			if (best == null || beats(held, count, bestHeld, bestCount)) {
				best = effect;
				bestHeld = held;
				bestCount = count;
			}
		}
		return best;
	}

	/**
	 * 뒤에 온 후보가 앞선 후보를 이기는가.
	 *
	 * <p>비기면 거짓이다. 후보를 얻은 순서대로 훑으므로, 비긴 자리는 먼저 얻은 쪽이 남는다.
	 */
	private static boolean beats(boolean held, int count, boolean bestHeld, int bestCount) {
		if (held != bestHeld) {
			return held;
		}
		return count > bestCount;
	}

	private static List<Presence> presences(List<Member> members) {
		List<Presence> presences = new ArrayList<>(members.size());
		for (Member member : members) {
			presences.add(member.presence());
		}
		return presences;
	}

	// ------------------------------------------------------------------ 구조물 찾기

	/** 캐시가 살아 있으면 그 값, 아니면 새로 찾는다. */
	private static @Nullable GlobalPos locate(MinecraftServer server, UUID teamId,
			CompassTargetEffect effect, ServerPlayer scout) {
		long now = server.overworld().getGameTime();
		Map<CompassTargetEffect, Cached> byEffect =
				CACHE.computeIfAbsent(teamId, id -> new IdentityHashMap<>());
		Cached cached = byEffect.get(effect);
		// 회차가 초기화되면 게임 시각이 뒤로 갈 수 있다. 그때는 캐시를 믿지 않는다.
		if (cached != null && now >= cached.searchedAt()
				&& now - cached.searchedAt() < SEARCH_INTERVAL_TICKS) {
			return cached.found();
		}
		GlobalPos found = search(server, effect, scout);
		byEffect.put(effect, new Cached(now, found));
		return found;
	}

	/** 지금 가진 정의의 결과만 남긴다. */
	private static void pruneCache(UUID teamId, List<CompassTargetEffect> candidates) {
		Map<CompassTargetEffect, Cached> byEffect = CACHE.get(teamId);
		if (byEffect != null && !byEffect.isEmpty()) {
			byEffect.keySet().removeIf(effect -> !containsSame(candidates, effect));
		}
	}

	/** {@code IdentityHashMap} 의 열쇠와 같은 기준으로, 같은 객체가 목록에 있는지 본다. */
	private static boolean containsSame(List<CompassTargetEffect> candidates,
			CompassTargetEffect effect) {
		for (CompassTargetEffect candidate : candidates) {
			if (candidate == effect) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 실제 탐색. 반경 안에 없으면 null.
	 *
	 * <p>{@code ServerLevel.findNearestMapStructure} 는 26.2 에서 <b>태그만</b> 받는다
	 * ({@code TagKey<Structure>}). 요새에는 태그가 없으므로 그 메서드를 그대로 쓸 수 없다.
	 * 그래서 그 메서드가 안에서 하는 일과 똑같이, 정의를 {@code HolderSet} 으로 푼 뒤
	 * {@code ChunkGenerator.findNearestMapStructure(ServerLevel, HolderSet, BlockPos, int, boolean)}
	 * 를 직접 부른다. 마지막 인자는 "이미 만들어진 구조물을 건너뛸지"이고, 바닐라
	 * {@code /locate structure} 와 같이 거짓을 준다.
	 */
	private static @Nullable GlobalPos search(MinecraftServer server, CompassTargetEffect effect,
			ServerPlayer scout) {
		ServerLevel level = server.getLevel(effect.dimension());
		if (level == null) {
			SharedFateMod.LOGGER.warn("compass_target 이 가리키는 차원이 없습니다: {}",
					effect.dimension().identifier());
			return null;
		}
		HolderSet<Structure> structures = effect.resolve(server.registryAccess());
		if (structures == null || structures.size() == 0) {
			SharedFateMod.LOGGER.warn("compass_target 이 가리키는 구조물을 찾을 수 없습니다: {}",
					effect.structureTag() == null
							? String.valueOf(effect.structureKey())
							: "#" + effect.structureTag().location());
			return null;
		}

		BlockPos origin = scout.blockPosition();
		Pair<BlockPos, Holder<Structure>> found = level.getChunkSource().getGenerator()
				.findNearestMapStructure(level, structures, origin, effect.searchRadius(), false);
		return found == null ? null : GlobalPos.of(effect.dimension(), found.getFirst());
	}

	private static List<Member> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<Member> members = new ArrayList<>();
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				// 주손 선택 칸은 공유 목록을 쓰면서도 사람마다 따로다. 왼손은 팀이 함께 쓰므로
				// 여기서 보면 전원이 들고 있는 것이 되어 사람을 가려내지 못한다.
				members.add(new Member(online, isCompass(online.getMainHandItem())));
			}
		}
		return members;
	}

	/**
	 * 탐색 기준점이 될 팀원. 고른 정의의 차원에 있는 사람 중에서 고른다.
	 *
	 * <p>{@link #choose} 가 고른 정의는 그 차원에 팀원이 있을 때만 나오므로 여기서 빈손으로
	 * 돌아가는 일은 없다. 나침반을 든 사람이 있으면 그 사람을 앞세운다 — 지금 바늘을 보고 있는
	 * 사람에게 가장 가까운 구조물이 잡힌다.
	 */
	private static @Nullable ServerPlayer scoutFor(List<Member> members,
			CompassTargetEffect effect) {
		ServerPlayer fallback = null;
		for (Member member : members) {
			if (!member.player().level().dimension().equals(effect.dimension())) {
				continue;
			}
			if (member.holdingCompass()) {
				return member.player();
			}
			if (fallback == null) {
				fallback = member.player();
			}
		}
		return fallback;
	}

	// ------------------------------------------------------------------ 나침반 손보기

	/**
	 * 이 성분을 우리가 꽂았는가.
	 *
	 * <p>플레이어가 만든 자철석 나침반은 언제나 {@code tracked} 가 참이다. 그래서 거짓이면서
	 * 목표가 들어 있는 성분은 우리 것뿐이다.
	 */
	public static boolean isOurs(@Nullable LodestoneTracker tracker) {
		return tracker != null && !tracker.tracked() && tracker.target().isPresent();
	}

	/**
	 * 팀의 나침반이 이 자리를 가리키게 한다. 실제로 바뀐 개수를 돌려준다.
	 *
	 * <p>{@code name} 을 주면 아이템 이름도 그것으로 맞춘다({@code ITEM_NAME}). {@code null}
	 * 이면 우리가 붙여 둔 이름을 걷어낸다 — 세트를 잃은 팀의 나침반에 「엔더 요새 나침반」이
	 * 남아 있으면 안 된다.
	 */
	public static int applyTarget(TeamState state, GlobalPos target, @Nullable String name) {
		LodestoneTracker wanted = new LodestoneTracker(Optional.of(target), false);
		return visitCompasses(state, stack -> {
			LodestoneTracker existing = stack.get(DataComponents.LODESTONE_TRACKER);
			if (existing != null && !isOurs(existing)) {
				// 플레이어가 직접 만든 자철석 나침반. 증강이 빼앗아 갈 물건이 아니다.
				return false;
			}
			boolean changed = false;
			if (!wanted.equals(existing)) {
				stack.set(DataComponents.LODESTONE_TRACKER, wanted);
				changed = true;
			}
			return applyName(stack, name) || changed;
		});
	}

	/** 이름 없이 자리만 맞추던 예전 호출을 위해 남겨 둔다. */
	public static int applyTarget(TeamState state, GlobalPos target) {
		return applyTarget(state, target, null);
	}

	/**
	 * 우리가 꽂아 둔 성분을 걷어내 평범한 나침반으로 되돌린다. 실제로 바뀐 개수를 돌려준다.
	 *
	 * <p>이름도 함께 걷는다. 바늘만 되돌리고 이름을 남기면 「엔더 요새 나침반」이라 적힌 채
	 * 아무 데도 안 가리키는 물건이 된다.
	 */
	public static int clearTargets(TeamState state) {
		return visitCompasses(state, stack -> {
			boolean changed = applyName(stack, null);
			if (!isOurs(stack.get(DataComponents.LODESTONE_TRACKER))) {
				return changed;
			}
			stack.remove(DataComponents.LODESTONE_TRACKER);
			return true;
		});
	}

	/**
	 * 나침반 이름을 맞춘다. 실제로 바뀌었으면 참.
	 *
	 * <p>{@code ITEM_NAME} 만 본다. 생존에서 그 성분을 바꿀 방법이 없으므로 <b>기본값과 다르면
	 * 우리 것</b>이고, 모루로 지어 준 이름({@code CUSTOM_NAME})은 건드리지도 가리지도 않는다 —
	 * {@code ItemStack.getHoverName} 이 {@code CUSTOM_NAME} 을 먼저 보기 때문이다.
	 *
	 * <p><b>걷을 때 {@code remove} 를 쓰면 안 된다.</b> 26.3 의 모든 아이템은 기본 성분에
	 * {@code ITEM_NAME} 을 갖고 있다(나침반은 {@code item.minecraft.compass}). 지워 버리면
	 * {@code getItemName()} 이 빈 글자를 돌려줘 <b>이름 없는 나침반</b>이 된다. 그래서 되돌릴
	 * 때는 기본값을 <b>다시 넣는다</b> — {@code PatchedDataComponentMap} 은 넣는 값이 기본값과
	 * 같으면 덧칠 항목을 아예 지우므로, 결과는 손댄 적 없는 나침반과 완전히 같다.
	 */
	private static boolean applyName(ItemStack stack, @Nullable String name) {
		Component fallback = stack.getPrototype().get(DataComponents.ITEM_NAME);
		Component existing = stack.get(DataComponents.ITEM_NAME);
		// 기울임을 끈다. 바닐라는 이름을 붙인 아이템을 기울여 쓰는데, 이 이름은 사람이 지은
		// 것이 아니라 「지금 무엇을 가리키는가」라는 상태 표시라 평범하게 보이는 편이 맞다.
		Component wanted = name == null
				? fallback
				: Component.literal(name).withStyle(style -> style.withItalic(false));
		if (wanted == null || wanted.equals(existing)) {
			return false;
		}
		stack.set(DataComponents.ITEM_NAME, wanted);
		return true;
	}

	/**
	 * 팀이 가진 나침반을 모두 훑는다.
	 *
	 * <p>보는 곳은 공유 인벤토리(그리고 확장 칸을 켰으면 그쪽까지)와 공유 왼손 칸이다.
	 * 오른손에 든 것은 단축바에 있으므로 공유 인벤토리에 이미 들어 있다. 넘침 대기열은 보지
	 * 않는다. 화면에 보이지 않는 자리라 가리켜 봐야 쓸 데가 없고, 칸이 비면 어차피
	 * 인벤토리로 들어와 다음 주기에 잡힌다.
	 *
	 * @return {@code action} 이 참을 돌려준 묶음 수
	 */
	private static int visitCompasses(TeamState state, Predicate<ItemStack> action) {
		int changed = visitList(state.mainItems, action);
		if (ExpandedInventoryManager.enabled()) {
			changed += visitList(state.extraItems, action);
		}
		ItemStack offhand = state.equipment.get(EquipmentSlot.OFFHAND);
		if (isCompass(offhand) && action.test(offhand)) {
			changed++;
		}
		return changed;
	}

	private static int visitList(SharedItemList items, Predicate<ItemStack> action) {
		int changed = 0;
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.get(slot);
			if (isCompass(stack) && action.test(stack)) {
				changed++;
			}
		}
		return changed;
	}

	private static boolean isCompass(@Nullable ItemStack stack) {
		return stack != null && !stack.isEmpty() && stack.getItem() == Items.COMPASS;
	}

	/**
	 * 바뀐 나침반을 화면에 바로 반영한다.
	 *
	 * <p>묶음을 제자리에서 고쳤을 뿐이라 창은 다음 방송 때 알아서 따라오지만, 성분이 바뀌면
	 * 이름과 반짝임까지 달라지므로 한 틱이라도 늦으면 눈에 띈다.
	 */
	private static void broadcast(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null && online.containerMenu != null) {
				online.containerMenu.broadcastChanges();
			}
		}
	}
}
