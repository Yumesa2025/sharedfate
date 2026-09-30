package com.sharedfate.ui;

/**
 * 증강 선택창의 「다시 뽑기」 단추가 무엇을 보여 주고 언제 눌리는지.
 *
 * <p>여기서 정하는 것은 <b>보여 주기</b>뿐이다. 실제로 다시 뽑아도 되는지는 서버가
 * {@code PerkManager.applyReroll} 에서 처음부터 다시 따진다. 이 클래스가 참을 돌려준다고
 * 해서 서버가 요청을 받아 준다는 뜻이 아니다.
 */
public final class PerkRerollButton {

	private PerkRerollButton() {
	}

	/**
	 * 단추에 적을 글자. 남은 횟수가 늘 보여야 한다.
	 *
	 * <p>0 일 때도 「0회 남음」이 아니라 못 쓴다는 뜻이 드러나게 적는다.
	 */
	public static String label(int remaining) {
		return remaining > 0
				? "다시 뽑기 (" + remaining + "회 남음)"
				: "다시 뽑기 (남은 횟수 없음)";
	}

	/**
	 * 단추를 그릴지.
	 *
	 * <p>서버가 강제로 띄운 창에서, 고를 권한이 있는 사람에게만 보인다. 관전자에게 보이면
	 * 눌러도 서버가 버리는 단추가 되고, {@code /shareteam perk} 로 직접 연 창에서는 시간이
	 * 멈춰 있지 않아 다시 뽑기 자체가 성립하지 않는다.
	 *
	 * <p>남은 횟수가 0 이어도 <b>그리기는 한다.</b>
	 */
	public static boolean visible(boolean forced, boolean canChoose) {
		return forced && canChoose;
	}

	/**
	 * 지금 누를 수 있는지.
	 *
	 * @param choiceSent    이미 증강을 골라 보낸 뒤인지
	 * @param rerollSent    다시 뽑기를 눌러 두고 서버의 새 후보를 기다리는 중인지
	 * @param showingResult 무엇이 정해졌는지 보여 주는 중인지
	 * @param remaining     이번 회차에 남은 횟수
	 */
	public static boolean enabled(boolean forced, boolean canChoose, boolean choiceSent,
			boolean rerollSent, boolean showingResult, int remaining) {
		return visible(forced, canChoose) && !choiceSent && !rerollSent && !showingResult
				&& remaining > 0;
	}

	/**
	 * 선택자가 <b>아닌</b> 사람에게 「다시 뽑기 제안」 단추를 그릴지.
	 *
	 * <p>{@link #visible} 과 정확히 반대쪽이다. 같은 자리에 서지만 하는 일이 다르다 —
	 * 이쪽은 후보를 갈아 끼우지 않고 <b>「다시 뽑자」는 표 하나</b>를 던진다. 실제로 다시
	 * 뽑는 것은 여전히 선택자뿐이다.
	 *
	 * <p>이 단추가 생기기 전에는 선택자가 아닌 사람에게 <b>남은 횟수 자체가 안 보였다.</b>
	 * 후보를 못 갈아 끼우는 사람에게는 필요 없는 값이라고 본 것인데, 제안을 하려면 남은
	 * 것이 있는지부터 알아야 한다.
	 *
	 * <p>{@code /shareteam perk} 로 직접 연 창에는 안 그린다. 그 창은 시간도 멈춰 있지
	 * 않고 선택자도 제안 상대도 없는 혼자 보는 확인용이다.
	 */
	public static boolean proposable(boolean forced, boolean canChoose) {
		return forced && !canChoose;
	}

	/**
	 * 제안 단추에 적을 글자. 남은 횟수가 늘 보여야 한다.
	 *
	 * <p>{@link #label} 보다 짧다. 뒤에 표 수가 붙을 자리를 남겨야 하는데, 단추 폭이
	 * 140픽셀이라 「다시 뽑기 (3회 남음)」 만으로도 거의 찬다.
	 */
	public static String proposeLabel(int remaining) {
		return remaining > 0
				? "다시 뽑기 제안 (" + remaining + "회)"
				: "다시 뽑기 (남은 횟수 없음)";
	}

	/**
	 * 제안 단추를 지금 누를 수 있는지.
	 *
	 * <p>{@link #enabled} 에 있는 {@code choiceSent}·{@code rerollSent} 가 여기엔 없다.
	 * 선택자가 아닌 사람은 선택을 보내지도, 다시 뽑기를 보내지도 않으므로 기다릴 답이 없다.
	 * 표는 몇 번이고 켜고 끌 수 있어야 한다 — 마음이 바뀌는 것이 이 장치의 요점이다.
	 *
	 * <p>남은 횟수가 0 이면 잠근다. 쓸 수 없는 것을 제안하면 선택자가 할 수 없는 일을
	 * 하라는 표를 보게 된다.
	 */
	public static boolean proposeEnabled(boolean forced, boolean canChoose, boolean showingResult,
			int remaining) {
		return proposable(forced, canChoose) && !showingResult && remaining > 0;
	}
}
