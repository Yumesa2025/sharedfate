package com.sharedfate.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 증강 발동 알림 줄 — 무엇을 띄우고, 언제 흐리고, 언제 소리를 내는가.
 *
 * <p>그리는 일({@code PerkProcHud})과 갈라 둔 까닭은 월드 없이 시험하려는 것이다. 시계는 밀리초를
 * 밖에서 받는다.
 *
 * <h2>하루 종일 보는 화면이라 아낀다</h2>
 * <ul>
 *   <li>한 줄은 {@link #SHOW_MS}(1.5초) 떠 있다. 처음 {@link #FADE_IN_MS} 동안 아래에서 살짝
 *       떠오르며 나타나고, 마지막 {@link #FADE_OUT_MS} 동안 흐려진다</li>
 *   <li>같은 증강은 {@link #TEXT_COOLDOWN_MS}(3초)에 한 번까지만 뜬다. 「반격」처럼 팀원이 맞을
 *       때마다 터지는 것이 난전에서 화면을 덮지 않게 한다</li>
 *   <li>여러 개가 겹치면 위로 쌓되 {@link #MAX_LINES} 줄까지다. 넘치면 가장 오래된 줄을 뺀다</li>
 *   <li>소리는 같은 증강이 {@link #SOUND_COOLDOWN_MS}(5초)에 한 번, 무엇이든 {@link #SOUND_GAP_MS}
 *       안에 두 번 나지 않는다. 셋이 한꺼번에 떠도 소리는 하나다</li>
 * </ul>
 */
public final class PerkProcFeed {
	/** 한 줄이 떠 있는 시간. */
	public static final long SHOW_MS = 1500;
	/** 나타나며 떠오르는 시간. */
	public static final long FADE_IN_MS = 150;
	/** 사라지기 전 흐려지는 시간. */
	public static final long FADE_OUT_MS = 450;
	/** 같은 증강이 다시 뜨기까지. */
	public static final long TEXT_COOLDOWN_MS = 3000;
	/** 같은 증강의 소리가 다시 나기까지. 글자보다 길다. */
	public static final long SOUND_COOLDOWN_MS = 5000;
	/** 서로 다른 증강이라도 이 안에서는 소리를 한 번만 낸다. */
	public static final long SOUND_GAP_MS = 300;
	/** 한꺼번에 보이는 줄 수. */
	public static final int MAX_LINES = 3;
	/** 글자의 가장 진한 불투명도. 반투명으로 둔다. */
	public static final float MAX_ALPHA = 0.85F;
	/** 줄 앞의 표식. */
	public static final String MARK = "⟡";

	/** 등급색. 팀 화면·보유 목록의 글자색과 같다. */
	public static final int SILVER = 0xFFC0C6CC;
	public static final int GOLD = 0xFFFFC63A;
	public static final int PRISM = 0xFF5FE0D8;

	/** 떠 있는 줄 하나. */
	public record Line(String key, String name, int accent, long startedAt) {
		/** 화면에 적는 글. 「⟡ 급소만 노려」. */
		public String text() {
			return MARK + " " + name;
		}
	}

	/**
	 * 지금 그릴 모습.
	 *
	 * @param alpha 0~{@link #MAX_ALPHA}
	 * @param rise  0~1. 1 이면 제자리, 0 이면 막 나타나 조금 아래에 있다
	 */
	public record Frame(Line line, float alpha, float rise) {
	}

	/**
	 * 알림 하나를 받은 결과.
	 *
	 * @param shown     줄을 새로 띄웠는가
	 * @param playSound 소리를 낼 차례인가
	 */
	public record Offer(boolean shown, boolean playSound) {
		static final Offer IGNORED = new Offer(false, false);
	}

	/** 오래된 것이 앞, 새것이 뒤. */
	private final Deque<Line> lines = new ArrayDeque<>();
	private final Map<String, Long> lastShown = new HashMap<>();
	private final Map<String, Long> lastSound = new HashMap<>();
	private long lastAnySound = Long.MIN_VALUE;

	/** 등급 id 를 테두리 색으로. 모르는 값은 실버. */
	public static int accentFor(String rarity) {
		if (rarity == null) {
			return SILVER;
		}
		return switch (rarity) {
			case "gold" -> GOLD;
			case "prism" -> PRISM;
			case "trial" -> DragonPrepStyle.COLOR;
			default -> SILVER;
		};
	}

	/**
	 * 서버가 보낸 알림 하나를 받는다.
	 *
	 * <p>같은 열쇠가 3초 안에 다시 오면 무시한다 — 떠 있는 줄을 늘리지도, 다시 시작하지도 않는다.
	 * 다시 시작하면 「반격」이 난전 내내 한 줄로 붙어 있게 된다.
	 */
	public Offer offer(String key, String name, String rarity, long now) {
		return offer(key, name, rarity, now, true, true);
	}

	/**
	 * 이 컴퓨터의 설정({@code sharedfate-client.json})을 얹어 받는다.
	 *
	 * <p>글자를 끄면 줄을 쌓지 않고, 소리를 끄면 소리 차례를 돌려주지 않는다. 둘 다 끄면 아무것도
	 * 기억하지 않는다 — 다시 켠 순간 묵은 쿨다운에 걸려 첫 알림이 안 뜨면 고장으로 읽힌다. 하나만
	 * 꺼도 3초 쿨다운은 그대로 센다. 소리만 켠 사람도 「반격」이 난전 내내 울리면 안 된다.
	 *
	 * @param showText  글자를 띄우는가({@code procAlerts})
	 * @param playSound 소리를 내는가({@code procSound})
	 */
	public Offer offer(String key, String name, String rarity, long now, boolean showText,
			boolean playSound) {
		if (key == null || name == null || name.isBlank() || (!showText && !playSound)) {
			return Offer.IGNORED;
		}
		Long shown = lastShown.get(key);
		if (shown != null && now >= shown && now - shown < TEXT_COOLDOWN_MS) {
			return Offer.IGNORED;
		}
		lastShown.put(key, now);
		if (showText) {
			expire(now);
			lines.addLast(new Line(key, name, accentFor(rarity), now));
			while (lines.size() > MAX_LINES) {
				lines.removeFirst();
			}
		}
		return new Offer(showText, playSound && takeSound(key, now));
	}

	private boolean takeSound(String key, long now) {
		Long last = lastSound.get(key);
		if (last != null && now >= last && now - last < SOUND_COOLDOWN_MS) {
			return false;
		}
		if (lastAnySound != Long.MIN_VALUE && now >= lastAnySound && now - lastAnySound < SOUND_GAP_MS) {
			return false;
		}
		lastSound.put(key, now);
		lastAnySound = now;
		return true;
	}

	/**
	 * 지금 그릴 줄들. <b>새것이 앞</b>이다 — 0 번이 바닥(경험치 바에 가장 가까운 줄)이고 오래된
	 * 줄이 위로 밀려 올라간다.
	 */
	public List<Frame> frames(long now) {
		expire(now);
		List<Frame> frames = new ArrayList<>(lines.size());
		Iterator<Line> newestFirst = lines.descendingIterator();
		while (newestFirst.hasNext()) {
			Line line = newestFirst.next();
			long age = Math.max(0, now - line.startedAt());
			frames.add(new Frame(line, alphaAt(age), riseAt(age)));
		}
		return frames;
	}

	/** 나이에 따른 불투명도. 나타날 때 오르고, 끝에서 내린다. */
	static float alphaAt(long age) {
		if (age < 0 || age >= SHOW_MS) {
			return 0.0F;
		}
		float in = Math.min(1.0F, age / (float) FADE_IN_MS);
		float out = Math.min(1.0F, (SHOW_MS - age) / (float) FADE_OUT_MS);
		return MAX_ALPHA * Math.min(in, out);
	}

	/** 나타날 때 아래에서 제자리로 떠오르는 정도. 끝으로 갈수록 천천히 멈춘다. */
	static float riseAt(long age) {
		float t = Math.clamp(age / (float) FADE_IN_MS, 0.0F, 1.0F);
		return 1.0F - (1.0F - t) * (1.0F - t);
	}

	/** 다 된 줄을 뺀다. 시계가 거꾸로 가면(월드를 옮겼을 때) 모두 뺀다. */
	private void expire(long now) {
		lines.removeIf(line -> now < line.startedAt() || now - line.startedAt() >= SHOW_MS);
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/** 접속을 끊으면 비운다. 다음 서버의 첫 화면에 남의 증강 이름이 뜨면 안 된다. */
	public void clear() {
		lines.clear();
		lastShown.clear();
		lastSound.clear();
		lastAnySound = Long.MIN_VALUE;
	}
}
