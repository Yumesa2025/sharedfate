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
 * <p>증강처럼 JSON 으로 빼는 것이 목표다. 다만 위험 타입마다 필요한 값이 다르고 그 목록이
 * 아직 두어 개 분량밖에 드러나지 않았다. 지금 파서를 만들면 카드 대여섯 장째에 다시 짠다.
 * <b>타입이 다섯쯤 굳은 뒤에 옮긴다.</b>
 *
 * <h2>카드는 룰렛으로 뽑는다</h2>
 *
 * <p>셋을 띄워 고르게 하지 않는다. 자리가 터지면 이름이 돌다가 멈추고, 멈춘 것이 그 판의
 * 시련이다. 새 화면이 필요 없으므로 <b>통신 규약이 29 그대로</b>이고, 카드가 한 장뿐일 때도
 * 굴러간다.
 *
 * <h2>풀을 나누는 이유</h2>
 *
 * <p>입장 직후와 체력 30% 는 같은 무게일 수 없다. 트리거마다 풀을 따로 두면 <b>난이도 곡선</b>이
 * 생긴다. 카드 하나가 여러 풀에 속할 수 있으므로 「전멸과 80% 를 한 묶음으로」도, 「30% 에만
 * 나오는 카드」도 만들 수 있다.
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

	/** 자리 여섯을 묶은 풀 넷. 카드를 적을 때 이 이름으로 적는다. */
	public static final Set<Trigger> POOL_ENTRY = EnumSet.of(Trigger.ENTRY);
	public static final Set<Trigger> POOL_FIRST_CRYSTAL = EnumSet.of(Trigger.FIRST_CRYSTAL);
	/** 크리스탈 전멸과 체력 80% 는 무게가 비슷해 함께 쓴다. */
	public static final Set<Trigger> POOL_MIDDLE = EnumSet.of(Trigger.ALL_CRYSTALS, Trigger.HEALTH_80);
	/** 체력 50% 와 30%. */
	public static final Set<Trigger> POOL_LATE = EnumSet.of(Trigger.HEALTH_50, Trigger.HEALTH_30);

	/**
	 * 위험 하나.
	 *
	 * <h2>왜 봉인 인터페이스인가</h2>
	 *
	 * <p>타입마다 필요한 값이 다르다. 칸을 하나로 합치면 낙뢰 카드에 「부활 개수」가, 부활
	 * 카드에 「예고 시간」이 붙어 <b>그 칸이 무슨 뜻인지 아무도 모르게 된다.</b>
	 *
	 * <p>실행기의 분기를 {@code default} 없는 switch 식으로 쓴다. 여기에 타입을 더하고 실행을
	 * 안 붙이면 <b>컴파일이 거절한다.</b> 이 저장소가 {@code GameOverCountdown.Reason} 에서
	 * 이미 쓰는 방식이다.
	 */
	public sealed interface Risk {

		/** 노리는 곳. */
		enum Aim {
			/** 그 사람이 조금 전 있던 자리. 계속 움직이면 빗나간다. */
			TRAIL,
			/** 아레나 안의 아무 곳. 서 있던 자리와 무관하다. */
			RANDOM_SPOT
		}

		/**
		 * 예고한 자리에 무언가 떨어진다.
		 *
		 * @param aim      발자국을 노리는가 아무 곳인가
		 * @param interval 떨어지는 간격(틱)
		 * @param lookback 몇 틱 전 발자국을 노리는가. {@link Aim#RANDOM_SPOT} 이면 쓰지 않는다
		 * @param damage   반경 안에 남아 있는 사람이 받는 피해
		 * @param radius   피해 반경(블록). 바닥 고리도 이 크기로 그린다
		 * @param launch   맞은 사람을 띄우는 높이(블록). 0 이면 띄우지 않는다.
		 *                 띄우는 동안은 낙하 피해를 면제한다 — 이 카드의 위험은 노출이지 낙사가 아니다
		 * @param count    한 번에 몇 군데인가. {@link Aim#TRAIL} 이면 <b>무작위로 뽑는 사람 수</b>다
		 */
		record DelayedStrike(Aim aim, int interval, int lookback, float damage, double radius,
				double launch, int count) implements Risk {
		}
	}

	/**
	 * 카드 한 장.
	 *
	 * @param id          저장에 쓰는 식별자
	 * @param name        룰렛과 화면에 뜨는 이름
	 * @param description 무엇이 일어나는지. 뽑힌 순간 읽는 글이다
	 * @param pools       이 카드가 나올 수 있는 자리들
	 * @param risks       이 카드가 거는 위험들. 여럿이면 함께 돈다
	 */
	public record Trial(String id, String name, String description, Set<Trigger> pools,
			List<Risk> risks) {

		/** 위험이 하나뿐인 흔한 경우. */
		public Trial(String id, String name, String description, Set<Trigger> pools, Risk risk) {
			this(id, name, description, pools, List.of(risk));
		}
	}

	/**
	 * 카드 목록. 내용은 {@code docs/드래곤-시련-카드.md} 와 짝이다.
	 *
	 * <p><b>빈 풀은 정상이다.</b> 카드를 채워 가는 동안 자리가 비면 그 자리는 그냥 지나간다.
	 */
	private static final List<Trial> TRIALS = List.of(
			new Trial("sharedfate:ground_strike", "자리 폭격",
					"12초마다 한 사람이 2초 전에 있던 자리가 터집니다. 맞으면 하늘로 떠오릅니다.",
					POOL_ENTRY,
					new Risk.DelayedStrike(Risk.Aim.TRAIL, 240, 40, 6.0F, 2.0, 4.0, 1)),
			new Trial("sharedfate:lightning_storm", "낙뢰",
					"6초마다 아레나 두 곳에 번개가 떨어집니다. 떨어지기 전에 자리가 보입니다.",
					POOL_MIDDLE,
					new Risk.DelayedStrike(Risk.Aim.RANDOM_SPOT, 120, 0, 5.0F, 3.0, 0.0, 2)));

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
	 * 이 자리에서 뽑힐 수 있는 카드들. 이미 뽑힌 것은 빠진다.
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
