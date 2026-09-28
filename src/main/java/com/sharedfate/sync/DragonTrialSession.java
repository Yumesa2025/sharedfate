package com.sharedfate.sync;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 드래곤 전투 한 판의 상태. 타이머와 지금까지 고른 시련만 들고 있다.
 *
 * <h2>무엇을 모르는가</h2>
 *
 * <p>시련이 무슨 효과인지 모른다. 드래곤도 모르고 월드도 모른다. 여기서 아는 것은 「전투가
 * 언제 시작됐고, 다음 시련이 언제이고, 지금까지 무엇을 골랐는가」뿐이다. 그래서 월드 없이
 * 시험할 수 있다.
 *
 * <h2>왜 시간으로 세는가</h2>
 *
 * <p>화력이 센 팀은 빨리 끝내 시련을 덜 보고, 약한 팀은 시련이 쌓인다. 팀 실력을 재는 코드를
 * 따로 두지 않아도 난이도가 저절로 맞는다. 조절 장치를 둘 두면 「이 팀이 왜 이렇게 어려웠나」를
 * 나중에 분해할 수 없다.
 *
 * <h2>상한을 두는 이유</h2>
 *
 * <p>화력이 약한 팀이 30분째 시련 여섯 장을 쌓고 있으면 그건 이미 진 싸움인데, 상한이 없으면
 * 「못 이기는데 끝나지도 않는」 상태가 된다. 상한에 닿으면 더 나빠지지 않으므로 팀이 스스로
 * 판단할 수 있다.
 */
public final class DragonTrialSession {
	private final UUID teamId;
	/** 전투가 시작된 게임 시각. */
	private final long startedTick;
	private final int intervalTicks;
	private final int maxTrials;

	/** 고른 순서대로 쌓인다. 전투 중에는 풀리지 않는다. */
	private final List<String> chosen = new ArrayList<>();
	/** 다음 시련을 낼 시각. 상한에 닿으면 더 갱신하지 않는다. */
	private long nextTrialTick;
	/** 지금 선택을 기다리는 중인가. 기다리는 동안에는 타이머가 멈춘다. */
	private boolean awaitingChoice;

	public DragonTrialSession(UUID teamId, long startedTick, int intervalTicks, int maxTrials) {
		this.teamId = teamId;
		this.startedTick = startedTick;
		this.intervalTicks = Math.max(1, intervalTicks);
		this.maxTrials = Math.max(0, maxTrials);
		this.nextTrialTick = startedTick + this.intervalTicks;
	}

	public UUID teamId() {
		return teamId;
	}

	public long startedTick() {
		return startedTick;
	}

	public List<String> chosen() {
		return Collections.unmodifiableList(chosen);
	}

	public int trialCount() {
		return chosen.size();
	}

	public boolean awaitingChoice() {
		return awaitingChoice;
	}

	/** 전투가 얼마나 이어졌는가. 로그에 남겨 전투 길이를 가늠하는 데 쓴다. */
	public long elapsedTicks(long now) {
		return Math.max(0L, now - startedTick);
	}

	/**
	 * 지금 새 시련을 낼 때인가.
	 *
	 * <p>이미 선택을 기다리는 중이면 내지 않는다. 아무도 안 고르는 동안 타이머가 계속 돌면
	 * 선택창이 겹쳐 쌓인다.
	 */
	public boolean shouldOfferTrial(long now) {
		return !awaitingChoice && trialCount() < maxTrials && now >= nextTrialTick;
	}

	/** 선택을 기다리기 시작한다. 이 동안 타이머는 멈춘다. */
	public void beginChoice() {
		awaitingChoice = true;
	}

	/**
	 * 고른 시련을 쌓는다.
	 *
	 * <p>다음 시각은 <b>고른 순간</b>을 기준으로 다시 센다. 전투 중이라 선택이 늦어질 수 있는데,
	 * 시작 시각 기준으로 세면 늦게 고른 만큼 다음 시련이 곧바로 따라붙는다.
	 *
	 * @return 실제로 쌓였으면 참. 이미 고른 것이거나 상한을 넘었으면 거짓
	 */
	public boolean choose(@Nullable String trialId, long now) {
		if (trialId == null || trialId.isBlank() || chosen.contains(trialId)
				|| trialCount() >= maxTrials) {
			awaitingChoice = false;
			return false;
		}
		chosen.add(trialId);
		awaitingChoice = false;
		nextTrialTick = now + intervalTicks;
		return true;
	}

	/** 아무도 고르지 않아 접었을 때. 타이머만 다시 돌린다. */
	public void cancelChoice(long now) {
		awaitingChoice = false;
		nextTrialTick = now + intervalTicks;
	}

	/** 다음 시련까지 남은 틱. 상한에 닿았으면 {@code -1}. */
	public long ticksUntilNextTrial(long now) {
		if (trialCount() >= maxTrials) {
			return -1L;
		}
		return Math.max(0L, nextTrialTick - now);
	}

	/** 저장에서 되살릴 때 쓴다. 서버가 재시작해도 타이머와 누적이 이어져야 한다. */
	public void restore(List<String> alreadyChosen, long nextTick, boolean waiting) {
		chosen.clear();
		if (alreadyChosen != null) {
			for (String id : alreadyChosen) {
				if (id != null && !id.isBlank() && !chosen.contains(id)) {
					chosen.add(id);
				}
			}
		}
		nextTrialTick = nextTick;
		awaitingChoice = waiting;
	}

	/** 저장에 쓰는 값. 다음 시련 시각을 그대로 적어야 서버가 재시작해도 타이머가 이어진다. */
	public long nextTrialTickForSave() {
		return nextTrialTick;
	}
}
