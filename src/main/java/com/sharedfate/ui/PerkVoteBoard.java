package com.sharedfate.ui;

import org.jetbrains.annotations.Nullable;

/**
 * 증강 선택창에서 <b>선택자가 아닌 사람이 던지는 표</b>의 규칙.
 *
 * <p>표는 <b>제안일 뿐이다.</b> 몇 개가 모이든 실제로 고르는 것은 선택자 하나고, 여기에도
 * 서버에도 「표가 몇 개 넘으면 자동으로 정해진다」는 길은 없다. 이 장치가 하는 일은
 * <b>팀이 무엇을 원하는지 선택자에게 보여 주는 것</b>뿐이다.
 *
 * <h2>한 사람은 한 표다 — 카드와 다시 뽑기를 통틀어서</h2>
 * <p>카드끼리만이 아니라 <b>다시 뽑기까지 한 통</b>으로 센다. 「이 카드로 하자」와 「다시
 * 뽑자」는 <b>서로 반대되는 제안</b>이라, 한 사람이 둘 다 들고 있을 수 있으면 선택자가 읽는
 * 숫자가 거짓이 된다 — 표를 던질 사람이 둘뿐인데 「카드 A 2표 · 다시 뽑기 2표」가 뜨면
 * 팀이 무엇을 원하는지 <b>정확히 알 수 없게</b> 되고, 그건 이 장치가 있는 이유 자체를
 * 지우는 것이다.
 *
 * <p>그래서 표 하나는 <b>대상 하나</b>를 가리킨다. 대상은 후보 증강 id 이거나
 * {@link #REROLL_TARGET} 이다.
 *
 * <p>마인크래프트 클래스를 하나도 쓰지 않는다. 서버가 표를 세고 클라이언트가 그리는데,
 * <b>양쪽이 같은 규칙을 봐야</b> 화면의 체크와 서버의 셈이 어긋나지 않는다.
 */
public final class PerkVoteBoard {

	/**
	 * 「다시 뽑자」는 제안을 가리키는 대상 이름.
	 *
	 * <p>증강 id 는 언제나 {@code sharedfate:light_step} 같은 네임스페이스 식별자라
	 * {@code #} 를 담을 수 없다. 그래서 후보 id 와 <b>절대 겹치지 않는다.</b>
	 */
	public static final String REROLL_TARGET = "#reroll";

	/**
	 * 표 하나를 그릴 글자.
	 *
	 * <p>U+2714 HEAVY CHECK MARK. 마인크래프트 26.3 이 쓰는 unifont
	 * ({@code unifont_all_no_pua-17.0.01.hex}) 에 이 자리가 들어 있는 것을 확인하고 골랐다 —
	 * 기본 글꼴에 없는 글자를 쓰면 네모 상자가 뜨고, 그건 고장으로 읽힌다.
	 */
	public static final String MARK = "✔";

	private PerkVoteBoard() {
	}

	/** 이 대상이 「다시 뽑자」는 제안인가. */
	public static boolean isReroll(@Nullable String target) {
		return REROLL_TARGET.equals(target);
	}

	/**
	 * 이미 {@code current} 에 표를 던져 둔 사람이 {@code clicked} 를 눌렀을 때 남는 표.
	 *
	 * <ul>
	 *   <li>같은 것을 다시 누르면 <b>표가 사라진다</b>({@code null}) — 취소</li>
	 *   <li>다른 것을 누르면 <b>표가 그쪽으로 옮겨 간다</b> — 한 사람은 언제나 한 표</li>
	 * </ul>
	 *
	 * <p>판단을 이 한 자리에 모아 둔 것은 <b>클라이언트가 스스로 세지 않게</b> 하기 위해서다.
	 * 클라이언트는 「내가 이걸 눌렀다」만 보내고, 눌린 결과가 무엇인지는 서버가 이 함수로 정한다.
	 *
	 * @param current 지금 이 사람의 표. 아직 안 던졌으면 {@code null}
	 * @param clicked 방금 누른 대상
	 * @return 이 사람의 새 표. {@code null} 이면 표가 없어진 것이다
	 */
	public static @Nullable String toggle(@Nullable String current, String clicked) {
		if (clicked == null || clicked.isEmpty()) {
			return current;
		}
		return clicked.equals(current) ? null : clicked;
	}

	/**
	 * 표 {@code count} 개를 나타내는 글자.
	 *
	 * <p>하나면 체크만, 둘 이상이면 체크 뒤에 수를 붙인다. 「한 명 더 누르면 2체크」를
	 * 체크 두 개로 그리지 않는 것은 <b>카드가 좁아서</b>다 — 등급 띠에 남는 자리가
	 * 열 몇 픽셀뿐이라 체크를 늘어놓으면 등급 글자를 덮는다.
	 *
	 * <p>0 이하면 빈 문자열이다. 아무도 안 누른 카드에는 아무것도 안 그린다.
	 */
	public static String mark(int count) {
		if (count <= 0) {
			return "";
		}
		return count == 1 ? MARK : MARK + count;
	}
}
