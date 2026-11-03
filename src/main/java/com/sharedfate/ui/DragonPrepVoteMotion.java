package com.sharedfate.ui;

/**
 * 「드래곤전 대비」 투표 창의 움직임 계산(2026-11-04). 마인크래프트 클래스를 쓰지 않아 화면 없이 시험한다
 * ({@link PerkCardFocus} · {@link PerkCardDismiss} 와 같은 까닭).
 *
 * <h2>등장 — 어둠에서 떠올라 뒤집힌다</h2>
 * <p>사람 말 「룰렛 대신 새로운 애니메이션으로」. 카드는 처음에 <b>뒷면</b>(엔더의 눈 한 개)으로 아래 어둠 속에
 * 있다가 왼쪽부터 차례로 떠오르고, 다 떠오르면 가로로 접혔다 펴지며 앞면이 나온다. 접히는 폭은
 * {@code |cos(π·t)|} 라 반 바퀴에서 0 이 되고, 그 순간 앞뒤가 바뀐다.
 *
 * <p>모든 시간은 <b>서버가 정한 등장 길이</b>({@code DragonPrepVote.REVEAL_TICKS}) 안에 끝나야 한다 — 연출이
 * 길면 투표가 열린 뒤에도 카드가 뒤집히는 중이라 누를 수 없다({@code DragonPrepVoteMotionTest}).
 *
 * <h2>룰렛 — 정해진 카드에서 멈춘다</h2>
 * <p>동점이면 서버가 멈출 카드를 이미 정해 보낸다. 화면은 {@link PerkDrawRoll}(선택자 뽑기와 같은 감속)로 두
 * 장 사이를 오가는데, 넘기는 횟수를 미리 세어 <b>마지막 넘김이 정해진 카드에 닿도록 시작 카드를 고른다</b>.
 * 그렇지 않으면 멈추는 순간 반대 카드에서 뽑힌 카드로 강조가 튄다.
 */
public final class DragonPrepVoteMotion {
	/** 카드 사이의 시차(ms). 왼쪽 카드가 먼저 떠오른다. */
	public static final long STAGGER_MILLIS = 350L;
	/** 어둠에서 떠오르는 시간(ms). */
	public static final long RISE_MILLIS = 700L;
	/** 다 떠오른 뒤 뒤집히는 시간(ms). */
	public static final long FLIP_MILLIS = 600L;
	/** 접혔을 때도 남기는 가로 배율. 0 이면 한 프레임 동안 카드가 통째로 사라져 깜빡인다. */
	public static final float MIN_FLIP_SCALE = 0.04F;

	private DragonPrepVoteMotion() {
	}

	/** 이 카드가 떠오른 정도 0.0~1.0. 곡선을 먹인 뒤의 값이다. */
	public static float rise(long elapsedMillis, int index) {
		long local = elapsedMillis - index * STAGGER_MILLIS;
		if (local <= 0L) {
			return 0.0F;
		}
		if (local >= RISE_MILLIS) {
			return 1.0F;
		}
		float remaining = 1.0F - (float) local / RISE_MILLIS;
		// ease-out: 빠르게 올라와 제자리에서 부드럽게 멈춘다.
		return 1.0F - remaining * remaining * remaining;
	}

	/** 제자리에서 아래로 밀어 둘 거리. 떠오르며 0 이 된다. */
	public static int riseOffset(long elapsedMillis, int index, int distance) {
		return Math.round(Math.max(0, distance) * (1.0F - rise(elapsedMillis, index)));
	}

	/** 뒤집힌 정도 0.0~1.0. 다 떠오른 뒤에 시작한다. */
	public static float flip(long elapsedMillis, int index) {
		long local = elapsedMillis - index * STAGGER_MILLIS - RISE_MILLIS;
		if (local <= 0L) {
			return 0.0F;
		}
		return local >= FLIP_MILLIS ? 1.0F : (float) local / FLIP_MILLIS;
	}

	/** 카드를 그릴 가로 배율. 반 바퀴에서 가장 좁다. */
	public static float flipScale(long elapsedMillis, int index) {
		float scale = (float) Math.abs(Math.cos(Math.PI * flip(elapsedMillis, index)));
		return Math.max(MIN_FLIP_SCALE, scale);
	}

	/** 앞면이 보이는가. 반 바퀴를 넘기면 앞면이다. */
	public static boolean frontVisible(long elapsedMillis, int index) {
		return flip(elapsedMillis, index) >= 0.5F;
	}

	/** 이 카드가 앞뒤를 바꾸는 순간(ms). 화면이 이때 소리를 낸다. */
	public static long turnMillis(int index) {
		return index * STAGGER_MILLIS + RISE_MILLIS + FLIP_MILLIS / 2;
	}

	/** 카드 {@code count} 장의 등장이 다 끝나는 데 걸리는 시간(ms). */
	public static long totalMillis(int count) {
		return Math.max(0, count - 1) * STAGGER_MILLIS + RISE_MILLIS + FLIP_MILLIS;
	}

	/**
	 * 룰렛을 {@code totalTicks} 동안 돌리면 카드를 몇 번 넘기는가.
	 *
	 * <p>{@link PerkDrawRoll} 을 실제로 끝까지 돌려 센다 — 감속 셈을 여기서 따로 적으면 언젠가 둘이 갈린다.
	 */
	public static int rouletteSteps(int totalTicks) {
		PerkDrawRoll roll = new PerkDrawRoll(totalTicks);
		int steps = 0;
		for (int tick = 0; tick < Math.max(1, totalTicks); tick++) {
			if (roll.tick() == PerkDrawRoll.Event.NEXT_NAME) {
				steps++;
			}
		}
		return steps;
	}

	/**
	 * 처음 강조할 카드. 여기서 {@code steps} 번 넘기면 {@code winner} 에 닿는다.
	 *
	 * @param count 카드 장수. 1 이하면 언제나 0
	 */
	public static int rouletteStart(int winner, int steps, int count) {
		if (count <= 1) {
			return 0;
		}
		return Math.floorMod(winner - steps, count);
	}
}
