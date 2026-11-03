package com.sharedfate.ui;

/**
 * 「다음 증강까지」 막대의 움직임 — 부드럽게 차오르기 · 찰 때의 짧은 반짝임 · 구간에 닿은 순간의 번쩍임.
 *
 * <p>그리는 일({@code PerkProgressHud})과 갈라 두어 월드 없이 시험한다. 시계는 밀리초를 밖에서 받는다.
 *
 * <h2>세 가지 움직임</h2>
 * <ul>
 *   <li><b>보간</b> — 보이는 값이 목표를 시간 상수 {@link #SMOOTH_MS} 로 따라간다. 화면 갱신 빈도와
 *       상관없이 같은 속도다</li>
 *   <li><b>반짝임</b> — 목표가 오르면 진행 끝이 {@link #SPARKLE_MS} 동안 밝아진다</li>
 *   <li><b>번쩍임</b> — 다음 증강 레벨이 올라가면(구간에 닿아 증강을 받았으면) 막대 전체가
 *       {@link #FLASH_MS} 동안 번쩍이고, 보이는 값은 곧바로 새 구간의 처음으로 간다. 0 까지 천천히
 *       줄어드는 모습은 「경험치를 잃었다」로 읽힌다</li>
 * </ul>
 */
public final class PerkGaugeMotion {
	/** 보간의 시간 상수. 이만큼 지나면 남은 거리의 약 63% 를 메운다. */
	public static final float SMOOTH_MS = 180.0F;
	/** 차오를 때 진행 끝이 밝아지는 시간. */
	public static final long SPARKLE_MS = 450;
	/** 구간에 닿았을 때 막대 전체가 번쩍이는 시간. */
	public static final long FLASH_MS = 700;
	/** 한 화면 사이의 시간이 이보다 길면(창을 내렸다 올렸을 때) 이만큼으로 본다. */
	static final long MAX_STEP_MS = 250;
	/** 이만큼 가까우면 목표에 붙인다. 막대 폭 182 에서 반 픽셀보다 작다. */
	static final float SNAP = 0.002F;
	/** 이보다 작게 오르면 반짝이지 않는다. 소수점 부스러기로 번쩍이면 늘 반짝인다. */
	static final float SPARKLE_THRESHOLD = 0.004F;

	private boolean started;
	private float shown;
	private float target;
	private int nextPerkLevel;
	private long lastNow;
	private long sparkleAt = Long.MIN_VALUE;
	private long flashAt = Long.MIN_VALUE;

	/**
	 * 한 화면마다 부른다.
	 *
	 * @param newTarget        0~1. {@link PerkGauge#fraction(int, int, float)}
	 * @param newNextPerkLevel 다음 증강이 나오는 레벨. 이것이 오르면 구간에 닿은 것이다
	 */
	public void update(float newTarget, int newNextPerkLevel, long now) {
		float clamped = Float.isFinite(newTarget) ? Math.clamp(newTarget, 0.0F, 1.0F) : 0.0F;
		if (!started || now < lastNow) {
			// 처음이거나 시계가 거꾸로 갔다(월드를 옮겼다). 지난 값에서 끌어오지 않는다.
			snap(clamped, newNextPerkLevel, now);
			return;
		}
		if (newNextPerkLevel != nextPerkLevel) {
			// 구간을 넘었다. 이전 구간이 0 이었으면(증강이 꺼져 있다 켜졌으면) 번쩍이지 않는다.
			if (nextPerkLevel > 0 && newNextPerkLevel > nextPerkLevel) {
				flashAt = now;
			}
			snap(clamped, newNextPerkLevel, now);
			return;
		}
		if (clamped > target + SPARKLE_THRESHOLD) {
			sparkleAt = now;
		}
		target = clamped;
		long step = Math.min(MAX_STEP_MS, now - lastNow);
		lastNow = now;
		float keep = (float) Math.exp(-step / SMOOTH_MS);
		shown = target + (shown - target) * keep;
		if (Math.abs(shown - target) < SNAP) {
			shown = target;
		}
	}

	private void snap(float value, int level, long now) {
		started = true;
		shown = value;
		target = value;
		nextPerkLevel = level;
		lastNow = now;
	}

	/** 지금 보이는 찬 정도(0~1). */
	public float shown() {
		return shown;
	}

	/** 진행 끝 반짝임의 세기(0~1). 막 올랐을 때 1 에서 시작해 줄어든다. */
	public float sparkle(long now) {
		return decay(sparkleAt, SPARKLE_MS, now);
	}

	/** 구간에 닿은 번쩍임의 세기(0~1). */
	public float flash(long now) {
		return decay(flashAt, FLASH_MS, now);
	}

	private static float decay(long at, long length, long now) {
		if (at == Long.MIN_VALUE || now < at || now - at >= length) {
			return 0.0F;
		}
		float t = (now - at) / (float) length;
		return (1.0F - t) * (1.0F - t);
	}

	/** 막대가 사라질 때(팀을 떠났을 때) 비운다. 다시 나타나면 보간 없이 제자리에 선다. */
	public void reset() {
		started = false;
		shown = 0.0F;
		target = 0.0F;
		nextPerkLevel = 0;
		sparkleAt = Long.MIN_VALUE;
		flashAt = Long.MIN_VALUE;
	}
}
