package com.sharedfate.client.trial;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.trial.TrialRouletteScreen.Spin;
import com.sharedfate.net.TrialRoulettePayload;
import com.sharedfate.net.TrialRoulettePayload.TrialOption;
import com.sharedfate.sync.TrialRoulette;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시련 룰렛 화면이 <b>언제 무엇을 띄우고 언제 닫히는가</b>.
 *
 * <h2>왜 이 시험이 있는가</h2>
 *
 * <p>이 화면이 틀리는 방식은 셋이고 셋 다 조용하다.
 *
 * <ul>
 *   <li><b>화면은 A 에서 멈췄는데 실제로는 B 가 걸린다.</b> 서버가 「거꾸로 맞추기」로 결과를
 *       마지막 칸에 놓는데 화면이 그 셈을 안 따라 하면 그렇게 된다. 빌드도 로그도 조용하고,
 *       플레이어는 화면을 못 믿게 된다.</li>
 *   <li><b>감속 곡선이 어긋난다.</b> 그러면 화면의 「몇 번째 칸」과 서버 로그의 「몇 번째 칸」이
 *       달라져, 위 사고가 났을 때 원인을 짚을 근거까지 사라진다.</li>
 *   <li><b>화면이 안 닫힌다.</b> ESC 가 막혀 있으므로 플레이어가 드래곤 전투 한가운데에
 *       갇힌다.</li>
 * </ul>
 *
 * <p>그리는 일은 시험할 수 없으므로 {@link Spin} 이 그 판단을 전부 들고 있다. 여기서 보는 것도
 * 그것뿐이다.
 */
class TrialRouletteScreenTest {

	/** 패킷을 하나라도 만들면 그 클래스의 코덱이 초기화된다. 레지스트리가 서 있어야 한다. */
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 기본 연출 길이. 서버가 실어 보내는 값이다. */
	private static final int SPIN = TrialRoulette.TOTAL_TICKS;

	/** 돌기가 끝날 때까지 칸을 차례로 모은다. 같은 칸이 이어지면 한 번만 담는다. */
	private static List<Integer> framesOf(Spin spin) {
		List<Integer> frames = new ArrayList<>();
		int previous = -1;
		for (int tick = 0; tick <= spin.totalTicks(); tick++) {
			int frame = spin.frameAt(tick);
			if (frame >= 0 && frame != previous) {
				frames.add(frame);
				previous = frame;
			}
		}
		return frames;
	}

	/**
	 * 마지막에 보이는 카드가 결과다. 후보가 몇 장이든.
	 *
	 * <p>{@code TrialRoulette.candidateAt} 과 같은 셈을 하지 않으면 여기서 걸린다.
	 */
	@Test
	void 마지막_칸이_뽑힌_카드다() {
		for (int count = 1; count <= 6; count++) {
			for (int result = 0; result < count; result++) {
				Spin spin = new Spin(count, result, SPIN);
				assertEquals(result, spin.optionAt(spin.lastFrame()),
						"후보 " + count + "장, 결과 " + result + " — 마지막 칸이 결과가 아니다."
								+ " 화면과 서버가 서로 다른 시련을 가리킨다");
				// 돌기가 끝나는 마지막 틱에도 같아야 한다. 칸 경계 계산이 한 칸 밀리면 여기서
				// 걸린다.
				assertEquals(result, spin.shownOption(SPIN - 1));
			}
		}
	}

	/** 멈춘 뒤에는 언제나 결과가 보인다. 붙잡아 두는 60틱 내내. */
	@Test
	void 멈춘_뒤에는_계속_결과가_보인다() {
		Spin spin = new Spin(4, 2, SPIN);
		for (int tick = SPIN; tick <= spin.totalTicks(); tick++) {
			assertTrue(spin.revealed(tick), tick + "틱에 아직 돌고 있다");
			assertEquals(2, spin.shownOption(tick));
		}
	}

	/** 칸이 하나도 안 빠지고 0번부터 차례로 나온다. 건너뛰면 글자가 튄다. */
	@Test
	void 칸이_빠짐없이_차례로_나온다() {
		Spin spin = new Spin(3, 1, SPIN);
		List<Integer> frames = framesOf(spin);
		assertEquals(TrialRoulette.gaps().length, frames.size(),
				"칸 수가 서버의 간격 개수와 다르다");
		for (int index = 0; index < frames.size(); index++) {
			assertEquals(index, frames.get(index), "칸이 건너뛰었다");
		}
	}

	/** 카드도 한 칸씩 차례로 넘어간다. 두 칸씩 뛰면 돌아가는 것으로 안 보인다. */
	@Test
	void 카드도_한_장씩_차례로_넘어간다() {
		Spin spin = new Spin(5, 3, SPIN);
		for (int frame = 1; frame <= spin.lastFrame(); frame++) {
			assertEquals((spin.optionAt(frame - 1) + 1) % 5, spin.optionAt(frame),
					frame + "번째 칸에서 카드가 한 장씩 넘어가지 않았다");
		}
	}

	/**
	 * 감속 곡선이 서버의 것과 <b>글자 그대로</b> 같다.
	 *
	 * <p>이 화면이 쓰는 칸 경계를 간격으로 되돌려 {@link TrialRoulette#gaps()} 와 맞댄다.
	 * 누가 편하다고 숫자를 박아 넣으면 그 순간 걸린다.
	 */
	@Test
	void 감속_곡선이_서버와_같다() {
		assertArrayEquals(TrialRoulette.gaps(), Spin.stepGaps(),
				"화면의 감속 곡선이 서버와 어긋났다. 서버 로그의 「몇 번째 칸」과 화면이 안 맞는다");
	}

	/** 뒤로 갈수록 느려진다. 곡선이 같다는 것만으로는 이 뜻이 안 남는다. */
	@Test
	void 뒤로_갈수록_느려진다() {
		int[] gaps = Spin.stepGaps();
		for (int index = 1; index < gaps.length; index++) {
			assertTrue(gaps[index] >= gaps[index - 1],
					index + "번째 칸에서 오히려 빨라졌다");
		}
		assertTrue(gaps[gaps.length - 1] > gaps[0], "처음과 끝의 빠르기가 같으면 뽑는 느낌이 없다");
	}

	/** 돌기 시간을 다 합치면 서버가 아는 전체 길이다. */
	@Test
	void 돌기_길이가_서버와_같다() {
		int sum = 0;
		for (int gap : Spin.stepGaps()) {
			sum += gap;
		}
		assertEquals(TrialRoulette.TOTAL_TICKS, sum);
	}

	/** {@code spinTicks} 가 0 이하면 돌지 않고 처음부터 결과를 보여 준다. */
	@Test
	void 돌_시간이_없으면_처음부터_결과다() {
		for (int spinTicks : new int[] {0, -1, -1000}) {
			Spin spin = new Spin(4, 3, spinTicks);
			assertTrue(spin.revealed(0), "돌 시간이 없는데 돌고 있다");
			assertEquals(3, spin.shownOption(0));
			assertEquals(0, spin.frameCount());
			assertEquals(TrialRouletteScreen.HOLD_TICKS, spin.totalTicks(),
					"돌지 않아도 읽을 시간은 그대로 있어야 한다");
		}
	}

	/**
	 * 돌기와 멈춤이 끝나면 닫힌다고 판정한다.
	 *
	 * <p>이것이 깨지면 플레이어가 화면에 갇힌다 — ESC 가 막혀 있어 빠져나갈 길이 없다.
	 */
	@Test
	void 다_끝나면_닫힌다() {
		Spin spin = new Spin(3, 0, SPIN);
		assertEquals(SPIN + TrialRouletteScreen.HOLD_TICKS, spin.totalTicks());
		assertFalse(spin.finished(spin.totalTicks() - 1), "아직 읽는 중인데 닫으려 한다");
		assertTrue(spin.finished(spin.totalTicks()));
		assertTrue(spin.finished(spin.totalTicks() + 1000));
	}

	/** 돌 시간이 없어도 반드시 닫힌다. 결과만 보여 주는 화면이 영영 남으면 안 된다. */
	@Test
	void 돌지_않는_연출도_닫힌다() {
		Spin spin = new Spin(1, 0, 0);
		assertFalse(spin.finished(0));
		assertTrue(spin.finished(TrialRouletteScreen.HOLD_TICKS));
	}

	/** 후보가 한 장이면 어느 칸이든 같은 카드다. 그것도 정직한 연출이다. */
	@Test
	void 후보가_한_장이면_언제나_같은_카드다() {
		Spin spin = new Spin(1, 0, SPIN);
		for (int tick = 0; tick <= spin.totalTicks(); tick++) {
			assertEquals(0, spin.shownOption(tick));
		}
	}

	/**
	 * 결과 번호가 범위 밖이어도 터지지 않는다.
	 *
	 * <p>{@code resultIndex} 는 패킷에 실려 <b>밖에서 오는 값</b>이다. 배열을 그대로 찌르면
	 * 드래곤 전투 중에 클라이언트가 죽는다.
	 */
	@Test
	void 결과_번호가_범위_밖이어도_터지지_않는다() {
		for (int given : new int[] {-5, -1, 3, 99, Integer.MAX_VALUE}) {
			Spin spin = new Spin(3, given, SPIN);
			assertTrue(spin.resultIndex() >= 0 && spin.resultIndex() < 3,
					"결과 번호가 후보 밖이다: " + spin.resultIndex());
			for (int tick = 0; tick <= spin.totalTicks(); tick++) {
				int shown = spin.shownOption(tick);
				assertTrue(shown >= 0 && shown < 3, tick + "틱에 없는 카드를 가리킨다: " + shown);
			}
			assertEquals(spin.resultIndex(), spin.optionAt(spin.lastFrame()));
		}
	}

	/** 후보가 하나도 없어도 터지지 않는다. 화면은 애초에 안 열리지만 계산은 견뎌야 한다. */
	@Test
	void 후보가_없어도_터지지_않는다() {
		Spin spin = new Spin(0, 7, SPIN);
		assertEquals(0, spin.resultIndex());
		assertEquals(0, spin.shownOption(0));
		assertEquals(0, spin.optionAt(spin.lastFrame()));
	}

	/** 빈 후보로는 화면을 열지 않는다. */
	@Test
	void 빈_후보로는_열지_않는다() {
		assertFalse(TrialRouletteScreen.shouldOpen(null));
		assertFalse(TrialRouletteScreen.shouldOpen(
				new TrialRoulettePayload("첫 크리스탈", 0, SPIN, List.of())));
		assertTrue(TrialRouletteScreen.shouldOpen(new TrialRoulettePayload("첫 크리스탈", 0, SPIN,
				List.of(new TrialOption("gravity", "중력 역전", "위아래가 뒤집힌다")))));
	}

	/** 패킷에서 그대로 받아 온다. 화면이 서버가 정한 길이와 결과를 고쳐 쓰면 안 된다. */
	@Test
	void 패킷_값을_그대로_쓴다() {
		TrialRoulettePayload payload = new TrialRoulettePayload("마지막 크리스탈", 1, 40,
				List.of(new TrialOption("a", "가", "가나다"), new TrialOption("b", "나", "라마바")));
		Spin spin = Spin.of(payload);
		assertEquals(2, spin.optionCount());
		assertEquals(1, spin.resultIndex());
		assertEquals(40, spin.spinTicks());
		assertEquals(1, spin.optionAt(spin.lastFrame()),
				"돌기가 짧아도 마지막 칸은 결과여야 한다");
	}

	/**
	 * 돌기가 짧으면 뒤쪽 칸이 아예 오지 않는다. 그래도 마지막에 보이는 것은 결과다.
	 *
	 * <p>거꾸로 맞추는 기준을 «열다섯 번째 칸»으로 못박아 두면 여기서 어긋난다.
	 */
	@Test
	void 돌기가_짧아도_마지막_칸이_결과다() {
		for (int spinTicks = 1; spinTicks <= SPIN + 40; spinTicks++) {
			Spin spin = new Spin(4, 2, spinTicks);
			assertEquals(2, spin.shownOption(spinTicks - 1),
					spinTicks + "틱짜리 연출의 마지막 칸이 결과가 아니다");
			assertEquals(2, spin.shownOption(spinTicks));
		}
	}

	/**
	 * 이름이 길면 배율을 낮춘다.
	 *
	 * <p>이 저장소는 카드 설명이 길어 아이콘이 통째로 사라진 사고를 이미 겪었다. 여기서 배율을
	 * 안 낮추면 긴 시련 이름이 판 밖으로 나간다.
	 */
	@Test
	void 자리가_없으면_이름_배율을_낮춘다() {
		int[] scales = {2, 1};
		assertEquals(2, TrialRouletteScreen.fitScale(scales, 50, 200), "자리가 넉넉하면 크게");
		assertEquals(2, TrialRouletteScreen.fitScale(scales, 100, 200), "딱 맞으면 그대로 크게");
		assertEquals(1, TrialRouletteScreen.fitScale(scales, 101, 200), "한 픽셀만 넘쳐도 낮춘다");
		assertEquals(1, TrialRouletteScreen.fitScale(scales, 9999, 200),
				"어느 배율도 안 들어가면 가장 작은 것으로 버틴다");
	}
}
