package com.sharedfate.perk;

import java.util.Locale;

/**
 * 증강의 세트 유형.
 *
 * <p>증강 하나가 유형을 여러 개 가질 수 있고, 아무 유형에도 속하지 않는 증강도 있다.
 * 같은 유형을 {@link #threshold()} 개 이상 모으면 세트 효과가 열리는 것이 목표이며,
 * 이 클래스는 그 판정에 쓸 <b>이름과 임계값</b>만 들고 있다. 판정과 효과 적용은 하지 않는다.
 */
public enum PerkSetType {
	WEAPON("무기", 2),
	POWER("화력", 3),
	HUNT("사냥", 2),
	MINING("채굴", 3),
	SUPPLY("보급", 2),
	DEFENSE("방어", 2),
	SURVIVAL("생존", 2),
	RECOVERY("회복", 2),
	SWAP("교환", 2),
	GAMBLE("도박", 2),
	MOBILITY("기동", 2),
	BLESSING("가호", 2),
	/**
	 * 개척 — 어디로 갈지 알고, 가서 값싸게 사 오고, 가져온 것을 빨리 굽는다.
	 *
	 * <p>단계가 <b>2 하나뿐</b>이다. 3·4단계를 두지 않은 것은 <b>의도한 것</b>이다. 이 유형의
	 * 증강 넷은 전부 「길을 줄여 주는」 것이라 세기를 올릴 축이 없다 — 마을을 두 배로 잘
	 * 가리킬 수는 없다. 그래서 2단계 하나에 값을 몰아 두었다.
	 */
	PIONEER("개척", 2),
	/**
	 * 결속 — 팀원이 뭉치면 강해진다.
	 *
	 * <p>단계가 <b>3 하나뿐</b>이다. 기동·가호와 같은 모양이고, 그 하나가 <b>모든 거리를
	 * 1.5배로 늘리는 것</b> — 즉 이 유형의 제약 자체를 풀어 준다. 뭉치기 힘들어서 못 쓰던 것이
	 * 모을수록 흩어져도 되는 것으로 바뀐다.
	 */
	BOND("결속", 2);

	private final String displayName;
	private final int threshold;

	PerkSetType(String displayName, int threshold) {
		this.displayName = displayName;
		this.threshold = threshold;
	}

	/** JSON 의 {@code set_types} 문자열 하나를 유형으로 바꾼다. 알 수 없는 값이면 null. */
	public static PerkSetType fromId(String id) {
		if (id == null) {
			return null;
		}
		try {
			return valueOf(id.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException error) {
			return null;
		}
	}

	/** JSON 에 적는 문자열. 예: {@code mining}. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 화면과 채팅에 쓰는 한국어 이름. */
	public String displayName() {
		return displayName;
	}

	/**
	 * 세트 효과가 열리는 데 필요한 같은 유형 증강의 개수.
	 *
	 * <p>지금은 채굴·화력이 3, 나머지는 2다. 판정하는 쪽은 숫자를
	 * 직접 쓰지 말고 이 메서드를 부른다.
	 */
	public int threshold() {
		return threshold;
	}
}
