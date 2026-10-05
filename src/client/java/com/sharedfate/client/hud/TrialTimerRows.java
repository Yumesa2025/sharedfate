package com.sharedfate.client.hud;

import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>글자와 판정</b> — 어느 줄이 위에 오고, 무슨 상태이고, 무엇을 적고,
 * 바를 얼마나 채우는가. 그리는 일은 {@link TrialTimersHud}, 자리는 {@link TrialTimersLayout} 이다.
 *
 * <h2>사람이 승인한 화면 (2026-10-05)</h2>
 *
 * <p>사람 말: <b>「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게 오른쪽
 * 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」</b> — 예시 화면을 보고 「이대로 진행」.
 * 한 줄은 <b>색 점 + 이름 + 남은 초 + 남은 비율 바</b>이고, 상태가 셋(대기 · 임박 · 예고 중)이다.
 * 제안 화면에 없던 둘(진행 중 · 체력형)은 서버 묶음이 정한 모양이다({@code TrialTimersPayload.Entry}).
 *
 * <h2>여기 있는 것은 전부 순수 함수다</h2>
 *
 * <p>화면은 시험할 수 없으므로 <b>판단을 전부 여기로 뺐다.</b> 글꼴도 직접 쥐지 않고 폭을 재는 함수를
 * 받는다 — 시험이 가짜 폭으로 자르기를 못박을 수 있게.
 */
public final class TrialTimerRows {

	/**
	 * 이 틱 이하로 남으면 「임박」. 100틱 = 5초.
	 *
	 * <p>사람이 승인한 화면의 문턱이다(「임박(남은 ≤ 100틱)」). 서버 묶음 설명도 같은 값을 적는다
	 * ({@code Entry.MODE_COUNTDOWN} 설명의 「100틱(5초) 이하면 임박」).
	 */
	public static final int IMMINENT_TICKS = 100;

	/** 1초의 틱 수. 숫자를 초로 바꿀 때만 쓴다. */
	private static final float TICKS_PER_SECOND = 20.0F;

	/** 「임박」 꼬리표. */
	public static final String TAG_IMMINENT = "임박";
	/** 「예고 중」 꼬리표 — 바닥 표식이 이미 깔렸다. */
	public static final String TAG_WARNING = "예고 중";
	/** 「진행 중」 꼬리표 — 지금 벌어지는 중이다. 사람 화면의 「이름 · 진행 중」. */
	public static final String TAG_ACTIVE = "진행 중";

	/** 일반 전투의 패널 제목. */
	public static final String HEADER_FIGHT = "다가오는 패턴";
	/**
	 * 최후의 저항의 패널 제목. 거기 줄은 패턴이 아니라 안전지대 · 번개 · 오브젝트 파도라서 사람 화면이
	 * 「다가오는 것」으로 바꿔 적었다.
	 */
	public static final String HEADER_LAST_STAND = "다가오는 것";

	/** 시전 바 「진행 중」 제목에 붙는 말. 서버({@code TrialTimers.RUNNING_SUFFIX})와 같은 글자다. */
	static final String RUNNING_SUFFIX = " · " + TAG_ACTIVE;

	/** 잘린 글자 끝에 붙이는 말줄임표. */
	static final String ELLIPSIS = "…";

	private TrialTimerRows() {
	}

	/** 한 줄의 상태. 그리는 모양(배경 · 테두리 · 꼬리표)이 이것 하나로 정해진다. */
	public enum State {
		/** 아직 멀다. */
		WAITING,
		/** 5초 안에 온다 — 배경이 밝아지고 흰 바탕 「임박」. */
		IMMINENT,
		/** 바닥 표식이 이미 깔렸다 — 종류 색 배경 · 테두리 · 「예고 중」. */
		WARNING,
		/** 지금 벌어지는 중 — 남은 시간은 끝날 때까지. 「진행 중」. */
		ACTIVE,
		/** 체력으로 정해진다 — 초 대신 「체력 25%」. */
		HEALTH
	}

	/**
	 * 지금 그릴 한 줄.
	 *
	 * @param entry     서버가 보낸 줄 그대로
	 * @param remaining 받은 뒤 흐른 틱을 뺀 <b>지금</b> 남은 틱. 0 에서 멈춘다. 체력형이면 0
	 * @param state     상태
	 */
	public record Row(Entry entry, int remaining, State state) {
	}

	/**
	 * 줄의 상태.
	 *
	 * <p>「예고 중」이 「임박」보다 먼저다 — 둘 다 참이면 바닥 표식이 이미 깔린 쪽이 더 급한 말이다.
	 * {@code warnTicks} 가 0 이면 바닥 예고가 없는 것이라 「예고 중」이 되지 않는다(사람 화면:
	 * 「MODE_COUNTDOWN 이고 남은 ≤ warnTicks, warnTicks > 0」).
	 *
	 * <p>모르는 {@code mode} 는 시간형으로 본다 — 새 서버가 보낸 새 모양에 터지지 않고, 숫자는 그래도
	 * 남은 틱이다.
	 */
	public static State stateOf(byte mode, int remaining, int warnTicks) {
		if (mode == Entry.MODE_HEALTH) {
			return State.HEALTH;
		}
		if (mode == Entry.MODE_ACTIVE) {
			return State.ACTIVE;
		}
		if (warnTicks > 0 && remaining <= warnTicks) {
			return State.WARNING;
		}
		return remaining <= IMMINENT_TICKS ? State.IMMINENT : State.WAITING;
	}

	/**
	 * 받은 줄들을 지금 그릴 차례로 만든다 — <b>남은 시간이 짧은 줄이 위, 체력형은 맨 아래</b>.
	 *
	 * <p>서버도 같은 차례로 담아 보내지만({@code TrialTimers.sorted}) 그것은 보낸 그 틱의 차례다.
	 * 받는 쪽이 줄여 가는 동안 0 에 닿은 줄끼리 순서가 섞일 수 있어 여기서 다시 세운다. 같은 남은
	 * 시간이면 열쇠 순 — 서버와 같은 셈이라 묶음이 갈릴 때 줄이 자리를 바꾸며 깜빡이지 않는다.
	 *
	 * @param elapsed 묶음을 받은 뒤 흐른(얼지 않은) 클라이언트 틱
	 */
	public static List<Row> rows(List<Entry> entries, int elapsed) {
		List<Row> rows = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			int remaining = entry.mode() == Entry.MODE_HEALTH
					? 0 : remainingAfter(entry.remainingTicks(), elapsed);
			rows.add(new Row(entry, remaining, stateOf(entry.mode(), remaining, entry.warnTicks())));
		}
		rows.sort(Comparator
				.comparingInt((Row row) -> row.state() == State.HEALTH ? 1 : 0)
				.thenComparingInt(Row::remaining)
				.thenComparing(row -> row.entry().id()));
		return rows;
	}

	/** 받은 남은 틱에서 흐른 틱을 뺀 값. <b>0 에서 멈춘다</b> — 새 묶음이 곧 온다. */
	public static int remainingAfter(int sentRemaining, int elapsed) {
		return Math.max(0, sentRemaining - Math.max(0, elapsed));
	}

	/**
	 * 그릴 때 쓰는 남은 틱 — 틱 사이를 부분 틱으로 메워 숫자가 0.05초씩 매끄럽게 준다.
	 *
	 * @param remaining 지금 남은 틱({@link #remainingAfter})
	 * @param partial   지난 틱부터 지금까지(0~1)
	 * @param counting  지금 시계가 도는가. 얼었거나 일시정지면 부분 틱을 빼지 않는다 — 빼면 멈춘
	 *                  화면에서 숫자가 한 틱 덜 남은 채로 서 있다
	 */
	public static float interpolated(int remaining, float partial, boolean counting) {
		if (!counting || remaining <= 0) {
			return Math.max(0, remaining);
		}
		float clamped = Math.max(0.0F, Math.min(1.0F, partial));
		return Math.max(0.0F, remaining - clamped);
	}

	/**
	 * 「12.3초」. 0.1초 단위로 <b>올림</b>한다.
	 *
	 * <p>올리는 까닭 — 내리면 1틱(0.05초) 남은 사건이 「0.0초」로 떠 「이미 지났다」로 읽힌다. 올리면
	 * 「0.0초」는 정말 0 일 때만 나온다. 소수점은 언제나 점이다({@link Locale#ROOT}) — 쉼표를 쓰는
	 * 언어 설정의 기계에서 「12,3초」가 되지 않게.
	 */
	public static String secondsText(float ticks) {
		float safe = Float.isNaN(ticks) ? 0.0F : Math.max(0.0F, ticks);
		// 0.1초 = 2틱. 부동소수 잔차(12.3 이 12.300001 로 오는 것)에 한 칸 더 올라가지 않게 아주 작게 뺀다.
		long tenths = (long) Math.ceil(safe * 10.0F / TICKS_PER_SECOND - 1.0E-4F);
		return String.format(Locale.ROOT, "%d.%d초", tenths / 10, tenths % 10);
	}

	/**
	 * 줄여 가는 바의 비율 — 남은/전체. 0~1.
	 *
	 * <p>분모가 0 이면 0 이다(서버는 {@code totalTicks ≥ remaining} 을 보장하지만 둘 다 0 일 수 있다).
	 */
	public static float countdownRatio(float remaining, int total) {
		if (total <= 0) {
			return 0.0F;
		}
		return clamp01(remaining / total);
	}

	/** 한 줄의 바. 체력형은 {@code fill}(1 이면 문턱까지 멀다), 그 밖은 남은/전체로 줄어든다. */
	public static float rowRatio(Row row, float displayRemaining) {
		if (row.state() == State.HEALTH) {
			float fill = row.entry().fill();
			return Float.isNaN(fill) ? 0.0F : clamp01(fill);
		}
		return countdownRatio(displayRemaining, row.entry().totalTicks());
	}

	/** 한 줄의 오른쪽 글자 — 체력형은 서버가 적어 보낸 글자, 그 밖은 남은 초. */
	public static String rightText(Row row, float displayRemaining) {
		if (row.state() == State.HEALTH) {
			return row.entry().valueText();
		}
		return secondsText(displayRemaining);
	}

	/** 한 줄의 꼬리표. 대기와 체력형은 없다. */
	public static @Nullable String tagOf(State state) {
		return switch (state) {
			case IMMINENT -> TAG_IMMINENT;
			case WARNING -> TAG_WARNING;
			case ACTIVE -> TAG_ACTIVE;
			case WAITING, HEALTH -> null;
		};
	}

	/**
	 * 시전 바의 비율. <b>예고 중은 차오르고</b>(경과/전체 — 「곧 터진다」가 차오르는 모양이다),
	 * 쉬는 중과 진행 중은 줄어든다(남은/전체). 모르는 상태는 쉬는 중으로 본다.
	 */
	public static float castRatio(byte state, float remaining, int total) {
		if (total <= 0) {
			return 0.0F;
		}
		if (state == Cast.STATE_WARNING) {
			return clamp01((total - remaining) / total);
		}
		return countdownRatio(remaining, total);
	}

	/**
	 * 시전 바 왼쪽 제목.
	 *
	 * <p>⚠ 진행 중 제목에는 <b>서버가 이미 「 · 진행 중」을 붙여 보낸다</b>({@code TrialTimers.castOf}
	 * 의 {@code RUNNING_SUFFIX}). 여기서 또 붙이면 「공허 흡입 · 진행 중 · 진행 중」이 된다. 그래서
	 * 붙어 있지 않을 때만 붙인다 — 서버가 언젠가 붙이기를 그만둬도 사람 화면대로 나온다.
	 */
	public static String castTitle(Cast cast) {
		if (cast.state() == Cast.STATE_RUNNING && !cast.title().endsWith(TAG_ACTIVE)) {
			return cast.title() + RUNNING_SUFFIX;
		}
		return cast.title();
	}

	/** 시전 바 오른쪽 시간. 예고 중이면 「2.0초 뒤 발동」, 그 밖은 남은 초. */
	public static String castTimeText(byte state, float displayRemaining) {
		String seconds = secondsText(displayRemaining);
		return state == Cast.STATE_WARNING ? seconds + " 뒤 발동" : seconds;
	}

	/** 패널 제목. 최후의 저항(시전 바가 있는 판)이면 「다가오는 것」. */
	public static String header(boolean lastStand) {
		return lastStand ? HEADER_LAST_STAND : HEADER_FIGHT;
	}

	/**
	 * 패널 머리줄 오른쪽 작은 글. 일반 전투는 줄 수, 최후의 저항은 「지금 체력 N%」.
	 *
	 * <p>체력은 <b>올림</b>이다. 오브젝트 파도 줄이 「체력 25%」라고 적는데, 내리면 25.4% 에서 이미
	 * 「지금 체력 25%」가 떠 문턱을 넘은 것처럼 읽힌다. 올리면 25% 가 뜨는 때가 곧 문턱이다.
	 *
	 * @param healthRatio 드래곤 체력 비율(0~1). 모르면 {@code NaN} — 그때 최후의 저항은 빈 글자
	 */
	public static String headerNote(boolean lastStand, int rowCount, float healthRatio) {
		if (!lastStand) {
			return rowCount + "개";
		}
		if (Float.isNaN(healthRatio)) {
			return "";
		}
		int percent = (int) Math.ceil(clamp01(healthRatio) * 100.0F - 1.0E-4F);
		return "지금 체력 " + percent + "%";
	}

	/**
	 * 폭에 맞춰 자른다. 넘치면 뒤를 자르고 「…」를 붙이며, 「…」조차 안 들어가면 빈 글자다.
	 *
	 * @param measure 글자 폭(GUI 픽셀). 그릴 때는 {@code font::width}
	 */
	public static String fit(String text, int maxWidth, ToIntFunction<String> measure) {
		if (text == null || text.isEmpty() || maxWidth <= 0) {
			return "";
		}
		if (measure.applyAsInt(text) <= maxWidth) {
			return text;
		}
		// 글자 단위로 줄인다. 한 줄 이름은 48자가 상한이라(MAX_LABEL_LENGTH) 앞에서부터 세도 싸다.
		for (int end = text.length() - 1; end > 0; end--) {
			// 서로게이트 쌍을 가르지 않는다.
			if (Character.isLowSurrogate(text.charAt(end))) {
				continue;
			}
			String candidate = text.substring(0, end).stripTrailing() + ELLIPSIS;
			if (measure.applyAsInt(candidate) <= maxWidth) {
				return candidate;
			}
		}
		return measure.applyAsInt(ELLIPSIS) <= maxWidth ? ELLIPSIS : "";
	}

	/**
	 * 한 줄 안에서 이름 · 꼬리표가 쓸 수 있는 폭을 나눈다.
	 *
	 * <p>오른쪽 시간(또는 체력 글자)은 <b>언제나</b> 그린다 — 이 화면이 답하는 것이 「몇 초 뒤」다.
	 * 남는 폭에 꼬리표와 이름을 넣되, 꼬리표를 넣으면 이름이 {@link #MIN_LABEL_WIDTH} 보다 좁아지는
	 * 좁은 화면에서는 <b>꼬리표를 뺀다</b> — 상태는 줄 배경 · 테두리가 함께 말하고 있고, 이름이 안
	 * 보이면 무엇이 오는지를 모른다.
	 *
	 * @param available 색 점 뒤부터 오른쪽 끝까지의 폭
	 * @return 그릴 이름(잘렸을 수 있음)과 꼬리표(뺐으면 {@code null})
	 */
	public static RowText rowText(String label, @Nullable String tag, String right, int available,
			ToIntFunction<String> measure) {
		int rightWidth = measure.applyAsInt(right);
		int labelSpace = available - rightWidth - RIGHT_GAP;
		String keptTag = tag;
		if (keptTag != null) {
			int tagSpace = measure.applyAsInt(keptTag) + TAG_PADDING * 2 + TAG_GAP;
			int afterTag = labelSpace - tagSpace;
			int wanted = measure.applyAsInt(label);
			if (afterTag < Math.min(wanted, MIN_LABEL_WIDTH)) {
				keptTag = null;
			} else {
				labelSpace = afterTag;
			}
		}
		return new RowText(fit(label, labelSpace, measure), keptTag);
	}

	/** 이름이 이보다 좁아지면 꼬리표를 뺀다(GUI 픽셀). 한글 세 글자쯤이다. */
	public static final int MIN_LABEL_WIDTH = 27;
	/** 이름(또는 꼬리표)과 오른쪽 시간 사이의 틈. */
	public static final int RIGHT_GAP = 4;
	/** 이름과 꼬리표 사이의 틈. */
	public static final int TAG_GAP = 3;
	/** 꼬리표 글자 양옆 여백. */
	public static final int TAG_PADDING = 2;

	/**
	 * 한 줄의 글자.
	 *
	 * @param label 그릴 이름
	 * @param tag   그릴 꼬리표. 없으면 {@code null}
	 */
	public record RowText(String label, @Nullable String tag) {
	}

	private static float clamp01(float value) {
		if (Float.isNaN(value)) {
			return 0.0F;
		}
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
