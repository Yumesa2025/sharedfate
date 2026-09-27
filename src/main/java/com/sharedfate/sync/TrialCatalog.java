package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 시련 카드 목록.
 *
 * <h2>왜 아직 JSON 이 아닌가</h2>
 *
 * <p>증강처럼 JSON 으로 빼는 것이 목표다. 다만 카드가 한 장뿐인 지금 파싱·등록 인프라를 먼저
 * 만들면, 카드 내용이 정해지기 전에 구조부터 굳는다. <b>한 장을 끝까지 굴려 보고</b> 구조가
 * 맞는 것을 확인한 뒤 옮긴다. 카드가 늘어나기 전에 옮기는 것이 순서다.
 *
 * <h2>카드는 위험과 보상 한 짝이다</h2>
 *
 * <p>증강이 「대가가 따르는 강화」였으니 시련은 그 대칭 — 「강화가 따르는 위험」이다. 그래야
 * 고를 때 실제로 고민이 생긴다. 셋 다 나쁘기만 하면 매번 「가장 덜 나쁜 것」을 고르게 되고
 * 선택이 단조로워진다.
 */
public final class TrialCatalog {

	/**
	 * 카드 한 장.
	 *
	 * @param id          저장에 쓰는 식별자
	 * @param name        화면에 뜨는 이름
	 * @param description 무엇이 일어나고 무엇을 얻는지. 고르기 전에 읽는 글이다
	 * @param risk        위험의 종류
	 */
	public record Trial(String id, String name, String description, Risk risk) {
	}

	/** 위험의 종류. 하나가 값에 따라 여러 카드가 된다. */
	public enum Risk {
		/** 각자가 조금 전 있던 자리에 무언가 떨어진다. 움직이면 피한다. */
		DELAYED_STRIKE
	}

	private static final List<Trial> TRIALS = List.of(
			new Trial("sharedfate:lightning_trail", "지나간 자리",
					"8초마다 각자가 2초 전에 있던 자리에 번개가 떨어집니다. 대신 팀이 15% 빨라집니다.",
					Risk.DELAYED_STRIKE));

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
	 * 아직 안 고른 카드 중에서 고를 수 있는 것들.
	 *
	 * <p>실제로는 여기서 셋을 뽑아 보여 준다. 지금은 카드가 한 장이라 그대로 돌려준다.
	 */
	public static List<Trial> offerable(@Nullable Collection<String> alreadyChosen) {
		List<Trial> available = new ArrayList<>();
		for (Trial trial : TRIALS) {
			if (alreadyChosen == null || !alreadyChosen.contains(trial.id())) {
				available.add(trial);
			}
		}
		return available;
	}
}
