package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Trial;
import com.sharedfate.sync.TrialCatalog.Trigger;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 시련을 뽑는 룰렛.
 *
 * <h2>왜 고르게 하지 않는가</h2>
 *
 * <p>⚠ <b>「새 화면이 필요해서」가 아니다.</b> 여기에는 한동안 「셋을 띄우려면 새 화면이
 * 필요하고 그러면 규약이 30 으로 올라간다, 룰렛은 전부 바닐라 패킷이라 규약이 29 그대로다」라고
 * 적혀 있었다. <b>둘 다 지금은 사실이 아니다.</b> 화면은 이미 있고({@code TrialRouletteScreen} ·
 * {@code TrialRoulettePayload}) 규약은 <b>33</b> 이다 — 드래곤이 때리는 중에 화면 위로 지나가는
 * 타이틀 글자가 읽히지 않아 판을 멈추고 화면을 띄우기로 한 그때 올라갔다
 * ({@link TrialCatalog} 클래스 설명). 그러니 「규약을 아낀다」는 이제 <b>고르게 하지 않는
 * 근거가 못 된다.</b>
 *
 * <p>근거로 남은 것은 둘이고, 둘 다 화면과 무관하다.
 *
 * <p>첫째, <b>카드 한 장부터 굴러간다.</b> 고르는 방식은 자리당 최소 세 장을 요구하는데,
 * 카드를 채워 가는 동안에는 그 조건을 맞출 수 없다.
 *
 * <p>둘째, <b>시련에 보상을 붙이지 않기로 한 것과 맞는다.</b> 셋 다 나쁘기만 한 것을 고르게
 * 하면 매번 「가장 덜 나쁜 것」을 찾는 계산이 되는데, 뽑기는 그 문제가 아예 없다. 이쪽이
 * 지금은 <b>주된 근거</b>다.
 *
 * <h2>결과를 먼저 정하고 연출을 돌린다</h2>
 *
 * <p>멈출 카드를 맨 처음에 뽑아 두고, 프레임을 거꾸로 세어 <b>마지막 칸이 그 카드가 되도록</b>
 * 순환 시작점을 맞춘다. 연출 도중에 결과가 바뀌지 않으므로 「돌다가 바뀌었다」는 의심이 생길
 * 여지가 없다.
 *
 * <h2>월드를 모른다</h2>
 *
 * <p>이 클래스는 화면에 무엇을 언제 띄울지만 계산한다. 플레이어도 레벨도 모르기 때문에
 * <b>월드 없이 시험할 수 있다.</b> 룰렛을 {@code DragonTrialManager} 안에 숨겨 두면 시험이
 * 닿지 않는데, 이 저장소는 「믹스인 안 private 이라 시험이 못 닿았고, 못 닿아서 회귀가
 * 조용했다」를 이미 겪었다.
 */
public final class TrialRoulette {

	/**
	 * 칸이 바뀌는 간격(틱). 뒤로 갈수록 느려진다.
	 *
	 * <p>일정한 간격으로 돌다 뚝 멈추면 뽑는 느낌이 나지 않는다. 총 {@value #TOTAL_TICKS} 틱,
	 * 약 4초다 — 사람이 「돌고 있다」를 알아보고 마지막에 「여기서 멈추나」를 지켜볼 만한 길이다.
	 */
	private static final int[] GAPS = {2, 2, 2, 2, 3, 3, 3, 4, 4, 5, 6, 7, 9, 11, 14};

	/** 연출 전체 길이. {@link #GAPS} 의 합이다. */
	public static final int TOTAL_TICKS = 77;

	private final Trigger trigger;
	private final List<Trial> candidates;
	private final Trial result;
	private final long startedTick;
	/** 각 칸이 나타나는 경과 틱. */
	private final int[] frameAt;
	/** 마지막에 보여 준 칸 번호. 같은 칸을 두 번 띄우지 않으려고 기억한다. */
	private int shownFrame = -1;

	private TrialRoulette(Trigger trigger, List<Trial> candidates, Trial result, long startedTick) {
		this.trigger = trigger;
		this.candidates = candidates;
		this.result = result;
		this.startedTick = startedTick;
		this.frameAt = frameStarts();
	}

	/**
	 * 룰렛을 연다.
	 *
	 * @param candidates 뽑힐 수 있는 카드들. 비어 있으면 {@code null} 을 돌려준다 —
	 *                   <b>풀이 마른 것은 오류가 아니다.</b> 카드를 채워 가는 동안에는 정상이다
	 * @param random     결과를 뽑는 난수. {@code Math.random()} 을 쓰지 않는 것은 서버가
	 *                   난수원을 쥐고 있어야 재현과 시험이 되기 때문이다
	 */
	public static @Nullable TrialRoulette open(@Nullable Trigger trigger,
			@Nullable List<Trial> candidates, long startedTick, @Nullable RandomSource random) {
		if (trigger == null || candidates == null || candidates.isEmpty()) {
			return null;
		}
		List<Trial> copy = List.copyOf(candidates);
		int index = random == null ? 0 : random.nextInt(copy.size());
		return new TrialRoulette(trigger, copy, copy.get(index), startedTick);
	}

	/** 시험이 결과를 못박고 싶을 때. 난수를 쓰지 않는다. */
	public static @Nullable TrialRoulette openWith(@Nullable Trigger trigger,
			@Nullable List<Trial> candidates, long startedTick, @Nullable Trial result) {
		if (trigger == null || candidates == null || candidates.isEmpty() || result == null
				|| !candidates.contains(result)) {
			return null;
		}
		return new TrialRoulette(trigger, List.copyOf(candidates), result, startedTick);
	}

	public Trigger trigger() {
		return trigger;
	}

	/** 멈출 카드. 연출이 도는 동안에도 이미 정해져 있다. */
	public Trial result() {
		return result;
	}

	public int frameCount() {
		return GAPS.length;
	}

	/** 연출이 끝났는가. 끝난 틱부터 참이다. */
	public boolean finished(long now) {
		return elapsed(now) >= TOTAL_TICKS;
	}

	/**
	 * 이번 틱에 새로 띄울 카드. 칸이 바뀌지 않았으면 {@code null} 이다.
	 *
	 * <p>호출자는 {@code null} 이 아닐 때만 화면과 소리를 낸다. 매 틱 타이틀을 다시 보내면
	 * 글자가 떨리고 소리가 뭉개진다.
	 */
	public @Nullable Trial advance(long now) {
		int frame = frameOf(elapsed(now));
		if (frame < 0 || frame == shownFrame) {
			return null;
		}
		shownFrame = frame;
		return candidateAt(frame);
	}

	/**
	 * 그 칸에 보이는 카드.
	 *
	 * <p>마지막 칸이 반드시 {@link #result} 가 되도록 시작점을 거꾸로 맞춘다. 후보가 하나뿐이면
	 * 모든 칸이 같은 카드다 — 그것도 「이것밖에 없다」를 보여 주는 정직한 연출이다.
	 */
	public Trial candidateAt(int frame) {
		int size = candidates.size();
		int last = GAPS.length - 1;
		int resultIndex = candidates.indexOf(result);
		int offset = Math.floorMod(resultIndex - last, size);
		return candidates.get(Math.floorMod(offset + frame, size));
	}

	/** 경과 틱이 몇 번째 칸에 드는가. 아직 첫 칸도 안 왔으면 -1. */
	public int frameOf(long elapsedTicks) {
		if (elapsedTicks < 0) {
			return -1;
		}
		int frame = -1;
		for (int index = 0; index < frameAt.length; index++) {
			if (elapsedTicks >= frameAt[index]) {
				frame = index;
			}
		}
		return frame;
	}

	/**
	 * 복원 직후에는 {@code now} 가 시작 틱보다 작을 수 있다. 음수 경과로 연출이 영영 안 끝나는
	 * 것을 막는다.
	 */
	private long elapsed(long now) {
		return Math.max(0L, now - startedTick);
	}

	/** 각 칸이 나타나는 경과 틱. 첫 칸은 0 이다. */
	private static int[] frameStarts() {
		int[] starts = new int[GAPS.length];
		int running = 0;
		for (int index = 0; index < GAPS.length; index++) {
			starts[index] = running;
			running += GAPS[index];
		}
		return starts;
	}

	/** 칸 사이 간격. 시험이 「뒤로 갈수록 느려진다」를 확인할 때 쓴다. */
	public static int[] gaps() {
		return GAPS.clone();
	}
}
