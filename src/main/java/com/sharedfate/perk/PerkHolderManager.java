package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.perk.effect.HolderEffect.HolderMode;
import com.sharedfate.perk.effect.HolderEffect.ModeResolver;
import com.sharedfate.perk.effect.OwnerBoundEffect;
import com.sharedfate.sync.SpreadDamageManager;
import com.sharedfate.sync.TitleMessenger;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * {@code holder} 증강의 보유자를 정하고 넘기는 곳.
 *
 * <p>{@link HolderEffect} 는 "보유자에게 무엇을 붙이는가"만 알고, "지금 누가 보유자인가"와
 * "언제 누구에게 넘기는가"는 여기서 정한다.
 *
 * <h2>보유자는 저장하지 않는다</h2>
 * <p>보유자는 이 클래스의 런타임 메모리에만 있다. 대신 {@link #reset} 이 서버가 멈출 때 반드시
 * 비운다. 비우지 않으면 다음 월드에 이전 회차의 보유자가 남는다.
 *
 * <h2>기준 시각</h2>
 * <p>{@link PeriodicPerkManager} 와 달리 오버월드의 게임 시간을 쓰지 않는다.
 *
 * <p>대신 <b>강제 증강 선택 세션이 살아 있는 동안에는 카운터 자체를 멈춘다.</b> 선택창이 떠 있는
 * 동안 팀원은 움직일 수도 맞을 수도 없으므로, 그동안 보유자가 바뀌면 아무도 그 사실을 겪지
 * 못한 채 버프만 옮겨 다닌다.
 *
 * <h2>보유자를 잃는 경우</h2>
 * <ul>
 *   <li>접속을 끊거나 죽으면 그 자리에서 다른 팀원에게 넘긴다.</li>
 *   <li>넘길 팀원이 없으면(혼자인 팀) 그대로 유지한다.</li>
 *   <li>아무도 접속해 있지 않으면 보유자를 비운다. 누군가 돌아오면 그때 새로 뽑는다.</li>
 * </ul>
 *
 * <h2>{@code fixed_to_owner} 는 위 규칙을 전부 건너뛴다</h2>
 * <p>{@link HolderEffect#fixedToOwner()} 가 참인 증강은 <b>그 증강을 고른 사람</b>이 회차 내내
 * 보유자다. 순환({@code rotate_ticks})도, 피격 넘김({@code pass_on_hurt})도, 죽었을 때의 넘김도
 * 일어나지 않는다. 이 갈래는 {@link #reconcileFixed} 하나로 끝나고, 나머지 경로
 * ({@link #passOnHurt}, {@link #release})는 이런 효과를 만나면 그냥 지나친다.
 *
 * <p>"고른 사람"은 {@code TeamState.perkOwners} 에 증강 id 별로 적혀 있다. 보유자와 달리 이
 * 값은 <b>월드 저장에 들어간다</b>. 적는 곳은 {@code PerkManager.commit} 한 자리뿐이다.
 *
 * <p><b>주인이 접속을 끊으면 그동안 보유자는 없다</b>({@link #fixedHolder} 가 null 을 돌려준다).
 * 무작위로 넘기면 「고정」이 아니게 되므로 넘기지 않는다. 다시 들어오면 다음 점검(반 초 이내)에
 * 그 사람이 곧바로 보유자로 돌아온다. 그동안 나머지 팀원은 {@code on_others} 를 계속 받는다 —
 * 왕이 없는 동안 팀이 디메리트만 지는 것도 의도한 결과다.
 *
 * <p>주인이 적혀 있지 않은 경우도 있다. 「숨은 재능」처럼 다른 증강이 덤으로 준 증강은 고른
 * 사람이 없다. 그때는 <b>처음 뽑힌 사람을 그대로 주인으로 삼아 적어 둔다.</b> 한 번 정해지면
 * 그 뒤로는 진짜 주인과 똑같이 고정된다.
 *
 * <h2>{@link HolderMode} — 강화와 「전원에게」</h2>
 * <p>{@link HolderEffect} 는 모드에 따라 다른 묶음을 붙인다. <b>지금 어느 모드인가를 정하는
 * 규칙은 이 클래스가 모른다.</b> 세트 정의와 세트 유형을 아는 쪽이
 * {@link #setModeResolver}(으)로 판정기를 꽂아 주고, 여기서는 그 답을 받아 나른다.
 * 판정기를 꽂지 않으면 언제나 {@link HolderMode#NORMAL} 이라, 이 기능을 쓰지 않는 서버에서는
 * 팀 상태를 찾아보는 일조차 하지 않는다.
 *
 * <p><b>모드가 바뀌는 순간이 이 확장에서 가장 위험한 자리다.</b> 이전 모드의 효과가 남아 있으면
 * 속성 수정자가 그대로 두 번 더해진다. 그래서 팀마다 마지막으로 맞춰 둔 모드를
 * {@link Holding#mode} 에 적어 두고, 답이 달라진 틱에 <b>접속 중인 팀원 전원</b>을 새 모드로
 * 다시 맞춘다({@link #reconcileMode}). 실제로 무엇을 떼고 무엇을 붙일지는
 * {@link HolderEffect#applyAs} 가 사람마다 기억해 둔 묶음을 보고 정확히 가른다.
 */
public final class PerkHolderManager {
	/** 보유자를 다시 살펴보는 주기. 반 초면 체감상 즉시 반응하는 것과 다르지 않다. */
	private static final int CHECK_INTERVAL_TICKS = 10;

	/** 넘어가지 않은 팀을 정리하는 주기. 자주 할 이유가 없다. */
	private static final int CLEANUP_INTERVAL_TICKS = 600;

	/**
	 * 보유자 하나를 가리키는 열쇠.
	 *
	 * <p>{@link HolderEffect} 객체는 증강 정의 하나당 하나뿐이라 객체 동일성으로 구분된다.
	 * 팀이 여럿이면 팀마다 보유자가 따로 있으므로 팀 식별자도 함께 묶는다.
	 */
	private record Key(HolderEffect effect, UUID teamId) {
	}

	/** 한 팀이 이 효과에 대해 들고 있는 상태. */
	private static final class Holding {
		/** 지금 보유자. 접속한 팀원이 하나도 없으면 null. */
		@Nullable UUID holder;
		/** 보유가 시작된 시각({@link #now} 기준). 순환과 최소 유지 시간의 기준점이다. */
		long since;
		/**
		 * 이 팀 전원을 마지막으로 맞춰 둔 모드.
		 *
		 * <p>매 틱 다시 판정하지만, 답이 이 값과 같으면 아무것도 하지 않는다. 모드가 그대로인
		 * 보통의 틱에 팀 전원의 수정자를 뗐다 붙이면 성능도 나쁘고 최대 체력을 건드리는 수정자는
		 * 현재 체력까지 깎는다. {@code ConditionalEffect} 가 판정을 기억해 두는 이유와 같다.
		 */
		HolderMode mode = HolderMode.NORMAL;
	}

	/** 한 증강의 최상위 {@code holder} 효과와 그 증강. 알림 문구에 증강 이름이 필요하다. */
	private record Owned(Perk perk, HolderEffect effect) {
	}

	private static final Map<Key, Holding> HOLDINGS = new ConcurrentHashMap<>();

	/** 자체 틱 카운터. {@link #tick} 이 실제로 진행한 횟수다. */
	private static volatile long now;

	private static int checkCounter;
	private static int cleanupCounter;
	private static volatile boolean warned;

	/**
	 * 지금 어느 모드인지 정하는 판정기. 꽂지 않으면 언제나 {@link HolderMode#NORMAL} 이다.
	 *
	 * <p>세트 정의와 세트 유형을 아는 쪽이 한 번 꽂아 둔다. 여기서는 무엇을 보고 정하는지 알지
	 * 못한다. 서버가 멈출 때도 지우지 않는다 — 모드 배선은 모드 초기화 때 한 번 하는 일이라,
	 * 월드를 바꿀 때마다 지우면 두 번째 월드부터 스위치가 죽는다.
	 */
	private static volatile @Nullable ModeResolver modeResolver;

	private PerkHolderManager() {
	}

	// ------------------------------------------------------------------ 모드 스위치

	/**
	 * 모드 판정기를 꽂는다. null 을 주면 언제나 {@link HolderMode#NORMAL} 로 돌아간다.
	 *
	 * <p><b>이것이 「가호」 같은 세트가 눌러야 할 스위치다.</b> 꽂는 쪽은 팀 상태와 증강 id 를
	 * 받아 {@link HolderMode#resolve} 로 두 스위치를 접어 돌려주면 된다. 증강 id 로 <b>세트에
	 * 속한 증강만</b> 골라야 한다. 팀 단위로만 판정하면 같은 팀이 함께 들고 있는 다른
	 * {@code holder} 증강까지 덩달아 강화된다.
	 */
	public static void setModeResolver(@Nullable ModeResolver resolver) {
		modeResolver = resolver;
	}

	/**
	 * 이 팀에서 이 증강이 지금 어느 모드인가.
	 *
	 * <p>판정기가 없거나 예외를 내면 {@link HolderMode#NORMAL} 이다. 서버 틱 한가운데서 불리므로
	 * 밖에서 꽂은 함수의 실수 때문에 보유자 처리 전체가 멈추게 두지 않는다.
	 */
	public static HolderMode modeOf(@Nullable TeamState state, @Nullable String perkId) {
		ModeResolver resolver = modeResolver;
		if (resolver == null) {
			return HolderMode.NORMAL;
		}
		try {
			HolderMode mode = resolver.resolve(state, perkId);
			return mode == null ? HolderMode.NORMAL : mode;
		} catch (RuntimeException error) {
			warnOnce(error);
			return HolderMode.NORMAL;
		}
	}

	/**
	 * 이 사람이 속한 팀에서 이 증강이 지금 어느 모드인가.
	 *
	 * <p>{@link HolderEffect} 가 플레이어 하나만 들고 부르는 자리다. 판정기를 꽂지 않았으면
	 * 팀 상태를 찾아보지도 않는다 — 이 기능을 쓰지 않는 서버의 뜨거운 경로(피해 배율 조회)에
	 * 아무 부담도 얹히지 않게 한다.
	 */
	public static HolderMode modeFor(@Nullable UUID player, @Nullable String perkId) {
		if (player == null || modeResolver == null) {
			return HolderMode.NORMAL;
		}
		return modeOf(TeamLookup.stateOf(player), perkId);
	}

	// ------------------------------------------------------------------ 조회

	/** 지금까지 센 틱 수. */
	public static long currentTick() {
		return now;
	}

	/**
	 * 이 사람이 이 효과의 보유자인가.
	 *
	 * <p>{@link HolderEffect#damageDealtMultiplier()} 가 부르는 자리다. 한 플레이어는 팀 하나에만
	 * 속하므로, 이 효과의 보유자 목록 어딘가에 그 UUID 가 있으면 곧 자기 팀의 보유자다.
	 * 팀 수만큼만 도는 순회라 비용이 없고, 보유자가 하나도 없는 서버에서는 첫 줄에서 되돌아간다.
	 */
	public static boolean isHolder(@Nullable HolderEffect effect, @Nullable UUID player) {
		if (effect == null || player == null || HOLDINGS.isEmpty()) {
			return false;
		}
		for (Map.Entry<Key, Holding> entry : HOLDINGS.entrySet()) {
			if (entry.getKey().effect() == effect && player.equals(entry.getValue().holder)) {
				return true;
			}
		}
		return false;
	}

	/** 이 팀에서 이 효과의 보유자. 아직 정해지지 않았으면 null. */
	public static @Nullable UUID holderOf(@Nullable HolderEffect effect, @Nullable UUID teamId) {
		if (effect == null || teamId == null) {
			return null;
		}
		Holding holding = HOLDINGS.get(new Key(effect, teamId));
		return holding == null ? null : holding.holder;
	}

	// ------------------------------------------------------------------ 보유자 선정 (순수 함수)

	/**
	 * 다음 보유자를 고른다.
	 *
	 * <p>보유자 선정 규칙 전체가 여기 한 곳에 있다. 마인크래프트 타입을 하나도 쓰지 않으므로
	 * 서버 없이 그대로 시험할 수 있다.
	 *
	 * <ul>
	 *   <li>후보가 없으면 null — 아무도 접속해 있지 않다는 뜻이다.</li>
	 *   <li>지금 보유자를 뺀 후보가 있으면 그중에서 무작위로 고른다. 같은 사람이 연달아
	 *       뽑히지 않게 반드시 뺀다.</li>
	 *   <li>뺐더니 아무도 남지 않으면(혼자인 팀) 지금 보유자를 그대로 유지한다.
	 *       단 지금 보유자가 후보에 없으면(나갔거나 죽었으면) null 이다.</li>
	 * </ul>
	 *
	 * @param current     지금 보유자. 아직 없으면 null
	 * @param candidates  고를 수 있는 사람들. 보통 접속 중인 팀원이다
	 * @param randomBelow {@code n} 을 받아 0 이상 {@code n} 미만의 수를 돌려주는 함수.
	 *                    null 이면 첫 후보를 고른다
	 */
	public static @Nullable UUID chooseNextHolder(@Nullable UUID current,
			@Nullable List<UUID> candidates, @Nullable IntUnaryOperator randomBelow) {
		if (candidates == null || candidates.isEmpty()) {
			return null;
		}
		List<UUID> others = new ArrayList<>(candidates.size());
		for (UUID candidate : candidates) {
			if (candidate != null && !candidate.equals(current)) {
				others.add(candidate);
			}
		}
		if (others.isEmpty()) {
			// 넘길 곳이 없다. 지금 보유자가 아직 살아 있으면 그대로 두고, 아니면 비운다.
			return current != null && candidates.contains(current) ? current : null;
		}
		if (others.size() == 1 || randomBelow == null) {
			return others.getFirst();
		}
		// 밖에서 들어온 난수를 그대로 믿지 않는다. 범위를 벗어나도 반드시 후보 안에 떨어진다.
		return others.get(Math.floorMod(randomBelow.applyAsInt(others.size()), others.size()));
	}

	/**
	 * {@code fixed_to_owner} 인 증강의 지금 보유자.
	 *
	 * <p>규칙이 한 줄이다 — <b>주인이 접속해 있으면 주인, 아니면 아무도 아니다.</b> 마인크래프트
	 * 타입을 하나도 쓰지 않으므로 서버 없이 그대로 시험할 수 있다. 이 규칙이 깨지면 「제왕과
	 * 신하」의 왕이 조용히 다른 사람에게 넘어가므로 반드시 시험으로 못박아 둔다.
	 *
	 * @param owner      이 증강을 고른 사람. 아직 모르면 null
	 * @param candidates 지금 접속해 있는 팀원들
	 * @return 보유자. 주인이 접속해 있지 않으면 null
	 */
	public static @Nullable UUID fixedHolder(@Nullable UUID owner, @Nullable List<UUID> candidates) {
		if (owner == null || candidates == null || !candidates.contains(owner)) {
			return null;
		}
		return owner;
	}

	/**
	 * 최소 유지 시간을 채웠는가.
	 *
	 * <p>{@code pass_on_hurt} 로 넘기려면 참이어야 한다. 이 장치가 없으면 받자마자 한 대 맞고
	 * 곧바로 넘어가 버려 버프를 쓸 틈이 없다.
	 */
	public static boolean holdSatisfied(long since, long now, int minHoldTicks) {
		return minHoldTicks <= 0 || now - since >= minHoldTicks;
	}

	/** 순환할 때가 됐는가. {@code rotateTicks} 가 0 이면 시간으로는 절대 넘어가지 않는다. */
	public static boolean rotationDue(long since, long now, int rotateTicks) {
		return rotateTicks > 0 && now - since >= rotateTicks;
	}

	// ------------------------------------------------------------------ 주기 진행

	/**
	 * 보유자를 한 틱 살펴본다.
	 *
	 * <p>서버 틱 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다. 보유자를 실제로
	 * 갈아 끼우는 것은 순환 시각이 됐거나 보유자가 사라졌을 때뿐이고, 나머지 틱에는 카운터만
	 * 오른다. 증강 풀이 비어 있거나 {@code holder} 증강을 가진 팀이 없으면 곧바로 되돌아간다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		// 강제 증강 선택 중에는 시간이 얼어 있고 팀원은 움직일 수도 맞을 수도 없다.
		// 그동안 보유자가 바뀌면 아무도 그 사실을 겪지 못하므로 시계째로 멈춘다.
		if (PerkChoiceSession.isActive()) {
			return;
		}
		now++;
		if (++checkCounter < CHECK_INTERVAL_TICKS) {
			return;
		}
		checkCounter = 0;

		try {
			TeamManager manager = TeamManager.get(server);
			for (ShareTeam team : List.copyOf(manager.allTeams())) {
				TeamState state = manager.stateByTeamId(team.teamId());
				if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
					continue;
				}
				for (Owned owned : holdersOf(state)) {
					if (owned.effect().fixedToOwner()) {
						reconcileFixed(server, manager, team, state, owned);
					} else {
						reconcile(server, team, state, owned);
					}
				}
				assignMissingOwners(server, manager, team, state);
			}
			if (++cleanupCounter >= CLEANUP_INTERVAL_TICKS) {
				cleanupCounter = 0;
				forgetStale(manager);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * {@code fixed_to_owner} 증강의 보유자를 주인에게 맞춘다.
	 *
	 * <p>순환도 최소 유지 시간도 보지 않는다. 주인이 접속해 있으면 주인이 보유자, 아니면
	 * 보유자가 없다. 실제로 갈아 끼우는 것은 그 답이 지금과 달라졌을 때뿐이라, 주인이 그대로
	 * 접속해 있는 보통의 틱에는 아무 일도 하지 않는다.
	 *
	 * <p>{@code on_pass} 는 언제나 걸지 않는다. 애초에 이 조합의 정의는
	 * {@link HolderEffect#fromJson} 이 받아 주지 않는다.
	 */
	private static void reconcileFixed(MinecraftServer server, TeamManager manager, ShareTeam team,
			TeamState state, Owned owned) {
		HolderEffect effect = owned.effect();
		Holding holding = HOLDINGS.computeIfAbsent(
				new Key(effect, team.teamId()), ignored -> new Holding());
		reconcileMode(server, team, state, owned, holding);
		List<UUID> online = onlineMembers(server, team);

		UUID owner = state.perkOwners.get(owned.perk().id());
		if (owner == null && !online.isEmpty()) {
			// 고른 사람을 알 수 없는 경로로 들어온 증강이다(「숨은 재능」 등). 처음 뽑힌 사람을
			// 그대로 주인으로 굳혀 둔다.
			owner = chooseNextHolder(null, online, randomOf(server));
			if (owner != null) {
				state.perkOwners.put(owned.perk().id(), owner);
				manager.setDirty();
			}
		}

		UUID next = fixedHolder(owner, online);
		if (Objects.equals(next, holding.holder)) {
			return;
		}
		assign(server, team, owned, holding, next, holding.holder, false);
	}

	/**
	 * 「고른 사람만」 증강인데 주인이 없는 것들에게 <b>접속 중인 팀원 중 한 명을 무작위로</b>
	 * 정해 주고, 팀 전원에게 누가 대상인지 알린다.
	 *
	 * <h2>왜 필요한가</h2>
	 * <p>주인은 {@code PerkManager.commit} 이 <b>고른 사람이 있을 때만</b> 적는다. 「숨은 재능」·
	 * 「하늘의 은총」·「요행」·「도박꾼」으로 덤으로 받거나 「환골탈태」로 갈아엎으면 주인이
	 * 비어 있고, 그러면 {@code perkOwners.get(id)} 가 {@code null} 이라 <b>팀의 누구와도 같지
	 * 않아 효과가 아무에게도 안 걸린다.</b> 「비행 부적」과 「열외」가 실제로 그랬다.
	 *
	 * <p>{@link HolderEffect} 를 가진 증강은 {@link #reconcileFixed} 가 이미 같은 일을 한다.
	 * 여기서는 그 장치가 닿지 못하는 {@link OwnerBoundEffect} 들을 같은 규칙으로 구제한다.
	 *
	 * <h2>한 번 정하면 바뀌지 않는다</h2>
	 * <p>{@code perkOwners} 에 적어 두고 저장까지 되므로, 서버를 껐다 켜도 같은 사람이다.
	 * 「고른 사람」과 완전히 같은 취급이 된다.
	 *
	 * <p>접속한 팀원이 하나도 없으면 아무것도 하지 않는다. 누군가 들어오면 그때(반 초 이내)
	 * 정해진다 — 아무도 없는 사이에 정하면 그 사실을 아무도 못 본다.
	 */
	private static void assignMissingOwners(MinecraftServer server, TeamManager manager,
			ShareTeam team, TeamState state) {
		List<UUID> online = null;
		for (String perkId : List.copyOf(state.ownedPerks)) {
			if (state.perkOwners.containsKey(perkId)) {
				continue;
			}
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null || !needsOwner(perk)) {
				continue;
			}
			if (online == null) {
				online = onlineMembers(server, team);
			}
			if (online.isEmpty()) {
				return;
			}
			UUID owner = chooseNextHolder(null, online, randomOf(server));
			if (owner == null) {
				continue;
			}
			state.perkOwners.put(perkId, owner);
			manager.setDirty();
			announceOwner(server, team, perk, server.getPlayerList().getPlayer(owner));
		}
	}

	/**
	 * 이 증강이 <b>주인 한 명</b>을 필요로 하는가.
	 *
	 * <p>증강 id 를 하나도 적지 않는다. {@link OwnerBoundEffect} 표지를 단 효과가 하나라도 있으면
	 * 참이다. 새 효과 타입이 같은 규칙을 쓰게 되면 표지만 달면 여기는 손대지 않아도 된다.
	 *
	 * <p>{@link HolderEffect} 는 보지 않는다. 그쪽은 {@link #reconcileFixed} 가 맡는다.
	 */
	public static boolean needsOwner(@Nullable Perk perk) {
		if (perk == null) {
			return false;
		}
		for (PerkEffect effect : perk.effects()) {
			if (effect instanceof OwnerBoundEffect) {
				return true;
			}
		}
		return false;
	}

	/** 「이 증강은 OOO님에게 걸립니다」를 팀 전원의 액션바에 띄운다. */
	private static void announceOwner(MinecraftServer server, ShareTeam team, Perk perk,
			@Nullable ServerPlayer owner) {
		if (owner == null) {
			return;
		}
		Component message = Component.literal(
				"[증강] " + perk.name() + ": " + owner.getPlainTextName() + "님에게 걸립니다.");
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				TitleMessenger.showActionBar(online, message);
			}
		}
	}

	/**
	 * 모드가 지난번과 달라졌으면 <b>접속 중인 팀원 전원</b>을 새 모드로 다시 맞춘다.
	 *
	 * <p>모드가 바뀌면 누가 보유자인지와 무관하게 팀 전원이 받을 묶음이 달라질 수 있다
	 * ({@link HolderMode#EVERYONE} 이 그렇다). 그래서 보유자 둘만 손보는 {@link #assign} 으로는
	 * 모자라고, 여기서 한 번에 훑는다.
	 *
	 * <p>이전 모드의 효과를 실제로 걷어내는 일은 {@link HolderEffect#applyAs} 가 한다. 사람마다
	 * 마지막에 붙여 준 묶음을 기억하고 있어서, 바뀐 사람만 정확히 그 묶음만 뗀다. 여기서
	 * 「전부 걷어내고 다시 붙인다」로 하면 켜진 적도 없는 묶음까지 지워 포션 효과가 날아간다.
	 *
	 * <p>모드가 그대로면 아무 일도 하지 않는다. 보통의 틱에는 이 검사 한 번으로 끝난다.
	 */
	private static void reconcileMode(MinecraftServer server, ShareTeam team, TeamState state,
			Owned owned, Holding holding) {
		HolderMode mode = modeOf(state, owned.perk().id());
		if (mode == holding.mode) {
			return;
		}
		holding.mode = mode;
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				owned.effect().applyAs(online, member.equals(holding.holder), mode);
			}
		}
	}

	/** 이 팀의 보유자가 아직 유효한지 보고, 아니면 넘기거나 새로 뽑는다. */
	private static void reconcile(MinecraftServer server, ShareTeam team, TeamState state,
			Owned owned) {
		HolderEffect effect = owned.effect();
		Holding holding = HOLDINGS.computeIfAbsent(
				new Key(effect, team.teamId()), ignored -> new Holding());
		reconcileMode(server, team, state, owned, holding);
		List<UUID> online = onlineMembers(server, team);

		if (online.isEmpty()) {
			// 아무도 없다. 보유자를 비워 두고, 누군가 돌아오면 그때 새로 뽑는다.
			if (holding.holder != null) {
				holding.holder = null;
				holding.since = now;
			}
			return;
		}
		if (holding.holder == null) {
			assign(server, team, owned, holding, chooseNextHolder(null, online, randomOf(server)),
					null, false);
			return;
		}
		if (!online.contains(holding.holder)) {
			// 접속을 끊었다. 곧바로 넘긴다. 이미 자리를 뜬 사람에게 on_pass 를 얹지는 않는다.
			UUID previous = holding.holder;
			assign(server, team, owned, holding,
					chooseNextHolder(previous, online, randomOf(server)), previous, false);
			return;
		}
		if (!rotationDue(holding.since, now, effect.rotateTicks())) {
			return;
		}
		UUID previous = holding.holder;
		UUID next = chooseNextHolder(previous, online, randomOf(server));
		if (next == null || next.equals(previous)) {
			// 혼자인 팀이라 넘길 곳이 없다. 시계만 다시 감는다. 실제로 넘어가지 않았으므로
			// on_pass 도 걸지 않는다.
			holding.since = now;
			return;
		}
		assign(server, team, owned, holding, next, previous, true);
	}

	/**
	 * 보유자를 바꾸고 두 사람의 효과를 갈아 끼운다.
	 *
	 * <p>순서가 중요하다. 이전 보유자에게서 {@code on_holder} 를 먼저 걷어내고
	 * {@code on_others} 를 붙인 다음, 새 보유자를 반대로 바꾸고, 마지막에 {@code on_pass} 를
	 * 얹는다. 걷어내기를 빠뜨리면 속성 수정자가 영구히 남아 팀이 망가지고, {@code on_pass} 를
	 * 먼저 얹으면 갈아 끼우는 {@code remove} 가 그것을 도로 걷어낸다.
	 *
	 * @param grantPass 실제로 다른 사람에게 넘어갔을 때만 참. 이때만 직전 보유자가 {@code on_pass} 를 받는다
	 */
	private static void assign(MinecraftServer server, ShareTeam team, Owned owned, Holding holding,
			@Nullable UUID next, @Nullable UUID previous, boolean grantPass) {
		holding.holder = next;
		holding.since = now;

		HolderEffect effect = owned.effect();
		ServerPlayer previousPlayer = previous == null ? null : server.getPlayerList().getPlayer(previous);
		ServerPlayer nextPlayer = next == null ? null : server.getPlayerList().getPlayer(next);

		if (previousPlayer != null && !previousPlayer.getUUID().equals(next)) {
			effect.applyAs(previousPlayer, false, holding.mode);
		}
		if (nextPlayer != null) {
			effect.applyAs(nextPlayer, true, holding.mode);
		}
		if (grantPass && previousPlayer != null && !previousPlayer.getUUID().equals(next)) {
			effect.grantPassEffects(previousPlayer);
		}
		if (holding.mode == HolderMode.EVERYONE) {
			// 전원이 같은 효과를 받는 동안에는 「누가 보유자인가」가 아무 뜻도 없다. 그런데도
			// 알리면 「보유자가 접속을 끊어 …」 같은 문구가 버프는 그대로인 채로 뜬다.
			return;
		}
		announce(server, team, owned, nextPlayer);
	}

	/**
	 * 보유자가 바뀌었음을 팀 전원에게 알린다.
	 *
	 * <p>액션바만 쓴다.
	 */
	private static void announce(MinecraftServer server, ShareTeam team, Owned owned,
			@Nullable ServerPlayer holder) {
		String name = holder == null ? null : holder.getPlainTextName();
		String perkName = owned.perk().name();
		String text;
		if (name != null) {
			text = "[증강] " + perkName + ": 이제 " + name + "님이 보유자입니다.";
		} else if (owned.effect().fixedToOwner()) {
			// 고정 보유자가 비었다는 것은 주인이 접속을 끊었다는 뜻이다.
			text = "[증강] " + perkName + ": 보유자가 접속을 끊어 돌아올 때까지 보유자가 없습니다.";
		} else {
			text = "[증강] " + perkName + ": 보유자가 없습니다.";
		}
		Component message = Component.literal(text);
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null) {
				TitleMessenger.showActionBar(online, message);
			}
		}
	}

	// ------------------------------------------------------------------ 사건

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DAMAGE} 에 붙는 지점.
	 *
	 * <p>{@code pass_on_hurt} 가 켜진 증강에서 보유자가 피해를 받으면 버프를 다른 팀원에게
	 * 넘긴다. 피해가 들어가는 모든 자리를 지나므로 어떤 예외도 밖으로 내보내지 않고, 보유자가
	 * 하나도 없으면 첫 몇 줄에서 되돌아 나간다.
	 *
	 * <p>{@code damageTaken} 은 {@link SpreadDamageManager#hurtTaken} 으로 고쳐 읽는다. 「완충」을 가진
	 * 팀에서는 처음 맞은 순간 피해가 0 으로 미뤄지고 그 뒤 몫이 1초마다 들어와, 그대로 믿으면 맞은
	 * 순간엔 안 넘어가고 몫이 올 때마다 넘어갔다(2026-10-06 검토에서 확정된 문제). 지금은
	 * 몫이면 건너뛰고 미룬 첫 피해면 미룬 양으로 세어, 맞은 그 순간에 한 번 넘어간다.
	 */
	public static void onDamage(LivingEntity victim, DamageSource source,
			float baseDamageTaken, float damageTaken, boolean blocked) {
		try {
			passOnHurt(victim, SpreadDamageManager.hurtTaken(victim, damageTaken), blocked);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	private static void passOnHurt(LivingEntity victim, float damageTaken, boolean blocked) {
		if (blocked || !(damageTaken > 0.0F) || HOLDINGS.isEmpty()
				|| !(victim instanceof ServerPlayer hurt)) {
			return;
		}
		// 강제 선택 중에는 무적이라 여기까지 오지 않지만, 다른 경로로 들어와도 순환은 멈춘다.
		if (PerkChoiceSession.isActive()) {
			return;
		}
		MinecraftServer server = hurt.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(hurt.getUUID());
		TeamState state = manager.stateOf(hurt.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return;
		}

		for (Owned owned : holdersOf(state)) {
			HolderEffect effect = owned.effect();
			// 고정 보유자는 맞아도 넘어가지 않는다. 「고정」의 뜻이 그것이다.
			if (effect.fixedToOwner() || !effect.passOnHurt()) {
				continue;
			}
			Holding holding = HOLDINGS.get(new Key(effect, team.teamId()));
			if (holding == null || !hurt.getUUID().equals(holding.holder)) {
				continue;
			}
			if (!holdSatisfied(holding.since, now, effect.minHoldTicks())) {
				// 받자마자 넘어가지 않게 하는 장치다. 아직 이르면 그대로 둔다.
				continue;
			}
			UUID next = chooseNextHolder(holding.holder, onlineMembers(server, team), randomOf(server));
			if (next == null || next.equals(holding.holder)) {
				// 혼자인 팀이라 넘길 곳이 없다. 버프도 디버프도 움직이지 않는다.
				continue;
			}
			assign(server, team, owned, holding, next, holding.holder, true);
		}
	}

	/**
	 * {@code ServerPlayerEvents.LEAVE} 에 붙는 지점.
	 *
	 * <p>주기 점검이 반 초 뒤에 어차피 알아채지만, 그 반 초 동안 아무도 버프를 들고 있지 않은
	 * 상태가 된다. 여기서 즉시 넘긴다.
	 */
	public static void onPlayerLeave(@Nullable ServerPlayer player) {
		try {
			release(player);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DEATH} 에 붙는 지점.
	 *
	 * <p>죽은 사람은 아직 접속해 있으므로 주기 점검이 알아채지 못한다. 여기서 명시적으로 뺀다.
	 */
	public static void onDeath(LivingEntity entity, DamageSource source) {
		try {
			if (entity instanceof ServerPlayer player) {
				release(player);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * 이 사람이 들고 있던 보유자 자리를 다른 팀원에게 넘긴다.
	 *
	 * <p>{@code on_pass} 는 걸지 않는다. 접속을 끊었으면 걸어 줄 대상이 없고, 죽었다면 이미
	 * 벌을 받은 셈이라 디버프를 더 얹을 이유가 없다. 팀원이 이 사람뿐이면 보유자는 비워진다.
	 *
	 * <p>{@code fixed_to_owner} 인 효과는 여기서 건드리지 않는다. 죽은 것뿐이면 주인은 여전히
	 * 주인이고, 접속을 끊었다면 {@link #reconcileFixed} 가 반 초 안에 보유자를 비운다.
	 */
	private static void release(@Nullable ServerPlayer player) {
		if (player == null || HOLDINGS.isEmpty()) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		TeamState state = manager.stateOf(player.getUUID());
		if (team == null || state == null || state.ownedPerks.isEmpty()) {
			return;
		}

		UUID leaving = player.getUUID();
		List<UUID> candidates = onlineMembers(server, team);
		candidates.remove(leaving);
		for (Owned owned : holdersOf(state)) {
			Holding holding = HOLDINGS.get(new Key(owned.effect(), team.teamId()));
			if (holding == null || !leaving.equals(holding.holder)) {
				continue;
			}
			if (owned.effect().fixedToOwner()) {
				// 고정 보유자는 여기서 아무것도 하지 않는다. 죽은 것뿐이면 주인은 그대로
				// 주인이고, 접속을 끊었다면 반 초 안에 reconcileFixed 가 보유자를 비운다.
				// 여기서 손대면 죽을 때마다 보유자가 잠깐 사라졌다 돌아와 알림만 두 줄 남는다.
				continue;
			}
			UUID next = chooseNextHolder(leaving, candidates, randomOf(server));
			if (next == null || next.equals(leaving)) {
				// 넘길 팀원이 없다. 그대로 둔다. 팀원이 한 명뿐인 팀에서는 이게 정답이고,
				// 접속 중인 팀원이 아예 없어지면 주기 점검이 보유자를 비운다.
				continue;
			}
			assign(server, team, owned, holding, next, leaving, false);
		}
	}

	// ------------------------------------------------------------------ 정리

	/**
	 * 서버가 멈출 때 보유자를 모두 비운다.
	 *
	 * <p>보유자는 저장되지 않는 런타임 값이므로, 남겨 두면 다음 월드에 이전 회차의 보유자가
	 * 그대로 딸려 들어간다. {@link PeriodicPerkManager#reset} 과 같은 자리에서 불린다.
	 *
	 * <p>효과가 사람마다 기억해 둔 묶음도 함께 버린다. 남겨 두면 다음 회차에서 「이미 그 묶음이
	 * 붙어 있다」고 잘못 믿어 걷어내기를 건너뛴다. {@code ConditionalPerkManager} 가
	 * {@code forgetAll} 을 부르는 것과 같은 이유다.
	 *
	 * <p>모드 판정기는 지우지 않는다. 모드 초기화 때 한 번 꽂는 배선이라, 월드를 바꿀 때마다
	 * 지우면 두 번째 월드부터 스위치가 죽는다.
	 */
	public static void reset() {
		for (Key key : HOLDINGS.keySet()) {
			key.effect().forgetAll();
		}
		HOLDINGS.clear();
		now = 0;
		checkCounter = 0;
		cleanupCounter = 0;
		warned = false;
	}

	/** 사라진 팀의 보유자 기록을 버린다. 팀이 해체돼도 자리가 남아 있지 않게 한다. */
	private static void forgetStale(TeamManager manager) {
		HOLDINGS.keySet().removeIf(key -> {
			TeamState state = manager.stateByTeamId(key.teamId());
			return state == null || !state.perksEnabled || state.ownedPerks.isEmpty();
		});
	}

	// ------------------------------------------------------------------ 도우미

	/** 이 팀이 보유한 증강의 최상위 {@code holder} 효과들. */
	private static List<Owned> holdersOf(TeamState state) {
		List<Owned> found = null;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof HolderEffect holder) {
					if (found == null) {
						found = new ArrayList<>(2);
					}
					found.add(new Owned(perk, holder));
				}
			}
		}
		return found == null ? List.of() : found;
	}

	/** 접속 중인 팀원. 부르는 쪽에서 걸러 내므로 고칠 수 있는 목록으로 돌려준다. */
	private static List<UUID> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<UUID> online = new ArrayList<>(team.size());
		for (UUID member : team.members()) {
			if (server.getPlayerList().getPlayer(member) != null) {
				online.add(member);
			}
		}
		return online;
	}

	/** 월드의 난수를 {@link #chooseNextHolder} 가 쓰는 모양으로 감싼다. */
	private static IntUnaryOperator randomOf(MinecraftServer server) {
		RandomSource random = server.overworld().getRandom();
		return bound -> bound <= 0 ? 0 : random.nextInt(bound);
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"보유자형 증강을 처리하지 못해 이번에는 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}

	// ------------------------------------------------------------------ 테스트 지원

	/** 테스트가 보유자를 정해 두고 배율이나 조회를 확인할 때 쓴다. */
	static void setHolderForTesting(HolderEffect effect, UUID teamId, @Nullable UUID holder) {
		Holding holding = HOLDINGS.computeIfAbsent(new Key(effect, teamId), ignored -> new Holding());
		holding.holder = holder;
		holding.since = now;
	}

	/** 테스트가 시각을 정해 두고 순환·최소 유지 판정을 확인할 때 쓴다. */
	static void setCurrentTickForTesting(long time) {
		now = time;
	}
}
