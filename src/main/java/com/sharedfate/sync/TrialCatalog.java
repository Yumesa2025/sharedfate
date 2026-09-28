package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 시련 카드 목록.
 *
 * <h2>왜 아직 JSON 이 아닌가</h2>
 *
 * <p>증강처럼 JSON 으로 빼는 것이 목표다. 다만 카드 내용이 아직 정해지지 않은 지금 파싱·등록
 * 인프라를 먼저 만들면 <b>구조부터 굳는다.</b> 한 장을 끝까지 굴려 보고 구조가 맞는 것을
 * 확인한 뒤, 카드가 늘어나기 전에 옮긴다.
 *
 * <h2>카드는 위험과 보상 한 짝이다</h2>
 *
 * <p>증강이 「대가가 따르는 강화」였으니 시련은 그 대칭 — 「강화가 따르는 위험」이다. 셋 다
 * 나쁘기만 하면 매번 「가장 덜 나쁜 것」을 고르게 되고 선택이 단조로워진다.
 *
 * <h2>풀을 나누는 이유</h2>
 *
 * <p>입장 직후와 체력 30% 는 같은 무게일 수 없다. 트리거마다 풀을 따로 두면 <b>난이도 곡선</b>이
 * 생긴다. 카드 하나가 여러 풀에 속할 수 있으므로 「80·50·30 을 한 묶음으로」도, 「30% 에만 나오는
 * 카드」도 만들 수 있다.
 */
public final class TrialCatalog {

	/**
	 * 시련이 나오는 자리.
	 *
	 * <p>순서는 대체로 이 차례가 된다 — 크리스탈이 살아 있으면 드래곤 체력이 잘 안 깎이므로
	 * 크리스탈 쪽이 먼저 온다. 다만 순서를 코드가 강제하지는 않는다.
	 */
	public enum Trigger {
		/** 엔드에 들어서는 순간. */
		ENTRY("입장"),
		/** 엔드 크리스탈이 처음 깨졌을 때. */
		FIRST_CRYSTAL("첫 크리스탈"),
		/** 엔드 크리스탈이 모두 깨졌을 때. */
		ALL_CRYSTALS("크리스탈 전멸"),
		/** 드래곤 체력이 80% 아래로 처음 내려갔을 때. */
		HEALTH_80("체력 80%"),
		HEALTH_50("체력 50%"),
		HEALTH_30("체력 30%");

		private final String label;

		Trigger(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	/**
	 * 카드 한 장.
	 *
	 * @param id          저장에 쓰는 식별자
	 * @param name        화면에 뜨는 이름
	 * @param description 무엇이 일어나고 무엇을 얻는지. 고르기 전에 읽는 글이다
	 * @param pools       이 카드가 나올 수 있는 자리들
	 * @param risk        위험의 종류
	 */
	public record Trial(String id, String name, String description, Set<Trigger> pools, Risk risk) {
	}

	/** 위험의 종류. 하나가 값에 따라 여러 카드가 된다. */
	public enum Risk {
		/** 각자가 조금 전 있던 자리에 무언가 떨어진다. 움직이면 피한다. */
		DELAYED_STRIKE
	}

	/**
	 * 지금은 견본 한 장뿐이다.
	 *
	 * <p>효과 내용은 사람이 채운다. 여기 있는 것은 <b>틀이 도는지 보기 위한 자리</b>다.
	 */
	private static final List<Trial> TRIALS = List.of(
			new Trial("sharedfate:lightning_trail", "지나간 자리",
					"8초마다 각자가 2초 전에 있던 자리에 번개가 떨어집니다. 대신 팀이 15% 빨라집니다.",
					EnumSet.allOf(Trigger.class), Risk.DELAYED_STRIKE));

	private TrialCatalog() {
	}

	public static List<Trial> all() {
		return TRIALS;
	}

	public static @Nullable Trial byId(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (Trial trial : TRIALS) {
			if (trial.id().equals(id)) {
				return trial;
			}
		}
		return null;
	}

	/**
	 * 이 자리에서 고를 수 있는 카드들. 이미 고른 것은 빠진다.
	 *
	 * <p>비어 있으면 그 트리거는 아무것도 주지 않고 지나간다 — 풀이 마른 것을 오류로 다루지
	 * 않는다. 카드를 채워 가는 동안에는 빈 풀이 정상이다.
	 */
	public static List<Trial> offerable(@Nullable Trigger trigger,
			@Nullable Collection<String> alreadyChosen) {
		List<Trial> available = new ArrayList<>();
		if (trigger == null) {
			return available;
		}
		for (Trial trial : TRIALS) {
			if (!trial.pools().contains(trigger)) {
				continue;
			}
			if (alreadyChosen == null || !alreadyChosen.contains(trial.id())) {
				available.add(trial);
			}
		}
		return available;
	}
}
