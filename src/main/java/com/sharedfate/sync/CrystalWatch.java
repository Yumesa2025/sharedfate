package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 엔드 크리스탈을 둘러싼 <b>한 줌의 전역 상태</b>. 믹스인과 시련 실행기가 여기서 만난다.
 *
 * <h2>왜 상태가 여기 있는가</h2>
 *
 * <p>믹스인은 값을 들 수 없다. {@code EndCrystalGuardMixin} 은 바닐라
 * {@code EndCrystal} 안에 섞여 들어가는 조각이라 자기 필드를 두어 봐야 <b>크리스탈 한 마리마다
 * 하나</b>가 되고, 시련 실행기가 그것을 찾아갈 길이 없다. 그래서 「지금 크리스탈이 투사체에
 * 면역인가」처럼 <b>판 전체에 하나뿐인 사실</b>은 이 클래스가 들고, 믹스인은 이것을 읽기만
 * 한다. {@code MobTickRate} 가 {@code ServerLevelSanctuaryTickMixin} 에 대해 하는 일과 같은
 * 모양이다.
 *
 * <h2>이 클래스는 정책을 모른다</h2>
 *
 * <p>「어느 카드가 면역을 켜는가」는 {@link TrialCrystalGuard} 가 정하고, 「깬 사람을 어떻게
 * 쓰는가」는 「표적」 카드 실행기가 정한다. 여기는 <b>깃발과 UUID 두 칸</b>이 전부다. 정책을
 * 여기 들이면 카드를 늘릴 때마다 믹스인이 보는 클래스가 부풀어 오른다.
 *
 * <h2>{@code volatile} 인 이유</h2>
 *
 * <p>쓰는 쪽(시련 틱)과 읽는 쪽(크리스탈 피격)은 모두 서버 스레드라 사실 필요 없다. 그래도
 * 붙여 둔 것은 <b>믹스인이 남의 클래스 안에서 도는 코드</b>라 어느 스레드가 부를지 이 파일만
 * 보고는 보증할 수 없기 때문이다. 값 두 칸을 읽고 쓰는 비용이므로 손해가 없다.
 *
 * <h2>반드시 비워야 한다</h2>
 *
 * <p>둘 다 <b>정적</b>이고 월드보다 오래 산다. 되돌리지 않으면 다음 전투는 물론 <b>다음
 * 월드</b>의 크리스탈까지 화살에 맞지 않는다. 월드가 바뀌거나 서버가 내려갈 때
 * {@link #clearState()} 를 부르는 것은 부르는 쪽의 의무다.
 */
public final class CrystalWatch {

	/** 크리스탈이 투사체에 면역인가. 믹스인이 매 피격마다 이 한 줄을 읽는다. */
	private static volatile boolean arrowImmune;

	/** 크리스탈을 가장 최근에 깬 사람. 아직 아무도 깨지 않았으면 {@code null}. */
	private static volatile @Nullable UUID lastBreaker;

	private CrystalWatch() {
	}

	/**
	 * 크리스탈이 투사체에 면역인가.
	 *
	 * <p>믹스인이 <b>모든 크리스탈 피격</b>에서 부른다. 그래서 이 메서드는 칸 하나를 읽는 것
	 * 이상을 해서는 안 된다 — 여기에 계산을 넣으면 시련이 하나도 없는 서버까지 값을 치른다.
	 */
	public static boolean arrowImmune() {
		return arrowImmune;
	}

	/** {@link TrialCrystalGuard} 가 켜고 끈다. <b>끄는 쪽을 빠뜨리지 말 것.</b> */
	public static void setArrowImmune(boolean immune) {
		arrowImmune = immune;
	}

	/**
	 * 크리스탈을 가장 최근에 깬 사람. 없으면 {@code null}.
	 *
	 * <p>「표적」 카드({@code Risk.Focus.CRYSTAL_BREAKER})가 이것을 읽어 드래곤이 노릴 사람을
	 * 고른다. {@code null} 이면 그 카드는 무작위로 물러난다 — 아무도 깨지 않은 판에서 표적이
	 * 정해지지 않는 것은 정상이다.
	 */
	public static @Nullable UUID lastBreaker() {
		return lastBreaker;
	}

	/**
	 * 크리스탈이 깨졌다고 적는다. 믹스인이 부른다.
	 *
	 * <p><b>{@code null} 은 기록을 지우지 않는다.</b> 크리스탈은 사람 아닌 원인(연쇄 폭발·
	 * 주인 없는 TNT)으로도 깨지는데, 그때마다 기록을 비우면 <b>연쇄로 남은 크리스탈이 함께
	 * 터지는 순간 방금 정해진 표적이 사라진다.</b> 실제로 크리스탈은 하나를 깨면 옆 것이
	 * 딸려 터지는 일이 흔하므로, 「사람이 깬 마지막 한 번」을 붙들고 있는 편이 카드의 뜻에
	 * 맞는다. 정말로 비워야 할 때는 {@link #clearState()} 다.
	 */
	public static void noteBreaker(@Nullable UUID playerId) {
		if (playerId == null) {
			return;
		}
		lastBreaker = playerId;
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. <b>두 칸을 모두 되돌린다.</b>
	 *
	 * <p>{@link TrialCrystalGuard#clearState()} 도 면역 깃발을 내리지만 그쪽은 「이 카드가 켠
	 * 것을 끈다」이고, 이쪽은 「판이 끝났으니 전부 버린다」다. 깬 사람 기록은 이쪽에만 있다 —
	 * 그 기록의 주인은 크리스탈 보호 카드가 아니라 판 자체이기 때문이다.
	 */
	public static void clearState() {
		arrowImmune = false;
		lastBreaker = null;
	}
}
