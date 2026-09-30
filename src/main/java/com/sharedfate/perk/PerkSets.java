package com.sharedfate.perk;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 세트 판정. <b>순수 계산만 있다.</b>
 *
 * <p>보유 증강 목록과 세트 정의를 인자로 받아 「유형별로 몇 개인가」·「어느 단계가 켜졌는가」·
 * 「켜진 단계의 효과는 무엇인가」를 돌려준다. {@link PerkRegistry}·{@link PerkSetRegistry} 같은
 * 전역 상태에는 손을 뻗지 않으므로 살아 있는 서버 없이 그대로 시험할 수 있다.
 *
 * <h2>세트는 증강이 아니다</h2>
 * <p>세트 보너스는 {@code TeamState.ownedPerks} 에 <b>절대 들어가지 않는다.</b> 「환골탈태」
 * ({@link com.sharedfate.perk.effect.RarityRerollEffect})가 그 목록의 크기를 그대로 세어 골드로
 * 갈아 끼우기 때문에, 세트를 목록에 넣으면 개수가 부풀고 갈아엎을 때 세트가 지워진다.
 * 세트는 언제나 {@code ownedPerks} 를 다시 세어 만드는 <b>파생 상태</b>이고 저장하지도 않는다.
 *
 * <p>「요행」·「숨은 재능」·「하늘의 은총」이 덤으로 준 증강에는 주인이 없다(설계 의도).
 * 주인 기록으로 세면 그것들이 세트에서 통째로 빠진다.
 *
 * <h2>단계는 누적이다</h2>
 * <p>채굴을 4개 모으면 2·3·4 단계가 <b>전부</b> 켜진다. 가장 높은 단계 하나만 켜지는 것이 아니다.
 */
public final class PerkSets {

	/**
	 * 세트 한 단계.
	 *
	 * <p>{@code sharedfate-sets.json} 의 {@code tiers} 항목 하나에 그대로 대응한다.
	 *
	 * @param type        어느 유형의 세트인가
	 * @param count       이 단계가 켜지는 데 필요한 같은 유형 증강의 개수
	 * @param name        이 단계를 부르는 이름. 따로 붙이지 않으면 <b>「보급 2」처럼 유형
	 *                    이름과 열리는 개수</b>가 된다 — 기본 정의는 전부 그 형태다
	 * @param description 화면에 보이는 설명
	 * @param effects     켜져 있는 동안 붙는 효과들. <b>비어 있을 수 있다</b> —
	 *                    보상이 아직 만들어지지 않은 자리라는 뜻이다({@link #isPlaceholder})
	 */
	public record Tier(PerkSetType type, int count, String name, String description,
			List<PerkEffect> effects) {

		public Tier {
			effects = List.copyOf(effects);
		}

		/**
		 * 아직 아무 효과도 붙지 않은 자리인가.
		 *
		 * <p>설명만 정해 두고 값을 나중에 채우는 단계가 있다. 화면은 이런 단계를
		 * 「예정」으로 흐리게 보여 주면 된다.
		 */
		public boolean isPlaceholder() {
			return effects.isEmpty();
		}
	}

	/**
	 * 화면에 그릴 유형 하나의 상태.
	 *
	 * @param type        유형
	 * @param owned       지금 가지고 있는 그 유형 증강의 개수
	 * @param nextCount   다음 단계가 켜지는 데 필요한 개수. 더 켤 것이 없으면 0.
	 *                    자세한 규칙은 {@link #statuses} 에 적어 뒀다
	 * @param activeTiers 지금 켜져 있는 단계들. 개수가 적은 것부터다. 하나도 없으면 빈 목록
	 */
	public record Status(PerkSetType type, int owned, int nextCount, List<Tier> activeTiers) {

		public Status {
			activeTiers = List.copyOf(activeTiers);
		}

		/** 이 유형의 세트가 하나라도 켜져 있는가. */
		public boolean isActive() {
			return !activeTiers.isEmpty();
		}
	}

	private PerkSets() {
	}

	// ------------------------------------------------------------------ 유형별 개수

	/**
	 * 보유 증강을 유형별로 센다.
	 *
	 * <p>한 증강이 유형을 <b>둘 이상</b> 가질 수 있고, 그때는 <b>양쪽에 다 세어진다.</b>
	 * 「원정 준비물」은 보급이자 도박이고 「피의 대가」는 화력이자 회복이다.
	 *
	 * <p>돌려주는 표에는 <b>유형 열네 개가 모두</b> 들어 있다. 하나도 없는 유형은 0 이다.
	 *
	 * @param ownedPerkIds {@code TeamState.ownedPerks} 그대로. null 이면 전부 0
	 * @param lookup       id 로 증강을 찾는 수단. 보통 {@code id -> PerkRegistry.byId(id).orElse(null)}
	 *                     을 넘긴다. 찾지 못한 id 는 조용히 건너뛴다 — 정의 파일을 손으로 고칠 수
	 *                     있는 이상 저장에만 남은 id 는 언제든 생긴다
	 */
	public static Map<PerkSetType, Integer> countByType(@Nullable Collection<String> ownedPerkIds,
			@Nullable Function<String, Perk> lookup) {
		Map<PerkSetType, Integer> counts = new EnumMap<>(PerkSetType.class);
		for (PerkSetType type : PerkSetType.values()) {
			counts.put(type, 0);
		}
		if (ownedPerkIds == null || ownedPerkIds.isEmpty() || lookup == null) {
			return counts;
		}
		for (String perkId : ownedPerkIds) {
			if (perkId == null || perkId.isBlank()) {
				continue;
			}
			Perk perk = lookup.apply(perkId);
			if (perk == null) {
				continue;
			}
			for (PerkSetType type : perk.setTypes()) {
				if (type != null) {
					counts.merge(type, 1, Integer::sum);
				}
			}
		}
		return counts;
	}

	// ------------------------------------------------------------------ 켜진 단계

	/**
	 * 지금 켜져 있는 단계 전부.
	 *
	 * <p><b>누적이다.</b> 가진 개수가 어떤 단계의 {@code count} 이상이면 그 단계는 켜진다.
	 * 채굴 4개면 2·3·4 가 모두 들어간다.
	 *
	 * <p>순서는 {@link PerkSetType} 선언 순서, 그 안에서는 {@code count} 오름차순이다.
	 * 화면과 효과 적용이 매번 같은 순서를 보아야 값이 흔들리지 않는다.
	 */
	public static List<Tier> activeTiers(@Nullable Map<PerkSetType, Integer> counts,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		if (counts == null || defined == null || defined.isEmpty()) {
			return List.of();
		}
		List<Tier> active = new ArrayList<>();
		for (PerkSetType type : PerkSetType.values()) {
			int owned = counts.getOrDefault(type, 0);
			if (owned <= 0) {
				continue;
			}
			for (Tier tier : sortedTiers(defined.get(type))) {
				if (owned >= tier.count()) {
					active.add(tier);
				}
			}
		}
		return List.copyOf(active);
	}

	/** 보유 목록에서 곧바로 켜진 단계를 낸다. */
	public static List<Tier> activeTiers(@Nullable Collection<String> ownedPerkIds,
			@Nullable Function<String, Perk> lookup,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		return activeTiers(countByType(ownedPerkIds, lookup), defined);
	}

	/**
	 * 켜진 모든 단계의 효과를 <b>하나의 목록</b>으로 펼친다.
	 *
	 * <p>효과를 붙이는 쪽은 어느 단계에서 온 것인지 알 필요가 없다. 보유 증강의 효과를 펼친
	 * 목록 뒤에 그대로 이어 붙이면 된다.
	 */
	public static List<PerkEffect> activeEffects(@Nullable Map<PerkSetType, Integer> counts,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		List<Tier> tiers = activeTiers(counts, defined);
		if (tiers.isEmpty()) {
			return List.of();
		}
		List<PerkEffect> effects = new ArrayList<>();
		for (Tier tier : tiers) {
			effects.addAll(tier.effects());
		}
		return List.copyOf(effects);
	}

	/** 보유 목록에서 곧바로 효과 목록을 낸다. */
	public static List<PerkEffect> activeEffects(@Nullable Collection<String> ownedPerkIds,
			@Nullable Function<String, Perk> lookup,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		return activeEffects(countByType(ownedPerkIds, lookup), defined);
	}

	// ------------------------------------------------------------------ 화면용

	/**
	 * 유형 열네 개의 상태를 한 번에 낸다. HUD·팀 화면·툴팁이 쓸 형태다.
	 *
	 * <p>{@code nextCount} 는 「앞으로 몇 개를 더 모으면 무언가가 켜지는가」다. 두 곳에서 고른다.
	 *
	 * <ul>
	 *   <li>아직 안 켜진 단계 중 가장 낮은 {@code count}</li>
	 *   <li>{@link PerkSetType#threshold()} — <b>단계가 하나도 정의되지 않은 유형</b>에서만
	 *       쓰는 대신할 값이다. 보상이 아직 없는 유형도 「무기 1/2」로 진행도를 보여 줄 수 있어야
	 *       하기 때문이다. 임계값 숫자를 여기 직접 적지 않고 반드시 이 메서드를 부른다 — 그 값은
	 *       시뮬레이션으로 정해져 앞으로도 자주 바뀐다.</li>
	 * </ul>
	 *
	 * <p>단계가 하나라도 정의된 유형에서는 <b>임계값을 섞지 않는다.</b> 기동은 단계가 3 하나뿐인데
	 * 임계값이 2 라, 섞으면 하나 가진 사람에게 「기동 1/2」가 떠 <b>2 에서 무언가 켜진다</b>고
	 * 읽힌다. 실제로 둘째를 모아도 아무 일이 없다. 두 값은 뜻이 다르다 — 임계값은 「이 유형을
	 * 노렸을 때 몇 개면 세트라 부를 만한가」이고, 화면의 분모는 「다음에 무엇이 켜지는가」다.
	 *
	 * <p>후보가 하나도 없으면(전부 켰으면) 0 이다.
	 */
	public static List<Status> statuses(@Nullable Map<PerkSetType, Integer> counts,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		List<Status> statuses = new ArrayList<>(PerkSetType.values().length);
		for (PerkSetType type : PerkSetType.values()) {
			int owned = counts == null ? 0 : counts.getOrDefault(type, 0);
			List<Tier> tiers = defined == null ? List.of() : sortedTiers(defined.get(type));

			List<Tier> active = new ArrayList<>();
			int next = 0;
			for (Tier tier : tiers) {
				if (owned >= tier.count()) {
					active.add(tier);
				} else if (next == 0 || tier.count() < next) {
					next = tier.count();
				}
			}
			// 단계가 하나라도 있으면 그 개수만 분모가 된다. 임계값은 단계가 없는 유형을 위한
			// 대신할 값이다.
			if (tiers.isEmpty() && owned < type.threshold()) {
				next = type.threshold();
			}
			statuses.add(new Status(type, owned, next, active));
		}
		return List.copyOf(statuses);
	}

	/** 보유 목록에서 곧바로 화면용 상태를 낸다. */
	public static List<Status> statuses(@Nullable Collection<String> ownedPerkIds,
			@Nullable Function<String, Perk> lookup,
			@Nullable Map<PerkSetType, List<Tier>> defined) {
		return statuses(countByType(ownedPerkIds, lookup), defined);
	}

	// ------------------------------------------------------------------ 공통

	/**
	 * 한 유형의 단계들을 {@code count} 오름차순으로 정렬한다.
	 *
	 * <p>이미 정렬돼 있으면 새로 만들지 않는다.
	 */
	private static List<Tier> sortedTiers(@Nullable List<Tier> tiers) {
		if (tiers == null || tiers.isEmpty()) {
			return List.of();
		}
		boolean sorted = true;
		for (int i = 1; i < tiers.size(); i++) {
			if (tiers.get(i - 1).count() > tiers.get(i).count()) {
				sorted = false;
				break;
			}
		}
		if (sorted) {
			return tiers;
		}
		List<Tier> copy = new ArrayList<>(tiers);
		copy.sort((first, second) -> Integer.compare(first.count(), second.count()));
		return copy;
	}
}
