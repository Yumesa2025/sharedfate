package com.sharedfate.perk;

import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 증강 한 개의 정의.
 *
 * <p>중첩 개념은 없다. 한 번 고른 증강은 그 회차 동안 다시 후보로 나오지 않는다.
 * 예전 정의 파일에 남아 있는 {@code stackable}·{@code maxStacks} 는 조용히 무시한다.
 *
 * @param id          고유 식별자. 예: {@code sharedfate:tough_body}
 * @param name        화면에 보이는 이름
 * @param description 화면에 보이는 설명
 * @param rarity      등급
 * @param icon        선택 화면 카드에 그릴 아이템. 없으면 {@code null} 이고, 그때는
 *                    클라이언트가 등급별 기본 아이콘을 대신 쓴다
 * @param minLevel    이 증강이 후보로 나올 수 있는 최소 팀 공유 레벨({@code state.xpLevel}).
 *                    0(기본값)이면 제한이 없다. {@link PerkDraft} 의 추첨이 이 값을
 *                    구간과 비교해 거른다 — "특정 구간에서만 나오는 증강"이 필요할 때 이
 *                    필드 하나로 표현한다. 예: 프리즘 「환골탈태」는 30(15렙에는 안 나옴)
 * @param setTypes    이 증강이 속한 세트 유형들. 한 증강이 둘 이상을 가질 수 있고, 어디에도
 *                    속하지 않으면 빈 목록이다(무유형). 세트 판정은 여기 담긴 유형을 센다
 * @param effects     이 증강이 가진 효과들
 * @param requires    이 증강이 후보로 나오기 위해 팀이 갖춰야 하는 전제조건. 없으면
 *                    {@code null} 이고, 그때는 지금까지처럼 언제나 후보다.
 *                    {@link Requirement} 문서에 왜 필요한지 적어 뒀다.
 *                    <b>자리가 맨 뒤인 것은 뜻이 아니라 사정 때문이다</b> — 개념으로는
 *                    {@link #minLevel} 옆에 있어야 하지만, 그 자리에 끼우면 여덟 인자짜리
 *                    표준 생성자를 쓰는 기존 코드가 전부 깨진다
 * @param weight      후보 추첨 가중치(0.44.0-dev). 기본 {@value #DEFAULT_WEIGHT}. 같은 통 안에서
 *                    이 값에 비례해 뽑힌다 — 0.5 면 다른 것의 절반쯤만 나온다. 통 안의 가중치가
 *                    <b>모두 같으면</b> 예전 균등 추첨과 난수 소비까지 똑같다({@link PerkDraft}).
 *                    {@code requires} 와 같은 사정으로 맨 뒤에 붙였다
 */
public record Perk(
		String id,
		String name,
		String description,
		PerkRarity rarity,
		@Nullable Identifier icon,
		int minLevel,
		List<PerkSetType> setTypes,
		List<PerkEffect> effects,
		@Nullable Requirement requires,
		double weight) {

	/** 가중치를 적지 않은 증강의 값. */
	public static final double DEFAULT_WEIGHT = 1.0;
	/** 정의 파일이 적을 수 있는 가장 큰 가중치. 넘으면 그 증강을 버린다({@link PerkRegistry}). */
	public static final double MAX_WEIGHT = 100.0;

	/**
	 * 증강이 후보에 들기 위해 팀이 갖춰야 하는 조건.
	 *
	 * <p>정의 파일에는 증강의 <b>최상위 필드</b>로 {@code "requires": "position_swap"} 처럼 적는다.
	 * {@code min_level} 과 같은 자리이고, 걸러 내는 곳도 같다({@link PerkDraft}).
	 *
	 * <h2>왜 필요한가</h2>
	 * <p>교환 시점에 붙는 증강들(「본진이 바뀐다」·「본진이 바뀔까?」·「폭발 교환」·「정거장」)은 팀이 위치
	 * 교환을 껐으면({@code TeamState.positionSwapEnabled()} 이 거짓) 아무 일도 하지 않는다. 그런
	 * 팀에게도 후보로 뜨면 고를 수 있는 죽은 카드가 되므로, 조건이 갖춰진 팀에게만 보여 준다.
	 *
	 * <h2>모르는 값은 증강을 버린다</h2>
	 * <p>세트 유형({@code set_types})과 달리 오타를 조용히 넘기지 않는다. 유형은 덧붙임이라
	 * 틀려도 증강 자체는 제 일을 하지만, 전제조건은 <b>이 증강이 언제 나오는가</b>를 정하는
	 * 값이라 잘못 읽으면 나오지 말아야 할 자리에 나오거나 영영 안 나온다. 어느 쪽이든 조용히
	 * 지나가면 사람이 알아챌 방법이 없다. 실제로 버리는 자리는 {@link PerkRegistry} 다.
	 */
	public enum Requirement {
		/** 팀의 위치 교환이 켜져 있어야 한다. */
		POSITION_SWAP("position_swap");

		/**
		 * 「모든 전제조건이 갖춰졌다」. 전제조건을 따지지 않는 옛 경로가 이것을 넘긴다.
		 *
		 * <p>{@link PerkDraft} 의 옛 시그니처들이 쓰는 값이라, 팀 상태를 모르는 자리는 지금까지와
		 * 똑같이 아무것도 거르지 않는다.
		 */
		public static final Set<Requirement> ALL =
				Collections.unmodifiableSet(EnumSet.allOf(Requirement.class));

		/** 아무 전제조건도 갖춰지지 않은 팀. */
		public static final Set<Requirement> NONE = Set.of();

		private final String id;

		Requirement(String id) {
			this.id = id;
		}

		/** 정의 파일에 적는 문자열. */
		public String id() {
			return id;
		}

		/** JSON 의 {@code requires} 문자열에 맞는 값. 알 수 없으면 {@code null}. */
		public static @Nullable Requirement fromId(@Nullable String raw) {
			if (raw == null) {
				return null;
			}
			String normalized = raw.trim().toLowerCase(Locale.ROOT);
			for (Requirement requirement : values()) {
				if (requirement.id.equals(normalized)) {
					return requirement;
				}
			}
			return null;
		}
	}

	public Perk {
		setTypes = List.copyOf(setTypes);
		effects = List.copyOf(effects);
		// 정의 파일은 PerkRegistry 가 범위를 먼저 본다. 여기서는 코드로 직접 만든 증강이 추첨을
		// 깨뜨리지 않게만 접는다 — 0 이하·NaN 은 「한 번도 안 나옴」이 아니라 기본값으로 본다.
		if (!(weight > 0.0) || !Double.isFinite(weight)) {
			weight = DEFAULT_WEIGHT;
		}
	}

	/** 가중치를 적지 않으면 {@value #DEFAULT_WEIGHT} 이다. {@code weight} 가 생기기 전의 아홉 인자짜리 자리다. */
	public Perk(String id, String name, String description, PerkRarity rarity,
			@Nullable Identifier icon, int minLevel, List<PerkSetType> setTypes,
			List<PerkEffect> effects, @Nullable Requirement requires) {
		this(id, name, description, rarity, icon, minLevel, setTypes, effects, requires,
				DEFAULT_WEIGHT);
	}

	/** 아이콘·최소 레벨을 따로 정하지 않은 무유형 증강. 화면은 등급별 기본 아이콘을 쓰고 언제나 나올 수 있다. */
	public Perk(String id, String name, String description, PerkRarity rarity,
			List<PerkEffect> effects) {
		this(id, name, description, rarity, null, 0, List.of(), effects, null);
	}

	/** 최소 레벨은 따로 정하지 않고 아이콘만 지정한다. */
	public Perk(String id, String name, String description, PerkRarity rarity,
			@Nullable Identifier icon, List<PerkEffect> effects) {
		this(id, name, description, rarity, icon, 0, List.of(), effects, null);
	}

	/** 유형을 적지 않으면 무유형이다. */
	public Perk(String id, String name, String description, PerkRarity rarity,
			@Nullable Identifier icon, int minLevel, List<PerkEffect> effects) {
		this(id, name, description, rarity, icon, minLevel, List.of(), effects, null);
	}

	/** 전제조건을 적지 않으면 언제나 후보다. {@code requires} 가 생기기 전의 여덟 인자짜리 자리다. */
	public Perk(String id, String name, String description, PerkRarity rarity,
			@Nullable Identifier icon, int minLevel, List<PerkSetType> setTypes,
			List<PerkEffect> effects) {
		this(id, name, description, rarity, icon, minLevel, setTypes, effects, null);
	}

	/** 이 증강이 해당 세트 유형에 속하는지. */
	public boolean hasSetType(PerkSetType type) {
		return type != null && setTypes.contains(type);
	}

	/**
	 * 이 팀에게 이 증강을 보여도 되는가.
	 *
	 * <p>전제조건이 없는 증강은 언제나 참이다. {@code satisfied} 가 {@code null} 이면 아무것도
	 * 갖춰지지 않은 것으로 본다 — 모르는 것을 갖춘 것으로 치면 전제조건이 있으나 마나가 된다.
	 *
	 * @param satisfied 이 팀이 갖춘 전제조건들
	 */
	public boolean requirementMet(@Nullable Set<Requirement> satisfied) {
		return requires == null || (satisfied != null && satisfied.contains(requires));
	}
}
