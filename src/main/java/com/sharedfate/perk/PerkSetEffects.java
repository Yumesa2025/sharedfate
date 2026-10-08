package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 세트 효과의 실행부.
 *
 * <p>{@link PerkSets} 는 순수 계산만, {@link PerkSetRegistry} 는 정의 보관만 한다. 그 둘과
 * 살아 있는 게임 상태({@code TeamState}·{@code ServerPlayer})를 잇는 자리가 여기다.
 *
 * <h2>세트를 소비하는 곳이 알아야 할 것은 한 줄뿐이다</h2>
 * <p>보유 증강의 효과를 훑는 코드는 이 저장소 곳곳에 있고 모양이 전부 같다.
 *
 * <pre>{@code
 * for (String perkId : state.ownedPerks) {
 *     Perk perk = PerkRegistry.byId(perkId).orElse(null);
 *     ...
 *     for (PerkEffect effect : perk.effects()) { ... }
 * }
 * // 그 뒤에 이 한 줄을 잇는다
 * for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) { ... }
 * }</pre>
 *
 * <p>{@link #activeEffectsOf} 는 켜진 모든 단계의 효과를 한 목록으로 펼쳐 준다. 어느 단계에서
 * 왔는지는 소비하는 쪽이 알 필요가 없다. 세트 정의가 비어 있으면 곧바로 빈 목록이라, 세트를
 * 쓰지 않는 서버의 뜨거운 경로에는 사실상 아무 부담도 얹히지 않는다.
 *
 * <h2>붙였다 떼는 효과만 따로 기억한다</h2>
 * <p>속성·최대 체력처럼 <b>플레이어에게 붙여 두는</b> 효과는 세트가 꺼질 때 누군가 걷어내야
 * 한다. 그런데 세트는 저장하지 않는 파생 상태라 「방금 전까지 무엇이 켜져 있었는가」를 아는
 * 곳이 없다. 그래서 이 클래스가 사람마다 <b>마지막으로 붙여 준 단계 목록</b>을 들고 있다가,
 * 다시 볼 때 빠진 단계만 {@link PerkEffect#remove} 한다.
 *
 * <p><b>「지금 안 켜진 단계는 전부 걷어낸다」로 하면 안 된다.</b> {@code on_swap} 처럼
 * {@code remove} 가 실제로 무언가를 지우는 효과가 있어서, 켜진 적도 없는 단계를 걷어내면
 * 포션으로 얻은 신속까지 사라진다. 빠진 것만 걷어내는 이 방식은 그런 사고가 날 수 없다.
 *
 * <p>기억은 메모리에만 둔다. 저장하지 않으므로 서버를 껐다 켜면 비어 있고, 그때는 어차피
 * 속성 수정자도 함께 사라진 뒤다({@code addTransientModifier}).
 *
 * <h2>{@link #refresh} 는 반드시 멱등이어야 한다</h2>
 * <p>접속·부활·상태이상 재적용마다 불린다. 두 번 불러도 값이 두 배가 되면 안 된다.
 * 켜져 있는 단계는 매번 {@link PerkEffect#apply} 를 다시 부르는데, 이 저장소의 효과들은
 * 전부 「지금 있어야 할 값으로 다시 맞춘다」로 만들어져 있어 몇 번을 불러도 결과가 같다.
 */
public final class PerkSetEffects {
	/**
	 * 사람마다 마지막으로 붙여 준 단계들.
	 *
	 * <p>서버 스레드에서만 오간다. 그래도 접속 종료 처리와 겹칠 수 있어 잠그고 쓴다.
	 */
	private static final Map<UUID, List<PerkSets.Tier>> APPLIED = new HashMap<>();

	/** 같은 경고로 로그를 채우지 않기 위한 표시. */
	private static volatile boolean warned;

	private PerkSetEffects() {
	}

	// ------------------------------------------------------------------ 조회

	/**
	 * 이 팀에 지금 켜져 있는 세트 단계들. 없으면 빈 목록.
	 *
	 * <p>판정은 언제나 {@code ownedPerks} 를 다시 세어 만든다. 저장된 값을 읽지 않으므로
	 * 「환골탈태」가 목록을 통째로 갈아엎어도 그 즉시 답이 따라온다.
	 */
	public static List<PerkSets.Tier> activeTiersOf(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty() || PerkSetRegistry.isEmpty()) {
			return List.of();
		}
		return PerkSets.activeTiers(state.ownedPerks, PerkSetEffects::lookup, PerkSetRegistry.all());
	}

	/**
	 * 이 팀에 지금 켜져 있는 세트 효과 전부. 없으면 빈 목록.
	 *
	 * <p><b>보유 증강의 효과를 훑는 자리마다 이것을 이어 붙인다.</b> 그러지 않으면 그 자리가
	 * 소비하는 효과 형은 세트에서 아무 일도 하지 않는다 — 빌드도 통과하고 로그도 없다.
	 */
	public static List<PerkEffect> activeEffectsOf(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty() || PerkSetRegistry.isEmpty()) {
			return List.of();
		}
		return PerkSets.activeEffects(state.ownedPerks, PerkSetEffects::lookup, PerkSetRegistry.all());
	}

	/**
	 * 화면에 그릴 유형 열네 개의 상태.
	 *
	 * <p>세트 정의가 비어 있어도 「가진 개수 / 임계값」은 나온다. 진행도만 보여 주는 화면은
	 * 정의 파일이 없어도 그대로 돈다.
	 */
	public static List<PerkSets.Status> statusesOf(@Nullable TeamState state) {
		return PerkSets.statuses(
				state == null ? List.of() : state.ownedPerks,
				PerkSetEffects::lookup,
				PerkSetRegistry.all());
	}

	// ------------------------------------------------------------------ 적용

	/**
	 * 한 사람에게 세트 효과를 지금 맞아야 할 모습으로 다시 맞춘다.
	 *
	 * <p>{@code PerkManager.refreshPlayer} 가 보유 증강 효과를 붙인 <b>바로 뒤</b>에 부른다.
	 * 빠진 단계를 먼저 걷어내고 켜진 단계를 붙인다. 몇 번을 불러도 결과가 같다.
	 */
	public static void refresh(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		apply(player, activeTiersOf(TeamLookup.stateOf(player.getUUID())));
	}

	/**
	 * 한 사람에게 붙여 둔 세트 효과를 전부 걷어낸다.
	 *
	 * <p>{@code PerkManager.setPerksEnabled(false)} 가 보유 증강 효과를 걷어내는 자리에서 함께
	 * 부른다. 이 사람에게 실제로 붙여 준 단계만 걷어내므로, 켜진 적 없는 단계의
	 * {@code remove} 가 엉뚱한 것을 지우는 일이 없다.
	 */
	public static void removeAll(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		apply(player, List.of());
	}

	/**
	 * 접속을 끊은 사람의 기억을 지운다.
	 *
	 * <p>부르지 않아도 값이 틀리지는 않는다. 다시 접속하면 속성 수정자가 이미 사라진 뒤이고
	 * {@link #refresh} 가 처음부터 다시 붙이기 때문이다. 다만 다시 오지 않는 사람의 기록이
	 * 쌓이면 그만큼 메모리가 샌다.
	 */
	public static void forget(@Nullable UUID playerId) {
		if (playerId == null) {
			return;
		}
		synchronized (APPLIED) {
			APPLIED.remove(playerId);
		}
	}

	/** 서버가 멈출 때나 시험에서 상태를 격리할 때 쓴다. */
	public static void reset() {
		synchronized (APPLIED) {
			APPLIED.clear();
		}
		warned = false;
	}

	/**
	 * 실제로 걷어내고 붙인다.
	 *
	 * <p>순서가 중요하다. <b>걷어내기가 먼저다.</b> 두 단계가 같은 속성을 건드릴 때 붙이기를
	 * 먼저 하면, 바로 뒤의 걷어내기가 방금 붙인 것을 지울 수 있다.
	 */
	private static void apply(ServerPlayer player, List<PerkSets.Tier> wanted) {
		UUID playerId = player.getUUID();
		List<PerkSets.Tier> previous;
		synchronized (APPLIED) {
			previous = wanted.isEmpty()
					? APPLIED.remove(playerId)
					: APPLIED.put(playerId, wanted);
		}
		if (previous != null) {
			for (PerkSets.Tier tier : previous) {
				if (wanted.contains(tier)) {
					continue;
				}
				for (PerkEffect effect : tier.effects()) {
					try {
						effect.remove(player);
					} catch (RuntimeException error) {
						warnOnce(tier, error);
					}
				}
			}
		}
		for (PerkSets.Tier tier : wanted) {
			for (PerkEffect effect : tier.effects()) {
				try {
					effect.apply(player);
				} catch (RuntimeException error) {
					warnOnce(tier, error);
				}
			}
		}
	}

	private static @Nullable Perk lookup(String perkId) {
		return PerkRegistry.byId(perkId).orElse(null);
	}

	private static void warnOnce(PerkSets.Tier tier, RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("세트 효과 '{} {}단계' 를 다루지 못했습니다. 이 경고는 한 번만 남습니다.",
				tier.type().displayName(), tier.count(), error);
	}
}
