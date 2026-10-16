package com.sharedfate.ui;

/**
 * 팀 만들기 탭의 「드래곤 시련 — 켬」 단추 둘레를 도는 보라 빛의 자리 계산.
 *
 * <p>그리는 일은 {@code TeamScreen} 이 맡고, 여기는 <b>몇 번째 테두리 칸이 어디인지</b>와
 * <b>지금 머리가 어디에 있는지</b>만 정한다. 화면 없이 시험할 수 있게 떼어 둔 것이다.
 *
 * <h2>테두리 칸을 세는 방법</h2>
 * <p>폭 {@code w}, 높이 {@code h} 인 사각형의 바깥 1px 테두리를 <b>왼쪽 위 모서리에서 시작해
 * 시계 방향으로</b> 0, 1, 2 … 로 센다. 모서리 칸은 한 번만 센다 — 두 번 세면 빛이 모서리에서
 * 한 프레임 멈칫한다. 그래서 둘레는 {@code 2w + 2h − 4} 칸이다.
 *
 * <h2>왜 시간을 틱이 아니라 밀리초로 재나</h2>
 * <p>틱(초당 20)으로 재면 한 바퀴 2초 동안 머리가 40번만 움직여, 둘레 300칸 남짓인 단추에서
 * 빛이 여덟 칸씩 뚝뚝 끊겨 건너뛴다. 실시간으로 재면 프레임마다 자리가 새로 정해지고, 프레임률이
 * 달라도 한 바퀴 시간은 같다.
 */
public final class DragonTrialGlow {

	/** 한 바퀴에 걸리는 시간(ms). */
	public static final long PERIOD_MS = 2000L;

	/**
	 * 머리 수. 둘레를 같은 간격으로 나눠 선다.
	 *
	 * <p>둘이면 늘 단추의 반대편 두 변에 하나씩 있어, 어느 쪽을 보든 빛이 눈에 걸린다.
	 */
	public static final int HEADS = 2;

	/**
	 * 꼬리 길이(칸). 머리 칸을 포함한다.
	 *
	 * <p>둘레의 4분의 1쯤이 알맞다 — 이보다 길면 두 머리의 꼬리가 이어져 둘레 전체가 한 줄로
	 * 보이고, 짧으면 「도는」 느낌 없이 점 두 개가 미끄러질 뿐이다.
	 */
	public static final int TAIL_LENGTH = 40;

	private DragonTrialGlow() {
	}

	/** 테두리 한 칸. 사각형 왼쪽 위에서 잰 오프셋이다. */
	public record Pixel(int dx, int dy) {
	}

	/** 바깥 1px 테두리의 칸 수. 폭이나 높이가 2 미만이면 테두리라 할 것이 없어 0이다. */
	public static int perimeter(int width, int height) {
		if (width < 2 || height < 2) {
			return 0;
		}
		return 2 * width + 2 * height - 4;
	}

	/**
	 * 테두리 {@code index} 번째 칸의 자리.
	 *
	 * <p>둘레를 넘거나 음수인 값도 받는다 — 둘레로 감아 돌린다. 꼬리를 그릴 때 머리에서
	 * 거꾸로 세다 0 아래로 내려가는 일이 늘 있다.
	 */
	public static Pixel pixelAt(int index, int width, int height) {
		int perimeter = perimeter(width, height);
		if (perimeter == 0) {
			return new Pixel(0, 0);
		}
		int i = Math.floorMod(index, perimeter);
		int right = width - 1;
		int bottom = height - 1;
		// 윗변: 왼쪽 위 → 오른쪽 위 (모서리 둘 포함)
		if (i < width) {
			return new Pixel(i, 0);
		}
		i -= width;
		// 오른변: 오른쪽 위 아래 칸부터 오른쪽 아래까지
		if (i < bottom) {
			return new Pixel(right, 1 + i);
		}
		i -= bottom;
		// 아랫변: 오른쪽 아래 왼쪽 칸부터 왼쪽 아래까지
		if (i < right) {
			return new Pixel(right - 1 - i, bottom);
		}
		i -= right;
		// 왼변: 왼쪽 아래 위 칸부터 왼쪽 위 아래 칸까지. 왼쪽 위는 0번이라 다시 세지 않는다.
		return new Pixel(0, bottom - 1 - i);
	}

	/**
	 * {@code head} 번째 머리가 지금 서 있는 칸.
	 *
	 * @param millis 실시간 밀리초. 어디서 시작한 값이든 상관없다 — 한 바퀴로 나눈 나머지만 본다
	 * @param head   0부터 {@link #HEADS} − 1 까지
	 */
	public static int headIndex(long millis, int perimeter, int head) {
		if (perimeter <= 0) {
			return 0;
		}
		long phase = Math.floorMod(millis, PERIOD_MS);
		int base = (int) (phase * perimeter / PERIOD_MS);
		return Math.floorMod(base + head * perimeter / HEADS, perimeter);
	}

	/**
	 * 머리에서 {@code step} 칸 뒤의 불투명도(0–255).
	 *
	 * <p>0이 머리라 가장 진하고 뒤로 갈수록 옅어진다. <b>제곱으로</b> 줄인다 — 곧게 줄이면
	 * 꼬리 끝이 뭉툭하게 끊겨 보이고, 제곱이면 머리 근처만 밝고 끝은 연기처럼 흩어진다.
	 * 꼬리 밖({@code step < 0} 또는 {@link #TAIL_LENGTH} 이상)은 0이다.
	 */
	public static int tailAlpha(int step) {
		if (step < 0 || step >= TAIL_LENGTH) {
			return 0;
		}
		double remain = 1.0 - (double) step / TAIL_LENGTH;
		return (int) Math.round(255 * remain * remain);
	}

	/** {@code 0xRRGGBB} 색에 불투명도를 얹어 {@code 0xAARRGGBB} 로 만든다. */
	public static int withAlpha(int rgb, int alpha) {
		return (Math.clamp(alpha, 0, 255) << 24) | (rgb & 0xFFFFFF);
	}
}
