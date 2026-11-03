package com.sharedfate.ui;

/**
 * 다음 증강 구간까지의 진행도 계산.
 *
 * <p>한 칸의 길이({@code step})는 {@code PerkMilestones.STEP} 을 그대로 받는다.
 */
public final class PerkGauge {
	private PerkGauge() {
	}

	/**
	 * 막대가 얼마나 찼는지 0.0~1.0 으로 돌려준다.
	 *
	 * <p>남은 레벨이 한 칸과 같으면 방금 구간을 지난 직후라 0.0, 0이면 다음 구간에 닿아 1.0 이다.
	 *
	 * <p><b>한 칸보다 많이 남은 경우</b>가 실제로 생긴다. 인챈트나 모루로 레벨을 써서 이전
	 * 구간보다도 아래로 내려가면 남은 레벨이 한 칸을 넘는다. 그때는 0.0 으로 자른다. 음수로
	 * 그리면 막대가 반대로 뻗고, 그대로 두면 이전 구간까지 되돌아가야 한다는 사실이 감춰진다.
	 *
	 * @param levelsRemaining 다음 구간까지 남은 레벨. 더 받을 증강이 없으면 음수가 올 수 있다
	 * @param step            구간 간격. 0 이하면 1로 본다
	 */
	public static float fraction(int levelsRemaining, int step) {
		int safeStep = Math.max(1, step);
		return Math.clamp((safeStep - levelsRemaining) / (float) safeStep, 0.0F, 1.0F);
	}

	/**
	 * {@link #fraction(int, int)} 에 <b>지금 레벨 안에서 찬 몫</b>까지 더한다. 막대가 레벨마다 한 칸씩
	 * 뛰지 않고 경험치 구슬을 먹을 때마다 조금씩 찬다.
	 *
	 * <p>한 칸보다 많이 남았으면(이전 구간 아래로 내려갔으면) 레벨 안의 몫을 더하지 않고 0 이다.
	 * 더하면 아직 이전 구간에도 못 닿았는데 막대가 차오르는 것처럼 보인다.
	 *
	 * @param levelProgress 바닐라 {@code experienceProgress}(0~1). 모르면 0
	 */
	public static float fraction(int levelsRemaining, int step, float levelProgress) {
		int safeStep = Math.max(1, step);
		if (levelsRemaining > safeStep) {
			return 0.0F;
		}
		float progress = Float.isFinite(levelProgress) ? Math.clamp(levelProgress, 0.0F, 1.0F) : 0.0F;
		return Math.clamp((safeStep - levelsRemaining + progress) / safeStep, 0.0F, 1.0F);
	}
}
