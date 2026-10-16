package com.sharedfate.ui;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 드래곤 시련 단추 둘레를 도는 빛의 자리 계산을 못박는 시험. */
class DragonTrialGlowTest {

	/** 팀 만들기 탭의 반쪽 단추 크기. {@code TeamScreen} 의 {@code PANEL_WIDTH / 2 − 2} × 18 이다. */
	private static final int WIDTH = 148;
	private static final int HEIGHT = 18;

	@Test
	void 둘레는_모서리를_한_번씩만_센다() {
		assertEquals(2 * WIDTH + 2 * HEIGHT - 4, DragonTrialGlow.perimeter(WIDTH, HEIGHT));
		assertEquals(4, DragonTrialGlow.perimeter(2, 2));
		assertEquals(0, DragonTrialGlow.perimeter(1, 10));
	}

	@Test
	void 한_바퀴의_칸은_모두_테두리_위에_있고_겹치지_않는다() {
		int perimeter = DragonTrialGlow.perimeter(WIDTH, HEIGHT);
		Set<DragonTrialGlow.Pixel> seen = new HashSet<>();
		for (int index = 0; index < perimeter; index++) {
			DragonTrialGlow.Pixel pixel = DragonTrialGlow.pixelAt(index, WIDTH, HEIGHT);
			boolean onEdge = pixel.dx() == 0 || pixel.dx() == WIDTH - 1
					|| pixel.dy() == 0 || pixel.dy() == HEIGHT - 1;
			assertTrue(onEdge, index + "번 칸 " + pixel + " 이 테두리 밖이다");
			assertTrue(pixel.dx() >= 0 && pixel.dx() < WIDTH
					&& pixel.dy() >= 0 && pixel.dy() < HEIGHT, index + "번 칸이 사각형 밖이다");
			assertTrue(seen.add(pixel), index + "번 칸 " + pixel + " 을 두 번 셌다");
		}
		assertEquals(perimeter, seen.size());
	}

	@Test
	void 이웃한_칸은_늘_한_칸씩_붙어_있다() {
		// 빛이 모서리를 돌 때 칸을 건너뛰면 머리가 순간이동한 것처럼 보인다.
		int perimeter = DragonTrialGlow.perimeter(WIDTH, HEIGHT);
		for (int index = 0; index < perimeter; index++) {
			DragonTrialGlow.Pixel a = DragonTrialGlow.pixelAt(index, WIDTH, HEIGHT);
			DragonTrialGlow.Pixel b = DragonTrialGlow.pixelAt(index + 1, WIDTH, HEIGHT);
			int distance = Math.abs(a.dx() - b.dx()) + Math.abs(a.dy() - b.dy());
			assertEquals(1, distance, index + "번과 다음 칸이 붙어 있지 않다: " + a + " → " + b);
		}
	}

	@Test
	void 시계_방향으로_돈다() {
		assertEquals(new DragonTrialGlow.Pixel(0, 0), DragonTrialGlow.pixelAt(0, WIDTH, HEIGHT));
		assertEquals(new DragonTrialGlow.Pixel(1, 0), DragonTrialGlow.pixelAt(1, WIDTH, HEIGHT));
		assertEquals(new DragonTrialGlow.Pixel(WIDTH - 1, 1),
				DragonTrialGlow.pixelAt(WIDTH, WIDTH, HEIGHT));
		// 음수는 거꾸로 감긴다. 0번 바로 앞은 왼변의 맨 위에서 한 칸 아래다.
		assertEquals(new DragonTrialGlow.Pixel(0, 1), DragonTrialGlow.pixelAt(-1, WIDTH, HEIGHT));
	}

	@Test
	void 머리는_한_바퀴_시간마다_제자리로_돌아온다() {
		int perimeter = DragonTrialGlow.perimeter(WIDTH, HEIGHT);
		long start = 123_456L;
		assertEquals(DragonTrialGlow.headIndex(start, perimeter, 0),
				DragonTrialGlow.headIndex(start + DragonTrialGlow.PERIOD_MS, perimeter, 0));
		assertEquals(0, DragonTrialGlow.headIndex(0, perimeter, 0));
		assertEquals(perimeter / 2,
				DragonTrialGlow.headIndex(DragonTrialGlow.PERIOD_MS / 2, perimeter, 0));
	}

	@Test
	void 두_머리는_둘레의_반대편에_선다() {
		int perimeter = DragonTrialGlow.perimeter(WIDTH, HEIGHT);
		for (long millis = 0; millis < DragonTrialGlow.PERIOD_MS; millis += 37) {
			int first = DragonTrialGlow.headIndex(millis, perimeter, 0);
			int second = DragonTrialGlow.headIndex(millis, perimeter, 1);
			assertEquals(perimeter / 2, Math.floorMod(second - first, perimeter));
		}
	}

	@Test
	void 꼬리는_머리가_가장_진하고_뒤로_갈수록_옅어진다() {
		assertEquals(255, DragonTrialGlow.tailAlpha(0));
		for (int step = 1; step < DragonTrialGlow.TAIL_LENGTH; step++) {
			assertTrue(DragonTrialGlow.tailAlpha(step) <= DragonTrialGlow.tailAlpha(step - 1),
					step + "번째 칸이 앞 칸보다 진하다");
		}
		assertEquals(0, DragonTrialGlow.tailAlpha(DragonTrialGlow.TAIL_LENGTH));
		assertEquals(0, DragonTrialGlow.tailAlpha(-1));
	}

	@Test
	void 두_꼬리는_이어지지_않는다() {
		// 꼬리가 반 바퀴보다 길면 둘이 이어져 테두리 전체가 한 줄로 보인다.
		int perimeter = DragonTrialGlow.perimeter(WIDTH, HEIGHT);
		assertTrue(DragonTrialGlow.TAIL_LENGTH < perimeter / DragonTrialGlow.HEADS);
	}

	@Test
	void 불투명도를_얹는다() {
		assertEquals(0x80B45CFF, DragonTrialGlow.withAlpha(0xB45CFF, 0x80));
		assertEquals(0xFFB45CFF, DragonTrialGlow.withAlpha(0x12B45CFF, 300));
	}
}
