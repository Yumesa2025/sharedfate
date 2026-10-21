package com.sharedfate.perk;

import java.util.List;
import java.util.Locale;

/**
 * 증강 등급.
 *
 * <p>구간마다 등급이 하나로 정해지고 그 등급 안에서만 후보 3개를 뽑는다. 한 라운드에
 * 여러 등급이 섞이지 않는다. 구간 → 등급 배정은 {@link PerkDraft#rarityFor} 가 맡는다.
 *
 * <h2>{@link #TRIAL} 은 등급이 아니라 「분류」다 (0.44.0-dev)</h2>
 * <p>드래곤 시련을 켠 팀만 여는 45·50 구간의 전용 풀 「드래곤전 대비」다. 실버·골드·프리즘의
 * 위아래가 아니라 <b>그 셋과 아예 섞이지 않는 따로 된 통</b>이라, 등급 확률표·프리즘 보정·실버
 * 차단·등급 다시 뽑기·세트 모으기 보정 어느 것도 이 통을 건드리지 않는다
 * ({@link PerkDraft#fallbackOrder} 가 서로를 끌어오지 않는 것이 그 담이다).
 *
 * <p>새 필드를 따로 두지 않고 이 열거에 넣은 까닭은 <b>모든 길이 이미 등급 하나로 갈라지기
 * 때문</b>이다. 추첨의 통 나누기({@code eligibleByRarity}) · 통신으로 내려가는 문자열
 * ({@code PerkOption.rarity}) · 화면의 색과 띠 · 「이 라운드는 무엇인가」({@code offerRarity})가
 * 전부 이 값 하나를 본다. 별도 필드로 두면 그 모든 자리에 「등급은 실버인데 사실은 시련 풀」
 * 이라는 두 번째 질문이 붙고, 하나라도 빠뜨리면 실버 차단이나 등급 다시 뽑기가 이 풀에 걸린다.
 * 열거에 넣으면 등급별로 갈라 쓰는 {@code switch} 가 컴파일 단계에서 빠진 자리를 알려 준다.
 */
public enum PerkRarity {
	SILVER("실버"),
	GOLD("골드"),
	PRISM("프리즘"),
	/** 드래곤 시련 팀의 45·50 구간 전용 분류. 등급이 아니다 — 클래스 설명. */
	TRIAL("드래곤전 대비");

	/** 진짜 등급 셋. 확률표·폴백·덤 지급처럼 「등급」만 다루는 자리가 이 목록을 쓴다. */
	public static final List<PerkRarity> GRADES = List.of(SILVER, GOLD, PRISM);

	private final String displayName;

	PerkRarity(String displayName) {
		this.displayName = displayName;
	}

	/**
	 * JSON의 rarity 문자열을 등급으로 바꾼다. 알 수 없는 값이면 null.
	 *
	 * <p>{@code common / rare / epic} 과 {@code platinum} 도 계속 받아준다.
	 */
	public static PerkRarity fromId(String id) {
		if (id == null) {
			return null;
		}
		String normalized = id.trim().toUpperCase(Locale.ROOT);
		switch (normalized) {
			case "COMMON":
				return SILVER;
			case "RARE":
				return GOLD;
			case "EPIC":
			case "PLATINUM":
				return PRISM;
			default:
				break;
		}
		try {
			return valueOf(normalized);
		} catch (IllegalArgumentException error) {
			return null;
		}
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** 화면과 채팅에 쓰는 한국어 이름. 실버 / 골드 / 프리즘 / 드래곤전 대비. */
	public String displayName() {
		return displayName;
	}

	/** 진짜 등급인가. {@link #TRIAL} 만 거짓이다. */
	public boolean isGrade() {
		return this != TRIAL;
	}

	/**
	 * 채팅에 「무슨 증강인가」를 적는 말. 등급이면 「실버 등급」, 분류면 「드래곤전 대비」.
	 *
	 * <p>「드래곤전 대비 등급」이라고 적으면 등급처럼 읽혀 이 분류를 따로 둔 뜻이 흐려진다.
	 */
	public String gradeLabel() {
		return isGrade() ? displayName + " 등급" : displayName;
	}
}
