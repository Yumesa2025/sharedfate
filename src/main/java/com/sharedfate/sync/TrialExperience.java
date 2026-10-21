package com.sharedfate.sync;

import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * 드래곤 시련 팀의 경험치 규칙 둘 — <b>41~50 곡선</b>과 <b>엔드 경험치 차단</b> (0.44.0-dev).
 *
 * <h2>곡선 — 레벨 40→50 을 바닐라 10→20 값으로</h2>
 * <p>시련 팀은 레벨 상한이 50 이고({@link StatMirror#TRIAL_LEVEL_CAP}) 45·50 에서 「드래곤전 대비」
 * 증강을 고른다. 바닐라 곡선 그대로면 40 → 50 에 약 2425 점이 들어 엔드에 가기 전에 닿기 어렵다.
 * 사람이 정한 값은 「레벨 L(40 ≤ L < 50)의 다음 레벨 비용 = 바닐라 레벨 L − 30 의 비용」 — 27 · 29 ·
 * 31 · 33 · 35 · 37 · 42 · 47 · 52 · 57, 합 <b>390</b> 이다. 40 아래는 바닐라와 한 점도 다르지 않다.
 *
 * <h2>왜 서버의 {@code Player.getXpNeededForNextLevel} 까지 바꾸는가</h2>
 * <p>팀 경험치는 {@link StatMirror} 가 팀원의 「레벨 + 막대」를 점수로 바꿔 차이를 모으는 방식이다.
 * 바닐라 {@code giveExperiencePoints} 는 막대를 {@code 받은 점수 ÷ getXpNeededForNextLevel()} 만큼
 * 채우므로, 공유 셈만 새 곡선으로 하고 바닐라는 옛 곡선으로 채우면 레벨 42 에서 구슬 31 점이 막대를
 * 31/220 만 채워 <b>점수가 7 분의 1 로 줄어든다.</b> 그래서 같은 곡선을 두 곳이 함께 쓴다 —
 * {@code PlayerExperienceRulesMixin} 이 서버 플레이어의 필요 점수를, {@link StatMirror} 가 점수 ↔
 * 레벨 환산을 이 파일의 식으로 한다. 클라이언트는 서버가 보내는 레벨·막대를 그대로 그리므로 곡선을
 * 몰라도 경험치 바가 어긋나지 않는다.
 *
 * <h2>엔드 경험치 차단</h2>
 * <p>시련 팀 팀원은 <b>엔드 차원에 있는 동안</b> 경험치를 얻지 못한다(레벨과 무관 — 사람 결정
 * 「낮은 레벨로 들어간 건 자업자득」). 막는 자리는 둘이다.
 * <ul>
 *   <li>플레이어 쪽 입구 — {@code Player.giveExperiencePoints}(양수)와
 *       {@code Player.giveExperienceLevels}(양수). 경험치 구슬(채굴·처치·병·화로·거래·번식·낚시·발전과제가
 *       모두 구슬이나 이 둘로 온다)과 {@code /xp} 가 여기를 지난다</li>
 *   <li>공유 풀 쪽 그물 — {@link StatMirror} 가 팀원의 경험치 증가를 모을 때 엔드에 선 사람의 증가는
 *       0 으로 자른다. 위 입구를 모르는 길(다른 모드 · 앞으로 생길 경로)이 레벨을 올려도 다음 바퀴에 팀
 *       값으로 되돌아간다</li>
 * </ul>
 * <p>줄어드는 쪽(인챈트 · 모루)은 막지 않는다. 수선(mending)은 구슬 값을 내구도로 바꾸는 것이라 그대로
 * 둔다 — 남은 몫이 경험치로 들어오는 자리만 막힌다.
 */
public final class TrialExperience {
	/** 곡선이 바뀌기 시작하는 레벨. 이 아래는 바닐라다. */
	public static final int CURVE_FROM = 40;
	/** 바꾼 구간이 바닐라의 어디를 빌려 오는가 — 레벨 L 의 비용은 바닐라 레벨 L − 이 값의 비용이다. */
	public static final int VANILLA_OFFSET = 30;

	private TrialExperience() {
	}

	// ------------------------------------------------------------------ 곡선 (월드 없이 답이 정해진다)

	/** 바닐라 {@code Player.getXpNeededForNextLevel} 과 같은 식. */
	public static int vanillaNeeded(int level) {
		int safe = Math.max(0, level);
		if (safe >= 30) {
			return 112 + (safe - 30) * 9;
		}
		if (safe >= 15) {
			return 37 + (safe - 15) * 5;
		}
		return 7 + safe * 2;
	}

	/** 바닐라에서 레벨 {@code level} 의 막대가 빈 지점까지 모인 점수. */
	public static long vanillaBase(int level) {
		long safe = Math.max(0, level);
		if (safe <= 15) {
			return safe * safe + 6L * safe;
		} else if (safe <= 30) {
			long steps = safe - 15L;
			return 315L + 37L * steps + 5L * steps * (steps - 1L) / 2L;
		} else {
			long steps = safe - 30L;
			return 1395L + 112L * steps + 9L * steps * (steps - 1L) / 2L;
		}
	}

	/**
	 * 레벨 {@code level} 에서 다음 레벨까지 드는 점수.
	 *
	 * @param trial 드래곤 시련 팀의 곡선인가. 거짓이면 바닐라 그대로다
	 */
	public static int needed(int level, boolean trial) {
		if (trial && level >= CURVE_FROM) {
			return vanillaNeeded(level - VANILLA_OFFSET);
		}
		return vanillaNeeded(level);
	}

	/** 레벨 {@code level} 의 막대가 빈 지점까지 모인 점수. */
	public static long base(int level, boolean trial) {
		int safe = Math.max(0, level);
		if (trial && safe > CURVE_FROM) {
			// 40 까지는 바닐라, 그 위는 바닐라 10 부터의 비용을 그대로 이어 붙인다.
			return vanillaBase(CURVE_FROM) + vanillaBase(safe - VANILLA_OFFSET)
					- vanillaBase(CURVE_FROM - VANILLA_OFFSET);
		}
		return vanillaBase(safe);
	}

	/** 레벨과 막대(0~1)를 점수로. 막대 몫은 반올림한다(예전 {@code StatMirror} 와 같은 식). */
	public static int pointsFor(int level, float progress, boolean trial) {
		int safe = Math.max(0, level);
		float bar = Float.isFinite(progress) ? Math.max(0.0F, Math.min(1.0F, progress)) : 0.0F;
		long total = base(safe, trial) + Math.round(bar * needed(safe, trial));
		return (int) Math.min(Integer.MAX_VALUE, total);
	}

	/** 점수에 해당하는 레벨. 막대가 비는 지점까지 모은 점수가 그 이하인 가장 높은 레벨이다. */
	public static int levelFor(int points, boolean trial) {
		int safe = Math.max(0, points);
		int low = 0;
		int high = 1;
		while (high < 100_000 && base(high, trial) <= safe) {
			high *= 2;
		}
		while (low + 1 < high) {
			int middle = low + (high - low) / 2;
			if (base(middle, trial) <= safe) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return low;
	}

	/** 점수를 그 레벨의 막대(0~1)로. */
	public static float progressFor(int points, boolean trial) {
		int safe = Math.max(0, points);
		int level = levelFor(safe, trial);
		return (float) (safe - base(level, trial)) / needed(level, trial);
	}

	// ------------------------------------------------------------------ 누구에게 걸리는가

	/** 이 팀이 시련 곡선을 쓰는가. */
	public static boolean usesTrialCurve(@Nullable TeamState state) {
		return state != null && state.dragonTrialsEnabled;
	}

	/** 이 플레이어(서버)가 시련 곡선을 쓰는가. 팀이 없으면 바닐라다. */
	public static boolean usesTrialCurve(@Nullable ServerPlayer player) {
		return player != null && usesTrialCurve(TeamLookup.stateOf(player.getUUID()));
	}

	/**
	 * 지금 이 사람이 경험치를 얻으면 안 되는가 — 시련 팀이고 엔드에 서 있다.
	 *
	 * <p>판정은 {@link #blocksGain(boolean, boolean)} 하나다. 이 메서드는 월드에서 두 사실을 찾아
	 * 넘길 뿐이다.
	 */
	public static boolean blocksGain(@Nullable ServerPlayer player) {
		if (player == null) {
			return false;
		}
		return blocksGain(usesTrialCurve(TeamLookup.stateOf(player.getUUID())),
				player.level().dimension() == Level.END);
	}

	/** 같은 판정을 팀 상태를 이미 든 자리에서. */
	public static boolean blocksGain(@Nullable TeamState state, @Nullable ServerPlayer player) {
		return player != null && blocksGain(usesTrialCurve(state),
				player.level().dimension() == Level.END);
	}

	/** 순수 판정. 시련 팀이면서 엔드에 있으면 막는다. */
	public static boolean blocksGain(boolean trialTeam, boolean inEnd) {
		return trialTeam && inEnd;
	}

	/**
	 * 공유 풀에 모을 경험치 변화량. 막힌 사람의 <b>증가</b>는 0 으로 자르고 감소는 그대로 둔다.
	 *
	 * @param blocked {@link #blocksGain} 의 답
	 */
	public static long admitted(long delta, boolean blocked) {
		return blocked ? Math.min(0L, delta) : delta;
	}
}
